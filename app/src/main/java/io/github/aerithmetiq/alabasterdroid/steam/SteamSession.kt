package io.github.aerithmetiq.alabasterdroid.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a successful sign-in hands back: enough to log on again later without a password. */
data class SteamLogin(val accountName: String, val refreshToken: String, val guardData: String?)

/** The account's public face, for the launcher's account line and avatar. */
data class SteamProfile(val personaName: String?, val avatarHashHex: String?)

/** Steam answered and rejected the stored login: the user has to sign in again. */
class LoginRejectedException(val result: EResult) : IOException("Steam rejected the login ($result)")

/**
 * One connection to Steam. Every call blocks, so a session is driven from a worker thread; JavaSteam's
 * callbacks are pumped on a thread of its own. [close] logs off and disconnects, and is safe to call
 * from any thread, any number of times: it is also how a pending sign-in is cancelled.
 *
 * Failures are split the way the launcher needs them: [IOException] for "Steam could not be reached
 * or dropped us" (play offline, keep the login), [LoginRejectedException] for "Steam said no" (sign in
 * again).
 */
class SteamSession : Closeable {

    val client = SteamClient()
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
    private val closed = AtomicBoolean(false)

    private val subscriptions = listOf(
        manager.subscribe(ConnectedCallback::class.java) { connected.countDown() },
        manager.subscribe(DisconnectedCallback::class.java) {
            /* A dropped connection is an answer, not a hang: wake whatever is waiting. */
            connected.countDown()
            loggedOn.completeExceptionally(IOException("Steam closed the connection"))
            ownPersona.completeExceptionally(IOException("Steam closed the connection"))
        },
        manager.subscribe(LoggedOnCallback::class.java) { loggedOn.complete(it) },
        manager.subscribe(PersonaStateCallback::class.java) { cb ->
            if (cb.friendId == client.steamID) ownPersona.complete(cb)
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
                loginID = LOGIN_ID
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
                throw IOException("Steam is busy ($result)")
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (signedIn) runCatching { user.logOff() }
        runCatching { client.disconnect() }
        pumping = false
        runCatching { pump.join(2000) }
        subscriptions.forEach { runCatching { it.close() } }
    }

    private companion object {
        /** Any stable non-zero value; Steam only compares it between sessions of one account. */
        const val LOGIN_ID = 0x41444431

        /** EClientPersonaStateFlag bits: the name, and presence (which carries the avatar hash). */
        const val PERSONA_NAME = 2
        const val PERSONA_PRESENCE = 16
    }
}
