package com.tim.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.Book
import com.tim.reader.data.ShelfItem
import com.tim.reader.library.ShadowCache
import com.tim.reader.library.rememberCover
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.BookLook
import com.tim.reader.theme.LedgeAlign
import com.tim.reader.theme.LedgeLook
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.StrokeLook
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val COVER_ASPECT = 2f / 3f

/** The gap between covers, kept small enough that lots of columns still leave room for books. */
fun bookSpacing(look: BookLook, columns: Int) = minOf(look.spacingDp, 144f / columns).dp

/** How many books fit across at this width. */
fun columnsFor(widthDp: Float) = (widthDp / 90f).toInt().coerceIn(3, 8)

class HeaderActions(
    val onHome: () -> Unit,
    val onImport: () -> Unit,
    val onShelves: () -> Unit,
    val onSettings: () -> Unit,
)

class BookActions(
    val onOpen: (Book) -> Unit,
    val onDetails: (Book) -> Unit,
    val onSeries: (String) -> Unit,
    val onSeriesEdit: (String) -> Unit,
)

@Composable
fun LibraryHeader(look: BarLook, actions: HeaderActions) {
    val shape = cornerShape(look.cornerDp)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("Quartz") }
                append(" Reader")
            },
            color = Color(look.textColor),
            fontSize = 26.sp,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Home", onClick = actions.onHome),
        )
        val icon = Color(look.iconColor)
        GlyphButton(Glyph.Plus, icon, "Import", actions.onImport)
        GlyphButton(Glyph.Book, icon, "Shelves", actions.onShelves)
        GlyphButton(Glyph.Gear, icon, "Settings", actions.onSettings)
    }
}

@Composable
fun Ledge(look: LedgeLook) {
    val shape = cornerShape(look.cornerDp)
    val alignment = when (look.align) {
        LedgeAlign.Left -> Alignment.CenterStart
        LedgeAlign.Center -> Alignment.Center
        LedgeAlign.Right -> Alignment.CenterEnd
    }
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(alignment)
                .fillMaxWidth(look.widthFraction.coerceIn(0.1f, 1f))
                .height(look.heightDp.dp)
                .themedShadow(look.shadow, shape)
                .themedFill(look.fill, shape)
                .themedStroke(look.stroke, shape),
        )
    }
}

