package eu.kanade.tachiyomi.source.bookoasis.progress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.isBookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.toBookOasisProgressRef
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.TextReaderAnchor
import eu.kanade.tachiyomi.ui.reader.loader.TextReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import org.jsoup.parser.Parser
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

internal object BookOasisProgressSync {
    fun resumePending(context: Context) {
        val retryable = BookOasisProgressStore(context).getItems().any {
            it.status !in setOf(
                BookOasisProgressStore.STATUS_UNBOUND,
                BookOasisProgressStore.STATUS_UNSUPPORTED,
                BookOasisProgressStore.STATUS_API_UNAVAILABLE,
            )
        }
        if (retryable) BookOasisProgressSyncJob.setupTask(context)
    }

    fun enqueue(
        context: Context,
        source: Source,
        chapterUrl: String,
        page: ReaderPage,
        totalPages: Int,
    ) {
        val httpSource = source as? HttpSource ?: return
        if (!httpSource.isBookOasisSource()) return
        val ref = chapterUrl.toBookOasisProgressRef() ?: return
        if (totalPages <= 0) return

        val textPage = page as? TextReaderPage
        val textAnchor = textPage?.anchor
        val binding = BookOasisProgressApi.localAccountBinding(httpSource)
        val queueKey = if (binding == null) {
            "${source.id}:unbound:${ref.dbType}:${ref.bookId}"
        } else {
            "${source.id}:$binding:${ref.dbType}:${ref.bookId}"
        }
        val format = ref.format.lowercase()
        val fingerprint = textPage?.readText()?.let(::bookOasisFingerprint)

        BookOasisProgressStore(context).put(
            BookOasisPendingProgress(
                sourceId = source.id,
                bookId = ref.bookId,
                dbType = ref.dbType,
                format = format,
                pageIndex = page.index.coerceIn(0, totalPages - 1),
                totalPages = totalPages,
                documentIndex = textAnchor?.documentIndex,
                startOffset = textAnchor?.startOffset,
                fingerprint = fingerprint,
                updatedAt = System.currentTimeMillis(),
                accountBinding = binding,
                status = if (binding == null) BookOasisProgressStore.STATUS_UNBOUND else null,
                queueKey = queueKey,
            ),
        )
        if (binding != null) BookOasisProgressSyncJob.setupTask(context)
    }

    suspend fun restorePage(
        context: Context,
        source: Source,
        chapterUrl: String,
        pages: List<ReaderPage>,
    ): Int? {
        val httpSource = source as? HttpSource ?: return null
        if (httpSource !is BookOasisSource || pages.isEmpty()) return null
        val ref = chapterUrl.toBookOasisProgressRef() ?: return null
        val binding = BookOasisProgressApi.localAccountBinding(httpSource) ?: return null
        val probe = BookOasisPendingProgress(
            sourceId = source.id,
            bookId = ref.bookId,
            dbType = ref.dbType,
            format = ref.format.lowercase(),
            pageIndex = 0,
            totalPages = pages.size,
            updatedAt = 0,
            accountBinding = binding,
            queueKey = "${source.id}:$binding:${ref.dbType}:${ref.bookId}",
        )
        val store = BookOasisProgressStore(context)
        return try {
            val server = BookOasisProgressApi.get(httpSource, probe)
            val pending = store.getItems().firstOrNull { it.key == probe.key }
            if (store.confirmedScope(probe.key)?.let { it != server.accountScope } == true) return null
            if (decideBookOasisProgress(store.lastReadAt(probe.key), server.updatedAt) !=
                BookOasisConflictDecision.PullServer
            ) {
                return null
            }
            val resolved = resolveBookOasisPage(
                server = server,
                format = ref.format,
                anchors = pages.map { (it as? TextReaderPage)?.anchor },
                textForPage = { pageIndex ->
                    (pages.getOrNull(pageIndex) as? TextReaderPage)?.readText()
                },
            )
            if (resolved == null && (server.pagesRead > 0 || !server.fingerprint.isNullOrBlank())) {
                store.rememberUnresolvedRestore(probe.key, server.revision)
            } else {
                store.rememberRevision(probe.key, server.revision, server.accountScope)
                if (pending != null) store.completeIfUnchanged(pending, server.revision, server.accountScope)
            }
            resolved
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) {
                "BookOasis progress restore failed for ${probe.dbType}:${probe.bookId}"
            }
            null
        }
    }
}

