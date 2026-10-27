package eu.kanade.tachiyomi.source.bookoasis

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.bookoasis.progress.BookOasisPendingProgress
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** Dashboard selections use the same persisted series/chapter rows and downloader as the native shelf. */
@Inject
class BookOasisDashboardLibrary(
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId,
    private val mangaRepository: MangaRepository,
) {
    /** Register a catalog series without favoriting it; the native detail screen owns selection. */
    internal suspend fun importSeries(source: BookOasisSource, remote: SManga): Manga =
        withContext(Dispatchers.IO) { networkToLocalManga(remote.toDomainManga(source.id)) }

    /** Match the exact book chapter, not a series representative or its first volume. */
    internal suspend fun findLocalBook(
        source: BookOasisSource,
        bookId: Long,
        dbType: String,
        format: String,
    ): Pair<Manga, Chapter>? = withContext(Dispatchers.IO) {
        val url = "/app-opds/api/media/books/$bookId/info?type=$dbType" +
            if (format in setOf("epub", "txt", "pdf")) "&bo_format=$format" else ""
        for (manga in mangaRepository.getFavorites().filter { it.source == source.id }) {
            val chapter = getChapterByUrlAndMangaId.await(url, manga.id)
            if (chapter != null) return@withContext manga to chapter
        }
        null
    }

    internal suspend fun prepareHomeBook(source: BookOasisSource, book: BookOasisHomeBook): Pair<Manga, Chapter> {
        findLocalBook(source, book.bookId, book.dbType, book.format)?.let { return it }
        BookOasisDashboardSession.login(source)
        return prepare(
            source,
            BookOasisDashboardRequest(BookOasisDashboardAction.Open, book.bookId, book.dbType, book.libraryId),
            "",
        )
    }

    /** Supply metadata for an offline read that is not in the cached server dashboard yet. */
    internal suspend fun localHomeBooks(
        source: BookOasisSource,
        pending: List<BookOasisPendingProgress>,
    ): List<BookOasisHomeBook> =
        withContext(Dispatchers.IO) {
            pending.filter { it.sourceId == source.id }.mapNotNull { item ->
                val (manga, chapter) = findLocalBook(source, item.bookId, item.dbType, item.format)
                    ?: return@mapNotNull null
                BookOasisHomeBook(
                    bookId = item.bookId,
                    dbType = item.dbType,
                    libraryId = (source.baseUrl + manga.url).toHttpUrlOrNull()?.queryParameter("library_id") ?: "all",
                    title = chapter.name.ifBlank { manga.title },
                    seriesName = manga.title,
                    format = if (item.format == "comic") "cbz" else item.format,
                    coverUrl = manga.thumbnailUrl?.takeIf { sameBookOasisOrigin(it, source.baseUrl) },
                    author = manga.author,
                    pagesRead = item.pageIndex + 1,
                    totalPages = item.totalPages,
                    progressPercent = null,
                    completed = item.pageIndex + 1 >= item.totalPages,
                )
            }
        }

    internal suspend fun prepare(
        source: BookOasisSource,
        request: BookOasisDashboardRequest,
        webCookie: String,
    ): Pair<Manga, Chapter> = withContext(Dispatchers.IO) {
        val (remote, chapterUrl) = source.dashboardSelection(request, webCookie)
        val manga = networkToLocalManga(remote.toDomainManga(source.id))
        updateMangaFromRemote(manga, fetchChapters = true).getOrThrow()
        val chapter = getChapterByUrlAndMangaId.await(chapterUrl, manga.id)
            ?: error("선택한 책을 서재에 등록하지 못했습니다.")
        if (!manga.favorite) {
            check(
                mangaRepository.update(
                    MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis()),
                ),
            )
        }
        mangaRepository.getMangaById(manga.id) to chapter
    }
}
