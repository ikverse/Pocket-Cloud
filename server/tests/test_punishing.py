"""The punishing test: heavy parallel load with a storage-server crash in the middle, and a power cut
(the whole server hard-killed mid-upload, then restarted). Afterwards every upload the server confirmed
must read back byte-for-byte, nothing half-written may be visible, and the databases must be intact.

Slow (a few minutes). Run with:  python -m unittest test_punishing
"""

import hashlib
import json
import os
import random
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import unittest
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import boto3
import botocore
from boto3.s3.transfer import TransferConfig
from botocore.config import Config

from helpers import GB, RCLONE, SERVER_DIR, PocketServer, free_port

SMALL_FILES = 3000
LARGE_FILES = 3
LARGE_SIZE = 100 * 1024 * 1024
MB = 1024 * 1024


def sha(b):
    return hashlib.sha256(b).hexdigest()


def make_client(e, retries=8):
    return boto3.client("s3", endpoint_url=e["endpoint"], aws_access_key_id=e["access_key"],
                        aws_secret_access_key=e["secret_key"], region_name="us-east-1",
                        config=Config(s3={"addressing_style": "path"}, max_pool_connections=32,
                                      retries={"max_attempts": retries, "mode": "standard"}))


def all_keys(c, bucket="shared"):
    keys, token = [], None
    while True:
        kw = {"Bucket": bucket, "MaxKeys": 1000}
        if token:
            kw["ContinuationToken"] = token
        r = c.list_objects_v2(**kw)
        keys += [o["Key"] for o in r.get("Contents", [])]
        if not r.get("IsTruncated"):
            return keys
        token = r["NextContinuationToken"]


