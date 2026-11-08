package com.tim.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Series
import com.tim.reader.data.volumeOrder

private sealed interface Found {
    val key: String

    class OneSeries(val series: Series, val volumes: List<Book>) : Found {
        override val key get() = "s" + series.id
    }

    class OneBook(val book: Book) : Found {
        override val key get() = "b" + book.id
    }
}

/** Searches titles, authors, series, and genres, putting series first so a whole run is one tap. */
private fun searchLibrary(state: LibraryState, query: String): List<Found> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val matches = state.books.filter { book ->
        val text = listOfNotNull(book.title, book.author, state.seriesFor(book.seriesId)?.title)
            .plus(book.info.genres)
            .joinToString(" ")
            .lowercase()
        words.all { it in text }
    }
    val series = matches.filter { it.seriesId != null }.groupBy { it.seriesId }.mapNotNull { (id, volumes) ->
        state.seriesFor(id)?.let { Found.OneSeries(it, volumes.sortedWith(volumeOrder)) }
    }.sortedBy { it.series.title.lowercase() }
    val single = matches.filter { book ->
        // A lone match inside a series is still shown, so searching one volume's title finds it.
        book.seriesId == null || series.none { it.series.id == book.seriesId } ||
            words.any { it in book.title.lowercase() && it !in state.seriesFor(book.seriesId)?.title.orEmpty().lowercase() }
    }.sortedBy { it.title.lowercase() }.map { Found.OneBook(it) }
    return series + single
}

/** Kept while the app runs, so coming back from a book shows the same results. */
private var lastQuery by mutableStateOf("")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibrarySearchScreen(state: LibraryState, actions: BookActions, onClose: () -> Unit) {
    var query by remember { mutableStateOf(lastQuery) }
    val results = remember(state.books, query) { searchLibrary(state, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (lastQuery.isBlank()) focus.requestFocus() }

    EditorTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(EditorColors.Panel)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        lastQuery = it
                    },
                    placeholder = { Text("Title, author, series, or genre") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                )
                EditorOutlineButton("Close", onClick = onClose)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(EditorColors.Card, RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                when {
                    query.isBlank() -> Text("Start typing to search your library.", color = EditorColors.Muted, modifier = Modifier.padding(8.dp))
                    results.isEmpty() -> Text("No matches.", color = EditorColors.Muted, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.key }) { found ->
                        when (found) {
                            is Found.OneSeries -> Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable { actions.onSeries(found.series.id) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SmallCover(found.volumes.first().displayCover)
                                Column(Modifier.weight(1f)) {
                                    Text(found.series.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Series, ${found.volumes.size} matching", color = EditorColors.Muted, fontSize = 13.sp)
                                }
                            }
                            is Found.OneBook -> Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onLongClick = { actions.onDetails(found.book) },
                                        onClick = { actions.onOpen(found.book) },
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SmallCover(found.book.displayCover)
                                Column(Modifier.weight(1f)) {
                                    Text(found.book.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val details = listOf(found.book.author, found.book.type.label).filter { it.isNotBlank() }
                                    Text(details.joinToString(" · "), color = EditorColors.Muted, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }
                        EditorDivider()
                    }
                }
            }
        }
    }
}
