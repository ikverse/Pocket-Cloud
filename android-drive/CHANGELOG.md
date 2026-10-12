# Changelog — Pocket Drive

Each release's notes live here. The release workflow copies the entry for the tagged version onto
the GitHub release, and refuses to publish a version that has no entry.

---

## [0.2.0] — unreleased

- The app can now update itself: Settings › About checks the Pocket Cloud releases on GitHub,
  downloads the newer build and hands it to Android's installer. A check runs when the app opens
  (switch it off in the same card); nothing is downloaded or installed without a tap.
- Debug builds now call themselves `<version>-debug`.

## [0.1.0] — 2026-10-12

- First build: browse, upload, download, share, offline copies, vaults, folder and camera sync,
  share links, quota, push and Shizuku backup of Android/data.
