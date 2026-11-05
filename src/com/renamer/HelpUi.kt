package com.renamer

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Context help + about for the main app's Alien Language section
 *  (ported from the standalone app, user order 2026-11-02;
 *  the main app has its own language section, so no per-page
 *  language button is added here)
 *  (1.3, fixed 1.4, user order 2026-11-02).
 *
 *  - A small floating galaxy-glass button, icon
 *    only, on every page. Tapping it opens a
 *    short, precise help sheet for THAT page.
 *  - A small glass icon on the converter's top
 *    row opens the full about sheet: every
 *    capability, how the app works, and the
 *    privacy reassurance.
 *
 *  1.4 FIXES (user feedback on 1.3): the button
 *  is now TRULY circular (the 1.3 drawable drew
 *  a circle larger than its bounds, so the
 *  square clip made it look rectangular), SMALLER
 *  (32dp, like the language circle), MUTED
 *  (pale, low-saturation galaxy, not deep
 *  indigo/purple), with a DRAWN "?" help icon
 *  in the middle (not a text label), and it is
 *  DRAGGABLE: drag it anywhere on the page;
 *  a tap (no drag) still opens help, and the
 *  position you drop it at is saved and reused
 *  on every page.
 *
 *  SECRECY RULE: no help or about text names
 *  any site, host, address or service the app
 *  uses. Links are called "private link" only.
 *  The privacy claim is kept to what is true
 *  and checkable: the app sends no copy of a
 *  link or message to the developer, there is
 *  no account, and only a link holder can open
 *  a chat.
 *
 *  The galaxy look is DRAWN (gradient, stars,
 *  nebula blobs, glass gloss) — never a photo
 *  crop pasted as UI. */

/** Drawn mild galaxy: pale low-saturation
 *  gradient, faint nebula blobs, small muted
 *  stars, glass gloss, and a drawn "?" help
 *  icon in deep indigo in the centre.
 *  The circle radius is exactly half the
 *  smaller side, so the drawable IS a circle. */
