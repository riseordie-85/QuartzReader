package com.tim.reader.stats

import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryState
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class GoalKind(val label: String) {
    Count("Read a number"),
    Series("Finish a series"),
    Book("Finish a book"),
    Shelf("Shelf challenge"),
    Habit("Daily habit"),
}

/** What a count goal counts. [type] limits it to one kind of book. */
enum class GoalUnit(val one: String, val many: String, val type: BookType? = null) {
    Stories("story", "stories"),
    Books("book", "books", BookType.Book),
    Comics("comic", "comics", BookType.Comic),
    Manga("manga volume", "manga volumes", BookType.Manga),
    Manhwa("manhwa episode", "manhwa episodes", BookType.Manhwa),
    Pages("page", "pages"),
    Hours("hour", "hours"),
    /** Minutes a day, for habit goals. */
    Minutes("minute", "minutes"),
    /** Days a week with some reading, for habit goals. */
    Days("day", "days"),
    ;

    fun count(n: Int) = "$n ${if (n == 1) one else many}"
}

enum class GoalPeriod(val label: String) {
    Day("today"),
    Week("this week"),
    Month("this month"),
    Year("this year"),
    Dates("between dates"),
}

/**
 * Something to aim for. Count and shelf goals use [unit], [target] and [period]. Series and book
 * goals aim to finish by [end]. Habit goals are minutes a day or days a week.
 */
data class Goal(
    val id: String,
    val kind: GoalKind,
    val unit: GoalUnit = GoalUnit.Stories,
    val target: Int = 1,
    val period: GoalPeriod = GoalPeriod.Year,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val seriesTitle: String? = null,
    val bookHash: String? = null,
    val bookTitle: String? = null,
    val shelfName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("unit", unit.name)
        put("target", target)
        put("period", period.name)
        start?.let { put("start", it.toString()) }
        end?.let { put("end", it.toString()) }
        seriesTitle?.let { put("series", it) }
        bookHash?.let { put("book", it) }
        bookTitle?.let { put("bookTitle", it) }
        shelfName?.let { put("shelf", it) }
        put("created", createdAt)
    }

    /** One line that says what the goal is, like "Read 4 books this month". */
    fun describe(): String = when (kind) {
        GoalKind.Count -> "Read ${unit.count(target)} ${periodText()}"
        GoalKind.Shelf -> "Read ${GoalUnit.Books.count(target)} from $shelfName ${periodText()}"
        GoalKind.Series -> "Finish $seriesTitle${end?.let { " by ${SHORT_DATE.format(it)}" }.orEmpty()}"
        GoalKind.Book -> "Finish $bookTitle${end?.let { " by ${SHORT_DATE.format(it)}" }.orEmpty()}"
        GoalKind.Habit -> if (unit == GoalUnit.Days) "Read ${unit.count(target)} a week" else "Read ${unit.count(target)} a day"
    }

    private fun periodText() = if (period == GoalPeriod.Dates) {
        listOfNotNull(start?.let(SHORT_DATE::format), end?.let(SHORT_DATE::format)).joinToString(" to ").let { "from $it" }
    } else {
        period.label
    }

    /** Goals that end. Once over, they move to the finished list. */
    val hasDeadline get() = kind == GoalKind.Series || kind == GoalKind.Book || period == GoalPeriod.Dates

    companion object {
        fun fromJson(id: String, data: JSONObject, at: Long): Goal? = runCatching {
            Goal(
                id = id,
                kind = GoalKind.valueOf(data.getString("kind")),
                unit = GoalUnit.valueOf(data.optString("unit", GoalUnit.Stories.name)),
                target = data.optInt("target", 1).coerceAtLeast(1),
                period = GoalPeriod.valueOf(data.optString("period", GoalPeriod.Year.name)),
                start = data.optString("start").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                end = data.optString("end").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                seriesTitle = data.optString("series").takeIf { it.isNotEmpty() },
                bookHash = data.optString("book").takeIf { it.isNotEmpty() },
                bookTitle = data.optString("bookTitle").takeIf { it.isNotEmpty() },
                shelfName = data.optString("shelf").takeIf { it.isNotEmpty() },
                createdAt = data.optLong("created", at),
            )
        }.getOrNull()
    }
}

