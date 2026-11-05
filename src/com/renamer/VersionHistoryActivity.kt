package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/**
 * Version history (2.22): opened by tapping the
 * version label on Home. Shows the LATEST version's
 * changes first; a glass button opens a version
 * picker (a beautiful bottom sheet listing the
 * versions), and picking one closes the sheet and
 * shows ONLY that version's changes, numbered.
 *
 * Only the last 10 versions are kept: when a new
 * version is added at the top, drop the oldest one
 * from VERSIONS so the list stays at 10.
 */
class VersionHistoryActivity : Activity() {

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private data class VersionEntry(
        val version: String,
        val code: Int,
        val highlight: String,
        val changesFa: List<String>,
        val changesEn: List<String>
    )

    // Newest first. Keep at most 10 entries.
    private val VERSIONS = listOf(
        VersionEntry("2.31", 146,
            "دکمه شناور سر جای درست و روش ارسال مخفی",
            listOf(
                "جای پیش‌فرض دکمه شناور راهنما شد وسط سمت راست صفحه، نه زیر دکمه‌های گوشی؛ جابه‌جایی با انگشت هم دیگر زیر دکمه‌های گوشی نمی‌رود",
                "گزینه مخفی روش ارسال با صفحه مدرن: روی صفحه پیام بیگانه یک دایره با انگشت بکش و وسطش را بزن تا صفحه زیبای انتخاب روش باز شود؛ فقط انتخاب است، هیچ نشانی‌ای نشان داده نمی‌شود",
                "روش‌ها: خودکار (هر دو روش، مثل قبل)، روش اول، یا روش پشتیبان — فقط برای فرستادن؛ خواندن پیام همیشه از هر دو روش انجام می‌شود"
            ),
            listOf(
                "Floating help button default spot is now the middle-right of the page, not under the phone nav buttons; dragging it can no longer drop it under them either",
                "Hidden send-method option with a modern picker page: draw a circle with your finger on the Alien Message hub page and tap its middle to open the beautiful method picker; it is only a choice, no address is ever shown",
                "Methods: Automatic (both methods, as before), First method, or Backup method — for sending only; reading always tries both methods"
            )),
        VersionEntry("2.30", 145,
            "امکانات نسخه مستقل در زبان بیگانه",
            listOf(
                "دکمه شناور راهنما دقیقاً به شکل نسخه مستقل: گرد، کوچک، کم‌رنگ، قابل جابه‌جایی، با راهنمای مخصوص هر صفحه",
                "دکمه درباره (✦) در ردیف بالای صفحه تبدیل، کنار دکمه راهنما: همه امکانات، روش کار و حریم خصوصی",
                "لینک‌های آینه‌ای: هر لینک روی دو هاست ساخته می‌شود؛ اگر یکی برایت باز نشد، از دیگری خوانده می‌شود",
                "در مسنجر: تیک ارسال و خوانده شدن داخل حباب پیام، ویرایش و حذف پیام خودم، پاسخ با کشیدن به چپ، و صف آفلاین",
                "پیش‌نمایش گفتگو در فهرست، نقل پیام پاسخ را تمیز نشان می‌دهد"
            ),
            listOf(
                "Floating help button exactly like the standalone app: round, small, muted, draggable, with per-page help",
                "About button (✦) in the converter top row, next to the guide button: all features, how it works, and privacy",
                "Mirror links: every link is created on two hosts; if one does not open for you, it is read from the other",
                "In the messenger: sent and read ticks inside the bubble, edit and delete my own messages, swipe-left reply, and an offline queue",
                "Conversation list previews show a reply quote cleanly"
            )),
        VersionEntry("2.29", 144,
            "اصلاحات مسنجر و سابقه لینک‌ها",
            listOf(
                "صفحه‌ها دیگر به ساعت گوشی نمی‌چسبند و دکمه‌ها زیر دکمه‌های گوشی نمی‌روند",
                "در راه‌اندازی گفتگو فقط یک لینک دیده می‌شود: همان که باید به دوستت بدهی؛ لینک مدیریت هیچ‌جا نشان داده نمی‌شود",
                "در بخش فرستنده هم لینک مدیریت حذف شد و فقط «لینک پیام شما برای ارسال به دیگران» دیده می‌شود",
                "آیکون سابقه بالای صفحه فرستنده: لینک‌ها شماره‌دار و تاریخ‌دار، با امکان اسم گذاشتن روی هر کدام",
                "دکمه ساخت لینک تازه در صفحه فرستنده؛ لینک قبلی در سابقه می‌ماند و با زدن روی آن دوباره فعال می‌شود"
            ),
            listOf(
                "Pages no longer stick to the phone's clock or slide under its nav buttons",
                "Conversation setup shows only one link: the one to give your friend; the manage link is never shown anywhere",
                "The sender page also hides the manage link and shows only “your message link to send to others”",
                "History icon at the top of the sender page: links numbered and dated, each renameable",
                "Fresh-link button on the sender page; the old link stays in history and reactivates on tap"
            )),
        VersionEntry("2.28", 143,
            "مسنجر بیگانه حرفه‌ای",
            listOf(
                "سابقه گفتگو بالای صفحه مسنجر با اسم شخص مقابل؛ نگه داشتن روی هر گفتگو: انتقال به بالا یا حذف",
                "دکمه گفتگوی جدید با لینک تازه برای هر شخص؛ لینک‌های هر گفتگو در برنامه ذخیره می‌شوند",
                "دکمه لینک خودت: لینک مدیریت بخش فرستنده را می‌آورد و باکس «این لینک را به دوستت بده» زیرش باز می‌شود",
                "شروع گفتگو اسم تو را می‌پرسد؛ دوستت همان اسم را می‌بیند و تو اسم او را",
                "سربرگ ثابت شیشه‌ای و کوتاه: عنوان کوچک سمت راست، آیکون کوچک وسط، اسم دوست سمت چپ، سه‌نقطه سمت چپ",
                "کادر نوشتن همیشه پایین صفحه ثابت است و دکمه ارسال یک دایره کوچک هم‌ارتفاع آن است",
                "پیام‌های خودت سبز کمرنگ با نوشته مشکی",
                "سه‌نقطه: پاک کردن همه پیام‌ها از هر دو طرف، و مسدود کردن؛ شخص مسدودشده می‌فهمد که مسدود شده است"
            ),
            listOf(
                "Conversation history at the top of Messenger with each counterpart's name; long-press: move to top or delete",
                "New conversation button with a fresh link per person; each conversation's links stay saved in the app",
                "“My link” button brings the sender section's manage link, with a “give this link to your friend” box below it",
                "Starting asks for your name; your friend sees that name, and you see theirs",
                "Fixed short glass header: small title right, small icon centre, friend's name left, menu at the far left",
                "Writing box always fixed at the bottom, with a small circle send button of the same height",
                "Your own messages in pale green with black text",
                "Menu: delete all messages both ways, and block; the blocked person learns they are blocked"
            )),
        VersionEntry("2.27", 142,
            "پیام بیگانه و مسنجر بیگانه",
            listOf(
                "با زدن روی آیکون بخش، صفحه پیام بیگانه باز می‌شود: فرستنده‌ام یا گیرنده‌ام",
                "فرستنده یک لینک پیام می‌سازد و با دکمه کنار خروجی متن را به لینک می‌فرستد",
                "گیرنده فقط لینک را (با شکل عجیب و غریب) وارد می‌کند؛ تازه‌سازی و بررسی خودکار با اعلان و صدا دارد",
                "مسنجر بیگانه: هر دو لینکشان را به هم می‌دهند و پیام‌ها هر ۱۰ ثانیه فقط تا وقتی در مسنجر هستی تازه می‌شوند",
                "ویجت تازه پیام بیگانه: آخرین پیام دریافتی روی صفحه اصلی",
                "آدرس سایت لینک‌ها هیچ‌وقت نشان داده نمی‌شود؛ همه لینک‌ها فقط با شکل عجیب و غریب دیده می‌شوند"
            ),
            listOf(
                "Tapping the section icon opens the Alien Message page: sender or receiver",
                "The sender creates one message link and sends text to it with the button next to the output",
                "The receiver only enters the link (in its strange form); refresh and auto-check with notification and sound",
                "Alien Messenger: both exchange their links and messages refresh every 10 seconds only while you are in the messenger",
                "New Alien Message widget: the latest received message on the home screen",
                "The links' site address is never shown; every link is only ever seen in its strange form"
            )),
        VersionEntry("2.26", 141,
            "زبان بیگانه: آیکون کهکشانی، خروجی کوتاه‌تر، فایل و انتقال کانفیگ",
            listOf(
                "آیکون بخش، تصویر کهکشانی شد و در بالای صفحه با اندازه هیدرا می‌آید",
                "خروجی کوتاه‌تر شد: عبارت‌های پرتکرار، اعداد دورقمی و واژه‌های بیشتر هر کدام یک نماد می‌شوند؛ متن‌های قدیمی همچنان باز می‌شوند",
                "دکمه سبز دیگر خط سفید ندارد؛ همه دکمه‌ها کوچک‌تر، مینیمال و شیشه‌ای و هماهنگ شدند",
                "باکس‌های ورودی و خروجی جمع‌وجورتر شدند و نهایت ۵ خط نشان می‌دهند با اسکرول داخلی",
                "دکمه نمونه حذف شد؛ ردیف وسط حالا پاک کردن، جابه‌جا و فایل است",
                "فایل متنی را می‌شود به ورودی داد و خروجی را در یک فایل ذخیره کرد؛ فایل غیرمتنی رد می‌شود تا رمزگشایی خراب نشود",
                "اگر خروجی یک کانفیگ باشد، پیام سبز نوعش را می‌گوید و دکمه انتقال آن را مستقیم به v2ray یا وایرگارد می‌برد؛ آن کانفیگ با رنگ کهکشانی و آیکون بیگانه مشخص می‌شود"
            ),
            listOf(
                "The section icon is now the galaxy image, shown at the top in Hydra's size",
                "Shorter output: frequent phrases, two-digit numbers and more words each become one symbol; old alien texts still decode",
                "The green button lost its white stripe; all buttons are smaller, minimal, glassy and harmonious",
                "Input and output boxes are more compact and show at most 5 lines with inner scrolling",
                "The example button is gone; the middle row is now Clear, Swap and File",
                "A text file can be fed to the input and the output saved to a file; non-text files are refused so decoding never breaks",
                "If the output is a config, a green note names its kind and the transfer button moves it straight to v2ray or WireGuard; there it is marked with a galaxy colour and the alien icon"
            )),
        VersionEntry("2.25", 140,
            "بخش جدید: زبان بیگانه",
            listOf(
                "بخش تازه «زبان بیگانه» به صفحه اصلی اضافه شد: متن را به زبانی ناشناخته تبدیل می‌کند و گیرنده با همین بخش پیام اصلی را پس می‌گیرد",
                "کلمات فارسی و انگلیسی، اعداد، همه علامت‌های کیبورد و کانفیگ v2ray کامل و سالم برمی‌گردند",
                "متن‌های روزمره کوتاه‌تر از ورودی می‌شوند؛ مثلاً «سلام خوبی» فقط ۳ نماد می‌شود",
                "دکمه‌های تبدیل دوطرفه، جابه‌جایی جهت، چسباندن، نمونه و کپی خروجی",
                "اسم و آیکون این بخش موقت است و بعداً عوض می‌شود"
            ),
            listOf(
                "New 'Alien Language' section on Home: turns text into a strange unknown language, and the receiver gets the original message back with this same section",
                "Persian and English words, numbers, every keyboard symbol and v2ray configs come back complete and intact",
                "Everyday text comes out shorter than the input; e.g. 'salam khubi' becomes just 3 symbols",
                "Two-way conversion, direction swap, paste, example and copy-output buttons",
                "This section's name and icon are temporary and will change later"
            )),
        VersionEntry("2.24", 139,
            "دایره‌های اوپن‌وی‌پی‌ان دقیق‌تر و راهنمای بخش",
            listOf(
                "دایره بالایی حالا همه سرورهای پروفایل را تست می‌کند و مثل تست پینگ v2ray، قبل از اتصال می‌گوید با همین نت وصل می‌شود یا نه",
                "تشخیص بسته بودن پورت UDP و تست زنده بودن خط اضافه شد تا دایره‌ها با تنظیم قدیمی گیر نکنند",
                "با لمس دایره‌ها دلیل هر رنگ با زبان ساده گفته می‌شود",
                "دکمه «؟» کنار لاگ، راهنمای همه گزینه‌های بخش اوپن‌وی‌پی‌ان را باز می‌کند",
                "دایره‌ها بعد از آزاد شدن خط هم از نو تست می‌شوند، نه فقط در ورود به صفحه"
            ),
            listOf(
                "The upper dot now tests every server of the profile and, like the v2ray ping test, says before connecting whether it will connect on this line",
                "UDP port-closed detection and a live line check were added so the dots never stick on a stale setting",
                "Tapping the dots explains each colour in plain words",
                "The '?' button beside the log opens the guide to every OpenVPN section option",
                "Dots re-test whenever the line becomes idle, not only on page entry"
            )),
        VersionEntry("2.23", 138,
            "دو دایره سلامت اوپن‌وی‌پی‌ان و زنجیره چندبخشی موتور",
            listOf(
                "هر کانفیگ اوپن‌وی‌پی‌ان دو دایره سلامت دارد: بالایی تست مستقیم با نت خودت، پایینی سلامت سرور از خارج ایران",
                "دایره پایینی با تونل سالم خودت تست می‌شود، نه با نت ایران؛ اگر تونلی نباشد خاکستری می‌ماند، نه قرمز",
                "موتور در آخرین تلاش از کانفیگ‌های سالم v2ray، وایرگارد و اضطراری زنجیره می‌سازد",
                "دکمه بازگشت از صفحه تاریخچه حذف شد و هیچ بخشی پشت دکمه‌های گوشی نمی‌ماند"
            ),
            listOf(
                "Every OpenVPN config has two health dots: upper = direct test on your own line, lower = server health from outside Iran",
                "The lower dot is tested through your own healthy tunnel, not the Iranian line; grey when no tunnel exists, never red",
                "The engine's last resort chains through healthy v2ray, WireGuard and Emergency configs",
                "Back button removed from the history page; no section sits behind the phone buttons"
            )),
        VersionEntry("2.22", 137,
            "تشخیص سلامت سرور و تاریخچه نسخه‌ها",
            listOf(
                "گزارش موتور حالا می‌گوید سرور زنده است یا از این خط مسدود است",
                "زنجیره موتور تا دو گزینه زنجیره اول را امتحان می‌کند",
                "خاموش کردن موتور، زنجیره‌های موتور را هم متوقف می‌کند",
                "نوار پایین صفحه اصلی یکدست شد و دکمه قفل و خروج بازطراحی شد",
                "با زدن روی نسخه در صفحه اصلی، تاریخچه تغییرات باز می‌شود",
                "صفحه اشتراک‌ها با کانفیگ‌های زیاد سریع باز می‌شود",
                "آیکون‌های راهنما یکدست و مرتب شدند",
                "انتخاب زنجیره اول: پینگ زنده هر کانفیگ v2ray و تیک سلامت هر زنجیره",
                "لاگ زنجیره دقیق‌تر شد: شروع، راه‌اندازی و علت هر خطا"
            ),
            listOf(
                "Engine report now says whether the server is alive or blocked on this line",
                "Engine chaining tries up to two hop-1 candidates",
                "Turning the engine off also stops engine chains",
                "Home bottom bar is uniform and the lock & exit button is redesigned",
                "Tapping the version on Home opens the change history",
                "Subscriptions page opens fast with many configs",
                "Guide icons are uniformly aligned",
                "Chain hop-1 picker: live v2ray pings and a health tick per hop",
                "Chain log is more precise: start, launch and every failure reason"
            )),
    )

