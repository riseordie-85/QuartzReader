package com.tim.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryRepository
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.FinishedRecord
import com.tim.reader.stats.ReadingHistory
import com.tim.reader.stats.formatMinutes
import com.tim.reader.stats.openReadStart
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val LONG_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatDay(time: Long) = LONG_DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

/** Every read of this book with its dates and time, plus Mark as read. [onChanged] reloads the history. */
@Composable
fun ReadsSection(book: Book, history: ReadingHistory, onChanged: () -> Unit) {
    val context = LocalContext.current
    val repository = LibraryRepository.get(context)
    var marking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<FinishedRecord?>(null) }
    val reads = history.finished.filter { it.bookHash == book.hash }.sortedByDescending { it.at }
    val sessions = history.sessions.filter { it.bookHash == book.hash }
    val total = sessions.sumOf { it.activeMs } / 60_000

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Reads", style = MaterialTheme.typography.titleMedium)
        if (total > 0) Text("${formatMinutes(total)} reading this book in all", style = MaterialTheme.typography.bodyMedium)
        history.openReadStart(book.hash)?.let { Text("Reading since ${formatDay(it)}") }
        reads.forEach { read ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(describe(read, history), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (read.readId != null) TextButton({ removing = read }) { Text("Remove") }
            }
        }
        if (reads.isEmpty() && total == 0L) Text("Not read yet", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton({ marking = true }) { Text("Mark as read") }
    }

    if (marking) {
        MarkAsReadDialog(onDismiss = { marking = false }) { at, unknown ->
            marking = false
            appScope.launch {
                repository.markAsRead(book, at, unknown)
                AchievementCenter.check(context)
                onChanged()
            }
        }
    }
    removing?.let { read ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this read?") },
            text = { Text("It comes off your stats and goals. Your reading time stays.") },
            confirmButton = {
                TextButton({
                    removing = null
                    val id = read.readId ?: return@TextButton
                    appScope.launch {
                        repository.deleteRead(id)
                        onChanged()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

private fun describe(read: FinishedRecord, history: ReadingHistory): String {
    val from = if (read.source == "goodreads") " (from Goodreads)" else ""
    if (read.dateUnknown) return "Read, date unknown$from"
    if (read.source == "goodreads") return "Read on ${formatDay(read.at)}$from"
    if (read.manual) return "Marked as read on ${formatDay(read.at)}"
    val start = read.readId?.let(history.readStarts::get)
    val minutes = history.sessions
        .filter { it.bookHash == read.bookHash && it.start >= (start ?: 0L) && it.start <= read.at }
        .sumOf { it.activeMs } / 60_000
    val dates = if (start == null || formatDay(start) == formatDay(read.at)) formatDay(read.at) else "${formatDay(start)} to ${formatDay(read.at)}"
    return if (minutes > 0) "$dates · ${formatMinutes(minutes)}" else dates
}

/** Days up to today, since a book can't be finished in the future. */
@OptIn(ExperimentalMaterial3Api::class)
private object PastDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()

    override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
}

/** Today, a chosen day, or "I don't remember". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkAsReadDialog(onDismiss: () -> Unit, onMark: (at: Long, dateUnknown: Boolean) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val picker = rememberDatePickerState(selectableDates = PastDates)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        // The picker gives midnight UTC. Noon on that day, here, keeps the date the same everywhere.
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onMark(day.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), false)
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When did you finish it?") },
        text = {
            Column {
                TextButton({ onMark(System.currentTimeMillis(), false) }) { Text("Today") }
                TextButton({ picking = true }) { Text("Pick a date") }
                TextButton({ onMark(System.currentTimeMillis(), true) }) { Text("I don't remember") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