internal fun resolveBookOasisPage(
    server: BookOasisServerProgress,
    format: String,
    anchors: List<TextReaderAnchor?>,
    textForPage: (Int) -> String? = { null },
): Int? {
    if (anchors.isEmpty()) return null
    val normalizedFormat = format.lowercase()
    val isText = normalizedFormat == "epub" || normalizedFormat == "txt"
    val documentIndex = server.documentIndex
    val fingerprint = server.fingerprint
        ?.let { if (server.fingerprintIsRaw) it else Parser.unescapeEntities(it, false) }
        ?.normalizeBookOasisSearchText()
        .orEmpty()

    // Position fingerprints are raw decoded native text. Match them before TXT offsets because
    // web and native decoders can normalize CRLF/whitespace differently. EPUB never treats a web
    // reader offset as a native offset; its spine+fingerprint or percentage are authoritative.
    if (isText && fingerprint.length >= MIN_FINGERPRINT_LENGTH) {
        val matchedPages = linkedSetOf<Int>()
        var previousSuffix: String? = null
        var previousPageIndex: Int? = null
        anchors.forEachIndexed { pageIndex, anchor ->
            if (documentIndex != null && anchor?.documentIndex != documentIndex) return@forEachIndexed
            val text = textForPage(pageIndex)?.normalizeBookOasisSearchText()
            if (text == null) {
                previousSuffix = null
                previousPageIndex = null
                return@forEachIndexed
            }
            if (text.contains(fingerprint, ignoreCase = true)) matchedPages += pageIndex
            val previous = previousSuffix
            if (previous != null && previousPageIndex == pageIndex - 1 &&
                (
                    matchesAcrossTextBoundary(previous, " ", text, fingerprint) ||
                        matchesAcrossTextBoundary(previous, "", text, fingerprint)
                    )
            ) {
                matchedPages += previousPageIndex
            }
            previousSuffix = text.takeLast((fingerprint.length - 1).coerceAtLeast(0))
            previousPageIndex = pageIndex
        }
        if (matchedPages.size == 1) return matchedPages.single()
        if (matchedPages.size > 1) {
            if (normalizedFormat == "txt" && server.startOffset != null) {
                val exact = matchedPages.firstOrNull { pageIndex ->
                    val anchor = anchors[pageIndex]
                    anchor != null && server.startOffset in anchor.startOffset until anchor.endOffsetExclusive
                }
                if (exact != null) return exact
                val nearest = matchedPages.minByOrNull { pageIndex ->
                    kotlin.math.abs(anchors[pageIndex]!!.startOffset - server.startOffset)
                }
                val nearestDistance = nearest?.let { kotlin.math.abs(anchors[it]!!.startOffset - server.startOffset) }
                val tied = nearest != null && matchedPages.count {
                    kotlin.math.abs(anchors[it]!!.startOffset - server.startOffset) == nearestDistance
                } > 1
                if (nearest != null && !tied && nearestDistance!! <= MAX_TEXT_OFFSET_DRIFT) return nearest
            }
            // An EPUB native offset is not in the web text coordinate system. If the raw anchor
            // is repeated in multiple local pages, do not choose an arbitrary occurrence.
            return null
        }
        // A supplied text anchor that cannot be found is not a trustworthy position. Legacy
        // records without an anchor still retain their coarse progress fallback below.
        return null
    }

    if (normalizedFormat == "txt" && documentIndex != null && server.startOffset != null) {
        val exact = anchors.indexOfFirst { anchor ->
            anchor != null && anchor.documentIndex == documentIndex &&
                server.startOffset in anchor.startOffset until anchor.endOffsetExclusive
        }
        if (exact >= 0) return exact
        val nearest = anchors.withIndex()
            .filter { it.value?.documentIndex == documentIndex }
            .minByOrNull { (_, anchor) -> kotlin.math.abs(anchor!!.startOffset - server.startOffset) }
        if (nearest != null) return nearest.index
    }

    val fallback = if (normalizedFormat == "epub" && server.percent != null) {
        ((server.percent * anchors.size) / 100).coerceAtLeast(1) - 1
    } else {
        server.pagesRead - 1
    }
    return fallback.takeIf { it >= 0 }?.coerceAtMost(anchors.lastIndex)
}

private fun matchesAcrossTextBoundary(left: String, separator: String, right: String, needle: String): Boolean {
    val joined = left + separator + right
    val boundary = left.length + separator.length
    var fromIndex = 0
    while (true) {
        val match = joined.indexOf(needle, startIndex = fromIndex, ignoreCase = true)
        if (match < 0) return false
        if (match < boundary && match + needle.length > boundary) return true
        fromIndex = match + 1
    }
}

internal fun bookOasisFingerprint(text: String): String? =
    text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
        .let { lines ->
            lines.firstOrNull { it.length >= MIN_FINGERPRINT_LENGTH } ?: lines.firstOrNull()
        }
        ?.take(FINGERPRINT_LENGTH)

