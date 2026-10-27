package com.renamer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Calculator (login page) themes + button shapes.
 *
 * Prefs (SharedPreferences "renamer_settings"):
 *   "calc_theme" -> theme id, default "pastel"
 *   "calc_shape" -> shape id, default "round"
 *
 * API for the parent to wire into PinActivity (PinActivity
 * itself is NOT edited here):
 *   val t = CalcTheme.theme(ctx)          // CalcTheme.Theme, colors as parsed ints
 *   val shape = CalcTheme.shape(ctx)      // "round" | "square" | "circle"
 *   root.background = CalcTheme.gradient(ctx)
 *   button.background = CalcTheme.buttonBackground(ctx, accent = false)
 *   equalsButton.background = CalcTheme.buttonBackground(ctx, accent = true)
 *   button.setTextColor(t.buttonText)
 *   display.setTextColor(t.display)
 *   // selected theme/shape are only written by
 *   // LoginPersonalizationActivity; PinActivity just reads them.
 *
 * All parsing is guarded: any bad hex falls back to the
 * pastel theme, so a corrupt pref can never crash the
 * login page.
 */
object CalcTheme {

    /** One theme, with colors already parsed to ints. */
    data class Theme(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val gradTop: Int,
        val gradMid: Int,
        val gradBottom: Int,
        val button: Int,
        val buttonText: Int,
        val display: Int,
        val accent: Int
    )

    private data class Def(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val top: String,
        val mid: String,
        val bottom: String,
        val button: String,
        val buttonText: String,
        val display: String,
        val accent: String
    )

    private val DEFS = listOf(
        Def("pastel", "ملایم پاستلی", "Soft pastel",
            "#E8EEF7", "#FDF6EC", "#E6F4EA",
            "#CCFFFFFF", "#333333", "#333333", "#D6EADF"),
        Def("dark", "تیره شیک", "Dark elegant",
            "#232526", "#414345", "#5A5A5A",
            "#33FFFFFF", "#FFFFFF", "#FFFFFF", "#00C9A7"),
        Def("orange", "نارنجی کلاسیک", "Classic orange",
            "#FFF3E0", "#FFE0B2", "#FFCC80",
            "#CCFFFFFF", "#333333", "#333333", "#FF9800"),
        Def("mint", "سبز نعنایی", "Mint green",
            "#E0F2F1", "#B2DFDB", "#80CBC4",
            "#CCFFFFFF", "#333333", "#333333", "#26A69A"),
        Def("purple", "بنفش ملایم", "Soft purple",
            "#EDE7F6", "#D1C4E9", "#B39DDB",
            "#CCFFFFFF", "#333333", "#333333", "#7E57C2"),
        Def("rose", "صورتی گل", "Rose pink",
            "#FCE4EC", "#F8BBD0", "#F48FB1",
            "#CCFFFFFF", "#4A2C35", "#4A2C35", "#EC407A"),
        Def("ocean", "آبی اقیانوس", "Ocean blue",
            "#E3F2FD", "#90CAF9", "#42A5F5",
            "#CCFFFFFF", "#102A43", "#102A43", "#1E88E5"),
        Def("sunset", "غروب نارنجی", "Sunset",
            "#FFF3E0", "#FFCC80", "#FF8A65",
            "#CCFFFFFF", "#4E342E", "#4E342E", "#FF7043"),
        Def("mono", "تک‌رنگ شیک", "Mono chic",
            "#F5F5F5", "#E0E0E0", "#BDBDBD",
            "#CCFFFFFF", "#212121", "#212121", "#616161"),
        Def("tealnight", "سبز تیره شب", "Teal night",
            "#004D40", "#00695C", "#00897B",
            "#33FFFFFF", "#FFFFFF", "#FFFFFF", "#26A69A")
    )

    /** All themes, in display order. */
    fun all(): List<Theme> = DEFS.map { toTheme(it) }

    fun themeId(ctx: Context): String =
        prefs(ctx).getString("calc_theme", "pastel") ?: "pastel"

    fun theme(ctx: Context): Theme {
        val def = DEFS.firstOrNull { it.id == themeId(ctx) } ?: DEFS[0]
        return toTheme(def)
    }

    /** Button shape id: "round" (32dp), "square" (8dp),
     *  "circle" (OVAL), "pill" (stadium), "soft" (16dp). */
    fun shape(ctx: Context): String {
        return when (prefs(ctx).getString("calc_shape", "round")) {
            "square", "circle", "pill", "soft" -> prefs(ctx).getString("calc_shape", "round")!!
            else -> "round"
        }
    }

    fun setTheme(ctx: Context, id: String) {
        if (DEFS.any { it.id == id }) prefs(ctx).edit().putString("calc_theme", id).apply()
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

    /** Full-page background gradient for the calculator page. */
    fun gradient(ctx: Context): GradientDrawable {
        val t = theme(ctx)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(t.gradTop, t.gradMid, t.gradBottom)
        )
    }

    /** Gradient for a theme preview swatch (used by the
     *  personalization page, not by PinActivity). */
    fun gradientFor(theme: Theme): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.gradTop, theme.gradMid, theme.gradBottom)
        )

    /**
     * Calculator button background in the user's chosen
     * shape. accent=true is for the "=" key: it uses the
     * theme's accent color instead of the plain button color.
     * Every button gets a soft translucent border so it
     * reads as glass on top of the gradient.
     */
    fun buttonBackground(ctx: Context, accent: Boolean): GradientDrawable {
        return buttonBackgroundFor(theme(ctx), shape(ctx), accent, ctx)
    }

    /** Same as buttonBackground, but for an explicit theme
     *  and shape (used for shape previews on the
     *  personalization page). */
    fun buttonBackgroundFor(theme: Theme, shape: String, accent: Boolean, ctx: Context): GradientDrawable {
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
        d.setColor(if (accent) theme.accent else theme.button)
        // Soft translucent border: dark on light buttons, light on dark ones.
        val darkButton = isDarkColor(theme.button)
        d.setStroke(
            (2f * density).toInt(),
            if (darkButton) Color.parseColor("#55FFFFFF") else Color.parseColor("#33000000")
        )
        return d
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)

    private fun safe(hex: String, fallback: String): Int {
        return try { Color.parseColor(hex) } catch (_: Exception) {
            try { Color.parseColor(fallback) } catch (_: Exception) { Color.WHITE }
        }
    }

    private fun toTheme(def: Def): Theme = Theme(
        id = def.id,
        nameFa = def.nameFa,
        nameEn = def.nameEn,
        gradTop = safe(def.top, "#E8EEF7"),
        gradMid = safe(def.mid, "#FDF6EC"),
        gradBottom = safe(def.bottom, "#E6F4EA"),
        button = safe(def.button, "#CCFFFFFF"),
        buttonText = safe(def.buttonText, "#333333"),
        display = safe(def.display, "#333333"),
        accent = safe(def.accent, "#D6EADF")
    )

    private fun isDarkColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Relative luminance, 0..1.
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return lum < 0.5
    }
}
