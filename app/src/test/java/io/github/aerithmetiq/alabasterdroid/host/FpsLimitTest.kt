/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Changed for Alabaster Droid with FpsLimit: the rates
 * are 30/45/60/90/120, and the per-screen choices and refresh rate are tested.
 */
package io.github.aerithmetiq.alabasterdroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side menu's frame-rate cap. The slider offers the rates the screen can show and the app stores
 * one of them; the bridge sends the rate (or [FpsLimit.OFF] with the switch off) and the shim's gate
 * turns it into an interval. What matters here is that the two sides of the slider - the position
 * and the rate - always agree, including for a value another build wrote into the preferences.
 */
class FpsLimitTest {

    @Test
    fun `the slider's five positions map to the five rates, in menu order`() {
        assertEquals(listOf(30, 45, 60, 90, 120), FpsLimit.CHOICES)
        for (index in FpsLimit.CHOICES.indices) {
            assertEquals(FpsLimit.CHOICES[index], FpsLimit.at(index))
            assertEquals("round trip at $index", index, FpsLimit.indexOf(FpsLimit.at(index)))
        }
    }

    @Test
    fun `a screen is offered only the rates it can show`() {
        assertEquals("a Retroid Pocket Nova", listOf(30, 45, 60), FpsLimit.choicesFor(listOf(60)))
        assertEquals(listOf(30, 45, 60, 90), FpsLimit.choicesFor(listOf(60, 90)))
        assertEquals(listOf(30, 45, 60, 90, 120), FpsLimit.choicesFor(listOf(60, 90, 120)))
        assertEquals("59.94 Hz rounds to 60; a 119 Hz mode still shows 120", listOf(30, 45, 60, 90, 120), FpsLimit.choicesFor(listOf(60, 119)))
        assertEquals("nothing reported: assume 60 Hz", listOf(30, 45, 60), FpsLimit.choicesFor(emptyList()))
        assertEquals("never empty", listOf(30), FpsLimit.choicesFor(listOf(24)))
    }

    @Test
    fun `the screen runs at the lowest refresh rate the cap divides evenly`() {
        val rates = listOf(60, 90, 120)
        assertEquals(60, FpsLimit.refreshFor(30, rates))
        assertEquals("45 is even only at 90 Hz", 90, FpsLimit.refreshFor(45, rates))
        assertEquals(60, FpsLimit.refreshFor(60, rates))
        assertEquals(90, FpsLimit.refreshFor(90, rates))
        assertEquals(120, FpsLimit.refreshFor(120, rates))
        assertEquals("no cap: as fast as the screen goes", 120, FpsLimit.refreshFor(FpsLimit.OFF, rates))
        assertEquals("45 on a 60/120 screen has no even rate: the highest", 120, FpsLimit.refreshFor(45, listOf(60, 120)))
        assertEquals(60, FpsLimit.refreshFor(45, listOf(60)))
        assertNull(FpsLimit.refreshFor(60, emptyList()))
    }

    @Test
    fun `a stored rate that is not a choice snaps to the nearest one, ties to the lower`() {
        assertEquals("an older build's 20", 30, FpsLimit.normalize(20))
        assertEquals("a tie at 37.5 goes to the lower rate", 30, FpsLimit.normalize(37))
        assertEquals("an older build's 40", 45, FpsLimit.normalize(40))
        assertEquals(45, FpsLimit.normalize(52))
        assertEquals(60, FpsLimit.normalize(53))
        assertEquals("a tie at 75 goes to the lower rate", 60, FpsLimit.normalize(75))
        assertEquals(90, FpsLimit.normalize(76))
        assertEquals(120, FpsLimit.normalize(240))
        assertEquals("nothing stored, nothing chosen", 30, FpsLimit.normalize(FpsLimit.DEFAULT))
        assertEquals("off is not a rate: it reads as unchosen", 30, FpsLimit.normalize(FpsLimit.OFF))
    }

    @Test
    fun `a rate the screen cannot show snaps to one it can`() {
        val nova = FpsLimit.choicesFor(listOf(60))
        assertEquals(60, FpsLimit.normalize(120, nova))
        assertEquals(45, FpsLimit.normalize(45, nova))
        assertEquals(2, FpsLimit.indexOf(90, nova))
        assertEquals(60, FpsLimit.at(9, nova))
    }

    @Test
    fun `a position outside the slider is clamped rather than crashing the menu`() {
        assertEquals(30, FpsLimit.at(-3))
        assertEquals(120, FpsLimit.at(FpsLimit.CHOICES.size + 4))
    }

    @Test
    fun `off is not a rate, and the default is the bottom of the slider`() {
        assertEquals(0, FpsLimit.OFF)
        assertEquals(30, FpsLimit.DEFAULT)
        assertTrue("off is not one of the choices", !FpsLimit.CHOICES.contains(FpsLimit.OFF))
    }
}
