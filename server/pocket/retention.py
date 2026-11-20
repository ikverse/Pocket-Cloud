"""Retention rules: per app, which old files under a prefix the nightly job removes (docs/app-api.md section 7).

A rule covers the keys under its prefix in one bucket (for 'device', every device's private space on its
own). A file is kept if it is among the keep_last newest by modification time, or younger than keep_days;
everything else under the prefix goes. A key under several rules goes only if every one of them would remove
it. latest.json and latest*.yml directly in releases/ are never removed.

Removed files go into the 14-day undo like any delete, so a bad rule can be reverted, and a rule that would
remove more than 500 files or more than half of the files under its prefix in one night removes nothing and
raises an alert instead.
"""

import os
import time

from . import undo
from .layout import FAMILY, remove_empty_dirs, safe_key, walk_files

MAX_RULES = 20
GUARD_FILES = 500


class RuleError(ValueError):
    pass


def validate(rules, app_id):
    """The checked, normalised list, or RuleError naming the rule ("Rule 2: ...")."""
    if not isinstance(rules, list):
        raise RuleError("retention is a list of rules.")
    if len(rules) > MAX_RULES:
        raise RuleError(f"At most {MAX_RULES} rules per app.")
    allowed = ("family",) if app_id == FAMILY else ("shared", "device")
    out, seen = [], set()
    for i, r in enumerate(rules, 1):
        def fail(msg):
            raise RuleError(f"Rule {i}: {msg}")
        if not isinstance(r, dict):
            fail("a rule is an object with bucket, prefix, keep_last and keep_days.")
        bucket, prefix = r.get("bucket"), r.get("prefix")
        if bucket not in allowed:
            fail("bucket is " + " or ".join(f"'{b}'" for b in allowed) + ".")
        if not isinstance(prefix, str) or not prefix or prefix.startswith("/") or "\x00" in prefix:
            fail("prefix is a non-empty key prefix such as 'backups/'.")
        if len(prefix.encode("utf-8", "surrogatepass")) > 1024:
            fail("prefix is at most 1024 bytes.")
        vals = {}
        for name, hi in (("keep_last", 1000), ("keep_days", 3650)):
            v = r.get(name)
            if v is not None and (isinstance(v, bool) or not isinstance(v, int) or not 1 <= v <= hi):
                fail(f"{name} is a whole number from 1 to {hi}, or null.")
            vals[name] = v
        if vals["keep_last"] is None and vals["keep_days"] is None:
            fail("set keep_last, keep_days or both.")
        if (bucket, prefix) in seen:
            fail(f"there is already a rule for {bucket} {prefix}.")
        seen.add((bucket, prefix))
        out.append({"bucket": bucket, "prefix": prefix, **vals})
    return out


def protected(key):
    if not key.startswith("releases/"):
        return False
    name = key[len("releases/"):]
    return "/" not in name and (name == "latest.json" or (name.startswith("latest") and name.endswith(".yml")))


def _spaces(core, app_id, bucket):
    """(owner app, space) pairs a rule's bucket covers."""
    if bucket in ("shared", "family"):
        return [(app_id, "shared")]
    return [(app_id, "device:" + d["uid"]) for d in core.registry.devices(app_id)]


def _files(core, owner, space, prefix):
    """key -> (mtime, size, arrived) under prefix, internal storage first (as rclone sees it). Only the prefix's
    folder is walked."""
    cut = prefix.rfind("/")
    parts = prefix[:cut].split("/") if cut > 0 else []
    if cut == 0 or (parts and safe_key("/".join(parts)) is None):
        return {}
    arrived = core.meta.arrivals(owner, space)
    out = {}
    for storage in core.layout.storages():
        base = core.layout.space_path(storage, owner, space)
        for path, st in walk_files(os.path.join(base, *parts)):
            key = "/".join(os.path.relpath(path, base).split(os.sep))
            if key.startswith(prefix):
                out.setdefault(key, (st.st_mtime, st.st_size, arrived.get(key)))
    return out


def _would_remove(rule, files, now):
    """keep_last counts the newest by modification time; keep_days ages a file from the later of its modification
    time and its arrival on the server, so a fresh upload carrying an old date is young."""
    keys = [k for k in files if not protected(k)]
    keep = set()
    if rule["keep_last"]:
        keep |= set(sorted(keys, key=lambda k: files[k][0], reverse=True)[:rule["keep_last"]])
    if rule["keep_days"]:
        keep |= {k for k in keys if now - max(files[k][0], files[k][2] or 0) < rule["keep_days"] * 86400}
    return set(keys) - keep


