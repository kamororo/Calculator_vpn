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
                } else if (activeName == displayName) {
                    activeName = null
                }
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
    private var lastError: String? = null
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
            null
        }
    }

    private fun isWgUp(): Boolean {
        val b = try { WgBackend.get(applicationContext) } catch (_: Throwable) { null } ?: return false
        val t = currentTunnel ?: return false
        return try { b.getState(t) == Tunnel.State.UP } catch (_: Throwable) { false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(root)
        root.addView(TextView(this).apply {
            text = "WireGuard"; textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })
        statusText = TextView(this).apply {
            text = L("قطع است", "Disconnected"); textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusText)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        val frame = android.widget.FrameLayout(this)
        frame.addView(scrollView)
        val fabSize = (72 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        val fab = Button(this).apply {
            text = "+"; textSize = 28f
            setTextColor(Color.parseColor("#333333"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E8F4FD"))
                setStroke(2, Color.parseColor("#BBDEFB"))
            }
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

    private fun refreshList() {
        listContainer.removeAllViews()
        if (tunnels.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز تونلی اضافه نشده است. با دکمه + پایین صفحه، فایل یا متن وایرگارد را وارد کن.",
                         "No tunnel yet. Use the + button at the bottom to import a WireGuard file or text.")
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
                setPadding(0, 14, 0, 14)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply { text = t.name; textSize = 17f })
            info.addView(TextView(this).apply {
                text = t.peers.firstOrNull()?.endpoint ?: ""
                textSize = 13f; setTextColor(Color.GRAY)
            })
            row.addView(info)
            row.addView(Button(this).apply {
                text = "✕"; textSize = 13f
                setTextColor(Color.parseColor("#B00020"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#FCE4EC"))
                }
                val s = (36 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(4, 0, 12, 0) }
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val wasActive = t.name == activeName
                    tunnels.remove(t)
                    if (wasActive) {
                        disconnectWg()
                        activeName = null
                        currentTunnel = null
                    }
                    saveTunnels(); refreshList(); refreshStatus()
                }
            })
            val sw = Switch(this)
            switchUpdating = true
            sw.isChecked = isActive
            switchUpdating = false
            sw.setOnCheckedChangeListener { _, checked ->
                if (switchUpdating) return@setOnCheckedChangeListener
                if (checked) connectTunnel(t) else disconnectWg()
            }
            row.addView(sw)
            row.setOnClickListener { showDetails(t) }
            listContainer.addView(row)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(Color.parseColor("#DDDDDD"))
            })
        }
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
            L("Keepalive: ", "Keepalive: ") + (peer?.keepAlive?.toString() ?: "-")
        android.app.AlertDialog.Builder(this)
            .setTitle("WireGuard")
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
        // Also validate with the official parser, so a
        // bad key or endpoint is caught here, with its
        // real reason, instead of failing silently later.
        try {
            Config.parse(BufferedReader(StringReader(text.trim())))
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
    private fun connectTunnel(t: WgTunnel) {
        // A hand-started tunnel is not smart connect's:
        // smart connect must never auto-off it later.
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", true).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        // Only one VPN at a time: if the v2ray VPN is
        // running, stop it first, then start WireGuard.
        if (VpnClientService.isRunning) {
            val stopIntent = Intent(this, VpnClientService::class.java).apply { action = "STOP" }
            try { startService(stopIntent) } catch (_: Exception) {}
            try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
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
        Thread {
            try {
                val config = Config.parse(BufferedReader(StringReader(t.conf.trim())))
                val b = backend()
                    ?: throw IllegalStateException(lastError ?: "wg-go unavailable")
                val tunnel = SimpleTunnel(t.name)
                currentTunnel = tunnel
                b.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread {
                    activeName = t.name
                    lastError = null
                    saveTunnels()
                    refreshStatus()
                    refreshList()
                }
            } catch (e: Throwable) {
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
                }
            }
        }.start()
    }

    private fun disconnectWg() {
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", false).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        val tunnel = currentTunnel
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
            runOnUiThread {
                activeName = null
                currentTunnel = null
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
                startWgInBackground(pending)
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
