"""The gateway: the front door every app request passes through.

For each request it works out the app and device from the access key, refuses keys used from another
device's address, enforces allocations and the storage pool, keeps the old version of anything deleted or
overwritten for the 14-day undo, handles temporary links, logs the request, and passes it on to rclone,
which checks the signature and does the storage work. It never changes stored data before rclone has
accepted the request, except the undo copy, which is thrown away again if rclone refuses.
"""

import http.client
import json
import os
import socket
import socketserver
import sys
import threading
import time
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

from . import sigv4, undo
from .layout import FAMILY
from .registry import RegistryError
from .s3ops import BUCKETS, GROWS, WRITES, classify, error_xml, parse_delete_request, parse_delete_result

HOP = {"connection", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade", "expect",
       "proxy-authenticate", "proxy-authorization"}
COPY_CHUNK = 1024 * 1024          # bodies are relayed in pieces this big (measured: 256 KB cost ~25% on fast links)
MAX_SMALL_BODY = 8 * 1024 * 1024     # DeleteObjects and enrollment bodies
DRAIN_LIMIT = 64 * 1024 * 1024       # refused uploads up to this size are read and discarded
CHANGES = {"PutObject", "CopyObject", "CompleteMultipartUpload", "DeleteObject"}   # what apps are told about (push)


def _json_body(body):
    try:
        out = json.loads(body or b"{}")
    except ValueError:
        raise Refuse(400, "BadRequest", "The request body must be JSON.")
    if not isinstance(out, dict):
        raise Refuse(400, "BadRequest", "The request body must be a JSON object.")
    return out


class Refuse(Exception):
    def __init__(self, status, code, message, headers=None):
        super().__init__(message)
        self.status, self.code, self.message, self.headers = status, code, message, headers or {}


class GatewayServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128
    allow_reuse_address = sys.platform != "win32"    # see PanelServer: quick port take-back on Linux, never on Windows

    def __init__(self, address, core):
        self.core = core
        super().__init__(address, Handler)

    def server_bind(self):
        # the stock server looks up the bind address's host name, which can hang for seconds on an address
        # without a reverse DNS entry (a LAN or second loopback address); the name is never used here
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[0], self.server_address[1]

    def handle_error(self, request, client_address):
        """A client dropping an idle connection is normal; anything else goes to the log, not the console."""
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, TimeoutError, socket.timeout)):
            return
        self.core.log(f"connection from {client_address[0]} failed:\n" + traceback.format_exc())


