# Alabaster Droid

Play **Alabaster Dawn** on Android: sign in with Steam, download the copy you own, and keep your saves
in sync with **Steam Cloud**, so you can switch between PC and handheld. Built for controller-first
handhelds (tested on a Retroid Pocket Nova and an AYN Odin 3) and phones.

> **Unofficial fan project.** Not affiliated with or endorsed by Radical Fish Games or Valve. It ships
> **no game files**: you need your own copy of Alabaster Dawn on Steam.

## Status

Alabaster Droid is being built in milestones.

| Milestone | What it adds | State |
|---|---|---|
| 1 | Launcher, Steam sign-in (password + Steam Guard, or QR code), profile, Steam diagnostics | done |
| 2 | Download Alabaster Dawn from Steam on first PLAY | done |
| 3 | Run the game: the WebView host, shim and the shader fixes mobile GPUs need | done |
| 4 | Steam Cloud saves: pull on PLAY, upload on quit, conflict handling, offline play | downloads confirmed on a device; uploads not yet |

The main button reads **DOWNLOAD** until the game is on the device, then **PLAY**.

## What it does today

- **Sign in with Steam**: username and password (with Steam Guard by app approval or code), or a QR code
  you scan with the Steam Mobile app. The QR code fits any screen shape, 4:3 included.
- **Your login stays private**: the password is passed to Steam and never stored. The refresh token
  Steam returns is encrypted with a key kept in the Android Keystore.
- **Profile**: your Steam persona name and avatar on the launcher.
- **Controller-first**: every control is reachable with a D-pad, with a clear focus ring.
- **Steam Cloud saves**: PLAY first downloads the saves that are newer in Steam Cloud. Uploading this
  device's saves when you leave the game is **off until you turn it on** (Settings → Steam Cloud saves),
  where a check lists what a sync would do with each file without doing it. When both sides changed
  you are asked which to keep; local saves that get replaced are copied to a backup folder first, and
  nothing is ever deleted from Steam Cloud. "Play offline" skips all of it.
- **Steam diagnostics** (Settings): a read-only report of Alabaster Dawn's depots, Steam Cloud rules and
  your Cloud files, with Share. It changes nothing on Steam.

## Building

Requirements: Android Studio (or the Android SDK with platform 36) and JDK 17 or newer.

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Credits

- **Written by Claude** (Anthropic's AI model, Claude Opus 5.5). **Reviewed and tested by
  AerithMetiq.** Commits name Claude as the author.
- [CrossDroid](https://github.com/dain98/CrossDroid) by dain98: the launcher design this follows, for
  CrossCode. No code was taken from it; see `NOTICE.md`.
- [RadicalFish Ports / alabaster-android](https://github.com/moronigranja/alabaster-android) by Moroni
  Granja (MIT): the research and the code that make Alabaster Dawn run in an Android WebView. The game
  host in this app is its code; see `NOTICE.md`.
- [JavaSteam](https://github.com/Longi94/JavaSteam): the Steam client library.
- **Alabaster Dawn** © [Radical Fish Games](https://www.radicalfishgames.com/). Go buy it.

## License

MIT, see `LICENSE`. Third-party notices are in `NOTICE.md`.
