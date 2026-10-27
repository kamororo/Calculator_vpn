package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*

/** Hydra main page — deliberately short, so it
 *  can never fail to scroll again:
 *  icon, current source, one row of three glass
 *  options (options / report / guide) and, at the
 *  bottom, the big glass circle: "اتصال" when off,
 *  "توقف" when connected or probing. The circle
 *  itself IS the status — there is no separate
 *  status line anymore. Options live on their own
 *  page, the report on its own page. */
class HydraActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int): Int = HydraUi.dp(this, v)

    private lateinit var circleBtn: Button
    private lateinit var sourceLine: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }
        scroll.addView(root)
        setContentView(scroll)

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(HydraUi.glassButton(this, L("بازگشت", "Back")) { finish() })
        topRow.addView(TextView(this).apply {
            text = L("هیدرا", "Hydra")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), 0, 0)
        })
        root.addView(topRow)

        // Full icon, centred, never cut off: the
        // page scrolls and the icon has its own
        // fixed box, so it always shows whole.
        try {
            val bmp = BitmapFactory.decodeStream(assets.open("hydra.png"))
            root.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(24)
                }
            })
        } catch (_: Exception) {}

        // What will actually be used — visible at a
        // glance, so "I picked X but it used Y" can
        // never happen silently again.
        sourceLine = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(sourceLine)

        // Spacer pushes the bottom cluster down when
        // the page is short; when content grows, the
        // whole page scrolls instead.
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // Three options in one row: options, report,
        // guide — all glass.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(16))
        }
        fun optionBtn(text: String, onClick: () -> Unit) {
            row.addView(HydraUi.glassButton(this, text, onClick).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
            })
        }
        optionBtn(L("آپشن‌ها", "Options")) {
            startActivity(android.content.Intent(this, HydraOptionsActivity::class.java))
        }
        optionBtn(L("گزارش", "Report")) {
            startActivity(android.content.Intent(this, HydraReportActivity::class.java))
        }
        optionBtn(L("راهنما", "Guide")) { showWhatIsHydra() }
        root.addView(row)

        // The power control: a big glass circle at
        // the bottom of the page.
        circleBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            elevation = 8f
            setOnClickListener { onCircleTapped() }
        }
        root.addView(circleBtn)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        handler.postDelayed(poller, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun onCircleTapped() {
        if (HydraEngine.isActive(this) || HydraService.running) {
            HydraEngine.log(this, "هیدرا با دکمه‌ی تو متوقف شد.",
                "Hydra stopped by your button.")
            HydraService.stop(this)
        } else {
            HydraService.start(this)
        }
        refreshAll()
    }

    private fun refreshAll() {
        val opts = HydraEngine.loadOpts(this)
        sourceLine.text = L("منبع فعلی: ", "Current source: ") +
            HydraEngine.sourceLabel(this, opts.source)
        val active = HydraEngine.isActive(this) || HydraService.running
        when {
            HydraEngine.hydraOwnsVpn() -> {
                circleBtn.text = L("توقف", "Stop")
                circleBtn.background = HydraUi.glassCircleBg(
                    Color.parseColor("#D9C8EAD4"), Color.parseColor("#FF2E9E5B"))
                circleBtn.setTextColor(Color.parseColor("#FF1B5E33"))
            }
            active -> {
                circleBtn.text = L("توقف", "Stop")
                circleBtn.background = HydraUi.glassCircleBg(
                    Color.parseColor("#D9FDEBC8"), Color.parseColor("#FFE8913A"))
                circleBtn.setTextColor(Color.parseColor("#FF7A4A00"))
            }
            else -> {
                circleBtn.text = L("اتصال", "Connect")
                circleBtn.background = HydraUi.glassCircleBg(
                    Color.parseColor("#D9FFFFFF"), Color.parseColor("#FF9AA0A6"))
                circleBtn.setTextColor(Color.BLACK)
            }
        }
    }

    private fun showWhatIsHydra() {
        val text = AppLanguage.t(this, HYDRA_FA, HYDRA_EN)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(L("هیدرا چیست؟", "What is Hydra?"))
            .setView(sv)
            .setPositiveButton(L("باشه", "OK"), null)
            .show()
    }

    companion object {
        private val HYDRA_FA = """
هیدرا یک موتور تازه نیست که از صفر نوشته شده باشد؛ یک «روش وصل شدن» است که روی موتورهای اثبات‌شده سوار می‌شود. درس بخش OpenVPN همین بود: موتور را از صفر نساز، موتور برنامه‌ی اصلی را بردار. هیدرا هم دقیقاً همین کار را می‌کند.

مشکل موتورهای معمولی چیست؟
هر فیلترشکن یک شکل ثابت دارد: vless همیشه یک‌جور دست‌تکانی می‌دهد، وایرگارد همیشه یک‌جور، سایفون همیشه یک‌جور. فیلتر همان شکل را یاد می‌گیرد و می‌بندد. پس هر چقدر هم سرورت خوب باشد، شکل ثابتت لو می‌رود.

هیدرا چه می‌کند؟
هیدرا شکل ثابت ندارد. وقتی دکمه‌اش را می‌زنی، اول شبکه‌ی خودت را «می‌چشد»: نقطه‌های اتصال تو را (کانفیگ‌های v2ray خودت، کانفیگ‌های تازه‌ی بخش اضطراری، هر دو، یا کانفیگی که خودت وارد می‌کنی) با چند شکل مختلف امتحان می‌کند؛ هر امتحان یک اتصال واقعی است، نه نمایش. شکلی که واقعاً وصل شود و تونلش جواب بدهد، برنده است و همان می‌ماند. اگر وسط کار فیلتر عوض شد و اتصال افتاد، هیدرا خودش دوباره می‌چشد و با یک شکل تازه وصل می‌شود؛ مثل هیدرای افسانه‌ای که سرش را بزنی، سر تازه درمی‌آورد. و یادش می‌ماند که روی این شبکه کدام ترکیب جواب داد، تا دفعه‌ی بعد اول همان را امتحان کند و سریع‌تر وصل شود.

این روش از کجا آمده؟
روش «چشیدن و نگه‌داشتن برنده» از Geneva آمده؛ سیستمی از دانشگاه مریلند که استراتژی‌های دور زدن فیلتر را به‌صورت خودکار روی شبکه‌ی زنده کشف می‌کند و در برابر فیلترهای واقعی، از جمله فیلتر ایران، جواب داده است. موتور Geneva خودش روی اندروید بدون روت اجرا نمی‌شود، پس روشش و چهار عمل اصلی‌اش (تکه‌تکه کردن، دست‌کاری، تکثیر، حذف) برداشته شده و روی موتور Xray برنامه (همان موتور بخش v2ray) پیاده شده است. شکل‌های قابل انتخاب هم از کاتالوگ واقعی GoodbyeDPI و ByeDPI و PowerTunnel آمده‌اند: تکه‌تکه کردن بسته‌ها، اثر انگشت TLS، تغییر مسیر به WebSocket و gRPC، و Mux. یعنی هیچ بخش اصلی‌اش اختراع تازه نیست؛ ترکیب روش اثبات‌شده با موتور اثبات‌شده است.

هیدرا به چه چیزی نیاز دارد؟
از تو هیچ کانفیگی نمی‌خواهد؛ ولی خودش سرور ندارد. نقطه‌ی اتصال را از کانفیگ‌های v2ray خودت، از بخش اضطراری، از هر دو، یا از کانفیگی که خودت در صفحه‌ی آپشن‌ها وارد می‌کنی می‌گیرد. اگر هیچ‌کدام نباشد، چیزی برای شکل دادن ندارد. و برای اینکه مجوز VPN ثبت شده باشد، اول یک بار از بخش v2ray به‌صورت دستی وصل شو. اگر به کانفیگ اضطراری وصل شد، اسم کانفیگ هیچ‌جا فرستاده و نشان داده نمی‌شود؛ فقط نوشته می‌شود «به کانفیگ اضطراری وصل شد».

گزارش چه می‌گوید؟
دکمه‌ی گزارش، صفحه‌ی گزارش را باز می‌کند؛ آنجا قدم‌به‌قدم و به زبان ساده نوشته شده که هیدرا چه می‌کند: کدام منبع انتخاب شده، کدام کانفیگ با کدام شکل امتحان شد، کدام جواب نداد و چرا، کدام برنده شد، کی اتصال افتاد و کی دوباره وصل شد؛ همه با ساعت دقیق.

آپشن‌ها چه هستند؟
دکمه‌ی آپشن‌ها صفحه‌ی آپشن‌ها را باز می‌کند: منبع نقطه‌های اتصال، حداکثر تعداد نقطه‌ها، حداکثر زمان چشیدن، شکل‌های قابل امتحان، نگهبانی خودکار و حافظه‌ی شبکه. تغییرها با دکمه‌ی ذخیره ثبت می‌شوند و برای شروع بعدی اعمال می‌شوند.

محدودیت‌های صادقانه:
اگر شبکه کاملاً بسته باشد و هیچ مسیری به بیرون نباشد، هیچ شکلی رد نمی‌شود. اتصال اول کمی طول می‌کشد، چون چشیدن چند ثانیه وقت می‌برد؛ ولی اتصال‌های بعدی با حافظه‌اش سریع‌تر می‌شوند. و مرحله‌ی بعدی هیدرا، که هنوز در این نسخه نیست، آوردن ترفندهای سطح بسته (بسته‌ی جعلی با TTL کم و به‌هم‌ریختن ترتیب بسته‌ها) از روی پیاده‌سازی جاوایی PowerTunnel است؛ آن ترفندها به کنترل سطح بسته نیاز دارند و جداگانه ساخته می‌شوند، نه با اختراع دوباره.
""".trimIndent()

        private val HYDRA_EN = """
Hydra is not a brand-new engine written from scratch; it is a way of connecting, built on proven engines. That was the lesson of the OpenVPN section: do not write the engine yourself, take the original app's engine. Hydra does exactly that.

What is wrong with normal engines?
Every VPN has a fixed shape: VLESS always shakes hands one way, WireGuard another, Psiphon another. The filter learns that shape and blocks it. No matter how good your server is, your fixed shape gives you away.

What does Hydra do?
Hydra has no fixed shape. When you tap it, it first "tastes" your network: it tries your endpoints (your own v2ray configs, fresh emergency configs, both, or a config you paste yourself) in several shapes; each try is a real connection, not a show. The shape that truly connects, with a working tunnel, wins and stays. If the filter changes mid-connection and it drops, Hydra tastes again on its own and reconnects with a fresh shape — like the mythical hydra that grows a new head when one is cut off. And it remembers which combination worked on this network, so next time it tries that one first and connects faster.

Where does this come from?
The "taste and keep the winner" method comes from Geneva, a University of Maryland system that discovers censorship-evasion strategies automatically against live networks, proven against real filters including Iran's. Geneva's own engine needs root, so it cannot run on Android without root; its method and its four primitives (fragment, tamper, duplicate, drop) were taken and implemented on this app's Xray engine (the v2ray section's engine). The selectable shapes come from the real GoodbyeDPI, ByeDPI and PowerTunnel catalog: packet fragmentation, TLS fingerprinting, WebSocket and gRPC transports, and mux. So no core part of it is a fresh invention; it is a proven method on a proven engine.

What does Hydra need?
No config from you; but it has no servers of its own. It takes endpoints from your v2ray configs, the emergency section, both, or a config you paste on the options page. With none, it has nothing to shape. And connect once manually from the v2ray section first, so the VPN permission is registered. If it connects to an emergency config, the config's name is never sent or shown anywhere; it only says "connected to an emergency config".

What does the report say?
The report button opens the report page; there, step by step and in plain language, it is written what Hydra does: which source was chosen, which config was tried with which shape, which one failed and why, which one won, when the connection dropped and when it reconnected — all with the exact time.

What are the options?
The options button opens the options page: the endpoint source, the maximum number of endpoints, the maximum probe time, the shapes to try, auto-watch and network memory. Changes save with the save button and apply to the next start.

Honest limits:
If the network is fully closed with no route out at all, no shape passes. The first connection takes a little longer, because tasting takes seconds; later connections are faster thanks to memory. And Hydra's next phase, not in this version yet, is packet-level tricks (fake low-TTL packets and packet disorder) ported from PowerTunnel's Java implementation; those need packet-level control and are built separately, not re-invented.
""".trimIndent()
    }
}
