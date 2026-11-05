package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.amnezia.awg.backend.Tunnel as AwgTunnel
import org.amnezia.awg.config.Config as AwgConfig
import java.io.BufferedReader
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard section, exactly like the official
 * WireGuard app:
 * - A list of tunnels, each row with a name, its
 *   endpoint underneath, and a Switch on the right
 *   to connect to exactly that tunnel (only one
 *   tunnel active at a time).
 * - A + button at the bottom-right: import a .conf
 *   file from storage, or import from the clipboard.
 *
 * Connection uses the OFFICIAL WireGuard Go backend
 * (com.wireguard.android:tunnel, wg-go), the same
 * library the official app uses — NOT Xray. The
 * .conf text is parsed by the official
 * com.wireguard.config.Config parser, and GoBackend
 * builds the TUN itself (addresses, routes from
 * AllowedIPs, DNS, MTU) and runs wg-go on it. That
 * is why the previous Xray-based WireGuard path,
 * which hung the app, could not turn off, and
 * connected without traffic, is gone: there is no
 * second TUN, no Xray routing, and no stopLoop on
 * the main thread any more.
 *
 * All GoBackend calls run on a background thread:
 * setState blocks waiting for its VpnService and
 * resolving the peer endpoint, and calling it on
 * the main thread would hang the UI. The Switch
 * listener only starts that background work, and
 * the list is only rebuilt when the running state
 * actually changes, so tapping a switch can never
 * be lost to a 2-second list rebuild.
 *
 * The private key is never shown in the UI (masked
 * in the details dialog) and is stored only in this
 * app's private preferences, as part of the
 * tunnel's .conf text.
 */
class WireGuardActivity : Activity() {
    private data class WgTunnel(
        val name: String,
        val conf: String,
        val privateKey: String,
        val address: List<String>,
        val dns: List<String>,
        val mtu: Int?,
        val peers: List<WgPeer>
    )
    private data class WgPeer(
        val publicKey: String,
        val preSharedKey: String?,
        val endpoint: String,
        val allowedIPs: List<String>,
        val keepAlive: Int?
    )

