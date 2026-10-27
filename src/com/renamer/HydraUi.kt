package com.renamer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/** Shared glass look for all Hydra pages:
 *  translucent white fill, thin grey rim and
 *  elevation — the same classy glass language as
 *  the v2ray row buttons. Every button on every
 *  Hydra page goes through here, so no page ever
 *  falls back to the flat default button. */
object HydraUi {
    const val PAGE_BG = "#F2F5F9"

    fun glassBg(fill: Int, stroke: Int, radiusDp: Float, ctx: Context): GradientDrawable {
        val d = (ctx.resources.displayMetrics.density * radiusDp).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = d.toFloat()
            setColor(fill)
            setStroke(2, stroke)
        }
    }

    fun glassCircleBg(fill: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(3, stroke)
        }
    }

    fun glassButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            background = glassBg(
                Color.parseColor("#CCFFFFFF"),
                Color.parseColor("#FFBFBFBF"), 28f, ctx)
            setTextColor(Color.BLACK)
            elevation = 6f
            setOnClickListener { onClick() }
        }
    }

    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
