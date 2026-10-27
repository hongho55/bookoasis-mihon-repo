package eu.kanade.tachiyomi.ui.reader.loader.bookoasis

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.bookoasis.BookOasisChapterRef
import eu.kanade.tachiyomi.source.bookoasis.bookOasisDocumentDownloadRequest
import eu.kanade.tachiyomi.source.bookoasis.isBookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.toBookOasisChapterRef
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.EpubPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle
import mihon.core.archive.epubReader
import java.io.File
import java.io.IOException

internal data class BookOasisPageLoaderFactory(
    val format: String,
    val create: () -> PageLoader,
)

internal fun HttpSource.bookOasisPageLoaderFactory(
    context: Context,
    chapterUrl: String,
    textStyle: TextReaderStyle,
): BookOasisPageLoaderFactory? {
    if (!isBookOasisSource()) return null
    val ref = chapterUrl.toBookOasisChapterRef() ?: return null
    return BookOasisPageLoaderFactory(ref.format) {
        BookOasisFilePageLoader(context, this, ref, textStyle)
    }
}

/**
 * Opens BookOasis EPUB/PDF/TXT as native Mihon reader pages.
 */
internal class BookOasisFilePageLoader(
    private val context: Context,
    private val source: HttpSource,
    private val ref: BookOasisChapterRef,
    private val textStyle: TextReaderStyle,
) : PageLoader() {

    override var isLocal: Boolean = true
    override val usesTextReaderStyle: Boolean = ref.format == "epub" || ref.format == "txt"

    private var delegate: PageLoader? = null

    override suspend fun getPages(): List<ReaderPage> {
        check(!isRecycled)

        val file = getOrDownloadFile()
        val uniFile = UniFile.fromFile(file)
            ?: error("Unable to open cached BookOasis file: ${file.absolutePath}")
        val loader = when (ref.format) {
            "epub" -> EpubPageLoader(
                reader = uniFile.epubReader(context),
                style = textStyle,
                cacheDir = context.cacheDir,
                paginationCacheKey = paginationCacheKey(file),
                extractedTextCacheKey = "bookoasis-epub-v1:${ref.dbType}:${ref.bookId}:${file.length()}:${file.lastModified()}",
            )
            "pdf" -> PdfPageLoader(context, uniFile)
            "txt" -> TextPageLoader(uniFile, textStyle, context.cacheDir)
            else -> error("Unsupported BookOasis document format: ${ref.format}")
        }

        delegate = loader
        return loader.getPages()
    }

    override suspend fun loadPage(page: ReaderPage) {
        delegate?.loadPage(page)
    }

    override fun retryPage(page: ReaderPage) {
        delegate?.retryPage(page)
    }

    override fun recycle() {
        delegate?.recycle()
        delegate = null
        super.recycle()
    }

    private suspend fun getOrDownloadFile(): File {
        val cacheDir = File(context.cacheDir, "bookoasis_reader").apply { mkdirs() }
        val target = File(cacheDir, "${ref.dbType}-${ref.bookId}.${ref.format}")
        if (target.isFile && target.length() > 0L) {
            return target
        }

        val temp = File(cacheDir, "${target.name}.part")
        if (temp.exists()) temp.delete()

        val request = source.bookOasisDocumentDownloadRequest(ref)

        source.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "BookOasis ${ref.format.uppercase()} download failed: HTTP ${response.code}",
                )
            }

            temp.outputStream().use { output ->
                response.body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("BookOasis returned an empty ${ref.format.uppercase()} file")
        }

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun paginationCacheKey(file: File): String = buildString {
        append("bookoasis:paragraph-gap-v2:")
        append(ref.dbType).append(':').append(ref.bookId).append(':')
        append(file.length()).append(':').append(file.lastModified()).append(':')
        append(textStyle.pageWidthPx).append('x').append(textStyle.pageHeightPx).append(':')
        append(textStyle.fontSize).append(':')
        append(textStyle.lineSpacingPercent).append(':')
        append(textStyle.paragraphSpacing).append(':')
        append(textStyle.horizontalMarginDp).append(':')
        append(textStyle.verticalMarginDp).append(':')
        append(textStyle.lineBreakMode.name).append(':')
        append(textStyle.fontFamily.name)
    }
}
