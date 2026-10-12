"""Apps and devices, kept in a small SQLite database (state/registry.db).

An app is the umbrella: name, allocation and rules. A device is one install of the app on one machine,
with its own S3 key that only works from the device's Tailscale address. Allocations are strict promises:
together they can never exceed the storage pool.
"""

import os
import secrets
import sqlite3
import threading
import time

from .layout import APP_ID, DEVICE_UID, FAMILY

_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

SCHEMA = """
CREATE TABLE IF NOT EXISTS apps (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'active',
    alloc_bytes INTEGER NOT NULL,
    warn_pct INTEGER NOT NULL,
    at_limit TEXT NOT NULL,
    move_days INTEGER NOT NULL,
    device_cap_bytes INTEGER,
    device_reserve_bytes INTEGER,
    family INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    created_by TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    uid TEXT NOT NULL,
    name TEXT NOT NULL,
    ip TEXT NOT NULL,
    access_key TEXT NOT NULL UNIQUE,
    secret TEXT NOT NULL,
    created_at REAL NOT NULL,
    last_seen REAL,
    blocked INTEGER NOT NULL DEFAULT 0,
    ip_alt TEXT,
    PRIMARY KEY (app_id, uid)
);
CREATE TABLE IF NOT EXISTS shares (
    token TEXT PRIMARY KEY,
    app_id TEXT NOT NULL,
    uid TEXT NOT NULL,
    space TEXT NOT NULL,
    key TEXT NOT NULL,
    created REAL NOT NULL,
    expires REAL NOT NULL,
    password_hash TEXT,
    max_downloads INTEGER,
    downloads INTEGER NOT NULL DEFAULT 0
);
"""


class RegistryError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def new_access_key():
    return "PK" + "".join(secrets.choice(_KEY_ALPHABET) for _ in range(18))


def new_secret():
    return secrets.token_urlsafe(30)


def connect(path, readonly=False):
    if readonly:
        uri = "file:" + path.replace("\\", "/") + "?mode=ro"
        con = sqlite3.connect(uri, uri=True, timeout=5)
    else:
        con = sqlite3.connect(path, timeout=10, check_same_thread=False)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("PRAGMA synchronous=NORMAL")
        con.execute("PRAGMA foreign_keys=ON")
    con.row_factory = sqlite3.Row
    return con


