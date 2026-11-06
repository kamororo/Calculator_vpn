package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/**
 * One section's full guide, as its own glass page
 * (not a small dialog), so long explanations are
 * fully readable and scrollable.
 *
 * RULES the user set for this page:
 * - In public mode, the Rename and Subscriptions
 *   sections are not listed at all, and NO text
 *   anywhere hints that another (private) section
 *   exists. The entry-password text never mentions
 *   a private password.
 * - The Emergency section explains its full VISIBLE
 *   behaviour (fresh fetch, ping test, best server,
 *   nothing saved, generic name, retry, ping line).
 *   It NEVER reveals the hidden settings gesture,
 *   its password, or any subscription link: those
 *   stay hidden by design, even in this guide.
 * - Everything else is explained completely,
 *   especially features this app ADDED that the
 *   original apps do not have (flag circle, FAB
 *   long-press modes, smart connect, widgets,
 *   notification, drag reorder, login themes).
 */
class GuideDetailActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class Block(val headFa: String, val headEn: String, val bodyFa: String, val bodyEn: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("section") ?: "v2ray"
        val (titleFa, titleEn, blocks) = content(key)
        val dark = Theme.isDark(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(if (dark) Color.parseColor("#121212") else Color.WHITE)
            fitsSystemWindows = true
        }
        scroll.addView(root)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = GradientDrawable()
            d.shape = GradientDrawable.RECTANGLE
            d.cornerRadius = dp(24).toFloat()
            d.setColor(if (dark) Color.parseColor("#CC1E1E1E") else Color.parseColor("#E8FFFFFF"))
            d.setStroke(dp(1), if (dark) Color.parseColor("#55FFFFFF") else Color.parseColor("#88CCCCCC"))
            background = d
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        panel.addView(TextView(this).apply {
            text = L(titleFa, titleEn); textSize = 19f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            setPadding(0, 0, 0, dp(12))
        })
        for (b in blocks) {
            panel.addView(TextView(this).apply {
                text = L(b.headFa, b.headEn); textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(if (dark) Color.parseColor("#81C784") else Color.parseColor("#0B6E4F"))
                setPadding(0, dp(10), 0, dp(2))
            })
            panel.addView(TextView(this).apply {
                text = L(b.bodyFa, b.bodyEn); textSize = 14f
                setTextColor(if (dark) Color.parseColor("#EEEEEE") else Color.parseColor("#333333"))
                setPadding(0, 0, 0, dp(6))
            })
        }
        panel.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            textSize = 13f
            isAllCaps = false
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000"))
                setStroke(1, if (dark) Color.parseColor("#55FFFFFF") else Color.parseColor("#22000000"))
            }
            setPadding(dp(24), dp(10), dp(24), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
            setOnClickListener { finish() }
        })
        root.addView(panel)
        setContentView(scroll)
        try {
                    } catch (_: Exception) {}
    }

    private fun content(key: String): Triple<String, String, List<Block>> {
        return when (key) {
            "home" -> Triple("صفحه اصلی", "Home page", listOf(
                Block("صفحه اصلی چیست", "What the home page is",
                    "صفحه‌ای که بعد از ورود می‌بینی: سه کاشی در هر ردیف، هر کاشی آیکون واقعی یک بخش با اسم دقیق خودش است. با زدن هر کاشی همان بخش باز می‌شود. بالای صفحه نه تیتر است و نه خط حالت؛ صفحه مستقیم با کاشی‌ها شروع می‌شود.",
                    "The page you see after entry: three tiles per row, each tile the section's real icon with its exact name. Tapping a tile opens that section. There is no title or mode line at the top; the page starts straight with the tiles."),
                Block("دکمه‌های بالای صفحه، سمت راست", "Top-row buttons, right side",
                    "از راست: چرخ‌دنده تنظیمات صفحه اصلی را باز می‌کند؛ FA/EN زبان برنامه را عوض می‌کند (فارسی یا انگلیسی)؛ ☾/☀ حالت روز و شب را عوض می‌کند. سمت چپ همین خط، همیشه نسخه برنامه با خاکستری کمرنگ نوشته شده است.",
                    "From the right: the gear opens the home page settings; FA/EN switches the app language (Persian or English); ☾/☀ switches day and night mode. On the left of the same row, the app version is always written in light grey."),
                Block("موتور هوشمند روی صفحه اصلی — قابلیتی که این برنامه اضافه کرده", "The Smart Engine on the home page — added by this app",
                    "دکمه ✦ و علامت ! کنارش، با هم یک قرص‌اند تا معلوم باشد ! مال موتور است. ✦ موتور را روشن و خاموش می‌کند: روشن یعنی سبز پررنگ، خاموش یعنی شیشه‌ای. ! توضیح کامل موتور را نشان می‌دهد. وقتی موتور خاموش است، هیچ دکمه و هیچ فعالیتی از موتور در هیچ صفحه‌ای دیده نمی‌شود و برنامه دقیقاً مثل قبل از اضافه شدن موتور کار می‌کند. وقتی از روشن به خاموش برمی‌گردی، همه‌چیز به حالت قبل برمی‌گردد: حافظه یادگیری و گزارش‌ها پاک می‌شوند و اگر اتصالی با موتور برقرار بود قطع می‌شود.",
                    "The ✦ button and the ! next to it form one pill, so it is clear the ! belongs to the engine. ✦ switches the engine on and off: on is solid green, off is glass. ! shows the engine's full explanation. When the engine is off, no engine button or activity appears on any page, and the app works exactly as before the engine was added. When you switch it from on to off, everything returns to its earlier state: learned memory and reports are wiped, and any live engine session is stopped."),
                Block("قفل و خروج", "Lock & exit",
                    "دکمه قرصی پایین صفحه، صفحه اصلی را می‌بندد و به صفحه ورود (ماشین‌حساب) برمی‌گردی.",
                    "The pill button at the bottom closes the home page and returns you to the entry (calculator) page."),
                Block("ترتیب کاشی‌ها", "Tile order",
                    "ترتیب کاشی‌ها را از تنظیمات صفحه اصلی ← مرتب‌سازی برنامه‌ها عوض می‌کنی: روی آیکون نگه دار، بکش، ذخیره کن.",
                    "You change the tile order from Home settings → Sort apps: hold an icon, drag it, save.")
            ))
            "v2ray" -> Triple("v2ray", "v2ray", listOf(
                Block("این بخش چیست", "What this section is",
                    "قلب برنامه. سرورهایت را اینجا می‌بینی، اضافه می‌کنی، تست می‌کنی و وصل می‌شوی.",
                    "The heart of the app. Your servers live here: add, test and connect."),
                Block("اضافه کردن کانفیگ", "Adding configs",
                    "با دکمه + می‌توانی سرور را با QR، از حافظه (کلیپ‌بورد)، از فایل یا به‌صورت دستی اضافه کنی. با سه‌نقطه هم می‌توانی سرور را ویرایش یا حذف کنی و اشتراک (سابسکریبشن) وارد کنی. کانفیگ‌های گروهی (GROUP::) و زنجیره‌ای (CHAIN::) هم پشتیبانی می‌شوند.",
                    "With + you add a server by QR, from clipboard, from a file, or manually. The ⋮ menu edits, deletes and imports subscriptions. GROUP:: and CHAIN:: entries are supported."),
                Block("دکمه اتصال و نگه داشتن آن — قابلیتی که این برنامه اضافه کرده", "The connect button and long-pressing it — added by this app",
                    "دکمه اتصال پایین صفحه دو کار می‌کند. یک ضربه ساده، وصل یا قطع می‌کند. اگر دکمه را حدود دو ثانیه نگه داری، یک انتخاب باز می‌شود: «نرمال» یعنی وصل شدن به همان کانفیگی که انتخاب کرده‌ای؛ «بهترین پینگ» یعنی برنامه خودش همه کانفیگ‌ها را تست پینگ می‌گیرد و به بهترین وصل می‌شود. کنار هر گزینه یک دکمه ! هم هست که همین را توضیح می‌دهد. تا وقتی یک بار این انتخاب را نکرده باشی، کنار دکمه یک راهنما نوشته می‌شود: «برای تنظیم نوع اتصال دکمه اتصال را نگه دارید».",
                    "The connect button does two things. A simple tap connects or disconnects. Holding it for about two seconds opens a choice: Normal connects to the config you selected; Best ping makes the app ping-test all configs itself and connect to the best one. A ! button beside each choice explains it. Until you choose once, a hint next to the button says to hold it to set the connect type."),
                Block("تست پینگ", "Ping test",
                    "تست پینگ دقیقاً به روش v2rayNG انجام می‌شود: اول بررسی TCP، بعد تست واقعی با هسته Xray. عددی که می‌بینی، پینگ واقعی همان کانفیگ است.",
                    "Ping testing follows the v2rayNG method exactly: a TCP check first, then a real test with the Xray core."),
                Block("دایره پرچم جلوی هر کانفیگ — قابلیتی که این برنامه اضافه کرده", "The flag circle in front of each config — added by this app",
                    "جلوی هر کانفیگ یک دایره کوچک هست که پرچم کشوری را نشان می‌دهد که آی‌پی آن کانفیگ متعلق به آن است. اگر روی دایره پرچم بزنی، سه ثانیه روی صفحه نوشته می‌شود که این کانفیگ برای کدام کشور و شهر است. برای اینکه دقیق‌تر باشد، آی‌پی نسخه ۴ ترجیح داده می‌شود و از دو منبع موقعیت‌یابی استفاده می‌شود. و برای کانفیگی که به آن وصلی، کشور از داخل خود تونل اندازه گرفته می‌شود (نه از روی هاست)، چون هاست یک دامنه ممکن است فقط به لبه CDN اشاره کند، نه به کشور واقعی سرور.",
                    "In front of each config there is a small circle showing the flag of the country that config's IP belongs to. Tapping it shows, for three seconds, which country and city that config is for. IPv4 is preferred and two location sources are used for accuracy. And for the config you are connected to, the country is measured from inside the tunnel itself (not from the host), because a domain's host may only point at a CDN edge, not the server's real country."),
                Block("زبانه‌های سابسکریبشن و صفحه تنظیمات ساب — قابلیتی که این برنامه اضافه کرده", "Subscription tabs and the subscription settings page — added by this app",
                    "بالای لیست کانفیگ‌ها یک نوار زبانه هست: «تمام کانفیگ‌ها» اول و پیش‌فرض است، و کنارش برای هر ساب یک زبانه با نام و تعداد کانفیگ‌هایش هست. وقتی روی زبانه یک ساب می‌روی، فقط کانفیگ‌های همان ساب نشان داده می‌شوند. از منوی کشویی (☰) گزینه «تنظیمات گروه سابسکریبشن» یک صفحه کامل باز می‌کند: هر ساب نام، لینک، تاریخ آخرین به‌روزرسانی، دکمه اشتراک‌گذاری، ویرایش و حذف، و یک سوییچ فعال/غیرفعال دارد. در صفحه ویرایش هر ساب می‌توانی نام، لینک، User Agent، هدرهای درخواست (JSON)، فیلتر نام (regex که موقع دریافت اعمال می‌شود)، به‌روزرسانی خودکار با فاصله حداقل ۱۵ دقیقه، اجازه آدرس HTTP ناامن، و پروکسی قبلی و بعدی را تنظیم کنی؛ موقع اتصال، ترتیب زنجیره دستگاه ← پروکسی قبلی ← کانفیگ ← پروکسی بعدی ← مقصد است. حذف یک ساب فقط همان ساب را پاک می‌کند، نه کانفیگ‌هایش را.",
                    "Above the config list there is a tab bar: “All configs” is first and default, and next to it each subscription gets a tab with its name and config count. Tapping a subscription tab shows only that subscription's configs. The drawer (☰) entry “Subscription group setting” opens a full page: each sub has a name, link, last-update date, share, edit and delete buttons, and an enable switch. In a sub's edit page you can set its name, link, User Agent, request headers (JSON), a remarks filter (regex applied at import), automatic update with a minimum 15-minute interval, allow insecure HTTP address, and previous/next proxy profiles; at connect time the chain order is device -> previous proxy -> config -> next proxy -> destination. Deleting a sub removes only the sub, not its configs."),
                Block("کارت فشرده، کادر سبز انتخاب، کلیپ‌بورد اول لیست و کانفیگ کاستوم", "Compact cards, green selected border, clipboard to top, and custom configs",
                    "هر کانفیگ حالا یک کارت فشرده‌تر است با فاصله کمتر بین کارت‌ها، و وقتی یک کارت را انتخاب می‌کنی، کل دور کادرش سبز می‌شود. وارد کردن کانفیگ از کلیپ‌بورد، کانفیگ را اول لیست می‌گذارد، نه آخرش. کانفیگ‌های کاستوم (یک JSON کامل Xray، نه یک لینک) دیگر به‌اشتباه vmess نشان داده نمی‌شوند؛ با برچسب Custom می‌آیند، نامشان از فیلد remarks همان JSON است، و با همان JSON خودشان وصل می‌شوند. گروه‌های سیاستی هم حالا قابل اتصال‌اند: با انتخاب یک گروه و وصل شدن، ترافیک بین اعضای گروه با استراتژی leastPing یا leastLoad پخش می‌شود.",
                    "Each config is now a more compact card with smaller gaps between cards, and selecting a card turns its whole border green. Importing a config from the clipboard puts it at the top of the list, not the bottom. Custom configs (a full Xray JSON, not a link) are no longer mislabelled as vmess; they come with a Custom label, their name comes from the JSON's remarks field, and they connect with their own JSON. Policy groups are connectable now too: select a group and connect, and traffic is balanced across its members with the leastPing or leastLoad strategy."),
                Block("زنجیره قبلی و بعدی هر کانفیگ", "Previous/next chain of a config",
                    "اگر کانفیگی در تنظیمات سابش پروکسی قبلی و بعدی داشته باشد، وصل شدن به آن کانفیگ به‌صورت زنجیره انجام می‌شود: دستگاه ← پروکسی قبلی ← کانفیگ ← پروکسی بعدی ← مقصد. این همان مکانیزمی است که موتور هوشمند برای «پرش» هم از آن استفاده می‌کند.",
                    "If a config's subscription settings define previous and next proxies, connecting to it becomes a chain: device -> previous proxy -> config -> next proxy -> destination. This is the same mechanism the Smart Engine uses for its “jump”."),
                Block("موتور هوشمند و تعمیر دسته‌ای — قابلیتی که این برنامه اضافه کرده", "Smart Engine and batch repair — added by this app",
                    "وقتی موتور از صفحه اصلی روشن باشد، وصل شدن به یک کانفیگ خراب اول با موتور بررسی می‌شود: گزینه‌ها موازی و در چند موج سنجیده می‌شوند (پرش از کانفیگ سالم، فرگمنت، اثر انگشت، SNI، شکل‌های بسته‌ای، IP و پورت جایگزین، ترنسپورت) و نسخه‌ای که واقعاً جواب بدهد وصل می‌شود؛ اگر هیچ‌کدام نشد، همان اتصال عادی انجام می‌شود. دکمه «گزارش موتور» بالای صفحه، قدم‌به‌قدم می‌نویسد چه امتحان شد و چه شد و گزارش قابل کپی است. دکمه «تعمیر دسته‌ای» هم کانفیگ‌های خراب (آن‌هایی که تست پینگشان منفی شده) را یکی‌یکی با موتور بررسی می‌کند و نسخه‌های تعمیرشده را فقط با تأیید تو، به‌عنوان کانفیگ جدید ذخیره می‌کند؛ کانفیگ‌های اصلی دست نمی‌خورند.",
                    "When the engine is switched on from the home page, connecting to a dead config is first checked by the engine: candidates are raced in parallel waves (jump via a healthy config, fragment, fingerprint, SNI, packet shapes, alternate IP and port, transport) and the variant that truly answers is connected; if none does, the normal connection follows. The “Engine report” button at the top writes step by step what was tried and what happened, and the report is copyable. The “Batch repair” button checks the dead configs (those whose ping test failed, including custom JSON configs) one by one with the engine and saves the repaired copies only with your confirmation, as NEW configs; the originals are never touched."),
                Block("حالت پروکسی و اینکه کدام برنامه‌ها از فیلترشکن رد شوند", "Proxy-only mode and which apps go through the VPN",
                    "در تنظیمات اتصال، حالت «فقط پروکسی» هسته را بدون تونل VPN اجرا می‌کند، پس مجوز VPN هم لازم ندارد. همان‌جا می‌توانی انتخاب کنی همه برنامه‌ها از فیلترشکن رد شوند، فقط چند برنامه رد شوند، یا چند برنامه از آن مستثنا باشند؛ خود برنامه همیشه مستثناست.",
                    "In the connection settings, “Proxy only” mode runs the core without a VPN tunnel, so no VPN permission is needed. There you can also choose whether all apps go through the VPN, only a few apps do, or a few apps are excluded; this app itself is always excluded."),
                Block("تنظیمات (فقط یک بخش)", "Settings (a single section)",
                    "از منوی کشویی (☰) فقط یک «تنظیمات» هست که همه چیز را یکجا دارد: انتخاب زبان برنامه، تنظیمات اتصال (MTU، DNS، پورت SOCKS) و تنظیمات کامل v2ray شامل DNS، Mux، Fragment، Sniffing، پروکسی محلی، Bypass LAN، سطح لاگ و آدرس تست پینگ. این تنظیم‌ها روی اتصال بعدی واقعاً اعمال می‌شوند. بعضی گزینه‌ها فقط ذخیره می‌شوند و در صفحه خودشان هم همین نوشته شده است.",
                    "The drawer (☰) has a single Settings section with everything together: app language, connection settings (MTU, DNS, SOCKS port) and full v2ray settings including DNS, Mux, Fragment, Sniffing, local proxy, Bypass LAN, log level and the delay-test URL. They really apply to the next connection. A few options are stored only, and the page says so itself.")
            ))
            "engine" -> Triple("موتور هوشمند", "Smart Engine", listOf(
                Block("موتور هوشمند چیست", "What the Smart Engine is",
                    "یک لایه کمکی است، نه یک وی‌پی‌ان جدا و نه یک بخش تازه. وقتی از صفحه اصلی (دکمه ✦) روشن باشد، در همه بخش‌های اتصال — v2ray، وایرگارد و امنزیا، OpenVPN، هیدرا، اتصال هوشمند و اتصال اضطراری — اول موتور امتحان می‌شود و اگر نتوانست، همان اتصال عادی همیشگی انجام می‌شود. روشن بودن موتور هیچ‌وقت اتصالی را که قبلاً کار می‌کرد خراب نمی‌کند. وقتی خاموش است، هیچ دکمه و هیچ فعالیتی از موتور در هیچ صفحه‌ای دیده نمی‌شود.",
                    "A helper layer, not a separate VPN and not a new section. When switched on from the home page (the ✦ button), it is tried FIRST in every connection section — v2ray, WireGuard & Amnezia, OpenVPN, Hydra, Smart Connect and Emergency connect — and if it cannot help, the section's normal connection runs exactly as before. Turning it on can never break a connection that already worked. When it is off, no engine button or activity appears on any page."),
                Block("موج‌های موازی تعمیر", "Parallel repair waves",
                    "اول نسخه اصلی کانفیگ تست می‌شود؛ اگر سالم باشد بدون هیچ تغییری وصل می‌شود. اگر جواب نداد، گزینه‌ها موازی و در چند موج سنجیده می‌شوند، نه یکی‌یکی: موج اول پرش از یک کانفیگ سالم دیگر (همان کاری که بخش زنجیره دوتایی می‌کند)، فرگمنت کردن بسته‌های TLS، چرخش اثر انگشت مرورگر، درست کردن SNI و شکل‌های بسته‌ای هیدرا (دی‌سینک) است؛ موج دوم آدرس‌های IP جایگزین همان سرور، پورت‌های جایگزین، عوض کردن ترنسپورت و حذف flow است. هر گزینه با تست واقعی از داخل همان گزینه سنجیده می‌شود، نه با حدس. اگر هیچ‌کدام جواب نداد، موج چهارم ترکیب‌های دوتایی (فرگمنت + شکل بسته، فرگمنت + اثر انگشت، فرگمنت + SNI، فرگمنت + پرش، و ترکیب هرکدام با پورت جایگزین) را هم موازی تست می‌کند. کانفیگ‌های کاستوم (JSON کامل) هم با همین موتور بررسی می‌شوند و تغییر مستقیم روی همان JSON اعمال می‌شود؛ فقط پرش و عوض کردن ترنسپورت برای آن‌ها انجام نمی‌شود.",
                    "First the original config is tested; if it is healthy it connects with no change at all. If it does not respond, candidates are raced in parallel waves, not one by one: wave 1 is a jump via another healthy config (the same trick the Double Chain section uses), TLS packet fragmenting, browser fingerprint rotation, SNI repair and Hydra's packet shapes (desync); wave 2 is alternate IPs of the same server, alternate ports, transport swap and flow removal. Every candidate is measured with a real test through that candidate, not a guess. If none answers, wave 4 races two-way combinations (fragment + packet shape, fragment + fingerprint, fragment + SNI, fragment + jump, and each combined with an alternate port). Custom configs (full JSON) are checked with the same engine, editing that JSON directly; only jump and transport swap are skipped for them."),
                Block("اسکن CDN در پس‌زمینه", "Background CDN scan",
                    "اگر کانفیگی که جواب نمی‌دهد از نوع TLS روی WS یا gRPC یا HTTP باشد و SNI مشخص داشته باشد و آدرسش پشت یک CDN شناخته‌شده (کلادفلر، فستلی، آکامای یا بانی) باشد، موتور بعد از موج دوم، آی‌پی‌های نمونه از همان CDN را با همان SNI موازی تست می‌کند و اگر یکی سالم بود با همان وصل می‌شود؛ این موج هیچ بخش تازه‌ای در برنامه ندارد و با خاموش شدن موتور کاملاً پاک می‌شود. آی‌پی برنده CDN هرگز به‌صورت کلی یاد گرفته نمی‌شود.",
                    "If a non-responding config is TLS over WS, gRPC or HTTP with an explicit SNI and its address sits behind a known CDN (Cloudflare, Fastly, Akamai or Bunny), the engine races sampled edge IPs of that same CDN with the same SNI after wave 2, and connects with a healthy one; this wave adds no new section to the app and is fully wiped when the engine is switched off. A winning CDN IP is never learned globally."),
                Block("یادگیری: برنده هر کانفیگ و برنده هر شبکه", "Learning: each config's winner and each network's winner",
                    "برنده هر کانفیگ (مثلاً «فرگمنت») برای همان کانفیگ به خاطر سپرده می‌شود و برنده کلی هر شبکه (وای‌فای یا موبایل) هم جداگانه به خاطر سپرده می‌شود؛ دفعه بعد که کانفیگی روی همان شبکه جواب نداد، اول همان برنده‌ها امتحان می‌شوند. پس تعمیر تکراری معمولاً با یک تست انجام می‌شود. آدرس‌های IP چون مخصوص همان سرورند، هرگز به‌صورت کلی یاد گرفته نمی‌شوند.",
                    "Each config's winner (for example “fragment”) is remembered for that config, and each network's overall winner (Wi-Fi or mobile) is remembered separately; next time a config fails on the same network, those winners are tried first. So a repeat repair usually takes a single test. IP addresses are server-specific and are never learned globally."),
                Block("نگهبان: اگر اتصال وسط کار افتاد", "Watchdog: if a connection dies mid-use",
                    "فقط برای اتصال‌هایی که خود موتور شروع کرده است: هر ۳۰ ثانیه از داخل همان تونل تست می‌شود که ترافیک واقعاً رد می‌شود یا نه. اگر دو تست پشت سر هم جواب نداد، موتور همان‌جا با یک نسخه بهتر دوباره وصل می‌شود، بدون اینکه تو کاری بکنی؛ اگر سه بار تلاش هم نشد، اتصال قطع می‌شود تا ترافیکت در تونل مرده گیر نکند. نگهبان هرگز به فیلترشکن دستی تو دست نمی‌زند و مالکیت اتصال هوشمند را هم عوض نمی‌کند.",
                    "Only for sessions the engine itself started: every 30 seconds it tests from inside the tunnel itself whether traffic truly passes. If two tests in a row fail, the engine reconnects on the spot with a better variant, without you doing anything; if three tries fail, the session is stopped so your traffic is not stuck in a dead tunnel. The watchdog never touches your hand-started VPN and never changes Smart Connect's ownership."),
                Block("خاموش شدن یعنی بازگشت کامل", "Switching off means a full return",
                    "وقتی موتور را خاموش می‌کنی، همه‌چیز به حالت قبل از روشن شدن برمی‌گردد: حافظه یادگیری (هم هر کانفیگ و هم هر شبکه) و همه گزارش‌ها پاک می‌شوند، و اگر اتصالی با موتور برقرار بود — چه v2ray چه وایرگارد — قطع می‌شود تا با اتصال عادی جایگزین شود. کانفیگ‌های ذخیره‌شده‌ات هرگز دست نمی‌خورند؛ تنها استثنا تعمیر دسته‌ای است که با تأیید خودت نسخه جدید ذخیره می‌کند.",
                    "When you switch the engine off, everything returns to the state before it was switched on: learned memory (per config and per network) and all reports are wiped, and any live engine session — v2ray or WireGuard — is stopped so a normal connection can replace it. Your saved configs are never touched; the only exception is batch repair, which saves a new copy only with your confirmation."),
                Block("گزارش موتور در هر بخش", "Engine report in every section",
                    "وقتی موتور روشن است، در صفحه هر بخشی که موتور می‌تواند در آن کار کند یک دکمه کوچک «گزارش موتور» هست که قدم‌به‌قدم می‌نویسد چه امتحان شد و چه شد، با ساعت دقیق؛ اولین خط گزارش، نوع کانفیگ را دقیقاً مثل v2rayNG می‌نویسد (مثلاً VLESS / ws / tls) و اگر پشت یک CDN شناخته‌شده باشد نام آن CDN را هم می‌نویسد؛ بعد قدم‌به‌قدم می‌نویسد هر تلاش دقیقاً چه چیزی را عوض کرد. اگر موتور هیچ راهی پیدا نکرد و اتصال عادی هم برقرار شد ولی ترافیکی رد نشد، همان اتصال قطع می‌شود تا حالت «متصل» دروغین نماند. گزارش را می‌توانی کپی کنی و بفرستی. وقتی موتور خاموش است، این دکمه اصلاً دیده نمی‌شود.",
                    "When the engine is on, the page of every section where the engine can work has a small “Engine report” button that writes step by step what was tried and what happened, with the exact time; the first line states the config type exactly like v2rayNG (for example VLESS / ws / tls) and, when it sits behind a known CDN, names that CDN too, and every attempt line says exactly what it changed. If the engine finds no way and the normal connection comes up but carries no traffic, that connection is stopped so no fake connected state remains. You can copy the report and send it. When the engine is off, the button is not shown at all."),
                Block("محدودیت‌های صادقانه", "Honest limits",
                    "اگر سرور خودش خاموش باشد، رمز یا UUID عوض شده باشد، یا اشتراک تمام شده باشد، هیچ دستکاری‌ای آن را زنده نمی‌کند؛ موتور فقط انسداد شبکه‌ای را دور می‌زند. بررسی موتور چند ثانیه به اتصال اضافه می‌کند؛ برای کانفیگ سالم معمولاً همان یک تست اول است.",
                    "If the server itself is down, the password or UUID changed, or the subscription expired, no manipulation revives it; the engine only bypasses network-level blocking. Checking adds a few seconds before connecting; for a healthy config it is usually just the first test.")
            ))
            "wireguard" -> Triple("وایرگارد و امنزیا", "WireGuard & Amnezia", listOf(
                Block("این بخش چیست", "What this section is",
                    "تونل وایرگارد و تونل امنزیا در یک بخش. از نسخه ۱٫۷۶، هر دو نوع کانفیگ — ساده وایرگارد و امنزیا — با موتور امنزیا وصل می‌شوند، چون موتور رسمی وایرگارد روی بعضی گوشی‌ها موقع وصل شدن برنامه را بیرون می‌انداخت و وصل نمی‌شد؛ هسته امنزیا وایرگارد استاندارد هم حرف می‌زند، پس کانفیگ ساده وایرگارد بدون هیچ تغییری با همان موتور وصل می‌شود.",
                    "WireGuard tunnels and AmneziaWG tunnels in one section. Since version 1.76, both kinds of config — plain WireGuard and Amnezia — connect through the Amnezia engine, because the official WireGuard engine threw the app out on some phones instead of connecting; the Amnezia core speaks standard WireGuard too, so a plain WireGuard config connects unchanged with that engine."),
                Block("از کجا معلوم کدام نوع است", "How the type is told",
                    "برنامه از روی خود کانفیگ تشخیص می‌دهد: اگر در بخش Interface کلیدهای مخصوص امنزیا باشد (مثل Jc و Jmin و Jmax و S1 تا S4 و H1 تا H4 و I1 تا I5) کانفیگ امنزیا حساب می‌شود، وگرنه وایرگارد ساده. برچسب کج هر ردیف همان نوع خود کانفیگ را نشان می‌دهد، ولی خط «موتور» در صفحه جزئیات همیشه «امنزیا» است، چون واقعاً همه با همان موتور وصل می‌شوند.",
                    "The app detects it from the config itself: if the Interface section carries Amnezia-specific keys (like Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5) the config counts as Amnezia, otherwise as plain WireGuard. Each row's slanted label still shows the config's own type, but the “Engine” line in the details dialog always reads “Amnezia”, because that is genuinely the engine everything connects with."),
                Block("اضافه کردن تونل", "Adding a tunnel",
                    "با دکمه + پایین صفحه، فایل .conf را از حافظه گوشی انتخاب می‌کنی و به‌صورت یک پروفایل ذخیره می‌شود.",
                    "With the + button at the bottom you pick a .conf file from the phone's storage and it is saved as a profile."),
                Block("وصل و قطع", "Connect and disconnect",
                    "هر پروفایل یک سوییچ وصل/قطع، یک دکمه ویرایش و یک دکمه حذف دارد. وقتی وصل می‌شوی، تونل با همان تنظیمات فایل تو ساخته می‌شود. اگر هم‌زمان v2ray وصل باشد، اول آن قطع می‌شود، چون اندروید فقط یک VPN را هم‌زمان قبول می‌کند.",
                    "Each profile has a connect/disconnect switch, an edit button and a delete button. Connecting builds the tunnel with exactly your file's settings. If v2ray is connected, it is stopped first, because Android allows only one VPN at a time."),
                Block("موتور هوشمند در این بخش", "Smart Engine in this section",
                    "وقتی موتور روشن است، وصل شدن اول آدرس‌های IP و پورت‌های جایگزین همان تونل را امتحان می‌کند و فقط وقتی هندشیک واقعی برقرار شد (نه فقط حالت UP) آن نسخه را وصل‌شده حساب می‌کند؛ اگر هیچ‌کدام نشد، همان اتصال عادی انجام می‌شود. دکمه «گزارش موتور» وقتی موتور روشن است نشان داده می‌شود.",
                    "When the engine is on, connecting first tries alternate IPs and ports of the same tunnel, and only counts a variant as connected when a real handshake is established (not just the UP state); if none works, the normal connection follows. The “Engine report” button appears while the engine is on."),
                Block("اگر وصل نشد", "If it fails to connect",
                    "علت واقعی (مثلاً خطای مجوز VPN، خطای ساخت تونل یا خطای DNS) در خط وضعیت نوشته می‌شود تا بدانی دقیقاً چه مشکلی بوده است.",
                    "The real reason (for example a VPN permission error, a tunnel creation error, or a DNS error) is written in the status line.")
            ))
            "openvpn" -> Triple("OpenVPN", "OpenVPN", listOf(
                Block("این بخش چیست", "What this section is",
                    "تونل OpenVPN با موتور رسمی OpenVPN3، همان موتوری که اپ رسمی OpenVPN استفاده می‌کند.",
                    "An OpenVPN tunnel with the official OpenVPN3 engine, the same engine the official OpenVPN app uses."),
                Block("اضافه کردن پروفایل", "Adding a profile",
                    "با دکمه + نارنجی پایین صفحه، فایل .ovpn را از حافظه گوشی انتخاب می‌کنی. بعضی فایل‌ها نام کاربری و رمز دارند و همان موقع اضافه می‌شوند؛ بعضی فایل‌ها بعد از وارد کردن، صفحه‌ای باز می‌کنند که نام کاربری و رمز را در آن می‌نویسی و بعد پروفایل ذخیره می‌شود. در آن صفحه، دو فیلد اول (نام و هاست) به‌صورت خودکار پر می‌شوند و دو فیلد بعد نام کاربری و رمز هستند.",
                    "With the orange + button you pick a .ovpn file from the phone's storage. Some files carry a username and password and are added right away; others open a page after import where you enter the username and password, and then the profile is saved. On that page the first two fields (name and host) are filled automatically, and the next two are username and password."),
                Block("وصل و قطع و حذف", "Connect, disconnect and delete",
                    "هر پروفایل یک سوییچ وصل/قطع، یک دکمه ویرایش و یک دکمه حذف دارد. حذف همیشه با تأیید انجام می‌شود، نه فوری. اگر فایل .ovpn به فایل‌های جدا (مثل گواهی) ارجاع بدهد که همراه فایل نیست، اضافه شدن آن ناموفق می‌شود؛ این محدودیت خود فایل است.",
                    "Each profile has a connect/disconnect switch, an edit button and a delete button. Deletion always asks for confirmation, never instantly. If a .ovpn file refers to separate files (like certificates) that are not bundled with it, adding it fails; that is a limitation of the file itself."),
                Block("موتور هوشمند در این بخش", "Smart Engine in this section",
                    "وقتی موتور روشن است، وصل شدن اول آدرس‌های IP جایگزین سرور را جلوتر از نسخه اصلی می‌چیند و بعد پروتکل جایگزین (UDP به‌جای TCP و برعکس) را امتحان می‌کند و اگر هیچ‌کدام نشد، همان اتصال عادی انجام می‌شود. دکمه «گزارش موتور» وقتی موتور روشن است نشان داده می‌شود.",
                    "When the engine is on, connecting first places alternate server IPs ahead of the original, then tries the alternate protocol (UDP instead of TCP and vice versa), and if none works, the normal connection follows. The “Engine report” button appears while the engine is on.")
            ))
            "emergency" -> Triple("اتصال اضطراری", "Emergency connect", listOf(
                Block("این بخش چیست", "What this section is",
                    "یک دکمه بزرگ. کافی است کمی صبر کنی تا برنامه با توجه به اینترنتت به بهترین سرور وصل شود.",
                    "One big button. Just wait a little while the app connects to the best server for your internet."),
                Block("نکته مهم", "Important note",
                    "این قسمت نیاز به هیچ‌گونه وارد کردن کانفیگی از سمت کاربر ندارد. زدن دوباره دکمه در حالت وصل، اتصال را قطع می‌کند.",
                    "This section needs no config entered by the user at all. Tapping the button again while connected disconnects."),
                Block("هر بار تازه، بهترین سرور", "Fresh every time, the best server",
                    "هر بار که دکمه را می‌زنی، برنامه از اول سرورها را تازه می‌گیرد و مثل v2rayNG تست پینگ می‌گیرد و به کم‌پینگ‌ترین سرور سالم وصل می‌شود؛ مدت تقریبی زیر دکمه نوشته می‌شود. هیچ سروری ذخیره نمی‌شود و هیچ‌چیز به لیست v2ray اضافه نمی‌شود، و فیلترشکن با اسم کلی «اتصال اضطراری» بالا می‌آید، نه با اسم واقعی سرور، پس باز کردن صفحه v2ray بعداً معلوم نمی‌کند کدام سرور استفاده شده است.",
                    "Every time you tap the button, the app fetches the servers fresh, ping-tests them like v2rayNG, and connects to the lowest-ping healthy server; the approximate time is written under the button. No server is saved and nothing is added to the v2ray list, and the VPN comes up under the generic name “Emergency connect”, never the server's real name, so opening the v2ray page later cannot reveal which server was used."),
                Block("اگر بهترین سرور بالا نیامد", "If the best server does not come up",
                    "اگر اتصال بعد از ۶ ثانیه واقعاً بالا نیامده باشد، برنامه بی‌صدا قطع می‌کند و سرور بعدی را امتحان می‌کند، تا سه تلاش. اگر هیچ‌کدام نشد، دکمه ساده به حالت خاموش برمی‌گردد و جزئیاتی نشان داده نمی‌شود.",
                    "If the connection has not actually come up after 6 seconds, the app silently stops and tries the next best server, up to three attempts. If none works, the button simply returns to its off state and no detail is shown."),
                Block("در حالت وصل: دیدن پینگ", "While connected: seeing the ping",
                    "وقتی وصل هستی، زیر دکمه نوشته می‌شود «وصل شد، کلیک کنید برای دیدن پینگ»؛ با زدن آن، پینگ همان سرور دوباره اندازه گرفته می‌شود و نشان داده می‌شود.",
                    "While connected, the line under the button reads “Connected, tap to see ping”; tapping it measures that server's ping again and shows it."),
                Block("اگر فیلترشکن دیگری روشن باشد", "If another VPN is already on",
                    "اگر اتصال بخش دیگری (مثلاً v2ray) فعال باشد، این صفحه آن را قطع نمی‌کند و خودش هم وصل نمی‌شود؛ فقط می‌گوید کدام اتصال فعال است و باید اول آن را از همان بخش قطع کنی.",
                    "If another section's connection (for example v2ray) is active, this page neither stops it nor connects itself; it only says which connection is active, and you must stop it from its own section first."),
                Block("موتور هوشمند در این بخش", "Smart Engine in this section",
                    "وقتی موتور روشن است، بهترین سرور اضطراری هم با همان موتور بررسی می‌شود (موج‌های موازی، پرش و شکل‌های بسته‌ای) و دکمه «گزارش موتور» هم زیر دکمه اصلی نشان داده می‌شود؛ گزارش فقط کارهای موتور را می‌نویسد، هرگز اسم واقعی سرور را. وقتی موتور خاموش است، این بخش دقیقاً مثل قبل کار می‌کند.",
                    "When the engine is on, the best emergency server is also checked with the same engine (parallel waves, jump and packet shapes), and an “Engine report” button appears under the main button; the report writes only what the engine did, never the server's real name. When the engine is off, this section works exactly as before.")
            ))
            "smart" -> Triple("اتصال هوشمند", "Smart Connect", listOf(
                Block("این بخش چیست", "What this section is",
                    "اتصال هوشمند از صفحه اصلی برنامه باز می‌شود. وقتی یک اپ انتخاب‌شده (مثلاً تلگرام) باز شود، بدون اینکه برنامه ما را باز کنی، به‌صورت خودکار وصل می‌شود — اما فقط وقتی هیچ فیلترشکن دیگری روشن نباشد. اگر آن اپ بیشتر از ۵ ثانیه جلوی صفحه نباشد، فیلترشکن به‌صورت خودکار خاموش می‌شود.",
                    "Smart Connect opens from the app's home page. When a selected app (for example Telegram) opens, it connects automatically without opening this app — but only when no other VPN is on. If that app stays away from the foreground for more than 5 seconds, the VPN turns itself off."),
                Block("هدف اتصال: فقط یکی", "Connection target: only one",
                    "در همین صفحه از یک فهرست کشویی انتخاب می‌کنی که اتصال هوشمند با چی وصل شود، و فقط یکی می‌تواند انتخاب شود: v2ray، وایرگارد، امنزیا، OpenVPN، اتصال اضطراری یا هیدرا. برای v2ray، اول به آخرین کانفیگی وصل می‌شود که به آن وصل شده بودی (سریع، بدون تست پینگ) و اگر آن وصل نشد، در یک مهلت ۶۰ ثانیه‌ای تست پینگ می‌گیرد و به بهترین کانفیگ وصل می‌شود (زنجیره‌ها در این انتخاب شرکت نمی‌کنند و وصل شدن همیشه مستقیم است). برای وایرگارد به تونل فعال یا اولین تونل ذخیره‌شده وصل می‌شود و برای امنزیا به اولین تونل امنزیا. برای OpenVPN به آخرین پروفایل. برای اتصال اضطراری دقیقاً مثل صفحه خودش کار می‌کند: هر بار تازه می‌گیرد، تست پینگ می‌گیرد و به بهترین سرور وصل می‌شود، بدون اینکه چیزی ذخیره شود و بدون اینکه کانفیگی از تو لازم باشد. برای هیدرا، خود موتور هیدرا نقطه و شکل را انتخاب می‌کند و قطع خودکار فقط همان جلسه هیدرای خود اتصال هوشمند را قطع می‌کند.",
                    "On this page you pick from a dropdown what Smart Connect connects with, and only one can be selected: v2ray, WireGuard, Amnezia, OpenVPN, Emergency connect or Hydra. For v2ray it first tries the last config you connected to (fast, no ping test), and if that fails it ping-tests under a single 60-second deadline and connects to the best config (chains never take part in this choice, and the connect is always direct). For WireGuard it connects to the active or first saved tunnel, and for Amnezia to the first Amnezia tunnel. For OpenVPN to the last profile. For Emergency connect it works exactly like the emergency page: it fetches fresh, ping-tests, and connects to the best server, without saving anything and without needing any config from you. For Hydra, Hydra's own engine picks the endpoint and shape, and auto-off stops only Smart Connect's own Hydra session."),
                Block("دکمه دایره و جعبه اپ‌ها", "The circle button and the apps box",
                    "دایره وسط صفحه خودش دکمه فعال‌سازی است، نه نمایش اپ‌ها: وقتی مجوزها کامل نیست، قرمز کم‌رنگ چشمک می‌زند و زیرش نوشته می‌شود «برای فعالسازی کلیک کنید»؛ وقتی مجوزها کامل شد سبز می‌شود با همان یک خط؛ وقتی فعال است سبز با کلمه «فعال» است. زیر فهرست هدف، یک جعبه باریک هست که اسم اپ‌های انتخاب‌شده‌ات مثل نوار متحرک از آن رد می‌شوند؛ با زدن همان جعبه، صفحه انتخاب اپ باز می‌شود. وقتی هیچ اپی انتخاب نکرده باشی، جعبه قرمز کم‌رنگ چشمک می‌زند و می‌نویسد «حداقل یک برنامه برای اتصال هوشمند اضافه کنید».",
                    "The circle in the middle of the page IS the activation button, not an app display: while permissions are incomplete it blinks light red with the line “tap to activate” under it; when permissions are complete it turns green with the same one line; when active it is green with the word “active”. Under the target dropdown there is a narrow box where your selected apps' names scroll past like a ticker; tapping that box opens the app picker. While no app is selected, the box blinks light red and reads “add at least one app for Smart Connect”."),
                Block("ترتیب مجوزها: باتری، مصرف، VPN، اپ‌ها", "Permission order: battery, usage, VPN, apps",
                    "فعال‌سازی چهار قدم دارد، به همین ترتیب: اول باتری (معافیت از بهینه‌سازی باتری، تا سیستم در پس‌زمینه نکشدش؛ در گوشی شیائومی اجازه Autostart هم لازم است)، بعد دسترسی مصرف برنامه (تا بفهمد چه اپی باز شده است)، بعد مجوز VPN (برای هر هدف، یک بار به‌صورت دستی وصل شده باش تا ثبت شده باشد)، و آخر انتخاب حداقل یک اپ. هر مجوزی که کم باشد، زیر دایره با اسم خودش نوشته می‌شود و با «روی دکمه فعالسازی کلیک کنید ★»؛ با زدن دایره، برای همان مجوز یک راهنمای قدم‌به‌قدم باز می‌شود که اسم مجوز و راه دادنش را می‌گوید و یک دکمه سبز چشمک‌زن به تنظیمات دارد. اگر همه مجوزها کامل باشد ولی اپی انتخاب نکرده باشی، با زدن دایره صفحه‌ای باز می‌شود که می‌گوید حداقل یک برنامه لازم است و دکمه «انتخاب برنامه» دارد.",
                    "Activation has four steps, in this order: first battery (exemption from battery optimisation, so the system does not kill it in the background; on Xiaomi phones the Autostart permission is needed too), then usage access (so it can tell which app opened), then VPN permission (for each target, connect once manually so it is registered), and finally picking at least one app. Any missing permission is named under the circle with “tap the activation button ★”; tapping the circle opens a step-by-step guide for that permission, naming the permission and how to grant it, with a blinking green button to Settings. If all permissions are complete but no app is picked, tapping the circle opens a page saying at least one app is needed, with a “Pick apps” button."),
                Block("اگر دسترسی مصرف رد شد", "If usage access is denied",
                    "اگر اندروید دسترسی را رد کرد و نوشت «Controlled by restricted setting»، اول قدم «باز کردن اطلاعات برنامه» را انجام بده و در آن صفحه از سه‌نقطه بالا، «Allow restricted settings» را فعال کن؛ این محدودیت اندروید برای برنامه‌های نصب‌شده از بیرون پلی‌استور است.",
                    "If Android denies it with “Controlled by restricted setting”, first do the “Open app info” step and enable “Allow restricted settings” from the top ⋮ menu there; that is Android's restriction for sideloaded apps."),
                Block("انتخاب اپ‌ها و جستجو", "Choosing apps and searching",
                    "لیست همه برنامه‌های گوشی، حتی سیستمی‌ها، اینجاست. با کادر جستجو می‌توانی اسم برنامه را فارسی یا انگلیسی تایپ کنی و سریع پیداش کنی؛ مثلاً «تلگرام» یا «اینستا». تیک هر اپی را بزنی، باز شدنش اتصال هوشمند را فعال می‌کند. اگر موقع فعال بودن، لیست را خالی ذخیره کنی، اتصال هوشمند هم خاموش می‌شود تا دایره و جعبه و سرویس با هم هم‌نظر باشند.",
                    "The list holds every app on the phone, including system apps. The search box finds an app fast whether you type its name in Persian or English, for example “تلگرام” or “insta”. Ticking an app makes opening it trigger Smart Connect. If you save an empty list while active, Smart Connect switches off too, so the circle, the box and the service agree."),
                Block("فیلترشکن دستی تو هرگز مختل نمی‌شود", "Your hand-started VPN is never disturbed",
                    "اگر خودت دستی فیلترشکنی روشن کرده باشی — از همین برنامه یا از برنامه دیگر — اتصال هوشمند ساکت می‌ماند و هیچ کاری نمی‌کند تا وقتی خودت آن را قطع کنی. اتصال هوشمند هرگز فیلترشکن دستی تو را قطع نمی‌کند، و قطع خودکارش هم فقط برای فیلترشکنی است که خودش روشن کرده باشد، نه برای فیلترشکن دستی تو.",
                    "If you manually connected a VPN — from this app or another one — Smart Connect stays quiet and does nothing until you disconnect it yourself. Smart Connect never disconnects your hand-started VPN, and its auto-off only ever stops a VPN it started itself, never your manual one."),
                Block("قطع با ۵ ثانیه مهلت، و استثنای ۳۰ ثانیه‌ای", "Disconnect after a 5-second grace, and the 30-second exception",
                    "اگر اپ انتخاب‌شده بیشتر از ۵ ثانیه جلوی صفحه نباشد — با دکمه برگشت، با پاک کردن از لیست اخیر، یا با رفتن به اپ دیگر یا صفحه اصلی — فیلترشکن قطع می‌شود. برگشت سریع‌تر از ۵ ثانیه (مثلاً یک سر زدن کوتاه به صفحه اصلی) قطع نمی‌کند. بودن در داخل همین برنامه هم قطع نمی‌کند، و رد شدن گذرای سایه اعلان‌ها یا صفحه برنامه‌های اخیر هم قطع نمی‌کند؛ ولی اگر بعد از ترک اپ، ۳۰ ثانیه پیاپی فقط همان سایه یا صفحه اخیر یا قفل دیده شود (مثلاً گوشی قفل شده یا صفحه اخیر باز مانده)، آن‌وقت قطع می‌شود.",
                    "If the selected app stays away from the foreground for MORE than 5 seconds — by Back, by swiping it away, or by going to another app or the home screen — the VPN turns off. Coming back within 5 seconds (a quick hop to Home, for example) never cuts it. Being inside this app itself never cuts it, and the notification shade or recents screen flashing through never cuts it either; but if, after leaving the app, 30 seconds pass with only that shade, recents or lock screen persistently visible (for example the phone locked or recents left open), then it does cut."),
                Block("بستن کامل برنامه هنگام ترک — اختیاری", "Fully closing the left app — opt-in",
                    "در همین صفحه، دکمه‌ای هست با عنوان «بستن کامل برنامه‌ی انتخاب‌شده هنگام ترک آن» که پیش‌فرض خاموش است. وقتی روشن باشد (دکمه سبز)، بعد از اینکه اپ انتخاب‌شده را ترک کردی و مهلت ۵ ثانیه گذشت، علاوه بر قطع فیلترشکن هوشمند، پردازش پس‌زمینه همان اپ هم کشته می‌شود و در چند نظرسنجی بعدی هم تکرار می‌شود تا باز شدن بعدی‌اش از اول باشد. این گزینه فقط روی همان اپ ترک‌شده اثر می‌گذارد و هرگز فیلترشکن دستی تو را قطع نمی‌کند.",
                    "On this same page there is a row titled “Fully close the selected app when leaving it”, off by default. When it is on, after you leave a selected app and the 5-second grace passes, Smart Connect not only stops its own VPN but also kills that app's background process once, so its next open is a cold start. This option affects only the app you just left and never disconnects your hand-started VPN."),
                Block("قطع دستی، برگشت بدون مکث، و زنده ماندن", "Manual disconnect, instant re-entry, and staying alive",
                    "اگر خودت به‌صورت دستی قطع کنی، اتصال هوشمند پنج دقیقه صبر می‌کند تا دوباره وصل نشود؛ ولی ذخیره کردن تنظیمات همین صفحه، آن صبر را پاک می‌کند. باز شدن دوباره اپ انتخاب‌شده هیچ مکثی ندارد: ورود دوباره نشانه‌های تلاش ناموفق را صفر می‌کند و در همان جلسه، فاصله تلاش‌های ناموفق فقط ۱۵ ثانیه است. برای اینکه فقط وقتی برنامه باز است کار نکند، سرویس بعد از ری‌استارت گوشی و بعد از به‌روزرسانی برنامه خودش دوباره راه می‌افتد، و باز کردن صفحه اصلی یا صفحه اتصال هوشمند هم آن را دوباره مسلح می‌کند.",
                    "If you disconnect manually, Smart Connect waits five minutes before connecting again; but saving this page's settings clears that wait. Re-entering a selected app is never blocked by a cooldown: re-entry resets failed-attempt markers, and within one session the failed-retry pace is only 15 seconds. To keep working without the app open, the service restarts itself after a reboot and after an app update, and opening the home page or the Smart Connect page re-arms it too."),
                Block("موتور هوشمند در این بخش", "Smart Engine in this section",
                    "وقتی موتور روشن است، برای هدف v2ray یک قدم انتخاب‌گر هم اضافه می‌شود: قبل از مسیر سریع همیشگی، همان موج‌های موازی روی کانفیگ هدف امتحان می‌شوند (حداکثر ۱۲ ثانیه)؛ اگر نسخه‌ای جواب داد با همان وصل می‌شود، وگرنه مسیر عادی (سریع و بعد بهترین پینگ) ادامه پیدا می‌کند. این قدم هرگز کانفیگ ذخیره‌شده را عوض نمی‌کند، مالکیت و زمان‌بندی اتصال هوشمند را هم عوض نمی‌کند. دکمه «گزارش موتور» وقتی موتور روشن است نشان داده می‌شود.",
                    "When the engine is on, the v2ray target gains one selector pre-step: before the usual fast path, the same parallel waves are tried on the target config (at most 12 seconds); if a variant answers, it connects with it, otherwise the normal path (fast path, then best-ping) continues. This step never overwrites the saved selected config and never changes Smart Connect's ownership or pacing. The “Engine report” button appears while the engine is on."),
                Block("هدف‌های تازه: کلودفلر و زنجیره", "New targets: Cloudflare and Chain",
                    "دو هدف تازه اضافه شده است: کلودفلر (کانفیگ‌هایی که در بخش کلودفلر ساخته‌ای) و زنجیره دوتایی (هر دو زنجیره‌اش باید انتخاب شده باشد و هیچ‌کدام اوپن‌وی‌پی‌ان نباشد، چون اتصال هوشمند نمی‌تواند مسیر پروکسی اوپن‌وی‌پی‌ان را در پس‌زمینه اجرا کند). قانونی که برای همه هدف‌ها برقرار است: فقط چیزی را می‌توانی انتخاب کنی که واقعاً داری؛ اگر برای هدفی کانفیگی ذخیره نشده باشد، آن گزینه به انتهای فهرست می‌رود، کم‌رنگ می‌شود و کنارش نوشته می‌شود «اول کانفیگ اضافه کنید» و قابل انتخاب نیست. اتصال اضطراری و هیدرا همیشه قابل انتخاب‌اند، چون به کانفیگ شخصی تو نیاز ندارند.",
                    "Two new targets were added: Cloudflare (configs you built in the Cloudflare section) and Double chain (both hops must be picked and neither may be OpenVPN, because Smart cannot run the OpenVPN proxy path in the background). One rule covers all targets: you can only pick what you actually have; if nothing is saved for a target, that option sinks to the end of the list, dims, shows \u201cadd a config first\u201d next to it, and cannot be picked. Emergency and Hydra are always pickable, because they need no personal config of yours."),
            ))
            "chain" -> Triple("زنجیره دوتایی", "Double chain", listOf(
                Block("این بخش چیست", "What this section is",
                    "زنجیره دوتایی یعنی ترافیکت به‌جای یک فیلترشکن، از دو فیلترشکن پشت سر هم رد می‌شود: اول زنجیره اول، بعد زنجیره دوم، بعد اینترنت. مسیر دقیق: گوشی تو ← زنجیره اول ← زنجیره دوم ← سایت مقصد.",
                    "Double chain means your traffic passes through two VPN hops, one after the other, instead of one: first hop, then second hop, then the internet. The exact path: your phone → first hop → second hop → destination."),
                Block("این بخش به چه دردی می‌خورد", "What this section is for",
                    "۱) حریم خصوصی دو لایه: زنجیره اول آدرس واقعی تو را می‌بیند ولی مقصد را نمی‌فهمد؛ زنجیره دوم مقصد را می‌بیند ولی آدرس واقعی تو را نمی‌بیند. پس هیچ سروری به‌تنهایی هم هویت تو و هم مقصدت را با هم ندارد. ۲) دور زدن فیلترینگی که تک‌اتصال را می‌بندد: گاهی اتصال مستقیم به یک سرور بسته می‌شود ولی همان سرور از پشت یک سرور دیگر در دسترس است. ۳) ترکیب دو سرور سالم وقتی یکی کند یا ناپایدار است.",
                    "1) Two-layer privacy: the first hop sees your real address but not your destination; the second hop sees the destination but not your real address. So no single server holds both your identity and your destination. 2) Bypassing filtering that blocks a single connection: sometimes a direct connection to a server is blocked, but the same server is reachable from behind another server. 3) Combining two healthy servers when one is slow or unstable."),
                Block("ساختن زنجیره", "Building a chain",
                    "برای هر زنجیره نوعش را با قرص‌های بالای کارت انتخاب کن: v2ray از لیست ذخیره‌شده‌ات یا با چسباندن دستی یک کانفیگ (هر دو زنجیره ورود دستی دارند)، اضطراری (بهترین سرور، خودکار و بدون ذخیره شدن)، OpenVPN با انتخاب پروفایل، یا WireGuard با انتخاب تونل. انتخاب‌ها همان لحظه ذخیره می‌شوند؛ دکمه قرصی «وصل شدن زنجیره» را بزن. وسط دو کارت، یک دکمه دایره‌ای ⇅ هست که جای دو زنجیره را عوض می‌کند و اسمش زیر همان دایره نوشته شده است.",
                    "For each hop, pick its type with the pills at the top of the card: v2ray from your saved list or by pasting a config manually (both hops support manual paste), Emergency (the best server, automatic, nothing saved), OpenVPN by picking a profile, or WireGuard by picking a tunnel. Picks save at once; tap the pill “Connect chain” button. Between the two cards there is a circular ⇅ button that swaps the two hops, its name written under the circle."),
                Block("موتور هوشمند و زنجیره", "Smart Engine and chains",
                    "«پرش» موتور هوشمند همان زنجیره است: وقتی کانفیگی مستقیم وصل نمی‌شود، موتور آن را از پشت یک کانفیگ سالم دیگر امتحان می‌کند. برای همین است که کانفیگی که در بخش v2ray وصل نمی‌شود، گاهی در همین بخش زنجیره راحت وصل می‌شود.",
                    "The Smart Engine's “jump” is exactly a chain: when a config does not connect directly, the engine tries it from behind another healthy config. That is why a config that fails in the v2ray section sometimes connects easily in this chain section."),
                Block("محدودیت‌های صادقانه", "Honest limits",
                    "اوپن‌وی‌پی‌ان فقط می‌تواند زنجیره دوم باشد، چون اندروید فقط یک تونل VPN را هم‌زمان اجازه می‌دهد. زنجیره همیشه کندتر از تک‌اتصال است و هر دو زنجیره باید سالم باشند؛ اگر یکی خراب باشد زنجیره وصل نمی‌شود. ترکیب‌های دارای WireGuard و OpenVPN با روش‌های استاندارد هسته‌ها ساخته شده‌اند ولی هنوز روی گوشی تست نشده‌اند؛ اگر ترکیبی وصل نشد، خطایش در صفحه زنجیره نشان داده می‌شود، نه اینکه بی‌صدا تک‌اتصال شود.",
                    "OpenVPN can only be the second hop, because Android allows only one VPN tunnel at a time. A chain is always slower than a single connection, and both hops must be healthy; if either one is down, the chain does not connect. Combinations with WireGuard and OpenVPN are built with the cores' standard mechanisms but have not been tested on a real device yet; if a combination fails, its error is shown on the chain page, never silently downgraded to a single hop.")
            ))
            "hydra" -> Triple("هیدرا", "Hydra", listOf(
                Block("این بخش چیست", "What this section is",
                    "هیدرا موتور تازه‌ای از صفر نیست؛ یک روش وصل شدن است که روی موتورهای اثبات‌شده سوار می‌شود. به‌جای یک شکل ثابت، شکل اتصال را روی شبکه‌ی زنده‌ی خودت می‌چشد و شکلی را که واقعاً وصل می‌شود نگه می‌دارد.",
                    "Hydra is not a brand-new engine from scratch; it is a way of connecting, built on proven engines. Instead of one fixed shape, it tastes connection shapes on your live network and keeps the shape that truly connects."),
                Block("از کجا آمده", "Where it comes from",
                    "روش چشیدن و نگه‌داشتن برنده از Geneva دانشگاه مریلند آمده (سیستمی که استراتژی دور زدن فیلتر را روی شبکه‌ی زنده کشف می‌کند و در برابر فیلتر ایران هم جواب داده است). موتور اجراکننده همان Xray بخش v2ray است. شکل‌ها هم از کاتالوگ واقعی GoodbyeDPI و ByeDPI و PowerTunnel آمده‌اند: Fragment، اثر انگشت TLS، WebSocket، gRPC و Mux.",
                    "The taste-and-keep-the-winner method comes from the University of Maryland's Geneva (a system that discovers evasion strategies against live networks, proven against Iran's filter too). The executing engine is the v2ray section's own Xray. The shapes come from the real GoodbyeDPI, ByeDPI and PowerTunnel catalog: fragment, TLS fingerprint, WebSocket, gRPC and mux."),
                Block("چطوری کار می‌کند", "How it works",
                    "دکمه‌ی شروع را بزن. هیدرا نقطه‌های اتصال را از کانفیگ‌های v2ray خودت یا از بخش اضطراری می‌گیرد و هر کدام را با چند شکل امتحان می‌کند؛ هر امتحان یک اتصال واقعی است که تونلش هم بررسی می‌شود. اولین شکلی که واقعاً وصل شود برنده است. اگر اتصال وسط کار افتاد، نگهبان هیدرا خودش دوباره می‌چشد و وصل می‌شود. ترکیب برنده هم در حافظه می‌ماند تا دفعه‌ی بعد اول همان امتحان شود.",
                    "Tap Start. Hydra takes endpoints from your v2ray configs or the emergency section and tries each in several shapes; each try is a real connection whose tunnel is verified. The first shape that truly connects wins. If the connection drops mid-way, Hydra's watchdog re-probes and reconnects on its own. The winning combination stays in memory so next time it is tried first."),
                Block("دی‌سینک: ترفند سطح بسته — قابلیتی که این برنامه اضافه کرده", "Desync: packet-level tricks — added by this app",
                    "در صفحه آپشن‌های هیدرا یک تیک «دی‌سینک» هست. وقتی روشن باشد، هیدرا شکل‌های سطح بسته را هم می‌چشد: تکه‌تکه کردن بسته‌های TLS با طول و تأخیرهای مختلف (کلاسیک، کلاسیک بلند، ریز، متوسط، باز، کند، نویزی، تکه‌ای دوم و بلند دوم) و نویز UDP. هر تلاش دی‌سینک طول و تأخیر و تکه‌بندی‌اش را کمی جابه‌جا می‌کند (جیتر)، تا هر تلاش امضای متفاوتی داشته باشد و فیلتر نتواند فقط یک الگوی ثابت را ببندد؛ همان ترکیب کلاسیکی که یک بار برنده شده، دقیقاً با همان اعداد هم امتحان می‌شود تا برد تکرار شود. وقتی دی‌سینک روشن است، ترکیب برنده اول امتحان می‌شود و شکل‌های کلاسیک اول صف‌اند. وقتی خاموش است، هیدرا دقیقاً مثل قبل کار می‌کند.",
                    "On Hydra's options page there is a “desync” tick. When it is on, Hydra also tastes packet-level shapes: splitting TLS packets with different lengths and delays (classic, classic long, tiny, mid, wide, slow, noise, split2, long2) and UDP noise. Every desync attempt jitters its length, delay and split a little, so each attempt presents a different signature and the filter cannot just block one fixed pattern; the exact classic combination that won once is also tried with its exact numbers so the win can repeat. When desync is on, the winning pair is tried first and the classic shapes head the queue. When it is off, Hydra works exactly as before."),
                Block("وصل صریح و چشیدن خودکار", "Explicit connect and automatic probing",
                    "زدن دکمه وصل هیدرا، اول فیلترشکن بخش‌های دیگر را قطع می‌کند (مثل وایرگارد) و بعد هیدرا را بالا می‌آورد؛ ولی چشیدن‌های خودکار بعدی (بعد از افتادن اتصال) کنار می‌کشند و مزاحم اتصال دستی تو نمی‌شوند.",
                    "Tapping Hydra's connect first stops other sections' VPNs (like WireGuard) and then brings Hydra up; but later automatic probes (after a drop) step aside and never disturb your hand-started connection."),
                Block("گزارش و گزارش دی‌سینک", "Report and desync report",
                    "دکمه‌ی گزارش صفحه‌ی گزارش را باز می‌کند؛ آنجا قدم‌به‌قدم و به زبان ساده نوشته شده که هیدرا چه می‌کند: کدام منبع انتخاب شده، کدام کانفیگ با کدام شکل امتحان شد، کدام جواب نداد و چرا، کدام برنده شد، کی قطع شد و کی دوباره وصل شد؛ همه با ساعت دقیق. وقتی دی‌سینک روشن است، یک دکمه «گزارش دی‌سینک (آزمایشی)» هم هست که اعداد واقعی هر تلاش دی‌سینک را نشان می‌دهد؛ این گزارش موقتی است و هر وقت بخواهی برداشته می‌شود.",
                    "The report button opens the report page; there, step by step and in plain language, it is written what Hydra does: which source was chosen, which config was tried with which shape, which one failed and why, which one won, when it dropped and when it reconnected — all with the exact time. While desync is on, there is also a “Desync report (experimental)” button showing each desync attempt's real numbers; that report is temporary and is removed whenever you ask."),
                Block("آپشن‌ها", "Options",
                    "دکمه‌ی آپشن‌ها صفحه‌ی آپشن‌ها را باز می‌کند: منبع نقطه‌های اتصال (کانفیگ‌های v2ray خودت، اضطراری، هر دو، یا کانفیگی که خودت وارد می‌کنی)، حداکثر تعداد نقطه‌ها، حداکثر زمان چشیدن، شکل‌های قابل امتحان، تیک دی‌سینک، نگهبانی خودکار و حافظه‌ی شبکه. تغییرها با دکمه‌ی ذخیره ثبت می‌شوند و برای شروع بعدی اعمال می‌شوند.",
                    "The options button opens the options page: the endpoint source (your own v2ray configs, emergency, both, or a config you paste yourself), the maximum number of endpoints, the maximum probe time, the shapes to try, the desync tick, auto-watch and network memory. Changes save with the save button and apply to the next start."),
                Block("موتور هوشمند در این بخش", "Smart Engine in this section",
                    "وقتی موتور روشن است، برای هر نقطه اتصال، آدرس‌های IP جایگزین همان سرور جلوتر از نسخه اصلی چیده می‌شوند تا هیدرا اول آن‌ها را بچشد؛ ولی ترتیب شکل‌های خود هیدرا دست نمی‌خورد و دی‌سینک، وقتی روشن باشد، همچنان اول تست می‌شود. نقطه‌های اضطراری هرگز دست نمی‌خورند. دکمه «گزارش موتور» وقتی موتور روشن است نشان داده می‌شود.",
                    "When the engine is on, alternate IPs of the same server are placed ahead of the original for each endpoint, so Hydra tastes them first; but Hydra's own shape order is untouched, and desync, when on, is still tested first. Emergency endpoints are never mutated. The “Engine report” button appears while the engine is on."),
                Block("محدودیت‌های صادقانه", "Honest limits",
                    "هیدرا خودش سرور ندارد؛ بدون کانفیگ‌های خودت یا بخش اضطراری چیزی برای شکل دادن ندارد. اگر شبکه کاملاً بسته باشد هیچ شکلی رد نمی‌شود. اتصال اول چند ثانیه طول می‌کشد چون چشیدن وقت می‌برد.",
                    "Hydra has no servers of its own; without your configs or the emergency section it has nothing to shape. If the network is fully closed, no shape passes. The first connection takes seconds because tasting takes time.")
            ))
            "widgets" -> Triple("ویجت‌ها", "Widgets", listOf(
                Block("این بخش چیست", "What this section is",
                    "از تنظیمات صفحه اصلی، برای هر بخش (v2ray، WireGuard یا OpenVPN) یک ویجت به صفحه اصلی گوشی اضافه می‌کنی.",
                    "From the home page Settings you add a widget to the phone's home screen for each section (v2ray, WireGuard or OpenVPN)."),
                Block("آیکون، دکمه اتصال است", "The icon is the connect button",
                    "آیکون واقعی همان بخش داخل ویجت قرار می‌گیرد و همان آیکون، دکمه اتصال و قطع است: با زدن آن، بدون باز کردن برنامه، به کانفیگ انتخابی‌ات وصل می‌شوی یا قطع می‌کنی. وقتی وصل است، دکمه ویجت سبز می‌شود؛ وقتی از داخل برنامه وصل یا قطع می‌کنی، ویجت خودش رنگش را عوض می‌کند.",
                    "The section's real icon sits inside the widget, and that icon is itself the connect and disconnect button: tapping it connects you to your selected config or disconnects, without opening the app. When connected the widget button turns green, and when you connect or disconnect from inside the app, the widget recolours itself."),
                Block("اندازه، رنگ و آیکون از داخل برنامه", "Size, colour and icon from inside the app",
                    "در صفحه ویجت‌ها، برای هر بخش اندازه (خودکار یا ۱×۱، ۱×۲، ۱×۳، ۲×۲)، رنگ دکمه در حالت قطع (آبی، صورتی، بنفش، نارنجی یا سبز ملایم) و آیکون (واقعی یا حرف اول بخش) را انتخاب می‌کنی و روی ویجت‌های نصب‌شده همان لحظه اعمال می‌شود. در حالت وصل، دکمه همیشه سبز است.",
                    "On the widgets page, for each section you pick the size (auto or 1×1, 1×2, 1×3, 2×2), the button colour when disconnected (soft blue, pink, purple, orange or soft green) and the icon (real or the section's initial letter), applied to installed widgets at once. When connected the button is always green."),
                Block("نکته مجوز", "Permission note",
                    "برای هر بخش، اول یک بار به‌صورت دستی وصل شده باش تا مجوز VPN ثبت شده باشد؛ بعد از آن ویجت بدون باز کردن برنامه کار می‌کند.",
                    "For each section, connect once manually first so the VPN permission is registered; after that the widget works without opening the app.")
            ))
            "notification" -> Triple("اعلان اتصال", "Connection notification", listOf(
                Block("این چیست", "What it is",
                    "وقتی وصل هستی — چه دستی وصل شده باشی چه اتصال هوشمند وصل کرده باشد — در قسمت اعلان‌ها یک اعلان می‌بینی که نشان می‌دهد به چه چیزی وصلی (مثلاً v2ray یا اتصال اضطراری)، سرعت اتصال چقدر است و کانفیگ برای آی‌پی کدام کشور است.",
                    "While connected — whether you connected manually or Smart Connect did — you see a notification showing what you are connected to (for example v2ray or emergency connect), the connection speed, and which country's IP the config is for."),
                Block("دکمه‌های اعلان", "Notification buttons",
                    "همان اعلان سه کار را دارد: توقف فیلترشکن (قطع)، اتصال به فیلترشکن (وصل) و ری‌استارت اتصال. سرعت، سرعت کل تونل است و چند ثانیه بعد از وصل شدن نشان داده می‌شود.",
                    "That same notification offers three actions: stop the VPN (disconnect), connect to the VPN, and restart the connection. The speed is the tunnel's total speed and appears a few seconds after connecting."),
                Block("مجوز اعلان", "Notification permission",
                    "در اولین ورود به برنامه، یک بار اجازه اعلان از تو خواسته می‌شود تا این اعلان دیده شود؛ اگر رد کنی، دیگر اصرار نمی‌شود و اعلان هم نشان داده نمی‌شود.",
                    "On your first entry into the app, notification permission is asked once so this notification can be shown; if you decline, it is not asked again and the notification does not appear.")
            ))
            "homesettings" -> Triple("تنظیمات صفحه اصلی", "Home page settings", listOf(
                Block("این بخش چیست", "What this section is",
                    "با چرخ‌دنده بالای صفحه اصلی به تنظیمات می‌رسی: مرتب‌سازی برنامه‌ها، ویجت صفحه اصلی، شخصی‌سازی ورود و همین راهنما. هر ردیف حالا یک ردیف فشرده با آیکون رنگی، تیتر و توضیح کوتاه است؛ با زدن همان ردیف، صفحه‌اش باز می‌شود.",
                    "The gear at the top of the home page opens Settings: sort apps, home screen widgets, login personalisation and this guide. Each row is now a compact row with a coloured icon, a title and a short description; tapping the row opens its page."),
                Block("مرتب‌سازی با کشیدن", "Sorting by dragging",
                    "در مرتب‌سازی، آیکون هر برنامه دقیقاً مثل صفحه اصلی نشان داده می‌شود. روی آیکون نگه دار و با دست آن را بکش روی ردیفی که می‌خواهی، بعد «ذخیره» را بزن. بدون ذخیره، ترتیب عوض نمی‌شود.",
                    "In sorting, each app's icon is shown exactly like on the home page. Hold an icon and drag it onto the row you want, then tap Save. Without saving, the order does not change."),
                Block("شخصی‌سازی ورود", "Login personalisation",
                    "صفحه ورود (ماشین‌حساب) را می‌توانی کامل به سلیقه خودت دربیاوری: استودیوی ماشین‌حساب با پس‌زمینه‌های کشیده‌شده (از جمله کهکشان با ستاره‌های کشیده‌شده و طرح رنگی با دو رنگ قابل انتخاب تو)، رنگ جداگانه برای دکمه «=» و نوشته نمایشگر و حاشیه دکمه‌ها، کلفتی حاشیه، شفافیت دکمه‌ها، پنج شکل دکمه (گرد، مربعی، دایره‌ای، کپسولی و نرم)، سه اندازه دکمه (کوچک، معمولی، بزرگ) و سه فونت نمایشگر (پررنگ، معمولی و تک‌فاصله‌ای). تغییر، دفعه بعد که صفحه ورود باز شود اعمال می‌شود و رمزها را عوض نمی‌کند. در همین صفحه نحوه باز شدن برنامه هم انتخابی است: با رمز (مثل قبل)؛ با رمز + لرزش گوشی، یعنی بعد از زدن رمز درست باید خود گوشی را به تعداد انتخاب‌شده (یک تا پنج بار) تکان بدهی تا برنامه باز شود و فقط زدن رمز کافی نیست؛ فقط با لرزش گوشی بدون رمز، که فقط بخش عمومی را باز می‌کند؛ یا بدون رمز که فقط بخش عمومی را باز می‌کند. «لرزش» یعنی خود گوشی را تکان دادن، نه لرزیدن گوشی؛ هیچ راهنمایی از این حرکت روی صفحه نیست.",
                    "You can fully restyle the login (calculator) page: the calculator studio with drawn backgrounds (including a drawn-star galaxy and a colourful design with two colours you pick), separate colours for the “=” key, the display text and the key border, border thickness, key transparency, five button shapes (round, square, circle, pill and soft), three key sizes (small, normal, large) and three display fonts (bold, normal and monospace). The change applies the next time the login page opens and does not change any password. On the same page, how the app opens is also your choice: with a PIN (as before); with PIN + phone shake, meaning after the correct PIN you must shake the phone itself the chosen number of times (one to five) before the app opens, and the PIN alone is not enough; with phone shake only and no PIN, which opens only the public section; or without a PIN, which opens only the public section. “Shake” means shaking the phone itself, not the phone vibrating; there is no on-screen hint about this gesture."),
            ))
            "rename" -> Triple("تغییر نام کانفیگ", "Rename configs", listOf(
                Block("این بخش چیست", "What this section is",
                    "اسم جدید برای کانفیگ‌ها، با خروجی زنده.",
                    "New names for configs, with live output."),
                Block("چطور کار می‌کند", "How it works",
                    "کانفیگ‌ها را در کادر اول می‌چسبانی (هر کانفیگ در یک خط) و نام‌های جدید را در کادر دوم می‌نویسی. هر خط نام، برای کانفیگ هم‌ردیف خودش است. اگر فقط یک نام بنویسی، همه کانفیگ‌ها دقیقاً همان نام را می‌گیرند، بدون هیچ شماره‌ای اضافه. خروجی به‌صورت زنده ساخته می‌شود: به محض اینکه اسم را تایپ کنی، کادر خروجی هم عوض می‌شود. کانفیگی که برایش نام جدید ننوشته باشی، نام قبلی خودش را نگه می‌دارد و از دست نمی‌رود. در آخر با دکمه پایین صفحه، همه کانفیگ‌های تغییرنام‌داده‌شده را یکجا کپی می‌کنی.",
                    "You paste configs in the first box (one config per line) and write the new names in the second box. Each name line belongs to the config on the same row. If you write only one name, every config gets exactly that name, with no added numbering. The output is live: as soon as you type a name, the output box changes too. A config you wrote no new name for keeps its previous name. Finally, with the bottom button, you copy all renamed configs at once.")
            ))
            "subs" -> Triple("اشتراک‌ها و تست پینگ", "Subscriptions & ping test", listOf(
                Block("این بخش چیست", "What this section is",
                    "لینک ساب، تست در پس‌زمینه و گزارش هر ساب.",
                    "Subscription links, background test and a per-subscription report."),
                Block("اشتراک‌ها", "Subscriptions",
                    "لینک‌های سابسکریبشن را ذخیره می‌کنی و با «به‌روزرسانی اشتراک‌های انتخاب‌شده» فقط کانفیگ‌های ساب‌های تیک‌خورده را می‌گیری؛ ساب‌های تیک‌نخورده دست نمی‌خورند. کانفیگ‌های تکراری به‌صورت نرمال‌شده حذف می‌شوند، یعنی کانفیگی که فقط اسمش فرق دارد یا ترتیب پارامترهایش فرق دارد، دوباره حساب نمی‌شود. لینک ساب فقط با حذف همان ردیف پاک می‌شود، نه خودکار.",
                    "You save subscription links and fetch only the checked subscriptions' configs with “Update selected subscriptions”; unchecked subscriptions are left untouched. Duplicate configs are removed in normalized form, so a config that only differs by name or parameter order is not counted twice. A subscription link is only deleted by deleting its own row, never automatically."),
                Block("تست پینگ", "Ping test",
                    "تست پینگ در یک سرویس پیش‌زمینه انجام می‌شود، پس با خاموش شدن صفحه یا بیرون رفتن از برنامه متوقف نمی‌شود. سالم‌ها کادری ندارند؛ دکمه «کپی کانفیگ‌های سالم» شمارنده زنده دارد و همان لحظه هر چه سالم پیدا شده باشد را، حتی وسط تست، یکجا کپی می‌کند؛ دکمه «توقف تست پینگ» هم هست. اگر در «نام دلخواه برای خروجی» نامی بنویسی، همه سالم‌ها دقیقاً همان نام را می‌گیرند و با پاک کردن نام، اسم‌های اصلی برمی‌گردند.",
                    "The ping test runs in a foreground service, so it does not stop when the screen turns off or you leave the app. Healthy configs have no box; the “Copy healthy configs” button carries a live count and copies whatever is healthy at that moment, even mid-test; there is also a “Stop ping test” button. If you write a name in the “Desired name for output” box, all healthy configs get exactly that name, and clearing the name brings back the original names."),
                Block("گزارش و خالی کردن", "Report and clearing",
                    "جلوی هر لینک ساب نوشته می‌شود که چند کانفیگ از آن گرفته شده و چندتایش سالم بوده است؛ عدد سالم‌ها همان لحظه که هر کانفیگ سالم پیدا می‌شود بالا می‌رود و لازم نیست تست تمام شود، و این گزارش بعد از بستن و باز کردن برنامه هم می‌ماند. کانفیگ‌های دریافت‌شده کادری ندارند و فقط تعدادشان در یک نوار کوتاه نشان داده می‌شود. از دکمه دایره‌ای سه‌نقطه بالای صفحه، گزینه «خالی کردن همه» هست که با تأیید تو، دریافت‌شده، نام دلخواه و سالم‌ها را خالی می‌کند.",
                    "In front of each subscription link it says how many configs were received from it and how many were healthy; the healthy number grows the moment each healthy config is found, without waiting for the test to finish, and this report survives closing and reopening the app. Received configs have no box; only their count is shown in a short strip. From the circular ⋮ button at the top, “Clear everything” empties the received configs, the desired name and the healthy ones after your confirmation.")
            ))
            "cloudflare" -> Triple("کلودفلر", "Cloudflare", listOf(
                Block("این بخش چیست", "What this section is",
                    "این بخش با دو مرحله‌ی ساده، با حساب کلودفلر خودتان کانفیگ می‌سازد و همه‌ی کارها همین‌جا داخل برنامه انجام می‌شود؛ لینک پنل به شما نمایش داده نمی‌شود و نیازی به باز کردن پنل نیست.",
                    "This section builds configs with your own Cloudflare account in two simple steps, and everything happens inside the app; the panel link is never shown to you and you never need to open the panel."),
                Block("اتصال و ساخت کانفیگ", "Connect and build configs",
                    "یک دکمه برای همه‌چیز است: اول کشورها را (یا «بدون پروکسی» را به‌تنهایی) تیک بزن، ایمیل و کلید گلوبال را وارد کن و «اتصال و ساخت کانفیگ» را بزن. برنامه حساب را چک می‌کند، پنل را می‌سازد (یا به‌روز می‌کند)، آی‌پی‌های سالم را اسکن و وارد پنل می‌کند و بعد برای همان انتخاب، کانفیگ‌ها را می‌سازد. هر بار که دوباره این دکمه را بزنی، پروکسی‌های قبلی و آی‌پی‌های قبلی از پنل پاک می‌شوند و پروکسی و آی‌پی تازه تحویل داده می‌شود؛ یعنی پنل همیشه دقیقاً همان انتخابِ آخر را دارد. توجه: چون پنل فقط انتخاب آخر را نگه می‌دارد، کانفیگ‌های کشوریِ قدیمی که قبلاً وارد v2ray کرده‌ای ممکن است بعد از یک ساخت تازه، از پروکسی تازه عبور کنند؛ برای اطمینان، بعد از هر ساخت تازه، کانفیگ‌های تازه را وارد کن.",
                    "One button does everything: first tick the countries (or “No proxy” on its own), enter the email and Global key, and tap “Connect and build configs”. The app checks the account, builds (or updates) the panel, scans healthy IPs into the panel, and then builds configs for that selection. Every time you tap the button again, the panel’s old proxies and IPs are deleted and fresh ones are delivered, so the panel always holds exactly the latest selection. Note: because the panel keeps only the latest selection, older country configs you imported into v2ray may route through the fresh proxies after a new build; to be safe, import the fresh configs after each new build."),
                Block("پروکسی تلگرام", "Telegram proxy",
                    "دکمه «اتصال و ساخت پروکسی تلگرام» کنار دکمه کانفیگ است. پروکسی تلگرام از نوع WEB proxy رسمی تلگرام است، نه MTProto (پروکسی MTProto روی کلودفلر ممکن نیست چون ورکر اتصال TCP خام نمی‌پذیرد). برنامه برای هر اکانت فقط یک پروکسی می‌سازد: سکرت آن را خودش به‌صورت تصادفی انتخاب می‌کند و از تو نمی‌خواهد؛ ورکر را روی حساب خودت بالا می‌آورد، صفحه bridge آن را تست می‌کند و لینک tg://webproxy را در کادری جدا، با حروف کوچک، بالای کادر کانفیگ‌ها نشان می‌دهد؛ جلویش دکمه کپی و دکمه «باز کردن با تلگرام» هست که با زدن آن، تلگرام باز می‌شود و می‌توانی پروکسی را با یک لمس اضافه کنی. اگر دو اکانت داشته باشی، بالای صفحه پروکسی هم یک کادر قرمز کم‌رنگ «اکانت را انتخاب کنید» می‌بینی که با انتخاب اکانت، سبز کم‌رنگ می‌شود و پروکسی با همان اکانت ساخته می‌شود. ساخت دوباره برای همان اکانت، پروکسی قبلی را جایگزین می‌کند و ورکر قبلی را پاک می‌کند؛ دکمه سطل آشغال کادر کانفیگ‌ها پروکسی را پاک نمی‌کند؛ برای پاک کردن پروکسی، سطل آشغال بالای کادر پروکسی را بزن که لینک و ورکر آن را هم برمی‌دارد.",
                    "The “Connect and build Telegram proxy” button sits next to the config button. The Telegram proxy is Telegram's official WEB proxy type, not MTProto (an MTProto proxy cannot run on Cloudflare because a Worker accepts no raw TCP). The app builds exactly one proxy per account: it picks the secret itself at random and never asks you for it; it deploys the Worker on your own account, tests its bridge page, and shows the tg://webproxy link in a separate card, in small letters, above the config cards; next to it are a copy button and an “Open with Telegram” button that opens Telegram so you can add the proxy with one tap. If you have two accounts, the proxy page also shows a light-red “Select an account” box that turns light green once you pick, and the proxy is built with that account. Rebuilding for the same account replaces the old proxy and deletes its old Worker; the trash button on the config card does not delete the proxy; the trash button above the proxy card deletes the proxy itself, including its Worker."),
Block("کانفیگ‌های ساخته‌شده", "Built configs",
                    "بعد از تکمیل مرحله‌ی ۲، یک کادر خوشگل و جمع‌وجور پایین صفحه (نه تمام‌صفحه) باز می‌شود که کانفیگ‌های ساخته‌شده را جدا جدا نشان می‌دهد. جلوی تک‌تک کانفیگ‌ها تست پینگ هست که با زدن آن، پینگ در جای مناسب نشان داده می‌شود؛ جلوی همه‌ی کانفیگ‌ها علامت کپی هست و علامتی هم برای اتصال از همین صفحه. پایین کادر سه دکمه‌ی مینیمال هست: کپی همه‌ی کانفیگ‌ها، تست پینگ همه و وارد کردن به قسمت v2ray.",
                    "After step 2 completes, a beautiful compact box opens below (not full-screen) showing the built configs separately. Each config has a ping test in front of it, which shows the ping in a suitable place when tapped; every config has a copy mark and a mark for connecting from this very page. At the bottom of the box are three minimal buttons: copy all configs, ping-test all, and import into the v2ray section."),
                Block("وارد کردن به v2ray", "Import to v2ray",
                    "کانفیگ‌هایی که وارد بخش v2ray می‌شوند، کنارشان به‌جای نوع کانفیگ (مثل vless) نوشته می‌شود «کلودفلر» و رنگ پس‌زمینه‌شان کمی ملایم کهکشانی است تا با کانفیگ‌های دیگر فرقشان مشخص باشد. آیکن پرچم کشور جلوی کانفیگ هم هر دو ثانیه بین پرچم همان کانفیگ و آیکن کلودفلر (همان آیکن روی بخش کلودفلر) تغییر می‌کند. اسم کانفیگ‌ها در هر دو بخش کلودفلر و v2ray به «کلودفلر - اسم کشور» تغییر می‌کند، در فارسی و انگلیسی به همان زبان.",
                    "Configs imported into the v2ray section show “Cloudflare” next to them instead of the config type (like vless), and their background is softly galaxy-tinted so they stand apart from other configs. The country-flag icon in front of the config also alternates every two seconds between that config's flag and the Cloudflare icon (the one on the Cloudflare section). Config names change in both the Cloudflare and v2ray sections to “Cloudflare - country name”, in Persian and English in the matching language."),
                Block("لاگ", "Log",
                    "بالای صفحه، سمت راست، یک آیکن مینیمال کوچک برای لاگ هست. مرحله به مرحله هر کاری که انجام می‌شود، لاگش همان‌جا می‌افتد؛ هم کارهایی که برنامه می‌کند و هم عیب‌یابی، از اول تا آخر؛ مثلاً ورکر ساخته شد، برنامه وارد پنل شد و مانند آن. در لاگ هیچ ایمیل، کلید، آدرس پنل یا لینک کانفیگی نوشته نمی‌شود.",
                    "At the top-right of the page there is a small minimal log icon. Step by step, everything that happens is logged there — both what the app does and debugging, from start to finish; for example, the worker was built, the app logged into the panel, and so on. The log never contains emails, keys, panel addresses or config links."),
                Block("کشورهای ثابت و بدون پروکسی", "Fixed countries and No proxy",
                    "کشورهای قابل انتخاب ثابت‌اند: آلمان، هلند، فرانسه، چین، آمریکا، کانادا و انگلیس؛ برای هر کشور یک کانفیگ ساخته می‌شود و رله همان کشور، مسیر ظاهری کانفیگ را تعیین می‌کند. گزینه «بدون پروکسی» را به‌تنهایی انتخاب کن تا به‌تعداد آی‌پی‌های سالم پیدا شده (حداکثر ۱۵) کانفیگ بدون رله بگیری؛ این کانفیگ‌ها مستقیم از پنل وصل می‌شوند. پنل فقط یک فهرست رله دارد: ساخت بدون پروکسی رله‌ها را خالی می‌کند و ساخت کشورها آن را برمی‌گرداند؛ پس کانفیگ‌های ساخته‌شده قبلی همیشه تابع آخرین ساخت‌اند و برای برگرداندن رفتارشان، همان حالت را دوباره بساز. هر بار که ساخت می‌زنی، برنامه در پس‌زمینه وارد پنل می‌شود و پروکسی‌های تازه را وارد می‌کند؛ پس از یک بار اتصال مرحله ۱، برای ساخت دوباره نیازی به زدن دوباره آن نیست، مگر اینکه خودت بخواهی آی‌پی‌های سالم تازه بگیری.",
                    "The pickable countries are fixed: Germany, Netherlands, France, China, United States, Canada and United Kingdom; one config is built per country, and that country's relay decides the config's apparent route. Pick “No proxy” on its own to get one relay-free config per healthy IP found (at most 15); those configs connect straight through the panel. The panel holds a single relay list: a proxy-free build empties it and a country build restores it, so configs built earlier always follow the latest build; rebuild the same mode to restore their behavior. Every build reopens the panel in the background and re-enters the fresh proxies, so after step 1 has connected once, rebuilding needs no fresh step-1 tap unless you want new healthy IPs."),
                    Block("مدیریت اکانت", "Account management", 
                        "دکمه + در ردیف مرحله‌ها وقتی ظاهر می‌شود که مرحله ۱ کامل انجام شده باشد (یا دو اکانت داشته باشی). در آنجا اکانت اول و دوم را می‌بینی و فقط یکی را می‌توانی انتخاب کنی؛ با سوییچ بین اکانت‌ها، کانفیگ‌های ساخته‌شده همان اکانت نشان داده می‌شود و بالای کادر هم نوشته می‌شود مال کدام اکانت است. افزودن اکانت تازه با ایمیل و کلید گلوبال و دکمه اتصال است؛ برنامه اتصال را چک می‌کند و اگر سالم بود اکانت را اضافه می‌کند و راه‌اندازی پنل و آی‌پی‌هایش را در پس‌زمینه انجام می‌دهد تا بعد از انتخاب و بستن صفحه، مرحله ۲ مستقیم کانفیگ بسازد. سقف دو اکانت است. کانفیگ‌های داخل همین بخش فقط با دکمه سطل آشغال پاک می‌شوند و پاک شدنشان ربطی به کانفیگ‌های واردشده به v2ray ندارد.", 
                        "The small + button appears in the step row once step 1 is fully done (or once you have two accounts). There you see Account 1 and Account 2 and can select only one; switching shows the configs built with that account, and the results box header says which account they belong to. Adding an account takes its email and Global key plus Connect; the app checks the connection and, if healthy, adds the account and runs its panel and IP setup in the background, so after selecting and closing, step 2 builds straight away. The limit is two accounts. Configs inside this section are deleted only with the trash button, and deleting them never touches configs already imported into v2ray.")
            ))
            else -> Triple(key, key, listOf(
                Block("توضیح", "Explanation", "برای این بخش توضیحی ثبت نشده است.", "No explanation recorded for this section.")
            ))
        }
    }
}
