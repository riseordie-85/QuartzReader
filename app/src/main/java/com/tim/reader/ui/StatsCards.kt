package com.tim.reader.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tim.reader.data.Book
import com.tim.reader.data.LibraryState
import com.tim.reader.library.rememberCover
import com.tim.reader.reader.ShareRatio
import com.tim.reader.reader.saveToGallery
import com.tim.reader.reader.shareImage
import com.tim.reader.stats.AchievementStatus
import com.tim.reader.stats.FinishedRecord
import com.tim.reader.stats.MonthRecap
import com.tim.reader.stats.Rating
import com.tim.reader.stats.YearRecap
import com.tim.reader.stats.formatMinutes
import com.tim.reader.theme.BarLook
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale

/** Cards are designed 400 units wide and drawn 1,080 pixels wide, whatever the screen. */
private const val DESIGN_WIDTH = 400f
private const val CARD_PIXELS = 1080f

/** What a stats share card shows. */
sealed interface StatsCard {
    class Finished(val book: Book, val seriesTitle: String?, val rating: Rating?, val minutes: Long, val days: Long?) : StatsCard
    class Month(val recap: MonthRecap) : StatsCard
    class Year(val recap: YearRecap) : StatsCard
    class Unlocked(val status: AchievementStatus) : StatsCard
}

/**
 * A full-screen preview of a stats card, with its size and style choices and buttons to share or
 * save it. The card is drawn with the chosen shelf style and copied to an image when shared.
 */
