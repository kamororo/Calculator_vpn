package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The chat widget's own chat window (2.33). It IS the in-app chat
 *  environment: same bubbles (mine green, partner white, radius 16,
 *  78% width, time + ticks inside my bubbles), same reply quotes,
 *  same send rules (send modes, offline queue) and same SEEN write
 *  as the messenger — but it opens straight onto the conversation
 *  and never opens the messenger or receiver pages.
 *
 *  Modes: "chat" (two-way, any person) and "receiver" (receive-only:
 *  no input, no reply). Tap ANY message for its menu: chat mode
 *  [reply | delete | copy], receiver mode [copy | delete]. Sent
 *  delete removes the message both ways (own link rebuilt without
 *  that line); received delete hides it locally (the widget and
 *  this window filter it; the messenger does not). */
class AlienQuickReplyActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)

    private data class Row(
        val ts: Long, val mine: Boolean, val text: String,
        val replyTo: Long?, val pending: Boolean, val read: Boolean)

    private var mode = "chat"
    private var chatId = ""
    private var chat: AlienChat? = null
    private lateinit var msgColumn: LinearLayout
    private lateinit var msgScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var replyBar: LinearLayout
    private lateinit var replyBarText: TextView
    private lateinit var openFullBtn: Button
    private var replyToTs: Long? = null
    private var currentRows: List<Row> = emptyList()
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollTask: Runnable? = null
    private var resumed = false

    private fun hiddenKey() = if (mode == "receiver") "receiver" else chatId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "chat"
        if (mode != "receiver") mode = "chat"
        chatId = intent.getStringExtra("chat_id") ?: ""
        if (mode == "chat") chat = AlienChats.find(this, chatId)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F5F9"))
        }
        // Header: three-dot menu (chat mode) + the chat's name,
        // centred. The menu holds the full-chat options so the
        // mini-chat is a complete chat window, not a viewer.
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(10))
        }
        if (mode == "chat") {
            headerRow.addView(TextView(this).apply {
                text = "⋮"
                textSize = 22f
                setTextColor(Color.parseColor("#1A237E"))
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { showHeaderMenu() }
            })
        } else {
            headerRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(1))
            })
        }
        headerRow.addView(TextView(this).apply {
            text = when {
                mode == "receiver" -> L("پیام بیگانه", "Alien Message")
                chat == null -> L("گفتگو", "Chat")
                chat!!.partnerName.isBlank() -> L("گفتگو", "Chat")
                else -> chat!!.partnerName
            }
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(1))
        })
        root.addView(headerRow)
        msgScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        msgColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        msgScroll.addView(msgColumn)
        root.addView(msgScroll)
        // Reply bar (hidden until a message is picked for reply).
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#E8F5E9"))
        }
        replyBarText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        replyBar.addView(replyBarText)
        val replyCancel = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#455A64"))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { replyToTs = null; replyBar.visibility = View.GONE }
        }
        replyBar.addView(replyCancel)
        root.addView(replyBar)
        // Input row (chat mode only).
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = if (mode == "chat") View.VISIBLE else View.GONE
        }
        input = EditText(this).apply {
            hint = L("پیام…", "Message…")
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(input)
        sendBtn = Button(this).apply {
            text = L("فرستادن", "Send")
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#0F766E"))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            setOnClickListener { send() }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)
        openFullBtn = Button(this).apply {
            text = if (mode == "receiver") L("باز کردن صفحه گیرنده", "Open the receiver page")
                else L("باز کردن گفتگوی کامل", "Open the full chat")
            textSize = 13f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(4), dp(12), dp(12))
            }
            setOnClickListener {
                if (mode == "receiver") {
                    startActivity(Intent(this@AlienQuickReplyActivity,
                        AlienSubActivity::class.java).apply {
                        putExtra("mode", "receiver")
                    })
                } else {
                    startActivity(Intent(this@AlienQuickReplyActivity,
                        AlienMessengerActivity::class.java).apply {
                        putExtra("mode", "chat")
                        putExtra("chat_id", chatId)
                    })
                }
                finish()
            }
        }
        root.addView(openFullBtn)
        setContentView(root)
        // Dialog overlay: float over the home screen like a widget
        // window — full width, 85% of the height, bottom-anchored.
        try {
            val dm = resources.displayMetrics
            window?.setLayout(dm.widthPixels, (dm.heightPixels * 0.85).toInt())
            window?.setGravity(Gravity.BOTTOM)
        } catch (_: Exception) {}
        if (mode == "chat" && chat == null) {
            msgColumn.addView(TextView(this).apply {
                text = L("این گفتگو دیگر نیست", "This chat no longer exists")
                textSize = 15f
                gravity = Gravity.CENTER
            })
            inputRow.visibility = View.GONE
            openFullBtn.visibility = View.GONE
            return
        }
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val task = object : Runnable {
            override fun run() {
                if (!resumed) return
                loadMessages()
                pollHandler.postDelayed(this, 10_000)
            }
        }
        pollTask = task
        pollHandler.postDelayed(task, 10_000)
    }

    override fun onPause() {
        resumed = false
        pollTask?.let { pollHandler.removeCallbacks(it) }
        super.onPause()
    }

    private fun loadMessages() {
        Thread {
            try {
                if (mode == "receiver") {
                    val view = AlienSub.receiverView(this)
                    if (view == null) {
                        runOnUiThread {
                            msgColumn.removeAllViews()
                            msgColumn.addView(TextView(this).apply {
                                text = L("هنوز لینک گیرنده ثبت نشده است",
                                    "No receiver link saved yet")
                                textSize = 14f
                                setTextColor(Color.GRAY)
                                gravity = Gravity.CENTER
                                setPadding(0, dp(16), 0, dp(16))
                            })
                        }
                        return@Thread
                    }
                    val parsed = AlienChats.parseSub(ShzClient.fetchAny(view))
                    val hidden = WidgetStyle.hiddenFor(this, "receiver")
                    val rows = parsed.messages
                        .filter { it.first !in hidden }
                        .map { (ts, second) ->
                            val (replyTo, text) = AlienChats.parseReply(second)
                            Row(ts, false, text, replyTo, false, false)
                        }
                        .sortedBy { it.ts }
                    runOnUiThread { renderRows(rows) }
                    return@Thread
                }
                val c = AlienChats.find(this, chatId) ?: return@Thread
                chat = c
                val ownContent = ShzClient.fetchAny(c.ownView)
                var own = AlienChats.parseSub(ownContent)
                if (ownContent == null) {
                    // Own link expired: same recovery as the messenger.
                    val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                    if (created != null) {
                        c.ownView = created.first
                        c.ownManage = created.second
                        AlienChats.update(this, c)
                        own = ParsedSub(null, null, false, null, emptyList())
                        runOnUiThread {
                            Toast.makeText(this, L("لینک خودت منقضی شده بود؛ لینک تازه ساخته شد — آن را دوباره به دوستت بده",
                                "Your link had expired; a fresh one was created — give it to your friend again"),
                                Toast.LENGTH_LONG).show()
                        }
                        Thread {
                            try { AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this)) } catch (_: Exception) {}
                        }.start()
                    }
                }
                val partner = AlienChats.parseSub(ShzClient.fetchAny(c.partnerView))
                val partnerSeen = partner.seenTs
                val merged = mutableListOf<Row>()
                for ((ts, second) in own.messages) {
                    val (replyTo, _) = AlienChats.parseReply(second)
                    // 2.64: decrypt at display time (E2E).
                    val text = AlienE2e.displayStored(this, chatId, second, mine = true)
                    merged += Row(ts, true, text, replyTo, false,
                        partnerSeen != null && ts <= partnerSeen)
                }
                for ((ts, second) in partner.messages) {
                    if (ts <= c.myClearTs) continue
                    val (replyTo, _) = AlienChats.parseReply(second)
                    val text = AlienE2e.displayStored(this, chatId, second, mine = false)
                    merged += Row(ts, false, text, replyTo, false, false)
                }
                for (item in OfflineQueue.listFor(this, chatId)) {
                    if (merged.none { it.ts == item.ts && it.mine }) {
                        merged += Row(item.ts, true, AlienE2e.displayStored(this, chatId, item.text, mine = true), null, true, false)
                    }
                }
                merged.sortBy { it.ts }
                // Filter locally hidden received messages.
                val hidden = WidgetStyle.hiddenFor(this, chatId)
                val visible = merged.filter { it.mine || it.ts !in hidden }
                // SEEN write, exactly like the messenger. (Only fires
                // when fresh partner messages exist, so a failed
                // partner fetch can never mark anything seen.)
                if (!c.blockedByMe && !partner.blocked) {
                    val maxPartnerTs = partner.messages
                        .filter { it.first > c.myClearTs }
                        .maxOfOrNull { it.first } ?: 0L
                    val seenToWrite = maxOf(own.seenTs ?: 0L, maxPartnerTs)
                    if (seenToWrite > (own.seenTs ?: 0L)) {
                        val next = AlienChats.buildOwnContent(
                            c.myName, c.myClearTs, c.blockedByMe,
                            own.messages.map { AlienChats.msgLine(it.first, it.second) },
                            seenToWrite)
                        ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                    }
                }
                val blocked = c.blockedByMe || c.blockedMe || partner.blocked
                runOnUiThread {
                    renderRows(visible)
                    input.isEnabled = !blocked
                    sendBtn.isEnabled = !blocked
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun renderRows(rows: List<Row>) {
        currentRows = rows
        msgColumn.removeAllViews()
        if (rows.isEmpty()) {
            msgColumn.addView(TextView(this).apply {
                text = L("هنوز پیامی نیست", "No messages yet")
                textSize = 13f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        for (row in rows) {
            val rowBox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (row.mine) Gravity.END else Gravity.START
                setPadding(0, dp(2), 0, dp(2))
            }
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = resources.getDrawable(
                    if (row.mine) R.drawable.widget_bubble_mine
                    else R.drawable.widget_bubble_theirs, null)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { showMessageMenu(row) }
            }
            if (row.replyTo != null) {
                val orig = rows.firstOrNull { it.ts == row.replyTo }
                val quote = if (orig == null) {
                    L("پیام اصلی حذف شده است", "Original message deleted")
                } else {
                    val who = if (orig.mine) L("من", "Me")
                        else (chat?.partnerName?.ifBlank { null } ?: L("دوست", "Friend"))
                    val short = if (orig.text.length > 30) orig.text.substring(0, 30) + "…" else orig.text
                    "↩ $who: $short"
                }
                bubble.addView(TextView(this).apply {
                    text = quote
                    textSize = 11f
                    setTextColor(Color.parseColor("#455A64"))
                    setPadding(0, 0, 0, dp(2))
                })
            }
            val textView = TextView(this).apply {
                text = row.text
                textSize = 14f
                setTextColor(Color.BLACK)
                maxWidth = maxW
            }
            bubble.addView(textView)
            val meta = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(2), 0, 0)
            }
            meta.addView(TextView(this).apply {
                text = timeFmt.format(Date(row.ts))
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
            })
            if (row.mine) {
                meta.addView(TextView(this).apply {
                    text = when {
                        row.pending -> " ⏳"
                        row.read -> " ✓✓"
                        else -> " ✓"
                    }
                    textSize = 11f
                    setTextColor(Color.parseColor("#0F766E"))
                })
            }
            bubble.addView(meta)
            rowBox.addView(bubble)
            msgColumn.addView(rowBox)
        }
        msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showMessageMenu(row: Row) {
        val items = mutableListOf<String>()
        if (mode == "chat") {
            items += L("پاسخ", "Reply")
            if (row.mine) items += L("ویرایش", "Edit")
        }
        items += L("حذف", "Delete")
        items += L("کپی", "Copy")
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                val picked = items[which]
                when {
                    picked == L("پاسخ", "Reply") -> {
                        replyToTs = row.ts
                        val who = if (row.mine) L("من", "Me")
                            else (chat?.partnerName?.ifBlank { null } ?: L("دوست", "Friend"))
                        val short = if (row.text.length > 30) row.text.substring(0, 30) + "…" else row.text
                        replyBarText.text = "↩ $who: $short"
                        replyBar.visibility = View.VISIBLE
                        input.requestFocus()
                    }
                    picked == L("ویرایش", "Edit") -> editMessage(row)
                    picked == L("حذف", "Delete") -> deleteRow(row)
                    picked == L("کپی", "Copy") -> {
                        try {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("message", row.text))
                            Toast.makeText(this, L("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                }
            }
            .show()
    }

    private fun showHeaderMenu() {
        val c = chat ?: return
        val blockLabel = if (c.blockedByMe) L("رفع مسدودیت", "Unblock")
            else L("مسدود کردن", "Block")
        AlertDialog.Builder(this)
            .setItems(arrayOf(
                L("پاک کردن همه پیام‌ها (دو طرفه)", "Delete all messages (both sides)"),
                blockLabel)) { _, which ->
                if (which == 0) confirmDeleteAll() else toggleBlock()
            }
            .show()
    }

    private fun editMessage(row: Row) {
        val field = EditText(this).apply {
            setText(row.text)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(L("ویرایش پیام", "Edit message"))
            .setView(field)
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val newText = field.text.toString().trim()
                if (newText.isBlank() || newText == row.text) return@setPositiveButton
                if (row.pending) {
                    // 2.64: the queue stores the stored (encrypted)
                    // form, like a fresh send.
                    val encNew = AlienE2e.encryptText(AlienE2e.getOrCreateKey(this, chatId, atSetup = false), newText)
                    val storedNew = if (row.replyTo != null) "R${row.replyTo}|$encNew" else encNew
                    OfflineQueue.updateText(this, chatId, row.ts, storedNew)
                    loadMessages()
                    return@setPositiveButton
                }
                val c = chat ?: return@setPositiveButton
                Thread {
                    try {
                        val own = AlienChats.parseSub(ShzClient.fetchAny(c.ownView))
                        // 2.64: encrypt the edited text the same
                        // way as a fresh send.
                        val encNew = AlienE2e.encryptText(AlienE2e.getOrCreateKey(this, c.id, atSetup = false), newText)
                        val second = if (row.replyTo != null) "R${row.replyTo}|$encNew" else encNew
                        val kept = own.messages.map {
                            if (it.first == row.ts) AlienChats.msgLine(it.first, second)
                            else AlienChats.msgLine(it.first, it.second)
                        }
                        val next = AlienChats.buildOwnContent(
                            c.myName, c.myClearTs, c.blockedByMe, kept, own.seenTs ?: 0L)
                        val ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                        runOnUiThread {
                            if (!ok) Toast.makeText(this,
                                L("ویرایش نشد؛ اینترنت را چک کن",
                                    "Could not edit; check your connection"),
                                Toast.LENGTH_SHORT).show()
                            loadMessages()
                            try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                        runOnUiThread { loadMessages() }
                    }
                }.start()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun confirmDeleteAll() {
        val c = chat ?: return
        AlertDialog.Builder(this)
            .setMessage(L("همه پیام‌ها از دو طرف پاک شوند؟",
                "Delete all messages on both sides?"))
            .setPositiveButton(L("پاک کردن همه", "Delete all")) { _, _ ->
                Thread {
                    try {
                        val own = AlienChats.parseSub(ShzClient.fetchAny(c.ownView))
                        val now = System.currentTimeMillis()
                        val next = AlienChats.buildOwnContent(
                            c.myName, now, c.blockedByMe, emptyList(), own.seenTs ?: 0L)
                        ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                        c.myClearTs = now
                        AlienChats.update(this, c)
                        runOnUiThread {
                            loadMessages()
                            try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                        runOnUiThread { loadMessages() }
                    }
                }.start()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
    }

    private fun toggleBlock() {
        val c = chat ?: return
        Thread {
            try {
                val own = AlienChats.parseSub(ShzClient.fetchAny(c.ownView))
                val blocked = !c.blockedByMe
                val next = AlienChats.buildOwnContent(
                    c.myName, c.myClearTs, blocked,
                    own.messages.map { AlienChats.msgLine(it.first, it.second) },
                    own.seenTs ?: 0L)
                val ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                if (ok) {
                    c.blockedByMe = blocked
                    AlienChats.update(this, c)
                }
                runOnUiThread {
                    Toast.makeText(this,
                        if (blocked) L("مسدود شد", "Blocked")
                        else L("رفع مسدودیت شد", "Unblocked"),
                        Toast.LENGTH_SHORT).show()
                    loadMessages()
                    try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                runOnUiThread { loadMessages() }
            }
        }.start()
    }

    private fun deleteRow(row: Row) {
        if (row.pending) {
            OfflineQueue.remove(this, chatId, row.ts)
            loadMessages()
            try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
            return
        }
        if (row.mine && mode == "chat") {
            // Sent: delete both ways — rebuild my link without it.
            val c = chat ?: return
            Thread {
                try {
                    val own = AlienChats.parseSub(ShzClient.fetchAny(c.ownView))
                    val kept = own.messages.filter { it.first != row.ts }
                    val next = AlienChats.buildOwnContent(
                        c.myName, c.myClearTs, c.blockedByMe,
                        kept.map { AlienChats.msgLine(it.first, it.second) },
                        own.seenTs ?: 0L)
                    val ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                    runOnUiThread {
                        if (!ok) {
                            Toast.makeText(this, L("حذف نشد؛ اینترنت را چک کن",
                                "Could not delete; check your connection"),
                                Toast.LENGTH_SHORT).show()
                        }
                        loadMessages()
                        try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    runOnUiThread { loadMessages() }
                }
            }.start()
            return
        }
        // Received: hide locally only (widget + this window filter it).
        WidgetStyle.hideMessage(this, hiddenKey(), row.ts)
        loadMessages()
        try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
    }

    private fun send() {
        val c = chat ?: return
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        sendBtn.isEnabled = false
        val ts = System.currentTimeMillis()
        val replyTo = replyToTs
        // 2.64: encrypt with this chat's own key before the message
        // leaves the device; the queue stores the stored form.
        val enc = AlienE2e.encryptText(AlienE2e.getOrCreateKey(this, c.id, atSetup = false), text)
        val stored = if (replyTo != null) "R$replyTo|$enc" else enc
        Thread {
            try {
                var content = ShzClient.fetchAny(c.ownView)
                if (content == null) {
                    val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                    if (created == null) {
                        OfflineQueue.add(this, OfflineQueue.Item(chatId, ts, stored))
                        runOnUiThread {
                            Toast.makeText(this, L("اینترنت نیست؛ پیام در صف ماند تا بعد فرستاده شود",
                                "No connection; the message is queued and will be sent later"),
                                Toast.LENGTH_SHORT).show()
                            sendBtn.isEnabled = true
                            loadMessages()
                        }
                        return@Thread
                    }
                    c.ownView = created.first
                    c.ownManage = created.second
                    AlienChats.update(this, c)
                    content = ""
                    runOnUiThread {
                        Toast.makeText(this, L("لینک خودت منقضی شده بود؛ لینک تازه ساخته شد — آن را دوباره به دوستت بده",
                            "Your link had expired; a fresh one was created — give it to your friend again"),
                            Toast.LENGTH_LONG).show()
                    }
                    Thread {
                        try { AlienChats.ensureNameLine(c.ownManage, c.myName, AlienSub.sendMode(this)) } catch (_: Exception) {}
                    }.start()
                }
                val own = AlienChats.parseSub(content)
                val lines = own.messages.map { AlienChats.msgLine(it.first, it.second) }.toMutableList()
                lines += AlienChats.msgLine(ts, stored)
                val next = AlienChats.buildOwnContent(
                    c.myName, c.myClearTs, c.blockedByMe, lines, own.seenTs ?: 0L)
                val ok = ShzClient.updateForMode(AlienSub.sendMode(this), c.ownManage, next)
                runOnUiThread {
                    if (!ok) {
                        OfflineQueue.add(this, OfflineQueue.Item(chatId, ts, stored))
                        Toast.makeText(this, L("اینترنت نیست؛ پیام در صف ماند تا بعد فرستاده شود",
                            "No connection; the message is queued and will be sent later"),
                            Toast.LENGTH_SHORT).show()
                    } else {
                        try { AlienChatWidgetProvider.refreshAll(this) } catch (_: Exception) {}
                    }
                    input.setText("")
                    replyToTs = null
                    replyBar.visibility = View.GONE
                    sendBtn.isEnabled = true
                    loadMessages()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    sendBtn.isEnabled = true
                    Toast.makeText(this, L("فرستاده نشد؛ اینترنت را چک کن",
                        "Could not send; check your connection"), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
