/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Only the package name is changed.
 */
package io.github.aerithmetiq.alabasterdawnbox.host

/**
 * Where the side menu puts the game picture inside the window. [wire] is both the pref value and
 * the string the page reads from `PortBridge.getViewAlign()`, so the two sides cannot drift.
 */
enum class ViewAlign(val wire: String) {
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom");

    companion object {
        /** The default, and the fallback for a pref written by an older or hand-edited build. */
        val DEFAULT = CENTER

        fun fromWire(raw: String?): ViewAlign = entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}
