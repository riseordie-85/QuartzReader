package com.tim.reader.sync

/**
 * KOReader and CrossPoint describe a place in an EPUB with a path like
 * `/body/DocFragment[3]/body/div[2]/p[14]/text()[1].57`: the chapter, the elements down from its body,
 * which text inside the last element, and how many characters into that text.
 *
 * This file only reads those paths. Making one happens in the page itself, from what's on screen.
 */
data class XPointer(
    /** The chapter, counting from 0 in the reading order. */
    val chapter: Int,
    val steps: List<Step>,
    /** Which text inside the last element, from 1, or null when the path ends at the element. */
    val textIndex: Int?,
    val offset: Int,
) {
    data class Step(val name: String, val index: Int)

    companion object {
        private val CHAPTER = Regex("""^/body/DocFragment\[(\d+)]""")
        private val STEP = Regex("""^([A-Za-z_][\w.:-]*)(?:\[(\d+)])?$""")
        private val TEXT = Regex("""^text\(\)(?:\[(\d+)])?$""")

        /** Reads a path, or null when it isn't one. A path with only the chapter means its start. */
        fun parse(path: String): XPointer? {
            val trimmed = path.trim()
            val chapterMatch = CHAPTER.find(trimmed) ?: return null
            val chapter = chapterMatch.groupValues[1].toIntOrNull()?.minus(1) ?: return null
            if (chapter < 0) return null
            var rest = trimmed.substring(chapterMatch.range.last + 1)
            if (rest.isEmpty() || rest == "/body") return XPointer(chapter, emptyList(), null, 0)
            if (!rest.startsWith("/body/")) return XPointer(chapter, emptyList(), null, 0)
            rest = rest.removePrefix("/body")

            // The offset is the number after the last dot, when the dot follows a bracket or a name.
            var offset = 0
            val dot = rest.lastIndexOf('.')
            if (dot > rest.lastIndexOf('/') && dot > rest.lastIndexOf(']') && dot < rest.length - 1) {
                val number = rest.substring(dot + 1)
                if (number.all(Char::isDigit)) {
                    offset = number.toIntOrNull() ?: 0
                    rest = rest.substring(0, dot)
                }
            }

            val parts = rest.split('/').filter { it.isNotEmpty() }
            val steps = mutableListOf<Step>()
            var textIndex: Int? = null
            for ((i, part) in parts.withIndex()) {
                val text = TEXT.matchEntire(part)
                if (text != null) {
                    if (i != parts.lastIndex) return null
                    textIndex = text.groupValues[1].toIntOrNull() ?: 1
                    continue
                }
                val step = STEP.matchEntire(part) ?: return null
                val name = step.groupValues[1].substringAfter(':')
                steps += Step(name, step.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
            return XPointer(chapter, steps, textIndex, offset.coerceAtLeast(0))
        }
    }
}

/** One piece of a chapter file, read in order. */
sealed interface Markup {
    data class Open(val name: String) : Markup
    data object Close : Markup
    data class Text(val text: String) : Markup
}

/** Where a path lands in a chapter, in a form the reader can go to. */
data class XPointerTarget(
    /** Characters of body text before the spot, out of [totalChars]. */
    val charOffset: Int,
    val totalChars: Int,
    /** A CSS selector for the element, or null when one can't be written. */
    val cssSelector: String?,
    /** Text just before the spot and from the spot on, inside that element, for finding it on the page. */
    val before: String,
    val highlight: String,
    val after: String,
    /** True when every step of the path was found. False means only the chapter matched. */
    val exact: Boolean,
) {
    val progression get() = if (totalChars <= 0) 0.0 else (charOffset.toDouble() / totalChars).coerceIn(0.0, 1.0)
}

private val HIDDEN = setOf("head", "style", "script", "title", "rp")

/** Spaces as KOReader's engine sees them: a run of these is one character. */
private fun isSpace(char: Char) = char == ' ' || char == '\n' || char == '\r' || char == '\t'

/** The index in [text] of the character KOReader counts as [collapsed], where space runs count once. */
internal fun rawIndexOf(text: String, collapsed: Int): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        if (count >= collapsed) return i
        if (isSpace(text[i])) {
            while (i < text.length && isSpace(text[i])) i++
        } else {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
        }
        count++
    }
    return text.length
}

/**
 * Follows [pointer] through a chapter's markup. Element names match without case or namespace prefix.
 * When a step can't be found, it settles on the deepest element that was, so a path from a
 * slightly different copy of the book still lands nearby.
 */
