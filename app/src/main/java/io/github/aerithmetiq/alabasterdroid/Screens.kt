package io.github.aerithmetiq.alabasterdroid

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Building blocks for the secondary screens, so they share the launcher's look without a layout each. */
object Screens {

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** A full-screen column on the launcher's gradient, scrolling when it is taller than the screen. */
    fun page(context: Context, build: LinearLayout.() -> Unit): View {
        val pad = dp(context, 24)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + dp(context, 20), pad, pad + dp(context, 20), pad)
            build()
        }
        return ScrollView(context).apply {
            isFillViewport = true
            /* Clear of the status and navigation bars on edge-to-edge Android (15+). */
            fitsSystemWindows = true
            setBackgroundResource(R.drawable.launcher_bg)
            addView(column)
        }
    }

    fun title(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.SERIF
        letterSpacing = 0.08f
        setTextColor(ContextCompat.getColor(context, R.color.alabaster))
    }

    fun body(context: Context, text: String, topDp: Int = 8) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dp(context, topDp), 0, 0)
    }

    /** The launcher's filled accent button. */
    fun primary(context: Context, text: String, onClick: () -> Unit) =
        MaterialButton(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.on_accent))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.accent)
            cornerRadius = dp(context, 12)
            strokeColor = ContextCompat.getColorStateList(context, R.color.focus_ring)
            strokeWidth = dp(context, 2)
            minHeight = dp(context, 50)
            setOnClickListener { onClick() }
        }

    /** The launcher's outlined button. */
    fun outlined(context: Context, text: String, onClick: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            cornerRadius = dp(context, 12)
            strokeColor = ContextCompat.getColorStateList(context, R.color.focus_ring_outline)
            strokeWidth = dp(context, 1)
            rippleColor = ContextCompat.getColorStateList(context, R.color.accent)
            minHeight = dp(context, 46)
            setOnClickListener { onClick() }
        }

    /** A row of buttons. */
    fun row(context: Context, topDp: Int = 14, vararg buttons: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(context, topDp), 0, 0)
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(context, 10)
            })
        }
    }

    /** Read-only, selectable monospace text, for reports and notices. */
    fun mono(context: Context, text: String) = TextView(context).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setTextIsSelectable(true)
        setPadding(0, dp(context, 12), 0, 0)
    }
}
