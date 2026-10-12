"""rclone's auth hook. rclone runs this once per access key (re-checked every 5 minutes) with
{"user": <access key>, "client_ip": ...} on stdin, and expects the key's secret and backend on stdout.
An empty answer refuses the key. Kept tiny: it runs as a separate process for each lookup."""

import json
import os
import sqlite3
import sys


def answer(request, state_dir):
    key = request.get("user") or ""
    uri = "file:" + os.path.join(state_dir, "registry.db").replace("\\", "/") + "?mode=ro"
    con = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        row = con.execute(
            "SELECT d.app_id, d.uid, d.secret, d.blocked, a.status, a.family FROM devices d JOIN apps a ON a.id = d.app_id"
            " WHERE d.access_key = ?", (key,)).fetchone()
    finally:
        con.close()
    if not row:
        return {}
    app_id, uid, secret, blocked, status, family = row
    if blocked or status != "active":
        return {}
    try:
        with open(os.path.join(state_dir, "flags.json"), encoding="utf-8") as f:
            nc = bool(json.load(f).get("internal_full"))
    except (OSError, ValueError):
        nc = False
    suffix = "_nc" if nc else ""
    return {
        "type": "combine",
        "upstreams": f"shared=p_{app_id}_s{suffix}: device=p_{app_id}_d_{uid}{suffix}:"
                     + (f" family=p__family_s{suffix}:" if family else ""),
        "_root": "",
        "_secret_access_key": secret,
    }


def main():
    state_dir = os.environ.get("POCKET_STATE", "")
    try:
        out = answer(json.load(sys.stdin), state_dir)
    except Exception as e:
        try:
            with open(os.path.join(state_dir, "logs", "authproxy.log"), "a", encoding="utf-8") as f:
                f.write("%r\n" % (e,))
        except OSError:
            pass
        out = {}
    sys.stdout.write(json.dumps(out))


if __name__ == "__main__":
    main()
