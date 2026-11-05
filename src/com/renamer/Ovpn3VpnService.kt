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
    // it, and the connect thread's finally closes the TUN
    // only while its own generation is still current, so
    // a stale thread finishing after a quick reconnect
    // can never kill the new session's TUN. The TUN
    // itself is also tagged (tunOwnerGen) at establish.
    private val tunGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var currentConnectGen = -1
    @Volatile private var tunOwnerGen = -1

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
        val myGen = tunGen.incrementAndGet()
        currentConnectGen = myGen
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
                    OvpnEngine.chainProxyPort = null
                    // 2.40: NEVER set proxyHost/proxyPort
                    // here — the shipped libovpn3 has NO
                    // JNI implementation for those setters
                    // (on-device: "No implementation found
                    // for ... proxyHost_set"), so setting
                    // them throws instantly and kills
                    // every OVPN chain. Set the proxy
                    // through the config text instead:
                    // the core's own parser applies
                    // http-proxy natively, same core
                    // fields, no JNI call. Any proxy
                    // lines already in the profile are
                    // stripped first so the chain proxy
                    // wins (normal connects never reach
                    // this block, so user proxies there
                    // are untouched).
                    // 2.47: strip by FIRST TOKEN, not by
                    // line prefix — the old prefix strip
                    // missed proxy lines with a leading
                    // "--" (OpenVPN accepts --option in
                    // config files), so a malformed
                    // profile proxy line survived and
                    // the core's eval failed with
                    // "option 'http-proxy' must have at
                    // least 2 arguments" (on-device
                    // 2.46 log). Token-prefix also
                    // catches http-proxy-option/-retry.
                    // 2.51: normalise a line the way a
                    // config parser would before testing
                    // it — trim, strip a BOM / NBSP at
                    // either end, strip ALL leading
                    // dashes (not just one "--"). The
                    // 2.47 test missed every ghost form
                    // outside "--x", and the same
                    // http-proxy error kept returning
                    // on 2.49, so the test itself was
                    // still too narrow.
                    fun normLine(line: String): String {
                        var t = line.trim().trim('\uFEFF', '\u00A0')
                        while (t.startsWith("-")) t = t.substring(1).trimStart()
                        return t
                    }
                    fun isProxyLine(line: String): Boolean {
                        val tok = normLine(line).split(Regex("\\s+")).firstOrNull() ?: ""
                        return tok.startsWith("http-proxy") || tok.startsWith("socks-proxy")
                    }
                    fun proxyArgCount(line: String): Int? {
                        val parts = normLine(line).split(Regex("\\s+"))
                        return if (parts.firstOrNull() == "http-proxy") parts.size - 1 else null
                    }
                    val keptProxy = cfg.content.lines().filterNot { isProxyLine(it) }
                    // 2.56-queue: build the proxy line as ONE string.
// The old form (keptProxy + "http-proxy 127.0.0.1 " + port)
// appended the Int as a SEPARATE list element, so the
// port landed on its own line, the verify loop deleted
// the 1-arg proxy line, and eval failed on bare "10810"
// (user's 17:36/17:51 test logs). A string template
// keeps host and port on the same line.
                    var finalText = (keptProxy + "http-proxy 127.0.0.1 $port").joinToString("\n")
                    // 2.51: verification loop — GUARANTEE
                    // no malformed http-proxy line reaches
                    // eval, whatever form the profile
                    // used. The appended line has exactly
                    // 2 args, so it can never be dropped
                    // here; each pass drops at least one
                    // bad line, so 5 passes always end.
                    var guard = 0
                    while (guard++ < 5) {
                        val bad = finalText.lines().firstOrNull { line ->
                            val n = proxyArgCount(line)
                            n != null && n != 2
                        } ?: break
                        finalText = finalText.lines().filterNot { it == bad }.joinToString("\n")
                    }
                    cfg.content = finalText
                    // Diagnostic only: how many http-proxy
                    // lines the final text holds, and
                    // each one's argument count — never
                    // any address or value. Also stored
                    // for the central log (counts only),
                    // so the next central log is decisive
                    // without the section log.
                    val proxyDiag = cfg.content.lines().mapNotNull { proxyArgCount(it) }
                    OvpnEngine.lastChainProxyDiag = "${proxyDiag.size}|${proxyDiag.joinToString(",")}"
                    OvpnEngine.addLog("زنجیره: پروکسی داخلی از طریق تنظیمات اعمال شد (خط‌های http-proxy نهایی: ${proxyDiag.size}، آرگومان‌ها: ${proxyDiag.joinToString(",")}).")
                    // 2.37: the loopback proxy is HTTP,
                    // so it carries TCP only. A UDP
                    // profile chained as-is would never
                    // hide inside hop1 — force TCP.
                    val forced = AdaptiveEngine.ovpnForceTcp(cfg.content)
                    if (forced != null) {
                        cfg.content = forced
                        OvpnEngine.addLog("زنجیره: پروفایل UDP به TCP تبدیل شد (پروکسی HTTP فقط TCP می‌برد).")
                    }
                }
                val eval = c.eval_config(cfg)
                if (eval.error) {
                    OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (eval.message ?: "config error")
                    OvpnEngine.isConnecting = false
                    OvpnEngine.isConnected = false
                    // survive an eval failure into the
                    // next attempt.
                    OvpnEngine.chainProxyActive = false
                    try {
                                            } catch (_: Exception) {}
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
                // close only when BOTH tokens still say
                // this session owns the TUN: this thread
                // is still the current connect, and the
                // TUN was tagged with this generation.
                // A stale thread finishing after a quick
                // reconnect therefore cannot kill the new
                // session's TUN.
                try {
                    if (currentConnectGen == myGen && tunOwnerGen == myGen) closeTun()
                } catch (_: Throwable) {}
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
            if (pfd == null) {
                tunOwnerGen = -1
                return -1
            }
            tunOwnerGen = currentConnectGen
            tunFd = pfd
            try {
                            } catch (_: Exception) {}
            return pfd.detachFd()
        } catch (t: Throwable) {
            try { OvpnEngine.addLog("tun establish failed: " + (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
            return -1
        }
    }

    private fun closeTun() {
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        tunOwnerGen = -1
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
