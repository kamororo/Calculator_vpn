package com.renamer

import android.content.Context
import android.content.Intent
import android.net.VpnService
import java.util.concurrent.Executors

/**
 * Headless emergency connect, for Smart Connect's
 * "emergency" target. This deliberately duplicates
 * EmergencyActivity's fetch/ping/start logic instead
 * of refactoring that page: the emergency page is
 * user-verified territory and must not be touched.
 *
 * Privacy rules, identical to the emergency page:
 * configs are fetched fresh every time, kept in
 * memory only, NEVER saved to SharedPreferences and
 * NEVER added to the v2ray server list, and the VPN
 * runs under the generic name "emergency connect",
 * never the real config name.
 */
object EmergencyConnect {
    /** Fresh fetch + ping, best first. Shared by
     *  connect() and by the double-chain engine, so
     *  an emergency hop resolves exactly like the
     *  emergency page does. In memory only. */
    fun sortedConfigs(ctx: Context): List<String> {
        if (!CoreInit.init(ctx)) return emptyList()
        val configs = fetch(ctx)
        if (configs.isEmpty()) return emptyList()
        return pingAndSort(configs)
    }

    fun connect(ctx: Context): Boolean {
        try {
            val sorted = sortedConfigs(ctx)
            // Try in ping order, up to 3 attempts, with
            // a 6s isRunning check after each start —
            // the same fallback the emergency page uses.
            for (i in 0 until minOf(3, sorted.size)) {
                if (start(ctx, sorted[i])) {
                    val deadline = System.currentTimeMillis() + 6000
                    while (System.currentTimeMillis() < deadline) {
                        if (VpnClientService.isRunning) return true
                        Thread.sleep(500)
                    }
                    // Not actually up: stop and try next.
                    try {
                        val stop = Intent(ctx, VpnClientService::class.java).apply { action = "STOP" }
                        ctx.startService(stop)
                    } catch (_: Exception) {}
                    try { ctx.stopService(Intent(ctx, VpnClientService::class.java)) } catch (_: Exception) {}
                    VpnClientService.isRunning = false
                    VpnClientService.emergencyConfig = null
                }
            }
            return false
        } catch (_: Throwable) {
            return false
        }
    }

    private fun fetch(ctx: Context): List<String> {
        val urls = mutableListOf(EmergencyActivity.EMERGENCY_SUB_URL)
        urls += EmergencyActivity.customSubs(ctx)
        // Parallel fetch, 8s per URL, 12s total (was:
        // sequential, 15s each) — same fix as the
        // emergency page's own fetch.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, urls.size))
        try {
            val futures = urls.map { url ->
                pool.submit(java.util.concurrent.Callable<List<String>> {
                    val seen = mutableSetOf<String>()
                    val out = mutableListOf<String>()
                    try {
                        val conn = java.net.URL(url).openConnection()
                            as java.net.HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        var body = conn.inputStream.bufferedReader().readText().trim()
                        if (!body.contains("://")) {
                            try {
                                var b = body.replace('-', '+').replace('_', '/')
                                while (b.length % 4 != 0) b += "="
                                body = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) {}
                        }
                        for (line in body.lines()) {
                            val l = line.trim()
                            if (l.isBlank() || !l.contains("://")) continue
                            if (XraySpeedtest.isGroup(l)) continue
                            if (seen.add(XraySpeedtest.dedupKey(l))) out += l
                        }
                    } catch (_: Exception) {}
                    out
                })
            }
            val deadline = System.currentTimeMillis() + 12_000
            val perUrl = mutableListOf<List<String>>()
            for (f in futures) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) { perUrl.add(emptyList()); continue }
                perUrl.add(try { f.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: Exception) { emptyList() })
            }
            val seen = mutableSetOf<String>()
            val out = mutableListOf<String>()
            for (list in perUrl) {
                for (l in list) {
                    if (seen.add(XraySpeedtest.dedupKey(l))) out += l
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** Bounded sort, same fix as the emergency
     *  page: completion order under a 25s deadline;
     *  healthy first (sorted), unmeasured appended,
     *  proven-dead dropped. */
    private fun pingAndSort(configs: List<String>): List<String> {
        val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<Long, String>>(pool)
        try {
            for (cfg in configs) {
                completion.submit(java.util.concurrent.Callable {
                    val d = try {
                        XraySpeedtest.measureOneWithError(cfg).first
                    } catch (_: Throwable) {
                        -1L
                    }
                    d to cfg
                })
            }
            val deadline = System.currentTimeMillis() + 25_000
            val healthy = mutableListOf<Pair<Long, String>>()
            val finished = mutableSetOf<String>()
            var remainingTasks = configs.size
            while (remainingTasks > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val future = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                remainingTasks--
                val result = try { future.get() } catch (_: Exception) { null }
                if (result != null) {
                    finished += result.second
                    if (result.first >= 0) healthy += result
                }
            }
            val out = healthy.sortedBy { it.first }.map { it.second }.toMutableList()
            for (cfg in configs) {
                if (cfg !in finished) out += cfg
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun start(ctx: Context, cfg: String): Boolean {
        val vpnConfig = VpnConfigBuilder.build(ctx, cfg) ?: return false
        VpnClientService.emergencyConfig = cfg
        val intent = Intent(ctx, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            putExtra("name", AppLanguage.t(ctx, "اتصال اضطراری", "Emergency connect"))
            // Smart Connect's emergency target is a
            // SMART session, not the emergency
            // section's: the emergency page must not
            // light up for it.
            putExtra("session_tag", "smart")
        }
        return try {
            ctx.startService(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
