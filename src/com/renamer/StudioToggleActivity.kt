package com.renamer

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Invisible toggle behind the section widget's connect button
 *  (2.33). Connects/disconnects WireGuard, OpenVPN or Emergency
 *  WITHOUT opening the app or any section page — the bug where
 *  tapping the widget opened the section.
 *
 *  Permission: when VpnService.prepare returns an intent, this
 *  activity shows the system dialog itself and retries on grant,
 *  so the OpenVPN prepare result is never dropped.
 *  After the action it polls the real state for up to ~10s, then
 *  refreshes all widgets and finishes. A refused action only
 *  toasts; it never opens a section. */
class StudioToggleActivity : Activity() {
    companion object {
        const val EXTRA_SECTION = "studio_section"
        private const val REQ_PREPARE = 701
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pollTask: Runnable? = null
    private var section = ""
    private var pendingOp = ""
    private var connMode = "last"
    // once, so a refused dialog can never loop.
    private var anyPermissionRetried = false

    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)

    private fun setWidgetState(key: String, value: Boolean) {
        try {
            getSharedPreferences("renamer_widget", MODE_PRIVATE).edit()
                .putBoolean("widget_state_$key", value).apply()
        } catch (_: Exception) {}
    }

    private fun lastOvpnProfile(): OvpnProfile? {
        return try {
            val prefs = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
            val last = prefs.getString("ovpn_last", null)?.takeIf { it.isNotBlank() }
            val list = OvpnStore.load(this)
            if (last != null) list.firstOrNull { it.name == last } ?: list.firstOrNull()
            else list.firstOrNull()
        } catch (_: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION) ?: ""
        if (section.isBlank()) { finish(); return }
        // 2.35 connection mode of the tapped widget (default: last).
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        connMode = if (widgetId != -1) WidgetStyle.connModeForWidget(this, widgetId) else "last"
        when (section) {
            "wireguard" -> toggleWireGuard()
            "openvpn" -> toggleOpenVpn()
            "emergency" -> toggleEmergency()
            else -> finish()
        }
    }

