package com.renamer

import android.content.Context
import android.graphics.Color

/**
 * Shared day/night theme, copied from HomeActivity.isDarkMode
 * so every page uses the same decision (theme_mode, with
 * empty falling back to the old dark_mode boolean).
 */
object Theme {
    fun themeMode(ctx: Context): String {
        return ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .getString("theme_mode", "") ?: ""
    }

    fun isDark(ctx: Context): Boolean {
        return when (themeMode(ctx)) {
            "dark" -> true
            "light" -> false
            "auto" -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            "night" -> {
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                h >= 18 || h < 6
            }
            else -> ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
                .getBoolean("dark_mode", false)
        }
    }

    fun bg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#121212") else Color.WHITE

    fun fg(ctx: Context): Int =
        if (isDark(ctx)) Color.WHITE else Color.BLACK

    fun subFg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#BBBBBB") else Color.parseColor("#555555")

    fun divider(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#333333") else Color.parseColor("#DDDDDD")

    fun card(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#1E1E1E") else Color.parseColor("#F5F5F5")
}
