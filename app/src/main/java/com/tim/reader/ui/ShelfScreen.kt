package com.tim.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import com.tim.reader.data.ShelfItem
import com.tim.reader.data.SortOrder
import com.tim.reader.data.ViewMode
import com.tim.reader.data.shelfItems
import com.tim.reader.theme.Presets
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import kotlinx.coroutines.launch

/** Shelves repeat this many times each way, so swiping feels endless. */
private const val LOOPS = 1000

class ShelfNeighbors(val previous: String, val next: String, val onPrevious: () -> Unit, val onNext: () -> Unit)

@Composable
fun ShelfPager(
    state: LibraryState,
    startShelfId: String,
    header: HeaderActions,
    actions: BookActions,
    onCurrentShelf: (String) -> Unit,
) {
    val shelves = state.shelves
    if (shelves.isEmpty()) return
    val repository = LibraryRepository.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val count = shelves.size

    key(count) {
        val total = if (count > 1) count * LOOPS else 1
        val start = shelves.indexOfFirst { it.id == startShelfId }.coerceAtLeast(0)
        val pager = rememberPagerState(initialPage = if (count > 1) count * (LOOPS / 2) + start else 0) { total }
        val latestShelves by rememberUpdatedState(shelves)
        val report by rememberUpdatedState(onCurrentShelf)

        // Keeps the route on the visible shelf, so Back and Import use the right one.
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { page ->
                latestShelves.getOrNull(page % latestShelves.size.coerceAtLeast(1))?.let { report(it.id) }
            }
        }

        val visible = shelves[pager.currentPage % count]
        CoverSplit(
            items = shelfItems(state.booksOn(visible.id), visible.sort, visible.groupSeries, state),
            theme = visible.theme,
            actions = actions,
            paneKey = visible.id,
        ) { pageModifier ->
        HorizontalPager(state = pager, modifier = pageModifier, beyondViewportPageCount = 1) { page ->
            val shelf = shelves[page % count]
            val neighbors = if (count > 1) {
                ShelfNeighbors(
                    previous = shelves[(page + count - 1) % count].name,
                    next = shelves[(page + 1) % count].name,
                    onPrevious = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
                )
            } else {
                null
            }
            ShelfContent(
                title = shelf.name,
                theme = shelf.theme,
                items = shelfItems(state.booksOn(shelf.id), shelf.sort, shelf.groupSeries, state),
                viewMode = shelf.viewMode,
                sort = shelf.sort,
                groupSeries = shelf.groupSeries,
                neighbors = neighbors,
                header = header,
                actions = actions,
                onViewMode = { mode -> scope.launch { repository.setShelfView(shelf.id, mode) } },
                onSort = { order -> scope.launch { repository.setShelfSort(shelf.id, order) } },
                onGroupSeries = { group -> scope.launch { repository.setShelfGrouping(shelf.id, group) } },
                onStyle = { style -> scope.launch { repository.setShelfTheme(shelf.id, style) } },
            )
        }
        }
    }
}

/** Recently Read, Read List and All Books as full pages, outside the swipe order. */
@Composable
fun ListPage(
    title: String,
    theme: ShelfTheme,
    items: List<ShelfItem>,
    header: HeaderActions,
    actions: BookActions,
) {
    var viewMode by remember(title) { mutableStateOf(ViewMode.Shelf) }
    CoverSplit(items, theme, actions, title) { pageModifier ->
        ShelfContent(
            title = title,
            theme = theme,
            items = items,
            viewMode = viewMode,
            sort = null,
            groupSeries = false,
            neighbors = null,
            header = header,
            actions = actions,
            onViewMode = { viewMode = it },
            onSort = {},
            onGroupSeries = {},
            onStyle = {},
            modifier = pageModifier,
        )
    }
}

