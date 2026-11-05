package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.*

/** Short loading page shown after a correct entry
 *  password, before the Home page. 2.18: white page,
 *  a pulsing glass orb, a big navy welcome line, a
 *  soft tagline, a spinner and, under it, three small
 *  dots bouncing in turn — no buttons and no lock
 *  wording. After about 3s it starts HomeActivity
 *  with the SAME mode extra and finishes itself, so
 *  Back never returns here. The page can be switched
 *  off from Personalization (pref "show_welcome"). */
class WelcomeActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goHome = Runnable {
        if (navigated) return@Runnable
        navigated = true
        val mode = intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC
        val home = Intent(this, HomeActivity::class.java)
        home.putExtra(PinActivity.EXTRA_MODE, mode)
        startActivity(home)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }

        // Pulsing glass orb: the app's own drawn orb
        // texture, gently breathing while the page loads.
        val orb = View(this).apply {
            background = HydraUi.GlassOrbDrawable(
                Color.parseColor("#A5F3FC"), Color.parseColor("#0E7490"), this@WelcomeActivity
            )
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
        }
        orb.startAnimation(ScaleAnimation(
            1f, 1.07f, 1f, 1.07f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        })
        root.addView(orb)

        val welcomeText = TextView(this).apply {
            text = L("خوش آمدید", "Welcome"); textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(welcomeText)
        // Slide-in from the reading direction: Persian
        // enters from the right, English from the left,
        // fading in at the same time.
        val fromX = if (AppLanguage.isEnglish(this)) -1f else 1f
        val enter = AnimationSet(true).apply {
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, fromX,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f
            ).apply { duration = 700 })
            addAnimation(AlphaAnimation(0f, 1f).apply { duration = 700 })
        }
        welcomeText.startAnimation(enter)

        root.addView(TextView(this).apply {
            text = L("اتصال سریع، امن و بدون دردسر", "Fast, secure, effortless connection")
            textSize = 15f
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })
        root.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F766E"))
        })
        setContentView(root)
        // 2.18: about 3 seconds on screen.
        handler.postDelayed(goHome, 3000)
        // Small loading animation under the spinner:
        // three teal dots bouncing in turn.
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        val dotColors = listOf("#0F766E", "#26A69A", "#80CBC4")
        for ((i, hex) in dotColors.withIndex()) {
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
            }
            dot.startAnimation(ScaleAnimation(
                0.6f, 1f, 0.6f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 500
                startOffset = (i * 150).toLong()
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
            dots.addView(dot)
        }
        root.addView(dots)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goHome)
        super.onDestroy()
    }
}