    /** Tunnel object for GoBackend. Its interface
     *  name must be a valid wg name (max 15 chars,
     *  [a-zA-Z0-9_=+.-]), so the user's display name
     *  is sanitised here; the UI always shows the
     *  original display name. */
    private inner class SimpleTunnel(val displayName: String) : Tunnel {
        val ifaceName: String = sanitizeName(displayName)
        override fun getName(): String = ifaceName
        override fun onStateChange(state: Tunnel.State) {
            runOnUiThread {
                if (state == Tunnel.State.UP) {
                    activeName = displayName
                    lastError = null
                    WgLog.add(L("وصل شد: ", "Connected: ") + displayName)
                } else if (activeName == displayName) {
                    activeName = null
                }
                if (state == Tunnel.State.DOWN) WgLog.add(L("قطع شد: ", "Disconnected: ") + displayName)
                if (state == Tunnel.State.DOWN && WgTunnelRegistry.wgTunnel === this)
                    WgTunnelRegistry.wgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }
    }

    /** Tunnel object for the AmneziaWG backend: same
     *  sanitising and state handling as SimpleTunnel. */
    private inner class AwgSimpleTunnel(val displayName: String) : AwgTunnel {
        val ifaceName: String = sanitizeName(displayName)
        override fun getName(): String = ifaceName
        override fun onStateChange(state: AwgTunnel.State) {
            runOnUiThread {
                if (state == AwgTunnel.State.UP) {
                    activeName = displayName
                    lastError = null
                    WgLog.add(L("وصل شد: ", "Connected: ") + displayName)
                } else if (activeName == displayName) {
                    activeName = null
                }
                if (state == AwgTunnel.State.DOWN) WgLog.add(L("قطع شد: ", "Disconnected: ") + displayName)
                if (state == AwgTunnel.State.DOWN && WgTunnelRegistry.awgTunnel === this)
                    WgTunnelRegistry.awgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }
    }


    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private var tunnels: MutableList<WgTunnel> = mutableListOf()
    private var activeName: String? = null
    private var pendingTunnel: WgTunnel? = null
    private var currentTunnel: SimpleTunnel? = null
    private var currentAwgTunnel: AwgSimpleTunnel? = null
    private var lastError: String? = null
    // ADAPTIVE ENGINE (1.78): one-shot bypass so
    // the engine's own fallback never re-enters
    // the engine flow.
    private var engineReportBtn: Button? = null
    private var engineBypassOnce = false
    private var switchUpdating = false
    private var lastShownState: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            // Only rebuild when the state actually
            // changed; rebuilding every 2 seconds used
            // to eat taps and flicker the switches.
            val state = (activeName ?: "") + "|" + isWgUp()
            if (state != lastShownState) {
                lastShownState = state
                refreshStatus()
                refreshList()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun prefs() = getSharedPreferences("renamer_wg", MODE_PRIVATE)

    /** In-memory connection-event log for this page
     *  only (never persisted): connect, disconnect
     *  and error lines with times, shown only
     *  behind the top log icon. */
    private object WgLog {
        private val lines = mutableListOf<String>()
        fun add(s: String) {
            val tm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            lines += "$tm  $s"
            while (lines.size > 100) lines.removeAt(0)
        }
        fun text(): String = lines.joinToString("\n")
        fun clear() { lines.clear() }
    }

    private fun sanitizeName(name: String): String {
        val clean = name.map { c ->
            if (c.isLetterOrDigit() || c in "_=+.-") c else '_'
        }.joinToString("").take(15)
        return clean.ifBlank { "wg" }
    }

    private fun backend(): GoBackend? {
        return try {
            WgBackend.get(applicationContext)
        } catch (e: Throwable) {
            lastError = "wg-go failed to load: ${e.message ?: e.javaClass.simpleName}"
            WgLog.add(L("خطا: ", "Error: ") + (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun awgBackend(): org.amnezia.awg.backend.GoBackend? {
        return try {
            AwgBackend.get(applicationContext)
        } catch (_: Throwable) { null }
    }

    private fun isWgUp(): Boolean {
        try {
            val b = WgBackend.get(applicationContext)
            val t = currentTunnel
            if (t != null && b.getState(t) == Tunnel.State.UP) return true
        } catch (_: Throwable) {}
        try {
            val b = AwgBackend.get(applicationContext)
            val t = currentAwgTunnel
            if (t != null && b.getState(t) == AwgTunnel.State.UP) return true
        } catch (_: Throwable) {}
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(root)
        // Log icon at the top: small, minimal, classy.
        // The log itself lives ONLY behind this icon,
        // never inline on the main page.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(View(this).apply {
            val s = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        topRow.addView(TextView(this).apply {
            text = "وایرگارد و امنزیا"; textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 12)
        })
        topRow.addView(ImageView(this).apply {
            setImageBitmap(HydraUi.logIconBitmap(this@WireGuardActivity, 40))
            val d8 = (8 * resources.displayMetrics.density).toInt()
            setPadding(d8, d8, d8, d8)
            background = HydraUi.glassBg(Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, this@WireGuardActivity)
            val s = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { showWgLogDialog() }
        })
        root.addView(topRow)
        statusText = TextView(this).apply {
            text = L("قطع است", "Disconnected"); textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusText)
        // ADAPTIVE ENGINE (1.78): minimal report
        // button, visible only while the engine is
        // on (visibility refreshed in onResume).
        engineReportBtn = AdaptiveEngine.reportButton(this, AdaptiveEngine.S_WG).apply {
            visibility = if (AdaptiveEngine.isEnabled(this@WireGuardActivity)) View.VISIBLE else View.GONE
        }
        root.addView(engineReportBtn)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        val frame = android.widget.FrameLayout(this)
        frame.addView(scrollView)
        val fabSize = (72 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        val fab = Button(this).apply {
            text = "+"; textSize = 28f
            setTextColor(Color.parseColor("#757575"))
            background = HydraUi.GlassOrbDrawable(
                Color.parseColor("#E9E9E9"), Color.parseColor("#CFCFCF"), this@WireGuardActivity)
            layoutParams = android.widget.FrameLayout.LayoutParams(fabSize, fabSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { showImportMenu() }
        }
        frame.addView(fab)
        root.setPadding(32, 32, 32, 32 + fabSize + fabMargin)
        setContentView(frame)
        frame.fitsSystemWindows = true
        loadTunnels()
        refreshList()
        refreshStatus()
        if (intent.getBooleanExtra("widget_auto_connect", false) && !isWgUp() && activeName != null) {
            tunnels.firstOrNull { it.name == activeName }?.let { connectTunnel(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
        AdaptiveEngine.setReportVisible(engineReportBtn, this, AdaptiveEngine.S_WG)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun loadTunnels() {
        tunnels = mutableListOf()
        try {
            val arr = JSONArray(prefs().getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val conf = o.optString("conf")
                val parsed = parseConf(conf) ?: continue
                tunnels += parsed.copy(name = name)
            }
        } catch (_: Exception) {}
        activeName = prefs().getString("wg_active", null)?.takeIf { it.isNotBlank() }
        if (activeName != null && tunnels.none { it.name == activeName }) activeName = null
        if (activeName != null) currentTunnel = SimpleTunnel(activeName!!)
    }

    private fun saveTunnels() {
        val arr = JSONArray()
        for (t in tunnels) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("conf", t.conf)
            })
        }
        prefs().edit()
            .putString("wg_list", arr.toString())
            .putString("wg_active", activeName ?: "")
            .apply()
    }

    private fun parseConf(text: String): WgTunnel? {
        var section = ""
        var privateKey = ""
        val address = mutableListOf<String>()
        val dns = mutableListOf<String>()
        var mtu: Int? = null
        val peers = mutableListOf<WgPeer>()
        var curPeer: MutableMap<String, String>? = null
        fun flushPeer() {
            val m = curPeer ?: return
            val pub = m["PublicKey"] ?: ""
            val ep = m["Endpoint"] ?: ""
            if (pub.isNotBlank() && ep.isNotBlank()) {
                peers += WgPeer(
                    publicKey = pub,
                    preSharedKey = m["PresharedKey"]?.takeIf { it.isNotBlank() },
                    endpoint = ep,
                    allowedIPs = (m["AllowedIPs"] ?: "0.0.0.0/0, ::/0")
                        .split(",").map { it.trim() }.filter { it.isNotBlank() },
                    keepAlive = m["PersistentKeepalive"]?.toIntOrNull()
                )
            }
            curPeer = null
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                if (section == "Peer") flushPeer()
                section = line.removeSurrounding("[", "]").trim()
                if (section == "Peer") curPeer = mutableMapOf()
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (section) {
                "Interface" -> when (key) {
                    "PrivateKey" -> privateKey = value
                    "Address" -> address += value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "DNS" -> dns += value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "MTU" -> mtu = value.toIntOrNull()
                }
                "Peer" -> curPeer?.put(key, value)
            }
        }
        if (section == "Peer") flushPeer()
        if (privateKey.isBlank() || peers.isEmpty()) return null
        return WgTunnel(
            name = "",
            conf = text.trim(),
            privateKey = privateKey,
            address = address,
            dns = dns,
            mtu = mtu,
            peers = peers
        )
    }

    // 2.26 alien imports: sha256 of the trimmed
    // conf text, written by the Alien section's
    // transfer button. Loaded fresh on every
    // refreshList, like the tunnels themselves.
    private var alienWgSet: Set<String> = emptySet()
    private var alienWgBitmapCache: android.graphics.Bitmap? = null

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

    private fun loadAlienWgSet(): Set<String> {
        return try {
            val arr = org.json.JSONArray(
                prefs().getString("alien_tunnels", "[]") ?: "[]")
            val s = mutableSetOf<String>()
            for (i in 0 until arr.length()) s += arr.optString(i)
            s
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun alienWgBitmap(): android.graphics.Bitmap {
        alienWgBitmapCache?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("alien.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        }
        alienWgBitmapCache = bmp
        return bmp
    }

    private fun galaxyWgBg(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#1A237E"),
                Color.parseColor("#4A148C"),
                Color.parseColor("#006064")
            )).apply {
            cornerRadius = 18 * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#7E57C2"))
        }
    }

    private fun refreshList() {
        alienWgSet = loadAlienWgSet()
        listContainer.removeAllViews()
        if (tunnels.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز تونلی اضافه نشده است. با دکمه + پایین صفحه، فایل یا متن وایرگارد یا امنزیا را وارد کن.",
                         "No tunnel yet. Use the + button at the bottom to import a WireGuard or AmneziaWG file or text.")
                setTextColor(Color.GRAY)
            })
            return
        }
        val up = isWgUp()
        for (t in tunnels) {
            val isActive = t.name == activeName && up
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val d = resources.displayMetrics.density
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 18 * d
                    // 2.0: the active row reads as "on" at a glance.
                        setColor(if (isActive) Color.parseColor("#E8F5E9") else Color.WHITE)
                        setStroke((1 * d).toInt(), if (isActive) Color.parseColor("#A5D6A7") else Color.parseColor("#E0E0E0"))
                }
                elevation = 2 * d
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = t.name; textSize = 17f
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = t.peers.firstOrNull()?.endpoint ?: ""
                textSize = 13f; setTextColor(Color.GRAY)
            })
            row.addView(engineEdgeLabel(WgDirect.isAwgConf(t.conf)))
            row.addView(ImageView(this).apply {
                setImageBitmap(typeIconBitmap(WgDirect.isAwgConf(t.conf)))
                val s = (24 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    setMargins(0, 0, (4 * resources.displayMetrics.density).toInt(), 0)
                }
            })
                row.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (isActive) Color.parseColor("#43A047") else Color.parseColor("#BDBDBD"))
                    }
                    val s = (12 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    }
                })
            if (alienWgSet.contains(sha256Hex(t.conf.trim()))) {
                // Alien import: deep galaxy card,
                // light text, alien icon after the
                // status dot (before the info).
                row.background = galaxyWgBg()
                try {
                    val edge = row.getChildAt(0)
                    if (edge is android.widget.LinearLayout && edge.childCount > 0) {
                        val lab = edge.getChildAt(0)
                        if (lab is android.widget.TextView) lab.setTextColor(Color.parseColor("#B0BEC5"))
                    }
                    info.getChildAt(0)?.let {
                        if (it is android.widget.TextView) it.setTextColor(Color.WHITE)
                    }
                    info.getChildAt(1)?.let {
                        if (it is android.widget.TextView) it.setTextColor(Color.parseColor("#CFD8DC"))
                    }
                } catch (_: Exception) {}
                row.addView(ImageView(this).apply {
                    setImageBitmap(alienWgBitmap())
                    val s = (30 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#E8FFFFFF"))
                        setStroke(1, Color.parseColor("#55000000"))
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt())
                })
            }
            row.addView(info)
            val tog = ImageView(this).apply {
                setImageBitmap(HydraUi.toggleBitmap(this@WireGuardActivity, "wg", isActive))
                val dd = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((49 * dd).toInt(), (25 * dd).toInt()).apply {
                    setMargins(0, 0, (12 * dd).toInt(), 0)
                }
                setOnClickListener {
                    if (isActive) disconnectWg() else connectTunnel(t)
                }
            }
            row.addView(tog)
            row.addView(Button(this).apply {
                text = "✕"; textSize = 11f
                setTextColor(Color.parseColor("#D32F2F"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#FCE4EC"))
                    setStroke(1, Color.parseColor("#F8BBD0"))
                }
                val s = (26 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(4, 0, 12, 0) }
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    // first, like OpenVPN: one stray tap
                    // used to destroy the tunnel and its
                    // key with no way back.
                    android.app.AlertDialog.Builder(this@WireGuardActivity)
                        .setTitle(L("حذف تانل", "Delete tunnel"))
                        .setMessage(L("تانل «${t.name}» حذف شود؟", "Delete tunnel “${t.name}”?"))
                        .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                            val wasActive = t.name == activeName
                            tunnels.remove(t)
                            if (wasActive) {
                                disconnectWg()
                                activeName = null
                                currentTunnel = null
                                currentAwgTunnel = null
                            }
                            saveTunnels(); refreshList(); refreshStatus()
                            try {
                                                            } catch (_: Exception) {}
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        .show()
                }
            })
            row.setOnClickListener { showDetails(t) }
            listContainer.addView(row)
        }
    }

