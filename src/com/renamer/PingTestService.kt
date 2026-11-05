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
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ping test that keeps running when the screen turns off or the
 * user leaves the app: a foreground service (notification, channel
 * "ping") holding a PARTIAL_WAKE_LOCK for the duration of the test.
 *
 * Contract with SubscriptionActivity (all in prefs "renamer_subs"):
 *  input  - "ping_test_list": configs to test, newline-joined
 *           (already deduped and filtered by the activity).
 *           "sub_source_map": normalized dedup key -> sub url.
 *           "subs": sub urls, "sub_stats": previous per-sub stats.
 *  live   - "ping_healthy_live": healthy configs, newline-joined,
 *           appended in completion order as each one is found.
 *           "ping_live_sub": per-sub healthy counts (JSON
 *           url -> count), bumped per find, like the live list.
 *           "ping_progress_done" / "ping_progress_total" /
 *           "ping_progress_healthy": ints, updated per config.
 *  output - "ping_done": true when finished (or failed/stopped
 *           with an error in "ping_error"). "ping_running": true
 *           only while the test is actually running.
 *           "last_healthy": final healthy list, sorted by delay.
 *           "box2": same final list (original names; the activity
 *           applies the desired name on top when it resumes).
 *           "sub_stats": per-sub received/healthy JSON, updated.
 *           "ping_applied": false at start; the activity sets it
 *           true once it has applied the final result to the UI.
 *
 * The per-config measurement is the same v2rayNG logic as
 * SubscriptionActivity.measureOne: TCP pre-check for simple types,
 * then Libv2ray.measureOutboundDelay with the primary test URL.
 * XraySpeedtest.kt is NOT changed.
 */
class PingTestService : Service() {

