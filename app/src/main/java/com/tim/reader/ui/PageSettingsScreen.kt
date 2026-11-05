package com.tim.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.reader.appScope
import com.tim.reader.data.Book
import com.tim.reader.data.ColorSet
import com.tim.reader.data.FitMode
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.PageAnimation
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderPrefs
import com.tim.reader.data.Shelf
import com.tim.reader.data.USE_BOOK_VALUE
import com.tim.reader.reader.TEXT_FONTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMAGE_BACKGROUNDS = listOf("Black" to 0xFF000000, "Dark gray" to 0xFF1E1E1E, "White" to 0xFFFFFFFF, "Sepia" to 0xFFF4ECD8)
private val TEXT_COLORS = listOf("White" to DAY_COLORS, "Sepia" to SEPIA_COLORS, "Navy" to NAVY_COLORS)
private val TEXT_SIZES = listOf("Small" to 0.85, "Normal" to 1.0, "Large" to 1.25, "Extra large" to 1.5)
private val SPACINGS = listOf("Tight" to 1.2, "Normal" to USE_BOOK_VALUE, "Loose" to 1.8)
private val MARGINS = listOf("Narrow" to 0.5, "Normal" to 1.0, "Wide" to 2.0)

/** A choice that starts on "Keep as is", so only what's picked changes each book. */
@Composable
private fun <T> KeepChoice(title: String, options: List<Pair<String, T>>, value: T?, onChange: (T?) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    ChoiceRow("Keep as is", value == null) { onChange(null) }
    options.forEach { (label, option) ->
        ChoiceRow(label, value == option) { onChange(option) }
    }
}

/**
 * Sets page settings on many books at once. On a shelf, they can also become the defaults for
 * books added later.
 */
@Composable
fun PageSettingsScreen(title: String, books: List<Book>, shelf: Shelf?, onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    var prefs by remember { mutableStateOf(shelf?.readerPrefs ?: ReaderPrefs()) }
    var direction by remember { mutableStateOf(shelf?.defaultDirection) }
    var applyToBooks by remember { mutableStateOf(true) }
    var forNewBooks by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    // A shelf shows everything, because books added to it later could be of either kind.
    val hasText = shelf != null || books.isEmpty() || books.any { it.format.equals("epub", ignoreCase = true) }
    val hasPages = shelf != null || books.isEmpty() || books.any { !it.format.equals("epub", ignoreCase = true) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Page settings", style = MaterialTheme.typography.headlineMedium)
        Text("$title, ${books.size} books")

        KeepChoice("Read direction", ReadDirection.entries.map { it.label to it }, direction) { direction = it }

        if (hasPages) {
            KeepChoice("Page fit", FitMode.entries.map { it.label to it }, prefs.fit) {
                prefs = prefs.copy(fit = it)
            }
            KeepChoice("Comic and PDF background", IMAGE_BACKGROUNDS, prefs.background) {
                prefs = prefs.copy(background = it)
            }
        }
        KeepChoice("Page turn", PageAnimation.entries.map { it.label to it }, prefs.animation) {
            prefs = prefs.copy(animation = it)
        }

        if (hasText) {
            val colors = prefs.pageBackground?.let { page -> prefs.textColor?.let { ColorSet(page, it) } }
            KeepChoice("Text theme", TEXT_COLORS, colors) { set ->
                prefs = prefs.copy(pageBackground = set?.background, textColor = set?.text)
            }
            KeepChoice("Font", TEXT_FONTS, prefs.fontFamily) { prefs = prefs.copy(fontFamily = it) }
            KeepChoice("Text size", TEXT_SIZES, prefs.fontSize) { prefs = prefs.copy(fontSize = it) }
            KeepChoice("Spacing", SPACINGS, prefs.lineHeight) {
                val keep = it == null && prefs.pageMargins == null
                prefs = prefs.copy(lineHeight = it, bookLayout = if (keep) shelf?.readerPrefs?.bookLayout else false)
            }
            KeepChoice("Margin", MARGINS, prefs.pageMargins) {
                val keep = it == null && prefs.lineHeight == null
                prefs = prefs.copy(pageMargins = it, bookLayout = if (keep) shelf?.readerPrefs?.bookLayout else false)
            }
            KeepChoice("Layout", listOf("Turn pages" to false, "Scroll" to true), prefs.scrolling) {
                prefs = prefs.copy(scrolling = it)
            }
        }

        if (shelf != null) {
            Text("Apply to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            ToggleRow("Every book on this shelf now", applyToBooks) { applyToBooks = it }
            ToggleRow("Books added to this shelf later", forNewBooks) { forNewBooks = it }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onDone, Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    saving = true
                    // This finishes even if the screen closes, because it can touch many books.
                    appScope.launch {
                        if (applyToBooks) repository.applyToBooks(books.map { it.id }, prefs, direction)
                        if (shelf != null && forNewBooks) repository.setShelfDefaults(shelf.id, prefs, direction)
                        withContext(Dispatchers.Main) { onDone() }
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (saving) "Saving" else "Apply") }
        }
    }
}
