package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/** Per-subscription settings, v2rayNG-style.
 *  Every option works: remarks, URL, user agent,
 *  request headers (JSON), remarks filter (regex,
 *  applied at import), enable update, automatic
 *  update + interval (min 15 min), allow insecure
 *  http, and prev/next proxy profiles (by remarks).
 *  Prev/next chain order at connect time is
 *  device -> prev -> config -> next -> destination
 *  (see VpnSubs.chainForConfig). */
class VpnSubEditActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private lateinit var status: TextView
    private lateinit var remarksEt: EditText
    private lateinit var urlEt: EditText
    private lateinit var uaEt: EditText
    private lateinit var headersEt: EditText
    private lateinit var filterEt: EditText
    private lateinit var intervalEt: EditText
    private lateinit var enabledSw: Switch
    private lateinit var autoSw: Switch
    private lateinit var insecureSw: Switch
    private lateinit var prevSpinner: Spinner
    private lateinit var nextSpinner: Spinner
    private var originalUrl: String = ""
    private var profileOptions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalUrl = intent.getStringExtra("url") ?: ""
        val items = VpnSubs.loadItems(this)
        val item = items.firstOrNull { it.url == originalUrl }
            ?: SubItem(url = originalUrl)
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        scroll.addView(root)

        // Top bar: back, delete (existing only), save.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@VpnSubEditActivity).apply {
                text = "←"
                
                setOnClickListener { finish() }
            })
            addView(TextView(this@VpnSubEditActivity).apply {
                text = if (originalUrl.isBlank())
                    L("افزودن سابسکریبشن", "Add subscription")
                else
                    L("تنظیمات سابسکریبشن", "Subscription settings")
                textSize = 18f
                setPadding((12 * d).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (originalUrl.isNotBlank()) {
                addView(Button(this@VpnSubEditActivity).apply {
                    text = "✕"
                    
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@VpnSubEditActivity)
                            .setTitle(L("حذف سابسکریبشن", "Delete subscription"))
                            .setMessage(L("این ساب حذف شود؟ کانفیگ‌هایش در لیست باقی می‌مانند.",
                                "Delete this subscription? Its configs stay in the list."))
                            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                                val all = VpnSubs.loadItems(this@VpnSubEditActivity)
                                all.removeAll { it.url == originalUrl }
                                VpnSubs.saveItems(this@VpnSubEditActivity, all)
                                finish()
                            }
                            .setNegativeButton(L("لغو", "Cancel"), null)
                            .show()
                    }
                })
            }
            addView(Button(this@VpnSubEditActivity).apply {
                text = "✓"
                
                setOnClickListener { save() }
            })
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(status)

        fun label(text: String) {
            root.addView(TextView(this).apply {
                this.text = text; textSize = 14f
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            })
        }
        fun field(initial: String, hint: String = ""): EditText {
            return EditText(this).apply {
                setText(initial)
                if (hint.isNotBlank()) this.hint = hint
                setSingleLine()
            }.also { root.addView(it) }
        }
        fun switchRow(text: String, checked: Boolean): Switch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            row.addView(TextView(this).apply {
                this.text = text; textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            return Switch(this).apply { isChecked = checked }.also { row.addView(it) }
                .also { root.addView(row) }
        }

        label(L("نام ساب (Remarks)", "Remarks"))
        remarksEt = field(item.remarks)
        label("Optional URL")
        urlEt = field(item.url, "https://...")
        label("User Agent")
        uaEt = field(item.userAgent)
        label(L("هدرهای درخواست (JSON)", "Request Headers (JSON)"))
        headersEt = EditText(this).apply {
            setText(item.requestHeaders)
            hint = "{\"Authorization\": \"Bearer ...\"}"
        }.also { root.addView(it) }
        label(L("فیلتر نام (regex؛ فقط کانفیگ‌های هم‌نام نگه داشته می‌شوند)", "Remarks regular filter (regex; only matching configs are kept)"))
        filterEt = field(item.filter)
        enabledSw = switchRow(L("فعال‌سازی به‌روزرسانی (Enable update)", "Enable update"), item.enabled)
        autoSw = switchRow(L("به‌روزرسانی خودکار (Enable automatic update)", "Enable automatic update"), item.autoUpdate)
        label(L("فاصله به‌روزرسانی خودکار (دقیقه، حداقل ۱۵)", "Auto Update Interval (Minutes, min 15)"))
        intervalEt = EditText(this).apply {
            setText(item.updateInterval.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }.also { root.addView(it) }
        insecureSw = switchRow(L("اجازه آدرس HTTP ناامن (Allow insecure HTTP address)", "Allow insecure HTTP address"), item.allowInsecure)

        // Prev/next profile dropdowns: remarks of
        // plain configs (v2rayNG getServerViaRemarks
        // skips custom configs; groups and chains
        // cannot be chain hops either).
        val servers = VpnSubs.loadServers(this)
        profileOptions = listOf("—") + servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) && !XraySpeedtest.isCustom(it)
        }.map { XraySpeedtest.displayName(it) }.distinct()
        fun spinner(labelText: String, current: String): Spinner {
            label(labelText)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            return Spinner(this).apply {
                this.adapter = adapter
                val idx = profileOptions.indexOf(current)
                if (idx >= 0) setSelection(idx)
            }.also { root.addView(it) }
        }
        prevSpinner = spinner(L("پروکسی قبلی (Previous proxy config remarks)", "Previous proxy config remarks"), item.prevProfile)
        nextSpinner = spinner(L("پروکسی بعدی (Next proxy config remarks)", "Next proxy config remarks"), item.nextProfile)

        root.addView(TextView(this).apply {
            text = L("ترتیب زنجیره در اتصال: دستگاه ← پروکسی قبلی ← کانفیگ ← پروکسی بعدی ← مقصد.",
                "Chain order at connect: device -> previous proxy -> config -> next proxy -> destination.")
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, (16 * d).toInt(), 0, 0)
        })
        setContentView(scroll)
    }

    private fun spinnerValue(sp: Spinner): String {
        val v = sp.selectedItem as? String ?: "—"
        return if (v == "—") "" else v
    }

    private fun save() {
        val url = urlEt.text.toString().trim()
        if (url.isBlank() || !url.startsWith("http")) {
            status.text = L("لینک ساب معتبر نیست.", "Subscription link is not valid.")
            return
        }
        val headers = headersEt.text.toString().trim()
        if (headers.isNotBlank()) {
            try {
                JSONObject(headers)
            } catch (_: Exception) {
                status.text = L("هدرهای درخواست باید یک JSON معتبر باشد.", "Request headers must be valid JSON.")
                return
            }
        }
        val interval = intervalEt.text.toString().trim().toIntOrNull()?.coerceAtLeast(15) ?: 1440
        val filter = filterEt.text.toString().trim()
        if (filter.isNotBlank()) {
            try {
                Regex(filter)
            } catch (_: Exception) {
                status.text = L("فیلتر regex معتبر نیست.", "The filter regex is not valid.")
                return
            }
        }
        val all = VpnSubs.loadItems(this)
        val existing = all.firstOrNull { it.url == originalUrl }
        val item = (existing ?: SubItem(url = url)).apply {
            this.url = url
            remarks = remarksEt.text.toString().trim()
            userAgent = uaEt.text.toString().trim()
            requestHeaders = headers
            this.filter = filter
            enabled = enabledSw.isChecked
            autoUpdate = autoSw.isChecked
            updateInterval = interval
            allowInsecure = insecureSw.isChecked
            prevProfile = spinnerValue(prevSpinner)
            nextProfile = spinnerValue(nextSpinner)
        }
        if (existing != null) {
            val idx = all.indexOf(existing)
            all[idx] = item
        } else {
            if (all.none { it.url == url }) all += item
        }
        VpnSubs.saveItems(this, all)
        finish()
    }
}
