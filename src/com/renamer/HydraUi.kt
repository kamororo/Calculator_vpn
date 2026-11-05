package com.renamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button

/** Shared glass look for all pages.
 *
 *  2.11: the photo textures, rebuilt properly. The
 *  2.10 flat vertical gradients were rightly called
 *  out as simplified: the photos show glossy 3D
 *  glass — a domed orb with a bright top-left
 *  specular, water-caustic cells inside, a rim
 *  light and a soft inner shadow — not a flat
 *  fill. Everything below renders that texture
 *  into a bitmap once per size (software canvas,
 *  so blur works) and then just draws the bitmap:
 *  one render, no per-frame cost, and the same
 *  texture in every state color, because only the
 *  hue parameter changes. */
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

    /** Mix a color toward white (f = 0..1). */
    fun lighten(color: Int, f: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * f).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * f).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * f).toInt()
        return Color.argb(Color.alpha(color), r, g, b)
    }

    /** Mix a color toward black (f = 0..1). */
    fun darken(color: Int, f: Float): Int {
        val r = (Color.red(color) * (1 - f)).toInt()
        val g = (Color.green(color) * (1 - f)).toInt()
        val b = (Color.blue(color) * (1 - f)).toInt()
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    /** Renders once into a bitmap per bounds size,
     *  then draws the bitmap. Subclasses implement
     *  render(); dropCache() forces a re-render
     *  (used when a color changes). */
    abstract class BitmapCachedDrawable(ctx: Context) : Drawable() {
        protected val density = ctx.resources.displayMetrics.density
        private var bmp: Bitmap? = null
        private var bw = 0
        private var bh = 0
        protected abstract fun render(c: Canvas, w: Int, h: Int)
        protected fun dropCache() { bmp = null }
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            if (bmp == null || bw != b.width() || bh != b.height()) {
                bmp?.recycle()
                bw = b.width(); bh = b.height()
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                render(Canvas(bmp!!), bw, bh)
            }
            canvas.drawBitmap(bmp!!, b.left.toFloat(), b.top.toFloat(), null)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** The photo orb: a domed glass sphere filled
     *  with tinted water. Bright top-left specular,
     *  soft water-caustic cells (fixed seed, so the
     *  pattern is designed, not noisy), a light rim
     *  on top, a deeper rim at the bottom, and a
     *  thin glass edge line. Only [base] (fill) and
     *  [rim] (edge) vary per state. */
    class GlassOrbDrawable(private val base: Int, private val rim: Int, ctx: Context) : BitmapCachedDrawable(ctx) {
        override fun render(c: Canvas, w: Int, h: Int) {
            val cx = w / 2f; val cy = h / 2f
            val r = minOf(w, h) / 2f
            if (r <= 0) return
            val dome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy - r * 0.15f, r * 1.15f,
                    intArrayOf(lighten(base, 0.60f), lighten(base, 0.25f), base, darken(base, 0.22f)),
                    floatArrayOf(0f, 0.35f, 0.68f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, r, dome)
            // Water caustics, clipped to the orb.
            c.save()
            c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
            val rnd = java.util.Random(7)
            for (i in 0 until 16) {
                val ang = rnd.nextFloat() * 360f
                val dist = rnd.nextFloat() * 0.80f * r
                val bx = cx + kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat() * dist
                val by = cy + kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat() * dist
                val br = r * (0.10f + rnd.nextFloat() * 0.14f)
                val white = rnd.nextBoolean()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (white) Color.argb(26 + rnd.nextInt(38), 255, 255, 255)
                        else withAlpha(darken(base, 0.25f), 16 + rnd.nextInt(18))
                    maskFilter = BlurMaskFilter(br * 0.9f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawCircle(bx, by, br, p)
            }
            for (i in 0 until 4) {
                val arcR = r * (0.30f + rnd.nextFloat() * 0.50f)
                val start = rnd.nextFloat() * 360f
                val sweep = 40f + rnd.nextFloat() * 70f
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = r * 0.10f
                    color = Color.argb(26 + rnd.nextInt(20), 255, 255, 255)
                    maskFilter = BlurMaskFilter(r * 0.06f, BlurMaskFilter.Blur.NORMAL)
                    strokeCap = Paint.Cap.ROUND
                }
                c.drawArc(RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR), start, sweep, false, p)
            }
            for (i in 0 until 8) {
                val ang = rnd.nextFloat() * 360f
                val dist = rnd.nextFloat() * 0.85f * r
                val sx = cx + kotlin.math.cos(Math.toRadians(ang.toDouble())).toFloat() * dist
                val sy = cy + kotlin.math.sin(Math.toRadians(ang.toDouble())).toFloat() * dist
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(60 + rnd.nextInt(50), 255, 255, 255)
                    maskFilter = BlurMaskFilter(r * 0.02f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawCircle(sx, sy, r * (0.02f + rnd.nextFloat() * 0.03f), p)
            }
            c.restore()
            // Top-left specular: the bright glass highlight.
            val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - r * 0.38f, cy - r * 0.42f, r * 0.75f,
                    intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.save()
            c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
            c.drawCircle(cx - r * 0.38f, cy - r * 0.42f, r * 0.75f, spec)
            c.restore()
            // Rim light: bright on top, deeper below.
            val rimTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * density
                color = Color.argb(130, 255, 255, 255)
            }
            c.drawArc(RectF(cx - r + 2f, cy - r + 2f, cx + r - 2f, cy + r - 2f), -160f, 140f, false, rimTop)
            val rimBottom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * density
                color = withAlpha(darken(base, 0.30f), 90)
            }
            c.drawArc(RectF(cx - r + 2f, cy - r + 2f, cx + r - 2f, cy + r - 2f), 20f, 140f, false, rimBottom)
            // Thin glass edge + inner glass line.
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2.5f * density
                color = withAlpha(rim, 220)
            }
            c.drawCircle(cx, cy, r - 1.25f * density, edge)
            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f * density
                color = Color.argb(50, 255, 255, 255)
            }
            c.drawCircle(cx, cy, r * 0.93f, inner)
        }
    }

    /** The photo's big round buttons, in any state
     *  color: same orb texture, only the hue
     *  changes. Callers keep passing their existing
     *  state colors — hues are never altered here. */
    fun glassCircleShade(base: Int, stroke: Int, ctx: Context): Drawable =
        GlassOrbDrawable(base, stroke, ctx)

    /** Frosted glass pill, photo-style: bright top
     *  edge, soft vertical shade, thin grey rim —
     *  richer than the flat glassBg, same call
     *  shape, so existing rows keep their behavior. */
    class FrostedPillDrawable(private val fill: Int, private val stroke: Int, radiusDp: Float, ctx: Context) : BitmapCachedDrawable(ctx) {
        private val radius = radiusDp * density
        override fun render(c: Canvas, w: Int, h: Int) {
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.argb(235, 255, 255, 255), fill, darken(fill, 0.06f)),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(rect, radius, radius, body)
            // Bright top edge, like light on glass.
            val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * density
                color = Color.argb(200, 255, 255, 255)
            }
            val top = RectF(2f * density, 2f * density, w - 2f * density, h * 0.55f)
            c.drawRoundRect(top, radius, radius, gloss)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f
                color = stroke
            }
            c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), radius, radius, rim)
        }
    }

    fun glassPill(fill: Int, stroke: Int, radiusDp: Float, ctx: Context): Drawable =
        FrostedPillDrawable(fill, stroke, radiusDp, ctx)

    /** Glossy toggle track capsule: vertical shade,
     *  bright top gloss band, soft bottom shade,
     *  thin rim — the photo's glass pill, not a
     *  flat bar. */
    class GlossCapsuleDrawable(private val base: Int, private val stroke: Int, ctx: Context) : BitmapCachedDrawable(ctx) {
        override fun render(c: Canvas, w: Int, h: Int) {
            val rad = h / 2f
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(lighten(base, 0.45f), base, darken(base, 0.12f)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(rect, rad, rad, body)
            val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h * 0.55f,
                    intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) })
            c.drawRect(RectF(0f, 0f, w.toFloat(), h * 0.55f), gloss)
            c.restore()
            val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, h * 0.75f, 0f, h.toFloat(),
                    intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(60, 0, 0, 0)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) })
            c.drawRect(RectF(0f, h * 0.75f, w.toFloat(), h.toFloat()), shade)
            c.restore()
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f
                color = stroke
            }
            c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), rad, rad, rim)
        }
        override fun getIntrinsicWidth(): Int = (64 * density).toInt()
        override fun getIntrinsicHeight(): Int = (36 * density).toInt()
    }

    /** Glass pill toggle track: the knob stays a
     *  glass sphere in both states (see
     *  glassSwitchThumb); only the track hue
     *  changes, and the Switch slides it natively. */
    fun glassSwitchTrack(offBase: Int, offStroke: Int, onBase: Int, onStroke: Int, ctx: Context): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), GlossCapsuleDrawable(onBase, onStroke, ctx))
            addState(intArrayOf(), GlossCapsuleDrawable(offBase, offStroke, ctx))
        }
    }

    /** Glass sphere knob: halo glow in the state
     *  color, bright top-left specular, grey edge —
     *  the photo's round glass knob, not a flat dot. */
    class GlassSphereThumbDrawable(private val halo: Int, ctx: Context) : BitmapCachedDrawable(ctx) {
        override fun render(c: Canvas, w: Int, h: Int) {
            val cx = w / 2f; val cy = h / 2f
            val r = minOf(w, h) / 2f
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r,
                    intArrayOf(withAlpha(halo, 110), withAlpha(halo, 0)),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, r, glow)
            val sr = r - 1.5f * density
            val sphere = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - sr * 0.35f, cy - sr * 0.35f, sr * 1.3f,
                    intArrayOf(Color.WHITE, Color.parseColor("#F6F6F6"), Color.parseColor("#D9D9D9"), Color.parseColor("#BFBFBF")),
                    floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, sr, sphere)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * density
                color = Color.parseColor("#BFBFBF")
            }
            c.drawCircle(cx, cy, sr, edge)
            val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 255, 255)
                maskFilter = BlurMaskFilter(sr * 0.08f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawCircle(cx - sr * 0.38f, cy - sr * 0.42f, sr * 0.16f, spec)
        }
        override fun getIntrinsicWidth(): Int = (30 * density).toInt()
        override fun getIntrinsicHeight(): Int = (30 * density).toInt()
    }

    /** Glass sphere knob: green halo when on, warm
     *  halo when off — the Switch state drives it. */
    fun glassSwitchThumb(ctx: Context): StateListDrawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked),
                GlassSphereThumbDrawable(Color.parseColor("#81C784"), ctx))
            addState(intArrayOf(),
                GlassSphereThumbDrawable(Color.parseColor("#E8D9A8"), ctx))
        }
    }

    /** Constant outer ring, photo-style: tone
     *  varies around the ring (some parts lighter,
     *  some darker), a glossy highlight arc on the
     *  top-left, thin edge lines — identical in all
     *  states, only the inner circle's hue changes. */
    class RingShadeDrawable(private val light: Int, private val dark: Int, private val strokeDp: Float, ctx: Context) : BitmapCachedDrawable(ctx) {
        override fun render(c: Canvas, w: Int, h: Int) {
            val cx = w / 2f; val cy = h / 2f
            val sw = strokeDp * density
            val r = minOf(w, h) / 2f - sw / 2f
            if (r <= 0) return
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw
                shader = SweepGradient(cx, cy,
                    intArrayOf(light, lighten(light, 0.30f), dark, lighten(dark, 0.25f), light),
                    floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f))
            }
            c.drawCircle(cx, cy, r, ring)
            val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw * 0.7f
                color = Color.argb(170, 255, 255, 255)
                maskFilter = BlurMaskFilter(sw * 0.8f, BlurMaskFilter.Blur.NORMAL)
                strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(rect, 180f, 120f, false, gloss)
            val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f
                color = Color.argb(60, 255, 255, 255)
            }
            c.drawCircle(cx, cy, r - sw / 2f, hair)
        }
    }

    /** Rotating inner rim: mostly the state color
     *  with tone variation around the ring, plus one
     *  translucent glass-water segment that is never
     *  removed. The rotation lives on the View
     *  (RotateAnimation), so recoloring with setBase
     *  never stops the spin. */
    class SweepRingDrawable(base: Int, ctx: Context, private val strokeDp: Float = 10f) : BitmapCachedDrawable(ctx) {
        private var baseColor = base
        fun setBase(c: Int) { baseColor = c; dropCache(); invalidateSelf() }
        override fun render(c: Canvas, w: Int, h: Int) {
            val cx = w / 2f; val cy = h / 2f
            val sw = strokeDp * density
            val r = minOf(w, h) / 2f - sw / 2f
            if (r <= 0) return
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val glass = Color.argb(120, 255, 255, 255)
            val faint = Color.argb(40, 255, 255, 255)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(cx, cy,
                    intArrayOf(darken(baseColor, 0.08f), baseColor, lighten(baseColor, 0.40f), glass, faint, baseColor),
                    floatArrayOf(0f, 0.45f, 0.62f, 0.78f, 0.88f, 1f))
            }
            c.drawArc(rect, 0f, 360f, false, ring)
            val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw * 0.5f
                color = Color.argb(90, 255, 255, 255)
                maskFilter = BlurMaskFilter(sw * 0.5f, BlurMaskFilter.Blur.NORMAL)
                strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(rect, 0f, 60f, false, gloss)
        }
    }

    // ---------- 2.16: Theme 2 deleted ----------
    // The 2.12 photo look is gone completely (user
    // order 2026-11-01): no photo assets, no photo
    // drawables, no look branches. Everything below
    // is the Theme 1 (2.11 glass) look, unconditionally.

    /** Smart bottom orb (glass, Theme 1). */
    fun smartOrbBg(base: Int, stroke: Int, ctx: Context): Drawable =
        GlassOrbDrawable(base, stroke, ctx)

    /** Hydra button (glass orb, Theme 1). */
    fun hydraBtnBg(base: Int, stroke: Int, ctx: Context): Drawable =
        GlassOrbDrawable(base, stroke, ctx)

    /** Emergency face (glass orb, Theme 1). */
    fun emergencyBtnBg(base: Int, stroke: Int, ctx: Context): Drawable =
        GlassOrbDrawable(base, stroke, ctx)

    /** Target/apps rows (glass pill, Theme 1). */
    fun smartRowBg(fill: Int, stroke: Int, radiusDp: Float, ctx: Context): Drawable =
        glassPill(fill, stroke, radiusDp, ctx)

    /** A real log glyph: a document page with
     *  lines — not three bars (that reads as an
     *  options menu). Dark gray, works on light
     *  glass. Size in dp. */
    fun logIconBitmap(ctx: Context, dpSize: Int): android.graphics.Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (dpSize * d).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = s / 96f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            style = Paint.Style.STROKE
            strokeWidth = 4f * f
            strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val page = Path().apply {
            moveTo(24f * f, 12f * f)
            lineTo(58f * f, 12f * f)
            lineTo(76f * f, 30f * f)
            lineTo(76f * f, 84f * f)
            lineTo(24f * f, 84f * f)
            close()
        }
        c.drawPath(page, fill)
        c.drawPath(page, stroke)
        val fold = Path().apply {
            moveTo(58f * f, 12f * f)
            lineTo(76f * f, 30f * f)
            lineTo(58f * f, 30f * f)
            close()
        }
        val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            style = Paint.Style.FILL
        }
        c.drawPath(fold, foldPaint)
        c.drawPath(fold, stroke)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#555555")
            strokeWidth = 4.5f * f
            strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(32f * f, 46f * f, 66f * f, 46f * f, line)
        c.drawLine(32f * f, 58f * f, 66f * f, 58f * f, line)
        c.drawLine(32f * f, 70f * f, 54f * f, 70f * f, line)
        return bmp
    }

    /** Whole-photo toggle (track + knob together,
     *  exactly like the photo). OVPN: off = photo
     *  as-is, on = same texture shifted green.
     *  WG: off = photo flipped (knob left, as
     *  ordered), on = photo as-is shifted green
     *  (knob right). Previous look = the 2.11
     *  glass capsule + sphere, composed into a
     *  bitmap of the same size. */
    /** Connect toggle (glass capsule + sphere, Theme 1). */
    /** 2.41: amber "busy" toggle for a profile whose
     *  engine ladder is running (ovpn size). */
    fun toggleBitmapBusy(ctx: Context): android.graphics.Bitmap {
        return glassToggleBitmap(ctx, 62, 41, false,
            Color.parseColor("#FFE0B2"), Color.parseColor("#FFB74D"),
            Color.parseColor("#FFE0B2"), Color.parseColor("#FFB74D"))
    }

    fun toggleBitmap(ctx: Context, kind: String, on: Boolean): android.graphics.Bitmap {
        val (offBase, offStroke, onBase, onStroke) = if (kind == "ovpn")
            Quad("#FFF3D6", "#E8D9A8", "#C8E6C9", "#81C784")
        else Quad("#EEEEEE", "#BDBDBD", "#C8E6C9", "#81C784")
        return glassToggleBitmap(ctx, if (kind == "ovpn") 62 else 76, if (kind == "ovpn") 41 else 37,
            on, Color.parseColor(offBase), Color.parseColor(offStroke),
            Color.parseColor(onBase), Color.parseColor(onStroke))
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    private fun glassToggleBitmap(ctx: Context, wDp: Int, hDp: Int, on: Boolean,
                                  offBase: Int, offStroke: Int, onBase: Int, onStroke: Int): android.graphics.Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val track = GlossCapsuleDrawable(if (on) onBase else offBase, if (on) onStroke else offStroke, ctx)
        track.setBounds(0, 0, w, h)
        track.draw(c)
        val thumbSize = h - (8 * d).toInt()
        val tx = if (on) w - thumbSize - (4 * d).toInt() else (4 * d).toInt()
        val thumb = GlassSphereThumbDrawable(if (on) Color.parseColor("#81C784") else Color.parseColor("#E8D9A8"), ctx)
        thumb.setBounds(tx, (4 * d).toInt(), tx + thumbSize, (4 * d).toInt() + thumbSize)
        thumb.draw(c)
        return bmp
    }
}
