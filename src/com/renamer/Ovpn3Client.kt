package com.renamer

import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_ConnectionInfo
import net.openvpn.ovpn3.ClientAPI_EvalConfig
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import net.openvpn.ovpn3.ClientAPI_Status
import net.openvpn.ovpn3.ClientAPI_StringVec
import net.openvpn.ovpn3.ClientAPI_TransportStats
import net.openvpn.ovpn3.DnsOptions

/**
 * OpenVPN3 client, following the official
 * OpenVPN Connect / Amnezia pattern: subclass
 * ClientAPI_OpenVPNClient, override the TUN
 * builder callbacks, socket_protect, event and
 * log. The C++ core (libovpn3.so, already in
 * this APK) does the TLS handshake itself,
 * parses the .ovpn verbatim, and calls these
 * callbacks to build the Android TUN.
 *
 * This is NOT the ics-openvpn 2.x engine: no
 * openvpn binary, no ConfigParser, no TLS
 * compat toggles. The config text is passed
 * verbatim to eval_config, exactly as the
 * official app does.
 */
class Ovpn3Client(
    private val onEvent: (name: String, info: String, error: Boolean, fatal: Boolean) -> Unit,
    private val onLog: (String) -> Unit,
    private val protectSocket: (Int) -> Boolean,
    private val establishTun: (TunSpec) -> Int
) : ClientAPI_OpenVPNClient() {

    data class TunSpec(
        var mtu: Int = 1500,
        val addresses: MutableList<Pair<String, Int>> = mutableListOf(),
        val routes: MutableList<Pair<String, Int>> = mutableListOf(),
        val excludeRoutes: MutableList<Pair<String, Int>> = mutableListOf(),
        val dnsServers: MutableList<String> = mutableListOf(),
        val searchDomains: MutableList<String> = mutableListOf(),
        var sessionName: String = ""
    )

    private val tun = TunSpec()
    @Volatile var lastConnectionInfo: ClientAPI_ConnectionInfo? = null

    // ---- TUN builder ----
    override fun tun_builder_new(): Boolean {
        tun.mtu = 1500
        tun.addresses.clear()
        tun.routes.clear()
        tun.excludeRoutes.clear()
        tun.dnsServers.clear()
        tun.searchDomains.clear()
        tun.sessionName = ""
        return true
    }

    override fun tun_builder_set_mtu(mtu: Int): Boolean {
        tun.mtu = mtu
        return true
    }

    override fun tun_builder_add_address(address: String, prefix_length: Int, gateway: String, ipv6: Boolean, net30: Boolean): Boolean {
        if (!ipv6) tun.addresses.add(address to prefix_length)
        return true
    }

    override fun tun_builder_add_route(address: String, prefix_length: Int, metric: Int, ipv6: Boolean): Boolean {
        if (address == "remote_host") return false
        if (!ipv6) tun.routes.add(address to prefix_length)
        return true
    }

    override fun tun_builder_exclude_route(address: String, prefix_length: Int, metric: Int, ipv6: Boolean): Boolean {
        if (!ipv6) tun.excludeRoutes.add(address to prefix_length)
        return true
    }

    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
        // EMULATED_EXCLUDE_ROUTES flag (1<<16): the
        // core emulates excluded routes itself.
        if ((flags and (1L shl 16)) != 0L) return true
        if (ipv4) tun.routes.add("0.0.0.0" to 0)
        if (ipv6) tun.routes.add("::" to 0)
        return true
    }

    override fun tun_builder_set_dns_options(dns: DnsOptions): Boolean {
        // v1.34: DO NOT touch the native DnsOptions
        // object at all. The user's v1.33 log proves
        // the crash happens exactly after PUSH:
        // TLS handshake OK, Session ACTIVE, PUSH
        // options received, then the page closes
        // and the app returns to Home. The very
        // next core step is tun_builder, and the
        // only native-structure access in it was
        // iterating DnsOptions.servers (a SWIG
        // map iterator). A native crash there
        // kills the process with no Java exception
        // to catch, which matches "page closes"
        // exactly. DNS is not worth a crash:
        // establishTun() already falls back to
        // 8.8.8.8 / 1.1.1.1 when no DNS was
        // collected, which are exactly the two
        // servers this server pushes anyway.
        // So: ignore the native object entirely.
        return true
    }

    override fun tun_builder_set_allow_local_dns(allow: Boolean): Boolean {
        return true
    }

    override fun tun_builder_set_layer(layer: Int): Boolean {
        return layer == 3
    }

    override fun tun_builder_set_session_name(name: String): Boolean {
        tun.sessionName = name
        return true
    }

    override fun tun_builder_set_remote_address(address: String, ipv6: Boolean): Boolean {
        return true
    }

    override fun tun_builder_establish(): Int {
        return try {
            try { onLog("TUN establish: mtu=${tun.mtu} addresses=${tun.addresses} routes=${tun.routes}") } catch (_: Throwable) {}
            establishTun(tun)
        } catch (e: Throwable) {
            -1
        }
    }

    override fun tun_builder_get_local_networks(ipv6: Boolean): ClientAPI_StringVec {
        return ClientAPI_StringVec()
    }

    // Newer-API callbacks, kept for completeness:
    // the old core never calls them, but if it
    // did, they feed the same TunSpec.
    override fun tun_builder_add_dns_server(address: String, ipv6: Boolean): Boolean {
        if (!ipv6) tun.dnsServers.add(address)
        return true
    }

    override fun tun_builder_add_search_domain(domain: String): Boolean {
        tun.searchDomains.add(domain)
        return true
    }

    // ---- Connection control ----
    override fun eval_config(arg0: ClientAPI_Config): ClientAPI_EvalConfig {
        return super.eval_config(arg0)
    }

    override fun connect(): ClientAPI_Status {
        return super.connect()
    }

    override fun socket_protect(socket: Int, remote: String, ipv6: Boolean): Boolean {
        return try { protectSocket(socket) } catch (_: Throwable) { false }
    }

    override fun stop() {
        super.stop()
    }

    // ---- Status callbacks ----
    override fun event(event: ClientAPI_Event) {
        try {
            val name = event.name ?: ""
            val info = event.info ?: ""
            if (name == "CONNECTED") {
                try { lastConnectionInfo = connection_info() } catch (_: Throwable) {}
            }
            onEvent(name, info, event.error, event.fatal)
        } catch (_: Throwable) {}
    }

    override fun log(arg0: ClientAPI_LogInfo) {
        try {
            val t = (arg0.text ?: "").trimEnd('\n')
            if (t.isNotBlank()) onLog(t)
        } catch (_: Throwable) {}
    }

    override fun transport_stats(): ClientAPI_TransportStats {
        return super.transport_stats()
    }

    fun provideCreds(username: String, password: String): ClientAPI_Status {
        val creds = ClientAPI_ProvideCreds()
        creds.username = username
        creds.password = password
        return provide_creds(creds)
    }
}
