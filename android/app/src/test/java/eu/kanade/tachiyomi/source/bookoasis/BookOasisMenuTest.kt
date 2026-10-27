package eu.kanade.tachiyomi.source.bookoasis

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class BookOasisMenuTest {
    private val libraryPayload = """{"success":true,"libraries":[
        {"id":12,"name":"가정","group_id":3},{"id":13,"name":"과학","group_id":3},
        {"id":14,"name":"만화"},{"id":15,"name":"기타"},{"id":12,"name":"중복"},{"id":16}],
        "groups":[{"id":3,"name":"도서"}]}"""
    private val pluginPayload = """{"success":true,"category_plugins":[
        {"id":"synthetic_search","title":"검색 시험 플러그인","order":5,"group_id":3},
        {"id":"other","name":"별도 메뉴"}]}"""

    @Test fun parsesServerMenuNamesGroupsAndOrderWithoutInventingOrDuplicatingMenus() {
        val menu = parseBookOasisMenu("general", JSONObject(libraryPayload), JSONObject(pluginPayload))
        assertEquals(listOf("가정", "과학", "만화", "기타"), menu.libraries.map { it.title })
        assertEquals(listOf("12", "13", "14", "15"), menu.libraries.map { it.id })
        assertEquals("3", menu.libraries.first().groupId)
        assertEquals("도서", menu.groups.single().title)
        assertEquals("검색 시험 플러그인", menu.plugins.first().title)
        assertEquals("별도 메뉴", menu.plugins.last().title)
    }

    @Test fun categoryIdentityIncludesLibraryTypeEvenWhenIdsCollide() {
        val general = parseBookOasisMenu("general", JSONObject(libraryPayload), JSONObject(pluginPayload))
        val adult = parseBookOasisMenu("adult", JSONObject(libraryPayload), JSONObject(pluginPayload))
        assertEquals("general:12", general.libraries.first().key)
        assertEquals("adult:12", adult.libraries.first().key)
        assertTrue(general.plugins.all { it.dbType == "general" })
        assertTrue(adult.plugins.all { it.dbType == "adult" })
    }

    @Test fun categorySearchAndPaginationKeepTheSelectedCategoryAndExactSearchTerm() {
        val query = "책 & 과학?"
        val url = bookOasisCatalogUrl("https://books.example", "general", "13", 2, query, "asc", 30).toHttpUrl()
        assertEquals("/api/media/list", url.encodedPath)
        assertEquals("general", url.queryParameter("type"))
        assertEquals("13", url.queryParameter("library_id"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals(query, url.queryParameter("search"))
        assertEquals("30", url.queryParameter("limit"))
    }

    @Test fun pluginOpensItsOwnServerUiNotAdminMetadataSearchOrServerWideBooks() {
        val id = "fixture_search&type=adult"
        val url = bookOasisPluginUrl("https://books.example", id, "general").toHttpUrl()
        assertEquals("books.example", url.host)
        assertEquals("/", url.encodedPath)
        assertEquals("1", url.queryParameter("kiosk"))
        assertEquals(id, url.queryParameter("plugin"))
        assertEquals(listOf("general"), url.queryParameterValues("type"))
        assertFalse(url.queryParameterNames.contains("return"))
        assertTrue(runCatching { bookOasisPluginUrl("https://u:p@books.example", "fixture", "general") }.isFailure)
        assertTrue(runCatching { bookOasisPluginUrl("https://books.example", "fixture", "invalid") }.isFailure)
    }

    @Test fun fetchesOnlyVisibleCategoryEndpointsWithCurrentAccountAndDbType() = runBlocking {
        withServer(200) { address, requests ->
            val menu =
                fetchBookOasisMenu(
                    OkHttpClient(),
                    address,
                    Headers.headersOf("Authorization", "Basic synthetic"),
                    "adult",
                )
            assertEquals(2, requests.size)
            assertEquals(
                setOf("/api/media/libraries?type=adult", "/api/media/category-plugins?type=adult"),
                requests.map {
                    it.first
                }.toSet(),
            )
            assertTrue(requests.all { it.second == "Basic synthetic" })
            assertEquals("adult", menu.dbType)
            assertEquals(4, menu.libraries.size)
        }
    }

    @Test fun loginRedirectIsNotAnEmptyMenuAndDoesNotForwardCredentialsElsewhere() = runBlocking {
        withServer(302) { address, requests ->
            assertTrue(
                runCatching {
                    fetchBookOasisMenu(OkHttpClient(), address, Headers.headersOf(), "general")
                }.isFailure,
            )
            assertFalse(requests.any { it.first.startsWith("/login") })
        }
    }

    @Test fun deniedOrOfflineMenuIsAnErrorNotAnInventedEmptySuccess() = runBlocking {
        withServer(403) { address, _ ->
            assertTrue(
                runCatching {
                    fetchBookOasisMenu(OkHttpClient(), address, Headers.headersOf(), "adult")
                }.isFailure,
            )
        }
    }

    private suspend fun withServer(status: Int, test: suspend (String, List<Pair<String, String?>>) -> Unit) {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            val body = (
                if (exchange.requestURI.path ==
                    "/api/media/libraries"
                ) {
                    libraryPayload
                } else {
                    pluginPayload
                }
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Location", "/login")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }
}
