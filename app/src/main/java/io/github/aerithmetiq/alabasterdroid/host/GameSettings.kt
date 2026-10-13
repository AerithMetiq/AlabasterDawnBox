package io.github.aerithmetiq.alabasterdroid.host

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/**
 * The game's settings, where the launcher and the game both find them. Alabaster Droid's own, not from
 * alabaster-android: upstream keeps these keys private to its activity, and here the launcher's
 * Settings screen edits the same values the in-game side menu does.
 *
 * The game runs in its own process, so the file has two readers. Each side opens it afresh through
 * [prefs] (which re-reads the file when the other process has written it) and the launcher writes
 * with `commit`, so a change made on one side is what the other sees next.
 */
object GameSettings {

    const val FILE = "ada"

    /** Whether the touch controls exist at all: off removes the pad and its buttons from the screen. */
    const val KEY_TOUCH_CONTROLS = "touch_controls"
    /** Whether the on-screen pad draws its controls (its own PAD/HIDE button). */
    const val KEY_PAD = "on_screen_pad"
    /** Whether the two sticks start where the screen half is touched. */
    const val KEY_DYNAMIC_STICKS = "dynamic_sticks"
    /** Whether a controller, mouse or keyboard in use hides the touch controls. The key string is upstream's. */
    const val KEY_HIDE_EXTERNAL_INPUT = "hide_with_controller"
    /** Where the picture sits vertically: a [ViewAlign.wire] value. */
    const val KEY_VIEW_ALIGN = "view_align"
    /** Whether the frame-rate / resolution / battery / temperature readout is drawn. */
    const val KEY_STATS = "stats_overlay"
    /** Whether the frame rate is capped, and at what (see [FpsLimit]). */
    const val KEY_LIMIT_FPS = "limit_fps"
    const val KEY_FPS_LIMIT = "fps_limit"

    /**
     * The game's Resolution option. The game keeps it in the page's own storage, which only the page
     * can reach, so the launcher cannot set it directly: it leaves the chosen rung in
     * [KEY_RESOLUTION_NEXT] (an index into [RESOLUTIONS], -1 for none), the game's next start carries
     * it to the shim in the URL ([RESOLUTION_PARAM]) and clears it, and the shim stores it before the
     * engine boots. [KEY_RESOLUTION_LAST] is what the engine reported rendering at, last time it ran.
     */
    const val KEY_RESOLUTION_NEXT = "resolution_next"
    const val KEY_RESOLUTION_LAST = "resolution_last"
    const val RESOLUTION_PARAM = "adaPixelSize"

    /** The rungs of the Resolution option, as the host serves it and the game's own options list
     *  them (see GameAssetHandler's ladder); the launcher offers the same five. */
    val RESOLUTIONS = listOf("640x360", "960x540", "1280x720", "1920x1080", "2560x1440")

    /** The `resolution=1920x1080` in the shim's boot report, or null. */
    fun resolutionIn(bootLine: String): String? = Regex("resolution=(\\d+x\\d+)").find(bootLine)?.groupValues?.get(1)

    /** The rung the launcher's button moves to from what is shown now (a pending choice, else the last known). */
    fun nextResolution(pending: Int, last: String?): Int {
        val current = if (pending in RESOLUTIONS.indices) pending else RESOLUTIONS.indexOf(last)
        return (current + 1) % RESOLUTIONS.size
    }

    fun resolutionLabel(pending: Int, last: String?): String = when {
        pending in RESOLUTIONS.indices -> RESOLUTIONS[pending] + " (from the next start)"
        last != null -> last
        else -> "the game's own"
    }

    @Suppress("DEPRECATION")
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    /** The refresh rates the screen offers at the size it is running at now, in whole Hz. */
    fun screenRefreshRates(activity: Activity): List<Int> = try {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        val now = display?.mode
        display?.supportedModes.orEmpty()
            .filter { now == null || (it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight) }
            .map { Math.round(it.refreshRate) }.distinct().sorted()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The frame-rate cap as one value, the way the launcher shows it: [FpsLimit.OFF], or a rate.
     * [next] is the value after it in the cycle Off, then each rate the screen can show.
     */
    fun fpsCap(prefs: SharedPreferences, choices: List<Int>): Int =
        if (!prefs.getBoolean(KEY_LIMIT_FPS, false)) FpsLimit.OFF
        else FpsLimit.normalize(prefs.getInt(KEY_FPS_LIMIT, FpsLimit.DEFAULT), choices)

    fun next(cap: Int, choices: List<Int>): Int {
        if (cap == FpsLimit.OFF) return choices.first()
        val index = choices.indexOf(cap)
        return if (index < 0 || index == choices.size - 1) FpsLimit.OFF else choices[index + 1]
    }
}