val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/** How a goal stands. [key] names the goal's current period, so reaching it is celebrated once. */
data class GoalProgress(
    val fraction: Float,
    val headline: String,
    val pace: String,
    val done: Boolean,
    val over: Boolean,
    val key: String,
)

private const val FINISHED = 0.999f

/** Works out where a goal stands today. */
fun goalProgress(goal: Goal, history: ReadingHistory, state: LibraryState, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): GoalProgress {
    fun dateOf(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val created = dateOf(goal.createdAt)
    val (from, to) = when {
        goal.kind == GoalKind.Habit && goal.unit == GoalUnit.Days -> weekOf(today)
        goal.kind == GoalKind.Habit -> today to today
        goal.kind == GoalKind.Series || goal.kind == GoalKind.Book -> (goal.end ?: today).let { end -> minOf(created, end) to end }
        else -> when (goal.period) {
            GoalPeriod.Day -> today to today
            GoalPeriod.Week -> weekOf(today)
            GoalPeriod.Month -> today.withDayOfMonth(1) to today.with(TemporalAdjusters.lastDayOfMonth())
            GoalPeriod.Year -> today.withDayOfYear(1) to today.with(TemporalAdjusters.lastDayOfYear())
            GoalPeriod.Dates -> (goal.start ?: created) to (goal.end ?: today)
        }
    }
    val key = "${goal.id}:$from"
    val over = goal.hasDeadline && today.isAfter(to)
    fun inRange(time: Long) = dateOf(time).let { !it.isBefore(from) && !it.isAfter(to) }
    val byHash = state.books.associateBy { it.hash }

    return when (goal.kind) {
        GoalKind.Count, GoalKind.Shelf -> {
            val shelfIds = goal.shelfName?.let { name ->
                state.shelves.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { state.shelfBooks[it.id] } ?: emptySet()
            }
            val done: Double = when {
                goal.kind == GoalKind.Shelf -> history.finished.count { read ->
                    inRange(read.at) && read.bookHash?.let(byHash::get)?.id?.let { it in shelfIds.orEmpty() } == true
                }.toDouble()
                goal.unit == GoalUnit.Pages -> history.sessions.filter { inRange(it.start) }.sumOf { session ->
                    if (session.format == "EPUB") (session.to - session.from).coerceAtLeast(0) else session.turns
                }.toDouble()
                goal.unit == GoalUnit.Hours -> history.sessions.filter { inRange(it.start) }.sumOf { it.activeMs } / 3_600_000.0
                else -> history.finished.count { read ->
                    inRange(read.at) && (goal.unit.type == null || read.bookHash?.let(byHash::get)?.type == goal.unit.type)
                }.toDouble()
            }
            val unit = if (goal.kind == GoalKind.Shelf) GoalUnit.Books else goal.unit
            countProgress(done, goal.target, unit, from, to, today, key, over)
        }
        GoalKind.Habit -> {
            val minutesByDay = history.sessions.filter { inRange(it.start) }
                .groupBy { dateOf(it.start) }
                .mapValues { (_, sessions) -> sessions.sumOf { it.activeMs } / 60_000 }
            if (goal.unit == GoalUnit.Days) {
                val days = minutesByDay.count { it.value >= 1 }
                countProgress(days.toDouble(), goal.target, GoalUnit.Days, from, to, today, key, over = false)
            } else {
                val minutes = minutesByDay[today] ?: 0L
                val left = (goal.target - minutes).coerceAtLeast(0)
                GoalProgress(
                    fraction = (minutes.toFloat() / goal.target).coerceIn(0f, 1f),
                    headline = "$minutes of ${goal.target} minutes today",
                    pace = if (left == 0L) "Done for today" else "$left minutes to go",
                    done = left == 0L,
                    over = false,
                    key = key,
                )
            }
        }
        GoalKind.Series -> {
            val wanted = seriesKey(goal.seriesTitle.orEmpty())
            val ids = state.series.filter { seriesKey(it.title) == wanted }.map { it.id }.toSet()
            val books = state.books.filter { it.seriesId in ids }
            if (books.isEmpty()) return GoalProgress(0f, "No books from this series", "Not in your library", false, over, key)
            val finishedHashes = history.finished.mapNotNull { it.bookHash }.toSet()
            val read = books.count { isFinished(it, finishedHashes) }
            val total = books.size.coerceAtLeast(1)
            val left = (books.size - read).coerceAtLeast(0)
            if (goal.end == null) {
                GoalProgress(read.toFloat() / total, "$read of ${books.size} read", if (left == 0) "Done!" else "$left left", left == 0, false, key)
            } else {
                deadlineProgress(read.toFloat() / total, "$read of ${books.size} read", left, "volume", today, to, key, over)
            }
        }
        GoalKind.Book -> {
            val book = goal.bookHash?.let(byHash::get)
            val finished = book != null && (book.progress >= FINISHED || history.finished.any { it.bookHash == book.hash && it.at >= goal.createdAt })
            val fraction = if (finished) 1f else book?.progress ?: 0f
            val elapsed = fractionElapsed(from, to, today)
            val pace = when {
                finished -> "Done!"
                book == null -> "Not in your library"
                over -> "Past the date"
                fraction + 0.05f >= elapsed -> "On track"
                else -> "${((elapsed - fraction) * 100).roundToInt()}% behind"
            }
            GoalProgress(fraction, "${(fraction * 100).roundToInt()}% read", pace, finished, over, key)
        }
    }
}

private fun isFinished(book: Book, finishedHashes: Set<String>) = book.progress >= FINISHED || book.hash in finishedHashes

private fun weekOf(day: LocalDate): Pair<LocalDate, LocalDate> {
    val start = day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()))
    return start to start.plusDays(6)
}

