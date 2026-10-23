# AlabasterDawnBox

Play **Alabaster Dawn** on Android. Sign in with Steam, download the copy your account owns, and keep
your saves in step with **Steam Cloud**, so you can carry a save between your PC and your handheld.
Made for controller-first handhelds and phones; tested on a Retroid Pocket Nova.

> **Unofficial fan project.** Not affiliated with or endorsed by Radical Fish Games or Valve. The app
> contains **no game files and no game art**: you need your own copy of Alabaster Dawn on Steam, and
> the game is downloaded from Steam to your device.

## Install

1. Download `AlabasterDawnBox-1.0.0.apk` from the [latest release](https://github.com/AerithMetiq/AlabasterDawnBox/releases/latest) and open it
   on the device (allow installs from your browser or file manager when Android asks).
2. Open AlabasterDawnBox and sign in to Steam: username and password (Steam Guard by app approval or
   code), or **Sign in with QR code** and scan it with the Steam Mobile app.
3. Press **DOWNLOAD**. About 340 MiB comes from Steam; the download carries on in the background with
   a notification, and can be cancelled.
4. Press **PLAY**.

Needs Android 8.0 or newer. Alabaster Dawn is demanding: a recent handheld or phone with a strong GPU
is the target (see [Performance](#performance)).

## What it does

**Steam**
- Signs in with Steam Guard or a QR code that fits any screen, 4:3 included. Your password goes to
  Steam and is never stored; the login token Steam returns is kept encrypted with a key held in the
  Android Keystore.
- Shows your Steam name and avatar, with a green dot while Steam can be reached.
- Downloads only the web game Alabaster Dawn is built from (its `terra/` folder), checks every file
  against Steam's checksums, and offers **UPDATE** when Steam has a newer build ("Update now" or
  "Play this version"). Settings can verify or delete the game files.
- **Playing status** (off by default, Settings → Steam): shows you as playing Alabaster Dawn on
  Steam, and Steam counts the play time.
- **Steam diagnostics** (Settings): a read-only report of the game's depots, its Steam Cloud rules,
  your Cloud files and achievements, which you can share. It leaves out your account name.

**Steam Cloud saves**
- PLAY first brings down the saves that are newer in Steam Cloud, then starts the game. Coming back
  from the game syncs again.
- **Uploads are off until you turn them on** (Settings → Steam Cloud saves). Until then your changes
  stay on the device and are counted under PLAY. "Check now" there lists what a sync would do with
  each file, without doing it.
- Each file is compared with the last version both sides agreed on, so only the side that changed
  wins. If both changed, you choose which to keep.
- Nothing is lost: a save about to be replaced on the device is copied to a backup first, and before
  an upload replaces a file in Steam Cloud, Steam's copy is kept on the device too. Settings → Steam
  Cloud saves → **Restore a backup** puts any of them back. The app never deletes a file from Steam
  Cloud.
- **Play offline** (under PLAY) skips Steam altogether. If Steam cannot be reached, the game starts
  with the saves on the device.
- Under PLAY: when you last played, when the saves were last in step with Steam Cloud, and how many
  have changed since.

**Playing**
- Controllers work as they do on PC. **Back** (or B in the launcher) pauses the game and opens the
  side menu: touch controls on or off, hide them while a controller is in use, dynamic sticks (touch
  anywhere), an FPS / battery / temperature readout, a **frame-rate limit** (30, 45, 60, and 90 or 120
  on screens that have them), Troubleshoot and Exit. Holding a direction scrolls the menu.
- **Touch controls** for phones, with a layout editor, a transparency slider, and controls that step
  aside during cutscenes.
- **Vibration** when the game rumbles the controller, following the game's own rumble option.
- **Resolution** (Settings → Game): the game's own render resolution, set before it starts.
- The game's settings are also in the launcher's Settings → Game, so you can set them before playing.

**Launcher**
- Every control works with a D-pad and shows a clear focus ring. B goes back on every screen.
- Leaving with B returns to whatever opened the app: the home screen, or a frontend such as ES-DE.

## Achievements

Alabaster Dawn is in Early Access and **has no Steam achievements yet**. AlabasterDawnBox already
carries the support for them (the same code that unlocks CrossCode's achievements in
[CrossCodeBox](https://github.com/AerithMetiq/CrossCodeBox)), and it will be switched on in an update
once Alabaster Dawn has achievements on Steam.

## How it compares

### With CrossCodeBox

[CrossCodeBox](https://github.com/AerithMetiq/CrossCodeBox) is the same launcher for CrossCode, built
from the same code; the two are kept in step (`PARITY.md` lists every feature in both). Everything
above works the same in both, except:

- **Only in CrossCodeBox:** Steam achievements that unlock (Alabaster Dawn has none yet, see
  [Achievements](#achievements)); the DLC your account owns is downloaded with the game; mods
  (CCLoader3 and a browser for the community's mod database); a touch option that fires L1/L2 only on
  a fresh tap, for CrossCode's dash; a fix that spaces CrossCode's pixel font right.
- **Only in AlabasterDawnBox:** the **Resolution** setting, and the shader fixes mobile GPUs need for
  Alabaster Dawn's lighting and fog (CrossCode draws on a 2D canvas and needs neither).
- **Different because the games differ:** vibration follows Alabaster Dawn's controller rumble, and
  CrossCode's screen shakes. Touch controls step aside in Alabaster Dawn's cutscenes, and in
  CrossCode's cutscenes, conversations and Direct Links. The touch stick's "always full tilt" is off
  by default here (Alabaster Dawn moves by how far the stick is tilted) and on in CrossCodeBox
  (CrossCode walks below 80 % tilt).

### With alabaster-android

[alabaster-android](https://github.com/moronigranja/alabaster-android) runs Alabaster Dawn and
CrossCode in one app, from a copy of the game you copy to the device and pick with Android's file
picker. AlabasterDawnBox's game host is its code (from commit `f1cedd5`, see `NOTICE.md`). On top of
it, AlabasterDawnBox adds:

- **Steam:** sign-in, the download from Steam with updates and verifying, Steam Cloud saves with
  backups and restore, the playing status, and achievement support for when the game has them.
- **A launcher:** controller-first, with the game's settings and the Resolution before it starts, and
  Back returning to whatever opened the app.
- **Changes to the game host:**
  - On a device whose WebView is 32-bit (the Retroid Pocket Nova's), the game runs in the app's own
    64-bit process. On the Nova this took the game from 45-55 fps to a steady 60 at 1080p, and
    lifted the 32-bit memory ceiling the game ran into after a few minutes of play.
  - Less work per frame and a faster start: the side menu's settings are read twice a second instead
    of every frame, the controller's state is parsed only when it changes, `bundle.js` is edited as
    bytes, and the files that need no edit are streamed.
  - Frame-rate limits of 30, 45, 60, 90 and 120 (alabaster-android: 20, 30, 40, 45, 60), only those
    the screen can show, with the screen's refresh rate picked to match. A limit the refresh rate
    does not divide (45 on 60 Hz) averages out instead of dropping to the next one down.
  - The side menu pauses the game and is driven by the controller; a held direction repeats.
  - Back opens the side menu on Android 13, where it did nothing.
  - The lighting, fog and combine shaders are also served at high precision: at medium precision
    they band into horizontal streaks on Adreno GPUs.
  - Touch controls: a switch that removes the whole overlay, a left side that mirrors the right, a
    transparency slider, hiding in cutscenes, and vibration from the game's rumble.

alabaster-android has things this app does not (at the time of writing): one app for both games; it
runs a copy you put on the device yourself, with no Steam sign-in; its screens in Spanish and
Brazilian Portuguese; home-screen shortcuts for each game; and a controller fix for the games' PC
builds.

### With CrossDroid

[CrossDroid](https://github.com/dain98/CrossDroid) by dain98 is a Steam launcher for CrossCode. This
app follows its launcher design (sign in to Steam, download the game, bring the save down before
playing and up after); none of its code is used. CrossDroid is for CrossCode only, so its closer
comparison is with CrossCodeBox; [CrossCodeBox's README](https://github.com/AerithMetiq/CrossCodeBox#with-crossdroid)
has it.

## Performance

Alabaster Dawn draws its lighting and fog on the GPU, and even 720p keeps a flagship handheld GPU busy.

- **Resolution** is the main dial: 1280x720 or 960x540 suit most handhelds. 1920x1080 runs on a
  Retroid Pocket Nova at close to 60 fps with the GPU at full load and running hot.
- The **frame-rate limit** saves heat and battery. Above 60 fps the game does draw new frames, so 90
  or 120 is smoother on a screen that has those modes, not wasted.
- Some devices, the Nova among them, ship a 32-bit WebView, which starves the game of CPU and memory.
  The app detects that and runs the game in its own 64-bit process instead; Troubleshoot's record
  says which happened.

## Where things are

The game, your saves and their backups are in
`Android/data/io.github.aerithmetiq.alabasterdawnbox/files/AlabasterDawn/` (saves under
`userdata/Saves/`). You can reach that folder over USB. **Uninstalling deletes it**, so turn uploads on
and sync, or copy the saves, before you uninstall.

## Troubleshooting

- **The game does not start or looks wrong:** in the game, Back → Troubleshoot shows a record of the
  start (device, WebView, shader fixes, errors) and can share it.
- **Steam problems:** Settings → Steam diagnostics. The report holds no account name, so it can be
  shared as it is.
- **"Couldn't reach Steam":** you can still play with the saves on the device; they sync the next time
  Steam answers.

## Building

Requirements: Android Studio, or the Android SDK with platform 36, and JDK 17 or newer.

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # signed when keystore.properties names a key (see below)
```

Release builds are signed with the key that `keystore.properties` at the repository root names
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); the file is never committed, and without it
the release APK is unsigned. A debug build and a release build cannot be installed over each other.
`tools/parity.py` compares the code shared with CrossCodeBox when both repositories are checked out
side by side.

## Credits

- **Written by Claude** (Anthropic's AI model, Claude Opus 5.5). **Reviewed and tested by
  AerithMetiq.** Commits name Claude as the author.
- [RadicalFish Ports / alabaster-android](https://github.com/moronigranja/alabaster-android) by Moroni
  Granja (MIT): the research and the code that make Alabaster Dawn run in an Android WebView. The game
  host in this app is its code; see `NOTICE.md`.
- [CrossDroid](https://github.com/dain98/CrossDroid) by dain98: the launcher design this follows. None
  of its code is used.
- [JavaSteam](https://github.com/Longi94/JavaSteam): the Steam client library.
- **Alabaster Dawn** © [Radical Fish Games](https://www.radicalfishgames.com/). Go buy it.

## License

MIT, see `LICENSE`. Third-party notices are in `NOTICE.md`.
