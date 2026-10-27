package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.manga.model.Manga
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.app.di.appGraph
import uy.kohesive.injekt.injectLazy
import java.io.File

internal enum class DownloadedDocumentFormat(val usesTextReaderStyle: Boolean) {
    EPUB(usesTextReaderStyle = true),
    PDF(usesTextReaderStyle = false),
    TXT(usesTextReaderStyle = true),
}

internal fun downloadedDocumentFormat(extension: String?): DownloadedDocumentFormat? = when (extension?.lowercase()) {
    "epub" -> DownloadedDocumentFormat.EPUB
    "pdf" -> DownloadedDocumentFormat.PDF
    "txt" -> DownloadedDocumentFormat.TXT
    else -> null
}

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val documentFallback: (() -> PageLoader)? = null,
) : PageLoader() {

    private val context: Context by injectLazy()

    private var pageLoader: PageLoader? = null
    private val readerPreferences: ReaderPreferences by lazy { context.appGraph.readerPreferences }

    override var isLocal: Boolean = true
    override val usesTextReaderStyle: Boolean
        get() = pageLoader?.usesTextReaderStyle == true ||
            downloadedDocumentFormat(findDownloadedDocument()?.extension)?.usesTextReaderStyle == true

    override suspend fun getPages(): List<ReaderPage> {
        check(!isRecycled)

        return try {
            val pages = getDownloadedPages()
            if (pages.isNotEmpty() || documentFallback == null) {
                pages
            } else {
                getFallbackPages()
            }
        } catch (error: Throwable) {
            if (error is CancellationException || documentFallback == null) throw error
            logcat(LogPriority.WARN, error) { "Downloaded document read failed before online fallback" }
            getFallbackPages()
        }
    }

    private suspend fun getDownloadedPages(): List<ReaderPage> {
        val downloadedDocument = findDownloadedDocument()
        if (downloadedDocument != null) {
            val readableDocument = prepareDownloadedDocument(downloadedDocument)
            return when (downloadedDocumentFormat(readableDocument.extension)) {
                DownloadedDocumentFormat.EPUB -> getPagesFromEpub(readableDocument)
                DownloadedDocumentFormat.PDF -> getPagesFromPdf(readableDocument)
                DownloadedDocumentFormat.TXT -> getPagesFromText(readableDocument)
                null -> error("Unsupported downloaded document: ${downloadedDocument.name}")
            }
        }

        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )

        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory()
        }
    }

    private fun findDownloadedDocument(): UniFile? {
        val dbChapter = chapter.chapter
        return downloadProvider.findChapterDocument(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
    }

    /**
     * Mirror SAF-backed documents into the app cache before opening them.
     * EPUB uses mmap internally, which is not guaranteed to work with every
     * document provider even though regular stream reads/writes succeed.
     */
    private fun prepareDownloadedDocument(file: UniFile): UniFile {
        val extension = file.extension?.lowercase()
            ?: error("Downloaded document has no extension: ${file.name}")
        val sourceLength = file.length()
        if (sourceLength <= 0L) {
            error("Downloaded document is empty: ${file.name}")
        }

        val cacheDir = File(context.cacheDir, "downloaded_reader").apply { mkdirs() }
        val prefix = "${source.id}-${chapter.chapter.id}-"
        val target = File(
            cacheDir,
            "$prefix$sourceLength-${file.lastModified()}.$extension",
        )

        if (!target.isFile || target.length() != sourceLength) {
            cacheDir.listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }

            val temp = File(cacheDir, "${target.name}.part")
            if (temp.exists()) temp.delete()
            file.openInputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() != sourceLength) {
                temp.delete()
                error("Downloaded document cache copy is incomplete: ${file.name}")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }

        return UniFile.fromFile(target)
            ?: error("Unable to open cached downloaded document: ${target.absolutePath}")
    }

    private suspend fun getFallbackPages(): List<ReaderPage> {
        pageLoader?.recycle()
        val fallback = documentFallback?.invoke()
            ?: error("Downloaded document fallback is unavailable")
        pageLoader = fallback
        val pages = fallback.getPages()
        isLocal = fallback.isLocal
        return pages
    }

    override fun recycle() {
        super.recycle()
        pageLoader?.recycle()
        pageLoader = null
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { pageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromEpub(file: UniFile): List<ReaderPage> {
        val loader = EpubPageLoader(
            file.epubReader(context),
            readerPreferences.textReaderStyle(context),
            context.cacheDir,
        ).also { pageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromText(file: UniFile): List<ReaderPage> {
        val loader = TextPageLoader(
            file,
            readerPreferences.textReaderStyle(context),
            context.cacheDir,
        ).also { pageLoader = it }
        return loader.getPages()
    }

    private suspend fun getPagesFromPdf(file: UniFile): List<ReaderPage> {
        val loader = PdfPageLoader(context, file).also { pageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        pageLoader?.loadPage(page)
    }
}
