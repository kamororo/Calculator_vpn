package com.renamer

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/**
 * "ویجت صفحه اصلی / Home screen widgets" — picked from the app's
 * Settings page (parent wiring). The user chooses a section (v2ray /
 * WireGuard / OpenVPN) and taps its row; on Android 8+ the system then
 * asks to pin that section's widget to the home screen. Afterwards the
 * widget can be long-pressed and resized to 1x1, 1x2, 1x3 or 2x2, and
 * the provider switches its layout to fit the new size.
 *
 * First-time note shown on the page: open the section once and connect
 * manually so the VPN permission is granted; after that the v2ray widget
 * works without opening the app. WireGuard / OpenVPN widgets always open
 * their section (with widget_auto_connect) because their engines need
 * the activity permission flow — that limit is stated on the page.
 */
class WidgetPickerActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circle(initial: String, fill: Int): TextView = TextView(this).apply {
        text = initial
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            setMargins(0, 0, dp(12), 0)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("ویجت صفحه اصلی", "Home screen widgets")
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "یک بخش را انتخاب کن؛ اندروید از تو می‌خواهد ویجتش را به صفحه اصلی اضافه کنی. " +
                    "بعد می‌توانی با نگه داشتن روی ویجت، اندازه‌اش را به ۱×۱، ۱×۲، ۱×۳ یا ۲×۲ تغییر بدهی و ویجت خودش را با اندازه جدید وفق می‌دهد.",
                "Pick a section; Android will ask you to add its widget to your home screen. " +
                    "Afterwards, long-press the widget to resize it to 1x1, 1x2, 1x3 or 2x2, and the widget adapts to the new size."
            )
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "نکته: بار اول بخش را یک بار باز کن و به صورت دستی وصل شو تا مجوز VPN ثبت شود؛ بعد از آن ویجت v2ray بدون باز کردن برنامه کار می‌کند. " +
                    "ویجت‌های WireGuard و OpenVPN همیشه بخش خودشان را باز می‌کنند، چون موتورشان به مجوز داخل برنامه نیاز دارد.",
                "Note: the first time, open the section once and connect manually so the VPN permission is granted; after that the v2ray widget works without opening the app. " +
                    "WireGuard and OpenVPN widgets always open their section, because their engines need the in-app permission flow."
            )
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(16))
        })

        fun row(
            initial: String, fill: Int, name: String, desc: String,
            provider: Class<*>
        ) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#F5F5F5"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(12)) }
                isClickable = true
                setOnClickListener { requestPin(provider) }
            }
            rowView.addView(circle(initial, fill))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = name
                textSize = 17f
            })
            texts.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            rowView.addView(texts)
            root.addView(rowView)
        }

        row(
            "V", Color.parseColor("#7E9CC9"), "v2ray",
            L("دکمه اتصال مستقیم از صفحه اصلی", "Direct connect button from the home screen"),
            VpnWidgetProvider::class.java
        )
        row(
            "W", Color.parseColor("#8FBC8F"), "WireGuard",
            L("باز کردن بخش WireGuard و اتصال خودکار", "Opens WireGuard and auto-connects"),
            WgWidgetProvider::class.java
        )
        row(
            "O", Color.parseColor("#E8A87C"), "OpenVPN",
            L("باز کردن بخش OpenVPN و اتصال خودکار", "Opens OpenVPN and auto-connects"),
            OvpnWidgetProvider::class.java
        )

        // Personalisation, set from inside the app and
        // applied to already-pinned widgets at once:
        // size (auto = follow the real widget size, or a
        // fixed 1x1/1x2/1x3/2x2 layout), off-colour and
        // icon (real section icon, or the initial letter).
        root.addView(TextView(this).apply {
            text = L("شخصی‌سازی ویجت", "Widget personalisation")
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        })
        fun cycleBtn(section: String, prefKey: String, labelFa: String, labelEn: String,
                     options: List<String>, display: List<String>): Button {
            val prefsW = getSharedPreferences("renamer_widget", MODE_PRIVATE)
            val btn = Button(this)
            fun refreshText() {
                val cur = prefsW.getString(prefKey + "_" + section, options[0]) ?: options[0]
                val idx = options.indexOf(cur).takeIf { it >= 0 } ?: 0
                btn.text = L(labelFa, labelEn) + ": " + display[idx]
            }
            refreshText()
            btn.setOnClickListener {
                val cur = prefsW.getString(prefKey + "_" + section, options[0]) ?: options[0]
                val idx = options.indexOf(cur).takeIf { it >= 0 } ?: 0
                val next = options[(idx + 1) % options.size]
                prefsW.edit().putString(prefKey + "_" + section, next).apply()
                refreshText()
                WidgetRefresh.refreshAll(this)
            }
            return btn
        }
        for ((section, name) in listOf("v2ray" to "v2ray", "wireguard" to "WireGuard", "openvpn" to "OpenVPN")) {
            root.addView(TextView(this).apply {
                text = name; textSize = 17f; setPadding(0, dp(8), 0, dp(4))
            })
            root.addView(cycleBtn(section, "widget_size", "اندازه", "Size",
                listOf("auto", "1x1", "1x2", "1x3", "2x2"),
                listOf(L("خودکار", "Auto"), "1x1", "1x2", "1x3", "2x2")))
            root.addView(cycleBtn(section, "widget_color", "رنگ دکمه (قطع)", "Button colour (off)",
                listOf("blue", "pink", "lavender", "orange", "mint"),
                listOf(L("آبی ملایم", "Soft blue"), L("صورتی", "Pink"), L("بنفش ملایم", "Soft purple"), L("نارنجی", "Orange"), L("سبز ملایم", "Soft green"))))
            root.addView(cycleBtn(section, "widget_icon", "آیکون", "Icon",
                listOf("icon", "letter"),
                listOf(L("آیکون واقعی برنامه", "Real app icon"), L("حرف اول", "Initial letter"))))
        }
        root.addView(TextView(this).apply {
            text = L("وقتی وصل است، دکمه ویجت همیشه سبز می‌شود، هر رنگی که برای حالت قطع انتخاب کرده باشی. وقتی از داخل برنامه وصل یا قطع می‌کنی، ویجت خودش رنگش را عوض می‌کند.",
                "When connected, the widget button is always green, whatever off-colour you picked. When you connect or disconnect from inside the app, the widget recolours itself.")
            textSize = 13f; setTextColor(Color.GRAY); setPadding(0, dp(8), 0, dp(8))
        })

        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

    private fun requestPin(provider: Class<*>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(
                this,
                L(
                    "در این اندروید، ویجت را باید دستی از لیست ویجت‌های لانچر اضافه کنی.",
                    "On this Android version, add the widget manually from the launcher's widget list."
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val manager = AppWidgetManager.getInstance(this)
            val component = ComponentName(this, provider)
            val ok = manager.requestPinAppWidget(component, null, null)
            if (!ok) {
                Toast.makeText(
                    this,
                    L(
                        "لانچر اجازه سنجاق کردن ویجت را نداد؛ ویجت را از لیست ویجت‌های لانچر اضافه کن.",
                        "The launcher did not allow pinning; add the widget from the launcher's widget list."
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                L("سنجاق کردن ویجت ناموفق بود: ${e.message}", "Pinning the widget failed: ${e.message}"),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
