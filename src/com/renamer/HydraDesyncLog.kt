package com.renamer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TEST-ONLY Hydra desync log. Deliberately a
 *  separate file + a separate button in
 *  HydraActivity, used ONLY by Hydra's desync
 *  paths — so the whole test log can be removed
 *  later by deleting this file + that button.
 *  Nothing else in the app reads it, and it
 *  never affects any other section. */
object HydraDesyncLog {
    private const val PREFS = "hydra_desync_log"
    private const val MAX = 200

    fun log(ctx: Context, fa: String, en: String) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val line = "$stamp ${AppLanguage.t(ctx, fa, en)}"
            val old = p.getString("lines", "") ?: ""
            val lines = (old.lines() + line).filter { it.isNotBlank() }.takeLast(MAX)
            p.edit().putString("lines", lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lines", "") ?: ""
        } catch (_: Exception) { "" }
    }

    fun showDialog(ctx: Context) {
        val body = read(ctx).ifBlank {
            AppLanguage.t(ctx, "هنوز رویدادی ثبت نشده است.", "No events recorded yet.")
        }
        val scroll = android.widget.ScrollView(ctx)
        val tv = android.widget.TextView(ctx).apply {
            text = body
            textSize = 13f
            setPadding(32, 24, 32, 24)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(ctx)
            .setTitle(AppLanguage.t(ctx, "گزارش دی‌سینک هیدرا (آزمایشی)", "Hydra desync report (test)"))
            .setView(scroll)
            .setPositiveButton(AppLanguage.t(ctx, "باشه", "OK"), null)
            .setNeutralButton(AppLanguage.t(ctx, "کپی", "Copy")) { _, _ ->
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("hydra desync log", read(ctx)))
                } catch (_: Exception) {}
            }
            .setNegativeButton(AppLanguage.t(ctx, "پاک کردن", "Clear")) { _, _ ->
                try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lines", "").apply() } catch (_: Exception) {}
            }
            .show()
    }
}
