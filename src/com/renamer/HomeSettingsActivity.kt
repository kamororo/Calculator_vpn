package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/** Settings page opened from the Home (apps) page.
 *  Each row opens one settings section; the sections
 *  themselves live in their own activities (created by
 *  other slices / the parent), this page only links to
 *  them. */
class HomeSettingsActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Theme.bg(this)
        val fg = Theme.fg(this)
        val subFg = Theme.subFg(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("تنظیمات", "Settings"); textSize = 22f
            setTextColor(fg)
            setPadding(0, 0, 0, dp(8))
        })

        // Compact icon rows (1.79): one tappable row
        // per setting — a small tinted icon circle, a
        // 15sp title and a short 12sp description that
        // wraps to at most two lines, plus a chevron.
        // The old layout (a full-width 17sp button +
        // a description block under it) took a whole
        // screen for five rows.
        fun settingRow(icon: String, tint: String, title: String, desc: String, target: Class<*>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    val i = Intent(this@HomeSettingsActivity, target)
                    if (target == GuideActivity::class.java || target == LoginPersonalizationActivity::class.java) {
                        i.putExtra(PinActivity.EXTRA_MODE,
                            intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
                    }
                    startActivity(i)
                }
            }
            row.addView(TextView(this).apply {
                text = icon
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#$tint"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#22$tint"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                }
            }
            col.addView(TextView(this).apply {
                text = title; textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(fg)
            })
            col.addView(TextView(this).apply {
                text = desc; textSize = 12f
                setTextColor(subFg)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = "‹"; textSize = 20f
                setTextColor(subFg)
            })
            root.addView(row)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
setBackgroundColor(Theme.divider(this@HomeSettingsActivity))
            })
        }

        settingRow(
            "⇅", "2196F3",
            L("تنظیمات صفحه اصلی", "Home settings"),
            L("جای برنامه‌ها را در صفحه اصلی عوض کن.", "Reorder the apps on Home."),
            ReorderActivity::class.java
        )
        settingRow(
            "▦", "4CAF50",
            L("ویجت صفحه اصلی", "Home screen widgets"),
            L("یک بخش را به صفحه اصلی ببر تا بدون باز کردن برنامه وصل شوی.", "Put a section on the home screen to connect without opening the app."),
            WidgetStudioActivity::class.java
        )
        settingRow(
            "✎", "9C27B0",
            L("شخصی‌سازی ورود", "Login personalization"),
            L("رنگ و شکل صفحه ورود (ماشین‌حساب) را به سلیقه خودت عوض کن.", "Change the colours and style of the entry (calculator) page to your taste."),
            LoginPersonalizationActivity::class.java
        )
        settingRow(
            "ⓘ", "009688",
            L("راهنمایی", "Guide"),
            L("توضیح هر بخش، جدا جدا.", "Each section explained, one by one."),
            GuideActivity::class.java
        )


        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            textSize = 13f
            isAllCaps = false
            setTextColor(fg)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#15000000"))
                setStroke(1, Color.parseColor("#22000000"))
            }
            setPadding(dp(24), dp(10), dp(24), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }
}
