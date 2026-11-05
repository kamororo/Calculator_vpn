package com.renamer

import android.content.Context
import android.content.Intent
import android.net.VpnService
import org.json.JSONArray
import org.json.JSONObject

/** Hydra: the self-learning evasion engine.
 *
 *  LINEAGE — no home-grown packet core, by the
 *  OpenVPN lesson. This engine is built from real,
 *  proven samples (reference clones in
 *  ~/workspace/.tmp/, read-only):
 *  - METHOD, from Geneva (Univ. of Maryland,
 *    BSD-3): test evasion strategies against the
 *    LIVE network, score them, keep the winners,
 *    re-test when conditions change. Geneva's own
 *    engine needs root (NFQUEUE/iptables), so it
 *    cannot run on Android; what is copied here is
 *    its method and its four primitives (fragment,
 *    tamper, duplicate, drop), applied at the
 *    stream level instead of the packet level.
 *  - TRANSPORT ENGINE, from Xray (the v2rayNG
 *    core already in this app): every shape below
 *    is executed by Xray's own proven mechanisms —
 *    finalmask fragment, uTLS fingerprint, ws/grpc
 *    transports, mux. No new TLS/VPN core is
 *    written here.
 *  - STRATEGY CATALOG, from GoodbyeDPI/ByeDPI/
 *    PowerTunnel (LibertyTunnel): split, fake,
 *    disorder and TTL desync. The portable subset
 *    (split/fragment, fingerprint tamper) is in
 *    this version; packet-level fake/TTL/disorder
 *    needs raw packet control and is the planned
 *    phase 2, ported from PowerTunnel's Java
 *    implementation (GPL-3.0), not invented here.
 *
 *  Each probe is a REAL VPN connect attempt,
 *  verified through the tunnel (an HTTP GET
 *  through the local socks proxy must succeed).
 *  The first shape that truly works wins. A
 *  watchdog re-probes automatically when the
 *  connection dies, and the last winner is
 *  remembered so the next start tries it first.
 *
 *  Honest limits, kept in code as well as in the
 *  "What is Hydra?" text: Hydra is NOT a server
 *  network — without the user's configs or the
 *  emergency section it has nothing to shape. It
 *  cannot pass a fully closed network (no route at
 *  all). */
object HydraEngine {
    const val PREFS = "renamer_hydra"
    private const val LOG_KEY = "hydra_log"
    private const val MAX_LOG = 300

    /** Actual fragment params of the last desync
     *  attempt (set by desyncFrag, read by
     *  attempt() for the desync test log).
     *  Attempts run sequentially on one thread,
     *  so a volatile holder is enough. */
    @Volatile var lastDesyncParams: String? = null

    /** Tasting shapes. Only PLAIN, FRAGMENT
     *  variants, FINGERPRINT variants, ALPN
     *  variants and MUX are client-side-only: they
     *  change nothing the server must cooperate
     *  with, so they can rescue a config that works
     *  on another network but not on this one.
     *  WS/GRPC are last-resort guesses: forcing a
     *  transport the server does not speak cannot
     *  work, so they are tried last. */
    enum class Strategy {
        PLAIN,
        FRAGMENT, FRAGMENT_SPLIT, FRAGMENT_LONG,
        DESYNC_CLASSIC, DESYNC_CLASSIC_LONG, DESYNC_TINY, DESYNC_MID, DESYNC_WIDE, DESYNC_SLOW, DESYNC_NOISE, DESYNC_SPLIT2, DESYNC_LONG2,
        FINGERPRINT, FINGERPRINT_FIREFOX, FINGERPRINT_SAFARI, FINGERPRINT_RANDOM,
        ALPN_H2, ALPN_HTTP11, ALPN_NONE,
        WS, GRPC, MUX
    }

    /** One endpoint plus where it came from. An
     *  emergency endpoint must NEVER be named in
     *  any UI, log or session name, and its link
     *  must never be saved or passed along — only
     *  the generic words "emergency config". */
    data class Endpoint(val link: String, val fromEmergency: Boolean)

    /** Plain-language label for an endpoint, safe
     *  to show anywhere. Emergency endpoints get
     *  only the generic label, never their name. */
    fun endpointLabel(ctx: Context, e: Endpoint): String =
        if (e.fromEmergency) AppLanguage.t(ctx, "کانفیگ اضطراری", "emergency config")
        else XraySpeedtest.displayName(e.link)

    fun sourceLabel(ctx: Context, source: String): String = when (source) {
        "v2ray" -> AppLanguage.t(ctx, "کانفیگ‌های v2ray من", "my v2ray configs")
        "emergency" -> AppLanguage.t(ctx, "اضطراری", "emergency")
        "clipboard" -> AppLanguage.t(ctx, "کانفیگی که وارد کردی", "the config you pasted")
        else -> AppLanguage.t(ctx, "هر دو؛ اول v2ray، بعد اضطراری", "both; v2ray first, then emergency")
    }

    data class Opts(
        val source: String = "both",
        val maxEndpoints: Int = 3,
        val budgetSec: Int = 90,
        val useFragment: Boolean = true,
        /** Hydra-only advanced desync: desync
         *  shapes first, jittered per attempt,
         *  winner combination first. Default OFF:
         *  when off, Hydra behaves exactly as
         *  before and no other section is touched. */
        val useDesync: Boolean = false,
        val useFingerprint: Boolean = true,
        val useWs: Boolean = true,
        val useGrpc: Boolean = true,
        val useMux: Boolean = true,
        val watchdog: Boolean = true,
        val useMemory: Boolean = true,
        /** Keep trying round after round until
         *  connected (no 60s idle after a failed
         *  probe). When off, one round only. */
        val continuous: Boolean = true,
        /** Order v2ray endpoints by a fast direct
         *  TCP handshake before tasting shapes. */
        val tcpRank: Boolean = true,
        /** Try ALPN variations (h2 / http/1.1 /
         *  none) on TLS configs. */
        val useAlpn: Boolean = true
    )

    fun loadOpts(ctx: Context): Opts {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Opts(
            source = p.getString("source", "both") ?: "both",
            maxEndpoints = (p.getString("max_endpoints", "5") ?: "5").toIntOrNull()?.coerceIn(1, 10) ?: 5,
            budgetSec = (p.getString("budget_sec", "90") ?: "90").toIntOrNull()?.coerceIn(30, 600) ?: 90,
            useFragment = p.getBoolean("use_fragment", true),
            useDesync = p.getBoolean("use_desync", false),
            useFingerprint = p.getBoolean("use_fingerprint", true),
            useWs = p.getBoolean("use_ws", true),
            useGrpc = p.getBoolean("use_grpc", true),
            useMux = p.getBoolean("use_mux", true),
            watchdog = p.getBoolean("watchdog", true),
            useMemory = p.getBoolean("use_memory", true),
            continuous = p.getBoolean("continuous", true),
            tcpRank = p.getBoolean("tcp_rank", true),
            useAlpn = p.getBoolean("use_alpn", true)
        )
    }

