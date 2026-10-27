package eu.kanade.tachiyomi.source.bookoasis

import eu.kanade.tachiyomi.source.bookoasis.progress.BookOasisPendingProgress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookOasisHomeDataTest {
    private val server = "https://books.example:5930"
    private fun book(id: Long = 7, format: String = "epub", read: Int = 11, total: Int = 100) = JSONObject()
        .put("id", id).put("title", "Fixture volume $id").put("series_name", "Fixture series")
        .put("library_id", 2).put("file_format", format).put("pages_read", read).put("total_pages", total)
    private fun snapshot(
        recent: JSONArray = JSONArray().put(book()),
        insights: JSONObject? = null,
        layout: JSONObject? = null,
        newBooks: JSONArray = JSONArray(),
        dbType: String = "general",
    ) = parseBookOasisHomeSnapshot(
        server,
        dbType,
        JSONObject().put("books", recent),
        JSONObject().put("books", newBooks),
        insights,
        null,
        layout,
        fetchedAt = 1234,
    )
    private fun pending(id: Long = 7, source: Long = 42, type: String = "general", page: Int = 3, total: Int = 28) =
        BookOasisPendingProgress(source, id, type, "epub", page, total, updatedAt = 2000)

    @Test fun parsesRealDashboardReadingHistoryAndDoesNotCallEpubPercentPages() {
        val value = snapshot().recentBooks.single()
        assertEquals(7L, value.bookId)
        assertEquals("2", value.libraryId)
        assertEquals("11%", value.positionLabel)
        assertEquals(11, value.progressPercent)
        assertFalse(value.positionLabel.contains("100"))
        assertEquals(value, snapshot().currentlyReading.single())
    }

    @Test fun epubHistoryStoresPercentageEvenWhenTotalPagesAreUnknown() {
        val value = parseBookOasisHomeBook(book(read = 40, total = 0), server, "general")!!
        assertEquals("40%", value.positionLabel)
    }

    @Test fun explicitCompletionIsOneHundredPercentAndAliasesMatchWebCards() {
        val value = parseBookOasisHomeBook(
            book().put("is_completed", 1)
                .put("title_alias", "내 제목").put("series_alias", "내 시리즈"),
            server,
            "general",
        )!!
        assertEquals(100, value.progressPercent)
        assertEquals("내 제목", value.title)
        assertEquals("내 시리즈", value.seriesName)
    }

    @Test fun fixedPageFormatsShowExactSavedPageAndCompletion() {
        val pdf = parseBookOasisHomeBook(book(format = "pdf", read = 8, total = 30), server, "general")!!
        assertEquals("8 / 30 쪽", pdf.positionLabel)
        assertFalse(pdf.completed)
        assertTrue(parseBookOasisHomeBook(book(format = "cbz", read = 3, total = 3), server, "general")!!.completed)
    }

    @Test fun currentReadingComesFromInsightsAndRecentRemainsServerOrder() {
        val stats = JSONObject().put("currently_reading", JSONArray().put(book(9, "pdf", 2, 12)))
        val value = snapshot(recent = JSONArray().put(book(8)).put(book(7)), insights = stats)
        assertEquals(listOf(9L), value.currentlyReading.map { it.bookId })
        assertEquals(listOf(8L, 7L), value.recentBooks.map { it.bookId })
    }

    @Test fun preservesServerLayoutOrderAndHiddenSections() {
        val layout = JSONObject().put("mode", "plugin").put(
            "widgets",
            JSONArray()
                .put(JSONObject().put("id", "core.new").put("kind", "core"))
                .put(JSONObject().put("id", "core.reading_insights").put("kind", "core").put("hidden", true))
                .put(JSONObject().put("id", "core.recent").put("kind", "core"))
                .put(JSONObject().put("id", "plugin.fixture").put("kind", "plugin").put("title", "Fixture web widget")),
        )
        assertEquals(listOf("core.new", "core.recent"), snapshot(layout = layout).sectionOrder)
        assertEquals(listOf("Fixture web widget"), snapshot(layout = layout).webOnlyWidgets)
        assertEquals(BOOKOASIS_CORE_HOME_SECTIONS, snapshot().sectionOrder)
    }

    @Test fun missingStatisticsRemainUnknownRatherThanFabricatedZero() {
        val value = snapshot()
        assertNull(value.insights)
        assertNull(value.totalBookCount)
        assertNull(value.totalSeriesCount)
        val goals = JSONObject().put(
            "streak",
            JSONObject().put("days", 3).put("last_7_days", JSONArray("[false,true,true,false,false,true,true]")),
        )
            .put("annual_goal", JSONObject().put("completed", 4).put("target", 30).put("pct", 13).put("year", 2026))
        assertEquals(3, snapshot(insights = goals).insights!!.streakDays)
        assertEquals(4, snapshot(insights = goals).insights!!.annualGoal!!.completed)
        assertNull(snapshot(insights = goals).insights!!.monthlyGoal)
    }

    @Test fun pendingNativePositionIsVisibleAndPromotedWithoutMutatingRemoteSnapshot() {
        val original = snapshot(recent = JSONArray(), newBooks = JSONArray().put(book()))
        val value = overlayBookOasisHomeProgress(original, listOf(pending()), 42)
        val current = value.currentlyReading.single()
        assertTrue(current.pendingSync)
        assertEquals("4 / 28 쪽", current.positionLabel)
        assertEquals(14, current.progressPercent)
        assertEquals(current, value.recentBooks.single())
        assertTrue(original.currentlyReading.isEmpty())
        assertEquals(11, original.newBooks.single().progressPercent)
    }

    @Test fun localMetadataSupportsOfflineBookAbsentFromOldServerHistory() {
        val local = parseBookOasisHomeBook(book(12, "txt", 0, 40), server, "general")!!
        val value =
            overlayBookOasisHomeProgress(
                snapshot(recent = JSONArray()),
                listOf(pending(12, page = 9, total = 40)),
                42,
                listOf(local),
            )
        assertEquals(12L, value.currentlyReading.single().bookId)
        assertEquals("10 / 40 쪽", value.currentlyReading.single().positionLabel)
    }

    @Test fun pendingSourceAndLibraryTypeNeverCrossEvenWhenBookIdsCollide() {
        val original = snapshot()
        assertEquals(
            original,
            overlayBookOasisHomeProgress(original, listOf(pending(source = 99), pending(type = "adult")), 42),
        )
        val complete = overlayBookOasisHomeProgress(original, listOf(pending(page = 27)), 42)
        assertTrue(complete.currentlyReading.isEmpty())
        assertTrue(complete.recentBooks.single().completed)
    }

    @Test fun rejectedIdsAndUnsupportedFormatsCannotProduceBookActions() {
        for (raw in listOf(
            -1,
            0,
            "1.5",
            "7oops",
        )) {
            assertNull(parseBookOasisHomeBook(book().put("id", raw), server, "general"))
        }
        assertNull(parseBookOasisHomeBook(book(format = "exe"), server, "general"))
        assertNull(parseBookOasisHomeBook(book(), server, "audiobook"))
    }

    @Test fun untrustedCoverUrlsCannotCarryCredentialsToOtherOrigins() {
        for (cover in listOf(
            "https://evil.invalid/private.jpg",
            "https://user:pass@books.example:5930/cover.jpg",
            "http://books.example:5930/cover.jpg",
        )) {
            val value = parseBookOasisHomeBook(book().put("cover_url", cover), server, "general")!!
            assertEquals("$server/api/cover/7?type=general", value.coverUrl)
        }
        assertEquals(
            "$server/covers/cover.jpg",
            parseBookOasisHomeBook(book().put("cover_image", "cover.jpg"), server, "general")!!.coverUrl,
        )
    }

    @Test fun nestedCoverFilenameUsesTheCoverRootRatherThanTheServerRoot() {
        for (raw in listOf(
            "general/cover.webp?t=123",
            "covers/general/cover.webp?t=123",
            "/covers/general/cover.webp?t=123",
        )) {
            val value = parseBookOasisHomeBook(book().put("cover_image", raw), server, "general")!!
            assertEquals("$server/covers/general/cover.webp?t=123", value.coverUrl)
        }
    }

    @Test fun coverImageUsesTheSameNormalizationWhenReturnedAsInsightsCoverUrl() {
        val value = parseBookOasisHomeBook(book().put("cover_url", "general/cover.webp?t=123"), server, "general")!!
        assertEquals("$server/covers/general/cover.webp?t=123", value.coverUrl)
    }

    @Test fun volumeOnlyFilenameIsSecondaryToItsSeriesAndStillSelectsTheExactBook() {
        val value = parseBookOasisHomeBook(
            book(10, "cbz").put("title", "01권.zip").put("series_name", "별빛 도서관"),
            server,
            "general",
        )!!
        assertEquals("별빛 도서관", value.displayTitle)
        assertEquals("01권.zip", value.volumeTitle)
        assertEquals("별빛 도서관 · 01권.zip", value.accessibleTitle)
        assertEquals("01권.zip", value.title)
        assertEquals(10L, value.bookId)
        assertEquals("general:10", value.key)
    }

    @Test fun singleBookTitleIsNotRepeatedAsVolumeSubtitle() {
        val value = parseBookOasisHomeBook(book().put("title", "단행본").put("series_name", ""), server, "general")!!
        assertEquals("단행본", value.displayTitle)
        assertNull(value.volumeTitle)
        assertEquals("단행본", value.accessibleTitle)
    }

    @Test fun coverNormalizationPreservesApiUrlsCacheVersionAndWindowsSubfolders() {
        assertEquals("$server/covers/general/cover.webp?t=123", bookOasisCoverUrl(server, "general\\cover.webp?t=123"))
        assertEquals("$server/covers/cover.webp?t=123", bookOasisCoverUrl(server, "/covers/cover.webp?t=123"))
        assertEquals("$server/api/media/books/7/cover", bookOasisCoverUrl(server, "/api/media/books/7/cover"))
        assertEquals("$server/covers/cover.webp", bookOasisCoverUrl(server, "$server/covers/cover.webp"))
    }

    @Test fun missingOrUntrustedCoverNeverReceivesAServerCredentialRequest() {
        for (raw in listOf(
            null,
            "",
            "null",
            "https://evil.invalid/cover.jpg",
            "//evil.invalid/cover.jpg",
            "https://u:p@books.example:5930/cover.jpg",
            "data:image/png;base64,fixture",
        )) {
            assertNull(bookOasisCoverUrl(server, raw))
        }
    }

    @Test fun cacheIdentitySeparatesAccountOriginProfileAndLibraryTypeWithoutStoringSecrets() {
        val original = bookOasisHomeCacheKey(1, server, "fixture-a", "general")
        assertEquals(original, bookOasisHomeCacheKey(1, server, "fixture-a", "general"))
        assertNotEquals(original, bookOasisHomeCacheKey(1, server, "fixture-b", "general"))
        assertNotEquals(original, bookOasisHomeCacheKey(2, server, "fixture-a", "general"))
        assertNotEquals(original, bookOasisHomeCacheKey(1, "https://other.example", "fixture-a", "general"))
        assertNotEquals(original, bookOasisHomeCacheKey(1, server, "fixture-a", "adult"))
        assertFalse(original.contains("fixture"))
    }
}
