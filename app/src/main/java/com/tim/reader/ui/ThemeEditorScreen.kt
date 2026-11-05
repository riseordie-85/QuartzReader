package com.tim.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.LibraryState
import com.tim.reader.data.Shelf
import com.tim.reader.data.ShelfItem
import com.tim.reader.theme.Fill
import com.tim.reader.theme.GradientFill
import com.tim.reader.theme.LedgeAlign
import com.tim.reader.theme.Presets
import com.tim.reader.theme.ShelfTheme
import com.tim.reader.theme.SolidFill
import com.tim.reader.theme.TextLook
import com.tim.reader.theme.ThemeJson
import com.tim.reader.theme.ThemedBackground
import com.tim.reader.theme.ThemedText
import com.tim.reader.theme.cornerShape
import com.tim.reader.theme.themedFill
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ThemePart(val label: String) {
    Background("Background"),
    Header("Header"),
    Title("Shelf title"),
    Ledge("Shelf ledge"),
    Books("Books"),
    Controls("Sort and view icons"),
    Bottom("Bottom bar"),
    Button("Home button"),
}

private const val MIN_CONTRAST = 3.0

private fun colorBehind(fill: Fill): Long? = when (fill) {
    is SolidFill -> fill.color
    is GradientFill -> fill.colors.firstOrNull()
    else -> null
}

private fun lowContrast(text: Long, behind: Fill): Boolean {
    val background = colorBehind(behind) ?: return false
    val opaque = (background or 0xFF000000).toInt()
    return ColorUtils.calculateContrast(text.toInt() or 0xFF000000.toInt(), opaque) < MIN_CONTRAST
}

@Composable
private fun ContrastWarning(text: Long, behind: Fill) {
    if (lowContrast(text, behind)) ContrastNote()
}

/** A tiny shelf, so a style can be chosen by how it looks. */
@Composable
private fun StyleTile(name: String, style: ShelfTheme, modifier: Modifier, onPick: () -> Unit, onDelete: (() -> Unit)?) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemedBackground(
            style.background,
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, EditorColors.Border, RoundedCornerShape(14.dp))
                .clickable(onClick = onPick),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .fillMaxWidth()
                    .height(16.dp)
                    .themedFill(style.header.fill, cornerShape(style.header.cornerDp)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .height(8.dp)
                    .themedFill(style.ledge.fill, cornerShape(style.ledge.cornerDp)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onDelete != null) TextButton(onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun StylePicker(
    state: LibraryState,
    onPick: (ShelfTheme) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    val saved = state.userPresets.map { it.first }.toSet()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = EditorColors.Card,
        title = { Text("Preset Style") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (Presets.all + state.userPresets).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, style) ->
                            StyleTile(
                                label,
                                style,
                                Modifier.weight(1f),
                                onPick = { onPick(style) },
                                onDelete = if (label in saved) ({ onDelete(label) }) else null,
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } },
    )
}

/** A small working copy of a shelf, drawn with the real shelf parts. */
@Composable
private fun ThemePreview(theme: ShelfTheme, name: String, state: LibraryState, showButton: Boolean, modifier: Modifier) {
    val books = remember(state.books) { state.books.take(4).map { ShelfItem.Single(it) } }
    val noHeader = remember { HeaderActions({}, {}, {}, {}) }
    val noBooks = remember { BookActions({}, {}, {}, {}) }
    val neighbors = remember(name) { ShelfNeighbors("Previous", "Next", {}, {}) }
    ThemedBackground(theme.background, modifier) {
        Column(Modifier.fillMaxSize()) {
            LibraryHeader(theme.header, noHeader)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemedText(name, theme.title, Modifier.weight(1f))
                GlyphButton(Glyph.Grid, Color(theme.controls.color), "View") {}
                PillButton("Sort by", Color(theme.controls.color), theme.controls.stroke) {}
            }
            ShelfRow(books, 4, theme, noBooks)
            if (showButton) {
                ShelfButton(name, theme.button, Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()) {}
            }
        }
        BottomBar(neighbors, theme, Modifier.align(Alignment.BottomCenter))
    }
}

