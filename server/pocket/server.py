"""Pocket Cloud server: starts rclone and the gateway, and runs the scheduler (usage scans, health checks,
nightly and weekly jobs) and the supervisor that restarts rclone if it dies.

    python -m pocket.server --state <dir> [--internal-root <dir>] [--sd-root <dir>] [--rclone <path>]
"""

import argparse
import contextlib
import datetime
import hmac
import ipaddress
import os
import signal
import sys
import threading
import time

from . import __version__
from .cards import Cards
from .gateway import Refuse, serve
from .health import Health, notify
from .jobs import JobBusy, Jobs
from .layout import FAMILY, TEMP_PREFIX, Layout, walk_files
from .panel import Admin
from .panel import serve as panel_serve
from .push import Push
from . import share as sharemod
from .rclone import Rclone, write_config, write_flags
from .registry import Registry, RegistryError
from .settings import GB, Settings
from .stats import Stats
from .usage import Usage

JOB_COPY = "Nightly copy to SD card"
JOB_MOVE = "Nightly move to SD card"
JOB_EARLY = "Early move"
JOB_UNDO = "Undo cleanup"
JOB_LOGS = "Log cleanup"
JOB_INTEGRITY = "Integrity check"


class Core:
    version = __version__

    def __init__(self, state_dir, overrides=None, echo=True):
        self.state_dir = os.path.abspath(state_dir)
        os.makedirs(os.path.join(self.state_dir, "logs"), exist_ok=True)
        self.echo = echo
        self._log_lock = threading.Lock()
        self.settings = Settings(self.state_dir, overrides)
        s = self.settings
        if not s.get("internal_root") or not (s.get("sd_root") or s.get("sd.search")):
            raise SystemExit("internal_root and sd_root (or sd search) must be set")
        # with swappable cards the SD folder is found by cards.poll() once running; until then, internal only
        self.layout = Layout(s.get("internal_root"), None if s.get("sd.search") else s.get("sd_root"), self.state_dir)
        self.layout.ensure()
        self.registry = Registry(self.state_dir)
        self.registry.ensure_family(s.get("family.alloc_gb") * GB, sum(s.pool_bytes()), s.get("new_apps"))
        self.stats = Stats(self.state_dir, notify=notify)
        self.usage = Usage(self.layout, self.registry, s)
        self.rclone = Rclone(s, self.state_dir, self.log)
        self.jobs = Jobs(self.layout, self.registry, self.usage, self.stats, self.rclone, s, self.log)
        self.health = Health(self)
        self.jobs.cool = self.health.too_hot      # nightly jobs pause while the phone is too hot
        ts = next((a for a in s.get("listen") if _is_tailscale(a)), None)
        self.push = Push(s, self.state_dir, self.log, address=ts or "127.0.0.1")
        self.tunnel = sharemod.Tunnel(s, self.state_dir, self.log)
        self.stats.enabled = lambda rule: self.settings.get("alerts").get(rule, True)
        self.admin = Admin(self.state_dir)
        self.local = threading.local()
        self.servers = []
        self.panel_servers = []
        self.share_servers = []
        self._share_lock = threading.Lock()
        self._share_stop = threading.Event()
        self.running_jobs = set()
        self.started_at = None
        self.internal_full = False
        self._gate = threading.Condition()
        self._paused = False
        self._inflight = 0
        self._stop = threading.Event()
        self._threads = []
        self._sd_lock = threading.RLock()
        self._lan_lock = threading.Lock()
        self._lan_stop = threading.Event()
        self._conflicts_lock = threading.Lock()
        self._conflicts_cache = {}
        self.registry.listeners.append(self._registry_changed)
        self.cards = Cards(self)

    # ---- logging ----
    def log(self, msg):
        line = f"{datetime.datetime.now().isoformat(timespec='seconds')} {msg}"
        with self._log_lock:
            with open(os.path.join(self.state_dir, "logs", "pocket.log"), "a", encoding="utf-8") as f:
                f.write(line + "\n")
        if self.echo:
            print(line, file=sys.stderr, flush=True)

    # ---- start and stop ----
    def start(self):
        self.log(f"Pocket Cloud {self.version} starting")
        self.cards.poll()                     # which card is in, before anything uses the SD folder
        self.clean_leftovers()
        write_config(self.rclone.conf, self.layout, self.registry)
        self.usage.scan()
        self.update_flags(force=True)
        self.rclone.start()
        self.started_at = time.time()
        port, panel_port = self.settings.get("storage_port"), self.settings.get("panel_port")
        for host in self.settings.get("listen"):
            t = threading.Thread(target=serve, args=(self, host, port, self._stop), daemon=True, name="listen-" + host)
            t.start()
            t.join(1)
            t = threading.Thread(target=panel_serve, args=(self, self.admin, host, panel_port, self._stop), daemon=True,
                                 name="panel-listen-" + host)
            t.start()
            t.join(1)
        if self.settings.get("lan.enabled"):
            self.set_lan(True)
        self.push.apply()
        self.set_share(self.settings.get("share.enabled"))
        loop = threading.Thread(target=self._loop, name="scheduler", daemon=True)
        loop.start()
        self._threads.append(loop)
        self.stats.event("restart", "", "info", "Pocket Cloud started.")

    def stop(self):
        self.log("Pocket Cloud stopping")
        self._stop.set()
        for srv in self.servers + self.panel_servers:
            srv.shutdown()
            srv.server_close()
        for t in self._threads:
            t.join(10)
        self.push.stop()
        self.tunnel.stop()
        for srv in self.share_servers:
            srv.shutdown()
            srv.server_close()
        self.rclone.stop()
        self.registry.close()
        self.stats.close()

    def clean_leftovers(self):
        """After a crash or power cut: remove half-written uploads and half-copied files. Nothing is running yet,
        so every such file is from before the restart."""
        n = 0
        tops = [self.layout.apps_root("int"), os.path.join(self.layout.root("int"), "_staging")]
        if self.layout.has_sd:
            tops += [self.layout.apps_root("sd"), os.path.join(self.layout.root("sd"), "_staging")]
        for top in tops:
            for d, _, files in os.walk(top):
                for f in files:
                    if f.startswith(TEMP_PREFIX) or d.endswith("_staging"):
                        try:
                            os.remove(os.path.join(d, f))
                            n += 1
                        except OSError:
                            pass
        if n:
            self.log(f"removed {n} half-written files left by an interrupted run")

    def wait_listening(self, timeout=10):
        want = len(self.settings.get("listen")) + (1 if self.settings.get("lan.enabled") and self.settings.get("lan_address") else 0)
        end = time.time() + timeout
        while time.time() < end and len(self.servers) < want:
            time.sleep(0.05)

    # ---- the LAN fast path: the gateway on the home Wi-Fi address as well ----
    def set_lan(self, enabled):
        """Start or stop the gateway listener on lan_address (settings lan.enabled). The control panel never
        listens there."""
        addr, port = self.settings.get("lan_address"), self.settings.get("storage_port")
        with self._lan_lock:
            running = [s for s in self.servers if s.server_address[0] == addr and addr not in self.settings.get("listen")]
            if enabled and addr and not running:
                self._lan_stop = threading.Event()
                threading.Thread(target=serve, args=(self, addr, port, self._lan_stop), daemon=True, name="listen-lan").start()
            elif not enabled:
                self._lan_stop.set()
                for srv in running:
                    srv.shutdown()
                    srv.server_close()
                    self.servers.remove(srv)
                    self.log(f"gateway stopped listening on {addr}:{port}")

    # ---- registry changes reach rclone through its config file ----
    def _registry_changed(self):
        write_config(self.rclone.conf, self.layout, self.registry)

    # ---- enrollment ----
    def enroll(self, req, ip, host_header):
        secret = str(req.get("secret") or "")
        if not hmac.compare_digest(secret.encode(), self.settings.get("enroll_secret").encode()):
            raise RegistryError("Forbidden", "The enrollment secret is not right.")
        int_limit, sd_limit = self.settings.pool_bytes()
        # a device may list its own addresses; the first LAN or Tailscale one (other than the one it came
        # from) becomes its second address, so its key works from either
        addresses = req.get("addresses")
        alt = _alt_address(addresses, ip) if isinstance(addresses, list) else None
        res = self.registry.enroll(str(req.get("app") or ""), str(req.get("device_id") or ""),
                                   str(req.get("device_name") or ""), ip, self.settings.get("new_apps"),
                                   int_limit + sd_limit, ip_alt=alt, set_alt=isinstance(addresses, list))
        app, dev = res["app"], res["device"]
        if res["app_created"]:
            self.log(f"new app {app['id']} from {dev['name']} ({ip})")
            self.stats.event("new_app", app["id"], "info", f"New app: {app['id']}, first connected from {dev['name']}.")
            if res["short_allocation"]:
                self.stats.raise_alert("short_alloc", app["id"], "warn",
                                       f"{app['id']} got {app['alloc_bytes'] / GB:.2f} GB: the pool had no more unpromised space.")
        if res["device_created"]:
            self.log(f"new device {dev['name']} ({dev['uid']}) for {app['id']} at {ip}")
            self.stats.event("new_device", f"{app['id']}/{dev['uid']}", "info", f"New device: {dev['name']} joined {app['id']}.")
        elif res["ip_moved"]:
            self.log(f"device {dev['uid']} of {app['id']} moved to {ip}")
            self.stats.event("device_moved", f"{app['id']}/{dev['uid']}", "info",
                             f"{dev['name']} ({app['id']}) enrolled again from a new address, {ip}.")
        if app["status"] != "active":
            raise RegistryError("AppWaiting", "This app is waiting for approval in the Pocket Cloud control panel.")
        port = self.settings.get("storage_port")
        endpoint = "http://" + (host_header or f"127.0.0.1:{port}")
        ts = next((a for a in self.settings.get("listen") if _is_tailscale(a)), None)
        lan = self.settings.get("lan_address") if self.settings.get("lan.enabled") else ""
        return {
            "app": app["id"], "device_id": dev["uid"], "access_key": dev["access_key"], "secret_key": dev["secret"],
            "endpoint": endpoint,
            "endpoints": {"tailscale": f"http://{ts}:{port}" if ts else endpoint, "lan": f"http://{lan}:{port}" if lan else None},
            "region": "us-east-1", "buckets": ["shared", "device"] + (["family"] if app.get("family") else []),
            "allocation_bytes": app["alloc_bytes"],
            "push": self.push.info(app["id"], host=ts or (host_header or "127.0.0.1").rsplit(":", 1)[0]),
        }

    # ---- a change an app should hear about (push): the family's changes reach every member ----
    def notify_change(self, app_id):
        if app_id == FAMILY:
            for a in self.registry.members():
                self.push.changed(a["id"])
        else:
            self.push.changed(app_id)

    # ---- share links (share.py): the listener, the tunnel, and the device endpoints ----
    def set_share(self, enabled):
        """Start or stop the share listener (on every gateway address) and the quick tunnel."""
        port = self.settings.get("share.port")
        with self._share_lock:
            if enabled and not self.share_servers:
                self._share_stop = threading.Event()
                for host in self.settings.get("listen"):
                    threading.Thread(target=sharemod.serve, args=(self, host, port, self._share_stop), daemon=True,
                                     name="share-listen-" + host).start()
            elif not enabled:
                self._share_stop.set()
                for srv in self.share_servers:
                    srv.shutdown()
                    srv.server_close()
                self.share_servers.clear()
        if enabled and self.tunnel.wanted():
            self.tunnel.start()
        elif not self.tunnel.wanted():
            self.tunnel.stop()

    def share_base(self):
        """Where share links point: the named tunnel's address, else the quick tunnel's, else the tailnet."""
        s = self.settings.get("share")
        if s["public_url"]:
            return s["public_url"].rstrip("/"), True
        if self.tunnel.public_url:
            return self.tunnel.public_url, True
        ts = next((a for a in self.settings.get("listen") if _is_tailscale(a)), None) or "127.0.0.1"
        return f"http://{ts}:{s['port']}", False

    def _share_public(self, sh):
        base, public = self.share_base()
        return {"token": sh["token"], "url": f"{base}/s/{sh['token']}", "public": public,
                "bucket": "shared" if sh["space"] == "shared" else "device", "key": sh["key"],
                "created": sh["created"], "expires": sh["expires"], "downloads": sh["downloads"],
                "max_downloads": sh["max_downloads"], "has_password": bool(sh["password_hash"])}

    def share_create(self, app, dev, body):
        if not self.settings.get("share.enabled"):
            raise Refuse(403, "SharesOff", "Share links are switched off in the Pocket Cloud control panel.")
        bucket, key = str(body.get("bucket", "")), str(body.get("key", ""))
        if bucket not in ("shared", "device"):
            raise Refuse(400, "BadRequest", "bucket is 'shared' or 'device'.")
        space = "shared" if bucket == "shared" else "device:" + dev["uid"]
        if not any(p and os.path.isfile(p) for p in (self.layout.key_path(s, app["id"], space, key) for s in self.layout.storages())):
            raise Refuse(404, "NoSuchKey", "There is no such file to share.")
        try:
            expires_s = int(body.get("expires_s") or sharemod.DEFAULT_EXPIRES_S)
            max_dl = body.get("max_downloads")
            max_dl = int(max_dl) if max_dl else None
        except (TypeError, ValueError):
            raise Refuse(400, "BadRequest", "expires_s and max_downloads are whole numbers.")
        if not 60 <= expires_s <= sharemod.MAX_EXPIRES_S:
            raise Refuse(400, "BadRequest", "expires_s is between 60 and %d seconds (30 days)." % sharemod.MAX_EXPIRES_S)
        if max_dl is not None and max_dl < 1:
            raise Refuse(400, "BadRequest", "max_downloads is at least 1.")
        password = body.get("password")
        pw_hash = sharemod.hash_password(str(password)) if password else None
        sh = self.registry.add_share(app["id"], dev["uid"], space, key, time.time() + expires_s, pw_hash, max_dl)
        self.log(f"share link for {app['id']} {bucket}/{key} by {dev['name']}, {expires_s // 3600} h")
        return self._share_public(sh)

    def share_list(self, app, dev):
        now = time.time()
        return {"shares": [self._share_public(sh) for sh in self.registry.shares(app["id"], dev["uid"]) if sh["expires"] > now]}

    def share_remove(self, app, dev, query):
        token = dict(query).get("token", "")
        if not self.registry.remove_share(token, app["id"], dev["uid"]):
            raise Refuse(404, "NoSuchShare", "No such share link of yours.")
        return {"ok": True}

    # ---- POST /_pocket/mirror-report: an outside mirror (the PC) says it has a copy as of now ----
    def mirror_report(self, app, dev, body):
        note = str(body.get("note") or "")[:200]
        self.stats.mirror_report(app["id"], dev["uid"], note)
        self.log(f"mirror report from {dev['name']} ({app['id']}): {note or 'up to date'}")
        return {"ok": True, "reported": time.time()}

    # ---- GET /_pocket/conflicts: names in the caller's spaces that differ only in upper/lower case ----
    def conflicts(self, app, dev):
        """Case-insensitive storage (the SD card, Windows) can't tell such names apart; clients can warn
        before syncing. Cached a minute per device."""
        key = (app["id"], dev["uid"])
        with self._conflicts_lock:
            hit = self._conflicts_cache.get(key)
            if hit and time.time() - hit[0] < 60:
                return hit[1]
        out = []
        for bucket, space in (("shared", "shared"), ("device", "device:" + dev["uid"])):
            names = {}           # lower-cased path of a file or folder -> exact spellings, on both storages
            for storage in self.layout.storages():
                base = self.layout.space_path(storage, app["id"], space)
                for path, _ in walk_files(base):
                    parts = os.path.relpath(path, base).split(os.sep)
                    for i in range(1, len(parts) + 1):
                        p = "/".join(parts[:i])
                        names.setdefault((p.lower(), i < len(parts)), set()).add(p)
            for (_, folder), spellings in sorted(names.items()):
                if len(spellings) > 1:
                    out.append({"bucket": bucket, "type": "folder" if folder else "file", "names": sorted(spellings)})
        result = {"conflicts": out}
        with self._conflicts_lock:
            self._conflicts_cache[key] = (time.time(), result)
        return result

    # ---- what a device may still write: GET /_pocket/quota ----
    def quota(self, app, dev):
        uid = dev["uid"]
        alloc = app["alloc_bytes"]
        used_app = self.usage.app_used(app["id"])
        used_dev = self.usage.space_used(app["id"], "device:" + uid)
        cap = app.get("device_cap_bytes")
        pool = self.usage.pool()
        target = "sd" if self.internal_full and self.layout.has_sd else "int"
        disk = self.usage.free(target) - (self.usage.reserve_bytes() if target == "int" else 0)
        room = min(pool["total"] - pool["used"], disk)
        hold_private = self.reserve_hold(app, uid)
        hold_shared = self.reserve_hold(app, None)
        free = min(alloc - used_app - hold_private, room)
        if cap:
            free = min(free, cap - used_dev)
        return {
            "app": app["id"], "device_id": uid, "allocation": alloc, "used_app": used_app, "used_device": used_dev,
            "device_cap": cap, "reserve": app.get("device_reserve_bytes") or 0, "held_for_others": hold_private,
            "free": max(0, free), "free_shared": max(0, min(alloc - used_app - hold_shared, room)),
            "read_only": bool(self.settings.get("server.read_only")),
        }

    # ---- room checks for uploads ----
    def check_space(self, app, space, size, ctx):
        used = self.usage.app_used(app["id"])
        if used + size > app["alloc_bytes"]:
            if app["at_limit"] == "refuse":
                raise Refuse(403, "QuotaExceeded",
                             f"{app['id']} would go over its {app['alloc_bytes'] / GB:.2f} GB allocation.")
            ctx["note"] = "over allocation (warn only)"
        # the per-device reserve is room for private spaces: a private write spends its own device's share,
        # a shared write (or a control panel upload) must leave every device's share free
        hold = self.reserve_hold(app, ctx.get("device") if space.startswith("device:") else None)
        if hold and used + size > app["alloc_bytes"] - hold:
            raise Refuse(403, "QuotaExceeded",
                         f"{hold / GB:.2f} GB of {app['id']}'s allocation is held back as the devices' reserve.")
        cap = app.get("device_cap_bytes")
        if cap and space.startswith("device:") and self.usage.space_used(app["id"], space) + size > cap:
            raise Refuse(403, "QuotaExceeded", f"This device's private space would go over its {cap / GB:.2f} GB cap.")
        pool = self.usage.pool()
        if pool["used"] + size > pool["total"]:
            raise Refuse(507, "PoolFull", "Pocket Cloud's storage pool is full.")
        target = "sd" if self.internal_full and self.layout.has_sd else "int"
        free = self.usage.free(target)
        if target == "int" and free - size < self.usage.reserve_bytes():
            raise Refuse(507, "PhoneStorageLow", "The phone's internal storage is too full to accept this upload.")
        if target == "sd" and free < size:
            raise Refuse(507, "PoolFull", "The SD card is full.")

    def reserve_hold(self, app, device_uid=None):
        """Bytes of the app's allocation kept free for the devices other than device_uid: each device is
        guaranteed room up to the app's per-device reserve, however much the others have used."""
        reserve = app.get("device_reserve_bytes") or 0
        if not reserve:
            return 0
        return sum(max(0, reserve - self.usage.space_used(app["id"], "device:" + d["uid"]))
                   for d in self.registry.devices(app["id"]) if d["uid"] != device_uid)

    # ---- the gate: lets the gateway pause briefly while rclone is restarted on purpose ----
    @contextlib.contextmanager
    def gate(self):
        with self._gate:
            end = time.time() + 60
            while self._paused and time.time() < end:
                self._gate.wait(1)
            self._inflight += 1
        try:
            yield
        finally:
            with self._gate:
                self._inflight -= 1
                self._gate.notify_all()

    def restart_rclone_drained(self, drain_s=30, before=None):
        """Hold new requests, let running ones finish (up to drain_s), run before() if given, restart rclone,
        then let everything go on."""
        with self._gate:
            self._paused = True
            end = time.time() + drain_s
            while self._inflight and time.time() < end:
                self._gate.wait(0.5)
            unfinished = self._inflight
        try:
            if before:
                before()
            self.rclone.restart()
        finally:
            with self._gate:
                self._paused = False
                self._gate.notify_all()
        if unfinished:
            self.log(f"rclone restarted with {unfinished} requests still running after {drain_s} s")

    # ---- the SD card changes: a card swapped in or out, pulled, or put back ----
    def set_sd(self, sd_root):
        """Use the SD folder sd_root from now on, or none (internal storage only). Requests are held while
        the storage server is rebuilt for it, as for any planned restart."""
        def switch():
            self.layout.set_sd(sd_root)
            self.layout.ensure()
            write_config(self.rclone.conf, self.layout, self.registry)
            self.internal_full = False
            write_flags(self.state_dir, internal_full=False)

        with self._sd_lock:
            if self.rclone.proc is None:      # still starting: nothing to restart yet
                switch()
                self.usage.scan()
                self.update_flags(force=True)
            else:
                self.restart_rclone_drained(before=switch)
                self.usage.scan()
                self.update_flags()
        self.log("SD card in use: %s" % (sd_root or "none, internal storage only"))

    def refresh_sd(self):
        """Right after a card is put to use: move files onto it and make its nightly copy (after any job that
        is running already)."""
        for name, fn in ((JOB_MOVE, self.jobs.move), (JOB_COPY, self.jobs.copy)):
            end = time.time() + 1800
            while True:
                try:
                    self.jobs.run(name, fn)
                    break
                except JobBusy:
                    if time.time() > end:
                        raise
                    time.sleep(1)
        self.usage.scan()
        self.update_flags()

    # ---- internal pool full: new writes go to the SD card ----
    def update_flags(self, force=False):
        """Switch new writes to the SD card when the internal pool reaches redirect_pct, and back once it is
        below redirect_back_pct. rclone keeps a connected key's view, so a switch restarts it (drained)."""
        pool = self.usage.pool()
        p = self.settings.get("pool")
        if not self.layout.has_sd:
            full = False             # nowhere to send them: uploads stay on internal storage until it is full
        elif self.internal_full:
            full = pool["int_used"] >= pool["int_limit"] * p["redirect_back_pct"] / 100
        else:
            full = pool["int_used"] >= pool["int_limit"] * p["redirect_pct"] / 100
        if force:
            self.internal_full = full
            write_flags(self.state_dir, internal_full=full)
        elif full != self.internal_full:
            self.internal_full = full
            write_flags(self.state_dir, internal_full=full)
            self.log("internal pool %s: new files now go to %s" % (
                "full" if full else "has room again", "the SD card" if full else "internal storage"))
            self.restart_rclone_drained()
        return full

    # ---- scheduler and supervisor ----
    def _loop(self):
        last_scan = time.time()
        last_health = 0.0
        last_flush = time.time()
        while not self._stop.wait(2):
            now = time.time()
            try:
                self.rclone.ensure_running()
            except Exception as e:
                self.log(f"rclone restart failed: {e}")
            try:
                self.push.ensure_running()
            except Exception as e:
                self.log(f"push server restart failed: {e}")
            try:
                self.tunnel.ensure_running()
            except Exception as e:
                self.log(f"cloudflared restart failed: {e}")
            try:
                if now - self.cards.last_poll >= self.settings.get("sd.poll_s"):
                    self.cards.poll()
                if now - last_flush >= 60:
                    self.registry.flush_last_seen()
                    last_flush = now
                if now - last_scan >= self.settings.get("server.usage_scan_s"):
                    self.usage.scan()
                    self.update_flags()
                    last_scan = now
                    self._maybe_early_move()
                if now - last_health >= self.settings.get("health.every_min") * 60:
                    self.health.run()
                    last_health = now
                due = self.due_jobs(datetime.datetime.now())
                if due:
                    threading.Thread(target=self.run_jobs, args=(due,), daemon=True, name="jobs").start()
            except Exception as e:
                self.log(f"scheduler error: {type(e).__name__}: {e}")

    def _maybe_early_move(self):
        pool = self.usage.pool()
        if pool["int_used"] >= pool["int_limit"] * self.settings.get("pool.early_move_pct") / 100:
            threading.Thread(target=self.run_jobs, args=([JOB_EARLY],), daemon=True, name="early-move").start()

    def due_jobs(self, now):
        """Which scheduled jobs should run now. Missed runs (phone off or restarting) are caught up."""
        due = []
        j = self.settings.get("jobs")
        h, m = map(int, j["nightly_time"].split(":"))
        last = self.stats.last_job(JOB_MOVE)
        last_day = datetime.date.fromtimestamp(last["started"]) if last else None
        if now >= now.replace(hour=h, minute=m, second=0, microsecond=0) and last_day != now.date():
            due.append("nightly")
        ih, im = map(int, j["integrity_time"].split(":"))
        days_back = (now.weekday() - j["integrity_weekday"]) % 7
        slot = (now - datetime.timedelta(days=days_back)).replace(hour=ih, minute=im, second=0, microsecond=0)
        if slot > now:
            slot -= datetime.timedelta(days=7)
        last_i = self.stats.last_job(JOB_INTEGRITY)
        if not last_i or datetime.datetime.fromtimestamp(last_i["started"]) < slot:
            due.append(JOB_INTEGRITY)
        return due

    def run_jobs(self, names):
        for name in names:
            try:
                if name == "nightly":
                    self.jobs.run(JOB_COPY, self.jobs.copy)
                    self.cards.refresh_active()
                    self.jobs.run(JOB_MOVE, self.jobs.move)
                    self.jobs.run(JOB_UNDO, self.jobs.undo_cleanup)
                    self.jobs.run(JOB_LOGS, self.jobs.purge)
                    self.usage.scan()
                    self.update_flags()
                    self.snapshot()
                elif name == JOB_EARLY:
                    self.jobs.run(JOB_EARLY, self.jobs.move, early=True)
                    self.usage.scan()
                    self.update_flags()
                elif name == JOB_INTEGRITY:
                    self.jobs.run(JOB_INTEGRITY, self.jobs.integrity)
            except JobBusy:
                pass

    def snapshot(self):
        per_app = {}
        for app in self.registry.apps():
            d = self.usage.app_detail(app["id"])
            per_app[app["id"]] = {"int": sum(x["int"] for x in d.values()), "sd": sum(x["sd"] for x in d.values()),
                                  "files": sum(x["files"] for x in d.values())}
        self.stats.storage_snapshot(self.usage.pool(), self.usage.free("int"), self.usage.free("sd"), per_app)


