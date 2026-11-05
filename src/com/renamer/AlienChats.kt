package com.renamer

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One stored messenger conversation (2.28).
 *  The app keeps every conversation's links
 *  itself, so the user can always return to an
 *  older chat. A conversation disappears only
 *  when the user deletes it from the list. */
data class AlienChat(
    var id: String,
    var myName: String = "",
    var partnerName: String = "",
    var ownView: String = "",
    var ownManage: String = "",
    var partnerView: String = "",
    var myClearTs: Long = 0L,
    var appliedClearTs: Long = 0L,
    var blockedByMe: Boolean = false,
    var blockedMe: Boolean = false,
    var lastPreview: String = "",
    var lastTs: Long = 0L
)

/** What one link's content means, after
 *  decoding it inside the app. Every stored
 *  line is fully alien-encoded — name, clear
 *  and block markers included — so a link
 *  opened outside the app shows nothing
 *  readable at all. Only "time|text" lines
 *  are messages; anything without a "|" is
 *  ignored here (for example an old one-way
 *  sender text on a reused link). */
data class ParsedSub(
    val name: String?,
    val clearTs: Long?,
    val blocked: Boolean,
    val seenTs: Long?,
    val messages: List<Pair<Long, String>>,
    val nameDropped: Boolean = false
)

/** one lock for every read-modify-write
 *  on a message link (send, queue flush,
 *  poll, SEEN writes, edit, delete, block).
 *  Held only on background threads, never
 *  across a UI wait; UI updates are posted
 *  with runOnUiThread outside the lock. */
object MsgrLinkLock {
    val lock = Any()
}

/** tiny atomic file store shared by the
 *  messenger lists. A save writes a temp file
 *  and renames it over the main file, so a
 *  crash mid-save can never leave a
 *  half-written list. The previous file that
 *  still parses is kept as .bak. */
object MsgrStore {
    fun file(ctx: Context, base: String, suffix: String) =
        File(ctx.filesDir, base + suffix)

