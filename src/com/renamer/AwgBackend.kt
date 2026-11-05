package com.renamer

import android.content.Context
import org.amnezia.awg.backend.GoBackend

/** Single shared AmneziaWG GoBackend instance, mirroring
 *  WgBackend: only one tunnel can be up at a time, and
 *  its state lives in this backend, not in an activity.
 *  The AWG backend loads libawg-go.so (the AmneziaWG
 *  core from the published org.amnezia.awg app), NOT
 *  libwg-go.so, so both engines coexist in this app. */
object AwgBackend {
    @Volatile private var backend: GoBackend? = null

    @Synchronized
    fun get(ctx: Context): GoBackend {
        return backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }
}