class CrashUnderLoad(unittest.TestCase):
    def test_storage_server_crash_during_heavy_load(self):
        srv = PocketServer().start()
        try:
            core = srv.core
            st, e = srv.enroll("stress", "device-0001", "PC")
            core.registry.set_allocation("stress", 4 * GB, core.usage.pool()["total"], 0)
            rnd = random.Random(7)
            small = {f"small/{i // 100:02d}/f{i:05d}.bin": os.urandom(rnd.randint(1024, 20 * 1024)) for i in range(SMALL_FILES)}
            large_paths = {}
            for i in range(LARGE_FILES):
                p = os.path.join(srv.tmp, f"large{i}.bin")
                with open(p, "wb") as f:
                    for _ in range(LARGE_SIZE // MB):
                        f.write(os.urandom(MB))
                large_paths[f"large/large{i}.bin"] = p
            large_hash = {k: sha(open(p, "rb").read()) for k, p in large_paths.items()}

            confirmed = {}            # key -> sha256 of what the server confirmed storing
            lock = threading.Lock()
            done_small = [0]
            local = threading.local()

            def client():
                if not hasattr(local, "c"):
                    local.c = make_client(e)
                return local.c

            def up_small(key):
                for attempt in range(3):           # an app's own retry on top of boto3's
                    try:
                        client().put_object(Bucket="shared", Key=key, Body=small[key])
                        with lock:
                            confirmed[key] = sha(small[key])
                            done_small[0] += 1
                        return
                    except (botocore.exceptions.ClientError, botocore.exceptions.BotoCoreError):
                        time.sleep(1)

            def up_large(key):
                cfg = TransferConfig(multipart_threshold=8 * MB, multipart_chunksize=8 * MB, max_concurrency=4)
                for attempt in range(4):
                    try:
                        client().upload_file(large_paths[key], "shared", key, Config=cfg)
                        with lock:
                            confirmed[key] = large_hash[key]
                        return
                    except Exception:
                        time.sleep(1)

            def crasher():
                while done_small[0] < SMALL_FILES // 2:
                    time.sleep(0.05)
                core.rclone.kill()          # the storage server dies mid-load; the supervisor must bring it back

            t0 = time.time()
            killer = threading.Thread(target=crasher)
            killer.start()
            with ThreadPoolExecutor(16) as pool:
                futures = [pool.submit(up_large, k) for k in large_paths] + [pool.submit(up_small, k) for k in small]
                for f in futures:
                    f.result()
            killer.join()
            took = time.time() - t0

            self.assertGreaterEqual(core.rclone.restarts, 1, "rclone was killed and restarted")
            self.assertEqual(len(confirmed), SMALL_FILES + LARGE_FILES, "every upload eventually succeeded")
            c = make_client(e)
            listed = all_keys(c)
            self.assertEqual(sorted(listed), sorted(confirmed), "listing shows exactly the stored files, nothing half-written")
            bad = []
            for key, digest in confirmed.items():
                if sha(c.get_object(Bucket="shared", Key=key)["Body"].read()) != digest:
                    bad.append(key)
            self.assertEqual(bad, [], "every confirmed upload reads back byte-for-byte")
            core.stats.flush()
            gateway_errors = core.stats.query("SELECT COUNT(*) AS n FROM requests WHERE note='gateway error'")[0]["n"]
            self.assertEqual(gateway_errors, 0)
            refused = core.stats.query("SELECT COUNT(*) AS n FROM requests WHERE status=503")[0]["n"]
            print(f"\n  crash under load: {len(confirmed)} files ({(SMALL_FILES * 10 * 1024 + LARGE_FILES * LARGE_SIZE) / MB:.0f} MB) "
                  f"in {took:.1f} s; rclone restarts: {core.rclone.restarts}; requests answered 'try again': {refused}")
        finally:
            srv.stop()


class PowerCut(unittest.TestCase):
    def start_server(self):
        env = dict(os.environ, PYTHONPATH=SERVER_DIR)
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
        kw = {} if sys.platform == "win32" else {"start_new_session": True}
        p = subprocess.Popen([sys.executable, "-m", "pocket.server", "--state", self.state], env=env, cwd=SERVER_DIR,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags, **kw)
        end = time.time() + 30
        while time.time() < end:
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}/_pocket/health", timeout=1)
                return p
            except Exception:
                time.sleep(0.2)
        raise RuntimeError("server did not start")

    def cut_power(self, p):
        """Kill the server and its rclone at once, with no chance to clean up."""
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
        else:
            os.killpg(p.pid, signal.SIGKILL)
        p.wait(10)

    def test_power_cut_mid_upload(self):
        tmp = PocketServer().tmp            # just borrow a temp folder layout
        self.state = os.path.join(tmp, "state")
        self.port = free_port()
        os.makedirs(self.state)
        with open(os.path.join(self.state, "settings.json"), "w") as f:
            json.dump({"internal_root": os.path.join(tmp, "int"), "sd_root": os.path.join(tmp, "sd"),
                       "rclone_binary": RCLONE, "storage_port": self.port, "rclone_port": free_port(),
                       "rc_port": free_port(), "panel_port": free_port(), "listen": ["127.0.0.1"]}, f)
        p = self.start_server()
        try:
            body = json.dumps({"secret": json.load(open(os.path.join(self.state, "settings.json")))["enroll_secret"],
                               "app": "power", "device_id": "device-0001", "device_name": "PC"}).encode()
            e = json.loads(urllib.request.urlopen(urllib.request.Request(
                f"http://127.0.0.1:{self.port}/_pocket/enroll", data=body, method="POST")).read())
            confirmed = {}
            stop = threading.Event()

            def writer(n):
                c = make_client(e, retries=1)
                i = 0
                while not stop.is_set():
                    key = f"w{n}/f{i:05d}.bin"
                    data = os.urandom(random.choice([2_000, 50_000, 3 * MB]))
                    try:
                        c.put_object(Bucket="shared", Key=key, Body=data)
                        confirmed[key] = sha(data)
                    except Exception:
                        pass
                    i += 1

            threads = [threading.Thread(target=writer, args=(n,)) for n in range(6)]
            for t in threads:
                t.start()
            time.sleep(4)
            self.cut_power(p)             # power cut while six writers are mid-upload
            stop.set()
            for t in threads:
                t.join()
            n_before = len(confirmed)
            self.assertGreater(n_before, 20)

            for db in ("registry.db", "stats.db"):
                con = sqlite3.connect(os.path.join(self.state, db))
                self.assertEqual(con.execute("PRAGMA integrity_check").fetchone()[0], "ok", db)
                con.close()

            half = [f for _, _, fs in os.walk(os.path.join(tmp, "int")) for f in fs if f.startswith(".rclone_temp")]
            p = self.start_server()
            c = make_client(e)
            listed = all_keys(c)
            missing = set(confirmed) - set(listed)
            self.assertEqual(missing, set(), "every upload confirmed before the power cut is still there")
            extra = [k for k in listed if k not in confirmed]
            for k in extra:      # uploads cut off before being confirmed may exist, but only whole
                self.assertTrue(k.startswith("w"), k)
            bad = [k for k, d in confirmed.items() if sha(c.get_object(Bucket="shared", Key=k)["Body"].read()) != d]
            self.assertEqual(bad, [], "confirmed uploads read back byte-for-byte after the power cut")
            temp_left = [f for _, _, fs in os.walk(os.path.join(tmp, "int")) for f in fs if f.startswith(".rclone_temp")]
            self.assertEqual(temp_left, [], "half-written files from the power cut are cleaned up on start")
            c.put_object(Bucket="shared", Key="after-restart.txt", Body=b"still works")
            self.assertEqual(c.get_object(Bucket="shared", Key="after-restart.txt")["Body"].read(), b"still works")
            print(f"\n  power cut: {n_before} confirmed uploads before the cut, all intact after restart; "
                  f"{len(extra)} unconfirmed uploads ended up stored whole; "
                  f"{len(half)} half-written files left by the cut were cleaned up on restart")
        finally:
            self.cut_power(p)
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
