package io.github.aerithmetiq.alabasterdroid.host

import org.junit.Assert.assertEquals
import org.junit.Test

class GameSettingsTest {

    /** The launcher's frame-rate button steps Off, then each rate the screen can show, then Off again. */
    @Test
    fun theFrameRateButtonCyclesThroughOffAndTheScreensRates() {
        val nova = FpsLimit.choicesFor(listOf(60))
        val seen = generateSequence(FpsLimit.OFF) { GameSettings.next(it, nova) }.take(5).toList()
        assertEquals(listOf(FpsLimit.OFF, 30, 45, 60, FpsLimit.OFF), seen)
        assertEquals("a rate this screen does not offer goes back to Off", FpsLimit.OFF, GameSettings.next(120, nova))
    }

    @Test
    fun theResolutionIsReadFromTheBootReport() {
        assertEquals(
            "1920x1080",
            GameSettings.resolutionIn("+6402ms ENGINE boot: complete in 6030ms, 1317 resources; resolution=1920x1080; audio=running"),
        )
        assertEquals(null, GameSettings.resolutionIn("ENGINE boot: resolution=?"))
    }

    @Test
    fun theResolutionButtonStepsFromWhatIsShown() {
        assertEquals("from the last known one", 3, GameSettings.nextResolution(-1, "1280x720"))
        assertEquals("from a pending choice", 3, GameSettings.nextResolution(2, "640x360"))
        assertEquals(4, GameSettings.nextResolution(3, null))
        assertEquals("wraps after 2560x1440", 0, GameSettings.nextResolution(-1, "2560x1440"))
        assertEquals("nothing known: the first rung", 0, GameSettings.nextResolution(-1, null))
        assertEquals("1280x720 (from the next start)", GameSettings.resolutionLabel(2, "1920x1080"))
        assertEquals("1920x1080", GameSettings.resolutionLabel(-1, "1920x1080"))
        assertEquals("the game's own", GameSettings.resolutionLabel(-1, null))
    }
}
