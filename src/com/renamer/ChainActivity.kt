package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray

/**
 * Double chain ("زنجیره دوتایی"): two hops, one after
 * the other. Traffic goes device -> hop1 -> hop2 ->
 * destination, so no single server sees both your real
 * IP and your destination.
 *
 * Each hop, independently, can be:
 *  - v2ray: picked from the saved v2ray list, or pasted
 *    manually right here (both hops support manual paste).
 *  - emergency: the emergency section's best server,
 *    resolved fresh at connect time, nothing saved.
 *  - openvpn: a saved OpenVPN profile. ONLY as hop2:
 *    Android allows a single VPN tunnel at a time, so
 *    an OpenVPN first hop could never feed a second
 *    hop; as hop2 it works via Xray's loopback proxy.
 *  - wireguard: a saved WireGuard tunnel, run as an
 *    Xray wireguard outbound.
 * Selections persist immediately (no Save button).
 */
class ChainActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private var card1Area: LinearLayout? = null
    private var card2Area: LinearLayout? = null
    private var card1Summary: TextView? = null
    private var card2Summary: TextView? = null
    private var card1Warn: TextView? = null
    private var pendingChainConfig: String? = null
    private var busy = false

    private val poller = object : Runnable {
        override fun run() {
            try { refreshStatus() } catch (_: Throwable) {}
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            fitsSystemWindows = true
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = L("زنجیره دوتایی", "Double chain")
            textSize = 22f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = L("ترافیکت از دو فیلترشکن پشت سر هم رد می‌شود.", "Your traffic passes through two VPN hops, one after the other.")
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24)
        })
        root.addView(Button(this).apply {
            text = L("این قسمت چکار انجام میده؟", "What does this section do?")
            setOnClickListener { showHowItWorks() }
        })
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 24, 0, 24) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // Hop 1 card.
        root.addView(buildHopCard(1))
        // Swap + arrow row.
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        mid.addView(TextView(this).apply {
            text = "↓"
            textSize = 28f
            setPadding(0, 0, 24, 0)
        })
        mid.addView(Button(this).apply {
            text = L("⇅ جابه‌جا کردن دو زنجیره", "⇅ Swap the two hops")
            setOnClickListener {
                ChainEngine.swapHops(this@ChainActivity)
                rebuildCards()
                refreshStatus()
            }
        })
        root.addView(mid)
        // Hop 2 card.
        root.addView(buildHopCard(2))

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 24, 0, 24) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
        connectBtn = Button(this).apply {
            text = L("وصل شدن زنجیره", "Connect chain")
            setOnClickListener { onConnectButton() }
        }
        root.addView(connectBtn)
        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        root.addView(statusText)
        root.addView(TextView(this).apply {
            text = L(
                "هر دو زنجیره باید سالم باشند؛ اگر یکی خراب باشد زنجیره وصل نمی‌شود. زنجیره همیشه کندتر از تک‌اتصال است، چون ترافیکت دو بار رمزنگاری و جابه‌جا می‌شود.",
                "Both hops must be healthy; if either one is down, the chain does not connect. A chain is always slower than a single connection, because your traffic is encrypted and relayed twice."
            )
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 24, 0, 0)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
        refreshStatus()
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    // ---------- Hop cards ----------

    private fun hopTitle(n: Int) =
        if (n == 1) L("زنجیره اول", "First hop") else L("زنجیره دوم", "Second hop")

    private fun buildHopCard(n: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        card.addView(TextView(this).apply {
            text = hopTitle(n)
            textSize = 18f
            setPadding(0, 0, 0, 12)
        })
        val area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (n == 1) card1Area = area else card2Area = area
        card.addView(area)
        val summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 0)
        }
        if (n == 1) card1Summary = summary else card2Summary = summary
        card.addView(summary)
        if (n == 1) {
            val warn = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#C62828"))
                setPadding(0, 8, 0, 0)
            }
            card1Warn = warn
            card.addView(warn)
        }
        rebuildHopArea(n)
        return card
    }

    private fun rebuildCards() {
        rebuildHopArea(1)
        rebuildHopArea(2)
        refreshSummaries()
    }

    private fun rebuildHopArea(n: Int) {
        val area = (if (n == 1) card1Area else card2Area) ?: return
        area.removeAllViews()
        val hop = ChainEngine.hop(this, n)
        val curType = hop?.type ?: ""
        // Type chips.
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val types = listOf(
            "v2ray" to "v2ray",
            "emergency" to L("اضطراری", "Emergency"),
            "openvpn" to "OpenVPN",
            "wireguard" to "WireGuard"
        )
        for ((key, label) in types) {
            val btn = Button(this).apply {
                text = label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundColor(
                    if (key == curType) Color.parseColor("#BBDEFB")
                    else Color.parseColor("#EEEEEE")
                )
                setOnClickListener { onTypePicked(n, key) }
            }
            chips.addView(btn)
        }
        area.addView(chips)
        // Selection area per type.
        val sel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        when (curType) {
            "v2ray" -> {
                sel.addView(Button(this).apply {
                    text = L("انتخاب از لیست v2ray", "Pick from the v2ray list")
                    setOnClickListener { pickV2ray(n) }
                })
                sel.addView(Button(this).apply {
                    text = L("وارد کردن دستی کانفیگ", "Paste a config manually")
                    setOnClickListener { pasteV2ray(n) }
                })
            }
            "emergency" -> {
                sel.addView(TextView(this).apply {
                    text = L(
                        "بهترین سرور اضطراری، در لحظه اتصال خودکار انتخاب می‌شود و چیزی ذخیره نمی‌شود.",
                        "The best emergency server is picked automatically at connect time, and nothing is saved."
                    )
                    textSize = 14f
                })
            }
            "openvpn" -> {
                sel.addView(Button(this).apply {
                    text = L("انتخاب پروفایل OpenVPN", "Pick an OpenVPN profile")
                    setOnClickListener { pickOvpn(n) }
                })
            }
            "wireguard" -> {
                sel.addView(Button(this).apply {
                    text = L("انتخاب تونل WireGuard", "Pick a WireGuard tunnel")
                    setOnClickListener { pickWg(n) }
                })
            }
            else -> {
                sel.addView(TextView(this).apply {
                    text = L("اول نوع زنجیره را از بالا انتخاب کن.", "First pick the hop type above.")
                    textSize = 14f
                    setTextColor(Color.GRAY)
                })
            }
        }
        area.addView(sel)
        refreshSummaries()
    }

    private fun refreshSummaries() {
        fun summaryFor(n: Int): String {
            val hop = ChainEngine.hop(this, n) ?: return L("— انتخاب نشده", "— not picked")
            return when (hop.type) {
                "v2ray" -> L("v2ray: ", "v2ray: ") + hop.label
                "emergency" -> L("اضطراری: بهترین سرور (خودکار)", "Emergency: best server (auto)")
                "openvpn" -> "OpenVPN: " + hop.label
                "wireguard" -> "WireGuard: " + hop.label
                else -> hop.label
            }
        }
        card1Summary?.text = summaryFor(1)
        card2Summary?.text = summaryFor(2)
        card1Warn?.text = if (ChainEngine.hop(this, 1)?.type == "openvpn") {
            L(
                "اوپن‌وی‌پی‌ان فقط می‌تواند زنجیره دوم باشد، چون اندروید فقط یک تونل VPN را هم‌زمان اجازه می‌دهد. با دکمه جابه‌جا کردن، جاها را عوض کن.",
                "OpenVPN can only be the second hop, because Android allows only one VPN tunnel at a time. Use the swap button to switch the hops."
            )
        } else ""
    }

    private fun onTypePicked(n: Int, key: String) {
        if (key == "emergency") {
            ChainEngine.saveHop(this, n, ChainEngine.Hop("emergency", "",
                L("بهترین سرور اضطراری (خودکار)", "Best emergency server (auto)")))
            rebuildHopArea(n)
            return
        }
        // Keep the previous pick when re-tapping the same type.
        val prev = ChainEngine.hop(this, n)
        if (prev?.type == key) {
            rebuildHopArea(n)
            return
        }
        ChainEngine.saveHop(this, n, ChainEngine.Hop(key, "", ""))
        if (n == 1 && key == "openvpn") {
            // OpenVPN can never be hop1: offer the swap
            // right away instead of failing at connect.
            AlertDialog.Builder(this)
                .setTitle(L("اوپن‌وی‌پی‌ان فقط زنجیره دوم", "OpenVPN can only be the second hop"))
                .setMessage(L(
                    "اندروید فقط یک تونل VPN را هم‌زمان اجازه می‌دهد، پس اوپن‌وی‌پی‌ان نمی‌تواند زنجیره اول باشد؛ چون ترافیک باید اول از زنجیره اول رد شود و بعد به اوپن‌وی‌پی‌ان برسد. جاهای دو زنجیره را عوض کنم؟",
                    "Android allows only one VPN tunnel at a time, so OpenVPN cannot be the first hop: traffic must pass the first hop before reaching OpenVPN. Shall I swap the two hops?"
                ))
                .setPositiveButton(L("جابه‌جا کن", "Swap")) { _, _ ->
                    ChainEngine.swapHops(this)
                    rebuildCards()
                }
                .setNegativeButton(L("همین‌طور بماند", "Keep as is"), null)
                .show()
        }
        rebuildHopArea(n)
    }

    // ---------- Pickers ----------

    private fun savedV2rayServers(): List<String> {
        return try {
            getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.map { it.trim() }?.filter {
                    it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
                } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun pickV2ray(n: Int) {
        val servers = savedV2rayServers()
        if (servers.isEmpty()) {
            Toast.makeText(this, L("لیست v2ray خالی است؛ اول در بخش v2ray کانفیگ اضافه کن.", "The v2ray list is empty; add configs in the v2ray section first."), Toast.LENGTH_LONG).show()
            return
        }
        val names = servers.map { XraySpeedtest.displayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(L("انتخاب کانفیگ v2ray", "Pick a v2ray config"))
            .setItems(names) { _, which ->
                ChainEngine.saveHop(this, n, ChainEngine.Hop("v2ray", servers[which], names[which]))
                rebuildHopArea(n)
            }
            .show()
    }

    private fun pasteV2ray(n: Int) {
        val input = EditText(this).apply {
            hint = L("کانفیگ را اینجا بچسبان…", "Paste the config here…")
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle(L("وارد کردن دستی کانفیگ v2ray", "Paste a v2ray config manually"))
            .setView(input)
            .setPositiveButton(L("تأیید", "OK")) { _, _ ->
                val cfg = input.text.toString().trim()
                if (!cfg.contains("://") || XraySpeedtest.outboundFor(cfg) == null) {
                    Toast.makeText(this, L("این کانفیگ قابل استفاده نیست.", "This config cannot be used."), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                ChainEngine.saveHop(this, n, ChainEngine.Hop("v2ray", cfg, XraySpeedtest.displayName(cfg)))
                rebuildHopArea(n)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun pickOvpn(n: Int) {
        val names = try {
            val arr = JSONArray(getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
                .getString("ovpn_list", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
        if (names.isEmpty()) {
            Toast.makeText(this, L("پروفایل OpenVPNای ذخیره نشده است.", "No saved OpenVPN profile."), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(L("انتخاب پروفایل OpenVPN", "Pick an OpenVPN profile"))
            .setItems(names.toTypedArray()) { _, which ->
                ChainEngine.saveHop(this, n, ChainEngine.Hop("openvpn", names[which], names[which]))
                rebuildHopArea(n)
            }
            .show()
    }

    private fun pickWg(n: Int) {
        val names = try {
            val arr = JSONArray(getSharedPreferences("renamer_wg", MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
        if (names.isEmpty()) {
            Toast.makeText(this, L("تونل WireGuardای ذخیره نشده است.", "No saved WireGuard tunnel."), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(L("انتخاب تونل WireGuard", "Pick a WireGuard tunnel"))
            .setItems(names.toTypedArray()) { _, which ->
                ChainEngine.saveHop(this, n, ChainEngine.Hop("wireguard", names[which], names[which]))
                rebuildHopArea(n)
            }
            .show()
    }

    // ---------- Connect / disconnect ----------

    private fun onConnectButton() {
        if (ChainEngine.isRunning(this)) {
            ChainEngine.stopAll(this)
            statusText.text = L("قطع شد.", "Disconnected.")
            refreshStatus()
            return
        }
        if (busy) return
        val h1 = ChainEngine.hop(this, 1)
        val h2 = ChainEngine.hop(this, 2)
        if (h1 == null || h2 == null) {
            statusText.text = L("هر دو زنجیره را انتخاب کن.", "Pick both hops first.")
            return
        }
        if (h1.type == "openvpn") {
            statusText.text = L(
                "اوپن‌وی‌پی‌ان نمی‌تواند زنجیره اول باشد؛ با دکمه جابه‌جا کردن، جاها را عوض کن.",
                "OpenVPN cannot be the first hop; use the swap button to switch the hops."
            )
            return
        }
        busy = true
        statusText.text = L("در حال آماده‌سازی زنجیره…", "Preparing the chain…")
        Thread {
            try {
                ChainEngine.stopAll(this)
                Thread.sleep(500)
                if (h2.type == "openvpn") {
                    startOvpnChain(h1, h2)
                } else {
                    startXrayChain(h1, h2)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    busy = false
                    statusText.text = L("خطا: ", "Error: ") + (t.message ?: t.javaClass.simpleName)
                }
            }
        }.start()
    }

    /** Xray-only chain (v2ray / emergency / wireguard
     *  hops): one Xray instance, hop2 dials through hop1. */
    private fun startXrayChain(h1: ChainEngine.Hop, h2: ChainEngine.Hop) {
        val config = ChainEngine.vpnConfig(this, h1, h2)
        if (config == null) {
            runOnUiThread {
                busy = false
                statusText.text = L(
                    "ساخت کانفیگ زنجیره ناموفق بود؛ یکی از زنجیره‌ها قابل استفاده نیست یا بهترین سرور اضطراری پیدا نشد.",
                    "Building the chain config failed; one hop cannot be used, or no healthy emergency server was found."
                )
            }
            return
        }
        pendingChainConfig = config
        runOnUiThread {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                startActivityForResult(prep, 600)
            } else {
                startXrayChainNow()
            }
        }
    }

    private fun startXrayChainNow() {
        val config = pendingChainConfig ?: return
        pendingChainConfig = null
        if (!CoreInit.init(applicationContext)) {
            busy = false
            statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
            return
        }
        VpnClientService.emergencyConfig = null
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", config)
            putExtra("name", L("زنجیره دوتایی", "Double chain"))
            putExtra("session_tag", "chain")
        }
        startService(intent)
        ChainEngine.setRunning(this, "xray")
        busy = false
        handler.postDelayed({ refreshStatus() }, 1500)
    }

    /** OpenVPN as hop2: Xray runs as a loopback proxy
     *  for hop1, and the OpenVPN client connects to its
     *  server through that proxy. */
    private fun startOvpnChain(h1: ChainEngine.Hop, h2: ChainEngine.Hop) {
        val proxyConfig = ChainEngine.proxyConfig(this, h1)
        if (proxyConfig == null) {
            runOnUiThread {
                busy = false
                statusText.text = L(
                    "ساخت پروکسی زنجیره اول ناموفق بود؛ زنجیره اول قابل استفاده نیست.",
                    "Building the first hop's proxy failed; the first hop cannot be used."
                )
            }
            return
        }
        if (!ChainEngine.startProxyCore(this, proxyConfig)) {
            runOnUiThread {
                busy = false
                statusText.text = L("پروکسی داخلی زنجیره راه‌اندازی نشد.", "The chain's internal proxy failed to start.")
            }
            return
        }
        val profile = ChainEngine.ovpnProfile(this, h2.ref)
        if (profile == null) {
            ChainEngine.stopProxyCore()
            runOnUiThread {
                busy = false
                statusText.text = L("پروفایل OpenVPN پیدا نشد؛ شاید حذف شده باشد.", "OpenVPN profile not found; it may have been deleted.")
            }
            return
        }
        runOnUiThread {
            OvpnEngine.chainProxyPort = ChainEngine.PROXY_HTTP_PORT
            OvpnEngine.chainProxyActive = true
            ChainEngine.setRunning(this, "ovpn")
            OvpnEngine.connect(this, profile) { _ -> handler.postDelayed({ refreshStatus() }, 1500) }
            busy = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 600) {
            if (resultCode == RESULT_OK) startXrayChainNow()
            else {
                busy = false
                statusText.text = L("مجوز VPN داده نشد.", "VPN permission was not granted.")
            }
        } else if (requestCode == 701) {
            OvpnEngine.onPrepareResult(this, resultCode == RESULT_OK)
        }
    }

    private fun refreshStatus() {
        val running = ChainEngine.isRunning(this)
        val h1 = ChainEngine.hop(this, 1)
        val h2 = ChainEngine.hop(this, 2)
        connectBtn.text = if (running) L("قطع زنجیره", "Disconnect chain")
            else L("وصل شدن زنجیره", "Connect chain")
        connectBtn.setBackgroundColor(
            if (running) Color.parseColor("#2E9E5B") else Color.parseColor("#DDDDDD")
        )
        val hops = if (h1 != null && h2 != null) {
            L("زنجیره اول: ", "First hop: ") + hopLabel(h1) + "\n" +
                L("زنجیره دوم: ", "Second hop: ") + hopLabel(h2)
        } else L("زنجیره‌ها را انتخاب کن.", "Pick the hops.")
        val state = when {
            running -> L("وصل است.", "Connected.")
            ChainEngine.prefs(this).getBoolean("chain_running", false) -> {
                val err = if (ChainEngine.prefs(this).getString("chain_mode", "") == "ovpn") {
                    OvpnEngine.lastError
                } else {
                    VpnClientService.lastError
                }
                L("وصل نشد.", "Not connected.") + (err?.let { "\n$it" } ?: "")
            }
            else -> L("قطع است.", "Disconnected.")
        }
        val ovpnNote = if (h2?.type == "openvpn") {
            "\n" + L(
                "ترکیب با OpenVPN آزمایشی است و نیاز به تست روی گوشی دارد.",
                "The OpenVPN combination is experimental and needs on-device testing."
            )
        } else ""
        val wgNote = if (h1?.type == "wireguard" || h2?.type == "wireguard") {
            "\n" + L(
                "ترکیب با WireGuard آزمایشی است و نیاز به تست روی گوشی دارد.",
                "The WireGuard combination is experimental and needs on-device testing."
            )
        } else ""
        statusText.text = "$hops\n$state$ovpnNote$wgNote"
    }

    private fun hopLabel(hop: ChainEngine.Hop): String {
        return when (hop.type) {
            "v2ray" -> "v2ray: " + hop.label
            "emergency" -> L("اضطراری (بهترین سرور)", "Emergency (best server)")
            "openvpn" -> "OpenVPN: " + hop.label
            "wireguard" -> "WireGuard: " + hop.label
            else -> hop.label
        }
    }

    private fun showHowItWorks() {
        AlertDialog.Builder(this)
            .setTitle(L("زنجیره دوتایی چکار می‌کند؟", "What does Double chain do?"))
            .setMessage(L(
                """زنجیره دوتایی یعنی ترافیکت به‌جای یک فیلترشکن، از دو فیلترشکن پشت سر هم رد می‌شود: اول زنجیره اول، بعد زنجیره دوم، بعد اینترنت. مسیر دقیق این است: گوشی تو ← زنجیره اول ← زنجیره دوم ← سایت مقصد.

این بخش دقیقاً به چه دردی می‌خورد؟
۱) حریم خصوصی دو لایه: زنجیره اول آدرس واقعی تو را می‌بیند ولی نمی‌فهمد به کجا می‌روی؛ زنجیره دوم مقصد را می‌بیند ولی آدرس واقعی تو را نمی‌بیند. پس هیچ سروری به‌تنهایی هم هویت تو و هم مقصدت را با هم ندارد.
۲) دور زدن فیلترینگی که تک‌اتصال را می‌بندد: گاهی اتصال مستقیم به یک سرور بسته می‌شود، ولی همان سرور از پشت یک سرور دیگر در دسترس است؛ زنجیره دوم از پشت زنجیره اول به مقصد می‌رسد.
۳) وقتی یک سرور کند یا ناپایدار است، ترکیب دو سرور سالم می‌تواند مسیر بهتری بسازد.

چطور استفاده کنی؟
برای هر زنجیره، نوعش را انتخاب کن: v2ray (از لیست ذخیره‌شده‌ات یا با چسباندن دستی یک کانفیگ)، اضطراری (بهترین سرور، خودکار و بدون ذخیره شدن)، OpenVPN (با انتخاب پروفایل)، یا WireGuard (با انتخاب تونل). بعد دکمه وصل را بزن. با دکمه جابه‌جا کردن هم می‌توانی جای دو زنجیره را عوض کنی.

دو نکته مهم:
• اوپن‌وی‌پی‌ان فقط می‌تواند زنجیره دوم باشد، چون اندروید فقط یک تونل VPN را هم‌زمان اجازه می‌دهد؛ پس ترافیک نمی‌تواند اول از اوپن‌وی‌پی‌ان رد شود و بعد به زنجیره دیگری برسد. اگر اوپن‌وی‌پی‌ان را برای زنجیره اول انتخاب کنی، برنامه خودش پیشنهاد جابه‌جا کردن می‌دهد.
• زنجیره همیشه کندتر از تک‌اتصال است و هر دو زنجیره باید سالم باشند؛ اگر یکی خراب باشد زنجیره وصل نمی‌شود. ترکیب‌های دارای WireGuard و OpenVPN با روش‌های استاندارد هسته‌ها ساخته شده‌اند ولی هنوز روی گوشی تست نشده‌اند؛ اگر ترکیبی وصل نشد، خطایش در همین صفحه نشان داده می‌شود، نه اینکه بی‌صدا تک‌اتصال شود.""",
                """Double chain means your traffic passes through two VPN hops, one after the other, instead of one: first hop, then second hop, then the internet. The exact path is: your phone → first hop → second hop → destination.

What is this section actually for?
1) Two-layer privacy: the first hop sees your real address but not where you are going; the second hop sees the destination but not your real address. So no single server holds both your identity and your destination together.
2) Bypassing filtering that blocks a single connection: sometimes a direct connection to a server is blocked, but the same server is reachable from behind another server; the second hop reaches the destination from behind the first hop.
3) When one server is slow or unstable, combining two healthy servers can build a better path.

How to use it?
For each hop, pick its type: v2ray (from your saved list, or by pasting a config manually), Emergency (the best server, automatic, nothing saved), OpenVPN (by picking a profile), or WireGuard (by picking a tunnel). Then tap Connect. The swap button switches the two hops.

Two important notes:
• OpenVPN can only be the second hop, because Android allows only one VPN tunnel at a time; so traffic cannot pass OpenVPN first and then reach another hop. If you pick OpenVPN as the first hop, the app itself offers to swap them.
• A chain is always slower than a single connection, and both hops must be healthy; if either one is down, the chain does not connect. Combinations with WireGuard and OpenVPN are built with the cores' standard mechanisms but have not been tested on a real device yet; if a combination fails to connect, its error is shown on this page, never silently downgraded to a single hop."""
            ))
            .setPositiveButton(L("باشه", "OK"), null)
            .show()
    }
}
