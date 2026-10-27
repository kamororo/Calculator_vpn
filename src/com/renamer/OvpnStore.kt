package com.renamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One OpenVPN profile, as imported from a .ovpn file. */
data class OvpnProfile(
    var name: String,
    var hostname: String,
    var ovpn: String,
    var username: String = "",
    var password: String = "",
    var savePassword: Boolean = true,
    // Try connecting without sending the client
    // certificate/key from the file, only
    // username/password. Some providers ship a
    // generic/expired/revoked client cert that the
    // server rejects with a RST right after it
    // verifies the server cert (VERIFY OK, then
    // "Connection reset"), while the official
    // OpenVPN Connect app, which does not send
    // that cert, connects fine with the same
    // username/password. Default off.
    var noClientCert: Boolean = false,
    // Force the "official-app compatible" TLS mode
    // from the first attempt (see OvpnEngine).
    var tlsCompat: Boolean = false,
    // Opt out of the default compat mode and use
    // normal TLS (1.3) instead. Default false =
    // compat mode is the default from v1.31, because
    // it is what passes servers/middleboxes that
    // RST the OpenSSL 3.5 TLS 1.3 handshake.
    var tlsNormal: Boolean = false
) {
    /** True when the .ovpn asks for a username/password. */
    fun needsAuth(): Boolean {
        for (line in ovpn.lines()) {
            val t = line.trim()
            if (t.startsWith("auth-user-pass", ignoreCase = true)) return true
        }
        return false
    }
    fun hasEmbeddedCredentials(): Boolean {
        return username.isNotBlank()
    }
}

object OvpnStore {
    /** Pending profile being imported or edited, passed
     *  to OpenVpnImportActivity. -1 = new import. */
    var pendingIndex: Int = -1
    var pending: OvpnProfile? = null
    /** Profile to auto-connect when OpenVpnActivity
     *  resumes (set by the Import page's CONNECT). */
    var pendingConnect: OvpnProfile? = null

    fun load(ctx: Context): MutableList<OvpnProfile> {
        val out = mutableListOf<OvpnProfile>()
        try {
            val arr = JSONArray(ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                .getString("ovpn_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += OvpnProfile(
                    name = o.optString("name"),
                    hostname = o.optString("hostname"),
                    ovpn = o.optString("ovpn"),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    savePassword = o.optBoolean("savePassword", true),
                    noClientCert = o.optBoolean("noClientCert", false),
                    tlsCompat = o.optBoolean("tlsCompat", false),
                    tlsNormal = o.optBoolean("tlsNormal", false)
                )
            }
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, profiles: List<OvpnProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("hostname", p.hostname)
                put("ovpn", p.ovpn)
                put("username", p.username)
                put("password", if (p.savePassword) p.password else "")
                put("savePassword", p.savePassword)
                put("noClientCert", p.noClientCert)
                put("tlsCompat", p.tlsCompat)
                put("tlsNormal", p.tlsNormal)
            })
        }
        ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
            .edit().putString("ovpn_list", arr.toString()).apply()
    }

    /** Parse a .ovpn file text. Returns null if it is
     *  not an OpenVPN profile at all. */
    fun parse(text: String, fileName: String?): OvpnProfile? {
        if (text.isBlank()) return null
        var hostname = ""
        var needsClient = false
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("client", ignoreCase = true)) needsClient = true
            if (t.startsWith("remote ", ignoreCase = true)) {
                val parts = t.split("\\s+".toRegex())
                if (parts.size >= 2) hostname = parts[1]
            }
        }
        if (hostname.isBlank() && !needsClient) return null
        if (hostname.isBlank()) return null
        val name = fileName?.removeSuffix(".ovpn")?.removeSuffix(".OVPN")
            ?.removeSuffix(".conf")?.removeSuffix(".CONF")?.takeIf { it.isNotBlank() }
            ?: hostname
        // Embedded credentials, if the file carries them
        // in an <auth-user-pass> block (first two lines).
        var user = ""
        var pass = ""
        val block = Regex("<auth-user-pass>(.*?)</auth-user-pass>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.getOrNull(1)
        if (block != null) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) user = lines[0]
            if (lines.size >= 2) pass = lines[1]
        }
        return OvpnProfile(name = name, hostname = hostname, ovpn = text.trim(),
            username = user, password = pass, savePassword = true)
    }
}
