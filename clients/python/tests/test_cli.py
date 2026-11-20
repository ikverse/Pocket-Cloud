"""The `pocket` command against the fake server."""

import io
import os
import subprocess
import sys
import unittest
from contextlib import redirect_stderr

from base import ENROLL_SECRET, FakeTestCase

from pocket_cloud import cli

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class CliTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        os.environ["POCKET_HOME"] = self.home
        os.environ["POCKET_APP"] = "cli-app"

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stderr(err):
            try:
                code = cli.run(list(argv), out=out)
            except Exception as e:
                from pocket_cloud import PocketError
                if not isinstance(e, PocketError):
                    raise
                print(f"pocket: {e.message}", file=err)
                code = 1
        return code, out.getvalue(), err.getvalue()

    def test_put_ls_get(self):
        src = self.write("report.txt", "hello")
        code, out, _ = self.run_cli("put", src, "reports/report.txt")
        self.assertEqual(code, 0)
        self.assertIn("shared/reports/report.txt", out)
        code, out, _ = self.run_cli("ls")
        self.assertEqual(out.split(), ["reports/"])
        code, out, _ = self.run_cli("ls", "reports/", "-l")
        self.assertIn("5 B", out)
        dest = self.path("back.txt")
        self.assertEqual(self.run_cli("get", "reports/report.txt", dest)[0], 0)
        self.assertEqual(self.read("back.txt"), b"hello")

    def test_backup_sync_publish_notify(self):
        db = self.write("ledger.db", "not really sqlite")
        code, out, _ = self.run_cli("backup", db, "--keep", "2")
        self.assertEqual(code, 0)
        self.assertIn("backups/ledger.db/", out)
        folder = self.path("notes")
        self.write("a.txt", "a", folder)
        code, out, _ = self.run_cli("sync", folder, "--prefix", "notes/")
        self.assertIn("1 up, 0 down", out)
        code, out, _ = self.run_cli("publish", self.write("tool.zip", "zip"), "1.2.3", "--platform", "any")
        self.assertIn("published cli-app 1.2.3", out)
        code, _, err = self.run_cli("notify", "hi")
        self.assertEqual(code, 1)
        self.assertIn("switched off", err)
        self.srv.control("app", {"notify": True}, query="app=cli-app")
        self.assertEqual(self.run_cli("notify", "hi", "--title", "T", "--tag", "tada")[0], 0)

    def test_installed_entry_point_style(self):
        env = dict(os.environ, POCKET_ENDPOINT=self.srv.endpoint, POCKET_ENROLL_SECRET=ENROLL_SECRET,
                   PYTHONPATH=ROOT)
        r = subprocess.run([sys.executable, "-m", "pocket_cloud.cli", "ls"], env=env, capture_output=True,
                           text=True, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        r = subprocess.run([sys.executable, "-m", "pocket_cloud.cli", "get", "missing.txt"], env=env,
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 1)
        self.assertIn("[NoSuchKey]", r.stderr)
        env.pop("POCKET_APP")
        r = subprocess.run([sys.executable, "-m", "pocket_cloud.cli", "ls"], env=env, capture_output=True,
                           text=True, timeout=60)
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
