package io.github.aerithmetiq.alabasterdroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.aerithmetiq.alabasterdroid.steam.LoginStore
import io.github.aerithmetiq.alabasterdroid.steam.SteamGame
import io.github.aerithmetiq.alabasterdroid.steam.SteamProbe
import io.github.aerithmetiq.alabasterdroid.steam.SteamSession
import java.time.Instant

/**
 * Steam diagnostics: logs on with the saved login and shows what [SteamProbe] learns, with Share.
 * Read-only. CrossCode is included as a known reference: its Steam Cloud layout is already understood,
 * so its section shows whether the probe reads Steam correctly.
 */
class SteamProbeActivity : AppCompatActivity() {

    @Volatile private var session: SteamSession? = null
    @Volatile private var leaving = false
    private lateinit var status: TextView
    private lateinit var report: TextView
    private var text: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        status = Screens.body(ctx, "Signing in with your saved login…", topDp = 12)
        report = Screens.mono(ctx, "")
        val run = Screens.primary(ctx, "Run again") { start() }
        val share = Screens.outlined(ctx, "Share") { share() }
        val back = Screens.outlined(ctx, "Back") { finish() }
        setContentView(Screens.page(ctx) {
            addView(Screens.title(ctx, "Steam diagnostics"))
            addView(status)
            addView(Screens.row(ctx, 12, run, share, back))
            addView(report)
        })
        back.post { back.requestFocus() }
        start()
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
            status.text = "Sign in on the launcher first; diagnostics use your saved login."
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
                val result = SteamProbe(s).run(SteamGame.entries, header()) { line -> ui { status.text = line } }
                ui {
                    text = result
                    report.text = result
                    status.text = "Done. Nothing was changed on Steam."
                }
            } catch (e: Throwable) {
                if (!leaving) {
                    Log.w("AlabasterDroid", "diagnostics failed", e)
                    ui { status.text = "Stopped: ${SteamProbe.describe(e)}" }
                }
            } finally {
                s.close()
                session = null
            }
        }, "steam-probe").start()
    }

    private fun header(): List<String> = listOf(
        "Alabaster Droid Steam diagnostics (read-only), ${Instant.now()}",
        "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "app: ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"}",
    )

    private fun share() {
        val body = text ?: run {
            status.text = "Nothing to share yet."
            return
        }
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Alabaster Droid Steam diagnostics")
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
