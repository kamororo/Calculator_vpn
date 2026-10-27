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
    private val pingMap = mutableMapOf<String, Long>()
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(glassTopButton("+") { showAddMenu() })
        topRow.addView(glassTopButton("⋮") { showMainMenu() })
        root.addView(topRow)

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

        root.addView(TextView(this).apply {
            text = L("سرورها", "Servers"); textSize = 18f; setPadding(0, 16, 0, 8)
        })
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
        root.addView(listContainer)
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
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
                (300 * resources.displayMetrics.density).toInt(),
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }
        drawerPanel.addView(TextView(this).apply {
            text = "v2rayNG"; textSize = 26f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 32)
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
            drawerPanel.addView(glassMenuButton(label) {
                closeDrawer()
                action()
            })
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
        val fabSize = (72 * resources.displayMetrics.density).toInt()
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
                setColor(Color.WHITE)
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
    private fun glassCircleButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        return Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke((1 * d).toInt(), Color.parseColor("#55000000"))
            }
            elevation = 3 * d
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
    }

    // The card behind one server row: pale fill, slate
    // border, generous rounding, like the reference
    // card. Compact now (8dp vertical padding, 8dp
    // gap between cards, was 14dp), and the whole
    // border turns green while the card is selected.
    private fun serverCard(isSelected: Boolean = false): LinearLayout {
        val d = resources.displayMetrics.density
        val border = if (isSelected) "#2E9E5B" else "#4A5A7A"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20 * d
                setColor(Color.parseColor("#F2F5F9"))
                setStroke((2 * d).toInt(), Color.parseColor(border))
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
        val w = minOf(110, protocol.length * 8 + 20)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(this@VpnActivity).apply {
                text = protocol
                textSize = 12f
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
        val glass = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke(2, Color.parseColor("#DDDDDD"))
        }
        val pale = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#E3F2FD"))
            setStroke(2, Color.parseColor("#BBDEFB"))
        }
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
        val pale = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor("#E8F4FD"))
            setStroke(2, Color.parseColor("#BBDEFB"))
        }
        val stronger = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.parseColor("#BBDEFB"))
            setStroke(2, Color.parseColor("#90CAF9"))
        }
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
                setColor(Color.WHITE)
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
                    // Glass touch: pale blue for 2 seconds.
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

    /** The FAB itself: a thin ring always around it.
     *  Disconnected: ring light gray, fill = mode color
     *  (pale yellow for normal, pale blue for best) or
     *  light gray if no mode chosen yet. Connected:
     *  the whole circle is green, ring green. Label is
     *  N for normal, B for best, ▶/■ before any mode
     *  is chosen. During the 2-second glass touch the
     *  pale blue is kept. */
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
        val connected = ownSession || ProxyOnlyService.isRunning
        if (connected) fabBusy = false
        val (fill, stroke, fg, label) = when {
            connected -> listOf(
                Color.parseColor("#2E9E5B"),
                Color.parseColor("#2E9E5B"),
                Color.WHITE,
                if (mode == "normal") "N" else if (mode == "best") "B" else "■"
            )
            fabBusy -> listOf(
                Color.parseColor("#FFF3E0"),
                Color.parseColor("#FFB74D"),
                Color.parseColor("#E65100"),
                "…"
            )
            mode == "normal" -> listOf(
                Color.parseColor("#FFF9C4"),
                Color.parseColor("#BDBDBD"),
                Color.parseColor("#666666"),
                "N"
            )
            mode == "best" -> listOf(
                Color.parseColor("#E3F2FD"),
                Color.parseColor("#BDBDBD"),
                Color.parseColor("#1565C0"),
                "B"
            )
            else -> listOf(
                Color.parseColor("#E0E0E0"),
                Color.parseColor("#BDBDBD"),
                Color.parseColor("#666666"),
                "▶"
            )
        }
        connectBtn.text = label as String
        connectBtn.setTextColor(fg as Int)
        connectBtn.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill as Int)
            setStroke(3, stroke as Int)
        }
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

    private fun refreshList() {
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
        for (cfg in visible) {
            val isGroup = XraySpeedtest.isGroup(cfg)
            val srv = XraySpeedtest.parseServer(cfg)
            val name = XraySpeedtest.displayName(cfg)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
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
            info.addView(TextView(this).apply { text = name; textSize = 16f })
            info.addView(TextView(this).apply {
                text = "${srv?.host ?: "?"}:${srv?.port ?: "?"}"
                textSize = 13f; setTextColor(Color.GRAY)
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
            row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val card = serverCard(isSel)
            card.addView(row)
            card.addView(protocolEdgeLabel(XraySpeedtest.protocolLabel(cfg)))
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

    private fun refreshStatus() {
        refreshFab()
        val connectedNow = (VpnClientService.isRunning &&
            VpnClientService.sessionTag in setOf("v2ray", "smart", null)) ||
            ProxyOnlyService.isRunning
        if (connectedNow && !stripAnnouncedConnected) {
            stripAnnouncedConnected = true
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
        }
    }

    private fun disconnectVpn() {
        try { ProxyOnlyService.stop(this) } catch (_: Exception) {}
        val stopIntent = Intent(this, VpnClientService::class.java).apply {
            action = "STOP"
        }
        try { startService(stopIntent) } catch (_: Exception) {}
        try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
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
    }

    private fun connectSelected(cfg: String) {
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
            // Only untested ones are measured when a
            // healthy cache exists; with no healthy
            // cache at all, everything is re-tested
            // (old -1s included) so a recovered
            // network is not missed.
            val toTest = if (cachedHealthy.isEmpty()) targets else untested
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
                    val removed = deleteNoPingIfEnabled(targets)
                    if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
                    connectSelected(chosen)
                }
            }
        }.start()
    }

    /** The + menu, exactly like v2rayNG's add menu,
     *  now as separate pale-blue glass buttons. */
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
        showGlassMenuDialog("+", items) { which ->
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
            selected?.let { startVpn(it) }
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
            val proxyCfg = if (mode == "Resolve only") {
                VpnConfigBuilder.applyResolvedIp(builtProxy ?: "", resolved)
            } else builtProxy
            if (proxyCfg == null) {
                statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
                fabIdle()
                return
            }
            if (!initCore()) {
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
        val vpnConfig = if (mode == "Resolve only") {
            VpnConfigBuilder.applyResolvedIp(built ?: "", resolved)
        } else built
        if (vpnConfig == null) {
            statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
            fabIdle()
            return
        }
        if (!initCore()) {
            statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
            fabIdle()
            return
        }
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            putExtra("name", XraySpeedtest.displayName(cfg))
            putExtra("link", effective)
            putExtra("session_tag", "v2ray")
        }
        // A normal connect is not an emergency connect.
        VpnClientService.emergencyConfig = null
        startService(intent)
        statusText.text = L("در حال اتصال...", "Connecting...")
        handler.postDelayed({ refreshStatus() }, 1500)
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
        showGlassMenuDialog("⋮", items) { which ->
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
