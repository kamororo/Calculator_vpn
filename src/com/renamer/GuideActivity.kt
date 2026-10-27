package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Guide page ("راهنمایی").
 *
 * One row per app section; tapping a row opens a GLASS-style
 * detail dialog: a rounded translucent panel (light translucent
 * white, or dark translucent when night mode is on, detected
 * via Theme.isDark) with a scrollable explanation and a Close
 * button, so long texts are fully readable.
 *
 * Every explanation describes REAL features of this app,
 * including the newest ones. If a feature is later removed,
 * its row must be removed here too.
 */
class GuideActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class Section(val titleFa: String, val titleEn: String, val summaryFa: String, val summaryEn: String, val bodyFa: String, val bodyEn: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("راهنمایی", "Guide"); textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "روی هر بخش بزن تا توضیح کاملش را ببینی.",
                "Tap a section to see its full explanation."
            )
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(16))
        })

        val isPublic = (intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC) != PinActivity.MODE_HIDDEN
        // Public mode: Rename and Subscriptions are NOT
        // listed at all, and no text hints that another
        // section exists.
        val rows = mutableListOf(
            Triple("v2ray", L("v2ray", "v2ray"), L("لیست سرورها، دکمه اتصال و پرچم کشور", "Server list, connect button and country flag")),
            Triple("wireguard", L("WireGuard", "WireGuard"), L("تونل رسمی با فایل .conf", "Official tunnel with .conf files")),
            Triple("openvpn", L("OpenVPN", "OpenVPN"), L("تونل رسمی OpenVPN3 با فایل .ovpn", "Official OpenVPN3 tunnel with .ovpn files")),
            Triple("emergency", L("اتصال اضطراری", "Emergency connect"), L("یک دکمه؛ کمی صبر تا به بهترین سرور وصل شود", "One button; wait a little for the best server")),
            Triple("smart", L("اتصال هوشمند", "Smart Connect"), L("وصل خودکار با باز شدن اپ، قطع خودکار با بسته شدنش", "Auto-connect when an app opens, auto-off when it closes")),
            Triple("chain", L("زنجیره دوتایی", "Double chain"), L("دو فیلترشکن پشت سر هم؛ هیچ سروری هم IP و هم مقصد را با هم نمی‌بیند", "Two VPN hops in a row; no server sees both your IP and destination")),
            Triple("hydra", L("هیدرا", "Hydra"), L("موتوری که شکل اتصال را روی شبکه‌ی زنده می‌چشد و برنده را نگه می‌دارد", "An engine that tastes connection shapes on the live network and keeps the winner")),
            Triple("widgets", L("ویجت‌ها", "Widgets"), L("آیکون واقعی هر بخش روی صفحه اصلی، با اندازه و رنگ دلخواه", "Each section's real icon on the home screen, sized and coloured your way")),
            Triple("notification", L("اعلان اتصال", "Connection notification"), L("سرعت، کشور و دکمه‌های قطع/وصل/ری‌استارت", "Speed, country and disconnect/connect/restart buttons")),
            Triple("homesettings", L("تنظیمات صفحه اصلی", "Home page settings"), L("مرتب‌سازی کشویی، تغییر رمز ورود و شخصی‌سازی ورود", "Drag sorting, entry password change and login personalisation"))
        )
        if (!isPublic) {
            rows += Triple("rename", L("تغییر نام کانفیگ", "Rename configs"), L("اسم جدید برای کانفیگ‌ها، خروجی زنده", "New names for configs, live output"))
            rows += Triple("subs", L("اشتراک‌ها و تست پینگ", "Subscriptions & ping test"), L("لینک ساب، تست در پس‌زمینه، گزارش هر ساب", "Subscription links, background test, per-sub report"))
        }
        val icons = mapOf(
            "v2ray" to "vpn.png", "wireguard" to "wg.png", "openvpn" to "ovpn.png",
            "emergency" to "vpn.png", "smart" to "smart.png", "chain" to "chain.png", "hydra" to "hydra.png", "widgets" to "sub.png",
            "notification" to "vpn.png", "homesettings" to "sub.png",
            "rename" to "v2ray.png", "subs" to "sub.png"
        )
        for ((key, title, summary) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener {
                    val i = Intent(this@GuideActivity, GuideDetailActivity::class.java)
                    i.putExtra("section", key)
                    startActivity(i)
                }
            }
            try {
                val bmp = android.graphics.BitmapFactory.decodeStream(assets.open(icons[key] ?: "vpn.png"))
                row.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            } catch (_: Exception) {}
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = title; textSize = 17f })
            col.addView(TextView(this).apply {
                text = summary; textSize = 13f
                setTextColor(Color.GRAY)
            })
            row.addView(col)
            root.addView(row)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setBackgroundColor(Color.parseColor("#DDDDDD"))
            })
        }

        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

}
