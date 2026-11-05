package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Hidden send-method choice (2.31). Reached ONLY through the hidden
 *  circle gesture on the hub page, like Emergency's hidden settings:
 *  it has no home tile, no settings row, and no mention in help or
 *  about. The page shows ONLY a choice — never an address: "auto"
 *  keeps the current mirror behaviour (both methods), "primary"
 *  sends only via the first method, "backup" only via the backup
 *  method. The choice gates SENDS only (create + update); reading
 *  always tries every part, and deletes hit every part.
 *
 *  Look: drawn only, never a photo crop — a deep-galaxy gradient
 *  hero card on top, glass option cards with a colour dot and a
 *  green tick for the selected method. */
class AlienHostActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun padForInsets(v: View, baseDp: Int) {
        val b = dp(baseDp)
        v.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(b, b + insets.systemWindowInsetTop,
                b, b + insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPage()
    }

    private fun dot(color: String): ImageView {
        return ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginEnd = dp(12)
            }
        }
    }

    private fun buildPage() {
        val current = AlienSub.sendMode(this)
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(root)
        padForInsets(scroll, 0)

        // Gradient hero card: deep galaxy, white title + subtitle.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#1A237E"),
                    Color.parseColor("#4A148C"),
                    Color.parseColor("#006064")
                )).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(20)
            }
        }
        hero.addView(TextView(this).apply {
            text = L("روش ارسال پیام", "Message send method")
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        hero.addView(TextView(this).apply {
            text = L("فقط انتخاب کن؛ هیچ نشانی‌ای اینجا دیده نمی‌شود",
                "Just pick one; no address is shown here.")
            textSize = 13f
            setTextColor(Color.parseColor("#E8EAF6"))
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(hero)

        val options = listOf(
            "auto" to Triple(L("خودکار", "Automatic"),
                L("رفتار فعلی: هر دو روش", "Current behaviour: both methods"), "#0F766E"),
            "primary" to Triple(L("روش اول", "First method"),
                L("فقط روش اول برای فرستادن", "First method only, for sending"), "#1A237E"),
            "backup" to Triple(L("روش پشتیبان", "Backup method"),
                L("فقط روش پشتیبان برای فرستادن", "Backup method only, for sending"), "#7B1FA2")
        )
        for ((mode, texts) in options) {
            val (label, desc, dotColor) = texts
            val sel = mode == current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = HydraUi.glassBg(
                    Color.parseColor(if (sel) "#E8F5E9" else "#FFFFFF"),
                    Color.parseColor(if (sel) "#2E9E5B" else "#E0E0E0"),
                    20f, this@AlienHostActivity)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
                isClickable = true
            }
            card.addView(dot(dotColor))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (sel) "#2E7D32" else "#1A237E"))
            })
            col.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            card.addView(col)
            if (sel) {
                card.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#2E7D32"))
                    setPadding(dp(8), 0, 0, 0)
                })
            }
            card.setOnClickListener {
                AlienSub.setSendMode(this, mode)
                buildPage()
            }
            root.addView(card)
        }

        root.addView(TextView(this).apply {
            text = L(
                "در حالت خودکار، پیام با هر دو روش ساخته و فرستاده می‌شود و اگر یک روش در دسترس نباشد، روش دیگر کار می‌کند. در روش اول یا پشتیبان، فقط همان روش برای فرستادن استفاده می‌شود؛ اگر آن روش در دسترس نباشد، پیام فرستاده نمی‌شود تا روش را عوض کنی.",
                "In Automatic mode, a message is created and sent with both methods, and if one method is unavailable, the other one works. In First method or Backup method, only that method is used for sending; if it is unavailable, the message is not sent until you change the method.")
            textSize = 13f
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })

        val back = Button(this).apply {
            text = L("بازگشت", "Back")
            textSize = 14f
            setTextColor(Color.parseColor("#1A237E"))
            background = HydraUi.glassPill(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 20f, this@AlienHostActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
            setOnClickListener { finish() }
        }
        root.addView(back)
        setContentView(scroll)
    }
}
