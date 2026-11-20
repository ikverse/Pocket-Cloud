"""The change feed (GET /_pocket/changes): one record per file put, deleted or moved, in state/stats.db.

Records are written wherever apps are told about changes (push): S3 and plain-route writes in the gateway, the
control panel's files page and undo, SD card put-back, and the nightly retention rules. Internal moves between
internal storage and the SD card are not changes.

A record belongs to the app that owns the space (the family bucket's records belong to the family app) and
holds the space as stored ('shared' or 'device:<uid>'); what a caller sees is worked out when it reads.

Cursors are the decimal seq of a record. A fresh database starts its seq at its creation time in milliseconds,
so cursors handed out by an earlier database (a server started fresh, or restored from an older copy) fall
below the floor and are answered with CursorExpired, as are cursors above the last seq ever issued.
"""

import os
import sqlite3
import threading
import time

from .layout import FAMILY

KEEP_DAYS = 30
KEEP_PER_APP = 100_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS changes (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    app TEXT NOT NULL, space TEXT NOT NULL, key TEXT NOT NULL, kind TEXT NOT NULL,
    from_app TEXT, from_space TEXT, from_key TEXT,
    size INTEGER, etag TEXT, time REAL NOT NULL, device TEXT, source TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS changes_app ON changes (app, seq);
CREATE INDEX IF NOT EXISTS changes_from_app ON changes (from_app, seq);
CREATE INDEX IF NOT EXISTS changes_time ON changes (time);
CREATE TABLE IF NOT EXISTS changes_meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);
"""


class CursorExpired(Exception):
    def __init__(self, cursor):
        super().__init__("cursor expired")
        self.cursor = cursor


class BadCursor(Exception):
    pass


def bucket_of(owner_id, space):
    if owner_id == FAMILY:
        return "family"
    return "shared" if space == "shared" else "device"


class Changes:
    def __init__(self, state_dir):
        self._con = sqlite3.connect(os.path.join(state_dir, "stats.db"), timeout=10, check_same_thread=False)
        self._con.row_factory = sqlite3.Row
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.execute("PRAGMA synchronous=NORMAL")
        self._con.executescript(SCHEMA)
        self._lock = threading.RLock()
        self._cond = threading.Condition(self._lock)
        floor = self._meta("floor")
        if floor is None:
            floor = int(time.time() * 1000)
            if self._con.execute("SELECT COUNT(*) FROM changes").fetchone()[0]:
                floor = self._con.execute("SELECT MIN(seq) FROM changes").fetchone()[0] - 1
            else:
                self._con.execute("DELETE FROM sqlite_sequence WHERE name='changes'")
                self._con.execute("INSERT INTO sqlite_sequence (name, seq) VALUES ('changes', ?)", (floor,))
            self._set_meta("floor", floor)
        self.floor = floor
        self._con.commit()

    def _meta(self, k):
        r = self._con.execute("SELECT v FROM changes_meta WHERE k=?", (k,)).fetchone()
        return r[0] if r else None

    def _set_meta(self, k, v):
        self._con.execute("INSERT OR REPLACE INTO changes_meta (k, v) VALUES (?,?)", (k, v))

    def last_seq(self):
        with self._lock:
            r = self._con.execute("SELECT seq FROM sqlite_sequence WHERE name='changes'").fetchone()
            return max(self.floor, r[0] if r else 0)

    def now_cursor(self):
        return str(self.last_seq())

    # ---- writing ----
    def record(self, app, space, key, kind, source, device=None, size=None, etag=None, from_=None, now=None):
        fa, fs, fk = from_ if from_ else (None, None, None)
        with self._cond:
            cur = self._con.execute(
                "INSERT INTO changes (app, space, key, kind, from_app, from_space, from_key, size, etag, time, device, source)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (app, space, key, kind, fa, fs, fk, size, etag, now or time.time(), device, source))
            self._con.commit()
            self._cond.notify_all()
            return cur.lastrowid

    # ---- reading ----
    def check(self, cursor, apps):
        """The seq of a cursor a caller following apps may use; raises BadCursor or CursorExpired."""
        try:
            c = int(cursor)
        except (TypeError, ValueError):
            raise BadCursor()
        with self._lock:
            marks = [self._meta("pruned:" + a) or 0 for a in apps]
            if c < self.floor or c > self.last_seq() or any(c < m for m in marks):
                raise CursorExpired(self.now_cursor())
        return c

    def fetch(self, app_id, uid, family, since, limit):
        """Changes after since that app_id's device uid may see, as API records. Returns (records, cursor, more)."""
        own_dev = "device:" + uid

        def visible(a, s):
            if a is None:
                return False
            return (a == app_id and s in ("shared", own_dev)) or (family and a == FAMILY)

        apps = (app_id, FAMILY) if family else (app_id,)
        marks = ",".join("?" * len(apps))
        out, more = [], False
        with self._lock:
            last = self.last_seq()
            rows = self._con.execute(
                f"SELECT * FROM changes WHERE seq > ? AND (app IN ({marks}) OR from_app IN ({marks})) ORDER BY seq",
                (since, *apps, *apps))
            for r in rows:
                rec = _present(r, visible)
                if rec is None:
                    continue
                if len(out) == limit:
                    more = True
                    break
                out.append(rec)
        cursor = str(out[-1]["seq"]) if more else str(last)
        return out, cursor, more

    def wait(self, after, timeout):
        """Block until a change newer than seq after is recorded, or timeout seconds pass."""
        end = time.time() + timeout
        with self._cond:
            while self.last_seq() <= after:
                left = end - time.time()
                if left <= 0:
                    return False
                self._cond.wait(left)
            return True

    def count(self, app_id):
        with self._lock:
            return self._con.execute("SELECT COUNT(*) FROM changes WHERE app=?", (app_id,)).fetchone()[0]

    # ---- nightly pruning ----
    def prune(self, now=None, keep_days=KEEP_DAYS, keep_per_app=KEEP_PER_APP):
        """Drop records older than keep_days and beyond the newest keep_per_app of each app. Returns the count."""
        now = now or time.time()
        removed = 0
        with self._lock:
            cutoff = now - keep_days * 86400
            apps = [r[0] for r in self._con.execute("SELECT DISTINCT app FROM changes")]
            for a in apps:
                top = self._con.execute("SELECT MAX(seq) FROM changes WHERE app=? AND time < ?", (a, cutoff)).fetchone()[0]
                n = self._con.execute("SELECT COUNT(*) FROM changes WHERE app=?", (a,)).fetchone()[0]
                if n > keep_per_app:
                    edge = self._con.execute("SELECT seq FROM changes WHERE app=? ORDER BY seq DESC LIMIT 1 OFFSET ?",
                                             (a, keep_per_app)).fetchone()[0]
                    top = max(top or 0, edge)
                if not top:
                    continue
                removed += self._con.execute("DELETE FROM changes WHERE app=? AND seq <= ?", (a, top)).rowcount
                self._set_meta("pruned:" + a, max(top, self._meta("pruned:" + a) or 0))
            self._con.commit()
        return removed

    def close(self):
        with self._lock:
            self._con.close()


