package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextReaderStyleCoordinatorTest {

    @Test
    fun `repagination anchors to start of visible text page`() {
        assertEquals(
            TextReaderAnchor(documentIndex = 2, startOffset = 120, endOffsetExclusive = 121),
            readingStartAnchor(
                TextReaderAnchor(documentIndex = 2, startOffset = 120, endOffsetExclusive = 280),
            ),
        )
    }
}
