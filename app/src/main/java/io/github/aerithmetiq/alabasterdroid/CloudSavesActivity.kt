package io.github.aerithmetiq.alabasterdroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.aerithmetiq.alabasterdroid.steam.CloudSaves
import io.github.aerithmetiq.alabasterdroid.steam.CloudSync
import io.github.aerithmetiq.alabasterdroid.steam.LoginStore
import io.github.aerithmetiq.alabasterdroid.steam.SaveAction
import io.github.aerithmetiq.alabasterdroid.steam.SteamCloudFiles
import io.github.aerithmetiq.alabasterdroid.steam.SteamGame
import io.github.aerithmetiq.alabasterdroid.steam.SteamProbe
import io.github.aerithmetiq.alabasterdroid.steam.SteamSession
import java.time.Instant

/**
 * Steam Cloud saves: the switch that lets this device upload its saves, and a **check** that shows,
 * file by file, what a sync would do. The check is a dry run: it reads Steam Cloud and this device's
 * save folder and changes neither. Its report holds file names, sizes and times only.
 */
class CloudSavesActivity : AppCompatActivity() {

    @Volatile private var session: SteamSession? = null
    @Volatile private var leaving = false
    private lateinit var status: TextView
    private lateinit var report: TextView
    private var text: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        status = Screens.body(ctx, "", topDp = 12)
        report = Screens.mono(ctx, "")
        val uploads = CheckBox(ctx).apply {
            text = "Upload this device's saves to Steam Cloud"
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
            isChecked = GameFiles.cloudUploads(ctx)
            setOnCheckedChangeListener { _, on ->
                GameFiles.setCloudUploads(ctx, on)
                status.text = if (on) "Uploads are on. Saves you change here are sent to Steam Cloud when you leave the game."
                else "Uploads are off. Nothing on this device is sent to Steam Cloud."
            }
        }
        val check = Screens.primary(ctx, "Check now (changes nothing)") { start() }
        val share = Screens.outlined(ctx, "Share") { share() }
        val back = Screens.outlined(ctx, "Back") { finish() }
        setContentView(Screens.page(ctx) {
            addView(Screens.title(ctx, "Steam Cloud saves"))
            addView(
                Screens.body(
                    ctx,
                    "PLAY downloads your newer saves from Steam Cloud first. With uploads on, the saves you " +
                        "change here are sent to Steam Cloud when you leave the game, so your PC picks them up. " +
                        "When both sides changed, you are asked which to keep. Local saves that get replaced are " +
                        "copied to the SaveBackups folder first, and nothing is ever deleted from Steam Cloud."
                )
            )
            addView(uploads)
            addView(
                Screens.body(
                    ctx,
                    "Check first: the report below lists every save file and what a sync would do with it, " +
                        "without doing it.",
                    topDp = 4,
                )
            )
            addView(Screens.row(ctx, 12, check, share, back))
            addView(status)
            addView(report)
        })
        Screens.goldStatusBar(this)
        check.post { check.requestFocus() }
    }

    override fun onDestroy() {
        leaving = true
        session?.let { s -> Thread({ s.close() }, "steam-close").start() }
        super.onDestroy()
    }

    private fun start() {
        if (session != null) return
        val login = LoginStore(this).load()
        if (login == null) {
            status.text = "Sign in on the launcher first; the check uses your saved login."
            return
        }
        status.text = "Connecting to Steam…"
        Thread({
            val s = SteamSession()
            session = s
            try {
                s.connect()
                ui { status.text = "Logging on…" }
                s.logOn(login)
                ui { status.text = "Comparing this device's saves with Steam Cloud…" }
                val sync = CloudSync(
                    SteamCloudFiles(s, SteamGame.ALABASTER_DAWN.appId),
                    GameFiles.saves(this), GameFiles.syncBase(this), GameFiles.saveBackups(this),
                )
                val steps = sync.check().steps
                val on = GameFiles.cloudUploads(this)
                val lines = header(on) + "" + CloudSaves.report(steps, on)
                val result = lines.joinToString("\n")
                ui {
                    text = result
                    report.text = result
                    status.text = "Done. Nothing was changed, here or on Steam. " +
                        "${CloudSaves.count(steps, SaveAction.DOWNLOAD)} to download, " +
                        "${CloudSaves.count(steps, SaveAction.UPLOAD)} changed here, " +
                        "${CloudSaves.count(steps, SaveAction.CONFLICT)} in conflict."
                }
            } catch (e: Throwable) {
                if (!leaving) {
                    Log.w("AlabasterDroid", "cloud check failed", e)
                    ui { status.text = "Stopped: ${SteamProbe.describe(e)}" }
                }
            } finally {
                s.close()
                session = null
            }
        }, "cloud-check").start()
    }

    private fun header(uploads: Boolean): List<String> = listOf(
        "Alabaster Droid Steam Cloud save check (dry run, nothing changed), ${Instant.now()}",
        "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "app: ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"}",
        "uploads: ${if (uploads) "on" else "off"}",
    )

    private fun share() {
        val body = text ?: run {
            status.text = "Nothing to share yet. Press Check now first."
            return
        }
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Alabaster Droid Steam Cloud save check")
                        putExtra(Intent.EXTRA_TEXT, body)
                    },
                    "Share the report"
                )
            )
        } catch (e: ActivityNotFoundException) {
            status.text = "No app to share with; select the text and copy it instead."
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }
}
