"""The in-memory fake Pocket Cloud server of docs/app-api.md section 11, for the library's tests.

    srv = FakeServer().start()       # real HTTP on 127.0.0.1, random port
    srv.endpoint                     # "http://127.0.0.1:<port>"
    srv.control("reset")             # POST /_fake/reset
    srv.stop()
"""

import base64
import email.utils
import hashlib
import hmac
import json
import math
import re
import secrets
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENROLL_SECRET = "fake-enroll-secret"
ALL_FEATURES = ["quota", "conflicts", "mirror-report", "share", "push",
                "files", "move", "changes", "publish", "retention", "notify"]
SKEW = 15 * 60
MAX_PUT = 2147483648
GIB = 1 << 30
EMPTY = hashlib.sha256(b"").hexdigest()
TAG = re.compile(r"^[a-z0-9_-]{1,40}$")
ROUTE_FEATURE = {"/_pocket/app": "files", "/_pocket/files/": "files", "/_pocket/list": "files",
                 "/_pocket/move": "move", "/_pocket/changes": "changes", "/_pocket/notify": "notify",
                 "/_pocket/quota": "quota", "/_pocket/share": "share", "/_pocket/pub/": "publish"}


class Refuse(Exception):
    def __init__(self, status, code, message, headers=None, extra=None):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message
        self.headers, self.extra = headers or {}, extra or {}


def _key_ok(key):
    raw = key.encode("utf-8")
    if not 1 <= len(raw) <= 1024 or key.startswith("/") or key.endswith("/") or "\0" in key:
        return False
    return all(seg not in ("", ".", "..") for seg in key.split("/"))


def _decode_key(raw):
    try:
        key = "/".join(urllib.parse.unquote(seg, errors="strict") for seg in raw.split("/"))
    except UnicodeDecodeError:
        raise Refuse(400, "BadKey", "The key is not UTF-8.")
    if not _key_ok(key):
        raise Refuse(400, "BadKey", f"Not a valid key: {key!r}")
    return key


class State:
    def __init__(self):
        self.lock = threading.Condition()
        self.reset()

    def reset(self):
        self.features = list(ALL_FEATURES)
        self.clock = None
        self.apps = {}                  # app -> switches
        self.keys = {}                  # access key -> {secret, app, device, name}
        self.store = {}                 # (app, bucket, device or "", key) -> obj
        self.changes = []               # records with "_app", "_space"
        self.seq = 0
        self.floor = 0                  # cursors below this are expired
        self.sent = []
        self.notify_times = {}
        self.fails = []
        self.moved = 0
        self.requests = []

    def now(self):
        return self.clock if self.clock is not None else time.time()

    def app(self, name):
        return self.apps.setdefault(name, {"publish": False, "notify": False, "read_only": False,
                                           "allocation": GIB, "retention": []})


