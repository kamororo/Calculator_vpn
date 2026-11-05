package com.renamer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Minimal client for the paste site the Alien
 *  Message feature uses (API documented at the
 *  site's own /doc/api page).
 *
 *  - POST / with form fields c (content), e
 *    (expiration seconds) creates a paste and
 *    returns {url, admin|manageUrl, ...}. The password
 *    field is deliberately OMITTED (the user wants
 *    it empty); the server then generates one, and
 *    the returned manageUrl carries it.
 *  - GET <url> returns the raw paste content.
 *  - PUT <manageUrl> with c + e replaces the
 *    content (and refreshes the expiration).
 *
 *  SECURITY RULE: this file never logs, prints or
 *  otherwise reveals a URL. The raw site address
 *  is used only inside HTTP calls; every surface
 *  the user sees shows the alien-encoded form.
 */
object ShzClient {
    const val EXPIRATION = "7776000" // 90 days, accepted by the new host too (verified 2026-11-02).
    const val HOST = "https://paste.example.com"
    // Previous host, read-only: links created
    // before the switch still fetch, update and
    // parse through the helpers below, so no
    // existing conversation or receiver link breaks.
    const val LEGACY_HOST = "legacy.example.com"

    /** True when a raw URL belongs to either
     *  host (new creates, old links). */
    fun isSiteLink(raw: String): Boolean {
        return raw.startsWith("https://") &&
            (raw.contains(HOST + "/") || raw.contains(LEGACY_HOST + "/"))
    }

    /** Index just after the host in a site URL,
     *  or -1 when it is not one of our hosts. */
    fun hostEnd(raw: String): Int {
        for (h in listOf(HOST, LEGACY_HOST)) {
            val i = raw.indexOf(h)
            if (i >= 0) return i + h.length
        }
        return -1
    }

    /** True when decoded text would reveal a
     *  site address (either host). */
    fun containsHost(s: String): Boolean {
        return s.contains(HOST) || s.contains(LEGACY_HOST)
    }

    data class Result(val ok: Boolean, val body: String?, val code: Int)

    private fun randomSuffix(n: Int = 6): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.util.Random()
        val sb = StringBuilder()
        repeat(n) { sb.append(chars[r.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun multipart(url: String, method: String, content: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val boundary = "----AlienSub" + randomSuffix(12)
            conn.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=$boundary")
            val out = conn.outputStream
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            // Content part with a random file name
            // (the site's "file name" field).
            val fileName = "alien-" + randomSuffix() + ".txt"
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"c\"; filename=\"$fileName\"\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(content.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            field("e", EXPIRATION)
            out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var n2 = it.read(tmp)
                while (n2 >= 0) { buf.write(tmp, 0, n2); n2 = it.read(tmp) }
                buf.toString("UTF-8")
            }
            Result(code in 200..299, body, code)
        } catch (_: Exception) {
            Result(false, null, -1)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun get(url: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var n2 = it.read(tmp)
                while (n2 >= 0) { buf.write(tmp, 0, n2); n2 = it.read(tmp) }
                buf.toString("UTF-8")
            }
            Result(code in 200..299, body, code)
        } catch (_: Exception) {
            Result(false, null, -1)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Split a single link or a "|" bundle
     *  (1.3 mirror format) into its parts.
     *  URLs never contain "|", so it is a safe
     *  separator, and it is never sent to a
     *  server — fetch/update split first. */
    fun splitBundle(s: String): List<String> =
        s.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    /** Create on one host. Returns
     *  (viewUrl, manageUrl) or null. Both hosts
     *  answer "admin" or "manageUrl"; accept
     *  both, so the same code reads either. */
    fun createOn(host: String, content: String): Pair<String, String>? {
        val r = multipart("https://$host/", "POST", content)
        if (!r.ok || r.body == null) return null
        return try {
            val o = JSONObject(r.body)
            val url = o.optString("url", "")
            var manage = o.optString("manageUrl", "")
            if (manage.isBlank()) manage = o.optString("admin", "")
            if (url.isBlank() || manage.isBlank()) null else url to manage
        } catch (_: Exception) {
            null
        }
    }

    /** Create a paste. Tries the main host
     *  first; only on failure does it fall back
     *  to the backup host. Returns (viewUrl,
     *  manageUrl) or null on failure. */
    fun create(content: String): Pair<String, String>? {
        return createOn(HOST, content) ?: createOn(LEGACY_HOST, content)
    }

    /** Create the SAME content on both hosts
     *  and return it as a "|" bundle
     *  (viewA|viewB, manageA|manageB). If only
     *  one host works, the bundle has one part
     *  — the fallback result. Null only when
     *  neither host works. A receiver tries
     *  each part in turn, so two users whose
     *  working hosts differ can still read the
     *  same shared link, as long as the sender
     *  could reach both hosts when creating. */
    fun createMirror(content: String): Pair<String, String>? {
        // 2.44: both hosts at once, not one after
        // the other — a fresh create writes the same
        // content to both, so parallel is identical
        // in result and about twice as fast.
        var a: Pair<String, String>? = null
        var b: Pair<String, String>? = null
        val ta = Thread { a = try { createOn(HOST, content) } catch (_: Exception) { null } }
        val tb = Thread { b = try { createOn(LEGACY_HOST, content) } catch (_: Exception) { null } }
        ta.start(); tb.start()
        try { ta.join() } catch (_: Exception) {}
        try { tb.join() } catch (_: Exception) {}
        if (a == null && b == null) return null
        val views = listOfNotNull(a?.first, b?.first).joinToString("|")
        val manages = listOfNotNull(a?.second, b?.second).joinToString("|")
        return views to manages
    }

    /** The part of a "|" bundle served by one host, or null. */
    fun partForHost(bundle: String, host: String): String? =
        splitBundle(bundle).firstOrNull { it.contains(host + "/") }

    /** Create honouring the send mode: "primary" = first method
     *  only, "backup" = backup method only, anything else =
     *  mirror (both methods, as before). */
    fun createForMode(mode: String, content: String): Pair<String, String>? {
        return when (mode) {
            "primary" -> createOn(HOST, content)
            "backup" -> createOn(LEGACY_HOST, content)
            else -> createMirror(content)
        }
    }

    /** Update honouring the send mode. Single-method modes update
     *  only that method's part of the bundle (false when the bundle
     *  has no such part); anything else updates every part. */
    fun updateForMode(mode: String, manageBundle: String, content: String): Boolean {
        return when (mode) {
            "primary" -> partForHost(manageBundle, HOST)?.let { update(it, content) } ?: false
            "backup" -> partForHost(manageBundle, LEGACY_HOST)?.let { update(it, content) } ?: false
            else -> updateAny(manageBundle, content)
        }
    }

    /** Fetch from a single link or a bundle:
     *  first part that answers wins. */
    fun fetchAny(viewBundle: String): String? {
        for (v in splitBundle(viewBundle)) {
            val c = fetch(v)
            if (c != null) return c
        }
        return null
    }

    /** Update every part of a manage bundle.
     *  True when at least one part updated. */
    fun updateAny(manageBundle: String, content: String): Boolean {
        // 2.44: every part at once (same OR result
        // as the old sequential loop).
        val parts = splitBundle(manageBundle)
        if (parts.isEmpty()) return false
        val results = BooleanArray(parts.size)
        val threads = parts.mapIndexed { i, m ->
            Thread { results[i] = try { update(m, content) } catch (_: Exception) { false } }
        }
        threads.forEach { it.start() }
        threads.forEach { try { it.join() } catch (_: Exception) {} }
        return results.any { it }
    }

    /** Delete every part of a manage bundle. */
    fun deleteAny(manageBundle: String): Boolean {
        // 2.44: every part at once (same OR result).
        val parts = splitBundle(manageBundle)
        if (parts.isEmpty()) return false
        val results = BooleanArray(parts.size)
        val threads = parts.mapIndexed { i, m ->
            Thread { results[i] = try { delete(m) } catch (_: Exception) { false } }
        }
        threads.forEach { it.start() }
        threads.forEach { try { it.join() } catch (_: Exception) {} }
        return results.any { it }
    }

    /** View link(s) for a manage link or bundle. */
    fun viewOfAny(manageBundle: String): String {
        return splitBundle(manageBundle).map { m ->
            val he = hostEnd(m)
            if (he < 0) m else {
                val colon = m.indexOf(":", he + 1)
                if (colon > 0) m.substring(0, colon) else m
            }
        }.joinToString("|")
    }

    /** Fetch raw content. Null = missing/expired
     *  (404) or network failure. */
    fun fetch(viewUrl: String): String? {
        val r = get(viewUrl)
        return if (r.ok) r.body else null
    }

    /** Replace a paste's content. True on success. */
    fun update(manageUrl: String, content: String): Boolean {
        return multipart(manageUrl, "PUT", content).ok
    }

    /** Delete a paste entirely (2.28: deleting
     *  a conversation removes its link too).
     *  True on success; failures are ignored by
     *  callers — the local record is gone
     *  either way. */
    fun delete(manageUrl: String): Boolean {
        return try {
            val conn = java.net.URL(manageUrl).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val code = conn.responseCode
            try { conn.inputStream?.close() } catch (_: Exception) {}
            try { conn.errorStream?.close() } catch (_: Exception) {}
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) { false }
    }
}
