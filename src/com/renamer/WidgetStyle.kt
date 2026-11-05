package com.renamer

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Per-widget Studio settings (2.32, extended 2.33), stored in
 *  "renamer_widget_style".
 *
 *  Per widget id: section/title/style/size/color/icon/showname,
 *  chat/chatmode for chat widgets, iconbg/btnshape/btncolor for
 *  the 2.33 icon + connect-button customization.
 *  Section-level keys (style_section_, color_section_) are the
 *  fallback and also restyle the older section widgets.
 *
 *  The pending slot holds the config of a widget being pinned:
 *  the system hands out no widget id in advance, so the next
 *  onUpdate claims the pending config for its new id.
 *  The draft slot holds the Studio's current choices, saved on
 *  every change, so leaving and returning loses nothing.
 *  Hidden messages (per chat id) are the user's local hides of
 *  received messages; the widget and the mini-chat both filter
 *  them, the messenger does not. */
object WidgetStyle {
    const val PREFS = "renamer_widget_style"
    private const val PENDING = "pending_json"
    private const val DRAFT = "draft_json"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun g(ctx: Context, key: String, def: String): String =
        try { prefs(ctx).getString(key, def) ?: def } catch (_: Exception) { def }

    fun sectionForWidget(ctx: Context, id: Int) = g(ctx, "section_$id", "")

    fun titleForWidget(ctx: Context, id: Int) = g(ctx, "title_$id", "")

