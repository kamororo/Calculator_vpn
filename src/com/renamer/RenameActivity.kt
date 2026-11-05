package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONObject

class RenameActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var inputF: EditText
    private lateinit var namesF: EditText
    private lateinit var outputF: EditText
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scrollView = scroll
        scroll.setBackgroundColor(Theme.bg(this))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.59: dp paddings (were raw px).
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Theme.bg(this@RenameActivity))
        }
        scroll.addView(root)
        fun label(text: String, size: Float = 15f) = TextView(this).apply {
            this.text = text; textSize = size
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
            setTextColor(Theme.fg(this@RenameActivity))
        }
        fun note(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Theme.subFg(this@RenameActivity)); textSize = 13f
            setPadding(0, 0, 0, dp(2))
        }
        // Fixed-height boxes with their OWN internal scroll:
        // no matter how many configs are pasted, the box never
        // grows taller, so the page never turns into endless
        // scrolling. The text scrolls inside the box instead.
        fun box(heightDp: Int, hint: String): EditText = EditText(this).apply {
            this.hint = hint
            gravity = Gravity.TOP
            textSize = 14f
            setTextColor(Color.BLACK)
            // 2.62: softer glass box — larger radius,
            // softer stroke, a touch of elevation and
            // padding, same fixed heights and internal
            // scroll as before.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#FFDADADA"))
            }
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (heightDp * resources.displayMetrics.density).toInt()
            )
            // Let the box scroll itself instead of the page
            // stealing the touch.
            setOnTouchListener { v, event ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        root.addView(TextView(this).apply {
            text = L("تغییر نام کانفیگ‌های v2ray", "Rename v2ray configs"); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@RenameActivity))
        })
        // --- Part 1 ---
        root.addView(label(L("۱) کانفیگ‌ها", "1) Configs")))
        root.addView(note(L("هر کانفیگ در یک خط؛ هر تعدادی که بخواهی.", "One config per line, as many as you want.")))
        inputF = box(240, "vless://...\nvmess://...\ntrojan://...")
        root.addView(inputF)
        // --- Part 2 ---
        root.addView(label(L("۲) نام‌های جدید", "2) New names")))
        root.addView(note(L("هر خط یک نام برای کانفیگ هم‌ردیفش؛ یک نام تنها = همان نام برای همه، بدون شماره.", "Each line names the config on its row; a single name applies to all, with no numbering.")))
        namesF = box(160, L("نام جدید\nمثلاً: سرور", "New name\ne.g.: server"))
        root.addView(namesF)
        val renameBtn = Button(this).apply {
            text = L("تغییر نام‌ها", "Rename")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            // 2.59: full-width teal gradient pill
            // (was a white glass box, raw-px pads).
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(
                    Color.parseColor("#FF0F766E"), Color.parseColor("#FF26A69A"))
            }
            elevation = 4f
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener { doRename() }
        }
        root.addView(renameBtn)
        // --- Part 3 ---
        root.addView(label(L("۳) کانفیگ‌های تغییرنام‌داده‌شده", "3) Renamed configs")))
        outputF = box(240, L("نتیجه اینجا نشان داده می‌شود", "The result will be shown here"))
        root.addView(outputF)
        statusText = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(Theme.fg(this@RenameActivity))
        }
        root.addView(statusText)
        // --- Bottom copy button ---
        val copyBtn = Button(this).apply {
            text = L("کپی همه کانفیگ‌های تغییرنام‌داده‌شده", "Copy all renamed configs")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            // 2.59: full-width white outline pill
            // (was a white glass box, raw-px pads).
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), Color.parseColor("#FF0F766E"))
                setColor(Color.WHITE)
            }
            elevation = 4f
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val text = outputF.text.toString().trim()
                if (text.isBlank()) {
                    statusText.text = L("چیزی برای کپی نیست؛ اول «تغییر نام‌ها» را بزن.", "Nothing to copy; tap “Rename” first.")
                    return@setOnClickListener
                }
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("configs", text))
                    statusText.text = L("همه کانفیگ‌ها کپی شد.", "All configs copied.")
                    Toast.makeText(this@RenameActivity, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                }
            }
        }
        root.addView(copyBtn)
        // Live output: typing in either box re-runs the
        // rename quietly (no statusText spam); the button
        // still calls the same doRename() with status.
        val liveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { doRename(quiet = true) }
        }
        inputF.addTextChangedListener(liveWatcher)
        namesF.addTextChangedListener(liveWatcher)
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

    /** Dedup key: the config WITHOUT its name. Two configs
     *  that differ only by name share the same key. */
    private fun dedupKey(cfg: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                while (raw.length % 4 != 0) raw += "="
                val json = JSONObject(String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8))
                json.remove("ps")
                json.toString()
            } else {
                cfg.substringBefore("#")
            }
        } catch (_: Exception) {
            cfg.substringBefore("#")
        }
    }

    private fun doRename(quiet: Boolean = false) {
        val rawConfigs = inputF.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        val names = namesF.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rawConfigs.isEmpty()) {
            outputF.setText("")
            if (!quiet) statusText.text = L("قسمت اول خالی است؛ اول کانفیگ‌ها را بچسبان.", "Part 1 is empty; paste the configs first.")
            return
        }
        // Remove duplicates first (same config, different name):
        // keep the first occurrence of each.
        val seen = mutableSetOf<String>()
        val configs = mutableListOf<String>()
        var dupRemoved = 0
        for (c in rawConfigs) {
            if (seen.add(dedupKey(c))) configs += c else dupRemoved++
        }
        if (names.isEmpty()) {
            // No new names: output the configs as-is
            // (deduped), keeping their previous names.
            outputF.setText(configs.joinToString("\n"))
            if (!quiet) statusText.text = L("نام جدیدی ننوشته‌ای؛ کانفیگ‌ها با همان نام قبلی نشان داده شدند.", "No new name written; configs are shown with their previous names.") +
                if (dupRemoved > 0) L(" $dupRemoved کانفیگ تکراری (فقط اسمشان فرق داشت) حذف شد.", " $dupRemoved duplicate configs (only their names differed) removed.") else ""
            return
        }
        val out = mutableListOf<String>()
        var renamed = 0
        for ((i, cfg) in configs.withIndex()) {
            // No numbering: a single name applies exactly as
            // written to every config.
            val newName: String? = when {
                names.size == 1 -> names[0]
                i < names.size -> names[i]
                else -> null
            }
            if (newName == null) {
                out += cfg
            } else {
                out += renameOne(cfg, newName)
                renamed++
            }
        }
        outputF.setText(out.joinToString("\n"))
        if (!quiet) AppLog.append(this, "renamer",
            "$renamed تا از ${configs.size} کانفیگ تغییر نام داده شد.",
            "$renamed of ${configs.size} configs renamed.")
        if (!quiet) statusText.text = L("$renamed تا از ${configs.size} کانفیگ تغییر نام داده شد.", "$renamed of ${configs.size} configs renamed.") +
            if (dupRemoved > 0) L(" $dupRemoved کانفیگ تکراری (فقط اسمشان فرق داشت) حذف شد.", " $dupRemoved duplicate configs (only their names differed) removed.") else "" +
            if (renamed < configs.size) L(" برای بقیه نام ننوشته بودی، همان نام قبلی ماند.", " For the rest, where you wrote no name, the previous name stayed.") else ""
    }

    private fun renameOne(cfg: String, newName: String): String {
        return try {
            if (cfg.startsWith("vmess://")) {
                var raw = cfg.removePrefix("vmess://").substringBefore("#").trim()
                // vmess links are often unpadded; pad before decoding.
                while (raw.length % 4 != 0) raw += "="
                val decoded = String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8)
                val json = JSONObject(decoded)
                json.put("ps", newName)
                val encoded = Base64.encodeToString(
                    json.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP
                ).trimEnd('=')
                "vmess://$encoded"
            } else {
                val base = cfg.substringBefore("#")
                val enc = java.net.URLEncoder.encode(newName, "UTF-8").replace("+", "%20")
                "$base#$enc"
            }
        } catch (_: Exception) {
            cfg
        }
    }
}
