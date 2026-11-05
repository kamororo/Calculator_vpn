package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * The lock page is a full-screen calculator. It works as a
 * normal calculator for any calculation.
 *
 * Secret unlock: PINs end with "=":
 *   1111 =   -> private (all sections)
 *   0000 =   -> public (public section only)
 *   = 1111 = -> private
 *   = 0000 = -> public
 * Anything else just calculates; no PIN is ever named on
 * this page, and a wrong code simply shows its result.
 *
 * 1.82 shake unlock (prefs in LoginPersonalizationActivity):
 *   password       = PIN only (default)
 *   password_shake = correct PIN, then shake the phone N times;
 *                    the PIN alone never opens anything
 *   shake_only     = shake N times, no PIN, opens PUBLIC only
 *   passwordless   = straight to PUBLIC (PINs kept)
 * "Shake" is the USER shaking the phone (accelerometer), not
 * the phone vibrating. The private section opens ONLY with a
 * password; shaking alone can never open it.
 *
 * NOTE (2026-10-28): this file was rebuilt from the 1.81
 * compiled class after a failed patch script truncated the
 * source to 0 bytes. Logic was recovered from javap output
 * of out/classes.jar (1.81 build) plus the known 1.81 reads;
 * the 1.82 shake change is applied on top of that recovery.
 */
class PinActivity : Activity() {
    companion object {
        const val PIN_HIDDEN = "1111"
        const val PIN_PUBLIC = "0000"
        const val EXTRA_MODE = "mode"
        const val MODE_HIDDEN = "hidden"
        const val MODE_PUBLIC = "public"
    }

    private lateinit var mainText: TextView

    // Current expression being built, e.g. "12+3".
    private var expr = ""
    // The number currently being typed (digits after the last
    // operator), kept as an exact string so "0000" != "0".
    private var currentNumber = ""
    private var justEvaluated = false
    private var freshEntry = true

