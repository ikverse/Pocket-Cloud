#!/data/data/com.termux/files/usr/bin/bash
# Set up (or update) Pocket Cloud inside Termux on the phone. Run in Termux after phone/push.sh:
#
#     bash /sdcard/Download/pocket-cloud/phone/setup.sh
#
# Safe to run again: it updates the code and keeps the data, the settings and the admin password.
set -euo pipefail

SRC=/sdcard/Download/pocket-cloud
APP=$HOME/pocket-cloud               # the code
STATE=$HOME/pocket-state             # settings, databases, logs
DATA=$HOME/pocket-data               # internal storage pool (Termux's private folder: fast, case-sensitive)
say() { printf '\n== %s\n' "$*"; }

[ -d "$SRC/server/pocket" ] || { echo "Can't read $SRC. Run termux-setup-storage, tap Allow, then try again."; exit 1; }

say "Installing Python, rclone and the Termux:API tools"
export DEBIAN_FRONTEND=noninteractive
pkg update -y -o Dpkg::Options::=--force-confnew
# no "recommended" extras: for python those are ~560 MB of compilers the server never uses
pkg install -y --no-install-recommends -o Dpkg::Options::=--force-confnew python rclone termux-api

say "Checking versions"
python --version
RCLONE_V=$(rclone version | awk 'NR == 1 {sub(/^v/, "", $2); print $2}')
echo "rclone $RCLONE_V"
python - "$RCLONE_V" <<'EOF'
import sys
v = tuple(int(x) for x in sys.argv[1].split("-")[0].split(".")[:2])
if v < (1, 65):
    sys.exit("rclone %s is too old: Pocket Cloud needs 1.65 or newer (serve s3 with --auth-proxy)." % sys.argv[1])
if v < (1, 75):
    print("Note: Pocket Cloud was tested with rclone 1.75; this is %s." % sys.argv[1])
EOF

say "Copying the code to $APP"
rm -rf "$APP.new"
mkdir -p "$APP.new"
cp -r "$SRC/server" "$SRC/phone" "$APP.new/"
rm -rf "$APP.old"
[ -d "$APP" ] && mv "$APP" "$APP.old"
mv "$APP.new" "$APP"
rm -rf "$APP.old"
chmod +x "$APP"/phone/*.sh "$APP/phone/start-pocket"

mkdir -p "$STATE/logs" "$DATA"

say "Finding the SD card"
SD=$("$APP/phone/sd-path.sh" || true)
if [ -z "$SD" ]; then
    echo "No SD card in the phone (or Termux has no folder on it yet). Pocket Cloud runs on internal storage"
    echo "until one is in; it then offers it in the control panel, under Storage › SD cards."
else
echo "SD card folder: $SD"
mkdir -p "$SD/pocket"

say "Checking the SD card"
T="$SD/pocket/.case-test-$$"
mkdir -p "$T"
echo a > "$T/Name.txt"; echo b > "$T/name.txt"
if [ "$(ls "$T" | wc -l)" = "1" ]; then CASE="ignores upper/lower case (Name.txt and name.txt are one file)"; else CASE="case-sensitive"; fi
rm -rf "$T"
echo "The card $CASE."
python - "$SD/pocket" <<'EOF'
import os, sys, time
p = os.path.join(sys.argv[1], ".speed-test")
block = os.urandom(4 * 1024 * 1024)
t = time.time()
with open(p, "wb") as f:
    for _ in range(32):
        f.write(block)
    f.flush(); os.fsync(f.fileno())
w = 128 / (time.time() - t)
t = time.time()
with open(p, "rb") as f:
    while f.read(4 * 1024 * 1024):
        pass
r = 128 / (time.time() - t)
os.remove(p)
print("Write %.0f MB/s, read %.0f MB/s (128 MB; the read may be served from memory)." % (w, r))
EOF
fi

say "Cloudflare tunnel (cloudflared, for share links)"
pkg install -y --no-install-recommends cloudflared || echo "cloudflared is not available; share links will only work on the tailnet."

say "Settings"
cd "$APP/server"
python - "$STATE" "$DATA" <<'EOF'
import os, shutil, sys
from pocket.settings import Settings, GB
state, data = sys.argv[1:]
first = not os.path.exists(os.path.join(state, "settings.json"))
s = Settings(state, {"internal_root": data, "rclone_binary": shutil.which("rclone") or "rclone"})
if "push" not in s._saved:      # push notices to apps are on from the start (the panel can switch them off)
    s.update({"push": {"enabled": True}})
    print("Push notices to apps switched on.")
if first:
    # size the internal pool from what this phone actually has free, leaving Android its reserve;
    # the SD pool is set by the server whenever a card is put to use
    reserve = s.get("pool.reserve_gb")
    internal = max(1, int(shutil.disk_usage(data).free / GB) - reserve - 1)
    s.update({"pool": {"internal_gb": internal}})
p = s.get("pool")
print("Internal pool %s GB, SD card pool %s GB, %s GB kept free for Android." % (p["internal_gb"], p["sd_gb"], p["reserve_gb"]))
print("Storage port %s, control panel port %s." % (s.get("storage_port"), s.get("panel_port")))
EOF

say "Automatic start"
mkdir -p "$HOME/.termux/boot"
cp "$APP/phone/start-pocket" "$HOME/.termux/boot/start-pocket"
chmod +x "$HOME/.termux/boot/start-pocket"
echo "Termux:Boot will start Pocket Cloud after every restart of the phone."

say "Starting Pocket Cloud"
"$APP/phone/stop.sh" || true
"$HOME/.termux/boot/start-pocket"
echo "Started. It waits for Tailscale, then listens on the phone's Tailscale address."
echo "Log: $STATE/logs/supervisor.log"
