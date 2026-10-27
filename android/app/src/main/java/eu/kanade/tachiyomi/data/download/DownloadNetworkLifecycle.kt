package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Watch only while a download is active, returning whether network loss needs a retry. */
internal suspend fun awaitDownloadNetwork(checks: Flow<Boolean>, isRunning: () -> Boolean): Boolean = coroutineScope {
    var networkAllowed = true
    val monitor = checks.onEach { networkAllowed = it }.launchIn(this)
    try {
        while (isRunning() && networkAllowed) delay(100)
        !networkAllowed
    } finally {
        // A permanent callbackFlow must not keep the worker alive after downloads stop.
        monitor.cancel()
    }
}
