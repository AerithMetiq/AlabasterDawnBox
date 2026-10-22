package io.github.aerithmetiq.alabasterdawnbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.aerithmetiq.alabasterdawnbox.steam.GameDownload
import io.github.aerithmetiq.alabasterdawnbox.steam.GameDownloader
import io.github.aerithmetiq.alabasterdawnbox.steam.LoginRejectedException
import io.github.aerithmetiq.alabasterdawnbox.steam.LoginStore
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamGame
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamProbe
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamSession
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What the game download is doing, for whoever is showing it. The download runs in
 * [GameDownloadService], which outlives the launcher's screen; the launcher reads this and listens.
 * Listeners are called on the main thread.
 */
object GameDownloadState {
    @Volatile var running = false
        private set
    /** Null until file data is moving. */
    @Volatile var percent: Int? = null
        private set
    @Volatile var status = ""
        private set
    /** How the last run ended, until someone has shown it ([takeEnd]). */
    @Volatile private var end: String? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)

    fun takeEnd(): String? = end.also { end = null }

    internal fun begin(text: String) {
        running = true
        percent = null
        status = text
        end = null
        changed()
    }

    internal fun update(text: String, percent: Int? = this.percent) {
        status = text
        this.percent = percent
        changed()
    }

    internal fun finish(text: String) {
        running = false
        percent = null
        status = text
        end = text
        changed()
    }

    private fun changed() {
        main.post { listeners.forEach { it() } }
    }
}

/**
 * Downloads, updates or verifies the game in the background: a foreground service with a progress
 * notification, so the download carries on when the launcher is left or the screen turns off. One run
 * does all three jobs, because [GameDownloader] checks every file on disk against Steam's manifest and
 * fetches only what is missing or different.
 */
class GameDownloadService : Service() {

    @Volatile private var downloader: GameDownloader? = null
    @Volatile private var session: SteamSession? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel()
            if (!GameDownloadState.running) stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification("Connecting to Steam…", null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!GameDownloadState.running) run()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun cancel() {
        cancelled = true
        downloader?.cancel()
        session?.let { s -> Thread({ s.close() }, "steam-close").start() }
    }

    private fun run() {
        val store = LoginStore(this)
        val login = store.load()
        if (login == null) {
            GameDownloadState.finish("Sign in to download Alabaster Dawn.")
            stopSelf()
            return
        }
        cancelled = false
        GameDownloadState.begin("Connecting to Steam…")
        acquireLocks()
        Thread({
            var end = "Alabaster Dawn is downloaded and checked."
            try {
                val s = SteamSession.open(SteamSession.Purpose.DOWNLOAD, login) { session = it }
                val d = GameDownloader(s, SteamGame.ALABASTER_DAWN.appId, GameFiles.root(this))
                downloader = d
                if (cancelled) throw java.util.concurrent.CancellationException()
                var shown = -1
                d.run(
                    status = { text -> report(text, null) },
                    bytes = { done, total ->
                        val percent = GameDownload.percent(done, total)
                        GameDownloadState.update(GameDownload.progressLine(done, total), percent)
                        if (percent != shown) {
                            shown = percent
                            notify(notification("Downloading Alabaster Dawn", percent))
                        }
                    },
                )
                GameFiles.installedBuild(this)?.let { GameFiles.setSteamBuild(this, it) }
            } catch (e: LoginRejectedException) {
                store.clearLogin()
                end = "Your Steam login has expired. Sign in again."
            } catch (e: Throwable) {
                end = if (cancelled) {
                    "Download stopped. Press DOWNLOAD to carry on."
                } else {
                    Log.w(TAG, "game download failed", e)
                    "Download failed: ${SteamProbe.describe(e)}. Press DOWNLOAD to try again."
                }
            } finally {
                session?.close()
                downloader = null
                session = null
            }
            releaseLocks()
            GameDownloadState.finish(end)
            stopForegroundCompat()
            /* Left behind (unless the user cancelled), so a download that ended while the launcher
             * was not on screen says how it ended. */
            if (!cancelled) notify(notification(end, null, ongoing = false), DONE_ID)
            stopSelf()
        }, "game-download").start()
    }

    private fun report(text: String, percent: Int?) {
        GameDownloadState.update(text, percent)
        notify(notification(text, percent))
    }

    private fun notification(text: String, percent: Int?, ongoing: Boolean = true): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game download", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        /* The same intent the app icon sends: it brings the app forward as it is, without putting a
         * second launcher on top of the one that is there (Back then seemed to do nothing once). */
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, LauncherActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, launch, immutable)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("AlabasterDawnBox")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.accent))
        if (ongoing) {
            builder.setProgress(100, percent ?: 0, percent == null)
            val stop = PendingIntent.getService(
                this, 1, Intent(this, GameDownloadService::class.java).setAction(ACTION_CANCEL), immutable
            )
            builder.addAction(0, "Cancel", stop)
        }
        return builder.build()
    }

    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        /* Without the notification permission (Android 13+) this is silently dropped; the download
         * itself does not depend on it. */
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    /** Keeps the processor and Wi-Fi awake for the download when the screen is off. */
    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlabasterDawnBox:download").apply { acquire(6 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AlabasterDawnBox:download").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "AlabasterDawnBox"
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "io.github.aerithmetiq.alabasterdawnbox.CANCEL_DOWNLOAD"

        /** Starts a download, update or verification; does nothing when one is running. */
        fun start(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(DONE_ID)
            ContextCompat.startForegroundService(context, Intent(context, GameDownloadService::class.java))
        }

        fun cancel(context: Context) {
            if (GameDownloadState.running) {
                context.startService(Intent(context, GameDownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }
    }
}