    fun read(ctx: Context, base: String, suffix: String): String? = try {
        val f = file(ctx, base, suffix)
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    fun parseArray(text: String): JSONArray? = try {
        JSONArray(text)
    } catch (_: Exception) { null }

    fun writeAtomic(ctx: Context, base: String, text: String) {
        try {
            val dir = ctx.filesDir
            val tmp = File(dir, "$base.tmp")
            val main = File(dir, base)
            val bak = File(dir, "$base.bak")
            tmp.writeText(text)
            val oldText = read(ctx, base, "")
            if (oldText != null && parseArray(oldText) != null) {
                try { main.copyTo(bak, overwrite = true) } catch (_: Exception) {}
            }
            if (!tmp.renameTo(main)) {
                try { tmp.copyTo(main, overwrite = true) } catch (_: Exception) {}
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

object AlienChats {
    private const val PREFS = "renamer_alien_msgr"
    private const val KEY = "chats"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Atomic local storage ----
    // The list lives in a file, not in prefs:
    // every save writes a temp file and renames
    // it over the main file, so a crash can
    // never leave a half-written list. The
    // previous good file is kept as .bak, and
    // the old prefs value stays as a one-way
    // legacy fallback. A corrupt file never
    // yields an emptied list: load falls back
    // to the last good in-memory copy, then to
    // .bak, then to legacy prefs.
    private const val FILE_BASE = "alien_chats.json"
    private var lastGoodChats: List<AlienChat>? = null
    private var bakFallbackLogged = false

    private fun parseChats(text: String): List<AlienChat>? {
        val arr = MsgrStore.parseArray(text) ?: return null
        val out = mutableListOf<AlienChat>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AlienChat(
                id = o.optString("id"),
                myName = o.optString("myName"),
                partnerName = o.optString("partnerName"),
                ownView = o.optString("ownView"),
                ownManage = o.optString("ownManage"),
                partnerView = o.optString("partnerView"),
                myClearTs = o.optLong("myClearTs"),
                appliedClearTs = o.optLong("appliedClearTs"),
                blockedByMe = o.optBoolean("blockedByMe"),
                blockedMe = o.optBoolean("blockedMe"),
                lastPreview = o.optString("lastPreview"),
                lastTs = o.optLong("lastTs")
            )
        }
        return out
    }

    private fun chatsToJson(list: List<AlienChat>): String {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("myName", c.myName)
            o.put("partnerName", c.partnerName)
            o.put("ownView", c.ownView)
            o.put("ownManage", c.ownManage)
            o.put("partnerView", c.partnerView)
            o.put("myClearTs", c.myClearTs)
            o.put("appliedClearTs", c.appliedClearTs)
            o.put("blockedByMe", c.blockedByMe)
            o.put("blockedMe", c.blockedMe)
            o.put("lastPreview", c.lastPreview)
            o.put("lastTs", c.lastTs)
            arr.put(o)
        }
        return arr.toString()
    }

    fun load(ctx: Context): MutableList<AlienChat> {
        val fromFile = MsgrStore.read(ctx, FILE_BASE, "")
        if (fromFile != null) {
            val parsed = parseChats(fromFile)
            if (parsed != null) {
                lastGoodChats = parsed
                return parsed.toMutableList()
            }
        }
        val fromBak = MsgrStore.read(ctx, FILE_BASE, ".bak")
        if (fromBak != null) {
            val parsed = parseChats(fromBak)
            if (parsed != null) {
                lastGoodChats = parsed
                // Self-heal: restore the main
                // file from the good backup.
                try { MsgrStore.writeAtomic(ctx, FILE_BASE, fromBak) } catch (_: Exception) {}
                if (!bakFallbackLogged) {
                    bakFallbackLogged = true
                    try {  } catch (_: Exception) {}
                }
                return parsed.toMutableList()
            }
        }
        val legacy = try { prefs(ctx).getString(KEY, null) } catch (_: Exception) { null }
        if (legacy != null) {
            val parsed = parseChats(legacy)
            if (parsed != null) {
                lastGoodChats = parsed
                // One-way migration: move the
                // legacy prefs list into the file.
                try { MsgrStore.writeAtomic(ctx, FILE_BASE, legacy) } catch (_: Exception) {}
                return parsed.toMutableList()
            }
        }
        return lastGoodChats?.toMutableList() ?: mutableListOf()
    }

    fun save(ctx: Context, list: List<AlienChat>) {
        lastGoodChats = list.toList()
        try { MsgrStore.writeAtomic(ctx, FILE_BASE, chatsToJson(list)) } catch (_: Exception) {}
    }

    fun find(ctx: Context, id: String): AlienChat? =
        load(ctx).firstOrNull { it.id == id }

    fun add(ctx: Context, chat: AlienChat) {
        val list = load(ctx)
        list.removeAll { it.id == chat.id }
        list.add(0, chat)
        save(ctx, list)
    }

    fun update(ctx: Context, chat: AlienChat) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == chat.id }
        if (i >= 0) list[i] = chat else list.add(0, chat)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    fun moveToTop(ctx: Context, id: String) {
        val list = load(ctx)
        val c = list.firstOrNull { it.id == id } ?: return
        list.remove(c)
        list.add(0, c)
        save(ctx, list)
    }

    // ---- Link-content protocol ----

    fun nameLine(name: String) = SecretLanguage.encode("NAME|$name")
    fun clearLine(ts: Long) = SecretLanguage.encode("CLEARALL|$ts")
    fun blockedLine() = SecretLanguage.encode("BLOCKED")
    fun msgLine(ts: Long, text: String) = SecretLanguage.encode("$ts|$text")
    fun msgLineReply(ts: Long, replyTo: Long, text: String) =
        SecretLanguage.encode("$ts|R$replyTo|$text")
    fun seenLine(ts: Long) = SecretLanguage.encode("SEEN|$ts")

    /** Splits a stored message second-part into
     *  (replyTo, displayText). A reply line stores
     *  "R<origTs>|text"; anything else is a plain
     *  message. Old "ts|text" lines parse as
     *  (null, text), so old chats keep working. */
    fun parseReply(text: String): Pair<Long?, String> {
        if (!text.startsWith("R")) return null to text
        val bar = text.indexOf("|")
        if (bar <= 1) return null to text
        val orig = text.substring(1, bar).toLongOrNull() ?: return null to text
        return orig to text.substring(bar + 1)
    }

    fun parseSub(content: String?): ParsedSub {
        if (content.isNullOrBlank()) return ParsedSub(null, null, false, null, emptyList())
        var name: String? = null
        var nameDropped = false
        var clearTs: Long? = null
        var blocked = false
        var seenTs: Long? = null
        val msgs = mutableListOf<Pair<Long, String>>()
        for (line in content.lines()) {
            if (line.isBlank()) continue
            val dec = try { SecretLanguage.decode(line) } catch (_: Exception) { "" }
            if (dec.isBlank()) continue
            when {
                dec.startsWith("SEEN|") -> {
                    val ts = dec.substring(5).toLongOrNull()
                    if (ts != null && (seenTs == null || ts > seenTs!!)) seenTs = ts
                }
                dec == "BLOCKED" -> blocked = true
                dec.startsWith("NAME|") -> {
                    // the NAME line goes
                    // through the same
                    // site-reveal filter as
                    // message text, so a
                    // site-like name can never
                    // surface in a header, a
                    // list card or a quote.
                    val n = dec.substring(5)
                    if (AlienSub.revealsSite(n)) nameDropped = true else name = n
                }
                dec.startsWith("CLEARALL|") -> {
                    val ts = dec.substring(9).toLongOrNull()
                    if (ts != null && (clearTs == null || ts > clearTs!!)) clearTs = ts
                }
                else -> {
                    val bar = dec.indexOf("|")
                    if (bar > 0) {
                        val ts = dec.substring(0, bar).toLongOrNull() ?: continue
                        val text = dec.substring(bar + 1)
                        if (text.isNotBlank() && !AlienSub.revealsSite(text)) msgs += ts to text
                    }
                }
            }
        }
        return ParsedSub(name, clearTs, blocked, seenTs, msgs, nameDropped)
    }

    /** Rebuilds my link's content from parsed
     *  parts: name line, my clear line, block
     *  line, then the message lines (already
     *  alien-encoded). */
    fun buildOwnContent(
        name: String?,
        myClearTs: Long,
        blocked: Boolean,
        msgLines: List<String>,
        seenTs: Long = 0L
    ): String {
        val parts = mutableListOf<String>()
        if (!name.isNullOrBlank()) parts += nameLine(name)
        if (seenTs > 0) parts += seenLine(seenTs)
        if (myClearTs > 0) parts += clearLine(myClearTs)
        if (blocked) parts += blockedLine()
        parts += msgLines
        return parts.joinToString("\n")
    }

    /** Writes my name into my link. Fresh links
     *  get just the name line; links that only
     *  hold plain old text keep that text and
     *  gain the name line at the end; links
     *  that already carry chat markers are
     *  rebuilt with the new name. */
    fun ensureNameLine(manage: String, name: String, mode: String = "auto"): Boolean {
        // serialized with every other
        // link read-modify-write.
        synchronized(MsgrLinkLock.lock) {
            return try {
            val content = ShzClient.fetchAny(viewOf(manage)) ?: return false
            val parsed = AlienChats.parseSub(content)
            val hasMarkers = parsed.name != null || parsed.clearTs != null ||
                parsed.blocked || parsed.messages.isNotEmpty()
            val next = if (content.isBlank()) {
                nameLine(name)
            } else if (hasMarkers) {
                buildOwnContent(
                    name,
                    parsed.clearTs ?: 0L,
                    parsed.blocked,
                    parsed.messages.map { msgLine(it.first, it.second) },
                    parsed.seenTs ?: 0L
                )
            } else {
                content.trimEnd() + "\n" + nameLine(name)
            }
            ShzClient.updateForMode(mode, manage, next)
            } catch (_: Exception) { false }
        }
    }

    /** The view link(s) that belong to a manage
     *  link (or bundle): everything before the
     *  password colon after each host. */
    fun viewOf(manage: String): String {
        return ShzClient.viewOfAny(manage)
    }

    /** deletes every part of a (possibly
     *  mirrored) manage bundle, one part at a
     *  time. True only when EVERY part reports
     *  success, so the overall result reflects
     *  reality instead of one lucky part. */
    fun deleteRemoteAll(manageBundle: String): Boolean {
        val parts = ShzClient.splitBundle(manageBundle)
        if (parts.isEmpty()) return false
        var all = true
        for (p in parts) {
            val ok = try { ShzClient.deleteAny(p) } catch (_: Exception) { false }
            if (!ok) all = false
        }
        return all
    }

    /** Splits an entered link (alien form) into
     *  view + manage. Returns null when the
     *  text is not a message link at all. */
    fun splitEntered(alienText: String): Pair<String, String>? {
        val raw = AlienSub.parseInput(alienText) ?: return null
        val parts = ShzClient.splitBundle(raw)
        if (parts.isEmpty()) return null
        val views = parts.map { part ->
            val he = ShzClient.hostEnd(part)
            if (he < 0) return null
            val colon = part.indexOf(":", he + 1)
            if (colon > 0) part.substring(0, colon) else part
        }
        val manages = parts.map { part ->
            val he = ShzClient.hostEnd(part)
            val colon = part.indexOf(":", he + 1)
            if (colon > 0) part else ""
        }
        return views.joinToString("|") to manages.joinToString("|")
    }
}


/** Offline send queue (1.5): messages whose
 *  send failed (no internet / host unreachable)
 *  are kept here per conversation, in order,
 *  until a later chat open / poll / retry tap
 *  sends them. The stored text is the message
 *  line's second part, so a reply keeps its
 *  "R<origTs>|" prefix. Nothing here is shown
 *  to the user as a link or an address. */
object OfflineQueue {
    private const val PREFS = "renamer_alien_queue"
    private const val KEY = "queue"

    data class Item(val chatId: String, val ts: Long, val text: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // same atomic file storage as the
    // chat list (temp file + rename, .bak,
    // legacy prefs fallback, last-good cache).
    private const val FILE_BASE = "alien_queue.json"
    private var lastGoodItems: List<Item>? = null
    private var bakFallbackLogged = false

    private fun parseItems(text: String): List<Item>? {
        val arr = MsgrStore.parseArray(text) ?: return null
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Item(o.optString("chatId"), o.optLong("ts"), o.optString("text"))
        }
        return out
    }

    private fun itemsToJson(list: List<Item>): String {
        val arr = JSONArray()
        for (it in list) {
            val o = JSONObject()
            o.put("chatId", it.chatId)
            o.put("ts", it.ts)
            o.put("text", it.text)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun loadAll(ctx: Context): MutableList<Item> {
        val fromFile = MsgrStore.read(ctx, FILE_BASE, "")
        if (fromFile != null) {
            val parsed = parseItems(fromFile)
            if (parsed != null) {
                lastGoodItems = parsed
                return parsed.toMutableList()
            }
        }
        val fromBak = MsgrStore.read(ctx, FILE_BASE, ".bak")
        if (fromBak != null) {
            val parsed = parseItems(fromBak)
            if (parsed != null) {
                lastGoodItems = parsed
                try { MsgrStore.writeAtomic(ctx, FILE_BASE, fromBak) } catch (_: Exception) {}
                if (!bakFallbackLogged) {
                    bakFallbackLogged = true
                    try {  } catch (_: Exception) {}
                }
                return parsed.toMutableList()
            }
        }
        val legacy = try { prefs(ctx).getString(KEY, null) } catch (_: Exception) { null }
        if (legacy != null) {
            val parsed = parseItems(legacy)
            if (parsed != null) {
                lastGoodItems = parsed
                try { MsgrStore.writeAtomic(ctx, FILE_BASE, legacy) } catch (_: Exception) {}
                return parsed.toMutableList()
            }
        }
        return lastGoodItems?.toMutableList() ?: mutableListOf()
    }

    private fun saveAll(ctx: Context, list: List<Item>) {
        lastGoodItems = list.toList()
        try { MsgrStore.writeAtomic(ctx, FILE_BASE, itemsToJson(list)) } catch (_: Exception) {}
    }

    fun listFor(ctx: Context, chatId: String): MutableList<Item> =
        loadAll(ctx).filter { it.chatId == chatId }.toMutableList()

    fun add(ctx: Context, item: Item) {
        val all = loadAll(ctx)
        all.removeAll { it.chatId == item.chatId && it.ts == item.ts }
        all += item
        saveAll(ctx, all)
    }

    fun remove(ctx: Context, chatId: String, ts: Long) {
        val all = loadAll(ctx)
        if (all.removeAll { it.chatId == chatId && it.ts == ts }) saveAll(ctx, all)
    }

    fun updateText(ctx: Context, chatId: String, ts: Long, text: String): Boolean {
        val all = loadAll(ctx)
        val i = all.indexOfFirst { it.chatId == chatId && it.ts == ts }
        if (i < 0) return false
        all[i] = all[i].copy(text = text)
        saveAll(ctx, all)
        return true
    }
}
