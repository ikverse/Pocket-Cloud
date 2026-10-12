"""Scheduled work: the nightly copy to the SD card, the nightly (and early) move to the SD card, the undo
cleanup, the weekly integrity check and log retention. Each run is recorded in stats.jobs."""

import datetime
import hashlib
import os
import shutil
import sqlite3
import threading
import time
import uuid

from .layout import remove_empty_dirs, walk_files
from .settings import GB, MB

CHUNK = 1024 * 1024
NO_SD = ("warn", "No SD card in the phone; skipped")
MTIME_SLACK_NS = 2_000_000_000     # FAT32 keeps modification times to 2 s (exFAT to 10 ms): that close is "the same"
COOL_CHECK_S = 30                   # how often a running job looks at the phone's temperature
COOL_POLL_S = 60                    # how often a paused job looks again
COOL_WAIT_MAX_S = 2 * 3600          # a job waits this long for the phone to cool down, then gives up


class JobBusy(Exception):
    pass


class TooHot(Exception):
    pass


def _fmt_bytes(n):
    return f"{n / GB:.2f} GB" if n >= GB else f"{n / MB:.0f} MB" if n >= MB else f"{n / 1024:.0f} KB"


class Jobs:
    def __init__(self, layout, registry, usage, stats, rclone, settings, log):
        self.layout = layout
        self.registry = registry
        self.usage = usage
        self.stats = stats
        self.rclone = rclone
        self.settings = settings
        self.log = log
        self._lock = threading.Lock()
        self.cool = None                # () -> (too hot, temperature); set by the server from the health checks
        self._sleep = time.sleep
        self._job_name = None
        self._cool_checked = 0.0

    @property
    def staging(self):
        return os.path.join(self.layout.root("sd"), "_staging")

    # ---- running and recording ----
    def run(self, name, fn, *args, **kw):
        """Run one job, record it, and raise an alert if it fails. Returns (result, detail)."""
        if not self._lock.acquire(blocking=False):
            raise JobBusy(name)
        job_id = self.stats.job_start(name)
        self._job_name = name
        try:
            result, detail = fn(*args, **kw)
        except TooHot:
            result, detail = "warn", f"stopped: the phone stayed too hot for {COOL_WAIT_MAX_S // 3600} hours"
            self.log(f"job {name} {detail}")
        except Exception as e:
            result, detail = "fail", f"{type(e).__name__}: {e}"
            self.log(f"job {name} failed: {detail}")
        finally:
            self._lock.release()
        self.stats.job_end(job_id, result, detail)
        if result == "fail":
            self.stats.raise_alert("job_failed", name, "bad", f"{name} failed: {detail}")
        else:
            self.stats.clear_alert("job_failed", name)
        return result, detail

    # ---- the thermal pause ----
    def _cool(self, force=False):
        """Wait while the phone is above its temperature limit (checked every COOL_CHECK_S of work, or now with
        force). An alert says which job is waiting; after COOL_WAIT_MAX_S the job gives up (TooHot)."""
        if not self.cool:
            return
        now = time.time()
        if not force and now - self._cool_checked < COOL_CHECK_S:
            return
        self._cool_checked = now
        name = self._job_name or "Job"
        waited = 0
        while True:
            hot, temp = self.cool()
            if not hot:
                if waited:
                    self.stats.clear_alert("thermal_pause", name)
                    self.log(f"{name} goes on: the phone is down to {temp:.1f} °C")
                self._cool_checked = time.time()
                return
            if not waited:
                self.log(f"{name} waits for the phone to cool down ({temp:.1f} °C)")
            self.stats.raise_alert("thermal_pause", name, "warn", f"{name} is waiting for the phone to cool down: {temp:.1f} °C.")
            if waited >= COOL_WAIT_MAX_S:
                self.stats.clear_alert("thermal_pause", name)
                raise TooHot()
            self._sleep(COOL_POLL_S)
            waited += COOL_POLL_S

    # ---- copying files safely to the SD card ----
    def _copy_to(self, src, dst, verify=True, staging=None):
        """Copy src to dst (on the SD card unless another staging folder on dst's storage is given) through a
        staging file, keep its modification time, check the copy reads back identical, then put it in place in
        one step. Returns the source stat used."""
        st = os.stat(src)
        staging = staging or self.staging
        os.makedirs(staging, exist_ok=True)
        tmp = os.path.join(staging, uuid.uuid4().hex)
        h = hashlib.sha256()
        try:
            with open(src, "rb") as fi, open(tmp, "wb") as fo:
                while True:
                    b = fi.read(CHUNK)
                    if not b:
                        break
                    h.update(b)
                    fo.write(b)
                fo.flush()
                os.fsync(fo.fileno())
            if verify and _sha256(tmp) != h.hexdigest():
                raise IOError("copy of %s did not read back identical" % src)
            os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(tmp, dst)
        except BaseException:
            try:
                os.remove(tmp)
            except FileNotFoundError:
                pass
            raise
        return st

    def _forget(self, app_id, rel_paths):
        """Tell rclone to forget the folders of moved files, for every device view of the app."""
        dirs_shared, dirs_device = set(), {}
        for rel in rel_paths:
            parts = rel.split(os.sep)
            if parts[0] == "shared":
                dirs_shared.add("/".join(["shared"] + parts[1:-1]))
            elif parts[0] == "devices" and len(parts) > 2:
                dirs_device.setdefault(parts[1], set()).add("/".join(["device"] + parts[2:-1]))
        devices = self.registry.devices(app_id)
        if dirs_shared:
            self.rclone.forget([d["access_key"] for d in devices], dirs_shared)
        for uid, dirs in dirs_device.items():
            self.rclone.forget([d["access_key"] for d in devices if d["uid"] == uid], dirs)

    # ---- nightly move ----
    def move(self, early=False):
        """Move files from internal storage to the SD card.
        Normal: files unchanged for the app's move age. Early: oldest files first (still skipping files
        changed recently) until the internal pool is back below its early-move level minus 10 points."""
        if not self.layout.has_sd:
            return NO_SD
        self._cool(force=True)
        skip = self.settings.get("jobs.skip_changed_hours") * 3600
        now = time.time()
        candidates = []
        names = {}          # (app, lower-cased path of a file or folder) -> exact spellings, on both storages

        def note(app_id, rel):
            parts = rel.split(os.sep)
            for i in range(1, len(parts) + 1):
                p = os.sep.join(parts[:i])
                names.setdefault((app_id, p.lower()), set()).add(p)

        for app in self.registry.apps():
            base = self.layout.app_dir("int", app["id"])
            min_age = skip if early else max(skip, app["move_days"] * 86400)
            for path, st in walk_files(base):
                note(app["id"], os.path.relpath(path, base))
                if now - st.st_mtime >= min_age:
                    candidates.append((st.st_mtime, app["id"], path, st.st_size))
            sd_base = self.layout.app_dir("sd", app["id"])
            for path, _ in walk_files(sd_base):
                note(app["id"], os.path.relpath(path, sd_base))
        candidates.sort()
        target = None
        if early:
            pool = self.usage.pool()
            target = pool["int_limit"] * (self.settings.get("pool.early_move_pct") - 10) / 100
            freeable = pool["int_used"]
        moved = skipped = 0
        moved_bytes = 0
        touched = {}
        kept = []           # (app, path) left on internal storage: the card can't tell their names apart
        for _, app_id, path, size in candidates:
            if early and freeable <= target:
                break
            self._cool()
            base_int = self.layout.app_dir("int", app_id)
            rel = os.path.relpath(path, base_int)
            # SD cards (FAT32, exFAT) treat Data.csv and data.csv as one name: moving either would overwrite
            # the other, or file it under the other's folder. Such files stay on internal storage.
            parts = rel.split(os.sep)
            if any(len(names[(app_id, os.sep.join(parts[:i]).lower())]) > 1 for i in range(1, len(parts) + 1)):
                kept.append((app_id, rel))
                continue
            dst = os.path.join(self.layout.app_dir("sd", app_id), rel)
            try:
                st = self._copy_to(path, dst)
                now_st = os.stat(path)
                if (now_st.st_size, now_st.st_mtime_ns) != (st.st_size, st.st_mtime_ns):
                    os.remove(dst)          # changed while being copied: leave it on internal storage
                    skipped += 1
                    continue
                os.remove(path)
            except FileNotFoundError:
                skipped += 1                # deleted or replaced while we worked
                continue
            remove_empty_dirs(os.path.dirname(path), os.path.join(base_int, rel.split(os.sep)[0]))
            touched.setdefault(app_id, []).append(rel)
            space = "shared" if rel.split(os.sep)[0] == "shared" else "device:" + rel.split(os.sep)[1]
            self.usage.add(app_id, space, "int", -size)
            self.usage.add(app_id, space, "sd", size)
            moved += 1
            moved_bytes += size
            if early:
                freeable -= size
        for app_id, rels in touched.items():
            self._forget(app_id, rels)
        detail = f"{moved} files, {_fmt_bytes(moved_bytes)} moved to the SD card"
        if skipped:
            detail += f" · {skipped} skipped (changed while moving)"
        if kept:
            listed = ", ".join(f"{a}/{r.replace(os.sep, '/')}" for a, r in kept[:5]) + (" …" if len(kept) > 5 else "")
            detail += f" · {len(kept)} kept on internal storage (names differ only in upper/lower case)"
            self.stats.raise_alert("case_conflict", "", "warn",
                                   f"{len(kept)} files stay on internal storage because the SD card can't tell their "
                                   f"names apart from others that differ only in upper/lower case: {listed}")
        else:
            self.stats.clear_alert("case_conflict", "")
        return ("warn" if skipped or kept else "ok"), detail

    # ---- nightly copy ----
    def copy(self):
        """Mirror everything still on internal storage to <sd>/_copy, so losing the phone loses at most a day.
        Also moves the internal part of the undo to the SD card."""
        if not self.layout.has_sd:
            return NO_SD
        self._cool(force=True)
        src_root = self.layout.apps_root("int")
        dst_root = os.path.join(self.layout.copy_root(), "apps")
        copied = removed = 0
        copied_bytes = 0
        seen = set()
        for path, st in walk_files(src_root):
            rel = os.path.relpath(path, src_root)
            seen.add(rel)
            dst = os.path.join(dst_root, rel)
            try:
                dst_st = os.stat(dst)
                if dst_st.st_size == st.st_size and abs(dst_st.st_mtime_ns - st.st_mtime_ns) <= MTIME_SLACK_NS:
                    continue
            except FileNotFoundError:
                pass
            self._cool()
            try:
                self._copy_to(path, dst)
            except FileNotFoundError:
                continue
            copied += 1
            copied_bytes += st.st_size
        for path, _ in walk_files(dst_root):
            rel = os.path.relpath(path, dst_root)
            if rel not in seen:
                os.remove(path)
                remove_empty_dirs(os.path.dirname(path), dst_root)
                removed += 1
        moved_undo = self._move_undo_to_sd()
        detail = f"{copied} files, {_fmt_bytes(copied_bytes)} copied"
        if removed:
            detail += f" · {removed} no longer on internal storage removed from the copy"
        if moved_undo:
            detail += f" · {moved_undo} undo files moved to the SD card"
        return "ok", detail

    def _move_undo_to_sd(self):
        src_root, dst_root = self.layout.undo_root("int"), self.layout.undo_root("sd")
        n = 0
        for path, st in walk_files(src_root):
            rel = os.path.relpath(path, src_root)
            dst = os.path.join(dst_root, rel)
            if os.path.basename(path) == "index.jsonl":
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(path, encoding="utf-8") as fi, open(dst, "a", encoding="utf-8") as fo:
                    fo.write(fi.read())
                os.remove(path)
                continue
            self._copy_to(path, dst)
            os.remove(path)
            n += 1
        for d, _, _ in os.walk(src_root, topdown=False):
            if d != src_root:
                try:
                    os.rmdir(d)
                except OSError:
                    pass
        return n

    # ---- undo cleanup ----
    def undo_cleanup(self):
        keep_days = self.settings.get("jobs.undo_days")
        cutoff = datetime.date.today() - datetime.timedelta(days=keep_days)
        files = size = 0
        for storage in self.layout.storages():
            root = self.layout.undo_root(storage)
            for name in os.listdir(root) if os.path.isdir(root) else []:
                try:
                    day = datetime.date.fromisoformat(name)
                except ValueError:
                    continue
                if day < cutoff:
                    for _, st in walk_files(os.path.join(root, name)):
                        files += 1
                        size += st.st_size
                    shutil.rmtree(os.path.join(root, name), ignore_errors=True)
        return "ok", f"{files} files, {_fmt_bytes(size)} kept longer than {keep_days} days removed"

    # ---- weekly integrity check ----
    def integrity(self):
        """Read every file on the SD card and compare it with the checksum taken when it was last seen
        unchanged. A file whose content changed while its size and time did not is reported as damaged."""
        if not self.layout.has_sd:
            return NO_SD
        self._cool(force=True)
        con = sqlite3.connect(os.path.join(self.layout.state_dir, "integrity.db"))
        con.execute("CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, size INTEGER, mtime_ns INTEGER, sha256 TEXT)")
        known = {r[0]: r[1:] for r in con.execute("SELECT path, size, mtime_ns, sha256 FROM files")}
        checked = damaged = 0
        problems = []
        seen = set()
        sd = self.layout.root("sd")
        for top in (self.layout.apps_root("sd"), self.layout.copy_root(), self.layout.undo_root("sd")):
            for path, st in walk_files(top):
                rel = os.path.relpath(path, sd)
                seen.add(rel)
                self._cool()
                digest = _sha256(path)
                checked += 1
                old = known.get(rel)
                if old and old[0] == st.st_size and old[1] == st.st_mtime_ns and old[2] != digest:
                    damaged += 1
                    problems.append(rel)
                    continue        # keep the good checksum so the file stays reported until replaced
                con.execute("INSERT OR REPLACE INTO files VALUES (?,?,?,?)", (rel, st.st_size, st.st_mtime_ns, digest))
        for rel in set(known) - seen:
            con.execute("DELETE FROM files WHERE path=?", (rel,))
        con.commit()
        con.close()
        if damaged:
            self.stats.raise_alert("integrity", "", "bad", f"{damaged} damaged files on the SD card: " + ", ".join(problems[:5]))
            return "fail", f"{checked} files checked, {damaged} damaged: " + ", ".join(problems[:5])
        self.stats.clear_alert("integrity", "")
        return "ok", f"{checked:,} files checked, 0 problems"

    # ---- logs ----
    def purge(self):
        n = self.stats.purge_requests(self.settings.get("logs.request_days"))
        s = self.registry.purge_shares()
        return "ok", f"{n} request log rows older than {self.settings.get('logs.request_days')} days removed" + (f" · {s} expired share links removed" if s else "")


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()
