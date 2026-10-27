package eu.kanade.tachiyomi.source.bookoasis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookOasisCompatTest {

    @Test
    fun `parses document chapter reference`() {
        val ref = "/api/media/books/42/info?type=adult&bo_format=EPUB".toBookOasisChapterRef()

        assertEquals(BookOasisChapterRef(bookId = 42L, dbType = "adult", format = "epub"), ref)
    }

    @Test
    fun `accepts app opds prefixed chapter path and defaults database`() {
        val ref = "/app-opds/api/media/books/7/info?bo_format=pdf".toBookOasisChapterRef()

        assertEquals(BookOasisChapterRef(bookId = 7L, dbType = "general", format = "pdf"), ref)
    }

    @Test
    fun `decodes query values`() {
        val ref = "/api/media/books/9/info?type=general%20archive&bo_format=txt".toBookOasisChapterRef()

        assertEquals("general archive", ref?.dbType)
    }

    @Test
    fun `accepts legacy adult OPDS-prefixed chapter path`() {
        val ref = "/app-opds-adult/api/media/books/101/info?type=adult&bo_format=pdf"
            .toBookOasisChapterRef()

        assertEquals(BookOasisChapterRef(bookId = 101L, dbType = "adult", format = "pdf"), ref)
    }

    @Test
    fun `rejects unsupported or malformed chapter reference`() {
        assertNull("/api/media/books/42/info?type=general&bo_format=zip".toBookOasisChapterRef())
        assertNull("/api/media/books/not-a-number/info?type=general&bo_format=epub".toBookOasisChapterRef())
        assertNull("/api/media/books/42/info?type=general".toBookOasisChapterRef())
    }
}
