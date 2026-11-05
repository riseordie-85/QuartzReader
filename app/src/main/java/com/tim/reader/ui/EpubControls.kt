package com.tim.reader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Book
import com.tim.reader.data.ColorSet
import com.tim.reader.data.Highlight
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.data.SettingScope
import com.tim.reader.data.resolveLook
import com.tim.reader.engine.Chapter
import com.tim.reader.library.DeviceSettings
import com.tim.reader.reader.capturePage
import com.tim.reader.reader.epubPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

private const val MAX_RESULTS = 300
private const val SEARCH_GROUP = "search"
private const val HIGHLIGHT_GROUP = "highlights"
private const val SEARCH_TINT = 0xFFFFA726.toInt()
private const val HIGHLIGHT_TINT = 0xFFFFEB3B.toInt()

private fun locatorOf(json: String): Locator? = try {
    Locator.fromJSON(JSONObject(json))
} catch (_: JSONException) {
    null
}

/** Kept while the book is open, so reopening search shows the last results. */
private class SearchState {
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    val results = mutableStateListOf<Locator>()
    val scroll = ScrollState(0)
}

/** Matches the side padding Readium's stylesheet picks for a screen this wide. */
private fun pageGutter(widthDp: Int) = when {
    widthDp >= 1200 -> 50
    widthDp >= 720 -> 40
    widthDp >= 560 -> 30
    else -> 20
}

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
    isSelecting: Boolean,
    onEndSelection: () -> Unit,
    onShowControls: (Boolean) -> Unit,
    onAnimatedTurns: (Boolean) -> Unit,
    onPageColor: (Int) -> Unit,
    onPagePadding: (outerMargin: Int, cameraGap: Int) -> Unit,
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
    var draft by remember { mutableStateOf<NoteDraft?>(null) }
    var notesTab by remember { mutableStateOf(NotesTab.Highlights) }
    var shareRequest by remember { mutableStateOf<ShareRequest?>(null) }
    val activity = LocalActivity.current
    var settingScope by remember { mutableStateOf(SettingScope.ThisBook) }
    var rotationLocked by remember { mutableStateOf(false) }

    val screen = LocalConfiguration.current
    val twoColumns = showsTwoPages(
        DeviceSettings.twoPage,
        direction,
        screen.screenWidthDp.toFloat(),
        screen.screenHeightDp.toFloat(),
    )
    val fixedLayout = publication.metadata.layout == Layout.FIXED
    val preferences = remember(look, direction, twoColumns) {
        epubPreferences(look, direction, twoColumns, fixedLayout)
    }
    LaunchedEffect(preferences) { navigator.submitPreferences(preferences) }
    // Two pages share one margin in the middle, so each outer edge takes half of its own here.
    val density = LocalDensity.current.density
    val scrolling = look.scrolling ?: (direction == ReadDirection.Vertical)
    val outerMargin = if (twoColumns && !fixedLayout && !scrolling) {
        (pageGutter(screen.screenWidthDp) * (look.pageMargins ?: 1.0) / 2 * density).roundToInt()
    } else {
        0
    }
    val cameraGap = (DeviceSettings.cameraGap * density).roundToInt()
    SideEffect {
        onAnimatedTurns(look.animation != PageAnimation.None)
        onPageColor(Color(look.pageBackground).toArgb())
        onPagePadding(outerMargin, cameraGap)
    }

    ReaderWindow(showControls || sheet != null, rotationLocked)
    BackHandler(enabled = sheet != null || draft != null || shareRequest != null) {
        when {
            shareRequest != null -> shareRequest = null
            draft != null -> draft = null
            sheet == ReaderSheet.Advanced || sheet == ReaderSheet.Colors -> sheet = ReaderSheet.Settings
            else -> sheet = null
        }
    }

    BackHandler(enabled = isSelecting && sheet == null && draft == null, onBack = onEndSelection)

    val highlights = liveBook?.let { library.highlights[it.id] }.orEmpty()
    val latestHighlights by rememberUpdatedState(highlights)
    LaunchedEffect(highlights) {
        val marks = highlights.mapNotNull { highlight ->
            locatorOf(highlight.locator)?.let { locator ->
                Decoration(highlight.id, locator, Decoration.Style.Highlight(tint = HIGHLIGHT_TINT))
            }
        }
        navigator.applyDecorations(marks, HIGHLIGHT_GROUP)
    }
    DisposableEffect(navigator) {
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val highlight = latestHighlights.firstOrNull { it.id == event.decoration.id } ?: return false
                draft = NoteDraft(highlight, event.decoration.locator, highlight.text)
                return true
            }
        }
        navigator.addDecorationListener(HIGHLIGHT_GROUP, listener)
        onDispose { navigator.removeDecorationListener(listener) }
    }

    val changeLook: ((ReaderPrefs) -> ReaderPrefs) -> Unit = { change ->
        if (liveBook != null) {
            scope.launch { repository.changePrefs(liveBook, settingScope, change) }
        } else {
            testPrefs = change(testPrefs)
        }
    }

    val positions by produceState(emptyList<Locator>(), publication) { value = publication.positions() }
    val contents = remember(publication) { flatten(publication.tableOfContents) }
    val locator by navigator.currentLocator.collectAsState()
    val page = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
    val pageCount = positions.size.coerceAtLeast(1)
    val search = remember(publication) { SearchState() }
    LaunchedEffect(sheet) {
        if (sheet == ReaderSheet.Search) navigator.applyDecorations(emptyList(), SEARCH_GROUP)
    }

    /** Hides the controls, moves to the highlight if there is one, and copies the page. */
    fun startShare(highlight: Highlight?) {
        scope.launch {
            sheet = null
            draft = null
            onShowControls(false)
            val target = highlight?.let { locatorOf(it.locator) }
            if (target != null) {
                navigator.go(target)
                // Another chapter can take a while to load, and a blank page is no use.
                withTimeoutOrNull(3000) { navigator.currentLocator.first { it.href.isEquivalent(target.href) } }
            }
            // The bars and any page change need a moment to reach the screen.
            delay(if (target != null) 350 else 250)
            val page = activity?.capturePage()
            if (page != null) shareRequest = ShareRequest(page, highlight?.text, highlight?.note)
        }
    }

    fun saveSelection(writeNote: Boolean) {
        scope.launch {
            val selection = navigator.currentSelection() ?: return@launch
            val text = selection.locator.text.highlight.orEmpty()
            // Clearing first means a double tap can't save the same text twice.
            onEndSelection()
            if (writeNote) {
                draft = NoteDraft(null, selection.locator, text)
            } else if (liveBook != null) {
                repository.addHighlight(liveBook.id, selection.locator.toJSON().toString(), text, "")
            }
        }
    }

    val caption = listOfNotNull(
        book?.title ?: publication.metadata.title,
        book?.author?.takeIf { it.isNotBlank() },
    ).joinToString(" - ")

    val request = shareRequest
    val editing = draft
    if (request != null) {
        ShareOverlay(request, caption) { shareRequest = null }
        return
    }
    if (editing != null) {
        NoteEditor(
            draft = editing,
            onSave = { note ->
                draft = null
                val saved = editing.highlight
                scope.launch {
                    if (saved != null) {
                        repository.updateNote(saved.id, note)
                    } else if (liveBook != null) {
                        repository.addHighlight(liveBook.id, editing.locator.toJSON().toString(), editing.text, note)
                    }
                }
            },
            onCancel = { draft = null },
            onGoTo = {
                draft = null
                sheet = null
                navigator.go(editing.locator)
            },
            onShare = { startShare(editing.highlight) },
            onDelete = {
                draft = null
                editing.highlight?.let { saved -> scope.launch { repository.removeHighlight(saved.id) } }
            },
        )
        return
    }
    if (isSelecting && liveBook != null) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SelectionButton("Highlight", Modifier.weight(1f)) { saveSelection(writeNote = false) }
                SelectionButton("Add Note", Modifier.weight(1f)) { saveSelection(writeNote = true) }
            }
        }
        return
    }
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
        hasNotes = liveBook != null,
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
        onShare = { startShare(null) },
        onSeek = ::goToPosition,
    ) { open ->
        when (open) {
            ReaderSheet.Settings -> QuickSettings(
                look = look,
                fixedLayout = fixedLayout,
                onChange = changeLook,
                onColors = { sheet = ReaderSheet.Colors },
                onAdvanced = { sheet = ReaderSheet.Advanced },
                onDone = { sheet = null },
            )
            ReaderSheet.Colors -> ReaderSheetCard("Colors", { sheet = ReaderSheet.Settings }) {
                ColorSection(
                    current = ColorSet(look.pageBackground, look.textColor),
                    saved = library.colorSets,
                    onChange = { set -> changeLook { it.copy(pageBackground = set.background, textColor = set.text) } },
                    onSave = { scope.launch { repository.addColorSet(ColorSet(look.pageBackground, look.textColor)) } },
                    onRemove = { set -> scope.launch { repository.removeColorSet(set) } },
                )
            }
            ReaderSheet.Notes -> NotesSheet(highlights, notesTab, { notesTab = it }) { highlight ->
                locatorOf(highlight.locator)?.let { draft = NoteDraft(highlight, it, highlight.text) }
            }
            ReaderSheet.Advanced -> TextSettings(
                direction = direction,
                look = look,
                type = liveBook?.type,
                scope = settingScope,
                colorSets = library.colorSets,
                fixedLayout = fixedLayout,
                rotationLocked = rotationLocked,
                actions = TextSettingsActions(
                    onChange = changeLook,
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
                    onClose = { sheet = ReaderSheet.Settings },
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
            ReaderSheet.Search -> SearchSheet(
                state = search,
                scope = scope,
                publication = publication,
                navigator = navigator,
                onMatch = { result ->
                    sheet = null
                    navigator.go(result)
                    // The sheet is closing, so the highlight belongs to the longer-lived scope.
                    scope.launch {
                        val match = Decoration(
                            id = "match",
                            locator = result,
                            style = Decoration.Style.Highlight(tint = SEARCH_TINT),
                        )
                        navigator.applyDecorations(listOf(match), SEARCH_GROUP)
                    }
                },
            )
        }
    }
}

/** Finds text in the book, and highlights the match it jumps to. */
@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun ColumnScope.SearchSheet(
    state: SearchState,
    scope: CoroutineScope,
    publication: Publication,
    navigator: EpubNavigatorFragment,
    onMatch: (Locator) -> Unit,
) {
    fun runSearch() {
        val text = state.query.trim()
        if (text.isEmpty() || state.searching) return
        state.searching = true
        state.searched = true
        state.results.clear()
        scope.launch {
            navigator.applyDecorations(emptyList(), SEARCH_GROUP)
            state.scroll.scrollTo(0)
            val found = publication.search(text)
            try {
                while (found != null && state.results.size < MAX_RESULTS) {
                    val page = found.next().getOrNull() ?: break
                    state.results += page.locators
                }
            } finally {
                found?.close()
                state.searching = false
            }
        }
    }

    ReaderSheetBody(state.scroll) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search:", fontSize = 20.sp)
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                trailingIcon = { GlyphButton(Glyph.Search, Color.White, "Find", onClick = ::runSearch) },
                modifier = Modifier.weight(1f),
            )
        }
        if (state.searching) CircularProgressIndicator(Modifier.padding(8.dp))
        if (state.searched && !state.searching && state.results.isEmpty()) {
            Text("Nothing found", fontSize = 18.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
        state.results.forEachIndexed { index, result ->
            val before = result.text.before.orEmpty().takeLast(30)
            val after = result.text.after.orEmpty().take(30)
            Text(
                "$before${result.text.highlight.orEmpty()}$after".replace('\n', ' '),
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMatch(result) }
                    .padding(vertical = 12.dp),
            )
            if (index < state.results.lastIndex) HorizontalDivider(color = Color.White)
        }
    }
}

@Composable
private fun SelectionButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick,
        modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
    ) { Text(label, fontSize = 20.sp, maxLines = 1) }
}
