package com.renamer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Automatic end-to-end encryption for the Alien Messenger (2.64).
 *
 *  Design (no extra steps for the user, fully automatic):
 *  - One random 32-byte AES-GCM key per chat PER SIDE, generated
 *    on-device. Each side encrypts its own messages with its own
 *    key, and decrypts the partner's messages with the partner's
 *    key (received inside the partner link's fragment).
 *  - The key travels ONLY inside the shared link's fragment
 *    ("#k=..."). A URL fragment is never sent to the server, so the
 *    paste host never sees it, and the host stores ciphertext only.
 *  - Messages are stored on the host as "E2E|<base64(iv||ciphertext)>"
 *    (fresh random 12-byte IV per message). Old alien-only links
 *    already sent stay as-is; they cannot be retroactively encrypted.
 *  - On-device both keys are wrapped with a master key held in
 *    the Android Keystore and stored in private prefs
 *    ("renamer_alien_e2e", "k_<chatId>" = own key, "pk_<chatId>" =
 *    partner key); a plaintext key never touches disk or the network.
 *  - Server-side expiry (90 days) and delete-on-chat-delete already
 *    exist; the host has no read-receipt API, so no new server call
 *    is possible.
 *  - If a key is unavailable (Keystore error, or the partner has
 *    not re-entered a fresh link yet), messages stay readable only
 *    as alien-encoded text — the app degrades to the old behaviour
 *    instead of dropping the message, and shows a placeholder for
 *    undecryptable messages. */
object AlienE2e {

    const val PREFIX = "E2E|"
    const val FRAG = "#k="

    private const val PREFS = "renamer_alien_e2e"
    private const val MASTER_ALIAS = "alien_e2e_master"
    private const val KEY_PREFIX = "k_"
    private const val PARTNER_KEY_PREFIX = "pk_"
    private const val SETUP_PREFIX = "setup_"
    private const val HINT_PREFIX = "hint_"
    private const val FAIL_PREFIX = "fail_"

    // ------------------------------------------------------------------
    // Android Keystore master key (AES/GCM, no user authentication).
    // ------------------------------------------------------------------

    private fun masterKey(createIfMissing: Boolean): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(MASTER_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            if (!createIfMissing) return null
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            gen.init(spec)
            gen.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapKey(raw: ByteArray): String? {
        return try {
            val master = masterKey(true) ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, master)
            val iv = c.iv // 12 bytes for GCM
            val ct = c.doFinal(raw)
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun unwrapKey(wrapped: String): ByteArray? {
        return try {
            val master = masterKey(false) ?: return null
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            if (blob.size <= 12) return null
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, iv))
            c.doFinal(ct)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Per-chat keys.
    // ------------------------------------------------------------------

    /** The stored key for a chat, or null when there is none / it
     *  cannot be unwrapped. */
    fun keyFor(ctx: Context, chatId: String): ByteArray? {
        return try {
            val wrapped = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + chatId, null) ?: return null
            unwrapKey(wrapped)
        } catch (_: Exception) {
            null
        }
    }

    /** The partner's key (from their link's fragment), or null when
     *  the partner has not shared a 2.64 link yet. */
    fun partnerKeyFor(ctx: Context, chatId: String): ByteArray? {
        return try {
            val wrapped = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PARTNER_KEY_PREFIX + chatId, null) ?: return null
            unwrapKey(wrapped)
        } catch (_: Exception) {
            null
        }
    }

    /** Stores the partner's key, received from their link. */
    fun storePartnerKey(ctx: Context, chatId: String, raw: ByteArray): Boolean {
        if (raw.size != 32) return false
        val wrapped = wrapKey(raw) ?: return false
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PARTNER_KEY_PREFIX + chatId, wrapped)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasKey(ctx: Context, chatId: String): Boolean = keyFor(ctx, chatId) != null

    /** Returns the chat's key, generating and storing a fresh one
     *  when needed. Null when the Keystore is unavailable. */
    fun getOrCreateKey(ctx: Context, chatId: String, atSetup: Boolean): ByteArray? {
        keyFor(ctx, chatId)?.let { return it }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return if (storeKey(ctx, chatId, raw, atSetup)) raw else null
    }

    /** Stores a key received from a partner link. */
    fun storeKey(ctx: Context, chatId: String, raw: ByteArray, atSetup: Boolean): Boolean {
        if (raw.size != 32) return false
        val wrapped = wrapKey(raw) ?: return false
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREFIX + chatId, wrapped)
                .putBoolean(SETUP_PREFIX + chatId, atSetup)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** True when this chat's key was created while the chat was still
     *  in setup (so the partner received the key inside the link). */
    fun wasKeyAtSetup(ctx: Context, chatId: String): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(SETUP_PREFIX + chatId, false)
        } catch (_: Exception) {
            false
        }
    }

    fun hintShown(ctx: Context, chatId: String): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(HINT_PREFIX + chatId, false)
        } catch (_: Exception) {
            true
        }
    }

    fun markHintShown(ctx: Context, chatId: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(HINT_PREFIX + chatId, true).apply()
        } catch (_: Exception) {}
    }

    fun failHintShown(ctx: Context, chatId: String): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(FAIL_PREFIX + chatId, false)
        } catch (_: Exception) {
            true
        }
    }

    fun markFailHintShown(ctx: Context, chatId: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(FAIL_PREFIX + chatId, true).apply()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // Message crypto. "stored" = the text as it sits on the host
    // (optionally "R<ts>|" reply-prefixed, see AlienChats.parseReply).
    // ------------------------------------------------------------------

    /** Encrypt one message's inner text. Returns the stored form
     *  ("E2E|<base64>") or the plain text when there is no key. */
    fun encryptText(key: ByteArray?, text: String): String {
        if (key == null) return text
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            text
        }
    }

    private fun decryptInner(key: ByteArray, inner: String): String? {
        if (!inner.startsWith(PREFIX)) return inner
        return try {
            val blob = Base64.decode(inner.substring(PREFIX.length), Base64.NO_WRAP)
            if (blob.size <= 12) return null
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Decrypt a stored message line (reply prefix preserved).
     *  Plain (pre-E2E) text is returned as-is. Null when the payload
     *  is E2E but the key is missing or wrong. */
    fun decryptStored(key: ByteArray?, stored: String): String? {
        val (replyTo, inner) = AlienChats.parseReply(stored)
        if (!inner.startsWith(PREFIX)) return stored
        if (key == null) return null
        val plain = decryptInner(key, inner) ?: return null
        return if (replyTo != null) "R$replyTo|$plain" else plain
    }

    /** Display text for a stored message, with a placeholder when it
     *  cannot be decrypted yet (partner has not re-entered the link
     *  carrying the key). My messages use my key, the partner's
     *  messages use the partner's key. */
    fun displayStored(ctx: Context, chatId: String, stored: String, mine: Boolean): String {
        val key = if (mine) keyFor(ctx, chatId) else partnerKeyFor(ctx, chatId)
        val dec = decryptStored(key, stored)
        if (dec != null) return AlienChats.parseReply(dec).second
        return AppLanguage.t(ctx,
            "🔒 پیام رمز شده — لینک تازه را دوباره وارد کن",
            "🔒 Encrypted message — re-enter the fresh link")
    }

    /** The chat-list preview is stored as "e2eM|<text>" (my message)
     *  or "e2eP|<text>" (partner's message) so the right key is used
     *  at display time. Pre-2.64 previews carry no marker and are
     *  plain text, shown as-is. */
    fun displayPreview(ctx: Context, chatId: String, preview: String): String {
        return when {
            preview.startsWith("e2eM|") ->
                displayStored(ctx, chatId, preview.substring(5), mine = true)
            preview.startsWith("e2eP|") ->
                displayStored(ctx, chatId, preview.substring(5), mine = false)
            else -> preview
        }
    }

    /** Prefix a stored preview with its owner marker (see above). */
    fun previewWithOwner(stored: String, mine: Boolean): String =
        (if (mine) "e2eM|" else "e2eP|") + stored

    // ------------------------------------------------------------------
    // Key transport inside the shared link fragment.
    // ------------------------------------------------------------------

    /** Append "#k=<base64url key>" to every part of a (possibly
     *  mirrored) view link. Fragments are never sent to the server. */
    fun linkWithKey(viewUrl: String, key: ByteArray?): String {
        if (key == null) return viewUrl
        val frag = FRAG + Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP)
        return viewUrl.split("|").joinToString("|") { part ->
            if (part.contains(FRAG)) part else part + frag
        }
    }

    /** Remove "#k=..." from every part of a raw link, so the stored
     *  view/manage links stay clean. */
    fun stripKeyFragment(rawLink: String): String {
        return rawLink.split("|").joinToString("|") { part ->
            val i = part.indexOf(FRAG)
            if (i < 0) part else part.substring(0, i)
        }
    }

    /** Extract the 32-byte key from an alien-encoded link the partner
     *  pasted, or null when the link carries none. */
    fun keyFromAlien(alienText: String): ByteArray? {
        val raw = try {
            AlienSub.parseInput(alienText)
        } catch (_: Exception) {
            null
        } ?: return null
        for (part in raw.split("|")) {
            val i = part.indexOf(FRAG)
            if (i < 0) continue
            val b64 = part.substring(i + FRAG.length).trim()
            if (b64.isBlank()) continue
            try {
                val k = Base64.decode(b64, Base64.URL_SAFE)
                if (k.size == 32) return k
            } catch (_: Exception) {
            }
        }
        return null
    }
}
