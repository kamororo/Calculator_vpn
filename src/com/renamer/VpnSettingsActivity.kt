package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

/** Full v2ray settings page, modelled on v2rayNG's
 *  Settings page and kept in the same section order:
 *  UI, VPN, Core, Mux, Fragment, Observatory,
 *  Advanced, Mode. Every value lives in the
 *  "renamer_vpn" SharedPreferences under the key
 *  named in each row helper call, and VpnConfigBuilder
 *  reads the keys it can actually apply to the Xray
 *  JSON; keys Xray has no field for (Hev TUN, root
 *  mode, LAN sharing) are stored only
 *  and are marked as such in the row description, so
 *  nothing here pretends to do more than it does. */
class VpnSettingsActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun prefs() = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title; textSize = 18f
            setTextColor(Color.parseColor("#FF8C00"))
            setPadding(0, dp(20), 0, dp(8))
        })
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
    }

    /** One row: title 16sp, description 13sp gray,
     *  control underneath, generous vertical padding
     *  so rows can never run into each other. */
    private fun row(root: LinearLayout, title: String, desc: String, control: android.view.View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        box.addView(TextView(this).apply { text = title; textSize = 16f })
        if (desc.isNotBlank()) box.addView(TextView(this).apply {
            text = desc; textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(4))
        })
        box.addView(control)
        root.addView(box)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })
    }

    private fun switchRow(root: LinearLayout, key: String, def: Boolean,
                          title: String, desc: String) {
        val sw = Switch(this).apply {
            isChecked = prefs().getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean(key, checked).apply()
            }
        }
        row(root, title, desc, sw)
    }

    private fun textRow(root: LinearLayout, key: String, def: String,
                        title: String, desc: String, numeric: Boolean = false) {
        val et = EditText(this).apply {
            setText(prefs().getString(key, def) ?: def)
            if (numeric) inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs().edit().putString(key, s.toString()).apply()
                }
            })
        }
        row(root, title, desc, et)
    }

    private fun choiceRow(root: LinearLayout, key: String, def: String,
                          options: Array<String>, title: String, desc: String,
                          onPick: ((String) -> Unit)? = null) {
        val cur = TextView(this).apply {
            text = prefs().getString(key, def) ?: def
            textSize = 14f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(4), 0, dp(4))
        }
        val btn = Button(this).apply {
            text = L("انتخاب", "Choose")
            
            setOnClickListener {
                AlertDialog.Builder(this@VpnSettingsActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(options,
                        options.indexOf(prefs().getString(key, def) ?: def)) { d, which ->
                        val v = options[which]
                        prefs().edit().putString(key, v).apply()
                        cur.text = v
                        onPick?.invoke(v)
                        d.dismiss()
                    }
                    .setNegativeButton(L("لغو", "Cancel"), null)
                    .show()
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(cur.apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        box.addView(btn)
        row(root, title, desc, box)
    }

    private fun noteRow(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("تنظیمات کامل v2ray", "Full v2ray settings"); textSize = 22f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L("تنظیمات در اتصال بعدی اعمال می‌شوند.",
                "Settings apply on the next connect.")
            textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        // ---- Connection settings (merged from the old
        // drawer "Settings" menu: MTU / DNS / SOCKS) ----
        section(root, L("تنظیمات اتصال", "Connection settings"))
        textRow(root, "vpn_mtu", "1500",
            L("MTU اتصال", "Connection MTU"),
            L("در اتصال بعدی اعمال می‌شود؛ مقدار قدیمی «mtu» هم هم‌زمان به‌روز می‌شود.",
              "Applied on the next connect; the legacy “mtu” value is updated too."))
        textRow(root, "dns", "1.1.1.1,8.8.8.8",
            L("DNS اتصال", "Connection DNS"),
            L("سرورهای DNS با کاما جدا شوند.",
              "DNS servers, separated by commas."))
        textRow(root, "socks_port", "10808",
            L("پورت SOCKS محلی", "Local SOCKS port"),
            L("پورت پروکسی SOCKS داخلی.",
              "Local SOCKS proxy port."))
        // ---- UI settings (same order as v2rayNG) ----
        section(root, L("تنظیمات رابط کاربری", "UI settings"))
        switchRow(root, "show_speed", false,
            L("نمایش سرعت", "Enable speed display"),
            L("نمایش سرعت زنده در نوار وضعیت هنگام اتصال.",
              "Show live speed in the status line while connected."))
        switchRow(root, "confirm_delete", false,
            L("تأیید حذف کانفیگ", "Delete config confirmation"),
            L("حذف کانفیگ (تکی یا گروه) اول یک پیام تأیید نشان بدهد.",
              "Ask for confirmation before deleting a config (single or group)."))
        switchRow(root, "double_column", false,
            L("نمایش دو ستونی", "Enable double column display"),
            L("لیست کانفیگ‌ها در دو ستون نمایش داده شود.",
              "Display the config list in double columns."))
        switchRow(root, "show_all_groups", false,
            L("نمایش همه گروه‌ها", "Enable show all groups"),
            L("گروه‌های سیاستی دستی هم به‌صورت زبانه در کنار ساب‌ها نشان داده شوند (خاموش = فقط زبانه ساب‌ها).",
              "Also show manual policy groups as tabs next to subscriptions (off = subscription tabs only)."))
        row(root, L("زبان", "Language"),
            L("زبان برنامه؛ بعد از انتخاب، صفحه دوباره ساخته می‌شود.",
              "App language; the page is recreated after choosing."),
            Button(this).apply {
                text = if (AppLanguage.isEnglish(this@VpnSettingsActivity)) "English" else "فارسی"
                
                setOnClickListener {
                    AlertDialog.Builder(this@VpnSettingsActivity)
                        .setTitle(L("زبان برنامه", "App language"))
                        .setItems(arrayOf("فارسی", "English")) { _, which ->
                            AppLanguage.set(this@VpnSettingsActivity, if (which == 0) "fa" else "en")
                            recreate()
                        }
                        .show()
                }
            })
        choiceRow(root, "ui_mode", "Follow system",
            arrayOf("Follow system", "Light", "Dark"),
            L("حالت رابط کاربری", "UI mode settings"),
            L("حالت شب/روز؛ روی تم برنامه هم اعمال می‌شود.",
              "Follow system, light or dark; also applied to the app theme."),
            onPick = { v ->
                // HomeActivity reads theme_mode, so mirror
                // the choice there too: this makes the
                // setting actually change the theme.
                val mapped = when (v) {
                    "Light" -> "light"; "Dark" -> "dark"; else -> "auto"
                }
                getSharedPreferences("renamer_settings", MODE_PRIVATE)
                    .edit().putString("theme_mode", mapped).apply()
            })

        // ---- VPN Settings ----
        section(root, L("تنظیمات VPN", "VPN Settings"))
        switchRow(root, "enable_ipv6", false,
            L("فعال‌سازی IPv6", "Enable IPv6"),
            L("افزودن آدرس و مسیر IPv6 به رابط VPN.",
              "Add IPv6 address and routes to the VPN interface."))
        switchRow(root, "prefer_ipv6", false,
            L("ترجیح IPv6", "Prefer IPv6"),
            L("هنگام حل نام دامنه، آدرس‌های IPv6 ترجیح داده شوند.",
              "Prefer IPv6 addresses when resolving domain names."))
        switchRow(root, "enable_local_dns", true,
            L("فعال‌سازی DNS محلی", "Enable local DNS"),
            L("پردازش DNS با ماژول DNS هسته.",
              "DNS processed by the core's DNS module."))
        switchRow(root, "enable_fake_dns", false,
            L("فعال‌سازی DNS جعلی", "Enable fake DNS"),
            L("DNS محلی آدرس‌های جعلی برمی‌گرداند (سریع‌تر است، ولی ممکن است برای بعضی اپ‌ها کار نکند).",
              "Local DNS returns fake IP addresses (faster, but it may not work for some apps)."))
        textRow(root, "vpn_dns", "1.1.1.1",
            L("DNS مربوط به VPN", "VPN DNS (only IPv4/v6)"),
            L("با کاما جدا کنید؛ اگر خالی باشد از DNS قبلی استفاده می‌شود.",
              "Comma-separated; if empty, the previous DNS setting is used."))
        switchRow(root, "append_http_proxy", false,
            L("افزودن HTTP Proxy به VPN", "Append HTTP Proxy to VPN"),
            L("پروکسی HTTP مستقیم استفاده شود، بدون عبور از رابط مجازی.",
              "HTTP proxy will be used directly, without going through the virtual NIC device."))
        choiceRow(root, "bypass_lan", "Bypass",
            arrayOf("Bypass", "Global", "Direct"),
            L("عبور VPN از LAN", "Does VPN bypass LAN"),
            L("Bypass: فقط آدرس‌های خصوصی مستقیم می‌روند؛ Global: همه از پروکسی؛ Direct: همه مستقیم.",
              "Bypass: only private addresses go direct; Global: everything through proxy; Direct: everything direct."))
        textRow(root, "vpn_interface_address", "10.10.14.x",
            L("آدرس رابط VPN", "VPN Interface Address"),
            L("توسط سرویس VPN اندروید استفاده می‌شود (در نسخه بعدی به آن وصل می‌شود).",
              "Used by the Android VPN service (wired into it in a later step)."))
        textRow(root, "vpn_mtu", "1500",
            L("MTU مربوط به VPN (پیش‌فرض ۱۵۰۰)", "VPN MTU (default 1500)"),
            L("توسط سرویس VPN اندروید استفاده می‌شود (در نسخه بعدی به آن وصل می‌شود).",
              "Used by the Android VPN service (wired into it in a later step)."),
            numeric = true)
        switchRow(root, "hev_tun", true,
            L("فعال‌سازی قابلیت Hev TUN", "Enable Hev TUN Feature"),
            L("وقتی فعال باشد، TUN از hev-socks5-tunnel استفاده می‌کند؛ در غیر این صورت از xray-core.",
              "When enabled, TUN will use hev-socks5-tunnel; otherwise, it will use xray-core."))
        noteRow(root, L("توجه: این برنامه در حال حاضر پیاده‌سازی Hev TUN ندارد و TUN آن بر پایه Xray است؛ این گزینه فقط ذخیره می‌شود و موتور را عوض نمی‌کند.",
            "Note: this app has no Hev TUN implementation and its TUN is Xray-based; this option is stored only and does not switch engines."))

        // ---- Core Settings ----
        section(root, L("تنظیمات هسته", "Core Settings"))
        switchRow(root, "enable_sniffing", true,
            L("فعال‌سازی Sniffing", "Enable Sniffing"),
            L("تلاش برای استخراج دامنه از بسته (پیش‌فرض روشن).",
              "Try sniffing domain from the packet (default on)."))
        switchRow(root, "route_only", false,
            L("فعال‌سازی routeOnly", "Enable routeOnly"),
            L("نام دامنه استخراج‌شده فقط برای مسیریابی استفاده شود و آدرس مقصد همان IP بماند.",
              "Use the sniffed domain name for routing only, and keep the target address as the IP address."))
        switchRow(root, "enable_local_proxy", false,
            L("فعال‌سازی پروکسی محلی", "Enable local proxy"),
            L("وقتی خاموش باشد، پروکسی SOCKS در دسترس نیست.",
              "When disabled, no SOCKS proxy is available."))
        switchRow(root, "allow_lan", false,
            L("اجازه اتصال از LAN", "Allow connections from the LAN"),
            L("دستگاه‌های دیگر می‌توانند از طریق پروکسی محلی وصل شوند؛ فقط در شبکه‌های مطمئن فعال کنید.",
              "Other devices can connect to proxy by your IP address through local proxy. Only enable in trusted networks."))
        switchRow(root, "dynamic_proxy_port", false,
            L("فعال‌سازی پورت پویا برای پروکسی محلی", "Enable dynamic local proxy port"),
            L("هر بار که inbound ساخته می‌شود، یک پورت تصادفی تولید شود (پورت ۰ در پیکربندی).",
              "Generate a random local proxy port each time the inbound is created (port 0 in the config)."))
        textRow(root, "local_proxy_port", "10808",
            L("پورت پروکسی محلی", "Local proxy port"), "", numeric = true)
        textRow(root, "local_proxy_user", "",
            L("نام کاربری پروکسی محلی (اختیاری)", "Local proxy username (optional)"), "")
        textRow(root, "local_proxy_pass", "",
            L("رمز پروکسی محلی (اختیاری)", "Local proxy password (optional)"), "")
        switchRow(root, "socks_udp", true,
            L("SOCKS5 UDP", "SOCKS5 UDP"),
            L("احراز هویت SOCKS5 هنگام استفاده از UDP کاملاً امن نیست.",
              "SOCKS5 authentication is not fully secure when using UDP."))
        textRow(root, "remote_dns", "1.1.1.1",
            L("DNS راه دور (udp/tcp/https/quic) (اختیاری)", "Remote DNS (udp/tcp/https/quic) (Optional)"), "")
        textRow(root, "domestic_dns", "223.5.5.5",
            L("DNS داخلی (اختیاری)", "Domestic DNS (Optional)"), "")
        textRow(root, "dns_hosts", "",
            L("DNS hosts (قالب: دامنه:آدرس،...)", "DNS hosts (Format: domain:address,...)"),
            L("مثال: example.com:1.2.3.4,other.com:5.6.7.8",
              "Example: example.com:1.2.3.4,other.com:5.6.7.8"))
        choiceRow(root, "log_level", "warning",
            arrayOf("warning", "debug", "info", "error"),
            L("سطح لاگ", "Log Level"), "")
        choiceRow(root, "pre_resolve", "Resolve and add to DNS Hosts",
            arrayOf("Resolve and add to DNS Hosts", "Resolve only", "Do not resolve"),
            L("روش پیش‌حل دامنه خروجی", "Outbound domain pre-resolve method"),
            L("در اتصال اعمال می‌شود: دامنه به IP حل می‌شود و در hosts دان‌اس پیکربندی گذاشته می‌شود.",
              "Applied at connect: the domain is resolved and put into the config's DNS hosts."))

        // ---- Mux Settings ----
        section(root, L("تنظیمات Mux", "Mux Settings"))
        switchRow(root, "enable_mux", false,
            L("فعال‌سازی Mux", "Enable Mux"),
            L("سریع‌تر است، ولی ممکن است اتصال ناپایدار شود.",
              "Faster, but it may cause unstable connectivity."))
        textRow(root, "mux_tcp", "8",
            L("اتصالات TCP (بازه ۱- تا ۱۰۲۴)", "TCP connections (range -1 to 1024)"), "", numeric = true)
        textRow(root, "mux_xudp", "8",
            L("اتصالات XUDP (بازه ۱- تا ۱۰۲۴)", "XUDP connections (range -1 to 1024)"), "", numeric = true)
        choiceRow(root, "mux_quic", "reject",
            arrayOf("reject", "drop"),
            L("نحوه برخورد با QUIC در تونل Mux", "Handling of QUIC in mux tunnel"),
            L("در اتصال اعمال می‌شود: به‌صورت xudpProxyUDP443 در شیء mux قرار می‌گیرد.",
              "Applied at connect: emitted as xudpProxyUDP443 in the mux object."))

        // ---- Fragment Settings ----
        section(root, L("تنظیمات Fragment", "Fragment Settings"))
        switchRow(root, "enable_fragment", false,
            L("فعال‌سازی Fragment", "Enable Fragment"),
            L("بسته‌ها تکه‌تکه می‌شوند تا شناسایی سخت‌تر شود.",
              "Packets are fragmented to make detection harder."))
        choiceRow(root, "fragment_packets", "tlshello",
            arrayOf("tlshello", "1-1", "1-2", "1-3"),
            L("بسته‌های Fragment", "Fragment Packets"), "")
        textRow(root, "fragment_length", "50-100",
            L("طول Fragment (کم-زیاد)", "Fragment Length (min-max)"), "")
        textRow(root, "fragment_interval", "10-20",
            L("فاصله Fragment (کم-زیاد)", "Fragment Interval (min-max)"), "")
        textRow(root, "fragment_max_split", "10",
            L("حداکثر تقسیم Fragment", "Fragment Max Split"), "", numeric = true)

        // ---- Observatory Settings ----
        section(root, L("تنظیمات Observatory", "Observatory Settings"))
        textRow(root, "least_ping_interval", "3m",
            L("بازه leastPing", "leastPing Interval"), "")
        textRow(root, "least_load_interval", "5m",
            L("بازه leastLoad", "leastLoad Interval"), "")
        choiceRow(root, "least_load_mode", "HEAD",
            arrayOf("HEAD", "GET"),
            L("حالت leastLoad", "leastLoad Mode"), "")
        textRow(root, "least_load_sampling", "2",
            L("نمونه‌برداری leastLoad", "leastLoad Sampling"), "", numeric = true)
        textRow(root, "least_load_timeout", "30s",
            L("مهلت leastLoad", "leastLoad Timeout"), "")
        noteRow(root, L("توجه: این تنظیمات وقتی با یک گروه سیاستی وصل می‌شوی اعمال می‌شوند (leastPing با observatory، leastLoad با burstObservatory)؛ اتصال تکی از آن‌ها استفاده نمی‌کند.",
            "Note: these apply when you connect with a policy group selected (leastPing via observatory, leastLoad via burstObservatory); a single connect does not use them."))

        // ---- Advanced Settings ----
        section(root, L("تنظیمات پیشرفته", "Advanced Settings"))
        switchRow(root, "auto_connect_startup", false,
            L("اتصال خودکار هنگام شروع", "Auto connect at startup"),
            L("به‌صورت خودکار به سرور انتخاب‌شده وصل شود، که ممکن است ناموفق باشد.",
              "Automatically connects to the selected server at startup, which may be unsuccessful."))
        textRow(root, "delay_test_url", "https://www.gstatic.com/generate_204",
            L("آدرس تست تأخیر واقعی", "True delay test url"), "")
        textRow(root, "delay_test_concurrency", "16",
            L("همزمانی تست تأخیر واقعی", "True delay test concurrency"), "", numeric = true)
        textRow(root, "conn_info_url", "https://api.ip.sb/geoip",
            L("آدرس تست اطلاعات اتصال فعلی", "Current connection info test url"), "")

        // ---- Mode Settings ----
        section(root, L("تنظیمات حالت", "Mode Settings"))
        choiceRow(root, "vpn_mode", "VPN",
            arrayOf("VPN", "Proxy only"),
            L("حالت", "Mode"),
            L("در حالت «فقط پروکسی» تونل VPN ساخته نمی‌شود؛ هسته فقط با پروکسی محلی (socks و http) اجرا می‌شود و سیستم تونل نمی‌شود.",
              "In Proxy only mode no VPN tunnel is built; the core runs with local proxy inbounds only (socks and http) and the system is not tunnelled."))
        switchRow(root, "root_mode", false,
            L("فعال‌سازی حالت روت (کاربران روت)", "Root mode enabled (Root Users)"),
            L("پروکسی شفاف سطح پایین با دسترسی روت؛ حالت VPN استاندارد را دور می‌زند.",
              "Enable low-level transparent proxying via root privileges. This mode bypasses the standard Android system VPN."))
        switchRow(root, "lan_sharing", false,
            L("اشتراک‌گذاری LAN / تترینگ (کاربران روت)", "LAN / tethering sharing (Root Users)"),
            L("دستگاه‌های هات‌اسپات وای‌فای و USB از طریق VPN مسیریابی شوند.",
              "Route Wi-Fi hotspot and USB-tethered devices through the VPN."))
        noteRow(root, L("توجه: حالت روت به دستگاه روت‌شده نیاز دارد و در این نسخه فقط ذخیره می‌شود؛ بدون روت هیچ کاری انجام نمی‌دهد.",
            "Note: root mode requires a rooted device and is stored only in this version; without root it does nothing."))

        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            
            setOnClickListener { finish() }
        })
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }
}
