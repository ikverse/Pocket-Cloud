"""pocket.app(name): the shared library surface of docs/app-api.md section 10, on the plain /_pocket/ routes."""

import datetime
import hashlib
import json
import mimetypes
import os
import re
import shutil
import sqlite3
import tempfile
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import List, Optional

from . import _Device, _write_private, _own_addresses, _sign
from ._errors import (AccessDenied, CursorExpired, FeatureOff, NotFound, PocketError, PreconditionFailed,
                      Unreachable)
from ._http import Transport, check_ok, file_sha256

# what a 0.2.x server (no "features" in health) has
OLD_FEATURES = ("quota", "conflicts", "mirror-report", "share", "push")
VERSION = re.compile(r"^[A-Za-z0-9._+-]{1,64}$")
SQLITE_MAGIC = b"SQLite format 3\x00"
_MISSING = object()


# ---------------------------------------------------------------------------------------------------- data types

@dataclass
class Entry:
    bucket: str
    key: str
    size: Optional[int]
    etag: Optional[str]
    modified: Optional[float]


@dataclass
class Change:
    seq: int
    bucket: str
    key: str
    kind: str
    from_: Optional[dict]
    size: Optional[int]
    etag: Optional[str]
    time: float
    device: Optional[str]
    source: str

    @classmethod
    def from_json(cls, d):
        return cls(seq=d.get("seq"), bucket=d.get("bucket"), key=d.get("key"), kind=d.get("kind"),
                   from_=d.get("from"), size=d.get("size"), etag=d.get("etag"), time=d.get("time"),
                   device=d.get("device"), source=d.get("source"))


@dataclass
class ReleaseFile:
    name: str
    path: str
    size: int
    sha256: str
    platform: Optional[str] = None
    url: Optional[str] = None


@dataclass
class Release:
    app: str
    version: str
    published: int
    notes: str
    files: List[ReleaseFile]
    _pocket: object = field(default=None, repr=False, compare=False)

    def download(self, dest):
        """Fetch the release's files and check each one's size and sha256 (ChecksumMismatch otherwise).
        dest is a folder (existing, or ending in a separator), or a file path when the release has one file.
        Returns the paths written."""
        as_dir = os.path.isdir(dest) or dest.endswith(("/", os.sep))
        if not as_dir and len(self.files) != 1:
            raise PocketError("This release has several files: give a folder to download into.", "BadRequest")
        out = []
        for f in self.files:
            target = os.path.join(dest, f.name) if as_dir else dest
            self._pocket._fetch_release_file(f, target)
            out.append(target)
        return out


class Share(dict):
    """The share link JSON from the server (token, url, expires, ...); also readable as attributes."""

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name) from None


@dataclass
class SyncResult:
    uploaded: List[str] = field(default_factory=list)
    downloaded: List[str] = field(default_factory=list)
    deleted_local: List[str] = field(default_factory=list)
    deleted_remote: List[str] = field(default_factory=list)
    conflicts: List[str] = field(default_factory=list)


def _entry(bucket, d):
    return Entry(bucket=d.get("bucket", bucket), key=d["key"], size=d.get("size"), etag=d.get("etag"),
                 modified=d.get("modified"))


def _utcnow():
    return datetime.datetime.now(datetime.timezone.utc)


# ---------------------------------------------------------------------------------------------------- enrollment

