package com.renamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloudflare section, GUIDED edition (v1.94).
 *
 * The old panel-manager cards are replaced by two guided steps plus
 * a compact results box — everything happens inside the app and the
 * panel itself is never shown:
 *
 *  Step 1 — the user creates a Cloudflare account (copyable signup
 *  link), opens the API tokens page (copyable link), enters the
 *  account email + Global key and taps Connect. The app checks the
 *  credentials' health, saves them, and the step key turns from soft
 *  red to soft green. Afterwards, in the background, the app builds
 *  the Nahan panel (fixed internal worker/database names, never
 *  shown), logs into it, creates ONE random user (empty limits,
 *  expiry and notes) and scans 10 healthy Cloudflare IPs into the
 *  panel's IPs section.
 *
 *  Step 2 — the user picks countries (Germany, Netherlands, France,
 *  China, any combination). For each country the app sets that
 *  country's proxy plus the NAT64 prefixes, fetches that user's
 *  subscription and keeps the first VLESS config. The step key turns
 *  soft green when done.
 *
 *  Results — a compact box below step 2 with one row per config:
 *  flag + "Cloudflare - country" name, ping test, copy and connect
 *  per config, plus copy-all / ping-all / import-to-v2ray buttons.
 *  Imported configs show in the v2ray section with a "Cloudflare"
 *  label, a soft galaxy tint and a flag that alternates every 2s
 *  between the country flag and the section icon.
 *
 *  A small log icon at the top-right records every step and every
 *  failure, start to finish. The log NEVER contains emails, keys,
 *  panel addresses, passwords, proxy IPs or config links — only step
 *  outcomes, counts and country names.
 *
 *  Panel password: the panel's public default password, used ONLY
 *  for the automatic background login (explicit user order: never
 *  changed, never displayed, never logged).
 */
class CloudflareActivity : Activity() {

    companion object {
        const val PREFS = "renamer_cf"
        const val VPN_REQ = 703

        /** Fixed internal names — never shown in the UI (user order). */
        private const val WORKER_NAME = "nahan-core"
        private const val DB_NAME = "iot_db"

        /** Panel's public default password: background login only,
         *  never displayed, never logged, never changed. */
        private const val PANEL_PASSWORD = "0000"

        private const val SIGNUP_URL = "https://dash.cloudflare.com/sign-up"
        private const val API_TOKENS_URL = "https://dash.cloudflare.com/profile/api-tokens"

        private data class Country(
            val code: String,
            val fa: String,
            val en: String,
            val flag: String,
            val proxy: String
        )

        /** Country proxies supplied by the user (one per country). */
        private val COUNTRIES = listOf(
            Country("de", "آلمان", "Germany", "🇩🇪", "109.122.197.126"),
            Country("nl", "هلند", "Netherlands", "🇳🇱", "79.137.197.212"),
            Country("fr", "فرانسه", "France", "🇫🇷", "159.60.146.82"),
            Country("cn", "چین", "China", "🇨🇳", "43.169.19.179"),
            Country("us", "آمریکا", "United States", "🇺🇸", "199.38.148.95"),
            Country("ca", "کانادا", "Canada", "🇨🇦", "155.138.128.135"),
            Country("gb", "انگلیس", "United Kingdom", "🇬🇧", "57.129.137.215")
        )

        /** No-proxy picker entry (1.97): not a country and never
         *  part of the relay list. */
        private val NOP = Country("nop", "بدون پروکسی", "No proxy", "", "")

        /** NAT64 prefixes applied for every country (user-supplied). */
        private val NAT64_PREFIXES = listOf(
            "[2602:fc59:11:64::]",
            "[2a02:898:146:64::]",
            "[2602:fc59:b0:64::]"
        )
        /** Country-name template: lets the app attribute each
         *  fetched config to its country from the panel's own
         *  geo. Panel names are never shown; the app renames
         *  anyway, so this only drives attribution. */
        private const val COUNTRY_TEMPLATE = "{FLAG} {COUNTRY} - {PROTOCOL} {PORT}"
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int): Int = HydraUi.dp(this, v)
    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // Results: raw panel link + country code. Names are computed
    // per language at display/copy/import time, never stored.
    private val results = mutableListOf<Pair<String, String>>()
    private val pingViews = mutableListOf<TextView?>()

    private val logLines = mutableListOf<String>()
    private var logVisible = false
    private var step1Busy = false
    private var buildBusy = false
    private var pendingBuild = false
    private var pendingNop = false
    private var bgRunning = false
    private var bgFailed = false
    private var pendingLink: String? = null

    private lateinit var statusView: TextView
    private lateinit var logBox: ScrollView
    private lateinit var logView: TextView
    private lateinit var step1Key: Button
    private lateinit var step1Content: LinearLayout
    private lateinit var step1Status: TextView
    private lateinit var retryBgBtn: Button
    private lateinit var emailEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var mgmtPlusBtn: Button
    private var mgmtDialog: android.app.Dialog? = null
    private lateinit var mgmtEmailEdit: EditText
    private lateinit var mgmtKeyEdit: EditText
    private var mgmtStatusView: TextView? = null
    private var mgmtBusy = false
    private var proxyBusy = false
    private var proxyDialog: android.app.Dialog? = null
    private var step1PickerSel = -1
    private var proxyPickerSel = -1
    private lateinit var step1PickerBox: LinearLayout
    private lateinit var proxyKey: Button
    // Selection lives here (by code) and survives grid rebuilds,
    // so ticks can never mix up between rows.
    private val selectedCodes = mutableSetOf<String>()
    private lateinit var countryGrid: LinearLayout
    // Disconnect guard: for a few seconds after the user taps
    // disconnect, the button stays IDLE even if the service
    // still reports running (kills the auto-reconnect report).
    private var disconnectingUntil = 0L
    private var cleanIpsCache: List<String> = emptyList()
    // Persian names for scanned countries; anything missing falls
    // back to the panel's English name.
    private val FA_NAMES = mapOf("us" to "آمریکا", "gb" to "بریتانیا", "de" to "آلمان", "nl" to "هلند", "fr" to "فرانسه", "it" to "ایتالیا", "es" to "اسپانیا", "se" to "سوئد", "no" to "نروژ", "dk" to "دانمارک", "fi" to "فنلاند", "pl" to "لهستان", "ch" to "سوئیس", "at" to "اتریش", "be" to "بلژیک", "ie" to "ایرلند", "pt" to "پرتغال", "cz" to "چک", "sk" to "اسلواکی", "gr" to "یونان", "tr" to "ترکیه", "ru" to "روسیه", "ua" to "اوکراین", "ca" to "کانادا", "mx" to "مکزیک", "br" to "برزیل", "ar" to "آرژانتین", "cl" to "شیلی", "co" to "کلمبیا", "pe" to "پرو", "au" to "استرالیا", "nz" to "نیوزیلند", "jp" to "ژاپن", "kr" to "کره جنوبی", "sg" to "سنگاپور", "hk" to "هنگ‌کنگ", "tw" to "تایوان", "in" to "هند", "id" to "اندونزی", "ph" to "فیلیپین", "vn" to "ویتنام", "th" to "تایلند", "my" to "مالزی", "ae" to "امارات", "sa" to "عربستان", "il" to "اسرائیل", "eg" to "مصر", "za" to "آفریقای جنوبی", "cn" to "چین", "kz" to "قزاقستان", "pk" to "پاکستان", "bd" to "بنگلادش", "ir" to "ایران", "iq" to "عراق", "af" to "افغانستان", "ge" to "گرجستان", "am" to "ارمنستان", "az" to "آذربایجان", "by" to "بلاروس", "hu" to "مجارستان", "ro" to "رومانی", "bg" to "بلغارستان", "hr" to "کرواسی", "si" to "اسلوونی", "rs" to "صربستان", "ee" to "استونی", "lv" to "لتونی", "lt" to "لیتوانی", "is" to "ایسلند", "lu" to "لوکزامبورگ", "mt" to "مالت", "cy" to "قبرس", "jo" to "اردن", "lb" to "لبنان", "kw" to "کویت", "qa" to "قطر", "bh" to "بحرین", "om" to "عمان")
    private lateinit var resultsCard: LinearLayout
    private lateinit var resultsTitle: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var proxyResultsCard: LinearLayout
    private lateinit var proxyResultsTitle: TextView
    private lateinit var proxyResultsBox: LinearLayout

    private fun reason(code: String): String {
        val (fa, en) = NahanClient.reasonFaEn(code)
        return AppLanguage.t(this, fa, en)
    }

    private fun deployReason(code: String): String {
        val (fa, en) = CfDeployClient.reasonFaEn(code)
        return AppLanguage.t(this, fa, en)
    }

