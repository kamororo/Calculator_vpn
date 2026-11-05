package com.renamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** MLM-faithful widget painting (Widget Studio 1.2.45):
 *  the card is painted as one bitmap (gradient, sheen,
 *  waves, neon rim, button glow), so the home widget looks
 *  exactly like the Studio preview. State colours follow
 *  MLM's WidgetPaint: GREEN #34C759, AMBER #FF9F0A.
 *  The card holds no text, so it is painted at a reduced
 *  scale for the home widget (keeps the RemoteViews parcel
 *  far under the binder limit); the in-app preview paints
 *  at full density. */
object WidgetCard {
    const val GREEN = 0xFF34C759.toInt()
    const val AMBER = 0xFFFF9F0A.toInt()
    const val OFF_GREY = 0xFF9E9E9E.toInt()

    fun alpha(color: Int, a: Float): Int {
        val aa = (a.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(aa, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun fade(color: Int, a: Float): Int =
        alpha(color, (Color.alpha(color) / 255f) * a)

    fun lighten(color: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return Color.argb(Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * ff).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * ff).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * ff).toInt())
    }

    fun darken(color: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return Color.argb(Color.alpha(color),
            (Color.red(color) * (1 - ff)).toInt(),
            (Color.green(color) * (1 - ff)).toInt(),
            (Color.blue(color) * (1 - ff)).toInt())
    }

