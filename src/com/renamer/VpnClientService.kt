package com.renamer

import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
        /** The SOCKS inbound port of the RUNNING
         *  session's own config JSON (2.53): probes
         *  must use THIS port, not the configured
         *  one — with a dynamic port the configured
         *  value is 0 and every probe fails, which
         *  falsely condemns healthy sessions. -1
         *  when no session (or port unknown). */
        @Volatile var sessionSocksPort: Int = -1
        /** Which section owns the running session:
         *  "v2ray", "emergency", "hydra", "chain" or
         *  "smart". This is what stops sections from
         *  lighting each other up: a section's button
         *  is "on" ONLY for its own tag. Set from the
         *  start intent's "session_tag" extra (older
         *  paths fall back to the emergency flag). */
        @Volatile var sessionTag: String? = null
        /** ADAPTIVE ENGINE (1.79): true only when this
         *  session was started through an engine plan.
         *  The watchdog watches ONLY such sessions, and
         *  toggle-off stops exactly these. */
        @Volatile var engineSession = false
        @Volatile var engineOrigLink: String? = null
        /** 2.12 session generation: bumped on every real start.
         *  A STOP (or destroy) belonging to an older generation
         *  must never clear a newer live session's flags. */
        @Volatile var sessionGen = 0

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

    /** Establish the TUN interface and start the
     *  core loop for [config]. Used by the normal
     *  start path AND by the engine watchdog's
     *  relaunch — the same code, so a healed session
     *  is exactly a fresh start. Returns false only
     *  when the TUN could not be established (the
     *  core thread's own async failures still stop
     *  the service themselves, exactly as before). */
    private fun coreFailed(reason: String) {
        lastError = reason
        isRunning = false
        try { tunPfd?.close() } catch (_: Throwable) {}
        tunPfd = null
        stopNotif()
        stopSelf()
    }

    private fun launchCore(
        config: String
    ): Boolean {
        // Establish the TUN interface here, on the main thread (callers
        // on the main thread for a normal start; the watchdog posts here too).
        // Per-app settings (from the drawer) are applied here:
        // bypass = selected apps are excluded, only = only the
        // selected apps go through the VPN, disabled = all apps
        // go through it (the app itself is always excluded).
        // Per-section TUN settings: v2ray keeps the shared
        // file (unchanged); emergency reads its own file
        // (migrated once from the shared file, then
        // independent). Other sections keep the shared
        // file for now.
        val tunFile = TunPrefs.fileFor(sessionTag)
        TunPrefs.migrateFileIfNeeded(this, tunFile)
        val prefs = getSharedPreferences(tunFile, MODE_PRIVATE)
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
            return false
        }
        tunPfd = pfd

        Thread {
            try {
                if (!CoreInit.init(applicationContext)) {
                    coreFailed("core init failed: ${CoreInit.lastError}")
                    return@Thread
                }
                try { controller?.stopLoop() } catch (_: Throwable) {}
                val c = Libv2ray.newCoreController(this)
                controller = c
                c.startLoop(config, pfd.fd)
                // 2.6 semantics (restored): a loop that ends on
                // its own stays silent here. Teardown happens
                // only on exception or core-init failure,
                // exactly as in 2.6, so no thread can ever
                // close another session's TUN.
            } catch (t: Throwable) {
                coreFailed("core failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }.start()
        return true
    }

    // ---------- adaptive-engine watchdog (1.79) ----------

    @Volatile private var watchdogRunning = false

    /** Watches ONLY engine sessions: every 30s it
     *  probes through the tunnel itself. Two dead
     *  probes in a row trigger one healing attempt
     *  (original first, then the parallel selector);
     *  a recovered original just resets the counters,
     *  a winning variant relaunches the core in
     *  place, and three failed healings stop the
     *  session so traffic is never stuck in a dead
     *  tunnel. Exits the moment the session ends,
     *  stops being an engine session, or the engine
     *  is switched off. Never touches a hand-started
     *  VPN or Smart's ownership: it only ever acts
     *  on a session the engine itself started. */
    private fun startEngineWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        Thread {
            var fails = 0
            var failedHeals = 0
            try {
                while (true) {
                    Thread.sleep(30000)
                    if (!isRunning || !engineSession) break
                    if (!AdaptiveEngine.isEnabled(applicationContext)) break
                    if (AdaptiveEngine.probeThroughTunnel(applicationContext)) {
                        fails = 0
                        continue
                    }
                    fails++
                    if (fails < 2) continue
                    fails = 0
                    val orig = engineOrigLink ?: break
                    when (val h = AdaptiveEngine.healSession(applicationContext, orig)) {
                        is AdaptiveEngine.HealResult.Recovered -> { failedHeals = 0 }
                        is AdaptiveEngine.HealResult.Relaunch -> {
                            failedHeals = 0
                            relaunchEngineCore(h.json)
                        }
                        is AdaptiveEngine.HealResult.Failed -> {
                            failedHeals++
                            if (failedHeals >= 3) {
                                stopEngineSessionDead()
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                watchdogRunning = false
            }
        }.start()
    }

    /** Relaunch the core with a healed config: stop
     *  the old core + TUN (same teardown as STOP,
     *  but without stopping the service), then
     *  re-establish on the main thread. If the user
     *  disconnected meanwhile, the post is a no-op. */
    private fun relaunchEngineCore(json: String) {
        Thread {
            try { controller?.stopLoop() } catch (_: Throwable) {}
            try { tunPfd?.close() } catch (_: Throwable) {}
            controller = null
            tunPfd = null
            Handler(Looper.getMainLooper()).post {
                try {
                    if (!isRunning || !engineSession) return@post
                    stopNotif()
                    if (!launchCore(json)) {
                        try {
                            AdaptiveEngine.append(applicationContext, AdaptiveEngine.S_V2RAY,
                                "موتور: نگهبان نتوانست اتصال را دوباره برقرار کند؛ اتصال قطع شد.",
                                "Engine: watchdog could not re-establish the session; session stopped.")
                        } catch (_: Exception) {}
                    } else {
                        try {
                            startNotif(if (emergencyConfig != null) "emergency" else "v2ray",
                                currentName, hostFromXrayJson(json))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun stopEngineSessionDead() {
        try {
            AdaptiveEngine.append(applicationContext, AdaptiveEngine.S_V2RAY,
                "موتور: نگهبان بعد از سه تلاش نتوانست اتصال را زنده کند؛ اتصال قطع شد.",
                "Engine: watchdog could not revive the session after three tries; session stopped.")
        } catch (_: Exception) {}
        engineSession = false
        engineOrigLink = null
        val stop = Intent(this, VpnClientService::class.java).apply { action = "STOP" }
        try { startService(stop) } catch (_: Exception) {}
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
            engineSession = false
            engineOrigLink = null
            sessionSocksPort = -1
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
            // 2.60: close the TUN fd HERE, on the
            // main thread, before stopping the
            // core in the background. The old order
            // closed it only after stopLoop
            // returned — and stopLoop can hang
            // (notably for a stuck WireGuard-style
            // core), so the service stopped while
            // the fd stayed open in its thread and
            // Android kept showing the VPN key
            // after a chain disconnect. Closing the
            // fd first is instant, and the core's
            // TUN reads then fail and end the core
            // on their own; stopLoop still runs
            // after, for a clean core shutdown.
            try { pfd?.close() } catch (_: Throwable) {}
            Thread {
                try { c?.stopLoop() } catch (_: Throwable) {}
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
        sessionGen++
        currentName = name
        currentLink = intent?.getStringExtra("link")
        sessionTag = intent?.getStringExtra("session_tag")
            ?: if (emergencyConfig != null) "emergency" else "v2ray"
        // 2.53: remember the session's REAL socks
        // port from its own config JSON, so probes
        // never guess (or probe port 0) again.
        sessionSocksPort = try {
            val inb = org.json.JSONObject(config).optJSONArray("inbounds")
            var found = -1
            if (inb != null) {
                for (i in 0 until inb.length()) {
                    val o = inb.optJSONObject(i) ?: continue
                    if (o.optString("protocol") == "socks") { found = o.optInt("port", -1); break }
                }
            }
            found
        } catch (_: Exception) { -1 }
        // ADAPTIVE ENGINE (1.79): the activity passes
        // these only for an engine-plan connect.
        engineSession = intent?.getBooleanExtra("engine_session", false) ?: false
        engineOrigLink = intent?.getStringExtra("engine_orig_link")
        if (engineSession) startEngineWatchdog()
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
            if (emergencyConfig != null) "emergency"
            else "v2ray",
            name, hostFromXrayJson(config)
        )
        launchCore(config)

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
        engineSession = false
        engineOrigLink = null
        sessionTag = null
        sessionSocksPort = -1
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
