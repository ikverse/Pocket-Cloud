"""Push: apps learn of changes at once instead of polling.

A small server built in speaks the part of ntfy's protocol that subscribers need (the official ntfy binaries
cannot run in Termux: they die on Android's system-call filter). The gateway publishes one "changed" message
per app, debounced so a burst of writes gives one message:

    topic pocket-<app>, message {"app": "<app>", "changes": <n>, "ts": <unix time>}

Clients subscribe with a plain HTTP request, or with the ntfy Android app (UnifiedPush) pointed at this server:

    GET  http://<phone>:2586/pocket-<app>/json             newline-delimited JSON, kept open
    GET  http://<phone>:2586/pocket-<app>/sse              the same as server-sent events
    GET  http://<phone>:2586/pocket-<app>/raw              just the message texts
    GET  .../json?poll=1&since=<all|none|message id|unix time|10m>    what was cached, then it ends
    POST http://<phone>:2586/<topic>                       publish (body = message)
    GET  http://<phone>:2586/v1/health

Several topics can be joined with commas. Messages are kept for 24 hours in memory only, so after a server
restart a client that asks for missed messages gets none: it should do a full sync when it reconnects anyway.
There is no authentication: only the tailnet can reach the port, and a spoofed message can do no more than
make a client look for changes.
"""

import json
import queue
import re
import secrets
import socketserver
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

TOPIC = re.compile(r"^[-_A-Za-z0-9]{1,64}$")
DURATION = re.compile(r"^(\d+)([smhd])$")
UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400}
KEEP_S = 24 * 3600           # how long a message stays available to late subscribers
KEEP_N = 200                 # ... and at most this many per topic
KEEPALIVE_S = 45             # an idle stream gets a keepalive line this often, so dead clients are noticed
MAX_BODY = 4096
ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"


class Hub:
    """The messages of every topic, and the streams waiting for the next one."""

    def __init__(self):
        self._lock = threading.Lock()
        self._messages = {}          # topic -> [message, ...] oldest first
        self._subs = {}              # topic -> set of queues

    def publish(self, topic, message, title=None, tags=None, now=None):
        now = int(now or time.time())
        msg = {"id": "".join(secrets.choice(ID_CHARS) for _ in range(12)), "time": now, "expires": now + KEEP_S,
               "event": "message", "topic": topic, "message": message}
        if title:
            msg["title"] = title
        if tags:
            msg["tags"] = tags
        with self._lock:
            lst = self._messages.setdefault(topic, [])
            lst.append(msg)
            lst[:] = [m for m in lst if m["expires"] > now][-KEEP_N:]
            subs = list(self._subs.get(topic, ()))
        for q in subs:
            q.put(msg)
        return msg

    def since(self, topics, since, now=None):
        """Cached messages of the topics for a "since" value: all, none, a message id, a unix time or a duration."""
        now = int(now or time.time())
        with self._lock:
            lists = [list(self._messages.get(t, ())) for t in topics]
        out = []
        for lst in lists:
            lst = [m for m in lst if m["expires"] > now]
            since = since or "none"
            if since == "none":
                chosen = []
            elif since == "all":
                chosen = lst
            elif since.isdigit():
                chosen = [m for m in lst if m["time"] >= int(since)]
            elif DURATION.match(since):
                n, unit = DURATION.match(since).groups()
                chosen = [m for m in lst if m["time"] >= now - int(n) * UNITS[unit]]
            else:                           # a message id; one we don't know (e.g. after a restart) gives all
                ids = [m["id"] for m in lst]
                chosen = lst[ids.index(since) + 1:] if since in ids else lst
            out += chosen
        return sorted(out, key=lambda m: m["time"])

    def subscribe(self, topics):
        q = queue.Queue()
        with self._lock:
            for t in topics:
                self._subs.setdefault(t, set()).add(q)
        return q

    def unsubscribe(self, topics, q):
        with self._lock:
            for t in topics:
                self._subs.get(t, set()).discard(q)

    def close(self):
        """End every open stream."""
        with self._lock:
            queues = {q for subs in self._subs.values() for q in subs}
        for q in queues:
            q.put(None)


