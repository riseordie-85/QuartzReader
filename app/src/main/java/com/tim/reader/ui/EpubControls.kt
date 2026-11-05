package com.tim.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.data.SettingScope
import com.tim.reader.data.resolveLook
import com.tim.reader.engine.Chapter
import com.tim.reader.library.DeviceSettings
import com.tim.reader.reader.epubPreferences
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import androidx.compose.ui.graphics.Color

private const val MAX_RESULTS = 300
private const val SEARCH_GROUP = "search"

private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<String, Link>> = links.flatMap { link ->
    val title = "    ".repeat(depth) + (link.title ?: "Untitled")
    listOf(title to link) + flatten(link.children, depth + 1)
}

/** The floating controls over Readium, and the bridge from reader settings to its preferences. */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun EpubControls(
    book: Book?,
    publication: Publication,
    navigator: EpubNavigatorFragment,
    showControls: Boolean,
    onAnimatedTurns: (Boolean) -> Unit,
    onPageColor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val library by repository.state.collectAsState()
    val liveBook = book?.let { library.book(it.id) ?: it }
    val scope = rememberCoroutineScope()

    var testPrefs by remember { mutableStateOf(ReaderPrefs()) }
    var testDirection by remember { mutableStateOf(ReadDirection.LeftToRight) }
    val look = liveBook?.let { library.readerLook(it) } ?: resolveLook(testPrefs)
    val direction = liveBook?.direction ?: testDirection
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var settingScope by remember { mutableStateOf(SettingScope.ThisBook) }
    var rotationLocked by remember { mutableStateOf(false) }

    val screen = LocalConfiguration.current
    val twoColumns = showsTwoPages(
        DeviceSettings.twoPage,
        direction,
        screen.screenWidthDp.toFloat(),
        screen.screenHeightDp.toFloat(),
    )
    val preferences = remember(look, direction, twoColumns) { epubPreferences(look, direction, twoColumns) }
    LaunchedEffect(preferences) { navigator.submitPreferences(preferences) }
    SideEffect {
        onAnimatedTurns(look.animation != PageAnimation.None)
        onPageColor(Color(look.pageBackground).toArgb())
    }

    ReaderWindow(showControls || sheet != null, rotationLocked)
    BackHandler(enabled = sheet != null) { sheet = null }

    val positions by produceState(emptyList<Locator>(), publication) { value = publication.positions() }
    val contents = remember(publication) { flatten(publication.tableOfContents) }
    val locator by navigator.currentLocator.collectAsState()
    val page = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
    val pageCount = positions.size.coerceAtLeast(1)

    if (!showControls) return

    val bookmarks = liveBook?.let { library.bookmarks[it.id].orEmpty() }
    val pageBookmark = bookmarks?.firstOrNull { it.page == page }

    fun goToPosition(target: Int) {
        positions.getOrNull(target)?.let { navigator.go(it) }
    }

    ReaderChrome(
        title = book?.title ?: publication.metadata.title ?: "Book",
        page = page,
        pageCount = pageCount,
        isBookmarked = bookmarks?.let { pageBookmark != null },
        hasChapters = contents.isNotEmpty(),
        hasSearch = publication.isSearchable,
        sheet = sheet,
        onSheet = { sheet = it },
        onBookmark = {
            if (liveBook != null) {
                val label = listOfNotNull(locator.title, "Page ${page + 1}").joinToString(", ")
                scope.launch {
                    if (pageBookmark != null) {
                        repository.removeBookmark(pageBookmark.id)
                    } else {
                        repository.addBookmark(liveBook.id, page, label)
                    }
                }
            }
        },
        onSeek = ::goToPosition,
    ) { open ->
        when (open) {
            ReaderSheet.Settings -> TextSettings(
                direction = direction,
                look = look,
                type = liveBook?.type,
                scope = settingScope,
                colorSets = library.colorSets,
                rotationLocked = rotationLocked,
                actions = TextSettingsActions(
                    onChange = { change ->
                        if (liveBook != null) {
                            scope.launch { repository.changePrefs(liveBook, settingScope, change) }
                        } else {
                            testPrefs = change(testPrefs)
                        }
                    },
                    onDirection = { option ->
                        if (liveBook != null) {
                            scope.launch { repository.setDirection(liveBook.id, option) }
                        } else {
                            testDirection = option
                        }
                    },
                    onScope = { settingScope = it },
                    onSaveColors = { set -> scope.launch { repository.addColorSet(set) } },
                    onRemoveColors = { set -> scope.launch { repository.removeColorSet(set) } },
                    onRotationLock = { rotationLocked = it },
                    onClose = { sheet = null },
                ),
            )
            ReaderSheet.Bookmarks -> BookmarkSheet(
                bookmarks = bookmarks.orEmpty(),
                onChoose = { target ->
                    sheet = null
                    goToPosition(target)
                },
                onRemove = { id -> scope.launch { repository.removeBookmark(id) } },
                onDone = { sheet = null },
            )
            ReaderSheet.Chapters -> ChapterSheet(
                chapters = contents.mapIndexed { index, (title, _) -> Chapter(title, index) },
                onChoose = { index ->
                    sheet = null
                    navigator.go(contents[index].second)
                },
                onDone = { sheet = null },
            )
            ReaderSheet.Search -> SearchSheet(publication, navigator) { sheet = null }
        }
    }
}

/** Finds text in the book, and highlights the match it jumps to. */
@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun ColumnScope.SearchSheet(publication: Publication, navigator: EpubNavigatorFragment, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<Locator>() }

    LaunchedEffect(Unit) { navigator.applyDecorations(emptyList(), SEARCH_GROUP) }

    fun runSearch() {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        searching = true
        searched = true
        results.clear()
        scope.launch {
            val found = publication.search(text)
            try {
                while (found != null && results.size < MAX_RESULTS) {
                    val page = found.next().getOrNull() ?: break
                    results += page.locators
                }
            } finally {
                found?.close()
                searching = false
            }
        }
    }

    ReaderSheetCard("Search", onDone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(::runSearch) { Text("Find") }
        }
        if (searching) CircularProgressIndicator(Modifier.padding(8.dp))
        if (searched && !searching && results.isEmpty()) {
            Text("Nothing found", modifier = Modifier.padding(vertical = 10.dp))
        }
        results.forEach { result ->
            val before = result.text.before.orEmpty().takeLast(30)
            val after = result.text.after.orEmpty().take(30)
            Text(
                "$before${result.text.highlight.orEmpty()}$after".replace('\n', ' '),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        navigator.go(result)
                        scope.launch {
                            val match = Decoration(
                                id = "match",
                                locator = result,
                                style = Decoration.Style.Highlight(tint = android.graphics.Color.YELLOW),
                            )
                            navigator.applyDecorations(listOf(match), SEARCH_GROUP)
                        }
                        onDone()
                    }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