    fun styleForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "style_$id", "")
        if (v.isNotBlank()) return v
        val sec = sectionForWidget(ctx, id)
        val s = if (sec.isNotBlank()) g(ctx, "style_section_$sec", "") else ""
        return if (s.isNotBlank()) s else "modern"
    }

    /** MLM Widget Studio presets (1.2.45, exact values):
     *  eight looks. Picking one in the Studio applies its whole
     *  design (accent, card, opacity, corner, button, shown
     *  info); the user may then change any part. Blank means
     *  "no template picked": the legacy style path
     *  (styleForWidget/bgRes) renders the widget as before, so
     *  existing widgets never change looks by themselves. */
    val PRESET_KEYS = listOf("glass", "minimal", "neon", "colorful",
        "compact", "dark_pro", "ios", "wave")

    fun templateForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "template_$id", "")
        return if (v in PRESET_KEYS) v else ""
    }

    /** MLM's exact preset values (StudioPresets, 1.2.45). */
    fun presetAccent(t: String): Int = when (t) {
        "minimal" -> Color.parseColor("#E9EBEE")
        "neon" -> Color.parseColor("#B46CFF")
        "colorful" -> Color.parseColor("#FF4F8B")
        "compact" -> Color.parseColor("#34C759")
        "dark_pro" -> Color.parseColor("#22C55E")
        "ios" -> Color.parseColor("#0A84FF")
        "wave" -> Color.parseColor("#8B5CF6")
        else -> Color.parseColor("#39E58C")
    }

    fun presetBg(t: String): Int = when (t) {
        "minimal" -> Color.parseColor("#131518")
        "neon" -> Color.parseColor("#0B0718")
        "colorful" -> Color.parseColor("#7A4DFF")
        "compact" -> Color.parseColor("#101418")
        "dark_pro" -> Color.parseColor("#06080B")
        "ios" -> Color.parseColor("#F2F2F7")
        "wave" -> Color.parseColor("#171447")
        else -> Color.parseColor("#0F2236")
    }

    fun presetOpacity(t: String): Int = when (t) {
        "glass" -> 46
        "minimal" -> 94
        "neon" -> 90
        "colorful" -> 100
        "compact" -> 90
        "dark_pro" -> 96
        "ios" -> 80
        "wave" -> 94
        else -> 55
    }

    fun presetCorner(t: String): Int = when (t) {
        "minimal" -> 22
        "neon" -> 26
        "compact", "dark_pro" -> 24
        "ios" -> 30
        else -> 28
    }

    fun presetButton(t: String): String = when (t) {
        "minimal", "colorful", "compact", "ios", "wave" -> "pill"
        "neon" -> "glow"
        else -> "ring"
    }

    /** Shown-info keys per preset (our keys: method, status,
     *  speed, usage, time, chart, icon). */
    fun presetShow(t: String): Set<String> = when (t) {
        "minimal" -> setOf("method", "speed", "icon")
        "neon" -> setOf("method", "status", "icon")
        "colorful" -> setOf("method", "speed", "icon")
        "compact" -> setOf("method", "icon")
        "dark_pro" -> setOf("method", "status", "speed", "usage", "time", "chart", "icon")
        "ios" -> setOf("method", "status", "speed", "icon")
        "wave" -> setOf("method", "speed", "icon")
        else -> setOf("method", "status", "speed", "icon")
    }

    /** The button/lamp/highlight colour (MLM's accent): the
     *  wheel colour when set, else the preset's accent. */
    fun accentForWidget(ctx: Context, id: Int): Int {
        val v = try { prefs(ctx).getInt("btncolor_$id", -1) } catch (_: Exception) { -1 }
        if (v != -1) return v
        val t = templateForWidget(ctx, id)
        return if (t.isNotBlank()) presetAccent(t) else effectiveBtnColor(ctx, id)
    }

    /** Card colour override (-1 = the preset's card). */
    fun bgForWidget(ctx: Context, id: Int): Int {
        val v = try { prefs(ctx).getInt("bgcolor_$id", -1) } catch (_: Exception) { -1 }
        if (v != -1) return v
        val t = templateForWidget(ctx, id)
        return if (t.isNotBlank()) presetBg(t) else -1
    }

    /** Card opacity 0..100 (-1 = the preset's opacity). */
    fun opacityForWidget(ctx: Context, id: Int): Int {
        val v = try { prefs(ctx).getInt("opacity_$id", -1) } catch (_: Exception) { -1 }
        if (v != -1) return v
        val t = templateForWidget(ctx, id)
        return if (t.isNotBlank()) presetOpacity(t) else -1
    }

    /** Corner radius dp (-1 = the preset's corner). */
    fun cornerForWidget(ctx: Context, id: Int): Int {
        val v = try { prefs(ctx).getInt("corner_$id", -1) } catch (_: Exception) { -1 }
        if (v != -1) return v
        val t = templateForWidget(ctx, id)
        return if (t.isNotBlank()) presetCorner(t) else -1
    }

    /** Text scale percent, 85..125 (default 100). */
    fun textScaleForWidget(ctx: Context, id: Int): Int {
        val v = try { prefs(ctx).getInt("textscale_$id", -1) } catch (_: Exception) { -1 }
        return if (v in 85..125) v else 100
    }

    /** 2.36 info toggles (MLM's info tab): all default ON. */
    fun showSpeedsForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showspeeds_$id", true) } catch (_: Exception) { true }

    fun showStatusForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showstatus_$id", true) } catch (_: Exception) { true }

    fun showIconForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showicon_$id", true) } catch (_: Exception) { true }

    fun showMethodForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showmethod_$id", true) } catch (_: Exception) { true }

    fun showUsageForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showusage_$id", false) } catch (_: Exception) { false }

    fun showTimeForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showtime_$id", false) } catch (_: Exception) { false }

    fun showChartForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showchart_$id", false) } catch (_: Exception) { false }

    fun templateBgRes(template: String) = when (template) {
        "glass" -> R.drawable.widget_bg_pro_glass
        "minimal" -> R.drawable.widget_bg_pro_minimal
        "neon" -> R.drawable.widget_bg_pro_neon
        else -> R.drawable.widget_bg
    }

    /** Pro templates are all dark: white name, soft grey subtext
     *  (neon gets a lavender tint). */
    fun templateTextColor(template: String): Int = Color.WHITE

    fun templateSubTextColor(template: String): Int = when (template) {
        "neon" -> Color.parseColor("#E1BEE7")
        else -> Color.parseColor("#B0BEC5")
    }

    fun styleForSection(ctx: Context, key: String): String {
        val s = g(ctx, "style_section_$key", "")
        return if (s.isNotBlank()) s else "modern"
    }

    fun colorForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "color_$id", "")
        if (v.isNotBlank()) return v
        val sec = sectionForWidget(ctx, id)
        val s = if (sec.isNotBlank()) g(ctx, "color_section_$sec", "") else ""
        return if (s.isNotBlank()) s else "blue"
    }

    fun iconForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "icon_$id", "")
        return if (v.isNotBlank()) v else "icon"
    }

    fun showNameForWidget(ctx: Context, id: Int): Boolean =
        try { prefs(ctx).getBoolean("showname_$id", true) } catch (_: Exception) { true }

    fun chatForWidget(ctx: Context, id: Int) = g(ctx, "chat_$id", "")

    fun chatModeForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "chatmode_$id", "")
        return if (v.isNotBlank()) v else "chat"
    }

    /** Icon background colour (ARGB int), or -1 when unset. */
    fun iconBgForWidget(ctx: Context, id: Int): Int =
        try { prefs(ctx).getInt("iconbg_$id", -1) } catch (_: Exception) { -1 }

    /** Effective icon background: the wheel colour when set,
     *  else the classic fixed colour dot. */
    fun effectiveIconBg(ctx: Context, id: Int): Int {
        val v = iconBgForWidget(ctx, id)
        return if (v != -1) v else colorDot(colorForWidget(ctx, id))
    }

    /** Connect-button shape: circle, rounded, pill, diamond. */
    fun btnShapeForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "btnshape_$id", "")
        return if (v.isNotBlank()) v else "circle"
    }

    /** Connect mode for a section widget: "any" tries the section's
     *  configs in order until one connects; blank/"last" connects
     *  to the last config (the 2.33/2.34 behaviour). Set from the
     *  Studio's connection-mode pills (not for chat/emergency). */
    fun connModeForWidget(ctx: Context, id: Int): String {
        val v = g(ctx, "connmode_$id", "")
        return if (v == "any") "any" else "last"
    }

    fun setConnMode(ctx: Context, id: Int, mode: String) {
        try { prefs(ctx).edit().putString("connmode_$id", mode).apply() }
        catch (_: Exception) {}
    }

    /** Connect-button colour (ARGB int), or -1 when unset. */
    fun btnColorForWidget(ctx: Context, id: Int): Int =
        try { prefs(ctx).getInt("btncolor_$id", -1) } catch (_: Exception) { -1 }

    fun effectiveBtnColor(ctx: Context, id: Int): Int {
        val v = btnColorForWidget(ctx, id)
        return if (v != -1) v else Color.parseColor("#0F766E")
    }

    fun bgRes(style: String) = when (style) {
        "minimal" -> R.drawable.widget_bg_minimal
        "neon" -> R.drawable.widget_bg_neon
        "modern" -> R.drawable.widget_bg_modern
        "galaxy" -> R.drawable.widget_bg_galaxy
        "gold" -> R.drawable.widget_bg_gold
        "ocean" -> R.drawable.widget_bg_ocean
        "sunset" -> R.drawable.widget_bg_sunset
        "forest" -> R.drawable.widget_bg_forest
        "midnight" -> R.drawable.widget_bg_midnight
        else -> R.drawable.widget_bg
    }

    /** Styles with white text (dark cards). */
    fun isDark(style: String) = style in setOf(
        "modern", "neon", "galaxy", "gold", "ocean", "sunset", "forest", "midnight")

    /** Title colour picked per theme so text stays readable:
     *  light themes get dark ink, dark themes get white. */
    fun textColor(style: String): Int = when (style) {
        "glass" -> Color.parseColor("#1A237E")
        "minimal" -> Color.parseColor("#1A1A1A")
        else -> Color.WHITE
    }

    /** Secondary text colour per theme (status, speeds). */
    fun subTextColor(style: String): Int = when (style) {
        "glass", "minimal" -> Color.parseColor("#455A64")
        "gold" -> Color.parseColor("#FFECB3")
        "ocean" -> Color.parseColor("#E0F2F1")
        "sunset" -> Color.parseColor("#FCE4EC")
        "forest" -> Color.parseColor("#E8F5E9")
        else -> Color.parseColor("#E0E0E0")
    }

    fun offOrbRes(color: String) = when (color) {
        "pink" -> R.drawable.widget_circle_off_pink
        "lavender" -> R.drawable.widget_circle_off_lavender
        "orange" -> R.drawable.widget_circle_off_orange
        "mint" -> R.drawable.widget_circle_off_mint
        else -> R.drawable.widget_circle_off
    }

    fun colorDot(color: String) = when (color) {
        "pink" -> Color.parseColor("#E91E63")
        "lavender" -> Color.parseColor("#9575CD")
        "orange" -> Color.parseColor("#FF9800")
        "mint" -> Color.parseColor("#26A69A")
        else -> Color.parseColor("#2196F3")
    }

    fun saveWidget(ctx: Context, id: Int, section: String, title: String,
        style: String, size: String, color: String, icon: String, showName: Boolean,
        iconBg: Int = -1, btnShape: String = "circle", btnColor: Int = -1,
        template: String = "", showSpeeds: Boolean = true,
        showStatus: Boolean = true, showIcon: Boolean = true,
        bgColor: Int = -1, opacity: Int = -1, corner: Int = -1,
        textScale: Int = -1, showMethod: Boolean = true,
        showUsage: Boolean = false, showTime: Boolean = false,
        showChart: Boolean = false) {
        prefs(ctx).edit()
            .putString("section_$id", section)
            .putString("title_$id", title)
            .putString("template_$id", template)
            .putBoolean("showspeeds_$id", showSpeeds)
            .putBoolean("showstatus_$id", showStatus)
            .putBoolean("showicon_$id", showIcon)
            .putBoolean("showmethod_$id", showMethod)
            .putBoolean("showusage_$id", showUsage)
            .putBoolean("showtime_$id", showTime)
            .putBoolean("showchart_$id", showChart)
            .putInt("bgcolor_$id", bgColor)
            .putInt("opacity_$id", opacity)
            .putInt("corner_$id", corner)
            .putInt("textscale_$id", textScale)
            .putString("style_$id", style)
            .putString("size_$id", size)
            .putString("color_$id", color)
            .putString("icon_$id", icon)
            .putBoolean("showname_$id", showName)
            .putInt("iconbg_$id", iconBg)
            .putString("btnshape_$id", btnShape)
            .putInt("btncolor_$id", btnColor)
            .putString("style_section_$section", style)
            .putString("color_section_$section", color)
            .apply()
    }

    fun saveChatWidget(ctx: Context, id: Int, title: String, style: String,
        size: String, color: String, chatId: String, mode: String) {
        prefs(ctx).edit()
            .putString("title_$id", title)
            .putString("style_$id", style)
            .putString("size_$id", size)
            .putString("color_$id", color)
            .putString("chat_$id", chatId)
            .putString("chatmode_$id", mode)
            .apply()
    }

    fun setPending(ctx: Context, json: String) {
        prefs(ctx).edit().putString(PENDING, json).apply()
    }

    fun takePending(ctx: Context): String? {
        val v = g(ctx, PENDING, "")
        if (v.isBlank()) return null
        prefs(ctx).edit().remove(PENDING).apply()
        return v
    }

    fun setDraft(ctx: Context, json: String) {
        prefs(ctx).edit().putString(DRAFT, json).apply()
    }

    fun getDraft(ctx: Context): String? {
        val v = g(ctx, DRAFT, "")
        return if (v.isBlank()) null else v
    }

    fun clearWidget(ctx: Context, id: Int) {
        val e = prefs(ctx).edit()
        for (k in listOf("section_", "title_", "style_", "size_", "color_",
            "icon_", "showname_", "chat_", "chatmode_", "cache_",
            "iconbg_", "btnshape_", "btncolor_", "connmode_", "template_",
            "showspeeds_", "showstatus_", "showicon_", "bgcolor_",
            "opacity_", "corner_", "textscale_", "showmethod_",
            "showusage_", "showtime_", "showchart_"))
            e.remove(k + id)
        e.apply()
    }

    /** Locally hidden received messages, per chat id. */
    fun hiddenFor(ctx: Context, chatId: String): MutableSet<Long> {
        val out = mutableSetOf<Long>()
        try {
            val arr = JSONArray(g(ctx, "hidden_$chatId", "[]"))
            for (i in 0 until arr.length()) out += arr.optLong(i)
        } catch (_: Exception) {}
        return out
    }

    fun hideMessage(ctx: Context, chatId: String, ts: Long) {
        val set = hiddenFor(ctx, chatId)
        set += ts
        try {
            val arr = JSONArray()
            for (t in set) arr.put(t)
            prefs(ctx).edit().putString("hidden_$chatId", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Chat message cache: JSONArray of {ts, mine, text}; used when
     *  the widget refreshes while offline, so it never goes blank. */
    fun saveChatCache(ctx: Context, id: Int, rows: List<Triple<Long, Boolean, String>>) {
        try {
            val arr = JSONArray()
            for ((ts, mine, text) in rows) {
                val o = JSONObject()
                o.put("ts", ts)
                o.put("mine", mine)
                o.put("text", text)
                arr.put(o)
            }
            prefs(ctx).edit().putString("cache_$id", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadChatCache(ctx: Context, id: Int): List<Triple<Long, Boolean, String>> {
        val out = mutableListOf<Triple<Long, Boolean, String>>()
        try {
            val arr = JSONArray(g(ctx, "cache_$id", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Triple(o.optLong("ts"), o.optBoolean("mine"), o.optString("text"))
            }
        } catch (_: Exception) {}
        return out
    }
}
