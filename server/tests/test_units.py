"""Unit tests: no rclone needed."""

import datetime
import os
import shutil
import sys
import tempfile
import unittest
from urllib.parse import urlsplit

import botocore.session
from botocore.auth import S3SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pocket import sigv4  # noqa: E402
from pocket.layout import safe_key  # noqa: E402
from pocket.registry import Registry, RegistryError  # noqa: E402
from pocket.s3ops import classify, parse_delete_request, parse_delete_result  # noqa: E402
from pocket.settings import Settings  # noqa: E402

GB = 1024 ** 3
NEW_APPS = {"auto_create": True, "alloc_gb": 3, "warn_pct": 80, "at_limit": "refuse", "move_days": 7}


def presign(method, key, expires=300, ak="PKTESTKEY", secret="sekret"):
    s = botocore.session.get_session()
    c = s.create_client("s3", endpoint_url="http://pocket:9000", region_name="us-east-1",
                        aws_access_key_id=ak, aws_secret_access_key=secret,
                        config=botocore.config.Config(s3={"addressing_style": "path"}, signature_version="s3v4"))
    op = {"GET": "get_object", "PUT": "put_object"}[method]
    return c.generate_presigned_url(op, Params={"Bucket": "shared", "Key": key}, ExpiresIn=expires)


def split(url):
    u = urlsplit(url)
    return u.path, sigv4.parse_query(u.query), {"host": u.netloc}


