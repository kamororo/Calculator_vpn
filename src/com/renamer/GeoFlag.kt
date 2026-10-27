package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Country flag + location lookup for a server host.
 *
 * The country/city come from https://api.ip.sb/geoip/<ip>
 * (the same endpoint v2rayNG uses for its "current
 * connection info test url"). Results are cached in
 * SharedPreferences "renamer_vpn" key "geo_cache" as a
 * JSON object host -> "CC|Country|City", so each host is
 * looked up at most once. All network work runs on a
 * background thread; the callback always runs on the
 * main thread. On any failure the callback receives
 * ("", "", "") — it never throws and never blocks UI.
 */
object GeoFlag {
    private const val PREFS = "renamer_vpn"
    private const val CACHE_KEY = "geo_cache_v2"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun flagEmoji(countryCode: String): String {
        val cc = countryCode.trim().uppercase()
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
        val a = 0x1F1E6 + (cc[0] - 'A')
        val b = 0x1F1E6 + (cc[1] - 'A')
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }

    private const val EGRESS_KEY = "geo_egress"

    /** Through-tunnel measurement for one config:
     *  "CC|Country|City", or null when never measured. */
    fun egressFor(ctx: Context, cfg: String): Triple<String, String, String>? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(EGRESS_KEY, "") ?: ""
            if (raw.isBlank()) return null
            val value = org.json.JSONObject(raw)
                .optString(XraySpeedtest.dedupKey(cfg), "")
            if (value.isBlank()) return null
            val parts = value.split("|")
            if (parts.size < 3) return null
            Triple(parts[0], parts[1], parts[2])
        } catch (_: Exception) { null }
    }

    /** Flag lookup for a row: the through-tunnel
     *  measurement wins when it exists (it is the
     *  country the traffic actually exits from),
     *  otherwise the host-based lookup runs. This is
     *  the fix for wrong flags: a domain that points
     *  at a CDN edge (often Cloudflare/US) can only
     *  ever show the edge's country from the host
     *  side; the truth is what the tunnel sees. */
    fun lookupForConfig(ctx: Context, cfg: String, host: String,
                        callback: (String, String, String) -> Unit) {
        val egress = try { egressFor(ctx, cfg) } catch (_: Exception) { null }
        if (egress != null) {
            mainHandler.post { callback(egress.first, egress.second, egress.third) }
            return
        }
        lookup(ctx, host, callback)
    }

    /** Measure the egress country THROUGH the tunnel:
     *  query the connection-info URL via the local
     *  socks proxy (127.0.0.1:socks port) while the
     *  VPN is up, and store it per config. Skipped
     *  when the socks port is dynamic (the real port
     *  is then unknown), when socks auth is on (this
     *  minimal client does not do SOCKS auth), or
     *  when there is no config to attribute it to.
     *  Runs on the caller's thread; call it from a
     *  worker thread. */
    fun measureEgress(ctx: Context, link: String?) {
        if (link.isNullOrBlank()) return
        if (XraySpeedtest.isGroup(link)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("dynamic_proxy_port", false)) return
            if (prefs.getBoolean("enable_local_proxy", false) &&
                prefs.getString("local_proxy_user", "").isNullOrBlank().not()) return
            val port = VpnConfigBuilder.socksPort(ctx)
            if (port <= 0) return
            val url = prefs.getString("conn_info_url", "https://api.ip.sb/geoip")
                ?.takeIf { it.isNotBlank() } ?: "https://api.ip.sb/geoip"
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", port))
            val conn = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "calculator")
            if (conn.responseCode != 200) { try { conn.disconnect() } catch (_: Exception) {}; return }
            val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            try { conn.disconnect() } catch (_: Exception) {}
            val cc = j.optString("country_code", "").ifBlank { j.optString("countryCode", "") }
            val country = j.optString("country", "")
            val city = j.optString("city", "")
            if (cc.isBlank() && country.isBlank()) return
            val raw = prefs.getString(EGRESS_KEY, "") ?: ""
            val obj = try { org.json.JSONObject(raw) } catch (_: Exception) { org.json.JSONObject() }
            obj.put(XraySpeedtest.dedupKey(link), "$cc|$country|$city")
            prefs.edit().putString(EGRESS_KEY, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun lookup(ctx: Context, host: String, callback: (String, String, String) -> Unit) {
        val appCtx = ctx.applicationContext
        val h = host.trim()
        if (h.isBlank()) {
            mainHandler.post { callback("", "", "") }
            return
        }
        // Cache hit: answer synchronously on the main thread.
        val cached = readCache(appCtx, h)
        if (cached != null) {
            val (cc, country, city) = cached
            mainHandler.post { callback(cc, country, city) }
            return
        }
        Thread {
            var result: Triple<String, String, String> = Triple("", "", "")
            try {
                // Resolve a domain to an IP first (best
                // effort; a plain IP passes through).
                // InetAddress has no timeout parameter, so
                // this runs on the worker thread only.
                // Prefer an IPv4 address: IPv6 geolocation is far
                // less reliable, and a domain's IPv6 edge often
                // belongs to a different provider/country.
                val ip = try {
                    val all = InetAddress.getAllByName(h).mapNotNull { it.hostAddress }
                    all.firstOrNull { it.contains(".") && !it.contains(":") }
                        ?: all.firstOrNull() ?: h
                } catch (_: Exception) { h }
                // Primary source: ip-api.com (MaxMind-based).
                // Fallback: api.ip.sb. If they disagree, the
                // primary wins; only a successful primary
                // result is cached, so a wrong old entry
                // (geo_cache v1) is never reused.
                result = queryIpApi(ip) ?: queryIpSb(ip) ?: Triple("", "", "")
                if (result.first.isNotBlank() || result.second.isNotBlank()) {
                    writeCache(appCtx, h, result.first, result.second, result.third)
                }
            } catch (_: Throwable) {
                // Any failure: fall through to ("", "", "").
            }
            val finalResult = result
            mainHandler.post { callback(finalResult.first, finalResult.second, finalResult.third) }
        }.start()
    }


    private fun queryIpApi(ip: String): Triple<String, String, String>? {
        return try {
            val conn = URL("https://ip-api.com/json/$ip?fields=status,country,countryCode,city").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "calculator")
            if (conn.responseCode != 200) { try { conn.disconnect() } catch (_: Exception) {}; return null }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            try { conn.disconnect() } catch (_: Exception) {}
            if (j.optString("status", "") != "success") return null
            val cc = j.optString("countryCode", "")
            val country = j.optString("country", "")
            val city = j.optString("city", "")
            if (cc.isBlank() && country.isBlank()) null else Triple(cc, country, city)
        } catch (_: Exception) { null }
    }

    private fun queryIpSb(ip: String): Triple<String, String, String>? {
        return try {
            val conn = URL("https://api.ip.sb/geoip/$ip").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "calculator")
            if (conn.responseCode != 200) { try { conn.disconnect() } catch (_: Exception) {}; return null }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            try { conn.disconnect() } catch (_: Exception) {}
            val cc = j.optString("country_code", "")
            val country = j.optString("country", "")
            val city = j.optString("city", "")
            if (cc.isBlank() && country.isBlank()) null else Triple(cc, country, city)
        } catch (_: Exception) { null }
    }

    private fun readCache(ctx: Context, host: String): Triple<String, String, String>? {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, "") ?: ""
            if (raw.isBlank()) return null
            val value = JSONObject(raw).optString(host, "")
            if (value.isBlank()) return null
            val parts = value.split("|")
            if (parts.size < 3) return null
            Triple(parts[0], parts[1], parts[2])
        } catch (_: Exception) { null }
    }

    private fun writeCache(ctx: Context, host: String, cc: String, country: String, city: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(CACHE_KEY, "") ?: ""
            val obj = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            obj.put(host, "$cc|$country|$city")
            prefs.edit().putString(CACHE_KEY, obj.toString()).apply()
        } catch (_: Exception) {
            // Cache failure must never break the lookup.
        }
    }
}
