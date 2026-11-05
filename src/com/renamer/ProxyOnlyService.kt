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
        /** 2.14: generation of the current proxy core. */
        @Volatile var proxyGen = 0

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
            // 2.14: no stopService (same race as VpnClientService).
        }
    }

    /** Generation of the core this object started (2.14). */
    private var myProxyGen = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.getStringExtra("config")
        val name = intent?.getStringExtra("name")
        if (config == null) {
            // 2.14: no stopSelf (see VpnClientService.coreFailed).
            return START_NOT_STICKY
        }
        proxyGen++
        myProxyGen = proxyGen
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
                // Holder for the core this thread starts:
                // the callback object below is created
                // before `val c` exists, so it cannot
                // name `c` directly; it reads the
                // holder, which is filled right after.
                var self: libv2ray.CoreController? = null
                val c = Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
                    override fun onEmitStatus(code: Long, message: String?): Long = 0
                    override fun shutdown(): Long {
                        // 2.57: the core stopped on its
                        // own — release the handle, but
                        // only if this core is still
                        // the current one.
                        val mine = self
                        if (mine != null && controller === mine) {
                            controller = null
                            isRunning = false
                            currentName = null
                        }
                        return 0
                    }
                    override fun startup(): Long = 0
                })
                self = c
                // 2.57: same fix as the chain proxy
                // core — startLoop RETURNS once the
                // core is running (it does not
                // block), so the old try/finally
                // cleared the handle immediately
                // after start, leaving a running
                // core that stop() could never
                // stop. The handle now lives until
                // stop() or the core's own shutdown.
                controller = c
                isRunning = true
                currentName = name
                c.startLoop(config, -1)
            } catch (_: Throwable) {
                isRunning = false
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 2.14: only the object that owns the current core may
        // stop it; a stale object must not kill a newer proxy
        // session (the companion controller is shared). Flags
        // are owned by start()/stop() and by the core's own
        // shutdown callback, never by onDestroy.
        if (myProxyGen == proxyGen) {
            try { controller?.stopLoop() } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}