class SigV4Tests(unittest.TestCase):
    def test_presigned_get_made_by_botocore_verifies(self):
        path, q, h = split(presign("GET", "reports/2026 report+final.json"))
        self.assertEqual(sigv4.presigned_key(q), "PKTESTKEY")
        ok, info = sigv4.verify_presigned("GET", path, q, h, "sekret")
        self.assertTrue(ok, info)
        self.assertEqual(info, "us-east-1")

    def test_presigned_put_verifies(self):
        path, q, h = split(presign("PUT", "upload.bin"))
        self.assertTrue(sigv4.verify_presigned("PUT", path, q, h, "sekret")[0])

    def test_wrong_secret_method_or_tampering_is_refused(self):
        path, q, h = split(presign("GET", "a.txt"))
        self.assertEqual(sigv4.verify_presigned("GET", path, q, h, "other")[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_presigned("PUT", path, q, h, "sekret")[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_presigned("GET", path.replace("a.txt", "b.txt"), q, h, "sekret")[1],
                         "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_presigned("GET", path, q, {"host": "evil:9000"}, "sekret")[1],
                         "SignatureDoesNotMatch")

    def test_expired_link_is_refused(self):
        path, q, h = split(presign("GET", "a.txt", expires=60))
        later = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=5)
        self.assertEqual(sigv4.verify_presigned("GET", path, q, h, "sekret", now=later)[1], "AccessDenied")

    def test_header_signature_matches_botocore(self):
        """The gateway's signing must compute exactly what botocore (and so rclone) computes."""
        import botocore.auth as ba
        now = datetime.datetime(2026, 10, 11, 12, 0, 0, tzinfo=datetime.timezone.utc)
        path = "/shared/some%20dir/file.txt"
        req = AWSRequest(method="GET", url="http://pocket:9000" + path + "?response-content-type=text%2Fplain",
                         headers={"x-amz-meta-note": "hi"})
        real = ba.datetime.datetime

        class Fixed(real):
            @classmethod
            def utcnow(cls):
                return now.replace(tzinfo=None)

            @classmethod
            def now(cls, tz=None):
                return now if tz else now.replace(tzinfo=None)

        ba.datetime.datetime = Fixed
        try:
            S3SigV4Auth(Credentials("PKTESTKEY", "sekret"), "s3", "us-east-1").add_auth(req)
        finally:
            ba.datetime.datetime = real
        payload = req.headers["x-amz-content-sha256"]       # botocore hashes the (empty) body over plain http
        headers = {"host": "pocket:9000", "x-amz-content-sha256": payload, "x-amz-date": "20261011T120000Z",
                   "x-amz-meta-note": "hi"}
        ours = sigv4.signature("sekret", "GET", path, [("response-content-type", "text/plain")], headers,
                               sorted(headers), payload, "20261011T120000Z", "us-east-1")
        self.assertEqual(ours, req.headers["Authorization"].split("Signature=")[1])

    def test_pocket_signature(self):
        h = sigv4.sign_pocket("GET", "/_pocket/quota", "", b"", "PKKEY", "secret", now=1_700_000_000)
        ok, why = sigv4.verify_pocket("GET", "/_pocket/quota", "", h, b"", "secret", now=1_700_000_100)
        self.assertEqual((ok, why), (True, ""))
        self.assertEqual(sigv4.verify_pocket("GET", "/_pocket/quota", "", h, b"", "other", now=1_700_000_100)[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_pocket("POST", "/_pocket/quota", "", h, b"", "secret", now=1_700_000_100)[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_pocket("GET", "/_pocket/quota", "a=1", h, b"", "secret", now=1_700_000_100)[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_pocket("GET", "/_pocket/quota", "", h, b"x", "secret", now=1_700_000_100)[1], "SignatureDoesNotMatch")
        self.assertEqual(sigv4.verify_pocket("GET", "/_pocket/quota", "", h, b"", "secret", now=1_700_001_000)[1], "RequestTimeTooSkewed")
        self.assertEqual(sigv4.verify_pocket("GET", "/_pocket/quota", "", {}, b"", "secret")[1], "MissingSignature")

    def test_access_key_from_header(self):
        h = "AWS4-HMAC-SHA256 Credential=PKABC/20261011/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00"
        self.assertEqual(sigv4.access_key_from_header(h), "PKABC")
        self.assertIsNone(sigv4.access_key_from_header("Basic abc"))


class S3OpsTests(unittest.TestCase):
    def test_classify(self):
        c = lambda m, b, k, q=(), h=None: classify(m, b, k, dict.fromkeys(q, ""), h or {})  # noqa: E731
        self.assertEqual(c("GET", "", ""), "ListBuckets")
        self.assertEqual(c("GET", "shared", "", ["list-type"]), "ListObjectsV2")
        self.assertEqual(c("PUT", "shared", ""), "CreateBucket")
        self.assertEqual(c("PUT", "shared", "a"), "PutObject")
        self.assertEqual(c("PUT", "shared", "a", [], {"x-amz-copy-source": "/shared/b"}), "CopyObject")
        self.assertEqual(c("PUT", "shared", "a", ["partNumber", "uploadId"]), "UploadPart")
        self.assertEqual(c("POST", "shared", "a", ["uploads"]), "CreateMultipartUpload")
        self.assertEqual(c("POST", "shared", "a", ["uploadId"]), "CompleteMultipartUpload")
        self.assertEqual(c("DELETE", "shared", "a", ["uploadId"]), "AbortMultipartUpload")
        self.assertEqual(c("DELETE", "shared", "a"), "DeleteObject")
        self.assertEqual(c("POST", "shared", "", ["delete"]), "DeleteObjects")
        self.assertEqual(c("HEAD", "shared", "a"), "HeadObject")

    def test_delete_xml(self):
        body = (b'<?xml version="1.0"?><Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Quiet>true</Quiet>'
                b"<Object><Key>a.txt</Key></Object><Object><Key>dir/b.txt</Key></Object></Delete>")
        self.assertEqual(parse_delete_request(body), ["a.txt", "dir/b.txt"])
        res = (b'<DeleteResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Deleted><Key>a.txt</Key></Deleted>'
               b"<Error><Key>dir/b.txt</Key><Code>AccessDenied</Code></Error></DeleteResult>")
        self.assertEqual(parse_delete_result(res), (["a.txt"], ["dir/b.txt"]))


class LayoutTests(unittest.TestCase):
    def test_safe_keys(self):
        self.assertEqual(safe_key("a/b/c.json"), ["a", "b", "c.json"])
        for bad in ["", "/abs", "dir/", "a//b", "../x", "a/./b", "a/../b", ".rclone_temp_x", "a\x00b"]:
            self.assertIsNone(safe_key(bad), bad)


class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.reg = Registry(self.dir)

    def tearDown(self):
        self.reg.close()
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_new_app_gets_default_and_reenroll_is_stable(self):
        r = self.reg.enroll("egx", "device-0001", "PC", "100.1.1.1", NEW_APPS, 10 * GB)
        self.assertTrue(r["app_created"] and r["device_created"])
        self.assertEqual(r["app"]["alloc_bytes"], 3 * GB)
        again = self.reg.enroll("egx", "device-0001", "PC", "100.1.1.1", NEW_APPS, 10 * GB)
        self.assertFalse(again["app_created"] or again["device_created"] or again["ip_moved"])
        self.assertEqual(again["device"]["access_key"], r["device"]["access_key"])
        moved = self.reg.enroll("egx", "device-0001", "PC", "100.9.9.9", NEW_APPS, 10 * GB)
        self.assertTrue(moved["ip_moved"])
        self.assertEqual(self.reg.device_by_key(r["device"]["access_key"])["ip"], "100.9.9.9")

    def test_strict_promises(self):
        self.reg.enroll("a1", "device-0001", "PC", "ip", NEW_APPS, 7 * GB)
        self.reg.enroll("a2", "device-0001", "PC", "ip", NEW_APPS, 7 * GB)
        r = self.reg.enroll("a3", "device-0001", "PC", "ip", NEW_APPS, 7 * GB)   # only 1 GB left unpromised
        self.assertTrue(r["short_allocation"])
        self.assertEqual(r["app"]["alloc_bytes"], 1 * GB)
        r = self.reg.enroll("a4", "device-0001", "PC", "ip", NEW_APPS, 7 * GB)
        self.assertEqual(r["app"]["alloc_bytes"], 0)
        with self.assertRaises(RegistryError):
            self.reg.set_allocation("a1", 4 * GB, 7 * GB, 0)                     # nothing left to promise
        with self.assertRaises(RegistryError):
            self.reg.set_allocation("a1", 1 * GB, 7 * GB, 2 * GB)                # below what it holds
        self.reg.set_allocation("a1", 2 * GB, 7 * GB, 1 * GB)
        self.assertEqual(self.reg.promised(), 7 * GB - 1 * GB)

    def test_bad_names(self):
        with self.assertRaises(RegistryError):
            self.reg.enroll("Bad App", "device-0001", "PC", "ip", NEW_APPS, GB)
        with self.assertRaises(RegistryError):
            self.reg.enroll("ok", "short", "PC", "ip", NEW_APPS, GB)

    def test_rotate_and_block(self):
        r = self.reg.enroll("egx", "device-0001", "PC", "ip", NEW_APPS, 10 * GB)
        old = r["device"]["access_key"]
        new = self.reg.rotate_key("egx", "device-0001")
        self.assertIsNone(self.reg.device_by_key(old))
        self.assertIsNotNone(self.reg.device_by_key(new["access_key"]))
        self.reg.set_blocked("egx", "device-0001", True)
        self.assertEqual(self.reg.device_by_key(new["access_key"])["blocked"], 1)


class SettingsTests(unittest.TestCase):
    def test_defaults_overrides_and_saved_changes(self):
        d = tempfile.mkdtemp()
        try:
            s = Settings(d, {"pool": {"internal_gb": 5}})
            self.assertEqual(s.get("pool.internal_gb"), 5)
            self.assertEqual(s.get("pool.sd_gb"), 200)
            self.assertTrue(s.get("enroll_secret"))
            s.update({"jobs": {"undo_days": 30}})
            again = Settings(d)
            self.assertEqual(again.get("jobs.undo_days"), 30)
            self.assertEqual(again.get("pool.internal_gb"), 5)
            self.assertEqual(again.get("enroll_secret"), s.get("enroll_secret"))
        finally:
            shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
