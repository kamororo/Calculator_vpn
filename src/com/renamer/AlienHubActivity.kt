package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Hub opened by tapping the Alien section's
 *  header icon (2.27). Kept deliberately simple:
 *  two plain choices for the message link, plus
 *  the messenger. No jargon, no raw site address
 *  anywhere on this page. */
class AlienHubActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- Hidden gesture (2.31, same model as Emergency settings):
    // draw one full circle anywhere on this page — the empty bottom
    // part is the natural spot — then tap its middle within 15s, and
    // the hidden send-method page opens. Normal taps pass through
    // untouched; only the deliberate centre tap is eaten.
    private val gesturePoints = mutableListOf<Pair<Float, Float>>()
    private var gestureCircleDrawn = false
    private var gestureCircleTime = 0L
    private var gestureCx = 0f
    private var gestureCy = 0f
    private var gestureRadius = 0f
    private var gestureCenterTap = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(24), dp(24), dp(24), dp(48))
        }
        // 2.32: the same living icon as the converter page —
        // thin slow rings hug each of its four circles.
        try {
            root.addView(AlienOrbitView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        } catch (_: Exception) {}
        root.addView(TextView(this).apply {
            text = L("پیام بیگانه", "Alien Message")
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = L(
                "با این قسمت می‌توانی با زبان بیگانه پیام بفرستی و بگیری؛ پیام‌ها از راه یک لینک می‌رسند.",
                "With this part you can send and receive messages in the alien language; messages arrive through a link.")
            textSize = 13f
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        root.addView(TextView(this).apply {
            text = L("تو فرستنده‌ای یا گیرنده؟", "Are you the sender or the receiver?")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        fun bigButton(label: String, fill: String, txt: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 15f
                isAllCaps = false
                setTextColor(Color.parseColor(txt))
                elevation = 3f
                // 2.46: FLAT fill, not glassPill — the
                // pill's white top gloss cut a white
                // band through the middle of every
                // tinted button. Smaller padding too:
                // same text size, smaller buttons.
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 24f * resources.displayMetrics.density
                    setColor(Color.parseColor(fill))
                }
                setOnClickListener { onClick() }
            }
        }
        root.addView(bigButton(L("من فرستنده‌ام", "I am the sender"), "#E0F2F1", "#00695C") {
            startActivity(Intent(this, AlienSubActivity::class.java).apply {
                putExtra("mode", "sender")
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        root.addView(bigButton(L("من گیرنده‌ام", "I am the receiver"), "#E8EAF6", "#1A237E") {
            startActivity(Intent(this, AlienSubActivity::class.java).apply {
                putExtra("mode", "receiver")
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(24)
        })
        root.addView(TextView(this).apply {
            text = L("مسنجر بیگانه", "Alien Messenger")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = L(
                "گفتگوی دو نفره با زبان بیگانه؛ هر دو لینکشان را به هم می‌دهند و پیام‌ها هر ۱۰ ثانیه تازه می‌شوند.",
                "Two-person chat in the alien language; both exchange their links and messages refresh every 10 seconds.")
            textSize = 13f
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        // 2.51: this button used to be the only hub
        // button without explicit params, and the
        // 2.46 padding shrink left its box squeezed
        // — full-width params, a bottom margin and
        // roomier padding, plus the root's extra
        // bottom clearance above, so it can never
        // sit half-hidden at the page bottom.
        root.addView(bigButton(L("باز کردن مسنجر", "Open the messenger"), "#E8F5E9", "#2E7D32") {
            startActivity(Intent(this, AlienMessengerActivity::class.java))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)
        HelpUi.attach(this, "hub")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gestureCircleDrawn && System.currentTimeMillis() - gestureCircleTime > 15000) {
                    gestureCircleDrawn = false
                }
                if (gestureCircleDrawn) {
                    val dx = ev.x - gestureCx
                    val dy = ev.y - gestureCy
                    val r = gestureRadius * 0.6f
                    if (dx * dx + dy * dy < r * r) {
                        gestureCenterTap = true
                        gestureDownX = ev.x
                        gestureDownY = ev.y
                    } else {
                        gestureCircleDrawn = false
                        gestureCenterTap = false
                    }
                }
                gesturePoints.clear()
                gesturePoints.add(ev.x to ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureCenterTap) gesturePoints.add(ev.x to ev.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureCenterTap) {
                    val moved = hypot(ev.x - gestureDownX, ev.y - gestureDownY)
                    gestureCenterTap = false
                    gestureCircleDrawn = false
                    gesturePoints.clear()
                    if (ev.actionMasked == MotionEvent.ACTION_UP && moved < 30 * density) {
                        openHostPage()
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (evaluateGestureCircle()) {
                        gestureCircleDrawn = true
                        gestureCircleTime = System.currentTimeMillis()
                    }
                }
                gesturePoints.clear()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True if the recorded stroke is one full circle. */
    private fun evaluateGestureCircle(): Boolean {
        if (gesturePoints.size < 20) return false
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var sumX = 0f
        var sumY = 0f
        for ((x, y) in gesturePoints) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sumX += x
            sumY += y
        }
        val w = maxX - minX
        val h = maxY - minY
        if (w < 80 || h < 80) return false
        val ratio = w / h
        if (ratio < 0.4f || ratio > 2.5f) return false
        val cx = sumX / gesturePoints.size
        val cy = sumY / gesturePoints.size
        var sumR = 0f
        for ((x, y) in gesturePoints) sumR += hypot(x - cx, y - cy)
        val avgR = sumR / gesturePoints.size
        if (avgR < 40) return false
        var total = 0.0
        var prev = atan2((gesturePoints[0].second - cy).toDouble(), (gesturePoints[0].first - cx).toDouble())
        for (i in 1 until gesturePoints.size) {
            val a = atan2((gesturePoints[i].second - cy).toDouble(), (gesturePoints[i].first - cx).toDouble())
            var d = a - prev
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
            total += d
            prev = a
        }
        if (abs(total) < 4.5) return false
        val (fx, fy) = gesturePoints.first()
        val (lx, ly) = gesturePoints.last()
        if (hypot(fx - lx, fy - ly) > avgR) return false
        gestureCx = cx
        gestureCy = cy
        gestureRadius = avgR
        return true
    }

    private fun openHostPage() {
        startActivity(Intent(this, AlienHostActivity::class.java))
    }
}
