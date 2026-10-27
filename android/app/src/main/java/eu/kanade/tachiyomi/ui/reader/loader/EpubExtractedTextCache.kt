package eu.kanade.tachiyomi.ui.reader.loader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Style-independent, disk-backed EPUB spine text. A cache is visible only after atomic completion. */
internal class EpubExtractedTextCache(
    cacheRoot: File,
    sourceIdentity: String,
) {
    private val directory = File(cacheRoot, "epub_extracted_text").apply { mkdirs() }
    private val id = sourceIdentity.sha256()
    private val cacheFile = File(directory, "$id.txtc")

    fun isComplete(): Boolean = cacheFile.isFile && cacheFile.length() >= HEADER_BYTES

    fun forEachDocument(block: (Int, String) -> Unit) {
        DataInputStream(BufferedInputStream(FileInputStream(cacheFile))).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == VERSION) { "Invalid EPUB text cache" }
            while (true) {
                val index = input.readInt()
                if (index == END) break
                val size = input.readInt()
                check(index >= 0 && size in 0..MAX_DOCUMENT_BYTES) { "Invalid EPUB text cache entry" }
                val bytes = ByteArray(size)
                input.readFully(bytes)
                block(index, String(bytes, StandardCharsets.UTF_8))
            }
        }
    }

    fun writer(): Writer = Writer(File.createTempFile("$id-", ".part", directory), cacheFile)

    class Writer internal constructor(private val temporary: File, private val target: File) : AutoCloseable {
        private val output = DataOutputStream(BufferedOutputStream(FileOutputStream(temporary)))
        private var finished = false

        init {
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
        }

        fun append(documentIndex: Int, text: String) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(documentIndex)
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun finish() {
            output.writeInt(END)
            output.flush()
            output.close()
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (target.exists() && !target.delete()) throw IOException("Unable to replace EPUB text cache")
            if (!temporary.renameTo(target)) throw IOException("Unable to publish EPUB text cache")
            finished = true
        }

        override fun close() {
            runCatching { output.close() }
            if (!finished) temporary.delete()
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAGIC = 0x45505458
        const val VERSION = 1
        const val END = -1
        const val HEADER_BYTES = 8L
        const val MAX_DOCUMENT_BYTES = 128 * 1024 * 1024
    }
}
