package com.tim.reader.sync

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tim.reader.data.HighlightRecord
import com.tim.reader.data.LibraryRepository
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Sync for the EPUB reader. Places go out as KOReader paths, worked out in the page from the
 * first and last letters on screen. Places coming in are followed through the chapter's file
 * and handed to Readium as a quote to find, so they land on the same words.
 *
 * KOReader numbers chapters across the book's whole spine, including pages marked as outside
 * the reading order, like many covers, which Readium leaves out. So chapter numbers are mapped
 * between the two.
 */
@OptIn(ExperimentalReadiumApi::class)
class EpubSync(
    private val publication: Publication,
    private val navigator: EpubNavigatorFragment,
) : SyncReader {
    /** The first and last letters on screen, as paths, from the latest page. */
    @Volatile private var screenStart: String? = null
    @Volatile private var screenEnd: String? = null
    @Volatile private var percentage = 0.0
    /** For each chapter Readium shows, its place in the full spine. */
    @Volatile private var spine: List<Int>? = null
    /** The last few chapters read, the least recently used first. */
    private val parsed = LinkedHashMap<Int, List<Markup>>(8, 0.75f, true)

    /** Works out this page's place. Call on the main thread after the page settles. */
    suspend fun locate(locator: Locator): LocalSpot? {
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href) ?: return null
        val fragment = spineIndexes().getOrElse(chapter) { chapter } + 1
        percentage = locator.locations.totalProgression ?: percentage
        val overflow = navigator.overflow.value
        val rtl = overflow.readingProgression == ReadingProgression.RTL
        val script = "$SCREEN_SCRIPT($fragment, ${overflow.scroll}, $rtl)"
        val answer = runCatching { navigator.evaluateJavascript(script) }.getOrNull()
        val json = answer?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        screenStart = json?.optString("start")?.takeIf { it.startsWith("/body/") }
        screenEnd = json?.optString("end")?.takeIf { it.startsWith("/body/") }
        return LocalSpot(screenStart ?: "/body/DocFragment[$fragment]/body", percentage)
    }

    private suspend fun spineIndexes(): List<Int> {
        spine?.let { return it }
        // Only a spine that was read is kept. The book may just be closing, and next time it can be read.
        val found = withContext(Dispatchers.IO) { runCatching { readSpine() }.getOrNull() }
            ?: return publication.readingOrder.indices.toList()
        spine = found
        return found
    }

    private suspend fun readFile(path: String): ByteArray? {
        val url = Url.fromDecodedPath(path) ?: return null
        val resource = publication.get(url) ?: return null
        return try {
            resource.read().getOrNull()
        } finally {
            resource.close()
        }
    }

    /** Finds each reading-order chapter in the full spine, from the book's package file. */
    private suspend fun readSpine(): List<Int>? {
        val container = readFile("META-INF/container.xml") ?: return null
        val packagePath = firstAttribute(container, "rootfile", "full-path") ?: return null
        val opf = readFile(packagePath) ?: return null
        val (hrefs, order) = readPackage(opf)
        val folder = packagePath.substringBeforeLast('/', "")
        val spinePaths = order.mapNotNull { id -> hrefs[id] }.map { href -> joinBookPath(folder, decodePercent(href.substringBefore('#'))) }
        val readingPaths = publication.readingOrder.map { link -> link.url().path?.trimStart('/') }
        return readingPaths.map { path -> spinePaths.indexOf(path).takeIf { it >= 0 } ?: return null }
    }

    /** The chapter Readium shows for a spine number. A page outside the reading order goes to the next chapter. */
    private fun readingIndexOf(spineIndex: Int, indexes: List<Int>): Int? {
        indexes.indexOf(spineIndex).takeIf { it >= 0 }?.let { return it }
        return indexes.indexOfFirst { it > spineIndex }.takeIf { it >= 0 }
    }

    /** The chapter's markup, read the way KOReader's engine sees it. A few chapters are kept. */
    private suspend fun markup(chapter: Int): List<Markup>? {
        synchronized(parsed) { parsed[chapter] }?.let { return it }
        val link = publication.readingOrder.getOrNull(chapter) ?: return null
        val pieces = withContext(Dispatchers.IO) {
            val resource = publication.get(link) ?: return@withContext null
            val bytes = try {
                resource.read().getOrNull()
            } finally {
                resource.close()
            }
            bytes?.let(::parseMarkup)
        } ?: return null
        synchronized(parsed) {
            parsed[chapter] = pieces
            while (parsed.size > 3) parsed.remove(parsed.keys.first())
        }
        return pieces
    }

    /** Follows a path to a chapter Readium shows, and a spot in it. */
    private suspend fun place(path: String): Pair<Int, XPointerTarget>? {
        val pointer = XPointer.parse(path) ?: return null
        val chapter = readingIndexOf(pointer.chapter, spineIndexes()) ?: return null
        if (spineIndexes().getOrNull(chapter) != pointer.chapter) {
            // The path is in a page outside the reading order, so it lands at the next chapter's start.
            return chapter to XPointerTarget(0, 1, null, "", "", "", exact = false)
        }
        val pieces = markup(chapter) ?: return null
        return chapter to withContext(Dispatchers.Default) { resolveXPointer(pointer, pieces.asSequence()) }
    }

    override suspend fun compare(remote: RemoteProgress): Comparison {
        val there = place(remote.progress)
        val here = screenStart?.let { place(it) }
        val hereEnd = screenEnd?.let { place(it) } ?: here
        val go: suspend () -> Boolean = { goTo(remote, there) }
        if (there == null || here == null || hereEnd == null) {
            // Without a path to follow, the percentages decide, allowing for each app counting a little differently.
            val difference = remote.percentage - percentage
            return when {
                difference > 0.005 -> Comparison.Ahead(go)
                difference < -0.005 -> Comparison.Behind
                else -> Comparison.Same
            }
        }
        val (chapter, target) = there
        return when {
            chapter > hereEnd.first || (chapter == hereEnd.first && target.charOffset > hereEnd.second.charOffset) ->
                Comparison.Ahead(go)
            chapter < here.first || (chapter == here.first && target.charOffset < here.second.charOffset) ->
                Comparison.Behind
            else -> Comparison.Same
        }
    }

    private suspend fun goTo(remote: RemoteProgress, there: Pair<Int, XPointerTarget>?): Boolean {
        val locator = there?.let { (chapter, target) -> locatorFor(chapter, target) }
            ?: publication.locateProgression(remote.percentage)
            ?: return false
        return withContext(Dispatchers.Main) { navigator.go(locator) }
    }

    /** The chapter's name from the contents, for clippings. */
    private fun titleOf(chapter: Int): String {
        val href = publication.readingOrder.getOrNull(chapter)?.url()?.path ?: return ""
        fun search(links: List<Link>): String? {
            for (link in links) {
                if (link.url().removeFragment().path == href) link.title?.let { return it }
                search(link.children)?.let { return it }
            }
            return null
        }
        return search(publication.tableOfContents).orEmpty()
    }

    /** Where a highlight sits, the way CrossPoint saves a clipping. */
    suspend fun clipPlace(locatorJson: String, text: String): ClipPlace? {
        val locator = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() ?: return null
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href) ?: return null
        val spineIndex = spineIndexes().getOrElse(chapter) { chapter }
        val title = locator.title?.takeIf { it.isNotBlank() } ?: titleOf(chapter)
        // Unreadable now, perhaps because the book is closing, so it waits for the next sync.
        val pieces = markup(chapter)?.takeIf { it.isNotEmpty() } ?: return null
        val chapterText = withContext(Dispatchers.Default) { visibleText(pieces.asSequence()) }
        val quote = locator.text.highlight?.takeIf { it.isNotEmpty() } ?: text
        val range = findQuote(
            chapterText,
            quote,
            locator.text.before.orEmpty(),
            locator.text.after.orEmpty(),
            locator.locations.progression,
        )
        return ClipPlace(
            spine = spineIndex,
            start = range?.let { codepointIndex(chapterText, it.first) },
            end = range?.let { codepointIndex(chapterText, it.last + 1) },
            chapter = title,
        )
    }

    /** A locator for a clipping from another device, saved the way the reader saves its own highlights. */
    suspend fun clippingLocator(spine: Int, start: Int?, end: Int?, text: String): String? {
        val indexes = spineIndexes()
        val chapter = readingIndexOf(spine, indexes)?.takeIf { indexes.getOrNull(it) == spine } ?: return null
        val link = publication.readingOrder.getOrNull(chapter) ?: return null
        // Unreadable now, perhaps because the book is closing, so it waits for the next sync.
        val pieces = markup(chapter)?.takeIf { it.isNotEmpty() } ?: return null
        val chapterText: String? = withContext(Dispatchers.Default) { visibleText(pieces.asSequence()) }
        var range: IntRange? = null
        if (chapterText != null) {
            if (start != null && end != null) {
                val from = utf16Index(chapterText, start)
                val to = utf16Index(chapterText, end)
                if (from != null && to != null && to > from) range = from until to
            }
            // Offsets from a different copy of the book can point at other words, so the words decide.
            // A long clipping's words may have been cut short, so they only need to start the same.
            fun squeezed(value: String) = value.filterNot { it.isWhitespace() || it == '\u00AD' }
            if (range == null || !squeezed(chapterText.substring(range)).startsWith(squeezed(text).take(200))) {
                range = findQuote(chapterText, text, "", "", range?.let { it.first.toDouble() / chapterText.length }) ?: range
            }
        }
        val highlight = if (chapterText != null && range != null) chapterText.substring(range) else text
        val before = if (chapterText != null && range != null) chapterText.substring(maxOf(0, range.first - 40), range.first) else null
        val after = if (chapterText != null && range != null) {
            chapterText.substring(range.last + 1, minOf(chapterText.length, range.last + 41))
        } else {
            null
        }
        val progression = if (chapterText != null && range != null && chapterText.isNotEmpty()) {
            range.first.toDouble() / chapterText.length
        } else {
            0.0
        }
        return Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            title = titleOf(chapter).ifBlank { null },
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(before = before, highlight = highlight, after = after),
        ).toJSON().toString()
    }

    private fun locatorFor(chapter: Int, target: XPointerTarget): Locator? {
        val link = publication.readingOrder.getOrNull(chapter) ?: return null
        val quote = target.highlight.takeIf { target.exact && it.isNotBlank() }
        return Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            title = link.title,
            locations = Locator.Locations(
                progression = target.progression,
                otherLocations = target.cssSelector?.let { mapOf("cssSelector" to it) } ?: emptyMap(),
            ),
            text = if (quote != null) Locator.Text(before = target.before, highlight = quote, after = target.after) else Locator.Text(),
        )
    }
}

