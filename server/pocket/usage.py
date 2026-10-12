"""How much each app, device space and storage uses.

Counted exactly by a scan of both storages every few minutes, and adjusted on every upload and delete in
between, so allocation and pool checks never have to touch the disk during a request.
"""

import os
import shutil
import threading
import time
from collections import defaultdict

from .layout import STORAGES, walk_files
from .settings import GB


class Usage:
    def __init__(self, layout, registry, settings):
        self.layout = layout
        self.registry = registry
        self.settings = settings
        self._lock = threading.Lock()
        self._scan_lock = threading.Lock()
        self._spaces = {}                   # (app, space) -> {"int": bytes, "sd": bytes, "files": n}
        self._extra = {"int_undo": 0, "state": 0, "sd_copy": 0, "sd_undo": 0}
        self._delta = defaultdict(int)      # (app, space, storage) -> bytes changed since the last scan
        self._delta_new = None              # changes since the running scan started
        self.scanned_at = 0.0
        self.scan_seconds = 0.0

    # ---- scanning ----
    def scan(self):
        with self._scan_lock:
            started = time.time()
            with self._lock:
                self._delta_new = defaultdict(int)
            spaces = {}
            for app in self.registry.apps():
                app_id = app["id"]
                for storage in self.layout.storages():
                    base = self.layout.app_dir(storage, app_id)
                    for path, st in walk_files(base):
                        rel = os.path.relpath(path, base).split(os.sep)
                        if rel[0] == "shared":
                            space = "shared"
                        elif rel[0] == "devices" and len(rel) > 2:
                            space = "device:" + rel[1]
                        else:
                            continue
                        entry = spaces.setdefault((app_id, space), {"int": 0, "sd": 0, "files": 0})
                        entry[storage] += st.st_size
                        entry["files"] += 1
            sd = self.layout.has_sd
            extra = {
                "int_undo": _tree_size(self.layout.undo_root("int")),
                "state": _tree_size(self.layout.state_dir),
                "sd_copy": _tree_size(self.layout.copy_root()) if sd else 0,
                "sd_undo": _tree_size(self.layout.undo_root("sd")) if sd else 0,
            }
            with self._lock:
                self._spaces = spaces
                self._extra = extra
                self._delta = self._delta_new
                self._delta_new = None
                self.scanned_at = time.time()
                self.scan_seconds = self.scanned_at - started

    def add(self, app_id, space, storage, delta):
        if not delta:
            return
        with self._lock:
            self._delta[(app_id, space, storage)] += delta
            if self._delta_new is not None:
                self._delta_new[(app_id, space, storage)] += delta

    # ---- reading ----
    def _space_total(self, app_id, space, storage=None):
        e = self._spaces.get((app_id, space), {"int": 0, "sd": 0})
        stores = [storage] if storage else STORAGES          # an absent card's share is 0 after a scan
        return max(0, sum(e[s] + self._delta.get((app_id, space, s), 0) for s in stores))

    def _spaces_of(self, app_id):
        return {sp for (a, sp) in self._spaces if a == app_id} | {k[1] for k in self._delta if k[0] == app_id}

    def app_used(self, app_id):
        with self._lock:
            return sum(self._space_total(app_id, sp) for sp in self._spaces_of(app_id))

    def space_used(self, app_id, space):
        with self._lock:
            return self._space_total(app_id, space)

    def app_detail(self, app_id):
        with self._lock:
            out = {}
            for sp in self._spaces_of(app_id):
                e = self._spaces.get((app_id, sp), {"files": 0})
                out[sp] = {"int": self._space_total(app_id, sp, "int"), "sd": self._space_total(app_id, sp, "sd"),
                           "files": e["files"]}
            return out

    def pool(self):
        """Pocket's use of each storage (app data, undo, nightly copy, logs) against the pool limits.
        With no SD card in the phone the SD pool is 0: only internal storage can take anything."""
        int_limit, sd_limit = self.settings.pool_bytes()
        if not self.layout.has_sd:
            sd_limit = 0
        with self._lock:
            apps_int = sum(e["int"] for e in self._spaces.values()) + sum(v for k, v in self._delta.items() if k[2] == "int")
            apps_sd = sum(e["sd"] for e in self._spaces.values()) + sum(v for k, v in self._delta.items() if k[2] == "sd")
            x = dict(self._extra)
        int_used = max(0, apps_int) + x["int_undo"] + x["state"]
        sd_used = max(0, apps_sd) + x["sd_copy"] + x["sd_undo"]
        return {
            "int_limit": int_limit, "sd_limit": sd_limit,
            "int_used": int_used, "sd_used": sd_used,
            "total": int_limit + sd_limit, "used": int_used + sd_used,
            "promised": self.registry.promised(),
            "detail": x, "apps_int": max(0, apps_int), "apps_sd": max(0, apps_sd),
        }

    def free(self, storage):
        root = self.layout.root(storage)
        return shutil.disk_usage(root).free if root else 0

    def reserve_bytes(self):
        return int(self.settings.get("pool.reserve_gb") * GB)


def _tree_size(top):
    return sum(st.st_size for _, st in walk_files(top))
