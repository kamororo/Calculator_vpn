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
 * - The Emergency section says ONLY: wait a little
 *   while the app connects to the best server for
 *   your internet, and it needs no config from the
 *   user. It never explains how it works, and never
 *   mentions the hidden settings gesture.
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
            text = L(titleFa, titleEn); textSize = 21f
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })
        for (b in blocks) {
            panel.addView(TextView(this).apply {
                text = L(b.headFa, b.headEn); textSize = 17f
                setTextColor(Color.parseColor("#FF8C00"))
                setPadding(0, dp(12), 0, dp(4))
            })
            panel.addView(TextView(this).apply {
                text = L(b.bodyFa, b.bodyEn); textSize = 15f
                setTextColor(if (dark) Color.parseColor("#EEEEEE") else Color.parseColor("#333333"))
                setPadding(0, 0, 0, dp(8))
            })
        }
        panel.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { finish() }
        })
        root.addView(panel)
        setContentView(scroll)
    }

    private fun content(key: String): Triple<String, String, List<Block>> {
        return when (key) {
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
                Block("تنظیمات (فقط یک بخش)", "Settings (a single section)",
                    "از منوی کشویی (☰) فقط یک «تنظیمات» هست که همه چیز را یکجا دارد: انتخاب زبان برنامه، تنظیمات اتصال (MTU، DNS، پورت SOCKS) و تنظیمات کامل v2ray شامل DNS، Mux، Fragment، Sniffing، پروکسی محلی، Bypass LAN، سطح لاگ و آدرس تست پینگ. این تنظیم‌ها روی اتصال بعدی واقعاً اعمال می‌شوند. بعضی گزینه‌ها فقط ذخیره می‌شوند و در صفحه خودشان هم همین نوشته شده است.",
                    "The drawer (☰) has a single Settings section with everything together: app language, connection settings (MTU, DNS, SOCKS port) and full v2ray settings including DNS, Mux, Fragment, Sniffing, local proxy, Bypass LAN, log level and the delay-test URL. They really apply to the next connection. A few options are stored only, and the page says so itself.")
            ))
            "wireguard" -> Triple("WireGuard", "WireGuard", listOf(
                Block("این بخش چیست", "What this section is",
                    "تونل وایرگارد با موتور رسمی خود وایرگارد، نه با موتور حدسی.",
                    "A WireGuard tunnel with the official WireGuard engine itself, not a guessed engine."),
                Block("اضافه کردن تونل", "Adding a tunnel",
                    "با دکمه + پایین صفحه، فایل .conf را از حافظه گوشی انتخاب می‌کنی و به‌صورت یک پروفایل ذخیره می‌شود.",
                    "With the + button at the bottom you pick a .conf file from the phone's storage and it is saved as a profile."),
                Block("وصل و قطع", "Connect and disconnect",
                    "هر پروفایل یک سوییچ وصل/قطع، یک دکمه ویرایش و یک دکمه حذف دارد. وقتی وصل می‌شوی، تونل با همان تنظیمات فایل تو ساخته می‌شود. اگر هم‌زمان v2ray وصل باشد، اول آن قطع می‌شود، چون اندروید فقط یک VPN را هم‌زمان قبول می‌کند.",
                    "Each profile has a connect/disconnect switch, an edit button and a delete button. Connecting builds the tunnel with exactly your file's settings. If v2ray is connected, it is stopped first, because Android allows only one VPN at a time."),
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
                    "Each profile has a connect/disconnect switch, an edit button and a delete button. Deletion always asks for confirmation, never instantly. If a .ovpn file refers to separate files (like certificates) that are not bundled with it, adding it fails; that is a limitation of the file itself.")
            ))
            "emergency" -> Triple("اتصال اضطراری", "Emergency connect", listOf(
                Block("این بخش چیست", "What this section is",
                    "یک دکمه بزرگ. کافی است کمی صبر کنی تا برنامه با توجه به اینترنتت به بهترین سرور وصل شود.",
                    "One big button. Just wait a little while the app connects to the best server for your internet."),
                Block("نکته مهم", "Important note",
                    "این قسمت نیاز به هیچ‌گونه وارد کردن کانفیگی از سمت کاربر ندارد. زدن دوباره دکمه در حالت وصل، اتصال را قطع می‌کند.",
                    "This section needs no config entered by the user at all. Tapping the button again while connected disconnects.")
            ))
            "smart" -> Triple("اتصال هوشمند", "Smart Connect", listOf(
                Block("این بخش چیست", "What this section is",
                    "اتصال هوشمند از صفحه اصلی برنامه باز می‌شود. وقتی یک اپ انتخاب‌شده (مثلاً تلگرام) باز شود، بدون اینکه برنامه ما را باز کنی، به‌صورت خودکار وصل می‌شود — اما فقط وقتی هیچ فیلترشکن دیگری روشن نباشد. اگر آن اپ بیشتر از ۵ ثانیه جلوی صفحه نباشد، فیلترشکن به‌صورت خودکار خاموش می‌شود.",
                    "Smart Connect opens from the app's home page. When a selected app (for example Telegram) opens, it connects automatically without opening this app — but only when no other VPN is on. If that app stays away from the foreground for more than 5 seconds, the VPN turns itself off."),
                Block("انتخاب اینکه با چی وصل شود — فقط یکی", "Choosing what it connects with — only one",
                    "در همین صفحه انتخاب می‌کنی که اتصال هوشمند با چی وصل شود: v2ray، یا WireGuard، یا OpenVPN، یا اتصال اضطراری. فقط یکی از این چهار می‌تواند انتخاب شود. برای v2ray، اول به آخرین کانفیگی وصل می‌شود که به آن وصل شده بودی (سریع، بدون تست پینگ) و اگر آن وصل نشد، تست پینگ می‌گیرد و به بهترین کانفیگ وصل می‌شود. برای WireGuard به تونل فعال ذخیره‌شده وصل می‌شود و برای OpenVPN به آخرین پروفایل. برای اتصال اضطراری دقیقاً مثل صفحه خودش کار می‌کند: هر بار تازه می‌گیرد، تست پینگ می‌گیرد و به بهترین سرور وصل می‌شود، بدون اینکه چیزی ذخیره شود و بدون اینکه کانفیگی از تو لازم باشد.",
                    "On this page you choose what Smart Connect connects with: v2ray, WireGuard, OpenVPN, or Emergency connect. Only one of the four can be selected. For v2ray it first tries the last config you connected to (fast, no ping test), and if that fails it ping-tests and connects to the best config. For WireGuard it connects to the saved active tunnel, and for OpenVPN to the last profile. For Emergency connect it works exactly like the emergency page: it fetches fresh, ping-tests, and connects to the best server, without saving anything and without needing any config from you."),
                Block("دو مجوز لازم", "Two permissions needed",
                    "اول، از همین صفحه «اعطای دسترسی مصرف برنامه» را بزن تا برنامه بفهمد چه اپی باز شده است. اگر اندروید دسترسی را رد کرد و نوشت «Controlled by restricted setting»، اول قدم «باز کردن اطلاعات برنامه» را انجام بده و در آن صفحه از سه‌نقطه بالا، «Allow restricted settings» را فعال کن؛ این محدودیت اندروید برای برنامه‌های نصب‌شده از بیرون پلی‌استور است. دوم، برای هر هدف، یک بار به‌صورت دستی وصل شده باش تا مجوز VPN ثبت شده باشد.",
                    "First, tap Grant usage access on this page so the app can tell which app opened. If Android denies it with “Controlled by restricted setting”, first do the “Open app info” step and enable “Allow restricted settings” from the top ⋮ menu there; that is Android's restriction for sideloaded apps. Second, for each target, connect once manually so the VPN permission is registered."),
                Block("انتخاب اپ‌ها و جستجو", "Choosing apps and searching",
                    "لیست همه برنامه‌های گوشی، حتی سیستمی‌ها، اینجاست. با کادر جستجو می‌توانی اسم برنامه را فارسی یا انگلیسی تایپ کنی و سریع پیداش کنی؛ مثلاً «تلگرام» یا «اینستا». تیک هر اپی را بزنی، باز شدنش اتصال هوشمند را فعال می‌کند.",
                    "The list holds every app on the phone, including system apps. The search box finds an app fast whether you type its name in Persian or English, for example “تلگرام” or “insta”. Ticking an app makes opening it trigger Smart Connect."),
                Block("فیلترشکن دستی تو هرگز مختل نمی‌شود", "Your hand-started VPN is never disturbed",
                    "اگر خودت دستی فیلترشکنی روشن کرده باشی — از همین برنامه یا از برنامه دیگر — اتصال هوشمند ساکت می‌ماند و هیچ کاری نمی‌کند تا وقتی خودت آن را قطع کنی. اتصال هوشمند هرگز فیلترشکن دستی تو را قطع نمی‌کند، و قطع خودکارش هم فقط برای فیلترشکنی است که خودش روشن کرده باشد، نه برای فیلترشکن دستی تو.",
                    "If you manually connected a VPN — from this app or another one — Smart Connect stays quiet and does nothing until you disconnect it yourself. Smart Connect never disconnects your hand-started VPN, and its auto-off only ever stops a VPN it started itself, never your manual one."),
                Block("قطع با ۵ ثانیه مهلت مینیمایز", "Disconnect after a 5-second minimise grace",
                    "اگر اپ انتخاب‌شده بیشتر از ۵ ثانیه جلوی صفحه نباشد — با دکمه برگشت، با پاک کردن از لیست اخیر، یا با رفتن به اپ دیگر یا صفحه اصلی — فیلترشکن قطع می‌شود. برگشت سریع‌تر از ۵ ثانیه (مثلاً یک سر زدن کوتاه به صفحه اصلی) قطع نمی‌کند. پس فیلترشکن فقط وقتی زنده می‌ماند که تو داخل اپ انتخاب‌شده باشی. فقط دو استثنا هست: بودن در داخل همین برنامه، و رد شدن گذرای سایه اعلان‌ها یا صفحه برنامه‌های اخیر، قطع نمی‌کند.",
                    "If the selected app stays away from the foreground for MORE than 5 seconds — by Back, by swiping it away, or by going to another app or the home screen — the VPN turns off. Coming back within 5 seconds (a quick hop to Home, for example) never cuts it. So the VPN only lives while you are inside the selected app. Only two exceptions: being inside this app itself, and the notification shade or recents screen flashing through, never cut it."),
                Block("قطع دستی و زنده ماندن در پس‌زمینه", "Manual disconnect and staying alive in the background",
                    "اگر خودت به‌صورت دستی قطع کنی، اتصال هوشمند پنج دقیقه صبر می‌کند تا دوباره وصل نشود. برای اینکه اتصال هوشمند فقط وقتی برنامه باز است کار نکند، بعد از ری‌استارت گوشی خودش دوباره راه می‌افتد؛ ولی اگر گوشی شیائومی (MIUI) داری، بهینه‌سازی باتری ممکن است آن را بکشد، پس در همین صفحه دکمه باتری را بزن و بهینه‌سازی باتری را برای این برنامه خاموش کن و اجازه Autostart را هم بده.",
                    "If you disconnect manually, Smart Connect waits five minutes before connecting again. To keep it working without the app open, it restarts itself after reboot; but on Xiaomi (MIUI) phones battery optimisation may kill it, so use the battery button on this page to turn battery optimisation off for this app and allow Autostart.")
            ))
            "chain" -> Triple("زنجیره دوتایی", "Double chain", listOf(
                Block("این بخش چیست", "What this section is",
                    "زنجیره دوتایی یعنی ترافیکت به‌جای یک فیلترشکن، از دو فیلترشکن پشت سر هم رد می‌شود: اول زنجیره اول، بعد زنجیره دوم، بعد اینترنت. مسیر دقیق: گوشی تو ← زنجیره اول ← زنجیره دوم ← سایت مقصد.",
                    "Double chain means your traffic passes through two VPN hops, one after the other, instead of one: first hop, then second hop, then the internet. The exact path: your phone → first hop → second hop → destination."),
                Block("این بخش به چه دردی می‌خورد", "What this section is for",
                    "۱) حریم خصوصی دو لایه: زنجیره اول آدرس واقعی تو را می‌بیند ولی مقصد را نمی‌فهمد؛ زنجیره دوم مقصد را می‌بیند ولی آدرس واقعی تو را نمی‌بیند. پس هیچ سروری به‌تنهایی هم هویت تو و هم مقصدت را با هم ندارد. ۲) دور زدن فیلترینگی که تک‌اتصال را می‌بندد: گاهی اتصال مستقیم به یک سرور بسته می‌شود ولی همان سرور از پشت یک سرور دیگر در دسترس است. ۳) ترکیب دو سرور سالم وقتی یکی کند یا ناپایدار است.",
                    "1) Two-layer privacy: the first hop sees your real address but not your destination; the second hop sees the destination but not your real address. So no single server holds both your identity and your destination. 2) Bypassing filtering that blocks a single connection: sometimes a direct connection to a server is blocked, but the same server is reachable from behind another server. 3) Combining two healthy servers when one is slow or unstable."),
                Block("ساختن زنجیره", "Building a chain",
                    "برای هر زنجیره نوعش را انتخاب کن: v2ray از لیست ذخیره‌شده‌ات یا با چسباندن دستی یک کانفیگ (هر دو زنجیره ورود دستی دارند)، اضطراری (بهترین سرور، خودکار و بدون ذخیره شدن)، OpenVPN با انتخاب پروفایل، یا WireGuard با انتخاب تونل. انتخاب‌ها همان لحظه ذخیره می‌شوند؛ دکمه وصل را بزن. با دکمه جابه‌جا کردن، جای دو زنجیره عوض می‌شود.",
                    "For each hop, pick its type: v2ray from your saved list or by pasting a config manually (both hops support manual paste), Emergency (the best server, automatic, nothing saved), OpenVPN by picking a profile, or WireGuard by picking a tunnel. Picks save at once; tap Connect. The swap button switches the two hops."),
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
                Block("گزارش", "Report",
                    "دکمه‌ی گزارش صفحه‌ی گزارش را باز می‌کند؛ آنجا قدم‌به‌قدم و به زبان ساده نوشته شده که هیدرا چه می‌کند: کدام منبع انتخاب شده، کدام کانفیگ با کدام شکل امتحان شد، کدام جواب نداد و چرا، کدام برنده شد، کی قطع شد و کی دوباره وصل شد؛ همه با ساعت دقیق.",
                    "The report button opens the report page; there, step by step and in plain language, it is written what Hydra does: which source was chosen, which config was tried with which shape, which one failed and why, which one won, when it dropped and when it reconnected — all with the exact time."),
                Block("آپشن‌ها", "Options",
                    "دکمه‌ی آپشن‌ها صفحه‌ی آپشن‌ها را باز می‌کند: منبع نقطه‌های اتصال (کانفیگ‌های v2ray خودت، اضطراری، هر دو، یا کانفیگی که خودت وارد می‌کنی)، حداکثر تعداد نقطه‌ها، حداکثر زمان چشیدن، شکل‌های قابل امتحان، نگهبانی خودکار و حافظه‌ی شبکه. تغییرها با دکمه‌ی ذخیره ثبت می‌شوند و برای شروع بعدی اعمال می‌شوند.",
                    "The options button opens the options page: the endpoint source (your own v2ray configs, emergency, both, or a config you paste yourself), the maximum number of endpoints, the maximum probe time, the shapes to try, auto-watch and network memory. Changes save with the save button and apply to the next start."),
                Block("محدودیت‌های صادقانه", "Honest limits",
                    "هیدرا خودش سرور ندارد؛ بدون کانفیگ‌های خودت یا بخش اضطراری چیزی برای شکل دادن ندارد. اگر شبکه کاملاً بسته باشد هیچ شکلی رد نمی‌شود. اتصال اول چند ثانیه طول می‌کشد چون چشیدن وقت می‌برد. ترفندهای سطح بسته (بسته‌ی جعلی با TTL کم و به‌هم‌ریختن ترتیب بسته‌ها) در این نسخه نیست و مرحله‌ی بعدی است که از روی پیاده‌سازی جاوایی PowerTunnel ساخته می‌شود.",
                    "Hydra has no servers of its own; without your configs or the emergency section it has nothing to shape. If the network is fully closed, no shape passes. The first connection takes seconds because tasting takes time. Packet-level tricks (fake low-TTL packets and packet disorder) are not in this version; they are the next phase, built from PowerTunnel's Java implementation.")
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
                    "با چرخ‌دنده بالای صفحه اصلی به تنظیمات می‌رسی: مرتب‌سازی برنامه‌ها، تغییر رمز ورود، ویجت صفحه اصلی، شخصی‌سازی ورود و همین راهنما.",
                    "The gear at the top of the home page opens Settings: sort apps, change entry password, home screen widgets, login personalisation and this guide."),
                Block("مرتب‌سازی با کشیدن", "Sorting by dragging",
                    "در مرتب‌سازی، آیکون هر برنامه دقیقاً مثل صفحه اصلی نشان داده می‌شود. روی آیکون نگه دار و با دست آن را بکش روی ردیفی که می‌خواهی، بعد «ذخیره» را بزن. بدون ذخیره، ترتیب عوض نمی‌شود.",
                    "In sorting, each app's icon is shown exactly like on the home page. Hold an icon and drag it onto the row you want, then tap Save. Without saving, the order does not change."),
                Block("تغییر رمز ورود", "Changing the entry password",
                    "رمز ورود را اینجا به رمز دلخواهت عوض می‌کنی: رمز فعلی، رمز جدید و تکرار رمز جدید را می‌نویسی. رمز جدید باید دقیقاً چهار رقم باشد.",
                    "Here you change the entry password to one of your choice: you enter the current password, the new password and its repeat. The new password must be exactly four digits."),
                Block("شخصی‌سازی ورود", "Login personalisation",
                    "صفحه ورود (ماشین‌حساب) را می‌توانی کامل به سلیقه خودت دربیاوری: ده مدل رنگ ترکیبی، پنج شکل دکمه (گرد، مربعی، دایره‌ای، کپسولی و نرم)، سه اندازه دکمه (کوچک، معمولی، بزرگ) و سه فونت نمایشگر (پررنگ، معمولی و تک‌فاصله‌ای). تغییر، دفعه بعد که صفحه ورود باز شود اعمال می‌شود و رمزها را عوض نمی‌کند.",
                    "You can fully restyle the login (calculator) page: ten mixed colour themes, five button shapes (round, square, circle, pill and soft), three key sizes (small, normal, large) and three display fonts (bold, normal and monospace). The change applies the next time the login page opens and does not change any password.")
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
                    "لینک‌های سابسکریبشن را ذخیره می‌کنی و با «به‌روزرسانی همه اشتراک‌ها» همه کانفیگ‌هایشان را یکجا می‌گیری. کانفیگ‌های تکراری به‌صورت نرمال‌شده حذف می‌شوند، یعنی کانفیگی که فقط اسمش فرق دارد یا ترتیب پارامترهایش فرق دارد، دوباره حساب نمی‌شود. لینک ساب فقط با حذف همان ردیف پاک می‌شود، نه خودکار.",
                    "You save subscription links and fetch all their configs at once with “Update all subscriptions”. Duplicate configs are removed in normalized form, so a config that only differs by name or parameter order is not counted twice. A subscription link is only deleted by deleting its own row, never automatically."),
                Block("تست پینگ", "Ping test",
                    "تست پینگ در یک سرویس پیش‌زمینه انجام می‌شود، پس با خاموش شدن صفحه یا بیرون رفتن از برنامه متوقف نمی‌شود. سالم‌ها همان لحظه که مشخص می‌شوند وارد کادر خروجی می‌شوند و لازم نیست تا آخر تست صبر کنی؛ دکمه «توقف تست پینگ» هم هست. اگر در کادر «نام دلخواه برای خروجی» نامی بنویسی، همه سالم‌ها دقیقاً همان نام را می‌گیرند.",
                    "The ping test runs in a foreground service, so it does not stop when the screen turns off or you leave the app. Healthy configs enter the output box the moment they are found; there is also a “Stop ping test” button. If you write a name in the “Desired name for output” box, all healthy configs get exactly that name."),
                Block("گزارش و خالی کردن کادرها", "Report and clearing the boxes",
                    "جلوی هر لینک ساب نوشته می‌شود که چند کانفیگ از آن گرفته شده و چندتایش سالم بوده است، و این گزارش بعد از بستن و باز کردن برنامه هم می‌ماند. از سه‌نقطه بالای صفحه، گزینه «خالی کردن همه کادرها» هست که با تأیید تو، هر سه کادر را خالی می‌کند.",
                    "In front of each subscription link it says how many configs were received from it and how many were healthy, and this report survives closing and reopening the app. From the top ⋮ menu, “Clear all boxes” empties all three boxes after your confirmation.")
            ))
            else -> Triple(key, key, listOf(
                Block("توضیح", "Explanation", "برای این بخش توضیحی ثبت نشده است.", "No explanation recorded for this section.")
            ))
        }
    }
}
