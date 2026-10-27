package com.renamer

import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject

/** VpnService that runs the Xray core in TUN mode, like
 *  v2rayNG's VPN mode: the device's traffic goes through
 *  the TUN interface into the core, and out the selected
 *  server.
 *
 *  Fixes for the previous "connecting, then instantly
 *  disconnected" failure:
 *  - The app's own package is excluded from the VPN
 *    (addDisallowedApplication), like v2rayNG does. Without
 *    this, the core's own connection to the proxy server
 *    is routed back into the TUN interface and loops.
 *  - The TUN interface is established on the service's
 *    main thread (onStartCommand), not in a worker thread.
 *  - Any start failure is stored in lastError instead of
 *    vanishing, so the activity can show the real reason.
 */
class VpnClientService : VpnService(), libv2ray.CoreCallbackHandler {
    companion object {
        @Volatile var instance: VpnClientService? = null
        @Volatile var isRunning = false
        @Volatile var currentName: String? = null
        @Volatile var lastError: String? = null
        /** The emergency config currently connected, in
         *  memory only (never saved to prefs or the server
         *  list). Used only to measure its ping again. */
        @Volatile var emergencyConfig: String? = null
        /** The link currently connected (for the
         *  through-tunnel flag measurement only). */
        @Volatile var currentLink: String? = null
        /** Which section owns the running session:
         *  "v2ray", "emergency", "hydra", "chain" or
         *  "smart". This is what stops sections from
         *  lighting each other up: a section's button
         *  is "on" ONLY for its own tag. Set from the
         *  start intent's "session_tag" extra (older
         *  paths fall back to the emergency flag). */
        @Volatile var sessionTag: String? = null
        /** Plain-language section name for messages
         *  like "an emergency connection is active". */
        fun sectionLabel(ctx: android.content.Context, tag: String?): String = when (tag) {
            "emergency" -> AppLanguage.t(ctx, "اضطراری", "emergency")
            "hydra" -> AppLanguage.t(ctx, "هیدرا", "Hydra")
            "chain" -> AppLanguage.t(ctx, "زنجیره دوتایی", "Double chain")
            "smart" -> AppLanguage.t(ctx, "اتصال هوشمند", "Smart connect")
            else -> AppLanguage.t(ctx, "v2ray", "v2ray")
        }
    }

    private var controller: CoreController? = null
    private var tunPfd: ParcelFileDescriptor? = null

    // --- VPN status notification (see VpnNotification) ---
    // Refresh runs on a background HandlerThread, never on
    // the UI thread, and only while the VPN is running.
    private var notifThread: HandlerThread? = null
    private var notifHandler: Handler? = null
    private var notifKind = "v2ray"
    private var notifName: String? = null
    private var notifHost: String? = null
    private var notifStarted = false

