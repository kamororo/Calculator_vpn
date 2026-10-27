package com.renamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Builds the full VPN-mode Xray config, following
 *  v2rayNG's v2ray_config_with_tun.json template: tun +
 *  socks inbounds, proxy/direct/block outbounds, private
 *  IPs routed direct. Shared by VpnActivity (normal
 *  connect) and EmergencyActivity (emergency connect),
 *  so both connect through exactly the same config.
 *
 *  The settings page (VpnSettingsActivity) stores its
 *  values in the same "renamer_vpn" prefs. This builder
 *  applies every setting Xray has a field for, exactly
 *  the way v2rayNG applies it (mux skip list, fragment
 *  finalmask, balancer observatory, ...). Settings Xray
 *  has no field for stay stored-only and are NOT
 *  invented into the JSON: Hev TUN (needs the native
 *  libhev-socks5-tunnel.so, which cannot be built
 *  here), root mode and LAN sharing (need a rooted
 *  device with iptables). */
object VpnConfigBuilder {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)

    private fun str(p: android.content.SharedPreferences, key: String, def: String): String =
        p.getString(key, def) ?: def

    private fun intOr(p: android.content.SharedPreferences, key: String, def: Int): Int =
        str(p, key, def.toString()).toIntOrNull() ?: def

    /** Parse a "min-max" string safely; on any parse
     *  error the supplied defaults are used. */
    private fun minMax(s: String, defMin: Int, defMax: Int): Pair<Int, Int> {
        val parts = s.split("-").mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size >= 2) parts[0] to parts[1] else defMin to defMax
    }

    /** MTU for the Android VPN interface. This is NOT
     *  part of the Xray JSON: VpnClientService builds
     *  the TUN with the Android Builder, so the parent
     *  should read this there. Reads the new "vpn_mtu"
     *  key first, falling back to the legacy "mtu". */
    fun mtu(ctx: Context): Int =
        intOr(prefs(ctx), "vpn_mtu", intOr(prefs(ctx), "mtu", 1500))

    /** VPN interface address, same note as mtu(): the
     *  Android Builder in VpnClientService consumes
     *  this, not the Xray JSON. */
    fun interfaceAddress(ctx: Context): String =
        str(prefs(ctx), "vpn_interface_address", "10.10.14.x")

    /** Delay-test URL for XraySpeedtest (not used by
     *  this builder's JSON; wired into the speedtest
     *  by the parent). */
    fun delayTestUrl(ctx: Context): String =
        str(prefs(ctx), "delay_test_url", "https://www.gstatic.com/generate_204")
            .takeIf { it.isNotBlank() } ?: "https://www.gstatic.com/generate_204"

    /** Delay-test concurrency for XraySpeedtest. */
    fun delayTestConcurrency(ctx: Context): Int =
        intOr(prefs(ctx), "delay_test_concurrency", 16).coerceIn(1, 64)

    /** Local socks port. Dynamic proxy port (v2rayNG
     *  "dynamic port") means port 0: Xray picks a
     *  free port at runtime. */
    fun socksPort(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.getBoolean("dynamic_proxy_port", false)) return 0
        return if (p.getBoolean("enable_local_proxy", false)) {
            intOr(p, "local_proxy_port", intOr(p, "socks_port", 10808))
        } else {
            intOr(p, "socks_port", 10808)
        }
    }

    /** Local inbounds: tun (VPN mode only) + socks,
     *  plus an http inbound when "append HTTP proxy"
     *  is on. Socks listen follows allow_lan
     *  (v2rayNG: 0.0.0.0 when LAN sharing is allowed,
     *  127.0.0.1 otherwise). */
    private fun vpnInbounds(ctx: Context, includeTun: Boolean): JSONArray {
        val p = prefs(ctx)
        val port = socksPort(ctx)
        val socksUdp = p.getBoolean("socks_udp", true)
        val sniffingOn = p.getBoolean("enable_sniffing", true)
        val routeOnly = p.getBoolean("route_only", false)
        val listen = if (p.getBoolean("allow_lan", false)) "0.0.0.0" else "127.0.0.1"
        val arr = JSONArray()
        if (includeTun) {
            arr.put(JSONObject().apply {
                put("tag", "tun"); put("protocol", "tun")
                put("settings", JSONObject().apply {
                    put("name", "xray0"); put("MTU", mtu(ctx)); put("userLevel", 8)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", sniffingOn)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    if (routeOnly) put("routeOnly", true)
                })
            })
        }
        val socks = JSONObject().apply {
            put("tag", "socks"); put("port", port); put("protocol", "socks")
            put("listen", listen)
            put("settings", JSONObject().apply {
                put("auth", "noauth"); put("udp", socksUdp); put("userLevel", 8)
            })
        }
        // Local proxy credentials: Xray socks inbound
        // supports auth "password" with accounts; only
        // switch to it when a username was actually
        // entered, so the default stays noauth exactly
        // as before.
        val user = str(p, "local_proxy_user", "")
        val pass = str(p, "local_proxy_pass", "")
        if (p.getBoolean("enable_local_proxy", false) && user.isNotBlank()) {
            socks.getJSONObject("settings").apply {
                put("auth", "password")
                put("accounts", JSONArray().put(JSONObject().apply {
                    put("user", user); put("pass", pass)
                }))
            }
        }
        arr.put(socks)
        if (p.getBoolean("append_http_proxy", false)) {
            val httpPort = if (port == 0) 0 else port + 1
            arr.put(JSONObject().apply {
                put("tag", "http"); put("port", httpPort); put("protocol", "http")
                put("listen", listen)
                put("settings", JSONObject().apply { put("userLevel", 8) })
            })
        }
        return arr
    }

    /** DNS section: remote/domestic keys win when
     *  present, otherwise the legacy comma list;
     *  fakedns first when enabled; queryStrategy
     *  only when the user set one of its switches;
     *  hosts from prefs plus any extra pairs (used
     *  by pre-resolve "add to DNS Hosts"). */
    private fun dnsObject(ctx: Context, extraHosts: Map<String, String>): JSONObject {
        val p = prefs(ctx)
        val dnsServers = mutableListOf<String>()
        if (p.getBoolean("enable_fake_dns", false)) dnsServers.add("fakedns")
        if (p.contains("remote_dns") || p.contains("domestic_dns")) {
            val remote = str(p, "remote_dns", "1.1.1.1").trim()
            val domestic = str(p, "domestic_dns", "223.5.5.5").trim()
            if (remote.isNotBlank()) dnsServers.add(remote)
            if (domestic.isNotBlank()) dnsServers.add(domestic)
        } else {
            dnsServers += str(p, "dns", "1.1.1.1,8.8.8.8")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        if (dnsServers.isEmpty()) { dnsServers.add("1.1.1.1"); dnsServers.add("8.8.8.8") }
        val dnsHosts = JSONObject()
        str(p, "dns_hosts", "").split(",").forEach { pair ->
            val kv = pair.split(":", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) {
                dnsHosts.put(kv[0].trim(), kv[1].trim())
            }
        }
        for ((k, v) in extraHosts) dnsHosts.put(k, v)
        val queryStrategy: String? = when {
            p.contains("enable_ipv6") && !p.getBoolean("enable_ipv6", false) -> "UseIPv4"
            p.getBoolean("prefer_ipv6", false) -> "UseIPv6"
            else -> null
        }
        return JSONObject().apply {
            if (queryStrategy != null) put("queryStrategy", queryStrategy)
            put("servers", JSONArray().apply { for (d in dnsServers) put(d) })
            if (dnsHosts.length() > 0) put("hosts", dnsHosts)
        }
    }

    /** Routing section: private IPs go direct, written
     *  as literal CIDRs, NOT geoip:private: a geo rule
     *  needs geoip.dat on disk, and a missing geo file
     *  makes the whole config fail to parse. The
     *  bypass_lan choice wins when explicitly set,
     *  otherwise the legacy routing_mode. */
    private fun routingObject(ctx: Context): JSONObject {
        val p = prefs(ctx)
        val bypass = if (p.contains("bypass_lan")) {
            str(p, "bypass_lan", "Bypass")
        } else if (str(p, "routing_mode", "bypass_private") == "global") {
            "Global"
        } else {
            "Bypass"
        }
        return JSONObject().apply {
            put("domainStrategy", "AsIs")
            when (bypass) {
                "Bypass" -> put("rules", JSONArray().put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray()
                        .put("10.0.0.0/8")
                        .put("172.16.0.0/12")
                        .put("192.168.0.0/16")
                        .put("127.0.0.0/8")
                        .put("169.254.0.0/16")
                        .put("::1/128")
                        .put("fc00::/7")
                        .put("fe80::/10"))
                    put("outboundTag", "direct")
                }))
                "Direct" -> put("final", "direct")
                else -> { /* Global: no rules */ }
            }
        }
    }

    /** Mux + fragment for one proxy outbound, exactly
     *  as v2rayNG applies them (CoreOutboundBuilder):
     *  mux is disabled for shadowsocks/socks/http/
     *  trojan/wireguard/hysteria and for xhttp
     *  networks; xudpProxyUDP443 comes from the
     *  mux_quic choice (our "drop" maps to Xray's
     *  "skip"); VLESS with a flow gets concurrency -1.
     *  Fragment is a streamSettings.finalmask (NOT a
     *  sockopt field — that was wrong and Xray
     *  ignores it): only for tls/reality security,
     *  skipped when a dialerProxy is present, and
     *  REALITY + tlshello becomes packets "1-3". */
    private fun applyMuxAndFragment(ctx: Context, ob: JSONObject) {
        val p = prefs(ctx)
        val protocol = ob.optString("protocol", "").lowercase()
        val ss = ob.optJSONObject("streamSettings")
        val network = ss?.optString("network", "") ?: ""
        val muxSkip = protocol in setOf(
            "shadowsocks", "socks", "http", "trojan",
            "wireguard", "hysteria2", "hysteria"
        ) || network == "xhttp"
        if (p.getBoolean("enable_mux", false) && !muxSkip) {
            var concurrency = intOr(p, "mux_tcp", 8)
            if (protocol == "vless") {
                val flow = ob.optJSONObject("settings")
                    ?.optJSONArray("vnext")?.optJSONObject(0)
                    ?.optJSONArray("users")?.optJSONObject(0)
                    ?.optString("flow", "")
                if (!flow.isNullOrBlank()) concurrency = -1
            }
            val quic = when (str(p, "mux_quic", "reject")) {
                "drop", "skip" -> "skip"
                "allow" -> "allow"
                else -> "reject"
            }
            ob.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", concurrency)
                put("xudpConcurrency", intOr(p, "mux_xudp", 8))
                put("xudpProxyUDP443", quic)
            })
        }
        if (p.getBoolean("enable_fragment", false)) {
            val stream = ob.optJSONObject("streamSettings")
                ?: JSONObject().also { ob.put("streamSettings", it) }
            val security = stream.optString("security", "")
            val hasDialerProxy = stream.optJSONObject("sockopt")
                ?.optString("dialerProxy", "").isNullOrBlank().not()
            if ((security == "tls" || security == "reality") && !hasDialerProxy) {
                var packets = str(p, "fragment_packets", "tlshello")
                if (security == "reality" && packets == "tlshello") packets = "1-3"
                val (lenMin, lenMax) = minMax(str(p, "fragment_length", "50-100"), 50, 100)
                val (intMin, intMax) = minMax(str(p, "fragment_interval", "10-20"), 10, 20)
                val maxSplit = intOr(p, "fragment_max_split", 10)
                stream.put("finalmask", JSONObject().apply {
                    put("tcp", JSONArray().put(JSONObject().apply {
                        put("type", "fragment")
                        put("settings", JSONObject().apply {
                            put("packets", packets)
                            put("length", "$lenMin-$lenMax")
                            put("delay", "$intMin-$intMax")
                            put("maxSplit", maxSplit)
                        })
                    }))
                    put("udp", JSONArray().put(JSONObject().apply {
                        put("type", "noise")
                        put("settings", JSONObject().apply {
                            put("noise", JSONArray().put(JSONObject().apply {
                                put("rand", "10-20")
                                put("delay", "10-16")
                            }))
                        })
                    }))
                })
            }
        }
    }

    /** Assemble a complete config from proxy
     *  outbounds. routingExtra adds balancer routing
     *  (and overrides final) for group connects;
     *  observatory is the top-level observatory /
     *  burstObservatory object for those connects. */
    private fun assemble(
        ctx: Context,
        proxies: JSONArray,
        includeTun: Boolean,
        extraHosts: Map<String, String>,
        routingExtra: (JSONObject.() -> Unit)? = null,
        observatory: Pair<String, JSONObject>? = null
    ): String {
        val p = prefs(ctx)
        val logLevel = str(p, "log_level", "warning").takeIf { it.isNotBlank() } ?: "warning"
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", logLevel) })
            put("inbounds", vpnInbounds(ctx, includeTun))
            put("outbounds", JSONArray().apply {
                for (i in 0 until proxies.length()) {
                    val ob = proxies.getJSONObject(i)
                    applyMuxAndFragment(ctx, ob)
                    put(ob)
                }
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
                put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            })
            put("routing", routingObject(ctx).apply { routingExtra?.invoke(this) })
            put("dns", dnsObject(ctx, extraHosts))
            // Live speed (v2rayNG PREF_SPEED_ENABLED):
            // the stats + policy.system objects Xray
            // needs for outbound uplink/downlink stats.
            if (p.getBoolean("show_speed", false)) {
                put("stats", JSONObject())
                put("policy", JSONObject().apply {
                    put("system", JSONObject().apply {
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    })
                })
            }
            if (observatory != null) put(observatory.first, observatory.second)
        }.toString()
    }

    /** Balancer connect for a policy group: its
     *  members become tagged outbounds, routing
     *  balances across them, and the Observatory
     *  objects from the settings page are emitted
     *  (leastPing -> observatory, leastLoad ->
     *  burstObservatory). One member just connects
     *  directly; zero members cannot connect. */
    private fun buildBalancer(
        ctx: Context,
        header: String,
        includeTun: Boolean,
        extraHosts: Map<String, String>
    ): String? {
        val p = prefs(ctx)
        val servers = VpnSubs.loadServers(ctx)
        val members = VpnSubs.groupMembers(servers, header).filter {
            !XraySpeedtest.isChain(it) && XraySpeedtest.outboundFor(it) != null
        }
        if (members.isEmpty()) return null
        if (members.size == 1) return buildInternal(ctx, members[0], includeTun, extraHosts)
        val tags = mutableListOf<String>()
        val proxies = JSONArray()
        for ((i, member) in members.withIndex()) {
            val ob = XraySpeedtest.outboundFor(member) ?: continue
            val tag = "m$i"
            ob.put("tag", tag)
            tags += tag
            proxies.put(ob)
        }
        if (tags.size < 2) return buildInternal(ctx, members[0], includeTun, extraHosts)
        val strategy = XraySpeedtest.groupStrategy(header)
        val observatory: Pair<String, JSONObject> = if (strategy == "leastLoad") {
            "burstObservatory" to JSONObject().apply {
                put("subjectSelector", JSONArray().apply { for (t in tags) put(t) })
                put("pingConfig", JSONObject().apply {
                    put("destination", delayTestUrl(ctx))
                    put("httpMethod", str(p, "least_load_mode", "HEAD"))
                    put("interval", str(p, "least_load_interval", "5m"))
                    put("sampling", intOr(p, "least_load_sampling", 2))
                    put("timeout", str(p, "least_load_timeout", "30s"))
                })
            }
        } else {
            "observatory" to JSONObject().apply {
                put("subjectSelector", JSONArray().apply { for (t in tags) put(t) })
                put("probeUrl", delayTestUrl(ctx))
                put("probeInterval", str(p, "least_ping_interval", "3m"))
                put("enableConcurrency", true)
            }
        }
        return assemble(ctx, proxies, includeTun, extraHosts,
            routingExtra = {
                put("balancers", JSONArray().put(JSONObject().apply {
                    put("tag", "balancer")
                    put("selector", JSONArray().apply { for (t in tags) put(t) })
                    put("strategy", JSONObject().apply { put("type", strategy) })
                }))
                put("final", "balancer")
            },
            observatory = observatory)
    }

    /** A custom config connects with its own JSON:
     *  only the inbounds are replaced (tun + socks in
     *  VPN mode, socks only in proxy-only mode), and
     *  the speed stats objects are added when live
     *  speed is on — everything else (outbounds,
     *  routing, dns) stays exactly as the user wrote
     *  it, because a custom config IS the config. */
    private fun buildCustom(ctx: Context, cfg: String, includeTun: Boolean): String? {
        return try {
            val json = JSONObject(cfg)
            json.put("inbounds", vpnInbounds(ctx, includeTun))
            val p = prefs(ctx)
            if (p.getBoolean("show_speed", false)) {
                if (!json.has("stats")) json.put("stats", JSONObject())
                if (!json.has("policy")) {
                    json.put("policy", JSONObject().apply {
                        put("system", JSONObject().apply {
                            put("statsOutboundUplink", true)
                            put("statsOutboundDownlink", true)
                        })
                    })
                }
            }
            json.toString()
        } catch (_: Exception) { null }
    }

    private fun buildInternal(
        ctx: Context,
        cfg: String,
        includeTun: Boolean,
        extraHosts: Map<String, String>
    ): String? {
        return when {
            XraySpeedtest.isGroup(cfg) -> buildBalancer(ctx, cfg, includeTun, extraHosts)
            XraySpeedtest.isCustom(cfg) -> buildCustom(ctx, cfg, includeTun)
            else -> {
                val proxies = XraySpeedtest.outboundsFor(cfg) ?: return null
                assemble(ctx, proxies, includeTun, extraHosts)
            }
        }
    }

    fun build(ctx: Context, cfg: String, extraHosts: Map<String, String> = emptyMap()): String? {
        return buildInternal(ctx, cfg, includeTun = true, extraHosts)
    }

    /** Pre-resolve method from the settings page. */
    fun preResolveMode(ctx: Context): String =
        str(prefs(ctx), "pre_resolve", "Resolve and add to DNS Hosts")

    /** Resolve one host to its first IPv4 address
     *  (IPv4 preferred, like the flag lookup), or
     *  null on failure. Never called on the UI thread
     *  by the activity — see startVpn. */
    private fun resolveIpv4(host: String): String? {
        return try {
            val all = java.net.InetAddress.getAllByName(host)
            all.firstOrNull { it.hostAddress?.contains(".") == true }?.hostAddress
                ?: all.firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.matches(Regex("^[0-9.]+$")) || host.contains(":")
    }

    /** All domain hosts inside a config (single link,
     *  chain parts, or group members) mapped to their
     *  resolved IPv4. The caller decides how to use
     *  the map: "Resolve and add to DNS Hosts" passes
     *  it to build() as extraHosts; "Resolve only"
     *  rewrites outbound addresses with it (below).
     *  Customs are skipped: a custom JSON is the
     *  user's own config, not to be rewritten.
     *  SPEED: lookups run in parallel under ONE
     *  shared 3s deadline. The old code resolved
     *  hosts one by one with no timeout at all, so
     *  a slow or filtered DNS made the connect tap
     *  look dead for many seconds. Unresolved hosts
     *  are simply left for Xray to resolve at dial
     *  time — pre-resolution is an optimization,
     *  never a gate. */
    fun preResolveHosts(ctx: Context, cfg: String): Map<String, String> {
        val parts: List<String> = when {
            XraySpeedtest.isChain(cfg) -> XraySpeedtest.chainParts(cfg)
            XraySpeedtest.isGroup(cfg) ->
                VpnSubs.groupMembers(VpnSubs.loadServers(ctx), cfg)
            XraySpeedtest.isCustom(cfg) -> emptyList()
            else -> listOf(cfg)
        }
        val hosts = mutableListOf<String>()
        for (part in parts) {
            val host = XraySpeedtest.parseServer(part)?.host ?: continue
            if (host.isBlank() || isIpLiteral(host) || hosts.contains(host)) continue
            hosts += host
        }
        if (hosts.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true }
        }
        try {
            val futures = hosts.map { h -> h to pool.submit<String?> { resolveIpv4(h) } }
            val deadline = System.currentTimeMillis() + 3000
            for ((h, f) in futures) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val ip = try { f.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: Exception) { null }
                if (ip != null) out[h] = ip
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    fun applyResolvedIp(jsonStr: String, resolved: Map<String, String>): String {
        if (resolved.isEmpty()) return jsonStr
        return try {
            val json = JSONObject(jsonStr)
            val outs = json.optJSONArray("outbounds") ?: return jsonStr
            for (i in 0 until outs.length()) {
                val ob = outs.optJSONObject(i) ?: continue
                val st = ob.optJSONObject("settings") ?: continue
                val vnext = st.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val v = vnext.getJSONObject(0)
                    val ip = resolved[v.optString("address", "")]
                    if (ip != null) v.put("address", ip)
                }
                val servers = st.optJSONArray("servers")
                if (servers != null && servers.length() > 0) {
                    val v = servers.getJSONObject(0)
                    val ip = resolved[v.optString("address", "")]
                    if (ip != null) v.put("address", ip)
                }
            }
            json.toString()
        } catch (_: Exception) { jsonStr }
    }

    /** Proxy-only mode: the same config, but with
     *  local proxy inbounds only and no tun inbound.
     *  The caller runs the core without a VPN service
     *  (see ProxyOnlyService). */
    fun buildProxyOnly(ctx: Context, cfg: String, extraHosts: Map<String, String> = emptyMap()): String? {
        return buildInternal(ctx, cfg, includeTun = false, extraHosts)
    }
}
