package com.tim.reader.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tim.reader.ResultsLog
import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.FitMode
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.engine.ImageDecode
import com.tim.reader.engine.PageLink
import com.tim.reader.engine.PageSize
import com.tim.reader.engine.PageSource
import com.tim.reader.engine.Spread
import com.tim.reader.engine.buildSpreads
import com.tim.reader.engine.spreadIndexOf
import com.tim.reader.library.DeviceSettings
import com.tim.reader.library.TwoPageMode
import com.tim.reader.reader.CurlEffect
import com.tim.reader.reader.PageLoader
import com.tim.reader.timed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** A page, or one slice of a very tall page, as a row in the vertical reader. */
private data class ReaderRow(val page: Int, val top: Int = -1, val bottom: Int = -1)

private const val DEFAULT_ASPECT = 0.7f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val ROWS_AHEAD = 4

/** Page turns, set by whichever reading mode is on screen. */
private class ReaderNav {
    var next: suspend () -> Unit = {}
    var previous: suspend () -> Unit = {}
    var goTo: suspend (Int) -> Unit = {}
}

private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    fun change(factor: Float, pan: Offset, width: Int, height: Int) {
        scale = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        x = (x + pan.x).coerceIn(-maxX, maxX)
        y = (y + pan.y).coerceIn(-maxY, maxY)
    }

    fun reset() {
        scale = 1f
        x = 0f
        y = 0f
    }

    fun toggle(at: Offset, width: Int, height: Int) {
        if (scale > 1f) {
            reset()
        } else {
            scale = DOUBLE_TAP_ZOOM
            val centerX = width / 2f
            val centerY = height / 2f
            change(1f, Offset((centerX - at.x) * (scale - 1f), (centerY - at.y) * (scale - 1f)), width, height)
        }
    }
}

