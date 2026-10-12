package io.github.aerithmetiq.alabasterdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeFitTest {

    /** The code, its border and the text column must fit the dialog on every shape. */
    private fun assertFits(w: Int, h: Int) {
        val fit = QrCode.fit(w, h)
        val content = fit.dialogDp - 2 * 24 - 2 * fit.padDp
        assertTrue("dialog fits the screen at ${w}x$h", fit.dialogDp <= w - 32)
        assertTrue("code leaves half the row to the text at ${w}x$h", fit.codeDp <= (content - 16) / 2 || fit.codeDp == 96)
        assertTrue("code is scannable at ${w}x$h", fit.codeDp >= 96)
    }

    @Test
    fun handheldsAndPhonesOfEveryShape() {
        listOf(
            427 to 320,   // 4:3, 640x480 @1.5x
            512 to 384,   // 4:3, 1024x768 @2x (Retroid-class)
            640 to 360,   // 16:9 phone
            738 to 415,   // 16:9 handheld (AYN-class)
            873 to 393,   // 21:9 phone
            1024 to 768,  // 4:3 tablet
        ).forEach { (w, h) -> assertFits(w, h) }
    }

    @Test
    fun fourByThreeHandheld() {
        assertEquals(QrCode.Fit(dialogDp = 480, codeDp = 192, padDp = 16), QrCode.fit(512, 384))
    }

    @Test
    fun largeScreensCapTheCode() {
        assertEquals(240, QrCode.fit(1280, 800).codeDp)
    }
}
