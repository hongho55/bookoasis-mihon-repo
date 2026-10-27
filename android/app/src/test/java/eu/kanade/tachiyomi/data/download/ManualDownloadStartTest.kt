package eu.kanade.tachiyomi.data.download

import android.content.Context
import eu.kanade.tachiyomi.data.download.model.Download
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ManualDownloadStartTest {
    @Test
    fun `manual save restarts a stopped nonempty queue including an already queued retry`() = runBlocking {
        withManager { manager, context, downloader, manga, chapter, _ ->
            manager.downloadChapters(manga, listOf(chapter), manualStart = true)
            coVerify { downloader.queueChapters(manga, listOf(chapter), false) }
            verify(exactly = 1) { DownloadJob.start(context) }
        }
    }

    @Test
    fun `background enqueue does not resume a paused queue`() = runBlocking {
        withManager { manager, context, downloader, manga, chapter, _ ->
            manager.downloadChapters(manga, listOf(chapter))
            coVerify { downloader.queueChapters(manga, listOf(chapter), true) }
            verify(exactly = 0) { DownloadJob.start(context) }
        }
    }

    @Test
    fun `manual save of an already downloaded chapter does not wake unrelated pending downloads`() = runBlocking {
        withManager { manager, context, _, manga, chapter, queue ->
            queue.value = emptyList()
            manager.downloadChapters(manga, listOf(chapter), manualStart = true)
            verify(exactly = 0) { DownloadJob.start(context) }
        }
    }

    @Test
    fun `manual save does not replace a running downloader`() = runBlocking {
        withManager { manager, context, downloader, manga, chapter, _ ->
            every { downloader.isRunning } returns true
            manager.downloadChapters(manga, listOf(chapter), manualStart = true)
            verify(exactly = 0) { DownloadJob.start(context) }
        }
    }

    private suspend fun withManager(
        test: suspend (DownloadManager, Context, Downloader, Manga, Chapter, MutableStateFlow<List<Download>>) -> Unit,
    ) {
        val context = mockk<Context>(relaxed = true)
        val downloader = mockk<Downloader>(relaxed = true)
        val manga = mockk<Manga>()
        val chapter = mockk<Chapter>()
        every { chapter.id } returns 42L
        val download = mockk<Download>()
        every { download.chapter } returns chapter
        val queue = MutableStateFlow(listOf(download))
        every { downloader.queueState } returns queue
        every { downloader.isRunning } returns false
        val manager = DownloadManager(context, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), downloader, mockk())
        mockkObject(DownloadJob)
        every { DownloadJob.isRunning(context) } returns false
        every { DownloadJob.start(context) } returns Unit
        try {
            test(manager, context, downloader, manga, chapter, queue)
        } finally {
            unmockkObject(DownloadJob)
        }
    }
}
