"""The control API's data and actions: everything the web panel and the Android app show and do.

Every function takes the server core and returns plain dicts and lists (sent as JSON). Bad input raises
ApiError, which the HTTP layer turns into a 4xx answer.
"""

import datetime
import json
import os
import shutil
import threading
import time
import uuid

from . import undo
from .gateway import Refuse
from .layout import DEVICE_UID, FAMILY, safe_key, space_dir, walk_files, remove_empty_dirs
from .registry import RegistryError
from .settings import GB

DAY = 86400
READS = ("GetObject", "HeadObject", "GetObjectOther", "HeadBucket", "GetBucketLocation", "GetBucketOther")
WRITES = ("PutObject", "UploadPart", "UploadPartCopy", "CopyObject", "CreateMultipartUpload", "CompleteMultipartUpload")
LISTS = ("ListObjectsV2", "ListObjects", "ListBuckets", "ListParts", "ListMultipartUploads")
DELETES = ("DeleteObject", "DeleteObjects", "AbortMultipartUpload")
ERR = "SUM(CASE WHEN status >= 400 AND status <> 404 THEN 1 ELSE 0 END)"   # 404 "not found" is a normal answer


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def bad(message, code="BadRequest"):
    return ApiError(400, code, message)


# ======================================================================== shared pieces

def _app_or_404(core, app_id):
    app = core.registry.app(app_id)
    if not app:
        raise ApiError(404, "NoSuchApp", f"No app called {app_id}.")
    return app


def _space(app_id, space, core):
    if space == "shared":
        return "shared"
    if space.startswith("device:") and DEVICE_UID.match(space[7:]):
        if not any(d["uid"] == space[7:] for d in core.registry.devices(app_id)):
            raise ApiError(404, "NoSuchDevice", "That device is not part of this app.")
        return space
    raise bad("A space is 'shared' or 'device:<device id>'.")


def _traffic_24h(core, by=("app",)):
    cols = ", ".join(by)
    rows = core.stats.query(
        f"SELECT {cols}, COUNT(*) AS requests, {ERR} AS errors, COALESCE(SUM(bytes_in),0) AS bytes_in,"
        f" COALESCE(SUM(bytes_out),0) AS bytes_out, MAX(ts) AS last FROM requests"
        f" WHERE ts > ? AND app IS NOT NULL AND op <> 'Enroll' GROUP BY {cols}", (time.time() - DAY,))
    return {tuple(r[c] for c in by) if len(by) > 1 else r[by[0]]: r for r in rows}


def _app_usage(core, app_id):
    d = core.usage.app_detail(app_id)
    return {"int": sum(x["int"] for x in d.values()), "sd": sum(x["sd"] for x in d.values()),
            "files": sum(x["files"] for x in d.values()), "spaces": d}


def _mask(key):
    return key[:6] + "…" + key[-3:]


def _next_time(hhmm, now=None):
    now = now or datetime.datetime.now()
    h, m = map(int, hhmm.split(":"))
    t = now.replace(hour=h, minute=m, second=0, microsecond=0)
    return t if t > now else t + datetime.timedelta(days=1)


def _pool(core):
    p = core.usage.pool()
    reserve = core.settings.get("pool.reserve_gb") * GB
    p["reserve"] = reserve
    p["unpromised"] = max(0, p["total"] - p["promised"])
    p["internal_full"] = core.internal_full
    p["int_max"] = int(core.usage.free("int") + p["int_used"] - reserve)       # slider limits
    p["sd_max"] = int(core.usage.free("sd") + p["sd_used"])
    return p


# ======================================================================== overview

