package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import go.Seq
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * A v2rayNG-style main page, hidden mode only:
 * a server list (name, protocol, address, ping, selected
 * marker), bulk add (paste many configs at once, or from
 * clipboard), per-server ping exactly like v2rayNG, and a
 * connect button that actually routes the device through
 * the selected server via VpnService + the Xray core
 * (TUN mode, like v2rayNG's VPN mode).
 */
class VpnActivity : Activity() {
    /** The old top status line is gone at the user's
     *  word: the top of the page must not say
     *  connected/disconnected — the FAB itself shows
     *  that. This sink keeps every existing
     *  `statusText.text = ...` assignment compiling:
     *  the message now lands on the single-line glass
     *  strip next to the FAB, where the old 2-second
     *  poller can no longer wipe it. It is NOT added
     *  to the history; only addStatusLine() writes
     *  history. */
    private val statusText = object {
        var text: String = ""
            set(v) { field = v; if (v.isNotBlank()) setStripLine(v) }
    }
    private lateinit var connectBtn: Button
    private lateinit var listContainer: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var addBtn: Button
    private lateinit var menuBtn: Button
    private var dragFrom = -1
    // FAB blink: soft neon, every 1.5s, ring only — the
    // stroke alternates between its color and a lighter
    // shade (see refreshFab).
    private var fabBlinkLight = false
    private val fabBlinkRunnable = object : Runnable {
        override fun run() {
            fabBlinkLight = !fabBlinkLight
            fabGlassUntil = 0
            refreshFab()
            handler.postDelayed(this, 1500)
        }
    }
    // "" = All configs (the default tab); otherwise a
    // sub URL, or a "GROUP::..." header when the
    // show-all-groups setting adds group tabs.
    private var selectedTab = ""
    // Previous /proc/net/dev tun totals, for the
    // live speed shown while connected.
    private var speedPrev: Pair<Long, Long>? = null
    private lateinit var scrollView: ScrollView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var hintText: TextView
    private lateinit var modeDim: View
    private lateinit var modeRow: LinearLayout
    private lateinit var frameView: android.widget.FrameLayout
    private var selectedRowView: View? = null
    // Geo flag overlay: a temporary TextView shown on the
    // root frame for exactly 3 seconds when a flag circle
    // is tapped (see showGeoMessage).
    private var geoOverlay: TextView? = null
    private val geoOverlayRemover = Runnable {
        geoOverlay?.let { frameView.removeView(it) }
        geoOverlay = null
    }
    // Connect mode: "" (not chosen yet), "normal" or "best".
    private var fabGlassUntil = 0L
    private var fabLongPressed = false
    private var fabDownX = 0f
    private var fabDownY = 0f
    private val fabLongPressRunnable = Runnable { onFabLongPress() }
    private var drawerDownX = 0f

    private fun connectMode(): String {
        return prefs().getString("connect_mode", "") ?: ""
    }
    private fun modeDone(): Boolean {
        return prefs().getBoolean("connect_mode_done", false)
    }
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private var servers: MutableList<String> = mutableListOf()
    private var selected: String? = null
    // ADAPTIVE ENGINE (1.78) state: the winning
    // variant for this one tap. One-shot: startVpn
    // consumes engineLink, doStartVpn consumes
    // enginePlan, so a later tap can never reuse a
    // stale variant.
    private var engineLink: String? = null
    private var enginePlan: AdaptiveEngine.Plan? = null
    // ADAPTIVE ENGINE (1.82): fallback no-fake-connected check.
    // Set when the engine found NO win and the normal connect runs
    // anyway; the check stops a dead "connected" session instead
    // of leaving it up. Token cancels stale checks on disconnect.
    private var pendingFallbackCheck = false
    // 2.39: guards a running engine check (a second
    // tap during the 90s run used to start a
    // parallel run, interleaving wave lines in
    // the report) and remembers whether the
    // session being checked was an engine WIN.
    private var engineRunActive = false
    // 2.43: central-log attempt context, consumed
    // once by the refreshStatus connected/error
    // one-shots. The name is kept for the session
    // so the dead-traffic stop can name it too.
    private var appLogPending = false
    private var appLogName = ""
    private var appLogType = ""
    private var appLogEngineOn = false
    private var appLogMode = ""
    private var appLogNoteFa = ""
    private var appLogNoteEn = ""
    // 2.68: the winning plan's
    // measured download speed, for the green log line.
    private var appLogSpeedMbps = -1.0

    /** 2.43: record a v2ray attempt (engine state,
     *  mode, type, winning method when there is
     *  one) and arm the one-shot outcome logs. */
    /** 2.45: a config's last measured ping (page
     *  map first, then the ping service's persisted
     *  ping_delays map). Null = never measured;
     *  -1 = failed in the last test. */
    private fun persistedPingMs(cfg: String): Long? {
        return try {
            val j = org.json.JSONObject(getSharedPreferences("renamer_subs", MODE_PRIVATE).getString("ping_delays", "{}") ?: "{}")
            val k = XraySpeedtest.dedupKey(cfg)
            if (j.has(k)) j.optLong(k) else null
        } catch (_: Exception) { null }
    }

    private fun appLogBegin(cfg: String, engineOn: Boolean, plan: AdaptiveEngine.Plan?) {
        testLogConnectFixes(cfg)
        appLogName = XraySpeedtest.displayName(cfg)
        appLogType = AppLog.cfgType(this, cfg)
        appLogEngineOn = engineOn
        appLogMode = connectMode()
        appLogNoteFa = plan?.noteFa ?: ""
        appLogNoteEn = plan?.noteEn ?: ""
        appLogSpeedMbps = plan?.speedMbps ?: -1.0
        appLogPending = true
        val engFa = if (engineOn) "موتور هوشمند: روشن" else "موتور هوشمند: خاموش"
        val engEn = if (engineOn) "Smart engine: ON" else "Smart engine: OFF"
        val modeFa = if (appLogMode == "best") "بهترین پینگ" else "عادی"
        val modeEn = if (appLogMode == "best") "best ping" else "normal"
        val noteFa = if (plan != null) "؛ روش برنده: ${plan.noteFa}" else if (engineOn) "؛ هیچ روشی برنده نشد؛ اتصال عادی" else ""
        val noteEn = if (plan != null) "; winning method: ${plan.noteEn}" else if (engineOn) "; no method won; normal connect" else ""
        // 2.45: the config's last ping, so the log
        // shows whether its ping test had answered.
        val pingMs = pingMap[XraySpeedtest.dedupKey(cfg)] ?: persistedPingMs(cfg)
        val pingFa = when {
            pingMs != null && pingMs >= 0 -> "؛ پینگ: $pingMs میلی‌ثانیه"
            pingMs != null -> "؛ پینگ: در آخرین تست ناموفق بود"
            else -> "؛ پینگ: اندازه‌گیری نشده"
        }
        val pingEn = when {
            pingMs != null && pingMs >= 0 -> "; ping: $pingMs ms"
            pingMs != null -> "; ping: failed in the last test"
            else -> "; ping: not measured"
        }
        AppLog.append(this, "v2ray",
            "تلاش اتصال «$appLogName»؛ نوع: $appLogType؛ $engFa؛ حالت: $modeFa$noteFa$pingFa.",
            "Connect attempt “$appLogName”; type: $appLogType; $engEn; mode: $modeEn$noteEn$pingEn.")
    }

    /** 2.43: outcome line for the armed attempt. */
    private fun appLogEvent(suffixFa: String, suffixEn: String) {
        val engFa = if (appLogEngineOn) "موتور هوشمند: روشن" else "موتور هوشمند: خاموش"
        val engEn = if (appLogEngineOn) "Smart engine: ON" else "Smart engine: OFF"
        val modeFa = if (appLogMode == "best") "بهترین پینگ" else "عادی"
        val modeEn = if (appLogMode == "best") "best ping" else "normal"
        val noteFa = if (appLogNoteFa.isNotBlank()) "؛ روش: $appLogNoteFa" else ""
        val noteEn = if (appLogNoteEn.isNotBlank()) "; method: $appLogNoteEn" else ""
        AppLog.append(this, "v2ray",
            "«$appLogName»؛ نوع: $appLogType؛ $engFa؛ حالت: $modeFa$noteFa؛ $suffixFa",
            "“$appLogName”; type: $appLogType; $engEn; mode: $modeEn$noteEn; $suffixEn")
    }

    /** 2.43: the refreshStatus connected one-shot,
     *  for this page's own sessions only (smart
     *  sessions are logged by the service). */
    private fun appLogConnected() {
        if (!appLogPending) return
        val tag = VpnClientService.sessionTag
        if (ProxyOnlyService.isRunning && !VpnClientService.isRunning) {
            appLogEvent("وصل شد (حالت فقط پروکسی)", "connected (proxy-only mode)")
        } else if (tag == "v2ray") {
            appLogEvent("وصل شد", "connected")
            // 2.68: one precise
            // green line when an engine method won.
            if (appLogEngineOn && appLogNoteFa.isNotBlank()) {
                val netV = AdaptiveEngine.netId(this)
                val spV = if (appLogSpeedMbps >= 0) String.format(java.util.Locale.US, "%.1f", appLogSpeedMbps) else ""
                AdaptiveEngine.winGreen(this, "v2ray",
                    "موتور هوشمند: روش برنده: «$appLogNoteFa»؛ شبکه «$netV»" + (if (spV.isNotBlank()) "؛ سرعت دانلود $spV مگابایت/ثانیه" else "") + "؛ واقعاً وصل شد",
                    "Smart engine: winning method: \u201c$appLogNoteEn\u201d; network \u201c$netV\u201d" + (if (spV.isNotBlank()) "; download speed $spV MB/s" else "") + "; really connected")
            }
            // 2.45: background real-connection check
            // (only when its toggle is ON).
            ConnVerify.verifyXray(this, "v2ray", "v2ray", VpnClientService.currentName ?: appLogName, VpnClientService.sessionSocksPort.takeIf { it > 0 } ?: VpnConfigBuilder.socksPort(this)) { VpnClientService.isRunning && VpnClientService.sessionTag == "v2ray" }
        } else {
            return
        }
        appLogPending = false
    }
    private var pendingWonCheck = false
    private var pendingWonLink: String? = null
    private var fallbackCheckToken = 0L
    private var engineReportRow: LinearLayout? = null
    private val pingMap = mutableMapOf<String, Long>()
    // CLOUDFLARE imports (1.94): dedupKey -> country code, read
    // from the Cloudflare section's prefs at refreshList(). Rows
    // for those configs show a Cloudflare label, a soft galaxy
    // tint, a name in the current language, and a flag that
    // alternates every 2s between the country flag and the
    // Cloudflare section icon.
    private var cfMap: Map<String, String> = emptyMap()
    private val cfFlagViews = mutableListOf<Triple<android.widget.ImageView, android.graphics.Bitmap, android.graphics.Bitmap>>()
    // 2.26 alien imports: dedupKeys transferred
    // from the Alien Language section, plus the
    // alternating alien/flag circles (same 2s
    // trick as Cloudflare's flag/cloud circle).
    private var alienSet: Set<String> = emptySet()
    private val alienFlagViews = mutableListOf<AlienFlagState>()
    private var alienFlagShowAlien = true
    private var alienBitmapCache: android.graphics.Bitmap? = null
    private class AlienFlagState(val alienBmp: android.graphics.Bitmap, var flagBmp: android.graphics.Bitmap) {
        lateinit var view: android.widget.ImageView
        var country: String = ""
        var city: String = ""
    }
    private val alienFlagToggle = object : Runnable {
        override fun run() {
            alienFlagShowAlien = !alienFlagShowAlien
            for (h in alienFlagViews) {
                try { h.view.setImageBitmap(if (alienFlagShowAlien) h.alienBmp else h.flagBmp) } catch (_: Exception) {}
            }
            handler.postDelayed(this, 2000)
        }
    }
    private var cfFlagShowCloud = false
    private var cfCloudBitmap: android.graphics.Bitmap? = null
    private val cfFlagToggle = object : Runnable {
        override fun run() {
            cfFlagShowCloud = !cfFlagShowCloud
            for ((view, flagBmp, cloudBmp) in cfFlagViews) {
                view.setImageBitmap(if (cfFlagShowCloud) cloudBmp else flagBmp)
            }
            handler.postDelayed(this, 2000)
        }
    }
    private var coreInitDone = false
    // Bottom status panel (v2rayNG-style): live
    // progress + short history lines, fixed at the
    // bottom so it stays visible while tests run.
    private val statusLines = mutableListOf<String>()
    private lateinit var stripLine: TextView
    private lateinit var bottomRow: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    /** True between tapping connect and the core
     *  actually coming up (or failing): the FAB
     *  shows an amber "working" look at once, so a
     *  slow DNS pre-resolve or ping test never
     *  looks like a dead tap. Cleared when the
     *  session comes up, on failure, or after a
     *  15s failsafe. */
    private var fabBusy = false
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private fun prefs() = getSharedPreferences("renamer_vpn", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the user's delay-test settings to every ping path.
        try {
            val sp = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            XraySpeedtest.testUrlOverride = sp.getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            XraySpeedtest.concurrencyOverride = sp.getString("delay_test_concurrency", null)?.toIntOrNull()
        } catch (_: Exception) {}
        scrollView = ScrollView(this)
        val scroll = scrollView
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        // Top bar, like v2rayNG's main page: hamburger (drawer)
        // + title + add + menu.
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(glassTopButton("☰") { toggleDrawer() })
        topRow.addView(TextView(this).apply {
            text = "v2ray"; textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
        })
        addBtn = glassTopButton("+") { showAddMenu() }
        topRow.addView(addBtn)
        menuBtn = glassTopButton("⋮") { showMainMenu() }
        topRow.addView(menuBtn)
        root.addView(topRow)
        // ADAPTIVE ENGINE (1.78): minimal report
        // button, visible only while the engine is
        // on (visibility refreshed in onResume).
        engineReportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (AdaptiveEngine.isEnabled(this@VpnActivity)) View.VISIBLE else View.GONE
        }
        engineReportRow?.addView(AdaptiveEngine.reportButton(this, AdaptiveEngine.S_V2RAY))
        // ADAPTIVE ENGINE (1.79): batch repair, same
        // row (so it hides with the row when the
        // engine is off). Dead configs are checked
        // one by one and repaired copies are saved
        // only after the user confirms.
        engineReportRow?.addView(Button(this).apply {
            text = AppLanguage.t(this@VpnActivity, "تعمیر دسته‌ای", "Batch repair")
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#0D47A1"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 48f
                setColor(Color.parseColor("#E3F2FD"))
                setStroke(2, Color.parseColor("#90CAF9"))
            }
            val d = resources.displayMetrics.density
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                leftMargin = (8 * d).toInt()
            }
            setOnClickListener { onBatchRepair() }
        })
        root.addView(engineReportRow)

        // No status line at the top anymore (the user's
        // word): the FAB itself shows connected /
        // disconnected. Messages go to the single-line
        // glass strip next to the FAB, at the bottom.
        connectBtn = Button(this).apply {
            text = "▶"
            textSize = 24f
        }
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // «سرورها» header removed at the user's
        // request (1.79): the page opens straight
        // into the tabs + list.
        // Subscription tab bar (v2rayNG-style): "All
        // configs" first and default, then one tab per
        // subscription (name + count). Tapping a tab
        // filters the list to that sub's configs.
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabBar)
        root.addView(tabScroll)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A little air between the tab bar and the first config.
        listContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (12 * resources.displayMetrics.density).toInt(), 0, 0) }
        root.addView(listContainer)
        // The drawer, like v2rayNG's side menu: now a WHITE
        // panel. A transparent scrim covers the rest of the
        // page while it is open, so tapping outside the
        // panel (or swiping left, handled in
        // dispatchTouchEvent) closes it; Back is no longer
        // the only way out.
        val frame = android.widget.FrameLayout(this)
        frameView = frame
        frame.addView(scroll)

        drawerScrim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        frame.addView(drawerScrim)
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 48, 32, 32)
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (240 * resources.displayMetrics.density).toInt(),
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }
        // The v2ray icon from the home page, small, instead
        // of the "v2rayNG" text (user order).
        drawerPanel.addView(android.widget.ImageView(this).apply {
            try {
                setImageBitmap(
                    android.graphics.BitmapFactory.decodeStream(assets.open("vpn.png"))
                )
            } catch (_: Exception) {
            }
            layoutParams = LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 0, 0, 16) }
        })
        val drawerItems = listOf(
            L("تنظیمات گروه سابسکریبشن", "Subscription group setting") to { showSubGroupSetting() },
            L("تنظیمات هر اپ", "Per-app settings") to { showPerAppSettings() },
            L("تنظیمات مسیریابی", "Routing Settings") to { showRoutingSettings() },
            L("فایل‌های asset", "Asset files") to { showAssetFiles() },
            L("تنظیمات", "Settings") to {
                startActivity(Intent(this, VpnSettingsActivity::class.java))
            },
            L("معرفی", "Promotion") to { showPromotion() },
            L("لاگ", "Logcat") to { showLogcat() },
            L("بررسی به‌روزرسانی", "Check for update") to { showCheckUpdate() },
            L("پشتیبان‌گیری و بازیابی", "Backup & Restore") to { showBackupRestore() },
            L("درباره", "About") to { showAbout() }
        )
        for ((label, action) in drawerItems) {
            val itemBtn = glassMenuButton(label) {
                closeDrawer()
                action()
            }
            // One line per option, never wrapped to two.
            itemBtn.isSingleLine = true
            itemBtn.ellipsize = android.text.TextUtils.TruncateAt.END
            drawerPanel.addView(itemBtn)
        }
        frame.addView(drawerPanel)
        // Dim layer for the connect-mode picker: ~60%
        // black, covering everything except the FAB and
        // the two mode circles (which are brought to the
        // front when the picker opens).
        modeDim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeModePicker() }
        }
        frame.addView(modeDim)
        // v2rayNG-style FAB: fixed at the bottom-right,
        // above the scrolling list, not inside it.
        val fabSize = (60 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        connectBtn.setPadding(0, 0, 0, 0)
        setupFabTouch()
        // Mode picker row: two circles to the LEFT of the
        // FAB, same bottom margin, hidden until long-press.
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin + fabSize + 12)
            }
        }
        modeRow.addView(modeOption(
            L("نرمال", "Normal"), Color.parseColor("#FFF9C4"), Color.parseColor("#666666"),
            L("با انتخاب این کلید شما به کانفیگ انتخابی خودتون وصل میشید",
              "With this key you connect to the config you selected")
        ) { selectConnectMode("normal") })
        modeRow.addView(modeOption(
            L("بهترین\nپینگ", "Best\nping"), Color.parseColor("#E3F2FD"), Color.parseColor("#1565C0"),
            L("با انتخاب این دکمه برنامه بصورت خودکار تست پینگ میگیره و به بهترین کانفیگ وصل میشه",
              "With this button the app automatically tests ping and connects to the best config")
        ) { selectConnectMode("best") })
        frame.addView(modeRow)
        // Hint next to the FAB, shown for 5 seconds on
        // each visit until the first long-press.
        hintText = TextView(this).apply {
            text = L("برای تنظیم نوع اتصال دکمه اتصال را نگه دارید",
                     "Hold the connect button to set the connection type")
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            setPadding(24, 16, 24, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin + fabSize + 12)
            }
        }
        frame.addView(hintText)
        // Bottom row, per the user's sketch: ONE
        // single-line glass strip BESIDE the start
        // button — not a separate row, not a big
        // panel. The strip is a pill, so its end near
        // the button curves around the circle, and the
        // gap to the button is small (8dp). It shows
        // only the latest line (ping progress, test
        // report, action counts); tapping it opens
        // the full history.
        bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(fabMargin, 0, fabMargin, fabMargin)
            }
        }
        stripLine = TextView(this).apply {
            text = L("آماده", "Ready")
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 0, 28, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 28 * resources.displayMetrics.density
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            elevation = 3 * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f)
        }
        stripLine.setOnClickListener { showStatusHistoryDialog() }
        bottomRow.addView(stripLine)
        bottomRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1)
        })
        connectBtn.layoutParams = LinearLayout.LayoutParams(fabSize, fabSize)
        bottomRow.addView(connectBtn)
        frame.addView(bottomRow)
        // Leave room at the bottom of the list so the FAB
        // never hides the last server row.
        root.setPadding(32, 32, 32, fabSize + fabMargin + (24 * resources.displayMetrics.density).toInt())
        setContentView(frame)
        // Widget tap: auto-connect shortly after the page opens.
        if (intent.getBooleanExtra("widget_auto_connect", false) && !VpnClientService.isRunning) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { if (!VpnClientService.isRunning) toggleConnect() } catch (_: Exception) {}
            }, 600)
        }
        frame.fitsSystemWindows = true

        servers = prefs().getString("servers", "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        selected = prefs().getString("selected", null)?.takeIf { it.isNotBlank() }
        if (selected != null && !servers.contains(selected)) selected = null
        refreshList()
        refreshStatus()
        // Automatic subscription update: overdue
        // enabled auto-update subs refresh in the
        // background when the page opens.
        Thread {
            try {
                val updated = VpnSubs.checkAutoUpdate(this)
                if (updated > 0) {
                    servers = VpnSubs.loadServers(this)
                    if (selected != null && !servers.contains(selected)) selected = null
                    runOnUiThread { refreshList() }
                }
            } catch (_: Exception) {}
        }.start()
        // Automatic connect at startup, only when the
        // user turned it on, a config is selected, and
        // VPN permission was already granted once
        // (prepare == null) — never prompt at startup.
        if (prefs().getBoolean("auto_connect_startup", false) &&
            selected != null && !VpnClientService.isRunning) {
            try {
                if (android.net.VpnService.prepare(this) == null) {
                    handler.postDelayed({
                        try {
                            if (!VpnClientService.isRunning && selected != null) {
                                toggleConnect()
                            }
                        } catch (_: Exception) {}
                    }, 1000)
                }
            } catch (_: Exception) {}
        }
    }

    /** Small round button for a server row (share / delete).
     *  Pale colors now: a light background with a dark
     *  icon color, instead of a saturated solid button. */
    // Glass circle button: monochrome, no colours.
    // A translucent white circle with a thin grey
    // rim and a little elevation, so it reads as
    // glass on the pale card. 30dp (was 34dp) and
    // 5dp side gaps, so buttons never touch.
    private fun lightOval(fill: Int, stroke: Int, widthDp: Float): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            setStroke((widthDp * d).toInt(), stroke)
        }
    }

    private fun lightRect(fill: Int, stroke: Int, widthDp: Float, radiusDp: Float): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(fill)
            setStroke((widthDp * d).toInt(), stroke)
        }
    }

    private fun glassCircleButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        return Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            background = lightOval(Color.parseColor("#E8FFFFFF"), Color.parseColor("#55000000"), 1f)
            elevation = 3 * d
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
    }

    // The card behind one server row: dark glass
    // fill, neon gradient border, generous rounding,
    // like the reference card. Compact (8dp vertical
    // padding, 8dp gap between cards), and the whole
    // border turns bright green while the card is
    // selected.
    private fun serverCard(isSelected: Boolean = false): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (isSelected) {
                lightRect(Color.parseColor("#F2F5F9"), Color.parseColor("#2E9E5B"), 2f, 20f)
            } else {
                lightRect(Color.parseColor("#F2F5F9"), Color.parseColor("#4A5A7A"), 1f, 20f)
            }
            setPadding((12 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
        }
    }

    // Vertical protocol label on the card's edge,
    // like the reference card: rotated so it reads
    // bottom-to-top, in the reference orange.
    private fun protocolEdgeLabel(protocol: String): LinearLayout {
        val d = resources.displayMetrics.density
        val w = minOf(170, protocol.length * 7 + 20)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(this@VpnActivity).apply {
                text = protocol
                textSize = if (protocol.length > 12) 10f else 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#E8913A"))
                gravity = Gravity.CENTER
                rotation = -90f
                layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (30 * d).toInt())
            })
        }
    }

    // Edit one config in place: the link itself is
    // editable, validated the same way as a new add.
    private fun editConfig(oldCfg: String) {
        val input = android.widget.EditText(this).apply {
            setText(oldCfg)
            setPadding(16, 16, 16, 16)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("ویرایش کانفیگ", "Edit config"))
            .setView(input)
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val nc = input.text.toString().trim()
                if ((!nc.contains("://") && !XraySpeedtest.isCustom(nc)) || (XraySpeedtest.outboundFor(nc) == null && !XraySpeedtest.isChain(nc))) {
                    statusText.text = L("کانفیگ واردشده معتبر نیست.", "The entered config is not valid.")
                    return@setPositiveButton
                }
                val idx = servers.indexOf(oldCfg)
                if (idx >= 0) servers[idx] = nc
                if (selected == oldCfg) selected = nc
                saveServers(); refreshList(); refreshStatus()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun toggleDrawer() {
        if (drawerPanel.visibility == View.VISIBLE) closeDrawer()
        else {
            drawerScrim.visibility = View.VISIBLE
            drawerPanel.visibility = View.VISIBLE
        }
    }

    private fun closeDrawer() {
        drawerPanel.visibility = View.GONE
        drawerScrim.visibility = View.GONE
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Swipe left on the open drawer closes it.
        if (::drawerPanel.isInitialized && drawerPanel.visibility == View.VISIBLE) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> drawerDownX = ev.x
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = drawerDownX - ev.x
                    if (dx > 120 * resources.displayMetrics.density) {
                        closeDrawer()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::modeRow.isInitialized && modeRow.visibility == View.VISIBLE) {
            closeModePicker()
            return
        }
        if (::drawerPanel.isInitialized && drawerPanel.visibility == View.VISIBLE) {
            closeDrawer()
            return
        }
        super.onBackPressed()
    }

    // --- Glass buttons ---
    /** A small round glass button for the top bar. On
     *  touch it turns pale blue for 2 seconds, then
     *  returns to glass. */
    private fun glassTopButton(label: String, onClick: () -> Unit): Button {
        val size = (40 * resources.displayMetrics.density).toInt()
        val glass = lightOval(Color.parseColor("#E0FFFFFF"), Color.parseColor("#DDDDDD"), 1f)
        val pale = lightOval(Color.parseColor("#E3F2FD"), Color.parseColor("#BBDEFB"), 1f)
        return Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            background = glass
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(0, 0, 0, 0)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    background = pale
                    handler.postDelayed({ background = glass }, 2000)
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    /** A glass menu button: very pale blue, rounded,
     *  separated from its neighbours by margins. */
    private fun glassMenuButton(label: String, onClick: () -> Unit): Button {
        val pale = lightRect(Color.parseColor("#E8F4FD"), Color.parseColor("#BBDEFB"), 1f, 20f)
        val stronger = lightRect(Color.parseColor("#BBDEFB"), Color.parseColor("#90CAF9"), 1f, 20f)
        return Button(this).apply {
            text = label; textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            background = pale
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            setPadding(24, 20, 24, 20)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    background = stronger
                    handler.postDelayed({ background = pale }, 2000)
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    /** A glass menu dialog: a vertical list of separate
     *  pale-blue glass buttons, with the same spacing
     *  the old list dialogs had. */
    private fun showGlassMenuDialog(title: String, items: Array<String>, onSelect: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(L("بستن", "Close"), null)
            .create()
        for ((i, label) in items.withIndex()) {
            col.addView(glassMenuButton(label) {
                dialog.dismiss()
                onSelect(i)
            })
        }
        dialog.show()
    }

    // --- Connect mode picker ---
    private fun modeOption(label: String, bg: Int, fg: Int, explanation: String, onSelect: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }
        val size = (88 * resources.displayMetrics.density).toInt()
        val circle = Button(this).apply {
            text = label; textSize = 12f
            setTextColor(fg)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(bg)
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onSelect() }
        }
        row.addView(circle)
        val infoSize = (28 * resources.displayMetrics.density).toInt()
        row.addView(Button(this).apply {
            text = "!"; textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            layoutParams = LinearLayout.LayoutParams(infoSize, infoSize).apply {
                setMargins(4, 0, 0, 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@VpnActivity)
                    .setMessage(explanation)
                    .setPositiveButton(L("بستن", "Close"), null)
                    .show()
            }
        })
        return row
    }

    private fun onFabLongPress() {
        fabLongPressed = true
        // First long-press ever: the hint never shows again.
        prefs().edit().putBoolean("connect_mode_done", true).apply()
        hintText.visibility = View.GONE
        closeDrawer()
        modeDim.visibility = View.VISIBLE
        modeRow.visibility = View.VISIBLE
        frameView.bringChildToFront(bottomRow)
        frameView.bringChildToFront(modeRow)
        refreshFab()
    }

    private fun closeModePicker() {
        modeDim.visibility = View.GONE
        modeRow.visibility = View.GONE
    }

    private fun selectConnectMode(mode: String) {
        prefs().edit().putString("connect_mode", mode)
            .putBoolean("connect_mode_done", true).apply()
        closeModePicker()
        refreshFab()
    }

    private fun setupFabTouch() {
        connectBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fabLongPressed = false
                    fabDownX = ev.x
                    fabDownY = ev.y
                    // Glass touch: dark glass + cyan ring for 2 seconds.
                    fabGlassUntil = System.currentTimeMillis() + 2000
                    connectBtn.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#E3F2FD"))
                        setStroke(3, Color.parseColor("#BBDEFB"))
                    }
                    connectBtn.setTextColor(Color.parseColor("#1565C0"))
                    handler.removeCallbacks(fabLongPressRunnable)
                    // 1 second (was 2): the mode picker
                    // must open fast.
                    handler.postDelayed(fabLongPressRunnable, 1000)
                    handler.postDelayed({ refreshFab() }, 2000)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.hypot(ev.x - fabDownX, ev.y - fabDownY)
                    if (moved > 30 * resources.displayMetrics.density) {
                        handler.removeCallbacks(fabLongPressRunnable)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(fabLongPressRunnable)
                    if (fabLongPressed) {
                        fabLongPressed = false
                        true
                    } else if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        toggleConnect()
                        // Keep the pale-blue glass for the
                        // rest of the 2 seconds, then restore.
                        handler.postDelayed({ refreshFab() }, 2000)
                        true
                    } else true
                }
                else -> true
            }
        }
    }

    /** The FAB itself: dark glass with a neon ring.
     *  Connected: green neon ring. Busy: orange ring.
     *  Normal mode: cyan-to-amber ring, letter N.
     *  Best mode: cyan-to-violet ring, letter B.
     *  No mode yet: gray ring, ▶. During the
     *  2-second glass touch the dark glass + cyan
     *  ring is kept. */
    private fun refreshFab() {
        if (System.currentTimeMillis() < fabGlassUntil &&
            modeRow.visibility != View.VISIBLE && !fabBusy) {
            return
        }
        val mode = connectMode()
        // The FAB is "connected" ONLY for a v2ray
        // session (manual, widget, or smart's v2ray
        // config). Another section's session must
        // never light this button up.
        val ownSession = VpnClientService.isRunning &&
            VpnClientService.sessionTag in arrayOf("v2ray", "smart", null)
        // 2.45: a DEAD background verdict demotes
        // the button from green — the tunnel is up
        // but carries no traffic, so green would be
        // a lie. 2.54: the demoted label is a red !
        // (warning), NOT ✕ — ✕ reads as "disconnect
        // me" and pulled users into tapping away a
        // session the app never stopped itself.
        // Tapping still disconnects (toggleConnect
        // keys on isRunning, not on this color);
        // the session itself is never stopped by a
        // verdict alone.
        val verifyDead = ownSession && ConnVerify.isDeadV2ray(this, VpnClientService.sessionTag, VpnClientService.currentName)
        val connected = (ownSession && !verifyDead) || ProxyOnlyService.isRunning
        if (connected) fabBusy = false
        // Blink phase: the ring (stroke) alternates between
        // its color and a lighter shade; the fill and the
        // letter never change.
        val (bg, fg, label) = when {
            verifyDead -> Triple(lightOval(Color.parseColor("#FFCDD2"), Color.parseColor("#D32F2F"), 2f),
                Color.parseColor("#B71C1C"),
                "!")
            connected -> Triple(lightOval(Color.parseColor("#C8E6C9"), Color.parseColor(if (fabBlinkLight) "#81C784" else "#2E9E5B"), 2f),
                Color.WHITE,
                if (mode == "normal") "N" else if (mode == "best") "B" else "■")
            fabBusy -> Triple(lightOval(Color.parseColor("#FFE0B2"), Color.parseColor("#E8913A"), 2f),
                Color.parseColor("#E65100"),
                "…")
            mode == "normal" -> Triple(lightOval(Color.parseColor("#FFF9C4"), Color.parseColor(if (fabBlinkLight) "#FFD54F" else "#F9A825"), 2f),
                Color.parseColor("#666666"),
                "N")
            mode == "best" -> Triple(lightOval(Color.parseColor("#E3F2FD"), Color.parseColor(if (fabBlinkLight) "#64B5F6" else "#1565C0"), 2f),
                Color.parseColor("#1565C0"),
                "B")
            else -> Triple(lightOval(Color.parseColor("#E0E0E0"), Color.parseColor("#9E9E9E"), 2f),
                Color.parseColor("#666666"),
                "▶")
        }
        connectBtn.text = label
        connectBtn.setTextColor(fg)
        connectBtn.background = bg
    }

    /** Subscription groups: the sub links now live here,
     *  not on the main page (like v2rayNG's drawer). */
    // Subscription group setting is now a real page
    // (list + per-sub settings, v2rayNG-style),
    // not the old add-a-link dialog.
    private fun showSubGroupSetting() {
        startActivity(Intent(this, VpnSubActivity::class.java))
    }

    private fun showPerAppSettings() {
        val mode = prefs().getString("per_app_mode", "bypass") ?: "bypass"
        val saved = prefs().getString("per_app_set", "")?.lines()
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تنظیمات هر اپ", "Per-app settings"))
            .setItems(arrayOf(
                L("Bypass selected apps (پروکسی نشوند)", "Bypass selected apps (do not proxy)"),
                L("Only selected apps (فقط این‌ها پروکسی شوند)", "Only selected apps (only these proxied)"),
                L("Disabled (همه اپ‌ها پروکسی شوند)", "Disabled (all apps proxied)")
            )) { _, which ->
                val newMode = when (which) {
                    0 -> "bypass"; 1 -> "only"; else -> "disabled"
                }
                if (newMode == "disabled") {
                    prefs().edit().putString("per_app_mode", "disabled")
                        .putString("per_app_set", "").apply()
                    statusText.text = L("Per-app غیرفعال شد.", "Per-app disabled.")
                    return@setItems
                }
                val apps = packageManager.getInstalledApplications(0)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { packageManager.getApplicationLabel(it).toString() }
                val names = apps.map {
                    packageManager.getApplicationLabel(it).toString()
                }.toTypedArray()
                val checked = BooleanArray(apps.size) { i ->
                    saved.contains(apps[i].packageName)
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle(L("اپ‌ها را انتخاب کن", "Select apps"))
                    .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }
                            .map { it.packageName }
                        prefs().edit().putString("per_app_mode", newMode)
                            .putString("per_app_set", chosen.joinToString("\n")).apply()
                        statusText.text = L("Per-app ذخیره شد: ${chosen.size} اپ (${if (newMode == "bypass") "bypass" else "only"}).", "Per-app saved: ${chosen.size} apps (${if (newMode == "bypass") "bypass" else "only"}).")
                    }
                    .setNegativeButton(L("لغو", "Cancel"), null)
                    .show()
            }
            .show()
    }

    private fun showRoutingSettings() {
        val cur = prefs().getString("routing_mode", "bypass_private") ?: "bypass_private"
        val labels = arrayOf(
            L("Global (همه از پروکسی)", "Global (all via proxy)"),
            L("Bypass private (خصوصی مستقیم، بقیه پروکسی)", "Bypass private (private direct, rest proxy)")
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تنظیمات مسیریابی", "Routing Settings"))
            .setSingleChoiceItems(labels, if (cur == "global") 0 else 1, null)
            .setPositiveButton(L("ذخیره", "Save")) { dialog, _ ->
                val list = (dialog as android.app.AlertDialog).listView
                val chosen = if (list.checkedItemPosition == 0) "global" else "bypass_private"
                prefs().edit().putString("routing_mode", chosen).apply()
                statusText.text = L("Routing ذخیره شد: ${if (chosen == "global") "Global" else "Bypass private"}.", "Routing saved: ${if (chosen == "global") "Global" else "Bypass private"}.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun showAssetFiles() {
        val dir = getDir("assets", MODE_PRIVATE)
        val geoip = java.io.File(dir, "geoip.dat")
        val geosite = java.io.File(dir, "geosite.dat")
        val text = L("geoip.dat: ${if (geoip.exists()) "${geoip.length()} بایت" else "وجود ندارد"}\n", "geoip.dat: ${if (geoip.exists()) "${geoip.length()} bytes" else "missing"}\n") +
            L("geosite.dat: ${if (geosite.exists()) "${geosite.length()} بایت" else "وجود ندارد"}\n", "geosite.dat: ${if (geosite.exists()) "${geosite.length()} bytes" else "missing"}\n") +
            L("پوشه: ${dir.absolutePath}", "Folder: ${dir.absolutePath}")
        android.app.AlertDialog.Builder(this)
            .setTitle(L("فایل‌های asset", "Asset files"))
            .setMessage(text)
            .setPositiveButton(L("بستن", "Close"), null)
            .setNeutralButton(L("کپی دوباره از داخل اپ", "Copy again from inside the app")) { _, _ ->
                try {
                    CoreInit.init(this)
                    statusText.text = L("فایل‌های asset دوباره کپی شدند.", "Asset files copied again.")
                } catch (e: Exception) {
                    statusText.text = L("کپی asset ناموفق: ${e.message}", "Failed to copy assets: ${e.message}")
                }
            }
            .show()
    }

    private fun showAppSettings() {
        // Settings now has two sections: connection
        // settings, and app language (فارسی / English).
        showGlassMenuDialog("Settings", arrayOf(
            L("تنظیمات اتصال (MTU / DNS / SOCKS)", "Connection settings (MTU / DNS / SOCKS)"),
            L("زبان برنامه / App language", "App language / زبان برنامه")
        )) { which ->
            if (which == 1) {
                showGlassMenuDialog(L("زبان برنامه", "App language"), arrayOf("فارسی", "English")) { langWhich ->
                    AppLanguage.set(this, if (langWhich == 0) "fa" else "en")
                    statusText.text = L("زبان برنامه تغییر کرد.", "App language changed.")
                    recreate()
                }
                return@showGlassMenuDialog
            }
            val curMtu = prefs().getString("mtu", "1500") ?: "1500"
            val curDns = prefs().getString("dns", "1.1.1.1,8.8.8.8") ?: "1.1.1.1,8.8.8.8"
            val curPort = prefs().getString("socks_port", "10808") ?: "10808"
            showForm("Settings", listOf(
                "MTU" to curMtu, L("DNS (با کاما جدا کن)", "DNS (comma-separated)") to curDns, L("پورت SOCKS", "SOCKS port") to curPort
            )) { v ->
                prefs().edit()
                    .putString("mtu", v["MTU"] ?: "1500")
                    .putString("dns", v[L("DNS (با کاما جدا کن)", "DNS (comma-separated)")] ?: "1.1.1.1,8.8.8.8")
                    .putString("socks_port", v[L("پورت SOCKS", "SOCKS port")] ?: "10808")
                    .apply()
                statusText.text = L("تنظیمات ذخیره شد (برای اتصال بعدی اعمال می‌شود).", "Settings saved (applied on next connect).")
            }
        }
    }

    private fun showPromotion() {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("معرفی", "Promotion"))
            .setMessage(L("در این اپ تبلیغی وجود ندارد.", "There is no promotion in this app."))
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showLogcat() {
        val log = try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200"))
            val out = proc.inputStream.bufferedReader().readText()
            out.lines().filter { it.contains("com.renamer") || it.contains("libv2ray") }
                .takeLast(100).joinToString("\n").ifBlank { L("لاگی برای این اپ ثبت نشده است.", "No log recorded for this app.") }
        } catch (e: Exception) {
            L("خواندن لاگ ناموفق: ${e.message}\nآخرین خطا: ${VpnClientService.lastError ?: "—"}", "Failed to read log: ${e.message}\nLast error: ${VpnClientService.lastError ?: "—"}")
        }
        val tv = TextView(this).apply {
            text = log; textSize = 12f
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("لاگ", "Logcat"))
            .setView(scroll)
            .setPositiveButton(L("بستن", "Close"), null)
            .setNeutralButton(L("کپی لاگ", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("log", log))
                    statusText.text = L("لاگ کپی شد.", "Log copied.")
                } catch (e: Exception) {
                    statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                }
            }
            .show()
    }

    private fun showCheckUpdate() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("بررسی به‌روزرسانی", "Check for update"))
            .setMessage(L("نسخه فعلی: ${info?.versionName ?: "?"} (کد ${info?.versionCode ?: "?"})\nبه‌روزرسانی این اپ از طریق لینک دانلودی که سازنده می‌فرستد انجام می‌شود.", "Current version: ${info?.versionName ?: "?"} (code ${info?.versionCode ?: "?"})\nUpdates for this app come through the download link sent by the creator."))
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showBackupRestore() {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("پشتیبان‌گیری و بازیابی", "Backup & Restore"))
            .setItems(arrayOf(L("Backup (ذخیره در فایل)", "Backup (save to file)"), L("Restore (خواندن از فایل)", "Restore (read from file)"))) { _, which ->
                if (which == 0) {
                    val json = JSONObject().apply {
                        put("servers", JSONArray(servers))
                        put("selected", selected ?: "")
                        put("vpn_subs", prefs().getString("vpn_subs", ""))
                        put("per_app_mode", prefs().getString("per_app_mode", ""))
                        put("per_app_set", prefs().getString("per_app_set", ""))
                        put("routing_mode", prefs().getString("routing_mode", ""))
                    }.toString()
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "renamer-backup.json")
                    }
                    pendingBackupJson = json
                    startActivityForResult(intent, 400)
                } else {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, L("انتخاب فایل بکاپ", "Choose a backup file")), 401)
                }
            }
            .show()
    }

    private var pendingBackupJson: String? = null

    private fun showAbout() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("درباره", "About"))
            .setMessage(L("calculator\nنسخه ${info?.versionName ?: "?"}\nهسته: Xray (مانند v2rayNG)\ncreated by : kamyar", "calculator\nVersion ${info?.versionName ?: "?"}\nCore: Xray (like v2rayNG)\ncreated by : kamyar"))
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusPoller)
        handler.post(cfFlagToggle)
        handler.post(alienFlagToggle)
        handler.post(fabBlinkRunnable)
        // ADAPTIVE ENGINE (1.78): report button
        // follows the toggle; a stale one-shot plan
        // never survives an engine-off return.
        AdaptiveEngine.setReportVisible(engineReportRow, this, AdaptiveEngine.S_V2RAY)
        if (!AdaptiveEngine.isEnabled(this)) { engineLink = null; enginePlan = null }
        // Hint next to the FAB, for 5 seconds, until the
        // first long-press has ever happened.
        if (!modeDone() && ::hintText.isInitialized) {
            hintText.visibility = View.VISIBLE
            handler.postDelayed({
                if (!modeDone()) hintText.visibility = View.GONE
            }, 5000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
        handler.removeCallbacks(cfFlagToggle)
        handler.removeCallbacks(alienFlagToggle)
        handler.removeCallbacks(fabBlinkRunnable)
    }

    private fun saveServers() {
        prefs().edit()
            .putString("servers", servers.joinToString("\n"))
            .putString("selected", selected ?: "")
            .apply()
    }

    // toTop = true only for clipboard import: the
    // user asked clipboard configs to land at the
    // TOP of the list, not the bottom. Every other
    // import path keeps appending.
    private fun addFromText(text: String, toTop: Boolean = false) {
        val seen = servers.map { XraySpeedtest.dedupKey(it) }.toMutableSet()
        val fresh = mutableListOf<String>()
        for (l in XraySpeedtest.splitConfigs(text)) {
            val c = l.trim()
            if (c.isBlank()) continue
            if (!c.contains("://") && !XraySpeedtest.isCustom(c)) continue
            if (seen.add(XraySpeedtest.dedupKey(c))) fresh += c
        }
        if (toTop) servers.addAll(0, fresh) else servers += fresh
        val added = fresh.size
        if (added == 0) {
            statusText.text = L("کانفیگ جدیدی اضافه نشد.", "No new config added.")
        } else {
            if (selected == null) selected = servers.firstOrNull()
            saveServers(); refreshList()
            statusText.text = L("$added کانفیگ اضافه شد.", "$added configs added.")
        }
        autoDedupIfEnabled()
    }

    /** Shows a message on screen for exactly 3 seconds
     *  as a temporary overlay TextView on the root frame
     *  (not a Toast, so the duration is exact). A new
     *  message replaces the old one and restarts the
     *  3-second timer. */
    private fun showGeoMessage(text: String) {
        if (!::frameView.isInitialized) return
        handler.removeCallbacks(geoOverlayRemover)
        geoOverlay?.let { frameView.removeView(it) }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#CC333333"))
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(32, 0, 32, 200)
            }
        }
        geoOverlay = tv
        frameView.addView(tv)
        frameView.bringChildToFront(tv)
        handler.postDelayed(geoOverlayRemover, 3000)
    }

    /** Configs visible under the current tab:
     *  all servers, one sub's configs (by the
     *  membership recorded at its last update), or
     *  one manual group's range. Configs fetched
     *  before the tab feature have no membership and
     *  show under All only, until their sub updates. */
    private fun visibleConfigs(): List<String> {
        if (selectedTab.isBlank()) return servers
        if (selectedTab == "CF::CLOUDFLARE") {
            val cm = loadCfMap()
            return servers.filter { cfg ->
                !XraySpeedtest.isGroup(cfg) &&
                    cm.containsKey(XraySpeedtest.dedupKey(cfg))
            }
        }
        if (selectedTab.startsWith("GROUP::")) {
            return VpnSubs.groupMembers(servers, selectedTab)
        }
        val items = VpnSubs.loadItems(this)
        val item = items.firstOrNull { it.url == selectedTab } ?: return servers
        return servers.filter { cfg ->
            !XraySpeedtest.isGroup(cfg) &&
                item.configKeys.contains(XraySpeedtest.dedupKey(cfg))
        }
    }

    /** Rebuild the tab bar: "All configs" first,
     *  then one tab per subscription (name + count),
     *  then manual group tabs when the show-all-groups
     *  setting is on. The selection survives when its
     *  tab still exists, otherwise it falls back to All. */
    private fun refreshTabBar() {
        if (!::tabBar.isInitialized) return
        val items = VpnSubs.loadItems(this)
        val showGroups = prefs().getBoolean("show_all_groups", false)
        val tabs = mutableListOf<Pair<String, String>>()
        tabs += "" to L("تمام کانفیگ‌ها", "All configs")
        // Cloudflare tab (NOT a subscription: it never
        // appears in the subscriptions section, and it
        // cannot be edited there — it vanishes when its
        // last config is deleted.
        val cm = loadCfMap()
        val cfCount = servers.count { cfg ->
            !XraySpeedtest.isGroup(cfg) &&
                cm.containsKey(XraySpeedtest.dedupKey(cfg))
        }
        if (cfCount > 0) {
            tabs += "CF::CLOUDFLARE" to "${L("کلادفلر", "Cloudflare")} ($cfCount)"
        }
        for (item in items) {
            val count = servers.count { cfg ->
                !XraySpeedtest.isGroup(cfg) &&
                    item.configKeys.contains(XraySpeedtest.dedupKey(cfg))
            }
            tabs += item.url to "${item.displayName()} ($count)"
        }
        if (showGroups) {
            for (cfg in servers) {
                if (XraySpeedtest.isGroup(cfg)) {
                    tabs += cfg to XraySpeedtest.groupName(cfg)
                }
            }
        }
        if (selectedTab.isNotBlank() && tabs.none { it.first == selectedTab }) {
            selectedTab = ""
        }
        tabBar.removeAllViews()
        val d = resources.displayMetrics.density
        for ((key, label) in tabs) {
            val isSel = key == selectedTab
            val tv = TextView(this).apply {
                text = label; textSize = 14f
                setTypeface(null, if (isSel) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL)
                setTextColor(if (isSel) Color.parseColor("#E8913A")
                    else Color.parseColor("#666666"))
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                setOnClickListener { selectedTab = key; refreshList() }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv)
                addView(View(this@VpnActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (2 * d).toInt())
                    setBackgroundColor(if (isSel) Color.parseColor("#E8913A")
                        else Color.TRANSPARENT)
                })
            }
            tabBar.addView(col)
        }
    }

    private fun confirmDelete(name: String, onConfirm: () -> Unit) {
        if (!prefs().getBoolean("confirm_delete", false)) {
            onConfirm()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف کانفیگ", "Delete config"))
            .setMessage(L("کانفیگ «$name» حذف شود؟", "Delete config “$name”?"))
            .setPositiveButton(L("حذف", "Delete")) { _, _ -> onConfirm() }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    /** Country code for a Cloudflare-imported config, or null. */
    private fun cfCountryOf(cfg: String): String? {
        return cfMap[XraySpeedtest.dedupKey(cfg)]
    }

    private fun cfDisplayName(code: String): String {
        return when (code) {
            "de" -> L("کلودفلر - آلمان", "Cloudflare - Germany")
            "nl" -> L("کلودفلر - هلند", "Cloudflare - Netherlands")
            "fr" -> L("کلودفلر - فرانسه", "Cloudflare - France")
            "cn" -> L("کلودفلر - چین", "Cloudflare - China")
            "us" -> L("کلودفلر - آمریکا", "Cloudflare - United States")
            "ca" -> L("کلودفلر - کانادا", "Cloudflare - Canada")
            "gb" -> L("کلودفلر - انگلیس", "Cloudflare - United Kingdom")
            "nop" -> L("کلودفلر - بدون پروکسی", "Cloudflare - No proxy")
            else -> L("کلودفلر", "Cloudflare")
        }
    }

    private fun cfCountryName(code: String): String {
        return when (code) {
            "de" -> L("آلمان", "Germany")
            "nl" -> L("هلند", "Netherlands")
            "fr" -> L("فرانسه", "France")
            "cn" -> L("چین", "China")
            "us" -> L("آمریکا", "United States")
            "ca" -> L("کانادا", "Canada")
            "gb" -> L("انگلیس", "United Kingdom")
            "nop" -> L("بدون پروکسی", "No proxy")
            else -> L("نامشخص", "unknown")
        }
    }

    /** Flag emoji drawn onto a bitmap, so the row's ImageView can
     *  alternate between the flag and the Cloudflare icon. */
    private fun flagBitmap(flag: String): android.graphics.Bitmap {
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 64f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(flag, size / 2f, y, paint)
        return bmp
    }

    /** Alien-imported dedupKeys, fresh from
     *  prefs every time, like loadCfMap. */
    private fun loadAlienSet(): Set<String> {
        return try {
            val arr = org.json.JSONArray(
                getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                    .getString("alien_configs", "[]") ?: "[]")
            val s = mutableSetOf<String>()
            for (i in 0 until arr.length()) s += arr.optString(i)
            s
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun alienBitmap(): android.graphics.Bitmap {
        alienBitmapCache?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("alien.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            flagBitmap("\uD83D\uDC7D")
        }
        alienBitmapCache = bmp
        return bmp
    }

    /** Deep galaxy card for alien rows (darker
     *  than Cloudflare's soft lavender tint). */
    private fun galaxyCardBg(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#1A237E"),
                Color.parseColor("#4A148C"),
                Color.parseColor("#006064")
            )).apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#7E57C2"))
        }
    }

    /** Light text for a galaxy card: name white,
     *  host pale grey, ping light green (or pale
     *  grey when unmeasured). Children order is
     *  fixed by the row builder: dot, info, ping. */
    private fun recolorRowForGalaxy(row: android.widget.LinearLayout) {
        try {
            val info = row.getChildAt(1)
            if (info is android.view.ViewGroup) {
                for (i in 0 until info.childCount) {
                    val tv = info.getChildAt(i)
                    if (tv is android.widget.TextView) {
                        tv.setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#CFD8DC"))
                    }
                }
            }
            val ping = row.getChildAt(2)
            if (ping is android.widget.TextView) {
                ping.setTextColor(
                    if (ping.text.toString() == "—") Color.parseColor("#B0BEC5")
                    else Color.parseColor("#A5D6A7"))
            }
        } catch (_: Exception) {}
    }

    private fun cloudBitmap(): android.graphics.Bitmap {
        cfCloudBitmap?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("cf.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            flagBitmap("☁️")
        }
        cfCloudBitmap = bmp
        return bmp
    }

    /** Cloudflare import map (dedupKey -> country code), fresh
     *  from prefs every time, so the tab count never goes stale. */
    private fun loadCfMap(): Map<String, String> {
        return try {
            val o = org.json.JSONObject(
                getSharedPreferences("renamer_cf", MODE_PRIVATE)
                    .getString("cf_imported", "") ?: ""
            )
            val m = mutableMapOf<String, String>()
            for (k in o.keys()) m[k] = o.optString(k, "")
            m
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Flag emoji for a Cloudflare country code: the fixed
     *  four, or a scanned country's stored flag. */
    private fun cfFlagEmoji(code: String): String {
        when (code) {
            "de" -> return "🇩🇪"
            "nl" -> return "🇳🇱"
            "fr" -> return "🇫🇷"
            "cn" -> return "🇨🇳"
            "us" -> return "🇺🇸"
            "ca" -> return "🇨🇦"
            "gb" -> return "🇬🇧"
        }
        return try {
            val arr = org.json.JSONArray(
                getSharedPreferences("renamer_cf", MODE_PRIVATE)
                    .getString("cf_proxy_countries", "[]") ?: "[]"
            )
            var out = ""
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("code", "") == code) {
                    out = o.optString("flag", "")
                    break
                }
            }
            out
        } catch (_: Exception) {
            ""
        }
    }

    // rows for configs this core cannot connect
    // (hysteria2/hy2/tuic have no outbound) must not
    // look normal — they get an honest suffix, and
    // the first sighting is logged once (generic).
    private val testLoggedUnsupported = mutableSetOf<String>()
    private fun unsupportedSuffix(cfg: String): String {
        val scheme = cfg.substringBefore("://").lowercase()
        if (scheme != "hysteria2" && scheme != "hy2" && scheme != "tuic") return ""
        val unsupported = try { XraySpeedtest.outboundFor(cfg) == null } catch (_: Exception) { false }
        if (!unsupported) return ""
        if (testLoggedUnsupported.add(XraySpeedtest.dedupKey(cfg))) {
                    }
        return L(" (پشتیبانی نمی‌شود)", " (not supported)")
    }

    /** one central-log line per connect attempt when a TEST parser
     *  fix actually applies to this config (Trojan password fallback,
     *  WS path from the link's own path). Generic: never the config. */
    private fun testLogConnectFixes(cfg: String) {
        try {
            val raw = cfg.substringBefore("#")
            if (raw.startsWith("trojan://") && raw.contains("@")) {
                val ui = raw.removePrefix("trojan://").substringBefore("@")
                if (ui.isNotBlank() && !ui.contains(":")) {
                                    }
            }
            val scheme = raw.substringBefore("://").lowercase()
            if (scheme == "vless" || scheme == "trojan") {
                val uri = java.net.URI(raw)
                val params = (uri.rawQuery ?: "").split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }.toMap()
                val p = uri.path ?: ""
                if (params["type"] == "ws" && params["path"] == null && p.isNotBlank() && p != "/") {
                                    }
            }
        } catch (_: Exception) {}
    }

    private fun refreshList() {
        cfFlagViews.clear()
        cfMap = loadCfMap()
        alienFlagViews.clear()
        alienSet = loadAlienSet()
        refreshTabBar()
        listContainer.removeAllViews()
        val visible = visibleConfigs()
        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (selectedTab.isBlank())
                    L("هنوز کانفیگی اضافه نشده است.", "No config added yet.")
                else
                    L("این زبانه کانفیگی ندارد.", "This tab has no configs.")
                setTextColor(Color.GRAY)
            })
            return
        }
        val doubleCol = prefs().getBoolean("double_column", false)
        val cards = mutableListOf<View>()
        for ((vi, cfg) in visible.withIndex()) {
            val isGroup = XraySpeedtest.isGroup(cfg)
            val srv = XraySpeedtest.parseServer(cfg)
            val cfCountry = cfCountryOf(cfg)
            val name = cfCountry?.let { cfDisplayName(it) } ?: XraySpeedtest.displayName(cfg)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
                // Uniform row height (1.79): every
                // row is at least 60dp, so a group
                // header (one line) is exactly as
                // tall as a config row (two lines).
                minimumHeight = (60 * resources.displayMetrics.density).toInt()
            }
            if (isGroup) {
                // Group header: selectable now (tapping
                // it selects the group, and connecting
                // with a group selected balances across
                // its members — see startVpn).
                val isSel = cfg == selected
                row.addView(TextView(this).apply {
                    text = "▾  $name"; textSize = 16f
                    setTextColor(Color.parseColor("#0B6E4F"))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(glassCircleButton("✕") {
                    confirmDelete(name) {
                        servers.remove(cfg)
                        if (selected == cfg) selected = null
                        saveServers(); refreshList()
                    }
                })
                row.setOnClickListener {
                    selected = cfg; saveServers(); refreshList()
                }
                row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val card = serverCard(isSel)
                card.addView(row)
                card.addView(protocolEdgeLabel("GROUP"))
                col.addView(card)
                cards += col
                continue
            }
            val isSel = cfg == selected
            row.addView(TextView(this).apply {
                text = if (isSel) "●" else "○"; textSize = 20f
                setTextColor(if (isSel) Color.parseColor("#0B6E4F") else Color.GRAY)
                setPadding(0, 0, 12, 0)
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = name + unsupportedSuffix(cfg); textSize = 16f
                // WHITE on the #F2F5F9 card was
                // unreadable (1.79 fix): dark text.
                // Single line + ellipsis keeps every
                // row the same height: a long name
                // used to wrap and make its row taller.
                setTextColor(Color.parseColor("#1A1A1A"))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "${srv?.host ?: "?"}:${srv?.port ?: "?"}"
                textSize = 13f; setTextColor(Color.GRAY)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(info)
            val ping = pingMap[XraySpeedtest.dedupKey(cfg)]
            row.addView(TextView(this).apply {
                text = if (ping == null) "—" else "$ping ms"
                textSize = 14f
                setTextColor(if (ping != null && ping >= 0) Color.parseColor("#0B6E4F") else Color.GRAY)
                setPadding(12, 0, 12, 0)
            })
            // Geo flag circle: for the connected config
            // the country is measured through the tunnel
            // (see GeoFlag); otherwise it is the host's
            // country. While the lookup runs it shows 🌐.
            val host = srv?.host ?: ""
            if (cfCountry != null) {
                // Cloudflare import: an ImageView alternating
                // every 2s between the country flag and the
                // Cloudflare section icon (see cfFlagToggle).
                val flagEmoji = cfFlagEmoji(cfCountry)
                val flagBmp = flagBitmap(flagEmoji)
                val cloudBmp = cloudBitmap()
                val flagView = android.widget.ImageView(this).apply {
                    val d = resources.displayMetrics.density
                    val size = (30 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
                    }
                    background = lightOval(
                        Color.parseColor("#E8FFFFFF"),
                        Color.parseColor("#55000000"), 1f
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                    setImageBitmap(if (cfFlagShowCloud) cloudBmp else flagBmp)
                    setOnClickListener {
                        showGeoMessage(
                            L(
                                "این کانفیگ کلودفلر برای کشور ${cfCountryName(cfCountry)} است",
                                "This Cloudflare config is for ${cfCountryName(cfCountry)}"
                            )
                        )
                    }
                }
                row.addView(flagView)
                cfFlagViews += Triple(flagView, flagBmp, cloudBmp)
            } else if (alienSet.contains(XraySpeedtest.dedupKey(cfg))) {
                // Alien import: a circle alternating
                // every 2s between the geo flag and
                // the alien section icon — the same
                // trick as Cloudflare's flag/cloud.
                val alienBmp = alienBitmap()
                val holder = AlienFlagState(alienBmp, flagBitmap("🌐"))
                val alienView = android.widget.ImageView(this).apply {
                    val d = resources.displayMetrics.density
                    val size = (30 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
                    }
                    background = lightOval(
                        Color.parseColor("#E8FFFFFF"),
                        Color.parseColor("#55000000"), 1f
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                    setImageBitmap(if (alienFlagShowAlien) alienBmp else holder.flagBmp)
                    setOnClickListener {
                        showGeoMessage(
                            if (holder.country.isNotBlank()) L(
                                "این کانفیگ از بخش زبان بیگانه آمده است؛ کشور ${holder.country} و شهر ${holder.city}",
                                "This config came from the Alien Language section; ${holder.country}, ${holder.city}"
                            ) else L(
                                "این کانفیگ از بخش زبان بیگانه آمده است",
                                "This config came from the Alien Language section"
                            )
                        )
                    }
                }
                holder.view = alienView
                row.addView(alienView)
                alienFlagViews += holder
                if (host.isNotBlank()) {
                    GeoFlag.lookupForConfig(this, cfg, host) { cc, country, city ->
                        holder.flagBmp = flagBitmap(GeoFlag.flagEmoji(cc))
                        holder.country = country
                        holder.city = city
                        if (!alienFlagShowAlien) alienView.setImageBitmap(holder.flagBmp)
                    }
                }
            } else {
            var geoCountry = ""
            var geoCity = ""
            val flagBtn = glassCircleButton("🌐") {
                if (geoCountry.isBlank() && geoCity.isBlank()) {
                    showGeoMessage(L("کشور این کانفیگ مشخص نشد", "This config's country is unknown"))
                } else {
                    val countryPart = if (geoCountry.isNotBlank()) geoCountry else L("نامشخص", "unknown")
                    val cityPart = if (geoCity.isNotBlank()) geoCity else L("نامشخص", "unknown")
                    showGeoMessage(L("این کانفیگ برای کشور $countryPart و شهر $cityPart است", "This config is for $countryPart, city $cityPart"))
                }
            }
            row.addView(flagBtn)
            if (host.isNotBlank()) {
                GeoFlag.lookupForConfig(this, cfg, host) { cc, country, city ->
                    geoCountry = country
                    geoCity = city
                    flagBtn.text = GeoFlag.flagEmoji(cc)
                }
            }
            }
            row.addView(glassCircleButton("✕") {
                confirmDelete(name) {
                    val wasSelected = cfg == selected
                    servers.remove(cfg)
                    if (wasSelected) selected = servers.firstOrNull()
                    saveServers(); refreshList(); refreshStatus()
                }
            })
            if (!XraySpeedtest.isChain(cfg)) {
                row.addView(glassCircleButton("↗") {
                    showQrFor(cfg)
                })
            }
            row.addView(glassCircleButton("✎") {
                editConfig(cfg)
            })
            row.setOnClickListener {
                selected = cfg; saveServers(); refreshList()
            }
            // Drag to reorder (v2rayNG-style): long-press a
            // row, drop it on another row. Only in the All
            // tab, where the visible list IS the saved list.
            row.setOnLongClickListener {
                if (selectedTab.isNotBlank()) {
                    android.widget.Toast.makeText(
                        this,
                        L("جابجایی فقط در «تمام کانفیگ‌ها» ممکن است.", "Reordering is only possible in All configs."),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    false
                } else {
                    dragFrom = vi
                    val data = android.content.ClipData.newPlainText("index", vi.toString())
                    row.startDragAndDrop(data, View.DragShadowBuilder(row), null, 0)
                    true
                }
            }
            row.setOnDragListener { _, ev ->
                when (ev.action) {
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                        row.setBackgroundColor(Color.parseColor("#E3F2FD"))
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_EXITED,
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        row.background = null
                        if (ev.action == android.view.DragEvent.ACTION_DRAG_ENDED) dragFrom = -1
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        val from = dragFrom
                        dragFrom = -1
                        row.background = null
                        if (from >= 0 && from != vi && selectedTab.isBlank()) {
                            val list = servers.toMutableList()
                            val item = list.removeAt(from)
                            // Drop position: upper half ->
                            // before this row, lower half ->
                            // after it.
                            var to = if (ev.y < row.height / 2f) vi else vi + 1
                            if (from < to) to -= 1
                            if (to < 0) to = 0
                            if (to > list.size) to = list.size
                            list.add(to, item)
                            servers.clear()
                            servers.addAll(list)
                            saveServers()
                        }
                        refreshList()
                        true
                    }
                    else -> true
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val card = serverCard(isSel)
            if (cfCountry != null) {
                // Soft galaxy tint, so Cloudflare imports stand
                // apart from ordinary configs at a glance.
                card.background = lightRect(
                    Color.parseColor("#ECE9FF"),
                    Color.parseColor("#7B7FE0"), 1f, 20f
                )
            } else if (alienSet.contains(XraySpeedtest.dedupKey(cfg))) {
                // Alien import: deep galaxy card with
                // light text, apart from both ordinary
                // and Cloudflare rows.
                card.background = galaxyCardBg()
                recolorRowForGalaxy(row)
            }
            card.addView(row)
            card.addView(
                protocolEdgeLabel(
                    cfCountry?.let { L("کلودفلر", "Cloudflare") }
                        ?: XraySpeedtest.fullType(cfg)
                )
            )
            col.addView(card)
            if (isSel) selectedRowView = col
            cards += col
        }
        if (doubleCol && cards.size > 1) {
            // Two cards per row: each card takes half
            // the width, with a small gap between them.
            val d = resources.displayMetrics.density
            var i = 0
            while (i < cards.size) {
                val pairRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val first = cards[i]
                first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 0, (4 * d).toInt(), 0) }
                pairRow.addView(first)
                if (i + 1 < cards.size) {
                    val second = cards[i + 1]
                    second.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins((4 * d).toInt(), 0, 0, 0) }
                    pairRow.addView(second)
                }
                listContainer.addView(pairRow)
                i += 2
            }
        } else {
            for (c in cards) listContainer.addView(c)
        }
    }

    // True once the strip has announced the
    // current connection. The poller must NEVER
    // rewrite the strip every 2s (that overwrite
    // is what used to wipe every message before
    // the user could read it, which is why the
    // auto toggles looked fake) — but it MAY
    // announce the connect TRANSITION once: after
    // v1.58 the strip stayed on "Connecting..."
    // forever, because nothing ever wrote the
    // success. One transition line, then silence.
    private var stripAnnouncedConnected = false
    // 2.13: last service error already shown in the strip.
    private var lastShownServiceError: String? = null

    private fun refreshStatus() {
        refreshFab()
        val connectedNow = (VpnClientService.isRunning &&
            VpnClientService.sessionTag in setOf("v2ray", "smart", null)) ||
            ProxyOnlyService.isRunning
        if (connectedNow && !stripAnnouncedConnected) {
            stripAnnouncedConnected = true
            appLogConnected()
            val nm = VpnClientService.currentName
            val ping = selected?.let { pingMap[XraySpeedtest.dedupKey(it)] }
            val pingPart = if (ping != null && ping >= 0) "؛ پینگ $ping ms" else ""
            val pingPartEn = if (ping != null && ping >= 0) "; ping $ping ms" else ""
            if (ProxyOnlyService.isRunning && !VpnClientService.isRunning) {
                addStatusLine("با موفقیت متصل شد (حالت فقط پروکسی)$pingPart",
                    "Connected successfully (proxy-only mode)$pingPartEn")
            } else if (nm != null) {
                addStatusLine("با موفقیت به «$nm» متصل شد$pingPart",
                    "Connected successfully to “$nm”$pingPartEn")
            } else {
                addStatusLine("با موفقیت متصل شد$pingPart",
                    "Connected successfully$pingPartEn")
            }
        } else if (!connectedNow) {
            stripAnnouncedConnected = false
            // 2.13: a failed connect used to die silently (the
            // strip stayed on Connecting...). Show the service
            // stored reason once, instead of silence.
            val err = VpnClientService.lastError
            if (err != null && err != lastShownServiceError) {
                lastShownServiceError = err
                addStatusLine("خطا در اتصال: $err", "Connect error: $err")
                if (appLogPending) { appLogEvent("ناموفق بود: $err", "failed: $err"); appLogPending = false }
            }
        }
    }

    /** ADAPTIVE ENGINE (1.82): stop a session the fallback check
     *  proved dead. Same stops as disconnectVpn, but it must NOT
     *  write smart_manual_stop_until — that suppression belongs
     *  ONLY to a manual disconnect (Smart regression guard). */
    private fun stopFallbackSession() {
        fallbackCheckToken = 0L
        try { ProxyOnlyService.stop(this) } catch (_: Exception) {}
        val stopIntent = Intent(this, VpnClientService::class.java).apply {
            action = "STOP"
            putExtra("stop_gen", VpnClientService.sessionGen)
        }
        try { startService(stopIntent) } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        VpnClientService.sessionTag = null
        try { getSharedPreferences("renamer_hydra", MODE_PRIVATE).edit().putBoolean("hydra_active", false).apply() } catch (_: Exception) {}
        VpnClientService.emergencyConfig = null
        refreshStatus()
    }

    private fun disconnectVpn() {
        val stopName = VpnClientService.currentName
        val stopTag = VpnClientService.sessionTag
        fallbackCheckToken = 0L
        try { ProxyOnlyService.stop(this) } catch (_: Exception) {}
        val stopIntent = Intent(this, VpnClientService::class.java).apply {
            action = "STOP"
            putExtra("stop_gen", VpnClientService.sessionGen)
        }
        try { startService(stopIntent) } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        VpnClientService.sessionTag = null
        try { getSharedPreferences("renamer_hydra", MODE_PRIVATE).edit().putBoolean("hydra_active", false).apply() } catch (_: Exception) {}
        // Manual disconnect must stick: smart connect must not
        // immediately reconnect while the selected app is still foreground.
        try {
            getSharedPreferences("renamer_smart", MODE_PRIVATE).edit()
                .putLong("smart_manual_stop_until", System.currentTimeMillis() + 5 * 60 * 1000).apply()
        } catch (_: Exception) {}
        VpnClientService.emergencyConfig = null
        refreshStatus()
        addStatusLine("اتصال قطع شد.", "Disconnected.")
        if (stopName != null && (stopTag == null || stopTag == "v2ray" || stopTag == "smart")) {
            AppLog.append(this, stopTag ?: "v2ray",
                "«$stopName»؛ دستی قطع شد.", "“$stopName”; stopped manually.")
        }
        appLogPending = false
    }

    /** ADAPTIVE ENGINE (1.78), first priority: when
     *  the engine is on, the ladder runs on a
     *  background thread and the winner (or the
     *  original, on any no-win) goes through the
     *  exact old connect path below. When off, this
     *  is a straight pass-through. */
    private fun connectSelected(cfg: String) {
        if (!AdaptiveEngine.isEnabled(this)) {
            appLogBegin(cfg, false, null)
            connectSelectedDirect(cfg)
            return
        }
        // 2.39: never run two engine checks at once.
        if (engineRunActive) {
            statusText.text = L("موتور هوشمند در حال بررسی است؛ کمی صبر کن.", "Smart engine is still checking; please wait.")
            return
        }
        engineRunActive = true
        statusText.text = L("موتور هوشمند در حال بررسی کانفیگ...", "Smart engine checking config...")
        Thread {
            // the jump/chain pool also takes persisted healthy
            // delays (subscription tests), not just this page's memory.
            val memPool = servers.filter { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
            val persistedPool = AdaptiveEngine.persistedPool(this, servers)
            if (persistedPool.any { it !in memPool }) {
                            }
            val pool = (memPool + persistedPool).distinct()
            // 2.38: 90s — on a filtered line the
            // failing probes of waves 1-2 alone ate
            // the old 45s budget, so waves 4-5 never
            // ran (and skipped silently).
            val plan = AdaptiveEngine.selectV2ray(this, cfg, AdaptiveEngine.S_V2RAY, 90000, pool)
            runOnUiThread {
                engineRunActive = false
                if (plan != null) {
                    enginePlan = plan
                    engineLink = plan.link
                }
                appLogBegin(cfg, true, plan)
                // 2.39: the traffic check runs for WON
                // sessions too — a probe-passed win
                // that carries no real traffic must
                // not stay "connected" (01:50 HK case).
                pendingFallbackCheck = true
                pendingWonCheck = (plan != null)
                pendingWonLink = plan?.link
                fabBusy = true; refreshFab()
                connectSelectedDirect(engineLink ?: cfg)
            }
        }.start()
    }

    private fun connectSelectedDirect(cfg: String) {
        // Proxy-only mode runs the core without a TUN,
        // so it needs no VPN permission prompt.
        if (prefs().getString("vpn_mode", "VPN") == "Proxy only") {
            startVpn(cfg)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            startVpn(cfg)
        }
    }

    // ---------- ADAPTIVE ENGINE (1.79): batch repair ----------

    private fun onBatchRepair() {
        if (!AdaptiveEngine.isEnabled(this)) return
        val healthyPool = servers.filter { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
        // Only PROVEN-dead configs (a ping test
        // already failed them), never untested ones,
        // groups or chains (customs included since 1.82).
        val dead = servers.filter { cfg ->
            (pingMap[XraySpeedtest.dedupKey(cfg)] ?: -1L) < 0 &&
            !XraySpeedtest.isGroup(cfg) && !XraySpeedtest.isChain(cfg)
        }
        if (dead.isEmpty()) {
            android.widget.Toast.makeText(this, L("کانفیگ خرابی برای تعمیر نیست.", "No dead configs to repair."), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Capped per run: each config gets up to 15s,
        // so an unbounded list cannot turn into a
        // 10-minute job.
        val targets = dead.take(12)
        val extra = dead.size - targets.size
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تعمیر دسته‌ای", "Batch repair"))
            .setMessage(L(
                "${targets.size} کانفیگ خراب با موتور بررسی می‌شود" +
                (if (extra > 0) " ($extra تای دیگر در این نوبت بررسی نمی‌شود)" else "") +
                ". نسخه‌های تعمیرشده فقط با تأیید تو، به‌عنوان کانفیگ جدید ذخیره می‌شوند؛ کانفیگ‌های اصلی دست نمی‌خورند.",
                "${targets.size} dead configs will be checked with the engine" +
                (if (extra > 0) " ($extra more not covered in this run)" else "") +
                ". Repaired copies are saved only with your confirmation, as NEW configs; the originals are never touched."))
            .setPositiveButton(L("شروع", "Start")) { _, _ -> runBatchRepair(targets, healthyPool) }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun runBatchRepair(targets: List<String>, healthyPool: List<String>) {
        val progressText = TextView(this).apply {
            text = L("در حال بررسی...", "Checking...")
            textSize = 14f
            setPadding(24, 16, 24, 16)
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(L("تعمیر دسته‌ای", "Batch repair"))
            .setView(progressText)
            .setNegativeButton(L("لغو", "Cancel")) { _, _ -> cancelled.set(true) }
            .create()
        dlg.show()
        Thread {
            val results = mutableListOf<Triple<String, AdaptiveEngine.Plan?, String>>()
            for ((idx, cfg) in targets.withIndex()) {
                if (cancelled.get()) break
                val n = idx + 1
                runOnUiThread {
                    progressText.text = L("در حال بررسی $n از ${targets.size}...", "Checking $n of ${targets.size}...")
                }
                val plan = try {
                    AdaptiveEngine.selectV2ray(this, cfg, AdaptiveEngine.S_V2RAY, 15000, healthyPool)
                } catch (_: Exception) { null }
                val label = if (plan == null) L("درست نشد", "not repaired")
                            else L(plan.noteFa, plan.noteEn)
                results += Triple(cfg, plan, label)
            }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                showBatchResults(results)
            }
        }.start()
    }

    private fun showBatchResults(results: List<Triple<String, AdaptiveEngine.Plan?, String>>) {
        if (results.isEmpty()) return
        val lines = results.joinToString("\n") { (cfg, plan, label) ->
            val name = XraySpeedtest.displayName(cfg)
            if (plan == null) "✕ $name: $label" else "✓ $name ← $label"
        }
        val tv = TextView(this).apply {
            text = lines
            textSize = 13f
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val wins = results.filter { it.second != null }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(L("نتیجه تعمیر دسته‌ای", "Batch repair results"))
            .setView(scroll)
        if (wins.isNotEmpty()) {
            dlg.setPositiveButton(L("ذخیره نسخه‌های تعمیرشده", "Save repaired copies")) { _, _ ->
                saveBatchResults(wins)
            }
        }
        dlg.setNegativeButton(L("بستن", "Close"), null)
        dlg.show()
    }

    private fun saveBatchResults(wins: List<Triple<String, AdaptiveEngine.Plan?, String>>) {
        var added = 0
        for ((cfg, plan, _) in wins) {
            if (plan == null) continue
            val saved: String? = try {
                if (!plan.fragment && plan.shape == null) {
                    // Plain link variant (IP/port/fp/
                    // SNI/transport/flow/jump):
                    // the link itself IS the repaired
                    // config. A saved jump chain gets
                    // the original's name, not the
                    // generic engine label.
                    var link = plan.link
                    if (XraySpeedtest.isCustom(link)) {
                        try {
                            val j = org.json.JSONObject(link)
                            j.put("remarks", XraySpeedtest.displayName(cfg) + " (تعمیرشده)")
                            link = j.toString()
                        } catch (_: Exception) {}
                    }
                    if (link.startsWith("CHAIN::")) {
                        val rest = link.removePrefix("CHAIN::")
                        val parts = rest.substringAfter("|||", "")
                        val origName = XraySpeedtest.displayName(cfg)
                        link = "CHAIN::$origName (پرش)|||$parts"
                    }
                    link
                } else {
                    // Fragment/shape win: the repair
                    // lives in the BUILT json, so the
                    // repaired copy is saved as a
                    // custom config (its inbounds are
                    // replaced again at connect time).
                    val built = buildVpnConfig(cfg) ?: continue
                    val withPlan = AdaptiveEngine.applyPlanToBuiltJson(this, built, plan?.copy(standbys = emptyList()), true) ?: continue
                    val j = org.json.JSONObject(withPlan)
                    j.put("remarks", XraySpeedtest.displayName(cfg) + " (تعمیرشده)")
                    j.toString()
                }
            } catch (_: Exception) { null }
            if (saved != null && !servers.contains(saved)) {
                servers.add(saved)
                added++
            }
        }
        if (added > 0) {
            saveServers()
            refreshList()
        }
        android.widget.Toast.makeText(this,
            L("$added نسخه تعمیرشده ذخیره شد.", "$added repaired copies saved."),
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleConnect() {
        // Another section's session is up: NEVER
        // stop it from here, and never pretend this
        // button is connected. Say which section is
        // running and let the user stop it there.
        // (Manual control DOES override a smart
        // session: smart is automation over v2ray
        // configs, not a section of its own.)
        if (VpnClientService.isRunning) {
            when (VpnClientService.sessionTag) {
                "emergency", "hydra", "chain" -> {
                    statusText.text = L("اتصال «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.", "A «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» connection is active; stop it first.")
                    return
                }
            }
            disconnectVpn()
            return
        }
        if (ProxyOnlyService.isRunning) {
            disconnectVpn()
            return
        }
        if (connectMode() == "best") {
            fabBusy = true; refreshFab()
            handler.postDelayed({ if (fabBusy) { fabBusy = false; refreshFab() } }, 15000)
            connectBestPing()
            return
        }
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک کانفیگ را انتخاب کن.", "Select a config first.")
            return
        }
        fabBusy = true; refreshFab()
        handler.postDelayed({ if (fabBusy) { fabBusy = false; refreshFab() } }, 15000)
        connectSelected(cfg)
    }

    private fun fabIdle() {
        fabBusy = false
        refreshFab()
    }

    /** Best-ping mode: ping all servers first, then
     *  connect to the lowest-ping healthy one. */
    /** Best-ping mode: ping all servers first, then
     *  connect to the lowest-ping healthy one.
     *  Fixed after the "smart does not work" report:
     *  every result now shows live (before, the page
     *  sat on "testing..." with zero feedback until
     *  everything finished, so a slow test looked
     *  dead); each measurement has a 30s timeout so
     *  one hung config cannot stall the whole run;
     *  chains and customs are excluded (they cannot
     *  be delay-tested as single outbounds, they only
     *  wasted pool slots). */
    /** Best-ping mode ("smart"): tap the FAB and the
     *  app ping-tests and connects to the lowest-ping
     *  healthy config — fast. Two earlier defects are
     *  fixed here. (1) Results used to be awaited in
     *  LIST order with a 30s timeout each, so one hung
     *  config stalled everything behind it while the
     *  page showed nothing (the 2s poller also wiped
     *  the "testing..." line). Now results are taken
     *  in COMPLETION order under a single 60s budget,
     *  and every finished result updates the strip
     *  live. (2) If the configs were already pinged
     *  this session, those results are reused at once
     *  and only untested ones are measured — the
     *  usual test-then-connect tap is instant.
     *  Cached all--1 (a previous fully-failed run)
     *  never counts as an answer: everything is
     *  re-tested, because that usually meant the test
     *  itself failed, not the configs. */
    private fun connectBestPing() {
        // Customs ARE testable: pingAll measures them
        // (their ms values show on the cards), so
        // excluding them here — as v1.57 wrongly did
        // with the comment "not delay-testable" — made
        // best-ping say "no config to test" for a
        // list of only customs. Only chains stay out:
        // a chain is multi-hop, not a single
        // outbound to delay-test.
        val targets = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (targets.isEmpty()) {
            statusText.text = L("کانفیگی برای تست نیست.", "No config to test.")
            fabIdle()
            return
        }
        val cachedHealthy = targets.filter { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
        val untested = targets.filter { !pingMap.containsKey(XraySpeedtest.dedupKey(it)) }
        // Instant path: everything already tested this
        // session AND at least one healthy result.
        if (untested.isEmpty() && cachedHealthy.isNotEmpty()) {
            val best = cachedHealthy.minByOrNull { pingMap[XraySpeedtest.dedupKey(it)] ?: Long.MAX_VALUE }!!
            val bestDelay = pingMap[XraySpeedtest.dedupKey(best)] ?: -1L
            selected = best
            saveServers(); refreshList()
            statusText.text = L("بهترین کانفیگ: ${XraySpeedtest.displayName(best)} ($bestDelay ms)، در حال اتصال...", "Best config: ${XraySpeedtest.displayName(best)} ($bestDelay ms), connecting...")
            addStatusLine("بهترین کانفیگ: ${XraySpeedtest.displayName(best)} ($bestDelay ms)", "Best config: ${XraySpeedtest.displayName(best)} ($bestDelay ms)")
            connectSelected(best)
            return
        }
        if (!initCore()) {
            statusText.text = L("تست پینگ شروع نشد: هسته Xray راه‌اندازی نشد.", "Ping test did not start: Xray core failed to start.")
            fabIdle()
            return
        }
        statusText.text = L("در حال تست پینگ برای بهترین کانفیگ...", "Testing ping for the best config...")
        Thread {
            val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            // untested ones are measured when a healthy
            // cache exists, AND configs that scored -1 are
            // re-tested too — so auto-delete below never
            // deletes a config that was not just re-tested.
            // With no healthy cache at all, everything is
            // re-tested (old -1s included) so a recovered
            // network is not missed.
            val failed = targets.filter { pingMap[XraySpeedtest.dedupKey(it)] == -1L }
            val toTest = if (cachedHealthy.isEmpty()) targets else (untested + failed).distinct()
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, Long>>(pool)
            try {
                for (cfg in toTest) {
                    completion.submit(java.util.concurrent.Callable {
                        val d = try { XraySpeedtest.measureOneWithError(cfg).first }
                            catch (_: Throwable) { -1L }
                        cfg to d
                    })
                }
                var done = targets.size - toTest.size
                val total = targets.size
                val deadline = System.currentTimeMillis() + 60_000
                var remainingTasks = toTest.size
                while (remainingTasks > 0) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val future = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    remainingTasks--
                    val result = try { future.get() } catch (_: Exception) { null }
                    if (result != null) {
                        val (cfg, d) = result
                        synchronized(pingMap) { pingMap[XraySpeedtest.dedupKey(cfg)] = d }
                        done++
                        runOnUiThread {
                            refreshList()
                            setStatusProgress("از $total کانفیگ $done تست گرفته شد", "$done of $total configs tested")
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            runOnUiThread {
                refreshList()
                var bestCfg: String? = null
                var bestDelay = Long.MAX_VALUE
                for (cfg in targets) {
                    val d = pingMap[XraySpeedtest.dedupKey(cfg)] ?: -1L
                    if (d >= 0 && d < bestDelay) { bestDelay = d; bestCfg = cfg }
                }
                val chosen = bestCfg
                if (chosen == null) {
                    statusText.text = L("کانفیگ سالمی پیدا نشد.", "No healthy config found.")
                    setStatusProgress("کانفیگ سالمی پیدا نشد", "No healthy config found")
                    fabIdle()
                } else {
                    selected = chosen
                    saveServers(); refreshList()
                    statusText.text = L("بهترین کانفیگ پیدا شد، در حال اتصال...", "Best config found, connecting...")
                    addStatusLine("بهترین کانفیگ: ${XraySpeedtest.displayName(chosen)} ($bestDelay ms)", "Best config: ${XraySpeedtest.displayName(chosen)} ($bestDelay ms)")
                    if (failed.isNotEmpty()) {
                                            }
                    val removed = deleteNoPingIfEnabled(targets)
                    if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
                    connectSelected(chosen)
                }
            }
        }.start()
    }

    /** The + menu, exactly like v2rayNG's add menu,
     *  now as separate pale-blue glass buttons. */
    /** A small v2rayNG-style overflow popup: matte-white,
     *  anchored under the tapped button, one line per
     *  option, dismissed by tapping anywhere else. Text
     *  colors stay the app's dark ones (readable on the
     *  light popup); the main page is untouched. */
    private fun showSmallPopupMenu(anchor: View, items: Array<String>, onSelect: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }
        val popup = android.widget.PopupWindow(
            col,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f * d
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        for ((i, label) in items.withIndex()) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 15f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#333333"))
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    (300 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { popup.dismiss(); onSelect(i) }
            })
            if (i < items.size - 1) {
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                })
            }
        }
        try {
            popup.showAsDropDown(anchor)
        } catch (_: Exception) {
        }
    }

    private fun showAddMenu() {
        val items = arrayOf(
            L("وارد کردن از QRcode", "Import from QRcode"),
            L("وارد کردن از کلیپ‌بورد", "Import from Clipboard"),
            L("وارد کردن از فایل محلی", "Import from locally"),
            L("افزودن [گروه سیاستی]", "Add [Policy group]"),
            L("افزودن [زنجیره پروکسی]", "Add [Proxy chain]"),
            L("افزودن [VMess]", "Add [VMess]"),
            L("افزودن [VLESS]", "Add [VLESS]"),
            L("افزودن [Shadowsocks]", "Add [Shadowsocks]"),
            L("افزودن [SOCKS]", "Add [SOCKS]"),
            L("افزودن [HTTP]", "Add [HTTP]")
        )
        showSmallPopupMenu(addBtn, items) { which ->
            when (which) {
                0 -> startActivityForResult(
                    Intent(this@VpnActivity, ScanActivity::class.java), 200)
                1 -> importFromClipboard()
                2 -> {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, L("انتخاب فایل کانفیگ", "Choose a config file")), 300)
                }
                3 -> addPolicyGroup()
                4 -> addProxyChain()
                5 -> addVMessForm()
                6 -> addVLESSForm()
                7 -> addShadowsocksForm()
                8 -> addSocksForm()
                9 -> addHttpForm()
            }
        }
    }

    private fun importFromClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isBlank()) {
                statusText.text = L("کلیپ‌بورد خالی است.", "Clipboard is empty.")
                return
            }
            addFromText(clip, toTop = true)
        } catch (e: Exception) {
            statusText.text = L("خواندن کلیپ‌بورد ناموفق: ${e.message}", "Failed to read clipboard: ${e.message}")
        }
    }

    // A policy group is now connectable: connecting
    // with a group selected balances across its
    // members (Observatory strategy below). The
    // strategy is stored on the header itself, so
    // old groups without a suffix default to leastPing.
    private fun addPolicyGroup() {
        showForm("Add [Policy group]", listOf(L("نام گروه", "Group name") to "")) { v ->
            val name = v[L("نام گروه", "Group name")] ?: ""
            if (name.isBlank()) {
                statusText.text = L("نام گروه را بنویس.", "Write the group name.")
                return@showForm
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(L("استراتژی گروه", "Group strategy"))
                .setItems(arrayOf(
                    L("کمترین پینگ (leastPing)", "Least ping (leastPing)"),
                    L("کمترین بار (leastLoad)", "Least load (leastLoad)")
                )) { _, which ->
                    val strategy = if (which == 1) "leastLoad" else "leastPing"
                    val entry = "GROUP::$name|||$strategy"
                    if (!servers.contains(entry)) servers += entry
                    saveServers(); refreshList()
                    statusText.text = L("گروه «$name» اضافه شد؛ با انتخاب آن و اتصال، ترافیک بین اعضای گروه پخش می‌شود.", "Group “$name” added; select it and connect to balance traffic across its members.")
                }
                .setNegativeButton(L("لغو", "Cancel"), null)
                .show()
        }
    }

    private fun addProxyChain() {
        val real = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (real.size < 2) {
            statusText.text = L("برای زنجیره حداقل ۲ سرور لازم است.", "At least 2 servers are needed for a chain.")
            return
        }
        val names = real.map { XraySpeedtest.displayName(it) }.toTypedArray()
        val checked = BooleanArray(real.size)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("سرورهای زنجیره را به ترتیب انتخاب کن", "Select the chain servers in order"))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(L("ساخت زنجیره", "Build chain")) { _, _ ->
                val chosen = real.filterIndexed { i, _ -> checked[i] }
                if (chosen.size < 2) {
                    statusText.text = L("برای زنجیره حداقل ۲ سرور انتخاب کن.", "Select at least 2 servers for a chain.")
                    return@setPositiveButton
                }
                showForm(L("نام زنجیره", "Chain name"), listOf(L("نام زنجیره", "Chain name") to "")) { v ->
                    val name = v[L("نام زنجیره", "Chain name")]?.takeIf { it.isNotBlank() } ?: L("زنجیره", "Chain")
                    val entry = "CHAIN::$name|||" + chosen.joinToString("|||")
                    servers += entry
                    saveServers(); refreshList()
                    statusText.text = L("زنجیره «$name» با ${chosen.size} سرور ساخته شد.", "Chain “$name” with ${chosen.size} servers built.")
                }
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    /** Generic manual-add form: a vertical list of labeled
     *  EditTexts in a dialog, values returned by label. */
    private fun showForm(title: String, fields: List<Pair<String, String>>,
                         onSubmit: (Map<String, String>) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        val inputs = mutableMapOf<String, EditText>()
        for ((label, def) in fields) {
            col.addView(TextView(this).apply { text = label; textSize = 14f })
            val et = EditText(this).apply {
                hint = label
                setText(def)
            }
            inputs[label] = et
            col.addView(et)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(L("افزودن", "Add")) { _, _ ->
                val values = inputs.mapValues { it.value.text.toString().trim() }
                onSubmit(values)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun addOne(cfg: String) {
        if (!cfg.contains("://")) {
            statusText.text = L("کانفیگ ساخته‌شده معتبر نیست.", "The built config is not valid.")
            return
        }
        if (XraySpeedtest.outboundFor(cfg) == null && !XraySpeedtest.isChain(cfg)) {
            statusText.text = L("این کانفیگ پشتیبانی نمی‌شود.", "This config is not supported.")
            return
        }
        if (!servers.contains(cfg)) servers += cfg
        if (selected == null) selected = servers.firstOrNull()
        saveServers(); refreshList()
        statusText.text = L("کانفیگ «${XraySpeedtest.displayName(cfg)}» اضافه شد.", "Config \"${XraySpeedtest.displayName(cfg)}\" added.")
    }

    private fun encName(name: String): String {
        return try {
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        } catch (_: Exception) { name }
    }

    private fun addVMessForm() {
        showForm("Add [VMess]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "443",
            "UUID" to "", "alterId" to "0",
            L("شبکه (tcp/ws/grpc)", "Network (tcp/ws/grpc)") to "tcp", L("مسیر (برای ws)", "Path (for ws)") to "/",
            L("tls (tls یا خالی)", "tls (tls or empty)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "443"
            val id = v["UUID"] ?: ""
            if (addr.isBlank() || id.isBlank()) {
                statusText.text = L("آدرس و UUID لازم است.", "Address and UUID are required.")
                return@showForm
            }
            val j = JSONObject().apply {
                put("v", "2"); put("ps", name); put("add", addr); put("port", port)
                put("id", id); put("aid", v["alterId"] ?: "0")
                put("net", v[L("شبکه (tcp/ws/grpc)", "Network (tcp/ws/grpc)")] ?: "tcp")
                put("type", "none"); put("host", "")
                put("path", v[L("مسیر (برای ws)", "Path (for ws)")] ?: "/")
                put("tls", v[L("tls (tls یا خالی)", "tls (tls or empty)")] ?: "")
                put("sni", "")
            }
            val b64 = android.util.Base64.encodeToString(
                j.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ).trimEnd('=')
            addOne("vmess://$b64#${encName(name)}")
        }
    }

    private fun addVLESSForm() {
        showForm("Add [VLESS]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "443",
            "UUID" to "", L("شبکه (tcp/ws/grpc/xhttp)", "Network (tcp/ws/grpc/xhttp)") to "tcp",
            L("امنیت (tls/reality/none)", "Security (tls/reality/none)") to "tls", "SNI" to "",
            "fingerprint" to "chrome", "publicKey (reality)" to "",
            "shortId (reality)" to "", "flow" to "",
            L("مسیر (ws/xhttp)", "Path (ws/xhttp)") to "/", "host (ws)" to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "443"
            val id = v["UUID"] ?: ""
            if (addr.isBlank() || id.isBlank()) {
                statusText.text = L("آدرس و UUID لازم است.", "Address and UUID are required.")
                return@showForm
            }
            val q = mutableListOf<String>()
            fun putQ(k: String, value: String?) {
                if (!value.isNullOrBlank()) q += "$k=${encName(value)}"
            }
            putQ("type", v[L("شبکه (tcp/ws/grpc/xhttp)", "Network (tcp/ws/grpc/xhttp)")])
            putQ("security", v[L("امنیت (tls/reality/none)", "Security (tls/reality/none)")])
            putQ("sni", v["SNI"])
            putQ("fp", v["fingerprint"])
            putQ("pbk", v["publicKey (reality)"])
            putQ("sid", v["shortId (reality)"])
            putQ("flow", v["flow"])
            putQ("path", v[L("مسیر (ws/xhttp)", "Path (ws/xhttp)")])
            putQ("host", v["host (ws)"])
            val query = if (q.isEmpty()) "" else "?" + q.joinToString("&")
            addOne("vless://$id@$addr:$port$query#${encName(name)}")
        }
    }

    private fun addShadowsocksForm() {
        showForm("Add [Shadowsocks]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "8388",
            L("روش (aes-256-gcm و...)", "Method (aes-256-gcm etc.)") to "aes-256-gcm", L("رمز", "Password") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "8388"
            val method = v[L("روش (aes-256-gcm و...)", "Method (aes-256-gcm etc.)")] ?: "aes-256-gcm"
            val pass = v[L("رمز", "Password")] ?: ""
            if (addr.isBlank() || pass.isBlank()) {
                statusText.text = L("آدرس و رمز لازم است.", "Address and password are required.")
                return@showForm
            }
            val raw = "$method:$pass@$addr:$port"
            val b64 = android.util.Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ).trimEnd('=')
            addOne("ss://$b64#${encName(name)}")
        }
    }

    private fun addSocksForm() {
        showForm("Add [SOCKS]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "1080",
            L("نام کاربری (اختیاری)", "Username (optional)") to "", L("رمز (اختیاری)", "Password (optional)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "1080"
            val user = v[L("نام کاربری (اختیاری)", "Username (optional)")] ?: ""
            val pass = v[L("رمز (اختیاری)", "Password (optional)")] ?: ""
            if (addr.isBlank()) {
                statusText.text = L("آدرس لازم است.", "Address is required.")
                return@showForm
            }
            val auth = if (user.isNotBlank()) "$user:$pass@" else ""
            addOne("socks://$auth$addr:$port#${encName(name)}")
        }
    }

    private fun addHttpForm() {
        showForm("Add [HTTP]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "8080",
            L("نام کاربری (اختیاری)", "Username (optional)") to "", L("رمز (اختیاری)", "Password (optional)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "8080"
            val user = v[L("نام کاربری (اختیاری)", "Username (optional)")] ?: ""
            val pass = v[L("رمز (اختیاری)", "Password (optional)")] ?: ""
            if (addr.isBlank()) {
                statusText.text = L("آدرس لازم است.", "Address is required.")
                return@showForm
            }
            val auth = if (user.isNotBlank()) "$user:$pass@" else ""
            addOne("http://$auth$addr:$port#${encName(name)}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val link = engineLink ?: selected
            engineLink = null
            link?.let { startVpn(it) }
        } else if (requestCode == 100) {
            statusText.text = L("دسترسی VPN تأیید نشد.", "VPN permission was not confirmed.")
        } else if (requestCode == 400 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                val json = pendingBackupJson ?: ""
                if (uri != null && json.isNotBlank()) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    statusText.text = L("بکاپ ذخیره شد.", "Backup saved.")
                }
            } catch (e: Exception) {
                statusText.text = L("ذخیره بکاپ ناموفق: ${e.message}", "Failed to save backup: ${e.message}")
            }
        } else if (requestCode == 401 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                val text = if (uri != null) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                } else ""
                val j = JSONObject(text)
                val arr = j.optJSONArray("servers")
                if (arr != null) {
                    servers = mutableListOf()
                    for (i in 0 until arr.length()) servers += arr.getString(i)
                }
                selected = j.optString("selected").takeIf { it.isNotBlank() }
                prefs().edit()
                    .putString("vpn_subs", j.optString("vpn_subs"))
                    .putString("per_app_mode", j.optString("per_app_mode"))
                    .putString("per_app_set", j.optString("per_app_set"))
                    .putString("routing_mode", j.optString("routing_mode"))
                    .apply()
                saveServers(); refreshList()
                statusText.text = L("بکاپ بازیابی شد: ${servers.size} کانفیگ.", "Backup restored: ${servers.size} configs.")
            } catch (e: Exception) {
                statusText.text = L("خواندن بکاپ ناموفق: ${e.message}", "Failed to read backup: ${e.message}")
            }
        } else if (requestCode == 300 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                if (uri == null) {
                    statusText.text = L("فایلی انتخاب نشد.", "No file selected.")
                    return
                }
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: ""
                if (text.isBlank()) {
                    statusText.text = L("فایل خالی بود.", "The file was empty.")
                } else {
                    addFromText(text)
                }
            } catch (e: Exception) {
                statusText.text = L("خواندن فایل ناموفق: ${e.message}", "Failed to read file: ${e.message}")
            }
        } else if (requestCode == 200 && resultCode == RESULT_OK) {
            val scanned = data?.getStringExtra("scanned") ?: ""
            if (scanned.contains("://")) {
                addFromText(scanned)
                statusText.text = L("کانفیگ از QR اضافه شد.", "Config added from QR.")
            } else {
                statusText.text = L("QR خوانده‌شده کانفیگ نبود.", "The scanned QR was not a config.")
            }
        }
    }

    private fun loadVpnSubs(): MutableList<String> {
        return prefs().getString("vpn_subs", "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    }

    // Update all enabled subscriptions, honouring
    // each sub's own options (see VpnSubs.fetchOne).
    private fun updateVpnSubs() {
        val items = VpnSubs.loadItems(this).filter { it.enabled }
        if (items.isEmpty()) {
            statusText.text = L("اول یک سابسکریبشن فعال اضافه کن.", "Add an enabled subscription first.")
            return
        }
        statusText.text = L("در حال به‌روزرسانی ساب‌ها...", "Updating subscriptions...")
        Thread {
            var added = 0
            var received = 0
            var failed = 0
            // One line per sub, so a failing sub
            // names its own cause instead of hiding
            // behind one aggregate "failed" count.
            val lines = mutableListOf<Pair<String, String>>()
            for (item in items) {
                try {
                    val r = VpnSubs.fetchOne(this, item)
                    added += r.added
                    received += r.received
                    val label = VpnSubs.displayLabel(item)
                    if (r.message == "ok") {
                        // Raw FA/EN pair: addStatusLine
                        // picks by language itself, so
                        // do NOT wrap these in L().
                        lines += ("ساب $label: ${r.received} دریافت، ${r.added} جدید"
                            to "Sub $label: ${r.received} received, ${r.added} new")
                    } else {
                        failed++
                        val (whyFa, whyEn) = VpnSubs.reasonFaEn(r.message)
                        lines += ("ساب $label ناموفق: $whyFa"
                            to "Sub $label failed: $whyEn")
                    }
                } catch (_: Exception) {
                    failed++
                    val label = VpnSubs.displayLabel(item)
                    lines += ("ساب $label ناموفق: خطای نامشخص"
                        to "Sub $label failed: unknown error")
                }
            }
            runOnUiThread {
                servers = VpnSubs.loadServers(this)
                if (selected == null) selected = servers.firstOrNull()
                if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
                saveServers(); refreshList()
                for ((fa, en) in lines) addStatusLine(fa, en)
                statusText.text = L("به‌روزرسانی ساب‌ها انجام شد: $received کانفیگ دریافت شد، $added جدید اضافه شد.", "Subscription update done: $received configs received, $added new added.") +
                    if (failed > 0) L(" ($failed ساب ناموفق بود.)", " ($failed subs failed.)") else ""
                autoDedupIfEnabled()
            }
        }.start()
    }

    private fun showQrFor(cfg: String) {
        try {
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val matrix = writer.encode(cfg, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until 512) for (y in 0 until 512) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
            val iv = ImageView(this).apply { setImageBitmap(bmp) }
            android.app.AlertDialog.Builder(this)
                .setTitle(XraySpeedtest.displayName(cfg))
                .setView(iv)
                .setPositiveButton(L("بستن", "Close"), null)
                .setNeutralButton(L("کپی لینک", "Copy link")) { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("server", cfg))
                        statusText.text = L("لینک سرور کپی شد.", "Server link copied.")
                    } catch (e: Exception) {
                        statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                    }
                }
                .show()
        } catch (e: Exception) {
            statusText.text = L("ساخت QR ناموفق: ${e.message}", "Failed to build QR: ${e.message}")
        }
    }

    // Pre-resolve runs DNS off the UI thread, then
    // the real connect runs with the results: the
    // "add to DNS Hosts" mode passes them into the
    // config's dns.hosts, the "Resolve only" mode
    // rewrites outbound addresses to the IPs.
    private fun startVpn(cfg: String) {
        engineLink = null
        // A hand-started connect is not smart connect's.
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        if (VpnConfigBuilder.preResolveMode(this) == "Do not resolve") {
            doStartVpn(cfg, emptyMap())
            return
        }
        statusText.text = L("در حال حل دامنه...", "Resolving domain...")
        Thread {
            val resolved = try { VpnConfigBuilder.preResolveHosts(this, cfg) }
                catch (_: Exception) { emptyMap<String, String>() }
            runOnUiThread { doStartVpn(cfg, resolved) }
        }.start()
    }

    private fun doStartVpn(cfg: String, resolved: Map<String, String>) {
        val mode = VpnConfigBuilder.preResolveMode(this)
        val hostsArg = if (mode == "Resolve and add to DNS Hosts") resolved else emptyMap()
        // Proxy-only mode: the core runs with local
        // proxy inbounds only, no TUN, no VPN service.
        if (prefs().getString("vpn_mode", "VPN") == "Proxy only") {
            val builtProxy = VpnConfigBuilder.buildProxyOnly(this, cfg, hostsArg)
            // ADAPTIVE ENGINE (1.78): one-shot plan
            // (fragment injection) applied to the
            // built JSON; null plan = unchanged.
            val builtProxyE = AdaptiveEngine.applyPlanToBuiltJson(this, builtProxy, enginePlan, false)
            enginePlan = null
            val proxyCfg = if (mode == "Resolve only") {
                VpnConfigBuilder.applyResolvedIp(builtProxyE ?: "", resolved)
            } else builtProxyE
            if (proxyCfg == null) {
                if (appLogPending) { appLogEvent("ناموفق بود: کانفیگ پشتیبانی نمی‌شود", "failed: config not supported"); appLogPending = false }
                statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
                fabIdle()
                return
            }
            if (!initCore()) {
                if (appLogPending) { appLogEvent("ناموفق بود: هسته راه‌اندازی نشد", "failed: core failed to start"); appLogPending = false }
                statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
                fabIdle()
                return
            }
            ProxyOnlyService.start(this, proxyCfg, XraySpeedtest.displayName(cfg))
            statusText.text = L("پروکسی فعال شد (بدون TUN).", "Proxy active (no TUN).")
            handler.postDelayed({ refreshStatus() }, 1500)
            return
        }
        // A config that belongs to a sub with prev/next
        // set connects as that sub's chain (device ->
        // prev -> config -> next -> destination).
        val effective = VpnSubs.chainForConfig(this, cfg) ?: cfg
        val built = buildVpnConfig(effective, hostsArg)
        // ADAPTIVE ENGINE (1.78): one-shot plan
        // (fragment injection) applied to the built
        // JSON; null plan = unchanged.
        // 1.79: remember whether this connect came
        // from the engine, and the ORIGINAL config,
        // before the plan is consumed below — the
        // service needs both for its watchdog.
        val wasEngine = enginePlan != null
        val engineOrig = if (wasEngine) selected else null
        val builtE = AdaptiveEngine.applyPlanToBuiltJson(this, built, enginePlan, true)
        enginePlan = null
        val vpnConfig = if (mode == "Resolve only") {
            VpnConfigBuilder.applyResolvedIp(builtE ?: "", resolved)
        } else builtE
        if (vpnConfig == null) {
            if (appLogPending) { appLogEvent("ناموفق بود: کانفیگ پشتیبانی نمی‌شود", "failed: config not supported"); appLogPending = false }
            statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
            fabIdle()
            return
        }
        if (!initCore()) {
            if (appLogPending) { appLogEvent("ناموفق بود: هسته راه‌اندازی نشد", "failed: core failed to start"); appLogPending = false }
            statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
            fabIdle()
            return
        }
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            putExtra("name", XraySpeedtest.displayName(cfg))
            putExtra("link", effective)
            putExtra("session_tag", "v2ray")
            // ADAPTIVE ENGINE (1.79): mark the session
            // so the service watchdog watches it, and
            // remember the ORIGINAL link for healing.
            if (wasEngine) {
                putExtra("engine_session", true)
                if (engineOrig != null) putExtra("engine_orig_link", engineOrig)
            }
        }
        // A normal connect is not an emergency connect.
        VpnClientService.emergencyConfig = null
        startService(intent)
        statusText.text = L("در حال اتصال...", "Connecting...")
        handler.postDelayed({ refreshStatus() }, 1500)
        // ADAPTIVE ENGINE (1.82): no fake "connected". If the
        // engine found no win, the normal connect above runs
        // anyway; if it comes up but no traffic passes, stop
        // it instead of leaving a dead "connected" state.
        // (Proxy-only mode is excluded: no TUN session exists.)
        if (pendingFallbackCheck) {
            pendingFallbackCheck = false
            if (AdaptiveEngine.isEnabled(this) && prefs().getString("vpn_mode", "VPN") != "Proxy only") {
                val token = System.currentTimeMillis()
                fallbackCheckToken = token
                Thread {
                    try { Thread.sleep(12000) } catch (_: Exception) {}
                    if (VpnClientService.isRunning && VpnClientService.sessionTag == "v2ray" && fallbackCheckToken == token && !AdaptiveEngine.probeThroughTunnel(this)) {
                        try { Thread.sleep(8000) } catch (_: Exception) {}
                        if (VpnClientService.isRunning && VpnClientService.sessionTag == "v2ray" && fallbackCheckToken == token && !AdaptiveEngine.probeThroughTunnel(this)) {
                            runOnUiThread {
                                val wasWon = pendingWonCheck
                                val wonLink = pendingWonLink
                                pendingWonCheck = false
                                pendingWonLink = null
                                stopFallbackSession()
                                if (wasWon && wonLink != null) {
                                    try {
                                        AdaptiveEngine.unlearnWin(this, XraySpeedtest.dedupKey(wonLink))
                                    } catch (_: Exception) {}
                                    AdaptiveEngine.append(this, AdaptiveEngine.S_V2RAY,
                                        "موتور: روش برنده در عمل ترافیکی عبور نداد؛ اتصال قطع شد و این روش برای این کانفیگ فراموش شد.",
                                        "Engine: the winning variant carried no real traffic; stopped and that variant was forgotten for this config.")
                                } else {
                                    AdaptiveEngine.append(this, AdaptiveEngine.S_V2RAY,
                                        "موتور: اتصال عادی برقرار شد ولی ترافیکی عبور نکرد؛ اتصال قطع شد تا حالت «متصل» دروغین نماند.",
                                        "Engine: normal connect came up but no traffic passed; stopped so no fake connected state remains.")
                                }
                                val deadName = if (appLogName.isNotBlank()) "«$appLogName»؛ " else ""
                                val deadNameEn = if (appLogName.isNotBlank()) "“$appLogName”; " else ""
                                AppLog.append(this, "v2ray",
                                    deadName + "اتصال برقرار شد ولی ترافیکی عبور نکرد؛ قطع شد" + if (wasWon) "؛ روش برنده موتور فراموش شد." else ".",
                                    deadNameEn + "connected but no traffic passed; stopped" + if (wasWon) "; the engine's winning variant was forgotten." else ".")
                                statusText.text = L("اتصال برقرار شد ولی ترافیکی عبور نکرد؛ قطع شد.", "Connected but no traffic passed; stopped.")
                                android.widget.Toast.makeText(this, L("اتصال برقرار شد ولی ترافیکی عبور نکرد؛ قطع شد.", "Connected but no traffic passed; stopped."), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun initCore(): Boolean {
        if (coreInitDone) return true
        val ok = CoreInit.init(applicationContext)
        if (ok) coreInitDone = true
        return ok
    }

    /** Full VPN-mode Xray config, shared with the
     *  emergency page (see VpnConfigBuilder). */
    private fun buildVpnConfig(cfg: String, extraHosts: Map<String, String> = emptyMap()): String? {
        return VpnConfigBuilder.build(this, cfg, extraHosts)
    }

    /** The strip shows exactly one line: the latest
     *  message. Nothing here ever grows the strip. */
    private fun setStripLine(msg: String) {
        if (::stripLine.isInitialized) stripLine.text = msg
    }

    private fun addStatusLine(fa: String, en: String) {
        val line = L(fa, en)
        statusLines += line
        while (statusLines.size > 40) statusLines.removeAt(0)
        setStripLine(line)
    }

    private fun setStatusProgress(fa: String, en: String) {
        setStripLine(L(fa, en))
    }

    private fun showStatusHistoryDialog() {
        val tv = TextView(this).apply {
            text = if (statusLines.isEmpty()) L("هنوز گزارشی نیست.", "No report yet.")
                   else statusLines.joinToString("\n")
            textSize = 14f
            setPadding(24, 16, 24, 16)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش وضعیت", "Status report"))
            .setView(sv)
            .setPositiveButton(L("باشه", "OK"), null)
            .show()
    }

    /** The ⋮ menu: Service restart removed at the user's
     *  request; Delete config now deletes ALL configs;
     *  Real delay now pings ALL configs (the old separate
     *  "تست پینگ همه" button is gone). */
    private fun showMainMenu() {
        val autoDedupOn = prefs().getBoolean("auto_dedup", false)
        val autoNopingOn = prefs().getBoolean("auto_noping", false)
        val items = arrayOf(
            L("حذف کانفیگ", "Delete config"),
            L("حذف کانفیگ‌های تکراری", "Delete duplicate config"),
            L("حذف کانفیگ‌های نامعتبر", "Delete invalid config"),
            L("خروجی کانفیگ‌ها در کلیپ‌بورد", "Export configs to clipboard"),
            L("پیدا کردن کانفیگ انتخاب‌شده", "Locate the selected config"),
            L("مرتب‌سازی بر اساس نتایج تست", "Sorting by test results"),
            L("تست TCP کانفیگ", "Tcping config"),
            L("تست پینگ همه", "Real delay config"),
            L("به‌روزرسانی اشتراک", "Update subscription"),
            L("حذف خودکار تکراری‌ها: ", "Auto-delete duplicates: ") + if (autoDedupOn) L("روشن", "On") else L("خاموش", "Off"),
            L("حذف خودکار بدون‌پینگ‌ها: ", "Auto-delete no-ping: ") + if (autoNopingOn) L("روشن", "On") else L("خاموش", "Off")
        )
        showSmallPopupMenu(menuBtn, items) { which ->
            when (which) {
                0 -> deleteAllConfigs()
                1 -> deleteDuplicates()
                2 -> deleteInvalid()
                3 -> exportToClipboard()
                4 -> locateSelected()
                5 -> sortByTestResults()
                6 -> tcpingSelected()
                7 -> pingAll()
                8 -> updateVpnSubs()
                9 -> {
                    val on = !prefs().getBoolean("auto_dedup", false)
                    prefs().edit().putBoolean("auto_dedup", on).apply()
                    if (on) {
                        // Run at once AND say how many:
                        // a silent toggle is exactly what
                        // looked fake before.
                        val removed = dedupServers()
                        statusText.text = if (removed > 0)
                            L("حذف خودکار تکراری‌ها روشن شد؛ $removed تکراری حذف شد.", "Auto-delete duplicates on; $removed duplicates removed.")
                        else
                            L("حذف خودکار تکراری‌ها روشن شد؛ تکراری‌ای نبود.", "Auto-delete duplicates on; no duplicates.")
                        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
                    } else {
                        statusText.text = L("حذف خودکار تکراری‌ها خاموش شد.", "Auto-delete duplicates off.")
                    }
                }
                10 -> {
                    val on = !prefs().getBoolean("auto_noping", false)
                    prefs().edit().putBoolean("auto_noping", on).apply()
                    if (on) {
                        // Clean the -1s that are ALREADY
                        // on screen right now, not only
                        // after the next test: turning
                        // the toggle on must do visible
                        // work at once. (The old build
                        // only cleaned after a fresh
                        // test, so with old -1s on
                        // screen it looked dead.)
                        val targetsNow = servers.filter { !XraySpeedtest.isGroup(it) }
                        val hadNegative = targetsNow.any { (pingMap[XraySpeedtest.dedupKey(it)] ?: 1L) < 0 }
                        val hadHealthy = targetsNow.any { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
                        val removed = deleteNoPingIfEnabled(targetsNow)
                        statusText.text = when {
                            removed > 0 -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ $removed کانفیگ حذف شد.", "Auto-delete no-ping on; $removed configs removed.")
                            pingMap.isEmpty() -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ هنوز تستی انجام نشده.", "Auto-delete no-ping on; no test run yet.")
                            hadNegative && !hadHealthy -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ همه تست‌ها -۱ بودند، پس چیزی حذف نشد (تست خودش خراب بوده، نه کانفیگ‌ها).", "Auto-delete no-ping on; all tests were -1, so nothing was removed (the test itself failed, not the configs).")
                            else -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ کانفیگ بدون پینگی نبود.", "Auto-delete no-ping on; no no-ping configs.")
                        }
                        if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
                    } else {
                        statusText.text = L("حذف خودکار بدون‌پینگ‌ها خاموش شد.", "Auto-delete no-ping off.")
                    }
                }
            }
        }
    }

    /** Delete config = delete ALL configs, with a confirm
     *  dialog so a stray tap cannot wipe the list. */
    private fun deleteAllConfigs() {
        if (servers.isEmpty()) {
            statusText.text = L("کانفیگی برای حذف نیست.", "No config to delete.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف کانفیگ", "Delete config"))
            .setMessage(L("همه ${servers.size} کانفیگ حذف شود؟", "All ${servers.size} configs be deleted?"))
            .setPositiveButton(L("بله، حذف همه", "Yes, delete all")) { _, _ ->
                servers.clear()
                pingMap.clear()
                selected = null
                saveServers(); refreshList()
                statusText.text = L("همه کانفیگ‌ها حذف شد.", "All configs deleted.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    /** Shared dedup logic (manual + auto): returns
     *  how many were removed. Group headers are
     *  never duplicates. Ping entries are only
     *  cleared for keys with no remaining config. */
    private fun dedupServers(): Int {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        var removed = 0
        for (cfg in servers) {
            if (XraySpeedtest.isGroup(cfg)) { kept += cfg; continue }
            if (seen.add(XraySpeedtest.dedupKey(cfg))) kept += cfg else removed++
        }
        val remainingKeys = kept.map { XraySpeedtest.dedupKey(it) }.toSet()
        val removedKeys = servers.filter { !kept.contains(it) }
            .map { XraySpeedtest.dedupKey(it) }.filter { it !in remainingKeys }
        servers = kept
        for (k in removedKeys) pingMap.remove(k)
        if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
        saveServers(); refreshList()
        return removed
    }

    private fun deleteDuplicates() {
        val removed = dedupServers()
        statusText.text = if (removed == 0) L("کانفیگ تکراری‌ای نبود.", "No duplicate config.")
                         else L("$removed کانفیگ تکراری حذف شد.", "$removed duplicate configs removed.")
        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
    }

    private fun autoDedupIfEnabled() {
        if (!prefs().getBoolean("auto_dedup", false)) return
        val removed = dedupServers()
        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
    }

    /** Auto-delete configs that were tested in this
     *  run and got no ping — only when the toggle is
     *  on, never the selected config, and NEVER when
     *  the whole run failed (that means the network
     *  itself is down, not that the configs are
     *  dead). Customs count here: pingAll genuinely
     *  measures them, so a custom with -1 is really
     *  dead. (v1.57 wrongly excluded customs, which
     *  is why -1 customs survived with the toggle
     *  on.) Only groups and chains stay out: groups
     *  are headers, chains are never delay-tested. */
    private fun deleteNoPingIfEnabled(targets: List<String>): Int {
        if (!prefs().getBoolean("auto_noping", false)) return 0
        val results = targets.associateWith { pingMap[XraySpeedtest.dedupKey(it)] ?: -1L }
        if (results.values.none { it >= 0 }) return 0
        val victims = targets.filter { cfg ->
            !XraySpeedtest.isGroup(cfg) && !XraySpeedtest.isChain(cfg) &&
                cfg != selected &&
                (results[cfg] ?: -1L) < 0
        }
        if (victims.isEmpty()) return 0
        servers.removeAll(victims.toSet())
        for (cfg in victims) pingMap.remove(XraySpeedtest.dedupKey(cfg))
        if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
        saveServers(); refreshList()
        return victims.size
    }

    /** Why delete-invalid used to "not work": outboundFor
     *  can THROW (malformed URI / JSON / base64) instead of
     *  returning null, and a config with a blank host or an
     *  out-of-range port used to pass parseServer. Both are
     *  now invalid, and any exception means invalid. */
    /** Invalid = cannot connect, judged by the
     *  real connect config. The old checks (parse +
     *  outbound + speedtest config) passed customs
     *  whose first outbound looked fine but whose
     *  full JSON (routing/dns) was broken — so
     *  those stayed and delete-invalid looked dead.
     *  build() assembles exactly what a connect
     *  uses, so build() == null is the honest test
     *  for links and customs alike. */
    private fun isInvalid(cfg: String): Boolean {
        if (XraySpeedtest.isGroup(cfg)) return false
        return try {
            if (XraySpeedtest.isChain(cfg)) {
                val parts = XraySpeedtest.chainParts(cfg)
                if (parts.isEmpty()) return true
                parts.any { isInvalid(it) }
            } else {
                val srv = XraySpeedtest.parseServer(cfg) ?: return true
                if (srv.host.isBlank() || srv.port <= 0 || srv.port > 65535) return true
                VpnConfigBuilder.build(this, cfg) == null
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun deleteInvalid() {
        // Compute once: the old code ran isInvalid
        // twice per config (two filters).
        val removedList = servers.filter { isInvalid(it) }
        if (removedList.isNotEmpty()) {
            servers.removeAll(removedList.toSet())
            for (cfg in removedList) pingMap.remove(XraySpeedtest.dedupKey(cfg))
            if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
            saveServers(); refreshList()
        }
        val removed = removedList.size
        statusText.text = if (removed == 0) L("کانفیگ نامعتبری نبود.", "No invalid config.")
                         else L("$removed کانفیگ نامعتبر حذف شد.", "$removed invalid configs removed.")
        if (removed > 0) addStatusLine("$removed کانفیگ نامعتبر حذف شد", "$removed invalid configs removed")
    }

    private fun exportToClipboard() {
        val real = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (real.isEmpty()) {
            statusText.text = L("کانفیگی برای خروجی نیست.", "No config to export.")
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "configs", real.joinToString("\n")))
            statusText.text = L("${real.size} کانفیگ در کلیپ‌بورد کپی شد.", "${real.size} configs copied to clipboard.")
        } catch (e: Exception) {
            statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
        }
    }

    private fun locateSelected() {
        val row = selectedRowView
        if (selected == null || row == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        scrollView.post {
            scrollView.smoothScrollTo(0, row.top)
        }
        statusText.text = L("سرور انتخاب‌شده پیدا شد.", "Selected server found.")
    }

    private fun sortByTestResults() {
        servers.sortWith(compareBy { cfg ->
            val p = pingMap[XraySpeedtest.dedupKey(cfg)]
            if (p == null || p < 0) Long.MAX_VALUE else p
        })
        saveServers(); refreshList()
        statusText.text = L("سرورها بر اساس نتیجه تست مرتب شدند (سالم‌ها اول، تست‌نشده‌ها آخر).", "Servers sorted by test result (healthy first, untested last).")
    }

    private fun tcpingSelected() {
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        val srv = XraySpeedtest.parseServer(cfg)
        if (srv == null) {
            statusText.text = L("آدرس این سرور خوانده نشد.", "This server's address could not be read.")
            return
        }
        Thread {
            val t = XraySpeedtest.tcpConnectTime(srv.host, srv.port)
            runOnUiThread {
                pingMap[XraySpeedtest.dedupKey(cfg)] = t
                refreshList()
                statusText.text = if (t >= 0) "Tcping: $t ms"
                                 else L("Tcping ناموفق بود.", "Tcping failed.")
            }
        }.start()
    }

    private fun realDelaySelected() {
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        if (!initCore()) {
            statusText.text = L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start.")
            return
        }
        statusText.text = L("در حال تست Real delay...", "Testing Real delay...")
        Thread {
            val (d, err) = try {
                XraySpeedtest.measureOneWithError(cfg)
            } catch (t: Throwable) {
                -1L to (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                pingMap[XraySpeedtest.dedupKey(cfg)] = d
                refreshList()
                statusText.text = if (d >= 0) "Real delay: $d ms"
                                 else L("Real delay ناموفق بود: $err", "Real delay failed: $err")
            }
        }.start()
    }

    private fun pingAll() {
        // Groups are headers, not servers: never ping
        // them. Chains are multi-hop, not single
        // outbounds: measuring them always yields -1,
        // which is a lie (they are not "dead"), so
        // they are left untested ("—") instead.
        val targets = servers.filter { !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
        if (targets.isEmpty()) {
            statusText.text = L("کانفیگی برای تست نیست.", "No config to test.")
            return
        }
        // Root cause of the previous "-1 for everything":
        // this page never initialized the Xray core (Seq
        // context + initCoreEnv) before measuring, unlike
        // the subscription page which does. Without it,
        // measureOutboundDelay fails for every config.
        if (!initCore()) {
            statusText.text = L("تست پینگ شروع نشد: هسته Xray راه‌اندازی نشد.", "Ping test did not start: Xray core failed to start.")
            return
        }
        statusText.text = L("در حال تست پینگ همه سرورها (${targets.size} تا)...", "Testing ping for all servers (${targets.size} servers)...")
        setStatusProgress("در حال تست پینگ همه سرورها...", "Testing ping for all servers...")
        Thread {
            val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            var firstError: String? = null
            val errLock = Any()
            val tested = java.util.concurrent.atomic.AtomicInteger(0)
            val total = targets.size
            val futures = targets.map { cfg ->
                pool.submit {
                    val (d, err) = try {
                        XraySpeedtest.measureOneWithError(cfg)
                    } catch (t: Throwable) {
                        -1L to (t.message ?: t.javaClass.simpleName)
                    }
                    if (err != null) {
                        synchronized(errLock) { if (firstError == null) firstError = err }
                    }
                    synchronized(pingMap) { pingMap[XraySpeedtest.dedupKey(cfg)] = d }
                    // Stream: show this row's result the
                    // moment it arrives, instead of
                    // waiting for the whole test to end.
                    val done = tested.incrementAndGet()
                    runOnUiThread {
                        refreshList()
                        setStatusProgress("از $total کانفیگ $done تست گرفته شد", "$done of $total configs tested")
                    }
                }
            }
            for (f in futures) { try { f.get() } catch (_: Throwable) {} }
            pool.shutdown()
            runOnUiThread {
                refreshList()
                val healthy = targets.count { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
                val allBad = targets.all { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) < 0 }
                statusText.text = if (allBad && firstError != null)
                    L("تست پینگ تمام شد، همه -۱ شدند. خطای اول: $firstError", "Ping test finished, all were -1. First error: $firstError")
                else
                    L("تست پینگ تمام شد: $healthy سالم از ${targets.size}.", "Ping test finished: $healthy healthy of ${targets.size}.")
                setStatusProgress("تست پینگ انجام شد", "Ping test finished")
                addStatusLine("از ${targets.size} کانفیگ $healthy کانفیگ سالم بود", "$healthy of ${targets.size} configs were healthy")
                val removed = deleteNoPingIfEnabled(targets)
                if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
            }
        }.start()
    }
}
