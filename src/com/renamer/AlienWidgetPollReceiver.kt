package com.renamer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Near-instant chat-widget delivery (2.33). True push is impossible
 *  without a push server (the links are plain pastebins), so while
 *  at least one chat widget exists an exact alarm checks every
 *  60s — the fastest reasonable cadence — and on a new partner or
 *  received message it refreshes the widgets and posts a
 *  notification whose tap opens the mini-chat for that chat.
 *  Android Doze may still delay checks while the phone sleeps
 *  deeply; tapping a widget always pulls the latest first. */
object AlienWidgetPoll {
    const val ACTION = "com.renamer.ALIEN_WIDGET_POLL"
    private const val REQ = 9011
    private const val INTERVAL = 60_000L

    fun hasWidgets(ctx: Context): Boolean = try {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, AlienChatWidgetProvider::class.java)).isNotEmpty()
    } catch (_: Exception) { false }

    private fun pending(ctx: Context, flags: Int): PendingIntent? = try {
        PendingIntent.getBroadcast(ctx, REQ,
            Intent(ctx, AlienWidgetPollReceiver::class.java).apply { action = ACTION },
            flags or PendingIntent.FLAG_IMMUTABLE)
    } catch (_: Exception) { null }

    fun schedule(ctx: Context) {
        if (!hasWidgets(ctx)) return
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            val at = System.currentTimeMillis() + INTERVAL
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx, PendingIntent.FLAG_NO_CREATE) ?: return
            am.cancel(pi)
            pi.cancel()
        } catch (_: Exception) {}
    }
}

class AlienWidgetPollReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlienWidgetPoll.schedule(ctx)
            return
        }
        if (!AlienWidgetPoll.hasWidgets(ctx)) {
            AlienWidgetPoll.cancel(ctx)
            return
        }
        AlienWidgetPoll.schedule(ctx)
        Thread { checkAll(ctx) }.start()
    }

    private fun checkAll(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(
                ComponentName(ctx, AlienChatWidgetProvider::class.java))
            val prefs = ctx.getSharedPreferences(
                WidgetStyle.PREFS, Context.MODE_PRIVATE)
            var anyNew = false
            var didReceiver = false
            val chatIds = mutableSetOf<String>()
            for (id in ids) {
                val mode = WidgetStyle.chatModeForWidget(ctx, id)
                if (mode == "receiver") {
                    if (didReceiver) continue
                    didReceiver = true
                    try {
                        val view = AlienSub.receiverView(ctx) ?: continue
                        val parsed = AlienChats.parseSub(ShzClient.fetchAny(view))
                        val maxTs = parsed.messages.maxOfOrNull { it.first } ?: continue
                        val last = prefs.getLong("poll_last_receiver", 0L)
                        if (maxTs > last) {
                            prefs.edit().putLong("poll_last_receiver", maxTs).apply()
                            val text = parsed.messages
                                .firstOrNull { it.first == maxTs }?.second
                                ?.let { AlienChats.parseReply(it).second } ?: ""
                            notify(ctx, 9100, null,
                                AppLanguage.t(ctx, "پیام بیگانه", "Alien Message"),
                                text)
                            anyNew = true
                        }
                    } catch (_: Exception) {}
                } else {
                    chatIds += WidgetStyle.chatForWidget(ctx, id)
                }
            }
            for (chatId in chatIds) {
                if (chatId.isBlank()) continue
                try {
                    val c = AlienChats.find(ctx, chatId) ?: continue
                    if (c.blockedByMe || c.blockedMe) continue
                    val partner = AlienChats.parseSub(ShzClient.fetchAny(c.partnerView))
                    val fresh = partner.messages.filter { it.first > c.myClearTs }
                    val maxTs = fresh.maxOfOrNull { it.first } ?: continue
                    val last = prefs.getLong("poll_last_$chatId", 0L)
                    if (maxTs > last) {
                        prefs.edit().putLong("poll_last_$chatId", maxTs).apply()
                        val text = fresh.firstOrNull { it.first == maxTs }?.second
                            // 2.64: decrypt at display time (E2E).
                            ?.let { AlienE2e.displayStored(ctx, chatId, it, mine = false) } ?: ""
                        notify(ctx, chatId.hashCode(), chatId,
                            c.partnerName.ifBlank {
                                AppLanguage.t(ctx, "گفتگو", "Chat")
                            }, text)
                        anyNew = true
                    }
                } catch (_: Exception) {}
            }
            if (anyNew) {
                try { AlienChatWidgetProvider.refreshAll(ctx) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun notify(ctx: Context, id: Int, chatId: String?, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(AlienSub.CHANNEL_ID,
                    AppLanguage.t(ctx, "پیام بیگانه", "Alien Message"),
                    NotificationManager.IMPORTANCE_DEFAULT)
                try { nm.createNotificationChannel(ch) } catch (_: Exception) {}
            }
            val open = Intent(ctx, AlienQuickReplyActivity::class.java).apply {
                if (chatId != null) {
                    putExtra("mode", "chat")
                    putExtra("chat_id", chatId)
                } else {
                    putExtra("mode", "receiver")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, AlienSub.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(if (text.length > 80) text.substring(0, 80) else text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (_: Exception) {}
    }
}
