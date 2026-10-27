package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*

/** Imported Profile page, exactly like the second
 *  screenshot: dark navy header with a back arrow
 *  and "Imported Profile", then Profile Name
 *  (auto-filled, editable), Server Hostname
 *  (locked, from the .ovpn remote line), Username,
 *  a "Save password" checkbox, Password with an
 *  eye toggle, and at the bottom PROFILES (save
 *  and go back) and CONNECT (save and connect). */
class OpenVpnImportActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = OvpnStore.pending ?: run { finish(); return }
        val isEdit = OvpnStore.pendingIndex >= 0

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Header (dp, see below)
        val density0 = resources.displayMetrics.density
        fun dp0(v: Int): Int = (v * density0).toInt()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(OpenVpnActivity.NAVY))
            setPadding(dp0(24), dp0(20), dp0(24), dp0(20))
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp0(24), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = if (isEdit) "Edit Profile" else "Imported Profile"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        // All paddings in dp, not raw pixels: on a
        // high-density phone 32px is only ~12dp, which
        // is why the fields looked cramped and ran
        // into each other in v1.23.
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun label(text: String): TextView {
            return TextView(this).apply {
                this.text = text; textSize = 15f
                setTextColor(Color.parseColor(OpenVpnActivity.NAVY))
                setPadding(0, dp(28), 0, dp(6))
            }
        }
        fun spacer(): View {
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
            }
        }
        fun field(initial: String, enabled: Boolean = true): EditText {
            return EditText(this).apply {
                setText(initial)
                textSize = 19f
                setTextColor(Color.BLACK)
                isEnabled = enabled
                minHeight = dp(56)
                setPadding(0, dp(12), 0, dp(12))
            }
        }
        body.addView(label("Profile Name"))
        val nameField = field(profile.name)
        body.addView(nameField)
        body.addView(spacer())

        body.addView(label("Server Hostname (locked)"))
        // Locked: shown, not editable, like the screenshot.
        val hostField = field(profile.hostname, enabled = false)
        body.addView(hostField)
        body.addView(spacer())

        val userLabel = label("Username")
        userLabel.setTextColor(Color.parseColor("#999999"))
        val userField = field(profile.username)
        userField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                userLabel.setTextColor(if (s.isNullOrBlank()) Color.parseColor("#999999")
                    else Color.parseColor(OpenVpnActivity.NAVY))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if (profile.username.isNotBlank()) userLabel.setTextColor(Color.parseColor(OpenVpnActivity.NAVY))
        body.addView(userLabel)
        body.addView(userField)
        body.addView(spacer())

        val saveBox = CheckBox(this).apply {
            text = "Save password"
            isChecked = profile.savePassword
            textSize = 18f
            setTextColor(Color.parseColor(OpenVpnActivity.NAVY))
            setPadding(0, dp(24), 0, dp(12))
        }
        body.addView(saveBox)

        val passLabel = label("Password")
        passLabel.setTextColor(Color.parseColor("#999999"))
        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val passField = EditText(this).apply {
            setText(profile.password)
            textSize = 19f
            minHeight = dp(56)
            setPadding(0, dp(12), 0, dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        passField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                passLabel.setTextColor(if (s.isNullOrBlank()) Color.parseColor("#999999")
                    else Color.parseColor(OpenVpnActivity.NAVY))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if (profile.password.isNotBlank()) passLabel.setTextColor(Color.parseColor(OpenVpnActivity.NAVY))
        passRow.addView(passField)
        var showPass = false
        passRow.addView(TextView(this).apply {
            text = "👁"; textSize = 22f
            setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener {
                showPass = !showPass
                passField.inputType = if (showPass) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                passField.transformationMethod = if (showPass) null
                    else PasswordTransformationMethod.getInstance()
                passField.setSelection(passField.text.length)
                text = if (showPass) "👁" else "👁"
            }
        })
        body.addView(passLabel)
        body.addView(passRow)
        body.addView(spacer())

        // Bottom buttons: PROFILES (gray text) and
        // CONNECT (orange), like the screenshot.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(48), 0, 0)
        }
        bottom.addView(Button(this).apply {
            text = "PROFILES"; textSize = 17f
            setTextColor(Color.parseColor("#999999"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                saveAndFinish(nameField.text.toString(), userField.text.toString(),
                    passField.text.toString(), saveBox.isChecked, connect = false)
            }
        })
        bottom.addView(Button(this).apply {
            text = "CONNECT"; textSize = 17f
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.parseColor(OpenVpnActivity.ORANGE))
            }
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                saveAndFinish(nameField.text.toString(), userField.text.toString(),
                    passField.text.toString(), saveBox.isChecked, connect = true)
            }
        })
        body.addView(bottom)
        root.addView(body)
        scroll.addView(root)
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

    private fun saveAndFinish(name: String, user: String, pass: String, savePass: Boolean, connect: Boolean) {
        val profile = OvpnStore.pending ?: run { finish(); return }
        val finalName = name.trim().ifBlank { profile.name }
        profile.name = finalName
        profile.username = user.trim()
        profile.password = pass
        profile.savePassword = savePass
        val profiles = OvpnStore.load(this)
        val idx = OvpnStore.pendingIndex
        if (idx >= 0 && idx < profiles.size) {
            profiles[idx] = profile
        } else {
            profiles += profile
        }
        // If Save password is off, do not persist the
        // password; the profile is still saved, and
        // connecting will ask for it again.
        if (!savePass) profile.password = ""
        OvpnStore.save(this, profiles)
        OvpnStore.pending = null
        OvpnStore.pendingIndex = -1
        if (connect) {
            // OpenVpnActivity must own VpnService.prepare,
            // so hand the profile back and let it
            // connect on resume.
            OvpnStore.pendingConnect = profile.copy(password = pass)
        }
        finish()
    }
}
