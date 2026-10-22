/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Changed for AlabasterDawnBox: the rates are
 * 30/45/60/90/120 instead of 20/30/40/45/60, a screen is only offered the rates it can show
 * ([choicesFor]), and [refreshFor] picks the screen refresh rate a cap is presented at.
 */
package io.github.aerithmetiq.alabasterdawnbox.host

import kotlin.math.abs

/**
 * The side menu's frame-rate cap: the rates its slider offers and the one the app remembers.
 *
 * The engine drives everything it draws from `requestAnimationFrame`, so the shim serves frames at
 * the capped rate and the game logic keeps advancing by the real elapsed time; only the presents
 * drop. A display can only present on a vsync, so a rate that is a whole division of the panel's
 * refresh is even (30 and 60 on a 60 Hz panel, all but 45 on a 120 Hz one), and one that is not is
 * served as an uneven mix that averages the rate: 45 on a 60 Hz panel is three frames out of every
 * four vsyncs.
 *
 * Five rates, in the order the menu shows them, with 30 as the default: the rate the switch stood for
 * before it had a slider, so a stored choice from an older build keeps its meaning.
 */
object FpsLimit {

    /**
     * What the bridge sends when the switch is off, and what the shim's gate reads as "no cap": its
     * `requestAnimationFrame` wrapper passes every call straight through.
     */
    const val OFF = 0

    /** Every rate the slider can offer, low to high. */
    val CHOICES = listOf(30, 45, 60, 90, 120)

    /** The rate the switch stands for when nothing has been chosen yet. */
    const val DEFAULT = 30

    /**
     * The rates a screen is offered: those it can actually show, given the refresh rates it supports.
     * A 60 Hz screen gets 30/45/60; 90 and 120 would only be labels there. Never empty.
     */
    fun choicesFor(refreshRates: List<Int>): List<Int> {
        val top = refreshRates.maxOrNull() ?: 60
        return CHOICES.filter { it <= top + 1 }.ifEmpty { listOf(CHOICES.first()) }
    }

    /**
     * The refresh rate to run the screen at for a cap: the lowest supported one the cap divides
     * evenly (so 45 gets 90 Hz where there is one, and 30 or 60 do not hold a 120 Hz panel at 120),
     * else the highest. With no cap ([OFF]) it is the highest, so the game runs as fast as it can.
     * Null when the screen reports no rates.
     */
    fun refreshFor(cap: Int, refreshRates: List<Int>): Int? {
        val rates = refreshRates.filter { it > 0 }.sorted()
        if (rates.isEmpty()) return null
        if (cap == OFF) return rates.last()
        return rates.firstOrNull { it >= cap && it % cap == 0 } ?: rates.last()
    }

    /** The rate at slider position [index]; the ends clamp, so a stale index cannot break the menu. */
    fun at(index: Int, choices: List<Int> = CHOICES): Int = choices[index.coerceIn(0, choices.size - 1)]

    /** The slider position of a stored rate: an exact choice, or the nearest one. */
    fun indexOf(fps: Int, choices: List<Int> = CHOICES): Int {
        var best = 0
        var bestGap = Int.MAX_VALUE
        for ((index, choice) in choices.withIndex()) {
            val gap = abs(choice - fps)
            if (gap < bestGap) {
                best = index
                bestGap = gap
            }
        }
        return best
    }

    /**
     * A stored rate as one this slider can show: a value another build wrote (or a hand-edited
     * preference, or a rate this screen cannot show) snaps to the nearest choice rather than leaving
     * the thumb between two positions, and a tie goes to the lower rate. [OFF] is not a rate - it
     * means the switch is off - so it reads as "nothing chosen", the default.
     */
    fun normalize(stored: Int, choices: List<Int> = CHOICES): Int =
        if (stored == OFF) at(indexOf(DEFAULT, choices), choices) else at(indexOf(stored, choices), choices)
}
