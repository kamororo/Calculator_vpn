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

        fun settingRow(title: String, desc: String, target: Class<*>) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            col.addView(Button(this).apply {
                text = title; textSize = 17f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setOnClickListener {
                    val i = Intent(this@HomeSettingsActivity, target)
                    if (target == GuideActivity::class.java) {
                        i.putExtra(PinActivity.EXTRA_MODE,
                            intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
                    }
                    startActivity(i)
                }
            })
            col.addView(TextView(this).apply {
                text = desc; textSize = 13f
                setTextColor(subFg)
                setPadding(dp(4), dp(4), 0, 0)
            })
            root.addView(col)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setBackgroundColor(Theme.divider(this@HomeSettingsActivity))
            })
        }

        settingRow(
            L("مرتب‌سازی برنامه‌ها", "Sort apps"),
            L("روی آیکون هر برنامه نگه دار و با دست بکش تا جایش عوض شود، بعد ذخیره کن.", "Hold an app's icon and drag it to its new place, then save."),
            ReorderActivity::class.java
        )
        settingRow(
            L("تغییر رمز ورود", "Change entry password"),
            L("رمز ورود را به رمز دلخواهت عوض کن.", "Change the entry password to one of your choice."),
            ChangePublicPinActivity::class.java
        )
        settingRow(
            L("ویجت صفحه اصلی", "Home screen widgets"),
            L("یک بخش (مثل v2ray یا WireGuard) را به صفحه اصلی گوشی ببر تا بدون باز کردن برنامه وصل شوی.", "Send a section (e.g. v2ray or WireGuard) to the phone home screen to connect without opening the app."),
            WidgetPickerActivity::class.java
        )
        settingRow(
            L("شخصی‌سازی ورود", "Login personalization"),
            L("رنگ و شکل صفحه ورود (ماشین‌حساب) را به سلیقه خودت عوض کن.", "Change the colours and style of the entry (calculator) page to your taste."),
            LoginPersonalizationActivity::class.java
        )
        settingRow(
            L("راهنمایی", "Guide"),
            L("توضیح هر بخش برنامه، جدا جدا و با جزئیات.", "A detailed explanation of each section of the app, one by one."),
            GuideActivity::class.java
        )

        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }
}
