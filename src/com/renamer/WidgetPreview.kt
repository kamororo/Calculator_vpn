package com.renamer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Fills an inflated widget_section layout exactly like
 *  SectionWidgetProvider fills the RemoteViews (same inputs,
 *  same rules): painted card, MLM state colours, per-size
 *  layouts, usage/time/chart on large. Used by the Studio
 *  and by the icon/button customize pages, so every preview
 *  matches the widget on the home screen.
 *
 *  The first 13 parameters keep their old order, so old
 *  call sites still compile; the rest are the MLM design
 *  parameters (accent, card, opacity, corner, text scale,
 *  shown info, size). */
object WidgetPreview {
    /** The widget layout for the app language and size. */
    fun layout(ctx: android.content.Context): Int = layout(ctx, "medium")

    fun layout(ctx: android.content.Context, size: String): Int {
        val en = AppLanguage.isEnglish(ctx)
        return when (size) {
            "small" -> if (en) R.layout.widget_section_small_en
                else R.layout.widget_section_small_fa
            "large" -> if (en) R.layout.widget_section_large_en
                else R.layout.widget_section_large_fa
            else -> if (en) R.layout.widget_section_en
                else R.layout.widget_section_fa
        }
    }

    private fun cardDp(size: String): Triple<Int, Int, Pair<Float, Float>> = when (size) {
        "small" -> Triple(110, 110, 55f to 62f)
        "large" -> Triple(250, 250, 125f to 115f)
        else -> Triple(250, 110, 46f to 55f)
    }

