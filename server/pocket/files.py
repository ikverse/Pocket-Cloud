"""The v2 app-facing routes of the gateway (docs/app-api.md): plain file routes, move, the change feed, the app's
switches, notifications and the key-less publish folder.

Files are read and written directly in the storage layout, as the control panel's files page does, not through
rclone: an upload is staged on the storage it will live on, hashed as it streams in, checked against
x-pocket-content-sha256 and only then put in place with one rename, so a refused or broken upload never
changes anything and readers see the old file or the new one. A move is a rename. Afterwards rclone is told
to forget its cached listings of the folders involved, so S3 clients see the change at once.

Every route goes through the same checks as S3 requests: the device checks, read-only mode, room
(check_space), undo copies of anything overwritten or deleted, usage, push, the request log and the change feed.
"""

import base64
import email.utils
import hashlib
import http.client
import ipaddress
import json
import os
import re
import stat
import time
import uuid
from urllib.parse import unquote_to_bytes

from . import sigv4, undo
from .changes import BadCursor, CursorExpired
from .layout import APP_ID, FAMILY, TEMP_PREFIX, remove_empty_dirs, safe_key, walk_files
from .refuse import Refuse

MAX_PUT = 2 * 1024 ** 3              # one file through PUT /_pocket/files (bigger: S3 multipart)
CHUNK = 1024 * 1024
LIST_CURSOR_S = 3600
LIST_HASH_BYTES = 256 * 1024 ** 2    # a listing page hashes at most about this much (and LIST_HASH_S) ...
LIST_HASH_S = 2.0                    # ... beyond its first file, then ends early with a cursor
SHA_HEX = re.compile(r"^[0-9a-f]{64}$")
PUB = "/_pocket/pub/"
FILES = "/_pocket/files/"


def is_tailscale(addr):
    try:
        return ipaddress.ip_address(addr) in ipaddress.ip_network("100.64.0.0/10")
    except ValueError:
        return False


def _bad_key(msg="Keys are 1-1024 bytes of UTF-8 without empty, '.' or '..' parts, and don't start or end with '/'."):
    return Refuse(400, "BadKey", msg)


def check_key(key):
    """Refuse (BadKey) a key that breaks the contract's rules, or that can't be stored as a file name here."""
    if not isinstance(key, str) or not key:
        raise _bad_key()
    try:
        raw = key.encode("utf-8")
    except UnicodeEncodeError:
        raise _bad_key()
    if len(raw) > 1024 or key.startswith("/") or key.endswith("/") or "\x00" in key:
        raise _bad_key()
    if any(p in ("", ".", "..") for p in key.split("/")):
        raise _bad_key()
    if safe_key(key) is None:
        raise _bad_key("This key can't be stored as a file name on the phone (control characters, or characters "
                       "this system doesn't allow in names).")
    return key


def _decode_key(raw):
    try:
        return unquote_to_bytes(raw).decode("utf-8")
    except UnicodeDecodeError:
        raise _bad_key()


def _etags(header):
    return [t.strip()[2:] if t.strip().startswith("W/") else t.strip() for t in header.split(",") if t.strip()]


def _matches(header, etag):
    """Does an if-match / if-none-match header hold for a file with this ETag (None: no file)?"""
    if etag is None:
        return False
    tags = _etags(header)
    return "*" in tags or etag in tags


def _secs(st):
    return st.st_mtime_ns / 1e9


def _parse_range(header, size):
    """(start, end) of a single 'bytes=' range, None to send the whole file, or Refuse 416."""
    if not header or not header.strip().lower().startswith("bytes="):
        return None
    spec = header.strip()[6:].strip()
    if "," in spec:
        return None                          # one range only: answer with the whole file
    a, sep, b = spec.partition("-")
    if not sep:
        return None
    try:
        if a.strip() == "":
            n = int(b)
            if n <= 0 or size == 0:
                raise ValueError("empty")
            start, end = max(0, size - n), size - 1
        else:
            start = int(a)
            end = min(int(b), size - 1) if b.strip() else size - 1
    except ValueError as e:
        if str(e) == "empty":
            raise Refuse(416, "InvalidRange", "The range is outside the file.", {"Content-Range": f"bytes */{size}"})
        return None
    if start < 0 or start >= size or start > end:
        raise Refuse(416, "InvalidRange", "The range is outside the file.", {"Content-Range": f"bytes */{size}"})
    return start, end