class GalaxyDrawable(private val density: Float) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#283593")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(170, 120, 144, 156)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Exactly half the smaller side: a true circle.
        val r = kotlin.math.min(w, h) / 2f
        bgPaint.shader = RadialGradient(
            cx - w * 0.2f, cy - h * 0.25f, r * 1.4f,
            intArrayOf(
                Color.parseColor("#E8EAF6"),
                Color.parseColor("#C5CAE9"),
                Color.parseColor("#B0BEC5")
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, bgPaint)
        // Faint nebula blobs (drawn, not photos).
        blobPaint.color = Color.argb(28, 149, 117, 205)
        canvas.drawCircle(cx - w * 0.25f, cy + h * 0.2f, w * 0.30f, blobPaint)
        blobPaint.color = Color.argb(28, 128, 203, 196)
        canvas.drawCircle(cx + w * 0.28f, cy - h * 0.18f, w * 0.26f, blobPaint)
        blobPaint.color = Color.argb(45, 255, 255, 255)
        canvas.drawCircle(cx + w * 0.1f, cy + h * 0.05f, w * 0.18f, blobPaint)
        // Small muted stars.
        starPaint.color = Color.parseColor("#5C6BC0")
        for (i in 0 until 28) {
            val sx = ((i * 37) % 100) / 100f * w
            val sy = ((i * 53) % 100) / 100f * h
            // Keep stars inside the circle.
            val dx = sx - cx
            val dy = sy - cy
            if (dx * dx + dy * dy > (r * 0.85f) * (r * 0.85f)) continue
            val sr = (0.6f + ((i * 7) % 3) * 0.4f) * density
            starPaint.alpha = 80 + ((i * 29) % 80)
            canvas.drawCircle(sx, sy, sr, starPaint)
        }
        // Glass gloss: bright at the top, fading down.
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.6f,
            intArrayOf(Color.argb(55, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, glossPaint)
        // Drawn help icon: "?" in the centre.
        iconPaint.textSize = h * 0.52f
        val fm = iconPaint.fontMetrics
        val iy = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText("?", cx, iy, iconPaint)
        canvas.drawCircle(cx, cy, r - 0.5f * density, edgePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object HelpUi {
    private const val TAG = "alien_help_fab"
    private const val POS_PREFS = "alien_help_pos"

    private fun dp(activity: Activity, v: Int) =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun t(activity: Activity, fa: String, en: String) =
        AppLanguage.t(activity, fa, en)

    /** (title, body) for one page. Precise:
     *  exact button labels, exact steps, exact
     *  behaviour. No site names, no addresses. */
    /** 2.35: help as (option, explanation) pairs, so the sheet
     *  writes each option name in readable green and its short
     *  black explanation under it. No site, host or address named. */
    private fun helpText(activity: Activity, key: String): Pair<String, List<Pair<String, String>>> {
        return when (key) {
            "hub" -> t(activity, "راهنمای پیام بیگانه", "Alien Message help") to listOf(
                t(activity, "من فرستنده‌ام", "I am the sender") to
                    t(activity, "به صفحه ساختن لینک پیام می‌برد", "Takes you to the message-link creation page"),
                t(activity, "من گیرنده‌ام", "I am the receiver") to
                    t(activity, "به صفحه وارد کردن لینک دوستت می‌برد", "Takes you to the page for entering a friend's link"),
                t(activity, "باز کردن مسنجر", "Open the messenger") to
                    t(activity, "گفتگوی دوطرفه را باز می‌کند", "Opens two-way chats"))
            "sender" -> t(activity, "راهنمای لینک پیام من", "My message link help") to listOf(
                t(activity, "ساخت لینک پیام", "Create message link") to
                    t(activity, "وقتی هنوز هیچ لینکی نداری، این دکمه را بزن تا لینک پیام تو ساخته شود؛ هر لینک تا ۹۰ روز معتبر است و بعد از آن باید با «ساخت لینک تازه» لینک تازه بسازی", "Tap this when you have no link yet to create your message link; each link stays valid for 90 days, after which you need a fresh one with “Create a fresh link”"),
                t(activity, "لینک پیام شما برای ارسال به دیگران", "Your message link to send to others") to
                    t(activity, "عنوان کادر لینک همین صفحه است؛ لینک داخل همین کادر، لینک پیام توست و همان چیزی است که باید به دیگران بدهی تا پیام‌هایت را بگیرند", "This is the label above the link box on this page; the link inside that box is your message link, the one to give others so they receive your messages"),
                t(activity, "کپی لینک", "Copy link") to
                    t(activity, "لینک داخل کادر را کپی می‌کند تا آن را در هر برنامه‌ای برای دوستت بفرستی؛ دکمه‌ی «ساخت لینک تازه» دقیقاً کنار همین دکمه است", "Copies the link inside the box so you can send it to your friend from any app; the “Create a fresh link” button sits right next to this one"),
                t(activity, "ساخت لینک تازه", "Create a fresh link") to
                    t(activity, "یک لینک پیام تازه می‌سازد و آن را جای لینک فعلی می‌گذارد؛ لینک قبلی پاک نمی‌شود و در سابقه (نماد ساعت بالای صفحه) می‌ماند تا هر وقت خواستی دوباره همان را فعال کنی", "Creates a fresh message link and replaces the current one with it; the old link is not deleted and stays in history (the clock icon at the top of the page) so you can activate it again any time"),
                t(activity, "فرستادن پیام به لینک", "Send a message to the link") to
                    t(activity, "عنوان بخش نوشتن پیام است؛ پیام را در کادر زیر همین عنوان بنویس و با دکمه‌ی «فرستادن» بفرست", "This is the label of the message-writing section; write your message in the box under this label and send it with the “Send” button"),
                t(activity, "فرستادن", "Send") to
                    t(activity, "متن کادر «فرستادن پیام به لینک» را به لینک فعلی می‌فرستد؛ همین پیام جای پیام قبلی همان لینک را می‌گیرد، پس گیرنده‌ها فقط همین پیام تازه را می‌بینند", "Sends the text of the “Send a message to the link” box to the current link; it replaces that link's previous message, so receivers only see this new one"),
                t(activity, "سابقه (نماد ساعت)", "History (clock icon)") to
                    t(activity, "لینک‌های قبلی‌ات را نشان می‌دهد؛ روی هر ردیف بزن تا همان لینک فعال شود و پیام‌های بعدی با آن فرستاده شوند؛ با مداد برای هر لینک اسم بگذار؛ اسم فقط برای خودت است و لینک را عوض نمی‌کند", "Shows your previous links; tap a row to activate that link so later messages go through it; use the pencil to name a link — the name is only for you and does not change the link"))
            "receiver" -> t(activity, "راهنمای گیرنده", "Receiver help") to listOf(
                t(activity, "ثبت لینک", "Save link") to
                    t(activity, "لینک بیگانه دوستت را بچسبان و ثبت کن", "Paste your friend's alien link and save it"),
                t(activity, "تازه‌سازی", "Refresh") to
                    t(activity, "پیام تازه را می‌گیرد؛ باز کردن صفحه هم خودش تازه می‌کند", "Fetches a new message; opening the page refreshes it too"),
                t(activity, "بررسی خودکار", "Auto-check") to
                    t(activity, "خاموش یا هر ۵، ۱۵، ۳۰، ۶۰ دقیقه؛ با انتخاب زمان، پیام تازه با صدا اعلان می‌شود و برای همین اولین بار اجازه اعلان خواسته می‌شود", "Off or every 5, 15, 30, 60 minutes; with a time set, a new message notifies you with sound, which is why notification permission is asked the first time"),
                t(activity, "قطع لینک", "Disconnect link") to
                    t(activity, "لینک ثبت‌شده و بررسی خودکار را پاک می‌کند", "Clears the saved link and the auto-check"),
                t(activity, "ویجت", "Widget") to
                    t(activity, "آخرین پیام همین لینک را نشان می‌دهد", "Shows the latest message of this same link"))
            "history" -> t(activity, "راهنمای سابقه‌ی لینک‌ها", "Link history help") to listOf(
                t(activity, "ردیف لینک", "Link row") to
                    t(activity, "بزن تا همان لینک فعال شود و پیام‌ها با آن فرستاده شوند", "Tap it to activate that link, so your messages go through it"),
                t(activity, "مداد", "Pencil") to
                    t(activity, "برای لینک اسم می‌گذارد؛ اسم فقط برای خودت است و لینک را عوض نمی‌کند", "Names the link; the name is only for you and does not change the link"))
            "msgr_list" -> t(activity, "راهنمای مسنجر", "Messenger help") to listOf(
                t(activity, "ردیف گفتگو", "Conversation row") to
                    t(activity, "بزن تا وارد همان گفتگو شوی", "Tap it to enter that conversation"),
                t(activity, "نگه داشتن ردیف", "Long-press a row") to
                    t(activity, "بردن به بالای فهرست یا پاک کردن گفتگو؛ پاک کردن لینک خودت را هم از سرور پاک می‌کند", "Move it to the top of the list, or delete the conversation; deleting also deletes your own link from the server"),
                t(activity, "گفتگوی جدید", "New conversation") to
                    t(activity, "برای هر دوست یک لینک تازه می‌سازد و گفتگوی تازه باز می‌کند", "Creates a fresh link for each friend and opens a new conversation"))
            "msgr_setup" -> t(activity, "راهنمای گفتگوی جدید", "New conversation help") to listOf(
                t(activity, "لینک تازه", "Fresh link") to
                    t(activity, "برای همین گفتگو یک لینک تازه می‌سازد و آن را در کادر «این لینک را به دوستت بده» نشان می‌دهد؛ هر گفتگو لینک خودش را دارد و در این صفحه فقط همین یک لینک نشان داده می‌شود", "Creates a fresh link for this conversation and shows it in the “Give this link to your friend” box; each conversation has its own link, and this page shows only that one link"),
                t(activity, "این لینک را به دوستت بده", "Give this link to your friend") to
                    t(activity, "عنوان کادر لینک همین صفحه است؛ لینک داخل همین کادر، لینک همین گفتگوست و همان چیزی است که باید به دوستت بدهی تا گفتگو وصل شود", "This is the label above the link box on this page; the link inside that box is this conversation's link, the one to give your friend so the chat connects"),
                t(activity, "کپی", "Copy") to
                    t(activity, "لینک داخل کادر «این لینک را به دوستت بده» را کپی می‌کند تا آن را برای دوستت بفرستی", "Copies the link inside the “Give this link to your friend” box so you can send it to your friend"),
                t(activity, "لینک دوست", "Friend's link") to
                    t(activity, "عنوان کادر دوست است؛ لینک بیگانه‌ای را که دوستت برایت فرستاده، در کادر زیر همین عنوان بچسبان؛ بدون آن، دکمه‌ی «شروع گفتگو» کار نمی‌کند", "This is the label of the friend's section; paste the alien link your friend sent you into the box under this label — without it, the “Start conversation” button does nothing"),
                t(activity, "شروع گفتگو", "Start conversation") to
                    t(activity, "وقتی لینک همین گفتگو با «لینک تازه» ساخته شده و لینک دوستت را هم چسبانده‌ای، این دکمه را بزن تا گفتگو باز شود؛ اگر اسمت ثبت نشده باشد، اول اسمت را می‌خواهد تا دوستت آن را بالای گفتگو ببیند؛ اگر لینک هنوز ساخته نشده باشد، می‌گوید اول با «لینک تازه» یک لینک بساز", "Tap this once this conversation's link is created with “Fresh link” and your friend's link is pasted, to open the chat; if your name is not saved yet, it asks for it first so your friend sees it at the top of the chat; if no link is created yet, it tells you to create one with “Fresh link” first"))
            "msgr_chat" -> t(activity, "راهنمای گفتگو", "Chat help") to listOf(
                t(activity, "کادر پایین و دکمه سبز", "Lower box and green button") to
                    t(activity, "پیام را بنویس و بفرست؛ برنامه فقط داخل همین گفتگو هر ۱۰ ثانیه پیام تازه می‌گیرد", "Write the message and send it; the app only fetches new messages every 10 seconds while you are inside this chat"),
                t(activity, "تیک‌ها", "Ticks") to
                    t(activity, "یک تیک یعنی فرستاده شده، دو تیک یعنی دوستت خوانده است", "One tick means sent, two ticks mean your friend has read it"),
                t(activity, "نگه داشتن حباب خودت", "Long-press your own bubble") to
                    t(activity, "ویرایش یا حذف؛ حذف برای هر دو طرف است", "Edit or delete it; deleting removes it for both sides"),
                t(activity, "کشیدن حباب به چپ", "Swipe a bubble left") to
                    t(activity, "در پاسخ به همان پیام می‌نویسی و نقل کوتاهی از آن بالای پیام تازه دیده می‌شود", "You reply to that exact message, and a short quote of it shows on top of the new message"),
                t(activity, "علامت ساعت", "Clock sign") to
                    t(activity, "پیام بدون اینترنت در صف می‌ماند و با باز شدن گفتگو یا آمدن اینترنت خودش به ترتیب فرستاده می‌شود؛ روی حباب در صف هم بزنی دوباره تلاش می‌کند", "Without internet the message stays in a queue and is sent on its own in order when the chat opens again or the connection returns; tapping a queued bubble retries it too"),
                t(activity, "لینک منقضی", "Expired link") to
                    t(activity, "برنامه خودش لینک تازه می‌سازد و می‌گوید دوباره آن را به دوستت بدهی", "The app creates a fresh link on its own and tells you to share it with your friend again"),
                t(activity, "منوی سه‌نقطه", "Three-dot menu") to
                    t(activity, "پاک کردن همه پیام‌ها از هر دو طرف، یا مسدود / آزاد کردن دوستت؛ وقتی مسدود می‌کنی، دوستت پیام مسدودی را می‌بیند و نمی‌تواند بنویسد", "Delete all messages from both sides, or block / unblock your friend; when you block, your friend sees the blocked notice and cannot write"))
            else -> t(activity, "راهنمای مبدل", "Converter help") to listOf(
                t(activity, "متن ← زبان بیگانه", "Text → Alien") to
                    t(activity, "متن عادی را همان‌طور که می‌نویسی به نماد تبدیل می‌کند", "Converts plain text into symbols as you type"),
                t(activity, "زبان بیگانه ← متن", "Alien → Text") to
                    t(activity, "نماد را به متن برمی‌گرداند", "Turns symbols back into text"),
                t(activity, "آمار زیر خروجی", "Stats under the output") to
                    t(activity, "چند نویسه به چند نماد تبدیل شده و چقدر کوتاه‌تر یا بلندتر شده است", "How many characters became how many symbols, and how much shorter or longer it got"),
                t(activity, "پاک کردن", "Clear") to
                    t(activity, "هر دو کادر را خالی می‌کند", "Empties both boxes"),
                t(activity, "⇅ جابه‌جا", "⇅ Swap") to
                    t(activity, "ورودی و خروجی را عوض می‌کند", "Exchanges input and output"),
                t(activity, "فایل", "File") to
                    t(activity, "متن را از فایل می‌خواند", "Reads text from a file"),
                t(activity, "کپی خروجی", "Copy output") to
                    t(activity, "خروجی را کپی می‌کند", "Copies the output"),
                t(activity, "ذخیره در فایل", "Save to file") to
                    t(activity, "خروجی را در فایل ذخیره می‌کند", "Saves the output to a file"),
                t(activity, "فرستادن به لینک پیام", "Send to message link") to
                    t(activity, "در حالت رمز کردن، خروجی را به لینک پیامت می‌فرستد", "In encode mode, sends the output to your message link"),
                t(activity, "تازه‌سازی پیام", "Refresh message") to
                    t(activity, "پیام تازه لینک را می‌گیرد", "Fetches the link's fresh message"),
                t(activity, "انتقال به v2ray / وایرگارد", "Send to v2ray / WireGuard") to
                    t(activity, "اگر خروجی یک کانفیگ باشد، آن را مستقیم به بخش خودش می‌برد و آنجا با کارت کهکشانی مشخص می‌شود", "If the output is a config, moves it straight to its own section, where it is marked with a galaxy card"),
                t(activity, "تصویر کهکشانی", "Galaxy image") to
                    t(activity, "بزن تا به بخش پیام بروی", "Tap it to go to the message section"))
        }
    }

    /** Sheet: green title, then per item a green option name and
     *  its short black explanation. A blank option renders just
     *  the text (used by the about sheet). */
    private fun showSheet(activity: Activity, title: String, items: List<Pair<String, String>>) {
        try {
            val dlg = Dialog(activity)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(activity, 24).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(activity, 1), Color.parseColor("#1A000000"))
                }
                setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 20))
            }
            card.addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#2E7D32"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(activity, 12))
            })
            card.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1))
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            })
            for ((option, desc) in items) {
                if (option.isNotBlank()) {
                    card.addView(TextView(activity).apply {
                        text = option
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#2E7D32"))
                        setPadding(0, dp(activity, 12), 0, dp(activity, 2))
                    })
                }
                card.addView(TextView(activity).apply {
                    text = desc
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(0, 0, 0, dp(activity, 6))
                })
            }
            card.addView(Button(activity).apply {
                text = t(activity, "بستن", "Close")
                textSize = 15f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(activity, 14).toFloat()
                    setColor(Color.parseColor("#0F766E"))
                }
                setPadding(0, dp(activity, 10), 0, dp(activity, 10))
                setOnClickListener { dlg.dismiss() }
            })
            val scroll = ScrollView(activity)
            scroll.addView(card)
            dlg.setContentView(scroll)
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dlg.show()
        } catch (_: Exception) {}
    }

    fun showHelp(activity: Activity, key: String) {
        val (title, items) = helpText(activity, key)
        showSheet(activity, title, items)
    }

    /** Full about sheet: every capability, how
     *  it works, and the privacy reassurance.
     *  No site, host or address is named. */
    fun showAbout(activity: Activity) {
        val title = t(activity, "درباره‌ی زبان بیگانه", "About Alien Language")
        val body = t(activity,
            "«زبان بیگانه» سه کار اصلی دارد: تبدیل متن به زبان بیگانه و برگرداندن آن، فرستادن پیام خصوصی با لینک، و گفتگوی دوطرفه در مسنجر بیگانه. برای هیچ‌کدام حساب، ورود، یا رمز عبور لازم نیست.\n\nچطور کار می‌کند؟\nمتن تو روی گوشی خودت به نمادهای بیگانه تبدیل می‌شود. برای پیام، همین نمادها در یک لینک خصوصی قرار می‌گیرند و تو آن لینک را فقط به کسی می‌دهی که می‌خواهی پیامت را بخواند. گیرنده لینک را در برنامه وارد می‌کند و برنامه نمادها را روی گوشی او به متن برمی‌گرداند. پیام بیگانه یک پیام تازه است (هر بار جای قبلی را می‌گیرد)؛ مسنجر گفتگوی کامل با سابقه است.\n\nهمه‌ی امکانات:\n• مبدل: تبدیل متن به نماد و برعکس، جابه‌جا، کپی، خواندن و ذخیره از فایل.\n• پیام بیگانه: فرستنده لینک می‌سازد و می‌فرستد؛ گیرنده لینک را وارد می‌کند، پیام تازه را با تازه‌سازی می‌بیند، بررسی خودکار و اعلان پیام تازه را فعال می‌کند و آخرین پیام را در ویجت می‌بیند.\n• سابقه‌ی لینک‌ها: لینک‌های ساخته‌شده، با اسم‌گذاری و فعال‌سازی دوباره.\n• مسنجر بیگانه: فهرست گفتگوها، گفتگوی تازه برای هر دوست، نمایش اسم طرف، مسدود کردن و آزاد کردن، پاک کردن همه‌ی پیام‌ها از هر دو طرف، تیک خوانده شدن، ویرایش و حذف پیام، پاسخ به پیام مشخص، و صف آفلاین.\n• انتقال کانفیگ: اگر خروجی تبدیل یک کانفیگ باشد، با یک دکمه مستقیم به بخش v2ray یا وایرگارد فرستاده می‌شود و آنجا با کارت کهکشانی مشخص می‌شود.\n\nحریم خصوصی:\nبرنامه لینک یا پیام تو را برای سازنده نمی‌فرستد و سازنده هیچ راهی برای دیدن پیام‌هایت ندارد؛ چون هیچ حسابی در برنامه نیست و پیام‌ها فقط با لینک خصوصی تو باز می‌شوند. پس فقط کسی که تو لینک را به او داده‌ای می‌تواند پیام را بخواند. به همین دلیل لینک خصوصی‌ات را فقط به کسی بده که به او اعتماد داری و آن را جایی عمومی پخش نکن. هر وقت خواستی، می‌توانی لینک را پاک کنی یا طرف را مسدود کنی.",
            "«Alien Language» does three main things: converting text to the alien language and back, sending a private message with a link, and two-way chats in Alien Messenger. None of them needs an account, a sign-in, or a password.\n\nHow does it work?\nYour text is turned into alien symbols on your own phone. For a message, those symbols go into a private link, and you give that link only to the person you want to read your message. The receiver enters the link in the app, and the app turns the symbols back into text on their phone. Alien Message holds one fresh message (each send replaces the previous one); the messenger is a full chat with history.\n\nAll the features:\n• Converter: text to symbols and back, swap, copy, and reading from or saving to a file.\n• Alien Message: the sender creates and sends a link; the receiver enters the link, sees new messages with refresh, turns on auto-check and new-message notifications, and sees the latest message in the widget.\n• Link history: links you created, with naming and re-activation.\n• Alien Messenger: conversation list, a fresh conversation per friend, showing each other's names, blocking and unblocking, deleting all messages from both sides, read ticks, editing and deleting a message, replying to a specific message, and an offline queue.\n• Config transfer: if the converter output is a config, one button sends it straight to the v2ray or WireGuard section, where it is marked with a galaxy card.\n\nPrivacy:\nThe app sends no copy of your link or message to the developer, and the developer has no way to see your messages; there is no account in the app, and messages only open with your private link. So only someone you gave the link to can read the message. That is why you should give your private link only to someone you trust, and never post it somewhere public. Whenever you want, you can delete the link or block the other person.")
        showSheet(activity, title, listOf("" to body))
    }

    /** Small glass icon-only button for the
     *  converter's top row (32dp, like the
     *  language circle). Opens the about sheet. */
    fun aboutButton(activity: Activity): Button {
        return Button(activity).apply {
            text = "✦"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            isAllCaps = false
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dp(activity, 1), Color.parseColor("#B2DFDB"))
            }
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32))
            setOnClickListener { showAbout(activity) }
        }
    }

    /** Floating galaxy help button, icon only
     *  (drawn "?" in the drawable, no text),
     *  32dp, on top of the page's content.
     *  Call after every setContentView; an old
     *  button (same tag) is removed first, so
     *  rebuilds never stack buttons.
     *
     *  DRAGGABLE: touching and moving the
     *  button drags it (clamped to the page's
     *  visible area, above the nav bar);
     *  lifting it without a drag opens help;
     *  lifting it after a drag saves its
     *  position, which is reused on every page.
     *  The default spot (no saved position) is
     *  the middle-right of the page (2.31). */
    fun attach(activity: Activity, key: String) {
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            var i = 0
            while (i < content.childCount) {
                if (content.getChildAt(i).tag == TAG) content.removeViewAt(i) else i++
            }
            val size = dp(activity, 32)
            val prefs = activity.getSharedPreferences(POS_PREFS, Activity.MODE_PRIVATE)
            val hasPos = prefs.contains("left") && prefs.contains("top")
            val btn = Button(activity).apply {
                tag = TAG
                text = ""
                isAllCaps = false
                background = GalaxyDrawable(activity.resources.displayMetrics.density)
                setPadding(0, 0, 0, 0)
                minimumWidth = 0
                minimumHeight = 0
                layoutParams = if (hasPos) {
                    FrameLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = prefs.getInt("left", 0)
                        topMargin = prefs.getInt("top", 0)
                    }
                } else {
                    // Default spot (2.31): middle-right, never under
                    // the phone's nav buttons.
                    FrameLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        setMargins(0, 0, dp(activity, 16), 0)
                    }
                }
            }
            content.addView(btn)
            // Clamp a saved position to this page's VISIBLE bounds:
            // above the nav bar and below the status bar (2.31: the
            // old clamp let the button sit under the nav buttons).
            btn.post {
                try {
                    val parent = btn.parent as? View ?: return@post
                    val pw = parent.width
                    val ph = parent.height
                    if (pw > 0 && ph > 0) {
                        val insets = btn.rootWindowInsets
                        val topGuard = insets?.systemWindowInsetTop ?: 0
                        val botGuard = insets?.systemWindowInsetBottom ?: 0
                        val maxTop = (ph - botGuard - size).coerceAtLeast(topGuard)
                        val nl = btn.left.coerceIn(0, (pw - size).coerceAtLeast(0))
                        val nt = btn.top.coerceIn(topGuard, maxTop)
                        if (nl != btn.left || nt != btn.top) {
                            val lp = btn.layoutParams as FrameLayout.LayoutParams
                            lp.gravity = Gravity.TOP or Gravity.START
                            lp.leftMargin = nl
                            lp.topMargin = nt
                            btn.layoutParams = lp
                        }
                    }
                } catch (_: Exception) {}
            }
            var downRawX = 0f
            var downRawY = 0f
            var downLeft = 0
            var downTop = 0
            var moved = false
            val thresh = dp(activity, 8).toFloat()
            btn.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        downLeft = v.left
                        downTop = v.top
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (!moved && (kotlin.math.abs(dx) > thresh || kotlin.math.abs(dy) > thresh)) moved = true
                        if (moved) {
                            val parent = v.parent as? View
                            val pw = parent?.width ?: 0
                            val ph = parent?.height ?: 0
                            var nl = (downLeft + dx).toInt()
                            var nt = (downTop + dy).toInt()
                            if (pw > 0) nl = nl.coerceIn(0, (pw - size).coerceAtLeast(0))
                            if (ph > 0) {
                                val insets = v.rootWindowInsets
                                val topGuard = insets?.systemWindowInsetTop ?: 0
                                val botGuard = insets?.systemWindowInsetBottom ?: 0
                                val maxTop = (ph - botGuard - size).coerceAtLeast(topGuard)
                                nt = nt.coerceIn(topGuard, maxTop)
                            }
                            val lp = v.layoutParams as FrameLayout.LayoutParams
                            lp.gravity = Gravity.TOP or Gravity.START
                            lp.leftMargin = nl
                            lp.topMargin = nt
                            lp.bottomMargin = 0
                            lp.rightMargin = 0
                            v.layoutParams = lp
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            showHelp(activity, key)
                        } else {
                            try {
                                prefs.edit().putInt("left", v.left).putInt("top", v.top).apply()
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        } catch (_: Exception) {}
    }
}
