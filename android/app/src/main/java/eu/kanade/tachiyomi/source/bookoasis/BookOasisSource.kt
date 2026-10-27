package eu.kanade.tachiyomi.source.bookoasis

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Built-in client for the documented BookOasis App-OPDS JSON compatibility API. */
internal class BookOasisSource(private val profile: Int) : HttpSource(), ConfigurableSource {
    override val id: Long = BookOasisLegacyIdentity.sourceIds[profile - 1]
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val name: String
        get() = getSourcePreferences().getString(NAME_KEY, "")?.trim()?.takeIf(String::isNotEmpty)
            ?: if (profile == 1) "BookOasis" else "BookOasis ($profile)"

    override val baseUrl: String
        get() = normalizeServerRoot(getSourcePreferences().getString(ADDRESS_KEY, "").orEmpty())

    private val libraryType: String
        get() = getSourcePreferences().getString(TYPE_KEY, "general") ?: "general"

    override fun headersBuilder(): Headers.Builder {
        val username = getSourcePreferences().getString(USERNAME_KEY, "").orEmpty()
        val password = getSourcePreferences().getString(PASSWORD_KEY, "").orEmpty()
        return super.headersBuilder().apply {
            if (username.isNotBlank()) {
                val token = Base64.getEncoder().encodeToString(
                    "$username:$password".toByteArray(StandardCharsets.UTF_8),
                )
                set("Authorization", "Basic $token")
            }
            set("Accept", "application/json")
        }
    }

    internal fun currentHeaders(): Headers = headersBuilder().build()

