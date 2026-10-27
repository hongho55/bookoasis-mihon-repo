package eu.kanade.tachiyomi.source.bookoasis

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Private, account/origin/profile-scoped last successful dashboard; no credentials are cached. */
internal class BookOasisHomeRepository(context: Context) {
    private val preferences = context.getSharedPreferences("bookoasis_native_home", Context.MODE_PRIVATE)

    fun key(source: BookOasisSource): String = bookOasisHomeCacheKey(
        source.id,
        source.baseUrl,
        source.getSourcePreferences().getString("username", "").orEmpty(),
        source.getSourcePreferences().getString("library_type", "general").orEmpty(),
    )

    fun cached(source: BookOasisSource): List<BookOasisHomeSnapshot> {
        if (source.baseUrl.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(preferences.getString(key(source), "[]"))
            (0 until array.length()).map { parseHomePayload(source.baseUrl, array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun refresh(source: BookOasisSource): List<BookOasisHomeSnapshot> = withContext(Dispatchers.IO) {
        val accountKey = key(source)
        val baseUrl = source.baseUrl
        val types = when (source.getSourcePreferences().getString("library_type", "general")) {
            "adult" -> listOf("adult")
            "both" -> listOf("general", "adult")
            else -> listOf("general")
        }
        BookOasisDashboardSession.login(source)
        val payloads = coroutineScope {
            types.map { type ->
                async { fetchBookOasisHomePayload(source.client, baseUrl, source.currentHeaders(), type) }
            }.awaitAll()
        }
        // A connection edit during a request must not save/display another account's response.
        if (accountKey != key(source)) throw CancellationException("BookOasis connection changed")
        preferences.edit(commit = true) { putString(accountKey, JSONArray(payloads).toString()) }
        payloads.map { parseHomePayload(baseUrl, it) }
    }
}

internal fun bookOasisHomeCacheKey(sourceId: Long, baseUrl: String, username: String, libraryType: String): String {
    val identity = JSONArray(listOf(sourceId.toString(), baseUrl, username, libraryType)).toString()
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** The exact read-only data routes used by the server dashboard, not the OPDS catalog subset. */
internal suspend fun fetchBookOasisHomePayload(
    client: OkHttpClient,
    baseUrl: String,
    headers: Headers,
    dbType: String,
): JSONObject = coroutineScope {
    require(bookOasisDashboardOrigin(baseUrl) != null && dbType in setOf("general", "adult"))
    val boundedClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build()
    val routes = listOf(
        HomeRoute("history", "/api/media/history", "최근 읽은 책"),
        HomeRoute("new", "/api/media/recently-added", "새로 추가된 책"),
        HomeRoute("insights", "/api/dashboard/insights", "읽기 현황", "library_type"),
        HomeRoute("totals", "/api/media/list-totals", "보관함 집계"),
        HomeRoute("layout", "/api/media/home-layout", "홈 배치", optional = true),
    )
    val responses = routes.map { route ->
        async {
            val url = (baseUrl.trimEnd('/') + route.path).toHttpUrl().newBuilder()
                .addQueryParameter(route.parameter, dbType)
                .apply { if (route.key == "totals") addQueryParameter("library_id", "all") }.build()
            try {
                val request = Request.Builder().url(url).headers(headers)
                    .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store").get().build()
                val response = boundedClient.newCall(request).await().use { response ->
                    if (route.optional && response.code == 404) return@use null
                    if (!response.isSuccessful) throw IOException("Dashboard ${route.key}: HTTP ${response.code}")
                    JSONObject(response.body.string()).also {
                        if (!it.optBoolean(
                                "success",
                            )
                        ) {
                            throw IOException("Dashboard ${route.key}: unsuccessful response")
                        }
                    }
                }
                Triple(route, response, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Triple(route, null, true)
            }
        }
    }.awaitAll()
    if (responses.filter { it.first.key in setOf("history", "new", "insights") }.all { it.second == null }) {
        throw IOException("대시보드 자료를 불러오지 못했습니다. 서버 연결과 계정을 확인해 주세요.")
    }
    JSONObject().put("dbType", dbType).put("fetchedAt", System.currentTimeMillis()).also { payload ->
        responses.forEach { (route, body, _) -> if (body != null) payload.put(route.key, body) }
        payload.put("failed", JSONArray(responses.filter { it.third }.map { it.first.label }))
    }
}

internal fun parseHomePayload(baseUrl: String, payload: JSONObject): BookOasisHomeSnapshot {
    val failed = payload.optJSONArray("failed")
    return parseBookOasisHomeSnapshot(
        baseUrl = baseUrl,
        dbType = payload.getString("dbType").also { require(it in setOf("general", "adult")) },
        history = payload.optJSONObject("history"),
        newBooks = payload.optJSONObject("new"),
        insights = payload.optJSONObject("insights"),
        totals = payload.optJSONObject("totals"),
        layout = payload.optJSONObject("layout"),
        failedSections = if (failed == null) emptyList() else (0 until failed.length()).map { failed.getString(it) },
        fetchedAt = payload.getLong("fetchedAt"),
    )
}

private data class HomeRoute(
    val key: String,
    val path: String,
    val label: String,
    val parameter: String = "type",
    val optional: Boolean = false,
)