/** Common HTML names for characters, which EPUB files use without declaring. */
private val ENTITIES = mapOf(
    "nbsp" to " ", "shy" to "­", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©",
    "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»", "middot" to "·",
    "bull" to "•", "deg" to "°", "times" to "×", "eacute" to "é", "egrave" to "è",
    "aacute" to "á", "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
    "ntilde" to "ñ", "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö", "auml" to "ä",
    "szlig" to "ß", "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "‌",
    "zwj" to "‍", "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡",
    "prime" to "′", "Prime" to "″", "iexcl" to "¡", "iquest" to "¿", "pound" to "£",
    "euro" to "€", "yen" to "¥", "cent" to "¢", "frac12" to "½", "frac14" to "¼",
    "frac34" to "¾", "larr" to "←", "rarr" to "→",
)

private fun newParser(bytes: ByteArray): XmlPullParser {
    val parser = Xml.newPullParser()
    // Android's parser reads the DOCTYPE by default, and then won't take the names below.
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
    runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    parser.setInput(ByteArrayInputStream(bytes), null)
    ENTITIES.forEach { (name, value) -> runCatching { parser.defineEntityReplacementText(name, value) } }
    return parser
}

private fun XmlPullParser.attribute(name: String): String? =
    (0 until attributeCount).firstOrNull { attributeName(it) == name }?.let { getAttributeValue(it) }

