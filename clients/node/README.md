# pocket-cloud (Node)

Connect a Node or Electron app to Pocket Cloud. Node 18 or newer.

There are two ways in:

- `pocket.connect(app)` gives a ready AWS SDK v3 `S3Client` for the app's buckets. Use it when you want S3, for
  example multipart uploads of files over 2 GiB.
- `pocket.app(app)` gives the Pocket Cloud surface that the Python and Kotlin-lite libraries share: files, JSON,
  backups, two-way sync, the change feed, releases, notifications, share links and quota. It uses the plain
  `/_pocket/` routes with Node's built-in `fetch`, not the AWS SDK. The contract it follows is in
  [`docs/app-api.md`](../../docs/app-api.md).

## Machine setup (once per machine)

Save the machine setup file from the control panel's Settings page as `~/.pocket/config`:

```ini
[pocket]
endpoint = http://pocket:9000
enroll_secret = ...
```

For CI, set `POCKET_ENDPOINT` and `POCKET_ENROLL_SECRET` instead. `POCKET_DEVICE_ID` and `POCKET_DEVICE_NAME`
can fix the device's identity (`POCKET_DEVICE_ID=github-actions`), and `POCKET_HOME` moves `~/.pocket`. The
Python and Kotlin libraries read the same files, so all apps on one machine count as one device.

The first time an app opens on a machine, it enrolls and saves its key in `~/.pocket/apps/<app>.json`. If the
device's address changes or its key is replaced, the library enrolls again once and retries the request.

## `connect()`: S3

```js
const pocket = require("pocket-cloud");
const { PutObjectCommand } = require("@aws-sdk/client-s3");

const s3 = await pocket.connect("egx");
await s3.send(new PutObjectCommand({ Bucket: "shared", Key: "prices/today.json", Body: data }));
```

## `app()`: the shared surface

```js
const pocket = require("pocket-cloud");
const app = await pocket.app("egx");   // options: {endpoint, enrollSecret, deviceId, deviceName, home, timeoutMs}

await app.put("notes/today.txt", "hello");                  // -> Entry {bucket, key, size, etag, modified}
const text = (await app.get("notes/today.txt")).toString();
await app.putJson("settings.json", { theme: "dark" });
const settings = await app.getJson("settings.json", { default: {} });
for await (const e of app.list("notes/")) console.log(e.key, e.size);
await app.move("inbox/a.txt", "done/a.txt");
await app.delete("done/a.txt");
```

Every method takes `{bucket}`, which defaults to `"shared"`. The other buckets are `"device"` (this device only)
and, for apps in the family, `"family"`.

| Method | Does |
|---|---|
| `get(key)` → `Buffer` | Read a file. Missing: `NotFound` |
| `getFile(key, dest)` → `Entry` | Stream to a file (temp file, then rename), keeping the server's time |
| `put(key, data, {ifMatch, ifNoneMatch: "*", contentType})` → `Entry` | Write bytes or a string |
| `putFile(key, src, {...})` → `Entry` | Stream from a file, keeping its time. Up to 2 GiB |
| `stat(key)` → `Entry \| null` | |
| `list(prefix, {folders})` → async iterator of `Entry` | All pages, in key order. `folders: true` rolls up keys below the next `/` |
| `delete(key, {ifMatch})` | Deleting a missing key is `NotFound` |
| `move(src, dst, {toBucket, overwrite, ifMatch})` → `Entry` | An existing target is `PreconditionFailed` (code `KeyExists`) unless `overwrite` |
| `getJson(key, {default})`, `putJson(key, value)` | |
| `backup(path, {keep: 7, prefix: "backups/"})` → `Entry` | Uploads to `backups/<name>/<UTC yyyymmdd-HHMMSS>-<name>`, then keeps the `keep` newest |
| `restoreLatest(prefix, dest)` → `Entry \| null` | Newest file under `prefix` → `dest` |
| `sync(folder, {prefix, allowMassDelete})` → `SyncResult` | Two-way sync, see below |
| `changes({since, wait})` → async iterator of `Change` | The change feed; read `.cursor` afterwards |
| `onChange(cb, {since, onError, onResync})` → `{stop(), ready}` | Follow the feed in the background |
| `publish(file, version, {notes, platform, extra})` → `Release` | See Releases |
| `releases().latest({platform})` → `Release \| null` | Read `latest.json` |
| `release.download(dest, {name})` → path | Fetch, then check size and sha256 |
| `releasesUrl()` → `string \| null` | The key-less publish folder URL |
| `notify(message, {title, tags, priority, click})` | Push notification to the app's notify topic |
| `share(key, {expires: 86400, password, maxDownloads})` | Temporary link |
| `quota()` | Allocation, usage, free room |
| `info()` | What is switched on for this app (`GET /_pocket/app`) |

Opening an app calls `/_pocket/health` once to learn what the server can do. Older servers still open. A method
whose feature the server lacks throws `FeatureOff` with code `FeatureMissing`.

