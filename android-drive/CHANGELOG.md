# Changelog — Pocket Drive

Each release's notes live here. The release workflow copies the entry for the tagged version onto
the GitHub release, and refuses to publish a version that has no entry.

---

## [0.3.1] — 2026-11-21

- Fixed: the setup screen's headings were black on the dark background.
- The setup screen now says where the QR code and the enrollment secret really are.
- New on the setup screen: Paste setup text, for the "Copy setup file" button of the Pocket Cloud app on
  the same phone.

## [0.3.0] — 2026-11-21

- New look and a new icon: the teal "PD" monogram, and a redesign around five tabs — Home, Files, Sync,
  Activity and Settings.
- Home shows at a glance whether everything is backed up, your space, camera backup, files kept offline,
  recent files and the Vault.
- Activity brings transfers in progress, failed transfers (with the reason and Retry) and the sync log
  together; a transfer bar stays above the tabs while anything is moving.
- Every file has one action sheet: open, download, keep offline, share a link, rename, move, move into
  the Vault, delete.
- Sync shows a file changed in two places at the top, with Keep phone's / Keep PC's / Keep both.
- Rename and move are now done by the server in one step when it supports it (Pocket Cloud server v2),
  so they are quicker and a file keeps its time and ETag; older servers still get a copy and a delete.
- Fixed: the Vault kept saying "Unlock" after a correct password.

## [0.2.0] — 2026-10-12

- The app can now update itself: Settings › About checks the Pocket Cloud releases on GitHub,
  downloads the newer build and hands it to Android's installer. A check runs when the app opens
  (switch it off in the same card); nothing is downloaded or installed without a tap.
- Debug builds now call themselves `<version>-debug`.

## [0.1.0] — 2026-10-12

- First build: browse, upload, download, share, offline copies, vaults, folder and camera sync,
  share links, quota, push and Shizuku backup of Android/data.
