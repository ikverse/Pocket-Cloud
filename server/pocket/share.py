"""Share links: one file for someone outside Tailscale.

An app creates a share with its device key (POST /_pocket/share), which gives a token. A separate listener
(this module) serves only GET /s/<token>: the file, after the password if the share has one, until the link
expires or its downloads are used up. Nothing else is reachable through it: no listing, no S3, no control
panel. On the phone, cloudflared (a Cloudflare Tunnel) connects this listener to the internet:

  - a quick tunnel (no account) gets a random https://....trycloudflare.com address each time it starts,
    so links die when the server restarts; the server starts and watches it itself;
  - a named tunnel (a Cloudflare account and domain) keeps one address: run cloudflared yourself and put
    the address in the share.public_url setting; the server then starts no tunnel.
"""

import hashlib
import html
import mimetypes
import os
import re
import secrets
import socketserver
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

TOKEN = re.compile(r"^/s/([A-Za-z0-9_-]{16,64})$")
ATTEMPTS = 5                 # wrong passwords per token and minute
MAX_EXPIRES_S = 30 * 86400
DEFAULT_EXPIRES_S = 7 * 86400
CHUNK = 1024 * 1024


# ---- passwords ----
def hash_password(password):
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2 ** 14, r=8, p=1)
    return "scrypt$%s$%s" % (salt.hex(), h.hex())


def check_password(stored, password):
    try:
        _, salt, h = stored.split("$")
        got = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt), n=2 ** 14, r=8, p=1)
        return secrets.compare_digest(got.hex(), h)
    except Exception:
        return False


FORM = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Pocket Cloud</title><body style="font:16px system-ui;margin:40px auto;max-width:360px;padding:0 16px">
<h2 style="font-weight:600">{name}</h2><p>This link needs a password.</p>{bad}
<form method="post"><input name="password" type="password" autofocus style="font:inherit;padding:8px;width:100%;box-sizing:border-box">
<button style="font:inherit;padding:8px 16px;margin-top:12px">Download</button></form></body>"""


class ShareServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = sys.platform != "win32"

    def __init__(self, address, core):
        self.core = core
        self._tries = {}             # token -> [timestamps of wrong passwords]
        self._tries_lock = threading.Lock()
        super().__init__(address, Handler)

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[0], self.server_address[1]

    def handle_error(self, request, client_address):
        pass

    def too_many_tries(self, token):
        with self._tries_lock:
            now = time.time()
            t = [x for x in self._tries.get(token, []) if now - x < 60]
            self._tries[token] = t
            return len(t) >= ATTEMPTS

    def wrong_try(self, token):
        with self._tries_lock:
            self._tries.setdefault(token, []).append(time.time())


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PocketCloud"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        self._serve()

    def do_HEAD(self):
        self._serve()

    def do_POST(self):
        self._serve()

    def _plain(self, status, text):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _html(self, status, text):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _serve(self):
        core = self.server.core
        path = self.path.partition("?")[0]
        m = TOKEN.match(path)
        n = int(self.headers.get("Content-Length") or 0)
        form = self.rfile.read(min(n, 4096)) if n else b""
        if not m or self.command not in ("GET", "HEAD", "POST"):
            return self._plain(404, "Not found.")
        token = m.group(1)
        share = core.registry.share(token)
        if not share:
            return self._plain(404, "This link does not exist or was removed.")
        now = time.time()
        if now > share["expires"]:
            return self._plain(410, "This link has expired.")
        if share["max_downloads"] and share["downloads"] >= share["max_downloads"]:
            return self._plain(410, "This link has been used as often as it was allowed to.")
        name = share["key"].rsplit("/", 1)[-1]
        if share["password_hash"]:
            given = ""
            if self.command == "POST":
                given = parse_qs(form.decode("utf-8", "replace")).get("password", [""])[0]
            if self.server.too_many_tries(token):
                return self._plain(429, "Too many wrong passwords. Wait a minute.")
            if not given:
                return self._html(200, FORM.format(name=html.escape(name), bad=""))
            if not check_password(share["password_hash"], given):
                self.server.wrong_try(token)
                return self._html(403, FORM.format(name=html.escape(name), bad="<p style='color:#b00'>That password is not right.</p>"))
        elif self.command == "POST":
            return self._plain(405, "Use GET.")
        for storage in core.layout.storages():
            p = core.layout.key_path(storage, share["app_id"], share["space"], share["key"])
            if p and os.path.isfile(p):
                return self._send_file(p, name, token)
        return self._plain(404, "The shared file is no longer there.")

    def _send_file(self, path, name, token):
        size = os.path.getsize(path)
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        start, end = 0, size - 1
        rng = self.headers.get("Range", "")
        partial = False
        if rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            try:
                if a:
                    start, end = int(a), (int(b) if b else size - 1)
                elif b:
                    start, end = max(0, size - int(b)), size - 1
                partial = True
            except ValueError:
                partial = False
            if partial and (start > end or start >= size):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, size - 1)
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Disposition", "attachment; filename*=UTF-8''%s" % _rfc5987(name))
        self.send_header("Cache-Control", "private, no-store")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        if not partial or start == 0:
            self.server.core.registry.share_downloaded(token)
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                b = f.read(min(CHUNK, left))
                if not b:
                    break
                self.wfile.write(b)
                left -= len(b)
        self.wfile.flush()


def _rfc5987(name):
    from urllib.parse import quote
    return quote(name, safe="")


def serve(core, host, port, stop_event):
    """Serve the share listener on one address, retrying while the address isn't available yet."""
    while not stop_event.is_set():
        try:
            srv = ShareServer((host, port), core)
        except OSError as e:
            core.log(f"share listener can't listen on {host}:{port} yet ({e}); retrying in 5 s")
            stop_event.wait(5)
            continue
        core.share_servers.append(srv)
        core.log(f"share listener on {host}:{port}")
        threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True, name=f"share-{host}").start()
        return srv