@Composable
private fun ShelfContent(
    title: String,
    theme: ShelfTheme,
    items: List<ShelfItem>,
    viewMode: ViewMode,
    sort: SortOrder?,
    groupSeries: Boolean,
    neighbors: ShelfNeighbors?,
    header: HeaderActions,
    actions: BookActions,
    onViewMode: (ViewMode) -> Unit,
    onSort: (SortOrder) -> Unit,
    onGroupSeries: (Boolean) -> Unit,
    onStyle: (ShelfTheme) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val controls = Color(theme.controls.color)
    val bottomSpace = if (neighbors != null) theme.bottom.heightDp.dp else 24.dp

    ThemedBackground(theme.background, modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LibraryHeader(theme.header, header)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemedText(title, theme.title, Modifier.weight(1f))
                GlyphButton(Glyph.Grid, controls, "Change view") {
                    onViewMode(ViewMode.entries[(viewMode.ordinal + 1) % ViewMode.entries.size])
                }
                if (sort != null) {
                    SortMenu(controls, sort, groupSeries, onSort, onGroupSeries, onStyle)
                }
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val columns = columnsFor(maxWidth.value)
                when {
                    items.isEmpty() -> Text(
                        text = "Nothing here yet",
                        color = Color(theme.title.color).copy(alpha = 0.7f),
                        modifier = Modifier.padding(24.dp),
                    )
                    viewMode == ViewMode.Shelf -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomSpace),
                    ) {
                        shelfRows(items, columns, theme, actions)
                    }
                    viewMode == ViewMode.Grid -> GridView(items, columns + 1, theme, actions, bottomSpace)
                    else -> ListView(items, theme, actions, bottomSpace)
                }
            }
        }
        if (neighbors != null) {
            BottomBar(neighbors, theme, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SortMenu(
    color: Color,
    sort: SortOrder,
    groupSeries: Boolean,
    onSort: (SortOrder) -> Unit,
    onGroupSeries: (Boolean) -> Unit,
    onStyle: (ShelfTheme) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillButton("Sort by", color) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == sort) "${option.label}  ✓" else option.label) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (groupSeries) "Show every volume" else "Group series") },
                onClick = {
                    open = false
                    onGroupSeries(!groupSeries)
                },
            )
            HorizontalDivider()
            Presets.all.forEach { (name, style) ->
                DropdownMenuItem(
                    text = { Text("Style: $name") },
                    onClick = {
                        open = false
                        onStyle(style)
                    },
                )
            }
        }
    }
}

@Composable
private fun GridView(items: List<ShelfItem>, columns: Int, theme: ShelfTheme, actions: BookActions, bottom: Dp) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.key }) { item ->
            Column {
                ShelfItemTile(item, theme.book, actions, Modifier.fillMaxWidth())
                Text(
                    text = item.displayTitle(),
                    color = Color(theme.title.color),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ListView(items: List<ShelfItem>, theme: ShelfTheme, actions: BookActions, bottom: Dp) {
    val text = Color(theme.title.color)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom)) {
        items(items, key = { it.key }) { item ->
            val subtitle = when (item) {
                is ShelfItem.Single -> item.book.author
                is ShelfItem.Stack -> "${item.volumes.size} volumes"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (item) {
                            is ShelfItem.Single -> actions.onOpen(item.book)
                            is ShelfItem.Stack -> actions.onSeries(item.series.id)
                        }
                    }
                    .padding(vertical = 10.dp),
            ) {
                Text(item.displayTitle(), color = text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = text.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(neighbors: ShelfNeighbors, theme: ShelfTheme, modifier: Modifier) {
    val look = theme.bottom
    val font = theme.title.copy(sizeSp = 20f, color = look.labelColor, stroke = null, shadow = null)
    Box(
        modifier
            .fillMaxWidth()
            .height(look.heightDp.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(look.fadeColor)))),
    ) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ThemedText(neighbors.previous, font, Modifier.clickable(onClickLabel = "Previous shelf", onClick = neighbors.onPrevious))
            ThemedText(neighbors.next, font, Modifier.clickable(onClickLabel = "Next shelf", onClick = neighbors.onNext))
        }
    }
}
