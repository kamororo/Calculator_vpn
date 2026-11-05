package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import go.Seq
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var subsContainer: LinearLayout
    private lateinit var addSubRow: LinearLayout
    private lateinit var toggleSubsBtn: Button
    private var subsVisible = false
    private lateinit var newSubF: EditText
    // Per-sub report: received count per sub, and which sub
    // each deduped config first came from (for healthy counts).
    private val subReceivedMap = mutableMapOf<String, Int>()
    private val subHealthyMap = mutableMapOf<String, Int>()
    private val subSourceMap = mutableMapOf<String, String>()
    // Custom name per sub link, and the set of subs the user
    // unchecked (default is selected; only unchecked subs are
    // stored, so old and new subs start out selected).
    private val subNamesMap = mutableMapOf<String, String>()
    private val subDeselected = mutableSetOf<String>()
    private lateinit var nameF: EditText
    // 2.52: no received/healthy boxes any more. Fetched
    // configs live in memory + prefs only (background),
    // and the healthy output is a read-only live view.
    private var fetchedConfigs: MutableList<String> = mutableListOf()
    private lateinit var copyBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var reportBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private var coreInitDone = false
    private var coreInitError: String? = null
    private var testing = false
    // Live poller for the foreground PingTestService: reads the
    // service's progress prefs every second while resumed, so the
    // page shows live progress even after leaving and returning.
    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false
    // on the last poll, and how many consecutive polls
    // saw no change at all.
    private var lastPollDone = -1
    private var lastPollHealthy = -1
    private var unchangedPolls = 0
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollPingProgress()
            pollHandler.postDelayed(this, 1000)
        }
    }
    // Healthy configs with their ORIGINAL names, kept so the
    // desired-name box can be applied or cleared at any time.
    private var lastHealthy: List<String> = emptyList()

    private fun prefs() = getSharedPreferences("renamer_subs", MODE_PRIVATE)

    /** Persist the fetched list, the desired name and
     *  lastHealthy, so returning to this page never clears
     *  them. Only a fresh selected-subs update replaces
     *  the fetched list, and only a fresh ping test
     *  replaces the healthy output. */
    private fun saveState() {
        try {
            val nm = try { nameF.text.toString() } catch (_: Exception) { "" }
            prefs().edit()
                .putString("fetched_list", fetchedConfigs.joinToString("\n"))
                .putString("desired_name", nm)
                .putString("last_healthy", lastHealthy.joinToString("\n"))
                .apply()
        } catch (_: Exception) {}
    }

    // 2.22: fillBoxes=false restores only the lists;
    // the heavy output fill is deferred by the
    // caller until after the page appears, so a page
    // holding thousands of configs opens instantly.
    private fun loadState(fillBoxes: Boolean = true) {
        try {
            val fetched = prefs().getString("fetched_list", "") ?: ""
            val box1Pref = prefs().getString("box1", "") ?: ""
            // The legacy box1 value is only a first-open
            // fallback when the new list is empty, and it
            // is cleared right after migrating so it can
            // never shadow the new list again.
            val useLegacy = fetched.isBlank() && box1Pref.isNotBlank()
            val lines = (if (useLegacy) box1Pref else fetched)
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            if (useLegacy) {
                try {
                    prefs().edit().putString("fetched_list", box1Pref)
                        .putString("box1", "").apply()
                                    } catch (_: Exception) {}
            }
            fetchedConfigs = lines.toMutableList()
            val nm = prefs().getString("desired_name", "") ?: ""
            val lh = prefs().getString("last_healthy", "") ?: ""
            val b2 = prefs().getString("box2", "") ?: ""
            if (nm.isNotBlank()) nameF.setText(nm)
            lastHealthy = (if (lh.isNotBlank()) lh else b2)
                .lines().map { it.trim() }.filter { it.isNotBlank() }
            if (fillBoxes) {
                refreshCopyButton()
            }
            if (fetchedConfigs.isNotEmpty() || lastHealthy.isNotEmpty()) {
                progressText.text = L("کانفیگ‌های قبلی بازیابی شدند؛ تا وقتی به‌روزرسانی نزنی، پاک نمی‌شوند.", "Previous configs were restored; they will not be deleted until you update.")
            }
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        try { saveState() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        val p = prefs()
        if (p.getBoolean("ping_running", false) && !p.getBoolean("ping_done", false)) {
            // A test started earlier is still running in the
            // foreground service: resume live polling, don't
            // restart the test.
            testing = true
            startBtn.isEnabled = false
            startPolling()
        } else if (p.getBoolean("ping_done", false) && !p.getBoolean("ping_applied", false)) {
            // The test finished while we were away: apply its
            // final result exactly once.
            applyFinishedTest()
        }
        // 2.62: the report button follows stored report
        // data even when nothing new finished.
        updateReportBtn()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    /** true when the ping foreground service is
     *  really alive. On API 26+ this only lists our own
     *  services, which is exactly what is needed here.
     *  Any doubt returns true, so a live-but-slow test
     *  is never reset by mistake. */
    private fun isPingServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(Int.MAX_VALUE)?.any {
                it.service.className == PingTestService::class.java.name
            } == true
        } catch (_: Exception) { true }
    }

    /** Reads the service's live progress prefs and updates the
     *  page: progress line AND box 2, which grows with every
     *  config the moment it is known healthy. */
    private fun pollPingProgress() {
        val p = prefs()
        if (p.getBoolean("ping_done", false)) {
            stopPolling()
            applyFinishedTest()
            return
        }
        if (!p.getBoolean("ping_running", false)) {
            // Stopped (stop button or system kill) without a
            // finished result.
            stopPolling()
            testing = false
            startBtn.isEnabled = true
            val msg = L("تست پینگ متوقف شد.", "Ping test stopped.")
            progressText.text = msg
            statusText.text = msg
            if (::progressBar.isInitialized) progressBar.visibility = View.GONE
            refreshCopyButton()
            return
        }
        val done = p.getInt("ping_progress_done", 0)
        val total = p.getInt("ping_progress_total", 0)
        val healthy = p.getInt("ping_progress_healthy", 0)
        // nothing moved for several polls AND the service
        // is not actually running any more (killed
        // without onDestroy) -> reset the flag here, so
        // Start is usable again. A live-but-slow service
        // is never reset: the running check guards it.
        if (done == lastPollDone && healthy == lastPollHealthy) {
            unchangedPolls++
        } else {
            unchangedPolls = 0
            lastPollDone = done
            lastPollHealthy = healthy
        }
        if (unchangedPolls >= 5 && !isPingServiceRunning()) {
            unchangedPolls = 0
            try {
                p.edit().putBoolean("ping_running", false).apply()
                            } catch (_: Exception) {}
            stopPolling()
            testing = false
            startBtn.isEnabled = true
            val stuckMsg = L("تست پینگ متوقف شد (سرویس قطع شده بود)؛ دوباره استارت بزن.", "Ping test stopped (its service was gone); start again.")
            progressText.text = stuckMsg
            statusText.text = stuckMsg
            if (::progressBar.isInitialized) progressBar.visibility = View.GONE
            refreshCopyButton()
            return
        }
        progressText.text = L("در حال تست پینگ... $done از $total تست شد، $healthy سالم", "Testing ping... $done of $total tested, $healthy healthy")
        if (::progressBar.isInitialized) {
            // 2.52: live progress bar under the status line.
            progressBar.visibility = View.VISIBLE
            progressBar.max = if (total > 0) total else 1
            progressBar.progress = done
        }
        // 2.52: the output view grows with every config
        // the moment it is known healthy, so it can be
        // copied at once without waiting for the end.
        refreshCopyButton()
    }

    /** Final step shared by the poller and onResume: take the
     *  service's sorted final list, refresh the per-sub report
     *  (always, not only when the subs list is visible), apply
     *  the desired name, and show the one-line summary.
     *  2.62: the page keeps ONLY the summary line — the
     *  per-sub block no longer lands on the page at all
     *  (it garbled the layout); it lives in the report
     *  dialog behind "مشاهده گزارش تست". */
    private fun applyFinishedTest() {
        val p = prefs()
        val lh = (p.getString("last_healthy", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        lastHealthy = lh
        loadSubStats()
        refreshSubsList()
        if (::progressBar.isInitialized) progressBar.visibility = View.GONE
        refreshCopyButton()
        saveState()
        p.edit().putBoolean("ping_applied", true).apply()
        testing = false
        startBtn.isEnabled = true
        val err = p.getString("ping_error", "") ?: ""
        val total = p.getInt("ping_progress_total", 0)
        progressText.text = if (err.isNotBlank()) {
            L("تست پینگ با خطا تمام شد: $err", "Ping test ended with an error: $err")
        } else {
            L("تست پینگ انجام شد: ${lh.size} سالم از $total کانفیگ.", "Ping test done: ${lh.size} healthy of $total configs.")
        }
        updateReportBtn()
    }

    /** The per-sub report dialog: clean rows that keep
     *  each direction separate — name (RTL), URL in
     *  its own LTR view, counts (RTL) — so no
     *  mixed-direction paragraph can garble, whatever
     *  the sub names or URLs are. */
    private fun showReportDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var any = false
        for (url in loadSubs()) {
            val rec = subReceivedMap[url] ?: 0
            if (rec <= 0) continue
            any = true
            val ok = subHealthyMap[url] ?: 0
            val name = subNamesMap[url]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFE0E0E0"), 18f, this@SubscriptionActivity)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
            }
            if (name != null && name != url) {
                card.addView(TextView(this).apply {
                    text = name; textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                card.addView(TextView(this).apply {
                    text = url; textSize = 12f
                    setTextColor(Color.GRAY)
                    textDirection = View.TEXT_DIRECTION_LTR
                })
            } else {
                card.addView(TextView(this).apply {
                    text = url; textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textDirection = View.TEXT_DIRECTION_LTR
                })
            }
            card.addView(TextView(this).apply {
                text = L("$ok سالم از $rec", "$ok healthy of $rec"); textSize = 13f
                setTextColor(Color.parseColor("#0B6E4F"))
            })
            col.addView(card)
        }
        if (!any) {
            col.addView(TextView(this).apply {
                text = L("هنوز گزارش تستی ثبت نشده است.", "No test report recorded yet.")
                setTextColor(Color.GRAY)
            })
        }
        val scroll = ScrollView(this).apply { addView(col) }
        AlertDialog.Builder(this)
            .setTitle(L("گزارش تست پینگ", "Ping test report"))
            .setView(scroll)
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun updateReportBtn() {
        if (!::reportBtn.isInitialized) return
        val hasReport = loadSubs().any { (subReceivedMap[it] ?: 0) > 0 }
        reportBtn.visibility = if (hasReport) View.VISIBLE else View.GONE
    }
    private fun loadSubs(): MutableList<String> {
        val raw = prefs().getString("subs", "") ?: ""
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    }
    private fun saveSubs(list: List<String>) {
        prefs().edit().putString("subs", list.joinToString("\n")).apply()
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, dp(5), 0, dp(5)) }
        setBackgroundColor(Color.parseColor("#DDDDDD"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(root)

        // Top bar: title + circular glass ⋮ menu button.
        // 2.62: the row is MATCH_PARENT now — it used
        // to wrap its content, which glued the ⋮ button
        // right against the title. The button is
        // smaller (34dp), tinted to the page's teal,
        // and held off the title by a marginStart gap
        // (direction-safe: the gap faces the title in
        // both RTL and LTR).
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topRow.addView(TextView(this).apply {
            text = L("اشتراک‌ها", "Subscriptions"); textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(Button(this).apply {
            text = "⋮"; textSize = 18f
            setTextColor(Color.parseColor("#FF0F766E"))
            isAllCaps = false
            background = HydraUi.glassCircleBg(Color.parseColor("#FFE8F5F3"), Color.parseColor("#FFBFBFBF"))
            elevation = 2f
            setPadding(0, 0, 0, 0)
            minimumWidth = 0; minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMarginStart(dp(10)) }
            setOnClickListener { showMenu() }
        })
        root.addView(topRow)
        // Progress line, right at the top, so a test or an
        // update is visible immediately without scrolling down.
        progressText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(progressText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        }
        root.addView(progressBar)
        root.addView(divider())

        // Saved subscription links: hidden by default, so a
        // long list never crowds the page.
        root.addView(TextView(this).apply {
            text = L("لینک‌های ساب ذخیره‌شده", "Saved subscription links"); textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
        })
        toggleSubsBtn = glassBtn(L("نمایش لینک‌های ساب", "Show subscription links")) {
            subsVisible = !subsVisible
            subsContainer.visibility = if (subsVisible) View.VISIBLE else View.GONE
            addSubRow.visibility = if (subsVisible) View.VISIBLE else View.GONE
            updateToggleText()
            if (subsVisible) refreshSubsList()
        }
        root.addView(toggleSubsBtn)
        subsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(subsContainer)
        loadSubStats()
        loadSubMeta()
        loadSubSource()
        updateToggleText()
        // 2.53: adding a sub lives INSIDE the saved-subs
        // section now — it appears below the list only
        // when the section is expanded, not on the
        // main page.
        addSubRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addSubRow.addView(TextView(this).apply {
            text = L("افزودن ساب جدید", "Add a new subscription"); textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, dp(8), 0, dp(2))
        })
        newSubF = EditText(this).apply {
            hint = L("لینک ساب جدید (https://...)", "New subscription link (https://...)")
            background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 18f, this@SubscriptionActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        addSubRow.addView(newSubF)
        addSubRow.addView(glassBtn(L("افزودن و ذخیره لینک ساب", "Add and save subscription link")) {
            val url = newSubF.text.toString().trim()
            if (url.isBlank() || !url.startsWith("http")) {
                statusText.text = L("لینک ساب معتبر نیست.", "Subscription link is not valid.")
                return@glassBtn
            }
            val subs = loadSubs()
            if (!subs.contains(url)) {
                subs += url; saveSubs(subs)
            }
            newSubF.setText("")
            refreshSubsList()
            statusText.text = L("لینک ساب ذخیره شد.", "Subscription link saved.")
        })
        root.addView(addSubRow)
        // 2.22: saved-sub rows build on first expand
        // (the toggle handler already refreshes then),
        // not eagerly here while the list is hidden.
        root.addView(divider())

        // 2.52: the update action, now selected-subs only.
        root.addView(glassBtn(L("بروزرسانی اشتراک‌های انتخاب‌شده", "Update selected subscriptions"), primary = true) { updateSelectedSubs() })
        root.addView(divider())

        // Ping row: start + stop, glass, modern padding.
        val pingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        startBtn = glassBtn(L("استارت تست پینگ", "Start ping test"), primary = true) { runPingTest() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }
        }
        pingRow.addView(startBtn)
        stopBtn = glassBtn(L("توقف تست پینگ", "Stop ping test")) {
            try {
                stopService(Intent(this@SubscriptionActivity, PingTestService::class.java))
            } catch (_: Exception) {}
            // flag itself instead of depending on the
            // service's onDestroy (which a system kill
            // can skip, leaving the test stuck forever).
            try {
                prefs().edit().putBoolean("ping_running", false).apply()
            } catch (_: Exception) {}
            testing = false
            startBtn.isEnabled = true
            try {
                            } catch (_: Exception) {}
            progressText.text = L("در حال توقف تست پینگ...", "Stopping ping test...")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pingRow.addView(stopBtn)
        root.addView(pingRow)

        // 2.62: the per-sub test report lives HERE, not
        // on the page: the old giant mixed-direction
        // block (Persian + URLs + digits in one
        // TextView) is what garbled the page. The page
        // keeps only the one-line summary; the full
        // report opens in a dialog whose rows keep
        // each direction separate, so it cannot garble.
        reportBtn = glassBtn(L("مشاهده گزارش تست", "View test report")) { showReportDialog() }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        root.addView(reportBtn)

        root.addView(divider())

        // Desired name for the output: one field, applied
        // AUTOMATICALLY (2.52). No apply button any more:
        // typing a name re-renders the output at once, and
        // clearing it brings back the original names.
        root.addView(TextView(this).apply {
            text = L("نام دلخواه برای خروجی", "Desired name for output"); textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, dp(4), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = L("خالی = اسم اصلی؛ یک نام = همان نام برای همه سالم‌ها، به‌صورت خودکار.", "Empty = original names; one name = that name for all healthy ones, automatically.")
            setTextColor(Color.GRAY); textSize = 13f
        })
        nameF = EditText(this).apply {
            hint = L("نام دلخواه (اختیاری)", "Desired name (optional)")
            background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 18f, this@SubscriptionActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    refreshCopyButton()
                    saveState()
                }
            })
        }
        root.addView(nameF)

        // 2.53: the output box is GONE (user order) —
        // the copy button below, with its live count,
        // is the whole output now.
        // Copy button, carrying the live healthy COUNT
        // (2.52): tapping it copies exactly what is
        // healthy at that moment, even mid-test.
        copyBtn = glassBtn(L("کپی کانفیگ‌های سالم (0)", "Copy healthy configs (0)"), primary = true) {
            val out = outputConfigs()
            if (out.isEmpty()) {
                statusText.text = L("چیزی برای کپی نیست.", "Nothing to copy.")
                return@glassBtn
            }
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("healthy", out.joinToString("\n")))
                val n = out.size
                statusText.text = if (prefs().getBoolean("ping_running", false))
                    L("$n کانفیگ سالمِ فعلاً پیدا شده کپی شد (تست هنوز تمام نشده).", "$n healthy configs found so far copied (test still running).")
                else
                    L("$n کانفیگ سالم کپی شد.", "$n healthy configs copied.")
                Toast.makeText(this@SubscriptionActivity, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
            }
        }
        // 2.62: the copy button sits a full section gap
        // below the desired-name box, like the other
        // buttons sit below their upper box — it used
        // to touch the box above it.
        copyBtn.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        root.addView(copyBtn)
        statusText = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)
        setContentView(scroll)
        scroll.fitsSystemWindows = true
        loadState(fillBoxes = false)
        // 2.22: fill the boxes right after the first
        // frame instead of before it — the page opens
        // instantly with many configs, then fills.
        scroll.post {
            try { refreshCopyButton() } catch (_: Exception) {}
        }
    }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 2.53: real gradient buttons, drawn (no
     *  photos): primary = teal gradient + white
     *  bold text; secondary = white-to-mist
     *  gradient + dark text. Flat fills, so no
     *  white gloss band on any of them.
     *  2.54: tighter — smaller text, padding and
     *  corners, so the page reads compact. */
    private fun glassBtn(text: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        background = android.graphics.drawable.GradientDrawable().apply {
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            if (primary) {
                colors = intArrayOf(Color.parseColor("#FF2BBBAD"), Color.parseColor("#FF0F766E"))
                setStroke(dp(1), Color.parseColor("#FF0B5E52"))
            } else {
                colors = intArrayOf(Color.parseColor("#FFFFFFFF"), Color.parseColor("#FFE8F5F3"))
                setStroke(dp(1), Color.parseColor("#FFBFBFBF"))
            }
            cornerRadius = 22f * resources.displayMetrics.density
        }
        textSize = if (primary) 15f else 14f
        setTextColor(if (primary) Color.WHITE else Color.parseColor("#FF1A1A1A"))
        if (primary) setTypeface(null, android.graphics.Typeface.BOLD)
        elevation = if (primary) 4f else 2f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }


    /** The exact configs the copy button will copy: the final
     *  sorted list (lowest ping first) once a test finished,
     *  live arrivals while one is running — all of them, with
     *  the desired name applied (2.6 behavior: no count limit). */
    private fun outputConfigs(): List<String> {
        val name = try { nameF.text.toString().trim() } catch (_: Exception) { "" }
        val base = currentHealthyBase()
        return if (name.isBlank()) base else base.map { renameConfig(it, name) }
    }

    /** The healthy list the output view and the copy
     *  button see right now (2.52). While a test runs,
     *  the live arrivals; after a clean finish, the
     *  final sorted list; after a mid-test stop, the
     *  partial live list when one exists. */
    private fun currentHealthyBase(): List<String> {
        val p = prefs()
        val live = (p.getString("ping_healthy_live", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        return when {
            p.getBoolean("ping_running", false) -> live
            p.getBoolean("ping_done", false) -> lastHealthy
            live.isNotEmpty() -> live
            else -> lastHealthy
        }
    }

    /** 2.53: the output BOX is gone — this only
     *  refreshes the copy button's live healthy
     *  count. The count needs no renaming, so it
     *  reads the base list directly. */
    private fun refreshCopyButton() {
        if (!::copyBtn.isInitialized) return
        val n = currentHealthyBase().size
        copyBtn.text = L("کپی کانفیگ‌های سالم ($n)", "Copy healthy configs ($n)")
    }

    private fun updateToggleText() {
        val n = loadSubs().size
        toggleSubsBtn.text = if (subsVisible) L("پنهان کردن لینک‌های ساب ($n تا)", "Hide subscription links ($n)")
                           else L("نمایش لینک‌های ساب ($n تا)", "Show subscription links ($n)")
    }

    private fun loadSubMeta() {
        try {
            val j = JSONObject(prefs().getString("sub_names", "{}") ?: "{}")
            for (key in j.keys()) {
                val v = j.optString(key, "")
                if (v.isNotBlank()) subNamesMap[key] = v
            }
        } catch (_: Exception) {}
        try {
            val j = org.json.JSONArray(prefs().getString("sub_deselected", "[]") ?: "[]")
            for (i in 0 until j.length()) subDeselected += j.optString(i)
        } catch (_: Exception) {}
    }

    private fun saveSubMeta() {
        try {
            val j = JSONObject()
            for ((k, v) in subNamesMap) j.put(k, v)
            prefs().edit().putString("sub_names", j.toString()).apply()
            val a = org.json.JSONArray()
            for (u in subDeselected) a.put(u)
            prefs().edit().putString("sub_deselected", a.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun isSubSelected(url: String) = !subDeselected.contains(url)

    private fun loadSubStats() {
        try {
            val j = JSONObject(prefs().getString("sub_stats", "{}") ?: "{}")
            for (key in j.keys()) {
                val o = j.optJSONObject(key) ?: continue
                subReceivedMap[key] = o.optInt("received", 0)
                subHealthyMap[key] = o.optInt("healthy", 0)
            }
        } catch (_: Exception) {}
    }

    /** Persist which sub each deduped config first came from, so
     *  per-sub healthy counts survive an app restart. Key =
     *  normalized dedup key, value = sub url. */
    private fun loadSubSource() {
        try {
            val j = JSONObject(prefs().getString("sub_source_map", "{}") ?: "{}")
            for (key in j.keys()) {
                val v = j.optString(key, "")
                if (v.isNotBlank()) subSourceMap[key] = v
            }
        } catch (_: Exception) {}
    }

    private fun saveSubSource() {
        try {
            val j = JSONObject()
            for ((k, v) in subSourceMap) j.put(k, v)
            prefs().edit().putString("sub_source_map", j.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun saveSubStats() {
        try {
            val j = JSONObject()
            for (url in loadSubs()) {
                val o = JSONObject()
                o.put("received", subReceivedMap[url] ?: 0)
                o.put("healthy", subHealthyMap[url] ?: 0)
                j.put(url, o)
            }
            prefs().edit().putString("sub_stats", j.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun refreshSubsList() {
        subsContainer.removeAllViews()
        updateToggleText()
        updateReportBtn()
        val subs = loadSubs()
        if (subs.isEmpty()) {
            subsContainer.addView(TextView(this).apply {
                text = L("هنوز لینک سابی ذخیره نشده است.", "No subscription link saved yet.")
                setTextColor(Color.GRAY)
            })
            return
        }
        for (url in subs) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFE0E0E0"), 18f, this@SubscriptionActivity)
                elevation = 1f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // Selection checkbox: the ping test only runs on
            // checked subs (see runPingTest).
            row.addView(CheckBox(this).apply {
                isChecked = isSubSelected(url)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) subDeselected.remove(url) else subDeselected += url
                    saveSubMeta()
                }
            })
            val label = subNamesMap[url]
            row.addView(TextView(this).apply {
                text = label ?: url; textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Edit button: give this sub a custom name.
            row.addView(Button(this).apply {
                text = "✎"
                background = HydraUi.glassCircleBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"))
                elevation = 4f
                setPadding(0, 0, 0, 0)
                minimumWidth = 0; minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) }
                setOnClickListener {
                    val input = EditText(this@SubscriptionActivity).apply {
                        setText(subNamesMap[url] ?: "")
                        hint = L("اسم دلخواه برای این ساب", "Custom name for this subscription")
                    }
                    AlertDialog.Builder(this@SubscriptionActivity)
                        .setTitle(L("ویرایش اسم ساب", "Edit subscription name"))
                        .setView(input)
                        .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                            val nm = input.text.toString().trim()
                            if (nm.isBlank()) subNamesMap.remove(url) else subNamesMap[url] = nm
                            saveSubMeta()
                            refreshSubsList()
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        .show()
                }
            })
            row.addView(Button(this).apply {
                text = "✕"
                background = HydraUi.glassCircleBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"))
                elevation = 4f
                setPadding(0, 0, 0, 0)
                minimumWidth = 0; minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) }
                setOnClickListener {
                    val cur = loadSubs(); cur.remove(url); saveSubs(cur)
                    subReceivedMap.remove(url); subHealthyMap.remove(url); saveSubStats()
                    subNamesMap.remove(url); subDeselected.remove(url); saveSubMeta()
                    refreshSubsList()
                }
            })
            col.addView(row)
            if (label != null) {
                col.addView(TextView(this).apply {
                    text = url; textSize = 12f
                    setTextColor(Color.GRAY)
                })
            }
            // Report in front of each sub link: received +
            // healthy counts (2.6 behavior — after a fresh
            // update the healthy count is the previous test's
            // until the next test replaces it).
            if (subReceivedMap.containsKey(url)) {
                val rec = subReceivedMap[url] ?: 0
                val ok = subHealthyMap[url] ?: 0
                col.addView(TextView(this).apply {
                    text = L("گزارش: $rec کانفیگ دریافت شد، $ok سالم", "Report: $rec configs received, $ok healthy")
                    textSize = 13f
                    setTextColor(Color.parseColor("#0B6E4F"))
                })
            }
            subsContainer.addView(col)
        }
    }

    private fun showMenu() {
        val items = arrayOf(L("بروزرسانی اشتراک‌های انتخاب‌شده", "Update selected subscriptions"), L("تست پینگ همه کانفیگ‌ها", "Ping test all configs"), L("خالی کردن همه", "Clear everything"))
        AlertDialog.Builder(this)
            .setTitle("⋮")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> updateSelectedSubs()
                    1 -> runPingTest()
                    2 -> AlertDialog.Builder(this)
                        .setTitle(L("خالی کردن همه", "Clear everything"))
                        .setMessage(L("کانفیگ‌های دریافت‌شده، نام دلخواه و خروجی سالم‌ها خالی شود؟", "Clear the received configs, the desired name and the healthy output?"))
                        .setPositiveButton(L("بله، خالی کن", "Yes, clear")) { _, _ ->
                            fetchedConfigs = mutableListOf()
                            nameF.setText("")
                            lastHealthy = emptyList()
                            // clears the ping state too, and
                            // stops a running test, so its
                            // finish cannot resurrect the
                            // cleared results afterwards.
                            try {
                                stopService(Intent(this@SubscriptionActivity, PingTestService::class.java))
                            } catch (_: Exception) {}
                            stopPolling()
                            testing = false
                            startBtn.isEnabled = true
                            unchangedPolls = 0
                            prefs().edit()
                                .putString("ping_healthy_live", "")
                                .putString("last_healthy", "")
                                .putString("box2", "")
                                .putBoolean("ping_running", false)
                                .putBoolean("ping_done", false)
                                .putBoolean("ping_applied", false)
                                .putInt("ping_progress_done", 0)
                                .putInt("ping_progress_total", 0)
                                .putInt("ping_progress_healthy", 0)
                                .apply()
                            try {
                                                            } catch (_: Exception) {}
                            saveState()
                            refreshCopyButton()
                            progressText.text = L("همه خالی شد.", "Everything cleared.")
                            statusText.text = L("همه خالی شد.", "Everything cleared.")
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        .show()
                }
            }
            .show()
    }

    /** Normalized dedup key. The old version was unreliable:
     *  vmess JSON toString() is key-order sensitive (the same
     *  server re-serialized in a different key order looked
     *  different), and non-vmess keys kept query parameters in
     *  their original order. Now vmess is canonicalized with
     *  keys SORTED and ps removed, and other protocols are
     *  normalized with query parameters sorted alphabetically,
     *  so only the name (#fragment) and parameter order are
     *  ignored. PingTestService.dedupKey must stay identical. */
    private fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
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
            val uri = java.net.URI(cfg.substringBefore("#"))
            val sortedQuery = if (uri.rawQuery.isNullOrBlank()) null else
                uri.rawQuery.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
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

    /** 2.52: update ONLY the checked subscriptions.
     *  Unchecked subs are left completely alone: their
     *  configs are not fetched, and their previous
     *  received/healthy report stays as it was. */
    private fun updateSelectedSubs() {
        val allSubs = loadSubs()
        val subs = allSubs.filter { isSubSelected(it) }
        if (subs.isEmpty()) {
            val msg = if (allSubs.isEmpty())
                L("اول یک لینک ساب ذخیره کن.", "Save a subscription link first.")
            else
                L("هیچ سابی انتخاب نشده؛ ساب‌های مورد نظر را تیک بزن.", "No subscription selected; tick the subscriptions you want.")
            progressText.text = msg
            statusText.text = msg
            return
        }
        progressText.text = L("در حال به‌روزرسانی اشتراک‌ها...", "Updating subscriptions...")
        statusText.text = L("در حال به‌روزرسانی اشتراک‌ها...", "Updating subscriptions...")
        Thread {
            val all = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var received = 0
            val perSub = mutableMapOf<String, Int>()
            val source = mutableMapOf<String, String>()
            for (url in subs) {
                var countForSub = 0
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 15000
                    var body = conn.inputStream.bufferedReader().readText().trim()
                    if (!body.contains("://")) {
                        try {
                            var b = body.replace('-', '+').replace('_', '/')
                            while (b.length % 4 != 0) b += "="
                            body = String(Base64.decode(b, Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {}
                    }
                    for (line in body.lines()) {
                        val l = line.trim()
                        if (l.isBlank() || !l.contains("://")) continue
                        received++
                        val key = dedupKey(l)
                        if (seen.add(key)) {
                            // Received count per sub counts UNIQUE
                            // configs only (first occurrence across
                            // all subs wins), so the report is honest
                            // about duplicates shared between subs.
                            countForSub++
                            all += l
                            source[key] = url
                        }
                    }
                } catch (e: Exception) {
                    // keep going with the other subscriptions
                }
                perSub[url] = countForSub
            }
            runOnUiThread {
                fetchedConfigs = all.toMutableList()
                saveState()
                // Selected subs only: received counts are
                // refreshed for them; unchecked subs keep
                // their previous report untouched.
                for ((u, c) in perSub) subReceivedMap[u] = c
                subSourceMap.clear(); subSourceMap.putAll(source)
                saveSubStats()
                saveSubSource()
                if (subsVisible) refreshSubsList()
                refreshCopyButton()
                val dup = received - all.size
                val msg = L("به‌روزرسانی انجام شد: $received کانفیگ دریافت شد", "Update done: $received configs received") +
                    if (dup > 0) L("، $dup تکراری حذف شد، ${all.size} کانفیگ باقی ماند.", ", $dup duplicates removed, ${all.size} configs left.")
                    else L("، ${all.size} کانفیگ اضافه شد.", ", ${all.size} configs added.")
                progressText.text = msg
                statusText.text = msg
                AppLog.append(this, "subs",
                    "به‌روزرسانی اشتراک‌های انتخاب‌شده انجام شد: $received کانفیگ دریافت شد" + if (dup > 0) "، $dup تکراری حذف شد، ${all.size} کانفیگ باقی ماند." else "، ${all.size} کانفیگ اضافه شد.",
                    "Selected subscription update done: $received configs received" + if (dup > 0) ", $dup duplicates removed, ${all.size} configs left." else ", ${all.size} configs added.")
            }
        }.start()
    }

    private fun renameConfig(cfg: String, newName: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val j = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                j.put("ps", newName)
                val enc = Base64.encodeToString(
                    j.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP
                ).trimEnd('=')
                "vmess://$enc"
            } else {
                val base = cfg.substringBefore("#")
                val enc = java.net.URLEncoder.encode(newName, "UTF-8").replace("+", "%20")
                "$base#$enc"
            }
        } catch (_: Exception) { cfg }
    }

    private fun initCore(): Boolean {
        if (coreInitDone) return true
        val ok = CoreInit.init(applicationContext)
        if (ok) coreInitDone = true else coreInitError = CoreInit.lastError
        return ok
    }

    /** Exact v2rayNG real ping, per config, 16 in parallel.
     *  Progress is shown at the TOP of the page, live, after
     *  every finished config, so a long test never looks dead:
     *  "در حال تست پینگ... X از Y تست شد، Z سالم".
     *  At the end the same section says "تست پینگ انجام شد". */
    /** Ping test via the foreground PingTestService, so the
     *  test keeps running when the screen turns off or the user
     *  leaves the app. This method only prepares the list,
     *  starts the service, and starts the live poller; the
     *  service does the measuring and writes live progress to
     *  prefs, which the poller shows in the output view
     *  as each healthy config appears. */
    private fun runPingTest() {
        if (testing || prefs().getBoolean("ping_running", false)) {
            progressText.text = L("تست پینگ در حال انجام است؛ صبر کن.", "Ping test is running; please wait.")
            startPolling()
            return
        }
        // 2.52: no received box any more — the test runs
        // on the fetched list kept in memory + prefs.
        val rawConfigs = fetchedConfigs.map { it.trim() }.filter { it.isNotBlank() }
        if (rawConfigs.isEmpty()) {
            progressText.text = L("هنوز کانفیگی دریافت نشده؛ اول اشتراک‌ها را به‌روز کن.", "No configs received yet; update the subscriptions first.")
            statusText.text = L("هنوز کانفیگی دریافت نشده؛ اول اشتراک‌ها را به‌روز کن.", "No configs received yet; update the subscriptions first.")
            return
        }
        // Remove duplicates BEFORE pinging, with the normalized
        // key, so a config differing only by name or parameter
        // order is tested once, not once per duplicate.
        val seenKeys = mutableSetOf<String>()
        val configs = mutableListOf<String>()
        for (c in rawConfigs) {
            if (seenKeys.add(dedupKey(c))) configs += c
        }
        val dupRemoved = rawConfigs.size - configs.size
        if (dupRemoved > 0) {
            fetchedConfigs = configs.toMutableList()
            saveState()
            progressText.text = L("$dupRemoved کانفیگ تکراری حذف شد؛ ${configs.size} کانفیگ برای تست باقی ماند.", "$dupRemoved duplicate configs removed; ${configs.size} configs left to test.")
        }
        // Ping only the checked subs. A config whose source
        // sub is unknown (pasted by hand) is still tested;
        // a config from an unchecked sub is skipped, and
        // the fetched list is NOT rewritten to the
        // filtered list, so unchecked configs stay there
        // for later.
        val toTest = configs.filter { cfg ->
            val src = subSourceMap[dedupKey(cfg)]
            src == null || isSubSelected(src)
        }
        val skipped = configs.size - toTest.size
        if (toTest.isEmpty()) {
            progressText.text = L("هیچ سابی انتخاب نشده؛ ساب‌های مورد نظر را تیک بزن.", "No subscription selected; tick the subscriptions you want.")
            statusText.text = L("هیچ سابی انتخاب نشده؛ ساب‌های مورد نظر را تیک بزن.", "No subscription selected; tick the subscriptions you want.")
            return
        }
        if (skipped > 0) {
            progressText.text = L("$skipped کانفیگ مربوط به ساب‌های تیک‌نخورده تست نمی‌شود؛ ${toTest.size} کانفیگ تست می‌شود.", "$skipped configs from unchecked subscriptions will not be tested; ${toTest.size} configs will be tested.")
        }
        if (!initCore()) {
            val msg = L("تست پینگ شروع نشد: هسته Xray راه‌اندازی نشد ($coreInitError). اپ را ببند و دوباره باز کن.", "Ping test did not start: Xray core failed to start ($coreInitError). Close and reopen the app.")
            progressText.text = msg
            statusText.text = msg
            return
        }
        // Hand the exact test list to the service, and persist
        // the source map so the service can attribute healthy
        // configs to subs even after a restart.
        saveSubSource()
        prefs().edit()
            .putString("ping_test_list", toTest.joinToString("\n"))
            .putString("ping_healthy_live", "")
            .putInt("ping_progress_done", 0)
            .putInt("ping_progress_total", toTest.size)
            .putInt("ping_progress_healthy", 0)
            .putBoolean("ping_done", false)
            .putBoolean("ping_applied", false)
            .putBoolean("ping_running", true)
            .putString("ping_error", "")
            .putString("ping_live_sub", "{}")
            .apply()
        if (subsVisible) refreshSubsList()
        saveState()
        testing = true
        startBtn.isEnabled = false
        // The output view is NOT cleared by hand here:
        // once the poller sees the test running it shows
        // the live list, which starts empty and grows.
        progressText.text = L("در حال تست پینگ... ۰ از ${toTest.size} تست شد، ۰ سالم", "Testing ping... 0 of ${toTest.size} tested, 0 healthy")
        try {
            val intent = Intent(this, PingTestService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            testing = false
            startBtn.isEnabled = true
            prefs().edit().putBoolean("ping_running", false).apply()
            val msg = L("تست پینگ شروع نشد: ${e.message}", "Ping test did not start: ${e.message}")
            progressText.text = msg
            statusText.text = msg
            return
        }
        startPolling()
    }

    private fun measureOne(cfg: String): Long {
        // Step 1 (v2rayNG): TCP pre-check for simple types, 1000 ms.
        if (XraySpeedtest.isSimpleType(cfg)) {
            val srv = XraySpeedtest.parseServer(cfg) ?: return -1
            if (XraySpeedtest.tcpConnectTime(srv.host, srv.port) < 0) return -1
        }
        // Step 2 (v2rayNG batch mode): Xray speedtest config +
        // measureOutboundDelay with the PRIMARY test URL only.
        // v2rayNG's batch worker (RealPingWorkerService) uses
        // only DELAY_TEST_URL; the second URL is used only in
        // CoreServiceManager's single-server test, not in batch.
        // Dropping the second attempt in batch mode therefore
        // halves the worst-case time for dead configs without
        // changing the measurement itself at all: the per-config
        // test is still TCP 1000 ms + Xray generate_204, 2
        // attempts inside libv2ray, min duration, 200/204 only.
        val config = XraySpeedtest.buildSpeedtestConfig(cfg) ?: return -1
        return try {
            Libv2ray.measureOutboundDelay(config, XraySpeedtest.TEST_URL)
        } catch (_: Throwable) { -1L }
    }
}
