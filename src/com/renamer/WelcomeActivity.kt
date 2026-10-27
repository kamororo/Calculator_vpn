package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*

/** Short white loading page shown after a correct
 *  entry password, before the Home page. Nothing on
 *  it but "Welcome" and a spinner — no buttons and
 *  no lock wording. After 1200ms it starts
 *  HomeActivity with the SAME mode extra and
 *  finishes itself, so Back never returns here. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        val welcomeText = TextView(this).apply {
            text = L("خوش آمدید", "Welcome"); textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(welcomeText)
        // Slide-in from the reading direction: Persian
        // enters from the right, English from the left.
        val fromX = if (AppLanguage.isEnglish(this)) -1f else 1f
        welcomeText.startAnimation(android.view.animation.TranslateAnimation(
            android.view.animation.Animation.RELATIVE_TO_SELF, fromX,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0f
        ).apply { duration = 700 })
        root.addView(ProgressBar(this).apply {
            isIndeterminate = true
        })
        setContentView(root)
        handler.postDelayed(goHome, 1200)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goHome)
        super.onDestroy()
    }
}
