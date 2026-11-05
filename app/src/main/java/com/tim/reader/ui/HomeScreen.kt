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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import com.tim.reader.data.ShelfItem
import com.tim.reader.data.ViewMode
import com.tim.reader.library.DeviceSettings
import com.tim.reader.theme.ButtonLook
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.fontFamily
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

private val TWO_PANE_WIDTH = 700.dp
private const val MAX_ANIMATED_JUMP = 6

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
    val chosenColumns = DeviceSettings.booksPerRow(booksPerRowKey("home", ViewMode.Shelf))
    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (maxWidth >= TWO_PANE_WIDTH && DeviceSettings.splitView) {
                val paneWidth = maxWidth.value / 2
                val selection = remember { CoverSelection() }
                // Every title Home can show is on the right, so any tile can bring up its cover.
                val covers = (state.recentlyRead + state.readList + state.books.sortedByDescending { it.addedAt })
                    .distinctBy { it.id }
                    .map { ShelfItem.Single(it) }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalCoverSelection provides selection) {
                            HomeList(state, theme, chosenColumns ?: columnsFor(paneWidth), header, actions, onRecentlyRead, onReadList, onShelf)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        CoverPager(covers, theme, actions, selection)
                    }
                }
            } else {
                HomeList(state, theme, chosenColumns ?: columnsFor(maxWidth.value), header, actions, onRecentlyRead, onReadList, onShelf)
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
                GlyphButton(Glyph.Grid, controls, "Open Recently Read", iconSize = 26.dp, outline = theme.controls.stroke, onClick = onRecentlyRead)
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
            SectionTitle("Read List", theme) { PillButton("View All", theme.controls, onReadList) }
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
                row.forEach { shelf -> ShelfButton(shelf.name, shelf.theme.button, Modifier.weight(1f)) { onShelf(shelf.id) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ShelfButton(name: String, look: ButtonLook, modifier: Modifier, onClick: () -> Unit) {
    val shape = cornerShape(look.cornerDp)
    Box(
        modifier
            .height(76.dp)
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .clickable(onClickLabel = name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
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

/** Large covers to swipe through, in the right pane when unfolded. */
@Composable
fun CoverPager(items: List<ShelfItem>, theme: ShelfTheme, actions: BookActions, selection: CoverSelection) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    // True while a tap moves the pager, so the pages it passes don't take over the highlight.
    val jumping = remember { booleanArrayOf(false) }

    LaunchedEffect(pager, selection, items) {
        snapshotFlow { selection.key }.collectLatest { key ->
            val index = items.indexOfFirst { it.key == key }
            if (index >= 0 && index != pager.currentPage) {
                jumping[0] = true
                try {
                    if (abs(index - pager.currentPage) > MAX_ANIMATED_JUMP) {
                        pager.scrollToPage(index)
                    } else {
                        pager.animateScrollToPage(index)
                    }
                } finally {
                    jumping[0] = false
                }
            }
        }
    }
    LaunchedEffect(pager, selection, items) {
        snapshotFlow { pager.currentPage }.collect { page ->
            if (!jumping[0]) items.getOrNull(page)?.let { selection.key = it.key }
        }
    }

    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        val item = items[page]
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ShelfItemTile(
                item,
                theme.book,
                actions,
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight(0.86f),
                inCoverPane = true,
            )
            Text(
                text = item.displayTitle(),
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

/**
 * Keeps the Home split on every library page when unfolded: the page on the left, and that page's
 * titles to swipe through on the right. Narrow screens show the page alone.
 */
@Composable
fun CoverSplit(
    items: List<ShelfItem>,
    theme: ShelfTheme,
    actions: BookActions,
    paneKey: Any,
    page: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_PANE_WIDTH && DeviceSettings.splitView) {
            val selection = remember(paneKey) { CoverSelection() }
            Row(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalCoverSelection provides selection) {
                    page(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                key(paneKey) {
                    ThemedBackground(
                        theme.background,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        ) {
                            CoverPager(items, theme, actions, selection)
                        }
                    }
                }
            }
        } else {
            page(Modifier.fillMaxSize())
        }
    }
}
