package io.github.aerithmetiq.alabasterdroid

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.ScrollView

/** Settings: Steam Cloud saves, the Steam diagnostics, the open-source notices and the version. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        setContentView(Screens.page(ctx) {
            addView(Screens.title(ctx, "Settings"))

            addView(Screens.body(ctx, "Steam Cloud saves", topDp = 20).apply { textSize = 16f; setTextColor(getColor(R.color.text_primary)) })
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
            cloud.post { cloud.requestFocus() }
        })
        Screens.goldStatusBar(this)
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
