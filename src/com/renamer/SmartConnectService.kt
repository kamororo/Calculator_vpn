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
        // Persistent absence: a real leave already
        // stamped, followed by this much longer of
        // null/SystemUI foreground (lock, recents
        // left open, idle), also cuts — a transient
        // flash is far shorter, so it never does.
        const val PERSISTENT_ABSENCE_MS = 25000L
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
    // 2.55: the app label snapshotted at attempt time.
    // The outcome line must name the app the attempt
    // was FOR, even if a later poll cycle already
    // cleared lastAttemptPkg (which used to print "؟"
    // for the first smart connect of a session).
    private var lastAttemptLabel: String? = null
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
    // 2.62: repeats left for the leave-kill: an app
    // that restarts its own background process gets
    // killed again on the next few polls, so one
    // restart does not undo the kill. Cleared on
    // re-entry, so an app the user is back inside
    // is never repeat-killed.
    private var killRepeatLeft = 0
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

    /** Name of the VPN Smart currently owns, for
     *  the status line: v2ray/emergency/hydra use
     *  the session name, WireGuard the active
     *  tunnel, OpenVPN the active profile. */
    private fun ownedVpnLabel(): String? {
        return try {
            when (smartPrefs().getString("smart_target", "v2ray")) {
                "wireguard", "amnezia" -> WgDirect.activeConf(this)?.first
                "openvpn" -> OvpnEngine.activeName?.takeIf { it.isNotBlank() }
                else -> VpnClientService.currentName?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    /** Update the foreground notification's text
     *  (same id/channel as onCreate), so the shade
     *  shows which config Smart connected to — not
     *  just "watching". Only called on connect /
     *  disconnect transitions, never on every poll. */
    private fun refreshNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Smart Connect")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Smart Connect")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            }
            nm.notify(NOTIFICATION_ID, n)
        } catch (_: Exception) {}
    }

    /** Any VPN up: ours (v2ray / WG / OpenVPN) OR another
     *  app's, detected from the system's active network
     *  (TRANSPORT_VPN). Smart connect must stay quiet
     *  while any of these is up. */
    private fun anyVpnUp(): Boolean {
        // Never gate on the stale widget pref (guard
        // item 5): ground truth is GoBackend's live
        // tunnels, and a stale flag is repaired here.
        try { WgDirect.clearStaleFlag(this) } catch (_: Exception) {}
        if (VpnClientService.isRunning || WgDirect.isLive(this) || OvpnEngine.isConnected) return true
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
    //     NEVER disturbed. Own package exempt;
    //     SystemUI/null never START the timer and
    //     never cut on a transient flash — BUT a
    //     real leave followed by 30s of persistent
    //     SystemUI/null absence (lock, recents left
    //     open, idle) DOES cut (amended 2026-10-28
    //     to fix missed disconnects; the transient
    //     protection is fully kept).
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
    //     leave plus a few repeats on the next
    //     polls, background processes only.
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
                killRepeatLeft = 0
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
        // unknown foreground (null) never starts the
        // timer, so a usage-stats lag cannot flap the
        // VPN — but a leave already stamped, followed
        // by 30s of persistent null/SystemUI absence
        // (lock, recents left open, idle), DOES cut:
        // that is a genuine absence, not a blip (see
        // expireLeave). A hand-started VPN is never
        // touched by this.
        if (fg == null || fg == "com.android.systemui") {
            // Persistent absence (see the comment
            // above): a stamped leave plus 30s of
            // null/SystemUI with no selected app
            // seen since. Transient flashes never
            // reach this.
            val leftAt = smartPrefs().getLong("smart_left_at", 0L)
            if (leftAt != 0L && now0 - leftAt > MINIMIZE_GRACE_MS + PERSISTENT_ABSENCE_MS) {
                expireLeave()
            }
        } else if (fg != packageName) {
            if (selectedApps().contains(fg)) {
                smartPrefs().edit().remove("smart_last_selected")
                    .putLong("smart_left_at", 0L).apply()
            } else {
                val leftAt = smartPrefs().getLong("smart_left_at", 0L)
                if (leftAt == 0L) {
                    smartPrefs().edit().remove("smart_last_selected")
                        .putLong("smart_left_at", now0).apply()
                } else if (now0 - leftAt > MINIMIZE_GRACE_MS) {
                    expireLeave()
                }
            }
        }
        // 2.62: repeat the leave-kill on the next few
        // polls: an app that restarts its own
        // background process is killed again while the
        // window lasts. A foreground app is safe: the
        // API only kills background processes.
        if (killRepeatLeft > 0) {
            killRepeatLeft--
            val pkg = lastSelectedFg
            if (pkg != null) {
                try {
                    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                        .killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
            }
        }
        // Never connect while ANY VPN is up — ours or
        // another app's — so a hand-started VPN is never
        // disturbed by smart connect.
        if (anyVpnUp()) {
            if (smartOwnsVpn()) {
                val lbl = ownedVpnLabel()
                diag(if (lbl != null) AppLanguage.t(this, "متصل (هوشمند): $lbl", "Connected (smart): $lbl") else AppLanguage.t(this, "متصل (هوشمند)", "Connected (smart)"))
            }
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
        lastAttemptLabel = smartAppLabel(fg)
        lastAttemptTime = now
        lastAttemptOk = false
        attemptInFlight = true
        Thread { runConnectAttempt() }.start()
    }

    /** Shared grace-expiry actions: cut ONLY a
     *  smart-owned VPN, optionally kill the left
     *  app's background process once (opt-in), and
     *  clear the leave stamp. Called from the
     *  normal grace path and from the persistent-
     *  absence path. */
    private fun expireLeave() {
        if (smartOwnsVpn()) {
            disconnectTarget()
            setSmartOwnsVpn(false)
        }
        // Optional auto-kill (user's proposal, OFF
        // by default): the user left a selected app
        // for good, so kill that app's background
        // process — the next open is a cold start,
        // the effect they got from manually
        // force-stopping. True force-stop needs a
        // system permission no normal app has; this
        // is the closest Android allows. Only once
        // per leave, and the API only kills
        // BACKGROUND processes, so a foreground app
        // is safe.
        if (smartPrefs().getBoolean("smart_kill_on_close", false) && !killDoneForLeave) {
            killDoneForLeave = true
            val pkg = lastSelectedFg
            if (pkg != null) {
                try {
                    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                        .killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
                // 2.62: the kill is now VISIBLE in the
                // central log (it used to be silent, so
                // a kill that fired looked like a kill
                // that never happened), and it repeats
                // on the next few polls.
                killRepeatLeft = 3
                AppLog.append(this, "smart",
                    "دستور بستن برنامه «${smartAppLabel(pkg)}» هنگام ترک صادر شد (بستن پردازش پس‌زمینه).",
                    "Close command for «${smartAppLabel(pkg)}» on leave issued (background-process kill).")
            }
        }
        smartPrefs().edit().remove("smart_last_selected")
            .putLong("smart_left_at", 0L).apply()
    }

    /** Stop whichever target smart connect started.
     *  This is NOT a manual stop: it does not set the
     *  5-minute manual-stop flag, so reopening a selected
     *  app reconnects right away. */
    private fun disconnectTarget() {
        setSmartOwnsVpn(false)
        val dTarget = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        AppLog.append(this, "smart",
            "قطع اتصال؛ هدف: ${smartTargetFa(dTarget)}.",
            "Disconnected; target: $dTarget.")
        // Fresh attempt markers: the next open of a
        // selected app must connect immediately, never
        // wait out the retry guard of the old cycle.
        lastAttemptPkg = null
        lastAttemptTime = 0L
        lastAttemptOk = false
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        try {
            when (target) {
                "wireguard", "amnezia" -> WgDirect.disconnect(this)
                "hydra" -> {
                    // Only when Hydra is actually
                    // Smart's session: never stop a
                    // hand-started Hydra (or anything
                    // else) from here.
                    if (HydraService.running || HydraEngine.hydraOwnsVpn()) {
                        try { HydraService.stop(this) } catch (_: Exception) {}
                    }
                }
                "openvpn" -> {
                    OvpnEngine.disconnect()
                    try {
                        getSharedPreferences("renamer_widget", MODE_PRIVATE)
                            .edit().putBoolean("widget_state_openvpn", false).apply()
                    } catch (_: Exception) {}
                    WidgetRefresh.refreshAll(this)
                }
                "chain" -> ChainEngine.stopAll(this)
                // v2ray AND emergency both run on
                // VpnClientService, so both stop here.
                else -> {
                    val stop = Intent(this, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
                    try { startService(stop) } catch (_: Exception) {}
                    VpnClientService.isRunning = false
                    WidgetRefresh.refreshAll(this)
                }
            }
        } catch (_: Throwable) {}
        refreshNotif("Watching selected apps")
    }

    private fun cfFirstLink(): String? = try {
        val arr = org.json.JSONArray(
            getSharedPreferences("renamer_cf", MODE_PRIVATE)
                .getString("cf_results", "[]") ?: "[]")
        if (arr.length() == 0) null
        else arr.getJSONObject(0).optString("link").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** 1.96: headless Xray-chain connect for Smart — the
     *  same config + service start the Chain page uses,
     *  minus the UI and the VPN-permission prompt (Smart
     *  only runs after the permission was granted once).
     *  OpenVPN-hop chains are excluded: they need the
     *  proxy-core path the Chain page runs in the
     *  foreground, so they are not offered as a Smart
     *  target (see targetAvailable). */
    private fun startChainSmart(): Boolean {
        return try {
            val h1 = ChainEngine.hop(this, 1) ?: return false
            val h2 = ChainEngine.hop(this, 2) ?: return false
            if (h1.type == "openvpn" || h2.type == "openvpn") return false
            val cfg = ChainEngine.vpnConfig(this, h1, h2) ?: return false
            if (!CoreInit.init(applicationContext)) return false
            if (android.net.VpnService.prepare(this) != null) return false
            VpnClientService.emergencyConfig = null
            val chainName = AppLanguage.t(this, "زنجیره دوتایی", "Double chain")
            val intent = Intent(this, VpnClientService::class.java).apply {
                putExtra("config", cfg)
                putExtra("name", chainName)
                putExtra("session_tag", "chain")
            }
            startService(intent)
            ChainEngine.setRunning(this, "xray")
            true
        } catch (_: Exception) { false }
    }

    private fun runConnectAttempt() {
        // 2.43: central log wraps the attempt: the
        // attempt line (app + target) here, the
        // outcome line in finally (every branch
        // returns early, but finally always runs).
        // A VPN already up is not an attempt at all,
        // so it returns before any log line.
        if (anyVpnUp()) {
            attemptInFlight = false
            return
        }
        smartLogAttempt()
        try { runConnectAttemptInner() } finally { smartLogOutcome() }
    }

    /** 2.43: app label for the central log. */
    private fun smartAppLabel(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "؟"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        } catch (_: Exception) { pkg }
    }

    /** 2.43: target code -> display name. */
    private fun smartTargetFa(t: String): String = when (t) {
        "wireguard" -> "وایرگارد"
        "amnezia" -> "امنزیا"
        "openvpn" -> "OpenVPN"
        "hydra" -> "هیدرا"
        "cloudflare" -> "کلادفلر"
        "chain" -> "زنجیره"
        "emergency" -> "اضطراری"
        else -> "v2ray"
    }

    private fun smartLogAttempt() {
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        val app = lastAttemptLabel ?: smartAppLabel(lastAttemptPkg)
        val eng = if (target == "v2ray" || target == "emergency") {
            if (AdaptiveEngine.isEnabled(this)) "؛ موتور هوشمند: روشن" else "؛ موتور هوشمند: خاموش"
        } else ""
        val engEn = if (target == "v2ray" || target == "emergency") {
            if (AdaptiveEngine.isEnabled(this)) "; smart engine: ON" else "; smart engine: OFF"
        } else ""
        AppLog.append(this, "smart",
            "تلاش اتصال خودکار؛ برنامه: $app؛ هدف: ${smartTargetFa(target)}$eng.",
            "Auto-connect attempt; app: $app; target: $target$engEn.")
    }

    private fun smartLogOutcome() {
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        val app = lastAttemptLabel ?: smartAppLabel(lastAttemptPkg)
        val conn = ownedVpnLabel()
        if (lastAttemptOk) {
            AppLog.append(this, "smart",
                "وصل شد؛ برنامه: $app؛ هدف: ${smartTargetFa(target)}" + (if (conn != null) "؛ اتصال: $conn." else "."),
                "Connected; app: $app; target: $target" + (if (conn != null) "; connection: $conn." else "."))
            // 2.45: background real-connection check
            // for smart sessions (v2ray/emergency via
            // socks, openvpn via exit IP; wireguard /
            // chain / hydra / cloudflare have no probe
            // here — their page/engine triggers cover
            // manual connects).
            val sName = VpnClientService.currentName ?: conn ?: "؟"
            when (target) {
                "v2ray", "emergency" -> {
                    // Probe through the RUNNING session's real SOCKS
                    // port first (the 2.53 fix, which this call had missed).
                    val sessionPort = VpnClientService.sessionSocksPort.takeIf { it > 0 }
                    if (sessionPort != null) {
                                            }
                    ConnVerify.verifyXray(this, "v2ray", "smart", sName, sessionPort ?: VpnConfigBuilder.socksPort(this)) { VpnClientService.isRunning }
                }
                "openvpn" -> ConnVerify.verifyOvpn(this, "openvpn", "smart", sName) { OvpnEngine.isConnected }
            }
        } else {
            // Failure reason: the service's latest
            // diag, but only when it was written
            // during THIS attempt (older diags are
            // stale and must never be blamed).
            val reason = smartPrefs().getString("diag_attempt", null)
            val reasonAt = smartPrefs().getLong("diag_attempt_at", 0L)
            val fresh = reason != null && reasonAt >= lastAttemptTime
            AppLog.append(this, "smart",
                "وصل نشد؛ برنامه: $app؛ هدف: ${smartTargetFa(target)}" + (if (fresh) "؛ علت: $reason." else "."),
                "Did not connect; app: $app; target: $target" + (if (fresh) "; reason: $reason." else "."))
        }
    }

    private fun runConnectAttemptInner() {
        // Non-v2ray targets: WireGuard / OpenVPN, same
        // trigger (a selected app opened), same guards.
        val target = smartPrefs().getString("smart_target", "v2ray") ?: "v2ray"
        // 1.96: Cloudflare target — the first config
        // built in the Cloudflare section, connected
        // through the normal v2ray path (its session
        // name is already "Cloudflare - country").
        if (target == "cloudflare") {
            val link = cfFirstLink()
            if (link != null && startVpnConfig(link)) {
                // do not report success until the tunnel is really up.
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (VpnClientService.isRunning) break
                    Thread.sleep(500)
                }
                if (VpnClientService.isRunning) {
                    setSmartOwnsVpn(true)
                    lastAttemptOk = true
                } else {
                                        diag(AppLanguage.t(this, "کلادفلر وصل نشد", "Cloudflare did not connect"))
                }
            }
            attemptInFlight = false
            return
        }
        // 1.96: Chain target — headless Xray chain
        // (see startChainSmart). Disconnect is the
        // "chain" branch of the stop dispatch.
        if (target == "chain") {
            if (startChainSmart()) {
                // do not report success until the tunnel is really up.
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (VpnClientService.isRunning) break
                    Thread.sleep(500)
                }
                if (VpnClientService.isRunning) {
                    setSmartOwnsVpn(true)
                    lastAttemptOk = true
                } else {
                                        diag(AppLanguage.t(this, "زنجیره وصل نشد", "Chain did not connect"))
                }
            }
            attemptInFlight = false
            return
        }
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
                        val emMsg = AppLanguage.t(this, "متصل شد (اضطراری)", "Connected (emergency)")
                        diag(emMsg)
                        refreshNotif(emMsg)
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
                        diag(AppLanguage.t(this, "وایرگارد و امنزیا: تونلی در این بخش نیست؛ اول یک تونل اضافه کن", "WireGuard & Amnezia: no tunnel in this section; add one first"))
                    else if (android.net.VpnService.prepare(this) != null)
                        diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
                    else diag(AppLanguage.t(this, "WireGuard وصل نشد", "WireGuard did not connect"))
                } else {
                    // Wait up to 8s for the tunnel to come up
                    // (ground truth: a live GoBackend
                    // tunnel, never the stale pref).
                    val deadline = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < deadline) {
                        if (WgDirect.isLive(this)) break
                        Thread.sleep(500)
                    }
                    if (WgDirect.isLive(this)) {
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        val wgName = WgDirect.activeConf(this)?.first
                        val wgMsg = if (wgName != null) AppLanguage.t(this, "متصل شد (WireGuard): $wgName", "Connected (WireGuard): $wgName") else AppLanguage.t(this, "متصل شد (WireGuard)", "Connected (WireGuard)")
                        diag(wgMsg)
                        refreshNotif(wgMsg)
                    } else diag(AppLanguage.t(this, "WireGuard وصل نشد", "WireGuard did not connect"))
                }
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        if (target == "amnezia") {
            try {
                // Mirror of the wireguard branch, but
                // Amnezia tunnels only: the saved active
                // tunnel when it is an Amnezia config,
                // else the first saved Amnezia tunnel.
                if (!WgDirect.connectFirstOrActiveAwg(this)) {
                    if (WgDirect.firstAwgConf(this) == null)
                        diag(AppLanguage.t(this, "امنزیا: تونلی در این بخش نیست؛ اول یک تونل امنزیا اضافه کن", "AmneziaWG: no Amnezia tunnel in this section; add one first"))
                    else if (android.net.VpnService.prepare(this) != null)
                        diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
                    else diag(AppLanguage.t(this, "امنزیا وصل نشد", "AmneziaWG did not connect"))
                } else {
                    // Wait up to 8s for the tunnel to come up
                    // (ground truth: a live backend
                    // tunnel, never the stale pref).
                    val deadline = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < deadline) {
                        if (WgDirect.isLive(this)) break
                        Thread.sleep(500)
                    }
                    if (WgDirect.isLive(this)) {
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        val awgName = WgDirect.activeConf(this)?.first
                        val awgMsg = if (awgName != null) AppLanguage.t(this, "متصل شد (امنزیا): $awgName", "Connected (AmneziaWG): $awgName") else AppLanguage.t(this, "متصل شد (امنزیا)", "Connected (AmneziaWG)")
                        diag(awgMsg)
                        refreshNotif(awgMsg)
                    } else diag(AppLanguage.t(this, "امنزیا وصل نشد", "AmneziaWG did not connect"))
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
                        // do not report success until CONNECTED is real.
                        val deadline = System.currentTimeMillis() + 8000
                        while (System.currentTimeMillis() < deadline) {
                            if (OvpnEngine.isConnected) break
                            Thread.sleep(500)
                        }
                        if (OvpnEngine.isConnected) {
                            getSharedPreferences("renamer_widget", MODE_PRIVATE)
                                .edit().putBoolean("widget_state_openvpn", true).apply()
                            setSmartOwnsVpn(true)
                            lastAttemptOk = true
                            val ovMsg = AppLanguage.t(this, "متصل شد (OpenVPN): ${prof.name}", "Connected (OpenVPN): ${prof.name}")
                            diag(ovMsg)
                            refreshNotif(ovMsg)
                            WidgetRefresh.refreshAll(this)
                        } else {
                                                        diag(AppLanguage.t(this, "OpenVPN وصل نشد", "OpenVPN did not connect"))
                        }
                    } else diag(AppLanguage.t(this, "OpenVPN: پروفایلی پیدا نشد", "OpenVPN: no profile found"))
                }
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        if (target == "hydra") {
            try {
                if (!HydraService.running) {
                    if (android.net.VpnService.prepare(this) != null) {
                        diag(AppLanguage.t(this, "مجوز VPN ثبت نشده؛ یک بار دستی وصل شو", "VPN permission not granted yet; connect once manually"))
                    } else {
                        // No takeover here: Smart must
                        // never disturb a hand-started
                        // VPN, and pollOnce already
                        // ensured none is up. Hydra's
                        // own probe gate steps aside
                        // if one appears mid-probe.
                        HydraService.start(this, takeover = false)
                        setSmartOwnsVpn(true)
                        lastAttemptOk = true
                        val hm = AppLanguage.t(this, "هیدرا در حال چشیدن و وصل شدن…", "Hydra is probing and connecting…")
                        diag(hm)
                        refreshNotif(hm)
                    }
                }
                // else: Hydra already running (possibly
                // hand-started): Smart claims nothing
                // and touches nothing.
            } catch (_: Throwable) {}
            attemptInFlight = false
            return
        }
        try {
            // ADAPTIVE ENGINE (1.78), selector-only
            // pre-step. REGRESSION GUARD: this changes
            // NOTHING about Smart's invariants — it
            // only tries to improve WHICH config gets
            // connected, and only when the engine is
            // on. It never overwrites the saved
            // "selected" config, never sets ownership
            // except through the normal success path
            // below, and on any no-win or failure
            // execution falls through to the exact
            // old logic (fast path, then best-ping).
            if (AdaptiveEngine.isEnabled(this)) {
                try {
                    val vpnPrefsE = vpnPrefs()
                    val serversE = vpnPrefsE.getString("servers", "")?.lines()
                        ?.filter { it.isNotBlank() } ?: emptyList()
                    val lastE = vpnPrefsE.getString("selected", null)?.takeIf { it.isNotBlank() }
                    if (lastE != null && serversE.contains(lastE) && !XraySpeedtest.isGroup(lastE)) {
                        // Smart finally gets a real pool — persisted
                        // healthy delays plus the subscription healthy list —
                        // so the jump/chain waves are no longer always skipped.
                        val smartPool = (AdaptiveEngine.persistedPool(this, serversE) +
                            AdaptiveEngine.lastHealthyLinks(this))
                            .distinctBy { XraySpeedtest.dedupKey(it) }
                        if (smartPool.isNotEmpty()) {
                                                    }
                        val plan = AdaptiveEngine.selectV2ray(this, lastE, AdaptiveEngine.S_SMART, 12000, smartPool)
                        if (plan != null && startVpnConfig(plan.link, plan, lastE)) {
                            val deadline = System.currentTimeMillis() + FAST_PATH_WAIT_MS
                            while (System.currentTimeMillis() < deadline) {
                                if (VpnClientService.isRunning) {
                                    setSmartOwnsVpn(true)
                                    lastAttemptOk = true
                                    val eMsg = AppLanguage.t(this, "متصل شد (موتور هوشمند): ", "Connected (Smart Engine): ") + XraySpeedtest.displayName(plan.link)
                                    diag(eMsg)
                                    refreshNotif(eMsg)
                                    attemptInFlight = false
                                    return
                                }
                                Thread.sleep(500)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
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
                        val fMsg = AppLanguage.t(this, "متصل شد: ", "Connected: ") + XraySpeedtest.displayName(last ?: "")
                        diag(fMsg)
                        refreshNotif(fMsg)
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
                                val bMsg = AppLanguage.t(this, "متصل شد: ", "Connected: ") + XraySpeedtest.displayName(chosen)
                                diag(bMsg)
                                refreshNotif(bMsg)
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
    private fun startVpnConfig(cfg: String, plan: AdaptiveEngine.Plan? = null, origLink: String? = null): Boolean {
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
            // ADAPTIVE ENGINE (1.78): one-shot plan
            // (fragment injection); null plan =
            // unchanged, exactly the old behavior.
            val builtE = AdaptiveEngine.applyPlanToBuiltJson(this, built, plan, true) ?: built
            val vpnConfig = if (mode == "Resolve only") {
                VpnConfigBuilder.applyResolvedIp(builtE, resolved)
            } else builtE
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
                // ADAPTIVE ENGINE (1.79): mark the
                // session for the service watchdog.
                if (plan != null && origLink != null) {
                    putExtra("engine_session", true)
                    putExtra("engine_orig_link", origLink)
                }
            }
            VpnClientService.emergencyConfig = null
            startService(intent)
            true
        } catch (_: Throwable) { false }
    }
}
