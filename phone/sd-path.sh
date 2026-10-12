#!/data/data/com.termux/files/usr/bin/sh
# Print Termux's own folder on the SD card (the only place on the card Android 10 lets Termux write),
# or nothing when no card is in. Android creates the folder once Termux has asked for storage access.
for d in "$HOME"/storage/external-1 /storage/*-*/Android/data/com.termux/files; do
    [ -d "$d" ] || continue
    d=$(cd "$d" && pwd -P)
    if touch "$d/.pocket-write-test" 2>/dev/null; then
        rm -f "$d/.pocket-write-test"
        echo "$d"
        exit 0
    fi
done
exit 1
