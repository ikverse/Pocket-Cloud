"""The control panel server (port 8000): the JSON control API used by the web panel and the Android app,
and the web panel's own files.

Sign-in: an admin password, set on first use. A successful sign-in returns a token; requests carry it as
"Authorization: Bearer <token>". Only a hash of each token is kept (state/admin.json), so tokens survive restarts.
"""

import hashlib
import hmac
import json
import mimetypes
import os
import re
import secrets
import socket
import socketserver
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

from . import panel_api as api

WEB_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "panel_web")
TOKEN_DAYS = 90
MAX_JSON = 16 * 1024 * 1024


class Admin:
    """The admin password and sign-in tokens."""

    def __init__(self, state_dir):
        self.path = os.path.join(state_dir, "admin.json")
        self._lock = threading.Lock()
        self._attempts = {}
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data = json.load(f)
        except (FileNotFoundError, ValueError):
            self.data = {"password": None, "tokens": {}}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    @staticmethod
    def _hash(password, salt, rounds=200_000):
        return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), rounds).hex()

    def has_password(self):
        return bool(self.data.get("password"))

    def set_password(self, password):
        if len(password) < 8:
            raise api.bad("The admin password needs at least 8 characters.")
        salt = secrets.token_hex(16)
        with self._lock:
            self.data["password"] = {"salt": salt, "hash": self._hash(password, salt), "rounds": 200_000}
            self.data["tokens"] = {}          # a new password signs every device out
            self._save()

    def check(self, password):
        p = self.data.get("password")
        return bool(p) and hmac.compare_digest(self._hash(password, p["salt"], p["rounds"]), p["hash"])

    def allow_attempt(self, ip):
        """At most 10 password attempts per address per 5 minutes."""
        now = time.time()
        with self._lock:
            recent = [t for t in self._attempts.get(ip, []) if now - t < 300]
            recent.append(now)
            self._attempts[ip] = recent
            return len(recent) <= 10

    def new_token(self, label):
        token = secrets.token_urlsafe(32)
        with self._lock:
            self.data["tokens"][hashlib.sha256(token.encode()).hexdigest()] = {
                "label": label[:80], "created": time.time(), "expires": time.time() + TOKEN_DAYS * 86400}
            self._save()
        return token

    def valid(self, token):
        if not token:
            return False
        h = hashlib.sha256(token.encode()).hexdigest()
        t = self.data["tokens"].get(h)
        if not t or t["expires"] < time.time():
            return False
        if t["expires"] - time.time() < (TOKEN_DAYS - 1) * 86400:      # in use: keep it valid (sliding)
            with self._lock:
                t["expires"] = time.time() + TOKEN_DAYS * 86400
                self._save()
        return True

    def revoke(self, token):
        with self._lock:
            self.data["tokens"].pop(hashlib.sha256(token.encode()).hexdigest(), None)
            self._save()


