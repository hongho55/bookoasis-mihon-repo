package eu.kanade.tachiyomi.source.bookoasis

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BookOasisDashboardSessionTest {
    @Test fun exchangesCredentialsInPostBodyForHttpOnlyCookie() {
        withServer(200, """{"success":true}""", "Set-Cookie: session=fixture; Path=/; HttpOnly\r\n") { address, capture ->
            assertEquals(listOf("session=fixture; Path=/; HttpOnly"), BookOasisDashboardSession.login(OkHttpClient(), address, "fixture-user", "fixture-pass"))
            val (headers, body) = capture()
            assertEquals("POST /login HTTP/1.1", headers.first())
            assertFalse(headers.any { it.contains("fixture-pass") || it.startsWith("Authorization:") })
            assertEquals("fixture-user", JSONObject(body).getString("username"))
            assertEquals("fixture-pass", JSONObject(body).getString("password"))
            assertTrue(JSONObject(body).getBoolean("remember_me"))
        }
    }

    @Test fun rejectsCookieLessLoginAndRedirectInsteadOfFollowingCredentials() {
        for ((code, headers) in listOf(200 to "", 302 to "Location: http://example.invalid/login\r\n", 401 to "")) {
            withServer(code, """{"success":true}""", headers) { address, capture ->
                assertTrue(runCatching { BookOasisDashboardSession.login(OkHttpClient(), address, "fixture-user", "fixture-pass") }.isFailure)
                capture()
            }
        }
    }

    @Test fun rejectsEmbeddedUrlCredentialsBeforeNetwork() {
        assertTrue(runCatching { BookOasisDashboardSession.login(OkHttpClient(), "https://user:pass@example.invalid", "fixture-user", "fixture-pass") }.isFailure)
    }

    private fun withServer(
        status: Int,
        responseBody: String,
        responseHeaders: String,
        test: (String, () -> Pair<List<String>, String>) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val captured = executor.submit<Pair<List<String>, String>> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = buildList {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            add(line)
                        }
                    }
                    val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(chars, read, length - read)
                        check(n > 0)
                        read += n
                    }
                    val bytes = responseBody.toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n$responseHeaders" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                    headers to String(chars)
                }
            }
            try {
                test("http://127.0.0.1:${server.localPort}") { captured.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
