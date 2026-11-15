package com.tim.reader.sync

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.mediatype.MediaType
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Sync for the EPUB reader. Places go out as KOReader paths, worked out in the page from the
 * first and last letters on screen. Places coming in are followed through the chapter's file
 * and handed to Readium as a quote to find, so they land on the same words.
 */
@OptIn(ExperimentalReadiumApi::class)
class EpubSync(
    private val publication: Publication,
    private val navigator: EpubNavigatorFragment,
) : SyncReader {
    /** The first and last letters on screen, as paths, from the latest page. */
    private var screenStart: String? = null
    private var screenEnd: String? = null
    private var percentage = 0.0

    /** Works out this page's place. Call on the main thread after the page settles. */
    suspend fun locate(locator: Locator): LocalSpot? {
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href) ?: return null
        percentage = locator.locations.totalProgression ?: percentage
        val overflow = navigator.overflow.value
        val rtl = overflow.readingProgression == ReadingProgression.RTL
        val script = "$SCREEN_SCRIPT(${chapter + 1}, ${overflow.scroll}, $rtl)"
        val answer = runCatching { navigator.evaluateJavascript(script) }.getOrNull()
        val json = answer?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        screenStart = json?.optString("start")?.takeIf { it.startsWith("/body/") }
        screenEnd = json?.optString("end")?.takeIf { it.startsWith("/body/") }
        return LocalSpot(screenStart ?: "/body/DocFragment[${chapter + 1}]/body", percentage)
    }

    /** The chapter's markup, read the way KOReader's engine sees it. */
    private suspend fun markup(chapter: Int): List<Markup>? = withContext(Dispatchers.IO) {
        val link = publication.readingOrder.getOrNull(chapter) ?: return@withContext null
        val resource = publication.get(link) ?: return@withContext null
        val bytes = try {
            resource.read().getOrNull()
        } finally {
            resource.close()
        }
        bytes?.let(::parseMarkup)
    }

    private suspend fun place(path: String): Pair<Int, XPointerTarget>? {
        val pointer = XPointer.parse(path) ?: return null
        val pieces = markup(pointer.chapter) ?: return null
        return pointer.chapter to withContext(Dispatchers.Default) { resolveXPointer(pointer, pieces.asSequence()) }
    }

    override suspend fun compare(remote: RemoteProgress): Comparison {
        val there = place(remote.progress)
        val here = screenStart?.let { place(it) }
        val hereEnd = screenEnd?.let { place(it) } ?: here
        val go: suspend () -> Unit = { goTo(remote, there) }
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

    private suspend fun goTo(remote: RemoteProgress, there: Pair<Int, XPointerTarget>?) {
        val locator = there?.let { (chapter, target) -> locatorFor(chapter, target) }
            ?: publication.locateProgression(remote.percentage)
            ?: return
        withContext(Dispatchers.Main) { navigator.go(locator) }
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

/** Reads a chapter file into pieces. Broken markup ends the list where it breaks. */
internal fun parseMarkup(bytes: ByteArray): List<Markup> {
    val pieces = ArrayList<Markup>()
    val parser = Xml.newPullParser()
    runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
    runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
    parser.setInput(ByteArrayInputStream(bytes), null)
    ENTITIES.forEach { (name, value) -> runCatching { parser.defineEntityReplacementText(name, value) } }
    try {
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
    function charRect(node, i) {
      range.setStart(node, i);
      range.setEnd(node, i + 1);
      var r = range.getClientRects();
      return r.length ? r[0] : null;
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
      if (spacesBefore) return base + '.0';
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