# ---------------------------------------------------------------- routes: (method, pattern, handler, needs sign-in)
def _routes():
    R = []

    def route(method, pattern, auth=True):
        def deco(fn):
            R.append((method, re.compile("^" + pattern + "$"), fn, auth))
            return fn
        return deco

    @route("GET", "/api/status", auth=False)
    def status(h, core, m):
        return {"ok": True, "version": core.version, "password_set": h.server.admin.has_password(),
                "signed_in": h.server.admin.valid(h.token())}

    @route("POST", "/api/setup", auth=False)
    def setup(h, core, m):
        body = h.json_body()        # read first: refusing with the request unread can reset the connection
        if h.server.admin.has_password():
            raise api.ApiError(409, "AlreadySetUp", "The admin password is already set.")
        h.server.admin.set_password(str(body.get("password", "")))
        return {"token": h.server.admin.new_token(body.get("device") or h.headers.get("User-Agent", ""))}

    @route("POST", "/api/login", auth=False)
    def login(h, core, m):
        admin = h.server.admin
        body = h.json_body()        # read first: refusing with the request unread can reset the connection
        if not admin.allow_attempt(h.client_address[0]):
            raise api.ApiError(429, "TooManyAttempts", "Too many sign-in attempts. Wait a few minutes.")
        if not admin.check(str(body.get("password", ""))):
            core.log(f"failed control panel sign-in from {h.client_address[0]}")
            raise api.ApiError(401, "WrongPassword", "That password is not right.")
        return {"token": admin.new_token(body.get("device") or h.headers.get("User-Agent", ""))}

    @route("POST", "/api/logout")
    def logout(h, core, m):
        h.server.admin.revoke(h.token())
        return {"ok": True}

    @route("POST", "/api/password")
    def password(h, core, m):
        body = h.json_body()
        if not h.server.admin.check(str(body.get("old", ""))):
            raise api.ApiError(401, "WrongPassword", "The current password is not right.")
        h.server.admin.set_password(str(body.get("new", "")))
        return {"token": h.server.admin.new_token(h.headers.get("User-Agent", ""))}

    simple = {
        ("GET", "/api/overview"): lambda h, c, m: api.overview(c),
        ("GET", "/api/storage"): lambda h, c, m: api.storage(c),
        ("GET", "/api/apps"): lambda h, c, m: api.apps_list(c),
        ("POST", "/api/apps"): lambda h, c, m: api.app_create(c, h.json_body()),
        ("GET", "/api/devices"): lambda h, c, m: api.devices_list(c),
        ("GET", "/api/undo"): lambda h, c, m: api.undo_list(c),
        ("POST", "/api/undo/restore"): lambda h, c, m: api.undo_restore(c, h.json_body()),
        ("GET", "/api/jobs"): lambda h, c, m: api.jobs_list(c),
        ("GET", "/api/alerts"): lambda h, c, m: api.alerts(c),
        ("PATCH", "/api/alerts"): lambda h, c, m: api.alerts_update(c, h.json_body()),
        ("GET", "/api/settings"): lambda h, c, m: api.settings_get(c),
        ("PATCH", "/api/settings"): lambda h, c, m: api.settings_update(c, h.json_body()),
        ("GET", "/api/settings/enroll-secret"): lambda h, c, m: api.enroll_secret(c),
        ("POST", "/api/settings/enroll-secret/rotate"): lambda h, c, m: api.enroll_secret(c, rotate=True),
        ("GET", "/api/settings/backup"): lambda h, c, m: api.backup(c),
        ("POST", "/api/settings/restore"): lambda h, c, m: api.restore(c, h.json_body()),
        ("POST", "/api/server/restart"): lambda h, c, m: (c.restart_rclone_drained(), {"ok": True})[1],
        ("GET", "/api/logs"): lambda h, c, m: api.logs(c, h.query()),
        ("GET", "/api/logs/system"): lambda h, c, m: api.syslog(c, h.query().get("lines") or 500),
        ("GET", "/api/files"): lambda h, c, m: api.files_list(c, h.query().get("app", ""), h.query().get("space", "shared"),
                                                             h.query().get("path", "")),
        ("DELETE", "/api/files"): lambda h, c, m: api.file_delete(c, h.query().get("app", ""), h.query().get("space", ""),
                                                                h.query().get("key", "")),
        ("POST", "/api/files/folder"): lambda h, c, m: api.folder_create(c, h.json_body().get("app", ""), h.json_body().get("space", ""),
                                                                        h.json_body().get("path", "")),
        # SD cards (cards.py)
        ("GET", "/api/cards"): lambda h, c, m: c.cards.status(),
        ("GET", "/api/cards/swap"): lambda h, c, m: c.cards.swap_plan(),
        ("POST", "/api/cards/swap"): lambda h, c, m: c.cards.swap_start(h.json_body()),
        ("POST", "/api/cards/use"): lambda h, c, m: c.cards.use_new(h.json_body()),
        ("GET", "/api/cards/unassigned"): lambda h, c, m: c.cards.unassigned(),
        ("POST", "/api/cards/unassigned/assign"): lambda h, c, m: c.cards.unassigned_assign(h.json_body()),
        ("POST", "/api/cards/unassigned/delete"): lambda h, c, m: c.cards.unassigned_delete(h.json_body()),
    }
    for (method, path), fn in simple.items():
        R.append((method, re.compile("^" + re.escape(path) + "$"), fn, True))

    A = "/api/apps/(?P<app>_?[a-z0-9][a-z0-9-]{0,39})"
    D = "(?P<uid>[A-Za-z0-9-]{8,64})"
    C = "/api/cards/(?P<card>[0-9a-f]{8})"
    more = [
        ("GET", A, lambda h, c, m: api.app_detail(c, m["app"])),
        ("PATCH", A, lambda h, c, m: api.app_update(c, m["app"], h.json_body())),
        ("DELETE", A, lambda h, c, m: api.app_delete(c, m["app"])),
        ("POST", A + "/empty", lambda h, c, m: api.app_empty(c, m["app"])),
        ("POST", A + "/rotate-keys", lambda h, c, m: api.app_rotate_all(c, m["app"])),
        ("POST", A + "/devices/" + D + "/rotate-key", lambda h, c, m: api.device_rotate(c, m["app"], m["uid"])),
        ("POST", A + "/devices/" + D + "/block", lambda h, c, m: api.device_block(c, m["uid"], h.json_body(), m["app"])),
        ("DELETE", A + "/devices/" + D, lambda h, c, m: api.device_remove(c, m["uid"], m["app"])),
        ("POST", "/api/devices/" + D + "/rename", lambda h, c, m: api.device_rename(c, m["uid"], h.json_body())),
        ("POST", "/api/devices/" + D + "/block", lambda h, c, m: api.device_block(c, m["uid"], h.json_body())),
        ("DELETE", "/api/devices/" + D, lambda h, c, m: api.device_remove(c, m["uid"])),
        ("POST", "/api/jobs/(?P<job>[a-z-]+)/run", lambda h, c, m: api.job_run(c, m["job"])),
        ("PATCH", C, lambda h, c, m: c.cards.rename(m["card"], h.json_body())),
        ("DELETE", C, lambda h, c, m: c.cards.forget(m["card"])),
        ("GET", C + "/compare", lambda h, c, m: c.cards.compare(m["card"])),
        ("POST", C + "/activate", lambda h, c, m: c.cards.activate(m["card"], h.json_body())),
        ("GET", C + "/files", lambda h, c, m: c.cards.files(m["card"], h.query().get("app", ""), h.query().get("space", ""),
                                                             h.query().get("path", ""))),
        ("POST", C + "/restore", lambda h, c, m: c.cards.restore(m["card"], h.json_body())),
    ]
    for method, pattern, fn in more:
        R.append((method, re.compile("^" + pattern + "$"), fn, True))
    return R


