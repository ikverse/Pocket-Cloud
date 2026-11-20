# Changelog — Pocket Cloud panel app

Each release's notes live here. The release workflow copies the entry for the tagged version onto
the GitHub release, and refuses to publish a version that has no entry.

---

## [0.3.0] — 2026-11-21

- New look and a new icon: the cobalt "PC" monogram, and a redesign around Home, Apps, Files, Phone and
  Settings, matching the new web control panel.
- Home shows whether the phone is healthy, how full it is, what each app did today and anything that needs
  you; the phone's storage, SD cards, nightly jobs and logs now live together under Phone.
- Each app keeps its own colour, the same in the app and in the web panel.
- New on an app's page (with a Pocket Cloud server v2): "Keep only recent files" rules that the nightly
  job applies, a switch for the publish folder that devices read updates from, a switch that lets the app
  send its own notifications, and how many changes its change feed holds.
- No more white flash when the app opens in dark mode.

## [0.2.0] — 2026-10-12

- The app can now update itself: Settings › About checks the Pocket Cloud releases on GitHub,
  downloads the newer build and hands it to Android's installer. A check runs when the app opens
  (switch it off in the same card); nothing is downloaded or installed without a tap.
- Debug builds now call themselves `<version>-debug`.

## [0.1.0] — 2026-10-11

- First build: overview, apps, files, alerts, cards, activity and settings for a Pocket Cloud server.
