# Changelog — Pocket Cloud panel app

Each release's notes live here. The release workflow copies the entry for the tagged version onto
the GitHub release, and refuses to publish a version that has no entry.

---

## [0.2.0] — 2026-10-12

- The app can now update itself: Settings › About checks the Pocket Cloud releases on GitHub,
  downloads the newer build and hands it to Android's installer. A check runs when the app opens
  (switch it off in the same card); nothing is downloaded or installed without a tap.
- Debug builds now call themselves `<version>-debug`.

## [0.1.0] — 2026-10-11

- First build: overview, apps, files, alerts, cards, activity and settings for a Pocket Cloud server.
