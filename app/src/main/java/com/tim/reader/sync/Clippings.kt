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

/** True for characters that the page and the file may space differently. */
private fun isLoose(char: Char) = char.isWhitespace() || char == '\u00AD'

/**
 * Finds a highlight's words in a chapter. The words around it settle which copy when a phrase
 * comes up more than once, and failing that, the one nearest [near], a fraction of the chapter.
 *
 * The page spaces words its own way, joining lines and putting breaks between paragraphs, so when
 * the words aren't found as they are, they're looked for again with all spacing left out.
 */
fun findQuote(text: String, quote: String, before: String, after: String, near: Double?): IntRange? {
    exactQuote(text, quote, before, after, near)?.let { return it }
    // Without spacing, keeping where each remaining letter was in the original.
    val positions = ArrayList<Int>(text.length)
    val squeezed = StringBuilder(text.length)
    text.forEachIndexed { index, char ->
        if (!isLoose(char)) {
            squeezed.append(char)
            positions += index
        }
    }
    fun squeeze(value: String) = value.filterNot(::isLoose)
    val found = exactQuote(squeezed.toString(), squeeze(quote), squeeze(before), squeeze(after), near) ?: return null
    return positions[found.first]..positions[found.last]
}

private fun exactQuote(text: String, quote: String, before: String, after: String, near: Double?): IntRange? {
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
    /** Names this book, so a copy imported again keeps its own sync state. */
    val key: String
    suspend fun records(): List<HighlightRecord>
    /** Adds highlights from other devices. Returns the IDs it could add. */
    suspend fun add(highlights: List<HighlightRecord>): Set<String>
    suspend fun setNote(id: String, note: String)
    suspend fun remove(id: String)
    /** Where a saved highlight sits, for sending. Null when the book can't be read just now. */
    suspend fun placeOf(record: HighlightRecord): ClipPlace?
    /** A saved locator for a highlight from another device, or null when it can't be placed. */
    suspend fun locatorFor(spine: Int, start: Int?, end: Int?, text: String): String?
}

/** What sync remembers between runs, for one book on one account. */
class ClipState(
    var cursor: Long = 0,
    /** What each highlight looked like on the server when last sent or seen: "x" removed, or "n:" and its note. */
    val sent: MutableMap<String, String> = HashMap(),
    /** The server's own copy of each live clipping, so a note edit keeps the rest as CrossPoint saved it. */
    val remote: MutableMap<String, String> = HashMap(),
)

interface ClipMemory {
    fun load(slot: String): ClipState
    fun save(slot: String, state: ClipState)
}

/** The longest note the server keeps. Longer ones are cut, so both sides compare the same text. */
private const val NOTE_BYTES = 4000

/** The state of a highlight on the server: removed, or there with this note. */
private fun signature(removed: Boolean, note: String?) =
    if (removed) "x" else "n:" + md5Hex(clipText(note.orEmpty(), NOTE_BYTES).toByteArray())

/** Fields the server adds that aren't part of a clipping sent back. */
private val SERVER_FIELDS = listOf("revision", "updated_at", "deleted", "color", "document")

