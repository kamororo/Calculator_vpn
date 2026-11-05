package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*

/** Hidden emergency settings, reached only through the
 *  circle gesture + password in EmergencyActivity.
 *  Shows the main subscription link (never deletable)
 *  and any custom links the user added (deletable).
 *  Custom links work alongside the main link for
 *  emergency connect: EmergencyActivity fetches all of
 *  them fresh on every connect and picks the lowest
 *  ping across the whole combined set. */
class EmergencySettingsActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var listContainer: LinearLayout

    private fun prefs() =
        getSharedPreferences("emergency_settings", MODE_PRIVATE)

    private fun saveCustom(list: List<String>) {
        prefs().edit().putString("custom_subs", list.joinToString("\n")).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First open after isolation: copy the current shared
        // TUN values once, so the fields below start filled.
        TunPrefs.migrateFileIfNeeded(this, TunPrefs.EMERGENCY)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("تنظیمات اتصال اضطراری", "Emergency connect settings"); textSize = 20f
            setPadding(0, 0, 0, 16)
        })

        // Main link: shown, never deletable.
        root.addView(TextView(this).apply {
            text = L("لینک ساب اصلی", "Main subscription link"); textSize = 16f
            setPadding(0, 8, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = EmergencyActivity.EMERGENCY_SUB_URL; textSize = 13f
            setTextColor(Color.parseColor("#555555"))
        })
        root.addView(TextView(this).apply {
            text = L("لینک اصلی غیرقابل حذف است.", "The main link cannot be deleted.")
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = L("لینک‌های ساب دلخواه", "Custom subscription links"); textSize = 16f
            setPadding(0, 8, 0, 4)
        })
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        val input = EditText(this).apply {
            hint = L("لینک ساب دلخواه (https://...)", "Custom subscription link (https://...)")
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = L("افزودن لینک ساب", "Add subscription link")
            setOnClickListener {
                val url = input.text.toString().trim()
                if (url.isBlank() || !url.startsWith("http")) return@setOnClickListener
                val cur = EmergencyActivity.customSubs(this@EmergencySettingsActivity)
                if (!cur.contains(url)) {
                    cur += url
                    saveCustom(cur)
                }
                input.setText("")
                refreshList()
            }
        })
        // TUN settings for THIS section only. They live in
        // the emergency prefs file, never in v2ray's file,
        // so changing v2ray's MTU/DNS/SOCKS/per-app can
        // never affect emergency (and vice versa).
        root.addView(TextView(this).apply {
            text = L("تنظیمات تونل اضطراری", "Emergency tunnel settings"); textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = L("فقط همین بخش را تغییر می‌دهد؛ تنظیمات وی‌توری دست نمی‌خورد.", "Changes only this section; v2ray's settings are untouched.")
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 8)
        })
        val tunPrefs = getSharedPreferences(TunPrefs.EMERGENCY, MODE_PRIVATE)
        val mtuF = EditText(this).apply {
            hint = L("MTU (مثلاً 1500)", "MTU (e.g. 1500)")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(tunPrefs.getString("vpn_mtu", null) ?: tunPrefs.getString("mtu", "1500") ?: "1500")
        }
        root.addView(mtuF)
        val dnsF = EditText(this).apply {
            hint = L("DNS (مثلاً 1.1.1.1,8.8.8.8)", "DNS (e.g. 1.1.1.1,8.8.8.8)")
            setText(tunPrefs.getString("dns", "1.1.1.1,8.8.8.8") ?: "1.1.1.1,8.8.8.8")
        }
        root.addView(dnsF)
        val socksF = EditText(this).apply {
            hint = L("پورت SOCKS (مثلاً 10808)", "SOCKS port (e.g. 10808)")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(tunPrefs.getString("socks_port", "10808") ?: "10808")
        }
        root.addView(socksF)
        root.addView(Button(this).apply {
            text = L("ذخیره تنظیمات تونل", "Save tunnel settings")
            setOnClickListener {
                val mtuV = mtuF.text.toString().trim().ifBlank { "1500" }
                tunPrefs.edit()
                    .putString("vpn_mtu", mtuV)
                    .putString("mtu", mtuV)
                    .putString("dns", dnsF.text.toString().trim().ifBlank { "1.1.1.1,8.8.8.8" })
                    .putString("socks_port", socksF.text.toString().trim().ifBlank { "10808" })
                    .apply()
                Toast.makeText(this@EmergencySettingsActivity, L("ذخیره شد", "Saved"), Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = L("تنظیمات هر اپ (اضطراری)", "Per-app settings (emergency)")
            setOnClickListener { showTunPerAppSettings() }
        })
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
        refreshList()
    }

    /** Per-app mode for the emergency section only,
     *  mirroring VpnActivity's dialog but reading and
     *  writing the emergency TUN file. */
    private fun showTunPerAppSettings() {
        val tp = getSharedPreferences(TunPrefs.EMERGENCY, MODE_PRIVATE)
        val mode = tp.getString("per_app_mode", "bypass") ?: "bypass"
        val saved = tp.getString("per_app_set", "")?.lines()
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تنظیمات هر اپ", "Per-app settings"))
            .setItems(arrayOf(
                L("Bypass selected apps (پروکسی نشوند)", "Bypass selected apps (do not proxy)"),
                L("Only selected apps (فقط این‌ها پروکسی شوند)", "Only selected apps (only these proxied)"),
                L("Disabled (همه اپ‌ها پروکسی شوند)", "Disabled (all apps proxied)")
            )) { _, which ->
                val newMode = when (which) {
                    0 -> "bypass"; 1 -> "only"; else -> "disabled"
                }
                if (newMode == "disabled") {
                    tp.edit().putString("per_app_mode", "disabled")
                        .putString("per_app_set", "").apply()
                    Toast.makeText(this, L("Per-app غیرفعال شد.", "Per-app disabled."), Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val apps = packageManager.getInstalledApplications(0)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { packageManager.getApplicationLabel(it).toString() }
                val names = apps.map {
                    packageManager.getApplicationLabel(it).toString()
                }.toTypedArray()
                val checked = BooleanArray(apps.size) { i ->
                    saved.contains(apps[i].packageName)
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle(L("اپ‌ها را انتخاب کن", "Select apps"))
                    .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }
                            .map { it.packageName }
                        tp.edit().putString("per_app_mode", newMode)
                            .putString("per_app_set", chosen.joinToString("\n")).apply()
                        Toast.makeText(this, L("Per-app ذخیره شد: ${chosen.size} اپ.", "Per-app saved: ${chosen.size} apps."), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(L("لغو", "Cancel"), null)
                    .show()
            }
            .show()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val cur = EmergencyActivity.customSubs(this)
        if (cur.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز لینک دلخواهی اضافه نشده است.", "No custom link added yet.")
                setTextColor(Color.GRAY)
            })
            return
        }
        for (url in cur) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(TextView(this).apply {
                text = url; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    val next = EmergencyActivity.customSubs(this@EmergencySettingsActivity)
                    next.remove(url)
                    saveCustom(next)
                    refreshList()
                }
            })
            listContainer.addView(row)
        }
    }
}
