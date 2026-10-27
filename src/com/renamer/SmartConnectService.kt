package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Smart Connect: watches the foreground app and connects
 * v2ray automatically when a user-selected app opens.
 *
 * Behaviour (see SmartConnectActivity for the settings):
 *  - Only runs while "smart_enabled" is true in prefs
 *    "renamer_smart" and at least one app is selected
 *    ("smart_apps", newline-joined); otherwise it stops
 *    itself.
 *  - Polls every 5 seconds with UsageStatsManager
 *    (INTERVAL_DAILY over the last 10 seconds) for the
 *    current foreground package. Usage Access must be
 *    granted by the user, otherwise the query returns
 *    nothing and no connect is ever triggered.
 *  - Fast path (preferred): connect to the last
 *    connected config — prefs "renamer_vpn" key
 *    "selected", which is exactly the key VpnActivity
 *    saves on every selection change (saveServers()) and
 *    on best-ping connect. No ping test, so this is fast.
 *    Started through the same path VpnActivity uses:
 *    VpnService.prepare (null = already authorised, so a
 *    background start works) + startService on
 *    VpnClientService with the JSON from
 *    VpnConfigBuilder.build + the display name. If VPN
 *    permission has never been granted, prepare returns
 *    an Intent that needs UI, so a background service
 *    cannot start the VPN — in that case nothing happens
 *    (the user must first connect once from VpnActivity).
 *  - Fallback: if the fast-path config does not result
 *    in a running VPN within 8 seconds (VpnClientService
 *    .isRunning still false), or it cannot even be built,
 *    ping all non-group servers exactly like VpnActivity's
 *    best-ping mode (XraySpeedtest.measureOneWithError,
 *    16 parallel, v2rayNG method) and connect to the
 *    lowest-ping healthy one. If none is healthy, do
 *    nothing and wait for the next trigger.
 *  - Retry guard: a failed attempt for one foreground app
 *    is only retried when the foreground app changes or
 *    60 seconds have passed — never an aggressive loop.
 *  - Manual disconnect: if prefs key
 *    "smart_manual_stop_until" (a timestamp in millis)
 *    is in the future, connecting is skipped until it
 *    passes. This service never sets that flag; it only
 *    reads it (checked in both "renamer_smart" and
 *    "renamer_vpn", the larger value wins). VpnActivity's
 *    disconnect does not set it in this slice either, so
 *    today the flag is only honoured if some other code
 *    sets it — see the report.
 *
 * Lightweight by design: the 5-second poll is a single
 * UsageStats query (cheap), no wake lock is held except
 * during an actual connect attempt (the attempt runs on
 * a worker thread and returns), and the service holds no
 * other resources.
 *
 * NOT wired by this slice: the manifest entries (service
 * + PACKAGE_USAGE_STATS permission) and the menu entry
 * that opens SmartConnectActivity are the parent's job.
 */
