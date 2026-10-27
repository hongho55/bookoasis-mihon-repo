package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderResumePageTest {

    @Test
    fun `local progress is selected before the first reader layout`() {
        assertEquals(
            9,
            resolveInitialReaderPage(
                savedStatePage = -1,
                chapterRead = false,
                localLastPage = 9,
                lastPageIndex = 99,
            ),
        )
    }

    @Test
    fun `process state takes precedence and is bounded to available pages`() {
        assertEquals(
            19,
            resolveInitialReaderPage(
                savedStatePage = 30,
                chapterRead = false,
                localLastPage = 9,
                lastPageIndex = 19,
            ),
        )
    }

    @Test
    fun `completed chapter starts at the beginning without process state`() {
        assertEquals(
            0,
            resolveInitialReaderPage(
                savedStatePage = -1,
                chapterRead = true,
                localLastPage = 99,
                lastPageIndex = 99,
            ),
        )
    }
}
