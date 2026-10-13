/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Adapted for Alabaster Droid; see the class comment.
 */
package io.github.aerithmetiq.alabasterdroid.host

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.aerithmetiq.alabasterdroid.GameFiles as AppGameFiles
import java.io.File

/**
 * Hosts the game. This is alabaster-android's `PortActivity` with its folder pickers and two-game entry
 * screen taken out: Alabaster Droid's launcher downloads the game from Steam into the app's own files
 * folder, so this activity is started by PLAY, reads that folder and goes straight to the WebView. The
 * WebView set-up, the shim injection, input, the side menu, the on-screen pad, diagnostics and the
 * pause/resume handling are alabaster-android's, unchanged.
 *
 * Saves are plain files in `userdata/` next to the game (the Steam layout: `Saves/Default/…`), outside
 * `terra/`. Nothing under the game folder is ever written to.
 *
 * The activity runs in its own process (`:game`, see the manifest): leaving the game ends that
 * process, as it does in alabaster-android, and the launcher underneath is untouched.
 */
class GameActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView

    private lateinit var gameRoot: File
    private lateinit var savesDir: File
    private var index: GameIndex? = null
    private var profile: GameProfile? = null
    /** The game's newest release, parsed from the changelog once the tree is indexed (see [GameVersion]). */
    private var gameReleaseVersion: String? = null
    /** Set by [exitGame]: the game's process ends once the activity is gone. */
    private var exiting = false
    private var lastBackAt = 0L
    private var fsBridge: FsBridge? = null
    private var saveStore: SaveStore? = null
    private var padLayoutStore: PadLayoutStore? = null
    private var webView: WebView? = null
    private var padView: OnScreenPadView? = null
    private var menuView: SideMenuView? = null
    private var hideWithExternalInput = true
    private var dynamicSticks = true
    private var viewAlign = ViewAlign.DEFAULT
    private var statsEnabled = false
    /** Whether the shim caps the page's frame rate (the side menu's battery switch). */
    private var limitFps = false

    /** The rate that switch stands for, from [FpsLimit]; the slider under it changes this. */
    private var fpsLimit = FpsLimit.DEFAULT
    private var shimSource: String = ""

    /** Whether the record is also kept as [LogFile.FILE] in the saves folder. */
    private var logToSaves = true

    /* Written from the diagnostic sink (the JS bridge thread) and read by the watchdog (main), so
     * these are volatile; only the main thread decides to write. */
    @Volatile
    private var logDirty = false
    @Volatile
    private var logWriting = false
    @Volatile
    private var logFailures = 0

    /* Whether the previous session's record has been read (or found absent) yet: until then nothing
     * is flushed, so the write cannot clobber the record that launch is about to carry over. */
    @Volatile
    private var previousRecordRead = false

    /* Whether a previous session's lines are already in the ring, so a second read (a saves folder
     * picked mid-session) does not carry the same record twice. */
    @Volatile
    private var previousRecordCarried = false

    /** The app's own log and frame clock; everything device-specific is reported through it. */
    private lateinit var diag: Diag
    private var assetHandler: GameAssetHandler? = null

    /** The last report the injected shim made, shown in the side menu (a boot stall names itself). */
    @Volatile
    private var lastEngineReport: String = "-"

    /* The watchdog that tells a blocked page apart from a page waiting for a hung asset read. */
    private val watchdog = Handler(Looper.getMainLooper())
    private var webViewStartedAt = 0L
    private var lastSilentLogAt = 0L

    /**
     * Whether the page is meant to be running right now: the engine's loop only polls once per frame
     * while it has window focus, and the port pauses it on purpose (app backgrounded, or the
     * diagnostics dialog in front). Silence in those states is expected, not a hang.
     */
    @Volatile
    private var engineActive = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        /* Everything logged here is also written to logcat, so nothing is lost on a device where
         * the diagnostics panel cannot be read. The sink also marks the persistent record dirty and
         * posts the auto-off check on the shim's boot report: it runs inside Diag's lock, so it must
         * not call back into Diag (a field write and a post allocate nothing per line). */
        diag = Diag().also { it.attachSink { line ->
            Log.i(TAG, line)
            if (line.contains(" ENGINE ")) lastEngineReport = line.substringAfter("ENGINE ")
            logDirty = true
            if (line.contains(" ENGINE boot: ")) watchdog.post { autoDisableLog() }
        } }
        diag.line("port ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android " +
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})")
        hideWithExternalInput = prefs.getBoolean(KEY_HIDE_EXTERNAL_INPUT, true)
        dynamicSticks = prefs.getBoolean(KEY_DYNAMIC_STICKS, true)
        viewAlign = ViewAlign.fromWire(prefs.getString(KEY_VIEW_ALIGN, null))
        statsEnabled = prefs.getBoolean(KEY_STATS, false)
        limitFps = prefs.getBoolean(KEY_LIMIT_FPS, false)
        fpsLimit = FpsLimit.normalize(prefs.getInt(KEY_FPS_LIMIT, FpsLimit.DEFAULT))
        logToSaves = prefs.getBoolean(LogFile.PREF_KEY, true)

        gameRoot = AppGameFiles.root(this)
        savesDir = File(gameRoot, SAVES_DIR)
        savesDir.mkdirs()
        /* The previous session's record is read back into the ring: a user who freezes and restarts
         * expects the panel (and the file) to still show what happened. */
        saveStore = FileStore(savesDir, savesDir.absolutePath)
        carryPreviousRecord(saveStore!!)

        /* A content view must exist before [applyImmersive] touches the window's decor. */
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(status, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        })
        applyImmersive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            /* On Android 16 with targetSdk 36 the framework routes back to the dispatcher and never
             * calls onBackPressed; older devices only ever use onBackPressed. */
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                backPressed("dispatcher")
            }
        }
        startGame()
    }

    /** Reads the downloaded game's folder off the main thread, then starts the WebView. */
    private fun startGame() {
        setStatus("Reading the game files…")
        /* The flush chain starts here rather than in launchWebView, so a failure reading the folder
         * leaves a record on disk, not only on screen. launchWebView re-posts this same chain. */
        watchdog.postDelayed(watchdogTick, WATCHDOG_TICK_MS)
        diag.line("indexing $gameRoot")
        val started = System.currentTimeMillis()
        Thread({
            val built = try {
                GameTree.index(gameRoot)
            } catch (e: Exception) {
                Log.e(TAG, "indexing failed", e)
                diag.line("indexing failed: $e")
                null
            }
            val selected = GameProfile.ALABASTER_DAWN
            val release = built?.find(selected.versionPath!!)?.let { entry ->
                runCatching { File(entry.docId).readText(Charsets.UTF_8) }.getOrNull()?.let(GameVersion::fromChangelog)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (built?.find(selected.indexHtml) == null) {
                    diag.line("no ${selected.indexHtml} in $gameRoot (${built?.size ?: 0} entries)")
                    setStatus("The game files are missing. Go back and download Alabaster Dawn again.")
                    return@runOnUiThread
                }
                val bound = built.bind(selected.pageRoot)
                profile = selected
                index = bound
                gameReleaseVersion = release
                diag.line("indexed ${bound.size} entries in ${System.currentTimeMillis() - started}ms " +
                    "(${selected.displayName})")
                padLayoutStore = PadLayoutStore(prefs, saveStore!!)
                fsBridge = FsBridge(bound, saveStore!!)
                launchWebView()
            }
        }, "ada-index").start()
    }

    /**
     * Drops the game's stored Resolution option — the one value a phone user can set that leaves the
     * game too slow to reach its own Options menu. The engine keeps its device-local options as one
     * JSON object in `localStorage` and reads it at boot, so the reset is a URL parameter the shim
     * acts on before the engine boots (the profile's `resetVideoParam`); with a page to reload it
     * happens now, and from the pre-game screen it is armed for the next start instead. The one-shot
     * is cleared as the URL is built, so a kill in between cannot leave it half-applied.
     *
     * Only a game whose profile carries a reset parameter has the control at all (the side menu and
     * diagnostics hide it for the others).
     */
    private fun resetVideoOptions() {
        if (profile?.resetVideoParam == null) return
        closeMenu()
        val view = webView
        if (view == null) {
            prefs.edit().putBoolean(KEY_RESET_VIDEO, true).apply()
            status.text = "The game's Resolution will be reset when it starts."
            diag.line("the game's stored Resolution will be reset at the next start")
            return
        }
        prefs.edit().putBoolean(KEY_RESET_VIDEO, false).apply()
        diag.line("reloading to reset the game's stored Resolution")
        view.loadUrl(gameUrl(resetVideo = true))
    }

    /** The game's URL, carrying the one-shot video reset to the shim when one was armed. */
    private fun gameUrl(resetVideo: Boolean): String {
        val p = profile ?: return ""
        val param = p.resetVideoParam
        return if (resetVideo && param != null) "${p.entryUrl}?$param=1" else p.entryUrl
    }

    private fun launchWebView() {
        val built = index ?: return
        val bridge = fsBridge ?: return
        val selected = profile ?: return
        shimSource = readAsset(selected.shimAsset)
        /* Alabaster Droid: before anything starts WebView's engine (see [InProcessRenderer]). */
        if (InProcessRenderer.needed(this)) {
            diag.line("webView is 32-bit on a 64-bit app; renderer: " + InProcessRenderer.apply(this))
        }
        val injectShim = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        diag.line("webView ${webViewPackage()} document-start scripts=" + !injectShim +
            " ua=${webSettingsUserAgent()}")
        if (injectShim) {
            diag.line("document-start scripts unsupported; the shim is injected via " + selected.indexHtml)
        }
        val handler = GameAssetHandler(
            built, selected, assets, injectShim, diag
        )
        assetHandler = handler
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/game/", handler)
            .build()
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        /* WebView inspection: always for a debug build, and for a released APK only when the global
         * setting below is on (`adb shell settings put global rfport_webview_debug 1`). A released
         * build is not debuggable, and this is the only way to read a page's own exception - a stalled
         * loader leaves nothing else. Reading a global setting needs no permission; writing it is the
         * shell's. */
        if (webViewInspectable()) {
            WebView.setWebContentsDebuggingEnabled(true)
            diag.line("webView inspection on (${DEBUG_WEBVIEW_SETTING})")
        }
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val telemetry = Telemetry(this)
        view.addJavascriptInterface(
            PortBridge(
                bridge,
                viewAlign = { viewAlign },
                statsEnabled = { statsEnabled },
                fpsLimit = { if (limitFps) fpsLimit else FpsLimit.OFF },
                telemetry = telemetry,
                diag = diag,
                onQuit = { runOnUiThread { exitGame() } },
            ).also { port ->
                /* The page's shader self-test finishes asynchronously; show the record again once it
                 * carries the verdicts (FINDINGS §22.11). */
                port.onShaderSelfTestDone = { runOnUiThread { if (!isFinishing) showDiagnostics() } }
            },
            BRIDGE_NAME
        )
        if (!injectShim) {
            try {
                WebViewCompat.addDocumentStartJavaScript(view, shimSource, setOf(GameProfile.ORIGIN))
            } catch (e: Exception) {
                Log.e(TAG, "document-start injection failed", e)
            }
        }
        view.webViewClient = PortWebViewClient(loader)
        /* Audio can stay suspended until a real gesture; a tap silently nudges it. */
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) resumeAudio()
            false
        }
        webView = view
        /* The pad sits above the WebView, so it receives touches first; when it does not claim a
         * gesture (a hidden pad, or a tap on empty space with it hidden) it returns false and the
         * FrameLayout passes the event on to the WebView. */
        val frame = FrameLayout(this)
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val pad = OnScreenPadView(this).apply {
            padEnabled = prefs.getBoolean(KEY_PAD, true)
            hideWithExternalInput = this@GameActivity.hideWithExternalInput
            dynamicSticks = this@GameActivity.dynamicSticks
            layout = padLayoutStore?.load() ?: PadLayout()
            onToggle = { prefs.edit().putBoolean(KEY_PAD, it).apply() }
            onLayoutChanged = { padLayoutStore?.save(it) }
            onFirstTouch = { resumeAudio() }
        }
        padView = pad
        frame.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val menu = SideMenuView(this, viewAlignSupported = selected.viewAlign).apply {
            setHideWithExternalInput(this@GameActivity.hideWithExternalInput)
            setDynamicSticks(this@GameActivity.dynamicSticks)
            setStatsEnabled(statsEnabled)
            setLimitFps(limitFps)
            setFpsLimit(fpsLimit)
            setLogToSaves(logToSaves)
            setAlign(viewAlign)
            onHideWithExternalInput = {
                this@GameActivity.hideWithExternalInput = it
                prefs.edit().putBoolean(KEY_HIDE_EXTERNAL_INPUT, it).apply()
                padView?.hideWithExternalInput = it
            }
            onDynamicSticks = {
                this@GameActivity.dynamicSticks = it
                prefs.edit().putBoolean(KEY_DYNAMIC_STICKS, it).apply()
                padView?.dynamicSticks = it
                diag.line("dynamic sticks " + (if (it) "on" else "off"))
            }
            onStatsEnabled = {
                this@GameActivity.statsEnabled = it
                prefs.edit().putBoolean(KEY_STATS, it).apply()
            }
            /* The shim reads this on its once-per-frame poll, so the cap lands on the next frame.
             * The menu is told too: the rate slider under the switch is shown only while the cap is
             * on, and that is this view's own state to keep. */
            onLimitFps = {
                this@GameActivity.limitFps = it
                prefs.edit().putBoolean(KEY_LIMIT_FPS, it).apply()
                menuView?.setLimitFps(it)
                diag.line("frame limit " + (if (it) "on: $fpsLimit fps" else "off"))
            }
            /* The slider's rate; the switch keeps its own state, so a rate can be chosen while the
             * cap is off and is what the cap uses the moment it goes on. */
            onFpsLimit = {
                this@GameActivity.fpsLimit = it
                prefs.edit().putInt(KEY_FPS_LIMIT, it).apply()
                menuView?.setFpsLimit(it)
                diag.line("frame limit: $it fps" + (if (this@GameActivity.limitFps) "" else " (off)"))
            }
            /* An explicit tap ends the auto-off for good, whichever way it went. */
            onLogToSaves = {
                this@GameActivity.logToSaves = it
                prefs.edit()
                    .putBoolean(LogFile.PREF_KEY, it)
                    .putBoolean(LogFile.PREF_SET_KEY, true)
                    .apply()
                logDirty = true
                diag.line("log file " + (if (it) "on" else "off") + ": " + LogFile.FILE)
            }
            onAlign = {
                this@GameActivity.viewAlign = it
                prefs.edit().putString(KEY_VIEW_ALIGN, it.wire).apply()
            }
            onExit = { exitGame() }
            onDiagnostics = { showDiagnostics() }
            onScrimTap = { closeMenu() }
        }
        frame.addView(
            menu,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        menuView = menu
        setContentView(frame)
        view.requestFocus()
        setStatus("Loading the game…")
        diag.line("loading ${selected.entryUrl}")
        webViewStartedAt = monotonicMs()
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed(watchdogTick, WATCHDOG_TICK_MS)
        val resetVideo = selected.resetVideoParam != null && prefs.getBoolean(KEY_RESET_VIDEO, false)
        if (resetVideo) prefs.edit().putBoolean(KEY_RESET_VIDEO, false).apply()
        view.loadUrl(gameUrl(resetVideo))
    }

    /**
     * Runs while the game is up. The engine polls `getGamepadJson()` once per frame, so silence
     * there means the page's JS thread stopped - a different failure from a page that is alive but
     * waiting for an asset read that never returns. Both are reported with the names involved, which
     * is what turns a screenshot of a frozen bar into something actionable.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            flushLogIfDirty()
            val now = monotonicMs()
            val silent = diag.silentMs()
            if (webView != null && engineActive && now - webViewStartedAt > SILENT_MS &&
                silent > SILENT_MS && now - lastSilentLogAt > SILENT_LOG_MS
            ) {
                lastSilentLogAt = now
                diag.line("engine silent for ${silent}ms; last bridge call: ${diag.lastBridgeCall()}")
                for (stuck in assetHandler?.stuck(STUCK_MS).orEmpty()) {
                    diag.line("asset read stuck: $stuck")
                }
            }
            watchdog.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private inner class PortWebViewClient(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            diag.line("render process gone (crashed=${detail.didCrash()}); rebuilding the WebView")
            rebuildWebView()
            return true
        }
    }

    private fun rebuildWebView() {
        teardownWebView()
        if (index != null && fsBridge != null) launchWebView()
    }

    /** The line shown before the game's own picture is up. */
    private fun setStatus(text: String) {
        status.text = text
        Log.i(TAG, text)
    }


    private fun showDiagnostics() {
        diag.line("diagnostics opened")
        val text = diag.snapshot(diagFacts())
        DiagnosticsDialog(
            this,
            text,
            { shareDiagnostics(text) },
            { resetVideoOptions() },
            { runShaderSelfTest() },
            { openDriverSettings() },
            shaderTools = profile?.rewrites == true,
        ).apply {
            setOnDismissListener {
                /* The dialog held window focus, which blurred the page; hand it back like closeMenu. */
                webView?.requestFocus()
                setPageFocus(true)
                engineActive = true
            }
            /* The dialog blurs the page, and the engine stops its loop on `blur` by design: silence
             * while it is open is expected, so the watchdog must not call it a hang. */
            engineActive = false
            show()
        }
    }

    /**
     * Asks the page to run the shader self-test (FINDINGS §22.11): every spelling the port could serve,
     * compiled on this device's own GL front end. The verdicts arrive as record lines, and the summary
     * brings this panel back with them in it.
     */
    private fun runShaderSelfTest() {
        val page = webView
        if (page == null) {
            diag.line("shader self-test: no page to ask")
            return
        }
        diag.line("shader self-test: asked")
        page.evaluateJavascript("window.adaShaderSelfTest && window.adaShaderSelfTest()", null)
    }

    /**
     * Where the driver choice lives: ANGLE Preferences — the ANGLE apk ships it as a Developer-options
     * entry, under either of its two package names — with Developer options as the fallback. The port
     * cannot set the choice itself (it is a privileged `Settings.Global` entry, FINDINGS §22.8), so the
     * most it can do is put the screen in front of the user.
     */
    private fun openDriverSettings() {
        for (component in ANGLE_PREFERENCES) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
            if (packageManager.resolveActivity(intent, 0) == null) continue
            try {
                startActivity(intent)
                diag.line("opened ${component.flattenToShortString()}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open $component", e)
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            diag.line("opened Developer options: no ANGLE Preferences on this device")
        } catch (e: Exception) {
            diag.line("no driver settings screen: ${e.javaClass.simpleName}")
        }
    }

    /** What the record is read against: the device, the WebView, the folders and the live counters. */
    private fun diagFacts(): List<String> = listOf(
        "app ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android " +
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})",
        "game ${assetHandler?.gameBuild() ?: gameReleaseVersion ?: "unknown"}",
        "webView ${webViewPackage()} document-start scripts=" +
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        "game files: $gameRoot " +
            "(${if (index == null) "not indexed" else "${index!!.size} entries"})",
        "saves: ${savesStatusLine()}",
        "engine: silent for ${diag.silentMs()}ms, last bridge call: ${diag.lastBridgeCall()}",
        assetHandler?.counters() ?: "assets: none served yet",
    )

    /**
     * Reads the previous session's record out of [store] and carries it into the ring, off the main
     * thread (it is a SAF read). No flush may run until it lands (see [previousRecordRead]), or the
     * first write would overwrite the very record being carried over.
     */
    private fun carryPreviousRecord(store: SaveStore) {
        Thread({
            val text = if (store.exists(LogFile.FILE)) store.read(LogFile.FILE) else null
            val previous = text?.let { LogFile.previousLines(it, diag.maxLines()) }.orEmpty()
            if (previous.isNotEmpty()) {
                diag.carryOver(previous)
                previousRecordCarried = true
            }
            previousRecordRead = true
        }, "ada-log-read").start()
    }

    /**
     * Keeps [LogFile.FILE] in the saves folder current, at most every watchdog tick. The snapshot is
     * taken here (main thread, the same text the panel shows) and written on [writeLogAsync]'s own
     * thread: the stores under it are SAF, and a blocked write must never block the UI.
     */
    private fun flushLogIfDirty() {
        if (!previousRecordRead) return
        if (!LogFile.shouldFlush(logToSaves, logDirty, logWriting, logFailures)) return
        val store = saveStore ?: return
        logDirty = false
        logWriting = true
        writeLogAsync(store, diag.snapshot(diagFacts()))
    }

    /**
     * If this write hangs inside the provider, [logWriting] never clears and no further write is
     * attempted: no ANR, no spin. [LogFile.MAX_FAILURES] bounds the case where it returns an error.
     */
    private fun writeLogAsync(store: SaveStore, text: String) {
        Thread({
            val ok = store.write(LogFile.FILE, text)
            watchdog.post {
                logWriting = false
                if (ok) {
                    logFailures = 0
                } else if (++logFailures <= LogFile.MAX_FAILURES) {
                    diag.line("could not write ${LogFile.FILE} (attempt $logFailures)")
                }
            }
        }, "ada-log").start()
    }

    /**
     * Called when the shim reports a completed boot: the record is most useful for a boot that
     * *fails*, so by default it stops growing once one succeeds. Only while the user has never
     * touched the toggle - and the file itself stays on disk either way.
     */
    private fun autoDisableLog() {
        if (!LogFile.shouldAutoDisable(prefs.getBoolean(LogFile.PREF_SET_KEY, false), logToSaves)) {
            return
        }
        logToSaves = false
        prefs.edit().putBoolean(LogFile.PREF_KEY, false).apply() // PREF_SET_KEY stays false: untouched
        menuView?.setLogToSaves(false)
        diag.line("log file off: the game completed its first boot")
        /* One final write, after the toggle went off: this is an explicit one-shot, not the
         * periodic path, so what happened up to and including this boot is on disk. */
        saveStore?.let { writeLogAsync(it, diag.snapshot(diagFacts())) }
    }

    /** Writes the record next to the app and hands it to any app that can send text. */
    private fun shareDiagnostics(text: String): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "diagnostics-${System.currentTimeMillis() / 1000}.txt")
        try {
            file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "cannot write $file", e)
            return "could not write the file: ${e.message}"
        }
        diag.line("diagnostics written to ${file.absolutePath}")
        return try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            (profile?.displayName ?: "Radical Fish") + " Android port diagnostics"
                        )
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Share diagnostics"
                )
            )
            "sent from ${file.absolutePath}"
        } catch (e: ActivityNotFoundException) {
            "no app to share it with; the record is at ${file.absolutePath}"
        }
    }

    private fun appVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        "${info.versionName} (${info.versionCode})"
    } catch (e: Exception) {
        "?"
    }

    /** The WebView implementation actually running: a device without Play may be far behind. */
    private fun webViewPackage(): String = try {
        WebViewCompat.getCurrentWebViewPackage(this)?.let { "${it.packageName} ${it.versionName}" }
            ?: "unknown (no provider package)"
    } catch (e: Exception) {
        "?"
    }

    private fun webSettingsUserAgent(): String = try {
        WebSettings.getDefaultUserAgent(this)
    } catch (e: Exception) {
        "?"
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    /** One Back press, from whichever path delivered it; a second report of the same press is dropped. */
    private fun backPressed(via: String) {
        val now = monotonicMs()
        if (now - lastBackAt < BACK_REPEAT_MS) return
        lastBackAt = now
        diag.line("back: $via (${if (webView != null) "game" else "pre-game"})")
        handleBack()
    }

    /** Back opens the port's menu, closes it when it is open; pre-game it still exits the app. */
    private fun handleBack() {
        val menu = menuView
        if (menu == null) {
            finish()
        } else if (menu.isOpen) {
            closeMenu()
        } else {
            openMenu()
        }
    }

    private fun openMenu() {
        val menu = menuView ?: return
        menu.setStatus(padView?.externalInputInUse == true, savesStatusLine())
        /* The game's name matters in a report from a host that runs two of them. */
        menu.setVersion("port ${appVersion()} · " + (profile?.displayName ?: "no game"))
        menu.setLastEngineReport(lastEngineReport)
        menu.open()
    }

    /**
     * Closing re-focuses the WebView and re-dispatches the page focus the way `resumeAudio` does:
     * the panel blocks focus, but a stray blur would otherwise leave the engine's loop stopped
     * until the next resume or pad touch.
     */
    private fun closeMenu() {
        menuView?.close()
        webView?.requestFocus()
        setPageFocus(true)
    }

    /** Without a saves folder the port saves into app storage, which cannot be copied out. */
    private fun savesStatusLine(): String =
        savesDir.absolutePath

    /* ------------------------------------------------------------------------- input ---- */

    /**
     * Back belongs to the port - it opens the menu while the game runs and exits the app before it
     * does - and it is taken here rather than left to the framework so that **every** Back reaches it:
     * a key event goes through the activity before the WebView can hand it to the page, which is what a
     * device report of "the physical Back button does not open the menu" (an AYN Odin 3) looks like from
     * the inside. `onBackInvokedDispatcher` (registered in `onCreate`) covers the gesture on API 33+,
     * and this covers the key on every version. Each path says which one it was in the record, so a
     * report like that can be told apart from a Back that never arrived at all.
     *
     * The two paths must not both act on one press: on API 33+, where the callback is registered, the
     * framework routes the Back key to it as well as the gesture, and handling the key here too ran
     * [handleBack] twice and opened the menu only to close it again. So from 33 up the callback owns
     * Back and the key is only swallowed; below 33 there is no callback and the key is all there is
     * (verified on an API 36 device: one `input keyevent 4` produced both a dispatcher and a key line).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            /* Alabaster Droid: the key is acted on at every API level. alabaster-android left it to
             * the back dispatcher from API 33 up, but the system only calls that when the app has
             * opted in to it, which is the default only for targetSdk 36 apps on Android 16: on
             * Android 13 (a Retroid Pocket Nova) neither path ran and Back did nothing. Where both
             * paths do deliver one press, [backPressed] acts on the first and drops the second. */
            if (event.action == KeyEvent.ACTION_UP) backPressed("key")
            return true
        }
        if (isGamepad(event.source)) {
            padView?.noteExternalInput()
            Gamepad.onKey(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> pad")
            }
            return true
        }
        /* A hardware key the player pressed is the same "external input" as a controller. The volume
         * keys are system keys, not playing, so they must not hide the pad. */
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            padView?.noteExternalInput()
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> page (scan ${event.scanCode})")
            }
        }
        /* Chromium derives `event.code` from the scan code, and the engine matches its bindings on
         * `code` alone. A key event that carries no scan code therefore reaches the page as
         * `code: ""` and plays nothing, so the port dispatches the DOM event the page would have
         * got; anything the port cannot name as a DOM key is left to the WebView as before. */
        if (event.scanCode == 0) {
            val keyEvent = domKeyEvent(event)
            if (keyEvent != null) {
                webView?.evaluateJavascript(keyEvent, null)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The `keydown`/`keyup` the page should have received for [event], or null when the Android key
     * code has no DOM name.
     *
     * `code` is the physical key and is what Alabaster Dawn's `terra` engine binds on; `key` is
     * carried because the engine reads it for a few never-ignored keys. Impact-era engines bind on the
     * *legacy* `keyCode`/`which` instead - CrossCode's Cubic Impact 0.5 does - and the
     * `KeyboardEvent` constructor refuses to set those, so they are defined onto the event after it is
     * built. Without them the event reached CrossCode and its title screen ignored it (measured on the
     * S22 Ultra, 2026-10-04: `key KEYCODE_ENTER -> page` in the record, no effect on screen).
     */
    private fun domKeyEvent(event: KeyEvent): String? {
        val (code, key) = when (val k = event.keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                "Key" + ('A' + k - KeyEvent.KEYCODE_A) to ('a' + k - KeyEvent.KEYCODE_A).toString()

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                "Digit" + (k - KeyEvent.KEYCODE_0) to (k - KeyEvent.KEYCODE_0).toString()

            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp" to "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown" to "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft" to "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight" to "ArrowRight"
            KeyEvent.KEYCODE_ENTER -> "Enter" to "Enter"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter" to "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape" to "Escape"
            KeyEvent.KEYCODE_SPACE -> "Space" to " "
            KeyEvent.KEYCODE_TAB -> "Tab" to "Tab"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft" to "Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight" to "Shift"
            KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft" to "Control"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight" to "Control"
            KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft" to "Alt"
            KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight" to "Alt"

            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                "F" + (k - KeyEvent.KEYCODE_F1 + 1) to "F" + (k - KeyEvent.KEYCODE_F1 + 1)

            else -> return null
        }
        val type = if (event.action == KeyEvent.ACTION_UP) "keyup" else "keydown"
        val repeat = if (event.repeatCount > 0) "true" else "false"
        val legacy = legacyKeyCode(code)
        val legacyJs = if (legacy > 0) {
            "try{Object.defineProperty(e,'keyCode',{get:function(){return $legacy}});" +
                "Object.defineProperty(e,'which',{get:function(){return $legacy}});}catch(x){}"
        } else {
            ""
        }
        return "(function(){var e=new KeyboardEvent('$type',{code:'$code',key:'$key'," +
            "bubbles:true,cancelable:true,repeat:$repeat});$legacyJs" +
            "window.dispatchEvent(e);})()"
    }

    /**
     * The legacy `keyCode`/`which` a DOM `code` stands for, or 0 when it has none. These are the
     * pre-`code` numbers (`Enter` 13, `Space` 32, arrows 37-40, `KeyA` 65, `Digit0` 48, `F1` 112).
     */
    private fun legacyKeyCode(code: String): Int = when {
        code.length == 4 && code.startsWith("Key") && code[3] in 'A'..'Z' -> code[3].code
        code.startsWith("Digit") && code.length == 6 -> '0'.code + (code[5] - '0')
        code.length in 2..3 && code[0] == 'F' && code.drop(1).toIntOrNull() in 1..12 ->
            111 + code.drop(1).toInt()
        code == "ArrowLeft" -> 37
        code == "ArrowUp" -> 38
        code == "ArrowRight" -> 39
        code == "ArrowDown" -> 40
        code == "Enter" || code == "NumpadEnter" -> 13
        code == "Escape" -> 27
        code == "Space" -> 32
        code == "Tab" -> 9
        code.startsWith("Shift") -> 16
        code.startsWith("Control") -> 17
        code.startsWith("Alt") -> 18
        else -> 0
    }

    /** Mouse movement and clicks hide the pad exactly like a controller; the event is not consumed. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (OnScreenPadView.isPointerInput(event)) padView?.noteExternalInput()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepad(event.source)) {
            padView?.noteExternalInput()
            Gamepad.onMotion(event)
            /* Alabaster Droid: alabaster-android logged the axes here on every stick movement, which
             * built and re-parsed the pad's JSON and wrote a log line per motion event on the UI
             * thread (about 12 % of a core while a stick moved). The game needs every core it can
             * get, so that debug log is left out. */
            return true
        }
        /* Hover does not reach dispatchTouchEvent, so it is noted here; a click with no preceding
         * movement is noted there instead. */
        if (OnScreenPadView.isPointerInput(event)) padView?.noteExternalInput()
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    /* ------------------------------------------------------------------- lifecycle ---- */

    /**
     * The engine stops itself on the page's `blur` (it suspends its AudioContext, makes the run
     * loop return before any update or draw, and clears held inputs) and starts again on `focus`.
     * An Android WebView dispatches neither when the app leaves the foreground, so the music and
     * the loop survive behind the launcher: measured on the SM-S908U1, the AudioTrack stayed
     * `active` and the renderer kept ~20% CPU. Dispatched here instead.
     */
    private fun setPageFocus(focused: Boolean) {
        webView?.evaluateJavascript(if (focused) PAGE_FOCUS_JS else PAGE_BLUR_JS, null)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        webView?.let {
            it.resumeTimers()
            it.onResume()
            it.requestFocus()
        }
        setPageFocus(true)
        engineActive = true
    }

    override fun onPause() {
        engineActive = false
        /* An edit in progress is saved when the app goes to the background, so it is not lost if
         * the process is killed. */
        padView?.commitIfEditing()
        /* Blur before the timers stop, or the AudioContext suspension waits for the app to come
         * back. pauseTimers() then covers the loop the engine drives with setInterval (an fps
         * below 60) and any other page timer. */
        setPageFocus(false)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    /**
     * The single way out of the game: the side menu's Exit and the engine's own quit both land here.
     *
     * The WebView renderer is shared for the life of the app process, and starting the game a second
     * time in the same process was measured to come up black (README "Not in this milestone"); the
     * documented workaround was to swipe the app away from recents. This is that workaround: tear the
     * WebView down, send the task to the back, then end the process, so the next launch is a new
     * process, a new renderer and a clean GL state. `Process.killProcess` is a deliberate exit, not a
     * crash - no dialog.
     *
     * The task is *not* finished. On Android the last activity of a task finishing removes the task
     * from recents (`finishAndRemoveTask` does it explicitly, and plain `finish` does it too), which
     * is why the app disappeared from the task list after an Exit; ending the process alone leaves
     * the recents entry in place, and the system starts a fresh process for it when the user taps it.
     */
    private fun exitGame() {
        diag.line("exit requested")
        flushLogBlocking()
        menuView?.close()
        teardownWebView()
        /* Back to the launcher, which lives in the app's main process; this one ends in onDestroy. */
        exiting = true
        finish()
    }

    /** The last record before the process ends: [writeLogAsync]'s thread would not survive the kill. */
    private fun flushLogBlocking() {
        if (!previousRecordRead || !logToSaves || !logDirty) return
        val store = saveStore ?: return
        logDirty = false
        try {
            store.write(LogFile.FILE, diag.snapshot(diagFacts()))
        } catch (e: Exception) {
            Log.w(TAG, "final ${LogFile.FILE} write failed", e)
        }
    }

    /**
     * The one WebView teardown. `destroy()` is only valid once the view has left the view tree, which
     * the old `onDestroy` did not do - it destroyed the WebView while it was still the content view.
     */
    private fun teardownWebView() {
        val view = webView ?: return
        webView = null
        try {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.loadUrl("about:blank")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed", e)
        }
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        teardownWebView()
        padView = null
        menuView = null
        super.onDestroy()
        if (exiting) Process.killProcess(Process.myPid())
    }

    /** A tap must restore audio if the WebView refused to start it without a gesture. */
    private fun resumeAudio() {
        setPageFocus(true)
    }

    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun webViewInspectable(): Boolean = isDebuggable() || try {
        Settings.Global.getInt(contentResolver, DEBUG_WEBVIEW_SETTING, 0) != 0
    } catch (e: Exception) {
        false
    }

    private fun readAsset(name: String): String = try {
        assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read asset $name", e)
        ""
    }

    companion object {
        private const val TAG = "RfPort"
        private const val PREF = "ada"
        /* The old single-folder key: read once at startup and then filed under its game's key. */
        private const val KEY_GAME = "game_tree_uri"
        /* The game the entry screen last pointed at: what auto-start starts. */
        private const val KEY_LAST_GAME = "last_game"
        /* Whether the entry screen goes straight into the last game instead of showing the cards. */
        private const val KEY_AUTO_START = "auto_start_last"
        private const val MENU_SELECT_GAME = 1
        private const val MENU_SELECT_SAVES = 2
        private const val MENU_HELP = 3
        /* The extra a shortcut carries: the GameProfile.id of the game it opens. */
        private const val EXTRA_GAME = "game"
        private const val MENU_LINK = 4
        /* A card's art tile: a square, so the crop is square too (see decodeArt). */
        private const val TILE_DP = 72
        /* The round play badge on a card, and the tap target of its three-dot folder menu. */
        private const val PLAY_DP = 44
        private const val DOTS_DP = 32
        /* The global setting that turns WebView inspection on for a released build. */
        private const val DEBUG_WEBVIEW_SETTING = "rfport_webview_debug"
        /* The single saves folder versions before per-game folders used; read once, in the migration. */
        private const val KEY_SAVES = "saves_tree_uri"

        /* Whether the on-screen pad draws its controls. */
        private const val KEY_PAD = "on_screen_pad"
        /* Whether the two sticks start where the screen half is touched (the side menu's switch). */
        private const val KEY_DYNAMIC_STICKS = "dynamic_sticks"
        /* Whether external input (controller, mouse or keyboard) hides the on-screen overlay (the
         * side menu's switch). The stored key string is kept as-is so installs keep their setting. */
        private const val KEY_HIDE_EXTERNAL_INPUT = "hide_with_controller"
        /* Where the picture sits vertically: a ViewAlign.wire value. */
        private const val KEY_VIEW_ALIGN = "view_align"
        /* Whether the next start of the game should drop the game's stored Resolution option: set
         * from the side menu's Reset resolution row, or from the pre-game screen where there is no
         * page to reload yet, and cleared as the URL that carries it to the shim is built. */
        private const val KEY_RESET_VIDEO = "reset_video"
        /* Whether the shim draws its frame-rate/resolution/battery/thermal readout. */
        private const val KEY_STATS = "stats_overlay"
        /* Whether the shim caps the page's frame rate, for battery, and at what (see [FpsLimit]). */
        private const val KEY_LIMIT_FPS = "limit_fps"
        private const val KEY_FPS_LIMIT = "fps_limit"
        private const val REQ_GAME = 101
        private const val REQ_SAVES = 102
        private const val BRIDGE_NAME = "PortBridge"
        /** Two reports of Back closer together than this are one press (see [backPressed]). */
        private const val BACK_REPEAT_MS = 300L
        /** The saves folder inside the install folder, beside `terra/` and never inside it. */
        const val SAVES_DIR = "userdata"

        /* Diagnostics: how long the page's frame clock may go quiet before it is reported, how often
         * that is repeated, how long an asset read may be unfinished first, and the poll interval. */
        private const val SILENT_MS = 5000L
        private const val SILENT_LOG_MS = 10000L
        private const val STUCK_MS = 5000L
        private const val WATCHDOG_TICK_MS = 2000L

        /* The engine's only pause/resume entry point (see setPageFocus). A plain non-bubbling
         * Event is all it takes: both listeners sit on `window` itself. */
        private const val PAGE_BLUR_JS = "window.dispatchEvent(new Event('blur'))"
        private const val PAGE_FOCUS_JS = "window.dispatchEvent(new Event('focus'))"

        /**
         * ANGLE Preferences, under both of the names its apk ships with: AOSP's `com.android.angle`
         * and Google's `com.google.android.angle` (the activity is `MainActivity` in both).
         */
        private val ANGLE_PREFERENCES = listOf(
            ComponentName("com.android.angle", "com.android.angle.MainActivity"),
            ComponentName("com.google.android.angle", "com.google.android.angle.MainActivity"),
        )
    }
}
