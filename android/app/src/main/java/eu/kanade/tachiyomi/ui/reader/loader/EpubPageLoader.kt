package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle
import mihon.core.archive.EpubReader
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.io.StringReader

/**
 * Loader used to load a chapter from a .epub file.
 *
 * Text-based EPUBs are rendered as regular Mihon pages. EPUB spine documents
 * are processed one at a time so the full book text is never retained in heap.
 * Image-only EPUBs keep the original image extraction behavior.
 */
internal class EpubPageLoader(
    private val reader: EpubReader,
    private val style: TextReaderStyle,
    private val cacheDir: File,
    private val paginationCacheKey: String? = null,
    private val extractedTextCacheKey: String? = null,
) : PageLoader() {

    override var isLocal: Boolean = true
    override val usesTextReaderStyle: Boolean = true

    private var pageStore: TextPageStore? = null

    override suspend fun getPages(): List<ReaderPage> {
        check(!isRecycled)

        pageStore?.close()
        pageStore = null

        val store = TextPageStore(cacheDir, paginationCacheKey)
        return try {
            if (store.hasCachedPages) {
                pageStore = store
                return store.getCachedPages().mapIndexed { index, cached ->
                    TextReaderPage(
                        index = index,
                        pageTextProvider = { store.read(cached.ref) },
                        initialStyle = style,
                        anchor = cached.anchor,
                    )
                }
            }

            val textPages = buildTextPages(store)
            if (textPages.isNotEmpty()) {
                store.finishWriting()
                pageStore = store
                textPages
            } else {
                store.close()
                reader.getImagesFromPages().mapIndexed { index, path ->
                    ReaderPage(index).apply {
                        stream = { reader.getInputStream(path)!! }
                        status = Page.State.Ready
                    }
                }
            }
        } catch (e: Throwable) {
            store.close()
            throw e
        }
    }

    private fun buildTextPages(store: TextPageStore): MutableList<ReaderPage> {
        val extractedCache = extractedTextCacheKey?.let { EpubExtractedTextCache(cacheDir, it) }
        val result = mutableListOf<ReaderPage>()
        if (extractedCache?.isComplete() == true) {
            extractedCache.forEachDocument { documentIndex, text -> paginateDocument(documentIndex, text, store, result) }
            return result
        }

        val packageHref = reader.getPackageHref()
        val packageDocument = reader.getPackageDocument(packageHref)
        val manifest = packageDocument.select("manifest > item")
            .filter { item ->
                val mediaType = item.attr("media-type")
                mediaType == "application/xhtml+xml" || mediaType == "text/html"
            }
            .associateBy { it.attr("id") }
        val spine = packageDocument.select("spine > itemref")
            .map { it.attr("idref") }

        val cacheWriter = extractedCache?.writer()
        try {
        spine.forEachIndexed { documentIndex, id ->
            val href = manifest[id]?.attr("href").orEmpty()
            if (href.isBlank()) return@forEachIndexed

            openEntry(packageHref, href)?.use { input ->
                val text = extractText(input)
                if (text.isBlank()) return@use
                cacheWriter?.append(documentIndex, text)
                paginateDocument(documentIndex, text, store, result)
            }
        }
        cacheWriter?.finish()
        } finally {
            cacheWriter?.close()
        }
        return result
    }

    private fun paginateDocument(documentIndex: Int, text: String, store: TextPageStore, result: MutableList<ReaderPage>) {
        StringReader(text).use { textReader ->
            TextPageRenderer.paginate(textReader, style) { page ->
                val anchor = TextReaderAnchor(documentIndex, page.startOffset, page.endOffsetExclusive)
                val ref = store.append(page.text, anchor)
                result += TextReaderPage(result.size, { store.read(ref) }, style, anchor)
            }
        }
    }

    private fun extractText(input: InputStream): String {
        val pageDoc = Jsoup.parse(input, null, "")
        pageDoc.select("br").append("\n")
        pageDoc.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote").append("\n")
        return pageDoc.body().wholeText()
            ?.replace(Regex("[ \\t]+\\n"), "\n")
            ?.replace(Regex("\\n{3,}"), "\n\n")
            ?.trim()
            .orEmpty()
    }

    private fun openEntry(packageHref: String, href: String): InputStream? {
        val resolved = resolveEntryPath(packageHref, href)
        val candidates = linkedSetOf(
            resolved,
            resolved.replace('\\', '/'),
            resolved.replace('/', '\\'),
        )
        return candidates.firstNotNullOfOrNull { path ->
            reader.getInputStream(path)
        }
    }

    private fun resolveEntryPath(packageHref: String, href: String): String {
        val relative = href
            .substringBefore('#')
            .substringBefore('?')
            .trimStart('/', '\\')
        val separator = if ('\\' in packageHref) '\\' else '/'
        val base = packageHref.substringBeforeLast(separator, "")
        val combined = if (base.isBlank()) relative else "$base$separator$relative"
        val parts = combined.split('/', '\\')
        val normalized = ArrayDeque<String>()

        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeLast()
                else -> normalized.addLast(part)
            }
        }
        return normalized.joinToString(separator.toString())
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        pageStore?.close()
        pageStore = null
        super.recycle()
        reader.close()
    }
}
