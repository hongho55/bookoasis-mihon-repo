package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.Typeface
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TextFontFamily
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TextLineBreakMode
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextPageRendererLayoutTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `warm extracted text cache preserves pages and reports cold warm timings`() {
        val html = "<html><body>" + List(120) { "<p>문단 $it — 바른 일이라 생각하지 않아요. 여러 문장을 포함하는 EPUB 본문입니다.</p>" }.joinToString("") + "</body></html>"
        val cache = EpubExtractedTextCache(temporaryFolder.root, "timing-fixture-v1")
        val style = TextReaderStyle(
            fontSize = 32, lineSpacingPercent = 115, paragraphSpacing = 12,
            horizontalMarginDp = 24, verticalMarginDp = 24,
            lineBreakMode = TextLineBreakMode.WORD, fontFamily = TextFontFamily.SYSTEM,
            typeface = Typeface.DEFAULT, readerTheme = 1, automaticDark = false,
            pageWidthPx = 720, pageHeightPx = 1280, density = 1f,
            fontSizePx = 32f, paragraphSpacingPx = 12,
        )
        var coldPages = 0
        var extracted = ""
        val coldExtractionNanos = measureNanoTime {
            val doc = Jsoup.parse(html)
            doc.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote").append("\n")
            extracted = doc.body().wholeText().trim()
            cache.writer().use { writer ->
                writer.append(0, extracted)
                writer.finish()
            }
        }
        val coldPaginationNanos = measureNanoTime { coldPages = TextPageRenderer.paginate(extracted, style).size }
        var warmPages = 0
        var warmExtracted = ""
        val warmExtractionNanos = measureNanoTime {
            cache.forEachDocument { _, text -> warmExtracted = text }
        }
        val warmPaginationNanos = measureNanoTime {
            warmPages = TextPageRenderer.paginate(warmExtracted, style).size
        }
        assertEquals(coldPages, warmPages)
        println("EPUB timing fixture (120 paragraphs, host/Robolectric): cold extract+cache=${coldExtractionNanos / 1_000_000}ms, warm cache read=${warmExtractionNanos / 1_000_000}ms, cold pagination=${coldPaginationNanos / 1_000_000}ms, warm pagination=${warmPaginationNanos / 1_000_000}ms, pages=$warmPages")
    }

    @Test
    fun `paginated Korean pages render within the text viewport`() {
        val paragraph = "바른 일이라 생각하지 않아요. 그래서 사연 듣고 도와드리려는 거고요. " +
            "그러면서 박미형 씨는 자신 또한 각성자라고 했다. 같은 각성자로서 억울한 처지에 놓인 내 여동생을 최대한 도울 것이라고도 말했다. " +
            "이후로 내 일과의 대부분은 인권단체와 함께였다. 정말이지 별짓을 다 했다."
        val document = List(18) { paragraph }.joinToString("\n\n")
        val cases = listOf(
            Triple(24f, 1f, 0),
            Triple(32f, 1.15f, 12),
            Triple(40f, 1.3f, 24),
            Triple(32f, 1f, 0),
        )

        cases.forEachIndexed { index, (fontSizePx, lineSpacing, paragraphSpacingPx) ->
            val style = TextReaderStyle(
                fontSize = fontSizePx.toInt(),
                lineSpacingPercent = (lineSpacing * 100).toInt(),
                paragraphSpacing = paragraphSpacingPx,
                horizontalMarginDp = 32,
                verticalMarginDp = 36,
                lineBreakMode = if (index == 3) {
                    TextLineBreakMode.CHARACTER
                } else {
                    TextLineBreakMode.WORD
                },
                fontFamily = if (index == 2) TextFontFamily.SERIF else TextFontFamily.SYSTEM,
                typeface = if (index == 2) {
                    Typeface.create("serif", Typeface.NORMAL)
                } else {
                    Typeface.DEFAULT
                },
                readerTheme = 1,
                automaticDark = false,
                pageWidthPx = 720,
                pageHeightPx = 1280,
                density = 1f,
                fontSizePx = fontSizePx,
                paragraphSpacingPx = paragraphSpacingPx,
            )
            val pages = TextPageRenderer.paginate(document, style)
            assertTrue("case $index should span multiple pages", pages.size > 1)
            assertTrue(
                "case $index should preserve all source text",
                pages.joinToString("") { it.text }.length > document.length - 50,
            )

            pages.dropLast(1).forEachIndexed { pageIndex, page ->
                val renderedLayoutHeight = TextPageRenderer.renderedLayoutHeight(page.text, style)
                assertTrue(
                    "case $index page $pageIndex layout exceeds its paginated viewport: height=$renderedLayoutHeight",
                    renderedLayoutHeight <= style.pageHeightPx - 2 * style.verticalMarginDp,
                )
            }
        }
    }
}