ROUTES = _routes()


class PanelServer(ThreadingHTTPServer):
    daemon_threads = True
    # On Linux/Android this only lets a restarted server take its port back at once instead of waiting about a
    # minute for old connections to time out. On Windows it would let another program share the port, so not there.
    allow_reuse_address = sys.platform != "win32"

    def __init__(self, address, core, admin):
        self.core, self.admin = core, admin
        super().__init__(address, PanelHandler)

    def server_bind(self):
        # the stock server looks up the bind address's host name, which can hang for seconds on an address
        # without a reverse DNS entry (a LAN or second loopback address); the name is never used here
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[0], self.server_address[1]

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError, socket.timeout)):
            return
        self.core.log("panel connection failed:\n" + traceback.format_exc())


class PanelHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PocketCloudPanel"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def token(self):
        auth = self.headers.get("Authorization", "")
        return auth[7:] if auth.startswith("Bearer ") else ""

    def query(self):
        return {k: v[0] for k, v in parse_qs(urlsplit(self.path).query).items()}

    def json_body(self):
        if self._body_read:
            return self._body
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_JSON:
            raise api.bad("Request too large.")
        raw = self.rfile.read(n) if n else b""
        self._body_read = True
        try:
            self._body = json.loads(raw or b"{}")
        except ValueError:
            raise api.bad("The request body is not valid JSON.")
        if not isinstance(self._body, dict):
            raise api.bad("The request body must be a JSON object.")
        return self._body

    def send(self, status, body, ctype="application/json", extra=()):
        data = body if isinstance(body, bytes) else json.dumps(body, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in extra:
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_GET(self):
        self.dispatch()

    do_POST = do_PATCH = do_DELETE = do_PUT = do_HEAD = do_GET

    def dispatch(self):
        core = self.server.core
        path = unquote(urlsplit(self.path).path)
        # One handler serves every request on a kept-alive connection: reset per-request state.
        self._body, self._body_read = None, False
        try:
            if not path.startswith("/api/"):
                return self.static(path)
            if path == "/api/files/download" and self.command == "GET":
                return self.download()
            if path == "/api/files/upload" and self.command == "PUT":
                return self.upload()
            for method, rx, fn, auth in ROUTES:
                m = rx.match(path)
                if m and method == self.command:
                    if auth and not self.server.admin.valid(self.token()):
                        raise api.ApiError(401, "SignInNeeded", "Sign in to the control panel first.")
                    return self.send(200, fn(self, core, m.groupdict()))
            raise api.ApiError(404, "NotFound", "Unknown control panel address.")
        except api.ApiError as e:
            extra = ()
            if int(self.headers.get("Content-Length") or 0) and not self._body_read:
                # answered before the request's content was read: it would garble the next request, so close
                self.close_connection = True
                extra = (("Connection", "close"),)
            self.send(e.status, {"error": e.code, "message": e.message}, extra=extra)
        except (ConnectionError, socket.timeout, TimeoutError):
            self.close_connection = True
        except Exception:
            core.log("panel error:\n" + traceback.format_exc())
            self.close_connection = True
            try:
                self.send(500, {"error": "InternalError", "message": "The control panel hit an unexpected error."})
            except OSError:
                pass

    # ---- files: streamed rather than JSON ----
    def _need_auth(self):
        if not self.server.admin.valid(self.token() or self.query().get("token", "")):
            raise api.ApiError(401, "SignInNeeded", "Sign in to the control panel first.")

    def download(self):
        self._need_auth()
        q = self.query()
        p = api.file_path(self.server.core, q.get("app", ""), q.get("space", ""), q.get("key", ""))
        size = os.path.getsize(p)
        name = os.path.basename(p).replace('"', "")
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", f'attachment; filename="{name}"')
        self.end_headers()
        with open(p, "rb") as f:
            while True:
                b = f.read(256 * 1024)
                if not b:
                    break
                self.wfile.write(b)

    def upload(self):
        self._need_auth()
        q = self.query()
        n = int(self.headers.get("Content-Length") or 0)
        out = api.file_upload(self.server.core, q.get("app", ""), q.get("space", ""), q.get("key", ""), self.rfile, n)
        self._body_read = True      # the body was the file itself, now fully read
        self.send(200, out)

    # ---- the web panel ----
    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        full = os.path.normpath(os.path.join(WEB_DIR, path.lstrip("/")))
        if not full.startswith(WEB_DIR) or not os.path.isfile(full):
            full = os.path.join(WEB_DIR, "index.html")       # the panel switches pages itself
            if not os.path.isfile(full):
                raise api.ApiError(404, "NotFound", "The web panel files are missing.")
        with open(full, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        self.send(200, data, ctype)


def serve(core, admin, host, port, stop_event):
    while not stop_event.is_set():
        try:
            srv = PanelServer((host, port), core, admin)
        except OSError as e:
            core.log(f"control panel can't listen on {host}:{port} yet ({e}); retrying in 5 s")
            stop_event.wait(5)
            continue
        core.panel_servers.append(srv)
        core.log(f"control panel listening on {host}:{port}")
        threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True, name=f"panel-{host}").start()
        return srv
