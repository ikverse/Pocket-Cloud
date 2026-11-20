# Pocket Cloud v2 — the plan

Approved direction (2026-11-20): the two mockups in `mockup/` are the target design.

- `mockup/panel-v2.html` — the control panel (web, and the Android panel app at phone size)
- `mockup/drive-v2.html` — the Pocket Drive Android app

The goal of v2 is that your own projects actually use the phone: an app-facing server API that needs no S3
library, small client libraries with one-line helpers (backup, sync, changes, publish, notify), and two
redesigned apps with their own branding.

The work is split so that most of it runs in parallel sessions. **Every session still follows the global rule
in `~/.claude/CLAUDE.md`: post the list of changes, wait for the word "approve", then write code.**

```
Step 0  Contract (1 session)            docs/app-api.md + docs/brand.md
          |
Step 1  Seven parallel sessions         A server · B python · C kotlin-lite · D node
          |                             E web panel · F Pocket Drive · G Android panel
Step 2  Bring it together (1 session)   merge, cross-test, wire rules, releases, phone
          |
Step 3  Your projects, one at a time    POCKET-CLOUD.md in each project's folder
```

---

## Branding

Both apps follow RaceWall's icon pattern: a two-letter monogram on a dark tile, the first letter light grey,
the second letter in the app's own colour. They share the mockups' type and layout, so they read as one family
and are told apart by colour.

| App | Monogram | Brand colour | Dark-theme tint | Tile | Grey letter |
|---|---|---|---|---|---|
| Pocket Cloud (web panel + Android panel) | **PC** — C in colour | Cobalt `#3350E8` | `#8198FF` | `#161D31` | `#D0D4E0` |
| Pocket Drive | **PD** — D in colour | Teal `#12A37F` | `#4CD0A0` | `#161D31` | `#D0D4E0` |

- **Typeface:** display Bricolage Grotesque (700), body Instrument Sans, data JetBrains Mono. On Android, bundle
  the three as font resources (all are OFL).
- **Monogram:** drawn as outlined paths from Bricolage Grotesque 700, never live text, so it looks the same
  everywhere. Letters sit in the adaptive icon's 66 dp safe zone, the same placement as RaceWall's
  `ic_launcher_foreground.xml`.
- **Monochrome icon** (Android 13 themed icons): both letters in one colour.
- **Web favicon:** the same monogram as an SVG.
- **Where the colour goes:** icon, primary buttons, selected tab, progress bars, links, charts' first series.
  Warnings stay amber `#C97A00`, errors red `#D1344B`, success green `#17966B` in both apps.
- The Drive mockup currently uses cobalt; session F switches it to teal.

Session 0 writes this up as `docs/brand.md` with the exact tokens for CSS and for Compose.

---

## Step 0 — the contract (one short session, first)

Everything in step 1 depends on two documents. Writing them first is what lets seven sessions work at once
without waiting on each other.

### Brief for session 0

> Work in `C:\Users\kabas\Documents\Claude\Claude Code\Pocket-Cloud` on a branch `v2/contract`.
> Read `docs/plan-v2.md`, `mockup/panel-v2.html`, `mockup/drive-v2.html`, `server/pocket/gateway.py`,
> `server/pocket/server.py`, `server/pocket/sigv4.py` (sign_pocket / verify_pocket) and
> `clients/python/pocket_cloud/__init__.py`.
> Write two documents, no code:
>
> 1. `docs/app-api.md` — the exact v2 app-facing API:
>    - `GET /_pocket/health` gains `"features": [...]`; list every feature name.
>    - Plain file routes signed with the device key the same way as `/_pocket/quota`
>      (`x-pocket-key`, `x-pocket-date`, `x-pocket-sign`): read, write (with an optional
>      `if-match` etag), list (prefix, cursor), delete, move. Paths, query params, status codes, JSON shapes,
>      error codes, size limits.
>    - Change feed: `GET /_pocket/changes?since=<cursor>` — what a change record holds (bucket, key, kind:
>      put/delete/move, size, etag, time, device), how cursors work, how long changes are kept, what a client
>      does when its cursor is too old.
>    - Publish folder: per app, `releases/` in the shared bucket readable without a key on the tailnet at
>      `/_pocket/pub/<app>/...`, switched on per app; the manifest format (`latest.json`) for app updaters.
>    - Retention rules: per app, list of `{prefix, keep_last, keep_days}`, applied nightly.
>    - App notifications: `POST /_pocket/notify {title, message, tags, priority}` to the app's own topic;
>      rate limit.
>    - Panel API additions for per-app rules (read + change), shape only.
>    - The library surface shared by Python, Kotlin and Node: `app(name)`, `get/put/list/delete/move`,
>      `getJson/putJson`, `backup(path, keep)`, `restoreLatest(prefix)`, `sync(folder, bucket)`,
>      `changes(since)` / `onChange`, `publish(file, version)`, `releases().latest()`, `notify(...)`,
>      `share(key, expires)`, `quota()`. Same names in all three (idiomatic case), same errors.
>    - A small fake-server spec (in-memory, same routes) that sessions B, C and D build for their tests.
> 2. `docs/brand.md` — the branding section of this plan as exact tokens: CSS custom properties for light and
>    dark, a Compose `ColorScheme` per app, type scale, corner radii, spacing, monogram geometry (viewport 108,
>    safe zone, letter heights and gap), favicon.
>
> Done when both files are written and the user has read them. Do not merge; the step 1 sessions branch from
> `v2/contract`.

