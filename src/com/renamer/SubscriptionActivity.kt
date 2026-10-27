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
    private lateinit var box1: EditText
    private lateinit var box2: EditText
    private lateinit var nameF: EditText
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
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

    /** Persist box 1, the desired name, the healthy output and
     *  lastHealthy, so returning to this page never clears
     *  them. Only a fresh "update all" replaces box 1, and
     *  only a fresh ping test replaces box 2. */
    private fun saveState() {
        try {
            prefs().edit()
                .putString("box1", box1.text.toString())
                .putString("box2", box2.text.toString())
                .putString("desired_name", nameF.text.toString())
                .putString("last_healthy", lastHealthy.joinToString("\n"))
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadState() {
        try {
            val b1 = prefs().getString("box1", "") ?: ""
            val b2 = prefs().getString("box2", "") ?: ""
            val nm = prefs().getString("desired_name", "") ?: ""
            val lh = prefs().getString("last_healthy", "") ?: ""
            if (b1.isNotBlank()) box1.setText(b1)
            if (b2.isNotBlank()) box2.setText(b2)
            if (nm.isNotBlank()) nameF.setText(nm)
            lastHealthy = lh.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (b1.isNotBlank() || b2.isNotBlank()) {
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
            return
        }
        val done = p.getInt("ping_progress_done", 0)
        val total = p.getInt("ping_progress_total", 0)
        val healthy = p.getInt("ping_progress_healthy", 0)
        progressText.text = L("در حال تست پینگ... $done از $total تست شد، $healthy سالم", "Testing ping... $done of $total tested, $healthy healthy")
        val live = (p.getString("ping_healthy_live", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        if (live.isNotEmpty()) {
            val name = nameF.text.toString().trim()
            val out = if (name.isBlank()) live else live.map { renameConfig(it, name) }
            box2.setText(out.joinToString("\n"))
        }
    }

    /** Final step shared by the poller and onResume: take the
     *  service's sorted final list, refresh the per-sub report
     *  (always, not only when the subs list is visible), apply
     *  the desired name, and show a per-sub summary line. */
    private fun applyFinishedTest() {
        val p = prefs()
        val lh = (p.getString("last_healthy", "") ?: "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        lastHealthy = lh
        loadSubStats()
        refreshSubsList()
        applyDesiredName()
        saveState()
        p.edit().putBoolean("ping_applied", true).apply()
        testing = false
        startBtn.isEnabled = true
        val err = p.getString("ping_error", "") ?: ""
        val total = p.getInt("ping_progress_total", 0)
        val base = if (err.isNotBlank()) {
            L("تست پینگ با خطا تمام شد: $err", "Ping test ended with an error: $err")
        } else {
            L("تست پینگ انجام شد: ${lh.size} سالم از $total کانفیگ.", "Ping test done: ${lh.size} healthy of $total configs.")
        }
        val summary = subSummaryLines()
        val msg = if (summary.isEmpty()) base else "$base\n$summary"
        progressText.text = msg
        statusText.text = msg
    }

    /** One line per sub with stats: "ساب X: a سالم از b". */
    private fun subSummaryLines(): String {
        val lines = mutableListOf<String>()
        for (url in loadSubs()) {
            val rec = subReceivedMap[url] ?: 0
            if (rec <= 0) continue
            val ok = subHealthyMap[url] ?: 0
            val name = subNamesMap[url] ?: url
            lines += L("ساب $name: $ok سالم از $rec", "Sub $name: $ok healthy of $rec")
        }
        return lines.joinToString("\n")
    }
    private fun loadSubs(): MutableList<String> {
        val raw = prefs().getString("subs", "") ?: ""
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    }
    private fun saveSubs(list: List<String>) {
        prefs().edit().putString("subs", list.joinToString("\n")).apply()
    }

    private fun fixedBox(heightDp: Int, hint: String): EditText = EditText(this).apply {
        this.hint = hint
        gravity = Gravity.TOP
        setTextColor(Color.BLACK)
        isVerticalScrollBarEnabled = true
        movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (heightDp * resources.displayMetrics.density).toInt()
        )
        setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 16, 0, 16) }
        setBackgroundColor(Color.parseColor("#DDDDDD"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        // Top bar: title + three-dot menu
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(TextView(this).apply {
            text = L("اشتراک‌ها", "Subscriptions"); textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(Button(this).apply {
            text = "⋮"; textSize = 20f
            setOnClickListener { showMenu() }
        })
        root.addView(topRow)
        // Progress line, right at the top, so a test or an
        // update is visible immediately without scrolling down.
        progressText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 8)
        }
        root.addView(progressText)
        root.addView(divider())

        // Saved subscription links: hidden by default, so a
        // long list never crowds the page. A button shows
        // them on this same page when tapped.
        root.addView(TextView(this).apply { text = L("لینک‌های ساب ذخیره‌شده", "Saved subscription links"); textSize = 18f })
        toggleSubsBtn = Button(this).apply {
            text = L("نمایش لینک‌های ساب", "Show subscription links")
            setOnClickListener {
                subsVisible = !subsVisible
                subsContainer.visibility = if (subsVisible) View.VISIBLE else View.GONE
                updateToggleText()
                if (subsVisible) refreshSubsList()
            }
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
        newSubF = EditText(this).apply { hint = L("لینک ساب جدید (https://...)", "New subscription link (https://...)") }
        root.addView(newSubF)
        root.addView(Button(this).apply {
            text = L("افزودن و ذخیره لینک ساب", "Add and save subscription link")
            setOnClickListener {
                val url = newSubF.text.toString().trim()
                if (url.isBlank() || !url.startsWith("http")) {
                    statusText.text = L("لینک ساب معتبر نیست.", "Subscription link is not valid.")
                    return@setOnClickListener
                }
                val subs = loadSubs()
                if (!subs.contains(url)) {
                    subs += url; saveSubs(subs)
                }
                newSubF.setText("")
                refreshSubsList()
                statusText.text = L("لینک ساب ذخیره شد.", "Subscription link saved.")
            }
        })
        refreshSubsList()
        root.addView(divider())

        // Box 1: fetched configs (fixed height, internal scroll)
        root.addView(TextView(this).apply {
            text = L("کانفیگ‌های دریافت‌شده", "Received configs"); textSize = 18f
            setPadding(0, 8, 0, 8)
        })
        box1 = fixedBox(240, L("با سه‌نقطه بالا، «به‌روزرسانی همه اشتراک‌ها» را بزن", "Tap “Update all subscriptions” in the ⋮ menu at the top"))
        root.addView(box1)
        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply {
            text = L("استارت تست پینگ", "Start ping test")
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runPingTest() }
        }
        startRow.addView(startBtn)
        stopBtn = Button(this).apply {
            text = L("توقف تست پینگ", "Stop ping test")
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                try {
                    stopService(Intent(this@SubscriptionActivity, PingTestService::class.java))
                } catch (_: Exception) {}
                progressText.text = L("در حال توقف تست پینگ...", "Stopping ping test...")
            }
        }
        startRow.addView(stopBtn)
        root.addView(startRow)
        root.addView(divider())

        // Desired name for the output: if filled, healthy
        // configs get exactly this name; if empty, they keep
        // their original names.
        root.addView(TextView(this).apply {
            text = L("نام دلخواه برای خروجی", "Desired name for output"); textSize = 18f
            setPadding(0, 8, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = L("اگر خالی بماند، کانفیگ‌ها با اسم‌های اصلی‌شان می‌آیند. اگر نامی بنویسی، همه سالم‌ها دقیقاً همان نام را می‌گیرند.", "If left empty, configs come with their original names. If you write a name, all healthy ones get exactly that name.")
            setTextColor(Color.GRAY); textSize = 14f
        })
        nameF = EditText(this).apply { hint = L("نام دلخواه (اختیاری)", "Desired name (optional)") }
        root.addView(nameF)
        root.addView(Button(this).apply {
            text = L("اعمال نام روی خروجی", "Apply name to output")
            setOnClickListener { applyDesiredName() }
        })
        // Box 2: healthy only (fixed height, internal scroll)
        root.addView(TextView(this).apply {
            text = L("فقط کانفیگ‌های سالم", "Only healthy configs"); textSize = 18f
            setPadding(0, 8, 0, 8)
        })
        box2 = fixedBox(240, L("بعد از تست پینگ، سالم‌ها اینجا می‌آیند", "After the ping test, healthy ones will appear here"))
        root.addView(box2)
        root.addView(Button(this).apply {
            text = L("کپی کانفیگ‌های سالم", "Copy healthy configs")
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                val text = box2.text.toString().trim()
                if (text.isBlank()) {
                    statusText.text = L("چیزی برای کپی نیست.", "Nothing to copy.")
                    return@setOnClickListener
                }
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("healthy", text))
                    statusText.text = L("کانفیگ‌های سالم کپی شد.", "Healthy configs copied.")
                    Toast.makeText(this@SubscriptionActivity, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                }
            }
        })
        statusText = TextView(this).apply { setPadding(0, 12, 0, 12) }
        root.addView(statusText)
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
        loadState()
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
        val subs = loadSubs()
        if (subs.isEmpty()) {
            subsContainer.addView(TextView(this).apply {
                text = L("هنوز لینک سابی ذخیره نشده است.", "No subscription link saved yet.")
                setTextColor(Color.GRAY)
            })
            return
        }
        for (url in subs) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                text = label ?: url; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Edit button: give this sub a custom name.
            row.addView(Button(this).apply {
                text = "✎"
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
                text = L("حذف", "Delete")
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
            // Report in front of each sub link: how many
            // configs were received from it, and how many of
            // those were healthy in the last ping test.
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
        val items = arrayOf(L("به‌روزرسانی همه اشتراک‌ها", "Update all subscriptions"), L("تست پینگ همه کانفیگ‌ها", "Ping test all configs"), L("خالی کردن همه کادرها", "Clear all boxes"))
        AlertDialog.Builder(this)
            .setTitle("⋮")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> updateAllSubs()
                    1 -> runPingTest()
                    2 -> AlertDialog.Builder(this)
                        .setTitle(L("خالی کردن همه کادرها", "Clear all boxes"))
                        .setMessage(L("همه کادرها (دریافت‌شده، نام دلخواه و سالم‌ها) خالی شود؟", "Clear all boxes (received, desired name and healthy ones)?"))
                        .setPositiveButton(L("بله، خالی کن", "Yes, clear")) { _, _ ->
                            box1.setText(""); box2.setText(""); nameF.setText("")
                            lastHealthy = emptyList()
                            saveState()
                            progressText.text = L("همه کادرها خالی شد.", "All boxes cleared.")
                            statusText.text = L("همه کادرها خالی شد.", "All boxes cleared.")
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

    private fun updateAllSubs() {
        val subs = loadSubs()
        if (subs.isEmpty()) {
            progressText.text = L("اول یک لینک ساب ذخیره کن.", "Save a subscription link first.")
            statusText.text = L("اول یک لینک ساب ذخیره کن.", "Save a subscription link first.")
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
                box1.setText(all.joinToString("\n"))
                saveState()
                subReceivedMap.clear(); subReceivedMap.putAll(perSub)
                subHealthyMap.clear()
                subSourceMap.clear(); subSourceMap.putAll(source)
                saveSubStats()
                saveSubSource()
                if (subsVisible) refreshSubsList()
                val dup = received - all.size
                val msg = L("به‌روزرسانی انجام شد: $received کانفیگ دریافت شد", "Update done: $received configs received") +
                    if (dup > 0) L("، $dup تکراری حذف شد، ${all.size} کانفیگ باقی ماند.", ", $dup duplicates removed, ${all.size} configs left.")
                    else L("، ${all.size} کانفیگ اضافه شد.", ", ${all.size} configs added.")
                progressText.text = msg
                statusText.text = msg
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

    /** Apply the desired-name box to the healthy output:
     *  empty -> original names, filled -> exactly that name. */
    private fun applyDesiredName() {
        if (lastHealthy.isEmpty()) {
            statusText.text = L("هنوز تست پینگی انجام نشده است.", "No ping test has been run yet.")
            return
        }
        val name = nameF.text.toString().trim()
        val out = if (name.isBlank()) lastHealthy
                  else lastHealthy.map { renameConfig(it, name) }
        box2.setText(out.joinToString("\n"))
        saveState()
        statusText.text = if (name.isBlank())
            L("خروجی با اسم‌های اصلی برگشت.", "Output returned with original names.")
        else
            L("خروجی با نام «$name» اعمال شد.", "Output applied with name “$name”.")
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
     *  prefs, which the poller shows in box 2 as each healthy
     *  config appears. */
    private fun runPingTest() {
        if (testing || prefs().getBoolean("ping_running", false)) {
            progressText.text = L("تست پینگ در حال انجام است؛ صبر کن.", "Ping test is running; please wait.")
            startPolling()
            return
        }
        val rawConfigs = box1.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rawConfigs.isEmpty()) {
            progressText.text = L("کادر اول خالی است؛ اول اشتراک‌ها را به‌روز کن.", "Box 1 is empty; update the subscriptions first.")
            statusText.text = L("کادر اول خالی است؛ اول اشتراک‌ها را به‌روز کن.", "Box 1 is empty; update the subscriptions first.")
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
            box1.setText(configs.joinToString("\n"))
            saveState()
            progressText.text = L("$dupRemoved کانفیگ تکراری حذف شد؛ ${configs.size} کانفیگ برای تست باقی ماند.", "$dupRemoved duplicate configs removed; ${configs.size} configs left to test.")
        }
        // Ping only the checked subs. A config whose source
        // sub is unknown (pasted by hand) is still tested;
        // a config from an unchecked sub is skipped, and
        // box 1 is NOT rewritten to the filtered list, so
        // unchecked configs stay there for later.
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
            .apply()
        saveState()
        testing = true
        startBtn.isEnabled = false
        // Box 2 is NOT cleared here: the old output stays
        // until live healthy configs start replacing it.
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