fun resolveXPointer(pointer: XPointer, markup: Sequence<Markup>): XPointerTarget {
    class Level(val matched: Boolean) {
        val sameName = HashMap<String, Int>()
        var children = 0
    }

    /** Text directly inside the target element, as a range of its text. */
    class Run(val start: Int, var end: Int, var spaceOnly: Boolean)

    val stack = ArrayList<Level>()
    var bodyDepth = -1
    var hiddenDepth = 0
    // Body text outside the target element, before and after it.
    var chars = 0
    var matched = 0
    var targetDepth = -1
    var targetStart = 0
    var targetClosed = false
    var bestStart = 0
    val selectorParts = ArrayList<String>()
    var selectorOk = true
    var bestSelector: String? = "body"
    val content = StringBuilder()
    val runs = ArrayList<Run>()
    var runOpen = false

    for (piece in markup) {
        when (piece) {
            is Markup.Open -> {
                runOpen = false
                val name = piece.name.substringAfter(':')
                val lower = name.lowercase()
                if (bodyDepth < 0) {
                    stack += Level(false)
                    if (lower == "body" && !targetClosed) {
                        bodyDepth = stack.size
                        if (pointer.steps.isEmpty()) {
                            targetDepth = stack.size
                            targetStart = chars
                        }
                    }
                    continue
                }
                val parent = stack.last()
                var isMatch = false
                if (hiddenDepth == 0) {
                    parent.children++
                    val count = (parent.sameName[lower] ?: 0) + 1
                    parent.sameName[lower] = count
                    // How deep the parent is below the body, which is 0.
                    val depth = stack.size - bodyDepth
                    val parentOnPath = depth == 0 || parent.matched
                    if (targetDepth < 0 && parentOnPath && depth == matched && matched < pointer.steps.size) {
                        val step = pointer.steps[matched]
                        if (step.name.equals(name, ignoreCase = true) && step.index == count) {
                            isMatch = true
                            matched++
                            if (piece.name.contains(':')) selectorOk = false
                            selectorParts += "$name:nth-child(${parent.children})"
                        }
                    }
                }
                if (hiddenDepth > 0 || lower in HIDDEN) hiddenDepth++
                stack += Level(isMatch)
                if (isMatch) {
                    bestStart = chars
                    bestSelector = if (selectorOk) (listOf("body") + selectorParts).joinToString(" > ") else null
                    if (matched == pointer.steps.size) {
                        targetDepth = stack.size
                        targetStart = chars
                    }
                }
            }
            is Markup.Close -> {
                if (stack.isEmpty()) continue
                runOpen = false
                val closing = stack.size
                if (bodyDepth >= 0 && hiddenDepth > 0) hiddenDepth--
                stack.removeAt(stack.lastIndex)
                if (closing == targetDepth) {
                    targetClosed = true
                    targetDepth = -1
                }
                if (closing == bodyDepth) bodyDepth = -1
            }
            is Markup.Text -> {
                if (bodyDepth < 0 || hiddenDepth > 0 || piece.text.isEmpty()) continue
                if (targetDepth < 0) {
                    chars += piece.text.length
                    continue
                }
                val direct = stack.size == targetDepth
                if (direct) {
                    if (!runOpen) runs += Run(content.length, content.length, true)
                    runOpen = true
                }
                content.append(piece.text)
                if (direct) {
                    val run = runs.last()
                    run.end = content.length
                    if (run.spaceOnly && !piece.text.all(::isSpace)) run.spaceOnly = false
                }
            }
        }
    }

    if (!targetClosed && targetDepth < 0) {
        // Only part of the path matched. Land at the start of the deepest element found.
        return XPointerTarget(bestStart, chars.coerceAtLeast(1), bestSelector, "", "", "", exact = false)
    }

    val text = content.toString()
    val total = chars + text.length
    var inElement = 0
    val wanted = pointer.textIndex
    if (wanted != null) {
        // KOReader skips text that's only spaces when it counts, at least between paragraphs, so
        // that count is tried first.
        val written = runs.filter { !it.spaceOnly }
        val run = written.getOrNull(wanted - 1) ?: runs.getOrNull(wanted - 1) ?: written.firstOrNull()
        if (run != null) inElement = run.start + rawIndexOf(text.substring(run.start, run.end), pointer.offset)
    }
    return quoteAt(text, inElement, targetStart, total, bestSelector)
}

private fun quoteAt(text: String, at: Int, elementStart: Int, total: Int, selector: String?): XPointerTarget {
    // Readium finds the spot by its words, so the quote starts at the next letter and stays on whole characters.
    var start = at.coerceIn(0, text.length)
    while (start < text.length && isSpace(text[start])) start++
    if (start in 1 until text.length && text[start].isLowSurrogate()) start--
    val end = safeEnd(text, (start + QUOTE).coerceAtMost(text.length))
    val beforeStart = safeStart(text, (start - QUOTE).coerceAtLeast(0))
    val afterEnd = safeEnd(text, (end + QUOTE).coerceAtMost(text.length))
    return XPointerTarget(
        charOffset = elementStart + start,
        totalChars = total.coerceAtLeast(1),
        cssSelector = selector,
        before = text.substring(beforeStart, start),
        highlight = text.substring(start, end),
        after = text.substring(end, afterEnd),
        exact = true,
    )
}

private const val QUOTE = 40

private fun safeEnd(text: String, end: Int) = if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end - 1 else end
private fun safeStart(text: String, start: Int) = if (start in 1 until text.length && text[start].isLowSurrogate()) start - 1 else start
