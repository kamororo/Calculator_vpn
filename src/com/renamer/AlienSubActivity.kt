package com.renamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Message-link screen (2.27), one page with two
 *  simple modes. Sender: create one link, share
 *  its alien form, send texts to it. Receiver:
 *  paste one alien link, refresh it, optionally
 *  auto-check it. The raw site address is never
 *  shown here — links appear only alien-encoded. */
class AlienSubActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var mode = "receiver"
    private var pendingAuto = -1
    private lateinit var root: LinearLayout
    private var senderViewBox: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "receiver"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the link history may
        // have switched the active link.
        if (mode == "sender") {
            val active = AlienSub.activeSenderLink(this)
            val box = senderViewBox
            if (active == null) {
                // every sender link is
                // gone — show the create state
                // instead of a deleted link.
                if (box != null) {
                    senderViewBox = null
                    rebuild()
                    try {  } catch (_: Exception) {}
                }
            } else if (box != null) {
                box.text = AlienSub.displayForm(active.view)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getStringExtra("mode") != null) {
            mode = intent.getStringExtra("mode")!!
            rebuild()
        }
    }

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun label(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(6))
        })
    }

    private fun bigButton(label: String, onClick: () -> Unit) {
        val b = AlienSub.miniButton(this, label)
        b.textSize = 15f
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.setOnClickListener { onClick() }
        root.addView(b, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun smallButton(label: String, onClick: () -> Unit) {
        val b = AlienSub.miniButton(this, label)
        b.setOnClickListener { onClick() }
        root.addView(b, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })
    }

    private fun copyableBox(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@AlienSubActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(tv)
        return tv
    }

    private fun copyToClipboard(text: String, doneFa: String, doneEn: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("alien-link", text))
            Toast.makeText(this, L(doneFa, doneEn), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun rebuild() {
        root.removeAllViews()
        if (mode == "sender") buildSender() else buildReceiver()
        HelpUi.attach(this, if (mode == "sender") "sender" else "receiver")
    }

    // ---------------- Sender ----------------

    private fun buildSender() {
        title(L("لینک پیام من", "My message link"))
        // History icon at the top of the page
        // (2.29): past links, numbered + dated.
        val histRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val histBtn = Button(this).apply {
            text = "🕒"
            textSize = 16f
            background = HydraUi.glassPill(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 18f, this@AlienSubActivity)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                startActivity(Intent(this@AlienSubActivity, AlienSenderHistoryActivity::class.java))
            }
        }
        histRow.addView(histBtn)
        root.addView(histRow)
        val view = AlienSub.senderView(this)
        val manage = AlienSub.senderManage(this)
        if (view == null || manage == null) {
            note(L(
                "یک لینک بساز و آن را به دیگران بده؛ هر متنی که به این لینک بفرستی، گیرنده‌ها آن را می‌گیرند.",
                "Create a link and give it to others; any text you send to this link reaches its receivers."))
            bigButton(L("ساخت لینک پیام", "Create message link")) {
                Thread {
                    val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                    runOnUiThread {
                        if (created == null) {
                            Toast.makeText(this,
                                L("ساخت لینک انجام نشد؛ اینترنت را چک کن", "Could not create the link; check your connection"),
                                Toast.LENGTH_SHORT).show()
                        } else {
                            AlienSub.setSender(this, created.first, created.second)
                            rebuild()
                            AppLog.append(this, "alien",
                                "لینک پیام ساخته شد (بدون نمایش لینک).",
                                "Message link created (link not shown).")
                        }
                    }
                }.start()
            }
            return
        }
        note(L(
            "این لینک توست. لینک اول را به دیگران بده تا پیام‌هایت را بگیرند.",
            "This is your link. Give the first link to others so they receive your messages."))
        label(L("لینک پیام شما برای ارسال به دیگران", "Your message link to send to others"))
        val alienView = AlienSub.displayForm(view)
        senderViewBox = copyableBox(alienView)
        // 2.42: "Create a fresh link" sits NEXT TO
        // "Copy link" (user order) — one row, both
        // actions on the same link.
        val linkBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val copyBtn = AlienSub.miniButton(this, L("کپی لینک", "Copy link"))
        // copy the box's CURRENT text, not
        // the value captured when the page was
        // built, so a link switched in the
        // history is what actually gets shared.
        copyBtn.setOnClickListener {
            val current = senderViewBox?.text?.toString()?.takeIf { it.isNotBlank() } ?: alienView
            copyToClipboard(current, "کپی شد", "Copied")
        }
        linkBtnRow.addView(copyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        val freshBtn = AlienSub.miniButton(this, L("ساخت لینک تازه", "Create a fresh link"))
        freshBtn.setOnClickListener {
            val oldFreshText = freshBtn.text.toString()
            freshBtn.isEnabled = false
            freshBtn.text = L("در حال ساخت…", "Creating…")
            Thread {
                val created = ShzClient.createForMode(AlienSub.sendMode(this), "")
                runOnUiThread {
                    freshBtn.isEnabled = true
                    freshBtn.text = oldFreshText
                    if (created == null) {
                        Toast.makeText(this,
                            L("ساخت لینک انجام نشد؛ اینترنت را چک کن", "Could not create the link; check your connection"),
                            Toast.LENGTH_SHORT).show()
                    } else {
                        AlienSub.addSenderLink(this, created.first, created.second)
                        rebuild()
                        Toast.makeText(this,
                            L("لینک تازه ساخته شد؛ لینک قبلی در سابقه ماند", "Fresh link created; the old one stays in history"),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        linkBtnRow.addView(freshBtn)
        root.addView(linkBtnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        label(L("فرستادن پیام به لینک", "Send a message to the link"))
        val input = EditText(this).apply {
            hint = L("پیام را اینجا بنویس…", "Write your message here…")
            textSize = 15f
            setTextColor(Color.BLACK)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(88)
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@AlienSubActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(input)
        bigButton(L("فرستادن", "Send")) {
            val text = input.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, L("اول پیام را بنویس", "Write the message first"), Toast.LENGTH_SHORT).show()
                return@bigButton
            }
            val alien = try { SecretLanguage.encode(text) } catch (_: Exception) { "" }
            Thread {
                // no fallback to the
                // page-creation manage link. If
                // there is no active link any
                // more, refuse instead of
                // sending through a stale one.
                val liveManage = AlienSub.senderManage(this)
                if (liveManage == null) {
                    runOnUiThread {
                        Toast.makeText(this,
                            L("لینکی فعال نیست؛ اول یک لینک بساز", "No active link; create one first"),
                            Toast.LENGTH_SHORT).show()
                        try {  } catch (_: Exception) {}
                    }
                    return@Thread
                }
                val ok = ShzClient.updateForMode(AlienSub.sendMode(this), liveManage, alien)
                runOnUiThread {
                    if (ok) {
                        input.setText("")
                        Toast.makeText(this, L("فرستاده شد", "Sent"), Toast.LENGTH_SHORT).show()
                        AppLog.append(this, "alien",
                            "پیام فرستاده شد (بدون محتوا).",
                            "Message sent (no content).")
                    } else {
                        Toast.makeText(this,
                            L("فرستاده نشد؛ اینترنت را چک کن", "Could not send; check your connection"),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    // ---------------- Receiver ----------------

    private fun buildReceiver() {
        title(L("دریافت پیام", "Receive messages"))
        val view = AlienSub.receiverView(this)
        if (view == null) {
            note(L(
                "لینک پیام فرستنده را (با همان شکل عجیب و غریب) اینجا بچسبان و ثبت کن؛ بعد پیام‌های تازه‌اش را می‌گیری.",
                "Paste the sender's message link (in its strange form) here and save it; then you will receive their new messages."))
            val input = EditText(this).apply {
                hint = L("لینک را اینجا بچسبان…", "Paste the link here…")
                textSize = 15f
                setTextColor(Color.BLACK)
                gravity = Gravity.TOP or Gravity.START
                minHeight = dp(88)
                background = HydraUi.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@AlienSubActivity)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            root.addView(input)
            bigButton(L("ثبت لینک", "Save link")) {
                val raw = AlienSub.parseInput(input.text.toString())
                if (raw == null) {
                    Toast.makeText(this,
                        L("این لینک درست نیست؛ لینک را با همان شکل عجیب و غریب بچسبان", "This link is not right; paste it in its strange form"),
                        Toast.LENGTH_SHORT).show()
                    return@bigButton
                }
                AlienSub.setReceiver(this, raw)
                rebuild()
            }
            return
        }
        label(L("آخرین پیام", "Latest message"))
        val msgBox = TextView(this).apply {
            text = AlienSub.lastMessage(this@AlienSubActivity)
                ?: L("هنوز پیامی نیامده است", "No message yet")
            textSize = 15f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(88)
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 16f, this@AlienSubActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(msgBox)
        bigButton(L("تازه‌سازی", "Refresh")) {
            refreshReceiver(showToast = true, msgBox = msgBox)
        }
        label(L("بررسی خودکار", "Auto-check"))
        val current = AlienSub.autoMinutes(this)
        val options = listOf(0 to L("خاموش", "Off"), 5 to L("۵ دقیقه", "5 min"), 15 to L("۱۵ دقیقه", "15 min"), 30 to L("۳۰ دقیقه", "30 min"), 60 to L("۶۰ دقیقه", "60 min"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pills = mutableListOf<Button>()
        for ((mins, lab) in options) {
            val b = AlienSub.miniButton(this, lab)
            b.textSize = 12f
            b.setOnClickListener {
                if (mins == 0) {
                    AlienSub.setAutoMinutes(this, 0)
                    paintPills(pills, options, 0)
                    return@setOnClickListener
                }
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingAuto = mins
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9101)
                    return@setOnClickListener
                }
                AlienSub.setAutoMinutes(this, mins)
                paintPills(pills, options, mins)
                Toast.makeText(this,
                    L("بررسی خودکار روشن شد", "Auto-check is on"),
                    Toast.LENGTH_SHORT).show()
            }
            pills += b
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        paintPills(pills, options, current)
        root.addView(row)
        root.addView(TextView(this).apply {
            text = L(
                "اگر پیام تازه‌ای بیاید، با صدا خبرت می‌کند و پیام روی صفحه اصلی و صفحه قفل هم دیده می‌شود.",
                "When a new message arrives, it notifies you with sound, and the message also shows on the home screen and the lock screen.")
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        smallButton(L("قطع لینک", "Disconnect link")) {
            AlienSub.clearReceiver(this)
            AlienSub.setAutoMinutes(this, 0)
            AlienSub.refreshWidget(this)
            rebuild()
        }
        // Opening this page refreshes the message
        // on its own, so the receiver never has to
        // wonder whether what they see is current.
        refreshReceiver(showToast = false, msgBox = msgBox)
    }

    private fun paintPills(pills: List<Button>, options: List<Pair<Int, String>>, selected: Int) {
        val sel = Color.parseColor("#0F766E")
        for (i in pills.indices) {
            val mins = options[i].first
            if (mins == selected) {
                pills[i].background = AlienSub.flatPill(this, sel)
                pills[i].setTextColor(Color.WHITE)
            } else {
                pills[i].background = HydraUi.glassPill(
                    Color.parseColor("#CCFFFFFF"), Color.parseColor("#FFBFBFBF"), 20f, this)
                pills[i].setTextColor(Color.parseColor("#455A64"))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingAuto > 0) {
                AlienSub.setAutoMinutes(this, pendingAuto)
                Toast.makeText(this,
                    L("بررسی خودکار روشن شد", "Auto-check is on"),
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this,
                    L("برای اعلان پیام تازه، دسترسی اعلان لازم است", "Notification permission is needed to alert you about new messages"),
                    Toast.LENGTH_SHORT).show()
            }
            pendingAuto = -1
            rebuild()
        }
    }

    /** Fetch the receiver link in the background.
     *  Returns true when the message is new. */
    fun refreshReceiver(showToast: Boolean, msgBox: TextView? = null) {
        val view = AlienSub.receiverView(this) ?: return
        Thread {
            val content = ShzClient.fetchAny(view)
            runOnUiThread {
                if (content == null) {
                    if (showToast) Toast.makeText(this,
                        L("لینک پیدا نشد؛ شاید منقضی شده باشد", "The link was not found; it may have expired"),
                        Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val old = AlienSub.lastContent(this)
                if (content == old) {
                    if (showToast) Toast.makeText(this,
                        L("پیام تازه‌ای نیست", "No new message"),
                        Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val decoded = try { SecretLanguage.decode(content) } catch (_: Exception) { "" }
                val shown = if (decoded.isBlank() || AlienSub.revealsSite(decoded)) "" else decoded
                AlienSub.setLast(this, content, shown)
                AlienSub.refreshWidget(this)
                if (msgBox != null) {
                    msgBox.text = if (shown.isBlank()) L("پیام خوانا نیست", "Message is unreadable") else shown
                }
                if (showToast) Toast.makeText(this,
                    L("پیام تازه رسید", "A new message arrived"),
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
