"""Health checks, run every few minutes. Each check raises an alert when it goes wrong and clears it when it
recovers. Phone readings (battery, temperature) come from Termux:API when it is installed."""

import json
import os
import shutil
import subprocess
import time
import urllib.request

from .settings import GB


def _size(b):
    """An allocation in its natural unit, as the panels show it: "3 GB", "1.5 GB", "5.17 MB"."""
    if b >= GB:
        return f"{round(b / GB, 2):g} GB"
    return f"{b / 1024 ** 2:.3g} MB"


def battery_status():
    """termux-battery-status as a dict, or None when not on the phone."""
    exe = shutil.which("termux-battery-status")
    if not exe:
        return None
    try:
        out = subprocess.run([exe], capture_output=True, timeout=15, text=True).stdout
        return json.loads(out)
    except Exception:
        return None


def notify(level, text):
    """Phone notification through Termux:API; silently does nothing elsewhere."""
    exe = shutil.which("termux-notification")
    if not exe:
        return
    subprocess.run([exe, "--title", "Pocket Cloud", "--content", text, "--id", "pocket-%d" % (hash(text) & 0xFFFF),
                    "--priority", "high" if level == "bad" else "default"], timeout=15, capture_output=True)


class Health:
    def __init__(self, core):
        self.core = core
        self.last = []          # [(name, ok, detail)] from the latest run
        self.last_run = 0.0
        self.temp_c = None      # latest phone temperature, if the phone reports one

    def too_hot(self):
        """(hot, temperature) from a fresh phone reading: hot above health.temp_high_c. (False, None) off the phone."""
        b = battery_status()
        temp = b.get("temperature") if b else None
        self.temp_c = temp
        return (temp is not None and temp > self.core.settings.get("health.temp_high_c")), temp

    def _check(self, results, name, ok, detail, kind=None, subject="", level="warn", text=None):
        results.append((name, ok, detail))
        if kind:
            stats = self.core.stats
            if ok:
                stats.clear_alert(kind, subject)
            else:
                stats.raise_alert(kind, subject, level, text or detail)

    def run(self):
        c = self.core
        h = c.settings.get("health")
        r = []

        # servers
        rclone_ok = c.rclone.alive()
        if rclone_ok:
            try:
                c.rclone.rc("core/version")
            except Exception:
                rclone_ok = False
        self._check(r, "Storage server", rclone_ok, "running" if rclone_ok else "not responding",
                    "server_down", "rclone", "bad", "The storage server is not responding.")
        gw_ok = False
        try:
            port = c.settings.get("storage_port")
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/_pocket/health", timeout=5) as resp:
                gw_ok = resp.status == 200
        except Exception:
            pass
        self._check(r, "Gateway", gw_ok, "running" if gw_ok else "not responding",
                    "server_down", "gateway", "bad", "The gateway is not responding.")
        if c.push.enabled:
            push_ok = c.push.healthy()
            self._check(r, "Push server", push_ok, "running" if push_ok else "not responding", "push_down", "", "warn",
                        "The push server is not responding; apps get no change notices until it is back.")
        else:
            c.stats.clear_alert("push_down", "")

        # storages
        sd_ok = c.layout.has_sd and _writable(c.layout.root("sd"))
        # a card swapped out on purpose (none in use, none pulled out) is not a fault: nothing is missing
        shelved = c.cards.enabled and not c.layout.has_sd and not c.cards.active_card()
        self._check(r, "SD card", sd_ok or shelved, "none in use" if shelved else "present" if sd_ok else "missing or read-only",
                    "sd_missing", "", "bad", c.cards.missing_text() if not c.layout.has_sd else
                    "The SD card is missing or can't be written.")
        int_free = c.usage.free("int")
        low = h["internal_free_low_gb"] * GB
        self._check(r, "Internal free space", int_free >= low, f"{int_free / GB:.1f} GB free",
                    "internal_low", "", "warn", f"Internal storage has only {int_free / GB:.1f} GB free.")
        if sd_ok:
            du = shutil.disk_usage(c.layout.root("sd"))
            pct = du.free / du.total * 100 if du.total else 0
            self._check(r, "SD card free space", pct >= h["sd_free_low_pct"], f"{pct:.0f}% free",
                        "sd_low", "", "warn", f"The SD card has only {pct:.0f}% free.")

        # pool and apps
        pool = c.usage.pool()
        pct = pool["used"] / pool["total"] * 100 if pool["total"] else 100
        self._check(r, "Storage pool", pct < 100, f"{pct:.0f}% used", "pool_full", "", "bad",
                    "The storage pool is full; uploads are refused for every app.")
        self._check(r, "Storage pool level", pct < h["pool_warn_pct"], f"{pct:.0f}% used", "pool_warn", "", "warn",
                    f"The storage pool is {pct:.0f}% used.")
        for app in c.registry.apps():
            used = c.usage.app_used(app["id"])
            apct = used / app["alloc_bytes"] * 100 if app["alloc_bytes"] else 100
            self._check(r, "App " + app["id"], apct < 100, f"{apct:.0f}% of allocation", "app_full", app["id"], "bad",
                        f"{app['name']} has reached its {_size(app['alloc_bytes'])} allocation.")
            self._check(r, "App level " + app["id"], apct < app["warn_pct"], f"{apct:.0f}%", "app_warn", app["id"], "warn",
                        f"{app['name']} is at {apct:.0f}% of its {_size(app['alloc_bytes'])} allocation.")

        # phone
        b = battery_status()
        if b:
            batt, temp, plugged = b.get("percentage"), b.get("temperature"), b.get("plugged")
            c.stats.phone_reading(batt, plugged, temp)
            if batt is not None:
                self._check(r, "Battery", batt >= h["battery_low_pct"], f"{batt}% · {plugged}", "battery_low", "", "bad",
                            f"Battery is at {batt}%. Is the charger connected?")
            if temp is not None:
                self.temp_c = temp
                self._check(r, "Temperature", temp <= h["temp_high_c"], f"{temp:.1f} °C", "temp_high", "", "warn",
                            f"Phone temperature is {temp:.1f} °C.")

        # error rate over the last hour (server errors only; 404s and refusals are normal answers)
        rows = c.stats.query("SELECT COUNT(*) AS n, SUM(status >= 500) AS e FROM requests WHERE ts > ?", (time.time() - 3600,))
        n, e = rows[0]["n"] or 0, rows[0]["e"] or 0
        rate = e / n * 100 if n else 0
        self._check(r, "Error rate", n < 20 or rate < h["error_rate_pct"], f"{rate:.1f}% of {n} requests",
                    "error_rate", "", "warn", f"{rate:.1f}% of requests failed in the last hour.")

        self.last = r
        self.last_run = time.time()
        return r


def _writable(root):
    if not os.path.isdir(root):
        return False                # never create it: a pulled card's folder must stay gone
    try:
        probe = os.path.join(root, ".pocket-probe")
        with open(probe, "w") as f:
            f.write("ok")
        os.remove(probe)
        return True
    except OSError:
        return False
