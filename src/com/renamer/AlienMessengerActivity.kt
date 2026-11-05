package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Alien Messenger (2.28). Three screens in
 *  one activity:
 *  - LIST: the conversation history on top,
 *    with the counterpart's name on each row;
 *    tap a name to enter that chat, long-press
 *    for move-to-top / delete. A fixed
 *    "new conversation" button gives the user
 *    a fresh link for someone new. Every
 *    conversation's links are stored by the
 *    app, so old chats are always returnable,
 *    and a chat's links vanish only when its
 *    delete button is used.
 *  - SETUP: my link (fresh, or the sender
 *    section's manage link via "my link"),
 *    the shareable link for the friend under
 *    "give this link to your friend", and the
 *    friend's link below. Starting asks for
 *    a name for myself, which the friend then
 *    sees; I see the name they chose.
 *  - CHAT: a fixed glass header (small title
 *    right, small icon centre, friend's name
 *    left, small glass menu at the far left),
 *    a fixed input row with a small circle
 *    send button, my bubbles pale green with
 *    black text. The menu holds: delete all
 *    messages both ways, block / unblock.
 *  Polling runs every 10 seconds ONLY while
 *  the chat is open. Every stored line is
 *  fully alien-encoded (name, clear and block
 *  markers included); decoding happens only
 *  inside the app, and the raw site address
 *  never appears anywhere. */
class AlienMessengerActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var mode = "list"
    private var chat: AlienChat? = null

    // Chat views.
    private var msgCol: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var headerName: TextView? = null
    private var noticeBox: TextView? = null
    private var partnerNote: TextView? = null
    private var inputBox: EditText? = null
    private var sendBtn: Button? = null
    private var replyBar: LinearLayout? = null
    private var replyBarText: TextView? = null
    private var replyToTs: Long? = null

    // own-link row (shown when a fresh
    // own link is created after expiry, or
    // from the chat menu) + dropped-NAME log
    // dedupe.
    private var ownLinkRow: LinearLayout? = null
    private var ownLinkBox: TextView? = null
    private val loggedNameDrops =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class ChatRow(
        val ts: Long,
        val text: String,
        val mine: Boolean,
        val pending: Boolean = false,
        val read: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            if (mode == "chat" && chat != null) {
                refreshChat()
                handler.postDelayed(this, 10000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "list"
        val id = intent.getStringExtra("chat_id")
        chat = if (id != null) AlienChats.find(this, id) else null
        if (mode != "list" && chat == null) {
            Toast.makeText(this, L("گفتگو پیدا نشد", "Conversation not found"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        when (mode) {
            "setup" -> buildSetup()
            "chat" -> buildChat()
            else -> buildList()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == "list") buildList()
        if (mode == "chat" && chat != null) {
            handler.post(poller)
            refreshChat()
            ensureMyNameLine()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    // ---------- shared helpers ----------

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Keeps a page off the status bar and
     *  the phone's nav buttons: base padding
     *  plus the real system insets (2.29). */
    private fun padForInsets(v: View, baseDp: Int) {
        val b = dp(baseDp)
        v.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(b, b + insets.systemWindowInsetTop,
                b, b + insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun copyText(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("alien", text))
            toast(L("کپی شد", "Copied"))
        } catch (_: Exception) {}
    }

    private fun glassButton(text: String, fill: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 14f
        b.setTextColor(Color.WHITE)
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor(fill))
        }
        b.setPadding(dp(18), dp(12), dp(18), dp(12))
        return b
    }

    private fun miniButton(text: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.setTextColor(Color.parseColor("#1A237E"))
        b.background = HydraUi.glassPill(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 20f, this)
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        return b
    }

    private fun openChat(c: AlienChat) {
        val it = Intent(this, AlienMessengerActivity::class.java)
        it.putExtra("chat_id", c.id)
        it.putExtra("mode", if (c.partnerView.isBlank()) "setup" else "chat")
        startActivity(it)
    }

    private fun fmtTime(ts: Long): String {
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        } catch (_: Exception) { "" }
    }

    // ---------- LIST ----------

    // 2.46: multi-select state for bulk delete
    // (mirrors the link-history page).
    private val selectedChatIds = mutableSetOf<String>()
    private var msgrDeleteBar: LinearLayout? = null
    private var msgrDeleteBtn: Button? = null
    private var msgrSelectAllBtn: Button? = null

    private fun updateMsgrDeleteBar() {
        val n = selectedChatIds.size
        msgrDeleteBar?.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        msgrDeleteBtn?.text = L("🗑  حذف گفتگوهای انتخاب‌شده ($n)", "🗑  Delete selected conversations ($n)")
        val total = AlienChats.load(this).size
        msgrSelectAllBtn?.text = if (n > 0 && n == total) L("لغو انتخاب همه", "Deselect all")
            else L("انتخاب همه", "Select all")
    }

    private fun refreshMsgrSelection() {
        updateMsgrDeleteBar()
        buildList()
    }

    private fun confirmDeleteSelectedChats() {
        val ids = selectedChatIds.toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(L("حذف گفتگوها", "Delete conversations"))
            .setMessage(L("${ids.size} گفتگو حذف شود؟ گفتگوها و لینک‌هایشان از برنامه پاک می‌شوند و لینک‌های راه دور هم حذف می‌شوند.",
                "${ids.size} conversations will be deleted? Their links are removed from the app and their remote links are deleted too."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                Thread {
                    // the local record is
                    // removed only when the
                    // remote delete really
                    // succeeded; otherwise the
                    // record is kept and the
                    // toast says so honestly.
                    val deletedIds = mutableListOf<String>()
                    var failed = 0
                    try {
                        val chats = AlienChats.load(this)
                        for (c in chats) if (c.id in ids) {
                            val ok = synchronized(MsgrLinkLock.lock) {
                                try { AlienChats.deleteRemoteAll(c.ownManage) } catch (_: Exception) { false }
                            }
                            if (ok) {
                                deletedIds += c.id
                                try {
                                    for (q in OfflineQueue.listFor(this, c.id).toList()) OfflineQueue.remove(this, c.id, q.ts)
                                } catch (_: Exception) {}
                            } else {
                                failed++
                            }
                        }
                        if (deletedIds.size + failed < ids.size) {
                            failed += ids.size - deletedIds.size - failed
                        }
                    } catch (_: Exception) {}
                    runOnUiThread {
                        for (id in deletedIds) AlienChats.delete(this, id)
                        selectedChatIds.clear()
                        buildList()
                        if (failed == 0) {
                            Toast.makeText(this, L("حذف شد", "Deleted"), Toast.LENGTH_SHORT).show()
                            try {  } catch (_: Exception) {}
                        } else {
                            Toast.makeText(this,
                                L("$failed گفتگو از راه دور حذف نشد و رکوردشان ماند",
                                    "$failed conversations could not be deleted remotely; their records were kept"),
                                Toast.LENGTH_LONG).show()
                            try {  } catch (_: Exception) {}
                        }
                    }
                }.start()
            }
            .setNegativeButton(L("نه", "Cancel"), null)
            .show()
    }

    private fun buildList() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        padForInsets(outer, 16)
        val title = TextView(this).apply {
            text = L("مسنجر بیگانه", "Alien Messenger")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
        }
        outer.addView(title)
        val label = TextView(this).apply {
            text = L("سابقه گفتگو", "Conversation history")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#455A64"))
            setPadding(0, dp(16), 0, dp(8))
        }
        outer.addView(label)

        // 2.46: bulk-delete toolbar.
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        msgrSelectAllBtn = Button(this).apply {
            text = L("انتخاب همه", "Select all")
            textSize = 13f
            isAllCaps = false
            background = HydraUi.glassPill(
                Color.parseColor("#E0F2F1"), Color.parseColor("#80CBC4"), 16f,
                this@AlienMessengerActivity)
            setTextColor(Color.parseColor("#00695C"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val all = AlienChats.load(this@AlienMessengerActivity)
                if (all.isNotEmpty() && selectedChatIds.size == all.size) selectedChatIds.clear()
                else selectedChatIds.addAll(all.map { it.id })
                refreshMsgrSelection()
            }
        }
        toolRow.addView(msgrSelectAllBtn)
        outer.addView(toolRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        msgrDeleteBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        msgrDeleteBtn = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            elevation = 4f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * resources.displayMetrics.density
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(Color.parseColor("#E53935"), Color.parseColor("#FF7043"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { confirmDeleteSelectedChats() }
        }
        msgrDeleteBar!!.addView(msgrDeleteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(msgrDeleteBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listCol)
        outer.addView(scroll)

        val chats = AlienChats.load(this)
        if (chats.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = L("هنوز گفتگویی نیست؛ با «گفتگوی جدید» یک گفتگو بساز",
                    "No conversations yet; start one with “New conversation”")
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (c in chats) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 20f, this@AlienMessengerActivity)
                elevation = 3f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                isClickable = true
            }
            val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val msgrCheck = android.widget.CheckBox(this).apply {
                isChecked = selectedChatIds.contains(c.id)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F766E"))
                setOnClickListener {
                    if (isChecked) selectedChatIds.add(c.id) else selectedChatIds.remove(c.id)
                    updateMsgrDeleteBar()
                }
            }
            nameRow.addView(msgrCheck)
            nameRow.addView(TextView(this).apply {
                text = if (c.partnerName.isBlank()) L("دوست", "Friend") else c.partnerName
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#1A237E"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            if (c.blockedByMe) {
                nameRow.addView(TextView(this).apply {
                    text = L(" • مسدود", " • blocked")
                    textSize = 12f
                    setTextColor(Color.parseColor("#D32F2F"))
                })
            }
            card.addView(nameRow)
            card.addView(TextView(this).apply {
                text = if (c.lastPreview.isBlank()) L("هنوز پیامی نیست", "No messages yet")
                    else c.lastPreview
                textSize = 12f
                setTextColor(Color.GRAY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.setOnClickListener {
                // 2.46: while anything is selected, a
                // tap toggles selection instead of
                // opening the chat.
                if (selectedChatIds.isNotEmpty()) {
                    if (selectedChatIds.contains(c.id)) selectedChatIds.remove(c.id)
                    else selectedChatIds.add(c.id)
                    refreshMsgrSelection()
                    return@setOnClickListener
                }
                openChat(c)
            }
            card.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setItems(arrayOf(
                        L("انتقال به بالای لیست", "Move to top of list"),
                        L("حذف گفتگو", "Delete conversation")
                    )) { _, which ->
                        if (which == 0) {
                            AlienChats.moveToTop(this, c.id)
                            buildList()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle(L("حذف گفتگو؟", "Delete conversation?"))
                                .setMessage(L("گفتگو و لینک‌هایش از برنامه پاک می‌شوند",
                                    "The conversation and its links will be removed from the app"))
                                .setPositiveButton(L("حذف شود", "Delete")) { _, _ ->
                                    Thread {
                                        // same honesty rule as
                                        // bulk delete — a failed
                                        // remote delete keeps the
                                        // local record.
                                        val ok = synchronized(MsgrLinkLock.lock) {
                                            try { AlienChats.deleteRemoteAll(c.ownManage) } catch (_: Exception) { false }
                                        }
                                        runOnUiThread {
                                            if (!ok) {
                                                Toast.makeText(this, L("حذف راه دور انجام نشد؛ گفتگو ماند",
                                                    "Remote delete failed; the conversation was kept"), Toast.LENGTH_SHORT).show()
                                                try {  } catch (_: Exception) {}
                                                return@runOnUiThread
                                            }
                                            try {
                                                for (q in OfflineQueue.listFor(this, c.id).toList()) OfflineQueue.remove(this, c.id, q.ts)
                                            } catch (_: Exception) {}
                                            AlienChats.delete(this, c.id)
                                            buildList()
                                        }
                                    }.start()
                                }
                                .setNegativeButton(L("نه", "No"), null)
                                .show()
                        }
                    }
                    .show()
                true
            }
            listCol.addView(card)
        }

        val newBtn = glassButton(L("گفتگوی جدید", "New conversation"), "#0F766E")
        newBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(24) }
        newBtn.setOnClickListener {
            newBtn.isEnabled = false
            val oldNewText = newBtn.text.toString()
            newBtn.text = L("در حال ساخت…", "Creating…")
            Thread {
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                runOnUiThread {
                    newBtn.isEnabled = true
                    newBtn.text = oldNewText
                    if (created == null) {
                        toast(L("ساخت لینک انجام نشد؛ اینترنت را چک کن",
                            "Could not create a link; check your connection"))
                        return@runOnUiThread
                    }
                    val c = AlienChat(
                        id = "c" + System.currentTimeMillis(),
                        ownView = created.first, ownManage = created.second)
                    AlienChats.add(this, c)
                    openChat(c)
                }
            }.start()
        }
        outer.addView(newBtn)
        updateMsgrDeleteBar()
        setContentView(outer)
        HelpUi.attach(this, "msgr_list")
    }

    // ---------- SETUP ----------

    private fun buildSetup() {
        val c = chat ?: return
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)
        padForInsets(scroll, 0)

        root.addView(TextView(this).apply {
            text = L("گفتگوی جدید", "New conversation")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
        })


        val ownRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        // 2.42: the "My link" button is gone (user
        // order — two link buttons confused users).
        // This page shows exactly ONE link, created
        // with "Fresh link" below.
        val btnOwnFresh = miniButton(L("لینک تازه", "Fresh link"))
        ownRow.addView(btnOwnFresh)
        root.addView(ownRow)

        // The shareable link, under its own label.
        val giveLabel = TextView(this).apply {
            text = L("این لینک را به دوستت بده", "Give this link to your friend")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, dp(16), 0, dp(6))
            visibility = if (c.ownView.isBlank()) View.GONE else View.VISIBLE
        }
        root.addView(giveLabel)
        val giveBox = TextView(this).apply {
            text = if (c.ownView.isBlank()) "" else AlienSub.displayForm(c.ownView)
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 16f, this@AlienMessengerActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
            visibility = if (c.ownView.isBlank()) View.GONE else View.VISIBLE
        }
        root.addView(giveBox)
        val giveCopy = miniButton(L("کپی", "Copy"))
        giveCopy.visibility = if (c.ownView.isBlank()) View.GONE else View.VISIBLE
        giveCopy.setOnClickListener { copyText(giveBox.text.toString()) }
        val giveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        giveRow.addView(giveCopy)
        root.addView(giveRow)

        fun refreshOwnBoxes() {
            val cc = AlienChats.find(this, c.id) ?: c
            val vis = if (cc.ownView.isBlank()) View.GONE else View.VISIBLE
            giveLabel.visibility = vis
            giveBox.visibility = vis
            giveCopy.visibility = vis
            giveBox.text = if (cc.ownView.isBlank()) "" else AlienSub.displayForm(cc.ownView)
        }

        // a fresh link first asks (the old
        // link stops receiving new messages),
        // then creates the new link BEFORE
        // deleting the old one remotely, so a
        // failed create never destroys the
        // working old link. The old remote
        // delete is best-effort and logged.
        val doFresh = {
            btnOwnFresh.isEnabled = false
            val oldFreshText = btnOwnFresh.text.toString()
            btnOwnFresh.text = L("در حال ساخت…", "Creating…")
            Thread {
                synchronized(MsgrLinkLock.lock) {
                val oldManage = (AlienChats.find(this, c.id) ?: c).ownManage
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                var oldDeleted: Boolean? = null
                if (created != null && oldManage.isNotBlank()) {
                    oldDeleted = try { AlienChats.deleteRemoteAll(oldManage) } catch (_: Exception) { false }
                }
                runOnUiThread {
                    btnOwnFresh.isEnabled = true
                    btnOwnFresh.text = oldFreshText
                    if (created == null) {
                        toast(L("ساخت لینک انجام نشد؛ اینترنت را چک کن",
                            "Could not create a link; check your connection"))
                        try {  } catch (_: Exception) {}
                        return@runOnUiThread
                    }
                    c.ownView = created.first
                    c.ownManage = created.second
                    AlienChats.update(this, c)
                    refreshOwnBoxes()
                    val oldStateFa = when (oldDeleted) {
                        null -> "بدون لینک قبلی"
                        true -> "حذف راه دور لینک قبلی موفق بود"
                        false -> "حذف راه دور لینک قبلی ناموفق بود"
                    }
                    val oldStateEn = when (oldDeleted) {
                        null -> "no previous link"
                        true -> "old link deleted remotely"
                        false -> "old link remote delete failed"
                    }
                    try {  } catch (_: Exception) {}
                }
                } // synchronized
            }.start()
        }
        btnOwnFresh.setOnClickListener {
            val cc = AlienChats.find(this, c.id) ?: c
            if (cc.ownView.isBlank() || cc.ownManage.isBlank()) {
                doFresh()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(L("لینک تازه ساخته شود؟", "Create a fresh link?"))
                    .setMessage(L("لینک قبلی دیگر پیام تازه نمی‌گیرد و در صورت امکان از راه دور هم حذف می‌شود؛ این کار قابل برگشت نیست.",
                        "The old link will stop receiving new messages and will be deleted remotely if possible; this cannot be undone."))
                    .setPositiveButton(L("بساز", "Create")) { _, _ -> doFresh() }
                    .setNegativeButton(L("نه", "Cancel"), null)
                    .show()
            }
        }

        root.addView(TextView(this).apply {
            text = L("لینک دوست", "Friend's link")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#455A64"))
            setPadding(0, dp(16), 0, dp(6))
        })
        val partnerInput = EditText(this).apply {
            hint = L("لینک بیگانه دوست را اینجا بچسبان", "Paste your friend's alien link here")
            textSize = 14f
            background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 16f, this@AlienMessengerActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (c.partnerView.isNotBlank()) setText(AlienSub.displayForm(c.partnerView))
        }
        root.addView(partnerInput)

        val startBtn = glassButton(L("شروع گفتگو", "Start conversation"), "#0F766E")
        startBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        startBtn.setOnClickListener {
            val cc = AlienChats.find(this, c.id) ?: c
            if (cc.ownView.isBlank() || cc.ownManage.isBlank()) {
                toast(L("اول با «لینک تازه» یک لینک بساز", "First create a link with “Fresh link”"))
                return@setOnClickListener
            }
            val entered = partnerInput.text.toString()
            val split = AlienChats.splitEntered(entered)
            if (split == null) {
                toast(L("لینک دوست درست نیست", "Your friend's link is not right"))
                return@setOnClickListener
            }
            cc.partnerView = split.first
            AlienChats.update(this, cc)
            chat = cc
            if (cc.myName.isBlank()) {
                askMyName { name ->
                    cc.myName = name
                    AlienChats.update(this, cc)
                    Thread {
                        try { AlienChats.ensureNameLine(cc.ownManage, name, AlienSub.sendMode(this)) } catch (_: Exception) {}
                        runOnUiThread { openChatAsChat(cc) }
                    }.start()
                }
            } else {
                openChatAsChat(cc)
            }
        }
        root.addView(startBtn)
        setContentView(scroll)
        HelpUi.attach(this, "msgr_setup")
    }

    private fun openChatAsChat(c: AlienChat) {
        val it = Intent(this, AlienMessengerActivity::class.java)
        it.putExtra("chat_id", c.id)
        it.putExtra("mode", "chat")
        startActivity(it)
        finish()
    }

    /** Asks the user to pick a name for
     *  themselves; the friend sees this name. */
    private fun askMyName(onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = L("مثلاً: علی", "e.g. Ali")
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(L("یک اسم برای خودت انتخاب کن", "Pick a name for yourself"))
            .setMessage(L("دوستت تو را با همین اسم می‌بیند", "Your friend will see you by this name"))
            .setView(input)
            .setPositiveButton(L("شروع گفتگو", "Start conversation")) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    toast(L("اسم را بنویس", "Write a name"))
                } else {
                    onOk(name)
                }
            }
            .show()
    }

    private fun ensureMyNameLine() {
        val c = chat ?: return
        if (c.myName.isBlank()) {
            askMyName { name ->
                c.myName = name
                AlienChats.update(this, c)
                Thread {
                    try { AlienChats.ensureNameLine(c.ownManage, name, AlienSub.sendMode(this)) } catch (_: Exception) {}
                }.start()
            }
            return
        }
        Thread {
            try {
                val content = ShzClient.fetchAny(c.ownView) ?: return@Thread
                val parsed = AlienChats.parseSub(content)
                if (parsed.name != c.myName) {
                    AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this))
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ---------- CHAT ----------

    private fun buildChat() {
        val c = chat ?: return
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
        }

        padForInsets(outer, 16)
        // Fixed glass header, compact: small
        // title right, small icon centre, the
        // friend's name left, small glass menu
        // at the far left.
        val header = RelativeLayout(this).apply {
            background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 0f, this@AlienMessengerActivity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val menuId = View.generateViewId()
        val iconId = View.generateViewId()
        val menuBtn = Button(this).apply {
            id = menuId
            text = "⋮"
            textSize = 16f
            setTextColor(Color.parseColor("#455A64"))
            background = HydraUi.glassPill(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 14f, this@AlienMessengerActivity)
            layoutParams = RelativeLayout.LayoutParams(dp(28), dp(28)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setPadding(0, 0, 0, 0)
        }
        menuBtn.setOnClickListener { showChatMenu() }
        header.addView(menuBtn)
        val title = TextView(this).apply {
            text = L("مسنجر بیگانه", "Alien Messenger")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        header.addView(title)
        val icon = ImageView(this).apply {
            id = iconId
            try {
                setImageBitmap(BitmapFactory.decodeStream(assets.open("alien.png")))
            } catch (_: Exception) {}
            layoutParams = RelativeLayout.LayoutParams(dp(32), dp(32)).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        header.addView(icon)
        val nameTv = TextView(this).apply {
            text = if (c.partnerName.isBlank()) L("دوست", "Friend") else c.partnerName
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.RIGHT_OF, menuId)
                addRule(RelativeLayout.LEFT_OF, iconId)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        header.addView(nameTv)
        headerName = nameTv
        outer.addView(header)

        partnerNote = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(2))
        }
        outer.addView(partnerNote)

        noticeBox = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#5D4037"))
            gravity = Gravity.CENTER
            background = HydraUi.glassBg(Color.parseColor("#FFF8E1"), Color.parseColor("#FFE082"), 14f, this@AlienMessengerActivity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), 0)
            }
        }
        outer.addView(noticeBox)

        // own-link row, hidden until a
        // fresh own link is created after
        // expiry or the menu asks for it. It
        // shows the share link (never the
        // manage link) with a copy button.
        val ownRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = HydraUi.glassBg(Color.parseColor("#E8F5E9"), Color.parseColor("#A5D6A7"), 14f, this@AlienMessengerActivity)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), 0)
            }
        }
        val ownBox = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A1A"))
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ownCopy = miniButton(L("کپی لینک من", "Copy my link"))
        ownCopy.setOnClickListener { copyText(ownBox.text.toString()) }
        ownRow.addView(ownBox)
        ownRow.addView(ownCopy)
        ownLinkRow = ownRow
        ownLinkBox = ownBox
        outer.addView(ownRow)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(col)
        msgCol = col
        chatScroll = scroll
        outer.addView(scroll)

        val replyBarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        val replyTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val replyClose = Button(this).apply {
            text = "\u00d7"
            textSize = 16f
            setTextColor(Color.parseColor("#455A64"))
            background = HydraUi.glassPill(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 14f, this@AlienMessengerActivity)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setPadding(0, 0, 0, 0)
            setOnClickListener { cancelReply() }
        }
        replyBarView.addView(replyTextView)
        replyBarView.addView(replyClose)
        replyBar = replyBarView
        replyBarText = replyTextView
        outer.addView(replyBarView)

        // Fixed input row: writing box plus a
        // small circle send button, same height,
        // on the same line.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val input = EditText(this).apply {
            hint = L("پیام…", "Message…")
            textSize = 15f
            background = HydraUi.glassBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E0E0E0"), 24f, this@AlienMessengerActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        inputBox = input
        val send = Button(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F766E"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8)
            }
            setPadding(0, 0, 0, 0)
        }
        sendBtn = send
        send.setOnClickListener { sendChatMessage() }
        inputRow.addView(input)
        inputRow.addView(send)
        outer.addView(inputRow)

        setContentView(outer)
        applyInputState()
        HelpUi.attach(this, "msgr_chat")
    }

    private fun applyInputState() {
        val c = chat ?: return
        val blocked = c.blockedByMe || c.blockedMe
        inputBox?.isEnabled = !blocked
        sendBtn?.isEnabled = !blocked
        sendBtn?.alpha = if (blocked) 0.4f else 1f
    }

    /** shows the own share link row (and
     *  optionally copies it). Safe from any
     *  thread; the row stays hidden until a
     *  fresh link exists or the menu asks. */
    private fun showOwnLinkRow(copy: Boolean = false) {
        runOnUiThread {
            val c = chat ?: return@runOnUiThread
            if (c.ownView.isBlank()) return@runOnUiThread
            ownLinkBox?.text = AlienSub.displayForm(c.ownView)
            ownLinkRow?.visibility = View.VISIBLE
            if (copy) copyText(ownLinkBox?.text.toString())
        }
    }

    private fun enterReplyMode(row: ChatRow) {
        val (_, display) = AlienChats.parseReply(row.text)
        replyToTs = row.ts
        val who = if (row.mine) {
            L("شما", "You")
        } else if (chat?.partnerName.isNullOrBlank()) {
            L("دوست", "Friend")
        } else {
            chat!!.partnerName
        }
        val short = if (display.length > 40) display.substring(0, 40) + "\u2026" else display
        replyBarText?.text = L("پاسخ به ", "Replying to ") + who + ": " + short
        replyBar?.visibility = View.VISIBLE
        inputBox?.requestFocus()
    }

    private fun cancelReply() {
        replyToTs = null
        replyBar?.visibility = View.GONE
    }

    private fun sendChatMessage() {
        val c = chat ?: return
        val text = inputBox?.text?.toString()?.trim() ?: ""
        if (text.isBlank()) return
        inputBox?.setText("")
        val replyTo = replyToTs
        cancelReply()
        val ts = System.currentTimeMillis()
        val stored = if (replyTo != null) "R$replyTo|$text" else text
        Thread {
            // the whole send is one locked
            // unit: read, expiry-create, build,
            // write — nothing can interleave.
            synchronized(MsgrLinkLock.lock) {
            var content = ShzClient.fetchAny(c.ownView)
            if (content == null) {
                // Own link expired: make a fresh
                // one and tell the user to
                // re-share it.
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                if (created == null) {
                    OfflineQueue.add(this, OfflineQueue.Item(c.id, ts, stored))
                    runOnUiThread {
                        toast(L("اینترنت نیست؛ پیام در صف ماند تا بعد فرستاده شود",
                            "No connection; the message is queued and will be sent later"))
                        refreshChat()
                    }
                    return@Thread
                }
                c.ownView = created.first
                c.ownManage = created.second
                AlienChats.update(this, c)
                // show the fresh link so
                // the user can actually give it
                // to their friend again.
                showOwnLinkRow()
                try {  } catch (_: Exception) {}
                content = ""
                runOnUiThread {
                    toast(L("لینک خودت منقضی شده بود؛ لینک تازه ساخته شد — آن را دوباره به دوستت بده",
                        "Your link had expired; a fresh one was created — give it to your friend again"))
                }
                Thread {
                    try { AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this)) } catch (_: Exception) {}
                }.start()
            }
            val line = AlienChats.msgLine(ts, stored)
            val next = if (content.isNullOrBlank()) line else content.trimEnd() + "\n" + line
            val ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
            runOnUiThread {
                if (!ok) {
                    OfflineQueue.add(this, OfflineQueue.Item(c.id, ts, stored))
                    toast(L("اینترنت نیست؛ پیام در صف ماند تا بعد فرستاده شود",
                        "No connection; the message is queued and will be sent later"))
                    refreshChat()
                } else {
                    refreshChat()
                    try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                }
            }
            } // synchronized
        }.start()
    }

    private fun showChatMenu() {
        val c = chat ?: return
        val items = mutableListOf<String>()
        items += L("پاک کردن همه پیام‌ها (دو طرفه)", "Delete all messages (both ways)")
        items += if (c.blockedByMe) L("رفع مسدودیت", "Unblock")
            else L("مسدود کردن", "Block")
        items += L("ساخت ویجت برای این گفتگو", "Build a widget for this chat")
        items += L("نمایش و کپی لینک من", "Show and copy my link")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) confirmDeleteAll()
                else if (which == 2) {
                    startActivity(Intent(this, WidgetStudioActivity::class.java).apply {
                        putExtra("preselect_chat_id", c.id)
                    })
                }
                else if (which == 3) {
                    // the own share link,
                    // always reachable from here.
                    showOwnLinkRow(copy = true)
                    try {  } catch (_: Exception) {}
                }
                else if (c.blockedByMe) doUnblock() else confirmBlock()
            }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(L("پاک کردن همه پیام‌ها؟", "Delete all messages?"))
            .setMessage(L("پیام‌های تو و دوستت هر دو پاک می‌شوند",
                "Both your messages and your friend's will be deleted"))
            .setPositiveButton(L("پاک شود", "Delete")) { _, _ -> doDeleteAll() }
            .setNegativeButton(L("نه", "No"), null)
            .show()
    }

    private fun doDeleteAll() {
        val c = chat ?: return
        Thread {
            val now = System.currentTimeMillis()
            val next = AlienChats.buildOwnContent(
                c.myName, now, c.blockedByMe,
                emptyList())
            // serialized with other link
            // writers, so a concurrent send
            // cannot resurrect messages.
            val ok = synchronized(MsgrLinkLock.lock) {
                ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
            }
            runOnUiThread {
                if (!ok) {
                    toast(L("پاک نشد؛ اینترنت را چک کن",
                        "Could not delete; check your connection"))
                    return@runOnUiThread
                }
                c.myClearTs = now
                AlienChats.update(this, c)
                // the offline queue is
                // cleared too, or its pending
                // messages would flow straight
                // back into the cleared link.
                try {
                    for (q in OfflineQueue.listFor(this, c.id).toList()) {
                        OfflineQueue.remove(this, c.id, q.ts)
                    }
                } catch (_: Exception) {}
                try {  } catch (_: Exception) {}
                refreshChat()
            }
        }.start()
    }

    private fun confirmBlock() {
        AlertDialog.Builder(this)
            .setTitle(L("مسدود کردن؟", "Block?"))
            .setMessage(L("دیگر پیامی از این شخص دریافت نمی‌کنی و او می‌فهمد که مسدود شده است",
                "You will no longer receive messages from this person, and they will know they are blocked"))
            .setPositiveButton(L("مسدود شود", "Block")) { _, _ -> doBlock() }
            .setNegativeButton(L("نه", "No"), null)
            .show()
    }

    private fun rewriteOwnBlocked(blocked: Boolean, onDone: (Boolean, Boolean) -> Unit) {
        val c = chat ?: return
        Thread {
            // a failed own-link read STOPS
            // here — nothing is written remotely,
            // so a network blip can never wipe
            // the user's own messages with
            // empty content. Serialized with
            // every other link writer.
            var readOk = true
            var ok = false
            synchronized(MsgrLinkLock.lock) {
                val content = ShzClient.fetchAny(c.ownView)
                if (content == null) {
                    readOk = false
                } else {
                    val parsed = AlienChats.parseSub(content)
                    val next = AlienChats.buildOwnContent(
                        c.myName, c.myClearTs, blocked,
                        parsed.messages.map { AlienChats.msgLine(it.first, it.second) },
                        parsed.seenTs ?: 0L)
                    ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                }
            }
            runOnUiThread {
                if (!readOk) {
                    toast(L("خواندن لینک انجام نشد؛ دوباره تلاش کن",
                        "Could not read the link; try again"))
                    try {  } catch (_: Exception) {}
                }
                onDone(ok, readOk)
            }
        }.start()
    }

    private fun doBlock() {
        val c = chat ?: return
        rewriteOwnBlocked(true) { ok, readOk ->
            if (!readOk) return@rewriteOwnBlocked
            if (!ok) {
                toast(L("مسدود نشد؛ اینترنت را چک کن",
                    "Could not block; check your connection"))
                return@rewriteOwnBlocked
            }
            c.blockedByMe = true
            AlienChats.update(this, c)
            handler.removeCallbacks(poller)
            refreshChat()
        }
    }

    private fun doUnblock() {
        val c = chat ?: return
        rewriteOwnBlocked(false) { ok, readOk ->
            if (!readOk) return@rewriteOwnBlocked
            if (!ok) {
                toast(L("رفع مسدودیت انجام نشد؛ اینترنت را چک کن",
                    "Could not unblock; check your connection"))
                return@rewriteOwnBlocked
            }
            c.blockedByMe = false
            AlienChats.update(this, c)
            refreshChat()
            handler.post(poller)
        }
    }

    private fun showMyMessageMenu(row: ChatRow) {
        val items = arrayOf(
            L("ویرایش پیام", "Edit message"),
            L("حذف پیام", "Delete message"),
            L("نه", "Cancel"))
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                if (which == 0) editMessageDialog(row)
                else if (which == 1) confirmDeleteMessage(row)
            }
            .show()
    }

    private fun editMessageDialog(row: ChatRow) {
        val (_, display) = AlienChats.parseReply(row.text)
        val input = EditText(this).apply {
            setText(display)
            textSize = 15f
        }
        AlertDialog.Builder(this)
            .setTitle(L("ویرایش پیام", "Edit message"))
            .setView(input)
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isBlank()) return@setPositiveButton
                if (row.pending) {
                    val (replyTo, _) = AlienChats.parseReply(row.text)
                    val newStored = if (replyTo != null) "R$replyTo|$newText" else newText
                    OfflineQueue.updateText(this, chat?.id ?: return@setPositiveButton, row.ts, newStored)
                    refreshChat()
                } else {
                    doEditSentMessage(row, newText)
                }
            }
            .setNegativeButton(L("نه", "Cancel"), null)
            .show()
    }

    private fun doEditSentMessage(row: ChatRow, newDisplay: String) {
        val c = chat ?: return
        Thread {
            // same stop-on-failed-read
            // rule as block, plus serialization.
            var ok = false
            var readFailed = false
            synchronized(MsgrLinkLock.lock) {
                val content = ShzClient.fetchAny(c.ownView)
                if (content == null) {
                    readFailed = true
                } else {
                    val parsed = AlienChats.parseSub(content)
                    val orig = parsed.messages.firstOrNull { it.first == row.ts }
                    if (orig != null) {
                        val (replyTo, _) = AlienChats.parseReply(orig.second)
                        val newStored = if (replyTo != null) "R$replyTo|$newDisplay" else newDisplay
                        val lines = parsed.messages.map { m ->
                            if (m.first == row.ts) AlienChats.msgLine(m.first, newStored)
                            else AlienChats.msgLine(m.first, m.second)
                        }
                        val next = AlienChats.buildOwnContent(
                            c.myName, c.myClearTs, c.blockedByMe, lines, parsed.seenTs ?: 0L)
                        ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                    }
                }
            }
            runOnUiThread {
                if (readFailed) {
                    toast(L("خواندن لینک انجام نشد؛ دوباره تلاش کن",
                        "Could not read the link; try again"))
                    try {  } catch (_: Exception) {}
                } else if (!ok) toast(L("ویرایش نشد؛ اینترنت را چک کن",
                    "Could not edit; check your connection"))
                else refreshChat()
            }
        }.start()
    }

    private fun confirmDeleteMessage(row: ChatRow) {
        AlertDialog.Builder(this)
            .setTitle(L("حذف این پیام؟", "Delete this message?"))
            .setMessage(L("این پیام برای هر دو طرف پاک می‌شود",
                "This message will be deleted for both sides"))
            .setPositiveButton(L("حذف شود", "Delete")) { _, _ ->
                if (row.pending) {
                    OfflineQueue.remove(this, chat?.id ?: return@setPositiveButton, row.ts)
                    refreshChat()
                } else {
                    doDeleteSentMessage(row)
                }
            }
            .setNegativeButton(L("نه", "No"), null)
            .show()
    }

    private fun doDeleteSentMessage(row: ChatRow) {
        val c = chat ?: return
        Thread {
            // same stop-on-failed-read
            // rule as block, plus serialization.
            var ok = false
            var readFailed = false
            synchronized(MsgrLinkLock.lock) {
                val content = ShzClient.fetchAny(c.ownView)
                if (content == null) {
                    readFailed = true
                } else {
                    val parsed = AlienChats.parseSub(content)
                    val lines = parsed.messages
                        .filter { it.first != row.ts }
                        .map { AlienChats.msgLine(it.first, it.second) }
                    val next = AlienChats.buildOwnContent(
                        c.myName, c.myClearTs, c.blockedByMe, lines, parsed.seenTs ?: 0L)
                    ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                }
            }
            runOnUiThread {
                if (readFailed) {
                    toast(L("خواندن لینک انجام نشد؛ دوباره تلاش کن",
                        "Could not read the link; try again"))
                    try {  } catch (_: Exception) {}
                } else if (!ok) toast(L("حذف نشد؛ اینترنت را چک کن",
                    "Could not delete; check your connection"))
                else refreshChat()
            }
        }.start()
    }

    /** Sends queued messages in order, inside
     *  the caller's background thread. Stops at
     *  the first failure so order is kept; a
     *  later refresh (chat open, 10s poll, or a
     *  tap on a pending bubble) tries again. */
    private fun flushOfflineQueue(c: AlienChat) {
        // the whole flush is one locked
        // unit, so a poll can never interleave
        // a read-modify-write in the middle.
        val queue = OfflineQueue.listFor(this, c.id)
        synchronized(MsgrLinkLock.lock) {
        for (item in queue) {
            var content = ShzClient.fetchAny(c.ownView)
            if (content == null) {
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                if (created == null) break
                c.ownView = created.first
                c.ownManage = created.second
                AlienChats.update(this, c)
                // show the fresh link so
                // the user can give it again.
                showOwnLinkRow()
                try {  } catch (_: Exception) {}
                content = ""
                Thread {
                    try { AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this)) } catch (_: Exception) {}
                }.start()
            }
            val line = AlienChats.msgLine(item.ts, item.text)
            val next = if (content.isNullOrBlank()) line else content.trimEnd() + "\n" + line
            if (!ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)) break
            OfflineQueue.remove(this, c.id, item.ts)
        }
        } // synchronized
    }

    private fun refreshChat() {
        val c = chat ?: return
        Thread {
            flushOfflineQueue(c)
            var myMsgs: List<Pair<Long, String>> = emptyList()
            var partnerMsgs: List<Pair<Long, String>> = emptyList()
            var pName: String? = null
            var pBlocked = false
            var partnerMissing = false
            var partnerSeen: Long = 0L
            var seenToWrite: Long = 0L
            var seenWritten = false

            // 2.44: own + partner are independent
            // reads — fetch them at once instead of
            // one after the other.
            // the fetch + clear-apply +
            // SEEN-write sequence is one locked
            // unit (the flush above already is).
            synchronized(MsgrLinkLock.lock) {
            var ownContent: String? = null
            var pContent: String? = null
            val tOwn = Thread { ownContent = try { ShzClient.fetchAny(c.ownView) } catch (_: Exception) { null } }
            val tPar = Thread { if (!c.blockedByMe) pContent = try { ShzClient.fetchAny(c.partnerView) } catch (_: Exception) { null } }
            tOwn.start(); tPar.start()
            try { tOwn.join() } catch (_: Exception) {}
            try { tPar.join() } catch (_: Exception) {}
            if (ownContent == null) {
                // Own link expired: fresh one.
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                if (created != null) {
                    c.ownView = created.first
                    c.ownManage = created.second
                    AlienChats.update(this, c)
                    // show the fresh link
                    // so the user can give it
                    // again.
                    showOwnLinkRow()
                    try {  } catch (_: Exception) {}
                    ownContent = ""
                    runOnUiThread {
                        toast(L("لینک خودت منقضی شده بود؛ لینک تازه ساخته شد — آن را دوباره به دوستت بده",
                            "Your link had expired; a fresh one was created — give it to your friend again"))
                    }
                    Thread {
                        try { AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this)) } catch (_: Exception) {}
                    }.start()
                }
            }
            val ownParsed = AlienChats.parseSub(ownContent)
            myMsgs = ownParsed.messages

            if (!c.blockedByMe) {
                if (pContent == null) {
                    partnerMissing = true
                } else {
                    val pp = AlienChats.parseSub(pContent)
                    pName = pp.name
                    // a site-like NAME is
                    // dropped by the filter in
                    // parseSub; log it once per
                    // chat so the log shows the
                    // filter actually fired.
                    if (pp.nameDropped && loggedNameDrops.add(c.id)) {
                        try {  } catch (_: Exception) {}
                    }
                    pBlocked = pp.blocked
                    partnerSeen = pp.seenTs ?: 0L
                    partnerMsgs = pp.messages.filter { it.first > c.myClearTs }
                    seenToWrite = maxOf(ownParsed.seenTs ?: 0L, partnerMsgs.maxOfOrNull { it.first } ?: 0L)
                    val pClear = pp.clearTs
                    if (pClear != null && pClear > c.appliedClearTs) {
                        // Friend cleared both ways:
                        // my link loses its
                        // messages too.
                        val nc = AlienChats.buildOwnContent(
                            c.myName, c.myClearTs, c.blockedByMe, emptyList(), seenToWrite)
                        if (ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, nc)) {
                            myMsgs = emptyList()
                            c.appliedClearTs = pClear
                            seenWritten = true
                        }
                    }
                }
            }

            if (!c.blockedByMe && !pBlocked && !seenWritten &&
                seenToWrite > (ownParsed.seenTs ?: 0L)) {
                val seenNext = AlienChats.buildOwnContent(
                    c.myName, c.myClearTs, c.blockedByMe,
                    myMsgs.map { AlienChats.msgLine(it.first, it.second) },
                    seenToWrite)
                ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, seenNext)
            }
            } // synchronized

            val all = mutableListOf<ChatRow>()
            for (m in myMsgs) all += ChatRow(m.first, m.second, true, false, m.first <= partnerSeen)
            for (m in partnerMsgs) all += ChatRow(m.first, m.second, false)
            for (q in OfflineQueue.listFor(this, c.id)) {
                if (all.none { it.ts == q.ts }) all += ChatRow(q.ts, q.text, true, true, false)
            }
            all.sortBy { it.ts }
            val latest = all.lastOrNull()
            // snapshot for the UI closure (smart cast cannot
            // capture the changing background var pName).
            val pNameFixed = pName

            runOnUiThread {
                val cc = AlienChats.find(this, c.id) ?: c
                var changed = false
                if (pNameFixed != null && pNameFixed != cc.partnerName) {
                    cc.partnerName = pNameFixed; changed = true
                }
                if (pBlocked != cc.blockedMe) { cc.blockedMe = pBlocked; changed = true }
                if (cc.appliedClearTs != c.appliedClearTs) {
                    cc.appliedClearTs = c.appliedClearTs; changed = true
                }
                if (latest != null && latest.ts != cc.lastTs) {
                    cc.lastTs = latest.ts
                    val latestDisplay = AlienChats.parseReply(latest.text).second
                    cc.lastPreview = if (latestDisplay.length > 40)
                        latestDisplay.substring(0, 40) + "…" else latestDisplay
                    changed = true
                }
                if (changed) AlienChats.update(this, cc)
                chat = cc

                headerName?.text = if (cc.partnerName.isBlank()) L("دوست", "Friend") else cc.partnerName
                partnerNote?.visibility = if (partnerMissing && !cc.blockedByMe) View.VISIBLE else View.GONE
                if (partnerMissing) {
                    partnerNote?.text = L("لینک دوست پیدا نشد؛ شاید منقضی شده باشد",
                        "Your friend's link was not found; it may have expired")
                }
                noticeBox?.visibility = when {
                    cc.blockedByMe -> {
                        noticeBox?.text = L("شما این شخص را مسدود کرده‌اید",
                            "You have blocked this person")
                        View.VISIBLE
                    }
                    cc.blockedMe -> {
                        noticeBox?.text = L("این کاربر شما را مسدود کرده است",
                            "This user has blocked you")
                        View.VISIBLE
                    }
                    else -> View.GONE
                }
                applyInputState()

                val col = msgCol ?: return@runOnUiThread
                col.removeAllViews()
                if (all.isEmpty()) {
                    col.addView(TextView(this).apply {
                        text = L("هنوز پیامی نیست…", "No messages yet…")
                        textSize = 13f
                        setTextColor(Color.GRAY)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(16), 0, dp(16))
                    })
                }
                for (m in all) {
                    col.addView(bubble(m, all, fmtTime(m.ts)))
                }
                chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun bubble(row: ChatRow, all: List<ChatRow>, time: String): LinearLayout {
        val mine = row.mine
        val (replyTo, display) = AlienChats.parseReply(row.text)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(if (mine) "#DCF8C6" else "#FFFFFF"))
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        if (replyTo != null) {
            val orig = all.firstOrNull { it.ts == replyTo }
            val previewText = if (orig == null) {
                L("پیام اصلی حذف شده است", "Original message was deleted")
            } else {
                val origDisplay = AlienChats.parseReply(orig.text).second
                val who = if (orig.mine) {
                    L("شما", "You")
                } else if (chat?.partnerName.isNullOrBlank()) {
                    L("دوست", "Friend")
                } else {
                    chat!!.partnerName
                }
                val short = if (origDisplay.length > 40) origDisplay.substring(0, 40) + "…" else origDisplay
                "↩ " + who + ": " + short
            }
            box.addView(TextView(this).apply {
                text = previewText
                textSize = 12f
                setTextColor(Color.parseColor("#455A64"))
                setPadding(0, 0, 0, dp(4))
            })
        }
        val tv = TextView(this).apply {
            text = display
            textSize = 15f
            setTextColor(Color.BLACK)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        box.addView(tv)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        val timeTv = TextView(this).apply {
            text = time
            textSize = 11f
            setTextColor(Color.GRAY)
        }
        bottom.addView(timeTv)
        if (mine) {
            val tickTv = TextView(this).apply {
                text = if (row.pending) "⏳" else if (row.read) "✓✓" else "✓"
                textSize = 11f
                setTextColor(Color.parseColor("#0F766E"))
                setPadding(dp(4), 0, 0, 0)
            }
            bottom.addView(tickTv)
        }
        box.addView(bottom)
        wrap.addView(box)
        var downX = 0f
        var downY = 0f
        var longFired = false
        val longRunnable = Runnable {
            if (mine && !longFired) {
                longFired = true
                showMyMessageMenu(row)
            }
        }
        box.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    longFired = false
                    v.postDelayed(longRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.x - downX) > dp(10) ||
                        kotlin.math.abs(ev.y - downY) > dp(10)) {
                        v.removeCallbacks(longRunnable)
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longRunnable)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!longFired && dx < -dp(60) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        enterReplyMode(row)
                        true
                    } else if (!longFired && row.pending &&
                        kotlin.math.abs(dx) < dp(10) &&
                        kotlin.math.abs(dy) < dp(10)) {
                        refreshChat()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longRunnable)
                    false
                }
                else -> false
            }
        }
        return wrap
    }
}
