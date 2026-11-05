package com.renamer

import android.content.Context
import android.graphics.Color

/** App Monitoring (the "connection doctor" idea,
 *  deliberately NOT called a doctor): a compact,
 *  glanceable state board for every section.
 *
 *  Design rule — read only, never measure: every
 *  card is computed from state the app already
 *  keeps (live connection flags, prefs, the
 *  central log's last line per section). No new
 *  instrumentation, no background work, no new
 *  prefs, so this board cannot go stale when a
 *  section changes: it re-reads the same sources
 *  of truth on every open and resume.
 *
 *  Honesty rule — a green dot means a live
 *  session flag that is actually up right now.
 *  Anything else shows its last recorded event
 *  (amber when that event was a failure, grey
 *  when idle or unrecorded). Nothing here claims
 *  "healthy" from a log line alone. */
object Monitoring {
    const val DOT_GREEN = 0xFF2E7D32.toInt()
    const val DOT_AMBER = 0xFFF9A825.toInt()
    const val DOT_GREY = 0xFF9E9E9E.toInt()
    const val DOT_TEAL = 0xFF0F766E.toInt()

    data class Card(
        val key: String,
        val titleFa: String,
        val titleEn: String,
        val dot: Int,
        val lineFa: String,
        val lineEn: String,
        val time: String,
        val inventoryFa: String = "",
        val inventoryEn: String = ""
    )

    private data class LastEvent(val time: String, val text: String)

    private fun lineSection(line: String): String {
        return try {
            val first = line.indexOf(']')
            if (first < 0) return ""
            val second = line.indexOf(']', first + 1)
            if (second < 0) return ""
            line.substring(first + 1, second).trim().removePrefix("[").trim()
        } catch (_: Exception) { "" }
    }

    private fun lineTime(line: String): String {
        return try {
            if (line.startsWith("[") && line.length >= 7) line.substring(1, 6) else ""
        } catch (_: Exception) { "" }
    }

    /** Body of a stored line, without its time and
     *  section tags, shortened for a card. */
    private fun lineBody(line: String): String {
        return try {
            val second = line.indexOf(']', line.indexOf(']') + 1)
            val body = if (second >= 0 && second + 1 < line.length) line.substring(second + 1).trim() else line
            if (body.length > 90) body.substring(0, 90) + "…" else body
        } catch (_: Exception) { line }
    }

    private fun isFailure(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("وصل نشد") || t.contains("ناموفق") || t.contains("خطا") ||
            t.contains("failed") || t.contains("error")
    }

