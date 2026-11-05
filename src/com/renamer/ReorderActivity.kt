package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray

/** Reorder the Home (apps) page sections — laid out
 *  like Home, with the layout itself adjustable
 *  here: tiles per row (2–5) and a size slider,
 *  both previewed live in this very grid and
 *  applied to Home on Save. A tile is tapped to
 *  select it (green glass ring), then the
 *  ‹ قبلی / بعدی › glass buttons move it, or it
 *  is long-pressed and dragged onto another tile.
 *  Order AND layout are kept in memory until Save
 *  is tapped, and only then written to prefs
 *  "renamer_settings" (keys "home_order" plus
 *  HomeLayout's cols/scale). Back discards
 *  unsaved changes. The Home page itself reads
 *  the same prefs. */
class ReorderActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "renamer_settings"
        const val KEY_ORDER = "home_order"
        val DEFAULT_ORDER = listOf("v2ray", "wireguard", "openvpn", "emergency", "smart", "chain", "hydra", "cloudflare", "secretlang", "renamer", "subs")
    }

    private fun sectionIcon(key: String): String = when (key) {
        "v2ray" -> "vpn.png"
        "wireguard" -> "wg.png"
        "openvpn" -> "ovpn.png"
        "emergency" -> "emergency.png"
        "smart" -> "smart.png"
        "chain" -> "chain.png"
        "hydra" -> "hydra.png"
        "cloudflare" -> "cf.png"
        "secretlang" -> "alien.png"
        "renamer" -> "v2ray.png"
        "subs" -> "sub.png"
        else -> "vpn.png"
    }

    private fun sectionName(key: String): String = when (key) {
        "v2ray" -> "v2ray"
        "wireguard" -> L("وایرگارد و امنزیا", "WireGuard & Amnezia")
        "openvpn" -> L("اوپن‌وی‌پی‌ان", "OpenVPN")
        "emergency" -> L("اتصال اضطراری", "Emergency")
        "smart" -> L("اتصال هوشمند", "Smart Connect")
        "chain" -> L("زنجیره دوتایی", "Double chain")
        "hydra" -> L("هیدرا", "Hydra")
        "cloudflare" -> L("کلودفلر", "Cloudflare")
        // through to `else` and show the raw key.
        "secretlang" -> L("زبان بیگانه", "Alien Language")
        "renamer" -> L("تغییر نام کانفیگ", "Rename configs")
        "subs" -> L("اشتراک‌ها و تست پینگ", "Subscriptions & ping test")
        else -> key
    }

    private val order = mutableListOf<String>()
    private lateinit var gridContainer: LinearLayout
    private lateinit var moveHint: TextView
    private var selected: String? = null

    // Layout draft (unsaved until Save): tiles per
    // row + tile size. The grid below IS the
    // preview — every change rebuilds it at once,
    // so what is seen here is what Home shows.
    private var layoutCols = 3
    private var layoutScale = 1.0f
    private lateinit var layoutValueText: TextView
    private lateinit var colsBox: TextView

    private fun layoutValueLabel(): String {
        val pct = (layoutScale * 100).toInt()
        return L("$layoutCols در ردیف • $pct٪", "$layoutCols per row • $pct%")
    }

    private fun refreshLayoutControls() {
        if (!::layoutValueText.isInitialized) return
        layoutValueText.text = layoutValueLabel()
        if (::colsBox.isInitialized) colsBox.text = "$layoutCols ▾"
    }

    /** Small popup (anchored, never fullscreen)
     *  with the per-row counts 2–5. */
    private fun showColsPopup(anchor: View) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#FFE0E0E0"))
            }
            elevation = 8f
        }
        for (n in 2..5) {
            list.addView(TextView(this).apply {
                text = n.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(7), dp(18), dp(7))
                setTextColor(if (n == layoutCols) Color.parseColor("#FF00695C") else Color.parseColor("#FF1A237E"))
                if (n == layoutCols) setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    layoutCols = n
                    rebuildGrid()
                    refreshLayoutControls()
                    colsPopup?.dismiss()
                }
            })
            if (n < 5) list.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#FFEEEEEE"))
            })
        }
        colsPopup = PopupWindow(list,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            showAsDropDown(anchor)
        }
    }

    private var colsPopup: PopupWindow? = null

    // Decoded icons, cached: rebuildGrid used to decode every
    // asset on every tap, which made the page feel sluggish.
    private val iconCache = mutableMapOf<String, android.graphics.Bitmap?>()
    private fun iconFor(key: String): android.graphics.Bitmap? {
        if (!iconCache.containsKey(key)) {
            iconCache[key] = try {
                android.graphics.BitmapFactory.decodeStream(assets.open(sectionIcon(key)))
            } catch (_: Exception) { null }
        }
        return iconCache[key]
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadOrder(): MutableList<String> {
        val result = mutableListOf<String>()
        try {
            val raw = prefs().getString(KEY_ORDER, null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val k = arr.optString(i)
                    if (k in DEFAULT_ORDER && k !in result) result += k
                }
            }
        } catch (_: Exception) {}
        // Any section missing from a stored order (e.g. a
        // section added later) goes at the end, so nothing
        // ever disappears from the Home page.
        for (k in DEFAULT_ORDER) if (k !in result) result += k
        return result
    }

    private fun saveOrder() {
        val arr = JSONArray()
        for (k in order) arr.put(k)
        prefs().edit().putString(KEY_ORDER, arr.toString()).apply()
    }

    /** Glass tile card: white normally (dark tile in
     *  dark mode, so the theme-coloured label stays
     *  readable), soft green when selected. The drag
     *  highlight is a third state, restored from these
     *  two on drag exit. The selected tile is unchanged. */
    private fun tileBg(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        val dark = Theme.isDark(this@ReorderActivity)
        setColor(if (selected) Color.parseColor("#E8F5E9")
            else if (dark) Color.parseColor("#1E1E1E") else Color.WHITE)
        setStroke(dp(1), if (selected) Color.parseColor("#66BB6A")
            else if (dark) Color.parseColor("#444444") else Color.parseColor("#E0E0E0"))
    }

    private fun dragBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        val dark = Theme.isDark(this@ReorderActivity)
        setColor(if (dark) Color.parseColor("#263238") else Color.parseColor("#E3F2FD"))
        setStroke(dp(1), if (dark) Color.parseColor("#546E7A") else Color.parseColor("#90CAF9"))
    }

    private fun updateMoveHint() {
        val key = selected
        moveHint.text = if (key == null) {
            L("روی یک برنامه بزن تا انتخاب شود.", "Tap an app to select it.")
        } else {
            L("انتخاب شده: ", "Selected: ") + sectionName(key)
        }
    }

    private fun moveSelected(delta: Int) {
        val key = selected ?: run {
            Toast.makeText(this, L("اول یک برنامه را بزن تا انتخاب شود.", "Tap an app first to select it."), Toast.LENGTH_SHORT).show()
            return
        }
        val i = order.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order.removeAt(i)
        order.add(j, key)
        rebuildGrid()
    }

    private fun rebuildGrid() {
        val iconDp = HomeLayout.iconDp(layoutCols, layoutScale, HomeLayout.screenWidthDp(this))
        val labelSp = HomeLayout.textSp(layoutCols, layoutScale)
        gridContainer.removeAllViews()
        for (rowStart in order.indices step layoutCols) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0 until layoutCols) {
                val idx = rowStart + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(8, 16, 8, 16)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(6, 6, 6, 6)
                    }
                }
                if (idx < order.size) {
                    val key = order[idx]
                    val isSel = key == selected
                    cell.background = tileBg(isSel)
                    cell.elevation = if (isSel) 6f else 2f
                    cell.addView(TextView(this).apply {
                        text = (idx + 1).toString(); textSize = 11f
                        setTextColor(if (isSel) Color.parseColor("#2E7D32") else Theme.subFg(this@ReorderActivity))
                        gravity = Gravity.CENTER
                    })
                    val bmp = iconFor(key)
                    if (bmp != null) {
                        cell.addView(ImageView(this).apply {
                            setImageBitmap(bmp)
                            layoutParams = LinearLayout.LayoutParams(dp(iconDp), dp(iconDp))
                        })
                    }
                    cell.addView(TextView(this).apply {
                        text = sectionName(key); textSize = labelSp; gravity = Gravity.CENTER
                        setTextColor(Theme.fg(this@ReorderActivity))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, 12, 0, 0)
                    })
                    cell.setOnClickListener {
                        selected = key
                        rebuildGrid()
                        updateMoveHint()
                    }
                    // Long-press and drag this tile onto
                    // another tile to move it there. The
                    // drag carries the source index; tiles
                    // only highlight during the drag and
                    // the grid is rebuilt once, on drop,
                    // so the drag is never interrupted
                    // by a rebuild.
                    cell.setOnLongClickListener { v ->
                        val data = android.content.ClipData.newPlainText("index", idx.toString())
                        v.startDragAndDrop(data, View.DragShadowBuilder(v), null, 0)
                        true
                    }
                    cell.setOnDragListener { v, event ->
                        when (event.action) {
                            android.view.DragEvent.ACTION_DRAG_STARTED -> true
                            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                                v.background = dragBg()
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                                v.background = tileBg(isSel)
                                true
                            }
                            android.view.DragEvent.ACTION_DROP -> {
                                val from = try {
                                    event.clipData.getItemAt(0).text.toString().toInt()
                                } catch (_: Exception) { -1 }
                                if (from in order.indices && from != idx) {
                                    val item = order.removeAt(from)
                                    // Insert at the target tile's
                                    // position, so the dragged item
                                    // ends up exactly where it was
                                    // dropped, in both directions.
                                    order.add(idx.coerceAtMost(order.size), item)
                                    rebuildGrid()
                                } else {
                                    v.background = tileBg(isSel)
                                }
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                                v.background = tileBg(isSel)
                                true
                            }
                            else -> true
                        }
                    }
                } else {
                    // Empty cell keeps the last row aligned.
                    cell.visibility = View.INVISIBLE
                }
                row.addView(cell)
            }
            gridContainer.addView(row)
            gridContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 12, 0, 12) }
                setBackgroundColor(Theme.divider(this@ReorderActivity))
            })
        }
    }

    /** Modern glass pill button (white glass, like the
     *  other glass controls in the app). */
    private fun glassPill(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        minimumWidth = 0
        minimumHeight = 0
        setTextColor(Color.parseColor("#1A237E"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor("#E0E0E0"))
        }
        elevation = 2f
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        order.clear()
        order.addAll(loadOrder())
        layoutCols = HomeLayout.cols(this)
        layoutScale = HomeLayout.scale(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Theme.bg(this@ReorderActivity))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = L("تنظیمات صفحه اصلی", "Home settings"); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@ReorderActivity))
            setPadding(0, 0, 0, dp(12))
        })

        // Layout card (glass): tiles per row via a
        // small dropdown box (default 3) + a size
        // slider. Every change rebuilds the grid
        // below at once, so the grid IS the preview
        // — nothing is saved until Save. In Persian
        // the card reads right-to-left, so labels sit
        // on the right as they should.
        val layoutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#FFE0E0E0"))
            }
            elevation = 3f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        }
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.59: explicit row direction — the
            // card's RTL did not reach the rows
            // on device (00:34 screenshot: the
            // labels stayed on the left).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("چیدمان صفحه اصلی", "Home layout")
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@ReorderActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            layoutValueText = TextView(this@ReorderActivity).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@ReorderActivity))
            }
            addView(layoutValueText)
        })
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
            // 2.59: explicit row direction (see
            // header row note above).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("در هر ردیف:", "Per row:")
                textSize = 14f
                setTextColor(Theme.subFg(this@ReorderActivity))
                setPaddingRelative(0, 0, dp(10), 0)
            })
            colsBox = TextView(this@ReorderActivity).apply {
                text = "$layoutCols ▾"
                // 2.59: a TextView pill, not a
                // Button — Button's own default
                // metrics kept inflating this box
                // far past text+padding on device
                // (the 00:34 screenshot). Sized to
                // ~60% of the old on-screen box:
                // 11sp, 10/4dp padding, 8dp corners.
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FF00695C"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FFE0F2F1"))
                    setStroke(dp(1), Color.parseColor("#FF80CBC4"))
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { showColsPopup(this) }
            }
            addView(colsBox)
        })
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.59: explicit row direction (see
            // header row note above).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("اندازه:", "Size:")
                textSize = 13f
                setTextColor(Theme.subFg(this@ReorderActivity))
                setPaddingRelative(0, 0, dp(8), 0)
            })
            addView(android.widget.SeekBar(this@ReorderActivity).apply {
                max = 70
                progress = ((layoutScale * 100).toInt() - 70).coerceIn(0, 70)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        layoutScale = (70 + p) / 100f
                        rebuildGrid()
                        refreshLayoutControls()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            })
        })
        layoutCard.addView(TextView(this).apply {
            text = L("چیدمان انتخابی روی صفحه اصلی هم اعمال می‌شود؛ تا «ذخیره» نزنی ثبت نمی‌شود.",
                "The chosen layout applies to Home too; it is saved only when you tap Save.")
            textSize = 12f
            setTextColor(Theme.subFg(this@ReorderActivity))
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(layoutCard)
        refreshLayoutControls()

        // Scrollable grid; the controls live below it,
        // outside the scroll, so they are always visible.
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(gridContainer)
        root.addView(scroll)
        rebuildGrid()
        try {
                    } catch (_: Exception) {}

        // Move controls: two glass pills + the selection line.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        controls.addView(glassPill(L("‹ قبلی", "‹ Previous")) { moveSelected(-1) })
        moveHint = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.subFg(this@ReorderActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        }
        controls.addView(moveHint)
        controls.addView(glassPill(L("بعدی ›", "Next ›")) { moveSelected(1) })
        root.addView(controls)
        updateMoveHint()

        // 2.46: smaller, centered pill (was a
        // full-width tall bar).
        val saveBtn = Button(this).apply {
            text = L("ذخیره", "Save")
            textSize = 15f
            isAllCaps = false
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(
                    Color.parseColor("#0F766E"), Color.parseColor("#26A69A"))
            }
            elevation = 4f
            setPadding(dp(32), dp(10), dp(32), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); gravity = android.view.Gravity.CENTER_HORIZONTAL }
            setOnClickListener {
                saveOrder()
                HomeLayout.save(this@ReorderActivity, layoutCols, layoutScale)
                text = L("ذخیره شد ✓", "Saved ✓")
                Toast.makeText(this@ReorderActivity, L("ترتیب و چیدمان ذخیره شد", "Order and layout saved"), Toast.LENGTH_SHORT).show()
                // Return the button to its normal label after
                // a short delay, so a further reorder + save
                // still reads as a save.
                postDelayed({ text = L("ذخیره", "Save") }, 2000)
            }
        }
        root.addView(saveBtn)
        setContentView(root)
        root.fitsSystemWindows = true
    }
}