/** Styles a shelf or Home, with a live preview. Nothing is saved until Save. */
@Composable
fun ThemeEditorScreen(
    name: String,
    start: ShelfTheme,
    isHome: Boolean,
    state: LibraryState,
    onSave: (ShelfTheme) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(start) }
    var part by remember { mutableStateOf(ThemePart.Background) }
    var showParts by remember { mutableStateOf(false) }
    var showStyles by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    val parts = ThemePart.entries.filter { !isHome || (it != ThemePart.Button && it != ThemePart.Bottom) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(ThemeJson.write(theme).toByteArray()) }
                    }.isSuccess
                }
                Toast.makeText(context, if (saved) "Style exported" else "Couldn't export the style", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    }.getOrNull()
                }
                val parsed = text?.let { runCatching { ThemeJson.parse(it) }.getOrNull() }
                if (parsed == null) {
                    Toast.makeText(context, "That file isn't a Quartz Reader style", Toast.LENGTH_SHORT).show()
                } else {
                    theme = parsed
                }
            }
        }
    }

    EditorTheme {
    Box(
        Modifier
            .fillMaxSize()
            .background(EditorColors.Panel)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // The screen's size, not this box's, because the keyboard must not flip the layout.
        val screen = LocalConfiguration.current
        val sideBySide = screen.screenWidthDp > screen.screenHeightDp
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                Text("Select item to edit:", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.padding(top = 6.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1.25f)) {
                        OutlinedButton(
                            onClick = { showParts = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.5.dp, EditorColors.Border),
                        ) {
                            Text(part.label, color = EditorColors.Ink, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                            ListArrow()
                        }
                        DropdownMenu(expanded = showParts, onDismissRequest = { showParts = false }) {
                            parts.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        showParts = false
                                        part = option
                                    },
                                )
                            }
                        }
                    }
                    EditorOutlineButton("Preset Style", Modifier.weight(1f)) { showStyles = true }
                    Box {
                        EditorOutlineButton("•••") { showMore = true }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(text = { Text("Save as a style") }, onClick = {
                                showMore = false
                                naming = true
                            })
                            DropdownMenuItem(text = { Text("Use on other shelves") }, onClick = {
                                showMore = false
                                copying = true
                            })
                            DropdownMenuItem(text = { Text("Export this style") }, onClick = {
                                showMore = false
                                export.launch("$name style.json")
                            })
                            DropdownMenuItem(text = { Text("Import a style") }, onClick = {
                                showMore = false
                                import.launch(arrayOf("application/json", "application/octet-stream"))
                            })
                        }
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PartEditor(part, theme) { theme = it }
                }

                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EditorOutlineButton("Cancel", Modifier.weight(1f), onClose)
                    EditorMainButton("Save", Modifier.weight(1f)) { onSave(theme) }
                }
            }
        }

        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                ThemePreview(theme, name, state, part == ThemePart.Button, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp)))
                controls(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ThemePreview(theme, name, state, part == ThemePart.Button, Modifier.fillMaxWidth().weight(0.72f).clip(RoundedCornerShape(0.dp)))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }

    if (showStyles) {
        StylePicker(
            state = state,
            onPick = { style ->
                showStyles = false
                theme = style
            },
            onDelete = { label -> scope.launch { repository.removeUserPreset(label) } },
            onClose = { showStyles = false },
        )
    }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as a style") },
            text = { OutlinedTextField(label, { label = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        val saved = theme
                        scope.launch { repository.saveUserPreset(label.trim(), saved) }
                    },
                    enabled = label.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton({ naming = false }) { Text("Cancel") } },
        )
    }

    if (copying) {
        val chosen = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { copying = false },
            title = { Text("Use on other shelves") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.shelves.forEach { shelf: Shelf ->
                        ToggleRow(shelf.name, shelf.id in chosen) { on ->
                            if (on) chosen.add(shelf.id) else chosen.remove(shelf.id)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    copying = false
                    val saved = theme
                    val shelves = chosen.toList()
                    scope.launch { shelves.forEach { repository.setShelfTheme(it, saved) } }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton({ copying = false }) { Text("Cancel") } },
        )
    }
    }
}

@Composable
private fun TextLookEditor(look: TextLook, behind: Fill, onChange: (TextLook) -> Unit) {
    FontEditor(look.font) { onChange(look.copy(font = it)) }
    EditorCard("Text") {
        EditorSlider("Size", look.sizeSp, 12f..60f, { "${it.roundToInt()} sp" }) { onChange(look.copy(sizeSp = it)) }
        EditorDivider()
        EditorSwitchRow("Bold", look.bold) { onChange(look.copy(bold = it)) }
        EditorDivider()
        EditorSwitchRow("Italic", look.italic) { onChange(look.copy(italic = it)) }
        EditorDivider()
        ColorRow("Color", look.color) { onChange(look.copy(color = it)) }
        ContrastWarning(look.color, behind)
    }
    StrokeEditor(look.stroke) { onChange(look.copy(stroke = it)) }
    ShadowEditor(look.shadow) { onChange(look.copy(shadow = it)) }
}

private fun dpLabel(value: Float) = "${value.roundToInt()} dp"

