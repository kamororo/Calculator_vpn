package com.renamer

import android.content.Context
import go.Seq
import libv2ray.Libv2ray
import java.io.File

/**
 * Correct Xray core initialization, the way v2rayNG does it:
 *
 * The core looks up geo files (geoip.dat, geosite.dat) with
 * os.Stat() on <asset-dir>/<file>. If initCoreEnv is called
 * with an empty path, that stat runs against the process
 * working directory (/system/bin for an Android app), fails
 * with "stat /system/bin/geoip.dat: no such file", and any
 * config using geoip:private fails to parse — exactly the
 * "core failed: ... failed to open geoip.dat" error from
 * the device screenshot.
 *
 * So: copy geoip.dat/geosite.dat from the APK assets into
 * the app's private asset directory first, then pass that
 * directory to initCoreEnv. After that, os.Stat succeeds
 * on a real file.
 */
object CoreInit {
    @Volatile var lastError: String? = null
    private var done = false

    @Synchronized
    fun init(ctx: Context): Boolean {
        if (done) return true
        return try {
            val dir = ctx.getDir("assets", Context.MODE_PRIVATE)
            for (name in listOf("geoip.dat", "geosite.dat")) {
                try {
                    val out = File(dir, name)
                    if (!out.exists() || out.length() == 0L) {
                        ctx.assets.open(name).use { input ->
                            out.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Missing geo file: VPN config no longer
                    // needs one (see VpnActivity), so continue.
                }
            }
            Seq.setContext(ctx.applicationContext)
            Libv2ray.initCoreEnv(dir.absolutePath, "")
            done = true
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }
}
