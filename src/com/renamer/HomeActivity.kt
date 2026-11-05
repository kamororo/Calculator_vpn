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

    // it can be rebuilt on resume (a saved reorder then
    // shows at once on return, instead of waiting for a
    // full activity recreate).
    private lateinit var gridContainer: LinearLayout
    private var gridDark = false
    private var gridFg = Color.BLACK
    private var gridIsHidden = false
    private var homeGridBuilt = false
    private var lastHomeOrder = ""

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

    /** 2.12: after a full rollback install, offer
     *  to restore the data backup once. */
    private fun maybeOfferRestore() {
        try {
            val found = RollbackManager.findBackup(this) ?: return
            val (dateAdded, json) = found
            val seen = getSharedPreferences("renamer_rollback", MODE_PRIVATE)
                .getString("restore_seen", null)
            if (seen == dateAdded.toString()) return
            getSharedPreferences("renamer_rollback", MODE_PRIVATE).edit()
                .putString("restore_seen", dateAdded.toString()).apply()
            android.app.AlertDialog.Builder(this)
                .setTitle(AppLanguage.t(this, "بازگرداندن داده‌ها", "Restore data"))
                .setMessage(AppLanguage.t(this,
                    "پشتیبان داده‌های نسخه قبلی پیدا شد. برگردد؟",
                    "A data backup from the previous version was found. Restore it?"))
                .setPositiveButton(AppLanguage.t(this, "بله", "Yes")) { _, _ ->
                    try {
                        // Only claim success when restore really ran;
                        // a rejected file (e.g. a main-app backup) must not
                        // show the false "restored" toast.
                        if (RollbackManager.restorePrefsJson(this, json)) {
                            android.widget.Toast.makeText(this,
                                AppLanguage.t(this, "داده‌ها برگشت.", "Data restored."),
                                android.widget.Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    } catch (_: Exception) {}
                }
                .setNegativeButton(AppLanguage.t(this, "نه", "No"), null)
                .show()
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.12: remove the removed Tor section's leftover
        // on-device data dirs (binaries + state). Nothing
        // in the app reads them anymore; this runs once.
        try {
            val spTor = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            if (!spTor.getBoolean("tor_leftover_cleaned_212", false)) {
                for (d in arrayOf("tor_bin", "tor_data", "geoip")) {
                    try { java.io.File(filesDir, d).deleteRecursively() } catch (_: Exception) {}
                }
                spTor.edit().putBoolean("tor_leftover_cleaned_212", true).apply()
            }
        } catch (_: Exception) {}
        try { RollbackManager.ensureSnapshot(this) } catch (_: Exception) {}
        try { maybeOfferRestore() } catch (_: Exception) {}
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
        gridIsHidden = isHidden
        val dark = isDarkMode(this)
        val bg = if (dark) Color.parseColor("#121212") else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        gridDark = dark
        gridFg = fg
        val fgGray = if (dark) Color.parseColor("#AAAAAA") else Color.GRAY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bg)
        }
        // Minimal classy top row: three small
        // circular buttons (night, language,
        // settings), no labels, no switch. Same
        // behaviors as before: night toggles the
        // theme, language opens its dialog, gear
        // opens Home settings.
        val density = resources.displayMetrics.density
        fun dpPx(v: Int) = (v * density + 0.5f).toInt()
        fun miniCircle(text: String, textSize: Float, onClick: () -> Unit): Button {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000"))
            }
            return Button(this).apply {
                this.text = text
                this.textSize = textSize
                background = d
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                setTextColor(fg)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(dpPx(32), dpPx(32)).apply {
                    setMargins(dpPx(4), 0, dpPx(4), 0)
                }
                setOnClickListener { onClick() }
            }
        }

        /** Same 32dp circle as miniCircle, but with a
         *  drawn glyph instead of a character: the
         *  monitoring (pulse) icon that opens the
         *  Monitoring + Log page. Same size, same
         *  margins, same tap target. */
        fun miniCircleIcon(asset: String, onClick: () -> Unit): android.widget.FrameLayout {
            val wrap = android.widget.FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000"))
                }
                layoutParams = LinearLayout.LayoutParams(dpPx(32), dpPx(32)).apply {
                    setMargins(dpPx(4), 0, dpPx(4), 0)
                }
                isClickable = true
                setOnClickListener { onClick() }
            }
            try {
                val bmp = BitmapFactory.decodeStream(assets.open(asset))
                wrap.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bmp)
                    layoutParams = android.widget.FrameLayout.LayoutParams(dpPx(21), dpPx(21)).apply {
                        gravity = Gravity.CENTER
                    }
                })
            } catch (_: Exception) {}
            return wrap
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Version label, always visible, left side,
        // light grey (1.79, user request): the other
        // buttons sit on the right.
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        topRow.addView(TextView(this).apply {
            text = if (versionName.isBlank()) "" else "V.$versionName"
            textSize = 12f
            setTextColor(fgGray)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 2.22: tapping the version opens the
            // version-history page (last 10 versions).
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, VersionHistoryActivity::class.java))
            }
        })
        // ADAPTIVE ENGINE (1.78): toggle + "!" explainer,
        // same small round shape as the other top
        // buttons. ON = solid green (the engine is
        // armed); OFF = the usual glass. The toggle
        // only flips a pref; every section checks it
        // itself, so OFF means exactly the old app.
        val engineOn = AdaptiveEngine.isEnabled(this@HomeActivity)
        // ADAPTIVE ENGINE (1.79): ✦ + "!" grouped in
        // one pill so the "!" is visibly the engine's
        // own explainer, not a stray button. The pill
        // ring is green while the engine is on.
        val enginePill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpPx(20).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dpPx(1), if (engineOn) Color.parseColor("#66BB6A")
                    else if (dark) Color.parseColor("#55FFFFFF") else Color.parseColor("#22000000"))
            }
            setPadding(dpPx(2), dpPx(2), dpPx(2), dpPx(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpPx(4), 0, dpPx(4), 0)
            }
        }
        fun pillCircle(text: String, textSize: Float, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                this.textSize = textSize
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000"))
                }
                minimumWidth = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                setTextColor(fg)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(dpPx(32), dpPx(32))
                setOnClickListener { onClick() }
            }
        }
        enginePill.addView(Button(this).apply {
            text = "✦"
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                if (engineOn) setColor(Color.parseColor("#4CAF50"))
                else setColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000"))
            }
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            setTextColor(if (engineOn) Color.WHITE else fg)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dpPx(32), dpPx(32))
            setOnClickListener {
                // Turning OFF reverts everything the
                // engine changed (1.79): memory and
                // reports are purged and any live
                // engine session is stopped, so the
                // app is exactly as before the engine.
                if (engineOn) AdaptiveEngine.disableCleanup(this@HomeActivity)
                AdaptiveEngine.setEnabled(this@HomeActivity, !engineOn)
                recreate()
            }
        })
        enginePill.addView(pillCircle("!", 14f) {
            val msg = TextView(this@HomeActivity).apply {
                text = AdaptiveEngine.explainText(this@HomeActivity)
                textSize = 14f
                setPadding(24, 16, 24, 16)
            }
            val scroll = ScrollView(this@HomeActivity).apply { addView(msg) }
            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle(AppLanguage.t(this@HomeActivity, "موتور هوشمند چیست؟", "What is the Smart Engine?"))
                .setView(scroll)
                .setPositiveButton(AppLanguage.t(this@HomeActivity, "باشه", "OK"), null)
                .show()
        })
        topRow.addView(enginePill)
        topRow.addView(miniCircle(if (dark) "☀" else "☾", 14f) {
            getSharedPreferences("renamer_settings", MODE_PRIVATE)
                .edit().putString("theme_mode", "").putBoolean("dark_mode", !dark).apply()
            recreate()
        })
        topRow.addView(miniCircle(if (AppLanguage.isEnglish(this@HomeActivity)) "EN" else "FA", 12f) {
            // 2.0: classy glass language sheet (replaces the
            // plain system list dialog).
            val dlg = android.app.Dialog(this@HomeActivity)
            val cardBg = if (dark) Color.parseColor("#1E1E1E") else Color.WHITE
            val cardStroke = if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")
            val selBg = if (dark) Color.parseColor("#1E3A24") else Color.parseColor("#E6F4EA")
            val selFg = if (dark) Color.parseColor("#A5D6A7") else Color.parseColor("#14532D")
            val card = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpPx(24).toFloat()
                    setColor(cardBg)
                    setStroke(dpPx(1), cardStroke)
                }
                setPadding(dpPx(24), dpPx(20), dpPx(24), dpPx(20))
            }
            card.addView(TextView(this@HomeActivity).apply {
                text = AppLanguage.t(this@HomeActivity, "زبان برنامه", "App language")
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(fg)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dpPx(12))
            })
            card.addView(View(this@HomeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpPx(1)
                )
                setBackgroundColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#EEEEEE"))
            })
            val isEn = AppLanguage.isEnglish(this@HomeActivity)
            fun langRow(label: String, selected: Boolean, lang: String): LinearLayout {
                val row = LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dpPx(14).toFloat()
                        setColor(if (selected) selBg else Color.TRANSPARENT)
                    }
                    setPadding(dpPx(16), dpPx(14), dpPx(16), dpPx(14))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dpPx(8), 0, 0) }
                }
                row.addView(TextView(this@HomeActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(if (selected) selFg else fg)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                row.addView(TextView(this@HomeActivity).apply {
                    text = if (selected) "✓" else ""
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(selFg)
                })
                row.setOnClickListener {
                    AppLanguage.set(this@HomeActivity, lang)
                    dlg.dismiss()
                    recreate()
                }
                return row
            }
            card.addView(langRow("فارسی", !isEn, "fa"))
            card.addView(langRow("English", isEn, "en"))
            dlg.setContentView(card)
            dlg.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            dlg.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.82).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dlg.show()
        })
        topRow.addView(miniCircle("⚙", 14f) {
            val s = Intent(this@HomeActivity, HomeSettingsActivity::class.java)
            s.putExtra(PinActivity.EXTRA_MODE, intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
            startActivity(s)
        })
        // 2.56: the central log button is now the
        // Monitoring button (same 32dp circle, drawn
        // pulse glyph instead of the 📋 character).
        // Hidden mode only, like the renamer/subs
        // tiles: monitoring names every section,
        // including hidden ones, so it must not
        // appear in public mode.
        if (isHidden) {
            topRow.addView(miniCircleIcon("monitor.png") {
                startActivity(Intent(this@HomeActivity, AppLogActivity::class.java))
            })
        }
        root.addView(topRow)
        // No title and no mode line at the top, at the user's request:
        // the page opens straight into the grid.
        root.addView(divider())

        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(gridContainer)
        rebuildHomeGrid()

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
        // 2.17: lock & exit lives in a fixed bottom bar,
        // not inside the scroll, so it always sits at
        // the bottom of the page. Same action (finish).
        // 2.22: the bar paints the page background
        // instead of staying transparent, so the strip
        // behind the button is exactly the same white
        // (or dark) as the options area above it —
        // no darker frame anymore.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(20))
        }
        // 2.22: much more professional — a drawn
        // padlock glyph (no emoji), clean white
        // capsule with a soft stroke and shadow in
        // light mode, matching dark treatment.
        barRow.addView(Button(this).apply {
            text = AppLanguage.t(this@HomeActivity, "قفل و خروج", "Lock & exit")
            textSize = 14f
            isAllCaps = false
            setTextColor(fg)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpPx(28).toFloat()
                setColor(if (dark) Color.parseColor("#1E1E1E") else Color.WHITE)
                setStroke(dpPx(1), if (dark) Color.parseColor("#55FFFFFF") else Color.parseColor("#E0E0E0"))
            }
            elevation = 4f
            try {
                val d = resources.displayMetrics.density
                val s = (18 * d).toInt()
                val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = fg
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 2f * d
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }
                cv.drawArc(android.graphics.RectF(s * 0.28f, s * 0.06f, s * 0.72f, s * 0.52f), 180f, 180f, false, pt)
                pt.style = android.graphics.Paint.Style.FILL
                cv.drawRoundRect(android.graphics.RectF(s * 0.16f, s * 0.46f, s * 0.84f, s * 0.94f), 3f * d, 3f * d, pt)
                pt.color = if (dark) Color.parseColor("#1E1E1E") else Color.WHITE
                cv.drawCircle(s * 0.5f, s * 0.66f, 2f * d, pt)
                val glyph = android.graphics.drawable.BitmapDrawable(resources, bmp)
                setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
                compoundDrawablePadding = dpPx(8)
            } catch (_: Exception) {}
            setPadding(dpPx(22), dpPx(12), dpPx(22), dpPx(12))
            setOnClickListener { finish() }
        })
        bottomBar.addView(barRow)
        // 2.18: lift the bar above the phone's own
        // navigation buttons (back/home/recents) by
        // the system nav inset, so it never hides
        // behind them. The insets are passed on, so
        // the scroll keeps its own behaviour.
        bottomBar.setOnApplyWindowInsetsListener { _, insets ->
            barRow.setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(16) + insets.systemWindowInsetBottom)
            insets
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        outer.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(bottomBar)
        setContentView(outer)
    }

    // reorder saved on the sort page is visible at
    // once instead of only after a full recreate.
    override fun onResume() {
        super.onResume()
        if (::gridContainer.isInitialized) {
            val orderNow = try {
                getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("home_order", "") ?: ""
            } catch (_: Exception) { "" }
            rebuildHomeGrid()
            if (homeGridBuilt && orderNow != lastHomeOrder) {
                try {
                                    } catch (_: Exception) {}
            }
            lastHomeOrder = orderNow
            homeGridBuilt = true
        }
    }

    /** The home grid, extracted from onCreate unchanged:
     *  same items, same saved order, same three-per-row
     *  layout, same icons and labels. Only the target
     *  container moved from the page root into
     *  gridContainer so onResume can rebuild it. */
    private fun rebuildHomeGrid() {
        val items = mutableListOf<MenuItem>()
        // Public sections: v2ray, WireGuard, OpenVPN and emergency connect.
        // SlipNet was removed at the user's request.
        items += MenuItem("v2ray", "v2ray", "vpn.png", VpnActivity::class.java)
        items += MenuItem("wireguard", "وایرگارد و امنزیا", "wg.png", WireGuardActivity::class.java)
        items += MenuItem("openvpn", "OpenVPN", "ovpn.png", OpenVpnActivity::class.java)
        items += MenuItem("emergency", AppLanguage.t(this, "اتصال اضطراری", "Emergency connect"), "emergency.png", EmergencyActivity::class.java)
        items += MenuItem("smart", AppLanguage.t(this, "اتصال هوشمند", "Smart Connect"), "smart.png", SmartConnectActivity::class.java)
        items += MenuItem("chain", AppLanguage.t(this, "زنجیره دوتایی", "Double chain"), "chain.png", ChainActivity::class.java)
        items += MenuItem("hydra", AppLanguage.t(this, "هیدرا", "Hydra"), "hydra.png", HydraActivity::class.java)
        items += MenuItem("cloudflare", AppLanguage.t(this, "کلودفلر", "Cloudflare"), "cf.png", CloudflareActivity::class.java)
        items += MenuItem("secretlang", AppLanguage.t(this, "زبان بیگانه", "Alien Language"), "alien.png", SecretLanguageActivity::class.java)
        if (gridIsHidden) {
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
        // Grid: tiles per row and tile size come
        // from the Sort page's layout settings
        // (HomeLayout, default 3 per row, 100%).
        // A thin light line separates each row.
        // Icons and labels are smaller than the old
        // two-column grid so cells fit a phone
        // width; long names wrap to a second line
        // instead of clipping, never more.
        val dp = resources.displayMetrics.density
        val cols = HomeLayout.cols(this)
        val scale = HomeLayout.scale(this)
        val iconDp = HomeLayout.iconDp(cols, scale, HomeLayout.screenWidthDp(this))
        val labelSp = HomeLayout.textSp(cols, scale)
        gridContainer.removeAllViews()
        for (rowStart in items.indices step cols) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0 until cols) {
                val idx = rowStart + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 16, 8, 16)
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
                                    (iconDp * dp).toInt(), (iconDp * dp).toInt()
                                )
                            })
                        } catch (_: Exception) {}
                    }
                    cell.addView(TextView(this).apply {
                        text = item.name; textSize = labelSp; gravity = Gravity.CENTER
                        setTextColor(gridFg)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, 12, 0, 0)
                    })
                    cell.setOnClickListener {
                        startActivity(Intent(this@HomeActivity, item.target))
                    }
                }
                row.addView(cell)
            }
            gridContainer.addView(row)
            gridContainer.addView(divider())
        }
    }
}
