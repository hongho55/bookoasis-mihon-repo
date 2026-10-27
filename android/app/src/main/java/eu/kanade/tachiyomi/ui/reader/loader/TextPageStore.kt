package eu.kanade.tachiyomi.ui.reader.loader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Disk-backed storage for text reader pages.
 *
 * Keeping page strings here avoids retaining an entire large TXT/EPUB in the
 * Java heap. Individual pages are loaded only when the viewer renders them.
 * A cache key optionally preserves the page text and anchors across reader
 * sessions so a large EPUB does not need to be extracted and paginated again.
 */
internal class TextPageStore(
    cacheRoot: File,
    cacheKey: String? = null,
) : Closeable {

    private val cacheDir = File(cacheRoot, "text_reader_pages").apply {
        mkdirs()
        pruneStaleFiles()
    }
    private val cacheId = cacheKey?.sha256()
    private val stableFile = cacheId?.let { File(cacheDir, "$it.utf8") }
    private val indexFile = cacheId?.let { File(cacheDir, "$it.idx") }
    private var file: File
    private var writer: RandomAccessFile?
    private var finalized = false
    private val recordedPages = mutableListOf<CachedPage>()
    private val cachedPages: List<CachedPage>?

    init {
        val loaded = loadCachedPages(stableFile, indexFile)
        if (loaded != null) {
            file = checkNotNull(stableFile)
            writer = null
            finalized = true
            cachedPages = loaded
            file.setLastModified(System.currentTimeMillis())
            indexFile?.setLastModified(System.currentTimeMillis())
        } else {
            stableFile?.delete()
            indexFile?.delete()
            file = if (cacheId == null) {
                File.createTempFile("pages-", ".utf8", cacheDir)
            } else {
                File.createTempFile("$cacheId-", ".part", cacheDir)
            }
            writer = RandomAccessFile(file, "rw")
            cachedPages = null
        }
    }

    val hasCachedPages: Boolean
        get() = cachedPages != null

    fun getCachedPages(): List<CachedPage> = cachedPages.orEmpty()

    @Synchronized
    fun append(text: String, anchor: TextReaderAnchor? = null): PageRef {
        val output = checkNotNull(writer) { "Text page store is already finalized" }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val offset = output.filePointer
        output.write(bytes)
        return PageRef(offset, bytes.size).also { ref ->
            if (anchor != null) recordedPages += CachedPage(ref, anchor)
        }
    }

    @Synchronized
    fun finishWriting() {
        writer?.fd?.sync()
        writer?.close()
        writer = null

        if (stableFile != null && indexFile != null) {
            val indexTemp = File.createTempFile("${cacheId}-", ".idx.part", cacheDir)
            DataOutputStream(BufferedOutputStream(indexTemp.outputStream())).use { output ->
                output.writeInt(INDEX_MAGIC)
                output.writeInt(INDEX_VERSION)
                output.writeInt(recordedPages.size)
                recordedPages.forEach { page ->
                    output.writeLong(page.ref.offset)
                    output.writeInt(page.ref.byteLength)
                    output.writeInt(page.anchor.documentIndex)
                    output.writeLong(page.anchor.startOffset)
                    output.writeLong(page.anchor.endOffsetExclusive)
                }
            }
            stableFile.delete()
            check(file.renameTo(stableFile)) { "Unable to persist text page cache" }
            indexFile.delete()
            check(indexTemp.renameTo(indexFile)) { "Unable to persist text page cache index" }
            file = stableFile
        }
        finalized = true
    }

    fun read(ref: PageRef): String {
        val bytes = ByteArray(ref.byteLength)
        RandomAccessFile(file, "r").use { input ->
            input.seek(ref.offset)
            input.readFully(bytes)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    override fun close() {
        writer?.close()
        writer = null
        if (cacheId == null || !finalized) file.delete()
    }

    internal data class PageRef(
        val offset: Long,
        val byteLength: Int,
    )

    internal data class CachedPage(
        val ref: PageRef,
        val anchor: TextReaderAnchor,
    )

    private fun loadCachedPages(data: File?, index: File?): List<CachedPage>? {
        if (data?.isFile != true || data.length() <= 0L || index?.isFile != true) return null
        return runCatching {
            DataInputStream(BufferedInputStream(index.inputStream())).use { input ->
                check(input.readInt() == INDEX_MAGIC)
                check(input.readInt() == INDEX_VERSION)
                val count = input.readInt()
                check(count in 1..MAX_CACHED_PAGES)
                List(count) {
                    val ref = PageRef(input.readLong(), input.readInt())
                    check(ref.offset >= 0 && ref.byteLength >= 0 && ref.offset + ref.byteLength <= data.length())
                    CachedPage(
                        ref = ref,
                        anchor = TextReaderAnchor(
                            documentIndex = input.readInt(),
                            startOffset = input.readLong(),
                            endOffsetExclusive = input.readLong(),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    private fun File.pruneStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
        listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val INDEX_MAGIC = 0x4D545850
        const val INDEX_VERSION = 1
        const val MAX_CACHED_PAGES = 1_000_000
        const val STALE_FILE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
