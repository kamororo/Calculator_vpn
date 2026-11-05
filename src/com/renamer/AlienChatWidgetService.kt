package com.renamer

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** List adapter for the chat widget (2.32). Loads on a binder
 *  thread, never the UI thread; falls back to the per-widget cache
 *  when the fetch fails, so the widget never goes blank offline. */
class AlienChatWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID)
        return ChatFactory(applicationContext, id)
    }

    private class ChatFactory(
        val ctx: Context, val appWidgetId: Int
    ) : RemoteViewsFactory {
        private var rows: List<Triple<Long, Boolean, String>> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            val all = AlienChatWidgetProvider.loadRows(ctx, appWidgetId)
            val count = 8
            rows = if (all.size > count) all.takeLast(count) else all
        }

        override fun onDestroy() {}

        override fun getCount(): Int = rows.size

        override fun getViewAt(pos: Int): RemoteViews? {
            if (pos < 0 || pos >= rows.size) return null
            val (ts, mine, text) = rows[pos]
            val v = RemoteViews(ctx.packageName, R.layout.widget_chat_item)
            v.setTextViewText(R.id.widget_chat_msg_mine, text)
            v.setTextViewText(R.id.widget_chat_msg_theirs, text)
            v.setViewVisibility(R.id.widget_chat_msg_mine,
                if (mine) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_chat_msg_theirs,
                if (mine) View.GONE else View.VISIBLE)
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            v.setTextViewText(R.id.widget_chat_time, fmt.format(java.util.Date(ts)))
            v.setInt(R.id.widget_chat_time, "setGravity",
                if (mine) android.view.Gravity.END else android.view.Gravity.START)
            v.setOnClickFillInIntent(R.id.widget_chat_item_root, Intent())
            return v
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