@Composable
fun StatsShareOverlay(card: StatsCard, state: LibraryState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val styles = remember(state.shelves, state.homeTheme) {
        listOfNotNull(state.homeTheme?.let { "Home" to it }) + state.shelves.map { it.name to it.theme }
    }
    if (styles.isEmpty()) return
    var styleIndex by remember { mutableStateOf(0) }
    var ratio by remember { mutableStateOf(ShareRatio.Square) }
    val layer = rememberGraphicsLayer()

    /** The card as a plain image, ready to save or send. */
    suspend fun capture(): Bitmap = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF0000000))
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val outer = LocalDensity.current
                val cardWidth = with(outer) { CARD_PIXELS.toDp() }
                val cardHeight = with(outer) { ratio.height.toFloat().toDp() }
                val scale = minOf(maxWidth / cardWidth, maxHeight / cardHeight)
                Box(Modifier.size(cardWidth * scale, cardHeight * scale), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .requiredSize(cardWidth, cardHeight)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            },
                    ) {
                        CompositionLocalProvider(LocalDensity provides Density(CARD_PIXELS / DESIGN_WIDTH, 1f)) {
                            CardContent(card, styles[styleIndex].second, state, tall = ratio == ShareRatio.Story)
                        }
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareRatio.entries.forEach { option -> CardChoice(option.label, ratio == option) { ratio = option } }
                styles.forEachIndexed { index, (name, _) -> CardChoice(name, styleIndex == index) { styleIndex = index } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardAction("Close", Modifier.weight(1f), onClose)
                CardAction("Save", Modifier.weight(1f)) {
                    scope.launch {
                        val saved = runCatching { capture() }.getOrNull()?.let { image -> withContext(Dispatchers.IO) { saveToGallery(context, image) } } == true
                        Toast.makeText(context, if (saved) "Saved to Pictures" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
                    }
                }
                CardAction("Share", Modifier.weight(1f)) {
                    scope.launch {
                        val failure = runCatching {
                            val image = capture()
                            withContext(Dispatchers.IO) { shareImage(context, image) }
                        }.exceptionOrNull()
                        if (failure != null) Toast.makeText(context, "Couldn't share the image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun CardChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) QUICK_BLUE else Color.White)) {
        Text(label, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun CardAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier, border = BorderStroke(1.dp, Color.White)) { Text(label, color = Color.White) }
}

// Everything below is drawn in card units: 400 wide, and 400 or 711 tall.

@Composable
private fun CardContent(card: StatsCard, theme: ShelfTheme, state: LibraryState, tall: Boolean) {
    ThemedBackground(theme.background, Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = if (tall) 48.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (tall) 18.dp else 10.dp, Alignment.CenterVertically),
        ) {
            when (card) {
                is StatsCard.Finished -> FinishedContent(card, theme, tall)
                is StatsCard.Month -> MonthContent(card.recap, theme, state, tall)
                is StatsCard.Year -> YearContent(card.recap, theme, state, tall)
                is StatsCard.Unlocked -> UnlockedContent(card.status, theme, tall)
            }
            Footer(theme.header)
        }
    }
}

@Composable
private fun CardPanel(look: BarLook, content: @Composable ColumnScope.() -> Unit) {
    val shape = cornerShape(look.cornerDp)
    Column(
        Modifier
            .fillMaxWidth()
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun CardText(look: BarLook, text: String, size: Int, bold: Boolean = false, lines: Int = 2, faded: Boolean = false) {
    Text(
        text,
        color = Color(look.textColor).copy(alpha = if (faded) 0.75f else 1f),
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Footer(look: BarLook) {
    Text("Quartz Reader", color = Color(look.textColor).copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CardTitle(text: String, theme: ShelfTheme, tall: Boolean) {
    // The shelf's title style, sized for the card.
    ThemedText(text, theme.title.copy(sizeSp = if (tall) 40f else 30f))
}

@Composable
private fun Cover(path: String?, width: Int) {
    val cover by rememberCover(path)
    val shown = cover
    if (shown != null) {
        Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.width(width.dp))
    }
}

@Composable
private fun FinishedContent(card: StatsCard.Finished, theme: ShelfTheme, tall: Boolean) {
    val look = theme.header
    CardTitle(if (card.seriesTitle != null) "Caught up!" else "Finished!", theme, tall)
    Cover(card.book.displayCover, if (tall) 230 else 110)
    CardPanel(look) {
        CardText(look, card.seriesTitle ?: card.book.title, 19, bold = true)
        if (card.book.author.isNotBlank()) CardText(look, card.book.author, 13, faded = true, lines = 1)
        card.rating?.let { StarRating(it.stars, Color(look.iconColor), starSize = 22.dp) }
        if (card.minutes > 0) {
            val over = card.days?.takeIf { it > 1 }?.let { " over $it days" }.orEmpty()
            CardText(look, "Read in ${formatMinutes(card.minutes)}$over", 13)
        }
        val review = card.rating?.review.orEmpty()
        if (review.isNotBlank()) {
            if (card.rating?.spoiler == true) {
                CardText(look, "Review hidden: it has spoilers", 12, faded = true)
            } else {
                CardText(look, "“$review”", 13, lines = if (tall) 10 else 3)
            }
        }
    }
}

@Composable
private fun StatRow(look: BarLook, items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (value, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CardText(look, value, 20, bold = true, lines = 1)
                CardText(look, label, 11, faded = true, lines = 1)
            }
        }
    }
}

@Composable
private fun CoverRow(books: List<Book>, count: Int, rows: Int) {
    if (books.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        books.take(count * rows).chunked(count).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { book -> Box(Modifier.width((356 / count - 6).dp)) { Cover(book.displayCover, 356 / count - 6) } }
            }
        }
    }
}

private fun finishedBooks(records: List<FinishedRecord>, state: LibraryState): List<Book> {
    val byHash = state.books.associateBy { it.hash }
    return records.mapNotNull { it.bookHash?.let(byHash::get) }.distinctBy { it.id }
}

@Composable
private fun MonthContent(recap: MonthRecap, theme: ShelfTheme, state: LibraryState, tall: Boolean) {
    val look = theme.header
    val name = recap.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    CardTitle("$name ${recap.month.year}", theme, tall)
    CardPanel(look) {
        CardText(look, formatMinutes(recap.minutes), 34, bold = true, lines = 1)
        CardText(look, "of reading", 13, faded = true)
        Spacer(Modifier.height(6.dp))
        StatRow(
            look,
            listOf(
                "${recap.finished.size}" to "finished",
                "${recap.daysRead}" to if (recap.daysRead == 1) "day read" else "days read",
                "${recap.streak}" to "best streak",
            ),
        )
    }
    CoverRow(finishedBooks(recap.finished, state), count = 5, rows = if (tall) 3 else 1)
}

@Composable
private fun YearContent(recap: YearRecap, theme: ShelfTheme, state: LibraryState, tall: Boolean) {
    val look = theme.header
    val byHash = remember(state.books) { state.books.associateBy { it.hash } }
    CardTitle("${recap.year} in Reading", theme, tall)
    CardPanel(look) {
        StatRow(
            look,
            listOf(
                "${recap.minutes / 60}" to "hours",
                "${recap.finished.size}" to "finished",
                "${recap.daysRead}" to "days read",
                "${recap.bestStreak}" to "best streak",
            ),
        )
    }
    CardPanel(look) {
        if (recap.topSeries.isNotEmpty()) {
            CardText(look, "Top series", 11, faded = true)
            recap.topSeries.take(if (tall) 3 else 1).forEach { (title, minutes) ->
                CardText(look, "$title · ${formatMinutes(minutes)}", 14, bold = true, lines = 1)
            }
        }
        recap.fastest?.let { (read, ms) ->
            val hours = ms / 3_600_000
            val span = if (hours < 24) "${hours.coerceAtLeast(1)} ${if (hours <= 1) "hour" else "hours"}" else "${hours / 24} days"
            CardText(look, "Fastest read: ${read.bookHash?.let(byHash::get)?.title ?: read.title ?: "a book"} in $span", 13, lines = 2)
        }
        if (recap.nightShare >= 10) CardText(look, "${recap.nightShare}% of it after dark", 13)
    }
    CoverRow(finishedBooks(recap.finished, state).takeLast(if (tall) 15 else 5).reversed(), count = 5, rows = if (tall) 3 else 1)
}

@Composable
private fun UnlockedContent(status: AchievementStatus, theme: ShelfTheme, tall: Boolean) {
    val look = theme.header
    val achievement = status.achievement
    GemIcon(status.gem, if (tall) 180.dp else 110.dp)
    CardTitle(achievement.name, theme, tall)
    CardPanel(look) {
        status.gem?.let { CardText(look, it.label, 18, bold = true) }
        val tier = achievement.tiers.getOrNull(status.tier)?.first
        if (tier != null) CardText(look, achievement.goal(tier), 14)
    }
}
