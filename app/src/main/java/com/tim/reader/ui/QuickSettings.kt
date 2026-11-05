package com.tim.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.data.ColorSet
import com.tim.reader.data.ReaderLook
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.data.USE_BOOK_VALUE
import com.tim.reader.reader.TEXT_FONTS
import kotlin.math.abs

val QUICK_BLUE = Color(0xFF4FC3F7)

private const val TIGHT = 1.2
private const val LOOSE = 1.8
private const val NARROW = 0.5
private const val NORMAL_MARGIN = 1.0
private const val WIDE = 2.0

private val MAIN_FONTS = listOf("Serif" to "serif", "Sans" to "sans-serif", "Mono" to "monospace")

private fun same(first: Double, second: Double) = abs(first - second) < 0.01

@Composable
private fun QuickRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun ThemeDot(set: ColorSet, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .background(Color(set.background), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) QUICK_BLUE else Color.White, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun RowScope.OptionBox(label: String, selected: Boolean, onClick: () -> Unit, icon: DrawScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .border(if (selected) 2.dp else 1.dp, if (selected) QUICK_BLUE else Color.White, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(30.dp, 22.dp), onDraw = icon)
        Text(label, fontSize = 12.sp)
    }
}

private fun DrawScope.spacingIcon(gap: Float) {
    val middle = size.height / 2
    listOf(middle - gap, middle, middle + gap).forEach { y ->
        drawLine(Color.White, Offset(2f, y), Offset(size.width - 2f, y), 3f)
    }
}

private fun DrawScope.marginIcon(reach: Float) {
    val y = size.height / 2
    val left = size.width * (0.5f - reach)
    val right = size.width * (0.5f + reach)
    drawLine(Color.White, Offset(left, y), Offset(right, y), 3f, StrokeCap.Round)
    listOf(left to 1f, right to -1f).forEach { (x, direction) ->
        val head = Path().apply {
            moveTo(x + 9f * direction, y - 7f)
            lineTo(x, y)
            lineTo(x + 9f * direction, y + 7f)
        }
        drawPath(head, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    }
}

/** The everyday settings, with everything else one tap away under Advanced. */
@Composable
fun ColumnScope.QuickSettings(
    look: ReaderLook,
    onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onColors: () -> Unit,
    onAdvanced: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = ColorSet(look.pageBackground, look.textColor)
    var moreFonts by remember { mutableStateOf(false) }
    val lineHeight = look.lineHeight
    val margins = look.pageMargins ?: NORMAL_MARGIN

    ReaderSheetBody {
        QuickRow("Theme") {
            listOf(DAY_COLORS, SEPIA_COLORS, NAVY_COLORS).forEach { set ->
                ThemeDot(set, set == colors) {
                    onChange { it.copy(pageBackground = set.background, textColor = set.text) }
                }
            }
            Box(
                Modifier
                    .size(46.dp)
                    .background(
                        Brush.sweepGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                        ),
                        CircleShape,
                    )
                    .clickable(onClick = onColors),
            )
        }

        QuickRow("Size") {
            Text("T", fontSize = 18.sp, fontFamily = FontFamily.Serif)
            PillSlider(
                value = look.fontSize.toFloat(),
                range = 0.5f..3f,
                thumb = QUICK_BLUE,
                onDone = { size -> onChange { it.copy(fontSize = size.toDouble()) } },
                modifier = Modifier.weight(1f),
            )
            Text("T", fontSize = 34.sp, fontFamily = FontFamily.Serif)
        }

        QuickRow("Font") {
            MAIN_FONTS.forEach { (label, family) ->
                val selected = look.fontFamily == family
                OutlinedButton(
                    onClick = { onChange { it.copy(fontFamily = family) } },
                    border = BorderStroke(1.dp, if (selected) QUICK_BLUE else Color.White),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) QUICK_BLUE else Color.Transparent,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(label, color = Color.White, maxLines = 1) }
            }
            Box {
                OutlinedButton(
                    onClick = { moreFonts = true },
                    border = BorderStroke(1.dp, Color.White),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) { Text("▼", color = Color.White) }
                DropdownMenu(expanded = moreFonts, onDismissRequest = { moreFonts = false }) {
                    TEXT_FONTS.filter { font -> MAIN_FONTS.none { it.second == font.second } }.forEach { (label, family) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                moreFonts = false
                                onChange { it.copy(fontFamily = family) }
                            },
                        )
                    }
                }
            }
        }

        QuickRow("Spacing") {
            OptionBox("Tight", lineHeight != null && same(lineHeight, TIGHT), {
                onChange { it.copy(lineHeight = TIGHT, bookLayout = false) }
            }) { spacingIcon(5f) }
            OptionBox("Normal", lineHeight == null, {
                onChange { it.copy(lineHeight = USE_BOOK_VALUE, bookLayout = false) }
            }) { spacingIcon(9f) }
            OptionBox("Loose", lineHeight != null && same(lineHeight, LOOSE), {
                onChange { it.copy(lineHeight = LOOSE, bookLayout = false) }
            }) { spacingIcon(14f) }
        }

        QuickRow("Margin") {
            OptionBox("Narrow", same(margins, NARROW), {
                onChange { it.copy(pageMargins = NARROW, bookLayout = false) }
            }) { marginIcon(0.22f) }
            OptionBox("Normal", same(margins, NORMAL_MARGIN), {
                onChange { it.copy(pageMargins = NORMAL_MARGIN, bookLayout = false) }
            }) { marginIcon(0.34f) }
            OptionBox("Wide", same(margins, WIDE), {
                onChange { it.copy(pageMargins = WIDE, bookLayout = false) }
            }) { marginIcon(0.46f) }
        }

        QuickRow("Custom") {
            OutlinedButton(onAdvanced, Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color.White)) {
                Text("Advanced", color = Color.White)
            }
        }

        OutlinedButton(
            onDone,
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            border = BorderStroke(1.dp, Color.White),
        ) { Text("Finished", color = Color.White) }
    }
}
