package com.renamer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * Calculator (login page) STUDIO — 2.48.
 *
 * The 14 fixed themes of 1.81–2.47 are DELETED
 * (user order 2026-11-03): no theme list, no
 * wallpaper photos. Everything is drawn: a
 * background design (gradient + glow), a neon
 * accent colour, modern glass keys, an optional
 * neon border around the keys, an optional 3D
 * depth gradient on the keys, and a selectable
 * display text colour — the same kind of access
 * the widget studio gives.
 *
 * Prefs (SharedPreferences "renamer_settings"):
 *   "calc_bg" -> background design id, default "midnight"
 *   "calc_accent" -> neon accent colour int, default cyan #00E5FF
 *   "calc_neon_border" -> Boolean, default false
 *   "calc_3d" -> Boolean, default false
 *   "calc_display_color" -> display text colour int,
 *       -1 = follow the neon accent (default -1)
 *   "calc_shape" / "calc_key_size" /
 *   "calc_display_font" -> kept from before.
 *
 * Migration (once, silent): users who had an old
 * fixed theme keep a similar look — the old theme
 * id maps to the closest drawn design + accent,
 * written as the new prefs. After that the old
 * "calc_theme"/"custom_color" prefs are ignored.
 *
 * API for PinActivity (it just reads prefs):
 *   root.background = CalcTheme.background(ctx)
 *   key.background = CalcTheme.buttonBackground(ctx, accent)
 *   key.setTextColor(if (accent) CalcTheme.accentTextColor(ctx)
 *       else CalcTheme.keyTextColor(ctx))
 *   display.setTextColor(CalcTheme.displayColor(ctx))
 * LoginPersonalizationActivity is the studio page
 * and is the only writer of the studio prefs.
 */
object CalcTheme {

    /** One drawn background design. */
    data class BgDesign(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val top: Int,
        val mid: Int,
        val bottom: Int,
        val glow: Int?,
        val dark: Boolean
    )

    private data class BgDef(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val top: String,
        val mid: String,
        val bottom: String,
        val glow: String?,
        val dark: Boolean
    )

    private val BG_DEFS = listOf(
        BgDef("midnight", "نیمه‌شب", "Midnight",
            "#1E2A5A", "#0B1026", "#000000", "#3B82F6", true),
        BgDef("violet", "بنفش نئونی", "Neon violet",
            "#4C1D95", "#2D1B4E", "#12081F", "#A855F7", true),
        BgDef("emerald", "زمرد", "Emerald",
            "#065F46", "#022C22", "#01120E", "#10B981", true),
        BgDef("sunset", "غروب", "Sunset",
            "#7C2D12", "#3B0A1E", "#1A0505", "#F97316", true),
        BgDef("ocean", "اقیانوس", "Ocean",
            "#0C4A6E", "#082F49", "#020617", "#06B6D4", true),
        BgDef("ruby", "یاقوت", "Ruby",
            "#881337", "#3B0713", "#160209", "#F43F5E", true),
        BgDef("light", "روشن", "Light",
            "#FFFFFF", "#E2E8F0", "#CBD5E1", null, false),
        BgDef("black", "مشکی", "Pure black",
            "#1A1A1A", "#000000", "#000000", null, true),
        BgDef("galaxy", "کهکشان", "Galaxy",
            "#0B1026", "#050514", "#000000", "#7C3AED", true),
        BgDef("colorful", "رنگارنگ", "Colorful",
            "#FF4F8B", "#B04DFF", "#7A4DFF", "#FF4F8B", true),
        BgDef("aurora", "شفق قطبی", "Aurora",
            "#0EA5E9", "#7C3AED", "#EC4899", "#7C3AED", true)
    )

    /** All background designs, in display order. */
    fun bgDesigns(): List<BgDesign> = BG_DEFS.map { d ->
        BgDesign(d.id, d.nameFa, d.nameEn,
            safe(d.top, "#0B1026"), safe(d.mid, "#0B1026"),
            safe(d.bottom, "#000000"),
            d.glow?.let { safe(it, "#3B82F6") }, d.dark)
    }

