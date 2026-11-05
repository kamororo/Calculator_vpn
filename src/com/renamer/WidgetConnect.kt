package com.renamer

import android.app.Activity
import android.content.Context
import android.content.Intent

/** 2.35 "any working config" connection mode for section widgets.
 *  The Studio's connection-mode pills choose between "last" (the
 *  2.33/2.34 behaviour, untouched) and "any": try the section's
 *  saved configs in order until one actually comes up.
 *
 *  All three functions BLOCK (bounded waits, at most 6 attempts)
 *  and must run off the main thread; the toggle activities call
 *  them on a worker thread and finish on the callback thread.
 *  Disconnect behaviour is unchanged: tapping a connected widget
 *  always disconnects, whichever mode it has.
 *
 *  (a) each function returns a Result instead of a
 *  Boolean: NEEDS_PERMISSION means the VPN permission is not
 *  granted yet, so the caller must show the system dialog and
 *  retry once after grant instead of reporting a plain failure.
 *  (b) "up" is no longer success by itself: a short real-traffic
 *  probe must pass first, and a dead tunnel falls through to
 *  the next config. */
object WidgetConnect {
    private const val MAX_TRIES = 6

    /** any-path outcome. */
    enum class Result { SUCCESS, FAILED, NEEDS_PERMISSION }

    /** short real-traffic probe for direct WG/OVPN
     *  sessions (which have no local SOCKS port): one plain
     *  fetch through the TUN, short timeouts. Any HTTP answer
     *  means traffic really passes. */
    private fun tunExitProbe(): Boolean {
        return try {
            val c = java.net.URL("https://api.ipify.org")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.requestMethod = "GET"
            c.responseCode in 100..599
        } catch (_: Exception) { false }
    }

    private fun logProbeDead(ctx: Context, section: String) {
        try {
                    } catch (_: Exception) {}
    }

    fun connectWireGuardAny(ctx: Context): Result {
        // on a connect that cannot even start.
        if (android.net.VpnService.prepare(ctx) != null) return Result.NEEDS_PERMISSION
        val entries = ArrayList<Pair<String, String>>()
        try {
            val prefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString("wg_list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optString("conf")
                if (c.isNotBlank()) entries += o.optString("name") to c
            }
        } catch (_: Exception) {}
        for ((name, conf) in entries.take(MAX_TRIES)) {
            try {
                try { WgDirect.disconnectAll(ctx) } catch (_: Exception) {}
                if (!WgDirect.connectConfText(ctx, name, conf)) continue
                val deadline = System.currentTimeMillis() + 6000
                var up = false
                while (System.currentTimeMillis() < deadline) {
                    if (try { WgDirect.isLive(ctx) } catch (_: Exception) { false }) { up = true; break }
                    Thread.sleep(500)
                }
                if (!up) {
                    try { WgDirect.disconnectAll(ctx) } catch (_: Exception) {}
                    continue
                }
                if (tunExitProbe()) return Result.SUCCESS
                logProbeDead(ctx, "wireguard")
                try { WgDirect.disconnectAll(ctx) } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        return Result.FAILED
    }

    fun connectOpenVpnAny(activity: Activity): Result {
        // would otherwise request permission from this
        // worker thread and the result would be lost.
        if (android.net.VpnService.prepare(activity) != null) return Result.NEEDS_PERMISSION
        val profiles = try { OvpnStore.load(activity) } catch (_: Exception) { emptyList() }
        for (profile in profiles.take(MAX_TRIES)) {
            try {
                OvpnEngine.connect(activity, profile) {}
                val deadline = System.currentTimeMillis() + 10000
                var up = false
                while (System.currentTimeMillis() < deadline) {
                    if (try { OvpnEngine.isConnected } catch (_: Exception) { false }) { up = true; break }
                    Thread.sleep(500)
                }
                if (!up) {
                    try { OvpnEngine.disconnect() } catch (_: Exception) {}
                    Thread.sleep(500)
                    continue
                }
                if (tunExitProbe()) return Result.SUCCESS
                logProbeDead(activity, "openvpn")
                try { OvpnEngine.disconnect() } catch (_: Exception) {}
                Thread.sleep(500)
            } catch (_: Exception) {}
        }
        return Result.FAILED
    }

    fun connectV2rayAny(ctx: Context): Result {
        if (android.net.VpnService.prepare(ctx) != null) return Result.NEEDS_PERMISSION
        val links = ArrayList<String>()
        try {
            val prefs = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
            val seen = HashSet<String>()
            for (line in (prefs.getString("servers", "") ?: "").lines()) {
                val t = line.trim()
                if (t.isNotBlank() && seen.add(t)) links += t
            }
        } catch (_: Exception) {}
        for (link in links.take(MAX_TRIES)) {
            try {
                if (VpnClientService.isRunning) {
                    try {
                        val stop = Intent(ctx, VpnClientService::class.java).apply {
                            action = "STOP"
                            putExtra("stop_gen", VpnClientService.sessionGen)
                        }
                        ctx.startService(stop)
                    } catch (_: Exception) {}
                    Thread.sleep(800)
                }
                try {
                    ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE)
                        .edit().putBoolean("smart_owns_vpn", false).apply()
                } catch (_: Exception) {}
                if (!CoreInit.init(ctx.applicationContext)) continue
                val vpnConfig = VpnConfigBuilder.build(ctx, link) ?: continue
                VpnClientService.emergencyConfig = null
                val intent = Intent(ctx, VpnClientService::class.java).apply {
                    putExtra("config", vpnConfig)
                    putExtra("name", XraySpeedtest.displayName(link))
                    putExtra("link", link)
                    putExtra("session_tag", "v2ray")
                }
                ctx.startService(intent)
                val deadline = System.currentTimeMillis() + 8000
                var up = false
                while (System.currentTimeMillis() < deadline) {
                    if (VpnClientService.isRunning &&
                        VpnClientService.sessionTag == "v2ray") { up = true; break }
                    Thread.sleep(500)
                }
                if (up) {
                    // Hold briefly: a config that dies at once
                    // does not count as "connected".
                    Thread.sleep(2000)
                    if (VpnClientService.isRunning &&
                        VpnClientService.sessionTag == "v2ray") {
                        // session's own socks port before
                        // success; a dead tunnel falls
                        // through to the stop + next config.
                        val port = VpnClientService.sessionSocksPort.takeIf { it > 0 }
                            ?: VpnConfigBuilder.socksPort(ctx)
                        if (port <= 0) return Result.SUCCESS
                        if (ConnVerify.socksProbe(ctx, port)) return Result.SUCCESS
                        logProbeDead(ctx, "v2ray")
                    }
                }
                try {
                    val stop = Intent(ctx, VpnClientService::class.java).apply {
                        action = "STOP"
                        putExtra("stop_gen", VpnClientService.sessionGen)
                    }
                    ctx.startService(stop)
                } catch (_: Exception) {}
                Thread.sleep(300)
            } catch (_: Exception) {}
        }
        return Result.FAILED
    }
}