class FakeServer:
    def __init__(self):
        self.state = State()
        handler = type("Handler", (_Handler,), {"state": self.state, "fake": self})
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.endpoint = f"http://127.0.0.1:{self.port}"
        self.thread = None

    def start(self):
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        return self

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def control(self, name, body=None, query="", method=None):
        """Call a /_fake/ route; returns its JSON."""
        data = json.dumps(body or {}).encode() if method != "GET" else None
        req = urllib.request.Request(f"{self.endpoint}/_fake/{name}" + (f"?{query}" if query else ""), data=data,
                                     method=method or ("POST" if data is not None else "GET"),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read() or b"{}")


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    state = None
    fake = None

    def log_message(self, *a):
        pass

    def handle_expect_100(self):
        """Like the real gateway: hold "100 Continue" back until the upload has passed its checks."""
        return True

    def _continue(self):
        if self.hdr.get("expect", "").lower() == "100-continue" and not self._continued:
            self._continued = True
            self.send_response_only(100)
            self.end_headers()

    def do_GET(self):
        self._handle("GET")

    def do_HEAD(self):
        self._handle("HEAD")

    def do_PUT(self):
        self._handle("PUT")

    def do_POST(self):
        self._handle("POST")

    def do_DELETE(self):
        self._handle("DELETE")

    def do_PATCH(self):
        self._handle("PATCH")

    # ------------------------------------------------------------------------------------------ plumbing

    def _handle(self, method):
        self.method = method
        self.raw_path, _, self.raw_query = self.path.partition("?")
        self.q = dict(urllib.parse.parse_qsl(self.raw_query, keep_blank_values=True))
        self.hdr = {k.lower(): v for k, v in self.headers.items()}
        self._status, self._code = 0, None
        self._body = None
        self._continued = False
        try:
            if self.raw_path.startswith("/_fake/"):
                return self._fake_route()
            self._maybe_fail()
            self._route()
        except Refuse as r:
            self._drain()
            self._error(r)
        except Exception as e:                     # a fake bug: say so loudly
            self._drain()
            self._error(Refuse(500, "InternalError", repr(e)))
        finally:
            if not self.raw_path.startswith("/_fake/"):
                with self.state.lock:
                    self.state.requests.append({"method": method, "path": self.raw_path, "query": self.raw_query,
                                                "status": self._status, "code": self._code})

    def _drain(self):
        expecting = self.hdr.get("expect", "").lower() == "100-continue"
        if self._body is None and expecting and not self._continued:
            self.close_connection = True          # the client never sent the body: answer and close
            self._body = b""
        if self._body is None:
            n = int(self.hdr.get("content-length") or 0)
            if n and n < 64 << 20:
                self.rfile.read(n)
            elif n:
                self.close_connection = True
            self._body = b""

    def body(self):
        if self._body is None:
            self._continue()
            n = int(self.hdr.get("content-length") or 0)
            self._body = self.rfile.read(n) if n else b""
        return self._body

    def json_body(self):
        try:
            v = json.loads(self.body() or b"{}")
        except ValueError:
            raise Refuse(400, "BadRequest", "The body is not JSON.")
        if not isinstance(v, dict):
            raise Refuse(400, "BadRequest", "The body is a JSON object.")
        return v

    def send(self, status, data=b"", headers=None, ctype="application/json"):
        self._status = status
        self.send_response(status)
        hs = dict(headers or {})
        if ctype and "Content-Type" not in hs:
            hs["Content-Type"] = ctype
        if "Content-Length" not in hs:
            hs["Content-Length"] = str(len(data))
        for k, v in hs.items():
            self.send_header(k, v)
        self.end_headers()
        if self.method != "HEAD" and data:
            self.wfile.write(data)

    def send_json(self, value, status=200, headers=None):
        self.send(status, json.dumps(value).encode(), headers)

    def _error(self, r):
        self._code = r.code
        headers = dict(r.headers)
        if self.method == "HEAD":
            headers["x-pocket-error-code"] = r.code
            self._status = r.status
            self.send_response(r.status)
            for k, v in headers.items():
                self.send_header(k, v)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_json(dict({"error": r.code, "message": r.message}, **r.extra), r.status, headers)

    def _maybe_fail(self):
        st = self.state
        with st.lock:
            for f in st.fails:
                m, _, p = f["route"].partition(" ")
                if f["times"] > 0 and m == self.method and self.raw_path.startswith(p):
                    f["times"] -= 1
                    headers = {"Retry-After": "0"} if f["status"] in (503, 429) else {}
                    raise Refuse(f["status"], f["code"], "Failing on purpose (fake).", headers)

    # ------------------------------------------------------------------------------------------ signing

    def _signed(self):
        st = self.state
        key, date, sig = (self.hdr.get(h) for h in ("x-pocket-key", "x-pocket-date", "x-pocket-sign"))
        if not (key and date and sig):
            raise Refuse(403, "MissingSignature", "This request is not signed.")
        with st.lock:
            dev = st.keys.get(key)
            if st.moved > 0 and dev:
                st.moved -= 1
                raise Refuse(403, "AccessDenied", "This device moved to a new address.",
                             {"x-pocket-error": "device-address-changed"})
        if not dev:
            raise Refuse(403, "InvalidAccessKeyId", "This key is not known.")
        try:
            d = int(date)
        except ValueError:
            raise Refuse(403, "MissingSignature", "x-pocket-date is unix seconds.")
        if abs(st.now() - d) > SKEW:
            raise Refuse(403, "RequestTimeTooSkewed", "x-pocket-date is more than 15 minutes off.")
        declared = self.hdr.get("x-pocket-content-sha256")
        if self.method == "PUT" and self.raw_path.startswith("/_pocket/files/"):
            if not declared:
                raise Refuse(400, "BadRequest", "Uploads send x-pocket-content-sha256.")
            digest = declared
        else:
            digest = hashlib.sha256(self.body()).hexdigest()
            if declared and declared != digest:
                raise Refuse(400, "BadDigest", "The body did not match x-pocket-content-sha256.")
        text = "\n".join([self.method, self.raw_path, self.raw_query, str(d), digest])
        want = hmac.new(dev["secret"].encode(), text.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(want, sig):
            raise Refuse(403, "SignatureDoesNotMatch", "The signature does not match.")
        return dev

    def _need(self, feature):
        if feature not in self.state.features:
            raise Refuse(404, "NotFound", "No such route on this server.")

    # ------------------------------------------------------------------------------------------ routes

    def _route(self):
        p, m = self.raw_path, self.method
        if p == "/_pocket/health" and m == "GET":
            return self.send_json({"ok": True, "version": "0.3.0-fake", "features": self.state.features})
        if p == "/_pocket/enroll" and m == "POST":
            return self._enroll()
        if p.startswith("/_pocket/pub/"):
            return self._pub()
        for prefix, feature in ROUTE_FEATURE.items():
            if p == prefix or (prefix.endswith("/") and p.startswith(prefix)):
                self._need(feature)
                break
        else:
            if p == "/_pocket/shares":
                self._need("share")
            else:
                raise Refuse(404, "NotFound", "No such route.")
        dev = self._signed()
        if p == "/_pocket/app" and m == "GET":
            return self._app_info(dev)
        if p.startswith("/_pocket/files/") and m in ("GET", "HEAD", "PUT", "DELETE"):
            return self._file(dev, m)
        if p == "/_pocket/list" and m == "GET":
            return self._list(dev)
        if p == "/_pocket/move" and m == "POST":
            return self._move(dev)
        if p == "/_pocket/changes" and m == "GET":
            return self._changes(dev)
        if p == "/_pocket/notify" and m == "POST":
            return self._notify(dev)
        if p == "/_pocket/quota" and m == "GET":
            return self.send_json(self._quota(dev))
        if p == "/_pocket/share" and m == "POST":
            return self._share(dev)
        raise Refuse(404, "NotFound", "No such route.")

    def _enroll(self):
        b = self.json_body()
        if b.get("secret") != ENROLL_SECRET:
            raise Refuse(403, "Forbidden", "The enrollment secret is not right.")
        app, device = str(b.get("app") or ""), str(b.get("device_id") or "")
        if not app or not device:
            raise Refuse(400, "BadRequest", "app and device_id are required.")
        st = self.state
        with st.lock:
            for k in [k for k, v in st.keys.items() if v["app"] == app and v["device"] == device]:
                del st.keys[k]
            ak = "AKFAKE" + "".join(secrets.choice("0123456789") for _ in range(10))
            sk = secrets.token_hex(20)
            st.keys[ak] = {"secret": sk, "app": app, "device": device, "name": str(b.get("device_name") or device)}
            st.app(app)
        ep = self.fake.endpoint
        self.send_json({"app": app, "device_id": device, "access_key": ak, "secret_key": sk, "endpoint": ep,
                        "endpoints": {"tailscale": ep, "lan": None}, "region": "us-east-1",
                        "buckets": ["shared", "device"], "allocation_bytes": st.app(app)["allocation"],
                        "push": None})

    def _app_info(self, dev):
        a = self.state.app(dev["app"])
        pub_url = f"{self.fake.endpoint}/_pocket/pub/{dev['app']}/"
        self.send_json({
            "app": dev["app"], "device_id": dev["device"], "buckets": ["shared", "device"],
            "features": self.state.features,
            "publish": {"enabled": bool(a["publish"]), "url": pub_url},
            "notify": {"enabled": bool(a["notify"]), "topic": f"pocket-{dev['app']}-notify", "url": None},
            "push": None, "retention": a["retention"], "changes": {"keep_days": 30},
            "read_only": bool(a["read_only"])})

    # ---- storage helpers

    def _space(self, dev, bucket):
        if bucket == "shared":
            return (dev["app"], "shared", "")
        if bucket == "device":
            return (dev["app"], "device", dev["device"])
        raise Refuse(404, "NoSuchBucket", f"No bucket {bucket!r} for this app.")

    def _used(self, app):
        return sum(len(o["data"]) for (a, _, _, _), o in self.state.store.items() if a == app)

    def _record(self, dev, space, key, kind, obj=None, src=None, source="app", device=True):
        st = self.state
        st.seq += 1
        rec = {"seq": st.seq, "bucket": space[1], "key": key, "kind": kind,
               "size": len(obj["data"]) if obj else None, "etag": obj["etag"] if obj else None,
               "time": st.now(), "device": dev["device"] if device else None, "source": source,
               "_app": space[0], "_dev": space[2]}
        if src is not None:
            rec["from"] = {"bucket": src[1], "key": src[2]}
            rec["_from_dev"] = src[3]
        st.changes.append(rec)
        st.lock.notify_all()

    def _writable(self, dev):
        if self.state.app(dev["app"])["read_only"]:
            raise Refuse(403, "ReadOnly", "Pocket Cloud is in read-only mode.")

    @staticmethod
    def _headers(obj):
        return {"ETag": obj["etag"], "Last-Modified": email.utils.formatdate(obj["modified"], usegmt=True),
                "x-pocket-modified": repr(obj["modified"]), "Accept-Ranges": "bytes",
                "Content-Type": obj["ctype"] or "application/octet-stream"}

    def _serve_obj(self, obj, extra=None):
        h = dict(self._headers(obj), **(extra or {}))
        inm = self.hdr.get("if-none-match")
        if inm and inm == obj["etag"]:
            h["Content-Length"] = "0"
            return self.send(304, b"", h, ctype=None)
        im = self.hdr.get("if-match")
        if im and im != obj["etag"]:
            raise Refuse(412, "PreconditionFailed", "The file has another ETag.")
        data = obj["data"]
        rng = self.hdr.get("range")
        if rng:
            m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip())
            if not m or (not m.group(1) and not m.group(2)):
                raise Refuse(416, "InvalidRange", "One range, bytes=a-b.")
            if m.group(1):
                a = int(m.group(1))
                b = int(m.group(2)) if m.group(2) else len(data) - 1
            else:
                a, b = max(0, len(data) - int(m.group(2))), len(data) - 1
            if a >= len(data) or a > b:
                raise Refuse(416, "InvalidRange", "The range is outside the file.")
            b = min(b, len(data) - 1)
            h["Content-Range"] = f"bytes {a}-{b}/{len(data)}"
            part = data[a:b + 1]
            h["Content-Length"] = str(len(part))
            return self.send(206, part, h, ctype=None)
        h["Content-Length"] = str(len(data))
        return self.send(200, data, h, ctype=None)

    # ---- 4.1-4.5

    def _file(self, dev, m):
        rest = self.raw_path[len("/_pocket/files/"):]
        bucket, _, raw_key = rest.partition("/")
        space = self._space(dev, urllib.parse.unquote(bucket))
        key = _decode_key(raw_key)
        st = self.state
        sk = space + (key,)
        if m in ("GET", "HEAD"):
            with st.lock:
                obj = st.store.get(sk)
            if obj is None:
                raise Refuse(404, "NoSuchKey", "No file at that key.")
            return self._serve_obj(obj)
        if m == "PUT":
            self._writable(dev)
            n = self.hdr.get("content-length")
            if n is None:
                raise Refuse(400, "BadRequest", "Content-Length is required.")
            if int(n) > MAX_PUT:
                raise Refuse(400, "EntityTooLarge", "Over 2 GiB: use S3 multipart.")
            with st.lock:
                old = st.store.get(sk)
                self._conditions(old)
                a = st.app(dev["app"])
                if self._used(dev["app"]) - (len(old["data"]) if old else 0) + int(n) > a["allocation"]:
                    raise Refuse(403, "QuotaExceeded", f"{dev['app']} would go over its allocation.")
            data = self.body()
            if hashlib.sha256(data).hexdigest() != self.hdr["x-pocket-content-sha256"]:
                raise Refuse(400, "BadDigest", "The body did not match x-pocket-content-sha256.")
            try:
                modified = float(self.hdr["x-pocket-modified"]) if self.hdr.get("x-pocket-modified") else st.now()
            except ValueError:
                raise Refuse(400, "BadRequest", "x-pocket-modified is unix seconds.")
            with st.lock:
                old = st.store.get(sk)
                self._conditions(old)            # again, now that the body is in
                obj = {"data": data, "ctype": self.hdr.get("content-type"), "modified": modified,
                       "etag": '"' + hashlib.md5(data).hexdigest() + '"'}
                st.store[sk] = obj
                self._record(dev, space, key, "put", obj)
            return self.send_json({"bucket": space[1], "key": key, "size": len(data), "etag": obj["etag"],
                                   "modified": modified, "created": old is None})
        # DELETE
        self._writable(dev)
        with st.lock:
            old = st.store.get(sk)
            if old is None:
                raise Refuse(404, "NoSuchKey", "No file at that key.")
            im = self.hdr.get("if-match")
            if im and im != old["etag"]:
                raise Refuse(412, "PreconditionFailed", "The file has another ETag.")
            del st.store[sk]
            self._record(dev, space, key, "delete")
        return self.send_json({"bucket": space[1], "key": key, "deleted": True})

    def _conditions(self, old):
        im, inm = self.hdr.get("if-match"), self.hdr.get("if-none-match")
        if im and (old is None or old["etag"] != im):
            raise Refuse(412, "PreconditionFailed", "The file is not the one if-match names.")
        if inm == "*" and old is not None:
            raise Refuse(412, "PreconditionFailed", "There is a file there already.")

    def _list(self, dev):
        bucket = self.q.get("bucket")
        if not bucket:
            raise Refuse(400, "BadRequest", "bucket is required.")
        space = self._space(dev, bucket)
        prefix = self.q.get("prefix", "")
        delim = self.q.get("delimiter")
        if delim not in (None, "/"):
            raise Refuse(400, "BadRequest", "delimiter is '/' only.")
        try:
            limit = int(self.q.get("limit", 1000))
        except ValueError:
            raise Refuse(400, "BadRequest", "limit is a number.")
        if not 1 <= limit <= 1000:
            raise Refuse(400, "BadRequest", "limit is 1-1000.")
        after = None
        if self.q.get("cursor"):
            try:
                c = json.loads(base64.urlsafe_b64decode(self.q["cursor"] + "=="))
            except ValueError:
                raise Refuse(400, "BadRequest", "Not a cursor.")
            if [c.get("b"), c.get("p"), c.get("d")] != [bucket, prefix, delim]:
                raise Refuse(400, "BadRequest", "That cursor is for another listing.")
            after = c["a"]
        st = self.state
        with st.lock:
            objs = {k[3]: o for k, o in st.store.items() if k[:3] == space and k[3].startswith(prefix)}
        items = {}
        for key, o in objs.items():
            rest = key[len(prefix):]
            if delim and "/" in rest:
                folder = prefix + rest[:rest.index("/") + 1]
                items[folder] = None
            else:
                items[key] = o
        names = sorted(items, key=lambda s: s.encode("utf-8"))
        if after is not None:
            names = [n for n in names if n.encode("utf-8") > after.encode("utf-8")]
        page, more = names[:limit], len(names) > limit
        files = [{"key": n, "size": len(items[n]["data"]), "etag": items[n]["etag"], "modified": items[n]["modified"]}
                 for n in page if items[n] is not None]
        out = {"bucket": bucket, "prefix": prefix, "files": files, "cursor": None}
        if delim:
            out["folders"] = [n for n in page if items[n] is None]
        if more:
            raw = json.dumps({"b": bucket, "p": prefix, "d": delim, "a": page[-1]}).encode()
            out["cursor"] = base64.urlsafe_b64encode(raw).decode().rstrip("=")
        self.send_json(out)

    def _move(self, dev):
        b = self.json_body()
        try:
            fb, fk = str(b["from"]["bucket"]), str(b["from"]["key"])
            tb, tk = str(b["to"]["bucket"]), str(b["to"]["key"])
        except (KeyError, TypeError):
            raise Refuse(400, "BadRequest", "from and to are {bucket, key}.")
        if not _key_ok(fk) or not _key_ok(tk):
            raise Refuse(400, "BadKey", "Not a valid key.")
        self._writable(dev)
        fs, ts = self._space(dev, fb), self._space(dev, tb)
        st = self.state
        with st.lock:
            src = st.store.get(fs + (fk,))
            if src is None:
                raise Refuse(404, "NoSuchKey", "No file at the source key.")
            if b.get("if_match") and b["if_match"] != src["etag"]:
                raise Refuse(412, "PreconditionFailed", "The source has another ETag.")
            if (ts + (tk,)) in st.store and not b.get("overwrite"):
                raise Refuse(409, "KeyExists", "There is a file at the target.")
            if fs + (fk,) != ts + (tk,):
                del st.store[fs + (fk,)]
                st.store[ts + (tk,)] = src
                self._record(dev, ts, tk, "move", src, src=(fs[0], fs[1], fk, fs[2]))
        self.send_json({"from": {"bucket": fb, "key": fk}, "to": {"bucket": tb, "key": tk},
                        "size": len(src["data"]), "etag": src["etag"]})

    # ---- 5

    def _visible(self, rec, dev):
        if rec["_app"] != dev["app"]:
            return False
        return rec["bucket"] == "shared" or rec["_dev"] == dev["device"]

    def _changes(self, dev):
        st = self.state
        try:
            limit = int(self.q.get("limit", 500))
            wait = float(self.q.get("wait", 0))
        except ValueError:
            raise Refuse(400, "BadRequest", "limit and wait are numbers.")
        if not 1 <= limit <= 1000 or not 0 <= wait <= 60:
            raise Refuse(400, "BadRequest", "limit is 1-1000, wait 0-60.")
        since = self.q.get("since")
        with st.lock:
            if since is None:
                return self.send_json({"changes": [], "cursor": str(st.seq), "more": False})
            try:
                s = int(since)
            except ValueError:
                s = -1
            if s < st.floor or s > st.seq:
                raise Refuse(410, "CursorExpired", "That cursor is older than what is kept.",
                             extra={"cursor": str(st.seq)})
            deadline = time.time() + wait
            while True:
                out, last, more = [], s, False
                for rec in st.changes:
                    if rec["seq"] <= s or not self._visible(rec, dev):
                        continue
                    if len(out) >= limit:
                        more = True
                        break
                    out.append({k: v for k, v in rec.items() if not k.startswith("_")})
                    last = rec["seq"]
                if out or time.time() >= deadline:
                    break
                st.lock.wait(max(0.0, deadline - time.time()))
            cursor = str(last) if more else str(st.seq)
        self.send_json({"changes": out, "cursor": cursor, "more": more})

    # ---- 6

    def _pub(self):
        if self.method not in ("GET", "HEAD"):
            raise Refuse(405, "MethodNotAllowed", "The publish folder is read-only.")
        rest = self.raw_path[len("/_pocket/pub/"):]
        app, _, raw = rest.partition("/")
        st = self.state
        a = st.apps.get(urllib.parse.unquote(app))
        if "publish" not in st.features or not a or not a["publish"] or not raw:
            raise Refuse(404, "NotFound", "Not found.")
        try:
            path = _decode_key(raw)
        except Refuse:
            raise Refuse(404, "NotFound", "Not found.")
        with st.lock:
            obj = st.store.get((urllib.parse.unquote(app), "shared", "", "releases/" + path))
        if obj is None:
            raise Refuse(404, "NotFound", "Not found.")
        name = path.rsplit("/", 1)[-1]
        top = "/" not in path
        cc = "no-cache" if top and (name == "latest.json" or re.match(r"^latest.*\.yml$", name)) else "max-age=86400"
        return self._serve_obj(obj, {"Cache-Control": cc})

    # ---- 8

    def _notify(self, dev):
        st = self.state
        a = st.app(dev["app"])
        b = self.json_body()
        if not a["notify"]:
            raise Refuse(403, "FeatureOff", "Notifications are switched off for this app.")
        msg = b.get("message")
        if not isinstance(msg, str) or not 1 <= len(msg) <= 4096:
            raise Refuse(400, "BadRequest", "message is 1-4096 characters.")
        title = b.get("title")
        if title is not None and (not isinstance(title, str) or len(title) > 200):
            raise Refuse(400, "BadRequest", "title is up to 200 characters.")
        tags = b.get("tags") or []
        if not isinstance(tags, list) or len(tags) > 10 or not all(isinstance(t, str) and TAG.match(t) for t in tags):
            raise Refuse(400, "BadRequest", "tags: up to 10 of a-z 0-9 _ -, 40 characters each.")
        prio = b.get("priority", 3)
        if not isinstance(prio, int) or isinstance(prio, bool) or not 1 <= prio <= 5:
            raise Refuse(400, "BadRequest", "priority is 1-5.")
        click = b.get("click")
        if click is not None and (not isinstance(click, str) or len(click) > 2000):
            raise Refuse(400, "BadRequest", "click is a URL up to 2000 characters.")
        with st.lock:
            now = st.now()
            times = [t for t in st.notify_times.get(dev["app"], []) if now - t < 86400]
            minute = [t for t in times if now - t < 60]
            if len(minute) >= 10 or len(times) >= 200:
                oldest = minute[0] if len(minute) >= 10 else times[0]
                window = 60 if len(minute) >= 10 else 86400
                retry = max(1, math.ceil(window - (now - oldest)))
                raise Refuse(429, "RateLimited", "Too many notifications.", {"Retry-After": str(retry)})
            times.append(now)
            st.notify_times[dev["app"]] = times
            st.sent.append({"app": dev["app"], "device": dev["device"], "message": msg, "title": title,
                            "tags": tags, "priority": prio, "click": click, "time": now})
        self.send_json({"ok": True, "id": secrets.token_hex(6), "topic": f"pocket-{dev['app']}-notify"})

    # ---- existing routes

    def _quota(self, dev):
        st = self.state
        with st.lock:
            a = st.app(dev["app"])
            used = self._used(dev["app"])
            used_dev = sum(len(o["data"]) for (ap, b, d, _), o in st.store.items()
                           if ap == dev["app"] and b == "device" and d == dev["device"])
        free = max(0, a["allocation"] - used)
        return {"app": dev["app"], "device_id": dev["device"], "allocation": a["allocation"], "used_app": used,
                "used_device": used_dev, "device_cap": None, "reserve": 0, "held_for_others": 0, "free": free,
                "free_shared": free, "read_only": bool(a["read_only"])}

    def _share(self, dev):
        b = self.json_body()
        bucket, key = str(b.get("bucket", "")), str(b.get("key", ""))
        if bucket not in ("shared", "device"):
            raise Refuse(400, "BadRequest", "bucket is 'shared' or 'device'.")
        space = self._space(dev, bucket)
        if space + (key,) not in self.state.store:
            raise Refuse(404, "NoSuchKey", "There is no such file to share.")
        expires = int(b.get("expires_s") or 86400)
        if not 60 <= expires <= 30 * 86400:
            raise Refuse(400, "BadRequest", "expires_s is between 60 and 2592000 seconds.")
        token = secrets.token_urlsafe(12)
        now = self.state.now()
        self.send_json({"token": token, "url": f"{self.fake.endpoint}/s/{token}", "public": False, "bucket": bucket,
                        "key": key, "created": now, "expires": now + expires, "downloads": 0,
                        "max_downloads": b.get("max_downloads"), "has_password": bool(b.get("password"))})

    # ------------------------------------------------------------------------------------------ /_fake/

    def _fake_route(self):
        st = self.state
        name = self.raw_path[len("/_fake/"):]
        b = self.json_body() if self.method == "POST" else {}
        with st.lock:
            if name == "reset":
                st.reset()
            elif name == "clock":
                st.clock = b.get("now")
            elif name == "features":
                st.features = list(b.get("features") or [])
            elif name == "app":
                a = st.app(self.q.get("app", ""))
                for k in ("publish", "notify", "read_only", "allocation", "retention"):
                    if k in b:
                        a[k] = b[k]
            elif name == "fail":
                st.fails.append({"route": b["route"], "status": int(b.get("status", 503)),
                                 "code": b.get("code", "ServiceUnavailable"), "times": int(b.get("times", 1))})
            elif name == "moved":
                st.moved = int(b.get("times", 1))
            elif name == "expire-changes":
                before = int(b["before"])
                st.changes = [c for c in st.changes if c["seq"] > before]
                st.floor = max(st.floor, before)
            elif name == "notifications":
                return self.send_json({"sent": list(st.sent)})
            elif name == "requests":
                return self.send_json({"requests": list(st.requests)})
            else:
                raise Refuse(404, "NotFound", "No such fake control.")
        self.send_json({"ok": True})
