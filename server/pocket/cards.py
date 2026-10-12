"""SD cards as a stack of archives. One card is in the phone at a time; full cards go on the shelf.

Where cards are found: settings sd.search, a pattern such as /storage/*-*/Android/data/com.termux/files (the only
folder on a card Android 10 lets Termux write). The part with the wildcard is the card's volume; Pocket keeps
everything in <folder>/pocket/. Each card Pocket has used carries <folder>/pocket/.pocket-card.json with its ID,
name and state ("active" or "shelved"); a card without it is new.

  Swap SD card   files apps keep using (configurations, databases, backups...) are copied to internal storage and
                 checked byte for byte; video, photo, audio and large archives stay on the card by default. What stays
                 is listed in pocket/MANIFEST.csv on the card and in the database; the card is marked shelved and the
                 server goes on with internal storage only. Its files are hidden from apps while it is on the shelf.
  Use this card  a new card gets its marker, folders, a speed test and the SD pool size; the nightly move and copy
                 then run at once, so carried-over files move onto it under the normal rules.
  Put back       a shelved card comes back: its files rejoin their apps (the newer of two versions stays, the other
                 goes to the undo), files of deleted apps or removed devices go to pocket/_unassigned/, and apps that
                 would pass their allocation are listed, with the option to raise it.

A card pulled without a swap: the server goes on with internal storage only and alerts; when it comes back,
everything resumes on its own.
"""

import csv
import datetime
import glob
import json
import math
import os
import re
import shutil
import sqlite3
import threading
import time
import uuid

from . import undo
from .layout import DEVICE_UID, remove_empty_dirs, safe_key, space_dir, walk_files
from .panel_api import ApiError, bad
from .settings import GB, MB

POCKET = "pocket"
MARKER = ".pocket-card.json"
HALF_GB = GB // 2

KINDS = {
    "video": "mp4 m4v mkv avi mov wmv flv webm 3gp 3g2 mts m2ts ts mpg mpeg vob ogv",
    "photo": "jpg jpeg png gif bmp tif tiff heic heif webp raw cr2 cr3 nef arw dng orf rw2 raf psd",
    "audio": "mp3 wav flac aac m4a ogg oga opus wma aiff aif alac amr mka",
    "archive": "zip 7z rar tar gz tgz bz2 tbz2 xz txz zst iso img dmg cab",
}
KIND_OF = {"." + ext: kind for kind, exts in KINDS.items() for ext in exts.split()}

SCHEMA = """
CREATE TABLE IF NOT EXISTS cards (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    volume TEXT,
    size INTEGER,
    fs TEXT,
    write_mbs REAL,
    read_mbs REAL,
    state TEXT NOT NULL,             -- active | shelved
    created REAL,
    activated REAL,
    shelved REAL,
    note TEXT NOT NULL DEFAULT '',
    files INTEGER NOT NULL DEFAULT 0,
    bytes INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS card_files (
    card_id TEXT NOT NULL,
    app TEXT NOT NULL,
    space TEXT NOT NULL,
    key TEXT NOT NULL,
    size INTEGER NOT NULL,
    mtime REAL NOT NULL,
    kind TEXT NOT NULL,
    PRIMARY KEY (card_id, app, space, key)
);
"""


def file_kind(name):
    return KIND_OF.get(os.path.splitext(name)[1].lower(), "other")


def carried_by_default(kind):
    return kind == "other"


def _split_rel(rel):
    """'<app>/shared/<key>' or '<app>/devices/<uid>/<key>' (a path under apps/) -> (app, space, key) or None."""
    parts = rel.replace("\\", "/").split("/")
    if len(parts) >= 3 and parts[1] == "shared":
        return parts[0], "shared", "/".join(parts[2:])
    if len(parts) >= 4 and parts[1] == "devices":
        return parts[0], "device:" + parts[2], "/".join(parts[3:])
    return None