private fun String.normalizeBookOasisSearchText(): String =
    replace(Regex("\\s+"), " ").trim()

private const val FINGERPRINT_LENGTH = 80
private const val MIN_FINGERPRINT_LENGTH = 15
private const val MAX_TEXT_OFFSET_DRIFT = 12_000L

class BookOasisProgressSyncJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withIOContext {
        val store = BookOasisProgressStore(context)
        val graph: AppGraph = context.metroGraph()
        val sourceManager = graph.sourceManager
        var retryNeeded = false

        store.getItems().forEach { originalItem ->
            if (originalItem.status == BookOasisProgressStore.STATUS_UNBOUND ||
                originalItem.status == BookOasisProgressStore.STATUS_UNSUPPORTED ||
                originalItem.status == BookOasisProgressStore.STATUS_API_UNAVAILABLE
            ) {
                return@forEach
            }

            val source = sourceManager.get(originalItem.sourceId) as? HttpSource
            if (source == null) {
                retryNeeded = true
                return@forEach
            }
            val currentBinding = BookOasisProgressApi.localAccountBinding(source)
            if (originalItem.accountBinding == null || currentBinding == null) {
                store.setStatusIfUnchanged(originalItem, BookOasisProgressStore.STATUS_UNBOUND)
                return@forEach
            }
            if (originalItem.accountBinding != currentBinding) {
                store.setStatusIfUnchanged(originalItem, BookOasisProgressStore.STATUS_ACCOUNT_CHANGED)
                return@forEach
            }
            if (originalItem.status == BookOasisProgressStore.STATUS_ACCOUNT_CHANGED) {
                store.setStatusIfUnchanged(originalItem, null)
            }
            val item = originalItem.copy(status = null)

            try {
                val server = BookOasisProgressApi.get(source, item)
                val confirmedScope = store.confirmedScope(item.key)
                if (confirmedScope != null && confirmedScope != server.accountScope) {
                    store.setStatusIfUnchanged(item, BookOasisProgressStore.STATUS_ACCOUNT_CHANGED)
                    return@forEach
                }
                store.bindAccountScopeIfUnchanged(item, server.accountScope)
                if (decideBookOasisProgress(item.updatedAt, server.updatedAt) != BookOasisConflictDecision.PushLocal) {
                    // The newer server position is restored on open; ties keep the current device page.
                    store.completeIfUnchanged(item, server.revision, server.accountScope)
                    return@forEach
                }
                when (
                    val result = BookOasisProgressApi.post(
                        source = source,
                        item = item.copy(accountScope = server.accountScope),
                        baseRevision = server.revision,
                        accountScope = server.accountScope,
                    )
                ) {
                    is BookOasisProgressPostResult.Success -> {
                        store.completeIfUnchanged(item, result.revision, result.accountScope)
                    }
                    is BookOasisProgressPostResult.Conflict -> {
                        // Another reader won the latest-time check during the preflight.
                        store.completeIfUnchanged(item, result.revision, result.accountScope)
                    }
                }
            } catch (error: BookOasisProgressException) {
                when (error.code) {
                    "ACCOUNT_CHANGED" -> store.setStatusIfUnchanged(
                        item,
                        BookOasisProgressStore.STATUS_ACCOUNT_CHANGED,
                    )
                    "UNSUPPORTED" -> store.setStatusIfUnchanged(
                        item,
                        BookOasisProgressStore.STATUS_UNSUPPORTED,
                    )
                    "STOCK_API_UNAVAILABLE", "FORBIDDEN", "INVALID" ->
                        store.setStatusIfUnchanged(item, BookOasisProgressStore.STATUS_API_UNAVAILABLE)
                    else -> {
                        retryNeeded = true
                        logSyncFailure(error, item)
                    }
                }
            } catch (error: Exception) {
                retryNeeded = true
                logSyncFailure(error, item)
            }
        }

        val remainsRetryable = store.getItems().any { it.status == null }
        if (retryNeeded || remainsRetryable) Result.retry() else Result.success()
    }

    private fun logSyncFailure(error: Exception, item: BookOasisPendingProgress) {
        logcat(LogPriority.WARN, error) {
            "BookOasis progress sync failed for ${item.dbType}:${item.bookId}"
        }
    }

    companion object {
        private const val TAG = "BookOasisProgressSync"

        fun setupTask(context: Context) {
            val request = OneTimeWorkRequestBuilder<BookOasisProgressSyncJob>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            // Never cancel a running sync: its POST may already have reached the server even if
            // cancellation prevents the returned revision from being saved locally. APPEND_OR_REPLACE
            // lets the current worker finish, then flushes the newest durable outbox value. Multiple
            // page turns may append lightweight workers, but only the latest outbox value is posted.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
