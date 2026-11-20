"""What the plain file routes know about a file beyond the file itself: its ETag and its stored Content-Type.

Kept in state/stats.db per (app, space, key). An ETag is trusted while the file's size and modification time
are what they were when it was taken (with FAT's 2 s of slack, as files moved to the SD card keep their time
only that closely); otherwise it is worked out again from the content. For files written through S3 the ETag
is the one rclone answered with, so files uploaded in parts keep S3's "<md5>-<parts>" form.
"""

import hashlib
import os
import sqlite3
import threading

SLACK_NS = 2_000_000_000
CHUNK = 1024 * 1024

SCHEMA = """
CREATE TABLE IF NOT EXISTS file_meta (
    app TEXT NOT NULL, space TEXT NOT NULL, key TEXT NOT NULL,
    size INTEGER, mtime_ns INTEGER, etag TEXT, ctype TEXT,
    PRIMARY KEY (app, space, key)
);
"""


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
        self._con.commit()
        self._lock = threading.Lock()

    def _row(self, app, space, key):
        with self._lock:
            return self._con.execute("SELECT size, mtime_ns, etag, ctype FROM file_meta WHERE app=? AND space=? AND key=?",
                                     (app, space, key)).fetchone()

    def set(self, app, space, key, size, mtime_ns, etag=None, ctype=None):
        with self._lock:
            self._con.execute("INSERT OR REPLACE INTO file_meta VALUES (?,?,?,?,?,?,?)",
                              (app, space, key, size, mtime_ns, quoted(etag), ctype))
            self._con.commit()

    def delete(self, app, space, key):
        with self._lock:
            self._con.execute("DELETE FROM file_meta WHERE app=? AND space=? AND key=?", (app, space, key))
            self._con.commit()

    def move(self, src, dst):
        """src, dst: (app, space, key). The file keeps its ETag and type under its new name."""
        with self._lock:
            self._con.execute("DELETE FROM file_meta WHERE app=? AND space=? AND key=?", dst)
            self._con.execute("UPDATE file_meta SET app=?, space=?, key=? WHERE app=? AND space=? AND key=?", (*dst, *src))
            self._con.commit()

    def info(self, app, space, key, path, st):
        """(etag, content type or None) of the file at path with stat st, working the ETag out if needed."""
        row = self._row(app, space, key)
        if row and row[2] and row[0] == st.st_size and abs((row[1] or 0) - st.st_mtime_ns) <= SLACK_NS:
            return row[2], row[3]
        etag = md5_etag(path)
        self.set(app, space, key, st.st_size, st.st_mtime_ns, etag, row[3] if row else None)
        return etag, (row[3] if row else None)

    def etag(self, app, space, key, path, st):
        return self.info(app, space, key, path, st)[0]

    def close(self):
        with self._lock:
            self._con.close()
