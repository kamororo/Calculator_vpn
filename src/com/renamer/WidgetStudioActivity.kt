package com.renamer

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget Studio, "Home screen widgets" (2.34, tabs like
 *  MLM's studio since 2.36). Gated by design: until a section
 *  is picked in the first box, nothing else is shown. The
 *  section widgets get four tabs: template (MLM's eight
 *  presets), colour (accent/background swatches, opacity,
 *  corner, text scale), info (shown rows) and size (the
 *  widget to pin: small/medium/large). Icon and button type
 *  are single glass buttons that open dedicated customize
 *  pages with a full widget preview and a Save button. Every
 *  change on this page itself is saved to the draft at once,
 *  so the only action is "Build and add to home screen". */
class WidgetStudioActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)

    private lateinit var root: LinearLayout
    private lateinit var previewBox: LinearLayout

    // Editor state (draft-backed; survives leaving and returning).
    private var section = ""
    private var style = "modern"
    // MLM template (section widgets): one of the eight presets.
    // Blank/legacy style path is kept for chat widgets.
    private var template = "glass"
    // Preview state tab: 0 off, 1 connecting, 2 on.
    // Preview-only: never saved to the widget.
    private var previewState = 0
    // Studio tab: template / color / info / size.
    private var studioTab = "template"
    // Widget size to pin: small / medium / large.
    private var widgetSize = "medium"
    private var showSpeeds = true
    private var showStatus = true
    private var showIcon = true
    private var showMethod = true
    private var showUsage = false
    private var showTime = false
    private var showChart = false
    // Design overrides (-1 = the preset's value).
    private var bgColor = -1
    private var opacity = -1
    private var corner = -1
    private var textScale = 100
    // 2.35 connection mode: "" = nothing picked (behaves as "last"),
    // "last" = the last config, "any" = whichever config connects.
    private var connMode = ""
    private var iconBg = Color.parseColor("#0F766E")
    private var btnShape = "ring"
    private var btnColor = Color.parseColor("#39E58C")
    private var titleText = ""
    private var chatMode = "chat"
    private var chatId = ""

    // (it used to be 6; the rest could not be created or
    // configured through the studio at all).
    private val sections = listOf("v2ray", "wireguard", "openvpn",
        "emergency", "smart", "chain", "hydra", "cloudflare",
        "secretlang", "message", "messenger", "renamer", "subs")
    private val styles = listOf(
        "modern", "neon", "galaxy",
        "gold", "ocean", "sunset", "forest", "midnight")
    // MLM's four button shapes; legacy keys are still
    // accepted from old drafts (they keep rendering).
    private val shapes = listOf("ring", "pill", "solid", "glow")
    private val legacyShapes = listOf("circle", "rounded", "diamond", "hexagon",
        "glowring", "wide")

    private fun sectionName(key: String): String = when (key) {
        "wireguard" -> L("وایرگارد و امنزیا", "WireGuard & Amnezia")
        "openvpn" -> "OpenVPN"
        "emergency" -> L("اتصال اضطراری", "Emergency")
        "v2ray" -> "v2ray"
        "hydra" -> L("هایدرا", "Hydra")
        "messenger" -> L("مسنجر بیگانه", "Alien Messenger")
        // wording as SectionWidgetProvider's own names).
        "smart" -> L("اتصال هوشمند", "Smart Connect")
        "chain" -> L("زنجیره دوتایی", "Double Chain")
        "cloudflare" -> L("کلودفلر", "Cloudflare")
        "secretlang" -> L("زبان بیگانه", "Alien Language")
        "message" -> L("پیام بیگانه", "Alien Message")
        "renamer" -> L("تغییر نام", "Rename")
        "subs" -> L("اشتراک‌ها", "Subscriptions")
        else -> L("انتخاب بخش…", "Choose a section…")
    }

    private fun styleName(key: String): String = when (key) {
        "modern" -> L("مدرن گرادیان", "Modern gradient")
        "glass" -> L("شیشه‌ای", "Glass")
        "minimal" -> L("مینیمال", "Minimal")
        "neon" -> L("نئون", "Neon")
        "galaxy" -> L("کهکشانی", "Galaxy")
        "gold" -> L("طلایی", "Gold")
        "ocean" -> L("اقیانوسی", "Ocean")
        "sunset" -> L("غروب", "Sunset")
        "forest" -> L("جنگلی", "Forest")
        "midnight" -> L("نیمه‌شب", "Midnight")
        else -> key
    }

    private fun templateName(key: String): String = when (key) {
        "glass" -> L("شیشه‌ای", "Glass")
        "minimal" -> L("مینیمال", "Minimal")
        "neon" -> L("نئونی", "Neon")
        "colorful" -> L("رنگارنگ", "Colorful")
        "compact" -> L("جمع‌وجور", "Compact")
        "dark_pro" -> L("تیرهٔ حرفه‌ای", "Dark Pro")
        "ios" -> L("سبک iOS", "iOS Style")
        "wave" -> L("موج", "Wave")
        else -> key
    }

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

    private fun defaultTitle(): String =
        if (section == "messenger") {
            if (chatMode == "receiver") L("پیام بیگانه", "Alien Message")
            else AlienChats.find(this, chatId)?.partnerName?.ifBlank { null }
                ?: L("گفتگو", "Chat")
        } else sectionName(section)

    private fun shownTitle(): String =
        if (titleText.isBlank()) defaultTitle() else titleText

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
            o.put("connmode", connMode)
            o.put("iconbg", iconBg)
            o.put("btnshape", btnShape)
            o.put("btncolor", btnColor)
            o.put("title", titleText)
            o.put("chatmode", chatMode)
            o.put("chatid", chatId)
            WidgetStyle.setDraft(this, o.toString())
        } catch (_: Exception) {}
    }

    private fun loadDraft() {
        try {
            val raw = WidgetStyle.getDraft(this) ?: return
            val o = org.json.JSONObject(raw)
            val s = o.optString("section")
            if (s in sections) section = s
            val st = o.optString("style")
            if (st in styles) style = st
            val connm = o.optString("connmode")
            if (connm == "last" || connm == "any") connMode = connm
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
            val cm = o.optString("chatmode")
            if (cm == "receiver" || cm == "chat") chatMode = cm
            chatId = o.optString("chatid")
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        loadDraft()
        val pre = intent.getStringExtra("preselect_chat_id")
        if (!pre.isNullOrBlank()) {
            section = "messenger"
            chatMode = "chat"
            chatId = pre
            titleText = ""
            saveDraft()
        }
        buildPage()
        try {
                    } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // A customize page may have saved new icon/button values.
        loadDraft()
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

    private fun glassButton(text: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = 2f
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { onClick() }
        })
    }

    private fun glassRow(preview: View, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        preview.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            setMargins(0, 0, dp(12), 0)
        }
        row.addView(preview)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.GRAY)
        })
        root.addView(row)
    }

    private class DialogRow(
        val icon: Bitmap?, val bgRes: Int?, val label: String, val selected: Boolean)

    /** Professional glass list dialog: white rounded card, a
     *  preview (icon or style swatch) per row, green check on
     *  the selected row. Replaces the plain system list. */
    private fun glassListDialog(title: String, rows: List<DialogRow>, onPick: (Int) -> Unit) {
        val dlg = Dialog(this)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = 8f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        // 2.61: the section list (11+ rows) grew taller
        // than the screen and the dialog had no scroll,
        // so bottom rows were cut off and unreachable.
        // Rows now live in a ScrollView capped at 70%
        // of the screen height; short lists (like the
        // style picker) stay compact, because the cap
        // only binds when the estimate exceeds it.
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, r) in rows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (r.selected) background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#E8F5E9"))
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener {
                    dlg.dismiss()
                    onPick(i)
                    saveDraft()
                    buildPage()
                }
            }
            if (r.icon != null) {
                row.addView(ImageView(this).apply {
                    setImageBitmap(r.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            } else if (r.bgRes != null) {
                row.addView(View(this).apply {
                    setBackgroundResource(r.bgRes)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(32)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            }
            row.addView(TextView(this).apply {
                text = r.label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (r.selected) row.addView(TextView(this).apply {
                text = "✓"
                textSize = 18f
                setTextColor(Color.parseColor("#2E9E5B"))
            })
            rowsBox.addView(row)
        }
        val screenH = resources.displayMetrics.heightPixels
        val estH = rows.size * dp(64) + dp(48)
        val capH = (screenH * 0.7).toInt()
        box.addView(ScrollView(this).apply {
            addView(rowsBox)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (estH > capH) capH else LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        dlg.setContentView(box)
        dlg.show()
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** A labelled slider card: the value label updates in
     *  place and the preview refreshes live (no page rebuild,
     *  so the drag is not interrupted). */
    private fun sliderRow(labelText: String, min: Int, max: Int, value: Int,
        onChange: (Int) -> Unit): LinearLayout {
        val valueView = TextView(this).apply {
            text = "$value"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + p
                    valueView.text = "$v"
                    onChange(v)
                    saveDraft()
                    refreshPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            addView(LinearLayout(this@WidgetStudioActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@WidgetStudioActivity).apply {
                    text = labelText
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(valueView)
            })
            addView(bar)
        }
    }

    private fun buildPage() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = L("ویجت صفحه اصلی", "Home screen widgets")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, 0, 0, dp(4))
        })

        // ---- section: the gate. Nothing else shows until picked.
        label(L("برای چه بخشی نیاز به ویجت دارید؟", "Which section do you need a widget for?"))
        glassButton(sectionName(section) + " ▾") {
            glassListDialog(L("انتخاب بخش", "Choose a section"),
                sections.map {
                    DialogRow(SectionWidgetProvider.sectionIconBitmap(this, it), null,
                        sectionName(it), it == section)
                }) { idx ->
                section = sections[idx]
                titleText = ""
                if (section == "messenger" && chatMode == "chat" && chatId.isBlank()) {
                    val chats = AlienChats.load(this@WidgetStudioActivity)
                    if (chats.isNotEmpty()) chatId = chats[0].id
                }
            }
        }
        if (section.isBlank()) return

        // ---- connection mode (2.35): two pills in one row, above
        // the rest of the form. Not for chat widgets or Emergency.
        // Nothing picked behaves as "last" (the 2.33/2.34 way).
        if (section != "messenger" && section != "emergency") {
            label(L("نحوه اتصال", "Connection method"))
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((modeKey, modeLabel) in listOf(
                "last" to L("وصل به آخرین کانفیگ", "Last config"),
                "any" to L("هر کانفیگی که وصل شد", "Any working config"))) {
                val selected = modeKey == connMode
                val b = Button(this).apply {
                    text = modeLabel
                    textSize = 13f
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(if (selected) Color.parseColor("#0F766E") else Color.WHITE)
                        setStroke(dp(1), if (selected) Color.parseColor("#0F766E") else Color.parseColor("#E0E0E0"))
                    }
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#1A237E"))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        // Tapping the selected pill clears it (back
                        // to the default: last config).
                        connMode = if (connMode == modeKey) "" else modeKey
                        saveDraft()
                        buildPage()
                    }
                }
                modeRow.addView(b)
            }
            root.addView(modeRow)
        }

        // ---- messenger extras ----
        if (section == "messenger") {
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((modeKey, modeLabel) in listOf(
                "receiver" to L("دریافت", "Receive"),
                "chat" to L("چت کردن", "Chat"))) {
                val selected = modeKey == chatMode
                val b = Button(this).apply {
                    text = modeLabel
                    textSize = 13f
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(if (selected) Color.parseColor("#0F766E") else Color.WHITE)
                        setStroke(dp(1), if (selected) Color.parseColor("#0F766E") else Color.parseColor("#E0E0E0"))
                    }
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#1A237E"))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        chatMode = modeKey
                        titleText = ""
                        if (modeKey == "chat" && chatId.isBlank()) {
                            val chats = AlienChats.load(this@WidgetStudioActivity)
                            if (chats.isNotEmpty()) chatId = chats[0].id
                        }
                        saveDraft()
                        buildPage()
                    }
                }
                modeRow.addView(b)
            }
            root.addView(modeRow)
            if (chatMode == "chat") {
                val chats = AlienChats.load(this@WidgetStudioActivity)
                if (chats.isEmpty()) {
                    glassButton(L("باز کردن مسنجر", "Open Messenger")) {
                        startActivity(Intent(this, AlienMessengerActivity::class.java))
                    }
                } else {
                    for (c in chats) {
                        val selected = c.id == chatId
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(20).toFloat()
                                setColor(if (selected) Color.parseColor("#E8F5E9") else Color.WHITE)
                                setStroke(dp(1), if (selected) Color.parseColor("#2E9E5B") else Color.parseColor("#E0E0E0"))
                            }
                            elevation = 2f
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(6)
                            }
                            isClickable = true
                            setOnClickListener {
                                chatId = c.id
                                titleText = ""
                                saveDraft()
                                buildPage()
                            }
                        }
                        row.addView(TextView(this).apply {
                            text = c.partnerName
                            textSize = 15f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        if (selected) row.addView(TextView(this).apply {
                            text = "✓"
                            textSize = 18f
                            setTextColor(Color.parseColor("#2E9E5B"))
                        })
                        root.addView(row)
                    }
                }
            } else {
                if (AlienSub.receiverView(this).isNullOrBlank()) {
                    glassButton(L("رفتن به صفحه گیرنده", "Go to the receiver page")) {
                        startActivity(Intent(this, AlienSubActivity::class.java)
                            .putExtra("mode", "receiver"))
                    }
                }
            }
        }

        // ---- display name ----
        label(L("اسم نمایشی ویجت", "Widget display name"))
        val nameField = EditText(this).apply {
            setText(titleText)
            hint = defaultTitle()
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                titleText = s?.toString() ?: ""
                saveDraft()
                refreshPreview()
            }
        })
        root.addView(nameField)

        // ---- icon + button: single buttons to customize pages ----
        if (section != "messenger") {
            label(L("آیکون", "Icon"))
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(iconBg)
                }
            }
            glassRow(dot, L("آیکون", "Icon"), L("رنگ دایره پشت آیکون", "Colour of the circle behind the icon")) {
                startActivity(Intent(this, WidgetCustomizeActivity::class.java)
                    .putExtra("mode", "icon"))
            }
            label(L("نوع دکمه اتصال", "Connect button type"))
            val btnMini = ImageView(this).apply {
                setImageBitmap(SectionWidgetProvider.buttonBitmap(this@WidgetStudioActivity, btnShape, btnColor, 108))
            }
            glassRow(btnMini, L("نوع دکمه اتصال", "Connect button type"), shapeName(btnShape)) {
                startActivity(Intent(this, WidgetCustomizeActivity::class.java)
                    .putExtra("mode", "button"))
            }
        }

        // ---- style (messenger only: chat widgets still use styles) ----
        if (section == "messenger") {
            label(L("سبک نمایش", "Style"))
            glassButton(styleName(style) + " ▾") {
                glassListDialog(L("سبک نمایش", "Style"),
                    styles.map {
                        DialogRow(null, WidgetStyle.bgRes(it), styleName(it), it == style)
                    }) { idx ->
                    style = styles[idx]
                }
            }
        }

        if (section != "messenger") {
            // ---- Studio tabs (MLM: قالب / رنگ / اطلاعات / اندازه) ----
            label(L("تنظیمات ویجت", "Widget settings"))
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((tabKey, tabLabel) in listOf(
                "template" to L("قالب", "Template"),
                "color" to L("رنگ", "Colour"),
                "info" to L("اطلاعات", "Info"),
                "size" to L("اندازه", "Size"))) {
                val selected = tabKey == studioTab
                val b = Button(this).apply {
                    text = tabLabel
                    textSize = 13f
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(if (selected) Color.parseColor("#0F766E") else Color.WHITE)
                        setStroke(dp(1), if (selected) Color.parseColor("#0F766E")
                            else Color.parseColor("#E0E0E0"))
                    }
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#1A237E"))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        studioTab = tabKey
                        buildPage()
                    }
                }
                tabRow.addView(b)
            }
            root.addView(tabRow)

            when (studioTab) {
                "color" -> {
                    // Accent swatches (MLM's 11).
                    label(L("رنگ دکمه و چراغ", "Button and lamp colour"))
                    val accents = listOf("#39E58C", "#34C759", "#0A84FF", "#64D2FF",
                        "#8B5CF6", "#B46CFF", "#FF4F8B", "#FF9F0A", "#FFD60A",
                        "#FF453A", "#E9EBEE")
                    val accScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val accRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val accCells = mutableListOf<Pair<FrameLayout, Int>>()
                    for (hex in accents) {
                        val col = Color.parseColor(hex)
                        val cell = FrameLayout(this).apply {
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                btnColor = col
                                for ((c, cc) in accCells) {
                                    c.background = if (cc == btnColor) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                saveDraft()
                                refreshPreview()
                            }
                        }
                        cell.addView(View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(col)
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                        cell.background = if (col == btnColor) GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(2), Color.parseColor("#2E9E5B"))
                        } else null
                        accCells += cell to col
                        accRow.addView(cell)
                    }
                    accScroll.addView(accRow)
                    root.addView(accScroll)
                    // Card colours (MLM's 10).
                    label(L("رنگ کارت", "Card colour"))
                    val bgs = listOf("#0F2236", "#131518", "#06080B", "#0B0718",
                        "#171447", "#1C1C1E", "#102A22", "#2A1020", "#7A4DFF", "#F2F2F7")
                    val bgScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val bgRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val effBg = if (bgColor != -1) bgColor
                        else WidgetStyle.presetBg(template)
                    val bgCells = mutableListOf<Pair<FrameLayout, Int>>()
                    for (hex in bgs) {
                        val col = Color.parseColor(hex)
                        val cell = FrameLayout(this).apply {
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                bgColor = col
                                for ((c, cc) in bgCells) {
                                    c.background = if (cc == bgColor) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                saveDraft()
                                refreshPreview()
                            }
                        }
                        cell.addView(View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(col)
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                        cell.background = if (col == effBg) GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(2), Color.parseColor("#2E9E5B"))
                        } else null
                        bgCells += cell to col
                        bgRow.addView(cell)
                    }
                    bgScroll.addView(bgRow)
                    root.addView(bgScroll)
                    // Sliders: opacity / corner / text scale.
                    root.addView(sliderRow(L("میزان شفافیت کارت", "Card opacity"),
                        0, 100, if (opacity != -1) opacity
                        else WidgetStyle.presetOpacity(template)) { opacity = it })
                    root.addView(sliderRow(L("گردی گوشه‌ها", "Corner radius"),
                        8, 40, if (corner != -1) corner
                        else WidgetStyle.presetCorner(template)) { corner = it })
                    root.addView(sliderRow(L("اندازه نوشته‌ها", "Text size"),
                        85, 125, textScale) { textScale = it })
                }
                "info" -> {
                    label(L("اطلاعات نمایشی", "Shown info"))
                    for ((flag, flagLabel, flagSub) in listOf(
                        Triple("method", L("نام بخش", "Section name"), ""),
                        Triple("status", L("وضعیت اتصال", "Connection status"), ""),
                        Triple("speeds", L("سرعت دانلود و آپلود", "Download and upload speeds"), ""),
                        Triple("chart", L("نمودار زنده", "Live chart"),
                            if (widgetSize == "small") L("در اندازه کوچک نشان داده نمی‌شود", "Not shown on small widgets") else ""),
                        Triple("usage", L("مصرف نشست", "Session usage"),
                            if (widgetSize != "large") L("فقط در اندازه بزرگ", "Large widgets only") else ""),
                        Triple("time", L("مدت اتصال", "Time connected"),
                            if (widgetSize != "large") L("فقط در اندازه بزرگ", "Large widgets only") else ""),
                        Triple("icon", L("آیکون بخش", "Section icon"), ""))) {
                        val on = when (flag) {
                            "method" -> showMethod
                            "status" -> showStatus
                            "speeds" -> showSpeeds
                            "chart" -> showChart
                            "usage" -> showUsage
                            "time" -> showTime
                            else -> showIcon
                        }
                        val blocked = (flag == "chart" && widgetSize == "small") ||
                            ((flag == "usage" || flag == "time") && widgetSize != "large")
                        val mark = TextView(this).apply {
                            text = if (on) "✓" else "○"
                            textSize = 20f
                            setTextColor(if (on) Color.parseColor("#2E9E5B") else Color.GRAY)
                            gravity = Gravity.CENTER
                        }
                        glassRow(mark, flagLabel,
                            if (flagSub.isNotBlank()) flagSub
                            else if (on) L("نمایش داده می‌شود", "Shown")
                            else L("پنهان است", "Hidden")) {
                            if (blocked) {
                                Toast.makeText(this, flagSub, Toast.LENGTH_SHORT).show()
                                return@glassRow
                            }
                            when (flag) {
                                "method" -> showMethod = !showMethod
                                "status" -> showStatus = !showStatus
                                "speeds" -> showSpeeds = !showSpeeds
                                "chart" -> showChart = !showChart
                                "usage" -> showUsage = !showUsage
                                "time" -> showTime = !showTime
                                else -> showIcon = !showIcon
                            }
                            saveDraft()
                            buildPage()
                        }
                    }
                }
                "size" -> {
                    label(L("اندازه ویجت", "Widget size"))
                    for ((sizeKey, sizeTitle, sizeSub) in listOf(
                        Triple("small", L("مربع کوچک", "Small square"),
                            L("110×110، آیکون و دکمه", "110x110, icon and button")),
                        Triple("medium", L("پهن", "Wide"),
                            L("250×110، اندازه فعلی ویجت‌ها", "250x110, the current widget size")),
                        Triple("large", L("بزرگ", "Large"),
                            L("250×250، نمودار و آمار نشست", "250x250, chart and session stats")))) {
                        val selected = sizeKey == widgetSize
                        val mark = TextView(this).apply {
                            text = if (selected) "✓" else "○"
                            textSize = 20f
                            setTextColor(if (selected) Color.parseColor("#2E9E5B") else Color.GRAY)
                            gravity = Gravity.CENTER
                        }
                        glassRow(mark, sizeTitle + if (selected) " ✓" else "", sizeSub) {
                            widgetSize = sizeKey
                            saveDraft()
                            buildPage()
                        }
                    }
                }
                else -> {
                    // Template gallery: MLM's eight presets.
                    label(L("قالب ظاهری", "Appearance template"))
                    val tplScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val tplRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    for (tpl in WidgetStyle.PRESET_KEYS) {
                        val selected = tpl == template
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(18).toFloat()
                                setColor(if (selected) Color.parseColor("#E8F5E9") else Color.WHITE)
                                setStroke(dp(1), if (selected) Color.parseColor("#2E9E5B")
                                    else Color.parseColor("#E0E0E0"))
                            }
                            elevation = 2f
                            setPadding(dp(8), dp(10), dp(8), dp(10))
                            layoutParams = LinearLayout.LayoutParams(dp(132),
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                // Picking a preset applies its whole
                                // design; the user may then change
                                // any part on the colour/info tabs.
                                template = tpl
                                btnColor = WidgetStyle.presetAccent(tpl)
                                btnShape = WidgetStyle.presetButton(tpl)
                                bgColor = -1
                                opacity = -1
                                corner = -1
                                textScale = 100
                                val shown = WidgetStyle.presetShow(tpl)
                                showMethod = "method" in shown
                                showStatus = "status" in shown
                                showSpeeds = "speed" in shown
                                showUsage = "usage" in shown
                                showTime = "time" in shown
                                showChart = "chart" in shown
                                showIcon = "icon" in shown
                                saveDraft()
                                buildPage()
                            }
                        }
                        card.addView(ImageView(this).apply {
                            setImageBitmap(WidgetPreview.templateThumbBitmap(
                                this@WidgetStudioActivity, tpl))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(66))
                        })
                        card.addView(TextView(this).apply {
                            text = templateName(tpl) + if (selected) " ✓" else ""
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, 0)
                            if (selected) setTextColor(Color.parseColor("#2E9E5B"))
                        })
                        tplRow.addView(card)
                    }
                    tplScroll.addView(tplRow)
                    root.addView(tplScroll)
                }
            }

            // ---- preview state tabs (قطع / در حال اتصال / متصل) ----
            label(L("وضعیت پیش‌نمایش", "Preview state"))
            val stateRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((idx, sl) in listOf(
                L("قطع", "Off"),
                L("در حال اتصال", "Connecting"),
                L("متصل", "On")).withIndex()) {
                val selected = idx == previewState
                val b = Button(this).apply {
                    text = sl
                    textSize = 13f
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(if (selected) Color.parseColor("#0F766E") else Color.WHITE)
                        setStroke(dp(1), if (selected) Color.parseColor("#0F766E")
                            else Color.parseColor("#E0E0E0"))
                    }
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#1A237E"))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        previewState = idx
                        buildPage()
                    }
                }
                stateRow.addView(b)
            }
            root.addView(stateRow)
        }

        // ---- live preview: the full widget, fixed size ----
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

        // ---- the only action ----
        val action = Button(this).apply {
            text = L("ساخت و افزودن به صفحه اصلی", "Build and add to home screen")
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
            setOnClickListener { onAdd() }
        }
        root.addView(action)
    }

    private fun refreshPreview() {
        if (!::previewBox.isInitialized) return
        previewBox.removeAllViews()
        if (section == "messenger") {
            refreshChatPreview()
            return
        }
        val view = layoutInflater.inflate(WidgetPreview.layout(this, widgetSize), previewBox, false)
        WidgetPreview.populate(this, view, section, style, iconBg, btnShape, btnColor,
            titleText, template, previewState, showSpeeds, showStatus, showIcon,
            showMethod, showUsage, showTime, showChart, widgetSize,
            btnColor, bgColor, opacity, corner, textScale)
        val previewH = when (widgetSize) { "small" -> 120; "large" -> 320; else -> 120 }
        previewBox.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(previewH)).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun refreshChatPreview() {
        val view = layoutInflater.inflate(R.layout.widget_chat, previewBox, false)
        val holder = view.findViewById<LinearLayout>(R.id.widget_chat_root)
        holder.setBackgroundResource(WidgetStyle.bgRes(style))
        val titleView = view.findViewById<TextView>(R.id.widget_chat_title)
        titleView.text = shownTitle()
        if (WidgetStyle.isDark(style)) titleView.setTextColor(Color.WHITE)
        val replyView = view.findViewById<TextView>(R.id.widget_chat_reply)
        replyView.visibility = if (chatMode == "chat") View.VISIBLE else View.GONE
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val loading = TextView(this).apply {
            text = L("در حال بارگذاری…", "Loading…")
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        listBox.addView(loading)
        view.findViewById<LinearLayout>(R.id.widget_chat_root).addView(listBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        view.findViewById<View>(R.id.widget_chat_list).visibility = View.GONE
        view.findViewById<View>(R.id.widget_chat_empty).visibility = View.GONE
        previewBox.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        if (chatMode == "receiver" && AlienSub.receiverView(this).isNullOrBlank()) {
            listBox.removeAllViews()
            listBox.addView(TextView(this).apply {
                text = L("هنوز لینک گیرنده ثبت نشده است", "No receiver link saved yet")
                textSize = 12f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            })
            return
        }
        if (chatMode == "chat" && chatId.isBlank()) {
            listBox.removeAllViews()
            listBox.addView(TextView(this).apply {
                text = L("یک گفتگو انتخاب کن", "Pick a chat")
                textSize = 12f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            })
            return
        }
        Thread {
            val rows = AlienChatWidgetProvider.loadRowsFor(this, chatMode, chatId)
            val count = 8
            val hiddenKey = if (chatMode == "receiver") "receiver" else chatId
            val hidden = WidgetStyle.hiddenFor(this, hiddenKey)
            val visible = rows?.filter { it.second || it.first !in hidden }
            val shown = if (visible != null && visible.size > count) visible.takeLast(count) else visible
            runOnUiThread {
                listBox.removeAllViews()
                if (shown.isNullOrEmpty()) {
                    listBox.addView(TextView(this).apply {
                        text = L("هنوز پیامی نیست", "No messages yet")
                        textSize = 12f
                        setTextColor(Color.GRAY)
                        gravity = Gravity.CENTER
                    })
                    return@runOnUiThread
                }
                val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
                for ((ts, mine, text) in shown) {
                    val row = layoutInflater.inflate(R.layout.widget_chat_item, listBox, false)
                    val mineView = row.findViewById<TextView>(R.id.widget_chat_msg_mine)
                    val theirsView = row.findViewById<TextView>(R.id.widget_chat_msg_theirs)
                    mineView.text = text
                    theirsView.text = text
                    mineView.visibility = if (mine) View.VISIBLE else View.GONE
                    theirsView.visibility = if (mine) View.GONE else View.VISIBLE
                    try {
                        row.findViewById<TextView>(R.id.widget_chat_time)?.text =
                            timeFmt.format(Date(ts))
                    } catch (_: Exception) {}
                    listBox.addView(row)
                }
            }
        }.start()
    }

    private fun onAdd() {
        if (section == "messenger") {
            if (chatMode == "chat" && chatId.isBlank()) {
                Toast.makeText(this, L("یک گفتگو انتخاب کن", "Pick a chat first"),
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (chatMode == "receiver" && AlienSub.receiverView(this).isNullOrBlank()) {
                Toast.makeText(this, L("اول یک لینک گیرنده ثبت کن", "Save a receiver link first"),
                    Toast.LENGTH_SHORT).show()
                return
            }
            val o = org.json.JSONObject()
            o.put("provider", "chat")
            o.put("title", titleText.ifBlank { defaultTitle() })
            o.put("style", style)
            o.put("color", "blue")
            o.put("chatId", chatId)
            o.put("chatmode", chatMode)
            WidgetStyle.setPending(this, o.toString())
            try {
                val mgr = AppWidgetManager.getInstance(this)
                val ok = mgr.requestPinAppWidget(
                    ComponentName(this, AlienChatWidgetProvider::class.java), null, null)
                if (!ok) throw Exception("pin refused")
                Toast.makeText(this, L("ویجت آماده است؛ آن را روی صفحه اصلی بگذار",
                    "Widget ready; place it on your home screen"), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, L("افزودن خودکار نشد؛ از بخش ویجت‌های اندروید اضافه‌اش کن",
                    "Auto-add failed; add it from Android's widget picker"),
                    Toast.LENGTH_LONG).show()
            }
        } else {
            val o = org.json.JSONObject()
            o.put("provider", "section")
            o.put("section", section)
            o.put("title", titleText.ifBlank { defaultTitle() })
            o.put("style", style)
            o.put("color", "blue")
            o.put("icon", "icon")
            o.put("showname", true)
            o.put("iconbg", iconBg)
            o.put("btnshape", btnShape)
            o.put("btncolor", btnColor)
            o.put("connmode", connMode)
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
            WidgetStyle.setPending(this, o.toString())
            try {
                val mgr = AppWidgetManager.getInstance(this)
                val providerClass = when (widgetSize) {
                    "small" -> SectionWidgetSmall::class.java
                    "large" -> SectionWidgetLarge::class.java
                    else -> SectionWidgetProvider::class.java
                }
                val ok = mgr.requestPinAppWidget(
                    ComponentName(this, providerClass), null, null)
                if (!ok) throw Exception("pin refused")
                Toast.makeText(this, L("ویجت آماده است؛ آن را روی صفحه اصلی بگذار",
                    "Widget ready; place it on your home screen"), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, L("افزودن خودکار نشد؛ از بخش ویجت‌های اندروید اضافه‌اش کن",
                    "Auto-add failed; add it from Android's widget picker"),
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
