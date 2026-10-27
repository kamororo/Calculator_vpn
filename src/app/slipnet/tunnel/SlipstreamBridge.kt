package app.slipnet.tunnel

import android.net.VpnService
import com.renamer.VpnClientService

/** Same class name, same native method names and
 *  signatures as SlipNet's own SlipstreamBridge,
 *  so SlipNet's libslipstream.so binds to it
 *  without any change to the native code.
 *  protectSocket is called by the Rust client
 *  when it opens a socket: the running Xray
 *  VpnClientService protects it, and our app
 *  package is already excluded from the VPN, so
 *  the QUIC traffic cannot loop into the TUN. */
object SlipstreamBridge {
    @Volatile var vpnService: VpnService? = null
    private var loaded = false
    private var port = 1080

    init {
        try {
            System.loadLibrary("slipstream")
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        val svc = vpnService ?: VpnClientService.instance
        return try {
            if (svc == null) true else svc.protect(fd)
        } catch (_: Throwable) {
            true
        }
    }

    fun startClient(domain: String, resolvers: List<String>, listenPort: Int, listenHost: String = "127.0.0.1", congestionControl: String = "bbr", keepAliveInterval: Int = 5000, gsoEnabled: Boolean = false): Int {
        if (!loaded) return -99
        val hosts = resolvers.map { it.substringBefore(":") }.toTypedArray()
        val ports = resolvers.map { it.substringAfter(":", "53").toIntOrNull() ?: 53 }.toIntArray()
        val auth = BooleanArray(resolvers.size) { false }
        port = listenPort
        return nativeStartSlipstreamClient(domain, hosts, ports, auth, listenPort, listenHost, congestionControl, keepAliveInterval, gsoEnabled, false, false, 1000, 30000)
    }

    fun stopClient() {
        try { nativeStopSlipstreamClient() } catch (_: Throwable) {}
    }

    fun isClientRunning(): Boolean = try { nativeIsClientRunning() } catch (_: Throwable) { false }
    fun lastError(): String? = try { nativeGetLastError() } catch (_: Throwable) { null }
    fun getPort(): Int = port

    private external fun nativeStartSlipstreamClient(
        domain: String,
        resolverHosts: Array<String>,
        resolverPorts: IntArray,
        resolverAuthoritative: BooleanArray,
        listenPort: Int,
        listenHost: String,
        congestionControl: String,
        keepAliveInterval: Int,
        gsoEnabled: Boolean,
        debugPoll: Boolean,
        debugStreams: Boolean,
        idlePollInterval: Int,
        idleTimeoutMs: Int
    ): Int

    private external fun nativeStopSlipstreamClient()
    private external fun nativeIsClientRunning(): Boolean
    private external fun nativeIsQuicReady(): Boolean
    private external fun nativeGetLastError(): String?
}
