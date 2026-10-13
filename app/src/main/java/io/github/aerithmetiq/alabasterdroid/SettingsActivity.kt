package io.github.aerithmetiq.alabasterdroid

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import io.github.aerithmetiq.alabasterdroid.host.FpsLimit
import io.github.aerithmetiq.alabasterdroid.host.GameSettings
import io.github.aerithmetiq.alabasterdroid.host.LogFile
import io.github.aerithmetiq.alabasterdroid.host.ViewAlign
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ScrollView

/**
 * Settings: the game's own settings (the same values its in-game menu changes, see [GameSettings]),
 * Steam Cloud saves, the Steam diagnostics, the open-source notices and the version.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        setContentView(Screens.page(ctx) {
            addView(Screens.title(ctx, "Settings"))
            gameSettings(this)

            addView(Screens.body(ctx, "Steam", topDp = 24).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
            addView(CheckBox(ctx).apply {
                text = "Show me as playing Alabaster Dawn on Steam"
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
                isChecked = GameFiles.playingStatus(ctx)
                setOnCheckedChangeListener { _, on -> GameFiles.setPlayingStatus(ctx, on) }
            })
            addView(
                Screens.body(
                    ctx,
                    "Off: Steam is not told when you play. On: while the game runs, your friends see you " +
                        "online and in Alabaster Dawn, and Steam counts the play time. Not used with Play offline.",
                    topDp = 0,
                )
            )

            addView(Screens.body(ctx, "Steam Cloud saves", topDp = 24).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
            addView(
                Screens.body(
                    ctx,
                    "Turn uploads to Steam Cloud on or off, and check what a sync would do with each save " +
                        "file before it does anything."
                )
            )
            val cloud = Screens.outlined(ctx, "Open Steam Cloud saves") {
                startActivity(Intent(ctx, CloudSavesActivity::class.java))
            }
            addView(Screens.row(ctx, 10, cloud))

            addView(Screens.body(ctx, "Steam diagnostics", topDp = 24).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
            addView(
                Screens.body(
                    ctx,
                    "A read-only report of what Steam says about Alabaster Dawn: its depots (where the game " +
                        "files are), its Steam Cloud rules and your Steam Cloud files. Changes nothing."
                )
            )
            val probe = Screens.outlined(ctx, "Open Steam diagnostics") {
                startActivity(Intent(ctx, SteamProbeActivity::class.java))
            }
            addView(Screens.row(ctx, 10, probe))

            addView(Screens.body(ctx, "About", topDp = 24).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
            addView(
                Screens.body(
                    ctx,
                    "Alabaster Droid ${versionName()}. An unofficial launcher: you play your own copy of " +
                        "Alabaster Dawn from Steam. Not affiliated with Radical Fish Games or Valve. " +
                        "Written by Claude (Anthropic); reviewed by AerithMetiq."
                )
            )
            val notices = Screens.outlined(ctx, "Open-source notices") { showNotices() }
            val back = Screens.outlined(ctx, "Back") { finish() }
            addView(Screens.row(ctx, 10, notices, back))
            /* Controller focus on the first button once the page is laid out. */
        })
        Screens.goldStatusBar(this)
    }

    /** The game's settings: each control writes straight to the file the game reads when it starts. */
    private fun gameSettings(page: LinearLayout) {
        val ctx = this
        val prefs = GameSettings.prefs(ctx)
        val choices = FpsLimit.choicesFor(GameSettings.screenRefreshRates(this))
        page.addView(Screens.body(ctx, "Game", topDp = 20).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
        page.addView(Screens.body(ctx, "The same settings as the menu inside the game (Back while playing). They apply the next time the game starts."))

        fun toggle(label: String, key: String, default: Boolean, also: (SharedPreferences.Editor) -> Unit = {}) =
            CheckBox(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).also(also).commit() }
                page.addView(this)
            }

        val first = toggle("Touch controls on screen", GameSettings.KEY_TOUCH_CONTROLS, true)
        toggle("Hide the touch controls while a controller is used", GameSettings.KEY_HIDE_EXTERNAL_INPUT, true)
        toggle("Dynamic sticks (touch anywhere)", GameSettings.KEY_DYNAMIC_STICKS, true)
        toggle("Show FPS / battery / temperature", GameSettings.KEY_STATS, false)
        /* A choice made here ends the log's automatic switch-off, as a tap in the game's menu does. */
        toggle("Keep a log with the saves", LogFile.PREF_KEY, true) { it.putBoolean(LogFile.PREF_SET_KEY, true) }

        fun capLabel(cap: Int) = "Frame rate limit: " + if (cap == FpsLimit.OFF) "Off" else "$cap fps"
        lateinit var fps: com.google.android.material.button.MaterialButton
        fps = Screens.outlined(ctx, capLabel(GameSettings.fpsCap(prefs, choices))) {
            val cap = GameSettings.next(GameSettings.fpsCap(prefs, choices), choices)
            prefs.edit().putBoolean(GameSettings.KEY_LIMIT_FPS, cap != FpsLimit.OFF).apply {
                if (cap != FpsLimit.OFF) putInt(GameSettings.KEY_FPS_LIMIT, cap)
            }.commit()
            fps.text = capLabel(cap)
        }
        fun alignLabel(a: ViewAlign) = "Game position: " + a.wire.replaceFirstChar { it.uppercase() }
        lateinit var align: com.google.android.material.button.MaterialButton
        align = Screens.outlined(ctx, alignLabel(ViewAlign.fromWire(prefs.getString(GameSettings.KEY_VIEW_ALIGN, null)))) {
            val all = ViewAlign.entries
            val next = all[(all.indexOf(ViewAlign.fromWire(prefs.getString(GameSettings.KEY_VIEW_ALIGN, null))) + 1) % all.size]
            prefs.edit().putString(GameSettings.KEY_VIEW_ALIGN, next.wire).commit()
            align.text = alignLabel(next)
        }
        fun resolutionLabel() = "Resolution: " + GameSettings.resolutionLabel(
            prefs.getInt(GameSettings.KEY_RESOLUTION_NEXT, -1), prefs.getString(GameSettings.KEY_RESOLUTION_LAST, null)
        )
        lateinit var resolution: com.google.android.material.button.MaterialButton
        resolution = Screens.outlined(ctx, resolutionLabel()) {
            val next = GameSettings.nextResolution(
                prefs.getInt(GameSettings.KEY_RESOLUTION_NEXT, -1), prefs.getString(GameSettings.KEY_RESOLUTION_LAST, null)
            )
            prefs.edit().putInt(GameSettings.KEY_RESOLUTION_NEXT, next).commit()
            resolution.text = resolutionLabel()
        }
        page.addView(Screens.row(ctx, 8, fps, align))
        page.addView(Screens.row(ctx, 8, resolution))
        page.addView(Screens.body(ctx, "Lower the resolution if the game does not hold its frame rate: 1280x720 or 960x540 on most handhelds.", topDp = 4))
        gameFiles(page)
        /* Controller focus on the first control once the page is laid out. */
        first.post { first.requestFocus() }
    }

    /** The downloaded game: check it against Steam, or delete it. */
    private fun gameFiles(page: LinearLayout) {
        val ctx = this
        page.addView(Screens.body(ctx, "Game files", topDp = 24).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
        val about = Screens.body(ctx, "")
        page.addView(about)
        fun describe() {
            if (!java.io.File(GameFiles.root(ctx), "terra").exists()) {
                about.text = "Alabaster Dawn is not downloaded."
                return
            }
            about.text = "Measuring…"
            Thread({
                val mib = GameFiles.gameBytes(ctx) / 1048576
                val build = GameFiles.installedBuild(ctx)
                runOnUiThread {
                    about.text = "Alabaster Dawn takes $mib MiB on this device" +
                        (if (build != null) ", build $build." else "; the download is not complete.") +
                        " Saves are kept apart and are not touched by either button."
                }
            }, "game-size").start()
        }
        describe()
        val verify = Screens.outlined(ctx, "Verify game files") {
            if (GameDownloadState.running) return@outlined
            /* The download checks every file against Steam's list and fetches what is missing or
             * different, so verifying is running it again. The launcher shows its progress. */
            GameDownloadService.start(ctx)
            finish()
        }
        val delete = Screens.outlined(ctx, "Delete game files") {
            if (GameDownloadState.running) {
                about.text = "A download is running. Cancel it on the launcher first."
                return@outlined
            }
            AlertDialog.Builder(ctx)
                .setTitle("Delete the game files?")
                .setMessage("This frees the storage Alabaster Dawn takes. Your saves, their backups and your settings stay. You can download the game again at any time.")
                .setPositiveButton("Delete") { _, _ ->
                    about.text = "Deleting…"
                    Thread({
                        GameFiles.deleteGame(ctx)
                        runOnUiThread { describe() }
                    }, "game-delete").start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        page.addView(Screens.row(ctx, 8, verify, delete))
    }

    private fun showNotices() {
        val text = listOf("NOTICE.md", "LICENSE").joinToString("\n\n" + "-".repeat(60) + "\n\n") { name ->
            runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("($name missing)")
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply {
                val pad = Screens.dp(this@SettingsActivity, 20)
                setPadding(pad, pad, pad, 0)
                addView(Screens.mono(this@SettingsActivity, text))
            })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
}
