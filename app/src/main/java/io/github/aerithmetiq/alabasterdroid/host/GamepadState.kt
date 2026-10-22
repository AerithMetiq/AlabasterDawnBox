/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Changed: the package name; [batch] publishes a
 * controller event's several changes once, and [publish] writes the payload itself instead of
 * building it from org.json objects (same JSON, and a non-finite number is sent as 0).
 */
package io.github.aerithmetiq.alabasterdroid.host

import kotlin.math.abs

/**
 * Controller state in the W3C "standard gamepad" layout the engine's `GAMEPAD_MAPPING_STANDARD`
 * expects: axes 0..3 = left X/Y, right X/Y (positive = right/down) and 17 buttons in the standard
 * order, D-pad at 12..15.
 *
 * Two sources feed it — the physical pad ([Gamepad] translates `KeyEvent`/`MotionEvent`) and the
 * on-screen pad ([overlayButton]/[overlayAxes], driven by [OnScreenPadView]) — and [publish] merges
 * them into one payload, so the engine always sees a single standard pad.
 *
 * Deliberately free of Android types, so the layout contract can be unit-tested on the JVM.
 *
 * [json] is called from the JavaScript bridge thread while input events arrive on the main thread.
 * The arrays are only touched by the input thread, which finishes building the payload before
 * publishing it through the volatile field the reader sees. The on-screen pad's setters run on the
 * main thread too, from the View's `onTouchEvent`, so that contract is unchanged.
 */
class GamepadState {

    private val axes = FloatArray(AXES)
    private val keyDown = BooleanArray(BUTTONS)
    private val hatDown = BooleanArray(DPAD_LAST - DPAD_FIRST + 1) // up, down, left, right
    private val trigger = FloatArray(BUTTONS)

    private val overlayKeyDown = BooleanArray(BUTTONS)
    private val overlayAxis = FloatArray(AXES)
    private var overlayEnabled = false

    @Volatile
    private var payload: String = ""

    @Volatile
    private var seen = false

    /* Alabaster Droid: set while [batch] runs, so the changes inside it publish once, at its end. */
    private var batching = false

    /* Alabaster Droid: the payload is written here and copied out; only the input thread touches it. */
    private val out = StringBuilder(PAYLOAD_CHARS)

    /**
     * `""` until a controller has been seen, so the shim can report no pads at all. An enabled
     * overlay is a pad in its own right: it must publish (all-neutral until a control is touched)
     * so the engine switches to its pad input device. Only hardware marks a controller as seen —
     * the overlay is a second source, not a second pad.
     */
    fun json(): String = if (seen || overlayEnabled) payload else ""

    fun press(button: Int) = setKey(button, true)

    fun release(button: Int) = setKey(button, false)

    /**
     * Alabaster Droid: applies [changes] and publishes once. One stick event sets the axes, both
     * triggers and the hat, and publishing after each of the four built the payload four times on the
     * UI thread for every motion event.
     */
    fun batch(changes: GamepadState.() -> Unit) {
        batching = true
        try {
            changes()
        } finally {
            batching = false
        }
        publish()
    }

    fun setAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        axes[0] = leftX
        axes[1] = leftY
        axes[2] = rightX
        axes[3] = rightY
        seen = true
        changed()
    }

    fun setTrigger(button: Int, value: Float) {
        trigger[button] = value.coerceIn(0f, 1f)
        seen = true
        changed()
    }

    fun setHat(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        hatDown[0] = up
        hatDown[1] = down
        hatDown[2] = left
        hatDown[3] = right
        seen = true
        changed()
    }

    private fun setKey(button: Int, down: Boolean) {
        keyDown[button] = down
        seen = true
        changed()
    }

    /**
     * The on-screen pad's switch. Disabling clears its state before publishing, so a finger held
     * down when the pad disappears cannot leave a stuck button or a displaced stick behind.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        if (!enabled) {
            overlayKeyDown.fill(false)
            overlayAxis.fill(0f)
        }
        changed()
    }

    /** A stray overlay event after the toggle cannot resurrect state: ignored while disabled. */
    fun overlayButton(button: Int, down: Boolean) {
        if (!overlayEnabled || button < 0 || button >= BUTTONS) return
        overlayKeyDown[button] = down
        changed()
    }

    fun overlayAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        if (!overlayEnabled) return
        overlayAxis[0] = leftX.coerceIn(-1f, 1f)
        overlayAxis[1] = leftY.coerceIn(-1f, 1f)
        overlayAxis[2] = rightX.coerceIn(-1f, 1f)
        overlayAxis[3] = rightY.coerceIn(-1f, 1f)
        changed()
    }

    private fun changed() {
        if (!batching) publish()
    }

    /** `{"axes":[lx,ly,rx,ry],"buttons":[{"pressed":false,"value":0.0},...]}`, the shim's wire format. */
    private fun publish() {
        val json = out
        json.setLength(0)
        json.append("{\"axes\":[")
        for (axis in 0 until AXES) {
            /* Whichever source is deflected further wins: a neutral overlay must not mask a held
             * physical stick, and a centred physical stick must not cancel an overlay one. */
            val physical = axes[axis]
            val overlay = overlayAxis[axis]
            if (axis > 0) json.append(',')
            number(json, if (abs(overlay) > abs(physical)) overlay else physical)
        }
        json.append("],\"buttons\":[")
        for (button in 0 until BUTTONS) {
            /* A digital press reads as {pressed: true, value: 1}; a trigger keeps its analogue value
             * and counts as pressed past halfway, which is what the engine's deadzones expect. */
            val digital = keyDown[button] || hatPressed(button) || overlayKeyDown[button]
            val analog = trigger[button]
            if (button > 0) json.append(',')
            json.append("{\"pressed\":").append(digital || analog > 0.5f).append(",\"value\":")
            number(json, if (digital) 1f else analog)
            json.append('}')
        }
        payload = json.append("]}").toString()
    }

    /** The value as org.json wrote it (a float widened to double); JSON has no NaN or infinity, so those are 0. */
    private fun number(json: StringBuilder, value: Float) {
        if (value.isFinite()) json.append(value.toDouble()) else json.append('0')
    }

    /** Buttons 12..15 are the D-pad, fed both by key events and by the hat axis. */
    private fun hatPressed(button: Int): Boolean {
        if (button < DPAD_FIRST || button > DPAD_LAST) return false
        return hatDown[button - DPAD_FIRST]
    }

    companion object {
        const val BUTTONS = 17
        const val AXES = 4
        const val DPAD_FIRST = 12
        const val DPAD_LAST = 15

        /* Room for the whole payload: four axes and 17 buttons at their longest. */
        private const val PAYLOAD_CHARS = 1024
    }
}
