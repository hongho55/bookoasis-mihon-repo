package eu.kanade.tachiyomi.ui.reader.tts

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.loader.TextReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the bridge between the Mihon reader UI and the standalone TTS service.
 */
internal class ReaderTtsCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: ReaderPreferences,
    private val moveToPageIndex: (Int) -> Unit,
    private val refreshCurrentPage: () -> Unit,
) {
    private var highlightedPage: TextReaderPage? = null

    fun toggle(
        chapter: ReaderChapter?,
        currentPage: Int,
        mangaTitle: String?,
    ) {
        chapter ?: return
        val chapterId = chapter.chapter.id ?: return
        val state = ReaderTtsController.state.value

        if (state.active && state.readerChapterId == chapterId) {
            if (state.playing) {
                ReaderTtsController.toggle(context)
            } else {
                val page = chapter.pages
                    ?.getOrNull((currentPage - 1).coerceAtLeast(0)) as? TextReaderPage
                if (page != null) {
                    ReaderTtsController.resumeFromSource(
                        context = context,
                        documentIndex = page.anchor.documentIndex,
                        sourceOffset = page.anchor.startOffset,
                    )
                } else {
                    ReaderTtsController.toggle(context)
                }
            }
            return
        }

        val textPages = chapter.pages?.filterIsInstance<TextReaderPage>().orEmpty()
        if (textPages.isEmpty()) return

        val startPageIndex = (currentPage - 1).coerceAtLeast(0)
        val title = mangaTitle ?: chapter.chapter.name
        val subtitle = chapter.chapter.name
        val speed = preferences.textTtsSpeedStep.get().coerceIn(6, 15) / 5f
        val enginePackage = preferences.textTtsEnginePackage.get()

        scope.launchIO {
            val session = runCatching {
                ReaderTtsSessionStore.create(context, textPages)
            }.getOrElse { error ->
                logcat(LogPriority.ERROR, error) { "Failed to create TTS text session" }
                return@launchIO
            }
            ReaderTtsController.start(
                context = context,
                sessionId = session.id,
                startReaderPageIndex = startPageIndex,
                readerChapterId = chapterId,
                title = title,
                subtitle = subtitle,
                speed = speed,
                enginePackage = enginePackage,
            )
        }
    }

    fun applyState(
        state: ReaderTtsController.State,
        chapter: ReaderChapter?,
        currentPage: Int,
    ) {
        val chapterId = chapter?.chapter?.id
        if (!state.active || chapter == null || chapterId != state.readerChapterId) {
            clearHighlight()
            return
        }

        val textPages = chapter.pages?.filterIsInstance<TextReaderPage>().orEmpty()
        if (textPages.isEmpty()) return

        val targetSourceOffset = when {
            state.sourceHighlightStart >= 0L -> state.sourceHighlightStart
            state.sourceSentenceStart >= 0L -> state.sourceSentenceStart
            else -> -1L
        }
        val targetPage = if (targetSourceOffset >= 0L) {
            textPages.firstOrNull { page ->
                page.anchor.documentIndex == state.documentIndex &&
                    targetSourceOffset >= page.anchor.startOffset &&
                    targetSourceOffset < page.anchor.endOffsetExclusive
            }
        } else {
            chapter.pages?.getOrNull(state.readerPageIndex) as? TextReaderPage
        } ?: return

        if (highlightedPage !== targetPage) {
            highlightedPage?.clearTtsHighlight()
        }

        val sentenceStart = maxOf(state.sourceSentenceStart, targetPage.anchor.startOffset)
        val sentenceEnd = minOf(state.sourceSentenceEndExclusive, targetPage.anchor.endOffsetExclusive)
        val activeStart = maxOf(state.sourceHighlightStart, targetPage.anchor.startOffset)
        val activeEnd = minOf(state.sourceHighlightEndExclusive, targetPage.anchor.endOffsetExclusive)

        if (
            state.sourceSentenceStart >= 0L &&
            state.sourceSentenceEndExclusive > state.sourceSentenceStart &&
            sentenceEnd > sentenceStart
        ) {
            targetPage.updateTtsHighlight(
                sentenceStart = (sentenceStart - targetPage.anchor.startOffset).toInt(),
                sentenceEndExclusive = (sentenceEnd - targetPage.anchor.startOffset).toInt(),
                activeStart = if (activeEnd > activeStart) {
                    (activeStart - targetPage.anchor.startOffset).toInt()
                } else {
                    -1
                },
                activeEndExclusive = if (activeEnd > activeStart) {
                    (activeEnd - targetPage.anchor.startOffset).toInt()
                } else {
                    -1
                },
            )
        } else {
            targetPage.clearTtsHighlight()
        }

        highlightedPage = targetPage
        val targetPageIsVisible = currentPage == targetPage.index + 1
        if (state.playing && !targetPageIsVisible) {
            moveToPageIndex(targetPage.index)
        }
        if (state.playing || targetPageIsVisible) {
            refreshCurrentPage()
        }
    }

    private fun clearHighlight() {
        if (highlightedPage == null) return
        highlightedPage?.clearTtsHighlight()
        highlightedPage = null
        refreshCurrentPage()
    }
}
