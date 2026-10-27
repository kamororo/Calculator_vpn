package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

/** SlipNet section, modelled on the SlipNet app itself:
 *  dark profile list, bottom status card, + FAB opening the
 *  New Profile sheet with every tunnel type, ⋮ menu
 *  (Real Ping / Simple Ping / Export / Import / Delete
 *  duplicates / Delete all), Chain, and a Settings page
 *  (SlipNetSettingsActivity) with every option from the
 *  app's Settings screenshots. Profiles and settings live
 *  in renamer_slipnet / renamer_slipnet_settings.
 *  Connect, in this build, works for VLESS and SOCKS5
 *  through the app's existing Xray engine; the other
 *  tunnel engines (DNSTT / VayDNS / Slipstream / SSH /
 *  NaiveProxy / DOH / Tor) are stored with their full
 *  fields and their engines follow — they are not
 *  claimed working until they are tested on device. */
class SlipNetActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private val handler = Handler(Looper.getMainLooper())
    private var profiles = mutableListOf<SlipNetStore.Profile>()
    private var selected = 0
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var bottomName: TextView
    private lateinit var bottomType: TextView
    private lateinit var fabPower: Button
    private lateinit var fabPlus: Button
    private var coreInitDone = false
    private val poller = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = SlipNetStore.load(this)
        val bg = Color.parseColor("#121212")
        val fg = Color.WHITE
        val accent = Color.parseColor("#A9C6FF")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(bg)
            setPadding(24, 24, 24, 24)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "SlipNet"; textSize = 26f; setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(btn("↗") { shareProfiles() })
        top.addView(btn("⚙") { startActivity(Intent(this, SlipNetSettingsActivity::class.java)) })
        top.addView(btn("⋮") { showMainMenu() })
        root.addView(top)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listBox)
        }
        root.addView(scroll)
        // Bottom status card + FABs, like SlipNet's own bottom bar.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(20, 16, 20, 16)
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(this).apply { text = L("قطع است", "Not Connected"); textSize = 17f; setTextColor(fg) }
        bottomName = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#AAAAAA")) }
        bottomType = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")) }
        info.addView(statusText); info.addView(bottomName); info.addView(bottomType)
        bottom.addView(info)
        // Circular FABs, exactly like SlipNet's own
        // bottom bar: + on the left, big power on the
        // right. A plain Button is rectangular, which
        // is why the old connect button looked broken.
        fun circleFab(text: String, color: Int): Button {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
            return Button(this).apply {
                this.text = text; textSize = 24f
                setTextColor(Color.parseColor("#1B1B2F"))
                background = d
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                val dp = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { setMargins(8, 0, 8, 0) }
            }
        }
        fabPlus = circleFab("+", accent)
        fabPlus.setOnClickListener { showNewProfile() }
        fabPower = circleFab("⏻", accent)
        fabPower.setOnClickListener { toggleConnect() }
        bottom.addView(fabPlus); bottom.addView(fabPower)
        root.addView(bottom)
        setContentView(root)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        profiles = SlipNetStore.load(this)
        refreshList()
        handler.post(poller)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(poller) }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 20f; setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { f() }
    }

    private fun refreshStatus() {
        // Power button state, like SlipNet: light
        // blue when disconnected, green when on.
        if (::fabPower.isInitialized) {
            val col = if (VpnClientService.isRunning) Color.parseColor("#2E9E5B") else Color.parseColor("#A9C6FF")
            (fabPower.background as? android.graphics.drawable.GradientDrawable)?.setColor(col)
            fabPower.setTextColor(if (VpnClientService.isRunning) Color.WHITE else Color.parseColor("#1B1B2F"))
        }
        if (VpnClientService.isRunning) {
            statusText.text = L("متصل: ", "Connected: ") + (VpnClientService.currentName ?: "")
            val p = profiles.getOrNull(selected)
            bottomName.text = p?.name ?: ""
            bottomType.text = p?.let { SlipNetStore.displayType(it.type) } ?: ""
        } else {
            val err = VpnClientService.lastError
            statusText.text = if (err != null) L("قطع شد: ", "Disconnected: ") + err else L("قطع است", "Not Connected")
            val p = profiles.getOrNull(selected)
            bottomName.text = p?.name ?: ""
            bottomType.text = p?.let { SlipNetStore.displayType(it.type) } ?: ""
        }
    }

    private fun refreshList() {
        listBox.removeAllViews()
        if (profiles.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = L("هنوز پروفایلی اضافه نشده است. با دکمه + پایین صفحه، یک پروفایل بساز یا وارد کن.", "No profiles yet. Use the + button below to create or import one.")
                textSize = 15f; setTextColor(Color.parseColor("#AAAAAA")); setPadding(0, 32, 0, 0)
            })
        }
        for ((i, p) in profiles.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 18, 0, 18)
                if (i == selected) setBackgroundColor(Color.parseColor("#222233"))
                setOnClickListener { selected = i; refreshList(); refreshStatus() }
            }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            txt.addView(TextView(this).apply { text = p.name; textSize = 18f; setTextColor(Color.WHITE) })
            txt.addView(TextView(this).apply {
                text = if (p.type == "chain") p.get("parts") else p.domain
                textSize = 14f; setTextColor(Color.parseColor("#AAAAAA"))
            })
            txt.addView(TextView(this).apply {
                text = SlipNetStore.displayType(p.type); textSize = 13f; setTextColor(Color.parseColor("#AAAAAA"))
            })
            row.addView(txt)
            row.addView(btn("✎") { openEdit(profiles.indexOf(p), p.type) })
            row.addView(btn("⋮") { showRowMenu(i) })
            row.addView(btn("✕") { confirmDelete(i) })
            listBox.addView(row)
            listBox.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }
        refreshStatus()
    }

    private fun showRowMenu(i: Int) {
        val p = profiles[i]
        AlertDialog.Builder(this)
            .setItems(arrayOf(L("ویرایش", "Edit"), L("کپی لینک", "Copy link"), L("حذف", "Delete"))) { _, which ->
                when (which) {
                    0 -> openEdit(i, p.type)
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("slipnet", SlipNetStore.exportText(listOf(p))))
                        Toast.makeText(this, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                    }
                    2 -> confirmDelete(i)
                }
            }.show()
    }

    private fun confirmDelete(i: Int) {
        AlertDialog.Builder(this)
            .setMessage(L("پروفایل «${profiles[i].name}» حذف شود؟", "Delete profile \"${profiles[i].name}\"?"))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                profiles.removeAt(i)
                if (selected >= profiles.size) selected = maxOf(0, profiles.size - 1)
                SlipNetStore.save(this, profiles); refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun showMainMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                L("Real Ping", "Real Ping"), L("Simple Ping", "Simple Ping"),
                L("خروجی همه پروفایل‌ها", "Export All Profiles"),
                L("وارد کردن پروفایل‌ها", "Import Profiles"),
                L("حذف پروفایل‌های تکراری", "Delete Duplicate Profiles"),
                L("حذف همه پروفایل‌ها", "Delete All Profiles")
            )) { _, which ->
                when (which) {
                    0, 1 -> statusText.text = L("تست پینگ برای پروفایل‌های اسلیپ‌نت هنوز در حال ساخت است.", "Ping test for SlipNet profiles is still being built.")
                    2 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("slipnet", SlipNetStore.exportText(profiles)))
                        Toast.makeText(this, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                    }
                    3 -> showImport()
                    4 -> {
                        val seen = mutableSetOf<String>(); val kept = mutableListOf<SlipNetStore.Profile>()
                        for (p in profiles) if (seen.add(p.name + "|" + p.type + "|" + p.domain)) kept.add(p)
                        profiles = kept; SlipNetStore.save(this, profiles); refreshList()
                    }
                    5 -> AlertDialog.Builder(this)
                        .setMessage(L("همه ${profiles.size} پروفایل حذف شود؟", "Delete all ${profiles.size} profiles?"))
                        .setPositiveButton(L("بله، حذف همه", "Yes, delete all")) { _, _ ->
                            profiles.clear(); SlipNetStore.save(this, profiles); refreshList()
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null).show()
                }
            }.show()
    }

    private fun showImport() {
        val box = EditText(this).apply { hint = L("slipnet://... یا vless://... یا JSON", "slipnet://... or vless://... or JSON"); minLines = 4 }
        AlertDialog.Builder(this)
            .setTitle(L("وارد کردن پروفایل‌ها", "Import Profiles"))
            .setView(box)
            .setPositiveButton(L("وارد کردن", "Import")) { _, _ ->
                val text = box.text.toString().trim()
                var added = 0
                for (line in text.lines()) {
                    val t = line.trim(); if (t.isBlank()) continue
                    val p = when {
                        t.startsWith("slipnet://") -> SlipNetStore.importSlipnetUri(t)
                        t.startsWith("vless://") -> {
                            try {
                                val uri = java.net.URI(t)
                                val json = JSONObject()
                                json.put("type", "vless")
                                json.put("name", java.net.URLDecoder.decode(uri.fragment ?: "VLESS", "UTF-8"))
                                json.put("domain", uri.host ?: "")
                                json.put("cdnPort", if (uri.port > 0) uri.port.toString() else "443")
                                json.put("vlessUuid", uri.userInfo ?: "")
                                SlipNetStore.Profile(json)
                            } catch (_: Exception) { null }
                        }
                        t.startsWith("{") || t.startsWith("[") -> {
                            try {
                                val arr = if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONArray().put(JSONObject(t))
                                for (i in 0 until arr.length()) { profiles.add(SlipNetStore.Profile(arr.getJSONObject(i))); added++ }
                                null
                            } catch (_: Exception) { null }
                        }
                        else -> null
                    }
                    if (p != null) { profiles.add(p); added++ }
                }
                SlipNetStore.save(this, profiles); refreshList()
                statusText.text = L("$added پروفایل وارد شد.", "$added profile(s) imported.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null).show()
    }

    private fun showNewProfile() {
        val types = listOf(
            "dnstt" to "DNSTT", "sayedns" to "NoizDNS", "vaydns" to "VayDNS",
            "slipstream" to "Slipstream", "ssh" to "SSH", "doh" to "DOH",
            "socks5" to "SOCKS5", "vless" to "VLESS", "naive" to "NaiveProxy",
            "snowflake" to "Tor"
        )
        val names = types.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(L("پروفایل جدید", "New Profile"))
            .setItems(names) { _, which ->
                val type = types[which].first
                if (type == "dnstt" || type == "sayedns" || type == "vaydns" || type == "slipstream" || type == "naive") {
                    // Offer the +SSH variant, exactly like SlipNet's type list.
                    AlertDialog.Builder(this)
                        .setTitle(L("نوع پروفایل", "Profile type"))
                        .setItems(arrayOf(names[which], names[which] + " + SSH")) { _, w2 ->
                            if (w2 == 0) openEdit(-1, type) else openEdit(-1, type + "_ssh")
                        }.show()
                } else openEdit(-1, type)
            }
            .setNeutralButton(L("Chain", "Chain")) { _, _ -> showChain() }
            .setNegativeButton(L("Import", "Import")) { _, _ -> showImport() }
            .show()
    }

    private fun showChain() {
        if (profiles.size < 2) {
            statusText.text = L("برای زنجیره حداقل ۲ پروفایل لازم است.", "A chain needs at least 2 profiles.")
            return
        }
        val names = profiles.map { it.name }.toTypedArray()
        val checked = BooleanArray(profiles.size)
        AlertDialog.Builder(this)
            .setTitle(L("زنجیره پروفایل‌ها", "Chain multiple profiles"))
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(L("ساخت زنجیره", "Create chain")) { _, _ ->
                val chosen = profiles.filterIndexed { i, _ -> checked[i] }
                if (chosen.size < 2) { statusText.text = L("برای زنجیره حداقل ۲ پروفایل انتخاب کن.", "Select at least 2 profiles for a chain."); return@setPositiveButton }
                val json = JSONObject()
                json.put("type", "chain")
                json.put("name", L("زنجیره", "Chain"))
                json.put("parts", chosen.joinToString(" → ") { it.name })
                json.put("partTypes", chosen.joinToString(",") { it.type })
                profiles.add(SlipNetStore.Profile(json))
                SlipNetStore.save(this, profiles); refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null).show()
    }

    private fun openEdit(index: Int, type: String) {
        val intent = Intent(this, SlipNetEditActivity::class.java).apply {
            putExtra(SlipNetEditActivity.EXTRA_INDEX, index)
            putExtra(SlipNetEditActivity.EXTRA_TYPE, type)
        }
        startActivityForResult(intent, 602)
    }

    private fun shareProfiles() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("slipnet", SlipNetStore.exportText(profiles)))
        Toast.makeText(this, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
    }

    private fun toggleConnect() {
        if (VpnClientService.isRunning) {
            val stop = Intent(this, VpnClientService::class.java).apply { action = "STOP" }
            try { startService(stop) } catch (_: Exception) {}
            try { stopService(Intent(this, VpnClientService::class.java)) } catch (_: Exception) {}
            VpnClientService.isRunning = false; VpnClientService.currentName = null
            SlipNetSsh.stop()
            SlipNetDns.stop()
            app.slipnet.tunnel.SlipstreamBridge.stopClient()
            SlipNetNaive.stop()
            refreshStatus(); return
        }
        val p = profiles.getOrNull(selected) ?: run {
            statusText.text = L("اول یک پروفایل را انتخاب کن.", "Select a profile first."); return
        }
        if (p.type == "ssh") {
            val intent = VpnService.prepare(this)
            if (intent != null) { startActivityForResult(intent, 601); return }
            startSsh(p); return
        }
        // Real SlipNet engines: DNSTT / NoizDNS / VayDNS
        // (SlipNet's own Go library), Slipstream (its Rust
        // library) and NaiveProxy (its binary) — each
        // creates a local SOCKS proxy, then the Xray
        // TUN routes through it, exactly as SlipNet's
        // own TUN routes through its local proxy.
        if (p.type in listOf("dnstt", "dnstt_ssh", "sayedns", "sayedns_ssh", "vaydns", "vaydns_ssh")) {
            val intent = VpnService.prepare(this)
            if (intent != null) { startActivityForResult(intent, 603); return }
            startDns(p); return
        }
        if (p.type.startsWith("slipstream")) {
            val intent = VpnService.prepare(this)
            if (intent != null) { startActivityForResult(intent, 604); return }
            startSlipstream(p); return
        }
        if (p.type.startsWith("naive")) {
            val intent = VpnService.prepare(this)
            if (intent != null) { startActivityForResult(intent, 605); return }
            startNaive(p); return
        }
        val uri = SlipNetStore.xrayUri(p)
        if (uri == null) {
            statusText.text = L("موتور ${SlipNetStore.displayType(p.type)} هنوز در حال ساخت است؛ پروفایل ذخیره شده است.", "The ${SlipNetStore.displayType(p.type)} engine is still being built; the profile was saved.")
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) { startActivityForResult(intent, 600); return }
        startXray(uri, p.name)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 600) {
            val p = profiles.getOrNull(selected) ?: return
            val uri = SlipNetStore.xrayUri(p) ?: return
            startXray(uri, p.name)
        }
        if (requestCode == 601) {
            val p = profiles.getOrNull(selected) ?: return
            startSsh(p)
        }
        if (requestCode == 603) {
            val p = profiles.getOrNull(selected) ?: return
            startDns(p)
        }
        if (requestCode == 604) {
            val p = profiles.getOrNull(selected) ?: return
            startSlipstream(p)
        }
        if (requestCode == 605) {
            val p = profiles.getOrNull(selected) ?: return
            startNaive(p)
        }
        if (requestCode == 602) {
            profiles = SlipNetStore.load(this)
            if (selected >= profiles.size) selected = maxOf(0, profiles.size - 1)
            refreshList()
        }
    }

    /** Standalone SSH connect: JSch session + local SOCKS,
     *  then the Xray TUN through that SOCKS proxy. */
    private fun startSsh(p: SlipNetStore.Profile) {
        statusText.text = L("در حال اتصال SSH...", "Connecting SSH...")
        Thread {
            try {
                val port = p.get("sshPort", "22").toIntOrNull() ?: 22
                val local = SlipNetSsh.start(
                    p.domain, port, p.get("sshUser"), p.get("sshPass"),
                    p.get("sshKey"), p.get("sshKeyPass")
                ) { msg -> runOnUiThread { statusText.text = msg } }
                runOnUiThread {
                    startXray("socks://127.0.0.1:$local", p.name)
                }
            } catch (e: Throwable) {
                SlipNetSsh.stop()
                runOnUiThread {
                    statusText.text = L("اتصال SSH ناموفق: ${e.message ?: e.javaClass.simpleName}", "SSH connect failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun startDns(p: SlipNetStore.Profile) {
        statusText.text = L("در حال اتصال ${SlipNetStore.displayType(p.type)}...", "Connecting ${SlipNetStore.displayType(p.type)}...")
        Thread {
            try {
                val port = SlipNetDns.start(this, p, 10802)
                val user = p.get("socksUser"); val pass = p.get("socksPass")
                val uri = if (user.isNotBlank()) "socks://$user:$pass@127.0.0.1:$port" else "socks://127.0.0.1:$port"
                runOnUiThread { startXray(uri, p.name) }
            } catch (e: Throwable) {
                SlipNetDns.stop()
                runOnUiThread { statusText.text = L("اتصال ${SlipNetStore.displayType(p.type)} ناموفق: ${e.message ?: e.javaClass.simpleName}", "${SlipNetStore.displayType(p.type)} connect failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun startSlipstream(p: SlipNetStore.Profile) {
        statusText.text = L("در حال اتصال Slipstream...", "Connecting Slipstream...")
        Thread {
            try {
                val resolvers = SlipNetDns.formatResolvers(p.get("resolvers")).split(",").filter { it.isNotBlank() }
                val rc = app.slipnet.tunnel.SlipstreamBridge.startClient(p.domain, resolvers, 10803)
                if (rc != 0) throw RuntimeException(app.slipnet.tunnel.SlipstreamBridge.lastError() ?: "Slipstream error $rc")
                runOnUiThread { startXray("socks://127.0.0.1:10803", p.name) }
            } catch (e: Throwable) {
                app.slipnet.tunnel.SlipstreamBridge.stopClient()
                runOnUiThread { statusText.text = L("اتصال Slipstream ناموفق: ${e.message ?: e.javaClass.simpleName}", "Slipstream connect failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun startNaive(p: SlipNetStore.Profile) {
        statusText.text = L("در حال اتصال NaiveProxy...", "Connecting NaiveProxy...")
        Thread {
            try {
                val port = SlipNetNaive.start(this, p, 10804)
                runOnUiThread { startXray("socks://127.0.0.1:$port", p.name) }
            } catch (e: Throwable) {
                SlipNetNaive.stop()
                runOnUiThread { statusText.text = L("اتصال NaiveProxy ناموفق: ${e.message ?: e.javaClass.simpleName}", "NaiveProxy connect failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun startXray(uri: String, name: String) {
        val vpnConfig = try { VpnConfigBuilder.build(this, uri) } catch (_: Exception) { null }
        if (vpnConfig == null) {
            statusText.text = L("این پروفایل برای اتصال پشتیبانی نمی‌شود.", "This profile is not supported for connecting."); return
        }
        if (!coreInitDone) { coreInitDone = CoreInit.init(applicationContext) }
        if (!coreInitDone) {
            statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start."); return
        }
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig); putExtra("name", name)
        }
        VpnClientService.emergencyConfig = null
        startService(intent)
        statusText.text = L("در حال اتصال...", "Connecting...")
        handler.postDelayed({ refreshStatus() }, 1500)
    }
}
