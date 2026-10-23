package io.github.aerithmetiq.alabasterdawnbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnToTest {

    private val self = "io.github.aerithmetiq.alabasterdawnbox"
    private val home = "com.android.launcher3"
    private val frontend = "org.es_de.frontend"

    @Test
    fun `a start from the home screen goes back home`() {
        val opener = ReturnTo.opener(home, self, null)
        assertNull(ReturnTo.target(opener, self, home))
    }

    @Test
    fun `a start from a frontend goes back to the frontend`() {
        val opener = ReturnTo.opener(frontend, self, home)
        assertEquals(frontend, ReturnTo.target(opener, self, home))
    }

    @Test
    fun `the app's own notification keeps the opener`() {
        assertEquals(frontend, ReturnTo.opener(self, self, frontend))
        assertNull(ReturnTo.opener(self, self, null))
    }

    @Test
    fun `an unknown starter goes home`() {
        val opener = ReturnTo.opener(null, self, frontend)
        assertNull(ReturnTo.target(opener, self, home))
    }

    @Test
    fun `this app as the opener goes home`() {
        assertNull(ReturnTo.target(self, self, home))
    }
}
