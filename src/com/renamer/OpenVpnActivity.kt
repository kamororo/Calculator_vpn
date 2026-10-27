package com.renamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class OpenVpnActivity : Activity() {
    companion object {
        const val NAVY = "#1B365D"
        const val ORANGE = "#ED7D31"
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var listContainer: LinearLayout
    private var profiles: MutableList<OvpnProfile> = mutableListOf()
    private var switchUpdating = false
    private var lastShownState: String? = null
    private var needDialogShownFor: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val state = (OvpnEngine.activeName ?: "") + "|" + OvpnEngine.isConnected + "|" + OvpnEngine.isConnecting + "|" + (OvpnEngine.lastError ?: "") + "|" + (OvpnEngine.lastState ?: "") + "|" + (OvpnEngine.lastStateMsg ?: "") + "|" + OvpnEngine.waitingForInput + "|" + OvpnEngine.logLines.size + "|" + OvpnEngine.logText().takeLast(200)
            if (state != lastShownState) {
                lastShownState = state
                refreshStatus()
                refreshList()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = OvpnStore.load(this)
        if (intent.getBooleanExtra("widget_auto_connect", false) && !OvpnEngine.isConnected && !OvpnEngine.isConnecting) {
            val last = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_last", null)
            val all = OvpnStore.load(this)
            val pick = all.firstOrNull { it.name == last } ?: all.firstOrNull()
            if (pick != null) OvpnStore.pendingConnect = pick
            try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", pick?.name ?: "").apply() } catch (_: Exception) {}
        }
        val frame = FrameLayout(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Header, exactly like the screenshot: dark navy
        // bar, menu on the left, "Profiles" in white,
        // import icon on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(NAVY))
            setPadding(24, 32, 24, 32)
        }
        header.addView(TextView(this).apply {
            text = "☰"; textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 24, 0)
            setOnClickListener { finish() }
        })
        // Orange dot on the menu, like the screenshot.
        header.addView(View(this).apply {
            val s = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(ORANGE))
            }
        })
        header.addView(TextView(this).apply {
            text = "Profiles"; textSize = 26f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        })
        header.addView(TextView(this).apply {
            text = "⧉"; textSize = 26f
            setTextColor(Color.WHITE)
            setOnClickListener { importFile() }
        })
        root.addView(header)
        statusText = TextView(this).apply {
            text = "DISCONNECTED"; textSize = 20f
            setTextColor(Color.parseColor(NAVY))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(32, 32, 32, 16)
        }
        root.addView(statusText)
        // Last engine state + last log lines, so a
        // stuck CONNECTING is visible as WAIT / AUTH /
        // RECONNECTING / NEED, not just "connecting".
        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(32, 0, 32, 8)
        }
        root.addView(logText)
        root.addView(TextView(this).apply {
            text = L("گزارش کامل اتصال", "Full connection log")
            textSize = 13f
            setTextColor(Color.parseColor(NAVY))
            setPadding(32, 0, 32, 16)
            setOnClickListener { showLogDialog() }
        })
        // v1.32: OpenVPN3 engine. No compat-mode
        // button anymore: there is no TLS toggle
        // in OpenVPN3, the core is the official
        // one and the config is used verbatim.
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 0)
        }
        root.addView(listContainer)
        // Bottom divider, like the screenshot.
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(32, 24, 32, 0) }
            setBackgroundColor(Color.parseColor(NAVY))
        })
        scroll.addView(root)
        frame.addView(scroll)
        // Orange + FAB, bottom-right, like the screenshot.
        val fabSize = (72 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        frame.addView(Button(this).apply {
            text = "+"; textSize = 32f
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(ORANGE))
            }
            layoutParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { importFile() }
        })
        // Bottom padding so the FAB never hides a row.
        root.setPadding(0, 0, 0, fabSize + fabMargin)
        setContentView(frame)
        frame.fitsSystemWindows = true
        refreshList()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        profiles = OvpnStore.load(this)
        handler.post(poller)
        refreshList()
        refreshStatus()
        val toConnect = OvpnStore.pendingConnect
        if (toConnect != null) {
            OvpnStore.pendingConnect = null
            connectProfile(toConnect)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun refreshStatus() {
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_openvpn", OvpnEngine.isConnected).apply() } catch (_: Exception) {}
        if (OvpnEngine.isConnected && OvpnEngine.activeName != null) {
            statusText.text = "CONNECTED: ${OvpnEngine.activeName}"
            statusText.setTextColor(Color.parseColor("#2E9E5B"))
            needDialogShownFor = null
        } else if (OvpnEngine.isConnecting && OvpnEngine.activeName != null) {
            val st = OvpnEngine.lastState
            statusText.text = if (st != null) "CONNECTING: ${OvpnEngine.activeName}  [$st] [OpenVPN3]"
                              else "CONNECTING: ${OvpnEngine.activeName} [OpenVPN3]"
            statusText.setTextColor(Color.parseColor(ORANGE))
            // Server is waiting for credentials that
            // were not supplied: surface it as a
            // prompt instead of a silent CONNECTING.
            if (OvpnEngine.waitingForInput && needDialogShownFor != OvpnEngine.activeName) {
                needDialogShownFor = OvpnEngine.activeName
                showNeedCredentialsDialog()
            }
        } else {
            statusText.text = "DISCONNECTED"
            statusText.setTextColor(Color.parseColor(NAVY))
            needDialogShownFor = null
        }
        val lastLogs = synchronized(OvpnEngine.logLines) {
            OvpnEngine.logLines.takeLast(3)
        }
        logText.text = buildString {
            if (OvpnEngine.lastState != null) append(L("وضعیت موتور: ${OvpnEngine.lastState}\n", "Engine status: ${OvpnEngine.lastState}\n"))
            if (lastLogs.isNotEmpty()) append(lastLogs.joinToString("\n"))
        }
    }

    private fun showLogDialog() {
        val text = OvpnEngine.logText().ifBlank {
            L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.")
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش اتصال OpenVPN", "OpenVPN connection log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("openvpn log", OvpnEngine.logText()))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show()
    }

    private fun showNeedCredentialsDialog() {
        val p = profiles.firstOrNull { it.name == OvpnEngine.activeName } ?: return
        val userInput = EditText(this).apply { hint = "Username"; setText(p.username) }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(userInput); addView(passInput)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("سرور یوزرنیم/پسورد می‌خواهد", "Server needs username/password"))
            .setMessage(L("اتصال در انتظار یوزرنیم/پسورد مانده است. آن‌ها را وارد کن تا دوباره وصل شود.", "The connection is waiting for username/password. Enter them to reconnect."))
            .setView(col)
            .setPositiveButton(L("وصل شو", "Connect")) { _, _ ->
                p.username = userInput.text.toString().trim()
                p.password = passInput.text.toString()
                OvpnStore.save(this, profiles)
                OvpnEngine.disconnect()
                needDialogShownFor = null
                connectProfile(p)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        if (OvpnEngine.lastError != null) {
            listContainer.addView(TextView(this).apply {
                text = OvpnEngine.lastError
                textSize = 13f
                setTextColor(Color.parseColor("#B00020"))
                setPadding(0, 0, 0, 16)
            })
        }
        if (profiles.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز پروفایلی اضافه نشده است. با دکمه + نارنجی پایین صفحه، یک فایل .ovpn وارد کن.",
                         "No profile yet. Tap the orange + button to import an .ovpn file.")
                setTextColor(Color.GRAY)
            })
            return
        }
        val connected = OvpnEngine.isConnected || OvpnEngine.isConnecting
        for ((index, p) in profiles.withIndex()) {
            val isActive = connected && OvpnEngine.activeName == p.name
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 20, 0, 20)
            }
            val sw = Switch(this)
            switchUpdating = true
            sw.isChecked = isActive
            switchUpdating = false
            sw.setOnCheckedChangeListener { _, checked ->
                if (switchUpdating) return@setOnCheckedChangeListener
                if (checked) connectProfile(p) else OvpnEngine.disconnect()
            }
            row.addView(sw)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(24, 0, 12, 0)
            }
            info.addView(TextView(this).apply {
                text = "OpenVPN Profile"; textSize = 14f
                setTextColor(Color.parseColor("#999999"))
            })
            info.addView(TextView(this).apply {
                text = p.name; textSize = 18f
                setTextColor(Color.BLACK)
            })
            info.setOnClickListener { editProfile(index) }
            row.addView(info)
            // Profile diagnosis (no sensitive values,
            // only which blocks/options the file has).
            row.addView(TextView(this).apply {
                text = "ℹ"; textSize = 22f
                setTextColor(Color.parseColor(NAVY))
                setPadding(12, 0, 12, 0)
                setOnClickListener { showDiagnosis(index) }
            })
            // Edit (pencil), like the screenshot.
            row.addView(TextView(this).apply {
                text = "✎"; textSize = 24f
                setTextColor(Color.parseColor("#999999"))
                setPadding(12, 0, 12, 0)
                setOnClickListener { editProfile(index) }
            })
            // Delete, with a confirm dialog (never instant).
            row.addView(Button(this).apply {
                text = "✕"; textSize = 13f
                setTextColor(Color.parseColor("#B00020"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#FCE4EC"))
                }
                val s = (36 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
                setPadding(0, 0, 0, 0)
                setOnClickListener { confirmDelete(index) }
            })
            listContainer.addView(row)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            })
        }
    }

    private fun showDiagnosis(index: Int) {
        val p = profiles[index]
        val text = p.ovpn
        fun has(re: String): Boolean = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(text)
        fun lineOpt(name: String): String {
            val m = Regex("(?m)^\\s*$name\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            return m?.groupValues?.getOrNull(1)?.trim() ?: "—"
        }
        // What the ics-openvpn ConfigParser itself
        // makes of this file (no values, only types
        // and presence). If this says the cert/key
        // are missing or the profile check fails,
        // the engine is dropping something.
        var parserInfo = ""
        try {
            val cp = de.blinkt.openvpn.core.ConfigParser()
            cp.parseConfig(java.io.StringReader(text))
            val vp = cp.convertProfile()
            val check = vp.checkProfile(this)
            val checkStr = try { getString(check) } catch (_: Exception) { "res $check" }
            val authType = when (vp.mAuthenticationType) {
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS -> L("USERPASS (فقط یوزرنیم/پسورد)", "USERPASS (username/password only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_CERTIFICATES -> L("USERPASS_CERTIFICATES (یوزرنیم/پسورد + گواهی)", "USERPASS_CERTIFICATES (username/password + certificate)")
                de.blinkt.openvpn.VpnProfile.TYPE_CERTIFICATES -> L("CERTIFICATES (فقط گواهی)", "CERTIFICATES (certificate only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_PKCS12 -> "USERPASS_PKCS12"
                de.blinkt.openvpn.VpnProfile.TYPE_PKCS12 -> "PKCS12"
                else -> L("نوع ${vp.mAuthenticationType}", "Type ${vp.mAuthenticationType}")
            }
            parserInfo = L("نوع احراز هویت (موتور): $authType\n", "Auth type (engine): $authType\n") +
                L("بررسی پروفایل (موتور): $checkStr\n", "Profile check (engine): $checkStr\n") +
                L("ca در پروفایل موتور: ${if (!vp.mCaFilename.isNullOrBlank()) "بله" else "خیر"}\n", "ca in engine profile: ${if (!vp.mCaFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("cert در پروفایل موتور: ${if (!vp.mClientCertFilename.isNullOrBlank()) "بله" else "خیر"}\n", "cert in engine profile: ${if (!vp.mClientCertFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("key در پروفایل موتور: ${if (!vp.mClientKeyFilename.isNullOrBlank()) "بله" else "خیر"}\n", "key in engine profile: ${if (!vp.mClientKeyFilename.isNullOrBlank()) "Yes" else "No"}\n") +
                L("tls-auth در موتور: ${if (vp.mUseTLSAuth) "بله (${vp.mTLSAuthDirection ?: ""})" else "خیر"}\n", "tls-auth in engine: ${if (vp.mUseTLSAuth) "Yes (${vp.mTLSAuthDirection ?: ""})" else "No"}\n")
        } catch (e: Exception) {
            parserInfo = L("خطای تجزیه موتور: ${e.message ?: e.javaClass.simpleName}\n", "Engine parse error: ${e.message ?: e.javaClass.simpleName}\n")
        }
        // Client certificate expiry, from the <cert>
        // block only (date + issuer, no cert content,
        // no private key). A cert the server has
        // revoked/expired is rejected exactly like
        // this log shows, so this date matters.
        var certInfo = ""
        try {
            val block = Regex("<cert>(.*?)</cert>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(text)?.groupValues?.getOrNull(1)
            if (block != null) {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(block.toByteArray())) as java.security.cert.X509Certificate
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                certInfo = L("گواهی کلاینت تا: ${fmt.format(cert.notAfter)}\n", "Client certificate until: ${fmt.format(cert.notAfter)}\n") +
                    L("صادرکننده گواهی کلاینت: ${cert.issuerX500Principal.name.take(80)}\n", "Client certificate issuer: ${cert.issuerX500Principal.name.take(80)}\n")
            } else {
                certInfo = L("بلوک <cert> در فایل نیست.\n", "No <cert> block in file.\n")
            }
        } catch (e: Exception) {
            certInfo = L("خواندن گواهی کلاینت ناموفق: ${e.message ?: ""}\n", "Failed to read client certificate: ${e.message ?: ""}\n")
        }
        val info = buildString {
            append(L("نام: ${p.name}\n", "Name: ${p.name}\n"))
            append(L("سرور: ${p.hostname}\n", "Server: ${p.hostname}\n"))
            append("proto: ${lineOpt("proto")}\n")
            append("remote: ${lineOpt("remote")}\n")
            append("cipher: ${lineOpt("cipher")}\n")
            append("auth: ${lineOpt("auth")}\n")
            append(L("ca دارد: ${if (has("<ca>") || has("(?m)^\\s*ca\\s+")) "بله" else "خیر"}\n", "Has ca: ${if (has("<ca>") || has("(?m)^\\s*ca\\s+")) "Yes" else "No"}\n"))
            append(L("گواهی کلاینت (cert) دارد: ${if (has("<cert>") || has("(?m)^\\s*cert\\s+")) "بله" else "خیر"}\n", "Has client certificate (cert): ${if (has("<cert>") || has("(?m)^\\s*cert\\s+")) "Yes" else "No"}\n"))
            append(L("کلید کلاینت (key) دارد: ${if (has("<key>") || has("(?m)^\\s*key\\s+")) "بله" else "خیر"}\n", "Has client key (key): ${if (has("<key>") || has("(?m)^\\s*key\\s+")) "Yes" else "No"}\n"))
            append(L("tls-auth دارد: ${if (has("tls-auth")) "بله" else "خیر"}\n", "Has tls-auth: ${if (has("tls-auth")) "Yes" else "No"}\n"))
            append(L("tls-crypt دارد: ${if (has("tls-crypt")) "بله" else "خیر"}\n", "Has tls-crypt: ${if (has("tls-crypt")) "Yes" else "No"}\n"))
            append(L("یوزرنیم/پسورد می‌خواهد: ${if (p.needsAuth()) "بله" else "خیر"}\n", "Needs username/password: ${if (p.needsAuth()) "Yes" else "No"}\n"))
            append(L("\n--- تشخیص موتور ---\n", "\n--- Engine diagnosis ---\n"))
            append(parserInfo)
            append(certInfo)
            append(L("\nنتیجه تست قبلی: با گزینه «بدون گواهی» سرور خطای صریح certificate unknown داد، پس سرور حتماً گواهی کلاینت می‌خواهد. آن گزینه را خاموش نگه دار.", "\nPrevious test result: with the “no certificate” option the server gave an explicit certificate unknown error, so the server definitely wants the client certificate. Keep that option off."))
        }
        val tv = TextView(this).apply {
            this.text = info
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        // v1.32: OpenVPN3 uses the .ovpn verbatim.
        // The old 2.x workarounds (no-client-cert,
        // TLS compat/normal) no longer exist and
        // are not shown anymore.
        val note = TextView(this).apply {
            this.text = L("موتور: OpenVPN3 (مثل برنامه رسمی) — فایل .ovpn دقیقاً همان‌طور که هست استفاده می‌شود.", "Engine: OpenVPN3 (like the official app) — the .ovpn file is used verbatim.")
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(32, 0, 32, 16)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv); addView(note)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تشخیص پروفایل", "Profile diagnosis"))
            .setView(col)
            .setPositiveButton(L("بستن", "Close"), null)
            .show()
    }

    private fun editProfile(index: Int) {
        OvpnStore.pendingIndex = index
        OvpnStore.pending = profiles[index].copy()
        startActivity(Intent(this, OpenVpnImportActivity::class.java))
    }

    private fun confirmDelete(index: Int) {
        val p = profiles[index]
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف پروفایل", "Delete profile"))
            .setMessage(L("پروفایل «", "Profile \"") + p.name + L("» حذف شود؟", "\" will be deleted."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                val wasActive = OvpnEngine.activeName == p.name
                profiles.removeAt(index)
                OvpnStore.save(this, profiles)
                if (wasActive) OvpnEngine.disconnect()
                refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun connectProfile(p: OvpnProfile) {
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_openvpn", true).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", p.name).apply() } catch (_: Exception) {}
        // Require BOTH username and password when the
        // profile needs auth: a blank username alone
        // used to let openvpn start, then wait forever
        // for input (NEED state) while the UI just
        // showed CONNECTING.
        if (p.needsAuth() && (p.username.isBlank() || p.password.isBlank()) && !p.hasEmbeddedCredentials()) {
            // Ask for credentials first, then connect.
            val userInput = EditText(this).apply {
                hint = "Username"
                setText(p.username)
            }
            val passInput = EditText(this).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val saveBox = CheckBox(this).apply {
                text = "Save password"; isChecked = true
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                addView(userInput); addView(passInput); addView(saveBox)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(col)
                .setPositiveButton("CONNECT") { _, _ ->
                    p.username = userInput.text.toString().trim()
                    p.password = passInput.text.toString()
                    p.savePassword = saveBox.isChecked
                    if (p.savePassword) OvpnStore.save(this, profiles)
                    OvpnEngine.connect(this, p) { ok ->
                        runOnUiThread { refreshStatus(); refreshList() }
                    }
                }
                .setNegativeButton(L("لغو", "Cancel")) { _, _ -> refreshList() }
                .show()
            return
        }
        OvpnEngine.connect(this, p) { ok ->
            runOnUiThread { refreshStatus(); refreshList() }
        }
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, L("انتخاب فایل OpenVPN", "Choose an OpenVPN file")), 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            OvpnEngine.onPrepareResult(this, resultCode == RESULT_OK)
            refreshStatus()
            refreshList()
            return
        }
        if (requestCode == 700 && resultCode == RESULT_OK) {
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
                val parsed = OvpnStore.parse(text, fileName)
                if (parsed == null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(L("فایل نامعتبر", "Invalid file"))
                        .setMessage(L("این فایل پروفایل OpenVPN نیست؛ باید یک فایل .ovpn با خط remote باشد.", "This is not an OpenVPN profile; it must be an .ovpn file with a remote line."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                    return
                }
                if (profiles.any { it.name == parsed.name && it.hostname == parsed.hostname }) {
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("این پروفایل قبلاً اضافه شده است.", "This profile was already added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                    return
                }
                if (parsed.needsAuth() && !parsed.hasEmbeddedCredentials()) {
                    // Open the Imported Profile page, like
                    // the second screenshot: first two
                    // fields auto-filled, username and
                    // password to be entered.
                    OvpnStore.pendingIndex = -1
                    OvpnStore.pending = parsed
                    startActivity(Intent(this, OpenVpnImportActivity::class.java))
                } else {
                    profiles += parsed
                    OvpnStore.save(this, profiles)
                    refreshList()
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("پروفایل «", "Profile \"") + parsed.name + L("» اضافه شد.", "\" added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show()
                }
            } catch (e: Exception) {
                android.app.AlertDialog.Builder(this)
                    .setMessage(L("خواندن فایل ناموفق: ", "Reading the file failed: ") + (e.message ?: ""))
                    .setPositiveButton(L("بستن", "Close"), null)
                    .show()
            }
        }
    }
}
