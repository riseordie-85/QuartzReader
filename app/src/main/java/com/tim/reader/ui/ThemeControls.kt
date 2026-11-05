package com.tim.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tim.reader.theme.fontFamily
import com.tim.reader.theme.rememberThemeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val ALPHA_MASK = 0xFF000000
private const val RGB_MASK = 0x00FFFFFFL
private const val THUMBS_PER_ROW = 4

private val QUICK_COLORS = listOf(
    0xFFFFFFFF, 0xFFD9D9D9, 0xFF8C8C8C, 0xFF333333, 0xFF000000, 0xFFC62828,
    0xFFEF6C00, 0xFFF9A825, 0xFF2E7D32, 0xFF00897B, 0xFF1565C0, 0xFF6A1B9A, 0xFFAD1457,
)

private fun dpText(value: Float) = "${value.roundToInt()} dp"

private fun percentText(value: Float) = "${(value * 100).roundToInt()}%"

/** A color you can see, which opens swatches, and the full picker if those aren't enough. */
@Composable
fun ColorRow(label: String, color: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(32.dp)
                .background(Color(color), CircleShape)
                .border(1.dp, EditorColors.Border, CircleShape),
        )
        ListArrow(EditorColors.Muted)
    }
    if (open) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(EditorColors.Well, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_COLORS.forEach { option ->
                    val isOn = (option and RGB_MASK) == (color and RGB_MASK)
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(Color(option), CircleShape)
                            .border(if (isOn) 3.dp else 1.dp, if (isOn) EditorColors.Accent else EditorColors.Border, CircleShape)
                            .clickable { onChange((option and RGB_MASK) or (color and ALPHA_MASK)) },
                    )
                }
            }
            EditorSlider("Opacity", ((color ushr 24) and 0xFF) / 255f, 0f..1f, ::percentText) { value ->
                onChange((color and RGB_MASK) or ((value * 255).roundToInt().toLong() shl 24))
            }
            TextButton({ custom = !custom }) { Text(if (custom) "Hide custom color" else "Custom color") }
            if (custom) {
                ColorPicker(color or ALPHA_MASK) { picked -> onChange((picked and RGB_MASK) or (color and ALPHA_MASK)) }
            }
        }
    }
}

@Composable
fun StrokeEditor(stroke: StrokeLook?, onChange: (StrokeLook?) -> Unit) {
    EditorSwitchCard("Outline", stroke != null, { on -> onChange(if (on) StrokeLook(0xFF000000, 2f) else null) }) {
        if (stroke != null) {
            ColorRow("Color", stroke.color) { onChange(stroke.copy(color = it)) }
            EditorDivider()
            EditorSlider("Thickness", stroke.widthDp, 0.5f..12f, ::dpText) { onChange(stroke.copy(widthDp = it)) }
        }
    }
}

@Composable
fun ShadowEditor(shadow: ShadowLook?, showDirection: Boolean = true, onChange: (ShadowLook?) -> Unit) {
    EditorSwitchCard("Shadow", shadow != null, { on -> onChange(if (on) ShadowLook() else null) }) {
        if (shadow != null) {
            ColorRow("Color", shadow.color) { onChange(shadow.copy(color = it)) }
            EditorDivider()
            EditorSlider("Distance", shadow.distanceDp, 0f..32f, ::dpText) { onChange(shadow.copy(distanceDp = it)) }
            if (showDirection) {
                EditorSlider("Softness", shadow.blurDp, 0f..32f, ::dpText) { onChange(shadow.copy(blurDp = it)) }
                EditorSlider("Angle", shadow.angle, 0f..360f, { "${it.roundToInt()}°" }) { onChange(shadow.copy(angle = it)) }
            }
            EditorSlider("Strength", shadow.opacity, 0f..1f, ::percentText) { onChange(shadow.copy(opacity = it)) }
        }
    }
}

/** One of the round fill choices, labeled so it's clear what each one is. */
@Composable
private fun FillDot(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, dot: @Composable () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .border(if (selected) 3.dp else 1.dp, if (selected) EditorColors.Accent else EditorColors.Border, CircleShape)
                .padding(4.dp)
                .clip(CircleShape),
        ) { dot() }
        Text(label, fontSize = 13.sp, color = if (selected) EditorColors.Ink else EditorColors.Muted)
    }
}

@Composable
private fun PictureIcon() {
    Canvas(
        Modifier
            .fillMaxSize()
            .background(EditorColors.Ink),
    ) {
        val unit = size.minDimension
        val line = unit * 0.05f
        drawRoundRect(
            Color.White,
            Offset(unit * 0.24f, unit * 0.28f),
            Size(unit * 0.52f, unit * 0.44f),
            CornerRadius(unit * 0.06f),
            style = Stroke(line),
        )
        drawCircle(Color.White, unit * 0.05f, Offset(unit * 0.38f, unit * 0.41f))
        val hill = Path().apply {
            moveTo(unit * 0.3f, unit * 0.67f)
            lineTo(unit * 0.46f, unit * 0.48f)
            lineTo(unit * 0.58f, unit * 0.6f)
            lineTo(unit * 0.62f, unit * 0.55f)
            lineTo(unit * 0.7f, unit * 0.67f)
            close()
        }
        drawPath(hill, Color.White)
    }
}

