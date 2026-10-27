package eu.kanade.tachiyomi.source.bookoasis.progress

import eu.kanade.tachiyomi.ui.reader.loader.TextReaderAnchor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BookOasisProgressDecisionTest {

    @Test
    fun `native fingerprint keeps decoded text without HTML escaping or whitespace rewriting`() {
        val fingerprint = bookOasisFingerprint("\n  A  & B < C > marker\n" + "x".repeat(100))
        assertEquals("A  & B < C > marker", fingerprint)
    }

    @Test
    fun `newer server position is selected even when it is an intentional rewind`() {
        assertEquals(BookOasisConflictDecision.PullServer, decideBookOasisProgress(100, 200))
    }

    @Test
    fun `first sync pushes local progress`() {
        assertEquals(
            BookOasisConflictDecision.PushLocal,
            decideBookOasisProgress(localUpdatedAt = 100, serverUpdatedAt = 0),
        )
    }

    @Test
    fun `newer local reading wins regardless of changed snapshot revision`() {
        assertEquals(
            BookOasisConflictDecision.PushLocal,
            decideBookOasisProgress(localUpdatedAt = 200, serverUpdatedAt = 100),
        )
    }

    @Test
    fun `equal reading times keep current local position without upload`() {
        assertEquals(
            BookOasisConflictDecision.KeepLocal,
            decideBookOasisProgress(localUpdatedAt = 200, serverUpdatedAt = 200),
        )
        assertEquals(BookOasisConflictDecision.KeepLocal, decideBookOasisProgress(0, 0))
    }

    @Test
    fun `direct adapter uses root stock API paths`() {
        assertEquals(
            "https://books.example/api/media/progress-state",
            BookOasisProgressApi.endpoint("https://books.example/app-opds/", "api/media/progress-state"),
        )
    }

    @Test
    fun `TXT raw fingerprint takes precedence over offset after repagination`() {
        val anchors = listOf(
            TextReaderAnchor(0, 0, 100),
            TextReaderAnchor(0, 100, 250),
            TextReaderAnchor(0, 250, 400),
        )
        val pages = listOf("Beginning", "Unique saved sentence across repagination", "Ending")
        assertEquals(
            1,
            resolveBookOasisPage(
                serverProgress(documentIndex = 0, startOffset = 20, fingerprint = "Unique saved sentence"),
                format = "txt",
                anchors = anchors,
                textForPage = pages::get,
            ),
        )
    }

    @Test
    fun `TXT UTF16 offset restores when no position fingerprint matches`() {
        val pages = listOf(
            TextReaderAnchor(0, 0, 100),
            TextReaderAnchor(0, 100, 250),
            TextReaderAnchor(0, 250, 400),
        )
        assertEquals(
            1,
            resolveBookOasisPage(
                serverProgress(documentIndex = 0, startOffset = 175),
                format = "txt",
                anchors = pages,
            ),
        )
    }

    @Test
    fun `EPUB ignores offsets and retains normalized percentage fallback`() {
        assertEquals(
            4,
            resolveBookOasisPage(
                serverProgress(documentIndex = 0, startOffset = 999, percent = 50),
                format = "epub",
                anchors = List(10) { null },
            ),
        )
    }

    private fun serverProgress(
        documentIndex: Int? = null,
        startOffset: Long? = null,
        percent: Int? = null,
        fingerprint: String? = null,
        fingerprintIsRaw: Boolean = true,
    ) = BookOasisServerProgress(
        revision = "stock1:test",
        accountScope = "test-account",
        pagesRead = 0,
        documentIndex = documentIndex,
        startOffset = startOffset,
        percent = percent,
        fingerprint = fingerprint,
        fingerprintIsRaw = fingerprintIsRaw,
    )
}