    fun hueShift(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    fun luminance(color: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(Color.red(color)) +
            0.7152 * lin(Color.green(color)) + 0.0722 * lin(Color.blue(color))
    }

    fun isLight(color: Int, opacity: Int): Boolean =
        luminance(color) > 0.55 && opacity >= 40

    private fun roundPath(rect: RectF, radius: Float): Path =
        Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

    /** Paints the card background. [state] is one of
     *  SectionWidgetProvider.STATE_* (drives the button
     *  glow: stronger while connected, amber while
     *  connecting). [btnCx]/[btnCy] are the button centre
     *  in pixels; [light] picks the rim/text treatment for
     *  pale cards (iOS). */
    fun paintCard(
        wPx: Int, hPx: Int, bg: Int, opacity: Int, cornerDp: Float,
        style: String, accent: Int, state: Int,
        btnCx: Float, btnCy: Float, density: Float, light: Boolean,
    ): Bitmap {
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(2), hPx.coerceAtLeast(2),
            Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = density
        val rect = RectF(d, d, w - d, h - d)
        val radius = cornerDp * d
        val path = roundPath(rect, radius)
        val op = opacity.coerceIn(0, 100) / 100f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style) {
            "colorful" -> {
                fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(alpha(accent, op), alpha(hueShift(accent, 42f), op),
                        alpha(bg, op)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            "wave" -> {
                fill.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    alpha(lighten(bg, 0.06f), op), alpha(darken(bg, 0.35f), op),
                    Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            "glass", "ios" -> {
                fill.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    alpha(lighten(bg, 0.10f), op), alpha(darken(bg, 0.10f), op),
                    Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
            }
            else -> {
                fill.color = alpha(bg, op)
                c.drawPath(path, fill)
            }
        }

        c.save()
        c.clipPath(path)
        when (style) {
            "glass", "ios" -> {
                // The sheen a pane of glass catches across its top corner.
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(rect.left, rect.top,
                        rect.left + rect.width() * 0.7f, rect.top + rect.height() * 0.9f,
                        if (light) 0x66FFFFFF else 0x2EFFFFFF, 0x00FFFFFF,
                        Shader.TileMode.CLAMP)
                }
                c.drawRect(rect, sheen)
            }
            "colorful" -> {
                // Soft light circles for depth.
                val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
                listOf(Triple(0.86f, 0.12f, 0.42f), Triple(0.10f, 0.95f, 0.36f),
                    Triple(0.55f, 0.55f, 0.22f)).forEach { (x, y, r) ->
                    val cx = rect.left + rect.width() * x
                    val cy = rect.top + rect.height() * y
                    val rad = max(rect.width(), rect.height()) * r
                    bubble.shader = RadialGradient(cx, cy, rad,
                        0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                    c.drawCircle(cx, cy, rad, bubble)
                }
            }
            "wave" -> {
                val layers = listOf(
                    Triple(0.62f, 0.00f, alpha(accent, 0.32f)),
                    Triple(0.70f, 1.10f, alpha(hueShift(accent, 40f), 0.24f)),
                    Triple(0.80f, 2.20f, alpha(hueShift(accent, -30f), 0.20f)),
                )
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                for ((level, phase, color) in layers) {
                    val wavePath = Path()
                    val baseY = rect.top + rect.height() * level
                    val amp = rect.height() * 0.07f
                    val len = rect.width() * 0.95f
                    wavePath.moveTo(rect.left, rect.bottom)
                    var x = rect.left
                    while (x <= rect.right + 2f) {
                        val y = baseY + amp * sin(((x - rect.left) / len) * 2.0 * PI + phase).toFloat()
                        wavePath.lineTo(x, y)
                        x += 4f
                    }
                    wavePath.lineTo(rect.right, rect.bottom)
                    wavePath.close()
                    p.shader = LinearGradient(0f, baseY - amp, 0f, rect.bottom,
                        color, fade(color, 0.25f), Shader.TileMode.CLAMP)
                    c.drawPath(wavePath, p)
                }
            }
            "dark_pro" -> {
                // A fine diagonal hatch and an accent hairline across the top.
                val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x08FFFFFF
                    strokeWidth = d
                }
                val step = 7f * d
                var x = rect.left - rect.height()
                while (x < rect.right) {
                    c.drawLine(x, rect.bottom, x + rect.height(), rect.top, hatch)
                    x += step
                }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(rect.left, 0f, rect.right, 0f,
                        intArrayOf(0x00000000, accent, 0x00000000),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    strokeWidth = 1.6f * d
                }
                c.drawLine(rect.left, rect.top + d, rect.right, rect.top + d, line)
            }
            else -> Unit
        }
        // The accent's glow where the button is; stronger while connected.
        if (style != "minimal" && style != "ios") {
            val radiusGlow = max(rect.width(), rect.height()) * 0.55f
            val glowA = when (state) {
                SectionWidgetProvider.STATE_ON -> 0.30f
                SectionWidgetProvider.STATE_CONNECTING -> 0.18f
                else -> 0.10f
            }
            val glowColor = if (state == SectionWidgetProvider.STATE_CONNECTING) AMBER else accent
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(btnCx, btnCy, radiusGlow,
                    alpha(glowColor, glowA), alpha(glowColor, 0f), Shader.TileMode.CLAMP)
            }
            c.drawRect(rect, glow)
        }
        c.restore()

        // The rim.
        when (style) {
            "neon" -> {
                val inset = RectF(rect).apply { inset(3f * d, 3f * d) }
                val neonPath = roundPath(inset, radius - 2f * d)
                val blur = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    setStyle(Paint.Style.STROKE)
                    strokeWidth = 2.4f * d
                    color = alpha(accent, 0.85f)
                    maskFilter = BlurMaskFilter(5f * d, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawPath(neonPath, blur)
                val crisp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    setStyle(Paint.Style.STROKE)
                    strokeWidth = 1.3f * d
                    color = lighten(accent, 0.25f)
                }
                c.drawPath(neonPath, crisp)
            }
            else -> {
                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    setStyle(Paint.Style.STROKE)
                    strokeWidth = 1.1f * d
                    if (style == "minimal" || style == "dark_pro") {
                        color = 0x14FFFFFF
                    } else {
                        shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                            if (light) 0xB3FFFFFF.toInt() else 0x6BFFFFFF,
                            if (light) 0x40FFFFFF else 0x0FFFFFFF,
                            Shader.TileMode.CLAMP)
                    }
                }
                c.drawPath(path, rim)
            }
        }
        return bmp
    }

    /** Live speed chart: two polylines (down, up) over a
     *  soft down-area fill. Empty samples -> transparent. */
    fun paintChart(
        wPx: Int, hPx: Int, down: List<Float>, up: List<Float>,
        downColor: Int, upColor: Int, density: Float,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(2), hPx.coerceAtLeast(2),
            Bitmap.Config.ARGB_8888)
        if (down.isEmpty() && up.isEmpty()) return bmp
        val c = Canvas(bmp)
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val n = max(down.size, up.size)
        if (n < 1) return bmp
        var maxV = 1f
        for (v in down) maxV = max(maxV, v)
        for (v in up) maxV = max(maxV, v)
        fun xAt(i: Int): Float = if (n == 1) w / 2f else i * w / (n - 1)
        fun yAt(v: Float): Float = h - (v / maxV) * h * 0.9f - h * 0.05f
        val d = density
        if (down.isNotEmpty()) {
            val area = Path()
            val line = Path()
            for (i in down.indices) {
                val x = xAt(i)
                val y = yAt(down[i])
                if (i == 0) {
                    area.moveTo(x, h)
                    area.lineTo(x, y)
                    line.moveTo(x, y)
                } else {
                    area.lineTo(x, y)
                    line.lineTo(x, y)
                }
            }
            area.lineTo(xAt(down.size - 1), h)
            area.close()
            c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = alpha(downColor, 0.15f)
            })
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = downColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }
        if (up.isNotEmpty()) {
            val line = Path()
            for (i in up.indices) {
                val x = xAt(i)
                val y = yAt(up[i])
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = upColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }
        return bmp
    }

    // ---------------------------------------------------------------------------------------------
    // Session tracking (usage + time) and chart samples.
    // The widget records a session when its section's state
    // flips off -> on, so usage/time are real even when the
    // connection was started inside the app, not the widget.
    // ---------------------------------------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)

    /** Total tunnel traffic in bytes (same interfaces as the
     *  speed reader: tun|wg|awg|amnezia|ovpn|ppp, summed). */
    fun totalTunnelBytes(): Long {
        var total = 0L
        try {
            val lines = File("/proc/net/dev").readLines()
            for (line in lines) {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val name = line.substring(0, colon).trim()
                if (!name.startsWith("tun") && !name.startsWith("wg") &&
                    !name.startsWith("awg") && !name.startsWith("amnezia") &&
                    !name.startsWith("ovpn") && !name.startsWith("ppp")) continue
                val parts = line.substring(colon + 1).trim().split(Regex("\\s+"))
                if (parts.size < 9) continue
                total += (parts[0].toLongOrNull() ?: 0L) + (parts[8].toLongOrNull() ?: 0L)
            }
        } catch (_: Exception) {}
        return total
    }

    /** Call on every widget render with the section's live
     *  state: starts a session on off -> on, clears it (and
     *  the chart) on on -> off. */
    fun noteState(ctx: Context, section: String, connected: Boolean) {
        try {
            val p = prefs(ctx)
            val last = p.getInt("sess_last_$section", 0)
            if (connected && last != 1) {
                p.edit().putInt("sess_last_$section", 1)
                    .putLong("sess_start_$section", System.currentTimeMillis())
                    .putLong("sess_base_$section", totalTunnelBytes())
                    .apply()
            } else if (!connected && last == 1) {
                p.edit().putInt("sess_last_$section", 0)
                    .remove("sess_start_$section")
                    .remove("sess_base_$section")
                    .remove("chart_$section")
                    .apply()
            }
        } catch (_: Exception) {}
    }

    fun sessionStartMs(ctx: Context, section: String): Long =
        try { prefs(ctx).getLong("sess_start_$section", 0L) } catch (_: Exception) { 0L }

    fun usageBytes(ctx: Context, section: String): Long {
        return try {
            val base = prefs(ctx).getLong("sess_base_$section", -1L)
            if (base < 0) 0L else max(0L, totalTunnelBytes() - base)
        } catch (_: Exception) { 0L }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /** Append one speed sample (KB/s) for a connected
     *  section; capped at 30, oldest dropped. At most one
     *  sample per 10s per section, so several widgets of
     *  one section rendering on the same tick do not flood
     *  the chart with duplicates. */
    fun chartAppend(ctx: Context, section: String, downKbps: Long, upKbps: Long) {
        try {
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            if (now - p.getLong("chart_t_$section", 0L) < 10_000) return
            val arr = JSONArray(p.getString("chart_$section", "[]"))
            arr.put(JSONArray().put(downKbps.toInt()).put(upKbps.toInt()))
            while (arr.length() > 30) arr.remove(0)
            p.edit().putString("chart_$section", arr.toString())
                .putLong("chart_t_$section", now).apply()
        } catch (_: Exception) {}
    }

    fun chartSamples(ctx: Context, section: String): Pair<List<Float>, List<Float>> {
        val down = ArrayList<Float>()
        val up = ArrayList<Float>()
        try {
            val arr = JSONArray(prefs(ctx).getString("chart_$section", "[]"))
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                down += pair.optInt(0).toFloat()
                up += pair.optInt(1).toFloat()
            }
        } catch (_: Exception) {}
        return down to up
    }
}