    private fun designById(id: String): BgDesign =
        bgDesigns().firstOrNull { it.id == id } ?: bgDesigns()[0]

    /** Neon accent swatches offered by the studio. */
    val NEON_SWATCHES = intArrayOf(
        Color.parseColor("#00E5FF"), Color.parseColor("#FF00E5"),
        Color.parseColor("#76FF03"), Color.parseColor("#FFEA00"),
        Color.parseColor("#B388FF"), Color.parseColor("#FF4081"),
        Color.parseColor("#00E676"), Color.parseColor("#FF9100"),
        Color.parseColor("#2979FF"), Color.parseColor("#FF1744")
    )

    /** Display-colour swatches offered by the studio. */
    val DISPLAY_SWATCHES = intArrayOf(
        Color.WHITE, Color.parseColor("#00E676"),
        Color.parseColor("#00E5FF"), Color.parseColor("#FFEA00"),
        Color.parseColor("#FF00E5"), Color.parseColor("#FF9100")
    )

    /** Follow-the-accent sentinel for the display colour. */
    const val DISPLAY_FOLLOW_ACCENT = -1

    // ---------- Studio prefs ----------

    fun bgDesign(ctx: Context): BgDesign {
        val p = prefs(ctx)
        val id = p.getString("calc_bg", null)
        if (id != null) return designById(id)
        // First read after the 2.48 studio: migrate
        // the old fixed theme to the closest design
        // + accent, so the user's look survives the
        // theme deletion instead of jumping to defaults.
        val (bgId, accent) = mapOldTheme(ctx)
        p.edit().putString("calc_bg", bgId).putInt("calc_accent", accent).apply()
        return designById(bgId)
    }

    fun bgId(ctx: Context): String = bgDesign(ctx).id

    fun setBg(ctx: Context, id: String) {
        if (BG_DEFS.any { it.id == id }) {
            prefs(ctx).edit().putString("calc_bg", id).apply()
            clearPreset(ctx)
        }
    }