class Handler(BaseHTTPRequestHandler):
    rbufsize = COPY_CHUNK             # read uploads from the socket in big pieces too
    protocol_version = "HTTP/1.1"
    server_version = "PocketCloud"
    sys_version = ""

    def setup(self):
        self.timeout = self.server.core.settings.get("server.timeout_s")
        super().setup()

    def log_message(self, fmt, *args):
        pass

    def handle_expect_100(self):
        # Don't say "100 Continue" yet: a refused upload is answered before its body is ever sent.
        self._expect = True
        return True

    def do_GET(self):
        self._dispatch()

    do_HEAD = do_PUT = do_POST = do_DELETE = do_GET

    # ---------- request plumbing ----------
    def _dispatch(self):
        core = self.server.core
        self.t0 = time.monotonic()
        self.rid = uuid.uuid4().hex[:16].upper()
        self.sent = False
        self.expect_pending = getattr(self, "_expect", False)
        self._expect = False
        self.body_left = self._body_length()
        raw_path, _, raw_query = self.path.partition("?")
        ctx = {"op": "Unknown", "bucket": "", "key": "", "app": None, "device": None,
               "bytes_in": 0, "bytes_out": 0, "status": 0, "note": None}
        try:
            if raw_path.startswith("/_pocket/"):
                self._internal(raw_path, raw_query, ctx)
            else:
                with core.gate():        # held for a moment while rclone is being restarted on purpose
                    self._s3(raw_path, raw_query, ctx)
        except Refuse as r:
            ctx["status"] = r.status
            ctx["note"] = ctx["note"] or r.code
            self._error(r.status, r.code, r.message, r.headers)
        except (ConnectionError, socket.timeout, TimeoutError) as e:
            ctx["status"] = ctx["status"] or 499
            ctx["note"] = "connection lost: %s" % type(e).__name__
            self.close_connection = True
        except Exception:
            core.log("gateway error:\n" + traceback.format_exc())
            ctx["status"] = 500
            ctx["note"] = "gateway error"
            if not self.sent:
                try:
                    self._error(500, "InternalError", "The gateway hit an unexpected error.")
                except OSError:
                    pass
            self.close_connection = True
        finally:
            if ctx["op"] != "Health":
                core.stats.request(
                    ts=time.time(), app=ctx["app"], device=ctx["device"], ip=self.client_ip(), op=ctx["op"],
                    bucket=ctx["bucket"], key=ctx["key"] if core.settings.get("logs.record_names") else None,
                    bytes_in=ctx["bytes_in"], bytes_out=ctx["bytes_out"], status=ctx["status"],
                    ms=round((time.monotonic() - self.t0) * 1000, 1), note=ctx["note"])

    def client_ip(self):
        ip = self.client_address[0]
        return ip[7:] if ip.startswith("::ffff:") else ip

    def _body_length(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            return "chunked"
        try:
            return int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return 0

    def _continue(self):
        """The request passed every check: let a client that is waiting send its body."""
        if self.expect_pending:
            self.wfile.write(b"HTTP/1.1 100 Continue\r\n\r\n")
            self.wfile.flush()
            self.expect_pending = False

    def _settle_body(self):
        """Before answering early: read and discard a small unread body so the client gets the answer and the
        connection stays usable. Returns extra headers (Connection: close when that isn't possible)."""
        if not self.body_left:
            return []
        if self.expect_pending or self.body_left == "chunked" or self.body_left > DRAIN_LIMIT:
            self.close_connection = True
            return [("Connection", "close")]
        left = self.body_left
        while left:
            b = self.rfile.read(min(COPY_CHUNK, left))
            if not b:
                break
            left -= len(b)
        self.body_left = 0
        return []

    def _read_small_body(self):
        self._continue()
        n = self.body_left
        if n == "chunked":
            data = b"".join(self._iter_chunked())
        else:
            if n > MAX_SMALL_BODY:
                raise Refuse(400, "EntityTooLarge", "Request body too large.")
            data = self.rfile.read(n) if n else b""
        self.body_left = 0
        return data

    def _iter_chunked(self):
        while True:
            line = self.rfile.readline(1024)
            if not line:
                raise ConnectionError("client closed during chunked body")
            size = int(line.split(b";")[0].strip() or b"0", 16)
            if size == 0:
                while self.rfile.readline(1024) not in (b"\r\n", b"\n", b""):
                    pass
                return
            left = size
            while left:
                b = self.rfile.read(min(left, COPY_CHUNK))
                if not b:
                    raise ConnectionError("client closed during chunked body")
                left -= len(b)
                yield b
            self.rfile.readline(16)

    def _send_head(self, status, reason, headers):
        lines = [f"HTTP/1.1 {status} {reason}\r\n"] + [f"{k}: {v}\r\n" for k, v in headers]
        self.wfile.write(("".join(lines) + "\r\n").encode("latin-1"))
        self.sent = True

    def _error(self, status, code, message, extra=None):
        body = error_xml(code, message, self.path.partition("?")[0], self.rid)
        headers = [("Content-Type", "application/xml"), ("x-amz-request-id", self.rid)] + list((extra or {}).items())
        headers += self._settle_body()
        if self.command == "HEAD":
            headers.append(("Content-Length", "0"))
            self._send_head(status, http.client.responses.get(status, ""), headers)
        else:
            headers.append(("Content-Length", str(len(body))))
            self._send_head(status, http.client.responses.get(status, ""), headers)
            self.wfile.write(body)
        self.wfile.flush()

    def _json(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        extra = self._settle_body()
        self._send_head(status, http.client.responses.get(status, ""), [
            ("Content-Type", "application/json"), ("Content-Length", str(len(body)))] + extra)
        self.wfile.write(body)
        self.wfile.flush()

    # ---------- /_pocket/ endpoints ----------
    # Per-device endpoints: signed with the device key (see sigv4.sign_pocket), JSON in and out.
    DEVICE_ROUTES = {
        ("GET", "/_pocket/quota"): ("Quota", lambda self, core, app, dev, body, q: core.quota(app, dev)),
        ("GET", "/_pocket/conflicts"): ("Conflicts", lambda self, core, app, dev, body, q: core.conflicts(app, dev)),
        ("POST", "/_pocket/mirror-report"): ("MirrorReport", lambda self, core, app, dev, body, q: core.mirror_report(app, dev, _json_body(body))),
        ("POST", "/_pocket/share"): ("ShareCreate", lambda self, core, app, dev, body, q: core.share_create(app, dev, _json_body(body))),
        ("GET", "/_pocket/shares"): ("ShareList", lambda self, core, app, dev, body, q: core.share_list(app, dev)),
        ("DELETE", "/_pocket/share"): ("ShareRemove", lambda self, core, app, dev, body, q: core.share_remove(app, dev, q)),
    }

    def _internal(self, raw_path, raw_query, ctx):
        core = self.server.core
        if raw_path == "/_pocket/health" and self.command == "GET":
            ctx["op"] = "Health"
            ctx["status"] = 200
            return self._json(200, {"ok": True, "version": core.version})
        route = self.DEVICE_ROUTES.get((self.command, raw_path))
        if route:
            ctx["op"] = route[0]
            try:
                body = self._read_small_body() if self.body_left else b""
                dev, app = self._device_auth(raw_path, raw_query, body, ctx)
                out = route[1](self, core, app, dev, body, sigv4.parse_query(raw_query))
            except Refuse as r:
                ctx["status"], ctx["note"] = r.status, ctx["note"] or r.code
                return self._json(r.status, {"error": r.code, "message": r.message})
            ctx["status"] = 200
            return self._json(200, out)
        if raw_path == "/_pocket/enroll" and self.command == "POST":
            ctx["op"] = "Enroll"
            try:
                req = json.loads(self._read_small_body() or b"{}")
            except ValueError:
                raise Refuse(400, "BadRequest", "Enrollment needs a JSON body.")
            ctx["app"] = req.get("app")
            ctx["device"] = req.get("device_id")
            try:
                out = core.enroll(req, self.client_ip(), self.headers.get("Host") or "")
            except RegistryError as e:
                ctx["status"] = 400 if e.code.startswith("Bad") else 403
                return self._json(ctx["status"], {"error": e.code, "message": str(e)})
            ctx["status"] = 200
            return self._json(200, out)
        ctx["status"] = 404
        return self._json(404, {"error": "NotFound", "message": "Unknown Pocket Cloud endpoint."})

    def _device_auth(self, raw_path, raw_query, body, ctx):
        """The device behind a signed /_pocket/ request, checked like an S3 request. Returns (device, app)."""
        core = self.server.core
        headers = {k.lower(): v for k, v in self.headers.items()}
        ak = headers.get("x-pocket-key", "")
        dev = core.registry.device_by_key(ak) if ak else None
        if not dev:
            raise Refuse(403, "InvalidAccessKeyId", "This key is not known to Pocket Cloud.")
        ok, why = sigv4.verify_pocket(self.command, raw_path, raw_query, headers, body, dev["secret"])
        if not ok:
            raise Refuse(403, why, "The request is not signed correctly for this key.")
        app = self._check_device(dev, ctx)
        core.registry.touch(ak, time.time())
        return dev, app

    def _check_device(self, dev, ctx):
        """Refusals shared by S3 and /_pocket/ requests: blocked device, app waiting, key used from elsewhere."""
        core = self.server.core
        app = core.registry.app(dev["app_id"])
        ctx["app"], ctx["device"] = app["id"], dev["uid"]
        if dev["blocked"]:
            raise Refuse(403, "AccessDenied", "This device is blocked in Pocket Cloud.")
        if app["status"] != "active":
            raise Refuse(403, "AccessDenied", "This app is waiting for approval in the Pocket Cloud control panel.")
        ip = self.client_ip()
        if ip != dev["ip"] and ip != (dev.get("ip_alt") or None):
            raise Refuse(403, "AccessDenied",
                         "This key belongs to a device at another address. Enroll again from this device.",
                         {"x-pocket-error": "device-address-changed"})
        return app

    # ---------- S3 ----------
    def _s3(self, raw_path, raw_query, ctx):
        core = self.server.core
        query = sigv4.parse_query(raw_query)
        qd = dict(query)
        headers = {k.lower(): v for k, v in self.headers.items()}
        segs = raw_path.lstrip("/").split("/", 1)
        bucket = unquote(segs[0]) if segs and segs[0] else ""
        key = unquote(segs[1]) if len(segs) > 1 else ""
        op = classify(self.command, bucket, key, qd, headers)
        ctx.update(op=op, bucket=bucket, key=key)

        # who is asking
        presigned = False
        ak = sigv4.access_key_from_header(headers.get("authorization"))
        if not ak:
            ak = sigv4.presigned_key(query)
            presigned = bool(ak)
        if not ak:
            raise Refuse(403, "AccessDenied", "Requests must be signed with a Pocket Cloud key.")
        dev = core.registry.device_by_key(ak)
        if not dev:
            raise Refuse(403, "InvalidAccessKeyId", "This key is not known to Pocket Cloud.")
        app = self._check_device(dev, ctx)
        core.registry.touch(ak, time.time())

        # the two buckets (three for apps in the family)
        buckets = BUCKETS + (("family",) if app.get("family") else ())
        if bucket and bucket not in buckets:
            what = "shared, device and family" if app.get("family") else "shared and device"
            if op == "CreateBucket":
                raise Refuse(400, "InvalidBucketName", f"Pocket Cloud apps have exactly these buckets: {what}.")
            raise Refuse(404, "NoSuchBucket", f"Pocket Cloud apps have exactly these buckets: {what}.")
        if op == "DeleteBucket":
            raise Refuse(403, "AccessDenied", "Pocket Cloud's buckets can't be deleted.")
        if op in ("PutBucketOther", "PutObjectOther", "PostObject"):
            raise Refuse(501, "NotImplemented", "Pocket Cloud does not support this operation.")
        if op in WRITES and core.settings.get("server.read_only"):
            raise Refuse(403, "AccessDenied", "Pocket Cloud is in read-only mode.")
        # the family bucket is the family app's shared space: its allocation, usage and undo, not the caller's
        owner = core.registry.app(FAMILY) if bucket == "family" else app
        space = "shared" if bucket in ("shared", "family") else "device:" + dev["uid"]

        # room for new bytes
        size = 0
        if op in GROWS:
            size = self._incoming_size(op, headers, app, dev)
            core.check_space(owner, space, size, ctx)

        # temporary links: checked here, then passed on as a normal signed request
        fwd_query = raw_query
        fwd_headers = self._forward_headers()
        if presigned:
            ok, info = sigv4.verify_presigned_any(self.command, raw_path, query, headers, dev["secret"])
            if not ok:
                raise Refuse(403, info, "The temporary link is not valid." if info != "AccessDenied" else "Request has expired.")
            stripped = sigv4.strip_presign(query)
            fwd_query = "&".join(f"{sigv4._enc(k)}={sigv4._enc(v)}" for k, v in stripped)
            extra = {k: v for k, v in headers.items()
                     if k.startswith("x-amz-") and k not in ("x-amz-date", "x-amz-content-sha256")}
            signed = sigv4.sign_headers(self.command, raw_path, stripped, headers.get("host", ""), ak, dev["secret"],
                                        region=info, extra=extra)
            fwd_headers = [(k, v) for k, v in fwd_headers if k.lower() not in signed] + list(signed.items())

        # keep the old version before a delete or overwrite (undone if rclone refuses)
        kept, kept_by_key, body = [], {}, None
        if op == "DeleteObject":
            kept = undo.keep(core.layout, owner["id"], space, key, "deleted", dev["uid"])
        elif op in ("PutObject", "CopyObject", "CompleteMultipartUpload"):
            kept = undo.keep(core.layout, owner["id"], space, key, "overwritten", dev["uid"])
        elif op == "DeleteObjects":
            body = self._read_small_body()
            try:
                keys = parse_delete_request(body)
            except Exception:
                raise Refuse(400, "MalformedXML", "The delete request could not be read.")
            for k in keys:
                kept_by_key[k] = undo.keep(core.layout, owner["id"], space, k, "deleted", dev["uid"])

        path_qs = raw_path + ("?" + fwd_query if fwd_query else "")
        if op == "CreateBucket":
            # shared and device always exist. rclone checks the signature first, so anything but a refusal
            # (403) means the key is good: answer like S3 does for a bucket you already own.
            status, data, hdrs = self._forward(path_qs, fwd_headers, None, ctx, capture="hold")
            if status in (401, 403):
                self._send_head(status, http.client.responses.get(status, ""),
                                [h for h in hdrs if h[0].lower() != "content-length"] + [("Content-Length", str(len(data)))])
                self.wfile.write(data)
            else:
                ctx["status"] = 200
                self._send_head(200, "OK", [("Location", "/" + bucket), ("Content-Length", "0"),
                                            ("x-amz-request-id", self.rid)])
            self.wfile.flush()
            return
        status, resp_body = self._forward(path_qs, fwd_headers, body, ctx, capture=(op == "DeleteObjects"))
        ok = 200 <= status < 300

        # afterwards: keep or drop the undo copies, and adjust usage
        if op == "DeleteObjects":
            deleted, failed = parse_delete_result(resp_body or b"") if ok else ([], list(kept_by_key))
            gone = set(kept_by_key) - set(failed) if ok else set()
            for k, kk in kept_by_key.items():
                if k in gone:
                    for storage, _, sz in kk:
                        core.usage.add(owner["id"], space, storage, -sz)
                else:
                    undo.drop(kk)
            if gone:
                core.notify_change(owner["id"])
            return
        if not ok:
            undo.drop(kept)
            return
        for storage, _, sz in kept:
            core.usage.add(owner["id"], space, storage, -sz)
        if op in GROWS:
            core.usage.add(owner["id"], space, "sd" if core.internal_full else "int", size)
        if op in CHANGES:
            core.notify_change(owner["id"])

    def _incoming_size(self, op, headers, app, dev):
        if op in ("CopyObject", "UploadPartCopy"):
            rng = headers.get("x-amz-copy-source-range", "")
            if rng.startswith("bytes="):
                a, _, b = rng[6:].partition("-")
                try:
                    return int(b) - int(a) + 1
                except ValueError:
                    pass
            src = unquote(headers.get("x-amz-copy-source", "").split("?")[0]).lstrip("/")
            sb, _, sk = src.partition("/")
            if sb in BUCKETS or (sb == "family" and app.get("family")):
                core = self.server.core
                sp = "shared" if sb in ("shared", "family") else "device:" + dev["uid"]
                owner_id = FAMILY if sb == "family" else app["id"]
                for storage in ("int", "sd"):
                    p = core.layout.key_path(storage, owner_id, sp, sk)
                    try:
                        if p:
                            return os.path.getsize(p)
                    except OSError:
                        pass
            return 0
        for h in ("x-amz-decoded-content-length", "content-length"):
            try:
                return int(headers[h])
            except (KeyError, ValueError):
                continue
        return 0

    def _forward_headers(self):
        return [(k, v) for k, v in self.headers.items() if k.lower() not in HOP and k.lower() != "content-length"]

    # ---------- talking to rclone ----------
    def _upstream(self, fresh=False):
        local = self.server.core.local
        conn = getattr(local, "conn", None)
        if fresh and conn is not None:
            conn.close()
            conn = None
        if conn is None:
            core = self.server.core
            conn = http.client.HTTPConnection("127.0.0.1", core.settings.get("rclone_port"),
                                              timeout=core.settings.get("server.timeout_s"))
            local.conn = conn
        return conn

    def _connect(self):
        """Connect to rclone, waiting up to 5 s while it restarts."""
        end = time.time() + 5
        while True:
            conn = self._upstream()
            try:
                if conn.sock is None:
                    conn.connect()
                return conn
            except OSError:
                self._upstream(fresh=True)
                if time.time() > end:
                    raise Refuse(503, "ServiceUnavailable", "Pocket Cloud's storage server is restarting. Try again.",
                                 {"Retry-After": "2"})
                time.sleep(0.2)

    def _send_request(self, conn, path_qs, headers, body):
        conn.putrequest(self.command, path_qs, skip_host=True, skip_accept_encoding=True)
        for k, v in headers:
            conn.putheader(k, v)
        if body is not None:
            conn.putheader("Content-Length", str(len(body)))
            conn.endheaders()
            if body:
                conn.send(body)
            return len(body)
        n = self.body_left
        if n:
            self._continue()
        if n == "chunked":
            conn.putheader("Transfer-Encoding", "chunked")
            conn.endheaders()
            sent = 0
            for b in self._iter_chunked():
                conn.send(b"%x\r\n" % len(b) + b + b"\r\n")
                sent += len(b)
            conn.send(b"0\r\n\r\n")
            self.body_left = 0
            return sent
        conn.putheader("Content-Length", str(n))
        conn.endheaders()
        sent = 0
        while sent < n:
            b = self.rfile.read(min(COPY_CHUNK, n - sent))
            if not b:
                self.body_left = n - sent
                raise ConnectionError("client closed during upload")
            conn.send(b)
            sent += len(b)
        self.body_left = 0
        return sent

    def _forward(self, path_qs, headers, body, ctx, capture=False):
        """Send the request to rclone and stream the answer back. Reads (GET/HEAD) are retried once if
        rclone fails before answering, which covers the instant a file is being moved to the SD card."""
        idempotent = self.command in ("GET", "HEAD")
        attempts = 2 if idempotent else 1
        if not idempotent:
            self._upstream(fresh=True)   # never send a body down a kept-alive connection rclone may have closed
        for attempt in range(attempts):
            conn = self._connect()
            try:
                ctx["bytes_in"] = self._send_request(conn, path_qs, headers, body)
                resp = conn.getresponse()
            except (http.client.HTTPException, OSError) as e:
                self._upstream(fresh=True)
                if attempt + 1 < attempts:
                    time.sleep(0.1)
                    continue
                if isinstance(e, ConnectionError) and self.body_left:
                    raise
                raise Refuse(503, "ServiceUnavailable", "Pocket Cloud's storage server did not answer. Try again.",
                             {"Retry-After": "2"})
            if resp.status >= 500 and attempt + 1 < attempts:
                resp.read()
                self._upstream(fresh=True)
                time.sleep(0.1)
                continue
            return self._relay(resp, ctx, capture)

    def _relay(self, resp, ctx, capture):
        status = resp.status
        ctx["status"] = status
        out_headers = [(k, v) for k, v in resp.getheaders() if k.lower() not in HOP]
        has_len = any(k.lower() == "content-length" for k, _ in out_headers)
        head = self.command == "HEAD"
        if capture == "hold":            # caller decides what to send
            data = resp.read()
            self._after_upstream(resp)
            return status, data, out_headers
        if capture:
            data = resp.read()
            out_headers = [(k, v) for k, v in out_headers if k.lower() != "content-length"]
            out_headers.append(("Content-Length", str(len(data))))
            self._send_head(status, resp.reason, out_headers)
            self.wfile.write(data)
            ctx["bytes_out"] = len(data)
            self.wfile.flush()
            self._after_upstream(resp)
            return status, data
        chunked = not has_len and not head and status not in (204, 304)
        if chunked:
            out_headers.append(("Transfer-Encoding", "chunked"))
        self._send_head(status, resp.reason, out_headers)
        sent = 0
        if not head:
            while True:
                b = resp.read(COPY_CHUNK) if not chunked else resp.read1(COPY_CHUNK)
                if not b:
                    break
                self.wfile.write(b"%x\r\n" % len(b) + b + b"\r\n" if chunked else b)
                sent += len(b)
            if chunked:
                self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()
        ctx["bytes_out"] = sent
        self._after_upstream(resp)
        return status, None

    def _after_upstream(self, resp):
        if resp.will_close:
            self._upstream(fresh=True)


def serve(core, host, port, stop_event):
    """Serve on one address, retrying while the address isn't available yet (e.g. Tailscale still starting)."""
    while not stop_event.is_set():
        try:
            srv = GatewayServer((host, port), core)
        except OSError as e:
            core.log(f"gateway can't listen on {host}:{port} yet ({e}); retrying in 5 s")
            stop_event.wait(5)
            continue
        core.servers.append(srv)
        core.log(f"gateway listening on {host}:{port}")
        t = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True,
                             name=f"gateway-{host}")
        t.start()
        return srv
