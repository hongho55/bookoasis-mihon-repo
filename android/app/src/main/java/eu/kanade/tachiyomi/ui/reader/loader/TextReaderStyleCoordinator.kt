package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

internal fun readingStartAnchor(anchor: TextReaderAnchor): TextReaderAnchor = TextReaderAnchor(
    documentIndex = anchor.documentIndex,
    startOffset = anchor.startOffset,
    endOffsetExclusive = anchor.startOffset + 1L,
)

/**
 * Keeps text-style preview and anchor-preserving repagination outside ReaderViewModel.
 */
internal class TextReaderStyleCoordinator(
    private val context: Context,
    private val preferences: ReaderPreferences,
) {
    fun preview(chapter: ReaderChapter): Boolean {
        if (chapter.pageLoader?.usesTextReaderStyle != true) return false
        val pages = chapter.pages?.filterIsInstance<TextReaderPage>().orEmpty()
        if (pages.isEmpty()) return false

        val style = preferences.textReaderStyle(context)
        pages.forEach { it.updateStyle(style) }
        return true
    }

    suspend fun repaginate(
        chapter: ReaderChapter,
        chapterLoader: ChapterLoader,
    ): PageLoader? {
        if (chapter.pageLoader?.usesTextReaderStyle != true) return null
        if (chapter.pages?.any { it is TextReaderPage } != true) return null

        val oldPageCount = chapter.pages?.size?.coerceAtLeast(1) ?: 1
        val requestedPage = chapter.requestedPage.coerceIn(0, oldPageCount - 1)
        val currentPage = chapter.pages?.getOrNull(requestedPage) as? TextReaderPage
        val readingAnchor = currentPage?.anchor?.let(::readingStartAnchor)
        val readingProgress = ((requestedPage + 0.5f) / oldPageCount)
            .coerceIn(0f, 0.999999f)

        return chapterLoader.reloadTextChapter(
            chapter = chapter,
            readingAnchor = readingAnchor,
            readingProgress = readingProgress,
        )
    }
}