    companion object {
        const val PREFS = "renamer_subs"
        const val CHANNEL_ID = "ping"
        const val NOTIF_ID = 1001
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applySpeedtestPrefs() {
        try {
            val sp = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            XraySpeedtest.testUrlOverride = sp.getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            XraySpeedtest.concurrencyOverride = sp.getString("delay_test_concurrency", null)?.toIntOrNull()
        } catch (_: Exception) {}
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applySpeedtestPrefs()
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopped = false
        startForegroundWithNotification(0, 0)
        val wl = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renamer:ping")
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
                    L("تست پینگ", "Ping test"),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
            val text = if (total > 0)
                L("در حال تست پینگ... $done از $total", "Testing ping... $done of $total")
            else
                L("در حال تست پینگ...", "Testing ping...")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setContentTitle(L("تست پینگ اشتراک‌ها", "Subscription ping test"))
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
        var pool: java.util.concurrent.ExecutorService? = null
        try {
            prefs().edit()
                .putBoolean("ping_running", true)
                .putBoolean("ping_done", false)
                .putBoolean("ping_applied", false)
                .putString("ping_error", "")
                .putString("ping_live_sub", "{}")
                .apply()
            val list = (prefs().getString("ping_test_list", "") ?: "")
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            val total = list.size
            prefs().edit()
                .putInt("ping_progress_total", total)
                .putInt("ping_progress_done", 0)
                .putInt("ping_progress_healthy", 0)
                .putString("ping_healthy_live", "")
                .putString("ping_live_sub", "{}")
                .apply()
            val sourceMap = mutableMapOf<String, String>()
            try {
                val j = JSONObject(prefs().getString("sub_source_map", "{}") ?: "{}")
                for (key in j.keys()) sourceMap[key] = j.optString(key, "")
            } catch (_: Throwable) {}
            if (total == 0) {
                finishWith(emptyList(), null)
                return
            }
            if (!CoreInit.init(applicationContext)) {
                finishWith(emptyList(), CoreInit.lastError ?: "Xray init failed")
                return
            }
            val done = AtomicInteger(0)
            val healthyCount = AtomicInteger(0)
            val results = mutableListOf<Pair<String, Long>>()
            val allDelays = mutableListOf<Pair<String, Long>>()
            val resultsLock = Any()
            val liveLock = Any()
            pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            val futures = list.map { cfg ->
                pool.submit {
                    if (stopped) return@submit
                    var delay = -1L
                    try { delay = measureOne(cfg) } catch (_: Throwable) { delay = -1L }
                    if (stopped) return@submit
                    // 2.45: record EVERY attempt (healthy
                    // and failed) for the central log.
                    synchronized(resultsLock) { allDelays += cfg to delay }
                    if (delay >= 0) {
                        synchronized(resultsLock) { results += cfg to delay }
                        healthyCount.incrementAndGet()
                        // Append IMMEDIATELY, so the activity can
                        // show this config the moment it is known
                        // healthy, without waiting for the test end.
                        synchronized(liveLock) {
                            val cur = prefs().getString("ping_healthy_live", "") ?: ""
                            val next = if (cur.isBlank()) cfg else "$cur\n$cfg"
                            prefs().edit()
                                .putString("ping_healthy_live", next)
                                .apply()
                        }
                    }
                    val d = done.incrementAndGet()
                    val h = healthyCount.get()
                    prefs().edit()
                        .putInt("ping_progress_done", d)
                        .putInt("ping_progress_healthy", h)
                        .apply()
                    if (d % 10 == 0 || d == total) {
                        try { startForegroundWithNotification(d, total) } catch (_: Throwable) {}
                    }
                }
            }
            for (f in futures) {
                try { f.get() } catch (_: Throwable) {}
                if (stopped) break
            }
            pool.shutdown()
            if (stopped) return
            val sorted = synchronized(resultsLock) { results.sortedBy { it.second } }
            // 2.45: persist every measured delay so a
            // later connect line can show this
            // config's last ping (or its failure).
            try {
                val dj = org.json.JSONObject()
                synchronized(resultsLock) {
                    for ((c, d) in allDelays) dj.put(XraySpeedtest.dedupKey(c), d)
                }
                prefs().edit().putString("ping_delays", dj.toString()).apply()
            } catch (_: Exception) {}
            finishWith(sorted.map { it.first }, null)
        } catch (t: Throwable) {
            try {
                prefs().edit()
                    .putBoolean("ping_done", true)
                    .putBoolean("ping_running", false)
                    .putString("ping_error", t.message ?: t.javaClass.simpleName)
                    .apply()
            } catch (_: Throwable) {}
        } finally {
            try { pool?.shutdownNow() } catch (_: Throwable) {}
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Throwable) {}
            wakeLock = null
            try { stopForeground(true) } catch (_: Throwable) {}
            stopSelf()
        }
    }

