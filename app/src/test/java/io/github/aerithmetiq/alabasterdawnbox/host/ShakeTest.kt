package io.github.aerithmetiq.alabasterdawnbox.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShakeTest {

    @Test
    fun `a shake's length and strength become a vibration`() {
        assertEquals(Shake(200, 127), Shake.of(200, 5f))
        assertEquals("STRONGEST and MEGA are full strength", Shake(75, 255), Shake.of(75, 15f))
        assertEquals("a faint shake can still be felt", Shake(30, 40), Shake.of(20, 0.5f))
        assertEquals("long shakes are capped", 600, Shake.of(2000, 3f)!!.ms)
        assertEquals("open-ended shakes are one short pulse", 150, Shake.of(-1, 4f)!!.ms)
    }

    @Test
    fun `no strength, nothing to feel`() {
        assertNull(Shake.of(200, 0f))
        assertNull(Shake.of(200, Float.NaN))
    }
}
