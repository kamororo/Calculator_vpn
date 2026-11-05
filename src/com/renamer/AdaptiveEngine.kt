package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** ADAPTIVE ENGINE (v1.79) — "موتور هوشمند".
 *
 *  1.79 changes the selector from a sequential ladder
 *  to parallel racing: the original is still probed
 *  first, but a dead config's candidates race in
 *  waves (jump + packet shapes first, then IP/port/
 *  transport, then a free Cloudflare WARP rung), so
 *  the repair that took 45s and never reached the
 *  jump rung now usually lands in seconds. Winners
 *  are learned per config AND per network (global
 *  learning), and everything the engine learned or
 *  changed is purged when the engine is switched
 *  off (disableCleanup), returning the app to its
 *  pre-engine state.
 *
 *  NOT a VPN protocol and NOT a section of its own. It is a
 *  first-priority layer that sits IN FRONT of the existing
 *  connect paths (v2ray, WireGuard/Amnezia, OpenVPN, Hydra,
 *  Smart Connect):
 *
 *  - OFF (default): every integration point returns before
 *    doing anything. Sections behave byte-for-byte as they
 *    did before this engine existed, and the report buttons
 *    are GONE (not just disabled).
 *  - ON: a connect first runs the engine's selector for
 *    that section. The selector only ever IMPROVES what
 *    gets connected; it never replaces a section's VPN
 *    machinery, never edits a saved config, and never
 *    disturbs another section's running VPN.
 *  - If the selector finds nothing, the section's normal
 *    connect runs exactly as before (fallback is built
 *    into every integration point, not an afterthought).
 *
 *  The v2ray selector is the deepest part: it probes the
 *  ORIGINAL config first (a healthy config is left
 *  completely alone — zero behavior change for working
 *  configs), and only a dead config goes through the
 *  mutation ladder: per-network memory winner, TLS
 *  fragment, uTLS fingerprint rotation, SNI repair,
 *  IP variants, port variants, transport swap, flow
 *  removal, and finally a "jump" through a known-healthy
 *  config (the app's own chain machinery). Every probe is
 *  a real delay test through the candidate (exit proof),
 *  not a TCP ping.
 *
 *  Protocol-aware guards (the ladder is NOT blind):
 *  Reality configs never get an SNI change (their SNI
 *  must match the Reality target); fragment is only
 *  injected for TLS/Reality stream security, and never
 *  on an outbound that already dials through another
 *  proxy (same guard as VpnConfigBuilder); emergency
 *  configs are never mutated at all (Hydra expansion
 *  skips fromEmergency endpoints).
 *
 *  Per-network memory: the winning mutation for a
 *  config is remembered per network type (Wi-Fi vs
 *  mobile). Next time, that mutation is tried first,
 *  so a repeat repair is usually one probe. Only the
 *  mutation id is stored (e.g. "fragment"), never a
 *  mutated link — saved configs are never rewritten.
 */
object AdaptiveEngine {

    const val S_V2RAY = "v2ray"
    const val S_WG = "wireguard"
    const val S_OVPN = "openvpn"
    const val S_HYDRA = "hydra"
    const val S_SMART = "smart"
    const val S_EMERGENCY = "emergency"

    private const val PREF_KEY = "adaptive_engine"
    private const val MAX_REPORT_LINES = 60

    fun isEnabled(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY, false)
        } catch (_: Exception) { false }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, on).apply()
        } catch (_: Exception) {}
    }

    private fun store(ctx: Context) =
        ctx.getSharedPreferences("renamer_engine", Context.MODE_PRIVATE)

    // ---------- report ----------

    private fun stamp(): String {
        return try {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
        } catch (_: Exception) { "" }
    }

    fun append(ctx: Context, section: String, fa: String, en: String) {
        try {
            val line = "[${stamp()}] " + AppLanguage.t(ctx, fa, en)
            val sp = store(ctx)
            val key = "report_$section"
            val old = sp.getString(key, "") ?: ""
            val lines = (if (old.isBlank()) mutableListOf() else old.lines().toMutableList())
            lines += line
            // 2.21: while the engine is ON the report
            // keeps the whole session (up to 400 lines)
            // instead of rotating at 60 mid-session,
            // so the log survives from turn-on until
            // turn-off. When OFF the old 60 cap applies.
            val cap = if (isEnabled(ctx)) 400 else MAX_REPORT_LINES
            while (lines.size > cap) lines.removeAt(0)
            sp.edit().putString(key, lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    fun report(ctx: Context, section: String): String {
        return try {
            val r = store(ctx).getString("report_$section", "") ?: ""
            if (r.isBlank()) AppLanguage.t(ctx,
                "هنوز گزارشی برای این بخش نیست. وقتی موتور روشن باشد و اتصالی انجام شود، اینجا می‌نویسد چه چیزی را امتحان کرد و چه شد.",
                "No report for this section yet. When the engine is on and a connection is made, it writes here what it tried and what happened.")
            else r
        } catch (_: Exception) { "" }
    }

    fun clearReport(ctx: Context, section: String) {
        try { store(ctx).edit().remove("report_$section").apply() } catch (_: Exception) {}
    }

    // ---------- per-section learning (2.21) ----------
    // When the engine connects a config with a method,
    // that method id is remembered FOR THIS SECTION
    // ONLY (learn_openvpn_* is never read by the
    // WireGuard or v2ray paths, and vice versa).
    // Next time the same config is tried, the learned
    // method goes first. A stale learned method costs
    // almost nothing: the fast probe skips it within
    // seconds when its server is dead.
    fun learnMethod(ctx: Context, section: String, key: String, method: String) {
        try { store(ctx).edit().putString("learn_${section}_$key", method).apply() } catch (_: Exception) {}
    }

    fun learnedMethod(ctx: Context, section: String, key: String): String? {
        return try { store(ctx).getString("learn_${section}_$key", null)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    /** 2.39: forget a win that proved dead in real
     *  traffic (probe-passed but no apps work).
     *  Removes the per-config mutation memory and
     *  all warm routes for this config, so the fake
     *  win is never promoted again. The global
     *  class memory is left alone: a stale class
     *  is re-probed first and skipped in seconds. */
    fun unlearnWin(ctx: Context, dedupKey: String) {
        try {
            val st = store(ctx)
            val ed = st.edit()
            for (k in st.all.keys) {
                if ((k.startsWith("mem_") || k.startsWith("warm_")) && k.endsWith("_$dedupKey")) ed.remove(k)
            }
            ed.apply()
        } catch (_: Exception) {}
    }

    fun sectionLabel(ctx: Context, section: String): String = when (section) {
        S_WG -> AppLanguage.t(ctx, "وایرگارد و امنزیا", "WireGuard & Amnezia")
        S_OVPN -> "OpenVPN"
        S_HYDRA -> AppLanguage.t(ctx, "هیدرا", "Hydra")
        S_SMART -> AppLanguage.t(ctx, "اتصال هوشمند", "Smart Connect")
        S_EMERGENCY -> AppLanguage.t(ctx, "اتصال اضطراری", "Emergency connect")
        else -> "v2ray"
    }

    /** Success lines of a report, rendered GREEN +
     *  bold (2.21): a line counts as success when it
     *  says the config really connected. The stored
     *  report stays plain text; only the rendering
     *  is colored. */
    fun coloredReport(activity: Activity, section: String): CharSequence {
        return try {
            val raw = report(activity, section)
            val sb = android.text.SpannableStringBuilder(raw)
            var start = 0
            for (line in raw.lines()) {
                val end = start + line.length
                if (line.contains("واقعاً وصل شد") || line.contains("really connected")) {
                    sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#2E7D32")),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                start = end + 1
            }
            sb
        } catch (_: Exception) { report(activity, section) }
    }

    /** The copyable report dialog every section shows
     *  (only when the engine is on, in sections
     *  where it can work; the caller hides the
     *  button otherwise). */
    fun showReportDialog(activity: Activity, section: String) {
        try {
            val textView = TextView(activity).apply {
                text = coloredReport(activity, section)
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(24, 16, 24, 16)
            }
            val scroll = ScrollView(activity).apply { addView(textView) }
            val dlg = AlertDialog.Builder(activity)
                .setTitle(AppLanguage.t(activity, "گزارش موتور: ", "Engine report: ") + sectionLabel(activity, section))
                .setView(scroll)
                .setPositiveButton(AppLanguage.t(activity, "کپی گزارش", "Copy report")) { _, _ ->
                    try {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("engine-report", report(activity, section)))
                        Toast.makeText(activity, AppLanguage.t(activity, "گزارش کپی شد.", "Report copied."), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .setNeutralButton(AppLanguage.t(activity, "پاک کردن", "Clear")) { _, _ ->
                    clearReport(activity, section)
                }
                .setNegativeButton(AppLanguage.t(activity, "بستن", "Close"), null)
                .create()
            dlg.show()
        } catch (_: Exception) {}
    }

    /** Report button for a section page. 2.60:
     *  prettier — a soft green gradient pill with
     *  a deeper stroke, bold 13sp text and a
     *  larger log glyph, same compact footprint
     *  (WRAP_CONTENT) so no page layout moves.
     *  The caller sets its visibility from
     *  setReportVisible() in onCreate and
     *  onResume: visible in every section where
     *  the engine can work, as soon as it is on. */
    fun reportButton(activity: Activity, section: String): Button {
        return Button(activity).apply {
            text = AppLanguage.t(activity, "گزارش موتور", "Engine report")
            textSize = 13f
            isAllCaps = false
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1B5E20"))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#E8F5E9"), Color.parseColor("#C8E6C9"))).apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 22f * activity.resources.displayMetrics.density
                setStroke((1.5f * activity.resources.displayMetrics.density).toInt(),
                    Color.parseColor("#66BB6A"))
            }
            try {
                val glyph = android.graphics.drawable.BitmapDrawable(
                    activity.resources, HydraUi.logIconBitmap(activity, 18))
                setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
                compoundDrawablePadding = (6 * activity.resources.displayMetrics.density).toInt()
            } catch (_: Exception) {}
            val d = activity.resources.displayMetrics.density
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            elevation = 2 * d
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { showReportDialog(activity, section) }
        }
    }

    /** 2.60: the sections where the engine HAS
     *  methods and can work. The report option
     *  is visible in exactly these sections, as
     *  soon as the engine is ON — the 2.59 rule
     *  (hide until the engine first wrote for the
     *  section) was wrong: the user wants the
     *  report visible immediately on engine-ON
     *  wherever the engine can work. Sections
     *  with no engine methods (Chain page,
     *  Cloudflare panel, subscriptions, ping,
     *  Monitoring) have no report button at all,
     *  so a button added there later stays hidden
     *  by this set, not by accident. */
    val ENGINE_SECTIONS = setOf(S_V2RAY, S_WG, S_OVPN, S_HYDRA, S_SMART, S_EMERGENCY)

    fun setReportVisible(btn: View?, ctx: Context, section: String) {
        try {
            if (btn != null) btn.visibility =
                if (isEnabled(ctx) && ENGINE_SECTIONS.contains(section)) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    // ---------- network key + memory ----------

    /** Wi-Fi vs mobile vs other, without any
     *  permission-gated lookup (no SSID, no location).
     *  Filtering behaves differently per network type,
     *  so this is the memory key that matters. */
    fun netKey(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return "other"
            val caps = cm.getNetworkCapabilities(net) ?: return "other"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                else -> "other"
            }
        } catch (_: Exception) { "other" }
    }

    private fun memKey(ctx: Context, dedupKey: String): String = "mem_" + netKey(ctx) + "_" + dedupKey

    fun memGet(ctx: Context, dedupKey: String): String? {
        return try { store(ctx).getString(memKey(ctx, dedupKey), null) } catch (_: Exception) { null }
    }

    fun memPut(ctx: Context, dedupKey: String, mutationId: String) {
        try { store(ctx).edit().putString(memKey(ctx, dedupKey), mutationId).apply() } catch (_: Exception) {}
    }

    // ---------- explanation (the "!" dialog) ----------

    fun explainText(ctx: Context): String {
        return AppLanguage.t(ctx, FA_EXPLAIN, EN_EXPLAIN)
    }

    private const val FA_EXPLAIN = """موتور هوشمند چیست؟
موتور هوشمند یک لایه‌ی کمکی است، نه یک وی‌پی‌ان جدا. وقتی روشن باشد، در همه‌ی بخش‌های اتصال (v2ray، وایرگارد و امنزیا، OpenVPN، هیدرا، اتصال هوشمند و اتصال اضطراری) اول موتور امتحان می‌شود و اگر موتور نتوانست، همان اتصال عادی همیشگی انجام می‌شود. یعنی روشن بودن موتور هیچ‌وقت اتصالی را که قبلاً کار می‌کرد خراب نمی‌کند.

وقتی روشن است دقیقاً چه می‌کند؟
• از همان لحظه‌ی اول روش‌های خود موتور را تست می‌کند و دیگر اتصال عادی کانفیگ را امتحان نمی‌کند؛ اگر روشی جواب داد، با همان نسخه وصل می‌شود.
• اگر کانفیگ جواب نداد، گزینه‌ها را موازی و در چند موج می‌سنجد، نه یکی‌یکی: موج اول پرش از یک کانفیگ سالم دیگر (همان کاری که بخش زنجیره دوتایی می‌کند)، فرگمنت کردن بسته‌های TLS، چرخش اثر انگشت مرورگر، درست کردن SNI و شکل‌های بسته‌ای هیدرا (دی‌سینک) است؛ موج دوم آدرس‌های IP جایگزین همان سرور، پورت‌های جایگزین، عوض کردن ترنسپورت و حذف flow است؛ موج‌های بعدی لبه‌های CDN و واپ رایگان کلادفلر (که حسابش داخل خود برنامه ساخته می‌شود) هستند؛ و اگر هیچ موج مستقیمی جواب نداد، موج آخر زنجیره است: کانفیگ هدف به‌عنوان خروجی، از پشت کانفیگ‌های سالم دیگر برنامه.
• نتیجه‌ی هر گزینه را با تست واقعی از داخل همان کانفیگ می‌سنجد، نه با حدس.
• برنده‌ی هر کانفیگ و برنده‌ی کلی هر شبکه (وای‌فای یا موبایل) را به خاطر می‌سپارد تا دفعه‌ی بعد اول همان‌ها امتحان شوند.
• نسخه‌ی کامل برنده‌ی هر کانفیگ روی هر شبکه (مسیر گرم) هم ذخیره می‌شود تا اتصال بعدی همان کانفیگ فقط با یک تست وصل شود؛ وضعیت شبکه (آیا کلادفلر در دسترس است) سنجیده می‌شود تا موج‌های بی‌فایده ساخته نشوند؛ آی‌پی‌هایی که دو بار پشت سر هم مرده‌اند مدتی کنار گذاشته می‌شوند؛ و وقتی یک موج چند گزینه‌ی سالم دارد، برنده با پشتیبان‌هایش در یک تونل می‌نشیند تا مرگ مسیر اصلی، اتصال را نیندازد.
• اگر اتصالی که با موتور برقرار شده وسط کار از نفس بیفتد، نگهبان موتور هر ۳۰ ثانیه از داخل همان تونل تست می‌کند و اگر لازم شد، بدون اینکه تو کاری بکنی، همان‌جا با یک نسخه‌ی بهتر دوباره وصل می‌شود؛ اگر بعد از سه بار هم نشد، اتصال را قطع می‌کند تا ترافیکت در تونل مرده گیر نکند.

در بخش‌های دیگر:
• وایرگارد و امنزیا: آدرس‌های IP و پورت‌های جایگزین همان تونل را با اثبات هندشیک واقعی امتحان می‌کند.
• OpenVPN: آدرس‌های IP جایگزین سرور و بعد پروتکل جایگزین را امتحان می‌کند.
• هیدرا: آدرس‌های IP جایگزین را جلوتر از نسخه‌ی اصلی می‌چیند؛ نردبان خود هیدرا، با دی‌سینک در اول صف، سر جایش می‌ماند؛ این فقط برای نقطه‌های اتصال v2ray است و شکل‌های دی‌سینک مال خود هیدرا است، نه موتور.
• اتصال هوشمند: همان انتخاب‌گر بالا روی کانفیگ هدف اعمال می‌شود؛ منطق خود اتصال هوشمند (زمان‌بندی، قطع و وصل، مالکیت وی‌پی‌ان) هیچ تغییری نمی‌کند؛ فقط برای هدف v2ray و فقط وقتی مسیر سریع جواب نداده باشد.
• اتصال اضطراری: بهترین سرور اضطراری هم با همین موتور بررسی می‌شود؛ مثل همیشه چیزی ذخیره نمی‌شود و اسم واقعی کانفیگ هیچ‌جا دیده نمی‌شود.

موتور کجا کمک می‌کند و کجا بی‌اثر است؟
• کمک می‌کند: v2ray (کامل‌ترین روش‌ها)، وایرگارد و امنزیا (آدرس و پورت جایگزین)، OpenVPN (آدرس جایگزین و پروتکل جایگزین)، اتصال اضطراری (همان روش‌های v2ray)، اتصال هوشمند فقط برای هدف v2ray، هیدرا فقط برای نقطه‌های اتصال v2ray.
• بی‌اثر است: صفحه زنجیره دوتایی (زنجیره با هسته پروکسی خودش کار می‌کند و موتور واردش نمی‌شود)، پنل کلادفلر، اشتراک‌ها و تست پینگ، و صفحه مانیتورینگ (فقط نمایش است).
• دکمه «گزارش موتور» در هر بخشی که موتور می‌تواند در آن کار کند، به‌محض روشن شدن موتور دیده می‌شود؛ در بخش‌هایی که موتور کاری نمی‌کند (زنجیره، پنل کلادفلر، اشتراک‌ها، مانیتورینگ) دکمه‌ای نیست.

تعمیر دسته‌ای:
در بخش v2ray، وقتی موتور روشن است، دکمه‌ی «تعمیر دسته‌ای» کانفیگ‌های خراب را یکی‌یکی با موتور بررسی می‌کند و نسخه‌های تعمیرشده را فقط با تأیید تو، به‌عنوان کانفیگ جدید ذخیره می‌کند؛ کانفیگ اصلی دست نمی‌خورد.

وقتی موتور خاموش می‌شود چه می‌شود؟
همه‌چیز به حالت قبل از روشن شدن برمی‌گردد: حافظه‌ی یادگیری و گزارش‌ها پاک می‌شوند، حساب واپ ساخته‌شده پاک می‌شود، و اگر اتصالی با موتور برقرار بود قطع می‌شود تا با اتصال عادی جایگزین شود. وقتی خاموش است، هیچ دکمه و هیچ فعالیتی از موتور دیده نمی‌شود و برنامه دقیقاً مثل قبل از اضافه شدن موتور کار می‌کند.

چه چیزی را هرگز انجام نمی‌دهد؟
• کانفیگ‌های ذخیره‌شده‌ی شما را تغییر نمی‌دهد و نسخه‌ی تعمیرشده را جایگزین نمی‌کند؛ فقط همان یک بار اتصال با نسخه‌ی بهتر انجام می‌شود (به‌جز تعمیر دسته‌ای که با تأیید خودت ذخیره می‌کند).
• وی‌پی‌ان دستی شما را قطع نمی‌کند.
• وقتی خاموش باشد، هیچ دکمه و هیچ فعالیتی از موتور دیده نمی‌شود و برنامه دقیقاً مثل قبل از اضافه شدن موتور کار می‌کند.

گزارش موتور:
دکمه‌ی «گزارش موتور» در بخش‌هایی که موتور می‌تواند در آن‌ها کار کند (v2ray، وایرگارد و امنزیا، OpenVPN، هیدرا، اتصال هوشمند، اتصال اضطراری) به‌محض روشن شدن موتور دیده می‌شود. داخل آن نوشته شده که موتور چه چیزی را امتحان کرد و چه شد؛ گزارش را می‌توانی کپی کنی و برایم بفرستی.

محدودیت‌های صادقانه:
• اگر سرور خودش خاموش باشد، رمز یا UUID عوض شده باشد، یا اشتراک تمام شده باشد، هیچ دستکاری‌ای آن را زنده نمی‌کند؛ موتور فقط انسداد شبکه‌ای را دور می‌زند.
• بررسی موتور چند ثانیه به اتصال اضافه می‌کند؛ برای کانفیگ سالم معمولاً همان یک تست اول است."""

    private const val EN_EXPLAIN = """What is the Smart Engine?
The Smart Engine is a helper layer, not a separate VPN. When it is on, it is tried FIRST in every connection section (v2ray, WireGuard & Amnezia, OpenVPN, Hydra, Smart Connect and Emergency connect), and if it cannot help, the section's normal connection runs exactly as before. So turning it on can never break a connection that already worked.

What exactly does it do when on?
• From the very first moment it tests the engine's own methods — it no longer tries the config's normal connection first. If a method works, it connects with that variant.
• If the config does not respond, it races the candidates in parallel waves instead of one by one: wave 1 is a jump via another healthy config (the same trick the Double Chain section uses), TLS packet fragmenting, browser fingerprint rotation, SNI repair and Hydra's packet shapes (desync); wave 2 is alternate IPs of the same server, alternate ports, transport swap and flow removal; the later waves are CDN edges and free Cloudflare WARP (whose account is generated inside the app); and if no direct wave works, the last wave is a chain: the target config as the exit, dialed through the app's other healthy configs.
• Every candidate is measured with a real test through that candidate, not a guess.
• The winner per config and the winner per network (Wi-Fi or mobile) are remembered, so next time they are tried first.
• The full winning variant of each config per network (warm route) is also remembered, so the next connect with the same config needs only one probe; the network verdict (is Cloudflare reachable) is measured so useless waves are never built; dial IPs that failed twice in a row are benched for a while; and when a wave has several healthy candidates, the winner sits in one tunnel with its runners-up as standbys, so a dead primary does not drop the connection.
• If an engine-started session dies mid-use, the engine's watchdog tests through the tunnel every 30 seconds and, if needed, reconnects it on the spot with a better variant without you doing anything; if three healings fail, it stops the session so your traffic is not stuck in a dead tunnel.

In the other sections:
• WireGuard & Amnezia: alternate IPs and ports of the same tunnel, proven by a real handshake.
• OpenVPN: alternate server IPs first, then an alternate protocol.
• Hydra: alternate IPs are placed ahead of the original; Hydra's own ladder, with desync first, stays as it is; this is only for v2ray endpoints, and the desync shapes belong to Hydra itself, not the engine.
• Smart Connect: the same selector above is applied to the target config; Smart Connect's own logic (timing, disconnects, VPN ownership) does not change at all; only for a v2ray target, and only when the fast path did not answer.
• Emergency connect: the best emergency server is checked with the same engine; as always nothing is saved and the real config name is never shown.

Where the engine helps, and where it does nothing:
• It helps: v2ray (the full methods), WireGuard & Amnezia (alternate IPs and ports), OpenVPN (alternate IPs and alternate protocol), Emergency connect (the same v2ray methods), Smart Connect only for a v2ray target, Hydra only for v2ray endpoints.
• It does nothing: the Double Chain page (chains run on their own proxy core and the engine never enters them), the Cloudflare panel, subscriptions and ping tests, and the Monitoring page (display only).
• The “Engine report” button appears in every section where the engine can work, as soon as the engine is turned on; sections where the engine does nothing (Chain, Cloudflare panel, subscriptions, Monitoring) have no button.

Batch repair:
In the v2ray section, while the engine is on, a "Batch repair" button checks the dead configs one by one with the engine and saves the repaired copies only with your confirmation, as NEW configs; the originals are never touched.

What happens when the engine is switched off?
Everything returns to the state before it was switched on: learned winners and reports are wiped, the generated WARP account is wiped, and any live engine session is stopped so a normal connection can replace it. When it is off, no button and no activity of the engine is visible, and the app works exactly as before the engine was added.

What does it NEVER do?
• It never changes your saved configs and never replaces one with a repaired copy; only that one connection uses the better variant (except batch repair, which saves only with your confirmation).
• It never stops a hand-started VPN.

Engine report:
An "Engine report" button appears in the sections where the engine can work (v2ray, WireGuard & Amnezia, OpenVPN, Hydra, Smart Connect, Emergency connect) as soon as the engine is turned on. It says what the engine tried and what happened; you can copy the report and send it to me.

Honest limits:
• If the server itself is down, the password or UUID changed, or the subscription expired, no manipulation revives it; the engine only bypasses network-level blocking.
• Checking adds a few seconds before connecting; for a healthy config it is usually just the first test."""

    // ---------- v2ray link surgery ----------

    private data class Mut(val id: String, val link: String, val fragment: Boolean, val shape: String?,
        val labelFa: String, val labelEn: String)

    data class Plan(val link: String, val fragment: Boolean, val noteFa: String, val noteEn: String,
        val shape: String? = null, val shapeHost: String = "",
        val standbys: List<String> = emptyList())

    private fun splitFrag(link: String): Pair<String, String> {
        val i = link.indexOf('#')
        return if (i < 0) link to "" else link.substring(0, i) to link.substring(i)
    }

    private fun queryParam(link: String, key: String): String? {
        val base = splitFrag(link).first
        val q = base.indexOf('?')
        if (q < 0) return null
        val query = base.substring(q + 1)
        for (part in query.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) {
                return try { java.net.URLDecoder.decode(kv[1], "UTF-8") } catch (_: Exception) { kv[1] }
            }
        }
        return null
    }

    private fun setQueryParam(link: String, key: String, value: String): String {
        val (base, frag) = splitFrag(link)
        val q = base.indexOf('?')
        if (q < 0) return base + "?" + key + "=" + value + frag
        val head = base.substring(0, q)
        val query = base.substring(q + 1)
        var done = false
        val parts = query.split("&").map { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) { done = true; key + "=" + value } else part
        }
        val newQuery = if (done) parts.joinToString("&") else parts.joinToString("&") + "&" + key + "=" + value
        return head + "?" + newQuery + frag
    }

    private fun removeQueryParam(link: String, key: String): String {
        val (base, frag) = splitFrag(link)
        val q = base.indexOf('?')
        if (q < 0) return link
        val head = base.substring(0, q)
        val kept = base.substring(q + 1).split("&").filter { part ->
            val kv = part.split("=", limit = 2)
            !(kv.size == 2 && kv[0] == key)
        }
        return if (kept.isEmpty()) head + frag else head + "?" + kept.joinToString("&") + frag
    }

    /** Replace host and/or port in the authority of a URI link.
     *  Returns the original on any surprise (IPv6, odd shape). */
    private fun setAuthority(link: String, newHost: String?, newPort: Int?): String {
        try {
            val (base, frag) = splitFrag(link)
            val schemeEnd = base.indexOf("://")
            if (schemeEnd < 0) return link
            val rest = base.substring(schemeEnd + 3)
            var authEnd = rest.length
            for (ch in charArrayOf('/', '?')) {
                val i = rest.indexOf(ch)
                if (i >= 0 && i < authEnd) authEnd = i
            }
            val authority = rest.substring(0, authEnd)
            val tail = rest.substring(authEnd)
            val atIdx = authority.lastIndexOf('@')
            val userinfo = if (atIdx >= 0) authority.substring(0, atIdx + 1) else ""
            val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
            if (hostPort.count { it == ':' } > 1) return link
            val oldHost = hostPort.substringBefore(":")
            val oldPort = hostPort.substringAfter(":", "").toIntOrNull()
            if (oldHost.isBlank()) return link
            val host = newHost ?: oldHost
            val port = newPort ?: oldPort
            val newAuthority = userinfo + host + if (port != null) ":$port" else ""
            return base.substring(0, schemeEnd + 3) + newAuthority + tail + frag
        } catch (_: Exception) { return link }
    }

    private fun vmessMutate(link: String, mutate: (JSONObject) -> Unit): String? {
        return try {
            val (base, frag) = splitFrag(link)
            var raw = base.removePrefix("vmess://").trim()
            while (raw.length % 4 != 0) raw += "="
            val j = JSONObject(String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8))
            mutate(j)
            val encoded = android.util.Base64.encodeToString(
                j.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).trimEnd('=')
            "vmess://" + encoded + frag
        } catch (_: Exception) { null }
    }

    private fun securityOf(link: String): String {
        if (XraySpeedtest.isCustom(link)) return customSecurity(link)
        return when {
            link.startsWith("vmess://") -> try {
                val (base, _) = splitFrag(link)
                var raw = base.removePrefix("vmess://").trim()
                while (raw.length % 4 != 0) raw += "="
                JSONObject(String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8)).optString("tls", "")
            } catch (_: Exception) { "" }
            else -> queryParam(link, "security") ?: ""
        }
    }

    private fun isUriLink(link: String) = link.startsWith("vless://") || link.startsWith("trojan://")

    /** All IPv4 addresses of a domain host (never an IP
     *  literal, never IPv6): the raw material for the
     *  IP-variant rungs. Called only on background
     *  threads. */
    fun resolveIps(host: String, max: Int = 8): List<String> {
        return try {
            if (host.isBlank() || host.contains(":") || host.matches(Regex("^[0-9.]+$"))) return emptyList()
            val all = java.net.InetAddress.getAllByName(host)
            all.mapNotNull { it.hostAddress }.filter { it.contains(".") && !it.contains(":") }.distinct().take(max)
        } catch (_: Exception) { emptyList() }
    }

    // ---------- fragment injection (JSON level) ----------

    /** Same fragment shape VpnConfigBuilder uses, but
     *  applied to one JSON object on demand — the
     *  engine never flips the global fragment pref,
     *  so a normal connect is never affected by an
     *  engine probe. Skips non-TLS/Reality security
     *  and outbounds that already dial through
     *  another proxy (the builder's own guard). */
    private fun injectFragmentInto(outbound: JSONObject, security: String): Boolean {
        return try {
            val ss = outbound.optJSONObject("streamSettings")
                ?: JSONObject().also { outbound.put("streamSettings", it) }
            if (ss.optString("security", "") != security) return false
            val sockopt = ss.optJSONObject("sockopt")
            if (sockopt != null && sockopt.optString("dialerProxy", "").isNotBlank()) return false
            val packets = if (security == "reality") "1-3" else "tlshello"
            ss.put("finalmask", JSONObject().apply {
                put("tcp", JSONArray().put(JSONObject().apply {
                    put("type", "fragment")
                    put("settings", JSONObject().apply {
                        put("packets", packets)
                        put("length", "50-100")
                        put("delay", "10-20")
                        put("maxSplit", 10)
                    })
                }))
            })
            true
        } catch (_: Exception) { false }
    }

    /** Apply a winning plan to a BUILT VPN JSON (the
     *  string VpnConfigBuilder produced). Null plan,
     *  non-fragment plan, or any error: the input
     *  comes back unchanged. */
    /** Apply a winning plan to a BUILT VPN JSON (the
     *  string VpnConfigBuilder produced). Fragment plans
     *  inject the fragment; packet-shape plans run the
     *  shape through Hydra's applyStrategy on the whole
     *  JSON (it finds the proxy outbound itself). Null
     *  plan, or any error: the input comes back
     *  unchanged, so the connect proceeds normally. */
    fun applyPlanToBuiltJson(ctx: Context, json: String?, plan: Plan?, includeTun: Boolean): String? {
        if (json == null || plan == null) return json
        var out: String = json
        // 2.5: standby balancer — when the winning
        // wave left healthy runners-up, rebuild
        // the config around one balancer so a
        // dead primary fails over in-tunnel.
        // Never for chain or JSON (warp/custom)
        // primaries; any failure keeps the old
        // single-outbound build.
        if (plan.standbys.isNotEmpty() && !plan.link.startsWith("CHAIN::") && !plan.link.trimStart().startsWith("{")) {
            try {
                val bal = VpnConfigBuilder.buildEngineBalancer(ctx, plan.link, plan.standbys, includeTun)
                if (bal != null) out = bal
            } catch (_: Exception) {}
        }
        if (plan.fragment) {
            out = try {
                val root = JSONObject(out)
                val outs = root.optJSONArray("outbounds") ?: return out
                var target: JSONObject? = null
                for (i in 0 until outs.length()) {
                    val ob = outs.optJSONObject(i) ?: continue
                    if (ob.optString("tag", "") == "proxy") { target = ob; break }
                }
                // Customs keep their own outbound tags: fall back
                // to the first real proxy outbound. Link-built
                // JSONs always hit the tag above, so their
                // behaviour is unchanged.
                if (target == null) {
                    for (i in 0 until outs.length()) {
                        val ob = outs.optJSONObject(i) ?: continue
                        if (PROXY_PROTOS.contains(ob.optString("protocol", "").lowercase())) { target = ob; break }
                    }
                }
                if (target != null) {
                    val sec = target.optJSONObject("streamSettings")?.optString("security", "") ?: ""
                    if (sec == "tls" || sec == "reality") injectFragmentInto(target, sec)
                }
                root.toString()
            } catch (_: Exception) { out }
        }
        if (plan.shape != null) {
            out = try {
                val st = HydraEngine.Strategy.valueOf(plan.shape)
                HydraEngine.applyStrategy(out, st, plan.shapeHost) ?: out
            } catch (_: Exception) { out }
        }
        return out
    }

    // ---------- v2ray probing + selector ----------

    /** One delay test through one candidate: a real
     *  request through the candidate (exit proof),
     *  not a TCP ping. -1 on any failure. */
    private fun probeOnce(ctx: Context, link: String, fragment: Boolean, shape: String? = null): Long {
        return try {
            if (!CoreInit.init(ctx)) return -1L
            var config = XraySpeedtest.buildSpeedtestConfig(link) ?: return -1L
            if (fragment) {
                try {
                    val root = JSONObject(config)
                    val outs = root.optJSONArray("outbounds")
                    if (outs == null || outs.length() == 0) return -1L
                    val ob = outs.getJSONObject(0)
                    val sec = ob.optJSONObject("streamSettings")?.optString("security", "") ?: ""
                    if (sec != "tls" && sec != "reality") return -1L
                    if (!injectFragmentInto(ob, sec)) return -1L
                    config = root.toString()
                    // 1.82: a combo probe may ALSO carry a shape;
                    // apply it after the fragment. Single
                    // mutations carry one or the other, so
                    // their behaviour is unchanged.
                    if (shape != null) {
                        try {
                            val st = HydraEngine.Strategy.valueOf(shape)
                            val host = try { XraySpeedtest.parseServer(link)?.host ?: "" } catch (_: Exception) { "" }
                            config = HydraEngine.applyStrategy(config, st, host) ?: return -1L
                        } catch (_: Exception) { return -1L }
                    }
                } catch (_: Exception) { return -1L }
            } else if (shape != null) {
                // Packet-shape probe: Hydra's own shape
                // applied to the speedtest config. A
                // null result means the shape does not
                // apply to this config — fail fast, it
                // is not a network failure.
                try {
                    val st = HydraEngine.Strategy.valueOf(shape)
                    val host = try { XraySpeedtest.parseServer(link)?.host ?: "" } catch (_: Exception) { "" }
                    config = HydraEngine.applyStrategy(config, st, host) ?: return -1L
                } catch (_: Exception) { return -1L }
            }
            libv2ray.Libv2ray.measureOutboundDelay(config, XraySpeedtest.testUrl(ctx))
        } catch (_: Throwable) { -1L }
    }

    /** A probe with a hard timeout: a hung candidate
     *  must never stall the ladder past its budget.
     *  The timed-out worker is abandoned (daemon
     *  thread, one pool per call, shut down after). */
    private fun probeWithTimeout(ctx: Context, link: String, fragment: Boolean, timeoutMs: Long, shape: String? = null): Long {
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true }
        }
        return try {
            val future = pool.submit(java.util.concurrent.Callable<Long> { probeOnce(ctx, link, fragment, shape) })
            try { future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: Exception) { try { future.cancel(true) } catch (_: Exception) {}; -1L }
        } catch (_: Exception) { -1L } finally {
            try { pool.shutdownNow() } catch (_: Exception) {}
        }
    }

    /** One race outcome: the healthy winners
     *  (best delay first, up to maxN) and the
     *  candidates that definitively failed.
     *  Abandoned/unfinished candidates at the
     *  deadline are in NEITHER list — a slow
     *  candidate is not a failed one. */
    private data class RaceOut(val winners: List<Pair<Mut, Long>>, val failed: List<Mut>)

    /** Race one wave of candidates in parallel
     *  (2.5: scored). Every candidate gets a real
     *  delay test at the same time. Winners are
     *  scored by delay, not by arrival: after the
     *  first success, a short grace window (250 ms,
     *  only when more than one candidate runs)
     *  lets a better one still win, and the healthy
     *  set is kept as standby routes. Bounded by
     *  the shared deadline; daemon threads only. */
    private fun raceWaveTop(ctx: Context, muts: List<Mut>, deadline: Long, maxN: Int, graceMs: Long): RaceOut {
        if (muts.isEmpty()) return RaceOut(emptyList(), emptyList())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            minOf(16, muts.size)) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<Mut, Long>>(pool)
            val futures = muts.map { m ->
                completion.submit(java.util.concurrent.Callable {
                    m to probeOnce(ctx, m.link, m.fragment, m.shape)
                })
            }
            val healthy = mutableListOf<Pair<Mut, Long>>()
            val failed = mutableListOf<Mut>()
            var remaining = muts.size
            var graceEnd: Long? = null
            while (remaining > 0) {
                val now = System.currentTimeMillis()
                val left = deadline - now
                if (left <= 0) break
                val wait = if (graceEnd != null) minOf(left, graceEnd - now) else left
                if (wait <= 0) break
                val f = completion.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                remaining--
                val res = try { f.get() } catch (_: Exception) { null }
                if (res != null && res.second >= 0) {
                    healthy += res
                    if (healthy.size >= maxN) break
                    if (graceEnd == null && muts.size > 1 && graceMs > 0) {
                        graceEnd = minOf(deadline, System.currentTimeMillis() + graceMs)
                    }
                } else if (res != null) {
                    failed += res.first
                }
            }
            for (fu in futures) { try { fu.cancel(true) } catch (_: Exception) {} }
            return RaceOut(healthy.sortedBy { it.second }.take(maxN), failed)
        } catch (_: Exception) { return RaceOut(emptyList(), emptyList()) } finally {
            try { pool.shutdownNow() } catch (_: Exception) {}
        }
    }

    /** First-healthy-win race (old behaviour):
     *  used where only one winner is needed. */
    private fun raceWave(ctx: Context, muts: List<Mut>, deadline: Long): Pair<Mut, Long>? {
        return raceWaveTop(ctx, muts, deadline, 1, 0).winners.firstOrNull()
    }

    /** The v2ray selector (1.79: parallel racing).
     *  Synchronous — the caller runs it on a
     *  background thread. Returns a Plan for the
     *  winning variant, or null meaning "use the
     *  normal connect" (original healthy, or nothing
     *  worked, or time ran out). Never throws: any
     *  error degrades to null + a report line, so
     *  the normal path always survives.
     *
     *  Order: (0) the original, untouched — a healthy
     *  config is left completely alone; (1) the
     *  per-config memory winner, alone; (2) WAVE 1,
     *  raced in parallel: jump via a healthy config
     *  (the path that already works in Double Chain),
     *  the network's global-learning winner, TLS
     *  fragment, fingerprint rotation, SNI repair,
     *  and Hydra's packet shapes; (3) WAVE 2, raced:
     *  alternate IPs, alternate ports, transport
     *  swap, flow removal; (3.5) WAVE 2.5, raced:
     *  CDN pool scan for TLS WS/gRPC/HTTP configs
     *  with an explicit SNI behind a known CDN;
     *  (4) WAVE 3: a free Cloudflare WARP account
     *  generated in-app.
     *  Emergency section: generic names only, no
     *  IPs in labels, and nothing is memorized. */
    /** One vmess JSON field, decoded (same pattern
     *  securityOf uses for "tls"). */
    private fun vmessField(link: String, key: String): String {
        return try {
            val (base, _) = splitFrag(link)
            var raw = base.removePrefix("vmess://").trim()
            while (raw.length % 4 != 0) raw += "="
            JSONObject(String(android.util.Base64.decode(raw, android.util.Base64.URL_SAFE), Charsets.UTF_8)).optString(key, "")
        } catch (_: Exception) { "" }
    }

    /** CDN-scan wave (1.81): for a dead TLS
     *  WS/gRPC/HTTP config with an explicit SNI
     *  whose address is (or resolves to) a known
     *  CDN edge, race sampled edge IPs of that
     *  CDN with the SNI unchanged. Returns the
     *  CDN's Persian name + the candidate Mut
     *  list, or null when the wave does not
     *  apply. CDN wins are never memorized (see
     *  win()): pool samples rotate, and IPs are
     *  server-specific by rule. Emergency uses
     *  generic labels, never the IP value. */
    private fun cdnMuts(cfg: String, host: String, emergency: Boolean): Pair<String, List<Mut>>? {
        try {
            if (securityOf(cfg) != "tls") return null
            val net = if (XraySpeedtest.isCustom(cfg)) customNetwork(cfg).ifBlank { "tcp" }
                      else if (cfg.startsWith("vmess://")) vmessField(cfg, "net").ifBlank { "tcp" }
                      else queryParam(cfg, "type") ?: "tcp"
            if (net != "ws" && net != "grpc" && net != "http") return null
            val sni = if (XraySpeedtest.isCustom(cfg)) customSni(cfg)
                      else if (cfg.startsWith("vmess://")) vmessField(cfg, "sni")
                      else queryParam(cfg, "sni") ?: ""
            if (sni.isBlank()) return null
            val probeIps = if (host.matches(Regex("^[0-9.]+$"))) listOf(host) else resolveIps(host, 8)
            var cdn: String? = null
            for (ip in probeIps) { cdn = CdnPools.detect(ip); if (cdn != null) break }
            if (cdn == null) return null
            val nameFa = CdnPools.nameFa(cdn)
            val nameEn = CdnPools.nameEn(cdn)
            val out = mutableListOf<Mut>()
            for (ip in CdnPools.candidates(cdn, 64)) {
                if (probeIps.contains(ip)) continue
                val v = if (XraySpeedtest.isCustom(cfg)) customMutate(cfg) { ob -> setAddress(ob, ip) }
                        else if (cfg.startsWith("vmess://")) vmessMutate(cfg) { j -> j.put("add", ip) }
                        else setAuthority(cfg, ip, null)
                if (v != null && v != cfg) {
                    val lblFa = if (emergency) "آی‌پی CDN جایگزین" else "CDN $nameFa $ip"
                    val lblEn = if (emergency) "CDN alternate IP" else "CDN $nameEn $ip"
                    out += Mut("cdn", v, false, null, lblFa, lblEn)
                }
            }
            if (out.isEmpty()) return null
            return nameFa to out
        } catch (_: Exception) { return null }
    }

    // ---------- custom JSON configs (1.82) ----------
    // The engine used to skip customs entirely. Their proxy
    // outbound already works with the JSON-level machinery
    // (fragment injection, packet shapes, speedtest build),
    // so 1.82 adds JSON-level address/port/SNI/fingerprint/
    // flow mutations. Transport swap and jump stay link-only:
    // a transport swap needs counterpart settings built from
    // scratch, and a CHAIN:: hop cannot safely embed a
    // multi-line JSON.
    private val PROXY_PROTOS = setOf("vmess", "vless", "trojan", "shadowsocks",
        "socks", "http", "wireguard", "hysteria2", "hysteria", "tuic")

    private fun customOutboundIndex(root: org.json.JSONObject): Int {
        val arr = root.optJSONArray("outbounds") ?: return -1
        for (i in 0 until arr.length()) {
            val ob = arr.optJSONObject(i) ?: continue
            if (PROXY_PROTOS.contains(ob.optString("protocol", "").lowercase())) return i
        }
        return -1
    }

    /** Edit the proxy outbound of a custom JSON in place.
     *  The edit reports whether it changed anything; no
     *  change (or any error) yields null, so a no-op
     *  mutation never enters the race. */
    private fun customMutate(cfg: String, edit: (org.json.JSONObject) -> Boolean): String? {
        return try {
            val root = org.json.JSONObject(cfg)
            val idx = customOutboundIndex(root)
            if (idx < 0) return null
            if (!edit(root.getJSONArray("outbounds").getJSONObject(idx))) return null
            root.toString()
        } catch (_: Exception) { null }
    }

    private fun customProto(cfg: String): String {
        return try {
            XraySpeedtest.customProxyOutbound(cfg)?.optString("protocol", "")?.lowercase() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun customSecurity(cfg: String): String {
        return try {
            XraySpeedtest.customProxyOutbound(cfg)
                ?.optJSONObject("streamSettings")?.optString("security", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun customNetwork(cfg: String): String {
        return try {
            XraySpeedtest.customProxyOutbound(cfg)
                ?.optJSONObject("streamSettings")?.optString("network", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun customTlsOrReality(ob: org.json.JSONObject): org.json.JSONObject? {
        val ss = ob.optJSONObject("streamSettings") ?: return null
        return when (ss.optString("security", "")) {
            "tls" -> ss.optJSONObject("tlsSettings")
            "reality" -> ss.optJSONObject("realitySettings")
            else -> null
        }
    }

    private fun customSni(cfg: String): String {
        return try {
            val ob = XraySpeedtest.customProxyOutbound(cfg) ?: return ""
            customTlsOrReality(ob)?.optString("serverName", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun customFp(cfg: String): String {
        return try {
            val ob = XraySpeedtest.customProxyOutbound(cfg) ?: return ""
            customTlsOrReality(ob)?.optString("fingerprint", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun customFlow(cfg: String): String {
        return try {
            val ob = XraySpeedtest.customProxyOutbound(cfg) ?: return ""
            val vnext = ob.optJSONObject("settings")?.optJSONArray("vnext") ?: return ""
            vnext.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)
                ?.optString("flow", "") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun setSni(ob: org.json.JSONObject, host: String): Boolean {
        val st = customTlsOrReality(ob) ?: return false
        if (st.optString("serverName", "") == host) return false
        st.put("serverName", host)
        return true
    }

    private fun setFp(ob: org.json.JSONObject, fp: String): Boolean {
        val st = customTlsOrReality(ob) ?: return false
        if (st.optString("fingerprint", "") == fp) return false
        st.put("fingerprint", fp)
        return true
    }

    private fun setAddress(ob: org.json.JSONObject, ip: String): Boolean {
        val st = ob.optJSONObject("settings") ?: return false
        val vnext = st.optJSONArray("vnext")
        if (vnext != null && vnext.length() > 0) {
            val v = vnext.optJSONObject(0) ?: return false
            if (v.optString("address", "") == ip) return false
            v.put("address", ip)
            return true
        }
        val servers = st.optJSONArray("servers")
        if (servers != null && servers.length() > 0) {
            val v = servers.optJSONObject(0) ?: return false
            if (v.optString("address", "") == ip) return false
            v.put("address", ip)
            return true
        }
        return false
    }

    private fun setPort(ob: org.json.JSONObject, port: Int): Boolean {
        val st = ob.optJSONObject("settings") ?: return false
        val vnext = st.optJSONArray("vnext")
        if (vnext != null && vnext.length() > 0) {
            val v = vnext.optJSONObject(0) ?: return false
            if (v.optInt("port", -1) == port) return false
            v.put("port", port)
            return true
        }
        val servers = st.optJSONArray("servers")
        if (servers != null && servers.length() > 0) {
            val v = servers.optJSONObject(0) ?: return false
            if (v.optInt("port", -1) == port) return false
            v.put("port", port)
            return true
        }
        return false
    }

    private fun removeFlow(ob: org.json.JSONObject): Boolean {
        val users = ob.optJSONObject("settings")?.optJSONArray("vnext")
            ?.optJSONObject(0)?.optJSONArray("users") ?: return false
        val u = users.optJSONObject(0) ?: return false
        if (u.optString("flow", "").isBlank()) return false
        u.remove("flow")
        return true
    }

    /** Port change for any candidate: a custom JSON gets a
     *  JSON edit, a vmess link a field edit, other links an
     *  authority edit. Used by the deep wave's combos. */
    private fun withPort(link: String, port: Int): String? {
        return if (XraySpeedtest.isCustom(link)) customMutate(link) { ob -> setPort(ob, port) }
               else if (link.startsWith("vmess://")) vmessMutate(link) { j -> j.put("port", port.toString()) }
               else setAuthority(link, null, port)
    }


    /** dedup keys of configs that measured healthy in a persisted
     *  subscription ping test ("renamer_subs" ping_delays, values >= 0).
     *  This is the durable half of the wave-1/wave-5 pool: unlike the
     *  v2ray page's in-memory ping map, it survives process death and is
     *  visible to headless callers (Smart) that never opened the page. */
    fun persistedHealthyDedups(ctx: Context): Set<String> {
        return try {
            val j = org.json.JSONObject(ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
                .getString("ping_delays", "{}") ?: "{}")
            val out = mutableSetOf<String>()
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (j.optLong(k, -1L) >= 0) out += k
            }
            out
        } catch (_: Exception) { emptySet() }
    }

    /** the persisted healthy links themselves, in stored order
     *  (the subscription service's final healthy list). Generic links
     *  only — callers never log their contents. */
    fun lastHealthyLinks(ctx: Context): List<String> {
        return try {
            (ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
                .getString("last_healthy", "") ?: "")
                .lines().filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    /** filter a config list down to the ones with a persisted
     *  healthy delay. Callers merge this with their in-memory pool. */
    fun persistedPool(ctx: Context, configs: List<String>): List<String> {
        val keys = persistedHealthyDedups(ctx)
        if (keys.isEmpty()) return emptyList()
        return configs.filter { XraySpeedtest.dedupKey(it) in keys }
    }

    fun selectV2ray(ctx: Context, cfg: String, section: String, budgetMs: Long, workingPool: List<String>): Plan? {
        try {
            if (XraySpeedtest.isGroup(cfg) || XraySpeedtest.isChain(cfg)) {
                append(ctx, section,
                    "موتور: این کانفیگ تکی نیست (گروه/زنجیره)؛ موتور رد شد و اتصال عادی انجام می‌شود.",
                    "Engine: not a single config (group/chain); engine skipped, normal connect follows.")
                return null
            }
            val deadline = System.currentTimeMillis() + budgetMs
            val emergency = section == S_EMERGENCY
            val isCustom = XraySpeedtest.isCustom(cfg)
            val name = if (emergency) AppLanguage.t(ctx, "اتصال اضطراری", "Emergency connect")
                       else XraySpeedtest.displayName(cfg)
            // 1.82: FIRST report line classifies the config
            // exactly like v2rayNG (fullType) and, when its host
            // sits behind a known CDN, names the family and says
            // it is behind a CDN. Emergency: family name only —
            // never host/IP values (emergency privacy).
            val typeLine = try {
                val ft = XraySpeedtest.fullType(cfg)
                val srv0 = try { XraySpeedtest.parseServer(cfg) } catch (_: Exception) { null }
                val h0 = srv0?.host ?: ""
                val cdn = when {
                    h0.isBlank() -> null
                    h0.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) -> CdnPools.detect(h0)
                    else -> resolveIps(h0, 8).firstNotNullOfOrNull { CdnPools.detect(it) }
                }
                if (cdn != null) "$ft · پشت CDN ${CdnPools.nameFa(cdn)}" else ft
            } catch (_: Exception) { XraySpeedtest.fullType(cfg) }
            append(ctx, section,
                "موتور: نوع کانفیگ «$typeLine»." ,
                "Engine: config type \"$typeLine\".")
            // 2.12: the engine no longer tries the
            // config's normal connection first. From
            // the very first moment it tests the
            // engine's own methods. (The previous
            // tech profile still probes the original
            // first, for the Settings rollback.)
            if (RollbackManager.techIsPrevious(ctx)) {
                // Step 0: the original, untouched.
                val origMs = probeWithTimeout(ctx, cfg, false, 10000)
                if (origMs >= 0) {
                    append(ctx, section,
                        "موتور: «$name» سالم است ($origMs ms)؛ بدون هیچ تغییری وصل می‌شود.",
                        "Engine: \"$name\" is healthy ($origMs ms); connecting unchanged.")
                    return null
                }
                append(ctx, section,
                    "موتور: «$name» جواب نداد؛ بررسی موازی شروع شد.",
                    "Engine: \"$name\" did not respond; parallel check started.")
            } else {
                append(ctx, section,
                    "موتور: روش‌های موتور برای «$name» شروع شد.",
                    "Engine: engine methods started for \"$name\".")
            }
            // 2.5: network verdict — measured once
            // per network (30-min cache), skipped
            // while a VPN is already up. It only
            // gates the Cloudflare CDN wave below;
            // an unknown verdict gates nothing.
            val verdict = verdictGet(ctx)
            if (verdict != null) {
                append(ctx, section,
                    "موتور: وضعیت شبکه — کلادفلر " + (if (verdict.cfOk) "در دسترس است" else "قطع است") + ".",
                    "Engine: network verdict — Cloudflare " + (if (verdict.cfOk) "reachable" else "cut") + ".")
            }
            val sec = securityOf(cfg)
            val isTlsOrReality = sec == "tls" || sec == "reality"
            val isReality = sec == "reality"
            val isTlsOnly = sec == "tls"
            val srv = try { XraySpeedtest.parseServer(cfg) } catch (_: Exception) { null }
            val host = srv?.host ?: ""
            val port = srv?.port ?: 443
            val dedup = XraySpeedtest.dedupKey(cfg)
            // 2.37: DoH IPs in parallel — the lookup
            // overlaps the verdict and wave 1, so
            // poisoned system DNS gets a second
            // opinion at no serial cost. Collected
            // into wave 2 below.
            val dohIpsRef = java.util.concurrent.atomic.AtomicReference<List<String>?>(null)
            val dohThread = Thread {
                try {
                    if (host.isNotBlank() && !host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")))
                        dohIpsRef.set(resolveIpsDoh(host, 4))
                } catch (_: Exception) {}
            }.also { it.start() }
            val hostOf = host
            fun win(m: Mut, ms: Long, standbys: List<String> = emptyList()): Plan {
                if (!emergency) {
                    if (m.id != "cdn" && !m.id.startsWith("combo:") && !m.id.startsWith("chain:")) memPut(ctx, dedup, m.id)
                    // Global learning: the winning
                    // CLASS is remembered per network,
                    // so the next dead config on this
                    // network tries it first. IPs are
                    // server-specific and never go
                    // global; jump/warp need no help.
                    val gclass = when {
                        m.id == "fragment" -> "fragment"
                        m.id.startsWith("fp:") -> m.id
                        m.id == "sni:addr" -> "sni:addr"
                        m.id.startsWith("shape:") -> m.id
                        m.id.startsWith("port:") -> m.id
                        m.id.startsWith("transport:") -> m.id
                        m.id == "flow:none" -> "flow:none"
                        else -> null
                    }
                    if (gclass != null && m.id != "cdn") gmemPut(ctx, gclass)
                    // Warm route: the full winning
                    // variant, so a repeat connect
                    // is one probe. Never warp or
                    // combos; chains are allowed —
                    // they were probe-validated.
                    if (m.id != "warp" && !m.id.startsWith("combo:")) {
                        warmPut(ctx, dedup, m.link, m.fragment, m.shape)
                    }
                    // A win rehabilitates a benched
                    // dial IP at once.
                    dialIpOf(m)?.let { brkClear(ctx, it) }
                }
                append(ctx, section,
                    "موتور: ✓ «$name» با «${m.labelFa}» ($ms ms)",
                    "Engine: ✓ \"$name\" with \"${m.labelEn}\" ($ms ms)")
                return Plan(m.link, m.fragment, m.labelFa, m.labelEn, m.shape, hostOf, standbys)
            }
            // Standby links from a scored race: the
            // healthy runners-up, primary excluded.
            // Never for emergency, chain or warp
            // primaries, and never chain/warp links.
            fun standbyLinks(out: RaceOut, winner: Mut): List<String> {
                if (emergency) return emptyList()
                if (winner.link.startsWith("CHAIN::") || winner.id == "warp") return emptyList()
                return out.winners.drop(1).mapNotNull { (wm, _) ->
                    if (wm.link.startsWith("CHAIN::") || wm.id == "warp") null
                    else if (wm.link == winner.link) null
                    else wm.link
                }.distinct().take(2)
            }
            fun waveReport(wave: Int, labels: String) {
                append(ctx, section,
                    "موتور: موج $wave ($labels) ✗",
                    "Engine: wave $wave ($labels) ✗")
            }
            val muts = mutableListOf<Mut>()
            if (isTlsOrReality) muts += Mut("fragment", cfg, true, null,
                if (sec == "reality") "فرگمنت Reality (1-3, 50-100)" else "فرگمنت TLS (tlshello, 50-100)",
                if (sec == "reality") "Reality fragment (1-3, 50-100)" else "TLS fragment (tlshello, 50-100)")
            if (isUriLink(cfg) && isTlsOrReality) {
                val curFp = queryParam(cfg, "fp") ?: "chrome"
                for (fp in listOf("chrome", "firefox", "safari")) {
                    if (fp != curFp) muts += Mut("fp:$fp", setQueryParam(cfg, "fp", fp), false, null, "اثر انگشت $fp", "fingerprint $fp")
                }
            }
            if (isCustom && isTlsOrReality) {
                val curFp = customFp(cfg)
                if (curFp.isNotBlank()) {
                    for (fp in listOf("chrome", "firefox", "safari")) {
                        if (fp != curFp) {
                            val v = customMutate(cfg) { ob -> setFp(ob, fp) }
                            if (v != null) muts += Mut("fp:$fp", v, false, null, "اثر انگشت $fp", "fingerprint $fp")
                        }
                    }
                }
            }
            if (!isReality && isUriLink(cfg)) {
                val sni = queryParam(cfg, "sni")
                if (sni != null && sni.isNotBlank() && sni != host && host.isNotBlank()) {
                    muts += Mut("sni:addr", setQueryParam(cfg, "sni", host), false, null,
                        if (emergency) "SNI برابر با آدرس سرور" else "SNI $host (قبلاً $sni)",
                        if (emergency) "SNI = server address" else "SNI $host (was $sni)")
                }
            }
            val vmessOldSni = vmessField(cfg, "sni")
            val vmessNewSni = vmessField(cfg, "add")
            if (!isReality && cfg.startsWith("vmess://")) {
                val vm = vmessMutate(cfg) { j ->
                    val sni = j.optString("sni", "")
                    val add = j.optString("add", "")
                    if (sni.isNotBlank() && add.isNotBlank() && sni != add) j.put("sni", add)
                }
                if (vm != null && vm != cfg) muts += Mut("sni:addr", vm, false, null,
                    if (emergency) "SNI برابر با آدرس سرور" else "SNI $vmessNewSni (قبلاً $vmessOldSni)",
                    if (emergency) "SNI = server address" else "SNI $vmessNewSni (was $vmessOldSni)")
            }
            if (isCustom && !isReality) {
                val sni = customSni(cfg)
                if (sni.isNotBlank() && sni != host && host.isNotBlank()) {
                    val v = customMutate(cfg) { ob -> setSni(ob, host) }
                    if (v != null) {
                        val lblFa = if (emergency) "SNI برابر با آدرس سرور" else "SNI $host (قبلاً $sni)"
                        val lblEn = if (emergency) "SNI = server address" else "SNI $host (was $sni)"
                        muts += Mut("sni:addr", v, false, null, lblFa, lblEn)
                    }
                }
            }
            // Packet shapes from Hydra's arsenal
            // (JSON level, not link level): desync
            // fragments, split/long fragments, a
            // randomized fingerprint, ALPN variants
            // (TLS only) and mux (vmess/vless only).
            if (isTlsOrReality) {
                muts += Mut("shape:DESYNC_CLASSIC", cfg, false, "DESYNC_CLASSIC", "شکست بسته کلاسیک", "desync classic")
                muts += Mut("shape:DESYNC_CLASSIC_LONG", cfg, false, "DESYNC_CLASSIC_LONG", "شکست بسته کلاسیک بلند", "desync classic long")
                muts += Mut("shape:DESYNC_TINY", cfg, false, "DESYNC_TINY", "شکست بسته ریز", "desync tiny")
                muts += Mut("shape:FRAGMENT_SPLIT", cfg, false, "FRAGMENT_SPLIT", "فرگمنت تکه‌ای", "fragment split")
                muts += Mut("shape:FRAGMENT_LONG", cfg, false, "FRAGMENT_LONG", "فرگمنت بلند", "fragment long")
                muts += Mut("shape:FINGERPRINT_RANDOM", cfg, false, "FINGERPRINT_RANDOM", "اثر انگشت تصادفی", "randomized fingerprint")
            }
            if (isTlsOnly) {
                muts += Mut("shape:ALPN_H2", cfg, false, "ALPN_H2", "ALPN h2", "ALPN h2")
                muts += Mut("shape:ALPN_HTTP11", cfg, false, "ALPN_HTTP11", "ALPN http/1.1", "ALPN http/1.1")
                muts += Mut("shape:ALPN_NONE", cfg, false, "ALPN_NONE", "بدون ALPN", "no ALPN")
            }
            if (cfg.startsWith("vmess://") || cfg.startsWith("vless://") || (isCustom && (customProto(cfg) == "vmess" || customProto(cfg) == "vless"))) {
                muts += Mut("shape:MUX", cfg, false, "MUX", "مالتی‌پلکس", "mux")
            }
            if (host.isNotBlank()) {
                for (ip in resolveIps(host)) {
                    val v = if (isCustom) customMutate(cfg) { ob -> setAddress(ob, ip) }
                            else if (cfg.startsWith("vmess://")) vmessMutate(cfg) { j -> j.put("add", ip) } else setAuthority(cfg, ip, null)
                    if (v != null && v != cfg) {
                        val lblFa = if (emergency) "آدرس IP جایگزین" else "آدرس IP جایگزین $ip"
                        val lblEn = if (emergency) "alternate IP" else "alternate IP $ip"
                        muts += Mut("ip:$ip", v, false, null, lblFa, lblEn)
                    }
                }
            }
            for (p in listOf(443, 8443, 2053, 2083, 2096)) {
                if (p == port) continue
                val v = if (isCustom) customMutate(cfg) { ob -> setPort(ob, p) }
                        else if (cfg.startsWith("vmess://")) vmessMutate(cfg) { j -> j.put("port", p.toString()) } else setAuthority(cfg, null, p)
                if (v != null && v != cfg) {
                    muts += Mut("port:$p", v, false, null, "پورت $p", "port $p")
                    if (muts.count { it.id.startsWith("port:") } >= 3) break
                }
            }
            if (isUriLink(cfg)) {
                val t = queryParam(cfg, "type") ?: "tcp"
                if (t == "ws") muts += Mut("transport:grpc", setQueryParam(cfg, "type", "grpc"), false, null, "ترنسپورت WS به gRPC", "transport WS to gRPC")
                else if (t == "grpc") muts += Mut("transport:ws", setQueryParam(cfg, "type", "ws"), false, null, "ترنسپورت gRPC به WS", "transport gRPC to WS")
            }
            if (cfg.startsWith("vless://") && !queryParam(cfg, "flow").isNullOrBlank()) {
                val fl0 = queryParam(cfg, "flow") ?: ""
                muts += Mut("flow:none", removeQueryParam(cfg, "flow"), false, null,
                    if (emergency) "حذف flow" else "حذف flow $fl0",
                    if (emergency) "flow removed" else "flow $fl0 removed")
            }
            if (isCustom) {
                val fl = customFlow(cfg)
                if (fl.isNotBlank()) {
                    val v = customMutate(cfg) { ob -> removeFlow(ob) }
                    if (v != null) {
                        val lblFa = if (emergency) "حذف flow" else "حذف flow $fl"
                        val lblEn = if (emergency) "flow removed" else "flow $fl removed"
                        muts += Mut("flow:none", v, false, null, lblFa, lblEn)
                    }
                }
            }
            val jumpTarget = workingPool.firstOrNull { XraySpeedtest.dedupKey(it) != dedup }
            if (jumpTarget != null && !isCustom) {
                muts += Mut("jump", "CHAIN::موتور هوشمند|||" + cfg + "|||" + jumpTarget, false, null,
                    if (emergency) "پرش از کانفیگ سالم" else "پرش از «${XraySpeedtest.displayName(jumpTarget)}»",
                    if (emergency) "jump via healthy config" else "jump via \"${XraySpeedtest.displayName(jumpTarget)}\"")
            }
            // 2.5: warm route — the full winning
            // variant of this config on this
            // network, probed alone first. A
            // stale route just falls through.
            if (!emergency) {
                val warmRaw = warmGet(ctx, dedup)
                if (warmRaw != null && System.currentTimeMillis() <= deadline) {
                    val wms = probeWithTimeout(ctx, warmRaw.first, warmRaw.second, 10000, warmRaw.third)
                    if (wms >= 0) {
                        append(ctx, section,
                            "موتور: «$name» با مسیر گرم ($wms ms) وصل می‌شود.",
                            "Engine: \"$name\" connects via its warm route ($wms ms).")
                        return Plan(warmRaw.first, warmRaw.second, "مسیر گرم", "warm route", warmRaw.third, hostOf)
                    }
                }
            }
            // Per-config memory winner, alone first:
            // a repeat repair is usually one probe.
            if (!emergency) {
                val mem = memGet(ctx, dedup)
                if (mem != null) {
                    val w = muts.firstOrNull { it.id == mem }
                    if (w != null && System.currentTimeMillis() <= deadline) {
                        val mw = raceWave(ctx, listOf(w), deadline)
                        if (mw != null) return win(mw.first, mw.second)
                    }
                }
            }
            // WAVE 1: jump + global winner + fragment
            // + fingerprints + SNI + packet shapes.
            val wave1 = mutableListOf<Mut>()
            muts.firstOrNull { it.id == "jump" }?.let { wave1 += it }
            if (!emergency) {
                val g = gmemGet(ctx)
                if (g != null) {
                    val gm = muts.firstOrNull { it.id == g }
                    if (gm != null && gm !in wave1) wave1 += gm
                }
            }
            for (m in muts) {
                if (m.id == "jump") continue
                if (m.id == "fragment" || m.id.startsWith("fp:") || m.id == "sni:addr" || m.id.startsWith("shape:")) {
                    if (m !in wave1) wave1 += m
                }
            }
            if (System.currentTimeMillis() > deadline) {
                append(ctx, section,
                    "موتور: زمان بررسی تمام شد؛ اتصال عادی انجام می‌شود.",
                    "Engine: check time ran out; normal connect follows.")
                return null
            }
            val w1out = raceWaveTop(ctx, wave1, deadline, 3, 250)
            val w1 = w1out.winners.firstOrNull()
            if (w1 != null) return win(w1.first, w1.second, standbyLinks(w1out, w1.first))
            waveReport(1, wave1.joinToString("، ") { it.labelFa })
            // WAVE 2: IPs + ports + transport + flow.
            val wave2base = muts.filter { m ->
                m.id.startsWith("ip:") || m.id.startsWith("port:") ||
                m.id.startsWith("transport:") || m.id == "flow:none"
            }
            // 2.37: DoH IPs join wave 2 (deduped
            // against system-DNS IPs; emergency
            // labels never carry the address).
            try { dohThread.join(5000) } catch (_: Exception) {}
            val dohMuts = mutableListOf<Mut>()
            try {
                val sysIps = muts.filter { it.id.startsWith("ip:") }
                    .map { it.id.removePrefix("ip:") }.toMutableSet()
                for (ip in (dohIpsRef.get() ?: emptyList())) {
                    if (!sysIps.add(ip)) continue
                    val v = if (isCustom) customMutate(cfg) { ob -> setAddress(ob, ip) }
                            else if (cfg.startsWith("vmess://")) vmessMutate(cfg) { j -> j.put("add", ip) } else setAuthority(cfg, ip, null)
                    if (v != null && v != cfg) {
                        val lblFa = if (emergency) "آدرس IP جایگزین" else "آدرس IP جایگزین $ip (DoH)"
                        val lblEn = if (emergency) "alternate IP" else "alternate IP $ip (DoH)"
                        dohMuts += Mut("ip:doh:$ip", v, false, null, lblFa, lblEn)
                    }
                }
            } catch (_: Exception) {}
            val wave2 = wave2base + dohMuts
            if (System.currentTimeMillis() > deadline) {
                append(ctx, section,
                    "موتور: زمان بررسی تمام شد؛ اتصال عادی انجام می‌شود.",
                    "Engine: check time ran out; normal connect follows.")
                return null
            }
            // 2.5: benched dial IPs (two failures
            // on this network) are skipped, and
            // fresh failures feed the breaker.
            val wave2live = if (emergency) wave2 else wave2.filter { m ->
                val ip = dialIpOf(m)
                ip == null || !brkActive(ctx, ip)
            }
            val w2out = raceWaveTop(ctx, wave2live, deadline, 3, 250)
            val w2 = w2out.winners.firstOrNull()
            if (!emergency) for (fm in w2out.failed) { dialIpOf(fm)?.let { brkBump(ctx, it) } }
            if (w2 != null) return win(w2.first, w2.second, standbyLinks(w2out, w2.first))
            waveReport(2, wave2.joinToString("، ") { it.labelFa })
            // WAVE 2.5: CDN pool scan (1.81) — only
            // for TLS WS/gRPC/HTTP configs with an
            // explicit SNI whose address is a known
            // CDN edge; sampled edge IPs of that
            // CDN race with the SNI unchanged.
            if (System.currentTimeMillis() <= deadline) {
                val cdnWave = cdnMuts(cfg, host, emergency)
                if (cdnWave != null) {
                    val (cdnName, cdnList0) = cdnWave
                    // 2.5 gate: when the verdict says
                    // Cloudflare is cut on this network
                    // AND the target sits behind
                    // Cloudflare, the pool is dead on
                    // arrival — skip it, don't burn time.
                    var skipCdn = false
                    if (verdict != null && !verdict.cfOk) {
                        val fam = try {
                            if (host.matches(Regex("^[0-9.]+$"))) CdnPools.detect(host)
                            else resolveIps(host, 8).firstNotNullOfOrNull { CdnPools.detect(it) }
                        } catch (_: Exception) { null }
                        if (fam == "cloudflare") skipCdn = true
                    }
                    if (skipCdn) {
                        append(ctx, section,
                            "موتور: موج CDN رد شد (کلادفلر روی این شبکه قطع است).",
                            "Engine: CDN wave skipped (Cloudflare is cut on this network).")
                    } else {
                        val cdnList = if (emergency) cdnList0 else cdnList0.filter { m ->
                            val ip = dialIpOf(m)
                            ip == null || !brkActive(ctx, ip)
                        }
                        if (cdnList.isEmpty()) {
                            append(ctx, section,
                                "موتور: موج CDN رد شد (همه‌ی لبه‌ها موقتاً کنار گذاشته شده‌اند).",
                                "Engine: CDN wave skipped (all edges temporarily benched).")
                        } else {
                            val wcOut = raceWaveTop(ctx, cdnList, deadline, 3, 250)
                            val wc = wcOut.winners.firstOrNull()
                            if (!emergency) for (fm in wcOut.failed) { dialIpOf(fm)?.let { brkBump(ctx, it) } }
                            if (wc != null) return win(wc.first, wc.second, standbyLinks(wcOut, wc.first))
                            append(ctx, section,
                                "موتور: موج CDN ($cdnName، ${cdnList.size} آی‌پی) جواب نداد.",
                                "Engine: CDN wave ($cdnName, ${cdnList.size} IPs) did not respond.")
                        }
                    }
                }
            } else {
                append(ctx, section,
                    "موتور: موج CDN رد شد (مهلت بررسی تمام شد).",
                    "Engine: CDN wave skipped (check deadline passed).")
            }
            // WAVE 3: free Cloudflare WARP, generated
            // in-app (last resort, needs registration).
            if (System.currentTimeMillis() <= deadline) {
                val wj = warpJson(ctx)
                if (wj != null) {
                    val wm = Mut("warp", wj, false, null, "واپ رایگان کلادفلر", "free Cloudflare WARP")
                    val w3 = raceWave(ctx, listOf(wm), deadline)
                    if (w3 != null) return win(w3.first, w3.second)
                    append(ctx, section,
                        "موتور: «واپ رایگان کلادفلر» جواب نداد.",
                        "Engine: \"free Cloudflare WARP\" did not respond.")
                } else {
                    append(ctx, section,
                        "موتور: ساخت حساب واپ ممکن نشد.",
                        "Engine: WARP account could not be created.")
                }
            } else {
                append(ctx, section,
                    "موتور: موج واپ رد شد (مهلت بررسی تمام شد).",
                    "Engine: WARP wave skipped (check deadline passed).")
            }
            // WAVE 4 (1.82): combinations of single wins —
            // the engine's last effort before giving up.
            // Combos are never memorized (see win()).
            if (System.currentTimeMillis() <= deadline) {
                val deep = mutableListOf<Mut>()
                val fpMuts = muts.filter { it.id.startsWith("fp:") }
                val sniMut = muts.firstOrNull { it.id == "sni:addr" }
                val portMuts = muts.filter { it.id.startsWith("port:") }
                val ipMuts = muts.filter { it.id.startsWith("ip:") }
                val jumpMut = muts.firstOrNull { it.id == "jump" }
                // Fragment + shape (both apply: fragment
                // first, then the shape — see probeOnce).
                deep += Mut("combo:frag+shape", cfg, true, "DESYNC_CLASSIC", "فرگمنت + شکست بسته کلاسیک", "fragment + desync classic")
                for (f in fpMuts.take(2)) {
                    deep += Mut("combo:frag+${f.id}", f.link, true, null, "فرگمنت + ${f.labelFa}", "fragment + ${f.labelEn}")
                }
                if (sniMut != null) {
                    deep += Mut("combo:frag+sni", sniMut.link, true, null, "فرگمنت + ${sniMut.labelFa}", "fragment + ${sniMut.labelEn}")
                }
                if (jumpMut != null) {
                    deep += Mut("combo:frag+jump", jumpMut.link, true, null, "فرگمنت + ${jumpMut.labelFa}", "fragment + ${jumpMut.labelEn}")
                }
                val firstPort = portMuts.firstOrNull()
                if (firstPort != null) {
                    val pVal = firstPort.id.removePrefix("port:").toIntOrNull()
                    if (pVal != null) {
                        for (f in fpMuts.take(1)) {
                            val v = withPort(f.link, pVal)
                            if (v != null) deep += Mut("combo:${f.id}+port:$pVal", v, false, null, "${f.labelFa} + پورت $pVal", "${f.labelEn} + port $pVal")
                        }
                        if (sniMut != null) {
                            val v = withPort(sniMut.link, pVal)
                            if (v != null) deep += Mut("combo:sni+port:$pVal", v, false, null, "${sniMut.labelFa} + پورت $pVal", "${sniMut.labelEn} + port $pVal")
                        }
                        val firstIp = ipMuts.firstOrNull()
                        if (firstIp != null) {
                            val v = withPort(firstIp.link, pVal)
                            if (v != null) deep += Mut("combo:ip+port:$pVal", v, false, null, "${firstIp.labelFa} + پورت $pVal", "${firstIp.labelEn} + port $pVal")
                        }
                    }
                }
                if (deep.isNotEmpty()) {
                    val w4out = raceWaveTop(ctx, deep, deadline, 3, 250)
                    val w4 = w4out.winners.firstOrNull()
                    if (w4 != null) return win(w4.first, w4.second, standbyLinks(w4out, w4.first))
                    waveReport(4, deep.joinToString("، ") { it.labelFa })
                }
            } else {
                append(ctx, section,
                    "موتور: موج 4 رد شد (مهلت بررسی تمام شد).",
                    "Engine: wave 4 skipped (check deadline passed).")
            }
            // WAVE 5 (2.5): chain as the last resort —
            // the target as the exit, dialed through
            // the app's other healthy configs (any
            // section, any protocol). Fixes blocks
            // on the first hop only, never a dead
            // target; strictly after every direct
            // wave failed. Session-only, like the
            // wave-1 jump. Smart passes an empty
            // pool, so this wave skips itself there.
            if (System.currentTimeMillis() <= deadline) {
                val jumpLink = muts.firstOrNull { it.id == "jump" }?.link
                val seenChain = mutableSetOf<String>()
                val chainMuts = mutableListOf<Mut>()
                for (e in workingPool) {
                    if (XraySpeedtest.dedupKey(e) == dedup) continue
                    val cl = "CHAIN::موتور هوشمند|||" + cfg + "|||" + e
                    if (cl == jumpLink) continue
                    if (!seenChain.add(cl)) continue
                    chainMuts += Mut("chain:" + chainMuts.size, cl, false, null,
                        if (emergency) "زنجیره از کانفیگ سالم" else "زنجیره از «${XraySpeedtest.displayName(e)}»",
                        if (emergency) "chain via a healthy config" else "chain via \"${XraySpeedtest.displayName(e)}\"")
                }
                if (chainMuts.isNotEmpty()) {
                    val w5out = raceWaveTop(ctx, chainMuts, deadline, 1, 250)
                    val w5 = w5out.winners.firstOrNull()
                    if (w5 != null) return win(w5.first, w5.second)
                    waveReport(5, chainMuts.joinToString("، ") { it.labelFa })
                } else {
                    append(ctx, section,
                        "موتور: موج زنجیره رد شد (کانفیگ دیگری با پینگ سالم پیدا نشد؛ یک بار تست پینگ بگیرید تا این موج فعال شود).",
                        "Engine: chain wave skipped (no other config with a healthy ping found; run a ping test once to enable this wave).")
                }
            } else {
                append(ctx, section,
                    "موتور: موج زنجیره رد شد (مهلت بررسی تمام شد).",
                    "Engine: chain wave skipped (check deadline passed).")
            }
            append(ctx, section,
                "موتور: هیچ تغییری «$name» را زنده نکرد؛ اتصال عادی انجام می‌شود.",
                "Engine: no change revived \"$name\"; normal connect follows.")
            return null
        } catch (t: Throwable) {
            try {
                append(ctx, section,
                    "موتور: خطا در بررسی (${t.javaClass.simpleName})؛ اتصال عادی انجام می‌شود.",
                    "Engine: check error (${t.javaClass.simpleName}); normal connect follows.")
            } catch (_: Exception) {}
            return null
        }
    }

    // ---------- global learning ----------

    private fun gmemKey(ctx: Context): String = "gmem_" + netKey(ctx)

    fun gmemGet(ctx: Context): String? {
        return try { store(ctx).getString(gmemKey(ctx), null) } catch (_: Exception) { null }
    }

    fun gmemPut(ctx: Context, mutationId: String) {
        try { store(ctx).edit().putString(gmemKey(ctx), mutationId).apply() } catch (_: Exception) {}
    }

    // ---------- 2.5 upgrade: verdict, warm routes, breaker ----------

    /** Network verdict: is Cloudflare reachable
     *  from here, and is there internet at all?
     *  Measured with two short parallel HTTPS
     *  probes (3 s each), cached 30 minutes per
     *  network kind. Returns null when unknown
     *  (a VPN is already up, or a probe error):
     *  unknown never gates anything. */
    private data class Verdict(val cfOk: Boolean, val netOk: Boolean)

    private fun verdictGet(ctx: Context): Verdict? {
        try {
            if (VpnClientService.isRunning) return null
            val key = "verdict_" + netKey(ctx)
            val raw = store(ctx).getString(key, null)
            if (raw != null) {
                val parts = raw.split(",")
                if (parts.size == 3) {
                    val ts = parts[2].toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - ts < 30 * 60 * 1000L) {
                        return Verdict(parts[0] == "1", parts[1] == "1")
                    }
                }
            }
            val cf = BooleanArray(1)
            val net = BooleanArray(1)
            val t1 = Thread { cf[0] = httpsOk("https://one.one.one.one/cdn-cgi/trace") }.apply { isDaemon = true }
            val t2 = Thread { net[0] = httpsOk("https://www.google.com/generate_204") }.apply { isDaemon = true }
            t1.start(); t2.start()
            t1.join(4000); t2.join(4000)
            val v = Verdict(cf[0], net[0])
            try { store(ctx).edit().putString(key, (if (v.cfOk) "1" else "0") + "," + (if (v.netOk) "1" else "0") + "," + System.currentTimeMillis()).apply() } catch (_: Exception) {}
            return v
        } catch (_: Exception) { return null }
    }

    private fun httpsOk(url: String): Boolean {
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.instanceFollowRedirects = true
            val code = c.responseCode
            try { c.disconnect() } catch (_: Exception) {}
            code in 200..299
        } catch (_: Exception) { false }
    }

    /** Warm route: the full winning variant of
     *  this config on this network, kept 24 h.
     *  A repeat connect probes it alone first —
     *  one probe instead of the whole ladder.
     *  Never stored for emergency, warp or
     *  combos. The probe gates staleness, so a
     *  dead warm route just falls through. */
    private fun warmKey(ctx: Context, dedupKey: String): String = "warm_" + netKey(ctx) + "_" + dedupKey

    private fun warmGet(ctx: Context, dedupKey: String): Triple<String, Boolean, String?>? {
        return try {
            val raw = store(ctx).getString(warmKey(ctx, dedupKey), null) ?: return null
            val j = org.json.JSONObject(raw)
            val ts = j.optLong("t", 0L)
            if (ts == 0L || System.currentTimeMillis() - ts > 24 * 60 * 60 * 1000L) return null
            val link = j.optString("l", "")
            if (link.isBlank()) return null
            Triple(link, j.optBoolean("f", false),
                j.optString("s", "").takeIf { it.isNotBlank() })
        } catch (_: Exception) { null }
    }

    private fun warmPut(ctx: Context, dedupKey: String, link: String, fragment: Boolean, shape: String?) {
        try {
            val j = org.json.JSONObject()
            j.put("l", link)
            j.put("f", fragment)
            j.put("s", shape ?: "")
            j.put("t", System.currentTimeMillis())
            store(ctx).edit().putString(warmKey(ctx, dedupKey), j.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Circuit breaker for dial IPs: an IP that
     *  definitively failed twice on this network
     *  is benched for 1 hour, so later races
     *  spend their slots on fresh edges instead
     *  of known-dead ones. Wins clear the bench.
     *  Never used for emergency. */
    private fun brkKey(ctx: Context, ip: String): String = "brk_" + netKey(ctx) + "_" + ip

    private fun brkActive(ctx: Context, ip: String): Boolean {
        return try {
            val raw = store(ctx).getString(brkKey(ctx, ip), null) ?: return false
            val parts = raw.split(",")
            if (parts.size != 2) return false
            val count = parts[0].toIntOrNull() ?: return false
            val ts = parts[1].toLongOrNull() ?: return false
            if (System.currentTimeMillis() - ts > 60 * 60 * 1000L) {
                try { store(ctx).edit().remove(brkKey(ctx, ip)).apply() } catch (_: Exception) {}
                return false
            }
            count >= 2
        } catch (_: Exception) { false }
    }

    private fun brkBump(ctx: Context, ip: String) {
        try {
            val raw = store(ctx).getString(brkKey(ctx, ip), null)
            var count = 0
            if (raw != null) {
                val parts = raw.split(",")
                count = parts.getOrNull(0)?.toIntOrNull() ?: 0
            }
            store(ctx).edit().putString(brkKey(ctx, ip), (count + 1).toString() + "," + System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    private fun brkClear(ctx: Context, ip: String) {
        try { store(ctx).edit().remove(brkKey(ctx, ip)).apply() } catch (_: Exception) {}
    }

    /** The dial IP a candidate actually uses, or
     *  null: ip:* candidates carry it in the id,
     *  CDN candidates in the link's address.
     *  Hostnames are never benched — only
     *  literal IPs, which is what dies. */
    private fun dialIpOf(m: Mut): String? {
        return try {
            if (m.id.startsWith("ip:")) {
                // strip the DoH marker first, then keep only literal IPs.
                val raw = m.id.removePrefix("ip:").removePrefix("doh:")
                return raw.takeIf { it.matches(Regex("^[0-9.]+$")) }
            }
            XraySpeedtest.parseServer(m.link)?.host?.takeIf { it.matches(Regex("^[0-9.]+$")) }
        } catch (_: Exception) { null }
    }

    // ---------- WireGuard engine-session tracking ----------

    /** The tunnel name connected through an engine
     *  variant (not a normal connect). Toggle-off
     *  disconnects it; a normal connect clears it. */
    fun setWgEngineActive(ctx: Context, name: String?) {
        try {
            val e = store(ctx).edit()
            if (name == null) e.remove("engine_wg_active") else e.putString("engine_wg_active", name)
            e.apply()
        } catch (_: Exception) {}
    }

    fun wgEngineActive(ctx: Context): String? {
        return try { store(ctx).getString("engine_wg_active", null) } catch (_: Exception) { null }
    }

    // ---------- revert on OFF ----------

    /** Everything the engine learned or changed,
     *  undone when the user switches the engine off:
     *  per-config and global memory, all reports, the
     *  generated WARP account, and any LIVE engine
     *  session (a v2ray engine session is stopped, a
     *  WireGuard engine variant is disconnected).
     *  Saved configs are never touched — the engine
     *  never rewrote one — so there is nothing else
     *  to revert. A later normal connect is exactly
     *  the old app again. */
    fun disableCleanup(ctx: Context) {
        // 2.22: an engine-started chain is engine-only
        // machinery (invisible proxy core); stopping
        // it on engine-off leaves no engine state
        // running. Direct OVPN sessions are normal
        // sessions manageable from the OVPN page, so
        // they are left alone as before.
        try {
            if (OpenVpnActivity.engineChainName != null) {
                ChainEngine.stopProxyCore()
                try { OvpnEngine.disconnect() } catch (_: Exception) {}
                ChainEngine.setRunning(ctx, null)
                OpenVpnActivity.engineChainName = null
                OpenVpnActivity.releaseChainProxyOwner()
            }
        } catch (_: Exception) {}
        val wasWg = wgEngineActive(ctx)
        try {
            val sp = store(ctx)
            val e = sp.edit()
            for (k in sp.all.keys) {
                if (k.startsWith("mem_") || k.startsWith("gmem_") || k.startsWith("report_") ||
                    k == "warp_account" || k == "engine_wg_active") e.remove(k)
            }
            e.apply()
        } catch (_: Exception) {}
        try {
            if (VpnClientService.engineSession) {
                val stop = Intent(ctx, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
                try { ctx.startService(stop) } catch (_: Exception) {}
                VpnClientService.isRunning = false
                VpnClientService.sessionTag = null
                VpnClientService.engineSession = false
                VpnClientService.engineOrigLink = null
            }
        } catch (_: Exception) {}
        try {
            if (wasWg != null) {
                try { WgDirect.disconnectAll(ctx) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------- self-healing watchdog support ----------

    /** Is the tunnel actually passing traffic?
     *  2.53 PRECISION: the probe goes through the
     *  RUNNING session's real SOCKS port (from its
     *  own config JSON), never a guessed one, and
     *  uses the verifier's dual-host retried probe
     *  (generate_204 + ipify, tried again once), so
     *  one blocked probe host cannot fake a death.
     *  With no usable port the probe is UNPROVABLE
     *  — and an unprovable probe must never condemn
     *  a session, so it counts as alive. Only used
     *  for engine sessions, always off the UI thread. */
    fun probeThroughTunnel(ctx: Context): Boolean {
        val port = VpnClientService.sessionSocksPort.takeIf { it > 0 }
            ?: VpnConfigBuilder.socksPort(ctx)
        if (port <= 0) return true
        return try {
            ConnVerify.socksProbe(ctx, port)
        } catch (_: Exception) { true }
    }

    sealed class HealResult {
        object Recovered : HealResult()
        data class Relaunch(val json: String) : HealResult()
        object Failed : HealResult()
    }

    /** One healing attempt for a dead engine
     *  session: if the ORIGINAL config answers again
     *  (transient failure), the session is fine and
     *  nothing is relaunched; otherwise the parallel
     *  selector runs (no jump pool — a healing
     *  session has no known-healthy peer) and the
     *  winning variant's full JSON is returned for
     *  relaunch. Failed when nothing answers. */
    fun healSession(ctx: Context, origLink: String): HealResult {
        return try {
            if (!isEnabled(ctx)) return HealResult.Failed
            if (probeWithTimeout(ctx, origLink, false, 8000) >= 0) return HealResult.Recovered
            val plan = selectV2ray(ctx, origLink, S_V2RAY, 20000, emptyList()) ?: return HealResult.Failed
            var json = VpnConfigBuilder.build(ctx, plan.link) ?: return HealResult.Failed
            json = applyPlanToBuiltJson(ctx, json, plan, true) ?: return HealResult.Failed
            HealResult.Relaunch(json)
        } catch (_: Exception) { HealResult.Failed }
    }

    // ---------- free Cloudflare WARP rung ----------

    /** A WARP account is free, needs no user account
     *  and no payment, and its configuration can be
     *  generated in-app with the same API calls the
     *  wgcf tool performs: register, enable warp,
     *  fetch the config. The private key never
     *  leaves the device; the account is cached in
     *  the engine's own prefs and purged on
     *  toggle-off. The peer public key is
     *  Cloudflare's well-known constant. Any failure
     *  just disables this rung — it never blocks the
     *  other waves. */
    private fun warpAccount(ctx: Context): JSONObject? {
        return try {
            val raw = store(ctx).getString("warp_account", null) ?: return null
            JSONObject(raw)
        } catch (_: Exception) { null }
    }

    private fun warpHttp(method: String, url: String, token: String?, body: String?): String? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null }
    }

    private fun warpRegister(ctx: Context): JSONObject? {
        return try {
            val kp = org.amnezia.awg.crypto.KeyPair()
            val priv = kp.privateKey.toBase64()
            val pub = kp.publicKey.toBase64()
            val installId = java.util.UUID.randomUUID().toString()
            val tos = try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
                    .format(java.util.Date())
            } catch (_: Exception) { "2026-01-01T00:00:00.000+00:00" }
            val base = "https://api.cloudflareclient.com/v0a5641/reg"
            val bodies = listOf(
                JSONObject().apply {
                    put("key", pub); put("install_id", installId)
                    put("fcm_token", "$installId:APA91b" + installId.replace("-", "").take(24))
                    put("tos", tos); put("model", "PC"); put("locale", "en_US")
                }.toString(),
                JSONObject().apply {
                    put("key", pub); put("install_id", installId); put("tos", tos)
                }.toString()
            )
            var id: String? = null
            var token: String? = null
            for (b in bodies) {
                val r = warpHttp("POST", base, null, b) ?: continue
                val j = try { JSONObject(r) } catch (_: Exception) { continue }
                id = j.optString("id", "")
                token = j.optString("token", "")
                if (id.isNotBlank() && token.isNotBlank()) break
                id = null; token = null
            }
            if (id.isNullOrBlank() || token.isNullOrBlank()) return null
            warpHttp("PATCH", "$base/$id", token, "{\"warp_enabled\":true}")
            val fetched = warpHttp("GET", "$base/$id", token, null) ?: return null
            val fj = JSONObject(fetched)
            val cfg = fj.optJSONObject("config") ?: return null
            val iface = cfg.optJSONObject("interface") ?: return null
            val addrs = iface.optJSONObject("addresses") ?: return null
            val v4 = addrs.optString("v4", "")
            val v6 = addrs.optString("v6", "")
            val peers = cfg.optJSONArray("peers")
            val peer = if (peers != null && peers.length() > 0) peers.getJSONObject(0) else null
            val peerPub = peer?.optString("public_key", "") ?: ""
            var endpoint = peer?.optString("endpoint", "") ?: ""
            if (v4.isBlank() || peerPub.isBlank()) return null
            if (endpoint.isBlank()) endpoint = "engage.cloudflareclient.com:2408"
            if (!endpoint.contains(":")) endpoint += ":2408"
            val clientId = cfg.optString("client_id", "").ifBlank { fj.optString("client_id", "") }
            val acc = JSONObject().apply {
                put("priv", priv); put("v4", v4); put("v6", v6)
                put("peer", peerPub); put("endpoint", endpoint)
                put("client_id", clientId)
            }
            store(ctx).edit().putString("warp_account", acc.toString()).apply()
            acc
        } catch (_: Exception) { null }
    }

    /** Full Xray config (custom JSON) for the WARP
     *  account: a wireguard outbound, so it probes
     *  and connects through the existing custom
     *  paths. Null when no account could be made. */
    fun warpJson(ctx: Context): String? {
        return try {
            val acc = warpAccount(ctx) ?: warpRegister(ctx) ?: return null
            val priv = acc.optString("priv", "")
            val v4 = acc.optString("v4", "")
            val v6 = acc.optString("v6", "")
            val peer = acc.optString("peer", "")
            val endpoint = acc.optString("endpoint", "")
            if (priv.isBlank() || v4.isBlank() || peer.isBlank() || endpoint.isBlank()) return null
            val addrArr = JSONArray().put(v4)
            if (v6.isNotBlank()) addrArr.put(v6)
            JSONObject().apply {
                put("remarks", "WARP (موتور هوشمند)")
                put("outbounds", JSONArray().put(JSONObject().apply {
                    put("protocol", "wireguard")
                    put("tag", "proxy")
                    put("settings", JSONObject().apply {
                        put("secretKey", priv)
                        put("address", addrArr)
                        put("mtu", 1280)
                        put("peers", JSONArray().put(JSONObject().apply {
                            put("publicKey", peer)
                            put("endpoint", endpoint)
                            put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                        }))
                    })
                }))
                put("routing", JSONObject().apply { put("rules", JSONArray()) })
            }.toString()
        } catch (_: Exception) { null }
    }

    /** WARP account's client_id as 3 reserved bytes (or null). Re-registers once when an
     *  older cached account predates client_id capture, so the standalone WireGuard
     *  config gains its Reserved line. */
    fun warpClientId(ctx: Context): List<Int>? {
        fun decode(cid: String): List<Int>? {
            return try {
                android.util.Base64.decode(cid, android.util.Base64.DEFAULT).take(3)
                    .map { it.toInt() and 0xFF }.takeIf { it.size == 3 }
            } catch (_: Exception) { null }
        }
        val acc = warpAccount(ctx)
        val cid = acc?.optString("client_id", "") ?: ""
        if (cid.isNotBlank()) return decode(cid)
        val fresh = warpRegister(ctx) ?: return null
        return decode(fresh.optString("client_id", ""))
    }

    // ---------- WireGuard / Amnezia variants ----------

    data class WgVariant(val conf: String, val labelFa: String, val labelEn: String)

    /** Endpoint variants of one .conf text: alternate
     *  IPs of the same endpoint host (same port),
     *  then one alternate port. The ORIGINAL conf is
     *  NOT in the list — the caller tries these
     *  first and the original last (fallback). */
    fun wgVariants(confText: String): List<WgVariant> {
        val out = mutableListOf<WgVariant>()
        try {
            val m = Regex("(?m)^\\s*Endpoint\\s*=\\s*(\\S+)\\s*$").find(confText) ?: return out
            val endpoint = m.groupValues[1]
            if (endpoint.count { it == ':' } != 1) return out
            val host = endpoint.substringBefore(":")
            val port = endpoint.substringAfter(":").toIntOrNull() ?: return out
            val isDomain = !host.matches(Regex("^[0-9.]+$")) && !host.contains(":")
            if (isDomain) {
                for (ip in resolveIps(host)) {
                    val v = confText.replaceFirst(m.value, m.value.replace(endpoint, ip + ":" + port))
                    if (v != confText) out += WgVariant(v, "آدرس IP جایگزین $ip", "alternate IP $ip")
                }
            }
            if (isDomain) {
                // 2.37: DoH IPs — a second opinion
                // when system DNS is poisoned.
                val seenDohWg = mutableSetOf<String>()
                for (wv in out) {
                    if (wv.labelEn.startsWith("alternate IP ")) seenDohWg += wv.labelEn.removePrefix("alternate IP ")
                }
                for (ip in resolveIpsDoh(host, 4)) {
                    if (!seenDohWg.add(ip)) continue
                    val v = confText.replaceFirst(m.value, m.value.replace(endpoint, ip + ":" + port))
                    if (v != confText) out += WgVariant(v, "آدرس IP جایگزین $ip (DoH)", "alternate IP $ip (DoH)")
                }
            }
            // 2.37: MTU 1280 variant — survives
            // MTU blackholes that drop big packets.
            run {
                val mtuRegex = Regex("(?m)^\\s*MTU\\s*=.*$")
                val mtuV = if (mtuRegex.containsMatchIn(confText)) {
                    confText.replace(mtuRegex, "MTU = 1280")
                } else {
                    val idx = confText.indexOf("[Interface]")
                    if (idx >= 0) {
                        val nl = confText.indexOf('\n', idx)
                        if (nl >= 0) confText.substring(0, nl + 1) + "MTU = 1280\n" + confText.substring(nl + 1) else confText
                    } else confText
                }
                if (mtuV != confText) out += WgVariant(mtuV, "MTU 1280", "MTU 1280")
            }
            for (p in listOf(443, 8443)) {
                if (p == port) continue
                val v = confText.replaceFirst(m.value, m.value.replace(endpoint, host + ":" + p))
                if (v != confText) out += WgVariant(v, "پورت $p", "port $p")
                break
            }
        } catch (_: Exception) {}
        return out.take(10)
    }

    // ---------- OpenVPN variants ----------

    /** Expanded remotes: for every `remote host port
     *  [proto]` line with a domain host, the resolved
     *  IPs are inserted as extra remote lines BEFORE
     *  the original, so OpenVPN tries the engine's
     *  variants first and the original remains as
     *  fallback inside the same attempt. */
    fun ovpnExpandedRemotes(ovpnText: String): String {
        return try {
            val out = mutableListOf<String>()
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val ips = resolveIps(parts[1])
                        if (ips.isNotEmpty()) {
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            for (ip in ips) out += "remote $ip$rest"
                        }
                    }
                }
                out += line
            }
            out.joinToString("\n")
        } catch (_: Exception) { ovpnText }
    }

    /** UDP <-> TCP swap of the `proto` line, or null
     *  when there is no proto line to swap. */
    /** 2.37: force an OpenVPN profile onto TCP for
     *  chaining. The chain rides a loopback HTTP
     *  proxy, which carries TCP only — a UDP
     *  profile chained as-is never hides inside
     *  hop1. Global `proto` lines and per-remote
     *  protos are rewritten; UDP-only
     *  `explicit-exit-notify` lines are dropped
     *  (they error on TCP). Null when the profile
     *  is already TCP-only. */
    fun ovpnForceTcp(ovpnText: String): String? {
        return try {
            var changed = false
            val out = ArrayList<String>()
            var hasProto = false
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t == "explicit-exit-notify" || t.startsWith("explicit-exit-notify ")) {
                    changed = true
                    continue
                }
                if (t == "proto" || t.startsWith("proto ")) {
                    hasProto = true
                    val nv = when {
                        t == "proto udp" || t.startsWith("proto udp ") -> line.replace("proto udp", "proto tcp-client")
                        t == "proto udp4" || t.startsWith("proto udp4 ") -> line.replace("proto udp4", "proto tcp4-client")
                        t == "proto udp6" || t.startsWith("proto udp6 ") -> line.replace("proto udp6", "proto tcp6-client")
                        t == "proto tcp-server" || t.startsWith("proto tcp-server ") -> line.replace("proto tcp-server", "proto tcp-client")
                        else -> line
                    }
                    if (nv != line) changed = true
                    out += nv
                    continue
                }
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val nv = when (parts[3]) {
                            "udp" -> "tcp-client"
                            "udp4" -> "tcp4-client"
                            "udp6" -> "tcp6-client"
                            "tcp-server" -> "tcp-client"
                            else -> null
                        }
                        if (nv != null) {
                            changed = true
                            out += (parts.subList(0, 3) + nv + parts.subList(4, parts.size)).joinToString(" ")
                            continue
                        }
                    }
                    out += line
                    continue
                }
                out += line
            }
            if (!hasProto) {
                changed = true
                out += "proto tcp-client"
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    fun ovpnProtoSwapped(ovpnText: String): String? {
        return try {
            var changed = false
            val out = ovpnText.lines().map { line ->
                val t = line.trim()
                when {
                    t == "proto udp" || t.startsWith("proto udp ") -> {
                        changed = true
                        line.replace("proto udp", "proto tcp-client")
                    }
                    t == "proto tcp-client" || t.startsWith("proto tcp-client ") -> {
                        changed = true
                        line.replace("proto tcp-client", "proto udp")
                    }
                    t == "proto tcp" || t.startsWith("proto tcp ") -> {
                        changed = true
                        line.replace("proto tcp", "proto udp")
                    }
                    else -> line
                }
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** Replaced remotes: for every `remote host ...`
     *  line with a domain host, the line is REPLACED
     *  by one `remote ip ...` line per resolved IP
     *  (no hostname line left), so the core never
     *  falls back to a poisoned hostname resolution.
     *  Null when nothing changes. */
    fun ovpnReplacedRemotes(ovpnText: String): String? {
        return try {
            var changed = false
            val out = mutableListOf<String>()
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val ips = resolveIps(parts[1])
                        if (ips.isNotEmpty()) {
                            changed = true
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            for (ip in ips) out += "remote $ip$rest"
                            continue
                        }
                    }
                }
                out += line
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** Same replacement as ovpnReplacedRemotes, but
     *  the IPs come from DNS-over-HTTPS (dns.google)
     *  instead of the system resolver, so a poisoned
     *  system DNS cannot feed bad addresses. Null
     *  when DoH yields nothing or nothing changes.
     *  Background threads only. */
    fun ovpnReplacedRemotesDoh(ovpnText: String): String? {
        return try {
            var changed = false
            val out = mutableListOf<String>()
            for (line in ovpnText.lines()) {
                val t = line.trim()
                if (t.startsWith("remote ") || t == "remote") {
                    val parts = t.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val ips = resolveIpsDoh(parts[1])
                        if (ips.isNotEmpty()) {
                            changed = true
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            for (ip in ips) out += "remote $ip$rest"
                            continue
                        }
                    }
                }
                out += line
            }
            if (changed) out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    /** A-record lookup over DNS-over-HTTPS
     *  (dns.google). IPv4 only, max 8, empty for IP
     *  literals and on any failure. Background
     *  threads only. */
    fun resolveIpsDoh(host: String, max: Int = 8): List<String> {
        return try {
            if (host.isBlank() || host.contains(":") || host.matches(Regex("^[0-9.]+$"))) return emptyList()
            val enc = java.net.URLEncoder.encode(host, "UTF-8")
            val conn = java.net.URL("https://dns.google/resolve?name=$enc&type=A")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val ans = org.json.JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until ans.length()) {
                val o = ans.optJSONObject(i) ?: continue
                if (o.optInt("type", -1) != 1) continue
                val ip = o.optString("data", "")
                if (ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) out += ip
                if (out.size >= max) break
            }
            out.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** Appends `mssfix 1200` when the config has no
     *  mssfix line (helps on lines where large
     *  packets get black-holed). Null when already
     *  present. */
    fun ovpnWithMssfix(ovpnText: String): String? {
        return try {
            for (line in ovpnText.lines()) {
                if (line.trim().startsWith("mssfix")) return null
            }
            ovpnText + "\nmssfix 1200"
        } catch (_: Exception) { null }
    }

    /** Human-readable IP changes between two .ovpn
     *  texts, from their `remote` lines: one entry
     *  per original hostname remote, like
     *  `host 443 -> 1.2.3.4, 5.6.7.8` (max 3 IPs
     *  shown). Empty when no hostname remote gained
     *  IP lines. */
    fun ovpnIpChanges(oldText: String, newText: String): String {
        return try {
            fun remotes(t: String): List<Pair<String, String>> {
                val out = ArrayList<Pair<String, String>>()
                for (line in t.lines()) {
                    val s = line.trim()
                    if (s.startsWith("remote ") || s == "remote") {
                        val parts = s.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val rest = if (parts.size > 2) " " + parts.subList(2, parts.size).joinToString(" ") else ""
                            out += parts[1] to rest
                        }
                    }
                }
                return out
            }
            val oldR = remotes(oldText)
            val newR = remotes(newText)
            val isIp = { h: String -> h.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) }
            val out = ArrayList<String>()
            for ((h, rest) in oldR) {
                if (isIp(h)) continue
                val ips = newR.filter { it.second == rest && isIp(it.first) }.map { it.first }.distinct()
                if (ips.isNotEmpty()) {
                    val shown = ips.take(3).joinToString(", ")
                    out += "$h$rest -> $shown" + if (ips.size > 3) "..." else ""
                }
            }
            out.joinToString("; ")
        } catch (_: Exception) { "" }
    }

    // ---------- Hydra endpoint expansion ----------

    /** Engine-first for Hydra, without touching its
     *  strategy ladder: alternate-IP endpoints are
     *  placed AHEAD of each original v2ray endpoint,
     *  so Hydra's own probe tries the variants
     *  first and the originals stay as fallback.
     *  Emergency endpoints are never mutated. When
     *  the engine is off, the list comes back
     *  untouched. */
    fun expandHydraEndpoints(ctx: Context, endpoints: List<HydraEngine.Endpoint>): List<HydraEngine.Endpoint> {
        if (!isEnabled(ctx)) return endpoints
        return try {
            val out = mutableListOf<HydraEngine.Endpoint>()
            var expanded = 0
            for (ep in endpoints) {
                if (!ep.fromEmergency && expanded < 20) {
                    val srv = try { XraySpeedtest.parseServer(ep.link) } catch (_: Exception) { null }
                    val host = srv?.host ?: ""
                    if (host.isNotBlank()) {
                        var added = 0
                        for (ip in resolveIps(host, 2)) {
                            val v = if (ep.link.startsWith("vmess://")) {
                                vmessMutate(ep.link) { j -> j.put("add", ip) }
                            } else {
                                setAuthority(ep.link, ip, null)
                            }
                            if (v != null && v != ep.link) {
                                out += HydraEngine.Endpoint(v, false)
                                added++
                            }
                        }
                        if (added > 0) expanded++
                    }
                }
                out += ep
            }
            if (expanded > 0) append(ctx, S_HYDRA,
                "موتور: برای $expanded نقطه‌ی اتصال، آدرس‌های IP جایگزین جلوتر از نسخه‌ی اصلی چیده شد.",
                "Engine: alternate IPs were placed ahead of the original for $expanded endpoints.")
            out
        } catch (_: Exception) { endpoints }
    }
}