    /** Writes the final sorted list, per-sub healthy stats, and
     *  the completion flags. Called only on a clean finish. */
    private fun finishWith(healthySorted: List<String>, error: String?) {
        try {
            // Per-sub healthy counts, from the persisted source map.
            val source = mutableMapOf<String, String>()
            try {
                val j = JSONObject(prefs().getString("sub_source_map", "{}") ?: "{}")
                for (key in j.keys()) source[key] = j.optString(key, "")
            } catch (_: Throwable) {}
            val healthyPerSub = mutableMapOf<String, Int>()
            for (cfg in healthySorted) {
                val src = source[dedupKey(cfg)] ?: continue
                healthyPerSub[src] = (healthyPerSub[src] ?: 0) + 1
            }
            // Keep previous received counts, replace healthy counts.
            val oldStats = mutableMapOf<String, Pair<Int, Int>>()
            try {
                val j = JSONObject(prefs().getString("sub_stats", "{}") ?: "{}")
                for (key in j.keys()) {
                    val o = j.optJSONObject(key) ?: continue
                    oldStats[key] = o.optInt("received", 0) to o.optInt("healthy", 0)
                }
            } catch (_: Throwable) {}
            val subs = (prefs().getString("subs", "") ?: "")
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            val statsJson = JSONObject()
            for (url in subs) {
                val o = JSONObject()
                o.put("received", oldStats[url]?.first ?: 0)
                o.put("healthy", healthyPerSub[url] ?: 0)
                statsJson.put(url, o)
            }
            val liveSubJson = JSONObject()
            for ((u, c) in healthyPerSub) liveSubJson.put(u, c)
            prefs().edit()
                .putString("last_healthy", healthySorted.joinToString("\n"))
                .putString("box2", healthySorted.joinToString("\n"))
                .putString("sub_stats", statsJson.toString())
                .putString("ping_live_sub", liveSubJson.toString())
                .putInt("ping_progress_done", prefs().getInt("ping_progress_total", 0))
                .putInt("ping_progress_healthy", healthySorted.size)
                .putBoolean("ping_done", true)
                .putBoolean("ping_running", false)
                .putString("ping_error", error ?: "")
                .apply()
                AppLog.append(this, "ping",
                    "تست پینگ تمام شد؛ سالم: ${healthySorted.size} از ${prefs().getInt("ping_progress_total", 0)}." + if (!error.isNullOrBlank()) " خطا: $error." else "",
                    "Ping test done; healthy: ${healthySorted.size} of ${prefs().getInt("ping_progress_total", 0)}." + if (!error.isNullOrBlank()) " Error: $error." else "")
        } catch (_: Throwable) {
            try {
                prefs().edit()
                    .putBoolean("ping_done", true)
                    .putBoolean("ping_running", false)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        stopped = true
        try {
            // If the worker is still going (stop button / system
            // kill), mark the test as no longer running, but never
            // overwrite a finished result.
            if (prefs().getBoolean("ping_done", false) != true) {
                prefs().edit().putBoolean("ping_running", false).apply()
            }
        } catch (_: Throwable) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null
        worker = null
        super.onDestroy()
    }

    private fun measureOne(cfg: String): Long {
        if (XraySpeedtest.isSimpleType(cfg)) {
            val srv = XraySpeedtest.parseServer(cfg) ?: return -1
            if (XraySpeedtest.tcpConnectTime(srv.host, srv.port) < 0) return -1
        }
        val config = XraySpeedtest.buildSpeedtestConfig(cfg) ?: return -1
        return try {
            Libv2ray.measureOutboundDelay(config, XraySpeedtest.TEST_URL)
        } catch (_: Throwable) { -1L }
    }

    /** Same normalized key as SubscriptionActivity.dedupKey, so the
     *  service's source lookup matches the activity's keys exactly. */
    private fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(
                    String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8)
                )
                j.remove("ps")
                canonicalJson(j)
            } else {
                normalizeUri(cfg)
            }
        } catch (_: Exception) { cfg.substringBefore("#") }
    }

    private fun canonicalJson(j: JSONObject): String {
        val keys = mutableListOf<String>()
        for (k in j.keys()) keys += k
        keys.sort()
        val sb = StringBuilder("{")
        for ((i, k) in keys.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append(JSONObject.quote(k)).append(":")
            when (val v = j.get(k)) {
                is String -> sb.append(JSONObject.quote(v))
                is JSONObject -> sb.append(canonicalJson(v))
                is org.json.JSONArray -> sb.append(v.toString())
                JSONObject.NULL -> sb.append("null")
                else -> sb.append(v.toString())
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun normalizeUri(cfg: String): String {
        return try {
            val noFrag = cfg.substringBefore("#")
            val uri = java.net.URI(noFrag)
            val query = uri.rawQuery
            val sortedQuery = if (query.isNullOrBlank()) null else
                query.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
            val sb = StringBuilder()
            sb.append(uri.scheme).append("://")
            if (uri.rawUserInfo != null) sb.append(uri.rawUserInfo).append("@")
            if (uri.host != null) sb.append(uri.host)
            if (uri.port != -1) sb.append(":").append(uri.port)
            if (uri.rawPath != null) sb.append(uri.rawPath)
            if (sortedQuery != null) sb.append("?").append(sortedQuery)
            sb.toString()
        } catch (_: Exception) {
            cfg.substringBefore("#")
        }
    }
}