class Tunnel:
    """A cloudflared quick tunnel to the share listener; its public address is read from cloudflared's own
    output and kept in state/share-url. Restarted by the scheduler if it dies: at once if it had been running
    for a while, otherwise after a growing wait (30 s up to 10 minutes), so a cloudflared that cannot run
    does not restart every couple of seconds."""

    URL = re.compile(rb"https://[a-z0-9-]+\.trycloudflare\.com")
    STABLE_S = 120               # a tunnel that ran this long before dying is restarted at once
    WAIT_MIN_S, WAIT_MAX_S = 30.0, 600.0

    def __init__(self, settings, state_dir, log):
        self.settings = settings
        self.state_dir = state_dir
        self.log = log
        self.proc = None
        self.public_url = ""
        self.restarts = 0
        self._lock = threading.Lock()
        self.binary = None
        self._started = 0.0
        self._delay = 0.0            # the wait before the next restart
        self._next_try = 0.0
        self._noted = None           # the dead process whose exit has been logged

    @property
    def path(self):
        return os.path.join(self.state_dir, "share-url")

    def wanted(self):
        s = self.settings.get("share")
        return bool(s["enabled"]) and not s["public_url"]

    def start(self):
        with self._lock:
            if self.proc and self.proc.poll() is None:
                return True
            self.binary = self.settings.get("share.cloudflared") or _which("cloudflared")
            if not self.binary:
                self.log("share: cloudflared is not installed (run phone/setup.sh); links only work on the tailnet")
                return False
            port = self.settings.get("share.port")
            flags = subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
            self.proc = subprocess.Popen([self.binary, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"],
                                         stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         creationflags=flags)
            self._started = time.time()
            threading.Thread(target=self._watch, args=(self.proc,), daemon=True, name="cloudflared").start()
            self.log("share: cloudflared quick tunnel starting")
            return True

    def _watch(self, proc):
        with open(os.path.join(self.state_dir, "logs", "cloudflared.log"), "ab") as logf:
            for line in proc.stdout:
                logf.write(line)
                m = self.URL.search(line)
                if m and m.group(0).decode() != self.public_url:
                    self.public_url = m.group(0).decode()
                    with open(self.path, "w", encoding="utf-8") as f:
                        f.write(self.public_url)
                    self.log(f"share: public address is {self.public_url}")

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def ensure_running(self):
        """Called by the scheduler every couple of seconds: start cloudflared again after it died, when the wait
        allows. The exit is logged once per death, not at every call."""
        if not self.wanted() or self.proc is None or self.alive():
            return
        now = time.time()
        if self._noted is not self.proc:                     # the first time this death is seen
            self._noted = self.proc
            ran = now - self._started
            self._delay = 0.0 if ran >= self.STABLE_S else min(self.WAIT_MAX_S, max(self.WAIT_MIN_S, self._delay * 2))
            self._next_try = now + self._delay
            self.log("share: cloudflared stopped (exit %s) after %d s; %s" % (
                self.proc.returncode, ran, "starting again" if not self._delay else "trying again in %d s" % self._delay))
        if now >= self._next_try:
            self.restarts += 1
            self.proc = None
            self.start()

    def stop(self):
        with self._lock:
            if self.proc and self.proc.poll() is None:
                self.proc.terminate()
                try:
                    self.proc.wait(10)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(5)
                self.log("share: cloudflared stopped")
            self.proc = None
            self.public_url = ""
            self._delay = 0.0
            self._next_try = 0.0
            try:
                os.remove(self.path)
            except OSError:
                pass


def _which(name):
    import shutil
    return shutil.which(name)
