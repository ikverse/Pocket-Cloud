"""sync(folder, bucket) against the fake server."""

import json
import os
import unittest

from base import FakeTestCase, pocket


class SyncTests(FakeTestCase):
    def setUp(self):
        super().setUp()
        self.a = self.open()
        self.dir = self.path("folder")
        os.makedirs(self.dir)

    def local(self, rel, data=None):
        if data is None:
            return self.read(rel, self.dir)
        p = self.write(rel, data, self.dir)
        st = os.stat(p)                          # make every write look newer than the last one
        os.utime(p, ns=(st.st_atime_ns, st.st_mtime_ns + 1_000_000_000))
        return p

    def files(self):
        out = set()
        for root, _, names in os.walk(self.dir):
            for n in names:
                out.add(os.path.relpath(os.path.join(root, n), self.dir).replace(os.sep, "/"))
        return out

    def test_first_run_copies_both_ways_and_never_deletes(self):
        self.local("here.txt", "local")
        self.local("sub/deep.txt", "deep")
        self.local("same.txt", "same")
        self.a.put("docs/there.txt", b"remote")
        self.a.put("docs/same.txt", b"same")
        r = self.a.sync(self.dir, prefix="docs/")
        self.assertEqual(sorted(r.uploaded), ["docs/here.txt", "docs/sub/deep.txt"])
        self.assertEqual(r.downloaded, ["docs/there.txt"])
        self.assertEqual((r.deleted_local, r.deleted_remote, r.conflicts), ([], [], []))
        self.assertEqual(self.local("there.txt"), b"remote")
        self.assertEqual(self.a.get("docs/sub/deep.txt"), b"deep")
        self.assertIn(".pocket-sync.json", os.listdir(self.dir))
        self.assertIsNone(self.a.stat("docs/.pocket-sync.json"))

    def test_first_run_differences_are_conflicts(self):
        self.local("notes.txt", "mine")
        self.a.put("notes.txt", b"theirs")
        r = self.a.sync(self.dir)
        self.assertEqual(len(r.conflicts), 2)
        self.assertEqual(self.local("notes.txt"), b"theirs")
        self.assertEqual(self.a.get("notes.txt"), b"theirs")
        copy = [k for k in r.conflicts if "(conflict Test PC " in k][0]
        self.assertTrue(copy.startswith("notes (conflict Test PC ") and copy.endswith(").txt"), copy)
        self.assertEqual(self.a.get(copy), b"mine")
        self.assertEqual(self.local(copy), b"mine")

    def test_later_runs_follow_changes_both_ways(self):
        self.local("a.txt", "1")
        self.local("b.txt", "1")
        self.local("c.txt", "1")
        self.a.sync(self.dir)
        self.local("a.txt", "2")                    # edited here
        os.remove(os.path.join(self.dir, "b.txt"))  # deleted here
        other = self.open(device_id="other-device-1", device_name="Laptop")
        other.put("c.txt", b"2")                    # edited there
        other.put("d.txt", b"new")                  # new there
        r = self.a.sync(self.dir)
        self.assertEqual(r.uploaded, ["a.txt"])
        self.assertEqual(sorted(r.downloaded), ["c.txt", "d.txt"])
        self.assertEqual(r.deleted_remote, ["b.txt"])
        self.assertIsNone(self.a.stat("b.txt"))
        self.assertEqual(self.a.get("a.txt"), b"2")
        self.assertEqual(self.local("c.txt"), b"2")
        other.delete("d.txt")
        other.move("c.txt", "moved/c.txt")
        r = self.a.sync(self.dir)
        self.assertEqual(sorted(r.deleted_local), ["c.txt", "d.txt"])
        self.assertEqual(r.downloaded, ["moved/c.txt"])
        self.assertEqual(self.files(), {".pocket-sync.json", "a.txt", "moved/c.txt"})
        r = self.a.sync(self.dir)
        self.assertEqual(r, pocket.SyncResult())    # nothing left to do
        changes = [x for x in self.requests() if x["path"] == "/_pocket/changes"]
        self.assertTrue(changes, "later runs use the change feed")

    def test_moves_out_of_the_bucket_or_prefix_remove_the_local_copy(self):
        self.local("a.txt", "1")
        self.local("b.txt", "2")
        self.a.sync(self.dir, prefix="docs/")
        other = self.open(device_id="other-device-1")
        self.a.move("docs/a.txt", "a.txt", to_bucket="device")     # into this device's private bucket
        other.move("docs/b.txt", "archive/b.txt")                    # out of the prefix
        r = self.a.sync(self.dir, prefix="docs/")
        self.assertEqual(sorted(r.deleted_local), ["docs/a.txt", "docs/b.txt"])
        self.assertEqual(self.files(), {".pocket-sync.json"})
        self.local("a.txt", "new")                                   # a later file there is a plain upload
        r = self.a.sync(self.dir, prefix="docs/")
        self.assertEqual((r.uploaded, r.conflicts), (["docs/a.txt"], []))

    def test_changed_on_both_sides_is_a_conflict(self):
        self.local("n.md", "base")
        self.a.sync(self.dir)
        self.local("n.md", "local edit")
        self.open(device_id="other-device-1").put("n.md", b"remote edit")
        r = self.a.sync(self.dir)
        self.assertEqual(self.a.get("n.md"), b"remote edit")
        self.assertEqual(self.local("n.md"), b"remote edit")
        copy = [k for k in r.conflicts if k != "n.md"][0]
        self.assertEqual(self.a.get(copy), b"local edit")
        self.assertIn("n.md", r.conflicts)

    def test_remote_edit_beats_local_delete_and_local_edit_beats_remote_delete(self):
        self.local("x.txt", "1")
        self.local("y.txt", "1")
        self.a.sync(self.dir)
        other = self.open(device_id="other-device-1")
        os.remove(os.path.join(self.dir, "x.txt"))
        other.put("x.txt", b"2")
        self.local("y.txt", "2")
        other.delete("y.txt")
        r = self.a.sync(self.dir)
        self.assertEqual(self.local("x.txt"), b"2")
        self.assertEqual(self.a.get("y.txt"), b"2")
        self.assertEqual((r.deleted_local, r.deleted_remote), ([], []))

    def test_mass_delete_guard(self):
        for i in range(60):
            self.local(f"f{i:02d}.txt", str(i))
        self.a.sync(self.dir)
        for i in range(60):
            os.remove(os.path.join(self.dir, f"f{i:02d}.txt"))
        self.local("new.txt", "still uploaded")
        with self.assertRaises(pocket.PocketError) as cm:
            self.a.sync(self.dir)
        self.assertEqual(cm.exception.code, "MassDelete")
        self.assertIn("60", str(cm.exception))
        self.assertEqual(len(list(self.a.list())), 61, "nothing deleted remotely")
        self.assertEqual(self.a.get("new.txt"), b"still uploaded")
        with self.assertRaises(pocket.PocketError):
            self.a.sync(self.dir)                    # still pending, still guarded
        r = self.a.sync(self.dir, allow_mass_delete=True)
        self.assertEqual(len(r.deleted_remote), 60)
        self.assertEqual([e.key for e in self.a.list()], ["new.txt"])

    def test_mass_delete_guard_counts_local_deletes_too(self):
        for i in range(51):
            self.a.put(f"r{i:02d}", b"x")
        self.a.sync(self.dir)
        other = self.open(device_id="other-device-1")
        for i in range(51):
            other.delete(f"r{i:02d}")
        with self.assertRaises(pocket.PocketError) as cm:
            self.a.sync(self.dir)
        self.assertEqual(cm.exception.code, "MassDelete")
        self.assertEqual(len(self.files()), 52)
        r = self.a.sync(self.dir, allow_mass_delete=True)
        self.assertEqual(len(r.deleted_local), 51)
        self.assertEqual(self.files(), {".pocket-sync.json"})

    def test_cursor_expired_does_a_full_resync_fake_only(self):
        self.local("a.txt", "1")
        self.a.sync(self.dir)
        other = self.open(device_id="other-device-1")
        other.put("b.txt", b"new")
        other.delete("a.txt")
        with open(os.path.join(self.dir, ".pocket-sync.json"), encoding="utf-8") as f:
            cursor = json.load(f)["cursor"]
        last = list(self.a.changes(cursor))
        self.srv.control("expire-changes", {"before": str(last[-1].seq)})
        r = self.a.sync(self.dir)
        self.assertEqual(r.downloaded, ["b.txt"])
        self.assertEqual(r.deleted_local, ["a.txt"])
        self.assertTrue(any(x["code"] == "CursorExpired" for x in self.requests()))

    def test_without_change_feed_lists_every_time_fake_only(self):
        self.srv.control("features", {"features": ["files", "move", "quota"]})
        a = self.open()
        self.local("a.txt", "1")
        a.sync(self.dir)
        self.a.put("b.txt", b"2")
        self.assertEqual(a.sync(self.dir).downloaded, ["b.txt"])
        os.remove(os.path.join(self.dir, "a.txt"))
        self.assertEqual(a.sync(self.dir).deleted_remote, ["a.txt"])

    def test_pocket_files_are_never_uploaded(self):
        self.local(".pocket-notes", "x")
        self.local(".pocket-cache/thing", "x")
        self.local("keep.txt", "x")
        self.a.sync(self.dir)
        self.assertEqual([e.key for e in self.a.list()], ["keep.txt"])

    def test_remote_changed_during_upload_becomes_a_conflict(self):
        self.local("a.txt", "1")
        self.a.sync(self.dir)
        self.local("a.txt", "mine")
        other = self.open(device_id="other-device-1")
        real = self.a.changes

        def changes_then_race(*args, **kw):          # the other device writes after the feed was read
            feed = list(real(*args, **kw))
            other.put("a.txt", b"theirs")

            class F(list):
                cursor = None
            f = F(feed)
            f.cursor = str(feed[-1].seq) if feed else args[0]
            return f
        self.a.changes = changes_then_race
        r = self.a.sync(self.dir)
        self.assertEqual(self.a.get("a.txt"), b"theirs")
        self.assertEqual(len(r.conflicts), 2)

    def test_state_for_another_prefix_counts_as_first_run(self):
        self.local("a.txt", "1")
        self.a.sync(self.dir, prefix="one/")
        os.remove(os.path.join(self.dir, "a.txt"))
        r = self.a.sync(self.dir, prefix="two/")
        self.assertEqual((r.deleted_remote, r.deleted_local), ([], []))
        self.assertIsNotNone(self.a.stat("one/a.txt"))


if __name__ == "__main__":
    unittest.main()
