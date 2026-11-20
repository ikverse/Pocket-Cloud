# Pocket Cloud v2 — the app-facing API

This is the contract for step 1 of `docs/plan-v2.md`. Session A builds the server side of it; sessions B, C and
D build the Python, Kotlin-lite and Node libraries on it; sessions E, F and G read `features` and the panel
routes from it. **Nobody edits this file in step 1.** If something here is wrong or missing, stop and tell the
user; it changes once, in step 2.

Everything below is in addition to what exists today. S3 (`connect()`), `/_pocket/enroll`, `/_pocket/quota`,
`/_pocket/conflicts`, `/_pocket/mirror-report`, `/_pocket/share(s)` and the push server keep working exactly as
they do now.

Contents

1. Basics: addresses, signing, errors, limits
2. `GET /_pocket/health` and the feature list
3. `GET /_pocket/app` — what is switched on for this app
4. Plain file routes: read, write, list, delete, move
5. Change feed
6. Publish folder and `latest.json`
7. Retention rules
8. App notifications
9. Panel API additions
10. The library surface (Python, Kotlin-lite, Node)
11. The fake server for library tests
12. Test vectors

---

## 1. Basics

### Addresses

All routes in this document are served by the **gateway**, on the same address and port as S3 (`storage_port`,
9000 by default), on every address the gateway listens on (Tailscale, and the LAN when that is switched on).
The one exception is the publish folder (section 6), which answers key-less requests only on the Tailscale
address.

The endpoint a library uses is the one it already has: `endpoint` from the machine setup file, or the
`endpoints.tailscale` / `endpoints.lan` returned by enrollment.

### Signing

Every route marked **signed** is signed with the device key exactly like `/_pocket/quota` today
(`sigv4.sign_pocket` / `verify_pocket`):

```
x-pocket-key:  <access key>
x-pocket-date: <unix seconds>          within 15 minutes of the server's clock
x-pocket-sign: hex(HMAC-SHA256(secret, METHOD + "\n" + PATH + "\n" + QUERY + "\n" + DATE + "\n" + sha256hex(BODY)))
```

- `METHOD` is upper case. `PATH` is the request path **exactly as sent on the wire, still percent-encoded**
  (e.g. `/_pocket/files/shared/notes/hello%20world.txt`). `QUERY` is the raw query string exactly as sent,
  without the `?`, `""` when there is none. The server does not re-encode or sort either; the client must sign
  the bytes it sends.
- `sha256hex(BODY)` is the lower-case hex SHA-256 of the request body; for no body it is the hash of the empty
  string (`e3b0c442…b855`).
- **Uploads (`PUT /_pocket/files/...`) also send `x-pocket-content-sha256: <sha256hex(BODY)>`.** The server
  checks the signature using that header's value before it reads the body, so a refused upload is answered
  before the body is sent (the gateway already holds `100 Continue` back). It then hashes the body as it
  streams in; if the result differs from the header, the upload is thrown away, nothing is stored or changed,
  and the answer is `400 BadDigest`. A client streaming a large file therefore reads it twice: once to hash,
  once to send. For every other route the header is optional; when present it must match too.
- The device checks of S3 requests apply to every signed route: unknown key, blocked device, app waiting for
  approval, key used from another device's address.

### Encoding keys in paths

A key is the object name inside a bucket, the same string S3 uses (`notes/2026/a.txt`). In a path it is
percent-encoded **per segment**: every byte outside `A-Z a-z 0-9 - _ . ~` is `%XX`, and `/` between segments
is kept as `/`. Keys are UTF-8, 1–1024 bytes, may not start with `/`, may not contain an empty segment (`//`),
a `.` or `..` segment, a NUL byte, or end in `/`. Anything else is `400 BadKey`.

### Buckets

