package com.tim.reader.engine

/** Puts page 10 after page 9, ignoring case. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val iEnd = digitRunEnd(a, i)
                val jEnd = digitRunEnd(b, j)
                val x = a.substring(i, iEnd).trimStart('0')
                val y = b.substring(j, jEnd).trimStart('0')
                if (x.length != y.length) return x.length - y.length
                val byDigits = x.compareTo(y)
                if (byDigits != 0) return byDigits
                i = iEnd
                j = jEnd
            } else {
                val diff = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (diff != 0) return diff
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end] in '0'..'9') end++
        return end
    }
}

/** One file inside an archive, or one document in a folder. */
data class RawEntry(val ordinal: Int, val path: String, val size: Long)

/** One readable page. */
data class Page(val ordinal: Int, val path: String, val chapter: String)

data class PageListing(
    val pages: List<Page>,
    /** Archives inside the archive that repeat images already sitting beside them. */
    val duplicateNested: List<String>,
    /** Archives inside the archive holding pages found nowhere else. Phase 1 will read these. */
    val unreadNested: List<String>,
    /** Where the comic's ComicInfo.xml sits in the archive, when it has one. */
    val comicInfoOrdinal: Int? = null,
)

private val imageExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "jp2", "j2k", "jpx", "jpf",
)
private val archiveExtensions = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z")

private fun extensionOf(path: String) = path.substringAfterLast('.', "").lowercase()

/** RAR archives made on Windows use backslashes. */
fun normalizePath(path: String) = path.replace('\\', '/').trimEnd('/')

private fun isJunk(path: String): Boolean {
    val parts = path.split('/')
    val name = parts.last().lowercase()
    return parts.any { it.equals("__MACOSX", ignoreCase = true) } ||
        name.startsWith(".") || name == "thumbs.db" || name == "desktop.ini"
}

fun buildPageListing(entries: List<RawEntry>): PageListing {
    val files = entries
        .map { it.copy(path = normalizePath(it.path)) }
        .filterNot { isJunk(it.path) }
    val images = files.filter { extensionOf(it.path) in imageExtensions }
    val nested = files.filter { extensionOf(it.path) in archiveExtensions }
    val foldersWithImages = images.map { it.path.substringBeforeLast('/', "") }.toSet()
    val (duplicates, unread) = nested.partition { it.path.substringBeforeLast('/', "") in foldersWithImages }
    return PageListing(
        pages = images
            .sortedWith(compareBy(NaturalOrder) { it.path })
            .map { Page(it.ordinal, it.path, it.path.substringBeforeLast('/', "")) },
        duplicateNested = duplicates.map { it.path },
        unreadNested = unread.map { it.path },
        comicInfoOrdinal = files.firstOrNull {
            it.path.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)
        }?.ordinal,
    )
}