/** A book cover standing on its bottom edge, with its pre-rendered shadow. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookTile(
    coverPath: String?,
    look: BookLook,
    progress: Float,
    label: String,
    badge: Int?,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover by rememberCover(coverPath)
    Box(modifier.aspectRatio(COVER_ASPECT)) {
        Spacer(
            Modifier
                .matchParentSize()
                .combinedClickable(onClickLabel = label, onClick = onClick, onLongClick = onLongClick)
                .drawWithCache {
                    val image = cover
                    val boxWidth = size.width
                    val boxHeight = size.height
                    val imageAspect = if (image != null) image.width.toFloat() / image.height else COVER_ASPECT
                    val width: Float
                    val height: Float
                    if (imageAspect > boxWidth / boxHeight) {
                        width = boxWidth
                        height = boxWidth / imageAspect
                    } else {
                        height = boxHeight
                        width = boxHeight * imageAspect
                    }
                    val left = ((boxWidth - width) / 2).roundToInt()
                    val top = (boxHeight - height).roundToInt()
                    val pixelWidth = width.roundToInt().coerceAtLeast(1)
                    val pixelHeight = height.roundToInt().coerceAtLeast(1)
                    val shadow = look.shadow
                    val shadowImage = shadow?.let { ShadowCache.get(it, pixelWidth, pixelHeight, density) }
                    val shadowPad = shadow?.let { ShadowCache.padding(it, density) } ?: 0
                    val radians = Math.toRadians((shadow?.angle ?: 0f).toDouble())
                    val distance = (shadow?.distanceDp ?: 0f) * density
                    val shadowLeft = left - shadowPad + (cos(radians) * distance).roundToInt()
                    val shadowTop = top - shadowPad + (sin(radians) * distance).roundToInt()
                    val barHeight = min(4f * density, height / 20f)

                    onDrawBehind {
                        if (shadowImage != null) {
                            drawImage(image = shadowImage, dstOffset = IntOffset(shadowLeft, shadowTop))
                        }
                        if (image != null) {
                            drawImage(
                                image = image,
                                dstOffset = IntOffset(left, top),
                                dstSize = IntSize(pixelWidth, pixelHeight),
                            )
                        } else {
                            drawRect(
                                Color(0x33000000),
                                Offset(left.toFloat(), top.toFloat()),
                                Size(width, height),
                            )
                        }
                        if (look.highlight) {
                            drawRect(
                                Color.White.copy(alpha = 0.35f),
                                Offset(left.toFloat(), top.toFloat()),
                                Size(2f * density, height),
                            )
                        }
                        if (progress > 0f) {
                            drawRect(
                                Color(0xFF4C8DFF),
                                Offset(left.toFloat(), top + height - barHeight),
                                Size(width * progress.coerceIn(0f, 1f), barHeight),
                            )
                        }
                        if (selected) {
                            drawRect(
                                Color(0xFF4C8DFF),
                                Offset(left.toFloat(), top.toFloat()),
                                Size(width, height),
                                style = Stroke(3f * density),
                            )
                        }
                    }
                },
        )
        if (badge != null) {
            Text(
                text = badge.toString(),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ShelfItemTile(
    item: ShelfItem,
    look: BookLook,
    actions: BookActions,
    modifier: Modifier = Modifier,
    inCoverPane: Boolean = false,
) {
    // The large cover opens on one tap. Shelf tiles select first while the split view shows.
    val selection = if (inCoverPane) null else LocalCoverSelection.current
    val selected by remember(selection, item.key) { derivedStateOf { selection?.key == item.key } }
    val onTap = { tapItem(item, selection, actions) }
    when (item) {
        is ShelfItem.Single -> BookTile(
            coverPath = item.book.displayCover,
            look = look,
            progress = item.book.progress,
            label = item.book.title,
            badge = null,
            selected = selected,
            onClick = onTap,
            onLongClick = { actions.onDetails(item.book) },
            modifier = modifier,
        )
        is ShelfItem.Stack -> {
            val first = item.volumes.first()
            BookTile(
                coverPath = first.displayCover,
                look = look,
                progress = 0f,
                label = item.series.title,
                badge = item.volumes.size,
                selected = selected,
                onClick = onTap,
                onLongClick = { actions.onSeriesEdit(item.series.id) },
                modifier = modifier,
            )
        }
    }
}

fun ShelfItem.displayTitle() = when (this) {
    is ShelfItem.Single -> book.title
    is ShelfItem.Stack -> series.title
}

/** One row of covers standing on one ledge. */
@Composable
fun ShelfRow(items: List<ShelfItem>, columns: Int, theme: ShelfTheme, actions: BookActions) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(bookSpacing(theme.book, columns)),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(columns) { index ->
                val item = items.getOrNull(index)
                if (item != null) {
                    ShelfItemTile(item, theme.book, actions, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Ledge(theme.ledge)
    }
}

fun LazyListScope.shelfRows(items: List<ShelfItem>, columns: Int, theme: ShelfTheme, actions: BookActions) {
    val rows = items.chunked(columns)
    items(rows, key = { row -> row.first().key }) { row ->
        ShelfRow(row, columns, theme, actions)
    }
}

/** The small outlined pill used for View All and Sort by. */
@Composable
fun PillButton(text: String, color: Color, stroke: StrokeLook? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = color,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(onClick = onClick)
            .border(stroke?.widthDp?.dp ?: 1.5.dp, stroke?.let { Color(it.color) } ?: color, shape)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    )
}
