package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import com.tim.reader.stats.goalProgress
import com.tim.reader.stats.evaluateAchievements
import com.tim.reader.stats.GoalProgress
import com.tim.reader.stats.Goal
import com.tim.reader.stats.AchievementStatus
import com.tim.reader.stats.AchievementCenter
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import java.time.YearMonth
import com.tim.reader.stats.yearRecap
import com.tim.reader.stats.monthRecap
import com.tim.reader.stats.ReadingHistory
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.stats.Stats
import com.tim.reader.stats.computeStats
import com.tim.reader.stats.firstDayOfWeek
import com.tim.reader.stats.formatMinutes
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val MAX_WEEKS = 53
private val CELL = 13.dp
private val CELL_GAP = 3.dp
internal val MAX_WIDTH = 760.dp
private val BAR_HEIGHT = 70.dp

/** Reading stats, drawn in the Home style. */
@Composable
fun StatsScreen(state: LibraryState, header: HeaderActions, onGoals: () -> Unit = {}, onAchievements: () -> Unit = {}) {
    val theme = state.homeTheme ?: return
    val context = LocalContext.current
    LaunchedEffect(Unit) { AchievementCenter.check(context) }
    // History doesn't change while the page is open, so it only reloads when books come or go.
    val page by produceState<StatsPage?>(null, state.books.size) {
        val repository = LibraryRepository.get(context)
        val history = repository.readingHistory()
        value = withContext(Dispatchers.Default) {
            val today = LocalDate.now()
            StatsPage(
                stats = computeStats(history, state.books),
                goals = history.goals.map { it to goalProgress(it, history, state, today) }
                    .filterNot { (goal, progress) -> goal.hasDeadline && (progress.over || progress.done) },
                achievements = evaluateAchievements(history, state),
                history = history,
            )
        }
    }
    val stats = page?.stats
    val look = theme.header

    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { LibraryHeader(look, header) }
            item {
                Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    ThemedText("Your Reading", theme.title)
                }
            }
            val current = stats
            when {
                current == null -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(look.iconColor))
                    }
                }
                current.isEmpty -> {
                    item {
                        Panel(look, "Nothing yet") {
                            Body(
                                look,
                                "Start reading and your stats show up here. Time counts while you're turning pages, " +
                                    "so a book left open doesn't add hours.",
                            )
                        }
                    }
                    item { GoalsPanel(look, page?.goals.orEmpty(), onGoals) }
                    item { AchievementsPanel(look, page?.achievements.orEmpty(), onAchievements) }
                }
                else -> {
                    item { Summary(look, current) }
                    item { GoalsPanel(look, page?.goals.orEmpty(), onGoals) }
                    item { AchievementsPanel(look, page?.achievements.orEmpty(), onAchievements) }
                    item { Calendar(look, current) }
                    if (current.byType.isNotEmpty()) item { ByType(look, current) }
                    item { FinishedByMonth(look, current) }
                    item { TimeOfDay(look, current) }
                    if (current.recentlyFinished.isNotEmpty()) item { RecentlyFinished(look, current) }
                    page?.history?.let { history -> item { SharePanel(look, history, state) } }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private class StatsPage(
    val stats: Stats,
    val goals: List<Pair<Goal, GoalProgress>>,
    val achievements: List<AchievementStatus>,
    val history: ReadingHistory,
)

@Composable
private fun SharePanel(look: BarLook, history: ReadingHistory, state: LibraryState) {
    var card by remember { mutableStateOf<StatsCard?>(null) }
    val today = LocalDate.now()
    Panel(look, "Share") {
        Body(look, "Make an image of your month or your year to share or save.")
        PanelLink(look, "This month") { card = StatsCard.Month(monthRecap(history, YearMonth.from(today))) }
        PanelLink(look, if (today.monthValue == 12) "${today.year} in Review" else "${today.year} so far") {
            card = StatsCard.Year(yearRecap(history, state, today.year))
        }
    }
    card?.let { StatsShareOverlay(it, state) { card = null } }
}

@Composable
private fun GoalsPanel(look: BarLook, goals: List<Pair<Goal, GoalProgress>>, onGoals: () -> Unit) {
    Panel(look, "Goals") {
        if (goals.isEmpty()) Body(look, "Set a goal for the week, month or year, a series to finish, or a daily habit.")
        goals.take(3).forEach { (goal, progress) -> GoalRow(look, goal, progress) }
        PanelLink(look, if (goals.isEmpty()) "Add a goal" else "All goals", onGoals)
    }
}

@Composable
private fun AchievementsPanel(look: BarLook, statuses: List<AchievementStatus>, onAchievements: () -> Unit) {
    val unlocked = statuses.filter { it.tier >= 0 }
    Panel(look, "Achievements") {
        Body(look, "${unlocked.size} of ${statuses.size} unlocked")
        if (unlocked.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                unlocked.sortedByDescending { it.tier }.take(8).forEach { GemIcon(it.gem, 30.dp) }
            }
        }
        PanelLink(look, "See all", onAchievements)
    }
}

