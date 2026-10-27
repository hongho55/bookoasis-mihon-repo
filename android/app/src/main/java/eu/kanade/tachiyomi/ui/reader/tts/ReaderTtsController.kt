package eu.kanade.tachiyomi.ui.reader.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ReaderTtsController {

    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val readerChapterId: Long = -1L,
        val readerPageIndex: Int = -1,
        val documentIndex: Int = -1,
        val sourceSentenceStart: Long = -1L,
        val sourceSentenceEndExclusive: Long = -1L,
        val sourceHighlightStart: Long = -1L,
        val sourceHighlightEndExclusive: Long = -1L,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    internal fun updateState(state: State) {
        mutableState.value = state
    }

    fun start(
        context: Context,
        sessionId: String,
        startReaderPageIndex: Int,
        readerChapterId: Long,
        title: String,
        subtitle: String?,
        speed: Float,
        enginePackage: String,
    ) {
        val intent = Intent(context, ReaderTtsService::class.java).apply {
            action = ReaderTtsService.ACTION_START
            putExtra(ReaderTtsService.EXTRA_SESSION_ID, sessionId)
            putExtra(ReaderTtsService.EXTRA_START_PAGE_INDEX, startReaderPageIndex)
            putExtra(ReaderTtsService.EXTRA_CHAPTER_ID, readerChapterId)
            putExtra(ReaderTtsService.EXTRA_TITLE, title)
            putExtra(ReaderTtsService.EXTRA_SUBTITLE, subtitle)
            putExtra(ReaderTtsService.EXTRA_SPEED, speed)
            putExtra(ReaderTtsService.EXTRA_ENGINE_PACKAGE, enginePackage)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun toggle(context: Context) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_TOGGLE),
        )
    }

    fun resumeFromSource(
        context: Context,
        documentIndex: Int,
        sourceOffset: Long,
    ) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java)
                .setAction(ReaderTtsService.ACTION_RESUME_FROM_SOURCE)
                .putExtra(ReaderTtsService.EXTRA_DOCUMENT_INDEX, documentIndex)
                .putExtra(ReaderTtsService.EXTRA_SOURCE_OFFSET, sourceOffset),
        )
    }

    fun stop(context: Context) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_STOP),
        )
    }

    fun setSpeed(context: Context, speed: Float) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java)
                .setAction(ReaderTtsService.ACTION_SET_SPEED)
                .putExtra(ReaderTtsService.EXTRA_SPEED, speed),
        )
    }

    fun setEngine(context: Context, enginePackage: String) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java)
                .setAction(ReaderTtsService.ACTION_SET_ENGINE)
                .putExtra(ReaderTtsService.EXTRA_ENGINE_PACKAGE, enginePackage),
        )
    }

    fun refreshVoiceSettings(context: Context, enginePackage: String) {
        if (!state.value.active) return
        context.startService(
            Intent(context, ReaderTtsService::class.java)
                .setAction(ReaderTtsService.ACTION_REFRESH_VOICE)
                .putExtra(ReaderTtsService.EXTRA_ENGINE_PACKAGE, enginePackage),
        )
    }
}