---

## Step 1 — seven parallel sessions

All seven branch from `v2/contract`, each in its own git worktree, each on its own branch. **Each owns its
folders outright; no two sessions edit the same file.** Shared rules:

- Nobody edits `.github/workflows/`, the root `README.md`, `.gitattributes`, `docs/app-api.md`,
  `docs/brand.md`, or version numbers/changelogs. Those change once, in step 2. If the contract looks wrong,
  stop and tell the user rather than editing it.
- Libraries (B, C, D) test against their own fake server built from the contract, so they never wait for A.
- Apps (E, F, G) feature-detect from `/_pocket/health` `features`; anything the running server lacks is hidden,
  not shown disabled.
- Each session ends with its tests passing and its branch **not merged**.

| # | Session | Branch | Owns | Depends on |
|---|---|---|---|---|
| A | Server features | `v2/server` | `server/pocket/`, `server/tests/` | contract |
| B | Python library | `v2/py` | `clients/python/` | contract |
| C | Kotlin light library | `v2/kotlin-lite` | `clients/kotlin-lite/` (new) | contract |
| D | Node library | `v2/node` | `clients/node/` | contract |
| E | Web panel rebuild | `v2/web-panel` | `server/panel_web/` | contract, brand |
| F | Pocket Drive redesign | `v2/drive` | `android-drive/` | contract, brand |
| G | Android panel redesign | `v2/android-panel` | `android-panel/` | contract, brand |

### Brief A — server features

> Work in Pocket-Cloud on branch `v2/server` in its own worktree. Read `docs/app-api.md` and
> `docs/plan-v2.md`. Touch only `server/pocket/` and `server/tests/`.
> Implement, each with tests in `server/tests/`:
> 1. `features` in `/_pocket/health`.
> 2. The plain file routes (read, write, list, delete, move) through the same checks as S3 requests in
>    `gateway.py` (`_check_device`, `check_space`, read-only mode, undo copies, usage, push on change).
>    Write via rclone or directly into the layout — decide and say which in the change list, with the reason.
> 3. Server-side move for S3 clients too (Drive currently copies + deletes).
> 4. The change feed: a `changes` table in the stats or registry database, written wherever `notify_change`
>    is called today, with cursors and pruning per the contract.
> 5. Publish folder: a key-less read listener on the tailnet address only, per-app switch, `latest.json`.
> 6. Retention rules: stored per app in `registry.db` (migrate like `_migrate`), applied by the nightly job in
>    `jobs.py`, logged, and kept files go through the normal undo.
> 7. `POST /_pocket/notify` publishing to the app's topic in `push.py`, rate-limited.
> 8. Panel API routes in `panel.py` / `panel_api.py` to read and change per-app rules, publish switch and
>    notify switch.
> Keep `python -m unittest` from `server/tests` green (the full suite takes ~3 min; `test_phone` needs the
> real phone and is skipped unless `POCKET_TOKEN` is set). Done: all routes in the contract answer as
> specified, tests pass, `demo_server.py` still runs.

### Brief B — Python library

