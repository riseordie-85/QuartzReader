package com.tim.reader.stats

import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository.PlannedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Reading stats from an Xteink e-reader running QuartzInk, as its File Transfer page exports them
 * (format "quartzink-stats", described in QuartzInk's docs/stats-export.md).
 */
class EreaderExport(
    /** The reader's own ID, stable across firmware updates. Imports are kept apart by it. */
    val deviceId: String,
    val deviceName: String,
    val exportedAt: LocalDateTime?,
    /** Every day the reader recorded reading, newest first. */
    val readingDays: List<LocalDate>,
    val books: List<EreaderBook>,
)

class EreaderBook(
    val title: String,
    val author: String,
    val readingSeconds: Long,
    val pagesTurned: Int,
    val completed: Boolean,
    val startDate: LocalDate?,
    val finishedDate: LocalDate?,
    /** Morning, afternoon, evening and night, as the reader splits the day. */
    val timeOfDaySeconds: LongArray,
)

private const val FORMAT = "quartzink-stats"
private const val FORMAT_VERSION = 1

/** Marks every event an e-reader import adds, with the reader's ID, so importing again replaces them. */
const val EREADER_SOURCE = "eink"

fun parseEreaderExport(text: String): EreaderExport {
    val root = JSONObject(text)
    require(root.optString("format") == FORMAT) { "This isn't a QuartzInk stats file" }
    val version = root.optInt("version")
    require(version in 1..FORMAT_VERSION) { "This stats file is version $version, newer than Quartz Reader understands" }
    val device = root.optJSONObject("device") ?: JSONObject()
    val deviceId = device.optString("id").filter { it.isLetterOrDigit() }
    require(deviceId.isNotEmpty() && deviceId.any { it != '0' }) { "This stats file doesn't say which reader it came from" }

    fun date(value: Any?): LocalDate? = (value as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val global = root.optJSONObject("global") ?: JSONObject()
    val days = global.optJSONArray("readingDays")
    val readingDays = buildList { if (days != null) for (i in 0 until days.length()) date(days.opt(i))?.let { add(it) } }

    val list = root.optJSONArray("books")
    val books = buildList {
        if (list != null) for (i in 0 until list.length()) {
            val book = list.optJSONObject(i) ?: continue
            val buckets = book.optJSONObject("timeOfDaySeconds") ?: JSONObject()
            add(
                EreaderBook(
                    title = book.optString("title").ifBlank { book.optString("path").substringAfterLast('/').substringBeforeLast('.') },
                    author = book.optString("author"),
                    readingSeconds = book.optLong("readingSeconds").coerceAtLeast(0),
                    pagesTurned = book.optInt("pagesTurned").coerceAtLeast(0),
                    completed = book.optBoolean("completed"),
                    startDate = date(book.opt("startDate")),
                    finishedDate = date(book.opt("finishedDate")),
                    timeOfDaySeconds = longArrayOf(
                        buckets.optLong("morning"),
                        buckets.optLong("afternoon"),
                        buckets.optLong("evening"),
                        buckets.optLong("night"),
                    ).map { it.coerceAtLeast(0) }.toLongArray(),
                ),
            )
        }
    }
    val exportedAt = root.optString("exportedAt").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return EreaderExport(deviceId, device.optString("name").ifBlank { "E-reader" }, exportedAt, readingDays, books)
}

/** Downloads the stats from a reader's File Transfer page, given its address as the reader shows it. */
suspend fun fetchEreaderExport(address: String): String = withContext(Dispatchers.IO) {
    val trimmed = address.trim().removeSuffix("/")
    require(trimmed.isNotEmpty()) { "Enter the address the reader shows" }
    val base = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    val connection = URL("$base/api/stats").openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    try {
        val code = connection.responseCode
        check(code == 200) { if (code == 404) "That reader doesn't have stats export. Update it to QuartzInk." else "The reader answered $code" }
        connection.inputStream.use { it.readBytes().decodeToString() }
    } finally {
        connection.disconnect()
    }
}

/** What an e-reader import will add. Counts are for the preview. */
class EreaderPlan(
    val export: EreaderExport,
    val events: List<PlannedEvent>,
    val books: Int,
    val matched: Int,
    val elsewhere: Int,
    val minutes: Long,
    val finished: Int,
    /** Finished on the reader but already finished in the app, so not counted twice. */
    val alreadyFinished: Int,
)

// The hour each of the reader's time-of-day buckets is placed at, inside Quartz Reader's own buckets.
private val BUCKET_HOURS = intArrayOf(9, 14, 19, 23)

/**
 * Turns an export into history events. The reader keeps only totals for each book, not its sessions,
 * so each book's time is spread over the days the reader recorded reading between its start and
 * finish, and split across the day the way the reader split it. Totals stay exact, and days, streaks
 * and time of day come out close to how the reading really went.
 */
fun planEreaderImport(
    export: EreaderExport,
    books: List<Book>,
    history: ReadingHistory,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
): EreaderPlan {
    val byTitle = books.groupBy { titleKey(it.title) }
    // Finishes from this reader's earlier import are replaced, so only the app's own count here.
    val finishedInApp = history.finished
        .filter { it.source != EREADER_SOURCE }
        .mapNotNullTo(HashSet()) { it.bookHash }
    val today = export.exportedAt?.toLocalDate() ?: LocalDate.now(zone)
    val readingDays = export.readingDays.toSortedSet()

    val events = ArrayList<PlannedEvent>()
    var matched = 0
    var elsewhere = 0
    var totalSeconds = 0L
    var finished = 0
    var alreadyFinished = 0
    var counted = 0

    for (entry in export.books) {
        if (entry.readingSeconds == 0L && !entry.completed) continue
        counted++
        val book = byTitle[titleKey(entry.title)]?.let { candidates ->
            candidates.firstOrNull { sameAuthor(it.author, entry.author) } ?: candidates.singleOrNull()?.takeIf { it.author.isBlank() || entry.author.isBlank() }
        }
        if (book != null) matched++ else elsewhere++
        val type = book?.type ?: BookType.Book

        fun baseData() = JSONObject().put("source", EREADER_SOURCE).put(EREADER_SOURCE, export.deviceId).also { data ->
            data.put("ereader", export.deviceName)
            if (book == null) data.put("title", entry.title).put("author", entry.author).put("type", type.name)
        }

        // The span the reading happened in. A finish recorded before the start (a clock change or a
        // book marked finished early) is treated as one day.
        val first = entry.startDate ?: entry.finishedDate ?: readingDays.lastOrNull() ?: today
        val last = (if (entry.completed) entry.finishedDate else null) ?: today
        val from = minOf(first, last)
        val to = maxOf(first, last)

        if (entry.readingSeconds > 0) {
            totalSeconds += entry.readingSeconds
            val days = readingDays.subSet(from, to.plusDays(1)).toList().ifEmpty { listOf(from) }
            val bucketTotal = entry.timeOfDaySeconds.sum()
            val shares = if (bucketTotal > 0) {
                entry.timeOfDaySeconds.map { it.toDouble() / bucketTotal }
            } else {
                listOf(0.0, 0.0, 1.0, 0.0) // no split recorded: evening
            }
            val slots = days.flatMap { day -> shares.indices.filter { shares[it] > 0 }.map { day to it } }
            val totalMs = entry.readingSeconds * 1000
            var msLeft = totalMs
            var turnsLeft = entry.pagesTurned
            slots.forEachIndexed { index, (day, bucket) ->
                val lastSlot = index == slots.lastIndex
                val ms = if (lastSlot) msLeft else (totalMs * shares[bucket] / days.size).toLong()
                val turns = if (lastSlot) turnsLeft else (entry.pagesTurned * shares[bucket] / days.size).toInt()
                msLeft -= ms
                turnsLeft -= turns
                if (ms <= 0) return@forEachIndexed
                val start = day.atTime(LocalTime.of(BUCKET_HOURS[bucket], 0)).atZone(zone).toInstant().toEpochMilli()
                val data = baseData()
                    .put("type", type.name)
                    .put("format", "XTEINK")
                    .put("active", ms)
                    .put("from", 0)
                    .put("to", 0)
                    .put("turns", turns)
                events += PlannedEvent(EventKind.SESSION, book, null, minOf(start, now), minOf(start + ms, now), data)
            }
        }

        if (entry.completed) {
            if (book != null && book.hash in finishedInApp) {
                alreadyFinished++
            } else {
                finished++
                val readId = UUID.randomUUID().toString()
                val finishedDay = entry.finishedDate ?: today
                val startedAt = minOf(from, finishedDay).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
                val finishedAt = finishedDay.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
                val data = baseData().put("dateUnknown", entry.finishedDate == null)
                events += PlannedEvent(EventKind.READ_STARTED, book, readId, minOf(startedAt, now), null, JSONObject(data.toString()))
                events += PlannedEvent(EventKind.READ_FINISHED, book, readId, minOf(finishedAt, now), null, data)
            }
        }
    }
    return EreaderPlan(export, events, counted, matched, elsewhere, totalSeconds / 60, finished, alreadyFinished)
}
