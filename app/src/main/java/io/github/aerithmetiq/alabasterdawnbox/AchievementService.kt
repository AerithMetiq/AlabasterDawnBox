package io.github.aerithmetiq.alabasterdawnbox

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.aerithmetiq.alabasterdawnbox.steam.Achievements
import io.github.aerithmetiq.alabasterdawnbox.steam.LoginStore
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamAchievements
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamGame
import io.github.aerithmetiq.alabasterdawnbox.steam.SteamSession
import java.io.File

/**
 * Unlocks the game's Steam achievements. **Writes to Steam**, and only acts while achievements are
 * turned on (Settings; on by default).
 *
 * The game reports an achievement through greenworks (`cc-shim.js`), and the game's process hands the
 * name here ([unlock]); this service runs in the launcher's process, which holds the Steam login. Names
 * wait in a queue file ([GameFiles.achievementQueue]) until Steam has them, so an achievement earned
 * offline goes up the next time Steam can be reached ([retry], from the launcher). Names arrive in
 * bursts (the game reports every achievement a save holds each time it loads one), so the service
 * waits a moment after the last one and sends them together: one read of the user's stats, one change
 * holding only the bits that are not set yet.
 */
class AchievementService : Service() {

    @Volatile private var worker: Thread? = null
    @Volatile private var lastArrival = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GameFiles.steamAchievements(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_NAME)?.let { name ->
            synchronized(LOCK) {
                val queue = queue(this)
                if (name !in queue) writeQueue(this, queue + name)
            }
            Log.i(TAG, "achievements: the game awarded $name")
        }
        lastArrival = System.currentTimeMillis()
        if (worker == null) worker = Thread({ work() }, "steam-achievements").apply { start() }
        return START_NOT_STICKY
    }

    private fun work() {
        try {
            var attempts = 0
            while (true) {
                /* Let a burst finish arriving first. */
                while (System.currentTimeMillis() - lastArrival < SETTLE_MS) Thread.sleep(250)
                val names = synchronized(LOCK) { queue(this) }
                if (names.isEmpty()) break
                val login = LoginStore(this).load() ?: break
                try {
                    SteamSession.open(SteamSession.Purpose.ACHIEVEMENTS, login).use { s ->
                        val steam = SteamAchievements(s, SteamGame.ALABASTER_DAWN.appId)
                        val unlocked = steam.unlock(names)
                        val state = steam.read()
                        /* Done with: unlocked now, already unlocked, or not an achievement of this game. */
                        val settled = names.filter { it !in state.bits || state.unlocked(it) }
                        synchronized(LOCK) { writeQueue(this, queue(this).filter { it !in settled }) }
                        Log.i(
                            TAG,
                            "achievements: unlocked ${unlocked.size} on Steam" +
                                (if (unlocked.isEmpty()) "" else " (${unlocked.joinToString()})") +
                                "; ${names.size - unlocked.size} were already there or not this game's",
                        )
                    }
                    attempts = 0
                } catch (e: Exception) {
                    Log.w(TAG, "achievements: not sent this time: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (++attempts >= MAX_ATTEMPTS) break
                    Thread.sleep(RETRY_MS)
                }
                if (System.currentTimeMillis() - lastArrival >= SETTLE_MS && synchronized(LOCK) { queue(this) }.isEmpty()) break
            }
        } catch (e: InterruptedException) {
            /* stopping */
        } finally {
            worker = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlabasterDawnBox"
        private const val EXTRA_NAME = "name"
        private const val SETTLE_MS = 3_000L
        private const val RETRY_MS = 30_000L
        private const val MAX_ATTEMPTS = 3
        private val LOCK = Any()

        /** From the game's process: the game awarded the achievement [name]. */
        fun unlock(context: Context, name: String) {
            runCatching {
                context.startService(Intent(context, AchievementService::class.java).putExtra(EXTRA_NAME, name))
            }.onFailure { Log.w(TAG, "achievements: could not pass $name on: ${it.message}") }
        }

        /** From the launcher: send what is still queued, if anything. */
        fun retry(context: Context) {
            if (GameFiles.steamAchievements(context) && queue(context).isNotEmpty()) {
                runCatching { context.startService(Intent(context, AchievementService::class.java)) }
            }
        }

        fun queue(context: Context): List<String> =
            runCatching { Achievements.parseQueue(GameFiles.achievementQueue(context).readText()) }.getOrDefault(emptyList())

        private fun writeQueue(context: Context, names: List<String>) {
            val file = GameFiles.achievementQueue(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Achievements.formatQueue(names))
            if (!tmp.renameTo(file)) {
                file.writeText(Achievements.formatQueue(names))
                tmp.delete()
            }
        }
    }
}
