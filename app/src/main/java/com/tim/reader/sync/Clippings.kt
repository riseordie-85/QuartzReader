package com.tim.reader.sync

import com.tim.reader.ResultsLog
import com.tim.reader.data.HighlightRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/*
 * Highlights and notes, shared with CrossPoint devices through the CrossPoint sync server, which
 * calls them clippings. Each clipping has an ID, the chapter's number in the book's full spine,
 * the words, and where they sit in that chapter as a count of letters. A highlight keeps the same
 * ID on every device, so nothing needs mapping.
 */

/** Where a highlight sits, the way CrossPoint saves it. Offsets count characters, not UTF-16 units. */
data class ClipPlace(val spine: Int, val start: Int?, val end: Int?, val chapter: String)

/** All of a chapter's text as the page shows it, without the head, styles or scripts. */
fun visibleText(markup: Sequence<Markup>): String {
    val text = StringBuilder()
    var inBody = false
    var depth = 0
    var bodyDepth = -1
    var hiddenDepth = 0
    val hidden = setOf("head", "style", "script", "title", "rp")
    for (piece in markup) {
        when (piece) {
            is Markup.Open -> {
                depth++
                val name = piece.name.substringAfter(':').lowercase()
                if (!inBody) {
                    if (name == "body") {
                        inBody = true
                        bodyDepth = depth
                    }
                } else if (hiddenDepth > 0 || name in hidden) {
                    hiddenDepth++
                }
            }
            Markup.Close -> {
                if (inBody && depth == bodyDepth) inBody = false else if (hiddenDepth > 0) hiddenDepth--
                depth--
            }
            is Markup.Text -> if (inBody && hiddenDepth == 0) text.append(piece.text)
        }
    }
    return text.toString()
}

/** Where a CrossPoint character count lands in a Kotlin string. */
fun utf16Index(text: String, codepoints: Int): Int? {
    if (codepoints < 0) return null
    return runCatching { text.offsetByCodePoints(0, codepoints) }.getOrNull()
}

/** How many characters, as CrossPoint counts them, come before a place in a Kotlin string. */
fun codepointIndex(text: String, utf16: Int): Int = text.codePointCount(0, utf16.coerceIn(0, text.length))

/**
 * Finds a highlight's words in a chapter. The words around it settle which copy when a phrase
 * comes up more than once, and failing that, the one nearest [near], a fraction of the chapter.
 */
fun findQuote(text: String, quote: String, before: String, after: String, near: Double?): IntRange? {
    if (quote.isEmpty()) return null
    val hits = ArrayList<Int>()
    var from = text.indexOf(quote)
    while (from >= 0 && hits.size < 200) {
        hits += from
        from = text.indexOf(quote, from + 1)
    }
    if (hits.isEmpty()) return null
    fun score(at: Int): Int {
        var matched = 0
        // Letters agreeing just before and just after the words count toward this copy.
        while (matched < before.length && at - matched - 1 >= 0 && text[at - matched - 1] == before[before.length - matched - 1]) matched++
        var later = 0
        val end = at + quote.length
        while (later < after.length && end + later < text.length && text[end + later] == after[later]) later++
        return matched + later
    }
    val target = near?.let { (it * text.length).toInt() }
    val best = hits.maxWithOrNull(
        compareBy<Int> { score(it) }.thenByDescending { at -> target?.let { kotlin.math.abs(at - it) } ?: at },
    ) ?: return null
    return best until best + quote.length
}

/** Shortens text to what CrossPoint keeps, without cutting a character in half. */
fun clipText(text: String, maxBytes: Int = 4000): String {
    if (text.toByteArray().size <= maxBytes) return text
    val out = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        val size = String(Character.toChars(point)).toByteArray().size
        if (bytes + size > maxBytes) break
        out.appendCodePoint(point)
        bytes += size
        i += Character.charCount(point)
    }
    return out.toString()
}

/** What sync does to the reader's highlights, kept apart so it can be tried without a phone. */
interface HighlightBook {
    suspend fun records(): List<HighlightRecord>
    suspend fun add(id: String, locator: String, text: String, note: String, createdAt: Long)
    suspend fun setNote(id: String, note: String)
    suspend fun remove(id: String)
    /** Where a saved highlight sits, for sending. Null when its chapter can't be found. */
    suspend fun placeOf(record: HighlightRecord): ClipPlace?
    /** A saved locator for a highlight from another device, or null when it can't be placed. */
    suspend fun locatorFor(spine: Int, start: Int?, end: Int?, text: String): String?
}

/** What's been sent or seen for each highlight, so only changes go out. Kept per book. */
interface ClipMemory {
    fun cursor(document: String): Long
    fun sent(document: String): MutableMap<String, String>
    fun save(document: String, cursor: Long, sent: Map<String, String>)
}

/** The state of a highlight on the server: removed, or there with this note. */
private fun signature(removed: Boolean, note: String?) = if (removed) "x" else "n:" + md5Hex(note.orEmpty().toByteArray())

