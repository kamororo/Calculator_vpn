package com.renamer

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Home-screen widget for the Alien Message
 *  feature (2.27): shows the latest received
 *  message; tapping it opens the receiver page.
 *  Content is pushed on demand whenever a new
 *  message arrives — never on a timer. */
class AlienWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        try { AlienSub.refreshWidget(ctx) } catch (_: Exception) {}
    }
}