    fun populate(
        activity: Activity, view: View, section: String, style: String,
        iconBg: Int, btnShape: String, btnColor: Int, title: String,
        template: String = "", forcedState: Int = -1,
        showSpeeds: Boolean = true, showStatus: Boolean = true,
        showIcon: Boolean = true, showMethod: Boolean = true,
        showUsage: Boolean = false, showTime: Boolean = false,
        showChart: Boolean = false, size: String = "medium",
        accent: Int = -1, bg: Int = -1, opacity: Int = -1,
        corner: Int = -1, textScale: Int = 100
    ) {
        val holder = view.findViewById<View>(R.id.widget_section_root)
        holder.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val state = if (forcedState >= 0) forcedState else try {
            SectionWidgetProvider.widgetState(activity, section)
        } catch (_: Exception) { SectionWidgetProvider.STATE_OFF }
        val connected = state == SectionWidgetProvider.STATE_ON
        val accentRes = if (accent != -1) accent
            else if (template.isNotBlank()) WidgetStyle.presetAccent(template)
            else btnColor
        val bgRes = if (bg != -1) bg
            else if (template.isNotBlank()) WidgetStyle.presetBg(template) else -1
        val opacityRes = if (opacity != -1) opacity
            else if (template.isNotBlank()) WidgetStyle.presetOpacity(template) else -1
        val cornerRes = if (corner != -1) corner
            else if (template.isNotBlank()) WidgetStyle.presetCorner(template) else -1
        val light = template.isNotBlank() &&
            WidgetCard.isLight(bgRes, opacityRes) && template != "colorful"
        val textCol = if (template.isNotBlank()) {
            if (light) Color.parseColor("#111114") else Color.WHITE
        } else WidgetStyle.textColor(style)
        val subCol = if (template.isNotBlank()) {
            if (light) Color.parseColor("#993C3C43") else Color.parseColor("#B3FFFFFF")
        } else WidgetStyle.subTextColor(style)
        val sc = textScale / 100f
        val density = activity.resources.displayMetrics.density
        // Card: painted (template) or legacy drawable.
        val bgView = view.findViewById<ImageView>(R.id.widget_section_bg)
        if (template.isBlank()) {
            holder.setBackgroundResource(WidgetStyle.bgRes(style))
            bgView.visibility = View.GONE
        } else {
            val (cw, ch, center) = cardDp(size)
            bgView.setImageBitmap(WidgetCard.paintCard(
                (cw * density).toInt(), (ch * density).toInt(),
                bgRes, opacityRes, cornerRes.toFloat(), template, accentRes, state,
                center.first * density, center.second * density, density, light))
            bgView.visibility = View.VISIBLE
        }
        val iconBgView = view.findViewById<ImageView>(R.id.widget_section_icon_bg)
        val iconView = view.findViewById<ImageView>(R.id.widget_section_icon)
        if (!showIcon) {
            iconBgView.visibility = View.GONE
            iconView.visibility = View.GONE
        } else {
            iconBgView.setColorFilter(iconBg)
            val bmp = SectionWidgetProvider.sectionIconBitmap(activity, section)
            if (bmp != null) iconView.setImageBitmap(bmp)
        }
        val btnView = view.findViewById<ImageView>(R.id.widget_section_btn)
        val btnPx = when (size) { "small" -> 160; "large" -> 260; else -> 204 }
        btnView.setImageBitmap(
            SectionWidgetProvider.buttonBitmap(activity, btnShape, accentRes, btnPx, state, light))
        if (btnShape == "wide" || btnShape == "pill") {
            val (bw, bh) = when (size) {
                "small" -> 84 to 48
                "large" -> 180 to 72
                else -> 120 to 68
            }
            btnView.layoutParams = LinearLayout.LayoutParams(
                (bw * density).toInt(), (bh * density).toInt())
        }
        val info = SectionWidgetProvider.infoFor(activity, section)
        val nameView = view.findViewById<TextView>(R.id.widget_section_name)
        if (!showMethod) {
            nameView.visibility = View.GONE
        } else {
            nameView.visibility = View.VISIBLE
            nameView.text = if (title.isBlank()) info.first else title
        }
        nameView.setTextColor(textCol)
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * sc)
        val dot = view.findViewById<TextView>(R.id.widget_section_dot)
        dot.text = "●"
        dot.setTextColor(SectionWidgetProvider.stateColor(state))
        val statusView = view.findViewById<TextView>(R.id.widget_section_status)
        if (size == "large") {
            // Header status (no status row on large).
            if (!showStatus) {
                statusView.visibility = View.GONE
                dot.visibility = View.GONE
            } else {
                statusView.visibility = View.VISIBLE
                dot.visibility = View.VISIBLE
                statusView.text = SectionWidgetProvider.statusHint(activity, section, state)
            }
        } else {
            val statusRow = view.findViewById<View>(R.id.widget_section_status_row)
            if (showStatus) {
                statusRow.visibility = View.VISIBLE
                statusView.text = SectionWidgetProvider.statusHint(activity, section, state)
            } else {
                statusRow.visibility = View.GONE
            }
        }
        statusView.setTextColor(subCol)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sc)
        var down = -1L
        var up = -1L
        if (connected) {
            val sp = VpnNotification.currentSpeedsKbps(activity)
            down = sp.first
            up = sp.second
        }
        if (size != "large") {
            val speeds = view.findViewById<TextView>(R.id.widget_section_speeds)
            if (!showSpeeds) {
                speeds.visibility = View.GONE
            } else {
                speeds.visibility = View.VISIBLE
                speeds.text = if (down < 0) "↓ —   ↑ —"
                    else SectionWidgetProvider.speedSpanned(down, up, accentRes)
            }
            speeds.setTextColor(subCol)
            speeds.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sc)
        }
        if (size != "small") {
            val chartView = view.findViewById<ImageView>(R.id.widget_section_chart)
            if (showChart && connected) {
                val (dSamples, uSamples) = WidgetCard.chartSamples(activity, section)
                val chw = if (size == "large") 222f else 140f
                val chh = if (size == "large") 44f else 20f
                chartView.setImageBitmap(WidgetCard.paintChart(
                    (chw * density).toInt(), (chh * density).toInt(),
                    dSamples, uSamples, accentRes,
                    WidgetCard.hueShift(accentRes, 55f), density))
                chartView.visibility = View.VISIBLE
            } else {
                chartView.visibility = View.GONE
            }
        }
        if (size == "large") {
            fun label(id: Int, fa: String, en: String): TextView {
                val v = view.findViewById<TextView>(id)
                v.text = AppLanguage.t(activity, fa, en)
                v.setTextColor(subCol)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * sc)
                return v
            }
            fun value(id: Int, text: String) {
                val v = view.findViewById<TextView>(id)
                v.text = text
                v.setTextColor(textCol)
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * sc)
            }
            label(R.id.widget_stat_dl_label, "دانلود", "Download")
            label(R.id.widget_stat_ul_label, "آپلود", "Upload")
            label(R.id.widget_stat_usage_label, "مصرف نشست", "Session")
            label(R.id.widget_stat_time_label, "مدت اتصال", "Connected for")
            fun fmtSpeed(kbps: Long): String =
                if (kbps >= 1024) String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
                else "$kbps KB/s"
            value(R.id.widget_stat_dl_value, if (down < 0) "—" else fmtSpeed(down))
            value(R.id.widget_stat_ul_value, if (up < 0) "—" else fmtSpeed(up))
            value(R.id.widget_stat_usage_value,
                if (!connected || !showUsage) "—"
                else WidgetCard.formatBytes(WidgetCard.usageBytes(activity, section)))
            val start = WidgetCard.sessionStartMs(activity, section)
            value(R.id.widget_stat_time_value,
                if (!connected || !showTime || start <= 0L) "—"
                else WidgetCard.formatDuration(System.currentTimeMillis() - start))
        }
    }

    /** Small stylized widget thumbnail for the Studio's template
     *  cards: the preset's card, its accent ring button, three
     *  text lines. Drawn, not inflated, so eight cards stay cheap. */
    fun templateThumbBitmap(ctx: android.content.Context, template: String,
        w: Int = 300, h: Int = 150): Bitmap {
        val t = if (template in WidgetStyle.PRESET_KEYS) template else "glass"
        val bgCol = WidgetStyle.presetBg(t)
        val accCol = WidgetStyle.presetAccent(t)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply { isAntiAlias = true }
        if (t == "colorful") {
            bg.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(accCol, WidgetCard.hueShift(accCol, 42f), bgCol),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        } else {
            bg.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(WidgetCard.lighten(bgCol, 0.08f), WidgetCard.darken(bgCol, 0.10f)),
                null, android.graphics.Shader.TileMode.CLAMP)
        }
        val rect = RectF(2f, 2f, w - 2f, h - 2f)
        canvas.drawRoundRect(rect, h * 0.18f, h * 0.18f, bg)
        val stroke = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = accCol
        }
        canvas.drawRoundRect(rect, h * 0.18f, h * 0.18f, stroke)
        // Ring button, connected accent, left side.
        val ring = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = h * 0.07f
            color = accCol
        }
        val cx = h * 0.32f
        val cy = h / 2f
        val r = h * 0.24f
        canvas.drawOval(RectF(cx - r, cy - r, cx + r, cy + r), ring)
        val glyph = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = h * 0.05f
            strokeCap = Paint.Cap.ROUND
            color = accCol
        }
        val g = RectF(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f)
        canvas.drawArc(g, 300f, 300f, false, glyph)
        canvas.drawLine(cx, cy - r * 0.62f, cx, cy - r * 0.05f, glyph)
        // Three text lines, right side.
        val line = Paint().apply { isAntiAlias = true }
        val x0 = h * 0.62f
        val lw = w - x0 - h * 0.15f
        line.color = Color.WHITE
        canvas.drawRoundRect(RectF(x0, h * 0.24f, x0 + lw * 0.9f, h * 0.24f + h * 0.09f),
            h * 0.045f, h * 0.045f, line)
        line.color = Color.parseColor("#B0BEC5")
        canvas.drawRoundRect(RectF(x0, h * 0.46f, x0 + lw * 0.65f, h * 0.46f + h * 0.09f),
            h * 0.045f, h * 0.045f, line)
        canvas.drawRoundRect(RectF(x0, h * 0.68f, x0 + lw * 0.75f, h * 0.68f + h * 0.09f),
            h * 0.045f, h * 0.045f, line)
        return bmp
    }
}
