package com.tim.reader.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tim.reader.data.ColorSet

private val BUILT_IN_SETS = listOf(
    ColorSet(0xFFFFFFFF, 0xFF1A1A1A),
    ColorSet(0xFFF4ECD8, 0xFF3B2F21),
    ColorSet(0xFFD9D9D9, 0xFF202020),
    ColorSet(0xFF2B2B2B, 0xFFE4E4E4),
    ColorSet(0xFF000000, 0xFFD8D8D8),
)

private enum class ColorTarget(val label: String) {
    Page("Page"),
    Text("Text"),
}

/** Ready-made and saved color sets, with a full picker for the page and text colors. */
@Composable
fun ColorSection(
    current: ColorSet,
    saved: List<ColorSet>,
    onChange: (ColorSet) -> Unit,
    onSave: () -> Unit,
    onRemove: (ColorSet) -> Unit,
) {
    var target by remember { mutableStateOf(ColorTarget.Page) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BUILT_IN_SETS.forEach { set ->
            ColorSetChip(set, set == current, onClick = { onChange(set) })
        }
        saved.forEach { set ->
            ColorSetChip(set, set == current, onClick = { onChange(set) }, onLongClick = { onRemove(set) })
        }
    }
    if (saved.isNotEmpty()) {
        Text("Long press a saved set to remove it", color = Color.LightGray)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorTarget.entries.forEach { option ->
            TextButton({ target = option }) {
                Text(option.label, color = if (target == option) Color.White else Color.Gray)
            }
        }
        Box(Modifier.weight(1f))
        TextButton(onSave, enabled = current !in BUILT_IN_SETS && current !in saved) { Text("Save this set") }
    }

    val color = if (target == ColorTarget.Page) current.background else current.text
    ColorPicker(color) { picked ->
        onChange(if (target == ColorTarget.Page) current.copy(background = picked) else current.copy(text = picked))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorSetChip(set: ColorSet, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(48.dp)
            .background(Color(set.background), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF4C8DFF) else Color.Gray, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Aa", color = Color(set.text))
    }
}

private fun toHex(color: Long) = "%06X".format(color and 0xFFFFFF)

private fun colorOf(hue: Float, saturation: Float, brightness: Float) =
    AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFFF

private fun fromHex(text: String): Long? =
    text.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000 }

/** Hue, saturation, and brightness sliders, or a typed hex value. */
@Composable
fun ColorPicker(color: Long, onColor: (Long) -> Unit) {
    val start = remember(color) { FloatArray(3).also { AndroidColor.colorToHSV(color.toInt(), it) } }
    var hue by remember(color) { mutableFloatStateOf(start[0]) }
    var saturation by remember(color) { mutableFloatStateOf(start[1]) }
    var brightness by remember(color) { mutableFloatStateOf(start[2]) }
    var hex by remember(color) { mutableStateOf(toHex(color)) }
    val shown = colorOf(hue, saturation, brightness)
    val finish = { onColor(colorOf(hue, saturation, brightness)) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(56.dp, 40.dp)
                    .background(Color(shown), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { typed ->
                    hex = typed
                    fromHex(typed)?.let(onColor)
                },
                label = { Text("Hex") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text("Hue", modifier = Modifier.padding(top = 8.dp))
        HueBar()
        Slider(hue, { hue = it }, valueRange = 0f..360f, onValueChangeFinished = finish)
        Text("Saturation")
        Slider(saturation, { saturation = it }, onValueChangeFinished = finish)
        Text("Brightness")
        Slider(brightness, { brightness = it }, onValueChangeFinished = finish)
    }
}

@Composable
private fun HueBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        repeat(36) { step ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(Color(AndroidColor.HSVToColor(floatArrayOf(step * 10f, 1f, 1f)))),
            )
        }
    }
}
