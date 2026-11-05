package com.renamer

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * v2rayNG's real-ping, reproduced exactly:
 *
 * 1. TCP pre-check (RealPingWorkerService.startRealPing): for simple
 *    types (VLESS/VMess/Trojan/Shadowsocks, not WireGuard, not custom,
 *    not h3), a 1000 ms socket connect to server:port. Fail -> -1.
 * 2. Build a speedtest Xray config (CoreConfigManager
 *    .getV2rayConfig4Speedtest + postProcessForSpeedtest): no
 *    inbounds, no DNS, no routing rules, mux removed. The main
 *    outbound is the config under test.
 * 3. CoreNativeManager.measureOutboundDelay -> libv2ray
 *    MeasureOutboundDelay(config, delayTestUrl): starts an Xray
 *    instance with only outbound/dispatcher/log apps, then does
 *    an HTTP GET *through that instance* to the delay test URL.
 *    Default URL (AppConfig.DELAY_TEST_URL):
 *    https://www.gstatic.com/generate_204
 *    Fallback (DELAY_TEST_URL2):
 *    https://www.google.com/generate_204
 *    Inside libv2ray: 12 s client timeout, 6 s TLS handshake
 *    timeout, 2 attempts, minimum duration wins, success only
 *    on HTTP 200 or 204. Anything else -> -1.
 *
 * Concurrency default in v2rayNG (SettingsManager
 * .getRealPingConcurrency) is 16, so tests run 16 at a time.
 */
object XraySpeedtest {
    const val TEST_URL = "https://www.gstatic.com/generate_204"
    const val TEST_URL2 = "https://www.google.com/generate_204"
    const val CONCURRENCY = 16
    @Volatile var testUrlOverride: String? = null
    @Volatile var concurrencyOverride: Int? = null
    fun testUrl(ctx: android.content.Context): String =
        ctx.getSharedPreferences("renamer_vpn", android.content.Context.MODE_PRIVATE)
            .getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            ?: (testUrlOverride ?: TEST_URL)
    fun poolSize(): Int = (concurrencyOverride ?: CONCURRENCY).coerceIn(1, 64)
    const val TCP_TIMEOUT_MS = 1000

    data class Server(val host: String, val port: Int)

