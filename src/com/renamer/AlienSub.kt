package com.renamer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews

/** Shared backend for the Alien Message feature
 *  (sub links + messenger + widget + auto-check).
 *
 *  SECRECY RULE (user order): the raw site address
 *  is NEVER shown to the user anywhere — not in a
 *  TextView, toast, widget, notification or log.
 *  Links are stored raw in private prefs (only the
 *  app itself uses them for HTTP), and every
 *  surface shows SecretLanguage.encode(rawUrl).
 *  The main section's decoder refuses to reveal a
 *  decoded site address (see SecretLanguageActivity).
 */
/** One sender link (2.29): the view link is
 *  what the user shares; the manage link is
 *  background-only and never displayed. */
data class SenderLink(
    var id: String,
    var view: String,
    var manage: String,
    var name: String = "",
    var createdAt: Long = 0L
)

object AlienSub {
    const val PREFS = "renamer_alien_sub"
    const val CHANNEL_ID = "alien_messages"
    const val CHECK_ACTION = "com.renamer.ALIEN_SUB_CHECK"

    // ---- Send method (hidden host choice, 2.31) ----
    // "auto" (default) = mirrors, "primary" = first method only,
    // "backup" = backup method only. Only a choice is ever shown;
    // no address is displayed anywhere.
    fun sendMode(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("send_mode", "auto") ?: "auto"
        } catch (_: Exception) { "auto" }
    }

    fun setSendMode(ctx: Context, mode: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("send_mode", mode).apply()
        } catch (_: Exception) {}
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Link handling (display form is always alien) ----

    /** What the user sees/copies for any stored link. */
    fun displayForm(rawUrl: String): String {
        return try { SecretLanguage.encode(rawUrl) } catch (_: Exception) { "" }
    }

    /** Turn user input (alien form) into the raw URL
     *  for internal use. Returns null when the input
     *  is not one of our links. The raw result is
     *  NEVER shown in UI — callers store it only. */
    fun parseInput(input: String): String? {
        return try {
            val decoded = SecretLanguage.decode(input.trim())
            val parts = ShzClient.splitBundle(decoded)
            if (parts.isNotEmpty() && parts.all { ShzClient.isSiteLink(it) }) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    /** True when a decoded text would reveal the
     *  site — the main decoder must hide it. */
    fun revealsSite(decoded: String): Boolean {
        return ShzClient.containsHost(decoded)
    }

    // ---- Shared small UI (same glass style as
    // the Alien section, kept simple) ----

    fun miniButton(ctx: Context, label: String): android.widget.Button {
        val d = ctx.resources.displayMetrics.density
        return android.widget.Button(ctx).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = HydraUi.glassPill(
                Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, ctx)
        }
    }

    fun flatPill(ctx: Context, fill: Int): android.graphics.drawable.GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * d
            setColor(fill)
        }
    }

    // ---- Stored state ----

    fun senderView(ctx: Context) = activeSenderLink(ctx)?.view
    fun senderManage(ctx: Context) = activeSenderLink(ctx)?.manage
    fun receiverView(ctx: Context) = prefs(ctx).getString("receiver_view", null)
    fun autoMinutes(ctx: Context) = prefs(ctx).getInt("auto_minutes", 0)
    fun lastContent(ctx: Context) = prefs(ctx).getString("receiver_last", null)
    fun lastMessage(ctx: Context) = prefs(ctx).getString("receiver_last_msg", null)

    // ---- Sender links history (2.29) ----
    // The manage link is NEVER shown to the
    // user anywhere; it lives only here, in
    // the background. What the user sees is
    // always the share link (the view link).
    fun senderLinks(ctx: Context): MutableList<SenderLink> {
        val out = mutableListOf<SenderLink>()
        try {
            val arr = org.json.JSONArray(prefs(ctx).getString("sender_links", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += SenderLink(
                    id = o.optString("id"),
                    view = o.optString("view"),
                    manage = o.optString("manage"),
                    name = o.optString("name"),
                    createdAt = o.optLong("createdAt"))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveSenderLinks(ctx: Context, list: List<SenderLink>) {
        try {
            val arr = org.json.JSONArray()
            for (e in list) {
                val o = org.json.JSONObject()
                o.put("id", e.id)
                o.put("view", e.view)
                o.put("manage", e.manage)
                o.put("name", e.name)
                o.put("createdAt", e.createdAt)
                arr.put(o)
            }
            prefs(ctx).edit().putString("sender_links", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun activeSenderLink(ctx: Context): SenderLink? {
        val list = senderLinks(ctx)
        if (list.isEmpty()) {
            // One-time import of the old single
            // link; the old keys are then gone.
            val v = prefs(ctx).getString("sender_view", null)
            val m = prefs(ctx).getString("sender_manage", null)
            if (v != null && m != null) {
                val e = SenderLink("s" + System.currentTimeMillis(), v, m, "",
                    System.currentTimeMillis())
                saveSenderLinks(ctx, mutableListOf(e))
                prefs(ctx).edit()
                    .putString("sender_active", e.id)
                    .remove("sender_view").remove("sender_manage").apply()
                return e
            }
            return null
        }
        val activeId = prefs(ctx).getString("sender_active", null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActiveSenderLink(ctx: Context, id: String) {
        prefs(ctx).edit().putString("sender_active", id).apply()
    }

    /** 2.44: bulk delete for the history page.
     *  Removes the local records; when the active
     *  link is among them, the first remaining
     *  link becomes active (or none is active). */
    fun deleteSenderLinks(ctx: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val list = senderLinks(ctx)
        val kept = list.filter { it.id !in ids }
        saveSenderLinks(ctx, kept)
        val activeId = prefs(ctx).getString("sender_active", null)
        if (activeId != null && activeId in ids) {
            if (kept.isNotEmpty()) prefs(ctx).edit().putString("sender_active", kept.first().id).apply()
            else prefs(ctx).edit().remove("sender_active").apply()
        }
    }

    fun addSenderLink(ctx: Context, view: String, manage: String): SenderLink {
        val list = senderLinks(ctx)
        val e = SenderLink("s" + System.currentTimeMillis(), view, manage, "",
            System.currentTimeMillis())
        list.add(0, e)
        saveSenderLinks(ctx, list)
        setActiveSenderLink(ctx, e.id)
        return e
    }

    fun renameSenderLink(ctx: Context, id: String, name: String) {
        val list = senderLinks(ctx)
        val e = list.firstOrNull { it.id == id } ?: return
        e.name = name
        saveSenderLinks(ctx, list)
    }

    fun setSender(ctx: Context, view: String, manage: String) {
        val list = senderLinks(ctx)
        val existing = list.firstOrNull { it.view == view }
        if (existing != null) setActiveSenderLink(ctx, existing.id)
        else addSenderLink(ctx, view, manage)
    }

    fun setReceiver(ctx: Context, view: String) {
        prefs(ctx).edit().putString("receiver_view", view).apply()
    }

    fun clearReceiver(ctx: Context) {
        prefs(ctx).edit().remove("receiver_view").remove("receiver_last").remove("receiver_last_msg").apply()
    }

    fun setAutoMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt("auto_minutes", minutes).apply()
        if (minutes > 0) scheduleCheck(ctx, minutes) else cancelCheck(ctx)
    }

    fun setLast(ctx: Context, content: String, decodedMessage: String) {
        prefs(ctx).edit().putString("receiver_last", content).putString("receiver_last_msg", decodedMessage).apply()
    }

    // ---- Auto-check alarm ----

    private fun checkIntent(ctx: Context): PendingIntent {
        val it = Intent(ctx, AlienSubCheckReceiver::class.java).apply { action = CHECK_ACTION }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(ctx, 9001, it, flags)
    }

    fun scheduleCheck(ctx: Context, minutes: Int) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + minutes * 60L * 1000L
            val pi = checkIntent(ctx)
            try {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {}
    }

    fun cancelCheck(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(checkIntent(ctx))
        } catch (_: Exception) {}
    }

    fun rearmIfNeeded(ctx: Context) {
        val m = autoMinutes(ctx)
        if (m > 0 && receiverView(ctx) != null) scheduleCheck(ctx, m)
    }

    // ---- Notification (new message) ----

    fun notifyNewMessage(ctx: Context, message: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    AppLanguage.t(ctx, "پیام بیگانه", "Alien Message"),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = AppLanguage.t(ctx,
                        "پیام‌های تازه لینک پیام بیگانه",
                        "New messages from your alien message link")
                }
                try { nm.createNotificationChannel(ch) } catch (_: Exception) {}
            }
            val open = Intent(ctx, AlienSubActivity::class.java).apply {
                putExtra("mode", "receiver")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(ctx, 9002, open, flags)
            val shown = if (message.length > 120) message.substring(0, 120) + "…" else message
            val nb = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(AppLanguage.t(ctx, "پیام جدید دارید", "You have a new message"))
                .setContentText(shown)
                .setStyle(Notification.BigTextStyle().bigText(shown))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            nm.notify(9003, nb.build())
        } catch (_: Exception) {}
    }

    // ---- Widget refresh ----

    fun refreshWidget(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AlienWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val msg = lastMessage(ctx) ?: ""
            val views = RemoteViews(ctx.packageName, R.layout.widget_alien)
            // 2.32: Studio style for the receiver widget too.
            try {
                val style = WidgetStyle.styleForSection(ctx, "message")
                views.setInt(R.id.widget_alien_root, "setBackgroundResource",
                    WidgetStyle.bgRes(style))
                if (WidgetStyle.isDark(style))
                    views.setTextColor(R.id.widget_alien_title, -1)
            } catch (_: Exception) {}
            views.setTextViewText(
                R.id.widget_alien_title,
                AppLanguage.t(ctx, "پیام بیگانه", "Alien Message")
            )
            views.setTextViewText(
                R.id.widget_alien_text,
                if (msg.isBlank()) AppLanguage.t(ctx, "هنوز پیامی نیامده است", "No message yet") else msg
            )
            val open = Intent(ctx, AlienSubActivity::class.java).apply {
                putExtra("mode", "receiver")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            views.setOnClickPendingIntent(
                R.id.widget_alien_root,
                PendingIntent.getActivity(ctx, 9004, open, flags)
            )
            for (id in ids) {
                try { mgr.updateAppWidget(id, views) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
