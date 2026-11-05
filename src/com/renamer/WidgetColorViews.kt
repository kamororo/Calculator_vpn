package com.renamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Spectrum wheel (2.17, extracted 2.33 from
 *  LoginPersonalizationActivity so the Widget Studio can use the
 *  exact same picker). Drag on the disc: angle = hue, distance
 *  from the centre = saturation. */
class ColorWheelView(ctx: Context) : View(ctx) {
    var hue = 170f
    var sat = 0.7f
    var onPick: ((Float, Float) -> Unit)? = null
    private var disc: Bitmap? = null
    private var testLogged = false

    private fun renderDisc(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d <= r) {
                    val h = ((Math.atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI).toFloat() + 360f) % 360f
                    bmp.setPixel(x, y, Color.HSVToColor(floatArrayOf(h, (d / r).coerceIn(0f, 1f), 1f)))
                }
            }
        }
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 280px and then upscaled to the view's pixel size
        // (700+px on dense screens), which looked blurry.
        // Now it renders at the view's own pixel size
        // (capped at 1024px to bound the one-time cost)
        // and draws 1:1 into a centred square.
        val px = minOf(width, height).coerceAtLeast(1).coerceAtMost(1024)
        if (disc == null || disc!!.width != px) disc = renderDisc(px)
        if (!testLogged) {
            testLogged = true
            try {
                            } catch (_: Exception) {}
        }
        disc?.let {
            val left = (width - px) / 2
            val top = (height - px) / 2
            canvas.drawBitmap(it, null, android.graphics.Rect(left, top, left + px, top + px), null)
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f
        val ang = Math.toRadians(hue.toDouble())
        val mx = cx + Math.cos(ang).toFloat() * sat * r
        val my = cy + Math.sin(ang).toFloat() * sat * r
        canvas.drawCircle(mx, my, 14f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
        })
        canvas.drawCircle(mx, my, 14f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#33000000")
        })
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f
            val dx = e.x - cx
            val dy = e.y - cy
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            hue = ((Math.atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI).toFloat() + 360f) % 360f
            sat = (d / r).coerceIn(0f, 1f)
            onPick?.invoke(hue, sat)
            invalidate()
            return true
        }
        return super.onTouchEvent(e)
    }
}

/** Brightness bar: black on the left, the picked
 *  hue at full brightness on the right. */
class ValueBarView(ctx: Context) : View(ctx) {
    var value = 0.9f
    var baseColor = Color.HSVToColor(floatArrayOf(170f, 0.7f, 1f))
    var onPick: ((Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val grad = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(Color.BLACK, baseColor), null, Shader.TileMode.CLAMP
        )
        val p = Paint().apply { shader = grad }
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            height / 2f, height / 2f, p
        )
        val tx = value * width
        canvas.drawCircle(tx, height / 2f, height / 2f + 4f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
        })
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            value = (e.x / width).coerceIn(0f, 1f)
            onPick?.invoke(value)
            invalidate()
            return true
        }
        return super.onTouchEvent(e)
    }
}