### The change feed

```js
const feed = app.changes();          // no since: no changes, and feed.cursor is "now"
for await (const _ of feed);
let cursor = feed.cursor;            // save it, do a full listing, then follow from it

const handle = app.onChange((c) => console.log(c.kind, c.bucket, c.key), {
  onResync: async (cursor) => { /* the cursor expired: list again */ },
});
// ...
handle.stop();
```

`onChange` listens on the app's push topic when the server has push switched on, and reads the feed each time a
message arrives. Without push, it long-polls. Your own device's changes come through too; compare `c.device`
to skip them.

### Sync

`sync(folder, {prefix})` keeps a folder and a prefix in the same state, both ways. It keeps its state in
`<folder>/.pocket-sync.json` and never uploads anything whose name starts with `.pocket-`.

- **First run:** nothing is deleted on either side. Files on only one side are copied across. Files that differ
  become conflicts.
- **Conflicts:** the server's file keeps its key. The local version is uploaded as
  `<name> (conflict <device name> <yyyy-mm-dd HHMM>).<ext>`.
- **Mass-delete guard:** a run that would delete more than 50 files deletes nothing and throws `PocketError` with
  code `MassDelete`. Everything else in that run is still done. Pass `allowMassDelete: true` if the deletes are
  meant.

### Releases and electron-updater

The control panel switches an app's publish folder on. The files under `releases/` in its shared bucket can then
be read **without a key** by anything on the tailnet.

```js
// in the build script, after electron-builder
const dist = "dist";
await app.publish(`${dist}/My App Setup 1.4.0.exe`, "1.4.0", {
  notes: "Faster reports.",
  platform: "windows-x64",
  extra: [`${dist}/latest.yml`, `${dist}/My App Setup 1.4.0.exe.blockmap`],
});
```

`publish()` uploads the installer to `releases/<version>/`, then rewrites `releases/latest.json` (with `if-match`,
so two publishers can't overwrite each other). For `extra` files:

- `latest*.yml` goes to the top of `releases/`, with its `url:` / `path:` entries pointed at `<version>/<file>`.
  This is the only change made to it.
- `.blockmap` files go next to the installer in `releases/<version>/`. electron-updater finds the previous
  version's blockmap by swapping the version string in the URL, so differential updates still work.
- Anything else goes to the top of `releases/` unchanged.

Publishing a second platform's build of the same version adds it to that release in `latest.json`.

In the Electron app:

```js
const { autoUpdater } = require("electron-updater");
const url = await app.releasesUrl();    // http://<phone>:9000/_pocket/pub/<app>/
if (url) {
  autoUpdater.setFeedURL({ provider: "generic", url });
  autoUpdater.checkForUpdatesAndNotify();
}
```

Other updaters read `latest.json` through `app.releases().latest({platform})`. That uses the key-less folder
when it is on and the signed route when it is off. `release.download(dest)` refuses a file whose size or sha256
does not match. A retention rule `{prefix: "releases/", keep_last: 6}` clears out old versions; rules never
delete `latest.json` or `latest*.yml` at the folder top.

## Errors

Every error is a `PocketError` with `.code` (the server's code or a library code) and `.status` (HTTP status, or
0 when there was no answer). The subclasses are exported by name and set `err.name` to match.

| Class | Codes |
|---|---|
| `NotFound` | `NoSuchKey`, `NoSuchBucket`, `NotFound` |
| `PreconditionFailed` | `PreconditionFailed`, `KeyExists` |
| `QuotaExceeded` | `QuotaExceeded`, `PoolFull`, `PhoneStorageLow` |
| `ReadOnly` | `ReadOnly` |
| `AccessDenied` | `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `MissingSignature`, `RequestTimeTooSkewed`, refused enrollment |
| `CursorExpired` | `CursorExpired`; `.cursor` is where to carry on after a full resync |
| `RateLimited` | `RateLimited`; `.retryAfter` in seconds |
| `FeatureOff` | `FeatureOff`, `FeatureMissing` |
| `Unreachable` | Connection failed, timeout, `ServiceUnavailable` after retries |

Library-only codes are `FeatureMissing`, `MassDelete`, `ChecksumMismatch`, `NotConfigured` and `Unreachable`.

Some calls retry up to 3 times on `Unreachable` or `503` (after 0.5 s, 1 s and 2 s, or as `Retry-After` says):
reads, `stat`, `list`, `changes` and conditional writes. Unconditional writes, `move`, `delete` and `notify` are
never retried automatically.

## Tests

```bash
npm test
```

- `test/sign.test.js` checks the signing vectors of `docs/app-api.md` section 12.
- `test/app.test.js` runs `app()` against an in-memory fake server (`test/fake-server.js`, section 11 of the
  contract). Tests that use the fake's `/_fake/` controls are marked fake-only.
- `test/client.test.js` runs `connect()` against the real server. It needs Python and rclone (`tools/rclone/`,
  or set `POCKET_RCLONE` to the rclone binary).