> Branch `v2/py`, own worktree. Read `docs/app-api.md`. Touch only `clients/python/`.
> Keep `connect()` exactly as it is. Add `pocket.app(name)` returning an object with the shared library
> surface, using the plain routes (no boto3 needed for them; boto3 stays a dependency for `connect()`).
> `sync(folder, bucket)` is two-way with a local state file, uses the change feed, never deletes on the first
> run, and stops before deleting more than 50 files without `allow_mass_delete=True`. `backup(path, keep)`
> copies SQLite files safely (the `sqlite3` backup API) before upload. Add a `pocket` command-line tool
> (`pocket backup`, `pocket sync`, `pocket ls`, `pocket get/put`, `pocket notify`, `pocket publish`) as a
> console script. Build the in-memory fake server from the contract in `tests/` and test against it.
> Done: tests pass, `pip install -e clients/python` gives a working `pocket` command, README updated.

### Brief C — Kotlin light library

> Branch `v2/kotlin-lite`, own worktree. Read `docs/app-api.md`. Create `clients/kotlin-lite/` as a new
> Gradle library (Kotlin/JVM, usable from Android; OkHttp + kotlinx.serialization; **no AWS SDK**). Leave
> `clients/kotlin/` untouched — Pocket Drive keeps using it.
> Implement the shared library surface, enrollment (same `/_pocket/enroll` as `Pocket.kt`), re-enroll on
> `device-address-changed`, request signing (port `sign_pocket` from `server/pocket/sigv4.py`), streaming
> upload/download, `backup(file, keep)` with a safe SQLite copy hook the caller supplies, `sync`, `changes`
> as a `Flow`, `releases().latest()` + download, `notify`. Credentials are stored through an interface the app
> implements (so Android apps can keep them in Keystore-encrypted storage). Fake server from the contract in
> `src/test`. Done: `./gradlew test` passes, a README with a 10-line Android example.

### Brief D — Node library

> Branch `v2/node`, own worktree. Read `docs/app-api.md`. Touch only `clients/node/`. Keep the existing
> `connect()`. Add `app(name)` with the shared surface on the plain routes (built-in `fetch`, no AWS SDK for
> these), typings in `index.d.ts`, `onChange` via the change feed (and the push topic when available),
> `releasesUrl()` for electron-updater's generic provider. Fake server from the contract in `test/`.
> Done: `npm test` passes, README updated.

### Brief E — web panel rebuild

> Branch `v2/web-panel`, own worktree. Read `docs/brand.md`, `docs/app-api.md` and open
> `mockup/panel-v2.html` in a browser — that is the design. Touch only `server/panel_web/`.
> Rebuild `index.html`, `panel.css`, `panel.js` to the v2 design on the **existing** panel API
> (`server/pocket/panel.py` routes) — every screen of the current panel must still be reachable: overview,
> storage pool and SD cards (swap, put back, unassigned), apps (allocation, warn %, device cap, reserve,
> family), devices (rename, block, remove), files (browse, upload, download, delete, card files), jobs,
> alerts, settings (all of them, including enroll secret, machine setup file, backup/restore), logs. Map them
> into Home / Apps / app page / Connect a project / Files / Phone / Settings. The per-app rules from the
> contract appear only when `/api/...` reports them. PC monogram favicon, cobalt, light + dark. Keep uPlot for
> charts. Check it against `server/tests/demo_server.py` (pick free ports, e.g. `python demo_server.py 18765
> 19765`; demo password is in that file). Done: every old function reachable, `test_panel` passes, checked at
> desktop and phone width in both themes.

### Brief F — Pocket Drive redesign

> Branch `v2/drive`, own worktree. Read `docs/brand.md` and open `mockup/drive-v2.html` — that is the design,
> in **teal** (the mockup still shows cobalt). Touch only `android-drive/` and not its version or changelog.
> Rebuild the UI layer (`ui/`) to the v2 design: tabs Home / Files / Sync / Activity / Settings, the persistent
> transfer bar, the file action sheet, Sync with conflicts on top, Activity merging Transfers + failures +
> sync log, Vault entry from Home, first-time setup with QR. Keep the data layer (`data/`) as is except where
> a screen needs a new read-only query. New PD monogram launcher icon (foreground, background, monochrome)
> replacing the folder icon; bundle the three fonts. Do not use the new server features yet (step 2 does).
> Build with `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
> :app:testDebugUnitTest`. Check on the emulator against the demo server. Install on a real phone only with
> `--user 0` (see the project memory about Dual Messenger). Done: builds, unit tests pass, every current
> feature reachable, screenshots of each screen in both themes shown to the user.

