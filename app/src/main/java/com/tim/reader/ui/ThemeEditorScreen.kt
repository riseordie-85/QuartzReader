package com.tim.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    if (lowContrast(text, behind)) Text("This may be hard to read.", color = Color(0xFFFFB74D))
}

/** A small working copy of a shelf, drawn with the real shelf parts. */
@Composable
private fun ThemePreview(theme: ShelfTheme, name: String, state: LibraryState, modifier: Modifier) {
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
                PillButton("Sort by", Color(theme.controls.color)) {}
            }
            ShelfRow(books, 4, theme, noBooks)
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
                if (text == null) {
                    Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                } else {
                    theme = ThemeJson.read(text)
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val sideBySide = maxWidth > maxHeight
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton({ showParts = true }, Modifier.fillMaxWidth()) { Text(part.label, maxLines = 1) }
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
                    Box {
                        OutlinedButton({ showStyles = true }) { Text("Styles") }
                        DropdownMenu(expanded = showStyles, onDismissRequest = { showStyles = false }) {
                            (Presets.all + state.userPresets).forEach { (label, preset) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showStyles = false
                                        theme = preset
                                    },
                                    trailingIcon = if (state.userPresets.any { it.first == label }) {
                                        {
                                            TextButton({
                                                showStyles = false
                                                scope.launch { repository.removeUserPreset(label) }
                                            }) { Text("Delete") }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        OutlinedButton({ showMore = true }) { Text("More") }
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
                        .padding(vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PartEditor(part, theme) { theme = it }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClose, Modifier.weight(1f)) { Text("Cancel") }
                    Button({ onSave(theme) }, Modifier.weight(1f)) { Text("Save") }
                }
            }
        }

        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                ThemePreview(theme, name, state, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(0.dp)))
                controls(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ThemePreview(theme, name, state, Modifier.fillMaxWidth().weight(0.8f).clip(RoundedCornerShape(0.dp)))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
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

@Composable
private fun TextLookEditor(look: TextLook, onChange: (TextLook) -> Unit) {
    EditorHeading("Font")
    FontEditor(look.font) { onChange(look.copy(font = it)) }
    ThemeSlider("Size", look.sizeSp, 12f..60f, " sp") { onChange(look.copy(sizeSp = it)) }
    ToggleRow("Bold", look.bold) { onChange(look.copy(bold = it)) }
    ToggleRow("Italic", look.italic) { onChange(look.copy(italic = it)) }
    ColorRow("Color", look.color) { onChange(look.copy(color = it)) }
    StrokeEditor(look.stroke) { onChange(look.copy(stroke = it)) }
    ShadowEditor(look.shadow) { onChange(look.copy(shadow = it)) }
}

@Composable
private fun ColumnScope.PartEditor(part: ThemePart, theme: ShelfTheme, onChange: (ShelfTheme) -> Unit) {
    when (part) {
        ThemePart.Background -> FillEditor(theme.background) { onChange(theme.copy(background = it)) }
        ThemePart.Header -> {
            val look = theme.header
            EditorHeading("Fill")
            FillEditor(look.fill) { onChange(theme.copy(header = look.copy(fill = it))) }
            ThemeSlider("Corner", look.cornerDp, 0f..40f, " dp") { onChange(theme.copy(header = look.copy(cornerDp = it))) }
            ColorRow("Text", look.textColor) { onChange(theme.copy(header = look.copy(textColor = it))) }
            ContrastWarning(look.textColor, look.fill)
            ColorRow("Icons", look.iconColor) { onChange(theme.copy(header = look.copy(iconColor = it))) }
            StrokeEditor(look.stroke) { onChange(theme.copy(header = look.copy(stroke = it))) }
            ShadowEditor(look.shadow) { onChange(theme.copy(header = look.copy(shadow = it))) }
        }
        ThemePart.Title -> {
            TextLookEditor(theme.title) { onChange(theme.copy(title = it)) }
            ContrastWarning(theme.title.color, theme.background)
        }
        ThemePart.Ledge -> {
            val look = theme.ledge
            EditorHeading("Fill")
            FillEditor(look.fill) { onChange(theme.copy(ledge = look.copy(fill = it))) }
            ThemeSlider("Height", look.heightDp, 0f..60f, " dp") { onChange(theme.copy(ledge = look.copy(heightDp = it))) }
            ThemeSlider("Width", look.widthFraction, 0.1f..1f) { onChange(theme.copy(ledge = look.copy(widthFraction = it))) }
            if (look.widthFraction < 1f) {
                ChipRow(LedgeAlign.entries.map { it.name }, look.align.ordinal) {
                    onChange(theme.copy(ledge = look.copy(align = LedgeAlign.entries[it])))
                }
            }
            ThemeSlider("Corner", look.cornerDp, 0f..30f, " dp") { onChange(theme.copy(ledge = look.copy(cornerDp = it))) }
            StrokeEditor(look.stroke) { onChange(theme.copy(ledge = look.copy(stroke = it))) }
            ShadowEditor(look.shadow) { onChange(theme.copy(ledge = look.copy(shadow = it))) }
        }
        ThemePart.Books -> {
            val look = theme.book
            ThemeSlider("Spacing", look.spacingDp, 0f..40f, " dp") { onChange(theme.copy(book = look.copy(spacingDp = it))) }
            ToggleRow("Highlight on covers", look.highlight) { onChange(theme.copy(book = look.copy(highlight = it))) }
            ShadowEditor(look.shadow) { onChange(theme.copy(book = look.copy(shadow = it))) }
        }
        ThemePart.Controls -> {
            val look = theme.controls
            ColorRow("Color", look.color) { onChange(theme.copy(controls = look.copy(color = it))) }
            ContrastWarning(look.color, theme.background)
            StrokeEditor(look.stroke) { onChange(theme.copy(controls = look.copy(stroke = it))) }
        }
        ThemePart.Bottom -> {
            val look = theme.bottom
            ColorRow("Fade color", look.fadeColor) { onChange(theme.copy(bottom = look.copy(fadeColor = it))) }
            ThemeSlider("Height", look.heightDp, 40f..240f, " dp") { onChange(theme.copy(bottom = look.copy(heightDp = it))) }
            ColorRow("Label color", look.labelColor) { onChange(theme.copy(bottom = look.copy(labelColor = it))) }
            ToggleRow("Shape behind labels", look.labelFill != null) { on ->
                onChange(theme.copy(bottom = look.copy(labelFill = if (on) SolidFill(0x80000000) else null)))
            }
            look.labelFill?.let { fill ->
                FillEditor(fill) { onChange(theme.copy(bottom = look.copy(labelFill = it))) }
            }
        }
        ThemePart.Button -> {
            val look = theme.button
            OutlinedButton({
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
            }) { Text("Match this shelf's header") }
            EditorHeading("Fill")
            FillEditor(look.fill) { onChange(theme.copy(button = look.copy(fill = it))) }
            ThemeSlider("Corner", look.cornerDp, 0f..40f, " dp") { onChange(theme.copy(button = look.copy(cornerDp = it))) }
            EditorHeading("Font")
            FontEditor(look.font) { onChange(theme.copy(button = look.copy(font = it))) }
            ToggleRow("Bold", look.bold) { onChange(theme.copy(button = look.copy(bold = it))) }
            ToggleRow("Italic", look.italic) { onChange(theme.copy(button = look.copy(italic = it))) }
            ColorRow("Text", look.textColor) { onChange(theme.copy(button = look.copy(textColor = it))) }
            ContrastWarning(look.textColor, look.fill)
            StrokeEditor(look.stroke) { onChange(theme.copy(button = look.copy(stroke = it))) }
            ShadowEditor(look.shadow) { onChange(theme.copy(button = look.copy(shadow = it))) }
        }
    }
}
