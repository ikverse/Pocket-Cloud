# pocket-cloud (Python)

Use Pocket Cloud from Python apps and scripts. Two ways in:

- **`pocket.app("name")`**: the v2 app API. Files, JSON, backups, folder sync, the change feed, releases and
  notifications on Pocket Cloud's own `/_pocket/` routes. Standard library only for these.
- **`pocket.connect("name")`**: a ready boto3 S3 client, unchanged from 0.1.

```bash
pip install -e clients/python        # from the Pocket-Cloud folder; gives the `pocket` command too
```

## Machine setup (once per machine)

Save the machine setup file from the control panel's Settings page as `~/.pocket/config`:

```ini
[pocket]
endpoint = http://pocket:9000
enroll_secret = ...
```

or set `POCKET_ENDPOINT` and `POCKET_ENROLL_SECRET` (CI). `POCKET_DEVICE_ID` / `POCKET_DEVICE_NAME` optionally
fix the device's identity; `POCKET_HOME` moves `~/.pocket`. The first time an app opens on a machine it enrolls
and saves its key in `~/.pocket/apps/<app>.json`. `app()` and `connect()` share that key.

## `pocket.app()`

```python
import pocket_cloud as pocket

p = pocket.app("egx")                                # bucket defaults to "shared" everywhere

p.put("notes/today.txt", "hello")                    # bytes or str -> Entry(bucket, key, size, etag, modified)
p.get("notes/today.txt")                             # b"hello"; missing: pocket.NotFound
p.put_file("reports/r.pdf", "r.pdf")                 # streams, keeps the file's time
p.get_file("reports/r.pdf", "copy.pdf")              # streams to a temp file, then renames
p.stat("reports/r.pdf")                              # Entry or None
for e in p.list("reports/", folders=True): ...       # every page; folders roll up below the next "/"
p.move("inbox/a.txt", "done/a.txt")                  # on the server, ETag and time kept
p.delete("done/a.txt", if_match=e.etag)

settings = p.get_json("settings.json", bucket="device", default={})
p.put_json("settings.json", settings, bucket="device")

p.backup("data/ledger.db", keep=14)                  # backups/ledger.db/<UTC yyyymmdd-HHMMSS>-ledger.db
p.restore_latest("backups/ledger.db/", "ledger.db")

result = p.sync("~/Notes", prefix="notes/")          # SyncResult(uploaded, downloaded, deleted_*, conflicts)

feed = p.changes()                                   # no cursor: no changes, feed.cursor is "now"
list(feed); cursor = feed.cursor
for c in p.changes(cursor, wait=30): print(c.kind, c.key)
handle = p.on_change(lambda c: print(c.kind, c.key)); handle.stop()

p.publish("dist/egx-5.9.1.apk", "5.9.1", notes="Faster reports.", platform="android-arm64")
rel = p.releases().latest(platform="android-arm64")  # Release or None
rel.download("downloads/")                           # checks size + sha256
p.releases_url()                                     # key-less publish URL on the tailnet

p.notify("6 new calls", title="Evening calls are ready", tags=["chart_with_upwards_trend"])
p.share("reports/r.pdf", expires=3600).url
p.quota()["free"]
```

When opened, the app asks `/_pocket/health` which features the server has. On an older server, a method whose
feature is missing raises `FeatureOff` with code `FeatureMissing`.

**Conditional writes.** `put(..., if_match=etag)` and `delete(..., if_match=etag)` write only if the file still
has that ETag, else `PreconditionFailed`. ETags are opaque quoted strings: compare them, don't parse them.

