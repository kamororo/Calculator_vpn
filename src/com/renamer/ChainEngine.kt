package com.renamer

import android.content.Context
import android.content.Intent
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Double-chain engine ("زنجیره دوتایی"): traffic goes
 * device -> hop1 -> hop2 -> destination, so no single
 * server sees both your real IP and your destination.
 *
 * Hop types (each hop, independently):
 *  - "v2ray": a config from the saved v2ray list, or a
 *    manually pasted config (ref = the config string).
 *  - "emergency": the emergency section's best server,
 *    resolved fresh at connect time (ref unused).
 *  - "wireguard": a saved WireGuard tunnel (ref = its
 *    name), run as an Xray wireguard outbound.
 *  - "openvpn": a saved OpenVPN profile (ref = its
 *    name). OpenVPN can ONLY be hop2, never hop1:
 *    Android allows a single VPN tunnel at a time, so
 *    an OpenVPN first hop could never feed traffic
 *    into a second hop. As hop2 it works the other
 *    way round: Xray runs as a local proxy for hop1,
 *    and the OpenVPN client connects to its server
 *    THROUGH that proxy, so device traffic still
 *    passes hop1 first, then OpenVPN.
 *
 * Honest limits (also stated in the UI and guide):
 *  - WireGuard hops and the OpenVPN-hop2 proxy path
 *    use mechanisms that are correct per the cores'
 *    own APIs, but have NOT been verified on a real
 *    device yet — if a combination fails, the chain
 *    status shows the error instead of silently
 *    falling back to a single hop.
 *  - A chain is only as healthy as its weakest hop:
 *    if either hop is down, the chain does not connect.
 */
object ChainEngine {
    const val PREFS = "renamer_chain"
    const val PROXY_SOCKS_PORT = 10809
    const val PROXY_HTTP_PORT = 10810

    data class Hop(val type: String, val ref: String, val label: String)

    // 2.16: in-memory chain log (like WG's), behind
    // the top log icon. Outcomes only, never configs.
    private val logLines = ArrayDeque<String>()
    fun log(msg: String) {
        val tm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.addLast("$tm  $msg")
        while (logLines.size > 100) logLines.removeFirst()
    }
    fun logText(): String = logLines.joinToString("\n")
    fun clearLog() { logLines.clear() }

    fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hop(ctx: Context, n: Int): Hop? {
        val p = prefs(ctx)
        val type = p.getString("hop${n}_type", "") ?: ""
        if (type.isBlank()) return null
        return Hop(type, p.getString("hop${n}_ref", "") ?: "",
            p.getString("hop${n}_label", "") ?: "")
    }

    fun saveHop(ctx: Context, n: Int, hop: Hop) {
        prefs(ctx).edit()
            .putString("hop${n}_type", hop.type)
            .putString("hop${n}_ref", hop.ref)
            .putString("hop${n}_label", hop.label)
            .apply()
    }

    fun swapHops(ctx: Context) {
        val h1 = hop(ctx, 1)
        val h2 = hop(ctx, 2)
        if (h1 != null) saveHop(ctx, 2, h1)
        if (h2 != null) saveHop(ctx, 1, h2)
    }

    fun isRunning(ctx: Context): Boolean {
        if (!prefs(ctx).getBoolean("chain_running", false)) return false
        return when (prefs(ctx).getString("chain_mode", "")) {
            "xray" -> VpnClientService.isRunning
            // 2.38: during "connecting" the chain IS
            // running — otherwise tapping the button
            // restarts instead of stopping.
            "ovpn" -> (OvpnEngine.isConnected || OvpnEngine.isConnecting)
            else -> false
        }
    }

    fun setRunning(ctx: Context, mode: String?) {
        prefs(ctx).edit()
            .putBoolean("chain_running", mode != null)
            .putString("chain_mode", mode ?: "")
            .apply()
    }

    /** Resolve a hop to its v2ray link string, or null.
     *  Emergency hops resolve to the freshly fetched
     *  best emergency config. */
    fun hopLink(ctx: Context, hop: Hop): String? {
        return when (hop.type) {
            // 2.40: custom JSON configs have no "://"
            // but ARE valid v2ray hops (their proxy
            // outbound is extracted by
            // customProxyOutbound) — rejecting them
            // made every custom-hop chain fail
            // instantly at build time.
            "v2ray" -> hop.ref.takeIf { it.contains("://") || XraySpeedtest.isCustom(it) }
            "emergency" -> try {
                EmergencyConnect.sortedConfigs(ctx).firstOrNull()
            } catch (_: Throwable) { null }
            else -> null
        }
    }

