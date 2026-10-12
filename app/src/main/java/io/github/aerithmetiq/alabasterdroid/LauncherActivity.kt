package io.github.aerithmetiq.alabasterdroid

import android.content.Intent
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import io.github.aerithmetiq.alabasterdroid.steam.LoginRejectedException
import io.github.aerithmetiq.alabasterdroid.steam.LoginStore
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
 * The home screen: sign in to Steam (password with Steam Guard, or QR code), then PLAY.
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
    private lateinit var avatar: ShapeableImageView
    private lateinit var gameArt: ImageView

    /** The session a sign-in or refresh is using, so leaving the screen can cancel it. */
    @Volatile private var session: SteamSession? = null
    @Volatile private var leaving = false
    @Volatile private var refreshing = false

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
        avatar = findViewById(R.id.avatar)
        gameArt = findViewById(R.id.game_art)

        signIn.setOnClickListener { signInWithPassword() }
        signInQr.setOnClickListener { signInWithQr() }
        play.setOnClickListener { play() }
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
    }

    override fun onDestroy() {
        leaving = true
        cancelSession()
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
        play.text = if (playOffline) "▶  PLAY OFFLINE" else "▶  PLAY"
        playCaption.text = when {
            !GameFiles.installed(this) -> "Downloads Alabaster Dawn from Steam the first time (your own copy)"
            playOffline -> "Plays your local save · syncs next time you play online"
            else -> "Pulls your latest save · uploads to Steam Cloud on quit"
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

    /** The game's own title art behind the launcher, once the game is downloaded. */
    private fun loadGameArt() {
        val file = GameFiles.titleArt(this)
        val bitmap = file?.let { BitmapFactory.decodeFile(it.path) }
        gameArt.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        bitmap?.let { gameArt.setImageBitmap(it) }
    }

    /* -------------------------------------------------------------- PLAY etc. ---- */

    private fun play() {
        if (!GameFiles.installed(this)) {
            setStatus("Downloading Alabaster Dawn from Steam comes in the next build (milestone 2).")
            return
        }
        setStatus("Running the game comes in milestone 3.")
    }

    private fun signOut() {
        cancelSession()
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
    }
}
