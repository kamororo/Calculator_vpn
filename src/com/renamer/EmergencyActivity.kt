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
 * Password is 123456789. One wrong entry locks this
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
        const val SETTINGS_PASSWORD = "0000"

        fun customSubs(ctx: android.content.Context): MutableList<String> {
            return ctx.getSharedPreferences("emergency_settings", MODE_PRIVATE)
                .getString("custom_subs", "")?.lines()
                ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
        }

    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var btn: Button
    private lateinit var outerRingView: android.view.View
    private lateinit var sweepView: android.view.View
    private lateinit var sweepDrawable: HydraUi.SweepRingDrawable
    private lateinit var rotAnim: android.view.animation.RotateAnimation
    private lateinit var infoText: TextView
    private var engineReportBtn: Button? = null
    private var engineSelecting = false
    // 1.82: subtle connecting pulse (classier button).
    private var pulseAnimator: android.animation.ObjectAnimator? = null
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
    // 2.61: NO fast path any more (user order): a
    // config that connected in the last 6h is NEVER
    // tried first. Every tap runs the ping race:
    // first sub-200ms wins at once, else the full
    // list is tested to the end and the lowest ping
    // leads. The old direct-first flow
    // (recentLastConfig / recordEmergencyLast /
    // directPhase / directCfg / freshConfigs) is
    // gone, not just disabled.
    // Dead list (2.61, emergency-only): configs
    // proven dead by a real attempt (session never
    // came up, or two dead traffic probes, or an
    // unbuildable config) are remembered by
    // fingerprint + time in the emergency settings
    // file — never the config itself — and filtered
    // out of every fresh fetch for 24h, so the ping
    // race stays short and an auto-reconnect never
    // wastes time on them. A config that later
    // proves healthy is removed at once.
    // Auto-reconnect (2.61, page-lifetime only):
    // after a CONFIRMED emergency session drops on
    // its own (not a manual stop, not another
    // section taking over), the page reconnects by
    // itself to a low-ping config from a fresh
    // fetch, up to 3 rounds, then gives up with a
    // message. It only runs while this page is
    // resumed: if Android destroys the page, the
    // watch ends with it — a persisted flag would
    // wrongly reconnect after the user moved on.
    private var confirmedEmergency = false
    private var manualStop = false
    private var autoReconnectActive = false
    private var autoReconnectRounds = 0
    private var stickyInfo: String? = null
    private var watchRunnable: Runnable? = null

    // 2.63: emergency pre-ping. When the page is
    // entered, the ping race starts by itself
    // (before any tap), so a later tap can use the
    // already-ordered list at once. One-shot: a
    // tap consumes the cache; leaving the page
    // without tapping cancels a still-running race.
    // Emergency page only — no other section reads
    // or writes these fields.
    private var prePingThread: Thread? = null
    private var prePingCancelled = false
    private var prePingOrdered: List<String>? = null
    private var prePingAt = 0L
    private var prePingGeneration = 0

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
        // Dual-circle button: a static outer ring, a
        // slowly rotating inner rim (mostly the state
        // color, one translucent glass-water segment
        // that is never removed), and the glass face.
        // The rim spins in ALL three states.
        val emWrap = android.widget.FrameLayout(this).apply {
            val s = (248 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        outerRingView = android.view.View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        emWrap.addView(outerRingView)
        sweepView = android.view.View(this).apply {
            val s = (220 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        }
        emWrap.addView(sweepView)
        btn = Button(this).apply {
            text = L("اتصال اضطراری", "Emergency connect")
            textSize = 20f
            val s = (200 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            setOnClickListener { onButton() }
        }
        emWrap.addView(btn)
        root.addView(emWrap)
        sweepDrawable = HydraUi.SweepRingDrawable(Color.parseColor("#E57373"), this)
        sweepView.background = sweepDrawable
        rotAnim = android.view.animation.RotateAnimation(0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 14000
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.view.animation.Animation.INFINITE
        }
        sweepView.startAnimation(rotAnim)
        infoText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Theme.subFg(this@EmergencyActivity))
            setPadding(0, 32, 0, 0)
            visibility = android.view.View.GONE
        }
        root.addView(infoText)
        // ADAPTIVE ENGINE (1.79): minimal report
        // button, visible only while the engine is
        // on (visibility refreshed in refreshAll).
        // The page stays generic: the report holds
        // engine actions only, never config names.
        engineReportBtn = AdaptiveEngine.reportButton(this, AdaptiveEngine.S_EMERGENCY).apply {
            visibility = if (AdaptiveEngine.isEnabled(this@EmergencyActivity)) android.view.View.VISIBLE else android.view.View.GONE
        }
        root.addView(engineReportBtn)
        setContentView(root)
        root.fitsSystemWindows = true
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        // The inner rim keeps spinning in every state;
        // restart it if the window dropped it.
        try { if (sweepView.animation == null) sweepView.startAnimation(rotAnim) } catch (_: Exception) {}
        // 2.61: auto-reconnect watch (page-lifetime
        // only — see the field comment above).
        watchRunnable?.let { handler.removeCallbacks(it) }
        val w = Runnable {
            try { watchEmergencySession() } catch (_: Exception) {}
            watchRunnable?.let { handler.postDelayed(it, 2000) }
        }
        watchRunnable = w
        handler.postDelayed(w, 2000)
        // 2.63: pre-ping on entry.
        maybeStartPrePing()
    }

    override fun onPause() {
        // 2.63: leaving without tapping stops a
        // still-running pre-ping.
        cancelPrePing()
        watchRunnable?.let { handler.removeCallbacks(it) }
        super.onPause()
    }

    /** 2.63: start the ping race in the background
     *  on page entry. Never over a live session,
     *  never while busy, never twice, and never
     *  when a fresh cache already exists. Only a
     *  non-empty order is cached. */
    private fun maybeStartPrePing() {
        if (VpnClientService.isRunning) return
        if (busy || engineSelecting || emergencyAttemptActive) return
        if (prePingOrdered != null && System.currentTimeMillis() - prePingAt < 5 * 60 * 1000L) return
        if (prePingThread?.isAlive == true) return
        prePingCancelled = false
        val gen = ++prePingGeneration
        prePingThread = Thread {
            try {
                val fetched = fetchEmergencyConfigs()
                val configs = filterDead(fetched)
                if (configs.isEmpty()) return@Thread
                val ordered = pingFirstUnder200Order(configs) { prePingCancelled }
                if (gen != prePingGeneration || prePingCancelled) return@Thread
                if (ordered.isNotEmpty()) {
                    prePingOrdered = ordered
                    prePingAt = System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    /** 2.63: stop a still-running pre-ping race. A
     *  finished cache survives for its 5 minutes;
     *  the generation guard discards any late
     *  result from the stopped race. */
    private fun cancelPrePing() {
        if (prePingThread?.isAlive == true) {
            prePingCancelled = true
            prePingGeneration++
        }
        prePingThread = null
    }

    /** 2.61: the auto-reconnect watch. While a
     *  confirmed emergency session is alive, do
     *  nothing. If another section took over, the
     *  user moved on: stop watching, never
     *  reconnect over another section's session.
     *  If no session runs and the drop was not
     *  manual, auto-reconnect (up to 3 rounds). */
    private fun watchEmergencySession() {
        if (!confirmedEmergency || manualStop) return
        if (busy || engineSelecting || emergencyAttemptActive || autoReconnectActive) return
        if (VpnClientService.isRunning) {
            if (!isEmergencySession()) confirmedEmergency = false
            return
        }
        if (autoReconnectRounds >= 3) {
            confirmedEmergency = false
            autoReconnectActive = false
            stickyInfo = L("اتصال اضطراری قطع شد و اتصال خودکار پس از ۳ بار ناموفق ماند؛ دوباره دکمه را بزن.", "Emergency disconnected and auto-reconnect failed 3 times; tap the button again.")
            refreshAll()
            return
        }
        autoReconnectActive = true
        autoReconnectRounds++
        stickyInfo = null
        startConnectFlow(auto = true)
    }

    /** 2.61: end of one connect round. Success
     *  keeps the watch armed (the session is
     *  confirmed again). Failure chains the next
     *  auto round while rounds remain, else gives
     *  up; a manual round just refreshes. */
    private fun finishConnectFlow(auto: Boolean, success: Boolean) {
        if (success) {
            confirmedEmergency = true
            autoReconnectActive = false
            stickyInfo = null
            refreshAll()
            return
        }
        if (auto && autoReconnectRounds < 3) {
            autoReconnectRounds++
            startConnectFlow(auto = true)
            return
        }
        if (auto) {
            confirmedEmergency = false
            autoReconnectActive = false
            stickyInfo = L("اتصال اضطراری قطع شد و اتصال خودکار پس از ۳ بار ناموفق ماند؛ دوباره دکمه را بزن.", "Emergency disconnected and auto-reconnect failed 3 times; tap the button again.")
        }
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
        // A glass password card (2.14), NOT the calculator page:
        // it only says the sentence below. Blank is ignored;
        // a wrong entry counts toward 3 before the lock.
        val dd = resources.displayMetrics.density
        fun dpv(v: Int): Int = (v * dd).toInt()
        lateinit var dialog: android.app.Dialog
        val input = EditText(this).apply {
            hint = L("رمز", "Password")
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#333333"))
            setPadding(dpv(16), dpv(14), dpv(16), dpv(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpv(16).toFloat()
                setColor(Color.parseColor("#F2F4F7"))
                setStroke(dpv(1), Color.parseColor("#DDE1E7"))
            }
        }
        fun pillBg(fill: String, stroke: String) =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpv(24).toFloat()
                setColor(Color.parseColor(fill))
                setStroke(dpv(1), Color.parseColor(stroke))
            }
        // 3 attempts before the whole-app lock (counter in
        // prefs, reset on success); the warning line lives below.
        var warnText: TextView? = null
        val enterBtn = Button(this).apply {
            text = L("ورود", "Enter")
            textSize = 15f
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.parseColor("#1B5E33"))
            background = pillBg("#E6F4EA", "#B9DFC5")
            setPadding(dpv(8), dpv(12), dpv(8), dpv(12))
            setOnClickListener {
                val entered = input.text.toString().trim()
                if (entered.isBlank()) return@setOnClickListener
                if (entered == SETTINGS_PASSWORD) {
                    // success resets the attempt counter.
                    emergencyPrefs().edit().remove("settings_pw_attempts").apply()
                    dialog.dismiss()
                    startActivity(Intent(this@EmergencyActivity, EmergencySettingsActivity::class.java))
                } else {
                    // only the 3rd wrong entry locks the WHOLE
                    // app (unusable until the creator's entry password
                    // is entered, or the app is reinstalled / data
                    // cleared). Earlier wrong entries just warn and
                    // keep this dialog open for a retry.
                    val attempts = emergencyPrefs().getInt("settings_pw_attempts", 0) + 1
                    emergencyPrefs().edit().putInt("settings_pw_attempts", attempts).apply()
                    if (attempts >= 3) {
                        emergencyPrefs().edit().putBoolean("settings_locked", true).apply()
                        AppLock.lock(this@EmergencyActivity)
                        dialog.dismiss()
                        android.app.AlertDialog.Builder(this@EmergencyActivity)
                            .setMessage(AppLock.message(this@EmergencyActivity))
                            .setPositiveButton(L("بستن", "Close")) { _, _ ->
                                goToLockScreen()
                            }
                            .show()
                    } else {
                        val left = 3 - attempts
                        warnText?.text = L("رمز اشتباه است؛ $left تلاش باقی مانده است.", "Wrong password; $left attempts left.")
                        warnText?.visibility = android.view.View.VISIBLE
                        input.setText("")
                                            }
                }
            }
        }
        val cancelBtn = Button(this).apply {
            text = L("لغو", "Cancel")
            textSize = 15f
            isAllCaps = false
            stateListAnimator = null
            setTextColor(Color.parseColor("#555555"))
            background = pillBg("#F2F4F7", "#DDE1E7")
            setPadding(dpv(8), dpv(12), dpv(8), dpv(12))
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(24), dpv(24), dpv(24), dpv(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpv(28).toFloat()
                setColor(Color.WHITE)
                setStroke(dpv(1), Color.parseColor("#E3E7EC"))
            }
        }
        card.addView(TextView(this).apply {
            text = AppLanguage.t(this@EmergencyActivity, "برای ورود به تنظیمات رمز را وارد کنید", "Enter the password to open settings")
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpv(18) })
        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpv(18) })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(enterBtn, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpv(8) })
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpv(8) })
        card.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        warnText = TextView(this).apply {
            text = ""
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B3261E"))
            visibility = android.view.View.GONE
        }
        card.addView(warnText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpv(12) })
        dialog = android.app.Dialog(this).apply {
            setContentView(card)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window?.setLayout((resources.displayMetrics.widthPixels * 0.85).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
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
    // 2.10: no blink any more. The state colors live
    // here; glassButton applies them to the face,
    // the outer ring and the rotating rim at once,
    // so the three circles can never drift apart.
    private var emFill = "#FDE8E8"
    private var emStroke = "#E57373"

    /** One place sets all three circles at once, so
     *  the face, the outer ring and the rotating rim
     *  can never drift apart across states. */
    private fun glassButton(fill: String, stroke: String, textColor: String) {
        emFill = fill; emStroke = stroke
        btn.background = HydraUi.emergencyBtnBg(Color.parseColor(fill), Color.parseColor(stroke), this)
        btn.elevation = 8 * resources.displayMetrics.density
        btn.setTextColor(Color.parseColor(textColor))
        try {
            outerRingView.background = HydraUi.RingShadeDrawable(
                HydraUi.lighten(Color.parseColor(stroke), 0.45f),
                Color.parseColor(stroke), 3f, this)
        } catch (_: Exception) {}
        try { sweepDrawable.setBase(Color.parseColor(stroke)) } catch (_: Exception) {}
    }

    /** 1.82: soft pulse while connecting — the single button
     *  breathes instead of sitting flat. Stopped the moment
     *  the session is up, down, or cancelled. */
    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.6f, 1f).apply {
            duration = 1600
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        btn.alpha = 1f
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
        } else if (busy || engineSelecting) {
            // Waiting: soft yellow, as soft as the
            // disconnected red — never a harsh yellow.
            glassButton("#FDEEB8", "#E3B93E", "#7A5A00")
            btn.text = L("اتصال اضطراری", "Emergency connect")
        } else {
            glassButton("#FDE8E8", "#E57373", "#333333")
            btn.text = L("اتصال اضطراری", "Emergency connect")
        }
    }

    private fun refreshAll() {
        val emNow = isEmergencySession()
        if (emNow && !appLogEmergencyWasConnected && appLogEmergencyPageAttempt) {
            appLogEmergencyPageAttempt = false
            AppLog.append(this, "emergency",
                "وصل شد (بدون نام کانفیگ)؛ موتور هوشمند: ${if (AdaptiveEngine.isEnabled(this)) "روشن" else "خاموش"}.",
                "Connected (no config name); smart engine: ${if (AdaptiveEngine.isEnabled(this)) "ON" else "OFF"}.")
            // 2.45: the headless trigger usually fired
            // already for this session; the dup-guard
            // makes this second call a no-op then.
            ConnVerify.verifyXray(this, "emergency", "emergency", "", VpnClientService.sessionSocksPort.takeIf { it > 0 } ?: VpnConfigBuilder.socksPort(this)) { isEmergencySession() }
        }
        appLogEmergencyWasConnected = emNow
        refreshButton()
        if (!busy && !engineSelecting) stopPulse()
        AdaptiveEngine.setReportVisible(engineReportBtn, this, AdaptiveEngine.S_EMERGENCY)
        if (isEmergencySession() && VpnClientService.emergencyConfig != null) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد با سابسکریبشن، کلیک کنید برای دیدن پینگ", "Connected via subscription, tap to see ping")
            infoText.isClickable = true
            infoText.setOnClickListener { measurePing() }
        } else if (isEmergencySession()) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد با سابسکریبشن", "Connected via subscription")
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
        // 2.61: a sticky message (auto-reconnect
        // give-up, or "no healthy config") survives
        // refreshAll while nothing runs and nothing
        // is connecting; it clears on the next tap.
        if (stickyInfo != null && !isEmergencySession() && !busy && !VpnClientService.isRunning) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = stickyInfo
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
                infoText.text = if (d >= 0) L("وصل شد با سابسکریبشن، پینگ: ", "Connected via subscription, ping: ") + "$d ms"
                                else L("وصل شد با سابسکریبشن، پینگ: -1 ms", "Connected via subscription, ping: -1 ms")
                infoText.isClickable = true
                infoText.setOnClickListener { measurePing() }
            }
        }.start()
    }


    // 2.43: central-log flip state for the page.
    // PageAttempt marks a connect started from this
    // page (so a Smart-driven emergency session
    // is not double-logged here — the headless
    // path logs it).
    private var appLogEmergencyWasConnected = false
    private var appLogEmergencyPageAttempt = false

    private fun onButton() {
        if (busy || engineSelecting) return
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
            confirmedEmergency = false
            manualStop = true
            autoReconnectActive = false
            autoReconnectRounds = 0
            stickyInfo = null
            val stopIntent = Intent(this, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
            }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.currentName = null
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
            stopPulse()
            refreshAll()
            appLogEmergencyPageAttempt = false
            AppLog.append(this, "emergency",
                "قطع دستی.",
                "Stopped manually.")
            return
        }
        // 2.61: a manual tap resets the auto state
        // (a fresh manual round, not a continuation
        // of any auto round) and runs the shared
        // flow: fetch, drop dead, ping race, try.
        manualStop = false
        autoReconnectRounds = 0
        autoReconnectActive = false
        stickyInfo = null
        startConnectFlow(auto = false)
    }


    /** The subscription flow found nothing usable: give up
     *  (a manual round just refreshes; an auto round chains
     *  the next round or gives up via finishConnectFlow). */
    private fun subFailed(auto: Boolean, showSticky: Boolean = true) {
        busy = false
        if (showSticky) {
            stickyInfo = L("هیچ کانفیگ سالمی پیدا نشد؛ بعداً دوباره امتحان کن.", "No healthy config found; try again later.")
        }
        if (auto) finishConnectFlow(auto = true, success = false) else refreshAll()
    }


    /** 2.61: one connect round, manual or automatic.
     *  Fetch fresh, drop dead-listed configs, run the
     *  ping race, try up to 3 healthy configs. Shared
     *  by the button and by auto-reconnect, so both
     *  obey the same rules (never a -1 config, never
     *  a dead-listed one). */
    private fun startConnectFlow(auto: Boolean) {
        busy = true
        stickyInfo = null
        startPulse()
        refreshButton()
        infoText.visibility = android.view.View.VISIBLE
        infoText.isClickable = false
        infoText.setOnClickListener(null)
        appLogEmergencyPageAttempt = true
        if (auto) {
            AppLog.append(this, "emergency",
                "اتصال خودکار آغاز شد (دور $autoReconnectRounds)؛ موتور هوشمند: ${if (AdaptiveEngine.isEnabled(this)) "روشن" else "خاموش"}.",
                "Auto-reconnect started (round $autoReconnectRounds); smart engine: ${if (AdaptiveEngine.isEnabled(this)) "ON" else "OFF"}.")
            infoText.text = L("اتصال قطع شد؛ اتصال خودکار به بهترین کانفیگ...", "Disconnected; auto-connecting to the best config...")
        } else {
            AppLog.append(this, "emergency",
                "تلاش اتصال آغاز شد؛ موتور هوشمند: ${if (AdaptiveEngine.isEnabled(this)) "روشن" else "خاموش"}.",
                "Connect attempt started; smart engine: ${if (AdaptiveEngine.isEnabled(this)) "ON" else "OFF"}.")
            infoText.text = L("در حال اتصال به بهترین کانفیگ...", "Connecting to the best config...")
        }
        Thread {
            try {
                // 2.63: a manual tap consumes a fresh
                // pre-ping cache (one-shot); auto
                // rounds always fetch fresh, and a
                // dead-filtered cache falls back to a
                // fresh fetch too.
                val cached = if (!auto) prePingOrdered?.takeIf {
                    System.currentTimeMillis() - prePingAt < 5 * 60 * 1000L
                } else null
                if (!auto) prePingOrdered = null
                val fetched: List<String>
                val configs: List<String>
                if (cached != null) {
                    val live = filterDead(cached)
                    if (live.isNotEmpty()) {
                        fetched = emptyList()
                        configs = live
                    } else {
                        fetched = fetchEmergencyConfigs()
                        configs = filterDead(fetched)
                    }
                } else {
                    fetched = fetchEmergencyConfigs()
                    configs = filterDead(fetched)
                }
                if (configs.isEmpty()) {
                    runOnUiThread { subFailed(auto) }
                    return@Thread
                }
                // Approximate time, from the fetched count
                // and the ping-test time. The count itself
                // is never shown, only the time. Manual
                // rounds only: an auto round keeps its
                // "reconnecting" text.
                if (!auto) {
                    val batches = (configs.size + XraySpeedtest.poolSize() - 1) /
                        XraySpeedtest.poolSize()
                    val estSeconds = (batches * 3 + 5).coerceAtLeast(10)
                    val estText = if (estSeconds < 60) L("تقریبا ", "About ") + "$estSeconds " + L("ثانیه", "seconds")
                                  else L("تقریبا ", "About ") + "${(estSeconds + 59) / 60} " + L("دقیقه", "minutes")
                    runOnUiThread {
                        infoText.text = L("در حال اتصال به بهترین کانفیگ... ", "Connecting to the best config... ") + estText
                    }
                }
                val ordered = orderEmergencyConfigs(configs)
                runOnUiThread {
                    busy = false
                    if (ordered.isEmpty()) {
                        subFailed(auto)
                        return@runOnUiThread
                    }
                    // Try in order, up to 3 attempts:
                    // if a connect does not actually come
                    // up, the next config is tried.
                    pendingSorted = ordered
                    tryEmergencyAt(0)
                }
            } catch (_: Exception) {
                // A failed sub flow gives up without inventing a
                // "no healthy config" message for a fetch error.
                runOnUiThread { subFailed(auto, showSticky = false) }
            }
        }.start()
    }

    /** 2.61: dead list, emergency-only. Keys
     *  "dead_<sha256>" -> timestamp in the
     *  emergency settings file. Only a fingerprint
     *  is ever stored, never a config. Entries older
     *  than 24h are ignored and pruned, so a
     *  recovered server returns to the pool. */
    private fun deadPrefs() = getSharedPreferences("emergency_settings", MODE_PRIVATE)

    private fun markDead(cfg: String) {
        try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return
            deadPrefs().edit().putLong("dead_$h", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    private fun unmarkDead(cfg: String) {
        try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return
            deadPrefs().edit().remove("dead_$h").apply()
        } catch (_: Exception) {}
    }

    private fun isDead(cfg: String): Boolean {
        return try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return false
            val t = deadPrefs().getLong("dead_$h", 0L)
            t > 0L && System.currentTimeMillis() - t < 24L * 60L * 60L * 1000L
        } catch (_: Exception) { false }
    }

    /** Drop dead-listed configs from a fresh fetch;
     *  also prune expired dead entries. */
    private fun filterDead(configs: List<String>): List<String> {
        try {
            val p = deadPrefs()
            val now = System.currentTimeMillis()
            val expired = p.all.keys.filter { it.startsWith("dead_") &&
                now - p.getLong(it, 0L) >= 24L * 60L * 60L * 1000L }
            if (expired.isNotEmpty()) {
                val e = p.edit()
                for (k in expired) e.remove(k)
                e.apply()
            }
        } catch (_: Exception) {}
        return configs.filter { !isDead(it) }
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

    /** 2.61: emergency-only selection, separate from
     *  v2ray and from every other section. NO fast
     *  path: a config that connected before is never
     *  tried first. The ping race decides every time:
     *  first result under 200ms wins at once, else
     *  the whole list is tested to the end and the
     *  lowest ping leads. Only fingerprints (SHA-256
     *  of the dedup key) and timestamps are ever
     *  remembered — never a config itself, which is
     *  still never saved anywhere. */
    private fun orderEmergencyConfigs(configs: List<String>): List<String> =
        pingFirstUnder200Order(configs)

    /** 2.61: ping all configs in parallel, HEALTHY
     *  configs only. The first result under 200ms
     *  wins at once and the rest of the test is
     *  abandoned — no waiting for the test to
     *  finish. If nothing is under 200ms, the test
     *  runs TO THE END of the list and the lowest
     *  ping leads. A config whose ping measured -1
     *  (failed) is NEVER returned: the old fallback
     *  to fetched order, and the appended unmeasured
     *  tail, are what connected -1 configs, and both
     *  are gone. Empty result means no healthy
     *  config: the caller connects nothing. The
     *  120s cap is only a hang guard for a stuck
     *  measure task; any realistic list finishes
     *  long before it. */
    private fun pingFirstUnder200Order(configs: List<String>, isCancelled: () -> Boolean = { false }): List<String> {
        if (configs.isEmpty()) return emptyList()
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
            val deadline = System.currentTimeMillis() + 120_000
            val healthy = mutableListOf<Pair<Long, String>>()
            var remainingTasks = configs.size
            var winner: String? = null
            while (remainingTasks > 0) {
                // 2.63: sliced poll so a pre-ping
                // cancel wakes the race up within
                // 500ms instead of at the deadline.
                // The manual flow passes no cancel
                // check, so its behavior is unchanged.
                if (isCancelled()) return emptyList()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val slice = minOf(remaining, 500L)
                val future = completion.poll(slice, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (future == null) continue
                remainingTasks--
                val result = try { future.get() } catch (_: Exception) { null }
                if (result != null && result.first >= 0) {
                    healthy += result
                    if (winner == null && result.first < 200) {
                        winner = result.second
                        break
                    }
                }
            }
            val out = mutableListOf<String>()
            if (winner != null) out += winner
            out += healthy.filter { it.second != winner }.sortedBy { it.first }.map { it.second }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun shaHex(s: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
            d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Exception) { "" }
    }

    /** Start attempt [index] of the current ordered list
     *  (max 3 attempts). Runs on the UI thread. When
     *  attempts run out, a manual round just
     *  refreshes; an auto round chains the next
     *  round (or gives up) via finishConnectFlow. */
    private fun tryEmergencyAt(index: Int) {
        if (index >= pendingSorted.size || index >= 3) {
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            if (autoReconnectActive) finishConnectFlow(auto = true, success = false) else refreshAll()
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
        // ADAPTIVE ENGINE (1.79): engine-first, with
        // the normal emergency start as fallback.
        // Generic section: the selector uses the
        // generic name, no IPs in labels, and
        // memorizes nothing.
        if (AdaptiveEngine.isEnabled(this)) {
            engineSelecting = true
            infoText.visibility = android.view.View.VISIBLE
            infoText.isClickable = false
            infoText.setOnClickListener(null)
            infoText.text = L("موتور هوشمند در حال بررسی...", "Smart engine checking...")
            Thread {
                val pool = pendingSorted.filter { it != cfg }.take(8)
                val plan = try {
                    AdaptiveEngine.selectV2ray(this, cfg, AdaptiveEngine.S_EMERGENCY, 20000, pool)
                } catch (_: Exception) { null }
                runOnUiThread {
                    try { startEmergencyVpnInner(cfg, plan) } catch (_: Exception) { engineSelecting = false; refreshAll() }
                }
            }.start()
            return
        }
        startEmergencyVpnInner(cfg, null)
    }

    private fun startEmergencyVpnInner(cfg: String, plan: AdaptiveEngine.Plan?) {
        engineSelecting = false
        val effective = plan?.link ?: cfg
        // Emergency builds with its OWN TUN prefs file,
        // not v2ray's (per-section isolation).
        var vpnConfig = VpnConfigBuilder.build(this, effective, prefsName = TunPrefs.EMERGENCY)
        vpnConfig = AdaptiveEngine.applyPlanToBuiltJson(this, vpnConfig, plan, true)
        if (vpnConfig == null) {
            // This config cannot even be built: it is
            // dead by construction — mark it and try
            // the next best one instead of giving up.
            if (emergencyAttemptActive) {
                try { markDead(pendingSorted.getOrNull(pendingIndex) ?: cfg) } catch (_: Exception) {}
                tryEmergencyAt(pendingIndex + 1)
            } else refreshAll()
            return
        }
        VpnClientService.emergencyConfig = effective
        // 2.68: one precise green
        // line when an engine method won (labels are
        // generic in emergency mode by design).
        if (plan != null) {
            val netE = AdaptiveEngine.netId(this)
            val spE = if (plan.speedMbps >= 0) String.format(java.util.Locale.US, "%.1f", plan.speedMbps) else ""
            AdaptiveEngine.winGreen(this, "emergency",
                "اتصال اضطراری: روش برنده: «${plan.noteFa}»؛ شبکه «$netE»" + (if (spE.isNotBlank()) "؛ سرعت دانلود $spE مگابایت/ثانیه" else "") + "؛ واقعاً وصل شد",
                "Emergency connect: winning method: \u201c${plan.noteEn}\u201d; network \u201c$netE\u201d" + (if (spE.isNotBlank()) "; download speed $spE MB/s" else "") + "; really connected")
        }
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            // Generic name only, never the real config name.
            putExtra("name", L("اتصال اضطراری", "Emergency connect"))
            putExtra("session_tag", "emergency")
            // ADAPTIVE ENGINE (1.79): mark the session
            // for the service watchdog.
            if (plan != null) {
                putExtra("engine_session", true)
                putExtra("engine_orig_link", cfg)
            }
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
                if (VpnClientService.sessionTag != "emergency") {
                    // Another section took over: not our success,
                    // and we must not keep trying over it.
                    emergencyAttemptActive = false
                    refreshAll()
                    return@Runnable
                }
                // 1.82: up is not enough — a tunnel that carries
                // no traffic is a dead session, not a success.
                // Two dead probes (like the engine watchdog)
                // before falling to the next best config.
                Thread {
                    val ok1 = AdaptiveEngine.probeThroughTunnel(this)
                    var ok = ok1
                    if (!ok) {
                        try { Thread.sleep(4000) } catch (_: Exception) {}
                        ok = AdaptiveEngine.probeThroughTunnel(this)
                    }
                    runOnUiThread {
                        if (!emergencyAttemptActive) return@runOnUiThread
                        if (ok) {
                            // Confirmed up: this config proved
                            // healthy, so it leaves the dead
                            // list at once, and the session
                            // becomes the watched one: if it
                            // drops on its own later, the page
                            // reconnects by itself.
                            try { unmarkDead(cfg) } catch (_: Exception) {}
                            confirmedEmergency = true
                            autoReconnectActive = false
                            stickyInfo = null
                            emergencyAttemptActive = false
                            refreshAll()
                        } else {
                            // Two dead probes: this config
                            // is proven dead — remember it
                            // and try the next best one.
                            try { markDead(cfg) } catch (_: Exception) {}
                            VpnClientService.isRunning = false
                            VpnClientService.sessionTag = null
                            VpnClientService.emergencyConfig = null
                            tryEmergencyAt(attemptIndex + 1)
                        }
                    }
                }.start()
                return@Runnable
            }
            // Session never came up: proven dead too.
            try { pendingSorted.getOrNull(attemptIndex)?.let { markDead(it) } } catch (_: Exception) {}
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
            // An auto round ends here too, not chained:
            // without permission no round can succeed.
            confirmedEmergency = false
            autoReconnectActive = false
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            refreshAll()
        }
    }
}
