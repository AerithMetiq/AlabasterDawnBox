package io.github.aerithmetiq.alabasterdroid

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import io.github.aerithmetiq.alabasterdroid.host.GameActivity
import io.github.aerithmetiq.alabasterdroid.steam.CloudSaves
import io.github.aerithmetiq.alabasterdroid.steam.CloudSync
import io.github.aerithmetiq.alabasterdroid.steam.GameDownload
import io.github.aerithmetiq.alabasterdroid.steam.GameDownloader
import io.github.aerithmetiq.alabasterdroid.steam.LoginRejectedException
import io.github.aerithmetiq.alabasterdroid.steam.LoginStore
import io.github.aerithmetiq.alabasterdroid.steam.SaveAction
import io.github.aerithmetiq.alabasterdroid.steam.SaveStep
import io.github.aerithmetiq.alabasterdroid.steam.SteamCloudFiles
import io.github.aerithmetiq.alabasterdroid.steam.SteamGame
import io.github.aerithmetiq.alabasterdroid.steam.SteamLogin
import io.github.aerithmetiq.alabasterdroid.steam.SteamProbe
import io.github.aerithmetiq.alabasterdroid.steam.SteamSession
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * The home screen: sign in to Steam (password with Steam Guard, or QR code), then PLAY. PLAY syncs
 * the saves with Steam Cloud first, and coming back from the game syncs them again (see [syncSaves]).
 *
 * The login is kept encrypted ([LoginStore]); on every start the launcher logs on in the background to
 * refresh the persona name and avatar and to find out early whether the login still works. A login
 * Steam rejects is dropped; a Steam that cannot be reached is not an expired login, and the game stays
 * playable offline.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var store: LoginStore

    private lateinit var signedOut: View
    private lateinit var signedIn: View
    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var signIn: MaterialButton
    private lateinit var signInQr: MaterialButton
    private lateinit var account: TextView
    private lateinit var play: MaterialButton
    private lateinit var playCaption: TextView
    private lateinit var offline: CheckBox
    private lateinit var busy: ProgressBar
    private lateinit var status: TextView
    private lateinit var downloadBar: ProgressBar
    private lateinit var avatar: ShapeableImageView
    private lateinit var gameArt: ImageView
    private lateinit var gameTitle: ImageView
    private lateinit var wordmark: TextView
    private lateinit var wordmarkDroid: TextView

    /** The session a sign-in or refresh is using, so leaving the screen can cancel it. */
    @Volatile private var session: SteamSession? = null
    @Volatile private var leaving = false
    @Volatile private var refreshing = false

    /** The game download in progress, with its own session, so PLAY, signing out or leaving can stop it. */
    @Volatile private var download: GameDownloader? = null
    @Volatile private var downloadSession: SteamSession? = null
    @Volatile private var downloading = false
    @Volatile private var downloadCancelled = false
    /** Shown on the button while file data is moving; null before that. UI thread only. */
    private var downloadPercent: Int? = null

    /** A Steam Cloud save sync in progress, with its own session, and the question it may be waiting on. */
    @Volatile private var syncing = false
    @Volatile private var syncSession: SteamSession? = null
    @Volatile private var conflictAnswer: CompletableFuture<Boolean?>? = null

    private val prefs get() = getSharedPreferences("launcher", MODE_PRIVATE)
    private val avatarFile get() = File(filesDir, "avatar.jpg")
    private val deviceName get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Alabaster Droid)"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        store = LoginStore(this)

        signedOut = findViewById(R.id.signed_out)
        signedIn = findViewById(R.id.signed_in)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        signIn = findViewById(R.id.sign_in)
        signInQr = findViewById(R.id.sign_in_qr)
        account = findViewById(R.id.account)
        play = findViewById(R.id.play)
        playCaption = findViewById(R.id.play_caption)
        offline = findViewById(R.id.offline)
        busy = findViewById(R.id.busy)
        status = findViewById(R.id.status)
        downloadBar = findViewById(R.id.download_bar)
        avatar = findViewById(R.id.avatar)
        gameArt = findViewById(R.id.game_art)

        /* The gold status bar. The avatar floats in the corner, outside the controls' layout, so it
         * is moved clear of the bar here. */
        val d = resources.displayMetrics.density
        Screens.goldStatusBar(this) { bars ->
            avatar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + (14 * d).toInt()
                marginEnd = bars.right + (18 * d).toInt()
            }
        }
        gameTitle = findViewById(R.id.game_title)
        wordmark = findViewById(R.id.wordmark)
        wordmarkDroid = findViewById(R.id.wordmark_droid)

        signIn.setOnClickListener { signInWithPassword() }
        signInQr.setOnClickListener { signInWithQr() }
        play.setOnClickListener { onPlay() }
        offline.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_OFFLINE, checked).apply()
            updatePlay()
        }
        findViewById<MaterialButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.sign_out).setOnClickListener { signOut() }

        render()
        if (store.load() != null) refreshProfile()
        else setStatus("Sign in with the Steam account that owns Alabaster Dawn.")
    }

    override fun onResume() {
        super.onResume()
        loadGameArt()
        updatePlay()
        syncAfterGame()
    }

    override fun onDestroy() {
        leaving = true
        cancelSession()
        cancelDownload()
        cancelSync()
        super.onDestroy()
    }

    /**
     * Closes the session a sign-in or refresh is using, off the main thread (logging off and stopping
     * the callback thread can take a moment). The pending call then throws and its worker ends.
     */
    private fun cancelSession(s: SteamSession? = session) {
        s?.let { Thread({ it.close() }, "steam-close").start() }
    }

    /* ------------------------------------------------------------------- state ---- */

    /** Shows the half of the screen the login state calls for, with controller focus on its main button. */
    private fun render() {
        if (store.load() != null) {
            signedOut.visibility = View.GONE
            signedIn.visibility = View.VISIBLE
            offline.isChecked = prefs.getBoolean(KEY_OFFLINE, false)
            account.text = accountLine()
            updatePlay()
            loadAvatar()
            play.requestFocus()
        } else {
            signedIn.visibility = View.GONE
            signedOut.visibility = View.VISIBLE
            avatar.visibility = View.GONE
            if (username.text.isNullOrEmpty()) username.setText(store.lastAccount ?: "")
            signInQr.requestFocus()
        }
    }

    /** "Signed in as <persona>", the name bright and bold against the dim label. */
    private fun accountLine(): CharSequence {
        val name = store.persona ?: store.lastAccount ?: "Steam user"
        return SpannableStringBuilder("Signed in as ").apply {
            val start = length
            append(name)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@LauncherActivity, R.color.text_primary)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updatePlay() {
        if (!::play.isInitialized) return
        val playOffline = prefs.getBoolean(KEY_OFFLINE, false)
        play.text = when {
            downloading -> downloadPercent?.let { "✕  CANCEL · $it%" } ?: "✕  CANCEL"
            !GameFiles.installed(this) -> "↓  DOWNLOAD"
            playOffline -> "▶  PLAY OFFLINE"
            else -> "▶  PLAY"
        }
        playCaption.text = when {
            downloading -> "Downloading Alabaster Dawn · keep Alabaster Droid open until it finishes"
            !GameFiles.installed(this) -> "Downloads your own copy of Alabaster Dawn from Steam"
            playOffline -> "Plays the saves on this device · no Steam Cloud sync"
            GameFiles.cloudUploads(this) -> "Syncs your saves with Steam Cloud before and after you play"
            else -> "Downloads your Steam Cloud saves · uploads are off (Settings)"
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun setBusy(on: Boolean, text: String? = null) {
        busy.visibility = if (on) View.VISIBLE else View.GONE
        signIn.isEnabled = !on
        signInQr.isEnabled = !on
        play.isEnabled = !on
        text?.let { setStatus(it) }
    }

    private fun ui(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    /* ----------------------------------------------------------------- sign-in ---- */

    private fun signInWithPassword() {
        val user = username.text?.toString()?.trim().orEmpty()
        val pass = password.text?.toString().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) {
            setStatus("Enter your Steam username and password.")
            return
        }
        hideKeyboard()
        setBusy(true, "Connecting to Steam…")
        runSignIn { s ->
            s.connect()
            ui { setStatus("Signing in as $user…") }
            s.signInWithPassword(deviceName, user, pass, store.guardDataFor(user), SteamGuardPrompt(s))
        }
    }

    private fun signInWithQr() {
        val fit = QrCode.fit(resources.configuration)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val codePx = dp(fit.codeDp)
        val borderPx = dp(6)

        val heading = TextView(this).apply {
            text = "Sign in with QR"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val instructions = TextView(this).apply {
            text = "In the Steam Mobile app, open the Steam Guard tab, tap the QR icon, scan this code, then approve."
            setPadding(0, dp(10), 0, 0)
        }
        val qrStatus = TextView(this).apply {
            text = "Connecting to Steam…"
            setPadding(0, dp(14), 0, 0)
        }
        val code = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(borderPx, borderPx, borderPx, borderPx)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(fit.padDp), dp(18), dp(fit.padDp), dp(18))
            addView(
                LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading); addView(instructions); addView(qrStatus)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(code, LinearLayout.LayoutParams(codePx, codePx).apply { leftMargin = dp(16) })
        }

        var cancelled = false
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(row) })
            .setNegativeButton("Cancel") { _, _ -> cancelled = true; cancelSession() }
            .setOnCancelListener { cancelled = true; cancelSession() }
            .show()
        dialog.window?.setLayout(dp(fit.dialogDp), WindowManager.LayoutParams.WRAP_CONTENT)

        setBusy(true, "Waiting for the QR sign-in…")
        runSignIn(onDone = { runCatching { dialog.dismiss() } }, quietIf = { cancelled }) { s ->
            s.connect()
            s.signInWithQr(deviceName, persistent = true) { url ->
                val bitmap = runCatching { QrCode.bitmap(url, codePx - 2 * borderPx) }.getOrNull()
                ui {
                    bitmap?.let { code.setImageBitmap(it) }
                    qrStatus.text = "Scan within about a minute; Steam refreshes the code on its own."
                }
            }
        }
    }

    /**
     * Runs a sign-in on a worker: [signIn] produces the login, which is then saved, logged on and used to
     * fetch the profile. [onDone] runs on the UI thread afterwards either way; a failure while
     * [quietIf] is true (the user cancelled) is not reported.
     */
    private fun runSignIn(
        onDone: () -> Unit = {},
        quietIf: () -> Boolean = { false },
        signIn: (SteamSession) -> SteamLogin,
    ) {
        Thread({
            val s = SteamSession()
            session = s
            try {
                val login = signIn(s)
                store.save(login)
                ui { setStatus("Signed in. Loading your profile…") }
                s.logOn(login)
                saveProfile(s)
                ui {
                    onDone()
                    password.setText("")
                    setBusy(false, "Signed in.")
                    render()
                }
            } catch (e: Throwable) {
                if (!leaving) {
                    val quiet = quietIf() || e is CancellationException || e.cause is CancellationException
                    if (!quiet) Log.w(TAG, "sign-in failed", e)
                    ui {
                        onDone()
                        setBusy(false, if (quiet) "Sign-in cancelled." else "Sign-in failed: ${SteamProbe.describe(e)}")
                        render()
                    }
                }
            } finally {
                s.close()
                session = null
            }
        }, "steam-sign-in").start()
    }

    /**
     * Steam Guard for a password sign-in: an approval in the Steam Mobile app needs nothing typed; a code
     * (from the app, or emailed) is asked for in a dialog while JavaSteam waits on the answer.
     */
    private inner class SteamGuardPrompt(private val s: SteamSession) : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askCode(
                if (previousCodeWasIncorrect) "That code was wrong. Enter the current code from the Steam Mobile app."
                else "Enter the code from the Steam Mobile app's Steam Guard tab."
            )

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
            askCode(
                if (previousCodeWasIncorrect) "That code was wrong. Enter the code Steam emailed to ${email ?: "you"}."
                else "Enter the code Steam emailed to ${email ?: "you"}."
            )

        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            ui { setStatus("Approve the sign-in in the Steam Mobile app, or wait for it to ask for a code…") }
            return CompletableFuture.completedFuture(true)
        }

        private fun askCode(message: String): CompletableFuture<String> {
            val answer = CompletableFuture<String>()
            ui {
                val input = EditText(this@LauncherActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    setSingleLine()
                }
                val pad = (20 * resources.displayMetrics.density).toInt()
                AlertDialog.Builder(this@LauncherActivity)
                    .setTitle("Steam Guard")
                    .setMessage(message)
                    .setView(LinearLayout(this@LauncherActivity).apply {
                        setPadding(pad, 0, pad, 0)
                        addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    })
                    .setPositiveButton("OK") { _, _ -> answer.complete(input.text.toString().trim()) }
                    .setNegativeButton("Cancel") { _, _ -> answer.cancel(true); cancelSession(s) }
                    .setOnCancelListener { answer.cancel(true); cancelSession(s) }
                    .show()
                input.requestFocus()
            }
            return answer
        }
    }

    /* ------------------------------------------------------------- the profile ---- */

    /** Logs on with the stored login in the background, to refresh the profile and check the login. */
    private fun refreshProfile() {
        val login = store.load() ?: return
        if (refreshing) return
        refreshing = true
        Thread({
            val s = SteamSession()
            session = s
            try {
                s.connect()
                s.logOn(login)
                saveProfile(s)
                ui { render() }
            } catch (e: LoginRejectedException) {
                store.clearLogin()
                ui {
                    render()
                    setStatus("Your Steam login has expired. Sign in again.")
                }
            } catch (e: Throwable) {
                /* Signing out mid-refresh closes this session on purpose; that is not "offline". */
                if (!leaving && store.load() != null) {
                    Log.i(TAG, "profile refresh failed", e)
                    ui { setStatus("Couldn't reach Steam. You can still play offline.") }
                }
            } finally {
                s.close()
                session = null
                refreshing = false
            }
        }, "steam-profile").start()
    }

    /** Saves the persona name and, when it changed, downloads the avatar. Called on a worker. */
    private fun saveProfile(s: SteamSession) {
        val profile = s.profile()
        profile.personaName?.let { store.persona = it }
        val hash = profile.avatarHashHex ?: return
        if (hash != store.avatarHash || !avatarFile.isFile) {
            if (downloadAvatar(hash)) store.avatarHash = hash
        }
    }

    private fun downloadAvatar(hash: String): Boolean = try {
        val conn = URL("https://avatars.steamstatic.com/${hash}_full.jpg").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            val tmp = File(filesDir, "avatar.jpg.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(avatarFile)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "avatar download failed", e)
        false
    }

    private fun loadAvatar() {
        val bitmap = if (avatarFile.isFile) BitmapFactory.decodeFile(avatarFile.path) else null
        avatar.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        bitmap?.let { avatar.setImageBitmap(it) }
    }

    /**
     * The game's own title-screen art, once the game is downloaded: its backdrop behind the launcher,
     * and its name in place of the "ALABASTER / DROID" wordmark. Each part falls back to the
     * launcher's own look when its file is not there.
     */
    private fun loadGameArt() {
        fun show(view: ImageView, file: File?): Boolean {
            val bitmap = file?.let { decodeArt(it) }
            view.visibility = if (bitmap != null) View.VISIBLE else View.GONE
            bitmap?.let { view.setImageBitmap(it) }
            return bitmap != null
        }
        show(gameArt, GameFiles.titleBackground(this))
        val named = show(gameTitle, GameFiles.titleName(this))
        wordmark.visibility = if (named) View.GONE else View.VISIBLE
        wordmarkDroid.visibility = wordmark.visibility
    }

    /** Decodes a picture no larger than the launcher can use, so big art does not waste memory. */
    private fun decodeArt(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /* -------------------------------------------------------------- PLAY etc. ---- */

    private fun onPlay() {
        when {
            downloading -> {
                setStatus("Stopping the download…")
                cancelDownload()
            }
            GameFiles.installed(this) ->
                if (prefs.getBoolean(KEY_OFFLINE, false)) {
                    setStatus("")
                    startGame(online = false)
                } else {
                    syncSaves(thenPlay = true)
                }
            prefs.getBoolean(KEY_OFFLINE, false) ->
                setStatus("Alabaster Dawn isn't downloaded yet. Untick Play offline, then press DOWNLOAD.")
            else -> startDownload()
        }
    }

    private fun startGame(online: Boolean) {
        /* Remembered on disk: Android may stop the launcher while the game runs. */
        prefs.edit().putBoolean(KEY_SYNC_AFTER_GAME, online).apply()
        startActivity(Intent(this, GameActivity::class.java))
    }

    /* ------------------------------------------------------ Steam Cloud saves ---- */

    /** Back from the game: send what changed, when the game was started online and uploads are on. */
    private fun syncAfterGame() {
        if (!prefs.getBoolean(KEY_SYNC_AFTER_GAME, false) || syncing) return
        /* One try per game session; whatever it does not send, the next PLAY's sync will. */
        prefs.edit().putBoolean(KEY_SYNC_AFTER_GAME, false).apply()
        if (store.load() == null || !GameFiles.installed(this)) return
        if (GameFiles.cloudUploads(this)) syncSaves(thenPlay = false)
        else setStatus("Your saves stay on this device: uploads to Steam Cloud are off (Settings).")
    }

    /**
     * Syncs the save folder with Steam Cloud on a worker with a session of its own, then starts the
     * game when [thenPlay]. Downloads always happen; uploads only when they are switched on in
     * Settings. When both sides changed, the user is asked which to keep, and cancelling changes
     * nothing. If Steam cannot be reached, PLAY carries on with the saves on this device.
     */
    private fun syncSaves(thenPlay: Boolean) {
        val login = store.load() ?: return
        if (syncing) return
        syncing = true
        val uploads = GameFiles.cloudUploads(this)
        setBusy(true, "Checking your Steam Cloud saves…")
        Thread({
            val s = SteamSession()
            var launch = thenPlay
            var end: String
            try {
                /* The start-up profile refresh logs on with the same login; let it finish first. */
                while (refreshing && !leaving) Thread.sleep(100)
                waitForGameToEnd()
                syncSession = s
                if (leaving) throw CancellationException()
                val reached = try {
                    s.connect()
                    s.logOn(login)
                    true
                } catch (e: LoginRejectedException) {
                    throw e
                } catch (e: IOException) {
                    Log.i(TAG, "cloud sync: Steam not reached", e)
                    false
                }
                if (!reached) {
                    end = if (thenPlay) "Couldn't reach Steam. Playing with the saves on this device."
                    else "Couldn't reach Steam. Your saves are on this device and sync at the next PLAY."
                } else {
                    val sync = CloudSync(
                        SteamCloudFiles(s, SteamGame.ALABASTER_DAWN.appId),
                        GameFiles.saves(this), GameFiles.syncBase(this), GameFiles.saveBackups(this),
                    )
                    val check = sync.check()
                    var steps = check.steps
                    var keptLocal = emptySet<String>()
                    var cancelled = false
                    if (steps.any { it.action == SaveAction.CONFLICT }) {
                        when (askConflict(steps, uploads)) {
                            true -> steps = CloudSaves.resolve(steps, cloudWins = true)
                            false -> {
                                keptLocal = steps.filter { it.action == SaveAction.CONFLICT }.map { it.name }.toSet()
                                steps = CloudSaves.resolve(steps, cloudWins = false)
                            }
                            null -> cancelled = true
                        }
                    }
                    if (cancelled) {
                        launch = false
                        end = "Nothing was changed. Press PLAY to choose again, or tick Play offline to use this device's saves."
                    } else {
                        val out = sync.apply(check, steps, uploads, keptLocal) { text -> ui { setStatus(text) } }
                        end = CloudSaves.summary(out.downloaded, out.uploaded, out.notUploaded, uploads, out.uploadError)
                        Log.i(TAG, "cloud sync: downloaded ${out.downloaded}, uploaded ${out.uploaded}, not uploaded ${out.notUploaded}")
                    }
                }
            } catch (e: LoginRejectedException) {
                store.clearLogin()
                launch = false
                end = "Your Steam login has expired. Sign in again."
            } catch (e: Throwable) {
                launch = false
                end = if (leaving || store.load() == null) {
                    "Signed out."
                } else {
                    Log.w(TAG, "cloud sync failed", e)
                    "Steam Cloud sync failed: ${SteamProbe.describe(e)}. " +
                        if (thenPlay) "Press PLAY to try again, or tick Play offline." else "It is tried again at the next PLAY."
                }
            } finally {
                s.close()
                syncSession = null
                syncing = false
            }
            ui {
                setBusy(false, end)
                render()
                if (launch) startGame(online = true)
            }
        }, "cloud-sync").start()
    }

    /**
     * Asks which side's saves to keep, on the UI thread, while the sync's worker waits. True: Steam
     * Cloud's. False: this device's. Null: cancelled.
     */
    private fun askConflict(steps: List<SaveStep>, uploads: Boolean): Boolean? {
        val answer = CompletableFuture<Boolean?>()
        conflictAnswer = answer
        val (here, there) = CloudSaves.conflictTimes(steps)
        val count = CloudSaves.count(steps, SaveAction.CONFLICT)
        ui {
            setStatus("Waiting for your choice…")
            AlertDialog.Builder(this)
                .setTitle("Which saves do you want to keep?")
                .setMessage(
                    "The saves on this device and in Steam Cloud are different ($count ${if (count == 1) "file" else "files"}).\n\n" +
                        "Steam Cloud: last saved ${CloudSaves.time(there)}\n" +
                        "This device: last saved ${CloudSaves.time(here)}\n\n" +
                        "Steam Cloud's: this device's saves are replaced. They are copied to the SaveBackups folder first.\n\n" +
                        "This device's: " +
                        if (uploads) "they are uploaded and replace the ones in Steam Cloud."
                        else "Steam Cloud is left as it is, because uploads are off (Settings)."
                )
                .setPositiveButton("Steam Cloud's") { _, _ -> answer.complete(true) }
                .setNegativeButton("This device's") { _, _ -> answer.complete(false) }
                .setNeutralButton("Cancel") { _, _ -> answer.complete(null) }
                .setOnCancelListener { answer.complete(null) }
                .show()
        }
        return try {
            answer.get()
        } finally {
            conflictAnswer = null
        }
    }

    /** The game runs in its own process; give it a moment to be gone, so no save is mid-write. */
    private fun waitForGameToEnd() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val name = "$packageName:game"
        repeat(30) {
            if (am.runningAppProcesses?.any { it.processName == name } != true) return
            Thread.sleep(100)
        }
    }

    private fun cancelSync() {
        conflictAnswer?.cancel(true)
        cancelSession(syncSession)
    }

    /**
     * Downloads the game's `terra/` from Steam with the saved login, on a worker with a session of its
     * own. Whatever is already on disk and intact is kept, so pressing PLAY again carries on a download
     * that was cancelled or failed.
     */
    private fun startDownload() {
        val login = store.load() ?: return
        downloading = true
        downloadCancelled = false
        downloadPercent = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        downloadBar.isIndeterminate = true
        downloadBar.visibility = View.VISIBLE
        setStatus("Connecting to Steam…")
        updatePlay()
        Thread({
            val s = SteamSession()
            var end = "Alabaster Dawn is downloaded."
            try {
                /* The start-up profile refresh logs on with the same login; let it finish first. */
                while (refreshing && !downloadCancelled) Thread.sleep(100)
                downloadSession = s
                if (downloadCancelled) throw CancellationException()
                s.connect()
                s.logOn(login)
                val d = GameDownloader(s, SteamGame.ALABASTER_DAWN.appId, GameFiles.root(this))
                download = d
                if (downloadCancelled) throw CancellationException()
                d.run(
                    status = { text -> ui { setStatus(text) } },
                    bytes = { done, total ->
                        ui {
                            val percent = GameDownload.percent(done, total)
                            downloadBar.isIndeterminate = false
                            downloadBar.progress = percent
                            setStatus(GameDownload.progressLine(done, total))
                            if (downloading && percent != downloadPercent) {
                                downloadPercent = percent
                                updatePlay()
                            }
                        }
                    },
                )
            } catch (e: LoginRejectedException) {
                store.clearLogin()
                end = "Your Steam login has expired. Sign in again."
            } catch (e: Throwable) {
                end = if (downloadCancelled) {
                    "Download stopped. Press DOWNLOAD to carry on."
                } else {
                    Log.w(TAG, "game download failed", e)
                    "Download failed: ${SteamProbe.describe(e)}. Press DOWNLOAD to try again."
                }
            } finally {
                s.close()
                download = null
                downloadSession = null
                downloading = false
            }
            ui {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                downloadBar.visibility = View.GONE
                /* Signing out stops the download too; "Signed out." is then the last word. */
                if (store.load() != null || !downloadCancelled) setStatus(end)
                render()
                loadGameArt()
            }
        }, "game-download").start()
    }

    private fun cancelDownload() {
        downloadCancelled = true
        download?.cancel()
        cancelSession(downloadSession)
    }

    private fun signOut() {
        cancelSession()
        cancelDownload()
        cancelSync()
        store.clearLogin()
        avatarFile.delete()
        password.setText("")
        render()
        setStatus("Signed out.")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private companion object {
        const val TAG = "AlabasterDroid"
        const val KEY_OFFLINE = "playOffline"
        const val KEY_SYNC_AFTER_GAME = "syncAfterGame"
    }
}