    /** Xray outbound for a hop, tagged as requested.
     *  Null when the hop cannot be resolved. */
    fun hopOutbound(ctx: Context, hop: Hop, tag: String): JSONObject? {
        return when (hop.type) {
            "v2ray" -> {
                val ob = XraySpeedtest.outboundFor(hop.ref) ?: return null
                ob.put("tag", tag)
                ob
            }
            "emergency" -> {
                val link = hopLink(ctx, hop) ?: return null
                val ob = XraySpeedtest.outboundFor(link) ?: return null
                ob.put("tag", tag)
                ob
            }
            "wireguard" -> wgOutbound(ctx, hop.ref, tag)
            else -> null
        }
    }

    /** WireGuard outbound from a saved tunnel's .conf
     *  text, per Xray's wireguard outbound format. */
    fun wgOutbound(ctx: Context, tunnelName: String, tag: String): JSONObject? {
        return try {
            val arr = JSONArray(ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]")
            var conf: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == tunnelName) {
                    conf = o.optString("conf")
                    break
                }
            }
            if (conf.isNullOrBlank()) return null
            var section = ""
            var priv = ""
            var addr = ""
            var pub = ""
            var endpoint = ""
            val allowed = mutableListOf<String>()
            var keepAlive = 0
            var psk = ""
            var reservedRaw = ""
            for (raw in conf.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    section = line
                    continue
                }
                val kv = line.split("=", limit = 2)
                if (kv.size != 2) continue
                val k = kv[0].trim()
                val v = kv[1].trim()
                when {
                    section.contains("Interface") && k == "PrivateKey" -> priv = v
                    section.contains("Interface") && k == "Address" -> addr = v.split(",").firstOrNull()?.trim() ?: ""
                    section.contains("Peer") && k == "PublicKey" -> pub = v
                    section.contains("Peer") && k == "Endpoint" -> endpoint = v
                    section.contains("Peer") && k == "AllowedIPs" -> allowed += v.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    section.contains("Peer") && k == "PersistentKeepalive" -> keepAlive = v.toIntOrNull() ?: 0
                    section.contains("Peer") && k == "PresharedKey" -> psk = v
                    section.contains("Interface") && k == "Reserved" -> reservedRaw = v
                }
            }
            if (priv.isBlank() || pub.isBlank() || endpoint.isBlank()) return null
            if (addr.isBlank()) addr = "10.0.0.2/32"
            if (allowed.isEmpty()) allowed += "0.0.0.0/0"
            // 2.60: pre-resolve a DOMAIN endpoint to
            // an IP before handing it to Xray. The
            // WireGuard device resolves its peer
            // through Go's resolver, which on
            // Android often cannot use the system
            // DNS — so a domain endpoint silently
            // never handshakes, and the chain (and
            // the WG test, which shares this
            // builder) looks connected but carries
            // no traffic, while the same tunnel
            // connects fine directly (the system
            // backend resolves via Android DNS).
            // WireGuard has no SNI, so pinning the
            // resolved IP is cryptographically
            // safe; on any failure the domain is
            // kept exactly as before. Runs only on
            // chain-start / test threads, never UI.
            val effEndpoint = try {
                val host: String
                val port: String
                if (endpoint.startsWith("[")) {
                    host = endpoint.substringAfter("[").substringBefore("]")
                    port = endpoint.substringAfter("]:", "")
                } else {
                    host = endpoint.substringBeforeLast(":")
                    port = endpoint.substringAfterLast(":", "")
                }
                val isIp = host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) || host.contains(":")
                if (!isIp && port.isNotBlank()) {
                    val addrs = java.net.InetAddress.getAllByName(host)
                    val v4 = addrs.firstOrNull { it.hostAddress?.contains(".") == true && !it.hostAddress!!.contains(":") }
                    val picked = v4 ?: addrs.firstOrNull()
                    if (picked?.hostAddress != null) {
                        val ip = picked.hostAddress!!
                        if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
                    } else endpoint
                } else endpoint
            } catch (_: Exception) { endpoint }
            JSONObject().apply {
                put("protocol", "wireguard")
                put("tag", tag)
                put("settings", JSONObject().apply {
                    put("secretKey", priv)
                    put("address", JSONArray().put(addr))
                    put("mtu", 1420)
                    put("peers", JSONArray().put(JSONObject().apply {
                        put("publicKey", pub)
                        put("endpoint", effEndpoint)
                        put("allowedIPs", JSONArray().apply {
                            for (a in allowed) put(a)
                        })
                        if (keepAlive > 0) put("keepAlive", keepAlive)
                        if (psk.isNotBlank()) put("preSharedKey", psk)
                    }))
                    // 2.51: Xray's WireGuard settings take
                    // "reserved" (exactly 3 bytes, base64
                    // in JSON). Our builder dropped it, so
                    // a server expecting reserved bytes
                    // rejected the handshake — the chain
                    // looked connected but carried no
                    // traffic. Conf forms: "1, 2, 3" |
                    // base64 | hex; anything else keeps
                    // the old behaviour (omitted).
                    val reservedB64: String? = try {
                        val bytes: ByteArray? = when {
                            reservedRaw.contains(",") -> {
                                val parts = reservedRaw.split(",").map { it.trim().toIntOrNull() ?: -1 }
                                if (parts.size == 3 && parts.all { it in 0..255 }) {
                                    parts.map { it.toByte() }.toByteArray()
                                } else null
                            }
                            reservedRaw.isNotBlank() -> {
                                try {
                                    val d = android.util.Base64.decode(reservedRaw, android.util.Base64.DEFAULT)
                                    if (d.size == 3) d else null
                                } catch (_: Exception) {
                                    val h = reservedRaw.trim()
                                    if (h.length == 6 && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                                        ByteArray(3) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                                    } else null
                                }
                            }
                            else -> null
                        }
                        bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    } catch (_: Exception) { null }
                    if (reservedB64 != null) put("reserved", reservedB64)
                })
            }
        } catch (_: Exception) { null }
    }

    /** Full VPN-mode Xray config for a two-hop chain
     *  where both hops run inside Xray (v2ray /
     *  emergency / wireguard). Wire order: device ->
     *  hop1 -> hop2 -> destination, so hop2's outbound
     *  (tag "proxy", the routing final) dials through
     *  hop1 via sockopt.dialerProxy — the same
     *  mechanism the app's existing CHAIN:: configs
     *  already use. */
    fun vpnConfig(ctx: Context, h1: Hop, h2: Hop): String? {
        return try {
            // Pure link chains take the proven path:
            // VpnConfigBuilder already handles CHAIN::
            // with dialerProxy, mux/fragment and routing.
            if ((h1.type == "v2ray" || h1.type == "emergency") &&
                (h2.type == "v2ray" || h2.type == "emergency")) {
                val l1 = hopLink(ctx, h1) ?: return null
                val l2 = hopLink(ctx, h2) ?: return null
                return VpnConfigBuilder.build(ctx, "CHAIN::chain|||$l2|||$l1")
            }
            // Mixed chains (a WireGuard hop involved):
            // take the builder's template and replace
            // only the outbounds array.
            val h1ob = hopOutbound(ctx, h1, "hop1") ?: return null
            val h2ob = hopOutbound(ctx, h2, "proxy") ?: return null
            val ss = h2ob.optJSONObject("streamSettings")
                ?: JSONObject().also { h2ob.put("streamSettings", it) }
            val sockopt = ss.optJSONObject("sockopt")
                ?: JSONObject().also { ss.put("sockopt", it) }
            sockopt.put("dialerProxy", "hop1")
            val templateLink = firstSavedLink(ctx)
                ?: "vless://u@127.0.0.1:443?encryption=none&security=none&type=tcp#dummy"
            val base = JSONObject(VpnConfigBuilder.build(ctx, templateLink) ?: return null)
            base.put("outbounds", JSONArray().apply {
                put(h2ob)
                put(h1ob)
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
                put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            })
            base.toString()
        } catch (_: Exception) { null }
    }

    private fun firstSavedLink(ctx: Context): String? {
        return try {
            ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.map { it.trim() }?.firstOrNull {
                    it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
                }
        } catch (_: Exception) { null }
    }

    /** Local-proxy Xray config for the OpenVPN-hop2
     *  case: SOCKS + HTTP inbounds on loopback only,
     *  one outbound (hop1). The OpenVPN client then
     *  connects to its server through the HTTP proxy,
     *  so device traffic still passes hop1 first. */
    fun proxyConfig(ctx: Context, h1: Hop): String? {
        return try {
            val h1ob = hopOutbound(ctx, h1, "proxy") ?: return null
            val templateLink = firstSavedLink(ctx)
                ?: "vless://u@127.0.0.1:443?encryption=none&security=none&type=tcp#dummy"
            val base = JSONObject(VpnConfigBuilder.build(ctx, templateLink) ?: return null)
            base.put("inbounds", JSONArray()
                .put(JSONObject().apply {
                    put("tag", "socks"); put("protocol", "socks")
                    put("listen", "127.0.0.1"); put("port", PROXY_SOCKS_PORT)
                    put("settings", JSONObject().apply {
                        put("auth", "noauth"); put("udp", true)
                    })
                })
                .put(JSONObject().apply {
                    put("tag", "http"); put("protocol", "http")
                    put("listen", "127.0.0.1"); put("port", PROXY_HTTP_PORT)
                    put("settings", JSONObject())
                }))
            base.put("outbounds", JSONArray().apply {
                put(h1ob)
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
                put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            })
            base.put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("final", "proxy")
            })
            base.toString()
        } catch (_: Exception) { null }
    }

    // --- Local proxy core (OpenVPN-hop2 only) ---
    @Volatile private var proxyController: CoreController? = null

    fun startProxyCore(ctx: Context, config: String): Boolean {
        return try {
            if (!CoreInit.init(ctx)) return false
            stopProxyCore()
            // Wait until the old core really
            // released 10810 (shutdown is async):
            // starting a new core while the old
            // listener lingers makes the new core's
            // HTTP inbound fail to bind, and the
            // OVPN client then dials a stale or
            // half-dead proxy — the "connected once,
            // never again" chain symptom.
            val freeDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < freeDeadline) {
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", PROXY_HTTP_PORT), 200)
                    s.close()
                    Thread.sleep(200)
                } catch (_: Exception) { break }
            }
            val c = Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                override fun onEmitStatus(code: Long, message: String?): Long = 0
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            })
            // No TUN here: this core is a loopback proxy
            // only, so there is no fd to hand over.
            // 2.57: startLoop RETURNS once the core
            // is running (it does not block — the
            // old "blocking startLoop" comment was
            // wrong), so the controller MUST be kept:
            // the old code nulled it right after
            // startLoop returned, which made
            // stopProxyCore a no-op forever and
            // leaked every proxy core — and its
            // 10810 listener — past its session.
            proxyController = c
            c.startLoop(config, -1)
            true
        } catch (_: Throwable) {
            proxyController = null
            false
        }
    }

    fun stopProxyCore() {
        try { proxyController?.stopLoop() } catch (_: Throwable) {}
        proxyController = null
    }

    /** Stop every VPN path (v2ray, WireGuard, OpenVPN,
     *  chain proxy core) and clear chain state. */
    fun stopAll(ctx: Context) {
        try {
            val stop = Intent(ctx, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
            ctx.startService(stop)
        } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        try { WgDirect.disconnect(ctx) } catch (_: Throwable) {}
        try { OvpnEngine.disconnect() } catch (_: Throwable) {}
        try { OvpnEngine.chainProxyActive = false } catch (_: Throwable) {}
        stopProxyCore()
        setRunning(ctx, null)
    }

    fun ovpnProfile(ctx: Context, name: String): OvpnProfile? {
        return try {
            val arr = JSONArray(ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                .getString("ovpn_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == name) {
                    return OvpnProfile(
                        name = o.optString("name"),
                        hostname = o.optString("hostname"),
                        ovpn = o.optString("ovpn"),
                        username = o.optString("username"),
                        password = o.optString("password"),
                        savePassword = o.optBoolean("savePassword", false)
                    )
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