@Composable
private fun ImageThumb(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val image by rememberThemeImage(name, thumbnail = true)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(0.85f)
            .clip(shape)
            .background(EditorColors.Well)
            .border(if (selected) 3.dp else 1.dp, if (selected) EditorColors.Accent else EditorColors.Border, shape)
            .clickable(onClick = onClick),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Chooses a color, a gradient, or a wallpaper for one part of the theme. */
@Composable
fun FillEditor(title: String, fill: Fill, onChange: (Fill) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableIntStateOf(0) }
    val firstColor = when (fill) {
        is SolidFill -> fill.color
        is GradientFill -> fill.colors.firstOrNull() ?: 0xFFFFFFFF
        is ImageFill -> fill.tint?.or(ALPHA_MASK) ?: 0xFF3A3A3A
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

    EditorCard(title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FillDot("Color", fill is SolidFill, { onChange(SolidFill(firstColor)) }, Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(firstColor or ALPHA_MASK)),
                )
            }
            FillDot(
                "Gradient",
                fill is GradientFill,
                { if (fill !is GradientFill) onChange(GradientFill(listOf(firstColor, 0xFF000000))) },
                Modifier.weight(1f),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(firstColor or ALPHA_MASK), Color.Black))),
                )
            }
            FillDot(
                "Image",
                fill is ImageFill,
                { if (fill !is ImageFill) onChange(ImageFill(ThemeImages.builtIn.first().first)) },
                Modifier.weight(1f),
            ) { PictureIcon() }
        }

        when (fill) {
            is SolidFill -> {
                EditorDivider()
                ColorRow("Color", fill.color) { onChange(SolidFill(it)) }
            }
            is GradientFill -> {
                val start = fill.colors.firstOrNull() ?: 0xFFFFFFFF
                val end = fill.colors.lastOrNull() ?: 0xFF000000
                EditorDivider()
                ColorRow("First color", start) { picked ->
                    onChange(fill.copy(colors = if (fill.colors.size < 2) listOf(picked, end) else listOf(picked) + fill.colors.drop(1)))
                }
                EditorDivider()
                ColorRow("Last color", end) { picked ->
                    onChange(fill.copy(colors = if (fill.colors.size < 2) listOf(start, picked) else fill.colors.dropLast(1) + picked))
                }
                EditorDivider()
                EditorSegments(GradientDirection.entries.map { it.name }, fill.direction.ordinal) {
                    onChange(fill.copy(direction = GradientDirection.entries[it]))
                }
            }
            is ImageFill -> {
                val names = remember(imported) { ThemeImages.builtIn.map { it.first } + ThemeFiles.images() }
                EditorDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Image", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    EditorOutlineButton("Choose File") { pick.launch(arrayOf("image/*")) }
                }
                names.chunked(THUMBS_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { name ->
                            ImageThumb(name, name == fill.name, Modifier.weight(1f)) { onChange(fill.copy(name = name)) }
                        }
                        repeat(THUMBS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                EditorDivider()
                EditorSegments(listOf("Fill the Space", "Tile"), fill.mode.ordinal) {
                    onChange(fill.copy(mode = ImageMode.entries[it]))
                }
                if (fill.mode == ImageMode.Tile) {
                    EditorSlider("Tile size", fill.tileScale, 0.25f..3f, ::percentText) { onChange(fill.copy(tileScale = it)) }
                }
                EditorDivider()
                EditorSwitchRow("Tint over the image", fill.tint != null) { on ->
                    onChange(fill.copy(tint = if (on) 0x66000000 else null))
                }
                fill.tint?.let { tint -> ColorRow("Tint", tint) { onChange(fill.copy(tint = it)) } }
            }
        }
    }
}

@Composable
private fun FontTile(label: String, font: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .border(if (selected) 3.dp else 1.dp, if (selected) EditorColors.Accent else EditorColors.Border, shape),
            contentAlignment = Alignment.Center,
        ) { Text("Aa", fontFamily = fontFamily(font), fontSize = 26.sp) }
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) EditorColors.Ink else EditorColors.Muted,
        )
    }
}

/** Each font shows itself, so choosing one doesn't take reading names. */
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
    EditorCard(
        "Font",
        trailing = {
            EditorOutlineButton("Choose File") {
                pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
            }
        },
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fonts.forEach { (name, label) ->
                FontTile(label, name, name == font) { onChange(name) }
            }
        }
    }
}

/** A short note under a color that's hard to read against what's behind it. */
@Composable
fun ContrastNote() {
    Text("This may be hard to read.", color = EditorColors.Warning, modifier = Modifier.padding(top = 2.dp))
}
