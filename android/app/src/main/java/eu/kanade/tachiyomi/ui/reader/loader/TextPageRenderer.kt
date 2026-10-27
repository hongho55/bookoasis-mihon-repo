package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TextLineBreakMode
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.roundToInt

internal data class PaginatedTextPage(
    val text: String,
    val startOffset: Long,
    val endOffsetExclusive: Long,
)

internal object TextPageRenderer {
    private const val STREAM_BUFFER_CHARS = 64 * 1024
    private const val STREAM_READ_CHARS = 8 * 1024
    private const val ZERO_WIDTH_SPACE = "\u200B"

    fun paginate(text: String, style: TextReaderStyle): List<PaginatedTextPage> {
        if (text.isBlank()) return emptyList()

        return calculatePageSlices(text, style)
            .mapNotNull { slice -> createPage(text, slice, 0L) }
            .ifEmpty {
                listOf(
                    PaginatedTextPage(
                        text = text,
                        startOffset = 0L,
                        endOffsetExclusive = text.length.toLong(),
                    ),
                )
            }
    }

    /**
     * Incrementally paginates a text stream while keeping only a bounded text
     * window in memory. The final incomplete page is retained between windows
     * so normal page filling is preserved across chunk boundaries.
     */
    fun paginate(
        reader: Reader,
        style: TextReaderStyle,
        onPage: (PaginatedTextPage) -> Unit,
    ) {
        val working = StringBuilder(STREAM_BUFFER_CHARS + STREAM_READ_CHARS)
        val chars = CharArray(STREAM_READ_CHARS)
        var firstChunk = true
        var workingStartOffset = 0L

        while (true) {
            val read = reader.read(chars)
            if (read < 0) break
            if (read == 0) continue

            var offset = 0
            if (firstChunk) {
                firstChunk = false
                if (chars[0] == '\uFEFF') {
                    offset = 1
                    workingStartOffset = 1L
                }
            }
            if (offset < read) {
                working.append(chars, offset, read - offset)
            }

            if (working.length >= STREAM_BUFFER_CHARS) {
                val consumed = emitCompletePages(working, style, workingStartOffset, onPage)
                workingStartOffset += consumed
            }
        }

        if (working.isNotEmpty()) {
            val text = working.toString()
            calculatePageSlices(text, style)
                .mapNotNull { slice -> createPage(text, slice, workingStartOffset) }
                .forEach(onPage)
        }
    }

    private fun emitCompletePages(
        working: StringBuilder,
        style: TextReaderStyle,
        baseOffset: Long,
        onPage: (PaginatedTextPage) -> Unit,
    ): Long {
        val text = working.toString()
        val slices = calculatePageSlices(text, style)
        if (slices.size <= 1) return 0L

        for (index in 0 until slices.lastIndex) {
            createPage(text, slices[index], baseOffset)?.let(onPage)
        }

        val consumed = slices.last().start
        working.delete(0, consumed)
        return consumed.toLong()
    }

    private fun createPage(
        text: String,
        slice: PageSlice,
        baseOffset: Long,
    ): PaginatedTextPage? {
        val rawPageText = text.substring(slice.start, slice.endExclusive)
        val leading = rawPageText.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return null
        val trailing = rawPageText.indexOfLast { !it.isWhitespace() }
        val pageText = rawPageText.substring(leading, trailing + 1)
        return PaginatedTextPage(
            text = pageText,
            startOffset = baseOffset + slice.start + leading,
            endOffsetExclusive = baseOffset + slice.start + trailing + 1,
        )
    }

    private fun calculatePageSlices(text: String, style: TextReaderStyle): List<PageSlice> {
        if (text.isEmpty()) return emptyList()

        val metrics = resolveLayoutMetrics(style)
        val builtLayout = createLayout(text, style, metrics.contentWidth)
        val layout = builtLayout.layout
        if (layout.lineCount == 0) return listOf(PageSlice(0, text.length))

        val pages = mutableListOf<PageSlice>()
        var startLine = 0
        while (startLine < layout.lineCount) {
            val startTop = layout.getLineTop(startLine)
            var endLine = startLine
            while (
                endLine + 1 < layout.lineCount &&
                layout.getLineBottom(endLine + 1) - startTop <= metrics.contentHeight
            ) {
                endLine++
            }

            val start = builtLayout.originalOffset(layout.getLineStart(startLine))
            val end = builtLayout.originalOffset(layout.getLineEnd(endLine))
                .coerceAtLeast(start + 1)
                .coerceAtMost(text.length)
            pages += PageSlice(start, end)
            startLine = endLine + 1
        }
        return pages.ifEmpty { listOf(PageSlice(0, text.length)) }
    }

