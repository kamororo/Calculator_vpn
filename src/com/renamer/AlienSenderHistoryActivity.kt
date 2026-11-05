package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sender-link history (2.29). Every link
 *  the user ever created, newest first,
 *  numbered and dated. Tap one to make it
 *  the active link again — its share link
 *  shows on the sender page and new messages
 *  go through it. The pencil renames a link.
 *  The manage link is never shown here (or
 *  anywhere): it stays in the background. */
class AlienSenderHistoryActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var col: LinearLayout

    // 2.44: multi-select state for bulk delete.
    private val selectedHistoryIds = mutableSetOf<String>()
    private var deleteBar: LinearLayout? = null
    private var deleteBtn: Button? = null
    private var selectAllBtn: Button? = null

    /** 2.44: delete bar + select-all label follow
     *  the selection; never rebuilds (the checkbox
     *  already shows its own state). */
    private fun updateDeleteBar() {
        val n = selectedHistoryIds.size
        deleteBar?.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        deleteBtn?.text = L("🗑  حذف انتخاب‌شده‌ها ($n)", "🗑  Delete selected ($n)")
        val total = AlienSub.senderLinks(this).size
        selectAllBtn?.text = if (n > 0 && n == total) L("لغو انتخاب همه", "Deselect all")
            else L("انتخاب همه", "Select all")
    }

    private fun refreshHistorySelection() {
        updateDeleteBar()
        rebuild()
    }

    private fun confirmDeleteSelected() {
        val ids = selectedHistoryIds.toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(L("حذف لینک‌ها", "Delete links"))
            .setMessage(L("${ids.size} لینک حذف شود؟ لینک‌هایشان از راه دور هم پاک می‌شوند و دیگر قابل برگشت نیستند.",
                "${ids.size} links will be deleted? Their remote links are deleted too and cannot be restored."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                Thread {
                    // every part of every
                    // link is deleted and
                    // checked. A link whose
                    // remote delete fails keeps
                    // its local record and its
                    // manage link; the toast
                    // says so honestly instead
                    // of always saying deleted.
                    val succeeded = mutableSetOf<String>()
                    var failed = 0
                    try {
                        val links = AlienSub.senderLinks(this)
                        for (e in links) if (e.id in ids) {
                            val ok = try { AlienChats.deleteRemoteAll(e.manage) } catch (_: Exception) { false }
                            if (ok) succeeded += e.id else failed++
                        }
                        if (succeeded.size + failed < ids.size) {
                            failed += ids.size - succeeded.size - failed
                        }
                    } catch (_: Exception) {}
                    if (succeeded.isNotEmpty()) AlienSub.deleteSenderLinks(this, succeeded)
                    runOnUiThread {
                        selectedHistoryIds.clear()
                        rebuild()
                        if (failed == 0) {
                            Toast.makeText(this, L("حذف شد", "Deleted"), Toast.LENGTH_SHORT).show()
                            try {  } catch (_: Exception) {}
                        } else {
                            Toast.makeText(this,
                                L("$failed لینک از راه دور حذف نشد و رکوردشان ماند",
                                    "$failed links could not be deleted remotely; their records were kept"),
                                Toast.LENGTH_LONG).show()
                            try {  } catch (_: Exception) {}
                        }
                    }
                }.start()
            }
            .setNegativeButton(L("نه", "Cancel"), null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = L("سابقه لینک‌ها", "Link history")
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
        })
        root.addView(TextView(this).apply {
            text = L("روی هر لینک بزن تا همان لینک فعال شود؛ با ✎ برایش اسم بگذار؛ با □ چند لینک را انتخاب کن و یکجا حذفشان کن",
                "Tap a link to activate it again; use ✎ to name it; tick □ on several links to delete them together")
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, dp(16))
        })
        // 2.44: selection toolbar + chic bulk bar.
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selectAllBtn = Button(this).apply {
            text = L("انتخاب همه", "Select all")
            textSize = 13f
            isAllCaps = false
            background = HydraUi.glassPill(
                Color.parseColor("#E0F2F1"), Color.parseColor("#80CBC4"), 16f,
                this@AlienSenderHistoryActivity)
            setTextColor(Color.parseColor("#00695C"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val links = AlienSub.senderLinks(this@AlienSenderHistoryActivity)
                if (links.isNotEmpty() && selectedHistoryIds.size == links.size) selectedHistoryIds.clear()
                else selectedHistoryIds.addAll(links.map { it.id })
                refreshHistorySelection()
            }
        }
        toolRow.addView(selectAllBtn)
        root.addView(toolRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        deleteBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        deleteBtn = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            elevation = 4f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * resources.displayMetrics.density
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(Color.parseColor("#E53935"), Color.parseColor("#FF7043"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { confirmDeleteSelected() }
        }
        deleteBar!!.addView(deleteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(deleteBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col)
        setContentView(scroll)
        HelpUi.attach(this, "history")
        rebuild()
    }

    private fun fmtDate(ts: Long): String {
        return try {
            SimpleDateFormat("yyyy/MM/dd – HH:mm", Locale.getDefault()).format(Date(ts))
        } catch (_: Exception) { "" }
    }

    private fun rebuild() {
        col.removeAllViews()
        val links = AlienSub.senderLinks(this)
        val active = AlienSub.activeSenderLink(this)
        if (links.isEmpty()) {
            col.addView(TextView(this).apply {
                text = L("هنوز لینکی ساخته نشده است", "No links created yet")
                textSize = 13f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            })
            return
        }
        for ((index, e) in links.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = HydraUi.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 20f,
                    this@AlienSenderHistoryActivity)
                elevation = 3f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                isClickable = true
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = if (e.name.isBlank()) L("لینک ${index + 1}", "Link ${index + 1}") else e.name
            texts.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1A237E"))
            })
            val sub = StringBuilder(fmtDate(e.createdAt))
            if (active != null && e.id == active.id) {
                if (sub.isNotEmpty()) sub.append("  •  ")
                sub.append(if (AppLanguage.isEnglish(this)) "active" else "فعال")
            }
            texts.addView(TextView(this).apply {
                text = sub.toString()
                textSize = 12f
                setTextColor(if (active != null && e.id == active.id)
                    Color.parseColor("#2E7D32") else Color.GRAY)
            })
            val check = android.widget.CheckBox(this).apply {
                isChecked = selectedHistoryIds.contains(e.id)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F766E"))
                setOnClickListener {
                    if (isChecked) selectedHistoryIds.add(e.id) else selectedHistoryIds.remove(e.id)
                    updateDeleteBar()
                }
            }
            card.addView(check)
            card.addView(texts)
            val renameBtn = Button(this).apply {
                text = "✎"
                textSize = 14f
                background = HydraUi.glassPill(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 14f,
                    this@AlienSenderHistoryActivity)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            renameBtn.setOnClickListener {
                val input = EditText(this).apply {
                    setText(e.name)
                    hint = L("اسم لینک", "Link name")
                    textSize = 15f
                    inputType = InputType.TYPE_CLASS_TEXT
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                AlertDialog.Builder(this)
                    .setTitle(L("اسم برای لینک", "Name for the link"))
                    .setView(input)
                    .setPositiveButton(L("ثبت", "Save")) { _, _ ->
                        AlienSub.renameSenderLink(this, e.id, input.text.toString().trim())
                        rebuild()
                    }
                    .setNegativeButton(L("نه", "Cancel"), null)
                    .show()
            }
            card.addView(renameBtn)
            card.setOnClickListener {
                // 2.44: while anything is selected, a
                // tap toggles selection instead of
                // activating (and leaving the page).
                if (selectedHistoryIds.isNotEmpty()) {
                    if (selectedHistoryIds.contains(e.id)) selectedHistoryIds.remove(e.id)
                    else selectedHistoryIds.add(e.id)
                    refreshHistorySelection()
                    return@setOnClickListener
                }
                AlienSub.setActiveSenderLink(this, e.id)
                Toast.makeText(this,
                    L("لینک فعال شد", "Link activated"), Toast.LENGTH_SHORT).show()
                finish()
            }
            col.addView(card)
        }
        updateDeleteBar()
    }
}
