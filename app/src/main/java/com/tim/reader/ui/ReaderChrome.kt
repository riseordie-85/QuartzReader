package com.tim.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Bookmark
import com.tim.reader.engine.Chapter

val PANEL_COLOR = Color(0xBF000000)
private val PILL_SHAPE = RoundedCornerShape(50)
private val BAR_SHAPE = RoundedCornerShape(28.dp)
private val SHEET_SHAPE = RoundedCornerShape(28.dp)

/** What opens between the bars. */
enum class ReaderSheet { Settings, Advanced, Colors, Bookmarks, Chapters, Search, Notes }

/** Keeps touches on a panel from reaching the page behind it. */
private fun Modifier.blocksTouches() = pointerInput(Unit) { }

/**
 * The floating controls: a title bar on top, and below it either a timeline or an open sheet.
 * Both readers share this. Pass null for [isBookmarked] when bookmarks aren't available.
 */
@Composable
fun ReaderChrome(
    title: String,
    page: Int,
    pageCount: Int,
    isBookmarked: Boolean?,
    hasChapters: Boolean,
    hasSearch: Boolean = false,
    hasNotes: Boolean = false,
    sheet: ReaderSheet?,
    onSheet: (ReaderSheet?) -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    onSeek: (Int) -> Unit,
    sheetContent: @Composable ColumnScope.(ReaderSheet) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (sheet != null) Modifier.blocksTouches() else Modifier)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val icons: @Composable () -> Unit = {
                fun toggle(target: ReaderSheet) = onSheet(if (sheet == target) null else target)
                if (hasChapters) GlyphButton(Glyph.List, Color.White, "Contents", iconSize = 30.dp) { toggle(ReaderSheet.Chapters) }
                if (hasSearch) GlyphButton(Glyph.Search, Color.White, "Search", iconSize = 30.dp) { toggle(ReaderSheet.Search) }
                if (hasNotes) GlyphButton(Glyph.Notes, Color.White, "Highlights and notes", iconSize = 30.dp) { toggle(ReaderSheet.Notes) }
                GlyphButton(Glyph.Share, Color.White, "Share this page", iconSize = 30.dp, onClick = onShare)
                GlyphButton(Glyph.Gear, Color.White, "Reader settings", iconSize = 30.dp) { toggle(ReaderSheet.Settings) }
                if (isBookmarked != null) {
                    GlyphButton(
                        glyph = if (isBookmarked) Glyph.Bookmark else Glyph.BookmarkOutline,
                        color = Color.White,
                        description = if (isBookmarked) "Remove bookmark" else "Add bookmark",
                        iconSize = 30.dp,
                        onLongClick = { onSheet(ReaderSheet.Bookmarks) },
                        onClick = onBookmark,
                    )
                }
            }

            // The text reader has more icons than fit beside a title.
            if (hasSearch || hasNotes) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PANEL_COLOR, BAR_SHAPE)
                        .blocksTouches()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    TopTitle(title, Modifier.padding(start = 8.dp, bottom = 4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) { icons() }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PANEL_COLOR, PILL_SHAPE)
                        .blocksTouches()
                        .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopTitle(title, Modifier.weight(1f))
                    icons()
                }
            }

            if (sheet != null) {
                sheetContent(sheet)
            } else {
                Spacer(Modifier.weight(1f))
                if (pageCount > 1) ReaderTimeline(page, pageCount, onSeek)
            }
        }
    }
}

@Composable
private fun TopTitle(title: String, modifier: Modifier) {
    Text(
        title,
        fontSize = 24.sp,
        fontWeight = FontWeight.Light,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A round thumb on a plain white line, which reports where it stops. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PillSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    thumb: Color,
    onDone: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Float?) -> Unit = {},
) {
    var dragging by remember(value) { mutableStateOf<Float?>(null) }
    Slider(
        value = dragging ?: value,
        onValueChange = {
            dragging = it
            onDrag(it)
        },
        onValueChangeFinished = {
            dragging?.let(onDone)
            onDrag(null)
        },
        valueRange = range,
        thumb = {
            Box(
                Modifier
                    .size(26.dp)
                    .background(thumb, CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White, CircleShape),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun ReaderTimeline(page: Int, pageCount: Int, onSeek: (Int) -> Unit) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.toInt() ?: page

    Row(
        Modifier
            .fillMaxWidth()
            .background(PANEL_COLOR, PILL_SHAPE)
            .blocksTouches()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${shown + 1}/$pageCount", fontSize = 18.sp, fontWeight = FontWeight.Light)
        PillSlider(
            value = page.toFloat(),
            range = 0f..(pageCount - 1).toFloat(),
            thumb = Color.White,
            onDone = { onSeek(it.toInt()) },
            onDrag = { scrubbing = it },
            modifier = Modifier.weight(1f),
        )
    }
}

/** A panel with no heading, for sheets that lead with their own controls. */
@Composable
fun ColumnScope.ReaderSheetBody(
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(PANEL_COLOR, SHEET_SHAPE)
            .blocksTouches()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A panel filling the space under the title bar. */
@Composable
fun ColumnScope.ReaderSheetCard(
    title: String,
    onDone: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(PANEL_COLOR, SHEET_SHAPE)
            .blocksTouches()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onDone, border = BorderStroke(1.dp, Color.White)) { Text("Done") }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
fun ColumnScope.ChapterSheet(chapters: List<Chapter>, onChoose: (Int) -> Unit, onDone: () -> Unit) {
    ReaderSheetCard("Chapters", onDone) {
        chapters.forEach { chapter ->
            Text(
                chapter.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(chapter.firstPage) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
fun ColumnScope.BookmarkSheet(
    bookmarks: List<Bookmark>,
    onChoose: (Int) -> Unit,
    onRemove: (String) -> Unit,
    onDone: () -> Unit,
) {
    ReaderSheetCard("Bookmarks", onDone) {
        if (bookmarks.isEmpty()) {
            Text("Tap the bookmark icon to mark this page.", modifier = Modifier.padding(vertical = 10.dp))
        }
        bookmarks.sortedBy { it.page }.forEach { bookmark ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bookmark.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onChoose(bookmark.page) }
                        .padding(vertical = 10.dp),
                )
                TextButton({ onRemove(bookmark.id) }) { Text("Remove") }
            }
        }
    }
}
