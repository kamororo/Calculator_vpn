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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(WidgetProviderBase.EXTRA_SECTION) ?: ""
        val action = intent.getStringExtra(WidgetProviderBase.EXTRA_ACTION)
            ?: WidgetProviderBase.ACTION_TOGGLE
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
    }
}
