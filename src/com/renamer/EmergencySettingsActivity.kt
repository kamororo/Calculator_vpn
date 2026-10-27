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
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
        refreshList()
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
