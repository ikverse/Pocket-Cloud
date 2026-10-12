"""The punishing test against the real phone: the live server on the Note 9, reached from this PC over Tailscale.

Same checks as test_punishing, aimed at a remote server. rclone and the server are killed on the phone by typing
into Termux over USB (adb), so the phone must be plugged in, unlocked, with Termux open. The power cut also tests
the supervisor (phone/run.sh), which must bring the server back by itself.

A throwaway app is enrolled; afterwards it is deleted and its files are removed from the undo, so nothing is left.

    set POCKET_TOKEN=<control panel sign-in token>
    python -m unittest test_phone

Optional: POCKET_PANEL (default http://pocket:8000), POCKET_ADB_SERIAL (default: the first real phone).
"""

import hashlib
import json
import os
import random
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import botocore
from boto3.s3.transfer import TransferConfig

from test_punishing import all_keys, make_client, sha

PANEL = os.environ.get("POCKET_PANEL", "http://pocket:8000")
TOKEN = os.environ.get("POCKET_TOKEN", "")
ADB = os.environ.get("ADB") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
APP = "phone-test"
SMALL_FILES = 3000
LARGE_FILES = 3
MB = 1024 * 1024
LARGE_SIZE = 100 * MB
OUT = "/sdcard/Download/pocket-test-out.txt"


