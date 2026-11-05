package com.tim.reader.reader

import com.tim.reader.data.BOOK_FONT
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.data.TextAlignment
import androidx.core.graphics.ColorUtils
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
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
fun epubPreferences(
    look: ReaderLook,
    direction: ReadDirection,
    twoColumns: Boolean,
    fixedLayout: Boolean,
): EpubPreferences {
    val font = look.fontFamily.takeIf { it != BOOK_FONT }
    // Readium ignores these while the book's own styles are in charge, but turning those styles
    // off also resets the book's type sizes, so it only happens when one of these is really set.
    val customLayout = look.lineHeight != null || look.paragraphSpacing != null || look.alignment != null
    // Colors flatten a book's own colored text, so the default pair leaves the book alone.
    val defaults = ReaderLook()
    val customColors = look.pageBackground != defaults.pageBackground || look.textColor != defaults.textColor
    val isDark = ColorUtils.calculateLuminance(look.pageBackground.toInt()) < 0.5
    return EpubPreferences(
        backgroundColor = if (customColors) Color(look.pageBackground.toInt()) else null,
        columnCount = if (twoColumns) ColumnCount.TWO else ColumnCount.ONE,
        fontFamily = font?.let { FontFamily(it) },
        fontSize = look.fontSize,
        lineHeight = look.lineHeight,
        pageMargins = look.pageMargins,
        paragraphSpacing = look.paragraphSpacing,
        publisherStyles = !customLayout,
        // Only right-to-left is forced, so a book that declares its own direction keeps it.
        readingProgression = if (direction == ReadDirection.RightToLeft) ReadingProgression.RTL else null,
        scroll = look.scrolling ?: (direction == ReadDirection.Vertical),
        spread = if (!fixedLayout) null else if (twoColumns) Spread.ALWAYS else Spread.NEVER,
        textAlign = when (look.alignment) {
            TextAlignment.Start -> TextAlign.START
            TextAlignment.Justify -> TextAlign.JUSTIFY
            null -> null
        },
        textColor = if (customColors) Color(look.textColor.toInt()) else null,
        theme = if (!customColors) null else if (isDark) Theme.DARK else Theme.LIGHT,
    )
}
