# Notices

## This project

Alabaster Droid: an unofficial Android launcher for Alabaster Dawn. MIT licensed (see `LICENSE`).

The code was written by Claude (Anthropic's AI model, Claude Opus 5.5, in Claude Code) and reviewed and
tested by AerithMetiq. Commits name Claude as the author and AerithMetiq as the committer.

This file and `LICENSE` are copied into the APK, so a distributed build carries its notices (Settings →
Open-source notices).

## The game is not part of this project

**Alabaster Dawn** (its artwork, audio, text, fonts, data, code and the `terra` engine) is © Radical
Fish Games. **None of it is in this repository or in the APK.** The app downloads the copy your own
Steam account owns, from Steam, onto your device. The launcher's background art is read from that
downloaded copy at runtime; the app's icon and wordmark are original.

"Alabaster Dawn" is a trademark of its owner, used here descriptively. This is an unofficial,
non-commercial project, not affiliated with, sponsored by or endorsed by Radical Fish Games or Valve.

## Third-party components

* **JavaSteam** (`in.dragonbra:javasteam`, https://github.com/Longi94/JavaSteam): MIT License, © Long
  Tran and contributors. Bundled with its runtime dependencies: **OkHttp** (Apache License 2.0, ©
  Square, Inc.), **Ktor** and **kotlinx.coroutines** (Apache License 2.0, © JetBrains s.r.o.),
  **Protocol Buffers** (BSD 3-Clause, © Google LLC) and **Apache Commons Lang** (Apache License 2.0, ©
  The Apache Software Foundation).
* **Bouncy Castle** (`org.bouncycastle:bcprov-jdk18on`, https://www.bouncycastle.org): the Bouncy
  Castle License (MIT-style), © The Legion of the Bouncy Castle Inc.
* **zstd-jni** (`com.github.luben:zstd-jni`, https://github.com/luben/zstd-jni): BSD 2-Clause License,
  © Luben Karavelov. It contains **Zstandard** (BSD License, © Meta Platforms, Inc.). Unpacks the game
  files Steam sends.
* **XZ for Java** (`org.tukaani:xz`, https://tukaani.org/xz/java.html): BSD Zero Clause License, by
  Lasse Collin and contributors. Unpacks the game files Steam sends.
* **ZXing** (`com.google.zxing:core`, https://github.com/zxing/zxing): Apache License 2.0, © ZXing
  authors. Draws the sign-in QR code.
* **Material Symbols** (the in-game menu's icons, `app/src/main/res/drawable/ic_menu_*.xml`, taken
  from alabaster-android): Apache License 2.0, © Google LLC. https://fonts.google.com/icons
* **AndroidX** (AppCompat, Core, WebKit) and **Material Components for Android**: Apache License 2.0, © The
  Android Open Source Project / Google LLC.
* **Gradle wrapper** (`gradle/wrapper/`, `gradlew*`, used to build): Apache License 2.0, © Gradle, Inc.

Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Code from alabaster-android

The part of this app that runs the game is **RadicalFish Ports / alabaster-android**
(https://github.com/moronigranja/alabaster-android, commit `f1cedd5`): MIT License, Copyright (c) 2026
Moroni Granja. It is the research and code that make Alabaster Dawn run in an Android WebView: the
Node/NW.js shim, the shader and bundle fixes mobile GPUs need, controller input, the on-screen pad,
the in-game menu and the diagnostics record.

Reused, each file carrying that notice at its top:

* `app/src/main/assets/ada-shim.js`, with two changes: the side menu's three settings are read from
  the app twice a second instead of on every frame, which was costing frames, and the frame-rate
  limit keeps a running deadline so a rate the screen cannot divide evenly averages out.
* Everything in `app/src/main/java/io/github/aerithmetiq/alabasterdroid/host/`, and its unit tests in
  `app/src/test/java/io/github/aerithmetiq/alabasterdroid/host/`. Most files differ only in their
  package name. Adapted: `GameActivity.kt` (alabaster-android's `PortActivity` without its folder
  pickers and two-game entry screen), `GameIndex.kt`, `GameAssetHandler.kt` and `FsBridge.kt` (the
  game and saves are plain files here, not folders picked through Android's storage framework), 
  `SideMenuView.kt` (no game-selection row; it can be driven by a controller; a "Touch controls"
  switch), `OnScreenPadView.kt` (that switch), `FpsLimit.kt` and
  its test (the rates are 30/45/60/90/120, limited to what the screen can show) and `ShaderPrecision.kt` (the light, fog and combine
  shaders are served at high precision as well as the GUI's). `GameActivity.kt` also acts on the Back
  key at every Android version, leaves out the per-movement stick log, pauses the game while the
  side menu is open and gives the controller to the menu. Each says so in its header.
* The in-game menu's icons (`ic_menu_*.xml`).

`host/GameSettings.kt`, `host/InProcessRenderer.kt` and their tests are this project's own and are not from alabaster-android.

The MIT License that covers that code:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
> OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## CrossDroid

**CrossDroid** (https://github.com/dain98/CrossDroid) by **dain98** is the Steam launcher for
CrossCode whose design this app's look follows (dark two-column launcher, Steam sign-in, Steam Cloud
saves). **No code or assets are taken from it**; this app is an independent implementation on
JavaSteam's own API.
