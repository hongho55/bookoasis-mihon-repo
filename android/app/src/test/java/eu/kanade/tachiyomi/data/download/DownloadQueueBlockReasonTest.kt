package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.ui.download.downloadQueueBlockReason
import eu.kanade.tachiyomi.util.system.NetworkState
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadQueueBlockReasonTest {
    @Test
    fun `cellular network explains wifi restriction without silently allowing data`() {
        assertTrue(downloadQueueBlockReason(NetworkState(true, true, false), true)!!.contains("Wi-Fi"))
        assertNull(downloadQueueBlockReason(NetworkState(true, true, false), false))
    }

    @Test
    fun `wifi only blocks cellular not validated wifi`() {
        assertNull(downloadQueueBlockReason(NetworkState(true, true, true), true))
    }

    @Test
    fun `offline and unvalidated networks explain connectivity instead of claiming downloading`() {
        assertTrue(downloadQueueBlockReason(NetworkState(false, false, false), false)!!.contains("네트워크"))
        assertTrue(downloadQueueBlockReason(NetworkState(true, false, true), true)!!.contains("네트워크"))
    }
}
