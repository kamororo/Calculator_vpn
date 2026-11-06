package com.renamer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

/** Chat widgets (2.32). Two modes, chosen in Widget Studio:
 *  - "receiver": the received Alien Messages, tap opens the receiver
 *    page. No reply button: a receiver only reads.
 *  - "chat": a two-way conversation (merged own + partner messages),
 *    tap opens the chat, and the reply button opens a small reply
 *    window — stock Android widgets cannot host a text field, so
 *    typing happens in that window, not inside the widget itself.
 *  Messages are cached per widget, so an offline refresh never
 *  blanks the widget. */
class AlienChatWidgetProvider : AppWidgetProvider() {

    companion object {

        /** Pure fetch, no cache: null on failure, a list (possibly
         *  empty) on success. The factory and the Studio preview both
         *  read through here, so the widget and its preview agree. */
        fun loadRowsFor(ctx: Context, mode: String, chatId: String): List<Triple<Long, Boolean, String>>? {
            try {
                val out = mutableListOf<Triple<Long, Boolean, String>>()
                if (mode == "receiver") {
                    val view = AlienSub.receiverView(ctx) ?: return null
                    val parsed = AlienChats.parseSub(ShzClient.fetchAny(view))
                    for ((ts, second) in parsed.messages)
                        out += Triple(ts, false, AlienChats.parseReply(second).second)
                } else {
                    val c = AlienChats.find(ctx, chatId) ?: return null
                    if (c.blockedByMe || c.blockedMe) {
                        out += Triple(System.currentTimeMillis(), false,
                            AppLanguage.t(ctx, "این گفتگو مسدود است", "This chat is blocked"))
                        return out
                    }
                    val own = AlienChats.parseSub(ShzClient.fetchAny(c.ownView))
                    for ((ts, second) in own.messages)
                        // 2.64: decrypt at display time (E2E).
                        out += Triple(ts, true, AlienE2e.displayStored(ctx, c.id, second, mine = true))
                    val partner = AlienChats.parseSub(ShzClient.fetchAny(c.partnerView))
                    for ((ts, second) in partner.messages) {
                        if (ts <= c.myClearTs) continue
                        out += Triple(ts, false, AlienE2e.displayStored(ctx, c.id, second, mine = false))
                    }
                    out.sortBy { it.first }
                }
                return out
            } catch (_: Exception) {
                return null
            }
        }

        /** Widget loader: fetch, falling back to the per-widget cache
         *  when offline, so the widget never goes blank. */
        fun loadRows(ctx: Context, id: Int): List<Triple<Long, Boolean, String>> {
            val rows = loadRowsFor(ctx, WidgetStyle.chatModeForWidget(ctx, id),
                WidgetStyle.chatForWidget(ctx, id))
            if (rows != null) {
                val mode = WidgetStyle.chatModeForWidget(ctx, id)
                val hiddenKey = if (mode == "receiver") "receiver"
                    else WidgetStyle.chatForWidget(ctx, id)
                val hidden = WidgetStyle.hiddenFor(ctx, hiddenKey)
                val visible = rows.filter { it.second || it.first !in hidden }
                WidgetStyle.saveChatCache(ctx, id, visible)
                return visible
            }
            return WidgetStyle.loadChatCache(ctx, id)
        }

        fun updateWidget(ctx: Context, appWidgetId: Int) {
            val mgr = AppWidgetManager.getInstance(ctx)
            var chatId = WidgetStyle.chatForWidget(ctx, appWidgetId)
            val modePref = WidgetStyle.chatModeForWidget(ctx, appWidgetId)
            if (chatId.isBlank() && modePref != "receiver") {
                // Claim the pending Studio config, if any.
                try {
                    val pending = WidgetStyle.takePending(ctx)
                    if (!pending.isNullOrBlank()) {
                        val o = org.json.JSONObject(pending)
                        if (o.optString("provider") == "chat") {
                            chatId = o.optString("chatId")
                            WidgetStyle.saveChatWidget(ctx, appWidgetId,
                                o.optString("title"), o.optString("style"),
                                o.optString("size"), o.optString("color"),
                                chatId, o.optString("chatmode"))
                        }
                    }
                } catch (_: Exception) {}
            }
            val views = RemoteViews(ctx.packageName, R.layout.widget_chat)
            val style = WidgetStyle.styleForWidget(ctx, appWidgetId)
            views.setInt(R.id.widget_chat_root, "setBackgroundResource",
                WidgetStyle.bgRes(style))
            val mode = WidgetStyle.chatModeForWidget(ctx, appWidgetId)
            if (chatId.isBlank() && mode != "receiver") {
                views.setTextViewText(R.id.widget_chat_title,
                    AppLanguage.t(ctx, "گفتگوی بیگانه", "Alien chat"))
                views.setTextViewText(R.id.widget_chat_empty,
                    AppLanguage.t(ctx, "از استودیو ویجت یک گفتگو انتخاب کن",
                        "Pick a chat in Widget Studio"))
                views.setViewVisibility(R.id.widget_chat_reply, View.GONE)
                val open = Intent(ctx, WidgetStudioActivity::class.java)
                views.setOnClickPendingIntent(R.id.widget_chat_root,
                    PendingIntent.getActivity(ctx, appWidgetId * 10 + 9, open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                try { mgr.updateAppWidget(appWidgetId, views) } catch (_: Exception) {}
                return
            }
            val title = WidgetStyle.titleForWidget(ctx, appWidgetId).ifBlank {
                if (mode == "receiver") AppLanguage.t(ctx, "پیام بیگانه", "Alien Message")
                else AlienChats.find(ctx, chatId)?.partnerName?.ifBlank { null }
                    ?: AppLanguage.t(ctx, "گفتگو", "Chat")
            }
            views.setTextViewText(R.id.widget_chat_title, title)
            if (WidgetStyle.isDark(style)) views.setTextColor(R.id.widget_chat_title, Color.WHITE)
            views.setTextViewText(R.id.widget_chat_empty,
                AppLanguage.t(ctx, "هنوز پیامی نیست", "No messages yet"))
            val svc = Intent(ctx, AlienChatWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("alienchat://widget/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.widget_chat_list, svc)
            views.setEmptyView(R.id.widget_chat_list, R.id.widget_chat_empty)
            // Every tap stays in the mini-chat: title, list items
            // and the reply button all open AlienQuickReplyActivity,
            // never the messenger or receiver pages.
            val mini = Intent(ctx, AlienQuickReplyActivity::class.java).apply {
                putExtra("mode", mode)
                putExtra("chat_id", chatId)
            }
            if (mode == "chat") {
                views.setViewVisibility(R.id.widget_chat_reply, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_chat_reply,
                    PendingIntent.getActivity(ctx, appWidgetId * 10 + 5, mini,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                views.setViewVisibility(R.id.widget_chat_reply, View.GONE)
            }
            val open = mini
            views.setOnClickPendingIntent(R.id.widget_chat_title,
                PendingIntent.getActivity(ctx, appWidgetId * 10 + 6, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setPendingIntentTemplate(R.id.widget_chat_list,
                PendingIntent.getActivity(ctx, appWidgetId * 10 + 7, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            try {
                mgr.updateAppWidget(appWidgetId, views)
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_chat_list)
            } catch (_: Exception) {}
        }

        fun updateAllWidgets(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AlienChatWidgetProvider::class.java))
                for (id in ids) updateWidget(ctx, id)
            } catch (_: Exception) {}
        }

        fun refreshAll(ctx: Context) = updateAllWidgets(ctx)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(ctx, id)
        try { AlienWidgetPoll.schedule(ctx) } catch (_: Exception) {}
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) WidgetStyle.clearWidget(ctx, id)
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val rest = mgr.getAppWidgetIds(ComponentName(ctx, AlienChatWidgetProvider::class.java))
            if (rest.isEmpty()) AlienWidgetPoll.cancel(ctx)
        } catch (_: Exception) {}
    }
}
