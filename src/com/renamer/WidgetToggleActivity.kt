package com.renamer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Transparent activity launched by a widget tap.
 * Widget taps are user gestures, so starting THIS
 * activity always works — unlike starting a VPN
 * service straight from a broadcast receiver, which
 * Android blocks in the background (that is why
 * widget taps used to do nothing). It performs the
 * toggle (or restart) for the section, refreshes all
 * widgets, and finishes immediately; nothing is shown.
 *
 * For v2ray it stops/starts VpnClientService directly
 * when the VPN permission was already granted once.
 * For WireGuard it uses WgDirect (same GoBackend).
 * For OpenVPN it uses OvpnEngine.connect, which needs
 * an Activity for prepare — and this IS one.
 */
class WidgetToggleActivity : Activity() {
    // only once, so a refused dialog can never loop.
    private var v2rayAnyRetried = false
    private var pendingV2rayAny = false
    private val reqPrepareV2ray = 702

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(WidgetProviderBase.EXTRA_SECTION) ?: ""
        val action = intent.getStringExtra(WidgetProviderBase.EXTRA_ACTION)
            ?: WidgetProviderBase.ACTION_TOGGLE
        // 2.35: v2ray widgets with connection mode "any" try the
        // saved configs in order until one connects.
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val connMode = if (widgetId != -1) WidgetStyle.connModeForWidget(this, widgetId) else "last"
        if (section == "v2ray" && connMode == "any" &&
            action != WidgetProviderBase.ACTION_RESTART) {
            if (toggleV2rayAny()) return
        }
        if (section == "v2ray" && action == WidgetProviderBase.ACTION_TOGGLE &&
            !VpnClientService.isRunning) {
            try { SectionWidgetProvider.setConnecting(this, "v2ray") } catch (_: Exception) {}
        }
        try {
            val provider = WidgetProviderBase.providerFor(section)
            if (provider != null) {
                if (action == WidgetProviderBase.ACTION_RESTART) provider.onRestart(this)
                else provider.onToggle(this)
            }
        } catch (_: Exception) {}
        // Give the toggle a beat to take effect, then
        // refresh the widgets (green when connected) and close.
        Handler(Looper.getMainLooper()).postDelayed({
            try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
            finish()
        }, 600)
        // Insurance for the second speed sample.
        Handler(Looper.getMainLooper()).postDelayed({
            try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        }, 4000)
    }

    /** v2ray "any" toggle. Returns true when it handled the tap
     *  (the activity finishes from the worker callback); false
     *  means "fall through to the normal toggle". Mirrors the
     *  guards of the legacy onToggle: another section's session
     *  is never killed, and a missing VPN permission opens the
     *  v2ray section instead of failing silently. */
    private fun toggleV2rayAny(): Boolean {
        try {
            val running = VpnClientService.isRunning
            val tag = VpnClientService.sessionTag
            if (running && tag in arrayOf("emergency", "hydra", "chain")) {
                try {
                    android.widget.Toast.makeText(this,
                        AppLanguage.t(this,
                            "اتصال «${VpnClientService.sectionLabel(this, tag)}» فعال است؛ اول آن را قطع کن.",
                            "A «${VpnClientService.sectionLabel(this, tag)}» connection is active; stop it first."),
                        android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).postDelayed({
                    try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
                    finish()
                }, 600)
                return true
            }
            if (running && tag in arrayOf("v2ray", "smart", null)) {
                try {
                    val stop = android.content.Intent(this, VpnClientService::class.java).apply {
                        action = "STOP"
                        putExtra("stop_gen", VpnClientService.sessionGen)
                    }
                    startService(stop)
                } catch (_: Exception) {}
                VpnClientService.isRunning = false
                VpnClientService.currentName = null
                VpnClientService.sessionTag = null
                Handler(Looper.getMainLooper()).postDelayed({
                    try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
                    finish()
                }, 600)
                return true
            }
            if (android.net.VpnService.prepare(this) != null) {
                startActivity(android.content.Intent(this, VpnActivity::class.java))
                finish()
                return true
            }
            try { SectionWidgetProvider.setConnecting(this, "v2ray") } catch (_: Exception) {}
            Thread {
                val res = try { WidgetConnect.connectV2rayAny(this@WidgetToggleActivity) }
                    catch (_: Exception) { WidgetConnect.Result.FAILED }
                runOnUiThread {
                    try { SectionWidgetProvider.clearConnecting(
                        this@WidgetToggleActivity, "v2ray") } catch (_: Exception) {}
                    when (res) {
                        WidgetConnect.Result.SUCCESS -> {
                            try { WidgetRefresh.refreshAll(this@WidgetToggleActivity) } catch (_: Exception) {}
                            finish()
                        }
                        // was revoked mid-flight): show the
                        // system dialog and retry once after
                        // grant, instead of failing.
                        WidgetConnect.Result.NEEDS_PERMISSION -> {
                            if (!v2rayAnyRetried) {
                                v2rayAnyRetried = true
                                pendingV2rayAny = true
                                try {
                                                                    } catch (_: Exception) {}
                                val prep = android.net.VpnService.prepare(this@WidgetToggleActivity)
                                if (prep != null) startActivityForResult(prep, reqPrepareV2ray)
                                else toggleV2rayAny()
                            } else {
                                try {
                                    android.widget.Toast.makeText(this@WidgetToggleActivity,
                                        AppLanguage.t(this@WidgetToggleActivity,
                                            "دسترسی VPN داده نشد", "VPN permission was not granted"),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                                try { WidgetRefresh.refreshAll(this@WidgetToggleActivity) } catch (_: Exception) {}
                                finish()
                            }
                        }
                        WidgetConnect.Result.FAILED -> {
                            try {
                                android.widget.Toast.makeText(this@WidgetToggleActivity,
                                    AppLanguage.t(this@WidgetToggleActivity,
                                        "هیچ کانفیگی وصل نشد", "No config could connect"),
                                    android.widget.Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                            try { WidgetRefresh.refreshAll(this@WidgetToggleActivity) } catch (_: Exception) {}
                            finish()
                        }
                    }
                }
            }.start()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPrepareV2ray || !pendingV2rayAny) return
        pendingV2rayAny = false
        if (resultCode == RESULT_OK) {
            toggleV2rayAny()
        } else {
            try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
            finish()
        }
    }
}
