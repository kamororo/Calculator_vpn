package com.renamer

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds a Nahan panel on the user's OWN Cloudflare account, from the
 * app, with the user's Global API credentials — exactly the way the
 * old BPB section built its panel, but for Nahan (D1 instead of KV).
 *
 * Steps (proven by the deploy that built the user's live panel):
 *  1. account: first account id (GET /accounts)
 *  2. subdomain: read the account's workers.dev subdomain
 *     (GET /accounts/{id}/workers/subdomain). There is NO API that
 *     mints a subdomain name — MLMVPN treats a missing one the same
 *     way: the user picks it once in the dashboard, then retries.
 *  3. d1: reuse a database with the given name, else create it
 *  4. table: CREATE TABLE IF NOT EXISTS kv_store in that database
 *  5. upload: PUT the Nahan bundle as worker.js with metadata
 *     (main_module worker.js, compatibility_date 2024-09-22,
 *     nodejs_compat, IOT_DB d1 binding)
 *  6. enable: POST subdomain {enabled: true} for the worker
 *  7. verify: GET the worker settings, confirm the IOT_DB binding
 *
 * Synchronous; call from a background thread. onStep fires AFTER each
 * step succeeds, so the UI can append a checkmark line per step. Any
 * failure stops the chain and returns Err(code): "auth" (bad email /
 * Global key), "no-subdomain", "http <n>", "timeout", "network",
 * "parse", "empty". Nothing here logs or stores the email, the key,
 * the account id, or the panel host — the activity owns prefs, and
 * the key stays only on this phone.
 */
object CfDeployClient {

    private const val API = "https://api.cloudflare.com/client/v4"

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class Err(val code: String) : Result<Nothing>()
    }

    data class DeployOutcome(val panelHost: String, val workerName: String)

    // ------------------------------------------------------------------
    // Low-level HTTP (Global auth: X-Auth-Email + X-Auth-Key)
    // ------------------------------------------------------------------

    private data class HttpOut(val code: Int, val body: String)

    private fun call(
        method: String,
        path: String,
        email: String,
        key: String,
        jsonBody: JSONObject?,
        rawBody: ByteArray?,
        contentType: String?
    ): HttpOut {
        val conn = URL(API + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 90000
        conn.requestMethod = method
        conn.setRequestProperty("X-Auth-Email", email)
        conn.setRequestProperty("X-Auth-Key", key)
        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody.toString()) }
        } else if (rawBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(rawBody) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = try {
            stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        conn.disconnect()
        return HttpOut(code, text)
    }

    private fun fail(e: Exception): String =
        if (e is java.net.SocketTimeoutException) "timeout" else "network"

    private fun failCode(code: Int): String =
        if (code == 401 || code == 403) "auth" else "http $code"

    /** Parse a Cloudflare envelope; null when it is not a success. */
    private fun resultOf(body: String): JSONObject? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) json.optJSONObject("result") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun multipart(metadata: JSONObject, script: ByteArray): Pair<ByteArray, String> {
        val boundary = "----cfnahan" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"")
        line("Content-Type: application/json")
        line("")
        out.write(metadata.toString().toByteArray(Charsets.UTF_8))
        line("")
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"")
        line("Content-Type: application/javascript+module")
        line("")
        out.write(script)
        line("")
        line("--$boundary--")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    // ------------------------------------------------------------------
    // Deploy
    // ------------------------------------------------------------------

    fun deploy(
        email: String,
        key: String,
        workerName: String,
        dbName: String,
        script: ByteArray,
        onStep: (String) -> Unit
    ): Result<DeployOutcome> {
        try {
            // 1. Account.
            val (acctCode, acctBody) = call("GET", "/accounts?per_page=50", email, key, null, null, null)
            if (acctCode !in 200..299) return Result.Err(failCode(acctCode))
            val acctJson = try {
                JSONObject(acctBody)
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            if (!acctJson.optBoolean("success", false)) return Result.Err("auth")
            val accounts = acctJson.optJSONArray("result")
            val accountId = if (accounts != null && accounts.length() > 0) {
                accounts.optJSONObject(0)?.optString("id", "") ?: ""
            } else {
                ""
            }
            if (accountId.isBlank()) return Result.Err("empty")
            onStep("account")

            // 2. Subdomain (read-only: no API mints one).
            val (subCode, subBody) = call(
                "GET", "/accounts/$accountId/workers/subdomain", email, key, null, null, null
            )
            if (subCode !in 200..299) return Result.Err(failCode(subCode))
            val subResult = resultOf(subBody)
            // Cloudflare has answered with both keys over time.
            val subdomain = (subResult?.optString("subdomain", "") ?: "")
                .ifBlank { subResult?.optString("name", "") ?: "" }
            if (subdomain.isBlank()) return Result.Err("no-subdomain")
            onStep("subdomain")

            // 3. D1 database: reuse by name, else create.
            var dbId = ""
            val (dbsCode, dbsBody) = call(
                "GET", "/accounts/$accountId/d1/database?per_page=100", email, key, null, null, null
            )
            if (dbsCode in 200..299) {
                try {
                    val dbs = JSONObject(dbsBody).optJSONArray("result")
                    if (dbs != null) {
                        for (i in 0 until dbs.length()) {
                            val d = dbs.optJSONObject(i) ?: continue
                            if (d.optString("name", "") == dbName) {
                                dbId = d.optString("uuid", "")
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fall through to create.
                }
            }
            if (dbId.isBlank()) {
                val (createCode, createBody) = call(
                    "POST", "/accounts/$accountId/d1/database", email, key,
                    JSONObject().put("name", dbName), null, null
                )
                if (createCode !in 200..299) return Result.Err(failCode(createCode))
                dbId = resultOf(createBody)?.optString("uuid", "") ?: ""
                if (dbId.isBlank()) return Result.Err("empty")
            }
            onStep("d1")

            // 4. kv_store table.
            val (tableCode, tableBody) = call(
                "POST", "/accounts/$accountId/d1/database/$dbId/query", email, key,
                JSONObject().put(
                    "sql",
                    "CREATE TABLE IF NOT EXISTS kv_store (key TEXT PRIMARY KEY, value TEXT);"
                ),
                null, null
            )
            if (tableCode !in 200..299) return Result.Err(failCode(tableCode))
            try {
                if (!JSONObject(tableBody).optBoolean("success", false)) {
                    return Result.Err("http $tableCode")
                }
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            onStep("table")

            // 5. Upload the worker.
            val metadata = JSONObject()
                .put("main_module", "worker.js")
                .put("compatibility_date", "2024-09-22")
                .put("compatibility_flags", org.json.JSONArray().put("nodejs_compat"))
                .put(
                    "bindings",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("name", "IOT_DB")
                            .put("type", "d1")
                            .put("id", dbId)
                    )
                )
            val (multiBody, multiType) = multipart(metadata, script)
            val (putCode, putBody) = call(
                "PUT", "/accounts/$accountId/workers/scripts/$workerName",
                email, key, null, multiBody, multiType
            )
            if (putCode !in 200..299) return Result.Err(failCode(putCode))
            try {
                if (!JSONObject(putBody).optBoolean("success", false)) {
                    return Result.Err("auth")
                }
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            onStep("upload")

            // 6. Enable workers.dev.
            val (enableCode, enableBody) = call(
                "POST", "/accounts/$accountId/workers/scripts/$workerName/subdomain",
                email, key, JSONObject().put("enabled", true), null, null
            )
            if (enableCode !in 200..299) return Result.Err(failCode(enableCode))
            try {
                if (!JSONObject(enableBody).optBoolean("success", false)) {
                    return Result.Err("auth")
                }
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            onStep("enable")

            // 7. Verify the D1 binding.
            val (settingsCode, settingsBody) = call(
                "GET", "/accounts/$accountId/workers/scripts/$workerName/settings",
                email, key, null, null, null
            )
            if (settingsCode !in 200..299) return Result.Err(failCode(settingsCode))
            val bindings = resultOf(settingsBody)?.optJSONArray("bindings")
            var bound = false
            if (bindings != null) {
                for (i in 0 until bindings.length()) {
                    val b = bindings.optJSONObject(i) ?: continue
                    if (b.optString("name", "") == "IOT_DB" && b.optString("type", "") == "d1") {
                        bound = true
                        break
                    }
                }
            }
            if (!bound) return Result.Err("binding")
            onStep("verify")

            return Result.Ok(DeployOutcome("$workerName.$subdomain.workers.dev", workerName))
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
    }

    /**
     * Telegram WEB proxy deploy (1.101): same account / subdomain /
     * upload / enable shape as deploy(), but for the WEB proxy
     * script — compatibility 2024-09-23, no flags, one secret_text
     * PROXY_SECRET binding. No D1, no panel. Returns the worker
     * host on success. Nothing here logs or stores the email, the
     * key, the secret, or the host.
     */
    fun deployTgp(
        email: String,
        key: String,
        workerName: String,
        secret: String,
        script: ByteArray
    ): Result<String> {
        try {
            val (acctCode, acctBody) = call("GET", "/accounts?per_page=50", email, key, null, null, null)
            if (acctCode !in 200..299) return Result.Err(failCode(acctCode))
            val acctJson = try {
                JSONObject(acctBody)
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            if (!acctJson.optBoolean("success", false)) return Result.Err("auth")
            val accounts = acctJson.optJSONArray("result")
            val accountId = if (accounts != null && accounts.length() > 0) {
                accounts.optJSONObject(0)?.optString("id", "") ?: ""
            } else {
                ""
            }
            if (accountId.isBlank()) return Result.Err("empty")
            val (subCode, subBody) = call(
                "GET", "/accounts/$accountId/workers/subdomain", email, key, null, null, null
            )
            if (subCode !in 200..299) return Result.Err(failCode(subCode))
            val subResult = resultOf(subBody)
            val subdomain = (subResult?.optString("subdomain", "") ?: "")
                .ifBlank { subResult?.optString("name", "") ?: "" }
            if (subdomain.isBlank()) return Result.Err("no-subdomain")
            val metadata = JSONObject()
                .put("main_module", "worker.js")
                .put("compatibility_date", "2024-09-23")
                .put("compatibility_flags", org.json.JSONArray())
                .put(
                    "bindings",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("name", "PROXY_SECRET")
                            .put("type", "secret_text")
                            .put("text", secret)
                    )
                )
            val (multiBody, multiType) = multipart(metadata, script)
            val (putCode, putBody) = call(
                "PUT", "/accounts/$accountId/workers/scripts/$workerName",
                email, key, null, multiBody, multiType
            )
            if (putCode !in 200..299) return Result.Err(failCode(putCode))
            try {
                if (!JSONObject(putBody).optBoolean("success", false)) {
                    return Result.Err("auth")
                }
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            val (enableCode, enableBody) = call(
                "POST", "/accounts/$accountId/workers/scripts/$workerName/subdomain",
                email, key, JSONObject().put("enabled", true), null, null
            )
            if (enableCode !in 200..299) return Result.Err(failCode(enableCode))
            try {
                if (!JSONObject(enableBody).optBoolean("success", false)) {
                    return Result.Err("auth")
                }
            } catch (_: Exception) {
                return Result.Err("parse")
            }
            val (settingsCode, settingsBody) = call(
                "GET", "/accounts/$accountId/workers/scripts/$workerName/settings",
                email, key, null, null, null
            )
            if (settingsCode !in 200..299) return Result.Err(failCode(settingsCode))
            val bindings = resultOf(settingsBody)?.optJSONArray("bindings")
            var bound = false
            if (bindings != null) {
                for (i in 0 until bindings.length()) {
                    val b = bindings.optJSONObject(i) ?: continue
                    if (b.optString("name", "") == "PROXY_SECRET" &&
                        b.optString("type", "") == "secret_text"
                    ) {
                        bound = true
                        break
                    }
                }
            }
            if (!bound) return Result.Err("binding")
            return Result.Ok("$workerName.$subdomain.workers.dev")
        } catch (e: Exception) {
            return Result.Err(fail(e))
        }
    }

    /** Best-effort worker delete: used when a rebuilt proxy replaces
     *  an older one, or a failed bridge test must not leave a
     *  useless worker behind. Never throws. */
    fun deleteWorker(email: String, key: String, workerName: String): Boolean {
        return try {
            val (acctCode, acctBody) = call("GET", "/accounts?per_page=50", email, key, null, null, null)
            if (acctCode !in 200..299) return false
            val acctJson = try {
                JSONObject(acctBody)
            } catch (_: Exception) {
                return false
            }
            if (!acctJson.optBoolean("success", false)) return false
            val accounts = acctJson.optJSONArray("result")
            val accountId = if (accounts != null && accounts.length() > 0) {
                accounts.optJSONObject(0)?.optString("id", "") ?: ""
            } else {
                ""
            }
            if (accountId.isBlank()) return false
            val (code, _) = call(
                "DELETE", "/accounts/$accountId/workers/scripts/$workerName",
                email, key, null, null, null
            )
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** Cheap Global-auth health check: the first accounts page must
     *  succeed. Used by the guided Cloudflare section before saving. */
    fun health(email: String, key: String): Result<Unit> {
        return try {
            val (code, body) = call("GET", "/accounts?per_page=1", email, key, null, null, null)
            if (code !in 200..299) Result.Err(failCode(code))
            else if (JSONObject(body).optBoolean("success", false)) Result.Ok(Unit)
            else Result.Err("auth")
        } catch (e: Exception) {
            Result.Err(fail(e))
        }
    }

    /** Plain-language reason for a deploy Err code, FA/EN pair. */
    fun reasonFaEn(code: String): Pair<String, String> {
        return when {
            code == "auth" ->
                "ایمیل یا کلید گلوبال درست نیست" to
                "Wrong account email or Global key"
            code == "no-subdomain" ->
                "این حساب هنوز ساب‌دامین workers.dev ندارد؛ یک بار از داشبورد کلودفلر یک نام برایش انتخاب کنید، بعد دوباره «ساخت پنل» را بزنید" to
                "This account has no workers.dev subdomain yet; pick a name for it once in the Cloudflare dashboard, then tap Build panel again"
            code == "binding" ->
                "اتصال دیتابیس به ورکر تأیید نشد" to
                "The database binding to the worker was not confirmed"
            code.startsWith("http ") ->
                "سرور کلودفلر کد ${code.removePrefix("http ")} برگرداند" to
                "Cloudflare returned code ${code.removePrefix("http ")}"
            code == "timeout" ->
                "در ۹۰ ثانیه جواب نداد (تایم‌اوت)" to
                "No answer within 90 seconds (timeout)"
            code == "network" ->
                "خطای شبکه (کلودفلر یا اینترنت در دسترس نیست)" to
                "Network error (Cloudflare or internet unreachable)"
            code == "empty" ->
                "پاسخ خالی برگشت" to
                "The response was empty"
            code == "parse" ->
                "پاسخ کلودفلر قابل خواندن نبود" to
                "Cloudflare's response could not be read"
            else -> code to code
        }
    }
}