    // Slanted engine label on the row's edge, like
    // the v2ray protocol label: rotated so it reads
    // bottom-to-top. WireGuard green, Amnezia purple.
    private fun engineEdgeLabel(isAwg: Boolean): LinearLayout {
        val d = resources.displayMetrics.density
        val label = if (isAwg) L("امنزیا", "AmneziaWG") else L("وایرگارد", "WireGuard")
        val w = minOf(110, label.length * 8 + 20)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(this@WireGuardActivity).apply {
                text = label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.ITALIC)
                setTextColor(Color.parseColor("#757575"))
                gravity = Gravity.CENTER
                rotation = -90f
                layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (30 * d).toInt())
            })
        }
    }

    /** Small type mark next to the vertical label:
     *  a shield for AmneziaWG, two linked rings for
     *  WireGuard — drawn in code, so no asset is
     *  needed, and the colors match the label. */
    private fun typeIconBitmap(isAwg: Boolean): android.graphics.Bitmap {
        val s = 96
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = Color.parseColor("#8A8A8A")
        }
        if (isAwg) {
            val path = android.graphics.Path().apply {
                moveTo(48f, 10f)
                lineTo(80f, 24f)
                lineTo(80f, 52f)
                cubicTo(80f, 72f, 66f, 84f, 48f, 90f)
                cubicTo(30f, 84f, 16f, 72f, 16f, 52f)
                lineTo(16f, 24f)
                close()
            }
            c.drawPath(path, paint)
        } else {
            c.drawCircle(38f, 48f, 18f, paint)
            c.drawCircle(58f, 48f, 18f, paint)
        }
        return bmp
    }

    private fun showWgLogDialog() {
        val text = WgLog.text().ifBlank { L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.") }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش وایرگارد", "WireGuard log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("wg log", WgLog.text()))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNeutralButton(L("پاک کردن", "Clear")) { _, _ ->
                try { WgLog.clear() } catch (_: Exception) {}
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showDetails(t: WgTunnel) {
        val peer = t.peers.firstOrNull()
        val text = L("نام: ", "Name: ") + t.name + "\n" +
            L("آدرس: ", "Address: ") + t.address.joinToString(", ") + "\n" +
            L("DNS: ", "DNS: ") + (if (t.dns.isEmpty()) "-" else t.dns.joinToString(", ")) + "\n" +
            L("MTU: ", "MTU: ") + (t.mtu?.toString() ?: "-") + "\n" +
            L("کلید خصوصی: ", "Private key: ") + "••••••••\n" +
            L("سرور: ", "Endpoint: ") + (peer?.endpoint ?: "-") + "\n" +
            L("AllowedIPs: ", "AllowedIPs: ") + (peer?.allowedIPs?.joinToString(", ") ?: "-") + "\n" +
            L("Keepalive: ", "Keepalive: ") + (peer?.keepAlive?.toString() ?: "-") + "\n" +
            L("موتور: ", "Engine: ") + (if (WgDirect.isAwgConf(t.conf)) L("امنزیا", "AmneziaWG") else L("وایرگارد", "WireGuard"))
        android.app.AlertDialog.Builder(this)
            .setTitle("وایرگارد و امنزیا")
            .setMessage(text)
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun refreshStatus() {
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", isWgUp()).apply() } catch (_: Exception) {}
        if (isWgUp() && activeName != null) {
            statusText.text = L("متصل: ", "Connected: ") + activeName
        } else if (lastError != null) {
            statusText.text = L("قطع شد: ", "Disconnected: ") + lastError
        } else {
            statusText.text = L("قطع است", "Disconnected")
        }
    }

    private fun showImportMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("+")
            .setView(scroll)
            .setNegativeButton(L("بستن", "Close"), null)
            .create()
        fun glassBtn(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label; textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 24f
                    setColor(Color.parseColor("#E8F4FD"))
                    setStroke(2, Color.parseColor("#BBDEFB"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                setPadding(24, 20, 24, 20)
                setOnClickListener { dialog.dismiss(); onClick() }
            })
        }
        glassBtn(L("وارد کردن فایل", "Import file")) { importFile() }
        glassBtn(L("وارد کردن از کلیپ‌بورد", "Import from clipboard")) { importClipboard() }
        dialog.show()
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, L("انتخاب فایل وایرگارد", "Choose a WireGuard file")), 500)
    }

    private fun importClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isBlank() || !clip.contains("[Interface]")) {
                statusText.text = L("کلیپ‌بورد کانفیگ وایرگارد نیست.", "Clipboard does not contain a WireGuard config.")
                return
            }
            addTunnelFromText(clip, null)
        } catch (e: Exception) {
            statusText.text = L("خواندن کلیپ‌بورد ناموفق: ", "Reading clipboard failed: ") + (e.message ?: "")
        }
    }

    private fun addTunnelFromText(text: String, suggestedName: String?) {
        val parsed = parseConf(text)
        if (parsed == null) {
            statusText.text = L("کانفیگ وایرگارد معتبر نیست؛ PrivateKey و Peer لازم است.", "Invalid WireGuard config; PrivateKey and a Peer are required.")
            return
        }
        // Also validate with the matching parser, so a
        // bad key or endpoint is caught here, with its
        // real reason, instead of failing silently later.
        // AmneziaWG configs parse with the AWG parser
        // (the plain WireGuard parser rejects junk keys).
        try {
            val filtered = WgDirect.stripReserved(text)
            if (WgDirect.isAwgConf(filtered)) AwgConfig.parse(BufferedReader(StringReader(filtered.trim())))
            else Config.parse(BufferedReader(StringReader(filtered.trim())))
        } catch (e: Exception) {
            statusText.text = L("کانفیگ وایرگارد معتبر نیست: ", "Invalid WireGuard config: ") + (e.message ?: "")
            return
        }
        val existing = tunnels.any {
            it.privateKey == parsed.privateKey &&
                it.peers.firstOrNull()?.endpoint == parsed.peers.firstOrNull()?.endpoint
        }
        if (existing) {
            statusText.text = L("این کانفیگ تکراری است.", "This config is a duplicate.")
            return
        }
        if (suggestedName != null) {
            tunnels += parsed.copy(name = uniqueName(suggestedName))
            saveTunnels(); refreshList()
            statusText.text = L("تونل وایرگارد اضافه شد.", "WireGuard tunnel added.")
            return
        }
        val input = EditText(this).apply {
            hint = L("نام تونل", "Tunnel name")
            setText(uniqueName(L("WireGuard", "WireGuard")))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("نام تونل", "Tunnel name"))
            .setView(input)
            .setPositiveButton(L("افزودن", "Add")) { _, _ ->
                val name = input.text.toString().trim().ifBlank { uniqueName("WireGuard") }
                tunnels += parsed.copy(name = uniqueName(name))
                saveTunnels(); refreshList()
                statusText.text = L("تونل وایرگارد اضافه شد.", "WireGuard tunnel added.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun uniqueName(base: String): String {
        if (tunnels.none { it.name == base }) return base
        var i = 2
        while (tunnels.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    // --- Connect / disconnect, official GoBackend ---
    /** ADAPTIVE ENGINE (1.78), first priority:
     *  endpoint variants with real handshake proof,
     *  then the exact old connect. Runs only when
     *  permission is already granted and no other
     *  VPN is running; otherwise the old path
     *  handles the dialog / v2ray stop, and its
     *  re-post (or the next tap) reaches the engine. */
    /** 2.45: background real-connection check
     *  for a WG tunnel: a fresh handshake, not
     *  just UP state (a blackholed endpoint also
     *  shows UP). Aborts silently when the tunnel
     *  is replaced or stopped before the verdict. */
    private fun startWgVerify(name: String, handshake: () -> Long, alive: () -> Boolean, onDone: ((Boolean) -> Unit)? = null) {
        if (!ConnVerify.begin(this, "wireguard", "wireguard", name)) return
        Thread {
            var proven = false
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 15000) {
                try {
                    val hs = handshake()
                    if (hs > 0 && hs * 1000 >= System.currentTimeMillis() - 180000) { proven = true; break }
                    if (!alive()) break
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
            if (!alive()) return@Thread
            ConnVerify.finish(this, "wireguard", "wireguard", name,
                if (proven) "PROVEN" else "DEAD",
                if (proven) "واقعاً وصل است (دست‌تکان تازه)" else "وصل نیست — تونل بالا است ولی دست‌تکانی ندارد",
                if (proven) "really connected (fresh handshake)" else "not connected — the tunnel is up but has no handshake")
            try { onDone?.invoke(proven) } catch (_: Exception) {}
        }.start()
    }

    private fun engineConnectTunnel(t: WgTunnel) {
        statusText.text = L("موتور هوشمند در حال بررسی تونل...", "Smart engine checking tunnel...")
        AdaptiveEngine.append(this, AdaptiveEngine.S_WG,
            "موتور: بررسی «${t.name}» شروع شد.",
            "Engine: checking \"${t.name}\" started.")
        Thread {
            val variants = AdaptiveEngine.wgVariants(t.conf)
            var connected = false
            var winLabel = ""
            var winLabelEn = ""
            for (v in variants) {
                if (tryWgVariant(t, v.conf)) { connected = true; winLabel = v.labelFa; winLabelEn = v.labelEn; break }
            }
            runOnUiThread {
                if (connected) {
                    AdaptiveEngine.append(this, AdaptiveEngine.S_WG,
                        "موتور: «${t.name}» با «$winLabel» وصل شد.",
                        "Engine: \"${t.name}\" connected with \"$winLabel\".")
                    appLogWgAttempted = false
                    AppLog.append(this, "wireguard",
                        "«${t.name}» با روش موتور «$winLabel» وصل شد؛ موتور هوشمند: روشن.",
                        "“${t.name}” connected with engine method “$winLabelEn”; smart engine ON.")
                    // 2.45: handshake check for the winning variant.
                    val vtEng = currentAwgTunnel
                    startWgVerify(t.name,
                        { val b = awgBackend(); if (b != null && vtEng != null) b.getLastHandshake(vtEng) else 0L },
                        { vtEng != null && WgTunnelRegistry.awgTunnel === vtEng })
                    // ADAPTIVE ENGINE (1.79): remember
                    // that this live tunnel is an engine
                    // variant, so toggle-off can revert
                    // it. A normal connect clears it.
                    AdaptiveEngine.setWgEngineActive(this, t.name)
                    activeName = t.name
                    lastError = null
                    saveTunnels()
                    refreshStatus()
                    refreshList()
                } else {
                    AdaptiveEngine.append(this, AdaptiveEngine.S_WG,
                        "موتور: هیچ تغییری «${t.name}» را وصل نکرد؛ اتصال عادی انجام می‌شود.",
                        "Engine: no change connected \"${t.name}\"; normal connect follows.")
                    appLogWgEngineTried = true
                    engineBypassOnce = true
                    connectTunnel(t)
                }
            }
        }.start()
    }

    /** One WG variant attempt: UP, then up to 7s
     *  for a REAL handshake (getLastHandshake > 0
     *  for this tunnel object). UP state alone is
     *  not proof — a blackholed endpoint also shows
     *  UP. On failure the tunnel is taken DOWN so
     *  the next variant (or the normal connect)
     *  starts clean. */
    private fun tryWgVariant(t: WgTunnel, confText: String): Boolean {
        var tunnel: AwgSimpleTunnel? = null
        return try {
            val config = AwgConfig.parse(BufferedReader(StringReader(WgDirect.stripReserved(confText).trim())))
            val b = awgBackend() ?: return false
            tunnel = AwgSimpleTunnel(t.name)
            currentAwgTunnel = tunnel
            currentTunnel = null
            WgTunnelRegistry.awgTunnel = tunnel
            b.setState(tunnel, AwgTunnel.State.UP, config)
            val startMs = System.currentTimeMillis()
            var ok = false
            while (System.currentTimeMillis() - startMs < 7000) {
                try {
                    val hs = b.getLastHandshake(tunnel)
                    if (hs > 0 && hs * 1000 >= startMs - 10000) { ok = true; break }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
            if (!ok) {
                try { b.setState(tunnel, AwgTunnel.State.DOWN, null) } catch (_: Exception) {}
                if (WgTunnelRegistry.awgTunnel === tunnel) WgTunnelRegistry.awgTunnel = null
                if (currentAwgTunnel === tunnel) currentAwgTunnel = null
            }
            ok
        } catch (_: Exception) {
            try {
                val b = awgBackend()
                if (tunnel != null && b != null) b.setState(tunnel, AwgTunnel.State.DOWN, null)
            } catch (_: Exception) {}
            if (tunnel != null && WgTunnelRegistry.awgTunnel === tunnel) WgTunnelRegistry.awgTunnel = null
            if (tunnel != null && currentAwgTunnel === tunnel) currentAwgTunnel = null
            false
        }
    }

    // 2.43: central-log flags. Attempted gates the
    // attempt line (the engine fallback re-enters
    // connectTunnel); EngineTried marks a direct
    // connect that follows a failed engine run.
    private var appLogWgAttempted = false
    private var appLogWgEngineTried = false

    private fun connectTunnel(t: WgTunnel) {
        WgLog.add(L("شروع اتصال: ", "Connecting: ") + t.name)
        if (!appLogWgAttempted) {
            appLogWgAttempted = true
            val kindFa = if (WgDirect.isAwgConf(t.conf)) "امنزیا" else "وایرگارد"
            val kindEn = if (WgDirect.isAwgConf(t.conf)) "Amnezia" else "WireGuard"
            val engPath = AdaptiveEngine.isEnabled(this) && !engineBypassOnce && VpnService.prepare(this) == null && !VpnClientService.isRunning
            AppLog.append(this, "wireguard",
                "تلاش اتصال «${t.name}»؛ نوع: $kindFa؛ موتور هوشمند: ${if (engPath) "روشن" else "خاموش"}.",
                "Connect attempt “${t.name}”; type: $kindEn; smart engine: ${if (engPath) "ON" else "OFF"}.")
        }
        if (AdaptiveEngine.isEnabled(this) && !engineBypassOnce) {
            if (VpnService.prepare(this) == null && !VpnClientService.isRunning) {
                engineConnectTunnel(t)
                return
            }
        }
        engineBypassOnce = false
        // ADAPTIVE ENGINE (1.79): a normal connect is
        // not an engine variant, so it clears the
        // toggle-off revert marker.
        AdaptiveEngine.setWgEngineActive(this, null)
        // A hand-started tunnel is not smart connect's:
        // smart connect must never auto-off it later.
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", true).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        // Only one VPN at a time: if the v2ray VPN is
        // running, stop it first, then start WireGuard.
        if (VpnClientService.isRunning) {
            val stopIntent = Intent(this, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            handler.postDelayed({ connectTunnel(t) }, 800)
            return
        }
        // VPN permission, like the official app: if the
        // system dialog is needed, show it and continue
        // when it returns OK.
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingTunnel = t
            startActivityForResult(prepareIntent, 600)
            return
        }
        startWgInBackground(t)
    }

    private fun startWgInBackground(t: WgTunnel) {
        statusText.text = L("در حال اتصال...", "Connecting...")
        // ENGINE FALLBACK (1.76): the stock
        // WireGuard backend crashes on this
        // device — 1.72, 1.74 and 1.75 all threw
        // the app out, even fully isolated —
        // while the Amnezia backend connects
        // fine. So EVERY config, plain WireGuard
        // included, now goes through the Amnezia
        // engine, which accepts plain configs
        // unchanged. The slanted row label still
        // shows the config's own type.
        Thread {
            var upTunnel: AwgTunnel? = null
            try {
                val config = AwgConfig.parse(BufferedReader(StringReader(WgDirect.stripReserved(t.conf).trim())))
                val b = awgBackend()
                    ?: throw IllegalStateException(lastError ?: "awg-go unavailable")
                val tunnel = AwgSimpleTunnel(t.name)
                currentAwgTunnel = tunnel
                currentTunnel = null
                upTunnel = tunnel
                WgTunnelRegistry.awgTunnel = tunnel
                b.setState(tunnel, AwgTunnel.State.UP, config)
                runOnUiThread {
                    activeName = t.name
                    lastError = null
                    saveTunnels()
                    refreshStatus()
                    refreshList()
                    val kindFa = if (WgDirect.isAwgConf(t.conf)) "امنزیا" else "وایرگارد"
                    val kindEn = if (WgDirect.isAwgConf(t.conf)) "Amnezia" else "WireGuard"
                    val engNote = if (appLogWgEngineTried) "؛ موتور اول امتحان شد و نشد، بعد اتصال مستقیم" else "؛ اتصال مستقیم"
                    val engNoteEn = if (appLogWgEngineTried) "; engine tried first and failed, then direct connect" else "; direct connect"
                    appLogWgAttempted = false
                    appLogWgEngineTried = false
                    // 2.45: handshake check for the direct tunnel.
                    // is written only AFTER this check proves
                    // a fresh handshake; a DEAD verdict logs
                    // that instead, so the log never claims
                    // a connection the tunnel did not prove.
                    val vtDir = upTunnel
                    startWgVerify(t.name,
                        { val b = awgBackend(); if (b != null && vtDir != null) b.getLastHandshake(vtDir) else 0L },
                        { vtDir != null && WgTunnelRegistry.awgTunnel === vtDir },
                        { proven ->
                            runOnUiThread {
                                if (proven) {
                                    AppLog.append(this, "wireguard",
                                        "«${t.name}» وصل شد؛ نوع: $kindFa$engNote.",
                                        "“${t.name}” connected; type: $kindEn$engNoteEn.")
                                                                    } else {
                                    AppLog.append(this, "wireguard",
                                        "«${t.name}»؛ وصل نیست — تونل بالا است ولی دست‌تکانی ندارد.",
                                        "“${t.name}” not connected — the tunnel is up but has no handshake.")
                                }
                            }
                        })
                }
            } catch (e: Throwable) {
                if (upTunnel != null && WgTunnelRegistry.awgTunnel === upTunnel)
                    WgTunnelRegistry.awgTunnel = null
                // This is the real reason, shown in the
                // status line so it can be reported:
                // VPN_NOT_AUTHORIZED, TUN_CREATION_ERROR,
                // DNS_RESOLUTION_FAILURE, GO_ACTIVATION,
                // bad key, bad endpoint, etc.
                runOnUiThread {
                    lastError = e.message ?: e.javaClass.simpleName
                    if (activeName == t.name) activeName = null
                    statusText.text = L("وصل نشد: ", "Failed to connect: ") + lastError
                    refreshList()
                    refreshStatus()
                    appLogWgAttempted = false
                    appLogWgEngineTried = false
                    AppLog.append(this, "wireguard",
                        "«${t.name}» وصل نشد: $lastError.",
                        "“${t.name}” did not connect: $lastError.")
                }
            }
        }.start()
    }

    private fun disconnectWg() {
        WgLog.add(L("قطع دستی", "Manual disconnect"))
        val wgStopName = activeName
        appLogWgAttempted = false
        appLogWgEngineTried = false
        if (wgStopName != null) {
            AppLog.append(this, "wireguard",
                "«$wgStopName»؛ دستی قطع شد.", "“$wgStopName”; stopped manually.")
        }
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        // ADAPTIVE ENGINE (1.79): disconnecting clears
        // the engine-variant marker with it.
        AdaptiveEngine.setWgEngineActive(this, null)
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", false).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        val tunnel = currentTunnel
        val tunnelAwg = currentAwgTunnel
        Thread {
            try {
                if (tunnel != null) {
                    backend()?.setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (_: Throwable) {
                // Turning off must never hang the UI:
                // even if GoBackend throws, the switch
                // and status are cleared below.
            }
            try {
                if (tunnelAwg != null) {
                    awgBackend()?.setState(tunnelAwg, AwgTunnel.State.DOWN, null)
                }
            } catch (_: Throwable) {}
            runOnUiThread {
                activeName = null
                if (tunnel != null && WgTunnelRegistry.wgTunnel === tunnel)
                    WgTunnelRegistry.wgTunnel = null
                if (tunnelAwg != null && WgTunnelRegistry.awgTunnel === tunnelAwg)
                    WgTunnelRegistry.awgTunnel = null
                currentTunnel = null
                currentAwgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }.start()
        // Update the UI at once, do not wait for the
        // background thread, so the switch never looks
        // stuck on.
        activeName = null
        refreshStatus()
        refreshList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 600) {
            val pending = pendingTunnel
            pendingTunnel = null
            if (resultCode == RESULT_OK && pending != null) {
                // Route through connectTunnel (not
                // straight to the background start)
                // so the engine gets its turn on the
                // permission-granted tap too; with
                // the engine off this is identical
                // to the old direct call.
                connectTunnel(pending)
            } else {
                statusText.text = L("دسترسی VPN تأیید نشد.", "VPN permission was not confirmed.")
                refreshList()
            }
            return
        }
        if (requestCode == 500 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data ?: return
                var fileName: String? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = c.getString(idx)
                        }
                    }
                } catch (_: Exception) {}
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                if (text.isBlank() || !text.contains("[Interface]")) {
                    statusText.text = L("فایل انتخاب‌شده کانفیگ وایرگارد نیست.", "The selected file is not a WireGuard config.")
                    return
                }
                val base = fileName?.removeSuffix(".conf")?.removeSuffix(".CONF")
                    ?.takeIf { it.isNotBlank() }
                addTunnelFromText(text, base)
            } catch (e: Exception) {
                statusText.text = L("خواندن فایل ناموفق: ", "Reading the file failed: ") + (e.message ?: "")
            }
        }
    }
}