@Composable
private fun PartEditor(part: ThemePart, theme: ShelfTheme, onChange: (ShelfTheme) -> Unit) {
    when (part) {
        ThemePart.Background -> FillEditor("Fill Style", theme.background) { onChange(theme.copy(background = it)) }
        ThemePart.Header -> {
            val look = theme.header
            FillEditor("Fill Style", look.fill) { onChange(theme.copy(header = look.copy(fill = it))) }
            EditorCard("Text and Icons") {
                ColorRow("Text", look.textColor) { onChange(theme.copy(header = look.copy(textColor = it))) }
                ContrastWarning(look.textColor, look.fill)
                EditorDivider()
                ColorRow("Icons", look.iconColor) { onChange(theme.copy(header = look.copy(iconColor = it))) }
            }
            EditorCard("Shape") {
                EditorSlider("Corners", look.cornerDp, 0f..40f, ::dpLabel) { onChange(theme.copy(header = look.copy(cornerDp = it))) }
            }
            StrokeEditor(look.stroke) { onChange(theme.copy(header = look.copy(stroke = it))) }
            ShadowEditor(look.shadow, showDirection = false) { onChange(theme.copy(header = look.copy(shadow = it))) }
        }
        ThemePart.Title -> TextLookEditor(theme.title, theme.background) { onChange(theme.copy(title = it)) }
        ThemePart.Ledge -> {
            val look = theme.ledge
            FillEditor("Fill Style", look.fill) { onChange(theme.copy(ledge = look.copy(fill = it))) }
            EditorCard("Size and Shape") {
                EditorSlider("Height", look.heightDp, 0f..60f, ::dpLabel) { onChange(theme.copy(ledge = look.copy(heightDp = it))) }
                EditorSlider("Width", look.widthFraction, 0.1f..1f, { "${(it * 100).roundToInt()}%" }) {
                    onChange(theme.copy(ledge = look.copy(widthFraction = it)))
                }
                if (look.widthFraction < 1f) {
                    EditorSegments(LedgeAlign.entries.map { it.name }, look.align.ordinal) {
                        onChange(theme.copy(ledge = look.copy(align = LedgeAlign.entries[it])))
                    }
                }
                EditorSlider("Corners", look.cornerDp, 0f..30f, ::dpLabel) { onChange(theme.copy(ledge = look.copy(cornerDp = it))) }
            }
            StrokeEditor(look.stroke) { onChange(theme.copy(ledge = look.copy(stroke = it))) }
            ShadowEditor(look.shadow, showDirection = false) { onChange(theme.copy(ledge = look.copy(shadow = it))) }
        }
        ThemePart.Books -> {
            val look = theme.book
            EditorCard("Layout") {
                EditorSlider("Space between books", look.spacingDp, 0f..40f, ::dpLabel) { onChange(theme.copy(book = look.copy(spacingDp = it))) }
                EditorDivider()
                EditorSwitchRow("Shine on covers", look.highlight) { onChange(theme.copy(book = look.copy(highlight = it))) }
            }
            ShadowEditor(look.shadow) { onChange(theme.copy(book = look.copy(shadow = it))) }
        }
        ThemePart.Controls -> {
            val look = theme.controls
            EditorCard("Color") {
                ColorRow("Icons and Sort By", look.color) { onChange(theme.copy(controls = look.copy(color = it))) }
                ContrastWarning(look.color, theme.background)
            }
            StrokeEditor(look.stroke) { onChange(theme.copy(controls = look.copy(stroke = it))) }
        }
        ThemePart.Bottom -> {
            val look = theme.bottom
            EditorCard("Fade") {
                ColorRow("Color", look.fadeColor) { onChange(theme.copy(bottom = look.copy(fadeColor = it))) }
                EditorDivider()
                EditorSlider("Height", look.heightDp, 40f..240f, ::dpLabel) { onChange(theme.copy(bottom = look.copy(heightDp = it))) }
            }
            EditorCard("Labels") {
                ColorRow("Text", look.labelColor) { onChange(theme.copy(bottom = look.copy(labelColor = it))) }
                EditorDivider()
                EditorSwitchRow("Shape behind labels", look.labelFill != null) { on ->
                    onChange(theme.copy(bottom = look.copy(labelFill = if (on) SolidFill(0x80000000) else null)))
                }
            }
            look.labelFill?.let { fill ->
                FillEditor("Label Fill", fill) { onChange(theme.copy(bottom = look.copy(labelFill = it))) }
            }
        }
        ThemePart.Button -> {
            val look = theme.button
            EditorOutlineButton("Match this shelf's header", Modifier.fillMaxWidth()) {
                onChange(
                    theme.copy(
                        button = look.copy(
                            fill = theme.header.fill,
                            cornerDp = theme.header.cornerDp,
                            textColor = theme.header.textColor,
                            stroke = theme.header.stroke,
                            shadow = theme.header.shadow,
                        ),
                    ),
                )
            }
            FillEditor("Fill Style", look.fill) { onChange(theme.copy(button = look.copy(fill = it))) }
            FontEditor(look.font) { onChange(theme.copy(button = look.copy(font = it))) }
            EditorCard("Text and Shape") {
                EditorSwitchRow("Bold", look.bold) { onChange(theme.copy(button = look.copy(bold = it))) }
                EditorDivider()
                EditorSwitchRow("Italic", look.italic) { onChange(theme.copy(button = look.copy(italic = it))) }
                EditorDivider()
                ColorRow("Text", look.textColor) { onChange(theme.copy(button = look.copy(textColor = it))) }
                ContrastWarning(look.textColor, look.fill)
                EditorDivider()
                EditorSlider("Corners", look.cornerDp, 0f..40f, ::dpLabel) { onChange(theme.copy(button = look.copy(cornerDp = it))) }
            }
            StrokeEditor(look.stroke) { onChange(theme.copy(button = look.copy(stroke = it))) }
            ShadowEditor(look.shadow, showDirection = false) { onChange(theme.copy(button = look.copy(shadow = it))) }
        }
    }
}
