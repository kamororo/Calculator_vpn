package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Icon / button customize page (2.34), opened from the Studio's
 *  single glass buttons. The wheel (and, for buttons, the shape
 *  grid) edits a working copy; the full widget preview below it
 *  updates live; Save writes the draft and returns. Back without
 *  saving discards the changes. */
class WidgetCustomizeActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)

    private lateinit var root: LinearLayout
    private lateinit var previewBox: LinearLayout

    private var mode = "icon"
    private var section = ""
    private var style = "glass"
    private var template = ""
    private var showSpeeds = true
    private var showStatus = true
    private var showIcon = true
    private var showMethod = true
    private var showUsage = false
    private var showTime = false
    private var showChart = false
    private var widgetSize = "medium"
    private var bgColor = -1
    private var opacity = -1
    private var corner = -1
    private var textScale = 100
    private var iconBg = Color.parseColor("#0F766E")
    private var btnShape = "ring"
    private var btnColor = Color.parseColor("#39E58C")
    private var titleText = ""

    // MLM's four button shapes; legacy keys still render.
    private val shapes = listOf("ring", "pill", "solid", "glow")
    private val legacyShapes = listOf("circle", "rounded", "diamond", "hexagon",
        "glowring", "wide")
    private val shapeCells = mutableListOf<Triple<String, LinearLayout, ImageView>>()

    private fun shapeName(key: String): String = when (key) {
        "ring" -> L("حلقه", "Ring")
        "pill" -> L("دکمه کشیده", "Pill")
        "solid" -> L("دایره پر", "Solid")
        "glow" -> L("حلقه درخشان", "Glow")
        "rounded" -> L("مربع گرد", "Rounded square")
        "diamond" -> L("لوزی", "Diamond")
        "hexagon" -> L("شش‌ضلعی", "Hexagon")
        "glowring" -> L("حلقه درخشان", "Glowing ring")
        "wide" -> L("دکمه کشیده", "Stretched button")
        else -> L("دایره", "Circle")
    }

    private fun loadDraft() {
        try {
            val raw = WidgetStyle.getDraft(this) ?: return
            val o = org.json.JSONObject(raw)
            val s = o.optString("section")
            if (s.isNotBlank()) section = s
            val st = o.optString("style")
            if (st.isNotBlank()) style = st
            val tpl = o.optString("template")
            if (tpl in WidgetStyle.PRESET_KEYS) template = tpl
            showSpeeds = o.optBoolean("showspeeds", true)
            showStatus = o.optBoolean("showstatus", true)
            showIcon = o.optBoolean("showicon", true)
            showMethod = o.optBoolean("showmethod", true)
            showUsage = o.optBoolean("showusage", false)
            showTime = o.optBoolean("showtime", false)
            showChart = o.optBoolean("showchart", false)
            bgColor = o.optInt("bgcolor", -1)
            opacity = o.optInt("opacity", -1)
            corner = o.optInt("corner", -1)
            textScale = o.optInt("textscale", 100)
            val sz = o.optString("size")
            if (sz in listOf("small", "medium", "large")) widgetSize = sz
            val ib = o.optInt("iconbg", -1)
            if (ib != -1) iconBg = ib
            val bs = o.optString("btnshape")
            if (bs in shapes || bs in legacyShapes) btnShape = bs
            val bc = o.optInt("btncolor", -1)
            if (bc != -1) btnColor = bc
            titleText = o.optString("title")
        } catch (_: Exception) {}
    }

    private fun saveDraft() {
        try {
            val o = org.json.JSONObject()
            o.put("section", section)
            o.put("style", style)
            o.put("template", template)
            o.put("showspeeds", showSpeeds)
            o.put("showstatus", showStatus)
            o.put("showicon", showIcon)
            o.put("showmethod", showMethod)
            o.put("showusage", showUsage)
            o.put("showtime", showTime)
            o.put("showchart", showChart)
            o.put("bgcolor", bgColor)
            o.put("opacity", opacity)
            o.put("corner", corner)
            o.put("textscale", textScale)
            o.put("size", widgetSize)
            o.put("iconbg", iconBg)
            o.put("btnshape", btnShape)
            o.put("btncolor", btnColor)
            o.put("title", titleText)
            // Chat fields AND connmode are preserved from the
            // existing draft (dropping connmode was a 2.35 bug).
            try {
                val old = org.json.JSONObject(WidgetStyle.getDraft(this) ?: "{}")
                o.put("chatmode", old.optString("chatmode"))
                o.put("chatid", old.optString("chatid"))
                o.put("connmode", old.optString("connmode"))
            } catch (_: Exception) {}
            WidgetStyle.setDraft(this, o.toString())
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "icon"
        loadDraft()
        val scroll = ScrollView(this).apply { fitsSystemWindows = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#F2F5F9"), Color.parseColor("#E8EAF6"))
            }
        }
        scroll.addView(root)
        setContentView(scroll)
        buildPage()
    }

    private fun label(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, dp(16), 0, dp(6))
        })
    }

    private fun refreshShapeCells() {
        for ((shape, cell, img) in shapeCells) {
            val selected = shape == btnShape
            cell.background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) Color.parseColor("#E8F5E9") else Color.WHITE)
                setStroke(dp(1), if (selected) Color.parseColor("#2E9E5B")
                    else Color.parseColor("#E0E0E0"))
            }
            img.setImageBitmap(
                SectionWidgetProvider.buttonBitmap(this, shape, btnColor, 192))
        }
    }

    private fun refreshPreview() {
        if (!::previewBox.isInitialized) return
        previewBox.removeAllViews()
        val view = layoutInflater.inflate(WidgetPreview.layout(this, widgetSize), previewBox, false)
        WidgetPreview.populate(this, view, section, style, iconBg, btnShape, btnColor,
            titleText, template, -1, showSpeeds, showStatus, showIcon,
            showMethod, showUsage, showTime, showChart, widgetSize,
            btnColor, bgColor, opacity, corner, textScale)
        val previewH = when (widgetSize) { "small" -> 120; "large" -> 320; else -> 120 }
        previewBox.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(previewH)).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun buildPage() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = if (mode == "icon") L("آیکون ویجت", "Widget icon")
                else L("دکمه اتصال", "Connect button")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, 0, 0, dp(4))
        })

        if (mode == "button") {
            label(L("شکل دکمه", "Button shape"))
            shapeCells.clear()
            for (rowStart in shapes.indices step 3) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (col in 0 until 3) {
                    val idx = rowStart + col
                    if (idx >= shapes.size) break
                    val shape = shapes[idx]
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                        elevation = 2f
                        isClickable = true
                        setOnClickListener {
                            btnShape = shape
                            refreshShapeCells()
                            refreshPreview()
                        }
                    }
                    val img = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                    }
                    cell.addView(img)
                    cell.addView(TextView(this).apply {
                        text = shapeName(shape)
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    })
                    shapeCells += Triple(shape, cell, img)
                    row.addView(cell)
                }
                root.addView(row)
            }
            refreshShapeCells()
        }

        label(if (mode == "icon") L("رنگ دایره", "Circle colour")
            else L("رنگ دکمه", "Button colour"))
        val base = if (mode == "icon") iconBg else btnColor
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        var h = hsv[0]
        var s = hsv[1]
        var v = hsv[2]
        val wheel = ColorWheelView(this).apply {
            hue = h
            sat = s
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = Gravity.CENTER
            }
        }
        val bar = ValueBarView(this).apply {
            value = v
            baseColor = Color.HSVToColor(floatArrayOf(h, s, 1f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(8)
            }
        }
        fun apply() {
            val c = Color.HSVToColor(floatArrayOf(h, s, v))
            if (mode == "icon") iconBg = c else btnColor = c
            bar.baseColor = Color.HSVToColor(floatArrayOf(h, s, 1f))
            bar.invalidate()
            if (mode == "button") refreshShapeCells()
            refreshPreview()
        }
        wheel.onPick = { hh, ss -> h = hh; s = ss; apply() }
        bar.onPick = { vv -> v = vv; apply() }
        root.addView(wheel)
        root.addView(bar)

        label(L("پیش‌نمایش زنده", "Live preview"))
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#E8EAF6"), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(previewBox)
        refreshPreview()

        val save = Button(this).apply {
            text = L("ذخیره", "Save")
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(
                    Color.parseColor("#0F766E"), Color.parseColor("#26A69A"))
            }
            elevation = 4f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            }
            setOnClickListener {
                saveDraft()
                finish()
            }
        }
        root.addView(save)
    }
}
