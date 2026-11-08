package com.tim.reader.stats

import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Achievement tiers follow the brand's gems. */
enum class Gem(val label: String, val color: Long, val shine: Long) {
    Quartz("Quartz", 0xFFE9DDF2, 0xFFFFFFFF),
    Amethyst("Amethyst", 0xFF9A5CD6, 0xFFE2C8FF),
    Sapphire("Sapphire", 0xFF2E6BD8, 0xFFB9D3FF),
    Diamond("Diamond", 0xFF9FE8FF, 0xFFFFFFFF),
}

private val FOUR = listOf(Gem.Quartz, Gem.Amethyst, Gem.Sapphire, Gem.Diamond)

/**
 * Something to unlock. Each tier is a threshold for [measure] with its gem. [goal] says what a
 * tier asks for, like "Finish 10 books". Hidden ones show as a mystery until unlocked.
 */
class Achievement(
    val id: String,
    val name: String,
    val tiers: List<Pair<Long, Gem>>,
    val hidden: Boolean = false,
    val goal: (Long) -> String,
    val measure: (AchievementFacts) -> Long,
)

/** Where one achievement stands. [tier] is the highest unlocked, or -1. */
data class AchievementStatus(val achievement: Achievement, val value: Long, val tier: Int) {
    val gem get() = achievement.tiers.getOrNull(tier)?.second
    val next get() = achievement.tiers.getOrNull(tier + 1)?.first
}

private fun tiers(vararg thresholds: Long) = thresholds.toList().zip(FOUR)

private fun plural(n: Long, one: String, many: String = one + "s") = "$n ${if (n == 1L) one else many}"

/** Everything the achievements look at, worked out once. */
class AchievementFacts(history: ReadingHistory, state: LibraryState, zone: ZoneId) {
    private fun dateOf(time: Long) = Instant.ofEpochMilli(time).atZone(zone)

    private val byHash = state.books.associateBy { it.hash }
    val finishedByType: Map<BookType, Int> = history.finished
        .mapNotNull { read -> read.bookHash?.let(byHash::get)?.type }
        .groupingBy { it }.eachCount()
    val hours = history.sessions.sumOf { it.activeMs } / 3_600_000L
    val bestStreak: Long
    val seriesFinished: Long
    val longSeriesFinished: Long
    val binge: Long
    val genres: Long
    val nightOwl = history.sessions.count { dateOf(it.start).hour in 0..3 }.toLong()
    val earlyBird = history.sessions.count { dateOf(it.start).hour in 5..7 }.toLong()
    val lunchBreak = history.sessions.count { session ->
        val time = dateOf(session.start)
        val minutes = time.hour * 60 + time.minute
        time.dayOfWeek != DayOfWeek.SATURDAY && time.dayOfWeek != DayOfWeek.SUNDAY && minutes in 690..809
    }.toLong()
    val marathonHours: Long
    val highlights = state.highlights.values.sumOf { it.size }.toLong()
    val reviews = (history.ratings.values + history.seriesRatings.values).count { it.review.isNotBlank() }.toLong()
    val ratings = (history.ratings.size + history.seriesRatings.size).toLong()
    val mostRereads = history.finished.mapNotNull { it.bookHash }.groupingBy { it }.eachCount().values.maxOrNull()?.toLong() ?: 0L
    val customShelves = state.shelves.count { it.defaultType == null }.toLong()
    val goalsReached = history.goalsDone.size.toLong()
    val midnightFinish = history.finished.count { dateOf(it.at).hour == 0 }.toLong()
    val quickFinish = history.finished.count { read ->
        val start = read.readId?.let(history.readStarts::get) ?: return@count false
        read.at - start in 0..86_400_000L
    }.toLong()

    init {
        val days = history.sessions.filter { it.activeMs >= 60_000 }.map { dateOf(it.start).toLocalDate() }.toSortedSet()
        var best = 0L
        var run = 0L
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        bestStreak = best

        val finishedHashes = history.finished.mapNotNull { it.bookHash }.toSet()
        val bySeries = state.books.filter { it.seriesId != null }.groupBy { it.seriesId }
        val complete = bySeries.values.filter { books -> books.size >= 2 && books.all { it.hash in finishedHashes || it.progress >= 0.999f } }
        seriesFinished = complete.size.toLong()
        longSeriesFinished = complete.count { it.size >= 20 }.toLong()

        binge = history.finished
            .mapNotNull { read -> read.bookHash?.let(byHash::get)?.seriesId?.let { series -> series to dateOf(read.at).toLocalDate() } }
            .groupingBy { it }.eachCount().values.maxOrNull()?.toLong() ?: 0L

        genres = history.finished.mapNotNull { it.bookHash?.let(byHash::get) }
            .flatMap { book -> book.info.genres.map { it.trim().lowercase() } }
            .filter { it.isNotEmpty() }
            .toSet().size.toLong()

        marathonHours = history.sessions.groupBy { dateOf(it.start).toLocalDate() }
            .values.maxOfOrNull { sessions -> sessions.sumOf { it.activeMs } }?.div(3_600_000L) ?: 0L
    }
}

