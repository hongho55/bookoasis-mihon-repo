package eu.kanade.tachiyomi.source.bookoasis.progress

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class BookOasisProgressSessionTest {
    @Test
    fun `adapter reads only stock endpoints with an isolated session`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 3) { request ->
                when {
                    request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"book":{"id":701,"file_format":"epub"}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"pages_read":0,"total_pages":100,"epub_session":{"index":null,"percent":0,"fingerprint":null,"updatedAt":null}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/tts/position?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"read":null,"listen":null,"legacy":null,"latest":null}}""",
                    )
                    else -> respond(request.socket, 404, "{}")
                }
            }
        }
        val client = BookOasisProgressApi.isolatedDirectClient(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            "binding-A",
        ) { "binding-A" }
        val state = runBlocking { client.get(pending("epub", 701, "binding-A")) }
        val requests = capture.get(5, TimeUnit.SECONDS)
        assertTrue(state.revision.startsWith("stock1:"))
        assertEquals("local1:binding-A", state.accountScope)
        assertEquals(
            listOf(
                "GET /api/media/books/701/reader-info?type=general HTTP/1.1",
                "GET /api/media/progress-state?db_type=general&book_id=701 HTTP/1.1",
                "GET /api/media/tts/position?db_type=general&book_id=701 HTTP/1.1",
            ),
            requests.map { it.requestLine },
        )
        assertTrue(requests.none { "/plugins/" in it.requestLine })
        assertTrue(requests.all { request -> request.headers.none { it.equals("Cookie: session=B", true) } })
    }

    @Test
    fun `equal reading time never writes just because a revision changed`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 3) { request ->
                when {
                    request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"book":{"id":701,"file_format":"epub"}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"pages_read":0,"total_pages":100,"epub_session":{"index":null,"percent":0,"fingerprint":null,"updatedAt":null}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/tts/position?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"read":null,"listen":null,"legacy":null,"latest":null}}""",
                    )
                    else -> respond(request.socket, 404, "{}")
                }
            }
        }
        val client = BookOasisProgressApi.isolatedDirectClient(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            "binding-A",
        ) { "binding-A" }
        val result = runBlocking {
            client.post(pending("epub", 701, "binding-A").copy(updatedAt = 0), "stock1:stale", "local1:binding-A")
        }
        assertTrue(result is BookOasisProgressPostResult.Conflict)
        val requests = capture.get(5, TimeUnit.SECONDS)
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.requestLine.startsWith("GET ") })
        assertTrue(requests.none { "/api/media/progress " in it.requestLine || "/plugins/" in it.requestLine })
    }

    @Test
    fun `unresolvable server EPUB anchor is rejected before accepting a new base revision`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 4) { request ->
                when {
                    request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"book":{"id":701,"file_format":"epub"}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"pages_read":60,"total_pages":100,"epub_session":{"index":1,"percent":60,"fingerprint":"older pointer","updatedAt":null}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/tts/position?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"latest":"read","read":{"chapter_idx":1,"char_offset":16,"text_len":1905,"anchor":"p;B blue mountains and amber r","updated_ms":100}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/epub/chapter?") -> respond(
                        request.socket,
                        200,
                        """{"chapter_idx":1,"content":"<p>Unique A&amp;B blue mountains and amber rivers.</p>"}""",
                    )
                    else -> respond(request.socket, 404, "{}")
                }
            }
        }
        val client = BookOasisProgressApi.isolatedDirectClient(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            "binding-A",
        ) { "binding-A" }
        try {
            runBlocking { client.get(pending("epub", 701, "binding-A")) }
            fail("A sliced HTML entity is not a trustworthy server location")
        } catch (error: BookOasisProgressException) {
            assertEquals("INVALID", error.code)
        }
        val requests = capture.get(5, TimeUnit.SECONDS)
        assertTrue(requests.all { it.requestLine.startsWith("GET ") })
        assertEquals(4, requests.size)
    }

    @Test
    fun `remote EPUB distinguishes encoded ampersand from literal entity text`() {
        listOf(
            "<p>A &amp; B raw chapter marker</p>" to "A & B raw chapter marker",
            "<p>A &amp;amp; B raw chapter marker</p>" to "A &amp; B raw chapter marker",
        ).forEach { (body, expected) ->
            withServer { server ->
                val capture = server.executor.submit<List<HttpRequest>> {
                    acceptRequests(server, 4) { request ->
                        when {
                            request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                                request.socket,
                                200,
                                """{"success":true,"book":{"id":701,"file_format":"epub"}}""",
                            )
                            request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                                request.socket,
                                200,
                                """{"success":true,"state":{"pages_read":10,"total_pages":100,"epub_session":{}}}""",
                            )
                            request.requestLine.startsWith("GET /api/media/tts/position?") -> respond(
                                request.socket,
                                200,
                                """{"success":true,"state":{"latest":"read","read":{"chapter_idx":0,"char_offset":0,"text_len":0,"anchor":"A &amp; B raw chapter marker","updated_ms":100}}}""",
                            )
                            request.requestLine.startsWith("GET /api/media/epub/chapter?") -> respond(
                                request.socket,
                                200,
                                JSONObject().put("chapter_idx", 0).put("content", body).toString(),
                            )
                            else -> respond(request.socket, 404, "{}")
                        }
                    }
                }
                val client = BookOasisProgressApi.isolatedDirectClient(
                    clientWithSharedAccountBCookie(),
                    server.address,
                    "account-A",
                    "password-A",
                    "binding-A",
                ) { "binding-A" }
                val remote = runBlocking { client.get(pending("epub", 701, "binding-A")) }
                assertEquals(expected, remote.fingerprint)
                assertTrue(remote.fingerprintIsRaw)
                assertTrue(capture.get(5, TimeUnit.SECONDS).all { it.requestLine.startsWith("GET ") })
            }
        }
    }

    @Test
    fun `lost acknowledgement retry accepts the same already stored position without POST`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 7) { request ->
                when {
                    request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"book":{"id":701,"file_format":"epub"}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"pages_read":10,"total_pages":100,"epub_session":{"index":0,"percent":0,"fingerprint":"A useful saved text anchor","updatedAt":null}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/tts/position?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"state":{"latest":"read","read":{"chapter_idx":0,"char_offset":0,"text_len":0,"anchor":"A useful saved text anchor","updated_ms":100}}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/epub/chapter?") -> respond(
                        request.socket,
                        200,
                        """{"chapter_idx":0,"content":"<p>A useful saved text anchor</p>"}""",
                    )
                    else -> respond(request.socket, 404, "{}")
                }
            }
        }
        val client = BookOasisProgressApi.isolatedDirectClient(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            "binding-A",
        ) { "binding-A" }
        val result = runBlocking {
            client.post(pending("epub", 701, "binding-A"), "stock1:before-the-lost-ACK", "local1:binding-A")
        }
        assertTrue(result is BookOasisProgressPostResult.Success)
        assertTrue(capture.get(5, TimeUnit.SECONDS).all { it.requestLine.startsWith("GET ") })
    }

    @Test
    fun `stock request refreshes captured account and never reuses shared WebView cookies`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 3) { request ->
                when (request.requestLine.substringBefore(' ')) {
                    "GET" -> if (request.headers.none { it.equals("Cookie: session=A", true) }) {
                        respond(request.socket, 401, "{}")
                    } else {
                        respond(request.socket, 200, """{"success":true,"state":{}}""")
                    }
                    "POST" -> {
                        assertEquals("/login", request.target)
                        assertEquals("account-A", JSONObject(request.body).getString("username"))
                        assertEquals("password-A", JSONObject(request.body).getString("password"))
                        respond(
                            request.socket,
                            200,
                            """{"success":true}""",
                            "Set-Cookie: session=A; Path=/; HttpOnly\r\n",
                        )
                    }
                    else -> fail("unexpected request")
                }
            }
        }
        val session = directSession(server, "binding-A") { "binding-A" }
        val payload = runBlocking {
            session.json("GET", "${server.address}/api/media/progress-state?db_type=general&book_id=701")
        }
        val requests = capture.get(5, TimeUnit.SECONDS)
        assertTrue(payload.optBoolean("success"))
        assertEquals(3, requests.size)
        assertFalse(requests.first().headers.any { it.equals("Cookie: session=B", true) })
        assertEquals("account-A", JSONObject(requests[1].body).getString("username"))
        assertTrue(requests.last().headers.any { it.equals("Cookie: session=A", true) })
        assertFalse(requests.last().headers.any { it.equals("Cookie: session=B", true) })
        assertTrue(requests.all { "/plugins/" !in it.requestLine })
    }

    @Test
    fun `account switch during stock 401 aborts before login or retry`() = withServer { server ->
        val binding = AtomicReference("binding-A")
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 1) { request ->
                binding.set("binding-B")
                respond(request.socket, 401, "{}")
            }
        }
        val session = directSession(server, "binding-A") { binding.get() }
        try {
            runBlocking {
                session.json("GET", "${server.address}/api/media/progress-state?db_type=general&book_id=701")
            }
            fail("account switch during 401 must abort")
        } catch (error: BookOasisProgressException) {
            assertEquals("ACCOUNT_CHANGED", error.code)
        }
        assertEquals(1, capture.get(5, TimeUnit.SECONDS).size)
    }

    @Test
    fun `redirects are not followed to another origin`() = withServer { server ->
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 1) { request ->
                respond(request.socket, 302, "{}", "Location: http://example.invalid/steal\r\n")
            }
        }
        val session = directSession(server, "binding-A") { "binding-A" }
        try {
            runBlocking {
                session.json("GET", "${server.address}/api/media/progress-state?db_type=general&book_id=701")
            }
            fail("redirect must be rejected")
        } catch (error: BookOasisProgressException) {
            assertEquals("UNSUPPORTED", error.code)
        }
        assertEquals(1, capture.get(5, TimeUnit.SECONDS).size)
    }

    @Test
    fun `newer local reading overwrites an older changed revision`() = withServer { server ->
        var posted = false
        val capture = server.executor.submit<List<HttpRequest>> {
            acceptRequests(server, 4) { request ->
                when {
                    request.requestLine.startsWith("GET /api/media/books/701/reader-info?") -> respond(
                        request.socket,
                        200,
                        """{"success":true,"book":{"id":701,"file_format":"pdf"}}""",
                    )
                    request.requestLine.startsWith("GET /api/media/progress-state?") -> respond(
                        request.socket,
                        200,
                        JSONObject().put("success", true).put(
                            "state",
                            JSONObject().put("pages_read", if (posted) 1 else 8).put("total_pages", 10),
                        ).toString(),
                    )
                    request.requestLine.startsWith("POST /api/media/progress ") -> {
                        posted = true
                        respond(request.socket, 200, """{"success":true}""")
                    }
                    else -> respond(request.socket, 404, "{}")
                }
            }
        }
        val client = BookOasisProgressApi.isolatedDirectClient(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            "binding-A",
        ) { "binding-A" }
        val result = runBlocking { client.post(pending("pdf", 701, "binding-A"), "stock1:changed", "local1:binding-A") }
        assertTrue(result is BookOasisProgressPostResult.Success)
        assertTrue(posted)
        assertEquals(1, capture.get(5, TimeUnit.SECONDS).count { it.requestLine.startsWith("POST ") })
    }

    private fun directSession(server: ServerFixture, binding: String, current: () -> String?) =
        BookOasisDirectWebSession(
            clientWithSharedAccountBCookie(),
            server.address,
            "account-A",
            "password-A",
            binding,
            current,
        )

    private fun clientWithSharedAccountBCookie(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = listOf(
                Cookie.Builder().name("session").value("B").domain(url.host).path("/").build(),
            )
        })
        .build()

    private fun pending(format: String, bookId: Long, binding: String) = BookOasisPendingProgress(
        sourceId = 7,
        bookId = bookId,
        dbType = "general",
        format = format,
        pageIndex = 0,
        totalPages = 10,
        documentIndex = if (format == "epub" || format == "txt") 0 else null,
        startOffset = if (format == "txt") 0 else null,
        fingerprint = if (format == "epub" || format == "txt") "A useful saved text anchor" else null,
        updatedAt = 1,
        accountBinding = binding,
    )

    private fun withServer(test: (ServerFixture) -> Unit) {
        val socket = ServerSocket(0)
        val fixture = ServerFixture(socket, "http://127.0.0.1:${socket.localPort}", Executors.newSingleThreadExecutor())
        try {
            test(fixture)
        } finally {
            socket.close()
            fixture.executor.shutdownNow()
        }
    }

    private fun acceptRequests(
        fixture: ServerFixture,
        count: Int,
        respondTo: (HttpRequest) -> Unit,
    ): List<HttpRequest> = buildList {
        repeat(count) {
            fixture.serverSocket.accept().use { socket ->
                socket.soTimeout = 5000
                val request = readRequest(socket)
                add(request)
                respondTo(request)
            }
        }
    }

    private fun readRequest(socket: Socket): HttpRequest {
        val reader = socket.getInputStream().bufferedReader()
        val first = reader.readLine() ?: error("request line missing")
        val headers = buildList {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                add(line)
            }
        }
        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val amount = reader.read(body, read, length - read)
            check(amount > 0)
            read += amount
        }
        return HttpRequest(socket, first, first.substringAfter(' ').substringBefore(' '), headers, String(body))
    }

    private fun respond(socket: Socket, status: Int, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n$extraHeaders" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private data class ServerFixture(
        val serverSocket: ServerSocket,
        val address: String,
        val executor: java.util.concurrent.ExecutorService,
    )

    private data class HttpRequest(
        val socket: Socket,
        val requestLine: String,
        val target: String,
        val headers: List<String>,
        val body: String,
    )
}
