"""Test helpers: a full Pocket Cloud server (real rclone, real gateway) in a temporary folder."""

import json
import os
import shutil
import socket
import sys
import tempfile
import time
import urllib.request

import boto3
from botocore.config import Config

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.dirname(HERE)
sys.path.insert(0, SERVER_DIR)

from pocket.server import Core  # noqa: E402

RCLONE = os.environ.get("POCKET_RCLONE") or os.path.join(os.path.dirname(SERVER_DIR), "tools", "rclone",
                                                        "rclone.exe" if sys.platform == "win32" else "rclone")
GB = 1024 ** 3


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class PocketServer:
    def __init__(self, **overrides):
        self.tmp = tempfile.mkdtemp(prefix="pocket-test-")
        self.state = os.path.join(self.tmp, "state")
        self.int_root = os.path.join(self.tmp, "int")
        self.sd_root = os.path.join(self.tmp, "sd")
        self.port = free_port()
        base = {
            "internal_root": self.int_root, "sd_root": self.sd_root, "rclone_binary": RCLONE,
            "storage_port": self.port, "rclone_port": free_port(), "rc_port": free_port(), "panel_port": free_port(),
            "listen": ["127.0.0.1"],
            "health": {"every_min": 1000},          # tests run health checks themselves
            "server": {"usage_scan_s": 100000},     # and scans
        }
        for k, v in overrides.items():
            if isinstance(v, dict) and isinstance(base.get(k), dict):
                base[k] = {**base[k], **v}
            else:
                base[k] = v
        self.overrides = base
        self.core = None

    def start(self):
        self.core = Core(self.state, self.overrides, echo=False)
        self.core.start()
        self.core.wait_listening()
        return self

    def stop(self, keep_files=False):
        if self.core:
            self.core.stop()
            self.core = None
        if not keep_files:
            shutil.rmtree(self.tmp, ignore_errors=True)

    @property
    def endpoint(self):
        return f"http://127.0.0.1:{self.port}"

    @property
    def panel(self):
        return f"http://127.0.0.1:{self.overrides['panel_port']}"

    def api(self, method, path, body=None, token=None, raw=None, headers=None):
        """Call the control API. Returns (status, parsed JSON or bytes)."""
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {"Content-Type": "application/json"} if body is not None else {}
        if token:
            h["Authorization"] = "Bearer " + token
        h.update(headers or {})
        req = urllib.request.Request(self.panel + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                payload = r.read()
                status = r.status
                ctype = r.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            payload, status, ctype = e.read(), e.code, e.headers.get("Content-Type", "")
        return status, (json.loads(payload) if "json" in ctype and payload else payload)

    def enroll(self, app, uid, name="Test device", secret=None):
        body = json.dumps({"secret": secret if secret is not None else self.core.settings.get("enroll_secret"),
                           "app": app, "device_id": uid, "device_name": name}).encode()
        req = urllib.request.Request(self.endpoint + "/_pocket/enroll", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def pocket(self, enrollment, method, path, query="", body=None):
        """A signed call to a /_pocket/ endpoint. Returns (status, parsed JSON)."""
        from pocket import sigv4
        data = json.dumps(body).encode() if body is not None else b""
        h = sigv4.sign_pocket(method, path, query, data, enrollment["access_key"], enrollment["secret_key"])
        if body is not None:
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(self.endpoint + path + ("?" + query if query else ""),
                                     data=data if body is not None else None, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                payload = e.read()
            try:
                return e.code, json.loads(payload or b"{}")
            except ValueError:
                return e.code, payload

    def client(self, enrollment, **cfg):
        return boto3.client(
            "s3", endpoint_url=enrollment["endpoint"], aws_access_key_id=enrollment["access_key"],
            aws_secret_access_key=enrollment["secret_key"], region_name="us-east-1",
            config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": cfg.pop("retries", 1)}, **cfg))

    def files(self, storage, *parts):
        return os.path.join(self.int_root if storage == "int" else self.sd_root, *parts)