    /** Host of the proxy outbound, best-effort, from the
     *  Xray JSON the service actually receives (the
     *  original config URI is NOT passed to the service,
     *  so parseServer cannot be used here). Covers the
     *  usual shapes: settings.address (vless/trojan/ss
     *  style), settings.vnext[0].address (vmess), and
     *  settings.servers[0].address (shadowsocks). */
    private fun hostFromXrayJson(config: String): String? {
        return try {
            val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                if (ob.optString("tag") != "proxy") continue
                val s = ob.optJSONObject("settings") ?: continue
                val direct = s.optString("address", "")
                if (direct.isNotBlank()) return direct
                val vnext = s.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val a = vnext.optJSONObject(0)?.optString("address", "") ?: ""
                    if (a.isNotBlank()) return a
                }
                val servers = s.optJSONArray("servers")
                if (servers != null && servers.length() > 0) {
                    val a = servers.optJSONObject(0)?.optString("address", "") ?: ""
                    if (a.isNotBlank()) return a
                }
                return null
            }
            null
        } catch (_: Throwable) { null }
    }

    /** Starts the ongoing status notification and the
     *  3-second refresh loop. The service was NOT
     *  foreground before: it becomes foreground here,
     *  with this notification, which also helps it
     *  survive. If startForeground throws (e.g. the
     *  manifest foregroundServiceType entry is missing),
     *  the exception is swallowed and the notification
     *  is posted normally instead — a missing manifest
     *  entry must never break connecting. */
    private fun startNotif(kind: String, name: String?, host: String?) {
        stopNotif()
        notifKind = kind
        notifName = name
        notifHost = host
        notifStarted = true
        try {
            val notif = VpnNotification.buildConnected(this, kind, name ?: "", host, currentLink)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        VpnNotification.NOTIF_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(VpnNotification.NOTIF_ID, notif)
                }
            } catch (_: Throwable) {
                // Foreground start failed (e.g. no
                // foregroundServiceType in the manifest):
                // keep the notification, drop foreground.
                VpnNotification.showConnected(this, kind, name ?: "", host, currentLink)
            }
        } catch (_: Throwable) {}
        val thread = HandlerThread("vpn-notif").also { it.start() }
        notifThread = thread
        val handler = Handler(thread.looper)
        notifHandler = handler
        val loop = object : Runnable {
            override fun run() {
                if (!isRunning || !notifStarted) return
                try {
                    VpnNotification.showConnected(
                        this@VpnClientService, notifKind,
                        notifName ?: "", notifHost, currentLink
                    )
                } catch (_: Throwable) {}
                try { handler.postDelayed(this, 3000) } catch (_: Throwable) {}
            }
        }
        handler.postDelayed(loop, 3000)
    }

    /** Stops the refresh loop and removes the ongoing
     *  notification. Idempotent and safe to call from
     *  any thread (STOP branch, failure branches,
     *  onDestroy). */
    private fun stopNotif() {
        notifStarted = false
        try { notifHandler?.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        notifHandler = null
        try { notifThread?.quitSafely() } catch (_: Throwable) {}
        notifThread = null
        try { stopForeground(true) } catch (_: Throwable) {}
        VpnNotification.showDisconnected(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit STOP: the activity sends this when the user
        // taps disconnect. Previously disconnect relied only on
        // stopService(), and if the service's onDestroy was
        // delayed the UI kept showing "connected" and the tap
        // looked like it did nothing. Now the service itself
        // stops the core, closes the TUN and clears the flag,
        // then stops itself.
        if (intent?.action == "STOP") {
            // Clear the flags at once, on the main
            // thread, but stop the core in the
            // background: stopLoop can block (notably
            // for WireGuard-style cores), and blocking
            // the service's main thread hangs the whole
            // app, which is what the user reported.
            isRunning = false
            currentName = null
            emergencyConfig = null
            currentLink = null
            sessionTag = null
            try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
            // Hydra's own per-attempt teardown must
            // NOT clear hydra_active (see
            // HydraEngine.stopVpn): it would end the
            // probe after the first failed shape.
            if (intent?.getBooleanExtra("hydra_teardown", false) != true) {
                try { getSharedPreferences("renamer_hydra", MODE_PRIVATE).edit().putBoolean("hydra_active", false).apply() } catch (_: Exception) {}
            }
            val c = controller
            val pfd = tunPfd
            controller = null
            tunPfd = null
            stopNotif()
            Thread {
                try { c?.stopLoop() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
            }.start()
            stopSelf()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra("config")
        val name = intent?.getStringExtra("name")
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastError = null
        // Mark running immediately, on the main thread, so the
        // activity's disconnect check sees the true state even
        // before the core thread finishes starting.
        isRunning = true
        currentName = name
        currentLink = intent?.getStringExtra("link")
        sessionTag = intent?.getStringExtra("session_tag")
            ?: if (emergencyConfig != null) "emergency" else "v2ray"
        // Through-tunnel flag measurement: once the
        // core is up, query the connection-info URL
        // via the local socks proxy and store the
        // egress country for this config (see GeoFlag).
        Thread {
            try {
                Thread.sleep(4000)
                if (isRunning && currentLink != null) {
                    GeoFlag.measureEgress(applicationContext, currentLink)
                }
            } catch (_: Exception) {}
        }.start()
        try { WidgetRefresh.refreshAll(applicationContext) } catch (_: Exception) {}
        // Ongoing status notification (with speed,
        // country and actions). Kind comes from the
        // emergency flag the activity set before
        // starting us; the name extra for emergency is
        // already the generic "اتصال اضطراری", never
        // the real config name, so nothing private leaks
        // into the notification.
        startNotif(
            if (emergencyConfig != null) "emergency" else "v2ray",
            name, hostFromXrayJson(config)
        )
        // Establish the TUN interface here, on the main thread.
        // Per-app settings (from the drawer) are applied here:
        // bypass = selected apps are excluded, only = only the
        // selected apps go through the VPN, disabled = all apps
        // go through it (the app itself is always excluded).
        val prefs = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
        val mtu = (prefs.getString("vpn_mtu", null) ?: prefs.getString("mtu", "1500") ?: "1500").toIntOrNull() ?: 1500
        val dns = (prefs.getString("dns", "1.1.1.1,8.8.8.8") ?: "1.1.1.1,8.8.8.8")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        val perAppMode = prefs.getString("per_app_mode", "bypass") ?: "bypass"
        val perAppSet = prefs.getString("per_app_set", "")?.lines()
            ?.filter { it.isNotBlank() } ?: emptyList()
        val pfd = try {
            val builder = Builder()
                .setSession("calculator")
                .addAddress("10.0.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .setMtu(mtu)
            for (d in dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }) builder.addDnsServer(d)
            when (perAppMode) {
                "only" -> {
                    for (pkg in perAppSet) {
                        if (pkg != packageName) {
                            try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                        }
                    }
                }
                "bypass" -> {
                    builder.addDisallowedApplication(packageName)
                    for (pkg in perAppSet) {
                        if (pkg != packageName) {
                            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                        }
                    }
                }
                else -> builder.addDisallowedApplication(packageName)
            }
            builder.establish()
        } catch (t: Throwable) {
            lastError = "establish failed: ${t.message ?: t.javaClass.simpleName}"
            null
        }
        if (pfd == null) {
            if (lastError == null) lastError = "establish returned null (VPN permission?)"
            isRunning = false
            stopNotif()
            stopSelf()
            return START_NOT_STICKY
        }
        tunPfd = pfd
        Thread {
            try {
                if (!CoreInit.init(applicationContext)) {
                    lastError = "core init failed: ${CoreInit.lastError}"
                    isRunning = false
                    try { pfd.close() } catch (_: Throwable) {}
                    tunPfd = null
                    stopNotif()
                    stopSelf()
                    return@Thread
                }
                try { controller?.stopLoop() } catch (_: Throwable) {}
                val c = Libv2ray.newCoreController(this)
                controller = c
                c.startLoop(config, pfd.fd)
                // isRunning/currentName were already set on the
                // main thread before this thread started.
            } catch (t: Throwable) {
                lastError = "core failed: ${t.message ?: t.javaClass.simpleName}"
                isRunning = false
                try { pfd.close() } catch (_: Throwable) {}
                tunPfd = null
                stopNotif()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        stopNotif()
        isRunning = false
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(applicationContext) } catch (_: Exception) {}
        currentName = null
        emergencyConfig = null
        sessionTag = null
        val c = controller
        val pfd = tunPfd
        controller = null
        tunPfd = null
        Thread {
            try { c?.stopLoop() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }.start()
    }

    override fun onRevoke() {
        super.onRevoke()
        lastError = "VPN permission revoked"
        stopSelf()
    }

    override fun onEmitStatus(code: Long, message: String?): Long = 0
    override fun shutdown(): Long = 0
    override fun startup(): Long = 0
}
