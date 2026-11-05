package com.renamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

/**
 * Notification actions for the VPN status notification
 * (see VpnNotification). Explicit broadcasts to this
 * receiver, so exported=false in the manifest is fine.
 *
 * - DISCONNECT: stops VpnClientService the same way the
 *   activities do (STOP intent + stopService) and
 *   cancels the ongoing notification.
 * - CONNECT: if not already running, restarts the last
 *   selected config (prefs "renamer_vpn" key "selected",
 *   which VpnActivity.saveServers() keeps as the full
 *   config string). Background start only works when
 *   VpnService.prepare() returns null, i.e. the user
 *   has connected at least once from the app and the
 *   VPN permission is already granted; otherwise (or
 *   when no config is stored, or the config cannot be
 *   built) VpnActivity is opened instead so the user
 *   can connect from the UI.
 * - RESTART: disconnect, then start the same config
 *   again IMMEDIATELY. A delayed restart (e.g. 800ms)
 *   cannot be done reliably here: a BroadcastReceiver's
 *   process may be killed as soon as onReceive returns,
 *   so a Handler.postDelayed posted here is
 *   best-effort at best and usually never fires. The
 *   immediate restart is the honest version: the
 *   service's own start path stops any previous core
 *   before starting the new one, and the STOP branch
 *   stops the old core on a background thread. There
 *   is a small race with the old core's shutdown, but
 *   the new core replaces the controller, so a restart
 *   does not leave two cores running.
 *
 * Starting the service from a receiver while the app is
 * in the background uses startForegroundService on
 * API 26+ (the service calls startForeground
 * immediately in onStartCommand, so the 5-second
 * foreground deadline is met). Delivering the STOP
 * intent to an already-running service via startService
 * is the same pattern the activities already use.
 */
class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (ctx == null || intent == null) return
        val appCtx = ctx.applicationContext
        when (intent.action) {
            VpnNotification.ACTION_DISCONNECT -> disconnect(appCtx)
            VpnNotification.ACTION_CONNECT -> connect(appCtx)
            VpnNotification.ACTION_RESTART -> {
                disconnect(appCtx)
                connect(appCtx)
            }
        }
    }

    private fun disconnect(ctx: Context) {
        try {
            val stop = Intent(ctx, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
            }
            try { ctx.startService(stop) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        VpnClientService.emergencyConfig = null
        VpnNotification.showDisconnected(ctx)
    }

    /** Starts the last selected config, or opens
     *  VpnActivity when a background start is impossible.
     *  Uses the application context throughout: a
     *  ReceiverRestrictedContext would be wrong for
     *  CoreInit/file access. */
    private fun connect(ctx: Context) {
        if (VpnClientService.isRunning) return
        val appCtx = ctx.applicationContext
        val prefs = appCtx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
        val cfg = prefs.getString("selected", null)?.takeIf { it.isNotBlank() }
        if (cfg == null) {
            openVpnActivity(appCtx)
            return
        }
        // Background start needs an already-granted VPN
        // permission; prepare() returning an Intent means
        // only an Activity can show the permission screen.
        try {
            if (VpnService.prepare(appCtx) != null) {
                openVpnActivity(appCtx)
                return
            }
        } catch (_: Throwable) {
            openVpnActivity(appCtx)
            return
        }
        val vpnConfig = try { VpnConfigBuilder.build(appCtx, cfg) } catch (_: Throwable) { null }
        if (vpnConfig == null) {
            openVpnActivity(appCtx)
            return
        }
        if (!CoreInit.init(appCtx)) {
            openVpnActivity(appCtx)
            return
        }
        try {
            val intent = Intent(appCtx, VpnClientService::class.java).apply {
                putExtra("config", vpnConfig)
                putExtra("name", XraySpeedtest.displayName(cfg))
            }
            VpnClientService.emergencyConfig = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        } catch (_: Throwable) {
            openVpnActivity(appCtx)
        }
    }

    private fun openVpnActivity(ctx: Context) {
        try {
            val intent = Intent(ctx, VpnActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) {}
    }
}
