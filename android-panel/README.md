# Pocket Cloud panel (Android)

The control panel for a Pocket Cloud server as a phone app: overview, apps, files, alerts, SD cards,
activity and settings. It signs in to the server's control panel API with the admin password.

## Build and test

```
cd android-panel
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

JDK: Android Studio's (`C:\Program Files\Android\Android Studio\jbr`). Minimum Android 10, target 36.

## Releasing

The app updates itself from this repository's GitHub releases (Settings › About). A release is one
tag push:

1. Bump `appVersionName` in `app/build.gradle.kts`.
2. Add a `## [<version>]` entry to `CHANGELOG.md`; the workflow refuses a version without one.
3. Push the tag `panel-v<version>`.

The workflow (`.github/workflows/android.yml`) runs the tests, builds a release signed with the key
in the `POCKET_KEYSTORE_*` secrets, and publishes `pocket-panel-<version>.apk` on the release.
Every release must be signed with the same key, or phones cannot install it over the previous one.
A debug build is signed with a different key, so the first release has to be installed by hand once
over a phone's debug build; after that the app offers each new release itself.

To sign a release build locally, put `POCKET_KEYSTORE_FILE`, `POCKET_KEYSTORE_PASSWORD`,
`POCKET_KEY_ALIAS` and `POCKET_KEY_PASSWORD` in `local.properties` (gitignored).
