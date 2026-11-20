# Pocket Cloud — Kotlin light library (`cloud.pocket.lite`)

A small Kotlin/JVM library for Pocket Cloud's plain `/_pocket/` routes (`docs/app-api.md`). It uses OkHttp,
kotlinx.serialization and coroutines, has **no AWS SDK**, and works on Android as it is. Every method is a
`suspend` function. `clients/kotlin` (the S3 client that Pocket Drive uses) is a separate library and is not
affected.

## Android

```kotlin
val pocket = Pocket.connect(
    app = "egx",
    endpoint = BuildConfig.POCKET_ENDPOINT,             // from the control panel's machine setup
    enrollSecret = BuildConfig.POCKET_ENROLL_SECRET,
    deviceName = Build.MODEL,
    store = KeystoreCredentialStore(context),           // your CredentialStore on EncryptedFile / Tink
)
pocket.putJson("prices/today.json", prices)
pocket.backup(context.getDatabasePath("egx.db"), keep = 7) { src, dest -> db.execSQL("VACUUM INTO '${dest.path}'") }
pocket.releases().latest("android-arm64")?.takeIf { it.version != BuildConfig.VERSION_NAME }?.download(context.cacheDir)
```

The library is plain JVM, so it takes no `Context`. Pass the endpoint, the enrollment secret, a device name and a
`CredentialStore` yourself. The store keeps the device id and the app's key. Implement it on Keystore-encrypted
storage:

```kotlin
interface CredentialStore {
    fun loadKey(app: String): DeviceKey?
    fun saveKey(app: String, key: DeviceKey)
    fun clearKey(app: String)
    fun loadDeviceId(): String?
    fun saveDeviceId(id: String)
}
```

`MemoryCredentialStore` is there for tests. `FileCredentialStore(home)` is the default on a computer. It writes
the same `~/.pocket/apps/<app>.json` and `~/.pocket/device_id` files as the Python and Node libraries, so all of
them share one key per app on a machine.

## On a computer

```kotlin
val pocket = Pocket.connect(app = "egx")       // reads ~/.pocket/config or POCKET_ENDPOINT / POCKET_ENROLL_SECRET
pocket.put("notes/a.txt", "hello".toByteArray())
pocket.list("notes/").collect { println("${it.key} ${it.size}") }
```

## What it does

| Method | |
|---|---|
| `get`, `getFile`, `put`, `putFile`, `stat`, `list` (a `Flow`, all pages), `delete`, `move` | Plain file routes. `getFile` and `putFile` stream from and to disk and keep file times |
| `getJson<T>`, `putJson` | kotlinx.serialization; `getJson(key, default = x)` gives `x` when the file is missing |
| `backup(file, keep, prefix, bucket, copy)` | Upload to `backups/<name>/<UTC yyyymmdd-HHMMSS>-<name>` and keep the `keep` newest. `copy` is a `SafeCopy` you supply, which makes a consistent copy of a database in use |
| `restoreLatest(prefix, dest)` | Newest file under a prefix |
| `sync(folder, bucket, prefix, allowMassDelete)` | Two-way sync, rules below |
| `changes(since)` | `Flow<Change>` that follows the change feed forever (long poll). Cancel the coroutine to stop |
| `publish(file, version, notes, platform)`, `releases().latest(platform)`, `Release.download(dest)`, `releasesUrl()` | Publish folder. Downloads are checked against `size` and `sha256` |
| `notify(message, title, tags, priority, click)` | Sends to the app's notify topic. It is never retried automatically |
| `share`, `quota`, `appInfo`, `features` | The existing share and quota routes, `GET /_pocket/app`, and the server's feature list |

The first `connect` on a device enrolls it through `/_pocket/enroll`, the same request as `clients/kotlin`, and
saves the key.

Requests are signed as `docs/app-api.md` section 1 says (checked against the section 12 vectors). Uploads also
carry `x-pocket-content-sha256`, so a file is read twice: once to hash it and once to send it.

When the server answers `403` with `device-address-changed`, or `InvalidAccessKeyId`, the library enrolls again
once and retries.

Reads, `stat`, `list`, `changes` and conditional writes are retried up to 3 times when there is no connection or
the answer is `503`. The waits are 0.5 s, 1 s and 2 s, or longer when `Retry-After` says so. Unconditional
writes, `move`, `delete` and `notify` are never retried.

A method whose feature the server lacks throws `FeatureOffException` with code `FeatureMissing`.

### Sync rules

- State is kept in `<folder>/.pocket-sync.json`. Nothing whose name starts with `.pocket-` is uploaded.
- The first run never deletes anything. Files found on only one side are copied to the other, and files on both
  sides that differ become conflicts.
- Later runs read the change feed from the saved cursor. If the cursor has expired, or the server has no
  `changes` feature, they fall back to a full listing.
- A file changed on both sides: the remote version stays under its key. The local version is renamed, and
  uploaded, as `<name> (conflict <device name> <yyyy-mm-dd HHMM>).<ext>`. Both keys are listed in `conflicts`.
- If one run would delete more than 50 files, it deletes nothing, does everything else, and throws
  `PocketException` with code `MassDelete`. Pass `allowMassDelete = true` to let it through.
- Remote writes use `if-match` / `if-none-match: *`, so a file changed meanwhile becomes a conflict, not a loss.

### Errors

`PocketException` (`code`, `status`) is the base class. Its subclasses are `NotFoundException`,
`PreconditionFailedException`, `QuotaExceededException`, `ReadOnlyException`, `AccessDeniedException`,
`CursorExpiredException` (`cursor`), `RateLimitedException` (`retryAfter`), `FeatureOffException` and
`UnreachableException`.

The library's own codes are `FeatureMissing`, `MassDelete`, `ChecksumMismatch`, `NotConfigured` and
`Unreachable`.

## Build and test

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew test
```

The tests start an in-memory fake Pocket Cloud server (`src/test/.../FakePocketServer.kt`, built from
`docs/app-api.md` section 11) on a random port.

- `SignerTest`, `PocketAppTest` and `SyncTest` use no `/_fake/` controls.
- `FakeOnlyTest` drives the fake's controls: failures, clocks, switches, the rate limit and cursor expiry.
