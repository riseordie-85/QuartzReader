package com.tim.reader.stats

import com.tim.reader.data.BookType
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * How fast this reader goes, from their own history: Readium positions a minute for EPUBs, and
 * seconds a page for each type of image book. Sensible starting speeds fill in until there's
 * enough reading to go on.
 */
class ReadingSpeeds(val positionsPerMinute: Double, private val pageSeconds: Map<BookType, Double>) {
    fun secondsPerPage(type: BookType): Double = pageSeconds[type] ?: DEFAULT_PAGE_SECONDS.getValue(type)

    companion object {
        // About 250 words a minute: a position is about 1,024 characters, a word about 5.7.
        private const val DEFAULT_POSITIONS_PER_MINUTE = 1.4
        private val DEFAULT_PAGE_SECONDS = mapOf(
            BookType.Book to 60.0,
            BookType.Comic to 20.0,
            BookType.Manga to 12.0,
            BookType.Manhwa to 8.0,
        )
        private const val MIN_TEXT_MINUTES = 15.0
        private const val MIN_PAGE_MINUTES = 10.0
        private const val MIN_TURNS = 20

        val Default = ReadingSpeeds(DEFAULT_POSITIONS_PER_MINUTE, emptyMap())

        fun from(history: ReadingHistory): ReadingSpeeds {
            var textMinutes = 0.0
            var positions = 0.0
            val pageMs = HashMap<BookType, Long>()
            val turns = HashMap<BookType, Int>()
            for (session in history.sessions) {
                val minutes = session.activeMs / 60_000.0
                if (session.format == "EPUB") {
                    // Jumps aren't reading, so a session counts no faster than about 540 words a minute.
                    positions += minOf((session.to - session.from).coerceAtLeast(0).toDouble(), minutes * 3.0)
                    textMinutes += minutes
                } else {
                    val type = session.type ?: continue
                    pageMs[type] = (pageMs[type] ?: 0L) + session.activeMs
                    turns[type] = (turns[type] ?: 0) + session.turns
                }
            }
            val ppm = if (textMinutes >= MIN_TEXT_MINUTES && positions > 0) (positions / textMinutes).coerceIn(0.3, 4.0) else DEFAULT_POSITIONS_PER_MINUTE
            val seconds = pageMs.mapNotNull { (type, ms) ->
                val count = turns[type] ?: 0
                if (ms / 60_000.0 >= MIN_PAGE_MINUTES && count >= MIN_TURNS) type to (ms / 1000.0 / count).coerceIn(2.0, 300.0) else null
            }.toMap()
            return ReadingSpeeds(ppm, seconds)
        }
    }
}

/** "Less than a minute", "12 min", or "4 h 10 min". */
fun formatTimeLeft(minutes: Double): String {
    if (minutes < 1) return "Less than a minute"
    val whole = ceil(minutes).roundToLong()
    return if (whole < 60) "$whole min" else if (whole % 60 == 0L) "${whole / 60} h" else "${whole / 60} h ${whole % 60} min"
}

/** The time left in an image book, like "18 min left in this volume". */
fun imageTimeLeft(speeds: ReadingSpeeds, type: BookType, pagesLeft: Int): String? {
    if (pagesLeft <= 0) return null
    val where = when (type) {
        BookType.Book -> "book"
        BookType.Comic -> "issue"
        BookType.Manga -> "volume"
        BookType.Manhwa -> "episode"
    }
    return "${formatTimeLeft(pagesLeft * speeds.secondsPerPage(type) / 60)} left in this $where"
}

/**
 * The time left in an EPUB's chapter and in the whole book. [position] is Readium's, counted from 1,
 * and [chapterStarts] are the first positions of each chapter.
 */
fun textTimeLeft(speeds: ReadingSpeeds, position: Int, positionCount: Int, chapterStarts: List<Int>): String? {
    if (positionCount <= 0 || position <= 0) return null
    val bookLeft = (positionCount - position + 1).coerceAtLeast(0)
    if (bookLeft <= 0) return null
    val book = "${formatTimeLeft(bookLeft / speeds.positionsPerMinute)} left in book"
    val nextChapter = chapterStarts.firstOrNull { it > position } ?: (positionCount + 1)
    val chapterLeft = nextChapter - position
    // A book with no real chapters, or one long one, just shows the book.
    if (chapterStarts.size < 2 || chapterLeft >= bookLeft) return book
    return "${formatTimeLeft(chapterLeft / speeds.positionsPerMinute)} left in chapter · $book"
}