def run(core, now=None, force=False):
    """Apply every app's rules once. Returns (result, detail) for the job record. The nightly run holds back a
    rule that would remove too much (the guard); force (the control panel's "run now") applies it anyway."""
    now = now or time.time()
    total_files = total_bytes = 0
    guarded = []
    for app in core.registry.apps():
        app_id = app["id"]
        rules = core.registry.retention_rules(app_id)
        if not rules:
            continue
        # what each rule would remove, per space
        plans, files_of = [], {}
        for r in rules:
            per_space, blocked = {}, False
            for owner, space in _spaces(core, app_id, r["bucket"]):
                files = _files(core, owner, space, r["prefix"])
                files_of.setdefault((owner, space), {}).update(files)
                gone = _would_remove(r, files, now)
                if not force and (len(gone) > GUARD_FILES or len(gone) * 2 > len(files)):
                    blocked = True
                per_space[(owner, space)] = gone
            subject = f"{app_id}:{r['bucket']}:{r['prefix']}"
            if blocked:
                n = sum(len(g) for g in per_space.values())
                core.stats.raise_alert("retention_guard", subject, "warn",
                                       f"Retention rule {r['bucket']} {r['prefix']} of {app_id} would remove {n} files "
                                       f"tonight (more than {GUARD_FILES}, or more than half of the files under it): "
                                       f"it removed nothing. Check the rule in the control panel; if it is right, run "
                                       f"\"Retention rules\" now on the Jobs page to apply it once anyway.")
                core.log(f"retention {app_id} {r['bucket']} {r['prefix']}: would remove {n} files; held back")
                guarded.append(subject)
                per_space = {k: set() for k in per_space}
            else:
                core.stats.clear_alert("retention_guard", subject)
            plans.append((r, per_space, blocked))
        # a key goes only if every rule covering it would remove it
        removed = {}          # (owner, space) -> {key: size}
        for (owner, space), files in files_of.items():
            for key, (_, size, _) in files.items():
                covering = [p for p in plans if (owner, space) in p[1] and key.startswith(p[0]["prefix"])]
                if covering and all(key in p[1][(owner, space)] for p in covering):
                    removed.setdefault((owner, space), {})[key] = size
        freed_by_space = {}
        for (owner, space), keys in removed.items():
            done = {}
            for key in sorted(keys):
                sz = _remove(core, owner, space, key)
                if sz is not None:
                    done[key] = sz
            freed_by_space[(owner, space)] = done
            if done:
                core.forget(owner, space, list(done))
        app_files = app_bytes = 0
        for r, per_space, blocked in plans:
            n = b = 0
            for sp in per_space:
                for key, sz in freed_by_space.get(sp, {}).items():
                    if key.startswith(r["prefix"]):
                        n += 1
                        b += sz
            core.registry.retention_ran(app_id, r["bucket"], r["prefix"], now, n, b)
            if not blocked:
                core.log(f"retention {app_id} {r['bucket']} {r['prefix']}: {n} files removed, {b} bytes freed")
        for done in freed_by_space.values():
            app_files += len(done)
            app_bytes += sum(done.values())
        if app_files:
            core.notify_change(app_id)
            core.stats.event("retention", app_id, "info",
                             f"Retention rules removed {app_files} old files ({app_bytes / 1024 ** 2:.1f} MB) from "
                             f"{app_id}; they stay in the undo for {core.settings.get('jobs.undo_days')} days.")
        total_files += app_files
        total_bytes += app_bytes
    detail = f"{total_files} files, {total_bytes / 1024 ** 2:.1f} MB removed by retention rules"
    if guarded:
        detail += f" · {len(guarded)} rules held back (would remove too much): " + ", ".join(guarded[:5])
    return ("warn" if guarded else "ok"), detail


def _remove(core, owner, space, key):
    """Delete one key like a delete through the gateway (undo, usage, change record). Returns bytes freed."""
    layout = core.layout
    with core.key_lock((owner, space, key)):
        kept = undo.keep(layout, owner, space, key, "deleted", "retention")
        if not kept:
            return None
        freed = 0
        for s, _, sz in kept:
            p = layout.key_path(s, owner, space, key)
            try:
                os.remove(p)
            except FileNotFoundError:
                pass
            remove_empty_dirs(os.path.dirname(p), layout.space_path(s, owner, space))
            core.usage.add(owner, space, s, -sz)
            freed += sz
        core.meta.delete(owner, space, key)
    core.record_change(owner, space, key, "delete", "retention", None)
    return freed