    private fun lastEvents(ctx: Context): Map<String, LastEvent> {
        val out = mutableMapOf<String, LastEvent>()
        try {
            val text = AppLog.text(ctx)
            if (text.isBlank()) return out
            for (line in text.lines()) {
                if (line.isBlank()) continue
                val sec = lineSection(line)
                if (sec.isBlank()) continue
                out[sec] = LastEvent(lineTime(line), lineBody(line))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun targetFa(target: String): String = when (target) {
        "wireguard" -> "وایرگارد"
        "amnezia" -> "امنزیا"
        "openvpn" -> "OpenVPN"
        "emergency" -> "اضطراری"
        "hydra" -> "هیدرا"
        "cloudflare" -> "کلودفلر"
        "chain" -> "زنجیره"
        else -> "v2ray"
    }

    /** Plain-language form of a raw engine error.
     *  Only known substrings are translated; anything
     *  else is shown raw, never guessed. */
    private fun plainError(text: String): String {
        val t = text
        return when {
            t.contains("BackendException") -> "خطای بک‌اند وایرگارد"
            t.contains("VPN permission denied") -> "مجوز VPN داده نشده"
            t.contains("UNKNOWN/UNSUPPORTED OPTIONS") -> "گزینه ناشناخته در پروفایل"
            t.contains("config error") -> "خطای پیکربندی"
            t.contains("No implementation found") -> "موتور با این گزینه سازگار نیست"
            else -> t
        }
    }

    /** One card per section, in home order. */
    fun collect(ctx: Context): List<Card> {
        val last = lastEvents(ctx)
        // Inventory + last-test health per section,
        // all from stores the sections already keep.
        val invFa = mutableMapOf<String, String>()
        val invEn = mutableMapOf<String, String>()
        try {
            val servers = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
                ?: emptyList()
            if (servers.isNotEmpty()) {
                val dj = try {
                    org.json.JSONObject(ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
                        .getString("ping_delays", "{}") ?: "{}")
                } catch (_: Exception) { org.json.JSONObject() }
                if (dj.length() > 0) {
                    val healthy = servers.count { dj.optInt(XraySpeedtest.dedupKey(it), -1) >= 0 }
                    // 2.58: the last ping sweep's
                    // clock time, from the last
                    // [ping] central-log line.
                    val pingTime = last["ping"]?.time ?: ""
                    val pingSuffixFa = if (pingTime.isNotBlank()) " • تست $pingTime" else ""
                    val pingSuffixEn = if (pingTime.isNotBlank()) " • tested $pingTime" else ""
                    invFa["v2ray"] = "${servers.size} کانفیگ • $healthy سالم از آخرین تست$pingSuffixFa"
                    invEn["v2ray"] = "${servers.size} configs • $healthy healthy at last test$pingSuffixEn"
                } else {
                    invFa["v2ray"] = "${servers.size} کانفیگ • هنوز تست نشده"
                    invEn["v2ray"] = "${servers.size} configs • not tested yet"
                }
            }
        } catch (_: Exception) {}
        try {
            val n = org.json.JSONArray(ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]").length()
            if (n > 0) {
                // 2.59: WG health from the WG test
                // (WgTestService, separate Xray core,
                // never touches a live session).
                // Amnezia tunnels are untestable, not
                // dead, and shown as such.
                val wt = ctx.getSharedPreferences("renamer_wg_test", Context.MODE_PRIVATE)
                val tested = wt.getBoolean("wg_test_done", false)
                val res = try {
                    org.json.JSONObject(wt.getString("wg_test_results", "{}") ?: "{}")
                } catch (_: Exception) { org.json.JSONObject() }
                val untestable = wt.getInt("wg_test_untestable", 0)
                if (tested && (res.length() > 0 || untestable > 0)) {
                    var healthy = 0
                    for (key in res.keys()) if (res.optBoolean(key, false)) healthy++
                    // 2.60: never let an untestable
                    // Amnezia tunnel read as dead.
                    // "0 سالم" alone made connected
                    // Amnezia tunnels look broken:
                    // the count now always names how
                    // many were actually tested, and
                    // when nothing was testable no
                    // healthy count is shown at all.
                    val unFa = if (untestable > 0) " • $untestable تست نشد" else ""
                    val unEn = if (untestable > 0) " • $untestable not tested" else ""
                    if (res.length() > 0) {
                        invFa["wireguard"] = "$n تونل • $healthy سالم از ${res.length()} تست‌شده$unFa"
                        invEn["wireguard"] = "$n tunnels • $healthy healthy of ${res.length()} tested$unEn"
                    } else {
                        invFa["wireguard"] = "$n تونل$unFa"
                        invEn["wireguard"] = "$n tunnels$unEn"
                    }
                } else {
                    invFa["wireguard"] = "$n تونل"
                    invEn["wireguard"] = "$n tunnels"
                }
            }
        } catch (_: Exception) {}
        try {
            val n = org.json.JSONArray(ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                .getString("ovpn_list", "[]") ?: "[]").length()
            if (n > 0) {
                invFa["openvpn"] = "$n پروفایل"
                invEn["openvpn"] = "$n profiles"
            }
        } catch (_: Exception) {}
        try {
            val h1 = ChainEngine.hop(ctx, 1)?.label
            val h2 = ChainEngine.hop(ctx, 2)?.label
            val parts = mutableListOf<String>()
            if (!h1.isNullOrBlank()) parts += "هاپ ۱: $h1"
            if (!h2.isNullOrBlank()) parts += "هاپ ۲: $h2"
            if (parts.isNotEmpty()) {
                invFa["chain"] = parts.joinToString(" • ")
                val partsEn = mutableListOf<String>()
                if (!h1.isNullOrBlank()) partsEn += "hop 1: $h1"
                if (!h2.isNullOrBlank()) partsEn += "hop 2: $h2"
                invEn["chain"] = partsEn.joinToString(" • ")
            }
        } catch (_: Exception) {}
        try {
            val j = org.json.JSONObject(ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
                .getString("sub_stats", "{}") ?: "{}")
            var received = 0
            var healthy = 0
            for (key in j.keys()) {
                val o = j.optJSONObject(key) ?: continue
                received += o.optInt("received", 0)
                healthy += o.optInt("healthy", 0)
            }
            if (j.length() > 0) {
                invFa["subs"] = "${j.length()} اشتراک • $healthy سالم از $received دریافت‌شده"
                invEn["subs"] = "${j.length()} subs • $healthy healthy of $received received"
            }
        } catch (_: Exception) {}
        val cards = mutableListOf<Card>()
        fun eventCard(
            key: String, titleFa: String, titleEn: String,
            live: Boolean, liveFa: String, liveEn: String
        ) {
            val ev = last[key]
            when {
                live -> cards += Card(key, titleFa, titleEn, DOT_GREEN, liveFa, liveEn, ev?.time ?: "")
                ev != null -> cards += Card(
                    key, titleFa, titleEn,
                    if (isFailure(ev.text)) DOT_AMBER else DOT_GREY,
                    ev.text, ev.text, ev.time
                )
                else -> cards += Card(key, titleFa, titleEn, DOT_GREY,
                    "رکوردی نیست", "No records yet", "")
            }
        }

        // v2ray: live only for a plain v2ray session
        // (an emergency session also runs through
        // VpnClientService, tagged "emergency").
        val vpnLive = try { VpnClientService.isRunning } catch (_: Exception) { false }
        val vpnTag = try { VpnClientService.sessionTag } catch (_: Exception) { null }
        val vpnName = try { VpnClientService.currentName } catch (_: Exception) { null }
        val vpnErr = try { VpnClientService.lastError } catch (_: Exception) { null }
        if (vpnLive && vpnTag != "emergency") {
            val name = if (!vpnName.isNullOrBlank()) ": $vpnName" else ""
            // 2.58: the truth-verifier verdict for
            // this live session, when the toggle
            // probed it (PROVEN/DEAD only; UNKNOWN
            // means not probed yet, never shown).
            val verify = try {
                ctx.getSharedPreferences("renamer_verify", Context.MODE_PRIVATE)
                    .getString("verify_state", "") ?: ""
            } catch (_: Exception) { "" }
            val verifyFa = when (verify) {
                "PROVEN" -> " • راستی‌آزمایی: ترافیک عبور می‌کند"
                "DEAD" -> " • راستی‌آزمایی: ترافیک عبور نمی‌کند"
                else -> ""
            }
            val verifyEn = when (verify) {
                "PROVEN" -> " • verified: traffic passes"
                "DEAD" -> " • verified: no traffic passes"
                else -> ""
            }
            cards += Card("v2ray", "v2ray", "v2ray", DOT_GREEN,
                "متصل$name$verifyFa", "Connected$name$verifyEn", last["v2ray"]?.time ?: "")
        } else if (!vpnErr.isNullOrBlank()) {
            cards += Card("v2ray", "v2ray", "v2ray", DOT_AMBER,
                "آخرین خطا: ${plainError(lineBody(vpnErr))}", "Last error: ${lineBody(vpnErr)}",
                last["v2ray"]?.time ?: "")
        } else {
            eventCard("v2ray", "v2ray", "v2ray", false, "", "")
        }

        // WireGuard: ground truth is the tunnel
        // OBJECTS' states (WgDirect.isReallyUp),
        // never the backend name set (isLive) or
        // the widget pref — both go stale when
        // Android revokes WG for another VPN.
        val wgLive = try { WgDirect.isReallyUp(ctx) } catch (_: Exception) { false }
        eventCard("wireguard", "وایرگارد و امنزیا", "WireGuard & Amnezia",
            wgLive, "متصل است", "Connected")

        // OpenVPN: live flag + last error are both
        // kept by the engine itself.
        val ovpnLive = try { OvpnEngine.isConnected } catch (_: Exception) { false }
        val ovpnName = try { OvpnEngine.activeName } catch (_: Exception) { null }
        val ovpnErr = try { OvpnEngine.lastError } catch (_: Exception) { null }
        if (ovpnLive) {
            val name = if (!ovpnName.isNullOrBlank()) ": $ovpnName" else ""
            cards += Card("openvpn", "OpenVPN", "OpenVPN", DOT_GREEN,
                "متصل$name", "Connected$name", last["openvpn"]?.time ?: "")
        } else if (!ovpnErr.isNullOrBlank()) {
            cards += Card("openvpn", "OpenVPN", "OpenVPN", DOT_AMBER,
                "آخرین خطا: ${plainError(lineBody(ovpnErr))}", "Last error: ${lineBody(ovpnErr)}",
                last["openvpn"]?.time ?: "")
        } else {
            eventCard("openvpn", "OpenVPN", "OpenVPN", false, "", "")
        }

        // Chain: live flag + hop labels from the
        // chain engine's own prefs.
        val chainLive = try { ChainEngine.isRunning(ctx) } catch (_: Exception) { false }
        if (chainLive) {
            val h1 = try { ChainEngine.hop(ctx, 1)?.label } catch (_: Exception) { null }
            val h2 = try { ChainEngine.hop(ctx, 2)?.label } catch (_: Exception) { null }
            val hops = if (!h1.isNullOrBlank() && !h2.isNullOrBlank()) ": $h1 ← $h2" else ""
            cards += Card("chain", "زنجیره دوتایی", "Double chain", DOT_GREEN,
                "زنجیره فعال است$hops", "Chain active$hops", last["chain"]?.time ?: "")
        } else {
            eventCard("chain", "زنجیره دوتایی", "Double chain", false, "", "")
        }

        // Emergency: a live emergency session is a
        // v2ray session tagged "emergency".
        eventCard("emergency", "اتصال اضطراری", "Emergency connect",
            vpnLive && vpnTag == "emergency", "در حال اتصال اضطراری", "Emergency session active")

        // Smart: a setting, not a session — teal
        // when enabled, grey when off, plus target
        // and selected-app count from its prefs.
        try {
            val sp = ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE)
            val enabled = sp.getBoolean("smart_enabled", false)
            val target = sp.getString("smart_target", "v2ray") ?: "v2ray"
            val apps = sp.getString("smart_apps", "")?.lines()?.count { it.isNotBlank() } ?: 0
            val ev = last["smart"]
            if (enabled) {
                cards += Card("smart", "اتصال هوشمند", "Smart Connect", DOT_TEAL,
                    "فعال — هدف: ${targetFa(target)}، $apps برنامه",
                    "On — target: $target, $apps apps", ev?.time ?: "")
            } else {
                cards += Card("smart", "اتصال هوشمند", "Smart Connect", DOT_GREY,
                    "خاموش", "Off", ev?.time ?: "")
            }
        } catch (_: Exception) {
            eventCard("smart", "اتصال هوشمند", "Smart Connect", false, "", "")
        }

        // Hydra: its service keeps a live flag.
        val hydraLive = try { HydraService.running } catch (_: Exception) { false }
        eventCard("hydra", "هیدرا", "Hydra", hydraLive, "در حال اجرا", "Running")

        // Cloudflare, subscriptions: one-shot or
        // background work — last recorded event
        // is the honest signal, never a guess.
        eventCard("cloudflare", "کلودفلر", "Cloudflare", false, "", "")
        eventCard("subs", "اشتراک‌ها و تست پینگ", "Subscriptions & ping test", false, "", "")

        // Engine: a global switch, read from the
        // engine's own isEnabled. 2.59 wording:
        // "connection sections" only — Chain,
        // Cloudflare and Subs have no engine
        // methods, so "every section" was wrong.
        try {
            if (AdaptiveEngine.isEnabled(ctx)) {
                cards += Card("engine", "موتور هوشمند", "Smart Engine", DOT_TEAL,
                    "روشن — در بخش‌های اتصال فعال است",
                    "On — active in connection sections", last["engine"]?.time ?: "")
            } else {
                cards += Card("engine", "موتور هوشمند", "Smart Engine", DOT_GREY,
                    "خاموش", "Off", "")
            }
        } catch (_: Exception) {
            eventCard("engine", "موتور هوشمند", "Smart Engine", false, "", "")
        }
        return cards.map {
            it.copy(
                inventoryFa = invFa[it.key] ?: "",
                inventoryEn = invEn[it.key] ?: ""
            )
        }
    }

    /** How many sections hold a live session right
     *  now (green dots only). */
    fun liveCount(cards: List<Card>): Int = cards.count { it.dot == DOT_GREEN }

    /** Latest event time across all cards, or "". */
    fun latestTime(cards: List<Card>): String =
        cards.mapNotNull { it.time.ifBlank { null } }.maxOrNull() ?: ""

    /** "What worked" — the engine's recent winning
     *  methods, read from recent v2ray log lines
     *  only (no new measurement). Most recent
     *  first, distinct, at most 3. */
    fun winningMethods(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val lines = AppLog.text(ctx).lines().asReversed()
            for (line in lines) {
                if (out.size >= 3) break
                val m = Regex("(?:روش برنده:|روش:|winning method:|method:)\\s*([^؛;]+)")
                    .find(line)?.groupValues?.getOrNull(1)?.trim()
                if (!m.isNullOrBlank() && m !in out &&
                    !m.contains("هیچ روشی") && !m.contains("no method")) {
                    out += m
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