class Handler(BaseHTTPRequestHandler):
    server_version = "PocketPush"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def _json(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _not_found(self):
        self._json(404, {"code": 40401, "http": 404, "error": "not found"})

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlsplit(self.path)
        parts = [p for p in url.path.split("/") if p]
        if parts == ["v1", "health"]:
            return self._json(200, {"healthy": True})
        if len(parts) == 2 and parts[1] in ("json", "sse", "raw"):
            topics = parts[0].split(",")
            if all(TOPIC.match(t) for t in topics):
                return self._stream(topics, parts[1], parse_qs(url.query))
        self._not_found()

    def do_POST(self):
        url = urlsplit(self.path)
        parts = [p for p in url.path.split("/") if p]
        n = min(int(self.headers.get("Content-Length") or 0), MAX_BODY)
        body = self.rfile.read(n).decode("utf-8", "replace") if n else ""
        if len(parts) != 1 or not TOPIC.match(parts[0]):
            return self._not_found()
        tags = [t.strip() for t in (self.headers.get("Tags") or "").split(",") if t.strip()]
        msg = self.server.hub.publish(parts[0], body or "triggered", title=self.headers.get("Title"), tags=tags or None)
        self._json(200, msg)

    do_PUT = do_POST

    def _stream(self, topics, kind, qs):
        hub = self.server.hub
        poll = (qs.get("poll", [""])[0] or "").lower() in ("1", "true", "yes")
        since = qs.get("since", ["all" if poll else "none"])[0]
        q = None if poll else hub.subscribe(topics)       # subscribe first, so nothing falls between the two reads
        try:
            backlog = hub.since(topics, since)
            self.send_response(200)
            self.send_header("Content-Type", {"json": "application/x-ndjson; charset=utf-8", "sse": "text/event-stream; charset=utf-8",
                                              "raw": "text/plain; charset=utf-8"}[kind])
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            topic_list = ",".join(topics)
            if not poll:
                self._write(kind, {"id": _new_id(), "time": int(time.time()), "event": "open", "topic": topic_list})
            seen = set()
            for m in backlog:
                seen.add(m["id"])
                self._write(kind, m)
            if poll:
                return
            while True:
                try:
                    m = q.get(timeout=KEEPALIVE_S)
                except queue.Empty:
                    m = {"id": _new_id(), "time": int(time.time()), "event": "keepalive", "topic": topic_list}
                if m is None:
                    return
                if m["id"] not in seen:
                    self._write(kind, m)
        except OSError:
            pass                                    # the client went away
        finally:
            if q is not None:
                hub.unsubscribe(topics, q)

    def _write(self, kind, obj):
        if kind == "json":
            data = json.dumps(obj) + "\n"
        elif kind == "sse":
            data = f"event: {obj['event']}\ndata: {json.dumps(obj)}\n\n"
        else:
            data = obj["message"] + "\n" if obj["event"] == "message" else "\n" if obj["event"] == "keepalive" else ""
        if data:
            self.wfile.write(data.encode("utf-8"))
            self.wfile.flush()


def _new_id():
    return "".join(secrets.choice(ID_CHARS) for _ in range(12))


class PushServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = sys.platform != "win32"

    def __init__(self, address, hub):
        self.hub = hub
        super().__init__(address, Handler)

    def server_bind(self):
        # the stock server looks up the bind address's host name, which can hang on an address without a reverse
        # DNS entry; the name is never used here
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[0], self.server_address[1]

    def handle_error(self, request, client_address):
        pass


class Push:
    def __init__(self, settings, state_dir, log, address=None, debounce_s=2.0):
        self.settings = settings
        self.state_dir = state_dir
        self.log = log
        self.address = address or "127.0.0.1"      # what apps are told: the phone's Tailscale address
        self.debounce_s = debounce_s
        self.hub = Hub()
        self.servers = []
        self.sent = 0
        self.restarts = 0
        self._lock = threading.Lock()
        self._pending = {}                          # app -> [changes, timer]
        self._server_lock = threading.Lock()
        self._delay = 0.0                           # back-off while the listening addresses can't be opened
        self._next_try = 0.0
        self._last_fail_log = 0.0

    # ---- settings ----
    @property
    def enabled(self):
        return bool(self.settings.get("push.enabled"))

    @property
    def port(self):
        return int(self.settings.get("push.port"))

    def url(self, host=None):
        return f"http://{host or self.address}:{self.port}"

    @staticmethod
    def topic(app_id):
        return "pocket-" + app_id

    def info(self, app_id, host=None):
        """What an enrolling app is told: where to subscribe, or None while push is off."""
        if not self.enabled:
            return None
        return {"url": self.url(host), "topic": self.topic(app_id)}

    # ---- publishing ----
    def changed(self, app_id):
        """Something in app_id's spaces changed: publish once the burst settles."""
        if not self.enabled:
            return
        with self._lock:
            p = self._pending.get(app_id)
            if p:
                p[0] += 1
                return
            t = threading.Timer(self.debounce_s, self._flush, args=(app_id,))
            t.daemon = True
            self._pending[app_id] = [1, t]
            t.start()

    def _flush(self, app_id):
        with self._lock:
            count, _ = self._pending.pop(app_id, (0, None))
        if count:
            self.publish(app_id, count)

    def flush(self):
        """Publish everything pending now (used when stopping and by the tests)."""
        with self._lock:
            items = list(self._pending.items())
            self._pending.clear()
        for app_id, (count, timer) in items:
            timer.cancel()
            self.publish(app_id, count)

    def publish(self, app_id, count):
        body = json.dumps({"app": app_id, "changes": count, "ts": time.time()})
        self.hub.publish(self.topic(app_id), body, title=f"{app_id} changed", tags=["pocket"])
        self.sent += 1
        return True

    def healthy(self):
        if not self.servers:
            return False
        host, port = self.servers[0].server_address[:2]
        try:
            with urllib.request.urlopen(f"http://{host}:{port}/v1/health", timeout=5) as r:
                return bool(json.loads(r.read()).get("healthy"))
        except Exception:
            return False

    # ---- listening ----
    def apply(self):
        """Start or stop listening to match the push.enabled setting."""
        if self.enabled:
            self.start()
        else:
            self.stop()

    def _hosts(self):
        return list(self.settings.get("listen")) or ["127.0.0.1"]

    def start(self):
        """Listen on every gateway address. Addresses that can't be opened yet are retried by ensure_running with
        a growing delay (30 s up to 10 minutes), and logged once, then now and then."""
        with self._server_lock:
            have = {s.server_address[0] for s in self.servers}
            failed = []
            for host in self._hosts():
                if host in have:
                    continue
                try:
                    srv = PushServer((host, self.port), self.hub)
                except OSError as e:
                    failed.append(f"{host}:{self.port} ({e})")
                    continue
                threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True,
                                 name=f"push-{host}").start()
                self.servers.append(srv)
                self.log(f"push server listening on {host}:{self.port}")
            if failed:
                self._delay = min(600.0, max(30.0, self._delay * 2))
                self._next_try = time.time() + self._delay
                if time.time() - self._last_fail_log > 600:
                    self.log(f"push: can't listen on {', '.join(failed)}; trying again in {int(self._delay)} s")
                    self._last_fail_log = time.time()
                return False
            self._delay = 0.0
            return True

    def alive(self):
        bound = {s.server_address[0] for s in self.servers}
        return bool(bound) and set(self._hosts()) <= bound

    def ensure_running(self):
        """Called by the scheduler: open the addresses that could not be opened, when the back-off allows."""
        if self.enabled and not self.alive() and time.time() >= self._next_try:
            self.restarts += 1
            self.start()

    def stop(self):
        self.flush()
        with self._server_lock:
            self.hub.close()
            for srv in self.servers:
                srv.shutdown()
                srv.server_close()
            if self.servers:
                self.log("push server stopped")
            self.servers.clear()
            self._delay = 0.0
            self._next_try = 0.0
