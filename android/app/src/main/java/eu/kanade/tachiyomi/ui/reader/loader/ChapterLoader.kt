package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.bookoasis.bookOasisPageLoaderFactory
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val manga: Manga,
    private val source: Source,
    private val readerPreferences: ReaderPreferences,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }


    internal suspend fun reloadTextChapter(
        chapter: ReaderChapter,
        readingAnchor: TextReaderAnchor?,
        readingProgress: Float,
    ): PageLoader? {
        return withIOContext {
            val newLoader = getPageLoader(chapter)
            if (!newLoader.usesTextReaderStyle) {
                newLoader.recycle()
                return@withIOContext null
            }

            try {
                val pages = newLoader.getPages()
                    .onEach { it.chapter = chapter }
                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If a newer slider value cancelled this rebuild while getPages()
                // was working, do not publish stale pages into the active chapter.
                currentCoroutineContext().ensureActive()

                val oldLoader = chapter.pageLoader
                chapter.pageLoader = newLoader
                val anchoredPageIndex = readingAnchor?.let { anchor ->
                    val textPages = pages.filterIsInstance<TextReaderPage>()
                    textPages.firstOrNull { it.anchor.contains(anchor) }?.index
                        ?: textPages
                            .filter { it.anchor.documentIndex == anchor.documentIndex }
                            .minByOrNull { page ->
                                kotlin.math.abs(page.anchor.startOffset - anchor.startOffset)
                            }
                            ?.index
                }
                chapter.requestedPage = anchoredPageIndex
                    ?: (readingProgress.coerceIn(0f, 0.999999f) * pages.size)
                        .toInt()
                        .coerceIn(0, pages.lastIndex)
                chapter.state = ReaderChapter.State.Loaded(pages)
                oldLoader
            } catch (e: Throwable) {
                newLoader.recycle()
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        val httpSource = source as? HttpSource
        val bookOasisLoader = httpSource?.bookOasisPageLoaderFactory(
            context = context,
            chapterUrl = dbChapter.url,
            textStyle = readerPreferences.textReaderStyle(context),
        )
        val documentFallback = bookOasisLoader
            ?.takeIf { it.format == "epub" || it.format == "txt" }
            ?.create

        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
                documentFallback,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context), readerPreferences.textReaderStyle(context), context.cacheDir)
                    is Format.Pdf -> PdfPageLoader(context, format.file)
                    is Format.Text -> TextPageLoader(format.file, readerPreferences.textReaderStyle(context), context.cacheDir)
                }
            }
            bookOasisLoader != null -> bookOasisLoader.create()
            source is HttpSource -> HttpPageLoader(chapter, source, chapterCache)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
