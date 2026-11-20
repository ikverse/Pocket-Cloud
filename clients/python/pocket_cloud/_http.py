"""The HTTP side of pocket.app(): signing, errors, retries and re-enrollment, on the standard library only."""

import hashlib
import http.client
import json
import socket
import time
import urllib.parse

from . import _sign
from ._errors import PocketError, Unreachable, error_for

BACKOFF = (0.5, 1.0, 2.0)
CHUNK = 1 << 20


class Response:
    def __init__(self, status, headers, data):
        self.status = status
        self.headers = headers          # lower-cased names
        self.data = data

    def json(self):
        return json.loads(self.data or b"{}")


def file_sha256(f):
    """Hash an open binary file from where it is, then go back there. Returns (hex, size)."""
    start = f.tell()
    h, size = hashlib.sha256(), 0
    while True:
        chunk = f.read(CHUNK)
        if not chunk:
            break
        h.update(chunk)
        size += len(chunk)
    f.seek(start)
    return h.hexdigest(), size


def _connection(url, timeout):
    parts = urllib.parse.urlsplit(url)
    cls = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    return cls(parts.hostname, parts.port, timeout=timeout), parts.path.rstrip("/")


def raw_request(url, method, path, query="", body=b"", headers=None, timeout=30, sink=None):
    """One HTTP exchange. body is bytes or an open binary file (with Content-Length in headers). With sink, a 2xx
    body is written to sink.write() instead of being kept. Raises OSError / http.client errors as they come."""
    conn, base = _connection(url, timeout)
    try:
        hdrs = dict(headers or {})
        if isinstance(body, (bytes, bytearray)):
            hdrs.setdefault("Content-Length", str(len(body)))
        conn.request(method, base + path + ("?" + query if query else ""), body=body if body else None, headers=hdrs)
        r = conn.getresponse()
        got = {k.lower(): v for k, v in r.getheaders()}
        if sink is not None and 200 <= r.status < 300 and method != "HEAD":
            while True:
                chunk = r.read(CHUNK)
                if not chunk:
                    break
                sink.write(chunk)
            return Response(r.status, got, b"")
        return Response(r.status, got, r.read())
    finally:
        conn.close()


def raise_for(resp, method):
    """Turn a non-2xx/304 answer into the library error."""
    body, code, message = {}, None, None
    if method == "HEAD":
        code = resp.headers.get("x-pocket-error-code")
    else:
        try:
            body = json.loads(resp.data or b"{}")
            if isinstance(body, dict):
                code, message = body.get("error"), body.get("message")
            else:
                body = {}
        except ValueError:
            body = {}
    raise error_for(resp.status, code, message, body, resp.headers)


class Transport:
    """Signed requests for one app and device. device is the saved key (a pocket_cloud._Device)."""

    def __init__(self, device, timeout=30):
        self.device = device
        self.timeout = timeout

    @property
    def endpoint(self):
        return self.device.endpoint

    def unsigned(self, method, path, query="", retry=True, sink=None, headers=None, url=None):
        return self._send(method, path, query, b"", headers or {}, retry, sink, signed=False, url=url)

    def request(self, method, path, params=None, body=b"", headers=None, retry=False, sink=None, ok=(200,)):
        """A signed request. params: list of (name, value). body: bytes or an open binary file. ok: statuses that
        are answers, not errors (304 is always passed through). retry: retry on Unreachable / 503."""
        query = _sign.query_string(params or [])
        headers = dict(headers or {})
        if hasattr(body, "read"):
            digest, size = file_sha256(body)
            headers["Content-Length"] = str(size)
        else:
            body = bytes(body or b"")
            digest = hashlib.sha256(body).hexdigest()
        if method == "PUT":
            headers["x-pocket-content-sha256"] = digest
        resp = self._send(method, path, query, body, headers, retry, sink, signed=True, digest=digest)
        if resp.status in ok or resp.status == 304:
            return resp
        raise_for(resp, method)

    def _send(self, method, path, query, body, headers, retry, sink, signed, digest=None, url=None):
        attempts = len(BACKOFF) + 1 if retry else 1
        reenrolled = False
        start = body.tell() if hasattr(body, "read") else None
        attempt = 0
        while True:
            hdrs = dict(headers)
            if signed:
                creds = self.device.creds
                hdrs.update(_sign.sign(method, path, query, digest, creds["access_key"], creds["secret_key"], time.time()))
            if start is not None:
                body.seek(start)
            if sink is not None and hasattr(sink, "seek"):
                sink.seek(0)
                sink.truncate()
            wait = None
            try:
                resp = raw_request(url or self.endpoint, method, path, query, body, hdrs, self.timeout, sink)
            except (OSError, http.client.HTTPException, socket.timeout) as e:
                if attempt + 1 >= attempts:
                    raise Unreachable(f"Pocket Cloud can't be reached at {url or self.endpoint}: {e}",
                                      "Unreachable", 0) from None
                resp = None
            if resp is not None:
                code = self._code(resp, method)
                if signed and resp.status == 403 and not reenrolled and (
                        resp.headers.get("x-pocket-error") == "device-address-changed" or code == "InvalidAccessKeyId"):
                    reenrolled = True
                    with self.device.lock:
                        self.device.enroll()
                    continue
                if resp.status != 503 or attempt + 1 >= attempts:
                    return resp
                try:
                    wait = float(resp.headers.get("retry-after"))
                except (TypeError, ValueError):
                    wait = None
            time.sleep(wait if wait is not None else BACKOFF[attempt])
            attempt += 1

    @staticmethod
    def _code(resp, method):
        if resp.status < 400:
            return None
        if method == "HEAD":
            return resp.headers.get("x-pocket-error-code")
        try:
            return json.loads(resp.data or b"{}").get("error")
        except (ValueError, AttributeError):
            return None


def check_ok(resp, method="GET"):
    if not 200 <= resp.status < 300:
        raise_for(resp, method)
    return resp


__all__ = ["Transport", "Response", "raw_request", "raise_for", "check_ok", "file_sha256", "PocketError"]
