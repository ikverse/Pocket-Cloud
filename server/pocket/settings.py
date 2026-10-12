"""Settings: one JSON file in the state folder. Every value has a default, so the file only holds what was changed."""

import copy
import json
import os
import secrets
import threading

GB = 1024 ** 3
MB = 1024 ** 2

DEFAULTS = {
    # Addresses the gateway listens on. On the phone: the Tailscale address and 127.0.0.1.
    "listen": ["127.0.0.1"],
    # The LAN fast path: with lan.enabled the gateway (never the control panel) also listens on the phone's
    # home Wi-Fi address (lan_address, found by run.sh), so a device on the same Wi-Fi can skip Tailscale.
    # Trade-off: anyone on that Wi-Fi can reach the storage port; every request still needs a device key
    # and enrolling needs the secret.
    "lan": {"enabled": False},
    "lan_address": "",
    "storage_port": 9000,
    "panel_port": 8000,
    "rclone_port": 9100,
    "rc_port": 5572,
    "internal_root": "",
    "sd_root": "",
    "rclone_binary": "rclone",
    "enroll_secret": "",
    "pool": {
        "internal_gb": 20,
        "sd_gb": 200,
        "reserve_gb": 10,        # always left free on internal storage for Android
        "early_move_pct": 90,    # internal pool use that starts an early move
        "redirect_pct": 95,      # internal pool use that sends new writes to the SD card
        "redirect_back_pct": 85, # ... and the level it must fall below before they return to internal storage
    },
    "new_apps": {
        "auto_create": True,
        "alloc_gb": 3,
        "warn_pct": 80,
        "at_limit": "refuse",    # or "warn"
        "move_days": 7,
    },
    "jobs": {
        "nightly_time": "03:00",
        "skip_changed_hours": 24,
        "undo_days": 14,
        "integrity_weekday": 6,  # Monday = 0 ... Sunday = 6
        "integrity_time": "04:00",
    },
    "logs": {
        "request_days": 90,
        "record_names": True,
    },
    "health": {
        "every_min": 5,
        "battery_low_pct": 30,
        "temp_high_c": 42,
        "internal_free_low_gb": 10,
        "sd_free_low_pct": 10,
        "pool_warn_pct": 80,
        "error_rate_pct": 2,
    },
    "alerts": {},                # alert kind -> False to switch that alert off (all on by default)
    "family": {
        "alloc_gb": 5,           # the family bucket's first allocation (changed later like any app's)
    },
    "share": {
        "enabled": False,        # the share-link listener (share.py); switched on in the control panel
        "port": 8010,
        "public_url": "",        # a named Cloudflare tunnel's address, e.g. https://files.example.com ("" = quick tunnel)
        "cloudflared": "",       # "" = find cloudflared on the PATH
    },
    "push": {
        "enabled": False,        # switched on by setup.sh the first time; see push.py
        "port": 2586,
    },
    "sd": {
        "search": "",            # where cards appear, e.g. /storage/*-*/Android/data/com.termux/files ("" = sd_root only)
        "poll_s": 10,
        "speed_test_mb": 64,
        "rules": [],             # swap choices: {"app", "space", "folder", "type", "carry"}
    },
    "server": {
        "read_only": False,
        "timeout_s": 60,
        "dir_cache_s": 30,
        "upload_buffer_mb": 64,
        "multipart_expiry_h": 168,      # a week: an interrupted large upload can be resumed for that long
        "usage_scan_s": 300,
    },
}


def _merge(base, over):
    out = copy.deepcopy(base)
    for k, v in (over or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Settings:
    """Thread-safe settings backed by state_dir/settings.json."""

    def __init__(self, state_dir, overrides=None):
        self.state_dir = state_dir
        self.path = os.path.join(state_dir, "settings.json")
        self._lock = threading.RLock()
        saved = {}
        if os.path.exists(self.path):
            with open(self.path, encoding="utf-8") as f:
                saved = json.load(f)
        self._saved = _merge(saved, overrides or {})
        self._data = _merge(DEFAULTS, self._saved)
        if not self._data["enroll_secret"]:
            self.update({"enroll_secret": secrets.token_urlsafe(24)})
        elif overrides:
            self._write()

    def get(self, dotted):
        with self._lock:
            node = self._data
            for part in dotted.split("."):
                node = node[part]
            return copy.deepcopy(node)

    def all(self):
        with self._lock:
            return copy.deepcopy(self._data)

    def update(self, changes):
        """Merge a (possibly nested) dict of changes and save it."""
        with self._lock:
            self._saved = _merge(self._saved, changes)
            self._data = _merge(DEFAULTS, self._saved)
            self._write()

    def replace(self, saved):
        """Replace every saved setting (restoring a backup)."""
        with self._lock:
            self._saved = dict(saved)
            self._data = _merge(DEFAULTS, self._saved)
            self._write()

    def _write(self):
        os.makedirs(self.state_dir, exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._saved, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    # Convenience values used across the server
    def pool_bytes(self):
        p = self.get("pool")
        return int(p["internal_gb"] * GB), int(p["sd_gb"] * GB)
