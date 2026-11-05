package com.renamer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** Studio section widgets (2.33). Horizontal row: big section icon
 *  on a wheel-coloured circle, then flag + name, dot + status,
 *  download/upload speeds, then the connect button (left in RTL).
 *  The connect button toggles WITHOUT opening the app
 *  (StudioToggleActivity for WG/OVPN/Emergency, WidgetToggleActivity
 *  for legacy v2ray widgets); name/status/flag taps open the
 *  section for management. */
open class SectionWidgetProvider : AppWidgetProvider() {

    companion object {
        fun targetActivityFor(key: String): Class<*> = when (key) {
            "v2ray" -> VpnActivity::class.java
            "wireguard" -> WireGuardActivity::class.java
            "openvpn" -> OpenVpnActivity::class.java
            "emergency" -> EmergencyActivity::class.java
            "smart" -> SmartConnectActivity::class.java
            "chain" -> ChainActivity::class.java
            "hydra" -> HydraActivity::class.java
            "cloudflare" -> CloudflareActivity::class.java
            "secretlang" -> SecretLanguageActivity::class.java
            "message" -> AlienHubActivity::class.java
            "messenger" -> AlienMessengerActivity::class.java
            "renamer" -> RenameActivity::class.java
            "subs" -> SubscriptionActivity::class.java
            else -> HomeActivity::class.java
        }

        fun displayNameFor(ctx: Context, key: String): String = when (key) {
            "v2ray" -> AppLanguage.t(ctx, "وی‌پی‌ان", "VPN")
            "wireguard" -> AppLanguage.t(ctx, "وایرگارد و امنزیا", "WireGuard & Amnezia")
            "openvpn" -> AppLanguage.t(ctx, "OpenVPN", "OpenVPN")
            "emergency" -> AppLanguage.t(ctx, "اتصال اضطراری", "Emergency")
            "smart" -> AppLanguage.t(ctx, "اتصال هوشمند", "Smart Connect")
            "chain" -> AppLanguage.t(ctx, "زنجیره دوتایی", "Double Chain")
            "hydra" -> AppLanguage.t(ctx, "هایدرا", "Hydra")
            "cloudflare" -> AppLanguage.t(ctx, "کلادفلر", "Cloudflare")
            "secretlang" -> AppLanguage.t(ctx, "زبان بیگانه", "Alien Language")
            "message" -> AppLanguage.t(ctx, "پیام بیگانه", "Alien Message")
            "messenger" -> AppLanguage.t(ctx, "مسنجر بیگانه", "Alien Messenger")
            "renamer" -> AppLanguage.t(ctx, "تغییر نام", "Rename")
            "subs" -> AppLanguage.t(ctx, "اشتراک‌ها", "Subscriptions")
            else -> AppLanguage.t(ctx, "بخش", "Section")
        }

        fun iconAssetFor(key: String): String? = when (key) {
            "v2ray" -> "vpn.png"
            "wireguard" -> "wg.png"
            "openvpn" -> "ovpn.png"
            "emergency" -> "emergency.png"
            "smart" -> "smart.png"
            "chain" -> "chain.png"
            "hydra" -> "hydra.png"
            "cloudflare" -> "cf.png"
            "secretlang", "message", "messenger" -> "alien.png"
            "renamer" -> "v2ray.png"
            "subs" -> "sub.png"
            else -> null
        }

        fun initialFor(key: String): String = when (key) {
            "v2ray" -> "و"
            "wireguard" -> "و"
            "openvpn" -> "ا"
            "emergency" -> "ا"
            "smart" -> "ه"
            "chain" -> "ز"
            "hydra" -> "ه"
            "cloudflare" -> "ک"
            "secretlang" -> "ب"
            "message" -> "پ"
            "messenger" -> "م"
            "renamer" -> "ت"
            "subs" -> "ا"
            else -> "•"
        }

        fun isToggleable(key: String) =
            key in setOf("v2ray", "wireguard", "openvpn", "emergency")

        private fun prefWidgetState(ctx: Context, key: String): Boolean = try {
            ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                .getBoolean("widget_state_$key", false)
        } catch (_: Exception) { false }

        // re-render on system schedule, so a live-vs-pref
        // disagreement is logged once, not every render.
        private val loggedLiveDisagree = mutableSetOf<String>()

        private fun logLiveDisagree(ctx: Context, key: String) {
            try {
                if (loggedLiveDisagree.add(key)) {
                                    }
            } catch (_: Exception) {}
        }

        fun isConnected(ctx: Context, key: String): Boolean = when (key) {
            "v2ray" -> VpnClientService.isRunning &&
                VpnClientService.sessionTag in arrayOf("v2ray", "smart", null)
            // provider: backend truth for WG, engine flag
            // for OVPN; the pref is only a fallback while
            // the engine has never run in this process.
            "wireguard" -> try {
                if (WgDirect.isLive(ctx)) true
                else {
                    if (prefWidgetState(ctx, key)) logLiveDisagree(ctx, key)
                    false
                }
            } catch (_: Exception) { prefWidgetState(ctx, key) }
            "openvpn" -> try {
                if (OvpnEngine.isConnected) true
                else if (OvpnEngine.isConnecting || OvpnEngine.activeName != null || OvpnEngine.lastState != null) {
                    if (prefWidgetState(ctx, key)) logLiveDisagree(ctx, key)
                    false
                } else prefWidgetState(ctx, key)
            } catch (_: Exception) { prefWidgetState(ctx, key) }
            "emergency", "hydra", "chain", "cloudflare", "smart" ->
                VpnClientService.isRunning && VpnClientService.sessionTag == key
            else -> false
        }

        /** Widget state (MLM's three looks): off (grey),
         *  connecting (amber, dashed ring), on (green lamp;
         *  the button itself is the accent). The connecting
         *  flag is set by the toggle activities when a
         *  connect starts and cleared by them on finish; a
         *  stale flag (>30s) is dropped on read, so a failed
         *  connect never leaves the widget amber forever. */
        const val STATE_OFF = 0
        const val STATE_CONNECTING = 1
        const val STATE_ON = 2
        private const val CONNECTING_TTL = 30_000L

        fun stateColor(state: Int): Int = when (state) {
            STATE_CONNECTING -> WidgetCard.AMBER
            STATE_ON -> WidgetCard.GREEN
            else -> WidgetCard.OFF_GREY
        }

        fun setConnecting(ctx: Context, section: String) {
            try {
                ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                    .edit().putLong("widget_connecting_$section",
                        System.currentTimeMillis()).apply()
            } catch (_: Exception) {}
        }

        fun clearConnecting(ctx: Context, section: String) {
            try {
                ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                    .edit().remove("widget_connecting_$section").apply()
            } catch (_: Exception) {}
        }

        fun isConnecting(ctx: Context, section: String): Boolean {
            return try {
                val prefs = ctx.getSharedPreferences("renamer_widget", Context.MODE_PRIVATE)
                val ts = prefs.getLong("widget_connecting_$section", 0L)
                if (ts <= 0L) return false
                if (System.currentTimeMillis() - ts > CONNECTING_TTL) {
                    clearConnecting(ctx, section)
                    return false
                }
                true
            } catch (_: Exception) { false }
        }

        fun widgetState(ctx: Context, section: String): Int = when {
            try { isConnected(ctx, section) } catch (_: Exception) { false } -> STATE_ON
            isConnecting(ctx, section) -> STATE_CONNECTING
            else -> STATE_OFF
        }

        /** MLM-style tap hints (replaces "connected/disconnected"). */
        fun statusHint(ctx: Context, section: String, state: Int): String = when {
            !isToggleable(section) -> AppLanguage.t(ctx, "باز کن", "Open")
            state == STATE_ON -> AppLanguage.t(ctx, "برای قطع بزنید", "Tap to disconnect")
            state == STATE_CONNECTING -> AppLanguage.t(ctx, "در حال اتصال…", "Connecting…")
            else -> AppLanguage.t(ctx, "برای اتصال بزنید", "Tap to connect")
        }

        /** Text inside the stretched ("wide") button. */
        fun buttonLabel(ctx: Context, state: Int): String = when (state) {
            STATE_ON -> AppLanguage.t(ctx, "متصل", "Connected")
            STATE_CONNECTING -> AppLanguage.t(ctx, "در حال اتصال…", "Connecting…")
            else -> AppLanguage.t(ctx, "اتصال", "Connect")
        }

        /** Speeds with coloured arrows (MLM: down in the
         *  accent, up hue-shifted from it). Spans survive
         *  RemoteViews parceling; if a launcher strips them
         *  the text itself is still correct. */
        fun speedSpanned(down: Long, up: Long, accent: Int): CharSequence {
            val d = formatSpeed(down)
            val u = formatSpeed(up)
            val text = "↓ $d   ↑ $u"
            val sp = SpannableString(text)
            try {
                sp.setSpan(ForegroundColorSpan(accent),
                    0, 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                val upIdx = 2 + d.length + 3
                sp.setSpan(ForegroundColorSpan(WidgetCard.hueShift(accent, 55f)),
                    upIdx, upIdx + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: Exception) {}
            return sp
        }

        /** Downscaled section icon (aspect kept, max 192px) so the
         *  RemoteViews parcel stays far under the binder limit. */
        fun sectionIconBitmap(ctx: Context, key: String): Bitmap? {
            val asset = iconAssetFor(key) ?: return null
            return try {
                val raw = BitmapFactory.decodeStream(ctx.assets.open(asset)) ?: return null
                val max = 192
                if (raw.width <= max && raw.height <= max) return raw
                val s = minOf(max.toFloat() / raw.width, max.toFloat() / raw.height)
                Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true)
            } catch (_: Exception) { null }
        }

        /** Connect-button bitmap. The 4-arg form is the
         *  disconnected look (wheel colour). The 5-arg form is
         *  state-aware (2.36): off = wheel colour, connecting =
         *  neon orange with a DASHED ring (MLM's connecting
         *  look), on = neon green. Shapes: circle, rounded,
         *  pill, diamond, hexagon, ring, glowring (ring + neon
         *  halo), wide (stretched pill with a state label). */
        fun buttonBitmap(ctx: Context, shape: String, color: Int, px: Int): Bitmap =
            buttonBitmap(ctx, shape, color, px, STATE_OFF)

        fun buttonBitmap(ctx: Context, shape: String, color: Int, px: Int, state: Int,
            lightCard: Boolean = false): Bitmap {
            val accent = color
            val shapeKey = when (shape) {
                "circle" -> "solid"
                "wide" -> "pill"
                "glowring" -> "glow"
                else -> shape
            }
            if (shapeKey == "pill") {
                val w = (px * 2).coerceAtLeast(96)
                val h = px.coerceAtLeast(48)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val rect = RectF(h * 0.04f, h * 0.04f, w - h * 0.04f, h - h * 0.04f)
                val radius = h * 0.48f
                val label = buttonLabel(ctx, state)
                if (state == STATE_ON) {
                    // Quiet pill in the accent with a lamp: the
                    // action now is "disconnect".
                    val fill = Paint().apply {
                        isAntiAlias = true
                        this.color = WidgetCard.alpha(accent, 0.22f)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(rect, radius, radius, fill)
                    val stroke = Paint().apply {
                        isAntiAlias = true
                        this.color = WidgetCard.alpha(accent, 0.85f)
                        style = Paint.Style.STROKE
                        strokeWidth = h * 0.03f
                    }
                    canvas.drawRoundRect(rect, radius, radius, stroke)
                    val onFill = if (lightCard) WidgetCard.darken(accent, 0.35f)
                        else WidgetCard.lighten(accent, 0.55f)
                    val tp = Paint().apply {
                        isAntiAlias = true
                        this.color = onFill
                        textSize = h * 0.30f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        textAlign = Paint.Align.CENTER
                    }
                    val tw = tp.measureText(label)
                    val gap = h * 0.10f
                    val dotR = h * 0.12f
                    val total = tw + gap + dotR * 2
                    val left = w / 2f - total / 2f
                    val rtl = !AppLanguage.isEnglish(ctx)
                    // Lamp at the start of the word: right in Persian.
                    val dotCx = if (rtl) left + total - dotR else left + dotR
                    val textCx = if (rtl) left + tw / 2f
                        else left + dotR * 2 + gap + tw / 2f
                    canvas.drawCircle(dotCx, h / 2f, dotR, Paint().apply {
                        isAntiAlias = true
                        this.color = WidgetCard.GREEN
                    })
                    val y = h / 2f - (tp.descent() + tp.ascent()) / 2f
                    canvas.drawText(label, textCx, y, tp)
                    return bmp
                }
                val fillColor = if (state == STATE_CONNECTING) WidgetCard.AMBER else accent
                val fill = Paint().apply {
                    isAntiAlias = true
                    this.color = fillColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rect, radius, radius, fill)
                val tp = Paint().apply {
                    isAntiAlias = true
                    this.color = if (WidgetCard.luminance(fillColor) > 0.6)
                        Color.parseColor("#111114") else Color.WHITE
                    textSize = h * 0.30f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                val y = h / 2f - (tp.descent() + tp.ascent()) / 2f
                canvas.drawText(label, w / 2f, y, tp)
                return bmp
            }
            val size = px.coerceAtLeast(48)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val inset = size * 0.04f
            val rect = RectF(inset, inset, size - inset, size - inset)
            val stateCol: Int? = when (state) {
                STATE_CONNECTING -> WidgetCard.AMBER
                STATE_ON -> accent
                else -> null
            }
            val offFill = if (lightCard) Color.argb(26, 0, 0, 0)
                else Color.argb(36, 255, 255, 255)
            when (shapeKey) {
                "rounded" -> {
                    val fill = Paint().apply {
                        isAntiAlias = true
                        this.color = stateCol ?: offFill
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, fill)
                }
                "diamond" -> {
                    val fill = Paint().apply {
                        isAntiAlias = true
                        this.color = stateCol ?: offFill
                        style = Paint.Style.FILL
                    }
                    val p = Path()
                    val cc = size / 2f
                    p.moveTo(cc, inset)
                    p.lineTo(size - inset, cc)
                    p.lineTo(cc, size - inset)
                    p.lineTo(inset, cc)
                    p.close()
                    canvas.drawPath(p, fill)
                }
                "hexagon" -> {
                    val fill = Paint().apply {
                        isAntiAlias = true
                        this.color = stateCol ?: offFill
                        style = Paint.Style.FILL
                    }
                    val p = Path()
                    val cc = size / 2f
                    val r = size / 2f - inset
                    for (i in 0 until 6) {
                        val a = Math.toRadians((60.0 * i).toDouble())
                        val x = (cc + r * Math.cos(a)).toFloat()
                        val y = (cc + r * Math.sin(a)).toFloat()
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                    canvas.drawPath(p, fill)
                }
                "ring", "glow" -> {
                    // Inner disc, so the ring reads as a button.
                    val disc = Paint().apply {
                        isAntiAlias = true
                        this.color = if (stateCol != null) WidgetCard.alpha(stateCol, 0.14f)
                            else offFill
                    }
                    canvas.drawOval(RectF(size * 0.06f, size * 0.06f,
                        size * 0.94f, size * 0.94f), disc)
                    // Hollow ring: the stroke is centred on the
                    // rect edge, so the rect is inset by 4% + half
                    // the 10% stroke (2.34 drew it at 4% and the
                    // ring clipped 1% on all sides).
                    val ringRect = RectF(size * 0.09f, size * 0.09f,
                        size * 0.91f, size * 0.91f)
                    if (shapeKey == "glow" && stateCol != null) {
                        val halo = Paint().apply {
                            isAntiAlias = true
                            this.color = stateCol
                            alpha = 70
                            style = Paint.Style.STROKE
                            strokeWidth = size * 0.16f
                        }
                        canvas.drawOval(ringRect, halo)
                    }
                    val ring = Paint().apply {
                        isAntiAlias = true
                        this.color = stateCol ?: WidgetCard.OFF_GREY
                        style = Paint.Style.STROKE
                        strokeWidth = size * 0.10f
                        if (state == STATE_CONNECTING) {
                            pathEffect = DashPathEffect(
                                floatArrayOf(size * 0.09f, size * 0.06f), 0f)
                        }
                    }
                    canvas.drawOval(ringRect, ring)
                }
                else -> {
                    // solid (and legacy circle)
                    val fill = Paint().apply {
                        isAntiAlias = true
                        this.color = stateCol ?: offFill
                        style = Paint.Style.FILL
                    }
                    canvas.drawOval(rect, fill)
                }
            }
            // Power glyph: arc with the gap centred at TOP
            // (300-degree sweep from 300 degrees) plus the vertical
            // line. Ring shapes tint the glyph with the state
            // colour (grey when off); filled shapes keep it white
            // (dark on a light accent).
            val glyphColor = when (shapeKey) {
                "ring", "glow" -> stateCol ?: WidgetCard.OFF_GREY
                else -> if (stateCol != null) {
                    if (WidgetCard.luminance(stateCol) > 0.6) Color.parseColor("#111114")
                    else Color.WHITE
                } else if (lightCard) Color.parseColor("#111114") else Color.WHITE
            }
            val glyph = Paint().apply {
                isAntiAlias = true
                this.color = glyphColor
                style = Paint.Style.STROKE
                strokeWidth = size * 0.08f
                strokeCap = Paint.Cap.ROUND
            }
            val g = RectF(size * 0.26f, size * 0.26f, size * 0.74f, size * 0.74f)
            canvas.drawArc(g, 300f, 300f, false, glyph)
            canvas.drawLine(size * 0.5f, size * 0.20f, size * 0.5f, size * 0.50f, glyph)
            return bmp
        }

        private fun lastOvpnProfile(ctx: Context): OvpnProfile? {
            return try {
                val prefs = ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                val last = prefs.getString("ovpn_last", null)?.takeIf { it.isNotBlank() }
                val list = OvpnStore.load(ctx)
                if (last != null) list.firstOrNull { it.name == last } ?: list.firstOrNull()
                else list.firstOrNull()
            } catch (_: Exception) { null }
        }

        /** (display name, config link for geo lookup, host for geo
         *  lookup) for the section's current connection target. */
        fun infoFor(ctx: Context, section: String): Triple<String, String?, String?> {
            return try {
                when (section) {
                    "wireguard" -> {
                        var name: String? = null
                        var conf: String? = null
                        try {
                            val prefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                            val active = prefs.getString("wg_active", null)
                            val arr = org.json.JSONArray(prefs.getString("wg_list", "[]"))
                            var firstName: String? = null
                            var firstConf: String? = null
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val n = o.optString("name")
                                val c = o.optString("conf")
                                if (firstName == null) { firstName = n; firstConf = c }
                                if (!active.isNullOrBlank() && (n == active || c == active)) {
                                    name = n; conf = c; break
                                }
                            }
                            if (name == null) { name = firstName; conf = firstConf }
                        } catch (_: Exception) {}
                        var host: String? = null
                        if (!conf.isNullOrBlank()) {
                            for (line in conf.lines()) {
                                val t = line.trim()
                                if (t.startsWith("Endpoint", ignoreCase = true) && t.contains("=")) {
                                    val ep = t.substringAfter("=").trim()
                                    host = ep.substringBefore(":").trim()
                                        .removePrefix("[").removeSuffix("]")
                                    break
                                }
                            }
                        }
                        Triple(name ?: displayNameFor(ctx, section), conf, host)
                    }
                    "openvpn" -> {
                        val p = lastOvpnProfile(ctx)
                        val running = try { OvpnEngine.isConnected } catch (_: Exception) { false }
                        val name = if (running) {
                            try { OvpnEngine.activeName } catch (_: Exception) { null }
                        } else null
                        Triple(name?.takeIf { it.isNotBlank() } ?: p?.name
                            ?: displayNameFor(ctx, section), null, p?.hostname)
                    }
                    "emergency" -> {
                        val running = try {
                            VpnClientService.isRunning &&
                                VpnClientService.sessionTag == "emergency"
                        } catch (_: Exception) { false }
                        val name = if (running) {
                            try { VpnClientService.currentName } catch (_: Exception) { null }
                        } else null
                        val link = if (running) {
                            try { VpnClientService.currentLink } catch (_: Exception) { null }
                        } else null
                        var host: String? = null
                        if (!link.isNullOrBlank() && link.contains("://")) {
                            try {
                                var h = link.substringAfter("://").substringBefore("/")
                                    .substringBefore("?").substringAfter("@")
                                    .substringBefore(":")
                                if (h.isBlank()) h = link
                                host = h
                            } catch (_: Exception) {}
                        }
                        Triple(name?.takeIf { it.isNotBlank() }
                            ?: AppLanguage.t(ctx, "اتصال اضطراری", "Emergency"),
                            link, host)
                    }
                    "v2ray" -> {
                        val sel = try {
                            ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                                .getString("selected", null)?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) { null }
                        var host: String? = null
                        if (!sel.isNullOrBlank() && sel.contains("://")) {
                            try {
                                var h = sel.substringAfter("://").substringBefore("/")
                                    .substringBefore("?").substringAfter("@")
                                    .substringBefore(":")
                                if (h.isBlank()) h = sel
                                host = h
                            } catch (_: Exception) {}
                        }
                        val name = if (!sel.isNullOrBlank()) {
                            try { XraySpeedtest.displayName(sel) }
                            catch (_: Exception) { displayNameFor(ctx, section) }
                        } else displayNameFor(ctx, section)
                        Triple(name, sel, host)
                    }
                    "hydra" -> {
                        val running = try {
                            VpnClientService.isRunning &&
                                VpnClientService.sessionTag == "hydra"
                        } catch (_: Exception) { false }
                        val name = if (running) {
                            try { VpnClientService.currentName } catch (_: Exception) { null }
                        } else null
                        val link = if (running) {
                            try { VpnClientService.currentLink } catch (_: Exception) { null }
                        } else null
                        var host: String? = null
                        if (!link.isNullOrBlank() && link.contains("://")) {
                            try {
                                var h = link.substringAfter("://").substringBefore("/")
                                    .substringBefore("?").substringAfter("@")
                                    .substringBefore(":")
                                if (h.isBlank()) h = link
                                host = h
                            } catch (_: Exception) {}
                        }
                        Triple(name?.takeIf { it.isNotBlank() }
                            ?: displayNameFor(ctx, section), link, host)
                    }
                    else -> Triple(displayNameFor(ctx, section), null, null)
                }
            } catch (_: Exception) {
                Triple(displayNameFor(ctx, section), null, null)
            }
        }

        private val speedRetryIds = mutableSetOf<Int>()

        /** 15s live-speed tick: an exact alarm chain (explicit
         *  broadcast to this provider, so it survives process
         *  death; the in-process 3s retry above does not). */
        const val ACTION_SPEED_TICK = "com.renamer.WIDGET_SPEED_TICK"
        private const val TICK_REQUEST = 9012

        private fun speedTickPending(ctx: Context, create: Boolean): android.app.PendingIntent? {
            val intent = Intent(ctx, SectionWidgetProvider::class.java).apply {
                action = ACTION_SPEED_TICK
            }
            val flag = if (create) android.app.PendingIntent.FLAG_UPDATE_CURRENT
                else android.app.PendingIntent.FLAG_NO_CREATE
            return android.app.PendingIntent.getBroadcast(ctx, TICK_REQUEST, intent,
                flag or android.app.PendingIntent.FLAG_IMMUTABLE)
        }

        fun scheduleSpeedTick(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val pi = speedTickPending(ctx, true) ?: return
                try {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 15000, pi)
                } catch (_: Exception) {
                    am.set(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 15000, pi)
                }
            } catch (_: Exception) {}
        }

        fun cancelSpeedTick(ctx: Context) {
            try {
                val pi = speedTickPending(ctx, false) ?: return
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.cancel(pi)
                pi.cancel()
            } catch (_: Exception) {}
        }

        fun anySectionConnected(ctx: Context): Boolean {
            return try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val all = ArrayList<Int>()
                for (cls in providerClasses()) {
                    all += mgr.getAppWidgetIds(ComponentName(ctx, cls)).toList()
                }
                all.any { isConnected(ctx, WidgetStyle.sectionForWidget(ctx, it)) }
            } catch (_: Exception) { false }
        }

        /** Size of a placed widget, from its provider class:
         *  SectionWidgetSmall -> small, SectionWidgetLarge ->
         *  large, anything else (incl. the original provider)
         *  -> medium. */
        fun sizeForWidgetId(ctx: Context, id: Int): String {
            return try {
                val info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)
                when (info?.provider?.className) {
                    SectionWidgetSmall::class.java.name -> "small"
                    SectionWidgetLarge::class.java.name -> "large"
                    else -> "medium"
                }
            } catch (_: Exception) { "medium" }
        }

