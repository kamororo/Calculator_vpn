package com.renamer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.random.Random

/**
 * Drawn galaxy sky for the calculator background
 * (2.50 "galaxy" design). No photo: a seeded,
 * deterministic star field plus soft nebula
 * blobs, drawn on a transparent base so it
 * layers over the design's dark gradient. Stars
 * are regenerated only when the bounds change,
 * so drawing is cheap.
 */
class CalcGalaxyDrawable(private val density: Float) : Drawable() {

    private data class Star(val x: Float, val y: Float, val r: Float, val alpha: Int)

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars: List<Star> = emptyList()
    private var lastW = 0
    private var lastH = 0

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width()
        val h = bounds.height()
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        if (w <= 0 || h <= 0) {
            stars = emptyList()
            return
        }
        val rnd = Random(1234)
        val count = ((w * h) / 9000).coerceIn(60, 220)
        val list = ArrayList<Star>(count)
        repeat(count) {
            list += Star(
                x = rnd.nextFloat() * w,
                y = rnd.nextFloat() * h,
                r = (0.6f + rnd.nextFloat() * 1.8f) * density,
                alpha = 90 + rnd.nextInt(166)
            )
        }
        stars = list
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        // Soft nebula blobs (drawn, low alpha).
        blobPaint.color = Color.argb(30, 124, 58, 237)
        canvas.drawCircle(w * 0.22f, h * 0.28f, min(w, h) * 0.42f, blobPaint)
        blobPaint.color = Color.argb(24, 37, 99, 235)
        canvas.drawCircle(w * 0.78f, h * 0.62f, min(w, h) * 0.38f, blobPaint)
        blobPaint.color = Color.argb(22, 219, 39, 119)
        canvas.drawCircle(w * 0.62f, h * 0.18f, min(w, h) * 0.30f, blobPaint)
        // Stars.
        starPaint.color = Color.WHITE
        for (s in stars) {
            starPaint.alpha = s.alpha
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }
        starPaint.alpha = 255
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
