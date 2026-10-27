package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray

/** Reorder the Home (apps) page sections.
 *  Each row has up/down arrows; the order is kept in
 *  memory until Save is tapped, and only then written
 *  to prefs "renamer_settings" key "home_order" (JSON
 *  array of section keys). Back discards unsaved
 *  changes. The Home page itself is wired by the
 *  parent to read "home_order". */
class ReorderActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "renamer_settings"
        const val KEY_ORDER = "home_order"
        val DEFAULT_ORDER = listOf("v2ray", "wireguard", "openvpn", "emergency", "smart", "chain", "hydra", "renamer", "subs")
    }

    private fun sectionIcon(key: String): String = when (key) {
        "v2ray" -> "vpn.png"
        "wireguard" -> "wg.png"
        "openvpn" -> "ovpn.png"
        "emergency" -> "vpn.png"
        "smart" -> "smart.png"
        "chain" -> "chain.png"
        "hydra" -> "hydra.png"
        "renamer" -> "v2ray.png"
        "subs" -> "sub.png"
        else -> "vpn.png"
    }

    private fun sectionName(key: String): String = when (key) {
        "v2ray" -> "v2ray"
        "wireguard" -> L("وایرگارد", "WireGuard")
        "openvpn" -> L("اوپن‌وی‌پی‌ان", "OpenVPN")
        "emergency" -> L("اتصال اضطراری", "Emergency")
        "smart" -> L("اتصال هوشمند", "Smart Connect")
        "chain" -> L("زنجیره دوتایی", "Double chain")
        "hydra" -> L("هیدرا", "Hydra")
        "renamer" -> L("تغییر نام کانفیگ", "Rename configs")
        "subs" -> L("اشتراک‌ها و تست پینگ", "Subscriptions & ping test")
        else -> key
    }

    private val order = mutableListOf<String>()
    private lateinit var listContainer: LinearLayout
    private lateinit var saveBtn: Button

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

    private fun rebuildList() {
        listContainer.removeAllViews()
        for ((index, key) in order.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                // Long-press and drag this row onto another
                // row to move it there. The drag carries
                // the source index; rows only highlight
                // during the drag and the list is rebuilt
                // once, on drop, so the drag is never
                // interrupted by a rebuild.
                setOnLongClickListener { v ->
                    val data = android.content.ClipData.newPlainText("index", index.toString())
                    v.startDragAndDrop(data, View.DragShadowBuilder(v), null, 0)
                    true
                }
                setOnDragListener { v, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> true
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                            v.setBackgroundColor(0xFFE3F2FD.toInt())
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_EXITED -> {
                            v.setBackgroundColor(0x00000000)
                            true
                        }
                        android.view.DragEvent.ACTION_DROP -> {
                            v.setBackgroundColor(0x00000000)
                            val from = try {
                                event.clipData.getItemAt(0).text.toString().toInt()
                            } catch (_: Exception) { -1 }
                            if (from in order.indices && from != index) {
                                val item = order.removeAt(from)
                                // Insert at the target row's
                                // position, so the dragged item
                                // ends up exactly where it was
                                // dropped, in both directions.
                                order.add(index.coerceAtMost(order.size), item)
                                rebuildList()
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_ENDED -> {
                            v.setBackgroundColor(0x00000000)
                            true
                        }
                        else -> true
                    }
                }
            }
            try {
                val bmp = android.graphics.BitmapFactory.decodeStream(assets.open(sectionIcon(key)))
                row.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            } catch (_: Exception) {}
            row.addView(TextView(this).apply {
                text = sectionName(key); textSize = 17f
                setTextColor(Theme.fg(this@ReorderActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "≡"; textSize = 22f
                setTextColor(Color.GRAY)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            listContainer.addView(row)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setBackgroundColor(Theme.divider(this@ReorderActivity))
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        order.clear()
        order.addAll(loadOrder())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Theme.bg(this@ReorderActivity))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = L("مرتب‌سازی برنامه‌ها", "Sort apps"); textSize = 22f
            setTextColor(Theme.fg(this@ReorderActivity))
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = L("روی آیکون هر برنامه نگه دار و با دست آن را بکش روی ردیفی که می‌خواهی، بعد «ذخیره» را بزن.", "Hold an app's icon and drag it onto the row you want, then tap Save.")
            textSize = 13f
            setTextColor(Theme.subFg(this@ReorderActivity))
            setPadding(0, 0, 0, dp(12))
        })

        // Scrollable list; the Save button lives below it,
        // outside the scroll, so it is always visible.
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll)
        rebuildList()

        saveBtn = Button(this).apply {
            text = L("ذخیره", "Save")
            textSize = 17f
            setPadding(0, dp(20), 0, dp(20))
            setOnClickListener {
                saveOrder()
                text = L("ذخیره شد ✓", "Saved ✓")
                Toast.makeText(this@ReorderActivity, L("ترتیب ذخیره شد", "Order saved"), Toast.LENGTH_SHORT).show()
                // Return the button to its normal label after
                // a short delay, so a further reorder + save
                // still reads as a save.
                postDelayed({ text = L("ذخیره", "Save") }, 2000)
            }
        }
        root.addView(saveBtn)
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setPadding(0, dp(16), 0, dp(16))
            // Back discards: nothing is written here, so any
            // unsaved arrow moves are simply lost.
            setOnClickListener { finish() }
        })
        setContentView(root)
        root.fitsSystemWindows = true
    }
}
