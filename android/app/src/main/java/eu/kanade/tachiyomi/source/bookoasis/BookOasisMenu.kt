package eu.kanade.tachiyomi.source.bookoasis

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class BookOasisLibraryMenu(
    val id: String,
    val title: String,
    val dbType: String,
    val groupId: String?,
) : java.io.Serializable {
    val key: String get() = "$dbType:$id"
}

internal data class BookOasisPluginMenu(
    val id: String,
    val title: String,
    val dbType: String,
    val groupId: String?,
)

internal data class BookOasisMenuGroup(val id: String, val title: String)

internal data class BookOasisMenu(
    val dbType: String,
    val libraries: List<BookOasisLibraryMenu>,
    val plugins: List<BookOasisPluginMenu>,
    val groups: List<BookOasisMenuGroup>,
)

/** Only the server's visible categories/plugins; never the admin plugin-management/config API. */
internal suspend fun fetchBookOasisMenu(
    client: OkHttpClient,
    baseUrl: String,
    headers: Headers,
    dbType: String,
): BookOasisMenu = withContext(Dispatchers.IO) {
    require(bookOasisDashboardOrigin(baseUrl) != null && dbType in setOf("general", "adult"))
    val boundedClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build()
    suspend fun get(path: String): JSONObject {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().addQueryParameter("type", dbType).build()
        val request = Request.Builder().url(url).headers(headers)
            .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store").get().build()
        return boundedClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("BookOasis menu: HTTP ${response.code}")
            JSONObject(response.body.string()).also {
                if (!it.optBoolean("success")) throw IOException("BookOasis menu request failed")
            }
        }
    }
    val libraries = async { get("/api/media/libraries") }
    val plugins = async { get("/api/media/category-plugins") }
    parseBookOasisMenu(dbType, libraries.await(), plugins.await())
}

internal fun parseBookOasisMenu(dbType: String, libraries: JSONObject, plugins: JSONObject): BookOasisMenu {
    require(dbType in setOf("general", "adult"))
    fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
    fun JSONArray?.rows(): List<JSONObject> = if (this == null) {
        emptyList()
    } else {
        (0 until length()).mapNotNull { optJSONObject(it) }
    }
    return BookOasisMenu(
        dbType,
        libraries.optJSONArray("libraries").rows().mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val title = row.text("name") ?: return@mapNotNull null
            BookOasisLibraryMenu(id, title, dbType, row.text("group_id"))
        }.distinctBy { it.id },
        plugins.optJSONArray("category_plugins").rows().mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val title = row.text("title") ?: row.text("name") ?: return@mapNotNull null
            BookOasisPluginMenu(id, title, dbType, row.text("group_id"))
        }.distinctBy { it.id },
        libraries.optJSONArray("groups").rows().mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val title = row.text("name") ?: return@mapNotNull null
            BookOasisMenuGroup(id, title)
        }.distinctBy { it.id },
    )
}

internal fun bookOasisCatalogUrl(
    baseUrl: String,
    dbType: String,
    libraryId: String,
    page: Int,
    query: String,
    sort: String,
    limit: Int,
): String {
    require(dbType in setOf("general", "adult") && libraryId.isNotBlank())
    return "${baseUrl.trimEnd('/')}/api/media/list".toHttpUrl().newBuilder()
        .addQueryParameter("type", dbType).addQueryParameter("library_id", libraryId)
        .addQueryParameter("page", page.coerceAtLeast(1).toString()).addQueryParameter("limit", limit.toString())
        .addQueryParameter("sort", sort).apply { if (query.isNotBlank()) addQueryParameter("search", query) }
        .build().toString()
}

/** The server's existing plugin-only entry preserves its own search/API/UI, without a web home. */
internal fun bookOasisPluginUrl(baseUrl: String, pluginId: String, dbType: String): String {
    require(bookOasisDashboardOrigin(baseUrl) != null && pluginId.isNotBlank() && dbType in setOf("general", "adult"))
    return "${baseUrl.trimEnd('/')}/".toHttpUrl().newBuilder()
        .addQueryParameter("kiosk", "1").addQueryParameter("plugin", pluginId).addQueryParameter("type", dbType)
        .build().toString()
}