class ClippingSync(
    private val book: HighlightBook,
    private val memory: ClipMemory,
    private val document: suspend () -> String?,
    private val tell: (String) -> Unit,
) {
    @Volatile private var unsupported = false

    /** Fetches highlights from other devices, then sends this device's changes. */
    suspend fun run(manual: Boolean) {
        if (!CrossPointSettings.isReady || !CrossPointSettings.syncHighlights) return
        if (unsupported && !manual) return
        val doc = document() ?: return
        val base = CrossPointSettings.baseUrl
        val user = CrossPointSettings.username
        val key = CrossPointSettings.key
        // State belongs to one account on one server, for one copy of the book.
        val slot = md5Hex("$base\n$user\n$doc\n${book.key}".toByteArray())
        // Two screens on the same book share one turn at a time, so neither loses the other's work.
        lockFor(slot).withLock {
            try {
                sync(manual, base, user, key, doc, slot)
            } catch (error: SyncException) {
                ResultsLog.add("CrossPoint highlights: ${error.message}")
                if (manual) tell(error.message ?: "Highlights didn't sync")
            }
        }
    }

    private suspend fun sync(manual: Boolean, base: String, user: String, key: String, doc: String, slot: String) {
        val state = memory.load(slot)
        val local = book.records().associateBy { it.id }
        while (true) {
            val page = KoSyncClient.fetchClippings(base, user, key, doc, state.cursor) ?: run {
                unsupported = true
                if (manual) tell("This sync server doesn't keep highlights. CrossPoint's does.")
                return
            }
            page.items.forEach { item -> applyRemote(item, local, state) }
            val moved = page.cursor > state.cursor
            state.cursor = maxOf(state.cursor, page.cursor)
            if (!page.more || !moved) break
        }
        unsupported = false
        memory.save(slot, state)

        // Clippings not here yet, including ones that couldn't be placed before, are tried now.
        val adding = ArrayList<HighlightRecord>()
        for ((id, json) in state.remote) {
            if (local.containsKey(id)) continue
            val item = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val text = item.optString("text").takeIf { it.isNotBlank() } ?: continue
            val spine = item.optInt("spine", -1).takeIf { it >= 0 } ?: continue
            val start = (item.opt("start_offset") as? Number)?.toInt()
            val end = (item.opt("end_offset") as? Number)?.toInt()
            val locator = book.locatorFor(spine, start, end, text) ?: continue
            val note = item.opt("note")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            val created = item.optLong("created_at", 0L).takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()
            adding += HighlightRecord(id, locator, text, note, created, removed = false)
        }
        val added = if (adding.isEmpty()) emptySet() else book.add(adding)
        adding.filter { it.id in added }.forEach { state.sent[it.id] = signature(false, it.note) }
        memory.save(slot, state)

        // Changes made here: new highlights, edited notes and removals not yet on the server.
        val outgoing = ArrayList<Pair<String, JSONObject>>()
        for (record in book.records()) {
            val now = signature(record.removed, record.note)
            if (state.sent[record.id] == now) continue
            if (record.removed) {
                // A highlight removed before it was ever sent has nothing to remove there.
                if (state.sent.containsKey(record.id) || state.remote.containsKey(record.id)) {
                    outgoing += now to JSONObject().put("id", record.id).put("deleted", 1)
                } else {
                    state.sent[record.id] = now
                }
                continue
            }
            val item = encode(record, state.remote[record.id]) ?: continue
            outgoing += now to item
        }
        for (batch in outgoing.chunked(50)) {
            KoSyncClient.pushClippings(base, user, key, doc, JSONArray(batch.map { it.second }))
            batch.forEach { (now, item) -> state.sent[item.getString("id")] = now }
            memory.save(slot, state)
        }
        if (manual && added.isNotEmpty()) {
            tell(if (added.size == 1) "Added a highlight from another device" else "Added ${added.size} highlights from other devices")
        }
    }

    /** Takes in one clipping from the server: a removal, a note changed elsewhere, or one to add. */
    private suspend fun applyRemote(item: JSONObject, local: Map<String, HighlightRecord>, state: ClipState) {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return
        val mine = local[id]
        if (item.optInt("deleted", 0) == 1) {
            if (mine != null && !mine.removed) book.remove(id)
            state.sent[id] = signature(true, null)
            state.remote.remove(id)
            return
        }
        state.remote[id] = item.toString()
        val note = item.opt("note")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
        val remoteState = signature(false, note)
        // Removed here, so the removal goes to the server next. Not here yet, so it's added below.
        if (mine == null || mine.removed) return
        val mineState = signature(false, mine.note)
        when {
            mineState == remoteState -> state.sent[id] = remoteState
            // Unchanged here since the last sync, so the other device's edit wins.
            state.sent[id] == mineState -> {
                book.setNote(id, note)
                state.sent[id] = remoteState
            }
        }
    }

    /**
     * A clipping to send. One that came from the server goes back as it was with only the note
     * changed, so CrossPoint keeps its own page details. A new one is placed in its chapter.
     */
    private suspend fun encode(record: HighlightRecord, remote: String?): JSONObject? {
        val note: Any = clipText(record.note, NOTE_BYTES).ifBlank { null } ?: JSONObject.NULL
        remote?.let { saved -> runCatching { JSONObject(saved) }.getOrNull() }?.let { item ->
            SERVER_FIELDS.forEach { item.remove(it) }
            return item.put("note", note)
        }
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
            .put("note", note)
            .put("created_at", record.createdAt / 1000)
            .put("layout_signature", 0)
        if (place.start != null && place.end != null && place.end > place.start) {
            item.put("start_offset", place.start).put("end_offset", place.end)
        }
        return item
    }

    private companion object {
        val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        fun lockFor(slot: String): Mutex = locks.getOrPut(slot) { Mutex() }
    }
}
