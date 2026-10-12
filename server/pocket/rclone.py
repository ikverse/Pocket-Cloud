"""Running rclone's S3 server behind the gateway.

rclone does the S3 protocol, file storage and the signature check for every key. For each key, the auth hook
(pocket.authproxy) tells rclone the key's secret and which folders it may see: a "combine" of two "union"
remotes, the app's shared space and the device's private space. Each union puts internal storage first, so
new files land there; the "_nc" variant marks internal storage "no create" so new files go to the SD card.
"""

import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def remote_name(app_id, space, nc=False):
    if space == "shared":
        base = f"p_{app_id}_s"
    else:
        base = f"p_{app_id}_d_{space.split(':', 1)[1]}"
    return base + ("_nc" if nc else "")


def _q(path, tag=""):
    return '"' + path.replace("\\", "/") + tag + '"'


def write_config(path, layout, registry):
    """Write rclone.conf with two union remotes (normal and SD-first) for every space of every app.
    With no SD card both are just internal storage."""
    lines = []
    for app in registry.apps():
        spaces = ["shared"] + ["device:" + d["uid"] for d in registry.devices(app["id"])]
        for space in spaces:
            layout.ensure_space(app["id"], space)
            internal = layout.space_path("int", app["id"], space)
            for nc in (False, True):
                if layout.has_sd:
                    sd = layout.space_path("sd", app["id"], space)
                    lines += [
                        f"[{remote_name(app['id'], space, nc)}]",
                        "type = union",
                        f"upstreams = {_q(internal, ':nc' if nc else '')} {_q(sd)}",
                        "create_policy = ff",
                        "search_policy = ff",
                        "",
                    ]
                else:       # a union needs two parts; with internal storage only, the space is just that folder
                    lines += [f"[{remote_name(app['id'], space, nc)}]", "type = alias",
                              "remote = " + internal.replace("\\", "/"), ""]       # taken literally: no quotes
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def write_flags(state_dir, **flags):
    """Small file the auth hook reads; currently just whether new writes go to the SD card."""
    path = os.path.join(state_dir, "flags.json")
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(flags, f)
    os.replace(tmp, path)


class Rclone:
    def __init__(self, settings, state_dir, log):
        self.settings = settings
        self.state_dir = state_dir
        self.conf = os.path.join(state_dir, "rclone.conf")
        self.log = log
        self.proc = None
        self._lock = threading.Lock()
        self.restarts = 0
        self.started_at = None

    @property
    def port(self):
        return self.settings.get("rclone_port")

    @property
    def rc_port(self):
        return self.settings.get("rc_port")

    def command(self):
        s = self.settings.get("server")
        return [
            self.settings.get("rclone_binary"), "serve", "s3",
            "--config", self.conf,
            "--addr", f"127.0.0.1:{self.port}",
            "--auth-proxy", f"{sys.executable} -m pocket.authproxy",
            "--dir-cache-time", f"{s['dir_cache_s']}s",
            "--multipart-expiry", f"{s['multipart_expiry_h']}h",
            "--multipart-streaming-buffer-limit", f"{s['upload_buffer_mb']}Mi",
            "--server-read-timeout", "1h", "--server-write-timeout", "1h",
            "--rc", "--rc-addr", f"127.0.0.1:{self.rc_port}", "--rc-no-auth",
            "--log-file", os.path.join(self.state_dir, "logs", "rclone.log"), "-v",
        ] + (["--read-only"] if s["read_only"] else [])

    def start(self):
        with self._lock:
            self._start_locked()
        self.wait_ready()

    def _start_locked(self):
        if self.proc and self.proc.poll() is None:
            return
        self._stop_leftover()
        os.makedirs(os.path.join(self.state_dir, "logs"), exist_ok=True)
        env = dict(os.environ)
        env["PYTHONPATH"] = SERVER_DIR + os.pathsep + env.get("PYTHONPATH", "")
        env["POCKET_STATE"] = self.state_dir
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
        self.proc = subprocess.Popen(self.command(), env=env, stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
        self.started_at = time.time()

    def _stop_leftover(self, timeout=15):
        """An rclone left running by a server that died without stopping it (a crash, a hard kill) still holds
        our ports and serves the old apps and keys. If anything answers on our control port, ask it to quit."""
        try:
            self.rc("core/version")
        except Exception:
            return
        self.log("an old storage server was still running; stopping it")
        try:
            self.rc("core/quit")
        except Exception:
            pass                     # it may close the connection as it quits
        end = time.time() + timeout
        while time.time() < end:
            try:
                self.rc("core/version")
                time.sleep(0.2)
            except Exception:
                time.sleep(0.5)      # control port closed; give it a moment to release the storage port too
                return
        raise RuntimeError("an old storage server on port %s would not stop" % self.rc_port)

    def restart(self):
        """Planned restart (not counted as a crash): every key's view is rebuilt from the current settings."""
        with self._lock:
            self._stop_locked()
            self._start_locked()
        self.wait_ready()

    def wait_ready(self, timeout=90):     # a phone that has just booted (or a busy PC) can be slow to start it
        end = time.time() + timeout
        while time.time() < end:
            if self.proc.poll() is not None:
                raise RuntimeError("rclone exited with code %s; see logs/rclone.log" % self.proc.returncode)
            try:
                self.rc("core/version")
                return
            except Exception:
                time.sleep(0.1)
        raise RuntimeError("rclone did not start within %ss" % timeout)

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def stop(self):
        with self._lock:
            self._stop_locked()

    def _stop_locked(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(5)

    def kill(self):
        """Hard kill, used by the tests to simulate a crash."""
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(5)

    def ensure_running(self):
        if self._lock.locked():
            return                   # a planned restart is under way
        if not self.alive():
            self.restarts += 1
            self.log("rclone not running (exit %s); restarting" % (self.proc.returncode if self.proc else "-"))
            self.start()

    def rc(self, command, **params):
        req = urllib.request.Request(f"http://127.0.0.1:{self.rc_port}/{command}", data=json.dumps(params).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read()
        return json.loads(body) if body.strip() else {}

    def forget(self, access_keys, dirs):
        """Make rclone forget cached listings of dirs (paths like 'shared/a/b') for the given keys' views,
        after files were moved or removed behind its back."""
        if not dirs:
            return
        try:
            names = self.rc("vfs/list").get("vfses", [])
        except Exception as e:
            self.log("vfs/list failed: %s" % e)
            return
        wanted = [n for n in names if any(n.startswith(f"proxy-{k}-") for k in access_keys)]
        for name in wanted:
            for d in sorted(set(dirs)):
                try:
                    self.rc("vfs/forget", fs=name, dir=d.replace("\\", "/"))
                except urllib.error.HTTPError:
                    pass   # a view that never listed this folder has nothing to forget
                except Exception as e:
                    self.log("vfs/forget %s %s failed: %s" % (name, d, e))
