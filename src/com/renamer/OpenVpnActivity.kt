package com.renamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class OpenVpnActivity : Activity() {
    companion object {
        const val NAVY = "#1B365D"
        const val ORANGE = "#ED7D31"
        /** Generation of the running OVPN engine
         *  ladder: a new ladder bumps it, and the
         *  old one exits quietly instead of racing
         *  it (2.19 ran two ladders at once, and
         *  their disconnects killed each other's
         *  sessions, producing "unknown error"
         *  failures). */
        val ladderGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var ladderProfile: String? = null
        /** Name of the profile whose ENGINE-started
         *  chain is currently up (2.21), or null.
         *  Only the OVPN page's disconnect clears an
         *  engine chain through this flag; a
         *  user-started chain from the Chain page is
         *  never touched through it. */
        @Volatile var engineChainName: String? = null

        // 2.23: loopback proxy-core ownership
        // ("dots" or "chain"). The dots sweep and
        // the engine chain share one core; whoever
        // owns it may stop it, and a connect tap
        // (chain) evicts dots. Companion, so it is
        // shared across page instances.
        val proxyOwnerLock = Any()
        @Volatile var proxyOwner: String? = null

        /** Releases a "chain" claim (page disconnect
         *  paths and engine-off call this). */
        fun releaseChainProxyOwner() {
            synchronized(proxyOwnerLock) {
                if (proxyOwner == "chain") proxyOwner = null
            }
        }
    }

    private lateinit var statusText: TextView

    private lateinit var listContainer: LinearLayout
    private var profiles: MutableList<OvpnProfile> = mutableListOf()
    private var switchUpdating = false
    private var lastShownState: String? = null
    // status state (item 10 below).
    private var lastLogState: String? = null
    private var ovpnPollLogNoted = false
    private var needDialogShownFor: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            // are tracked separately now: a new log line
            // used to rebuild the whole list every 2s,
            // which could swallow a tap on a toggle made
            // in the same moment. Log-only changes now
            // refresh just the status line.
            val statusState = (OvpnEngine.activeName ?: "") + "|" + OvpnEngine.isConnected + "|" + OvpnEngine.isConnecting + "|" + (OvpnEngine.lastError ?: "") + "|" + (OvpnEngine.lastState ?: "") + "|" + (OvpnEngine.lastStateMsg ?: "") + "|" + OvpnEngine.waitingForInput
            val logState = OvpnEngine.logLines.size.toString() + "|" + OvpnEngine.logText().takeLast(200)
            if (statusState != lastShownState) {
                lastShownState = statusState
                lastLogState = logState
                refreshStatus()
                refreshList()
            } else if (logState != lastLogState) {
                lastLogState = logState
                refreshStatus()
                if (!ovpnPollLogNoted) {
                    ovpnPollLogNoted = true
                    try {
                                            } catch (_: Exception) {}
                }
            }
            // 2.24: when the line becomes idle after
            // having been busy (disconnect, failed
            // attempt, ladder end), re-test the dots
            // — otherwise they stay frozen at their
            // "while VPN up" grey.
            val nowBusy = anyVpnUp()
            if (lastVpnBusy && !nowBusy) handler.post { startHealthSweep() }
            lastVpnBusy = nowBusy
            handler.postDelayed(this, 2000)
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    // ADAPTIVE ENGINE (1.78) report button.
    private var engineReportBtn: android.widget.Button? = null

    // 2.23: two health dots per profile. localHealth /
    // remoteHealth: 0 = unknown (grey), 1 = ok
    // (green), -1 = bad (faded red). refreshList
    // paints from these maps, so the 2s poller never
    // resets them; the sweep fills them. The dot
    // views are re-registered on every rebuild.
    private val localHealth = HashMap<String, Int>()
    private val remoteHealth = HashMap<String, Int>()
    private val localDotViews = HashMap<String, View>()
    private val remoteDotViews = HashMap<String, View>()
    private var healthSweepGen = 0
    // 2.24: plain-language reason codes for the
    // dot-tap popup (never hosts/IPs/ports).
    private val localResultCode = HashMap<String, String>()
    private val remoteResultCode = HashMap<String, String>()
    private var lastVpnBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = OvpnStore.load(this)
        if (intent.getBooleanExtra("widget_auto_connect", false) && !OvpnEngine.isConnected && !OvpnEngine.isConnecting) {
            val last = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_last", null)
            val all = OvpnStore.load(this)
            val pick = all.firstOrNull { it.name == last } ?: all.firstOrNull()
            if (pick != null) OvpnStore.pendingConnect = pick
            try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", pick?.name ?: "").apply() } catch (_: Exception) {}
        }
        val frame = FrameLayout(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Header, exactly like the screenshot: dark navy
        // bar, menu on the left, "Profiles" in white,
        // import icon on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(NAVY))
            setPadding(24, 32, 24, 32)
        }
        // 2.12: the header ☰ (returned to Home)
        // and its orange dot were removed at the
        // user's order.
        header.addView(TextView(this).apply {
            text = "Profiles"; textSize = 26f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        })
        // Log lives ONLY here now: a small glass icon
        // at the top opens the full log dialog; the
        // main page shows no log lines any more.
        // 2.24: "?" guide circle next to the log
        // icon: every OVPN option, including both
        // dots' full semantics.
        header.addView(TextView(this).apply {
            text = "?"; textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(2, Color.parseColor("#66FFFFFF"))
            }
            val s = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, (16 * resources.displayMetrics.density).toInt(), 0)
            }
            setOnClickListener { showOvpnGuide() }
        })
        header.addView(ImageView(this).apply {
            setImageBitmap(HydraUi.logIconBitmap(this@OpenVpnActivity, 40))
            val d8 = (8 * resources.displayMetrics.density).toInt()
            setPadding(d8, d8, d8, d8)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(2, Color.parseColor("#66FFFFFF"))
            }
            val s = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, (16 * resources.displayMetrics.density).toInt(), 0)
            }
            setOnClickListener { showLogDialog() }
        })
        // 2.12: the header ⧉ (import) was removed
        // at the user's order; the + FAB imports.
        root.addView(header)
        statusText = TextView(this).apply {
            text = "DISCONNECTED"; textSize = 20f
            setTextColor(Color.parseColor(NAVY))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(32, 32, 32, 16)
        }
        root.addView(statusText)

        // ADAPTIVE ENGINE (1.78): minimal report
        // button, visible only while the engine is
        // on (visibility refreshed in onResume).
        engineReportBtn = AdaptiveEngine.reportButton(this, AdaptiveEngine.S_OVPN).apply {
            visibility = if (AdaptiveEngine.isEnabled(this@OpenVpnActivity)) View.VISIBLE else View.GONE
        }
        root.addView(engineReportBtn)
        // v1.32: OpenVPN3 engine. No compat-mode
        // button anymore: there is no TLS toggle
        // in OpenVPN3, the core is the official
        // one and the config is used verbatim.
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 0)
        }
        root.addView(listContainer)
        // Bottom divider, like the screenshot.
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(32, 24, 32, 0) }
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        })
        scroll.addView(root)
        frame.addView(scroll)
        // + FAB, bottom-right.
        val fabSize = (72 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        frame.addView(Button(this).apply {
            text = "+"; textSize = 32f
            setTextColor(Color.WHITE)
            background = HydraUi.GlassOrbDrawable(
                Color.parseColor("#F2DDA6"), Color.parseColor("#D9B877"), this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { importFile() }
        })
        // Bottom padding so the FAB never hides a row.
        root.setPadding(0, 0, 0, fabSize + fabMargin)
        setContentView(frame)
        frame.fitsSystemWindows = true
        refreshList()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        profiles = OvpnStore.load(this)
        handler.post(poller)
        AdaptiveEngine.setReportVisible(engineReportBtn, this, AdaptiveEngine.S_OVPN)
        refreshList()
        refreshStatus()
        val toConnect = OvpnStore.pendingConnect
        if (toConnect != null) {
            OvpnStore.pendingConnect = null
            connectProfile(toConnect)
        }
        // 2.23: fresh health sweep on every entry.
        startHealthSweep()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        // 2.23: the sweep is page-scoped — stop it
        // and its tunnel, unless the engine chain
        // owns the core by now (then the chain's own
        // lifecycle owns it, never the dots).
        healthSweepGen++
        synchronized(proxyOwnerLock) {
            if (proxyOwner == "dots") {
                try { ChainEngine.stopProxyCore() } catch (_: Exception) {}
                proxyOwner = null
            }
        }
    }

    /** Stops an ENGINE-started chain's proxy core
     *  (2.21). Called by the page's own disconnect
     *  paths and by connectProfile when switching
     *  profiles, so a stale loopback proxy never
     *  lingers. Never touches a user-started chain:
     *  those are tracked by ChainEngine prefs, not
     *  by engineChainName. */
    private fun stopEngineChainProxyIfActive() {
        try {
            if (engineChainName != null) {
                ChainEngine.stopProxyCore()
                ChainEngine.setRunning(this, null)
                engineChainName = null
                releaseChainProxyOwner()
            }
        } catch (_: Exception) {}
    }

    /** One-line name tap popup (2.21): a small glass
     *  popup exactly the size of the row, showing the
     *  full name, auto-closing after 2s, closing
     *  immediately on any other tap (outside touch)
     *  or on tapping the popup itself. */
    private fun showNamePopup(row: View, name: String) {
        try {
            val d = resources.displayMetrics.density
            val tv = TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            }
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(tv)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                tv.background = HydraUi.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 18f, this)
                val loc = IntArray(2)
                row.getLocationOnScreen(loc)
                val lp = win.attributes
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = loc[1]
                lp.width = row.width
                lp.height = if (row.height > 0) row.height
                    else android.view.WindowManager.LayoutParams.WRAP_CONTENT
                lp.dimAmount = 0f
                win.attributes = lp
            }
            tv.setOnClickListener { try { dlg.dismiss() } catch (_: Exception) {} }
            dlg.show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {} }, 2000)
        } catch (_: Exception) {}
    }

    private fun refreshStatus() {
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_openvpn", OvpnEngine.isConnected).apply() } catch (_: Exception) {}
        // 2.43: central-log flip detector (the page
        // polls every 2s). Engine-ON wins are logged
        // by the ladder itself; this covers direct
        // connects, direct failures and manual stops.
        // Ladder gaps are skipped via ladderProfile.
        val ovpnNow = OvpnEngine.isConnected && OvpnEngine.activeName != null
        if (ovpnNow && !appLogOvpnWasConnected) {
            if (appLogOvpnPending) {
                AppLog.append(this, "openvpn",
                    "«${OvpnEngine.activeName}» وصل شد؛ موتور هوشمند: خاموش؛ اتصال مستقیم.",
                    "“${OvpnEngine.activeName}” connected; smart engine OFF; direct connect.")
                // 2.45: background exit-IP check.
                ConnVerify.verifyOvpn(this, "openvpn", "openvpn", OvpnEngine.activeName ?: "?") { OvpnEngine.isConnected }
                // 2.60: last REAL dial per profile
                // (a real ovpn3 dial on this line —
                // unlike the upper dot, which is only
                // a port probe). Shown under each
                // profile name, so the page answers
                // "which profiles actually connect
                // on my line" from real attempts,
                // not from probes alone.
                try { recordDial(OvpnEngine.activeName ?: "", true, null); refreshList() } catch (_: Exception) {}
                appLogOvpnPending = false
            }
        } else if (!ovpnNow && appLogOvpnWasConnected && ladderProfile == null) {
            if (appLogOvpnLastName.isNotBlank()) {
                AppLog.append(this, "openvpn",
                    "«$appLogOvpnLastName»؛ قطع شد.",
                    "“$appLogOvpnLastName”; disconnected.")
            }
        }
        if (appLogOvpnPending && !ovpnNow && !OvpnEngine.isConnecting && OvpnEngine.lastError != null && OvpnEngine.lastError != appLogOvpnLastError) {
            appLogOvpnLastError = OvpnEngine.lastError
            AppLog.append(this, "openvpn",
                "«$appLogOvpnPendingName» وصل نشد: ${OvpnEngine.lastError}.",
                "“$appLogOvpnPendingName” did not connect: ${OvpnEngine.lastError}.")
            try { recordDial(appLogOvpnPendingName, false, OvpnEngine.lastError); refreshList() } catch (_: Exception) {}
            appLogOvpnPending = false
        }
        if (ovpnNow) appLogOvpnLastName = OvpnEngine.activeName ?: appLogOvpnLastName
        appLogOvpnWasConnected = ovpnNow
        if (OvpnEngine.isConnected && OvpnEngine.activeName != null) {
            statusText.text = "CONNECTED: ${OvpnEngine.activeName}"
            statusText.setTextColor(Color.parseColor("#2E9E5B"))
            needDialogShownFor = null
        } else if (OvpnEngine.isConnecting && OvpnEngine.activeName != null) {
            val st = OvpnEngine.lastState
            statusText.text = if (st != null) "CONNECTING: ${OvpnEngine.activeName}  [$st] [OpenVPN3]"
                              else "CONNECTING: ${OvpnEngine.activeName} [OpenVPN3]"
            statusText.setTextColor(Color.parseColor(ORANGE))
            // Server is waiting for credentials that
            // were not supplied: surface it as a
            // prompt instead of a silent CONNECTING.
            if (OvpnEngine.waitingForInput && needDialogShownFor != OvpnEngine.activeName) {
                needDialogShownFor = OvpnEngine.activeName
                showNeedCredentialsDialog()
            }
        } else if (ladderProfile != null) {
            // 2.41: a ladder is working but no client
            // is up right now (probe/expansion gap) —
            // show that instead of DISCONNECTED so a
            // tap visibly registers and isn't repeated.
            statusText.text = L("موتور در حال بررسی «${ladderProfile}»…", "Engine checking \"${ladderProfile}\"…")
            statusText.setTextColor(Color.parseColor(ORANGE))
        } else {
            statusText.text = "DISCONNECTED"
            statusText.setTextColor(Color.parseColor(NAVY))
            needDialogShownFor = null
        }

    }

    private fun showLogDialog() {
        val text = OvpnEngine.logText().ifBlank {
            L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.")
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش اتصال OpenVPN", "OpenVPN connection log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("openvpn log", OvpnEngine.logText()))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNeutralButton(L("پاک کردن", "Clear")) { _, _ ->
                try { OvpnEngine.clearLog() } catch (_: Exception) {}
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showNeedCredentialsDialog() {
        val p = profiles.firstOrNull { it.name == OvpnEngine.activeName } ?: return
        val userInput = EditText(this).apply { hint = "Username"; setText(p.username) }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // save the password always, unlike the first
        // dialog which has a checkbox. Now it has one
        // too, and honours the choice.
        val saveBox = CheckBox(this).apply {
            text = L("ذخیره‌ی رمز", "Save password"); isChecked = p.savePassword
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(userInput); addView(passInput); addView(saveBox)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("سرور یوزرنیم/پسورد می‌خواهد", "Server needs username/password"))
            .setMessage(L("اتصال در انتظار یوزرنیم/پسورد مانده است. آن‌ها را وارد کن تا دوباره وصل شود.", "The connection is waiting for username/password. Enter them to reconnect."))
            .setView(col)
            .setPositiveButton(L("وصل شو", "Connect")) { _, _ ->
                p.username = userInput.text.toString().trim()
                p.password = passInput.text.toString()
                p.savePassword = saveBox.isChecked
                if (p.savePassword) OvpnStore.save(this, profiles)
                try {
                                    } catch (_: Exception) {}
                OvpnEngine.disconnect()
                needDialogShownFor = null
                connectProfile(p)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        // 2.23: drop health state of deleted profiles
        // so the maps never paint a stale row.
        try {
            val names = profiles.map { it.name }.toSet()
            localDotViews.keys.retainAll(names)
            remoteDotViews.keys.retainAll(names)
            localHealth.keys.retainAll(names)
            remoteHealth.keys.retainAll(names)
            localResultCode.keys.retainAll(names)
            remoteResultCode.keys.retainAll(names)
        } catch (_: Exception) {}
        if (OvpnEngine.lastError != null) {
            listContainer.addView(TextView(this).apply {
                text = OvpnEngine.lastError
                textSize = 13f
                setTextColor(Color.parseColor("#B00020"))
                setPadding(0, 0, 0, 16)
            })
        }
        if (profiles.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز پروفایلی اضافه نشده است. با دکمه + پایین صفحه، یک فایل .ovpn وارد کن.",
                         "No profile yet. Tap the orange + button to import an .ovpn file.")
                setTextColor(Color.GRAY)
            })
            return
        }
        val connected = OvpnEngine.isConnected || OvpnEngine.isConnecting
        for ((index, p) in profiles.withIndex()) {
            val isActive = connected && OvpnEngine.activeName == p.name
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val d = resources.displayMetrics.density
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 18 * d
                    // 2.0: the active row reads as "on" at a glance.
                        setColor(if (isActive) Color.parseColor("#E8EEF7") else Color.WHITE)
                        setStroke((1 * d).toInt(), if (isActive) Color.parseColor(NAVY) else Color.parseColor("#E0E0E0"))
                }
                elevation = 2 * d
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
            }
            val sw = ImageView(this).apply {
                setImageBitmap(if (ladderProfile == p.name) HydraUi.toggleBitmapBusy(this@OpenVpnActivity) else HydraUi.toggleBitmap(this@OpenVpnActivity, "ovpn", isActive))
                val dd = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((48 * dd).toInt(), (32 * dd).toInt()).apply {
                    setMargins(0, 0, (8 * dd).toInt(), 0)
                }
                setOnClickListener {
                    if (ladderProfile == p.name) {
                        // 2.41: re-tapping the profile its own
                        // ladder is working on CANCELS it — it
                        // used to restart the ladder, so rapid
                        // re-taps could never let one finish.
                        ladderGen.incrementAndGet()
                        OvpnEngine.disconnect()
                        refreshStatus(); refreshList()
                    } else if (isActive) {
                        stopEngineChainProxyIfActive()
                        OvpnEngine.disconnect()
                    } else connectProfile(p)
                }
            }
            row.addView(sw)
                // 2.23: two 12dp health dots, same size,
            // stacked: UPPER = direct test on this
            // line (no engine), LOWER = server health
            // from outside Iran (via a tunnel exit).
            // A connected profile is green on both
            // by definition; otherwise the maps paint.
            val trulyConnected = OvpnEngine.isConnected && OvpnEngine.activeName == p.name
            val dotsCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, (4 * resources.displayMetrics.density).toInt(), 0)
                }
            }
            val upperDot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (trulyConnected) Color.parseColor("#43A047") else dotColor(localHealth[p.name] ?: 0))
                }
                val s = (12 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val lowerDot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (trulyConnected) Color.parseColor("#43A047") else dotColor(remoteHealth[p.name] ?: 0))
                }
                val s = (12 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    topMargin = (4 * resources.displayMetrics.density).toInt()
                }
            }
            dotsCol.addView(upperDot)
            dotsCol.addView(lowerDot)
            localDotViews[p.name] = upperDot
            remoteDotViews[p.name] = lowerDot
            dotsCol.setOnClickListener { showDotInfo(p.name) }
            row.addView(dotsCol)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(24, 0, 12, 0)
            }
            info.addView(TextView(this).apply {
                text = "OpenVPN Profile"; textSize = 14f
                setTextColor(Color.GRAY)
            })
            info.addView(TextView(this).apply {
                text = p.name; textSize = 18f
                setTextColor(Color.BLACK)
                // 2.21: long names stay on ONE line
                // with …; tapping the name shows the
                // full name in a small glass popup.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener { showNamePopup(row, p.name) }
            })
            // 2.60: last real dial under the name,
            // so the page itself answers which
            // profiles really connect on this line.
            info.addView(TextView(this).apply {
                val (dtxt, dcol) = dialLineText(p.name)
                text = dtxt; textSize = 12f
                setTextColor(Color.parseColor(dcol))
            })
            info.setOnClickListener { editProfile(index) }
            row.addView(info)
                // Profile diagnosis (no sensitive values,
                // only which blocks/options the file has).
                row.addView(TextView(this).apply {
                    text = "ℹ"; textSize = 22f
                    setTextColor(Color.parseColor(NAVY))
                    setPadding(12, 0, 12, 0)
                    setOnClickListener { showDiagnosis(index) }
                })
                // Edit (pencil), like the screenshot.
                row.addView(TextView(this).apply {
                    text = "✎"; textSize = 24f
                    setTextColor(Color.GRAY)
                    setPadding(12, 0, 12, 0)
                    setOnClickListener { editProfile(index) }
                })
                // Delete, with a confirm dialog (never instant).
                row.addView(Button(this).apply {
                    text = "✕"; textSize = 12f
                    setTextColor(Color.parseColor("#D32F2F"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#FCE4EC"))
                        setStroke(1, Color.parseColor("#F8BBD0"))
                    }
                    val s = (32 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { confirmDelete(index) }
                })
            listContainer.addView(row)
        }
    }

    private fun showDiagnosis(index: Int) {
        val p = profiles[index]
        val text = p.ovpn
        fun has(re: String): Boolean = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(text)
        fun lineOpt(name: String): String {
            val m = Regex("(?m)^\\s*$name\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            return m?.groupValues?.getOrNull(1)?.trim() ?: "—"
        }
        // What the ics-openvpn ConfigParser itself
        // makes of this file (no values, only types
        // and presence). If this says the cert/key
        // are missing or the profile check fails,
        // the engine is dropping something.
        var parserInfo = ""
        try {
            val cp = de.blinkt.openvpn.core.ConfigParser()
            cp.parseConfig(java.io.StringReader(text))
            val vp = cp.convertProfile()
            val check = vp.checkProfile(this)
            val checkStr = try { getString(check) } catch (_: Exception) { "res $check" }
            val authType = when (vp.mAuthenticationType) {
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS -> L("USERPASS (فقط یوزرنیم/پسورد)", "USERPASS (username/password only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_CERTIFICATES -> L("USERPASS_CERTIFICATES (یوزرنیم/پسورد + گواهی)", "USERPASS_CERTIFICATES (username/password + certificate)")
                de.blinkt.openvpn.VpnProfile.TYPE_CERTIFICATES -> L("CERTIFICATES (فقط گواهی)", "CERTIFICATES (certificate only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_PKCS12 -> "USERPASS_PKCS12"
                de.blinkt.openvpn.VpnProfile.TYPE_PKCS12 -> "PKCS12"
                else -> L("نوع ${vp.mAuthenticationType}", "Type ${vp.mAuthenticationType}")
            }
            parserInfo = L("نوع احراز هویت (موتور): $authType\n", "Auth type (engine): $authType\n") +
                L("بررسی پروفایل (موتور): $checkStr\n", "Profile check (engine): $checkStr\n") +
                L("ca در پروفایل موتور: ${if (!vp.mCaFilename.isNullOrBlank()) "بله" else "خیر"}\n", "ca in engine profile: ${if (!vp.mCaFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("cert در پروفایل موتور: ${if (!vp.mClientCertFilename.isNullOrBlank()) "بله" else "خیر"}\n", "cert in engine profile: ${if (!vp.mClientCertFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("key در پروفایل موتور: ${if (!vp.mClientKeyFilename.isNullOrBlank()) "بله" else "خیر"}\n", "key in engine profile: ${if (!vp.mClientKeyFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("tls-auth در موتور: ${if (vp.mUseTLSAuth) "بله (${vp.mTLSAuthDirection ?: ""})" else "خیر"}\n", "tls-auth in engine: ${if (vp.mUseTLSAuth) "Yes (${vp.mTLSAuthDirection ?: ""})" else "No"}\n")
        } catch (e: Exception) {
            parserInfo = L("خطای تجزیه موتور: ${e.message ?: e.javaClass.simpleName}\n", "Engine parse error: ${e.message ?: e.javaClass.simpleName}\n")
        }
        // Client certificate expiry, from the <cert>
        // block only (date + issuer, no cert content,
        // no private key). A cert the server has
        // revoked/expired is rejected exactly like
        // this log shows, so this date matters.
        var certInfo = ""
        try {
            val block = Regex("<cert>(.*?)</cert>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(text)?.groupValues?.getOrNull(1)
            if (block != null) {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(block.toByteArray())) as java.security.cert.X509Certificate
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                certInfo = L("گواهی کلاینت تا: ${fmt.format(cert.notAfter)}\n", "Client certificate until: ${fmt.format(cert.notAfter)}\n") +
                    L("صادرکننده گواهی کلاینت: ${cert.issuerX500Principal.name.take(80)}\n", "Client certificate issuer: ${cert.issuerX500Principal.name.take(80)}\n")
            } else {
                certInfo = L("بلوک <cert> در فایل نیست.\n", "No <cert> block in file.\n")
            }
        } catch (e: Exception) {
            certInfo = L("خواندن گواهی کلاینت ناموفق: ${e.message ?: ""}\n", "Failed to read client certificate: ${e.message ?: ""}\n")
        }
        val info = buildString {
            append(L("نام: ${p.name}\n", "Name: ${p.name}\n"))
            append(L("سرور: ${p.hostname}\n", "Server: ${p.hostname}\n"))
            append("proto: ${lineOpt("proto")}\n")
            append("remote: ${lineOpt("remote")}\n")
            append("cipher: ${lineOpt("cipher")}\n")
            append("auth: ${lineOpt("auth")}\n")
            append(L("ca دارد: ${if (has("<ca>") || has("(?m)^\\s*ca\\s+")) "بله" else "خیر"}\n", "Has ca: ${if (has("<ca>") || has("(?m)^\\s*ca\\s+")) "Yes" else "No"}\n"))
            append(L("گواهی کلاینت (cert) دارد: ${if (has("<cert>") || has("(?m)^\\s*cert\\s+")) "بله" else "خیر"}\n", "Has client certificate (cert): ${if (has("<cert>") || has("(?m)^\\s*cert\\s+")) "Yes" else "No"}\n"))
            append(L("کلید کلاینت (key) دارد: ${if (has("<key>") || has("(?m)^\\s*key\\s+")) "بله" else "خیر"}\n", "Has client key (key): ${if (has("<key>") || has("(?m)^\\s*key\\s+")) "Yes" else "No"}\n"))
            append(L("tls-auth دارد: ${if (has("tls-auth")) "بله" else "خیر"}\n", "Has tls-auth: ${if (has("tls-auth")) "Yes" else "No"}\n"))
            append(L("tls-crypt دارد: ${if (has("tls-crypt")) "بله" else "خیر"}\n", "Has tls-crypt: ${if (has("tls-crypt")) "Yes" else "No"}\n"))
            append(L("یوزرنیم/پسورد می‌خواهد: ${if (p.needsAuth()) "بله" else "خیر"}\n", "Needs username/password: ${if (p.needsAuth()) "Yes" else "No"}\n"))
            append(L("\n--- تشخیص موتور ---\n", "\n--- Engine diagnosis ---\n"))
            append(parserInfo)
            append(certInfo)
            append(L("\nنتیجه تست قبلی: با گزینه «بدون گواهی» سرور خطای صریح certificate unknown داد، پس سرور حتماً گواهی کلاینت می‌خواهد. آن گزینه را خاموش نگه دار.", "\nPrevious test result: with the “no certificate” option the server gave an explicit certificate unknown error, so the server definitely wants the client certificate. Keep that option off."))
        }
        val tv = TextView(this).apply {
            this.text = info
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        // v1.32: OpenVPN3 uses the .ovpn verbatim.
        // The old 2.x workarounds (no-client-cert,
        // TLS compat/normal) no longer exist and
        // are not shown anymore.
        val note = TextView(this).apply {
            this.text = L("موتور: OpenVPN3 (مثل برنامه رسمی) — فایل .ovpn دقیقاً همان‌طور که هست استفاده می‌شود.", "Engine: OpenVPN3 (like the official app) — the .ovpn file is used verbatim.")
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(32, 0, 32, 16)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv); addView(note)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تشخیص پروفایل", "Profile diagnosis"))
            .setView(col)
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun editProfile(index: Int) {
        OvpnStore.pendingIndex = index
        OvpnStore.pending = profiles[index].copy()
        startActivity(Intent(this, OpenVpnImportActivity::class.java))
    }

    private fun confirmDelete(index: Int) {
        val p = profiles[index]
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف پروفایل", "Delete profile"))
            .setMessage(L("پروفایل «", "Profile \"") + p.name + L("» حذف شود؟", "\" will be deleted."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                val wasActive = OvpnEngine.activeName == p.name
                profiles.removeAt(index)
                OvpnStore.save(this, profiles)
                if (wasActive) {
                    stopEngineChainProxyIfActive()
                    OvpnEngine.disconnect()
                }
                refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    // 2.43: central-log state for the flip
    // detector in refreshStatus.
    private var appLogOvpnWasConnected = false
    private var appLogOvpnLastName = ""
    private var appLogOvpnPending = false
    private var appLogOvpnPendingName = ""
    private var appLogOvpnLastError: String? = null

    private fun connectProfile(p: OvpnProfile) {
        stopEngineChainProxyIfActive()
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_openvpn", true).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", p.name).apply() } catch (_: Exception) {}
        // Require BOTH username and password when the
        // profile needs auth: a blank username alone
        // used to let openvpn start, then wait forever
        // for input (NEED state) while the UI just
        // showed CONNECTING.
        if (p.needsAuth() && (p.username.isBlank() || p.password.isBlank()) && !p.hasEmbeddedCredentials()) {
            // Ask for credentials first, then connect.
            val userInput = EditText(this).apply {
                hint = "Username"
                setText(p.username)
            }
            val passInput = EditText(this).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val saveBox = CheckBox(this).apply {
                text = "Save password"; isChecked = true
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                addView(userInput); addView(passInput); addView(saveBox)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(col)
                .setPositiveButton("CONNECT") { _, _ ->
                    p.username = userInput.text.toString().trim()
                    p.password = passInput.text.toString()
                    p.savePassword = saveBox.isChecked
                    if (p.savePassword) OvpnStore.save(this, profiles)
                    connectOvpnResolved(p)
                }
                .setNegativeButton(L("لغو", "Cancel")) { _, _ -> refreshList() }
                .show()
            return
        }
        connectOvpnResolved(p)
    }

    /** ADAPTIVE ENGINE (2.21): the engine-ON OpenVPN
     *  ladder, after the 2.20 field report (healthy
     *  configs connect instantly; the VPN Gate server
     *  answered nothing on any method):
     *  (a) ONE ladder at a time (generation guard,
     *  2.20); guarded disconnects; engine-off aborts
     *  within ~400ms; profile names on every line;
     *  failures carry the core's last state.
     *  (b) FAST probes: a TCP rung first gets a
     *  reliable 5s connect probe (a failed TCP probe
     *  means the TCP handshake cannot succeed, so
     *  the rung is skipped instead of burning 40s).
     *  A UDP rung gets a genuine OpenVPN
     *  client-reset probe; silence is INCONCLUSIVE
     *  (tls-auth/tls-crypt servers stay silent to
     *  unauthenticated resets by design), so a
     *  silent UDP rung still runs with a shorter
     *  25s budget instead of being skipped.
     *  (c) EFFECT dedupe: the expanded rung runs
     *  only when it actually adds IPs (2.20 wasted
     *  40s on it with "no IP change").
     *  (d) PER-SECTION LEARNING: the method that
     *  connected a profile last time goes first next
     *  time (OVPN keys only — never leaks into
     *  WireGuard or v2ray).
     *  (e) LAST RESORT = CHAIN: after all direct
     *  rungs fail, the ladder routes the OpenVPN
     *  client through a healthy v2ray config via
     *  the app's existing chain machinery (loopback
     *  Xray proxy + OvpnEngine chain mode).
     *  Success still needs the real CONNECTED event
     *  plus a 3s hold. No method from the original
     *  set was removed: dead endpoints are skipped
     *  fast (and the skip is logged), nothing else.
     *  OVPN rungs use ONLY OVPN-level methods (IPs,
     *  proto, mssfix, DoH, chain) — v2ray-only
     *  tricks (fragment, MUX, ALPN) are never
     *  applied to an OpenVPN profile. Saved configs
     *  are never rewritten. Off = the old single
     *  direct connect. */
    private fun connectOvpnResolved(p: OvpnProfile) {
        // 2.43: central log (attempt; the outcome is
        // logged by the ladder wins and by the
        // refreshStatus flip detector below).
        val ovpnEng = AdaptiveEngine.isEnabled(this)
        AppLog.append(this, "openvpn",
            "تلاش اتصال «${p.name}»؛ موتور هوشمند: ${if (ovpnEng) "روشن" else "خاموش"}.",
            "Connect attempt “${p.name}”; smart engine: ${if (ovpnEng) "ON" else "OFF"}.")
        if (!ovpnEng) { appLogOvpnPending = true; appLogOvpnPendingName = p.name }
        if (!AdaptiveEngine.isEnabled(this)) {
            OvpnEngine.connect(this, p) { runOnUiThread { refreshStatus(); refreshList() } }
            return
        }
        Thread { runOvpnEngineLadder(p) }.start()
    }

    private data class OvpRung(
        val id: String,
        val nameFa: String,
        val nameEn: String,
        val text: String,
        val detailFa: String,
        val detailEn: String
    )

    private data class OvpExpansion(
        val expanded: String,
        val replaced: String?,
        val replacedDoh: String?,
        val swapped: String?,
        val mssfixed: String?
    )

    private data class OvpEndpoint(val host: String, val port: Int, val proto: String)

    /** Runs the engine-ON OpenVPN ladder on a
     *  background thread. Never claims success
     *  without the core's CONNECTED event plus a
     *  3s stability hold (and a through-tunnel
     *  exit-IP probe for direct rungs). */
    /** 2.41: wrapper that publishes the ladder's
     *  profile so the page SHOWS the ladder (status
     *  line + busy toggle) instead of looking dead
     *  during probe/expansion gaps — the invisible
     *  gaps were why re-taps kept killing ladders. */
    private fun runOvpnEngineLadder(p: OvpnProfile) {
        ladderProfile = p.name
        refreshOvpnUi()
        try { runOvpnEngineLadderInner(p) } finally {
            if (ladderProfile == p.name) ladderProfile = null
            refreshOvpnUi()
        }
    }

    private fun runOvpnEngineLadderInner(p: OvpnProfile) {
        val appCtx = applicationContext
        val myGen = ladderGen.incrementAndGet()
        fun log(fa: String, en: String) {
            try { AdaptiveEngine.append(appCtx, AdaptiveEngine.S_OVPN, fa, en) } catch (_: Exception) {}
        }
        fun aborted(): Boolean = ladderGen.get() != myGen || !AdaptiveEngine.isEnabled(appCtx)
        fun safeDisconnect() {
            try {
                if (ladderGen.get() == myGen && OvpnEngine.activeName == p.name) {
                    if (engineChainName == p.name) {
                        ChainEngine.stopProxyCore()
                        ChainEngine.setRunning(appCtx, null)
                        engineChainName = null
                    }
                    OvpnEngine.disconnect()
                }
            } catch (_: Exception) {}
        }
        /** True when the ladder must stop now (and
         *  logs why). Engine-off also stops the
         *  current attempt; a superseding ladder
         *  owns the session, so it is left alone
         *  (its proxy core is stopped separately). */
        fun stopRequested(sessionCheck: Boolean): Boolean {
            if (ladderGen.get() != myGen) {
                log("موتور: اتصال دیگری شروع شد؛ نردبان «${p.name}» متوقف شد.",
                    "Engine: another connection started; ladder for \"${p.name}\" stopped.")
                return true
            }
            if (!AdaptiveEngine.isEnabled(appCtx)) {
                log("موتور: موتور خاموش شد؛ نردبان «${p.name}» متوقف شد.",
                    "Engine: engine turned off; ladder for \"${p.name}\" stopped.")
                safeDisconnect()
                return true
            }
            if (sessionCheck) {
                val an = OvpnEngine.activeName
                if (an != null && an != p.name) {
                    log("موتور: اتصال دیگری شروع شد؛ نردبان «${p.name}» متوقف شد.",
                        "Engine: another connection started; ladder for \"${p.name}\" stopped.")
                    return true
                }
            }
            return false
        }
        fun ipDetail(t: String): Pair<String, String> {
            val c = AdaptiveEngine.ovpnIpChanges(p.ovpn, t)
            return if (c.isEmpty()) "بدون تغییر IP" to "no IP change" else c to c
        }
        fun expansionRungs(e: OvpExpansion): List<OvpRung> {
            val out = ArrayList<OvpRung>()
            fun add(id: String, nFa: String, nEn: String, text: String?, dFa: String, dEn: String) {
                if (text == null) return
                if (out.any { it.text == text }) return
                out += OvpRung(id, nFa, nEn, text, dFa, dEn)
            }
            val dExp = ipDetail(e.expanded)
            // 2.21: the expanded rung runs ONLY when
            // it actually adds IPs — 2.20 wasted a
            // full 40s attempt on it with no change.
            val expandedUseful = e.expanded != p.ovpn && dExp.first != "بدون تغییر IP"
            add("expanded", "IP جایگزین", "Alternate IPs", if (expandedUseful) e.expanded else null, dExp.first, dExp.second)
            val dRep = if (e.replaced != null) ipDetail(e.replaced) else "بدون تغییر IP" to "no IP change"
            add("replaced", "IP مستقیم به‌جای هاست", "Direct IP instead of hostname", e.replaced, dRep.first, dRep.second)
            val dDoh = if (e.replacedDoh != null) ipDetail(e.replacedDoh) else "بدون تغییر IP" to "no IP change"
            add("replacedDoh", "IP از راه DoH به‌جای هاست", "Direct IP via DoH instead of hostname", e.replacedDoh, dDoh.first, dDoh.second)
            if (e.swapped != null) {
                val dSw = ipDetail(e.swapped)
                add("swapped", "IP جایگزین + پروتکل جایگزین", "Alternate IPs + alternate protocol", e.swapped,
                    dSw.first + "؛ پروتکل جایگزین", dSw.second + "; alternate protocol")
            }
            if (e.mssfixed != null) {
                val dMs = ipDetail(e.mssfixed)
                add("mssfix", "IP جایگزین + mssfix", "Alternate IPs + mssfix", e.mssfixed,
                    dMs.first + "؛ mssfix 1200", dMs.second + "; mssfix 1200")
            }
            return out
        }
        // Parallel prep (never blocks the first
        // connect): direct exit IP, and the
        // DNS/DoH expansion texts.
        val directIpRef = java.util.concurrent.atomic.AtomicReference<String?>()
        Thread { try { directIpRef.set(fetchOvpnExitIp()) } catch (_: Exception) {} }.start()
        val expRef = java.util.concurrent.atomic.AtomicReference<OvpExpansion?>()
        Thread {
            try {
                val expanded = AdaptiveEngine.ovpnExpandedRemotes(p.ovpn)
                val replaced = AdaptiveEngine.ovpnReplacedRemotes(p.ovpn)
                val replacedDoh = AdaptiveEngine.ovpnReplacedRemotesDoh(p.ovpn)
                val base = if (expanded != p.ovpn) expanded else p.ovpn
                expRef.set(OvpExpansion(expanded, replaced, replacedDoh,
                    AdaptiveEngine.ovpnProtoSwapped(base), AdaptiveEngine.ovpnWithMssfix(base)))
            } catch (_: Exception) {}
        }.start()
        // 2.37: engine methods first, always. The
        // original rung is NEVER attempted before
        // the expansion methods: expansion gets up
        // to 15s (slow DNS/DoH on filtered lines
        // routinely exceeds 3s), and only if it
        // still has not arrived does the original
        // run alone before the chain.
        var exp: OvpExpansion? = null
        val expDeadline = System.currentTimeMillis() + 15000
        while (exp == null && System.currentTimeMillis() < expDeadline) {
            exp = expRef.get()
            if (exp == null) try { Thread.sleep(100) } catch (_: Exception) {}
        }
        val rungs = ArrayList<OvpRung>()
        var expInserted = false
        if (exp != null) {
            rungs += expansionRungs(exp)
            expInserted = true
        }
        rungs += OvpRung("original", "اصلی (بدون تغییر)", "Original (unchanged)", p.ovpn, "بدون تغییر", "unchanged")
        // Per-section learning (2.21): the method
        // that connected THIS profile last time goes
        // first. Keys are namespaced by section, so
        // an OVPN win never affects other sections.
        val learned = AdaptiveEngine.learnedMethod(appCtx, AdaptiveEngine.S_OVPN, p.name)
        if (learned != null) {
            val li = rungs.indexOfFirst { it.id == learned }
            if (li > 0) {
                val lr = rungs.removeAt(li)
                rungs.add(0, lr)
                log("موتور: روش یادگرفته‌شده برای «${p.name}» اول امتحان می‌شود: «${lr.nameFa}».",
                    "Engine: learned method for \"${p.name}\" goes first: \"${lr.nameEn}\".")
            }
        }
        if (expInserted) {
            log("موتور: بررسی «${p.name}» شروع شد؛ ${rungs.size} روش امتحان می‌شود.",
                "Engine: checking \"${p.name}\" started; ${rungs.size} methods will be tried.")
        } else {
            log("موتور: بررسی «${p.name}» شروع شد؛ روش‌های IP جایگزین هنوز آماده نیست؛ با روش اصلی شروع می‌شود و اگر آماده شدند اضافه می‌شوند.",
                "Engine: checking \"${p.name}\" started; alternate-IP methods are not ready yet; starting with the original, and they will join if they arrive.")
        }
        if (expInserted && rungs.none { it.id == "expanded" || it.id == "replaced" || it.id == "replacedDoh" }) {
            log("موتور: برای «${p.name}» نشانی IP تازه‌ای پیدا نشد (DNS جواب نداد یا سرور از اول IP است)؛ روش‌های IP جایگزین رد شد.",
                "Engine: no fresh IPs found for \"${p.name}\" (DNS gave nothing or the server is already an IP); alternate-IP methods skipped.")
        }
        val probeCache = HashMap<String, Int>()
        var tcpAliveSeen = false
        var tcpDeadSeen = false
        var idx = 0
        while (idx < rungs.size) {
            if (stopRequested(false)) return
            if (!expInserted) {
                val e = expRef.get()
                if (e != null) {
                    expInserted = true
                    val add = expansionRungs(e).filter { b -> rungs.none { it.text == b.text } }
                    if (add.isNotEmpty()) {
                        rungs.addAll(idx, add)
                        log("موتور: روش‌های IP جایگزین آماده شد و به نردبان «${p.name}» اضافه شد (اکنون ${rungs.size} روش).",
                            "Engine: alternate-IP methods are ready and joined the ladder for \"${p.name}\" (now ${rungs.size} methods).")
                    }
                }
            }
            val rung = rungs[idx]
            // ---- fast endpoint probe (2.21) ----
            val ep = rungEndpoint(rung.text)
            var timeoutMs = 40000L
            var skipFa: String? = null
            var skipEn: String? = null
            if (ep != null) {
                val key = ep.proto + "://" + ep.host + ":" + ep.port
                val res = probeCache.getOrPut(key) { probeEndpoint(ep) }
                if (ep.proto == "tcp") {
                    if (res == 0) tcpAliveSeen = true
                    if (res == 1) tcpDeadSeen = true
                }
                when (res) {
                    -1 -> { skipFa = "نام سرور به IP تبدیل نشد"; skipEn = "hostname could not be resolved" }
                    1 -> { skipFa = "سرور جواب پروب سریع را نداد"; skipEn = "server did not answer the fast probe" }
                    2 -> timeoutMs = 25000L
                }
            }
            if (skipFa != null) {
                log("موتور: روش «${rung.nameFa}» برای «${p.name}» رد شد: $skipFa.",
                    "Engine: \"${rung.nameEn}\" for \"${p.name}\" skipped: $skipEn.")
                idx++
                continue
            }
            log("موتور: تلاش ${idx + 1} از ${rungs.size} — «${rung.nameFa}» برای «${p.name}» (${rung.detailFa}).",
                "Engine: attempt ${idx + 1} of ${rungs.size} — \"${rung.nameEn}\" for \"${p.name}\" (${rung.detailEn}).")
            val rungProfile = p.copy(ovpn = rung.text)
            val started = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                try { OvpnEngine.connect(this@OpenVpnActivity, rungProfile) {} } catch (_: Exception) {} finally { started.countDown() }
            }
            try { started.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            refreshOvpnUi()
            when (waitOvpnRung(p.name, timeoutMs) { aborted() }) {
                0 -> {
                    var stable = true
                    var abortHit = false
                    val holdEnd = System.currentTimeMillis() + 3000
                    while (System.currentTimeMillis() < holdEnd) {
                        if (aborted()) { abortHit = true; break }
                        if (!OvpnEngine.isConnected || OvpnEngine.activeName != p.name) { stable = false; break }
                        try { Thread.sleep(200) } catch (_: Exception) {}
                    }
                    if (abortHit) { stopRequested(true); return }
                    if (!stable) {
                        log("موتور: روش «${rung.nameFa}» برای «${p.name}» لحظه‌ای وصل شد ولی پایدار نماند.",
                            "Engine: \"${rung.nameEn}\" for \"${p.name}\" connected briefly but did not hold.")
                        safeDisconnect()
                        try { Thread.sleep(800) } catch (_: Exception) {}
                        idx++
                    } else {
                        val directIp = directIpRef.get()
                        val (ok, dFa, dEn) = verifyOvpnTunnel(directIp)
                        if (ok) {
                            AdaptiveEngine.learnMethod(appCtx, AdaptiveEngine.S_OVPN, p.name, rung.id)
                            // 2.41: remember this profile's
                            // measured egress for the chain
                            // page's info card (an OVPN chain
                            // cannot measure its own egress
                            // from inside the app).
                            try { appCtx.getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_exit_" + p.name, dFa).apply() } catch (_: Exception) {}
                            val extraFa = if (directIp != null) " (IP مستقیم: $directIp)" else " (IP مستقیم خوانده نشد؛ راستی‌آزمایی با IP خروجی انجام شد)"
                            val extraEn = if (directIp != null) " (direct IP: $directIp)" else " (direct IP unread; verification used the exit IP only)"
                            log("موتور: «${p.name}» با روش «${rung.nameFa}» واقعاً وصل شد — ${methodExplainFa(rung.id, null)}. IP خروجی: $dFa$extraFa.",
                                "Engine: \"${p.name}\" really connected with \"${rung.nameEn}\" — ${methodExplainEn(rung.id, null)}. Exit IP: $dEn$extraEn.")
                            AppLog.append(appCtx, "openvpn",
                                "«${p.name}» با روش موتور «${rung.nameFa}» وصل شد؛ موتور هوشمند: روشن.",
                                "“${p.name}” connected with engine method “${rung.nameEn}”; smart engine ON.")
                            // 2.45: background exit-IP check.
                            ConnVerify.verifyOvpn(appCtx, "openvpn", "openvpn", p.name) { OvpnEngine.isConnected }
                            refreshOvpnUi()
                            return
                        }
                        log("موتور: تونل با روش «${rung.nameFa}» برای «${p.name}» بالا آمد ولی ترافیک عبور نکرد: $dFa.",
                            "Engine: tunnel came up with \"${rung.nameEn}\" for \"${p.name}\" but no traffic passed: $dEn.")
                        safeDisconnect()
                        try { Thread.sleep(800) } catch (_: Exception) {}
                        idx++
                    }
                }
                1 -> {
                    val err = OvpnEngine.lastError ?: ("نامشخص (آخرین وضعیت هسته: " + (OvpnEngine.lastState ?: "?") + " " + (OvpnEngine.lastStateMsg ?: "") + ")").trim()
                    log("موتور: روش «${rung.nameFa}» برای «${p.name}» جواب نداد: $err.",
                        "Engine: \"${rung.nameEn}\" for \"${p.name}\" did not work: $err.")
                    safeDisconnect()
                    try { Thread.sleep(800) } catch (_: Exception) {}
                    idx++
                }
                2 -> {
                    val secs = (timeoutMs / 1000).toInt()
                    log("موتور: روش «${rung.nameFa}» برای «${p.name}» در $secs ثانیه جواب نداد.",
                        "Engine: \"${rung.nameEn}\" for \"${p.name}\" gave no answer in $secs seconds.")
                    safeDisconnect()
                    try { Thread.sleep(800) } catch (_: Exception) {}
                    idx++
                }
                else -> {
                    if (ladderGen.get() != myGen && engineChainName == p.name) {
                        try { ChainEngine.stopProxyCore(); ChainEngine.setRunning(appCtx, null); engineChainName = null } catch (_: Exception) {}
                    }
                    stopRequested(true)
                    return
                }
            }
        }
        // ---- LAST RESORT: chain (2.21, multi-
        // section 2.23: healthy hop1 candidates from
        // v2ray, WireGuard and Emergency, best
        // first — the engine tries every section
        // before giving up on the profile) ----
        val hopCandidates = healthySectionHops(appCtx, true, exhaustive = true)
        if (hopCandidates.isEmpty()) {
            log("موتور: زنجیره ممکن نشد؛ در هیچ بخشی (v2ray، وایرگارد، اضطراری) کانفیگ سالم برای زنجیره اول پیدا نشد — اول در یکی از این بخش‌ها کانفیگ سالم اضافه کن تا موتور بتواند از آن زنجیره بسازد.",
                "Engine: chaining not possible; no healthy hop-1 config was found in any section (v2ray, WireGuard, Emergency) — add a healthy config in one of those sections first so the engine can chain through it.")
        } else {
            var chained = false
            val nV = hopCandidates.count { it.type == "v2ray" }
            val nW = hopCandidates.count { it.type == "wireguard" }
            val nE = hopCandidates.count { it.type == "emergency" }
            log("موتور: برای زنجیره آخر ${hopCandidates.size} کانفیگ سالم پیدا شد (v2ray: $nV، وایرگارد: $nW، اضطراری: $nE)؛ همه به ترتیب امتحان می‌شوند.",
                "Engine: ${hopCandidates.size} healthy configs found for the last-resort chain (v2ray: $nV, WireGuard: $nW, Emergency: $nE); all will be tried in order.")
            for (hop in hopCandidates) {
                if (aborted()) break
                if (tryChainFallback(p, appCtx, myGen, hop,
                        { fa, en -> log(fa, en) }, { aborted() })) { chained = true; break }
                // tryChainFallback cleans up after
                // itself on failure; the next
                // candidate gets a fresh attempt.
            }
            if (chained) {
                refreshOvpnUi()
                return
            }
        }
        if (aborted()) { stopRequested(true); return }
        safeDisconnect()
        val lastErr = OvpnEngine.lastError ?: ("نامشخص (آخرین وضعیت هسته: " + (OvpnEngine.lastState ?: "?") + " " + (OvpnEngine.lastStateMsg ?: "") + ")").trim()
        // Server-health diagnosis (2.22): from
        // evidence already collected — no new waits.
        // Order matters: DNS first (it explains
        // everything else), then the filtered-
        // handshake case, then dead-vs-offline.
        val firstEp = rungEndpoint(p.ovpn)
        val firstIsIp = firstEp?.host?.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) ?: true
        val dnsFailed = firstEp != null && !firstIsIp &&
            rungs.none { it.id == "expanded" || it.id == "replaced" || it.id == "replacedDoh" }
        val diagFa: String
        val diagEn: String
        when {
            dnsFailed -> {
                diagFa = "تشخیص: نام سرور به IP تبدیل نشد؛ DNS از این خط جواب نمی‌دهد (مسدود یا مسموم است)."
                diagEn = "Diagnosis: the hostname did not resolve; DNS gives nothing on this line (blocked or poisoned)."
            }
            tcpAliveSeen -> {
                diagFa = "تشخیص: پورت سرور باز است و جواب می‌دهد، ولی دست‌تکان اوپن‌وی‌پی‌ان کامل نمی‌شود؛ یعنی یا فیلتر مسیر ترافیک اوپن‌وی‌پی‌ان را می‌برد یا روی آن پورت سرویس اوپن‌وی‌پی‌ان نیست."
                diagEn = "Diagnosis: the server port is open and answers, but the OpenVPN handshake never completes; either a filter drops OpenVPN traffic or no OpenVPN service listens on that port."
            }
            tcpDeadSeen && directIpRef.get() == null -> {
                diagFa = "تشخیص: هم سرور جواب نداد هم IP مستقیم خوانده نشد؛ به‌احتمال زیاد اینترنت دستگاه قطع است یا مسیر کاملاً بسته است."
                diagEn = "Diagnosis: neither the server nor the direct exit IP answered; most likely the device is offline or the route is fully closed."
            }
            tcpDeadSeen -> {
                diagFa = "تشخیص: سرور اصلاً جواب نمی‌دهد؛ یا خاموش است یا از این خط کاملاً مسدود است."
                diagEn = "Diagnosis: the server does not answer at all; it is either down or fully blocked from this line."
            }
            directIpRef.get() == null -> {
                diagFa = "تشخیص: IP خروجی مستقیم خوانده نشد؛ شاید اینترنت دستگاه قطع است یا همین سرویس‌ها مسدودند — اول اتصال خط را چک کن."
                diagEn = "Diagnosis: the direct exit IP could not be read; the device may be offline or those services blocked — check the line first."
            }
            else -> {
                diagFa = "تشخیص: سرور در هیچ روشی جواب نداد؛ وضعیت دقیق پورت از این تست‌ها مشخص نیست."
                diagEn = "Diagnosis: the server answered no method; the port's exact state is unclear from these tests."
            }
        }
        log("موتور: $diagFa", "Engine: $diagEn")
        log("موتور: «${p.name}» با هیچ‌یک از ${rungs.size} روش مستقیم و زنجیره وصل نشد. آخرین خطا: $lastErr.",
            "Engine: \"${p.name}\" connected with none of the ${rungs.size} direct methods nor chaining. Last error: $lastErr.")
        refreshOvpnUi()
    }

    /** Waits for one rung's real outcome: 0 = the
     *  core reported CONNECTED, 1 = the core gave
     *  up (error/permission), 2 = timeout,
     *  3 = aborted (newer ladder, engine off, or
     *  another session took over). */
    private fun waitOvpnRung(name: String, timeoutMs: Long, abort: () -> Boolean): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (abort()) return 3
            val an = OvpnEngine.activeName
            if (an != null && an != name) return 3
            if (OvpnEngine.isConnected && an == name) return 0
            if (!OvpnEngine.isConnecting && !OvpnEngine.isConnected) return 1
            try { Thread.sleep(400) } catch (_: Exception) {}
        }
        return if (abort()) 3 else 2
    }

    /** Plain exit-IP fetch (no proxy): the address
     *  the internet sees for this device right now.
     *  Null when unreachable. Background threads
     *  only. */
    private fun fetchOvpnExitIp(): String? {
        val urls = listOf("https://api.ipify.org", "https://checkip.amazonaws.com")
        val ipRe = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
        for (u in urls) {
            try {
                val c = java.net.URL(u).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.requestMethod = "GET"
                if (c.responseCode == 200) {
                    val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (body.matches(ipRe)) return body
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /** Through-tunnel proof, Emergency-style: one
     *  probe, then a second after 4s when the first
     *  fails. Success = an exit IP different from
     *  the direct IP (or any exit IP when the direct
     *  one could not be read). Returns (ok, detailFa,
     *  detailEn); detail is the exit IP on success. */
    private fun verifyOvpnTunnel(directIp: String?): Triple<Boolean, String, String> {
        var exit = fetchOvpnExitIp()
        if (exit == null) {
            try { Thread.sleep(4000) } catch (_: Exception) {}
            exit = fetchOvpnExitIp()
        }
        if (exit == null) return Triple(false, "پروب ترافیک ناموفق بود", "traffic probe failed")
        if (directIp != null && exit == directIp) return Triple(false, "IP خروجی همان IP مستقیم است ($exit)", "exit IP equals the direct IP ($exit)")
        return Triple(true, exit, exit)
    }

    private fun refreshOvpnUi() {
        try {
            runOnUiThread {
                try { refreshStatus(); refreshList() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** First `remote` line + effective proto of one
     *  .ovpn text. A `remote host port proto` token
     *  wins over the `proto` directive. Null when
     *  there is no remote line at all. */
    private fun rungEndpoint(text: String): OvpEndpoint? {
        var host: String? = null
        var port = 1194
        var remoteProto: String? = null
        var directiveProto: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("remote ") && host == null) {
                val parts = t.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    host = parts[1]
                    if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
                    if (parts.size >= 4) {
                        val pr = parts[3].lowercase()
                        if (pr.startsWith("tcp")) remoteProto = "tcp" else if (pr == "udp") remoteProto = "udp"
                    }
                }
            }
            if (t == "proto udp" || t.startsWith("proto udp ")) directiveProto = "udp"
            else if (t.startsWith("proto tcp")) directiveProto = "tcp"
        }
        if (host == null) return null
        return OvpEndpoint(host, port, remoteProto ?: directiveProto ?: "udp")
    }

    /** Fast liveness probe for one rung endpoint.
     *  Returns 0 = server answered, 1 = reliably
     *  dead (TCP connect failed — the TCP handshake
     *  cannot succeed on a path where TCP itself
     *  fails), 2 = unknown (UDP silent — inconclusive,
     *  because tls-auth/tls-crypt servers stay silent
     *  to unauthenticated resets by design; the rung
     *  still runs, with a shorter budget), -1 = the
     *  hostname could not be resolved by either
     *  resolver. Background threads only. */
    private fun probeEndpoint(ep: OvpEndpoint): Int {
        val isIp = ep.host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))
        val hosts: List<String> = if (isIp) listOf(ep.host) else {
            val sys = try { AdaptiveEngine.resolveIps(ep.host) } catch (_: Exception) { emptyList() }
            if (sys.isNotEmpty()) sys.take(2)
            else try { AdaptiveEngine.resolveIpsDoh(ep.host) } catch (_: Exception) { emptyList() }.take(2)
        }
        if (hosts.isEmpty()) return -1
        return if (ep.proto == "tcp") {
            var ok = false
            for (h in hosts) {
                if (probeTcpAlive(h, ep.port)) { ok = true; break }
            }
            if (ok) 0 else 1
        } else {
            if (probeUdpReset(hosts[0], ep.port)) 0 else 2
        }
    }

    private fun probeTcpAlive(host: String, port: Int): Boolean {
        return try {
            val s = java.net.Socket()
            try {
                s.connect(java.net.InetSocketAddress(host, port), 2500)
                true
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { false }
    }

    /** One genuine OpenVPN client-reset packet
     *  (opcode 7 = HARD_RESET_CLIENT, key id 0),
     *  sent three times across 5s like the core's
     *  own retransmits. Any UDP reply means the
     *  server is alive. Silence proves nothing (see
     *  probeEndpoint). Background threads only. */
    private fun probeUdpReset(host: String, port: Int): Boolean {
        return try {
            val sock = java.net.DatagramSocket()
            sock.soTimeout = 500
            val addr = java.net.InetAddress.getByName(host)
            val sess = ByteArray(8)
            java.util.Random().nextBytes(sess)
            val pkt = ByteArray(13)
            pkt[0] = 0x38.toByte()
            System.arraycopy(sess, 0, pkt, 1, 8)
            val start = System.currentTimeMillis()
            val deadline = start + 5000
            var sent = 0
            var alive = false
            while (System.currentTimeMillis() < deadline && !alive) {
                val now = System.currentTimeMillis()
                if (sent < 3 && now - start >= sent * 1500L) {
                    try { sock.send(java.net.DatagramPacket(pkt, pkt.size, addr, port)) } catch (_: Exception) {}
                    sent++
                }
                try {
                    val buf = ByteArray(512)
                    sock.receive(java.net.DatagramPacket(buf, buf.size))
                    alive = true
                } catch (_: java.net.SocketTimeoutException) {}
            }
            try { sock.close() } catch (_: Exception) {}
            alive
        } catch (_: Exception) { false }
    }

    /** Plain-language explanation of what the winning
     *  method actually did — the success line must
     *  say this, not just the method's name. */
    private fun methodExplainFa(id: String, hop1: String?): String = when (id) {
        "expanded" -> "به‌جای نام سرور، با IPهای تازه وصل شد"
        "replaced" -> "نام سرور کاملاً با IP جایگزین شد"
        "replacedDoh" -> "IP سرور از راه DoH گرفته شد تا DNS مسموم دور زده شود"
        "swapped" -> "پروتکل اتصال عوض شد (UDP↔TCP)"
        "mssfix" -> "اندازه بسته‌ها کوچک شد (mssfix 1200)"
        "chain" -> "از راه زنجیره: اول «${hop1 ?: ""}» بعد تونل OpenVPN از داخل همان مسیر"
        else -> "بدون هیچ تغییری"
    }

    private fun methodExplainEn(id: String, hop1: String?): String = when (id) {
        "expanded" -> "connected with fresh IPs instead of the hostname"
        "replaced" -> "hostname fully replaced by IPs"
        "replacedDoh" -> "server IP fetched via DoH to bypass poisoned DNS"
        "swapped" -> "connection protocol swapped (UDP↔TCP)"
        "mssfix" -> "packet size reduced (mssfix 1200)"
        "chain" -> "via chaining: \"$hop1\" first, then the OpenVPN tunnel through it"
        else -> "unchanged"
    }

    /** LAST RESORT (2.21, multi-section 2.23):
     *  route the OpenVPN client through a healthy
     *  hop1 from any section (v2ray / WireGuard /
     *  Emergency), using the app's existing chain
     *  machinery (loopback Xray proxy + OvpnEngine
     *  chain mode). Returns true only when the chain
     *  really connected (real CONNECTED event + 3s
     *  hold); the session is left up for the caller.
     *  On any failure or abort the proxy core and
     *  the session are cleaned up here, so nothing
     *  lingers. Hydra is not a hop1: its desync
     *  shapes are applied to a full Hydra config by
     *  a tasting process the chain proxy does not
     *  run, so a shapeless hop labelled "Hydra"
     *  would be fake. */
    private fun tryChainFallback(
        p: OvpnProfile,
        appCtx: Context,
        myGen: Int,
        hop: ChainEngine.Hop,
        log: (String, String) -> Unit,
        aborted: () -> Boolean
    ): Boolean {
        fun cleanup() {
            try {
                if (ladderGen.get() == myGen) {
                    // 2.23: stop the core only while
                    // the chain still owns it.
                    synchronized(proxyOwnerLock) {
                        if (proxyOwner == "chain") {
                            ChainEngine.stopProxyCore()
                            proxyOwner = null
                        }
                    }
                    if (OvpnEngine.activeName == p.name) OvpnEngine.disconnect()
                    ChainEngine.setRunning(appCtx, null)
                    if (engineChainName == p.name) engineChainName = null
                }
            } catch (_: Exception) {}
        }
        val hopTypeFa = when (hop.type) { "wireguard" -> "وایرگارد"; "emergency" -> "اضطراری"; else -> "v2ray" }
        val hopTypeEn = when (hop.type) { "wireguard" -> "WireGuard"; "emergency" -> "Emergency"; else -> "v2ray" }
        val tcpForced = try { AdaptiveEngine.ovpnForceTcp(p.ovpn) != null } catch (_: Exception) { false }
        val chainNoteFa = if (tcpForced) " + TCP اجباری" else ""
        val chainNoteEn = if (tcpForced) " + forced TCP" else ""
        log("موتور: روش‌های مستقیم برای «${p.name}» جواب نداد؛ آخرین سنگر: زنجیره با «${hop.label}» ($hopTypeFa$chainNoteFa).",
            "Engine: direct methods for \"${p.name}\" failed; last resort: chaining with \"${hop.label}\" ($hopTypeEn$chainNoteEn).")
        if (tcpForced) {
            log("موتور: پروفایل «${p.name}» UDP بود؛ برای زنجیره به TCP تبدیل می‌شود چون پروکسی HTTP فقط TCP می‌برد.",
                "Engine: profile \"${p.name}\" was UDP; it is forced onto TCP for chaining because the HTTP proxy carries TCP only.")
        }
        val proxyConfig = try { ChainEngine.proxyConfig(appCtx, hop) } catch (_: Exception) { null }
        if (proxyConfig == null) {
            log("موتور: ساخت پروکسی زنجیره برای «${p.name}» ناموفق بود.",
                "Engine: building the chain proxy for \"${p.name}\" failed.")
            return false
        }
        if (aborted()) { cleanup(); return false }
        // 2.23: the chain owns the loopback core
        // from here (evicting a dots sweep's tunnel;
        // the sweep degrades to grey, never breaks
        // the chain).
        synchronized(proxyOwnerLock) { proxyOwner = "chain" }
        Thread { try { ChainEngine.startProxyCore(appCtx, proxyConfig) } catch (_: Exception) {} }.start()
        var proxyUp = false
        val proxyDeadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < proxyDeadline) {
            if (aborted()) { cleanup(); return false }
            try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress("127.0.0.1", ChainEngine.PROXY_HTTP_PORT), 300)
                s.close(); proxyUp = true; break
            } catch (_: Exception) {}
            try { Thread.sleep(200) } catch (_: Exception) {}
        }
        if (!proxyUp) {
            log("موتور: پروکسی داخلی زنجیره برای «${p.name}» بالا نیامد.",
                "Engine: the chain's internal proxy for \"${p.name}\" did not come up.")
            cleanup(); return false
        }
        if (aborted()) { cleanup(); return false }
        engineChainName = p.name
        val started = java.util.concurrent.CountDownLatch(1)
        // OvpnEngine.connect must run on the UI
        // thread (it may request VPN permission,
        // requestCode 701, handled by this page).
        runOnUiThread {
            try {
                OvpnEngine.chainProxyPort = ChainEngine.PROXY_HTTP_PORT
                OvpnEngine.chainProxyActive = true
                ChainEngine.setRunning(appCtx, "ovpn")
                OvpnEngine.connect(this@OpenVpnActivity, p, preserveChainProxy = true) {}
            } catch (_: Exception) {} finally { started.countDown() }
        }
        try { started.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        refreshOvpnUi()
        when (waitOvpnRung(p.name, 40000L) { aborted() }) {
            0 -> {
                var stable = true
                var abortHit = false
                val holdEnd = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < holdEnd) {
                    if (aborted()) { abortHit = true; break }
                    if (!OvpnEngine.isConnected || OvpnEngine.activeName != p.name) { stable = false; break }
                    try { Thread.sleep(200) } catch (_: Exception) {}
                }
                if (abortHit) { cleanup(); return false }
                if (!stable) {
                    log("موتور: زنجیره برای «${p.name}» لحظه‌ای وصل شد ولی پایدار نماند.",
                        "Engine: chaining for \"${p.name}\" connected briefly but did not hold.")
                    cleanup(); return false
                }
                AdaptiveEngine.learnMethod(appCtx, AdaptiveEngine.S_OVPN, p.name, "chain")
                AppLog.append(appCtx, "openvpn",
                    "«${p.name}» با روش موتور «زنجیره» (${hop.label}) وصل شد؛ موتور هوشمند: روشن.",
                    "“${p.name}” connected with engine method “chain” (${hop.label}); smart engine ON.")
                // 2.45: chain sessions disallow the
                // app's own traffic, so no live probe
                // is possible — record the reason.
                ConnVerify.skip(appCtx, "openvpn", "openvpn", p.name,
                    "زنجیره اوپن‌وی‌پی‌ان است؛ اندازه‌گیری زنده از داخل برنامه ممکن نیست",
                    "OpenVPN chain; live measurement from inside the app is not possible")
                log("موتور: «${p.name}» با روش «زنجیره» واقعاً وصل شد — ${methodExplainFa("chain", hop.label)}.",
                    "Engine: \"${p.name}\" really connected with \"chaining\" — ${methodExplainEn("chain", hop.label)}.")
                return true
            }
            1 -> {
                val err = OvpnEngine.lastError ?: ("نامشخص (آخرین وضعیت هسته: " + (OvpnEngine.lastState ?: "?") + " " + (OvpnEngine.lastStateMsg ?: "") + ")").trim()
                log("موتور: زنجیره برای «${p.name}» جواب نداد: $err.",
                    "Engine: chaining for \"${p.name}\" did not work: $err.")
                cleanup(); return false
            }
            2 -> {
                log("موتور: زنجیره برای «${p.name}» در ۴۰ ثانیه جواب نداد.",
                    "Engine: chaining for \"${p.name}\" gave no answer in 40 seconds.")
                cleanup(); return false
            }
            else -> { cleanup(); return false }
        }
    }

    // ---------- 2.23 health dots ----------
    private fun dotColor(state: Int): Int = when (state) {
        1 -> Color.parseColor("#43A047")
        -1 -> Color.parseColor("#E57373")
        else -> Color.parseColor("#BDBDBD")
    }

    /** 2.60: last REAL dial per profile — a real
     *  ovpn3 connect on this line, not a port
     *  probe. The upper dot alone cannot answer
     *  "does this profile connect": UDP servers
     *  stay silent by design (grey), and a TCP
     *  answer does not prove the TLS/auth dial.
     *  Only direct attempts from this page are
     *  recorded (chains never touch this). */
    private fun recordDial(name: String, ok: Boolean, err: String?) {
        if (name.isBlank()) return
        try {
            val e = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit()
            e.putBoolean("ovpn_dial_ok_$name", ok)
            e.putLong("ovpn_dial_time_$name", System.currentTimeMillis())
            if (ok) e.remove("ovpn_dial_err_$name") else e.putString("ovpn_dial_err_$name", err ?: "")
            e.apply()
        } catch (_: Exception) {}
    }

    private fun dialLineText(name: String): Pair<String, String> {
        try {
            val p = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
            if (!p.contains("ovpn_dial_time_$name")) {
                return L("هنوز به‌صورت مستقیم وصل نشده", "Never connected directly") to "#9E9E9E"
            }
            val ok = p.getBoolean("ovpn_dial_ok_$name", false)
            val t = p.getLong("ovpn_dial_time_$name", 0)
            val cal = java.util.Calendar.getInstance()
            val now = java.util.Calendar.getInstance()
            cal.timeInMillis = t
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
            val whenFa = if (cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) "امروز، $hm"
                else "${cal.get(java.util.Calendar.DAY_OF_MONTH)}/${cal.get(java.util.Calendar.MONTH) + 1} $hm"
            val whenEn = if (cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) "today, $hm"
                else "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)} $hm"
            return if (ok) {
                L("آخرین وصل واقعی: وصل شد ($whenFa)", "Last real connect: succeeded ($whenEn)") to "#2E7D32"
            } else {
                L("آخرین وصل واقعی: ناموفق ($whenFa)", "Last real connect: failed ($whenEn)") to "#C62828"
            }
        } catch (_: Exception) {
            return L("هنوز به‌صورت مستقیم وصل نشده", "Never connected directly") to "#9E9E9E"
        }
    }

    /** Repaints both dots of every row from the
     *  maps (UI thread only). A connected profile
     *  is green on both by definition. */
    private fun paintDots() {
        try {
            for ((name, v) in localDotViews) {
                val c = if (OvpnEngine.isConnected && OvpnEngine.activeName == name)
                    Color.parseColor("#43A047") else dotColor(localHealth[name] ?: 0)
                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(c)
            }
            for ((name, v) in remoteDotViews) {
                val c = if (OvpnEngine.isConnected && OvpnEngine.activeName == name)
                    Color.parseColor("#43A047") else dotColor(remoteHealth[name] ?: 0)
                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(c)
            }
        } catch (_: Exception) {}
    }

    /** Healthy hop1 candidates across sections
     *  (2.23), best first. v2ray configs are ping-
     *  filtered in parallel (12s deadline, up to 2,
     *  selected first); WireGuard tunnels are DNS-
     *  filtered (up to 2 more). Emergency is added
     *  only when withEmergency: a fresh emergency
     *  fetch + ping race is fine for the engine's
     *  last resort, but not for a page-entry sweep.
     *  A candidate that cannot answer a health check
     *  is skipped outright — chaining through a dead
     *  hop wastes a whole attempt. Hydra is not
     *  a candidate here either: its desync shapes
     *  are applied to a full Hydra config by a
     *  tasting process the chain proxy does not
     *  run, so a shapeless hop labelled "Hydra"
     *  would be fake. */
    private fun healthySectionHops(ctx: Context, withEmergency: Boolean, exhaustive: Boolean = false): List<ChainEngine.Hop> {
        val out = ArrayList<ChainEngine.Hop>()
        try { CoreInit.init(ctx) } catch (_: Exception) {}
        try {
            val sp = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
            val sel = sp.getString("selected", null)?.takeIf { it.isNotBlank() }
            fun usable(l: String): Boolean {
                return l.contains("://") && !XraySpeedtest.isGroup(l) && !XraySpeedtest.isChain(l)
            }
            val all = ArrayList<String>()
            if (sel != null && usable(sel)) all += sel
            for (l in (sp.getString("servers", "") ?: "").lines().map { it.trim() }) {
                if (usable(l) && l !in all) all += l
            }
            if (all.isNotEmpty()) {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                val healthy = java.util.Collections.synchronizedList(ArrayList<String>())
                val done = java.util.concurrent.CountDownLatch(all.size)
                for (l in all) {
                    pool.submit {
                        try { if (XraySpeedtest.measureOne(l) >= 0) healthy += l } catch (_: Exception) {} finally { done.countDown() }
                    }
                }
                done.await(if (exhaustive) 90 else 12, java.util.concurrent.TimeUnit.SECONDS)
                try { pool.shutdownNow() } catch (_: Exception) {}
                for (l in all) {
                    if (l in healthy) {
                        out += ChainEngine.Hop("v2ray", l, XraySpeedtest.displayName(l))
                        if (!exhaustive && out.size >= 2) break
                    }
                }
            }
        } catch (_: Exception) {}
        if (exhaustive || out.size < 4) {
            try {
                val arr = org.json.JSONArray(ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                    .getString("wg_list", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    if (!exhaustive && out.size >= 4) break
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    val conf = o.optString("conf")
                    var endpoint = ""
                    var section = ""
                    for (raw in conf.lines()) {
                        val line = raw.trim()
                        if (line.startsWith("[")) { section = line; continue }
                        if (section.contains("Peer") && line.startsWith("Endpoint")) {
                            endpoint = line.substringAfter("=", "").trim()
                        }
                    }
                    if (endpoint.isBlank()) continue
                    val host = endpoint.substringBeforeLast(":").trim()
                    if (host.isBlank()) continue
                    val ok = try { java.net.InetAddress.getByName(host); true } catch (_: Exception) { false }
                    if (ok) out += ChainEngine.Hop("wireguard", name, name)
                }
            } catch (_: Exception) {}
        }
        if (withEmergency) {
            out += ChainEngine.Hop("emergency", "", L("اضطراری (بهترین سرور)", "Emergency (best server)"))
        }
        return out
    }

    /** Page-entry health sweep (2.23). Phase 1
     *  paints the UPPER dots with direct probes
     *  from this line. Phase 2 paints the LOWER
     *  dots with probes through one healthy hop1
     *  tunnel, i.e. from outside Iran. Stops dead
     *  on page exit (gen) or on a newer sweep. */
    private fun startHealthSweep() {
        val gen = ++healthSweepGen
        val snap = profiles.map { it.name to it.ovpn }
        if (snap.isEmpty()) return
        Thread {
            try {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                val done = java.util.concurrent.CountDownLatch(snap.size)
                for ((name, text) in snap) {
                    pool.submit {
                        try {
                            if (healthSweepGen != gen) return@submit
                            val trulyConnected = OvpnEngine.isConnected && OvpnEngine.activeName == name
                            val (state, code) = when {
                                trulyConnected -> 1 to "connected"
                                anyVpnUp() -> 0 to "vpn_up"
                                else -> dotLocalResult(text)
                            }
                            localHealth[name] = state
                            localResultCode[name] = code
                            runOnUiThread { paintDots() }
                        } catch (_: Exception) {} finally { done.countDown() }
                    }
                }
                done.await(30, java.util.concurrent.TimeUnit.SECONDS)
                try { pool.shutdownNow() } catch (_: Exception) {}
                if (healthSweepGen == gen) runRemoteSweep(gen, snap)
            } catch (_: Exception) {}
        }.start()
    }

    private fun runRemoteSweep(gen: Int, snap: List<Pair<String, String>>) {
        val endpoints = LinkedHashMap<String, OvpEndpoint>()
        val epsOfProfile = HashMap<String, List<String>>()
        for ((name, text) in snap) {
            val keys = ArrayList<String>()
            for (ep in ovpnEndpoints(text)) {
                val key = "${ep.proto}://${ep.host}:${ep.port}"
                endpoints[key] = ep
                keys += key
            }
            if (keys.isNotEmpty()) epsOfProfile[name] = keys
        }
        if (endpoints.isEmpty()) {
            for ((name, _) in snap) remoteResultCode[name] = "no_endpoint"
            return
        }
        // A running chain (user or engine) owns the
        // loopback core: never touch it from here.
        if (engineChainName != null) {
            for ((name, _) in snap) remoteResultCode[name] = "chain_busy"
            return
        }
        try {
            if (ChainEngine.isRunning(this)) {
                for ((name, _) in snap) remoteResultCode[name] = "chain_busy"
                return
            }
        } catch (_: Exception) {}
        synchronized(proxyOwnerLock) {
            // A "chain" claim with no live chain is
            // stale (e.g. the chain was cut from the
            // widget, which bypasses the page): the
            // dots may reclaim the core then.
            val chainGone = proxyOwner == "chain" && engineChainName == null &&
                try { !ChainEngine.isRunning(this) } catch (_: Exception) { true }
            if (proxyOwner != null && !chainGone) {
                for ((name, _) in snap) remoteResultCode[name] = "chain_busy"
                return
            }
            proxyOwner = "dots"
        }
        var tunnelOk = false
        try {
            val hops = healthySectionHops(this, false)
            for (hop in hops) {
                if (healthSweepGen != gen) return
                val cfg = try { ChainEngine.proxyConfig(this, hop) } catch (_: Exception) { null } ?: continue
                Thread { try { ChainEngine.startProxyCore(this, cfg) } catch (_: Exception) {} }.start()
                var up = false
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (healthSweepGen != gen) return
                    try {
                        val s = java.net.Socket()
                        s.connect(java.net.InetSocketAddress("127.0.0.1", ChainEngine.PROXY_SOCKS_PORT), 300)
                        s.close(); up = true; break
                    } catch (_: Exception) {}
                    try { Thread.sleep(200) } catch (_: Exception) {}
                }
                if (!up) { try { ChainEngine.stopProxyCore() } catch (_: Exception) {}; continue }
                // Trust the tunnel only after it
                // provably carries traffic.
                tunnelOk = socksTcpAlive("1.1.1.1", 443, 3000)
                if (!tunnelOk) { try { ChainEngine.stopProxyCore() } catch (_: Exception) {}; continue }
                break
            }
            if (!tunnelOk) {
                for ((name, _) in snap) remoteResultCode[name] = "no_tunnel"
                return
            }
            val verdicts = HashMap<String, Int>()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            val done = java.util.concurrent.CountDownLatch(endpoints.size)
            for ((key, ep) in endpoints) {
                pool.submit {
                    try {
                        if (healthSweepGen != gen) return@submit
                        verdicts[key] = if (ep.proto == "tcp") {
                            if (socksTcpAlive(ep.host, ep.port, 4000)) 1 else -1
                        } else {
                            if (socksUdpAnswered(ep.host, ep.port)) 1 else 0
                        }
                    } catch (_: Exception) {} finally { done.countDown() }
                }
            }
            done.await(60, java.util.concurrent.TimeUnit.SECONDS)
            try { pool.shutdownNow() } catch (_: Exception) {}
            if (healthSweepGen != gen) return
            for ((name, keys) in epsOfProfile) {
                val vs = keys.mapNotNull { verdicts[it] }
                if (vs.isEmpty()) continue
                val state = when {
                    vs.any { it == 1 } -> 1
                    vs.any { it == -1 } -> -1
                    else -> 0
                }
                remoteHealth[name] = state
                remoteResultCode[name] = when (state) {
                    1 -> "ok"
                    -1 -> "dead"
                    else -> "udp_silent"
                }
            }
            runOnUiThread { paintDots() }
        } finally {
            // Release only our own claim: if the
            // engine chain evicted us meanwhile, its
            // lifecycle owns the core, not the dots.
            synchronized(proxyOwnerLock) {
                if (proxyOwner == "dots") {
                    try { ChainEngine.stopProxyCore() } catch (_: Exception) {}
                    proxyOwner = null
                }
            }
        }
    }

    /** TCP connect through the loopback SOCKS
     *  tunnel (the hop1 exit resolves the host, so
     *  the verdict is the exit's, not this line's). */
    private fun socksTcpAlive(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", ChainEngine.PROXY_SOCKS_PORT))
            val s = java.net.Socket(proxy)
            try { s.connect(java.net.InetSocketAddress(host, port), timeoutMs); true } finally { try { s.close() } catch (_: Exception) {} }
        } catch (_: Exception) { false }
    }

    /** OpenVPN reset through the SOCKS tunnel's UDP
     *  relay (RFC 1928 UDP ASSOCIATE). Any reply
     *  means the server is alive from the tunnel
     *  exit; silence is inconclusive (grey). */
    private fun socksUdpAnswered(host: String, port: Int): Boolean {
        return try {
            val ctrl = java.net.Socket()
            ctrl.connect(java.net.InetSocketAddress("127.0.0.1", ChainEngine.PROXY_SOCKS_PORT), 3000)
            ctrl.soTimeout = 5000
            val out = ctrl.getOutputStream()
            val inp = ctrl.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val greet = ByteArray(2)
            if (readFully(inp, greet) < 2 || greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
                try { ctrl.close() } catch (_: Exception) {}
                return false
            }
            out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            val rep = ByteArray(10)
            if (readFully(inp, rep) < 10 || rep[1] != 0x00.toByte()) {
                try { ctrl.close() } catch (_: Exception) {}
                return false
            }
            // Reply ATYP 1 (IPv4) is what Xray's
            // relay returns; anything else is a fail.
            if (rep[3] != 0x01.toByte()) {
                try { ctrl.close() } catch (_: Exception) {}
                return false
            }
            val relayPort = ((rep[8].toInt() and 0xFF) shl 8) or (rep[9].toInt() and 0xFF)
            val relayIp = "${rep[4].toInt() and 0xFF}.${rep[5].toInt() and 0xFF}.${rep[6].toInt() and 0xFF}.${rep[7].toInt() and 0xFF}"
            val udp = java.net.DatagramSocket()
            udp.soTimeout = 500
            val relay = java.net.InetSocketAddress(relayIp, relayPort)
            val sess = ByteArray(8)
            java.util.Random().nextBytes(sess)
            val payload = ByteArray(13)
            payload[0] = 0x38.toByte()
            System.arraycopy(sess, 0, payload, 1, 8)
            val pkt: ByteArray = if (host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
                val parts = host.split(".").map { it.toIntOrNull() ?: 0 }
                val b = ByteArray(4 + 4 + 2 + payload.size)
                b[3] = 0x01.toByte()
                for (i in 0 until 4) b[4 + i] = parts[i].toByte()
                b[8] = ((port shr 8) and 0xFF).toByte()
                b[9] = (port and 0xFF).toByte()
                System.arraycopy(payload, 0, b, 10, payload.size)
                b
            } else {
                val hb = host.toByteArray(Charsets.UTF_8)
                val b = ByteArray(4 + 1 + hb.size + 2 + payload.size)
                b[3] = 0x03.toByte()
                b[4] = hb.size.toByte()
                System.arraycopy(hb, 0, b, 5, hb.size)
                val po = 5 + hb.size
                b[po] = ((port shr 8) and 0xFF).toByte()
                b[po + 1] = (port and 0xFF).toByte()
                System.arraycopy(payload, 0, b, po + 2, payload.size)
                b
            }
            val start = System.currentTimeMillis()
            val deadline = start + 4000
            var sent = 0
            var alive = false
            while (System.currentTimeMillis() < deadline && !alive) {
                val now = System.currentTimeMillis()
                if (sent < 3 && now - start >= sent * 1300L) {
                    try { udp.send(java.net.DatagramPacket(pkt, pkt.size, relay)) } catch (_: Exception) {}
                    sent++
                }
                try {
                    val buf = ByteArray(512)
                    udp.receive(java.net.DatagramPacket(buf, buf.size))
                    alive = true
                } catch (_: java.net.SocketTimeoutException) {}
            }
            try { udp.close() } catch (_: Exception) {}
            try { ctrl.close() } catch (_: Exception) {}
            alive
        } catch (_: Exception) { false }
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = try { inp.read(buf, off, buf.size - off) } catch (_: Exception) { -1 }
            if (n <= 0) break
            off += n
        }
        return off
    }

    // ---------- 2.24 precise dot tests ----------
    /** LIVE "is any tunnel up" check (2.24). The
     *  old check trusted the wg_active PREF, which
     *  can go stale (app killed while WG was up)
     *  and then grey the dots forever. Engine
     *  volatile flags plus the active network's
     *  TRANSPORT_VPN capability are live for every
     *  backend, prefs included. */
    private fun anyVpnUp(): Boolean {
        return try {
            if (VpnClientService.isRunning || OvpnEngine.isConnected || OvpnEngine.isConnecting) return true
            try { if (ChainEngine.isRunning(this)) return true } catch (_: Exception) {}
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) { false }
    }

    /** ALL remotes of a profile (2.24), each with
     *  its own port/proto; the directive proto is
     *  the fallback per line (directives may come
     *  after the remote lines, hence two passes).
     *  The old first-remote-only parse painted
     *  multi-remote profiles wrong when the first
     *  remote was dead. */
    private fun ovpnEndpoints(text: String): List<OvpEndpoint> {
        val out = ArrayList<OvpEndpoint>()
        var directiveProto: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t == "proto udp" || t.startsWith("proto udp ")) directiveProto = "udp"
            else if (t.startsWith("proto tcp")) directiveProto = "tcp"
        }
        for (line in text.lines()) {
            val t = line.trim()
            if (!t.startsWith("remote ")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val host = parts[1]
            val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1194 else 1194
            var proto: String? = null
            if (parts.size >= 4) {
                val pr = parts[3].lowercase()
                if (pr.startsWith("tcp")) proto = "tcp" else if (pr.startsWith("udp")) proto = "udp"
            }
            out += OvpEndpoint(host, port, proto ?: directiveProto ?: "udp")
        }
        return out
    }

    /** One endpoint's direct verdict (2.24):
     *  0 = answered, 1 = TCP reliably dead,
     *  3 = UDP port closed (ICMP unreachable),
     *  -1 = unresolvable, 2 = UDP silent
     *  (inconclusive). Background threads only. */
    private fun probeDotEndpoint(ep: OvpEndpoint): Int {
        val isIp = ep.host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))
        val hosts: List<String> = if (isIp) listOf(ep.host) else {
            val sys = try { AdaptiveEngine.resolveIps(ep.host) } catch (_: Exception) { emptyList() }
            if (sys.isNotEmpty()) sys.take(2)
            else try { AdaptiveEngine.resolveIpsDoh(ep.host) } catch (_: Exception) { emptyList() }.take(2)
        }
        if (hosts.isEmpty()) return -1
        return if (ep.proto == "tcp") {
            var ok = false
            for (h in hosts) {
                if (probeTcpAlive(h, ep.port)) { ok = true; break }
            }
            if (ok) 0 else 1
        } else {
            probeUdpDot(hosts[0], ep.port)
        }
    }

    /** UDP liveness with ICMP feedback (2.24): a
     *  CONNECTED socket turns an ICMP
     *  port-unreachable into PortUnreachable, i.e.
     *  proof the port is CLOSED (red) — distinct
     *  from silence (grey), which proves nothing
     *  for tls-auth servers. */
    private fun probeUdpDot(host: String, port: Int): Int {
        return try {
            val sock = java.net.DatagramSocket()
            try {
                sock.connect(java.net.InetSocketAddress(host, port))
                sock.soTimeout = 500
                val addr = sock.inetAddress ?: return 2
                val sess = ByteArray(8)
                java.util.Random().nextBytes(sess)
                val pkt = ByteArray(13)
                pkt[0] = 0x38.toByte()
                System.arraycopy(sess, 0, pkt, 1, 8)
                val start = System.currentTimeMillis()
                val deadline = start + 5000
                var sent = 0
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (sent < 3 && now - start >= sent * 1500L) {
                        try { sock.send(java.net.DatagramPacket(pkt, pkt.size, addr, port)) } catch (_: java.net.PortUnreachableException) { return 3 } catch (_: Exception) {}
                        sent++
                    }
                    try {
                        val buf = ByteArray(512)
                        sock.receive(java.net.DatagramPacket(buf, buf.size))
                        return 0
                    } catch (_: java.net.PortUnreachableException) { return 3 }
                    catch (_: java.net.SocketTimeoutException) {}
                }
                2
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { 2 }
    }

    /** Local dot verdict for one profile (2.24):
     *  green if ANY remote answers (the core
     *  itself falls back across remotes); else red
     *  if any is definitively dead/closed/
     *  unresolvable; else grey. This is the
     *  "will pressing connect on my line work"
     *  prediction, like the v2ray ping test. */
    private fun dotLocalResult(text: String): Pair<Int, String> {
        val eps = ovpnEndpoints(text)
        if (eps.isEmpty()) return 0 to "no_endpoint"
        val results = eps.map { probeDotEndpoint(it) }
        return when {
            results.any { it == 0 } -> 1 to "ok"
            results.any { it == 1 } -> -1 to "tcp_dead"
            results.any { it == 3 } -> -1 to "udp_closed"
            results.any { it == -1 } -> -1 to "unresolved"
            else -> 0 to "udp_silent"
        }
    }

    private fun localResultText(code: String): String = when (code) {
        "ok" -> L("جواب مستقیم دریافت شد؛ با همین نت به‌احتمال زیاد وصل می‌شود.", "Direct answer received; it will most likely connect on this line.")
        "tcp_dead" -> L("پورت از همین خط جواب نداد؛ با همین نت وصل نمی‌شود.", "The port gave no answer on this line; it will not connect on this line.")
        "udp_closed" -> L("پورت بسته است (سرور می‌گوید این پورت باز نیست).", "The port is closed (the server says this port is not open).")
        "unresolved" -> L("نشانی سرور از همین خط پیدا نشد.", "The server address did not resolve on this line.")
        "udp_silent" -> L("سرور UDP جواب نداد؛ برای سرورهای tls-auth این طبیعی است و چیزی را ثابت نمی‌کند — جواب قطعی با زدن دکمه اتصال معلوم می‌شود.", "The UDP server stayed silent; that is normal for tls-auth servers and proves nothing — pressing connect gives the final answer.")
        "vpn_up" -> L("تست نشد، چون الان یک VPN وصل است و تست از داخل تونل چیزی درباره خط خودت نمی‌گوید.", "Not tested: a VPN is up, and a probe through the tunnel says nothing about your own line.")
        "no_endpoint" -> L("نشانی سروری در فایل پیدا نشد.", "No server address was found in the file.")
        else -> L("هنوز تست نشده است.", "Not tested yet.")
    }

    private fun remoteResultText(code: String): String = when (code) {
        "ok" -> L("سرور از خروجی تونل جواب داد؛ سرور زنده است.", "The server answered from the tunnel exit; the server is alive.")
        "dead" -> L("سرور از خروجی تونلِ تأییدشده جواب نداد؛ سرور خاموش یا مسدود است.", "The server gave no answer through the verified tunnel; the server is down or blocked.")
        "udp_silent" -> L("سرور UDP از خروجی تونل هم ساکت ماند؛ چیزی را ثابت نمی‌کند.", "The UDP server stayed silent from the tunnel exit too; that proves nothing.")
        "no_tunnel" -> L("تونل سالمی برای تست بیرونی نبود؛ پس سلامت سرور نامشخص ماند.", "No healthy tunnel was available for the outside test, so server health stayed unknown.")
        "chain_busy" -> L("الان یک زنجیره در حال اجراست؛ تست بیرونی بعداً انجام می‌شود.", "A chain is running right now; the outside test will run later.")
        "no_endpoint" -> L("نشانی سروری در فایل پیدا نشد.", "No server address was found in the file.")
        else -> L("هنوز تست نشده است.", "Not tested yet.")
    }

    /** Tap the dots: plain-language reasons for
     *  both dots (2.24). No hosts, IPs or ports —
     *  only what each dot means and why it shows
     *  its colour. */
    private fun showDotInfo(name: String) {
        try {
            val d = resources.displayMetrics.density
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            }
            fun addLine(title: String, body: String) {
                box.addView(TextView(this).apply {
                    text = title; textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.BLACK)
                })
                box.addView(TextView(this).apply {
                    text = body; textSize = 13f
                    setTextColor(Color.parseColor("#455A64"))
                    setPadding(0, 0, 0, (12 * d).toInt())
                })
            }
            val connectedNow = OvpnEngine.isConnected && OvpnEngine.activeName == name
            val upper = if (connectedNow) L("وصل است", "Connected")
                else localResultText(localResultCode[name] ?: "not_tested")
            val lower = if (connectedNow) L("سرور زنده است (همین اتصال)", "Server alive (this connection)")
                else remoteResultText(remoteResultCode[name] ?: "not_tested")
            box.addView(TextView(this).apply {
                text = name; textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, (12 * d).toInt())
            })
            addLine(L("دایره بالایی — تست مستقیم با نت خودت (بدون موتور)", "Upper dot — direct test on your line (no engine)"), upper)
            addLine(L("دایره پایینی — سلامت سرور از خارج ایران", "Lower dot — server health from outside Iran"), lower)
            // 2.60: last real dial (a real ovpn3
            // connect, not a probe) with its reason
            // when it failed.
            val (dtxt, _) = dialLineText(name)
            val derr = try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_dial_err_$name", null) } catch (_: Exception) { null }
            val dbody = if (!derr.isNullOrBlank() && dtxt.contains(L("ناموفق", "failed"))) "$dtxt؛ علت: $derr" else dtxt
            addLine(L("آخرین وصل واقعی", "Last real connect"), dbody)
            box.addView(TextView(this).apply {
                text = L("دایره‌ها در هر ورود به این صفحه، و هر بار که خط آزاد شود، از نو تست می‌شوند. خاکستری یعنی «نامشخص»، نه «خراب».",
                    "The dots re-test on every entry to this page and whenever the line becomes idle. Grey means \"unknown\", not \"broken\".")
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(box)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                box.background = HydraUi.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 18f, this)
            }
            dlg.show()
        } catch (_: Exception) {}
    }

    /** OVPN guide (2.24): every option of this
     *  section, including both dots' semantics. */
    private fun showOvpnGuide() {
        try {
            val d = resources.displayMetrics.density
            val en = AppLanguage.isEnglish(this)
            fun sec(faT: String, faB: String, enT: String, enB: String): Pair<String, String> {
                return if (en) enT to enB else faT to faB
            }
            val sections = listOf(
                sec("دایره‌های سلامت",
                    "دو دایره کنار دکمه اتصال: بالایی تست مستقیم با نت خودت بدون موتور است (سبز = جواب می‌دهد، قرمز کمرنگ = مرده یا بسته، خاکستری = UDP ساکت یا VPN وصل است)؛ پایینی سلامت سرور از خارج ایران است که از خروجی یک تونل سالم تست می‌شود (سبز = زنده، قرمز کمرنگ = مرده، خاکستری = نامشخص). با لمس دایره‌ها دلیل هر رنگ را می‌بینی. تست‌ها در هر ورود به صفحه و هر بار که خط آزاد شود تکرار می‌شوند.",
                    "Health dots",
                    "Two dots beside the connect toggle: the upper one is a direct test on your own line without the engine (green = answers, faded red = dead or closed, grey = UDP silent or a VPN is up); the lower one is server health from outside Iran, tested through a healthy tunnel exit (green = alive, faded red = dead, grey = unknown). Tap the dots to see why each shows its colour. Tests repeat on every entry and whenever the line becomes idle."),
                sec("دکمه خاموش و روشن",
                    "لمس آن وصل و قطع می‌کند. وقتی موتور هوشمند روشن است، نردبان روش‌ها و در آخر زنجیره امتحان می‌شود؛ وقتی خاموش است، اتصال دقیقاً مثل قبل، یک اتصال مستقیم است.",
                    "Connect toggle",
                    "Tap it to connect or disconnect. While the smart engine is on, the method ladder runs and chaining is the last resort; while it is off, connecting is exactly as before — one direct attempt."),
                sec("دکمه +",
                    "وارد کردن فایل .ovpn. هر فایل یک پروفایل می‌شود و اسمش از اسم فایل می‌آید.",
                    "Add button",
                    "Import an .ovpn file. Each file becomes one profile, named after the file."),
                sec("علامت ℹ (تشخیص)",
                    "تشخیص فایل؛ فقط می‌گوید فایل چه بخش‌ها و گزینه‌هایی دارد و هیچ مقداری را نشان نمی‌دهد.",
                    "Diagnosis info",
                    "File diagnosis; it only says which blocks and options the file has and never shows any values."),
                sec("مداد ✎ و لمس اسم",
                    "مداد پروفایل را ویرایش می‌کند. لمس اسم پروفایل، اسم کامل را در پنجره کوچکی نشان می‌دهد.",
                    "Edit and name tap",
                    "The pencil edits the profile. Tapping the profile name shows the full name in a small popup."),
                sec("ضربدر ✕ (حذف)",
                    "حذف با پنجره تأیید انجام می‌شود، هیچ‌وقت فوری نیست؛ حذف پروفایلی که وصل است، اتصال را قطع می‌کند.",
                    "Delete",
                    "Deletion asks for confirmation and is never instant; deleting the connected profile disconnects it."),
                sec("موتور هوشمند",
                    "وقتی از صفحه اصلی روشن است، روش‌ها را به ترتیب امتحان می‌کند (IP جایگزین، IP مستقیم، DoH، پروتکل جایگزین، mssfix، اصلی) و در آخر از یک زنجیره سالم v2ray، وایرگارد یا اضطراری وصل می‌شود؛ دکمه گزارش، لاگ موتور را نشان می‌دهد. با خاموش شدن موتور، همه‌چیز به حالت عادی برمی‌گردد و زنجیره‌های موتور متوقف می‌شوند.",
                    "Smart engine",
                    "While it is on from Home, it tries the methods in order (alternate IPs, direct IP, DoH, protocol swap, mssfix, original) and finally chains through a healthy v2ray, WireGuard or Emergency hop; the report button shows the engine log. Turning it off restores normal behaviour and stops engine chains."),
                sec("آیکون لاگ",
                    "آیکون بالای صفحه، متن کامل لاگ‌ها را نشان می‌دهد.",
                    "Log icon",
                    "The icon at the top shows the full log text.")
            )
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            }
            box.addView(TextView(this).apply {
                text = L("راهنمای OpenVPN", "OpenVPN guide")
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, (14 * d).toInt())
            })
            for ((t, b) in sections) {
                box.addView(TextView(this).apply {
                    text = t; textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#1B365D"))
                    setPadding(0, (8 * d).toInt(), 0, (2 * d).toInt())
                })
                box.addView(TextView(this).apply {
                    text = b; textSize = 13f
                    setTextColor(Color.parseColor("#37474F"))
                    setPadding(0, 0, 0, (6 * d).toInt())
                })
            }
            val scroll = ScrollView(this)
            scroll.addView(box)
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(scroll)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                box.background = HydraUi.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 18f, this)
                val lp = win.attributes
                lp.width = (340 * d).toInt()
                lp.height = (560 * d).toInt()
                win.attributes = lp
            }
            dlg.show()
        } catch (_: Exception) {}
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, L("انتخاب فایل OpenVPN", "Choose an OpenVPN file")), 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            OvpnEngine.onPrepareResult(this, resultCode == RESULT_OK)
            refreshStatus()
            refreshList()
            return
        }
        if (requestCode == 700 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data ?: return
                var fileName: String? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = c.getString(idx)
                        }
                    }
                } catch (_: Exception) {}
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val parsed = OvpnStore.parse(text, fileName)
                if (parsed == null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(L("فایل نامعتبر", "Invalid file"))
                        .setMessage(L("این فایل پروفایل OpenVPN نیست؛ باید یک فایل .ovpn با خط remote باشد.", "This is not an OpenVPN profile; it must be an .ovpn file with a remote line."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                    return
                }
                if (profiles.any { it.name == parsed.name && it.hostname == parsed.hostname }) {
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("این پروفایل قبلاً اضافه شده است.", "This profile was already added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                    return
                }
                if (parsed.needsAuth() && !parsed.hasEmbeddedCredentials()) {
                    // Open the Imported Profile page, like
                    // the second screenshot: first two
                    // fields auto-filled, username and
                    // password to be entered.
                    OvpnStore.pendingIndex = -1
                    OvpnStore.pending = parsed
                    startActivity(Intent(this, OpenVpnImportActivity::class.java))
                } else {
                    profiles += parsed
                    OvpnStore.save(this, profiles)
                    refreshList()
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("پروفایل «", "Profile \"") + parsed.name + L("» اضافه شد.", "\" added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                }
            } catch (e: Exception) {
                android.app.AlertDialog.Builder(this)
                    .setMessage(L("خواندن فایل ناموفق: ", "Reading the file failed: ") + (e.message ?: ""))
                    .setPositiveButton(L("بستن", "Close"), null)
                    .show()
            }
        }
    }
}