    fun parseServer(cfg: String): Server? {
        if (isGroup(cfg)) return null
        if (isCustom(cfg)) return customServer(cfg)
        if (isChain(cfg)) {
            val first = chainParts(cfg).firstOrNull() ?: return null
            return parseServer(first)
        }
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                val host = j.optString("add")
                val port = j.optString("port").toIntOrNull() ?: return null
                if (host.isBlank()) null else Server(host, port)
            } else {
                val uri = URI(cfg.substringBefore("#"))
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                Server(host, port)
            }
        } catch (_: Exception) { null }
    }

    fun tcpConnectTime(host: String, port: Int, timeoutMs: Int = TCP_TIMEOUT_MS): Long {
        val start = System.currentTimeMillis()
        return try {
            Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                close()
            }
            System.currentTimeMillis() - start
        } catch (_: Throwable) { -1L }
    }

    fun isSimpleType(cfg: String): Boolean {
        return cfg.startsWith("vless://") || cfg.startsWith("vmess://") ||
            cfg.startsWith("trojan://") || cfg.startsWith("ss://") ||
            cfg.startsWith("socks://") || cfg.startsWith("socks5://") || cfg.startsWith("socks4://") ||
            cfg.startsWith("http://")
    }

    /** A v2rayNG CUSTOM config: a full Xray JSON config,
     *  not a URI link. v2rayNG detects it by the
     *  presence of inbounds + outbounds + routing in
     *  one JSON object (or an array of them in a
     *  subscription). It must be labelled "Custom",
     *  never mistaken for a vmess link. */
    fun isCustom(cfg: String): Boolean {
        val s = cfg.trim()
        if (!s.startsWith("{")) return false
        return s.contains("\"outbounds\"") && s.contains("\"routing\"")
    }

    /** Split pasted/fetched text into configs. A
     *  pretty-printed custom JSON spans many lines,
     *  so it must NOT be split line by line: if the
     *  whole text is one JSON object or an array of
     *  them, each object is one config. Otherwise the
     *  old line-by-line behaviour applies. */
    fun splitConfigs(text: String): List<String> {
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        try {
            if (s.startsWith("[")) {
                val arr = org.json.JSONArray(s)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val el = arr.get(i)
                    if (el is org.json.JSONObject) out.add(el.toString())
                    else if (el is String && el.isNotBlank()) out.add(el.trim())
                }
                if (out.isNotEmpty()) return out
            } else if (s.startsWith("{") && isCustom(s)) {
                return listOf(s)
            }
        } catch (_: Exception) {}
        return s.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Remarks of a custom config (its JSON
     *  "remarks" field), or "" when absent. */
    fun customRemarks(cfg: String): String {
        return try { org.json.JSONObject(cfg).optString("remarks", "") } catch (_: Exception) { "" }
    }

    /** The proxy outbound of a custom config: the
     *  first outbound whose protocol is a real proxy
     *  protocol (v2rayNG's getProxyOutbound), skipping
     *  freedom / blackhole / dns. Used for ping and
     *  for the speedtest config. */
    fun customProxyOutbound(cfg: String): org.json.JSONObject? {
        return try {
            val arr = org.json.JSONObject(cfg).optJSONArray("outbounds") ?: return null
            val proxyProtos = setOf("vmess", "vless", "trojan", "shadowsocks",
                "socks", "http", "wireguard", "hysteria2", "hysteria", "tuic")
            for (i in 0 until arr.length()) {
                val ob = arr.getJSONObject(i)
                if (proxyProtos.contains(ob.optString("protocol", "").lowercase())) {
                    return org.json.JSONObject(ob.toString()).apply { put("tag", "proxy") }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Server address/port of a custom config, from
     *  its proxy outbound (vnext or servers form). */
    fun customServer(cfg: String): Server? {
        return try {
            val ob = customProxyOutbound(cfg) ?: return null
            val st = ob.optJSONObject("settings") ?: return null
            val vnext = st.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val v = vnext.getJSONObject(0)
                val host = v.optString("address", "")
                val port = v.optInt("port", -1)
                if (host.isNotBlank() && port > 0) return Server(host, port)
            }
            val servers = st.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val v = servers.getJSONObject(0)
                val host = v.optString("address", "")
                val port = v.optInt("port", -1)
                if (host.isNotBlank() && port > 0) return Server(host, port)
            }
            null
        } catch (_: Exception) { null }
    }

    fun isGroup(cfg: String) = cfg.startsWith("GROUP::")
    fun isChain(cfg: String) = cfg.startsWith("CHAIN::")
    // Group headers may carry a strategy suffix
    // ("GROUP::name|||leastLoad"); the name is the
    // part before it, the strategy after it.
    fun groupName(cfg: String) = cfg.removePrefix("GROUP::").substringBefore("|||")
    fun groupStrategy(cfg: String): String {
        val s = cfg.removePrefix("GROUP::").substringAfter("|||", "")
        return if (s == "leastLoad") "leastLoad" else "leastPing"
    }
    fun chainName(cfg: String): String {
        return cfg.removePrefix("CHAIN::").substringBefore("|||")
    }
    fun chainParts(cfg: String): List<String> {
        val rest = cfg.removePrefix("CHAIN::").substringAfter("|||", "")
        if (rest.isBlank()) return emptyList()
        return rest.split("|||").filter { it.isNotBlank() }
    }

    /** Outbounds for a config: a single proxy outbound, or
     *  a chain (first outbound dials through the next via
     *  streamSettings.sockopt.dialerProxy, Xray's chaining
     *  mechanism). Tags: proxy, chain1, chain2, ... */
    fun outboundsFor(cfg: String): JSONArray? {
        if (isChain(cfg)) {
            val parts = chainParts(cfg)
            if (parts.isEmpty()) return null
            val arr = JSONArray()
            for ((i, part) in parts.withIndex()) {
                val ob = outboundFor(part) ?: return null
                val tag = if (i == 0) "proxy" else "chain$i"
                ob.put("tag", tag)
                if (i < parts.size - 1) {
                    val nextTag = "chain${i + 1}"
                    var ss = ob.optJSONObject("streamSettings")
                    if (ss == null) {
                        ss = JSONObject()
                        ob.put("streamSettings", ss)
                    }
                    var sockopt = ss.optJSONObject("sockopt")
                    if (sockopt == null) {
                        sockopt = JSONObject()
                        ss.put("sockopt", sockopt)
                    }
                    sockopt.put("dialerProxy", nextTag)
                }
                arr.put(ob)
            }
            return arr
        }
        val ob = outboundFor(cfg) ?: return null
        return JSONArray().put(ob)
    }

    /** v2rayNG real ping for one config: TCP pre-check, then
     *  Xray measureOutboundDelay with the primary test URL only
     *  (batch mode in v2rayNG uses only DELAY_TEST_URL). */
    fun measureOne(cfg: String): Long = measureOneWithError(cfg).first

    /** Same as measureOne, but keeps the real error message
     *  instead of swallowing it into -1, so a failing test
     *  can actually be diagnosed on the device. */
    fun measureOneWithError(cfg: String): Pair<Long, String?> {
        if (isSimpleType(cfg)) {
            val srv = parseServer(cfg) ?: return -1L to "parseServer failed"
            if (tcpConnectTime(srv.host, srv.port) < 0) return -1L to "TCP connect to ${srv.host}:${srv.port} failed (1000 ms)"
        }
        val config = buildSpeedtestConfig(cfg) ?: return -1L to "buildSpeedtestConfig failed (unsupported protocol?)"
        return try {
            libv2ray.Libv2ray.measureOutboundDelay(config, testUrlOverride ?: TEST_URL) to null
        } catch (t: Throwable) {
            -1L to (t.message ?: t.javaClass.simpleName)
        }
    }

    /** Dedup key: a config ignoring only its name.
     *  Why the old key missed duplicates: for vmess,
     *  JSONObject.toString() order is hash order, and for
     *  URI configs the query-parameter order matters, so
     *  the same server delivered twice (different param
     *  order, or a different JSON key order) got two
     *  different keys and delete-duplicate did nothing.
     *  Now both are normalized: vmess keys are sorted,
     *  URI query params are sorted, host is lowercased,
     *  and a trailing "/" path is dropped. */
    fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                j.remove("ps")
                val keys = j.keys().asSequence().sorted().toList()
                keys.joinToString("|") { k -> "$k=${j.optString(k)}" }
            } else {
                val base = cfg.substringBefore("#")
                val scheme = base.substringBefore("://")
                val rest = base.substringAfter("://")
                val query = rest.substringAfter("?", "")
                val noQuery = rest.substringBefore("?")
                // Split userinfo@host:port from the path.
                val atIdx = noQuery.lastIndexOf("@")
                val hostPort = if (atIdx >= 0) noQuery.substring(atIdx + 1) else noQuery
                val userinfo = if (atIdx >= 0) noQuery.substring(0, atIdx + 1) else ""
                val path = hostPort.substringAfter("/", "")
                val hostPortOnly = hostPort.substringBefore("/")
                val sortedQuery = if (query.isBlank()) "" else "?" +
                    query.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
                val normPath = if (path.isBlank() || path == "/") "" else "/$path"
                "$scheme://$userinfo${hostPortOnly.lowercase()}$normPath$sortedQuery"
            }
        } catch (_: Exception) { cfg.substringBefore("#") }
    }

    /** Human name of a config: vmess ps, or the #fragment. */
    fun displayName(cfg: String): String {
        if (isCustom(cfg)) {
            val r = customRemarks(cfg)
            return if (r.isNotBlank()) r else "Custom"
        }
        if (isGroup(cfg)) return groupName(cfg)
        if (isChain(cfg)) return chainName(cfg)
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val ps = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                    .optString("ps", "")
                if (ps.isNotBlank()) ps else cfg.substringBefore("://")
            } else {
                val frag = cfg.substringAfter("#", "")
                if (frag.isNotBlank()) java.net.URLDecoder.decode(frag, "UTF-8") else cfg.substringBefore("://")
            }
        } catch (_: Exception) { cfg.substringBefore("://") }
    }

    fun protocolLabel(cfg: String): String {
        return when {
            isCustom(cfg) -> "Custom"
            isGroup(cfg) -> "GROUP"
            isChain(cfg) -> "CHAIN"
            cfg.startsWith("vless://") -> "VLESS"
            cfg.startsWith("vmess://") -> "VMess"
            cfg.startsWith("trojan://") -> "Trojan"
            cfg.startsWith("ss://") -> "Shadowsocks"
            cfg.startsWith("wireguard://") -> "WireGuard"
            else -> cfg.substringBefore("://").uppercase()
        }
    }

    /** Full type exactly like v2rayNG's server list
     *  (MainServerRowModels.serverProtocolDescription):
     *  PROTOCOL / network / security, joined with " / ",
     *  protocol ALL CAPS, network/security lowercase, tcp
     *  omitted, security kept only when tls or reality,
     *  insecure TLS shown as "tls insecure". Examples:
     *  VLESS / ws / tls, VMESS / grpc / tls, TROJAN / tls,
     *  SHADOWSOCKS, VLESS / ws, VLESS / ws / reality,
     *  HYSTERIA2 / hysteria / tls, WIREGUARD, SOCKS, HTTP,
     *  CUSTOM. (The user's quoted "Vless/tls/ws" does not
     *  match this checkout's format — order, casing and
     *  separator all differ — so this follows the source.) */
    fun fullType(cfg: String): String {
        if (isCustom(cfg)) return "CUSTOM"
        if (isGroup(cfg)) return "GROUP"
        if (isChain(cfg)) return "CHAIN"
        val scheme = cfg.substringBefore("://").lowercase()
        val proto = when (scheme) {
            "vless" -> "VLESS"
            "vmess" -> "VMESS"
            "trojan" -> "TROJAN"
            "ss" -> "SHADOWSOCKS"
            "socks", "socks5", "socks4" -> "SOCKS"
            "http" -> "HTTP"
            "wireguard" -> "WIREGUARD"
            "hysteria2", "hy2" -> "HYSTERIA2"
            "hysteria" -> "HYSTERIA"
            "tuic" -> "TUIC"
            else -> scheme.uppercase()
        }
        var net = ""
        var sec = ""
        var insecure = false
        try {
            when {
                cfg.startsWith("vmess://") -> {
                    var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                    while (raw.length % 4 != 0) raw += "="
                    val j = org.json.JSONObject(String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8))
                    net = j.optString("net", "tcp")
                    sec = j.optString("tls", "")
                }
                cfg.startsWith("vless://") || cfg.startsWith("trojan://") -> {
                    val p = parseProxy(cfg)
                    net = p.params["type"] ?: "tcp"
                    sec = if (scheme == "trojan") (p.params["security"] ?: "tls") else (p.params["security"] ?: "")
                    insecure = p.params["insecure"] == "1" || p.params["insecure"].equals("true", ignoreCase = true) ||
                        p.params["allowInsecure"] == "1" || p.params["allow_insecure"] == "1"
                }
                cfg.startsWith("hysteria2://") || cfg.startsWith("hy2://") -> {
                    val p = parseProxy(cfg)
                    net = "hysteria"
                    sec = p.params["security"] ?: "tls"
                }
            }
        } catch (_: Exception) {}
        if (sec != "tls" && sec != "reality") sec = ""
        val parts = mutableListOf(proto)
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts.add(net.lowercase())
        if (sec.isNotBlank()) parts.add(if (insecure && sec == "tls") "tls insecure" else sec.lowercase())
        return parts.joinToString(" / ")
    }

    /** Speedtest Xray config for one profile, as v2rayNG builds it. */
    fun buildSpeedtestConfig(cfg: String): String? {
        val outbounds = try { outboundsFor(cfg) } catch (_: Exception) { return null } ?: return null

        outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
        outbounds.put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("outbounds", outbounds)
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray())
                put("final", "proxy")
            })
        }.toString()
    }

    private fun parseProxy(cfg: String): ProxyParts {
        val uri = URI(cfg.substringBefore("#"))
        val q = uri.rawQuery ?: ""
        val params = q.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        val userInfo = try { java.net.URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8") } catch (_: Exception) { uri.rawUserInfo ?: "" }
        return ProxyParts(
            scheme = uri.scheme ?: "",
            server = uri.host ?: "",
            port = if (uri.port != -1) uri.port else 443,
            id = userInfo.substringBefore(":").ifBlank { userInfo },
            // a userinfo with no ":" carries the password
            // itself (standard Trojan links) — it must not become empty.
            password = if (userInfo.contains(":")) userInfo.substringAfter(":") else userInfo,
            params = params,
            path = uri.path ?: ""
        )
    }

    private data class ProxyParts(
        val scheme: String, val server: String, val port: Int,
        val id: String, val password: String,
        val params: Map<String, String>, val path: String
    )

    private fun streamSettings(p: ProxyParts): JSONObject {
        val s = JSONObject()
        val net = p.params["type"] ?: "tcp"
        s.put("network", net)
        val sec = p.params["security"] ?: ""
        if (sec == "reality" || sec == "tls") {
            s.put("security", sec)
            if (sec == "reality") {
                s.put("realitySettings", JSONObject().apply {
                    put("show", false)
                    put("fingerprint", p.params["fp"] ?: "chrome")
                    if (p.params["sni"] != null) put("serverName", p.params["sni"])
                    if (p.params["pbk"] != null) put("publicKey", p.params["pbk"])
                    put("shortId", p.params["sid"] ?: "")
                    put("spiderX", p.params["spx"] ?: "")
                })
            } else {
                s.put("tlsSettings", JSONObject().apply {
                    if (p.params["sni"] != null) put("serverName", p.params["sni"])
                    if (p.params["fp"] != null) put("fingerprint", p.params["fp"])
                })
            }
        }
        if (net == "ws") {
            s.put("wsSettings", JSONObject().apply {
                // prefer the query path, else the link's own URI path, else "/".
                put("path", p.params["path"]?.takeIf { it.isNotBlank() } ?: p.path.ifBlank { "/" })
                if (p.params["host"] != null) put("headers", JSONObject().apply {
                    put("Host", p.params["host"])
                })
            })
        }
        if (net == "grpc") {
            s.put("grpcSettings", JSONObject().apply {
                put("serviceName", p.params["serviceName"] ?: "")
            })
        }
        return s
    }

    fun outboundFor(cfg: String): JSONObject? {
        if (isCustom(cfg)) return customProxyOutbound(cfg)
        return when {
            cfg.startsWith("http://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "http"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            if (p.id.isNotBlank()) {
                                put("users", JSONArray().put(JSONObject().apply {
                                    put("user", p.id)
                                    put("pass", p.password)
                                }))
                            }
                        }))
                    })
                }
            }
            cfg.startsWith("socks://") || cfg.startsWith("socks5://") || cfg.startsWith("socks4://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "socks"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            if (p.id.isNotBlank()) {
                                put("users", JSONArray().put(JSONObject().apply {
                                    put("user", p.id)
                                    put("pass", p.password)
                                }))
                            }
                        }))
                    })
                }
            }
            cfg.startsWith("vless://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "vless"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", p.id)
                                put("encryption", p.params["encryption"] ?: "none")
                                if (!p.params["flow"].isNullOrBlank()) put("flow", p.params["flow"])
                            }))
                        }))
                    })
                    put("streamSettings", streamSettings(p))
                }
            }
            cfg.startsWith("vmess://") -> {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                val net = j.optString("net", "tcp")
                val tls = j.optString("tls", "")
                val ss = JSONObject().apply {
                    put("network", net)
                    if (tls == "tls" || tls == "reality") {
                        put("security", tls)
                        if (tls == "tls") {
                            put("tlsSettings", JSONObject().apply {
                                if (j.optString("sni").isNotBlank()) put("serverName", j.optString("sni"))
                            })
                        }
                        if (tls == "reality") {
                            put("realitySettings", JSONObject().apply {
                                put("show", false)
                                put("fingerprint", j.optString("fp").ifBlank { "chrome" })
                                if (j.optString("sni").isNotBlank()) put("serverName", j.optString("sni"))
                                if (j.optString("pbk").isNotBlank()) put("publicKey", j.optString("pbk"))
                                put("shortId", j.optString("sid", ""))
                                put("spiderX", j.optString("spx", ""))
                            })
                        }
                    }
                    if (net == "ws") {
                        put("wsSettings", JSONObject().apply {
                            put("path", j.optString("path", "/"))
                            if (j.optString("host").isNotBlank()) put("headers", JSONObject().apply {
                                put("Host", j.optString("host"))
                            })
                        })
                    }
                }
                JSONObject().apply {
                    put("protocol", "vmess"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().put(JSONObject().apply {
                            put("address", j.optString("add"))
                            put("port", j.optString("port").toIntOrNull() ?: 443)
                            put("users", JSONArray().put(JSONObject().apply {
                                put("id", j.optString("id"))
                                put("alterId", j.optString("aid").toIntOrNull() ?: 0)
                                put("security", "auto")
                            }))
                        }))
                    })
                    put("streamSettings", ss)
                }
            }
            cfg.startsWith("trojan://") -> {
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "trojan"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", p.server); put("port", p.port)
                            put("password", p.password)
                        }))
                    })
                    put("streamSettings", streamSettings(p))
                }
            }
            cfg.startsWith("ss://") -> {
                val p = parseProxy(cfg)
                var method = ""
                var pass = p.password
                var host = p.server
                var port = p.port
                // ss://base64(method:password@host:port) form
                if (p.id.isBlank() || !p.id.contains(":")) {
                    try {
                        val decoded = String(Base64.decode(
                            cfg.removePrefix("ss://").substringBefore("#").substringBefore("/"),
                            Base64.URL_SAFE), Charsets.UTF_8)
                        val at = decoded.lastIndexOf("@")
                        if (at > 0) {
                            method = decoded.substring(0, at).substringBefore(":")
                            pass = decoded.substring(0, at).substringAfter(":")
                            val hp = decoded.substring(at + 1)
                            host = hp.substringBeforeLast(":")
                            port = hp.substringAfterLast(":").toIntOrNull() ?: port
                        }
                    } catch (_: Exception) {}
                } else {
                    method = p.id.substringBefore(":")
                    pass = p.password
                }
                JSONObject().apply {
                    put("protocol", "shadowsocks"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("servers", JSONArray().put(JSONObject().apply {
                            put("address", host); put("port", port)
                            put("method", method.ifBlank { "aes-128-gcm" })
                            put("password", pass)
                            put("uot", true)
                        }))
                    })
                }
            }
            cfg.startsWith("wireguard://") -> {
                // WireGuard: v2rayNG skips the TCP pre-check for it
                // and measures through the Xray wireguard outbound.
                val p = parseProxy(cfg)
                JSONObject().apply {
                    put("protocol", "wireguard"); put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("secretKey", p.params["privateKey"] ?: "")
                        put("address", JSONArray().put("10.0.0.2/32"))
                        put("peers", JSONArray().put(JSONObject().apply {
                            put("publicKey", p.params["publicKey"] ?: "")
                            put("endpoint", "${p.server}:${p.port}")
                            put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                            put("keepAlive", 25)
                        }))
                    })
                }
            }
            else -> null
        }
    }
}
