"""The control API (used by the web panel and the Android app), against the real server."""

import json
import os
import time
import unittest
import urllib.parse

import botocore

from helpers import GB, PocketServer


class PanelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = PocketServer().start()
        status, body = cls.srv.api("POST", "/api/setup", {"password": "correct horse"})
        assert status == 200, body
        cls.token = body["token"]

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def call(self, method, path, body=None, expect=200, **kw):
        status, out = self.srv.api(method, path, body, token=self.token, **kw)
        self.assertEqual(status, expect, out)
        return out

    def app(self, name, uid="device-0001", device_name="Desktop PC"):
        status, e = self.srv.enroll(name, uid, device_name)
        self.assertEqual(status, 200, e)
        return e, self.srv.client(e)

    # ---------- signing in ----------
    def test_sign_in(self):
        srv = self.srv
        self.assertEqual(srv.api("GET", "/api/status")[1]["password_set"], True)
        self.assertEqual(srv.api("POST", "/api/setup", {"password": "another one"})[0], 409, "set up only once")
        self.assertEqual(srv.api("GET", "/api/overview")[0], 401, "the API needs a token")
        self.assertEqual(srv.api("GET", "/api/overview", token="made-up")[0], 401)
        self.assertEqual(srv.api("POST", "/api/login", {"password": "wrong"})[0], 401)
        status, body = srv.api("POST", "/api/login", {"password": "correct horse", "device": "test"})
        self.assertEqual(status, 200)
        t = body["token"]
        self.assertEqual(srv.api("GET", "/api/overview", token=t)[0], 200)
        self.assertEqual(srv.api("POST", "/api/logout", {}, token=t)[0], 200)
        self.assertEqual(srv.api("GET", "/api/overview", token=t)[0], 401, "signed out")

    def test_too_many_password_attempts(self):
        srv = self.srv
        srv.core.admin._attempts.clear()
        codes = [srv.api("POST", "/api/login", {"password": "nope"})[0] for _ in range(12)]
        self.assertEqual(codes[:10], [401] * 10)
        self.assertEqual(codes[10:], [429, 429])
        srv.core.admin._attempts.clear()

    # ---------- pages ----------
    def test_every_page_answers(self):
        e, c = self.app("pages")
        c.put_object(Bucket="shared", Key="a.txt", Body=b"hello")
        c.get_object(Bucket="shared", Key="a.txt")["Body"].read()
        self.srv.core.stats.flush()
        ov = self.call("GET", "/api/overview")
        self.assertGreaterEqual(ov["traffic"]["requests"], 2)
        self.assertEqual(sum(ov["hourly"]["pages"]), 2)
        self.assertTrue({"Gateway", "Storage server (rclone)", "Control panel"} <= {s["name"] for s in ov["services"]})
        st = self.call("GET", "/api/storage")
        self.assertIn("pages", [a["id"] for a in st["apps"]])
        self.assertGreater(st["internal"]["total"], 0)
        apps = self.call("GET", "/api/apps")
        a = next(x for x in apps["apps"] if x["id"] == "pages")
        self.assertEqual((a["used"], a["requests"], a["devices"][0]["name"]), (5, 2, "Desktop PC"))
        d = self.call("GET", "/api/apps/pages")
        self.assertEqual(d["devices"][0]["key"][:6], e["access_key"][:6])
        self.assertNotIn(e["access_key"], json.dumps(d), "full keys are never shown")
        self.assertEqual(sum(d["series"]["writes"]), 1)
        self.assertEqual(sum(d["series"]["reads"]), 1)
        self.assertIn(e["device_id"], [x["uid"] for x in self.call("GET", "/api/devices")["devices"]])
        self.assertTrue(self.call("GET", "/api/jobs")["jobs"])
        self.assertTrue(self.call("GET", "/api/alerts")["rules"])
        self.assertNotIn("enroll_secret", self.call("GET", "/api/settings")["settings"])
        ops = {r["op"] for r in self.call("GET", "/api/logs?app=pages")["items"]}
        self.assertTrue({"PutObject", "GetObject"} <= ops, ops)
        self.assertTrue(any("pages" in l for l in self.call("GET", "/api/logs/system")["lines"]))
        self.assertEqual(self.call("GET", "/api/apps/nope", expect=404)["error"], "NoSuchApp")

    # ---------- apps ----------
    def test_app_settings_and_strict_allocation(self):
        _, c = self.app("conf")
        c.put_object(Bucket="shared", Key="x", Body=b"x" * 1000)
        d = self.call("PATCH", "/api/apps/conf", {"name": "Config test", "notes": "n", "warn_pct": 70, "at_limit": "warn",
                                                  "move_days": 3, "device_cap": 1 * GB})
        self.assertEqual((d["name"], d["warn_pct"], d["at_limit"], d["move_days"], d["device_cap"]),
                         ("Config test", 70, "warn", 3, 1 * GB))
        self.assertEqual(self.call("PATCH", "/api/apps/conf", {"alloc": d["alloc_max"] + GB}, expect=400)["error"], "PoolTooSmall")
        self.assertEqual(self.call("PATCH", "/api/apps/conf", {"alloc": 500}, expect=400)["error"], "BelowUse")
        self.assertEqual(self.call("PATCH", "/api/apps/conf", {"alloc": 2 * GB})["alloc"], 2 * GB)
        self.call("PATCH", "/api/apps/conf", {"warn_pct": 99}, expect=400)
        self.assertEqual(self.call("PATCH", "/api/apps/conf", {"device_reserve": GB})["device_reserve"], GB)
        self.call("PATCH", "/api/apps/conf", {"device_reserve": 3 * GB}, expect=400)     # more than allocation / devices
        self.assertIsNone(self.call("PATCH", "/api/apps/conf", {"device_reserve": None})["device_reserve"])
        self.call("PATCH", "/api/apps/conf", {"at_limit": "maybe"}, expect=400)

    def test_family_bucket_in_the_panel(self):
        apps = {a["id"]: a for a in self.call("GET", "/api/apps")["apps"]}
        self.assertTrue(apps["_family"]["is_family"])
        self.assertEqual(apps["_family"]["name"], "Family")
        _, c = self.app("member")
        self.assertFalse(self.call("GET", "/api/apps/member")["family"])
        self.assertTrue(self.call("PATCH", "/api/apps/member", {"family": True})["family"])
        self.assertFalse(self.call("PATCH", "/api/apps/member", {"family": False})["family"])
        self.assertEqual(self.call("DELETE", "/api/apps/_family", expect=400)["error"], "BadRequest")
        self.call("PATCH", "/api/apps/_family", {"family": True}, expect=400)
        listed = self.call("GET", "/api/apps")["apps"]
        self.assertEqual(self.call("GET", "/api/overview")["app_count"], sum(1 for a in listed if not a["is_family"]))

    def test_lan_setting(self):
        s = self.call("PATCH", "/api/settings", {"lan": {"enabled": True}})
        self.assertTrue(s["settings"]["lan"]["enabled"])
        self.assertIsNone(s["addresses"]["lan"])            # no Wi-Fi address known on the PC
        s = self.call("PATCH", "/api/settings", {"lan": {"enabled": False}})
        self.assertFalse(s["settings"]["lan"]["enabled"])

    def test_create_empty_and_delete_app(self):
        created = self.call("POST", "/api/apps", {"id": "handmade", "name": "Hand made"})
        self.assertEqual(created["name"], "Hand made")
        self.call("POST", "/api/apps", {"id": "handmade"}, expect=400)
        self.call("POST", "/api/apps", {"id": "Bad Name"}, expect=400)
        _, c = self.app("handmade")
        for k in ("a", "b/c"):
            c.put_object(Bucket="shared", Key=k, Body=b"data")
        c.put_object(Bucket="device", Key="p", Body=b"private")
        self.assertEqual(self.call("POST", "/api/apps/handmade/empty")["files"], 3)
        self.assertEqual(c.list_objects_v2(Bucket="shared").get("Contents", []), [])
        self.assertEqual(c.list_objects_v2(Bucket="device").get("Contents", []), [])
        undo_items = [u for u in self.call("GET", "/api/undo")["items"] if u["app"] == "handmade"]
        self.assertEqual(sorted(u["key"] for u in undo_items), ["a", "b/c", "p"])
        c.put_object(Bucket="shared", Key="again", Body=b"1")
        self.assertEqual(self.call("DELETE", "/api/apps/handmade")["files"], 1)
        self.call("GET", "/api/apps/handmade", expect=404)
        with self.assertRaises(botocore.exceptions.ClientError):
            c.list_objects_v2(Bucket="shared")

    def test_rotate_all_keys(self):
        e, c = self.app("rot")
        self.call("POST", "/api/apps/rot/rotate-keys")
        with self.assertRaises(botocore.exceptions.ClientError):
            c.list_objects_v2(Bucket="shared")

    # ---------- devices ----------
    def test_device_actions(self):
        e, c = self.app("devs", "device-00aa", "Old name")
        self.call("POST", "/api/devices/device-00aa/rename", {"name": "Galaxy S24 Ultra"})
        self.assertEqual(self.srv.core.registry.devices("devs")[0]["name"], "Galaxy S24 Ultra")
        self.call("POST", "/api/devices/device-00aa/block", {"blocked": True})
        with self.assertRaises(botocore.exceptions.ClientError):
            c.list_objects_v2(Bucket="shared")
        self.call("POST", "/api/devices/device-00aa/block", {"blocked": False})
        c.list_objects_v2(Bucket="shared")
        self.call("POST", "/api/apps/devs/devices/device-00aa/rotate-key")
        e2, c2 = self.app("devs", "device-00aa")
        self.assertNotEqual(e2["access_key"], e["access_key"])
        c2.put_object(Bucket="device", Key="mine", Body=b"x")
        self.call("DELETE", "/api/apps/devs/devices/device-00aa")
        self.assertEqual(self.srv.core.registry.devices("devs"), [])
        self.call("POST", "/api/devices/device-zzzz/rename", {"name": "x"}, expect=404)

    # ---------- files and undo ----------
    def test_files_browse_upload_download_delete_restore(self):
        e, c = self.app("files")
        c.put_object(Bucket="shared", Key="docs/report.txt", Body=b"v1")
        listing = self.call("GET", "/api/files?app=files&space=shared")
        self.assertEqual([(i["type"], i["name"], i["files"]) for i in listing["items"]], [("dir", "docs", 1)])
        inner = self.call("GET", "/api/files?app=files&space=shared&path=docs")
        self.assertEqual([(i["name"], i["size"], i["storage"]) for i in inner["items"]], [("report.txt", 2, "int")])
        q = urllib.parse.urlencode({"app": "files", "space": "shared", "key": "docs/new.bin"})
        out = self.call("PUT", "/api/files/upload?" + q, raw=b"uploaded in the panel",
                        headers={"Content-Type": "application/octet-stream"})
        self.assertEqual(out["size"], 21)
        self.assertEqual(c.get_object(Bucket="shared", Key="docs/new.bin")["Body"].read(), b"uploaded in the panel")
        dl = self.call("GET", "/api/files/download?" + urllib.parse.urlencode({"app": "files", "space": "shared", "key": "docs/report.txt"}))
        self.assertEqual(dl, b"v1")
        q = urllib.parse.urlencode({"app": "files", "space": "shared", "key": "docs/report.txt"})
        self.call("DELETE", "/api/files?" + q)
        with self.assertRaises(botocore.exceptions.ClientError):
            c.head_object(Bucket="shared", Key="docs/report.txt")          # gone for apps right away
        entry = next(u for u in self.call("GET", "/api/undo")["items"] if u["app"] == "files" and u["key"] == "docs/report.txt")
        self.assertEqual(entry["reason"], "deleted")
        self.call("POST", "/api/undo/restore", {"storage": entry["storage"], "path": entry["path"]})
        self.assertEqual(c.get_object(Bucket="shared", Key="docs/report.txt")["Body"].read(), b"v1")
        self.call("POST", "/api/undo/restore", {"storage": entry["storage"], "path": entry["path"]}, expect=404)
        self.call("POST", "/api/files/folder", {"app": "files", "space": "shared", "path": "empty-folder"})
        names = [i["name"] for i in self.call("GET", "/api/files?app=files&space=shared")["items"]]
        self.assertIn("empty-folder", names)
        dev = "device:" + e["device_id"]
        self.call("GET", "/api/files?" + urllib.parse.urlencode({"app": "files", "space": dev}))
        self.call("GET", "/api/files?app=files&space=device:nobody-000", expect=404)
        self.call("GET", "/api/files?app=files&space=shared&path=../../etc", expect=400)

    def test_upload_respects_allocation(self):
        self.app("full")
        core = self.srv.core
        core.registry.set_allocation("full", 1000, core.usage.pool()["total"], 0)
        q = urllib.parse.urlencode({"app": "full", "space": "shared", "key": "big"})
        out = self.call("PUT", "/api/files/upload?" + q, raw=b"x" * 5000, expect=403)
        self.assertEqual(out["error"], "QuotaExceeded")

    # ---------- jobs ----------
    def test_run_a_job_now(self):
        self.call("POST", "/api/jobs/undo-cleanup/run")
        self.call("POST", "/api/jobs/nope/run", expect=404)
        deadline = time.time() + 20
        while time.time() < deadline:
            last = next(j for j in self.call("GET", "/api/jobs")["jobs"] if j["key"] == "undo-cleanup")["last"]
            if last and last["result"]:
                break
            time.sleep(0.2)
        self.assertEqual(last["result"], "ok")
        self.call("POST", "/api/jobs/health/run")

    # ---------- alerts ----------
    def test_alert_switches_and_thresholds(self):
        out = self.call("PATCH", "/api/alerts", {"enabled": {"app_warn": False}, "thresholds": {"health.battery_low_pct": 25}})
        self.assertFalse(next(r for r in out["rules"] if r["kind"] == "app_warn")["enabled"])
        self.assertEqual(self.srv.core.settings.get("health.battery_low_pct"), 25)
        self.assertFalse(self.srv.core.stats.raise_alert("app_warn", "x", "warn", "switched off"))
        self.call("PATCH", "/api/alerts", {"enabled": {"app_warn": True}})
        self.call("PATCH", "/api/alerts", {"thresholds": {"health.battery_low_pct": 99}}, expect=400)
        self.call("PATCH", "/api/alerts", {"enabled": {"no_such": True}}, expect=400)

    # ---------- settings ----------
    def test_settings_validation_and_pool(self):
        s = self.call("PATCH", "/api/settings", {"new_apps": {"alloc_gb": 5}, "jobs": {"nightly_time": "2:30", "undo_days": 20}})
        self.assertEqual((s["settings"]["new_apps"]["alloc_gb"], s["settings"]["jobs"]["nightly_time"]), (5, "02:30"))
        self.call("PATCH", "/api/settings", {"jobs": {"nightly_time": "25:00"}}, expect=400)
        self.call("PATCH", "/api/settings", {"storage_port": 1}, expect=400)
        self.call("PATCH", "/api/settings", {"health": {"every_min": 0}}, expect=400)
        promised = s["pool"]["promised"]
        tiny = (promised / GB) / 2
        out = self.call("PATCH", "/api/settings", {"pool": {"internal_gb": tiny / 2, "sd_gb": tiny / 2}}, expect=400)
        self.assertIn(out["error"], ("PoolTooSmall", "BadRequest"))
        sd_max = int(s["pool"]["sd_max"] // GB)            # what this disk can actually hold
        self.call("PATCH", "/api/settings", {"pool": {"sd_gb": sd_max + 5}}, expect=400)
        if s["pool"]["int_limit"] + sd_max * GB >= promised:
            ok = self.call("PATCH", "/api/settings", {"pool": {"sd_gb": sd_max}})
            self.assertEqual(ok["settings"]["pool"]["sd_gb"], sd_max)
        else:                 # this small disk can't hold what earlier tests promised: strictness must say so
            self.assertEqual(self.call("PATCH", "/api/settings", {"pool": {"sd_gb": sd_max}}, expect=400)["error"], "PoolTooSmall")
        self.call("PATCH", "/api/settings", {"new_apps": {"alloc_gb": 3}, "jobs": {"nightly_time": "03:00", "undo_days": 14}})
        self.srv.core.settings.update({"pool": {"sd_gb": 200}})

    def test_read_only_switch_applies(self):
        _, c = self.app("ros")
        self.call("PATCH", "/api/settings", {"server": {"read_only": True}})
        try:
            with self.assertRaises(botocore.exceptions.ClientError):
                c.put_object(Bucket="shared", Key="x", Body=b"1")
        finally:
            self.call("PATCH", "/api/settings", {"server": {"read_only": False}})
        time.sleep(1.5)                    # the storage server restarts in the background
        self.srv.client(self.srv.enroll("ros", "device-0001")[1], retries=5).put_object(Bucket="shared", Key="x", Body=b"1")

    def test_enrollment_secret_rotation(self):
        old = self.call("GET", "/api/settings/enroll-secret")["enroll_secret"]
        self.assertIn(old, self.call("GET", "/api/settings/enroll-secret")["machine_config"])
        new = self.call("POST", "/api/settings/enroll-secret/rotate")["enroll_secret"]
        self.assertNotEqual(old, new)
        self.assertEqual(self.srv.enroll("after-rotate", "device-0001", secret=old)[0], 403)
        self.assertEqual(self.srv.enroll("after-rotate", "device-0001", secret=new)[0], 200)

    def test_backup_and_restore(self):
        self.app("bk")
        backup = self.call("GET", "/api/settings/backup")
        self.assertEqual(backup["kind"], "pocket-cloud-backup")
        self.call("DELETE", "/api/apps/bk")
        out = self.call("POST", "/api/settings/restore", backup)
        self.assertGreaterEqual(out["apps"], 1)
        self.assertIsNotNone(self.srv.core.registry.app("bk"))
        self.call("POST", "/api/settings/restore", {"kind": "something else"}, expect=400)

    def test_password_change(self):
        out = self.call("POST", "/api/password", {"old": "correct horse", "new": "battery staple"})
        self.assertEqual(self.srv.api("GET", "/api/overview", token=self.token)[0], 401, "a new password signs everyone out")
        type(self).token = out["token"]
        self.call("POST", "/api/password", {"old": "battery staple", "new": "short"}, expect=400)
        out = self.call("POST", "/api/password", {"old": "battery staple", "new": "correct horse"})
        type(self).token = out["token"]

    def test_many_requests_on_one_connection(self):
        """Browsers reuse connections: every request's content must be read, or the next request is garbled."""
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", self.srv.overrides["panel_port"], timeout=10)
        auth = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}

        def call(method, path, body=None):
            conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=auth)
            r = conn.getresponse()
            data = r.read()
            return r.status, (json.loads(data) if data else None), r.getheader("Connection")

        self.app("keepalive")
        self.assertEqual(call("PATCH", "/api/apps/keepalive", {"notes": "one"})[0], 200)
        self.assertEqual(call("PATCH", "/api/apps/keepalive", {"notes": "two"})[0], 200)
        self.assertEqual(call("POST", "/api/devices/device-0001/rename", {"name": "Desktop PC"})[0], 200)
        self.assertEqual(call("GET", "/api/apps/keepalive")[1]["notes"], "two")
        # refused before its content is read: the server must close rather than misread what follows
        status, _, connection = call("POST", "/api/no-such-thing", {"x": "y" * 1000})
        self.assertEqual((status, connection), (404, "close"))
        conn.close()
        conn = http.client.HTTPConnection("127.0.0.1", self.srv.overrides["panel_port"], timeout=10)
        self.assertEqual(call("GET", "/api/apps/keepalive")[0], 200)

    def test_web_panel_files(self):
        status, body = self.srv.api("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn(b"Pocket Cloud", body)


if __name__ == "__main__":
    unittest.main()
