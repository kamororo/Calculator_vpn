package com.renamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restart Smart Connect after a reboot or an app
 *  update, but ONLY if the user left it enabled with
 *  at least one app selected. Without this, smart
 *  connect silently never runs again after reboot —
 *  which is why it used to look like it only worked
 *  while the app was open. */
class SmartBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val prefs = ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("smart_enabled", false)) return
            val apps = prefs.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (apps.isEmpty()) return
            val svc = Intent(ctx, SmartConnectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Exception) {}
    }
}
