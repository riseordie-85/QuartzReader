package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.appScope
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.library.DeviceSettings
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.Goal
import com.tim.reader.stats.GoalKind
import com.tim.reader.stats.GoalPeriod
import com.tim.reader.stats.GoalProgress
import com.tim.reader.stats.GoalUnit
import com.tim.reader.stats.SHORT_DATE
import com.tim.reader.stats.goalProgress
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** A ring that fills as a goal is reached. */
@Composable
fun GoalRing(fraction: Float, color: Color, size: Dp = 56.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.width * 0.12f
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** One goal: its ring, what it is, and how it's going. */
@Composable
fun GoalRow(look: BarLook, goal: Goal, progress: GoalProgress, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Row(
        if (onEdit != null) Modifier.clickable(onClickLabel = "Edit goal", onClick = onEdit) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GoalRing(progress.fraction, Color(look.iconColor))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(goal.describe(), color = Color(look.textColor), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Muted(look, progress.headline)
            Muted(look, progress.pace)
        }
        if (onRemove != null) {
            Muted(look, "Remove", Modifier.clickable(onClickLabel = "Remove goal", onClick = onRemove).padding(6.dp))
        }
    }
}

private suspend fun removeGoal(context: android.content.Context, goal: Goal) {
    LibraryRepository.get(context).addEvent(EventKind.GOAL, null, goal.id, System.currentTimeMillis(), null, JSONObject().put("removed", true))
}

@Composable
fun GoalsScreen(state: LibraryState, header: HeaderActions) {
    val theme = state.homeTheme ?: return
    val look = theme.header
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Goal?>(null) }
    val history = rememberHistory(version)
    val today = LocalDate.now()

    if (adding || editing != null) {
        GoalEditor(state, editing, onDismiss = { adding = false; editing = null }) { goal ->
            adding = false
            editing = null
            appScope.launch {
                LibraryRepository.get(context).addEvent(EventKind.GOAL, null, goal.id, System.currentTimeMillis(), null, goal.toJson())
                AchievementCenter.check(context)
                version++
            }
        }
    }

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
                    ThemedText("Goals", theme.title)
                }
            }
            val goals = history?.goals.orEmpty().map { it to goalProgress(it, history!!, state, today) }
            val (finished, active) = goals.partition { (goal, progress) -> goal.hasDeadline && (progress.over || progress.done) }
            if (history != null && goals.isEmpty()) {
                item {
                    Panel(look, "No goals yet") {
                        Body(look, "Set a goal for a week, a month or the year, finish a series by a date, or build a daily habit.")
                    }
                }
            }
            item {
                Panel(look, null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Body(look, "Show goals on Home", Modifier.weight(1f))
                        Switch(DeviceSettings.homeGoals, { DeviceSettings.updateHomeGoals(it) })
                    }
                }
            }
            items(active, key = { it.first.id }) { (goal, progress) ->
                Panel(look, null) {
                    GoalRow(look, goal, progress, onEdit = { editing = goal }) {
                        appScope.launch {
                            removeGoal(context, goal)
                            version++
                        }
                    }
                }
            }
            item {
                Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()) {
                    ShelfButton(
                        "Add a goal",
                        theme.button.copy(cornerDp = theme.header.cornerDp),
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        onClick = { adding = true },
                    )
                }
            }
            if (finished.isNotEmpty()) {
                item {
                    Panel(look, "Finished Goals") {
                        finished.forEach { (goal, progress) ->
                            GoalRow(look, goal, progress, onEdit = { editing = goal }) {
                                appScope.launch {
                                    removeGoal(context, goal)
                                    version++
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private val COUNT_UNITS = listOf(GoalUnit.Stories, GoalUnit.Books, GoalUnit.Comics, GoalUnit.Manga, GoalUnit.Manhwa, GoalUnit.Pages, GoalUnit.Hours)

/** Builds a new goal. Each kind asks only for what it needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditor(state: LibraryState, initial: Goal?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    // An edited goal keeps its ID and start, so it stays one goal and its pace still counts from when it was set.
    val id = remember(initial) { initial?.id ?: UUID.randomUUID().toString() }
    val createdAt = remember(initial) { initial?.createdAt ?: System.currentTimeMillis() }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: GoalKind.Count) }
    var unit by remember(initial) { mutableStateOf(initial?.takeIf { it.kind == GoalKind.Count }?.unit ?: GoalUnit.Books) }
    var target by remember(initial) { mutableStateOf(initial?.target?.toString() ?: "4") }
    var period by remember(initial) { mutableStateOf(initial?.period ?: GoalPeriod.Month) }
    var start by remember(initial) { mutableStateOf(initial?.start) }
    var end by remember(initial) { mutableStateOf(initial?.end) }
    var search by remember { mutableStateOf("") }
    var seriesTitle by remember(initial) { mutableStateOf(initial?.seriesTitle) }
    var bookHash by remember(initial) { mutableStateOf(initial?.bookHash) }
    var bookTitle by remember(initial) { mutableStateOf(initial?.bookTitle) }
    var shelfName by remember(initial) { mutableStateOf(initial?.shelfName) }
    var habitDays by remember(initial) { mutableStateOf(initial?.unit == GoalUnit.Days) }
    var pickingDate by remember { mutableStateOf<String?>(null) }

    pickingDate?.let { which ->
        DateDialog(onDismiss = { pickingDate = null }) { date ->
            if (which == "start") start = date else end = date
            pickingDate = null
        }
    }

    val number = target.toIntOrNull()?.takeIf { it > 0 }
    val draft: Goal? = when (kind) {
        GoalKind.Count -> number?.let {
            Goal(id, kind, unit, it, period, start.takeIf { period == GoalPeriod.Dates }, end.takeIf { period == GoalPeriod.Dates })
        }?.takeIf { period != GoalPeriod.Dates || (it.start != null && it.end != null && !it.end.isBefore(it.start)) }
        GoalKind.Shelf -> if (number != null && shelfName != null) {
            Goal(id, kind, GoalUnit.Books, number, period, start.takeIf { period == GoalPeriod.Dates }, end.takeIf { period == GoalPeriod.Dates }, shelfName = shelfName)
        } else {
            null
        }
        GoalKind.Series -> seriesTitle?.let { Goal(id, kind, end = end, seriesTitle = it) }
        GoalKind.Book -> bookHash?.let { Goal(id, kind, end = end, bookHash = it, bookTitle = bookTitle) }
        GoalKind.Habit -> number?.let {
            Goal(id, kind, if (habitDays) GoalUnit.Days else GoalUnit.Minutes, if (habitDays) it.coerceAtMost(7) else it)
        }
    }
    val goal = draft?.copy(createdAt = createdAt)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New goal" else "Edit goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoalKind.entries.forEach { option ->
                        FilterChip(kind == option, {
                            kind = option
                            target = when (option) {
                                GoalKind.Habit -> if (habitDays) "5" else "20"
                                GoalKind.Shelf -> "5"
                                else -> "4"
                            }
                        }, label = { Text(option.label) })
                    }
                }
                when (kind) {
                    GoalKind.Count -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            COUNT_UNITS.forEach { option ->
                                FilterChip(unit == option, { unit = option }, label = { Text(option.many.replaceFirstChar { it.uppercase() }) })
                            }
                        }
                        NumberField(target, if (unit == GoalUnit.Hours) "Hours" else "How many") { target = it }
                        PeriodChoice(period, start, end, { period = it }) { pickingDate = it }
                    }
                    GoalKind.Shelf -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.shelves.forEach { shelf ->
                                FilterChip(shelfName == shelf.name, { shelfName = shelf.name }, label = { Text(shelf.name) })
                            }
                        }
                        NumberField(target, "How many books") { target = it }
                        PeriodChoice(period, start, end, { period = it }) { pickingDate = it }
                    }
                    GoalKind.Series -> {
                        Picker(
                            search,
                            "Series",
                            seriesTitle,
                            state.series.map { it.title }.distinctBy { it.lowercase() },
                            { search = it },
                        ) { seriesTitle = it }
                        DeadlineButton(end) { pickingDate = "end" }
                    }
                    GoalKind.Book -> {
                        // Books sharing a title are told apart by author.
                        val titles = state.books.groupBy { it.title.lowercase() }.values.flatMap { same ->
                            same.map { book -> (if (same.size > 1) "${book.title} · ${book.author}" else book.title) to book.hash }
                        }.toMap()
                        Picker(search, "Book", bookTitle, titles.keys.toList(), { search = it }) { title ->
                            bookTitle = title
                            bookHash = titles[title]
                        }
                        DeadlineButton(end) { pickingDate = "end" }
                    }
                    GoalKind.Habit -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(!habitDays, { habitDays = false; target = "20" }, label = { Text("Minutes a day") })
                            FilterChip(habitDays, { habitDays = true; target = "5" }, label = { Text("Days a week") })
                        }
                        NumberField(target, if (habitDays) "Days a week" else "Minutes a day") { target = it }
                    }
                }
                goal?.let { Text(it.describe(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = { TextButton({ goal?.let(onSave) }, enabled = goal != null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onChange(it.filter(Char::isDigit).take(5)) },
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodChoice(period: GoalPeriod, start: LocalDate?, end: LocalDate?, onPeriod: (GoalPeriod) -> Unit, onPickDate: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GoalPeriod.entries.forEach { option ->
            FilterChip(period == option, { onPeriod(option) }, label = { Text(option.label.replaceFirstChar { it.uppercase() }) })
        }
    }
    if (period == GoalPeriod.Dates) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ onPickDate("start") }) { Text(start?.let(SHORT_DATE::format) ?: "From") }
            OutlinedButton({ onPickDate("end") }) { Text(end?.let(SHORT_DATE::format) ?: "To") }
        }
    }
}

@Composable
private fun DeadlineButton(end: LocalDate?, onPick: () -> Unit) {
    OutlinedButton(onPick) { Text(end?.let { "By ${SHORT_DATE.format(it)}" } ?: "Pick a date (optional)") }
}

/** A search box with matching choices underneath. */
@Composable
private fun Picker(search: String, label: String, chosen: String?, options: List<String>, onSearch: (String) -> Unit, onChoose: (String) -> Unit) {
    OutlinedTextField(search, onSearch, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
    if (chosen != null) Text("Chosen: $chosen", style = MaterialTheme.typography.bodySmall)
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        options.filter { search.isBlank() || it.contains(search.trim(), ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .take(30)
            .forEach { option ->
                Text(
                    option,
                    fontWeight = if (option == chosen) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(option) }
                        .padding(vertical = 8.dp),
                )
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(picker)
    }
}

/** A strip of goal rings for Home, shown when switched on from the Goals page. */
@Composable
fun HomeGoalsStrip(state: LibraryState, look: BarLook, onGoals: () -> Unit) {
    // Reloads after reading in the EPUB reader, which leaves Home open behind it.
    val history = rememberHistory(state.books.size to rememberResumeCount()) ?: return
    val today = LocalDate.now()
    val goals = history.goals.map { it to goalProgress(it, history, state, today) }
        .filterNot { (goal, progress) -> goal.hasDeadline && (progress.over || progress.done) }
        .take(3)
    if (goals.isEmpty()) return
    Box(Modifier.fillMaxWidth().clickable(onClickLabel = "Open goals", onClick = onGoals)) {
        Panel(look, null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                goals.forEach { (goal, progress) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GoalRing(progress.fraction, Color(look.iconColor), 36.dp)
                        Column {
                            Muted(look, goal.describe().removePrefix("Read "), maxLines = 2)
                            Muted(look, progress.headline)
                        }
                    }
                }
            }
        }
    }
}
