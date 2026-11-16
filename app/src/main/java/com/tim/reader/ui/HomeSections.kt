package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.library.DeviceSettings
import com.tim.reader.stats.AchievementStatus
import com.tim.reader.stats.Goal
import com.tim.reader.stats.GoalProgress
import com.tim.reader.stats.Stats
import com.tim.reader.stats.computeStats
import com.tim.reader.stats.evaluateAchievements
import com.tim.reader.stats.goalProgress
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedStroke
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The parts Home can show, in any order. A shelf of the reader's own is "shelf:" and its ID. */
enum class HomeSection(val key: String, val label: String, val isStats: Boolean = false) {
    // Goals load their own history, so they don't need the stats worked out.
    Goals("goals", "Goals"),
    RecentlyRead("recent", "Recently Read"),
    ReadList("readList", "Read List"),
    ShelfButtons("shelves", "Shelf buttons"),
    ReadingTime("time", "Today, this week, this year, streak", isStats = true),
    Achievements("achievements", "Achievements", isStats = true),
    ReadingDays("days", "Reading days", isStats = true),
    FinishedByType("byType", "Finished by type", isStats = true),
    FinishedByWeekday("byWeekday", "Finished by weekday", isStats = true),
    FinishedThisYear("year", "Finished this year", isStats = true),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

const val SHELF_SECTION = "shelf:"

/** What Home shows until the reader changes it: the mockup's layout, with goals when they're on. */
fun homeSections(state: LibraryState): List<String> {
    val chosen = DeviceSettings.homeSections ?: listOf(
        HomeSection.Goals,
        HomeSection.RecentlyRead,
        HomeSection.ReadList,
        HomeSection.ReadingDays,
        HomeSection.ShelfButtons,
    ).filter { it != HomeSection.Goals || DeviceSettings.homeGoals }.map { it.key }
    // A shelf that's been removed drops out without changing the saved list.
    return chosen.distinct().filter { key ->
        HomeSection.of(key) != null || (key.startsWith(SHELF_SECTION) && state.shelf(key.removePrefix(SHELF_SECTION)) != null)
    }
}

fun sectionLabel(key: String, state: LibraryState): String =
    HomeSection.of(key)?.label ?: state.shelf(key.removePrefix(SHELF_SECTION))?.name ?: key

/** Everything Home's stats sections show, worked out away from the main thread. */
class HomeStats(
    val stats: Stats,
    val goals: List<Pair<Goal, GoalProgress>>,
    val achievements: List<AchievementStatus>,
)

@Composable
fun rememberHomeStats(state: LibraryState, wanted: Boolean): HomeStats? {
    val context = LocalContext.current
    // Reloads after reading, which leaves Home open behind the reader.
    val resumes = rememberResumeCount()
    val loaded by produceState<HomeStats?>(null, wanted, state.books.size, resumes) {
        if (!wanted) return@produceState
        val history = LibraryRepository.get(context).readingHistory()
        value = withContext(Dispatchers.Default) {
            val today = LocalDate.now()
            HomeStats(
                stats = computeStats(history, state.books),
                goals = history.goals.map { it to goalProgress(it, history, state, today) }
                    .filterNot { (goal, progress) -> goal.hasDeadline && (progress.over || progress.done) },
                achievements = evaluateAchievements(history, state),
            )
        }
    }
    return loaded
}

/**
 * Home in edit mode: each section as a row to drag by its handle or remove, then the ones not
 * on Home to add back. Changes save as they're made.
 */
@Composable
fun HomeEditor(state: LibraryState, look: BarLook, onDone: () -> Unit) {
    val sections = homeSections(state)
    val hidden = HomeSection.entries.map { it.key }.filter { it !in sections } +
        state.shelves.map { SHELF_SECTION + it.id }.filter { it !in sections }
    val text = Color(look.textColor)
    val muted = text.copy(alpha = 0.7f)
    val shape = cornerShape(look.cornerDp.coerceAtMost(24f))
    // Read fresh each time, since a drag swaps places faster than the screen redraws.
    val latestState by rememberUpdatedState(state)
    fun current() = homeSections(latestState)

    fun move(from: Int, to: Int) {
        val list = current().toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        DeviceSettings.updateHomeSections(list)
    }

    Column(
        Modifier
            .widthIn(max = MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit Home", color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // The button is filled with the card's text color, so its word takes a contrasting one.
            Text(
                "Done",
                color = contrastOn(text),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .background(text, cornerShape(18f))
                    .clickable(onClickLabel = "Finish editing Home", onClick = onDone)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        Text("Drag a section by its handle to move it.", color = muted, fontSize = 14.sp)

        var dragging by remember { mutableStateOf<String?>(null) }
        var offset by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableIntStateOf(0) }
        sections.forEachIndexed { index, section ->
            // Keyed, so the row being dragged keeps its gesture as it changes places.
            key(section) {
            val isDragging = dragging == section
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) offset else 0f
                        shadowElevation = if (isDragging) 8f else 0f
                    }
                    .onSizeChanged { if (index == 0) rowHeight = it.height }
                    .background(muted.copy(alpha = if (isDragging) 0.22f else 0.1f), cornerShape(14f))
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { move(index, index - 1); true }.takeIf { index > 0 },
                            CustomAccessibilityAction("Move down") { move(index, index + 1); true }.takeIf { index < sections.lastIndex },
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pointerInput(section) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = section
                                    offset = 0f
                                },
                                onDragEnd = {
                                    dragging = null
                                    offset = 0f
                                },
                                onDragCancel = {
                                    dragging = null
                                    offset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                                // Rows are all one height plus the gap, so passing half of one swaps places.
                                val step = rowHeight + 8.dp.toPx()
                                val at = current().indexOf(section)
                                if (step > 0f && offset > step / 2 && at < current().lastIndex) {
                                    move(at, at + 1)
                                    offset -= step
                                } else if (step > 0f && offset < -step / 2 && at > 0) {
                                    move(at, at - 1)
                                    offset += step
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Handle, text, Modifier.size(22.dp))
                }
                Text(
                    sectionLabel(section, state),
                    color = text,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlyphButton(Glyph.Close, muted, "Remove ${sectionLabel(section, state)}", iconSize = 16.dp) {
                    DeviceSettings.updateHomeSections(current() - section)
                }
            }
            }
        }

        if (hidden.isNotEmpty()) {
            Text("Add to Home", color = text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            hidden.forEach { key ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Add ${sectionLabel(key, state)}") { DeviceSettings.updateHomeSections(current() + key) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        GlyphIcon(Glyph.Plus, text, Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(sectionLabel(key, state), color = text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (key.startsWith(SHELF_SECTION)) Text("A row of this shelf's books", color = muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Black or white, whichever reads better on [color]. */
internal fun contrastOn(color: Color): Color {
    val light = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (light > 0.55f) Color.Black else Color.White
}
