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

    // UPDATE WITH EVERY BUILD — newest first, max 10, drop oldest.
    // با هر بیلد به‌روز کن — جدیدترین اول، حداکثر ۱۰، قدیمی‌ترین را حذف کن.
    private val VERSIONS = listOf(
        VersionEntry("2.68", 184,
            "قابلیت‌های موتور هوشمند از نسخه تست",
            listOf(
                "قابلیت‌های موتور هوشمند نسخه تست به نسخه اصلی آمد: پروفایل‌های خودکار جانک وایرگارد و آمنزیا",
                "موتور حالا برای هدف وایرگارد در اسمارت کانکت هم کار می‌کند",
                "برنده را سرعت دانلود واقعی انتخاب می‌کند، نه فقط پینگ",
                "فینگرپرینت‌ها و پورت‌های بیشتر، مالتی‌پلکس خودکار و یادگیری روش برنده برای هر شبکه",
                "لبه‌های CDN بیشتر، از جمله آروان‌کلاد",
                "همه این‌ها فقط وقتی موتور روشن است؛ وقتی خاموش است برنامه دقیقاً مثل قبل کار می‌کند؛ تاریخچه نسخه‌ها هم حالا ۱۰ به‌روزرسانی آخر را با تغییرنامه دقیق نشان می‌دهد"
            ),
            listOf(
                "Smart-engine features from the test build came to the main app: automatic WireGuard and Amnezia junk profiles",
                "The engine now also works for WireGuard targets in Smart Connect",
                "The winner is picked by real download speed, not just ping",
                "More fingerprints and ports, automatic Mux, and per-network learning of the winning method",
                "More CDN edges, including ArvanCloud",
                "All of this only while the engine is ON; with it OFF the app works exactly as before; version history now shows the last 10 updates with exact changelogs"
            )),
        VersionEntry("2.67", 183,
            "حذف کامل وارپ",
            listOf(
                "وارپ کاملاً حذف شد؛ این به‌روزرسانی فقط همین تغییر را دارد",
                "دکمه وارپ رایگان و تونل ذخیره‌شده وارپ از صفحه وایرگارد برداشته شد",
                "گزینه‌های وارپ از اضطراری حذف شد و اضطراری دوباره فقط با اشتراک کار می‌کند",
                "همه کدهای وارپ از برنامه پاک شد"
            ),
            listOf(
                "WARP was removed completely; this update is only that change",
                "The free-WARP button and the saved WARP tunnel were removed from the WireGuard page",
                "WARP options were removed from Emergency, which is back to subscription-only",
                "All WARP code was removed from the app"
            )),
        VersionEntry("2.66", 182,
            "وارپ از راه Xray",
            listOf(
                "وارپ از راه هسته Xray وصل می‌شود: خروجی وایرگارد با مقدار reserved",
                "بک‌اند AWG مقدار reserved را پشتیبانی نمی‌کند",
                "به همین دلیل قبلاً هندشیک هرگز جواب نمی‌داد"
            ),
            listOf(
                "WARP now connects through the Xray core: a WireGuard outbound with the reserved value",
                "The AWG backend has no reserved support",
                "That is why the handshake never answered before"
            )),
        VersionEntry("2.65", 181,
            "رفع خطای تجزیه وارپ",
            listOf(
                "نقطه پایانی به‌صورت JSON خام ثبت‌نام ذخیره می‌شد و خطای ParseException می‌داد",
                "نقطه پایانی هنگام خواندن نرمال می‌شود و Reserved از متن وایرگارد برداشته شد",
                "خودآزمایی و مهاجرت خودکار اضافه شد"
            ),
            listOf(
                "The endpoint was stored as raw registration JSON and caused a ParseException",
                "The endpoint is now normalized at read time, and Reserved was removed from the WireGuard text",
                "Self-validation and automatic migration were added"
            )),
        VersionEntry("2.64", 180,
            "رمزنگاری مسنجر و وارپ رایگان",
            listOf(
                "مسنجر حالا خودکار رمزنگاری سرتاسری دارد: کلید AES-GCM برای هر گفتگو و هر طرف",
                "کلید فقط در بخش تکه لینک اشتراکی می‌رود؛ روی هاست فقط متن رمز شده ذخیره می‌شود",
                "دکمه وارپ رایگان در صفحه وایرگارد و انتخاب‌گر وارپ / وارپ+اشتراک در اضطراری"
            ),
            listOf(
                "Messenger now has automatic end-to-end encryption: an AES-GCM key per conversation per side",
                "The key travels only inside the shared link's fragment; the host stores only ciphertext",
                "Free WARP button on the WireGuard page and a WARP / WARP+Sub picker in Emergency"
            )),
        VersionEntry("2.63", 179,
            "پیش‌پینگ اضطراری",
            listOf(
                "صفحه اضطراری هنگام ورود، خودکار مسابقه پینگ می‌گیرد تا آماده باشد",
                "نتیجه یک‌بار مصرف در حافظه موقت می‌ماند و با یک لمس دستی مصرف می‌شود",
                "با ترک صفحه، پیش‌پینگ لغو می‌شود"
            ),
            listOf(
                "The Emergency page now runs an automatic ping race on entry so it is ready",
                "The result is kept in a one-shot cache and consumed by one manual tap",
                "Leaving the page cancels the pre-ping"
            )),
        VersionEntry("2.62", 178,
            "رفع به‌هم‌ریختگی اشتراک‌ها و دکمه حذف اسمارت",
            listOf(
                "به‌هم‌ریختگی کارت‌های اشتراک رفع شد",
                "گزارش کامل تست پشت دکمه «مشاهده گزارش تست»",
                "حذف اسمارت قوی‌تر شد و دکمه حذف به شکل قرص کوچک و دیده‌شونده آمد"
            ),
            listOf(
                "The subscription cards garble was fixed",
                "The full test report sits behind the “view test report” button",
                "Smart Kill was strengthened, and its toggle is now a small visible pill"
            )),
        VersionEntry("2.61", 177,
            "اضطراری: بدون مسیر سریع، با فهرست مرده و وصل خودکار",
            listOf(
                "مسیر سریع اضطراری حذف شد؛ همه چیز از مسیر مشترک اتصال می‌گذرد",
                "پینگ ۱- دیگر برنمی‌گردد",
                "فهرست مرده فقط اثر انگشت نگه می‌دارد و بعد از ۲۴ ساعت پاک می‌شود",
                "تا وقتی صفحه اضطراری باز است، تا ۳ دور وصل خودکار تلاش می‌کند"
            ),
            listOf(
                "Emergency's fast path was removed; everything now goes through the shared connect flow",
                "Ping -1 is never returned again",
                "The dead list keeps fingerprints only and is pruned after 24 hours",
                "Auto-reconnect tries up to 3 rounds while the Emergency page stays open"
            )),
        VersionEntry("2.60", 176,
            "رفع زنجیره وایرگارد و آیکون VPN",
            listOf(
                "تلاش برای رفع زنجیره وایرگارد: نقطه پایانی دامنه قبل از اتصال به IP تبدیل می‌شود",
                "رفع آیکون VPN: توصیف‌گر TUN بلافاصله پس از توقف بسته می‌شود و زنجیره اوپن‌وی‌پی‌ان ناموفق بعد از ۱۰ ثانیه خراب می‌شود",
                "بخش زنجیره حالا حکم می‌دهد: عبور ترافیک اثبات‌شده یا بدون ترافیک مرده",
                "نوشته وایرگارد در مانیتورینگ اصلاح شد"
            ),
            listOf(
                "WireGuard chain fix attempt: a domain endpoint is pre-resolved to IP before connecting",
                "VPN icon fixes: the TUN fd is closed immediately on stop, and a failed OpenVPN chain is torn down after 10 seconds",
                "The chain section now gives a verdict: traffic passed PROVEN or no traffic DEAD",
                "WireGuard wording in Monitoring was fixed"
            )),
        VersionEntry("2.59", 175,
            "گزارش موتور از لحظه روشن شدن",
            listOf(
                "جعبه شمارش تنظیمات صفحه اصلی کوچک‌تر شد و ردیف‌ها برچسب فارسی سمت راست گرفتند",
                "دکمه گزارش موتور به محض روشن شدن موتور دیده می‌شود، نه فقط بعد از اولین گزارش؛ و زیباتر شد",
                "نوشته کارت موتور در مانیتورینگ اصلاح شد: «در بخش‌های اتصال فعال است»",
                "بخش تازه در موتور: کجا کمک می‌کند و کجا هیچ کاری نمی‌کند"
            ),
            listOf(
                "The Home Settings count box is smaller, and its rows now have Persian labels on the right",
                "The engine report button appears as soon as the engine is ON, not only after the first report; and it is prettier",
                "Monitoring's engine card wording was fixed to “active in the connection sections”",
                "A new engine section: where it helps and where it does nothing"
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
