package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadNetworkLifecycleTest {
    @Test
    fun `completed download releases the infinite network observer`() = runBlocking {
        var running = true
        var released = false
        val checks = MutableStateFlow(true).onCompletion { released = true }
        launch { delay(50); running = false }
        val retry = withTimeout(1000) { awaitDownloadNetwork(checks) { running } }
        assertFalse(retry)
        assertTrue(released)
    }

    @Test
    fun `network loss requests a recoverable retry instead of terminal failure`() = runBlocking {
        val network = MutableStateFlow(true)
        var released = false
        launch { delay(50); network.value = false }
        val retry = withTimeout(1000) { awaitDownloadNetwork(network.onCompletion { released = true }) { true } }
        assertTrue(retry)
        assertTrue(released)
    }

    @Test
    fun `cancellation unregisters the network observer`() = runBlocking {
        var subscribed = false
        var released = false
        val network = MutableStateFlow(true).onStart { subscribed = true }.onCompletion { released = true }
        val worker = launch { awaitDownloadNetwork(network) { true } }
        withTimeout(1000) { while (!subscribed) delay(10) }
        worker.cancelAndJoin()
        assertTrue(released)
    }
}
