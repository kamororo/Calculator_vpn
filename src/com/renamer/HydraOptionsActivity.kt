package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*

/** Hydra options, on their own short page, so
 *  every control is always reachable — the old
 *  page buried Save below the fold because it did
 *  not scroll, and the saved source silently stayed
 *  on its old value. All buttons here are glass. */
class HydraOptionsActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int): Int = HydraUi.dp(this, v)

    private lateinit var sourceGroup: RadioGroup
    private lateinit var radioClipboard: RadioButton
    private lateinit var clipboardBox: LinearLayout
    private lateinit var clipboardEdit: EditText
    private lateinit var maxEndpointsEdit: EditText
    private lateinit var budgetEdit: EditText
    private lateinit var swFragment: Switch
    private lateinit var swFingerprint: Switch
    private lateinit var swAlpn: Switch
    private lateinit var swWs: Switch
    private lateinit var swGrpc: Switch
    private lateinit var swMux: Switch
    private lateinit var swWatchdog: Switch
    private lateinit var swMemory: Switch
    private lateinit var swContinuous: Switch
    private lateinit var swTcpRank: Switch

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
        }
        scroll.addView(root)
        setContentView(scroll)

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(HydraUi.glassButton(this, L("بازگشت", "Back")) { finish() })
        topRow.addView(TextView(this).apply {
            text = L("آپشن‌های هیدرا", "Hydra options")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), 0, 0)
        })
        root.addView(topRow)

        root.addView(TextView(this).apply {
            text = L("نقطه‌های اتصال از کجا بیایند؟",
                "Where should endpoints come from?")
            textSize = 15f
            setPadding(0, dp(20), 0, dp(8))
        })
        val opts = HydraEngine.loadOpts(this)
        sourceGroup = RadioGroup(this)
        val radioV2ray = RadioButton(this).apply {
            id = View.generateViewId()
            text = L("کانفیگ‌های v2ray من", "My v2ray configs")
        }
        val radioEmergency = RadioButton(this).apply {
            id = View.generateViewId()
            text = L("اضطراری (تازه، بدون ذخیره)", "Emergency (fresh, nothing saved)")
        }
        val radioBoth = RadioButton(this).apply {
            id = View.generateViewId()
            text = L("هر دو؛ اول v2ray، بعد اضطراری", "Both; v2ray first, then emergency")
        }
        radioClipboard = RadioButton(this).apply {
            id = View.generateViewId()
            text = L("کانفیگی که خودم وارد می‌کنم", "A config I paste myself")
        }
        sourceGroup.addView(radioV2ray)
        sourceGroup.addView(radioEmergency)
        sourceGroup.addView(radioBoth)
        sourceGroup.addView(radioClipboard)
        sourceGroup.check(when (opts.source) {
            "v2ray" -> radioV2ray.id
            "emergency" -> radioEmergency.id
            "clipboard" -> radioClipboard.id
            else -> radioBoth.id
        })
        root.addView(sourceGroup)

        // Pasted-config box, shown only for the
        // clipboard source.
        clipboardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        clipboardEdit = EditText(this).apply {
            minLines = 3
            setText(getSharedPreferences(HydraEngine.PREFS, MODE_PRIVATE)
                .getString("clipboard_config", "") ?: "")
        }
        clipboardBox.addView(clipboardEdit)
        clipboardBox.addView(HydraUi.glassButton(this,
            L("چسباندن از کلیپ‌بورد", "Paste from clipboard")) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (text.isNotBlank()) clipboardEdit.setText(text)
            } catch (_: Exception) {}
        })
        root.addView(clipboardBox)
        fun syncClipboardBox() {
            clipboardBox.visibility =
                if (sourceGroup.checkedRadioButtonId == radioClipboard.id) View.VISIBLE
                else View.GONE
        }
        sourceGroup.setOnCheckedChangeListener { _, _ -> syncClipboardBox() }
        syncClipboardBox()

        fun numberRow(label: String, initial: String): EditText {
            root.addView(TextView(this).apply {
                text = label; textSize = 15f
                setPadding(0, dp(12), 0, 0)
            })
            val edit = EditText(this).apply {
                setText(initial)
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            root.addView(edit)
            return edit
        }
        maxEndpointsEdit = numberRow(
            L("حداکثر چند نقطه‌ی اتصال چشیده شود؟ (۱ تا ۱۰)",
                "How many endpoints to probe at most? (1 to 10)"),
            opts.maxEndpoints.toString())
        budgetEdit = numberRow(
            L("حداکثر زمان چشیدن (ثانیه، ۳۰ تا ۶۰۰)",
                "Maximum probe time (seconds, 30 to 600)"),
            opts.budgetSec.toString())

        fun switchRow(label: String, initial: Boolean): Switch {
            val sw = Switch(this).apply {
                text = label
                isChecked = initial
            }
            root.addView(sw)
            return sw
        }
        swFragment = switchRow(L("شکل‌های Fragment (چند الگوی تکه‌تکه کردن بسته‌ها)",
            "Fragment shapes (several packet-fragmentation patterns)"), opts.useFragment)
        swFingerprint = switchRow(L("شکل‌های اثر انگشت TLS (کروم، فایرفاکس، سافاری، تصادفی)",
            "TLS fingerprint shapes (Chrome, Firefox, Safari, randomized)"), opts.useFingerprint)
        swAlpn = switchRow(L("شکل‌های ALPN (h2، http/1.1، هیچ‌کدام)",
            "ALPN shapes (h2, http/1.1, none)"), opts.useAlpn)
        swWs = switchRow(L("شکل WebSocket",
            "WebSocket shape"), opts.useWs)
        swGrpc = switchRow(L("شکل gRPC",
            "gRPC shape"), opts.useGrpc)
        swMux = switchRow(L("شکل Mux",
            "Mux shape"), opts.useMux)
        swWatchdog = switchRow(L("نگهبانی خودکار: اگر اتصال افتاد، خودش دوباره بچشد و وصل شود",
            "Auto-watch: if the connection drops, re-probe and reconnect on its own"),
            opts.watchdog)
        swMemory = switchRow(L("حافظه‌ی شبکه: دفعه‌ی بعد اول همان ترکیب برنده‌ی قبلی را امتحان کن",
            "Network memory: next time, try the previous winning combination first"),
            opts.useMemory)
        swContinuous = switchRow(L("تلاش پیوسته: اگر چشیدن جواب نداد، دوربه‌دور ادامه بده تا وصل شود (بدون انتظار ۶۰ ثانیه‌ای)",
            "Continuous retry: if probing fails, keep going round after round until connected (no 60-second wait)"),
            opts.continuous)
        swTcpRank = switchRow(L("چینش هوشمند: نقطه‌های v2ray را با تست سریع TCP مرتب کن تا کم‌تأخیرترین اول امتحان شود",
            "Smart ordering: order v2ray endpoints by a fast TCP test so the lowest-latency one is tried first"),
            opts.tcpRank)

        root.addView(HydraUi.glassButton(this,
            L("پاک کردن حافظه", "Clear memory")) {
            HydraEngine.clearMemory(this)
            Toast.makeText(this, L("حافظه پاک شد", "Memory cleared"),
                Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        })
        root.addView(HydraUi.glassButton(this,
            L("ذخیره آپشن‌ها", "Save options")) {
            val source = when (sourceGroup.checkedRadioButtonId) {
                radioV2ray.id -> "v2ray"
                radioEmergency.id -> "emergency"
                radioClipboard.id -> "clipboard"
                else -> "both"
            }
            // Keep exactly one config: the first
            // line that looks like a link — or the
            // whole text when it is one custom JSON
            // (a custom has no "://" line, so the old
            // filter silently discarded pasted customs).
            val rawPasted = clipboardEdit.text.toString().trim()
            val pasted = rawPasted.lines().firstOrNull { it.contains("://") }?.trim()
                ?: if (XraySpeedtest.isCustom(rawPasted)) rawPasted else ""
            HydraEngine.saveOpts(this, HydraEngine.Opts(
                source = source,
                maxEndpoints = maxEndpointsEdit.text.toString().toIntOrNull() ?: 3,
                budgetSec = budgetEdit.text.toString().toIntOrNull() ?: 90,
                useFragment = swFragment.isChecked,
                useFingerprint = swFingerprint.isChecked,
                useAlpn = swAlpn.isChecked,
                useWs = swWs.isChecked,
                useGrpc = swGrpc.isChecked,
                useMux = swMux.isChecked,
                watchdog = swWatchdog.isChecked,
                useMemory = swMemory.isChecked,
                continuous = swContinuous.isChecked,
                tcpRank = swTcpRank.isChecked
            ))
            getSharedPreferences(HydraEngine.PREFS, MODE_PRIVATE).edit()
                .putString("clipboard_config", pasted).apply()
            Toast.makeText(this,
                L("ذخیره شد؛ برای شروع بعدی اعمال می‌شود",
                    "Saved; applies to the next start"),
                Toast.LENGTH_SHORT).show()
            finish()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })
        root.addView(TextView(this).apply {
            text = L("نکته: برای اینکه مجوز VPN ثبت شده باشد، اول یک بار از بخش v2ray به‌صورت دستی وصل شو؛ بعد از آن هیدرا بدون باز کردن بخش دیگری کار می‌کند.",
                "Note: connect once manually from the v2ray section first so the VPN permission is registered; after that Hydra works without opening another section.")
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(24))
        })
    }
}