    private fun setStatus(s: String) {
        runOnUiThread {
            statusView.text = s
            statusView.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun step1Status(s: String) {
        runOnUiThread { step1Status.text = s }
    }


    private fun toast(s: String) {
        runOnUiThread {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cloudflare", text))
        toast(L("کپی شد.", "Copied."))
    }

    private fun bg(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (_: Exception) {
                setStatus(L("خطای غیرمنتظره.", "Unexpected error."))
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            setStatus(L("مرورگر باز نشد.", "Could not open the browser."))
        }
    }

    // ------------------------------------------------------------------
    // Log (top-right icon): outcomes and counts only, never secrets.
    // ------------------------------------------------------------------

    private fun logLine(fa: String, en: String) = logLineStr(L(fa, en))

    private fun logLineStr(s: String) {
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
            logLines += "$time $s"
            while (logLines.size > 150) logLines.removeAt(0)
            try {
                val arr = JSONArray()
                for (l in logLines) arr.put(l)
                prefs().edit().putString("cf_log", arr.toString()).apply()
            } catch (_: Exception) {
            }
            refreshLogView()
        }
    }

    private fun refreshLogView() {
        if (!logVisible) return
        logView.text = if (logLines.isEmpty()) {
            L("هنوز چیزی ثبت نشده است.", "Nothing logged yet.")
        } else {
            logLines.joinToString("\n")
        }
        logBox.post { logBox.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadLog() {
        logLines.clear()
        try {
            val arr = JSONArray(prefs().getString("cf_log", "") ?: "")
            for (i in 0 until arr.length()) logLines += arr.optString(i, "")
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Small UI builders
    // ------------------------------------------------------------------

    private fun hintText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Theme.subFg(this@CloudflareActivity))
            setPadding(0, dp(2), 0, dp(6))
        }
    }

    private fun edit(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Theme.fg(this@CloudflareActivity))
            setHintTextColor(Theme.subFg(this@CloudflareActivity))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button {
        return HydraUi.glassButton(this, text, onClick).apply { textSize = 12f }
    }

    private fun row(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            setBackgroundColor(Theme.divider(this@CloudflareActivity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        }
    }

    /** A copyable link row: selectable text + copy + open buttons. */
    private fun linkRow(url: String): LinearLayout {
        val r = row()
        r.addView(TextView(this).apply {
            text = url
            textSize = 13f
            setTextColor(Color.parseColor("#1A5FB4"))
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        r.addView(smallButton(L("کپی", "Copy")) { copyText(url) })
        r.addView(smallButton(L("باز کردن", "Open")) { openUrl(url) })
        return r
    }

    /** A step key: full-width tinted glass button, soft red while
     *  pending, soft green when done. */
    private fun stepKey(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }
    }

    private fun paintStepKey(btn: Button, done: Boolean) {
        val (fill, stroke, txt) = if (done) {
            Triple(
                Color.parseColor("#E6F4EA"),
                Color.parseColor("#A3D9B1"),
                Color.parseColor("#14532D")
            )
        } else {
            Triple(
                Color.parseColor("#FDECEC"),
                Color.parseColor("#E8AFAF"),
                Color.parseColor("#7A1F1F")
            )
        }
        btn.background = HydraUi.glassBg(fill, stroke, 28f, this)
        btn.setTextColor(txt)
    }

    private fun refreshStepKeys() {
        // 1.100: one merged key. Green once step 1 is FULLY
        // done (panel + healthy IPs); the results box shows
        // whether configs were built.
        val bgDone = prefs().getBoolean("cf_bg_done", false)
        step1Key.text = L("اتصال و ساخت کانفیگ", "Connect and build configs") +
            if (bgDone) " ✓" else ""
        paintStepKey(step1Key, bgDone)
        // 1.101: the proxy key is green once this account has a
        // Telegram proxy, pending red otherwise.
        if (::proxyKey.isInitialized) {
            val hasProxy = prefs().getString("cf_proxy_link", "")?.isNotBlank() == true
            proxyKey.text = L("اتصال و ساخت پروکسی تلگرام", "Connect and build Telegram proxy") +
                if (hasProxy) " ✓" else ""
            paintStepKey(proxyKey, hasProxy)
        }
        // The small + button (add/switch accounts): visible
        // once step 1 is fully done, or always once two
        // accounts exist (so the user can switch back).
        if (::mgmtPlusBtn.isInitialized) {
            mgmtPlusBtn.visibility = if (loadAccounts().size >= 2 || bgDone) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Theme.bg(this@CloudflareActivity))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Top bar: log icon at the visual right, guide icon next
        // to it (absolute rules, so it holds in both languages).
        // No back button here (user order): system Back still works.
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }
        val logBtn = HydraUi.glassButton(this, "🗒") {
            logVisible = !logVisible
            logBox.visibility = if (logVisible) View.VISIBLE else View.GONE
            if (logVisible) refreshLogView()
        }.apply { textSize = 12f; minimumWidth = 0; minimumHeight = 0; setPadding(0, 0, 0, 0) }
        logBtn.id = View.generateViewId()
        logBtn.layoutParams = RelativeLayout.LayoutParams(
            dp(32), dp(32)
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            addRule(RelativeLayout.CENTER_VERTICAL)
        }
        val guideBtn = HydraUi.glassButton(this, "؟") {
            val i = Intent(this, GuideDetailActivity::class.java)
            i.putExtra("section", "cloudflare")
            startActivity(i)
        }.apply { textSize = 12f; minimumWidth = 0; minimumHeight = 0; setPadding(0, 0, 0, 0) }
        guideBtn.layoutParams = RelativeLayout.LayoutParams(
            dp(32), dp(32)
        ).apply {
            addRule(RelativeLayout.LEFT_OF, logBtn.id)
            addRule(RelativeLayout.CENTER_VERTICAL)
        }
        topBar.addView(guideBtn)
        topBar.addView(logBtn)
        root.addView(topBar)

        // Section icon, like the Hydra page: the galaxy cloud icon.
        root.addView(ImageView(this).apply {
            try {
                setImageBitmap(
                    android.graphics.BitmapFactory.decodeStream(assets.open("cf.png"))
                )
            } catch (_: Exception) {
            }
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(94)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(4), 0, dp(4))
            }
        })
        root.addView(TextView(this).apply {
            text = L("ساخت کانفیگ و پروکسی تلگرام با کلادفلر", "Build configs and Telegram proxy with Cloudflare")
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        // 2.0: no "start from step 1" line — the status view
        // stays hidden until a real status message is set.
        statusView = TextView(this).apply {
            text = ""
            visibility = View.GONE
            textSize = 14f
            setTextColor(Theme.fg(this@CloudflareActivity))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView)

        // Collapsible log box (toggled by the top-right icon).
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.fg(this@CloudflareActivity))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logBox = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
            setBackgroundColor(Theme.card(this@CloudflareActivity))
            addView(logView)
        }
        root.addView(logBox)

        // ---------------- Step 1 ----------------
        step1Key = stepKey("") { toggleStep1() }.apply { textSize = 14f }
        step1Content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        step1Content.addView(TextView(this).apply {
            text = L(
                "⚠️ اول فیلترشکن را خاموش کنید؛ اسکن آی‌پی باید بدون فیلترشکن انجام شود.",
                "⚠️ Turn your VPN off first; the IP scan must run without a VPN."
            )
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            setPadding(0, dp(4), 0, dp(8))
        })
        // 1.101: account picker for the config build (only when
        // two accounts exist). Rebuilt on every dialog open:
        // light red until the user picks, light green after.
        step1PickerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        step1Content.addView(step1PickerBox)
        step1Content.addView(hintText(L(
            "وارد لینک زیر شو و یک اکانت کلودفلر بساز؛ بعد وارد ایمیلت شو تا ایمیل کلودفلر تایید شود.",
            "Open the link below and create a Cloudflare account; then check your email to confirm the Cloudflare email.")))
        step1Content.addView(linkRow(SIGNUP_URL))
        step1Content.addView(hintText(L(
            "حالا لینک زیر را باز کن؛ پایین صفحه، جلوی Global API Key روی View بزن؛ یک کد تایید به ایمیلت می‌آید، آن را کپی و اینجا وارد کن تا کلید نمایش داده شود.",
            "Now open the link below; at the bottom of the page, tap View next to the Global API Key; a confirmation code will be emailed to you — copy it and enter it there so the key is shown to you.")))
        step1Content.addView(linkRow(API_TOKENS_URL))
        emailEdit = edit(
            L("ایمیل حساب کلودفلر", "Cloudflare account email"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        emailEdit.setText(prefs().getString("cf_email", "") ?: "")
        step1Content.addView(emailEdit)
        keyEdit = edit(
            L("کلید گلوبال دریافتی", "Received Global key"),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        keyEdit.setText(prefs().getString("cf_key", "") ?: "")
        step1Content.addView(keyEdit)
        step1Content.addView(hintText(L(
            "کشورها را انتخاب کن؛ برای هر کشور یک کانفیگ ساخته می‌شود. «بدون پروکسی» را به‌تنهایی انتخاب کن تا به‌تعداد آی‌پی‌های سالم (حداکثر ۱۵) کانفیگ بدون پروکسی بگیری.",
            "Pick countries; one config is built per country. Pick “No proxy” on its own to get one proxy-free config per healthy IP (at most 15).")))
        countryGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        step1Content.addView(countryGrid)
        step1Content.addView(HydraUi.glassButton(this, L("اتصال و ساخت کانفیگ", "Connect and build configs")) { doConnectAndBuild() })
        step1Status = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.subFg(this@CloudflareActivity))
            setPadding(0, dp(6), 0, dp(4))
        }
        step1Content.addView(step1Status)
        retryBgBtn = HydraUi.glassButton(
            this,
            L("تلاش دوباره برای کارهای پس‌زمینه", "Retry background tasks")
        ) { retryBackground() }.apply { visibility = View.GONE }
        step1Content.addView(retryBgBtn)
        // ---------------- Step 2 ----------------
        // The two main keys stack in one left column; the + button spans both rows.
        // their contents open as slightly smaller pages
        // (dialogs) instead of expanding inline, so nothing
        // is open by default on entry.
        val keysWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // 2.0: one wrap — the left column stacks the two keys
        // at exactly equal size (same width, same 48dp height,
        // small gap), so neither box can look cut or uneven;
        // the + button spans the height of both keys.
        step1Key.setPadding(dp(12), dp(8), dp(12), dp(8))
        step1Key.isSingleLine = true
        step1Key.ellipsize = TextUtils.TruncateAt.END
        step1Key.gravity = android.view.Gravity.CENTER
        step1Key.minimumHeight = dp(48)
        step1Key.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        proxyKey = stepKey("") { showProxyDialog() }.apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(48)
        }
        proxyKey.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        mgmtPlusBtn = Button(this).apply {
            text = "+"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#14532D"))
            background = HydraUi.glassBg(
                Color.parseColor("#E6F4EA"),
                Color.parseColor("#A3D9B1"), 15f, this@CloudflareActivity
            )
            elevation = 3 * resources.displayMetrics.density
            setPadding(0, 0, 0, 0)
            setOnClickListener { showAccountDialog() }
        }
        mgmtPlusBtn.layoutParams = LinearLayout.LayoutParams(
            dp(48), LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(4), 0, 0, 0) }
        val keysCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        keysCol.addView(step1Key)
        keysCol.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            )
        })
        keysCol.addView(proxyKey)
        keysWrap.addView(keysCol)
        keysWrap.addView(mgmtPlusBtn)
        root.addView(keysWrap)

        root.addView(dividerView().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(12), 0, dp(12)) }
        })

        // ---------------- Results box (compact) ----------------
        resultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = HydraUi.glassBg(
                Theme.card(this@CloudflareActivity),
                Theme.divider(this@CloudflareActivity), 16f, this@CloudflareActivity
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        resultsTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            setPadding(0, 0, 0, 0)
        }
        val resultsTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        resultsTitle.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        resultsTitleRow.addView(resultsTitle)
        val trashBtn = circleButton("🗑") { clearResults() }
        trashBtn.layoutParams = LinearLayout.LayoutParams(
            trashBtn.layoutParams.width, trashBtn.layoutParams.height
        ).apply { setMargins(dp(8), 0, dp(8), 0) }
        resultsTitleRow.addView(trashBtn)
        resultsCard.addView(resultsTitleRow)
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsCard.addView(resultsBox)
        // 1.102: the proxy box is SEPARATE from the config box
        // (its own card above it), never mixed into it.
        proxyResultsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = HydraUi.glassBg(
                Theme.card(this@CloudflareActivity),
                Theme.divider(this@CloudflareActivity), 16f, this@CloudflareActivity
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        proxyResultsTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            setPadding(0, 0, 0, 0)
        }
        val proxyTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        proxyResultsTitle.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        proxyTitleRow.addView(proxyResultsTitle)
        val proxyTrashBtn = circleButton("🗑") { clearProxy() }
        proxyTrashBtn.layoutParams = LinearLayout.LayoutParams(
            proxyTrashBtn.layoutParams.width, proxyTrashBtn.layoutParams.height
        ).apply { setMargins(dp(8), 0, dp(8), 0) }
        proxyTitleRow.addView(proxyTrashBtn)
        proxyResultsCard.addView(proxyTitleRow)
        proxyResultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        proxyResultsCard.addView(proxyResultsBox)
        root.addView(proxyResultsCard)
        root.addView(resultsCard)


        // 1.99: migrate the legacy single account into slot 0,
        // then trust the active account's snapshot (this heals
        // a kill in the middle of background work).
        migrateAccounts()
        if (loadAccounts().isNotEmpty()) {
            loadAccountToWorkingSet(activeIdx())
            if (::emailEdit.isInitialized) {
                emailEdit.setText(prefs().getString("cf_email", "") ?: "")
                keyEdit.setText(prefs().getString("cf_key", "") ?: "")
            }
        }
        loadLog()
        loadResults()
        refreshStepKeys()
        loadCleanIps()
        rebuildCountryGrid()
        renderResults()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQ && resultCode == RESULT_OK) {
            val link = pendingLink
            pendingLink = null
            if (link != null) doConnect(link)
        } else if (requestCode == VPN_REQ) {
            setStatus(L("دسترسی VPN تأیید نشد.", "VPN permission was not confirmed."))
        }
    }

    // ------------------------------------------------------------------
    // Step pages: slightly smaller pages inside this page.
    // ------------------------------------------------------------------

    private var step1Dialog: android.app.Dialog? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectBtns = mutableListOf<Button>()
    private var connectTapTime = 0L
    private val connPoller = object : Runnable {
        override fun run() {
            syncConnectButtons()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(connPoller)
        syncConnectButtons()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(connPoller)
    }

    /** Small glass circle button (dialog close). */
    private fun circleButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            background = HydraUi.glassBg(
                Color.parseColor("#E8FFFFFF"),
                Color.parseColor("#55000000"), 15f, this@CloudflareActivity
            )
            elevation = 3 * d
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
    }

    /** A slightly-smaller-than-screen page inside this page: a
     *  rounded dialog (92% wide, 85% tall, scrollable content). */
    private fun makeDialog(title: String, content: View): android.app.Dialog {
        val d = android.app.Dialog(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        titleRow.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        titleRow.addView(circleButton("✕") { d.dismiss() })
        wrap.addView(titleRow)
        // Detach from any previous dialog before re-parenting.
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        wrap.addView(content)
        val scroll = ScrollView(this).apply { addView(wrap) }
        d.setContentView(scroll)
        d.setCancelable(true)
        d.window?.let { w ->
            val dm = resources.displayMetrics
            w.setLayout(
                (dm.widthPixels * 0.92).toInt(),
                (dm.heightPixels * 0.85).toInt()
            )
            w.setBackgroundDrawable(
                HydraUi.glassBg(
                    Theme.card(this), Theme.divider(this), 20f, this
                )
            )
        }
        return d
    }

    private fun showStep1Dialog() {
        step1Content.visibility = View.VISIBLE
        step1PickerSel = -1
        refreshStep1Picker(-1)
        step1Dialog?.dismiss()
        step1Dialog = makeDialog(
            L("اتصال و ساخت کانفیگ", "Connect and build configs"),
            step1Content
        )
        step1Dialog?.show()
    }

    /**
     * 1.101: the two-account picker box. Light red with "select
     * an account" until the user picks, light green once picked.
     * Minimal and compact. The caller rebuilds the container on
     * every pick.
     */
    private fun accountPickerBox(
        selected: Int,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val picked = selected >= 0
        val (fill, stroke, txt) = if (picked) {
            Triple("#E6F4EA", "#A3D9B1", "#14532D")
        } else {
            Triple("#FDECEC", "#E8AFAF", "#7A1F1F")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HydraUi.glassBg(
                Color.parseColor(fill), Color.parseColor(stroke), 14f,
                this@CloudflareActivity
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(8)) }
            addView(TextView(this@CloudflareActivity).apply {
                text = if (picked) {
                    L("اکانت ${selected + 1} انتخاب شد", "Account ${selected + 1} selected")
                } else {
                    L("اکانت را انتخاب کنید", "Select an account")
                }
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(txt))
                setPadding(0, 0, 0, dp(4))
            })
            for (i in 0..1) {
                addView(LinearLayout(this@CloudflareActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    addView(TextView(this@CloudflareActivity).apply {
                        text = if (i == selected) "●" else "○"
                        textSize = 18f
                        setTextColor(
                            if (i == selected) Color.parseColor("#2E9E5B")
                            else Color.parseColor("#9E9E9E")
                        )
                        setPadding(0, 0, dp(8), 0)
                    })
                    addView(TextView(this@CloudflareActivity).apply {
                        text = accountName(i)
                        textSize = 15f
                        setTextColor(Theme.fg(this@CloudflareActivity))
                    })
                    setOnClickListener { onPick(i) }
                })
            }
        }
    }

    private fun refreshStep1Picker(sel: Int) {
        step1PickerBox.removeAllViews()
        if (loadAccounts().size == 2) {
            step1PickerBox.visibility = View.VISIBLE
            step1PickerBox.addView(accountPickerBox(sel) { s ->
                step1PickerSel = s
                refreshStep1Picker(s)
            })
        } else {
            step1PickerBox.visibility = View.GONE
        }
    }

    /** 1.100: the merged step needs a panel that was
     *  connected ONCE (address + user + healthy IPs saved).
     *  The connect-and-build button runs the full pipeline,
     *  so by build time this is always true; the guard only
     *  stops a stray direct build call. */
    private fun panelReady(): Boolean {
        val panelUrl = prefs().getString("panel_url", "") ?: ""
        val user = prefs().getString("cf_panel_user", "") ?: ""
        if (panelUrl.isBlank() || user.isBlank()) return false
        return cleanIpsCache.ifEmpty { loadCleanIps() }.isNotEmpty()
    }

    private fun toggleStep1() = showStep1Dialog()

    /** Small minimal pill button for the results rows. */
    private fun miniButton(text: String, onClick: () -> Unit): Button {
        return HydraUi.glassButton(this, text, onClick).apply {
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), 0, dp(2), 0) }
        }
    }

    private fun paintConnectButton(btn: Button, state: String, onClick: () -> Unit) {
        btn.tag = state
        // 1.102: same compact size as the other row buttons.
        btn.textSize = 10f
        btn.setPadding(dp(6), dp(2), dp(6), dp(2))
        btn.minimumWidth = 0
        btn.minimumHeight = 0
        when (state) {
            "CONNECTED" -> {
                btn.text = L("متصل", "Connected")
                btn.background = HydraUi.glassBg(
                    Color.parseColor("#E6F4EA"),
                    Color.parseColor("#A3D9B1"), 28f, this
                )
                btn.setTextColor(Color.parseColor("#14532D"))
            }
            "CONNECTING" -> {
                btn.text = L("در حال اتصال…", "Connecting…")
                btn.background = HydraUi.glassBg(
                    Color.parseColor("#CCFFFFFF"),
                    Color.parseColor("#FFBFBFBF"), 28f, this
                )
                btn.setTextColor(Color.BLACK)
            }
            else -> {
                btn.text = L("اتصال", "Connect")
                btn.background = HydraUi.glassBg(
                    Color.parseColor("#CCFFFFFF"),
                    Color.parseColor("#FFBFBFBF"), 28f, this
                )
                btn.setTextColor(Color.BLACK)
            }
        }
        btn.setOnClickListener { onClick() }
    }

    /** Syncs every results-box connect button with the real VPN
     *  state: green "متصل" while exactly this config is connected
     *  (tapping it disconnects), "اتصال" otherwise. A button that
     *  is still "connecting" after 15s falls back to "اتصال". */
    private fun syncConnectButtons() {
        val running = try {
            VpnClientService.isRunning
        } catch (_: Exception) {
            false
        }
        val current = try {
            VpnClientService.currentName
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        for (i in results.indices) {
            val btn = connectBtns.getOrNull(i) ?: continue
            val code = results[i].second
            // A fresh disconnect wins over a still-dying
            // service: for a few seconds after the user taps
            // disconnect, the button stays IDLE even if the
            // service still reports running, so it can never
            // flip back to CONNECTED on its own (the
            // auto-reconnect the user reported).
            val desired = when {
                now < disconnectingUntil -> "IDLE"
                running && current == cfName(code) -> "CONNECTED"
                btn.tag == "CONNECTING" && now - connectTapTime < 15000 -> "CONNECTING"
                else -> "IDLE"
            }
            if (btn.tag == desired) continue
            when (desired) {
                "CONNECTED" -> paintConnectButton(btn, desired) {
                    // Same disconnect as the v2ray page: the
                    // STOP intent clears the running flags at
                    // once, so the poller cannot resurrect
                    // the button.
                    try {
                        val stop = Intent(this, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
                        try { startService(stop) } catch (_: Exception) {}
                    } catch (_: Exception) {
                    }
                    // 2.14: no stopService after STOP (same race
                    // as VpnClientService: a scheduled destroy
                    // racing a fast reconnect).
                    disconnectingUntil = System.currentTimeMillis() + 5000
                    toast(L("قطع شد.", "Disconnected."))
                    paintConnectButton(btn, "IDLE") { connectFromBox(i) }
                }
                "IDLE" -> paintConnectButton(btn, desired) { connectFromBox(i) }
                else -> {
                }
            }
        }
    }

    private fun connectFromBox(idx: Int) {
        // A fresh intentional connect cancels the guard.
        disconnectingUntil = 0
        val btn = connectBtns.getOrNull(idx) ?: return
        val (link, code) = results.getOrNull(idx) ?: return
        connectTapTime = System.currentTimeMillis()
        paintConnectButton(btn, "CONNECTING") { }
        connect(renamed(link, code))
    }

    // ------------------------------------------------------------------
    // Step 1: health check, save, green key, background work
    // ------------------------------------------------------------------

    /** Any VPN at all (ours or another app's) breaks the direct scan. */
    private fun isVpnOn(): Boolean {
        if (VpnClientService.isRunning) return true
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    /** Merged step-1 button (1.100): pick countries first,
     *  then one tap connects, refreshes the panel + healthy
     *  IPs, and builds configs for the selection. Re-tapping
     *  clears the panel's old proxies/IPs: the build writes
     *  exactly the new selection and the fresh scan. */
    private fun doConnectAndBuild() {
        if (bgRunning || buildBusy) {
            step1Status(L(
                "کاری در حال انجام است؛ کمی صبر کنید.",
                "Something is already running; please wait a moment."
            ))
            return
        }
        // 1.101: with two accounts the build needs an explicit
        // pick from the red/green picker box above.
        if (loadAccounts().size == 2) {
            if (step1PickerSel < 0) {
                step1Status(L("اول اکانت را انتخاب کنید.", "Select an account first."))
                return
            }
            if (step1PickerSel != activeIdx()) {
                activateAccount(step1PickerSel)
            }
        }
        val nopSelected = selectedCodes.contains("nop")
        val selected = COUNTRIES.filter { selectedCodes.contains(it.code) }
        if (nopSelected && selected.isNotEmpty()) {
            step1Status(L(
                "«بدون پروکسی» را به‌تنهایی انتخاب کن؛ ترکیب آن با کشورها ممکن نیست.",
                "Pick “No proxy” on its own; it cannot be combined with countries."
            ))
            return
        }
        if (!nopSelected && selected.isEmpty()) {
            step1Status(L("دست کم یک کشور انتخاب کنید.", "Select at least one country."))
            return
        }
        pendingNop = nopSelected
        pendingBuild = true
        step1Status(L("در حال اتصال و ساخت کانفیگ…", "Connecting and building configs…"))
        doConnect1()
    }

    private fun doConnect1() {
        if (step1Busy || bgRunning) return
        val email = emailEdit.text.toString().trim()
        val key = keyEdit.text.toString().trim()
        if (email.isBlank() || key.isBlank()) {
            setStatus(L("ایمیل و کلید گلوبال را وارد کنید.", "Enter the email and Global key."))
            return
        }
        if (isVpnOn()) {
            val w = L(
                "فیلترشکن روشن است؛ اول آن را خاموش کنید.",
                "A VPN is on; turn it off first."
            )
            setStatus(w)
            toast(w)
            logLineStr(w)
            return
        }
        step1Busy = true
        setStatus(L("در حال بررسی سلامت اتصال…", "Checking connection health…"))
        logLine("بررسی سلامت اتصال کلودفلر…", "Checking Cloudflare connection health…")
        bg {
            try {
                when (val r = CfDeployClient.health(email, key)) {
                    is CfDeployClient.Result.Ok -> {
                        prefs().edit()
                            .putString("cf_email", email)
                            .putString("cf_key", key)
                            .putBoolean("cf_step1_done", true)
                            .apply()
                        saveWorkingSetToActive()
                        runOnUiThread { refreshStepKeys() }
                        setStatus(L("اتصال سالم است و ذخیره شد.", "Connection is healthy and saved."))
                        logLine("اتصال سالم است و ذخیره شد.", "Connection is healthy and saved.")
                        runBackground()
                    }
                    is CfDeployClient.Result.Err -> {
                        val m = deployReason(r.code)
                        setStatus(m)
                        logLineStr(m)
                    }
                }
            } finally {
                step1Busy = false
            }
        }
    }

    private fun retryBackground() {
        if (bgRunning) return
        if (prefs().getString("cf_email", "")?.isBlank() != false ||
            prefs().getString("cf_key", "")?.isBlank() != false
        ) {
            setStatus(L("ایمیل و کلید گلوبال را وارد و «اتصال» را بزنید.",
                "Enter the email and Global key, then tap Connect."))
            return
        }
        runBackground()
    }

    private fun failBackground(msg: String) {
        bgFailed = true
        runOnUiThread { retryBgBtn.visibility = View.VISIBLE }
        step1Status(msg)
        setStatus(msg)
        logLineStr(msg)
    }

    private fun deployStepLabel(step: String): String {
        return when (step) {
            "account" -> L("حساب پیدا شد", "Account found")
            "subdomain" -> L("ساب‌دامین آماده است", "Subdomain ready")
            "d1" -> L("دیتابیس آماده شد", "Database ready")
            "table" -> L("جدول ساخته شد", "Table created")
            "upload" -> L("ورکر ساخته شد", "Worker built")
            "enable" -> L("آدرس workers.dev فعال شد", "workers.dev address enabled")
            "verify" -> L("اتصال دیتابیس تأیید شد", "Database binding verified")
            else -> step
        }
    }

    // ------------------------------------------------------------------
    // Step 1 background: deploy -> panel login -> random user ->
    // 10-IP scan -> push to the panel. Nothing here is ever shown
    // except step outcomes and counts (see the log contract above).
    // ------------------------------------------------------------------

    private fun runBackground() {
        if (bgRunning) return
        bgRunning = true
        bgFailed = false
        runOnUiThread { retryBgBtn.visibility = View.GONE }
        step1Status(L("در حال انجام کارهای پس‌زمینه…", "Running background tasks…"))
        bg {
            try {
                val email = prefs().getString("cf_email", "") ?: ""
                val key = prefs().getString("cf_key", "") ?: ""
                val script = try {
                    assets.open("nahan_worker.js").readBytes()
                } catch (_: Exception) {
                    null
                }
                if (script == null || script.isEmpty()) {
                    failBackground(L(
                        "فایل ورکر در برنامه پیدا نشد.",
                        "The worker file was not found in the app."))
                    return@bg
                }
                // 1. Deploy (idempotent: reuses database/worker by name).
                var panelUrl = ""
                when (val r = CfDeployClient.deploy(email, key, WORKER_NAME, DB_NAME, script) { step ->
                    logLineStr(deployStepLabel(step))
                }) {
                    is CfDeployClient.Result.Ok -> {
                        panelUrl = "https://" + r.data.panelHost
                        prefs().edit().putString("panel_url", panelUrl).apply()
                        logLine("پنل ساخته شد.", "Panel built.")
                    }
                    is CfDeployClient.Result.Err -> {
                        failBackground(deployReason(r.code))
                        return@bg
                    }
                }
                // 2. Panel login (automatic, background only).
                when (NahanClient.auth(panelUrl, PANEL_PASSWORD)) {
                    is NahanClient.Result.Ok ->
                        logLine("برنامه وارد پنل شد.", "App logged into the panel.")
                    is NahanClient.Result.Err -> {
                        failBackground(L(
                            "ورود به پنل ناموفق بود.",
                            "Panel login failed."))
                        return@bg
                    }
                }
                // 3. One random user (empty limits, expiry, notes).
                // A saved user is reused after verifying it still
                // exists, so re-running step 1 never duplicates users.
                val savedUser = prefs().getString("cf_panel_user", "") ?: ""
                var userName = savedUser
                var haveUser = false
                if (savedUser.isNotBlank()) {
                    when (val r = NahanClient.getUsers(panelUrl, PANEL_PASSWORD)) {
                        is NahanClient.Result.Ok ->
                            haveUser = r.data.any { it.name == savedUser }
                        else -> {
                        }
                    }
                }
                if (!haveUser) {
                    val rnd = "user-" + (1..6).map {
                        "abcdefghijklmnopqrstuvwxyz0123456789".random()
                    }.joinToString("")
                    when (NahanClient.createUser(panelUrl, PANEL_PASSWORD, rnd, 0.0, 0, "")) {
                        is NahanClient.Result.Ok -> {
                            userName = rnd
                            prefs().edit().putString("cf_panel_user", rnd).apply()
                            logLine("یک کاربر ساخته شد.", "A user was created.")
                        }
                        is NahanClient.Result.Err -> {
                            failBackground(L(
                                "ساخت کاربر در پنل ناموفق بود.",
                                "Creating the panel user failed."))
                            return@bg
                        }
                    }
                } else {
                    userName = savedUser
                    logLine(
                        "از کاربر ساخته‌شده‌ی قبلی استفاده شد.",
                        "The previously created user is reused."
                    )
                }
                // 4. Scan 10 healthy Cloudflare IPs (direct, no VPN).
                logLine("در حال اسکن آی‌پی‌های کلودفلر…", "Scanning Cloudflare IPs…")
                val host = panelUrl.removePrefix("https://").removePrefix("http://")
                    .substringBefore("/")
                val found = scanCleanIps(host)
                if (found.isEmpty()) {
                    failBackground(L(
                        "اسکن آی‌پی چیزی پیدا نکرد؛ فیلترشکن را خاموش و دوباره تلاش کنید.",
                        "The IP scan found nothing; turn the VPN off and retry."))
                    return@bg
                }
                logLine(
                    "اسکن کامل شد؛ ${found.size} آی‌پی سالم پیدا شد.",
                    "Scan finished; ${found.size} healthy IPs found."
                )
                // 5. Push the scanned IPs into the panel.
                when (NahanClient.syncConfig(
                    panelUrl, PANEL_PASSWORD,
                    JSONObject().put("cleanIps", NahanClient.joinIpList(found))
                )) {
                    is NahanClient.Result.Ok -> {
                        logLine("آی‌پی‌ها به پنل ارسال شد.", "IPs pushed to the panel.")
                        try {
                            prefs().edit().putString("cf_clean_ips", org.json.JSONArray(found).toString()).apply()
                            cleanIpsCache = found
                        } catch (_: Exception) {}
                    }
                    is NahanClient.Result.Err -> {
                        failBackground(L(
                            "ارسال آی‌پی‌ها به پنل ناموفق بود.",
                            "Pushing IPs to the panel failed."))
                        return@bg
                    }
                }
                prefs().edit().putBoolean("cf_bg_done", true).apply()
                saveWorkingSetToActive()
                step1Status(L(
                    "مرحله ۱ کامل شد؛ ساخت کانفیگ‌ها شروع می‌شود.",
                    "Step 1 is complete; building configs next."))
                setStatus(L(
                    "مرحله ۱ کامل شد؛ ساخت کانفیگ‌ها شروع می‌شود.",
                    "Step 1 is complete; building configs next."))
                // 1.100: the merged button asked for a build
                // after the setup — run it now (it re-checks
                // panelReady and its own busy guard).
                if (pendingBuild) {
                    pendingBuild = false
                    if (pendingNop) doNopBuild() else doBuildConfigs()
                }
                logLine("مرحله ۱ کامل شد.", "Step 1 complete.")
            } finally {
                bgRunning = false
            }
        }
    }

    /** HTTPS to a raw edge IP with SNI + cert verification for the
     *  panel host (layered socket: the host parameter drives both).
     *  Returns the response body, or null. */
    private fun probeViaIp(host: String, ip: String, path: String, timeoutMs: Int): String? {
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(ip, 443), timeoutMs)
            plain.soTimeout = timeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, host, 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray(Charsets.UTF_8))
            ssl.outputStream.flush()
            val raw = ssl.inputStream.readBytes().toString(Charsets.UTF_8)
            try {
                ssl.close()
            } catch (_: Exception) {
            }
            var body = raw.substringAfter("\r\n\r\n", "")
            if (body.isBlank()) return null
            // Minimal chunked-transfer decoding, if the edge used it.
            if (raw.substringBefore("\r\n\r\n", "").contains("chunked", ignoreCase = true)) {
                val out = StringBuilder()
                var rest = body
                while (true) {
                    val nl = rest.indexOf("\r\n")
                    if (nl < 0) break
                    val size = rest.substring(0, nl).trim().toIntOrNull(16) ?: break
                    if (size == 0) break
                    val start = nl + 2
                    if (rest.length < start + size) break
                    out.append(rest.substring(start, start + size))
                    rest = rest.substring(start + size).removePrefix("\r\n")
                }
                body = out.toString()
            }
            if (body.isBlank()) null else body
        } catch (_: Exception) {
            null
        }
    }

    /** Decodes a Nahan subscription body (single-line base64 or
     *  plain links) into links, the same way the panel client does. */
    private fun decodeSubLinks(body: String): List<String> {
        var b = body.trim()
        if (!b.contains("://") && !b.startsWith("{") && !b.startsWith("[")) {
            try {
                var t = b.replace('-', '+').replace('_', '/')
                while (t.length % 4 != 0) t += "="
                b = String(
                    android.util.Base64.decode(t, android.util.Base64.DEFAULT),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                return emptyList()
            }
        }
        return b.lines().map { it.trim() }.filter { it.contains("://") }
    }

    /** Parallel clean-IP scan: probes Cloudflare edge candidates
     *  through the panel's public subscription URL, keeps the first
     *  10 that actually serve it. Same scanner idea as the old
     *  section, pointed at the Nahan subscription. */
    private fun scanCleanIps(host: String): List<String> {
        val candidates = try {
            CdnPools.candidates("cloudflare", 105)
        } catch (_: Exception) {
            emptyList()
        }
        val found = Collections.synchronizedList(mutableListOf<String>())
        val index = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val deadline = System.currentTimeMillis() + 60000
        repeat(8) {
            pool.submit {
                while (System.currentTimeMillis() < deadline && found.size < 15) {
                    val i = index.getAndIncrement()
                    if (i >= candidates.size) break
                    val ip = candidates[i]
                    val body = try {
                        probeViaIp(host, ip, "/sync", 5000)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (decodeSubLinks(body).isNotEmpty()) found.add(ip)
                }
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(65, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        try {
            pool.shutdownNow()
        } catch (_: Exception) {
        }
        return found.distinct()
    }


    /** All picker countries: the fixed ones first, then scanned
     *  ones, capped at 15 total. */
    /** Countries the removed (1.97) proxy scanner had saved,
     *  kept read-only: their proxies stay in the canonical
     *  relay list so configs built from them keep routing
     *  to the same country, and their names still display.
     *  Nothing writes this list anymore. */
    private fun legacyCountries(): List<Country> {
        val out = mutableListOf<Country>()
        try {
            val arr = org.json.JSONArray(
                prefs().getString("cf_proxy_countries", "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("code", "")
                val proxy = o.optString("proxy", "")
                val en = o.optString("en", "")
                val flag = o.optString("flag", "")
                if (code.isBlank() || proxy.isBlank()) continue
                out += Country(
                    code, FA_NAMES[code] ?: en.ifBlank { code },
                    en.ifBlank { code }, flag.ifBlank { "\uD83C\uDF10" }, proxy
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun countryForCode(code: String): Country? =
        if (code == "nop") NOP
        else (COUNTRIES + legacyCountries()).firstOrNull { it.code == code }


    /** Picker entries: fixed countries + the no-proxy option. */
    private fun pickerItems(): List<Country> = COUNTRIES + NOP

    /** Flag emoji (two regional indicators) -> ISO code. */
    private fun flagToCode(flag: String): String? {
        val points = flag.codePoints().toArray()
        if (points.size != 2) return null
        val a = points[0] - 0x1F1E6
        val b = points[1] - 0x1F1E6
        if (a !in 0..25 || b !in 0..25) return null
        return "${('a' + a)}${('b' + b)}"
    }

    /** Code + flag + English name from a panel-generated config
     *  name ("flag Country - VLESS 443"). */
    private fun panelCountryInfo(link: String): Triple<String, String, String>? {
        val frag = link.substringAfter("#", "")
        if (frag.isBlank()) return null
        val name = try {
            java.net.URLDecoder.decode(frag, "UTF-8")
        } catch (_: Exception) {
            frag
        }
        val prefix = name.substringBefore(" - ", "").trim()
        if (prefix.isEmpty()) return null
        val flagEnd = prefix.indexOf(' ')
        if (flagEnd < 0) return null
        val flag = prefix.substring(0, flagEnd)
        val code = flagToCode(flag) ?: return null
        val en = prefix.substring(flagEnd).trim()
        if (en.isEmpty()) return null
        return Triple(code, flag, en)
    }

    /** Relay index (configIndex) of a built panel link.
     *  1.100 ROOT CAUSE (proven from the panel source):
     *  VLESS links carry NO payload in path= — that param
     *  is just "/sync" (the API route). The index is the
     *  TAIL of the UUID: generateConfigUuid appends
     *  configIndex as 8 hex chars. Trojan links DO carry
     *  the payload in path=, so they keep the path parse.
     *  The panel's proxy list is whole blocks of the raw
     *  list (raw, then one NAT64 block per prefix), so
     *  index % raw count — what linkCountry does — is the
     *  correct country. */
    private fun relayIdxOf(link: String): Int? {
        if (link.startsWith("vless://")) {
            return try {
                val uuid = link.substringAfter("://").substringBefore("@")
                val clean = uuid.replace("-", "").lowercase()
                if (clean.length != 32 ||
                    !clean.all { it.isDigit() || it in 'a'..'f' }
                ) {
                    return null
                }
                val v = clean.substring(24, 32).toLongOrNull(16) ?: return null
                if (v < 0) null else v.toInt()
            } catch (_: Exception) {
                null
            }
        }
        return try {
            val q = link.substringAfter("?", "")
            val pathParam = q.split("&").firstOrNull { it.startsWith("path=") }
                ?: return null
            val enc = pathParam.substringAfter("=")
            val raw = StringBuilder()
            var i = 0
            while (i < enc.length) {
                val ch = enc[i]
                if (ch == '%' && i + 2 < enc.length) {
                    val v = enc.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        raw.append(v.toChar())
                        i += 3
                        continue
                    }
                }
                raw.append(ch)
                i++
            }
            val b64 = raw.toString().removePrefix("/")
            val bytes = try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                android.util.Base64.decode(b64, android.util.Base64.URL_SAFE)
            }
            val json = String(bytes, Charsets.UTF_8)
            org.json.JSONObject(json).optInt("relayIdx", -1).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** Country code a built link actually relays through: the
     *  relay index mapped onto the given proxy list
     *  (routing truth; the index is modulo the list size because
     *  the panel's proxy variants come in whole blocks of the
     *  list), falling back to the panel's geo name. */
    private fun linkCountry(link: String, proxies: List<String>): String? {
        val idx = relayIdxOf(link)
        if (idx != null && proxies.isNotEmpty()) {
            val proxy = proxies[idx % proxies.size]
            (COUNTRIES + legacyCountries()).firstOrNull { it.proxy == proxy }?.let { return it.code }
        }
        return panelCountryInfo(link)?.first
    }

    private fun linkServer(link: String): String {
        return try {
            val afterAt = link.substringAfter("@", "")
            val hostPort = afterAt.substringBefore("?", afterAt)
            val host = hostPort.substringBefore(":")
            if (host.startsWith("[")) host.substringBefore("]") + "]" else host
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadCleanIps(): List<String> {
        return try {
            val arr = org.json.JSONArray(
                prefs().getString("cf_clean_ips", "[]") ?: "[]"
            )
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out += arr.optString(i, "")
            out.filter { it.isNotBlank() }.also { cleanIpsCache = it }
        } catch (_: Exception) {
            cleanIpsCache
        }
    }

    /** 3 countries per row; each checkbox is a distinct view bound
     *  to one country code, so ticks can never mix up. */
    private fun rebuildCountryGrid() {
        if (!::countryGrid.isInitialized) return
        countryGrid.removeAllViews()
        val list = pickerItems()
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (j in 0 until 3) {
                val c = list.getOrNull(i + j)
                if (c == null) {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    })
                } else {
                    row.addView(CheckBox(this).apply {
                        text = if (c.flag.isBlank()) L(c.fa, c.en) else "${c.flag} ${L(c.fa, c.en)}"
                        textSize = 14f
                        setTextColor(Theme.fg(this@CloudflareActivity))
                        isChecked = selectedCodes.contains(c.code)
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        setOnCheckedChangeListener { _, on ->
                            if (on) selectedCodes.add(c.code)
                            else selectedCodes.remove(c.code)
                        }
                    })
                }
            }
            countryGrid.addView(row)
            i += 3
        }
    }

    /** A tighter row for the compact results cards. */
    private fun tightRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
    }


    // ------------------------------------------------------------------
    // Step 2: one config per selected country
    // ------------------------------------------------------------------

    /** 1.97: proxy-free build. Writes an EMPTY relay list
     *  (the sync spread-merge clears backupRelay + customRelay,
     *  so the panel generates direct configs), then fetches with
     *  the same 11s retry rhythm and takes the first vless per
     *  healthy clean IP, capped at 15. Panel-state note: the
     *  panel holds ONE relay list, so after this build the
     *  previously built country configs route direct until
     *  countries are built again (and vice versa) — the guide
     *  says so plainly, and the status line repeats it. */
    private fun doNopBuild() {
        if (buildBusy) return
        if (!panelReady()) {
            setStatus(L("اول مرحله ۱ را کامل کنید تا پنل و آی‌پی‌های سالم آماده شود.", "Complete step 1 first so the panel and healthy IPs are ready."))
            return
        }
        buildBusy = true
        step1Status(L("در حال ساخت کانفیگ‌های بدون پروکسی…", "Building proxy-free configs…"))
        logLine("ساخت کانفیگ بدون پروکسی.", "Building proxy-free configs.")
        bg {
            try {
                val panelUrl = prefs().getString("panel_url", "") ?: ""
                val user = prefs().getString("cf_panel_user", "") ?: ""
                if (panelUrl.isBlank() || user.isBlank()) {
                    step1Status(L(
                        "اطلاعات پنل کامل نیست؛ مرحله ۱ را دوباره انجام دهید.",
                        "Panel info is incomplete; redo step 1."))
                    return@bg
                }
                val clean = cleanIpsCache.ifEmpty { loadCleanIps() }
                if (clean.isEmpty()) {
                    step1Status(L(
                        "آی‌پی سالمی پیدا نشده؛ مرحله ۱ را دوباره انجام دهید.",
                        "No healthy IP was found; redo step 1."))
                    return@bg
                }
                val want = clean.take(15)
                // 1.98: reopen the panel in the background on
                // every build, so the new proxies are entered
                // even if step 1 was tapped long ago.
                when (NahanClient.auth(panelUrl, PANEL_PASSWORD)) {
                    is NahanClient.Result.Ok ->
                        logLine("پنل در پس‌زمینه باز شد.", "Panel reopened in the background.")
                    is NahanClient.Result.Err -> {
                        step1Status(L(
                            "ارتباط با پنل برقرار نشد؛ مرحله ۱ را دوباره انجام دهید.",
                            "Could not reach the panel; redo step 1."))
                        return@bg
                    }
                }
                val fields = JSONObject()
                    .put("nat64Prefix", NAT64_PREFIXES.joinToString("\n"))
                    .put("backupRelay", "")
                    .put("customRelay", "")
                    .put("nameStrategy", COUNTRY_TEMPLATE)
                when (NahanClient.syncConfig(panelUrl, PANEL_PASSWORD, fields)) {
                    is NahanClient.Result.Ok ->
                        logLine("رله‌های پنل خالی شد.", "Panel relays emptied.")
                    is NahanClient.Result.Err -> {
                        step1Status(L("خالی کردن رله‌ها ناموفق بود.", "Emptying the relays failed."))
                        return@bg
                    }
                }
                var lastByIp = mapOf<String, String>()
                var covered: Map<String, String>? = null
                var attempts = 0
                while (attempts < 9 && covered == null) {
                    attempts++
                    val links = fetchSubLinks(panelUrl, user)
                    if (links != null) {
                        val byIp = mutableMapOf<String, String>()
                        for (link in links) {
                            if (!link.startsWith("vless://")) continue
                            val srv = linkServer(link)
                            if (srv in want && !byIp.containsKey(srv)) byIp[srv] = link
                        }
                        lastByIp = byIp
                        if (want.all { byIp.containsKey(it) }) covered = byIp
                        else if (attempts < 9) {
                            logLine(
                                "اشتراک: ${links.size} لینک؛ برای ${byIp.size} آی‌پی از ${want.size} آی‌پی کانفیگ پیدا شد.",
                                "Subscription: ${links.size} links; configs found for ${byIp.size} of ${want.size} IPs."
                            )
                            if (byIp.isEmpty()) {
                                when (NahanClient.syncConfig(panelUrl, PANEL_PASSWORD, fields)) {
                                    is NahanClient.Result.Ok ->
                                        logLine("پروکسی‌ها دوباره وارد پنل شد.", "Relays re-entered into the panel.")
                                    else -> {}
                                }
                            } else {
                                logLine("پنل هنوز به‌روز نشده؛ دوباره تلاش می‌شود.", "Panel not updated yet; retrying.")
                            }
                            try { Thread.sleep(11000) } catch (_: Exception) {}
                        }
                    } else if (attempts < 9) {
                        logLine("گرفتن اشتراک ناموفق بود؛ دوباره تلاش می‌شود.", "Fetching the subscription failed; retrying.")
                        try { Thread.sleep(11000) } catch (_: Exception) {}
                    }
                }
                val byIp = covered ?: lastByIp
                val built = want.mapNotNull { ip -> byIp[ip]?.let { it to "nop" } }
                if (built.isEmpty()) {
                    step1Status(L("هیچ کانفیگی ساخته نشد.", "No configs were built."))
                    logLine("هیچ کانفیگی ساخته نشد.", "No configs were built.")
                    return@bg
                }
                results.clear()
                results.addAll(built)
                saveResults()
                prefs().edit().putBoolean("cf_step2_done", true).apply()
                saveWorkingSetToActive()
                runOnUiThread {
                    refreshStepKeys()
                    renderResults()
                    step1Dialog?.dismiss()
                }
                step1Status(L("${built.size} کانفیگ بدون پروکسی ساخته شد.", "${built.size} proxy-free configs built."))
                setStatus(L(
                    "${built.size} کانفیگ بدون پروکسی ساخته شد؛ توجه: رله پنل خالی شد و کانفیگ‌های کشوریِ قبلی تا ساخت دوباره کشورها بدون پروکسی وصل می‌شوند.",
                    "${built.size} proxy-free configs built. Note: the panel relays are now empty, so older country configs will connect proxy-free until countries are built again."))
                logLine("${built.size} کانفیگ بدون پروکسی ساخته شد.", "${built.size} proxy-free configs built.")
            } finally {
                buildBusy = false
            }
        }
    }

    private fun doBuildConfigs() {
        if (buildBusy) return
        if (!panelReady()) {
            setStatus(L("اول مرحله ۱ را کامل کنید تا پنل و آی‌پی‌های سالم آماده شود.", "Complete step 1 first so the panel and healthy IPs are ready."))
            return
        }
        val selected = COUNTRIES.filter { selectedCodes.contains(it.code) }
        val nopSelected = selectedCodes.contains("nop")
        if (nopSelected && selected.isNotEmpty()) {
            setStatus(L("«بدون پروکسی» را به‌تنهایی انتخاب کن؛ ترکیب آن با کشورها ممکن نیست.", "Pick “No proxy” on its own; it cannot be combined with countries."))
            return
        }
        if (nopSelected) {
            doNopBuild()
            return
        }
        if (selected.isEmpty()) {
            setStatus(L("دست کم یک کشور انتخاب کنید.", "Select at least one country."))
            return
        }
        buildBusy = true
        step1Status(L("در حال ساخت کانفیگ‌ها…", "Building configs…"))
        logLine(
            "ساخت کانفیگ برای: " + selected.joinToString("، ") { it.fa },
            "Building configs for: " + selected.joinToString(", ") { it.en }
        )
        bg {
            try {
                val panelUrl = prefs().getString("panel_url", "") ?: ""
                val user = prefs().getString("cf_panel_user", "") ?: ""
                if (panelUrl.isBlank() || user.isBlank()) {
                    step1Status(L(
                        "اطلاعات پنل کامل نیست؛ مرحله ۱ را دوباره انجام دهید.",
                        "Panel info is incomplete; redo step 1."))
                    return@bg
                }
                // ONE write: NAT64 + exactly the SELECTED
                // countries' proxies + a country-name template.
                // 1.100 (user order): re-tapping clears the
                // panel's old proxies — the panel holds only
                // the latest selection. NOTE: older country
                // configs imported into v2ray re-route through
                // the new selection after such a rebuild; the
                // template lets the app attribute each fetched
                // config to its country from the panel's own
                // geo (panel names are never shown; the app
                // renames anyway).
                // 1.98: reopen the panel in the background on
                // every build, so the new proxies are entered
                // even if step 1 was tapped long ago.
                when (NahanClient.auth(panelUrl, PANEL_PASSWORD)) {
                    is NahanClient.Result.Ok ->
                        logLine("پنل در پس‌زمینه باز شد.", "Panel reopened in the background.")
                    is NahanClient.Result.Err -> {
                        step1Status(L(
                            "ارتباط با پنل برقرار نشد؛ مرحله ۱ را دوباره انجام دهید.",
                            "Could not reach the panel; redo step 1."))
                        return@bg
                    }
                }
                val selectedProxies = selected.map { it.proxy }
                val fields = JSONObject()
                    .put("nat64Prefix", NAT64_PREFIXES.joinToString("\n"))
                    .put("backupRelay", selectedProxies.joinToString("\n"))
                    .put("nameStrategy", COUNTRY_TEMPLATE)
                when (NahanClient.syncConfig(panelUrl, PANEL_PASSWORD, fields)) {
                    is NahanClient.Result.Ok ->
                        logLine("مقادیر NAT64 و پروکسی‌ها تنظیم شد.", "NAT64 values and proxies set.")
                    is NahanClient.Result.Err -> {
                        step1Status(L(
                            "تنظیم پروکسی و NAT64 ناموفق بود.",
                            "Setting proxies and NAT64 failed."))
                        return@bg
                    }
                }
                // Fetch with retries: the worker caches sysConfig for
                // 10s per isolate, so a fetch right after the write
                // can still see the PREVIOUS relays/template (this
                // is what used to make every country return the same
                // first config, which the import then deduped to
                // one). Retry 11s apart — past the cache — until
                // every selected country is covered.
                val wanted = selected.map { it.code }
                // Healthy clean IPs, for spreading the
                // countries across them (see below).
                val clean = cleanIpsCache.ifEmpty { loadCleanIps() }
                var lastByCountry = mapOf<String, String>()
                var covered: Map<String, String>? = null
                var attempts = 0
                while (attempts < 9 && covered == null) {
                    attempts++
                    val links = fetchSubLinks(panelUrl, user)
                    if (links != null) {
                        val grouped = mutableMapOf<String, MutableList<String>>()
                        for (link in links) {
                            if (!link.startsWith("vless://")) continue
                            val cc = linkCountry(link, selectedProxies) ?: continue
                            if (cc in wanted) grouped.getOrPut(cc) { mutableListOf() } += link
                        }
                        // Spread countries across the healthy
                        // clean IPs: country i prefers the
                        // config whose server is clean[i %
                        // clean.size]. So 14 countries over 7
                        // healthy IPs give 2 configs per IP;
                        // with 15 healthy IPs each country
                        // gets its own. Falls back to the
                        // first config of the country.
                        val byCountry = mutableMapOf<String, String>()
                        for ((i, c) in selected.withIndex()) {
                            val list = grouped[c.code] ?: continue
                            val wantServer = if (clean.isNotEmpty()) clean[i % clean.size] else null
                            val pick = wantServer?.let { s ->
                                list.firstOrNull { linkServer(it) == s }
                            } ?: list.firstOrNull()
                            if (pick != null) byCountry[c.code] = pick
                        }
                        lastByCountry = byCountry
                        if (wanted.all { byCountry.containsKey(it) }) {
                            covered = byCountry
                        } else if (attempts < 9) {
                            logLine(
                                "اشتراک: ${links.size} لینک؛ برای ${byCountry.size} کشور از ${wanted.size} کشور کانفیگ پیدا شد.",
                                "Subscription: ${links.size} links; configs found for ${byCountry.size} of ${wanted.size} countries."
                            )
                            if (byCountry.isEmpty()) {
                                // Nothing attributed at all: the panel
                                // may not have applied the relay write
                                // yet — re-enter the proxies, then wait
                                // past the 10s isolate cache.
                                when (NahanClient.syncConfig(panelUrl, PANEL_PASSWORD, fields)) {
                                    is NahanClient.Result.Ok ->
                                        logLine("پروکسی‌ها دوباره وارد پنل شد.", "Relays re-entered into the panel.")
                                    else -> {}
                                }
                            } else {
                                logLine("پنل هنوز به‌روز نشده؛ دوباره تلاش می‌شود.", "Panel not updated yet; retrying.")
                            }
                            try {
                                Thread.sleep(11000)
                            } catch (_: Exception) {
                            }
                        }
                    } else if (attempts < 9) {
                        logLine("گرفتن اشتراک ناموفق بود؛ دوباره تلاش می‌شود.", "Fetching the subscription failed; retrying.")
                        try {
                            Thread.sleep(11000)
                        } catch (_: Exception) {
                        }
                    }
                }
                val byCountry = covered ?: lastByCountry
                val built = selected.mapNotNull { c ->
                    byCountry[c.code]?.let { it to c.code }
                }
                if (built.isEmpty()) {
                    step1Status(L("هیچ کانفیگی ساخته نشد.", "No configs were built."))
                    logLine("هیچ کانفیگی ساخته نشد.", "No configs were built.")
                    return@bg
                }
                if (built.size < selected.size) {
                    val missing = selected.filter { c -> !byCountry.containsKey(c.code) }
                    logLine(
                        "برای این کشورها کانفیگی ساخته نشد: " + missing.joinToString("، ") { it.fa },
                        "No config was built for: " + missing.joinToString(", ") { it.en }
                    )
                }
                results.clear()
                results.addAll(built)
                saveResults()
                prefs().edit().putBoolean("cf_step2_done", true).apply()
                saveWorkingSetToActive()
                runOnUiThread {
                    refreshStepKeys()
                    renderResults()
                    step1Dialog?.dismiss()
                }
                step1Status(L("${built.size} کانفیگ ساخته شد.", "${built.size} configs built."))
                setStatus(L("${built.size} کانفیگ ساخته شد.", "${built.size} configs built."))
                logLine("${built.size} کانفیگ ساخته شد.", "${built.size} configs built.")
                AppLog.append(this, "cloudflare",
                    "${built.size} کانفیگ ساخته شد.",
                    "${built.size} configs built.")
            } finally {
                buildBusy = false
            }
        }
    }
    /** Subscription links: the user's own, falling back to default. */
    private fun fetchSubLinks(panelUrl: String, user: String): List<String>? {
        when (val r = NahanClient.fetchSubscription(NahanClient.userSubUrl(panelUrl, user))) {
            is NahanClient.Result.Ok -> return r.data
            else -> {
            }
        }
        when (val r = NahanClient.fetchSubscription(NahanClient.defaultSubUrl(panelUrl))) {
            is NahanClient.Result.Ok -> return r.data
            else -> return null
        }
    }

    // ------------------------------------------------------------------
    // Names, results box, ping, import, connect
    // ------------------------------------------------------------------

    /** Display name in both sections, in the current language:
     *  "Cloudflare - country". */
    // ------------------------------------------------------------------
    // Account management (1.99): up to two Cloudflare accounts.
    // The live state stays in the working-set prefs; each account
    // also keeps a snapshot in "cf_accounts" (index 0 = اکانت اول).
    // Switching saves the outgoing snapshot and loads the incoming
    // one, so each account's built configs are shown on its own.
    // ------------------------------------------------------------------

    private fun accountName(i: Int): String = L("اکانت ${i + 1}", "Account ${i + 1}")

    /** 1.102: the account label appears ONLY once a second
     *  account exists; with a single account the section shows
     *  no account label at all. */
    private fun accountSuffix(): String =
        if (loadAccounts().size >= 2) L(" — اکانت ${activeIdx() + 1}", " — Account ${activeIdx() + 1}") else ""

    private fun loadAccounts(): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val arr = JSONArray(prefs().getString("cf_accounts", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                out += arr.optJSONObject(i) ?: JSONObject()
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveAccounts(list: List<JSONObject>) {
        try {
            val arr = JSONArray()
            for (o in list) arr.put(o)
            prefs().edit().putString("cf_accounts", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun activeIdx(): Int {
        val n = loadAccounts().size
        val i = prefs().getInt("cf_active_account", 0)
        return if (n == 0) 0 else i.coerceIn(0, n - 1)
    }

    private fun snapshotOfWorkingSet(): JSONObject {
        return JSONObject()
            .put("email", prefs().getString("cf_email", "") ?: "")
            .put("key", prefs().getString("cf_key", "") ?: "")
            .put("panel_url", prefs().getString("panel_url", "") ?: "")
            .put("panel_user", prefs().getString("cf_panel_user", "") ?: "")
            .put("clean_ips", prefs().getString("cf_clean_ips", "[]") ?: "[]")
            .put("results", prefs().getString("cf_results", "[]") ?: "[]")
            .put("step1_done", prefs().getBoolean("cf_step1_done", false))
            .put("bg_done", prefs().getBoolean("cf_bg_done", false))
            .put("step2_done", prefs().getBoolean("cf_step2_done", false))
            .put("proxy_link", prefs().getString("cf_proxy_link", "") ?: "")
            .put("proxy_worker", prefs().getString("cf_proxy_worker", "") ?: "")
    }

    private fun saveWorkingSetToAccount(idx: Int) {
        val list = loadAccounts()
        if (idx < 0 || idx >= list.size) return
        list[idx] = snapshotOfWorkingSet()
        saveAccounts(list)
    }

    private fun saveWorkingSetToActive() = saveWorkingSetToAccount(activeIdx())

    private fun loadAccountToWorkingSet(idx: Int) {
        val list = loadAccounts()
        if (idx < 0 || idx >= list.size) return
        val o = list[idx]
        prefs().edit()
            .putString("cf_email", o.optString("email", ""))
            .putString("cf_key", o.optString("key", ""))
            .putString("panel_url", o.optString("panel_url", ""))
            .putString("cf_panel_user", o.optString("panel_user", ""))
            .putString("cf_clean_ips", o.optString("clean_ips", "[]"))
            .putString("cf_results", o.optString("results", "[]"))
            .putBoolean("cf_step1_done", o.optBoolean("step1_done", false))
            .putBoolean("cf_bg_done", o.optBoolean("bg_done", false))
            .putBoolean("cf_step2_done", o.optBoolean("step2_done", false))
            .putString("cf_proxy_link", o.optString("proxy_link", ""))
            .putString("cf_proxy_worker", o.optString("proxy_worker", ""))
            .apply()
        cleanIpsCache = try {
            val arr = JSONArray(o.optString("clean_ips", "[]"))
            (0 until arr.length()).map { arr.optString(it, "") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun migrateAccounts() {
        if (prefs().contains("cf_accounts")) return
        val email = prefs().getString("cf_email", "") ?: ""
        if (email.isBlank()) {
            prefs().edit()
                .putString("cf_accounts", "[]")
                .putInt("cf_active_account", 0)
                .apply()
            return
        }
        saveAccounts(mutableListOf(snapshotOfWorkingSet()))
        prefs().edit().putInt("cf_active_account", 0).apply()
    }

    private fun refreshAccountViews() {
        if (::emailEdit.isInitialized) {
            emailEdit.setText(prefs().getString("cf_email", "") ?: "")
            keyEdit.setText(prefs().getString("cf_key", "") ?: "")
        }
        refreshStepKeys()
    }

    private fun showAccountDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val accounts = loadAccounts()
        val active = activeIdx()
        for ((i, acc) in accounts.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = if (i == active) {
                    HydraUi.glassBg(
                        Color.parseColor("#E6F4EA"),
                        Color.parseColor("#A3D9B1"), 14f, this@CloudflareActivity
                    )
                } else {
                    HydraUi.glassBg(
                        Theme.card(this@CloudflareActivity),
                        Theme.divider(this@CloudflareActivity), 14f, this@CloudflareActivity
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(8)) }
            }
            val dot = TextView(this).apply {
                text = if (i == active) "●" else "○"
                textSize = 18f
                setTextColor(
                    if (i == active) Color.parseColor("#2E9E5B")
                    else Color.parseColor("#9E9E9E")
                )
                setPadding(0, 0, dp(8), 0)
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val name = TextView(this).apply {
                text = accountName(i)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.fg(this@CloudflareActivity))
            }
            val mail = TextView(this).apply {
                text = acc.optString("email", "")
                textSize = 13f
                setTextColor(Theme.subFg(this@CloudflareActivity))
            }
            texts.addView(name)
            texts.addView(mail)
            row.addView(dot)
            row.addView(texts)
            row.setOnClickListener { trySwitchAccount(i) }
            content.addView(row)
        }
        if (accounts.size < 2) {
            content.addView(hintText(L(
                "اکانت تازه با ایمیل و کلید گلوبال اضافه می‌شود؛ سقف دو اکانت است.",
                "A new account is added with its email and Global key; the limit is two accounts."
            )))
            mgmtEmailEdit = edit(
                L("ایمیل حساب کلودفلر", "Cloudflare account email"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
            content.addView(mgmtEmailEdit)
            mgmtKeyEdit = edit(
                L("کلید گلوبال دریافتی", "Received Global key"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            content.addView(mgmtKeyEdit)
            content.addView(HydraUi.glassButton(this, L("اتصال", "Connect")) { addAccount() })
            mgmtStatusView = TextView(this).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@CloudflareActivity))
                setPadding(0, dp(6), 0, dp(4))
            }
            content.addView(mgmtStatusView)
        } else {
            content.addView(hintText(L(
                "حداکثر دو اکانت اضافه شده است.",
                "Maximum of two accounts."
            )))
        }
        mgmtDialog?.dismiss()
        mgmtDialog = makeDialog(L("مدیریت اکانت", "Account management"), content)
        mgmtDialog?.show()
    }

    /** Core account switch (no dialog rebuild): save the outgoing
     *  snapshot, load the incoming one, refresh views + results. */
    private fun activateAccount(i: Int) {
        val accounts = loadAccounts()
        if (i < 0 || i >= accounts.size) return
        if (i == activeIdx()) return
        saveWorkingSetToActive()
        prefs().edit().putInt("cf_active_account", i).apply()
        loadAccountToWorkingSet(i)
        refreshAccountViews()
        loadResults()
        renderResults()
    }

    private fun trySwitchAccount(i: Int) {
        if (bgRunning) {
            toast(L(
                "کارهای پس‌زمینه در حال انجام است؛ کمی صبر کنید.",
                "Background tasks are running; please wait a moment."
            ))
            return
        }
        val before = activeIdx()
        activateAccount(i)
        if (activeIdx() == before) return
        logLine(
            "به ${accountName(i)} سوییچ شد.",
            "Switched to ${accountName(i)}."
        )
        // Rebuild the dialog so the selection dot moves.
        showAccountDialog()
    }

    private fun addAccount() {
        if (mgmtBusy || bgRunning) {
            toast(L(
                "کارهای پس‌زمینه در حال انجام است؛ کمی صبر کنید.",
                "Background tasks are running; please wait a moment."
            ))
            return
        }
        val email = mgmtEmailEdit.text.toString().trim()
        val key = mgmtKeyEdit.text.toString().trim()
        val statusView = mgmtStatusView
        fun msg(s: String) {
            runOnUiThread { statusView?.text = s }
        }
        if (email.isBlank() || key.isBlank()) {
            msg(L("ایمیل و کلید را وارد کنید.", "Enter the email and key."))
            return
        }
        val accounts = loadAccounts()
        if (accounts.size >= 2) {
            msg(L("حداکثر دو اکانت اضافه شده است.", "Maximum of two accounts."))
            return
        }
        if (accounts.any { it.optString("email", "").equals(email, ignoreCase = true) }) {
            msg(L("این اکانت قبلاً اضافه شده است.", "This account is already added."))
            return
        }
        if (isVpnOn()) {
            msg(L(
                "برای ساخت پنل و اسکن آی‌پی، اول فیلترشکن را خاموش کنید.",
                "Turn the VPN off first so the panel can be built and IPs scanned."
            ))
            return
        }
        mgmtBusy = true
        msg(L("در حال بررسی اتصال…", "Checking the connection…"))
        bg {
            try {
                when (val r = CfDeployClient.health(email, key)) {
                    is CfDeployClient.Result.Ok -> {
                        saveWorkingSetToActive()
                        val acc = JSONObject()
                            .put("email", email)
                            .put("key", key)
                            .put("panel_url", "")
                            .put("panel_user", "")
                            .put("clean_ips", JSONArray().toString())
                            .put("results", JSONArray().toString())
                            .put("step1_done", true)
                            .put("bg_done", false)
                            .put("step2_done", false)
                            .put("proxy_link", "")
                            .put("proxy_worker", "")
                        val list = loadAccounts()
                        list.add(acc)
                        saveAccounts(list)
                        val idx = list.size - 1
                        prefs().edit().putInt("cf_active_account", idx).apply()
                        loadAccountToWorkingSet(idx)
                        runOnUiThread {
                            refreshAccountViews()
                            loadResults()
                            renderResults()
                            mgmtDialog?.dismiss()
                            if (list.size >= 2) {
                                toast(L(
                                    "اکانت ${idx + 1} اضافه شد؛ آماده‌سازی در پس‌زمینه شروع شد.",
                                    "Account ${idx + 1} added; background setup started."
                                ))
                            } else {
                                toast(L(
                                    "اکانت اضافه شد؛ آماده‌سازی در پس‌زمینه شروع شد.",
                                    "Account added; background setup started."
                                ))
                            }
                        }
                        if (list.size >= 2) {
                            logLine(
                                "اکانت ${idx + 1} اضافه شد.",
                                "Account ${idx + 1} added."
                            )
                        } else {
                            logLine("اکانت اضافه شد.", "Account added.")
                        }
                        // Full step-1 setup for the new account, in
                        // the background, exactly like step 1 does.
                        runBackground()
                    }
                    is CfDeployClient.Result.Err -> {
                        msg(deployReason(r.code))
                    }
                }
            } finally {
                mgmtBusy = false
            }
        }
    }

    // ------------------------------------------------------------------
    // Telegram WEB proxy (1.101)
    // ------------------------------------------------------------------

    /** The active account's proxy link, or "" when none was built. */
    private fun proxyLink(): String = prefs().getString("cf_proxy_link", "") ?: ""

    private fun proxyWorker(): String = prefs().getString("cf_proxy_worker", "") ?: ""

    private fun saveProxy(worker: String, link: String) {
        prefs().edit()
            .putString("cf_proxy_worker", worker)
            .putString("cf_proxy_link", link)
            .apply()
        saveWorkingSetToActive()
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Bridge capability for the worker host, exactly like the
     *  script computes it: base64url(HMAC-SHA256(key = the
     *  hex-decoded secret, message = bridge context + host)). */
    private fun bridgeCap(secret: String, host: String): String {
        val keyBytes = ByteArray(secret.length / 2) { i ->
            secret.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val sig = mac.doFinal(
            ("tdesktop-web-proxy-bridge-v1\n" + host).toByteArray(Charsets.UTF_8)
        )
        return android.util.Base64.encodeToString(
            sig,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING
        )
    }

    /** GET the bridge page; true only on HTTP 200 with the bridge
     *  page body. Retried a few times: right after the subdomain
     *  enable, the route can race propagation (1.101 lesson). */
    private fun bridgeOk(host: String, cap: String): Boolean {
        repeat(4) { attempt ->
            try {
                val conn = URL("https://$host/?bridge=$cap").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val body = try {
                    conn.inputStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                } catch (_: Exception) {
                    ""
                }
                conn.disconnect()
                if (code == 200 && body.contains("tproxy")) return true
            } catch (_: Exception) {
            }
            if (attempt < 3) Thread.sleep(3000)
        }
        return false
    }

    private fun showProxyDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val accounts = loadAccounts()
        proxyPickerSel = -1
        if (accounts.isEmpty()) {
            // No account yet: ask for email + Global key here.
            content.addView(hintText(L(
                "برای ساخت پروکسی تلگرام، ایمیل و کلید گلوبال حساب کلودفلر را وارد کن؛ برنامه با همان‌ها پروکسی را روی حساب خودت می‌سازد.",
                "To build a Telegram proxy, enter the Cloudflare account email and Global key; the app builds the proxy on your own account with them."
            )))
            val em = edit(
                L("ایمیل حساب کلودفلر", "Cloudflare account email"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
            val ky = edit(
                L("کلید گلوبال دریافتی", "Received Global key"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            content.addView(em)
            content.addView(ky)
            val st = TextView(this).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@CloudflareActivity))
                setPadding(0, dp(6), 0, dp(4))
            }
            content.addView(HydraUi.glassButton(this, L("اتصال و ساخت پروکسی", "Connect and build proxy")) {
                proxyBuildFromNewAccount(em, ky, st)
            })
            content.addView(st)
        } else {
            val active = activeIdx()
            val accLine = TextView(this).apply {
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.fg(this@CloudflareActivity))
                setPadding(0, dp(4), 0, dp(4))
            }
            val noteView = TextView(this).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@CloudflareActivity))
                setPadding(0, dp(2), 0, dp(4))
                visibility = View.GONE
            }
            val pickerBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun refreshAccLine(idx: Int) {
                val acc = accounts.getOrNull(idx) ?: return
                accLine.text = L(
                    "اکانت: ${accountName(idx)} (${acc.optString("email", "")})",
                    "Account: ${accountName(idx)} (${acc.optString("email", "")})"
                )
            }
            fun refreshAll(sel: Int) {
                val u = if (accounts.size == 2 && sel >= 0) sel else active
                refreshAccLine(u)
                pickerBox.removeAllViews()
                if (accounts.size == 2) {
                    pickerBox.visibility = View.VISIBLE
                    pickerBox.addView(accountPickerBox(sel) { s ->
                        proxyPickerSel = s
                        refreshAll(s)
                    })
                } else {
                    pickerBox.visibility = View.GONE
                }
                val hasOld = accounts.getOrNull(u)?.optString("proxy_link", "")?.isNotBlank() == true
                noteView.visibility = if (hasOld) View.VISIBLE else View.GONE
                if (hasOld) {
                    noteView.text = L(
                        "برای این اکانت قبلاً پروکسی ساخته شده؛ ساخت دوباره جایگزین آن می‌شود.",
                        "This account already has a proxy; rebuilding replaces it."
                    )
                }
            }
            content.addView(accLine)
            content.addView(pickerBox)
            content.addView(noteView)
            refreshAll(-1)
            val st = TextView(this).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@CloudflareActivity))
                setPadding(0, dp(6), 0, dp(4))
            }
            content.addView(HydraUi.glassButton(this, L("اتصال و ساخت پروکسی", "Connect and build proxy")) {
                proxyBuild(st)
            })
            content.addView(st)
        }
        proxyDialog?.dismiss()
        proxyDialog = makeDialog(L("پروکسی تلگرام", "Telegram proxy"), content)
        proxyDialog?.show()
    }

    /** Proxy build for an already-saved account (picker or active). */
    private fun proxyBuild(statusView: TextView) {
        if (proxyBusy || bgRunning) {
            toast(L("کاری در حال انجام است؛ کمی صبر کنید.", "Something is already running; please wait a moment."))
            return
        }
        val accounts = loadAccounts()
        if (accounts.isEmpty()) {
            statusView.text = L("اول یک اکانت اضافه کنید.", "Add an account first.")
            return
        }
        if (accounts.size == 2) {
            if (proxyPickerSel < 0) {
                statusView.text = L("اول اکانت را انتخاب کنید.", "Select an account first.")
                return
            }
            if (proxyPickerSel != activeIdx()) {
                activateAccount(proxyPickerSel)
            }
        }
        val acc = loadAccounts().getOrNull(activeIdx()) ?: return
        val email = acc.optString("email", "")
        val key = acc.optString("key", "")
        if (email.isBlank() || key.isBlank()) {
            statusView.text = L("ایمیل و کلید این اکانت خالی است.", "This account's email and key are empty.")
            return
        }
        proxyBusy = true
        statusView.text = L("در حال ساخت پروکسی تلگرام…", "Building the Telegram proxy…")
        bg {
            try {
                deployProxyNow(email, key, statusView)
            } finally {
                proxyBusy = false
            }
        }
    }

    /** Proxy build that first creates the account (proxy dialog
     *  with no accounts yet). No panel work: the proxy needs no
     *  panel, only the Cloudflare credentials. */
    private fun proxyBuildFromNewAccount(em: EditText, ky: EditText, statusView: TextView) {
        if (proxyBusy || bgRunning) {
            toast(L("کاری در حال انجام است؛ کمی صبر کنید.", "Something is already running; please wait a moment."))
            return
        }
        val email = em.text.toString().trim()
        val key = ky.text.toString().trim()
        if (email.isBlank() || key.isBlank()) {
            statusView.text = L("ایمیل و کلید را وارد کنید.", "Enter the email and key.")
            return
        }
        val accounts = loadAccounts()
        if (accounts.size >= 2) {
            statusView.text = L("حداکثر دو اکانت اضافه شده است.", "Maximum of two accounts.")
            return
        }
        if (accounts.any { it.optString("email", "").equals(email, ignoreCase = true) }) {
            statusView.text = L("این اکانت قبلاً اضافه شده است.", "This account is already added.")
            return
        }
        proxyBusy = true
        statusView.text = L("در حال بررسی اتصال…", "Checking the connection…")
        bg {
            try {
                when (val r = CfDeployClient.health(email, key)) {
                    is CfDeployClient.Result.Ok -> {
                        prefs().edit()
                            .putString("cf_email", email)
                            .putString("cf_key", key)
                            .putBoolean("cf_step1_done", true)
                            .apply()
                        val acc = JSONObject()
                            .put("email", email)
                            .put("key", key)
                            .put("panel_url", "")
                            .put("panel_user", "")
                            .put("clean_ips", JSONArray().toString())
                            .put("results", JSONArray().toString())
                            .put("step1_done", true)
                            .put("bg_done", false)
                            .put("step2_done", false)
                            .put("proxy_link", "")
                            .put("proxy_worker", "")
                        val list = loadAccounts()
                        list.add(acc)
                        saveAccounts(list)
                        val idx = list.size - 1
                        prefs().edit().putInt("cf_active_account", idx).apply()
                        loadAccountToWorkingSet(idx)
                        runOnUiThread {
                            refreshAccountViews()
                            loadResults()
                            renderResults()
                        }
                        logLine("اکانت ${idx + 1} اضافه شد.", "Account ${idx + 1} added.")
                        deployProxyNow(email, key, statusView)
                    }
                    is CfDeployClient.Result.Err -> {
                        val m = deployReason(r.code)
                        runOnUiThread { statusView.text = m }
                    }
                }
            } finally {
                proxyBusy = false
            }
        }
    }

    /** The actual deploy, on a background thread with proxyBusy
     *  already held: upload, enable, bridge-test, save, replace
     *  the account's old worker. One proxy per account. */
    private fun deployProxyNow(email: String, key: String, statusView: TextView) {
        val script = try {
            assets.open("tgp_worker.js").readBytes()
        } catch (_: Exception) {
            null
        }
        if (script == null) {
            runOnUiThread { statusView.text = L("فایل ورکر پیدا نشد.", "Worker file not found.") }
            return
        }
        // The secret is generated in-app (user order): the user
        // is never asked for it. "dd" + 32 random hex chars.
        val worker = "tgp-" + randomHex(4)
        val secret = "dd" + randomHex(16)
        when (val r = CfDeployClient.deployTgp(email, key, worker, secret, script)) {
            is CfDeployClient.Result.Ok -> {
                val host = r.data
                if (!bridgeOk(host, bridgeCap(secret, host))) {
                    CfDeployClient.deleteWorker(email, key, worker)
                    runOnUiThread {
                        statusView.text = L(
                            "پروکسی ساخته شد ولی تست آن ناموفق بود؛ دوباره تلاش کنید.",
                            "The proxy was built but its test failed; please retry."
                        )
                    }
                    logLineStr(L("تست پروکسی تلگرام ناموفق بود.", "Telegram proxy test failed."))
                    return
                }
                val oldWorker = proxyWorker()
                val link = "tg://webproxy?server=$host&secret=$secret"
                saveProxy(worker, link)
                if (oldWorker.isNotBlank() && oldWorker != worker) {
                    CfDeployClient.deleteWorker(email, key, oldWorker)
                }
                runOnUiThread {
                    refreshStepKeys()
                    renderResults()
                    proxyDialog?.dismiss()
                    toast(L("پروکسی تلگرام ساخته شد.", "Telegram proxy built."))
                }
                logLine("پروکسی تلگرام ساخته شد.", "Telegram proxy built.")
            }
            is CfDeployClient.Result.Err -> {
                val m = deployReason(r.code)
                runOnUiThread { statusView.text = m }
                logLineStr(m)
            }
        }
    }

    /** 1.101: the Telegram proxy card — small link text, copy
     *  button, and a small Telegram icon that opens Telegram
     *  with the proxy link so the user can add it in one tap. */
    private fun proxyCard(link: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HydraUi.glassBg(
                Theme.card(this@CloudflareActivity),
                Theme.divider(this@CloudflareActivity), 14f, this@CloudflareActivity
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
        }
        val top = tightRow()
        top.addView(ImageView(this).apply {
            try {
                setImageBitmap(
                    android.graphics.BitmapFactory.decodeStream(assets.open("telegram.png"))
                )
            } catch (_: Exception) {
            }
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                setMargins(0, 0, dp(6), 0)
            }
        })
        top.addView(TextView(this).apply {
            text = L("پروکسی تلگرام", "Telegram proxy")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.fg(this@CloudflareActivity))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        card.addView(top)
        card.addView(TextView(this).apply {
            text = link
            textSize = 12f
            setTextColor(Theme.subFg(this@CloudflareActivity))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        })
        // 1.102: aligned like the config rows — spacer on the
        // left, both buttons right, same size as the config
        // buttons. The Telegram icon button is replaced by an
        // "Open with Telegram" button.
        val bottom = tightRow()
        bottom.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        bottom.addView(miniButton(L("کپی", "Copy")) { copyText(link) })
        bottom.addView(miniButton(L("باز کردن با تلگرام", "Open with Telegram")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                toast(L("تلگرام پیدا نشد؛ لینک را کپی کنید.", "Telegram was not found; copy the link instead."))
            }
        })
        card.addView(bottom)
        return card
    }

    /** 2.0: delete the proxy for the active account — clears
     *  the link + worker locally and removes the worker from
     *  Cloudflare best-effort (a failed remote delete still
     *  clears the section; the worker can be removed from the
     *  dashboard). Trash never touches configs. */
    private fun clearProxy() {
        val worker = proxyWorker()
        val link = proxyLink()
        if (worker.isBlank() && link.isBlank()) return
        saveProxy("", "")
        refreshStepKeys()
        renderResults()
        toast(L("پروکسی پاک شد.", "Proxy deleted."))
        logLine("پروکسی پاک شد.", "Proxy deleted.")
        if (worker.isBlank()) return
        val email = prefs().getString("cf_email", "") ?: ""
        val key = prefs().getString("cf_key", "") ?: ""
        if (email.isBlank() || key.isBlank()) return
        Thread {
            try {
                CfDeployClient.deleteWorker(email, key, worker)
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Trash: clears ONLY this section's results for the active
     *  account. Configs already imported into v2ray are untouched. */
    private fun clearResults() {
        if (results.isEmpty()) return
        results.clear()
        saveResults()
        prefs().edit().putBoolean("cf_step2_done", false).apply()
        saveWorkingSetToActive()
        renderResults()
        refreshStepKeys()
        val n = activeIdx() + 1
        if (loadAccounts().size >= 2) {
            toast(L("کانفیگ‌های اکانت $n پاک شد.", "Account $n configs cleared."))
            logLine("کانفیگ‌های اکانت $n پاک شد.", "Account $n configs cleared.")
        } else {
            toast(L("کانفیگ‌ها پاک شد.", "Configs cleared."))
            logLine("کانفیگ‌ها پاک شد.", "Configs cleared.")
        }
    }

    private fun cfName(code: String): String {
        val c = countryForCode(code)
            ?: return L("کلودفلر", "Cloudflare")
        return L("کلودفلر - ${c.fa}", "Cloudflare - ${c.en}")
    }

    /** The link with its #fragment replaced by the Cloudflare name
     *  (URL-encoded, spaces as %20, like the Renamer does). */
    private fun renamed(link: String, code: String): String {
        val name = cfName(code)
        val enc = try {
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            name
        }
        return link.substringBefore("#") + "#" + enc
    }

    /** 1.102: the proxy box, separate from the config box and
     *  above it. Title carries the account label only when a
     *  second account exists. */
    private fun renderProxyBox() {
        val proxy = proxyLink()
        proxyResultsBox.removeAllViews()
        if (proxy.isBlank()) {
            proxyResultsCard.visibility = View.GONE
            return
        }
        proxyResultsCard.visibility = View.VISIBLE
        proxyResultsTitle.text = L("پروکسی ساخته شده", "Proxy built") + accountSuffix()
        proxyResultsBox.addView(proxyCard(proxy))
    }

    private fun renderResults() {
        renderProxyBox()
        resultsBox.removeAllViews()
        pingViews.clear()
        connectBtns.clear()
        if (results.isEmpty()) {
            resultsCard.visibility = View.GONE
            return
        }
        resultsCard.visibility = View.VISIBLE
        resultsTitle.text = L(
            "کانفیگ‌های ساخته شده${accountSuffix()} (${results.size})",
            "Built configs${accountSuffix()} (${results.size})"
        )
        for ((idx, res) in results.withIndex()) {
            val (link, code) = res
            val country = countryForCode(code)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = HydraUi.glassBg(
                    Theme.card(this@CloudflareActivity),
                    Theme.divider(this@CloudflareActivity), 14f, this@CloudflareActivity
                )
                setPadding(dp(10), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(4)) }
            }
            val top = tightRow()
            top.addView(TextView(this).apply {
                text = country?.flag ?: "🌐"
                textSize = 18f
                setPadding(0, 0, dp(6), 0)
            })
            top.addView(TextView(this).apply {
                text = cfName(code)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.fg(this@CloudflareActivity))
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            card.addView(top)
            val bottom = tightRow()
            val pingView = TextView(this).apply {
                text = "—"
                textSize = 11f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            pingViews += pingView
            bottom.addView(pingView)
            bottom.addView(miniButton(L("پینگ", "Ping")) { pingOne(idx) })
            bottom.addView(miniButton(L("کپی", "Copy")) { copyText(renamed(link, code)) })
            val connBtn = miniButton(L("اتصال", "Connect")) { connectFromBox(idx) }
            connectBtns += connBtn
            bottom.addView(connBtn)
            card.addView(bottom)
            resultsBox.addView(card)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        fun actionBtn(text: String, size: Float, onClick: () -> Unit): Button {
            return HydraUi.glassButton(this, text, onClick).apply {
                textSize = size
                isSingleLine = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                minimumWidth = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), 0, dp(4), 0) }
            }
        }
        // Order: import, ping test (middle, slightly bigger
        // so its text stays on one line), copy all.
        actions.addView(actionBtn(L("انتقال به v2ray", "Import to v2ray"), 12f) { importToVpn() })
        actions.addView(actionBtn(L("تست پینگ", "Ping test"), 14f) { pingAll() })
        actions.addView(actionBtn(L("کپی همه", "Copy all"), 12f) { copyAllResults() })
        resultsBox.addView(actions)
        syncConnectButtons()
    }
    private fun pingOne(idx: Int) {
        val view = pingViews.getOrNull(idx) ?: return
        val (link, _) = results.getOrNull(idx) ?: return
        view.text = "…"
        view.setTextColor(Color.GRAY)
        bg {
            val ms = try {
                XraySpeedtest.measureOne(link)
            } catch (_: Exception) {
                -1L
            }
            runOnUiThread {
                if (ms >= 0) {
                    view.text = "$ms ms"
                    view.setTextColor(Color.parseColor("#0B6E4F"))
                } else {
                    view.text = L("ناموفق", "Failed")
                    view.setTextColor(Color.GRAY)
                }
            }
        }
    }

    private fun pingAll() {
        for (i in results.indices) pingOne(i)
    }

    private fun copyAllResults() {
        if (results.isEmpty()) {
            setStatus(L("چیزی برای کپی نیست.", "Nothing to copy."))
            return
        }
        copyText(results.joinToString("\n") { renamed(it.first, it.second) })
    }

    private fun importToVpn() {
        if (results.isEmpty()) {
            setStatus(L("چیزی برای وارد کردن نیست.", "Nothing to import."))
            return
        }
        bg {
            val servers = VpnSubs.loadServers(this)
            var added = 0
            val imported = loadImported().toMutableMap()
            val fresh = mutableListOf<String>()
            for ((link, code) in results) {
                val nl = renamed(link, code)
                val key = XraySpeedtest.dedupKey(nl)
                imported[key] = code
                // 1.97: Cloudflare configs are added even
                // when identical ones already exist —
                // v2ray refuses duplicates in general, but
                // the Cloudflare section is the exception
                // the user ordered.
                fresh += nl
                added++
            }
            // New configs go to the TOP of the v2ray list.
            VpnSubs.saveServers(this, fresh + servers)
            saveImported(imported)
            toast(L("$added کانفیگ به v2ray اضافه شد.", "$added configs added to v2ray."))
            logLine(
                "کانفیگ‌ها به v2ray وارد شد ($added مورد).",
                "Configs imported to v2ray ($added)."
            )
            setStatus(L(
                "کانفیگ‌ها به v2ray اضافه شد؛ آن‌ها را با برچسب «کلودفلر» می‌بینید.",
                "Configs added to v2ray; you will see them with the “Cloudflare” label."))
        }
    }

    // ------------------------------------------------------------------
    // Persistence (results + imported-country map)
    // ------------------------------------------------------------------

    private fun saveResults() {
        try {
            val arr = JSONArray()
            for ((link, code) in results) {
                arr.put(JSONObject().put("link", link).put("country", code))
            }
            prefs().edit().putString("cf_results", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadResults() {
        results.clear()
        try {
            val arr = JSONArray(prefs().getString("cf_results", "") ?: "")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("link", "")
                val code = o.optString("country", "")
                if (link.isNotBlank() && code.isNotBlank()) results += link to code
            }
        } catch (_: Exception) {
        }
    }

    private fun loadImported(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val o = JSONObject(prefs().getString("cf_imported", "") ?: "")
            for (k in o.keys()) map[k] = o.optString(k, "")
        } catch (_: Exception) {
        }
        return map
    }

    private fun saveImported(map: Map<String, String>) {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            prefs().edit().putString("cf_imported", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Connect via the v2ray engine
    // ------------------------------------------------------------------

    private fun connect(link: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingLink = link
            startActivityForResult(intent, VPN_REQ)
        } else {
            doConnect(link)
        }
    }

    private fun doConnect(link: String) {
        bg {
            val built = VpnConfigBuilder.build(this, link)
            if (built == null) {
                setStatus(L("این کانفیگ ساخته نشد.", "This config could not be built."))
                return@bg
            }
            val intent = Intent(this, VpnClientService::class.java).apply {
                putExtra("config", built)
                putExtra("name", XraySpeedtest.displayName(link))
                putExtra("session_tag", "cloudflare")
            }
            startService(intent)
            setStatus(L("در حال اتصال…", "Connecting…"))
        }
    }
}