private fun fractionElapsed(from: LocalDate, to: LocalDate, today: LocalDate): Float {
    val total = ChronoUnit.DAYS.between(from, to) + 1
    // A deadline before the start counts as already over.
    if (total <= 0) return 1f
    val gone = (ChronoUnit.DAYS.between(from, today) + 1).coerceIn(0, total)
    return gone.toFloat() / total
}

private fun countProgress(done: Double, target: Int, unit: GoalUnit, from: LocalDate, to: LocalDate, today: LocalDate, key: String, over: Boolean): GoalProgress {
    val shown = if (unit == GoalUnit.Hours) "%.1f".format(done) else done.toInt().toString()
    val reached = done >= target
    val expected = target * fractionElapsed(from, to, today)
    val gap = done - expected
    val single = from == to
    fun amount(value: Double) = if (unit == GoalUnit.Hours) "%.1f ${unit.many}".format(value) else unit.count(ceil(value - 0.001).toInt().coerceAtLeast(1))
    val pace = when {
        reached -> "Done!"
        over -> "Ended ${amount(target - done)} short"
        single -> "${amount(target - done)} to go"
        gap >= 1 -> "${amount(gap)} ahead"
        gap <= -1 -> "${amount(-gap)} behind"
        else -> "On track"
    }
    return GoalProgress(
        fraction = (done / target).toFloat().coerceIn(0f, 1f),
        headline = "$shown of ${unit.count(target)}",
        pace = pace,
        done = reached,
        over = over,
        key = key,
    )
}

private fun deadlineProgress(fraction: Float, headline: String, left: Int, unit: String, today: LocalDate, end: LocalDate, key: String, over: Boolean): GoalProgress {
    val days = ChronoUnit.DAYS.between(today, end) + 1
    val pace = when {
        left == 0 -> "Done!"
        over -> "Past the date"
        left >= days -> "About ${ceil(left.toDouble() / days).toInt()} a day to finish in time"
        else -> {
            val perWeek = ceil(left * 7.0 / days).toInt()
            "About $perWeek $unit${if (perWeek == 1) "" else "s"} a week to finish in time"
        }
    }
    return GoalProgress(fraction.coerceIn(0f, 1f), headline, pace, left == 0, over, key)
}
