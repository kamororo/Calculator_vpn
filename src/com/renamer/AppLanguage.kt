package com.renamer

import android.content.Context

/** App language, chosen in the v2ray Settings section.
 *  Stored in renamer_settings, key "app_language":
 *  "fa" or "en". When nothing is stored yet (first
 *  launch), the phone's language decides: a Persian
 *  phone gets Persian, anything else gets English.
 *  Once the user picks a language in the app, that
 *  choice wins from then on. t() returns the Persian
 *  string by default, the English one when English is
 *  selected. Applied to the v2ray page, the home menu
 *  and the emergency page; other sections keep their
 *  current strings and can be moved to t() later. */
object AppLanguage {
    fun isEnglish(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
        if (sp.contains("app_language")) {
            return sp.getString("app_language", "fa") == "en"
        }
        // First launch: follow the phone's language.
        return java.util.Locale.getDefault().language != "fa"
    }

    fun t(ctx: Context, fa: String, en: String): String {
        return if (isEnglish(ctx)) en else fa
    }

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .edit().putString("app_language", lang).apply()
    }
}
