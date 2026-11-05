package com.tim.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import com.tim.reader.data.ShelfItem
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.fontFamily
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke

private val TWO_PANE_WIDTH = 700.dp

@Composable
fun HomeScreen(
    state: LibraryState,
    header: HeaderActions,
    actions: BookActions,
    onRecentlyRead: () -> Unit,
    onReadList: () -> Unit,
    onShelf: (String) -> Unit,
) {
    val theme = state.homeTheme ?: return
    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (maxWidth >= TWO_PANE_WIDTH) {
                val paneWidth = maxWidth.value / 2
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        HomeList(state, theme, columnsFor(paneWidth), header, actions, onRecentlyRead, onReadList, onShelf)
                    }
                    Box(Modifier.weight(1f)) {
                        CoverPager(state, theme, actions)
                    }
                }
            } else {
                HomeList(state, theme, columnsFor(maxWidth.value), header, actions, onRecentlyRead, onReadList, onShelf)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, theme: ShelfTheme, end: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedText(text, theme.title, Modifier.weight(1f))
        end()
    }
}

@Composable
private fun EmptyRow(text: String, theme: ShelfTheme, columns: Int, actions: BookActions) {
    Column {
        Text(
            text = text,
            color = Color(theme.title.color).copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        ShelfRow(emptyList(), columns, theme, actions)
    }
}

@Composable
private fun HomeList(
    state: LibraryState,
    theme: ShelfTheme,
    columns: Int,
    header: HeaderActions,
    actions: BookActions,
    onRecentlyRead: () -> Unit,
    onReadList: () -> Unit,
    onShelf: (String) -> Unit,
) {
    val recent = state.recentlyRead.take(columns).map { ShelfItem.Single(it) }
    val readList = state.readList.take(columns).map { ShelfItem.Single(it) }
    val controls = Color(theme.controls.color)

    LazyColumn(Modifier.fillMaxSize()) {
        item { LibraryHeader(theme.header, header) }
        item {
            SectionTitle("Recently Read", theme) {
                GlyphButton(Glyph.Grid, controls, "Open Recently Read", onRecentlyRead)
            }
        }
        item {
            if (recent.isEmpty()) {
                val hint = if (state.books.isEmpty()) "Tap + to add your first book" else "Books you open appear here"
                EmptyRow(hint, theme, columns, actions)
            } else {
                ShelfRow(recent, columns, theme, actions)
            }
        }
        item {
            SectionTitle("Read List", theme) { PillButton("View All", controls, onReadList) }
        }
        item {
            if (readList.isEmpty()) {
                EmptyRow("Hold a book to add it here", theme, columns, actions)
            } else {
                ShelfRow(readList, columns, theme, actions)
            }
        }
        items(state.shelves.chunked(2), key = { row -> row.first().id }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { shelf -> ShelfButton(shelf, Modifier.weight(1f)) { onShelf(shelf.id) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShelfButton(shelf: Shelf, modifier: Modifier, onClick: () -> Unit) {
    val look = shelf.theme.button
    val shape = cornerShape(look.cornerDp)
    Box(
        modifier
            .height(76.dp)
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .clickable(onClickLabel = shelf.name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shelf.name,
            color = Color(look.textColor),
            fontFamily = fontFamily(look.font),
            fontSize = 26.sp,
            fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (look.italic) FontStyle.Italic else FontStyle.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** The right pane when unfolded: large covers to swipe through. */
@Composable
private fun CoverPager(state: LibraryState, theme: ShelfTheme, actions: BookActions) {
    val books: List<Book> = state.recentlyRead.ifEmpty { state.books.sortedByDescending { it.addedAt } }
    if (books.isEmpty()) return
    val pager = rememberPagerState { books.size }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        val book = books[page]
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BookTile(
                coverPath = book.displayCover,
                look = theme.book,
                progress = book.progress,
                label = book.title,
                badge = null,
                onClick = { actions.onOpen(book) },
                onLongClick = { actions.onDetails(book) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight(0.86f),
            )
            Text(
                text = book.title,
                color = Color(theme.title.color),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            )
        }
    }
}
