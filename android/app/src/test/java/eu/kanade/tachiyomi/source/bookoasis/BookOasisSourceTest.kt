package eu.kanade.tachiyomi.source.bookoasis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookOasisSourceTest {

    @Test
    fun `built in profiles keep extension legacy ids and independent instances`() {
        val sources = (1..3).map(::BookOasisSource)

        assertEquals(BookOasisLegacyIdentity.sourceIds, sources.map { it.id })
        assertTrue(sources.all { it.supportsLatest })
        assertEquals(3, sources.map { it.id }.toSet().size)
    }

    @Test
    fun `configured extension url suffixes normalize to the same root`() {
        assertEquals("http://books.local:5930", BookOasisSource.normalizeServerRoot("http://books.local:5930/"))
        assertEquals(
            "http://books.local:5930",
            BookOasisSource.normalizeServerRoot("http://books.local:5930/app-opds/"),
        )
        assertEquals(
            "http://books.local:5930",
            BookOasisSource.normalizeServerRoot("http://books.local:5930/app-opds-adult"),
        )
        assertEquals("", BookOasisSource.normalizeServerRoot(" "))
    }

    @Test
    fun `comic prefetch uses read only web stream not automatic progress OPDS stream`() {
        assertEquals(
            "https://books.local/api/media/stream?db_type=general&book_id=42&page_idx=3",
            bookOasisReadOnlyPageUrl("https://books.local/", "general", "42", 3),
        )
        assertFalse(bookOasisReadOnlyPageUrl("https://books.local", "adult", "42", 0).contains("/app-opds/"))
    }

    @Test
    fun `authenticated requests require exact same origin`() {
        val server = "https://books.local:5930"

        assertTrue(sameBookOasisOrigin("https://books.local:5930/app-opds/covers/a.jpg", server))
        assertFalse(sameBookOasisOrigin("https://attacker.local/app-opds/covers/a.jpg", server))
        assertFalse(sameBookOasisOrigin("http://books.local:5930/app-opds/covers/a.jpg", server))
        assertFalse(sameBookOasisOrigin("https://books.local:8443/app-opds/covers/a.jpg", server))
        assertFalse(sameBookOasisOrigin("not a URL", server))
    }
}
