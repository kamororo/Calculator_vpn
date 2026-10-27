package com.renamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One v2rayNG-style subscription item, with every
 *  option from the subscription settings page.
 *  Stored as JSON in renamer_vpn key "vpn_sub_items".
 *  Legacy plain links (key "vpn_subs") are migrated
 *  once on first load — never auto-deleted, only an
 *  explicit per-row delete removes one. */
data class SubItem(
    var url: String,
    var remarks: String = "",
    var enabled: Boolean = true,
    var lastUpdated: Long = 0L,
    var autoUpdate: Boolean = false,
    var updateInterval: Int = 1440,
    var prevProfile: String = "",
    var nextProfile: String = "",
    var filter: String = "",
    var allowInsecure: Boolean = false,
    var userAgent: String = "",
    var requestHeaders: String = "",
    var configKeys: MutableSet<String> = mutableSetOf()
) {
    fun displayName(): String {
        if (remarks.isNotBlank()) return remarks
        val frag = url.substringAfter("#", "")
        if (frag.isNotBlank()) return java.net.URLDecoder.decode(frag, "UTF-8")
        return try {
            val u = java.net.URL(url)
            u.host.ifBlank { url }
        } catch (_: Exception) { url }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("remarks", remarks); put("enabled", enabled)
        put("lastUpdated", lastUpdated); put("autoUpdate", autoUpdate)
        put("updateInterval", updateInterval)
        put("prevProfile", prevProfile); put("nextProfile", nextProfile)
        put("filter", filter); put("allowInsecure", allowInsecure)
        put("userAgent", userAgent); put("requestHeaders", requestHeaders)
        put("configKeys", JSONArray(configKeys.toList()))
    }

    companion object {
        fun fromJson(o: JSONObject): SubItem {
            val keys = mutableSetOf<String>()
            val arr = o.optJSONArray("configKeys")
            if (arr != null) for (i in 0 until arr.length()) keys.add(arr.optString(i))
            return SubItem(
                url = o.optString("url", ""),
                remarks = o.optString("remarks", ""),
                enabled = o.optBoolean("enabled", true),
                lastUpdated = o.optLong("lastUpdated", 0L),
                autoUpdate = o.optBoolean("autoUpdate", false),
                updateInterval = o.optInt("updateInterval", 1440).coerceAtLeast(15),
                prevProfile = o.optString("prevProfile", ""),
                nextProfile = o.optString("nextProfile", ""),
                filter = o.optString("filter", ""),
                allowInsecure = o.optBoolean("allowInsecure", false),
                userAgent = o.optString("userAgent", ""),
                requestHeaders = o.optString("requestHeaders", ""),
                configKeys = keys
            )
        }
    }
}

