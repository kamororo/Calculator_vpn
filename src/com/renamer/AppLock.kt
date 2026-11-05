package com.renamer

import android.content.Context

/** Whole-app lock, triggered by one wrong emergency
 *  settings password. Stored in its own prefs file, so
 *  clearing app data (or uninstall + reinstall) wipes
 *  it, which is the only recovery besides entering the
 *  creator's entry password on the lock screen. */
object AppLock {
    fun message(ctx: Context): String {
        // the lock message names no private or
        // hidden section -- only generic recovery
        // guidance (ask the maker, or reinstall).
        try {
                    } catch (_: Exception) {}
        return AppLanguage.t(ctx,
            "برنامه قفل شده است. برای استفاده دوباره از برنامه، از سازنده برنامه درخواست رمز ورود بکنید و یا برنامه را کامل از روی گوشی پاک کنید و دوباره نصب کنید",
            "The app is locked. To use it again, ask the app creator for the entry password, or uninstall the app completely from your phone and install it again.")
    }

    /** The creator's entry password for unlocking a
     *  locked app. This is deliberately DIFFERENT from
     *  the emergency settings password (123456789): the
     *  app locks because that password was entered
     *  wrong, so its unlock password must be another
     *  one, given by the creator. */
    const val UNLOCK_PASSWORD = "0000"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    fun isLocked(ctx: Context): Boolean {
        return prefs(ctx).getBoolean("locked", false)
    }

    fun lock(ctx: Context) {
        prefs(ctx).edit().putBoolean("locked", true).apply()
    }

    /** Unlocks only with the creator's entry password. */
    fun unlock(ctx: Context, password: String): Boolean {
        if (password == UNLOCK_PASSWORD) {
            prefs(ctx).edit().putBoolean("locked", false).apply()
            ctx.getSharedPreferences("emergency_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("settings_locked", false).apply()
            return true
        }
        return false
    }
}