private fun XmlPullParser.attributeName(index: Int) = getAttributeName(index).substringAfter(':')

/** The first [attribute] on a [tag] element, ignoring namespace prefixes. */
private fun firstAttribute(bytes: ByteArray, tag: String, attribute: String): String? = runCatching {
    val parser = newParser(bytes)
    var event = parser.next()
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == tag) {
            parser.attribute(attribute)?.let { return@runCatching it }
        }
        event = parser.next()
    }
    null
}.getOrNull()

/** A package file's manifest, as ids to paths, and its spine as ids in order, every page included. */
private fun readPackage(bytes: ByteArray): Pair<Map<String, String>, List<String>> {
    val hrefs = HashMap<String, String>()
    val order = ArrayList<String>()
    runCatching {
        val parser = newParser(bytes)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "item" -> {
                        val id = parser.attribute("id")
                        val href = parser.attribute("href")
                        if (id != null && href != null) hrefs[id] = href
                    }
                    "itemref" -> parser.attribute("idref")?.let { order += it }
                }
            }
            event = parser.next()
        }
    }
    return hrefs to order
}

/** Reads a chapter file into pieces. Broken markup ends the list where it breaks. */
internal fun parseMarkup(bytes: ByteArray): List<Markup> {
    val pieces = ArrayList<Markup>()
    try {
        val parser = newParser(bytes)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> pieces += Markup.Open(parser.name)
                XmlPullParser.END_TAG -> pieces += Markup.Close
                XmlPullParser.TEXT -> parser.text?.let { if (it.isNotEmpty()) pieces += Markup.Text(it) }
            }
            event = parser.next()
        }
    } catch (_: Exception) {
        // What was read so far is still useful.
    }
    return pieces
}

