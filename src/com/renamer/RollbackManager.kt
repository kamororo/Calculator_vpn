package com.renamer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Smart rollback (2.12).
 *
 *  Three layers, all real:
 *
 *  1. LOOK rollback (instant, in-app, no data
 *     loss): HydraUi's photo-asset look vs the
 *     previous version's look (2.11 glass orbs).
 *     A pref flip; pages opened afterwards use
 *     the old look. Reversible at any time.
 *
 *  2. TECH rollback (instant, in-app): behavior
 *     flags for the latest update's technical
 *     changes — currently the engine's
 *     methods-first connect (current) vs probing
 *     the untouched original first (previous).
 *
 *  3. FULL rollback (APK): on every version
 *     change, the running version saves a copy of
 *     its own APK while it still runs, so the NEXT
 *     version holds the previous APK. Rolling back
 *     fully exports the prefs to Downloads, copies
 *     the previous APK there, and asks the system
 *     to uninstall the current app — Android
 *     refuses to install an older versionCode over
 *     a newer one, so uninstall + reinstall from
 *     Downloads is the only real path, and the
 *     dialog says so plainly. Data restore works
 *     when the target version is 2.12 or newer
 *     (it contains this file); older targets
 *     start fresh.
 *
 *  NOTE for future releases: the "previous" look
 *  and "previous" tech branches are frozen at the
 *  version BEFORE the release that introduced
 *  them. Each release must rotate: current look /
 *  tech becomes the new "previous" branch, or the
 *  rollback will point at the wrong version.
 *
 *  2.13 EXCEPTION (user order 2026-11-01): the
 *  shipped default look is "previous" (2.11);
 *  branches were NOT rotated in 2.13 —
 *  "previous" still means the 2.11 look and
 *  "current" still means the 2.12 photo look.
 *  Do not rotate until the user orders a new look.
 *
 *  2.15 (user order 2026-11-01): the Settings
 *  rollback box is replaced by a theme picker:
 *  Theme 1 = "previous" branch (2.11 glass look,
 *  the one the user calls correct), Theme 2 =
 *  "current" branch (2.12 photo look, the one
 *  the user calls broken). Both branches stay
 *  until the user picks one; the loser will
 *  then be deleted completely. Tech switching
 *  is no longer offered in Settings.
 *
 *  2.16 (user order 2026-11-01): the verdict is in —
 *  Theme 1 stays, Theme 2 is DELETED COMPLETELY:
 *  photo assets, photo drawables, look branches,
 *  look prefs and the Settings theme picker are all
 *  gone. Theme 1 (2.11 glass) is now the only look,
 *  unconditionally. Tech profiles stay (engine use).
 */
object RollbackManager {
    private const val PREFS = "renamer_rollback"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- profile flags ----------
    fun techIsPrevious(ctx: Context): Boolean =
        sp(ctx).getString("tech", "current") == "previous"

    fun applyTech(ctx: Context, prev: Boolean) {
        sp(ctx).edit().putString("tech", if (prev) "previous" else "current").apply()
    }

