package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.download.DOCUMENT_DOWNLOAD_EXTENSIONS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadedPdfOfflineTest {

    @Test
    fun `PDF is included in the durable download document contract`() {
        assertEquals(setOf("epub", "pdf", "txt"), DOCUMENT_DOWNLOAD_EXTENSIONS)

        val readerFormats = DOCUMENT_DOWNLOAD_EXTENSIONS
            .mapNotNull(::downloadedDocumentFormat)
            .map { it.name.lowercase() }
            .toSet()
        assertEquals(DOCUMENT_DOWNLOAD_EXTENSIONS, readerFormats)
    }

    @Test
    fun `downloaded PDF selects native format without text reader styling`() {
        assertEquals(DownloadedDocumentFormat.PDF, downloadedDocumentFormat("PDF"))
        assertFalse(DownloadedDocumentFormat.PDF.usesTextReaderStyle)

        assertEquals(DownloadedDocumentFormat.EPUB, downloadedDocumentFormat("epub"))
        assertTrue(DownloadedDocumentFormat.EPUB.usesTextReaderStyle)
        assertEquals(DownloadedDocumentFormat.TXT, downloadedDocumentFormat("txt"))
        assertTrue(DownloadedDocumentFormat.TXT.usesTextReaderStyle)
        assertNull(downloadedDocumentFormat("cbz"))
    }
}