    fun saveOpts(ctx: Context, o: Opts) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("source", o.source)
            .putString("max_endpoints", o.maxEndpoints.toString())
            .putString("budget_sec", o.budgetSec.toString())
            .putBoolean("use_fragment", o.useFragment)
            .putBoolean("use_desync", o.useDesync)
            .putBoolean("use_fingerprint", o.useFingerprint)
            .putBoolean("use_ws", o.useWs)
            .putBoolean("use_grpc", o.useGrpc)
            .putBoolean("use_mux", o.useMux)
            .putBoolean("watchdog", o.watchdog)
            .putBoolean("use_memory", o.useMemory)
            .putBoolean("continuous", o.continuous)
            .putBoolean("tcp_rank", o.tcpRank)
            .putBoolean("use_alpn", o.useAlpn)
            .apply()
    }

    fun isDesyncStrategy(s: Strategy): Boolean = s.name.startsWith("DESYNC_")

    fun strategyLabel(ctx: Context, s: Strategy): String = when (s) {
        Strategy.PLAIN -> AppLanguage.t(ctx, "ساده (بدون تغییر)", "Plain (no change)")
        Strategy.FRAGMENT -> AppLanguage.t(ctx, "Fragment (tlshello)", "Fragment (tlshello)")
        Strategy.FRAGMENT_SPLIT -> AppLanguage.t(ctx, "Fragment (تکه ۱ تا ۳)", "Fragment (split 1-3)")
        Strategy.FRAGMENT_LONG -> AppLanguage.t(ctx, "Fragment (تکه‌های بلند)", "Fragment (long splits)")
        Strategy.DESYNC_CLASSIC -> AppLanguage.t(ctx, "دی‌سینک کلاسیک (همان برد قبلی، بدون تغییر)", "Desync classic (the previous win, unchanged)")
        Strategy.DESYNC_CLASSIC_LONG -> AppLanguage.t(ctx, "دی‌سینک کلاسیک بلند", "Desync classic long")
        Strategy.DESYNC_TINY -> AppLanguage.t(ctx, "دی‌سینک ریز (تکه‌ی اول، کوتاه)", "Desync tiny (first packet, short)")
        Strategy.DESYNC_MID -> AppLanguage.t(ctx, "دی‌سینک میانی (۱ تا ۲)", "Desync mid (1-2)")
        Strategy.DESYNC_WIDE -> AppLanguage.t(ctx, "دی‌سینک گسترده (۱ تا ۵)", "Desync wide (1-5)")
        Strategy.DESYNC_SLOW -> AppLanguage.t(ctx, "دی‌سینک کند (تأخیر زیاد)", "Desync slow (long delays)")
        Strategy.DESYNC_NOISE -> AppLanguage.t(ctx, "دی‌سینک نویزی (نویز سنگین)", "Desync noisy (heavy noise)")
        Strategy.DESYNC_SPLIT2 -> AppLanguage.t(ctx, "دی‌سینک تکه‌ی دوم (۱ تا ۳، کوتاه)", "Desync split 2 (1-3, short)")
        Strategy.DESYNC_LONG2 -> AppLanguage.t(ctx, "دی‌سینک بلند ۲ (تکه‌های خیلی بلند)", "Desync long 2 (very long splits)")
        Strategy.FINGERPRINT -> AppLanguage.t(ctx, "اثر انگشت کروم", "Chrome fingerprint")
        Strategy.FINGERPRINT_FIREFOX -> AppLanguage.t(ctx, "اثر انگشت فایرفاکس", "Firefox fingerprint")
        Strategy.FINGERPRINT_SAFARI -> AppLanguage.t(ctx, "اثر انگشت سافاری", "Safari fingerprint")
        Strategy.FINGERPRINT_RANDOM -> AppLanguage.t(ctx, "اثر انگشت تصادفی", "Randomized fingerprint")
        Strategy.ALPN_H2 -> AppLanguage.t(ctx, "ALPN: h2", "ALPN: h2")
        Strategy.ALPN_HTTP11 -> AppLanguage.t(ctx, "ALPN: http/1.1", "ALPN: http/1.1")
        Strategy.ALPN_NONE -> AppLanguage.t(ctx, "ALPN: هیچ‌کدام", "ALPN: none")
        Strategy.WS -> AppLanguage.t(ctx, "WebSocket", "WebSocket")
        Strategy.GRPC -> AppLanguage.t(ctx, "gRPC", "gRPC")
        Strategy.MUX -> AppLanguage.t(ctx, "Mux", "Mux")
    }

    // ---- Report log: simple language + exact time ----

    fun log(ctx: Context, fa: String, en: String) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try { JSONArray(p.getString(LOG_KEY, "[]") ?: "[]") }
                catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("fa", fa)
                put("en", en)
            })
            while (arr.length() > MAX_LOG) arr.remove(0)
            p.edit().putString(LOG_KEY, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    data class LogLine(val timeMs: Long, val fa: String, val en: String)

    fun readLog(ctx: Context): List<LogLine> {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LOG_KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LogLine(o.optLong("t", 0L), o.optString("fa", ""), o.optString("en", ""))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clearLog(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LOG_KEY, "[]").apply()
    }

    // ---- Winner memory ----

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Marker for the last winner: either a plain
     *  link, or the literal marker "emergency"
     *  (never an emergency link — those are never
     *  saved). */
    fun lastWinner(ctx: Context): Pair<String, String>? {
        val e = prefs(ctx).getString("last_endpoint", null)?.takeIf { it.isNotBlank() } ?: return null
        val s = prefs(ctx).getString("last_strategy", null)?.takeIf { it.isNotBlank() } ?: return null
        return e to s
    }

    fun saveWinner(ctx: Context, endpoint: Endpoint, strategy: Strategy) {
        val marker = if (endpoint.fromEmergency) "emergency" else endpoint.link
        prefs(ctx).edit()
            .putString("last_endpoint", marker)
            .putString("last_strategy", strategy.name)
            .apply()
    }

    fun clearMemory(ctx: Context) {
        prefs(ctx).edit().remove("last_endpoint").remove("last_strategy").apply()
    }

    fun isActive(ctx: Context): Boolean =
        prefs(ctx).getBoolean("hydra_active", false)

    fun setActive(ctx: Context, active: Boolean) {
        prefs(ctx).edit().putBoolean("hydra_active", active).apply()
    }

    fun setPhase(ctx: Context, phase: String, detail: String) {
        prefs(ctx).edit().putString("hydra_phase", phase)
            .putString("hydra_detail", detail).apply()
    }

    // ---- Strategy application on a built Xray JSON ----

    /** Apply one shape to a built VPN config JSON.
     *  Returns the modified JSON, or null when the
     *  shape does not apply to this config (e.g.
     *  fragment on a non-TLS config, mux on a
     *  shadowsocks config). The caller logs that
     *  skip in plain language; it is not a failure. */
    fun applyStrategy(jsonStr: String, s: Strategy, host: String): String? {
        if (s == Strategy.PLAIN) return jsonStr
        return try {
            val json = JSONObject(jsonStr)
            val outs = json.optJSONArray("outbounds") ?: return null
            var proxy: JSONObject? = null
            for (i in 0 until outs.length()) {
                val ob = outs.optJSONObject(i) ?: continue
                if (ob.optString("tag", "") == "proxy") { proxy = ob; break }
                val proto = ob.optString("protocol", "")
                if (proto != "freedom" && proto != "blackhole" && proxy == null) proxy = ob
            }
            val ob = proxy ?: return null
            val protocol = ob.optString("protocol", "").lowercase()
            val ss = ob.optJSONObject("streamSettings")
            // Desync jitter: every desync attempt
            // varies its length/delay/split around
            // the base values, so each attempt
            // presents a different signature and
            // DPI cannot just block one fixed
            // pattern (the fixed pattern is why a
            // shape worked once and then never
            // again). The ACTUAL values used are
            // recorded for the desync test log.
            fun jitterRange(range: String, pct: Int, minV: Int): String {
                val parts = range.split("-")
                val a = parts.getOrNull(0)?.toIntOrNull() ?: return range
                val b = parts.getOrNull(1)?.toIntOrNull() ?: return range
                fun j(v: Int): Int {
                    val d = (v * pct / 100).coerceAtLeast(2)
                    return (v + java.util.Random().nextInt(d * 2 + 1) - d).coerceAtLeast(minV)
                }
                val na = j(a); val nb = j(b)
                return if (na <= nb) "$na-$nb" else "$nb-$na"
            }
            fun jitterInt(v: Int, delta: Int, minV: Int, maxV: Int): Int {
                return (v + java.util.Random().nextInt(delta * 2 + 1) - delta).coerceIn(minV, maxV)
            }
            fun putFragment(packets: String, length: String, delay: String, maxSplit: Int, noiseRand: String = "10-20", noiseDelay: String = "10-16"): Boolean {
                if (ss == null) return false
                val security = ss.optString("security", "")
                if (security != "tls" && security != "reality") return false
                if (ss.optJSONObject("sockopt")?.optString("dialerProxy", "")
                        .isNullOrBlank().not()) return false
                // Reality always splits 1-3 (as before);
                // TLS varies the pattern per variant.
                val pk = if (security == "reality") "1-3" else packets
                // Built in flat pieces on purpose: the
                // old nested-apply chain was easy to
                // unbalance by one brace.
                val tcpFrag = JSONObject().apply {
                    put("type", "fragment")
                    put("settings", JSONObject().apply {
                        put("packets", pk)
                        put("length", length)
                        put("delay", delay)
                        put("maxSplit", maxSplit)
                    })
                }
                val udpNoise = JSONObject().apply {
                    put("type", "noise")
                    put("settings", JSONObject().apply {
                        put("noise", JSONArray().put(JSONObject().apply {
                            put("rand", noiseRand)
                            put("delay", noiseDelay)
                        }))
                    })
                }
                ss.put("finalmask", JSONObject().apply {
                    put("tcp", JSONArray().put(tcpFrag))
                    put("udp", JSONArray().put(udpNoise))
                })
                return true
            }
            fun desyncFrag(packets: String, length: String, delay: String, maxSplit: Int, noiseRand: String = "10-20", noiseDelay: String = "10-16", jitter: Boolean = true): Boolean {
                val security = ss?.optString("security", "") ?: ""
                val effPackets = if (security == "reality") "1-3" else packets
                val jl = if (jitter) jitterRange(length, 25, 10) else length
                val jd = if (jitter) jitterRange(delay, 30, 3) else delay
                val jm = if (jitter) jitterInt(maxSplit, 2, 1, 25) else maxSplit
                lastDesyncParams = "packets=$effPackets, len=$jl, delay=$jd, maxSplit=$jm"
                return putFragment(packets, jl, jd, jm, noiseRand, noiseDelay)
            }

            fun putFingerprint(value: String): Boolean {
                val tls = ss?.optJSONObject("tlsSettings")
                val reality = ss?.optJSONObject("realitySettings")
                when {
                    tls != null -> tls.put("fingerprint", value)
                    reality != null -> reality.put("fingerprint", value)
                    else -> return false
                }
                return true
            }
            when (s) {
                Strategy.FRAGMENT -> {
                    if (!putFragment("tlshello", "50-100", "10-20", 10)) return null
                }
                Strategy.FRAGMENT_SPLIT -> {
                    if (!putFragment("1-3", "50-100", "10-20", 10)) return null
                }
                Strategy.FRAGMENT_LONG -> {
                    if (!putFragment("tlshello", "100-200", "30-60", 15)) return null
                }
                Strategy.DESYNC_CLASSIC -> {
                    // Exact old FRAGMENT params, no
                    // jitter: this IS the combination
                    // that won once before, so it
                    // must be tried exactly, not
                    // varied away from the win.
                    if (!desyncFrag("tlshello", "50-100", "10-20", 10, jitter = false)) return null
                }
                Strategy.DESYNC_CLASSIC_LONG -> {
                    if (!desyncFrag("tlshello", "100-200", "30-60", 15, jitter = false)) return null
                }
                Strategy.DESYNC_TINY -> {
                    if (!desyncFrag("1", "20-40", "5-10", 5)) return null
                }
                Strategy.DESYNC_MID -> {
                    if (!desyncFrag("1-2", "40-80", "8-15", 8)) return null
                }
                Strategy.DESYNC_WIDE -> {
                    if (!desyncFrag("1-5", "80-150", "15-30", 20)) return null
                }
                Strategy.DESYNC_SLOW -> {
                    if (!desyncFrag("tlshello", "50-100", "50-100", 10)) return null
                }
                Strategy.DESYNC_NOISE -> {
                    if (!desyncFrag("tlshello", "50-100", "10-20", 10, "20-40", "5-10")) return null
                }
                Strategy.DESYNC_SPLIT2 -> {
                    if (!desyncFrag("1-3", "30-60", "5-12", 12)) return null
                }
                Strategy.DESYNC_LONG2 -> {
                    if (!desyncFrag("tlshello", "150-250", "20-40", 12)) return null
                }
                Strategy.FINGERPRINT -> {
                    if (!putFingerprint("chrome")) return null
                }
                Strategy.FINGERPRINT_FIREFOX -> {
                    if (!putFingerprint("firefox")) return null
                }
                Strategy.FINGERPRINT_SAFARI -> {
                    if (!putFingerprint("safari")) return null
                }
                Strategy.FINGERPRINT_RANDOM -> {
                    if (!putFingerprint("randomized")) return null
                }
                Strategy.ALPN_H2, Strategy.ALPN_HTTP11, Strategy.ALPN_NONE -> {
                    // ALPN lives in tlsSettings only;
                    // Reality negotiates it differently,
                    // so this shape does not apply there.
                    val tls = ss?.optJSONObject("tlsSettings") ?: return null
                    val arr = JSONArray()
                    when (s) {
                        Strategy.ALPN_H2 -> arr.put("h2")
                        Strategy.ALPN_HTTP11 -> arr.put("http/1.1")
                        else -> {}
                    }
                    tls.put("alpn", arr)
                }
                Strategy.WS -> {
                    if (ss == null) return null
                    if (ss.optString("network", "") == "ws") return null
                    ss.put("network", "ws")
                    ss.put("wsSettings", JSONObject().apply {
                        put("path", "/")
                        put("headers", JSONObject().apply { put("Host", host) })
                    })
                }
                Strategy.GRPC -> {
                    if (ss == null) return null
                    if (ss.optString("network", "") == "grpc") return null
                    ss.put("network", "grpc")
                    ss.put("grpcSettings", JSONObject().apply {
                        put("serviceName", "grpc")
                    })
                }
                Strategy.MUX -> {
                    if (protocol != "vmess" && protocol != "vless") return null
                    if (ob.has("mux")) return null
                    ob.put("mux", JSONObject().apply {
                        put("enabled", true)
                        put("concurrency", 8)
                        put("xudpConcurrency", 8)
                    })
                }
                else -> return jsonStr
            }
            json.toString()
        } catch (_: Exception) { null }
    }


    // ---- Endpoints ----

    /** Plain links AND custom JSON configs are
     *  probables. Customs used to be filtered out
     *  here — so a list of only customs resolved to
     *  "no endpoint found" while the same configs
     *  connected fine in the v2ray section (which
     *  builds them via buildCustom). Chains stay
     *  out: a chain is multi-hop, not a single
     *  outbound to taste. Groups stay out as
     *  headers; their members are plain links and
     *  are already included. */
    fun v2rayEndpoints(ctx: Context): List<Endpoint> {
        return VpnSubs.loadServers(ctx).filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }.map { Endpoint(it, false) }
    }

    /** Order endpoints by a fast direct-TCP
     *  handshake (<=1s each, in parallel): the
     *  lowest TCP latency is tried first, because
     *  it is the fastest expected win. A TCP
     *  failure does NOT drop an endpoint — a
     *  filtered direct route can still work
     *  through the proxy — it only sorts last. */
    private fun rankByTcp(endpoints: List<Endpoint>): List<Endpoint> {
        if (endpoints.size < 2) return endpoints
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val scored = endpoints.mapIndexed { idx, ep ->
                val future = pool.submit<Long> {
                    val srv = try { XraySpeedtest.parseServer(ep.link) } catch (_: Exception) { null }
                    if (srv == null) Long.MAX_VALUE
                    else {
                        val ms = XraySpeedtest.tcpConnectTime(srv.host, srv.port)
                        if (ms < 0) Long.MAX_VALUE else ms
                    }
                }
                Triple(idx, ep, future)
            }
            val withScores = scored.map { (idx, ep, f) ->
                val s = try { f.get(3, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: Exception) { Long.MAX_VALUE }
                Triple(idx, ep, s)
            }
            return withScores.sortedWith(compareBy({ it.third }, { it.first })).map { it.second }
        } finally {
            pool.shutdownNow()
        }
    }

    fun emergencyEndpoints(ctx: Context): List<Endpoint> {
        return try { EmergencyConnect.sortedConfigs(ctx) }
            catch (_: Exception) { emptyList() }
            .map { Endpoint(it, true) }
    }

    /** The single pasted config, if the user chose
     *  the clipboard source and pasted a valid
     *  link on the options page. */
    fun clipboardEndpoint(ctx: Context): Endpoint? {
        val raw = prefs(ctx).getString("clipboard_config", "") ?: ""
        val line = raw.lines().firstOrNull { it.contains("://") }?.trim()
            ?: return null
        // Customs are allowed here: a pasted custom
        // JSON is probed with the plain shape (its
        // own config, built via buildCustom).
        if (XraySpeedtest.isGroup(line) || XraySpeedtest.isChain(line)) return null
        if (XraySpeedtest.parseServer(line) == null) return null
        return Endpoint(line, false)
    }

    /** ONLY the chosen source is used — nothing
     *  else. (An earlier report said emergency
     *  configs were used while "my v2ray configs"
     *  was selected; that was the old default
     *  "both" still saved, because the Save button
     *  sat below the fold of a page that did not
     *  scroll. The options now live on their own
     *  short page, and the chosen source is logged
     *  at every probe start so the report proves
     *  which source was used.) */
    fun resolveEndpoints(ctx: Context, opts: Opts): List<Endpoint> {
        log(ctx, "منبع انتخاب‌شده: ${sourceLabel(ctx, opts.source)}.",
            "Chosen source: ${sourceLabel(ctx, opts.source)}.")
        when (opts.source) {
            // ADAPTIVE ENGINE (1.78): alternate-IP
            // endpoints ahead of each original
            // (engine-first, originals stay as
            // fallback). Off = list untouched.
            "v2ray" -> return AdaptiveEngine.expandHydraEndpoints(ctx, v2rayEndpoints(ctx).take(opts.maxEndpoints))
            "emergency" -> {
                log(ctx, "در حال گرفتن کانفیگ‌های تازه‌ی اضطراری برای چشیدن…",
                    "Fetching fresh emergency configs for probing…")
                return emergencyEndpoints(ctx).take(opts.maxEndpoints)
            }
            "clipboard" -> {
                val one = clipboardEndpoint(ctx)
                if (one == null) {
                    log(ctx, "برای منبع «کانفیگ واردشده» هنوز کانفیگ معتبری وارد نکردی؛ از صفحه‌ی آپشن‌ها کانفیگ را بچسبان.",
                        "For the “pasted config” source you have not pasted a valid config yet; paste one on the options page.")
                    return emptyList()
                }
                return listOf(one)
            }
            else -> {
                val out = mutableListOf<Endpoint>()
                out += v2rayEndpoints(ctx)
                if (out.size < opts.maxEndpoints) {
                    log(ctx, "در حال گرفتن کانفیگ‌های تازه‌ی اضطراری برای چشیدن…",
                        "Fetching fresh emergency configs for probing…")
                    out += emergencyEndpoints(ctx)
                }
                return AdaptiveEngine.expandHydraEndpoints(ctx, out.distinctBy { it.link }.take(opts.maxEndpoints))
            }
        }
    }

    // ---- VPN plumbing ----

    /** Tear down a Hydra attempt's core. The STOP
     *  intent carries the hydra_teardown extra so
     *  VpnClientService does NOT clear hydra_active:
     *  without it, every failed attempt's teardown
     *  killed the whole probe after ONE shape (the
     *  user watched exactly that: plain failed, then
     *  "probing stopped"). A genuine user stop
     *  (Hydra button, HydraService stop, v2ray page
     *  disconnect) sends STOP without the extra, so
     *  it still clears the flag. */
    fun stopVpn(ctx: Context) {
        try {
            val stop = Intent(ctx, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
                putExtra("hydra_teardown", true)
            }
            ctx.startService(stop)
        } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
    }

    /** Is any VPN already up (ours, WireGuard,
     *  OpenVPN, or another app's)? Hydra never
     *  fights an existing VPN. */
    fun anyVpnUp(ctx: Context): Boolean {
        if (VpnClientService.isRunning) return true
        try { if (WgDirect.isUp(ctx)) return true } catch (_: Exception) {}
        try { if (OvpnEngine.isConnected) return true } catch (_: Exception) {}
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val active = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(active)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) { false }
    }

    /** True when the running VPN is Hydra's own
     *  (its session name starts with the Hydra
     *  prefix set in attempt()). */
    fun hydraOwnsVpn(): Boolean {
        return VpnClientService.isRunning &&
            (VpnClientService.sessionTag == "hydra" ||
                (VpnClientService.currentName ?: "").startsWith("هیدرا:"))
    }

    /** Which VPN is up, in plain words, so the
     *  "steps aside" message is never a mystery:
     *  our own session (by tag), a live WireGuard
     *  tunnel, OpenVPN, or another app's VPN. */
    fun whichVpnUp(ctx: Context): String? {
        if (VpnClientService.isRunning) {
            val tag = VpnClientService.sessionTag
            return try { VpnClientService.sectionLabel(ctx, tag) } catch (_: Exception) { tag ?: "?" }
        }
        try { if (WgDirect.isLive(ctx)) return "WireGuard" } catch (_: Exception) {}
        try { if (OvpnEngine.isConnected) return "OpenVPN" } catch (_: Exception) {}
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true)
                AppLanguage.t(ctx, "یک برنامه‌ی دیگر", "another app")
            else null
        } catch (_: Exception) { null }
    }

    /** The "steps aside" line, with the reason. */
    fun logHandover(ctx: Context) {
        val who = whichVpnUp(ctx)
        if (who != null) {
            log(ctx, "اتصال «$who» فعال است؛ هیدرا کنار می‌کشد.",
                "A “$who” connection is active; Hydra steps aside.")
        } else {
            log(ctx, "اتصال به یک VPN دیگر واگذار شد؛ هیدرا کنار می‌کشد.",
                "The connection was handed to another VPN; Hydra steps aside.")
        }
    }

    /** Explicit-tap takeover (HydraService.start
     *  ONLY — never the watchdog or continuous
     *  re-probes, which still step aside). The
     *  user tapped connect, so Hydra stops what
     *  it can stop (our own session, a live
     *  WireGuard tunnel, OpenVPN), repairs a
     *  stale WireGuard flag, and waits for the
     *  tunnels to release — mirroring what
     *  WireGuard already does to v2ray. Another
     *  app's VPN cannot be stopped; the probe
     *  will then step aside WITH the reason. */
    fun takeoverForExplicitConnect(ctx: Context) {
        try {
            if (WgDirect.clearStaleFlag(ctx)) {
                log(ctx, "حالت وایرگارد قدیمی و بی‌اعتبار بود؛ پاک شد تا جلوی هیدرا را نگیرد.",
                    "The WireGuard flag was stale; cleared so it no longer blocks Hydra.")
            }
        } catch (_: Exception) {}
        var stopped = false
        try {
            if (VpnClientService.isRunning) { stopVpn(ctx); stopped = true }
        } catch (_: Exception) {}
        try {
            if (WgDirect.isLive(ctx)) { WgDirect.disconnectAll(ctx); stopped = true }
        } catch (_: Exception) {}
        try {
            if (OvpnEngine.isConnected) { OvpnEngine.disconnect(); stopped = true }
        } catch (_: Exception) {}
        if (stopped) {
            log(ctx, "اتصال قبلی قطع شد تا هیدرا وصل شود.",
                "The previous connection was stopped so Hydra can connect.")
            try { Thread.sleep(800) } catch (_: Exception) {}
        }
    }

    private fun verifyTunnel(ctx: Context, port: Int): Boolean {
        return try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", port))
            val conn = java.net.URL(VpnConfigBuilder.delayTestUrl(ctx))
                .openConnection(proxy) as java.net.HttpURLConnection
            // Faster failures: a filtered tunnel hangs
            // every verify try until its timeout, so
            // shorter timeouts mean many more shapes
            // fit in one round's budget. 3s is plenty
            // for a healthy tunnel (generate_204
            // answers in well under a second even on
            // slow nets).
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            try { conn.disconnect() } catch (_: Exception) {}
            code in 200..299
        } catch (_: Exception) { false }
    }

    enum class AttemptResult { SUCCESS, FAILED, CORE_FAILED, TUNNEL_FAILED, NOT_APPLICABLE, PERMISSION_NEEDED, ABORTED, STOLEN }

    /** One real connect attempt with one shape.
     *  STOLEN means another VPN came up instead of
     *  ours — we must NOT stop it. */
    fun attempt(ctx: Context, endpoint: Endpoint, strategy: Strategy): AttemptResult {
        val cfg = endpoint.link
        val host = XraySpeedtest.parseServer(cfg)?.host ?: ""
        val base = VpnConfigBuilder.build(ctx, cfg) ?: return AttemptResult.FAILED
        val json = if (strategy == Strategy.PLAIN) base
            else (applyStrategy(base, strategy, host) ?: return AttemptResult.NOT_APPLICABLE)
        if (isDesyncStrategy(strategy)) {
            HydraDesyncLog.log(ctx,
                "امتحان «${endpointLabel(ctx, endpoint)}» با ${strategyLabel(ctx, strategy)}: ${lastDesyncParams ?: ""}",
                "Trying “${endpointLabel(ctx, endpoint)}” with ${strategyLabel(ctx, strategy)}: ${lastDesyncParams ?: ""}")
        }
        if (!CoreInit.init(ctx.applicationContext)) return AttemptResult.FAILED
        if (VpnService.prepare(ctx) != null) return AttemptResult.PERMISSION_NEEDED
        // Emergency sessions carry ONLY the generic
        // name — never the config's real name, and
        // never the link extra.
        val name = "هیدرا: " + endpointLabel(ctx, endpoint)
        val intent = Intent(ctx, VpnClientService::class.java).apply {
            putExtra("config", json)
            putExtra("name", name)
            if (!endpoint.fromEmergency) putExtra("link", cfg)
            putExtra("session_tag", "hydra")
        }
        VpnClientService.emergencyConfig = null
        try { ctx.startService(intent) } catch (_: Exception) { return AttemptResult.FAILED }
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            if (!isActive(ctx)) { stopVpn(ctx); return AttemptResult.ABORTED }
            if (VpnClientService.isRunning) break
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        if (!VpnClientService.isRunning) { stopVpn(ctx); return AttemptResult.CORE_FAILED }
        if (VpnClientService.currentName != name) {
            // Someone else's VPN came up mid-attempt.
            return AttemptResult.STOLEN
        }
        val port = VpnConfigBuilder.socksPort(ctx)
        if (port <= 0) return AttemptResult.SUCCESS
        // The old code verified the tunnel exactly
        // ONCE, the instant the core reported up —
        // but the local socks proxy is often not
        // accepting yet at that exact moment, so a
        // healthy connection was killed as "failed"
        // and every shape died the same way. That
        // is why good configs connected in the v2ray
        // section (which has no such check) but not
        // in Hydra. Retry a few times before judging.
        var ok = false
        for (i in 0 until 3) {
            if (verifyTunnel(ctx, port)) { ok = true; break }
            if (i < 2) try { Thread.sleep(1000) } catch (_: Exception) {}
        }
        if (!ok) { stopVpn(ctx); return AttemptResult.TUNNEL_FAILED }
        return AttemptResult.SUCCESS
    }

    /** The full probe: ordered candidates, within
     *  the time budget, until one shape truly works.
     *  Returns true on a verified win. */
    /** Short Latin type token for a built config:
     *  e.g. "VLESS + Reality", "VLESS + TLS + WS",
     *  "Trojan + TLS", "Shadowsocks". Used by the
     *  probe's detection line so the user sees WHAT
     *  Hydra thinks the config is, before it starts
     *  trying shapes for it. */
    fun describeType(baseJson: String?): String {
        if (baseJson == null) return "Unknown (build failed)"
        return try {
            val json = JSONObject(baseJson)
            val outs = json.optJSONArray("outbounds") ?: return "Unknown"
            var proxy: JSONObject? = null
            for (i in 0 until outs.length()) {
                val ob = outs.optJSONObject(i) ?: continue
                if (ob.optString("tag", "") == "proxy") { proxy = ob; break }
                val proto = ob.optString("protocol", "")
                if (proto != "freedom" && proto != "blackhole" && proxy == null) proxy = ob
            }
            val ob = proxy ?: return "Unknown"
            val proto = ob.optString("protocol", "").uppercase().ifBlank { "Unknown" }
            val ss = ob.optJSONObject("streamSettings")
            val security = ss?.optString("security", "") ?: ""
            val network = ss?.optString("network", "") ?: ""
            val parts = mutableListOf(proto)
            if (security.isNotBlank() && security != "none") parts += security.replaceFirstChar { it.uppercase() }
            if (network.isNotBlank() && network != "tcp") parts += network.uppercase()
            parts.joinToString(" + ")
        } catch (_: Exception) { "Unknown" }
    }

    /** Taste cursor: index into the candidate
     *  list where the next round resumes. A
     *  round's budget usually runs out mid-list;
     *  without this, every round retried the same
     *  first shapes and later shapes/endpoints
     *  starved forever. */
    fun getTasteCursor(ctx: Context): Int =
        ctx.getSharedPreferences("renamer_hydra", Context.MODE_PRIVATE)
            .getInt("taste_cursor", 0)

    fun setTasteCursor(ctx: Context, idx: Int) {
        ctx.getSharedPreferences("renamer_hydra", Context.MODE_PRIVATE)
            .edit().putInt("taste_cursor", idx).apply()
    }

    fun probeAndConnect(ctx: Context, opts: Opts, startIndex: Int = 0): Boolean {
        if (anyVpnUp(ctx) && !hydraOwnsVpn()) {
            logHandover(ctx)
            return false
        }
        val endpoints = resolveEndpoints(ctx, opts)
        if (endpoints.isEmpty()) {
            // Say WHY, with counts, instead of a bare
            // "no endpoint": the report must prove
            // what the saved list actually held.
            if (opts.source == "v2ray") {
                val all = VpnSubs.loadServers(ctx)
                val groups = all.count { XraySpeedtest.isGroup(it) }
                val chains = all.count { XraySpeedtest.isChain(it) }
                if (all.isEmpty()) {
                    log(ctx, "در بخش v2ray هیچ کانفیگی ذخیره نشده؛ اول کانفیگ اضافه کن.",
                        "No config is saved in the v2ray section; add a config first.")
                } else {
                    log(ctx, "از ${all.size} کانفیگ ذخیره‌شده هیچ‌کدام قابل چشیدن نبود ($groups گروه، $chains زنجیره).",
                        "None of the ${all.size} saved configs could be probed ($groups groups, $chains chains).")
                }
            } else {
                log(ctx, "نقطه‌ی اتصالی از منبع انتخاب‌شده پیدا نشد.",
                    "No endpoint found from the chosen source.")
            }
            return false
        }
        log(ctx, "چشیدن شروع شد؛ ${endpoints.size} نقطه‌ی اتصال پیدا شد.",
            "Probing started; ${endpoints.size} endpoints found.")
        AppLog.append(ctx, "hydra",
            "چشیدن شروع شد؛ ${endpoints.size} نقطه‌ی اتصال؛ منبع: ${sourceLabel(ctx, opts.source)}.",
            "Probing started; ${endpoints.size} endpoints; source: ${sourceLabel(ctx, opts.source)}.")
        // Desync-first (Hydra-only tick): when the
        // desync tick is on, desync shapes are the
        // FIRST attempts, before plain and before
        // the old fixed fragments. When off, this
        // list is exactly the old one.
        val strategies = mutableListOf<Strategy>()
        if (opts.useDesync) {
            strategies += Strategy.DESYNC_CLASSIC
            strategies += Strategy.DESYNC_CLASSIC_LONG
            strategies += Strategy.DESYNC_TINY
            strategies += Strategy.DESYNC_MID
            strategies += Strategy.DESYNC_WIDE
            strategies += Strategy.DESYNC_SLOW
            strategies += Strategy.DESYNC_NOISE
            strategies += Strategy.DESYNC_SPLIT2
            strategies += Strategy.DESYNC_LONG2
        }
        strategies += Strategy.PLAIN
        if (opts.useFragment) {
            strategies += Strategy.FRAGMENT
            strategies += Strategy.FRAGMENT_SPLIT
            strategies += Strategy.FRAGMENT_LONG
        }
        if (opts.useFingerprint) {
            strategies += Strategy.FINGERPRINT
            strategies += Strategy.FINGERPRINT_FIREFOX
            strategies += Strategy.FINGERPRINT_SAFARI
            strategies += Strategy.FINGERPRINT_RANDOM
        }
        if (opts.useAlpn) {
            strategies += Strategy.ALPN_H2
            strategies += Strategy.ALPN_HTTP11
            strategies += Strategy.ALPN_NONE
        }
        if (opts.useDesync) {
            HydraDesyncLog.log(ctx,
                "دی‌سینک هیدرا فعال؛ شکل‌های دی‌سینک اول امتحان می‌شوند (۹ شکل؛ کلاسیک اول، بقیه با پارامتر تصادفی).",
                "Hydra desync on; desync shapes are tried first (9 shapes; classic first, the rest randomized).")
        }
        // MUX is client-side; WS/GRPC are
        // server-dependent guesses, so they go last.
        if (opts.useMux) strategies += Strategy.MUX
        if (opts.useWs) strategies += Strategy.WS
        if (opts.useGrpc) strategies += Strategy.GRPC
        // Strict source order: the v2ray section
        // first, then the emergency section. The
        // memory winner only moves to the FRONT OF
        // ITS OWN SECTION — it used to jump ahead
        // of everything, which is why "both" seemed
        // to test emergency first.
        var v2raySection = endpoints.filter { !it.fromEmergency }.toMutableList()
        val emergencySection = endpoints.filter { it.fromEmergency }.toMutableList()
        // Smart order: TCP-rank the v2ray section
        // (the emergency section already arrives in
        // ping order from its own fetch). The memory
        // winner below still jumps to the front of
        // its own section, ahead of the ranking.
        if (opts.tcpRank && v2raySection.size > 1) {
            v2raySection = rankByTcp(v2raySection).toMutableList()
            log(ctx, "نقطه‌های v2ray با تست سریع TCP مرتب شدند؛ کم‌تأخیرترین اول امتحان می‌شود.",
                "v2ray endpoints were ordered by a fast TCP test; the lowest-latency one is tried first.")
        }
        if (opts.useMemory) {
            val winner = lastWinner(ctx)
            if (winner != null) {
                val (we, ws) = winner
                val strat = try { Strategy.valueOf(ws) } catch (_: Exception) { null }
                if (strat != null) {
                    val section = if (we == "emergency") emergencySection else v2raySection
                    val hit = if (we == "emergency") section.firstOrNull()
                        else section.firstOrNull { it.link == we }
                    if (hit != null) {
                        section.remove(hit)
                        section.add(0, hit)
                        log(ctx, "حافظه می‌گوید دفعه‌ی قبل این ترکیب جواب داد؛ در بخش خودش اول امتحانش می‌کنم.",
                            "Memory says this combination worked last time; trying it first within its own section.")
                    }
                }
            }
        }
        val candidates = mutableListOf<Pair<Endpoint, Strategy>>()
        for (ep in v2raySection + emergencySection) {
            val nm = endpointLabel(ctx, ep)
            if (XraySpeedtest.isCustom(ep.link)) {
                // A custom JSON is the user's own
                // config: only the plain shape applies
                // to it. Rewriting its outbounds with
                // fragment / fingerprint / ws / grpc /
                // mux shapes would corrupt it.
                log(ctx, "تشخیص «$nm»: کانفیگ سفارشی (JSON کامل)؛ فقط شکل ساده قابل امتحان است.",
                    "Detected for “$nm”: custom config (full JSON); only the plain shape applies.")
                val pair = ep to Strategy.PLAIN
                if (!candidates.contains(pair)) candidates += pair
                continue
            }
            // Detect the type from the built config
            // and keep ONLY the shapes that apply to
            // it, so the report shows a diagnosis
            // instead of a row of "does not apply"
            // lines, and no budget is wasted on
            // shapes that cannot work for this type.
            // (A Reality config, for example, gets
            // plain + fragments + fingerprints + mux
            // — never ALPN, which does not apply to
            // Reality — and the line below says so.)
            val base = VpnConfigBuilder.build(ctx, ep.link)
            val host = XraySpeedtest.parseServer(ep.link)?.host ?: ""
            val strats = strategies.filter { s ->
                s == Strategy.PLAIN || (base != null && applyStrategy(base, s, host) != null)
            }
            val shapeNames = strats.joinToString("، ") { strategyLabel(ctx, it) }
            log(ctx, "تشخیص «$nm»: ${describeType(base)}؛ شکل‌های قابل امتحان: $shapeNames.",
                "Detected for “$nm”: ${describeType(base)}; shapes to try: $shapeNames.")
            for (s in strats) {
                val pair = ep to s
                if (!candidates.contains(pair)) candidates += pair
            }
        }
        // Desync winner-first: when the desync tick
        // is on and memory holds a winner, that
        // EXACT (endpoint, strategy) pair goes to
        // the very front and the round starts from
        // it — the old memory only moved the
        // endpoint, leaving plain first, and the
        // taste cursor (an index into a list that
        // TCP ranking reshuffles every round) could
        // skip the winner entirely. That is why a
        // shape worked once and then never again.
        var winnerMoved = false
        if (opts.useDesync && opts.useMemory) {
            val winner = lastWinner(ctx)
            if (winner != null) {
                val (we, ws) = winner
                val strat = try { Strategy.valueOf(ws) } catch (_: Exception) { null }
                if (strat != null) {
                    val hitIdx = candidates.indexOfFirst { (ep, s) ->
                        s == strat && (if (we == "emergency") ep.fromEmergency else ep.link == we)
                    }
                    if (hitIdx > 0) {
                        val hit = candidates.removeAt(hitIdx)
                        candidates.add(0, hit)
                        winnerMoved = true
                        HydraDesyncLog.log(ctx,
                            "حافظه: ترکیب برنده‌ی قبلی اول امتحان می‌شود: «${endpointLabel(ctx, hit.first)}» با ${strategyLabel(ctx, strat)}.",
                            "Memory: previous winning combination is tried first: “${endpointLabel(ctx, hit.first)}” with ${strategyLabel(ctx, strat)}.")
                    }
                }
            }
        }
        // Desync shape-grouping: when the desync
        // tick is on, try each desync shape for
        // ALL endpoints before moving to the next
        // shape (classic for every endpoint first,
        // then classic-long, then the rest). The
        // old per-endpoint order buried the known
        // win for a later endpoint behind dozens
        // of attempts on earlier endpoints, and
        // the round budget ran out first. The
        // winner pair (if any) stays at index 0.
        if (opts.useDesync) {
            val desyncOrder = listOf(Strategy.DESYNC_CLASSIC, Strategy.DESYNC_CLASSIC_LONG, Strategy.DESYNC_TINY, Strategy.DESYNC_MID, Strategy.DESYNC_WIDE, Strategy.DESYNC_SLOW, Strategy.DESYNC_NOISE, Strategy.DESYNC_SPLIT2, Strategy.DESYNC_LONG2)
            val winnerPair = if (winnerMoved) candidates.removeAt(0) else null
            val desyncPairs = mutableListOf<Pair<Endpoint, Strategy>>()
            val restPairs = mutableListOf<Pair<Endpoint, Strategy>>()
            for (pr in candidates) {
                if (isDesyncStrategy(pr.second)) desyncPairs += pr else restPairs += pr
            }
            desyncPairs.sortBy { desyncOrder.indexOf(it.second) }
            candidates.clear()
            if (winnerPair != null) candidates += winnerPair
            candidates += desyncPairs
            candidates += restPairs
        }
        val budgetDeadline = System.currentTimeMillis() + opts.budgetSec * 1000L
        // Resume across rounds (see getTasteCursor):
        // endpoints may have changed between rounds,
        // so clamp the cursor into this list — but
        // never past a winner that was just moved
        // to the front.
        val start = if (winnerMoved) 0 else if (startIndex in candidates.indices) startIndex else 0
        if (start > 0) log(ctx, "ادامه‌ی چشیدن از ترکیب شماره ${start + 1} از ${candidates.size}.",
            "Resuming probing from combination ${start + 1} of ${candidates.size}.")
        for (idx in start until candidates.size) {
            val (endpoint, strategy) = candidates[idx]
            if (!isActive(ctx)) {
                setTasteCursor(ctx, 0)
                log(ctx, "چشیدن متوقف شد.", "Probing stopped.")
                return false
            }
            // Settle: let the previous attempt's core
            // fully release the TUN before starting
            // the next attempt.
            try { Thread.sleep(600) } catch (_: Exception) {}
            if (System.currentTimeMillis() > budgetDeadline) {
                setTasteCursor(ctx, idx)
                log(ctx, "زمان این دور تمام شد؛ دور بعد از ترکیب شماره ${idx + 1} ادامه می‌دهد.",
                    "This round's time ran out; the next round resumes from combination ${idx + 1}.")
                return false
            }
            val name = endpointLabel(ctx, endpoint)
            log(ctx, "در حال امتحان «$name» با شکل ${strategyLabel(ctx, strategy)}…",
                "Trying “$name” with shape ${strategyLabel(ctx, strategy)}…")
            when (attempt(ctx, endpoint, strategy)) {
                AttemptResult.SUCCESS -> {
                    if (endpoint.fromEmergency) {
                        log(ctx, "به کانفیگ اضطراری وصل شد.",
                            "Connected to an emergency config.")
                    } else {
                        log(ctx, "وصل شد! شکل برنده برای «$name»: ${strategyLabel(ctx, strategy)}.",
                            "Connected! Winning shape for “$name”: ${strategyLabel(ctx, strategy)}.")
                    }

                    saveWinner(ctx, endpoint, strategy)
                    if (isDesyncStrategy(strategy)) {
                        HydraDesyncLog.log(ctx,
                            "وصل شد با ${strategyLabel(ctx, strategy)} برای «$name».",
                            "Connected with ${strategyLabel(ctx, strategy)} for “$name”.")
                    }
                    setTasteCursor(ctx, 0)
                    setPhase(ctx, "connected",
                        AppLanguage.t(ctx, "متصل: $name", "Connected: $name"))
                    AppLog.append(ctx, "hydra",
                        "«$name» با شکل «${strategyLabel(ctx, strategy)}» وصل شد.",
                        "“$name” connected with shape “${strategyLabel(ctx, strategy)}”.")
                    // 2.45: background real-connection check.
                    ConnVerify.verifyXray(ctx, "hydra", "hydra", name, VpnConfigBuilder.socksPort(ctx)) { VpnClientService.isRunning && VpnClientService.sessionTag == "hydra" }
                    return true
                }
                AttemptResult.NOT_APPLICABLE -> {
                    log(ctx, "این شکل برای «$name» معنا ندارد؛ رد می‌شوم.",
                        "This shape does not apply to “$name”; skipping.")

                }
                AttemptResult.PERMISSION_NEEDED -> {
                    setTasteCursor(ctx, 0)
                    log(ctx, "مجوز VPN هنوز ثبت نشده؛ اول یک بار از بخش v2ray دستی وصل شو، بعد هیدرا را بزن.",
                        "VPN permission is not granted yet; connect once manually from the v2ray section, then try Hydra.")
                    return false
                }
                AttemptResult.ABORTED -> { setTasteCursor(ctx, 0); return false }
                AttemptResult.STOLEN -> {
                    setTasteCursor(ctx, 0)
                    logHandover(ctx)
                    return false
                }
                AttemptResult.CORE_FAILED -> {
                    log(ctx, "هسته برای «$name» بالا نیامد؛ شکل بعدی.",
                        "The core did not come up for “$name”; next shape.")
                    if (isDesyncStrategy(strategy)) {
                        HydraDesyncLog.log(ctx,
                            "«${strategyLabel(ctx, strategy)}» برای «$name»: هسته بالا نیامد.",
                            "“${strategyLabel(ctx, strategy)}” for “$name”: the core did not come up.")
                    }

                }
                AttemptResult.TUNNEL_FAILED -> {
                    log(ctx, "«$name» وصل شد ولی تونلش جواب نداد؛ شکل بعدی.",
                        "“$name” connected but its tunnel did not respond; next shape.")
                    if (isDesyncStrategy(strategy)) {
                        HydraDesyncLog.log(ctx,
                            "«${strategyLabel(ctx, strategy)}» برای «$name» وصل شد ولی تونلش جواب نداد.",
                            "“${strategyLabel(ctx, strategy)}” connected for “$name” but its tunnel did not respond.")
                    }

                }
                AttemptResult.FAILED -> {
                    log(ctx, "این ترکیب جواب نداد؛ شکل بعدی.",
                        "This combination did not work; next shape.")
                    if (isDesyncStrategy(strategy)) {
                        HydraDesyncLog.log(ctx,
                            "«${strategyLabel(ctx, strategy)}» برای «$name» جواب نداد.",
                            "“${strategyLabel(ctx, strategy)}” did not work for “$name”.")
                    }

                }
            }
        }
        setTasteCursor(ctx, 0)
        log(ctx, "همه‌ی شکل‌ها امتحان شد و هیچ‌کدام وصل نشد.",
            "All shapes were tried and none connected.")
        setPhase(ctx, "failed",
            AppLanguage.t(ctx, "هیچ شکلی جواب نداد", "No shape worked"))
        AppLog.append(ctx, "hydra",
            "همه‌ی شکل‌ها امتحان شد و هیچ‌کدام وصل نشد.",
            "All shapes were tried and none connected.")
        return false
    }
}
