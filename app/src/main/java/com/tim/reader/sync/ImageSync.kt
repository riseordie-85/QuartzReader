package com.tim.reader.sync

/**
 * Sync for comics, manga and PDFs, where KOReader saves a page number from 1. The same file
 * pages the same way in both apps, so the number carries over directly.
 */
class ImageSync(
    private val pageCount: Int,
    private val currentPage: () -> Int,
    /** The pages on screen, which can be two side by side. Empty means just the current page. */
    private val shownPages: () -> List<Int> = { emptyList() },
    private val goTo: suspend (Int) -> Unit,
) : SyncReader {
    fun spot(page: Int) = LocalSpot(
        progress = (page + 1).toString(),
        percentage = if (pageCount <= 0) 0.0 else ((page + 1).toDouble() / pageCount).coerceIn(0.0, 1.0),
    )

    override suspend fun compare(remote: RemoteProgress): Comparison {
        if (pageCount <= 0) return Comparison.Same
        val page = remote.progress.trim().toIntOrNull()?.minus(1)
            ?: ((remote.percentage * pageCount).toInt() - 1)
        val target = page.coerceIn(0, pageCount - 1)
        val shown = shownPages().ifEmpty { listOf(currentPage()) }
        return when {
            target > shown.max() -> Comparison.Ahead { goTo(target) }
            target < shown.min() -> Comparison.Behind
            else -> Comparison.Same
        }
    }
}
