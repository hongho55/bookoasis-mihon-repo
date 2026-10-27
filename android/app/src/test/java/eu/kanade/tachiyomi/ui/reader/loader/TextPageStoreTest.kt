package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TextPageStoreTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `persistent page cache restores text and anchors`() {
        val firstAnchor = TextReaderAnchor(documentIndex = 1, startOffset = 0, endOffsetExclusive = 5)
        val secondAnchor = TextReaderAnchor(documentIndex = 1, startOffset = 5, endOffsetExclusive = 10)

        TextPageStore(cacheDir, "book-style-key").use { store ->
            store.append("first", firstAnchor)
            store.append("second", secondAnchor)
            store.finishWriting()
        }

        TextPageStore(cacheDir, "book-style-key").use { restored ->
            assertTrue(restored.hasCachedPages)
            val pages = restored.getCachedPages()
            assertEquals(listOf(firstAnchor, secondAnchor), pages.map { it.anchor })
            assertEquals(listOf("first", "second"), pages.map { restored.read(it.ref) })
        }
    }

    @Test
    fun `different style key does not reuse cache`() {
        TextPageStore(cacheDir, "style-one").use { store ->
            store.append(
                "page",
                TextReaderAnchor(documentIndex = 0, startOffset = 0, endOffsetExclusive = 4),
            )
            store.finishWriting()
        }

        TextPageStore(cacheDir, "style-two").use { store ->
            assertTrue(!store.hasCachedPages)
        }
    }
}
