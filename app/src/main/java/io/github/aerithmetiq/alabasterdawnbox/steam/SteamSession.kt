package io.github.aerithmetiq.alabasterdawnbox.steam

import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** What a successful sign-in hands back: enough to log on again later without a password. */
data class SteamLogin(val accountName: String, val refreshToken: String, val guardData: String?)

/** The account's public face, for the launcher's account line and avatar. */
data class SteamProfile(val personaName: String?, val avatarHashHex: String?)

/** Steam answered and rejected the stored login: the user has to sign in again. */
class LoginRejectedException(val result: EResult) : IOException("Steam rejected the login ($result)")

/** The Steam server that answered cannot take the logon right now; another one can (see [SteamSession.open]). */
class SteamBusyException(result: EResult) : IOException("Steam is busy ($result)")

/**
 * One connection to Steam. Every call blocks, so a session is driven from a worker thread; JavaSteam's
 * callbacks are pumped on a thread of its own. [close] logs off and disconnects, and is safe to call
 * from any thread, any number of times: it is also how a pending sign-in is cancelled.
 *
 * Failures are split the way the launcher needs them: [IOException] for "Steam could not be reached
 * or dropped us" (play offline, keep the login), [LoginRejectedException] for "Steam said no" (sign in
 * again).
 *
 * [purpose] sets the logon's `loginID`. Steam lets one account be logged on several times as long as
 * the ids differ, and replaces a session whose id is used again; so each job that can overlap another
 * (the launcher's profile refresh, a download, a save sync, the playing status) has its own.
 */
class SteamSession(private val purpose: Purpose = Purpose.LAUNCHER) : Closeable {

    enum class Purpose { LAUNCHER, DOWNLOAD, SAVES, PLAYING, DIAGNOSTICS, ACHIEVEMENTS }

    val client = if (purpose == Purpose.DOWNLOAD) SteamClient(downloadConfiguration()) else SteamClient()
    private val manager = CallbackManager(client)
    private val user: SteamUser = client.getHandler(SteamUser::class.java)
        ?: error("JavaSteam has no SteamUser handler")

    @Volatile private var pumping = true
    private val pump = Thread({
        while (pumping) manager.runWaitCallbacks(500L)
    }, "steam-callbacks").apply { isDaemon = true }

    private val connected = CountDownLatch(1)
    private val loggedOn = CompletableFuture<LoggedOnCallback>()
    private val ownPersona = CompletableFuture<PersonaStateCallback>()
    @Volatile private var signedIn = false

    /**
     * Called (on the callback thread) each time Steam pushes this account's own status, with a line
     * such as "Online, game 3110760". This is Steam's own word on whether a playing status took.
     * Asking for the status instead (`requestFriendInfo`) is not a substitute: its answer was seen to
     * say "Offline, game 0" seconds after a push said otherwise.
     */
    @Volatile var onOwnState: ((String) -> Unit)? = null

    /** True once Steam has closed the connection (or it dropped); the session is then of no more use. */
    @Volatile var disconnected = false

    /** Why Steam logged this session off, when it said so before disconnecting; null otherwise. */
    @Volatile var loggedOffResult: EResult? = null
        private set
    private val closed = AtomicBoolean(false)

    private val subscriptions = listOf(
        manager.subscribe(ConnectedCallback::class.java) { connected.countDown() },
        manager.subscribe(DisconnectedCallback::class.java) {
            /* A dropped connection is an answer, not a hang: wake whatever is waiting. */
            disconnected = true
            connected.countDown()
            loggedOn.completeExceptionally(IOException("Steam closed the connection"))
            ownPersona.completeExceptionally(IOException("Steam closed the connection"))
        },
        manager.subscribe(LoggedOnCallback::class.java) { loggedOn.complete(it) },
        manager.subscribe(LoggedOffCallback::class.java) { loggedOffResult = it.result },
        manager.subscribe(PersonaStateCallback::class.java) { cb ->
            if (cb.friendId == client.steamID) {
                ownPersona.complete(cb)
                onOwnState?.invoke("${cb.personaState}, game ${cb.gamePlayedAppId}")
            }
        },
    )