/** One finger stays with the pager until the page is zoomed, so swipes keep turning pages. */
private fun Modifier.zoomGestures(zoom: ZoomState, width: Int, height: Int, panWhenZoomed: Boolean = true) =
    pointerInput(zoom, width, height, panWhenZoomed) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var active = false
            var travel = 0f
            do {
                // The early pass lets a pinch claim the fingers before a list or pager can scroll.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || (panWhenZoomed && zoom.scale > 1f)) {
                    if (!active) {
                        travel += event.calculatePan().getDistance()
                        active = fingers >= 2 || travel > viewConfiguration.touchSlop
                    }
                    if (active) {
                        zoom.change(event.calculateZoom(), event.calculatePan(), width, height)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** Page-turn taps act at once. Elsewhere a short wait tells a single tap from a double tap. */
private suspend fun PointerInputScope.detectReaderTaps(
    turnsPage: (Offset) -> Boolean,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
) = awaitEachGesture {
    awaitFirstDown()
    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
    up.consume()
    val at = up.position
    if (turnsPage(at)) {
        onTap(at)
        return@awaitEachGesture
    }
    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
    if (second == null) {
        onTap(at)
        return@awaitEachGesture
    }
    val secondUp = waitForUpOrCancellation()
    if (secondUp == null) {
        onTap(at)
    } else {
        secondUp.consume()
        onDoubleTap(secondUp.position)
    }
}

/** Two pages show on wide, nearly square windows: an unfolded Fold, or a tablet held sideways. */
private fun showsTwoPages(mode: TwoPageMode, direction: ReadDirection, widthDp: Float, heightDp: Float) = when {
    direction == ReadDirection.Vertical -> false
    mode == TwoPageMode.Never -> false
    mode == TwoPageMode.Always -> true
    else -> widthDp >= 600f && widthDp / heightDp >= 0.7f
}

@Composable
fun ImageReaderScreen(
    source: PageSource,
    book: Book? = null,
    alwaysShowControls: Boolean = false,
    onPage: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val library by repository.state.collectAsState()
    val liveBook = book?.let { library.book(it.id) ?: it }
    val loader = remember(source) { PageLoader(context, source, book?.hash) }
    val scope = rememberCoroutineScope()
    val nav = remember { ReaderNav() }
    val focus = remember { FocusRequester() }

    var testLook by remember { mutableStateOf(ReaderLook()) }
    val look = liveBook?.let { library.readerLook(it) } ?: testLook
    var direction by remember { mutableStateOf(book?.direction ?: ReadDirection.LeftToRight) }
    var currentPage by remember {
        mutableStateOf((book?.position ?: 0).coerceIn(0, (source.pageCount - 1).coerceAtLeast(0)))
    }
    var scrubPage by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(alwaysShowControls) }
    var panel by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var settingScope by remember { mutableStateOf(SettingScope.ThisBook) }
    var rotationLocked by remember { mutableStateOf(false) }
    var testShift by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var widePages by remember(source) { mutableStateOf(loader.savedWidePages().orEmpty()) }
    val shift = liveBook?.spreadShift ?: testShift

    ReaderWindow(controls || panel, rotationLocked)
    LaunchedEffect(currentPage) { onPage(currentPage) }
    // Keys go to this screen, and a closed panel or dialog can leave them with nowhere to go.
    LaunchedEffect(panel, showChapters, showBookmarks) {
        if (!panel && !showChapters && !showBookmarks) focus.requestFocus()
    }
    BackHandler(enabled = panel) { panel = false }

    fun changeLook(change: (ReaderPrefs) -> ReaderPrefs) {
        val current = liveBook
        if (current == null) {
            val updated = change(ReaderPrefs(testLook.fit, testLook.animation, testLook.background))
            testLook = ReaderLook(
                updated.fit ?: testLook.fit,
                updated.animation ?: testLook.animation,
                updated.background ?: testLook.background,
            )
            return
        }
        scope.launch {
            when (settingScope) {
                SettingScope.ThisBook -> repository.setBookPrefs(current.id, change(current.readerPrefs))
                SettingScope.ThisType -> {
                    val prefs = library.typePrefs[current.type] ?: ReaderPrefs()
                    // A setting saved on the book would hide the wider one, so that field is cleared.
                    repository.setTypePrefs(current.type, change(prefs))
                    repository.setBookPrefs(current.id, clearChanged(current.readerPrefs, change))
                }
                SettingScope.AllBooks -> {
                    repository.setGlobalPrefs(change(library.globalPrefs))
                    BookType.entries.forEach { type ->
                        library.typePrefs[type]?.let { repository.setTypePrefs(type, clearChanged(it, change)) }
                    }
                    repository.setBookPrefs(current.id, clearChanged(current.readerPrefs, change))
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(look.background))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val isVolume = event.key == Key.VolumeDown || event.key == Key.VolumeUp
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isVolume
                val rightToLeft = direction == ReadDirection.RightToLeft
                val forward = when (event.key) {
                    Key.VolumeDown, Key.DirectionDown, Key.PageDown, Key.Spacebar -> true
                    Key.VolumeUp, Key.DirectionUp, Key.PageUp -> false
                    Key.DirectionRight -> !rightToLeft
                    Key.DirectionLeft -> rightToLeft
                    else -> return@onPreviewKeyEvent false
                }
                // Holding a key would restart the same turn over and over.
                if (event.nativeKeyEvent.repeatCount == 0) {
                    scope.launch { if (forward) nav.next() else nav.previous() }
                }
                true
            }
            .pointerInput(direction) {
                if (direction == ReadDirection.Vertical) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta.y
                            if (delta == 0f) continue
                            scope.launch { if (delta > 0f) nav.next() else nav.previous() }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val twoPage = showsTwoPages(DeviceSettings.twoPage, direction, maxWidth.value, maxHeight.value)
        val spreads = remember(source, twoPage, shift, widePages) {
            buildSpreads(source.pageCount, twoPage, shift, widePages)
        }
        LaunchedEffect(source, twoPage) {
            if (twoPage) loader.widePages { widePages = it }
        }

        if (direction == ReadDirection.Vertical) {
            ContinuousReader(source, loader, width, height, currentPage, look, nav, { currentPage = it }) { controls = !controls }
        } else {
            key(spreads) {
                PagedReader(
                    source = source,
                    loader = loader,
                    spreads = spreads,
                    startPage = currentPage,
                    direction = direction,
                    look = look,
                    width = width,
                    height = height,
                    nav = nav,
                    // Only a real page turn moves the position, not rearranging the same page into new spreads.
                    onSpread = { spread ->
                        if (currentPage !in spread.pages) {
                            currentPage = if (spread == spreads.last()) spread.pages.last() else spread.pages.first()
                        }
                    },
                    onMenu = { controls = !controls },
                )
            }
        }

        if (controls) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "${currentPage + 1} / ${source.pageCount}   ${book?.title ?: source.label(currentPage)}",
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (source.pageCount > 1) {
                    Slider(
                        value = scrubPage ?: currentPage.toFloat(),
                        onValueChange = { scrubPage = it },
                        onValueChangeFinished = {
                            val target = scrubPage?.toInt() ?: currentPage
                            scrubPage = null
                            scope.launch { nav.goTo(target) }
                        },
                        valueRange = 0f..(source.pageCount - 1).toFloat(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ panel = true }) { Text("Settings", color = Color.White) }
                    if (source.chapters.isNotEmpty()) {
                        OutlinedButton({ showChapters = true }) { Text("Chapters", color = Color.White) }
                    }
                    if (book != null) {
                        OutlinedButton({ showBookmarks = true }) { Text("Bookmarks", color = Color.White) }
                    }
                    if (alwaysShowControls) {
                        Button(
                            onClick = {
                                running = true
                                scope.launch {
                                    runSpeedTest(source, width)
                                    running = false
                                }
                            },
                            enabled = !running,
                        ) { Text(if (running) "Testing" else "Speed test") }
                    }
                }
            }
        }

        if (panel) {
            ReaderPanel(
                direction = direction,
                look = look,
                type = liveBook?.type,
                scope = settingScope,
                shift = shift,
                showingTwoPages = twoPage,
                rotationLocked = rotationLocked,
                actions = ReaderPanelActions(
                    onDirection = { option ->
                        direction = option
                        liveBook?.let { current -> scope.launch { repository.setDirection(current.id, option) } }
                    },
                    onFit = { option -> changeLook { it.copy(fit = option) } },
                    onAnimation = { option -> changeLook { it.copy(animation = option) } },
                    onBackground = { option -> changeLook { it.copy(background = option) } },
                    onShift = { option ->
                        if (liveBook != null) {
                            scope.launch { repository.setSpreadShift(liveBook.id, option) }
                        } else {
                            testShift = option
                        }
                    },
                    onScope = { settingScope = it },
                    onRotationLock = { rotationLocked = it },
                    onClose = { panel = false },
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showChapters) {
        ChapterDialog(
            chapters = source.chapters,
            onChoose = { page ->
                showChapters = false
                scope.launch { nav.goTo(page) }
            },
            onClose = { showChapters = false },
        )
    }
    if (showBookmarks && liveBook != null) {
        BookmarkDialog(
            bookmarks = library.bookmarks[liveBook.id].orEmpty(),
            onChoose = { page ->
                showBookmarks = false
                scope.launch { nav.goTo(page) }
            },
            onAdd = { scope.launch { repository.addBookmark(liveBook.id, currentPage, "Page ${currentPage + 1}") } },
            onRemove = { id -> scope.launch { repository.removeBookmark(id) } },
            onClose = { showBookmarks = false },
        )
    }
}

/** Clears whichever field a change sets, so the book follows the wider setting. */
private fun clearChanged(prefs: ReaderPrefs, change: (ReaderPrefs) -> ReaderPrefs): ReaderPrefs {
    val probe = change(ReaderPrefs())
    return ReaderPrefs(
        fit = prefs.fit.takeIf { probe.fit == null },
        animation = prefs.animation.takeIf { probe.animation == null },
        background = prefs.background.takeIf { probe.background == null },
    )
}

/** Full screen while reading, with the system bars back when the controls show. */
@Composable
private fun ReaderWindow(showBars: Boolean, rotationLocked: Boolean) {
    val activity = LocalActivity.current
    val view = LocalView.current
    val keepOn = DeviceSettings.keepScreenOn

    DisposableEffect(showBars, activity) {
        val window = activity?.window
        if (window != null) {
            val bars = WindowCompat.getInsetsController(window, view)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showBars) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(rotationLocked, activity) {
        activity?.requestedOrientation =
            if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

private suspend fun runSpeedTest(source: PageSource, width: Int) = withContext(Dispatchers.IO) {
    val last = source.pageCount - 1
    ResultsLog.add("Speed test: ${source.title}, width $width")
    val stops = listOf(
        "First page" to 0,
        "Middle page" to last / 2,
        "Last page" to last,
        "Back to page 2" to minOf(1, last),
        "Next page" to minOf(2, last),
    )
    for ((label, page) in stops) {
        timed(label) { runCatching { source.render(page, width)?.recycle() } }
    }
    val heap = Runtime.getRuntime()
    val native = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    ResultsLog.add("Memory: Java ${(heap.totalMemory() - heap.freeMemory()) / (1024 * 1024)} MB, native $native MB")
}

@Composable
private fun PagedReader(
    source: PageSource,
    loader: PageLoader,
    spreads: List<Spread>,
    startPage: Int,
    direction: ReadDirection,
    look: ReaderLook,
    width: Int,
    height: Int,
    nav: ReaderNav,
    onSpread: (Spread) -> Unit,
    onMenu: () -> Unit,
) {
    val vertical = direction == ReadDirection.VerticalPaged
    val rightToLeft = direction == ReadDirection.RightToLeft
    val pager = rememberPagerState(initialPage = spreadIndexOf(spreads, startPage)) { spreads.size }
    val curl = remember { CurlEffect.create() }
    var zoomedPage by remember { mutableStateOf(-1) }
    val animation = when {
        look.animation == PageAnimation.Curl && (vertical || curl == null) -> PageAnimation.Slide
        else -> look.animation
    }

    suspend fun turnTo(index: Int) {
        if (index !in spreads.indices) return
        when (animation) {
            PageAnimation.None -> pager.scrollToPage(index)
            PageAnimation.Curl -> pager.animateScrollToPage(index, animationSpec = tween(550))
            else -> pager.animateScrollToPage(index, animationSpec = tween(280))
        }
    }

    SideEffect {
        nav.next = { turnTo(pager.targetPage + 1) }
        nav.previous = { turnTo(pager.targetPage - 1) }
        nav.goTo = { page -> pager.scrollToPage(spreadIndexOf(spreads, page)) }
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { index ->
            spreads.getOrNull(index)?.let { onSpread(it) }
        }
    }
    LaunchedEffect(pager, width) {
        snapshotFlow { pager.settledPage }.collectLatest { index ->
            for (near in listOf(index + 1, index - 1, index + 2)) {
                val spread = spreads.getOrNull(near) ?: continue
                loader.readAhead(spread.pages, width / spread.pages.size)
            }
        }
    }

    val pageContent: @Composable (Int) -> Unit = { index ->
        SpreadView(
            source = source,
            loader = loader,
            spread = spreads[index],
            index = index,
            pager = pager,
            rightToLeft = rightToLeft,
            vertical = vertical,
            animation = animation,
            curl = curl,
            look = look,
            width = width,
            height = height,
            onZoomed = { isZoomed ->
                if (isZoomed) {
                    zoomedPage = index
                } else if (zoomedPage == index) {
                    zoomedPage = -1
                }
            },
            onTurn = { forward -> turnTo(pager.targetPage + if (forward) 1 else -1) },
            onLink = { page -> pager.scrollToPage(spreadIndexOf(spreads, page)) },
            onMenu = onMenu,
        )
    }

    if (vertical) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = zoomedPage != pager.currentPage,
        ) { index -> pageContent(index) }
    } else {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            reverseLayout = rightToLeft,
            userScrollEnabled = zoomedPage != pager.currentPage,
        ) { index -> pageContent(index) }
    }
}

/** Pins fading and curling pages in place, so they turn over rather than slide away. */
private fun Modifier.pageTransition(
    pager: PagerState,
    index: Int,
    animation: PageAnimation,
    vertical: Boolean,
    rightToLeft: Boolean,
    curl: CurlEffect?,
) = graphicsLayer {
    val distance = (index - pager.currentPage) - pager.currentPageOffsetFraction
    val offset = distance.coerceIn(-1f, 1f)
    // Only pages mid-turn are pinned. A resting neighbor on screen would take the current page's touches.
    val pinned = abs(distance) < 1f && (animation == PageAnimation.Fade || animation == PageAnimation.Curl)
    if (pinned && vertical) translationY = -offset * size.height
    if (pinned && !vertical) translationX = -offset * size.width * if (rightToLeft) -1f else 1f
    if (pinned && animation == PageAnimation.Fade) alpha = if (offset < 0f) 1f + offset else 1f
    if (animation == PageAnimation.Curl) {
        renderEffect = if (pinned && offset < 0f && curl != null) {
            curl.at(size.width, size.height, -offset, rightToLeft)
        } else {
            null
        }
    }
}

@Composable
private fun SpreadView(
    source: PageSource,
    loader: PageLoader,
    spread: Spread,
    index: Int,
    pager: PagerState,
    rightToLeft: Boolean,
    vertical: Boolean,
    animation: PageAnimation,
    curl: CurlEffect?,
    look: ReaderLook,
    width: Int,
    height: Int,
    onZoomed: (Boolean) -> Unit,
    onTurn: suspend (Boolean) -> Unit,
    onLink: suspend (Int) -> Unit,
    onMenu: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val zoom = remember(index) { ZoomState() }
    val visualPages = if (rightToLeft) spread.pages.reversed() else spread.pages
    val latestOnMenu by rememberUpdatedState(onMenu)
    val latestOnTurn by rememberUpdatedState(onTurn)
    var links by remember(spread) { mutableStateOf<Map<Int, Pair<List<PageLink>, PageSize?>>>(emptyMap()) }

    LaunchedEffect(zoom.scale > 1f) { onZoomed(zoom.scale > 1f) }
    LaunchedEffect(pager, index) {
        snapshotFlow { pager.settledPage }.collect { if (it != index) zoom.reset() }
    }
    LaunchedEffect(spread) {
        if (source.isVector) {
            links = withContext(Dispatchers.IO) {
                spread.pages.associateWith { page ->
                    runCatching { source.links(page) to source.size(page) }.getOrDefault(emptyList<PageLink>() to null)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(-index.toFloat())
            .pageTransition(pager, index, animation, vertical, rightToLeft, curl)
            .background(Color(look.background))
            .zoomGestures(zoom, width, height)
            .pointerInput(spread, width, height, rightToLeft, look.fit) {
                detectReaderTaps(
                    turnsPage = { at -> zoom.scale == 1f && at.x !in (width / 3f)..(width * 2f / 3f) },
                    onDoubleTap = { at -> zoom.toggle(at, width, height) },
                    onTap = { at ->
                        val link = if (zoom.scale == 1f && look.fit == FitMode.Screen) {
                            linkAt(at, visualPages, links, width, height)
                        } else {
                            null
                        }
                        val forward = (at.x > width * 2f / 3f) != rightToLeft
                        when {
                            link != null -> scope.launch { onLink(link.target) }
                            at.x in (width / 3f)..(width * 2f / 3f) -> latestOnMenu()
                            else -> scope.launch { latestOnTurn(forward) }
                        }
                    },
                )
            },
    ) {
        val sharp = source.isVector && zoom.scale > 1.5f
        val slotWidth = width / visualPages.size
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.x
                    translationY = zoom.y
                },
        ) {
            visualPages.forEachIndexed { slot, page ->
                val alignment = when {
                    visualPages.size == 1 -> Alignment.Center
                    slot == 0 -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                }
                PageImage(
                    source = source,
                    loader = loader,
                    page = page,
                    width = if (sharp) slotWidth * 2 else slotWidth,
                    fit = if (vertical || visualPages.size > 1) FitMode.Screen else look.fit,
                    alignment = alignment,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                )
            }
        }
    }
}

/** Finds a PDF link under a tap, allowing for where the page sits on screen. */
private fun linkAt(
    at: Offset,
    visualPages: List<Int>,
    links: Map<Int, Pair<List<PageLink>, PageSize?>>,
    width: Int,
    height: Int,
): PageLink? {
    val slotWidth = width.toFloat() / visualPages.size
    val slot = (at.x / slotWidth).toInt().coerceIn(0, visualPages.lastIndex)
    val (pageLinks, size) = links[visualPages[slot]] ?: return null
    if (size == null || pageLinks.isEmpty()) return null
    val scale = minOf(slotWidth / size.width, height.toFloat() / size.height)
    val shownWidth = size.width * scale
    val shownHeight = size.height * scale
    val left = when {
        visualPages.size == 1 -> (slotWidth - shownWidth) / 2f
        slot == 0 -> slotWidth - shownWidth
        else -> slotWidth
    }
    val x = (at.x - left) / shownWidth
    val y = (at.y - (height - shownHeight) / 2f) / shownHeight
    return pageLinks.firstOrNull { x in it.left..it.right && y in it.top..it.bottom }
}

@Composable
private fun PageImage(
    source: PageSource,
    loader: PageLoader,
    page: Int,
    width: Int,
    fit: FitMode,
    alignment: Alignment,
    modifier: Modifier,
) {
    var image by remember(page) { mutableStateOf(loader.cachedPage(page, width)) }
    var failed by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page, width) {
        val loaded = loader.page(page, width)
        if (loaded != null) image = loaded
        failed = image == null
    }
    val shown: Bitmap? = image

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            shown != null -> {
                val picture = remember(shown) { shown.asImageBitmap() }
                if (fit == FitMode.Width) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = picture,
                            contentDescription = source.label(page),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(shown.width.toFloat() / shown.height),
                            contentScale = ContentScale.FillBounds,
                        )
                    }
                } else {
                    Image(
                        bitmap = picture,
                        contentDescription = source.label(page),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = if (fit == FitMode.Height) ContentScale.FillHeight else ContentScale.Fit,
                        alignment = alignment,
                    )
                }
            }
            failed -> Text("This page couldn't be read", color = Color.Gray)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun ContinuousReader(
    source: PageSource,
    loader: PageLoader,
    width: Int,
    height: Int,
    startPage: Int,
    look: ReaderLook,
    nav: ReaderNav,
    onPage: (Int) -> Unit,
    onMenu: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rows = remember(source) {
        mutableStateListOf<ReaderRow>().apply { addAll(List(source.pageCount) { ReaderRow(it) }) }
    }
    val aspects = remember(source) { mutableStateMapOf<ReaderRow, Float>() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val zoom = remember { ZoomState() }

    SideEffect {
        nav.next = { list.animateScrollBy(height * 0.85f) }
        nav.previous = { list.animateScrollBy(-height * 0.85f) }
        nav.goTo = { page ->
            val index = rows.indexOfFirst { it.page == page }
            if (index >= 0) list.scrollToItem(index)
        }
    }

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { index ->
            rows.getOrNull(index)?.let { onPage(it.page) }
        }
    }
    // Loading ahead of the scroll is what keeps fast scrolling from showing empty rows.
    LaunchedEffect(list, width) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collectLatest { last ->
            for (index in (last + 1)..(last + ROWS_AHEAD)) {
                val row = rows.getOrNull(index) ?: break
                if (row.top >= 0) {
                    loader.slice(row.page, row.top, row.bottom, width)
                    continue
                }
                // A tall page decoded whole would be shrunk, so it waits to be cut into slices.
                val size = loader.size(row.page)
                if (size == null || !size.isTall || !source.canSlice) loader.page(row.page, width)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .zoomGestures(zoom, width, height, panWhenZoomed = false)
            .pointerInput(width, height) {
                detectReaderTaps(
                    turnsPage = { at -> zoom.scale == 1f && at.y !in (height / 3f)..(height * 2f / 3f) },
                    onDoubleTap = { at -> zoom.toggle(at, width, height) },
                    onTap = { at ->
                        when {
                            at.y < height / 3f -> scope.launch { nav.previous() }
                            at.y > height * 2f / 3f -> scope.launch { nav.next() }
                            else -> onMenu()
                        }
                    },
                )
            },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.x
                },
        ) {
            itemsIndexed(rows, key = { _, row -> "${row.page}:${row.top}" }) { _, row ->
                VerticalRow(source, loader, width, look, row, rows, aspects)
            }
        }
    }
}

@Composable
private fun VerticalRow(
    source: PageSource,
    loader: PageLoader,
    width: Int,
    look: ReaderLook,
    row: ReaderRow,
    rows: SnapshotStateList<ReaderRow>,
    aspects: SnapshotStateMap<ReaderRow, Float>,
) {
    val isSlice = row.top >= 0
    var image by remember(row, width) {
        mutableStateOf(if (isSlice) loader.cachedSlice(row.page, row.top, width) else loader.cachedPage(row.page, width))
    }
    LaunchedEffect(row, width) {
        if (isSlice) {
            if (image == null) image = loader.slice(row.page, row.top, row.bottom, width)
            return@LaunchedEffect
        }
        val size = loader.size(row.page)
        if (size != null && size.isTall && source.canSlice) {
            loader.prepareTallPage(row.page, size)
            replaceWithSlices(rows, aspects, row, size)
        } else {
            if (size != null) aspects[row] = size.width.toFloat() / size.height
            if (image == null) image = loader.page(row.page, width)
        }
    }

    val shown = image
    val aspect = when {
        shown != null -> shown.width.toFloat() / shown.height
        else -> aspects[row] ?: DEFAULT_ASPECT
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color(look.background)),
    ) {
        if (shown != null) {
            val picture = remember(shown) { shown.asImageBitmap() }
            Image(
                bitmap = picture,
                contentDescription = source.label(row.page),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

private fun replaceWithSlices(
    rows: SnapshotStateList<ReaderRow>,
    aspects: SnapshotStateMap<ReaderRow, Float>,
    row: ReaderRow,
    size: PageSize,
) {
    val index = rows.indexOf(row)
    if (index < 0) return
    ResultsLog.add("Tall page ${row.page + 1}: ${size.width}x${size.height}, cutting into slices")
    val slices = (0 until size.height step ImageDecode.SLICE_HEIGHT).map { top ->
        ReaderRow(row.page, top, minOf(top + ImageDecode.SLICE_HEIGHT, size.height))
    }
    // Knowing each slice's shape up front keeps the list from jumping as they load.
    slices.forEach { aspects[it] = size.width.toFloat() / (it.bottom - it.top) }
    rows.removeAt(index)
    rows.addAll(index, slices)
}
