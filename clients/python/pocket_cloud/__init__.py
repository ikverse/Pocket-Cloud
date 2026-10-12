"""Pocket Cloud for Python apps.

    import pocket_cloud as pocket
    s3 = pocket.connect("egx")          # a ready boto3 S3 client
    s3.put_object(Bucket="shared", Key="prices/today.json", Body=data)
    s3.get_object(Bucket="device", Key="settings.json")

The first time an app connects from a machine, this enrolls it with the server and saves the device's key.
Every app has two buckets: "shared" (all its devices) and "device" (this device only).

Machine setup, once per machine: <home>/.pocket/config with

    [pocket]
    endpoint = http://pocket:9000
    enroll_secret = ...

or the environment variables POCKET_ENDPOINT and POCKET_ENROLL_SECRET (for CI). POCKET_DEVICE_ID and
POCKET_DEVICE_NAME optionally fix the device's identity, e.g. POCKET_DEVICE_ID=github-actions.
"""

import configparser
import json
import os
import re
import secrets
import socket
import tempfile
import threading
import urllib.error
import urllib.request

import boto3
import botocore.session
from botocore.config import Config

__version__ = "0.1.0"
__all__ = ["connect", "PocketError"]

DEVICE_ID = re.compile(r"^[A-Za-z0-9-]{8,64}$")


class PocketError(Exception):
    """Pocket Cloud could not be reached or refused this app or device."""


def _home(home=None):
    return home or os.environ.get("POCKET_HOME") or os.path.join(os.path.expanduser("~"), ".pocket")


def _write_private(path, text):
    """Write a file only this user can read, replacing any old one in a single step."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise


def _machine_config(home, endpoint, enroll_secret):
    endpoint = endpoint or os.environ.get("POCKET_ENDPOINT")
    enroll_secret = enroll_secret or os.environ.get("POCKET_ENROLL_SECRET")
    if not (endpoint and enroll_secret):
        cfg = configparser.ConfigParser()
        cfg.read(os.path.join(home, "config"), encoding="utf-8")
        section = cfg["pocket"] if cfg.has_section("pocket") else {}
        endpoint = endpoint or section.get("endpoint")
        enroll_secret = enroll_secret or section.get("enroll_secret")
    if not endpoint or not enroll_secret:
        raise PocketError(
            "This machine is not set up for Pocket Cloud. Save the machine setup file from the control panel's "
            f"Settings page as {os.path.join(home, 'config')}, or set POCKET_ENDPOINT and POCKET_ENROLL_SECRET.")
    return endpoint.rstrip("/"), enroll_secret


def _own_addresses():
    """This machine's addresses (Tailscale and LAN), so the server can accept its key from either."""
    out = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            a = info[4][0]
            if a not in out and not a.startswith("127."):
                out.append(a)
    except OSError:
        pass
    return out[:10]


def _device_id(home, given):
    given = given or os.environ.get("POCKET_DEVICE_ID")
    if given:
        if not DEVICE_ID.match(given):
            raise PocketError("A device id uses 8-64 letters, digits and dashes.")
        return given
    path = os.path.join(home, "device_id")
    try:
        with open(path, encoding="utf-8") as f:
            existing = f.read().strip()
        if DEVICE_ID.match(existing):
            return existing
    except FileNotFoundError:
        pass
    new = "dev-" + secrets.token_hex(12)
    _write_private(path, new + "\n")
    return new


class _Device:
    """This device's key for one app: saved on disk, re-enrolled when the server asks for it."""

    def __init__(self, app, home, endpoint, enroll_secret, device_id, device_name):
        self.app = app
        self.home = _home(home)
        self.endpoint, self.enroll_secret = _machine_config(self.home, endpoint, enroll_secret)
        self.device_id = _device_id(self.home, device_id)
        self.device_name = device_name or os.environ.get("POCKET_DEVICE_NAME") or socket.gethostname()
        self.path = os.path.join(self.home, "apps", app + ".json")
        self.lock = threading.Lock()
        self.creds = self._load() or self.enroll()

    def _load(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                saved = json.load(f)
        except (FileNotFoundError, ValueError):
            return None
        if saved.get("endpoint") != self.endpoint or saved.get("device_id") != self.device_id:
            return None
        return saved

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
                msg = json.loads(e.read()).get("message")
            except ValueError:
                msg = None
            raise PocketError(f"Pocket Cloud refused to enroll {self.app}: {msg or e}") from None
        except OSError as e:
            raise PocketError(f"Pocket Cloud can't be reached at {self.endpoint}: {e}") from None
        creds = {"endpoint": self.endpoint, "device_id": self.device_id, "access_key": got["access_key"],
                 "secret_key": got["secret_key"], "region": got.get("region", "us-east-1"),
                 "endpoints": got.get("endpoints") or {}}
        _write_private(self.path, json.dumps(creds, indent=2))
        self.creds = creds
        return creds


def connect(app, *, endpoint=None, enroll_secret=None, device_id=None, device_name=None, home=None, **config):
    """Connect app to Pocket Cloud and return a ready boto3 S3 client for its "shared" and "device" buckets.

    Extra keyword arguments are passed to botocore's Config (e.g. connect_timeout=5)."""
    dev = _Device(app, home, endpoint, enroll_secret, device_id, device_name)

    session = botocore.session.get_session()
    session.set_credentials(dev.creds["access_key"], dev.creds["secret_key"])
    creds = session.get_credentials()    # the client signs with this object, so updating it updates the client
    base = Config(region_name=dev.creds.get("region", "us-east-1"), signature_version="s3v4",
                  s3={"addressing_style": "path"}, retries={"mode": "standard", "max_attempts": 5},
                  request_checksum_calculation="when_required", response_checksum_validation="when_required")
    client = boto3.session.Session(botocore_session=session).client(
        "s3", endpoint_url=dev.endpoint, config=base.merge(Config(**config)) if config else base)

    def reenroll_and_retry(response=None, request_dict=None, **_):
        """The device moved to a new address or its key was replaced: enroll again and retry once."""
        if not response or request_dict is None:
            return None
        http, parsed = response
        code = (parsed or {}).get("Error", {}).get("Code")
        moved = http.headers.get("x-pocket-error") == "device-address-changed"
        if http.status_code != 403 or not (moved or code == "InvalidAccessKeyId"):
            return None
        ctx = request_dict.setdefault("context", {})
        if ctx.get("pocket_reenrolled"):
            return None
        ctx["pocket_reenrolled"] = True
        with dev.lock:
            new = dev.enroll()
            creds.access_key, creds.secret_key = new["access_key"], new["secret_key"]
        return 0

    client.meta.events.register_first("needs-retry.s3", reenroll_and_retry)
    return client
