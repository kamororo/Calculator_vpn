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
    private lateinit var connectBtn: Button
    // 2.41: chain info card (below the connect
    // button, only while a chain is connected).
    private lateinit var chainInfoCard: android.widget.LinearLayout
    private lateinit var chainHop1Tv: android.widget.TextView
    private lateinit var chainHop2Tv: android.widget.TextView
    private lateinit var chainExitTv: android.widget.TextView
    private var chainInfoKey: String? = null
    // 2.41: the running Xray chain core's socks port
    // (parsed at chain start; -1 = unknown).
    @Volatile private var chainSocksPort: Int = -1
    private var chainRingLight = false
    private val chainRingRunnable = object : Runnable {
        override fun run() {
            try { chainRingLight = !chainRingLight; paintChainButton() }
            catch (_: Throwable) {}
            handler.postDelayed(this, 1500)
        }
    }
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
            setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(16))
            // 1.81: light page background so the
            // white hop cards read as cards.
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            fitsSystemWindows = true
        }
        scroll.addView(root)
        setContentView(scroll)

        // 2.57: guide + log at the top. In Persian
        // the row reads right-to-left (title right,
        // icons left); the help control is a small
        // icon-only "?" circle beside the log icon,
        // and the log icon keeps a real end margin
        // so it never looks clipped at the edge.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutDirection = if (AppLanguage.isEnglish(this@ChainActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            // 2.58: extra side breathing room so
            // the title never sits near an edge
            // (8dp here + 16dp root padding).
            setPadding(dpPx(8), 0, dpPx(8), 0)
            addView(TextView(this@ChainActivity).apply {
                text = L("زنجیره دوتایی", "Double chain")
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dpPx(8), 0)
                }
            })
            addView(View(this@ChainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@ChainActivity).apply {
                text = "?"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0D47A1"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#E3F2FD"))
                    setStroke(dpPx(1), Color.parseColor("#90CAF9"))
                }
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dpPx(30), dpPx(30)).apply {
                    setMargins(dpPx(4), 0, dpPx(4), 0)
                }
                setOnClickListener { showHowItWorks() }
            })
            addView(ImageView(this@ChainActivity).apply {
                setImageBitmap(HydraUi.logIconBitmap(this@ChainActivity, 36))
                val d8 = dpPx(8)
                setPadding(d8, d8, d8, d8)
                background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 18f, this@ChainActivity)
                layoutParams = LinearLayout.LayoutParams(dpPx(36), dpPx(36)).apply {
                    setMargins(dpPx(4), 0, dpPx(4), 0)
                }
                setOnClickListener { showChainLogDialog() }
            })
        })
        // 1.96: home-page chain icon at the top, then
        // the guide section. The page's own
        // explanatory lines moved into the guide
        // dialog (showHowItWorks holds the full text).
        try {
            val bmp = android.graphics.BitmapFactory.decodeStream(assets.open("chain.png"))
            root.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dpPx(96), dpPx(96)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        } catch (_: Exception) {}
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // Hop 1 card.
        root.addView(buildHopCard(1))
        // Swap row (2.57): full-width and centered
        // with real dp padding, so the circle is
        // always fully visible and centered between
        // the cards (it used to be a wrap-content
        // row with raw-pixel padding, which left it
        // off-center and cramped against the cards).
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dpPx(10), 0, dpPx(10))
        }
        mid.addView(circleButton("⇅", 48, 18f) {
            ChainEngine.swapHops(this@ChainActivity)
            rebuildCards()
            refreshStatus()
        }.apply { elevation = dpPx(4).toFloat() })
        root.addView(mid)
        // Hop 2 card.
        root.addView(buildHopCard(2))

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
        // 1.96: small minimal circle button. Its ring
        // (the stroke) blinks gently every 1.5s; the
        // fill never blinks (see paintChainButton).
        connectBtn = Button(this).apply {
            textSize = 13f
            isAllCaps = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpPx(112), dpPx(112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                // 2.0: breathing room so the ring/shadow never
                // touches its neighbours.
                setMargins(0, dpPx(4), 0, dpPx(4))
            }
            setOnClickListener { onConnectButton() }
        }
        root.addView(connectBtn)
        paintChainButton()
        // 2.41: chain info card — hop-1 server IP,
        // hop-2 server IP, current exit IP. Shown
        // only while a chain is connected.
        chainInfoCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#D8DEE8"), 16f, this@ChainActivity)
            setPadding(dpPx(16), dpPx(12), dpPx(16), dpPx(12))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dpPx(12), 0, 0)
            }
            visibility = View.GONE
        }
        chainInfoCard.addView(android.widget.TextView(this).apply {
            text = L("اطلاعات اتصال زنجیره", "Chain connection info")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, 0, 0, dpPx(8))
        })
        chainHop1Tv = android.widget.TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#333333")) }
        chainHop2Tv = android.widget.TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#333333")) }
        chainExitTv = android.widget.TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#333333")) }
        chainInfoCard.addView(chainHop1Tv)
        chainInfoCard.addView(chainHop2Tv)
        chainInfoCard.addView(chainExitTv)
        root.addView(chainInfoCard)
        // 2.57: bottom breathing room, so the orb
        // and the info card never sit under the
        // phone's nav buttons.
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpPx(32))
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
        refreshStatus()
        handler.removeCallbacks(chainRingRunnable)
        handler.post(chainRingRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        handler.removeCallbacks(chainRingRunnable)
        super.onPause()
    }

    // ---------- UI helpers (1.79): pill + circle buttons ----------

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** A pill button: fully rounded, soft fill +
     *  stroke. Selected pills get the blue treatment. */
    private fun pillButton(text: String, selected: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(if (selected) Color.parseColor("#0D47A1") else Color.parseColor("#333333"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpPx(24).toFloat()
                setColor(if (selected) Color.parseColor("#BBDEFB") else Color.parseColor("#EEEEEE"))
                setStroke(dpPx(1), if (selected) Color.parseColor("#64B5F6") else Color.parseColor("#DDDDDD"))
            }
            minimumWidth = 0
            // 2.0: uniform pill height so no row looks uneven.
            minimumHeight = dpPx(40)
            setPadding(dpPx(8), dpPx(10), dpPx(8), dpPx(10))
            setOnClickListener { onClick() }
        }
    }

    /** A true circle button with a glyph label. */
    private fun circleButton(text: String, sizeDp: Int, textSize: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            this.textSize = textSize
            isAllCaps = false
            setTextColor(Color.parseColor("#0D47A1"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E3F2FD"))
                setStroke(dpPx(2), Color.parseColor("#90CAF9"))
            }
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpPx(sizeDp), dpPx(sizeDp))
            setOnClickListener { onClick() }
        }
    }

    // ---------- Hop cards ----------

    private fun hopTitle(n: Int) =
        if (n == 1) L("زنجیره اول", "First hop") else L("زنجیره دوم", "Second hop")

    private fun buildHopCard(n: Int): LinearLayout {
        // 1.81: a real modern card — white, rounded,
        // soft stroke + elevation — instead of the
        // old flat grey rectangle.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(20), dpPx(20), dpPx(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpPx(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dpPx(1), Color.parseColor("#E0E0E0"))
            }
            elevation = dpPx(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        card.addView(TextView(this).apply {
            text = hopTitle(n)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
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
        // 2.0: uniform chips — single-line, no wrap, centered
        // row, tighter padding — no chip ever looks cut.
        chips.gravity = Gravity.CENTER_VERTICAL
        for ((key, label) in types) {
            val btn = pillButton(label, key == curType) { onTypePicked(n, key) }.apply {
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 12f
                setPadding(dpPx(6), dpPx(10), dpPx(6), dpPx(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dpPx(2), 0, dpPx(2), 0)
                }
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
                sel.addView(pillButton(L("انتخاب از لیست v2ray", "Pick from the v2ray list"), false) { pickV2ray(n) })
                sel.addView(pillButton(L("وارد کردن دستی کانفیگ", "Paste a config manually"), false) { pasteV2ray(n) })
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
                sel.addView(pillButton(L("انتخاب پروفایل OpenVPN", "Pick an OpenVPN profile"), false) { pickOvpn(n) })
            }
            "wireguard" -> {
                sel.addView(pillButton(L("انتخاب تونل WireGuard", "Pick a WireGuard tunnel"), false) { pickWg(n) })
            }
            else -> {
                sel.addView(TextView(this).apply {
                    text = L("اول نوع زنجیره را از بالا انتخاب کن.", "First pick the hop type above.")
                    textSize = 14f
                    setTextColor(Color.GRAY)
                })
            }
        }
        // 1.81: the selection buttons used to
        // touch each other vertically — full width
        // + a gap between every two of them.
        for (i in 0 until sel.childCount) {
            sel.getChildAt(i).layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (i < sel.childCount - 1) dpPx(10) else 0
            }
        }
        area.addView(sel)
        refreshSummaries()
    }

    private fun refreshSummaries() {
        fun summaryFor(n: Int): CharSequence {
            val hop = ChainEngine.hop(this, n) ?: return L("— انتخاب نشده", "— not picked")
            val base = when (hop.type) {
                "v2ray" -> L("v2ray: ", "v2ray: ") + hop.label
                "emergency" -> L("اضطراری: بهترین سرور (خودکار)", "Emergency: best server (auto)")
                "openvpn" -> "OpenVPN: " + hop.label
                "wireguard" -> "WireGuard: " + hop.label
                else -> hop.label
            }
            val tick = hopHealthTick(n) ?: return base
            val sb = android.text.SpannableStringBuilder(base)
            val start = sb.length
            sb.append(tick.first)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(tick.second)),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
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

    // ---------- Hop health (2.22) ----------
    // Stored per hop in renamer_chain prefs: "ok" /
    // "bad" + the ping when known. The summary shows
    // a small green tick (or a faded red cross) from
    // it. A missing entry shows no tick at all.
    private fun setHopHealth(n: Int, ok: Boolean, pingMs: Long?) {
        try {
            ChainEngine.prefs(this).edit()
                .putString("hop${n}_health", if (ok) "ok" else "bad")
                .putString("hop${n}_ping", pingMs?.toString() ?: "")
                .apply()
        } catch (_: Exception) {}
    }

    private fun clearHopHealth(n: Int) {
        try {
            ChainEngine.prefs(this).edit().remove("hop${n}_health").remove("hop${n}_ping").apply()
        } catch (_: Exception) {}
    }

    private fun hopHealthTick(n: Int): Pair<String, String>? {
        return try {
            val sp = ChainEngine.prefs(this)
            when (sp.getString("hop${n}_health", null)) {
                "ok" -> {
                    val ping = sp.getString("hop${n}_ping", null)?.takeIf { it.isNotBlank() }
                    ("  ✓" + if (ping != null) " ${ping}ms" else "") to "#2E7D32"
                }
                "bad" -> "  ✗" to "#E57373"
                else -> null
            }
        } catch (_: Exception) { null }
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
        // 2.22: the picker tests pings in the background
        // and shows each config's ping live, so hop1 can
        // be picked by health without connecting first.
        // Tapping a row picks it right away; a
        // background authority test then stores that
        // hop's health for the summary tick.
        try { CoreInit.init(applicationContext) } catch (_: Exception) {}
        val dlg = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpPx(20), dpPx(16), dpPx(20), dpPx(16))
        }
        root.addView(TextView(this).apply {
            text = L("انتخاب کانفیگ v2ray", "Pick a v2ray config")
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpPx(12))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpPx(420)))
        val pingViews = HashMap<Int, TextView>()
        var stopped = false
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        fun closePicker() {
            stopped = true
            try { pool.shutdownNow() } catch (_: Exception) {}
            try { dlg.dismiss() } catch (_: Exception) {}
        }
        for ((i, srv) in servers.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpPx(8), 0, dpPx(8))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            row.addView(TextView(this).apply {
                text = XraySpeedtest.displayName(srv)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val pingTv = TextView(this).apply {
                text = "…"
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(dpPx(12), 0, 0, 0)
            }
            pingViews[i] = pingTv
            row.addView(pingTv)
            row.setOnClickListener {
                val name = XraySpeedtest.displayName(srv)
                ChainEngine.saveHop(this, n, ChainEngine.Hop("v2ray", srv, name))
                clearHopHealth(n)
                Thread {
                    try {
                        val ms = XraySpeedtest.measureOne(srv)
                        setHopHealth(n, ms >= 0, if (ms >= 0) ms else null)
                        runOnUiThread { try { refreshSummaries() } catch (_: Exception) {} }
                    } catch (_: Exception) {}
                }.start()
                closePicker()
                rebuildHopArea(n)
            }
            list.addView(row)
        }
        dlg.setContentView(root)
        dlg.setOnDismissListener {
            stopped = true
            try { pool.shutdownNow() } catch (_: Exception) {}
        }
        dlg.show()
        for ((i, srv) in servers.withIndex()) {
            pool.submit {
                if (stopped) return@submit
                val ms = try { XraySpeedtest.measureOne(srv) } catch (_: Exception) { -1L }
                if (stopped) return@submit
                runOnUiThread {
                    try {
                        val tv = pingViews[i] ?: return@runOnUiThread
                        if (ms >= 0) {
                            tv.text = "${ms}ms"
                            tv.setTextColor(Color.parseColor("#2E7D32"))
                        } else {
                            tv.text = "—"
                            tv.setTextColor(Color.parseColor("#BDBDBD"))
                        }
                    } catch (_: Exception) {}
                }
            }
        }
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
                clearHopHealth(n)
                rebuildHopArea(n)
                testOvpnHopHealth(n, names[which])
            }
            .show()
    }

    /** Background health test for a picked OpenVPN
     *  hop (2.22): TCP profiles get a real connect
     *  probe; UDP profiles get a reset probe, and
     *  silence leaves NO tick (it proves nothing —
     *  tls-auth servers stay silent). The summary
     *  tick updates when the test finishes. */
    private fun testOvpnHopHealth(n: Int, profileName: String) {
        Thread {
            try {
                val profile = ChainEngine.ovpnProfile(this, profileName) ?: return@Thread
                val text = profile.ovpn
                var host: String? = null
                var port = 1194
                var proto = "udp"
                for (line in text.lines()) {
                    val t = line.trim()
                    if (t.startsWith("remote ") && host == null) {
                        val parts = t.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            host = parts[1]
                            if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
                            if (parts.size >= 4) {
                                val pr = parts[3].lowercase()
                                if (pr.startsWith("tcp")) proto = "tcp" else if (pr == "udp") proto = "udp"
                            }
                        }
                    }
                    if (t == "proto udp" || t.startsWith("proto udp ")) proto = "udp"
                    else if (t.startsWith("proto tcp")) proto = "tcp"
                }
                val h = host ?: return@Thread
                val isIp = h.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))
                val ips: List<String> = if (isIp) listOf(h)
                    else try { AdaptiveEngine.resolveIps(h) } catch (_: Exception) { emptyList() }
                if (ips.isEmpty()) {
                    setHopHealth(n, false, null)
                } else if (proto == "tcp") {
                    var ok = false
                    for (ip in ips.take(2)) {
                        try {
                            val s = java.net.Socket()
                            try { s.connect(java.net.InetSocketAddress(ip, port), 3000); ok = true } finally { try { s.close() } catch (_: Exception) {} }
                            if (ok) break
                        } catch (_: Exception) {}
                    }
                    setHopHealth(n, ok, null)
                } else {
                    if (ovpnUdpAnswered(ips[0], port)) setHopHealth(n, true, null)
                    // Silent UDP: no tick (inconclusive).
                }
                runOnUiThread { try { refreshSummaries() } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }.start()
    }

    private fun ovpnUdpAnswered(host: String, port: Int): Boolean {
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
            val deadline = start + 4000
            var sent = 0
            var alive = false
            while (System.currentTimeMillis() < deadline && !alive) {
                val now = System.currentTimeMillis()
                if (sent < 3 && now - start >= sent * 1300L) {
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

    /** Background health test for a picked WireGuard
     *  hop (2.22): the tunnel endpoint must resolve
     *  in DNS. That proves reachability of the
     *  address, not a live server (a WireGuard
     *  handshake cannot be faked cheaply), and the
     *  tick means exactly that. */
    private fun testWgHopHealth(n: Int, tunnelName: String) {
        Thread {
            try {
                val arr = JSONArray(getSharedPreferences("renamer_wg", MODE_PRIVATE)
                    .getString("wg_list", "[]") ?: "[]")
                var conf: String? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name") == tunnelName) { conf = o.optString("conf"); break }
                }
                var endpoint = ""
                var section = ""
                for (raw in (conf ?: "").lines()) {
                    val line = raw.trim()
                    if (line.startsWith("[")) { section = line; continue }
                    if (section.contains("Peer") && line.startsWith("Endpoint")) {
                        endpoint = line.substringAfter("=", "").trim()
                    }
                }
                if (endpoint.isBlank()) return@Thread
                val host = endpoint.substringBeforeLast(":").trim()
                if (host.isBlank()) return@Thread
                val ok = try {
                    java.net.InetAddress.getByName(host); true
                } catch (_: Exception) { false }
                setHopHealth(n, ok, null)
                runOnUiThread { try { refreshSummaries() } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }.start()
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
                // 2.41: Amnezia junk params are dropped
                // by Xray's plain WireGuard outbound, so
                // an Amnezia hop may look connected
                // without carrying traffic — warn, don't
                // block (the info card will show the
                // dead state if it happens).
                val wgConf = wgConfByName(names[which])
                if (wgConf != null && Regex("(?m)^\\s*(Jc|Jmin|Jmax|S1|S2|H1|H2|H3|H4)\\s*=").containsMatchIn(wgConf)) {
                    android.widget.Toast.makeText(this, L("این کانفیگ امنزیا است؛ پارامترهای junk آن در زنجیره‌ی Xray اعمال نمی‌شود و ممکن است زنجیره وصل شود ولی کار نکند.", "This is an Amnezia config; its junk params are not applied in an Xray chain, so the chain may look connected without carrying traffic."), android.widget.Toast.LENGTH_LONG).show()
                    // generic log line for the warning (no config contents).
                                    }
                clearHopHealth(n)
                rebuildHopArea(n)
                testWgHopHealth(n, names[which])
            }
            .show()
    }

    /** Chain log dialog (2.16): the log lives only
     *  behind the top icon, never on the page. */
    private fun showChainLogDialog() {
        val text = ChainEngine.logText().ifBlank { L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.") }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش زنجیره", "Chain log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("chain log", ChainEngine.logText()))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNeutralButton(L("پاک کردن", "Clear")) { _, _ ->
                try { ChainEngine.clearLog() } catch (_: Exception) {}
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show()
    }

    // ---------- Connect / disconnect ----------

    // 2.43: precise hop descriptions for the
    // central log — type + config kind + label,
    // never the link itself.
    private var appLogChainExitKey: String? = null
    // 2.46: OVPN-chain failure tracking — an
    // OVPN handshake failure was silent in the
    // central log (attempt without any outcome).
    private var appLogChainOvpnAttempt = false
    private var appLogChainOvpnConnected = false
    private var appLogChainOvpnErrorBefore: String? = null
    // 2.57: snapshot of the attempt's hop pair, so
    // its outcome is logged against the hops it
    // actually used even if a newer attempt has
    // begun (or the hops were changed) since.
    private var appLogChainOvpnAttemptDescFa: String? = null
    private var appLogChainOvpnAttemptDescEn: String? = null
    // 2.60: chain verdict + failure teardown state.
    // chainVerdictLoggedKey: the chain key whose
    // traffic verdict already went to the section
    // log (once per connection). ovpnFailSince:
    // when the current OVPN-chain failure was
    // first seen while the session was neither
    // connected nor connecting; after 10s of
    // persistent failure the session is really
    // torn down (see refreshStatus).
    private var chainVerdictLoggedKey: String? = null
    private var ovpnFailSince: Long = 0

    private fun hopDescFa(h: ChainEngine.Hop): String = when (h.type) {
        "v2ray" -> "v2ray (" + AppLog.cfgType(this, h.ref) + ") «${h.label}»"
        "emergency" -> "اضطراری «${h.label}»"
        "wireguard" -> {
            val conf = try { wgConfByName(h.ref) } catch (_: Exception) { null }
            val kind = if (conf != null && WgDirect.isAwgConf(conf)) "امنزیا" else "وایرگارد"
            "$kind «${h.label}»"
        }
        "openvpn" -> "اوپن‌وی‌پی‌ان «${h.label}»"
        else -> "«${h.label}»"
    }

    private fun hopDescEn(h: ChainEngine.Hop): String = when (h.type) {
        "v2ray" -> "v2ray (" + AppLog.cfgType(this, h.ref) + ") “${h.label}”"
        "emergency" -> "emergency “${h.label}”"
        "wireguard" -> {
            val conf = try { wgConfByName(h.ref) } catch (_: Exception) { null }
            val kind = if (conf != null && WgDirect.isAwgConf(conf)) "Amnezia" else "WireGuard"
            "$kind “${h.label}”"
        }
        "openvpn" -> "OpenVPN “${h.label}”"
        else -> "“${h.label}”"
    }

    private fun onConnectButton() {
        if (ChainEngine.isRunning(this)) {
            ChainEngine.stopAll(this)
            appLogChainOvpnAttempt = false
            appLogChainOvpnConnected = false
            appLogChainOvpnAttemptDescFa = null
            appLogChainOvpnAttemptDescEn = null
            chainVerdictLoggedKey = null
            ovpnFailSince = 0
            ChainEngine.log(L("قطع شد.", "Disconnected."))
            val sh1 = ChainEngine.hop(this, 1)
            val sh2 = ChainEngine.hop(this, 2)
            if (sh1 != null && sh2 != null) {
                AppLog.append(this, "chain",
                    "قطع دستی؛ زنجیره اول: ${hopDescFa(sh1)} → زنجیره دوم: ${hopDescFa(sh2)}.",
                    "Manual stop; hop 1: ${hopDescEn(sh1)} → hop 2: ${hopDescEn(sh2)}.")
            }
            refreshStatus()
            return
        }
        if (busy) {
            android.widget.Toast.makeText(this, L("عملیات قبلی هنوز در حال انجام است؛ کمی صبر کن.", "The previous operation is still running; please wait."), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val h1 = ChainEngine.hop(this, 1)
        val h2 = ChainEngine.hop(this, 2)
        if (h1 == null || h2 == null) {
            android.widget.Toast.makeText(this, L("هر دو زنجیره را انتخاب کن.", "Pick both hops first."), android.widget.Toast.LENGTH_SHORT).show()
            ChainEngine.log(L("هر دو زنجیره را انتخاب کن.", "Pick both hops first."))
            return
        }
        if (h1.type == "openvpn") {
            android.widget.Toast.makeText(this, L("اوپن‌وی‌پی‌ان نمی‌تواند زنجیره اول باشد؛ با دکمه جابه‌جا کردن، جاها را عوض کن.", "OpenVPN cannot be the first hop; use the swap button to switch the hops."), android.widget.Toast.LENGTH_SHORT).show()
            ChainEngine.log(L("اوپن‌وی‌پی‌ان نمی‌تواند زنجیره اول باشد؛ با دکمه جابه‌جا کردن، جاها را عوض کن.", "OpenVPN cannot be the first hop; use the swap button to switch the hops."))
            return
        }
        ChainEngine.log(L("شروع زنجیره: «${h1.label}» → «${h2.label}».", "Chain starting: \"${h1.label}\" → \"${h2.label}\"."))
        AppLog.append(this, "chain",
            "تلاش اتصال؛ زنجیره اول: ${hopDescFa(h1)} → زنجیره دوم: ${hopDescFa(h2)}؛ حالت: ${if (h2.type == "openvpn") "اوپن‌وی‌پی‌ان" else "Xray"}.",
            "Connect attempt; hop 1: ${hopDescEn(h1)} → hop 2: ${hopDescEn(h2)}; mode: ${if (h2.type == "openvpn") "OpenVPN" else "Xray"}.")
        if (h2.type == "openvpn") {
            // 2.57: flush a previous attempt's
            // outcome first, if it failed but its
            // poller window never ran (e.g. the
            // page was paused): otherwise the old
            // failure is silently overwritten by
            // this attempt's flags and never logged.
            if (appLogChainOvpnAttempt && !appLogChainOvpnConnected) {
                val oldErr = OvpnEngine.lastError
                if (!oldErr.isNullOrBlank() && oldErr != appLogChainOvpnErrorBefore) {
                    val oldFa = appLogChainOvpnAttemptDescFa
                    val oldEn = appLogChainOvpnAttemptDescEn
                    AppLog.append(this, "chain",
                        "زنجیره اوپن‌وی‌پی‌ان وصل نشد؛ علت: $oldErr.",
                        "OpenVPN chain did not connect; reason: $oldErr.")
                    ChainEngine.log(L(
                        "زنجیره اوپن‌وی‌پی‌ان وصل نشد" + (if (!oldFa.isNullOrBlank()) ": $oldFa" else "") + "؛ علت: $oldErr.",
                        "OpenVPN chain did not connect" + (if (!oldEn.isNullOrBlank()) ": $oldEn" else "") + "; reason: $oldErr."))
                }
            }
            appLogChainOvpnAttempt = true
            appLogChainOvpnConnected = false
            appLogChainOvpnErrorBefore = OvpnEngine.lastError
            appLogChainOvpnAttemptDescFa = "زنجیره اول: ${hopDescFa(h1)} → زنجیره دوم: ${hopDescFa(h2)}"
            appLogChainOvpnAttemptDescEn = "hop 1: ${hopDescEn(h1)} → hop 2: ${hopDescEn(h2)}"
            OvpnEngine.lastChainProxyDiag = ""
        }
        busy = true
        paintChainButton()
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
                    val msg = L("خطا: ", "Error: ") + (t.message ?: t.javaClass.simpleName)
                    android.widget.Toast.makeText(this@ChainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    ChainEngine.log(msg)
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
                android.widget.Toast.makeText(this@ChainActivity, L("ساخت کانفیگ زنجیره ناموفق بود؛ یکی از زنجیره‌ها قابل استفاده نیست یا بهترین سرور اضطراری پیدا نشد.", "Building the chain config failed; one hop cannot be used, or no healthy emergency server was found."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("ساخت کانفیگ زنجیره ناموفق بود؛ یکی از زنجیره‌ها قابل استفاده نیست یا بهترین سرور اضطراری پیدا نشد.", "Building the chain config failed; one hop cannot be used, or no healthy emergency server was found."))
                AppLog.append(this, "chain",
                    "ساخت کانفیگ زنجیره ناموفق بود؛ زنجیره اول: ${hopDescFa(h1)} → زنجیره دوم: ${hopDescFa(h2)}.",
                    "Building the chain config failed; hop 1: ${hopDescEn(h1)} → hop 2: ${hopDescEn(h2)}.")
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
            android.widget.Toast.makeText(this@ChainActivity, L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start."), android.widget.Toast.LENGTH_SHORT).show()
            ChainEngine.log(L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start."))
            return
        }
        // 2.41: remember the chain core's socks port
        // (10808 by default, dynamic when enabled) so
        // the info card measures the exit IP through
        // the chain itself — never through the OVPN
        // proxy core (10810), which is a different
        // core and exits via hop 1.
        chainSocksPort = try {
            val inb = org.json.JSONObject(config).optJSONArray("inbounds")
            var p = -1
            if (inb != null) {
                for (i in 0 until inb.length()) {
                    val o = inb.getJSONObject(i)
                    if (o.optString("tag") == "socks") { p = o.optInt("port", -1); break }
                }
            }
            p
        } catch (_: Exception) { -1 }
        VpnClientService.emergencyConfig = null
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", config)
            putExtra("name", L("زنجیره دوتایی", "Double chain"))
            putExtra("session_tag", "chain")
        }
        startService(intent)
        ChainEngine.setRunning(this, "xray")
        val xl1 = ChainEngine.hop(this, 1)?.label ?: ""
        val xl2 = ChainEngine.hop(this, 2)?.label ?: ""
        ChainEngine.log(L("زنجیره Xray راه‌اندازی شد: «$xl1» → «$xl2».", "Xray chain started: \"$xl1\" → \"$xl2\"."))
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
                android.widget.Toast.makeText(this@ChainActivity, L("ساخت پروکسی زنجیره اول ناموفق بود؛ زنجیره اول قابل استفاده نیست.", "Building the first hop's proxy failed; the first hop cannot be used."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("ساخت پروکسی زنجیره اول («${h1.label}») ناموفق بود؛ زنجیره اول قابل استفاده نیست.", "Building the first hop's (\"${h1.label}\") proxy failed; the first hop cannot be used."))
                AppLog.append(this, "chain",
                    "ساخت پروکسی زنجیره اول ناموفق بود؛ زنجیره اول: ${hopDescFa(h1)}.",
                    "Building the first hop's proxy failed; hop 1: ${hopDescEn(h1)}.")
            }
            return
        }
        if (!ChainEngine.startProxyCore(this, proxyConfig)) {
            runOnUiThread {
                busy = false
                android.widget.Toast.makeText(this@ChainActivity, L("پروکسی داخلی زنجیره راه‌اندازی نشد.", "The chain's internal proxy failed to start."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("پروکسی داخلی زنجیره («${h1.label}») راه‌اندازی نشد.", "The chain's internal proxy (\"${h1.label}\") failed to start."))
                AppLog.append(this, "chain",
                    "پروکسی داخلی زنجیره راه‌اندازی نشد؛ زنجیره اول: ${hopDescFa(h1)}.",
                    "The chain's internal proxy failed to start; hop 1: ${hopDescEn(h1)}.")
            }
            return
        }
        // 2.39: wait until the loopback proxy really
        // listens (like the engine's chain does) —
        // dialing a not-yet-listening port fails
        // the OVPN handshake before it starts.
        var proxyReady = false
        val proxyDeadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < proxyDeadline) {
            try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress("127.0.0.1", ChainEngine.PROXY_HTTP_PORT), 500)
                s.close()
                proxyReady = true
                break
            } catch (_: Exception) {
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
        if (!proxyReady) {
            ChainEngine.stopProxyCore()
            runOnUiThread {
                busy = false
                android.widget.Toast.makeText(this@ChainActivity, L("پروکسی داخلی زنجیره بالا نیامد.", "The chain's internal proxy did not come up."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("پروکسی داخلی زنجیره («${h1.label}») بالا نیامد؛ زنجیره متوقف شد.", "The chain's internal proxy (\"${h1.label}\") did not come up; chain stopped."))
                AppLog.append(this, "chain",
                    "پروکسی داخلی زنجیره بالا نیامد؛ زنجیره اول: ${hopDescFa(h1)}.",
                    "The chain's internal proxy did not come up; hop 1: ${hopDescEn(h1)}.")
            }
            return
        }
        val profile = ChainEngine.ovpnProfile(this, h2.ref)
        if (profile == null) {
            ChainEngine.stopProxyCore()
            runOnUiThread {
                busy = false
                android.widget.Toast.makeText(this@ChainActivity, L("پروفایل OpenVPN پیدا نشد؛ شاید حذف شده باشد.", "OpenVPN profile not found; it may have been deleted."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("پروفایل OpenVPN («${h2.label}») پیدا نشد؛ شاید حذف شده باشد.", "OpenVPN profile (\"${h2.label}\") not found; it may have been deleted."))
                AppLog.append(this, "chain",
                    "پروفایل OpenVPN زنجیره دوم پیدا نشد؛ زنجیره دوم: ${hopDescFa(h2)}.",
                    "The second hop's OpenVPN profile was not found; hop 2: ${hopDescEn(h2)}.")
            }
            return
        }
        runOnUiThread {
            OvpnEngine.chainProxyPort = ChainEngine.PROXY_HTTP_PORT
            OvpnEngine.chainProxyActive = true
            if (AdaptiveEngine.ovpnForceTcp(profile.ovpn) != null) {
                ChainEngine.log(L("زنجیره با TCP اجباری: پروفایل «${h2.label}» UDP بود و پروکسی HTTP فقط TCP می‌برد.",
                    "Chain with forced TCP: profile \"${h2.label}\" was UDP and the HTTP proxy carries TCP only."))
            }
            ChainEngine.setRunning(this, "ovpn")
            OvpnEngine.connect(this, profile, preserveChainProxy = true) { _ -> handler.postDelayed({ refreshStatus() }, 1500) }
            ChainEngine.log(L("زنجیره OpenVPN راه‌اندازی شد: «${h1.label}» → «${h2.label}».", "OpenVPN chain started: \"${h1.label}\" → \"${h2.label}\"."))
            busy = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 600) {
            if (resultCode == RESULT_OK) startXrayChainNow()
            else {
                busy = false
                android.widget.Toast.makeText(this@ChainActivity, L("مجوز VPN داده نشد.", "VPN permission was not granted."), android.widget.Toast.LENGTH_SHORT).show()
                ChainEngine.log(L("مجوز VPN داده نشد.", "VPN permission was not granted."))
            }
        } else if (requestCode == 701) {
            OvpnEngine.onPrepareResult(this, resultCode == RESULT_OK)
        }
    }

    /** 1.96: paints the small circle connect button
     *  from the live state. Off = soft red, connecting
     *  = soft yellow, on = soft green. The ring (stroke)
     *  is a slightly deeper shade and blinks gently
     *  (chainRingLight) between that shade and a slightly
     *  lighter one, every 1.5s. Only the ring blinks,
     *  never the fill. */
    private fun paintChainButton() {
        val running = ChainEngine.isRunning(this)
        val fill: String; val ring: String; val ringLight: String
        val fg: String; val label: String
        when {
            running -> {
                fill = "#E8F5E9"; ring = "#66BB6A"; ringLight = "#A5D6A7"
                fg = "#1B5E20"; label = L("قطع", "Disconnect")
            }
            busy -> {
                fill = "#FFF8E1"; ring = "#F9A825"; ringLight = "#FFD54F"
                fg = "#333333"; label = "…"
            }
            else -> {
                fill = "#FDE8E8"; ring = "#E57373"; ringLight = "#EF9A9A"
                fg = "#333333"; label = L("وصل", "Connect")
            }
        }
        // 2.16: like the Emergency face (glass orb),
        // same 112dp size. The ring blink survives
        // as the orb rim alternating shades.
        val stroke = if (chainRingLight) ringLight else ring
        connectBtn.background = HydraUi.GlassOrbDrawable(
            Color.parseColor(fill), Color.parseColor(stroke), this@ChainActivity)
        connectBtn.setTextColor(Color.parseColor(fg))
        connectBtn.text = label
        connectBtn.elevation = 2 * resources.displayMetrics.density
    }

    private fun refreshStatus() {
        // 2.16: no status lines under the connect
        // button any more (user order) — the button
        // itself shows the state, and problems go
        // to the chain log behind the top icon.
        // 2.41 exception (new user order): the chain
        // info card below the button, visible only
        // while a chain is connected.
        paintChainButton()
        try { updateChainInfoCard() } catch (_: Exception) {}
        // 2.60: Xray-chain traffic verdict, mirrored
        // into the SECTION log (the central log
        // already gets it from ConnVerify, but the
        // section log only said "started" /
        // "disconnected" — never whether traffic
        // passed). Once per connection, keyed like
        // the info card, while the chain runs.
        try {
            val running = ChainEngine.isRunning(this)
            if (!running) {
                chainVerdictLoggedKey = null
            } else {
                val vh1 = ChainEngine.hop(this, 1)
                val vh2 = ChainEngine.hop(this, 2)
                if (vh1 != null && vh2 != null && vh2.type != "openvpn") {
                    val vkey = vh1.label + "|" + vh2.label + "|" + vh2.type
                    if (chainVerdictLoggedKey != vkey) {
                        val verdict = getSharedPreferences("renamer_verify", MODE_PRIVATE)
                            .getString("verify_state", "")
                        if (verdict == "PROVEN" || verdict == "DEAD") {
                            chainVerdictLoggedKey = vkey
                            if (verdict == "PROVEN") {
                                ChainEngine.log(L(
                                    "بررسی ترافیک زنجیره: زنجیره اول: ${hopDescFa(vh1)} → زنجیره دوم: ${hopDescFa(vh2)}: ترافیک عبور کرد.",
                                    "Chain traffic check: hop 1: ${hopDescEn(vh1)} → hop 2: ${hopDescEn(vh2)}: traffic passed."))
                            } else {
                                ChainEngine.log(L(
                                    "بررسی ترافیک زنجیره: زنجیره اول: ${hopDescFa(vh1)} → زنجیره دوم: ${hopDescFa(vh2)}: ترافیک عبور نکرد — تونل بالا است ولی ترافیکی عبور نمی‌دهد.",
                                    "Chain traffic check: hop 1: ${hopDescEn(vh1)} → hop 2: ${hopDescEn(vh2)}: no traffic passed — the tunnel is up but carries no traffic."))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        // 2.60: an OVPN chain that FAILS used to
        // keep running forever: the poller below
        // only logged once the running flag was
        // already false, and nothing ever cleared
        // it or stopped the ovpn3 client — so the
        // client kept retrying with its TUN up and
        // Android kept showing the VPN key after
        // a failed chain. Now a failure that
        // persists 10s (past short reconnect
        // pauses) while neither connected nor
        // connecting is really torn down: client
        // stopped, proxy core stopped, running
        // cleared. The existing block below then
        // logs the reason on the next poll.
        // Direct OpenVPN sessions are untouched:
        // this branch only runs for a chain
        // attempt (appLogChainOvpnAttempt).
        try {
            if (appLogChainOvpnAttempt && !appLogChainOvpnConnected &&
                !OvpnEngine.isConnecting && OvpnEngine.lastError != null &&
                ChainEngine.isRunning(this) && !busy) {
                val now = System.currentTimeMillis()
                if (ovpnFailSince == 0L) {
                    ovpnFailSince = now
                } else if (now - ovpnFailSince >= 10_000) {
                    ovpnFailSince = 0
                    try { OvpnEngine.disconnect() } catch (_: Throwable) {}
                    try { ChainEngine.stopProxyCore() } catch (_: Throwable) {}
                    try { ChainEngine.setRunning(this, null) } catch (_: Throwable) {}
                }
            } else {
                ovpnFailSince = 0
            }
        } catch (_: Exception) {}
        // 2.46: a silent OVPN-chain failure finally
        // speaks: an attempt that ends without ever
        // connecting logs its reason (the core's
        // last error).
        // 2.49: the 2.46 stale filter was WRONG for
        // the recurring case — it hid the reason
        // exactly when the SAME error came back
        // (the 2.47 log: six reason-less failures),
        // which is precisely the signal that a fix
        // did NOT change the error. Now the reason
        // is always shown when present, with a
        // "(same as the previous attempt)" marker
        // when it matches the pre-attempt value.
        if (appLogChainOvpnAttempt && !ChainEngine.isRunning(this) && !busy) {
            appLogChainOvpnAttempt = false
            if (!appLogChainOvpnConnected) {
                val err = OvpnEngine.lastError
                val repeated = !err.isNullOrBlank() && err == appLogChainOvpnErrorBefore
                val reasonFa = if (!err.isNullOrBlank()) "؛ علت: $err" + (if (repeated) " (همان خطای تلاش قبل)" else "") else ""
                val reasonEn = if (!err.isNullOrBlank()) "; reason: $err" + (if (repeated) " (same error as the previous attempt)" else "") else ""
                val diagParts = OvpnEngine.lastChainProxyDiag.split("|")
                val proxyFa = if (diagParts.size == 2) "؛ پروکسی نهایی: ${diagParts[0]} خط، آرگومان‌ها: ${diagParts[1]}" else ""
                val proxyEn = if (diagParts.size == 2) "; final proxy: ${diagParts[0]} lines, args: ${diagParts[1]}" else ""
                AppLog.append(this, "chain",
                    "زنجیره اوپن‌وی‌پی‌ان وصل نشد$reasonFa$proxyFa.",
                    "OpenVPN chain did not connect$reasonEn$proxyEn.")
                // 2.57: the section log itself never
                // recorded an OVPN dial failure — only
                // the central log did. Mirror it here
                // (with the snapshot hop pair) so the
                // section log is self-contained.
                val snapFa = appLogChainOvpnAttemptDescFa
                val snapEn = appLogChainOvpnAttemptDescEn
                ChainEngine.log(L(
                    "زنجیره اوپن‌وی‌پی‌ان وصل نشد" + (if (!snapFa.isNullOrBlank()) ": $snapFa" else "") + "$reasonFa$proxyFa.",
                    "OpenVPN chain did not connect" + (if (!snapEn.isNullOrBlank()) ": $snapEn" else "") + "$reasonEn$proxyEn."))
            }
            appLogChainOvpnConnected = false
            appLogChainOvpnAttemptDescFa = null
            appLogChainOvpnAttemptDescEn = null
        }
    }

    /** 2.41: fills the chain info card. Hop server
     *  IPs come from the hop configs (domains are
     *  resolved); the exit IP is measured live for
     *  Xray chains (through the chain's own socks
     *  inbound) and read from hop-2's last measured
     *  direct egress for OVPN chains. A dead chain
     *  shows "no traffic passed" instead of a fake
     *  IP. The card also feeds the central log once
     *  per connection (connected + exit IP). */
    private fun updateChainInfoCard() {
        val running = ChainEngine.isRunning(this)
        val h1 = if (running) ChainEngine.hop(this, 1) else null
        val h2 = if (running) ChainEngine.hop(this, 2) else null
        val connected = when {
            h1 == null || h2 == null -> false
            h2.type == "openvpn" -> OvpnEngine.isConnected
            else -> VpnClientService.isRunning
        }
        if (!connected || h1 == null || h2 == null) {
            chainInfoCard.visibility = View.GONE
            chainInfoKey = null
            chainSocksPort = -1
            appLogChainExitKey = null
            return
        }
        chainInfoCard.visibility = View.VISIBLE
        val key = h1.label + "|" + h2.label + "|" + h2.type
        if (key == chainInfoKey) return
        chainInfoKey = key
        // 2.43: central log, once per connection.
        val modeFa = if (h2.type == "openvpn") "اوپن‌وی‌پی‌ان" else "Xray"
        val modeEn = if (h2.type == "openvpn") "OpenVPN" else "Xray"
        AppLog.append(this, "chain",
            "وصل شد؛ زنجیره اول: ${hopDescFa(h1)} → زنجیره دوم: ${hopDescFa(h2)}؛ حالت: $modeFa.",
            "Connected; hop 1: ${hopDescEn(h1)} → hop 2: ${hopDescEn(h2)}; mode: $modeEn.")
        if (h2.type == "openvpn") appLogChainOvpnConnected = true
        // 2.45: background real-connection check —
        // through the chain's own socks for Xray
        // chains; OVPN chains cannot be probed
        // live from inside the app (the chain TUN
        // disallows the app's own traffic).
        if (h2.type == "openvpn") {
            ConnVerify.skip(this, "chain", "chain", h1.label + " → " + h2.label,
                "زنجیره اوپن‌وی‌پی‌ان است؛ اندازه‌گیری زنده از داخل برنامه ممکن نیست",
                "OpenVPN chain; live measurement from inside the app is not possible")
        } else {
            val cPort = if (chainSocksPort > 0) chainSocksPort else VpnConfigBuilder.socksPort(this)
            ConnVerify.verifyXray(this, "chain", "chain", h1.label + " → " + h2.label, cPort) { ChainEngine.isRunning(this) }
        }
        chainHop1Tv.text = L("سرور زنجیره اول: …", "Hop 1 server: …")
        chainHop2Tv.text = L("سرور زنجیره دوم: …", "Hop 2 server: …")
        chainExitTv.text = L("IP خروجی فعلی: در حال بررسی…", "Current exit IP: checking…")
        Thread {
            val ip1 = hopServerIp(h1)
            val ip2 = hopServerIp(h2)
            val exit = if (h2.type == "openvpn") {
                try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_exit_" + h2.ref, null) } catch (_: Exception) { null }
            } else {
                fetchChainExitIpViaChain()
            }
            runOnUiThread {
                if (chainInfoKey != key) return@runOnUiThread
                chainHop1Tv.text = L("سرور زنجیره اول: ", "Hop 1 server: ") + (ip1 ?: h1.label)
                chainHop2Tv.text = L("سرور زنجیره دوم: ", "Hop 2 server: ") + (ip2 ?: h2.label)
                chainExitTv.text = when {
                    exit != null && h2.type == "openvpn" -> L("IP خروجی فعلی (از آخرین اتصال مستقیم hop2): ", "Current exit IP (from hop 2's last direct connect): ") + exit
                    exit != null -> L("IP خروجی فعلی: ", "Current exit IP: ") + exit
                    h2.type == "openvpn" -> L("IP خروجی فعلی: هنوز اندازه‌گیری نشده (یک بار زنجیره دوم را تکی وصل کن)", "Current exit IP: not measured yet (connect hop 2 alone once)")
                    else -> L("IP خروجی فعلی: ترافیکی عبور نکرد", "Current exit IP: no traffic passed")
                }
                if (exit != null && appLogChainExitKey != key) {
                    appLogChainExitKey = key
                    AppLog.append(this, "chain",
                        "IP خروجی فعلی: $exit.",
                        "Current exit IP: $exit.")
                }
            }
        }.start()
    }

    /** Server host of a hop, resolved to an IP when
     *  it is a domain (unresolvable hosts are shown
     *  as-is). Background threads only. */
    private fun hopServerIp(hop: ChainEngine.Hop): String? {
        val host: String? = when (hop.type) {
            "v2ray" -> XraySpeedtest.parseServer(hop.ref)?.host
            "emergency" -> try { ChainEngine.hopLink(this, hop)?.let { XraySpeedtest.parseServer(it)?.host } } catch (_: Exception) { null }
            "wireguard" -> wgEndpointHost(hop.ref)
            "openvpn" -> ovpnRemoteHost(hop.ref)
            else -> null
        }
        if (host.isNullOrBlank()) return null
        if (host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) return host
        return try { java.net.InetAddress.getByName(host).hostAddress } catch (_: Exception) { host }
    }

    private fun wgConfByName(name: String): String? {
        for (pref in listOf("renamer_wg", "renamer_ovpn")) {
            try {
                val arr = org.json.JSONArray(getSharedPreferences(pref, MODE_PRIVATE).getString("wg_list", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name") == name) return o.optString("conf").ifBlank { null }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun wgEndpointHost(name: String): String? {
        val conf = wgConfByName(name) ?: return null
        for (raw in conf.lines()) {
            val line = raw.trim()
            if (line.startsWith("Endpoint", ignoreCase = true)) {
                val v = line.substringAfter("=", "").trim()
                if (v.isEmpty()) return null
                return if (v.startsWith("[")) v.substringAfter("[").substringBefore("]") else v.substringBefore(":")
            }
        }
        return null
    }

    private fun ovpnRemoteHost(profileName: String): String? {
        return try {
            val profiles = OvpnStore.load(this)
            val p = profiles.firstOrNull { it.name == profileName } ?: return null
            for (raw in p.ovpn.lines()) {
                val t = raw.trim()
                if (t.startsWith("remote ")) {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) return parts[1]
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Exit IP through an Xray chain's OWN socks
     *  inbound (the same core and the same routing
     *  the browser's traffic takes, so the result is
     *  exactly what an IP-check site shows). Never
     *  through the OVPN proxy core (10810): that
     *  core exits via hop 1, not via the chain.
     *  Null when the chain carries no traffic —
     *  that IS the dead-chain signal. */
    private fun fetchChainExitIpViaChain(): String? {
        val port = if (chainSocksPort > 0) chainSocksPort else try { VpnConfigBuilder.socksPort(this) } catch (_: Exception) { 10808 }
        if (port <= 0) return null
        val urls = listOf("https://api.ipify.org", "https://checkip.amazonaws.com")
        val ipRe = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", port))
        for (u in urls) {
            try {
                val c = java.net.URL(u).openConnection(proxy) as java.net.HttpURLConnection
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