def _json_obj(body):
    try:
        out = json.loads(body or b"{}")
    except ValueError:
        raise Refuse(400, "BadRequest", "The request body must be JSON.")
    if not isinstance(out, dict):
        raise Refuse(400, "BadRequest", "The request body must be a JSON object.")
    return out


def _int_param(q, name, lo, hi, default):
    v = q.get(name)
    if v is None or v == "":
        return default
    try:
        n = int(v)
    except ValueError:
        n = None
    if n is None or not lo <= n <= hi:
        raise Refuse(400, "BadRequest", f"{name} is a whole number from {lo} to {hi}.")
    return n


def _b64(obj):
    return base64.urlsafe_b64encode(json.dumps(obj, separators=(",", ":")).encode()).decode().rstrip("=")


def _unb64(s):
    try:
        out = json.loads(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)))
    except (ValueError, TypeError):
        return None
    return out if isinstance(out, dict) else None


def _sort_key(k):
    return k.encode("utf-8", "surrogateescape")


class FileRoutes:
    """Mixed into the gateway's request handler."""

    # (method, path) -> (handler, op name for the request log, held by the gate while rclone restarts)
    V2_ROUTES = {
        ("GET", "/_pocket/app"): ("_r_app", "AppInfo", False),
        ("GET", "/_pocket/list"): ("_r_list", "FileList", True),
        ("POST", "/_pocket/move"): ("_r_move", "FileMove", True),
        ("GET", "/_pocket/changes"): ("_r_changes", "Changes", False),
        ("POST", "/_pocket/notify"): ("_r_notify", "Notify", False),
    }
    FILE_METHODS = {"GET": ("_r_get", "FileGet"), "HEAD": ("_r_get", "FileHead"), "PUT": ("_r_put", "FilePut"),
                    "DELETE": ("_r_delete", "FileDelete")}

    def _v2(self, raw_path, raw_query, ctx):
        """Answer a v2 route. False when raw_path is none of them."""
        if raw_path.startswith(PUB):
            name, op, gated = "_r_pub", "PubRead", True
        elif raw_path.startswith(FILES):
            name, op = self.FILE_METHODS.get(self.command, ("_r_405", "FileOther"))
            gated = True
        else:
            hit = self.V2_ROUTES.get((self.command, raw_path))
            if not hit:
                return False
            name, op, gated = hit
        ctx["op"] = op
        self._drain_put = False
        try:
            if gated:          # a card swap or planned restart waits for running file operations
                with self.server.core.gate():
                    getattr(self, name)(raw_path, raw_query, ctx)
            else:
                getattr(self, name)(raw_path, raw_query, ctx)
        except Refuse as r:
            self._jerr(r, ctx)
        return True

    def _jerr(self, r, ctx):
        ctx["status"], ctx["note"] = r.status, ctx["note"] or r.code
        if self._drain_put and not self.expect_pending and isinstance(self.body_left, int) and 0 < self.body_left <= MAX_PUT:
            # a signed upload refused before its body was read: read the body and throw it away, so a client that
            # sends it without waiting for 100 Continue gets this answer instead of a reset connection
            left = self.body_left
            while left:
                b = self.rfile.read(min(CHUNK, left))
                if not b:
                    break
                left -= len(b)
            self.body_left = left
        extra = list(r.headers.items()) + self._settle_body()
        reason = http.client.responses.get(r.status, "")
        if self.command == "HEAD":
            self._send_head(r.status, reason, [("x-pocket-error-code", r.code), ("Content-Length", "0")] + extra)
        else:
            body = json.dumps({"error": r.code, "message": r.message, **r.body}).encode("utf-8")
            self._send_head(r.status, reason, [("Content-Type", "application/json"),
                                               ("Content-Length", str(len(body)))] + extra)
            self.wfile.write(body)
        self.wfile.flush()

    def _ok(self, obj, ctx):
        ctx["status"] = 200
        self._json(200, obj)

    # ---------- who is asking ----------
    def _v2_auth(self, raw_path, raw_query, body, ctx, body_hash=None):
        """The device behind a signed request, checked like an S3 request. Returns (device, app)."""
        core = self.server.core
        headers = {k.lower(): v for k, v in self.headers.items()}
        ak = headers.get("x-pocket-key", "")
        dev = core.registry.device_by_key(ak) if ak else None
        if not dev:
            raise Refuse(403, "InvalidAccessKeyId", "This key is not known to Pocket Cloud.")
        if not headers.get("x-pocket-sign"):
            raise Refuse(403, "MissingSignature", "The request is not signed.")
        ok, why = sigv4.verify_pocket(self.command, raw_path, raw_query, headers, body, dev["secret"], body_hash=body_hash)
        if not ok:
            raise Refuse(403, why, "The request is not signed correctly for this key." if why != "RequestTimeTooSkewed"
                         else "x-pocket-date is more than 15 minutes off the server's clock.")
        given = headers.get("x-pocket-content-sha256", "").strip().lower()
        if body_hash is None and given and given != hashlib.sha256(body or b"").hexdigest():
            raise Refuse(400, "BadDigest", "The body did not match x-pocket-content-sha256.")
        app = self._check_device(dev, ctx)
        core.registry.touch(ak, time.time())
        return dev, app

    def _body(self):
        return self._read_small_body() if self.body_left else b""

    def _where(self, app, dev, bucket):
        """(owning app, space) behind a bucket name as this device sees it."""
        if bucket == "shared":
            return app, "shared"
        if bucket == "device":
            return app, "device:" + dev["uid"]
        if bucket == "family" and app.get("family"):
            return self.server.core.registry.app(FAMILY), "shared"
        what = "shared, device and family" if app.get("family") else "shared and device"
        raise Refuse(404, "NoSuchBucket", f"Pocket Cloud apps have exactly these buckets: {what}.")

    def _writable(self):
        if self.server.core.settings.get("server.read_only"):
            raise Refuse(403, "ReadOnly", "Pocket Cloud is in read-only mode.")

    def _locate(self, owner_id, space, key):
        """(storage, path, stat) of a key's file, internal storage first (as rclone looks), or None."""
        layout = self.server.core.layout
        for storage in layout.storages():
            p = layout.key_path(storage, owner_id, space, key)
            if not p:
                continue
            try:
                st = os.stat(p)
            except OSError:
                continue
            if stat.S_ISREG(st.st_mode):
                return storage, p, st
        return None

    def _file_target(self, raw_path):
        bucket, sep, raw_key = raw_path[len(FILES):].partition("/")
        if not sep or not raw_key:
            raise _bad_key()
        return bucket, check_key(_decode_key(raw_key))

    # ---------- GET / HEAD /_pocket/files/<bucket>/<key> ----------
    def _r_get(self, raw_path, raw_query, ctx):
        bucket, key = self._file_target(raw_path)
        dev, app = self._v2_auth(raw_path, raw_query, self._body(), ctx)
        owner, space = self._where(app, dev, bucket)
        ctx.update(bucket=bucket, key=key)
        self._send_file(owner["id"], space, key, ctx)

    def _send_file(self, owner_id, space, key, ctx, cache=None, missing=("NoSuchKey", "No file at that key.")):
        core = self.server.core
        f = loc = None
        for _ in range(2):           # the nightly move may take the file to the SD card between look and open
            loc = self._locate(owner_id, space, key)
            if not loc:
                break
            try:
                f = open(loc[1], "rb")
                break
            except FileNotFoundError:
                continue
        if f is None:
            raise Refuse(404, *missing)
        with f:
            st = os.fstat(f.fileno())
            etag, ctype = core.meta.info(owner_id, space, key, loc[1], st)
            h = self.headers
            if h.get("if-match") and not _matches(h["if-match"], etag):
                raise Refuse(412, "PreconditionFailed", "The file's ETag is not the one given in if-match.")
            base = [("ETag", etag), ("Last-Modified", email.utils.formatdate(st.st_mtime, usegmt=True)),
                    ("x-pocket-modified", repr(_secs(st))), ("Accept-Ranges", "bytes")]
            if cache:
                base.append(("Cache-Control", cache))
            if h.get("if-none-match") and _matches(h["if-none-match"], etag):
                ctx["status"] = 304
                self._send_head(304, "Not Modified", base + self._settle_body())
                self.wfile.flush()
                return
            size = st.st_size
            rng = _parse_range(h.get("range"), size)
            status, start, end = (206, *rng) if rng else (200, 0, size - 1)
            length = end - start + 1 if size else 0
            headers = [("Content-Type", ctype or "application/octet-stream"), ("Content-Length", str(length))] + base
            if rng:
                headers.append(("Content-Range", f"bytes {start}-{end}/{size}"))
            ctx["status"] = status
            self._send_head(status, http.client.responses[status], headers + self._settle_body())
            sent = 0
            if self.command != "HEAD":
                f.seek(start)
                while sent < length:
                    b = f.read(min(CHUNK, length - sent))
                    if not b:
                        self.close_connection = True       # the file shrank under us: the length is wrong now
                        break
                    self.wfile.write(b)
                    sent += len(b)
            self.wfile.flush()
            ctx["bytes_out"] = sent

    # ---------- PUT /_pocket/files/<bucket>/<key> ----------
    def _r_put(self, raw_path, raw_query, ctx):
        core = self.server.core
        layout = core.layout
        bucket, key = self._file_target(raw_path)
        h = self.headers
        sha = (h.get("x-pocket-content-sha256") or "").strip().lower()
        if not SHA_HEX.match(sha):
            raise Refuse(400, "BadRequest", "Uploads need x-pocket-content-sha256: the body's SHA-256 in hex.")
        dev, app = self._v2_auth(raw_path, raw_query, b"", ctx, body_hash=sha)
        self._drain_put = True             # signed by a known device: a refusal from here on reads the body first
        owner, space = self._where(app, dev, bucket)
        oid, uid = owner["id"], dev["uid"]
        ctx.update(bucket=bucket, key=key)
        self._writable()
        if self.body_left == "chunked" or h.get("Content-Length") is None:
            raise Refuse(400, "BadRequest", "Uploads need Content-Length (no chunked uploads on this route).")
        n = self.body_left
        if n > MAX_PUT:
            raise Refuse(400, "EntityTooLarge", "Files over 2 GiB go through S3 multipart uploads.")
        mod_ns = None
        if h.get("x-pocket-modified"):
            try:
                mod = float(h["x-pocket-modified"])
                if not 0 <= mod < 1e11:
                    raise ValueError()
                mod_ns = int(round(mod * 1e9))
            except ValueError:
                raise Refuse(400, "BadRequest", "x-pocket-modified is a time in unix seconds.")
        ctype = h.get("Content-Type")
        core.check_space(owner, space, n, ctx)
        self._put_conditions(oid, space, key)        # answer 412 before the body is sent, when we can
        self._continue()
        target = "sd" if core.internal_full and layout.has_sd else "int"
        staging = os.path.join(layout.root(target), "_staging")
        os.makedirs(staging, exist_ok=True)
        tmp = os.path.join(staging, uuid.uuid4().hex)
        md5, sh, got = hashlib.md5(), hashlib.sha256(), 0
        try:
            with open(tmp, "wb") as f:
                while got < n:
                    b = self.rfile.read(min(CHUNK, n - got))
                    if not b:
                        self.body_left = n - got
                        raise ConnectionError("client closed during upload")
                    md5.update(b)
                    sh.update(b)
                    f.write(b)
                    got += len(b)
                self.body_left = 0
                f.flush()
                os.fsync(f.fileno())
            ctx["bytes_in"] = got
            if sh.hexdigest() != sha:
                raise Refuse(400, "BadDigest", "The upload did not match its x-pocket-content-sha256; nothing was stored.")
            if mod_ns is not None:
                os.utime(tmp, ns=(mod_ns, mod_ns))
            etag = '"%s"' % md5.hexdigest()
            dst = layout.key_path(target, oid, space, key)
            with core.key_lock((oid, space, key)):
                old = self._put_conditions(oid, space, key)
                self._make_room_for(dst)
                kept = undo.keep(layout, oid, space, key, "overwritten", uid)
                for s, _, sz in kept:
                    core.usage.add(oid, space, s, -sz)
                    other = layout.key_path(s, oid, space, key)
                    if s != target and other and os.path.isfile(other):
                        os.remove(other)          # the new version replaces the copy on the other storage
                os.replace(tmp, dst)
                st = os.stat(dst)
                core.meta.set(oid, space, key, st.st_size, st.st_mtime_ns, etag, ctype)     # arrived now
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)
        core.usage.add(oid, space, target, got)
        core.forget(oid, space, [key])
        core.record_change(oid, space, key, "put", "app", uid, got, etag)
        core.notify_change(oid)
        self._ok({"bucket": bucket, "key": key, "size": got, "etag": etag, "modified": _secs(st), "created": old is None}, ctx)

    def _put_conditions(self, oid, space, key):
        """Check if-match / if-none-match against the current file. Returns its location (None: no file)."""
        loc = self._locate(oid, space, key)
        im, inm = self.headers.get("if-match"), self.headers.get("if-none-match")
        if im or inm:
            etag = self.server.core.meta.etag(oid, space, key, loc[1], loc[2]) if loc else None
            if im and not _matches(im, etag):
                raise Refuse(412, "PreconditionFailed", "The file is not the one given in if-match." if loc
                             else "There is no file at that key (if-match).")
            if inm and _matches(inm, etag):
                raise Refuse(412, "PreconditionFailed", "There is already a file at that key (if-none-match).")
        return loc

    @staticmethod
    def _make_room_for(path):
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
        except OSError:
            raise _bad_key("A file is in the way of this key's folder.")
        if os.path.isdir(path):
            raise _bad_key("There is a folder at this key.")

    # ---------- DELETE /_pocket/files/<bucket>/<key> ----------
    def _r_delete(self, raw_path, raw_query, ctx):
        core = self.server.core
        layout = core.layout
        bucket, key = self._file_target(raw_path)
        dev, app = self._v2_auth(raw_path, raw_query, self._body(), ctx)
        owner, space = self._where(app, dev, bucket)
        oid, uid = owner["id"], dev["uid"]
        ctx.update(bucket=bucket, key=key)
        self._writable()
        with core.key_lock((oid, space, key)):
            loc = self._locate(oid, space, key)
            if not loc:
                raise Refuse(404, "NoSuchKey", "No file at that key.")
            im = self.headers.get("if-match")
            if im and not _matches(im, core.meta.etag(oid, space, key, loc[1], loc[2])):
                raise Refuse(412, "PreconditionFailed", "The file is not the one given in if-match.")
            kept = undo.keep(layout, oid, space, key, "deleted", uid)
            if not kept:
                raise Refuse(404, "NoSuchKey", "No file at that key.")
            for s, _, sz in kept:
                p = layout.key_path(s, oid, space, key)
                try:
                    os.remove(p)
                except FileNotFoundError:
                    pass
                remove_empty_dirs(os.path.dirname(p), layout.space_path(s, oid, space))
                core.usage.add(oid, space, s, -sz)
            core.meta.delete(oid, space, key)
        core.forget(oid, space, [key])
        core.record_change(oid, space, key, "delete", "app", uid)
        core.notify_change(oid)
        self._ok({"bucket": bucket, "key": key, "deleted": True}, ctx)

    def _r_405(self, raw_path, raw_query, ctx):
        raise Refuse(405, "MethodNotAllowed", "Files are read with GET or HEAD, written with PUT and removed with DELETE.",
                     {"Allow": "GET, HEAD, PUT, DELETE"})

    # ---------- GET /_pocket/list ----------
    def _r_list(self, raw_path, raw_query, ctx):
        core = self.server.core
        dev, app = self._v2_auth(raw_path, raw_query, self._body(), ctx)
        q = dict(sigv4.parse_query(raw_query))
        bucket = q.get("bucket") or ""
        if not bucket:
            raise Refuse(400, "BadRequest", "bucket is required.")
        owner, space = self._where(app, dev, bucket)
        oid = owner["id"]
        prefix, delim = q.get("prefix", ""), q.get("delimiter", "")
        if delim not in ("", "/"):
            raise Refuse(400, "BadRequest", "The only delimiter is '/'.")
        limit = _int_param(q, "limit", 1, 1000, 1000)
        after = None
        if q.get("cursor"):
            c = _unb64(q["cursor"])
            if not c or (c.get("b"), c.get("p"), c.get("d")) != (bucket, prefix, delim) or not isinstance(c.get("a"), str):
                raise Refuse(400, "BadRequest", "That cursor is not from this listing.")
            if not isinstance(c.get("t"), (int, float)) or time.time() - c["t"] > LIST_CURSOR_S:
                raise Refuse(400, "BadRequest", "That cursor has expired; list again from the start.")
            after = _sort_key(c["a"])
        ctx.update(bucket=bucket, key=prefix)
        items = self._listing(oid, space, prefix, delim)
        keys = sorted(items, key=_sort_key)
        if after is not None:
            keys = [k for k in keys if _sort_key(k) > after]
        # Files without a known ETag are hashed here (the background hasher fills most in). A page stops early
        # once that took too long, so a first listing of big files never times out: limit is a maximum.
        files, folders, page = [], [], []
        hashed, deadline = 0, time.monotonic() + LIST_HASH_S
        try:
            for k in keys[:limit]:
                if items[k] is None:
                    folders.append(k)
                else:
                    path, st = items[k]
                    etag = core.meta.known(oid, space, k, st)
                    if etag is None:
                        if page and (hashed >= LIST_HASH_BYTES or time.monotonic() >= deadline):
                            break
                        try:
                            etag = core.meta.etag(oid, space, k, path, st, commit=False)
                        except FileNotFoundError:
                            page.append(k)    # deleted or moved to the SD card while listing: left out
                            continue
                        hashed += st.st_size
                    files.append({"key": k, "size": st.st_size, "etag": etag, "modified": _secs(st)})
                page.append(k)
        finally:
            if hashed:
                core.meta.commit()
        more = len(page) < len(keys) and bool(page)
        out = {"bucket": bucket, "prefix": prefix, "files": files}
        if delim:
            out["folders"] = folders
        out["cursor"] = _b64({"b": bucket, "p": prefix, "d": delim, "a": page[-1], "t": int(time.time())}) if more else None
        self._ok(out, ctx)

    def _listing(self, oid, space, prefix, delim):
        """key -> (path, stat) of the files under prefix (internal storage first), and folder -> None."""
        layout = self.server.core.layout
        cut = prefix.rfind("/")
        if cut == 0:
            return {}
        parts = prefix[:cut].split("/") if cut > 0 else []
        if parts and safe_key("/".join(parts)) is None:
            return {}
        out = {}
        if delim:                          # one level: files in the prefix's folder, and its non-empty subfolders
            above = prefix[:cut + 1] if cut > 0 else ""
            for storage in layout.storages():
                try:
                    entries = list(os.scandir(os.path.join(layout.space_path(storage, oid, space), *parts)))
                except (FileNotFoundError, NotADirectoryError):
                    continue
                for ent in entries:
                    key = above + ent.name
                    if not key.startswith(prefix) or ent.name.startswith(TEMP_PREFIX):
                        continue
                    try:
                        if ent.is_dir(follow_symlinks=False):
                            if key + "/" not in out and next(walk_files(ent.path), None) is not None:
                                out[key + "/"] = None
                        elif ent.is_file(follow_symlinks=False):
                            out.setdefault(key, (ent.path, ent.stat(follow_symlinks=False)))
                    except FileNotFoundError:
                        continue
            return out
        for storage in layout.storages():
            base = layout.space_path(storage, oid, space)
            for path, st in walk_files(os.path.join(base, *parts)):
                key = "/".join(os.path.relpath(path, base).split(os.sep))
                if key.startswith(prefix):
                    out.setdefault(key, (path, st))
        return out

    # ---------- POST /_pocket/move ----------
    def _r_move(self, raw_path, raw_query, ctx):
        core = self.server.core
        layout = core.layout
        body = self._body()
        dev, app = self._v2_auth(raw_path, raw_query, body, ctx)
        req = _json_obj(body)
        src, dst = req.get("from"), req.get("to")
        for side in (src, dst):
            if not isinstance(side, dict) or not isinstance(side.get("bucket"), str) or not isinstance(side.get("key"), str):
                raise Refuse(400, "BadRequest", "from and to are {\"bucket\": ..., \"key\": ...}.")
            check_key(side["key"])
        if_match, overwrite = req.get("if_match"), req.get("overwrite", False)
        if if_match is not None and not isinstance(if_match, str):
            raise Refuse(400, "BadRequest", "if_match is an ETag string.")
        if not isinstance(overwrite, bool):
            raise Refuse(400, "BadRequest", "overwrite is true or false.")
        so, ss = self._where(app, dev, src["bucket"])
        do, ds = self._where(app, dev, dst["bucket"])
        sid, did, sk, dk, uid = so["id"], do["id"], src["key"], dst["key"], dev["uid"]
        if (sid, ss, sk) == (did, ds, dk):
            raise Refuse(400, "BadRequest", "from and to are the same file.")
        ctx.update(bucket=src["bucket"], key=sk)
        self._writable()
        with core.key_lock((sid, ss, sk), (did, ds, dk)):
            loc = self._locate(sid, ss, sk)
            if not loc:
                raise Refuse(404, "NoSuchKey", "No file at the from key.")
            storage, spath, st = loc
            etag, _ = core.meta.info(sid, ss, sk, spath, st)
            if if_match is not None and not _matches(if_match, etag):
                raise Refuse(412, "PreconditionFailed", "The file is not the one given in if_match.")
            existing = self._locate(did, ds, dk)
            if existing and not overwrite:
                raise Refuse(409, "KeyExists", "There is already a file at the to key.")
            # a rename adds no bytes to the pool or the disk; only the target's own limits can be crossed
            if sid != did:
                core.check_space(do, ds, st.st_size, ctx, new_bytes=False)
            elif ss != ds:
                core.check_device_cap(do, ds, st.st_size)
            dpath = layout.key_path(storage, did, ds, dk)
            self._make_room_for(dpath)
            if existing:
                for s, _, sz in undo.keep(layout, did, ds, dk, "overwritten", uid):
                    if s != storage:
                        os.remove(layout.key_path(s, did, ds, dk))
                    core.usage.add(did, ds, s, -sz)
            os.replace(spath, dpath)
            for s in layout.storages():          # an older copy of the source on the other storage goes too
                p = layout.key_path(s, sid, ss, sk) if s != storage else None
                if p and os.path.isfile(p):
                    sz = os.path.getsize(p)
                    os.remove(p)
                    core.usage.add(sid, ss, s, -sz)
            remove_empty_dirs(os.path.dirname(spath), layout.space_path(storage, sid, ss))
            if (sid, ss) != (did, ds):
                core.usage.add(sid, ss, storage, -st.st_size)
                core.usage.add(did, ds, storage, st.st_size)
            core.meta.move((sid, ss, sk), (did, ds, dk))
        core.forget(sid, ss, [sk])
        core.forget(did, ds, [dk])
        core.record_change(did, ds, dk, "move", "app", uid, st.st_size, etag, from_=(sid, ss, sk))
        core.notify_change(sid)
        if did != sid:
            core.notify_change(did)
        self._ok({"from": {"bucket": src["bucket"], "key": sk}, "to": {"bucket": dst["bucket"], "key": dk},
                  "size": st.st_size, "etag": etag}, ctx)

    # ---------- GET /_pocket/changes ----------
    def _r_changes(self, raw_path, raw_query, ctx):
        core = self.server.core
        dev, app = self._v2_auth(raw_path, raw_query, self._body(), ctx)
        q = dict(sigv4.parse_query(raw_query))
        limit = _int_param(q, "limit", 1, 1000, 500)
        wait = _int_param(q, "wait", 0, 60, 0)
        family = bool(app.get("family"))
        since = q.get("since")
        if not since:
            return self._ok({"changes": [], "cursor": core.changes.now_cursor(), "more": False}, ctx)
        try:
            c = core.changes.check(since, (app["id"], FAMILY) if family else (app["id"],))
        except (BadCursor, CursorExpired):
            raise Refuse(410, "CursorExpired", "That cursor is older than the changes kept (or not from this server): "
                         "list everything again, then follow the feed from the cursor given here.",
                         body={"cursor": core.changes.now_cursor()})
        end = time.time() + wait
        while True:
            recs, cursor, more = core.changes.fetch(app["id"], dev["uid"], family, c, limit)
            left = end - time.time()
            if recs or left <= 0:
                break
            core.changes.wait(int(cursor), left)
            c = int(cursor)
        self._ok({"changes": recs, "cursor": cursor, "more": more}, ctx)

    # ---------- GET /_pocket/app, POST /_pocket/notify ----------
    def _r_app(self, raw_path, raw_query, ctx):
        dev, app = self._v2_auth(raw_path, raw_query, self._body(), ctx)
        self._ok(self.server.core.app_info(app, dev), ctx)

    def _r_notify(self, raw_path, raw_query, ctx):
        body = self._body()
        dev, app = self._v2_auth(raw_path, raw_query, body, ctx)
        self._ok(self.server.core.notify_app(app, dev, _json_obj(body)), ctx)

    # ---------- GET / HEAD /_pocket/pub/<app>/<path>: the key-less publish folder ----------
    def _r_pub(self, raw_path, raw_query, ctx):
        core = self.server.core
        if self.command not in ("GET", "HEAD"):
            raise Refuse(405, "MethodNotAllowed", "The publish folder is read-only.", {"Allow": "GET, HEAD"})
        nf = Refuse(404, "NotFound", "Not found.")      # the same answer whatever is missing: apps stay private
        if not self._pub_address_ok():
            raise nf
        app_id, _, sub = raw_path[len(PUB):].partition("/")
        app = core.registry.app(app_id) if APP_ID.match(app_id) else None
        if not app or not app.get("publish") or not sub:
            raise nf
        try:
            key = check_key("releases/" + _decode_key(sub))
        except Refuse:
            raise nf
        ctx.update(app=app_id, bucket="shared", key=key)
        name = key.rsplit("/", 1)[-1]
        fresh = name == "latest.json" or (name.startswith("latest") and name.endswith(".yml"))
        self._body()
        self._send_file(app_id, "shared", key, ctx, cache="no-cache" if fresh else "max-age=86400",
                        missing=("NotFound", "Not found."))

    def _pub_address_ok(self):
        """Key-less reads answer only on the gateway's Tailscale address (and on loopback when tests allow it)."""
        addr = self.server.server_address[0]
        if is_tailscale(addr):
            return True
        try:
            loop = ipaddress.ip_address(addr).is_loopback
        except ValueError:
            loop = False
        return loop and bool(self.server.core.settings.get("publish.allow_loopback"))