    private lateinit var contentBox: LinearLayout
    private lateinit var pickerBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = L("تاریخچه ورژن‌ها", "Version history")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = L(
                "تغییرات هر ورژن، مختصر و شماره‌دار. اول تغییرات آخرین ورژن را می‌بینی؛ با دکمه پایین ورژن دیگری را انتخاب کن.",
                "Each version's changes, short and numbered. You see the latest version first; pick another one with the button below.")
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, dp(16))
        })
        pickerBtn = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.parseColor("#1B5E20"))
            background = HydraUi.glassPill(
                Color.parseColor("#F1F8E9"), Color.parseColor("#81C784"), 24f, this@VersionHistoryActivity)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            setOnClickListener { showVersionPicker() }
        }
        root.addView(pickerBtn)
        contentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contentBox)
        // 2.23: no Back button on this page (user
        // order); the system back gesture/button
        // still leaves it. fitsSystemWindows keeps
        // the bottom content above the phone's
        // navigation buttons — this page was the
        // one full page missing it.
        scroll.fitsSystemWindows = true
        setContentView(scroll)
        // Before any pick: the latest version's info.
        render(VERSIONS.first())
    }

    private fun faNum(n: Int): String {
        val map = mapOf('0' to '۰', '1' to '۱', '2' to '۲', '3' to '۳', '4' to '۴',
            '5' to '۵', '6' to '۶', '7' to '۷', '8' to '۸', '9' to '۹')
        return n.toString().map { map[it] ?: it }.joinToString("")
    }

    private fun render(v: VersionEntry) {
        pickerBtn.text = L("انتخاب ورژن ▾  (الان: ${v.version})", "Pick a version ▾  (now: ${v.version})")
        contentBox.removeAllViews()
        contentBox.addView(TextView(this).apply {
            text = L("ورژن ${v.version}", "Version ${v.version}")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1B365D"))
        })
        contentBox.addView(TextView(this).apply {
            text = L("کد ${faNum(v.code)}  •  ${v.highlight}", "Code ${v.code}  •  ${v.highlight}")
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(14))
        })
        val changes = if (AppLanguage.isEnglish(this)) v.changesEn else v.changesFa
        for ((i, c) in changes.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                background = HydraUi.glassBg(
                    Color.parseColor("#F7F8FA"), Color.parseColor("#E0E0E0"), 16f, this@VersionHistoryActivity)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            row.addView(TextView(this).apply {
                text = faNum(i + 1) + "."
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E7D32"))
                setPadding(0, 0, dp(10), 0)
            })
            row.addView(TextView(this).apply {
                text = c
                textSize = 14f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            contentBox.addView(row)
        }
    }

    /** Bottom sheet listing the versions, newest
     *  first. Tapping one closes the sheet and shows
     *  only that version's changes on the page. */
    private fun showVersionPicker() {
        val dlg = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = L("انتخاب ورژن", "Pick a version")
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        for (v in VERSIONS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            row.addView(TextView(this).apply {
                text = L("ورژن ${v.version}", "Version ${v.version}")
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = v.highlight
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            row.setOnClickListener {
                dlg.dismiss()
                render(v)
            }
            card.addView(row)
            card.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#EEEEEE"))
            })
        }
        dlg.setContentView(card)
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val lp = win.attributes
            lp.gravity = Gravity.BOTTOM
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp
        }
        dlg.show()
    }
}
