"""The v2 app-facing API (docs/app-api.md) against the real server: plain file routes, move, the change feed,
the publish folder, retention rules, notifications and the panel's rules routes."""

import hashlib
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.parse

from helpers import PocketServer, free_port

from pocket.changes import Changes, CursorExpired
from pocket.layout import FAMILY

ALL = ["quota", "conflicts", "mirror-report", "share", "push",
       "files", "move", "changes", "publish", "retention", "notify"]


def q(key):
    return urllib.parse.quote(key, safe="/-_.~")


def md5(b):
    return '"%s"' % hashlib.md5(b).hexdigest()


class V2(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = PocketServer(publish={"allow_loopback": True}, push={"enabled": True, "port": free_port()}).start()
        status, body = cls.srv.api("POST", "/api/setup", {"password": "correct horse"})
        assert status == 200, body
        cls.token = body["token"]

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    # ---- helpers ----
    def app(self, name, uid="device-0001", device_name="Desktop PC"):
        status, e = self.srv.enroll(name, uid, device_name)
        self.assertEqual(status, 200, e)
        return e

    def put(self, e, key, data, bucket="shared", expect=200, headers=None, **kw):
        status, h, body = self.srv.raw(e, "PUT", f"/_pocket/files/{bucket}/{q(key)}", body=data, headers=headers, **kw)
        self.assertEqual(status, expect, body)
        return json.loads(body) if body else None

    def get(self, e, key, bucket="shared", headers=None, method="GET"):
        return self.srv.raw(e, method, f"/_pocket/files/{bucket}/{q(key)}", headers=headers)

    def call(self, e, method, path, query="", body=None, expect=200):
        status, out = self.srv.pocket(e, method, path, query, body)
        self.assertEqual(status, expect, out)
        return out

    def panel(self, method, path, body=None, expect=200):
        status, out = self.srv.api(method, path, body, token=self.token)
        self.assertEqual(status, expect, out)
        return out

    def changes(self, e, since, **kw):
        query = urllib.parse.urlencode({"since": since, **kw})
        return self.call(e, "GET", "/_pocket/changes", query)

    def cursor(self, e):
        return self.call(e, "GET", "/_pocket/changes")["cursor"]

    # ---- health and the app's switches ----
    def test_health_and_app(self):
        status, h, body = self.srv.raw({"access_key": "", "secret_key": ""}, "GET", "/_pocket/health")
        self.assertEqual(json.loads(body)["features"], ALL)
        e = self.app("v2-app")
        info = self.call(e, "GET", "/_pocket/app")
        self.assertEqual(info["app"], "v2-app")
        self.assertEqual(info["device_id"], "device-0001")
        self.assertEqual(info["buckets"], ["shared", "device"])
        self.assertEqual(info["features"], ALL)
        self.assertEqual(info["publish"], {"enabled": False, "url": f"http://127.0.0.1:{self.srv.port}/_pocket/pub/v2-app/"})
        self.assertEqual(info["notify"]["topic"], "pocket-v2-app-notify")
        self.assertFalse(info["notify"]["enabled"])
        self.assertEqual(info["push"]["topic"], "pocket-v2-app")
        self.assertEqual(info["retention"], [])
        self.assertEqual(info["changes"], {"keep_days": 30})
        self.assertFalse(info["read_only"])
        bad = dict(e, secret_key="wrong")
        self.assertEqual(self.srv.pocket(bad, "GET", "/_pocket/app")[1]["error"], "SignatureDoesNotMatch")
        self.assertEqual(self.srv.pocket(dict(e, access_key="PKNOPE"), "GET", "/_pocket/app")[1]["error"], "InvalidAccessKeyId")
        self.assertEqual(self.srv.pocket(e, "GET", "/_pocket/nothing-here")[1]["error"], "NotFound")

    # ---- read, stat, write ----
    def test_write_read_stat(self):
        e = self.app("v2-files")
        out = self.put(e, "notes/hello world.txt", b"hello", headers={"Content-Type": "text/plain"})
        self.assertEqual(out["etag"], '"5d41402abc4b2a76b9719d911017c592"')
        self.assertEqual((out["bucket"], out["key"], out["size"], out["created"]), ("shared", "notes/hello world.txt", 5, True))
        status, h, body = self.get(e, "notes/hello world.txt")
        self.assertEqual((status, body), (200, b"hello"))
        self.assertEqual(h["content-type"], "text/plain")
        self.assertEqual(h["etag"], out["etag"])
        self.assertEqual(h["accept-ranges"], "bytes")
        self.assertAlmostEqual(float(h["x-pocket-modified"]), out["modified"], places=3)
        self.assertIn("GMT", h["last-modified"])
        status, h, body = self.get(e, "notes/hello world.txt", method="HEAD")
        self.assertEqual((status, body, h["content-length"]), (200, b"", "5"))
        # overwrite: not created, old version in the undo
        out2 = self.put(e, "notes/hello world.txt", b"hello again")
        self.assertFalse(out2["created"])
        undo = self.panel("GET", "/api/undo")["items"]
        self.assertTrue(any(i["app"] == "v2-files" and i["key"] == "notes/hello world.txt" and i["reason"] == "overwritten"
                            for i in undo))
        # the file is visible through S3 too, and S3 writes through the plain routes
        s3 = self.srv.client(e)
        self.assertEqual(s3.get_object(Bucket="shared", Key="notes/hello world.txt")["Body"].read(), b"hello again")
        s3.put_object(Bucket="shared", Key="from-s3.bin", Body=b"abc")
        status, h, body = self.get(e, "from-s3.bin")
        self.assertEqual((status, body, h["etag"]), (200, b"abc", md5(b"abc")))
        self.assertEqual(h["etag"], s3.head_object(Bucket="shared", Key="from-s3.bin")["ETag"])
        # times are kept
        out = self.put(e, "old.txt", b"x", headers={"x-pocket-modified": "1700000000.5"})
        self.assertAlmostEqual(out["modified"], 1700000000.5, places=3)
        # the private space
        self.put(e, "mine.txt", b"private", bucket="device")
        self.assertEqual(self.get(e, "mine.txt", bucket="device")[2], b"private")
        other = self.app("v2-files", "device-0002", "Laptop")
        self.assertEqual(self.get(other, "mine.txt", bucket="device")[0], 404)
        self.assertEqual(json.loads(self.get(e, "x", bucket="family")[2])["error"], "NoSuchBucket")
        status, h, body = self.get(e, "missing.txt")
        self.assertEqual((status, json.loads(body)["error"]), (404, "NoSuchKey"))
        status, h, body = self.get(e, "missing.txt", method="HEAD")
        self.assertEqual((status, h["x-pocket-error-code"], body), (404, "NoSuchKey", b""))

    def test_range_and_conditions(self):
        e = self.app("v2-range")
        etag = self.put(e, "r.bin", b"0123456789")["etag"]
        status, h, body = self.get(e, "r.bin", headers={"Range": "bytes=2-4"})
        self.assertEqual((status, body, h["content-range"]), (206, b"234", "bytes 2-4/10"))
        self.assertEqual(self.get(e, "r.bin", headers={"Range": "bytes=-3"})[2], b"789")
        self.assertEqual(self.get(e, "r.bin", headers={"Range": "bytes=7-"})[2], b"789")
        status, h, body = self.get(e, "r.bin", headers={"Range": "bytes=20-30"})
        self.assertEqual((status, json.loads(body)["error"]), (416, "InvalidRange"))
        self.assertEqual(self.get(e, "r.bin", headers={"if-none-match": etag})[0], 304)
        self.assertEqual(self.get(e, "r.bin", headers={"if-none-match": '"other"'})[0], 200)
        self.assertEqual(self.get(e, "r.bin", headers={"if-match": '"other"'})[0], 412)
        self.assertEqual(self.get(e, "r.bin", headers={"if-match": etag})[0], 200)
        # conditional writes
        self.put(e, "r.bin", b"new", headers={"if-match": '"stale"'}, expect=412)
        self.put(e, "r.bin", b"new", headers={"if-none-match": "*"}, expect=412)
        self.put(e, "nothing.bin", b"new", headers={"if-match": etag}, expect=412)
        self.put(e, "fresh.bin", b"new", headers={"if-none-match": "*"})
        e2 = self.put(e, "r.bin", b"new", headers={"if-match": etag})["etag"]
        self.assertEqual(e2, md5(b"new"))

    def test_upload_refusals(self):
        e = self.app("v2-refuse")
        status, h, body = self.srv.raw(e, "PUT", "/_pocket/files/shared/a.txt", body=b"hello", sha="0" * 64)
        self.assertEqual((status, json.loads(body)["error"]), (400, "BadDigest"))
        self.assertEqual(self.get(e, "a.txt")[0], 404, "nothing stored")
        staging = self.srv.files("int", "_staging")
        self.assertEqual(os.listdir(staging) if os.path.isdir(staging) else [], [], "no leftovers")
        for key in ("a//b", "./a", "a/../b", "a/"):
            status, h, body = self.srv.raw(e, "PUT", "/_pocket/files/shared/" + key, body=b"x")
            self.assertEqual((status, json.loads(body)["error"]), (400, "BadKey"), key)
        status, h, body = self.srv.raw(e, "PUT", "/_pocket/files/shared/n.txt", body=b"x",
                                       headers={"x-pocket-key": e["access_key"], "x-pocket-sign": "0" * 64})
        self.assertEqual(json.loads(body)["error"], "SignatureDoesNotMatch")
        # read-only mode
        core = self.srv.core
        core.settings.update({"server": {"read_only": True}})
        try:
            status, h, body = self.srv.raw(e, "PUT", "/_pocket/files/shared/ro.txt", body=b"x")
            self.assertEqual((status, json.loads(body)["error"]), (403, "ReadOnly"))
            self.assertEqual(self.call(e, "GET", "/_pocket/app")["read_only"], True)
        finally:
            core.settings.update({"server": {"read_only": False}})
        # over the allocation
        core.registry.set_allocation("v2-refuse", 1000, sum(core.settings.pool_bytes()), 0)
        status, h, body = self.srv.raw(e, "PUT", "/_pocket/files/shared/big.bin", body=b"x" * 2000)
        self.assertEqual((status, json.loads(body)["error"]), (403, "QuotaExceeded"))

    def test_list(self):
        e = self.app("v2-list")
        for k in ("notes/a.txt", "notes/b.txt", "notes/2026/c.txt", "notes/2026/d/e.txt", "nother.txt", "zeta.txt"):
            self.put(e, k, k.encode())
        out = self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=notes%2F")
        self.assertEqual([f["key"] for f in out["files"]], ["notes/2026/c.txt", "notes/2026/d/e.txt", "notes/a.txt", "notes/b.txt"])
        self.assertNotIn("folders", out)
        self.assertIsNone(out["cursor"])
        self.assertEqual(out["files"][2]["etag"], md5(b"notes/a.txt"))
        out = self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=notes%2F&delimiter=%2F")
        self.assertEqual([f["key"] for f in out["files"]], ["notes/a.txt", "notes/b.txt"])
        self.assertEqual(out["folders"], ["notes/2026/"])
        out = self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=no")
        self.assertEqual(len(out["files"]), 5)
        # paging
        seen, cursor = [], None
        while True:
            query = "bucket=shared&limit=2" + (f"&cursor={cursor}" if cursor else "")
            out = self.call(e, "GET", "/_pocket/list", query)
            seen += [f["key"] for f in out["files"]]
            cursor = out["cursor"]
            if not cursor:
                break
        self.assertEqual(seen, sorted(seen))
        self.assertEqual(len(seen), 6)
        first = self.call(e, "GET", "/_pocket/list", "bucket=shared&limit=2")["cursor"]
        self.assertEqual(self.srv.pocket(e, "GET", "/_pocket/list", f"bucket=device&limit=2&cursor={first}")[1]["error"],
                         "BadRequest")
        self.assertEqual(self.srv.pocket(e, "GET", "/_pocket/list", "bucket=shared&limit=0")[0], 400)
        self.assertEqual(self.srv.pocket(e, "GET", "/_pocket/list", "bucket=shared&delimiter=%7C")[0], 400)
        self.assertEqual(self.srv.pocket(e, "GET", "/_pocket/list", "bucket=nope")[1]["error"], "NoSuchBucket")

    def test_delete(self):
        e = self.app("v2-delete")
        etag = self.put(e, "d/x.txt", b"x")["etag"]
        self.assertEqual(self.srv.raw(e, "DELETE", "/_pocket/files/shared/d/x.txt", headers={"if-match": '"no"'})[0], 412)
        status, h, body = self.srv.raw(e, "DELETE", "/_pocket/files/shared/d/x.txt", headers={"if-match": etag})
        self.assertEqual(json.loads(body), {"bucket": "shared", "key": "d/x.txt", "deleted": True})
        self.assertEqual(self.get(e, "d/x.txt")[0], 404)
        status, h, body = self.srv.raw(e, "DELETE", "/_pocket/files/shared/d/x.txt")
        self.assertEqual((status, json.loads(body)["error"]), (404, "NoSuchKey"))
        undo = self.panel("GET", "/api/undo")["items"]
        self.assertTrue(any(i["app"] == "v2-delete" and i["key"] == "d/x.txt" and i["reason"] == "deleted" for i in undo))

    def test_move(self):
        e = self.app("v2-move")
        etag = self.put(e, "inbox/a.txt", b"hello", headers={"x-pocket-modified": "1700000000"})["etag"]
        out = self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "inbox/a.txt"},
                                                          "to": {"bucket": "shared", "key": "done/a.txt"}})
        self.assertEqual(out, {"from": {"bucket": "shared", "key": "inbox/a.txt"},
                               "to": {"bucket": "shared", "key": "done/a.txt"}, "size": 5, "etag": etag})
        self.assertEqual(self.get(e, "inbox/a.txt")[0], 404)
        status, h, body = self.get(e, "done/a.txt")
        self.assertEqual((body, h["etag"]), (b"hello", etag))
        self.assertAlmostEqual(float(h["x-pocket-modified"]), 1700000000, places=3)
        self.put(e, "other.txt", b"other")
        mv = {"from": {"bucket": "shared", "key": "done/a.txt"}, "to": {"bucket": "shared", "key": "other.txt"}}
        self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/move", body=mv)[1]["error"], "KeyExists")
        self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/move", body={**mv, "if_match": '"x"', "overwrite": True})[0], 412)
        self.call(e, "POST", "/_pocket/move", body={**mv, "if_match": etag, "overwrite": True})
        self.assertEqual(self.get(e, "other.txt")[2], b"hello")
        self.assertTrue(any(i["app"] == "v2-move" and i["key"] == "other.txt" and i["reason"] == "overwritten"
                            for i in self.panel("GET", "/api/undo")["items"]))
        # across buckets: usage follows the file
        used_before = self.srv.core.usage.space_used("v2-move", "device:device-0001")
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "other.txt"},
                                                    "to": {"bucket": "device", "key": "mine.txt"}})
        self.assertEqual(self.get(e, "mine.txt", bucket="device")[2], b"hello")
        self.assertEqual(self.srv.core.usage.space_used("v2-move", "device:device-0001"), used_before + 5)
        self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/move", body={**mv, "from": {"bucket": "shared", "key": "gone"}})[1]["error"],
                         "NoSuchKey")
        self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared"}})[1]["error"], "BadRequest")
        # S3 sees the move at once
        s3 = self.srv.client(e)
        keys = [o["Key"] for o in s3.list_objects_v2(Bucket="shared").get("Contents", [])]
        self.assertNotIn("other.txt", keys)

    # ---- the change feed ----
    def test_changes(self):
        e = self.app("v2-feed")
        e2 = self.app("v2-feed", "device-0002", "Laptop")
        start = self.cursor(e)
        self.assertEqual(self.changes(e, start)["changes"], [])
        etag = self.put(e, "inbox/a.txt", b"hello")["etag"]
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "inbox/a.txt"},
                                                    "to": {"bucket": "shared", "key": "done/a.txt"}})
        self.put(e, "secret.txt", b"mine", bucket="device")
        s3 = self.srv.client(e2)
        s3.put_object(Bucket="shared", Key="s3.txt", Body=b"abc")
        s3.delete_object(Bucket="shared", Key="s3.txt")
        s3.delete_object(Bucket="shared", Key="never-there.txt")
        self.srv.raw(e, "DELETE", "/_pocket/files/shared/done/a.txt")
        out = self.changes(e, start)
        got = [(c["kind"], c["bucket"], c["key"]) for c in out["changes"]]
        self.assertEqual(got, [("put", "shared", "inbox/a.txt"), ("move", "shared", "done/a.txt"),
                               ("put", "device", "secret.txt"), ("put", "shared", "s3.txt"),
                               ("delete", "shared", "s3.txt"), ("delete", "shared", "done/a.txt")])
        c0, c1 = out["changes"][0], out["changes"][1]
        self.assertEqual((c0["size"], c0["etag"], c0["device"], c0["source"]), (5, etag, "device-0001", "app"))
        self.assertEqual(c1["from"], {"bucket": "shared", "key": "inbox/a.txt"})
        self.assertEqual(out["changes"][3]["etag"], md5(b"abc"))
        self.assertEqual(out["changes"][3]["device"], "device-0002")
        self.assertIsNone(out["changes"][4]["etag"])
        self.assertFalse(out["more"])
        seqs = [c["seq"] for c in out["changes"]]
        self.assertEqual(seqs, sorted(seqs))
        # the other device does not see the private file
        got2 = [(c["kind"], c["key"]) for c in self.changes(e2, start)["changes"]]
        self.assertNotIn(("put", "secret.txt"), got2)
        self.assertEqual(len(got2), 5)
        # paging and the cursor
        page = self.changes(e, start, limit=2)
        self.assertEqual((len(page["changes"]), page["more"]), (2, True))
        rest = self.changes(e, page["cursor"])
        self.assertEqual([c["seq"] for c in rest["changes"]], seqs[2:])
        self.assertEqual(self.changes(e, rest["cursor"])["changes"], [])
        # another app sees none of it
        other = self.app("v2-feed-other")
        self.assertEqual(self.changes(other, start)["changes"], [])
        # a cursor never issued, or not a cursor
        for bad in (str(int(out["cursor"]) + 1000), "abc", "1"):
            status, body = self.srv.pocket(e, "GET", "/_pocket/changes", "since=" + bad)
            self.assertEqual((status, body["error"]), (410, "CursorExpired"), bad)
            self.assertEqual(body["cursor"], self.cursor(e))

    def test_changes_long_poll_and_moves_across_buckets(self):
        e = self.app("v2-poll")
        e2 = self.app("v2-poll", "device-0002", "Laptop")
        start = self.cursor(e)
        t0 = time.time()
        out = self.changes(e, start, wait=1)
        self.assertEqual(out["changes"], [])
        self.assertGreaterEqual(time.time() - t0, 0.9)
        threading.Timer(0.5, lambda: self.put(e, "late.txt", b"x")).start()
        t0 = time.time()
        out = self.changes(e, start, wait=20)
        self.assertLess(time.time() - t0, 10)
        self.assertEqual([c["key"] for c in out["changes"]], ["late.txt"])
        # a move out of the private space is a put for the other device; into it, a delete
        start = self.cursor(e)
        self.put(e, "p.txt", b"p", bucket="device")
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "device", "key": "p.txt"},
                                                    "to": {"bucket": "shared", "key": "p.txt"}})
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "p.txt"},
                                                    "to": {"bucket": "device", "key": "p2.txt"}})
        mine = [(c["kind"], c["bucket"], c["key"]) for c in self.changes(e, start)["changes"]]
        self.assertEqual(mine, [("put", "device", "p.txt"), ("move", "shared", "p.txt"), ("move", "device", "p2.txt")])
        theirs = [(c["kind"], c["bucket"], c["key"]) for c in self.changes(e2, start)["changes"]]
        self.assertEqual(theirs, [("put", "shared", "p.txt"), ("delete", "shared", "p.txt")])

    def test_changes_from_s3_copy_multipart_and_batch_delete(self):
        e = self.app("v2-s3feed")
        s3 = self.srv.client(e)
        start = self.cursor(e)
        s3.put_object(Bucket="shared", Key="src.txt", Body=b"source")
        s3.copy_object(Bucket="shared", Key="copy.txt", CopySource={"Bucket": "shared", "Key": "src.txt"})
        part1, part2 = os.urandom(5 * 1024 * 1024), b"tail"
        up = s3.create_multipart_upload(Bucket="shared", Key="big.bin")["UploadId"]
        p1 = s3.upload_part(Bucket="shared", Key="big.bin", UploadId=up, PartNumber=1, Body=part1)["ETag"]
        p2 = s3.upload_part(Bucket="shared", Key="big.bin", UploadId=up, PartNumber=2, Body=part2)["ETag"]
        done = s3.complete_multipart_upload(Bucket="shared", Key="big.bin", UploadId=up, MultipartUpload={
            "Parts": [{"ETag": p1, "PartNumber": 1}, {"ETag": p2, "PartNumber": 2}]})
        s3.delete_objects(Bucket="shared", Delete={"Objects": [{"Key": "src.txt"}, {"Key": "copy.txt"}, {"Key": "nope"}]})
        out = self.changes(e, start)["changes"]
        self.assertEqual([(c["kind"], c["key"]) for c in out],
                         [("put", "src.txt"), ("put", "copy.txt"), ("put", "big.bin"), ("delete", "src.txt"),
                          ("delete", "copy.txt")])
        self.assertEqual(out[1]["etag"], md5(b"source"))
        self.assertEqual(out[2]["size"], len(part1) + len(part2))
        self.assertEqual(out[2]["etag"], done["ETag"], "parts uploads keep S3's ETag")
        status, h, _ = self.get(e, "big.bin", method="HEAD")
        self.assertEqual(h["etag"], done["ETag"])

    def test_changes_from_the_panel(self):
        e = self.app("v2-panelfeed")
        start = self.cursor(e)
        status, _ = self.srv.api("PUT", "/api/files/upload?app=v2-panelfeed&space=shared&key=up.txt", raw=b"panel",
                                 token=self.token)
        self.assertEqual(status, 200)
        self.panel("DELETE", "/api/files?app=v2-panelfeed&space=shared&key=up.txt")
        out = self.changes(e, start)["changes"]
        self.assertEqual([(c["kind"], c["source"], c["device"]) for c in out], [("put", "panel", None), ("delete", "panel", None)])
        self.assertEqual(out[0]["etag"], md5(b"panel"))

    # ---- publish folder ----
    def pub(self, path, method="GET", headers=None):
        return self.srv.raw({"access_key": "", "secret_key": ""}, method, "/_pocket/pub/" + path, headers=headers)

    def test_publish(self):
        e = self.app("v2-pub")
        self.put(e, "releases/1.0/app.apk", b"apk-bytes")
        self.put(e, "releases/latest.json", json.dumps({"app": "v2-pub", "version": "1.0", "published": 1795000000}).encode())
        self.assertEqual(self.pub("v2-pub/latest.json")[0], 404, "off by default")
        self.assertEqual(self.pub("no-such-app/latest.json")[0], 404)
        rules = self.panel("PATCH", "/api/apps/v2-pub/rules", {"publish": {"enabled": True}})
        self.assertTrue(rules["publish"]["enabled"])
        self.assertEqual(rules["publish"]["latest"], {"version": "1.0", "published": 1795000000})
        status, h, body = self.pub("v2-pub/latest.json")
        self.assertEqual((status, h["cache-control"]), (200, "no-cache"))
        self.assertEqual(json.loads(body)["version"], "1.0")
        status, h, body = self.pub("v2-pub/1.0/app.apk")
        self.assertEqual((status, body, h["cache-control"]), (200, b"apk-bytes", "max-age=86400"))
        self.assertEqual(self.pub("v2-pub/1.0/app.apk", headers={"Range": "bytes=0-2"})[2], b"apk")
        self.assertEqual(self.pub("v2-pub/1.0/app.apk", headers={"if-none-match": h["etag"]})[0], 304)
        self.assertEqual(self.pub("v2-pub/1.0/app.apk", method="HEAD")[0], 200)
        self.assertEqual(self.pub("v2-pub/")[0], 404, "no listing")
        self.assertEqual(self.pub("v2-pub/1.0/missing.apk")[0], 404)
        self.assertEqual(self.pub("v2-pub/latest.json", method="DELETE")[0], 405)
        self.assertEqual(self.call(e, "GET", "/_pocket/app")["publish"]["enabled"], True)
        # only on the Tailscale address
        self.srv.core.settings.update({"publish": {"allow_loopback": False}})
        try:
            self.assertEqual(self.pub("v2-pub/latest.json")[0], 404)
        finally:
            self.srv.core.settings.update({"publish": {"allow_loopback": True}})
        self.srv.core.stats.flush()
        ops = self.srv.core.stats.query("SELECT op, device FROM requests WHERE op='PubRead' AND app='v2-pub'")
        self.assertTrue(ops and all(r["device"] is None for r in ops))

    # ---- retention ----
    def test_retention(self):
        e = self.app("v2-keep")
        now = time.time()
        for i in range(10):
            self.put(e, f"backups/db/{i:02d}.zip", b"z" * 100, headers={"x-pocket-modified": str(now - (10 - i) * 86400)})
        self.put(e, "logs/old.log", b"l", bucket="device", headers={"x-pocket-modified": str(now - 40 * 86400)})
        self.put(e, "logs/new.log", b"l", bucket="device", headers={"x-pocket-modified": str(now - 86400)})
        self.put(e, "logs/older.log", b"l", bucket="device", headers={"x-pocket-modified": str(now - 50 * 86400)})
        self.put(e, "logs/keep1.log", b"l", bucket="device")
        for v in range(8):
            self.put(e, f"releases/{v}/a.apk", b"a", headers={"x-pocket-modified": str(now - (20 - v) * 86400)})
        self.put(e, "releases/latest.json", b"{}", headers={"x-pocket-modified": str(now - 90 * 86400)})
        self.put(e, "releases/latest-mac.yml", b"y", headers={"x-pocket-modified": str(now - 90 * 86400)})
        self.backdate_arrivals("v2-keep")
        start = self.cursor(e)
        bad = self.srv.api("PATCH", "/api/apps/v2-keep/rules", {"retention": [
            {"bucket": "shared", "prefix": "a/", "keep_last": 1, "keep_days": None},
            {"bucket": "shared", "prefix": "b/", "keep_last": None, "keep_days": None}]}, token=self.token)
        self.assertEqual(bad[0], 400)
        self.assertTrue(bad[1]["message"].startswith("Rule 2:"), bad)
        dup = self.srv.api("PATCH", "/api/apps/v2-keep/rules", {"retention": [
            {"bucket": "shared", "prefix": "a/", "keep_last": 1}, {"bucket": "shared", "prefix": "a/", "keep_days": 3}]},
            token=self.token)
        self.assertEqual(dup[0], 400)
        self.assertEqual(self.srv.api("PATCH", "/api/apps/v2-keep/rules", {"retention": [
            {"bucket": "family", "prefix": "a/", "keep_last": 1}]}, token=self.token)[0], 400)
        rules = [{"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": None},
                 {"bucket": "device", "prefix": "logs/", "keep_last": None, "keep_days": 14},
                 {"bucket": "shared", "prefix": "releases/", "keep_last": 6, "keep_days": None},
                 # overlaps backups/: keeps what is younger than 10 days, so 01 and 02 stay (both rules must agree)
                 {"bucket": "shared", "prefix": "backups/db/", "keep_last": None, "keep_days": 10}]
        out = self.panel("PATCH", "/api/apps/v2-keep/rules", {"retention": rules})
        self.assertEqual([r["prefix"] for r in out["retention"]], ["backups/", "logs/", "releases/", "backups/db/"])
        self.assertIsNone(out["retention"][0]["last_run"])
        self.assertEqual(self.call(e, "GET", "/_pocket/app")["retention"], rules)
        core = self.srv.core
        result, detail = core.jobs.run("Retention rules", core.jobs.retention)
        self.assertEqual(result, "ok", detail)
        names = [f["key"] for f in self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=backups%2F")["files"]]
        # backups/ alone would keep 03..09; backups/db/ (10 days) would keep 01..09: only 00 goes
        self.assertEqual(names, [f"backups/db/{i:02d}.zip" for i in range(1, 10)])
        logs = [f["key"] for f in self.call(e, "GET", "/_pocket/list", "bucket=device&prefix=logs%2F")["files"]]
        self.assertEqual(logs, ["logs/keep1.log", "logs/new.log"])
        rel = [f["key"] for f in self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=releases%2F")["files"]]
        self.assertIn("releases/latest.json", rel)
        self.assertIn("releases/latest-mac.yml", rel)
        self.assertEqual(len([k for k in rel if k.endswith(".apk")]), 6)
        feed = self.changes(e, start)["changes"]
        self.assertEqual(len(feed), 5)
        self.assertTrue(all(c["kind"] == "delete" and c["source"] == "retention" and c["device"] is None for c in feed))
        undo = [i for i in self.panel("GET", "/api/undo")["items"] if i["app"] == "v2-keep" and i["reason"] == "deleted"]
        self.assertEqual(len(undo), 5)
        after = self.panel("GET", "/api/apps/v2-keep/rules")["retention"]
        self.assertEqual((after[0]["last_removed"], after[0]["last_freed"]), (1, 100))
        self.assertEqual(after[1]["last_removed"], 2)
        self.assertIsNotNone(after[1]["last_run"])
        # the guard: a rule that would remove more than half removes nothing and raises an alert
        self.panel("PATCH", "/api/apps/v2-keep/rules", {"retention": [{"bucket": "shared", "prefix": "backups/", "keep_last": 1}]})
        result, detail = core.jobs.run("Retention rules", core.jobs.retention)
        self.assertEqual(result, "warn", detail)
        self.assertEqual(len(self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=backups%2F")["files"]), 9)
        self.assertTrue(any(a["kind"] == "retention_guard" for a in core.stats.active_alerts()))
        # run by hand from the panel, the held-back rule is applied
        self.panel("POST", "/api/jobs/retention/run", {})
        end = time.time() + 30
        while time.time() < end and len(self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=backups%2F")["files"]) > 1:
            time.sleep(0.2)
        self.assertEqual([f["key"] for f in self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=backups%2F")["files"]],
                         ["backups/db/09.zip"])
        self.assertFalse(any(a["kind"] == "retention_guard" and a["subject"].startswith("v2-keep:")
                             for a in core.stats.active_alerts()))
        self.panel("PATCH", "/api/apps/v2-keep/rules", {"retention": []})
        self.assertEqual(self.panel("GET", "/api/apps/v2-keep/rules")["retention"], [])

    def backdate_arrivals(self, app):
        """Files the test gave old dates are taken as having arrived then too (as if uploaded long ago)."""
        meta = self.srv.core.meta
        with meta._lock:
            meta._con.execute("UPDATE file_meta SET arrived = mtime_ns / 1e9 WHERE app=?", (app,))
            meta._con.commit()

    def test_retention_ages_by_arrival(self):
        e = self.app("v2-arrive")
        now = time.time()
        self.put(e, "logs/ancient.log", b"a", headers={"x-pocket-modified": str(now - 400 * 86400)})
        self.put(e, "logs/old.log", b"o", headers={"x-pocket-modified": str(now - 300 * 86400)})
        self.backdate_arrivals("v2-arrive")
        # uploaded just now, but carrying an old date: young
        self.put(e, "logs/fresh-copy.log", b"f", headers={"x-pocket-modified": str(now - 200 * 86400)})
        self.panel("PATCH", "/api/apps/v2-arrive/rules", {"retention": [{"bucket": "shared", "prefix": "logs/", "keep_days": 7}]})
        core = self.srv.core
        core.jobs.run("Retention rules", lambda: core.jobs.retention(force=True))
        keys = [f["key"] for f in self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=logs%2F")["files"]]
        self.assertEqual(keys, ["logs/fresh-copy.log"])

    def test_empty_app_feeds_deletes(self):
        e = self.app("v2-empty")
        self.put(e, "a.txt", b"a")
        self.put(e, "b/c.txt", b"c", bucket="device")
        start = self.cursor(e)
        self.panel("POST", "/api/apps/v2-empty/empty", {})
        got = sorted((c["kind"], c["bucket"], c["key"], c["source"]) for c in self.changes(e, start)["changes"])
        self.assertEqual(got, [("delete", "device", "b/c.txt", "panel"), ("delete", "shared", "a.txt", "panel")])
        self.assertIsNone(self.srv.core.meta._row("v2-empty", "shared", "a.txt"))

    def test_listing_hashes_within_a_budget(self):
        import pocket.files as files
        e = self.app("v2-hash")
        core = self.srv.core
        base = self.srv.files("int", "apps", "v2-hash", "shared", "old")
        os.makedirs(base, exist_ok=True)
        for i in range(4):                     # written before v2 (behind the server's back): no ETag yet
            with open(os.path.join(base, f"{i}.bin"), "wb") as f:
                f.write(b"%d" % i * 1000)
        saved = files.LIST_HASH_BYTES
        files.LIST_HASH_BYTES = 1              # every file without an ETag ends the page
        try:
            pages, seen, cursor = 0, [], None
            while True:
                out = self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=old%2F" + (f"&cursor={cursor}" if cursor else ""))
                pages += 1
                seen += [(f["key"], f["etag"]) for f in out["files"]]
                cursor = out["cursor"]
                if not cursor:
                    break
            self.assertEqual(pages, 4)
            self.assertEqual(seen, [(f"old/{i}.bin", md5(b"%d" % i * 1000)) for i in range(4)])
            # known now: one page
            self.assertIsNone(self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=old%2F")["cursor"])
            with open(os.path.join(base, "new.bin"), "wb") as f:
                f.write(b"n")
            self.assertGreaterEqual(core.hash_backlog(), 1)
            self.assertEqual(core.meta.known("v2-hash", "shared", "old/new.bin", os.stat(os.path.join(base, "new.bin"))), md5(b"n"))
        finally:
            files.LIST_HASH_BYTES = saved

    def test_delimiter_listing_skips_empty_folders(self):
        e = self.app("v2-delim")
        self.put(e, "top.txt", b"t")
        self.put(e, "full/a/b.txt", b"b")
        os.makedirs(self.srv.files("int", "apps", "v2-delim", "shared", "empty", "deeper"), exist_ok=True)
        out = self.call(e, "GET", "/_pocket/list", "bucket=shared&delimiter=%2F")
        self.assertEqual(([f["key"] for f in out["files"]], out["folders"]), (["top.txt"], ["full/"]))
        out = self.call(e, "GET", "/_pocket/list", "bucket=shared&prefix=fu&delimiter=%2F")
        self.assertEqual(out["folders"], ["full/"])

    def test_move_near_the_allocation(self):
        e = self.app("v2-tight")
        core = self.srv.core
        self.put(e, "big.bin", b"x" * 900)
        core.registry.set_allocation("v2-tight", 1000, sum(core.settings.pool_bytes()), core.usage.app_used("v2-tight"))
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "big.bin"},
                                                    "to": {"bucket": "device", "key": "big.bin"}})
        self.call(e, "POST", "/_pocket/move", body={"from": {"bucket": "device", "key": "big.bin"},
                                                    "to": {"bucket": "shared", "key": "big.bin"}})
        core.registry.update_app("v2-tight", device_cap_bytes=500)
        try:
            status, body = self.srv.pocket(e, "POST", "/_pocket/move", body={"from": {"bucket": "shared", "key": "big.bin"},
                                                                             "to": {"bucket": "device", "key": "big.bin"}})
            self.assertEqual((status, body["error"]), (403, "QuotaExceeded"), "the device cap still holds")
        finally:
            core.registry.update_app("v2-tight", device_cap_bytes=None)

    def test_refused_big_upload_is_answered_not_reset(self):
        e = self.app("v2-drain")
        core = self.srv.core
        core.settings.update({"server": {"read_only": True}})
        try:
            body = b"\0" * (70 * 1024 * 1024)          # more than the 64 MiB other refusals read and discard
            status, h, out = self.srv.raw(e, "PUT", "/_pocket/files/shared/big.bin", body=body)
            self.assertEqual((status, json.loads(out)["error"]), (403, "ReadOnly"))
        finally:
            core.settings.update({"server": {"read_only": False}})

    # ---- notifications ----
    def test_notify(self):
        e = self.app("v2-notify", device_name="Desktop PC")
        msg = {"message": "6 new calls", "title": "Evening calls", "tags": ["chart_with_upwards_trend"], "priority": 4,
               "click": "egx://calls"}
        status, body = self.srv.pocket(e, "POST", "/_pocket/notify", body=msg)
        self.assertEqual((status, body["error"]), (403, "FeatureOff"))
        self.panel("PATCH", "/api/apps/v2-notify/rules", {"notify": {"enabled": True}})
        self.assertTrue(self.call(e, "GET", "/_pocket/app")["notify"]["enabled"])
        out = self.call(e, "POST", "/_pocket/notify", body=msg)
        self.assertEqual(out["topic"], "pocket-v2-notify-notify")
        sent = self.srv.core.push.hub.since(["pocket-v2-notify-notify"], "all")
        self.assertEqual(len(sent), 1)
        m = sent[0]
        self.assertEqual((m["id"], m["message"], m["title"], m["priority"], m["click"], m["x-pocket-device"]),
                         (out["id"], "6 new calls", "Evening calls", 4, "egx://calls", "Desktop PC"))
        self.assertEqual(self.srv.core.push.hub.since(["pocket-v2-notify"], "all"), [], "not on the change topic")
        for bad in ({}, {"message": ""}, {"message": "x", "priority": 9}, {"message": "x", "tags": ["Bad Tag"]}):
            self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/notify", body=bad)[0], 400, bad)
        for _ in range(9):
            self.call(e, "POST", "/_pocket/notify", body={"message": "again"})
        status, h, body = self.srv.raw(e, "POST", "/_pocket/notify", body=json.dumps({"message": "one too many"}).encode())
        self.assertEqual((status, json.loads(body)["error"]), (429, "RateLimited"))
        self.assertGreaterEqual(int(h["retry-after"]), 1)
        rules = self.panel("GET", "/api/apps/v2-notify/rules")
        self.assertEqual((rules["notify"]["sent_today"], rules["notify"]["limit_day"]), (10, 200))
        # push switched off on the server: FeatureOff too
        self.srv.core.settings.update({"push": {"enabled": False}})
        try:
            self.assertEqual(self.srv.pocket(e, "POST", "/_pocket/notify", body={"message": "x"})[1]["error"], "FeatureOff")
        finally:
            self.srv.core.settings.update({"push": {"enabled": True}})

    # ---- the panel ----
    def test_panel_features_and_rules(self):
        self.assertEqual(self.srv.api("GET", "/api/status")[1]["features"], ALL)
        self.assertEqual(self.panel("GET", "/api/overview")["features"], ALL)
        self.app("v2-rules")
        out = self.panel("GET", "/api/apps/v2-rules/rules")
        self.assertEqual(out["retention"], [])
        self.assertEqual(out["publish"], {"enabled": False, "url": f"http://127.0.0.1:{self.srv.port}/_pocket/pub/v2-rules/",
                                          "latest": None})
        self.assertEqual(out["notify"], {"enabled": False, "topic": "pocket-v2-rules-notify", "sent_today": 0, "limit_day": 200})
        self.assertEqual(out["changes"]["keep_days"], 30)
        self.assertEqual(self.srv.api("GET", "/api/apps/no-such/rules", token=self.token)[0], 404)
        self.assertEqual(self.srv.api("PATCH", "/api/apps/v2-rules/rules", {"publish": True}, token=self.token)[0], 400)
        out = self.panel("PATCH", "/api/apps/v2-rules/rules", {"publish": {"enabled": True}, "notify": {"enabled": True}})
        self.assertTrue(out["publish"]["enabled"] and out["notify"]["enabled"])
        events = self.srv.core.stats.query("SELECT kind FROM alerts WHERE subject='v2-rules' AND kind LIKE 'app_%'")
        self.assertEqual({r["kind"] for r in events}, {"app_publish", "app_notify"})
        self.assertIn("retention", {j["key"] for j in self.panel("GET", "/api/jobs")["jobs"]})


class FamilyFeed(unittest.TestCase):
    """The family bucket's changes reach every member app."""

    @classmethod
    def setUpClass(cls):
        cls.srv = PocketServer().start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.stop()

    def test_family_changes(self):
        srv = self.srv
        _, a = srv.enroll("fam-a", "device-000a")
        _, b = srv.enroll("fam-b", "device-000b")
        _, c = srv.enroll("fam-c", "device-000c")
        for app in ("fam-a", "fam-b"):
            srv.core.registry.update_app(app, family=1)
        _, a = srv.enroll("fam-a", "device-000a")
        start = srv.pocket(a, "GET", "/_pocket/changes")[1]["cursor"]
        status, h, body = srv.raw(a, "PUT", "/_pocket/files/family/shared.txt", body=b"for all")
        self.assertEqual(status, 200, body)
        self.assertEqual(srv.raw(b, "GET", "/_pocket/files/family/shared.txt")[2], b"for all")
        for e in (a, b):
            out = srv.pocket(e, "GET", "/_pocket/changes", "since=" + start)[1]["changes"]
            self.assertEqual([(x["bucket"], x["key"]) for x in out], [("family", "shared.txt")])
        self.assertEqual(srv.pocket(c, "GET", "/_pocket/changes", "since=" + start)[1]["changes"], [])
        self.assertEqual(json.loads(srv.raw(c, "GET", "/_pocket/files/family/shared.txt")[2])["error"], "NoSuchBucket")
        self.assertEqual(srv.pocket(a, "GET", "/_pocket/app")[1]["buckets"], ["shared", "device", "family"])


class ChangeStore(unittest.TestCase):
    """The change table on its own: cursors, expiry and pruning."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="pocket-changes-")
        self.c = Changes(self.tmp)

    def tearDown(self):
        self.c.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_cursors_and_expiry(self):
        c = self.c
        start = c.last_seq()
        self.assertGreater(start, 10 ** 12, "seq starts at the database's creation time in ms")
        s1 = c.record("app", "shared", "a", "put", "app", "dev", 1, '"x"')
        s2 = c.record("app", "device:dev-0002", "b", "put", "app", "dev-0002", 1, '"y"')
        self.assertEqual(c.check(str(start), ("app",)), start)
        recs, cursor, more = c.fetch("app", "dev-0001", False, start, 10)
        self.assertEqual([r["seq"] for r in recs], [s1])
        self.assertEqual(cursor, str(s2), "the cursor skips what this device can't see")
        with self.assertRaises(CursorExpired):
            c.check(str(s2 + 1), ("app",))
        with self.assertRaises(CursorExpired):
            c.check(str(start - 1), ("app",))
        # a fresh database: earlier cursors are below its floor
        other = tempfile.mkdtemp(prefix="pocket-changes-")
        try:
            time.sleep(0.01)
            fresh = Changes(other)
            with self.assertRaises(CursorExpired):
                fresh.check(str(s1), ("app",))
            fresh.close()
        finally:
            shutil.rmtree(other, ignore_errors=True)

    def test_prune(self):
        c = self.c
        start = c.last_seq()
        now = time.time()
        old = [c.record("app", "shared", f"old{i}", "put", "app", now=now - 40 * 86400) for i in range(3)]
        new = [c.record("app", "shared", f"new{i}", "put", "app", now=now) for i in range(5)]
        c.record("other", "shared", "x", "put", "app", now=now)
        self.assertEqual(c.prune(now=now, keep_per_app=3), 5)
        self.assertEqual(c.count("app"), 3)
        self.assertEqual(c.count("other"), 1)
        with self.assertRaises(CursorExpired):
            c.check(str(start), ("app",))
        with self.assertRaises(CursorExpired):
            c.check(str(old[-1]), ("app",))
        self.assertEqual(c.check(str(new[1]), ("app",)), new[1])
        self.assertEqual(c.check(str(start), ("other",)), start, "another app's cursor still holds")
        recs, _, _ = c.fetch("app", "dev-0001", False, new[1], 10)
        self.assertEqual([r["key"] for r in recs], ["new2", "new3", "new4"])

    def test_family_visibility(self):
        c = self.c
        start = c.last_seq()
        c.record(FAMILY, "shared", "f", "put", "app")
        self.assertEqual(len(c.fetch("a", "d-000001", True, start, 10)[0]), 1)
        self.assertEqual(len(c.fetch("a", "d-000001", False, start, 10)[0]), 0)
        self.assertEqual(c.fetch("a", "d-000001", True, start, 10)[0][0]["bucket"], "family")


if __name__ == "__main__":
    unittest.main()