        fun layoutFor(size: String, ctx: Context): Int {
            val en = AppLanguage.isEnglish(ctx)
            return when (size) {
                "small" -> if (en) R.layout.widget_section_small_en
                    else R.layout.widget_section_small_fa
                "large" -> if (en) R.layout.widget_section_large_en
                    else R.layout.widget_section_large_fa
                else -> if (en) R.layout.widget_section_en
                    else R.layout.widget_section_fa
            }
        }

        /** Card dp size and the paint scale for the home
         *  widget. The card holds no text, so it is painted
         *  at a reduced scale (gradients need no pixels) to
         *  keep the RemoteViews parcel under the binder limit. */
        private fun cardDims(size: String): Triple<Int, Int, Float> = when (size) {
            "small" -> Triple(110, 110, 2.0f)
            "large" -> Triple(250, 250, 1.0f)
            else -> Triple(250, 110, 1.5f)
        }

        /** Button centre (dp) for the card's glow. */
        private fun buttonCenterDp(size: String): Pair<Float, Float> = when (size) {
            "small" -> 55f to 62f
            "large" -> 125f to 115f
            else -> 46f to 55f
        }

        private fun providerClasses() = listOf(
            SectionWidgetProvider::class.java,
            SectionWidgetSmall::class.java,
            SectionWidgetLarge::class.java)

