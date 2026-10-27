package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.File

/**
 * Ongoing VPN status notification for VpnClientService
 * (v2ray / emergency connects).
 *
 * What it shows, per the user's request:
 * - the connected config's name (title),
 * - which kind it is (v2ray / emergency / WireGuard /
 *   OpenVPN — the kind label, not the config's secrets),
 * - the country/city of the config's IP (from the same
 *   cached geo lookup GeoFlag uses; never a live network
 *   call from the refresh loop),
 * - the current speed of the VPN tunnel.
 *
 * Speed measurement and its limits (read this before
 * trusting the number):
 * - It reads /proc/net/dev and sums receive + transmit
 *   bytes of the TUN interface (first line named exactly
 *   "tun0", otherwise the first line starting with "tun").
 *   The rate is (delta bytes / delta seconds / 1024),
 *   i.e. total tunnel throughput, down + up combined.
 * - It is NOT per-app and NOT per-config: it is all
 *   traffic currently flowing through the device's VPN
 *   tunnel. If another VPN were running at the same time
 *   (this app normally allows only one), the number
 *   would mix them.
 * - The first call only establishes a baseline and
 *   returns -1 (shown as "—"); the first real value
 *   appears on the next refresh (~3 seconds later).
 * - On any read/parse failure it returns -1 and the
 *   caller shows "—" instead of a number. It never
 *   throws and never blocks the UI thread (the service
 *   refreshes it from a background handler thread).
 *
 * Disconnected state: showDisconnected() CANCELS the
 * ongoing notification and leaves no notification
 * behind, so a stale "connected" notification can never
 * linger after a disconnect. Trade-off, stated plainly:
 * once disconnected there is no notification left to tap
 * "Connect" on — reconnecting is done by reopening the
 * app (or via Smart Connect). If a persistent
 * "disconnected, tap to connect" notification is wanted
 * later, it is a one-line change inside showDisconnected
 * (build a non-ongoing notification with only the
 * Connect action instead of cancelling).
 */
object VpnNotification {
    const val CHANNEL_ID = "vpn_status"
    const val NOTIF_ID = 3001

    const val ACTION_DISCONNECT = "com.renamer.VPN_DISCONNECT"
    const val ACTION_CONNECT = "com.renamer.VPN_CONNECT"
    const val ACTION_RESTART = "com.renamer.VPN_RESTART"

    private val speedLock = Any()
    private var lastBytes: Long = -1
    private var lastTimeMs: Long = 0

    /** Hosts for which a background GeoFlag.lookup has
     *  already been triggered this process lifetime, so
     *  the refresh loop never spams lookups. */
    private val lookupTriggered = mutableSetOf<String>()

    fun kindLabel(ctx: Context, kind: String): String = when (kind) {
        "emergency" -> AppLanguage.t(ctx, "اتصال اضطراری", "Emergency")
        "wireguard" -> "WireGuard"
        "openvpn" -> "OpenVPN"
        else -> "v2ray"
    }

    /** Live status line. kind is the DISPLAY label
     *  (see kindLabel). Unknown location is passed as
     *  "—" by the caller and shown as-is; unknown speed
     *  (speedKbps < 0) is shown as "—" too. */
    fun statusLine(speedKbps: Long, country: String, city: String, kind: String): String {
        val location = when {
            country.isBlank() -> null
            city.isBlank() -> country
            else -> "$country، $city"
        }
        val speed = if (speedKbps < 0) "—"
            else "$speedKbps کیلوبایت بر ثانیه"
        val parts = mutableListOf(kind)
        if (location != null) parts += location
        parts += speed
        return parts.joinToString(" • ")
    }

    /** Same line, in the user's selected language
     *  (English gets "KB/s" and a comma separator). */
    fun statusLine(ctx: Context, speedKbps: Long, country: String, city: String, kind: String): String {
        if (!AppLanguage.isEnglish(ctx)) return statusLine(speedKbps, country, city, kind)
        val location = when {
            country.isBlank() -> null
            city.isBlank() -> country
            else -> "$country, $city"
        }
        val speed = if (speedKbps < 0) "—" else "$speedKbps KB/s"
        val parts = mutableListOf(kind)
        if (location != null) parts += location
        parts += speed
        return parts.joinToString(" • ")
    }

