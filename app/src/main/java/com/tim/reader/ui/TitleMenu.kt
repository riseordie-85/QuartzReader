package com.tim.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tim.reader.library.DeviceSettings
import com.tim.reader.theme.ButtonLook
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.fontFamily
import com.tim.reader.theme.themedFill
import com.tim.reader.theme.themedShadow
import com.tim.reader.theme.themedStroke
import kotlin.math.roundToInt

private const val FEWEST = 2
private const val MOST = 12

/** What the title menu offers: buttons, and the books per row slider when the view has rows. */
class TitleMenu(
    val buttons: List<Pair<String, () -> Unit>>,
    val rowKey: String?,
    val automaticRow: Int,
)

/** Shown in the title's color, so it reads as part of the title. */
@Composable
private fun TitleArrow(color: Color, sizeSp: Float) {
    val width = (sizeSp * 0.75f).dp
    Canvas(Modifier.size(width, width * 0.6f)) {
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2, size.height)
            close()
        }
        drawPath(arrow, color)
    }
}

/** Takes the header's icon color, with text that stays readable on it. */
@Composable
private fun MenuButton(label: String, fill: Color, corner: Float, modifier: Modifier, onClick: () -> Unit) {
    val shape = cornerShape(corner.coerceAtMost(24f))
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (fill.luminance() > 0.5f) Color.Black else Color.White,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The shelf or series title, with a small arrow that opens its menu. The menu is drawn in the
 * header's style, so each shelf's menu matches its theme.
 */
@Composable
fun TitleWithMenu(title: String, theme: ShelfTheme, menu: TitleMenu, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var titleHeight by remember { mutableIntStateOf(0) }
    val hasMenu = menu.buttons.isNotEmpty() || menu.rowKey != null
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp

    Box(modifier) {
        Row(
            Modifier
                .onSizeChanged { titleHeight = it.height }
                .then(if (hasMenu) Modifier.clickable(onClickLabel = "$title options") { open = true } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemedText(title, theme.title, Modifier.weight(1f, fill = false))
            if (hasMenu) TitleArrow(Color(theme.title.color), theme.title.sizeSp)
        }
        if (open) {
            // Room around the panel so its shadow isn't cut off at the popup's edge.
            val room = theme.header.shadow?.let { (it.distanceDp * 2 + 4).dp } ?: 0.dp
            val shift = with(density) {
                IntOffset(-12.dp.roundToPx() - room.roundToPx(), titleHeight + 6.dp.roundToPx() - room.roundToPx())
            }
            Popup(
                alignment = Alignment.TopStart,
                offset = shift,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(Modifier.padding(room)) {
                    MenuPanel(theme, menu, minOf(screenWidth - 32, 560)) { open = false }
                }
            }
        }
    }
}

@Composable
private fun MenuPanel(theme: ShelfTheme, menu: TitleMenu, widthDp: Int, onClose: () -> Unit) {
    val look = theme.header
    val shape = cornerShape(look.cornerDp)
    val accent = Color(look.iconColor)
    val text = Color(look.textColor)
    Column(
        Modifier
            .width(widthDp.dp)
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (menu.buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                menu.buttons.forEach { (label, action) ->
                    MenuButton(label, accent, look.cornerDp, Modifier.weight(1f)) {
                        onClose()
                        action()
                    }
                }
            }
        }
        val key = menu.rowKey
        if (key != null) {
            val chosen = DeviceSettings.booksPerRow(key)
            val shown = chosen ?: menu.automaticRow
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Books Per Row",
                    color = text,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (chosen == null) "Auto ($shown)" else "$shown",
                    color = text,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(cornerShape(12f))
                        .clickable { DeviceSettings.updateBooksPerRow(key, null) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Slider(
                value = shown.toFloat(),
                onValueChange = { DeviceSettings.updateBooksPerRow(key, it.roundToInt()) },
                valueRange = FEWEST.toFloat()..MOST.toFloat(),
                steps = MOST - FEWEST - 1,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = accent.copy(alpha = 0.45f),
                    activeTickColor = accent.copy(alpha = 0.45f),
                    inactiveTickColor = accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            )
            Text(
                "Tap the number to go back to Auto. Saved on this device, separately for portrait and landscape.",
                color = text.copy(alpha = 0.8f),
                fontSize = 13.sp,
            )
        }
    }
}

/** The series Continue button, in the shelf's button style. */
@Composable
fun ContinuePill(look: ButtonLook, modifier: Modifier, onClick: () -> Unit) {
    val shape = cornerShape(look.cornerDp.coerceAtLeast(28f))
    Box(
        modifier
            .height(56.dp)
            .themedShadow(look.shadow, shape)
            .themedFill(look.fill, shape)
            .themedStroke(look.stroke, shape)
            .clip(shape)
            .clickable(onClickLabel = "Continue reading", onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Continue", color = Color(look.textColor), fontFamily = fontFamily(look.font), fontSize = 22.sp, maxLines = 1)
    }
}
