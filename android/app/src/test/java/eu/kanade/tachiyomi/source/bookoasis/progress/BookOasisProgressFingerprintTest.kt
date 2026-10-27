package eu.kanade.tachiyomi.source.bookoasis.progress

import eu.kanade.tachiyomi.ui.reader.loader.TextReaderAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BookOasisProgressFingerprintTest {

    @Test
    fun `fingerprint maps through whitespace changes and different local pagination`() {
        val anchors = listOf(
            TextReaderAnchor(documentIndex = 4, startOffset = 0, endOffsetExclusive = 80),
            TextReaderAnchor(documentIndex = 4, startOffset = 80, endOffsetExclusive = 180),
            TextReaderAnchor(documentIndex = 4, startOffset = 180, endOffsetExclusive = 260),
        )
        val text = listOf(
            "Earlier passage on this page.",
            "Before the saved sentence. The   reader's   precise\nanchor appears here, after repagination.",
            "Later passage.",
        )

        assertEquals(
            1,
            resolveBookOasisPage(
                serverProgress(documentIndex = 4, fingerprint = "The reader's precise anchor appears here"),
                format = "epub",
                anchors = anchors,
                textForPage = text::get,
            ),
        )
    }

    @Test
    fun `fingerprint split at a local page boundary maps to the page where it starts`() {
        val anchors = listOf(
            TextReaderAnchor(documentIndex = 1, startOffset = 0, endOffsetExclusive = 40),
            TextReaderAnchor(documentIndex = 1, startOffset = 40, endOffsetExclusive = 100),
        )
        val text = listOf("The sentence strad", "dles page boundary across two pages.")

        assertEquals(
            0,
            resolveBookOasisPage(
                serverProgress(documentIndex = 1, fingerprint = "sentence straddles page boundary"),
                format = "epub",
                anchors = anchors,
                textForPage = text::get,
            ),
        )
    }

    @Test
    fun `duplicate fingerprint is searched only in saved spine and scans that spine for ambiguity`() {
        val anchors = listOf(
            TextReaderAnchor(documentIndex = 2, startOffset = 0, endOffsetExclusive = 100),
            TextReaderAnchor(documentIndex = 2, startOffset = 100, endOffsetExclusive = 200),
            TextReaderAnchor(documentIndex = 8, startOffset = 0, endOffsetExclusive = 100),
            TextReaderAnchor(documentIndex = 8, startOffset = 100, endOffsetExclusive = 200),
        )
        val reads = mutableListOf<Int>()
        val text = mapOf(
            0 to "The repeated anchor appears in another chapter.",
            1 to "More text from the other chapter.",
            2 to "The repeated anchor appears here in the saved chapter.",
            3 to "More text from the saved chapter.",
        )

        assertEquals(
            2,
            resolveBookOasisPage(
                serverProgress(documentIndex = 8, fingerprint = "The repeated anchor appears"),
                format = "epub",
                anchors = anchors,
                textForPage = { pageIndex ->
                    reads += pageIndex
                    text.getValue(pageIndex)
                },
            ),
        )
        assertEquals(listOf(2, 3), reads)
    }

    @Test
    fun `unmatched EPUB anchor refuses an arbitrary percentage jump`() {
        val anchors = List(10) {
            TextReaderAnchor(
                documentIndex = 5,
                startOffset = it * 100L,
                endOffsetExclusive =
                (it + 1) * 100L,
            )
        }

        assertEquals(
            null,
            resolveBookOasisPage(
                serverProgress(documentIndex = 5, percent = 50, fingerprint = "not present in the chapter"),
                format = "epub",
                anchors = anchors,
                textForPage = { "Unrelated page $it." },
            ),
        )
    }

    @Test
    fun `epub fingerprint takes precedence and does not use native offset`() {
        val anchors = listOf(
            TextReaderAnchor(documentIndex = 3, startOffset = 0, endOffsetExclusive = 100),
            TextReaderAnchor(documentIndex = 3, startOffset = 100, endOffsetExclusive = 200),
        )

        assertEquals(
            0,
            resolveBookOasisPage(
                serverProgress(
                    documentIndex = 3,
                    startOffset = 150,
                    fingerprint = "This phrase is only on page zero",
                ),
                format = "epub",
                anchors = anchors,
                textForPage = { if (it == 0) "This phrase is only on page zero." else "Unrelated text page $it." },
            ),
        )
    }

    @Test
    fun `position raw fingerprint matches native decoded text before percentage fallback`() {
        // Reader Bridge position.fingerprint is the raw decoded text, not an HTML-escaped state value.
        val server = serverProgress(
            documentIndex = 0,
            percent = 40,
            fingerprint = "Page six is A&B marker",
        )
        val anchors = List(100) {
            TextReaderAnchor(documentIndex = 0, startOffset = it * 100L, endOffsetExclusive = (it + 1) * 100L)
        }
        val text = List(100) { "Unrelated local EPUB page $it." }.toMutableList().apply {
            this[5] = "Page six is A&B marker at the saved position."
        }

        assertEquals(
            5,
            resolveBookOasisPage(
                server = server,
                format = "epub",
                anchors = anchors,
                textForPage = text::get,
            ),
        )
    }

    @Test
    fun `repeated EPUB sentence across pages is ambiguous and stays unrestored`() {
        val anchors = listOf(
            TextReaderAnchor(3, 0, 100),
            TextReaderAnchor(3, 100, 200),
        )
        val repeated = "This repeated native sentence appears in every chapter page"
        assertEquals(
            null,
            resolveBookOasisPage(
                serverProgress(documentIndex = 3, percent = 80, fingerprint = repeated),
                format = "epub",
                anchors = anchors,
                textForPage = { repeated },
            ),
        )
    }

    @Test
    fun `TXT repeated anchor is disambiguated only by an exact canonical range`() {
        val anchors = listOf(TextReaderAnchor(0, 0, 100), TextReaderAnchor(0, 100, 200))
        val repeated = "This repeated native sentence appears in every text page"
        assertEquals(
            1,
            resolveBookOasisPage(
                serverProgress(documentIndex = 0, startOffset = 150, fingerprint = repeated),
                format = "txt",
                anchors = anchors,
                textForPage = { repeated },
            ),
        )
    }

    private fun serverProgress(
        documentIndex: Int? = null,
        startOffset: Long? = null,
        percent: Int? = null,
        fingerprint: String? = null,
    ) = BookOasisServerProgress(
        revision = "stock1:test",
        accountScope = "local1:test",
        pagesRead = 0,
        documentIndex = documentIndex,
        startOffset = startOffset,
        percent = percent,
        fingerprint = fingerprint,
    )
}