def card_fs(volume):
    """The card's file system, from Android's list of mounted drives ("unknown" anywhere else)."""
    try:
        with open("/proc/mounts", encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return "unknown"
    names = {"exfat": "exFAT", "vfat": "FAT32", "ntfs": "NTFS", "ext4": "ext4", "f2fs": "F2FS"}
    for line in lines:
        fields = line.split()
        if len(fields) < 4:
            continue
        mnt, typ, opts = fields[1], fields[2], fields[3]
        if mnt.endswith("/" + volume) and typ not in ("sdcardfs", "fuse", "tmpfs"):
            m = re.search(r"\bfs=([a-z0-9]+)", opts)
            t = m.group(1) if typ == "sdfat" and m else typ
            return names.get(t, t)
    return "unknown"


def speed_test(root, mb):
    """Write then read mb MB on the card. The read can come from memory, so only the write speed is a fair
    measure; both are shown."""
    os.makedirs(root, exist_ok=True)
    p = os.path.join(root, ".speed-test")
    block = os.urandom(MB)
    try:
        t = time.time()
        with open(p, "wb") as f:
            for _ in range(mb):
                f.write(block)
            f.flush()
            os.fsync(f.fileno())
        w = mb / max(time.time() - t, 1e-6)
        t = time.time()
        with open(p, "rb") as f:
            while f.read(MB):
                pass
        r = mb / max(time.time() - t, 1e-6)
    finally:
        try:
            os.remove(p)
        except OSError:
            pass
    return round(w, 1), round(r, 1)


class Cards:
    def __init__(self, core):
        self.core = core
        self._lock = threading.RLock()
        self.con = sqlite3.connect(os.path.join(core.state_dir, "registry.db"), timeout=30, check_same_thread=False)
        self.con.row_factory = sqlite3.Row
        self.con.executescript(SCHEMA)
        self.slot = None            # what is in the card slot: {"state", "volume", "folder", "card"}
        self.job = None             # the running or last card job, for the control panel's progress
        self.last_poll = 0.0
        self._shelved_here = None   # a card just swapped out, still in the slot until it is unmounted

    # ------------------------------------------------------------------ settings and records
    @property
    def enabled(self):
        return bool(self.core.settings.get("sd.search"))

    def _rows(self, sql, args=()):
        with self._lock:
            return [dict(r) for r in self.con.execute(sql, args)]

    def _exec(self, sql, args=()):
        with self._lock:
            self.con.execute(sql, args)
            self.con.commit()

    def card(self, card_id):
        rows = self._rows("SELECT * FROM cards WHERE id=?", (card_id,))
        return rows[0] if rows else None

    def active_card(self):
        rows = self._rows("SELECT * FROM cards WHERE state='active' ORDER BY activated DESC")
        return rows[0] if rows else None

    def _next_name(self):
        n = self._rows("SELECT COUNT(*) AS n FROM cards")[0]["n"]
        return f"Card {n + 1}"

    def _busy(self):
        return bool(self.job and self.job.get("running"))

    def _log(self, msg):
        self.core.log(msg)

    # ------------------------------------------------------------------ marker
    @staticmethod
    def _read_marker(root):
        try:
            with open(os.path.join(root, MARKER), encoding="utf-8") as f:
                m = json.load(f)
            return m if isinstance(m, dict) and m.get("id") else None
        except (OSError, ValueError):
            return None

    @staticmethod
    def _write_marker(root, marker):
        os.makedirs(root, exist_ok=True)
        tmp = os.path.join(root, MARKER + ".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(marker, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, os.path.join(root, MARKER))

    def _marker_for(self, card, state):
        return {"id": card["id"], "name": card["name"], "state": state, "created": card["created"],
                "shelved": card.get("shelved"), "pocket": self.core.version}

    # ------------------------------------------------------------------ finding cards
    def _pattern(self):
        parts = self.core.settings.get("sd.search").replace("\\", "/").split("/")
        i = next((n for n, p in enumerate(parts) if any(c in p for c in "*?[")), len(parts) - 1)
        return "/".join(parts[:i + 1]), "/".join(parts[i + 1:])

    def scan(self):
        """[(volume id, volume path, Termux's folder on it or None)] for every card in the phone."""
        vol_glob, rel = self._pattern()
        out = []
        for vol in sorted(glob.glob(vol_glob)):
            if not os.path.isdir(vol):
                continue
            folder = os.path.join(vol, rel) if rel else vol
            out.append((os.path.basename(vol.rstrip("/\\")), vol, folder if os.path.isdir(folder) else None))
        return out

    def poll(self):
        """Called every few seconds: notice a card going in or coming out, and follow it."""
        if not self.enabled or self._busy():
            return
        with self._lock:
            self.last_poll = time.time()
            found = self.scan()
            if not found:
                self._slot_empty()
                return
            volume, _, folder = found[0]            # the phone has one card slot
            if folder is None:
                # Android makes Termux's folder on a card only when termux-setup-storage runs in Termux on screen
                # (tried from the server in the background, it does nothing), so the panel asks the person to.
                self._leave_card("a card Termux has no folder on yet")
                self.slot = {"state": "needs_setup", "volume": volume, "folder": None, "card": None}
                return
            root = os.path.join(folder, POCKET)
            marker = self._read_marker(root)
            active = self.active_card()
            if marker and active and marker["id"] == active["id"] and marker.get("state") == "active":
                self.slot = {"state": "active", "volume": volume, "folder": folder, "card": active["id"]}
                if self.core.layout.root("sd") != os.path.abspath(root):
                    self._log(f"SD card {active['name']} ({volume}) is in; using it")
                    self.core.set_sd(root)
                    self.core.stats.clear_alert("sd_missing", "")
                    if active["volume"] != volume:
                        self._exec("UPDATE cards SET volume=? WHERE id=?", (volume, active["id"]))
                return
            if marker:
                self._leave_card("another card is in its place")
                card = self.card(marker["id"]) or self._import(marker, volume, root)
                if self._shelved_here == card["id"]:
                    self.slot = {"state": "shelved_here", "volume": volume, "folder": folder, "card": card["id"]}
                    return
                self.slot = {"state": "archive", "volume": volume, "folder": folder, "card": card["id"]}
                self.core.stats.raise_alert("archive_card", volume, "info",
                                            f"{card['name']}, an archive card, is in the phone. Browse it, restore "
                                            f"files from it, or put it back to work, in Storage › SD cards.")
                return
            # no marker
            if not active and not self._rows("SELECT id FROM cards") and os.path.isdir(os.path.join(root, "apps")):
                self._adopt(volume, folder, root)    # the card Pocket already used before cards could be swapped
                return
            self._leave_card("a new card is in its place")
            self.slot = {"state": "new", "volume": volume, "folder": folder, "card": None}
            self.core.stats.raise_alert("new_card", volume, "info",
                                        "A new SD card is in the phone. Set it up with Use this card, in Storage › SD cards.")

    def _slot_empty(self):
        if self.core.layout.has_sd:
            self._leave_card("it was removed")
        if self._shelved_here:
            self._log("the swapped-out card was removed")
            self._shelved_here = None
        for kind in ("sd_ready", "new_card", "archive_card"):
            for a in self.core.stats.active_alerts():
                if a["kind"] == kind:
                    self.core.stats.clear_alert(kind, a["subject"])
        self.slot = None

    def _leave_card(self, why):
        """The active card is no longer usable: go on with internal storage only."""
        if not self.core.layout.has_sd:
            return
        active = self.active_card()
        name = active["name"] if active else "The SD card"
        self._log(f"{name} is gone ({why}): going on with internal storage only")
        self.core.set_sd(None)
        self.core.stats.raise_alert("sd_missing", "", "bad", self.missing_text())

    def missing_text(self):
        active = self.active_card()
        if not active:
            return "There is no SD card in the phone: everything is on internal storage."
        return (f"{active['name']} was removed without a swap. Its {active['files']:,} files "
                f"({active['bytes'] / GB:.2f} GB, as of the last nightly copy) are not available until it is back.")

    def _import(self, marker, volume, root):
        """A marked card this server has no record of (its database was reset): take its record from the card."""
        card = {"id": marker["id"], "name": marker.get("name") or self._next_name(), "volume": volume, "size": None,
                "fs": None, "write_mbs": None, "read_mbs": None, "state": "shelved",
                "created": marker.get("created") or time.time(), "activated": None,
                "shelved": marker.get("shelved") or time.time(), "note": "found on the card"}
        self._insert(card)
        self._catalog(card["id"], root)
        return self.card(card["id"])

    def _insert(self, c):
        self._exec("INSERT OR REPLACE INTO cards (id, name, volume, size, fs, write_mbs, read_mbs, state, created,"
                   " activated, shelved, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                   (c["id"], c["name"], c["volume"], c["size"], c["fs"], c["write_mbs"], c["read_mbs"], c["state"],
                    c["created"], c["activated"], c["shelved"], c.get("note", "")))

    def _adopt(self, volume, folder, root):
        card = {"id": uuid.uuid4().hex[:8], "name": self._next_name(), "volume": volume,
                "size": shutil.disk_usage(folder).total, "fs": card_fs(volume), "write_mbs": None, "read_mbs": None,
                "state": "active", "created": time.time(), "activated": time.time(), "shelved": None,
                "note": "already in use"}
        self._insert(card)
        self._write_marker(root, self._marker_for(card, "active"))
        self.slot = {"state": "active", "volume": volume, "folder": folder, "card": card["id"]}
        self._log(f"the SD card {volume} Pocket was already using is now {card['name']}")
        if self.core.layout.root("sd") != os.path.abspath(root):
            self.core.set_sd(root)
        self._catalog(card["id"], root)

    # ------------------------------------------------------------------ lists of files
    def _walk_card(self, root):
        """(app, space, key, path, stat) for every app file on a card."""
        base = os.path.join(root, "apps")
        for path, st in walk_files(base):
            parts = _split_rel(os.path.relpath(path, base))
            if parts and safe_key(parts[2]) is not None:
                yield parts[0], parts[1], parts[2], path, st

    def _catalog(self, card_id, root):
        """Record every app file on the card, in the database (to browse it on the shelf)."""
        rows = [(card_id, a, sp, k, st.st_size, st.st_mtime, file_kind(k)) for a, sp, k, _, st in self._walk_card(root)]
        with self._lock:
            self.con.execute("DELETE FROM card_files WHERE card_id=?", (card_id,))
            self.con.executemany("INSERT OR REPLACE INTO card_files VALUES (?,?,?,?,?,?,?)", rows)
            self.con.execute("UPDATE cards SET files=?, bytes=? WHERE id=?", (len(rows), sum(r[4] for r in rows), card_id))
            self.con.commit()
        return rows

    def refresh_active(self):
        """After the nightly copy: what is on the active card, so a lost card's contents are known."""
        active = self.active_card()
        if active and self.core.layout.has_sd:
            self._catalog(active["id"], self.core.layout.root("sd"))

    def _write_manifest(self, card, root, rows):
        with open(os.path.join(root, "MANIFEST.csv"), "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["app", "space", "key", "size", "modified", "type"])
            for _, a, sp, k, size, mtime, kind in sorted(rows, key=lambda r: (r[1], r[2], r[3])):
                w.writerow([a, sp, k, size, datetime.datetime.fromtimestamp(mtime).isoformat(timespec="seconds"), kind])
        with open(os.path.join(root, "README.txt"), "w", encoding="utf-8") as f:
            f.write(f"Pocket Cloud SD card: {card['name']} (ID {card['id']})\n"
                    f"Shelved {datetime.datetime.now():%Y-%m-%d %H:%M}, holding {len(rows):,} files "
                    f"({sum(r[4] for r in rows) / GB:.2f} GB).\n\n"
                    "Every file is under apps/<app>/shared/... or apps/<app>/devices/<device>/..., with its own name.\n"
                    "MANIFEST.csv lists them all. Put the card back in the phone running Pocket Cloud to browse it,\n"
                    "restore files from it, or put it back to work.\n")

    # ------------------------------------------------------------------ status
    def _public(self, c):
        return {k: c[k] for k in ("id", "name", "volume", "size", "fs", "write_mbs", "read_mbs", "state", "created",
                                  "activated", "shelved", "note", "files", "bytes")} if c else None

    def status(self):
        layout = self.core.layout
        active = self.active_card()
        a = self._public(active)
        if a:
            a["present"] = bool(self.slot and self.slot["state"] == "active")
            if a["present"]:
                du = shutil.disk_usage(layout.root("sd"))
                a.update(total=du.total, free=du.free)
        slot = None
        if self.slot:
            slot = {"state": self.slot["state"], "volume": self.slot["volume"],
                    "card": self._public(self.card(self.slot["card"])) if self.slot["card"] else None}
            if self.slot["folder"]:
                du = shutil.disk_usage(self.slot["folder"])
                slot.update(total=du.total, free=du.free, fs=card_fs(self.slot["volume"]))
        shelf = [self._public(c) for c in self._rows("SELECT * FROM cards WHERE state='shelved' ORDER BY shelved DESC")]
        return {"mode": "cards" if self.enabled else "fixed", "sd_in_use": layout.has_sd, "active": a, "slot": slot,
                "shelf": shelf, "job": self.job, "slow_mbs": 10}

    # ------------------------------------------------------------------ jobs
    def _start_job(self, kind, card, fn):
        with self._lock:
            if self._busy():
                raise ApiError(409, "Busy", "Another SD card job is running.")
            self.job = {"kind": kind, "card": card, "running": True, "step": "Starting", "done": 0, "total": 0,
                        "started": time.time(), "finished": None, "error": None, "result": None}
        job = self.job

        def run():
            try:
                job["result"] = fn(job)
            except ApiError as e:
                job["error"] = e.message
            except Exception as e:
                job["error"] = f"{type(e).__name__}: {e}"
                self._log(f"SD card job {kind} failed: {job['error']}")
            finally:
                job["running"] = False
                job["finished"] = time.time()
                job["step"] = "Failed" if job["error"] else "Done"

        threading.Thread(target=run, daemon=True, name="card-" + kind).start()
        return {"ok": True, "job": job}

    def _hold_jobs(self, job):
        """Keep the nightly jobs out while a card job changes the card (they wait for the next round)."""
        job["step"] = "Waiting for running jobs to finish"
        if not self.core.jobs._lock.acquire(timeout=600):
            raise ApiError(409, "Busy", "A nightly job is still running; try again in a few minutes.")

    # ------------------------------------------------------------------ swap
    def _need_active_present(self):
        active = self.active_card()
        if not (active and self.slot and self.slot["state"] == "active" and self.core.layout.has_sd):
            raise ApiError(409, "NoActiveCard", "There is no SD card in use to swap out.")
        return active

    def _rules(self):
        return {(r["app"], r["space"], r["folder"], r["type"]): bool(r["carry"])
                for r in self.core.settings.get("sd.rules") if isinstance(r, dict)}

    def _group_of(self, app, space, key):
        parts = key.split("/")
        return app, space, parts[0] if len(parts) > 1 else "", os.path.splitext(parts[-1])[1].lower()

    def _room_internal(self):
        pool = self.core.usage.pool()
        return max(0, min(self.core.usage.free("int") - self.core.usage.reserve_bytes(),
                          pool["int_limit"] - pool["int_used"]))

    def _mirror_state(self, root):
        """Has an outside mirror (the PC's nightly copy) reported since the card's newest file was written?
        Files that stay on a shelved card have no other copy unless such a mirror holds one."""
        last_write = max((st.st_mtime for *_, st in self._walk_card(root)), default=None)
        m = self.core.stats.last_mirror()
        ok = bool(m) and (last_write is None or m["ts"] >= last_write)
        return {"reported": m["ts"] if m else None, "note": m["note"] if m else None, "last_write": last_write, "ok": ok}

    def swap_plan(self):
        self._need_active_present()
        root = self.core.layout.root("sd")
        rules = self._rules()
        names = {a["id"]: a["name"] for a in self.core.registry.apps()}
        groups = {}
        for app, space, key, _, st in self._walk_card(root):
            g = groups.setdefault(self._group_of(app, space, key), {"files": 0, "bytes": 0, "kind": file_kind(key)})
            g["files"] += 1
            g["bytes"] += st.st_size
        out = []
        for (app, space, folder, typ), g in sorted(groups.items()):
            default = carried_by_default(g["kind"])
            out.append({"app": app, "app_name": names.get(app, app), "space": space, "folder": folder, "type": typ,
                        "kind": g["kind"], "files": g["files"], "bytes": g["bytes"], "default": default,
                        "carry": rules.get((app, space, folder, typ), default)})
        carry = sum(g["bytes"] for g in out if g["carry"])
        room = self._room_internal()
        return {"groups": out, "carry_bytes": carry, "carry_files": sum(g["files"] for g in out if g["carry"]),
                "stay_bytes": sum(g["bytes"] for g in out if not g["carry"]),
                "stay_files": sum(g["files"] for g in out if not g["carry"]),
                "room": room, "enough": carry <= room, "mirror": self._mirror_state(root)}

    def swap_start(self, body):
        active = self._need_active_present()
        choices = body.get("groups")
        if not isinstance(choices, list):
            raise bad("Send the groups with their carry choice.")
        if not body.get("override") and not self._mirror_state(self.core.layout.root("sd"))["ok"]:
            raise ApiError(409, "MirrorStale", "No mirror has reported a copy since the card was last written: files that "
                                               "stay on the card would have no second copy. Run the mirror first, or swap anyway.")
        rules = self._rules()
        for g in choices:
            try:
                k = (str(g["app"]), str(g["space"]), str(g.get("folder", "")), str(g.get("type", "")))
            except (KeyError, TypeError):
                raise bad("Each group needs app, space, folder, type and carry.")
            rules[k] = bool(g.get("carry"))
        self.core.settings.update({"sd": {"rules": [
            {"app": a, "space": s, "folder": f, "type": t, "carry": c} for (a, s, f, t), c in sorted(rules.items())]}})
        return self._start_job("swap", active["id"], lambda job: self._swap(job, active))

    def _swap(self, job, card):
        core = self.core
        self._hold_jobs(job)
        try:
            root = core.layout.root("sd")
            rules = self._rules()
            carry = []
            for app, space, key, path, st in self._walk_card(root):
                kind = file_kind(key)
                if rules.get(self._group_of(app, space, key), carried_by_default(kind)):
                    carry.append((app, space, key, path, st.st_size))
            need = sum(c[4] for c in carry)
            if need > self._room_internal():
                raise ApiError(507, "NoRoom", f"Internal storage needs {need / GB:.2f} GB for the files to keep, "
                                              f"and has {self._room_internal() / GB:.2f} GB.")
            job.update(step="Copying the files to keep using to internal storage", total=len(carry), done=0)
            staging = os.path.join(core.layout.root("int"), "_staging")
            for app, space, key, path, size in carry:
                dst = core.layout.key_path("int", app, space, key)
                if dst and not os.path.exists(dst):          # a newer version on internal storage already wins
                    core.jobs._copy_to(path, dst, staging=staging)
                job["done"] += 1
            job.update(step="Removing the copied files from the card", done=0)
            for app, space, key, path, size in carry:
                os.remove(path)
                remove_empty_dirs(os.path.dirname(path), os.path.join(root, "apps", app))
                job["done"] += 1
            job["step"] = "Removing the card's copy of internal storage"
            shutil.rmtree(os.path.join(root, "_copy"), ignore_errors=True)
            shutil.rmtree(os.path.join(root, "_staging"), ignore_errors=True)
            job["step"] = "Listing what stays on the card"
            rows = self._catalog(card["id"], root)
            shelved = time.time()
            self._exec("UPDATE cards SET state='shelved', shelved=? WHERE id=?", (shelved, card["id"]))
            card = self.card(card["id"])
            self._write_manifest(card, root, rows)
            self._write_marker(root, self._marker_for(card, "shelved"))
        finally:
            core.jobs._lock.release()
        job["step"] = "Switching to internal storage only"
        with self._lock:
            self._shelved_here = card["id"]
            core.set_sd(None)
            self.slot = dict(self.slot or {}, state="shelved_here", card=card["id"])
        core.stats.raise_alert("sd_ready", card["id"], "info",
                               f"{card['name']} is ready to remove. Unmount it in Android's storage settings, then take it out.")
        self._log(f"{card['name']} swapped out: {len(carry):,} files kept on internal storage, "
                  f"{card['files']:,} files ({card['bytes'] / GB:.2f} GB) stay on the card")
        return {"carried": len(carry), "stayed": card["files"], "stayed_bytes": card["bytes"]}

    # ------------------------------------------------------------------ a new card
    def use_new(self, body):
        with self._lock:
            if not (self.slot and self.slot["state"] == "new"):
                raise ApiError(409, "NoNewCard", "There is no new SD card in the phone.")
            slot = dict(self.slot)
        name = str(body.get("name") or "").strip()[:40] or self._next_name()
        return self._start_job("use", None, lambda job: self._use(job, slot, name))

    def _settle_previous(self):
        """A card that was in use but is gone without a swap: it is on the shelf now, as last listed."""
        prev = self.active_card()
        if prev:
            self._exec("UPDATE cards SET state='shelved', shelved=?, note=? WHERE id=?",
                       (time.time(), "removed without a swap", prev["id"]))

    def _use(self, job, slot, name):
        core = self.core
        folder, volume = slot["folder"], slot["volume"]
        root = os.path.join(folder, POCKET)
        job["step"] = "Checking the card"
        os.makedirs(root, exist_ok=True)
        w, r = speed_test(root, core.settings.get("sd.speed_test_mb"))
        du = shutil.disk_usage(folder)
        card = {"id": uuid.uuid4().hex[:8], "name": name, "volume": volume, "size": du.total, "fs": card_fs(volume),
                "write_mbs": w, "read_mbs": r, "state": "active", "created": time.time(), "activated": time.time(),
                "shelved": None, "note": ""}
        self._settle_previous()
        self._insert(card)
        self._write_marker(root, self._marker_for(card, "active"))
        core.settings.update({"pool": {"sd_gb": max(1, int(du.free * 0.95 / GB))}})
        job["step"] = "Switching to the new card"
        with self._lock:
            core.set_sd(root)
            self.slot = {"state": "active", "volume": volume, "folder": folder, "card": card["id"]}
        core.stats.clear_alert("new_card", volume)
        core.stats.clear_alert("sd_missing", "")
        job["step"] = "Moving files onto the card and making the nightly copy"
        core.refresh_sd()
        self._catalog(card["id"], root)
        self._log(f"{name} ({volume}, {card['fs']}, write {w} MB/s) is now the SD card in use")
        return {"card": card["id"], "write_mbs": w, "read_mbs": r, "slow": w < 10}

    # ------------------------------------------------------------------ put a shelved card back to work
    def _need_archive_in(self, card_id):
        with self._lock:
            if not (self.slot and self.slot["state"] in ("archive", "shelved_here") and self.slot["card"] == card_id):
                raise ApiError(409, "CardNotIn", "That card is not in the phone.")
            return dict(self.slot)

    def _compare(self, root):
        core = self.core
        apps = {a["id"]: a for a in core.registry.apps()}
        devices = {(d["app_id"], "device:" + d["uid"]) for d in core.registry.devices()}
        rejoin, conflicts, unassigned = [], [], []
        added = {}
        for app, space, key, path, st in self._walk_card(root):
            if app not in apps:
                unassigned.append({"app": app, "space": space, "key": key, "size": st.st_size, "why": "app deleted"})
                continue
            if space != "shared" and (app, space) not in devices:
                unassigned.append({"app": app, "space": space, "key": key, "size": st.st_size, "why": "device removed"})
                continue
            here = core.layout.key_path("int", app, space, key)
            if here and os.path.isfile(here):
                hst = os.stat(here)
                card_newer = st.st_mtime > hst.st_mtime + 2      # the card keeps times to 2 s (FAT32)
                conflicts.append({"app": app, "space": space, "key": key, "card_size": st.st_size,
                                  "card_modified": st.st_mtime, "current_size": hst.st_size,
                                  "current_modified": hst.st_mtime, "keep": "card" if card_newer else "current"})
                if card_newer:
                    added[app] = added.get(app, 0) + st.st_size - hst.st_size
                continue
            rejoin.append({"app": app, "space": space, "key": key, "size": st.st_size})
            added[app] = added.get(app, 0) + st.st_size
        overruns = []
        for app, extra in added.items():
            a = apps[app]
            would = core.usage.app_used(app) + extra
            if would > a["alloc_bytes"]:
                overruns.append({"app": app, "name": a["name"], "alloc": a["alloc_bytes"], "would_use": would,
                                 "raise_to": int(math.ceil(would / HALF_GB) * HALF_GB)})
        return rejoin, conflicts, unassigned, overruns

    def compare(self, card_id):
        slot = self._need_archive_in(card_id)
        root = os.path.join(slot["folder"], POCKET)
        rejoin, conflicts, unassigned, overruns = self._compare(root)
        du = shutil.disk_usage(slot["folder"])
        pocket_used = sum(st.st_size for _, st in walk_files(root))
        return {"card": self._public(self.card(card_id)),
                "rejoin_files": len(rejoin), "rejoin_bytes": sum(f["size"] for f in rejoin),
                "conflicts": conflicts[:500], "conflict_count": len(conflicts),
                "card_wins": sum(1 for c in conflicts if c["keep"] == "card"),
                "unassigned": unassigned[:500], "unassigned_count": len(unassigned),
                "unassigned_bytes": sum(f["size"] for f in unassigned),
                "overruns": overruns,
                "sd_pool_gb": max(1, int((du.free + pocket_used) * 0.95 / GB))}

    def activate(self, card_id, body):
        slot = self._need_archive_in(card_id)
        raise_apps = set(body.get("raise") or [])
        return self._start_job("put_back", card_id, lambda job: self._activate(job, slot, card_id, raise_apps))

    def _activate(self, job, slot, card_id, raise_apps):
        core = self.core
        folder, volume = slot["folder"], slot["volume"]
        root = os.path.join(folder, POCKET)
        job["step"] = "Comparing the card with the server"
        rejoin, conflicts, unassigned, overruns = self._compare(root)
        now = datetime.datetime.now()
        job.update(step="Putting aside files of deleted apps and removed devices", total=len(unassigned), done=0)
        for f in unassigned:
            src = os.path.join(root, "apps", f["app"], space_dir(f["space"]), *f["key"].split("/"))
            dst = os.path.join(root, "_unassigned", f["app"], space_dir(f["space"]), *f["key"].split("/"))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(src, dst)
            remove_empty_dirs(os.path.dirname(src), os.path.join(root, "apps"))
            job["done"] += 1
        job.update(step="Keeping the newer of two versions", total=len(conflicts), done=0)
        for c in conflicts:
            card_path = os.path.join(root, "apps", c["app"], space_dir(c["space"]), *c["key"].split("/"))
            if c["keep"] == "card":
                kept = undo.keep(core.layout, c["app"], c["space"], c["key"], "replaced by a returning SD card", "SD card")
                for s, _, sz in kept:
                    os.remove(core.layout.key_path(s, c["app"], c["space"], c["key"]))
                    core.usage.add(c["app"], c["space"], s, -sz)
            else:
                _to_card_undo(root, now, c["app"], c["space"], c["key"], card_path, "older than the version in use")
            job["done"] += 1
        w, r = speed_test(root, core.settings.get("sd.speed_test_mb"))
        du = shutil.disk_usage(folder)
        pocket_used = sum(st.st_size for _, st in walk_files(root))
        self._settle_previous()
        self._exec("UPDATE cards SET state='active', activated=?, shelved=NULL, volume=?, size=?, fs=?, write_mbs=?,"
                   " read_mbs=?, note='' WHERE id=?", (time.time(), volume, du.total, card_fs(volume), w, r, card_id))
        card = self.card(card_id)
        self._write_marker(root, self._marker_for(card, "active"))
        core.settings.update({"pool": {"sd_gb": max(1, int((du.free + pocket_used) * 0.95 / GB))}})
        job["step"] = "Switching to the card"
        with self._lock:
            if self._shelved_here == card_id:
                self._shelved_here = None
            core.set_sd(root)
            self.slot = {"state": "active", "volume": volume, "folder": folder, "card": card_id}
        for kind, subject in (("archive_card", volume), ("sd_ready", card_id), ("sd_missing", "")):
            core.stats.clear_alert(kind, subject)
        raised, not_raised = [], []
        for o in overruns:
            if o["app"] in raise_apps:
                try:
                    core.registry.set_allocation(o["app"], o["raise_to"], core.usage.pool()["total"], core.usage.app_used(o["app"]))
                    raised.append(o["app"])
                except Exception as e:
                    not_raised.append({"app": o["app"], "why": str(e)})
        job["step"] = "Making the nightly copy"
        core.refresh_sd()
        self._catalog(card_id, root)
        self._log(f"{card['name']} is back in use: {len(rejoin):,} files rejoined, {len(conflicts):,} with two versions, "
                  f"{len(unassigned):,} put aside")
        return {"rejoined": len(rejoin), "conflicts": len(conflicts), "unassigned": len(unassigned),
                "raised": raised, "not_raised": not_raised}

    # ------------------------------------------------------------------ browsing and restoring archive files
    def files(self, card_id, app, space, path=""):
        if not self.card(card_id):
            raise ApiError(404, "NoSuchCard", "No such card.")
        prefix = path.strip("/")
        rows = self._rows("SELECT app, space, key, size, mtime, kind FROM card_files WHERE card_id=? ORDER BY app, space, key",
                          (card_id,))
        if not app:
            apps = {}
            names = {a["id"]: a["name"] for a in self.core.registry.apps()}
            for r in rows:
                e = apps.setdefault((r["app"], r["space"]), {"app": r["app"], "name": names.get(r["app"], r["app"]),
                                                              "space": r["space"], "files": 0, "size": 0})
                e["files"] += 1
                e["size"] += r["size"]
            return {"card": card_id, "spaces": list(apps.values())}
        entries = {}
        for r in rows:
            if r["app"] != app or r["space"] != space:
                continue
            k = r["key"]
            if prefix and not k.startswith(prefix + "/"):
                continue
            rest = k[len(prefix) + 1:] if prefix else k
            head, _, tail = rest.partition("/")
            if tail:
                d = entries.setdefault(("d", head), {"type": "dir", "name": head, "files": 0, "size": 0})
                d["files"] += 1
                d["size"] += r["size"]
            else:
                entries[("f", head)] = {"type": "file", "name": head, "size": r["size"], "modified": r["mtime"],
                                        "kind": r["kind"], "key": k}
        items = sorted(entries.values(), key=lambda x: (x["type"] != "dir", x["name"].lower()))
        inserted = bool(self.slot and self.slot["card"] == card_id and self.slot["state"] in ("archive", "shelved_here"))
        return {"card": card_id, "app": app, "space": space, "path": prefix, "items": items, "inserted": inserted}

    def restore(self, card_id, body):
        slot = self._need_archive_in(card_id)
        core = self.core
        app, space, key = str(body.get("app", "")), str(body.get("space", "")), str(body.get("key", ""))
        if not core.registry.app(app):
            raise ApiError(409, "AppGone", "The app this file belonged to no longer exists.")
        if space != "shared" and not (space.startswith("device:") and DEVICE_UID.match(space[7:]) and
                                      any("device:" + d["uid"] == space for d in core.registry.devices(app))):
            raise ApiError(409, "DeviceGone", "The device this file belonged to has been removed.")
        if safe_key(key) is None:
            raise bad("That file name can't be used.")
        src = os.path.join(slot["folder"], POCKET, "apps", app, space_dir(space), *key.split("/"))
        if not os.path.isfile(src):
            raise ApiError(404, "NoSuchKey", "That file is not on the card.")
        dst = core.layout.key_path("int", app, space, key)
        if os.path.isfile(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
            return {"restored": False, "reason": "The version in use is newer, so it was kept."}
        core.notify_change(app)
        kept = undo.keep(core.layout, app, space, key, "replaced by a restore from an SD card", "control panel")
        for s, _, sz in kept:
            p = core.layout.key_path(s, app, space, key)
            os.remove(p)
            core.usage.add(app, space, s, -sz)
        core.jobs._copy_to(src, dst, staging=os.path.join(core.layout.root("int"), "_staging"))
        core.usage.add(app, space, "int", os.path.getsize(dst))
        devs = core.registry.devices(app)
        keys = [d["access_key"] for d in devs if space == "shared" or "device:" + d["uid"] == space]
        core.rclone.forget(keys, ["/".join(["shared" if space == "shared" else "device"] + key.split("/")[:-1])])
        return {"restored": True}

    # ------------------------------------------------------------------ files put aside on the active card
    def unassigned(self):
        top = self.core.layout.unassigned_root()
        groups = {}
        if top:
            for path, st in walk_files(top):
                parts = _split_rel(os.path.relpath(path, top))
                if not parts:
                    continue
                g = groups.setdefault((parts[0], parts[1]), {"app": parts[0], "space": parts[1], "files": 0, "size": 0})
                g["files"] += 1
                g["size"] += st.st_size
        return {"groups": sorted(groups.values(), key=lambda g: (g["app"], g["space"]))}

    def _unassigned_dir(self, body):
        top = self.core.layout.unassigned_root()
        app, space = str(body.get("app", "")), str(body.get("space", ""))
        if not top:
            raise ApiError(409, "NoActiveCard", "There is no SD card in use.")
        try:
            d = os.path.join(top, app, space_dir(space))
        except ValueError:
            raise bad("Unknown space.")
        if not app or "/" in app or "\\" in app or app.startswith(".") or not os.path.isdir(d):
            raise ApiError(404, "NoSuchGroup", "No such unassigned files.")
        return app, space, d

    def unassigned_assign(self, body):
        """Give unassigned files to an app: they go into its shared space under unassigned/<old app>/."""
        core = self.core
        app, space, d = self._unassigned_dir(body)
        to = str(body.get("to", ""))
        if not core.registry.app(to):
            raise ApiError(404, "NoSuchApp", f"No app called {to}.")
        core.notify_change(to)
        moved = 0
        for path, _ in list(walk_files(d)):
            key = "/".join(["unassigned", app] + os.path.relpath(path, d).split(os.sep))
            dst = core.layout.key_path("sd", to, "shared", key)
            if dst is None:
                continue
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(path, dst)
            moved += 1
        shutil.rmtree(d, ignore_errors=True)
        remove_empty_dirs(os.path.dirname(d), core.layout.unassigned_root())
        core.usage.scan()
        core.rclone.forget([dv["access_key"] for dv in core.registry.devices(to)], ["shared/unassigned"])
        return {"ok": True, "files": moved}

    def unassigned_delete(self, body):
        core = self.core
        app, space, d = self._unassigned_dir(body)
        now = datetime.datetime.now()
        n = 0
        root = core.layout.root("sd")
        for path, _ in list(walk_files(d)):
            key = "/".join(os.path.relpath(path, d).split(os.sep))
            _to_card_undo(root, now, app, space, key, path, "unassigned files deleted")
            n += 1
        shutil.rmtree(d, ignore_errors=True)
        remove_empty_dirs(os.path.dirname(d), core.layout.unassigned_root())
        core.usage.scan()
        return {"ok": True, "files": n}

    def forget(self, card_id):
        """Remove a shelved card's record (the card was formatted, lost or thrown away). Nothing on any card
        changes; if that card turns up again it is taken back from the marker it carries."""
        card = self.card(card_id)
        if not card:
            raise ApiError(404, "NoSuchCard", "No such card.")
        if card["state"] != "shelved":
            raise ApiError(409, "CardInUse", "Only a card on the shelf can be forgotten.")
        if self.slot and self.slot["card"] == card_id:
            raise ApiError(409, "CardInPhone", "That card is in the phone; take it out first.")
        with self._lock:
            self.con.execute("DELETE FROM card_files WHERE card_id=?", (card_id,))
            self.con.execute("DELETE FROM cards WHERE id=?", (card_id,))
            self.con.commit()
        self._log(f"{card['name']} forgotten")
        return {"ok": True}

    def rename(self, card_id, body):
        card = self.card(card_id)
        if not card:
            raise ApiError(404, "NoSuchCard", "No such card.")
        name = str(body.get("name", "")).strip()
        if not 1 <= len(name) <= 40:
            raise bad("A card name is 1-40 characters.")
        self._exec("UPDATE cards SET name=? WHERE id=?", (name, card_id))
        if self.slot and self.slot["card"] == card_id and self.slot["folder"]:
            root = os.path.join(self.slot["folder"], POCKET)
            m = self._read_marker(root)
            if m:
                m["name"] = name
                self._write_marker(root, m)
        return self._public(self.card(card_id))


def _to_card_undo(root, now, app, space, key, src, reason):
    """Move a file on a card into that card's own undo, listed like any other undo entry."""
    day, t = now.strftime("%Y-%m-%d"), now.strftime("%H%M%S%f")
    undo_root = os.path.join(root, "_undo")
    dst = os.path.join(undo_root, day, app, space_dir(space), *key.split("/")) + "~" + t
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    size = os.path.getsize(src)
    os.replace(src, dst)
    remove_empty_dirs(os.path.dirname(src), root)
    with open(os.path.join(undo_root, day, "index.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps({"time": now.isoformat(timespec="seconds"), "app": app, "space": space, "key": key,
                            "path": os.path.relpath(dst, undo_root), "size": size, "reason": reason,
                            "device": "SD card"}) + "\n")
