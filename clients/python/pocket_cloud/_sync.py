"""Two-way folder sync (docs/app-api.md section 10, "sync rules").

State lives in <folder>/.pocket-sync.json:
    {"version": 1, "bucket": ..., "prefix": ..., "cursor": <change-feed cursor or null>, "full": <list next time>,
     "files": {"<relative key>": {"size": n, "mtime_ns": n, "etag": "\"...\""}}}
A file is in "files" when, after the last run, the local copy and the remote copy were the same.
"""

import datetime
import hashlib
import json
import os
import re

from . import _sign, _write_private
from ._errors import CursorExpired, NotFound, PocketError, PreconditionFailed
from ._app import SyncResult

STATE = ".pocket-sync.json"
MASS_DELETE = 50


def _ignored(rel):
    return any(seg.startswith(".pocket-") for seg in rel.split("/"))


def _scan(folder):
    out = {}
    for root, dirs, files in os.walk(folder):
        dirs[:] = [d for d in dirs if not d.startswith(".pocket-")]
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, folder).replace(os.sep, "/")
            if _ignored(rel):
                continue
            try:
                _sign.check_key(rel)
                st = os.stat(full)
            except (PocketError, OSError):
                continue
            out[rel] = (st.st_size, st.st_mtime_ns)
    return out


def _md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return '"' + h.hexdigest() + '"'


def _load_state(folder, bucket, prefix):
    try:
        with open(os.path.join(folder, STATE), encoding="utf-8") as f:
            st = json.load(f)
    except (FileNotFoundError, ValueError):
        return None
    if st.get("bucket") != bucket or st.get("prefix") != prefix or not isinstance(st.get("files"), dict):
        return None                    # a state for another target counts as a first run
    return st


def _conflict_name(rel, device_name):
    folder, _, name = rel.rpartition("/")
    stem, dot, ext = name.rpartition(".")
    if not dot or not stem:
        stem, ext = name, ""
    who = re.sub(r"[\\/:*?\"<>|]", "-", device_name or "device").strip() or "device"
    when = datetime.datetime.now().strftime("%Y-%m-%d %H%M")
    new = f"{stem} (conflict {who} {when})" + (f".{ext}" if ext else "")
    return (folder + "/" + new) if folder else new


