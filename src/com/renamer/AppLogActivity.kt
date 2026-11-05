package com.renamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Monitoring + App Log, one page (2.56).
 *  The Home log button now opens this page: the
 *  top half is App Monitoring — a compact,
 *  glanceable state board for every section,
 *  computed only from state the app already
 *  keeps (see Monitoring.kt) — and below a thin
 *  grey divider sits the app log itself.
 *
 *  The log half keeps its name, its description
 *  and the background real-connection check with
 *  its descriptions, but nothing else from the
 *  old advanced view: no search box, no stats
 *  line, no ordering button, no section chips
 *  and no "all" option. The log box shows at
 *  most 5 lines and scrolls inside itself, so
 *  long logs never push the page around; Copy
 *  and Clear sit BELOW the box. */
class AppLogActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var logBox: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var monitorGrid: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var summaryDot: android.view.View
    private lateinit var winningText: TextView
    private lateinit var progressText: TextView
    private lateinit var copyBtn: Button

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            try { refreshMonitoring() } catch (_: Exception) {}
            handler.postDelayed(this, 2000)
        }
    }

    private fun glassCard(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor("#CCFFFFFF"))
        setStroke(dp(1), Color.parseColor("#FFE0E0E0"))
    }

    private fun glassPill(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(Color.parseColor("#FFFFFF"))
        setStroke(dp(1), Color.parseColor("#FFDDDDDD"))
    }

    /** Small glass "?" button beside a section
     *  title: a brief explanation dialog, nothing
     *  more — the titles stay clean. */
    private fun helpButton(messageFa: String, messageEn: String): Button {
        return Button(this).apply {
            text = "?"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CCFFFFFF"))
                setStroke(dp(1), Color.parseColor("#FFB2DFDB"))
            }
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener {
                android.app.AlertDialog.Builder(this@AppLogActivity)
                    .setMessage(L(messageFa, messageEn))
                    .setPositiveButton(L("باشه", "OK"), null)
                    .show()
            }
        }
    }

    /** A title row: bold title on one side, the
     *  "?" explainer on the other. */
    private fun titleRow(titleFa: String, titleEn: String, helpFa: String, helpEn: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@AppLogActivity).apply {
                text = L(titleFa, titleEn)
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FF1A237E"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(helpButton(helpFa, helpEn))
        }
    }

    private fun dotView(color: Int): android.view.View {
        return android.view.View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
    }

    private fun monitorCard(card: Monitoring.Card): LinearLayout {
        val isEn = AppLanguage.isEnglish(this)
        // Uniform size: every card is exactly the
        // same height, whatever its text wraps to,
        // so the board reads as one clean grid.
        // The border tints with the state (green =
        // live, amber = last event failed, grey =
        // idle, teal = a switch that is on).
        // 2.59 beauty: a soft vertical wash of
        // the state colour under white, a
        // stronger state border, a rounder
        // corner and a softer shadow — same
        // grid, same size, prettier surface.
        val border = when (card.dot) {
            Monitoring.DOT_GREEN -> Color.parseColor("#FF81C784")
            Monitoring.DOT_AMBER -> Color.parseColor("#FFFFD54F")
            Monitoring.DOT_TEAL -> Color.parseColor("#FF4DB6AC")
            else -> Color.parseColor("#FFBDBDBD")
        }
        val wash = when (card.dot) {
            Monitoring.DOT_GREEN -> Color.parseColor("#FFE8F5E9")
            Monitoring.DOT_AMBER -> Color.parseColor("#FFFFF8E1")
            Monitoring.DOT_TEAL -> Color.parseColor("#FFE0F2F1")
            else -> Color.parseColor("#FFFAFAFA")
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.parseColor("#FFFFFFFF"), wash)
                setStroke(dp(1), border)
            }
            elevation = 3f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            addView(LinearLayout(this@AppLogActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dotView(card.dot))
                addView(TextView(this@AppLogActivity).apply {
                    text = if (isEn) card.titleEn else card.titleFa
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#FF1A237E"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(TextView(this@AppLogActivity).apply {
                text = if (isEn) card.lineEn else card.lineFa
                textSize = 12f
                setTextColor(Color.parseColor("#FF37474F"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            val inv = if (isEn) card.inventoryEn else card.inventoryFa
            if (inv.isNotBlank()) {
                addView(TextView(this@AppLogActivity).apply {
                    text = inv
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#FF00695C"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }
            if (card.time.isNotBlank()) {
                addView(TextView(this@AppLogActivity).apply {
                    text = L("آخرین رویداد ${card.time}", "Last event ${card.time}")
                    textSize = 11f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            // Tap a card to open its section — the
            // board is a launcher, not just a display.
            // The engine has no page of its own, so
            // its card is display-only.
            val target = when (card.key) {
                "v2ray" -> VpnActivity::class.java
                "wireguard" -> WireGuardActivity::class.java
                "openvpn" -> OpenVpnActivity::class.java
                "chain" -> ChainActivity::class.java
                "emergency" -> EmergencyActivity::class.java
                "smart" -> SmartConnectActivity::class.java
                "hydra" -> HydraActivity::class.java
                "cloudflare" -> CloudflareActivity::class.java
                "subs" -> SubscriptionActivity::class.java
                else -> null
            }
            if (target != null) {
                isClickable = true
                setOnClickListener {
                    startActivity(android.content.Intent(this@AppLogActivity, target))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(HydraUi.PAGE_BG))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        // ---- Monitoring (top half) ----
        root.addView(titleRow(
            "مانیتورینگ برنامه", "App monitoring",
            "مانیتورینگ، وضعیت لحظه‌ای همه بخش‌ها را با یک نگاه نشان می‌دهد: کدام بخش در حال اتصال است، کدام خاموش است، هر بخش چند کانفیگ دارد و چندتایش در آخرین تست سالم بوده. این صفحه چیزی اندازه نمی‌گیرد جز تست پینگ v2ray و تست تونل‌های وایرگارد که هیچ‌کدام اتصال زنده را قطع نمی‌کنند؛ OpenVPN فقط با وصل شدن تست می‌شود، پس برای اینکه اتصال فعلی‌ات قطع نشود، فقط شمارش و آخرین وضعیت را نشان می‌دهد. روی هر کارت بزن تا همان بخش باز شود.",
            "Monitoring shows every section's live state at a glance: which section is connected, which is off, how many configs each section has and how many were healthy at the last test. It measures nothing except the v2ray ping test and the WireGuard tunnel test, neither of which cuts a live connection; OpenVPN can only be tested by connecting, so to keep your live connection up it shows counts and last state only. Tap a card to open its section."))
        root.addView(TextView(this).apply {
            text = L("وضعیت زنده بخش‌ها؛ تست‌های v2ray و وایرگارد هم دوره‌ای به‌صورت خودکار تازه می‌شوند.",
                "Live section states; v2ray and WireGuard tests are also refreshed automatically from time to time.")
            textSize = 13f
            setTextColor(Color.parseColor("#FF455A64"))
            setPadding(0, dp(8), 0, dp(12))
        })
        // Verdict line: one dot + one sentence for
        // the whole board (green = connected and no
        // recent failure, amber = some section's
        // last event was a failure, grey = idle).
        // 2.59 beauty: the verdict rides in a
        // soft teal glass pill, not bare text.
        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFE0F2F1"))
                setStroke(dp(1), Color.parseColor("#FFB2DFDB"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        summaryDot = dotView(Monitoring.DOT_GREY)
        summaryRow.addView(summaryDot)
        summaryText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
        }
        summaryRow.addView(summaryText)
        root.addView(summaryRow)
        winningText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF455A64"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(winningText)
        // Test-all row: a manual sweep button +
        // live progress while a sweep runs. The
        // sweep is the subs page's PingTestService
        // (foreground service, never touches a
        // live VPN session); it only re-measures
        // the v2ray section's own saved configs.
        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        testRow.addView(Button(this).apply {
            text = L("تست همه بخش‌ها", "Test all sections")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(
                    Color.parseColor("#FF0F766E"), Color.parseColor("#FF26A69A"))
            }
            elevation = 3f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { startSweep(force = true) }
        })
        // 2.58: copy a full monitoring report
        // (verdict + every card + winning
        // methods) as plain text, for sending
        // to the developer or keeping.
        testRow.addView(Button(this).apply {
            text = L("کپی گزارش", "Copy report")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#FF0F766E"))
                setColor(Color.WHITE)
            }
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            setOnClickListener { copyMonitoringReport() }
        })
        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF00695C"))
            setPadding(dp(10), 0, 0, 0)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testRow.addView(progressText)
        root.addView(testRow)
        monitorGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(monitorGrid)

        // ---- Thin grey divider between the halves ----
        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(12), 0, dp(12)) }
            setBackgroundColor(Color.parseColor("#FFDDDDDD"))
        })

        // ---- App log (bottom half) ----
        root.addView(titleRow(
            "لاگ برنامه", "App log",
            "لاگ، همه تلاش‌های اتصال و رویدادهای بخش‌ها را دقیق و به‌ترتیب زمان ثبت می‌کند. برای ارسال لاگ دقیق به سازنده: اول دکمه «پاک کردن لاگ» پایین صفحه را بزن، بعد روی کانفیگی که وصل نمی‌شود بزن، و همان لاگ تازه را کپی کن و بفرست.",
            "The log records every connection attempt and section event, precise and in time order. To send an accurate log to the developer: first tap “Clear log” at the bottom of this page, then tap the config that fails to connect, and copy and send that fresh log."))
        root.addView(TextView(this).apply {
            text = L(
                "همه‌ی تلاش‌های اتصال و رویدادهای بخش‌های برنامه، دقیق و به‌ترتیب زمان، اینجا ثبت می‌شود: هر تلاش با موتور روشن بوده یا خاموش، نوع کانفیگ چه بوده، و نتیجه چه شد. برای ارسال لاگ دقیق به سازنده: اول دکمه «پاک کردن لاگ» پایین صفحه را بزن، بعد روی کانفیگی که وصل نمی‌شود بزن، و همان لاگ تازه را کپی کن و بفرست تا عیب‌یابی دقیق باشد.",
                "Every connection attempt and section event, precise and in time order, is recorded here: whether each attempt ran with the engine on or off, what kind of config it was, and what the result was. To send an accurate log to the developer: first tap “Clear log” at the bottom of this page, then tap the config that fails to connect, and copy and send that fresh log so debugging is precise.")
            textSize = 13f
            setTextColor(Color.parseColor("#FF455A64"))
            setPadding(0, dp(8), 0, dp(12))
        })

        // Background real-connection check, unchanged.
        val verifyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val verifyTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        verifyTexts.addView(TextView(this).apply {
            text = L("بررسی واقعی اتصال در پس‌زمینه", "Background real-connection check")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1A237E"))
        })
        verifyTexts.addView(TextView(this).apply {
            text = L("وقتی روشن است، بعد از هر اتصال در پس‌زمینه تست می‌شود که تونل واقعاً ترافیک می‌برد یا نه، و نتیجه در همین لاگ ثبت می‌شود.",
                "When on, every new connection is probed in the background for real traffic, and the verdict is written into this log.")
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        verifyRow.addView(verifyTexts)
        verifyRow.addView(android.widget.Switch(this).apply {
            isChecked = ConnVerify.isEnabled(this@AppLogActivity)
            setOnCheckedChangeListener { _, on ->
                ConnVerify.setEnabled(this@AppLogActivity, on)
                Toast.makeText(this@AppLogActivity,
                    if (on) L("بررسی واقعی اتصال روشن شد", "Real-connection check turned on")
                    else L("بررسی واقعی اتصال خاموش شد", "Real-connection check turned off"),
                    Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(verifyRow)

        // Log box: a fixed 5-line window that scrolls
        // inside itself. A plain TextView with
        // maxLines did NOT scroll here: the outer
        // page ScrollView stole every vertical drag,
        // so lines below the window (e.g. the newest
        // openvpn/wireguard lines) were unreachable
        // and the box looked like it only had v2ray.
        // A real nested ScrollView + disallow-intercept
        // on touch fixes that, and the box opens
        // scrolled to the bottom (newest lines first
        // seen) while the stored order stays oldest
        // first, exactly like the old full log.
        logBox = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FF1A1A1A"))
            // 2.58: text selection stays OFF on
            // purpose. A selectable TextView
            // consumes drag gestures for text
            // selection and starves this inner
            // ScrollView, which is exactly why
            // 2.57's box still would not scroll.
            // Copying is done by the Copy button
            // below, which copies the full log.
            setTextIsSelectable(false)
            isLongClickable = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val logHeight = logBox.lineHeight * 5 + dp(20)
        logScroll = ScrollView(this).apply {
            background = HydraUi.glassBg(
                Color.parseColor("#FFFFFFFF"), Color.parseColor("#FFE0E0E0"), 16f, this@AppLogActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, logHeight)
            isVerticalScrollBarEnabled = true
            // Keep the outer page ScrollView from
            // stealing this box's own scrolls.
            // 2.58: re-assert on MOVE too, so no
            // child gesture can hand the drag
            // back to the outer ScrollView.
            setOnTouchListener { v, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_DOWN ||
                    ev.action == android.view.MotionEvent.ACTION_MOVE) {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
        logScroll.addView(logBox)
        root.addView(logScroll)

        // Copy + Clear BELOW the box.
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        // 2.59 beauty: Copy is the primary
        // action (teal gradient pill), Clear the
        // secondary (white outline pill) — the
        // same pair language as Test/Report
        // above, so the whole page reads as one
        // design.
        copyBtn = Button(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(
                    Color.parseColor("#FF0F766E"), Color.parseColor("#FF26A69A"))
            }
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                val lines = allLines()
                if (lines.isEmpty()) {
                    Toast.makeText(this@AppLogActivity,
                        L("چیزی برای کپی نیست", "Nothing to copy"), Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("app-log", lines.joinToString("\n")))
                    Toast.makeText(this@AppLogActivity,
                        L("لاگ کپی شد", "Log copied"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        btnRow.addView(Button(this).apply {
            text = L("پاک کردن لاگ", "Clear log")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#FF0F766E"))
                setColor(Color.WHITE)
            }
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                AppLog.clear(this@AppLogActivity)
                refresh()
                Toast.makeText(this@AppLogActivity,
                    L("لاگ پاک شد", "Log cleared"), Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btnRow)

        refresh()
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        maybeAutoSweep()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    /** Start a sweep via the subs page's
     *  PingTestService (v2ray) and WgTestService
     *  (WireGuard): both measure their section's
     *  own saved configs in the background and
     *  never touch a live VPN session.
     *  Auto-sweeps are throttled to one per 10
     *  minutes; the button forces one (unless a
     *  sweep is already running). */
    private fun startSweep(force: Boolean) {
        try {
            val subs = getSharedPreferences("renamer_subs", MODE_PRIVATE)
            val wgTest = getSharedPreferences("renamer_wg_test", MODE_PRIVATE)
            if (subs.getBoolean("ping_running", false) || wgTest.getBoolean("wg_test_running", false)) {
                // 2.59: either test running blocks a
                // new sweep; auto-sweeps stay silent.
                if (force) Toast.makeText(this,
                    L("تستی در حال اجراست؛ صبر کن.", "A test is already running."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            val servers = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
                ?: emptyList()
            val seen = mutableSetOf<String>()
            val toTest = servers.filter { seen.add(XraySpeedtest.dedupKey(it)) }
            // 2.59: WG tunnels join the sweep (own
            // service, own core, same no-touch rule).
            val wgNames = try {
                val arr = org.json.JSONArray(getSharedPreferences("renamer_wg", MODE_PRIVATE)
                    .getString("wg_list", "[]") ?: "[]")
                (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("name", "")?.takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) { emptyList() }
            if (toTest.isEmpty() && wgNames.isEmpty()) {
                if (force) Toast.makeText(this,
                    L("چیزی برای تست نیست.", "Nothing to test."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (!CoreInit.init(applicationContext)) {
                if (force) Toast.makeText(this,
                    L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            var startedAny = false
            if (toTest.isNotEmpty()) {
                subs.edit()
                    .putString("ping_test_list", toTest.joinToString("\n"))
                    .putString("ping_healthy_live", "")
                    .putInt("ping_progress_done", 0)
                    .putInt("ping_progress_total", toTest.size)
                    .putInt("ping_progress_healthy", 0)
                    .putBoolean("ping_done", false)
                    .putBoolean("ping_applied", false)
                    .putBoolean("ping_running", true)
                    .putString("ping_error", "")
                    .putString("ping_live_sub", "{}")
                    .apply()
                try {
                    val intent = android.content.Intent(this, PingTestService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    startedAny = true
                } catch (_: Exception) {
                    subs.edit().putBoolean("ping_running", false).apply()
                    if (force) Toast.makeText(this,
                        L("تست پینگ شروع نشد.", "Ping test did not start."),
                        Toast.LENGTH_SHORT).show()
                }
            }
            // 2.59: WG tunnels, same sweep button.
            if (wgNames.isNotEmpty()) {
                wgTest.edit()
                    .putBoolean("wg_test_running", true)
                    .putBoolean("wg_test_done", false)
                    .putInt("wg_test_progress_done", 0)
                    .putInt("wg_test_total", wgNames.size)
                    .putInt("wg_test_healthy", 0)
                    .putInt("wg_test_untestable", 0)
                    .putString("wg_test_error", "")
                    .apply()
                try {
                    val intent = android.content.Intent(this, WgTestService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    startedAny = true
                } catch (_: Exception) {
                    wgTest.edit().putBoolean("wg_test_running", false).apply()
                    if (force) Toast.makeText(this,
                        L("تست وایرگارد شروع نشد.", "WireGuard test did not start."),
                        Toast.LENGTH_SHORT).show()
                }
            }
            if (startedAny) {
                getSharedPreferences("renamer_settings", MODE_PRIVATE).edit()
                    .putLong("monitoring_sweep_at", System.currentTimeMillis()).apply()
                if (force) Toast.makeText(this,
                    when {
                        toTest.isNotEmpty() && wgNames.isNotEmpty() ->
                            L("تست پینگ ${toTest.size} کانفیگ و ${wgNames.size} تونل وایرگارد شروع شد.",
                                "Ping test started for ${toTest.size} configs and ${wgNames.size} WireGuard tunnels.")
                        toTest.isNotEmpty() ->
                            L("تست پینگ ${toTest.size} کانفیگ شروع شد.", "Ping test started for ${toTest.size} configs.")
                        else ->
                            L("تست ${wgNames.size} تونل وایرگارد شروع شد.", "WireGuard test started for ${wgNames.size} tunnels.")
                    },
                    Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            if (force) Toast.makeText(this,
                L("تست شروع نشد.", "Test did not start."),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAutoSweep() {
        try {
            val last = getSharedPreferences("renamer_settings", MODE_PRIVATE)
                .getLong("monitoring_sweep_at", 0L)
            if (System.currentTimeMillis() - last < 10 * 60 * 1000L) return
            startSweep(force = false)
        } catch (_: Exception) {}
    }

    /** Raw stored lines, oldest first, as AppLog keeps them. */
    private fun allLines(): List<String> {
        val t = AppLog.text(this)
        if (t.isBlank()) return emptyList()
        return t.lines().filter { it.isNotBlank() }
    }

    private fun refresh() {
        refreshMonitoring()
        refreshLog()
    }

    /** Monitoring half only — cheap enough for
     *  the 2s poller, and it never touches the
     *  log box, so it cannot yank the user's
     *  scroll position while they read the log. */
    private fun refreshMonitoring() {
        if (!::monitorGrid.isInitialized) return
        // Monitoring cards, two per row.
        val cards = Monitoring.collect(this)
        monitorGrid.removeAllViews()
        for (rowStart in cards.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(monitorCard(cards[rowStart]))
            if (rowStart + 1 < cards.size) {
                row.addView(monitorCard(cards[rowStart + 1]))
            } else {
                row.addView(LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                })
            }
            monitorGrid.addView(row)
        }
        val live = Monitoring.liveCount(cards)
        val amber = cards.count { it.dot == Monitoring.DOT_AMBER }
        val latest = Monitoring.latestTime(cards)
        val latestSuffix = if (latest.isNotBlank()) L(" • آخرین رویداد $latest", " • latest event $latest") else ""
        when {
            amber > 0 -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_AMBER)
                }
                summaryText.text = L("$amber بخش آخرین رویدادش خطا بود" +
                    (if (live > 0) " • $live بخش متصل است" else "") + latestSuffix,
                    "$amber sections' last event was a failure" +
                    (if (live > 0) " • $live connected" else "") + latestSuffix)
            }
            live > 0 -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_GREEN)
                }
                summaryText.text = L("وضعیت خوب است — $live بخش متصل است$latestSuffix",
                    "All good — $live sections connected$latestSuffix")
            }
            else -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_GREY)
                }
                summaryText.text = L("هیچ اتصالی فعال نیست$latestSuffix",
                    "No connection active$latestSuffix")
            }
        }
        val wins = Monitoring.winningMethods(this)
        if (wins.isEmpty()) {
            winningText.visibility = android.view.View.GONE
        } else {
            winningText.visibility = android.view.View.VISIBLE
            winningText.text = L("آخرین روش‌های برنده: " + wins.joinToString("، "),
                "Recent winning methods: " + wins.joinToString(", "))
        }
        // Live sweep progress, if one is running.
        // 2.59: ping and WG progress combine into
        // one block (each on its own line).
        try {
            val subs = getSharedPreferences("renamer_subs", MODE_PRIVATE)
            val parts = mutableListOf<String>()
            if (subs.getBoolean("ping_running", false)) {
                val d = subs.getInt("ping_progress_done", 0)
                val t = subs.getInt("ping_progress_total", 0)
                val h = subs.getInt("ping_progress_healthy", 0)
                parts += L("در حال تست پینگ… $d از $t تست شد، $h سالم",
                    "Testing ping… $d of $t tested, $h healthy")
            }
            val wg = getSharedPreferences("renamer_wg_test", MODE_PRIVATE)
            if (wg.getBoolean("wg_test_running", false)) {
                val d = wg.getInt("wg_test_progress_done", 0)
                val t = wg.getInt("wg_test_total", 0)
                val h = wg.getInt("wg_test_healthy", 0)
                parts += L("در حال تست وایرگارد… $d از $t تست شد، $h سالم",
                    "Testing WireGuard… $d of $t tested, $h healthy")
            }
            if (parts.isNotEmpty()) {
                progressText.visibility = android.view.View.VISIBLE
                progressText.text = parts.joinToString("\n")
            } else {
                progressText.visibility = android.view.View.GONE
            }
        } catch (_: Exception) {}
    }

    /** 2.58: one plain-text monitoring report:
     *  verdict + every card (state, line,
     *  inventory, last-event time) + winning
     *  methods. Computed only from existing
     *  state, like the cards themselves. */
    private fun copyMonitoringReport() {
        try {
            val cards = Monitoring.collect(this)
            val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
            val sb = StringBuilder()
            sb.append(L("گزارش مانیتورینگ برنامه — $stamp",
                "App monitoring report — $stamp")).append("\n")
            if (::summaryText.isInitialized) sb.append(summaryText.text).append("\n")
            for (c in cards) {
                val title = L(c.titleFa, c.titleEn)
                val line = L(c.lineFa, c.lineEn)
                val inv = L(c.inventoryFa, c.inventoryEn)
                val state = when (c.dot) {
                    Monitoring.DOT_GREEN -> L("متصل", "connected")
                    Monitoring.DOT_AMBER -> L("خطا", "failure")
                    Monitoring.DOT_TEAL -> L("فعال", "on")
                    else -> L("خاموش", "off")
                }
                sb.append("• $title: $state — $line")
                if (inv.isNotBlank()) sb.append(" ($inv)")
                if (c.time.isNotBlank()) sb.append(" [${c.time}]")
                sb.append("\n")
            }
            if (::winningText.isInitialized && winningText.text.isNotBlank()) {
                sb.append(winningText.text).append("\n")
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("monitoring-report", sb.toString()))
            android.widget.Toast.makeText(this,
                L("گزارش مانیتورینگ کپی شد.", "Monitoring report copied."),
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun refreshLog() {
        if (!::logBox.isInitialized) return
        // Log: everything, in stored order (oldest
        // first, like the old full log). The box
        // opens at the bottom so the newest lines
        // are seen at once; scroll up for older.
        val all = allLines()
        copyBtn.text = L("کپی لاگ", "Copy log")
        logBox.text = if (all.isEmpty()) L(
            "هنوز چیزی ثبت نشده است. وقتی در بخش‌های برنامه اتصالی انجام شود، اینجا می‌نویسد چه شد.",
            "Nothing recorded yet. When a connection happens in any section, it will be written here.")
        else all.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
