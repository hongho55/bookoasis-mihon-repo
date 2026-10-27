package eu.kanade.tachiyomi.ui.reader.tts

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.min

class ReaderTtsService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var sessionId: String? = null
    private var session: ReaderTtsSessionStore.SessionReader? = null
    private var pagePosition = 0
    private var pageSpeechStartOffset = 0
    private var currentWindow: SpeechWindow? = null
    private var currentSegments: List<Segment> = emptyList()
    private var lookaheadConsumedChars: Map<Int, Int> = emptyMap()
    private var segmentPosition = 0
    private var segmentSpeechOffset = 0
    private var currentUtteranceBaseOffset = 0
    private var currentUtteranceId: String? = null
    private var ttsGeneration = 0
    private var playbackSpeed = DEFAULT_SPEED
    private var enginePreference = AUTO_ENGINE
    private var readerChapterId = -1L
    private var title = "Text reader"
    private var subtitle: String? = null
    private var playing = false
    private var initialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_TOGGLE -> if (playing) pausePlayback() else resumePlayback()
            ACTION_RESUME_FROM_SOURCE -> resumeFromSource(
                documentIndex = intent.getIntExtra(EXTRA_DOCUMENT_INDEX, -1),
                sourceOffset = intent.getLongExtra(EXTRA_SOURCE_OFFSET, -1L),
            )
            ACTION_STOP -> stopPlayback()
            ACTION_SET_SPEED -> {
                playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, playbackSpeed).coerceIn(MIN_SPEED, MAX_SPEED)
                tts?.setSpeechRate(playbackSpeed)
                if (playing && initialized) restartCurrentUtterance()
                refreshNotification()
            }
            ACTION_SET_ENGINE, ACTION_REFRESH_VOICE -> {
                val requestedEngine = intent.getStringExtra(EXTRA_ENGINE_PACKAGE).orEmpty()
                reinitializeTts(requestedEngine)
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
            stopSelf()
            return
        }
        val newSession = ReaderTtsSessionStore.open(this, newSessionId) ?: run {
            ReaderTtsSessionStore.delete(this, newSessionId)
            stopSelf()
            return
        }

        cleanupTts()
        session?.close()
        ReaderTtsSessionStore.delete(this, sessionId)

        sessionId = newSessionId
        session = newSession
        readerChapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Text reader" }
        subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
        enginePreference = intent.getStringExtra(EXTRA_ENGINE_PACKAGE).orEmpty().ifBlank { AUTO_ENGINE }

        val requestedPageIndex = intent.getIntExtra(EXTRA_START_PAGE_INDEX, 0)
        pagePosition = newSession.pages.indexOfFirst { it.readerPageIndex >= requestedPageIndex }
            .takeIf { it >= 0 }
            ?: 0
        pageSpeechStartOffset = 0
        currentWindow = null
        currentSegments = emptyList()
        lookaheadConsumedChars = emptyMap()
        segmentPosition = 0
        segmentSpeechOffset = 0
        currentUtteranceBaseOffset = 0
        currentUtteranceId = null
        playing = false
        initialized = false

        startForeground(Notifications.ID_READER_TTS, buildNotification(preparing = true))
        ReaderTtsController.updateState(
            ReaderTtsController.State(
                active = true,
                playing = false,
                readerChapterId = readerChapterId,
                readerPageIndex = newSession.pages[pagePosition].readerPageIndex,
                documentIndex = newSession.pages[pagePosition].documentIndex,
            ),
        )

        initializeTts(enginePreference, resumeAfterInit = true)
    }

    private fun initializeTts(
        requestedEnginePreference: String,
        resumeAfterInit: Boolean,
    ) {
        enginePreference = requestedEnginePreference.ifBlank { AUTO_ENGINE }
        val engine = resolveEngine(enginePreference)
        val generation = ++ttsGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (generation != ttsGeneration) return@post
                if (status != TextToSpeech.SUCCESS) {
                    if (engine != null) {
                        cleanupTts()
                        initializeTts(SYSTEM_DEFAULT_ENGINE, resumeAfterInit)
                    } else {
                        stopPlayback()
                    }
                    return@post
                }

                initialized = true
                tts?.apply {
                    setSpeechRate(playbackSpeed)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setOnUtteranceProgressListener(progressListener)
                }
                playing = resumeAfterInit
                updatePlaybackState(clearHighlight = false)
                if (playing) speakCurrentSegment()
            }
        }

        tts = if (engine == null) {
            TextToSpeech(this, listener)
        } else {
            TextToSpeech(this, listener, engine)
        }
    }

    private fun reinitializeTts(requestedEnginePreference: String) {
        if (session == null) return
        val resumeAfterInit = playing
        currentUtteranceId = null
        cleanupTts()
        initializeTts(
            requestedEnginePreference.ifBlank { enginePreference },
            resumeAfterInit = resumeAfterInit,
        )
    }

    private fun restartCurrentUtterance() {
        if (!playing || !initialized) return
        currentUtteranceId = null
        tts?.stop()
        speakCurrentSegment()
    }

    private fun resolveEngine(preference: String): String? {
        if (preference == SYSTEM_DEFAULT_ENGINE) return null
        if (preference.isNotBlank() && preference != AUTO_ENGINE) return preference

        @Suppress("DEPRECATION")
        val installed = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        )
        return installed
            .asSequence()
            .mapNotNull { it.serviceInfo?.packageName }
            .firstOrNull { it == GOOGLE_TTS_PACKAGE }
    }

    private fun pausePlayback() {
        if (!initialized || !playing) return
        tts?.stop()
        playing = false
        updatePlaybackState(clearHighlight = false)
    }

    private fun resumePlayback() {
        if (!initialized || session == null) return
        playing = true
        updatePlaybackState(clearHighlight = false)
        speakCurrentSegment()
    }

    private fun resumeFromSource(
        documentIndex: Int,
        sourceOffset: Long,
    ) {
        if (!initialized || documentIndex < 0 || sourceOffset < 0L) return
        val session = session ?: return

        val exactPosition = session.pages.indexOfFirst { entry ->
            entry.documentIndex == documentIndex &&
                sourceOffset >= entry.sourceStartOffset &&
                sourceOffset < entry.sourceEndOffsetExclusive
        }
        val nextPosition = if (exactPosition < 0) {
            session.pages.indexOfFirst { entry ->
                entry.documentIndex == documentIndex && entry.sourceStartOffset >= sourceOffset
            }
        } else {
            -1
        }
        val previousPosition = if (exactPosition < 0 && nextPosition < 0) {
            session.pages.indexOfLast { entry ->
                entry.documentIndex == documentIndex && entry.sourceStartOffset <= sourceOffset
            }
        } else {
            -1
        }
        val newPagePosition = when {
            exactPosition >= 0 -> exactPosition
            nextPosition >= 0 -> nextPosition
            previousPosition >= 0 -> previousPosition
            else -> return
        }
        val entry = session.pages[newPagePosition]
        val relativeOffset = (sourceOffset - entry.sourceStartOffset)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        tts?.stop()
        pagePosition = newPagePosition
        pageSpeechStartOffset = relativeOffset
        currentWindow = null
        currentSegments = emptyList()
        lookaheadConsumedChars = emptyMap()
        segmentPosition = 0
        segmentSpeechOffset = 0
        currentUtteranceBaseOffset = 0
        currentUtteranceId = null
        playing = true
        ReaderTtsController.updateState(
            ReaderTtsController.State(
                active = true,
                playing = true,
                readerChapterId = readerChapterId,
                readerPageIndex = entry.readerPageIndex,
                documentIndex = documentIndex,
                sourceSentenceStart = sourceOffset,
                sourceSentenceEndExclusive = sourceOffset,
            ),
        )
        refreshNotification()
        speakCurrentSegment()
    }

    private fun speakCurrentSegment() {
        if (!playing || !initialized) return

        if (currentSegments.isEmpty()) {
            if (!prepareCurrentSegments()) return
        }

        if (segmentPosition !in currentSegments.indices) {
            advancePage()
            return
        }

        val segment = currentSegments[segmentPosition]
        val speechOffset = segmentSpeechOffset.coerceIn(0, segment.text.length)
        if (speechOffset >= segment.text.length) {
            segmentSpeechOffset = 0
            advanceSegment()
            return
        }

        currentUtteranceBaseOffset = speechOffset
        val utteranceId = "$ttsGeneration:$pagePosition:$segmentPosition:$speechOffset"
        currentUtteranceId = utteranceId
        val result = tts?.speak(
            segment.text.substring(speechOffset),
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (result == TextToSpeech.ERROR) {
            currentUtteranceId = null
            segmentSpeechOffset = 0
            advanceSegment()
        }
    }

    private fun prepareCurrentSegments(): Boolean {
        val session = session ?: run {
            stopPlayback()
            return false
        }
        val currentEntry = session.pages.getOrNull(pagePosition) ?: run {
            stopPlayback()
            return false
        }
        val currentText = session.readPage(pagePosition)
        val safeStart = pageSpeechStartOffset.coerceIn(0, currentText.length)

        if (safeStart >= currentText.length) {
            advancePage()
            return false
        }

        val window = buildSpeechWindow(session, currentEntry.documentIndex, safeStart)
        val currentPart = window.parts.firstOrNull { it.pagePosition == pagePosition } ?: run {
            advancePage()
            return false
        }
        val segments = segmentText(window.text)
            .filter { it.start < currentPart.windowEndExclusive }

        if (segments.isEmpty()) {
            advancePage()
            return false
        }

        currentWindow = window
        currentSegments = segments
        segmentPosition = 0
        lookaheadConsumedChars = buildMap {
            val last = segments.last()
            window.parts
                .asSequence()
                .filter { it.pagePosition > pagePosition }
                .forEach { part ->
                    val consumedEnd = min(last.endExclusive, part.windowEndExclusive)
                    if (consumedEnd > part.windowStart) {
                        put(part.pagePosition, consumedEnd - part.windowStart)
                    }
                }
        }
        return true
    }

    private fun buildSpeechWindow(
        session: ReaderTtsSessionStore.SessionReader,
        documentIndex: Int,
        currentPageStart: Int,
    ): SpeechWindow {
        val text = StringBuilder()
        val parts = mutableListOf<WindowPart>()
        var candidatePage = pagePosition
        var pagesAdded = 0
        var firstPageEnd = -1

        while (
            candidatePage < session.pages.size &&
            pagesAdded < MAX_LOOKAHEAD_PAGES &&
            text.length < MAX_SPEECH_WINDOW_CHARS
        ) {
            val entry = session.pages[candidatePage]
            if (entry.documentIndex != documentIndex) break

            val pageText = session.readPage(candidatePage)
            val pageStart = if (candidatePage == pagePosition) currentPageStart else 0
            if (pageStart >= pageText.length) {
                candidatePage++
                continue
            }

            if (text.isNotEmpty()) text.append(' ')
            val windowStart = text.length
            val available = (MAX_SPEECH_WINDOW_CHARS - text.length).coerceAtLeast(0)
            if (available == 0) break

            val takeCount = min(pageText.length - pageStart, available)
            text.append(pageText, pageStart, pageStart + takeCount)
            val windowEnd = text.length
            parts += WindowPart(
                windowStart = windowStart,
                windowEndExclusive = windowEnd,
                pagePosition = candidatePage,
                documentIndex = entry.documentIndex,
                sourceStartOffset = entry.sourceStartOffset + pageStart,
            )

            if (candidatePage == pagePosition) {
                firstPageEnd = windowEnd
            }

            pagesAdded++
            if (
                candidatePage > pagePosition &&
                firstPageEnd >= 0 &&
                hasSentenceTerminatorAfter(text, firstPageEnd)
            ) {
                break
            }
            if (takeCount < pageText.length - pageStart) break
            candidatePage++
        }

        return SpeechWindow(
            text = text.toString(),
            parts = parts,
        )
    }

    private fun hasSentenceTerminatorAfter(text: CharSequence, start: Int): Boolean {
        for (index in start.coerceAtLeast(0) until text.length) {
            if (text[index] in SENTENCE_TERMINATORS) return true
        }
        return false
    }

    private fun advanceSegment() {
        currentUtteranceId = null
        segmentSpeechOffset = 0
        currentUtteranceBaseOffset = 0
        segmentPosition++
        if (segmentPosition >= currentSegments.size) advancePage() else speakCurrentSegment()
    }

    private fun advancePage() {
        val session = session ?: return stopPlayback()
        if (pagePosition + 1 >= session.pages.size) {
            stopPlayback()
            return
        }

        pagePosition++
        pageSpeechStartOffset = lookaheadConsumedChars[pagePosition] ?: 0
        currentWindow = null
        currentSegments = emptyList()
        segmentPosition = 0
        segmentSpeechOffset = 0
        currentUtteranceBaseOffset = 0
        currentUtteranceId = null
        speakCurrentSegment()
    }

    private fun stopPlayback() {
        playing = false
        cleanupTts()
        session?.close()
        session = null
        currentWindow = null
        currentSegments = emptyList()
        lookaheadConsumedChars = emptyMap()
        val oldSessionId = sessionId
        sessionId = null
        ReaderTtsSessionStore.delete(this, oldSessionId)
        ReaderTtsController.updateState(ReaderTtsController.State())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupTts() {
        ttsGeneration++
        currentUtteranceId = null
        initialized = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun updatePlaybackState(clearHighlight: Boolean = true) {
        val session = session ?: return
        val page = session.pages.getOrNull(pagePosition) ?: return
        val previous = ReaderTtsController.state.value
        ReaderTtsController.updateState(
            ReaderTtsController.State(
                active = true,
                playing = playing,
                readerChapterId = readerChapterId,
                readerPageIndex = if (!clearHighlight && previous.readerPageIndex >= 0) {
                    previous.readerPageIndex
                } else {
                    page.readerPageIndex
                },
                documentIndex = if (!clearHighlight && previous.documentIndex >= 0) {
                    previous.documentIndex
                } else {
                    page.documentIndex
                },
                sourceSentenceStart = if (clearHighlight) -1L else previous.sourceSentenceStart,
                sourceSentenceEndExclusive = if (clearHighlight) -1L else previous.sourceSentenceEndExclusive,
                sourceHighlightStart = if (clearHighlight) -1L else previous.sourceHighlightStart,
                sourceHighlightEndExclusive = if (clearHighlight) -1L else previous.sourceHighlightEndExclusive,
            ),
        )
        refreshNotification()
    }

    private fun publishHighlight(
        segment: Segment,
        relativeStart: Int = 0,
        relativeEndExclusive: Int = segment.text.length,
    ) {
        val session = session ?: return
        val window = currentWindow ?: return
        val sentenceStart = window.mapStart(segment.start) ?: return
        val sentenceEnd = window.mapEnd(segment.endExclusive) ?: return

        val safeStart = relativeStart.coerceIn(0, segment.text.length)
        val safeEnd = relativeEndExclusive.coerceIn(safeStart, segment.text.length)
        val activeStart = if (safeEnd > safeStart) {
            window.mapStart(segment.start + safeStart)
        } else {
            null
        }
        val activeEnd = if (safeEnd > safeStart) {
            window.mapEnd(segment.start + safeEnd)
        } else {
            null
        }
        val activePosition = activeStart ?: sentenceStart
        val page = session.pages.getOrNull(activePosition.pagePosition) ?: return

        ReaderTtsController.updateState(
            ReaderTtsController.State(
                active = true,
                playing = playing,
                readerChapterId = readerChapterId,
                readerPageIndex = page.readerPageIndex,
                documentIndex = activePosition.documentIndex,
                sourceSentenceStart = sentenceStart.sourceOffset,
                sourceSentenceEndExclusive = sentenceEnd.sourceOffset,
                sourceHighlightStart = activeStart?.sourceOffset ?: -1L,
                sourceHighlightEndExclusive = activeEnd?.sourceOffset ?: -1L,
            ),
        )
    }

    private fun SpeechWindow.mapStart(windowOffset: Int): MappedPosition? {
        if (text.isEmpty() || parts.isEmpty()) return null
        val clamped = windowOffset.coerceIn(0, text.lastIndex)
        val part = parts.firstOrNull { clamped in it.windowStart until it.windowEndExclusive }
            ?: parts.firstOrNull { it.windowStart > clamped }
            ?: parts.lastOrNull()
            ?: return null
        val charIndex = clamped.coerceIn(part.windowStart, part.windowEndExclusive - 1)
        return MappedPosition(
            pagePosition = part.pagePosition,
            documentIndex = part.documentIndex,
            sourceOffset = part.sourceStartOffset + (charIndex - part.windowStart),
        )
    }

    private fun SpeechWindow.mapEnd(windowEndExclusive: Int): MappedPosition? {
        if (text.isEmpty() || parts.isEmpty()) return null
        val clamped = (windowEndExclusive - 1).coerceIn(0, text.lastIndex)
        val part = parts.firstOrNull { clamped in it.windowStart until it.windowEndExclusive }
            ?: parts.lastOrNull { it.windowEndExclusive <= clamped + 1 }
            ?: parts.firstOrNull()
            ?: return null
        val charIndex = clamped.coerceIn(part.windowStart, part.windowEndExclusive - 1)
        return MappedPosition(
            pagePosition = part.pagePosition,
            documentIndex = part.documentIndex,
            sourceOffset = part.sourceStartOffset + (charIndex - part.windowStart) + 1L,
        )
    }

    private fun refreshNotification() {
        if (session == null) return
        startForeground(Notifications.ID_READER_TTS, buildNotification(preparing = false))
    }

    private fun buildNotification(preparing: Boolean): android.app.Notification {
        val toggleIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, ReaderTtsService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ReaderTtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return notificationBuilder(Notifications.CHANNEL_READER_TTS) {
            setSmallIcon(R.drawable.ic_mihon)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            setContentTitle(title)
            setContentText(
                when {
                    preparing -> "TTS preparing"
                    subtitle.isNullOrBlank() -> String.format(Locale.US, "TTS %.1fx", playbackSpeed)
                    else -> subtitle + " · " + String.format(Locale.US, "%.1fx", playbackSpeed)
                },
            )
            addAction(
                if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
                if (playing) "Pause" else "Play",
                toggleIntent,
            )
            addAction(R.drawable.ic_close_24dp, "Stop", stopIntent)
        }.build()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                if (!playing || utteranceId != currentUtteranceId) return@post
                currentSegments.getOrNull(segmentPosition)?.let { segment ->
                    publishHighlight(
                        segment = segment,
                        relativeStart = currentUtteranceBaseOffset,
                        relativeEndExclusive = (currentUtteranceBaseOffset + 1).coerceAtMost(segment.text.length),
                    )
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            mainHandler.post {
                if (!playing || utteranceId != currentUtteranceId) return@post
                currentSegments.getOrNull(segmentPosition)?.let { segment ->
                    val absoluteStart = (currentUtteranceBaseOffset + start).coerceIn(0, segment.text.length)
                    val absoluteEnd = (currentUtteranceBaseOffset + end).coerceIn(absoluteStart, segment.text.length)
                    segmentSpeechOffset = absoluteStart
                    publishHighlight(segment, absoluteStart, absoluteEnd)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (!playing || utteranceId != currentUtteranceId) return@post
                currentUtteranceId = null
                segmentSpeechOffset = 0
                advanceSegment()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                if (!playing || utteranceId != currentUtteranceId) return@post
                currentUtteranceId = null
                segmentSpeechOffset = 0
                advanceSegment()
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onError(utteranceId)
        }
    }

    override fun onDestroy() {
        cleanupTts()
        session?.close()
        session = null
        ReaderTtsSessionStore.delete(this, sessionId)
        sessionId = null
        ReaderTtsController.updateState(ReaderTtsController.State())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun segmentText(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()

        val segments = ArrayList<Segment>()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            addBoundedSegments(text, start, end, segments)
            start = end
            end = iterator.next()
        }

        if (segments.isEmpty()) addBoundedSegments(text, 0, text.length, segments)
        return segments
    }

    private fun addBoundedSegments(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        out: MutableList<Segment>,
    ) {
        var start = rawStart
        var end = rawEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return

        val maxLength = min(TextToSpeech.getMaxSpeechInputLength() - 64, MAX_SEGMENT_CHARS)
        var cursor = start
        while (cursor < end) {
            var chunkEnd = min(cursor + maxLength, end)
            if (chunkEnd < end) {
                val preferredBreak = text.lastIndexOfAny(
                    charArrayOf(' ', '\n', ',', '.', '!', '?', '。', '！', '？'),
                    startIndex = chunkEnd,
                )
                if (preferredBreak > cursor + maxLength / 2) chunkEnd = preferredBreak + 1
            }
            val chunk = text.substring(cursor, chunkEnd)
            val leading = chunk.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val last = chunk.indexOfLast { !it.isWhitespace() }
            if (last >= 0) {
                out += Segment(
                    start = cursor + leading,
                    endExclusive = cursor + last + 1,
                    text = chunk.substring(leading, last + 1),
                )
            }
            cursor = chunkEnd
        }
    }

    private data class Segment(
        val start: Int,
        val endExclusive: Int,
        val text: String,
    )

    private data class SpeechWindow(
        val text: String,
        val parts: List<WindowPart>,
    )

    private data class WindowPart(
        val windowStart: Int,
        val windowEndExclusive: Int,
        val pagePosition: Int,
        val documentIndex: Int,
        val sourceStartOffset: Long,
    )

    private data class MappedPosition(
        val pagePosition: Int,
        val documentIndex: Int,
        val sourceOffset: Long,
    )

    companion object {
        const val ACTION_START = "eu.kanade.tachiyomi.reader.tts.START"
        const val ACTION_TOGGLE = "eu.kanade.tachiyomi.reader.tts.TOGGLE"
        const val ACTION_RESUME_FROM_SOURCE = "eu.kanade.tachiyomi.reader.tts.RESUME_FROM_SOURCE"
        const val ACTION_STOP = "eu.kanade.tachiyomi.reader.tts.STOP"
        const val ACTION_SET_SPEED = "eu.kanade.tachiyomi.reader.tts.SET_SPEED"
        const val ACTION_SET_ENGINE = "eu.kanade.tachiyomi.reader.tts.SET_ENGINE"
        const val ACTION_REFRESH_VOICE = "eu.kanade.tachiyomi.reader.tts.REFRESH_VOICE"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_START_PAGE_INDEX = "start_page_index"
        const val EXTRA_DOCUMENT_INDEX = "document_index"
        const val EXTRA_SOURCE_OFFSET = "source_offset"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ENGINE_PACKAGE = "engine_package"

        const val AUTO_ENGINE = "auto"
        const val SYSTEM_DEFAULT_ENGINE = "system"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        private const val DEFAULT_SPEED = 1.2f
        private const val MIN_SPEED = 1.2f
        private const val MAX_SPEED = 3.0f
        private const val MAX_SEGMENT_CHARS = 1200
        private const val MAX_LOOKAHEAD_PAGES = 3
        private const val MAX_SPEECH_WINDOW_CHARS = 6000
        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '。', '！', '？')

        private const val REQUEST_TOGGLE = 9101
        private const val REQUEST_STOP = 9102
    }
}
