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
* **ZXing** (`com.google.zxing:core`, https://github.com/zxing/zxing): Apache License 2.0, © ZXing
  authors. Draws the sign-in QR code.
* **AndroidX** (AppCompat, Core) and **Material Components for Android**: Apache License 2.0, © The
  Android Open Source Project / Google LLC.
* **Gradle wrapper** (`gradle/wrapper/`, `gradlew*`, used to build): Apache License 2.0, © Gradle, Inc.

Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

## Prior art

* **RadicalFish Ports / alabaster-android** (https://github.com/moronigranja/alabaster-android): MIT
  License, © 2026 Moroni Granja. The research that makes Alabaster Dawn run in an Android WebView (its
  Node/NW.js shim and the shader fixes mobile GPUs need). When this project includes code from it, that
  code keeps its copyright notice and is listed here.
* **CrossDroid** (https://github.com/dain98/CrossDroid): the Steam-launcher design this app's look
  follows (dark two-column launcher, Steam sign-in, Steam Cloud saves) for CrossCode. CrossDroid declares
  no license, so **no code or assets were taken from it**; this app is an independent implementation on
  JavaSteam's own API.
