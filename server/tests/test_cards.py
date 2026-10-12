"""Swappable SD cards (cards.py), with folders standing in for cards: tmp/cards/<volume>/files is a card in the
phone; renaming the volume folder away pulls the card out."""

import csv
import json
import os
import time
import unittest

import botocore

from helpers import GB, PocketServer
from pocket import panel_api

DAY = 86400


class CardServer:
    def __init__(self):
        self.srv = PocketServer(sd_root="", sd={"search": "", "poll_s": 1, "speed_test_mb": 1})
        self.cards_dir = os.path.join(self.srv.tmp, "cards")
        self.out_dir = os.path.join(self.srv.tmp, "pulled")
        os.makedirs(self.cards_dir)
        os.makedirs(self.out_dir)
        self.srv.overrides["sd"]["search"] = os.path.join(self.cards_dir, "*", "files")
        self.srv.start()
        self.core = self.srv.core

    def insert(self, volume, with_folder=True):
        src = os.path.join(self.out_dir, volume)
        dst = os.path.join(self.cards_dir, volume)
        if os.path.isdir(src):
            os.rename(src, dst)
        else:
            os.makedirs(os.path.join(dst, "files") if with_folder else dst)
        self.core.cards.poll()

    def pull(self, volume):
        os.rename(os.path.join(self.cards_dir, volume), os.path.join(self.out_dir, volume))
        self.core.cards.poll()

    def pocket(self, volume):
        here = os.path.join(self.cards_dir, volume, "files", "pocket")
        return here if os.path.isdir(here) else os.path.join(self.out_dir, volume, "files", "pocket")

    def wait(self):
        end = time.time() + 120
        while self.core.cards.job and self.core.cards.job["running"] and time.time() < end:
            time.sleep(0.1)
        job = self.core.cards.job
        assert job and not job["running"], "card job did not finish"
        assert not job["error"], job["error"]
        return job["result"]

    def use_new(self, volume="AAAA-0001"):
        if not os.path.isdir(os.path.join(self.cards_dir, volume)):
            self.insert(volume)
        self.core.cards.use_new({})
        return self.wait()

    def put(self, client, key, body, days_old=0, app="cards", bucket="shared"):
        client.put_object(Bucket=bucket, Key=key, Body=body)
        if days_old:
            sub = "shared" if bucket == "shared" else os.path.join("devices", "device-0001")
            p = os.path.join(self.core.layout.apps_root("int"), app, sub, *key.split("/"))
            t = time.time() - days_old * DAY
            os.utime(p, (t, t))

    def stop(self):
        self.srv.stop()


def marker(pocket):
    with open(os.path.join(pocket, ".pocket-card.json"), encoding="utf-8") as f:
        return json.load(f)


def keys(client, bucket="shared"):
    return sorted(o["Key"] for o in client.list_objects_v2(Bucket=bucket).get("Contents", []))


def read(client, key, bucket="shared"):
    return client.get_object(Bucket=bucket, Key=key)["Body"].read()


