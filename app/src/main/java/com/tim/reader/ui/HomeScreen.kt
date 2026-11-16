package com.tim.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import com.tim.reader.data.ShelfItem
import com.tim.reader.data.ViewMode
import com.tim.reader.data.shelfItems
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
    onStats: () -> Unit = {},
    onGoals: () -> Unit = {},
    onAchievements: () -> Unit = {},
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
                ClosePeekInSplitView()
                // Every title Home can show is on the right, so any tile can bring up its cover.
                val covers = (state.recentlyRead + state.readList + state.books.sortedByDescending { it.addedAt })
                    .distinctBy { it.id }
                    .map { ShelfItem.Single(it) }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalCoverSelection provides selection) {
                            HomeList(state, theme, chosenColumns ?: columnsFor(paneWidth), header, actions, onRecentlyRead, onReadList, onShelf, onStats, onGoals, onAchievements)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        CoverPager(covers, theme, actions, selection)
                    }
                }
            } else {
                HomeList(state, theme, chosenColumns ?: columnsFor(maxWidth.value), header, actions, onRecentlyRead, onReadList, onShelf, onStats, onGoals, onAchievements)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, theme: ShelfTheme, modifier: Modifier = Modifier, end: @Composable () -> Unit) {
    Row(
        modifier
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
    onStats: () -> Unit,
    onGoals: () -> Unit,
    onAchievements: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    val sections = homeSections(state)
    val card = theme.cardLook
    val homeStats = rememberHomeStats(state, sections.any { HomeSection.of(it)?.isStats == true })
    val controls = Color(theme.controls.color)

    // Holding a section's title, or any empty part of Home, opens edit mode. Books and buttons
    // take their own presses first, and a scroll cancels the hold, so neither opens it.
    val holdToEdit = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.isConsumed) return@awaitEachGesture
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Final)
                true
            }
            if (ended == null) editing = true
        }
    }

    LazyColumn(Modifier.fillMaxSize().then(holdToEdit), horizontalAlignment = Alignment.CenterHorizontally) {
        item { LibraryHeader(theme.header, header) }
        if (editing) {
            item { HomeEditor(state, card) { editing = false } }
        } else {
            sections.forEach { key ->
                val shelfId = key.removePrefix(SHELF_SECTION).takeIf { key.startsWith(SHELF_SECTION) }
                when {
                    shelfId != null -> state.shelf(shelfId)?.let { shelf ->
                        val books = shelfItems(state.booksOn(shelf.id), shelf.sort, shelf.groupSeries, state).take(columns)
                        item(key = key + ":title") {
                            SectionTitle(shelf.name, theme) {
                                GlyphButton(Glyph.Grid, controls, "Open ${shelf.name}", iconSize = 26.dp, outline = theme.controls.stroke) {
                                    onShelf(shelf.id)
                                }
                            }
                        }
                        item(key = key) {
                            if (books.isEmpty()) EmptyRow("No books on this shelf yet", theme, columns, actions) else ShelfRow(books, columns, theme, actions)
                        }
                    }
                    else -> when (HomeSection.of(key)) {
                        HomeSection.Goals -> item(key = key) { HomeGoalsStrip(state, card, onGoals) }
                        HomeSection.RecentlyRead -> {
                            val recent = state.recentlyRead.take(columns).map { ShelfItem.Single(it) }
                            item(key = key + ":title") {
                                SectionTitle("Recently Read", theme) {
                                    GlyphButton(Glyph.Grid, controls, "Open Recently Read", iconSize = 26.dp, outline = theme.controls.stroke, onClick = onRecentlyRead)
                                }
                            }
                            item(key = key) {
                                if (recent.isEmpty()) {
                                    val hint = if (state.books.isEmpty()) "Tap the import button to add your first book" else "Books you open appear here"
                                    EmptyRow(hint, theme, columns, actions)
                                } else {
                                    ShelfRow(recent, columns, theme, actions)
                                }
                            }
                        }
                        HomeSection.ReadList -> {
                            val readList = state.readList.take(columns).map { ShelfItem.Single(it) }
                            item(key = key + ":title") {
                                SectionTitle("Read List", theme) { PillButton("View All", theme.controls, onReadList) }
                            }
                            item(key = key) {
                                if (readList.isEmpty()) EmptyRow("Hold a book to add it here", theme, columns, actions) else ShelfRow(readList, columns, theme, actions)
                            }
                        }
                        HomeSection.ShelfButtons -> items(state.shelves.chunked(2), key = { row -> "button:" + row.first().id }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Each button keeps its shelf's look, but takes Home's corners so they all match the header.
                                row.forEach { shelf ->
                                    val look = shelf.theme.button.copy(cornerDp = theme.header.cornerDp)
                                    ShelfButton(shelf.name, look, Modifier.weight(1f)) { onShelf(shelf.id) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        HomeSection.ReadingTime -> homeStats?.let { loaded -> item(key = key) { Summary(card, loaded.stats) } }
                        HomeSection.Achievements -> homeStats?.let { loaded ->
                            item(key = key) { AchievementsPanel(card, loaded.achievements, onAchievements) }
                        }
                        HomeSection.ReadingDays -> homeStats?.let { loaded -> item(key = key) { Calendar(card, loaded.stats) } }
                        HomeSection.FinishedByType -> homeStats?.let { loaded -> item(key = key) { FinishedByType(card, loaded.stats) } }
                        HomeSection.FinishedByWeekday -> homeStats?.let { loaded ->
                            item(key = key) { FinishedByWeekday(card, loaded.stats, DeviceSettings.chartStartsSunday) }
                        }
                        HomeSection.FinishedThisYear -> homeStats?.let { loaded -> item(key = key) { FinishedByMonth(card, loaded.stats) } }
                        null -> Unit
                    }
                }
            }
            if (sections.isEmpty()) {
                item {
                    Text(
                        "Home is empty. Hold here to add sections.",
                        color = Color(theme.title.color).copy(alpha = 0.7f),
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { BottomBarSpacer() }
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
        val style = TextStyle(
            fontFamily = fontFamily(look.font),
            fontSize = 26.sp,
            fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (look.italic) FontStyle.Italic else FontStyle.Normal,
        )
        val outline = look.textStroke
        if (outline != null) {
            // The outline is the same words drawn as a stroke underneath, twice as wide since half falls inside.
            val width = with(LocalDensity.current) { outline.widthDp.dp.toPx() * 2 }
            Text(
                text = name,
                color = Color(outline.color),
                style = style.copy(drawStyle = Stroke(width = width, join = StrokeJoin.Round)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Text(
            text = name,
            color = Color(look.textColor),
            style = style,
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

    // The bottom bar runs under this pane too, so the details stop above it.
    val bottom = LocalBottomBarSpace.current
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        val item = items[page]
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 20.dp + bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // No fill, so the cover takes the largest size that fits and sits right on the details.
                ShelfItemTile(item, theme.book, actions, inCoverPane = true)
            }
            CoverInfoCard(
                item,
                theme.cardLook,
                actions,
                Modifier
                    .padding(top = 20.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
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
            ClosePeekInSplitView()
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

/** Unfolding into the split view shows details beside the shelf, so a popup from before closes. */
@Composable
private fun ClosePeekInSplitView() {
    val peek = LocalBookPeek.current ?: return
    LaunchedEffect(peek) { peek.bookId = null }
}
