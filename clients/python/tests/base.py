"""Shared setup: one fake server per test class, a fresh Pocket home and a reset fake per test."""

import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import pocket_cloud as pocket  # noqa: E402
from pocket_cloud import _http  # noqa: E402
from fake_server import ENROLL_SECRET, FakeServer  # noqa: E402

ENV = ("POCKET_ENDPOINT", "POCKET_ENROLL_SECRET", "POCKET_DEVICE_ID", "POCKET_DEVICE_NAME", "POCKET_HOME",
       "POCKET_APP")


class FakeTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = FakeServer().start()
        cls._backoff = _http.BACKOFF
        _http.BACKOFF = (0.01, 0.02, 0.04)        # keep retry tests quick

    @classmethod
    def tearDownClass(cls):
        _http.BACKOFF = cls._backoff
        cls.srv.stop()

    def setUp(self):
        self.srv.control("reset")
        self.home = tempfile.mkdtemp(prefix="pocket-home-")
        self.tmp = tempfile.mkdtemp(prefix="pocket-tmp-")
        with open(os.path.join(self.home, "config"), "w") as f:
            f.write(f"[pocket]\nendpoint = {self.srv.endpoint}\nenroll_secret = {ENROLL_SECRET}\n")
        self._env = {k: os.environ.pop(k) for k in ENV if k in os.environ}

    def tearDown(self):
        shutil.rmtree(self.home, ignore_errors=True)
        shutil.rmtree(self.tmp, ignore_errors=True)
        for k in ENV:
            os.environ.pop(k, None)
        os.environ.update(self._env)

    def open(self, name="test-app", **kw):
        kw.setdefault("home", self.home)
        kw.setdefault("device_name", "Test PC")
        return pocket.app(name, **kw)

    def requests(self):
        return self.srv.control("requests", method="GET")["requests"]

    def path(self, *parts):
        return os.path.join(self.tmp, *parts)

    def write(self, rel, data, folder=None):
        p = os.path.join(folder or self.tmp, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode())
        return p

    def read(self, rel, folder=None):
        with open(os.path.join(folder or self.tmp, *rel.split("/")), "rb") as f:
            return f.read()
