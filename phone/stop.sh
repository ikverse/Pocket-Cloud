#!/data/data/com.termux/files/usr/bin/sh
# Stop Pocket Cloud cleanly (the server finishes its requests and closes its databases) and release the wake lock.
PIDF=$HOME/pocket-state/run.pid
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
    kill -TERM "$(cat "$PIDF")"
    for _ in $(seq 1 30); do kill -0 "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null || break; sleep 1; done
    echo "Pocket Cloud stopped."
else
    echo "Pocket Cloud is not running."
fi
termux-wake-unlock 2>/dev/null || true
