package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/** SlipNet Settings, with every option from the SlipNet
 *  app's Settings screenshots, grouped exactly as there:
 *  Connection, Tools, Proxy Settings, DNS, Network,
 *  Split Tunneling, Domain Routing, Geo-Bypass, SSH Tunnel,
 *  Appearance, Debug, Reset. Values live in
 *  renamer_slipnet_settings and apply on next connection. */
class SlipNetSettingsActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SlipNetStore.settings(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = L("تنظیمات", "Settings"); textSize = 24f; setTextColor(Color.WHITE)
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        }
        root.addView(scroll)
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back"); setOnClickListener { finish() }
        })
        setContentView(root)

        fun header(fa: String, en: String) {
            list.addView(TextView(this).apply {
                text = L(fa, en); textSize = 17f; setTextColor(Color.parseColor("#A9C6FF"))
                setPadding(0, 28, 0, 8)
            })
        }
        fun toggle(key: String, fa: String, en: String, descFa: String, descEn: String, def: Boolean = false) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 12, 0, 12) }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            txt.addView(TextView(this).apply { text = L(fa, en); textSize = 16f; setTextColor(Color.WHITE) })
            txt.addView(TextView(this).apply { text = L(descFa, descEn); textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")) })
            val sw = Switch(this).apply {
                isChecked = prefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
            }
            row.addView(txt); row.addView(sw); list.addView(row)
        }
        fun field(key: String, fa: String, en: String, def: String) {
            list.addView(TextView(this).apply { text = L(fa, en); textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 4) })
            val e = EditText(this).apply {
                setText(prefs.getString(key, def) ?: def)
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { _, has -> if (!has) prefs.edit().putString(key, text.toString()).apply() }
            }
            list.addView(e)
        }
        fun button(fa: String, en: String, f: () -> Unit) {
            list.addView(Button(this).apply { text = L(fa, en); setOnClickListener { f() } })
        }

        header("اتصال", "Connection")
        toggle("auto_connect_on_boot", "اتصال خودکار هنگام روشن شدن گوشی", "Auto-connect on boot", "هنگام روشن شدن گوشی به‌صورت خودکار وصل شود", "Automatically connect when device starts")
        toggle("proxy_only", "حالت فقط پروکسی", "Proxy-only mode", "پروکسی SOCKS5 بدون ساختن تونل VPN", "Expose SOCKS5 proxy without creating VPN tunnel")
        toggle("kill_switch", "کیل سوییچ", "Kill switch", "اگر اتصال VPN قطع شد، همه ترافیک مسدود شود", "Block all traffic if VPN connection drops")
        toggle("auto_reconnect", "اتصال مجدد خودکار", "Auto-reconnect", "اگر VPN به‌صورت غیرمنتظره قطع شد، دوباره وصل شود", "Automatically reconnect if VPN drops unexpectedly", true)
        toggle("notif_traffic", "شمارنده ترافیک در اعلان", "Notification traffic counter", "سرعت آپلود/دانلود و مصرف داده در اعلان نشان داده شود", "Show upload/download speed and data usage in notification", true)
        field("sleep_timer", "تایمر خواب (دقیقه، ۰ یعنی خاموش)", "Sleep timer (minutes, 0 = off)", "0")
        toggle("battery_exempt", "بهینه‌سازی باتری: معاف", "Battery optimization: exempted", "VPN در پس‌زمینه به‌صورت قابل اعتماد اجرا می‌شود", "VPN will run reliably in background", true)

        header("ابزارها", "Tools")
        button("اسکنر ریزالور DNS", "DNS Resolver Scanner") { showScanner() }

        header("تنظیمات پروکسی", "Proxy Settings")
        field("listen_addr", "آدرس گوش دادن", "Listen Address", "127.0.0.1")
        field("listen_port", "پورت گوش دادن", "Listen Port", "1080")
        toggle("proxy_auth", "احراز هویت پروکسی", "Proxy Authentication", "هر اپی بدون اطلاعات ورود از پروکسی محلی استفاده می‌کند", "Any app can use the local proxy without credentials")
        toggle("http_proxy", "پروکسی HTTP", "HTTP proxy", "پروکسی HTTP برای دستگاه‌هایی که SOCKS5 پشتیبانی نمی‌کنند", "Enable HTTP proxy for devices that don't support SOCKS5")
        toggle("append_http_proxy", "افزودن پروکسی HTTP به VPN", "Append HTTP Proxy to VPN", "ترافیک اپ از راه پروکسی HTTP مستقیم برود، بدون TUN", "Route app traffic through HTTP proxy directly, bypassing TUN")

        header("DNS", "DNS")
        toggle("global_resolver", "بازنویسی سراسری ریزالور", "Global resolver override", "یک لیست ریزالور ثابت برای همه پروفایل‌ها اجبار شود", "Force a fixed resolver list across all profiles")
        toggle("dns_pool", "استخر DNS", "DNS pool", "در هر اتصال، سریع‌ترین ریزالورها به‌صورت خودکار انتخاب شوند", "Auto-pick the fastest resolvers on each connect", true)
        toggle("http_ssh_verify", "تأیید HTTP/SSH", "HTTP/SSH verification", "فقط دست‌تکانی — اسکن سریع‌تر", "Handshake-only — faster scan")
        field("remote_dns", "سرور DNS راه‌دور", "Remote DNS server", "8.8.8.8, 1.1.1.1")
        field("dns_workers", "کارگران DNS", "DNS workers", "per-query")
        toggle("prevent_dns_fallback", "جلوگیری از بازگشت DNS", "Prevent DNS Fallback", "اگر تونل SSH قطع باشد، کوئری‌های DNS ناموفق می‌شوند (بدون نشت)", "DNS queries fail if SSH tunnel is down (no leak)", true)

        header("شبکه", "Network")
        toggle("disable_quic", "غیرفعال کردن QUIC", "Disable QUIC", "پروتکل QUIC مسدود شود تا TCP اجباری شود", "Block QUIC protocol to force TCP", true)
        field("vpn_mtu", "MTU وی‌پی‌ان", "VPN MTU", "1280")
        field("bandwidth", "محدودیت پهنای باند", "Bandwidth Limit", "Unlimited / Unlimited")

        header("تونل‌زنی جداگانه", "Split Tunneling")
        toggle("split_tunnelling", "فعال کردن تونل‌زنی جداگانه", "Enable split tunneling", "انتخاب کن کدام اپ‌ها از VPN استفاده کنند", "Choose which apps use the VPN")

        header("مسیریابی دامنه", "Domain Routing")
        toggle("domain_routing", "فعال کردن مسیریابی دامنه", "Enable domain routing", "دامنه‌های خاص از راه VPN یا دور VPN مسیریابی شوند", "Route specific domains through or around the VPN")

        header("دور زدن جغرافیایی", "Geo-Bypass")
        toggle("geo_bypass", "فعال کردن دور زدن جغرافیایی", "Enable geo-bypass", "ترافیک داخلی مستقیم برود، VPN برای سایت‌های محلی دور زده شود", "Route domestic traffic directly, bypass VPN for local sites")

        header("تونل SSH", "SSH Tunnel")
        field("ssh_cipher", "رمزنگاری SSH", "Cipher", "Auto (Fastest)")
        toggle("ssh_compression", "فشرده‌سازی", "Compression", "داده از راه SSH فشرده شود", "Compress data through SSH (helps on slow links)")
        field("ssh_max_channels", "حداکثر کانال‌ها", "Max Channels", "16")

        header("ظاهر", "Appearance")
        toggle("dark_mode", "حالت تیره", "Dark mode", "پیروی از سیستم", "Follow system")

        header("اشکال‌زدایی", "Debug")
        toggle("debug_logging", "لاگ اشکال‌زدایی", "Debug logging", "لاگ مفصل برای عیب‌یابی فعال شود", "Enable verbose logging for troubleshooting")

        header("بازنشانی", "Reset")
        button("بازنشانی همه تنظیمات", "Reset all settings") {
            AlertDialog.Builder(this)
                .setMessage(L("همه تنظیمات به مقادیر پیش‌فرض برگردد؟", "Restore all settings to their default values?"))
                .setPositiveButton(L("بله", "Yes")) { _, _ ->
                    prefs.edit().clear().apply()
                    Toast.makeText(this, L("تنظیمات بازنشانی شد.", "Settings reset."), Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(L("لغو", "Cancel"), null).show()
        }
    }

    private fun showScanner() {
        val profiles = SlipNetStore.load(this)
        val resolvers = profiles.flatMap { it.get("resolvers").split(",").map { s -> s.trim() } }.filter { it.isNotBlank() }.distinct()
        val text = if (resolvers.isEmpty()) L("پروفایلی با ریزالور پیدا نشد.", "No profile with resolvers found.")
        else resolvers.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle(L("اسکنر ریزالور DNS", "DNS Resolver Scanner"))
            .setMessage(L("ریزالورهای پروفایل‌هایت:\n", "Your profiles' resolvers:\n") + text + "\n\n" + L("اسکن کامل ریزالورها (تست کارکرد واقعی هر ریزالور) در بیلد بعدی اسلیپ‌نت می‌آید.", "Full resolver scanning (testing each resolver for real) comes in the next SlipNet build."))
            .setPositiveButton(L("بستن", "Close"), null).show()
    }
}
