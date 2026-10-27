package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import libv2ray.CoreController
import libv2ray.Libv2ray

/** Proxy-only mode (settings vpn_mode = "Proxy only"):
 *  the Xray core runs with local proxy inbounds only
 *  (socks, plus http when append_http_proxy is on)
 *  and NO tun inbound, so no Android VPN service and
 *  no VPN permission are involved. Apps that support
 *  a proxy can point at 127.0.0.1:socks_port; the
 *  system itself is NOT tunnelled (that is what the
 *  mode means). Same startLoop(-1) mechanism as
 *  ChainEngine's loopback proxy core. */
class ProxyOnlyService : Service() {
    companion object {
        @Volatile var isRunning = false
        @Volatile var currentName: String? = null
        private var controller: CoreController? = null

        fun start(ctx: Context, config: String, name: String) {
            stop(ctx)
            val intent = Intent(ctx, ProxyOnlyService::class.java).apply {
                putExtra("config", config)
                putExtra("name", name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            try { controller?.stopLoop() } catch (_: Throwable) {}
            controller = null
            isRunning = false
            currentName = null
            try { ctx.stopService(Intent(ctx, ProxyOnlyService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.getStringExtra("config")
        val name = intent?.getStringExtra("name")
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel("proxy_only") == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            "proxy_only",
                            AppLanguage.t(this, "پروکسی", "Proxy"),
                            NotificationManager.IMPORTANCE_LOW
                        )
                    )
                }
            }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "proxy_only")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle(AppLanguage.t(this, "پروکسی فعال", "Proxy active"))
                .setContentText(name ?: "")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForeground(3001, notif)
        } catch (_: Throwable) {}
        Thread {
            try {
                if (!CoreInit.init(applicationContext)) return@Thread
                val c = Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                    override fun onEmitStatus(code: Long, message: String?): Long = 0
                    override fun shutdown(): Long = 0
                    override fun startup(): Long = 0
                })
                c.startLoop(config, -1)
                controller = c
                isRunning = true
                currentName = name
            } catch (_: Throwable) {
                isRunning = false
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { controller?.stopLoop() } catch (_: Throwable) {}
        controller = null
        isRunning = false
        currentName = null
        super.onDestroy()
    }
}
