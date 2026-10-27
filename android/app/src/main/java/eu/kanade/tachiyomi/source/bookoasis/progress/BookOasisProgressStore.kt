package eu.kanade.tachiyomi.source.bookoasis.progress

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject

data class BookOasisPendingProgress(
    val sourceId: Long,
    val bookId: Long,
    val dbType: String,
    val format: String,
    val pageIndex: Int,
    val totalPages: Int,
    val documentIndex: Int? = null,
    val documentHref: String? = null,
    val startOffset: Long? = null,
    val fingerprint: String? = null,
    val updatedAt: Long,
    val accountBinding: String? = null,
    val accountScope: String? = null,
    val status: String? = null,
    val conflictRevision: String? = null,
    val queueKey: String? = null,
) {
    val key: String = queueKey ?: "$sourceId:$dbType:$bookId"
}

internal enum class BookOasisConflictDecision {
    PushLocal,
    PullServer,
    KeepLocal,
}

internal fun decideBookOasisProgress(
    localUpdatedAt: Long,
    serverUpdatedAt: Long,
): BookOasisConflictDecision =
    when {
        serverUpdatedAt > localUpdatedAt -> BookOasisConflictDecision.PullServer
        localUpdatedAt > serverUpdatedAt -> BookOasisConflictDecision.PushLocal
        else -> BookOasisConflictDecision.KeepLocal
    }