def panel(method, path, body=None, wait=150):
    """Call the control API, waiting for it while the server restarts (after the power cut the control panel
    can take about a minute to get its port back)."""
    end = time.time() + wait
    while True:
        req = urllib.request.Request(PANEL + path, data=json.dumps(body).encode() if body is not None else None,
                                     method=method, headers={"Authorization": "Bearer " + TOKEN,
                                                             "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.URLError as e:
            if isinstance(e, urllib.error.HTTPError) or time.time() > end:
                raise
            time.sleep(3)


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=kw.get("timeout", 60)).stdout


def first_phone():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    phones = [l.split()[0] for l in out if l.endswith("\tdevice") and not l.startswith("emulator-")]
    return os.environ.get("POCKET_ADB_SERIAL") or (phones[0] if phones else "")


SERIAL = first_phone() if os.path.exists(ADB) else ""


def termux(command):
    """Type a command into Termux on the phone and press Enter. The screen must be on with Termux in front:
    typing into anything else (a lock screen) would go nowhere useful, so that is checked first."""
    focus = adb("shell", "dumpsys window | grep mCurrentFocus")
    if "com.termux/" not in focus:
        raise RuntimeError("Termux is not in front on the phone (unlock it and open Termux): " + focus.strip())
    if "'" in command:
        raise ValueError("no single quotes in typed commands")
    adb("shell", "input text '" + command.replace(" ", "%s") + "'")
    adb("shell", "input keyevent KEYCODE_ENTER")


def on_phone(command, timeout=120):
    """Run a command in Termux and return its output (through a file in Download, then removed)."""
    adb("shell", "rm -f " + OUT)
    termux(f"{{ {command}; }} > {OUT} 2>&1; echo __done__ >> {OUT}")
    end = time.time() + timeout
    while time.time() < end:
        text = adb("shell", "cat " + OUT + " 2>/dev/null")
        if "__done__" in text:
            adb("shell", "rm -f " + OUT)
            return text.replace("__done__", "").strip()
        time.sleep(1)
    raise RuntimeError(f"no answer from the phone for: {command}")


CHECK_SCRIPT = r'''
import os, sqlite3, sys
home = os.path.expanduser("~")
for db in ("registry.db", "stats.db"):
    con = sqlite3.connect("file:" + os.path.join(home, "pocket-state", db) + "?mode=ro", uri=True)
    print("db", db, con.execute("PRAGMA integrity_check").fetchone()[0])
    con.close()
roots = [os.path.join(home, "pocket-data")] + sys.argv[1:]
print("temp", sum(1 for r in roots for _, _, fs in os.walk(r) for f in fs if f.startswith(".rclone_temp")))
'''


def wait_storage(endpoint, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        try:
            urllib.request.urlopen(endpoint + "/_pocket/health", timeout=3)
            return time.time()
        except Exception:
            time.sleep(1)
    raise RuntimeError("the storage server did not come back")


@unittest.skipUnless(TOKEN and SERIAL, "needs POCKET_TOKEN and the phone on USB")
class Phone(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        secret = panel("GET", "/api/settings/enroll-secret")["enroll_secret"]
        storage_port = panel("GET", "/api/settings")["settings"]["storage_port"]
        cls.endpoint = PANEL.rsplit(":", 1)[0] + f":{storage_port}"
        body = json.dumps({"secret": secret, "app": APP, "device_id": "phone-test-pc", "device_name": "PC (phone test)"}).encode()
        req = urllib.request.Request(cls.endpoint + "/_pocket/enroll", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        cls.e = json.loads(urllib.request.urlopen(req, timeout=30).read())
        cls.sd = on_phone("sh ~/pocket-cloud/phone/sd-path.sh")
        with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
            f.write(CHECK_SCRIPT)
        adb("push", f.name, "/sdcard/Download/pocket-check.py")
        os.remove(f.name)

    @classmethod
    def tearDownClass(cls):
        try:
            panel("DELETE", f"/api/apps/{APP}")
        except urllib.error.HTTPError:
            pass
        # the deleted app's files went into the undo; this was test data, so remove it there too
        on_phone(f"rm -rf ~/pocket-data/_undo/*/{APP} {cls.sd}/pocket/_undo/*/{APP} && echo purged")
        adb("shell", "rm -f /sdcard/Download/pocket-check.py")

    def test_1_storage_server_crash_during_heavy_load(self):
        rnd = random.Random(7)
        small = {f"load/small/{i // 100:02d}/f{i:05d}.bin": os.urandom(rnd.randint(1024, 20 * 1024)) for i in range(SMALL_FILES)}
        tmp = tempfile.mkdtemp(prefix="pocket-phone-test-")
        large_paths, large_hash = {}, {}
        for i in range(LARGE_FILES):
            p = os.path.join(tmp, f"large{i}.bin")
            h = hashlib.sha256()
            with open(p, "wb") as f:
                for _ in range(LARGE_SIZE // MB):
                    b = os.urandom(MB)
                    h.update(b)
                    f.write(b)
            large_paths[f"load/large/large{i}.bin"] = p
            large_hash[f"load/large/large{i}.bin"] = h.hexdigest()
        rclone_before = on_phone("pgrep -f rclone.serve")

        confirmed, lock, done_small, local = {}, threading.Lock(), [0], threading.local()

        def client():
            if not hasattr(local, "c"):
                local.c = make_client(self.e)
            return local.c

        def up_small(key):
            for _ in range(3):
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
            for _ in range(4):
                try:
                    client().upload_file(large_paths[key], "shared", key, Config=cfg)
                    with lock:
                        confirmed[key] = large_hash[key]
                    return
                except Exception:
                    time.sleep(1)

        killed = []

        def crasher():
            while done_small[0] < SMALL_FILES // 2:
                time.sleep(0.05)
            termux("pkill -9 -f rclone.serve")   # the storage server dies mid-load; the server must bring it back
            killed.append(time.time())

        t0 = time.time()
        killer = threading.Thread(target=crasher)
        killer.start()
        with ThreadPoolExecutor(16) as pool:
            for f in [pool.submit(up_large, k) for k in large_paths] + [pool.submit(up_small, k) for k in small]:
                f.result()
        killer.join()
        took = time.time() - t0
        rclone_after = on_phone("pgrep -f rclone.serve")

        self.assertTrue(killed, "rclone was killed on the phone")
        self.assertTrue(rclone_after and rclone_after != rclone_before, f"rclone came back: {rclone_before} -> {rclone_after}")
        self.assertEqual(len(confirmed), SMALL_FILES + LARGE_FILES, "every upload eventually succeeded")
        c = make_client(self.e)
        listed = [k for k in all_keys(c) if k.startswith("load/")]
        self.assertEqual(sorted(listed), sorted(confirmed), "listing shows exactly the stored files, nothing half-written")
        t1 = time.time()
        bad = [k for k, d in confirmed.items() if sha(c.get_object(Bucket="shared", Key=k)["Body"].read()) != d]
        self.assertEqual(bad, [], "every confirmed upload reads back byte-for-byte")
        total = sum(len(v) for v in small.values()) + LARGE_FILES * LARGE_SIZE
        print(f"\n  crash under load on the phone: {len(confirmed)} files ({total / MB:.0f} MB) uploaded in {took:.0f} s "
              f"({total / MB / took:.1f} MB/s) with rclone killed midway; all read back in {time.time() - t1:.0f} s")
        for p in large_paths.values():
            os.remove(p)
        os.rmdir(tmp)

    def test_2_power_cut_mid_upload(self):
        confirmed, stop = {}, threading.Event()

        def writer(n):
            c = make_client(self.e, retries=1)
            i = 0
            while not stop.is_set():
                key = f"power/w{n}/f{i:05d}.bin"
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
        time.sleep(8)
        # power cut: the server and its rclone are killed at once, with no chance to clean up
        termux("pkill -9 -f pocket.server; pkill -9 -f rclone.serve")
        cut = time.time()
        time.sleep(2)
        stop.set()
        for t in threads:
            t.join()
        n_before = len(confirmed)
        self.assertGreater(n_before, 10)

        back = wait_storage(self.endpoint)       # the supervisor must restart it by itself
        check = on_phone(f"python /sdcard/Download/pocket-check.py {self.sd}/pocket")
        self.assertIn("db registry.db ok", check)
        self.assertIn("db stats.db ok", check)
        self.assertIn("temp 0", check, "half-written files from the cut are cleaned up on start")
        rclones = on_phone("pgrep -f rclone.serve").split()
        self.assertEqual(len(rclones), 1, f"exactly one storage server after the restart, no orphan: {rclones}")

        c = make_client(self.e)
        listed = [k for k in all_keys(c) if k.startswith("power/")]
        missing = set(confirmed) - set(listed)
        self.assertEqual(missing, set(), "every upload confirmed before the power cut is still there")
        bad = [k for k, d in confirmed.items() if sha(c.get_object(Bucket="shared", Key=k)["Body"].read()) != d]
        self.assertEqual(bad, [], "confirmed uploads read back byte-for-byte after the power cut")
        c.put_object(Bucket="shared", Key="after-restart.txt", Body=b"still works")
        self.assertEqual(c.get_object(Bucket="shared", Key="after-restart.txt")["Body"].read(), b"still works")
        extra = len([k for k in listed if k not in confirmed])
        print(f"\n  power cut on the phone: {n_before} confirmed uploads before the cut, all intact; "
              f"the supervisor had the server back in {back - cut:.0f} s; {extra} unconfirmed uploads were stored whole; "
              f"databases intact")


if __name__ == "__main__":
    unittest.main()