    fun accent(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains("calc_accent")) return p.getInt("calc_accent", Color.parseColor("#00E5FF"))
        // Triggers the one-time migration, which
        // writes calc_accent as well.
        bgDesign(ctx)
        return p.getInt("calc_accent", Color.parseColor("#00E5FF"))
    }

    fun setAccent(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_accent", color).apply()
    }

    fun neonBorder(ctx: Context): Boolean =
        prefs(ctx).getBoolean("calc_neon_border", false)

    fun setNeonBorder(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("calc_neon_border", on).apply()
        clearPreset(ctx)
    }

    fun depth3d(ctx: Context): Boolean =
        prefs(ctx).getBoolean("calc_3d", false)

    fun setDepth3d(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("calc_3d", on).apply()
        clearPreset(ctx)
    }

    /** Display text colour: always an explicit
     *  colour since 2.50 (no more follow-accent
     *  coupling). A 2.48 follow (-1) migrates once
     *  to the old accent value; fresh users get
     *  white. */
    fun displayColor(ctx: Context): Int {
        val p = prefs(ctx)
        if (!p.contains("calc_display_color")) {
            p.edit().putInt("calc_display_color", Color.WHITE).apply()
            return Color.WHITE
        }
        val stored = p.getInt("calc_display_color", Color.WHITE)
        if (stored == DISPLAY_FOLLOW_ACCENT) {
            val acc = accent(ctx)
            p.edit().putInt("calc_display_color", acc).apply()
            return acc
        }
        return stored
    }

    fun displayColorRaw(ctx: Context): Int =
        prefs(ctx).getInt("calc_display_color", DISPLAY_FOLLOW_ACCENT)

    fun setDisplayColor(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_display_color", color).apply()
        clearPreset(ctx)
    }

    /** "=" key colour, separate from the display
     *  and border colours (2.50 decoupling). First
     *  read after 2.50 fills it from the old accent
     *  so the "=" key keeps its colour. */
    fun eqColor(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains("calc_eq_color")) return p.getInt("calc_eq_color", Color.parseColor("#00E5FF"))
        val acc = accent(ctx)
        p.edit().putInt("calc_eq_color", acc).apply()
        return acc
    }

    fun setEqColor(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_eq_color", color).apply()
        clearPreset(ctx)
    }

    /** Key-border colour, separate from the "="
     *  and display colours. First read fills it
     *  from the old accent. */
    fun borderColor(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains("calc_border_color")) return p.getInt("calc_border_color", Color.parseColor("#00E5FF"))
        val acc = accent(ctx)
        p.edit().putInt("calc_border_color", acc).apply()
        return acc
    }

    fun setBorderColor(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_border_color", color).apply()
        clearPreset(ctx)
    }

    /** Key-border thickness in dp (2.50 slider). */
    fun borderWidthDp(ctx: Context): Float =
        prefs(ctx).getFloat("calc_border_width_dp", 2.5f)

    fun setBorderWidthDp(ctx: Context, dp: Float) {
        prefs(ctx).edit().putFloat("calc_border_width_dp", dp).apply()
        clearPreset(ctx)
    }

    /** Key-glass alpha 0..255. Untouched users
     *  keep the legacy alpha (dark bg 0x33, light
     *  bg 0xCC) until they move the slider. */
    fun keyAlpha(ctx: Context): Int {
        val p = prefs(ctx)
        if (!p.contains("calc_key_transparency")) {
            return if (bgDesign(ctx).dark) 0x33 else 0xCC
        }
        val t = p.getInt("calc_key_transparency", 40)
        return (255 * (1 - t / 100f)).toInt().coerceIn(26, 255)
    }

    /** Key transparency % (0 = opaque, 100 =
     *  nearly invisible). Untouched users see the
     *  value their legacy alpha maps to. */
    fun keyTransparency(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains("calc_key_transparency")) return p.getInt("calc_key_transparency", 40)
        val legacy = if (bgDesign(ctx).dark) 0x33 else 0xCC
        return (100 * (1 - legacy / 255f)).toInt()
    }

    fun setKeyTransparency(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt("calc_key_transparency", percent.coerceIn(0, 100)).apply()
        clearPreset(ctx)
    }

    /** The colorful design's two user-settable
     *  colours (defaults = the widget studio's
     *  colorful preset). */
    fun bgColor1(ctx: Context): Int =
        prefs(ctx).getInt("calc_bg_c1", Color.parseColor("#FF4F8B"))

    fun bgColor2(ctx: Context): Int =
        prefs(ctx).getInt("calc_bg_c2", Color.parseColor("#7A4DFF"))

    fun setBgColor1(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_bg_c1", color).apply()
        clearPreset(ctx)
    }

    fun setBgColor2(ctx: Context, color: Int) {
        prefs(ctx).edit().putInt("calc_bg_c2", color).apply()
        clearPreset(ctx)
    }

    // ---------- Theme presets (2.50) ----------

    /** A theme is a one-tap preset over the
     *  studio prefs — NOT a separate theme
     *  system. Applying one writes every field
     *  below; any later individual change clears
     *  the preset so the row can say "custom". */
    data class Preset(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val bgId: String,
        val bgC1: Int?,
        val bgC2: Int?,
        val eq: Int,
        val display: Int,
        val borderOn: Boolean,
        val borderColor: Int,
        val borderWidth: Float,
        val depth3d: Boolean,
        val transparency: Int
    )

    fun presets(): List<Preset> = listOf(
        Preset("neon_midnight", "نئون نیمه‌شب", "Neon midnight",
            "midnight", null, null,
            Color.parseColor("#00E5FF"), Color.parseColor("#00E5FF"),
            true, Color.parseColor("#00E5FF"), 2.5f, true, 40),
        Preset("galaxy", "کهکشان", "Galaxy",
            "galaxy", null, null,
            Color.parseColor("#B388FF"), Color.WHITE,
            true, Color.parseColor("#B388FF"), 2.5f, true, 45),
        Preset("colorful", "رنگارنگ", "Colorful",
            "colorful", Color.parseColor("#FF4F8B"), Color.parseColor("#7A4DFF"),
            Color.parseColor("#FF4F8B"), Color.WHITE,
            true, Color.parseColor("#FF4F8B"), 2.5f, true, 35),
        Preset("aurora", "شفق قطبی", "Aurora",
            "aurora", null, null,
            Color.parseColor("#06B6D4"), Color.parseColor("#E0F2FE"),
            true, Color.parseColor("#06B6D4"), 2.0f, true, 40),
        Preset("emerald", "زمرد", "Emerald",
            "emerald", null, null,
            Color.parseColor("#00E676"), Color.parseColor("#00E676"),
            false, Color.parseColor("#00E676"), 2.5f, false, 40),
        Preset("sunset", "غروب", "Sunset",
            "sunset", null, null,
            Color.parseColor("#FF9100"), Color.parseColor("#FFEA00"),
            true, Color.parseColor("#FF9100"), 2.0f, true, 40),
        Preset("ruby", "یاقوت", "Ruby",
            "ruby", null, null,
            Color.parseColor("#FF4081"), Color.WHITE,
            true, Color.parseColor("#FF4081"), 3.0f, true, 35),
        Preset("light_minimal", "مینیمال روشن", "Light minimal",
            "light", null, null,
            Color.parseColor("#0F766E"), Color.parseColor("#333333"),
            false, Color.parseColor("#0F766E"), 2.5f, false, 20),
        Preset("pure_black", "مشکی خالص", "Pure black",
            "black", null, null,
            Color.WHITE, Color.WHITE,
            true, Color.WHITE, 1.5f, false, 80)
    )

    fun presetId(ctx: Context): String =
        prefs(ctx).getString("calc_theme_preset", "") ?: ""

    fun presetById(id: String): Preset? = presets().firstOrNull { it.id == id }

    fun applyPreset(ctx: Context, preset: Preset) {
        val e = prefs(ctx).edit()
        e.putString("calc_bg", preset.bgId)
        if (preset.bgC1 != null) e.putInt("calc_bg_c1", preset.bgC1)
        if (preset.bgC2 != null) e.putInt("calc_bg_c2", preset.bgC2)
        e.putInt("calc_eq_color", preset.eq)
        e.putInt("calc_display_color", preset.display)
        e.putBoolean("calc_neon_border", preset.borderOn)
        e.putInt("calc_border_color", preset.borderColor)
        e.putFloat("calc_border_width_dp", preset.borderWidth)
        e.putBoolean("calc_3d", preset.depth3d)
        e.putInt("calc_key_transparency", preset.transparency)
        e.putString("calc_theme_preset", preset.id)
        e.apply()
    }

    private fun clearPreset(ctx: Context) {
        if (presetId(ctx).isNotEmpty()) {
            prefs(ctx).edit().putString("calc_theme_preset", "").apply()
        }
    }

    /** Key text colour: white on dark backgrounds,
     *  dark grey on light ones. */
    fun keyTextColor(ctx: Context): Int =
        if (bgDesign(ctx).dark) Color.WHITE else Color.parseColor("#333333")

    /** "=" key text colour: readable on the
     *  "=" key's own colour. */
    fun accentTextColor(ctx: Context): Int =
        if (isDark(eqColor(ctx))) Color.WHITE else Color.parseColor("#333333")

    /** Reset the studio (design + colours + effects)
     *  to the defaults. Shape, size and font are
     *  separate settings and are left alone. */
    fun resetStudio(ctx: Context) {
        prefs(ctx).edit()
            .putString("calc_bg", "midnight")
            .putInt("calc_eq_color", Color.parseColor("#00E5FF"))
            .putInt("calc_display_color", Color.WHITE)
            .putBoolean("calc_neon_border", false)
            .putInt("calc_border_color", Color.parseColor("#00E5FF"))
            .putFloat("calc_border_width_dp", 2.5f)
            .putBoolean("calc_3d", false)
            .putInt("calc_key_transparency", 40)
            .putString("calc_theme_preset", "")
            .apply()
    }

    // ---------- Kept settings: shape / size / font ----------

    /** Button shape id: "round" (32dp), "square" (8dp),
     *  "circle" (OVAL), "pill" (stadium), "soft" (16dp). */
    fun shape(ctx: Context): String {
        return when (prefs(ctx).getString("calc_shape", "round")) {
            "square", "circle", "pill", "soft" -> prefs(ctx).getString("calc_shape", "round")!!
            else -> "round"
        }
    }

    fun setShape(ctx: Context, shape: String) {
        if (shape in listOf("round", "square", "circle", "pill", "soft")) {
            prefs(ctx).edit().putString("calc_shape", shape).apply()
        }
    }

    /** Key size scale: "small" 0.85, "normal" 1.0, "large" 1.15. */
    fun keyScale(ctx: Context): Float {
        return when (prefs(ctx).getString("calc_key_size", "normal")) {
            "small" -> 0.85f
            "large" -> 1.15f
            else -> 1.0f
        }
    }

    fun setKeySize(ctx: Context, size: String) {
        if (size in listOf("small", "normal", "large")) {
            prefs(ctx).edit().putString("calc_key_size", size).apply()
        }
    }

    /** Display font: "normal", "bold", "mono". */
    fun displayFont(ctx: Context): String {
        return when (prefs(ctx).getString("calc_display_font", "bold")) {
            "normal", "mono" -> prefs(ctx).getString("calc_display_font", "bold")!!
            else -> "bold"
        }
    }

    fun setDisplayFont(ctx: Context, font: String) {
        if (font in listOf("normal", "bold", "mono")) {
            prefs(ctx).edit().putString("calc_display_font", font).apply()
        }
    }

    // ---------- Drawables ----------

    /** Full-page background for PinActivity. */
    fun background(ctx: Context): android.graphics.drawable.Drawable =
        backgroundForDesign(ctx, bgDesign(ctx))

    /** Background drawable for one design, at any
     *  size — used by PinActivity AND by the
     *  studio's tiles and previews. Galaxy layers
     *  the drawn star sky over its dark gradient;
     *  colorful uses its two user-settable colours;
     *  the rest use their gradient + top glow.
     *  All drawn — no photo is ever used here. */
    fun backgroundForDesign(ctx: Context, design: BgDesign): android.graphics.drawable.Drawable {
        val dm = ctx.resources.displayMetrics
        if (design.id == "galaxy") {
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(design.top, design.mid, design.bottom)
            )
            return LayerDrawable(arrayOf(base, CalcGalaxyDrawable(dm.density)))
        }
        val linear: GradientDrawable
        val glow: Int?
        if (design.id == "colorful") {
            val c1 = bgColor1(ctx)
            val c2 = bgColor2(ctx)
            linear = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c1, mixA(c1, c2, 0.5f), c2)
            )
            glow = c2
        } else {
            linear = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(design.top, design.mid, design.bottom)
            )
            glow = design.glow
        }
        if (glow == null) return linear
        val radial = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = (dm.widthPixels * 0.9f).coerceAtLeast(1f)
            setGradientCenter(0.5f, 0.0f)
            colors = intArrayOf(
                (glow and 0x00FFFFFF) or 0x66000000,
                Color.TRANSPARENT
            )
        }
        return LayerDrawable(arrayOf(linear, radial))
    }

    /** Calculator key background in the user's
     *  chosen shape. accent=true is the "=" key:
     *  it uses the neon accent instead of the
     *  glass fill. Modern glass by default; the
     *  neon-border toggle swaps the soft border
     *  for an accent rim; the 3D toggle turns the
     *  fill into a lit-top / shaded-bottom gradient
     *  so the key reads as having depth. */
    fun buttonBackground(ctx: Context, accent: Boolean): GradientDrawable =
        keyBackground(shape(ctx), accent, ctx)

    /** Same as buttonBackground, but for an explicit
     *  shape (used by the studio's shape previews). */
    fun keyBackground(shape: String, accent: Boolean, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        val density = ctx.resources.displayMetrics.density
        when (shape) {
            "circle" -> d.shape = GradientDrawable.OVAL
            "square" -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 8f * density
            }
            "pill" -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 100f * density
            }
            "soft" -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 16f * density
            }
            else -> {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = 32f * density
            }
        }
        val darkBg = bgDesign(ctx).dark
        val acc = eqColor(ctx)
        val fill = if (accent) acc
            else Color.argb(keyAlpha(ctx), 255, 255, 255)
        if (depth3d(ctx)) {
            d.orientation = GradientDrawable.Orientation.TOP_BOTTOM
            d.colors = intArrayOf(
                mixA(fill, Color.WHITE, 0.30f),
                fill,
                mixA(fill, Color.BLACK, 0.28f)
            )
        } else {
            d.setColor(fill)
        }
        if (neonBorder(ctx)) {
            val rim = if (accent) mixA(acc, Color.WHITE, 0.45f) else borderColor(ctx)
            d.setStroke((borderWidthDp(ctx) * density).toInt().coerceAtLeast(1), rim)
        } else {
            val darkButton = isDark(fill)
            d.setStroke(
                (2f * density).toInt(),
                if (darkButton) Color.parseColor("#55FFFFFF") else Color.parseColor("#33000000")
            )
        }
        return d
    }

    // ---------- Helpers ----------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)

    private fun safe(hex: String, fallback: String): Int {
        return try { Color.parseColor(hex) } catch (_: Exception) {
            try { Color.parseColor(fallback) } catch (_: Exception) { Color.WHITE }
        }
    }

    /** Old fixed theme -> (design, accent). Only
     *  used once, on the first studio read after
     *  the 2.48 migration. */
    private fun mapOldTheme(ctx: Context): Pair<String, Int> {
        val p = prefs(ctx)
        val old = p.getString("calc_theme", "pastel") ?: "pastel"
        if (old == "custom") {
            val c = p.getInt("custom_color", Color.parseColor("#0F766E"))
            return Pair(if (isDark(c)) "midnight" else "light", c)
        }
        return when (old) {
            "dark" -> Pair("midnight", Color.parseColor("#00C9A7"))
            "orange" -> Pair("sunset", Color.parseColor("#FF9800"))
            "mint" -> Pair("emerald", Color.parseColor("#26A69A"))
            "purple" -> Pair("violet", Color.parseColor("#7E57C2"))
            "rose" -> Pair("ruby", Color.parseColor("#EC407A"))
            "ocean" -> Pair("ocean", Color.parseColor("#1E88E5"))
            "sunset" -> Pair("sunset", Color.parseColor("#FF7043"))
            "mono" -> Pair("light", Color.parseColor("#616161"))
            "tealnight" -> Pair("emerald", Color.parseColor("#26A69A"))
            "galaxy_blue" -> Pair("ocean", Color.parseColor("#6C6C9E"))
            "galaxy_soft" -> Pair("violet", Color.parseColor("#9575CD"))
            "galaxy_neon" -> Pair("violet", Color.parseColor("#00E5FF"))
            "galaxy_starry" -> Pair("midnight", Color.parseColor("#4DD0E1"))
            else -> Pair("light", Color.parseColor("#0F766E"))
        }
    }

    /** Relative luminance, 0..1. Public: the studio
     *  needs it for swatch check colours. */
    fun luminance(color: Int): Double {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    fun isDark(color: Int): Boolean = luminance(color) < 0.5

    fun mixA(a: Int, b: Int, t: Float): Int {
        val al = (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt()
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val b2 = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.argb(al, r, g, b2)
    }
}
