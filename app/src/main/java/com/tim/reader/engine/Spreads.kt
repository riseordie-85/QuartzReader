package com.tim.reader.engine

/** What one screen shows: one page, or two side by side. */
data class Spread(val pages: List<Int>)

/** Pages at least this much wider than tall are treated as full spreads. Near-square pages pair up. */
private const val WIDE_RATIO = 1.1f

fun isWidePage(size: PageSize) = size.width > size.height * WIDE_RATIO

/**
 * Lays pages out for the reader. In two-page view the cover shows alone and wide pages take the
 * whole screen. Shifting by one fixes books whose spreads come out misaligned.
 */
fun buildSpreads(count: Int, twoPage: Boolean, shift: Boolean, widePages: Set<Int>): List<Spread> {
    if (!twoPage) return List(count) { Spread(listOf(it)) }
    val spreads = ArrayList<Spread>()
    var page = 0
    if (!shift && count > 0) {
        spreads += Spread(listOf(0))
        page = 1
    }
    while (page < count) {
        if (page in widePages || page + 1 >= count || page + 1 in widePages) {
            spreads += Spread(listOf(page))
            page += 1
        } else {
            spreads += Spread(listOf(page, page + 1))
            page += 2
        }
    }
    return spreads
}

fun spreadIndexOf(spreads: List<Spread>, page: Int): Int =
    spreads.indexOfFirst { page in it.pages }.coerceAtLeast(0)