    /** Connects to a Steam server. Throws [IOException] when there is no answer. */
    fun connect(timeoutSec: Long = 30) {
        pump.start()
        try {
            client.connect()
        } catch (e: Exception) {
            throw IOException("could not reach Steam: ${e.message}", e)
        }
        if (!connected.await(timeoutSec, TimeUnit.SECONDS)) {
            throw IOException("no answer from Steam in ${timeoutSec}s")
        }
        if (!client.isConnected) throw IOException("Steam closed the connection")
    }

    /**
     * Signs in by QR code: [onCode] gets the URL to draw, and again each time Steam rotates it. Returns
     * once the Steam Mobile app approves. [persistent] asks for a long-lived login the launcher keeps.
     */
    fun signInWithQr(deviceName: String, persistent: Boolean, onCode: (String) -> Unit): SteamLogin {
        val details = AuthSessionDetails().apply {
            deviceFriendlyName = deviceName
            persistentSession = persistent
        }
        val auth = client.authentication.beginAuthSessionViaQR(details).get(30, TimeUnit.SECONDS)
        auth.challengeUrlChanged = IChallengeUrlChanged { rotated -> rotated?.let { onCode(it.challengeUrl) } }
        onCode(auth.challengeUrl)
        val done = auth.pollingWaitForResult().get()
        return SteamLogin(done.accountName, done.refreshToken, done.newGuardData)
    }

    /**
     * Signs in with a password. The password goes to Steam through JavaSteam and is not kept. Steam
     * Guard is handled by [authenticator]: an app approval, or a code the user types.
     */
    fun signInWithPassword(
        deviceName: String,
        username: String,
        password: String,
        guardData: String?,
        authenticator: IAuthenticator,
    ): SteamLogin {
        val details = AuthSessionDetails().apply {
            deviceFriendlyName = deviceName
            this.username = username
            this.password = password
            this.guardData = guardData
            this.authenticator = authenticator
            persistentSession = true
        }
        val auth = client.authentication.beginAuthSessionViaCredentials(details).get(60, TimeUnit.SECONDS)
        val done = auth.pollingWaitForResult().get()
        return SteamLogin(done.accountName, done.refreshToken, done.newGuardData ?: guardData)
    }

