package io.github.aerithmetiq.alabasterdawnbox

/**
 * Where leaving the launcher with Back (B or the gesture) goes: the app that opened it, not the next
 * task in Android's order. Android's own Back on a task's root moves the task to the back, which
 * shows whatever task is under it; after a switch through recents that is another app, not the home
 * screen, so leaving took a second press of B (AerithMetiq, 2026-10-22, with both Box apps open).
 *
 * Pure, so the rule is unit-tested; the launcher supplies the package names.
 */
object ReturnTo {

    /**
     * The opener to keep after a start by [starter] (the starting package, from `Activity.referrer`).
     * A start by this app itself (its download notification) keeps the [previous] opener; an unknown
     * starter forgets it.
     */
    fun opener(starter: String?, self: String, previous: String?): String? = when (starter) {
        self -> previous
        else -> starter
    }

    /**
     * The package to bring forward on Back, or null for the home screen: the home screen when the
     * opener is unknown, this app, or the home screen app itself.
     */
    fun target(opener: String?, self: String, home: String?): String? =
        if (opener == null || opener == self || opener == home) null else opener
}
