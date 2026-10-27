package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*

/** Hydra report, on its own page: every step in
 *  plain language, with the exact time, newest at
 *  the bottom, live-updating while open. */
class HydraReportActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int): Int = HydraUi.dp(this, v)

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
        }
        setContentView(root)

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(HydraUi.glassButton(this, L("بازگشت", "Back")) { finish() })
        topRow.addView(TextView(this).apply {
            text = L("گزارش هیدرا", "Hydra report")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), 0, 0)
        })
        root.addView(topRow)
        root.addView(HydraUi.glassButton(this,
            L("پاک کردن گزارش", "Clear report")) {
            HydraEngine.clearLog(this)
            refreshLog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        })

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(16)
            }
            setBackgroundColor(Color.WHITE)
        }
        logText = TextView(this).apply {
            textSize = 13f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        logScroll.addView(logText)
        root.addView(logScroll)
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        handler.postDelayed(poller, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun refreshLog() {
        val lines = HydraEngine.readLog(this)
        val en = AppLanguage.isEnglish(this)
        val fmt = java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault())
        val sb = StringBuilder()
        for (line in lines) {
            val msg = if (en) line.en.ifBlank { line.fa } else line.fa
            sb.append(fmt.format(java.util.Date(line.timeMs)))
                .append(" — ").append(msg).append("\n")
        }
        if (sb.isEmpty()) {
            sb.append(L("هنوز گزارشی نیست؛ هیدرا را شروع کن تا قدم‌به‌قدم بنویسد چه می‌کند.",
                "No report yet; start Hydra and it will write, step by step, what it does."))
        }
        logText.text = sb.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