def _is_tailscale(addr):
    try:
        return ipaddress.ip_address(addr) in ipaddress.ip_network("100.64.0.0/10")
    except ValueError:
        return False


def _alt_address(addresses, ip):
    """The first LAN (private) or Tailscale address in a device's list that isn't the one it came from."""
    for a in addresses[:10]:
        try:
            x = ipaddress.ip_address(str(a).strip())
        except ValueError:
            continue
        if str(x) == ip or x.is_loopback or x.is_link_local:
            continue
        if x.is_private or _is_tailscale(str(x)):
            return str(x)
    return None


def main(argv=None):
    p = argparse.ArgumentParser(prog="pocket.server")
    p.add_argument("--state", required=True)
    p.add_argument("--internal-root")
    p.add_argument("--sd-root")
    p.add_argument("--sd-search", help="where swappable SD cards appear, e.g. /storage/*-*/Android/data/com.termux/files")
    p.add_argument("--rclone")
    p.add_argument("--listen", help="comma-separated addresses")
    p.add_argument("--lan-address", help="the phone's home Wi-Fi address; used only when lan.enabled is on")
    a = p.parse_args(argv)
    overrides = {}
    if a.internal_root:
        overrides["internal_root"] = os.path.abspath(a.internal_root)
    if a.sd_root:
        overrides["sd_root"] = os.path.abspath(a.sd_root)
    if a.sd_search:
        overrides["sd"] = {"search": a.sd_search}
    if a.rclone:
        overrides["rclone_binary"] = a.rclone
    if a.listen:
        overrides["listen"] = [x.strip() for x in a.listen.split(",") if x.strip()]
    if a.lan_address is not None:
        overrides["lan_address"] = a.lan_address.strip()
    core = Core(a.state, overrides)
    done = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: done.set())
    core.start()
    while not done.wait(1):
        pass
    core.stop()


if __name__ == "__main__":
    main()
