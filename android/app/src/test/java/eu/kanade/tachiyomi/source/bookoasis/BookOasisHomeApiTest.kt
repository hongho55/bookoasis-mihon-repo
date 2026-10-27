package eu.kanade.tachiyomi.source.bookoasis

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class BookOasisHomeApiTest {
    @Test fun requestsActualDashboardRoutesAndKeepsPerAccountAuthAndDbType() = runBlocking {
        withServer { address, requests ->
            val payload =
                fetchBookOasisHomePayload(
                    OkHttpClient(),
                    address,
                    Headers.headersOf("Authorization", "Basic synthetic"),
                    "adult",
                )
            val value = parseHomePayload(address, payload)
            assertEquals(5, requests.size)
            assertTrue(requests.all { it.second == "Basic synthetic" })
            assertTrue(
                requests.single {
                    it.first.startsWith("/api/dashboard/insights?")
                }.first.contains("library_type=adult"),
            )
            assertTrue(requests.single { it.first.startsWith("/api/media/history?") }.first.contains("type=adult"))
            assertTrue(
                requests.single {
                    it.first.startsWith("/api/media/list-totals?")
                }.first.contains("library_id=all"),
            )
            assertEquals("adult", value.dbType)
            assertEquals(50, value.currentlyReading.single().progressPercent)
            assertEquals(10, value.totalBookCount)
            assertTrue(value.failedSections.isEmpty())
        }
    }

    @Test fun failedSectionIsExplicitAndMissingTotalsAreNotInvented() = runBlocking {
        withServer(mapOf("/api/media/list-totals" to 503, "/api/media/home-layout" to 404)) { address, _ ->
            val value =
                parseHomePayload(
                    address,
                    fetchBookOasisHomePayload(OkHttpClient(), address, Headers.headersOf(), "general"),
                )
            assertEquals(listOf("보관함 집계"), value.failedSections)
            assertNull(value.totalBookCount)
            assertEquals(BOOKOASIS_CORE_HOME_SECTIONS, value.sectionOrder)
            assertEquals(7L, value.currentlyReading.single().bookId)
        }
    }

    @Test fun loginRedirectDoesNotMasqueradeAsEmptySuccessfulDashboard() = runBlocking {
        withServer(
            mapOf(
                "/api/media/history" to 302,
                "/api/media/recently-added" to 302,
                "/api/dashboard/insights" to 302,
            ),
        ) { address, requests ->
            val result =
                runCatching { fetchBookOasisHomePayload(OkHttpClient(), address, Headers.headersOf(), "general") }
            assertTrue(result.isFailure)
            assertFalse(requests.any { it.first.startsWith("/login") })
        }
    }

    private suspend fun withServer(
        statuses: Map<String, Int> = emptyMap(),
        test: suspend (String, List<Pair<String, String?>>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path
            val body = when (path) {
                "/api/media/history", "/api/media/recently-added" -> """
                    {"success":true,"books":[{"id":7,"title":"Fixture","series_name":"Fixture",
                    "file_format":"epub","pages_read":50,"total_pages":100}]}
                """.trimIndent()
                "/api/dashboard/insights" -> """
                    {"success":true,"currently_reading":[{"id":7,"title":"Fixture","file_format":"epub",
                    "progress_pct":50,"pages_read":50,"total_pages":100}]}
                """.trimIndent()
                "/api/media/list-totals" -> """{"success":true,"total_book_count":10,"total_series_count":8}"""
                "/api/media/home-layout" -> """{"success":true,"mode":"classic","widgets":[]}"""
                else -> """{"success":true,"widgets":[]}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Location", "/login")
            exchange.sendResponseHeaders(statuses[path] ?: 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
