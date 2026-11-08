package com.tim.reader.library

import com.tim.reader.data.BookType

/** A title worked out from a file name, with its series when the name shows a volume number. */
data class ParsedName(val title: String, val series: String?, val number: Double?)

private val brackets = Regex("""[(\[{][^)\]}]*[)\]}]""")
private val copyPrefix = Regex("""^copy of\s+""", RegexOption.IGNORE_CASE)
private val extension = Regex("""\.(epub|pdf|cbz|cbr|cb7|zip|rar|7z|mobi|azw3?|prc)$""", RegexOption.IGNORE_CASE)
private val volumeMarker = Regex(
    """^(.*?)[\s_\-.,:]*\b(?:v|vol|volume|book|c|ch|chapter|ep|episode|issue|#)[\s.\-_#]*(\d+(?:\.\d+)?)(?!\d)""",
    RegexOption.IGNORE_CASE,
)
private val hashMarker = Regex("""^(.*?)[\s_\-.,:]*#\s*(\d+(?:\.\d+)?)(?!\d)""")
private val japaneseMarker = Regex("""^(.*?)\s*第?\s*(\d+(?:\.\d+)?)\s*[巻話]""")
private val trailingNumber = Regex("""^(.*?)[\s_\-]+(\d{1,3})$""")
private val spaces = Regex("""\s+""")

private fun tidy(text: String) = text
    .replace('_', ' ')
    .replace(spaces, " ")
    .trim(' ', '-', '.', ',', ':')

/** Bare trailing numbers only count for comics, so "Catch-22" and "Fahrenheit 451" stay whole. */
fun parseName(fileName: String, allowTrailingNumber: Boolean = true): ParsedName {
    val base = extension.replace(fileName, "")
    val clean = tidy(copyPrefix.replace(brackets.replace(base, " ").trim(), ""))
    val title = clean.ifBlank { tidy(base) }
    val match = hashMarker.find(clean)
        ?: volumeMarker.find(clean)
        ?: japaneseMarker.find(clean)
        ?: if (allowTrailingNumber) trailingNumber.find(clean) else null
    val series = match?.groupValues?.get(1)?.let(::tidy)?.takeIf { it.isNotBlank() }
    // A file named only "Vol. 12" has no series name, but its number still orders the series.
    val number = match?.groupValues?.get(2)?.toDoubleOrNull()
    return ParsedName(title, series, number)
}

/**
 * Treats "One Piece", "one_piece", and "ONE-PIECE" as the same series. Each type keeps its own, so
 * a manga and its light novels stay apart even with the same name.
 */
fun seriesMatchKey(title: String, type: BookType) =
    "${type.name}:" + title.lowercase().filter { it.isLetterOrDigit() }

/** The name a file gets in the library folder. */
fun safeFileName(name: String) = name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().ifBlank { "Untitled" }
