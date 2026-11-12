package com.tim.reader.stats

import com.tim.reader.data.Book
import com.tim.reader.data.BookType
import com.tim.reader.data.LibraryRepository.ImportedRead
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a Goodreads export holds, ready to import. Counts are for the preview. */
class GoodreadsPlan(
    val reads: List<ImportedRead>,
    val matched: Int,
    val elsewhere: Int,
    val ratings: Int,
    val reviews: Int,
    val skipped: Int,
    val undated: Int,
)

private val GOODREADS_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/**
 * Reads a Goodreads library export (Settings > Import and export on Goodreads). Only books on the
 * "read" shelf come in. Books in the library are matched by title and author; the rest come in as
 * read elsewhere. Ones imported before are skipped, and ratings already given in the app are kept.
 */
fun planGoodreadsImport(csv: String, books: List<Book>, history: ReadingHistory, zone: ZoneId = ZoneId.systemDefault()): GoodreadsPlan {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull()?.map { it.trim() } ?: return GoodreadsPlan(emptyList(), 0, 0, 0, 0, 0, 0)
    fun column(name: String) = header.indexOfFirst { it.equals(name, ignoreCase = true) }
    val idColumn = column("Book Id")
    val titleColumn = column("Title")
    val authorColumn = column("Author")
    val ratingColumn = column("My Rating")
    val readColumn = column("Date Read")
    val addedColumn = column("Date Added")
    val shelfColumn = column("Exclusive Shelf")
    val reviewColumn = column("My Review")
    val spoilerColumn = column("Spoiler")
    require(titleColumn >= 0 && shelfColumn >= 0) { "This doesn't look like a Goodreads export" }

    val imported = history.finished.mapNotNullTo(HashSet()) { it.sourceId }
    val byTitle = books.groupBy { titleKey(it.title) }
    val reads = ArrayList<ImportedRead>()
    var matched = 0
    var elsewhere = 0
    var ratings = 0
    var reviews = 0
    var skipped = 0
    var undated = 0

    for (row in rows.drop(1)) {
        fun cell(index: Int) = row.getOrNull(index)?.trim().orEmpty()
        if (!cell(shelfColumn).equals("read", ignoreCase = true)) continue
        val id = cell(idColumn)
        if (id.isNotEmpty() && id in imported) {
            skipped++
            continue
        }
        val title = cell(titleColumn)
        if (title.isEmpty()) continue
        val author = cell(authorColumn)
        val book = byTitle[titleKey(title)]?.let { candidates ->
            candidates.firstOrNull { sameAuthor(it.author, author) } ?: candidates.singleOrNull()?.takeIf { it.author.isBlank() }
        }

        val readDate = parseDate(cell(readColumn))
        val at = (readDate ?: parseDate(cell(addedColumn)) ?: LocalDate.now(zone)).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        if (readDate == null) undated++
        val data = JSONObject().put("source", "goodreads").put("dateUnknown", readDate == null)
        if (id.isNotEmpty()) data.put("goodreads", id)
        if (book == null) {
            data.put("title", cleanTitle(title)).put("author", author).put("type", BookType.Book.name)
            elsewhere++
        } else {
            matched++
        }

        val stars = cell(ratingColumn).toIntOrNull()?.takeIf { it in 1..5 }
        val review = plainReview(cell(reviewColumn))
        // A rating made in the app wins over the imported one.
        val keepOwn = book != null && history.ratings.containsKey(book.hash)
        val rating = if (stars != null && !keepOwn) {
            ratings++
            if (review.isNotEmpty()) reviews++
            Rating(stars.toFloat(), review, cell(spoilerColumn).equals("true", ignoreCase = true)).toJson().also { json ->
                if (book == null) json.put("elsewhere", elsewhereKey(cleanTitle(title), author))
            }
        } else {
            null
        }
        reads += ImportedRead(book, at, data, rating)
    }
    return GoodreadsPlan(reads, matched, elsewhere, ratings, reviews, skipped, undated)
}

private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text, GOODREADS_DATE) }.getOrNull()

// Goodreads adds the series to titles, like "Leviathan Wakes (The Expanse, #1)".
private val SERIES_SUFFIX = Regex("""\s*\([^()]*#\s*[\d.]+\)\s*$""")

private fun cleanTitle(title: String) = title.replace(SERIES_SUFFIX, "").trim()

internal fun titleKey(title: String) = cleanTitle(title).lowercase()
    .replace(Regex("""^(the|a|an)\s+"""), "")
    .substringBefore(':')
    .filter { it.isLetterOrDigit() }

/** Authors match on their last names, so "J.R.R. Tolkien" finds "Tolkien, J. R. R." too. */
internal fun sameAuthor(ours: String, theirs: String): Boolean {
    fun lastName(name: String): String {
        val trimmed = name.trim()
        val last = if (',' in trimmed) trimmed.substringBefore(',') else trimmed.substringAfterLast(' ')
        return last.lowercase().filter { it.isLetter() }
    }
    val a = lastName(ours)
    return a.isNotEmpty() && a == lastName(theirs)
}

/** Goodreads reviews are HTML, so line breaks are kept and the rest of the markup dropped. */
private fun plainReview(html: String): String = html
    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .trim()

/** A small CSV reader that handles quoted fields with commas, quotes and line breaks inside. */
fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    val input = text.removePrefix("﻿")
    while (i < input.length) {
        val c = input[i]
        when {
            quoted && c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            !quoted && c == ',' -> {
                row += field.toString()
                field.clear()
            }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                row += field.toString()
                field.clear()
                if (row.any { it.isNotEmpty() }) rows += row
                row = ArrayList()
            }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        if (row.any { it.isNotEmpty() }) rows += row
    }
    return rows
}
