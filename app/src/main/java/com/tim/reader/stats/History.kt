package com.tim.reader.stats

import com.tim.reader.data.BookType
import com.tim.reader.data.EventKind
import org.json.JSONObject

/** One row of the reading history, as stored. */
class EventRow(
    val id: String,
    val kind: String,
    val bookHash: String?,
    val readId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val data: JSONObject,
)

/** One reading session from the history. */
data class SessionRecord(
    val bookHash: String?,
    val start: Long,
    val end: Long,
    val type: BookType?,
    val format: String,
    val activeMs: Long,
    val from: Int,
    val to: Int,
    val turns: Int,
)

/**
 * One finished read from the history. [manual] reads were marked as read or imported rather than
 * read in the app. A book read elsewhere has no hash, only its [title], [author] and [type].
 * [dateUnknown] reads have a stand-in date, so they count in totals but not by month or year.
 */
data class FinishedRecord(
    val bookHash: String?,
    val at: Long,
    val readId: String? = null,
    val manual: Boolean = false,
    val dateUnknown: Boolean = false,
    val title: String? = null,
    val author: String? = null,
    val type: BookType? = null,
    /** The Goodreads book ID for imported reads, so importing again skips them. */
    val sourceId: String? = null,
    val eventId: String = "",
    /** "reader", "manual" or "goodreads". */
    val source: String = "reader",
) {
    /** Ratings and reviews for books read elsewhere are kept under this, since they have no hash. */
    val ratingKey get() = bookHash ?: title?.let { elsewhereKey(it, author.orEmpty()) }
}

/** Names a book read elsewhere by its title and author. */
fun elsewhereKey(title: String, author: String) = "elsewhere:" + (title + "|" + author).lowercase().filter { it.isLetterOrDigit() || it == '|' }

/** Stars from half a star to five, with an optional short review. */
data class Rating(val stars: Float, val review: String = "", val spoiler: Boolean = false, val at: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("stars", stars.toDouble()).put("review", review).put("spoiler", spoiler)

    companion object {
        fun fromJson(data: JSONObject, at: Long) = Rating(
            stars = data.optDouble("stars", 0.0).toFloat(),
            review = data.optString("review"),
            spoiler = data.optBoolean("spoiler"),
            at = at,
        )
    }
}

/** Series ratings are matched by title, so they find their series on every device. */
fun seriesKey(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

class ReadingHistory(
    val sessions: List<SessionRecord>,
    val finished: List<FinishedRecord>,
    /** When each read began, by read ID. */
    val readStarts: Map<String, Long> = emptyMap(),
    /** Which book each read is of, by read ID. */
    val readBooks: Map<String, String> = emptyMap(),
    /** The newest rating for each book, by book hash, or by [elsewhereKey] for books read elsewhere. */
    val ratings: Map<String, Rating> = emptyMap(),
    /** The newest rating for each series, by [seriesKey]. */
    val seriesRatings: Map<String, Rating> = emptyMap(),
    val goals: List<Goal> = emptyList(),
    /** Goal periods already celebrated, as "goal ID:period start". */
    val goalsDone: Set<String> = emptySet(),
    /** Achievement tiers already unlocked, as "achievement ID:tier". */
    val unlocked: Set<String> = emptySet(),
) {
    companion object {
        fun from(rows: List<EventRow>): ReadingHistory {
            val sessions = ArrayList<SessionRecord>()
            val finished = ArrayList<FinishedRecord>()
            val starts = HashMap<String, Long>()
            val readBooks = HashMap<String, String>()
            val ratings = HashMap<String, Rating>()
            val seriesRatings = HashMap<String, Rating>()
            val goals = LinkedHashMap<String, Goal?>()
            val goalsDone = HashSet<String>()
            val unlocked = HashSet<String>()
            // Rows come oldest first, so a later rating or goal change replaces an earlier one.
            for (row in rows) {
                val data = row.data
                when (row.kind) {
                    EventKind.SESSION -> sessions += SessionRecord(
                        bookHash = row.bookHash,
                        start = row.startedAt,
                        end = row.endedAt ?: row.startedAt,
                        type = BookType.entries.firstOrNull { it.name == data.optString("type") },
                        format = data.optString("format"),
                        activeMs = data.optLong("active"),
                        from = data.optInt("from"),
                        to = data.optInt("to"),
                        turns = data.optInt("turns"),
                    )
                    EventKind.READ_STARTED -> row.readId?.let { id ->
                        starts[id] = row.startedAt
                        row.bookHash?.let { readBooks[id] = it }
                    }
                    EventKind.READ_FINISHED -> finished += FinishedRecord(
                        bookHash = row.bookHash,
                        at = row.startedAt,
                        readId = row.readId,
                        manual = data.optString("source", "reader") != "reader",
                        dateUnknown = data.optBoolean("dateUnknown"),
                        title = data.optString("title").takeIf { it.isNotEmpty() },
                        author = data.optString("author").takeIf { it.isNotEmpty() },
                        type = BookType.entries.firstOrNull { it.name == data.optString("type") },
                        sourceId = data.optString("goodreads").takeIf { it.isNotEmpty() },
                        eventId = row.id,
                        source = data.optString("source", "reader"),
                    )
                    EventKind.RATING -> (row.bookHash ?: data.optString("elsewhere").takeIf { it.isNotEmpty() })?.let { hash ->
                        val rating = Rating.fromJson(data, row.startedAt)
                        if (rating.stars > 0f) ratings[hash] = rating else ratings.remove(hash)
                    }
                    EventKind.SERIES_RATING -> {
                        val key = seriesKey(data.optString("series"))
                        val rating = Rating.fromJson(data, row.startedAt)
                        if (key.isNotEmpty()) {
                            if (rating.stars > 0f) seriesRatings[key] = rating else seriesRatings.remove(key)
                        }
                    }
                    EventKind.GOAL -> row.readId?.let { id ->
                        goals[id] = if (data.optBoolean("removed")) null else Goal.fromJson(id, data, row.startedAt)
                    }
                    EventKind.GOAL_DONE -> goalsDone += data.optString("key")
                    EventKind.ACHIEVEMENT -> unlocked += "${data.optString("id")}:${data.optInt("tier")}"
                }
            }
            return ReadingHistory(
                sessions = sessions,
                finished = finished,
                readStarts = starts,
                readBooks = readBooks,
                ratings = ratings,
                seriesRatings = seriesRatings,
                goals = goals.values.filterNotNull(),
                goalsDone = goalsDone,
                unlocked = unlocked,
            )
        }
    }
}

/** A read of this book that began but hasn't finished, as its start time. */
fun ReadingHistory.openReadStart(bookHash: String): Long? {
    val finishedIds = finished.mapNotNullTo(HashSet()) { it.readId }
    return readBooks.filter { (id, hash) -> hash == bookHash && id !in finishedIds }
        .keys.mapNotNull(readStarts::get).maxOrNull()
}