val ACHIEVEMENTS: List<Achievement> = listOf(
    Achievement("books", "Bookworm", tiers(1, 10, 50, 100), goal = { "Finish ${plural(it, "book")}" }) {
        it.finishedByType[BookType.Book]?.toLong() ?: 0L
    },
    Achievement("comics", "Panel Hopper", tiers(1, 25, 100, 250), goal = { "Finish ${plural(it, "comic")}" }) {
        it.finishedByType[BookType.Comic]?.toLong() ?: 0L
    },
    Achievement("manga", "Volume Collector", tiers(1, 25, 100, 500), goal = { "Finish ${plural(it, "manga volume")}" }) {
        it.finishedByType[BookType.Manga]?.toLong() ?: 0L
    },
    Achievement("manhwa", "Scroll Master", tiers(10, 100, 500, 1000), goal = { "Finish ${plural(it, "manhwa episode")}" }) {
        it.finishedByType[BookType.Manhwa]?.toLong() ?: 0L
    },
    Achievement("hours", "Time Well Spent", tiers(10, 100, 500, 1000), goal = { "Read for ${plural(it, "hour")}" }) { it.hours },
    Achievement("streak", "On a Roll", tiers(7, 30, 100, 365), goal = { "Read ${plural(it, "day")} in a row" }) { it.bestStreak },
    Achievement("series", "Series Finisher", tiers(1, 5, 20, 50), goal = { "Finish every volume of ${plural(it, "series", "series")}" }) { it.seriesFinished },
    Achievement("binge", "Binge", listOf(5L to Gem.Amethyst), goal = { "Finish $it from one series in a day" }) { it.binge },
    Achievement("omnivore", "Omnivore", listOf(4L to Gem.Sapphire), goal = { "Finish a book, a comic, a manga and a manhwa" }) {
        it.finishedByType.size.toLong()
    },
    Achievement("genres", "Genre Explorer", tiers(3, 10, 25, 50), goal = { "Finish books from ${plural(it, "genre")}" }) { it.genres },
    Achievement("night", "Night Owl", tiers(1, 10, 50, 100), goal = { "Read after midnight ${plural(it, "time")}" }) { it.nightOwl },
    Achievement("early", "Early Bird", tiers(1, 10, 50, 100), goal = { "Read before 8 AM ${plural(it, "time")}" }) { it.earlyBird },
    Achievement("lunch", "Lunch Break", tiers(5, 25, 100, 250), goal = { "Read at lunch on ${plural(it, "weekday")}" }) { it.lunchBreak },
    Achievement("marathon", "Marathon", tiers(3, 5, 8, 12), goal = { "Read ${plural(it, "hour")} in one day" }) { it.marathonHours },
    Achievement("highlights", "Highlighter", tiers(1, 10, 100, 500), goal = { "Make ${plural(it, "highlight")}" }) { it.highlights },
    Achievement("ratings", "Star Giver", tiers(1, 25, 100, 250), goal = { "Rate ${plural(it, "book")}" }) { it.ratings },
    Achievement("reviews", "Critic", tiers(1, 10, 50, 100), goal = { "Write ${plural(it, "review")}" }) { it.reviews },
    Achievement("rereads", "Comfort Read", tiers(2, 3, 5, 10), goal = { "Finish the same book ${plural(it, "time")}" }) { it.mostRereads },
    Achievement("shelves", "Shelf Maker", tiers(1, 3, 6, 10), goal = { "Make ${plural(it, "custom shelf", "custom shelves")}" }) { it.customShelves },
    Achievement("goals", "Goal Getter", tiers(1, 10, 50, 100), goal = { "Reach ${plural(it, "goal")}" }) { it.goalsReached },
    Achievement("midnight", "Midnight Finish", listOf(1L to Gem.Diamond), hidden = true, goal = { "Finish a book in the midnight hour" }) {
        it.midnightFinish
    },
    Achievement("quick", "Couldn't Put It Down", listOf(1L to Gem.Diamond), hidden = true, goal = { "Finish a book within a day of starting it" }) {
        it.quickFinish
    },
    Achievement("long", "The Long Haul", listOf(1L to Gem.Diamond), hidden = true, goal = { "Finish a series of 20 or more" }) {
        it.longSeriesFinished
    },
)

fun evaluateAchievements(history: ReadingHistory, state: LibraryState, zone: ZoneId = ZoneId.systemDefault()): List<AchievementStatus> {
    val facts = AchievementFacts(history, state, zone)
    return ACHIEVEMENTS.map { achievement ->
        val value = achievement.measure(facts)
        val tier = achievement.tiers.indexOfLast { value >= it.first }
        AchievementStatus(achievement, value, tier)
    }
}
