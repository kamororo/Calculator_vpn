package com.renamer

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Real SlipNet DNS engines, using SlipNet's own
 *  compiled Go library (libslipnet-go.so, renamed
 *  from SlipNet's libgojni.so so it cannot collide
 *  with Xray's libgojni.so) through the exact
 *  gomobile bindings in mobile/ and vaydns/.
 *  The client creates a local SOCKS5 proxy; the
 *  existing Xray TUN then routes through it.
 *  Our app package is excluded from the VPN
 *  (VpnClientService.addDisallowedApplication),
 *  so the DNS tunnel traffic itself never loops
 *  into the TUN — the same reason SlipNet's own
 *  tunnel does not loop. Nothing here is a
 *  reimplementation: DNSTT, NoizDNS and VayDNS
 *  are SlipNet's own code. */
object SlipNetDns {
    private var dnstt: mobile.DnsttClient? = null
    private var vayClient: vaydns.VaydnsClient? = null
    @Volatile var port = 0
        private set

    fun isRunning(): Boolean = try {
        dnstt?.isRunning == true || vayClient?.isRunning == true
    } catch (_: Throwable) { false }

    /** Format resolvers exactly like SlipNet's
     *  DnsttBridge: DoH/DoT/TCP URLs pass through,
     *  host:port passes through, bare host gets :53. */
    fun formatResolvers(raw: String): String {
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(",") { a ->
            when {
                a.startsWith("https://") || a.startsWith("tls://") || a.startsWith("tcp://") -> a
                a.contains(":") -> a
                else -> "$a:53"
            }
        }
    }

    fun start(ctx: Context, p: SlipNetStore.Profile, listenPort: Int): Int {
        stop()
        val json = p.json
        val type = p.type
        val domain = json.optString("domain")
        val pubkey = json.optString("pubkey")
        val resolvers = formatResolvers(json.optString("resolvers"))
        if (domain.isBlank() || pubkey.isBlank()) throw IllegalArgumentException("Domain and public key are required")
        val listenAddr = "127.0.0.1:$listenPort"
        val methodSsh = json.optString("connectionMethod") == "SSH"
        // SOCKS credentials are the credentials of the
        // local SOCKS proxy SlipNet creates, if set.
        val socksUser = json.optString("socksUser")
        val socksPass = json.optString("socksPass")
        if (type.startsWith("vaydns")) {
            val c = vaydns.Vaydns.newClient(resolvers, domain, pubkey, listenAddr)
            c.setDnsttCompat(json.optBoolean("vaydnsDnsttCompat", false))
            val payload = json.optInt("dnsQuerySize", 0)
            if (payload > 0) c.setMaxPayload(payload.toLong())
            c.setRecordType(json.optString("recordType", "txt").ifBlank { "txt" })
            c.setMaxQnameLen(json.optInt("maxQname", 101).toLong())
            val rps = json.optString("rps", "0").toDoubleOrNull() ?: 0.0
            if (rps > 0) c.setRPS(rps)
            c.setIdleTimeout(json.optInt("vaydnsIdleTimeout", 0).toLong())
            c.setKeepAlive(json.optInt("vaydnsKeepalive", 0).toLong())
            c.setUDPTimeout(json.optInt("vaydnsUdpTimeout", 0).toLong())
            c.setMaxNumLabels(json.optInt("vaydnsMaxNumLabels", 0).toLong())
            c.setClientIDSize(json.optInt("vaydnsClientIdSize", 0).toLong())
            c.setResolverMode("fanout")
            c.setRRSpreadCount(3)
            if (socksUser.isNotBlank()) c.setSocksCredentials(socksUser, socksPass)
            vayClient = c
            c.start()
            Thread.sleep(100)
            if (!c.isRunning) throw RuntimeException("VayDNS client failed to start")
        } else {
            // dnstt, dnstt_ssh, sayedns, sayedns_ssh
            val c = mobile.Mobile.newClient(resolvers, domain, pubkey, listenAddr)
            c.setAuthoritativeMode(json.optBoolean("authoritative", false))
            val payload = json.optInt("dnsQuerySize", 0)
            if (payload > 0) c.setMaxPayload(payload.toLong())
            if (type.startsWith("sayedns")) {
                c.setNoizMode(true)
                c.setDeviceManufacturer(Build.MANUFACTURER)
                if (json.optBoolean("noizdnsStealth", false)) c.setStealthMode(true)
            }
            c.setResolverMode("fanout")
            c.setRRSpreadCount(3)
            if (socksUser.isNotBlank()) c.setSocksCredentials(socksUser, socksPass)
            dnstt = c
            c.start()
            Thread.sleep(100)
            if (!c.isRunning) throw RuntimeException("DNSTT client failed to start")
        }
        port = listenPort
        return listenPort
    }

    fun stop() {
        try { dnstt?.stop() } catch (_: Throwable) {}
        try { vayClient?.stop() } catch (_: Throwable) {}
        dnstt = null
        vayClient = null
        port = 0
    }
}

/** NaiveProxy, exactly as SlipNet's NaiveBridge:
 *  the compiled libnaive.so is executed as a
 *  binary with --listen=socks:// and --proxy=https://. */
object SlipNetNaive {
    private var proc: Process? = null
    fun start(ctx: Context, p: SlipNetStore.Profile, listenPort: Int): Int {
        stop()
        val json = p.json
        val host = json.optString("domain")
        val port = json.optString("naivePort", "443").ifBlank { "443" }
        val user = json.optString("naiveUser")
        val pass = json.optString("naivePass")
        val binary = ctx.applicationInfo.nativeLibraryDir + "/libnaive.so"
        if (!java.io.File(binary).exists()) throw RuntimeException("NaiveProxy binary not found")
        val resolved = try {
            val addrs = java.net.InetAddress.getAllByName(host)
            val v4 = addrs.firstOrNull { it is java.net.Inet4Address } ?: addrs.firstOrNull()
            v4?.hostAddress ?: host
        } catch (_: Exception) { host }
        val args = mutableListOf(binary, "--listen=socks://127.0.0.1:$listenPort", "--log",
            "--proxy=https://$user:$pass@$host:$port",
            "--host-resolver-rules=MAP $host $resolved")
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        proc = pb.start()
        Thread.sleep(800)
        return listenPort
    }
    fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }
}
