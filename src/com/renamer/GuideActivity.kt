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

    /** Crops a bitmap to the bounding box of its
     *  opaque pixels (alpha > 16). Galaxy icons carry
     *  different transparent padding, which used to
     *  make rows look unevenly spaced; after cropping,
     *  every glyph fills the same 40dp box evenly.
     *  Returns the original when there is nothing to
     *  crop (opaque or fully transparent). */
    private fun cropTransparent(src: android.graphics.Bitmap): android.graphics.Bitmap {
        return try {
            if (!src.hasAlpha()) return src
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = (px[y * w + x] ushr 24) and 0xFF
                    if (a > 16) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) src
            else android.graphics.Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        } catch (_: Exception) { src }
    }

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
            gravity = if (AppLanguage.isEnglish(this@GuideActivity)) Gravity.LEFT else Gravity.RIGHT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "روی هر بخش بزن تا توضیح کاملش را ببینی.",
                "Tap a section to see its full explanation."
            )
            textSize = 13f; setTextColor(Color.GRAY)
            gravity = if (AppLanguage.isEnglish(this@GuideActivity)) Gravity.LEFT else Gravity.RIGHT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(16))
        })

        val isPublic = (intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC) != PinActivity.MODE_HIDDEN
        // Public mode: Rename and Subscriptions are NOT
        // listed at all, and no text hints that another
        // section exists.
        val rows = mutableListOf(
            Triple("home", L("صفحه اصلی", "Home page"), L("سه کاشی در هر ردیف، دکمه‌های بالا و نسخه برنامه", "Three tiles per row, top buttons and app version")),
            Triple("v2ray", L("v2ray", "v2ray"), L("لیست سرورها، دکمه اتصال و پرچم کشور", "Server list, connect button and country flag")),
            Triple("engine", L("موتور هوشمند", "Smart Engine"), L("موج‌های موازی تعمیر، پرش، واپ و نگهبان", "Parallel repair waves, jump, WARP and watchdog")),
            Triple("wireguard", L("وایرگارد و امنزیا", "WireGuard & Amnezia"), L("هر دو نوع با موتور امنزیا وصل می‌شوند", "Both kinds connect through the Amnezia engine")),
            Triple("openvpn", L("OpenVPN", "OpenVPN"), L("تونل رسمی OpenVPN3 با فایل .ovpn", "Official OpenVPN3 tunnel with .ovpn files")),
            Triple("emergency", L("اتصال اضطراری", "Emergency connect"), L("یک دکمه؛ کمی صبر تا به بهترین سرور وصل شود", "One button; wait a little for the best server")),
            Triple("smart", L("اتصال هوشمند", "Smart Connect"), L("وصل خودکار با باز شدن اپ، قطع خودکار با بسته شدنش", "Auto-connect when an app opens, auto-off when it closes")),
            Triple("chain", L("زنجیره دوتایی", "Double chain"), L("دو فیلترشکن پشت سر هم؛ هیچ سروری هم IP و هم مقصد را با هم نمی‌بیند", "Two VPN hops in a row; no server sees both your IP and destination")),
            Triple("hydra", L("هیدرا", "Hydra"), L("موتوری که شکل اتصال را روی شبکه‌ی زنده می‌چشد و برنده را نگه می‌دارد", "An engine that tastes connection shapes on the live network and keeps the winner")),
            Triple("cloudflare", L("کلودفلر", "Cloudflare"), L("مدیریت پنل نهان خودتان: کاربرها، آی‌پی‌ها، کشور پروکسی و اشتراک", "Manage your own Nahan panel: users, IPs, proxy country and subscription")),
            Triple("widgets", L("ویجت‌ها", "Widgets"), L("آیکون واقعی هر بخش روی صفحه اصلی، با اندازه و رنگ دلخواه", "Each section's real icon on the home screen, sized and coloured your way")),
            Triple("notification", L("اعلان اتصال", "Connection notification"), L("سرعت، کشور و دکمه‌های قطع/وصل/ری‌استارت", "Speed, country and disconnect/connect/restart buttons")),
            Triple("homesettings", L("تنظیمات صفحه اصلی", "Home page settings"), L("ردیف‌های فشرده آیکون‌دار: مرتب‌سازی، رمز ورود و شخصی‌سازی", "Compact icon rows: sorting, entry password and personalisation"))
        )
        if (!isPublic) {
            rows += Triple("rename", L("تغییر نام کانفیگ", "Rename configs"), L("اسم جدید برای کانفیگ‌ها، خروجی زنده", "New names for configs, live output"))
            rows += Triple("subs", L("اشتراک‌ها و تست پینگ", "Subscriptions & ping test"), L("لینک ساب، تست در پس‌زمینه، گزارش هر ساب", "Subscription links, background test, per-sub report"))
        }
        val icons = mapOf(
            "home" to "sub.png", "v2ray" to "vpn.png", "engine" to "vpn.png",
            "wireguard" to "wg.png", "openvpn" to "ovpn.png",
            "emergency" to "emergency.png", "smart" to "smart.png", "chain" to "chain.png", "hydra" to "hydra.png", "widgets" to "sub.png",
            "notification" to "vpn.png", "homesettings" to "sub.png",
            "rename" to "v2ray.png", "subs" to "sub.png", "cloudflare" to "cf.png"
        )

    // 2.22: cropTransparent is defined below and used
    // for every row icon, so PNGs with different
    // transparent padding all sit evenly.
        for ((key, title, summary) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    val i = Intent(this@GuideActivity, GuideDetailActivity::class.java)
                    i.putExtra("section", key)
                    startActivity(i)
                }
            }
            // Icon ALWAYS on the right, text on its left, in both
            // languages: the row is forced LTR, the text column
            // comes first (weight 1), the icon last. FA text is
            // right-aligned (toward the icon), EN text starts at
            // the left edge — so Latin titles no longer jump to
            // the opposite side from Persian ones.
            val textGravity = if (AppLanguage.isEnglish(this)) Gravity.LEFT else Gravity.RIGHT
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = title; textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                gravity = textGravity
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            col.addView(TextView(this).apply {
                text = summary; textSize = 12f
                setTextColor(Color.GRAY)
                gravity = textGravity
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(col)
            try {
                val bmp = android.graphics.BitmapFactory.decodeStream(assets.open(icons[key] ?: "vpn.png"))
                row.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(cropTransparent(bmp))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        setMargins(dp(12), 0, 0, 0)
                    }
                })
            } catch (_: Exception) {}
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
            textSize = 13f
            isAllCaps = false
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#15000000"))
                setStroke(1, Color.parseColor("#22000000"))
            }
            setPadding(dp(24), dp(10), dp(24), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

}
