package com.renamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SlipNet profiles and settings, stored in SharedPreferences
 *  (renamer_slipnet / renamer_slipnet_settings), following the
 *  SlipNet app's own model (ServerProfile.kt / TunnelType):
 *  DNSTT, DNSTT+SSH, NoizDNS, NoizDNS+SSH, VayDNS, VayDNS+SSH,
 *  Slipstream, Slipstream+SSH, SSH, NaiveProxy, NaiveProxy+SSH,
 *  SOCKS5, VLESS, DOH, Tor, plus Chain and Import.
 *  Profiles are never hard-coded and are only removed by an
 *  explicit per-row delete, like subscription links. */
object SlipNetStore {
    data class Profile(val json: JSONObject) {
        val name: String get() = json.optString("name")
        val type: String get() = json.optString("type")
        val domain: String get() = json.optString("domain")
        fun get(k: String, d: String = ""): String = json.optString(k, d)
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("renamer_slipnet", Context.MODE_PRIVATE)
    fun settings(ctx: Context) = ctx.getSharedPreferences("renamer_slipnet_settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        try {
            val arr = JSONArray(prefs(ctx).getString("profiles", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(Profile(arr.getJSONObject(i)))
        } catch (_: Exception) {}
        return out
    }

    fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) arr.put(p.json)
        prefs(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    fun displayType(type: String): String = when (type) {
        "dnstt" -> "DNSTT"
        "dnstt_ssh" -> "DNSTT + SSH"
        "sayedns" -> "NoizDNS"
        "sayedns_ssh" -> "NoizDNS + SSH"
        "vaydns" -> "VayDNS"
        "vaydns_ssh" -> "VayDNS + SSH"
        "slipstream" -> "Slipstream"
        "slipstream_ssh" -> "Slipstream + SSH"
        "ssh" -> "SSH"
        "naive" -> "NaiveProxy"
        "naive_ssh" -> "NaiveProxy + SSH"
        "socks5" -> "SOCKS5"
        "vless" -> "VLESS"
        "doh" -> "DOH"
        "snowflake" -> "Tor"
        "chain" -> "Chain"
        else -> type
    }

    /** Fields shown in the profile form for a tunnel type.
     *  Each entry: key, labelFa, labelEn, default. */
    fun fieldsFor(type: String): List<Array<String>> {
        val common = listOf(
            arrayOf("name", "نام", "Name", ""),
            arrayOf("domain", "دامنه / سرور", "Domain / Server", "")
        )
        val dns = listOf(
            arrayOf("resolvers", "ریزالورها (با کاما جدا کن)", "Resolvers (comma separated)", ""),
            arrayOf("pubkey", "کلید عمومی", "Public key", "")
        )
        val ssh = listOf(
            arrayOf("sshUser", "نام کاربری SSH", "SSH username", ""),
            arrayOf("sshPass", "رمز SSH", "SSH password", ""),
            arrayOf("sshPort", "پورت SSH", "SSH port", "22"),
            arrayOf("sshHost", "هاست SSH", "SSH host", "127.0.0.1")
        )
        return when (type) {
            "dnstt", "sayedns" -> common + dns
            "dnstt_ssh", "sayedns_ssh" -> common + dns + ssh
            "vaydns" -> common + dns + listOf(
                arrayOf("recordType", "نوع رکورد DNS", "DNS record type", "txt"),
                arrayOf("maxQname", "حداکثر طول QNAME", "Max QNAME length", "101"),
                arrayOf("rps", "محدودیت کوئری در ثانیه", "Queries per second", "0")
            )
            "vaydns_ssh" -> common + dns + ssh + listOf(
                arrayOf("recordType", "نوع رکورد DNS", "DNS record type", "txt"),
                arrayOf("maxQname", "حداکثر طول QNAME", "Max QNAME length", "101"),
                arrayOf("rps", "محدودیت کوئری در ثانیه", "Queries per second", "0")
            )
            "slipstream" -> common
            "slipstream_ssh" -> common + ssh
            "ssh" -> common + ssh + listOf(
                arrayOf("sshKey", "کلید خصوصی SSH (اختیاری، PEM)", "SSH private key (optional, PEM)", ""),
                arrayOf("sshKeyPass", "رمز کلید SSH (اختیاری)", "SSH key passphrase (optional)", "")
            )
            "naive" -> common + listOf(
                arrayOf("naivePort", "پورت Naive", "Naive port", "443"),
                arrayOf("naiveUser", "نام کاربری Naive", "Naive username", ""),
                arrayOf("naivePass", "رمز Naive", "Naive password", "")
            )
            "naive_ssh" -> common + ssh + listOf(
                arrayOf("naivePort", "پورت Naive", "Naive port", "443"),
                arrayOf("naiveUser", "نام کاربری Naive", "Naive username", ""),
                arrayOf("naivePass", "رمز Naive", "Naive password", "")
            )
            "socks5" -> common + listOf(
                arrayOf("socksPort", "پورت SOCKS5", "SOCKS5 port", "1080"),
                arrayOf("socksUser", "نام کاربری (اختیاری)", "Username (optional)", ""),
                arrayOf("socksPass", "رمز (اختیاری)", "Password (optional)", "")
            )
            "vless" -> common + listOf(
                arrayOf("vlessUuid", "UUID", "UUID", ""),
                arrayOf("vlessSecurity", "امنیت (tls/none)", "Security (tls/none)", "tls"),
                arrayOf("vlessTransport", "ترنسپورت (ws/tcp)", "Transport (ws/tcp)", "ws"),
                arrayOf("vlessWsPath", "مسیر WS", "WS path", "/"),
                arrayOf("cdnIp", "IP CDN (اختیاری)", "CDN IP (optional)", ""),
                arrayOf("cdnPort", "پورت CDN", "CDN port", "443"),
                arrayOf("vlessSni", "SNI (اختیاری)", "SNI (optional)", "")
            )
            "doh" -> common + listOf(
                arrayOf("dohUrl", "آدرس DOH", "DOH URL", "")
            )
            "snowflake" -> common + listOf(
                arrayOf("torBridges", "خطوط بریج Tor (هر خط یکی)", "Tor bridge lines (one per line)", "")
            )
            else -> common
        }
    }

    /** Build an Xray URI for the two tunnel types whose engine
     *  already works in this app (VLESS and SOCKS5 via Xray).
     *  Returns null for every other type. */
    fun xrayUri(p: Profile): String? {
        val enc = { s: String -> try { java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") } catch (_: Exception) { s } }
        return when (p.type) {
            "vless" -> {
                val uuid = p.get("vlessUuid"); val host = p.domain
                if (uuid.isBlank() || host.isBlank()) return null
                val port = p.get("cdnPort", "443").ifBlank { "443" }
                val sec = p.get("vlessSecurity", "tls")
                val net = p.get("vlessTransport", "ws")
                val path = p.get("vlessWsPath", "/")
                val sni = p.get("vlessSni").ifBlank { host }
                val cdn = p.get("cdnIp").ifBlank { host }
                "vless://$uuid@$cdn:$port?security=$sec&encryption=none&type=$net&path=${enc(path)}&host=$host&sni=$sni#${enc(p.name)}"
            }
            "socks5" -> {
                val host = p.domain; val port = p.get("socksPort", "1080").ifBlank { "1080" }
                if (host.isBlank()) return null
                val u = p.get("socksUser"); val pw = p.get("socksPass")
                if (u.isNotBlank()) "socks://$u:$pw@$host:$port#${enc(p.name)}"
                else "socks://$host:$port#${enc(p.name)}"
            }
            else -> null
        }
    }

    /** Tolerant import of a slipnet:// URI: base64 (URL-safe,
     *  unpadded) pipe-delimited profile, v2 and later layout:
     *  version|tunnelType|name|domain|resolvers|...|pubkey|...
     *  Unknown / encrypted bundles return null. */
    fun importSlipnetUri(text: String): Profile? {
        return try {
            val t = text.trim()
            if (!t.startsWith("slipnet://")) return null
            var b64 = t.removePrefix("slipnet://").trim()
            b64 = b64.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val f = decoded.split("|")
            if (f.size < 5) return null
            val type = f[1]
            val json = JSONObject()
            json.put("type", type)
            json.put("name", f.getOrElse(2) { "" })
            json.put("domain", f.getOrElse(3) { "" })
            json.put("resolvers", f.getOrElse(4) { "" })
            // v2+: pubkey at index 11, socks user/pass at 12/13,
            // ssh user/pass/port/host at 15/16/17/19 (see SlipNet ConfigImporter).
            if (f.size > 11) json.put("pubkey", f[11])
            if (f.size > 12) json.put("socksUser", f[12])
            if (f.size > 13) json.put("socksPass", f[13])
            if (f.size > 15) json.put("sshUser", f[15])
            if (f.size > 16) json.put("sshPass", f[16])
            if (f.size > 17) json.put("sshPort", f[17])
            if (f.size > 19) json.put("sshHost", f[19])
            if (f.size > 21) json.put("dohUrl", f[21])
            Profile(json)
        } catch (_: Exception) { null }
    }

    fun exportText(list: List<Profile>): String {
        val arr = JSONArray()
        for (p in list) arr.put(p.json)
        return arr.toString()
    }
}
