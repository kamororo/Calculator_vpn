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

    /** Tunnel object for the AmneziaWG backend: same
     *  sanitising as DirectTunnel, so a display name
     *  maps to the same interface name on both engines. */
    class AwgDirectTunnel(val displayName: String) : org.amnezia.awg.backend.Tunnel {
        val ifaceName: String = displayName.map { c ->
            if (c.isLetterOrDigit() || c in "_=+.-") c else '_'
        }.joinToString("").take(15).ifBlank { "wg" }
        override fun getName(): String = ifaceName
        override fun onStateChange(state: org.amnezia.awg.backend.Tunnel.State) {}
    }

    /** Smart detection: is this .conf an AmneziaWG
     *  config? All junk/obfuscation keys are
     *  Interface-level, so only the section before
     *  the first [Peer] is scanned, case-insensitively.
     *  A plain WireGuard .conf has none of these keys
     *  and keeps using the official WireGuard engine. */
    private val AWG_KEYS = setOf(
        "jc", "jmin", "jmax",
        "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition",
        "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts",
        "randomtrailers", "disablecookies"
    )

    fun isAwgConf(conf: String): Boolean {
        return try {
            for (raw in conf.lines()) {
                val line = raw.trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (line.removeSurrounding("[", "]").trim().equals("Peer", ignoreCase = true)) break
                    continue
                }
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).trim().lowercase()
                if (key in AWG_KEYS) return true
            }
            false
        } catch (_: Exception) { false }
    }

    @Volatile private var current: DirectTunnel? = null
    @Volatile private var currentAwg: AwgDirectTunnel? = null

    /** 2.68: the tunnel object
     *  Smart Connect's engine attempts poll for a
     *  fresh handshake. */
    fun currentAwgTunnel(): AwgDirectTunnel? = currentAwg

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

    /** Ground truth: is a WireGuard tunnel REALLY
     *  up right now? GoBackend tracks its tunnels
     *  by name, so this covers tunnels started
     *  from the WG section UI as well as from
     *  here. The widget pref (isUp) is NOT ground
     *  truth: it stays true after an abnormal end
     *  (crash, killed process), and that stale
     *  true is exactly what made Hydra step aside
     *  on every tap. */
    fun isLive(ctx: Context): Boolean {
        return try {
            WgBackend.get(ctx).runningTunnelNames.isNotEmpty() ||
                AwgBackend.get(ctx).runningTunnelNames.isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** Object-state WireGuard check, for Monitoring.
     *  isLive() counts backend NAME registrations,
     *  which go stale when Android revokes WG's
     *  service for another VPN (e.g. OpenVPN): the
     *  WG page then shows disconnected while
     *  isLive() still says up, because nothing on
     *  that path clears the name registration.
     *  This checks the registered/current tunnel
     *  OBJECTS' states instead — the same rule the
     *  WG page's own poller uses — so a revoked
     *  tunnel reads DOWN even when its name
     *  lingers. isLive() itself is untouched:
     *  other callers rely on its name-set meaning. */
    fun isReallyUp(ctx: Context): Boolean {
        try {
            val wg = WgTunnelRegistry.wgTunnel
            if (wg != null) {
                try {
                    if (WgBackend.get(ctx).getState(wg) == Tunnel.State.UP) return true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val awg = WgTunnelRegistry.awgTunnel
            if (awg != null) {
                try {
                    if (AwgBackend.get(ctx).getState(awg) == org.amnezia.awg.backend.Tunnel.State.UP) return true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val c = current
            if (c != null) {
                try {
                    if (WgBackend.get(ctx).getState(c) == Tunnel.State.UP) return true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val ca = currentAwg
            if (ca != null) {
                try {
                    if (AwgBackend.get(ctx).getState(ca) == org.amnezia.awg.backend.Tunnel.State.UP) return true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return false
    }

    /** 2.59: names of the tunnels whose OBJECT
     *  state is UP right now (same four objects
     *  isReallyUp checks). The set holds display
     *  names where the object carries one, plus
     *  interface names (registry tunnels are
     *  typed as the backend interface, which has
     *  no displayName). The WG test uses this to
     *  count a live tunnel as healthy WITHOUT
     *  probing it — probing the live tunnel's
     *  endpoint with a second handshake must
     *  never disturb the live session.
     *  Read-only; changes nothing. */
    fun liveTunnelNames(ctx: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val wg = WgTunnelRegistry.wgTunnel
            if (wg != null) {
                try {
                    if (WgBackend.get(ctx).getState(wg) == Tunnel.State.UP) {
                        (wg as? DirectTunnel)?.displayName?.let { out += it }
                        out += wg.name
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val awg = WgTunnelRegistry.awgTunnel
            if (awg != null) {
                try {
                    if (AwgBackend.get(ctx).getState(awg) == org.amnezia.awg.backend.Tunnel.State.UP) {
                        (awg as? AwgDirectTunnel)?.displayName?.let { out += it }
                        out += awg.name
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val c = current
            if (c != null) {
                try {
                    if (WgBackend.get(ctx).getState(c) == Tunnel.State.UP) out += c.displayName
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val ca = currentAwg
            if (ca != null) {
                try {
                    if (AwgBackend.get(ctx).getState(ca) == org.amnezia.awg.backend.Tunnel.State.UP) out += ca.displayName
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return out
    }

    /** 2.59: the interface name a display name
     *  maps to (same sanitising DirectTunnel
     *  uses), so the WG test can match a live
     *  registry tunnel back to its saved name. */
    fun ifaceNameOf(display: String): String =
        display.map { c -> if (c.isLetterOrDigit() || c in "_=+.-") c else '_' }
            .joinToString("").take(15).ifBlank { "wg" }

    /** Clear a stale widget flag: pref says up
     *  but no tunnel is really running (the
     *  process that owned it is gone). Returns
     *  true when it cleared something. */
    fun clearStaleFlag(ctx: Context): Boolean {
        return try {
            val p = ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
            if (p.getBoolean("widget_state_wireguard", false) && !isLive(ctx)) {
                p.edit().putBoolean("widget_state_wireguard", false).apply()
                WidgetRefresh.refreshAll(ctx)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /** Stop the running WireGuard/Amnezia tunnels,
     *  whoever started them (section UI or here).
     *  Stops use the tunnel objects actually
     *  registered with the backends: stopping "by
     *  name" with fresh objects was a silent no-op
     *  (identity check), so it never stopped
     *  anything. Used only by Hydra's
     *  explicit-connect takeover. */
    fun disconnectAll(ctx: Context) {
        val w = WgTunnelRegistry.wgTunnel
        val a = WgTunnelRegistry.awgTunnel
        try {
            if (w != null) Thread {
                try { WgBackend.get(ctx).setState(w, Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            }.start()
        } catch (_: Throwable) {}
        try {
            if (a != null) Thread {
                try { AwgBackend.get(ctx).setState(a, org.amnezia.awg.backend.Tunnel.State.DOWN, null) } catch (_: Throwable) {}
            }.start()
        } catch (_: Throwable) {}
        try {
            ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                .edit().putBoolean("widget_state_wireguard", false).apply()
        } catch (_: Exception) {}
        current = null
        currentAwg = null
        if (WgTunnelRegistry.wgTunnel === w) WgTunnelRegistry.wgTunnel = null
        if (WgTunnelRegistry.awgTunnel === a) WgTunnelRegistry.awgTunnel = null
        WidgetRefresh.refreshAll(ctx)
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

    /** First saved AMNEZIA tunnel, if any (used only
     *  by the Smart Connect "amnezia" target). */
    fun firstAwgConf(ctx: Context): Pair<String, String>? {
        return try {
            val prefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val conf = o.optString("conf")
                if (conf.isNotBlank() && isAwgConf(conf)) return Pair(o.optString("name"), conf)
            }
            null
        } catch (_: Exception) { null }
    }

    /** Connect an Amnezia tunnel only: the saved active
     *  tunnel when it is an Amnezia config, else the
     *  first saved Amnezia tunnel (recorded as active,
     *  so the WG section agrees). Returns false when
     *  there is no Amnezia tunnel at all, the
     *  permission dialog is still needed, or the
     *  backend throws. Used ONLY by the Smart Connect
     *  "amnezia" target; the plain "wireguard" target
     *  keeps connectFirstOrActive (any tunnel, engine
     *  auto-detected per config). */
    fun connectFirstOrActiveAwg(ctx: Context): Boolean {
        val active = activeConf(ctx)
        val conf = if (active != null && isAwgConf(active.second)) active
            else firstAwgConf(ctx)?.also { (nm, _) ->
                try {
                    ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                        .edit().putString("wg_active", nm).apply()
                } catch (_: Exception) {}
            } ?: return false
        return connectConf(ctx, conf.first, conf.second)
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

    /** Connect an explicit .conf text (used by the Cloudflare test section). */
    /** AWG's parser throws on unknown attributes and does not know Reserved.
     *  Other apps do use it, so it stays in the saved/displayed text — but
     *  every in-app parse runs on this filtered copy. */
    fun stripReserved(conf: String): String =
        conf.lines().filter { !it.trim().startsWith("Reserved", ignoreCase = true) }.joinToString("\n")

    fun connectConfText(ctx: Context, name: String, conf: String): Boolean =
        connectConf(ctx, name, conf)

    private fun connectConf(ctx: Context, name: String, conf: String): Boolean {
        if (VpnClientService.isRunning) {
            try {
                val stop = android.content.Intent(ctx, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
                ctx.startService(stop)
                VpnClientService.isRunning = false
                Thread.sleep(800)
            } catch (_: Exception) {}
        }
        if (VpnService.prepare(ctx) != null) return false
        // ENGINE FALLBACK (1.76): no WG-backend
        // calls on the connect path at all — see
        // WireGuardActivity. Nothing creates a
        // stock-WG tunnel anymore, so there is
        // nothing to stop here.
        // ENGINE FALLBACK (1.76): every config,
        // plain WireGuard included, goes through
        // the Amnezia engine — see the activity.
        return try {
            val config = org.amnezia.awg.config.Config.parse(BufferedReader(StringReader(stripReserved(conf).trim())))
            val tunnel = AwgDirectTunnel(name)
            currentAwg = tunnel
            current = null
            WgTunnelRegistry.awgTunnel = tunnel
            Thread {
                try {
                    AwgBackend.get(ctx).setState(tunnel, org.amnezia.awg.backend.Tunnel.State.UP, config)
                    ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                        .edit().putBoolean("widget_state_wireguard", true).apply()
                    WidgetRefresh.refreshAll(ctx)
                } catch (_: Throwable) {
                    if (WgTunnelRegistry.awgTunnel === tunnel) WgTunnelRegistry.awgTunnel = null
                    currentAwg = null
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
        val tunnelAwg = currentAwg
        Thread {
            try {
                if (tunnel != null) WgBackend.get(ctx).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Throwable) {}
            try {
                if (tunnelAwg != null) AwgBackend.get(ctx).setState(tunnelAwg, org.amnezia.awg.backend.Tunnel.State.DOWN, null)
            } catch (_: Throwable) {}
            if (tunnel != null && WgTunnelRegistry.wgTunnel === tunnel) WgTunnelRegistry.wgTunnel = null
            if (tunnelAwg != null && WgTunnelRegistry.awgTunnel === tunnelAwg) WgTunnelRegistry.awgTunnel = null
            try {
                ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                    .edit().putBoolean("widget_state_wireguard", false).apply()
            } catch (_: Exception) {}
            WidgetRefresh.refreshAll(ctx)
        }.start()
        current = null
        currentAwg = null
        try {
            ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                .edit().putBoolean("widget_state_wireguard", false).apply()
        } catch (_: Exception) {}
        WidgetRefresh.refreshAll(ctx)
    }
}
