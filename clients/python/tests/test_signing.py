"""Section 12 test vectors and the key encoding rules."""

import hashlib
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pocket_cloud import PocketError, _sign  # noqa: E402

KEY, SECRET, DATE = "AKTEST0000000001", "secret-for-tests-only", 1795000000


class VectorTests(unittest.TestCase):
    def sig(self, method, path, query, body):
        return _sign.sign(method, path, query, hashlib.sha256(body).hexdigest(), KEY, SECRET, DATE)["x-pocket-sign"]

    def test_quota(self):
        self.assertEqual(self.sig("GET", "/_pocket/quota", "", b""),
                         "d872ac21f923208dc0ca0344d8a3fe488e4b717b656d7328149e89799ac06b71")

    def test_put(self):
        path = "/_pocket/files/shared/" + _sign.key_path("notes/hello world.txt")
        self.assertEqual(path, "/_pocket/files/shared/notes/hello%20world.txt")
        self.assertEqual(hashlib.sha256(b"hello").hexdigest(),
                         "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        self.assertEqual(self.sig("PUT", path, "", b"hello"),
                         "015bd2b75bedd7fbc3a9ce49abd670c4ff7a1ef6d8f96fd795cfbe5d8995c04f")

    def test_list(self):
        query = _sign.query_string([("bucket", "shared"), ("prefix", "notes/"), ("limit", 2)])
        self.assertEqual(query, "bucket=shared&prefix=notes%2F&limit=2")
        self.assertEqual(self.sig("GET", "/_pocket/list", query, b""),
                         "0884bcb1271e92c9f16e501b09205689aacc6e65c212461a42ffeb102f6f14d6")

    def test_headers(self):
        h = _sign.sign("GET", "/_pocket/quota", "", _sign.EMPTY_SHA256, KEY, SECRET, DATE)
        self.assertEqual(h["x-pocket-key"], KEY)
        self.assertEqual(h["x-pocket-date"], "1795000000")


class KeyTests(unittest.TestCase):
    def test_encoding_per_segment(self):
        self.assertEqual(_sign.key_path("a/b c/ü~.-_"), "a/b%20c/%C3%BC~.-_")
        self.assertEqual(_sign.key_path("x+y&z=1"), "x%2By%26z%3D1")

    def test_bad_keys(self):
        for bad in ["", "/a", "a/", "a//b", "a/./b", "../a", "a\0b", "x" * 1025]:
            with self.assertRaises(PocketError) as cm:
                _sign.key_path(bad)
            self.assertEqual(cm.exception.code, "BadKey")

    def test_longest_key(self):
        self.assertTrue(_sign.key_path("x" * 1024))


if __name__ == "__main__":
    unittest.main()
