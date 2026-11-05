package com.renamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Background check for the Alien Message
 *  receiver (2.27). The alarm fires, this fetches
 *  the link, and only when the content actually
 *  changed does the user hear about it: widget
 *  refresh + a "new message" notification with
 *  sound (visible on the lock screen too). After
 *  a reboot, the alarm is re-armed from prefs.
 *  No URL is ever logged or shown here. */
class AlienSubCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val action = intent?.action
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                AlienSub.rearmIfNeeded(ctx)
                return
            }
            val view = AlienSub.receiverView(ctx) ?: return
            val minutes = AlienSub.autoMinutes(ctx)
            if (minutes > 0) AlienSub.scheduleCheck(ctx, minutes)
            Thread {
                try {
                    val content = ShzClient.fetchAny(view) ?: return@Thread
                    val old = AlienSub.lastContent(ctx)
                    if (content == old) return@Thread
                    val decoded = try { SecretLanguage.decode(content) } catch (_: Exception) { "" }
                    val shown = if (decoded.isBlank() || AlienSub.revealsSite(decoded)) "" else decoded
                    AlienSub.setLast(ctx, content, shown)
                    AlienSub.refreshWidget(ctx)
                    if (shown.isNotBlank()) AlienSub.notifyNewMessage(ctx, shown)
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }
}
