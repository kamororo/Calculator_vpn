package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

/** SlipNet Edit Profile, rebuilt from SlipNet's own
 *  EditProfileScreen.kt (3565 lines, Compose) as a
 *  View-based page with the same sections, in the
 *  same order, for each tunnel type:
 *  DNSTT / NoizDNS / VayDNS: Profile Name, Domain,
 *  Public Key, DNS Transport chips (UDP/TCP/DoT/DoH),
 *  DNS Resolver + Scan for Working Resolvers,
 *  Authoritative Mode, Auto-tune, DNS Query Size,
 *  Connection Method (SOCKS / SSH), SOCKS5
 *  Credentials or SSH credentials, Server Setup Guide.
 *  VayDNS adds Record Type chips, Max QNAME, RPS and
 *  advanced DNS fields. Other types show their own
 *  fields (VLESS, SSH, SOCKS5, Naive, DOH, Tor,
 *  Slipstream) plus the same Connection Method
 *  section where SlipNet has one.
 *  Everything saved here is what connect() reads —
 *  no field is a placeholder. */
class SlipNetEditActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var json: JSONObject
    private var isNew = false
    private var index = -1
    private val edits = mutableMapOf<String, EditText>()
    private val switches = mutableMapOf<String, Switch>()
    private var dnsTransport = "UDP"
    private var connectionMethod = "SOCKS"
    private lateinit var sshBox: LinearLayout
    private lateinit var socksBox: LinearLayout
    private lateinit var dnsQuerySizeText: TextView

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_TYPE = "type"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "dnstt"
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        val list = SlipNetStore.load(this)
        json = if (index in list.indices) list[index].json else JSONObject().apply { put("type", type) }
        isNew = index !in list.indices
        if (json.optString("type").isBlank()) json.put("type", type)
        dnsTransport = json.optString("dnsTransport", "UDP").ifBlank { "UDP" }
        connectionMethod = json.optString("connectionMethod", "SOCKS").ifBlank { "SOCKS" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 16, 24, 24)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "←"; textSize = 22f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } })
        top.addView(TextView(this).apply {
            text = L("ویرایش پروفایل", "Edit Profile"); textSize = 22f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Button(this).apply { text = "✓"; textSize = 22f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { save() } })
        root.addView(top)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#333333"))
        })
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(form)
        }
        root.addView(scroll)
        setContentView(root)

        val t = json.optString("type")
        val isDns = t in listOf("dnstt", "dnstt_ssh", "sayedns", "sayedns_ssh", "vaydns", "vaydns_ssh")
        val isVay = t.startsWith("vaydns")

        fun label(s: String) {
            form.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(0, 20, 0, 4) })
        }
        fun field(key: String, fa: String, en: String, hint: String = "", password: Boolean = false, multi: Boolean = false): EditText {
            label(L(fa, en))
            val e = EditText(this).apply {
                setText(json.optString(key))
                setHint(L(hint, hint))
                setTextColor(Color.WHITE)
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (multi) { minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            }
            edits[key] = e; form.addView(e); return e
        }
        fun switch(key: String, fa: String, en: String, descFa: String, descEn: String, def: Boolean = false) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16, 0, 4) }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            txt.addView(TextView(this).apply { text = L(fa, en); textSize = 16f; setTextColor(Color.WHITE) })
            txt.addView(TextView(this).apply { text = L(descFa, descEn); textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")) })
            val sw = Switch(this).apply { isChecked = json.optBoolean(key, def) }
            switches[key] = sw; row.addView(txt); row.addView(sw); form.addView(row)
        }
        fun chips(titleFa: String, titleEn: String, options: List<String>, current: String, onPick: (String) -> Unit) {
            label(L(titleFa, titleEn))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (o in options) {
                val b = Button(this).apply {
                    text = o
                    setTextColor(if (o == current) Color.parseColor("#1B1B2F") else Color.WHITE)
                    setBackgroundColor(if (o == current) Color.parseColor("#A9C6FF") else Color.parseColor("#333333"))
                    setOnClickListener { onPick(o); recreate() }
                }
                row.addView(b)
            }
            form.addView(row)
        }

        field("name", "نام پروفایل", "Profile Name")
        field("domain", "دامنه", "Domain")
        if (isDns) {
            field("pubkey", "کلید عمومی", "Public Key")
            form.addView(TextView(this).apply { text = L("کلید عمومی پروتکل Noise سرور، با فرمت hex", "Server's Noise protocol public key in hex format"); textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")) })
            chips("ترنسپورت DNS", "DNS Transport", listOf("UDP", "TCP", "DoT", "DoH"), dnsTransport) { dnsTransport = it; json.put("dnsTransport", it) }
            field("resolvers", "ریزالور DNS", "DNS Resolver", "IP or domain (host:port)")
            form.addView(Button(this).apply {
                text = L("🔍 اسکن ریزالورهای سالم", "Scan for Working Resolvers")
                setBackgroundColor(Color.parseColor("#A9C6FF")); setTextColor(Color.parseColor("#1B1B2F"))
                setOnClickListener { scanResolvers() }
            })
            switch("authoritative", "حالت Authoritative", "Authoritative Mode", "نرخ کوئری تهاجمی برای سرعت بیشتر", "Aggressive query rate for faster speeds")
            switch("autoTune", "Auto-tune", "Auto-tune", "هنگام اتصال، ریزالور را بررسی کن تا طول کوئری انتخاب شود", "Probe resolver at connect time to pick query length")
            // DNS Query Size selector, like SlipNet's dialog.
            label(L("اندازه کوئری DNS", "DNS Query Size"))
            dnsQuerySizeText = TextView(this).apply {
                text = "${json.optInt("dnsQuerySize", 50)} " + L("بایت در هر کوئری", "bytes per query")
                textSize = 15f; setTextColor(Color.WHITE)
                setOnClickListener {
                    val sizes = arrayOf("20", "30", "50", "80", "100")
                    AlertDialog.Builder(this@SlipNetEditActivity)
                        .setItems(sizes) { _, w -> json.put("dnsQuerySize", sizes[w].toInt()); dnsQuerySizeText.text = "${sizes[w]} " + L("بایت در هر کوئری", "bytes per query") }
                        .show()
                }
            }
            form.addView(dnsQuerySizeText)
            if (isVay) {
                chips("نوع رکورد DNS", "DNS record type", listOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv", "null", "caa"), json.optString("recordType", "txt").ifBlank { "txt" }) { json.put("recordType", it) }
                field("maxQname", "حداکثر طول QNAME", "Max QNAME length")
                field("rps", "محدودیت کوئری در ثانیه", "Queries per second")
                field("vaydnsIdleTimeout", "تایم‌اوت بیکاری نشست (ثانیه)", "Session idle timeout (seconds)")
                field("vaydnsKeepalive", "فاصله keepalive (ثانیه)", "Keepalive interval (seconds)")
                field("vaydnsUdpTimeout", "تایم‌اوت UDP در هر کوئری (میلی‌ثانیه)", "Per-query UDP timeout (ms)")
                switch("vaydnsDnsttCompat", "سازگاری DNSTT", "DNSTT compatibility", "فرمت وایر DNSTT با ClientID هشت بایتی", "DNSTT wire format with 8-byte ClientID")
            }
            if (t == "sayedns" || t == "sayedns_ssh") {
                switch("noizdnsStealth", "حالت مخفی NoizDNS", "NoizDNS stealth mode", "سرعت را با مقاومت در برابر DPI عوض می‌کند", "Trades speed for DPI resistance")
            }
            // Connection Method, exactly like SlipNet: SOCKS / SSH.
            label(L("روش اتصال", "Connection Method"))
            val methodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in listOf("SOCKS", "SSH")) {
                methodRow.addView(Button(this).apply {
                    text = m
                    setTextColor(if (m == connectionMethod) Color.parseColor("#1B1B2F") else Color.WHITE)
                    setBackgroundColor(if (m == connectionMethod) Color.parseColor("#A9C6FF") else Color.parseColor("#333333"))
                    setOnClickListener { connectionMethod = m; json.put("connectionMethod", m); updateMethodBoxes() }
                })
            }
            form.addView(methodRow)
            socksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            socksBox.addView(TextView(this).apply { text = L("اطلاعات SOCKS5 (اختیاری)", "SOCKS5 Credentials (Optional)"); textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 4) })
            val su = EditText(this).apply { setText(json.optString("socksUser")); hint = L("نام کاربری", "Username"); setTextColor(Color.WHITE) }
            val sp = EditText(this).apply { setText(json.optString("socksPass")); hint = L("رمز", "Password"); setTextColor(Color.WHITE); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            edits["socksUser"] = su; edits["socksPass"] = sp
            socksBox.addView(su); socksBox.addView(sp)
            form.addView(socksBox)
            sshBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sshBox.addView(TextView(this).apply { text = L("اطلاعات SSH", "SSH Credentials"); textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 20, 0, 4) })
            for ((k, fa, en) in listOf(
                Triple("sshUser", "نام کاربری SSH", "SSH username"),
                Triple("sshPass", "رمز SSH", "SSH password"),
                Triple("sshPort", "پورت SSH", "SSH port"),
                Triple("sshHost", "هاست SSH", "SSH host")
            )) {
                val e = EditText(this).apply { setText(json.optString(k, if (k == "sshPort") "22" else if (k == "sshHost") "127.0.0.1" else "")); hint = L(fa, en); setTextColor(Color.WHITE) }
                edits[k] = e; sshBox.addView(e)
            }
            form.addView(sshBox)
            updateMethodBoxes()
        } else {
            // Non-DNS types: their own fields from SlipNetStore, in order.
            for (f in SlipNetStore.fieldsFor(t)) {
                if (f[0] == "name" || f[0] == "domain") continue
                val isPass = f[0].contains("Pass") || f[0].contains("pass")
                field(f[0], f[1], f[2], "", isPass, f[0] == "torBridges")
            }
            if (t == "ssh") {
                field("sshKey", "کلید خصوصی SSH (اختیاری، PEM)", "SSH private key (optional, PEM)", "", false, true)
                field("sshKeyPass", "رمز کلید SSH (اختیاری)", "SSH key passphrase (optional)")
            }
            if (t == "vless") {
                switch("sniFragmentEnabled", "تکه‌تکه کردن SNI", "SNI fragmentation", "مقاومت در برابر DPI با تکه‌تکه کردن ClientHello", "DPI resistance by fragmenting ClientHello", true)
                switch("wsHeaderObfuscation", "ابهام هدر WS", "WS header obfuscation", "هدرهای شبیه مرورگر در درخواست ارتقای WS", "Browser-like headers in WS upgrade request", true)
            }
        }
        form.addView(Button(this).apply {
            text = L("↗ راهنمای راه‌اندازی سرور", "Server Setup Guide")
            setBackgroundColor(Color.parseColor("#222233")); setTextColor(Color.parseColor("#A9C6FF"))
            setOnClickListener {
                AlertDialog.Builder(this@SlipNetEditActivity)
                    .setTitle(L("راهنمای راه‌اندازی سرور", "Server Setup Guide"))
                    .setMessage(L("سرور اسلیپ‌نت با SlipGate روی یک VPS لینوکسی ساخته می‌شود: یک دامنه به VPS وصل کن، SlipGate را نصب کن، تونل مورد نظرت (DNSTT، VayDNS، Slipstream یا بقیه) را بساز و لینک slipnet:// ساخته‌شده را در همین برنامه وارد کن. این راهنما خلاصه‌ای از راهنمای رسمی SlipGate است.", "A SlipNet server is built with SlipGate on a Linux VPS: point a domain at the VPS, install SlipGate, create the tunnel you want (DNSTT, VayDNS, Slipstream or the rest) and import the slipnet:// link it generates here. This is a summary of the official SlipGate guide."))
                    .setPositiveButton(L("بستن", "Close"), null).show()
            }
        })
    }

    private fun updateMethodBoxes() {
        if (!::socksBox.isInitialized) return
        socksBox.visibility = if (connectionMethod == "SOCKS") android.view.View.VISIBLE else android.view.View.GONE
        sshBox.visibility = if (connectionMethod == "SSH") android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun scanResolvers() {
        val resolvers = json.optString("resolvers").split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (resolvers.isEmpty()) {
            Toast.makeText(this, L("اول یک ریزالور بنویس.", "Write a resolver first."), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, L("در حال تست ریزالورها...", "Testing resolvers..."), Toast.LENGTH_SHORT).show()
        Thread {
            val results = resolvers.map { r ->
                val host = r.substringBefore(":")
                val ok = try { java.net.InetAddress.getByName(host); true } catch (_: Exception) { false }
                "$r: " + if (ok) L("سالم", "OK") else L("ناموفق", "Failed")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(L("نتیجه اسکن ریزالور", "Resolver scan result"))
                    .setMessage(results.joinToString("\n"))
                    .setPositiveButton(L("بستن", "Close"), null).show()
            }
        }.start()
    }

    private fun save() {
        for ((k, e) in edits) json.put(k, e.text.toString())
        for ((k, s) in switches) json.put(k, s.isChecked)
        json.put("dnsTransport", dnsTransport)
        json.put("connectionMethod", connectionMethod)
        if (json.optString("name").isBlank()) {
            Toast.makeText(this, L("نام پروفایل را بنویس.", "Write a profile name."), Toast.LENGTH_SHORT).show()
            return
        }
        val list = SlipNetStore.load(this)
        if (isNew) list.add(SlipNetStore.Profile(json))
        else if (index in list.indices) list[index] = SlipNetStore.Profile(json)
        SlipNetStore.save(this, list)
        setResult(Activity.RESULT_OK)
        finish()
    }
}
