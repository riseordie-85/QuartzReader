package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryState
import com.tim.reader.data.ShelfItem
import com.tim.reader.library.Importer
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke

/** The book whose details show over the page after one tap on its cover, when there's no split view. */
class BookPeek {
    var bookId by mutableStateOf<String?>(null)
}

/** Null where a tap should open a book straight away. */
val LocalBookPeek = compositionLocalOf<BookPeek?> { null }

/** The whole library, for parts deep in a page that need more than the book, like its series' name. */
val LocalLibraryState = compositionLocalOf<LibraryState?> { null }

/** How far along a book is, in a few words. */
fun progressLabel(book: Book): String {
    val percent = (book.progress * 100).toInt()
    return when {
        book.lastReadAt == null && book.progress <= 0f -> "Not started"
        percent >= 100 -> "Finished"
        else -> "$percent% read"
    }
}

/** Short lines about a book: who wrote it, its series, what kind of book it is, and how far along. */
fun bookFacts(book: Book, state: LibraryState?): List<String> {
    val series = state?.seriesFor(book.seriesId)?.title
    val volume = book.seriesNumber?.let { "Volume ${formatNumber(it)}" }
    val kind = listOfNotNull(
        book.type.label,
        book.format.takeIf { it.isNotBlank() && it != Importer.FOLDER_FORMAT && it != "UNKNOWN" },
        book.info.year.takeIf { it.isNotBlank() },
    )
    return listOfNotNull(
        book.author.takeIf { it.isNotBlank() },
        listOfNotNull(series, volume).joinToString(" · ").takeIf { it.isNotBlank() },
        kind.joinToString(" · "),
        progressLabel(book),
    )
}

/** "Continue" for a book that's been started, or "Open". */
private fun openLabel(book: Book) = if (book.lastReadAt != null && book.progress < 0.995f) "Continue" else "Open"

/** A rounded button in a card's text color: filled for the main action, outlined for the rest. */
@Composable
fun CardButton(text: String, color: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = cornerShape(18f)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(if (filled) color else Color.Transparent, shape)
            .border(2.dp, color, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) contrastOn(color) else color,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The split view's details under the large cover: the title, its facts and a few lines of its
 * description, with buttons to open it or see everything about it.
 */
@Composable
fun CoverInfoCard(item: ShelfItem, look: BarLook, actions: BookActions, modifier: Modifier = Modifier) {
    val state = LocalLibraryState.current
    val text = Color(look.textColor)
    val muted = text.copy(alpha = 0.72f)
    val shape = cornerShape(look.cornerDp.coerceAtMost(28f))
    Column(
        modifier
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            item.displayTitle(),
            color = text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = when (item) {
            is ShelfItem.Single -> bookFacts(item.book, state)
            is ShelfItem.Stack -> listOfNotNull(
                item.volumes.firstOrNull()?.author?.takeIf { it.isNotBlank() },
                "${item.volumes.size} volumes",
            )
        }
        facts.forEach { line ->
            Text(line, color = muted, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val description = (item as? ShelfItem.Single)?.book?.info?.description.orEmpty()
        if (description.isNotBlank()) {
            Text(
                description,
                color = text,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (item) {
                is ShelfItem.Single -> {
                    CardButton("Details", text, filled = false, modifier = Modifier.weight(1f)) { actions.onDetails(item.book) }
                    CardButton(openLabel(item.book), text, filled = true, modifier = Modifier.weight(1f)) { item.activate(actions) }
                }
                is ShelfItem.Stack -> {
                    CardButton("Edit series", text, filled = false, modifier = Modifier.weight(1f)) { actions.onSeriesEdit(item.series.id) }
                    CardButton("Open series", text, filled = true, modifier = Modifier.weight(1f)) { item.activate(actions) }
                }
            }
        }
    }
}

/**
 * A book's cover and details over the page, after one tap on it. Tapping the cover or Open reads
 * it, and Details goes to everything about it.
 */
@Composable
fun BookPeekDialog(book: Book, state: LibraryState, theme: ShelfTheme, actions: BookActions, onDismiss: () -> Unit) {
    val look = theme.cardLook
    val text = Color(look.textColor)
    val muted = text.copy(alpha = 0.72f)
    val shape = cornerShape(look.cornerDp.coerceAtMost(28f))
    fun open() {
        onDismiss()
        actions.onOpen(book)
    }
    fun details() {
        onDismiss()
        actions.onDetails(book)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(20.dp)
                .themedShadow(look.shadow, shape)
                .themedFill(look.fill, shape)
                .themedStroke(look.stroke, shape)
                // Scrolls as a whole on a short screen, so the buttons are never out of reach.
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookTile(
                    coverPath = book.displayCover,
                    look = theme.book,
                    progress = book.progress,
                    label = "Open ${book.title}",
                    badge = null,
                    onClick = { open() },
                    onLongClick = { details() },
                    modifier = Modifier.width(112.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    bookFacts(book, state).forEach { line ->
                        Text(line, color = muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val description = book.info.description
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardButton("Details", text, filled = false, modifier = Modifier.weight(1f), onClick = { details() })
                CardButton(openLabel(book), text, filled = true, modifier = Modifier.weight(1f), onClick = { open() })
            }
        }
    }
}
