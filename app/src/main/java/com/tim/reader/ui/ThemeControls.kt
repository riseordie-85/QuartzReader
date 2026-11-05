package com.tim.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.theme.Fill
import com.tim.reader.theme.GradientDirection
import com.tim.reader.theme.GradientFill
import com.tim.reader.theme.ImageFill
import com.tim.reader.theme.ImageMode
import com.tim.reader.theme.ShadowLook
import com.tim.reader.theme.SolidFill
import com.tim.reader.theme.StrokeLook
import com.tim.reader.theme.ThemeFiles
import com.tim.reader.theme.ThemeImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val ALPHA_MASK = 0xFF000000
private const val RGB_MASK = 0x00FFFFFFL

@Composable
fun EditorHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
}

/** A row of choices that scrolls sideways on narrow screens. */
@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            OutlinedButton(
                onClick = { onSelect(index) },
                border = BorderStroke(if (index == selected) 2.dp else 1.dp, if (index == selected) QUICK_BLUE else Color.Gray),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** A slider that changes the preview as it moves. */
@Composable
fun ThemeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    onChange: (Float) -> Unit,
) {
    val shown = if (range.endInclusive <= 4f) "%.2f".format(value) else value.roundToInt().toString()
    Text("$label: $shown$unit")
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

/** A color swatch that opens the picker, with opacity kept separately because the picker has none. */
@Composable
fun ColorRow(label: String, color: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp, 28.dp)
                .background(Color(color), RoundedCornerShape(6.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(6.dp)),
        )
        Text(label)
    }
    if (open) {
        ColorPicker(color or ALPHA_MASK) { picked -> onChange((picked and RGB_MASK) or (color and ALPHA_MASK)) }
        val opacity = ((color ushr 24) and 0xFF) / 255f
        ThemeSlider("Opacity", opacity, 0f..1f) { value ->
            onChange((color and RGB_MASK) or ((value * 255).roundToInt().toLong() shl 24))
        }
    }
}

@Composable
fun StrokeEditor(stroke: StrokeLook?, onChange: (StrokeLook?) -> Unit) {
    ToggleRow("Outline", stroke != null) { on -> onChange(if (on) StrokeLook(0xFF000000, 2f) else null) }
    if (stroke != null) {
        ColorRow("Outline color", stroke.color) { onChange(stroke.copy(color = it)) }
        ThemeSlider("Outline width", stroke.widthDp, 0.5f..12f, " dp") { onChange(stroke.copy(widthDp = it)) }
    }
}

@Composable
fun ShadowEditor(shadow: ShadowLook?, showDirection: Boolean = true, onChange: (ShadowLook?) -> Unit) {
    ToggleRow("Shadow", shadow != null) { on -> onChange(if (on) ShadowLook() else null) }
    if (shadow != null) {
        ColorRow("Shadow color", shadow.color) { onChange(shadow.copy(color = it)) }
        if (showDirection) {
            ThemeSlider("Angle", shadow.angle, 0f..360f, "°") { onChange(shadow.copy(angle = it)) }
        }
        ThemeSlider("Distance", shadow.distanceDp, 0f..32f, " dp") { onChange(shadow.copy(distanceDp = it)) }
        if (showDirection) {
            ThemeSlider("Blur", shadow.blurDp, 0f..32f, " dp") { onChange(shadow.copy(blurDp = it)) }
        }
        ThemeSlider("Opacity", shadow.opacity, 0f..1f) { onChange(shadow.copy(opacity = it)) }
    }
}

/** Chooses a color, a gradient, or a wallpaper for one part of the theme. */
@Composable
fun FillEditor(fill: Fill, onChange: (Fill) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableIntStateOf(0) }
    val firstColor = when (fill) {
        is SolidFill -> fill.color
        is GradientFill -> fill.colors.firstOrNull() ?: 0xFFFFFFFF
        is ImageFill -> fill.tint?.or(ALPHA_MASK) ?: 0xFFFFFFFF
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { runCatching { ThemeFiles.importImage(context, uri) }.getOrNull() }
                if (name == null) {
                    Toast.makeText(context, "Couldn't use that image", Toast.LENGTH_SHORT).show()
                } else {
                    imported++
                    onChange(ImageFill(name))
                }
            }
        }
    }

    val kind = when (fill) {
        is SolidFill -> 0
        is GradientFill -> 1
        is ImageFill -> 2
    }
    ChipRow(listOf("Color", "Gradient", "Image"), kind) { index ->
        when (index) {
            0 -> onChange(SolidFill(firstColor))
            1 -> if (fill !is GradientFill) onChange(GradientFill(listOf(firstColor, 0xFF000000)))
            else -> if (fill !is ImageFill) onChange(ImageFill(ThemeImages.builtIn.first().first))
        }
    }

    when (fill) {
        is SolidFill -> ColorRow("Color", fill.color) { onChange(SolidFill(it)) }
        is GradientFill -> {
            val start = fill.colors.firstOrNull() ?: 0xFFFFFFFF
            val end = fill.colors.lastOrNull() ?: 0xFF000000
            ColorRow("First color", start) { picked ->
                onChange(fill.copy(colors = if (fill.colors.size < 2) listOf(picked, end) else listOf(picked) + fill.colors.drop(1)))
            }
            ColorRow("Last color", end) { picked ->
                onChange(fill.copy(colors = if (fill.colors.size < 2) listOf(start, picked) else fill.colors.dropLast(1) + picked))
            }
            ChipRow(GradientDirection.entries.map { it.name }, fill.direction.ordinal) {
                onChange(fill.copy(direction = GradientDirection.entries[it]))
            }
        }
        is ImageFill -> {
            val names = remember(imported) { ThemeImages.builtIn + ThemeFiles.images().mapIndexed { index, name -> name to "Mine ${index + 1}" } }
            ChipRow(names.map { it.second }, names.indexOfFirst { it.first == fill.name }) { index ->
                onChange(fill.copy(name = names[index].first))
            }
            OutlinedButton({ pick.launch(arrayOf("image/*")) }) { Text("Add an image") }
            ChipRow(listOf("Fill the space", "Tile"), fill.mode.ordinal) { onChange(fill.copy(mode = ImageMode.entries[it])) }
            if (fill.mode == ImageMode.Tile) {
                ThemeSlider("Tile size", fill.tileScale, 0.25f..3f) { onChange(fill.copy(tileScale = it)) }
            }
            ToggleRow("Tint over the image", fill.tint != null) { on -> onChange(fill.copy(tint = if (on) 0x66000000 else null)) }
            fill.tint?.let { tint -> ColorRow("Tint", tint) { onChange(fill.copy(tint = it)) } }
        }
    }
}

/** Built-in fonts and any brought in, with a button to bring in another. */
@Composable
fun FontEditor(font: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { runCatching { ThemeFiles.importFont(context, uri) }.getOrNull() }
                if (name == null) {
                    Toast.makeText(context, "That file isn't a font Android can use", Toast.LENGTH_SHORT).show()
                } else {
                    imported++
                    onChange(name)
                }
            }
        }
    }
    val fonts = remember(imported) {
        listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono", "cursive" to "Cursive") +
            ThemeFiles.fonts().map { it to ThemeFiles.fontLabel(it) }
    }
    ChipRow(fonts.map { it.second }, fonts.indexOfFirst { it.first == font }) { onChange(fonts[it].first) }
    OutlinedButton({ pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) {
        Text("Add a font")
    }
}