@Composable
private fun PanelLink(look: BarLook, text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color(look.iconColor),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** A card in the header's style, so stats match the shelf they're shown over. */
@Composable
internal fun Panel(look: BarLook, title: String?, outer: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = cornerShape(look.cornerDp)
    Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(horizontal = outer, vertical = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .themedShadow(look.shadow, shape)
                .themedFill(look.fill, shape)
                .themedStroke(look.stroke, shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) Text(title, color = Color(look.textColor), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun Body(look: BarLook, text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(look.textColor), fontSize = 15.sp, modifier = modifier)
}

@Composable
internal fun Muted(look: BarLook, text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        color = Color(look.textColor).copy(alpha = 0.7f),
        fontSize = 13.sp,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Summary(look: BarLook, stats: Stats) {
    val tiles = listOf(
        "Today" to formatMinutes(stats.todayMinutes),
        "This week" to formatMinutes(stats.weekMinutes),
        "This year" to formatMinutes(stats.yearMinutes),
        "Streak" to if (stats.streak == 1) "1 day" else "${stats.streak} days",
    )
    BoxWithConstraints(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()) {
        val perRow = if (maxWidth >= 560.dp) 4 else 2
        Column {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    row.forEach { (label, value) ->
                        Box(Modifier.weight(1f)) {
                            Panel(look, null, outer = 6.dp) {
                                Muted(look, label)
                                // Long totals like "123 h 45 m" get smaller instead of cut off.
                                Text(
                                    value,
                                    color = Color(look.textColor),
                                    fontSize = if (value.length > 7) 18.sp else 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Days shaded by minutes read, a column per week, ending this week. */
@Composable
private fun Calendar(look: BarLook, stats: Stats) {
    val accent = Color(look.iconColor)
    Panel(look, "Reading Days") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val weeks = ((maxWidth + CELL_GAP) / (CELL + CELL_GAP)).toInt().coerceIn(1, MAX_WEEKS)
            val lastWeek = stats.today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()))
            val first = lastWeek.minusWeeks((weeks - 1).toLong())
            Canvas(Modifier.fillMaxWidth().height(CELL * 7 + CELL_GAP * 6)) {
                val cell = with(density) { CELL.toPx() }
                val gap = with(density) { CELL_GAP.toPx() }
                val radius = CornerRadius(cell / 4)
                for (week in 0 until weeks) {
                    for (dayIndex in 0 until 7) {
                        val date = first.plusDays(week * 7L + dayIndex)
                        if (date.isAfter(stats.today)) continue
                        val minutes = stats.minutesByDay[date] ?: 0L
                        drawRoundRect(
                            color = accent.copy(alpha = shade(minutes)),
                            topLeft = Offset(week * (cell + gap), dayIndex * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Muted(look, "Best streak ${stats.bestStreak} ${if (stats.bestStreak == 1) "day" else "days"}", Modifier.weight(1f))
            Muted(look, "Less")
            listOf(0L, 10L, 20L, 45L, 90L).forEach { minutes ->
                Box(Modifier.size(10.dp).background(accent.copy(alpha = shade(minutes)), RoundedCornerShape(2.dp)))
            }
            Muted(look, "More")
        }
    }
}

private fun shade(minutes: Long) = when {
    minutes <= 0 -> 0.12f
    minutes < 15 -> 0.35f
    minutes < 30 -> 0.55f
    minutes < 60 -> 0.78f
    else -> 1f
}

@Composable
internal fun Bar(look: BarLook, fraction: Float, modifier: Modifier = Modifier) {
    val accent = Color(look.iconColor)
    Box(modifier.height(10.dp).background(accent.copy(alpha = 0.15f), RoundedCornerShape(5.dp))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(accent, RoundedCornerShape(5.dp)),
        )
    }
}

@Composable
private fun ByType(look: BarLook, stats: Stats) {
    val most = stats.byType.maxOf { it.minutes }.coerceAtLeast(1)
    Panel(look, "By Type") {
        stats.byType.forEach { type ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Body(look, type.type.folderName, Modifier.weight(1f))
                    Muted(look, formatMinutes(type.minutes))
                }
                Bar(look, type.minutes.toFloat() / most, Modifier.fillMaxWidth())
                val details = buildList {
                    if (type.finished > 0) add("${type.finished} finished")
                    if (type.words > 0) add("${"%,d".format(type.words)} words")
                    if (type.pages > 0) add("${"%,d".format(type.pages)} pages")
                    type.speed?.let { speed ->
                        add(if (type.words > 0) "$speed words a minute" else "$speed seconds a page")
                    }
                }
                if (details.isNotEmpty()) Muted(look, details.joinToString(" · "), maxLines = 2)
            }
        }
    }
}

@Composable
private fun FinishedByMonth(look: BarLook, stats: Stats) {
    val accent = Color(look.iconColor)
    val most = stats.finishedByMonth.maxOf { it.second }.coerceAtLeast(1)
    Panel(look, "Finished") {
        Body(look, "${stats.finishedThisYear} this year · ${stats.finishedTotal} in all")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            stats.finishedByMonth.forEach { (month, count) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Muted(look, if (count > 0) "$count" else " ")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(BAR_HEIGHT * maxOf(count.toFloat() / most, 0.04f))
                            .background(if (count > 0) accent else accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
                    )
                    Muted(look, month.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                }
            }
        }
    }
}

@Composable
private fun TimeOfDay(look: BarLook, stats: Stats) {
    val most = stats.timeOfDay.maxOf { it.second }.coerceAtLeast(1)
    Panel(look, "When You Read") {
        stats.timeOfDay.forEach { (label, minutes) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Body(look, label, Modifier.widthIn(min = 92.dp))
                Bar(look, minutes.toFloat() / most, Modifier.weight(1f))
                Muted(look, formatMinutes(minutes), Modifier.widthIn(min = 64.dp))
            }
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
private fun RecentlyFinished(look: BarLook, stats: Stats) {
    Panel(look, "Recently Finished") {
        stats.recentlyFinished.forEach { finished ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    finished.book?.title ?: finished.title ?: "A book no longer in your library",
                    color = Color(look.textColor),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val date = Instant.ofEpochMilli(finished.at).atZone(ZoneId.systemDefault()).toLocalDate()
                Muted(look, if (date == LocalDate.now()) "Today" else DATE.format(date), Modifier.padding(start = 10.dp))
            }
        }
    }
}