    /** Current tunnel throughput in KB/s, or -1 when
     *  unknown (first call, or any read/parse failure).
     *  Safe to call from any thread; never throws. */
    fun currentSpeedKbps(): Long {
        return try {
            var rx = -1L
            var tx = -1L
            var firstTunRx = -1L
            var firstTunTx = -1L
            for (line in File("/proc/net/dev").readLines()) {
                val trimmed = line.trim()
                if (!trimmed.contains(":")) continue
                val iface = trimmed.substringBefore(":").trim()
                if (!iface.startsWith("tun")) continue
                val fields = trimmed.substringAfter(":").trim()
                    .split(Regex("\\s+"))
                if (fields.size < 9) continue
                val r = fields[0].toLongOrNull() ?: continue
                val t = fields[8].toLongOrNull() ?: continue
                if (firstTunRx < 0) { firstTunRx = r; firstTunTx = t }
                if (iface == "tun0") { rx = r; tx = t; break }
            }
            if (rx < 0) { rx = firstTunRx; tx = firstTunTx }
            if (rx < 0 || tx < 0) return -1
            val total = rx + tx
            val now = System.currentTimeMillis()
            synchronized(speedLock) {
                if (lastBytes < 0 || lastTimeMs <= 0) {
                    lastBytes = total
                    lastTimeMs = now
                    return -1
                }
                val dtSec = (now - lastTimeMs) / 1000.0
                val delta = total - lastBytes
                lastBytes = total
                lastTimeMs = now
                if (dtSec <= 0 || delta < 0) return -1
                (delta / dtSec / 1024).toLong()
            }
        } catch (_: Throwable) { -1 }
    }

    /** Cached geo result for a host, read DIRECTLY from
     *  the geo_cache JSON (same format GeoFlag writes:
     *  host -> "CC|Country|City"). Returns null when not
     *  cached yet. Never does a network call. */
    fun cachedLocation(ctx: Context, host: String?): Triple<String, String, String>? {
        if (host.isNullOrBlank()) return null
        return try {
            val raw = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                .getString("geo_cache_v2", "") ?: ""
            if (raw.isBlank()) return null
            val value = JSONObject(raw).optString(host, "")
            if (value.isBlank()) return null
            val parts = value.split("|")
            if (parts.size < 3) return null
            Triple(parts[0], parts[1], parts[2])
        } catch (_: Exception) { null }
    }

    /** Triggers a background GeoFlag.lookup for this host
     *  at most once per process, so a later refresh can
     *  show the country/city from cache. Never blocks. */
    fun ensureLookup(ctx: Context, host: String?) {
        if (host.isNullOrBlank()) return
        synchronized(lookupTriggered) {
            if (!lookupTriggered.add(host)) return
        }
        try { GeoFlag.lookup(ctx, host) { _, _, _ -> } } catch (_: Throwable) {}
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        AppLanguage.t(ctx, "وضعیت VPN", "VPN Status"),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        } catch (_: Throwable) {}
    }

    private fun actionIntent(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, VpnActionReceiver::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    /** Builds (but does not post) the connected
     *  notification. The service passes this to
     *  startForeground; showConnected posts it directly. */
    /** Egress measurement for the connected link,
     *  read directly from the geo_egress JSON. */
    fun egressLocation(ctx: Context, link: String?): Triple<String, String, String>? {
        if (link.isNullOrBlank()) return null
        return try { GeoFlag.egressFor(ctx, link) } catch (_: Exception) { null }
    }

    fun buildConnected(ctx: Context, kind: String, configName: String, host: String?, link: String? = null): Notification {
        ensureChannel(ctx)
        val label = kindLabel(ctx, kind)
        val title = configName.ifBlank { label }
        val cached = egressLocation(ctx, link) ?: cachedLocation(ctx, host)
        if (cached == null) ensureLookup(ctx, host)
        val country = cached?.second ?: "—"
        val city = cached?.third ?: ""
        val text = statusLine(ctx, currentSpeedKbps(), country, city, label)
        val openApp = Intent(ctx, VpnActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openFlags = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        val content = PendingIntent.getActivity(ctx, 0, openApp, openFlags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, AppLanguage.t(ctx, "قطع", "Disconnect"),
                actionIntent(ctx, ACTION_DISCONNECT, 1))
            .addAction(0, AppLanguage.t(ctx, "وصل", "Connect"),
                actionIntent(ctx, ACTION_CONNECT, 2))
            .addAction(0, AppLanguage.t(ctx, "ری‌استارت", "Restart"),
                actionIntent(ctx, ACTION_RESTART, 3))
            .build()
    }

    /** Posts/updates the ongoing connected notification. */
    fun showConnected(ctx: Context, kind: String, configName: String, host: String?, link: String? = null) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildConnected(ctx, kind, configName, host, link))
        } catch (_: Throwable) {}
    }

    /** Cancels the ongoing notification and leaves NO
     *  notification behind (see the class comment for
     *  the trade-off and the one-line alternative). */
    fun showDisconnected(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (_: Throwable) {}
    }
}
