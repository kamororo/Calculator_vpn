package com.renamer

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Background "really connected?" verifier (2.45).
 *
 *  The connect buttons turn green the moment a
 *  session starts — before the core is up, and
 *  even when the tunnel is up but carries no
 *  traffic (the post-handshake data kill). This
 *  object probes a fresh connection in the
 *  background and writes the verdict into the
 *  central log, and the v2ray button drops its
 *  green when the verdict is DEAD.
 *
 *  The probe runs ONLY when the user turned it
 *  on (the switch on the «لاگ برنامه» page,
 *  off by default). Triggers are one-shots per
 *  connect; a second trigger for the same
 *  session while a probe runs is skipped.
 *  A probe that finds its session gone aborts
 *  silently — a user stop is never reported as
 *  a dead tunnel. A DEAD verdict never stops
 *  the session by itself: a probe failure can
 *  be transient, so stopping stays the user's
 *  tap. */
object ConnVerify {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("renamer_verify", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("verify_enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("verify_enabled", on).apply()
    }

    /** Start a verification for this session.
     *  False = verifier off, or the same session
     *  is already being verified. */
    fun begin(ctx: Context, kind: String, tag: String, name: String): Boolean {
        if (!isEnabled(ctx)) return false
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val same = p.getString("verify_state", "") == "UNKNOWN"
            && p.getString("verify_kind", "") == kind
            && p.getString("verify_tag", "") == tag
            && p.getString("verify_name", "") == name
            && now - p.getLong("verify_time", 0L) < 60000
        if (same) return false
        p.edit()
            .putString("verify_state", "UNKNOWN")
            .putString("verify_kind", kind)
            .putString("verify_tag", tag)
            .putString("verify_name", name)
            .putLong("verify_time", now)
            .apply()
        return true
    }

    /** Record a verdict. Ignored when the state
     *  moved on (a stale probe finishing late) or
     *  when it is for another session. */
    fun finish(ctx: Context, kind: String, tag: String, name: String,
               verdict: String, detailFa: String, detailEn: String) {
        val p = prefs(ctx)
        if (p.getString("verify_state", "") != "UNKNOWN") return
        if (p.getString("verify_kind", "") != kind
            || p.getString("verify_tag", "") != tag
            || p.getString("verify_name", "") != name) return
        p.edit().putString("verify_state", verdict).apply()
        AppLog.append(ctx, kind,
            if (name.isBlank()) "بررسی واقعی اتصال: $detailFa"
            else "بررسی واقعی اتصال «$name»: $detailFa",
            if (name.isBlank()) "Real-connection check: $detailEn"
            else "Real-connection check “$name”: $detailEn")
    }

    /** True only for the current v2ray-family
     *  session with a DEAD verdict. Drives the
     *  v2ray button's red state. */
    fun isDeadV2ray(ctx: Context, tag: String?, name: String?): Boolean {
        if (tag.isNullOrBlank() || name.isNullOrBlank()) return false
        val p = prefs(ctx)
        return p.getString("verify_state", "") == "DEAD"
            && p.getString("verify_kind", "") == "v2ray"
            && p.getString("verify_tag", "") == tag
            && p.getString("verify_name", "") == name
    }

    private val ipRe = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    /** Through-tunnel probe via a SOCKS port:
     *  generate_204, then ipify; both are tried
     *  again once after 2s. True when ANY of the
     *  four attempts carries traffic. Two
     *  independent hosts, not one, so a single
     *  blocked probe host cannot fake a death. */
    /** Raw SOCKS5 probe (2.54): Java's own SOCKS
     *  path resolves the target locally and cannot
     *  do proxy auth, so it can fail where real
     *  tunnel traffic succeeds. This client speaks
     *  SOCKS5 itself: domain-form CONNECT (Xray
     *  resolves remotely, exactly like TUN traffic)
     *  + password auth from the local-proxy prefs
     *  when set. Plain HTTP to two hosts; any HTTP
     *  status line means traffic passed. The second
     *  value names the failing stage, so a DEAD
     *  verdict says WHICH layer failed instead of
     *  guessing. Stages: ok, socks_refused (no TCP
     *  to the local proxy), socks_method (bad
     *  greeting), socks_auth (auth required or
     *  failed), socks_connect (CONNECT refused),
     *  http_fail (connected but no HTTP answer). */
    fun socksProbeDetailed(ctx: Context, port: Int): Pair<Boolean, String> {
        fun probeHost(host: String, path: String): Pair<Boolean, String> {
            var sock: java.net.Socket? = null
            var phase = "connect"
            try {
                sock = java.net.Socket()
                try {
                    sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
                } catch (_: Exception) {
                    return Pair(false, "socks_refused")
                }
                sock.soTimeout = 5000
                phase = "handshake"
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                fun readByte(): Int {
                    val b = inp.read()
                    if (b < 0) throw java.io.EOFException()
                    return b
                }
                fun readExact(n: Int) {
                    var off = 0
                    val buf = ByteArray(n)
                    while (off < n) {
                        val r = inp.read(buf, off, n - off)
                        if (r < 0) throw java.io.EOFException()
                        off += r
                    }
                }
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                out.flush()
                val ver = readByte()
                val method = readByte()
                if (ver != 0x05 || method == 0xFF) return Pair(false, "socks_method")
                if (method == 0x02) {
                    val vp = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                    val user = vp.getString("local_proxy_user", "") ?: ""
                    val pass = vp.getString("local_proxy_pass", "") ?: ""
                    val enabled = vp.getBoolean("enable_local_proxy", false)
                    if (!enabled || user.isBlank()) return Pair(false, "socks_auth")
                    val ub = user.toByteArray(Charsets.UTF_8)
                    val pb = pass.toByteArray(Charsets.UTF_8)
                    if (ub.size > 255 || pb.size > 255) return Pair(false, "socks_auth")
                    val req = ByteArray(3 + ub.size + pb.size)
                    req[0] = 0x01; req[1] = ub.size.toByte()
                    ub.copyInto(req, 2)
                    req[2 + ub.size] = pb.size.toByte()
                    pb.copyInto(req, 3 + ub.size)
                    out.write(req); out.flush()
                    val aver = readByte(); val astat = readByte()
                    if (aver != 0x01 || astat != 0x00) return Pair(false, "socks_auth")
                } else if (method != 0x00) {
                    return Pair(false, "socks_method")
                }
                val hb = host.toByteArray(Charsets.UTF_8)
                val creq = ByteArray(7 + hb.size)
                creq[0] = 0x05; creq[1] = 0x01; creq[2] = 0x00; creq[3] = 0x03
                creq[4] = hb.size.toByte()
                hb.copyInto(creq, 5)
                creq[5 + hb.size] = 0; creq[6 + hb.size] = 80
                out.write(creq); out.flush()
                val rver = readByte(); val rep = readByte()
                readByte()
                val atyp = readByte()
                if (rver != 0x05 || rep != 0x00) return Pair(false, "socks_connect")
                when (atyp) {
                    0x01 -> readExact(4)
                    0x03 -> { val l = readByte(); readExact(l) }
                    0x04 -> readExact(16)
                    else -> return Pair(false, "socks_connect")
                }
                readExact(2)
                phase = "http"
                val http = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                out.write(http.toByteArray(Charsets.UTF_8)); out.flush()
                val line = java.io.BufferedReader(java.io.InputStreamReader(inp, Charsets.UTF_8)).readLine()
                    ?: return Pair(false, "http_fail")
                val code = line.split(" ").getOrNull(1)?.toIntOrNull()
                if (code != null && code in 100..599) return Pair(true, "ok")
                return Pair(false, "http_fail")
            } catch (_: Exception) {
                return Pair(false, if (phase == "handshake") "socks_method" else "http_fail")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        fun once(): Pair<Boolean, String> {
            val a = probeHost("www.gstatic.com", "/generate_204")
            if (a.first) return a
            val b = probeHost("api.ipify.org", "/")
            if (b.first) return b
            val rank = mapOf("socks_refused" to 5, "socks_method" to 4, "socks_auth" to 3,
                "socks_connect" to 2, "http_fail" to 1)
            return if ((rank[a.second] ?: 0) >= (rank[b.second] ?: 0)) a else b
        }
        val first = once()
        if (first.first) return first
        try { Thread.sleep(2000) } catch (_: Exception) {}
        return once()
    }

    /** Boolean form kept for the engine paths
     *  (fallback check, watchdog, emergency check). */
    fun socksProbe(ctx: Context, port: Int): Boolean =
        socksProbeDetailed(ctx, port).first

    /** Exit IP through the tunnel (plain fetch,
     *  no proxy — the app's own traffic rides
     *  the TUN for direct sessions). Null when
     *  no exit IP could be read. */
    fun exitIpProbe(): String? {
        val urls = listOf("https://api.ipify.org", "https://checkip.amazonaws.com")
        for (u in urls) {
            try {
                val c = URL(u).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.requestMethod = "GET"
                if (c.responseCode == 200) {
                    val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (body.matches(ipRe)) return body
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /** Xray-family session (v2ray/smart/emergency/
     *  hydra/chain): probe through the session's
     *  own socks inbound. */
    fun verifyXray(ctx: Context, kind: String, tag: String, name: String,
                   port: Int, alive: () -> Boolean) {
        if (!begin(ctx, kind, tag, name)) return
        if (port <= 0) {
            // 2.53 PRECISION: with no usable socks
            // port a probe cannot run at all — that
            // is UNKNOWN, never DEAD.
            finish(ctx, kind, tag, name, "SKIP",
                "پورت پروکسی جلسه مشخص نیست؛ قضاوتی نمی‌شود",
                "Session proxy port unknown; no verdict")
            return
        }
        Thread {
            try {
                if (!alive()) return@Thread
                val (ok, stage) = socksProbeDetailed(ctx, port)
                if (!alive()) return@Thread
                finish(ctx, kind, tag, name,
                    if (ok) "PROVEN" else "DEAD",
                    if (ok) "واقعاً وصل است (ترافیک از تونل گذشت)"
                    else "وصل نیست — " + when (stage) {
                        "socks_refused" -> "پروکسی محلی جلسه در دسترس نیست"
                        "socks_method" -> "پروکسی محلی پاسخ درستی نداد"
                        "socks_auth" -> "پروکسی محلی احراز هویت می‌خواهد"
                        "socks_connect" -> "پروکسی محلی به مقصد وصل نشد"
                        else -> "تونل بالا است ولی ترافیکی عبور نمی‌دهد"
                    },
                    if (ok) "really connected (traffic passed through the tunnel)"
                    else "not connected — " + when (stage) {
                        "socks_refused" -> "local session proxy unreachable"
                        "socks_method" -> "local proxy gave a bad greeting"
                        "socks_auth" -> "local proxy wants authentication"
                        "socks_connect" -> "local proxy could not connect out"
                        else -> "the tunnel is up but no traffic passes"
                    })
            } catch (_: Exception) {}
        }.start()
    }

    /** OpenVPN direct session: probe the exit IP
     *  through the TUN. */
    fun verifyOvpn(ctx: Context, kind: String, tag: String, name: String,
                   alive: () -> Boolean) {
        if (!begin(ctx, kind, tag, name)) return
        Thread {
            try {
                if (!alive()) return@Thread
                val ip = exitIpProbe()
                if (!alive()) return@Thread
                finish(ctx, kind, tag, name,
                    if (ip != null) "PROVEN" else "DEAD",
                    if (ip != null) "واقعاً وصل است (IP خروجی: $ip)"
                    else "وصل نیست — IP خروجی از تونل خوانده نشد",
                    if (ip != null) "really connected (exit IP: $ip)"
                    else "not connected — no exit IP could be read through the tunnel")
            } catch (_: Exception) {}
        }.start()
    }

    /** A session that cannot be probed from
     *  inside the app (OVPN chains: the chain
     *  TUN disallows the app's own traffic).
     *  Records the reason instead of a fake
     *  verdict. */
    fun skip(ctx: Context, kind: String, tag: String, name: String,
             reasonFa: String, reasonEn: String) {
        if (!begin(ctx, kind, tag, name)) return
        finish(ctx, kind, tag, name, "SKIP", reasonFa, reasonEn)
    }
}