internal class BookOasisProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun observeItems() = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(OUTBOX_PREFIX)) trySend(getItems())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(getItems())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun put(item: BookOasisPendingProgress) {
        synchronized(lock) {
            val storageKey = OUTBOX_PREFIX + item.key
            val previous = preferences.getString(storageKey, null)?.let(::decodeItem)
            // A real page change is a new reading event, not a permanently blocked conflict.
            val value = item.copy(
                accountScope = item.accountScope ?: previous?.accountScope,
            )
            preferences.edit(commit = true) {
                putString(storageKey, encodeItem(value))
                putLong(READ_AT_PREFIX + item.key, item.updatedAt)
            }
        }
    }

    fun getItems(): List<BookOasisPendingProgress> = synchronized(lock) {
        preferences.all.mapNotNull { (key, rawValue) ->
            if (!key.startsWith(OUTBOX_PREFIX)) return@mapNotNull null
            runCatching { decodeItem(rawValue as String, key.removePrefix(OUTBOX_PREFIX)) }.getOrNull()
        }
    }

    fun confirmedRevision(key: String): String? = synchronized(lock) {
        preferences.getString(REVISION_PREFIX + key, null)
    }

    fun confirmedScope(key: String): String? = synchronized(lock) {
        preferences.getString(SCOPE_PREFIX + key, null)
    }

    fun lastReadAt(key: String): Long = synchronized(lock) {
        val pending = preferences.getString(OUTBOX_PREFIX + key, null)
            ?.let { runCatching { decodeItem(it, key).updatedAt }.getOrNull() } ?: 0L
        maxOf(preferences.getLong(READ_AT_PREFIX + key, 0L), pending)
    }

    fun rememberUnresolvedRestore(key: String, revision: String) {
        synchronized(lock) {
            preferences.edit(commit = true) { putString(UNRESOLVED_RESTORE_PREFIX + key, revision) }
        }
    }

    fun rememberRevision(key: String, revision: String, accountScope: String) {
        synchronized(lock) {
            preferences.edit(commit = true) {
                putString(REVISION_PREFIX + key, revision)
                putString(SCOPE_PREFIX + key, accountScope)
                remove(UNRESOLVED_RESTORE_PREFIX + key)
            }
        }
    }

    fun bindAccountScopeIfUnchanged(item: BookOasisPendingProgress, accountScope: String) {
        synchronized(lock) {
            val prefKey = OUTBOX_PREFIX + item.key
            val raw = preferences.getString(prefKey, null) ?: return
            val current = runCatching { decodeItem(raw, item.key) }.getOrNull() ?: return
            if (!samePosition(current, item)) return
            if (current.accountScope != null && current.accountScope != accountScope) return
            preferences.edit(commit = true) {
                putString(prefKey, encodeItem(current.copy(accountScope = accountScope)))
            }
        }
    }

    fun setStatusIfUnchanged(
        item: BookOasisPendingProgress,
        status: String?,
        conflictRevision: String? = null,
    ) {
        synchronized(lock) {
            val prefKey = OUTBOX_PREFIX + item.key
            val raw = preferences.getString(prefKey, null) ?: return
            val current = runCatching { decodeItem(raw, item.key) }.getOrNull() ?: return
            if (!samePosition(current, item)) return
            preferences.edit(commit = true) {
                putString(
                    prefKey,
                    encodeItem(
                        current.copy(
                            status = status,
                            conflictRevision = if (status == STATUS_CONFLICT) {
                                conflictRevision ?: current.conflictRevision
                            } else {
                                null
                            },
                        ),
                    ),
                )
            }
        }
    }

    fun markConflictIfUnchanged(item: BookOasisPendingProgress, revision: String) {
        setStatusIfUnchanged(item, STATUS_CONFLICT, revision)
    }

    fun completeIfUnchanged(item: BookOasisPendingProgress, revision: String, accountScope: String) {
        synchronized(lock) {
            val prefKey = OUTBOX_PREFIX + item.key
            val current = preferences.getString(prefKey, null)
                ?.let { runCatching { decodeItem(it, item.key) }.getOrNull() }
            preferences.edit(commit = true) {
                putString(REVISION_PREFIX + item.key, revision)
                putString(SCOPE_PREFIX + item.key, accountScope)
                remove(UNRESOLVED_RESTORE_PREFIX + item.key)
                if (current != null && samePosition(current, item)) {
                    putLong(READ_AT_PREFIX + item.key, maxOf(lastReadAt(item.key), current.updatedAt))
                    remove(prefKey)
                }
            }
        }
    }

    private fun encodeItem(item: BookOasisPendingProgress): String = JSONObject()
        .put("sourceId", item.sourceId)
        .put("bookId", item.bookId)
        .put("dbType", item.dbType)
        .put("format", item.format)
        .put("pageIndex", item.pageIndex)
        .put("totalPages", item.totalPages)
        .put("documentIndex", item.documentIndex)
        .put("documentHref", item.documentHref)
        .put("startOffset", item.startOffset)
        .put("fingerprint", item.fingerprint)
        .put("updatedAt", item.updatedAt)
        .put("accountBinding", item.accountBinding)
        .put("accountScope", item.accountScope)
        .put("status", item.status)
        .put("conflictRevision", item.conflictRevision)
        .put("queueKey", item.key)
        .toString()

    private fun decodeItem(raw: String, actualQueueKey: String? = null): BookOasisPendingProgress {
        val value = JSONObject(raw)
        return BookOasisPendingProgress(
            sourceId = value.getLong("sourceId"),
            bookId = value.getLong("bookId"),
            dbType = value.getString("dbType"),
            format = value.optString("format", "pdf"),
            pageIndex = value.getInt("pageIndex"),
            totalPages = value.getInt("totalPages"),
            documentIndex = value.optIntOrNull("documentIndex"),
            documentHref = value.optStringOrNull("documentHref"),
            startOffset = value.optLongOrNull("startOffset"),
            fingerprint = value.optStringOrNull("fingerprint"),
            updatedAt = value.getLong("updatedAt"),
            accountBinding = value.optStringOrNull("accountBinding"),
            accountScope = value.optStringOrNull("accountScope"),
            status = value.optStringOrNull("status"),
            conflictRevision = value.optStringOrNull("conflictRevision"),
            queueKey = actualQueueKey ?: value.optStringOrNull("queueKey"),
        )
    }

    private fun samePosition(first: BookOasisPendingProgress, second: BookOasisPendingProgress): Boolean =
        first.updatedAt == second.updatedAt &&
            first.pageIndex == second.pageIndex &&
            first.totalPages == second.totalPages &&
            first.documentIndex == second.documentIndex &&
            first.startOffset == second.startOffset &&
            first.fingerprint == second.fingerprint &&
            first.accountBinding == second.accountBinding

    companion object {
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_API_UNAVAILABLE = "stock-api-unavailable"
        const val STATUS_PLUGIN_UNAVAILABLE = STATUS_API_UNAVAILABLE
        const val STATUS_UNSUPPORTED = "unsupported"
        const val STATUS_ACCOUNT_CHANGED = "account-changed"
        const val STATUS_UNBOUND = "unbound"

        private const val PREFERENCES_NAME = "bookoasis_progress_sync"
        private const val OUTBOX_PREFIX = "outbox:"
        private const val REVISION_PREFIX = "revision:"
        private const val SCOPE_PREFIX = "scope:"
        private const val READ_AT_PREFIX = "read-at:"
        private const val UNRESOLVED_RESTORE_PREFIX = "unresolved-restore:"
        private val lock = Any()
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else getInt(name)

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name) || !has(name)) null else getLong(name)

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else getString(name)
