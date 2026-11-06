# تغییر نام کانفیگ (Config Renamer)

«تغییر نام کانفیگ» ابزاری برای تغییر نام گروهی کانفیگ‌های v2ray است که
به‌مرور بخش‌های اتصال هم به آن اضافه شده: v2ray، WireGuard، OpenVPN، اتصال اضطراری، اتصال هوشمند، زنجیره‌ی دوتایی، هیدرا، پنل Cloudflare و پیام‌رسانی زبان ناشناس. صفحه‌ی قفل برنامه
به شکل یک ماشین‌حساب درآمده و بخش خصوصی با پین باز می‌شود.

> **هشدار امنیتی برای سازنده‌ی بیلد:** پین‌ها و رمزهایی که در کد
> هست، فقط مقادیر نمونه‌اند. قبل از این‌که بیلد خودت را توزیع کنی،
> حتماً آن‌ها را عوض کن:
>
> - پین خصوصی و عمومی در `src/com/renamer/PinActivity.kt`
> - رمز ورود کلی در `src/com/renamer/AppLock.kt`
> - رمز تنظیمات اضطراری در `src/com/renamer/EmergencyActivity.kt`
- رمز پنل Cloudflare در `src/com/renamer/CloudflareActivity.kt`
- آدرس هاست پیام‌رسانی در `src/com/renamer/ShzClient.kt` (‏HOST و LEGACY_HOST)
>
> لینک سابسکریبشن اضطراری پیش‌فرض، لینک واقعی خود برنامه است و عمداً
> نگه داشته شده تا بخش اضطراری بدون هیچ تنظیم اضافه‌ای کار کند. اگر
> فورک خودت را می‌سازی، آن را با لینک خودت عوض کن.
>
> همچنین هرگز فایل کی‌استور نسخه‌ی نهایی (release keystore) را وارد
> گیت نکن؛ بیلد عمومی با یک کی‌استور دیباگ که در اولین اجرا ساخته
> می‌شود امضا می‌شود.

## ساخت (Build)

نیازمندی‌ها:

- JDK (پوشه‌ی `tools/jdk` در فضای کاری)
- Android SDK با `android.jar` مربوط به API 34
- kotlinc (پوشه‌ی `tools/kotlin`)
- خروجی‌های کتابخانه‌های ثالث که در `third_party/` آمده‌اند
  (zxing، تونل WireGuard، jsch و وابستگی‌های OpenVPN)
- و این موارد بزرگ‌تر که بیرون از ریپو می‌مانند و باید مسیرشان را
  بدهی: فایل aar کتابخانه‌ی Xray، سورس‌های ics-openvpn و فایل aar
  WireGuard

دستور ساخت:

```sh
bash build.sh
```

خروجی در `out/ConfigRenamer.apk` ساخته می‌شود.

مسیرهای پیش‌فرض برای این ماشین توسعه تنظیم شده‌اند؛ برای ساخت در
جای دیگر، این متغیرهای محیطی را بده:

| متغیر | معنی |
| --- | --- |
| `WORKSPACE` | پوشه‌ی فضای کاری که SDK و JDK و کتابخانه‌ها در آن‌اند |
| `JDK` | پوشه‌ی JDK |
| `BT` | پوشه‌ی ابزارهای SDK (aapt2 و d8) |
| `ANDROID_JAR` | مسیر `android.jar` |
| `XRAY_AAR` | پوشه‌ی aar کتابخانه‌ی Xray |
| `WG_AAR` | پوشه‌ی aar کتابخانه‌ی WireGuard |
| `OVPN_SRC` | پوشه‌ی سورس ics-openvpn (`vpnLib/src/main`) |

مثال:

```sh
WORKSPACE=/home/user/workspace bash build.sh
```

## منشأ فایل‌های third_party

این فایل‌ها کتابخانه‌های آماده‌اند که برای راحتی بیلد داخل ریپو
آمده‌اند؛ سورس اصلی هر کدام نزد پروژه‌ی بالادستی‌اش است:

| فایل | منشأ | لایسنس |
| --- | --- | --- |
| `zxing-core.jar` | ZXing (کتابخانه‌ی بارکد) | Apache-2.0 |
| `wg-tunnel-stripped.jar` | کتابخانه‌ی تونل WireGuard اندروید | MIT |
| `jsch-0.1.55.jar` | JSch (پیاده‌سازی SSH) | BSD |
| `awg-tunnel.jar` | کتابخانه‌ی تونل AmneziaWG (‏org.amnezia.awg‏) | GPL-3.0 |
| `androidx-collection-stub.jar` | استاب مجموعه‌های AndroidX | Apache-2.0 |
| `ovpn_deps/annotation-jvm-1.8.0.jar` | آنوتیشن‌های JetBrains | Apache-2.0 |
| `ovpn_deps/classes.jar` | کلاس‌های کامپایل‌شده‌ی ics-openvpn | GPL-3.0 |
| `ovpn_deps/localbm-classes.jar` | LocalBroadcastManager اندروید | Apache-2.0 |

وابستگی‌های بزرگ‌تر (فایل aar کتابخانه‌ی Xray، سورس‌های ics-openvpn
و فایل aar وایرگارد) داخل ریپو نیستند؛ این‌ها پروژه‌های عمومی‌اند و
مسیرشان را با متغیرهای محیطی جدول بالا می‌دهی. این همان روال معمول
پروژه‌های اوپن‌سورس است: کد خود برنامه کامل داخل ریپوست و
وابستگی‌ها از پروژه‌های بالادستی مستند می‌شوند، نه این‌که سورس همه‌ی
دنیا داخل یک ریپو کپی شود.

## سورس وابستگی‌های بزرگ

این وابستگی‌ها پروژه‌های عمومی جداگانه‌اند و سورسشان همین‌جاست:

- کتابخانه‌ی Xray اندروید: <https://github.com/XTLS/Xray-core>
- سورس‌های ics-openvpn: <https://github.com/schwabe/ics-openvpn>
- کتابخانه‌ی تونل WireGuard اندروید: <https://github.com/WireGuard/wireguard-android>

برای بیلد، خروجی‌های ساخته‌شده‌ی این پروژه‌ها (aar و کتابخانه‌های
نیتیو) را با متغیرهای محیطی جدول بالا به اسکریپت بیلد بده.

## نسخه

این ریپو بر مبنای نسخه‌ی ۲٫۶۸ (versionCode 184) آماده شده است.

## لایسنس

این پروژه تحت لایسنس GPL-3.0 منتشر شده است (فایل `LICENSE` را
ببینید). بخش‌هایی از کد از پروژه‌ی v2rayNG اقتباس شده که آن هم
GPL-3.0 است.

---

## English summary

Config Renamer renames v2ray configs in bulk and also includes
connection sections: v2ray, WireGuard, OpenVPN, emergency connect,
smart connect, double chain, Hydra, Cloudflare panel and alien-language
messenger. Its lock screen looks like a
calculator and the private section opens with a PIN.

Build with `bash build.sh` (see the Persian section above for
prerequisites and environment variables). The PINs, passwords and paste host in the code are sample values —
change them before distributing your own build; the default emergency
subscription URL is the app's real one, kept intentionally so emergency
works out of the box. Never commit a release keystore to a public
repository.

License: GPL-3.0 (see `LICENSE`).
