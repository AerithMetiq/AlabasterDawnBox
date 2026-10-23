# Parity: AlabasterDawnBox and CrossCodeBox

The two apps share one code base and should behave the same. When a feature lands in one, say here
whether it applies to the other and where it stands. The same file is kept in both repositories.

Shared files are copied with the package name changed (`alabasterdawnbox` / `crosscodebox`), so a
later fix can be carried over file by file. Game-specific hooks (CrossCode's `ig.*`, Alabaster
Dawn's `terra` engine) cannot be copied as they are: each needs its own look at the game's code.

| Feature | AlabasterDawnBox | CrossCodeBox |
|---|---|---|
| Steam sign-in, profile, online dot | yes | yes |
| Background download, update, verify, delete | yes | yes |
| Added depots (`GameDownload.extras`): DLC the account owns | yes (the game has none) | yes: "A New Home" and the ninja skin on the Nova |
| Steam Cloud sync, dry-run check, restore | yes | yes |
| Steam's copies kept before an upload (`-steam` backups) | yes, upload confirmed | yes, upload confirmed |
| Playing status | yes | yes |
| Steam achievements | dormant: the game has none on Steam; plumbing checked | yes, unlock confirmed |
| Vibration from the game | rumble via the pad's `vibrationActuator`; confirmed in play (2026-10-22) | screen shakes via `ig.rumble`; confirmed |
| Touch controls hide in cutscenes | cutscenes (`g_scene.isCutscene()`); not yet seen in one | cutscenes, conversations, Direct Links; confirmed |
| Touch controls transparency | yes | yes, confirmed |
| Touch stick always full tilt | yes, off by default | "always sprint", on by default; reported working |
| L1/L2 only on a fresh tap | not offered (dash is not on L1/L2) | yes (CrossCode's dash); not yet tried in play |
| Font glyph fix (`exactUnscaledDraws`) | not needed (WebGL text, not `ig.Font`) | yes, confirmed |
| Shader rewrites, in-process renderer | yes | renderer yes; shaders not needed (canvas 2D) |
| Mod loader and mod browser | none exists for the game yet | CCLoader3, off by default; mod browser (CCModDB), add a file, remove; community mods loaded on the Nova |
| Launcher: B leaves on the first press | yes | yes |
| Back returns to the app that opened the launcher (`ReturnTo`) | yes, checked | yes, checked |
| Side menu: a held direction repeats (`holdMenuDirection`) | yes, not yet tried | yes, not yet tried |
| Launcher logo cut to its own columns | n/a (the game ships a title-name image) | yes |
| Black page background behind the picture | already black | yes (style.css paints #080808) |
| Verify leaves `<web root>/mods/` alone (`GameDownload.KEPT`) | yes (unused) | yes, checked |
| Achievements wait while the playing status holds the game | yes | yes, checked |
| JavaSteam message router added before connecting (crash fix) | yes | yes |
| `fs.readdir` calls back later, as Node's does | yes (editor tools only) | yes: fixed the double extension load |
