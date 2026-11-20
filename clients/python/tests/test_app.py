"""pocket.app() against the fake server. Tests that use /_fake/ controls are named *_fake_only."""

import datetime
import hashlib
import json
import os
import sqlite3
import time
import unittest
from unittest import mock

from base import FakeTestCase, pocket

from pocket_cloud import _app


class OpenTests(FakeTestCase):
    def saved(self):
        with open(os.path.join(self.home, "apps", "test-app.json"), encoding="utf-8") as f:
            return json.load(f)

    def test_first_open_enrolls_and_reuses_the_key(self):
        a = self.open()
        self.assertIn("files", a.features)
        saved = self.saved()
        self.assertTrue(saved["access_key"].startswith("AKFAKE"))
        self.open().quota()
        enrolls = [r for r in self.requests() if r["path"] == "/_pocket/enroll"]
        self.assertEqual(len(enrolls), 1)

    def test_not_configured(self):
        os.remove(os.path.join(self.home, "config"))
        with self.assertRaises(pocket.PocketError) as cm:
            self.open()
        self.assertEqual(cm.exception.code, "NotConfigured")

    def test_wrong_enroll_secret_is_access_denied(self):
        with self.assertRaises(pocket.AccessDenied):
            self.open(enroll_secret="nope", endpoint=self.srv.endpoint)

    def test_missing_feature_raises_feature_missing_fake_only(self):
        self.srv.control("features", {"features": ["quota", "share", "files"]})
        a = self.open()
        a.put("x", b"1")
        for call in (lambda: a.move("x", "y"), lambda: list(a.changes()), lambda: a.notify("hi")):
            with self.assertRaises(pocket.FeatureOff) as cm:
                call()
            self.assertEqual(cm.exception.code, "FeatureMissing")

    def test_old_server_without_features_fake_only(self):
        self.srv.control("features", {"features": []})
        with mock.patch.object(_app.App, "_load_health",
                               lambda self: setattr(self, "_features", list(_app.OLD_FEATURES))):
            a = self.open()
        with self.assertRaises(pocket.FeatureOff):
            a.get("x")

    def test_reenrolls_once_when_the_device_moved_fake_only(self):
        a = self.open()
        a.put("a", b"1")
        self.srv.control("moved", {"times": 1})
        self.assertEqual(a.get("a"), b"1")
        enrolls = [r for r in self.requests() if r["path"] == "/_pocket/enroll"]
        self.assertEqual(len(enrolls), 2)

    def test_gives_up_after_one_reenroll_fake_only(self):
        a = self.open()
        self.srv.control("moved", {"times": 5})
        with self.assertRaises(pocket.AccessDenied):
            a.get("a")

    def test_unknown_key_reenrolls(self):
        a = self.open()
        a._dev.creds = dict(a._dev.creds, access_key="AKFAKE0000000000")
        a.put("k", b"v")
        self.assertEqual(a.get("k"), b"v")

    def test_shares_the_key_with_connect(self):
        self.open()
        saved = self.saved()
        self.assertEqual(set(saved), {"endpoint", "device_id", "access_key", "secret_key", "region", "endpoints"})


class FileTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open()

    def test_put_get_stat_delete(self):
        e = self.a.put("notes/hello world.txt", b"hello")
        self.assertEqual(e.etag, '"5d41402abc4b2a76b9719d911017c592"')
        self.assertEqual((e.bucket, e.key, e.size), ("shared", "notes/hello world.txt", 5))
        self.assertEqual(self.a.get("notes/hello world.txt"), b"hello")
        s = self.a.stat("notes/hello world.txt")
        self.assertEqual((s.size, s.etag), (5, e.etag))
        self.assertIsInstance(s.modified, float)
        self.a.delete("notes/hello world.txt")
        self.assertIsNone(self.a.stat("notes/hello world.txt"))
        with self.assertRaises(pocket.NotFound) as cm:
            self.a.get("notes/hello world.txt")
        self.assertEqual(cm.exception.code, "NoSuchKey")
        with self.assertRaises(pocket.NotFound):
            self.a.delete("notes/hello world.txt")

    def test_str_and_unicode_keys(self):
        self.a.put("ü/日本 & co.txt", "text ✓")
        self.assertEqual(self.a.get("ü/日本 & co.txt").decode(), "text ✓")

    def test_device_bucket_is_private(self):
        self.a.put("mine", b"1", bucket="device")
        other = self.open(device_id="other-device-1")
        self.assertIsNone(other.stat("mine", bucket="device"))
        self.assertEqual(other.stat("mine"), None)
        with self.assertRaises(pocket.NotFound) as cm:
            self.a.get("x", bucket="family")
        self.assertEqual(cm.exception.code, "NoSuchBucket")

    def test_if_match(self):
        e = self.a.put("k", b"1")
        self.a.put("k", b"2", if_match=e.etag)
        with self.assertRaises(pocket.PreconditionFailed):
            self.a.put("k", b"3", if_match=e.etag)
        with self.assertRaises(pocket.PreconditionFailed):
            self.a.delete("k", if_match=e.etag)
        self.assertEqual(self.a.get("k"), b"2")

    def test_files_and_streaming(self):
        src = self.write("big.bin", os.urandom(3 << 20))
        os.utime(src, (1700000000, 1700000000))
        e = self.a.put_file("big.bin", src)
        self.assertEqual(e.modified, 1700000000.0)
        self.assertEqual(e.size, 3 << 20)
        dest = self.path("out", "big.bin")
        got = self.a.get_file("big.bin", dest)
        self.assertEqual(self.read("out/big.bin"), self.read("big.bin"))
        self.assertEqual(got.etag, e.etag)
        self.assertEqual([n for n in os.listdir(self.path("out")) if n.startswith(".pocket-tmp")], [])

    def test_medium_upload_goes_through_100_continue(self):
        src = self.write("two.bin", os.urandom(2 << 20))
        e = self.a.put_file("two.bin", src)
        self.assertEqual(e.size, 2 << 20)
        self.assertEqual(self.a.get("two.bin"), self.read("two.bin"))

    def test_big_upload_refused_early_gives_the_real_error_fake_only(self):
        src = self.write("big.bin", b"\0" * (70 << 20))
        old = self.a.put("big.bin", b"small")
        self.a.put("big.bin", b"changed")
        with self.assertRaises(pocket.PreconditionFailed):
            self.a.put_file("big.bin", src, if_match=old.etag)
        self.srv.control("app", {"read_only": True}, query="app=test-app")
        with self.assertRaises(pocket.ReadOnly):
            self.a.put_file("big.bin", src)
        self.srv.control("app", {"read_only": False}, query="app=test-app")
        self.srv.control("moved", {"times": 1})
        e = self.a.put_file("big.bin", src)                          # re-enrolls, then uploads
        self.assertEqual(e.size, 70 << 20)
        enrolls = [r for r in self.requests() if r["path"] == "/_pocket/enroll"]
        self.assertEqual(len(enrolls), 2)

    def test_bad_key_is_refused_locally(self):
        with self.assertRaises(pocket.PocketError) as cm:
            self.a.put("a//b", b"x")
        self.assertEqual(cm.exception.code, "BadKey")

    def test_list_pages_and_folders(self):
        for i in range(5):
            self.a.put(f"notes/{i}.txt", b"x")
        self.a.put("notes/2026/a.txt", b"x")
        self.a.put("other", b"x")
        keys = [e.key for e in self.a.list("notes/")]
        self.assertEqual(keys, ["notes/0.txt", "notes/1.txt", "notes/2.txt", "notes/2026/a.txt", "notes/3.txt",
                                "notes/4.txt"])
        top = [(e.key, e.size) for e in self.a.list("notes/", folders=True)]
        self.assertIn(("notes/2026/", None), top)
        self.assertNotIn("notes/2026/a.txt", [k for k, _ in top])

    def test_list_follows_cursors_fake_only(self):
        for i in range(1203):
            self.a.put(f"many/{i:05d}", b"")
        self.assertEqual(len(list(self.a.list("many/"))), 1203)
        lists = [r for r in self.requests() if r["path"] == "/_pocket/list"]
        self.assertEqual(len(lists), 2)

    def test_move(self):
        e = self.a.put("inbox/a.txt", b"hello")
        m = self.a.move("inbox/a.txt", "done/a.txt")
        self.assertEqual((m.key, m.etag), ("done/a.txt", e.etag))
        self.assertIsNone(self.a.stat("inbox/a.txt"))
        self.a.put("inbox/b.txt", b"b")
        with self.assertRaises(pocket.PreconditionFailed) as cm:
            self.a.move("inbox/b.txt", "done/a.txt")
        self.assertEqual(cm.exception.code, "KeyExists")
        self.a.move("inbox/b.txt", "done/a.txt", overwrite=True)
        self.assertEqual(self.a.get("done/a.txt"), b"b")
        self.a.move("done/a.txt", "private.txt", to_bucket="device")
        self.assertEqual(self.a.get("private.txt", bucket="device"), b"b")

    def test_json(self):
        self.assertEqual(self.a.get_json("cfg.json", default={"n": 0}), {"n": 0})
        with self.assertRaises(pocket.NotFound):
            self.a.get_json("cfg.json")
        self.a.put_json("cfg.json", {"n": 1, "name": "ü"})
        self.assertEqual(self.a.get_json("cfg.json"), {"n": 1, "name": "ü"})
        self.assertEqual(self.a.stat("cfg.json").size, len(self.a.get("cfg.json")))

    def test_read_only_and_quota_fake_only(self):
        self.srv.control("app", {"read_only": True}, query="app=test-app")
        with self.assertRaises(pocket.ReadOnly):
            self.a.put("k", b"1")
        self.srv.control("app", {"read_only": False, "allocation": 10}, query="app=test-app")
        self.a.put("k", b"12345")
        with self.assertRaises(pocket.QuotaExceeded):
            self.a.put("k2", b"123456")
        q = self.a.quota()
        self.assertEqual((q["allocation"], q["used_app"], q["free"]), (10, 5, 5))

    def test_retries_reads_and_conditional_writes_fake_only(self):
        self.a.put("k", b"1")
        self.srv.control("fail", {"route": "GET /_pocket/files", "status": 503, "code": "ServiceUnavailable",
                                  "times": 2})
        self.assertEqual(self.a.get("k"), b"1")
        self.srv.control("fail", {"route": "GET /_pocket/files", "status": 503, "code": "ServiceUnavailable",
                                  "times": 4})
        with self.assertRaises(pocket.Unreachable):
            self.a.get("k")
        self.srv.control("fail", {"route": "PUT /_pocket/files", "status": 503, "code": "ServiceUnavailable",
                                  "times": 1})
        with self.assertRaises(pocket.Unreachable):
            self.a.put("k", b"2")                 # unconditional: not retried
        e = self.a.stat("k")
        self.srv.control("fail", {"route": "PUT /_pocket/files", "status": 503, "code": "ServiceUnavailable",
                                  "times": 1})
        self.a.put("k", b"2", if_match=e.etag)    # conditional: retried
        self.assertEqual(self.a.get("k"), b"2")

    def test_unreachable(self):
        with self.assertRaises(pocket.Unreachable) as cm:
            pocket.app("dead-app", home=self.home, endpoint="http://127.0.0.1:1", timeout=2)
        self.assertEqual(cm.exception.code, "Unreachable")
        self.a._dev.endpoint = "http://127.0.0.1:1"      # a saved key, server gone
        with self.assertRaises(pocket.Unreachable):
            self.a.quota()


class BackupTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open()

    def test_backup_keeps_the_newest(self):
        src = self.write("notes.txt", "v")
        base = datetime.datetime(2026, 11, 20, 12, 0, 0, tzinfo=datetime.timezone.utc)
        for i in range(5):
            with mock.patch.object(_app, "_utcnow", return_value=base + datetime.timedelta(seconds=i)):
                e = self.a.backup(src, keep=3)
        self.assertEqual(e.key, "backups/notes.txt/20261120-120004-notes.txt")
        keys = [x.key for x in self.a.list("backups/notes.txt/")]
        self.assertEqual(keys, [f"backups/notes.txt/20261120-12000{i}-notes.txt" for i in (2, 3, 4)])
        out = self.a.restore_latest("backups/notes.txt/", self.path("restored.txt"))
        self.assertEqual(out.key, keys[-1])
        self.assertEqual(self.read("restored.txt"), b"v")
        self.assertIsNone(self.a.restore_latest("backups/none/", self.path("x")))

    def test_device_clock_behind_never_deletes_the_new_backup(self):
        src = self.write("notes.txt", "v")
        now = datetime.datetime(2026, 11, 20, 12, 0, 0, tzinfo=datetime.timezone.utc)
        for i in range(3):
            with mock.patch.object(_app, "_utcnow", return_value=now + datetime.timedelta(minutes=i)):
                self.a.backup(src, keep=3)
        behind = now - datetime.timedelta(days=30)                   # this device's clock went back a month
        with mock.patch.object(_app, "_utcnow", return_value=behind):
            e = self.a.backup(src, keep=3)
        keys = [x.key for x in self.a.list("backups/notes.txt/")]
        self.assertIn(e.key, keys)
        self.assertEqual(len(keys), 3)
        self.assertNotIn("backups/notes.txt/20261120-120000-notes.txt", keys, "the oldest upload went")

    def test_sqlite_in_use_is_copied_safely(self):
        db = self.path("ledger.db")
        con = sqlite3.connect(db)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("CREATE TABLE t (n INTEGER)")
        con.executemany("INSERT INTO t VALUES (?)", [(i,) for i in range(1000)])
        con.commit()                               # rows sit in the WAL, not yet in the main file
        try:
            e = self.a.backup(db)
            self.a.get_file(e.key, self.path("copy.db"))
            c2 = sqlite3.connect(self.path("copy.db"))
            self.assertEqual(c2.execute("SELECT COUNT(*) FROM t").fetchone()[0], 1000)
            self.assertEqual(c2.execute("PRAGMA integrity_check").fetchone()[0], "ok")
            c2.close()
        finally:
            con.close()


class ChangeTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open()

    def test_cursor_now_then_changes(self):
        feed = self.a.changes()
        self.assertEqual(list(feed), [])
        start = feed.cursor
        self.a.put("a", b"1")
        self.a.move("a", "b")
        self.a.delete("b")
        self.a.put("p", b"1", bucket="device")
        other = self.open(device_id="other-device-1")
        other.put("theirs", b"x", bucket="device")
        feed = self.a.changes(start)
        got = [(c.kind, c.bucket, c.key) for c in feed]
        self.assertEqual(got, [("put", "shared", "a"), ("move", "shared", "b"), ("delete", "shared", "b"),
                               ("put", "device", "p")])
        moved = [c for c in self.a.changes(start) if c.kind == "move"][0]
        self.assertEqual(moved.from_, {"bucket": "shared", "key": "a"})
        self.assertEqual(moved.device, self.a.device_id)
        self.assertEqual(list(self.a.changes(feed.cursor)), [])

    def test_long_poll(self):
        feed = self.a.changes()
        list(feed)
        import threading
        threading.Timer(0.3, lambda: self.open(device_id="other-device-1").put("late", b"1")).start()
        t = time.time()
        got = list(self.a.changes(feed.cursor, wait=5))
        self.assertEqual([c.key for c in got], ["late"])
        self.assertLess(time.time() - t, 4)

    def test_long_poll_longer_than_the_timeout(self):
        a = self.open(timeout=1)
        feed = a.changes()
        list(feed)
        t = time.time()
        self.assertEqual(list(a.changes(feed.cursor, wait=2)), [])
        self.assertGreaterEqual(time.time() - t, 1.5)

    def test_cursor_expired_fake_only(self):
        feed = self.a.changes()
        list(feed)
        old = feed.cursor
        for i in range(3):
            self.a.put(f"k{i}", b"x")
        newest = list(self.a.changes(old))
        self.srv.control("expire-changes", {"before": str(newest[-1].seq)})
        with self.assertRaises(pocket.CursorExpired) as cm:
            list(self.a.changes(old))
        self.assertTrue(cm.exception.cursor)

    def test_on_change(self):
        seen = []
        h = self.a.on_change(seen.append)
        time.sleep(0.3)
        self.a.put("x", b"1")
        for _ in range(50):
            if seen:
                break
            time.sleep(0.1)
        h.stop(timeout=0)
        self.assertEqual([c.key for c in seen], ["x"])


class PublishTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open("egx-analyzer")

    def test_publish_and_download_through_the_publish_folder_fake_only(self):
        self.srv.control("app", {"publish": True}, query="app=egx-analyzer")
        self.a.info()
        apk = self.write("egx analyzer-5.9.1.apk", os.urandom(50000))
        rel = self.a.publish(apk, "5.9.1", notes="Faster reports.", platform="android-arm64")
        self.assertEqual(rel.version, "5.9.1")
        self.assertTrue(rel.files[0].url.endswith("/_pocket/pub/egx-analyzer/5.9.1/egx%20analyzer-5.9.1.apk"))
        manifest = self.a.get_json("releases/latest.json")
        self.assertEqual(manifest["files"][0]["sha256"], hashlib.sha256(self.read("egx analyzer-5.9.1.apk")).hexdigest())
        latest = self.a.releases().latest(platform="android-arm64")
        self.assertEqual((latest.version, latest.notes), ("5.9.1", "Faster reports."))
        self.assertIsNone(self.a.releases().latest(platform="windows-x64"))
        paths = latest.download(self.path("dl") + os.sep)
        self.assertEqual(self.read("dl/egx analyzer-5.9.1.apk"), self.read("egx analyzer-5.9.1.apk"))
        self.assertEqual(len(paths), 1)
        pubs = [r for r in self.requests() if r["path"].startswith("/_pocket/pub/")]
        self.assertTrue(pubs and all(r["status"] == 200 for r in pubs))
        self.assertTrue(self.a.releases_url().endswith("/_pocket/pub/egx-analyzer/"))

    def test_publish_off_falls_back_to_signed_reads(self):
        self.a.publish(self.write("a.zip", b"one"), "1.0")
        self.a.publish(self.write("b.zip", b"two"), "1.1")
        latest = self.a.releases().latest()
        self.assertEqual(latest.version, "1.1")
        self.assertIsNone(latest.files[0].url)
        latest.download(self.path("got.zip"))
        self.assertEqual(self.read("got.zip"), b"two")
        self.assertEqual(self.a.get("releases/1.0/a.zip"), b"one")

    def test_checksum_mismatch(self):
        rel = self.a.publish(self.write("a.zip", b"one"), "1.0")
        self.a.put("releases/1.0/a.zip", b"tampered")
        with self.assertRaises(pocket.PocketError) as cm:
            self.a.releases().latest().download(self.path("x.zip"))
        self.assertEqual(cm.exception.code, "ChecksumMismatch")
        self.assertFalse(os.path.exists(self.path("x.zip")))
        self.assertEqual(rel.files[0].size, 3)

    def test_no_release_and_bad_version(self):
        self.assertIsNone(self.a.releases().latest())
        with self.assertRaises(pocket.PocketError):
            self.a.publish(self.write("a.zip", b"x"), "1.0 beta")

    def test_concurrent_manifest_write_retries_fake_only(self):
        self.a.publish(self.write("a.zip", b"one"), "1.0")
        self.srv.control("fail", {"route": "PUT /_pocket/files/shared/releases/latest.json", "status": 412,
                                  "code": "PreconditionFailed", "times": 1})
        self.a.publish(self.write("b.zip", b"two"), "2.0")
        self.assertEqual(self.a.get_json("releases/latest.json")["version"], "2.0")


class NotifyShareTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open("egx")

    def test_notify_off_by_default(self):
        with self.assertRaises(pocket.FeatureOff) as cm:
            self.a.notify("hello")
        self.assertEqual(cm.exception.code, "FeatureOff")

    def test_notify_and_rate_limit_fake_only(self):
        self.srv.control("app", {"notify": True}, query="app=egx")
        r = self.a.notify("6 new calls", title="Evening calls are ready", tags=["chart_with_upwards_trend"],
                          priority=4, click="egx://calls")
        self.assertEqual(r["topic"], "pocket-egx-notify")
        sent = self.srv.control("notifications", method="GET")["sent"]
        self.assertEqual((sent[0]["title"], sent[0]["priority"], sent[0]["tags"]),
                         ("Evening calls are ready", 4, ["chart_with_upwards_trend"]))
        for _ in range(9):
            self.a.notify("x")
        with self.assertRaises(pocket.RateLimited) as cm:
            self.a.notify("one too many")
        self.assertGreater(cm.exception.retry_after, 0)
        with self.assertRaises(pocket.PocketError) as cm:
            self.a.notify("x", tags=["Not Valid"])

    def test_share(self):
        self.a.put("report.pdf", b"%PDF")
        s = self.a.share("report.pdf", expires=3600, max_downloads=2)
        self.assertTrue(s.token)
        self.assertEqual((s["key"], s.max_downloads), ("report.pdf", 2))
        with self.assertRaises(pocket.NotFound):
            self.a.share("missing.pdf")

    def test_info(self):
        info = self.a.info()
        self.assertEqual(info["app"], "egx")
        self.assertIn("files", info["features"])


class ErrorTypeTests(unittest.TestCase):
    def test_hierarchy(self):
        for cls in (pocket.NotFound, pocket.PreconditionFailed, pocket.QuotaExceeded, pocket.ReadOnly,
                    pocket.AccessDenied, pocket.CursorExpired, pocket.RateLimited, pocket.FeatureOff,
                    pocket.Unreachable):
            self.assertTrue(issubclass(cls, pocket.PocketError))
        e = pocket.PocketError("plain")
        self.assertEqual((str(e), e.code, e.status), ("plain", "PocketError", 0))

    def test_mapping(self):
        from pocket_cloud._errors import error_for
        cases = {"NoSuchKey": pocket.NotFound, "KeyExists": pocket.PreconditionFailed,
                 "PoolFull": pocket.QuotaExceeded, "PhoneStorageLow": pocket.QuotaExceeded,
                 "ReadOnly": pocket.ReadOnly, "RequestTimeTooSkewed": pocket.AccessDenied,
                 "ServiceUnavailable": pocket.Unreachable, "BadDigest": pocket.PocketError}
        for code, cls in cases.items():
            self.assertIs(type(error_for(400, code, "m")), cls, code)
        e = error_for(410, "CursorExpired", "m", {"cursor": "12"})
        self.assertEqual(e.cursor, "12")


if __name__ == "__main__":
    unittest.main()
