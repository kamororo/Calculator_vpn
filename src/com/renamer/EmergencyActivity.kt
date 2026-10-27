package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Emergency quick connect.
 *
 * Visible UI: one big button, plus one small info line
 * under it (connecting time, then the "connected, tap
 * to see ping" line, then the measured ping). Nothing
 * else is shown: no subscription link, no config list,
 * no config names, no ping numbers during the search,
 * no counts, no status text, no back button (system
 * Back closes the page).
 *
 * On tap, every time, in the background:
 *  1. the emergency subscriptions are fetched fresh
 *     (the main link below, plus any custom links the
 *     user added in the hidden settings),
 *  2. their configs are ping-tested (exactly like
 *     v2rayNG), without ever being saved to the app's
 *     server list or shown anywhere,
 *  3. the app connects to the lowest-ping healthy config.
 * If nothing is healthy, the button simply returns to
 * its off state; no detail is shown.
 *
 * The emergency configs live only in memory during this
 * flow. They are never written to SharedPreferences,
 * never added to the v2ray server list, and the VPN is
 * started under the generic name "اتصال اضطراری", never
 * under the real config name, so opening the v2ray page
 * later cannot reveal which emergency config was used.
 *
 * Hidden settings: draw one full circle with a finger,
 * then tap the middle of that circle. A password page
 * opens (NOT the calculator from the first page): it
 * only says "برای ورود به تنظیمات رمز را وارد کنید".
 * Password is SETTINGS_PASSWORD below. One wrong entry locks this
 * settings section completely ("این بخش برای شما قفل
 * شد") until the app's data is cleared, which wipes
 * the lock flag from SharedPreferences.
 */
class EmergencyActivity : Activity() {
    companion object {
        // The main emergency subscription, as supplied.
        // It is shown in the hidden settings, and it is
        // never deletable there.
        const val EMERGENCY_SUB_URL =
            "https://raw.githubusercontent.com/inaz266/In-az-26/refs/heads/main/filtee.txt"
        const val SETTINGS_PASSWORD = "000000000"

        fun customSubs(ctx: android.content.Context): MutableList<String> {
            return ctx.getSharedPreferences("emergency_settings", MODE_PRIVATE)
                .getString("custom_subs", "")?.lines()
                ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var btn: Button
    private lateinit var infoText: TextView
    private var busy = false
    private var pendingBest: String? = null
    private val handler = Handler(Looper.getMainLooper())
    // Ordered healthy configs for the current emergency
    // attempt, best first. In memory only, never saved.
    private var pendingSorted: List<String> = emptyList()
    private var pendingIndex = 0
    // Guards the 6-second retry check: cleared on manual
    // disconnect, on success, and when attempts run out,
    // so a stale check can never reconnect by itself.
    private var emergencyAttemptActive = false
    private var emergencyRetryRunnable: Runnable? = null

    // --- Hidden circle gesture state ---
    private val points = mutableListOf<Pair<Float, Float>>()
    private var circleDrawn = false
    private var circleCx = 0f
    private var circleCy = 0f
    private var circleRadius = 0f
    private var circleTime = 0L
    private var centerTapCandidate = false
    private var centerTapDownX = 0f
    private var centerTapDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Theme.bg(this@EmergencyActivity))
            setPadding(32, 32, 32, 32)
        }
        btn = Button(this).apply {
            text = L("اتصال اضطراری", "Emergency connect")
            textSize = 20f
            setOnClickListener { onButton() }
        }
        val size = (220 * resources.displayMetrics.density).toInt()
        btn.layoutParams = LinearLayout.LayoutParams(size, size)
        root.addView(btn)
        infoText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Theme.subFg(this@EmergencyActivity))
            setPadding(0, 32, 0, 0)
            visibility = android.view.View.GONE
        }
        root.addView(infoText)
        setContentView(root)
        root.fitsSystemWindows = true
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    // --- Gesture: intercept touches at activity level so
    // drawing works anywhere, without eating button taps
    // except for the deliberate centre tap. ---
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val dp = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (circleDrawn && System.currentTimeMillis() - circleTime > 15000) {
                    circleDrawn = false
                }
                if (circleDrawn) {
                    val dx = ev.x - circleCx
                    val dy = ev.y - circleCy
                    val r = circleRadius * 0.6f
                    if (dx * dx + dy * dy < r * r) {
                        centerTapCandidate = true
                        centerTapDownX = ev.x
                        centerTapDownY = ev.y
                    } else {
                        // A tap elsewhere cancels the gesture.
                        circleDrawn = false
                        centerTapCandidate = false
                    }
                }
                points.clear()
                points.add(ev.x to ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!centerTapCandidate) points.add(ev.x to ev.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (centerTapCandidate) {
                    val moved = hypot(ev.x - centerTapDownX, ev.y - centerTapDownY)
                    centerTapCandidate = false
                    circleDrawn = false
                    points.clear()
                    if (ev.actionMasked == MotionEvent.ACTION_UP && moved < 30 * dp) {
                        openSettingsGate()
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (evaluateCircle()) {
                        circleDrawn = true
                        circleTime = System.currentTimeMillis()
                    }
                }
                points.clear()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True if the recorded stroke is one full circle. */
    private fun evaluateCircle(): Boolean {
        if (points.size < 20) return false
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var sumX = 0f
        var sumY = 0f
        for ((x, y) in points) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sumX += x
            sumY += y
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w < 80 || h < 80) return false
        val ratio = w / h
        if (ratio < 0.4f || ratio > 2.5f) return false
        val cx = sumX / points.size
        val cy = sumY / points.size
        var sumR = 0f
        for ((x, y) in points) sumR += hypot(x - cx, y - cy)
        val avgR = sumR / points.size
        if (avgR < 40) return false
        // Total signed rotation around the centroid.
        var total = 0.0
        var prev = atan2((points[0].second - cy).toDouble(), (points[0].first - cx).toDouble())
        for (i in 1 until points.size) {
            val a = atan2((points[i].second - cy).toDouble(), (points[i].first - cx).toDouble())
            var d = a - prev
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
            total += d
            prev = a
        }
        if (abs(total) < 4.5) return false
        // Start and end should be near each other.
        val (fx, fy) = points.first()
        val (lx, ly) = points.last()
        if (hypot(fx - lx, fy - ly) > avgR) return false
        circleCx = cx
        circleCy = cy
        circleRadius = avgR
        return true
    }

    // --- Password gate for the hidden settings ---
    private fun emergencyPrefs() =
        getSharedPreferences("emergency_settings", MODE_PRIVATE)

    private fun openSettingsGate() {
        if (AppLock.isLocked(this) || emergencyPrefs().getBoolean("settings_locked", false)) {
            android.app.AlertDialog.Builder(this)
                .setMessage(AppLock.message(this))
                .setPositiveButton(L("بستن", "Close")) { _, _ ->
                    goToLockScreen()
                }
                .show()
            return
        }
        // A plain password page, NOT the calculator page:
        // it only says the sentence below.
        val input = EditText(this).apply {
            hint = L("رمز", "Password")
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setMessage(AppLanguage.t(this, "برای ورود به تنظیمات رمز را وارد کنید", "Enter the password to open settings"))
            .setView(input)
            .setPositiveButton(L("ورود", "Enter"), null)
            .setNegativeButton(L("لغو", "Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = input.text.toString().trim()
            if (entered.isBlank()) return@setOnClickListener
            if (entered == SETTINGS_PASSWORD) {
                dialog.dismiss()
                startActivity(Intent(this, EmergencySettingsActivity::class.java))
            } else {
                // One wrong entry locks the WHOLE app:
                // it becomes unusable until the creator's
                // entry password is entered on the lock
                // screen, or the app is uninstalled and
                // reinstalled (or its data cleared).
                emergencyPrefs().edit().putBoolean("settings_locked", true).apply()
                AppLock.lock(this)
                dialog.dismiss()
                android.app.AlertDialog.Builder(this)
                    .setMessage(AppLock.message(this))
                    .setPositiveButton(L("بستن", "Close")) { _, _ ->
                        goToLockScreen()
                    }
                    .show()
            }
        }
    }

    private fun goToLockScreen() {
        val intent = Intent(this, PinActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // --- Button + info line ---
    /** Glass button, same 220dp size as before: a
     *  translucent fill with a thin stroke ring. The
     *  ring is always a little stronger than the fill,
     *  and no state uses a harsh bright color. */
    private fun glassButton(fill: String, stroke: String, textColor: String) {
        btn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(fill))
            setStroke((3 * resources.displayMetrics.density).toInt(), Color.parseColor(stroke))
        }
        btn.setTextColor(Color.parseColor(textColor))
    }

    /** The button is "on" ONLY for an emergency
     *  session. A v2ray (or any other section's)
     *  session used to light this button up, because
     *  this only checked isRunning — that cross-talk
     *  is the bug being fixed. */
    private fun isEmergencySession(): Boolean =
        VpnClientService.isRunning && VpnClientService.sessionTag == "emergency"

    private fun refreshButton() {
        if (isEmergencySession()) {
            glassButton("#E8F5E9", "#66BB6A", "#1B5E20")
            btn.text = L("قطع اتصال اضطراری", "Disconnect emergency")
        } else {
            glassButton("#FDE8E8", "#E57373", "#333333")
            btn.text = L("اتصال اضطراری", "Emergency connect")
        }
    }

    private fun refreshAll() {
        refreshButton()
        if (isEmergencySession() && VpnClientService.emergencyConfig != null) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد، کلیک کنید برای دیدن پینگ", "Connected, tap to see ping")
            infoText.isClickable = true
            infoText.setOnClickListener { measurePing() }
        } else if (isEmergencySession()) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد", "Connected")
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        } else if (VpnClientService.isRunning) {
            // Another section's session: say so
            // plainly, but never claim emergency
            // itself is connected.
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("اتصال «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.", "A «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» connection is active; stop it first.")
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        } else if (!busy) {
            infoText.visibility = android.view.View.GONE
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        }
    }

    private fun measurePing() {
        val cfg = VpnClientService.emergencyConfig ?: return
        infoText.text = L("در حال اندازه‌گیری پینگ...", "Measuring ping...")
        infoText.isClickable = false
        Thread {
            val d = try {
                if (!CoreInit.init(applicationContext)) -1L
                else XraySpeedtest.measureOneWithError(cfg).first
            } catch (_: Throwable) {
                -1L
            }
            runOnUiThread {
                infoText.text = if (d >= 0) L("وصل شد، پینگ: ", "Connected, ping: ") + "$d ms"
                                else L("وصل شد، پینگ: -1 ms", "Connected, ping: -1 ms")
                infoText.isClickable = true
                infoText.setOnClickListener { measurePing() }
            }
        }.start()
    }

    private fun onButton() {
        if (busy) return
        if (VpnClientService.isRunning && !isEmergencySession()) {
            // Another section's session: NEVER stop
            // it from here. Just say which one it is.
            infoText.visibility = android.view.View.VISIBLE
            infoText.isClickable = false
            infoText.setOnClickListener(null)
            infoText.text = L("اتصال «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.", "A «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» connection is active; stop it first.")
            return
        }
        if (isEmergencySession()) {
            emergencyAttemptActive = false
            emergencyRetryRunnable?.let { handler.removeCallbacks(it) }
            emergencyRetryRunnable = null
            pendingSorted = emptyList()
            val stopIntent = Intent(this, VpnClientService::class.java).apply {
                action = "STOP"
            }
            try { startService(stopIntent) } catch (_: Exception) {}
            try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.currentName = null
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
            refreshAll()
            return
        }
        busy = true
        glassButton("#FFFDE7", "#FFE082", "#333333")
        infoText.visibility = android.view.View.VISIBLE
        infoText.isClickable = false
        infoText.setOnClickListener(null)
        infoText.text = L("در حال اتصال به بهترین کانفیگ...", "Connecting to the best config...")
        Thread {
            try {
                val configs = fetchEmergencyConfigs()
                if (configs.isEmpty()) {
                    runOnUiThread { busy = false; refreshAll() }
                    return@Thread
                }
                // Approximate time, from the fetched count
                // and the ping-test time. The count itself
                // is never shown, only the time.
                val batches = (configs.size + XraySpeedtest.poolSize() - 1) /
                    XraySpeedtest.poolSize()
                val estSeconds = (batches * 3 + 5).coerceAtLeast(10)
                val estText = if (estSeconds < 60) L("تقریبا ", "About ") + "$estSeconds " + L("ثانیه", "seconds")
                              else L("تقریبا ", "About ") + "${(estSeconds + 59) / 60} " + L("دقیقه", "minutes")
                runOnUiThread {
                    infoText.text = L("در حال اتصال به بهترین کانفیگ... ", "Connecting to the best config... ") + estText
                }
                val sorted = pingAndSort(configs)
                runOnUiThread {
                    busy = false
                    if (sorted.isEmpty()) {
                        refreshAll()
                        return@runOnUiThread
                    }
                    // Try in ping order, up to 3 attempts:
                    // if a connect does not actually come
                    // up, the next best config is tried.
                    pendingSorted = sorted
                    tryEmergencyAt(0)
                }
            } catch (_: Exception) {
                runOnUiThread { busy = false; refreshAll() }
            }
        }.start()
    }

    /** Fetch fresh from the main link + every custom link.
     *  Returns deduped configs, in memory only. */
    private fun fetchEmergencyConfigs(): List<String> {
        if (!CoreInit.init(applicationContext)) return emptyList()
        val urls = mutableListOf(EMERGENCY_SUB_URL)
        urls += customSubs(this)
        // Fetch all subscription URLs IN PARALLEL
        // (was: one by one, 15s timeout each, so
        // several slow subs stacked into a long
        // dead-looking wait before the first connect
        // attempt). 8s per URL, 12s for all of them
        // together; whatever arrived in time is used.
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

    /** Ping all configs and return the healthy ones,
     *  best (lowest delay) first. Configs themselves
     *  are never logged, shown, or saved.
     *  BOUNDED: results are taken in completion
     *  order under a 25s global deadline (was: waited
     *  in list order with NO timeout, so one hung
     *  config stalled the whole sort and the tap
     *  looked dead). Finished healthy configs come
     *  first, sorted; configs still unmeasured at
     *  the deadline are appended after them (they
     *  may still work — the connect attempts will
     *  prove it); proven-dead (-1) ones are dropped. */
    private fun pingAndSort(configs: List<String>): List<String> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(XraySpeedtest.poolSize())
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<Long, String>>(pool)
        try {
            for (cfg in configs) {
                completion.submit(java.util.concurrent.Callable {
                    val d = try { XraySpeedtest.measureOneWithError(cfg).first }
                        catch (_: Throwable) { -1L }
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

    /** Start attempt [index] of the current ordered list
     *  (max 3 attempts). Runs on the UI thread. */
    private fun tryEmergencyAt(index: Int) {
        if (index >= pendingSorted.size || index >= 3) {
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            refreshAll()
            return
        }
        pendingIndex = index
        pendingBest = pendingSorted[index]
        emergencyAttemptActive = true
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            startEmergencyVpn(pendingSorted[index])
        }
    }

    private fun startEmergencyVpn(cfg: String) {
        val vpnConfig = VpnConfigBuilder.build(this, cfg) ?: run {
            // This config cannot even be built: try the
            // next best one instead of giving up.
            if (emergencyAttemptActive) tryEmergencyAt(pendingIndex + 1)
            else refreshAll()
            return
        }
        VpnClientService.emergencyConfig = cfg
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            // Generic name only, never the real config name.
            putExtra("name", L("اتصال اضطراری", "Emergency connect"))
            putExtra("session_tag", "emergency")
        }
        startService(intent)
        handler.postDelayed({ refreshAll() }, 1500)
        // Accuracy check: if the VPN is not actually up
        // after 6 seconds, stop and try the next best
        // config (up to 3 attempts total). Silent, as
        // before: no config is ever named or shown.
        emergencyRetryRunnable?.let { handler.removeCallbacks(it) }
        val attemptIndex = pendingIndex
        val runnable = Runnable {
            if (!emergencyAttemptActive) return@Runnable
            if (VpnClientService.isRunning) {
                // Success ONLY if the running session
                // is emergency's own; another section
                // taking over is not our success, and
                // we must not keep trying over it.
                emergencyAttemptActive = false
                refreshAll()
                return@Runnable
            }
            try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
            tryEmergencyAt(attemptIndex + 1)
        }
        emergencyRetryRunnable = runnable
        handler.postDelayed(runnable, 6000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            pendingBest?.let { startEmergencyVpn(it) }
            pendingBest = null
        } else if (requestCode == 100) {
            // Permission denied: do not silently try the
            // next config, it would hit the same gate.
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            refreshAll()
        }
    }
}
