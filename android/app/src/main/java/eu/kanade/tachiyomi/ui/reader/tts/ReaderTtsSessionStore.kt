package eu.kanade.tachiyomi.ui.reader.tts

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.loader.TextReaderPage
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object ReaderTtsSessionStore {

    data class PageEntry(
        val readerPageIndex: Int,
        val documentIndex: Int,
        val sourceStartOffset: Long,
        val sourceEndOffsetExclusive: Long,
        val fileOffset: Long,
        val byteLength: Int,
    )

    data class SessionInfo(
        val id: String,
        val pages: List<PageEntry>,
    )

    class SessionReader(
        private val contentFile: File,
        val pages: List<PageEntry>,
    ) : Closeable {
        fun readPage(position: Int): String {
            val entry = pages[position]
            val bytes = ByteArray(entry.byteLength)
            RandomAccessFile(contentFile, "r").use { input ->
                input.seek(entry.fileOffset)
                input.readFully(bytes)
            }
            return String(bytes, StandardCharsets.UTF_8)
        }

        override fun close() = Unit
    }

    fun create(
        context: Context,
        pages: List<TextReaderPage>,
    ): SessionInfo {
        val dir = sessionDir(context)
        pruneStale(dir)

        val id = UUID.randomUUID().toString()
        val contentFile = File(dir, "$id.utf8")
        val indexFile = File(dir, "$id.idx")
        val entries = ArrayList<PageEntry>(pages.size)

        RandomAccessFile(contentFile, "rw").use { output ->
            pages.forEach { page ->
                val text = page.readText()
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                val fileOffset = output.filePointer
                output.write(bytes)
                entries += PageEntry(
                    readerPageIndex = page.index,
                    documentIndex = page.anchor.documentIndex,
                    sourceStartOffset = page.anchor.startOffset,
                    sourceEndOffsetExclusive = page.anchor.endOffsetExclusive,
                    fileOffset = fileOffset,
                    byteLength = bytes.size,
                )
            }
            output.fd.sync()
        }

        indexFile.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            entries.forEach { entry ->
                writer.append(entry.readerPageIndex.toString())
                    .append('\t')
                    .append(entry.documentIndex.toString())
                    .append('\t')
                    .append(entry.sourceStartOffset.toString())
                    .append('\t')
                    .append(entry.sourceEndOffsetExclusive.toString())
                    .append('\t')
                    .append(entry.fileOffset.toString())
                    .append('\t')
                    .append(entry.byteLength.toString())
                    .append('\n')
            }
        }
        return SessionInfo(id, entries)
    }

    fun open(context: Context, id: String): SessionReader? {
        val dir = sessionDir(context)
        val contentFile = File(dir, "$id.utf8")
        val indexFile = File(dir, "$id.idx")
        if (!contentFile.isFile || !indexFile.isFile) return null

        val pages = indexFile.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 6) return@mapNotNull null
                PageEntry(
                    readerPageIndex = parts[0].toIntOrNull() ?: return@mapNotNull null,
                    documentIndex = parts[1].toIntOrNull() ?: return@mapNotNull null,
                    sourceStartOffset = parts[2].toLongOrNull() ?: return@mapNotNull null,
                    sourceEndOffsetExclusive = parts[3].toLongOrNull() ?: return@mapNotNull null,
                    fileOffset = parts[4].toLongOrNull() ?: return@mapNotNull null,
                    byteLength = parts[5].toIntOrNull() ?: return@mapNotNull null,
                )
            }.toList()
        }
        if (pages.isEmpty()) return null
        return SessionReader(contentFile, pages)
    }

    fun delete(context: Context, id: String?) {
        if (id.isNullOrBlank()) return
        val dir = sessionDir(context)
        File(dir, "$id.utf8").delete()
        File(dir, "$id.idx").delete()
    }

    private fun sessionDir(context: Context): File {
        return File(context.cacheDir, "reader_tts_sessions").apply { mkdirs() }
    }

    private fun pruneStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
        dir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private const val STALE_FILE_AGE_MS = 24L * 60L * 60L * 1000L
}
