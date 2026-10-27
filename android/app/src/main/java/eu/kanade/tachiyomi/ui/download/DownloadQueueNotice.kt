package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import mihon.app.di.appGraph

internal fun downloadQueueBlockReason(network: NetworkState, wifiOnly: Boolean): String? = when {
    !network.isOnline -> "네트워크 연결을 기다리고 있습니다. 연결되면 자동으로 이어받습니다."
    wifiOnly && !network.isWifi -> "Wi-Fi 전용 설정으로 다운로드가 대기 중입니다."
    else -> null
}

/** Visible even when Android download-notification permission was denied. Never silently allow data use. */
@Composable
internal fun DownloadQueueNotice() {
    val context = LocalContext.current
    val graph = context.appGraph
    val manager = graph.downloadManager
    val queue by manager.queueState.collectAsState()
    if (queue.isEmpty()) return
    val network by remember(context) { context.networkStateFlow() }.collectAsState(context.activeNetworkState())
    val wifiOnly by graph.downloadPreferences.downloadOnlyOverWifi.changes()
        .collectAsState(graph.downloadPreferences.downloadOnlyOverWifi.get())
    val statuses by remember(queue) {
        if (queue.isEmpty()) flowOf(emptyList()) else combine(queue.map { it.statusFlow }) { it.toList() }
    }.collectAsState(emptyList())
    val running by remember(context) { manager.isDownloaderRunning }.collectAsState(false)
    var confirmMobile by remember { mutableStateOf(false) }
    val blocked = downloadQueueBlockReason(network, wifiOnly)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            blocked ?: when {
                Download.State.ERROR in statuses -> "다운로드에 실패한 책이 있습니다. 재개를 눌러 다시 시도해 주세요."
                running -> "다운로드 중입니다. 완료된 책은 오프라인 서재에서 읽을 수 있습니다."
                else -> "다운로드가 일시 중지되어 있습니다. 재개를 눌러 주세요."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (network.isOnline && wifiOnly && !network.isWifi) {
            TextButton(onClick = { confirmMobile = true }) { Text("모바일 데이터 사용 허용") }
        }
    }
    if (confirmMobile) {
        AlertDialog(
            onDismissRequest = { confirmMobile = false },
            title = { Text("모바일 데이터로 다운로드할까요?") },
            text = { Text("도서 크기만큼 데이터가 사용됩니다. 허용하면 Wi-Fi 전용 설정이 해제되며, 이후 다운로드에도 적용됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMobile = false
                    graph.downloadPreferences.downloadOnlyOverWifi.set(false)
                    manager.startDownloads()
                }) { Text("허용하고 다운로드") }
            },
            dismissButton = { TextButton(onClick = { confirmMobile = false }) { Text("취소") } },
        )
    }
}