### Brief G — Android panel redesign

> Branch `v2/android-panel`, own worktree. Read `docs/brand.md` and open `mockup/panel-v2.html` at phone
> width — that is the design. Touch only `android-panel/` and not its version or changelog. Rebuild the UI
> layer to the v2 design with a bottom bar Home / Apps / Files / Phone / Settings, keeping every function of
> the current screens (`ui/screens/*`). New PC monogram launcher icon in cobalt; bundle the three fonts;
> regenerate the colour scheme (`tools/gen_scheme.py` or by hand from `docs/brand.md`). Build and test as in
> brief F (same JAVA_HOME). Done: builds, tests pass, screenshots of each screen in both themes.

---

## Step 2 — bring it together (one session, after all of step 1)

### Brief for step 2

> Work in Pocket-Cloud on `main`. The seven branches `v2/server`, `v2/py`, `v2/kotlin-lite`, `v2/node`,
> `v2/web-panel`, `v2/drive`, `v2/android-panel` are finished. List the plan, wait for "approve", then:
> 1. Merge `v2/contract`, then `v2/server`, then the rest, resolving nothing silently.
> 2. Run all three libraries' tests against the **real** server (`server/tests/helpers.PocketServer`) as well
>    as their fakes; fix mismatches on the side that disagrees with `docs/app-api.md`.
> 3. Wire the per-app rules (retention, publish, notify) into the web panel and the Android panel.
> 4. Move Pocket Drive onto server move, the change feed and the features list (it currently copies + deletes
>    and lists whole prefixes; see `android-drive/README.md`).
> 5. CI: add the library tests to `.github/workflows/`; README updated.
> 6. Versions: both apps to 0.3.0 with CHANGELOG entries; push `panel-v0.3.0` and `drive-v0.3.0` tags only
>    when the user says so (the release workflow publishes signed APKs).
> 7. The phone: push the server with `phone/push.sh`, then the user runs `setup.sh` in Termux; check
>    `/_pocket/health` lists the new features over Tailscale.
> Done: everything merged, all tests green, phone running v2, both apps installed from the release.

**Left for later, on purpose:** shared spaces between apps (generalising the family bucket). It changes how
the rclone config is written per app and deserves its own change list once v2 is proven.

---

## Step 3 — your projects, one at a time

Each project has a `POCKET-CLOUD.md` in its own folder: goal, what is stored and where, files to change,
steps, tests, and what "done" means. Start a session in that folder and point it at the file. All need steps
1 and 2 finished first, because they use the v2 libraries.

| Order | Project | Guide | Why this order |
|---|---|---|---|
| 1 | Signal Lab EGX | `signal-lab-egx/POCKET-CLOUD.md` | Its ledger and hand-entered dividends have no backup at all today |
| 2 | EGX Analyzer | `android/POCKET-CLOUD.md` | Already has a `SyncTransport` seam; biggest daily use |
| 3 | Signal Lab | `signal-lab-android/POCKET-CLOUD.md` | Already has a `RemoteFolder` seam |
| 4 | RaceWall desktop | `RaceWall/POCKET-CLOUD.md` | Do together with 5 |
| 5 | RaceWall tablet | `RaceWall-Android/POCKET-CLOUD.md` | The other half of 4 |
| 6 | Village | `PlatformExperimental/POCKET-CLOUD.md` | Database backup + phone notifications |
| 7 | ModelGen | `ModelGen/POCKET-CLOUD.md` | Selected models only; space is tight |
| 8 | ClaudeIn | `ClaudeIn/POCKET-CLOUD.md` | Small: encrypted pairings backup |
| 9 | Deskglow | `Deskglow/POCKET-CLOUD.md` | Small: layout snapshots |

**Space budget.** The pool is ~14 GB internal + the SD card (Card 2). The guides keep each project well inside
a few GB: no video, no raw 3D versions, no browser profiles. Upload speed over Tailscale was 0.8–4.8 MB/s.

**Secrets never go up in plain text.** Keystores, pairing keys, tokens and API keys either stay out, or are
encrypted on the machine before upload (the guides say which).
