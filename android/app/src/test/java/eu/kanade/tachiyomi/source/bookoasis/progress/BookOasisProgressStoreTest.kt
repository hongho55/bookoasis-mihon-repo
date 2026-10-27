package eu.kanade.tachiyomi.source.bookoasis.progress

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BookOasisProgressStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun accountScopedQueueKeysKeepSwitchedAccountsSeparate() {
        val store = BookOasisProgressStore(context)
        store.put(item(accountBinding = "sha256:account-a", queueKey = "7:sha256:account-a:general:42"))
        store.put(item(accountBinding = "sha256:account-b", queueKey = "7:sha256:account-b:general:42"))

        assertEquals(2, store.getItems().size)
        assertEquals(setOf("sha256:account-a", "sha256:account-b"), store.getItems().map { it.accountBinding }.toSet())
    }

    @Test fun newReadingUnblocksOldConflictAndKeepsActualReadingTime() {
        val store = BookOasisProgressStore(context)
        val queued = item(accountBinding = "sha256:account-a", queueKey = "7:sha256:account-a:general:42")
        store.put(queued)
        store.markConflictIfUnchanged(queued, "stock1:remote")

        val updated = queued.copy(pageIndex = 9, updatedAt = queued.updatedAt + 1)
        store.put(updated)
        val retained = store.getItems().single()
        assertNull(retained.status)
        assertNull(retained.conflictRevision)
        assertEquals(9, retained.pageIndex)
        assertEquals(updated.updatedAt, store.lastReadAt(updated.key))
    }

    @Test fun genuineNewReadingIsNotPermanentlyBlockedByAnUnresolvedOldRestore() {
        val queued = item(accountBinding = "sha256:account-a", queueKey = "7:sha256:account-a:general:42")
        context.getSharedPreferences("bookoasis_progress_sync", Context.MODE_PRIVATE).edit(commit = true) {
            putString("unresolved-restore:" + queued.key, "stock1:unresolved-remote")
        }
        val store = BookOasisProgressStore(context)
        store.put(queued)
        assertNull(store.getItems().single().status)
        assertNull(store.getItems().single().conflictRevision)
        assertEquals(queued.pageIndex, store.getItems().single().pageIndex)
        assertNull(store.confirmedRevision(queued.key))
    }

    @Test fun olderUnboundOutboxRecordIsHeldRatherThanMigrated() {
        context.getSharedPreferences("bookoasis_progress_sync", Context.MODE_PRIVATE).edit(commit = true) {
            putString(
                "outbox:7:general:42",
                JSONObject()
                    .put("sourceId", 7)
                    .put("bookId", 42)
                    .put("dbType", "general")
                    .put("format", "txt")
                    .put("pageIndex", 3)
                    .put("totalPages", 20)
                    .put("updatedAt", 123)
                    .toString(),
            )
        }
        val store = BookOasisProgressStore(context)
        val legacy = store.getItems().single()
        assertNull(legacy.accountBinding)
        assertEquals("7:general:42", legacy.key)

        store.setStatusIfUnchanged(legacy, BookOasisProgressStore.STATUS_UNBOUND)
        assertEquals(BookOasisProgressStore.STATUS_UNBOUND, store.getItems().single().status)
        assertTrue(store.getItems().isNotEmpty())
    }

    @Test fun completionForAnOlderSnapshotDoesNotDeleteANewerQueuedPosition() {
        val store = BookOasisProgressStore(context)
        val original = item(accountBinding = "sha256:account-a", queueKey = "7:sha256:account-a:general:42")
        store.put(original)
        store.put(original.copy(pageIndex = 4, updatedAt = original.updatedAt + 1))
        store.completeIfUnchanged(original, "stock1:done", "account-scope")

        assertEquals(4, store.getItems().single().pageIndex)
        assertEquals("stock1:done", store.confirmedRevision(original.key))
        assertEquals("account-scope", store.confirmedScope(original.key))
    }

    @Test fun oldConflictCanBeAutomaticallyCompletedWithoutLosingItsReadingTime() {
        val store = BookOasisProgressStore(context)
        val queued = item(accountBinding = "sha256:account-a", queueKey = "7:sha256:account-a:general:42")
        store.put(queued)
        store.markConflictIfUnchanged(queued, "stock1:old")
        store.completeIfUnchanged(queued, "stock1:latest", "account-scope")
        assertTrue(store.getItems().isEmpty())
        assertEquals(queued.updatedAt, BookOasisProgressStore(context).lastReadAt(queued.key))
        assertEquals("stock1:latest", store.confirmedRevision(queued.key))
    }

    private fun item(
        accountBinding: String?,
        queueKey: String,
    ) = BookOasisPendingProgress(
        sourceId = 7,
        bookId = 42,
        dbType = "general",
        format = "txt",
        pageIndex = 3,
        totalPages = 20,
        documentIndex = 0,
        startOffset = 123,
        fingerprint = "raw native paragraph",
        updatedAt = 123,
        accountBinding = accountBinding,
        queueKey = queueKey,
    )
}
