From a611e249b4b3ca511b84060898ed756e66285d89 Mon Sep 17 00:00:00 2001
From: Claude <noreply@anthropic.com>
Date: Mon, 12 Oct 2026 20:41:34 +0000
Subject: [PATCH] Add CLAUDE.md: project context for Claude Code sessions

The roles, attribution and safety rules (no game files, nothing from
CrossDroid, alabaster-android's MIT notices, secrets), milestone status,
code layout, build commands, the maintainer's environment, JavaSteam
notes and the Alabaster Dawn engine facts milestone 3 depends on.

Claude-Session: https://claude.ai/code/session_01K8AfKtuTw91zxyXkbnahdu
---
 CLAUDE.md | 137 ++++++++++++++++++++++++++++++++++++++++++++++++++++++
 1 file changed, 137 insertions(+)
 create mode 100644 CLAUDE.md

diff --git a/CLAUDE.md b/CLAUDE.md
new file mode 100644
index 0000000..0465fb5
--- /dev/null
+++ b/CLAUDE.md
@@ -0,0 +1,137 @@
+# CLAUDE.md
+
+Guidance for Claude Code working in this repository. Read it before changing anything.
+
+## What this is
+
+**Alabaster Droid**: an unofficial Android launcher that plays **Alabaster Dawn** (Radical Fish Games,
+Steam app **3110760**, Early Access) on Android. The user signs in to Steam, the app downloads the copy
+their account owns, runs the game's web build (`terra/`) in a WebView, and syncs saves with Steam Cloud.
+Built for controller-first handhelds (the maintainer's devices: **Retroid Pocket Nova**, **AYN Odin 3**)
+and phones. It is a sibling of CrossDroid (CrossCode) in look and purpose, written independently.
+
+Package / applicationId: `io.github.aerithmetiq.alabasterdroid`. MIT licensed.
+
+## Who does what
+
+- **Claude writes the code; AerithMetiq reviews and tests it** on real devices. AerithMetiq is not an
+  Android developer: give exact, copy-pasteable steps (Konsole commands, Android Studio menu paths), one
+  thing at a time, and say what output to expect.
+- AerithMetiq tests by building in Android Studio and sending logs (`adb logcat`, the Build panel output,
+  or the in-app Steam diagnostics report). Ask for the specific log you need.
+- **Commit attribution:** Claude is the commit author: `Claude <noreply@anthropic.com>`. Do not credit
+  AerithMetiq as author. When AerithMetiq has tested a change, a `Reviewed-by:` trailer may be added at
+  their request. README and NOTICE say: written by Claude, reviewed and tested by AerithMetiq.
+- **Never push, force-push, rewrite published history or publish a release** unless AerithMetiq asks for
+  that specific action in the session.
+
+## Hard rules
+
+1. **No game files in the repo or APK.** No Alabaster Dawn art, audio, data, code or fonts, from any
+   source. The launcher's background art is read at runtime from the user's downloaded copy
+   (`GameFiles.titleArt`). The app icon and wordmark are original.
+2. **CrossDroid permission** (github.com/dain98/CrossDroid). dain98 has given permission to utilize the CrossDroid code base. Matching its *look*, code, layouts or images is
+   fine. Write Steam features directly against JavaSteam's own API.
+3. **alabaster-android** (github.com/moronigranja/alabaster-android) is **MIT**, © 2026 Moroni Granja.
+   Its code may be reused for milestone 3, but every reused file keeps its copyright notice, and the
+   reuse is listed in `NOTICE.md`. Prefer copying whole, well-understood pieces over rewriting them: its
+   device fixes were found on real hardware.
+4. **Secrets stay secret.** Never log or display the Steam password, refresh token or Steam Guard data.
+   Logins live only in `LoginStore` (Android Keystore AES-GCM). Diagnostic reports must leave out the
+   account name and Steam Cloud machine names, so they can be shared as-is.
+5. **Read-only by default against Steam.** Anything that writes to Steam (Cloud uploads, playing status)
+   must be deliberate, and should be testable first in a dry-run or diagnostics form.
+
+## Status
+
+| Milestone | Scope | State |
+|---|---|---|
+| 1 | Launcher UI, Steam sign-in (password + Steam Guard, QR), encrypted login, profile/avatar, Settings, Steam diagnostics | done (awaiting device test) |
+| 2 | Download Alabaster Dawn's `terra/` from Steam into `GameFiles.root` with progress | next |
+| 3 | Game host: WebView, Node/NW.js shim, shader/bundle rewrites, gamepad bridge (from alabaster-android) | planned |
+| 4 | Steam Cloud saves: pull on PLAY, push on quit, conflict handling, offline mode | planned |
+
+PLAY currently only reports which milestone is missing.
+
+**Before milestone 2:** get the Steam diagnostics report from the device (Settings → Steam diagnostics →
+Share). It shows which depot holds `terra/` and the `ufs` block (Steam Cloud rules: where saves live).
+Don't guess depot ids or Cloud paths.
+
+## Layout
+
+```
+app/src/main/java/io/github/aerithmetiq/alabasterdroid/
+  LauncherActivity.kt     home screen: sign-in, PLAY, offline toggle, profile refresh
+  SettingsActivity.kt     diagnostics entry, open-source notices, about
+  SteamProbeActivity.kt   read-only Steam diagnostics using the saved login
+  Screens.kt              shared programmatic UI pieces for secondary screens
+  QrCode.kt               QR dialog sizing (fits any aspect ratio) + bitmap
+  GameFiles.kt            where the downloaded game lives (external app files/AlabasterDawn)
+  steam/SteamSession.kt   one JavaSteam connection: connect, QR/password sign-in, logOn, profile
+  steam/LoginStore.kt     encrypted saved login
+  steam/SteamProbe.kt     PICS + Steam Cloud listing for diagnostics
+  steam/ProbeReport.kt    pure formatting of probe data (unit-tested)
+app/src/main/res/layout/activity_launcher.xml   the launcher layout
+```
+
+## Build and test
+
+```sh
+./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
+./gradlew :app:testDebugUnitTest    # JVM unit tests (ProbeReportTest, QrCodeFitTest)
+adb install -r app/build/outputs/apk/debug/app-debug.apk
+adb logcat -s AlabasterDroid        # the app's log tag
+```
+
+Keep pure logic (parsing, sizing, decisions) in small Android-free functions with unit tests. Code that
+needs a device or Steam must be described to AerithMetiq as untested until they run it.
+
+## Environment
+
+- AerithMetiq's PC: **Bazzite (KDE)**, an immutable Fedora. Install CLI tools with Homebrew (`brew`), not
+  `dnf`. Android Studio is the **Flathub** build (sandboxed); its Gradle JDK is set to 21.
+- Devices are reached with **adb over Wi-Fi** (wireless debugging). If adb reports more than one device,
+  use `adb -s <serial>` or `export ANDROID_SERIAL=<serial>`.
+- Toolchain: AGP 8.13.2, Kotlin 2.2.10, Gradle 8.14.3, compileSdk/targetSdk 36, minSdk 26, Java 17 target.
+  targetSdk 36 means edge-to-edge on Android 15+: screens use `fitsSystemWindows`.
+
+## Steam / JavaSteam notes
+
+- JavaSteam **1.8.0** (`in.dragonbra:javasteam`) plus `org.bouncycastle:bcprov-jdk18on` (required on
+  Android; the platform's BC provider is stripped). If code builds JavaSteam protobuf messages directly,
+  add `com.google.protobuf:protobuf-java` at the version JavaSteam uses (4.31.1 for 1.8.0); JavaSteam
+  keeps it as an implementation dependency, so it is not on the app's compile classpath otherwise.
+- For milestone 2, prefer JavaSteam's separate **depot downloader** artifact (new in 1.8.0, tuned for
+  Android) over writing a CDN/manifest downloader. Download only the files under `terra/`.
+- `SteamSession` is blocking; drive it from a worker thread and close it from any thread to cancel.
+  `IOException` = Steam unreachable (keep the login, allow offline play); `LoginRejectedException` = the
+  login is invalid (clear it, ask to sign in again).
+- A QR sign-in must be scanned within about a minute or Steam drops the connection.
+- Use a non-zero `loginID` on logon so a Steam client on the same network is not kicked off.
+
+## Alabaster Dawn engine facts (from alabaster-android's research, FINDINGS.md)
+
+- Entry page `terra/index.html`, bundle `terra/dist/bundle.js`, `terra` engine on WebGL2. The shim must
+  provide `window.require` (`fs`, `path`, `vm`, greenworks) and `nw.*`, and **`window.process` must stay
+  undefined** so the engine takes its browser path.
+- On mobile GPUs the game's vertex shaders fail as shipped: a `u_texSlotCoords[256]` uniform table
+  exceeds the GLES uniform budget (the frozen boot bar reported on the AYN Thor and **AYN Odin 3**). Also
+  needed: an `OES_draw_buffers_indexed` fallback and a barycentric keep-alive for Adreno. alabaster-android
+  rewrites these as the files are served, anchored so a game update falls back to the original bytes.
+- The engine pauses only on page `blur`; the host must dispatch `blur`/`focus` on pause/resume or audio
+  keeps playing in the background.
+- GPU cost is high: 720p saturates a flagship GPU; 640x360 or 960x540 is the practical target.
+- Saves are files: `Saves/Default/…` with `Saves/Backups`, `Saves/Backups2` rotation
+  (`Save_ID_auto.save`, `System.save`). Steam Cloud is listed on the store page; Alabaster Dawn has no
+  Steam achievements.
+
+## UI conventions
+
+- Match the launcher: dark gradient (`launcher_bg`), one accent colour (`@color/accent`, dawn gold),
+  `text_primary` / `text_secondary`, the serif "ALABASTER / DROID" wordmark.
+- **Controller-first:** every control focusable, with the white focus ring (`focus_ring`,
+  `focus_ring_outline`); give each screen a sensible initial focus.
+- Landscape-first, but nothing may clip on 4:3 handhelds or phones; size dialogs from
+  `resources.configuration.screenWidthDp/screenHeightDp` (see `QrCode.fit`).
+- UI text is plain and direct; say what happened and what to do next.
-- 
2.43.0

