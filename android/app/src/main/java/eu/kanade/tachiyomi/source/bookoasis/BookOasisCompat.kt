package eu.kanade.tachiyomi.source.bookoasis

import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.bookoasis.BookOasisLegacyIdentity
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import logcat.LogPriority
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val SUPPORTED_FORMATS = setOf("zip", "cbz", "imgdir", "epub", "pdf", "txt")
private val DOCUMENT_FORMATS = setOf("epub", "pdf", "txt")

internal data class BookOasisChapterRef(
    val bookId: Long,
    val dbType: String,
    val format: String,
)

internal data class BookOasisDocumentDownload(
    val extension: String,
    val request: Request,
)

private fun HttpSource.bookOasisServerBase(): String {
    val normalized = baseUrl.trimEnd('/')
    return when {
        normalized.endsWith("/app-opds-adult", ignoreCase = true) ->
            normalized.dropLast("/app-opds-adult".length)
        normalized.endsWith("/app-opds", ignoreCase = true) ->
            normalized.dropLast("/app-opds".length)
        else -> normalized
    }
}

internal fun HttpSource.bookOasisHeaders(url: String? = null) =
    (this as? BookOasisSource)?.let { source ->
        if (url == null) source.currentHeaders() else source.headersForUrl(url)
    } ?: headers.newBuilder().apply {
        if (url != null && !sameBookOasisOrigin(url, bookOasisServerBase())) {
            removeAll("Authorization")
            removeAll("Cookie")
        }
    }.build()

internal fun HttpSource.bookOasisDocumentDownloadRequest(ref: BookOasisChapterRef): Request {
    val downloadUrl = buildString {
        append(bookOasisServerBase())
        append("/app-opds/download/")
        append(ref.dbType)
        append('/')
        append(ref.bookId)
    }

    return Request.Builder()
        .url(downloadUrl)
        .headers(bookOasisHeaders(downloadUrl))
        .get()
        .build()
}

internal fun HttpSource.bookOasisDocumentDownload(chapterUrl: String): BookOasisDocumentDownload? {
    if (!isBookOasisSource()) return null
    val ref = chapterUrl.toBookOasisChapterRef() ?: return null
    return BookOasisDocumentDownload(
        extension = ref.format,
        request = bookOasisDocumentDownloadRequest(ref),
    )
}

internal fun Source.isBookOasisSource(): Boolean = BookOasisLegacyIdentity.isBookOasisId(id) || this is BookOasisSource

internal fun String.toBookOasisProgressRef(): BookOasisChapterRef? {
    toBookOasisChapterRef()?.let { return it }
    val match = Regex("""^/app-opds/api/media/books/(\d+)/info\?type=(general|adult)$""").matchEntire(this) ?: return null
    val bookId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
    return BookOasisChapterRef(bookId, match.groupValues[2], "comic")
}

internal fun String.toBookOasisChapterRef(): BookOasisChapterRef? {
    val path = substringBefore('?')
    val match = Regex("""^/(?:app-opds(?:-adult)?/)?api/media/books/(\d+)/info$""").matchEntire(path) ?: return null
    val params = parseQueryParameters(this)
    val format = params["bo_format"]?.lowercase() ?: return null
    if (format !in DOCUMENT_FORMATS) return null

    return BookOasisChapterRef(
        bookId = match.groupValues[1].toLongOrNull() ?: return null,
        dbType = params["type"].orEmpty().ifBlank { "general" },
        format = format,
    )
}

/**
 * BookOasis extension v1.4.x filters its chapter list to ZIP/CBZ.
 * Fetch the same detail JSON with the extension's authenticated client and
 * rebuild the chapter list so EPUB/PDF/TXT remain visible to the host app.
 */
internal suspend fun HttpSource.fetchBookOasisChapters(manga: SManga): List<SChapter> {
    // Upstream App-OPDS deliberately filters documents to ZIP/CBZ. The dedicated built-in
    // source already has the dashboard's normal authenticated web session: use its full detail API.
    val detailUrl = if (this is BookOasisSource) dashboardDetailUrl(manga.url) else manga.url.toAbsoluteUrl(baseUrl)
    val dbType = parseQueryParameters(detailUrl)["type"].orEmpty().ifBlank { "general" }

    val request = Request.Builder()
        .url(detailUrl)
        .headers(bookOasisHeaders(detailUrl))
        .get()
        .build()

    val root = client.newCall(request).await().use { response ->
        if (!response.isSuccessful) {
            error("BookOasis detail request failed: HTTP ${response.code}")
        }
        JSONObject(response.body.string())
    }

    val rawBooks = root.optJSONArray("books") ?: return emptyList()
    val books = buildList {
        for (index in 0 until rawBooks.length()) {
            val item = rawBooks.optJSONObject(index) ?: continue
            val id = item.optLong("id", -1L)
            val format = item.optString("file_format", "").lowercase()
            if (id <= 0L || format !in SUPPORTED_FORMATS) continue

            add(
                Triple(
                    id,
                    item.optString("title", "").ifBlank { "Book $id" },
                    format,
                ),
            )
        }
    }

    return books.mapIndexed { index, (bookId, title, format) ->
        SChapter.create().apply {
            url = buildString {
                append(if (baseUrl.trimEnd('/').endsWith("/app-opds", ignoreCase = true)) "" else "/app-opds")
                append("/api/media/books/")
                append(bookId)
                append("/info?type=")
                append(dbType)
                if (format in DOCUMENT_FORMATS) {
                    append("&bo_format=")
                    append(format)
                }
            }
            name = title
            chapter_number = (books.size - index).toFloat()
        }
    }.reversed()
}

internal suspend fun Source.fetchBookOasisChaptersOrDefault(
    manga: SManga,
    defaultChapters: List<SChapter>,
    fetchChapters: Boolean,
): List<SChapter> {
    if (!fetchChapters || this !is HttpSource || !isBookOasisSource()) return defaultChapters
    return try {
        withIOContext { fetchBookOasisChapters(manga) }
    } catch (error: Exception) {
        logcat(LogPriority.ERROR, error) {
            "BookOasis document chapter compatibility fallback failed"
        }
        defaultChapters
    }
}

private fun String.toAbsoluteUrl(baseUrl: String): String {
    return if (startsWith("http://") || startsWith("https://")) {
        this
    } else {
        baseUrl.trimEnd('/') + "/" + trimStart('/')
    }
}

private fun parseQueryParameters(urlOrPath: String): Map<String, String> {
    val rawQuery = runCatching {
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            URI(urlOrPath).rawQuery
        } else {
            urlOrPath.substringAfter('?', "")
        }
    }.getOrDefault(urlOrPath.substringAfter('?', ""))

    if (rawQuery.isNullOrBlank()) return emptyMap()

    return rawQuery.split('&')
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decodeQuery(key) to decodeQuery(value)
        }
        .toMap()
}

private fun decodeQuery(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
