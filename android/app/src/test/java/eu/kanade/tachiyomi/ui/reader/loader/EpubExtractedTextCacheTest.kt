package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EpubExtractedTextCacheTest {
    @TempDir lateinit var cacheDir: File

    @Test
    fun `completed cache reuses extracted documents independent of typography`() {
        val source = "book:epub:file-v1"
        val cache = EpubExtractedTextCache(cacheDir, source)
        assertFalse(cache.isComplete())
        cache.writer().use { writer ->
            writer.append(0, "첫 번째 장\n본문")
            writer.append(4, "다섯 번째 장")
            writer.finish()
        }
        assertTrue(cache.isComplete())
        val restored = mutableListOf<Pair<Int, String>>()
        EpubExtractedTextCache(cacheDir, source).forEachDocument { index, text -> restored += index to text }
        assertEquals(listOf(0 to "첫 번째 장\n본문", 4 to "다섯 번째 장"), restored)
        assertFalse(EpubExtractedTextCache(cacheDir, "book:epub:file-v2").isComplete())
    }

    @Test
    fun `abandoned extraction never publishes partial cache`() {
        val cache = EpubExtractedTextCache(cacheDir, "source")
        cache.writer().use { it.append(0, "partial") }
        assertFalse(cache.isComplete())
        assertTrue(File(cacheDir, "epub_extracted_text").listFiles().orEmpty().none { it.extension == "txtc" })
    }
}
