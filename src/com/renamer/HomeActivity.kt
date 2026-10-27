package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/** Main menu, as it was before v1.39, with one
 *  change the user asked for: the SlipNet section
 *  is removed entirely (no tile, no activity in
 *  the manifest). The Android 16 targetSdk fix
 *  from v1.40 is kept, and the original icons
 *  from v1.39 are kept. */
class HomeActivity : Activity() {
    private data class MenuItem(val key: String, val name: String, val iconAsset: String?, val target: Class<*>)

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 12, 0, 12) }
        setBackgroundColor(Color.parseColor("#DDDDDD"))
    }

    companion object {
        fun themeMode(ctx: android.content.Context): String {
            return ctx.getSharedPreferences("renamer_settings", MODE_PRIVATE)
                .getString("theme_mode", "") ?: ""
        }
        fun isDarkMode(ctx: android.content.Context): Boolean {
            return when (themeMode(ctx)) {
                "dark" -> true
                "light" -> false
                "auto" -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                "night" -> {
                    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    h >= 18 || h < 6
                }
                else -> ctx.getSharedPreferences("renamer_settings", MODE_PRIVATE)
                    .getBoolean("dark_mode", false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-arm Smart Connect on every Home open, so a
        // killed service comes back without the user
        // having to re-save it.
        try {
            val sp = getSharedPreferences("renamer_smart", MODE_PRIVATE)
            val apps = sp.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (sp.getBoolean("smart_enabled", false) && apps.isNotEmpty()) {
                val svc = Intent(this, SmartConnectService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            }
        } catch (_: Exception) {}
        val mode = intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC
        val isHidden = mode == PinActivity.MODE_HIDDEN
        val dark = isDarkMode(this)
        val bg = if (dark) Color.parseColor("#121212") else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val fgGray = if (dark) Color.parseColor("#AAAAAA") else Color.GRAY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bg)
        }
        // Day/night switch at the top, as before v1.39.
        // Clearing theme_mode makes the switch authoritative again.
        val nightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        nightRow.addView(TextView(this).apply {
            text = AppLanguage.t(this@HomeActivity, "حالت شب", "Night mode"); textSize = 13f
            setTextColor(fgGray)
            setPadding(0, 0, 8, 0)
        })
        nightRow.addView(Switch(this).apply {
            isChecked = dark
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("renamer_settings", MODE_PRIVATE)
                    .edit().putString("theme_mode", "").putBoolean("dark_mode", checked).apply()
                recreate()
            }
        })
        // Settings gear, opening the Home settings page.
        nightRow.addView(Button(this).apply {
            text = "⚙"; textSize = 16f
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val s = Intent(this@HomeActivity, HomeSettingsActivity::class.java)
                s.putExtra(PinActivity.EXTRA_MODE, intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
                startActivity(s)
            }
        })
        // Language selector, right next to day/night.
        nightRow.addView(Button(this).apply {
            text = if (AppLanguage.isEnglish(this@HomeActivity)) "EN" else "FA"
            textSize = 13f
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle(AppLanguage.t(this@HomeActivity, "زبان برنامه", "App language"))
                    .setItems(arrayOf("فارسی", "English")) { _, which ->
                        AppLanguage.set(this@HomeActivity, if (which == 0) "fa" else "en")
                        recreate()
                    }
                    .show()
            }
        })
        root.addView(nightRow)
        root.addView(TextView(this).apply {
            text = AppLanguage.t(this@HomeActivity, "منوی اصلی", "Main menu"); textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(fg)
        })
        root.addView(TextView(this).apply {
            text = if (isHidden) AppLanguage.t(this@HomeActivity, "حالت مخفی: همه بخش‌ها در دسترس است", "Hidden mode: all sections available")
                   else AppLanguage.t(this@HomeActivity, "حالت عمومی", "Public mode")
            setTextColor(fgGray); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        })
        root.addView(divider())

        val items = mutableListOf<MenuItem>()
        // Public sections: v2ray, WireGuard, OpenVPN and emergency connect.
        // SlipNet was removed at the user's request.
        items += MenuItem("v2ray", "v2ray", "vpn.png", VpnActivity::class.java)
        items += MenuItem("wireguard", "WireGuard", "wg.png", WireGuardActivity::class.java)
        items += MenuItem("openvpn", "OpenVPN", "ovpn.png", OpenVpnActivity::class.java)
        items += MenuItem("emergency", AppLanguage.t(this, "اتصال اضطراری", "Emergency connect"), "vpn.png", EmergencyActivity::class.java)
        items += MenuItem("smart", AppLanguage.t(this, "اتصال هوشمند", "Smart Connect"), "smart.png", SmartConnectActivity::class.java)
        items += MenuItem("chain", AppLanguage.t(this, "زنجیره دوتایی", "Double chain"), "chain.png", ChainActivity::class.java)
        items += MenuItem("hydra", AppLanguage.t(this, "هیدرا", "Hydra"), "hydra.png", HydraActivity::class.java)
        if (isHidden) {
            items += MenuItem("renamer", AppLanguage.t(this, "تغییر نام کانفیگ", "Config Renamer"), "v2ray.png", RenameActivity::class.java)
            items += MenuItem("subs", AppLanguage.t(this, "اشتراک‌ها و تست پینگ", "Subscriptions & Ping Test"), "sub.png", SubscriptionActivity::class.java)
        }

        // Apply the user's saved order from Settings > Sort apps.
        try {
            val orderRaw = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("home_order", "") ?: ""
            if (orderRaw.isNotBlank()) {
                val order = org.json.JSONArray(orderRaw).let { a -> (0 until a.length()).map { a.optString(it) } }
                val rank = order.withIndex().associate { it.value to it.index }
                items.sortWith(compareBy { rank[it.key] ?: Int.MAX_VALUE })
            }
        } catch (_: Exception) {}
        // Grid: two icons per row, name under each icon.
        // A thin light line separates each row from the next.
        val dp = resources.displayMetrics.density
        for (rowStart in items.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0 until 2) {
                val idx = rowStart + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (idx < items.size) {
                    val item = items[idx]
                    if (item.iconAsset != null) {
                        try {
                            val bmp = BitmapFactory.decodeStream(assets.open(item.iconAsset))
                            cell.addView(ImageView(this).apply {
                                setImageBitmap(bmp)
                                layoutParams = LinearLayout.LayoutParams(
                                    (96 * dp).toInt(), (96 * dp).toInt()
                                )
                            })
                        } catch (_: Exception) {}
                    }
                    cell.addView(TextView(this).apply {
                        text = item.name; textSize = 16f; gravity = Gravity.CENTER
                        setTextColor(fg)
                        setPadding(0, 12, 0, 0)
                    })
                    cell.setOnClickListener {
                        startActivity(Intent(this@HomeActivity, item.target))
                    }
                }
                row.addView(cell)
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(Button(this).apply {
            text = AppLanguage.t(this@HomeActivity, "قفل و خروج", "Lock & exit")
            setOnClickListener { finish() }
        })
        // Scrollable on purpose: tiles are added over
        // time, and a fixed page cut the last row off
        // (the Hydra icon showed half) once the grid
        // grew past the screen. With a ScrollView, any
        // number of future tiles just scrolls.
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        scroll.addView(root)
        setContentView(scroll)
    }
}
