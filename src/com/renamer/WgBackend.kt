package com.renamer

import android.content.Context
import com.wireguard.android.backend.GoBackend

/** Single shared GoBackend instance for the whole
 *  app, like the official WireGuard app's backend:
 *  only one tunnel can be up at a time, and its
 *  state lives in this backend, not in an activity. */
object WgBackend {
    @Volatile private var backend: GoBackend? = null

    @Synchronized
    fun get(ctx: Context): GoBackend {
        return backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }
}
