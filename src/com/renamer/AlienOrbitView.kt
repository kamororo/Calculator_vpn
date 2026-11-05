package com.renamer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** The Alien section icon, alive (2.32, restyled 2.35). Draws the
 *  same galaxy bitmap as before, plus one very thin slow ring
 *  hugging each of the THREE SMALL DOTS (2.35: the ring around the
 *  big disc was removed at the user's order). The rings sit only
 *  ~2dp off their circle, rotate slowly and classily (one full
 *  turn in ~30s, dots slightly varied), teal — NOT Hydra's
 *  wide-gap fast sweep. A faint full ring stays put; only a
 *  brighter 70-degree arc travels around it. The animation runs
 *  only while the view is attached, so it costs nothing when the
 *  page is hidden.
 *
 *  2.35: the white middle of the big disc also breathes gently:
 *  a soft white glow over the disc centre pulses on a 2s cycle
 *  (bright white to a slightly darker white), driven by the
 *  rotation animator's own invalidations, so no second animator.
 *
 *  Circle geometry was MEASURED from alien.png (1112x944) by alpha
 *  component analysis: disc centre + radii, then the three dots. */
class AlienOrbitView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val density = ctx.resources.displayMetrics.density
    private var bmp: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var angle = 0f

    private data class Ring(
        val cx: Float, val cy: Float,
        val rx: Float, val ry: Float,
        val faint: Int, val arc: Int, val speed: Float
    )

    // 2.35: only the three small dots keep a ring; the disc ring
    // was removed. Bitmap fractions: left dot, right dot, bottom dot.
    private val rings = listOf(
        Ring(0.231f, 0.308f, 0.0216f, 0.0254f,
            Color.parseColor("#80CBC4"), Color.parseColor("#26A69A"), 1.25f),
        Ring(0.804f, 0.308f, 0.0207f, 0.0254f,
            Color.parseColor("#80CBC4"), Color.parseColor("#26A69A"), 0.8f),
        Ring(0.518f, 0.901f, 0.0221f, 0.0249f,
            Color.parseColor("#80CBC4"), Color.parseColor("#26A69A"), 1.1f)
    )

    // Gentle 2s white pulse over the big disc's centre (2.35).
    // Bitmap fractions of the disc centre + radii (measured).
    private val discCxF = 0.5235f
    private val discCyF = 0.5235f
    private val discRxF = 0.2935f
    private val discRyF = 0.3545f
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulseShader: android.graphics.Shader? = null
    private var pulseCx = -1f
    private var pulseCy = -1f
    private var pulseR = -1f

    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        try { bmp = BitmapFactory.decodeStream(ctx.assets.open("alien.png")) } catch (_: Exception) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 30000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            }
        }
        try { animator?.start() } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        try { animator?.cancel() } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || b == null || b.isRecycled) return
        // FIT_CENTER, exactly like the ImageView this replaces.
        val scale = minOf(w / b.width, h / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        canvas.drawBitmap(b, null, RectF(left, top, left + dw, top + dh), null)
        // Soft white breathing glow on the disc centre: a 2s sine
        // cycle, bright white down to a slightly darker white.
        val dcx = left + discCxF * dw
        val dcy = top + discCyF * dh
        val dr = minOf(discRxF * dw, discRyF * dh) * 0.6f
        if (dr > 0) {
            if (pulseShader == null || pulseCx != dcx || pulseCy != dcy || pulseR != dr) {
                pulseShader = android.graphics.RadialGradient(dcx, dcy, dr,
                    intArrayOf(Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
                pulseCx = dcx; pulseCy = dcy; pulseR = dr
            }
            val phase = (System.currentTimeMillis() % 2000) / 2000f
            val breathe = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)
            pulsePaint.shader = pulseShader
            pulsePaint.alpha = (60 + breathe * 90).toInt()
            canvas.drawCircle(dcx, dcy, dr, pulsePaint)
        }
        val gap = 2f * density
        for (ring in rings) {
            val cx = left + ring.cx * dw
            val cy = top + ring.cy * dh
            val rx = ring.rx * dw + gap
            val ry = ring.ry * dh + gap
            val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
            faintPaint.color = ring.faint
            faintPaint.alpha = 110
            canvas.drawOval(oval, faintPaint)
            arcPaint.color = ring.arc
            canvas.save()
            canvas.rotate(angle * ring.speed, cx, cy)
            canvas.drawArc(oval, -35f, 70f, false, arcPaint)
            canvas.restore()
        }
    }
}
