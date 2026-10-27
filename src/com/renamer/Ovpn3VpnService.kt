package com.renamer

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import net.openvpn.ovpn3.ClientAPI_Config

/**
 * VpnService that runs the OpenVPN3 core
 * in-process. It owns the TUN interface:
 * the core calls tun_builder_establish, this
 * service builds the Android TUN with
 * VpnService.Builder and returns its fd.
 * Sockets are protected with VpnService.protect
 * so transport traffic never goes through the
 * tunnel itself (the classic routing loop).
 *
 * Started by OvpnEngine after VpnService.prepare.
 * The profile is passed via OvpnEngine's static
 * pending fields, because a .ovpn can be large
 * for an Intent extra.
 *
 * v1.33 crash hardening (user reported
 * "calculator keeps stopping" on v1.32):
 * - NO startForeground() at all. A VpnService
 *   is not a plain started service; calling
 *   startForeground on it was the same class
 *   of crash as v1.24 (FGS type/timeout on
 *   Android 14+). The official WireGuard
 *   GoBackend VpnService also does not
 *   foreground itself. If the system needs
 *   a notification, VpnService provides its
 *   own VPN notification.
 * - ALL native interaction happens on the
 *   connect thread, never on the main thread.
 *   v1.32 constructed the SWIG client on the
 *   main thread in onStartCommand, so any
 *   native Error (UnsatisfiedLinkError,
 *   NoSuchMethodError, JNI director failure)
 *   killed the app process with the crash
 *   dialog. Now every native call is inside
 *   try/catch(Throwable), and a failure is
 *   reported as "وصل نشد: ..." in the UI,
 *   never as a crash.
 */
class Ovpn3VpnService : VpnService() {

    companion object {
        @Volatile var instance: Ovpn3VpnService? = null
        @Volatile var client: Ovpn3Client? = null
        @Volatile var connectThread: Thread? = null
    }

    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            System.loadLibrary("ovpn3")
        } catch (t: Throwable) {
            try { OvpnEngine.addLog("load ovpn3 failed: " + (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            if (action == "STOP") {
                stopClient()
                stopSelf()
                return START_NOT_STICKY
            }
            val profile = OvpnEngine.pendingProfile3 ?: return START_NOT_STICKY
            startClient(profile)
            return START_NOT_STICKY
        } catch (t: Throwable) {
            try {
                OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (t.message ?: t.javaClass.simpleName)
                OvpnEngine.isConnecting = false
                OvpnEngine.isConnected = false
            } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
    }

    private fun startClient(profile: OvpnProfile) {
        try { client?.stop() } catch (t: Throwable) {}
        OvpnEngine.isConnecting = true
        OvpnEngine.isConnected = false
        OvpnEngine.activeName = profile.name
        OvpnEngine.lastError = null
        OvpnEngine.lastState = "CONNECTING"
        val svc = this
        val t = Thread {
            var c: Ovpn3Client? = null
            try {
                c = Ovpn3Client(
                    onEvent = { name, info, error, fatal ->
                        OvpnEngine.onOvpn3Event(name, info, error, fatal)
                    },
                    onLog = { line -> OvpnEngine.addLog(line) },
                    protectSocket = { fd ->
                        try { svc.protect(fd) } catch (t: Throwable) { false }
                    },
                    establishTun = { spec -> establishTun(spec) }
                )
                client = c
                val cfg = ClientAPI_Config()
                cfg.content = profile.ovpn
                // Double-chain hop2: connect to the
                // server through Xray's loopback HTTP
                // proxy, so traffic passes hop1 first.
                // Consumed once; normal connects never
                // set it, so they are unaffected.
                OvpnEngine.chainProxyPort?.let { port ->
                    cfg.proxyHost = "127.0.0.1"
                    cfg.proxyPort = port.toString()
                    OvpnEngine.chainProxyPort = null
                }
                val eval = c.eval_config(cfg)
                if (eval.error) {
                    OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (eval.message ?: "config error")
                    OvpnEngine.isConnecting = false
                    OvpnEngine.isConnected = false
                    OvpnEngine.addLog("eval_config error: " + (eval.message ?: ""))
                    return@Thread
                }
                if (profile.username.isNotBlank() || profile.password.isNotBlank()) {
                    try {
                        val st = c.provideCreds(profile.username, profile.password)
                        if (st.error) {
                            OvpnEngine.addLog("provide_creds: " + (st.message ?: ""))
                        }
                    } catch (t: Throwable) {
                        OvpnEngine.addLog("provide_creds failed: " + (t.message ?: t.javaClass.simpleName))
                    }
                }
                val status = c.connect()
                if (status.error) {
                    if (OvpnEngine.isConnecting) {
                        OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (status.message ?: status.status ?: "")
                        OvpnEngine.isConnecting = false
                        OvpnEngine.isConnected = false
                    }
                }
            } catch (t: Throwable) {
                // Never let a native Error kill
                // the app: report it as a status.
                try {
                    OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (t.message ?: t.javaClass.simpleName)
                    OvpnEngine.isConnecting = false
                    OvpnEngine.isConnected = false
                    OvpnEngine.addLog("ovpn3 connect failed: " + (t.message ?: t.javaClass.simpleName))
                } catch (_: Throwable) {}
            } finally {
                try { closeTun() } catch (_: Throwable) {}
            }
        }
        connectThread = t
        t.start()
    }

    private fun establishTun(spec: Ovpn3Client.TunSpec): Int {
        try {
            closeTun()
            val b = Builder()
            b.setSession("calculator")
            // Double-chain hop2 only: our own app's
            // sockets (including Xray's loopback proxy
            // traffic) must bypass this TUN, or Xray's
            // outbound packets would loop back into
            // the tunnel they came from.
            if (OvpnEngine.chainProxyActive) {
                try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            try { b.setMtu(spec.mtu) } catch (t: Throwable) {}
            for ((addr, prefix) in spec.addresses) {
                try { b.addAddress(addr, prefix) } catch (t: Throwable) {}
            }
            for ((addr, prefix) in spec.routes) {
                try {
                    if (addr == "::") b.addRoute(addr, 0) else b.addRoute(addr, prefix)
                } catch (t: Throwable) {}
            }
            val dns = spec.dnsServers.filter { it.contains(".") }.distinct()
            for (d in dns) {
                try { b.addDnsServer(d) } catch (t: Throwable) {}
            }
            if (dns.isEmpty()) {
                try { b.addDnsServer("8.8.8.8") } catch (t: Throwable) {}
                try { b.addDnsServer("1.1.1.1") } catch (t: Throwable) {}
            }
            for (d in spec.searchDomains) {
                try { b.addSearchDomain(d) } catch (t: Throwable) {}
            }
            val pfd = b.establish()
            if (pfd == null) return -1
            tunFd = pfd
            return pfd.detachFd()
        } catch (t: Throwable) {
            try { OvpnEngine.addLog("tun establish failed: " + (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
            return -1
        }
    }

    private fun closeTun() {
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
    }

    private fun stopClient() {
        try { client?.stop() } catch (_: Throwable) {}
        client = null
        try { closeTun() } catch (_: Throwable) {}
        OvpnEngine.isConnected = false
        OvpnEngine.isConnecting = false
        OvpnEngine.activeName = null
    }

    override fun onRevoke() {
        try { stopClient() } catch (_: Throwable) {}
        super.onRevoke()
    }

    override fun onDestroy() {
        try { stopClient() } catch (_: Throwable) {}
        if (instance === this) instance = null
        super.onDestroy()
    }
}
