"""A demo Pocket Cloud for checking the web panel and the Android app by eye.

Starts a full server (real rclone, gateway, control panel) and fills it with real traffic: apps enroll, upload,
download, overwrite and delete files, and the nightly jobs run. Nothing is invented: every number the panel
shows comes from that traffic.

    python demo_server.py [panel_port] [storage_port]      (defaults 8765, 9765; stop with Ctrl+C)

The demo admin password is in DEMO_PASSWORD below (a test value for this local demo only).
"""

import os
import random
import sys
import time
import warnings

warnings.simplefilter("ignore")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from helpers import PocketServer  # noqa: E402

DEMO_PASSWORD = "pocket-demo-1"
APPS = [  # app, devices (id, name), files to write
    ("egx-analyzer", [("desktop-0001", "Desktop PC"), ("s24ultra-001", "Galaxy S24 Ultra")], 60),
    ("signal-lab", [("note9-pocket", "pocket (this phone)"), ("desktop-0001", "Desktop PC")], 90),
    ("racewall", [("desktop-0001", "Desktop PC")], 25),
    ("deskglow", [("note9-pocket", "pocket (this phone)")], 10),
    ("spen-lab", [("s24ultra-001", "Galaxy S24 Ultra")], 8),
]


def main():
    panel_port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    storage_port = int(sys.argv[2]) if len(sys.argv) > 2 else 9765
    srv = PocketServer(panel_port=panel_port, storage_port=storage_port)
    srv.port = storage_port
    srv.start()
    core = srv.core
    rnd = random.Random(11)
    print("filling with traffic…", flush=True)
    for app, devices, count in APPS:
        clients = []
        for uid, name in devices:
            status, e = srv.enroll(app, uid, name)
            clients.append(srv.client(e))
        for i in range(count):
            c = rnd.choice(clients)
            space = "shared" if rnd.random() < 0.8 else "device"
            key = f"{rnd.choice(['data', 'cache', 'reports', 'state'])}/{i:04d}.{rnd.choice(['json', 'bin', 'csv'])}"
            c.put_object(Bucket=space, Key=key, Body=os.urandom(rnd.randint(2_000, 900_000)))
            for _ in range(rnd.randint(0, 4)):
                c.get_object(Bucket=space, Key=key)["Body"].read()
            if rnd.random() < 0.1:
                c.put_object(Bucket=space, Key=key, Body=os.urandom(rnd.randint(2_000, 50_000)))
            if rnd.random() < 0.06:
                c.delete_object(Bucket=space, Key=key)
            c.list_objects_v2(Bucket=space, MaxKeys=20)
        # some files are older, so the nightly move has work to do
        base = srv.files("int", "apps", app, "shared")
        for root, _, files in os.walk(base):
            for f in files:
                if rnd.random() < 0.5:
                    t = time.time() - rnd.randint(8, 30) * 86400
                    os.utime(os.path.join(root, f), (t, t))
    core.registry.update_app("egx-analyzer", name="EGX Analyzer", notes="Price history and analysis exports")
    core.registry.update_app("signal-lab", name="Signal Lab", notes="Crypto candles and scanner state")
    core.registry.update_app("racewall", name="RaceWall", notes="F1 session timing archives")
    core.registry.update_app("deskglow", name="Deskglow")
    core.registry.update_app("spen-lab", name="S Pen Lab")
    core.usage.scan()
    pool = core.usage.pool()
    core.registry.set_allocation("racewall", 6 * 1024 ** 3, pool["total"], core.usage.app_used("racewall"))
    core.registry.set_allocation("deskglow", int(core.usage.app_used("deskglow") * 1.15) + 1, pool["total"], core.usage.app_used("deskglow"))
    core.run_jobs(["nightly"])
    core.health.run()
    core.admin.set_password(DEMO_PASSWORD)
    print(f"ready: control panel http://127.0.0.1:{panel_port}  storage http://127.0.0.1:{storage_port}", flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        srv.stop()


if __name__ == "__main__":
    main()