    // ---------- versions ----------
    fun currentVersion(ctx: Context): Pair<Int, String> {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
            Pair(code, pi.versionName ?: "")
        } catch (_: Exception) { Pair(0, "") }
    }

    fun prevVersion(ctx: Context): Pair<Int, String>? {
        val code = sp(ctx).getInt("prev_code", 0)
        val name = sp(ctx).getString("prev_name", "") ?: ""
        return if (code > 0) Pair(code, name) else null
    }

    private fun rollbackDir(ctx: Context): File {
        val d = File(ctx.filesDir, "rollback")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun prevApkFile(ctx: Context): File? {
        val f = File(rollbackDir(ctx), "prev.apk")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Called on every app launch. On a version
     *  change, the previously saved current APK
     *  becomes the previous APK, and the running
     *  APK is saved as current. First run of a
     *  version with this file has no previous APK
     *  yet — that is honest, and the Settings box
     *  says so. */
    fun ensureSnapshot(ctx: Context) {
        try {
            val cur = currentVersion(ctx)
            val seen = sp(ctx).getInt("snapshot_version", -1)
            if (seen == cur.first) return
            val dir = rollbackDir(ctx)
            val curFile = File(dir, "cur.apk")
            val prevFile = File(dir, "prev.apk")
            if (curFile.exists() && curFile.length() > 0) {
                if (prevFile.exists()) prevFile.delete()
                curFile.renameTo(prevFile)
                sp(ctx).edit()
                    .putInt("prev_code", seen)
                    .putString("prev_name", sp(ctx).getString("snapshot_name", "") ?: "")
                    .apply()
            }
            val src = File(ctx.applicationInfo.sourceDir)
            if (src.exists()) src.copyTo(curFile, overwrite = true)
            sp(ctx).edit()
                .putInt("snapshot_version", cur.first)
                .putString("snapshot_name", cur.second)
                .apply()
        } catch (_: Exception) {}
    }

    // ---------- prefs export / restore ----------
    // Keys whose names contain any of these
    // fragments are SENSITIVE and are never written
    // to the plain Downloads backup: passwords,
    // private keys, secrets, tokens, Cloudflare
    // credentials (cf_key / cf_email), PINs, and
    // manage links.
    private val SENSITIVE_KEY_FRAGMENTS = listOf(
        "password", "privatekey", "private_key", "secret",
        "token", "cf_key", "cf_email", "pin", "manage")
    private fun isSensitiveKey(key: String): Boolean {
        val low = key.lowercase()
        return SENSITIVE_KEY_FRAGMENTS.any { low.contains(it) }
    }

    private fun prefsJson(ctx: Context): String {
        val root = JSONObject()
        try {
            val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray()
            for (f in files) {
                val name = f.name.removeSuffix(".xml")
                if (name == PREFS) continue
                val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val obj = JSONObject()
                for ((k, v) in sp.all) {
                    if (isSensitiveKey(k)) continue
                    val o = JSONObject()
                    when (v) {
                        is String -> { o.put("t", "s"); o.put("v", v) }
                        is Int -> { o.put("t", "i"); o.put("v", v) }
                        is Long -> { o.put("t", "l"); o.put("v", v) }
                        is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                        is Boolean -> { o.put("t", "b"); o.put("v", v) }
                        is Set<*> -> {
                            o.put("t", "ss")
                            val arr = JSONArray()
                            for (s in v) arr.put(s.toString())
                            o.put("v", arr)
                        }
                        else -> continue
                    }
                    obj.put(k, o)
                }
                root.put(name, obj)
            }
            // Marker: backups written by this app carry
            // it, so a foreign file is recognizable.
            root.put("appBackup", true)
            root.put("backupVersion", 1)
        } catch (_: Exception) {}
        return root.toString()
    }

    fun restorePrefsJson(ctx: Context, json: String): Boolean {
        try {
            val root = JSONObject(json)
            // A backup without the marker is an older
            // backup of this same app: accept it, but
            // say so in the log, so a foreign file
            // never restores silently.
            if (!root.optBoolean("appBackup", false) || root.optInt("backupVersion", 0) < 1) {
                try {
                    AppLog.append(ctx, "renamer",
                        "بکاپ قدیمی بدون نشان بازیابی شد.",
                        "Legacy backup without marker restored.")
                } catch (_: Exception) {}
            }
            var any = false
            val names = root.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name == PREFS || name == "appBackup" || name == "backupVersion") continue
                val obj = root.getJSONObject(name)
                val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = obj.getJSONObject(k)
                    when (o.optString("t")) {
                        "s" -> ed.putString(k, o.optString("v"))
                        "i" -> ed.putInt(k, o.optInt("v"))
                        "l" -> ed.putLong(k, o.optLong("v"))
                        "f" -> ed.putFloat(k, o.optDouble("v").toFloat())
                        "b" -> ed.putBoolean(k, o.optBoolean("v"))
                        "ss" -> {
                            val arr = o.optJSONArray("v")
                            val set = mutableSetOf<String>()
                            if (arr != null) for (i in 0 until arr.length()) set.add(arr.optString(i))
                            ed.putStringSet(k, set)
                        }
                    }
                }
                ed.commit()
                any = true
            }
            if (any) {
                try {
                    AppLog.append(ctx, "renamer",
                        "پشتیبان بازیابی شد.",
                        "Backup restored.")
                } catch (_: Exception) {}
            }
            return any
        } catch (_: Exception) { return false }
    }

    // ---------- Downloads helpers ----------
    private fun downloadsUri(ctx: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            } else {
                @Suppress("DEPRECATION")
                val out = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), name)
                out.writeBytes(bytes)
                Uri.fromFile(out)
            }
        } catch (_: Exception) { null }
    }

    private fun downloadsStream(ctx: Context, src: File, name: String, mime: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val out = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), name)
                src.copyTo(out, overwrite = true)
                Uri.fromFile(out)
            }
        } catch (_: Exception) { null }
    }

    /** Exports the previous APK + a prefs backup
     *  to Downloads. Returns the previous version
     *  label, or null when there is nothing to
     *  roll back to or the export failed. */
    fun exportFullRollback(ctx: Context): String? {
        val prev = prevApkFile(ctx) ?: return null
        val label = prevVersion(ctx)?.let { "${it.second} (کد ${it.first})" } ?: "نسخه قبلی"
        val apkUri = downloadsStream(ctx, prev, "ConfigRenamer-prev.apk",
            "application/vnd.android.package-archive") ?: return null
        val jsonUri = downloadsUri(ctx, "ConfigRenamer-backup.json",
            "application/json", prefsJson(ctx).toByteArray(Charsets.UTF_8)) ?: return null
        if (apkUri == Uri.EMPTY || jsonUri == Uri.EMPTY) return null
        try {
                    } catch (_: Exception) {}
        return label
    }

    /** Finds the newest rollback prefs backup in
     *  Downloads. Returns (dateAdded, json) or
     *  null. */
    fun findBackup(ctx: Context): Pair<Long, String>? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val cur = ctx.contentResolver.query(uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf("ConfigRenamer-backup.json"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                    ?: return null
                cur.use {
                    if (!it.moveToFirst()) return null
                    val id = it.getLong(0)
                    val date = it.getLong(1)
                    val fileUri = Uri.withAppendedPath(uri, id.toString())
                    val text = ctx.contentResolver.openInputStream(fileUri)?.use { s ->
                        s.readBytes().toString(Charsets.UTF_8)
                    } ?: return null
                    Pair(date, text)
                }
            } else {
                @Suppress("DEPRECATION")
                val f = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "ConfigRenamer-backup.json")
                if (f.exists()) Pair(f.lastModified(), f.readText(Charsets.UTF_8)) else null
            }
        } catch (_: Exception) { null }
    }
}