    /** Uses the same measured layout as rendering so host-side tests can check page bounds. */
    internal fun renderedLayoutHeight(text: String, style: TextReaderStyle): Int =
        createLayout(text, style, resolveLayoutMetrics(style).contentWidth).layout.height

    fun render(
        text: String,
        style: TextReaderStyle,
        sentenceHighlightRange: IntRange? = null,
        activeHighlightRange: IntRange? = null,
    ): InputStream {
        val metrics = resolveLayoutMetrics(style)
        val bitmap = Bitmap.createBitmap(metrics.pageWidth, metrics.pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val (backgroundColor, _) = resolveColors(style)
        canvas.drawColor(backgroundColor)
        canvas.save()
        canvas.translate(metrics.horizontalMargin.toFloat(), metrics.verticalMargin.toFloat())
        createLayout(
            text = text,
            style = style,
            contentWidth = metrics.contentWidth,
            sentenceHighlightRange = sentenceHighlightRange,
            activeHighlightRange = activeHighlightRange,
        ).layout.draw(canvas)
        canvas.restore()

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun createLayout(
        text: String,
        style: TextReaderStyle,
        contentWidth: Int,
        sentenceHighlightRange: IntRange? = null,
        activeHighlightRange: IntRange? = null,
    ): BuiltLayout {
        val (_, textColor) = resolveColors(style)
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = textColor
            textSize = style.fontSizePx.coerceAtLeast(1f)
            typeface = style.typeface
        }
        val styledText = addTextSpans(
            text = text,
            style = style,
            sentenceHighlightRange = sentenceHighlightRange,
            activeHighlightRange = activeHighlightRange,
        )
        val preparedText = prepareLineBreakText(text, styledText, style.lineBreakMode)
        val layout = StaticLayout.Builder
            .obtain(preparedText.text, 0, preparedText.text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // Page slices are laid out again when rendered. Keeping font padding
            // here can make the sliced layout taller than the same lines measured
            // during pagination, which clips the last line at larger font sizes.
            .setIncludePad(false)
            .setLineSpacing(0f, style.lineSpacingPercent / 100f)
            .build()
        return BuiltLayout(layout, preparedText.originalOffsetByPreparedOffset)
    }

    private fun prepareLineBreakText(
        originalText: String,
        styledText: CharSequence,
        mode: TextLineBreakMode,
    ): PreparedText {
        if (mode == TextLineBreakMode.WORD || originalText.length < 2) {
            return PreparedText(styledText, null)
        }

        val breakIterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
            setText(originalText)
        }
        val insertionOffsets = mutableListOf<Int>()
        var boundary = breakIterator.first()
        while (true) {
            boundary = breakIterator.next()
            if (boundary == BreakIterator.DONE || boundary >= originalText.length) break
            if (boundary <= 0) continue

            val before = originalText.codePointBefore(boundary)
            val after = originalText.codePointAt(boundary)
            if (!Character.isWhitespace(before) && !Character.isWhitespace(after)) {
                insertionOffsets += boundary
            }
        }
        if (insertionOffsets.isEmpty()) return PreparedText(styledText, null)

        val prepared = SpannableStringBuilder(styledText)
        insertionOffsets.asReversed().forEach { offset ->
            prepared.insert(offset, ZERO_WIDTH_SPACE)
        }

        val insertedAt = BooleanArray(originalText.length + 1)
        insertionOffsets.forEach { insertedAt[it] = true }
        val mapping = IntArray(prepared.length + 1)
        var preparedOffset = 0
        mapping[0] = 0
        for (originalOffset in 0 until originalText.length) {
            if (insertedAt[originalOffset]) {
                preparedOffset++
                mapping[preparedOffset] = originalOffset
            }
            preparedOffset++
            mapping[preparedOffset] = originalOffset + 1
        }
        return PreparedText(prepared, mapping)
    }

    private fun resolveLayoutMetrics(style: TextReaderStyle): LayoutMetrics {
        val pageWidth = style.pageWidthPx.coerceAtLeast(1)
        val pageHeight = style.pageHeightPx.coerceAtLeast(1)
        val requestedHorizontalMargin = (style.horizontalMarginDp * style.density)
            .roundToInt()
            .coerceAtLeast(0)
        val requestedVerticalMargin = (style.verticalMarginDp * style.density).roundToInt().coerceAtLeast(0)
        val horizontalMargin = requestedHorizontalMargin.coerceAtMost(((pageWidth - 1) / 2).coerceAtLeast(0))
        val verticalMargin = requestedVerticalMargin.coerceAtMost(((pageHeight - 1) / 2).coerceAtLeast(0))

        return LayoutMetrics(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            horizontalMargin = horizontalMargin,
            verticalMargin = verticalMargin,
            contentWidth = (pageWidth - horizontalMargin * 2).coerceAtLeast(1),
            contentHeight = (pageHeight - verticalMargin * 2).coerceAtLeast(1),
        )
    }

    private fun resolveColors(style: TextReaderStyle): Pair<Int, Int> {
        val useDark = when (style.readerTheme) {
            0 -> false
            1 -> true
            2 -> true
            3 -> style.automaticDark
            else -> style.automaticDark
        }
        val background = when (style.readerTheme) {
            2 -> Color.rgb(48, 48, 48)
            else -> if (useDark) Color.BLACK else Color.WHITE
        }
        val foreground = if (useDark) Color.WHITE else Color.BLACK
        return background to foreground
    }

    private fun addTextSpans(
        text: String,
        style: TextReaderStyle,
        sentenceHighlightRange: IntRange?,
        activeHighlightRange: IntRange?,
    ): CharSequence {
        val needsParagraphSpacing = style.paragraphSpacingPx > 0 && '\n' in text
        val needsSentenceHighlight = sentenceHighlightRange != null && !sentenceHighlightRange.isEmpty()
        val needsActiveHighlight = activeHighlightRange != null && !activeHighlightRange.isEmpty()
        if (!needsParagraphSpacing && !needsSentenceHighlight && !needsActiveHighlight) return text

        val spannable = SpannableString(text)
        if (needsParagraphSpacing) {
            val fontMetrics = TextPaint().apply {
                textSize = style.fontSizePx.coerceAtLeast(1f)
                typeface = style.typeface
            }.fontMetricsInt
            text.forEachIndexed { index, char ->
                if (char == '\n') {
                    spannable.setSpan(
                        ParagraphSpacingSpan(
                            style.paragraphSpacingPx,
                            fontMetrics.descent,
                            fontMetrics.bottom,
                        ),
                        index,
                        (index + 1).coerceAtMost(text.length),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

        val (_, baseTextColor) = resolveColors(style)
        val darkTheme = baseTextColor == Color.WHITE
        sentenceHighlightRange?.let { range ->
            applyHighlightSpan(
                spannable = spannable,
                textLength = text.length,
                range = range,
                backgroundColor = if (darkTheme) Color.rgb(77, 66, 25) else Color.rgb(255, 243, 176),
                foregroundColor = if (darkTheme) Color.WHITE else Color.BLACK,
            )
        }
        activeHighlightRange?.let { range ->
            applyHighlightSpan(
                spannable = spannable,
                textLength = text.length,
                range = range,
                backgroundColor = if (darkTheme) Color.rgb(255, 193, 7) else Color.rgb(255, 179, 0),
                foregroundColor = Color.BLACK,
            )
        }
        return spannable
    }

    private fun applyHighlightSpan(
        spannable: SpannableString,
        textLength: Int,
        range: IntRange,
        backgroundColor: Int,
        foregroundColor: Int,
    ) {
        if (range.isEmpty()) return
        val start = range.first.coerceIn(0, textLength)
        val endExclusive = (range.last + 1).coerceIn(start, textLength)
        if (start >= endExclusive) return
        spannable.setSpan(
            BackgroundColorSpan(backgroundColor),
            start,
            endExclusive,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        spannable.setSpan(
            ForegroundColorSpan(foregroundColor),
            start,
            endExclusive,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private class ParagraphSpacingSpan(
        private val spacing: Int,
        private val baseDescent: Int,
        private val baseBottom: Int,
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence?,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: Paint.FontMetricsInt,
        ) {
            // StaticLayout can invoke a paragraph span for each wrapped line and reuse metrics.
            // Only the line containing the newline gets the extra gap; never accumulate it.
            val newlineOffset = (text as? android.text.Spanned)?.getSpanStart(this) ?: -1
            val extra = if (newlineOffset >= start && newlineOffset < end) spacing else 0
            fm.descent = baseDescent + extra
            fm.bottom = baseBottom + extra
        }
    }

    private data class PreparedText(
        val text: CharSequence,
        val originalOffsetByPreparedOffset: IntArray?,
    )

    private data class BuiltLayout(
        val layout: StaticLayout,
        val originalOffsetByLayoutOffset: IntArray?,
    ) {
        fun originalOffset(layoutOffset: Int): Int {
            val mapping = originalOffsetByLayoutOffset ?: return layoutOffset
            return mapping[layoutOffset.coerceIn(0, mapping.lastIndex)]
        }
    }

    private data class LayoutMetrics(
        val pageWidth: Int,
        val pageHeight: Int,
        val horizontalMargin: Int,
        val verticalMargin: Int,
        val contentWidth: Int,
        val contentHeight: Int,
    )

    private data class PageSlice(
        val start: Int,
        val endExclusive: Int,
    )
}
