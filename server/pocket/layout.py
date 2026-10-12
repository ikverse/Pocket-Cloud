"""Where everything lives on the two storages.

    <internal>/apps/<app>/shared/...            recent files of the app's shared space
    <internal>/apps/<app>/devices/<uid>/...     recent files of one device's private space
    <sd>/apps/<app>/...                         the same tree for files moved to the SD card
    <sd>/_copy/apps/...                         nightly mirror of everything still on internal storage
    <sd>/_unassigned/<app>/<space>/...          files of a returning card whose app or device no longer exists
    <storage>/_undo/<YYYY-MM-DD>/<app>/<space>/<key>~<time>   deleted and overwritten files (14-day undo)

The SD card can be absent (no card in the phone, or between two cards): then only internal storage exists, and
storages() says so. It can also change while the server runs, when cards are swapped (see cards.py).
"""

import os
import re
import sys

STORAGES = ("int", "sd")
FAMILY = "_family"          # the family bucket: a built-in app whose shared space member apps see as bucket "family"
APP_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
DEVICE_UID = re.compile(r"^[A-Za-z0-9-]{8,64}$")
TEMP_PREFIX = ".rclone_temp_"
_WIN_BAD = set('<>:"|?*\\')


def space_dir(space):
    """'shared' or 'device:<uid>' -> the folder under apps/<app>/."""
    if space == "shared":
        return "shared"
    kind, _, uid = space.partition(":")
    if kind != "device" or not DEVICE_UID.match(uid):
        raise ValueError("bad space " + space)
    return os.path.join("devices", uid)


class Layout:
    def __init__(self, internal_root, sd_root, state_dir):
        self.roots = {"int": os.path.abspath(internal_root), "sd": os.path.abspath(sd_root) if sd_root else None}
        self.state_dir = os.path.abspath(state_dir)

    @property
    def has_sd(self):
        return self.roots["sd"] is not None

    def storages(self):
        """The storages that exist right now: both, or only internal storage when there is no SD card."""
        return STORAGES if self.has_sd else ("int",)

    def set_sd(self, sd_root):
        self.roots["sd"] = os.path.abspath(sd_root) if sd_root else None

    def root(self, storage):
        return self.roots[storage]

    def apps_root(self, storage):
        return os.path.join(self.roots[storage], "apps")

    def app_dir(self, storage, app):
        return os.path.join(self.apps_root(storage), app)

    def space_path(self, storage, app, space):
        return os.path.join(self.app_dir(storage, app), space_dir(space))

    def undo_root(self, storage):
        return os.path.join(self.roots[storage], "_undo")

    def copy_root(self):
        return os.path.join(self.roots["sd"], "_copy") if self.has_sd else None

    def unassigned_root(self):
        return os.path.join(self.roots["sd"], "_unassigned") if self.has_sd else None

    def ensure(self):
        for s in self.storages():
            os.makedirs(self.apps_root(s), exist_ok=True)
            os.makedirs(self.undo_root(s), exist_ok=True)
        if self.has_sd:
            os.makedirs(self.copy_root(), exist_ok=True)
        os.makedirs(self.state_dir, exist_ok=True)

    def ensure_space(self, app, space):
        for s in self.storages():
            os.makedirs(self.space_path(s, app, space), exist_ok=True)

    def key_path(self, storage, app, space, key):
        """File path for an S3 key, or None when the key can't be mapped to a plain file name
        (the gateway then leaves that request entirely to rclone), or the storage isn't there."""
        rel = safe_key(key)
        if rel is None or self.roots[storage] is None:
            return None
        return os.path.join(self.space_path(storage, app, space), *rel)


def safe_key(key):
    """Split an S3 key into path parts if every part is a plain, portable file name."""
    if not key or key.startswith("/") or key.endswith("/"):
        return None
    parts = key.split("/")
    for p in parts:
        if p in ("", ".", "..") or "\x00" in p or p.startswith(TEMP_PREFIX):
            return None
        if any(ord(c) < 32 for c in p):
            return None
        if sys.platform == "win32" and (set(p) & _WIN_BAD or p[-1] in ". "):
            return None
    return parts


def walk_files(top):
    """Yield (path, os.stat_result) for every regular file under top; rclone's in-progress temp files are skipped."""
    stack = [top]
    while stack:
        d = stack.pop()
        try:
            with os.scandir(d) as it:
                for e in it:
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.is_file(follow_symlinks=False) and not e.name.startswith(TEMP_PREFIX):
                        try:
                            yield e.path, e.stat(follow_symlinks=False)
                        except FileNotFoundError:
                            pass
        except (FileNotFoundError, NotADirectoryError):
            pass


def remove_empty_dirs(top, stop_at):
    """Remove empty folders from top upwards, never removing stop_at itself."""
    d = os.path.abspath(top)
    stop_at = os.path.abspath(stop_at)
    while d.startswith(stop_at) and d != stop_at:
        try:
            os.rmdir(d)
        except OSError:
            return
        d = os.path.dirname(d)
