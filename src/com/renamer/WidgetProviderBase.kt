package com.renamer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Base for the three home-screen widgets (v2ray / WireGuard / OpenVPN).
 *
 * Size model (honest version of the user's 1x1 / 1x2 / 1x3 / 2x2 ask):
 * there is ONE provider per section, with resizeMode horizontal|vertical.
 * The user long-presses the widget and resizes it; onAppWidgetOptionsChanged
 * then re-picks the layout from the widget's real size:
 *   minWidth < 110dp -> widget_small (1x1: button only)
 *   minWidth < 200dp -> widget_medium (button + name + status)
 *   otherwise        -> widget_large (button + name + status + restart)
 * So 1x2 / 1x3 / 2x2 are all reachable by resizing, not four fixed providers.
 *
 * State:
 * - v2ray: live state from VpnClientService.isRunning.
 * - WireGuard / OpenVPN: there is no live flag the widget can read from
 *   their engines, so the widget reads prefs "renamer_widget" key
 *   "widget_state_<sectionKey>" (boolean). The parent must update that
 *   flag from WireGuardActivity / OpenVpnActivity when their connection
 *   state changes; until then those widgets always show "disconnected".
 *
 * Connect behaviour (honest limits):
 * - v2ray, VPN permission already granted once (VpnService.prepare == null):
 *   the widget starts/stops VpnClientService directly with the selected
 *   config (prefs "renamer_vpn": "selected" + "servers", the same keys
 *   VpnActivity uses). If no config is selected, it opens VpnActivity.
 * - v2ray, permission NOT granted yet, and always for WireGuard/OpenVPN
 *   (their engines need the activity permission flow): the widget opens
 *   the section activity with extra "widget_auto_connect"=true, so the
 *   parent-wired activity can auto-connect after permission is granted.
 *   The activity side of that extra is NOT implemented here (parent work).
 * - Starting a service from a broadcast receiver can be blocked by
 *   Android's background-service rules; on any such failure the widget
 *   falls back to opening the section activity instead of crashing.
 */
/** Refresh every section's widgets (used when a VPN
 *  connects/disconnects from inside the app, so the
 *  widget turns green/red on its own). */
object WidgetRefresh {
    fun refreshAll(ctx: Context) {
        try { VpnWidgetProvider().updateAllWidgets(ctx) } catch (_: Exception) {}
        try { WgWidgetProvider().updateAllWidgets(ctx) } catch (_: Exception) {}
        try { OvpnWidgetProvider().updateAllWidgets(ctx) } catch (_: Exception) {}
    }
}

abstract class WidgetProviderBase : AppWidgetProvider() {

    abstract fun sectionKey(): String
    abstract fun sectionInitial(): String
    abstract fun sectionDisplayName(): String
    abstract fun targetActivity(): Class<*>
    abstract fun iconRes(): Int

    private fun widgetSetting(ctx: Context, key: String, def: String): String {
        return try {
            ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .getString(key + "_" + sectionKey(), def) ?: def
        } catch (_: Exception) { def }
    }

    private fun offCircleRes(ctx: Context): Int {
        return when (widgetSetting(ctx, "widget_color", "blue")) {
            "pink" -> R.drawable.widget_circle_off_pink
            "lavender" -> R.drawable.widget_circle_off_lavender
            "orange" -> R.drawable.widget_circle_off_orange
            "mint" -> R.drawable.widget_circle_off_mint
            else -> R.drawable.widget_circle_off
        }
    }

    private fun L(ctx: Context, fa: String, en: String): String = AppLanguage.t(ctx, fa, en)

    /** Live connected state for this section. */
    open fun isConnected(ctx: Context): Boolean {
        return if (sectionKey() == "v2ray") {
            // The v2ray widget is "on" only for a
            // v2ray session (manual, widget, smart),
            // never for another section's session.
            VpnClientService.isRunning &&
                VpnClientService.sessionTag in arrayOf("v2ray", "smart", null)
        } else {
            ctx.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .getBoolean("widget_state_" + sectionKey(), false)
        }
    }

    private fun widgetPrefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)

    private fun togglePendingIntent(ctx: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(ctx, WidgetToggleActivity::class.java).apply {
            putExtra(EXTRA_SECTION, sectionKey())
            putExtra(EXTRA_ACTION, ACTION_TOGGLE)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            ctx, appWidgetId * 10 + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun restartPendingIntent(ctx: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(ctx, WidgetToggleActivity::class.java).apply {
            putExtra(EXTRA_SECTION, sectionKey())
            putExtra(EXTRA_ACTION, ACTION_RESTART)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            ctx, appWidgetId * 10 + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun updateWidget(ctx: Context, appWidgetId: Int) {
        val manager = AppWidgetManager.getInstance(ctx)
        val options: Bundle = try {
            manager.getAppWidgetOptions(appWidgetId)
        } catch (_: Exception) {
            Bundle()
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 70)
        val layout = when (widgetSetting(ctx, "widget_size", "auto")) {
            "1x1" -> R.layout.widget_small
            "1x2", "1x3" -> R.layout.widget_medium
            "2x2" -> R.layout.widget_large
            else -> when {
                minWidth < 110 -> R.layout.widget_small
                minWidth < 200 -> R.layout.widget_medium
                else -> R.layout.widget_large
            }
        }
        val connected = try { isConnected(ctx) } catch (_: Exception) { false }
        val views = RemoteViews(ctx.packageName, layout)
        views.setInt(
            R.id.widget_btn, "setBackgroundResource",
            if (connected) R.drawable.widget_circle_on else offCircleRes(ctx)
        )
        if (widgetSetting(ctx, "widget_icon", "icon") == "letter") {
            views.setTextViewText(R.id.widget_btn, sectionInitial())
            views.setViewVisibility(R.id.widget_icon, android.view.View.GONE)
        } else {
            views.setTextViewText(R.id.widget_btn, "")
            views.setImageViewResource(R.id.widget_icon, iconRes())
            views.setViewVisibility(R.id.widget_icon, android.view.View.VISIBLE)
        }
        val toggle = togglePendingIntent(ctx, appWidgetId)
        views.setOnClickPendingIntent(R.id.widget_btn, toggle)
        views.setOnClickPendingIntent(R.id.widget_icon, toggle)
        if (layout != R.layout.widget_small) {
            views.setTextViewText(R.id.widget_name, sectionDisplayName())
            views.setTextViewText(
                R.id.widget_status,
                if (connected) L(ctx, "وصل است", "Connected")
                else L(ctx, "قطع است", "Disconnected")
            )
        }
        if (layout == R.layout.widget_large) {
            views.setOnClickPendingIntent(R.id.widget_restart, restartPendingIntent(ctx, appWidgetId))
        }
        try {
            manager.updateAppWidget(appWidgetId, views)
        } catch (_: Exception) {
        }
    }

    fun updateAllWidgets(ctx: Context) {
        try {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, javaClass))
            for (id in ids) updateWidget(ctx, id)
        } catch (_: Exception) {
        }
    }

    override fun onUpdate(
        ctx: Context, manager: AppWidgetManager, appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) updateWidget(ctx, id)
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        updateWidget(ctx, appWidgetId)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (intent.getStringExtra(EXTRA_SECTION) == sectionKey()) {
                    onToggle(ctx)
                    updateAllWidgets(ctx)
                }
                return
            }
            ACTION_RESTART -> {
                if (intent.getStringExtra(EXTRA_SECTION) == sectionKey()) {
                    onRestart(ctx)
                    updateAllWidgets(ctx)
                }
                return
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun openSectionActivity(ctx: Context) {
        try {
            val intent = Intent(ctx, targetActivity()).apply {
                putExtra("widget_auto_connect", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun stopVpn(ctx: Context) {
        try {
            val stop = Intent(ctx, VpnClientService::class.java).apply { action = "STOP" }
            ctx.startService(stop)
        } catch (_: Exception) {
        }
        try { ctx.stopService(Intent(ctx, VpnClientService::class.java)) } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        VpnClientService.sessionTag = null
    }

    /** Start the v2ray VPN with the selected config. Returns false when
     *  there is nothing to connect to (no selected config, or the config
     *  cannot be built), so the caller can open the activity instead. */
    private fun startVpnSelected(ctx: Context): Boolean {
        try { ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        return try {
            val prefs = widgetPrefs(ctx)
            val selected = prefs.getString("selected", null)?.takeIf { it.isNotBlank() }
                ?: return false
            val servers = prefs.getString("servers", "")?.lines()
                ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            if (servers.isNotEmpty() && !servers.contains(selected)) return false
            if (!CoreInit.init(ctx.applicationContext)) return false
            val vpnConfig = VpnConfigBuilder.build(ctx, selected) ?: return false
            VpnClientService.emergencyConfig = null
            val intent = Intent(ctx, VpnClientService::class.java).apply {
                putExtra("config", vpnConfig)
                putExtra("name", XraySpeedtest.displayName(selected))
                putExtra("link", selected)
                putExtra("session_tag", "v2ray")
            }
            ctx.startService(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun onToggle(ctx: Context) {
        if (sectionKey() == "wireguard") {
            if (WgDirect.isUp(ctx)) { WgDirect.disconnect(ctx); return }
            if (!WgDirect.connectActive(ctx)) openSectionActivity(ctx)
            return
        }
        if (sectionKey() == "openvpn") {
            if (OvpnEngine.isConnected) { OvpnEngine.disconnect(); WidgetRefresh.refreshAll(ctx); return }
            try {
                val last = lastOvpnProfile(ctx)
                if (last != null && ctx is android.app.Activity) {
                    OvpnEngine.connect(ctx, last) { WidgetRefresh.refreshAll(ctx) }
                    return
                }
            } catch (_: Exception) {}
            openSectionActivity(ctx)
            return
        }
        if (sectionKey() != "v2ray") {
            openSectionActivity(ctx)
            return
        }
        // Another section's session is up: the
        // widget must not kill it, and must not
        // start over it either — just say so.
        if (VpnClientService.isRunning &&
            VpnClientService.sessionTag in arrayOf("emergency", "hydra", "chain")) {
            try {
                android.widget.Toast.makeText(ctx,
                    AppLanguage.t(ctx,
                        "اتصال «${VpnClientService.sectionLabel(ctx, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.",
                        "A «${VpnClientService.sectionLabel(ctx, VpnClientService.sessionTag)}» connection is active; stop it first."),
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        if (isConnected(ctx)) {
            stopVpn(ctx)
            return
        }
        // Not connected: direct start only when the VPN permission was
        // already granted once (prepare returns null). Otherwise the
        // permission dialog must come from an activity.
        val needsPermission = try {
            VpnService.prepare(ctx) != null
        } catch (_: Exception) {
            true
        }
        if (needsPermission) {
            openSectionActivity(ctx)
            return
        }
        if (!startVpnSelected(ctx)) {
            // No selected config (or start blocked in background):
            // fall back to the activity instead of doing nothing.
            openSectionActivity(ctx)
        }
    }

    fun onRestart(ctx: Context) {
        if (sectionKey() != "v2ray") {
            // Restart is only meaningful for v2ray here; for the other
            // sections it behaves like a toggle (open the activity).
            openSectionActivity(ctx)
            return
        }
        stopVpn(ctx)
        // Best-effort restart: stop, then start again immediately in
        // the same receiver call. A true 800ms delayed restart would
        // need an AlarmManager round-trip; the immediate restart keeps
        // the widget self-contained and is documented as best-effort.
        val needsPermission = try {
            VpnService.prepare(ctx) != null
        } catch (_: Exception) {
            true
        }
        if (needsPermission || !startVpnSelected(ctx)) {
            openSectionActivity(ctx)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.renamer.WIDGET_TOGGLE"
        const val ACTION_RESTART = "com.renamer.WIDGET_RESTART"
        const val EXTRA_SECTION = "widget_section"
        const val EXTRA_ACTION = "widget_action"
        const val WIDGET_PREFS = "renamer_widget"

        fun providerFor(section: String): WidgetProviderBase? = when (section) {
            "v2ray" -> VpnWidgetProvider()
            "wireguard" -> WgWidgetProvider()
            "openvpn" -> OvpnWidgetProvider()
            else -> null
        }
    }

    private fun lastOvpnProfile(ctx: Context): OvpnProfile? {
        return try {
            val prefs = ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
            val last = prefs.getString("ovpn_last", null)?.takeIf { it.isNotBlank() }
            val arr = org.json.JSONArray(prefs.getString("ovpn_list", "[]") ?: "[]")
            var first: OvpnProfile? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val prof = OvpnProfile(
                    name = o.optString("name"),
                    hostname = o.optString("hostname"),
                    ovpn = o.optString("ovpn"),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    savePassword = o.optBoolean("savePassword", false)
                )
                if (first == null) first = prof
                if (prof.name == last) return prof
            }
            first
        } catch (_: Exception) { null }
    }
}