    override fun onDestroy() {
        pollTask?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    /** Polls the real state until it flips or ~10s pass, then
     *  refreshes widgets and finishes. */
    private fun pollUntil(done: () -> Boolean, onDone: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000
        val task = object : Runnable {
            override fun run() {
                val ok = try { done() } catch (_: Exception) { false }
                if (ok || System.currentTimeMillis() >= deadline) {
                    try { SectionWidgetProvider.clearConnecting(
                        this@StudioToggleActivity, section) } catch (_: Exception) {}
                    onDone()
                    try { WidgetRefresh.refreshAll(this@StudioToggleActivity) } catch (_: Exception) {}
                    // Insurance for the second speed sample: one
                    // more refresh 4s later, even if this activity
                    // is already finished by then.
                    handler.postDelayed({
                        try { WidgetRefresh.refreshAll(this@StudioToggleActivity) } catch (_: Exception) {}
                    }, 4000)
                    finish()
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        pollTask = task
        handler.post(task)
    }

    private fun finishNow() {
        try { SectionWidgetProvider.clearConnecting(this, section) } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        finish()
    }

    // ---- WireGuard ----
    private fun toggleWireGuard() {
        if (WgDirect.isUp(this)) {
            WgDirect.disconnect(this)
            setWidgetState("wireguard", false)
            pollUntil({ !WgDirect.isUp(this) }) {}
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingOp = "wg"
            startActivityForResult(prepare, REQ_PREPARE)
            return
        }
        connectWireGuard()
    }

    /** "last" = first-or-active (2.33/2.34); "any" = try the saved
     *  tunnels in order until one comes up. */
    private fun connectWireGuard() {
        try { SectionWidgetProvider.setConnecting(this, "wireguard") } catch (_: Exception) {}
        if (connMode == "any") {
            Thread {
                val res = try { WidgetConnect.connectWireGuardAny(this@StudioToggleActivity) }
                    catch (_: Exception) { WidgetConnect.Result.FAILED }
                runOnUiThread {
                    when (res) {
                        WidgetConnect.Result.SUCCESS -> {
                            setWidgetState("wireguard", true)
                            finishNow()
                        }
                        // the system dialog and retry once
                        // after grant, instead of failing.
                        WidgetConnect.Result.NEEDS_PERMISSION -> {
                            if (!anyPermissionRetried) {
                                anyPermissionRetried = true
                                pendingOp = "wg_any"
                                try {
                                                                    } catch (_: Exception) {}
                                val prep = VpnService.prepare(this@StudioToggleActivity)
                                if (prep != null) startActivityForResult(prep, REQ_PREPARE)
                                else connectWireGuard()
                            } else {
                                Toast.makeText(this@StudioToggleActivity,
                                    L("دسترسی VPN داده نشد", "VPN permission was not granted"),
                                    Toast.LENGTH_SHORT).show()
                                finishNow()
                            }
                        }
                        WidgetConnect.Result.FAILED -> {
                            Toast.makeText(this@StudioToggleActivity,
                                L("هیچ کانفیگی وصل نشد", "No config could connect"),
                                Toast.LENGTH_SHORT).show()
                            finishNow()
                        }
                    }
                }
            }.start()
            return
        }
        val ok = try { WgDirect.connectFirstOrActive(this) } catch (_: Exception) { false }
        if (!ok) {
            Toast.makeText(this, L("اول یک تانل وایرگارد اضافه کن",
                "Add a WireGuard tunnel first"), Toast.LENGTH_SHORT).show()
            finishNow()
            return
        }
        pollUntil({ WgDirect.isUp(this) }) {}
    }

    // ---- OpenVPN ----
    private fun toggleOpenVpn() {
        val connected = try { OvpnEngine.isConnected } catch (_: Exception) { false }
        if (connected) {
            OvpnEngine.disconnect()
            setWidgetState("openvpn", false)
            pollUntil({ !OvpnEngine.isConnected }) {}
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingOp = "ovpn"
            startActivityForResult(prepare, REQ_PREPARE)
            return
        }
        connectOvpn()
    }

    private fun connectOvpn() {
        try { SectionWidgetProvider.setConnecting(this, "openvpn") } catch (_: Exception) {}
        if (connMode == "any") {
            Thread {
                val res = try { WidgetConnect.connectOpenVpnAny(this@StudioToggleActivity) }
                    catch (_: Exception) { WidgetConnect.Result.FAILED }
                runOnUiThread {
                    when (res) {
                        WidgetConnect.Result.SUCCESS -> {
                            setWidgetState("openvpn", true)
                            finishNow()
                        }
                        // the system dialog and retry once
                        // after grant, instead of dropping
                        // the result as the old any-path did.
                        WidgetConnect.Result.NEEDS_PERMISSION -> {
                            if (!anyPermissionRetried) {
                                anyPermissionRetried = true
                                pendingOp = "ovpn_any"
                                try {
                                                                    } catch (_: Exception) {}
                                val prep = VpnService.prepare(this@StudioToggleActivity)
                                if (prep != null) startActivityForResult(prep, REQ_PREPARE)
                                else connectOvpn()
                            } else {
                                Toast.makeText(this@StudioToggleActivity,
                                    L("دسترسی VPN داده نشد", "VPN permission was not granted"),
                                    Toast.LENGTH_SHORT).show()
                                finishNow()
                            }
                        }
                        WidgetConnect.Result.FAILED -> {
                            Toast.makeText(this@StudioToggleActivity,
                                L("هیچ کانفیگی وصل نشد", "No config could connect"),
                                Toast.LENGTH_SHORT).show()
                            finishNow()
                        }
                    }
                }
            }.start()
            return
        }
        val profile = lastOvpnProfile()
        if (profile == null) {
            Toast.makeText(this, L("اول یک پروفایل OpenVPN اضافه کن",
                "Add an OpenVPN profile first"), Toast.LENGTH_SHORT).show()
            finishNow()
            return
        }
        try {
            OvpnEngine.connect(this, profile) { _ -> }
        } catch (_: Exception) {
            finishNow()
            return
        }
        pollUntil({ OvpnEngine.isConnected }) {
            if (OvpnEngine.isConnected) setWidgetState("openvpn", true)
        }
    }

    // ---- Emergency ----
    private fun toggleEmergency() {
        val running = try { VpnClientService.isRunning } catch (_: Exception) { false }
        val tag = try { VpnClientService.sessionTag } catch (_: Exception) { null }
        if (running && tag == "emergency") {
            try {
                val stop = android.content.Intent(this, VpnClientService::class.java)
                    .apply { action = "STOP" }
                startService(stop)
            } catch (_: Exception) {}
            pollUntil({ !VpnClientService.isRunning }) {}
            return
        }
        if (running) {
            // Another session owns the tunnel: never kill it.
            Toast.makeText(this, L("اول اتصال فعلی را قطع کن",
                "Disconnect the current connection first"), Toast.LENGTH_SHORT).show()
            finishNow()
            return
        }
        try { SectionWidgetProvider.setConnecting(this, "emergency") } catch (_: Exception) {}
        Thread {
            try { EmergencyConnect.connect(this) } catch (_: Exception) {}
        }.start()
        pollUntil({
            VpnClientService.isRunning && VpnClientService.sessionTag == "emergency"
        }) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PREPARE) return
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, L("دسترسی VPN داده نشد", "VPN permission was not granted"),
                Toast.LENGTH_SHORT).show()
            finishNow()
            return
        }
        when (pendingOp) {
            "wg", "wg_any" -> connectWireGuard()
            "ovpn", "ovpn_any" -> connectOvpn()
            else -> finishNow()
        }
    }
}