    /** Logs on with a login from a sign-in (fresh or stored). */
    fun logOn(login: SteamLogin, timeoutSec: Long = 30) {
        user.logOn(
            LogOnDetails().apply {
                username = login.accountName
                accessToken = login.refreshToken
                shouldRememberPassword = true
                /* Non-zero, so a Steam client signed in on the same network is not kicked off. */
                loginID = LOGIN_ID + purpose.ordinal
            }
        )
        val result = try {
            loggedOn.get(timeoutSec, TimeUnit.SECONDS).result
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("no logon answer from Steam in ${timeoutSec}s")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "logon failed")
        }
        when (result) {
            EResult.OK -> signedIn = true
            EResult.TryAnotherCM, EResult.ServiceUnavailable, EResult.Timeout, EResult.Busy ->
                throw SteamBusyException(result)
            else -> throw LoginRejectedException(result)
        }
    }

    /**
     * The signed-in account's persona name and avatar. Asks for the account's own persona data only;
     * it does not set the account "online" to friends.
     */
    fun profile(timeoutSec: Long = 10): SteamProfile {
        val friends = client.getHandler(SteamFriends::class.java) ?: return SteamProfile(null, null)
        client.steamID?.let { friends.requestFriendInfo(it, PERSONA_NAME or PERSONA_PRESENCE) }
        val cb = runCatching { ownPersona.get(timeoutSec, TimeUnit.SECONDS) }.getOrNull()
        val name = cb?.playerName?.takeIf { it.isNotEmpty() } ?: friends.getPersonaName()
        val hash = (cb?.avatarHash?.takeIf { it.isNotEmpty() } ?: friends.getPersonaAvatar())
            ?.takeIf { bytes -> bytes.any { it.toInt() != 0 } }
            ?.joinToString("") { "%02x".format(it) }
        return SteamProfile(name, hash)
    }

    /**
     * **Writes to Steam**: shows the account as online and playing [appId] for as long as this
     * session stays logged on. Friends see it, and Steam counts the play time. [stopPlaying], or
     * closing the session, ends it.
     */
    fun showPlaying(appId: Int, buildId: Int, processId: Int) {
        val apps = client.getHandler(SteamApps::class.java) ?: return
        client.getHandler(SteamFriends::class.java)?.setPersonaState(EPersonaState.Online)
        Thread.sleep(1000)
        apps.notifyGamesPlayed(
            listOf(
                GamePlayedInfo(
                    gameId = appId.toLong(),
                    processId = processId,
                    ownerId = client.steamID?.accountID?.toInt() ?: 0,
                    gameBuildId = buildId,
                )
            ),
            EOSType.AndroidUnknown,
        )
    }

    /**
     * Tells Steam this session is running [appId] (or nothing, with null) without touching the
     * account's online status, so friends are not shown it going online. Steam's own client only
     * changes a game's stats while the game runs (see SteamAchievements).
     */
    fun runningGame(appId: Int?) {
        val apps = client.getHandler(SteamApps::class.java) ?: return
        val games = if (appId == null) emptyList() else listOf(
            GamePlayedInfo(gameId = appId.toLong(), processId = 0, ownerId = client.steamID?.accountID?.toInt() ?: 0, gameBuildId = 0)
        )
        apps.notifyGamesPlayed(games, EOSType.AndroidUnknown)
    }

    fun stopPlaying() {
        runCatching { client.getHandler(SteamApps::class.java)?.notifyGamesPlayed(emptyList(), EOSType.AndroidUnknown) }
        runCatching { client.getHandler(SteamFriends::class.java)?.setPersonaState(EPersonaState.Offline) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (signedIn) runCatching { user.logOff() }
        runCatching { client.disconnect() }
        pumping = false
        runCatching { pump.join(2000) }
        subscriptions.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Connects and logs on, and returns the session. A server that answers "busy" or "try another
         * server" is common and brief, so that answer is met with a fresh connection (which lands on
         * another server), up to [attempts] times. Each session is handed to [hold] as it is made, so
         * the caller can close it to cancel. Throws as [connect] and [logOn] do.
         */
        fun open(purpose: Purpose, login: SteamLogin, attempts: Int = 3, hold: (SteamSession) -> Unit = {}): SteamSession {
            var last: SteamBusyException? = null
            repeat(attempts) { attempt ->
                val s = SteamSession(purpose)
                hold(s)
                try {
                    s.connect()
                    s.logOn(login)
                    return s
                } catch (e: SteamBusyException) {
                    last = e
                    s.close()
                    Thread.sleep(1000L * (attempt + 1))
                } catch (e: Throwable) {
                    s.close()
                    throw e
                }
            }
            throw last ?: IOException("could not log on to Steam")
        }

        /** Any stable non-zero value; Steam only compares it between sessions of one account. The
         *  purpose's number is added to it. */
        private const val LOGIN_ID = 0x41444431

        /** EClientPersonaStateFlag bits: the name, and presence (which carries the avatar hash). */
        private const val PERSONA_NAME = 2
        private const val PERSONA_PRESENCE = 16
    }
}

/**
 * A download session's HTTP client: [GameDownloader] keeps [GameDownloader.CONNECTIONS] chunk requests
 * in flight to a few servers. OkHttp's defaults (at most five requests per host, five idle connections
 * kept) would queue most of them and keep closing connections that the next chunk then reopens.
 */
private fun downloadConfiguration(): SteamConfiguration {
    val http = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = GameDownloader.CONNECTIONS })
        .connectionPool(ConnectionPool(GameDownloader.CONNECTIONS + 4, 5, TimeUnit.MINUTES))
        .build()
    return SteamConfiguration.create { it.withHttpClient(http) }
}
