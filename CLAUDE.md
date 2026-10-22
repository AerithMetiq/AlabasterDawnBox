# CLAUDE.md

Guidance for Claude Code working in this repository. Read it before changing anything.

## What this is

**AlabasterDawnBox**: an unofficial Android launcher that plays **Alabaster Dawn** (Radical Fish Games,
Steam app **3110760**, Early Access) on Android. The user signs in to Steam, the app downloads the copy
their account owns, runs the game's web build (`terra/`) in a WebView, and syncs saves with Steam Cloud.
Built for controller-first handhelds (the maintainer's devices: **Retroid Pocket Nova**, **AYN Odin 3**)
and phones. Its launcher design follows CrossDroid's (a CrossCode launcher by dain98); none of
CrossDroid's code is in it (hard rule 2).

**Renamed from Alabaster Droid on 2026-10-21**, app ID included (`io.github.aerithmetiq.alabasterdroid`
→ `io.github.aerithmetiq.alabasterdawnbox`), so it installs as a new app: sign in again, download the
game again, saves come back from Steam Cloud. The GitHub repository is `AerithMetiq/AlabasterDawnBox`;
the local folder is still `~/AlabasterDroid` (Claude Code's notes for this project are keyed to it).
Its sibling for CrossCode is **CrossCodeBox** (`~/CrossCodeBox`, built from this code base).

Package / applicationId: `io.github.aerithmetiq.alabasterdawnbox`. MIT licensed.

## Who does what

- **Claude writes the code; AerithMetiq reviews and tests it** on real devices. AerithMetiq is not an
  Android developer: give exact, copy-pasteable steps (Konsole commands, Android Studio menu paths), one
  thing at a time, and say what output to expect.
- AerithMetiq tests by building in Android Studio and sending logs (`adb logcat`, the Build panel output,
  or the in-app Steam diagnostics report). Ask for the specific log you need.
- **Commit attribution:** Claude is the commit author: `Claude <noreply@anthropic.com>`. Do not credit
  AerithMetiq as author. When AerithMetiq has tested a change, a `Reviewed-by:` trailer may be added at
  their request. README and NOTICE say: written by Claude, reviewed and tested by AerithMetiq.
- **Never push, force-push, rewrite published history or publish a release** unless AerithMetiq asks for
  that specific action in the session.

## Hard rules

1. **No game files in the repo or APK.** No Alabaster Dawn art, audio, data, code or fonts, from any
   source. The launcher's backdrop and title image are read at runtime from the user's
   downloaded copy (`GameFiles.titleBackground` = `title-color-01.png`, `titleName` =
   `title-name-01.png`, which replaces the "ALABASTER / DROID" wordmark while it is shown). The app icon and the fallback wordmark are
   original; game art can never be the installed app's icon, because an icon ships inside the APK.
2. **No CrossDroid code.** dain98 had allowed CrossDroid's code (github.com/dain98/CrossDroid) to be
   reused, and **withdrew that permission on 2026-10-21**. Do not copy, adapt or read CrossDroid's code
   for this project, the local copy at `~/CrossDroid` included. The one file that had been adapted
   from it, `steam/SteamCloudFiles.kt`, was written again that day from JavaSteam's API and the
   protocol facts below, without reading the old version. At AerithMetiq's request the history was
   rewritten the same day so that every commit carries the new version (force-pushed with the
   `v0.1.0` tag). The `v0.1.0` pre-release APK on GitHub was built before that, from the old code.
   The README still credits CrossDroid's launcher design (not code).
3. **alabaster-android** (github.com/moronigranja/alabaster-android) is **MIT**, © 2026 Moroni Granja.
   Its code may be reused for milestone 3, but every reused file keeps its copyright notice, and the
   reuse is listed in `NOTICE.md`. Prefer copying whole, well-understood pieces over rewriting them: its
   device fixes were found on real hardware.
4. **Secrets stay secret.** Never log or display the Steam password, refresh token or Steam Guard data.
   Logins live only in `LoginStore` (Android Keystore AES-GCM). Diagnostic reports must leave out the
   account name and Steam Cloud machine names, so they can be shared as-is.
5. **Read-only by default against Steam.** Anything that writes to Steam (Cloud uploads, playing status)
   must be deliberate, and should be testable first in a dry-run or diagnostics form.

## Status

| Milestone | Scope | State |
|---|---|---|
| 1 | Launcher UI, Steam sign-in (password + Steam Guard, QR), encrypted login, profile/avatar, Settings, Steam diagnostics | done; sign-in and diagnostics confirmed on the Retroid Pocket Nova (2026-10-12) |
| 2 | Download Alabaster Dawn's `terra/` from Steam into `GameFiles.root` with progress | done; download confirmed on the Retroid Pocket Nova (2026-10-12) |
| 3 | Game host: WebView, Node/NW.js shim, shader/bundle rewrites, gamepad bridge (from alabaster-android) | done; the game runs on the Retroid Pocket Nova (2026-10-12) |
| 4 | Steam Cloud saves: pull on PLAY, push on quit, conflict handling, offline mode | written 2026-10-12, unit-tested. On the Nova the same day: a first PLAY asked about the conflict, downloaded all 9 Cloud files (checksums matched), backed up the 8 local ones and started the game. AerithMetiq then turned uploads on and reported the upload worked |

The main button reads DOWNLOAD while the game is not installed and CANCEL during a download (the
percentage is in the status line and the bar below, not on the button); once installed it reads PLAY, which syncs the saves and then starts `host.GameActivity`.

**Added after milestone 4 (2026-10-12; unit-tested).** Run on the Nova the same day: verify game
files, PLAY with sync, the sync status line, the launcher's Resolution setting reaching the shim, the
restore list, and the launcher's Game settings. AerithMetiq also confirmed the
background download (screen off included), the playing status and a real restore (20:22: the 8 files
went back, the replaced ones were backed up, and the next sync uploaded them). Not yet run: an actual
update (needs a new build on Steam) and delete game files.

- **Background download** (`GameDownloadService`, a `dataSync` foreground service with a progress
  notification and a Cancel action; wake and Wi-Fi locks). The launcher only mirrors
  `GameDownloadState`. One run downloads, resumes, updates or verifies: the downloader hashes every
  file against the manifest. Settings → "Verify game files" starts the same run.
  Without the notification permission (Android 13+; it was declined on the Nova) the download
  still runs but shows nothing outside the app; the launcher's caption says so. The notification
  opens the app with the package's launch intent, so it does not stack a second launcher.
- **Game updates**: the launcher's start-up logon also reads the public build id
  (`steamPublicBuild`, read-only) into `GameFiles.steamBuild`; when it differs from the marker's
  `build=`, the button reads UPDATE and asks "Update now / Play this version". An update removes
  the marker first, so a cancelled update leaves the game not playable until it is finished.
- **Delete game files** (Settings): removes `terra/` and the marker; saves, backups, settings stay.
- **A logon answered `TryAnotherCM`/busy is retried on a fresh connection** (`SteamSession.open`,
  up to three times). Seen on the Nova: the first verify failed with it and the next press worked.
- **Steam sessions have a purpose** (`SteamSession.Purpose`), which sets the `loginID`: sessions
  that can overlap (profile refresh, download, save sync, playing status, diagnostics) each have
  their own id, so one does not replace another. Nothing waits for the profile refresh any more.
- **Playing status** (`PlayingService`, `SteamSession.showPlaying`): **writes to Steam**, off by
  default (`GameFiles.playingStatus`, Settings). Started with the game when it starts online,
  stopped in the launcher's `onResume`. Sets the persona Online and sends `notifyGamesPlayed`.
  Confirmed on the Nova (2026-10-12): after the two calls Steam pushes the account's own status as
  `Online, game 3110760`, which the service logs (`adb logcat -s AlabasterDawnBox | grep "playing status"`).
  Trust those pushes, not `requestFriendInfo` on oneself: its answer said `Offline, game 0` seconds
  after a push said otherwise, which cost an hour of looking for a bug that was not there.
- **Save backups can be restored** (`steam/SaveBackups.kt`, Settings → Steam Cloud saves →
  "Restore a backup"). A backup holds only the files a sync replaced, not a full snapshot; a
  restore backs up what it replaces. Newest 8 folders are kept.
- **Sync status line** under PLAY (`CloudSaves.syncStatus`): time of the last sync that reached
  Steam (`GameFiles.lastSync`) and how many local save files differ from the base (computed
  locally, no network).
- **Resolution from the launcher** (Settings → Game): the option lives in the page's
  `localStorage["xg_local_options"]["pixel-size"]` (a rung index, 0-4; the launcher offers the same five
  sizes the game's options do), which only the page can
  write. The launcher stores the chosen rung (`GameSettings.KEY_RESOLUTION_NEXT`), the next start
  passes it once as `?adaPixelSize=N`, and the shim stores it before the engine boots. The last
  resolution the engine reported is kept (`KEY_RESOLUTION_LAST`) for the launcher to show.

**Steam Cloud saves (milestone 4), how it works:**

- The sync is three-way and per file (`steam/CloudSaves.kt`, pure and unit-tested): besides this
  device and Steam Cloud it keeps a base, the SHA-1 each file had when the two sides were last equal
  (`GameFiles.syncBase`, `cloud-sync.txt`). Only one side changed: that side wins. Both changed, or
  different files with no base (a first sync): a conflict, and the user picks one side for all files.
- PLAY (unless "Play offline" is ticked) runs `LauncherActivity.syncSaves`: downloads first, each
  replaced local file copied to `SaveBackups/<time>/` (newest 8 kept), then uploads, then the game.
  Steam unreachable: the game starts with the local saves. A failed download: nothing is changed and
  the game does not start.
- Sessions for saves use `SteamSession.Purpose.SAVES`.
- Coming back from the game (launcher `onResume`, flag `syncAfterGame` in the `launcher` preferences,
  so it survives the launcher being stopped) runs the sync again to upload.
- **Uploads are off by default** (hard rule 5): `GameFiles.cloudUploads`, switched in Settings →
  Steam Cloud saves (`CloudSavesActivity`), whose "Check now" is the dry run: it lists every file
  and what a sync would do, and changes nothing. With uploads off, changed files are counted and
  left; they stay "changed here" and go up once uploads are on.
- The app **never deletes a Steam Cloud file**. A save deleted on the device is left in the Cloud
  (and not fetched again until the Cloud copy changes); a file deleted in the Cloud is moved to the
  backup folder here. Zero-byte local files are ignored.
- `steam/SteamCloudFiles.kt` is the only code that writes to Steam (`upload`). It uploads without an
  upload batch (`uploadBatchId = 0`); a batch needs the auth session's client id, which is not kept.
  It sends the file uncompressed with `canEncrypt = false`, commits (or closes a failed transfer as
  failed), then reads the Cloud listing back and only counts the upload when the file is listed with
  the SHA-1 it sent. A download may come back as a one-entry zip; it is unpacked and checked against
  Steam's SHA-1. Rewritten 2026-10-21 (hard rule 2). On the Nova that day the listing was confirmed
  ("Check now": every file `same on both`, checksums matching); **download and upload not yet run**
  (they run when a sync has something to move).

**What the device's diagnostics report showed (2026-10-12, public build 25724722):**

- Depots: `3110761` is shared by Windows and Linux (341.5 MiB); `3110762`–`3110765` are the per-OS,
  per-arch desktop runtimes. The report lists no file names, so the downloader does not hard-code a
  depot: it reads manifests (shared depots first) until one lists `terra/index.html`. Still don't guess.
- Steam Cloud (`ufs`): quota 100 MB, 500 files, pattern `*` recursive. The root is the game's `Saves/`
  folder (Windows `%LOCALAPPDATA%/Alabaster Dawn/Saves/`, Linux `~/.config/Alabaster Dawn/Saves/`), and
  Cloud file names are relative to it under the `%WinAppDataLocal%` token: `Default/`, `Backups/`,
  `Backups2/`, each with `Save_ID_auto.save`, `Save_ID_0001.save`, `System.save`. `CloudSaves.relative` maps
  these names to the device's save folder; keep that folder outside `terra/` (the downloader deletes
  files under `terra/` that the manifest does not list).

## Layout

```
app/src/main/java/io/github/aerithmetiq/alabasterdawnbox/
  LauncherActivity.kt     home screen: sign-in, PLAY, offline toggle, profile refresh
  SettingsActivity.kt     game settings, Steam Cloud saves and diagnostics entries, notices, about
  SteamProbeActivity.kt   read-only Steam diagnostics using the saved login
  Screens.kt              shared programmatic UI pieces for secondary screens
  QrCode.kt               QR dialog sizing (fits any aspect ratio) + bitmap
  GameFiles.kt            where the downloaded game lives (external app files/AlabasterDawn)
  steam/SteamSession.kt   one JavaSteam connection: connect, QR/password sign-in, logOn, profile
  steam/LoginStore.kt     encrypted saved login
  steam/SteamProbe.kt     PICS + Steam Cloud listing for diagnostics
  steam/ProbeReport.kt    pure formatting of probe data (unit-tested)
  steam/GameDownload.kt   pure download decisions: depot order, where terra/ is, wanted files (unit-tested)
  steam/GameDownloader.kt downloads terra/ over JavaSteam's CDN client, resumable, verified
  steam/CloudSaves.kt     pure save-sync decisions: Cloud names, the three-way plan, wording (unit-tested)
  steam/CloudSync.kt      reads both sides and carries a plan out: downloads, backups, uploads
  steam/SteamCloudFiles.kt  list / download / upload one app's Cloud files (ours; rewritten 2026-10-21)
  CloudSavesActivity.kt   Settings → Steam Cloud saves: the uploads switch, the dry-run check, restore a backup
  steam/SaveBackups.kt    local save backups: list, restore, prune (unit-tested)
  GameDownloadService.kt  the download as a foreground service, and GameDownloadState for the launcher
  PlayingService.kt       shows the account as playing on Steam while the game runs (opt-in)
  host/GameSettings.kt    ours: the game's settings keys, shared by the launcher and the game process
  host/                   the game host, from alabaster-android (commit f1cedd5); see below
  host/InProcessRenderer.kt  ours: keeps the page out of a 32-bit renderer process (see Performance)
  host/BundleBytes.kt     ours: the host's edits to bundle.js, made on its bytes (unit-tested against the String rewrites)
app/src/main/assets/ada-shim.js   alabaster-android's Node/NW.js shim (settings polled twice a second, not per frame; frame gate keeps a running deadline; pad JSON parsed only when it changes)
app/src/main/res/layout/activity_launcher.xml   the launcher layout
```

## Build and test

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests (ProbeReportTest, QrCodeFitTest, GameDownloadTest, CloudSavesTest, host/*)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s AlabasterDawnBox        # the app's log tag
```

Keep pure logic (parsing, sizing, decisions) in small Android-free functions with unit tests. Code that
needs a device or Steam must be described to AerithMetiq as untested until they run it.

## The game host (`host/`)

Copied from alabaster-android rather than rewritten (hard rule 3). Keep it close to upstream so its
later device fixes can be carried over file by file: change a file only when AlabasterDawnBox needs it
to differ, and say what changed in the file's header and in `NOTICE.md`.

- Verbatim apart from the package name: the shader rewrites (`ShaderSlots`, `ShaderArrays`,
  `ShaderDither`, `ShaderVariants`, `RewriteCache`), `PortBridge`, `OnScreenPadModel`, `Diag*`,
  `LogFile`, `GameProfile` (its CrossCode entry is unused here) and the tests.
- Changed for speed only (2026-10-21, see Performance): `GamepadState` (payload written with a
  `StringBuilder`, one publish per controller event via `batch`), `Gamepad` (uses `batch`;
  `getMotionRange` lookups), `OnScreenPadView` (idle timer posted once, not per input event; it also
  has the "Touch controls" switch), `GameVersion` (`VERSION_MANAGER` made internal for `BundleBytes`).
- Adapted: `GameActivity` (upstream `PortActivity` minus the SAF folder pickers and entry screen; it
  indexes `GameFiles.root` and launches the WebView), `GameIndex` (`GameTree.index` walks plain
  files; `GameEntry.docId` is an absolute path), `GameAssetHandler` and `FsBridge` (read files, no
  `SafStore`), `SideMenuView` (no game-selection row; controller selection), `FpsLimit` and its test
  (see below), `ShaderPrecision` (raises the light, fog and
  combine shaders to highp as well as upstream's four GUI ones, after the array lift: game 0.2.0-7
  has 35 mediump shaders, and at mediump the depth maths bands into horizontal streaks on Adreno.
  Confirmed on the Nova: fog only, and fog + lights, left the streaks; adding `combine.frag` removed
  them. Stages that share uniforms are always raised together, or the program does not link).
- `GameActivity` acts on the Back **key** at every API level and drops a duplicate from the back
  dispatcher. Upstream leaves API 33+ to the dispatcher, which the system only calls for apps opted
  in to it; on Android 13 that left Back (and so the side menu) dead.
- **The side menu pauses the game and takes the controller** (ours, written 2026-10-12; AerithMetiq
  confirmed it and the new frame-rate caps on the Nova). `openMenu` blurs the page (the engine's own pause) and `setPageFocus(true)`
  is refused while the menu is open. Nothing in the menu takes view focus, so the selection is
  `SideMenuView`'s own: `GameActivity.menuKey` / `menuMotion` hand it D-pad, stick, A and B
  (`padKey`); B acts on release so the game does not resume with B held.
- **Frame-rate caps are 30/45/60/90/120** (`FpsLimit`, changed from upstream's 20/30/40/45/60). The
  menu only offers the rates the screen can show (`choicesFor`), and
  `applyRefreshRate` asks for the lowest refresh rate the cap divides evenly (`refreshFor`). The
  shim's gate keeps a running deadline, so 45 on 60 Hz averages 45 (three vsyncs of four) instead
  of upstream's drop to 30. The cap is read on the engine's poll, so a change made in the menu
  lands when the game resumes. Unknown: whether the engine draws anything new above 60 fps (its
  logic was measured upstream as a 60 Hz fixed step); 90/120 need a 90/120 Hz screen to check.
- **Game settings are shared with the launcher** (`host/GameSettings.kt`, ours; written 2026-10-12,
  **not yet run on a device**). The keys live there; the launcher's Settings screen has a "Game"
  section that edits the same `ada` preferences file the side menu does. Two processes read that
  file, so both open it through `GameSettings.prefs` (`MODE_MULTI_PROCESS`, which re-reads a file
  the other process wrote) and the launcher writes with `commit`. New keys go in `GameSettings`.
- **"Touch controls" switch** (`touch_controls`, ours): off removes the whole on-screen overlay,
  its PAD/EDIT buttons included (`OnScreenPadView.touchControls`). Upstream's `on_screen_pad` only
  hides the controls and leaves those buttons.
- `GameActivity` runs in its own process (`:game`). Exit finishes the activity and then kills that
  process, so the launcher resumes.
- Saves: `GameFiles.root/userdata/` is the engine's `/saves`, so files land in
  `userdata/Saves/Default/…`, the Steam layout. The launcher syncs `userdata/Saves/`
  (`GameFiles.saves`) with Steam Cloud, only while the game's process is not running.
- The host logs under the tag `RfPort` (upstream's): `adb logcat -s AlabasterDawnBox RfPort`. In the
  game, Back opens the side menu; Troubleshoot there shows and shares the diagnostics record, which is
  also written to `userdata/` as a log file.

## Performance: measured on the Retroid Pocket Nova (2026-10-12)

At 960x540 in a busy area the game ran at 58-59 fps: about one dropped frame a second, in bursts.

- **The limit is the page's single JavaScript thread, not the GPU.** GPU busy 35-39 %
  (`/sys/class/kgsl/kgsl-3d0/gpubusy`, one read = busy/total), CPU cores at max frequency, battery
  27 °C. The engine's `requestAnimationFrame` callback took 12.4-14.5 ms (p50) of the 16.7 ms frame,
  p99 16-19 ms; garbage collection was negligible. It runs on a 2.8 GHz core, not the prime core,
  and an app cannot pin another process's thread.
- **The host's own overhead was part of it.** The shim made four synchronous `PortBridge` calls per
  frame (gamepad, frame-rate cap, picture position, stats switch). A/B by live patch, twice: 14.6 ms
  per frame with all four, 13.0-13.6 ms with only the gamepad call; frames over 16 ms fell from
  61-85 to 6-21 per 8 s. The shim now reads the three settings every 30th poll. The remaining
  per-frame call is `getGamepadJson` (it also feeds the watchdog's "engine silent" clock).
- **After that fix (installed build, same day):** 58.4-59.7 fps over 20 s windows, 3-9 dropped
  frames each. JavaScript per frame 12.0-12.3 ms mean. The drops that remain are frames where the
  callback's wall time is 20-25 ms but its CPU time is about 10 ms: the thread is runnable and
  waiting for a core. `/proc/<renderer pid>/task/<CrRendererMain tid>/schedstat` showed it waiting
  5-16 % of the time (50-160 ms per second) although the system was about 60 % idle. The renderer
  is an isolated process in the `top-app` cpuset; the app cannot set its threads' priority or
  affinity. Then added, effect not yet measured: `android:appCategory="game"`, and the host's per-motion-event
  `axes=` logging removed (the app's main thread ran at ~12 % of a core while a stick moved).
- **Root cause found: on the Nova the WebView's renderer is a 32-bit process.** The firmware's
  `com.android.webview` has `primaryCpuAbi=armeabi-v7a`, so `webview_zygote` is a child of the 32-bit
  `zygote` and the sandboxed renderer, where the game's JavaScript runs, is 32-bit, although the app
  itself is 64-bit. Two consequences, both measured:
  1. Its threads may only use the cores that still run 32-bit code: `Cpus_allowed_list: 0-2,5-6`
     (three little cores and the two Cortex-A710s). The two A715s and the prime X3 core are 64-bit
     only; cpu7 stayed at 0 % busy. The main thread and the audio thread share cpu5-6, which is the
     "waiting for a core" above. Extra load does not help: two busy workers halved the frame rate.
  2. It runs out of address space. After about six minutes of play the renderer was at
     `VmSize` 3.5 GB (`VmPeak` 3.68 GB) and the engine failed with `RESOURCE FAILURE: Couldn't decode
     audio file 'media/audio/bgm/overworld-greens-normal.wav'` (`decodeAudioData`: unable to decode).
  The game-category flag and removing the stick log changed nothing measurable (57-59 fps, waiting
  10-14 %). An app cannot choose the renderer's bitness. Leads: single-process WebView
  (`adb shell cmd webviewupdate disable-multiprocess`, device-wide, puts the renderer in the 64-bit
  app process); a 64-bit WebView from a firmware update; or shipping a 64-bit engine in the app.
- **Single-process WebView fixes it on the Nova (measured 2026-10-12).** After
  `adb shell cmd webviewupdate disable-multiprocess` and an app restart, the page runs on
  `Chrome_InProcRendererThread` inside the 64-bit `:game` process: mask `0-7`, no sandboxed process,
  no 4 GB ceiling. Waiting-for-a-core fell from 10-14 % to 0.3-0.5 %, JavaScript per frame from
  12-14.6 ms to 8.9-10 ms, and at **1920x1080** the game held 59.1-59.9 fps in most 20 s windows with
  the GPU at 97-99 % (before: 45-55 fps at 720p and at 1080p). The limit is now the GPU, as
  alabaster-android found on 64-bit WebViews. The setting is device-wide, removes the renderer
  sandbox for every app's WebView, and is undone with `enable-multiprocess`; the app cannot set it.
  Correction to an earlier guess: the engine does render the full 1920x1080 on this 1280x960
  screen; it is not clamped to the window.
- **The app now does this for itself: `host/InProcessRenderer.kt`** (our code, not upstream's).
  When the app is 64-bit and the WebView package's library folder is a 32-bit ABI's, it removes
  WebView's `webview-sandboxed-renderer` command-line switch before the engine starts, so only this
  app's page runs in-process; the device-wide setting stays at its default. Release WebViews rename
  their classes (`org.chromium.base.CommandLine` is `em` in 109.0.5414.123), so the class is found
  by its shape among the package's default-package classes and accepted only if it reports that
  switch as set. Verified on the Nova with multi-process enabled device-wide: the record says
  `webView is 32-bit on a 64-bit app; renderer: in this process (em)`, there is no sandboxed
  process, and the page thread's mask is `0-7`. Any failure leaves the page in the sandboxed
  process as before; look for that record line first when a device reports poor performance.
  Measured with it at 1920x1080: 58.7-59.8 fps over four 20 s windows, GPU 100 % busy, the page
  thread waiting for a core 0.7 % of the time, boot in 6.1 s (8.5-11.3 s before).
- **Host overhead cut further (2026-10-21; unit-tested; first record from the Nova the same day).** Graphics are
  untouched: every shader fix and the bundle's ladder and `TEX_SLOT_COUNT` edits serve the same bytes
  as before (`BundleBytesTest` compares them).
  - Boot: the Nova's record showed `slowest=304ms terra/dist/bundle.js`, and the page can do nothing
    before it has the bundle. `GameAssetHandler` now edits the bundle as bytes (`BundleBytes`: no
    12.7 MB decode/encode) and does the version and the resolution ladder *before*
    `ShaderSlots.awaitReport`, so only the table-size edit waits for the page's probe. On the PC's
    JVM with a synthetic 12.7 MB bundle: 230-400 ms before, ~15-20 ms during the wait plus ~25-30 ms
    after it.
  - Boot and area loads: files no rewrite can touch (images, audio, data) are served as an open
    `FileInputStream` instead of being read whole into a byte array first. Less heap churn in the
    `:game` process, which also holds the renderer. The "slow asset" record line now times opening a
    file, not reading it.
  - Per frame: the shim skips `JSON.parse` and the copy when the pad payload is the same text as last
    frame (the earlier CDP profile put `parseJson` at 0.2 % of the page's time; small, but free).
  - UI thread, while a stick moves: one payload per motion event instead of four, no org.json
    objects, no iterator per axis lookup, and the pad's idle timer is no longer removed and re-posted
    on every event.
  - Launcher: the title art is decoded only when its files change, not on every `onResume`.
  - The Nova's first record with it (game 0.2.0-9, 1280x720, a 90 fps cap): `slowest=44ms
    terra/dist/bundle.js`, against 304 ms in the 2026-10-12 record. That older record predates the
    in-process renderer too, whose probe answers sooner (114 ms after loading against 194 ms), so
    not all of the drop is the byte edits. Boot complete in 5823 ms with 1286 resources, against
    5.9-6.1 s with 1836 resources on game 0.2.0-7: a different game build loading different
    resources, so no boot claim either way. The same shader lifts ran, and `TEX_SLOT_COUNT 192
    packed`. Not yet checked: controls (sticks, triggers, D-pad, on-screen pad).
  - Frame times with it, measured the same day (game 0.2.0-9, 1920x1080, cap 60 on the 60 Hz mode, a
    forest area mid-fight): 57.0 fps (one 600 ms stall, cause not logged) and 59.6 fps over two 20 s
    windows, 8-9 frames over 25 ms in each; p50 and p99 16.7 ms. GPU 93-100 % busy at its top clock,
    680 MHz (the firmware's mode holds the floor at 615), at 83.6-87.6 °C with no thermal throttling
    yet (`thermal_pwrlevel 0`). The GPU is the whole limit at 1080p; host overhead is not. On this
    screen the picture is 1280x720 device pixels, so 1080p is drawn at 2.25x the pixels shown and
    scaled down: its gain is smoother lighting, fog and edges, not sharper art. Offered a 1600x900
    rung (about 30 % fewer pixels than 1080p) or a narrower precision raise; AerithMetiq chose to
    play at 1280x720 instead, so neither was built.
  - Not changed, on purpose: the frame-rate cap with the switch off still asks for the screen's
    fastest mode (120 Hz on the Nova when the system allows it). Whether the engine draws anything
    new above 60 fps is still unknown; if it does not, capping at 60 would halve GPU work for free.
- **Do not test by suspending the game's AudioContext**: with audio suspended the frame rate fell to
  47-50 fps, so the engine's pacing depends on the running audio clock and the comparison is void.
  The WebAudio thread (`AudioOutputDevice`) itself uses 40-45 % of a core.
- **How to measure** (debug builds have WebView inspection on): with the game running,
  `adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof io.github.aerithmetiq.alabasterdawnbox:game)`,
  then a CDP client on `http://127.0.0.1:9222/json`: `Tracing.start` (categories `devtools.timeline`,
  `v8`, `toplevel`) and read the `FireAnimationFrame` durations on `CrRendererMain`; `Profiler` for
  where the time goes. Count frames with `requestAnimationFrame` deltas, not an fps overlay. Compare
  A/B in the same scene, alternating, because the scene changes the cost more than most fixes do.

## Environment

- AerithMetiq's PC: **Bazzite (KDE)**, an immutable Fedora. Install CLI tools with Homebrew (`brew`), not
  `dnf`. Android Studio is the **Flathub** build (sandboxed); its Gradle JDK is set to 21.
- Devices are reached with **adb over Wi-Fi** (wireless debugging). If adb reports more than one device,
  use `adb -s <serial>` or `export ANDROID_SERIAL=<serial>`.
- The Nova's screen has **60 Hz and 120 Hz modes** (`dumpsys display`); which one is active is a
  system setting, and the performance numbers below were taken at 60. With the game asking for
  120 Hz and a 90 cap, the title screen read 91 fps at 1280x720.
- The **Retroid Pocket Nova** (Android 13, Adreno 740, 1280x960 4:3 screen) ships the AOSP WebView
  `com.android.webview` **109** and it cannot be updated or switched (checked 2026-10-12).
  alabaster-android was measured on WebView 153, so anything the host needs must work on 109. The 16:9
  game fills a 1280x720 strip of that screen. Its WebView is also **32-bit**, which cripples the
  game unless the page runs in the app's own 64-bit process, which `InProcessRenderer` arranges
  (see Performance).
- Toolchain: AGP 8.13.2, Kotlin 2.2.10, Gradle 8.14.3, compileSdk/targetSdk 36, minSdk 26, Java 17 target.
  targetSdk 36 means edge-to-edge on Android 15+: screens use `fitsSystemWindows` and
  `Screens.goldStatusBar`.

## Steam / JavaSteam notes

- JavaSteam **1.8.0** (`in.dragonbra:javasteam`) plus `org.bouncycastle:bcprov-jdk18on` (required on
  Android; the platform's BC provider is stripped). If code builds JavaSteam protobuf messages directly,
  add `com.google.protobuf:protobuf-java` at the version JavaSteam uses (4.31.1 for 1.8.0); JavaSteam
  keeps it as an implementation dependency, so it is not on the app's compile classpath otherwise.
- The game download does **not** use JavaSteam's separate depot downloader artifact: in 1.8.0 its file
  filter is not wired up (`usingFileList` is never set), so it can only fetch whole depots, and it
  swallows chunk errors. `GameDownloader` calls the core API instead (`SteamApps.getDepotDecryptionKey`,
  `SteamContent.getManifestRequestCode` / `getServersForSteamPipe`, `cdn.Client`, `DepotChunk.process`)
  and downloads only the files under `terra/`. Revisit if a later JavaSteam adds a working filter.
- **Download speed** (rewritten 2026-10-21): 2 433 of `terra/`'s
  3 031 files are 64 KiB or less, so a download is mostly waiting for answers. `GameDownloader` keeps
  `CONNECTIONS` (16) chunk requests in flight across all files, to the 3 least loaded servers (a
  server that fails a request goes to the back of the list), and interleaves big and small files
  (`GameDownload.interleaveBySize`). The download session's `SteamClient` gets an OkHttp client
  sized for that (`SteamSession`: 16 requests per host, 20 idle connections); JavaSteam's default
  client allows 5 of each. Before: 6 workers, one file each with its chunks in sequence, largest
  files first, every request to the next of all servers. A failed request is tried again on
  another server after a pause that doubles (0.5 s to 8 s, 8 attempts); the old code retried 6
  times at once and then failed the whole download. A run logs the servers it uses and
  `download: N files, X MiB fetched in S s` (`adb logcat -s AlabasterDawnBox`).
  - Old build on the Nova (Wi-Fi, full download): about 3 MiB/s for 150 s while the big files ran,
    then 0.3-0.5 MiB/s for the small-file tail; at 215 s (309.6 MiB) a chunk got HTTP 503 six times
    and the download failed; resuming took 83 s more. About 4 min 53 s in all.
  - New build, same day and network: **88 s** from the button to `installed.txt` (341 MiB in 80.4 s
    of transfer, up to ~17 MiB/s, the small-file tail ~1.2 MiB/s), 16 requests retried, none failed.
    Steam offered 20 servers; the 2nd-ranked, `cache1-pit-pitx`, refused connections from this
    network (10 s connect timeouts), was demoted, and cost the first ~10 s.
- `com.squareup.okhttp3:okhttp` is `compileOnly` (5.3.2, the version JavaSteam brings) for that client.
- `com.google.protobuf:protobuf-java` is `compileOnly` (4.31.1): `SteamCloudFiles` reads a protobuf
  enum (a Cloud file's persist state; only `Persisted` files count as existing).
- JavaSteam leaves the chunk decompressors to the app (`compileOnly`): `com.github.luben:zstd-jni` (the
  `@aar`, for Android's native libraries) and `org.tukaani:xz`. Without them chunks fail to unpack.
  `SteamContent` returns coroutine `Deferred`s, so `kotlinx-coroutines-core` is a direct dependency.
- A finished download leaves `installed.txt` (depot, manifest, build id) in `GameFiles.root`;
  `GameFiles.installed` needs it. Compare its manifest id with PICS to detect a game update.
- `SteamSession` is blocking; drive it from a worker thread and close it from any thread to cancel.
  `IOException` = Steam unreachable (keep the login, allow offline play); `LoginRejectedException` = the
  login is invalid (clear it, ask to sign in again).
- A QR sign-in must be scanned within about a minute or Steam drops the connection.
- Use a non-zero `loginID` on logon so a Steam client on the same network is not kicked off.

## Alabaster Dawn engine facts (from alabaster-android's research, FINDINGS.md)

- Entry page `terra/index.html`, bundle `terra/dist/bundle.js`, `terra` engine on WebGL2. The shim must
  provide `window.require` (`fs`, `path`, `vm`, greenworks) and `nw.*`, and **`window.process` must stay
  undefined** so the engine takes its browser path.
- On mobile GPUs the game's vertex shaders fail as shipped: a `u_texSlotCoords[256]` uniform table
  exceeds the GLES uniform budget (the frozen boot bar reported on the AYN Thor and **AYN Odin 3**). Also
  needed: an `OES_draw_buffers_indexed` fallback and a barycentric keep-alive for Adreno. alabaster-android
  rewrites these as the files are served, anchored so a game update falls back to the original bytes.
- The engine pauses only on page `blur`; the host must dispatch `blur`/`focus` on pause/resume or audio
  keeps playing in the background.
- GPU cost is high: 720p saturates a flagship GPU; 640x360 or 960x540 is the practical target.
- Saves are files: `Saves/Default/…` with `Saves/Backups`, `Saves/Backups2` rotation
  (`Save_ID_auto.save`, `System.save`). Steam Cloud is listed on the store page; Alabaster Dawn has no
  Steam achievements.

## UI conventions

- **The status bar is the accent gold with dark icons on every launcher screen**: call
  `Screens.goldStatusBar(this)` after `setContentView`. It lays the window out edge to edge and
  draws a gold strip the height of the status bar, because Android 15+ ignores `statusBarColor`.
- Match the launcher: dark gradient (`launcher_bg`), one accent colour (`@color/accent`, dawn gold),
  `text_primary` / `text_secondary`, the serif "ALABASTER / DROID" wordmark.
- **Controller-first:** every control focusable, with the white focus ring (`focus_ring`,
  `focus_ring_outline`); give each screen a sensible initial focus.
- Landscape-first, but nothing may clip on 4:3 handhelds or phones; size dialogs from
  `resources.configuration.screenWidthDp/screenHeightDp` (see `QrCode.fit`).
- UI text is plain and direct; say what happened and what to do next.
