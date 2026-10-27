package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.TextReaderStyle
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class TextPageLoader(
    private val file: UniFile,
    private val style: TextReaderStyle,
    private val cacheDir: File,
) : PageLoader() {

    override var isLocal: Boolean = true
    override val usesTextReaderStyle: Boolean = true

    private var pageStore: TextPageStore? = null

    override suspend fun getPages(): List<ReaderPage> {
        pageStore?.close()
        pageStore = null

        val store = TextPageStore(cacheDir)
        return try {
            val pages = mutableListOf<ReaderPage>()
            openDecodedReader().use { reader ->
                TextPageRenderer.paginate(reader, style) { page ->
                    val ref = store.append(page.text)
                    pages += TextReaderPage(
                        index = pages.size,
                        pageTextProvider = { store.read(ref) },
                        initialStyle = style,
                        anchor = TextReaderAnchor(
                            documentIndex = 0,
                            startOffset = page.startOffset,
                            endOffsetExclusive = page.endOffsetExclusive,
                        ),
                    )
                }
            }
            store.finishWriting()
            pageStore = store
            pages
        } catch (e: Throwable) {
            store.close()
            throw e
        }
    }

    override fun recycle() {
        pageStore?.close()
        pageStore = null
        super.recycle()
    }

    private fun openDecodedReader(): Reader {
        val charset = detectCharset()
        return InputStreamReader(file.openInputStream().buffered(), charset)
    }

    private fun detectCharset(): Charset {
        val prefix = ByteArray(3)
        val prefixSize = file.openInputStream().buffered().use { input ->
            input.read(prefix)
        }

        if (
            prefixSize >= 3 &&
            prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte()
        ) {
            return StandardCharsets.UTF_8
        }
        if (prefixSize >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }
        if (prefixSize >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }

        return if (isValidUtf8()) {
            StandardCharsets.UTF_8
        } else {
            Charset.forName("MS949")
        }
    }

    private fun isValidUtf8(): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            InputStreamReader(file.openInputStream().buffered(), decoder).use { reader ->
                val buffer = CharArray(8 * 1024)
                while (reader.read(buffer) >= 0) {
                    // Validation only. Decoded characters are intentionally discarded.
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
