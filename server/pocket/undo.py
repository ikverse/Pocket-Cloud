"""The 14-day undo: before a file is deleted or overwritten, its current version is kept under
<storage>/_undo/<date>/<app>/<space>/<key>~<time>, on the same storage it was on.

On internal storage this is a hard link (instant, no extra space until the original goes away). Where hard links
don't exist (the SD card's filesystems, and Python on Android, which has no os.link at all) it is a copy. Each kept version is also listed in <storage>/_undo/<date>/index.jsonl
so the control panel can show and restore it.
"""

import datetime
import json
import os
import shutil
import threading

from .layout import space_dir

_index_lock = threading.Lock()


def _stamp(now):
    return now.strftime("%Y-%m-%d"), now.strftime("%H%M%S%f")


def _link_or_copy(src, dst):
    """Keep src as dst: a hard link where there are hard links, otherwise a copy with the same modification time.
    The copy deliberately leaves permissions alone (shutil.copy2 would set them, which the SD card's filesystems
    refuse). A file that vanishes meanwhile raises FileNotFoundError."""
    try:
        os.link(src, dst)
        return
    except (OSError, AttributeError):       # AttributeError: this Python has no os.link
        pass
    st = os.stat(src)
    try:
        shutil.copyfile(src, dst)
        try:
            os.utime(dst, ns=(st.st_atime_ns, st.st_mtime_ns))
        except OSError:
            pass                            # the time is a nicety; the content is what matters
    except BaseException:
        try:
            os.remove(dst)
        except OSError:
            pass
        raise


def keep(layout, app_id, space, key, reason, device_uid, now=None):
    """Keep the current version(s) of a key. Returns [(storage, kept_path, size)] (empty if it doesn't exist)."""
    now = now or datetime.datetime.now()
    day, t = _stamp(now)
    kept = []
    for storage in layout.storages():
        src = layout.key_path(storage, app_id, space, key)
        if src is None or not os.path.isfile(src):
            continue
        dst = os.path.join(layout.undo_root(storage), day, app_id, space_dir(space), *key.split("/"))
        dst = f"{dst}~{t}"
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        try:
            _link_or_copy(src, dst)
        except FileNotFoundError:
            continue
        size = os.path.getsize(dst)
        kept.append((storage, dst, size))
        _append_index(layout, storage, day, {
            "time": now.isoformat(timespec="seconds"), "app": app_id, "space": space, "key": key,
            "path": os.path.relpath(dst, layout.undo_root(storage)), "size": size, "reason": reason,
            "device": device_uid,
        })
    return kept


def drop(kept):
    """Throw kept versions away again, when the delete or overwrite did not happen after all."""
    for _, path, _ in kept:
        try:
            os.remove(path)
        except FileNotFoundError:
            pass


def _append_index(layout, storage, day, entry):
    path = os.path.join(layout.undo_root(storage), day, "index.jsonl")
    with _index_lock:
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
