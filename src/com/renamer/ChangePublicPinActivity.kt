package com.renamer

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*

/** Change ONLY the public section entry password.
 *  SECURITY: the private (hidden) section password is
 *  never displayed, never changed and never written
 *  from this page. It is only compared against (via
 *  the existing PinActivity constant) so that a user
 *  cannot pick it as their new public password — and
 *  that rejection shows only a generic error, never
 *  the value itself. */
class ChangePublicPinActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "renamer_settings"
        const val KEY_PUBLIC_PIN = "public_pin"
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun pinField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Theme.bg(this@ChangePublicPinActivity))
            fitsSystemWindows = true
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("تغییر رمز ورود", "Change entry password"); textSize = 22f
            setTextColor(Theme.fg(this@ChangePublicPinActivity))
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = L("رمز ورود را اینجا به رمز دلخواهت عوض می‌کنی.", "Change the entry password here to one of your choice.")
            textSize = 13f
            setTextColor(Theme.subFg(this@ChangePublicPinActivity))
            setPadding(0, 0, 0, dp(16))
        })

        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f
            setTextColor(Theme.fg(this@ChangePublicPinActivity))
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(label(L("رمز فعلی بخش عمومی", "Current public password")))
        val currentF = pinField(L("رمز فعلی", "Current password"))
        root.addView(currentF)
        root.addView(label(L("رمز جدید (۴ رقم)", "New password (4 digits)")))
        val newF = pinField(L("رمز جدید", "New password"))
        root.addView(newF)
        root.addView(label(L("تکرار رمز جدید", "Repeat new password")))
        val repeatF = pinField(L("تکرار رمز جدید", "Repeat new password"))
        root.addView(repeatF)

        val statusText = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)) }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = L("ذخیره", "Save")
            textSize = 17f
            setPadding(0, dp(20), 0, dp(20))
            setOnClickListener {
                val stored = prefs().getString(KEY_PUBLIC_PIN, PinActivity.PIN_PUBLIC) ?: PinActivity.PIN_PUBLIC
                val current = currentF.text.toString().trim()
                val newPin = newF.text.toString().trim()
                val repeat = repeatF.text.toString().trim()
                val error: String? = when {
                    current != stored ->
                        L("رمز فعلی درست نیست.", "The current password is incorrect.")
                    newPin.length != 4 || !newPin.all { it.isDigit() } ->
                        L("رمز جدید باید دقیقاً ۴ رقم باشد.", "The new password must be exactly 4 digits.")
                    newPin != repeat ->
                        L("تکرار رمز جدید با رمز جدید یکی نیست.", "The repeated password does not match the new password.")
                    // Generic rejection only: the private
                    // password's value is never shown here.
                    newPin == PinActivity.PIN_HIDDEN ->
                        L("این رمز قابل استفاده نیست.", "This password cannot be used.")
                    else -> null
                }
                if (error != null) {
                    statusText.text = error
                    return@setOnClickListener
                }
                prefs().edit().putString(KEY_PUBLIC_PIN, newPin).apply()
                statusText.text = L("رمز بخش عمومی تغییر کرد.", "Public password changed.")
                Toast.makeText(this@ChangePublicPinActivity, L("رمز بخش عمومی تغییر کرد", "Public password changed"), Toast.LENGTH_SHORT).show()
                currentF.setText(""); newF.setText(""); repeatF.setText("")
            }
        })
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }
}
