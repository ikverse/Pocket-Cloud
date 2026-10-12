#!/data/data/com.termux/files/usr/bin/bash
# Keeps Pocket Cloud running: waits for Tailscale, starts the server on the phone's Tailscale address, and starts
# it again if it stops or the address changes. Started by start-pocket; one copy at a time. The server finds the
# SD card itself (and follows card swaps), so it starts with or without one.
APP=$HOME/pocket-cloud
STATE=$HOME/pocket-state
DATA=$HOME/pocket-data
CARDS="/storage/*-*/Android/data/com.termux/files"     # Termux's folder on whatever card is in the phone
PIDF=$STATE/run.pid
mkdir -p "$STATE/logs"

if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
    echo "Pocket Cloud is already running (pid $(cat "$PIDF"))."
    exit 0
fi
echo $$ > "$PIDF"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# The phone's Tailscale address: the source address the phone would use to reach Tailscale's own DNS server.
# When Tailscale is off, that route goes out over Wi-Fi instead, so anything outside 100.64.0.0/10 means "not up".
tailscale_ip() {
    python - <<'EOF'
import ipaddress, socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.connect(("100.100.100.100", 53))
    ip = s.getsockname()[0]
    if ipaddress.ip_address(ip) in ipaddress.ip_network("100.64.0.0/10"):
        print(ip)
except OSError:
    pass
EOF
}

# The phone's home Wi-Fi address, for the LAN fast path (used only when it is switched on in the control panel).
lan_ip() {
    termux-wifi-connectioninfo 2>/dev/null | python -c '
import json, sys
try:
    print(json.load(sys.stdin).get("ip") or "")
except Exception:
    pass'
}

CHILD=
trap 'log "stopping"; [ -n "$CHILD" ] && kill -TERM "$CHILD" 2>/dev/null; wait "$CHILD" 2>/dev/null; rm -f "$PIDF"; exit 0' TERM INT

waited=
while true; do
    IP=$(tailscale_ip)
    if [ -z "$IP" ]; then
        [ -z "$waited" ] && log "waiting for Tailscale"; waited=1; sleep 15; continue
    fi
    waited=
    LAN=$(lan_ip)
    log "starting on $IP${LAN:+ (Wi-Fi $LAN)}"
    cd "$APP/server" || exit 1
    python -m pocket.server --state "$STATE" --internal-root "$DATA" --sd-search "$CARDS" \
        --rclone "$(command -v rclone)" --listen "127.0.0.1,$IP" --lan-address "$LAN" >> "$STATE/logs/server-console.log" 2>&1 &
    CHILD=$!
    while kill -0 "$CHILD" 2>/dev/null; do
        sleep 30
        NOW=$(tailscale_ip)
        if [ -n "$NOW" ] && [ "$NOW" != "$IP" ]; then
            log "Tailscale address changed from $IP to $NOW; restarting"
            kill -TERM "$CHILD"
        fi
    done
    wait "$CHILD"; code=$?
    CHILD=
    # a server that died without stopping its storage server leaves that rclone running with the old apps and keys
    if pkill -f "rclone serve s3 --config $STATE/rclone.conf"; then log "stopped the storage server it left running"; fi
    log "server stopped (exit $code); starting again in 5 s"
    sleep 5
done
