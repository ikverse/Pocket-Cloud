"""Statistics: every request, daily totals, storage history, job runs, alerts and phone readings,
in state/stats.db. Requests are queued and written in batches by a background thread."""

import datetime
import os
import queue
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
    ts REAL NOT NULL, app TEXT, device TEXT, ip TEXT, op TEXT, bucket TEXT, key TEXT,
    bytes_in INTEGER, bytes_out INTEGER, status INTEGER, ms REAL, note TEXT
);
CREATE INDEX IF NOT EXISTS requests_ts ON requests (ts);
CREATE INDEX IF NOT EXISTS requests_app ON requests (app, ts);
CREATE TABLE IF NOT EXISTS daily (
    day TEXT NOT NULL, app TEXT NOT NULL, device TEXT NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0, errors INTEGER NOT NULL DEFAULT 0,
    bytes_in INTEGER NOT NULL DEFAULT 0, bytes_out INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, app, device)
);
CREATE TABLE IF NOT EXISTS storage (
    ts REAL NOT NULL, int_used INTEGER, sd_used INTEGER, int_free INTEGER, sd_free INTEGER,
    int_limit INTEGER, sd_limit INTEGER
);
CREATE TABLE IF NOT EXISTS app_storage (
    day TEXT NOT NULL, app TEXT NOT NULL, int_bytes INTEGER, sd_bytes INTEGER, files INTEGER,
    PRIMARY KEY (day, app)
);
CREATE TABLE IF NOT EXISTS jobs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT NOT NULL, started REAL NOT NULL, finished REAL,
    result TEXT, detail TEXT
);
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, subject TEXT NOT NULL DEFAULT '',
    level TEXT NOT NULL, text TEXT NOT NULL, raised REAL NOT NULL, cleared REAL
);
CREATE TABLE IF NOT EXISTS phone (
    ts REAL NOT NULL, battery INTEGER, plugged TEXT, temp_c REAL
);
CREATE TABLE IF NOT EXISTS mirror_reports (
    ts REAL NOT NULL, app TEXT, device TEXT, note TEXT
);
"""


# Alerts that share one on/off switch in the control panel
RULE_OF = {"new_device": "new_app", "device_moved": "new_app"}


def day_of(ts):
    return datetime.date.fromtimestamp(ts).isoformat()


class Stats:
    def __init__(self, state_dir, notify=None):
        self.path = os.path.join(state_dir, "stats.db")
        self._con = sqlite3.connect(self.path, timeout=10, check_same_thread=False)
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.execute("PRAGMA synchronous=NORMAL")
        self._con.executescript(SCHEMA)
        self._con.commit()
        self._lock = threading.RLock()
        self._q = queue.Queue()
        self._stop = threading.Event()
        self.notify = notify          # called as notify(level, text) when an alert is raised
        self.enabled = None           # called as enabled(rule) -> bool; set by the server from its settings
        self._writer = threading.Thread(target=self._write_loop, name="stats-writer", daemon=True)
        self._writer.start()

    # ---- requests ----
    def request(self, **row):
        self._q.put(row)

    def _write_loop(self):
        while not self._stop.is_set() or not self._q.empty():
            rows = []
            try:
                rows.append(self._q.get(timeout=0.5))
                while len(rows) < 500:
                    rows.append(self._q.get_nowait())
            except queue.Empty:
                pass
            if rows:
                self._store(rows)

    def _store(self, rows):
        with self._lock:
            self._con.executemany(
                "INSERT INTO requests (ts, app, device, ip, op, bucket, key, bytes_in, bytes_out, status, ms, note)"
                " VALUES (:ts, :app, :device, :ip, :op, :bucket, :key, :bytes_in, :bytes_out, :status, :ms, :note)",
                [{**{"note": None}, **r} for r in rows])
            agg = {}
            for r in rows:
                if not r.get("app"):
                    continue
                k = (day_of(r["ts"]), r["app"], r.get("device") or "")
                a = agg.setdefault(k, [0, 0, 0, 0])
                a[0] += 1
                a[1] += 1 if (r.get("status") or 0) >= 400 else 0
                a[2] += r.get("bytes_in") or 0
                a[3] += r.get("bytes_out") or 0
            self._con.executemany(
                "INSERT INTO daily (day, app, device, requests, errors, bytes_in, bytes_out) VALUES (?,?,?,?,?,?,?)"
                " ON CONFLICT (day, app, device) DO UPDATE SET requests=requests+excluded.requests,"
                " errors=errors+excluded.errors, bytes_in=bytes_in+excluded.bytes_in, bytes_out=bytes_out+excluded.bytes_out",
                [(*k, *v) for k, v in agg.items()])
            self._con.commit()

    def flush(self, timeout=5):
        end = time.time() + timeout
        while not self._q.empty() and time.time() < end:
            time.sleep(0.02)
        time.sleep(0.05)

    def query(self, sql, args=()):
        with self._lock:
            return [dict(zip([c[0] for c in cur.description], row)) for cur in [self._con.execute(sql, args)] for row in cur.fetchall()]

    def purge_requests(self, older_than_days):
        cutoff = time.time() - older_than_days * 86400
        with self._lock:
            n = self._con.execute("DELETE FROM requests WHERE ts < ?", (cutoff,)).rowcount
            self._con.commit()
            return n

    # ---- storage, phone ----
    def storage_snapshot(self, pool, int_free, sd_free, per_app):
        now = time.time()
        with self._lock:
            self._con.execute("INSERT INTO storage VALUES (?,?,?,?,?,?,?)",
                              (now, pool["int_used"], pool["sd_used"], int_free, sd_free, pool["int_limit"], pool["sd_limit"]))
            self._con.executemany(
                "INSERT INTO app_storage VALUES (?,?,?,?,?) ON CONFLICT (day, app) DO UPDATE SET"
                " int_bytes=excluded.int_bytes, sd_bytes=excluded.sd_bytes, files=excluded.files",
                [(day_of(now), app, d["int"], d["sd"], d["files"]) for app, d in per_app.items()])
            self._con.commit()

    def phone_reading(self, battery, plugged, temp_c):
        with self._lock:
            self._con.execute("INSERT INTO phone VALUES (?,?,?,?)", (time.time(), battery, plugged, temp_c))
            self._con.commit()

    # ---- mirror reports: an outside copy (the PC's nightly mirror) saying it is up to date ----
    def mirror_report(self, app, device, note):
        with self._lock:
            self._con.execute("INSERT INTO mirror_reports VALUES (?,?,?,?)", (time.time(), app, device, note))
            self._con.commit()

    def last_mirror(self):
        rows = self.query("SELECT * FROM mirror_reports ORDER BY ts DESC LIMIT 1")
        return rows[0] if rows else None

    # ---- jobs ----
    def job_start(self, job):
        with self._lock:
            cur = self._con.execute("INSERT INTO jobs (job, started) VALUES (?, ?)", (job, time.time()))
            self._con.commit()
            return cur.lastrowid

    def job_end(self, job_id, result, detail):
        with self._lock:
            self._con.execute("UPDATE jobs SET finished=?, result=?, detail=? WHERE id=?", (time.time(), result, detail, job_id))
            self._con.commit()

    def last_job(self, job, result=None):
        sql = "SELECT * FROM jobs WHERE job=?" + (" AND result=?" if result else "") + " ORDER BY id DESC LIMIT 1"
        rows = self.query(sql, (job, result) if result else (job,))
        return rows[0] if rows else None

    # ---- alerts ----
    def raise_alert(self, kind, subject, level, text):
        """Raise an alert once; it stays active (and is not repeated) until cleared.
        Alerts switched off in the control panel are not raised."""
        if self.enabled and not self.enabled(RULE_OF.get(kind, kind)):
            return False
        with self._lock:
            row = self._con.execute("SELECT id FROM alerts WHERE kind=? AND subject=? AND cleared IS NULL",
                                    (kind, subject)).fetchone()
            if row:
                return False
            self._con.execute("INSERT INTO alerts (kind, subject, level, text, raised) VALUES (?,?,?,?,?)",
                              (kind, subject, level, text, time.time()))
            self._con.commit()
        if self.notify:
            try:
                self.notify(level, text)
            except Exception:
                pass
        return True

    def event(self, kind, subject, level, text):
        """A one-off alert (new app, new device): raised and cleared at once, so it only shows in history."""
        if self.raise_alert(kind, subject, level, text):
            self.clear_alert(kind, subject)

    def clear_alert(self, kind, subject=""):
        with self._lock:
            n = self._con.execute("UPDATE alerts SET cleared=? WHERE kind=? AND subject=? AND cleared IS NULL",
                                  (time.time(), kind, subject)).rowcount
            self._con.commit()
            return n

    def active_alerts(self):
        return self.query("SELECT * FROM alerts WHERE cleared IS NULL ORDER BY raised DESC")

    def close(self):
        self._stop.set()
        self._writer.join(10)
        with self._lock:
            self._con.close()
