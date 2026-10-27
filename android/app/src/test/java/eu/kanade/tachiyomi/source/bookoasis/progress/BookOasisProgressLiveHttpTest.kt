package eu.kanade.tachiyomi.source.bookoasis.progress

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opt-in real HTTP JVM acceptance test. Credentials are read in memory and never logged. */
@RunWith(RobolectricTestRunner::class)
class BookOasisProgressLiveHttpTest {
    @Test
    fun `stock fixture roundtrips EPUB TXT PDF CBZ and ZIP through the Android adapter`() {
        val configPath = System.getenv("BOOKOASIS_LIVE_TEST_CONFIG")
        assumeTrue("Set BOOKOASIS_LIVE_TEST_CONFIG to run the isolated stock HTTP test", !configPath.isNullOrBlank())
        val configFile = File(requireNotNull(configPath)).canonicalFile
        require(configFile.name == "live-test-config.json") {
            "Refusing an unrecognized live test configuration filename"
        }
        val fixtureDirectory = requireNotNull(configFile.parentFile)
        val config = JSONObject(configFile.readText())
        val address = config.getString("url").trimEnd('/')
        require(address == "http://127.0.0.1:18759") { "Refusing a non-fixture endpoint" }
        val credentialsFile = File(config.getString("credentials_file")).canonicalFile
        require(credentialsFile.toPath().startsWith(fixtureDirectory.toPath())) {
            "Refusing a credentials path outside the isolated fixture directory"
        }
        val credentials = JSONObject(credentialsFile.readText())
        val username = credentials.getString("username")
        val password = credentials.getString("password")
        val binding = requireNotNull(BookOasisProgressApi.localAccountBinding(address, username))
        val client = BookOasisProgressApi.isolatedDirectClient(
            OkHttpClient(),
            address,
            username,
            password,
            binding,
        ) { binding }

        val cases = listOf(
            Case(config.getLong("epub_book"), "epub", EPUB_PAGES, EPUB_PROGRESS_PAGE),
            Case(config.getLong("txt_book"), "txt", TXT_PAGES, 0),
            Case(config.getLong("pdf_book"), "pdf", PAGE_COUNT, PAGE_INDEX),
            Case(config.getLong("cbz_book"), "comic", PAGE_COUNT, PAGE_INDEX),
            Case(config.getLong("zip_book"), "comic", PAGE_COUNT, PAGE_INDEX),
        )
        assertEquals(listOf("epub", "txt", "pdf", "comic", "comic"), cases.map { it.format })

        for (case in cases) {
            val probe = pending(case, binding, fingerprint = null, offset = null)
            val before = runBlocking { client.get(probe) }
            val bodyAndAnchor = if (case.format == "epub" || case.format == "txt") {
                val body = runBlocking { client.readOnlyText(probe, 0) }
                val anchor = uniqueAnchor(body)
                body to anchor
            } else {
                "" to null
            }
            val rawText = bodyAndAnchor.first
            val anchor = bodyAndAnchor.second
            val nativeOffset = if (case.format == "txt") rawText.indexOf(anchor!!).toLong() else null
            val item = pending(case, binding, anchor, nativeOffset)

            val result = runBlocking { client.post(item, before.revision, before.accountScope) }
            assertTrue(
                "${case.format} stock progress write was not acknowledged",
                result is BookOasisProgressPostResult.Success,
            )
            val success = result as BookOasisProgressPostResult.Success
            val after = runBlocking { client.get(item) }
            assertEquals("${case.format} GET read-back revision", success.revision, after.revision)
            assertEquals("local1:${binding.removePrefix("sha256:")}", after.accountScope)
            if (case.format == "epub" || case.format == "txt") {
                assertEquals("${case.format} raw anchor", anchor, after.fingerprint)
                assertEquals("${case.format} read/listen precedence", "read", after.positionKind)
            } else {
                assertEquals("${case.format} page progress", case.pageIndex + 1, after.pagesRead)
                assertEquals("${case.format} page count", case.totalPages, after.totalPages)
            }
        }
    }

    private fun uniqueAnchor(text: String): String {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        for (windowSize in 4..10) {
            for (window in words.windowed(windowSize)) {
                val candidate = window.joinToString(" ").take(120)
                if (candidate.codePointCount(0, candidate.length) >= 16 &&
                    BookOasisProgressApi.anchorOffsets(text, candidate).size == 1
                ) {
                    return candidate
                }
            }
        }
        error("Synthetic text did not contain a unique sentence anchor")
    }

    private fun pending(case: Case, binding: String, fingerprint: String?, offset: Long?) = BookOasisPendingProgress(
        sourceId = 7,
        bookId = case.bookId,
        dbType = "general",
        format = case.format,
        pageIndex = case.pageIndex,
        totalPages = case.totalPages,
        documentIndex = if (case.format == "epub" || case.format == "txt") 0 else null,
        startOffset = offset,
        fingerprint = fingerprint,
        updatedAt = System.currentTimeMillis(),
        accountBinding = binding,
        queueKey = "live-direct-test:${case.bookId}",
    )

    private data class Case(
        val bookId: Long,
        val format: String,
        val totalPages: Int,
        val pageIndex: Int,
    )

    private companion object {
        const val EPUB_PAGES = 100
        const val EPUB_PROGRESS_PAGE = 24
        const val TXT_PAGES = 1
        const val PAGE_COUNT = 10
        const val PAGE_INDEX = 2
    }
}
