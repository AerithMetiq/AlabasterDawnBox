package io.github.aerithmetiq.alabasterdroid

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** The Steam sign-in QR code: its size for the screen, and its bitmap. */
object QrCode {

    /** The dialog and code sizes, in dp, for one screen. */
    data class Fit(val dialogDp: Int, val codeDp: Int, val padDp: Int)

    /**
     * Sizes the sign-in dialog from the window it has, so the code is never clipped on any aspect
     * ratio (4:3 handhelds included): the code takes at most half the row beside its instructions and
     * whatever fits vertically, 96..240 dp.
     */
    fun fit(screenWidthDp: Int, screenHeightDp: Int): Fit {
        val pad = if (screenWidthDp < 600) 16 else 24
        val dialog = minOf(screenWidthDp - 32, 600)
        val content = dialog - 2 * 24 - 2 * pad
        val gap = 16
        val heightBudget = screenHeightDp - (56 + 2 * 24 + 2 * pad + 16)
        val code = minOf((content - gap) / 2, heightBudget, 240).coerceAtLeast(96)
        return Fit(dialog, code, pad)
    }

    fun fit(config: Configuration): Fit = fit(config.screenWidthDp, config.screenHeightDp)

    fun bitmap(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
