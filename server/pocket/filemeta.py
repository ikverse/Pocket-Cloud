"""What the plain file routes know about a file beyond the file itself: its ETag, its stored Content-Type, and
when this version arrived on the server.

Kept in state/stats.db per (app, space, key). An ETag is trusted while the file's size and modification time
are what they were when it was taken (with FAT's 2 s of slack, as files moved to the SD card keep their time
only that closely); otherwise it is worked out again from the content. For files written through S3 the ETag
is the one rclone answered with, so files uploaded in parts keep S3's "<md5>-<parts>" form.

The arrival time is the server's own clock when a version was stored (a client may give a file any
modification time); retention rules age files by it as well, so a fresh upload of an old file is not removed
the same night.
"""

import hashlib
import os
import sqlite3
import threading
import time

SLACK_NS = 2_000_000_000
CHUNK = 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS file_meta (
    app TEXT NOT NULL, space TEXT NOT NULL, key TEXT NOT NULL,
    size INTEGER, mtime_ns INTEGER, etag TEXT, ctype TEXT, arrived REAL,
    PRIMARY KEY (app, space, key)
);
"""
COLS = "app, space, key, size, mtime_ns, etag, ctype, arrived"


def md5_etag(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return '"%s"' % h.hexdigest()


def quoted(etag):
    if not etag:
        return None
    etag = etag.strip()
    return etag if etag.startswith('"') else '"%s"' % etag


class FileMeta:
    def __init__(self, state_dir):
        self._con = sqlite3.connect(os.path.join(state_dir, "stats.db"), timeout=10, check_same_thread=False)
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.executescript(SCHEMA)
        have = {r[1] for r in self._con.execute("PRAGMA table_info(file_meta)")}
        if "arrived" not in have:                      # tables made before arrival times were kept
            self._con.execute("ALTER TABLE file_meta ADD COLUMN arrived REAL")
        self._con.commit()
        self._lock = threading.Lock()

    def _row(self, app, space, key):
        with self._lock:
            return self._con.execute("SELECT size, mtime_ns, etag, ctype, arrived FROM file_meta WHERE app=? AND space=? AND key=?",
                                     (app, space, key)).fetchone()

    def _put(self, app, space, key, size, mtime_ns, etag, ctype, arrived, commit=True):
        with self._lock:
            self._con.execute(f"INSERT OR REPLACE INTO file_meta ({COLS}) VALUES (?,?,?,?,?,?,?,?)",
                              (app, space, key, size, mtime_ns, quoted(etag), ctype, arrived))
            if commit:
                self._con.commit()

    def set(self, app, space, key, size, mtime_ns, etag=None, ctype=None, arrived=None):
        """A new version was stored (arrived now, unless given)."""
        self._put(app, space, key, size, mtime_ns, etag, ctype, arrived if arrived is not None else time.time())

    def arrive(self, app, space, key, st, etag=None, ctype=None):
        """A version was put in place by the server itself (restore, put-back): it arrived now."""
        self._put(app, space, key, st.st_size, st.st_mtime_ns, etag, ctype, time.time())

    def delete(self, app, space, key):
        with self._lock:
            self._con.execute("DELETE FROM file_meta WHERE app=? AND space=? AND key=?", (app, space, key))
            self._con.commit()

    def move(self, src, dst):
        """src, dst: (app, space, key). The file keeps its ETag, type and arrival under its new name."""
        with self._lock:
            self._con.execute("DELETE FROM file_meta WHERE app=? AND space=? AND key=?", dst)
            self._con.execute("UPDATE file_meta SET app=?, space=?, key=? WHERE app=? AND space=? AND key=?", (*dst, *src))
            self._con.commit()

    def known(self, app, space, key, st):
        """The stored ETag if it still holds for a file with stat st, else None. Never reads the file."""
        row = self._row(app, space, key)
        if row and row[2] and row[0] == st.st_size and abs((row[1] or 0) - st.st_mtime_ns) <= SLACK_NS:
            return row[2]
        return None

    def info(self, app, space, key, path, st, commit=True):
        """(etag, content type or None) of the file at path with stat st, working the ETag out if needed."""
        row = self._row(app, space, key)
        if row and row[2] and row[0] == st.st_size and abs((row[1] or 0) - st.st_mtime_ns) <= SLACK_NS:
            return row[2], row[3]
        etag = md5_etag(path)
        self._put(app, space, key, st.st_size, st.st_mtime_ns, etag, row[3] if row else None,
                  row[4] if row else None, commit=commit)
        return etag, (row[3] if row else None)

    def etag(self, app, space, key, path, st, commit=True):
        return self.info(app, space, key, path, st, commit)[0]

    def commit(self):
        with self._lock:
            self._con.commit()

    def arrivals(self, app, space):
        """key -> arrival time, for the files of one space that have one."""
        with self._lock:
            return {r[0]: r[1] for r in self._con.execute(
                "SELECT key, arrived FROM file_meta WHERE app=? AND space=? AND arrived IS NOT NULL", (app, space))}

    def close(self):
        with self._lock:
            self._con.close()
