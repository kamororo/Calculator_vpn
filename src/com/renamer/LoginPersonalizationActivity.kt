package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Login personalization page ("شخصی‌سازی ورود").
 *
 * It ONLY writes prefs — it does not restyle PinActivity.
 * PinActivity reads the same prefs on its next open:
 *   "renamer_settings" / "calc_theme" (theme id, default "pastel")
 *   "renamer_settings" / "calc_shape" (shape id, default "round")
 * See CalcTheme.kt for the full read API.
 */
class LoginPersonalizationActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var root: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("شخصی‌سازی ورود", "Login personalization"); textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "رنگ و شکل صفحه ماشین‌حساب (صفحه ورود) را اینجا انتخاب کن. تغییر، دفعه بعد که صفحه ورود باز شود اعمال می‌شود.",
                "Choose the color and button shape of the calculator (login) page here. The change applies the next time the login page opens."
            )
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(16))
        })

        rebuild()
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

    private fun rebuild() {
        // Keep the two header views, rebuild the option rows.
        while (root.childCount > 2) root.removeViewAt(2)

        root.addView(sectionHeader(L("رنگ ماشین‌حساب", "Calculator colors")))
        val selectedTheme = CalcTheme.themeId(this)
        for (theme in CalcTheme.all()) {
            val selected = theme.id == selectedTheme
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener {
                    CalcTheme.setTheme(this@LoginPersonalizationActivity, theme.id)
                    rebuild()
                }
            }
            // Gradient preview swatch.
            row.addView(View(this).apply {
                background = CalcTheme.gradientFor(theme)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(48)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            })
            row.addView(TextView(this).apply {
                text = L(theme.nameFa, theme.nameEn); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(sectionHeader(L("شکل دکمه‌ها", "Button shape")))
        val shapes = listOf(
            Triple("round", L("گرد", "Round"), L("مستطیل با گوشه‌های گرد", "Rounded rectangle")),
            Triple("square", L("مربعی", "Square"), L("مستطیل با گوشه‌های کم‌گرد", "Slightly rounded rectangle")),
            Triple("circle", L("دایره‌ای", "Circle"), L("دکمه‌های کاملاً دایره‌ای", "Fully circular buttons")),
            Triple("pill", L("کپسولی", "Pill"), L("دکمه‌های کشیده و کاملاً گرد", "Stadium-shaped buttons")),
            Triple("soft", L("نرم", "Soft"), L("مستطیل با گوشه‌های نرم ۱۶", "Soft 16dp corners"))
        )
        val selectedShape = CalcTheme.shape(this)
        val currentTheme = CalcTheme.theme(this)
        for ((id, name, desc) in shapes) {
            val selected = id == selectedShape
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener {
                    CalcTheme.setShape(this@LoginPersonalizationActivity, id)
                    rebuild()
                }
            }
            // Shape preview: a small button in this shape.
            row.addView(View(this).apply {
                background = CalcTheme.buttonBackgroundFor(currentTheme, id, accent = false, this@LoginPersonalizationActivity)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply { text = name; textSize = 16f })
            col.addView(TextView(this).apply {
                text = desc; textSize = 13f; setTextColor(Color.GRAY)
            })
            row.addView(col)
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(sectionHeader(L("اندازه دکمه‌ها", "Key size")))
        val keySizes = listOf(
            Triple("small", L("کوچک", "Small"), L("دکمه‌های جمع‌وجورتر", "More compact keys")),
            Triple("normal", L("معمولی", "Normal"), L("اندازه استاندارد", "Standard size")),
            Triple("large", L("بزرگ", "Large"), L("دکمه‌های درشت‌تر برای لمس راحت‌تر", "Bigger keys, easier to tap"))
        )
        val curKeySize = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("calc_key_size", "normal") ?: "normal"
        for ((id, name, desc) in keySizes) {
            val selected = id == curKeySize
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener {
                    CalcTheme.setKeySize(this@LoginPersonalizationActivity, id)
                    rebuild()
                }
            }
            row.addView(TextView(this).apply {
                text = name; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = desc; textSize = 13f; setTextColor(Color.GRAY)
            })
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(sectionHeader(L("فونت نمایشگر", "Display font")))
        val fonts = listOf(
            Triple("bold", L("پررنگ", "Bold"), L("خوانا و برجسته", "Clear and bold")),
            Triple("normal", L("معمولی", "Normal"), L("ساده", "Plain")),
            Triple("mono", L("تک‌فاصله‌ای", "Monospace"), L("مثل نمایشگر ماشین‌حساب‌های قدیمی", "Like old calculator displays"))
        )
        val curFont = CalcTheme.displayFont(this)
        for ((id, name, desc) in fonts) {
            val selected = id == curFont
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener {
                    CalcTheme.setDisplayFont(this@LoginPersonalizationActivity, id)
                    rebuild()
                }
            }
            row.addView(TextView(this).apply {
                text = name; textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = desc; textSize = 13f; setTextColor(Color.GRAY)
            })
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(TextView(this).apply {
            text = L(
                "توجه: این تنظیم فقط ظاهر صفحه ورود (ماشین‌حساب) را عوض می‌کند؛ رمزها و قفل برنامه با آن عوض نمی‌شوند.",
                "Note: this only changes the look of the login (calculator) page; it does not change any password or the app lock."
            )
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 18f
        setTextColor(Color.parseColor("#FF8C00"))
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
        setBackgroundColor(Color.parseColor("#DDDDDD"))
    }
}
