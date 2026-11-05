package com.renamer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Central app log (2.43): one persistent, readable
 *  record of what every section did — connection
 *  attempts with engine state, hop types and
 *  results, plus event-level entries for the rest.
 *  Privacy rule: NEVER message text, config links
 *  or keys here — only names, types and outcomes.
 *  The log is a plain readable list; anything
 *  copied out of it must stay safe to share. */
object AppLog {
    private const val PREFS = "renamer_applog"
    private const val KEY = "lines"
    const val CAP = 500

    private fun stamp(): String = try {
        SimpleDateFormat("HH:mm", Locale.US).format(Date())
    } catch (_: Exception) { "" }

    /** Append one precise entry. fa/en are resolved
     *  in the language active right now (same rule
     *  as the engine reports). */
    fun append(ctx: Context, section: String, fa: String, en: String) {
        try {
            val line = "[${stamp()}] [$section] " + AppLanguage.t(ctx, fa, en)
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = sp.getString(KEY, "") ?: ""
            val lines = (if (old.isBlank()) mutableListOf() else old.lines().toMutableList())
            lines += line
            while (lines.size > CAP) lines.removeAt(0)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    fun text(ctx: Context): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        } catch (_: Exception) {}
    }

    /** Config type label for a link, for precise log
     *  entries. Never returns the link itself. */
    fun cfgType(ctx: Context, link: String): String {
        return try {
            when {
                XraySpeedtest.isGroup(link) -> AppLanguage.t(ctx, "گروه", "Group")
                XraySpeedtest.isChain(link) -> AppLanguage.t(ctx, "زنجیره", "Chain")
                XraySpeedtest.isCustom(link) -> AppLanguage.t(ctx, "کاستوم", "Custom")
                link.startsWith("vless://") -> {
                    val low = link.lowercase()
                    when {
                        low.contains("security=reality") -> "VLESS Reality"
                        low.contains("type=ws") -> "VLESS WS"
                        low.contains("type=grpc") -> "VLESS gRPC"
                        else -> "VLESS"
                    }
                }
                link.startsWith("vmess://") -> "VMess"
                link.startsWith("trojan://") -> "Trojan"
                link.startsWith("ss://") -> "Shadowsocks"
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> "Hysteria2"
                link.startsWith("tuic://") -> "TUIC"
                link.startsWith("socks://") || link.startsWith("http://") -> AppLanguage.t(ctx, "پروکسی", "Proxy")
                else -> AppLanguage.t(ctx, "نامشخص", "Unknown")
            }
        } catch (_: Exception) { AppLanguage.t(ctx, "نامشخص", "Unknown") }
    }

    /** Engine state label, always spelled out so an
     *  entry never leaves doubt about how the
     *  attempt was made. */
    fun engineState(ctx: Context): String {
        return try {
            if (AdaptiveEngine.isEnabled(ctx)) AppLanguage.t(ctx, "موتور هوشمند: روشن", "Smart engine: ON")
            else AppLanguage.t(ctx, "موتور هوشمند: خاموش", "Smart engine: OFF")
        } catch (_: Exception) { "" }
    }
}
