package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder

/** Owns a Hydra session: runs the probe, then
 *  watches the connection and re-probes on its
 *  own when it dies — unless the user stopped it
 *  (hydra_active false) or another VPN took over,
 *  in which case it steps aside without stopping
 *  that VPN. The activity only shows state; this
 *  service does the work, so closing the page does
 *  not stop the watchdog. */
class HydraService : Service() {
    companion object {
        const val ACTION_START = "hydra_start"
        const val ACTION_STOP = "hydra_stop"

        @Volatile var running = false

        fun start(ctx: Context) {
            val intent = Intent(ctx, HydraService::class.java).apply { action = ACTION_START }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
                try { ctx.startService(intent) } catch (_: Exception) {}
            }
        }

        fun stop(ctx: Context) {
            HydraEngine.setActive(ctx, false)
            try {
                val intent = Intent(ctx, HydraService::class.java).apply { action = ACTION_STOP }
                ctx.startService(intent)
            } catch (_: Exception) {}
            try { ctx.stopService(Intent(ctx, HydraService::class.java)) } catch (_: Exception) {}
        }
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HydraEngine.setActive(this, false)
            HydraEngine.stopVpn(this)
            running = false
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        HydraEngine.setActive(this, true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel("hydra") == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            "hydra",
                            AppLanguage.t(this, "هیدرا", "Hydra"),
                            NotificationManager.IMPORTANCE_LOW
                        )
                    )
                }
            }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "hydra")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle(AppLanguage.t(this, "هیدرا فعال است", "Hydra is active"))
                .setContentText(AppLanguage.t(this, "در حال چشیدن شبکه و نگهبانی از اتصال", "Probing the network and watching the connection"))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForeground(3002, notif)
        } catch (_: Throwable) {}
        worker = Thread {
            try {
                runSession()
            } catch (_: Throwable) {
            } finally {
                running = false
                try { stopForeground(true) } catch (_: Exception) {}
                try { stopSelf() } catch (_: Exception) {}
            }
        }.also { it.start() }
        return START_STICKY
    }

    /** Probe in rounds until connected, stopped,
     *  or handed to another VPN. There is NO 60s
     *  idle after a failed probe anymore: a failed
     *  round settles only 3s (15s after 10 rounds, a
     *  battery guard) and goes again with freshly
     *  resolved endpoints and a fresh strategy
     *  order, so the fastest working combination is
     *  found instead of waited for. When the
     *  continuous option is off, exactly one round
     *  runs. A missing VPN permission stops the
     *  loop at once: rounds cannot succeed without
     *  it, and the probe's own message already says
     *  to connect once manually first. */
    private fun probeUntilConnected(ctx: Context): Boolean {
        var round = 0
        while (HydraEngine.isActive(ctx)) {
            if (HydraEngine.anyVpnUp(ctx) && !HydraEngine.hydraOwnsVpn()) {
                HydraEngine.log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                    "The connection was handed to another VPN; Hydra steps aside.")
                return false
            }
            if (VpnService.prepare(ctx) != null) {
                if (round == 0) HydraEngine.probeAndConnect(ctx, HydraEngine.loadOpts(ctx))
                return false
            }
            round++
            if (round > 1) HydraEngine.log(ctx, "دور $round چشیدن…",
                "Probing round $round…")
            val optsNow = HydraEngine.loadOpts(ctx)
            if (HydraEngine.probeAndConnect(ctx, optsNow, HydraEngine.getTasteCursor(ctx))) return true
            if (!HydraEngine.isActive(ctx)) return false
            if (HydraEngine.anyVpnUp(ctx) && !HydraEngine.hydraOwnsVpn()) {
                HydraEngine.log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                    "The connection was handed to another VPN; Hydra steps aside.")
                return false
            }
            if (!optsNow.continuous) return false
            val settle = if (round >= 10) 15_000L else 3_000L
            if (round == 10) HydraEngine.log(ctx,
                "۱۰ دور جواب نداد؛ برای حفظ باتری بین دورها ۱۵ ثانیه مکث می‌کنم.",
                "10 rounds failed; pausing 15 seconds between rounds to save battery.")
            // Sleep in slices so a stop is instant.
            var waited = 0L
            while (HydraEngine.isActive(ctx) && waited < settle) {
                try { Thread.sleep(500) } catch (_: Exception) {}
                waited += 500
            }
        }
        return false
    }

    private fun sleepSlice(ctx: Context, ms: Long) {
        var waited = 0L
        while (HydraEngine.isActive(ctx) && waited < ms) {
            try { Thread.sleep(1000) } catch (_: Exception) {}
            waited += 1000
        }
    }

    private fun runSession() {
        val ctx = applicationContext
        HydraEngine.log(ctx, "هیدرا شروع شد.",
            "Hydra started.")
        HydraEngine.setPhase(ctx, "probing",
            AppLanguage.t(ctx, "در حال چشیدن شبکه…", "Probing the network…"))
        if (!probeUntilConnected(ctx)) return
        // Connected. Watch only if the watchdog
        // toggle is on; otherwise leave the VPN up
        // and exit — no watching, no re-probing.
        if (!HydraEngine.loadOpts(ctx).watchdog) return
        // Watchdog: the VPN is up. Watch it, and
        // re-probe on its own when it dies.
        var tunnelFailures = 0
        while (HydraEngine.isActive(ctx)) {
            sleepSlice(ctx, 20_000)
            if (!HydraEngine.isActive(ctx)) return
            if (!VpnClientService.isRunning) {
                if (!HydraEngine.hydraOwnsVpn() && HydraEngine.anyVpnUp(ctx)) {
                    HydraEngine.log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                        "The connection was handed to another VPN; Hydra steps aside.")
                    return
                }
                HydraEngine.log(ctx, "اتصال قطع شد؛ دوباره می‌چشم و وصل می‌شوم.",
                    "Connection lost; re-probing and reconnecting.")
                if (HydraEngine.anyVpnUp(ctx) && !HydraEngine.hydraOwnsVpn()) {
                    HydraEngine.log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                        "The connection was handed to another VPN; Hydra steps aside.")
                    return
                }
                HydraEngine.setPhase(ctx, "probing",
                    AppLanguage.t(ctx, "در حال چشیدن دوباره…", "Re-probing…"))
                if (!probeUntilConnected(ctx)) return
                tunnelFailures = 0
                continue
            }
            if (!HydraEngine.hydraOwnsVpn()) {
                HydraEngine.log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                    "The connection was handed to another VPN; Hydra steps aside.")
                return
            }
            // Tunnel health check every tick (20s):
            // one failed check warns, two in a row
            // re-probe. A single blip must not kill
            // a healthy connection.
            val port = VpnConfigBuilder.socksPort(ctx)
            if (port > 0) {
                val ok = try {
                    val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress("127.0.0.1", port))
                    val conn = java.net.URL(VpnConfigBuilder.delayTestUrl(ctx))
                        .openConnection(proxy) as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val code = conn.responseCode
                    try { conn.disconnect() } catch (_: Exception) {}
                    code in 200..299
                } catch (_: Exception) { false }
                if (!ok) {
                    tunnelFailures++
                    HydraEngine.log(ctx, "تونل جواب نمی‌دهد (خطای $tunnelFailures از ۲).",
                        "Tunnel is not responding (failure $tunnelFailures of 2).")
                    if (tunnelFailures >= 2) {
                        HydraEngine.log(ctx, "تونل از کار افتاد؛ قطع و چشیدن دوباره.",
                            "Tunnel failed; disconnecting and re-probing.")
                        HydraEngine.stopVpn(ctx)
                        tunnelFailures = 0
                    }
                } else {
                    tunnelFailures = 0
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
