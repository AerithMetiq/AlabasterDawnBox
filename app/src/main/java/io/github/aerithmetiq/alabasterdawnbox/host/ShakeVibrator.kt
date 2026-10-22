package io.github.aerithmetiq.alabasterdawnbox.host

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * AlabasterDawnBox's own (shared with CrossCodeBox): the game's rumble as vibration. Alabaster Dawn
 * rumbles through the controller's `vibrationActuator` (a "dual-rumble" effect whose magnitudes already
 * follow the game's rumble option), which `ada-shim.js` gives its pad and passes on here with the
 * effect's length and its stronger magnitude times 10. [Shake] turns that into a vibration.
 */
class ShakeVibrator(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }

    private var lastAt = 0L
    private var lastUntil = 0L
    private var lastAmplitude = 0

    /** Called on the JavaBridge thread; the system's vibrator is thread-safe. */
    @Synchronized
    fun vibrate(ms: Int, strength: Float) {
        val v = vibrator ?: return
        val shake = Shake.of(ms, strength) ?: return
        val now = SystemClock.uptimeMillis()
        /* Shakes come in bursts: a weaker one while a stronger one still runs adds nothing. */
        if (now < lastUntil && shake.amplitude <= lastAmplitude) return
        if (now - lastAt < Shake.MIN_GAP_MS && shake.amplitude <= lastAmplitude) return
        lastAt = now
        lastUntil = now + shake.ms
        lastAmplitude = shake.amplitude
        val amplitude = if (v.hasAmplitudeControl()) shake.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = VibrationEffect.createOneShot(shake.ms.toLong(), amplitude)
        /* As game media, not touch feedback: the touch-haptics setting must not silence the game. */
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            }
        }
    }
}

/** One vibration: [ms] long at [amplitude] (1-255). Pure, so the mapping is unit-tested. */
data class Shake(val ms: Int, val amplitude: Int) {
    companion object {
        const val MIN_GAP_MS = 40L
        private const val OPEN_ENDED_MS = 150
        private const val MAX_MS = 600
        private const val MIN_MS = 30

        /**
         * A game shake [ms] long (-1: until the game stops it, felt as one short pulse) with the game's
         * [strength] (its SHAKE_POWER, 0.5 to 15; 0 with rumble off). Null for nothing to feel.
         */
        fun of(ms: Int, strength: Float): Shake? {
            if (!(strength > 0f)) return null
            val length = if (ms < 0) OPEN_ENDED_MS else ms.coerceIn(MIN_MS, MAX_MS)
            val amplitude = (strength / 10f * 255f).toInt().coerceIn(40, 255)
            return Shake(length, amplitude)
        }
    }
}
