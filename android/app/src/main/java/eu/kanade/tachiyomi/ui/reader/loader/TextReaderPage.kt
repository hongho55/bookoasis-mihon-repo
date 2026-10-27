package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle

/**
 * A rendered text page whose visual style can be refreshed cheaply while layout-affecting
 * changes trigger a debounced chapter repagination to rebuild page boundaries.
 */
internal data class TextReaderAnchor(
    val documentIndex: Int,
    val startOffset: Long,
    val endOffsetExclusive: Long,
) {
    fun contains(other: TextReaderAnchor): Boolean {
        if (documentIndex != other.documentIndex) return false
        val point = other.startOffset
        return point in startOffset until endOffsetExclusive
    }
}

internal class TextReaderPage(
    index: Int,
    private val pageTextProvider: () -> String,
    initialStyle: TextReaderStyle,
    val anchor: TextReaderAnchor,
) : ReaderPage(index) {

    @Volatile
    private var style: TextReaderStyle = initialStyle

    @Volatile
    private var sentenceHighlightRange: IntRange? = null

    @Volatile
    private var activeHighlightRange: IntRange? = null

    init {
        stream = {
            TextPageRenderer.render(
                text = pageTextProvider(),
                style = style,
                sentenceHighlightRange = sentenceHighlightRange,
                activeHighlightRange = activeHighlightRange,
            )
        }
        status = Page.State.Ready
    }

    constructor(
        index: Int,
        pageText: String,
        initialStyle: TextReaderStyle,
        anchor: TextReaderAnchor,
    ) : this(index, { pageText }, initialStyle, anchor)

    fun updateStyle(newStyle: TextReaderStyle) {
        style = newStyle
    }

    fun readText(): String = pageTextProvider()

    fun updateTtsHighlight(
        sentenceStart: Int,
        sentenceEndExclusive: Int,
        activeStart: Int,
        activeEndExclusive: Int,
    ) {
        sentenceHighlightRange = if (sentenceStart >= 0 && sentenceEndExclusive > sentenceStart) {
            sentenceStart until sentenceEndExclusive
        } else {
            null
        }
        activeHighlightRange = if (activeStart >= 0 && activeEndExclusive > activeStart) {
            activeStart until activeEndExclusive
        } else {
            null
        }
    }

    fun clearTtsHighlight() {
        sentenceHighlightRange = null
        activeHighlightRange = null
    }
}
