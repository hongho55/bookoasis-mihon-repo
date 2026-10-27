package eu.kanade.tachiyomi.source.bookoasis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookOasisDashboardBridgeTest {
    @Test fun keepsStoredSeriesIdentityButReadsUnfilteredDashboardDetail() {
        assertEquals(
            "/api/media/detail?type=adult&library_id=7&series=fixture%20series",
            bookOasisDashboardDetailPath("/app-opds/api/media/detail?type=adult&library_id=7&series=fixture%20series"),
        )
        assertEquals("/app-opds/api/media/books/7/info?type=general", bookOasisDashboardDetailPath("/app-opds/api/media/books/7/info?type=general"))
    }
    @Test fun permitsOnlyConfiguredOriginAndTopFrame() {
        assertTrue(isTrustedBookOasisDashboardMessage("https://books.example.test/root", "https://books.example.test", true))
        assertFalse(isTrustedBookOasisDashboardMessage("https://books.example.test", "https://books.example.test.evil.test", true))
        assertFalse(isTrustedBookOasisDashboardMessage("https://books.example.test", "http://books.example.test", true))
        assertFalse(isTrustedBookOasisDashboardMessage("https://books.example.test", "https://books.example.test:444", true))
        assertFalse(isTrustedBookOasisDashboardMessage("https://books.example.test", "https://books.example.test", false))
        assertFalse(isTrustedBookOasisDashboardMessage("https://books.example.test", "file:///tmp/book.html", true))
        assertFalse(isTrustedBookOasisDashboardMessage("https://user:pass@books.example.test", "https://books.example.test", true))
    }

    @Test fun normalizesDefaultPortsAndSupportsIPv6WithoutWildcardOrigins() {
        assertEquals("https://books.example.test", bookOasisDashboardOrigin("https://books.example.test:443/prefix"))
        assertEquals("http://[2001:db8::1]:5930", bookOasisDashboardOrigin("http://[2001:db8::1]:5930/"))
        assertNull(bookOasisDashboardOrigin("javascript:alert(1)"))
        assertNull(bookOasisDashboardOrigin("content://books/1"))
    }

    @Test fun parsesOnlyExplicitBookActions() {
        assertEquals(
            BookOasisDashboardRequest(BookOasisDashboardAction.Open, 42, "general"),
            parseBookOasisDashboardMessage("""{"action":"open","bookId":42}"""),
        )
        assertEquals(
            BookOasisDashboardRequest(BookOasisDashboardAction.Download, 7, "adult"),
            parseBookOasisDashboardMessage("""{"action":"download","bookId":"7","dbType":"adult"}"""),
        )
    }

    @Test fun rejectsArbitraryActionsInvalidIdsAndNonBookLibraries() {
        for (payload in listOf(
            """{"action":"exec","bookId":1}""",
            """{"action":"open","bookId":-1}""",
            """{"action":"open","bookId":0}""",
            """{"action":"open","bookId":1.5}""",
            """{"action":"open","bookId":true}""",
            """{"action":"open","bookId":"9223372036854775808"}""",
            """{"action":"open","bookId":1,"dbType":"video"}""",
            """{"action":"open","bookId":1,"dbType":"../../private"}""",
            """{"action":"open","bookId":1,"libraryId":"../../private"}""",
            """{"action":"open","bookId":1,"libraryId":"-2"}""",
            "[]",
            "not-json",
            "x".repeat(1025),
        )) {
            assertNull(payload, parseBookOasisDashboardMessage(payload))
        }
    }
}
