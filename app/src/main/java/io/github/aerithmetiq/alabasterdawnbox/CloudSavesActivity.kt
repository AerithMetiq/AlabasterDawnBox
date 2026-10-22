package io.github.aerithmetiq.alabasterdawnbox

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.aerithmetiq.alabasterdawnbox.steam.SaveBackups
import io.github.aerithmetiq.alabasterdawnbox.steam.CloudSaves
import io.github.aerithmetiq.alabasterdawnbox.steam.CloudSync
import io.github.aerithmetiq.alabasterdawnbox.steam.LoginStore
import io.github.aerithmetiq.alabasterdawnbox.steam.SaveAction
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamCloudFiles
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamGame
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamProbe
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamSession
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
        val restore = Screens.outlined(ctx, "Restore a backup") { chooseBackup() }
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
            addView(
                Screens.body(
                    ctx,
                    "Backups: before a sync (or a restore) replaces save files on this device, it copies " +
                        "them to SaveBackups. Before an upload replaces files in Steam Cloud, it downloads Steam's " +
                        "copies first (listed as \"from Steam Cloud\"). Restoring puts the files back on this " +
                        "device; nothing is sent to Steam until the next sync.",
                    topDp = 20,
                )
            )
            addView(Screens.row(ctx, 8, restore))
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
            try {
                val s = SteamSession.open(SteamSession.Purpose.SAVES, login) { session = it }
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
                    Log.w("AlabasterDawnBox", "cloud check failed", e)
                    ui { status.text = "Stopped: ${SteamProbe.describe(e)}" }
                }
            } finally {
                session?.close()
                session = null
            }
        }, "cloud-check").start()
    }

    /** Lists the local save backups, newest first, and puts the chosen one back after a confirmation. */
    private fun chooseBackup() {
        val saves = GameFiles.saves(this)
        val folder = GameFiles.saveBackups(this)
        val backups = SaveBackups.list(folder)
        if (backups.isEmpty()) {
            status.text = "There are no save backups on this device yet."
            return
        }
        val labels = backups.map { "${it.label}  ·  ${it.files.size} ${if (it.files.size == 1) "file" else "files"}" }
        AlertDialog.Builder(this)
            .setTitle("Restore which backup?")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = backups[which]
                AlertDialog.Builder(this)
                    .setTitle("Restore the backup of ${chosen.label}?")
                    .setMessage(
                        "These save files are put back:\n\n" + chosen.files.joinToString("\n") +
                            "\n\nThe files they replace are backed up first, so this can be undone. " +
                            "At the next PLAY the sync treats them as changed on this device."
                    )
                    .setPositiveButton("Restore") { _, _ ->
                        val n = runCatching { SaveBackups.restore(saves, folder, chosen.name) }
                        status.text = n.fold(
                            { "Restored $it save ${if (it == 1) "file" else "files"} from ${chosen.label}." },
                            { "Could not restore: ${SteamProbe.describe(it)}" },
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun header(uploads: Boolean): List<String> = listOf(
        "AlabasterDawnBox Steam Cloud save check (dry run, nothing changed), ${Instant.now()}",
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
                        putExtra(Intent.EXTRA_SUBJECT, "AlabasterDawnBox Steam Cloud save check")
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
