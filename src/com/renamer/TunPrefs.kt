package com.renamer

import android.content.Context

/** Per-section TUN settings files. v2ray keeps the shared
 *  file ("renamer_vpn") exactly as before. The emergency
 *  section gets its own file, migrated ONCE from the shared
 *  file (so its first isolated start uses the user's current
 *  values), then fully independent: changing v2ray's MTU,
 *  DNS, SOCKS port or per-app set can never affect
 *  emergency, and vice versa. Other sections keep the
 *  shared file for now; the mechanism extends to them by
 *  adding a fileFor() branch plus a builder prefsName.
 *  NOTE: only TUN-session keys matter here (mtu,
 *  vpn_mtu, dns, socks_port, per_app_mode, per_app_set,
 *  routing/mux/fragment keys). The migration copies ALL
 *  keys once; extra keys (servers, selected, ...) are
 *  inert in the section file because nothing reads
 *  them from there. */
object TunPrefs {
    const val SHARED = "renamer_vpn"
    const val EMERGENCY = "renamer_tun_emergency"

    fun fileFor(tag: String?): String =
        if (tag == "emergency") EMERGENCY else SHARED

    fun migrateFileIfNeeded(ctx: Context, targetFile: String) {
        if (targetFile == SHARED) return
        try {
            val t = ctx.getSharedPreferences(targetFile, Context.MODE_PRIVATE)
            if (t.getBoolean("tun_migrated", false)) return
            val s = ctx.getSharedPreferences(SHARED, Context.MODE_PRIVATE)
            val e = t.edit()
            for ((k, v) in s.all) {
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(k, v as Set<String>)
                }
            }
            e.putBoolean("tun_migrated", true).apply()
        } catch (_: Exception) {}
    }
}