        fun updateWidget(ctx: Context, appWidgetId: Int) {
            val mgr = AppWidgetManager.getInstance(ctx)
            var section = WidgetStyle.sectionForWidget(ctx, appWidgetId)
            if (section.isBlank()) {
                // Claim the pending Studio config, if any.
                try {
                    val pending = WidgetStyle.takePending(ctx)
                    if (!pending.isNullOrBlank()) {
                        val o = org.json.JSONObject(pending)
                        if (o.optString("provider") == "section") {
                            section = o.optString("section")
                            WidgetStyle.saveWidget(ctx, appWidgetId, section,
                                o.optString("title"), o.optString("style"),
                                o.optString("size"), o.optString("color"),
                                o.optString("icon"), o.optBoolean("showname", true),
                                o.optInt("iconbg", -1), o.optString("btnshape", "circle"),
                                o.optInt("btncolor", -1),
                                o.optString("template"),
                                o.optBoolean("showspeeds", true),
                                o.optBoolean("showstatus", true),
                                o.optBoolean("showicon", true),
                                o.optInt("bgcolor", -1), o.optInt("opacity", -1),
                                o.optInt("corner", -1), o.optInt("textscale", -1),
                                o.optBoolean("showmethod", true),
                                o.optBoolean("showusage", false),
                                o.optBoolean("showtime", false),
                                o.optBoolean("showchart", false))
                            val cm = o.optString("connmode")
                            if (cm.isNotBlank()) WidgetStyle.setConnMode(ctx, appWidgetId, cm)
                        }
                    }
                } catch (_: Exception) {}
            }
            val size = sizeForWidgetId(ctx, appWidgetId)
            val views = RemoteViews(ctx.packageName, layoutFor(size, ctx))
            val d = ctx.resources.displayMetrics.density
            if (section.isBlank()) {
                // Restored widget with no config: tapping opens Studio.
                views.setTextViewText(R.id.widget_section_name,
                    AppLanguage.t(ctx, "ویجت صفحه اصلی", "Home screen widgets"))
                views.setTextViewText(R.id.widget_section_dot, "●")
                views.setInt(R.id.widget_section_dot, "setTextColor", WidgetCard.OFF_GREY)
                if (size != "large") {
                    views.setTextViewText(R.id.widget_section_status,
                        AppLanguage.t(ctx, "برای تنظیم، بزن", "Tap to set up"))
                    views.setViewVisibility(R.id.widget_section_speeds, View.GONE)
                }
                views.setViewVisibility(R.id.widget_section_icon, View.GONE)
                views.setViewVisibility(R.id.widget_section_icon_bg, View.GONE)
                val (cw, ch, cs) = cardDims(size)
                val (bcx, bcy) = buttonCenterDp(size)
                views.setImageViewBitmap(R.id.widget_section_bg, WidgetCard.paintCard(
                    (cw * cs).toInt(), (ch * cs).toInt(),
                    WidgetStyle.presetBg("glass"), 46, 28f, "glass",
                    WidgetStyle.presetAccent("glass"), STATE_OFF,
                    bcx * cs, bcy * cs, cs, false))
                val open = Intent(ctx, WidgetStudioActivity::class.java)
                views.setOnClickPendingIntent(R.id.widget_section_root,
                    PendingIntent.getActivity(ctx, appWidgetId * 10 + 9, open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                try { mgr.updateAppWidget(appWidgetId, views) } catch (_: Exception) {}
                return
            }
            val style = WidgetStyle.styleForWidget(ctx, appWidgetId)
            val template = WidgetStyle.templateForWidget(ctx, appWidgetId)
            val title = WidgetStyle.titleForWidget(ctx, appWidgetId)
            val connected = try { isConnected(ctx, section) } catch (_: Exception) { false }
            val state = widgetState(ctx, section)
            WidgetCard.noteState(ctx, section, connected)
            val accent = WidgetStyle.accentForWidget(ctx, appWidgetId)
            val showMethod = WidgetStyle.showMethodForWidget(ctx, appWidgetId)
            val showStatus = WidgetStyle.showStatusForWidget(ctx, appWidgetId)
            val showSpeeds = WidgetStyle.showSpeedsForWidget(ctx, appWidgetId)
            val showIcon = WidgetStyle.showIconForWidget(ctx, appWidgetId)
            val showChart = WidgetStyle.showChartForWidget(ctx, appWidgetId)
            val textScale = WidgetStyle.textScaleForWidget(ctx, appWidgetId)
            val sc = textScale / 100f
            // Ink (MLM's Ink): text on a pale card is dark, else white.
            val lightCard = template.isNotBlank() &&
                WidgetCard.isLight(WidgetStyle.bgForWidget(ctx, appWidgetId),
                    WidgetStyle.opacityForWidget(ctx, appWidgetId)) &&
                template != "colorful"
            val textCol = if (template.isNotBlank()) {
                if (lightCard) Color.parseColor("#111114") else Color.WHITE
            } else WidgetStyle.textColor(style)
            val subCol = if (template.isNotBlank()) {
                if (lightCard) Color.parseColor("#993C3C43") else Color.parseColor("#B3FFFFFF")
            } else WidgetStyle.subTextColor(style)
            // Card: painted (template) or legacy drawable.
            if (template.isNotBlank()) {
                // Reapply() reuses views: clear any legacy root background.
                views.setInt(R.id.widget_section_root, "setBackgroundResource", 0)
                val bg = WidgetStyle.bgForWidget(ctx, appWidgetId)
                val opacity = WidgetStyle.opacityForWidget(ctx, appWidgetId)
                val corner = WidgetStyle.cornerForWidget(ctx, appWidgetId)
                val (cw, ch, cs) = cardDims(size)
                val (bcx, bcy) = buttonCenterDp(size)
                views.setImageViewBitmap(R.id.widget_section_bg, WidgetCard.paintCard(
                    (cw * cs).toInt(), (ch * cs).toInt(), bg, opacity,
                    corner.toFloat(), template, accent, state,
                    bcx * cs, bcy * cs, cs, lightCard))
                views.setViewVisibility(R.id.widget_section_bg, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_section_bg, View.GONE)
                views.setInt(R.id.widget_section_root, "setBackgroundResource",
                    WidgetStyle.bgRes(style))
            }
            // 2.35: the FA/EN layout files already encode the order
            // (button first, always left; icon before the name), so
            // the root is forced LTR and no launcher locale can
            // mirror the widget (2.34's setLayoutDirection(1) was
            // ignored by the launcher, which renders LTR).
            views.setInt(R.id.widget_section_root, "setLayoutDirection", 0)
            // Icon: small section icon on the wheel-coloured circle,
            // inline before the name.
            if (!showIcon) {
                views.setViewVisibility(R.id.widget_section_icon, View.GONE)
                views.setViewVisibility(R.id.widget_section_icon_bg, View.GONE)
            } else {
                views.setInt(R.id.widget_section_icon_bg, "setColorFilter",
                    WidgetStyle.effectiveIconBg(ctx, appWidgetId))
                val bmp = sectionIconBitmap(ctx, section)
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.widget_section_icon, bmp)
                    views.setViewVisibility(R.id.widget_section_icon, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_section_icon, View.GONE)
                }
            }
            // Connect button: chosen shape in the MLM state colour
            // (accent when on, amber while connecting, grey when
            // off — except the pill, which is accent even off).
            val btnShape = WidgetStyle.btnShapeForWidget(ctx, appWidgetId)
            val btnPx = when (size) { "small" -> 160; "large" -> 260; else -> 204 }
            views.setImageViewBitmap(R.id.widget_section_btn,
                buttonBitmap(ctx, btnShape, accent, btnPx, state, lightCard))
            if ((btnShape == "wide" || btnShape == "pill") && Build.VERSION.SDK_INT >= 31) {
                val wDp = when (size) { "small" -> 84f; "large" -> 180f; else -> 120f }
                views.setViewLayoutWidth(R.id.widget_section_btn, wDp,
                    TypedValue.COMPLEX_UNIT_DIP)
            }
            // Info: name / dot + status / speeds.
            val info = infoFor(ctx, section)
            val name = if (title.isNotBlank()) title else info.first
            if (!showMethod) {
                views.setViewVisibility(R.id.widget_section_name, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_section_name, View.VISIBLE)
                views.setTextViewText(R.id.widget_section_name, name)
            }
            views.setTextColor(R.id.widget_section_name, textCol)
            views.setTextViewTextSize(R.id.widget_section_name,
                TypedValue.COMPLEX_UNIT_SP, 15f * sc)
            views.setTextViewText(R.id.widget_section_dot, "●")
            views.setInt(R.id.widget_section_dot, "setTextColor", stateColor(state))
            if (size == "large") {
                // Header status (no status row on large).
                if (!showStatus) {
                    views.setViewVisibility(R.id.widget_section_status, View.GONE)
                    views.setViewVisibility(R.id.widget_section_dot, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_section_status, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_section_dot, View.VISIBLE)
                    views.setTextViewText(R.id.widget_section_status,
                        statusHint(ctx, section, state))
                }
            } else {
                if (showStatus) {
                    views.setViewVisibility(R.id.widget_section_status_row, View.VISIBLE)
                    views.setTextViewText(R.id.widget_section_status,
                        statusHint(ctx, section, state))
                } else {
                    views.setViewVisibility(R.id.widget_section_status_row, View.GONE)
                }
            }
            views.setTextColor(R.id.widget_section_status, subCol)
            views.setTextViewTextSize(R.id.widget_section_status,
                TypedValue.COMPLEX_UNIT_SP, 12f * sc)
            var down = -1L
            var up = -1L
            if (connected) {
                val sp = VpnNotification.currentSpeedsKbps(ctx)
                down = sp.first
                up = sp.second
                if (down >= 0) WidgetCard.chartAppend(ctx, section, down, up)
            }
            if (size != "large") {
                if (!showSpeeds) {
                    views.setViewVisibility(R.id.widget_section_speeds, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_section_speeds, View.VISIBLE)
                    views.setTextViewText(R.id.widget_section_speeds,
                        if (down < 0) "↓ —   ↑ —" else speedSpanned(down, up, accent))
                    if (down < 0 && connected) {
                        // Baseline just established: one delayed
                        // re-update so real speeds appear shortly.
                        if (appWidgetId !in speedRetryIds) {
                            speedRetryIds += appWidgetId
                            Handler(Looper.getMainLooper()).postDelayed({
                                try { updateWidget(ctx, appWidgetId) } catch (_: Exception) {}
                            }, 3000)
                        }
                    }
                }
                views.setTextColor(R.id.widget_section_speeds, subCol)
                views.setTextViewTextSize(R.id.widget_section_speeds,
                    TypedValue.COMPLEX_UNIT_SP, 12f * sc)
            }
            // Chart (medium strip, large band; never on small).
            if (size != "small" && showChart && connected) {
                val (dSamples, uSamples) = WidgetCard.chartSamples(ctx, section)
                val chw = if (size == "large") 222f else 140f
                val chh = if (size == "large") 44f else 20f
                views.setImageViewBitmap(R.id.widget_section_chart, WidgetCard.paintChart(
                    (chw * d).toInt(), (chh * d).toInt(), dSamples, uSamples,
                    accent, WidgetCard.hueShift(accent, 55f), d))
                views.setViewVisibility(R.id.widget_section_chart, View.VISIBLE)
            } else if (size != "small") {
                views.setViewVisibility(R.id.widget_section_chart, View.GONE)
            }
            // Large stats: download / upload / session / time.
            if (size == "large") {
                val showUsage = WidgetStyle.showUsageForWidget(ctx, appWidgetId)
                val showTime = WidgetStyle.showTimeForWidget(ctx, appWidgetId)
                views.setTextViewText(R.id.widget_stat_dl_label,
                    AppLanguage.t(ctx, "دانلود", "Download"))
                views.setTextViewText(R.id.widget_stat_ul_label,
                    AppLanguage.t(ctx, "آپلود", "Upload"))
                views.setTextViewText(R.id.widget_stat_dl_value,
                    if (down < 0) "—" else formatSpeed(down))
                views.setTextViewText(R.id.widget_stat_ul_value,
                    if (up < 0) "—" else formatSpeed(up))
                views.setTextViewText(R.id.widget_stat_usage_label,
                    AppLanguage.t(ctx, "مصرف نشست", "Session"))
                views.setTextViewText(R.id.widget_stat_usage_value,
                    if (!connected || !showUsage) "—"
                    else WidgetCard.formatBytes(WidgetCard.usageBytes(ctx, section)))
                views.setTextViewText(R.id.widget_stat_time_label,
                    AppLanguage.t(ctx, "مدت اتصال", "Connected for"))
                val start = WidgetCard.sessionStartMs(ctx, section)
                views.setTextViewText(R.id.widget_stat_time_value,
                    if (!connected || !showTime || start <= 0L) "—"
                    else WidgetCard.formatDuration(System.currentTimeMillis() - start))
                for (id in listOf(R.id.widget_stat_dl_label, R.id.widget_stat_ul_label,
                    R.id.widget_stat_usage_label, R.id.widget_stat_time_label)) {
                    views.setTextColor(id, subCol)
                    views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, 10f * sc)
                }
                for (id in listOf(R.id.widget_stat_dl_value, R.id.widget_stat_ul_value,
                    R.id.widget_stat_usage_value, R.id.widget_stat_time_value)) {
                    views.setTextColor(id, textCol)
                    views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, 13f * sc)
                }
            }
            // Reliable live speeds: a 15s alarm tick chain (the
            // in-process retry above dies with the process).
            if (connected) {
                scheduleSpeedTick(ctx)
            } else {
                if (!anySectionConnected(ctx)) cancelSpeedTick(ctx)
            }
            // Taps: EVERY part of a configured widget toggles the
            // connection; nothing on the widget opens the app. (For
            // legacy non-toggleable sections the tap still opens
            // the section, there is no toggle action for them.)
            val toggleIntent = when (section) {
                "wireguard", "openvpn", "emergency" ->
                    Intent(ctx, StudioToggleActivity::class.java).apply {
                        putExtra(StudioToggleActivity.EXTRA_SECTION, section)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                "v2ray" -> Intent(ctx, WidgetToggleActivity::class.java).apply {
                    putExtra(WidgetProviderBase.EXTRA_SECTION, section)
                    putExtra(WidgetProviderBase.EXTRA_ACTION, WidgetProviderBase.ACTION_TOGGLE)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                else -> Intent(ctx, targetActivityFor(section))
            }
            val togglePending = PendingIntent.getActivity(ctx, appWidgetId * 10 + 1, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_section_root, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_btn, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_icon, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_icon_bg, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_name, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_dot, togglePending)
            views.setOnClickPendingIntent(R.id.widget_section_status, togglePending)
            if (size != "large") {
                views.setOnClickPendingIntent(R.id.widget_section_speeds, togglePending)
            } else {
                for (id in listOf(R.id.widget_stat_dl_label, R.id.widget_stat_dl_value,
                    R.id.widget_stat_ul_label, R.id.widget_stat_ul_value,
                    R.id.widget_stat_usage_label, R.id.widget_stat_usage_value,
                    R.id.widget_stat_time_label, R.id.widget_stat_time_value)) {
                    views.setOnClickPendingIntent(id, togglePending)
                }
            }
            if (size != "small") {
                views.setOnClickPendingIntent(R.id.widget_section_chart, togglePending)
            }
            try { mgr.updateAppWidget(appWidgetId, views) } catch (_: Exception) {}
        }

        private fun formatSpeed(kbps: Long): String =
            if (kbps >= 1024) String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
            else "$kbps KB/s"

        fun updateAllWidgets(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                for (cls in providerClasses()) {
                    for (id in mgr.getAppWidgetIds(ComponentName(ctx, cls))) {
                        updateWidget(ctx, id)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(ctx, id)
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        updateWidget(ctx, appWidgetId)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) WidgetStyle.clearWidget(ctx, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_SPEED_TICK) {
            try {
                updateAllWidgets(ctx)
                if (anySectionConnected(ctx)) scheduleSpeedTick(ctx)
            } catch (_: Exception) {}
            return
        }
        super.onReceive(ctx, intent)
    }
}

/** Small (2x2) and large (4x4) section widgets: same code,
 *  the size comes from the provider class (see
 *  sizeForWidgetId). The original provider stays the medium
 *  (4x2) one, so existing widgets keep working. */
class SectionWidgetSmall : SectionWidgetProvider()
class SectionWidgetLarge : SectionWidgetProvider()
