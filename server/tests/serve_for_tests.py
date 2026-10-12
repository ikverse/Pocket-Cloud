"""A Pocket Cloud server for the Node and Kotlin library tests.

Prints one JSON line {"endpoint", "secret"} once ready, then answers commands on stdin, one JSON line each:
    move <app>      pretend the app's devices moved to another address
    rotate <app>    replace the keys of the app's devices
    enrolls <app>   how many enrollments the app made
    devices <app>   the app's devices
    quit
"""

import json
import os
import sys
import warnings

warnings.simplefilter("ignore")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from helpers import PocketServer  # noqa: E402


def main():
    srv = PocketServer().start()
    core = srv.core
    out = sys.stdout

    def say(obj):
        out.write(json.dumps(obj) + "\n")
        out.flush()

    say({"endpoint": srv.endpoint, "secret": core.settings.get("enroll_secret")})
    try:
        for line in sys.stdin:
            cmd, _, app = line.strip().partition(" ")
            reg = core.registry
            if cmd == "move":
                reg._con.execute("UPDATE devices SET ip='100.64.1.2' WHERE app_id=?", (app,))
                reg._changed()
                say({"ok": True})
            elif cmd == "rotate":
                keys = [reg.rotate_key(app, d["uid"])["access_key"] for d in reg.devices(app)]
                say({"ok": True, "keys": keys})
            elif cmd == "enrolls":
                core.stats.flush()
                n = core.stats.query("SELECT COUNT(*) AS n FROM requests WHERE op='Enroll' AND app=?", (app,))[0]["n"]
                say({"ok": True, "count": n})
            elif cmd == "devices":
                say({"ok": True, "devices": [{"uid": d["uid"], "name": d["name"], "ip": d["ip"]} for d in reg.devices(app)]})
            elif cmd == "quit":
                break
            else:
                say({"ok": False, "error": "unknown command " + cmd})
    finally:
        srv.stop()


if __name__ == "__main__":
    main()
