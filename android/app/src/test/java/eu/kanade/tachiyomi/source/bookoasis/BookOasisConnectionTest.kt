package eu.kanade.tachiyomi.source.bookoasis

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BookOasisConnectionTest {
    @Test fun existingConfigurationAndSkipDoNotForceOnboarding() {
        assertEquals(false, shouldShowBookOasisOnboarding("http://server", false))
        assertEquals(false, shouldShowBookOasisOnboarding("", true))
        assertEquals(true, shouldShowBookOasisOnboarding("", false))
    }

    @Test fun firstSuccessfulConnectionOpensBrowseOnlyOnce() {
        assertEquals(true, shouldOpenBookOasisBrowse(firstBrowseAlreadyOpened = false))
        assertEquals(false, shouldOpenBookOasisBrowse(firstBrowseAlreadyOpened = true))
    }

    @Test fun rejectsInvalidUrlWithoutRequest() = runBlocking {
        assertEquals(
            BookOasisConnectionResult.InvalidUrl,
            BookOasisConnection.validate(OkHttpClient(), "not a url", "u", "p"),
        )
    }

    @Test fun authenticatedCatalogRequestAcceptsDocumentedJsonAndNormalizesRoot() = runBlocking {
        val (result, request) = requestWithResponse(200, "{\"success\":true,\"series\":[]}", rootSuffix = "/app-opds/")
        assertEquals(BookOasisConnectionResult.Connected, result)
        assertEquals(
            "GET /app-opds/api/media/list?type=general&library_id=all&page=1&limit=30&sort=asc HTTP/1.1",
            request.first(),
        )
        assertEquals("Authorization: Basic dXNlcjpwYXNz", request.first { it.startsWith("Authorization:") })
    }

    @Test fun classifiesUnreachableServer() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(
            BookOasisConnectionResult.Unreachable,
            BookOasisConnection.validate(OkHttpClient(), "http://127.0.0.1:$closed", "user", "pass"),
        )
    }

    @Test fun classifiesAuthAndUnexpectedResponseSeparately() = runBlocking {
        assertEquals(BookOasisConnectionResult.AuthenticationRejected, requestWithResponse(401, "{}").first)
        assertEquals(BookOasisConnectionResult.AuthenticationRejected, requestWithResponse(403, "{}").first)
        assertEquals(BookOasisConnectionResult.UnexpectedResponse, requestWithResponse(302, "{}").first)
        assertEquals(BookOasisConnectionResult.UnexpectedResponse, requestWithResponse(200, "{\"success\":true}").first)
        assertEquals(BookOasisConnectionResult.UnexpectedResponse, requestWithResponse(500, "oops").first)
    }

    private fun requestWithResponse(
        status: Int,
        body: String,
        rootSuffix: String = "",
    ): Pair<BookOasisConnectionResult, List<String>> {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val capture = executor.submit<List<String>> {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val lines = buildList {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        add(line)
                    }
                }
                val bytes = body.toByteArray()
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    write(bytes)
                    flush()
                }
                lines
            }
        }
        try {
            val result = runBlocking {
                BookOasisConnection.validate(
                    OkHttpClient(),
                    "http://127.0.0.1:${server.localPort}$rootSuffix",
                    "user",
                    "pass",
                )
            }
            return result to capture.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
