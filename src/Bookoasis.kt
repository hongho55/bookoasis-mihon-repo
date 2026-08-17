package eu.kanade.tachiyomi.extension.all.bookoasis

import android.net.Uri
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

@Source
abstract class Bookoasis :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val username: String
        get() = preferences.getString(PREF_USERNAME, "").orEmpty()

    private val password: String
        get() = preferences.getString(PREF_PASSWORD, "").orEmpty()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request().newBuilder()
        if (username.isNotEmpty()) {
            request.header("Authorization", Credentials.basic(username, password))
        }

        val response = chain.proceed(request.build())
        if (response.code == 401) {
            response.close()
            throw IOException("BookOasis authentication failed. Check the source username and password.")
        }
        response
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_USERNAME
                title = "Username"
                summary = "BookOasis account username"
            },
        )
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_PASSWORD
                title = "Password"
                summary = "BookOasis account password"
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            },
        )
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getSeriesPage(page, sort = "asc")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeriesPage(page, sort = "latest")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getSeriesPage(page, query = query, sort = "asc")

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val ref = parseSeriesRef(url.toString()) ?: return null
        val detail = getDetail(ref)
        return detail.toSManga(ref)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val ref = parseSeriesRef(manga.url) ?: throw IOException("Invalid BookOasis series URL")
        val detail = getDetail(ref)

        val updatedManga = if (fetchDetails) detail.toSManga(ref) else manga
        val updatedChapters = if (fetchChapters) detail.books.toChapters() else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bookId = parseBookId(chapter.url) ?: throw IOException("Invalid BookOasis book URL")
        val infoUrl = "$baseUrl/app-opds/api/media/books/$bookId/info".toHttpUrl().newBuilder()
            .addQueryParameter("type", DB_TYPE)
            .build()
        val info = client.newCall(GET(infoUrl, headers)).await().parseAs<BookInfoResponse>()
        if (!info.success || info.totalPages <= 0) {
            throw IOException("BookOasis did not return pages for this book")
        }

        return (0 until info.totalPages).map { pageIndex ->
            Page(
                index = pageIndex,
                imageUrl = streamUrl(bookId, pageIndex),
            )
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String {
        val bookId = parseBookId(chapter.url) ?: return super.getChapterUrl(chapter)
        return streamUrl(bookId, 0)
    }

    private suspend fun getSeriesPage(
        page: Int,
        query: String = "",
        sort: String,
    ): MangasPage {
        val url = "$baseUrl/app-opds/api/media/list".toHttpUrl().newBuilder()
            .addQueryParameter("type", DB_TYPE)
            .addQueryParameter("library_id", LIBRARY_ALL)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)
            }
            .build()

        val response = client.newCall(GET(url, headers)).await().parseAs<SeriesListResponse>()
        if (!response.success) throw IOException("BookOasis returned an unsuccessful series response")
        return MangasPage(
            mangas = response.series.map(::toSManga),
            hasNextPage = response.hasMore,
        )
    }

    private suspend fun getDetail(ref: SeriesRef): DetailResponse {
        val url = "$baseUrl/app-opds/api/media/detail".toHttpUrl().newBuilder()
            .addQueryParameter("type", DB_TYPE)
            .addQueryParameter("library_id", ref.libraryId.toString())
            .addQueryParameter("series", ref.seriesName)
            .build()
        val response = client.newCall(GET(url, headers)).await().parseAs<DetailResponse>()
        if (!response.success) throw IOException("BookOasis returned an unsuccessful detail response")
        return response
    }

    private fun toSManga(series: SeriesDto): SManga {
        val seriesName = series.seriesName.ifBlank { series.displayName }
        return SManga.create().apply {
            url = seriesUrl(series.libraryId, seriesName)
            title = series.displayName.ifBlank { seriesName }
            author = series.author
            genre = listOf(series.genre, series.tags)
                .filter(String::isNotBlank)
                .joinToString(", ")
            thumbnail_url = absoluteUrl(series.coverImage)
            status = if (series.isCompleted == 1) SManga.COMPLETED else SManga.ONGOING
        }
    }

    private fun DetailResponse.toSManga(ref: SeriesRef): SManga {
        val seriesName = meta.seriesName.ifBlank { ref.seriesName }
        return SManga.create().apply {
            url = seriesUrl(ref.libraryId, seriesName)
            title = meta.seriesAlias.ifBlank { seriesName }
            author = meta.author
            description = meta.summary
            genre = listOf(meta.genre, meta.tags)
                .filter(String::isNotBlank)
                .joinToString(", ")
            thumbnail_url = absoluteUrl(meta.coverImage)
            status = if (meta.isCompleted == 1) SManga.COMPLETED else SManga.ONGOING
        }
    }

    private fun List<BookDto>.toChapters(): List<SChapter> = mapIndexed { index, book ->
        SChapter.create().apply {
            url = bookUrl(book.id)
            name = book.title.ifBlank { "Book ${index + 1}" }
            chapter_number = index + 1F
            scanlator = book.fileFormat.uppercase().takeIf(String::isNotBlank)
        }
    }

    private fun seriesUrl(libraryId: Int, seriesName: String): String = "/app-opds/series/$libraryId/${Uri.encode(seriesName)}"

    private fun bookUrl(bookId: Int): String = "/app-opds/book/$bookId"

    private fun streamUrl(bookId: Int, pageIndex: Int): String = "$baseUrl/app-opds/api/media/stream?db_type=$DB_TYPE&book_id=$bookId&page_idx=$pageIndex"

    private fun parseSeriesRef(url: String): SeriesRef? {
        val normalized = if (url.startsWith("http")) url else baseUrl + url
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments
        if (segments.size != 4 || segments[0] != "app-opds" || segments[1] != "series") return null
        return SeriesRef(
            libraryId = segments[2].toIntOrNull() ?: return null,
            seriesName = segments[3],
        )
    }

    private fun parseBookId(url: String): Int? {
        val normalized = if (url.startsWith("http")) url else baseUrl + url
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments
        if (segments.size != 3 || segments[0] != "app-opds" || segments[1] != "book") return null
        return segments[2].toIntOrNull()
    }

    private fun absoluteUrl(value: String): String? = value.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
    }

    private data class SeriesRef(
        val libraryId: Int,
        val seriesName: String,
    )

    private companion object {
        const val DB_TYPE = "general"
        const val LIBRARY_ALL = "all"
        const val PAGE_SIZE = 30
        const val PREF_USERNAME = "bookoasis_username"
        const val PREF_PASSWORD = "bookoasis_password"
    }
}