class SmartConnectService : Service() {
    companion object {
        const val CHANNEL_ID = "smart"
        const val NOTIFICATION_ID = 2001
        const val POLL_MS = 5000L
        const val FAST_PATH_WAIT_MS = 8000L
        // Within one continuous foreground session,
        // a failed attempt paces the next try by 15s
        // (was 60s). Re-entering the app (see
        // pollOnce) resets this entirely, so a
        // failure never blocks a fresh open.
        const val RETRY_MS = 15000L
        // Minimise grace: leaving a selected app only
        // counts after MINIMIZE_GRACE_MS away. A quick
        // hop to Home/recents and back never cuts.
        const val MINIMIZE_GRACE_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPkg: String? = null
    // Minimise tracking: when a selected app leaves
    // the foreground we remember when it left
    // (persisted smart_left_at, so a service restart
    // does not lose it). Only if it stays away for
    // more than MINIMIZE_GRACE_MS do we disconnect:
    // a quick minimise-and-return never cuts, but
    // the VPN only lives while the user is inside a
    // selected app. The old smart_last_selected key
    // is cleared and unused.
    private var lastAttemptPkg: String? = null
    private var lastAttemptTime = 0L
    // Last foreground package seen (non-null only):
    // detects a RE-ENTRY into a selected app, so a
    // fresh open resets the failed-attempt markers
    // instead of being blocked by them.
    private var lastSeenFg: String? = null
    // Last selected app seen in the foreground:
    // the app to auto-kill (if the toggle is on)
    // when the user leaves it for good.
    private var lastSelectedFg: String? = null
    // Auto-kill must fire once per leave, not every
    // 5s while the user stays away.
    private var killDoneForLeave = false
    // True when the last attempt for lastAttemptPkg
    // actually connected. The 60s retry guard below
    // must ONLY pace failures: after a successful
    // connect + disconnect, reopening the app must
    // connect at once, not wait out the guard.
    private var lastAttemptOk = false
    private var attemptInFlight = false

    // Live diagnostics for the Smart Connect page:
    // what the service last saw in the foreground,
    // and what its last connect attempt concluded.
    // Written only when the message changes, so the
    // 5-second poll does not churn prefs. Without
    // this, a dead service or a lost usage-access
    // permission looks identical to "working" from
    // the outside — which is exactly how smart
    // connect silently "stopped working" before.
    private var lastDiag = ""
    private fun diag(msg: String) {
        if (msg == lastDiag) return
        lastDiag = msg
        try {
            smartPrefs().edit()
                .putString("diag_attempt", msg)
                .putLong("diag_attempt_at", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    private val poller = object : Runnable {
        override fun run() {
            try { pollOnce() } catch (_: Throwable) {}
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun applySpeedtestPrefs() {
        try {
            val sp = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            XraySpeedtest.testUrlOverride = sp.getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            XraySpeedtest.concurrencyOverride = sp.getString("delay_test_concurrency", null)?.toIntOrNull()
        } catch (_: Exception) {}
    }
    override fun onCreate() {
        super.onCreate()
        applySpeedtestPrefs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Smart Connect", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Connect")
                .setContentText("Watching selected apps")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Smart Connect")
                .setContentText("Watching selected apps")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("renamer_smart", MODE_PRIVATE)
        val enabled = prefs.getBoolean("smart_enabled", false)
        val apps = prefs.getString("smart_apps", "")?.lines()
            ?.filter { it.isNotBlank() } ?: emptyList()
        if (!enabled || apps.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(poller)
        handler.post(poller)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        // If smart connect is still enabled, a kill
        // (system / MIUI) should not end it forever:
        // schedule a restart in 60s. onStartCommand
        // stops itself at once if it was disabled meanwhile.
        try {
            val prefs = getSharedPreferences("renamer_smart", MODE_PRIVATE)
            val enabled = prefs.getBoolean("smart_enabled", false)
            val apps = prefs.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (enabled && apps.isNotEmpty()) {
                val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val pi = android.app.PendingIntent.getForegroundService(
                    this, 9001, Intent(this, SmartConnectService::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(
                    android.app.AlarmManager.AlarmClockInfo(
                        System.currentTimeMillis() + 60000, null),
                    pi)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun smartPrefs() = getSharedPreferences("renamer_smart", MODE_PRIVATE)
    private fun vpnPrefs() = getSharedPreferences("renamer_vpn", MODE_PRIVATE)

    /** True when the currently-running VPN (if any) was
     *  started by Smart Connect itself. Only then may
     *  auto-off disconnect it: a VPN the user started
     *  by hand must NEVER be touched by smart connect. */
    private fun smartOwnsVpn(): Boolean {
        return try {
            smartPrefs().getBoolean("smart_owns_vpn", false)
        } catch (_: Exception) { false }
    }

    private fun setSmartOwnsVpn(v: Boolean) {
        try {
            smartPrefs().edit().putBoolean("smart_owns_vpn", v).apply()
        } catch (_: Exception) {}
    }

    /** Any VPN up: ours (v2ray / WG / OpenVPN) OR another
     *  app's, detected from the system's active network
     *  (TRANSPORT_VPN). Smart connect must stay quiet
     *  while any of these is up. */
    private fun anyVpnUp(): Boolean {
        if (VpnClientService.isRunning || WgDirect.isUp(this) || OvpnEngine.isConnected) return true
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { false }
    }

    private fun selectedApps(): Set<String> {
        return smartPrefs().getString("smart_apps", "")?.lines()
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    private fun manualStopActive(): Boolean {
        val now = System.currentTimeMillis()
        val a = smartPrefs().getLong("smart_manual_stop_until", 0L)
        val b = vpnPrefs().getLong("smart_manual_stop_until", 0L)
        return maxOf(a, b) > now
    }

    /** Current foreground package. UsageEvents (the last
     *  MOVE_TO_FOREGROUND event) is used first: the old
     *  queryUsageStats/max-lastTimeUsed approach kept
     *  reporting a just-closed app as foreground, because
     *  its lastTimeUsed stays the newest until another
     *  app's bucket flushes — which is exactly why
     *  auto-off never fired after closing an app.
     *  Falls back to queryUsageStats when the event
     *  window is empty. */
    private fun foregroundPackage(): String? {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 30000, now)
            var fg: String? = null
            val ev = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fg = ev.packageName
                }
            }
            if (fg != null) return fg
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 10000, now)
            return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Throwable) { return null }
    }

    // ============================================================
    // REGRESSION GUARD — DO NOT BREAK (user-confirmed
    // working 2026-10-27, v1.62; the user explicitly
    // ordered that no future update may break this
    // section again). Any change touching this service
    // must preserve ALL of these, and if a change
    // conflicts with one, the change loses:
    //  1. Opening a selected app connects: fast path
    //     (saved selected config) else best-ping in
    //     completion order under ONE 60s deadline,
    //     chains excluded, DIRECT connect — never
    //     silently apply sub prev/next chaining.
    //  2. Leaving a selected app for >5s (grace)
    //     disconnects ONLY a smart-owned VPN
    //     (smart_owns_vpn); a hand-started VPN is
    //     NEVER disturbed. Own package + SystemUI
    //     exempt; null foreground never disconnects.
    //  3. NO cooldown blocks a fresh open: re-entry
    //     into a selected app (lastSeenFg) resets the
    //     failed-attempt markers; within one session
    //     a failed attempt paces retries by 15s only.
    //  4. Manual-stop suppression (5 min) is set ONLY
    //     by a manual disconnect; saving Smart
    //     settings clears it.
    //  5. WG target connects via
    //     WgDirect.connectFirstOrActive (active
    //     tunnel, else first saved tunnel) — NEVER
    //     gate on the stale widget_state_wireguard
    //     pref, which can claim "up" while no VPN
    //     runs.
    //  6. smart_kill_on_close stays OPT-IN, default
    //     OFF (it can cut a call/download in the
    //     left app); when on, it kills once per
    //     leave, background processes only.
    //  7. Revival stays: START_STICKY, 60s alarm
    //     restart on destroy, BOOT_COMPLETED +
    //     MY_PACKAGE_REPLACED receiver, Home +
    //     Smart-page re-arm on open.
    // ============================================================
    private fun pollOnce() {
        // Re-check on every poll so disabling smart
        // connect stops the work without waiting for a
        // service restart.
        if (!smartPrefs().getBoolean("smart_enabled", false) ||
            selectedApps().isEmpty()) {
            stopSelf()
            return
        }
        val fg = foregroundPackage()
        val now0 = System.currentTimeMillis()
        if (fg != null) {
            lastForegroundPkg = fg
            try {
                smartPrefs().edit()
                    .putString("diag_fg", fg)
                    .putLong("diag_poll_at", now0)
                    .apply()
            } catch (_: Exception) {}
            // RE-ENTRY: the foreground moved INTO a
            // selected app from something else. A
            // fresh open is a fresh intent, not a
            // retry: clear the failed-attempt markers
            // so a previous failure (even seconds
            // ago) never blocks this open. This is
            // the "sometimes works, sometimes not"
            // trap, and why force-stopping the app
            // itself sometimes did NOT help: the
            // markers live in THIS service, so
            // force-stopping Telegram left them
            // untouched and the 60s guard kept
            // blocking reopens anyway.
            if (selectedApps().contains(fg)) {
                if (lastSeenFg != fg) {
                    lastAttemptPkg = null
                    lastAttemptTime = 0L
                    lastAttemptOk = false
                }
                lastSelectedFg = fg
                killDoneForLeave = false
            }
            lastSeenFg = fg
        }
        // Auto-off, ONLY for a VPN smart connect
        // itself started, with a 5-second minimise
        // grace: the VPN lives only while the user is
        // inside a selected app, but leaving it for
        // 5 seconds or less (a quick hop to Home or
        // recents and back) never cuts it. Leaving
        // for MORE than 5 seconds — minimise, Back,
        // swipe-away, another app — disconnects.
        // Two exceptions, both transient: this app
        // itself (managing settings must not kill the
        // VPN) and SystemUI (notification shade and
        // the recents screen flash through it). An
        // unknown foreground (null) never disconnects
        // and never starts the timer, so a usage-stats
        // lag cannot flap the VPN. A hand-started VPN
        // is never touched by this.
        if (fg != null && fg != packageName && fg != "com.android.systemui") {
            if (selectedApps().contains(fg)) {
                smartPrefs().edit().remove("smart_last_selected")
                    .putLong("smart_left_at", 0L).apply()
            } else {
                val leftAt = smartPrefs().getLong("smart_left_at", 0L)
                if (leftAt == 0L) {
                    smartPrefs().edit().remove("smart_last_selected")
                        .putLong("smart_left_at", now0).apply()
                } else if (now0 - leftAt > MINIMIZE_GRACE_MS) {
                    if (smartOwnsVpn()) {
                        disconnectTarget()
                        setSmartOwnsVpn(false)
                    }
                    // Optional auto-kill (user's
                    // proposal, OFF by default): the
                    // user left a selected app for
                    // good, so kill that app's
                    // background process — the next
                    // open is a cold start, the
                    // effect they got from manually
                    // force-stopping. True force-stop
                    // needs a system permission no
                    // normal app has; this is the
                    // closest Android allows. Only
                    // once per leave, and the API
                    // only kills BACKGROUND processes,
                    // so a foreground app is safe.
                    if (smartPrefs().getBoolean("smart_kill_on_close", false) && !killDoneForLeave) {
                        killDoneForLeave = true
                        val pkg = lastSelectedFg
                        if (pkg != null) {
                            try {
                                (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                                    .killBackgroundProcesses(pkg)
                            } catch (_: Exception) {}
                        }
                    }
                    smartPrefs().edit().remove("smart_last_selected")
                        .putLong("smart_left_at", 0L).apply()
                }
            }
        }
        // Never connect while ANY VPN is up — ours or
        // another app's — so a hand-started VPN is never
        // disturbed by smart connect.
        if (anyVpnUp()) {
            if (smartOwnsVpn()) diag(AppLanguage.t(this, "متصل (هوشمند)", "Connected (smart)"))
            else diag(AppLanguage.t(this, "فیلترشکن دیگری روشن است؛ اتصال هوشمند ساکت است", "Another VPN is on; smart connect stays quiet"))
            return
        }
        if (fg == null || !selectedApps().contains(fg)) return
        if (manualStopActive()) {
            diag(AppLanguage.t(this, "قطع دستی؛ تا ۵ دقیقه وصل نمی‌شود", "Manual disconnect; no reconnect for 5 minutes"))
            return
        }
        if (attemptInFlight) return
        val now = System.currentTimeMillis()
        // Failed-only retry guard: a failed attempt
        // for this app paces the next try by 60s, but
        // a previous SUCCESS never blocks a reconnect.
        if (fg == lastAttemptPkg && !lastAttemptOk && now - lastAttemptTime < RETRY_MS) return
        lastAttemptPkg = fg
        lastAttemptTime = now
        lastAttemptOk = false
        attemptInFlight = true
        Thread { runConnectAttempt() }.start()
    }

    /** Stop whichever target smart connect started.
     *  This is NOT a manual stop: it does not set the
     *  5-minute manual-stop flag, so reopening a selected
     *  app reconnects right away. */
    private fun disconnectTarget() {
        setSmartOwnsVpn(false)
        // Fresh attempt markers: the next open of a
        // selected app must connect immediately, never
        // wait out the retry guard of the old cycle.
        lastAttemptPkg = null
        lastAttemptTime = 0L
        lastAttemptOk = false
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        try {
            when (target) {
                "wireguard" -> WgDirect.disconnect(this)
                "openvpn" -> {
                    OvpnEngine.disconnect()
                    try {
                        getSharedPreferences("renamer_widget", MODE_PRIVATE)
                            .edit().putBoolean("widget_state_openvpn", false).apply()
                    } catch (_: Exception) {}
                    WidgetRefresh.refreshAll(this)
                }
                // v2ray AND emergency both run on
                // VpnClientService, so both stop here.
                else -> {
                    val stop = Intent(this, VpnClientService::class.java).apply { action = "STOP" }
                    try { startService(stop) } catch (_: Exception) {}
                    try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
                    VpnClientService.isRunning = false
                    WidgetRefresh.refreshAll(this)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun runConnectAttempt() {
        // Double-check at attempt time: never connect
        // over a VPN that is already up.
        if (anyVpnUp()) {
            attemptInFlight = false
            return
        }
        // Non-v2ray targets: WireGuard / OpenVPN, same
        // trigger (a selected app opened), same guards.
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        // Emergency target: the emergency section's
        // own engine, headless — fetch fresh, ping,
        // connect to the best, same privacy rules
        // (nothing saved, generic session name). Needs
        // one manual emergency connect first, exactly
        // like the other targets need one manual connect
        // for the VPN permission.
        if (target == "emergency") {
            try {
                if (android.net.VpnService.prepare(this) == null) {
                    if (EmergencyConnect.connect(this)) {
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        diag(AppLanguage.t(this, "متصل شد (اضطراری)", "Connected (emergency)"))
                    } else diag(AppLanguage.t(this, "اضطراری: سرور سالمی پیدا نشد", "Emergency: no healthy server found"))
                } else diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        if (target == "wireguard") {
            try {
                // Never gate on the widget pref alone:
                // after an abnormal end it can claim
                // "up" while no VPN runs, which made
                // this whole branch silently skip
                // (pollOnce already ensured no VPN is
                // up, so just connect). Falls back to
                // the first saved tunnel when no
                // active tunnel is recorded — a user
                // who never tapped "select" in the WG
                // section still expects Smart to use
                // their tunnel.
                if (!WgDirect.connectFirstOrActive(this)) {
                    if (WgDirect.activeConf(this) == null && WgDirect.firstConf(this) == null)
                        diag(AppLanguage.t(this, "WireGuard: تونلی در بخش وایرگارد نیست؛ اول یک تونل اضافه کن", "WireGuard: no tunnel in the WireGuard section; add one first"))
                    else if (android.net.VpnService.prepare(this) != null)
                        diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
                    else diag(AppLanguage.t(this, "WireGuard وصل نشد", "WireGuard did not connect"))
                } else {
                    // Wait up to 8s for the tunnel to come up.
                    val deadline = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < deadline) {
                        if (WgDirect.isUp(this)) break
                        Thread.sleep(500)
                    }
                    if (WgDirect.isUp(this)) {
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        diag(AppLanguage.t(this, "متصل شد (WireGuard)", "Connected (WireGuard)"))
                    } else diag(AppLanguage.t(this, "WireGuard وصل نشد", "WireGuard did not connect"))
                }
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        if (target == "openvpn") {
            try {
                if (!OvpnEngine.isConnected && android.net.VpnService.prepare(this) == null) {
                    val prefsO = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
                    val last = prefsO.getString("ovpn_last", null)?.takeIf { it.isNotBlank() }
                    val arr = org.json.JSONArray(prefsO.getString("ovpn_list", "[]") ?: "[]")
                    var prof: OvpnProfile? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val cand = OvpnProfile(
                            name = o.optString("name"), hostname = o.optString("hostname"),
                            ovpn = o.optString("ovpn"), username = o.optString("username"),
                            password = o.optString("password"),
                            savePassword = o.optBoolean("savePassword", false))
                        if (prof == null) prof = cand
                        if (cand.name == last) { prof = cand; break }
                    }
                    if (prof != null) {
                        OvpnEngine.pendingProfile3 = prof
                        OvpnEngine.activeName = prof.name
                        try { startService(android.content.Intent(this, Ovpn3VpnService::class.java)) } catch (_: Exception) {}
                        getSharedPreferences("renamer_widget", MODE_PRIVATE)
                            .edit().putBoolean("widget_state_openvpn", true).apply()
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        diag(AppLanguage.t(this, "متصل شد (OpenVPN)", "Connected (OpenVPN)"))
                        WidgetRefresh.refreshAll(this)
                    } else diag(AppLanguage.t(this, "OpenVPN: پروفایلی پیدا نشد", "OpenVPN: no profile found"))
                }
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        try {
            // --- Step 1: fast path, last connected config.
            val vpnPrefs = vpnPrefs()
            val servers = vpnPrefs.getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() } ?: emptyList()
            val last = vpnPrefs.getString("selected", null)?.takeIf { it.isNotBlank() }
            val fastOk = last != null && servers.contains(last) &&
                !XraySpeedtest.isGroup(last) && startVpnConfig(last)
            if (fastOk) {
                // Wait up to 8 seconds for the VPN to
                // actually come up.
                val deadline = System.currentTimeMillis() + FAST_PATH_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (VpnClientService.isRunning) {
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        diag(AppLanguage.t(this, "متصل شد: ", "Connected: ") + XraySpeedtest.displayName(last ?: ""))
                        attemptInFlight = false
                        return
                    }
                    Thread.sleep(500)
                }
            }
            // --- Step 2: fallback, best-ping connect.
            if (VpnClientService.isRunning) {
                attemptInFlight = false
                return
            }
            // Chains are multi-hop, not single
            // outbounds to delay-test (same rule as
            // the v2ray page's best-ping).
            val targets = servers.filter { !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
            if (targets.isEmpty()) {
                diag(AppLanguage.t(this, "کانفیگی برای وصل شدن نیست", "No configs to connect to"))
                attemptInFlight = false
                return
            }
            if (!CoreInit.init(applicationContext)) {
                diag(AppLanguage.t(this, "آماده‌سازی هسته ناموفق", "Core init failed"))
                attemptInFlight = false
                return
            }
            // Completion order under ONE deadline:
            // the old code awaited futures in LIST
            // order with NO timeout, so one hung
            // config stalled smart connect for
            // minutes (and attemptInFlight blocked
            // every new trigger meanwhile). Same
            // pattern as the v2ray page's best-ping.
            val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            try {
                val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, Long>>(pool)
                for (cfg in targets) {
                    completion.submit(java.util.concurrent.Callable {
                        val d = try { XraySpeedtest.measureOneWithError(cfg).first }
                            catch (_: Throwable) { -1L }
                        cfg to d
                    })
                }
                val deadline = System.currentTimeMillis() + 60_000
                var bestCfg: String? = null
                var bestDelay = Long.MAX_VALUE
                var remaining = targets.size
                while (remaining > 0) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    val f = try { completion.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) }
                        catch (_: Exception) { null } ?: break
                    remaining--
                    val (cfg, d) = try { f.get() } catch (_: Exception) { continue }
                    if (d >= 0 && d < bestDelay) { bestDelay = d; bestCfg = cfg }
                }
                val chosen = bestCfg
                if (chosen != null) {
                    // Remember it as the selected config so
                    // the next trigger takes the fast path.
                    vpnPrefs.edit().putString("selected", chosen).apply()
                    if (startVpnConfig(chosen)) {
                        val deadline = System.currentTimeMillis() + FAST_PATH_WAIT_MS
                        while (System.currentTimeMillis() < deadline) {
                            if (VpnClientService.isRunning) {
                                setSmartOwnsVpn(true)
                                lastAttemptOk = true
                                break
                            }
                            Thread.sleep(500)
                        }
                    }
                }
                if (bestCfg == null) diag(AppLanguage.t(this, "کانفیگ سالمی پیدا نشد", "No healthy config found"))
                // If none healthy: do nothing; the retry
                // guard in pollOnce() paces the next try.
            } finally {
                pool.shutdownNow()
            }
        } catch (_: Throwable) {
            // Never crash the service on a failed attempt.
        } finally {
            attemptInFlight = false
        }
    }

    /** Same connect path as VpnActivity.startVpn, minus
     *  the UI: build the Xray JSON, init the core, and
     *  start VpnClientService. Returns false when the
     *  config cannot be built, the core fails, or VPN
     *  permission was never granted (prepare() returns a
     *  non-null Intent, which only an Activity can show).
     *  The caller checks VpnClientService.isRunning to
     *  confirm the VPN actually came up. */
    private fun startVpnConfig(cfg: String): Boolean {
        return try {
            // Direct connect, exactly as the last
            // version where smart connect worked
            // without complaints (v1.53): NO silent
            // sub prev/next chaining here. Chaining
            // is an explicit user action in the
            // Chain section; smart connect applying
            // it behind the user's back turned a
            // plain connect into a multi-hop chain
            // connect that could fail where the
            // direct connect would succeed.
            val effective = cfg
            val mode = VpnConfigBuilder.preResolveMode(this)
            val resolved = if (mode == "Do not resolve") emptyMap()
                else VpnConfigBuilder.preResolveHosts(this, effective)
            val hostsArg = if (mode == "Resolve and add to DNS Hosts") resolved else emptyMap()
            val built = VpnConfigBuilder.build(this, effective, hostsArg) ?: run {
                diag(AppLanguage.t(this, "ساخت کانفیگ ناموفق", "Config build failed"))
                return false
            }
            val vpnConfig = if (mode == "Resolve only") {
                VpnConfigBuilder.applyResolvedIp(built, resolved)
            } else built
            if (!CoreInit.init(applicationContext)) {
                diag(AppLanguage.t(this, "آماده‌سازی هسته ناموفق", "Core init failed"))
                return false
            }
            // Background start only works if permission
            // was already granted once (prepare == null).
            if (VpnService.prepare(this) != null) {
                diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
                return false
            }
            val intent = Intent(this, VpnClientService::class.java).apply {
                putExtra("config", vpnConfig)
                putExtra("name", XraySpeedtest.displayName(cfg))
                putExtra("link", effective)
                putExtra("session_tag", "smart")
            }
            VpnClientService.emergencyConfig = null
            startService(intent)
            true
        } catch (_: Throwable) { false }
    }
}
