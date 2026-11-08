package com.tim.reader.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.EventKind
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.library.rememberCover
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.Rating
import com.tim.reader.stats.ReadingHistory
import com.tim.reader.stats.formatMinutes
import com.tim.reader.stats.seriesKey
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val FINISHED = 0.999f

/** Saves a book's rating, or removes it when [rating] is null. */
suspend fun saveBookRating(context: Context, book: Book, rating: Rating?) {
    val data = (rating ?: Rating(0f)).toJson()
    LibraryRepository.get(context).addEvent(EventKind.RATING, book, null, System.currentTimeMillis(), null, data)
    AchievementCenter.check(context)
}

/** Saves a series rating, matched by the series title. */
suspend fun saveSeriesRating(context: Context, title: String, rating: Rating?) {
    val data = (rating ?: Rating(0f)).toJson().put("series", title)
    LibraryRepository.get(context).addEvent(EventKind.SERIES_RATING, null, null, System.currentTimeMillis(), null, data)
    AchievementCenter.check(context)
}

/** Stars, a short review and a spoiler switch, with Save once something changes. */
@Composable
fun RatingEditor(saved: Rating?, onSave: (Rating?) -> Unit) {
    var stars by remember(saved) { mutableStateOf(saved?.stars ?: 0f) }
    var review by remember(saved) { mutableStateOf(saved?.review.orEmpty()) }
    var spoiler by remember(saved) { mutableStateOf(saved?.spoiler ?: false) }
    val changed = stars != (saved?.stars ?: 0f) || review != saved?.review.orEmpty() || spoiler != (saved?.spoiler ?: false)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your rating", style = MaterialTheme.typography.titleMedium)
        StarRating(stars, MaterialTheme.colorScheme.primary) { stars = it }
        OutlinedTextField(
            review,
            { review = it },
            Modifier.fillMaxWidth(),
            label = { Text("Short review") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 2,
            maxLines = 6,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(spoiler, { spoiler = it })
            Text("Contains spoilers")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onSave(Rating(stars, review.trim(), spoiler)) }, enabled = changed && stars > 0f) { Text("Save rating") }
            if (saved != null) TextButton({ onSave(null) }) { Text("Remove rating") }
        }
        if (changed && stars == 0f) Text("Tap the stars to rate", style = MaterialTheme.typography.bodySmall)
    }
}

/** The history, loaded once for a screen and again when [key] changes. */
@Composable
fun rememberHistory(key: Any?): ReadingHistory? {
    val context = LocalContext.current
    val history by produceState<ReadingHistory?>(null, key) {
        value = LibraryRepository.get(context).readingHistory()
    }
    return history
}

/**
 * Shown back in the library after a book is finished. Volumes in a series only ask once the
 * whole series is read, and then rate the series rather than each volume.
 */
@Composable
fun FinishedCard(bookId: String, state: LibraryState, onDone: () -> Unit) {
    val context = LocalContext.current
    val book = state.book(bookId)
    val history = rememberHistory(bookId)
    if (book == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    history ?: return

    val inSeries = book.seriesId != null && book.type != BookType.Book
    val seriesTitle = state.seriesFor(book.seriesId)?.title
    if (inSeries) {
        val finishedHashes = history.finished.mapNotNull { it.bookHash }.toSet()
        val allRead = state.books.filter { it.seriesId == book.seriesId }
            .all { it.id == book.id || it.progress >= FINISHED || it.hash in finishedHashes }
        if (!allRead || seriesTitle == null) {
            LaunchedEffect(Unit) { onDone() }
            return
        }
    }
    val saved = if (inSeries) history.seriesRatings[seriesKey(seriesTitle.orEmpty())] else history.ratings[book.hash]

    // This read's time: from when it began to now.
    val lastRead = history.finished.lastOrNull { it.bookHash == book.hash }
    val began = lastRead?.readId?.let(history.readStarts::get)
    val minutes = history.sessions
        .filter { it.bookHash == book.hash && (began == null || it.start >= began) }
        .sumOf { it.activeMs } / 60_000
    val days = if (began != null && lastRead != null) {
        ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(began).atZone(ZoneId.systemDefault()).toLocalDate(),
            Instant.ofEpochMilli(lastRead.at).atZone(ZoneId.systemDefault()).toLocalDate(),
        ) + 1
    } else {
        null
    }
    val cover by rememberCover(book.displayCover)
    var sharing by remember { mutableStateOf(false) }
    if (sharing) {
        StatsShareOverlay(
            StatsCard.Finished(book, seriesTitle.takeIf { inSeries }, saved, minutes, days),
            state,
        ) { sharing = false }
    }

    Dialog(onDismissRequest = onDone) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (inSeries) "Caught up!" else "Finished!", style = MaterialTheme.typography.headlineMedium)
                cover?.let { Image(it, contentDescription = null, modifier = Modifier.width(120.dp)) }
                Text(if (inSeries) seriesTitle.orEmpty() else book.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (minutes > 0) {
                    val over = days?.takeIf { it > 1 }?.let { " over $it days" }.orEmpty()
                    Text("Read in ${formatMinutes(minutes)}$over", style = MaterialTheme.typography.bodyMedium)
                }
                RatingEditor(saved) { rating ->
                    appScope.launch {
                        if (inSeries) saveSeriesRating(context, seriesTitle.orEmpty(), rating) else saveBookRating(context, book, rating)
                    }
                    onDone()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ sharing = true }) { Text("Share") }
                    TextButton(onDone) { Text("Not now") }
                }
            }
        }
    }
}

/** Counts the times this screen's activity comes back, so a screen left open behind a reader can reload. */
@Composable
fun rememberResumeCount(): Int {
    val owner = LocalActivity.current as? ComponentActivity
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        owner?.lifecycle?.addObserver(watcher)
        onDispose { owner?.lifecycle?.removeObserver(watcher) }
    }
    return count
}
