#!/bin/sh
# Copy Pocket Cloud to the phone over USB, into Download/pocket-cloud. Run on the PC (Git Bash):
#
#     phone/push.sh [adb-serial]
#
# Then, in Termux on the phone:  bash /sdcard/Download/pocket-cloud/phone/setup.sh
set -eu

export MSYS_NO_PATHCONV=1            # Git Bash would otherwise rewrite /sdcard/... into a Windows path
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
    # the first real phone, never the emulator
    SERIAL=$("$ADB" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "No phone connected over USB."; exit 1; }

HERE=$(cd "$(dirname "$0")/.." && pwd)
DEST=/sdcard/Download/pocket-cloud
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# only what runs on the phone: the server package, the web panel and these scripts
mkdir -p "$STAGE/pocket-cloud/server"
cp -r "$HERE/server/pocket" "$HERE/server/panel_web" "$STAGE/pocket-cloud/server/"
cp -r "$HERE/phone" "$STAGE/pocket-cloud/"
find "$STAGE" -name __pycache__ -type d -prune -exec rm -rf {} +

LOCAL="$STAGE/pocket-cloud"
command -v cygpath >/dev/null && LOCAL=$(cygpath -m "$LOCAL")   # adb.exe is a Windows program: give it a Windows path

echo "Copying to $SERIAL:$DEST"
"$ADB" -s "$SERIAL" shell rm -rf "$DEST"
"$ADB" -s "$SERIAL" shell mkdir -p /sdcard/Download
"$ADB" -s "$SERIAL" push "$LOCAL" /sdcard/Download/
COUNT=$("$ADB" -s "$SERIAL" shell "find $DEST -type f | wc -l" | tr -d '\r')
[ "$COUNT" -gt 0 ] || { echo "Nothing arrived on the phone."; exit 1; }
echo "Done: $COUNT files. In Termux: bash $DEST/phone/setup.sh"
