package com.renamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * "اتصال هوشمند" (Smart Connect) settings page.
 *
 * When enabled and apps are selected here, the
 * SmartConnectService watches the foreground app: when a
 * selected app (e.g. Telegram) opens, v2ray connects
 * automatically in the background, without opening this
 * app. It first tries the last connected config (fast,
 * no ping test); if that fails it ping-tests and
 * connects to the best config.
 *
 * Prefs "renamer_smart":
 *   "smart_enabled" (boolean)
 *   "smart_apps" (package names, newline-joined)
 *
 * Detecting the foreground app needs Usage Access: the
 * button below opens Settings.ACTION_USAGE_ACCESS_SETTINGS.
 * Without that permission smart connect cannot work.
 *
 * This page only stores settings; it does not start or
 * stop the service itself (the parent wires the entry
 * point and the service lifecycle).
 */
class SmartConnectActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun prefs() = getSharedPreferences("renamer_smart", MODE_PRIVATE)

    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)
        // Fixed bottom bar (Save + Back) outside the
        // scroll, so saving never needs scrolling to the
        // very bottom of a long app list.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        outer.addView(scroll)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 16, 32, 32)
        }
        val saveBtn = Button(this).apply {
            text = L("ذخیره", "Save")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val backBtn = Button(this).apply {
            text = L("بازگشت", "Back")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomBar.addView(saveBtn)
        bottomBar.addView(backBtn)
        outer.addView(bottomBar)
        setContentView(outer)

        // Re-arm the service when this page opens, so
        // a service MIUI killed comes back without a
        // re-save: opening this section itself revives it.
        try {
            val sp = prefs()
            val apps = sp.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (sp.getBoolean("smart_enabled", false) && apps.isNotEmpty()) {
                val svc = android.content.Intent(this, SmartConnectService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    startForegroundService(svc) else startService(svc)
            }
        } catch (_: Exception) {}

        root.addView(TextView(this).apply {
            text = L("اتصال هوشمند", "Smart Connect")
            textSize = 22f
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = L(
                "وقتی یک اپ انتخاب‌شده باز شود، v2ray به‌صورت خودکار و در پس‌زمینه وصل می‌شود، بدون اینکه نیاز باشد این برنامه را باز کنی. اول به آخرین کانفیگی که به آن وصل شده بودی وصل می‌شود (سریع، بدون تست پینگ) و اگر آن وصل نشد، تست پینگ می‌گیرد و به بهترین کانفیگ وصل می‌شود.",
                "When a selected app opens, v2ray connects automatically in the background, without opening this app. It first tries the last config you connected to (fast, no ping test), and if that fails it ping-tests and connects to the best config."
            )
            textSize = 15f
            setPadding(0, 0, 0, 24)
        })

        // "How does this section work?" — the full
        // explanation, including the two rules the user
        // asked to be stated plainly: smart connect only
        // runs when NO other VPN is up, and it never
        // disturbs a hand-started VPN; and disconnect
        // happens only on a FULL close, not on minimise.
        root.addView(Button(this).apply {
            text = L("این قسمت چطور کار می‌کند؟", "How does this section work?")
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SmartConnectActivity)
                    .setTitle(L("اتصال هوشمند چطور کار می‌کند؟", "How does Smart Connect work?"))
                    .setMessage(L("""۱) اپ‌های دلخواهت را تیک بزن و «ذخیره» را بزن. از آن به بعد، به محض اینکه یکی از آن اپ‌ها باز شود، برنامه بدون اینکه خودش باز شود، به هدفی که انتخاب کرده‌ای وصل می‌شود: v2ray، یا WireGuard، یا OpenVPN، یا اتصال اضطراری. فقط یکی از این چهار می‌تواند انتخاب شود.

۲) اتصال هوشمند فقط وقتی وصل می‌شود که هیچ فیلترشکن دیگری روشن نباشد. اگر خودت دستی فیلترشکنی روشن کرده باشی — از همین برنامه یا از برنامه دیگر — اتصال هوشمند ساکت می‌ماند و هیچ کاری نمی‌کند تا وقتی خودت آن فیلترشکن را قطع کنی. اتصال هوشمند هرگز فیلترشکن دستی تو را قطع نمی‌کند و کارش را مختل نمی‌کند.

۳) برای v2ray، اول به آخرین کانفیگی وصل می‌شود که به آن وصل شده بودی (سریع، بدون تست پینگ) و اگر آن وصل نشد، تست پینگ می‌گیرد و به بهترین کانفیگ وصل می‌شود. برای WireGuard به تونل فعال ذخیره‌شده وصل می‌شود و برای OpenVPN به آخرین پروفایل. برای اتصال اضطراری دقیقاً مثل صفحه خودش کار می‌کند: هر بار تازه می‌گیرد، تست پینگ می‌گیرد و به بهترین سرور وصل می‌شود، بدون اینکه چیزی ذخیره شود و بدون اینکه کانفیگی از تو لازم باشد.

۴) قطع شدن با ۵ ثانیه مهلت اتفاق می‌افتد: اگر اپ انتخاب‌شده بیشتر از ۵ ثانیه جلوی صفحه نباشد — با دکمه برگشت، با پاک کردن از لیست اخیر، یا با رفتن به اپ دیگر یا صفحه اصلی — فیلترشکن قطع می‌شود. برگشت سریع‌تر از ۵ ثانیه (مثلاً یک سر زدن کوتاه به صفحه اصلی) قطع نمی‌کند. فقط دو استثنا هست: بودن در داخل همین برنامه، و رد شدن گذرای سایه اعلان‌ها یا صفحه برنامه‌های اخیر، قطع نمی‌کند.

۵) برای کار کردن این قسمت دو چیز لازم است: دسترسی مصرف برنامه (از همین صفحه) تا برنامه بفهمد چه اپی باز شده است، و یک بار وصل شدن دستی برای هر هدف تا مجوز VPN ثبت شده باشد. اگر خودت دستی قطع کنی، اتصال هوشمند پنج دقیقه صبر می‌کند تا دوباره وصل نشود.

نکته: بودن در داخل همین برنامه هرگز فیلترشکن را قطع نمی‌کند، پس تنظیم کردن برنامه بی‌خطر است.""", """1) Tick the apps you want and tap Save. From then on, as soon as one of those apps opens, the app connects — without opening itself — to the target you chose: v2ray, WireGuard, OpenVPN, or Emergency connect. Only one of the four can be selected.

2) Smart Connect only connects when NO other VPN is on. If you manually connected a VPN — from this app or another one — Smart Connect stays quiet and does nothing until you disconnect that VPN yourself. Smart Connect never disconnects or disturbs your hand-started VPN.

3) For v2ray it first tries the last config you connected to (fast, no ping test), and if that fails it ping-tests and connects to the best config. For WireGuard it connects to the saved active tunnel, and for OpenVPN to the last profile. For Emergency connect it works exactly like the emergency page: it fetches fresh, ping-tests, and connects to the best server, without saving anything and without needing any config from you.

4) Disconnect happens after a 5-second grace: if the selected app stays away from the foreground for MORE than 5 seconds — by Back, by swiping it away, or by going to another app or the home screen — the VPN turns off. Coming back within 5 seconds (a quick hop to Home, for example) never cuts it. Two exceptions only: being inside this app itself, and the notification shade / recents screen flashing through, never cut it.

5) Two things are needed: Usage Access (from this page) so the app can tell which app opened, and one manual connect per target so the VPN permission is registered. If you disconnect manually, Smart Connect waits five minutes before connecting again.

Note: being inside this app itself never cuts the VPN, so changing settings is safe."""))
                    .setPositiveButton(L("باشه", "OK"), null)
                    .show()
            }
        })

        // Master switch.
        val enabledNow = prefs().getBoolean("smart_enabled", false)
        val masterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val masterLabel = TextView(this).apply {
            text = L("اتصال هوشمند", "Smart Connect")
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val masterSwitch = Switch(this).apply { isChecked = enabledNow }
        masterRow.addView(masterLabel)
        masterRow.addView(masterSwitch)
        root.addView(masterRow)

        // What should smart connect connect TO? v2ray
        // (last/best config), WireGuard (saved active
        // tunnel) or OpenVPN (last profile). Stored in
        // renamer_smart "smart_target".
        root.addView(TextView(this).apply {
            text = L("وصل شدن به چی؟", "Connect to what?")
            textSize = 18f; setPadding(0, 0, 0, 8)
        })
        val targetNow = prefs().getString("smart_target", "v2ray") ?: "v2ray"
        val targetGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val targetRadios = listOf(
            "v2ray" to L("v2ray (آخرین یا بهترین کانفیگ)", "v2ray (last or best config)"),
            "wireguard" to L("WireGuard (تونل فعال ذخیره‌شده)", "WireGuard (saved active tunnel)"),
            "openvpn" to L("OpenVPN (آخرین پروفایل)", "OpenVPN (last profile)"),
            "emergency" to L("اتصال اضطراری (بهترین سرور، بدون کانفیگ از سمت تو)", "Emergency connect (best server, no config from you)"),
        )
        // BUG FIX (root cause): RadioGroup mutual
        // exclusion ONLY works when every RadioButton
        // has a unique view ID. Programmatically
        // created buttons all had id = -1, so tapping
        // WireGuard checked it WITHOUT unchecking
        // v2ray, and Save then picked the FIRST checked
        // button (v2ray) — the target could never
        // change. Each button now gets a generated ID,
        // and the saved target is checked through the
        // group itself.
        val targetIds = mutableMapOf<String, Int>()
        for ((key, label) in targetRadios) {
            val rid = View.generateViewId()
            targetIds[key] = rid
            targetGroup.addView(RadioButton(this).apply {
                id = rid
                text = label; tag = key
                setPadding(0, 4, 0, 4)
            })
        }
        targetIds[targetNow]?.let { targetGroup.check(it) }
        root.addView(targetGroup)
        root.addView(TextView(this).apply {
            text = L("برای WireGuard و OpenVPN و اتصال اضطراری هم مثل v2ray است: اول باید یک بار دستی وصل شده باشی تا مجوز VPN ثبت شده باشد؛ بعد از آن اتصال هوشمند بدون باز کردن برنامه وصل می‌شود. اتصال اضطراری مثل صفحه خودش کار می‌کند: هر بار تازه می‌گیرد، تست پینگ می‌گیرد و به بهترین سرور وصل می‌شود، بدون اینکه چیزی ذخیره شود.",
                "WireGuard, OpenVPN and Emergency connect work like v2ray: connect once manually first so the VPN permission is granted; after that smart connect works without opening the app. Emergency connect works exactly like its own page: it fetches fresh, ping-tests and connects to the best server, without saving anything.")
            textSize = 13f; setTextColor(Color.GRAY); setPadding(0, 8, 0, 24)
        })

        // Optional auto-kill (OFF by default):
        // after leaving a selected app for good,
        // kill that app's background process so the
        // next open is a cold start — the effect of
        // manually force-stopping it. True
        // force-stop needs a system permission no
        // normal app has; killBackgroundProcesses is
        // the closest Android allows. Warning is in
        // the label itself: it can cut an ongoing
        // call or download in that app.
        val killSwitch = Switch(this).apply {
            isChecked = prefs().getBoolean("smart_kill_on_close", false)
        }
        val killRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val killLabel = TextView(this).apply {
            text = L("بستن کامل برنامه بعد از خروج (نزدیک‌ترین کار به فورس‌استاپ خودکار؛ ممکن است تماس یا دانلود در حال انجام آن برنامه را قطع کند)",
                "Fully close the app after leaving it (closest to automatic force-stop; may cut an ongoing call or download in that app)")
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        killRow.addView(killLabel)
        killRow.addView(killSwitch)
        root.addView(killRow)

        // Restricted-settings guide (Android blocks sensitive
        // permissions for sideloaded apps until the user
        // enables "Allow restricted settings" in App info).
        root.addView(TextView(this).apply {
            text = if (hasUsageAccess()) L("وضعیت دسترسی: فعال است ✓", "Access status: granted ✓")
                   else L("وضعیت دسترسی: هنوز داده نشده است", "Access status: not granted yet")
            textSize = 15f
            setTextColor(if (hasUsageAccess()) Color.parseColor("#0B6E4F") else Color.parseColor("#B00020"))
            setPadding(0, 0, 0, 8)
        })
        root.addView(Button(this).apply {
            text = L("۱) باز کردن اطلاعات برنامه (رفع محدودیت)", "1) Open app info (lift restriction)")
            setOnClickListener {
                try {
                    val uri = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    Toast.makeText(this@SmartConnectActivity,
                        L("در صفحه اطلاعات برنامه، روی سه‌نقطه بالا بزن و «Allow restricted settings» را فعال کن، بعد برگرد.", "On the app info page, tap the top ⋮ menu and enable “Allow restricted settings”, then come back."),
                        Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        })
        root.addView(TextView(this).apply {
            text = L("اگر اندروید دسترسی را رد کرد و نوشت «Controlled by restricted setting»، اول قدم ۱ را انجام بده؛ بدون آن، قدم ۲ هم رد می‌شود. این محدودیت اندروید برای برنامه‌های نصب‌شده از بیرون پلی‌استور است، نه مشکل این برنامه.",
                "If Android denies access with “Controlled by restricted setting”, do step 1 first — step 2 will also be denied without it. This is Android's restriction for sideloaded apps, not a bug in this app.")
            textSize = 13f; setTextColor(Color.GRAY); setPadding(0, 8, 0, 16)
        })
        // Usage Access button + note.
        root.addView(Button(this).apply {
            text = L("۲) اعطای دسترسی مصرف برنامه", "2) Grant usage access")
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this@SmartConnectActivity,
                        L("باز کردن تنظیمات دسترسی ممکن نشد.", "Could not open access settings."),
                        Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(TextView(this).apply {
            text = L(
                "بدون دسترسی مصرف برنامه، اتصال هوشمند نمی‌تواند بفهمد کدام اپ باز شده و کار نمی‌کند. این دسترسی را در صفحه‌ای که باز می‌شود برای این برنامه فعال کن.",
                "Without usage access, smart connect cannot tell which app is open and will not work. Enable it for this app on the page that opens."
            )
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 24)
        })
        // Staying alive without the app open: the service
        // is a foreground service and restarts on boot,
        // but Xiaomi/MIUI battery optimisation can still
        // kill it. This button opens the system page
        // where the user exempts the app.
        root.addView(Button(this).apply {
            text = L("جلوگیری از کشته شدن در پس‌زمینه (باتری)", "Keep alive in background (battery)")
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this@SmartConnectActivity,
                        L("باز کردن تنظیمات باتری ممکن نشد.", "Could not open battery settings."),
                        Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(TextView(this).apply {
            text = L("اگر اتصال هوشمند فقط وقتی برنامه باز است کار می‌کند، علت معمولاً این است: بعد از ری‌استارت گوشی سرویس دوباره راه می‌افتد، ولی بهینه‌سازی باتری شیائومی (MIUI) آن را می‌کشد. در صفحه‌ای که باز می‌شود، برای این برنامه بهینه‌سازی باتری را خاموش کن و در تنظیمات گوشی، اجازه «Autostart» را هم بده.",
                "If smart connect only works while the app is open, this is usually why: the service restarts after reboot, but Xiaomi (MIUI) battery optimisation kills it. On the page that opens, turn battery optimisation off for this app, and in the phone settings also allow Autostart for it.")
            textSize = 13f; setTextColor(Color.GRAY); setPadding(0, 8, 0, 24)
        })

        // Installed apps, sorted by label, with a search
        // box: type an app name and tick it at once.
        root.addView(TextView(this).apply {
            text = L("اپ‌های انتخاب‌شده", "Selected apps")
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })
        val saved = prefs().getString("smart_apps", "")?.lines()
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val checkedPkgs = saved.toMutableSet()
        val apps = try {
            packageManager.getInstalledApplications(0)
                .sortedBy { packageManager.getApplicationLabel(it).toString() }
        } catch (_: Exception) { emptyList() }
        // Persian names people type for popular apps, so
        // typing "تلگرام" finds Telegram even though its
        // label and package are in English.
        val faAliases = mapOf(
            "تلگرام" to "telegram", "تلگرم" to "telegram", "تل" to "telegram",
            "اینستاگرام" to "instagram", "اینستا" to "instagram",
            "واتساپ" to "whatsapp", "واتس‌اپ" to "whatsapp",
            "یوتیوب" to "youtube", "یوتوب" to "youtube",
            "گوگل" to "google", "کروم" to "chrome",
            "توییتر" to "twitter", "ایکس" to "twitter",
            "فیسبوک" to "facebook", "مسنجر" to "messenger",
            "تیکتاک" to "tiktok", "تیک‌تاک" to "tiktok",
            "اسپاتیفای" to "spotify", "نتفلیکس" to "netflix",
            "جی‌میل" to "gmail", "جیمیل" to "gmail",
            "مپ" to "maps", "نقشه" to "maps",
            "درایو" to "drive", "تلویزیون" to "tv"
        )
        fun norm(s: String): String {
            return s.trim().lowercase()
                .replace(" ", "").replace("‌", "").replace("ـ", "")
                .replace('ی', 'ي').replace('ک', 'ك')
        }
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderList(query: String) {
            listBox.removeAllViews()
            val q = norm(query)
            val qLatin = faAliases.entries.firstOrNull { norm(it.key) == q }?.value
            val shown = apps.filter { app ->
                if (q.isBlank()) true
                else {
                    val label = packageManager.getApplicationLabel(app).toString()
                    val hay = norm(label) + " " + norm(app.packageName)
                    val rawHay = (label + " " + app.packageName).lowercase()
                    hay.contains(q) || rawHay.contains(query.trim().lowercase()) ||
                        (qLatin != null && hay.contains(qLatin))
                }
            }.sortedWith(compareBy(
                { app ->
                    // Exact / prefix matches first, then the rest.
                    val label = packageManager.getApplicationLabel(app).toString().lowercase()
                    val term = qLatin ?: q
                    if (label == term) 0 else if (label.startsWith(term)) 1 else 2
                },
                { packageManager.getApplicationLabel(it).toString() }
            ))
            if (shown.isEmpty()) {
                listBox.addView(TextView(this).apply {
                    text = L("اپی با این اسم پیدا نشد.", "No app matches that name.")
                    setTextColor(Color.GRAY)
                })
                return
            }
            for (app in shown) {
                val label = packageManager.getApplicationLabel(app).toString()
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(CheckBox(this).apply {
                    text = label
                    isChecked = checkedPkgs.contains(app.packageName)
                    setOnCheckedChangeListener { _, c ->
                        if (c) checkedPkgs.add(app.packageName) else checkedPkgs.remove(app.packageName)
                    }
                    setPadding(0, 4, 0, 0)
                })
                col.addView(TextView(this).apply {
                    text = app.packageName; textSize = 12f
                    setTextColor(Color.GRAY); setPadding(48, 0, 0, 4)
                })
                listBox.addView(col)
            }
        }
        root.addView(EditText(this).apply {
            hint = L("جستجوی اسم برنامه…", "Search app name…")
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderList(s.toString())
                }
            })
        })
        root.addView(listBox)
        renderList("")

        // Save (fixed bottom bar) + Back.
        saveBtn.setOnClickListener {
                val chosen = checkedPkgs.toList()
                val checkedId = targetGroup.checkedRadioButtonId
                val target = if (checkedId != -1) {
                    (targetGroup.findViewById<RadioButton>(checkedId)?.tag as? String)
                } else null
                val targetFinal = target ?: prefs().getString("smart_target", "v2ray") ?: "v2ray"
                prefs().edit()
                    .putBoolean("smart_enabled", masterSwitch.isChecked)
                    .putString("smart_apps", chosen.joinToString("\n"))
                    .putString("smart_target", targetFinal)
                    .putBoolean("smart_kill_on_close", killSwitch.isChecked)
                    .apply()
                // Re-saving smart connect re-asserts
                // it: clear the 5-minute manual-stop
                // suppression, so opening a selected
                // app right after saving connects at
                // once instead of looking dead.
                try {
                    prefs().edit().remove("smart_manual_stop_until").apply()
                    getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                        .edit().remove("smart_manual_stop_until").apply()
                } catch (_: Exception) {}
                try {
                    if (masterSwitch.isChecked && chosen.isNotEmpty()) {
                        val svc = android.content.Intent(this@SmartConnectActivity, SmartConnectService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            startForegroundService(svc) else startService(svc)
                    } else {
                        stopService(android.content.Intent(this@SmartConnectActivity, SmartConnectService::class.java))
                    }
                } catch (_: Exception) {}
                Toast.makeText(this@SmartConnectActivity,
                    L("ذخیره شد: ${chosen.size} اپ.", "Saved: ${chosen.size} apps."),
                    Toast.LENGTH_SHORT).show()
        }
        backBtn.setOnClickListener { finish() }
    }

    companion object {
        const val PREFS_NAME = "renamer_smart"
        const val KEY_ENABLED = "smart_enabled"
        const val KEY_APPS = "smart_apps"
    }
}