object VpnSubs {
    private const val KEY = "vpn_sub_items"
    private const val LEGACY_KEY = "vpn_subs"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)

    fun loadItems(ctx: Context): MutableList<SubItem> {
        val raw = prefs(ctx).getString(KEY, null)
        if (!raw.isNullOrBlank()) {
            return try {
                val arr = JSONArray(raw)
                val out = mutableListOf<SubItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("url", "").isNotBlank()) out.add(SubItem.fromJson(o))
                }
                out
            } catch (_: Exception) { mutableListOf() }
        }
        // One-time migration from the legacy plain-link list.
        val legacy = prefs(ctx).getString(LEGACY_KEY, "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val items = legacy.map { SubItem(url = it) }.toMutableList()
        if (items.isNotEmpty()) saveItems(ctx, items)
        return items
    }

    fun saveItems(ctx: Context, items: List<SubItem>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun loadServers(ctx: Context): MutableList<String> {
        return prefs(ctx).getString("servers", "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    }

    fun saveServers(ctx: Context, servers: List<String>) {
        prefs(ctx).edit().putString("servers", servers.joinToString("\n")).apply()
    }

    /** Members of a manual group: the configs after
     *  its GROUP:: header, up to the next header. */
    fun groupMembers(servers: List<String>, header: String): List<String> {
        val out = mutableListOf<String>()
        var inside = false
        for (cfg in servers) {
            if (XraySpeedtest.isGroup(cfg)) {
                inside = (cfg == header)
                continue
            }
            if (inside) out += cfg
        }
        return out
    }

    /** The sub item a config belongs to, if any. */
    fun itemForConfig(items: List<SubItem>, cfg: String): SubItem? {
        val key = XraySpeedtest.dedupKey(cfg)
        return items.firstOrNull { it.configKeys.contains(key) }
    }

    /** Resolve a profile by its remarks (display
     *  name) among plain configs only: v2rayNG
     *  getServerViaRemarks skips custom configs, and
     *  groups/chains cannot be chain hops here. */
    private fun byRemark(servers: List<String>, remark: String): String? {
        return servers.firstOrNull {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) &&
                !XraySpeedtest.isCustom(it) && XraySpeedtest.displayName(it) == remark
        }
    }

    /** Prev/next chain for a config that belongs to
     *  a sub with prev/next set. v2rayNG semantics
     *  (CoreConfigContextBuilder): the resolved
     *  order is [next, config, prev], fed into a
     *  dialerProxy chain, so traffic goes
     *  device -> prev -> config -> next ->
     *  destination. Returns a "CHAIN::" string our
     *  builder already understands, or null. */
    fun chainForConfig(ctx: Context, cfg: String): String? {
        if (XraySpeedtest.isGroup(cfg) || XraySpeedtest.isChain(cfg) ||
            XraySpeedtest.isCustom(cfg)) return null
        val items = loadItems(ctx)
        val item = itemForConfig(items, cfg) ?: return null
        if (item.prevProfile.isBlank() && item.nextProfile.isBlank()) return null
        val servers = loadServers(ctx)
        val parts = mutableListOf<String>()
        if (item.nextProfile.isNotBlank()) {
            byRemark(servers, item.nextProfile)?.let { parts += it }
        }
        parts += cfg
        if (item.prevProfile.isNotBlank()) {
            byRemark(servers, item.prevProfile)?.let { parts += it }
        }
        if (parts.size < 2) return null
        return "CHAIN::" + XraySpeedtest.displayName(cfg) + "|||" + parts.joinToString("|||")
    }

    data class FetchResult(val added: Int, val received: Int, val message: String)

    /** Fetch one sub. Runs on the caller's thread
     *  (call it from a background thread). Honours
     *  user agent, request headers (JSON object),
     *  allow-insecure, and the remarks filter
     *  (v2rayNG: non-matching remarks are dropped).
     *  Never deletes configs: membership is updated
     *  to the current fetch, stale configs simply
     *  stop belonging to the tab. */
    /** Fetch one subscription. message is a
     *  reason code, not just "ok"/"insecure":
     *  "http <code>", "timeout", "network",
     *  "empty", "no-configs", "filtered". The old
     *  code swallowed every failure as a bare
     *  failed++ (and inputStream threw on any HTTP
     *  error), so an update that got nothing looked
     *  identical to one that worked — the user
     *  could never tell why. Also: a fetch that
     *  yields zero usable configs must NEVER wipe
     *  the tab's membership — one bad response
     *  (provider glitch, block page, empty body)
     *  used to empty the whole tab. */
    fun fetchOne(ctx: Context, item: SubItem): FetchResult {
        if (item.url.startsWith("http://") && !item.allowInsecure) {
            return FetchResult(0, 0, "insecure")
        }
        val rawBody: String
        try {
            val conn = java.net.URL(item.url).openConnection()
                as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent",
                item.userAgent.ifBlank { "v2rayNG" })
            if (item.requestHeaders.isNotBlank()) {
                try {
                    val h = JSONObject(item.requestHeaders)
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        conn.setRequestProperty(k, h.optString(k, ""))
                    }
                } catch (_: Exception) {}
            }
            val code = conn.responseCode
            if (code !in 200..299) return FetchResult(0, 0, "http $code")
            rawBody = conn.inputStream.bufferedReader().readText().trim()
        } catch (_: java.net.SocketTimeoutException) {
            return FetchResult(0, 0, "timeout")
        } catch (_: Exception) {
            return FetchResult(0, 0, "network")
        }
        if (rawBody.isBlank()) return FetchResult(0, 0, "empty")
        var body = rawBody
        val trimmed = body.trim()
        if (!trimmed.contains("://") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            try {
                var b = trimmed.replace('-', '+').replace('_', '/')
                while (b.length % 4 != 0) b += "="
                body = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        val configs = XraySpeedtest.splitConfigs(body)
        val filterRegex = try {
            if (item.filter.isNotBlank()) Regex(item.filter) else null
        } catch (_: Exception) { null }
        val servers = loadServers(ctx)
        val seen = servers.map { XraySpeedtest.dedupKey(it) }.toMutableSet()
        var added = 0
        var received = 0
        var parsed = 0
        val memberKeys = mutableSetOf<String>()
        for (cfg in configs) {
            val c = cfg.trim()
            if (c.isBlank()) continue
            if (!c.contains("://") && !XraySpeedtest.isCustom(c)) continue
            parsed++
            val name = XraySpeedtest.displayName(c)
            if (filterRegex != null && !filterRegex.containsMatchIn(name)) continue
            received++
            val key = XraySpeedtest.dedupKey(c)
            memberKeys.add(key)
            if (seen.add(key)) { servers += c; added++ }
        }
        if (received == 0) {
            // Do not touch membership or the
            // last-updated stamp: the tab keeps
            // showing what it had.
            return FetchResult(0, 0, if (parsed > 0) "filtered" else "no-configs")
        }
        item.configKeys = memberKeys
        item.lastUpdated = System.currentTimeMillis()
        saveServers(ctx, servers)
        val items = loadItems(ctx)
        val idx = items.indexOfFirst { it.url == item.url }
        if (idx >= 0) items[idx] = item else items += item
        saveItems(ctx, items)
        return FetchResult(added, received, "ok")
    }

    /** Plain-language reason for a fetchOne
     *  message code, as an FA/EN pair (so callers
     *  that store both languages get both right). */
    fun reasonFaEn(message: String): Pair<String, String> {
        return when {
            message == "insecure" ->
                "لینک http است و «اتصال ناامن» این ساب خاموش است" to
                "The link is http and this sub's insecure-connection switch is off"
            message.startsWith("http ") ->
                "سرور کد ${message.removePrefix("http ")} برگرداند" to
                "The server returned code ${message.removePrefix("http ")}"
            message == "timeout" ->
                "در ۱۵ ثانیه جواب نداد (تایم‌اوت)" to
                "No answer within 15 seconds (timeout)"
            message == "network" ->
                "خطای شبکه (لینک یا اینترنت در دسترس نیست)" to
                "Network error (link or internet unreachable)"
            message == "empty" ->
                "پاسخ خالی برگشت" to
                "The response was empty"
            message == "no-configs" ->
                "پاسخ رسید ولی کانفیگی در آن نبود" to
                "A response arrived but it held no configs"
            message == "filtered" ->
                "همه‌ی کانفیگ‌ها با فیلتر این ساب حذف شدند" to
                "All configs were removed by this sub's filter"
            else -> message to message
        }
    }

    /** reasonFaEn in the current language. */
    fun reasonLabel(ctx: Context, message: String): String {
        val (fa, en) = reasonFaEn(message)
        return AppLanguage.t(ctx, fa, en)
    }

    /** Display name of a sub item: remarks, else
     *  the URL itself. */
    fun displayLabel(item: SubItem): String =
        item.remarks.ifBlank { item.url }

    /** Fetch every enabled item that is overdue for
     *  its automatic update (interval floor 15 min).
     *  Returns how many items were updated. */
    fun checkAutoUpdate(ctx: Context): Int {
        val items = loadItems(ctx)
        var updated = 0
        val now = System.currentTimeMillis()
        for (item in items) {
            if (!item.enabled || !item.autoUpdate) continue
            val intervalMs = item.updateInterval.coerceAtLeast(15).toLong() * 60_000L
            if (item.lastUpdated > 0 && now - item.lastUpdated < intervalMs) continue
            try {
                fetchOne(ctx, item)
                updated++
            } catch (_: Exception) {}
        }
        return updated
    }
}