/**
 * Runs in the page. Finds the first and last letters on screen and writes each as a KOReader
 * path. Text KOReader might count differently ends the path at its element instead.
 */
private const val SCREEN_SCRIPT = """(function (chapter, scroll, rtl) {
  try {
    var body = document.body;
    if (!body) return null;
    var W = window.innerWidth, H = window.innerHeight;
    function isBefore(r) { return scroll ? r.bottom <= 1 : (rtl ? r.left >= W - 1 : r.right <= 1); }
    function isAfter(r) { return scroll ? r.top >= H - 1 : (rtl ? r.right <= 1 : r.left >= W - 1); }
    var range = document.createRange();
    function rectAt(node, i) {
      range.setStart(node, i);
      range.setEnd(node, i + 1);
      var r = range.getClientRects();
      return r.length ? r[0] : null;
    }
    // A collapsed space has no box, so the nearest letter that has one stands in for it.
    function charRect(node, i) {
      for (var j = i; j < node.data.length && j < i + 16; j++) { var a = rectAt(node, j); if (a) return a; }
      for (var k = i - 1; k >= 0 && k > i - 16; k--) { var b = rectAt(node, k); if (b) return b; }
      return null;
    }
    function nodeState(node) {
      range.selectNodeContents(node);
      var rs = range.getClientRects(), seen = false, before = true, after = true;
      for (var i = 0; i < rs.length; i++) {
        var r = rs[i];
        if (r.width === 0 && r.height === 0) continue;
        seen = true;
        if (!isBefore(r)) before = false;
        if (!isAfter(r)) after = false;
      }
      if (!seen) return 0;
      if (before) return -1;
      if (after) return 1;
      return 2;
    }
    function solid(node, i) { return !/\s/.test(node.data.charAt(i)); }
    function firstShown(node) {
      var lo = 0, hi = node.data.length - 1;
      while (lo < hi) {
        var mid = (lo + hi) >> 1, r = charRect(node, mid);
        if (r && isBefore(r)) lo = mid + 1; else hi = mid;
      }
      while (lo < node.data.length - 1 && !solid(node, lo)) lo++;
      return lo;
    }
    function lastShown(node) {
      var lo = 0, hi = node.data.length - 1;
      while (lo < hi) {
        var mid = (lo + hi + 1) >> 1, r = charRect(node, mid);
        if (r && isAfter(r)) hi = mid - 1; else lo = mid;
      }
      return lo;
    }
    function hidden(el) {
      for (; el && el !== body; el = el.parentNode) {
        var n = (el.localName || '').toLowerCase();
        if (n === 'script' || n === 'style' || n === 'rp' || n === 'title') return true;
      }
      return false;
    }
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) { return /\S/.test(n.data) && !hidden(n.parentNode) ? 1 : 3; }
    });
    var first = null, firstAt = 0, last = null, lastAt = 0, n;
    while ((n = walker.nextNode())) {
      var state = nodeState(n);
      if (state === 1) break;
      if (state !== 2) continue;
      if (!first) { first = n; firstAt = firstShown(n); }
      last = n; lastAt = lastShown(n);
    }
    function counted(text, end) {
      var count = 0, i = 0;
      while (i < end) {
        if (/[ \t\r\n]/.test(text.charAt(i))) { while (i < end && /[ \t\r\n]/.test(text.charAt(i))) i++; }
        else { var c = text.charCodeAt(i); i += (c >= 0xD800 && c <= 0xDBFF) ? 2 : 1; }
        count++;
      }
      return count;
    }
    function elementPath(el) {
      var steps = [];
      for (; el && el !== body; el = el.parentNode) {
        if (el.nodeType !== 1 || !el.parentNode) return null;
        var name = el.localName, count = 0, mine = 0;
        for (var s = el.parentNode.firstElementChild; s; s = s.nextElementSibling) {
          if (s.localName === name) { count++; if (s === el) mine = count; }
        }
        steps.unshift(count > 1 ? name + '[' + mine + ']' : name);
      }
      if (el !== body) return null;
      return '/body/DocFragment[' + chapter + ']/body' + (steps.length ? '/' + steps.join('/') : '');
    }
    function pathOf(node, at) {
      var parent = node.parentNode, base = elementPath(parent);
      if (base === null) return null;
      var index = 0, count = 0, spacesBefore = false;
      for (var c = parent.firstChild; c; c = c.nextSibling) {
        if ((c.nodeType !== 3 && c.nodeType !== 4) || c.data.length === 0) continue;
        count++;
        if (c === node) index = count;
        else if (!index && !/\S/.test(c.data)) spacesBefore = true;
      }
      // Only a container drops that text from its count, so only there is the count unsure.
      if (spacesBefore && /^(body|div|section|article|aside|header|footer|nav|main|ul|ol|dl|table|thead|tbody|tfoot|tr|figure|blockquote|hgroup|details)$/i.test(parent.localName)) return base + '.0';
      return base + '/text()' + (count > 1 ? '[' + index + ']' : '') + '.' + counted(node.data, at);
    }
    function firstElement(root) {
      for (var c = root.firstElementChild; c; c = c.nextElementSibling) {
        var rs = c.getClientRects();
        for (var i = 0; i < rs.length; i++) {
          if (!isBefore(rs[i]) && !isAfter(rs[i]) && (rs[i].width > 0 || rs[i].height > 0)) return firstElement(c) || c;
        }
      }
      return null;
    }
    if (!first) {
      var shown = firstElement(body);
      var path = shown ? elementPath(shown) : null;
      return { start: path ? path + '.0' : null, end: null };
    }
    return { start: pathOf(first, firstAt), end: pathOf(last, lastAt) };
  } catch (e) {
    return null;
  }
})"""

/** The reader's highlights for one book, for [ClippingSync]. */
class EpubHighlightBook(
    private val repository: LibraryRepository,
    private val bookId: String,
    private val epub: EpubSync,
) : HighlightBook {
    override val key = bookId

    override suspend fun records() = repository.highlightRecords(bookId)

    override suspend fun add(highlights: List<HighlightRecord>) = repository.addSyncedHighlights(bookId, highlights)

    override suspend fun setNote(id: String, note: String) {
        repository.updateNote(id, note)
    }

    override suspend fun remove(id: String) {
        repository.removeHighlight(id)
    }

    override suspend fun placeOf(record: HighlightRecord) = epub.clipPlace(record.locator, record.text)

    override suspend fun locatorFor(spine: Int, start: Int?, end: Int?, text: String) =
        epub.clippingLocator(spine, start, end, text)
}
