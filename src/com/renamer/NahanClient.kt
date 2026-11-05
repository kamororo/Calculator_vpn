package com.renamer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pure HTTP client for a Nahan panel (Cloudflare Workers gateway).
 *
 * The panel's dashboard API route is "sync": every API call goes to
 * <host>/sync/api/<endpoint>, the dashboard page is <host>/sync/dash,
 * and the subscription is <host>/sync (or <host>/sync?sub=<name>).
 *
 * All functions are SYNCHRONOUS and must be called from a background
 * thread. They never throw: every failure comes back as Err(code)
 * with a plain reason code ("http <n>", "timeout", "network", "auth",
 * "empty", "parse"), the same style VpnSubs uses, so the activity can
 * show a plain-language message instead of a raw exception.
 *
 * Nothing here logs or stores the password, the panel URL, or any
 * config body. The password is passed per call and never persisted
 * by this client (the activity owns the prefs, like the old panel
 * password, editable by the user, never hard-coded).
 */
object NahanClient {

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class Err(val code: String) : Result<Nothing>()
    }

    /** One panel user (a profile that gets its own subscription). */
    data class NahanUser(
        val id: String,
        val name: String,
        val paused: Boolean,
        val expiryMs: Long,
        val notes: String,
        val createdAt: Long
    )

    /** One subscription profile from the auth response. */
    data class NahanProfile(
        val name: String,
        val id: String,
        /** Full subscription URL for this profile, if the panel gave one. */
        val syncUrl: String
    )

    /** What a successful login returns: the whole sysConfig plus profiles. */
    data class AuthData(
        val config: JSONObject,
        val profiles: List<NahanProfile>,
        val version: String
    ) {
        /** Current clean IPs, split the way the panel splits them. */
        fun cleanIps(): List<String> = splitIpList(config.optString("cleanIps", ""))
        fun nat64Prefix(): String = config.optString("nat64Prefix", "")
        fun backupRelay(): String = config.optString("backupRelay", "")
        fun customRelay(): String = config.optString("customRelay", "")
        fun nameStrategy(): String = config.optString("nameStrategy", "")
        fun mode(): String = config.optString("mode", "")
    }

    data class StatsData(
        val usersTotal: Int,
        val usersActive: Int,
        val uptimeSeconds: Long,
        val activeConnections: Int,
        val version: String,
        /** Raw relay-health object, shape varies with relays in use. */
        val relays: JSONObject?
    )

    // ------------------------------------------------------------------
    // URL helpers
    // ------------------------------------------------------------------

    /** Normalize whatever the user pasted (host, /sync, or /sync/dash)
     *  into the API root: <scheme>://<host>/sync. */
    fun apiRoot(panelUrl: String): String {
        var u = panelUrl.trim().removeSuffix("/")
        if (!u.contains("://")) u = "https://$u"
        u = u.removeSuffix("/")
        return when {
            u.endsWith("/sync/dash") -> u.removeSuffix("/dash")
            u.endsWith("/sync") -> u
            u.endsWith("/panel") -> u.removeSuffix("/panel") + "/sync"
            else -> "$u/sync"
        }
    }

    /** Dashboard page to open in the browser. */
    fun dashboardUrl(panelUrl: String): String = apiRoot(panelUrl) + "/dash"

    /** Default (all-profiles) subscription URL. */
    fun defaultSubUrl(panelUrl: String): String = apiRoot(panelUrl)

    /** Per-user subscription URL. */
    fun userSubUrl(panelUrl: String, userName: String): String =
        apiRoot(panelUrl) + "?sub=" + URLEncoder.encode(userName, "UTF-8")

    /** The panel splits IP lists on newlines, commas and semicolons and
     *  strips "#comment" suffixes — mirror that exactly. */
    fun splitIpList(raw: String): List<String> {
        return raw.split(Regex("[\\r\\n,;]+"))
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun joinIpList(ips: List<String>): String = ips.distinct().joinToString("\n")

    // ------------------------------------------------------------------
    // Low-level HTTP
    // ------------------------------------------------------------------

    private data class HttpOut(val code: Int, val body: String)

    private fun http(
        url: String,
        method: String,
        body: JSONObject?,
        bearer: String?,
        readTimeoutMs: Int = 15000
    ): HttpOut {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json")
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = try { stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "" } catch (_: Exception) { "" }
        conn.disconnect()
        return HttpOut(code, text.trim())
    }

    /** Map a thrown exception (or a bad HTTP code) to a reason code. */
    private fun fail(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        else -> "network"
    }

    private fun failCode(code: Int): String = if (code == 401 || code == 403) "auth" else "http $code"

    // ------------------------------------------------------------------
    // Auth + config
    // ------------------------------------------------------------------

    /** Log in with the panel password (masterKey). Returns the full
     *  sysConfig and the profiles. Never retried by callers: the panel
     *  rate-limits auth (15 attempts / 15 min / IP). */
    fun auth(panelUrl: String, password: String): Result<AuthData> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            out = http("$root/api/auth", "POST", JSONObject().put("key", password), null)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Err("empty")
        return try {
            val json = JSONObject(out.body)
            if (!json.optBoolean("success", false)) return Result.Err("auth")
            val config = json.optJSONObject("config") ?: JSONObject()
            val profiles = mutableListOf<NahanProfile>()
            val arr = json.optJSONArray("profiles")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "")
                    if (name.isBlank()) continue
                    profiles += NahanProfile(
                        name = name,
                        id = o.optString("id", ""),
                        syncUrl = o.optString("sync", "")
                    )
                }
            }
            Result.Ok(AuthData(config, profiles, json.optString("version", "")))
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    /**
     * Push ONLY the given fields into sysConfig (cleanIps, nat64Prefix,
     * relays, nameStrategy...). The panel merges them; it never wipes
     * fields the app did not send. Callers must never send a full
     * config they read back — that would overwrite panel-side fields
     * the app does not own.
     */
    fun syncConfig(panelUrl: String, password: String, fields: JSONObject): Result<Unit> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            val body = JSONObject().put("key", password).put("config", fields)
            out = http("$root/api/sync", "POST", body, null)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        return try {
            val json = JSONObject(out.body)
            if (json.optBoolean("success", false)) Result.Ok(Unit) else Result.Err("auth")
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    private fun userFromJson(o: JSONObject): NahanUser? {
        val id = o.optString("id", "")
        val name = o.optString("name", "")
        if (id.isBlank() || name.isBlank()) return null
        val paused = when {
            o.has("paused") -> o.optBoolean("paused", false)
            o.has("active") -> !o.optBoolean("active", true)
            else -> false
        }
        return NahanUser(
            id = id,
            name = name,
            paused = paused,
            expiryMs = o.optLong("expiryMs", 0L),
            notes = o.optString("notes", ""),
            createdAt = o.optLong("createdAt", 0L)
        )
    }

    fun getUsers(panelUrl: String, password: String): Result<List<NahanUser>> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            out = http("$root/api/users", "GET", null, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Err("empty")
        return try {
            val users = mutableListOf<NahanUser>()
            val trimmed = out.body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    userFromJson(arr.optJSONObject(i) ?: continue)?.let { users += it }
                }
            } else {
                val json = JSONObject(trimmed)
                val arr = json.optJSONArray("users")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        userFromJson(arr.optJSONObject(i) ?: continue)?.let { users += it }
                    }
                }
            }
            Result.Ok(users)
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    fun createUser(
        panelUrl: String,
        password: String,
        name: String,
        trafficLimit: Double,
        expiryDays: Int,
        notes: String
    ): Result<NahanUser> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            val body = JSONObject()
                .put("name", name)
                .put("trafficLimit", trafficLimit)
                .put("expiryDays", expiryDays)
                .put("notes", notes)
            out = http("$root/api/users", "POST", body, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Err("empty")
        return try {
            val json = JSONObject(out.body)
            val obj = json.optJSONObject("user") ?: json
            val user = userFromJson(obj)
            if (user != null) Result.Ok(user) else Result.Err("parse")
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    fun deleteUser(panelUrl: String, password: String, id: String): Result<Unit> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            val enc = URLEncoder.encode(id, "UTF-8")
            out = http("$root/api/users?id=$enc", "DELETE", null, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        return Result.Ok(Unit)
    }

    /** Pause/unpause (toggle) or reset a user's traffic. */
    fun userAction(panelUrl: String, password: String, id: String, action: String): Result<Unit> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            val enc = URLEncoder.encode(id, "UTF-8")
            out = http("$root/api/users?id=$enc&action=$action", "POST", null, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        return Result.Ok(Unit)
    }

    // ------------------------------------------------------------------
    // Stats + logs
    // ------------------------------------------------------------------

    fun getStats(panelUrl: String, password: String): Result<StatsData> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            out = http("$root/api/stats", "GET", null, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Err("empty")
        return try {
            val json = JSONObject(out.body)
            val users = json.optJSONObject("users")
            val system = json.optJSONObject("system")
            Result.Ok(
                StatsData(
                    usersTotal = users?.optInt("total", 0) ?: 0,
                    usersActive = users?.optInt("active", 0) ?: 0,
                    uptimeSeconds = system?.optLong("uptimeSeconds", 0L) ?: 0L,
                    activeConnections = system?.optInt("activeConnections", 0) ?: 0,
                    version = system?.optString("version", "")?.takeIf { it.isNotBlank() }
                        ?: json.optString("version", ""),
                    relays = json.optJSONObject("relays")
                )
            )
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    /** Panel log lines, best-effort: the exact shape varies, so objects
     *  are reduced to their message-ish field and anything else is
     *  kept as its raw line. Never fails the section on a shape miss. */
    fun getLogs(panelUrl: String, password: String): Result<List<String>> {
        val root = apiRoot(panelUrl)
        val out: HttpOut
        try {
            out = http("$root/api/logs", "GET", null, password)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Ok(emptyList())
        return try {
            val lines = mutableListOf<String>()
            val trimmed = out.body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) {
                        val m = o.optString("message", "")
                            .ifBlank { o.optString("msg", "") }
                            .ifBlank { o.optString("text", "") }
                        if (m.isNotBlank()) lines += m
                    } else {
                        val s = arr.optString(i, "")
                        if (s.isNotBlank()) lines += s
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val arr = json.optJSONArray("logs")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "")
                        if (s.isNotBlank()) lines += s
                    }
                }
            } else {
                lines += trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            Result.Ok(lines)
        } catch (_: Exception) {
            Result.Err("parse")
        }
    }

    // ------------------------------------------------------------------
    // Subscription
    // ------------------------------------------------------------------

    /** The panel ships fake display configs among the real ones: usage
     *  and expiry rows pointing at 127.0.0.1. They are NOT connectable
     *  and must never reach the v2ray list. Real configs point at the
     *  worker host. */
    fun isFakeConfig(link: String): Boolean {
        if (link.contains("127.0.0.1")) return true
        val frag = link.substringAfter("#", "")
        if (frag.isNotBlank()) {
            val name = try {
                java.net.URLDecoder.decode(frag, "UTF-8")
            } catch (_: Exception) { frag }
            if (name.startsWith("📊") || name.startsWith("📅")) return true
        }
        return false
    }

    /**
     * Fetch a subscription URL (default or per-user). The body is a
     * single-line base64 blob (or plain links as a fallback) — decoded
     * the same way VpnSubs does it — and returned with fakes removed
     * and duplicates dropped. No auth: sub links are public by design.
     */
    fun fetchSubscription(subUrl: String): Result<List<String>> {
        val out: HttpOut
        try {
            out = http(subUrl, "GET", null, null, readTimeoutMs = 30000)
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
        if (out.code !in 200..299) return Result.Err(failCode(out.code))
        if (out.body.isBlank()) return Result.Err("empty")
        var body = out.body.trim()
        if (!body.contains("://") && !body.startsWith("{") && !body.startsWith("[")) {
            try {
                var b = body.replace('-', '+').replace('_', '/')
                while (b.length % 4 != 0) b += "="
                body = String(
                    android.util.Base64.decode(b, android.util.Base64.DEFAULT),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                return Result.Err("parse")
            }
        }
        val seen = mutableSetOf<String>()
        val links = mutableListOf<String>()
        for (raw in body.lines()) {
            val link = raw.trim()
            if (link.isBlank() || !link.contains("://")) continue
            if (isFakeConfig(link)) continue
            if (seen.add(link)) links += link
        }
        if (links.isEmpty()) return Result.Err("no-configs")
        return Result.Ok(links)
    }

    /** Plain-language reason for a Result.Err code, FA/EN pair. */
    fun reasonFaEn(code: String): Pair<String, String> {
        return when {
            code == "auth" ->
                "رمز پنل درست نیست یا دسترسی رد شد" to
                "Wrong panel password or access denied"
            code.startsWith("http ") ->
                "سرور کد ${code.removePrefix("http ")} برگرداند" to
                "The server returned code ${code.removePrefix("http ")}"
            code == "timeout" ->
                "در ۱۵ ثانیه جواب نداد (تایم‌اوت)" to
                "No answer within 15 seconds (timeout)"
            code == "network" ->
                "خطای شبکه (پنل یا اینترنت در دسترس نیست)" to
                "Network error (panel or internet unreachable)"
            code == "empty" ->
                "پاسخ خالی برگشت" to
                "The response was empty"
            code == "parse" ->
                "پاسخ پنل قابل خواندن نبود" to
                "The panel's response could not be read"
            code == "no-configs" ->
                "اشتراک رسید ولی کانفیگ واقعی در آن نبود" to
                "The subscription arrived but held no real configs"
            else -> code to code
        }
    }
}
