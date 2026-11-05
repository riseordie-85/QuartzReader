package com.tim.reader.reader

import com.tim.reader.data.BOOK_FONT
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.data.TextAlignment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.ExperimentalReadiumApi

/** Fonts every device can show, by their CSS name. */
val TEXT_FONTS = listOf(
    "Book's font" to BOOK_FONT,
    "Serif" to "serif",
    "Sans serif" to "sans-serif",
    "Monospace" to "monospace",
    "Cursive" to "cursive",
    "OpenDyslexic" to "OpenDyslexic",
)

/** Turns reader settings into Readium's preferences. */
@OptIn(ExperimentalReadiumApi::class)
fun epubPreferences(look: ReaderLook, direction: ReadDirection, twoColumns: Boolean): EpubPreferences {
    val font = look.fontFamily.takeIf { it != BOOK_FONT }
    // Readium ignores most layout settings while the book's own styles are in charge.
    val customLayout = font != null || look.lineHeight != null || look.paragraphSpacing != null ||
        look.alignment != null
    return EpubPreferences(
        backgroundColor = Color(look.pageBackground.toInt()),
        columnCount = if (twoColumns) ColumnCount.TWO else ColumnCount.ONE,
        fontFamily = font?.let { FontFamily(it) },
        fontSize = look.fontSize,
        lineHeight = look.lineHeight,
        pageMargins = look.pageMargins,
        paragraphSpacing = look.paragraphSpacing,
        publisherStyles = !customLayout,
        readingProgression = if (direction == ReadDirection.RightToLeft) ReadingProgression.RTL else ReadingProgression.LTR,
        scroll = look.scrolling ?: (direction == ReadDirection.Vertical),
        textAlign = when (look.alignment) {
            TextAlignment.Start -> TextAlign.START
            TextAlignment.Justify -> TextAlign.JUSTIFY
            TextAlignment.Center -> TextAlign.CENTER
            null -> null
        },
        textColor = Color(look.textColor.toInt()),
    )
}
