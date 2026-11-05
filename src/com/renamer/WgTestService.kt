package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.59: WireGuard tunnel test WITHOUT connecting.
 *
 * The user asked for WG health without cutting a live VPN.
 * The trick is the same one the v2ray ping test uses: a
 * SEPARATE Xray core (Libv2ray.measureOutboundDelay)
 * runs the tunnel's own outbound and fetches the test
 * URL through it. No system VPN is touched, so a live
 * session keeps running untouched.
 *
 * Three deliberate limits, all honest:
 *  - AmneziaWG tunnels are NOT probed: the Xray core
 *    does not support Amnezia junk parameters, so a
 *    probe without junk would fail even for a healthy
 *    Amnezia tunnel and report a false DEAD. They are
 *    counted as untestable, never as dead.
 *  - A tunnel that is LIVE right now is counted
 *    healthy WITHOUT probing: it is connected, so it
 *    is alive by definition, and a second handshake
 *    from the same key must never disturb it.
 *  - OpenVPN profiles cannot be tested this way
 *    (they need the ovpn3 client, not an Xray
 *    outbound), so they stay count + last-state only.
 *
 * Prefs "renamer_wg_test" (prepared by the activity,
 * like the ping flow):
 *  input  - nothing; the service reads "wg_list" from
 *           "renamer_wg" itself.
 *  live   - "wg_test_progress_done" / "wg_test_total" /
 *           "wg_test_healthy" / "wg_test_untestable".
 *  output - "wg_test_done": true when finished.
 *           "wg_test_running": true only while running.
 *           "wg_test_results": JSON name -> true/false
 *           for probed tunnels (live ones included as
 *           true). "wg_test_untestable_names": JSON
 *           array of Amnezia names. "wg_test_time":
 *           finish clock time. "wg_test_error".
 */
class WgTestService : Service() {

    companion object {
        const val PREFS = "renamer_wg_test"
        const val CHANNEL_ID = "wgtest"
        const val NOTIF_ID = 1002
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopped = false
        startForegroundWithNotification(0, 0)
        val wl = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renamer:wgtest")
        } catch (_: Throwable) { null }
        wakeLock = wl
        try { wl?.acquire() } catch (_: Throwable) {}
        worker = Thread { runTest() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(done: Int, total: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    L("تست وایرگارد", "WireGuard test"),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
            val text = if (total > 0)
                L("در حال تست وایرگارد... $done از $total", "Testing WireGuard... $done of $total")
            else
                L("در حال تست وایرگارد...", "Testing WireGuard...")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setContentTitle(L("تست تونل‌های وایرگارد", "WireGuard tunnel test"))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun runTest() {
        try {
            if (!CoreInit.init(applicationContext)) {
                finishError("core init failed")
                return
            }
            val wgPrefs = getSharedPreferences("renamer_wg", MODE_PRIVATE)
            val arr = try {
                JSONArray(wgPrefs.getString("wg_list", "[]") ?: "[]")
            } catch (_: Exception) { JSONArray() }
            val live = try {
                WgDirect.liveTunnelNames(applicationContext)
            } catch (_: Exception) { emptySet<String>() }
            val results = JSONObject()
            val untestable = JSONArray()
            var done = 0
            var healthy = 0
            val total = arr.length()
            for (i in 0 until arr.length()) {
                if (stopped) break
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val conf = o.optString("conf", "")
                if (name.isBlank()) continue
                when {
                    name in live || WgDirect.ifaceNameOf(name) in live -> {
                        // Live tunnel: healthy by
                        // definition, never probed.
                        results.put(name, true)
                        healthy++
                    }
                    WgDirect.isAwgConf(conf) -> {
                        // Amnezia junk is not supported
                        // by the Xray core: probing would
                        // falsely report a healthy tunnel
                        // as dead. Untestable, not dead.
                        untestable.put(name)
                    }
                    else -> {
                        val ok = probe(name)
                        results.put(name, ok)
                        if (ok) healthy++
                    }
                }
                done++
                prefs().edit()
                    .putInt("wg_test_progress_done", done)
                    .putInt("wg_test_healthy", healthy)
                    .putInt("wg_test_untestable", untestable.length())
                    .apply()
                startForegroundWithNotification(done, total)
            }
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            prefs().edit()
                .putString("wg_test_results", results.toString())
                .putString("wg_test_untestable_names", untestable.toString())
                .putInt("wg_test_total", total)
                .putInt("wg_test_progress_done", done)
                .putInt("wg_test_healthy", healthy)
                .putInt("wg_test_untestable", untestable.length())
                .putString("wg_test_time", time)
                .putBoolean("wg_test_done", true)
                .putBoolean("wg_test_running", false)
                .putString("wg_test_error", "")
                .apply()
            AppLog.append(this, "wireguard",
                "تست تونل‌ها تمام شد؛ سالم: $healthy از $done.",
                "Tunnel test done; healthy: $healthy of $done.")
        } catch (t: Throwable) {
            finishError(t.message)
        } finally {
            try { wakeLock?.release() } catch (_: Throwable) {}
            stopSelf()
        }
    }

    /** One tunnel through its own Xray WG outbound,
     *  same shape as XraySpeedtest's speedtest config. */
    private fun probe(name: String): Boolean {
        return try {
            val ob = ChainEngine.wgOutbound(applicationContext, name, "proxy") ?: return false
            val outbounds = JSONArray().put(ob)
            outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
            outbounds.put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            val config = JSONObject().apply {
                put("log", JSONObject().apply { put("loglevel", "warning") })
                put("outbounds", outbounds)
                put("routing", JSONObject().apply {
                    put("domainStrategy", "IPIfNonMatch")
                    put("rules", JSONArray())
                    put("final", "proxy")
                })
            }.toString()
            Libv2ray.measureOutboundDelay(config, XraySpeedtest.TEST_URL) >= 0
        } catch (_: Throwable) { false }
    }

    private fun finishError(err: String?) {
        try {
            prefs().edit()
                .putBoolean("wg_test_running", false)
                .putBoolean("wg_test_done", true)
                .putString("wg_test_error", err ?: "")
                .apply()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }
}