`shared` (all the app's devices), `device` (this device only) and, for apps in the family, `family`.
Any other name is `404 NoSuchBucket`. The family bucket counts against the family app's allocation, as in S3.

### Errors

Every error is JSON, `Content-Type: application/json`:

```json
{"error": "QuotaExceeded", "message": "egx would go over its 4.00 GB allocation."}
```

`message` is for people and may change; `error` is the stable code clients act on. A `HEAD` error has the same
status and an `x-pocket-error-code: <code>` header and no body.

| Status | `error` | When | Library error type |
|---|---|---|---|
| 400 | `BadRequest` | Malformed JSON, missing or wrong field, bad query value | `PocketError` |
| 400 | `BadKey` | Key breaks the rules above | `PocketError` |
| 400 | `BadDigest` | Upload body did not match `x-pocket-content-sha256` | `PocketError` |
| 400 | `EntityTooLarge` | Body over the limit for that route | `PocketError` |
| 403 | `InvalidAccessKeyId` | Key not known (re-enroll once, then give up) | `AccessDenied` |
| 403 | `SignatureDoesNotMatch` / `MissingSignature` | Signing wrong | `AccessDenied` |
| 403 | `RequestTimeTooSkewed` | `x-pocket-date` more than 15 min off | `AccessDenied` |
| 403 | `AccessDenied` | Blocked device, app waiting, read of another device's space. With header `x-pocket-error: device-address-changed`: enroll again and retry once | `AccessDenied` |
| 403 | `ReadOnly` | Server is in read-only mode (writes, deletes, moves) | `ReadOnly` |
| 403 | `QuotaExceeded` | Over the app's allocation or the device cap | `QuotaExceeded` |
| 403 | `FeatureOff` | The feature exists but is switched off for this app (publish, notify) | `FeatureOff` |
| 404 | `NoSuchKey` | No file at that key | `NotFound` |
| 404 | `NoSuchBucket` | Bucket is not one of the app's | `NotFound` |
| 404 | `NotFound` | Unknown `/_pocket/` route (an older server: see `features`) | `NotFound` |
| 409 | `KeyExists` | Move target exists and `overwrite` was false | `PreconditionFailed` |
| 410 | `CursorExpired` | Change-feed cursor older than what is kept | `CursorExpired` |
| 412 | `PreconditionFailed` | `if-match` / `if-none-match` did not hold | `PreconditionFailed` |
| 416 | `InvalidRange` | `Range` outside the file | `PocketError` |
| 429 | `RateLimited` | Notify rate limit; `Retry-After: <seconds>` is set | `RateLimited` |
| 503 | `ServiceUnavailable` | Storage restarting; `Retry-After` is set. Safe to retry reads and conditional writes | `Unreachable` |
| 507 | `PoolFull` / `PhoneStorageLow` | No room on the phone | `QuotaExceeded` |

`ReadOnly` is new: S3 requests keep answering `AccessDenied` for read-only mode as today, the plain routes say
`ReadOnly` so a library can tell the two apart.

### Limits

| What | Limit |
|---|---|
| One file through `PUT /_pocket/files/...` | 2 GiB (`2147483648` bytes). Bigger files: S3 multipart through `connect()` |
| JSON request bodies (move, notify, share, ...) | 8 MiB, as today |
| Key length | 1024 bytes UTF-8 |
| `list` page | `limit` 1–1000, default 1000 |
| `changes` page | `limit` 1–1000, default 500; `wait` 0–60 s |
| Clock skew | 15 minutes |

### ETags

An ETag is the quoted lower-case hex MD5 of the content, `"9a0364b9e99bb480dd25e1f0284c8555"`, the same value
S3 listings show for files uploaded in one piece. Files uploaded through S3 multipart have S3's
`"<md5>-<parts>"` form. **Clients treat ETags as opaque strings** and only compare them for equality, always
with the quotes.

---

## 2. `GET /_pocket/health` (not signed)

Today: `{"ok": true, "version": "0.2.0"}`. v2 adds the feature list:

```json
{"ok": true, "version": "0.3.0",
 "features": ["quota", "conflicts", "mirror-report", "share", "push",
              "files", "move", "changes", "publish", "retention", "notify"]}
```

| Feature | Means the server has |
|---|---|
| `quota` | `GET /_pocket/quota` (exists today) |
| `conflicts` | `GET /_pocket/conflicts` (exists today) |
| `mirror-report` | `POST /_pocket/mirror-report` (exists today) |
| `share` | `POST/GET/DELETE /_pocket/share(s)` (exists today; may still be switched off in settings) |
| `push` | The push server (exists today; `enroll` returns `push.url` and `push.topic`) |
| `files` | Section 4 file routes and `GET /_pocket/app` |
| `move` | `POST /_pocket/move`, and S3 `CopyObject` + `DeleteObject` no longer needed for moves (see 4.6) |
| `changes` | Section 5 change feed |
| `publish` | Section 6 publish folder (may be off per app) |
| `retention` | Section 7 retention rules |
| `notify` | Section 8 `POST /_pocket/notify` (may be off per app) |

A server without `features` (0.2.x) has exactly `quota`, `conflicts`, `mirror-report`, `share`, `push`.
Features are server capabilities; whether one is **switched on for an app** comes from `GET /_pocket/app`.
Apps hide what is missing instead of showing it disabled. Names are lower case, `a-z` and `-`; a client ignores
names it does not know.

---

## 3. `GET /_pocket/app` (signed) — feature `files`

What this app and device may use, so a library can decide before it tries. Health cannot say this because it
has no key.

```json
{
  "app": "egx-analyzer",
  "device_id": "dev-4f1c…",
  "buckets": ["shared", "device"],
  "features": ["files", "move", "changes", "publish", "retention", "notify", "quota", "conflicts", "mirror-report", "share", "push"],
  "publish": {"enabled": true, "url": "http://100.101.102.103:9000/_pocket/pub/egx-analyzer/"},
  "notify":  {"enabled": false, "topic": "pocket-egx-analyzer-notify", "url": "http://100.101.102.103:2586"},
  "push":    {"topic": "pocket-egx-analyzer", "url": "http://100.101.102.103:2586"},
  "retention": [{"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": null}],
  "changes": {"keep_days": 30},
  "read_only": false
}
```

- `features` is the health list (the same server capabilities), repeated here so one call is enough.
- `publish.url` is always present (`null` when the server has no Tailscale address); `enabled` says whether it
  answers. The same holds for `notify`.
- `push` is `null` when the push server is switched off.

---

## 4. Plain file routes (signed) — feature `files`

These reach the same files as S3: a file written here is visible through S3 and the reverse. They go through
the same checks as S3 requests in `gateway.py`: device checks, read-only mode, `check_space` for allocation /
device cap / reserve / pool, undo copies for anything overwritten or deleted, usage, push on change, request
log, and (new) the change feed.

### 4.1 Read — `GET /_pocket/files/<bucket>/<key>`

- `200` with the bytes. Headers: `Content-Length`, `Content-Type` (from the stored type, else
  `application/octet-stream`), `ETag`, `Last-Modified` (HTTP date), `x-pocket-modified` (unix seconds, may have
  a fraction), `Accept-Ranges: bytes`.
- `Range: bytes=a-b` gives `206` with `Content-Range`. One range only. `416 InvalidRange` outside the file.
- `if-none-match: "<etag>"` gives `304` with no body when it matches.
- `if-match: "<etag>"` gives `412 PreconditionFailed` when it does not.
- `404 NoSuchKey`.

### 4.2 Stat — `HEAD /_pocket/files/<bucket>/<key>`

The headers of 4.1, no body. `404` with `x-pocket-error-code: NoSuchKey` when missing.

### 4.3 Write — `PUT /_pocket/files/<bucket>/<key>`

Request headers:

| Header | |
|---|---|
| `Content-Length` | Required (no chunked uploads on this route). Over 2 GiB: `400 EntityTooLarge` |
| `x-pocket-content-sha256` | Required, see Signing |
| `Content-Type` | Optional, stored and returned on read |
| `if-match: "<etag>"` | Optional: write only if the current file has this ETag, else `412` (and `412` if there is no file) |
| `if-none-match: *` | Optional: write only if there is no file yet, else `412` |
| `x-pocket-modified` | Optional unix seconds: the file's own modification time, stored as its time (sync keeps times) |

The write is atomic: readers see the old file or the new one, never a part. On success, `200`:

```json
{"bucket": "shared", "key": "notes/a.txt", "size": 5, "etag": "\"5d41402abc4b2a76b9719d911017c592\"",
 "modified": 1795000000.0, "created": false}
```

`created` is `true` when there was no file before. The old file, if any, goes into the 14-day undo as
`overwritten`. Errors: `400 BadDigest`, `403 ReadOnly`, `403 QuotaExceeded`, `412`, `507`.

### 4.4 List — `GET /_pocket/list?bucket=<b>&prefix=<p>&delimiter=/&cursor=<c>&limit=<n>`

| Param | |
|---|---|
| `bucket` | Required |
| `prefix` | Optional, default `""`. Plain string match on the key, not a folder (`notes/` and `no` both work) |
| `delimiter` | Optional, only `/` is accepted. With it, keys below the next `/` after the prefix are rolled up into `folders` |
| `cursor` | Optional, from the previous page |
| `limit` | 1–1000, default 1000 |

```json
{"bucket": "shared", "prefix": "notes/",
 "files": [{"key": "notes/a.txt", "size": 5, "etag": "\"5d41…\"", "modified": 1795000000.0}],
 "folders": ["notes/2026/"],
 "cursor": "bm90ZXMvYS50eHQ"}
```

- Sorted by key, byte order of the UTF-8. `folders` is present only with `delimiter`, sorted, each ending in `/`.
- `cursor` is `null` on the last page. Cursors are opaque; one stays valid for 1 hour. A cursor for another
  bucket, prefix or delimiter is `400 BadRequest`.
- Files being uploaded and rclone's temporary files are never listed.

### 4.5 Delete — `DELETE /_pocket/files/<bucket>/<key>`

- Optional `if-match: "<etag>"`, else `412`.
- `200 {"bucket": "shared", "key": "notes/a.txt", "deleted": true}`. The file goes into undo as `deleted`.
- Deleting a key that is not there is `404 NoSuchKey` (unlike S3), so a client knows nothing happened.
- Folders are not objects: there is no folder delete. Delete the files under it.

### 4.6 Move — `POST /_pocket/move` — feature `move`

```json
{"from": {"bucket": "shared", "key": "inbox/a.txt"},
 "to":   {"bucket": "shared", "key": "done/a.txt"},
 "if_match": "\"5d41…\"",
 "overwrite": false}
```

- `if_match` optional: the source's ETag must match, else `412`.
- `overwrite` default `false`: an existing target is `409 KeyExists`. With `true`, the old target goes into undo
  as `overwritten`.
- Moves within a bucket and between the app's own buckets are allowed; across buckets the target's room is
  checked (`QuotaExceeded`). The ETag is unchanged. The file's time is kept.
- One file per call. A folder move is the library's job (list, then move each).
- `200 {"from": {...}, "to": {...}, "size": 5, "etag": "\"5d41…\""}`.
- The move writes **one** change record of kind `move` (section 5), not a delete and a put.

**S3 clients too:** with `move` in features, an S3 `CopyObject` followed within 10 s by a `DeleteObject` of the
copy source by the same device is still two operations (S3 semantics do not change), but Pocket Drive switches to
`POST /_pocket/move` in step 2. Session A decides whether a same-storage `CopyObject` becomes a rename on disk.

---

## 5. Change feed — feature `changes`

### `GET /_pocket/changes?since=<cursor>&limit=<n>&wait=<s>` (signed)

```json
{"changes": [
   {"seq": 1842, "bucket": "shared", "key": "notes/a.txt", "kind": "put",
    "size": 5, "etag": "\"5d41…\"", "time": 1795000000.12, "device": "dev-4f1c…", "source": "app"},
   {"seq": 1843, "bucket": "shared", "key": "done/a.txt", "kind": "move",
    "from": {"bucket": "shared", "key": "inbox/a.txt"},
    "size": 5, "etag": "\"5d41…\"", "time": 1795000003.4, "device": "dev-4f1c…", "source": "app"},
   {"seq": 1850, "bucket": "shared", "key": "backups/old.zip", "kind": "delete",
    "size": null, "etag": null, "time": 1795003600.0, "device": null, "source": "retention"}
 ],
 "cursor": "1850",
 "more": false}
```

A change record:

| Field | |
|---|---|
| `seq` | Integer, grows by at least 1 per change, per server (not per app). Gaps are normal |
| `bucket` | `shared`, `device` or `family` — as **this caller** sees it |
| `key` | The key after the change (for `move`, the new key) |
| `kind` | `put` (new or overwritten), `delete`, or `move` |
| `from` | `move` only: `{bucket, key}` before |
| `size`, `etag` | After the change; `null` for `delete` |
| `time` | Unix seconds with fraction, when the server applied it |
| `device` | Device uid that made it; `null` when not a device |
| `source` | `app` (a device, S3 or plain routes), `panel` (control panel files page), `retention` (nightly rules), `undo` (restored from undo), `card` (SD card put-back) |

What a caller sees: changes in `shared` from every device (including its own — compare `device` to skip
them), changes in **its own** `device` bucket only, and `family` changes when the app is in the family.

S3 `DeleteObjects` writes one `delete` per key actually deleted. Multipart uploads write one `put` at
`CompleteMultipartUpload`. Internal moves between internal storage and the SD card are not changes.

### Cursors

- A cursor is an opaque string. (Session A will use the decimal `seq` of the last change returned, but clients
  must not parse or compute it.)
- **No `since`**: the answer has no changes and `cursor` is "now". A new client takes this cursor, does a full
  listing, then follows the feed — nothing between the two is lost because the listing happens after the cursor.
- `since=<cursor>`: changes strictly after it, oldest first, at most `limit`. `more: true` means ask again right
  away with the new `cursor`.
- `wait=<1..60>`: when there is nothing new, hold the request open up to that many seconds and answer as soon as
  a change arrives (long poll). `wait=0` (default) answers at once. A client may instead listen on the push
  topic `pocket-<app>` and call `changes` when a message arrives.
- The returned `cursor` is always usable for the next call, even when `changes` is empty.

### Keeping and expiry

- Changes are kept **30 days**, and at most the newest **100 000 per app**; older ones are pruned by the nightly
  job. `GET /_pocket/app` reports `changes.keep_days`.
- `since` older than the oldest kept change, or a cursor the server never issued: `410 CursorExpired`:

  ```json
  {"error": "CursorExpired", "message": "…", "cursor": "1850"}
  ```

  The client then does a **full resync**: lists the buckets it follows, compares with what it holds, and
  carries on from the `cursor` in the error body (taken before the listing, so nothing is missed).
- A server restored from backup, or started fresh, answers `CursorExpired` to cursors from before; same
  handling.

---

## 6. Publish folder — feature `publish`

Each app may switch on a publish folder: the files under `releases/` in its **shared** bucket become readable
**without a key** by anything on the tailnet, so app updaters (Android apps, electron-updater, scripts) can
fetch releases without credentials.

### Reading — `GET` / `HEAD /_pocket/pub/<app>/<path>` (not signed)

- Maps to `shared/releases/<path>` of app `<app>`. `<path>` is percent-encoded per segment like keys.
- Answers only when the request arrived on the gateway's **Tailscale** address. On the LAN address, through the
  share tunnel, or from `127.0.0.1` other than in tests: `404 NotFound`.
- Off for that app, unknown app, or no such file: `404 NotFound` — the same answer in all three cases, so the
  folder does not reveal which apps exist.
- Headers as in 4.1, `Range` and `if-none-match` supported. `Cache-Control: no-cache` on `latest.json` and
  `latest*.yml`, `Cache-Control: max-age=86400` on everything else.
- `GET /_pocket/pub/<app>/` (the folder itself) is `404`: there is no listing; `latest.json` is the index.
- Read-only: any other method is `405`. Reads are logged as op `PubRead` with `device = null`.

### Writing

There is no special write route: an app writes `releases/...` in its shared bucket with any device key (plain
routes or S3). The library's `publish()` does it in this order, so readers never see a manifest pointing at a
file that is not there yet:

1. `PUT releases/<version>/<file name>` for each file.
2. Read `releases/latest.json` (may be missing), build the new one.
3. `PUT releases/latest.json` with `if-match` the old ETag (or `if-none-match: *`); on `412`, back to step 2.

`<version>` is the caller's version string, restricted to `A-Z a-z 0-9 . _ + -`, 1–64 characters.

### `latest.json`

```json
{
  "app": "egx-analyzer",
  "version": "5.9.1",
  "published": 1795000000,
  "notes": "Faster reports.",
  "files": [
    {"name": "egx-analyzer-5.9.1-arm64.apk", "path": "5.9.1/egx-analyzer-5.9.1-arm64.apk",
     "size": 75497472, "sha256": "3f0a…", "platform": "android-arm64"}
  ]
}
```

| Field | |
|---|---|
| `app` | The app id |
| `version` | The release's version string, as given to `publish` |
| `published` | Unix seconds |
| `notes` | Optional, plain text, may be `""` |
| `files[].name` | File name |
| `files[].path` | Relative to the publish folder: fetch `<publish url><path>` |
| `files[].size`, `files[].sha256` | Clients check both after download and refuse a mismatch |
| `files[].platform` | Optional free string (`android-arm64`, `windows-x64`, `any`); `releases().latest(platform=)` filters on it |

`schema` is not versioned in v2; readers ignore unknown fields. Only the newest release is listed; older
releases stay under their version folders until retention removes them (a good rule:
`{prefix: "releases/", keep_last: 6}`, which keeps `latest.json` because rules never delete `latest.json` or
`latest*.yml` at the folder top).

**electron-updater:** its generic provider wants `latest.yml` next to the installers. `publish()` in the Node
library uploads electron-builder's `latest.yml` (and `.blockmap` files) unchanged to `releases/` top level as
well as writing `latest.json`; `releasesUrl()` returns the publish URL, which is what `setFeedURL({provider:
"generic", url})` needs. electron-builder's file names must then be unique per version, which they are by
default.

---

## 7. Retention rules — feature `retention`

Per app, a list of rules the server applies **nightly**, as part of the nightly job in `jobs.py` (before the
copy to the SD card). Rules are set in the control panel only (section 9); apps read them in `GET /_pocket/app`.

```json
[{"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": null},
 {"bucket": "device", "prefix": "logs/",    "keep_last": null, "keep_days": 14}]
```

| Field | |
|---|---|
| `bucket` | `shared`, `device` (applied to every device's private space separately) or `family` (family app only) |
| `prefix` | Non-empty string; the rule covers keys that start with it |
| `keep_last` | Integer 1–1000 or `null` |
| `keep_days` | Integer 1–3650 or `null` |

- At least one of `keep_last` / `keep_days` is set. At most 20 rules per app. Two rules with the same bucket and
  prefix are refused.
- Within a rule's files, a file is **kept** if it is among the `keep_last` newest (by modification time) **or**
  younger than `keep_days`. Everything else under the prefix is deleted. With only one of the two set, only that
  one counts.
- Under `releases/`, `latest.json` and `latest*.yml` directly in `releases/` are never deleted.
- If one key falls under several rules, it is deleted only if every rule covering it would delete it.
- Deletions go into the 14-day undo as `deleted` (so a bad rule can be reverted), adjust usage, send one push
  message per app, write change records with `source: "retention"` and `device: null`, and are logged:
  one line per rule with the count and bytes freed, and a panel event when anything was removed.
- A rule that would delete more than 500 files or more than half of the files under its prefix in one night
  deletes nothing that night and raises a panel alert instead.

The library's `backup(path, keep)` prunes on the client side right after uploading (so it works on servers
without `retention`); a retention rule is the server-side safety net for the same prefix.

---

## 8. App notifications — feature `notify`

### `POST /_pocket/notify` (signed)

```json
{"message": "6 new calls", "title": "Evening calls are ready", "tags": ["chart_with_upwards_trend"],
 "priority": 3, "click": "egx://calls"}
```

| Field | |
|---|---|
| `message` | Required, 1–4096 characters |
| `title` | Optional, up to 200 characters |
| `tags` | Optional list of up to 10 strings, each `a-z 0-9 _ -`, up to 40 characters (ntfy tags; emoji short names work in the ntfy app) |
| `priority` | Optional 1–5 (ntfy: 1 min, 3 default, 5 urgent), default 3 |
| `click` | Optional URL opened when the notification is tapped, up to 2000 characters |

- Published on the push server to the app's **own notify topic**, `pocket-<app>-notify` — not on the change topic
  `pocket-<app>`, which stays machine-readable `{"app", "changes", "ts"}` messages. People subscribe to the
  notify topic with the ntfy app; libraries never parse it.
- The device's name is added as the ntfy message's own `x-pocket-device` field, not to the text.
- Switched **off** by default per app: `403 FeatureOff` until the control panel switches it on. Push switched
  off on the server: `403 FeatureOff` too.
- Rate limit per app: **10 per minute and 200 per day** (rolling windows). Over it: `429 RateLimited`,
  `Retry-After: <seconds>`. Libraries do not retry notify automatically.
- `200 {"ok": true, "id": "<ntfy message id>", "topic": "pocket-egx-analyzer-notify"}`.

---

## 9. Panel API additions (signed in to the panel, as all `/api/` routes)

Shape only; session A implements, sessions E and G show them when present.

### `GET /api/status` / `GET /api/overview`

Both gain `"features": [...]`, the same list as health. The panel shows the rules below only when the matching
feature is present.

### `GET /api/apps/<app>/rules`

```json
{
  "retention": [{"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": null,
                 "last_run": 1795003600, "last_removed": 2, "last_freed": 96468992}],
  "publish": {"enabled": true, "url": "http://100.101.102.103:9000/_pocket/pub/egx-analyzer/",
              "latest": {"version": "5.9.1", "published": 1795000000}},
  "notify": {"enabled": false, "topic": "pocket-egx-analyzer-notify", "sent_today": 0, "limit_day": 200},
  "changes": {"keep_days": 30, "count": 1842}
}
```

`publish.latest` is `null` when there is no `latest.json`. `last_*` fields are `null` before the first run.

### `PATCH /api/apps/<app>/rules`

Any subset of:

```json
{"retention": [{"bucket": "shared", "prefix": "backups/", "keep_last": 7, "keep_days": null}],
 "publish": {"enabled": true},
 "notify": {"enabled": true}}
```

- `retention` replaces the whole list (send `[]` to remove all). Validation as section 7; errors are the panel's
  usual `400 {"error": "BadRequest", "message": ...}` naming the rule (`"Rule 2: …"`).
- Answers with the full `GET` shape.
- Changing publish or notify is logged and raised as a panel event.

---

## 10. The library surface

The same object, the same names (each language's own case), the same arguments and the same errors in all
three libraries. Python is `clients/python` (`pocket_cloud`), Kotlin-lite is `clients/kotlin-lite`
(`cloud.pocket.lite`), Node is `clients/node` (`pocket-cloud`). `connect()` in Python and Node, and
`clients/kotlin`, are unchanged.

### Getting the app object

| | Python | Kotlin-lite | Node |
|---|---|---|---|
| Open | `pocket.app("egx")` | `Pocket.connect(context, app = "egx")` (Android) / `Pocket.connect(app = "egx", store = …)` (JVM) | `await pocket.app("egx")` |
| Options | `endpoint, enroll_secret, device_id, device_name, home, timeout=30` | `endpoint, enrollSecret, deviceId, deviceName, store: CredentialStore, timeoutMs` | `{endpoint, enrollSecret, deviceId, deviceName, home, timeoutMs}` |

Opening reads the machine setup like `connect()` (config file or `POCKET_ENDPOINT` / `POCKET_ENROLL_SECRET`),
enrolls when there is no saved key, and calls `/_pocket/health` once to learn `features`. It does not fail on an
old server; a method whose feature is missing raises `FeatureOff` (code `FeatureMissing`) when called.

All three re-enroll **once** and retry the request when they get `403` with `x-pocket-error:
device-address-changed` or `InvalidAccessKeyId`, like `connect()` does today.

Kotlin functions are `suspend`; Node methods return promises; Python is synchronous.

### Methods

`key` is a string key. `bucket` defaults to `"shared"` everywhere.

| Python | Kotlin-lite | Node | Does |
|---|---|---|---|
| `get(key, bucket=)` → `bytes` | `get(key, bucket)` → `ByteArray` | `get(key, {bucket})` → `Buffer` | 4.1. Missing: `NotFound` |
| `get_file(key, dest, bucket=)` | `getFile(key, dest: File, bucket)` | `getFile(key, dest, {bucket})` | Streams to a file (temp file + rename) |
| `put(key, data, bucket=, if_match=, content_type=)` → `Entry` | `put(key, data: ByteArray, …)` | `put(key, data, {…})` | 4.3, `data` bytes/str |
| `put_file(key, src, bucket=, if_match=)` → `Entry` | `putFile(key, src: File, …)` | `putFile(key, src, {…})` | Streams from a file, keeps its time |
| `stat(key, bucket=)` → `Entry` or `None` | `stat(…)` → `Entry?` | `stat(…)` → `Entry \| null` | 4.2 |
| `list(prefix="", bucket=, folders=False)` → iterator of `Entry` | `list(prefix, bucket)` → `Flow<Entry>` | `list(prefix, {bucket})` → async iterator | 4.4, all pages |
| `delete(key, bucket=, if_match=)` | `delete(…)` | `delete(…)` | 4.5 |
| `move(src, dst, bucket=, to_bucket=, overwrite=False, if_match=)` → `Entry` | `move(…)` | `move(…)` | 4.6 |
| `get_json(key, bucket=, default=…)` | `getJson<T>(key, bucket)` | `getJson(key, {bucket, default})` | `get` + parse; missing with a default gives the default |
| `put_json(key, value, bucket=, if_match=)` | `putJson(key, value, …)` | `putJson(key, value, {…})` | Serialise (UTF-8, `application/json`) + `put` |
| `backup(path, keep=7, prefix="backups/", bucket=)` → `Entry` | `backup(file, keep, prefix, bucket, copy: SafeCopy?)` | `backup(path, {keep, prefix, bucket})` | Upload to `<prefix><name>/<UTC yyyymmdd-HHMMSS>-<name>`, then delete all but the `keep` newest there. Python copies SQLite files with the `sqlite3` backup API first; Kotlin takes a caller-supplied copy hook |
| `restore_latest(prefix, dest, bucket=)` → `Entry` or `None` | `restoreLatest(prefix, dest)` | `restoreLatest(prefix, dest)` | Newest file under prefix → `dest` |
| `sync(folder, bucket=, prefix="", allow_mass_delete=False)` → `SyncResult` | `sync(folder, bucket, prefix, allowMassDelete)` | `sync(folder, {bucket, prefix, allowMassDelete})` | Two-way sync, rules below |
| `changes(since=None, wait=0)` → iterator of `Change` | `changes(since)` → `Flow<Change>` (follows forever) | `changes({since})` → async iterator | Section 5 |
| `on_change(callback)` → handle with `.stop()` | (use `changes()`) | `onChange(cb)` → `{stop()}` | Follow the feed in the background (push topic when present, else long poll) |
| `publish(file, version, notes="", platform=None)` → `Release` | `publish(file, version, notes, platform)` | `publish(file, version, {notes, platform, extra})` | Section 6 write order. Node `extra`: files such as `latest.yml` to put at the folder top |
| `releases().latest(platform=None)` → `Release` or `None` | `releases().latest(platform)` | `releases().latest({platform})` | Read `latest.json` (key-less publish URL when on, else the signed route) |
| `Release.download(dest)` | `latest.download(dest)` | `release.download(dest)` | Fetch and check `size` + `sha256` |
| `releases_url()` → `str` | `releasesUrl()` | `releasesUrl()` | The publish URL |
| `notify(message, title=None, tags=None, priority=3, click=None)` | `notify(message, title, tags, priority, click)` | `notify(message, {title, tags, priority, click})` | Section 8 |
| `share(key, expires=86400, bucket=, password=None, max_downloads=None)` → `Share` | `share(…)` | `share(key, {expires, …})` | Existing `POST /_pocket/share`; `expires` in seconds |
| `quota()` → `dict` | `quota()` → `Quota` | `quota()` | Existing `GET /_pocket/quota`, fields unchanged |

Data types (Python dataclass / Kotlin data class / Node plain object):

- `Entry`: `bucket, key, size, etag, modified` (unix seconds as float / `Double` / `number`)
- `Change`: the record of section 5 (`seq, bucket, key, kind, from_ (Python) / from, size, etag, time, device, source`)
- `Release`: `app, version, published, notes, files` (each `name, path, size, sha256, platform, url`)
- `Share`: the existing share JSON
- `SyncResult`: `uploaded, downloaded, deleted_local, deleted_remote, conflicts` (lists of keys)

### `sync` rules (all three)

- Two-way between a local folder and `prefix` in a bucket. State is kept in `<folder>/.pocket-sync.json` (key →
  local size + mtime, remote ETag, last cursor); that file and anything starting with `.pocket-` is never
  uploaded.
- First run (no state file): **never deletes** on either side; files only on one side are copied over; files on
  both sides that differ become conflicts.
- Later runs use the change feed from the saved cursor (full listing on `CursorExpired` or without `changes`).
- Conflicts (changed on both sides since the last run): the remote file is kept under its key, the local version
  is uploaded as `<name> (conflict <device name> <yyyy-mm-dd HHMM>).<ext>`; both are listed in `conflicts`.
- **Mass-delete guard:** if one run would delete more than 50 files (local plus remote), it deletes nothing,
  does everything else, and raises `PocketError` with code `MassDelete` and the count, unless
  `allow_mass_delete` is true.
- Remote writes use `if-match` / `if-none-match: *` so a file changed meanwhile becomes a conflict, not a loss.

### Errors (all three)

```
PocketError                 .code (server "error" or a library code), .status (HTTP status or 0), message
 ├─ NotFound                NoSuchKey, NoSuchBucket, NotFound
 ├─ PreconditionFailed      PreconditionFailed, KeyExists
 ├─ QuotaExceeded           QuotaExceeded, PoolFull, PhoneStorageLow
 ├─ ReadOnly                ReadOnly
 ├─ AccessDenied            AccessDenied, InvalidAccessKeyId, SignatureDoesNotMatch, MissingSignature, RequestTimeTooSkewed, and enrollment refusals
 ├─ CursorExpired           CursorExpired  (.cursor: the resync cursor)
 ├─ RateLimited             RateLimited    (.retry_after / retryAfter seconds)
 ├─ FeatureOff              FeatureOff, and the library code FeatureMissing (server lacks the feature)
 └─ Unreachable             connection failed, timeout, ServiceUnavailable (after retries)
```

Library-only codes: `FeatureMissing`, `MassDelete`, `ChecksumMismatch` (release download), `NotConfigured`
(no machine setup), `Unreachable`. Python names are exactly these; Kotlin uses `PocketException` as the base
and `…Exception` suffixes (`NotFoundException`); Node exports the classes with the names above and sets
`err.name` to match.

Retries: reads, `stat`, `list`, `changes` and conditional writes are retried up to 3 times on `Unreachable` /
`503` with backoff (0.5 s, 1 s, 2 s, honouring `Retry-After`). Unconditional writes, `move`, `delete` and
`notify` are not retried automatically.

---

## 11. The fake server for library tests

Sessions B, C and D each build an in-memory fake in their own test folder, from this section, so they never
wait for session A. It is a real HTTP server on `127.0.0.1` with a random port, started and stopped by the
tests.

### It implements

- `GET /_pocket/health` with all features, and `POST /_pocket/enroll` accepting secret `fake-enroll-secret`,
  returning the real enroll shape (`access_key` `AKFAKE` + 10 digits, a random `secret_key`, `endpoint` its own
  URL, `endpoints.tailscale` the same, `push: null`).
- Every signed route in sections 3–8 plus `/_pocket/quota` and `/_pocket/share`, with the **real signature
  check** (port of `verify_pocket`) including `x-pocket-content-sha256` and `BadDigest`.
- Storage: a map `(app, bucket, device-or-"", key) → {bytes, content_type, etag (real MD5), modified}`.
  `device` buckets are separate per device; `family` absent.
- The change feed with integer cursors, `wait` long-poll, and the same visibility rules.
- `/_pocket/pub/<app>/...` with no address check.
- Notify recording messages instead of sending them, with the real rate limit.
- `quota()`: allocation 1 GiB per app by default, real usage from the map; over it gives `QuotaExceeded`.
- Error shapes, codes and statuses exactly as section 1. Not needed: S3, Range on `pub`, retention running.

### Test controls (not signed, fake only, under `/_fake/`)

| Route | Does |
|---|---|
| `POST /_fake/reset` | Empty everything |
| `POST /_fake/clock {"now": 1795000000}` | Set the server's clock (skew checks, times, `keep_days`); `{"now": null}` back to real time |
| `POST /_fake/features {"features": ["files", …]}` | What health and `/_pocket/app` report; routes not listed answer `404 NotFound` |
| `POST /_fake/app {"publish": true, "notify": true, "read_only": false, "allocation": 1073741824, "retention": [...]}` | Per-app switches (app from `?app=`) |
| `POST /_fake/fail {"route": "PUT /_pocket/files", "status": 503, "code": "ServiceUnavailable", "times": 1}` | Next N matching requests fail like that |
| `POST /_fake/moved {"times": 1}` | Next N signed requests get `403 AccessDenied` + `x-pocket-error: device-address-changed` |
| `POST /_fake/expire-changes {"before": "1850"}` | Drop changes up to a cursor, so older cursors give `CursorExpired` |
| `GET /_fake/notifications` | `{"sent": [{app, device, message, title, tags, priority, click, time}]}` |
| `GET /_fake/requests` | Log of `{method, path, query, status, code}` for asserting retries and re-enrollment |

A library test suite runs against the fake by default. In step 2 the same suites also run against the real
server (`server/tests/helpers.PocketServer`), so tests must not depend on `/_fake/` routes except in tests
explicitly marked fake-only.

---

## 12. Test vectors

Key `AKTEST0000000001`, secret `secret-for-tests-only`, `x-pocket-date` `1795000000`. Computed with the
server's own `sigv4.sign_pocket`; every library's signer must give exactly these.

| Method | Path | Query | Body | `x-pocket-sign` |
|---|---|---|---|---|
| `GET` | `/_pocket/quota` | `` | (empty) | `d872ac21f923208dc0ca0344d8a3fe488e4b717b656d7328149e89799ac06b71` |
| `PUT` | `/_pocket/files/shared/notes/hello%20world.txt` | `` | `hello` | `015bd2b75bedd7fbc3a9ce49abd670c4ff7a1ef6d8f96fd795cfbe5d8995c04f` |
| `GET` | `/_pocket/list` | `bucket=shared&prefix=notes%2F&limit=2` | (empty) | `0884bcb1271e92c9f16e501b09205689aacc6e65c212461a42ffeb102f6f14d6` |

The `PUT` vector's `x-pocket-content-sha256` is `2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824`
(SHA-256 of `hello`); its ETag after writing is `"5d41402abc4b2a76b9719d911017c592"`.