class Registry:
    def __init__(self, state_dir):
        self.path = os.path.join(state_dir, "registry.db")
        self._con = connect(self.path)
        self._con.executescript(SCHEMA)
        self._migrate()
        self._con.commit()
        self._lock = threading.RLock()
        self._by_key = {}
        self._last_seen = {}
        self.listeners = []          # called with no arguments after every change
        self._reload()

    def _migrate(self):
        """Columns added after the first release, for databases made before them."""
        for table, column, decl in (("apps", "device_reserve_bytes", "INTEGER"), ("devices", "ip_alt", "TEXT"),
                                    ("apps", "family", "INTEGER NOT NULL DEFAULT 0")):
            have = {r["name"] for r in self._con.execute(f"PRAGMA table_info({table})")}
            if column not in have:
                self._con.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")

    # ---- reading ----
    def _reload(self):
        rows = self._con.execute("SELECT * FROM devices").fetchall()
        self._by_key = {r["access_key"]: dict(r) for r in rows}
        self._apps = {r["id"]: dict(r) for r in self._con.execute("SELECT * FROM apps")}

    def device_by_key(self, access_key):
        with self._lock:
            d = self._by_key.get(access_key)
            return dict(d) if d else None

    def app(self, app_id):
        with self._lock:
            a = self._apps.get(app_id)
            return dict(a) if a else None

    def apps(self):
        with self._lock:
            return [dict(r) for r in self._con.execute("SELECT * FROM apps ORDER BY created_at")]

    def devices(self, app_id=None):
        with self._lock:
            if app_id:
                rows = self._con.execute("SELECT * FROM devices WHERE app_id=? ORDER BY created_at", (app_id,))
            else:
                rows = self._con.execute("SELECT * FROM devices ORDER BY created_at")
            out = [dict(r) for r in rows]
            for d in out:
                d["last_seen"] = self._last_seen.get(d["access_key"], d["last_seen"])
            return out

    def promised(self):
        with self._lock:
            return self._con.execute("SELECT COALESCE(SUM(alloc_bytes), 0) FROM apps").fetchone()[0]

    # ---- changing ----
    def _changed(self):
        self._con.commit()
        self._reload()
        for fn in list(self.listeners):
            fn()

    def enroll(self, app_id, uid, name, ip, new_apps, pool_total, now=None, ip_alt=None, set_alt=False):
        """Create the app (if new) and the device (if new), and bind the device's key to ip (and, with set_alt,
        to ip_alt as a second address: the device's LAN or Tailscale address). Returns a dict describing what
        happened."""
        now = now or time.time()
        if not APP_ID.match(app_id or ""):
            raise RegistryError("BadAppId", "App names use 1-40 lowercase letters, digits and dashes.")
        if not DEVICE_UID.match(uid or ""):
            raise RegistryError("BadDeviceId", "Device ids use 8-64 letters, digits and dashes.")
        name = (name or "").strip()[:60] or uid[:12]
        result = {"app_created": False, "device_created": False, "ip_moved": False, "short_allocation": False}
        with self._lock:
            app = self.app(app_id)
            if app is None:
                unpromised = max(0, pool_total - self.promised())
                wanted = int(new_apps["alloc_gb"] * 1024 ** 3)
                alloc = min(wanted, unpromised)
                result["short_allocation"] = alloc < wanted
                status = "active" if new_apps["auto_create"] else "waiting"
                self._con.execute(
                    "INSERT INTO apps (id, name, status, alloc_bytes, warn_pct, at_limit, move_days, created_at, created_by)"
                    " VALUES (?,?,?,?,?,?,?,?,?)",
                    (app_id, app_id, status, alloc, new_apps["warn_pct"], new_apps["at_limit"], new_apps["move_days"], now, uid))
                result["app_created"] = True
            dev = self._con.execute("SELECT * FROM devices WHERE app_id=? AND uid=?", (app_id, uid)).fetchone()
            if dev is None:
                self._con.execute(
                    "INSERT INTO devices (app_id, uid, name, ip, access_key, secret, created_at, ip_alt) VALUES (?,?,?,?,?,?,?,?)",
                    (app_id, uid, name, ip, new_access_key(), new_secret(), now, ip_alt))
                result["device_created"] = True
            else:
                if dev["ip"] != ip:
                    self._con.execute("UPDATE devices SET ip=? WHERE app_id=? AND uid=?", (ip, app_id, uid))
                    result["ip_moved"] = True
                if set_alt and dev["ip_alt"] != ip_alt:
                    self._con.execute("UPDATE devices SET ip_alt=? WHERE app_id=? AND uid=?", (ip_alt, app_id, uid))
            self._changed()
            result["app"] = self.app(app_id)
            result["device"] = dict(self._con.execute(
                "SELECT * FROM devices WHERE app_id=? AND uid=?", (app_id, uid)).fetchone())
            return result

    def ensure_family(self, wanted, pool_total, new_apps):
        """The family bucket is a built-in app: created once, with the family allocation (or what is left)."""
        with self._lock:
            if self.app(FAMILY):
                return
            alloc = min(int(wanted), max(0, pool_total - self.promised()))
            self._con.execute(
                "INSERT INTO apps (id, name, status, alloc_bytes, warn_pct, at_limit, move_days, created_at, created_by, family)"
                " VALUES (?,?,?,?,?,?,?,?,?,1)",
                (FAMILY, "Family", "active", alloc, new_apps["warn_pct"], new_apps["at_limit"], new_apps["move_days"],
                 time.time(), "server"))
            self._changed()

    def members(self):
        """Apps that see the family bucket."""
        with self._lock:
            return [a for a in self.apps() if a["family"] and a["id"] != FAMILY]

    def create_app(self, app_id, name, new_apps, pool_total, created_by, now=None):
        """Create an app by hand (from the control panel), with the default allocation (or what is left)."""
        if not APP_ID.match(app_id or ""):
            raise RegistryError("BadAppId", "App names use 1-40 lowercase letters, digits and dashes.")
        with self._lock:
            if self.app(app_id):
                raise RegistryError("AppExists", f"There is already an app called {app_id}.")
            alloc = min(int(new_apps["alloc_gb"] * 1024 ** 3), max(0, pool_total - self.promised()))
            self._con.execute(
                "INSERT INTO apps (id, name, status, alloc_bytes, warn_pct, at_limit, move_days, created_at, created_by)"
                " VALUES (?,?,?,?,?,?,?,?,?)",
                (app_id, (name or app_id).strip()[:60], "active", alloc, new_apps["warn_pct"], new_apps["at_limit"],
                 new_apps["move_days"], now or time.time(), created_by))
            self._changed()
            return self.app(app_id)

    def replace_all(self, apps, devices):
        """Replace every app and device (restoring a settings backup)."""
        app_cols = ("id", "name", "notes", "status", "alloc_bytes", "warn_pct", "at_limit", "move_days",
                    "device_cap_bytes", "device_reserve_bytes", "family", "created_at", "created_by")
        dev_cols = ("app_id", "uid", "name", "ip", "access_key", "secret", "created_at", "last_seen", "blocked", "ip_alt")
        with self._lock:
            try:
                self._con.execute("BEGIN")
                self._con.execute("DELETE FROM devices")
                self._con.execute("DELETE FROM apps")
                self._con.executemany(f"INSERT INTO apps ({','.join(app_cols)}) VALUES ({','.join('?' * len(app_cols))})",
                                      [tuple(a.get(c) for c in app_cols) for a in apps])
                self._con.executemany(f"INSERT INTO devices ({','.join(dev_cols)}) VALUES ({','.join('?' * len(dev_cols))})",
                                      [tuple(d.get(c) for c in dev_cols) for d in devices])
                self._con.execute("COMMIT")
            except Exception:
                self._con.execute("ROLLBACK")
                raise
            self._last_seen.clear()
            self._changed()

    def update_app(self, app_id, **fields):
        allowed = {"name", "notes", "status", "warn_pct", "at_limit", "move_days", "device_cap_bytes", "device_reserve_bytes", "family"}
        bad = set(fields) - allowed
        if bad:
            raise ValueError("can't change " + ", ".join(sorted(bad)))
        if not fields:
            return self.app(app_id)
        with self._lock:
            sets = ", ".join(f"{k}=?" for k in fields)
            self._con.execute(f"UPDATE apps SET {sets} WHERE id=?", (*fields.values(), app_id))
            self._changed()
            return self.app(app_id)

    def set_allocation(self, app_id, alloc_bytes, pool_total, used_bytes):
        """Strict: never below what the app holds, never above its allocation plus the unpromised pool."""
        with self._lock:
            app = self.app(app_id)
            if app is None:
                raise RegistryError("NoSuchApp", "No app called " + app_id)
            ceiling = app["alloc_bytes"] + max(0, pool_total - self.promised())
            if alloc_bytes > ceiling:
                raise RegistryError("PoolTooSmall", "Only %d bytes of the pool can be promised to this app." % ceiling)
            if alloc_bytes < used_bytes:
                raise RegistryError("BelowUse", "The app already holds more than that.")
            self._con.execute("UPDATE apps SET alloc_bytes=? WHERE id=?", (int(alloc_bytes), app_id))
            self._changed()
            return self.app(app_id)

    def set_blocked(self, app_id, uid, blocked):
        with self._lock:
            self._con.execute("UPDATE devices SET blocked=? WHERE app_id=? AND uid=?", (1 if blocked else 0, app_id, uid))
            self._changed()

    def rename_device(self, uid, name):
        with self._lock:
            self._con.execute("UPDATE devices SET name=? WHERE uid=?", (name.strip()[:60], uid))
            self._changed()

    def rotate_key(self, app_id, uid):
        with self._lock:
            self._con.execute("UPDATE devices SET access_key=?, secret=? WHERE app_id=? AND uid=?",
                              (new_access_key(), new_secret(), app_id, uid))
            self._changed()
            return dict(self._con.execute("SELECT * FROM devices WHERE app_id=? AND uid=?", (app_id, uid)).fetchone())

    def remove_device(self, app_id, uid):
        with self._lock:
            self._con.execute("DELETE FROM devices WHERE app_id=? AND uid=?", (app_id, uid))
            self._changed()

    def remove_app(self, app_id):
        with self._lock:
            self._con.execute("DELETE FROM shares WHERE app_id=?", (app_id,))
            self._con.execute("DELETE FROM devices WHERE app_id=?", (app_id,))
            self._con.execute("DELETE FROM apps WHERE id=?", (app_id,))
            self._changed()

    # ---- share links (share.py) ----
    def add_share(self, app_id, uid, space, key, expires, password_hash=None, max_downloads=None, now=None):
        token = secrets.token_urlsafe(24)
        with self._lock:
            self._con.execute("INSERT INTO shares (token, app_id, uid, space, key, created, expires, password_hash, max_downloads)"
                              " VALUES (?,?,?,?,?,?,?,?,?)",
                              (token, app_id, uid, space, key, now or time.time(), expires, password_hash, max_downloads))
            self._con.commit()
        return self.share(token)

    def share(self, token):
        with self._lock:
            r = self._con.execute("SELECT * FROM shares WHERE token=?", (token,)).fetchone()
            return dict(r) if r else None

    def shares(self, app_id, uid=None):
        with self._lock:
            if uid:
                rows = self._con.execute("SELECT * FROM shares WHERE app_id=? AND uid=? ORDER BY created DESC", (app_id, uid))
            else:
                rows = self._con.execute("SELECT * FROM shares WHERE app_id=? ORDER BY created DESC", (app_id,))
            return [dict(r) for r in rows]

    def remove_share(self, token, app_id=None, uid=None):
        with self._lock:
            sql, args = "DELETE FROM shares WHERE token=?", [token]
            if app_id:
                sql += " AND app_id=?"
                args.append(app_id)
            if uid:
                sql += " AND uid=?"
                args.append(uid)
            n = self._con.execute(sql, args).rowcount
            self._con.commit()
            return n

    def share_downloaded(self, token):
        with self._lock:
            self._con.execute("UPDATE shares SET downloads = downloads + 1 WHERE token=?", (token,))
            self._con.commit()

    def purge_shares(self, older_than_s=86400):
        """Remove links expired for more than a day (so a late 'expired' answer still makes sense before that)."""
        with self._lock:
            n = self._con.execute("DELETE FROM shares WHERE expires < ?", (time.time() - older_than_s,)).rowcount
            self._con.commit()
            return n

    # last_seen is updated on every request, so it is kept in memory and written once a minute
    def touch(self, access_key, ts):
        self._last_seen[access_key] = ts

    def flush_last_seen(self):
        with self._lock:
            items = list(self._last_seen.items())
            if items:
                self._con.executemany("UPDATE devices SET last_seen=? WHERE access_key=?", [(t, k) for k, t in items])
                self._con.commit()

    def close(self):
        self.flush_last_seen()
        self._con.close()
