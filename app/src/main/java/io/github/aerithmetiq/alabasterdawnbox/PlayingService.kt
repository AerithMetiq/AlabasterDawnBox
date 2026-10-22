package io.github.aerithmetiq.alabasterdawnbox

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.aerithmetiq.alabasterdawnbox.steam.LoginStore
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamGame
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamSession

/**
 * While the game runs, keeps a Steam session that shows the account as playing Alabaster Dawn.
 * **Writes to Steam**, so it only runs when the user has turned it on (Settings, off by default) and
 * the game was started online.
 *
 * The launcher starts it with the game and stops it when the game is left. It is an ordinary service,
 * not a foreground one: when the app is put in the background Android stops it after a while, and the
 * status ends with it, which is the right outcome. A lost connection is retried while it runs.
 */
class PlayingService : Service() {

    @Volatile private var running = false
    @Volatile private var session: SteamSession? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            running = true
            worker = Thread({ loop() }, "steam-playing").apply { start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        session?.let { s ->
            Thread({
                s.stopPlaying()
                s.close()
            }, "steam-close").start()
        }
        super.onDestroy()
    }

    private fun loop() {
        val login = LoginStore(this).load() ?: return
        val build = GameFiles.installedBuild(this)?.toIntOrNull() ?: 0
        while (running) {
            try {
                val s = SteamSession.open(SteamSession.Purpose.PLAYING, login) { session = it }
                /* Steam's own word on it, for the log: "Online, game 3110760" is the status taking. */
                s.onOwnState = { Log.i(TAG, "playing status: Steam shows this account as $it") }
                s.showPlaying(SteamGame.ALABASTER_DAWN.appId, build, Process.myPid())
                Log.i(TAG, "playing status: sent to Steam")
                holdsGame = true
                while (running && !s.disconnected) Thread.sleep(1000)
            } catch (e: InterruptedException) {
                /* stopping */
            } catch (e: Throwable) {
                Log.i(TAG, "playing status: ${e.message}")
            } finally {
                holdsGame = false
                if (!running) session?.stopPlaying()
                session?.close()
                session = null
            }
            if (running) runCatching { Thread.sleep(RETRY_MS) }
        }
        Log.i(TAG, "playing status: ended")
    }

    companion object {
        private const val TAG = "AlabasterDawnBox"
        private const val RETRY_MS = 30_000L

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlayingService::class.java)) }
        }

        /**
         * True while a session of this service has the game "in game" on Steam. Another session of
         * the account that then asks about the same game's stats is dropped by Steam (Nova,
         * 2026-10-21), so AchievementService waits until this is false.
         */
        @Volatile var holdsGame = false
            private set

        fun stop(context: Context) {
            holdsGame = false
            context.stopService(Intent(context, PlayingService::class.java))
        }
    }
}