def sync(pocket, folder, bucket="shared", prefix="", allow_mass_delete=False):
    pocket._need("files")
    folder = os.path.abspath(folder)
    os.makedirs(folder, exist_ok=True)
    result = SyncResult()
    state = _load_state(folder, bucket, prefix)
    first = state is None
    if first:
        state = {"version": 1, "bucket": bucket, "prefix": prefix, "cursor": None, "full": True, "files": {}}
    known = state["files"]
    has_feed = "changes" in pocket.features

    # ---- what the remote side holds now: {rel: etag}
    def full_listing():
        return {e.key[len(prefix):]: e.etag for e in pocket.list(prefix, bucket)
                if not _ignored(e.key[len(prefix):])}

    remote = None
    new_cursor = None
    if has_feed and state.get("cursor") and not state.get("full"):
        remote = {rel: s["etag"] for rel, s in known.items()}
        try:
            feed = pocket.changes(state["cursor"])
            for c in feed:
                if c.bucket != bucket:
                    continue
                if c.kind == "move" and c.from_ and c.from_.get("bucket") == bucket \
                        and c.from_.get("key", "").startswith(prefix):
                    remote.pop(c.from_["key"][len(prefix):], None)
                if not c.key.startswith(prefix):
                    continue
                rel = c.key[len(prefix):]
                if _ignored(rel):
                    continue
                if c.kind == "delete":
                    remote.pop(rel, None)
                else:
                    remote[rel] = c.etag
            new_cursor = feed.cursor
        except CursorExpired as e:
            new_cursor = e.cursor
            remote = None
    if remote is None:
        if has_feed and new_cursor is None:
            feed = pocket.changes()            # "now", taken before the listing so nothing is missed
            list(feed)
            new_cursor = feed.cursor
        remote = full_listing()

    local = _scan(folder)

    def path_of(rel):
        return os.path.join(folder, *rel.split("/"))

    def record(rel, etag):
        st = os.stat(path_of(rel))
        known[rel] = {"size": st.st_size, "mtime_ns": st.st_mtime_ns, "etag": etag}

    def download(rel):
        target = path_of(rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        e = pocket.get_file(prefix + rel, target, bucket)
        if e.modified:
            os.utime(target, (e.modified, e.modified))
        record(rel, e.etag)
        result.downloaded.append(prefix + rel)

    def upload(rel, if_match=None):
        """Upload; a remote file that changed meanwhile turns this into a conflict."""
        try:
            e = pocket.put_file(prefix + rel, path_of(rel), bucket, if_match=if_match, _if_none_match=not if_match)
        except PreconditionFailed:
            conflict(rel)
            return
        record(rel, e.etag)
        result.uploaded.append(prefix + rel)

    def conflict(rel):
        """Keep the remote file under its key; the local version goes up (and stays here) under a conflict name."""
        new_rel = _conflict_name(rel, pocket.device_name)
        n = 2
        while new_rel in local or new_rel in remote or os.path.exists(path_of(new_rel)):
            base = _conflict_name(rel, pocket.device_name)
            stem, dot, ext = base.rpartition(".")
            new_rel = f"{stem} {n}.{ext}" if dot and "(conflict" in stem else f"{base} {n}"
            n += 1
        os.replace(path_of(rel), path_of(new_rel))
        e = pocket.put_file(prefix + new_rel, path_of(new_rel), bucket, _if_none_match=True)
        record(new_rel, e.etag)
        remote[new_rel] = e.etag
        try:
            download(rel)
            result.downloaded.remove(prefix + rel)
        except NotFound:
            known.pop(rel, None)
        result.conflicts += [prefix + rel, prefix + new_rel]

    def same(rel, etag):
        return etag is not None and _md5(path_of(rel)) == etag

    deletes_local, deletes_remote = [], []
    try:
        for rel in sorted(set(local) | set(remote) | set(known)):
            L, R, S = local.get(rel), remote.get(rel), known.get(rel)
            if rel not in remote:
                R = None
            if first or S is None:
                if L and rel in remote:
                    if same(rel, R):
                        record(rel, R)
                    else:
                        conflict(rel)
                elif L:
                    upload(rel)
                elif rel in remote:
                    download(rel)
                continue
            local_changed = L is not None and (L[0], L[1]) != (S["size"], S["mtime_ns"])
            remote_changed = rel in remote and R != S["etag"]
            if L and rel in remote:
                if local_changed and remote_changed:
                    if same(rel, R):
                        record(rel, R)
                    else:
                        conflict(rel)
                elif local_changed:
                    upload(rel, if_match=S["etag"])
                elif remote_changed:
                    download(rel)
            elif L:                                # gone remotely
                if local_changed:
                    upload(rel)
                else:
                    deletes_local.append(rel)
            elif rel in remote:                    # gone locally
                if remote_changed:
                    download(rel)
                else:
                    deletes_remote.append(rel)
            else:
                known.pop(rel, None)

        count = len(deletes_local) + len(deletes_remote)
        if count > MASS_DELETE and not allow_mass_delete:
            state["full"] = True                   # the next run lists everything, so the deletes stay pending
            state["cursor"] = new_cursor
            raise PocketError(f"Sync would delete {count} files ({len(deletes_local)} here, {len(deletes_remote)} on "
                              f"Pocket Cloud); nothing was deleted. Run again with allow_mass_delete=True if that "
                              f"is what you want.", "MassDelete")
        for rel in deletes_local:
            try:
                os.remove(path_of(rel))
            except FileNotFoundError:
                pass
            known.pop(rel, None)
            result.deleted_local.append(prefix + rel)
        for rel in deletes_remote:
            try:
                pocket.delete(prefix + rel, bucket, if_match=known[rel]["etag"])
            except NotFound:
                pass
            except PreconditionFailed:              # changed meanwhile: bring it back instead
                download(rel)
                continue
            known.pop(rel, None)
            result.deleted_remote.append(prefix + rel)
        state["cursor"] = new_cursor
        state["full"] = not has_feed
    finally:
        _write_private(os.path.join(folder, STATE), json.dumps(state, indent=1, sort_keys=True))
    return result
