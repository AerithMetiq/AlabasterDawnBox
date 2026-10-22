/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Only the package name is changed.
 */
package io.github.aerithmetiq.alabasterdawnbox.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewAlignTest {

    @Test
    fun `every position keeps the wire value the page and the pref read`() {
        assertEquals("top", ViewAlign.TOP.wire)
        assertEquals("center", ViewAlign.CENTER.wire)
        assertEquals("bottom", ViewAlign.BOTTOM.wire)
    }

    @Test
    fun `a wire value round-trips`() {
        for (value in ViewAlign.entries) assertSame(value, ViewAlign.fromWire(value.wire))
    }

    @Test
    fun `a missing or unknown pref keeps the picture centred`() {
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire(null))
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire(""))
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire("middle"))
    }
}