class _AppDevice(_Device):
    """The same saved key as connect() uses (<home>/apps/<app>.json), with the library's error types."""

    def enroll(self):
        body = json.dumps({"secret": self.enroll_secret, "app": self.app, "device_id": self.device_id,
                           "device_name": self.device_name, "addresses": _own_addresses()}).encode()
        req = urllib.request.Request(self.endpoint + "/_pocket/enroll", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                got = json.loads(r.read())
        except urllib.error.HTTPError as e:
            try:
                d = json.loads(e.read())
                msg, code = d.get("message"), d.get("error")
            except ValueError:
                msg, code = None, None
            finally:
                e.close()
            raise AccessDenied(f"Pocket Cloud refused to enroll {self.app}: {msg or e}", code or "AccessDenied",
                               e.code) from None
        except OSError as e:
            raise Unreachable(f"Pocket Cloud can't be reached at {self.endpoint}: {e}", "Unreachable", 0) from None
        creds = {"endpoint": self.endpoint, "device_id": self.device_id, "access_key": got["access_key"],
                 "secret_key": got["secret_key"], "region": got.get("region", "us-east-1"),
                 "endpoints": got.get("endpoints") or {}}
        _write_private(self.path, json.dumps(creds, indent=2))
        self.creds = creds
        return creds


def app(name, *, endpoint=None, enroll_secret=None, device_id=None, device_name=None, home=None, timeout=30):
    """Open app `name` on Pocket Cloud: enrolls this device the first time, then reuses the saved key."""
    return App(name, endpoint=endpoint, enroll_secret=enroll_secret, device_id=device_id, device_name=device_name,
               home=home, timeout=timeout)


class _Releases:
    def __init__(self, pocket):
        self._pocket = pocket

    def latest(self, platform=None):
        """The newest release from latest.json, or None. With platform, only its files (and 'any' / unmarked)."""
        return self._pocket._latest_release(platform)


class _Handle:
    def __init__(self, thread, stop):
        self._thread, self._stop = thread, stop

    def stop(self, timeout=None):
        self._stop.set()
        self._thread.join(timeout)


class ChangeFeed:
    """Iterator of Change; .cursor is where to carry on (after the last change seen, or 'now')."""

    def __init__(self, pocket, since, wait):
        self._pocket, self.cursor, self._wait = pocket, since, wait

    def __iter__(self):
        while True:
            params = [("since", self.cursor), ("wait", self._wait or None)]
            d = self._pocket._call("GET", "/_pocket/changes", params, retry=True).json()
            for c in d.get("changes", []):
                yield Change.from_json(c)
            self.cursor = d.get("cursor")
            if not d.get("more"):
                return


class App:
    def __init__(self, name, *, endpoint=None, enroll_secret=None, device_id=None, device_name=None, home=None,
                 timeout=30):
        try:
            self._dev = _AppDevice(name, home, endpoint, enroll_secret, device_id, device_name)
        except (AccessDenied, Unreachable):
            raise
        except PocketError as e:
            if "not set up" in e.message:
                raise PocketError(e.message, "NotConfigured") from None
            raise
        self.name = name
        self.device_id = self._dev.device_id
        self.device_name = self._dev.device_name
        self._http = Transport(self._dev, timeout)
        self._features = None
        self._info = None
        try:
            self._load_health()
        except Unreachable:
            pass                                   # learned on first use instead

    # ---------------------------------------------------------------------------------------- features

    def _load_health(self):
        resp = self._http.unsigned("GET", "/_pocket/health", retry=False)
        try:
            d = resp.json() if resp.status == 200 else {}
        except ValueError:
            d = {}
        feats = d.get("features")
        self._features = list(feats) if isinstance(feats, list) else list(OLD_FEATURES)

    @property
    def features(self):
        if self._features is None:
            self._load_health()
        return list(self._features)

    def _need(self, feature):
        if feature not in self.features:
            raise FeatureOff(f"This Pocket Cloud server does not have '{feature}' yet.", "FeatureMissing", 0)

    def info(self):
        """GET /_pocket/app: buckets, features, publish/notify/push, retention rules, read-only."""
        self._need("files")
        self._info = self._call("GET", "/_pocket/app", retry=True).json()
        return self._info

    def _cached_info(self):
        return self._info if self._info is not None else self.info()

    # ---------------------------------------------------------------------------------------- plumbing

    def _call(self, method, path, params=None, body=b"", headers=None, retry=False, sink=None, ok=(200,)):
        return self._http.request(method, path, params, body, headers, retry, sink, ok)

    @staticmethod
    def _file_path(bucket, key):
        return f"/_pocket/files/{_sign.quote(bucket)}/{_sign.key_path(key)}"

    def _json(self, method, path, value, retry=False):
        body = json.dumps(value).encode("utf-8")
        return self._call(method, path, body=body, headers={"Content-Type": "application/json"}, retry=retry).json()

    # ---------------------------------------------------------------------------------------- files

    def get(self, key, bucket="shared"):
        """The file's bytes. Missing: NotFound."""
        self._need("files")
        return self._call("GET", self._file_path(bucket, key), retry=True).data

    def _get_with_etag(self, key, bucket):
        resp = self._call("GET", self._file_path(bucket, key), retry=True)
        return resp.data, resp.headers.get("etag")

    def get_file(self, key, dest, bucket="shared"):
        """Stream the file to dest (through a temp file in the same folder, renamed at the end). Returns Entry."""
        self._need("files")
        folder = os.path.dirname(os.path.abspath(dest))
        os.makedirs(folder, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=folder, prefix=".pocket-tmp-")
        try:
            with os.fdopen(fd, "w+b") as f:
                resp = self._call("GET", self._file_path(bucket, key), retry=True, sink=f)
            os.replace(tmp, dest)
        except BaseException:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise
        return self._entry_from_headers(bucket, key, resp.headers)

    @staticmethod
    def _entry_from_headers(bucket, key, h):
        size = h.get("content-length")
        mod = h.get("x-pocket-modified")
        return Entry(bucket=bucket, key=key, size=int(size) if size is not None else None, etag=h.get("etag"),
                     modified=float(mod) if mod else None)

    def _put(self, key, body, bucket="shared", if_match=None, if_none_match=False, content_type=None, modified=None):
        self._need("files")
        headers = {}
        if content_type:
            headers["Content-Type"] = content_type
        if if_match:
            headers["if-match"] = if_match
        if if_none_match:
            headers["if-none-match"] = "*"
        if modified is not None:
            headers["x-pocket-modified"] = repr(float(modified))
        conditional = bool(if_match or if_none_match)
        d = self._call("PUT", self._file_path(bucket, key), body=body, headers=headers, retry=conditional).json()
        return _entry(bucket, d)

    def put(self, key, data, bucket="shared", if_match=None, content_type=None):
        """Write data (bytes or str) at key. With if_match, only if the current file has that ETag."""
        if isinstance(data, str):
            data = data.encode("utf-8")
            content_type = content_type or "text/plain; charset=utf-8"
        return self._put(key, bytes(data), bucket, if_match=if_match, content_type=content_type)

    def put_file(self, key, src, bucket="shared", if_match=None, *, _if_none_match=False):
        """Stream the file at src to key, keeping its modification time."""
        ctype = mimetypes.guess_type(src)[0]
        with open(src, "rb") as f:
            return self._put(key, f, bucket, if_match=if_match, if_none_match=_if_none_match, content_type=ctype,
                             modified=os.stat(src).st_mtime)

    def stat(self, key, bucket="shared"):
        """Entry for key, or None when there is no file."""
        self._need("files")
        try:
            resp = self._call("HEAD", self._file_path(bucket, key), retry=True)
        except NotFound as e:
            if e.code == "NoSuchKey":
                return None
            raise
        return self._entry_from_headers(bucket, key, resp.headers)

    def list(self, prefix="", bucket="shared", folders=False):
        """Every file whose key starts with prefix, by key, all pages. With folders=True, keys below the next '/'
        are rolled up into folder entries (key ending in '/', size/etag/modified None)."""
        self._need("files")
        cursor = None
        while True:
            params = [("bucket", bucket), ("prefix", prefix or None), ("delimiter", "/" if folders else None),
                      ("cursor", cursor)]
            d = self._call("GET", "/_pocket/list", params, retry=True).json()
            items = [(f["key"], _entry(bucket, f)) for f in d.get("files", [])]
            items += [(k, Entry(bucket, k, None, None, None)) for k in d.get("folders", []) or []]
            for _, e in sorted(items, key=lambda kv: kv[0].encode("utf-8")):
                yield e
            cursor = d.get("cursor")
            if not cursor:
                return

    def delete(self, key, bucket="shared", if_match=None):
        """Delete the file at key. Missing: NotFound."""
        self._need("files")
        self._call("DELETE", self._file_path(bucket, key), headers={"if-match": if_match} if if_match else None)

    def move(self, src, dst, bucket="shared", to_bucket=None, overwrite=False, if_match=None):
        """Move one file on the server (same ETag and time). An existing dst without overwrite: PreconditionFailed."""
        self._need("move")
        _sign.check_key(src)
        _sign.check_key(dst)
        body = {"from": {"bucket": bucket, "key": src}, "to": {"bucket": to_bucket or bucket, "key": dst},
                "overwrite": bool(overwrite)}
        if if_match:
            body["if_match"] = if_match
        d = self._json("POST", "/_pocket/move", body)
        to = d.get("to", body["to"])
        return Entry(to["bucket"], to["key"], d.get("size"), d.get("etag"), d.get("modified"))

    def get_json(self, key, bucket="shared", default=_MISSING):
        """get + json.loads. Missing with a default gives the default."""
        try:
            data = self.get(key, bucket)
        except NotFound as e:
            if default is not _MISSING and e.code == "NoSuchKey":
                return default
            raise
        return json.loads(data.decode("utf-8"))

    def put_json(self, key, value, bucket="shared", if_match=None):
        data = json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")
        return self._put(key, data, bucket, if_match=if_match, content_type="application/json")

    # ---------------------------------------------------------------------------------------- backup

    def backup(self, path, keep=7, prefix="backups/", bucket="shared"):
        """Upload path to <prefix><name>/<UTC yyyymmdd-HHMMSS>-<name>, then delete all but the keep newest there.
        SQLite databases are copied with sqlite3's backup API first, so a database in use is copied safely."""
        if int(keep) < 1:
            raise PocketError("keep is at least 1.", "BadRequest")
        name = os.path.basename(os.path.abspath(path))
        folder = f"{prefix}{name}/"
        key = f"{folder}{_utcnow().strftime('%Y%m%d-%H%M%S')}-{name}"
        with open(path, "rb") as f:
            is_sqlite = f.read(len(SQLITE_MAGIC)) == SQLITE_MAGIC
        if is_sqlite:
            tmpdir = tempfile.mkdtemp(prefix="pocket-backup-")
            try:
                copy = os.path.join(tmpdir, name)
                src = sqlite3.connect(path)
                try:
                    dst = sqlite3.connect(copy)
                    try:
                        src.backup(dst)
                    finally:
                        dst.close()
                finally:
                    src.close()
                st = os.stat(path)
                os.utime(copy, (st.st_atime, st.st_mtime))
                entry = self.put_file(key, copy, bucket)
            finally:
                shutil.rmtree(tmpdir, ignore_errors=True)
        else:
            entry = self.put_file(key, path, bucket)
        kept = [e for e in self.list(folder, bucket) if e.key.endswith("-" + name)]
        for old in kept[:-int(keep)]:
            try:
                self.delete(old.key, bucket)
            except NotFound:
                pass
        return entry

    def restore_latest(self, prefix, dest, bucket="shared"):
        """Download the newest file under prefix (by modification time, then key) to dest. None when empty."""
        best = None
        for e in self.list(prefix, bucket):
            if best is None or (e.modified or 0, e.key) >= (best.modified or 0, best.key):
                best = e
        if best is None:
            return None
        self.get_file(best.key, dest, bucket)
        return best

    # ---------------------------------------------------------------------------------------- sync

    def sync(self, folder, bucket="shared", prefix="", allow_mass_delete=False):
        """Two-way sync between folder and prefix in bucket. See README / docs/app-api.md section 10."""
        from ._sync import sync
        return sync(self, folder, bucket, prefix, allow_mass_delete)

    # ---------------------------------------------------------------------------------------- changes

    def changes(self, since=None, wait=0):
        """Iterator of Change after cursor `since` (all pages). Without since: no changes, and .cursor is 'now'."""
        self._need("changes")
        return ChangeFeed(self, since, wait)

    def on_change(self, callback, since=None):
        """Call callback(change) for every new change, in a background thread. Returns a handle with .stop()."""
        self._need("changes")
        stop = threading.Event()

        def run():
            cursor = since
            if cursor is None:
                feed = self.changes()
                list(feed)
                cursor = feed.cursor
            while not stop.is_set():
                try:
                    feed = self.changes(cursor, wait=10)
                    for c in feed:
                        if stop.is_set():
                            return
                        callback(c)
                    cursor = feed.cursor
                except CursorExpired as e:
                    cursor = e.cursor
                except PocketError:
                    stop.wait(5)

        t = threading.Thread(target=run, name=f"pocket-changes-{self.name}", daemon=True)
        t.start()
        return _Handle(t, stop)

    # ---------------------------------------------------------------------------------------- publish

    def releases_url(self):
        """The key-less publish URL (None when the server has no Tailscale address)."""
        self._need("publish")
        return (self._cached_info().get("publish") or {}).get("url")

    def publish(self, file, version, notes="", platform=None):
        """Upload file as release `version` and point releases/latest.json at it (section 6 order)."""
        self._need("files")
        if not VERSION.match(str(version)):
            raise PocketError("A version is 1-64 of A-Z a-z 0-9 . _ + -", "BadRequest")
        name = os.path.basename(file)
        path = f"{version}/{name}"
        with open(file, "rb") as f:
            sha, size = file_sha256(f)
        self.put_file("releases/" + path, file)
        for _ in range(20):
            try:
                _, etag = self._get_with_etag("releases/latest.json", "shared")
            except NotFound:
                etag = None
            manifest = {"app": self.name, "version": str(version), "published": int(time.time()),
                        "notes": notes or "",
                        "files": [{"name": name, "path": path, "size": size, "sha256": sha, "platform": platform}]}
            data = json.dumps(manifest, indent=2).encode("utf-8")
            try:
                self._put("releases/latest.json", data, "shared", if_match=etag, if_none_match=etag is None,
                          content_type="application/json")
                break
            except PreconditionFailed:
                continue
        else:
            raise PreconditionFailed("releases/latest.json kept changing; try again.", "PreconditionFailed", 412)
        return self._release(manifest, self._publish_url())

    def _publish_url(self):
        try:
            pub = self._cached_info().get("publish") or {}
        except PocketError:
            return None
        return pub.get("url") if pub.get("enabled") else None

    def _release(self, d, url):
        files = [ReleaseFile(name=f.get("name"), path=f.get("path"), size=f.get("size"), sha256=f.get("sha256"),
                             platform=f.get("platform"),
                             url=(url + "/".join(_sign.quote(s) for s in f["path"].split("/")))
                             if url and f.get("path") else None)
                 for f in d.get("files", [])]
        return Release(app=d.get("app"), version=d.get("version"), published=d.get("published"),
                       notes=d.get("notes", ""), files=files, _pocket=self)

    def releases(self):
        return _Releases(self)

    def _latest_release(self, platform):
        url = self._publish_url() if "publish" in self.features else None
        data = None
        if url:
            try:
                resp = self._http.unsigned("GET", "", url=url + "latest.json")
                if resp.status == 200:
                    data = resp.data
            except PocketError:
                data = None
        if data is None:
            try:
                data = self.get("releases/latest.json")
            except NotFound:
                return None
        rel = self._release(json.loads(data.decode("utf-8")), url)
        if platform is not None:
            rel.files = [f for f in rel.files if f.platform in (platform, "any", None)]
            if not rel.files:
                return None
        return rel

    def _fetch_release_file(self, f, target):
        folder = os.path.dirname(os.path.abspath(target))
        os.makedirs(folder, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=folder, prefix=".pocket-tmp-")
        try:
            with os.fdopen(fd, "w+b") as out:
                done = False
                if f.url:
                    try:
                        resp = self._http.unsigned("GET", "", url=f.url, sink=out)
                        done = resp.status == 200
                    except PocketError:
                        done = False
                if not done:
                    out.seek(0)
                    out.truncate()
                    self._call("GET", self._file_path("shared", "releases/" + f.path), retry=True, sink=out)
                out.flush()
                out.seek(0)
                sha, size = file_sha256(out)
            if size != f.size or sha != f.sha256:
                raise PocketError(f"{f.name} did not match the release (size {size}, sha256 {sha}).",
                                  "ChecksumMismatch")
            os.replace(tmp, target)
        except BaseException:
            try:
                os.remove(tmp)
            except OSError:
                pass
            raise

    # ---------------------------------------------------------------------------------------- notify, share, quota

    def notify(self, message, title=None, tags=None, priority=3, click=None):
        """A notification on this app's ntfy topic (pocket-<app>-notify). Not retried."""
        self._need("notify")
        body = {"message": message, "priority": priority}
        if title is not None:
            body["title"] = title
        if tags:
            body["tags"] = list(tags)
        if click is not None:
            body["click"] = click
        return self._json("POST", "/_pocket/notify", body)

    def share(self, key, expires=86400, bucket="shared", password=None, max_downloads=None):
        """A share link for one file (the existing POST /_pocket/share). expires in seconds."""
        self._need("share")
        body = {"bucket": bucket, "key": key, "expires_s": int(expires)}
        if password:
            body["password"] = password
        if max_downloads:
            body["max_downloads"] = int(max_downloads)
        return Share(self._json("POST", "/_pocket/share", body))

    def quota(self):
        """The existing GET /_pocket/quota: allocation, used_app, used_device, free, read_only, ..."""
        self._need("quota")
        return self._call("GET", "/_pocket/quota", retry=True).json()

    def __repr__(self):
        return f"<pocket app {self.name!r} at {self._dev.endpoint}>"
