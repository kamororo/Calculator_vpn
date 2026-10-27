package com.renamer

import android.content.Context

/** App language, chosen in the v2ray Settings section.
 *  Stored in renamer_settings, key "app_language":
 *  "fa" (default) or "en". t() returns the Persian
 *  string by default, the English one when English is
 *  selected. Applied to the v2ray page, the home menu
 *  and the emergency page; other sections keep their
 *  current strings and can be moved to t() later. */
object AppLanguage {
    fun isEnglish(ctx: Context): Boolean {
        return ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .getString("app_language", "fa") == "en"
    }

    fun t(ctx: Context, fa: String, en: String): String {
        return if (isEnglish(ctx)) en else fa
    }

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .edit().putString("app_language", lang).apply()
    }
}