def overview(core):
    now = time.time()
    pool = _pool(core)
    apps = core.registry.apps()
    usage = {a["id"]: _app_usage(core, a["id"]) for a in apps}
    t = _traffic_24h(core)
    start = (int(now) // 3600) * 3600 - 23 * 3600
    hours = [start + i * 3600 for i in range(24)]
    hourly = {}
    for r in core.stats.query("SELECT app, CAST((ts - ?) / 3600 AS INTEGER) AS h, COUNT(*) AS n FROM requests"
                              " WHERE ts >= ? AND app IS NOT NULL AND op <> 'Enroll' GROUP BY app, h", (start, start)):
        if 0 <= r["h"] < 24:
            hourly.setdefault(r["app"], [0] * 24)[r["h"]] = r["n"]
    phone = core.stats.query("SELECT * FROM phone ORDER BY ts DESC LIMIT 1")
    j = core.settings.get("jobs")
    health_last = core.health.last
    passed = sum(1 for _, ok, _ in health_last if ok)
    services = [
        {"name": "Gateway", "ok": bool(core.servers), "state": "Running" if core.servers else "Stopped",
         "detail": ", ".join(f"{s.server_address[0]}:{s.server_address[1]}" for s in core.servers)},
        {"name": "Storage server (rclone)", "ok": core.rclone.alive(), "state": "Running" if core.rclone.alive() else "Stopped",
         "detail": f"up since {_hm(core.rclone.started_at)}" + (f" · {core.rclone.restarts} crash restarts" if core.rclone.restarts else "")},
        *([{"name": "Share links", "ok": bool(core.share_servers) and (not core.tunnel.wanted() or core.tunnel.alive()),
            "state": "Running" if core.share_servers else "Stopped",
            "detail": core.share_base()[0] + ("" if core.share_base()[1] else " · tailnet only, no tunnel")}]
          if core.settings.get("share.enabled") else []),
        *([{"name": "Push server", "ok": core.push.alive(), "state": "Running" if core.push.alive() else "Stopped",
            "detail": f"{core.push.address}:{core.push.port}"}]
          if core.push.enabled else []),
        {"name": "Control panel", "ok": True, "state": "Running",
         "detail": ", ".join(f"{s.server_address[0]}:{s.server_address[1]}" for s in core.panel_servers)},
        {"name": "Scheduler", "ok": True, "state": "Running", "detail": f"next: nightly jobs at {_next_time(j['nightly_time']).strftime('%a %H:%M')}"},
        # the checker itself is fine when it runs; what it finds shows up as alerts
        {"name": "Health checks", "ok": True, "warn": passed != len(health_last),
         "state": "Passing" if passed == len(health_last) else f"{len(health_last) - passed} problem{'s' if len(health_last) - passed > 1 else ''}",
         "detail": f"last {_hm(core.health.last_run)} · {passed} of {len(health_last)} passed" if health_last else "not run yet"},
    ]
    totals = {k: sum(r[k] or 0 for r in t.values()) for k in ("requests", "errors", "bytes_in", "bytes_out")}
    return {
        "now": now, "started_at": core.started_at, "version": core.version,
        "pool": pool,
        "files": sum(u["files"] for u in usage.values()),
        "apps_used": sum(u["int"] + u["sd"] for u in usage.values()),
        "app_count": sum(1 for a in apps if a["id"] != FAMILY), "device_count": len({d["uid"] for d in core.registry.devices()}),
        "apps_over_warning": sum(1 for a in apps if a["alloc_bytes"] and (usage[a["id"]]["int"] + usage[a["id"]]["sd"]) * 100 >= a["alloc_bytes"] * a["warn_pct"]),
        "traffic": totals,
        "hours": hours, "hourly": hourly,
        "allocation": [{"id": a["id"], "name": a["name"], "used": usage[a["id"]]["int"] + usage[a["id"]]["sd"],
                        "alloc": a["alloc_bytes"], "warn_pct": a["warn_pct"]} for a in apps],
        "phone": phone[0] if phone else None,
        "services": services,
        "alerts": core.stats.active_alerts(),
    }


def _hm(ts):
    if not ts:
        return "—"
    d = datetime.datetime.fromtimestamp(ts)
    return f"{d.day} {d:%b, %H:%M}"          # "11 Oct, 16:05", as everywhere else in the panels


# ======================================================================== storage

def storage(core):
    pool = _pool(core)
    du_int = shutil.disk_usage(core.layout.root("int"))
    x = pool["detail"]
    system_int = x["state"] + x["int_undo"]
    internal = {"total": du_int.total, "free": du_int.free, "data": pool["apps_int"], "system": system_int,
                "other": max(0, du_int.total - du_int.free - pool["apps_int"] - system_int)}
    if core.layout.has_sd:
        du_sd = shutil.disk_usage(core.layout.root("sd"))
        sd_pocket = pool["apps_sd"] + x["sd_copy"] + x["sd_undo"]
        sd = {"present": True, "total": du_sd.total, "free": du_sd.free, "data": pool["apps_sd"], "copy": x["sd_copy"],
              "undo": x["sd_undo"], "other": max(0, du_sd.total - du_sd.free - sd_pocket)}
    else:
        sd = {"present": False, "total": 0, "free": 0, "data": 0, "copy": 0, "undo": 0, "other": 0}
    rows = core.stats.query("SELECT ts, int_used, sd_used FROM storage WHERE ts > ? ORDER BY ts", (time.time() - 31 * DAY,))
    per_day = {}
    for r in rows:
        per_day[datetime.date.fromtimestamp(r["ts"]).isoformat()] = r
    growth = [{"day": d, "int": r["int_used"], "sd": r["sd_used"]} for d, r in sorted(per_day.items())]
    today = datetime.date.today().isoformat()
    if not growth or growth[-1]["day"] != today:
        growth.append({"day": today, "int": pool["int_used"], "sd": pool["sd_used"]})
    per_day_growth = None
    if len(growth) >= 2:
        days = (datetime.date.fromisoformat(growth[-1]["day"]) - datetime.date.fromisoformat(growth[0]["day"])).days or 1
        per_day_growth = ((growth[-1]["int"] + growth[-1]["sd"]) - (growth[0]["int"] + growth[0]["sd"])) / days
    apps = []
    for a in core.registry.apps():
        u = _app_usage(core, a["id"])
        apps.append({"id": a["id"], "name": a["name"], "int": u["int"], "sd": u["sd"], "files": u["files"],
                     "move_days": a["move_days"]})
    return {"internal": internal, "sd": sd, "pool": pool, "growth": growth, "growth_per_day": per_day_growth,
            "apps": apps, "early_move_pct": core.settings.get("pool.early_move_pct")}


# ======================================================================== apps

def apps_list(core):
    pool = _pool(core)
    t = _traffic_24h(core)
    devices = core.registry.devices()
    out = []
    for a in core.registry.apps():
        u = _app_usage(core, a["id"])
        devs = [d for d in devices if d["app_id"] == a["id"]]
        tr = t.get(a["id"], {})
        last = max([d["last_seen"] or 0 for d in devs] + [tr.get("last") or 0]) or None
        out.append({**_app_public(a), "used": u["int"] + u["sd"], "files": u["files"],
                    "devices": [{"uid": d["uid"], "name": d["name"]} for d in devs],
                    "requests": tr.get("requests", 0), "errors": tr.get("errors", 0) or 0,
                    "bytes_in": tr.get("bytes_in", 0), "bytes_out": tr.get("bytes_out", 0), "last_used": last})
    return {"apps": out, "pool": {"total": pool["total"], "promised": pool["promised"], "unpromised": pool["unpromised"]},
            "defaults": core.settings.get("new_apps")}


def _app_public(a):
    return {"id": a["id"], "name": a["name"], "notes": a["notes"], "status": a["status"], "alloc": a["alloc_bytes"],
            "warn_pct": a["warn_pct"], "at_limit": a["at_limit"], "move_days": a["move_days"],
            "device_cap": a["device_cap_bytes"], "device_reserve": a.get("device_reserve_bytes"),
            "family": bool(a.get("family")), "is_family": a["id"] == FAMILY,
            "created_at": a["created_at"], "created_by": a["created_by"]}


def app_detail(core, app_id):
    a = _app_or_404(core, app_id)
    pool = _pool(core)
    u = _app_usage(core, app_id)
    t = _traffic_24h(core, ("app", "device"))
    ta = _traffic_24h(core).get(app_id, {})
    devs = []
    for d in core.registry.devices(app_id):
        tr = t.get((app_id, d["uid"]), {})
        sp = u["spaces"].get("device:" + d["uid"], {"int": 0, "sd": 0, "files": 0})
        devs.append({"uid": d["uid"], "name": d["name"], "ip": d["ip"], "ip_alt": d.get("ip_alt"), "key": _mask(d["access_key"]),
                     "blocked": bool(d["blocked"]), "private": sp["int"] + sp["sd"], "requests": tr.get("requests", 0),
                     "last_seen": max(d["last_seen"] or 0, tr.get("last") or 0) or None, "created_at": d["created_at"]})
    shared = u["spaces"].get("shared", {"int": 0, "sd": 0, "files": 0})
    start = datetime.date.today() - datetime.timedelta(days=6)
    days = [(start + datetime.timedelta(days=i)).isoformat() for i in range(7)]
    series = {k: [0] * 7 for k in ("reads", "writes", "listings", "deletes")}
    t0 = time.mktime(start.timetuple())
    for r in core.stats.query("SELECT CAST((ts - ?) / 86400 AS INTEGER) AS d, op, COUNT(*) AS n FROM requests"
                              " WHERE app = ? AND ts >= ? GROUP BY d, op", (t0, app_id, t0)):
        if not 0 <= r["d"] < 7:
            continue
        kind = ("reads" if r["op"] in READS else "writes" if r["op"] in WRITES else
                "listings" if r["op"] in LISTS else "deletes" if r["op"] in DELETES else None)
        if kind:
            series[kind][r["d"]] += r["n"]
    used = u["int"] + u["sd"]
    return {**_app_public(a), "used": used, "int": u["int"], "sd": u["sd"], "files": u["files"],
            "shared": shared["int"] + shared["sd"], "devices": devs,
            "requests": ta.get("requests", 0), "errors": ta.get("errors", 0) or 0,
            "bytes_in": ta.get("bytes_in", 0), "bytes_out": ta.get("bytes_out", 0),
            "days": days, "series": series,
            "alloc_min": used, "alloc_max": a["alloc_bytes"] + pool["unpromised"], "unpromised": pool["unpromised"]}


def app_update(core, app_id, body):
    a = _app_or_404(core, app_id)
    fields = {}
    if "name" in body:
        name = str(body["name"]).strip()
        if not 1 <= len(name) <= 60:
            raise bad("A display name is 1-60 characters.")
        fields["name"] = name
    if "notes" in body:
        fields["notes"] = str(body["notes"])[:500]
    if "warn_pct" in body:
        fields["warn_pct"] = _num(body["warn_pct"], 50, 95, "Warn at", int)
    if "at_limit" in body:
        if body["at_limit"] not in ("refuse", "warn"):
            raise bad("At 100% is 'refuse' or 'warn'.")
        fields["at_limit"] = body["at_limit"]
    if "move_days" in body:
        fields["move_days"] = _num(body["move_days"], 1, 60, "Move to SD card after", int)
    if "device_cap" in body:
        cap = body["device_cap"]
        alloc = int(body.get("alloc", a["alloc_bytes"]))
        fields["device_cap_bytes"] = None if not cap else _num(cap, 0, alloc, "Cap per device", int)
    if "device_reserve" in body:
        res = body["device_reserve"]
        alloc = int(body.get("alloc", a["alloc_bytes"]))
        n = max(1, len(core.registry.devices(app_id)))
        fields["device_reserve_bytes"] = None if not res else _num(res, 0, alloc // n, "Reserve per device", int)
    if "status" in body:
        if body["status"] not in ("active", "waiting"):
            raise bad("Status is 'active' or 'waiting'.")
        fields["status"] = body["status"]
    restart = False
    if "family" in body:
        if app_id == FAMILY:
            raise bad("The family bucket itself is not a member of anything.")
        fields["family"] = 1 if body["family"] else 0
        restart = fields["family"] != a["family"]      # every member's view is rebuilt by the storage server
    if "alloc" in body:
        pool = core.usage.pool()
        used = core.usage.app_used(app_id)
        try:
            core.registry.set_allocation(app_id, int(body["alloc"]), pool["total"], used)
        except RegistryError as e:
            raise bad(str(e), e.code)
    if fields:
        core.registry.update_app(app_id, **fields)
    if restart:
        threading.Thread(target=core.restart_rclone_drained, daemon=True).start()
    return app_detail(core, app_id)


def app_create(core, body):
    app_id = str(body.get("id", "")).strip()
    try:
        core.registry.create_app(app_id, body.get("name") or app_id, core.settings.get("new_apps"),
                                 sum(core.settings.pool_bytes()), "control panel")
    except RegistryError as e:
        raise bad(str(e), e.code)
    for s in core.layout.storages():
        os.makedirs(core.layout.space_path(s, app_id, "shared"), exist_ok=True)
    core.stats.event("new_app", app_id, "info", f"New app: {app_id}, created in the control panel.")
    return app_detail(core, app_id)


def app_empty(core, app_id):
    """Delete every file of the app. Nothing is lost at once: all of it goes into the 14-day undo."""
    _app_or_404(core, app_id)
    now = datetime.datetime.now()
    moved = 0
    for storage in core.layout.storages():
        base = core.layout.app_dir(storage, app_id)
        for path, st in list(walk_files(base)):
            rel = os.path.relpath(path, base).split(os.sep)
            space = "shared" if rel[0] == "shared" else ("device:" + rel[1] if len(rel) > 2 else None)
            if not space:
                continue
            key = "/".join(rel[1:] if space == "shared" else rel[2:])
            dst = os.path.join(core.layout.undo_root(storage), now.strftime("%Y-%m-%d"), app_id, *rel) + "~" + now.strftime("%H%M%S%f")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(path, dst)
            undo._append_index(core.layout, storage, now.strftime("%Y-%m-%d"), {
                "time": now.isoformat(timespec="seconds"), "app": app_id, "space": space, "key": key,
                "path": os.path.relpath(dst, core.layout.undo_root(storage)), "size": st.st_size,
                "reason": "app emptied", "device": "control panel"})
            moved += 1
        for d, _, _ in os.walk(base, topdown=False):
            if d not in (base, os.path.join(base, "shared"), os.path.join(base, "devices")) and \
                    os.path.dirname(d) != os.path.join(base, "devices"):
                try:
                    os.rmdir(d)
                except OSError:
                    pass
    if app_id == FAMILY:
        members = {a["id"] for a in core.registry.members()}
        keys = [d["access_key"] for d in core.registry.devices() if d["app_id"] in members]
        core.rclone.forget(keys, ["family", ""])
    else:
        keys = [d["access_key"] for d in core.registry.devices(app_id)]
        core.rclone.forget(keys, ["shared", "device", ""])
    core.usage.scan()
    return {"ok": True, "files": moved}


def app_delete(core, app_id):
    if app_id == FAMILY:
        raise bad("The family bucket can't be deleted; empty it, or take the apps out of the family.")
    res = app_empty(core, app_id)
    for storage in core.layout.storages():
        shutil.rmtree(core.layout.app_dir(storage, app_id), ignore_errors=True)
    core.registry.remove_app(app_id)
    core.usage.scan()
    core.stats.event("app_deleted", app_id, "info", f"App {app_id} was deleted in the control panel.")
    return {"ok": True, "files": res["files"]}


def app_rotate_all(core, app_id):
    _app_or_404(core, app_id)
    for d in core.registry.devices(app_id):
        core.registry.rotate_key(app_id, d["uid"])
    return {"ok": True}


# ======================================================================== devices

def devices_list(core):
    t = _traffic_24h(core, ("device",))
    apps = {a["id"]: a["name"] for a in core.registry.apps()}
    out = {}
    for d in core.registry.devices():
        e = out.setdefault(d["uid"], {"uid": d["uid"], "name": d["name"], "ip": d["ip"], "ip_alt": d.get("ip_alt"), "apps": [], "blocked": True,
                                      "last_seen": None, "created_at": d["created_at"]})
        e["apps"].append({"id": d["app_id"], "name": apps.get(d["app_id"], d["app_id"]), "blocked": bool(d["blocked"])})
        e["blocked"] = e["blocked"] and bool(d["blocked"])
        e["last_seen"] = max(e["last_seen"] or 0, d["last_seen"] or 0) or None
        if (d["last_seen"] or 0) >= (e["last_seen"] or 0):
            e["ip"], e["ip_alt"] = d["ip"], d.get("ip_alt")
    for uid, e in out.items():
        tr = t.get(uid, {})
        e.update(requests=tr.get("requests", 0), bytes_in=tr.get("bytes_in", 0), bytes_out=tr.get("bytes_out", 0))
        e["last_seen"] = max(e["last_seen"] or 0, tr.get("last") or 0) or None
    return {"devices": sorted(out.values(), key=lambda e: -(e["last_seen"] or 0))}


def device_rename(core, uid, body):
    name = str(body.get("name", "")).strip()
    if not 1 <= len(name) <= 60:
        raise bad("A device name is 1-60 characters.")
    if not any(d["uid"] == uid for d in core.registry.devices()):
        raise ApiError(404, "NoSuchDevice", "No such device.")
    core.registry.rename_device(uid, name)
    return {"ok": True}


def device_block(core, uid, body, app_id=None):
    blocked = bool(body.get("blocked", True))
    found = False
    for d in core.registry.devices(app_id):
        if d["uid"] == uid:
            core.registry.set_blocked(d["app_id"], uid, blocked)
            found = True
    if not found:
        raise ApiError(404, "NoSuchDevice", "No such device.")
    return {"ok": True}


def device_remove(core, uid, app_id=None):
    """Remove a device (from one app, or from all). Its private files go into the 14-day undo."""
    devs = [d for d in core.registry.devices(app_id) if d["uid"] == uid]
    if not devs:
        raise ApiError(404, "NoSuchDevice", "No such device.")
    now = datetime.datetime.now()
    for d in devs:
        for storage in core.layout.storages():
            src = core.layout.space_path(storage, d["app_id"], "device:" + uid)
            if os.path.isdir(src):
                dst = os.path.join(core.layout.undo_root(storage), now.strftime("%Y-%m-%d"), d["app_id"], "devices",
                                   uid + "~" + now.strftime("%H%M%S%f"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                os.replace(src, dst)
        core.registry.remove_device(d["app_id"], uid)
    core.usage.scan()
    return {"ok": True}


def device_rotate(core, app_id, uid):
    if not any(d["uid"] == uid for d in core.registry.devices(app_id)):
        raise ApiError(404, "NoSuchDevice", "No such device.")
    core.registry.rotate_key(app_id, uid)
    return {"ok": True}


# ======================================================================== files

def _key_parts(path):
    if path in ("", None):
        return []
    parts = safe_key(path.strip("/"))
    if parts is None:
        raise bad("That file name can't be used.")
    return parts


def files_list(core, app_id, space, path=""):
    _app_or_404(core, app_id)
    space = _space(app_id, space, core)
    parts = _key_parts(path)
    entries = {}
    for storage in core.layout.storages():
        base = os.path.join(core.layout.space_path(storage, app_id, space), *parts)
        try:
            items = list(os.scandir(base))
        except (FileNotFoundError, NotADirectoryError):
            continue
        for e in items:
            if e.name.startswith(".rclone_temp_"):
                continue
            if e.is_dir():
                d = entries.setdefault(("d", e.name), {"type": "dir", "name": e.name, "files": 0, "size": 0, "storages": []})
                for _, st in walk_files(e.path):
                    d["files"] += 1
                    d["size"] += st.st_size
                d["storages"].append(storage)
            elif e.is_file() and ("f", e.name) not in entries:
                st = e.stat()
                entries[("f", e.name)] = {"type": "file", "name": e.name, "size": st.st_size, "modified": st.st_mtime,
                                          "storage": storage, "key": "/".join(parts + [e.name])}
    items = sorted(entries.values(), key=lambda x: (x["type"] != "dir", x["name"].lower()))
    return {"app": app_id, "space": space, "path": "/".join(parts), "items": items}


def file_path(core, app_id, space, key):
    _app_or_404(core, app_id)
    space = _space(app_id, space, core)
    if safe_key(key or "") is None:
        raise bad("That file name can't be used.")
    for storage in core.layout.storages():
        p = core.layout.key_path(storage, app_id, space, key)
        if p and os.path.isfile(p):
            return p
    raise ApiError(404, "NoSuchKey", "No such file.")


def _views(core, app_id, space):
    if app_id == FAMILY:
        members = {a["id"] for a in core.registry.members()}
        return [d["access_key"] for d in core.registry.devices() if d["app_id"] in members], "family"
    devs = core.registry.devices(app_id)
    if space == "shared":
        return [d["access_key"] for d in devs], "shared"
    return [d["access_key"] for d in devs if "device:" + d["uid"] == space], "device"


def file_upload(core, app_id, space, key, stream, length):
    app = _app_or_404(core, app_id)
    space = _space(app_id, space, core)
    if safe_key(key or "") is None:
        raise bad("That file name can't be used.")
    try:
        core.check_space(app, space, length, {})
    except Refuse as r:
        raise ApiError(r.status, r.code, r.message)
    storage = "sd" if core.internal_full else "int"
    dst = core.layout.key_path(storage, app_id, space, key)
    staging = os.path.join(core.layout.root(storage), "_staging")
    os.makedirs(staging, exist_ok=True)
    tmp = os.path.join(staging, uuid.uuid4().hex)
    got = 0
    try:
        with open(tmp, "wb") as f:
            while got < length:
                b = stream.read(min(256 * 1024, length - got))
                if not b:
                    raise bad("The upload was cut off.")
                f.write(b)
                got += len(b)
            f.flush()
            os.fsync(f.fileno())
        kept = undo.keep(core.layout, app_id, space, key, "overwritten", "control panel")
        for s, _, sz in kept:
            core.usage.add(app_id, space, s, -sz)
            other = core.layout.key_path(s, app_id, space, key)
            if s != storage and other and os.path.isfile(other):
                os.remove(other)          # the new version replaces a copy on the other storage
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        os.replace(tmp, dst)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    core.usage.add(app_id, space, storage, got)
    keys, prefix = _views(core, app_id, space)
    core.rclone.forget(keys, ["/".join([prefix] + key.split("/")[:-1])])
    core.notify_change(app_id)
    return {"ok": True, "key": key, "size": got, "storage": storage}


def file_delete(core, app_id, space, key):
    _app_or_404(core, app_id)
    space = _space(app_id, space, core)
    file_path(core, app_id, space, key)       # 404 if missing
    kept = undo.keep(core.layout, app_id, space, key, "deleted", "control panel")
    for storage, _, sz in kept:
        p = core.layout.key_path(storage, app_id, space, key)
        os.remove(p)
        remove_empty_dirs(os.path.dirname(p), core.layout.space_path(storage, app_id, space))
        core.usage.add(app_id, space, storage, -sz)
    keys, prefix = _views(core, app_id, space)
    core.rclone.forget(keys, ["/".join([prefix] + key.split("/")[:-1])])
    core.notify_change(app_id)
    return {"ok": True}


def folder_create(core, app_id, space, path):
    _app_or_404(core, app_id)
    space = _space(app_id, space, core)
    parts = _key_parts(path)
    if not parts:
        raise bad("A folder needs a name.")
    os.makedirs(os.path.join(core.layout.space_path("int", app_id, space), *parts), exist_ok=True)
    return {"ok": True}


# ======================================================================== undo

def undo_list(core):
    keep = core.settings.get("jobs.undo_days")
    today = datetime.date.today()
    names = {d["uid"]: d["name"] for d in core.registry.devices()}
    out = []
    for storage in core.layout.storages():
        root = core.layout.undo_root(storage)
        for day in sorted(os.listdir(root), reverse=True) if os.path.isdir(root) else []:
            idx = os.path.join(root, day, "index.jsonl")
            try:
                age = (today - datetime.date.fromisoformat(day)).days
            except ValueError:
                continue
            if not os.path.isfile(idx):
                continue
            with open(idx, encoding="utf-8") as f:
                for line in f:
                    try:
                        e = json.loads(line)
                    except ValueError:
                        continue
                    if not os.path.isfile(os.path.join(root, e["path"])):
                        continue        # already restored, or moved to the SD card's undo by the nightly copy
                    e.update(storage=storage, days_left=max(0, keep - age),
                             device_name=names.get(e.get("device"), e.get("device")))
                    out.append(e)
    out.sort(key=lambda e: e["time"], reverse=True)
    return {"items": out[:1000], "undo_days": keep}


def undo_restore(core, body):
    storage, rel = body.get("storage"), str(body.get("path", ""))
    if storage not in core.layout.storages() or ".." in rel.replace("\\", "/").split("/"):
        raise bad("Unknown undo entry.")
    root = core.layout.undo_root(storage)
    src = os.path.normpath(os.path.join(root, rel))
    if not src.startswith(root) or not os.path.isfile(src):
        raise ApiError(404, "NoSuchEntry", "That version is no longer in the undo.")
    entry = next((e for e in undo_list(core)["items"] if e["storage"] == storage and e["path"] == rel), None)
    if not entry:
        raise ApiError(404, "NoSuchEntry", "That version is no longer in the undo.")
    app_id, space, key = entry["app"], entry["space"], entry["key"]
    _app_or_404(core, app_id)
    if space != "shared" and not any("device:" + d["uid"] == space for d in core.registry.devices(app_id)):
        raise ApiError(409, "DeviceGone", "The device this file belonged to has been removed.")
    kept = undo.keep(core.layout, app_id, space, key, "replaced by a restore", "control panel")
    for s, _, sz in kept:
        p = core.layout.key_path(s, app_id, space, key)
        os.remove(p)
        core.usage.add(app_id, space, s, -sz)
    dst = core.layout.key_path(storage, app_id, space, key)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    os.replace(src, dst)
    core.usage.add(app_id, space, storage, os.path.getsize(dst))
    keys, prefix = _views(core, app_id, space)
    core.rclone.forget(keys, ["/".join([prefix] + key.split("/")[:-1])])
    core.notify_change(app_id)
    return {"ok": True, "app": app_id, "space": space, "key": key}


# ======================================================================== jobs

JOB_FNS = {
    "nightly-copy": ("Nightly copy to SD card", lambda c: c.jobs.copy, "Copies files saved today to the SD card"),
    "nightly-move": ("Nightly move to SD card", lambda c: c.jobs.move, "Moves files unchanged for each app's move age off internal storage"),
    "undo-cleanup": ("Undo cleanup", lambda c: c.jobs.undo_cleanup, "Removes deleted files kept longer than the undo period"),
    "integrity": ("Integrity check", lambda c: c.jobs.integrity, "Reads every file on the SD card and compares checksums"),
    "log-cleanup": ("Log cleanup", lambda c: c.jobs.purge, "Removes request logs older than the log period"),
}
WEEKDAYS = ["Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays"]


def jobs_list(core):
    j = core.settings.get("jobs")
    nightly = _next_time(j["nightly_time"])
    now = datetime.datetime.now()
    days_ahead = (j["integrity_weekday"] - now.weekday()) % 7
    ih, im = map(int, j["integrity_time"].split(":"))
    nxt_i = (now + datetime.timedelta(days=days_ahead)).replace(hour=ih, minute=im, second=0, microsecond=0)
    if nxt_i <= now:
        nxt_i += datetime.timedelta(days=7)
    out = []
    for key, (name, _, what) in JOB_FNS.items():
        last = core.stats.last_job(name)
        sched = (f"{WEEKDAYS[j['integrity_weekday']]} {j['integrity_time']}" if key == "integrity"
                 else f"Daily {j['nightly_time']}")
        out.append({"key": key, "name": name, "what": what, "schedule": sched,
                    "next": (nxt_i if key == "integrity" else nightly).timestamp(),
                    "last": last, "running": key in core.running_jobs})
    hc = core.health
    out.append({"key": "health", "name": "Health check", "what": "Servers, network, battery, temperature, free space, error rate",
                "schedule": f"Every {core.settings.get('health.every_min')} min",
                "next": (hc.last_run or time.time()) + core.settings.get("health.every_min") * 60,
                "last": {"started": hc.last_run, "finished": hc.last_run,
                         "result": "ok" if all(ok for _, ok, _ in hc.last) else "warn",
                         "detail": f"{sum(1 for _, ok, _ in hc.last if ok)} of {len(hc.last)} passed"} if hc.last_run else None,
                "running": "health" in core.running_jobs})
    history = core.stats.query("SELECT * FROM jobs ORDER BY id DESC LIMIT 60")
    return {"jobs": out, "history": history}


def job_run(core, key):
    if key not in JOB_FNS and key != "health":
        raise ApiError(404, "NoSuchJob", "No such job.")
    if key in core.running_jobs:
        raise ApiError(409, "JobRunning", "That job is already running.")

    def work():
        core.running_jobs.add(key)
        try:
            if key == "health":
                core.health.run()
            else:
                name, fn, _ = JOB_FNS[key]
                try:
                    core.jobs.run(name, fn(core))
                except Exception as e:      # JobBusy: another job holds the lock; wait for it once
                    time.sleep(2)
                    core.jobs.run(name, fn(core))
                core.usage.scan()
        except Exception as e:
            core.log(f"job {key} started from the panel failed: {e}")
        finally:
            core.running_jobs.discard(key)

    threading.Thread(target=work, daemon=True, name="panel-job-" + key).start()
    return {"ok": True, "started": key}


# ======================================================================== alerts

RULES = [
    ("server_down", "Gateway or storage server not responding", "2 failed checks in a row", None),
    ("pool_warn", "Storage pool passes", None, ("health.pool_warn_pct", 50, 95, 5, "% used")),
    ("pool_full", "Storage pool full", "uploads refused for every app", None),
    ("app_warn", "App passes its warning level", "per app, default 80%", None),
    ("app_full", "App reaches its allocation", "uploads refused or warned, per app", None),
    ("internal_low", "Internal storage free space low", None, ("health.internal_free_low_gb", 1, 30, 1, "GB")),
    ("sd_low", "SD card free space low", None, ("health.sd_free_low_pct", 1, 30, 1, "%")),
    ("battery_low", "Battery low (charger problem)", None, ("health.battery_low_pct", 10, 60, 5, "%")),
    ("temp_high", "Phone temperature high", None, ("health.temp_high_c", 35, 50, 1, "°C")),
    ("thermal_pause", "Jobs waiting for the phone to cool down", "nightly move, copy and integrity check pause above the temperature limit", None),
    ("push_down", "Push server not responding", "apps get no change notices until it is back", None),
    ("error_rate", "Error rate rising", None, ("health.error_rate_pct", 0.5, 10, 0.5, "% / hour")),
    ("job_failed", "Nightly or weekly job failed", "any failure", None),
    ("integrity", "SD card files damaged", "found by the weekly check", None),
    ("case_conflict", "Files kept off the SD card", "names that differ only in upper/lower case", None),
    ("sd_missing", "SD card missing or unreadable", "checked with every health check", None),
    ("new_card", "New SD card inserted", "ready to set up with Use this card", None),
    ("archive_card", "Archive SD card inserted", "browse it, restore from it or put it back to work", None),
    ("sd_ready", "SD card ready to remove", "after a swap", None),
    ("new_app", "New app or new device", "every time one enrolls", None),
    ("restart", "Pocket Cloud started (after a restart)", "reported once it is running again", None),
]


def alerts(core):
    enabled = core.settings.get("alerts")
    rules = []
    for kind, label, text, thr in RULES:
        r = {"kind": kind, "label": label, "text": text, "enabled": enabled.get(kind, True)}
        if thr:
            key, lo, hi, step, unit = thr
            r["threshold"] = {"key": key, "value": core.settings.get(key), "min": lo, "max": hi, "step": step, "unit": unit}
        rules.append(r)
    history = core.stats.query("SELECT * FROM alerts ORDER BY raised DESC LIMIT 100")
    return {"active": core.stats.active_alerts(), "history": history, "rules": rules}


def alerts_update(core, body):
    changes = {}
    if "enabled" in body:
        kinds = {k for k, *_ in RULES}
        enabled = dict(core.settings.get("alerts"))
        for k, v in body["enabled"].items():
            if k not in kinds:
                raise bad(f"Unknown alert {k}.")
            enabled[k] = bool(v)
        changes["alerts"] = enabled
    if "thresholds" in body:
        for key, value in body["thresholds"].items():
            spec = next((t for *_, t in RULES if t and t[0] == key), None)
            if not spec:
                raise bad(f"Unknown threshold {key}.")
            _, lo, hi, _, _ = spec
            section, name = key.split(".")
            changes.setdefault(section, {})[name] = _num(value, lo, hi, key)
    if changes:
        core.settings.update(changes)
        for kind, on in changes.get("alerts", {}).items():
            if not on:
                for a in core.stats.active_alerts():
                    if a["kind"] == kind:
                        core.stats.clear_alert(kind, a["subject"])
    return alerts(core)


# ======================================================================== settings

def _num(v, lo, hi, label, cast=float):
    try:
        x = cast(v)
    except (TypeError, ValueError):
        raise bad(f"{label} must be a number.")
    if not lo <= x <= hi:
        raise bad(f"{label} must be between {lo} and {hi}.")
    return x


def _hhmm(v, label):
    try:
        h, m = map(int, str(v).split(":"))
        assert 0 <= h < 24 and 0 <= m < 60
    except (ValueError, AssertionError):
        raise bad(f"{label} is a time like 03:00.")
    return f"{h:02d}:{m:02d}"


EDITABLE = {
    "new_apps.auto_create": bool, "new_apps.alloc_gb": (0.5, 20), "new_apps.warn_pct": (50, 95, int),
    "new_apps.at_limit": ("refuse", "warn"), "new_apps.move_days": (1, 60, int),
    "pool.reserve_gb": (2, 20), "pool.early_move_pct": (50, 100, int),
    "jobs.nightly_time": "time", "jobs.skip_changed_hours": (1, 72, int), "jobs.undo_days": (1, 60, int),
    "jobs.integrity_weekday": (0, 6, int), "jobs.integrity_time": "time",
    "logs.request_days": (7, 365, int), "logs.record_names": bool, "lan.enabled": bool, "push.enabled": bool, "share.enabled": bool,
    "health.every_min": (1, 30, int),
    "server.read_only": bool, "server.timeout_s": (10, 300, int), "server.dir_cache_s": (5, 300, int),
    "server.upload_buffer_mb": (16, 256, int), "server.multipart_expiry_h": (1, 336, int),
}
RESTARTS_RCLONE = {"server.read_only", "server.dir_cache_s", "server.upload_buffer_mb", "server.multipart_expiry_h"}


def settings_get(core):
    s = core.settings.all()
    s.pop("enroll_secret", None)
    pool = _pool(core)
    return {"settings": s, "pool": pool, "enroll_secret_set": True,
            "machine_config": f"[pocket]\nendpoint = http://pocket:{s['storage_port']}\nenroll_secret = <secret>\n",
            "addresses": {"storage": f"http://pocket:{s['storage_port']}", "panel": f"http://pocket:{s['panel_port']}",
                          "lan": f"http://{s['lan_address']}:{s['storage_port']}" if s["lan_address"] else None,
                          "push": core.push.url("pocket") if s["push"]["enabled"] else None,
                          "share": core.share_base()[0] if s["share"]["enabled"] else None}}


def settings_update(core, body):
    changes, touched = {}, set()
    for key, value in _flatten(body):
        if key in ("pool.internal_gb", "pool.sd_gb"):
            continue
        spec = EDITABLE.get(key)
        if spec is None:
            raise bad(f"{key} can't be changed here.")
        if spec is bool:
            value = bool(value)
        elif spec == "time":
            value = _hhmm(value, key)
        elif isinstance(spec, tuple) and isinstance(spec[0], str):
            if value not in spec:
                raise bad(f"{key} must be one of {', '.join(spec)}.")
        else:
            value = _num(value, spec[0], spec[1], key, spec[2] if len(spec) > 2 else float)
        section, name = key.split(".")
        changes.setdefault(section, {})[name] = value
        touched.add(key)
    p = body.get("pool", {})
    if "internal_gb" in p or "sd_gb" in p or "reserve_gb" in p:
        pool = _pool(core)
        reserve = changes.get("pool", {}).get("reserve_gb", core.settings.get("pool.reserve_gb")) * GB
        internal = float(p.get("internal_gb", core.settings.get("pool.internal_gb"))) * GB
        sd = float(p.get("sd_gb", core.settings.get("pool.sd_gb"))) * GB
        int_max = core.usage.free("int") + pool["int_used"] - reserve
        # only the values being changed are checked against the disks: a limit that was set earlier stays valid
        if ("internal_gb" in p or "reserve_gb" in p) and (internal < pool["int_used"] or internal > int_max):
            raise bad(f"Internal storage for Pocket must be between {pool['int_used'] / GB:.1f} and {int_max / GB:.1f} GB.")
        if "sd_gb" in p and not core.layout.has_sd:
            raise bad("There is no SD card in use; the SD pool is set when a card is put to use.", "NoSdCard")
        if "sd_gb" in p and (sd < pool["sd_used"] or sd > pool["sd_max"]):
            raise bad(f"SD card for Pocket must be between {pool['sd_used'] / GB:.1f} and {pool['sd_max'] / GB:.1f} GB.")
        if internal + sd < pool["promised"]:
            raise bad(f"The pool can't be smaller than the {pool['promised'] / GB:.1f} GB already promised to apps.",
                      "PoolTooSmall")
        changes.setdefault("pool", {}).update({"internal_gb": internal / GB, "sd_gb": sd / GB})
    if changes:
        core.settings.update(changes)
        core.update_flags()
        if "lan.enabled" in touched:
            core.set_lan(core.settings.get("lan.enabled"))
        if "push.enabled" in touched:
            core.push.apply()
        if "share.enabled" in touched:
            core.set_share(core.settings.get("share.enabled"))
        if touched & RESTARTS_RCLONE:
            threading.Thread(target=core.restart_rclone_drained, daemon=True).start()
    return settings_get(core)


def _flatten(body, prefix=""):
    for k, v in body.items():
        if isinstance(v, dict):
            yield from _flatten(v, prefix + k + ".")
        else:
            yield prefix + k, v


def enroll_secret(core, rotate=False):
    if rotate:
        import secrets
        core.settings.update({"enroll_secret": secrets.token_urlsafe(24)})
        core.stats.event("secret_rotated", "", "info", "The enrollment secret was replaced in the control panel.")
    s = core.settings.get("enroll_secret")
    port = core.settings.get("storage_port")
    return {"enroll_secret": s, "machine_config": f"[pocket]\nendpoint = http://pocket:{port}\nenroll_secret = {s}\n",
            "ci": {"POCKET_ENDPOINT": f"http://pocket:{port}", "POCKET_ENROLL_SECRET": s}}


def backup(core):
    return {
        "kind": "pocket-cloud-backup", "version": 1, "made": time.time(),
        "settings": core.settings._saved,
        "apps": core.registry.apps(),
        "devices": [{k: d[k] for k in ("app_id", "uid", "name", "ip", "access_key", "secret", "created_at", "last_seen", "blocked")}
                    for d in core.registry.devices()],
    }


def restore(core, data):
    if not isinstance(data, dict) or data.get("kind") != "pocket-cloud-backup":
        raise bad("That is not a Pocket Cloud settings backup.")
    keep = {k: core.settings.get(k) for k in ("listen", "storage_port", "rclone_port", "rc_port", "panel_port",
                                                "internal_root", "sd_root", "rclone_binary")}
    restored = dict(data.get("settings") or {})
    restored.update(keep)            # addresses and folders belong to this phone, not to the backup
    core.registry.replace_all(data.get("apps") or [], data.get("devices") or [])
    core.settings.replace(restored)
    core.update_flags()
    threading.Thread(target=core.restart_rclone_drained, daemon=True).start()
    return {"ok": True, "apps": len(data.get("apps") or []), "devices": len(data.get("devices") or [])}


# ======================================================================== logs

def logs(core, q):
    where, args = ["1=1"], []
    for col in ("app", "device", "op"):
        if q.get(col):
            where.append(f"{col} = ?")
            args.append(q[col])
    if q.get("status") == "err":
        where.append("status >= 400 AND status <> 404")
    elif q.get("status") == "ok":
        where.append("(status < 400 OR status = 404)")
    if q.get("q"):
        where.append("key LIKE ?")
        args.append("%" + q["q"] + "%")
    if q.get("before"):
        where.append("ts < ?")
        args.append(float(q["before"]))
    limit = min(int(q.get("limit") or 200), 1000)
    rows = core.stats.query(f"SELECT * FROM requests WHERE {' AND '.join(where)} ORDER BY ts DESC LIMIT {limit}", args)
    names = {d["uid"]: d["name"] for d in core.registry.devices()}
    for r in rows:
        r["device_name"] = names.get(r["device"], r["device"])
    return {"items": rows, "ops": [r["op"] for r in core.stats.query("SELECT DISTINCT op FROM requests ORDER BY op")]}


def syslog(core, lines=500):
    path = os.path.join(core.state_dir, "logs", "pocket.log")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            tail = f.readlines()[-int(lines):]
    except FileNotFoundError:
        tail = []
    return {"lines": [l.rstrip("\n") for l in reversed(tail)]}