class ClippingSync(
    private val book: HighlightBook,
    private val memory: ClipMemory,
    private val document: suspend () -> String?,
    private val tell: (String) -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var unsupported = false

    /** Fetches highlights from other devices, then sends this device's changes. */
    suspend fun run(manual: Boolean) = lock.withLock {
        if (!CrossPointSettings.isReady || !CrossPointSettings.syncHighlights) return@withLock
        if (unsupported && !manual) return@withLock
        val doc = document() ?: return@withLock
        val base = CrossPointSettings.baseUrl
        val user = CrossPointSettings.username
        val key = CrossPointSettings.key
        try {
            var cursor = memory.cursor(doc)
            val sent = memory.sent(doc)
            val local = book.records().associateBy { it.id }
            var added = 0
            while (true) {
                val page = KoSyncClient.fetchClippings(base, user, key, doc, cursor) ?: run {
                    unsupported = true
                    if (manual) tell("This sync server doesn't keep highlights. CrossPoint's does.")
                    return@withLock
                }
                for (item in page.items) {
                    if (applyRemote(item, local, sent)) added++
                }
                if (page.cursor <= cursor && page.more) break
                cursor = page.cursor
                if (!page.more) break
            }
            unsupported = false
            memory.save(doc, cursor, sent)

            // Changes made here: new highlights, edited notes and removals not yet on the server.
            val outgoing = ArrayList<Pair<String, JSONObject>>()
            for (record in book.records()) {
                val state = signature(record.removed, record.note)
                if (sent[record.id] == state) continue
                if (record.removed) {
                    // A highlight removed before it was ever sent has nothing to remove there.
                    if (sent.containsKey(record.id)) outgoing += state to JSONObject().put("id", record.id).put("deleted", 1)
                    else sent[record.id] = state
                    continue
                }
                val item = encode(record) ?: continue
                outgoing += state to item
            }
            for (batch in outgoing.chunked(50)) {
                KoSyncClient.pushClippings(base, user, key, doc, JSONArray(batch.map { it.second }))
                batch.forEach { (state, item) -> sent[item.getString("id")] = state }
                memory.save(doc, cursor, sent)
            }
            if (manual && added > 0) tell(if (added == 1) "Added a highlight from another device" else "Added $added highlights from other devices")
        } catch (error: SyncException) {
            ResultsLog.add("CrossPoint highlights: ${error.message}")
            if (manual) tell(error.message ?: "Highlights didn't sync")
        }
    }

    /** Applies one clipping from the server. Returns true when it added a highlight here. */
    private suspend fun applyRemote(item: JSONObject, local: Map<String, HighlightRecord>, sent: MutableMap<String, String>): Boolean {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return false
        val mine = local[id]
        if (item.optInt("deleted", 0) == 1) {
            if (mine != null && !mine.removed) book.remove(id)
            sent[id] = signature(true, null)
            return false
        }
        val note = item.opt("note")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        val remoteState = signature(false, note)
        if (mine != null) {
            // Removed here: the removal goes to the server next.
            if (mine.removed) return false
            val mineState = signature(false, mine.note)
            when {
                mineState == remoteState -> sent[id] = remoteState
                // Unchanged here since the last sync, so the other device's edit wins.
                sent[id] == mineState -> {
                    book.setNote(id, note)
                    sent[id] = remoteState
                }
            }
            return false
        }
        val text = item.optString("text")
        if (text.isBlank()) return false
        val spine = item.optInt("spine", -1).takeIf { it >= 0 } ?: return false
        val start = item.opt("start_offset")?.takeIf { it is Number }?.let { (it as Number).toInt() }
        val end = item.opt("end_offset")?.takeIf { it is Number }?.let { (it as Number).toInt() }
        val locator = book.locatorFor(spine, start, end, text) ?: return false
        val created = item.optLong("created_at", 0L).takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()
        book.add(id, locator, text, note, created)
        sent[id] = remoteState
        return true
    }

    private suspend fun encode(record: HighlightRecord): JSONObject? {
        val place = book.placeOf(record) ?: return null
        val text = clipText(record.text.ifBlank { return null })
        val item = JSONObject()
            .put("id", record.id)
            .put("spine", place.spine)
            .put("start_page", 0)
            .put("end_page", 0)
            .put("pages", 1)
            .put("start_word", 0)
            .put("end_word", 0)
            .put("words", text.split(Regex("\\s+")).count { it.isNotEmpty() }.coerceAtMost(65_535))
            .put("chapter", place.chapter.take(47))
            .put("text", text)
            .put("note", record.note.ifBlank { null } ?: JSONObject.NULL)
            .put("created_at", record.createdAt / 1000)
            .put("layout_signature", 0)
        if (place.start != null && place.end != null && place.end > place.start) {
            item.put("start_offset", place.start).put("end_offset", place.end)
        }
        return item
    }
}
