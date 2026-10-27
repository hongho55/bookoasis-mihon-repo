package eu.kanade.tachiyomi.source.bookoasis.progress

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookOasisProgressWireFormatTest {
    @Test fun `server reading time is converted to device clock without using fetch time as a new read`() {
        val progress = JSONObject("""{"success":true,"state":{"pages_read":1}}""")
        val tts =
            JSONObject(
                """{"success":true,"server_now_ms":3000,"state":{"latest":"read","read":{"chapter_idx":0,"anchor":"A saved paragraph","updated_ms":2000}}}""",
            )
        val snapshot = BookOasisProgressApi.parseSnapshot(progress, tts, "txt", "local1:test", receivedAt = 8000)
        assertEquals(7000L, snapshot.updatedAt)
        assertEquals("A saved paragraph", snapshot.fingerprint)
    }

    @Test fun `coarse formats use their recorded progress time rather than refresh time`() {
        val progress = JSONObject("""{"success":true,"state":{"pages_read":4,"last_read_at":"2026-10-27T10:00:00Z"}}""")
        val parsed = BookOasisProgressApi.parseSnapshot(
            progress,
            null,
            "pdf",
            "local1:test",
            receivedAt = 9_999_999_999_999,
        )
        assertEquals(java.time.Instant.parse("2026-10-27T10:00:00Z").toEpochMilli(), parsed.updatedAt)
    }

    @Test
    fun `stock paths are same-origin direct APIs and never plugin RPC`() {
        val root = "https://books.example/app-opds-adult/"
        val paths = listOf(
            "api/media/books/701/reader-info",
            "api/media/progress-state",
            "api/media/progress",
            "api/media/tts/position",
            "api/media/txt",
            "api/media/epub/chapter",
        ).map { BookOasisProgressApi.endpoint(root, it) }
        assertEquals(
            listOf(
                "https://books.example/api/media/books/701/reader-info",
                "https://books.example/api/media/progress-state",
                "https://books.example/api/media/progress",
                "https://books.example/api/media/tts/position",
                "https://books.example/api/media/txt",
                "https://books.example/api/media/epub/chapter",
            ),
            paths,
        )
        assertTrue(paths.none { "plugins/action" in it })
    }

    @Test
    fun `account binding is local and changes when username changes`() {
        val first = BookOasisProgressApi.localAccountBinding("https://books.example", "reader-one")
        val second = BookOasisProgressApi.localAccountBinding("https://books.example", "reader-two")
        assertTrue(first!!.startsWith("sha256:"))
        assertNotEquals(first, second)
        assertFalse(first.contains("reader-one"))
    }

    @Test
    fun `snapshot chooses newest read or listen pointer and hashes stock state`() {
        val progress = JSONObject(
            """{"success":true,"state":{"pages_read":17,"total_pages":100,"epub_session":{"index":2,"percent":0,"fingerprint":"old &amp; pointer","updatedAt":"2025-01-01 00:00:00"}}}""",
        )
        val tts = JSONObject(
            """{"success":true,"server_now_ms":9999999999999,"state":{"latest":"listen","read":{"chapter_idx":3,"char_offset":500,"text_len":8000,"anchor":"read raw & text","updated_ms":1800000000020},"listen":{"chapter_idx":4,"char_offset":1234,"text_len":8000,"anchor":"listen raw & text","updated_ms":1800000000030},"legacy":null}}""",
        )
        val parsed = BookOasisProgressApi.parseSnapshot(progress, tts, "txt", "local1:abc")
        assertEquals("stock1:", parsed.revision.take(7))
        assertEquals("local1:abc", parsed.accountScope)
        assertEquals(17, parsed.pagesRead)
        assertEquals(4, parsed.documentIndex)
        assertEquals(1234L, parsed.startOffset)
        assertEquals("listen raw & text", parsed.fingerprint)
        assertEquals("listen", parsed.positionKind)
        assertTrue(parsed.fingerprintIsRaw)

        val reorderedProgress = JSONObject(
            """{"state":{"epub_session":{"updatedAt":"2025-01-01 00:00:00","fingerprint":"old &amp; pointer","percent":0,"index":2},"total_pages":100,"pages_read":17},"success":true}""",
        )
        val reorderedTts = JSONObject(
            """{"state":{"legacy":null,"listen":{"updated_ms":1800000000030,"anchor":"listen raw & text","text_len":8000,"char_offset":1234,"chapter_idx":4},"read":{"updated_ms":1800000000020,"anchor":"read raw & text","text_len":8000,"char_offset":500,"chapter_idx":3},"latest":"listen"},"server_now_ms":1,"success":true}""",
        )
        assertEquals(
            parsed.revision,
            BookOasisProgressApi.parseSnapshot(reorderedProgress, reorderedTts, "txt", "local1:abc").revision,
        )
    }

    @Test
    fun `a newer legacy EPUB pointer wins over an older fine TTS pointer`() {
        val progress = JSONObject(
            """{"success":true,"state":{"pages_read":8,"total_pages":100,"epub_session":{"index":6,"percent":8,"fingerprint":"Legacy &amp; decoded anchor","updatedAt":"2999-01-01 00:00:00"}}}""",
        )
        val tts = JSONObject(
            """{"success":true,"state":{"latest":"read","read":{"chapter_idx":3,"char_offset":7,"text_len":100,"anchor":"older fine anchor","updated_ms":1}}}""",
        )
        val parsed = BookOasisProgressApi.parseSnapshot(progress, tts, "epub", "local1:abc")
        assertEquals("legacy", parsed.positionKind)
        assertEquals(6, parsed.documentIndex)
        assertNull(parsed.startOffset)
        assertEquals("Legacy & decoded anchor", parsed.fingerprint)
        assertTrue(parsed.fingerprintIsRaw)
    }

    @Test
    fun `legacy literal entity anchor is decoded once and remains raw`() {
        val progress = JSONObject(
            """{"success":true,"state":{"pages_read":1,"total_pages":10,"epub_session":{"index":0,"fingerprint":"Literal &amp;amp; is meaningful text","updatedAt":null}}}""",
        )
        val parsed = BookOasisProgressApi.parseSnapshot(progress, null, "txt", "local1:test")
        assertEquals("Literal &amp; is meaningful text", parsed.fingerprint)
        assertTrue(parsed.fingerprintIsRaw)
    }

    @Test
    fun `EPUB half percentage follows plugin ties to even rounding`() {
        val item = pending("epub", "A raw chapter anchor", 0).copy(pageIndex = 0, totalPages = 8)
        val coarse = JSONObject(
            """{"pages_read":12,"total_pages":100,"epub_session":{"index":0,"percent":0,"fingerprint":"A raw chapter anchor"}}""",
        )
        val fine = JSONObject(
            """{"latest":"read","read":{"chapter_idx":0,"char_offset":0,"text_len":0,"anchor":"A raw chapter anchor","updated_ms":4}}""",
        )
        BookOasisProgressApi.validateAcknowledgedPosition(item, "epub", coarse, fine)
    }

    @Test
    fun `stock TXT chunk mapping follows JS UTF16 slicing and newline lookahead`() {
        val emojiText = "x".repeat(3999) + "😀" + "z".repeat(4000)
        assertEquals(listOf(0, 4000, 8000), BookOasisProgressApi.webTxtChunks(emojiText))
        assertEquals(0xDE00.toChar(), emojiText[4000])

        val newlineText = "x".repeat(4001) + "\n" + "end"
        assertEquals(listOf(0, 4002), BookOasisProgressApi.webTxtChunks(newlineText))
        assertEquals(listOf(0), BookOasisProgressApi.webTxtChunks("\uFEFFshort"))
    }

    @Test
    fun `TXT anchor mapping handles entities emoji and repeated anchors conservatively`() {
        val anchor = "A&B <escaped> 😀 saved sentence"
        val text = "Intro 😀\n$anchor\nMiddle\n$anchor\nEnd"
        val secondOffset = text.lastIndexOf(anchor).toLong()
        assertEquals(secondOffset, BookOasisProgressApi.mapTxtAnchor(text, secondOffset, anchor))
        assertEquals(
            anchor,
            BookOasisProgressApi.escapeWebHtml(anchor).let {
                org.jsoup.parser.Parser.unescapeEntities(it, false)
            },
        )
        try {
            BookOasisProgressApi.mapTxtAnchor(
                "$anchor\n${"z".repeat(20_001)}\n$anchor",
                ("$anchor\n${"z".repeat(20_001)}\n$anchor".lastIndexOf(anchor) / 2).toLong(),
                anchor,
            )
            fail("equidistant repeated anchors must not be guessed")
        } catch (error: BookOasisProgressException) {
            assertEquals("INVALID", error.code)
        }
        try {
            BookOasisProgressApi.mapTxtAnchor("unrelated text", 0, anchor)
            fail("missing text anchor must not be mapped")
        } catch (error: BookOasisProgressException) {
            assertEquals("NOT_PERSISTED", error.code)
        }
    }

    @Test
    fun `EPUB HTML entities decode for unique raw anchor validation and duplicates fail closed`() {
        val raw = "Unique & decoded <anchor> sentence"
        assertTrue(
            BookOasisProgressApi.epubWebAnchorIsUnique("<p>Unique &amp; decoded &lt;anchor&gt; sentence</p>", raw),
        )
        assertFalse(
            BookOasisProgressApi.epubWebAnchorIsUnique(
                "<p>$raw one</p><p>$raw two</p>",
                raw,
            ),
        )
        assertEquals(
            "A&amp;B &lt;x&gt; &quot;q&quot; &#039;s&#039;",
            BookOasisProgressApi.escapeWebHtml("A&B <x> \"q\" 's'"),
        )
    }

    @Test
    fun `stock ACK validation rejects mismatching TTS state so caller retains outbox`() {
        val item = pending(format = "epub", fingerprint = "A raw chapter anchor", documentIndex = 0)
        val coarse = JSONObject(
            """{"pages_read":6,"total_pages":100,"epub_session":{"index":0,"percent":0,"fingerprint":"A raw chapter anchor"}}""",
        )
        val matchingTts = JSONObject(
            """{"latest":"read","read":{"chapter_idx":0,"char_offset":0,"text_len":0,"anchor":"A raw chapter anchor","updated_ms":4}}""",
        )
        BookOasisProgressApi.validateAcknowledgedPosition(item, "epub", coarse, matchingTts)

        val mismatch = JSONObject(matchingTts.toString())
            .put("read", JSONObject(matchingTts.getJSONObject("read").toString()).put("anchor", "different anchor"))
        try {
            BookOasisProgressApi.validateAcknowledgedPosition(item, "epub", coarse, mismatch)
            fail("mismatching read-back cannot acknowledge or delete the outbox")
        } catch (error: BookOasisProgressException) {
            assertEquals("NOT_PERSISTED", error.code)
        }
    }

    private fun pending(
        format: String,
        fingerprint: String,
        documentIndex: Int,
    ) = BookOasisPendingProgress(
        sourceId = 7,
        bookId = 42,
        dbType = "general",
        format = format,
        pageIndex = 5,
        totalPages = 100,
        documentIndex = documentIndex,
        startOffset = if (format == "txt") 200L else null,
        fingerprint = fingerprint,
        updatedAt = 1,
        accountBinding = "sha256:local",
    )
}