def _present(r, visible):
    """A stored record as the caller sees it: a move with only one side visible is a put or a delete."""
    to_ok = visible(r["app"], r["space"])
    base = {"seq": r["seq"], "time": r["time"], "device": r["device"], "source": r["source"]}
    if r["kind"] == "move":
        from_ok = visible(r["from_app"], r["from_space"])
        if to_ok and from_ok:
            return {**base, "bucket": bucket_of(r["app"], r["space"]), "key": r["key"], "kind": "move",
                    "from": {"bucket": bucket_of(r["from_app"], r["from_space"]), "key": r["from_key"]},
                    "size": r["size"], "etag": r["etag"]}
        if to_ok:
            return {**base, "bucket": bucket_of(r["app"], r["space"]), "key": r["key"], "kind": "put",
                    "size": r["size"], "etag": r["etag"]}
        if from_ok:
            return {**base, "bucket": bucket_of(r["from_app"], r["from_space"]), "key": r["from_key"], "kind": "delete",
                    "size": None, "etag": None}
        return None
    if not to_ok:
        return None
    gone = r["kind"] == "delete"
    return {**base, "bucket": bucket_of(r["app"], r["space"]), "key": r["key"], "kind": r["kind"],
            "size": None if gone else r["size"], "etag": None if gone else r["etag"]}
