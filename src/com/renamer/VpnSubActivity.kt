package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Subscription group setting, v2rayNG-style:
 *  one row per subscription with its name, URL,
 *  last-update date, share / edit / delete icons
 *  and an enable toggle. Deleting a row removes
 *  the subscription only — its configs stay in the
 *  list (never auto-deleted). */
class VpnSubActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        scroll.addView(root)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@VpnSubActivity).apply {
                text = "←"
                setOnClickListener { finish() }
            })
            addView(TextView(this@VpnSubActivity).apply {
                text = L("تنظیمات گروه سابسکریبشن", "Subscription group setting")
                textSize = 18f
                setPadding((12 * d).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(status)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@VpnSubActivity).apply {
                text = L("+ افزودن ساب", "+ Add subscription")
                setOnClickListener {
                    startActivity(Intent(this@VpnSubActivity, VpnSubEditActivity::class.java))
                }
            })
            addView(Button(this@VpnSubActivity).apply {
                text = L("به‌روزرسانی همه", "Update all")
                setPadding((12 * d).toInt(), 0, 0, 0)
                setOnClickListener { updateAll() }
            })
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        setContentView(scroll)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun fmtDate(ms: Long): String {
        if (ms <= 0) return L("هرگز", "Never")
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val d = resources.displayMetrics.density
        val items = VpnSubs.loadItems(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = L("هنوز سابی اضافه نشده است.", "No subscription added yet.")
                setTextColor(Color.GRAY)
                setPadding(0, (16 * d).toInt(), 0, 0)
            })
            return
        }
        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * d
                    setColor(Color.parseColor("#F2F5F9"))
                    setStroke((2 * d).toInt(), Color.parseColor("#4A5A7A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = item.displayName(); textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(Switch(this).apply {
                isChecked = item.enabled
                setOnCheckedChangeListener { _, checked ->
                    item.enabled = checked
                    val all = VpnSubs.loadItems(this@VpnSubActivity)
                    val idx = all.indexOfFirst { it.url == item.url }
                    if (idx >= 0) all[idx] = item
                    VpnSubs.saveItems(this@VpnSubActivity, all)
                }
            })
            card.addView(top)
            card.addView(TextView(this).apply {
                text = item.url; textSize = 13f
                setTextColor(Color.GRAY)
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = L("آخرین به‌روزرسانی: ${fmtDate(item.lastUpdated)}  •  ${item.configKeys.size} کانفیگ",
                    "Last update: ${fmtDate(item.lastUpdated)}  •  ${item.configKeys.size} configs")
                textSize = 13f
                setTextColor(Color.GRAY)
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            actions.addView(Button(this).apply {
                text = "↗"
                setOnClickListener {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("sub", item.url))
                        status.text = L("لینک ساب کپی شد.", "Subscription link copied.")
                    } catch (_: Exception) {}
                }
            })
            actions.addView(Button(this).apply {
                text = "✎"
                setOnClickListener {
                    startActivity(Intent(this@VpnSubActivity, VpnSubEditActivity::class.java)
                        .putExtra("url", item.url))
                }
            })
            actions.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@VpnSubActivity)
                        .setTitle(L("حذف سابسکریبشن", "Delete subscription"))
                        .setMessage(L("ساب «${item.displayName()}» حذف شود؟ کانفیگ‌هایش در لیست باقی می‌مانند.",
                            "Delete subscription “${item.displayName()}”? Its configs stay in the list."))
                        .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                            val all = VpnSubs.loadItems(this@VpnSubActivity)
                            all.removeAll { it.url == item.url }
                            VpnSubs.saveItems(this@VpnSubActivity, all)
                            status.text = L("ساب حذف شد.", "Subscription deleted.")
                            refreshList()
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        .show()
                }
            })
            card.addView(actions)
            listBox.addView(card)
        }
    }

    private fun updateAll() {
        val items = VpnSubs.loadItems(this).filter { it.enabled }
        if (items.isEmpty()) {
            status.text = L("ساب فعالی برای به‌روزرسانی نیست.", "No enabled subscription to update.")
            return
        }
        status.text = L("در حال به‌روزرسانی ساب‌ها...", "Updating subscriptions...")
        Thread {
            var added = 0
            var received = 0
            var failed = 0
            val lines = mutableListOf<String>()
            for (item in items) {
                try {
                    val r = VpnSubs.fetchOne(this, item)
                    added += r.added
                    received += r.received
                    val label = VpnSubs.displayLabel(item)
                    if (r.message == "ok") {
                        lines += L("ساب $label: ${r.received} دریافت، ${r.added} جدید",
                            "Sub $label: ${r.received} received, ${r.added} new")
                    } else {
                        failed++
                        lines += L("ساب $label ناموفق: ${VpnSubs.reasonLabel(this, r.message)}",
                            "Sub $label failed: ${VpnSubs.reasonLabel(this, r.message)}")
                    }
                } catch (_: Exception) {
                    failed++
                    val label = VpnSubs.displayLabel(item)
                    lines += L("ساب $label ناموفق: خطای نامشخص",
                        "Sub $label failed: unknown error")
                }
            }
            runOnUiThread {
                val head = L("به‌روزرسانی انجام شد: $received کانفیگ دریافت شد، $added جدید اضافه شد.",
                    "Update done: $received configs received, $added new added.") +
                    if (failed > 0) L(" ($failed ساب ناموفق بود.)", " ($failed subs failed.)") else ""
                status.text = (listOf(head) + lines).joinToString("\n")
                refreshList()
            }
        }.start()
    }
}
