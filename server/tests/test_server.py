"""Integration tests: the real gateway in front of real rclone, used through boto3 like an app would."""

import hashlib
import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

import botocore
from boto3.s3.transfer import TransferConfig

from helpers import GB, PocketServer


def code(e):
    return e.exception.response["Error"]["Code"], e.exception.response["ResponseMetadata"]["HTTPStatusCode"]


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = PocketServer().start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def app(self, name, uid="device-0001", device_name="Desktop PC"):
        status, e = self.srv.enroll(name, uid, device_name)
        self.assertEqual(status, 200, e)
        return e, self.srv.client(e)

    # ---------- enrollment ----------
    def test_enrollment(self):
        status, body = self.srv.enroll("enr", "device-0001", "PC", secret="wrong")
        self.assertEqual((status, body["error"]), (403, "Forbidden"))
        status, body = self.srv.enroll("Not Valid", "device-0001", "PC")
        self.assertEqual((status, body["error"]), (400, "BadAppId"))
        e1, c1 = self.app("enr")
        self.assertEqual(e1["allocation_bytes"], 3 * GB)
        self.assertEqual(e1["buckets"], ["shared", "device"])
        e1b, _ = self.app("enr")
        self.assertEqual((e1b["access_key"], e1b["secret_key"]), (e1["access_key"], e1["secret_key"]))
        e2, _ = self.app("enr", uid="device-0002", device_name="Laptop")
        self.assertNotEqual(e2["access_key"], e1["access_key"])
        active = [a["kind"] for a in self.srv.core.stats.query("SELECT kind FROM alerts WHERE subject LIKE 'enr%'")]
        self.assertIn("new_app", active)
        self.assertIn("new_device", active)

    def test_quota_endpoint(self):
        e, c = self.app("quo")
        core = self.srv.core
        core.registry.set_allocation("quo", 1_000_000, core.usage.pool()["total"], 0)
        core.registry.update_app("quo", device_cap_bytes=400_000)
        c.put_object(Bucket="shared", Key="a", Body=b"x" * 100_000)
        c.put_object(Bucket="device", Key="b", Body=b"x" * 50_000)
        status, q = self.srv.pocket(e, "GET", "/_pocket/quota")
        self.assertEqual(status, 200, q)
        self.assertEqual((q["app"], q["device_id"], q["allocation"], q["used_app"], q["used_device"], q["device_cap"]),
                         ("quo", "device-0001", 1_000_000, 150_000, 50_000, 400_000))
        self.assertEqual((q["free"], q["free_shared"], q["reserve"], q["held_for_others"]), (350_000, 850_000, 0, 0))
        bad = dict(e, secret_key="wrong")
        status, q = self.srv.pocket(bad, "GET", "/_pocket/quota")
        self.assertEqual((status, q["error"]), (403, "SignatureDoesNotMatch"))
        status, q = self.srv.pocket(dict(e, access_key="PKNOBODY"), "GET", "/_pocket/quota")
        self.assertEqual((status, q["error"]), (403, "InvalidAccessKeyId"))
        status, q = self.srv.pocket(e, "GET", "/_pocket/nothing")
        self.assertEqual(status, 404)

    def test_conflicts_endpoint(self):
        """Names differing only in case can't coexist on this PC's own disk (Windows), so the walk is faked:
        the phone's internal storage is case-sensitive and holds such files for real."""
        e, c = self.app("case")
        core = self.srv.core
        fake = {core.layout.space_path("int", "case", "shared"): ["Docs/A.txt", "docs/b.txt", "x/Name.csv", "x/name.csv", "fine.txt"],
                core.layout.space_path("int", "case", "device:device-0001"): ["Same", "same"]}
        from pocket import server as srvmod
        real = srvmod.walk_files
        srvmod.walk_files = lambda base: [(os.path.join(base, *k.split("/")), None) for k in fake.get(base, [])]
        try:
            status, out = self.srv.pocket(e, "GET", "/_pocket/conflicts")
        finally:
            srvmod.walk_files = real
        self.assertEqual(status, 200, out)
        self.assertEqual(out["conflicts"], [
            {"bucket": "shared", "type": "folder", "names": ["Docs", "docs"]},
            {"bucket": "shared", "type": "file", "names": ["x/Name.csv", "x/name.csv"]},
            {"bucket": "device", "type": "file", "names": ["Same", "same"]},
        ])

    def test_mirror_report(self):
        e, c = self.app("mirror")
        status, out = self.srv.pocket(e, "POST", "/_pocket/mirror-report", body={"note": "rclone sync ok"})
        self.assertEqual((status, out["ok"]), (200, True))
        m = self.srv.core.stats.last_mirror()
        self.assertEqual((m["app"], m["device"], m["note"]), ("mirror", "device-0001", "rclone sync ok"))
        status, out = self.srv.pocket(e, "POST", "/_pocket/mirror-report", body=[1, 2])
        self.assertEqual((status, out["error"]), (400, "BadRequest"))

    def test_push_messages(self):
        """Writes and deletes give one debounced message per app, delivered to subscribers of its topic;
        enrollment tells apps where to listen. The server speaks ntfy's subscribe/publish protocol."""
        import http.client
        from helpers import free_port
        core = self.srv.core
        port = free_port()
        try:
            status, e = self.srv.enroll("push", "device-0001", "PC")
            self.assertIsNone(e["push"])
            core.settings.update({"push": {"enabled": True, "port": port}})
            core.push.apply()
            core.push.debounce_s = 0.3
            status, e = self.srv.enroll("push", "device-0001", "PC")
            self.assertEqual(e["push"], {"url": f"http://127.0.0.1:{port}", "topic": "pocket-push"})
            self.assertTrue(core.push.healthy())

            def get(path):
                with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=10) as r:
                    return r.status, r.read().decode()

            self.assertEqual(json.loads(get("/v1/health")[1]), {"healthy": True})
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                get("/nothing/here/at/all")
            self.assertEqual(ctx.exception.code, 404)

            # a subscriber with an open stream
            events = []
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            conn.request("GET", "/pocket-push/json")
            resp = conn.getresponse()
            threading.Thread(target=lambda: [events.append(json.loads(l)) for l in resp if l.strip()], daemon=True).start()

            def wait(n):
                end = time.time() + 5
                while len(events) < n and time.time() < end:
                    time.sleep(0.05)
                return events

            self.assertEqual(wait(1)[0]["event"], "open")
            c = self.srv.client(e)
            c.put_object(Bucket="shared", Key="a", Body=b"1")
            c.put_object(Bucket="shared", Key="b", Body=b"2")
            c.delete_object(Bucket="shared", Key="a")
            first = wait(2)[1]
            self.assertEqual((first["event"], first["topic"], first["title"]), ("message", "pocket-push", "push changed"))
            self.assertEqual(json.loads(first["message"])["changes"], 3, "one message for the burst")
            c.get_object(Bucket="shared", Key="b")           # reads are not changes
            c.delete_objects(Bucket="shared", Delete={"Objects": [{"Key": "b"}]})
            second = wait(3)[2]
            self.assertEqual(json.loads(second["message"])["changes"], 1)
            self.assertEqual(len(events), 3, events)

            # late subscribers: cached messages by poll, by id, by duration; other apps' topics stay separate
            polled = [json.loads(l) for l in get("/pocket-push/json?poll=1")[1].splitlines()]
            self.assertEqual([m["id"] for m in polled], [first["id"], second["id"]])
            after = [json.loads(l) for l in get(f"/pocket-push/json?poll=1&since={first['id']}")[1].splitlines()]
            self.assertEqual([m["id"] for m in after], [second["id"]])
            self.assertEqual(len(get("/pocket-push/json?poll=1&since=10m")[1].splitlines()), 2)
            self.assertEqual(get("/pocket-push/json?poll=1&since=none")[1], "")
            self.assertEqual(get("/pocket-other/json?poll=1")[1], "")
            self.assertIn("event: message", get("/pocket-push/sse?poll=1")[1])
            self.assertEqual(len(get("/pocket-push/raw?poll=1")[1].splitlines()), 2)

            # publishing over HTTP, like ntfy's own clients do
            req = urllib.request.Request(f"http://127.0.0.1:{port}/pocket-push", data=b"hello", method="POST", headers={"Title": "Hi"})
            with urllib.request.urlopen(req, timeout=10) as r:
                self.assertEqual(json.loads(r.read())["message"], "hello")
            self.assertEqual(wait(4)[3]["message"], "hello")
            checks = dict((n, ok) for n, ok, _ in core.health.run())
            self.assertTrue(checks["Push server"])
        finally:
            core.settings.update({"push": {"enabled": False, "port": 2586}})
            core.push.apply()                                # closes every open stream
        self.assertFalse(core.push.alive())
        with self.assertRaises(OSError):
            urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=2)

    def test_tunnel_waits_with_growing_delay_when_cloudflared_keeps_dying(self):
        """A cloudflared that dies at once is not restarted every few seconds; one that ran for a while is."""
        import sys as _sys
        core = self.srv.core
        t = core.tunnel
        # python stands in for cloudflared: "python tunnel --no-autoupdate ..." fails at once
        core.settings.update({"share": {"enabled": True, "cloudflared": _sys.executable}})

        def log_lines():
            return [l for l in open(os.path.join(self.srv.state, "logs", "pocket.log"), encoding="utf-8") if "cloudflared stopped" in l]

        def died():
            end = time.time() + 10
            while t.alive() and time.time() < end:
                time.sleep(0.05)
            self.assertFalse(t.alive())

        try:
            self.assertTrue(t.start())
            died()
            for _ in range(5):                               # the scheduler calls this every 2 s
                t.ensure_running()
            self.assertEqual((t.restarts, t._delay, len(log_lines())), (0, 30.0, 1), "waits, and says so once")
            t._next_try = 0
            t.ensure_running()                               # the wait is over: one more try
            self.assertEqual(t.restarts, 1)
            died()
            for _ in range(5):
                t.ensure_running()
            self.assertEqual((t.restarts, t._delay, len(log_lines())), (1, 60.0, 2), "the wait doubles")
            t._started -= 500                                # this one "ran" for a while before dying
            t._next_try = 0
            t.ensure_running()
            self.assertEqual(t.restarts, 2)
            died()
            t._started -= 500
            t.ensure_running()
            self.assertEqual((t.restarts, t._delay), (3, 0.0), "restarted at once, the wait reset")
        finally:
            core.settings.update({"share": {"enabled": False, "cloudflared": ""}})
            t.stop()

    def test_push_waits_with_growing_delay_when_it_cannot_listen(self):
        import socket
        from helpers import free_port
        core = self.srv.core
        blocker = socket.socket()
        blocker.bind(("127.0.0.1", 0))
        blocker.listen(1)
        port = blocker.getsockname()[1]
        try:
            core.settings.update({"push": {"enabled": True, "port": port}})
            core.push.apply()
            self.assertFalse(core.push.alive())
            first = core.push._delay
            self.assertEqual(first, 30.0)
            core.push.ensure_running()                       # too early: nothing is tried
            self.assertEqual(core.push.restarts, 0)
            core.push._next_try = 0
            core.push.ensure_running()
            self.assertEqual((core.push.restarts, core.push._delay), (1, 60.0))
            lines = [l for l in open(os.path.join(self.srv.state, "logs", "pocket.log"), encoding="utf-8") if "push: can't listen" in l]
            self.assertEqual(len(lines), 1, "logged once, not at every attempt")
            blocker.close()
            core.push._next_try = 0
            core.push.ensure_running()
            self.assertTrue(core.push.alive())
            self.assertEqual(core.push._delay, 0.0)
        finally:
            blocker.close()
            core.settings.update({"push": {"enabled": False, "port": 2586}})
            core.push.apply()

    def test_share_links(self):
        from helpers import free_port
        port = free_port()
        srv = PocketServer(share={"enabled": True, "port": port}).start()
        try:
            status, e = srv.enroll("shr", "device-0001", "PC")
            c = srv.client(e)
            c.put_object(Bucket="shared", Key="docs/report.pdf", Body=b"%PDF-" + b"x" * 5000)
            c.put_object(Bucket="device", Key="secret.txt", Body=b"mine")
            base = f"http://127.0.0.1:{port}"

            status, sh = srv.pocket(e, "POST", "/_pocket/share", body={"bucket": "shared", "key": "docs/report.pdf", "expires_s": 3600})
            self.assertEqual(status, 200, sh)
            self.assertEqual((sh["url"], sh["public"], sh["has_password"]), (f"{base}/s/{sh['token']}", False, False))
            with urllib.request.urlopen(sh["url"], timeout=5) as r:
                self.assertEqual(r.status, 200)
                self.assertEqual(r.headers["Content-Type"], "application/pdf")
                self.assertIn("report.pdf", r.headers["Content-Disposition"])
                self.assertEqual(len(r.read()), 5005)
            req = urllib.request.Request(sh["url"], headers={"Range": "bytes=0-4"})
            with urllib.request.urlopen(req, timeout=5) as r:
                self.assertEqual((r.status, r.read(), r.headers["Content-Range"]), (206, b"%PDF-", "bytes 0-4/5005"))
            status, lst = srv.pocket(e, "GET", "/_pocket/shares")
            # the whole file and a range starting at 0 (how download managers begin) count as downloads
            self.assertEqual((len(lst["shares"]), lst["shares"][0]["downloads"], lst["shares"][0]["key"]), (1, 2, "docs/report.pdf"))

            # nothing else is reachable through the listener
            for p in ("/", "/s/", "/shared/docs/report.pdf", "/_pocket/health", "/api/overview", "/s/nope"):
                with self.assertRaises(urllib.error.HTTPError) as ctx:
                    urllib.request.urlopen(base + p, timeout=5)
                self.assertEqual(ctx.exception.code, 404, p)

            # password + download limit
            status, sh2 = srv.pocket(e, "POST", "/_pocket/share", body={"bucket": "device", "key": "secret.txt", "password": "open sesame", "max_downloads": 1})
            self.assertEqual(status, 200, sh2)
            with urllib.request.urlopen(sh2["url"], timeout=5) as r:
                self.assertIn(b"needs a password", r.read())
            data = urllib.parse.urlencode({"password": "wrong"}).encode()
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(urllib.request.Request(sh2["url"], data=data, method="POST"), timeout=5)
            self.assertEqual(ctx.exception.code, 403)
            data = urllib.parse.urlencode({"password": "open sesame"}).encode()
            with urllib.request.urlopen(urllib.request.Request(sh2["url"], data=data, method="POST"), timeout=5) as r:
                self.assertEqual(r.read(), b"mine")
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(urllib.request.Request(sh2["url"], data=data, method="POST"), timeout=5)
            self.assertEqual(ctx.exception.code, 410, "used up")

            # expiry, revoke, validation
            srv.core.registry._con.execute("UPDATE shares SET expires=? WHERE token=?", (time.time() - 1, sh["token"]))
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(sh["url"], timeout=5)
            self.assertEqual(ctx.exception.code, 410)
            status, out = srv.pocket(e, "DELETE", "/_pocket/share", query="token=" + sh2["token"])
            self.assertEqual(status, 200, out)
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(sh2["url"], timeout=5)
            self.assertEqual(ctx.exception.code, 404)
            status, out = srv.pocket(e, "POST", "/_pocket/share", body={"bucket": "shared", "key": "missing.txt"})
            self.assertEqual((status, out["error"]), (404, "NoSuchKey"))
            status, out = srv.pocket(e, "POST", "/_pocket/share", body={"bucket": "shared", "key": "docs/report.pdf", "expires_s": 10 ** 9})
            self.assertEqual(status, 400)
            srv.core.settings.update({"share": {"enabled": False}})
            srv.core.set_share(False)
            status, out = srv.pocket(e, "POST", "/_pocket/share", body={"bucket": "shared", "key": "docs/report.pdf"})
            self.assertEqual((status, out["error"]), (403, "SharesOff"))
            with self.assertRaises(OSError):
                urllib.request.urlopen(base + "/s/x", timeout=2)
        finally:
            srv.stop()

    def test_family_bucket(self):
        """Apps in the family share a third bucket: the family's own allocation, usage and undo."""
        core = self.srv.core
        e1, c1 = self.app("fam-a")
        e2, c2 = self.app("fam-b", uid="device-0002", device_name="Laptop")
        e3, c3 = self.app("fam-c", uid="device-0003", device_name="Other")
        self.assertEqual(e1["buckets"], ["shared", "device"])
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c1.put_object(Bucket="family", Key="list.txt", Body=b"milk")
        self.assertEqual(code(ctx), ("NoSuchBucket", 404))
        core.registry.update_app("fam-a", family=1)
        core.registry.update_app("fam-b", family=1)
        core.restart_rclone_drained()                 # as the control panel does when membership changes
        e1, c1 = self.app("fam-a")
        self.assertEqual(e1["buckets"], ["shared", "device", "family"])
        c1.put_object(Bucket="family", Key="list.txt", Body=b"milk")
        self.assertEqual(c2.get_object(Bucket="family", Key="list.txt")["Body"].read(), b"milk")
        self.assertIn("family", [b["Name"] for b in c2.list_buckets()["Buckets"]])
        self.assertNotIn("family", [b["Name"] for b in c3.list_buckets()["Buckets"]])
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c3.get_object(Bucket="family", Key="list.txt")
        self.assertEqual(code(ctx), ("NoSuchBucket", 404))
        self.assertEqual((core.usage.app_used("_family"), core.usage.app_used("fam-a")), (4, 0))
        self.assertTrue(os.path.isfile(self.srv.files("int", "apps", "_family", "shared", "list.txt")))
        core.registry.set_allocation("_family", 100, core.usage.pool()["total"], 4)
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c2.put_object(Bucket="family", Key="big", Body=b"x" * 200)
        self.assertEqual(code(ctx), ("QuotaExceeded", 403))
        c2.delete_object(Bucket="family", Key="list.txt")
        kept = [e for e in self._undo_index("_family") if e["key"] == "list.txt"]
        self.assertEqual((len(kept), kept[0]["reason"], kept[0]["device"]), (1, "deleted", "device-0002"))

    def test_second_device_address(self):
        """A device may be bound to two addresses (Tailscale and LAN); its key works from either."""
        status, e = self.srv.enroll("two", "device-0001", "PC")
        self.assertEqual(e["endpoints"], {"tailscale": e["endpoint"], "lan": None})
        body = json.dumps({"secret": self.srv.core.settings.get("enroll_secret"), "app": "two", "device_id": "device-0001",
                           "device_name": "PC", "addresses": ["127.0.0.1", "fe80::1", "8.8.8.8", "100.64.0.9", "192.168.1.5"]}).encode()
        req = urllib.request.Request(self.srv.endpoint + "/_pocket/enroll", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            e = json.loads(r.read())
        reg = self.srv.core.registry
        dev = reg.device_by_key(e["access_key"])
        self.assertEqual((dev["ip"], dev["ip_alt"]), ("127.0.0.1", "100.64.0.9"))
        c = self.srv.client(e)
        reg._con.execute("UPDATE devices SET ip='100.64.0.9', ip_alt='127.0.0.1' WHERE access_key=?", (e["access_key"],))
        reg._changed()
        c.put_object(Bucket="shared", Key="from-second-address", Body=b"1")
        reg._con.execute("UPDATE devices SET ip_alt=NULL WHERE access_key=?", (e["access_key"],))
        reg._changed()
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.get_object(Bucket="shared", Key="from-second-address")
        self.assertEqual(code(ctx), ("AccessDenied", 403))

    def test_lan_listener(self):
        """With lan.enabled the gateway also answers on the LAN address; switching it off stops that."""
        srv = PocketServer(lan_address="127.0.0.2", lan={"enabled": True}).start()
        try:
            lan = f"http://127.0.0.2:{srv.port}"
            with urllib.request.urlopen(lan + "/_pocket/health", timeout=5) as r:
                self.assertEqual(json.loads(r.read())["ok"], True)
            status, e = srv.enroll("lan", "device-0001", "PC")
            self.assertEqual(e["endpoints"]["lan"], lan)
            srv.core.set_lan(False)
            with self.assertRaises(OSError):
                urllib.request.urlopen(lan + "/_pocket/health", timeout=2)
            with urllib.request.urlopen(srv.endpoint + "/_pocket/health", timeout=5) as r:
                self.assertEqual(r.status, 200)
            srv.core.set_lan(True)
            srv.core.wait_listening()
            with urllib.request.urlopen(lan + "/_pocket/health", timeout=5) as r:
                self.assertEqual(r.status, 200)
        finally:
            srv.stop()

    def test_key_only_works_from_its_device_address(self):
        e, c = self.app("addr")
        c.put_object(Bucket="shared", Key="x", Body=b"1")
        reg = self.srv.core.registry
        reg._con.execute("UPDATE devices SET ip='100.64.0.9' WHERE access_key=?", (e["access_key"],))
        reg._changed()
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.get_object(Bucket="shared", Key="x")
        self.assertEqual(code(ctx), ("AccessDenied", 403))
        self.assertEqual(ctx.exception.response["ResponseMetadata"]["HTTPHeaders"].get("x-pocket-error"),
                         "device-address-changed")
        e2, c2 = self.app("addr")                       # enrolling again moves the key to this address
        self.assertEqual(e2["access_key"], e["access_key"])
        self.assertEqual(c2.get_object(Bucket="shared", Key="x")["Body"].read(), b"1")

    def test_unknown_and_wrong_keys(self):
        e, c = self.app("keys")
        bad = self.srv.client({**e, "secret_key": "not-the-secret"})
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            bad.list_objects_v2(Bucket="shared")
        self.assertEqual(code(ctx)[1], 403)
        nobody = self.srv.client({**e, "access_key": "PKNOBODY"})
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            nobody.list_objects_v2(Bucket="shared")
        self.assertEqual(code(ctx), ("InvalidAccessKeyId", 403))
        with self.assertRaises(urllib.error.HTTPError) as h:
            urllib.request.urlopen(self.srv.endpoint + "/shared/x")
        self.assertEqual(h.exception.code, 403)

    # ---------- isolation and buckets ----------
    def test_spaces_are_isolated(self):
        _, a1 = self.app("iso", "device-000a", "A")
        _, a2 = self.app("iso", "device-000b", "B")
        _, other = self.app("iso-other")
        a1.put_object(Bucket="shared", Key="common.txt", Body=b"for everyone")
        a1.put_object(Bucket="device", Key="mine.txt", Body=b"a1 only")
        self.assertEqual(a2.get_object(Bucket="shared", Key="common.txt")["Body"].read(), b"for everyone")
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            a2.get_object(Bucket="device", Key="mine.txt")
        self.assertEqual(code(ctx)[1], 404)
        self.assertEqual(a2.list_objects_v2(Bucket="device").get("Contents", []), [])
        self.assertEqual(other.list_objects_v2(Bucket="shared").get("Contents", []), [])
        self.assertTrue(os.path.isfile(self.srv.files("int", "apps", "iso", "devices", "device-000a", "mine.txt")))

    def test_buckets(self):
        _, c = self.app("bkt")
        self.assertEqual(sorted(b["Name"] for b in c.list_buckets()["Buckets"]), ["device", "shared"])
        c.create_bucket(Bucket="shared")              # apps often do this on startup: must just work
        c.create_bucket(Bucket="device")
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.create_bucket(Bucket="photos")
        self.assertEqual(code(ctx), ("InvalidBucketName", 400))
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.put_object(Bucket="photos", Key="a", Body=b"")
        self.assertEqual(code(ctx), ("NoSuchBucket", 404))
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.delete_bucket(Bucket="shared")
        self.assertEqual(code(ctx)[1], 403)
        bad = self.srv.client({**self.srv.enroll("bkt", "device-0001")[1], "secret_key": "wrong"})
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            bad.create_bucket(Bucket="shared")        # still needs a good signature
        self.assertEqual(code(ctx)[1], 403)

    # ---------- undo ----------
    def test_undo_keeps_deleted_and_overwritten_versions(self):
        _, c = self.app("und")
        c.put_object(Bucket="shared", Key="doc/a.txt", Body=b"v1")
        c.put_object(Bucket="shared", Key="doc/a.txt", Body=b"v2")
        c.delete_object(Bucket="shared", Key="doc/a.txt")
        with self.assertRaises(botocore.exceptions.ClientError):
            c.head_object(Bucket="shared", Key="doc/a.txt")
        index = self._undo_index("und")
        self.assertEqual([(i["key"], i["reason"]) for i in index], [("doc/a.txt", "overwritten"), ("doc/a.txt", "deleted")])
        kept = [open(os.path.join(self.srv.int_root, "_undo", i["path"]), "rb").read() for i in index]
        self.assertEqual(kept, [b"v1", b"v2"])

    def test_batch_delete_and_refused_delete(self):
        _, c = self.app("bdel")
        for k in ("a", "b", "c"):
            c.put_object(Bucket="shared", Key=k, Body=k.encode())
        r = c.delete_objects(Bucket="shared", Delete={"Objects": [{"Key": "a"}, {"Key": "b"}], "Quiet": True})
        self.assertFalse(r.get("Errors"))
        self.assertEqual([o["Key"] for o in c.list_objects_v2(Bucket="shared")["Contents"]], ["c"])
        self.assertEqual(sorted(i["key"] for i in self._undo_index("bdel")), ["a", "b"])
        e = self.srv.enroll("bdel", "device-0001")[1]
        bad = self.srv.client({**e, "secret_key": "wrong"})
        with self.assertRaises(botocore.exceptions.ClientError):
            bad.delete_object(Bucket="shared", Key="c")
        self.assertEqual(c.get_object(Bucket="shared", Key="c")["Body"].read(), b"c")
        undo_files = [f for _, _, fs in os.walk(os.path.join(self.srv.int_root, "_undo")) for f in fs if f.startswith("c~")]
        self.assertEqual(undo_files, [], "a refused delete must not leave an undo copy")

    def test_undo_works_without_hard_links(self):
        """Python on the phone has no os.link, and the SD card refuses hard links: the undo copy is a plain copy
        that keeps the file's modification time, and deleting or overwriting must still work."""
        e, c = self.app("nolink")
        real = os.link

        def refused(*a, **k):
            raise OSError(1, "Operation not permitted")

        try:
            for case, broken in (("no os.link", None), ("hard links refused", refused)):
                key = "doc-" + case.split()[0] + ".txt"
                if broken is None:
                    del os.link
                else:
                    os.link = broken
                c.put_object(Bucket="shared", Key=key, Body=b"v1", Metadata={"mtime": "1700000000.0"})
                c.put_object(Bucket="shared", Key=key, Body=b"v2")                  # overwrite
                c.delete_object(Bucket="shared", Key=key)                           # delete
                entries = [x for x in self._undo_index("nolink") if x["key"] == key]
                self.assertEqual([x["reason"] for x in entries], ["overwritten", "deleted"], case)
                first = os.path.join(self.srv.int_root, "_undo", entries[0]["path"])
                second = os.path.join(self.srv.int_root, "_undo", entries[1]["path"])
                self.assertEqual(open(first, "rb").read(), b"v1", case)
                self.assertEqual(open(second, "rb").read(), b"v2", case)
                self.assertEqual(os.stat(first).st_mtime, 1700000000.0, case)
                self.assertEqual(os.stat(first).st_nlink, 1, "a copy, not a link")
                with self.assertRaises(botocore.exceptions.ClientError) as ctx:
                    c.get_object(Bucket="shared", Key=key)
                self.assertEqual(code(ctx), ("NoSuchKey", 404))
        finally:
            os.link = real

    def _undo_index(self, app):
        out = []
        root = os.path.join(self.srv.int_root, "_undo")
        for day in os.listdir(root):
            p = os.path.join(root, day, "index.jsonl")
            if os.path.exists(p):
                out += [json.loads(l) for l in open(p, encoding="utf-8") if json.loads(l)["app"] == app]
        return out

    # ---------- allocations and the pool ----------
    def test_allocation_refuse_and_warn(self):
        e, c = self.app("quota")
        core = self.srv.core
        core.registry.set_allocation("quota", 1024 * 1024, core.usage.pool()["total"], 0)
        c.put_object(Bucket="shared", Key="half", Body=b"x" * 600_000)
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.put_object(Bucket="shared", Key="too-much", Body=b"x" * 600_000)
        self.assertEqual(code(ctx), ("QuotaExceeded", 403))
        core.registry.update_app("quota", at_limit="warn")
        c.put_object(Bucket="shared", Key="too-much", Body=b"x" * 600_000)
        core.stats.flush()
        notes = [r["note"] for r in core.stats.query("SELECT note FROM requests WHERE app='quota' AND key='too-much'")]
        self.assertIn("over allocation (warn only)", notes)

    def test_device_cap(self):
        _, c = self.app("cap")
        self.srv.core.registry.update_app("cap", device_cap_bytes=100_000)
        c.put_object(Bucket="shared", Key="big-shared", Body=b"x" * 200_000)      # the cap is only for device spaces
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c.put_object(Bucket="device", Key="big-private", Body=b"x" * 200_000)
        self.assertEqual(code(ctx), ("QuotaExceeded", 403))

    def test_device_reserve(self):
        """Each device keeps room up to the reserve, whatever the other devices used."""
        e1, c1 = self.app("res")
        e2, c2 = self.app("res", uid="device-0002", device_name="Laptop")
        core = self.srv.core
        core.registry.set_allocation("res", 1_000_000, core.usage.pool()["total"], 0)
        core.registry.update_app("res", device_reserve_bytes=300_000)
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c1.put_object(Bucket="shared", Key="a", Body=b"x" * 450_000)    # shared must leave both reserves (600 000)
        self.assertEqual(code(ctx), ("QuotaExceeded", 403))
        c1.put_object(Bucket="shared", Key="a", Body=b"x" * 350_000)
        c1.put_object(Bucket="device", Key="b", Body=b"x" * 350_000)        # its own share plus the unreserved rest
        with self.assertRaises(botocore.exceptions.ClientError) as ctx:
            c1.put_object(Bucket="device", Key="c", Body=b"x" * 10_000)     # the last 300 000 are the laptop's
        self.assertEqual(code(ctx), ("QuotaExceeded", 403))
        c2.put_object(Bucket="device", Key="mine", Body=b"x" * 250_000)     # the laptop still can
        core.registry.update_app("res", device_reserve_bytes=None)
        c1.put_object(Bucket="device", Key="c", Body=b"x" * 10_000)

    def test_pool_full(self):
        _, c = self.app("poolfull")
        core = self.srv.core
        before = core.settings.get("pool")
        used = core.usage.pool()["used"]
        try:
            core.settings.update({"pool": {"internal_gb": (used + 50_000) / GB / 2, "sd_gb": (used + 50_000) / GB / 2}})
            with self.assertRaises(botocore.exceptions.ClientError) as ctx:
                c.put_object(Bucket="shared", Key="x", Body=b"x" * 100_000)
            self.assertEqual(code(ctx)[1], 507)
            self.assertEqual(ctx.exception.response["Error"]["Code"], "PoolFull")
        finally:
            core.settings.update({"pool": before})

    # ---------- temporary links ----------
    def test_temporary_links(self):
        e, c = self.app("links")
        c.put_object(Bucket="shared", Key="report 2026+final.txt", Body=b"shared by link")
        v4 = self.srv.client(e, signature_version="s3v4")
        for label, client in (("version 2 (boto3 default)", c), ("version 4", v4)):
            with self.subTest(label):
                url = client.generate_presigned_url("get_object", Params={"Bucket": "shared", "Key": "report 2026+final.txt"},
                                                    ExpiresIn=120)
                self.assertEqual(urllib.request.urlopen(url).read(), b"shared by link")
                key = "uploaded-%s.txt" % label[:9].strip().replace(" ", "")
                put = client.generate_presigned_url("put_object", Params={"Bucket": "shared", "Key": key}, ExpiresIn=120)
                # version 2 links sign the content type; the link was made without one, so send none
                req = urllib.request.Request(put, data=b"by link", method="PUT", headers={"Content-Type": ""})
                self.assertEqual(urllib.request.urlopen(req).status, 200)
                self.assertEqual(c.get_object(Bucket="shared", Key=key)["Body"].read(), b"by link")
                with self.assertRaises(urllib.error.HTTPError) as h:
                    urllib.request.urlopen(url.replace("report", "rep0rt"))
                self.assertEqual(h.exception.code, 403)
                other = self.srv.client({**e, "secret_key": "someone-else"}, signature_version=client.meta.config.signature_version)
                forged = other.generate_presigned_url("get_object", Params={"Bucket": "shared", "Key": "report 2026+final.txt"})
                with self.assertRaises(urllib.error.HTTPError) as h:
                    urllib.request.urlopen(forged)
                self.assertEqual(h.exception.code, 403)

    # ---------- big files, copies, read-only ----------
    def test_multipart_upload_and_copy(self):
        _, c = self.app("big")
        data = os.urandom(20 * 1024 * 1024 + 123)
        path = os.path.join(self.srv.tmp, "big.bin")
        with open(path, "wb") as f:
            f.write(data)
        cfg = TransferConfig(multipart_threshold=8 * 1024 * 1024, multipart_chunksize=8 * 1024 * 1024)
        c.upload_file(path, "shared", "media/big.bin", Config=cfg)
        got = c.get_object(Bucket="shared", Key="media/big.bin")["Body"].read()
        self.assertEqual(hashlib.sha256(got).hexdigest(), hashlib.sha256(data).hexdigest())
        c.copy_object(Bucket="device", Key="copy.bin", CopySource={"Bucket": "shared", "Key": "media/big.bin"})
        self.assertEqual(c.head_object(Bucket="device", Key="copy.bin")["ContentLength"], len(data))
        rng = c.get_object(Bucket="shared", Key="media/big.bin", Range="bytes=100-199")["Body"].read()
        self.assertEqual(rng, data[100:200])

    def test_read_only_mode(self):
        _, c = self.app("ro")
        c.put_object(Bucket="shared", Key="a", Body=b"1")
        core = self.srv.core
        core.settings.update({"server": {"read_only": True}})
        try:
            with self.assertRaises(botocore.exceptions.ClientError) as ctx:
                c.put_object(Bucket="shared", Key="b", Body=b"2")
            self.assertEqual(code(ctx), ("AccessDenied", 403))
            self.assertEqual(c.get_object(Bucket="shared", Key="a")["Body"].read(), b"1")
        finally:
            core.settings.update({"server": {"read_only": False}})

    def test_blocked_and_rotated_keys(self):
        e, c = self.app("blk")
        core = self.srv.core
        core.registry.set_blocked("blk", "device-0001", True)
        with self.assertRaises(botocore.exceptions.ClientError):
            c.list_objects_v2(Bucket="shared")
        core.registry.set_blocked("blk", "device-0001", False)
        new = core.registry.rotate_key("blk", "device-0001")
        with self.assertRaises(botocore.exceptions.ClientError):
            c.list_objects_v2(Bucket="shared")
        c2 = self.srv.client({**e, "access_key": new["access_key"], "secret_key": new["secret"]})
        c2.list_objects_v2(Bucket="shared")

    # ---------- logging ----------
    def test_every_request_is_logged(self):
        _, c = self.app("logs")
        c.put_object(Bucket="shared", Key="logged.txt", Body=b"12345")
        c.get_object(Bucket="shared", Key="logged.txt")["Body"].read()
        core = self.srv.core
        core.stats.flush()
        rows = core.stats.query("SELECT op, key, bytes_in, bytes_out, status, device FROM requests WHERE app='logs' ORDER BY ts")
        ops = [(r["op"], r["key"], r["status"]) for r in rows]
        self.assertIn(("PutObject", "logged.txt", 200), ops)
        self.assertIn(("GetObject", "logged.txt", 200), ops)
        put = next(r for r in rows if r["op"] == "PutObject")
        get = next(r for r in rows if r["op"] == "GetObject")
        self.assertEqual((put["bytes_in"], get["bytes_out"], put["device"]), (5, 5, "device-0001"))
        daily = core.stats.query("SELECT requests, bytes_in FROM daily WHERE app='logs'")
        self.assertGreaterEqual(daily[0]["requests"], 2)
        core.settings.update({"logs": {"record_names": False}})
        try:
            c.get_object(Bucket="shared", Key="logged.txt")["Body"].read()
            core.stats.flush()
            last = core.stats.query("SELECT key FROM requests WHERE app='logs' ORDER BY ts DESC LIMIT 1")[0]
            self.assertIsNone(last["key"])
        finally:
            core.settings.update({"logs": {"record_names": True}})

    def test_usage_tracking(self):
        _, c = self.app("use")
        core = self.srv.core
        c.put_object(Bucket="shared", Key="a", Body=b"x" * 1000)
        c.put_object(Bucket="device", Key="b", Body=b"x" * 500)
        self.assertEqual(core.usage.app_used("use"), 1500)
        c.delete_object(Bucket="shared", Key="a")
        self.assertEqual(core.usage.app_used("use"), 500)
        core.usage.scan()
        self.assertEqual(core.usage.app_used("use"), 500)
        self.assertEqual(core.usage.space_used("use", "device:device-0001"), 500)


class LeftoverRclone(unittest.TestCase):
    def test_server_stops_an_rclone_left_running_by_a_dead_server(self):
        import subprocess
        from helpers import RCLONE, free_port
        rc_port = free_port()
        # what a server killed outright leaves behind: its rclone, still answering on the control port
        stray = subprocess.Popen([RCLONE, "rcd", "--rc-addr", f"127.0.0.1:{rc_port}", "--rc-no-auth"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            end = time.time() + 30
            while time.time() < end:
                try:
                    with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{rc_port}/core/version", data=b"{}"), timeout=1):
                        break
                except Exception:
                    time.sleep(0.2)
            srv = PocketServer(rc_port=rc_port).start()
            try:
                self.assertIsNotNone(stray.wait(15), "the leftover rclone was asked to quit and did")
                st, e = srv.enroll("left", "device-0001")
                self.assertEqual(st, 200)
                c = srv.client(e)
                c.put_object(Bucket="shared", Key="k.txt", Body=b"served by the new rclone")
                self.assertEqual(c.get_object(Bucket="shared", Key="k.txt")["Body"].read(), b"served by the new rclone")
            finally:
                srv.stop()
        finally:
            if stray.poll() is None:
                stray.kill()


if __name__ == "__main__":
    unittest.main()
