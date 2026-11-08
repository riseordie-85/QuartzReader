package com.tim.reader.stats

import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

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

/** One finished read from the history. */
data class FinishedRecord(val bookHash: String?, val at: Long)

class ReadingHistory(val sessions: List<SessionRecord>, val finished: List<FinishedRecord>)

/** Reading for one type, in its own units. */
data class TypeStats(
    val type: BookType,
    val minutes: Long,
    val finished: Int,
    val pages: Int,
    val words: Long,
    /** Words a minute for books, and seconds a page for everything else. Null until there's enough reading. */
    val speed: Int?,
)

/** A book finished, with the book when it's still in the library. */
data class FinishedBook(val book: Book?, val at: Long)

data class Stats(
    val todayMinutes: Long,
    val weekMinutes: Long,
    val yearMinutes: Long,
    val totalMinutes: Long,
    val streak: Int,
    val bestStreak: Int,
    val minutesByDay: Map<LocalDate, Long>,
    val today: LocalDate,
    val byType: List<TypeStats>,
    val finishedByMonth: List<Pair<YearMonth, Int>>,
    val finishedThisYear: Int,
    val finishedTotal: Int,
    val timeOfDay: List<Pair<String, Long>>,
    val pages: Int,
    val words: Long,
    val recentlyFinished: List<FinishedBook>,
) {
    val isEmpty get() = totalMinutes == 0L && finishedTotal == 0
}

// A Readium position is about 1,024 characters, and English averages close to 5.7 characters a word.
private const val WORDS_PER_POSITION = 180

// Faster than this is skipping, not reading, so it's capped. About 540 words a minute.
private const val MAX_POSITIONS_PER_MINUTE = 3.0

// A session counts toward the day only once it reaches a minute.
private const val MINUTE_MS = 60_000L

private const val MIN_MINUTES_FOR_SPEED = 10

/** Works out every stat from the history. Runs off the main thread, since histories grow long. */
fun computeStats(history: ReadingHistory, books: List<Book>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Stats {
    fun dateOf(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    val today = dateOf(now)
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val yearStart = today.withDayOfYear(1)

    val msByDay = HashMap<LocalDate, Long>()
    val msByType = HashMap<BookType, Long>()
    val pagesByType = HashMap<BookType, Int>()
    val wordsByType = HashMap<BookType, Long>()
    val buckets = longArrayOf(0, 0, 0, 0)

    for (session in history.sessions) {
        if (session.activeMs <= 0) continue
        val day = dateOf(session.start)
        msByDay[day] = (msByDay[day] ?: 0L) + session.activeMs
        val type = session.type ?: BookType.Book
        msByType[type] = (msByType[type] ?: 0L) + session.activeMs
        val minutes = session.activeMs.toDouble() / MINUTE_MS
        if (session.format == "EPUB") {
            val forward = (session.to - session.from).coerceAtLeast(0)
            val counted = minOf(forward.toDouble(), minutes * MAX_POSITIONS_PER_MINUTE)
            wordsByType[type] = (wordsByType[type] ?: 0L) + (counted * WORDS_PER_POSITION).toLong()
        } else {
            pagesByType[type] = (pagesByType[type] ?: 0) + session.turns
        }
        val hour = Instant.ofEpochMilli(session.start).atZone(zone).hour
        val bucket = when (hour) {
            in 5..11 -> 0
            in 12..16 -> 1
            in 17..21 -> 2
            else -> 3
        }
        buckets[bucket] += session.activeMs
    }

    val minutesByDay = msByDay.mapValues { it.value / MINUTE_MS }
    fun minutesBetween(from: LocalDate) = msByDay.filterKeys { !it.isBefore(from) && !it.isAfter(today) }.values.sum() / MINUTE_MS

    // A streak is days in a row with at least a minute of reading. Today only breaks it once it's over.
    val readDays = minutesByDay.filterValues { it >= 1 }.keys
    var streak = 0
    var day = if (today in readDays) today else today.minusDays(1)
    while (day in readDays) {
        streak++
        day = day.minusDays(1)
    }
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    for (date in readDays.sorted()) {
        run = if (previous != null && date == previous.plusDays(1)) run + 1 else 1
        best = maxOf(best, run)
        previous = date
    }

    val byHash = books.associateBy { it.hash }
    val finishedByType = HashMap<BookType, Int>()
    for (read in history.finished) {
        val type = read.bookHash?.let { byHash[it]?.type } ?: continue
        finishedByType[type] = (finishedByType[type] ?: 0) + 1
    }

    val byType = BookType.entries.mapNotNull { type ->
        val ms = msByType[type] ?: 0L
        val finished = finishedByType[type] ?: 0
        if (ms == 0L && finished == 0) return@mapNotNull null
        val minutes = ms / MINUTE_MS
        val pages = pagesByType[type] ?: 0
        val words = wordsByType[type] ?: 0L
        val speed = when {
            minutes < MIN_MINUTES_FOR_SPEED -> null
            words > 0 -> (words / minutes).toInt()
            pages > 0 -> (ms / 1000 / pages).toInt()
            else -> null
        }
        TypeStats(type, minutes, finished, pages, words, speed)
    }

    val thisMonth = YearMonth.from(today)
    val finishedMonths = history.finished.groupingBy { YearMonth.from(dateOf(it.at)) }.eachCount()
    val finishedByMonth = (11 downTo 0).map { back -> thisMonth.minusMonths(back.toLong()).let { it to (finishedMonths[it] ?: 0) } }

    val recentlyFinished = history.finished
        .sortedByDescending { it.at }
        .take(RECENT_FINISHED)
        .map { FinishedBook(it.bookHash?.let(byHash::get), it.at) }

    return Stats(
        todayMinutes = minutesBetween(today),
        weekMinutes = minutesBetween(weekStart),
        yearMinutes = minutesBetween(yearStart),
        totalMinutes = msByDay.values.sum() / MINUTE_MS,
        streak = streak,
        bestStreak = best,
        minutesByDay = minutesByDay,
        today = today,
        byType = byType,
        finishedByMonth = finishedByMonth,
        finishedThisYear = history.finished.count { !dateOf(it.at).isBefore(yearStart) },
        finishedTotal = history.finished.size,
        timeOfDay = listOf("Morning", "Afternoon", "Evening", "Night").zip(buckets.map { it / MINUTE_MS }),
        pages = pagesByType.values.sum(),
        words = wordsByType.values.sum(),
        recentlyFinished = recentlyFinished,
    )
}

private const val RECENT_FINISHED = 8

/** "2 h 15 m", or "45 m" under an hour. */
fun formatMinutes(minutes: Long): String = when {
    minutes < 60 -> "$minutes m"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} m"
}

/** The first day of the week where the reader lives, for the calendar. */
fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
