"""Scheduled jobs, health checks and the supervisor, against the real server."""

import datetime
import io
import json
import os
import threading
import time
import unittest

import botocore

from helpers import GB, PocketServer
from pocket import server as srvmod

DAY = 86400


def age(path, days):
    t = time.time() - days * DAY
    os.utime(path, (t, t))


class JobTests(unittest.TestCase):
    def setUp(self):
        self.srv = PocketServer().start()
        self.core = self.srv.core
        st, self.e = self.srv.enroll("jobs", "device-0001", "PC")
        self.c = self.srv.client(self.e)

    def tearDown(self):
        self.srv.stop()

    def put(self, bucket, key, body, days_old=0):
        self.c.put_object(Bucket=bucket, Key=key, Body=body)
        sub = "shared" if bucket == "shared" else os.path.join("devices", "device-0001")
        p = self.srv.files("int", "apps", "jobs", sub, *key.split("/"))
        if days_old:
            age(p, days_old)
        return p

    def test_nightly_move_and_immediate_reads(self):
        old = self.put("shared", "old/a.txt", b"old shared", days_old=10)
        priv = self.put("device", "old/p.txt", b"old private", days_old=10)
        new = self.put("shared", "new.txt", b"new", days_old=1)
        # warm rclone's listing cache, as a busy app would have
        self.c.list_objects_v2(Bucket="shared", Prefix="old/")
        self.c.get_object(Bucket="shared", Key="old/a.txt")["Body"].read()
        result, detail = self.core.jobs.run(srvmod.JOB_MOVE, self.core.jobs.move)
        self.assertEqual(result, "ok", detail)
        self.assertFalse(os.path.exists(old))
        self.assertFalse(os.path.exists(priv))
        self.assertTrue(os.path.exists(new), "files changed within the move age stay on internal storage")
        self.assertTrue(os.path.isfile(self.srv.files("sd", "apps", "jobs", "shared", "old", "a.txt")))
        # read straight away: no stale cache
        self.assertEqual(self.c.get_object(Bucket="shared", Key="old/a.txt")["Body"].read(), b"old shared")
        self.assertEqual(self.c.get_object(Bucket="device", Key="old/p.txt")["Body"].read(), b"old private")
        keys = [o["Key"] for o in self.c.list_objects_v2(Bucket="shared")["Contents"]]
        self.assertEqual(sorted(keys), ["new.txt", "old/a.txt"])
        # modification time kept, so it is still "old"
        self.assertLess(os.path.getmtime(self.srv.files("sd", "apps", "jobs", "shared", "old", "a.txt")), time.time() - 9 * DAY)
        # overwrite and delete of a moved file still work, and the undo keeps the SD copy
        self.c.put_object(Bucket="shared", Key="old/a.txt", Body=b"rewritten")
        self.assertEqual(self.c.get_object(Bucket="shared", Key="old/a.txt")["Body"].read(), b"rewritten")
        self.c.delete_object(Bucket="device", Key="old/p.txt")
        with self.assertRaises(botocore.exceptions.ClientError):
            self.c.head_object(Bucket="device", Key="old/p.txt")
        sd_undo = [f for _, _, fs in os.walk(self.srv.files("sd", "_undo")) for f in fs]
        self.assertTrue(any(f.startswith("p.txt~") for f in sd_undo))
        self.assertTrue(any(f.startswith("a.txt~") for f in sd_undo))
        rows = self.core.stats.query("SELECT job, result, detail FROM jobs")
        self.assertEqual(rows[-1]["job"], srvmod.JOB_MOVE)

    def test_per_app_move_age(self):
        p = self.put("shared", "three-days.txt", b"x", days_old=3)
        self.core.jobs.move()
        self.assertTrue(os.path.exists(p))
        self.core.registry.update_app("jobs", move_days=2)
        self.core.jobs.move()
        self.assertFalse(os.path.exists(p))

    def test_names_differing_only_in_case_stay_on_internal(self):
        clash = self.put("shared", "data/Data.csv", b"upper", days_old=10)
        folder = self.put("shared", "Reports/a.csv", b"in Reports", days_old=10)
        plain = self.put("shared", "other/x.csv", b"no clash", days_old=10)
        # what earlier moves left on the card: a file and a folder whose names differ only in case
        for rel, body in (("data/data.csv", b"lower"), ("reports/b.csv", b"in reports")):
            p = self.srv.files("sd", "apps", "jobs", "shared", *rel.split("/"))
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "wb") as f:
                f.write(body)
        result, detail = self.core.jobs.move()
        self.assertEqual(result, "warn", detail)
        self.assertIn("2 kept on internal storage", detail)
        self.assertTrue(os.path.exists(clash) and os.path.exists(folder), "clashing names stay on internal storage")
        self.assertFalse(os.path.exists(plain), "everything else still moves")
        alerts = [a for a in self.core.stats.active_alerts() if a["kind"] == "case_conflict"]
        self.assertEqual(len(alerts), 1)
        self.assertIn("jobs/shared/data/Data.csv", alerts[0]["text"])
        self.assertIn("jobs/shared/Reports/a.csv", alerts[0]["text"])
        # once the other spellings are gone, the files move and the alert clears
        os.remove(self.srv.files("sd", "apps", "jobs", "shared", "data", "data.csv"))
        os.remove(self.srv.files("sd", "apps", "jobs", "shared", "reports", "b.csv"))
        os.rmdir(self.srv.files("sd", "apps", "jobs", "shared", "reports"))
        result, detail = self.core.jobs.move()
        self.assertEqual(result, "ok", detail)
        self.assertFalse(os.path.exists(clash) or os.path.exists(folder))
        self.assertFalse([a for a in self.core.stats.active_alerts() if a["kind"] == "case_conflict"])

    def test_early_move_frees_internal_pool(self):
        paths = [self.put("shared", f"f{i}.bin", os.urandom(200_000), days_old=2 + i) for i in range(5)]
        recent = self.put("shared", "fresh.bin", os.urandom(200_000))
        self.core.usage.scan()
        used = self.core.usage.pool()["int_used"]
        # make the internal pool so small that it is over the early-move level
        self.core.settings.update({"pool": {"internal_gb": used / GB * 1.02}})
        result, detail = self.core.jobs.move(early=True)
        moved = {p for p in paths if not os.path.exists(p)}
        self.assertTrue(moved, detail)
        self.assertLess(len(moved), len(paths), "stops once the pool is back under the early-move level")
        self.assertEqual(moved, set(paths[-len(moved):]), "oldest files go first")   # paths[4] is the oldest
        self.assertTrue(os.path.exists(recent), "files changed in the last 24 h are never moved")

    def test_nightly_copy_mirrors_internal_and_moves_undo(self):
        self.put("shared", "a.txt", b"aaa")
        self.put("device", "b.txt", b"bbb")
        self.c.put_object(Bucket="shared", Key="a.txt", Body=b"aaa2")      # leaves an undo copy on internal storage
        result, detail = self.core.jobs.copy()
        self.assertEqual(result, "ok", detail)
        mirror = os.path.join(self.srv.sd_root, "_copy", "apps", "jobs")
        self.assertEqual(open(os.path.join(mirror, "shared", "a.txt"), "rb").read(), b"aaa2")
        self.assertEqual(open(os.path.join(mirror, "devices", "device-0001", "b.txt"), "rb").read(), b"bbb")
        self.assertEqual([f for _, _, fs in os.walk(self.srv.files("int", "_undo")) for f in fs], [])
        sd_undo = [f for _, _, fs in os.walk(self.srv.files("sd", "_undo")) for f in fs]
        self.assertIn("index.jsonl", sd_undo)
        self.assertTrue(any(f.startswith("a.txt~") for f in sd_undo))
        # once a file is moved or deleted, it leaves the mirror
        self.c.delete_object(Bucket="device", Key="b.txt")
        self.core.jobs.copy()
        self.assertFalse(os.path.exists(os.path.join(mirror, "devices", "device-0001", "b.txt")))

    def test_jobs_wait_for_the_phone_to_cool_down(self):
        jobs = self.core.jobs
        readings = iter([(True, 45.0), (True, 44.0), (False, 39.0)])
        jobs.cool = lambda: next(readings)
        slept = []
        jobs._sleep = slept.append
        self.put("shared", "old", b"x", days_old=10)
        result, detail = jobs.run("Nightly move to SD card", jobs.move)
        self.assertEqual((result, slept), ("ok", [60, 60]))
        self.assertEqual(self.where("old"), ["sd"])
        rows = self.core.stats.query("SELECT subject, cleared FROM alerts WHERE kind='thermal_pause'")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["subject"], "Nightly move to SD card")
        self.assertIsNotNone(rows[0]["cleared"], "the alert is cleared once the job goes on")
        jobs.cool = lambda: (True, 50.0)           # never cools down: the job gives up after the maximum wait
        result, detail = jobs.run("Nightly copy to SD card", jobs.copy)
        self.assertEqual(result, "warn")
        self.assertIn("too hot", detail)
        self.assertFalse([a for a in self.core.stats.active_alerts() if a["kind"] == "thermal_pause"])

    def test_nightly_copy_tolerates_the_card_rounding_times(self):
        """FAT32 keeps modification times to 2 s: a copy whose time is off by that much is still up to date."""
        self.put("shared", "doc.txt", b"text", days_old=1)
        self.core.jobs.run("Nightly copy to SD card", self.core.jobs.copy)
        copy = self.srv.files("sd", "_copy", "apps", "jobs", "shared", "doc.txt")
        st = os.stat(copy)
        os.utime(copy, ns=(st.st_atime_ns, st.st_mtime_ns - 1_500_000_000))
        result, detail = self.core.jobs.run("Nightly copy to SD card", self.core.jobs.copy)
        self.assertTrue(detail.startswith("0 files"), detail)
        os.utime(copy, ns=(st.st_atime_ns, st.st_mtime_ns - 5_000_000_000))
        result, detail = self.core.jobs.run("Nightly copy to SD card", self.core.jobs.copy)
        self.assertTrue(detail.startswith("1 files"), detail)

    def test_undo_cleanup(self):
        old_day = (datetime.date.today() - datetime.timedelta(days=20)).isoformat()
        recent_day = (datetime.date.today() - datetime.timedelta(days=3)).isoformat()
        for day in (old_day, recent_day):
            d = self.srv.files("sd", "_undo", day, "jobs", "shared")
            os.makedirs(d)
            open(os.path.join(d, "x~1"), "w").write("x")
        result, detail = self.core.jobs.undo_cleanup()
        self.assertFalse(os.path.exists(self.srv.files("sd", "_undo", old_day)))
        self.assertTrue(os.path.exists(self.srv.files("sd", "_undo", recent_day)))
        self.assertIn("1 files", detail)

    def test_integrity_check_finds_damage(self):
        self.put("shared", "keep.bin", os.urandom(50_000), days_old=10)
        self.core.jobs.move()
        result, detail = self.core.jobs.integrity()
        self.assertEqual(result, "ok", detail)
        p = self.srv.files("sd", "apps", "jobs", "shared", "keep.bin")
        st = os.stat(p)
        with open(p, "r+b") as f:          # flip one byte, keep size and time: silent card damage
            b = f.read(1)
            f.seek(0)
            f.write(bytes([b[0] ^ 0xFF]))
        os.utime(p, ns=(st.st_atime_ns, st.st_mtime_ns))
        result, detail = self.core.jobs.run(srvmod.JOB_INTEGRITY, self.core.jobs.integrity)
        self.assertEqual(result, "fail")
        self.assertIn("keep.bin", detail)
        kinds = [a["kind"] for a in self.core.stats.active_alerts()]
        self.assertIn("integrity", kinds)

    def test_scheduler_catches_up_missed_runs(self):
        now = datetime.datetime(2026, 10, 11, 2, 0)        # Sunday, before 03:00
        self.assertNotIn("nightly", self.core.due_jobs(now))
        self.assertIn("nightly", self.core.due_jobs(now.replace(hour=3, minute=5)))
        self.core.jobs.run(srvmod.JOB_MOVE, lambda: ("ok", ""))
        self.assertNotIn("nightly", self.core.due_jobs(datetime.datetime.now().replace(hour=23)))
        # integrity: due if never run; not due again right after running
        self.assertIn(srvmod.JOB_INTEGRITY, self.core.due_jobs(datetime.datetime.now()))
        self.core.jobs.run(srvmod.JOB_INTEGRITY, lambda: ("ok", ""))
        self.assertNotIn(srvmod.JOB_INTEGRITY, self.core.due_jobs(datetime.datetime.now()))

    def test_health_alerts(self):
        core = self.core
        core.registry.set_allocation("jobs", 100_000, core.usage.pool()["total"], 0)
        self.put("shared", "big", b"x" * 90_000)
        checks = dict((n, ok) for n, ok, _ in core.health.run())
        self.assertTrue(checks["Storage server"] and checks["Gateway"] and checks["SD card"])
        self.assertFalse(checks["App level jobs"])
        kinds = {(a["kind"], a["subject"]) for a in core.stats.active_alerts()}
        self.assertIn(("app_warn", "jobs"), kinds)
        self.c.delete_object(Bucket="shared", Key="big")
        core.health.run()
        kinds = {(a["kind"], a["subject"]) for a in core.stats.active_alerts()}
        self.assertNotIn(("app_warn", "jobs"), kinds)

    def where(self, key):
        return [s for s in ("int", "sd") if os.path.isfile(self.srv.files(s, "apps", "jobs", "shared", key))]

    def test_internal_pool_full_switches_every_key_to_sd(self):
        core = self.core
        self.put("shared", "fill.bin", os.urandom(300_000))
        self.c.list_objects_v2(Bucket="shared")              # this key's view is now held by rclone
        core.usage.scan()
        used = core.usage.pool()["int_used"]
        core.settings.update({"pool": {"internal_gb": used / GB}})                 # 100% full
        self.assertTrue(core.update_flags())
        self.assertTrue(json.load(open(os.path.join(self.srv.state, "flags.json")))["internal_full"])
        self.c.put_object(Bucket="shared", Key="after-full.txt", Body=b"to the card")   # already-connected key
        self.assertEqual(self.where("after-full.txt"), ["sd"])
        _, e2 = self.srv.enroll("jobs", "device-0002", "Laptop")                    # and a brand-new one
        self.srv.client(e2).put_object(Bucket="shared", Key="new-device.txt", Body=b"x")
        self.assertEqual(self.where("new-device.txt"), ["sd"])
        self.assertEqual(self.c.get_object(Bucket="shared", Key="fill.bin")["ContentLength"], 300_000)
        # 90% full: still above the 85% way-back level, so writes stay on the card
        core.settings.update({"pool": {"internal_gb": used / GB / 0.90}})
        self.assertTrue(core.update_flags())
        # 80%: back to internal storage
        core.settings.update({"pool": {"internal_gb": used / GB / 0.80}})
        self.assertFalse(core.update_flags())
        self.c.put_object(Bucket="shared", Key="back.txt", Body=b"internal again")
        self.assertEqual(self.where("back.txt"), ["int"])
        self.assertEqual(core.rclone.restarts, 0, "planned restarts are not counted as crashes")

    def test_requests_during_the_switch_are_held_not_lost(self):
        core = self.core
        data = os.urandom(3 * 1024 * 1024)

        class SlowBody(io.BytesIO):          # an upload that takes a few seconds
            def read(self, n=-1):
                time.sleep(0.02)
                return super().read(65536 if n is None or n < 0 else min(n, 65536))

        results, finished = {}, {}

        def slow_upload():
            try:
                self.srv.client(self.e).put_object(Bucket="shared", Key="slow.bin", Body=SlowBody(data))
                results["slow"] = "ok"
            except Exception as ex:
                results["slow"] = repr(ex)
            finished["slow"] = time.time()

        def quick_upload():
            try:
                self.srv.client(self.e).put_object(Bucket="shared", Key="quick.txt", Body=b"held, then stored")
                results["quick"] = "ok"
            except Exception as ex:
                results["quick"] = repr(ex)

        t = threading.Thread(target=slow_upload)
        t.start()
        time.sleep(0.7)                       # the slow upload is now in flight
        core.usage.scan()
        core.settings.update({"pool": {"internal_gb": core.usage.pool()["int_used"] / GB}})
        q = threading.Timer(0.2, quick_upload)  # arrives while the gateway is holding requests
        q.start()
        t0 = time.time()
        self.assertTrue(core.update_flags())
        took = time.time() - t0
        t.join()
        q.join()
        self.assertEqual(results, {"slow": "ok", "quick": "ok"})
        self.assertGreater(finished["slow"], t0, "the slow upload was still running when the switch began")
        self.assertEqual(self.c.get_object(Bucket="shared", Key="slow.bin")["Body"].read(), data)
        self.assertEqual(self.c.get_object(Bucket="shared", Key="quick.txt")["Body"].read(), b"held, then stored")
        self.assertEqual(self.where("quick.txt"), ["sd"])
        self.assertLess(took, 30)

    def test_supervisor_restarts_rclone(self):
        self.put("shared", "before.txt", b"1")
        self.core.rclone.kill()
        c = self.srv.client(self.e, retries=5)
        deadline = time.time() + 15
        while True:          # the scheduler loop notices within ~2 s and restarts rclone
            try:
                self.assertEqual(c.get_object(Bucket="shared", Key="before.txt")["Body"].read(), b"1")
                break
            except botocore.exceptions.ClientError:
                if time.time() > deadline:
                    raise
                time.sleep(0.5)
        self.assertGreaterEqual(self.core.rclone.restarts, 1)
        c.put_object(Bucket="shared", Key="after.txt", Body=b"2")


if __name__ == "__main__":
    unittest.main()
