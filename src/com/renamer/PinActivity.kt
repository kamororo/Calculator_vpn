package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * The lock page is a full-screen calculator. It works as a
 * normal calculator for any calculation.
 *
 * Secret unlock, two ways, both end with "=":
 *   <private PIN> =   -> private (all sections)
 *   <public PIN> =    -> public (public section only)
 *   = <private PIN> = -> private
 *   = <public PIN> =  -> public
 * (The default PINs live in the companion below; change
 *  them before distributing your own build.)
 * Anything else just calculates; no PIN is ever named on
 * this page, and a wrong code simply shows its result.
 */
class PinActivity : Activity() {
    companion object {
        const val PIN_HIDDEN = "1111"
        const val PIN_PUBLIC = "0000"
        const val EXTRA_MODE = "mode"
        const val MODE_HIDDEN = "hidden"
        const val MODE_PUBLIC = "public"
    }

    private lateinit var exprText: TextView
    private lateinit var mainText: TextView

    // Current expression being built, e.g. "12+3".
    private var expr = ""
    // The number currently being typed (digits after the last
    // operator), kept as an exact string so "0000" != "0".
    private var currentNumber = ""
    private var justEvaluated = false
    private var freshEntry = true

    private fun glassButton(label: String, accent: Boolean, sizePx: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            // Soft light calculator: dark-gray text on
            // translucent white keys, so it stays readable
            // on the pastel background. The "=" key gets a
            // soft green tint instead of a saturated color.
            setTextColor(CalcTheme.theme(this@PinActivity).buttonText)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
            // Soft light pastel gradient, much lighter
            // and calmer than the old dark blue-teal one.
            background = CalcTheme.gradient(this@PinActivity)
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
        exprText = TextView(this).apply {
            text = ""; textSize = 18f
            setTextColor(Color.parseColor("#777777"))
            gravity = Gravity.END
        }
        mainText = TextView(this).apply {
            text = "0"; textSize = 52f
            typeface = when (CalcTheme.displayFont(this@PinActivity)) {
                "mono" -> Typeface.MONOSPACE
                "normal" -> Typeface.DEFAULT
                else -> Typeface.DEFAULT_BOLD
            }
            setTextColor(CalcTheme.theme(this@PinActivity).display)
            gravity = Gravity.END
        }
        display.addView(exprText)
        display.addView(mainText)
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
        for (rowDef in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((label, accent) in rowDef) {
                row.addView(glassButton(label, accent, btnSize) { onKey(label) })
            }
            root.addView(row)
        }
        root.addView(TextView(this).apply {
            text = "created by : kamyar"; textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 10, 0, 0)
        })
        setContentView(root)
        root.fitsSystemWindows = true
    }

    /** Whole-app lock screen: only the lock message and
     *  a field for the creator's entry password. A correct
     *  entry unlocks the app; anything else leaves it
     *  locked. Uninstall + reinstall (or clear data) is
     *  the other way out, because it wipes the lock flag. */
    private fun showLockScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        root.addView(TextView(this).apply {
            text = AppLock.message(this@PinActivity)
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })
        val input = EditText(this).apply {
            hint = AppLanguage.t(this@PinActivity, "رمز ورود", "Entry password")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = AppLanguage.t(this@PinActivity, "ورود", "Enter")
            setOnClickListener {
                val pw = input.text.toString().trim()
                if (pw.isBlank()) return@setOnClickListener
                if (AppLock.unlock(this@PinActivity, pw)) {
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

    private fun refreshDisplay() {
        exprText.text = expr
        mainText.text = if (currentNumber.isNotEmpty()) currentNumber
                        else if (expr.isNotEmpty()) expr else "0"
    }

    private fun onKey(k: String) {
        when (k) {
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
                    // "=" then an operator: keep calculating
                    // from the shown result, if any.
                    if (mainText.text.toString() != "0") {
                        expr = mainText.text.toString()
                        currentNumber = expr
                    } else return
                }
                if (currentNumber.isEmpty()) return
                expr += when (k) {
                    "−" -> "-"; "×" -> "*"; "÷" -> "/"; else -> k
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
                val r = if (currentNumber.startsWith("-")) currentNumber.drop(1)
                        else "-$currentNumber"
                expr = expr.dropLast(currentNumber.length) + r
                currentNumber = r
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
            else -> { // digit
                if (justEvaluated) {
                    // After "=", a fresh number starts a new
                    // calculation (or a secret code, see onEquals).
                    expr = ""; currentNumber = ""
                    justEvaluated = false
                }
                if (freshEntry && currentNumber.isEmpty()) freshEntry = false
                if (currentNumber.length >= 12) return
                // Keep the exact typed string, so "0000"
                // stays "0000" and unlocks as a code.
                currentNumber += k
                expr += k
            }
        }
        if (k == "=") return // onEquals already set the display
        refreshDisplay()
    }

    private fun onEquals() {
        // "=" with nothing typed just arms the next code
        // entry (the first "=" of "= <PIN> =").
        if (expr.isEmpty()) {
            justEvaluated = true
            refreshDisplay()
            return
        }
        // Secret codes: a bare number, typed either as
        // "<PIN> =" or after a previous "=" ("= <PIN> =").
        val code = if (expr.isNotEmpty() && currentNumber == expr) currentNumber
                   else if (justEvaluated) currentNumber else null
        val publicPin = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            .getString("public_pin", null)?.takeIf { it.length == 4 } ?: PIN_PUBLIC
        val mode = when (code) {
            PIN_HIDDEN -> MODE_HIDDEN
            publicPin -> MODE_PUBLIC
            else -> null
        }
        if (mode != null) {
            val intent = Intent(this, WelcomeActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }
            startActivity(intent)
            expr = ""; currentNumber = ""
            justEvaluated = false; freshEntry = true
            refreshDisplay()
            return
        }
        val result = evaluate(expr)
        if (result == null) {
            exprText.text = expr
            mainText.text = AppLanguage.t(this@PinActivity, "خطا", "Error")
            expr = ""; currentNumber = ""
            justEvaluated = false; freshEntry = true
            return
        }
        val r = formatNumber(result)
        exprText.text = "$expr ="
        mainText.text = r
        expr = r
        currentNumber = r
        justEvaluated = true
        freshEntry = false
    }

    private fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return AppLanguage.t(this@PinActivity, "خطا", "Error")
        val s = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e12) {
            v.toLong().toString()
        } else {
            "%.10f".format(v).trimEnd('0').trimEnd('.')
        }
        return s
    }

    /** Evaluate + - * / with * / first, left to right. */
    private fun evaluate(e: String): Double? {
        if (e.isBlank()) return null
        val tokens = mutableListOf<String>()
        var num = ""
        for (ch in e) {
            if (ch.isDigit() || ch == '.') num += ch
            else if (ch in "+-*/") {
                if (num.isEmpty() && ch == '-' && tokens.isEmpty()) { num = "-"; continue }
                if (num.isEmpty()) return null
                tokens += num; num = ""
                tokens += ch.toString()
            } else return null
        }
        if (num.isEmpty()) return null
        tokens += num
        // First pass: * and /
        val pass = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if ((t == "*" || t == "/") && pass.isNotEmpty()) {
                val left = pass.removeAt(pass.size - 1).toDoubleOrNull() ?: return null
                val right = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: return null
                if (t == "/" && right == 0.0) return null
                pass += formatNumber(if (t == "*") left * right else left / right)
                i += 2
            } else {
                pass += t; i++
            }
        }
        // Second pass: + and -
        var result = pass[0].toDoubleOrNull() ?: return null
        i = 1
        while (i < pass.size) {
            val op = pass[i]
            val v = pass.getOrNull(i + 1)?.toDoubleOrNull() ?: return null
            result = if (op == "+") result + v else if (op == "-") result - v else return null
            i += 2
        }
        return result
    }
}
