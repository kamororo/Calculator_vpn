package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * "اتصال هوشمند" (Smart Connect) settings page.
 *
 * LOOK (redesigned like the Hydra page; the SYSTEM
 * underneath is unchanged): a green lightning bolt
 * with a light-blue outline centred at the top (no
 * grey ring), then one minimal row of two buttons —
 * how it works / usage tutorial — then the target
 * dropdown (with a red star: picking a target is
 * required for the section to work), then a
 * half-height apps box (selected apps scroll past
 * like a ticker; light red and blinking while no
 * app is picked yet), then the big activation
 * circle (same size as Hydra's connect circle).
 * The circle IS the activate/deactivate button:
 * blinking light red "برای فعالسازی کلیک کنید"
 * while a permission is still missing, green
 * "برای فعالسازی کلیک کنید" once all permissions
 * are granted, and green "فعال" once active, with
 * "حالت هوشمند فعال شد" in soft green underneath.
 *
 * Activation is gated, in this order: battery
 * optimisation exemption, Usage Access, VPN
 * permission, then at least one picked app. Until
 * the permissions are complete, tapping the circle
 * shows a small page for the first missing item —
 * clearly naming it, how to grant it, and a
 * blinking soft-green button straight to the
 * settings that grant it — and a line under the
 * circle says what is missing and how to fix it.
 * Granting one item advances the flow to the next
 * on the following tap. The tutorial page is pure
 * tutorial: it holds no activation buttons.
 *
 * Prefs "renamer_smart" (unchanged):
 *   "smart_enabled" (boolean)
 *   "smart_apps" (package names, newline-joined)
 *   "smart_target" (v2ray / wireguard / amnezia /
 *     openvpn / emergency / hydra)
 *   "smart_kill_on_close" (boolean)
 *
 * This page only stores settings and starts/stops the
 * service; the watching/connecting engine itself lives
 * in SmartConnectService and is untouched by the
 * redesign. Detecting the foreground app needs Usage
 * Access; without it smart connect cannot work.
 */
class SmartConnectActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    // ADAPTIVE ENGINE (1.78) report button.
    private var engineReportBtn: android.widget.Button? = null
    private fun prefs() = getSharedPreferences("renamer_smart", MODE_PRIVATE)
    private fun dp(v: Int): Int = HydraUi.dp(this, v)

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun savedApps(): List<String> {
        return prefs().getString("smart_apps", "")?.lines()
            ?.filter { it.isNotBlank() } ?: emptyList()
    }

    // Target options, in the user's order. The key is
    // what is stored in "smart_target"; the label is
    // what the dropdown row shows.
    private val TARGETS = listOf(
        Triple("v2ray", "V2ray (کانفیگ‌های شخصی)", "V2ray (personal configs)"),
        Triple("wireguard", "وایرگارد", "WireGuard"),
        Triple("amnezia", "امنزیا", "AmneziaWG"),
        Triple("openvpn", "اوپن‌وی‌پی‌ان", "OpenVPN"),
        Triple("emergency", "اتصال اضطراری (بدون نیاز به کانفیگ شخصی)", "Emergency connect (no personal config needed)"),
        Triple("hydra", "هیدرا (ممکن است کمی دیرتر وصل بشود)", "Hydra (may connect slower)"),
        Triple("cloudflare", "کلودفلر (کانفیگ‌های ساخته‌شده در بخش کلودفلر)", "Cloudflare (configs built in the Cloudflare section)"),
        Triple("chain", "زنجیره دوتایی", "Double chain"),
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusLine: TextView
    private var smartRingView: View? = null
    private var smartTopRingAnim: android.view.animation.RotateAnimation? = null
    // 2.12: the top ring rotates slowly and
    // classily (14s, like Emergency) instead of
    // blinking — the photo's bolt sits inside it.
    private fun ringDrawable(stroke: String) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.parseColor(stroke))
        }
    private lateinit var targetLabel: TextView
    private lateinit var circleBtn: Button
    private lateinit var appsBox: LinearLayout
    private lateinit var tickerText: TextView
    private val checkedPkgs = mutableSetOf<String>()

    // Gate order: battery exemption first, then
    // usage access, then the VPN permission, and
    // only after all permissions, the app list.
    private enum class Missing { BATTERY, USAGE, VPN, APPS }

    private fun hasBatteryAccess(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) { false }
    }

    private fun firstMissing(): Missing? {
        if (!hasBatteryAccess()) return Missing.BATTERY
        if (!hasUsageAccess()) return Missing.USAGE
        if (VpnService.prepare(this) != null) return Missing.VPN
        if (savedApps().isEmpty()) return Missing.APPS
        return null
    }

    // Line under the circle while a permission is
    // still missing: names the permission, tells
    // the user to tap the circle, red star beside it.
    private fun missingLine(m: Missing): String {
        return when (m) {
            Missing.BATTERY -> L(
                "دسترسی باتری نیاز است؛ روی دکمه فعالسازی کلیک کنید ★",
                "Battery access is needed; tap the activation button ★")
            Missing.USAGE -> L(
                "دسترسی مصرف برنامه نیاز است؛ روی دکمه فعالسازی کلیک کنید ★",
                "Usage access is needed; tap the activation button ★")
            Missing.VPN -> L(
                "مجوز VPN نیاز است؛ روی دکمه فعالسازی کلیک کنید ★",
                "VPN permission is needed; tap the activation button ★")
            Missing.APPS -> ""
        }
    }

    // Circle blink: while a permission is still
    // missing, the pale red deepens every second.
    private var circleBlinkDeep = false
    private val circleBlinkRunnable = object : Runnable {
        override fun run() {
            if (prefs().getBoolean("smart_enabled", false)) return
            circleBlinkDeep = !circleBlinkDeep
            circleBtn.background = HydraUi.smartOrbBg(
                if (circleBlinkDeep) Color.parseColor("#EF9A9A") else Color.parseColor("#FFCDD2"),
                Color.parseColor("#E57373"), this@SmartConnectActivity)
            handler.postDelayed(this, 1000)
        }
    }
    private fun startCircleBlink() {
        handler.removeCallbacks(circleBlinkRunnable)
        circleBlinkDeep = false
        circleBtn.background = HydraUi.smartOrbBg(
            Color.parseColor("#FFCDD2"), Color.parseColor("#E57373"), this@SmartConnectActivity)
        handler.post(circleBlinkRunnable)
    }
    private fun stopCircleBlink() { handler.removeCallbacks(circleBlinkRunnable) }

    // Apps-box blink: while no app is picked yet,
    // the pale red box deepens every second.
    private var appsBlinkDeep = false
    private val appsBlinkRunnable = object : Runnable {
        override fun run() {
            appsBlinkDeep = !appsBlinkDeep
            appsBox.background = HydraUi.glassPill(
                if (appsBlinkDeep) Color.parseColor("#EF9A9A") else Color.parseColor("#FFCDD2"),
                Color.parseColor("#E57373"), 16f, this@SmartConnectActivity)
            handler.postDelayed(this, 1000)
        }
    }
    private fun startAppsBlink() {
        handler.removeCallbacks(appsBlinkRunnable)
        appsBlinkDeep = false
        appsBox.background = HydraUi.glassPill(
            Color.parseColor("#FFCDD2"), Color.parseColor("#E57373"), 16f, this@SmartConnectActivity)
        handler.post(appsBlinkRunnable)
    }
    private fun stopAppsBlink() { handler.removeCallbacks(appsBlinkRunnable) }

    // Blinking soft-green button inside a permission
    // dialog: the button that takes the user to the
    // settings that grant that permission.
    private var dialogBlinkBtn: Button? = null
    private var dialogBlinkDeep = false
    private val dialogBlinkRunnable = object : Runnable {
        override fun run() {
            val b = dialogBlinkBtn ?: return
            dialogBlinkDeep = !dialogBlinkDeep
            b.setBackgroundColor(
                if (dialogBlinkDeep) Color.parseColor("#A5D6A7") else Color.parseColor("#C8E6C9"))
            handler.postDelayed(this, 1000)
        }
    }
    private fun startDialogBlink(dialog: AlertDialog) {
        stopDialogBlink()
        val b = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        dialogBlinkBtn = b
        dialogBlinkDeep = false
        b.setBackgroundColor(Color.parseColor("#C8E6C9"))
        handler.post(dialogBlinkRunnable)
        dialog.setOnDismissListener { stopDialogBlink() }
    }
    private fun stopDialogBlink() {
        handler.removeCallbacks(dialogBlinkRunnable)
        dialogBlinkBtn = null
    }

    // Single source of truth for the page state:
    // circle look + status line + apps box. Called
    // on create, on resume, after every tap, after
    // every settings return and after picker Save.
    private fun refreshActivation() {
        val enabled = prefs().getBoolean("smart_enabled", false)
        if (enabled) {
            stopCircleBlink()
            circleBtn.text = L("فعال", "Active")
            circleBtn.textSize = 20f
            circleBtn.setTypeface(null, android.graphics.Typeface.BOLD)
            circleBtn.background = HydraUi.smartOrbBg(
                Color.parseColor("#C8E6C9"), Color.parseColor("#81C784"), this@SmartConnectActivity)
            circleBtn.setTextColor(Color.parseColor("#1B5E20"))
            statusLine.text = L("حالت هوشمند فعال شد", "Smart mode is on")
            statusLine.setTextColor(Color.parseColor("#66BB6A"))
            statusLine.visibility = View.VISIBLE
        } else {
            val m = firstMissing()
            if (m == null || m == Missing.APPS) {
                // All permissions granted: green and
                // ready, "برای فعالسازی کلیک کنید" on
                // one line. Tapping it with no apps
                // opens the select-an-app page.
                stopCircleBlink()
                circleBtn.text = L("برای فعالسازی کلیک کنید", "Tap to activate")
                circleBtn.textSize = 12f
                circleBtn.setTypeface(null, android.graphics.Typeface.NORMAL)
                circleBtn.background = HydraUi.smartOrbBg(
                    Color.parseColor("#C8E6C9"), Color.parseColor("#81C784"), this@SmartConnectActivity)
                circleBtn.setTextColor(Color.parseColor("#1B5E20"))
                statusLine.visibility = View.GONE
            } else {
                // A permission is still missing:
                // blinking light red circle, one-line
                // text, and the permission line under
                // it with its red star.
                circleBtn.text = L("برای فعالسازی کلیک کنید", "Tap to activate")
                circleBtn.textSize = 12f
                circleBtn.setTypeface(null, android.graphics.Typeface.NORMAL)
                circleBtn.setTextColor(Color.parseColor("#B71C1C"))
                startCircleBlink()
                statusLine.text = missingLine(m)
                statusLine.setTextColor(Color.parseColor("#B00020"))
                statusLine.visibility = View.VISIBLE
            }
        }
        refreshAppsBox()
    }

    private fun refreshTargetLabel() {
        val now = prefs().getString("smart_target", "v2ray") ?: "v2ray"
        val t = TARGETS.firstOrNull { it.first == now } ?: TARGETS[0]
        targetLabel.text = L(t.second, t.third)
    }

    // Apps box: selected apps scroll past like a
    // ticker, with wide gaps between them. While
    // no app is picked yet, the box is light red
    // and blinking, asking for at least one app.
    private fun refreshAppsBox() {
        val labels = savedApps().mapNotNull { pkg ->
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { null }
        }
        if (labels.isEmpty()) {
            tickerText.text = L("حداقل یک برنامه برای اتصال هوشمند اضافه کنید",
                "Add at least one app for Smart Connect")
            tickerText.textSize = 12f
            tickerText.setTextColor(Color.parseColor("#B71C1C"))
            tickerText.gravity = Gravity.CENTER
            tickerText.isSelected = false
            startAppsBlink()
        } else {
            stopAppsBlink()
            appsBox.background = HydraUi.smartRowBg(
                Color.parseColor("#FFFFFFFF"), Color.parseColor("#FFBFBFBF"), 16f, this)
            tickerText.text = labels.joinToString("        ")
            tickerText.textSize = 14f
            tickerText.setTextColor(Color.parseColor("#FF333333"))
            tickerText.gravity = Gravity.CENTER_VERTICAL
            tickerText.isSelected = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }
        scroll.addView(root)
        setContentView(scroll)

        // Re-arm the service when this page opens, so
        // a service MIUI killed comes back without a
        // re-save: opening this section itself revives it.
        try {
            val sp = prefs()
            val apps = sp.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (sp.getBoolean("smart_enabled", false) && apps.isNotEmpty()) {
                val svc = Intent(this, SmartConnectService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(svc) else startService(svc)
            }
        } catch (_: Exception) {}

        // Top row: title only (1.81: the back
        // button was removed — system Back (or the
        // gesture) returns to the previous page).
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        topRow.addView(TextView(this).apply {
            text = L("اتصال هوشمند", "Smart Connect")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(topRow)

        // Smart icon: ONLY the lightning bolt, no
        // grey ring around it — green bolt with a
        // light-blue outline, centred like before.
        // 1.96: thin neon ring around the bolt, blinking
        // gently every 1.5s between two close greens.
        // The ring is a separate overlay view, so the
        // blink touches the ring only, never the icon.
        val smartIconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(184)
            ).apply { topMargin = dp(24) }
        }
        smartRingView = View(this).apply {
            background = HydraUi.SweepRingDrawable(Color.parseColor("#66BB6A"), this@SmartConnectActivity)
            layoutParams = FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER)
        }
        smartTopRingAnim = android.view.animation.RotateAnimation(0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 14000
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.view.animation.Animation.INFINITE
        }
        smartRingView?.startAnimation(smartTopRingAnim)
        smartIconWrap.addView(smartRingView)
        smartIconWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_smart_bolt)
            layoutParams = FrameLayout.LayoutParams(dp(150), dp(170), Gravity.CENTER)
        })
        root.addView(smartIconWrap)

        // Two minimal buttons in one row: how it
        // works / usage tutorial. Activation moved
        // into the big circle below; the tutorial
        // holds no activation buttons anymore.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        fun rowBtn(text: String, onClick: () -> Unit): Button {
            return HydraUi.glassButton(this, text, onClick).apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
            }
        }
        row.addView(rowBtn(L("نحوه عملکرد", "How it works")) { showHowDialog() })
        row.addView(rowBtn(L("آموزش استفاده", "Usage tutorial")) { showTutorialDialog() })
        root.addView(row)

        // ADAPTIVE ENGINE (1.78): minimal report
        // button, visible only while the engine is
        // on (visibility refreshed in onResume).
        engineReportBtn = AdaptiveEngine.reportButton(this, AdaptiveEngine.S_SMART).apply {
            visibility = if (AdaptiveEngine.isEnabled(this@SmartConnectActivity)) View.VISIBLE else View.GONE
        }
        root.addView(engineReportBtn)

        // Target dropdown: "وصل شدن هوشمند به ▼" with a
        // red star — picking a target is required for
        // the section to work. Tapping opens the small
        // options page; picking one closes it and that
        // becomes Smart Connect's connection method.
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HydraUi.smartRowBg(
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FFBFBFBF"), 16f, this@SmartConnectActivity)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            isClickable = true
            setOnClickListener { showTargetDialog() }
        }
        targetRow.addView(TextView(this).apply {
            text = L("وصل شدن هوشمند به", "Smart connect to")
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        })
        targetLabel = TextView(this).apply {
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        targetRow.addView(targetLabel)
        targetRow.addView(TextView(this).apply {
            text = "▼"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(dp(8), 0, dp(4), 0)
        })
        targetRow.addView(TextView(this).apply {
            text = "★"
            textSize = 18f
            setTextColor(Color.parseColor("#D32F2F"))
        })
        root.addView(targetRow)
        refreshTargetLabel()

        // Apps box, half the height of the target
        // box: selected apps scroll past like a
        // ticker; tapping it opens the picker.
        // Its height is set to exactly half of the
        // target row once laid out.
        appsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HydraUi.smartRowBg(
                Color.parseColor("#FFFFFFFF"),
                Color.parseColor("#FFBFBFBF"), 16f, this@SmartConnectActivity)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            isClickable = true
            setOnClickListener { openPicker() }
        }
        tickerText = TextView(this).apply {
            textSize = 14f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        appsBox.addView(tickerText)
        root.addView(appsBox)
        targetRow.post {
            val h = targetRow.height / 2
            if (h > 0) {
                appsBox.layoutParams.height = h
                appsBox.requestLayout()
            }
        }

        // The activation circle: same size as
        // Hydra's connect circle. It IS the
        // activate/deactivate button — its look
        // (blinking red / green ready / green
        // active) is set by refreshActivation().
        circleBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
                bottomMargin = dp(8)
            }
            elevation = 8f
            setSingleLine()
            setOnClickListener { onActivateTapped() }
        }
        root.addView(circleBtn)

        // Status line under the circle: the missing
        // permission (with its red star) while
        // inactive, "حالت هوشمند فعال شد" in soft
        // green once active, hidden when ready.
        statusLine = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(8))
            visibility = View.GONE
        }
        root.addView(statusLine)

        // 2.62: the kill toggle is a compact pill, not a
        // full-width row with a checkbox. Tap toggles,
        // no tick needed: GREEN = on, LIGHT RED = off,
        // in the page's own green/red (the circle's
        // active/inactive colors). Smaller in both
        // dimensions: 13sp text, tight padding, and
        // wrap-content width, centered.
        val killPill = Button(this).apply {
            text = L("بستن کامل برنامه‌ی انتخاب‌شده هنگام ترک آن", "Fully close the selected app when leaving it")
            textSize = 13f
            isAllCaps = false
            setPadding(dp(16), dp(8), dp(16), dp(8))
            elevation = 2f
            minimumWidth = 0; minimumHeight = 0
        }
        fun paintKillPill() {
            val on = prefs().getBoolean("smart_kill_on_close", false)
            killPill.background = HydraUi.smartRowBg(
                Color.parseColor(if (on) "#FFC8E6C9" else "#FFFFCDD2"),
                Color.parseColor(if (on) "#FF81C784" else "#FFE57373"), 18f, this@SmartConnectActivity)
            killPill.setTextColor(Color.parseColor(if (on) "#FF1B5E20" else "#FFB71C1C"))
            killPill.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        paintKillPill()
        killPill.setOnClickListener {
            val on = !prefs().getBoolean("smart_kill_on_close", false)
            prefs().edit().putBoolean("smart_kill_on_close", on).apply()
            paintKillPill()
        }
        val killRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
        killRow.addView(killPill)
        root.addView(killRow)

        refreshActivation()
    }

    override fun onResume() {
        super.onResume()
        refreshActivation()
        AdaptiveEngine.setReportVisible(engineReportBtn, this, AdaptiveEngine.S_SMART)
        if (dialogBlinkBtn != null) {
            handler.removeCallbacks(dialogBlinkRunnable)
            handler.post(dialogBlinkRunnable)
        }
        if (smartRingView?.animation == null) smartRingView?.startAnimation(smartTopRingAnim)
    }

    override fun onPause() {
        super.onPause()
        stopCircleBlink()
        stopAppsBlink()
        stopDialogBlink()
        smartRingView?.clearAnimation()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 700 || requestCode == 701) {
            // Back from the system VPN consent dialog
            // (700) or the battery-exemption request
            // (701): refresh the activation state
            // (and the status line). Activation itself
            // still happens on the next tap, so the
            // flow advances one permission at a time.
            refreshActivation()
        }
    }

    private fun onActivateTapped() {
        if (prefs().getBoolean("smart_enabled", false)) {
            // Deactivate: same as the old Save with
            // the switch off — flag off, service stopped.
            prefs().edit().putBoolean("smart_enabled", false).apply()
            try { stopService(Intent(this, SmartConnectService::class.java)) } catch (_: Exception) {}
            refreshActivation()
            Toast.makeText(this,
                L("اتصال هوشمند غیرفعال شد.", "Smart Connect disabled."),
                Toast.LENGTH_SHORT).show()
            return
        }
        when (val missing = firstMissing()) {
            null -> {
                prefs().edit().putBoolean("smart_enabled", true).apply()
                // Re-asserting smart connect clears the
                // 5-minute manual-stop suppression, so
                // opening a selected app right after
                // activating connects at once instead of
                // looking dead — same as the old Save.
                try {
                    prefs().edit().remove("smart_manual_stop_until").apply()
                    getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                        .edit().remove("smart_manual_stop_until").apply()
                } catch (_: Exception) {}
                try {
                    val svc = Intent(this, SmartConnectService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(svc) else startService(svc)
                } catch (_: Exception) {}
                refreshActivation()
                Toast.makeText(this,
                    L("اتصال هوشمند فعال شد.", "Smart Connect enabled."),
                    Toast.LENGTH_SHORT).show()
            }
            Missing.APPS -> {
                // All permissions are done; only the
                // app list is missing. The page says
                // so and offers the picker.
                showSelectAppDialog()
            }
            else -> {
                // A permission is still missing: do
                // NOT activate. The status line under
                // the circle already names it; the
                // guided page explains it and offers
                // the blinking button to Settings.
                refreshActivation()
                showMissingDialog(missing)
            }
        }
    }

    // Beautiful guided page for one missing
    // permission: clearly names the permission,
    // explains how to grant it, and offers a
    // blinking soft-green button straight to the
    // settings that grant it.
    private fun showMissingDialog(m: Missing) {
        when (m) {
            Missing.BATTERY -> {
                val d = AlertDialog.Builder(this)
                    .setTitle(L("دسترسی باتری", "Battery access"))
                    .setMessage(L(
                        "برای اینکه اتصال هوشمند در پس‌زمینه زنده بماند و سیستم آن را نبندد — به‌خصوص در گوشی‌های شیائومی که بهینه‌سازی باتری (MIUI) سرویس‌های پس‌زمینه را می‌کشد — باید «عدم بهینه‌سازی باتری» برای این برنامه فعال باشد. با زدن دکمه‌ی سبز، صفحه‌ی درخواست باز می‌شود؛ گزینه‌ی «اجازه دادن» را انتخاب کن تا این برنامه از بهینه‌سازی باتری معاف شود. بعد از آن، با زدن دوباره‌ی دایره سراغ دسترسی بعدی می‌روی.",
                        "For Smart Connect to stay alive in the background and not be killed by the system — especially on Xiaomi phones, where MIUI battery optimisation kills background services — “Ignore battery optimisations” must be enabled for this app. The green button opens the request page; choose “Allow” so this app is exempt from battery optimisation. After that, tapping the circle again moves you to the next permission."))
                    .setPositiveButton(L("اعطای دسترسی باتری", "Grant battery access")) { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.fromParts("package", packageName, null)
                            }
                            startActivityForResult(intent, 701)
                        } catch (_: Exception) {
                            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                        }
                    }
                    .setNegativeButton(L("باشه", "OK"), null)
                    .show()
                startDialogBlink(d)
            }
            Missing.USAGE -> {
                val d = AlertDialog.Builder(this)
                    .setTitle(L("دسترسی مصرف برنامه", "Usage access"))
                    .setMessage(L(
                        "اتصال هوشمند برای اینکه بفهمد چه اپی باز شده است، به «دسترسی مصرف برنامه» نیاز دارد. در صفحه‌ای که باز می‌شود، این برنامه را فعال کن. اگر اندروید دسترسی را با «Controlled by restricted setting» رد کرد، اول در اطلاعات برنامه (دکمه «اطلاعات برنامه» پایین) روی سه‌نقطه بالا بزن و «Allow restricted settings» را فعال کن، بعد برگرد و همین دسترسی را بده.",
                        "Smart Connect needs “Usage Access” to tell which app is open. On the page that opens, enable it for this app. If Android denies it with “Controlled by restricted setting”, first open App info (the button below), tap the top ⋮ menu and enable “Allow restricted settings”, then come back and grant this access."))
                    .setPositiveButton(L("باز کردن تنظیمات دسترسی", "Open access settings")) { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNeutralButton(L("اطلاعات برنامه", "App info")) { _, _ ->
                        try {
                            val uri = android.net.Uri.fromParts("package", packageName, null)
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                            Toast.makeText(this,
                                L("در صفحه اطلاعات برنامه، روی سه‌نقطه بالا بزن و «Allow restricted settings» را فعال کن، بعد برگرد.", "On the app info page, tap the top ⋮ menu and enable “Allow restricted settings”, then come back."),
                                Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(L("باشه", "OK"), null)
                    .show()
                startDialogBlink(d)
            }
            Missing.VPN -> {
                val d = AlertDialog.Builder(this)
                    .setTitle(L("مجوز VPN", "VPN permission"))
                    .setMessage(L(
                        "اتصال هوشمند برای وصل شدن به مجوز VPN نیاز دارد. این مجوز وقتی ثبت می‌شود که یک بار به‌صورت دستی به هدف انتخاب‌شده وصل شده باشی. با دکمه پایین هم می‌توانی همین‌جا مجوز را بدهی؛ بعد از آن، با زدن دوباره‌ی دایره، فعالسازی ادامه پیدا می‌کند.",
                        "Smart Connect needs the VPN permission to connect. It is registered when you connect once manually to the chosen target. The button below grants it right here; after that, tapping the circle again continues the activation."))
                    .setPositiveButton(L("اعطای مجوز VPN", "Grant VPN permission")) { _, _ ->
                        try {
                            val pi = VpnService.prepare(this)
                            if (pi != null) startActivityForResult(pi, 700)
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(L("باشه", "OK"), null)
                    .show()
                startDialogBlink(d)
            }
            Missing.APPS -> { /* handled by showSelectAppDialog, never here */ }
        }
    }

    // Page shown when every permission is granted
    // but no app is picked yet: at least one app
    // must be selected for Smart mode.
    private fun showSelectAppDialog() {
        AlertDialog.Builder(this)
            .setTitle(L("انتخاب برنامه", "Select apps"))
            .setMessage(L(
                "حداقل نیاز است یک برنامه را برای استفاده از حالت هوشمند انتخاب کنید. روی «انتخاب برنامه» بزن، برنامه‌های موردنظرت را با جستجو پیدا کن، تیک بزن و «ذخیره» را بزن؛ بعد از آن، با زدن دوباره‌ی دایره فعالسازی انجام می‌شود.",
                "At least one app must be selected to use Smart mode. Tap “Select apps”, find your apps with search, tick them and tap “Save”; after that, tapping the circle again activates it."))
            .setPositiveButton(L("انتخاب برنامه", "Select apps")) { _, _ -> openPicker() }
            .setNegativeButton(L("باشه", "OK"), null)
            .show()
    }

    // 1.96: a target is selectable only when the user
    // actually has something to connect with. Targets
    // that need a personal config and have none sink to
    // the end of the dialog, dimmed, with "add a config
    // first" next to them, and cannot be picked.
    // Emergency and Hydra need no personal config.
    // Chain needs both hops set and no OpenVPN hop,
    // because Smart cannot run the proxy-core
    // OpenVPN-hop2 variant headlessly.
    private fun targetAvailable(key: String): Boolean = when (key) {
        "v2ray" -> VpnSubs.loadServers(this).isNotEmpty()
        "wireguard" -> WgDirect.firstConf(this) != null
        "amnezia" -> WgDirect.firstAwgConf(this) != null
        "openvpn" -> OvpnStore.load(this).isNotEmpty()
        "cloudflare" -> cfFirstLink() != null
        "chain" -> {
            val h1 = ChainEngine.hop(this, 1)
            val h2 = ChainEngine.hop(this, 2)
            h1 != null && h2 != null && h1.type != "openvpn" && h2.type != "openvpn"
        }
        else -> true
    }

    private fun cfFirstLink(): String? = try {
        val arr = org.json.JSONArray(
            getSharedPreferences("renamer_cf", MODE_PRIVATE)
                .getString("cf_results", "[]") ?: "[]")
        if (arr.length() == 0) null
        else arr.getJSONObject(0).optString("link").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun orderedTargets(): List<Triple<String, String, String>> {
        val (ok, no) = TARGETS.partition { targetAvailable(it.first) }
        return ok + no
    }

    private fun showTargetDialog() {
        val ordered = orderedTargets()
        val now = prefs().getString("smart_target", "v2ray") ?: "v2ray"
        val labels = ordered.map { t ->
            val base = L(t.second, t.third)
            if (targetAvailable(t.first)) base
            else base + L(" — اول کانفیگ اضافه کنید", " — add a config first")
        }
        val checked = ordered.indexOfFirst { it.first == now }.takeIf { it >= 0 } ?: 0
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_single_choice, labels) {
            override fun isEnabled(position: Int): Boolean =
                targetAvailable(ordered[position].first)
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as CheckedTextView
                if (!targetAvailable(ordered[position].first))
                    v.setTextColor(Color.parseColor("#9E9E9E"))
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle(L("وصل شدن هوشمند به", "Smart connect to"))
            .setSingleChoiceItems(adapter, checked) { dialog, which ->
                val key = ordered[which].first
                if (!targetAvailable(key)) return@setSingleChoiceItems
                prefs().edit().putString("smart_target", key).apply()
                refreshTargetLabel()
                dialog.dismiss()
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showHowDialog() {
        val tv = TextView(this).apply {
            text = L("""۱) اپ‌های دلخواهت را از کادر برنامه‌ها (زیر کادر نوع اتصال) تیک بزن و «ذخیره» را بزن. از آن به بعد، به محض اینکه یکی از آن اپ‌ها باز شود، برنامه بدون اینکه خودش باز شود، به هدفی که انتخاب کرده‌ای وصل می‌شود: v2ray، وایرگارد، امنزیا، OpenVPN، اتصال اضطراری، یا هیدرا. فقط یکی از این شش می‌تواند انتخاب شود.

۲) اتصال هوشمند فقط وقتی وصل می‌شود که هیچ فیلترشکن دیگری روشن نباشد. اگر خودت دستی فیلترشکنی روشن کرده باشی — از همین برنامه یا از برنامه دیگر — اتصال هوشمند ساکت می‌ماند و هیچ کاری نمی‌کند تا وقتی خودت آن فیلترشکن را قطع کنی. اتصال هوشمند هرگز فیلترشکن دستی تو را قطع نمی‌کند و کارش را مختل نمی‌کند.

۳) برای v2ray، اول به آخرین کانفیگی وصل می‌شود که به آن وصل شده بودی (سریع، بدون تست پینگ) و اگر آن وصل نشد، تست پینگ می‌گیرد و به بهترین کانفیگ وصل می‌شود. برای WireGuard به تونل فعال ذخیره‌شده وصل می‌شود. برای امنزیا فقط به تونل‌های امنزیا وصل می‌شود: اگر تونل فعال ذخیره‌شده امنزیا باشد همان، وگرنه اولین تونل امنزیای ذخیره‌شده. برای OpenVPN به آخرین پروفایل. برای اتصال اضطراری دقیقاً مثل صفحه خودش کار می‌کند: هر بار تازه می‌گیرد، تست پینگ می‌گیرد و به بهترین سرور وصل می‌شود، بدون اینکه چیزی ذخیره شود و بدون اینکه کانفیگی از تو لازم باشد. برای هیدرا مثل صفحه خودش کار می‌کند: شبکه را می‌چشد و با شکلی که جواب بدهد وصل می‌شود؛ به همین دلیل ممکن است اتصال دیرتر برقرار شود.

۴) قطع شدن با ۵ ثانیه مهلت اتفاق می‌افتد: اگر اپ انتخاب‌شده بیشتر از ۵ ثانیه جلوی صفحه نباشد — با دکمه برگشت، با پاک کردن از لیست اخیر، یا با رفتن به اپ دیگر یا صفحه اصلی — فیلترشکن قطع می‌شود. برگشت سریع‌تر از ۵ ثانیه (مثلاً یک سر زدن کوتاه به صفحه اصلی) قطع نمی‌کند. فقط دو استثنا هست: بودن در داخل همین برنامه، و رد شدن گذرای سایه اعلان‌ها یا صفحه برنامه‌های اخیر، قطع نمی‌کند. اگر صفحه برنامه‌های اخیر یا قفل برای مدت طولانی بماند (بیش از ۳۰ ثانیه)، قطع می‌شود.

۵) برای کار کردن این قسمت دو چیز لازم است: دسترسی مصرف برنامه (از همین صفحه) تا برنامه بفهمد چه اپی باز شده است، و یک بار وصل شدن دستی برای هر هدف تا مجوز VPN ثبت شده باشد. اگر خودت دستی قطع کنی، اتصال هوشمند پنج دقیقه صبر می‌کند تا دوباره وصل نشود.

نکته: بودن در داخل همین برنامه هرگز فیلترشکن را قطع نمی‌کند، پس تنظیم کردن برنامه بی‌خطر است.""", """1) Pick your apps from the apps box (below the connection-type box) and tap Save. From then on, as soon as one of those apps opens, the app connects — without opening itself — to the target you chose: v2ray, WireGuard, AmneziaWG, OpenVPN, Emergency connect, or Hydra. Only one of the six can be selected.

2) Smart Connect only connects when NO other VPN is on. If you manually connected a VPN — from this app or another one — Smart Connect stays quiet and does nothing until you disconnect that VPN yourself. Smart Connect never disconnects or disturbs your hand-started VPN.

3) For v2ray it first tries the last config you connected to (fast, no ping test), and if that fails it ping-tests and connects to the best config. For WireGuard it connects to the saved active tunnel. For AmneziaWG it connects only to Amnezia tunnels: the saved active tunnel when it is an Amnezia config, otherwise the first saved Amnezia tunnel. For OpenVPN it connects to the last profile. For Emergency connect it works exactly like the emergency page: it fetches fresh, ping-tests, and connects to the best server, without saving anything and without needing any config from you. For Hydra it works exactly like its own page: it tastes the network and connects with a shape that works; that is why the connection may take longer.

4) Disconnect happens after a 5-second grace: if the selected app stays away from the foreground for MORE than 5 seconds — by Back, by swiping it away, or by going to another app or the home screen — the VPN turns off. Coming back within 5 seconds (a quick hop to Home, for example) never cuts it. Two exceptions only: being inside this app itself, and the notification shade / recents screen flashing through, never cut it. If the recents screen or the lock screen stays for a long time (more than 30 seconds), it does disconnect.

5) Two things are needed: Usage Access (from this page) so the app can tell which app opened, and one manual connect per target so the VPN permission is registered. If you disconnect manually, Smart Connect waits five minutes before connecting again.

Note: being inside this app itself never cuts the VPN, so changing settings is safe.""")
            textSize = 15f
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(L("اتصال هوشمند چطور کار می‌کند؟", "How does Smart Connect work?"))
            .setView(sv)
            .setPositiveButton(L("باشه", "OK"), null)
            .show()
    }

    private fun showTutorialDialog() {
        val tv = TextView(this).apply {
            text = L("""برای اینکه اتصال هوشمند خودش و بدون باز کردن برنامه کار کند، این‌ها لازم است:

۱) انتخاب برنامه: روی کادر برنامه‌ها (زیر کادر نوع اتصال) بزن، با جستجو برنامه‌های موردنظرت را پیدا کن، تیک بزن و «ذخیره» را بزن. تا حداقل یک برنامه انتخاب نشده باشد، فعالسازی کار نمی‌کند و کادر برنامه‌ها قرمز چشمک‌زن می‌ماند.

۲) دسترسی مصرف برنامه: برنامه باید بفهمد چه اپی باز شده است. در تنظیمات دسترسی مصرف برنامه، این برنامه را فعال کن. اگر اندروید دسترسی را با «Controlled by restricted setting» رد کرد — این محدودیت اندروید برای برنامه‌های نصب‌شده از بیرون پلی‌استور است، نه مشکل این برنامه — اول در اطلاعات برنامه روی سه‌نقطه بالا بزن و «Allow restricted settings» را فعال کن، بعد برگرد و دسترسی را بده.

۳) مجوز VPN: برای هر هدف، اول یک بار به‌صورت دستی وصل شو تا مجوز VPN ثبت شود؛ بعد از آن اتصال هوشمند بدون باز کردن برنامه وصل می‌شود. مجوز را می‌توانی از جریان فعالسازی (دکمه‌ی دایره‌ای) هم بدهی.

۴) باتری: اگر اتصال هوشمند فقط وقتی برنامه باز است کار می‌کند، علت معمولاً این است: بعد از ری‌استارت گوشی سرویس دوباره راه می‌افتد، ولی بهینه‌سازی باتری شیائومی (MIUI) آن را می‌کشد. این دسترسی در جریان فعالسازی (با زدن دکمه‌ی دایره‌ای) از تو خواسته می‌شود؛ آن را بده و در تنظیمات گوشی، اجازه «Autostart» را هم بده.

بعد از این‌ها، روی دکمه‌ی دایره‌ای بزن؛ تا همه‌چیز کامل نباشد فعال نمی‌شود، و با دادن هر دسترسی، با زدن دوباره‌ی دایره سراغ دسترسی بعدی می‌روی تا وقتی همه کامل شود و دایره سبز «فعال» شود.""", """For Smart Connect to work on its own, without opening the app, these are needed:

1) Select apps: tap the apps box (below the connection-type box), find your apps with search, tick them and tap “Save”. Until at least one app is selected, activation will not work, and the apps box stays blinking red.

2) Usage Access: the app must be able to tell which app is open. Enable this app on the usage access settings page. If Android denies it with “Controlled by restricted setting” — that is Android's restriction for sideloaded apps, not a bug in this app — first open App info, tap the top ⋮ menu and enable “Allow restricted settings”, then come back and grant it.

3) VPN permission: for each target, connect once manually first so the VPN permission is registered; after that Smart Connect connects without opening the app. You can also grant it from the activation flow (the circle button).

4) Battery: if Smart Connect only works while the app is open, this is usually why: the service restarts after reboot, but Xiaomi (MIUI) battery optimisation kills it. Grant it in the activation flow (by tapping the circle button), and in the phone settings also allow Autostart for it.

After these, tap the circle button; it will not activate until everything is complete, and granting each permission moves you to the next one on the following tap, until all are done and the circle turns green “Active”.""")
            textSize = 15f
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(L("آموزش استفاده", "Usage tutorial"))
            .setView(sv)
            .setPositiveButton(L("باشه", "OK"), null)
            .show()
    }

    private fun openPicker() {
        checkedPkgs.clear()
        checkedPkgs.addAll(savedApps())
        val apps = try {
            packageManager.getInstalledApplications(0)
                .sortedBy { packageManager.getApplicationLabel(it).toString() }
        } catch (_: Exception) { emptyList() }
        // Persian names people type for popular apps, so
        // typing "تلگرام" finds Telegram even though its
        // label and package are in English.
        val faAliases = mapOf(
            "تلگرام" to "telegram", "تلگرم" to "telegram", "تل" to "telegram",
            "اینستاگرام" to "instagram", "اینستا" to "instagram",
            "واتساپ" to "whatsapp", "واتس‌اپ" to "whatsapp",
            "یوتیوب" to "youtube", "یوتوب" to "youtube",
            "گوگل" to "google", "کروم" to "chrome",
            "توییتر" to "twitter", "ایکس" to "twitter",
            "فیسبوک" to "facebook", "مسنجر" to "messenger",
            "تیکتاک" to "tiktok", "تیک‌تاک" to "tiktok",
            "اسپاتیفای" to "spotify", "نتفلیکس" to "netflix",
            "جی‌میل" to "gmail", "جیمیل" to "gmail",
            "مپ" to "maps", "نقشه" to "maps",
            "درایو" to "drive", "تلویزیون" to "tv"
        )
        fun norm(s: String): String {
            return s.trim().lowercase()
                .replace(" ", "").replace("‌", "").replace("ـ", "")
                .replace('ی', 'ي').replace('ک', 'ك')
        }
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderList(query: String) {
            listBox.removeAllViews()
            val q = norm(query)
            val qLatin = faAliases.entries.firstOrNull { norm(it.key) == q }?.value
            val shown = apps.filter { app ->
                if (q.isBlank()) true
                else {
                    val label = packageManager.getApplicationLabel(app).toString()
                    val hay = norm(label) + " " + norm(app.packageName)
                    val rawHay = (label + " " + app.packageName).lowercase()
                    hay.contains(q) || rawHay.contains(query.trim().lowercase()) ||
                        (qLatin != null && hay.contains(qLatin))
                }
            }.sortedWith(compareBy(
                { app ->
                    val label = packageManager.getApplicationLabel(app).toString().lowercase()
                    val term = qLatin ?: q
                    if (label == term) 0 else if (label.startsWith(term)) 1 else 2
                },
                { packageManager.getApplicationLabel(it).toString() }
            ))
            if (shown.isEmpty()) {
                listBox.addView(TextView(this).apply {
                    text = L("اپی با این اسم پیدا نشد.", "No app matches that name.")
                    setTextColor(Color.GRAY)
                })
                return
            }
            for (app in shown) {
                val label = packageManager.getApplicationLabel(app).toString()
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(CheckBox(this).apply {
                    text = label
                    isChecked = checkedPkgs.contains(app.packageName)
                    setOnCheckedChangeListener { _, c ->
                        if (c) checkedPkgs.add(app.packageName) else checkedPkgs.remove(app.packageName)
                    }
                    setPadding(0, 4, 0, 0)
                })
                col.addView(TextView(this).apply {
                    text = app.packageName; textSize = 12f
                    setTextColor(Color.GRAY); setPadding(48, 0, 0, 4)
                })
                listBox.addView(col)
            }
        }
        val searchBox = EditText(this).apply {
            hint = L("جستجوی اسم برنامه…", "Search app name…")
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderList(s.toString())
                }
            })
        }
        val listScroll = ScrollView(this).apply {
            addView(listBox)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(searchBox)
            addView(listScroll)
        }
        renderList("")
        AlertDialog.Builder(this)
            .setTitle(L("انتخاب برنامه", "Select apps"))
            .setView(col)
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val chosen = checkedPkgs.toList()
                prefs().edit().putString("smart_apps", chosen.joinToString("\n")).apply()
                // Same service semantics as the old
                // Save: enabled + apps -> service on;
                // enabled + no apps -> service off —
                // and with no apps left, deactivate
                // fully so the circle, the apps box
                // and the service all agree.
                try {
                    if (prefs().getBoolean("smart_enabled", false) && chosen.isNotEmpty()) {
                        val svc = Intent(this, SmartConnectService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(svc) else startService(svc)
                    } else if (prefs().getBoolean("smart_enabled", false)) {
                        prefs().edit().putBoolean("smart_enabled", false).apply()
                        stopService(Intent(this, SmartConnectService::class.java))
                    }
                } catch (_: Exception) {}
                refreshActivation()
                Toast.makeText(this,
                    L("ذخیره شد: ${chosen.size} اپ.", "Saved: ${chosen.size} apps."),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    companion object {
        const val PREFS_NAME = "renamer_smart"
        const val KEY_ENABLED = "smart_enabled"
        const val KEY_APPS = "smart_apps"
    }
}
