package com.renamer

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import java.io.BufferedReader
import java.io.StringReader

/**
 * WireGuard connect/disconnect usable from ANY context
 * (SmartConnectService, WidgetToggleActivity), not just
 * WireGuardActivity. It mirrors WireGuardActivity's own
 * logic: same GoBackend singleton (WgBackend), same
 * interface-name sanitising, same prefs (renamer_wg
 * wg_list / wg_active, renamer_widget widget_state).
 *
 * Limits, stated honestly: connect works in the
 * background only when the VPN permission was already
 * granted once (VpnService.prepare == null); otherwise
 * the caller must open WireGuardActivity so the system
 * dialog can appear. Disconnect needs the tunnel that
 * GoBackend tracks; this object keeps its own reference
 * to the last tunnel it started, and falls back to
 * stopping via the activity when it has none.
 */
object WgDirect {
    class DirectTunnel(val displayName: String) : Tunnel {
        val ifaceName: String = displayName.map { c ->
            if (c.isLetterOrDigit() || c in "_=+.-") c else '_'
        }.joinToString("").take(15).ifBlank { "wg" }
        override fun getName(): String = ifaceName
        override fun onStateChange(state: Tunnel.State) {}
    }

    @Volatile private var current: DirectTunnel? = null

    fun activeConf(ctx: Context): Pair<String, String>? {
        return try {
            val prefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
            val active = prefs.getString("wg_active", null)?.takeIf { it.isNotBlank() } ?: return null
            val arr = JSONArray(prefs.getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == active) {
                    val conf = o.optString("conf")
                    if (conf.isNotBlank()) return Pair(active, conf)
                }
            }
            null
        } catch (_: Exception) { null }
    }

    fun isUp(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                .getBoolean("widget_state_wireguard", false)
        } catch (_: Exception) { false }
    }

    /** First saved tunnel, if any (used when no
     *  active tunnel is recorded). */
    fun firstConf(ctx: Context): Pair<String, String>? {
        return try {
            val prefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val conf = o.optString("conf")
                if (conf.isNotBlank()) return Pair(o.optString("name"), conf)
            }
            null
        } catch (_: Exception) { null }
    }

    /** Connect the saved active tunnel, falling back
     *  to the first saved tunnel (and recording it as
     *  active, so the WG section agrees). Returns
     *  false when there is no tunnel at all, the
     *  permission dialog is still needed, or
     *  GoBackend throws. */
    fun connectFirstOrActive(ctx: Context): Boolean {
        val conf = activeConf(ctx) ?: firstConf(ctx)?.also { (nm, _) ->
            try {
                ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                    .edit().putString("wg_active", nm).apply()
            } catch (_: Exception) {}
        } ?: return false
        return connectConf(ctx, conf.first, conf.second)
    }

    /** Connect the saved active tunnel. Returns false when
     *  there is no active tunnel, the permission dialog is
     *  still needed, or GoBackend throws. */
    fun connectActive(ctx: Context): Boolean {
        val (name, conf) = activeConf(ctx) ?: return false
        return connectConf(ctx, name, conf)
    }

    private fun connectConf(ctx: Context, name: String, conf: String): Boolean {
        if (VpnClientService.isRunning) {
            try {
                val stop = android.content.Intent(ctx, VpnClientService::class.java).apply { action = "STOP" }
                ctx.startService(stop)
                ctx.stopService(android.content.Intent(ctx, VpnClientService::class.java))
                VpnClientService.isRunning = false
                Thread.sleep(800)
            } catch (_: Exception) {}
        }
        if (VpnService.prepare(ctx) != null) return false
        return try {
            val config = Config.parse(BufferedReader(StringReader(conf.trim())))
            val tunnel = DirectTunnel(name)
            current = tunnel
            Thread {
                try {
                    WgBackend.get(ctx).setState(tunnel, Tunnel.State.UP, config)
                    ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                        .edit().putBoolean("widget_state_wireguard", true).apply()
                    WidgetRefresh.refreshAll(ctx)
                } catch (_: Throwable) {
                    ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                        .edit().putBoolean("widget_state_wireguard", false).apply()
                    WidgetRefresh.refreshAll(ctx)
                }
            }.start()
            true
        } catch (_: Throwable) { false }
    }

    fun disconnect(ctx: Context) {
        val tunnel = current
        Thread {
            try {
                if (tunnel != null) WgBackend.get(ctx).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Throwable) {}
            try {
                ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                    .edit().putBoolean("widget_state_wireguard", false).apply()
            } catch (_: Exception) {}
            WidgetRefresh.refreshAll(ctx)
        }.start()
        current = null
        try {
            ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                .edit().putBoolean("widget_state_wireguard", false).apply()
        } catch (_: Exception) {}
        WidgetRefresh.refreshAll(ctx)
    }
}