**Retries.** Reads, `stat`, `list`, `changes` and conditional writes are retried 3 times (0.5 s, 1 s, 2 s, or
the server's `Retry-After`) when the server is unreachable or answers 503. Unconditional writes, `move`,
`delete` and `notify` are not. A `403` saying the device moved, or that the key is unknown, re-enrolls once
and retries.

### Backups

`backup(path, keep=7, prefix="backups/")` uploads to `<prefix><name>/<UTC yyyymmdd-HHMMSS>-<name>` and then
deletes all but the `keep` newest there. **SQLite databases** (recognised by their header) are first copied
with `sqlite3`'s backup API, so a database that is open and being written (WAL included) is backed up
consistently. Add a retention rule for the same prefix in the control panel as the server-side safety net.

### Sync rules

- Two-way between a local folder and `prefix` in a bucket. State lives in `<folder>/.pocket-sync.json`.
  That file, and anything whose name starts with `.pocket-`, is never uploaded.
- **The first run never deletes.** Files on one side only are copied over. Files on both sides that differ
  become conflicts.
- Later runs read the change feed from the saved cursor. They fall back to a full listing when the cursor has
  expired or the server has no change feed.
- **Conflicts** happen when a file changed on both sides. The remote file stays under its key. The local
  version is uploaded, and kept locally, as `<name> (conflict <device name> <yyyy-mm-dd HHMM>).<ext>`. Both
  keys are listed in `result.conflicts`.
- An edit wins over a delete: a file edited on one side and deleted on the other is copied back, not removed.
- Remote writes use `if-match` / `if-none-match: *`, so a file that changes during the run becomes a conflict,
  never a loss.
- **Mass-delete guard.** If a run would delete more than 50 files (local plus remote), it deletes nothing,
  still does everything else, and raises `PocketError` with code `MassDelete` and the count. The deletes stay
  pending. Run `sync(..., allow_mass_delete=True)` if they are really wanted.

### Errors

```
PocketError                 .code (server "error" or a library code), .status (HTTP status or 0)
 ├─ NotFound                NoSuchKey, NoSuchBucket, NotFound
 ├─ PreconditionFailed      PreconditionFailed, KeyExists
 ├─ QuotaExceeded           QuotaExceeded, PoolFull, PhoneStorageLow
 ├─ ReadOnly                ReadOnly
 ├─ AccessDenied            bad key or signature, blocked device, enrollment refused
 ├─ CursorExpired           .cursor: where to carry on after a full resync
 ├─ RateLimited             .retry_after seconds (notify)
 ├─ FeatureOff              FeatureOff, FeatureMissing (server lacks the feature), SharesOff
 └─ Unreachable             connection failed, timeout, ServiceUnavailable after retries
```

Library codes: `FeatureMissing`, `MassDelete`, `ChecksumMismatch`, `NotConfigured`, `Unreachable`, `BadKey`.

## The `pocket` command

```bash
pocket --app egx ls backups/ -l              # -r: everything below, not just one level
pocket --app egx put report.pdf reports/2026/report.pdf
pocket --app egx get reports/2026/report.pdf [dest | -]
pocket --app egx backup data/ledger.db --keep 14
pocket --app egx sync ./notes --prefix notes/ [--allow-mass-delete]
pocket --app egx notify "Evening calls are ready" --title EGX --tag chart_with_upwards_trend
pocket --app egx publish dist/egx-5.9.1.apk 5.9.1 --notes "Faster reports." --platform android-arm64
```

`--app` can come from `POCKET_APP`, and `--bucket device` works on the file commands. The exit code is `1`
with the message on a Pocket Cloud error, and `2` for a usage error.

## `pocket.connect()` (S3, unchanged)

```python
s3 = pocket.connect("egx")                   # a ready boto3 S3 client
s3.put_object(Bucket="shared", Key="prices/today.json", Body=data)
s3.get_object(Bucket="device", Key="settings.json")
```

Use it for S3 tools, and for files over 2 GiB (multipart).

## Tests

```bash
cd clients/python/tests
python -m unittest test_signing test_app test_sync test_cli    # against the in-memory fake server
```

`tests/fake_server.py` is the fake server of `docs/app-api.md` section 11: a real HTTP server on `127.0.0.1`
with the real signature check and `/_fake/` test controls. Tests named `*_fake_only` use those controls.
`test_signing.py` checks the section 12 signing vectors. `test_client.py` runs `connect()` against the real
server (`server/tests/helpers.PocketServer`, needs rclone).
