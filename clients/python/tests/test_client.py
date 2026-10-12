"""The Python library against the real Pocket Cloud server (server/tests/helpers.py starts one)."""

import json
import os
import shutil
import sys
import tempfile
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))          # Pocket-Cloud
sys.path.insert(0, os.path.join(ROOT, "server", "tests"))
sys.path.insert(0, os.path.dirname(HERE))

import pocket_cloud as pocket  # noqa: E402
from helpers import PocketServer  # noqa: E402


class ClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = PocketServer().start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="pocket-home-")
        with open(os.path.join(self.home, "config"), "w") as f:
            f.write(f"[pocket]\nendpoint = {self.srv.endpoint}\nenroll_secret = {self.srv.core.settings.get('enroll_secret')}\n")
        for k in ("POCKET_ENDPOINT", "POCKET_ENROLL_SECRET", "POCKET_DEVICE_ID", "POCKET_DEVICE_NAME", "POCKET_HOME"):
            os.environ.pop(k, None)

    def tearDown(self):
        shutil.rmtree(self.home, ignore_errors=True)

    def enrolls(self, app):
        self.srv.core.stats.flush()
        return self.srv.core.stats.query("SELECT COUNT(*) AS n FROM requests WHERE op='Enroll' AND app=?", (app,))[0]["n"]

    def test_first_connect_enrolls_and_both_buckets_work(self):
        s3 = pocket.connect("py-first", home=self.home, device_name="Test PC")
        s3.put_object(Bucket="shared", Key="hello.txt", Body=b"hello")
        s3.put_object(Bucket="device", Key="mine.txt", Body=b"private")
        self.assertEqual(s3.get_object(Bucket="shared", Key="hello.txt")["Body"].read(), b"hello")
        self.assertEqual(s3.get_object(Bucket="device", Key="mine.txt")["Body"].read(), b"private")
        dev_id = open(os.path.join(self.home, "device_id")).read().strip()
        self.assertTrue(dev_id.startswith("dev-"))
        saved = json.load(open(os.path.join(self.home, "apps", "py-first.json")))
        self.assertEqual(saved["device_id"], dev_id)
        devices = self.srv.core.registry.devices("py-first")
        self.assertEqual([(d["uid"], d["name"]) for d in devices], [(dev_id, "Test PC")])

    def test_later_connects_reuse_the_saved_key(self):
        pocket.connect("py-reuse", home=self.home).list_objects_v2(Bucket="shared")
        pocket.connect("py-reuse", home=self.home).list_objects_v2(Bucket="shared")
        pocket.connect("py-reuse", home=self.home).list_objects_v2(Bucket="shared")
        self.assertEqual(self.enrolls("py-reuse"), 1)

    def test_two_apps_on_one_machine_share_the_device_id(self):
        a = pocket.connect("py-app-a", home=self.home)
        b = pocket.connect("py-app-b", home=self.home)
        a.put_object(Bucket="shared", Key="x", Body=b"a")
        self.assertEqual(b.list_objects_v2(Bucket="shared").get("Contents", []), [], "apps can't see each other")
        uid_a = self.srv.core.registry.devices("py-app-a")[0]["uid"]
        uid_b = self.srv.core.registry.devices("py-app-b")[0]["uid"]
        self.assertEqual(uid_a, uid_b)

    def test_recovers_when_the_device_address_changes(self):
        s3 = pocket.connect("py-moved", home=self.home)
        s3.put_object(Bucket="shared", Key="a", Body=b"1")
        reg = self.srv.core.registry
        reg._con.execute("UPDATE devices SET ip='100.64.1.2' WHERE app_id='py-moved'")
        reg._changed()
        self.assertEqual(s3.get_object(Bucket="shared", Key="a")["Body"].read(), b"1")   # no error reaches the app
        self.assertEqual(reg.devices("py-moved")[0]["ip"], "127.0.0.1")
        self.assertEqual(self.enrolls("py-moved"), 2)

    def test_recovers_when_the_key_is_replaced(self):
        s3 = pocket.connect("py-rotated", home=self.home)
        s3.put_object(Bucket="shared", Key="a", Body=b"1")
        dev = self.srv.core.registry.devices("py-rotated")[0]
        new = self.srv.core.registry.rotate_key("py-rotated", dev["uid"])
        s3.put_object(Bucket="shared", Key="b", Body=b"2")
        saved = json.load(open(os.path.join(self.home, "apps", "py-rotated.json")))
        self.assertEqual(saved["access_key"], new["access_key"])
        self.assertEqual(s3.get_object(Bucket="shared", Key="b")["Body"].read(), b"2")
        # a fresh start reads the new key from disk and needs no further enrollment
        pocket.connect("py-rotated", home=self.home).get_object(Bucket="shared", Key="a")
        self.assertEqual(self.enrolls("py-rotated"), 2)

    def test_temporary_link(self):
        s3 = pocket.connect("py-link", home=self.home)
        s3.put_object(Bucket="shared", Key="report.txt", Body=b"by link")
        url = s3.generate_presigned_url("get_object", Params={"Bucket": "shared", "Key": "report.txt"}, ExpiresIn=60)
        self.assertIn("X-Amz-Signature", url)          # version 4 links
        self.assertEqual(urllib.request.urlopen(url).read(), b"by link")

    def test_environment_variables_for_ci(self):
        os.environ["POCKET_ENDPOINT"] = self.srv.endpoint
        os.environ["POCKET_ENROLL_SECRET"] = self.srv.core.settings.get("enroll_secret")
        os.environ["POCKET_DEVICE_ID"] = "github-actions"
        os.environ["POCKET_DEVICE_NAME"] = "GitHub Actions"
        os.environ["POCKET_HOME"] = tempfile.mkdtemp(prefix="pocket-ci-")
        try:
            pocket.connect("py-ci").put_object(Bucket="shared", Key="build.txt", Body=b"ok")
            devices = self.srv.core.registry.devices("py-ci")
            self.assertEqual([(d["uid"], d["name"]) for d in devices], [("github-actions", "GitHub Actions")])
        finally:
            shutil.rmtree(os.environ.pop("POCKET_HOME"), ignore_errors=True)
            for k in ("POCKET_ENDPOINT", "POCKET_ENROLL_SECRET", "POCKET_DEVICE_ID", "POCKET_DEVICE_NAME"):
                os.environ.pop(k, None)

    def test_clear_errors(self):
        empty = tempfile.mkdtemp()
        try:
            with self.assertRaises(pocket.PocketError) as e:
                pocket.connect("py-x", home=empty)
            self.assertIn("not set up", str(e.exception))
            with self.assertRaises(pocket.PocketError) as e:
                pocket.connect("py-x", home=self.home, enroll_secret="wrong")
            self.assertIn("refused", str(e.exception))
            with self.assertRaises(pocket.PocketError) as e:
                pocket.connect("py-x", home=self.home, endpoint="http://127.0.0.1:9")
            self.assertIn("can't be reached", str(e.exception))
        finally:
            shutil.rmtree(empty, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
