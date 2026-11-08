package com.tim.reader.stats

import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A month of reading, for its share card. */
class MonthRecap(
    val month: YearMonth,
    val minutes: Long,
    val daysRead: Int,
    val finished: List<FinishedRecord>,
    val streak: Int,
)

/** A year of reading, for Year in Review. */
class YearRecap(
    val year: Int,
    val minutes: Long,
    val daysRead: Int,
    val finished: List<FinishedRecord>,
    /** Series by time spent, most first, as title and minutes. */
    val topSeries: List<Pair<String, Long>>,
    /** The quickest read from start to finish, and how long it took in milliseconds. */
    val fastest: Pair<FinishedRecord, Long>?,
    /** The share of reading time between 10 PM and 5 AM, as a percentage. */
    val nightShare: Int,
    val bestStreak: Int,
    val topType: BookType?,
)

private fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

private fun bestRun(days: Collection<LocalDate>): Int {
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in days.sorted()) {
        run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
        best = maxOf(best, run)
        previous = day
    }
    return best
}

fun monthRecap(history: ReadingHistory, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): MonthRecap {
    val sessions = history.sessions.filter { YearMonth.from(dayOf(it.start, zone)) == month }
    val minutesByDay = sessions.groupBy { dayOf(it.start, zone) }.mapValues { (_, list) -> list.sumOf { it.activeMs } / 60_000 }
    return MonthRecap(
        month = month,
        minutes = sessions.sumOf { it.activeMs } / 60_000,
        daysRead = minutesByDay.count { it.value >= 1 },
        finished = history.finished.filter { !it.dateUnknown && YearMonth.from(dayOf(it.at, zone)) == month }.sortedBy { it.at },
        streak = bestRun(minutesByDay.filterValues { it >= 1 }.keys),
    )
}

fun yearRecap(history: ReadingHistory, state: LibraryState, year: Int, zone: ZoneId = ZoneId.systemDefault()): YearRecap {
    val sessions = history.sessions.filter { dayOf(it.start, zone).year == year }
    val byHash = state.books.associateBy { it.hash }
    val minutesByDay = sessions.groupBy { dayOf(it.start, zone) }.mapValues { (_, list) -> list.sumOf { it.activeMs } / 60_000 }
    val finished = history.finished.filter { !it.dateUnknown && dayOf(it.at, zone).year == year }.sortedBy { it.at }

    val seriesMinutes = HashMap<String, Long>()
    for (session in sessions) {
        val book = session.bookHash?.let(byHash::get) ?: continue
        val title = state.seriesFor(book.seriesId)?.title ?: continue
        seriesMinutes[title] = (seriesMinutes[title] ?: 0L) + session.activeMs / 60_000
    }

    // Only reads finished in the app have a real start, so only they can be the fastest.
    val fastest = finished.filterNot { it.manual }.mapNotNull { read ->
        val start = read.readId?.let(history.readStarts::get) ?: return@mapNotNull null
        (read to read.at - start).takeIf { it.second >= 0 }
    }.minByOrNull { it.second }

    val total = sessions.sumOf { it.activeMs }
    val night = sessions.filter { Instant.ofEpochMilli(it.start).atZone(zone).hour.let { hour -> hour >= 22 || hour < 5 } }.sumOf { it.activeMs }
    val topType = sessions.groupBy { it.type }.mapValues { (_, list) -> list.sumOf { it.activeMs } }
        .filterKeys { it != null }.maxByOrNull { it.value }?.key

    return YearRecap(
        year = year,
        minutes = total / 60_000,
        daysRead = minutesByDay.count { it.value >= 1 },
        finished = finished,
        topSeries = seriesMinutes.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
        fastest = fastest,
        nightShare = if (total > 0) (night * 100 / total).toInt() else 0,
        bestStreak = bestRun(minutesByDay.filterValues { it >= 1 }.keys),
        topType = topType,
    )
}

/** The title to show for a finished read, whether the book is in the library or was read elsewhere. */
fun FinishedRecord.displayTitle(books: Map<String, Book>): String = bookHash?.let(books::get)?.title ?: title ?: "A book"

/** What the evening reminder should say, or null when today's reading is already done. */
fun reminderMessage(history: ReadingHistory, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<String, String>? {
    val today = dayOf(now, zone)
    val minutesByDay = history.sessions.groupBy { dayOf(it.start, zone) }.mapValues { (_, list) -> list.sumOf { it.activeMs } / 60_000 }
    val todayMinutes = minutesByDay[today] ?: 0L
    // A daily habit goal sets the bar; otherwise any reading keeps the streak.
    val habit = history.goals.firstOrNull { it.kind == GoalKind.Habit && it.unit == GoalUnit.Minutes }
    val target = habit?.target?.toLong() ?: 1L
    if (todayMinutes >= target) return null
    var streak = 0
    var day = today.minusDays(1)
    while ((minutesByDay[day] ?: 0L) >= 1) {
        streak++
        day = day.minusDays(1)
    }
    val title = if (streak >= 2) "Keep your $streak-day streak going" else "Time for a little reading"
    val text = when {
        habit != null && todayMinutes > 0 -> "$todayMinutes of ${habit.target} minutes read today"
        habit != null -> "${habit.target} minutes reaches today's goal"
        else -> "A few pages keeps the habit going"
    }
    return title to text
}

/** How long one read took: its reading time in minutes, and the days from start to finish when known. */
fun readSummary(history: ReadingHistory, read: FinishedRecord, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long?> {
    val start = read.readId?.let(history.readStarts::get)
    val minutes = history.sessions
        .filter { it.bookHash != null && it.bookHash == read.bookHash && it.start >= (start ?: 0L) && it.start <= read.at }
        .sumOf { it.activeMs } / 60_000
    val days = if (start != null && !read.manual) java.time.temporal.ChronoUnit.DAYS.between(dayOf(start, zone), dayOf(read.at, zone)) + 1 else null
    return minutes to days
}