    // ---------- 1.82 shake unlock ----------
    // Pending mode after a correct PIN in password_shake mode;
    // cleared on pause and 30s after the PIN.
    private var pendingShakeMode: String? = null
    private var pendingShakeMs = 0L
    private var shakeCount = 0
    private var shakeWindowStart = 0L
    private var lastShakeMs = 0L
    private var sensorMgr: SensorManager? = null
    private val shakeListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val g = kotlin.math.sqrt(x * x + y * y + z * z) / 9.81f
            if (g < 1.8f) return
            val now = SystemClock.uptimeMillis()
            if (now - lastShakeMs < 400) return
            lastShakeMs = now
            val mode = unlockMode()
            if (mode == "password_shake") {
                if (pendingShakeMode == null) return
                if (now - pendingShakeMs > 30000) { pendingShakeMode = null; return }
            } else if (mode != "shake_only") return
            if (now - shakeWindowStart > 5000) { shakeCount = 0; shakeWindowStart = now }
            shakeCount++
            vibrateTick()
            if (shakeCount >= shakeTarget()) {
                shakeCount = 0
                when (mode) {
                    "shake_only" -> openMode(MODE_PUBLIC)
                    "password_shake" -> {
                        val m = pendingShakeMode; pendingShakeMode = null
                        if (m != null) openMode(m)
                    }
                }
                expr = ""; currentNumber = ""
                justEvaluated = false; freshEntry = true
                refreshDisplay()
            }
        }
    }

    // ---------- unlock options (prefs written
    // by LoginPersonalizationActivity) ----------
    private fun unlockMode(): String =
        getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("unlock_mode", "password") ?: "password"

    // 1.81's "unlock_vibrate_count" (haptic pulses) is no longer
    // read: 1.82 replaces it with a shake COUNT, its own pref.
    private fun shakeTarget(): Int =
        getSharedPreferences("renamer_settings", MODE_PRIVATE)
            .getInt("unlock_shake_count", 3).coerceIn(1, 5)

    private fun openMode(mode: String) {
        // 2.18: the welcome page can be switched off
        // from Personalization ("show_welcome", default
        // on); when off, go straight Home with the
        // same mode extra.
        val target = if (getSharedPreferences("renamer_settings", MODE_PRIVATE)
            .getBoolean("show_welcome", true)) {
            WelcomeActivity::class.java
        } else {
            HomeActivity::class.java
        }
        startActivity(Intent(this, target).apply {
            putExtra(EXTRA_MODE, mode)
        })
    }

    /** One short haptic tick: feedback that a shake was counted.
     *  Never blocks the unlock on failure. */
    private fun vibrateTick() {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return
            if (!vib.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(60)
            }
        } catch (_: Exception) {}
    }

    private fun glassButton(label: String, accent: Boolean, sizePx: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            // 2.48 studio: normal keys use the
            // background-aware text colour, the "="
            // key uses the accent-readable colour.
            setTextColor(if (accent) CalcTheme.accentTextColor(this@PinActivity) else CalcTheme.keyTextColor(this@PinActivity))
            elevation = 4f
            background = CalcTheme.buttonBackground(this@PinActivity, accent)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                .apply { setMargins(10, 10, 10, 10) }
            setOnClickListener { onClick() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First entry only: ask for the notification
        // permission, so the connection notification
        // (speed / country / disconnect-connect-restart)
        // can actually be shown. Asked once, never nagged.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val sp = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            if (!sp.getBoolean("notif_perm_asked", false) &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                sp.edit().putBoolean("notif_perm_asked", true).apply()
                android.app.AlertDialog.Builder(this)
                    .setTitle(AppLanguage.t(this, "اعلان اتصال", "Connection notification"))
                    .setMessage(AppLanguage.t(this, "برای اینکه وقتی وصل می‌شوی، سرعت و کشور کانفیگ و دکمه‌های قطع/وصل/ری‌استارت در اعلان دیده شود، اجازه اعلان لازم است.", "To show speed, config country and disconnect/connect/restart buttons in a notification while connected, notification permission is needed."))
                    .setPositiveButton(AppLanguage.t(this, "اجازه بده", "Allow")) { _, _ ->
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                    .setNegativeButton(AppLanguage.t(this, "فعلاً نه", "Not now"), null)
                    .show()
            }
        }
        if (AppLock.isLocked(this)) {
            showLockScreen()
            return
        }
        // 1.81 unlock modes: passwordless opens
        // straight into the PUBLIC section only;
        // the PINs are kept, so switching back to
        // "with password" restores them as-is.
        if (unlockMode() == "passwordless") {
            openMode(MODE_PUBLIC)
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
            // Soft light pastel gradient, much lighter
            // and calmer than the old dark blue-teal one.
            background = CalcTheme.background(this@PinActivity)
        }
        // Calculator display: expression on top, big result
        // below. This is the whole "screen" of the calculator.
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#88FFFFFF"))
                setStroke(2, Color.parseColor("#CCFFFFFF"))
            }
            background = bg
            setPadding(28, 28, 28, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        mainText = TextView(this).apply {
            text = "0"; textSize = 52f
            typeface = when (CalcTheme.displayFont(this@PinActivity)) {
                "mono" -> Typeface.MONOSPACE
                "normal" -> Typeface.DEFAULT
                else -> Typeface.DEFAULT_BOLD
            }
            setTextColor(CalcTheme.displayColor(this@PinActivity))
            gravity = Gravity.END
        }
        display.addView(mainText)
        // 1.82: no on-screen unlock gesture here. Shaking is
        // sensed by the accelerometer (see shakeListener);
        // the display stays a plain calculator display.
        root.addView(display)

        // Circular buttons: a square whose side is one
        // quarter of the screen width (minus padding and
        // margins), drawn as an oval, so each key is a true
        // circle. The display above expands to fill the rest
        // of the page, so the whole screen is still used.
        val dm = resources.displayMetrics
        val btnSize = (((dm.widthPixels - (24 * dm.density * 2).toInt() - 80) / 4) * CalcTheme.keyScale(this@PinActivity)).toInt()
        val rows = listOf(
            listOf("C" to false, "⌫" to false, "%" to false, "÷" to false),
            listOf("7" to false, "8" to false, "9" to false, "×" to false),
            listOf("4" to false, "5" to false, "6" to false, "−" to false),
            listOf("1" to false, "2" to false, "3" to false, "+" to false),
            listOf("±" to false, "0" to false, "." to false, "=" to true)
        )
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((label, accent) in row) {
                rowLayout.addView(glassButton(label, accent, btnSize) { onKey(label) })
            }
            root.addView(rowLayout)
        }
        root.addView(TextView(this).apply {
            text = "created by : kamyar"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 8, 0, 8)
        })
        setContentView(root)
        root.fitsSystemWindows = true
    }

    override fun onResume() {
        super.onResume()
        if (unlockMode() == "password_shake" || unlockMode() == "shake_only") {
            try {
                val sm = getSystemService(SENSOR_SERVICE) as? SensorManager
                sensorMgr = sm
                sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        try { sensorMgr?.unregisterListener(shakeListener) } catch (_: Exception) {}
        sensorMgr = null
        pendingShakeMode = null
        super.onPause()
    }

    private fun refreshDisplay() {
        mainText.text = if (currentNumber.isNotEmpty()) currentNumber
                        else if (expr.isNotEmpty()) expr
                        else "0"
    }

    private fun onKey(key: String) {
        when (key) {
            "C" -> {
                expr = ""; currentNumber = ""
                justEvaluated = false; freshEntry = true
            }
            "⌫" -> {
                if (currentNumber.isNotEmpty()) {
                    currentNumber = currentNumber.dropLast(1)
                    expr = expr.dropLast(1)
                }
                justEvaluated = false
            }
            "=" -> onEquals()
            "+", "−", "×", "÷" -> {
                if (currentNumber.isEmpty() && expr.isEmpty()) {
                    if (mainText.text == "0") return
                    expr = mainText.text.toString()
                    currentNumber = expr
                }
                if (currentNumber.isEmpty()) return
                expr += when (key) {
                    "−" -> "-"
                    "×" -> "*"
                    "÷" -> "/"
                    else -> key
                }
                currentNumber = ""
                justEvaluated = false
                freshEntry = false
            }
            "%" -> {
                if (currentNumber.isEmpty()) return
                val v = currentNumber.toDoubleOrNull() ?: return
                val r = formatNumber(v / 100.0)
                expr = expr.dropLast(currentNumber.length) + r
                currentNumber = r
            }
            "±" -> {
                if (currentNumber.isEmpty()) return
                val new = if (currentNumber.startsWith("-")) currentNumber.drop(1) else "-$currentNumber"
                expr = expr.dropLast(currentNumber.length) + new
                currentNumber = new
            }
            "." -> {
                if (currentNumber.contains(".")) return
                if (currentNumber.isEmpty()) {
                    if (freshEntry) { expr = ""; freshEntry = false }
                    currentNumber = "0."
                    expr += "0."
                } else {
                    currentNumber += "."
                    expr += "."
                }
                justEvaluated = false
            }
            else -> {
                if (justEvaluated) {
                    expr = ""; currentNumber = ""
                    justEvaluated = false
                }
                if (freshEntry && currentNumber.isEmpty()) freshEntry = false
                if (currentNumber.length >= 12) return
                currentNumber += key
                expr += key
            }
        }
        refreshDisplay()
    }

    private fun onEquals() {
        // "=" with nothing typed just arms the next code
        // entry (the first "=" of "= 1111 =").
        if (expr.isEmpty()) {
            justEvaluated = true
            refreshDisplay()
            return
        }
        // Secret codes: a bare number, typed either as
        // "1111 =" or after a previous "=" ("= 1111 =").
        // Fix: after an evaluation, the displayed
        // result is NOT a freshly typed code. A second
        // "=" must never re-check it as a PIN -- a code
        // counts only when typed since the last
        // evaluation (digit entry already clears
        // justEvaluated, and the "= 1111 =" arming
        // press returns early above, so justEvaluated
        // here can only mean "this value is an
        // evaluation result").
        val skipCodeCheck = justEvaluated
        val code = if (skipCodeCheck) null
                   else if (expr.isNotEmpty() && currentNumber == expr) currentNumber
                   else null
        if (skipCodeCheck && expr.isNotEmpty()) {
            try {
                            } catch (_: Exception) {}
        }
        val publicPin = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            .getString("public_pin", null)?.takeIf { it.length == 4 } ?: PIN_PUBLIC
        val mode = when (code) {
            PIN_HIDDEN -> MODE_HIDDEN
            publicPin -> MODE_PUBLIC
            else -> null
        }
        if (mode != null) {
            // 1.82: in password+shake mode the PIN alone
            // never opens anything: the user must shake the
            // phone shakeTarget() times within 30s of the PIN.
            if (unlockMode() == "password_shake") {
                pendingShakeMode = mode
                pendingShakeMs = SystemClock.uptimeMillis()
                shakeCount = 0; shakeWindowStart = 0L
                expr = ""; currentNumber = ""
                justEvaluated = false; freshEntry = true
                refreshDisplay()
                return
            }
            openMode(mode)
            expr = ""; currentNumber = ""
            justEvaluated = false; freshEntry = true
            refreshDisplay()
            return
        }
        val result = evaluate(expr)
        if (result == null) {
            mainText.text = AppLanguage.t(this@PinActivity, "خطا", "Error")
            expr = ""; currentNumber = ""
            justEvaluated = false; freshEntry = true
            return
        }
        val r = formatNumber(result)
        mainText.text = r
        expr = r
        currentNumber = r
        justEvaluated = true
        freshEntry = false
    }

    private fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return AppLanguage.t(this, "خطا", "Error")
        if (v == Math.floor(v) && Math.abs(v) < 1.0E12) return v.toLong().toString()
        return String.format("%.10f", v).trimEnd('0').trimEnd('.')
    }

    private fun evaluate(s: String): Double? {
        if (s.isBlank()) return null
        val tokens = ArrayList<String>()
        var cur = ""
        for (ch in s) {
            if (ch.isDigit() || ch == '.') {
                cur += ch
            } else if (ch in "+-*/") {
                if (ch == '-' && cur.isEmpty() && tokens.isEmpty()) {
                    cur = "-"
                } else {
                    if (cur.isEmpty()) return null
                    tokens.add(cur); cur = ""
                    tokens.add(ch.toString())
                }
            } else {
                return null
            }
        }
        if (cur.isEmpty()) return null
        tokens.add(cur)
        // First pass: * and / (left to right).
        val out = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if ((t == "*" || t == "/") && out.isNotEmpty()) {
                val lhs = out.removeAt(out.size - 1).toDoubleOrNull() ?: return null
                val rhs = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: return null
                if (t == "/" && rhs == 0.0) return null
                out.add(formatNumber(if (t == "*") lhs * rhs else lhs / rhs))
                i += 2
            } else {
                out.add(t)
                i++
            }
        }
        // Second pass: + and -.
        var result = out[0].toDoubleOrNull() ?: return null
        i = 1
        while (i < out.size) {
            val op = out[i]
            val rhs = out.getOrNull(i + 1)?.toDoubleOrNull() ?: return null
            if (op == "+") result += rhs
            else if (op == "-") result -= rhs
            else return null
            i += 2
        }
        return result
    }

    private fun showLockScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        root.addView(TextView(this).apply {
            text = AppLock.message(this@PinActivity)
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        val input = EditText(this).apply {
            hint = AppLanguage.t(this@PinActivity, "رمز ورود", "Entry password")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = AppLanguage.t(this@PinActivity, "ورود", "Enter")
            setOnClickListener {
                val v = input.text.toString().trim()
                if (v.isNotBlank() && AppLock.unlock(this@PinActivity, v)) {
                    recreate()
                } else {
                    input.setText("")
                    input.hint = AppLanguage.t(this@PinActivity, "رمز اشتباه است", "Wrong password")
                }
            }
        })
        setContentView(root)
        root.fitsSystemWindows = true
    }
}
