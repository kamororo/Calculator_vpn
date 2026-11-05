package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 2.16: app icon + name customization.
 *
 *  What Android actually allows, stated plainly:
 *  - The REAL app icon can only switch between
 *    icons declared at build time (activity-alias).
 *    Here: the calculator icon (default) and the
 *    beautiful icon. The alias label is fixed too
 *    ("calculator" / "ConfigRenamer") — a typed
 *    custom name for the real icon is impossible.
 *  - A fully custom name AND any icon (the
 *    beautiful one, or any installed app's icon)
 *    IS possible as a home-screen shortcut: the
 *    shortcut opens the same lock screen.
 *  The Settings / Personalization UI says exactly
 *  this, so nobody is promised what cannot be done.
 */
object AppIconSwitcher {
    private fun calcComponent(ctx: Context) =
        ComponentName(ctx, ctx.packageName + ".PinActivity")
    private fun beautifulComponent(ctx: Context) =
        ComponentName(ctx, ctx.packageName + ".AliasBeautiful")

    fun isBeautiful(ctx: Context): Boolean = try {
        ctx.packageManager.getComponentEnabledSetting(beautifulComponent(ctx)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (_: Exception) { false }

    fun setBeautiful(ctx: Context, beautiful: Boolean) {
        val pm = ctx.packageManager
        val on = if (beautiful) beautifulComponent(ctx) else calcComponent(ctx)
        val off = if (beautiful) calcComponent(ctx) else beautifulComponent(ctx)
        // Enable first, then disable: the launcher
        // never sees zero icons for this app.
        pm.setComponentEnabledSetting(on,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(off,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    fun beautifulBitmap(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_beautiful)
    } catch (_: Exception) { null }

    fun calcBitmap(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
    } catch (_: Exception) { null }

    fun drawableToBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    fun launchableApps(ctx: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            ctx.packageManager.queryIntentActivities(intent, 0)
        } catch (_: Exception) { emptyList() }
        return list.sortedBy { it.loadLabel(ctx.packageManager)?.toString() ?: "" }
    }

    /** True if the system showed the pin confirmation.
     *  False = this launcher cannot pin shortcuts. */
    fun requestShortcut(activity: Activity, name: String, icon: Bitmap): Boolean {
        return try {
            val sm = activity.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager
                ?: return false
            if (!sm.isRequestPinShortcutSupported) return false
            val intent = Intent(activity, PinActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val info = ShortcutInfo.Builder(activity, "custom_home_shortcut")
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon))
                .setIntent(intent)
                .build()
            sm.requestPinShortcut(info, null)
        } catch (_: Exception) { false }
    }

    /** Picker dialog: every launchable app, icon + name.
     *  onPick receives the chosen app; the dialog closes. */
    fun showAppPicker(activity: Activity, onPick: (ResolveInfo) -> Unit) {
        val apps = launchableApps(activity)
        val pm = activity.packageManager
        val list = ListView(activity)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val app = apps[p]
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(32, 20, 32, 20)
                }
                row.addView(ImageView(activity).apply {
                    try { setImageDrawable(app.loadIcon(pm)) } catch (_: Exception) {}
                    val s = (48 * activity.resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        setMargins(0, 0, 32, 0)
                    }
                })
                row.addView(TextView(activity).apply {
                    text = app.loadLabel(pm)?.toString() ?: ""
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                return row
            }
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("یک برنامه انتخاب کن")
            .setView(list)
            .setNegativeButton("بستن", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            onPick(apps[pos])
            dlg.dismiss()
        }
        dlg.show()
    }
}
