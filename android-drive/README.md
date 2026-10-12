# Pocket Drive (Android)

A Drive-style app for your Pocket Cloud: browse, upload, download, share, keep files offline, encrypt folders, and sync folders
and camera photos. It talks to the server's S3 gateway as an ordinary enrolled app called `drive` (the `shared` bucket is
"My Drive", the `device` bucket is "This device"), through the Kotlin client in `../clients/kotlin`.

## Build and test

```
cd android-drive
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 59 unit tests
```

JDK: Android Studio's (`C:\Program Files\Android\Android Studio\jbr`). Minimum Android 10, target 36.
`POCKET_INTEROP_DIR=<folder>` makes `CryptTest` write vault files that plain rclone can then read (`rclone cat`).

To try it without the phone: `python server/tests/demo_server.py` style servers work; the app's address is `10.0.2.2:<port>`
from the emulator, with the server's enrollment secret.

## Releasing

The app updates itself from this repository's GitHub releases (Settings › About). A release is one
tag push:

1. Bump `appVersionName` in `app/build.gradle.kts`.
2. Add a `## [<version>]` entry to `CHANGELOG.md`; the workflow refuses a version without one.
3. Push the tag `drive-v<version>`.

The workflow (`.github/workflows/android.yml`) runs the tests, builds a release signed with the key
in the `POCKET_KEYSTORE_*` secrets, and publishes `pocket-drive-<version>.apk` on the release.
Every release must be signed with the same key, or phones cannot install it over the previous one.
A debug build is signed with a different key, so the first release has to be installed by hand once
over a phone's debug build; after that the app offers each new release itself.

To sign a release build locally, put `POCKET_KEYSTORE_FILE`, `POCKET_KEYSTORE_PASSWORD`,
`POCKET_KEY_ALIAS` and `POCKET_KEY_PASSWORD` in `local.properties` (gitignored).

## What is where

| Folder | What |
| --- | --- |
| `data/Remote.kt`, `Transfers.kt`, `Background.kt` | S3 calls, the resumable transfer queue, WorkManager / user-initiated-job runners |
| `data/ServerApi.kt` | Pocket Cloud's own signed `/_pocket/` calls: quota, share links, versions, trash |
| `data/sync/` | planner (pure, tested), scanners, sync engine, schedulers, local trash |
| `data/vault/` | rclone-compatible encryption (names: AES-EME + base32; contents: XSalsa20-Poly1305 blocks) |
| `data/Offline.kt`, `SearchIndex.kt` | offline copies, whole-drive search list |
| `shizuku/` | read-only backup of Android/data folders through Shizuku |
| `ui/screens/` | Files, Sync, Transfers, Settings, viewers, QR scanner |

## Behaviour worth knowing

- rclone's S3 server can't make empty folders, so a new folder holds a hidden `.pocket-keep` file. It is hidden in listings and ignored by sync.
- Syncing an ordinary folder needs "All files access" (Android 11+). On Android 10 (the Note 9) only photo and video backup works.
- Sync never deletes more than 50 files, or a fifth of a pair, without asking. Files removed on the server go to a 30-day trash on the phone.
- Vault passwords are kept encrypted with an Android Keystore key. There is no recovery: lose the password, lose the files.
- Plain rclone opens a vault with a `crypt` remote on the vault's folder and the same password (default salt).

## What the app expects from the server

The app asks `GET /_pocket/health` for `features` (a list of names) and an optional `lan_endpoint`, and shows or hides things by name.
Features that are missing are not an error: the app falls back to plain S3.

| Feature name | Used for | Status on the server (last checked) |
| --- | --- | --- |
| `quota` | storage bar in Settings | route exists: `GET /_pocket/quota` |
| `share` | "Create link", links list | routes exist: `POST /_pocket/share`, `GET /_pocket/shares`, `DELETE /_pocket/share?token=` |
| `versions` | "Version history", Trash | **not there yet.** Expected: `GET /_pocket/versions?bucket=&key=` and `GET /_pocket/trash?bucket=&prefix=` returning `{"versions":[...]}` (or `items`) of `{key, version, size, time, reason: "deleted"\|"overwritten", by, days_left}`, and `POST /_pocket/restore {bucket, key, version}` |
| `push` | instant sync through ntfy (UnifiedPush) | app side done; server must accept `POST /_pocket/push {endpoint}` and publish "changed" messages |
| `lan` + `lan_endpoint` | faster address at home | app side done (probes it first, falls back) |
| `move` | rename without copying | **not used yet.** The app copies then deletes. Needs a documented request shape |
| `changes` | skip full listings | **not used yet.** The app lists the whole prefix each run |

The `/_pocket/` requests are signed with the headers `x-pocket-key`, `x-pocket-date`, `x-pocket-sign`
(HMAC-SHA256 of `METHOD\nPATH\nQUERY\nDATE\nsha256(body)`), exactly as `pocket/sigv4.py` does; `ServerApiTest` checks the app's
signatures against the server's own.

To try a feature before the server announces it, add its name to the string `forcedFeatures` (comma separated) in the app's
private `shared_prefs/drive.xml` (debug builds: `adb shell run-as cloud.pocket.drive`).