    internal fun headersForUrl(url: String): Headers = headersBuilder().apply {
        if (!sameBookOasisOrigin(url, baseUrl)) {
            removeAll("Authorization")
            removeAll("Cookie")
        }
    }.build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = ADDRESS_KEY
                title = "Server address"
                summary = "BookOasis server root URL"
                dialogTitle = title
                setDefaultValue("")
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = USERNAME_KEY
                title = "Username"
                setDefaultValue("")
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PASSWORD_KEY
                title = "Password"
                setDefaultValue("")
                setOnBindEditTextListener { it.inputType = 0x81 }
            },
        )
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = TYPE_KEY
                title = "Library"
                entries = arrayOf("General", "Adult", "Both")
                entryValues = arrayOf("general", "adult", "both")
                setDefaultValue("general")
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = NAME_KEY
                title = "Display name"
                setDefaultValue("")
            },
        )
    }

    override fun popularMangaRequest(page: Int): Request = listRequest(page, "", "asc")
    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, "", "desc")
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = listRequest(page, query, "asc")

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListPage(page, "", "asc")
    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListPage(page, "", "desc")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchListPage(page, query, "asc")

    private suspend fun fetchListPage(page: Int, query: String, sort: String): MangasPage {
        // The native catalog uses the full web API (App-OPDS lists comics only).
        // Establish the same account session before listing documents, without a WebView.
        BookOasisDashboardSession.login(this)
        val types = if (libraryType == "both") listOf("general", "adult") else listOf(libraryType)
        val pages = types.map { type ->
            client.newCall(listRequest(page, query, sort, type)).await().use { parseSeriesPage(it, type) }
        }
        val mangas = pages.flatMap { it.mangas }.distinctBy { it.url }
        return MangasPage(mangas, pages.any { it.hasNextPage })
    }

    internal fun configuredLibraryTypes(): List<String> =
        if (libraryType == "both") listOf("general", "adult") else listOf(libraryType)

    internal suspend fun getLibraryManga(page: Int, query: String, library: BookOasisLibraryMenu): MangasPage {
        require(library.dbType in configuredLibraryTypes())
        BookOasisDashboardSession.login(this)
        val url = bookOasisCatalogUrl(baseUrl, library.dbType, library.id, page, query, "asc", PAGE_SIZE)
        return client.newCall(GET(url, currentHeaders())).await().use { parseSeriesPage(it, library.dbType) }
    }

    private fun listRequest(
        page: Int,
        query: String,
        sort: String,
        type: String = if (libraryType == "adult") "adult" else "general",
    ): Request = "$baseUrl/api/media/list".toHttpUrl()
        .newBuilder()
        .addQueryParameter("type", type)
        .addQueryParameter("library_id", "all")
        .addQueryParameter("page", page.coerceAtLeast(1).toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .addQueryParameter("sort", sort)
        .apply { if (query.isNotBlank()) addQueryParameter("search", query) }
        .build().let { GET(it.toString(), headersBuilder().build()) }

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesPage(response)
    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesPage(response)
    override fun searchMangaParse(response: Response): MangasPage = parseSeriesPage(response)

    private fun parseSeriesPage(
        response: Response,
        type: String = response.request.url.queryParameter("type") ?: if (libraryType ==
            "adult"
        ) {
            "adult"
        } else {
            "general"
        },
    ): MangasPage {
        val root = JSONObject(response.body.string())
        if (!root.optBoolean(
                "success",
                false,
            )
        ) {
            throw IllegalStateException(root.optString("error", "BookOasis request failed"))
        }
        val series = root.optJSONArray("series") ?: JSONArray()
        val mangas = buildList {
            for (i in 0 until series.length()) {
                val item = series.optJSONObject(i) ?: continue
                val title = item.optString("display_name").ifBlank { item.optString("series_name") }
                if (title.isBlank()) continue
                val seriesName = item.optString("series_name", title)
                val libraryId = item.optString("library_id", "all")
                val representative = item.optLong("representative_book_id", -1L)
                val manga = SManga.create().apply {
                    this.title = title
                    url = detailPath(type, libraryId, seriesName, representative)
                    thumbnail_url = coverUrl(item.optString("cover_image"))
                    author = item.optString("author")
                    description = item.optString("representative_title")
                }
                add(manga)
            }
        }
        return MangasPage(mangas, root.optBoolean("has_more", mangas.size >= PAGE_SIZE))
    }

    internal fun dashboardDetailUrl(mangaPath: String): String =
        baseUrl + bookOasisDashboardDetailPath(mangaPath)

    override fun mangaDetailsRequest(manga: SManga): Request = GET(
        dashboardDetailUrl(manga.url),
        headersBuilder().build(),
    )
    override fun mangaDetailsParse(response: Response): SManga {
        val root = JSONObject(response.body.string())
        if (!root.optBoolean(
                "success",
                false,
            )
        ) {
            throw IllegalStateException(root.optString("error", "BookOasis detail request failed"))
        }
        val meta = root.optJSONObject("meta") ?: JSONObject()
        return SManga.create().apply {
            title = meta.optString("series_name", meta.optString("title"))
            author = meta.optString("author")
            description = meta.optString("description")
            thumbnail_url = coverUrl(meta.optString("cover_image"))
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(
        dashboardDetailUrl(manga.url),
        headersBuilder().build(),
    )
    override fun chapterListParse(response: Response): List<SChapter> {
        val root = JSONObject(response.body.string())
        if (!root.optBoolean(
                "success",
                false,
            )
        ) {
            throw IllegalStateException(root.optString("error", "BookOasis chapter request failed"))
        }
        val books = root.optJSONArray("books") ?: return emptyList()
        val dbType = root.optJSONObject("meta")?.optString("type")
            ?: if (libraryType == "adult") "adult" else "general"
        val chapters = buildList {
            for (i in 0 until books.length()) {
                val book = books.optJSONObject(i) ?: continue
                val bookId = book.optLong("id", -1L)
                if (bookId <= 0) continue
                val format = book.optString("file_format").lowercase()
                if (format !in SUPPORTED_FORMATS) continue
                add(
                    SChapter.create().apply {
                        name = book.optString("title").ifBlank { "Book $bookId" }
                        url = "/app-opds/api/media/books/$bookId/info?type=$dbType" +
                            if (format in DOCUMENT_FORMATS) "&bo_format=$format" else ""
                        chapter_number = (books.length() - i).toFloat()
                        date_upload = 0L
                    },
                )
            }
        }
        return chapters.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // The web stream is read-only. Establish its normal web session before
        // the existing image prefetch/download engine requests any pages.
        BookOasisDashboardSession.login(this)
        return client.newCall(pageListRequest(chapter)).await().use(::pageListParse)
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headersBuilder().build())
    override fun pageListParse(response: Response): List<Page> {
        val root = JSONObject(response.body.string())
        if (!root.optBoolean(
                "success",
                false,
            )
        ) {
            throw IllegalStateException(root.optString("error", "BookOasis book info request failed"))
        }
        val totalPages = root.optInt("total_pages", 0).coerceAtLeast(0)
        val bookId = response.request.url.encodedPath
            .substringAfterLast("/books/").substringBefore('/')
        val type = response.request.url.queryParameter("type") ?: "general"
        return (0 until totalPages).map { index ->
            Page(index, bookOasisReadOnlyPageUrl(baseUrl, type, bookId, index))
        }
    }

    internal suspend fun dashboardSelection(
        selection: BookOasisDashboardRequest,
        webCookie: String,
    ): Pair<SManga, String> {
        val url = "$baseUrl/api/media/books/${selection.bookId}/reader-info".toHttpUrl().newBuilder()
            .addQueryParameter("type", selection.dbType).build()
        val request = GET(url.toString(), currentHeaders().newBuilder().set("Cookie", webCookie).build())
        return client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).await().use { response ->
                check(response.isSuccessful) { "책 정보를 읽지 못했습니다. 서버 연결을 확인해 주세요." }
                val root = JSONObject(response.body.string())
                check(root.optBoolean("success")) { "책 정보를 읽지 못했습니다." }
                val book = requireNotNull(root.optJSONObject("book"))
                check(book.optLong("id") == selection.bookId)
                val format = book.optString("file_format").lowercase()
                check(format in SUPPORTED_FORMATS) { "이 형식은 네이티브 리더에서 지원하지 않습니다." }
                val series = book.optString("series_name")
                val remote = SManga.create().apply {
                    title = series.ifBlank { book.optString("title", "Book ${selection.bookId}") }
                    this.url = detailPath(selection.dbType, selection.libraryId, series, selection.bookId)
                }
                val chapterUrl = "/app-opds/api/media/books/${selection.bookId}/info?type=${selection.dbType}" +
                    if (format in DOCUMENT_FORMATS) "&bo_format=$format" else ""
                remote to chapterUrl
            }
    }

    private fun apiUrl(route: String) = "$baseUrl/app-opds/api/media/$route".toHttpUrl()

    private fun detailPath(type: String, libraryId: String, series: String, representativeId: Long): String =
        apiUrl("detail").newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("library_id", libraryId)
            .addQueryParameter("series", series)
            .apply {
                if (representativeId >
                    0
                ) {
                    addQueryParameter("representative_book_id", representativeId.toString())
                }
            }
            .build().toString().removePrefix(baseUrl)

    private fun coverUrl(value: String): String? = value.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("http://") ||
            it.startsWith("https://")
        ) {
            it
        } else {
            bookOasisCoverUrl(baseUrl, it)
        }
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageUrlRequest(page: Page): Request = GET(page.url, headersForUrl(page.url))

    override fun getFilterList() = FilterList()

    companion object {
        private const val PAGE_SIZE = 30
        private const val ADDRESS_KEY = "address"
        private const val USERNAME_KEY = "username"
        private const val PASSWORD_KEY = "password"
        private const val TYPE_KEY = "library_type"
        private const val NAME_KEY = "display_name"
        private val SUPPORTED_FORMATS = setOf("zip", "cbz", "imgdir", "epub", "pdf", "txt")
        private val DOCUMENT_FORMATS = setOf("epub", "pdf", "txt")

        internal fun normalizeServerRoot(value: String): String = value.trim().trimEnd('/').let { root ->
            when {
                root.endsWith("/app-opds-adult", true) -> root.removeSuffix("/app-opds-adult")
                root.endsWith("/app-opds", true) -> root.removeSuffix("/app-opds")
                else -> root
            }
        }
    }
}

internal fun bookOasisReadOnlyPageUrl(baseUrl: String, dbType: String, bookId: String, pageIndex: Int): String =
    // App-OPDS stream GET records progress even for prefetched pages. Its web
    // equivalent does not; only the explicit Reader Bridge writer owns progress.
    "${baseUrl.trimEnd('/')}/api/media/stream".toHttpUrl().newBuilder()
        .addQueryParameter("db_type", dbType)
        .addQueryParameter("book_id", bookId)
        .addQueryParameter("page_idx", pageIndex.toString())
        .build().toString()

internal fun sameBookOasisOrigin(url: String, baseUrl: String): Boolean {
    val requestUrl = url.toHttpUrlOrNull() ?: return false
    val serverUrl = baseUrl.toHttpUrlOrNull() ?: return false
    return requestUrl.scheme == serverUrl.scheme &&
        requestUrl.host == serverUrl.host &&
        requestUrl.port == serverUrl.port
}
