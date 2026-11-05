package com.renamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wireguard.config.Config as WgConfig
import org.amnezia.awg.config.Config as AwgConfig
import java.io.BufferedReader
import java.io.StringReader

/** "Alien language" section (2.26): write text, get
 *  it back in a strange-looking reversible
 *  language; paste alien text and get the original
 *  message back. Also handles text files both
 *  ways, and detects a decoded v2ray / WireGuard /
 *  Amnezia config and offers an immediate transfer
 *  to its own section. Codec lives in
 *  SecretLanguage.kt (pure Kotlin, JVM-tested). */
class SecretLanguageActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val REQ_PICK = 901
    private val REQ_SAVE = 902

    private var encodeMode = true
    private lateinit var inputBox: EditText
    private lateinit var outputBox: TextView
    private lateinit var statsLine: TextView
    private lateinit var noteLine: TextView
    private lateinit var btnEncode: Button
    private lateinit var btnDecode: Button
    private lateinit var btnTransfer: Button
    private lateinit var btnSendToLink: Button
    private lateinit var btnRefreshMsg: Button
    private lateinit var copyRow: LinearLayout
    private var converting = false
    private var detected: Detected? = null


    private data class Detected(
        val kind: String, val payload: String,
        val noteFa: String, val noteEn: String,
        val btnFa: String, val btnEn: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor(HydraUi.PAGE_BG)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        // Top row: small section name at the LEFT,
        // info circle at the RIGHT (2.26 order).
        val topRow = RelativeLayout(this)
        val nameView = TextView(this).apply {
            text = L("زبان بیگانه", "Alien Language")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        val infoId = View.generateViewId()
        val infoView = TextView(this).apply {
            id = infoId
            text = "ⓘ"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F766E"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dp(1), Color.parseColor("#B2DFDB"))
            }
            layoutParams = RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { showGuide() }
        }
        val aboutView = HelpUi.aboutButton(this).apply {
            layoutParams = RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
                addRule(RelativeLayout.LEFT_OF, infoId)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginEnd = dp(8)
            }
        }
        topRow.addView(nameView)
        topRow.addView(aboutView)
        topRow.addView(infoView)
        root.addView(topRow)
        // Header icon (2.32): the same galaxy bitmap, now alive —
        // thin slow rings hug each of its four circles (big disc +
        // three dots), close to the circles, colours matched.
        try {
            root.addView(AlienOrbitView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(12)
                }
                // 2.27: tapping the section icon
                // opens the message hub.
                setOnClickListener { startActivity(Intent(this@SecretLanguageActivity, AlienHubActivity::class.java)) }
            })
        } catch (_: Exception) {}
        // Direction segmented control (compact).
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        btnEncode = miniButton(L("متن ← زبان بیگانه", "Text → Alien"))
        btnDecode = miniButton(L("زبان بیگانه ← متن", "Alien → Text"))
        btnEncode.setOnClickListener { if (!encodeMode) { encodeMode = true; refreshMode(); convert() } }
        btnDecode.setOnClickListener { if (encodeMode) { encodeMode = false; refreshMode(); convert() } }
        modeRow.addView(btnEncode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        modeRow.addView(btnDecode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(modeRow)
        // Input box (compact, max 5 lines, inner scroll).
        root.addView(TextView(this).apply {
            text = L("ورودی", "Input")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(6))
        })
        inputBox = EditText(this).apply {
            hint = L("متن را اینجا بنویس…", "Write your text here…")
            textSize = 15f
            setTextColor(Color.BLACK)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(88)
            setMaxLines(5)
            isVerticalScrollBarEnabled = true
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@SecretLanguageActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        makeInnerScrollable(inputBox)
        root.addView(inputBox)
        // Action row: clear (right) | swap (center) |
        // file (left) — one compact, classy line.
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnClear = miniButton(L("پاک کردن", "Clear"))
        val btnSwap = miniButton("⇅ " + L("جابه‌جا", "Swap"))
        val btnFile = miniButton(L("فایل", "File"))
        btnClear.setOnClickListener { inputBox.setText("") }
        btnSwap.setOnClickListener {
            val out = outputBox.text.toString()
            encodeMode = !encodeMode
            refreshMode()
            inputBox.setText(out)
        }
        btnFile.setOnClickListener { pickFile() }
        actRow.addView(btnClear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actRow.addView(btnSwap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        actRow.addView(btnFile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actRow)
        // Output box.
        root.addView(TextView(this).apply {
            text = L("خروجی", "Output")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, dp(6))
        })
        outputBox = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(88)
            setMaxLines(5)
            isVerticalScrollBarEnabled = true
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@SecretLanguageActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        makeInnerScrollable(outputBox)
        root.addView(outputBox)
        // Green config note (only when the output is
        // a detected config).
        noteLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#2E7D32"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(noteLine)
        // Copy row: copy + save-to-file + transfer
        // (transfer only when a config is detected).
        copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val btnCopy = miniButton(L("کپی خروجی", "Copy output"))
        btnCopy.setOnClickListener {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("alien", outputBox.text.toString()))
                Toast.makeText(this, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        val btnSave = miniButton(L("ذخیره در فایل", "Save to file"))
        btnSave.setOnClickListener { saveOutput() }
        btnTransfer = miniButton("")
        btnTransfer.visibility = View.GONE
        btnTransfer.setOnClickListener {
            val d = detected ?: return@setOnClickListener
            if (d.kind == "v2ray") transferV2ray(d.payload) else transferWg(d.payload)
        }
        copyRow.addView(btnCopy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        copyRow.addView(btnSave, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        copyRow.addView(btnTransfer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(copyRow)
        // Message-link row (2.27): send the current
        // output to my link (sender, encode mode
        // only), and refresh my received link
        // (receiver). Hidden unless relevant.
        val linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        btnSendToLink = miniButton(L("فرستادن به لینک پیام", "Send to message link"))
        btnSendToLink.visibility = View.GONE
        btnSendToLink.setOnClickListener {
            val manage = AlienSub.senderManage(this) ?: return@setOnClickListener
            val text = outputBox.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, L("خروجی خالی است", "The output is empty"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                val ok = ShzClient.updateForMode(AlienSub.sendMode(this), manage, text)
                runOnUiThread {
                    Toast.makeText(this,
                        if (ok) L("فرستاده شد", "Sent") else L("فرستاده نشد؛ اینترنت را چک کن", "Could not send; check your connection"),
                        Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        btnRefreshMsg = miniButton(L("تازه‌سازی پیام", "Refresh message"))
        btnRefreshMsg.visibility = View.GONE
        btnRefreshMsg.setOnClickListener {
            val view = AlienSub.receiverView(this) ?: return@setOnClickListener
            Thread {
                val content = ShzClient.fetchAny(view)
                runOnUiThread {
                    if (content == null) {
                        Toast.makeText(this,
                            L("لینک پیدا نشد؛ شاید منقضی شده باشد", "The link was not found; it may have expired"),
                            Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val old = AlienSub.lastContent(this)
                    if (content == old) {
                        Toast.makeText(this, L("پیام تازه‌ای نیست", "No new message"), Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val decoded = try { SecretLanguage.decode(content) } catch (_: Exception) { "" }
                    val shown = if (decoded.isBlank() || AlienSub.revealsSite(decoded)) "" else decoded
                    AlienSub.setLast(this, content, shown)
                    AlienSub.refreshWidget(this)
                    if (encodeMode) { encodeMode = false; refreshMode() }
                    inputBox.setText(content)
                    Toast.makeText(this, L("پیام تازه رسید", "A new message arrived"), Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        linkRow.addView(btnSendToLink, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        linkRow.addView(btnRefreshMsg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(linkRow)
        statsLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(statsLine)
        inputBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!converting) convert()
            }
        })
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)
        HelpUi.attach(this, "converter")
        refreshMode()
    }


    /** Compact, classy, minimal glass pill — the one
     *  button style for the whole page (2.26). */
    private fun miniButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = HydraUi.glassPill(
                Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, this@SecretLanguageActivity)
        }
    }

    /** Flat solid pill for the SELECTED direction
     *  (2.26): the glass pill's white top gloss
     *  drew a white stripe across the teal button. */
    private fun flatPill(fill: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(fill)
        }
    }

    private fun refreshMode() {
        val sel = Color.parseColor("#0F766E")
        if (encodeMode) {
            btnEncode.background = flatPill(sel)
            btnEncode.setTextColor(Color.WHITE)
            btnDecode.background = HydraUi.glassPill(
                Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, this)
            btnDecode.setTextColor(Color.parseColor("#455A64"))
        } else {
            btnDecode.background = flatPill(sel)
            btnDecode.setTextColor(Color.WHITE)
            btnEncode.background = HydraUi.glassPill(
                Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, this)
            btnEncode.setTextColor(Color.parseColor("#455A64"))
        }
        inputBox.hint = if (encodeMode) L("متن را اینجا بنویس…", "Write your text here…")
            else L("متن بیگانه را اینجا بچسبان…", "Paste the alien text here…")
        refreshLinkButtons()
    }

    /** Message-link buttons (2.27): send shows
     *  only for senders in encode mode (the
     *  output is alien text there); refresh shows
     *  for receivers in either mode. */
    private fun refreshLinkButtons() {
        try {
            btnSendToLink.visibility =
                if (encodeMode && AlienSub.senderManage(this) != null) View.VISIBLE else View.GONE
            btnRefreshMsg.visibility =
                if (AlienSub.receiverView(this) != null) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    /** Let a max-5-lines box scroll inside itself
     *  instead of growing, even inside the page's
     *  own ScrollView. */
    private fun makeInnerScrollable(v: View) {
        v.setOnTouchListener { _, ev ->
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (ev.action == MotionEvent.ACTION_UP) {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun convert() {
        try {
            converting = true
            val input = inputBox.text.toString()
            val rawOut = if (encodeMode) SecretLanguage.encode(input) else SecretLanguage.decode(input)
            // Secrecy (2.27): the message-link
            // site's address must never be revealed
            // through this decoder.
            val guarded = !encodeMode && AlienSub.revealsSite(rawOut)
            val out = if (guarded) L("این یک لینک پیام بیگانه است؛ برای دیدنش از بخش پیام بیگانه استفاده کن", "This is an alien message link; open it from the Alien Message section") else rawOut
            outputBox.text = out
            statsLine.text = if (input.isEmpty() || guarded) "" else {
                val inLen = input.length
                val outLen = out.length
                val pct = if (inLen == 0) 0 else (100 * (inLen - outLen)) / inLen
                val tail = when {
                    pct > 0 -> L("$pct٪ کوتاه‌تر", "$pct% shorter")
                    pct == 0 -> L("هم‌اندازه", "same length")
                    else -> L("طولانی‌تر شد", "longer")
                }
                if (encodeMode) L("$inLen نویسه ← $outLen نماد ($tail)", "$inLen chars → $outLen symbols ($tail)")
                else L("$inLen نماد ← $outLen نویسه ($tail)", "$inLen symbols → $outLen chars ($tail)")
            }
            detected = if (guarded) null else detectConfig(out)
            val d = detected
            if (d == null) {
                noteLine.visibility = View.GONE
                btnTransfer.visibility = View.GONE
            } else {
                noteLine.text = if (AppLanguage.isEnglish(this)) d.noteEn else d.noteFa
                noteLine.visibility = View.VISIBLE
                btnTransfer.text = if (AppLanguage.isEnglish(this)) d.btnEn else d.btnFa
                btnTransfer.visibility = View.VISIBLE
            }
            refreshLinkButtons()
        } catch (_: Exception) {
            outputBox.text = ""
            statsLine.text = ""
            noteLine.visibility = View.GONE
            btnTransfer.visibility = View.GONE
            detected = null
        } finally {
            converting = false
        }
    }

    /** What the output is, if it is a config: a
     *  v2ray link, or a parseable WireGuard /
     *  Amnezia block. Anything else -> null, so the
     *  transfer button never offers a broken move. */
    private fun detectConfig(text: String): Detected? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = Regex("(vmess|vless|trojan|ss|socks|hysteria2|hy2|tuic)://\\S+").find(t)
        if (m != null) {
            return Detected("v2ray", m.value,
                "این خروجی یک کانفیگ v2ray است", "This output is a v2ray config",
                "انتقال به v2ray", "Send to v2ray")
        }
        if (t.contains("[Interface]") && t.contains("[Peer]") && t.contains("PrivateKey")) {
            try {
                val f = WgDirect.stripReserved(t)
                if (WgDirect.isAwgConf(f)) AwgConfig.parse(BufferedReader(StringReader(f.trim())))
                else WgConfig.parse(BufferedReader(StringReader(f.trim())))
            } catch (_: Exception) {
                return null
            }
            return if (WgDirect.isAwgConf(t)) {
                Detected("wg", t,
                    "این خروجی یک کانفیگ امنزیا است", "This output is an AmneziaWG config",
                    "انتقال به وایرگارد", "Send to WireGuard")
            } else {
                Detected("wg", t,
                    "این خروجی یک کانفیگ وایرگارد است", "This output is a WireGuard config",
                    "انتقال به وایرگارد", "Send to WireGuard")
            }
        }
        return null
    }

    private fun sha256Hex(s: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
            sb.toString()
        } catch (_: Exception) {
            s.hashCode().toString()
        }
    }

    private fun transferV2ray(link: String) {
        try {
            val prefs = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            val servers = prefs.getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
            if (servers.contains(link)) {
                Toast.makeText(this,
                    L("این کانفیگ از قبل در بخش v2ray هست", "This config is already in the v2ray section"),
                    Toast.LENGTH_SHORT).show()
            } else {
                servers += link
                val arr = org.json.JSONArray(prefs.getString("alien_configs", "[]") ?: "[]")
                val key = XraySpeedtest.dedupKey(link)
                var has = false
                for (i in 0 until arr.length()) if (arr.optString(i) == key) has = true
                if (!has) arr.put(key)
                prefs.edit()
                    .putString("servers", servers.joinToString("\n"))
                    .putString("alien_configs", arr.toString())
                    .apply()
                Toast.makeText(this,
                    L("به بخش v2ray منتقل شد", "Moved to the v2ray section"),
                    Toast.LENGTH_SHORT).show()
            }
            startActivity(Intent(this, VpnActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, L("انتقال انجام نشد", "Transfer failed"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun transferWg(conf: String) {
        try {
            val text = conf.trim()
            val prefs = getSharedPreferences("renamer_wg", MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("conf", "")?.trim() == text) {
                    Toast.makeText(this,
                        L("این تونل از قبل در بخش وایرگارد هست", "This tunnel is already in the WireGuard section"),
                        Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, WireGuardActivity::class.java))
                    return
                }
            }
            val names = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name", "")?.let { names += it }
            }
            var name = L("بیگانه", "Alien")
            if (names.contains(name)) {
                var n = 2
                while (names.contains("$name $n")) n++
                name = "$name $n"
            }
            arr.put(org.json.JSONObject().apply {
                put("name", name)
                put("conf", text)
            })
            val marks = org.json.JSONArray(prefs.getString("alien_tunnels", "[]") ?: "[]")
            marks.put(sha256Hex(text))
            prefs.edit()
                .putString("wg_list", arr.toString())
                .putString("alien_tunnels", marks.toString())
                .apply()
            Toast.makeText(this,
                L("به بخش وایرگارد منتقل شد", "Moved to the WireGuard section"),
                Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, WireGuardActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, L("انتقال انجام نشد", "Transfer failed"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickFile() {
        try {
            val it = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(
                Intent.createChooser(it, L("انتخاب فایل", "Choose a file")), REQ_PICK)
        } catch (_: Exception) {}
    }

    private fun saveOutput() {
        try {
            val it = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, if (encodeMode) "alien.txt" else "decoded.txt")
            }
            startActivityForResult(it, REQ_SAVE)
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            if (requestCode == REQ_PICK) {
                val uri = data.data ?: return
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                if (bytes.size > 2_000_000) {
                    Toast.makeText(this,
                        L("فایل خیلی بزرگ است", "The file is too large"),
                        Toast.LENGTH_SHORT).show()
                    return
                }
                // Strict UTF-8: a non-text file is
                // refused rather than corrupted —
                // a lossy read would break decoding.
                val dec = java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                val text = dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                inputBox.setText(text)
                Toast.makeText(this, L("فایل خوانده شد", "File loaded"), Toast.LENGTH_SHORT).show()
            } else if (requestCode == REQ_SAVE) {
                val uri = data.data ?: return
                contentResolver.openOutputStream(uri)?.use {
                    it.write(outputBox.text.toString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, L("ذخیره شد", "Saved"), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this,
                L("این فایل متنی نیست یا خوانده نشد", "This file is not text or could not be read"),
                Toast.LENGTH_SHORT).show()
        }
    }

    /** Exact-functionality guide, behind the ⓘ
     *  circle (2.26). */
    private fun showGuide() {
        try {
            val d = resources.displayMetrics.density
            val en = AppLanguage.isEnglish(this)
            fun sec(faT: String, faB: String, enT: String, enB: String): Pair<String, String> {
                return if (en) enT to enB else faT to faB
            }
            val sections = listOf(
                sec("این بخش چیست",
                    "متن را به زبانی ناشناخته تبدیل می‌کند که فقط با همین بخش خوانده می‌شود؛ خروجی هیچ حرف فارسی یا انگلیسی ندارد.",
                    "What this is",
                    "It turns text into a strange unknown language that only this section can read back; the output contains no Persian or English letters."),
                sec("رمز کردن",
                    "متن را در کادر بالا بنویس، یا با دکمه فایل یک فایل متنی بده؛ خروجی بیگانه را با کپی خروجی بفرست.",
                    "Encoding",
                    "Write text in the top box, or give a text file with the File button; send the alien output with Copy output."),
                sec("باز کردن",
                    "متن بیگانه را در کادر بالا بچسبان (یا فایل بیگانه را بده)؛ پیام اصلی در خروجی می‌آید. فاصله و خط اضافه در متن چسبانده‌شده نادیده گرفته می‌شود.",
                    "Decoding",
                    "Paste the alien text in the top box (or give the alien file); the original message appears in the output. Extra spaces and lines in pasted text are ignored."),
                sec("چه چیزی پشتیبانی می‌شود",
                    "فارسی، انگلیسی، اعداد، همه علامت‌های کیبورد، کانفیگ v2ray و وایرگارد و فایل‌های متنی مثل .ovpn. همه‌چیز روی خود گوشی انجام می‌شود و چیزی به جایی فرستاده نمی‌شود.",
                    "What is supported",
                    "Persian, English, numbers, every keyboard symbol, v2ray and WireGuard configs, and text files like .ovpn. Everything happens on the phone itself; nothing is sent anywhere."),
                sec("چرا کوتاه‌تر می‌شود",
                    "کلمات پرتکرار و تکه‌های کانفیگ هر کدام یک نماد می‌شوند، پس متن روزمره کوتاه‌تر می‌شود؛ متن پر از ایموجی ممکن است کمی بلندتر شود.",
                    "Why it gets shorter",
                    "Frequent words and config fragments each become one symbol, so everyday text gets shorter; emoji-heavy text may grow slightly."),
                sec("کانفیگ",
                    "اگر خروجی یک کانفیگ باشد، پیام سبز زیر خروجی نوعش را می‌گوید و دکمه انتقال آن را مستقیم به بخش خودش (v2ray یا وایرگارد) می‌برد؛ آن کانفیگ در بخش خودش با رنگ کهکشانی و آیکون بیگانه مشخص می‌شود.",
                    "Configs",
                    "If the output is a config, the green note under the output names its kind, and the transfer button moves it straight to its own section (v2ray or WireGuard); there it is marked with a galaxy colour and the alien icon."),
                sec("فایل",
                    "دکمه فایل متن فایل را به ورودی می‌آورد؛ دکمه ذخیره در فایل خروجی را در یک فایل می‌نویسد. فقط فایل‌های متنی پذیرفته می‌شوند تا رمزگشایی خراب نشود.",
                    "Files",
                    "The File button loads a file's text into the input; Save to file writes the output into a file. Only text files are accepted, so decoding never breaks.")
            )
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            }
            box.addView(TextView(this).apply {
                text = L("راهنمای زبان بیگانه", "Alien Language guide")
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, (14 * d).toInt())
            })
            for ((t, b) in sections) {
                box.addView(TextView(this).apply {
                    text = t; textSize = 15f
                    setTypeface(null, Typeface.BOLD)
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
}