class CardTests(unittest.TestCase):
    def setUp(self):
        self.cs = CardServer()
        self.core = self.cs.core
        st, self.e = self.cs.srv.enroll("cards", "device-0001", "PC")
        self.assertEqual(st, 200)
        self.c = self.cs.srv.client(self.e)

    def tearDown(self):
        self.cs.stop()

    def alerts(self, kind):
        return [a for a in self.core.stats.active_alerts() if a["kind"] == kind]

    # ------------------------------------------------------------------
    def test_no_card_then_a_new_card(self):
        self.assertFalse(self.core.layout.has_sd)
        self.assertIsNone(self.core.cards.status()["slot"])
        self.cs.put(self.c, "old/a.txt", b"before the card", days_old=10)
        self.assertEqual(read(self.c, "old/a.txt"), b"before the card", "works with internal storage only")
        self.assertEqual(self.core.usage.pool()["sd_limit"], 0)

        self.cs.insert("AAAA-0001")
        self.assertEqual(self.core.cards.status()["slot"]["state"], "new")
        self.assertTrue(self.alerts("new_card"))
        result = self.cs.use_new("AAAA-0001")
        self.assertIn("write_mbs", result)
        st = self.core.cards.status()
        self.assertEqual(st["slot"]["state"], "active")
        self.assertEqual(st["active"]["name"], "Card 1")
        self.assertTrue(self.core.layout.has_sd)
        self.assertFalse(self.alerts("new_card"))
        self.assertEqual(marker(self.cs.pocket("AAAA-0001"))["state"], "active")
        moved = os.path.join(self.cs.pocket("AAAA-0001"), "apps", "cards", "shared", "old", "a.txt")
        self.assertTrue(os.path.isfile(moved), "old files moved onto the new card straight away")
        self.assertEqual(read(self.c, "old/a.txt"), b"before the card")
        self.assertGreater(self.core.usage.pool()["sd_limit"], 0)

    def test_card_without_termux_folder_waits_for_setup(self):
        self.cs.insert("BBBB-0002", with_folder=False)
        self.assertEqual(self.core.cards.status()["slot"]["state"], "needs_setup")
        os.makedirs(os.path.join(self.cs.cards_dir, "BBBB-0002", "files"))
        self.core.cards.poll()
        self.assertEqual(self.core.cards.status()["slot"]["state"], "new")

    def test_card_pulled_without_a_swap_and_put_back(self):
        self.cs.use_new()
        self.cs.put(self.c, "moved.txt", b"on the card", days_old=10)
        self.core.refresh_sd()
        self.assertEqual(keys(self.c), ["moved.txt"])
        self.cs.pull("AAAA-0001")
        self.assertFalse(self.core.layout.has_sd)
        self.assertIn("Card 1 was removed without a swap", self.alerts("sd_missing")[0]["text"])
        self.core.health.run()
        self.assertTrue(self.alerts("sd_missing"), "a card pulled without a swap is still reported")
        with self.assertRaises(panel_api.ApiError):
            self.core.cards.forget(self.core.cards.active_card()["id"])     # a card in use can't be forgotten
        self.assertEqual(keys(self.c), [], "the card's files are not shown while it is out")
        self.cs.put(self.c, "while-out.txt", b"internal")
        self.assertEqual(read(self.c, "while-out.txt"), b"internal")
        self.cs.insert("AAAA-0001")
        self.assertTrue(self.core.layout.has_sd)
        self.assertEqual(keys(self.c), ["moved.txt", "while-out.txt"], "everything resumes when it is back")
        self.assertEqual(read(self.c, "moved.txt"), b"on the card")
        self.assertFalse(self.alerts("sd_missing"))

    def test_swap_with_default_sorting_and_overrides(self):
        self.cs.use_new()
        files = {"config/settings.json": b'{"a": 1}', "media/clip.mp4": b"video" * 100, "media/photo.jpg": b"jpg",
                 "backups/old.zip": b"zip", "notes/a.txt": b"note", "keep/x.mp4": b"keep this video"}
        for k, v in files.items():
            self.cs.put(self.c, k, v, days_old=10)
        self.core.refresh_sd()
        plan = self.core.cards.swap_plan()
        by = {(g["folder"], g["type"]): g for g in plan["groups"]}
        self.assertTrue(by[("config", ".json")]["carry"])
        self.assertTrue(by[("notes", ".txt")]["carry"])
        for k in (("media", ".mp4"), ("media", ".jpg"), ("backups", ".zip"), ("keep", ".mp4")):
            self.assertFalse(by[k]["carry"], k)
        self.assertTrue(plan["enough"])
        by[("keep", ".mp4")]["carry"] = True          # the person's own choices
        by[("notes", ".txt")]["carry"] = False
        # files that stay on the card have no second copy unless a mirror reported one after the last write
        from pocket.panel_api import ApiError
        self.assertFalse(plan["mirror"]["ok"])
        with self.assertRaises(ApiError) as ctx:
            self.core.cards.swap_start({"groups": list(by.values())})
        self.assertEqual(ctx.exception.code, "MirrorStale")
        self.core.stats.mirror_report("pc-mirror", "device-0001", "test")
        self.assertTrue(self.core.cards.swap_plan()["mirror"]["ok"])
        self.core.cards.swap_start({"groups": list(by.values())})
        result = self.cs.wait()
        self.assertEqual(result["carried"], 2)

        self.assertFalse(self.core.layout.has_sd)
        self.assertEqual(self.core.cards.status()["slot"]["state"], "shelved_here")
        self.assertTrue(self.alerts("sd_ready"))
        self.assertEqual(keys(self.c), ["config/settings.json", "keep/x.mp4"], "only the carried files are shown")
        self.assertEqual(read(self.c, "config/settings.json"), files["config/settings.json"])
        self.assertEqual(read(self.c, "keep/x.mp4"), files["keep/x.mp4"])
        pocket = self.cs.pocket("AAAA-0001")
        with open(os.path.join(pocket, "MANIFEST.csv"), encoding="utf-8") as f:
            listed = sorted(r["key"] for r in csv.DictReader(f))
        self.assertEqual(listed, ["backups/old.zip", "media/clip.mp4", "media/photo.jpg", "notes/a.txt"])
        self.assertFalse(os.path.exists(os.path.join(pocket, "apps", "cards", "shared", "config")), "carried files left the card")
        self.assertFalse(os.path.exists(os.path.join(pocket, "_copy")))
        self.assertEqual(marker(pocket)["state"], "shelved")
        shelf = self.core.cards.status()["shelf"]
        self.assertEqual([(c["name"], c["files"]) for c in shelf], [("Card 1", 4)])
        rules = {(r["folder"], r["type"]): r["carry"] for r in self.core.settings.get("sd.rules")}
        self.assertTrue(rules[("keep", ".mp4")])
        self.assertFalse(rules[("notes", ".txt")])
        self.cs.pull("AAAA-0001")
        self.assertIsNone(self.core.cards.status()["slot"])
        self.assertFalse(self.alerts("sd_ready"))
        self.core.health.run()
        self.assertFalse(self.alerts("sd_missing"), "a card swapped out on purpose is not reported missing")
        # the card was formatted or lost: forget it
        card_id = shelf[0]["id"]
        self.core.cards.forget(card_id)
        self.assertEqual(self.core.cards.status()["shelf"], [])
        with self.assertRaises(panel_api.ApiError):
            self.core.cards.forget(card_id)
        self.cs.insert("AAAA-0001")              # it turns up after all: taken back from its marker
        self.assertEqual(self.core.cards.status()["slot"]["state"], "archive")

    def test_put_back_to_work(self):
        self.cs.use_new()
        st, e2 = self.cs.srv.enroll("gone", "device-0002", "Old PC")
        c2 = self.cs.srv.client(e2)
        self.cs.put(self.c, "a/rejoin.txt", b"rejoins", days_old=10)
        self.cs.put(self.c, "a/card-newer.txt", b"card version (newer)", days_old=10)
        self.cs.put(self.c, "a/current-newer.txt", b"card version (older)", days_old=10)
        self.cs.put(self.c, "p.txt", b"private", days_old=10, bucket="device")
        self.cs.put(c2, "x.txt", b"of an app deleted later", days_old=10, app="gone")
        self.core.refresh_sd()
        plan = self.core.cards.swap_plan()
        for g in plan["groups"]:
            g["carry"] = False                       # everything stays on the card
        self.core.cards.swap_start({"groups": plan["groups"], "override": True})
        self.cs.wait()
        card_id = self.core.cards.status()["shelf"][0]["id"]
        self.cs.pull("AAAA-0001")

        # while the card is on the shelf: new versions, an app deleted, a tight allocation
        self.cs.put(self.c, "a/current-newer.txt", b"current version (newer)")
        self.cs.put(self.c, "a/card-newer.txt", b"current version (older)", days_old=20)
        panel_api.app_delete(self.core, "gone")
        used = self.core.usage.app_used("cards")
        self.core.registry.set_allocation("cards", used + 10, self.core.usage.pool()["total"], used)

        self.cs.insert("AAAA-0001")
        st = self.core.cards.status()
        self.assertEqual(st["slot"]["state"], "archive")
        self.assertTrue(self.alerts("archive_card"))
        cmp_ = self.core.cards.compare(card_id)
        self.assertEqual(cmp_["rejoin_files"], 2)                         # rejoin.txt and the private p.txt
        keep = {c["key"]: c["keep"] for c in cmp_["conflicts"]}
        self.assertEqual(keep, {"a/card-newer.txt": "card", "a/current-newer.txt": "current"})
        self.assertEqual([(u["app"], u["why"]) for u in cmp_["unassigned"]], [("gone", "app deleted")])
        self.assertEqual([o["app"] for o in cmp_["overruns"]], ["cards"])

        self.core.cards.activate(card_id, {"raise": ["cards"]})
        result = self.cs.wait()
        self.assertEqual(result["raised"], ["cards"])
        self.assertTrue(self.core.layout.has_sd)
        self.assertEqual(self.core.cards.status()["active"]["id"], card_id)
        self.assertEqual(read(self.c, "a/rejoin.txt"), b"rejoins")
        self.assertEqual(read(self.c, "p.txt", bucket="device"), b"private")
        self.assertEqual(read(self.c, "a/card-newer.txt"), b"card version (newer)")
        self.assertEqual(read(self.c, "a/current-newer.txt"), b"current version (newer)")
        undone = {(e["key"], e["reason"]) for e in panel_api.undo_list(self.core)["items"]}
        self.assertIn(("a/card-newer.txt", "replaced by a returning SD card"), undone, "the older version in use went to the undo")
        self.assertIn(("a/current-newer.txt", "older than the version in use"), undone, "the card's older version went to the undo")
        self.assertGreaterEqual(self.core.registry.app("cards")["alloc_bytes"], used)
        groups = self.core.cards.unassigned()["groups"]
        self.assertEqual([(g["app"], g["files"]) for g in groups], [("gone", 1)])
        self.core.cards.unassigned_assign({"app": "gone", "space": "shared", "to": "cards"})
        self.assertEqual(read(self.c, "unassigned/gone/x.txt"), b"of an app deleted later")
        self.assertEqual(self.core.cards.unassigned()["groups"], [])
        self.assertFalse(self.alerts("archive_card"))

    def test_browse_and_restore_from_an_archive_card(self):
        self.cs.use_new()
        self.cs.put(self.c, "docs/old.txt", b"archived", days_old=10)
        self.cs.put(self.c, "docs/movie.mp4", b"movie", days_old=10)
        self.core.refresh_sd()
        plan = self.core.cards.swap_plan()
        for g in plan["groups"]:
            g["carry"] = False
        self.core.cards.swap_start({"groups": plan["groups"], "override": True})
        self.cs.wait()
        card_id = self.core.cards.status()["shelf"][0]["id"]
        self.cs.pull("AAAA-0001")
        spaces = self.core.cards.files(card_id, "", "")["spaces"]
        self.assertEqual([(s["app"], s["files"]) for s in spaces], [("cards", 2)])
        top = self.core.cards.files(card_id, "cards", "shared")
        self.assertEqual([i["name"] for i in top["items"]], ["docs"])
        self.assertFalse(top["inserted"])
        with self.assertRaises(panel_api.ApiError):
            self.core.cards.restore(card_id, {"app": "cards", "space": "shared", "key": "docs/old.txt"})
        self.cs.insert("AAAA-0001")
        inner = self.core.cards.files(card_id, "cards", "shared", "docs")
        self.assertEqual([i["name"] for i in inner["items"]], ["movie.mp4", "old.txt"])
        self.assertTrue(inner["inserted"])
        self.assertTrue(self.core.cards.restore(card_id, {"app": "cards", "space": "shared", "key": "docs/old.txt"})["restored"])
        self.assertEqual(read(self.c, "docs/old.txt"), b"archived")
        self.assertEqual(keys(self.c), ["docs/old.txt"], "only the restored file joins; the card stays an archive")

    def test_card_already_in_use_before_cards_is_adopted(self):
        self.cs.stop()
        cs = CardServer.__new__(CardServer)
        srv = PocketServer(sd_root="", sd={"search": "", "poll_s": 1, "speed_test_mb": 1})
        cards_dir = os.path.join(srv.tmp, "cards")
        legacy = os.path.join(cards_dir, "CCCC-0003", "files", "pocket", "apps", "old", "shared")
        os.makedirs(legacy)
        with open(os.path.join(legacy, "kept.txt"), "w") as f:
            f.write("from before")
        srv.overrides["sd"]["search"] = os.path.join(cards_dir, "*", "files")
        srv.start()
        try:
            st = srv.core.cards.status()
            self.assertEqual(st["active"]["name"], "Card 1")
            self.assertEqual(st["slot"]["state"], "active")
            self.assertTrue(srv.core.layout.has_sd)
        finally:
            srv.stop()
        self.cs = cs
        cs.stop = lambda: None


if __name__ == "__main__":
    unittest.main()
