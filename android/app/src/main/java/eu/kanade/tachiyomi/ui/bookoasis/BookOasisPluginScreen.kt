package eu.kanade.tachiyomi.ui.bookoasis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.bookOasisPluginUrl
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack

internal class BookOasisPluginScreen(
    private val pluginId: String,
    private val title: String,
    private val dbType: String,
) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val source = remember { BookOasisSource(1) }
        val url = remember { bookOasisPluginUrl(source.baseUrl, pluginId, dbType) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "북오아시스 메뉴로 돌아가기")
                }
                Text(
                    title,
                    Modifier.weight(1f).padding(end = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Keep the plugin's own content search, pagination and detail UI. Never substitute
            // the server-wide book search or the unrelated admin metadata-provider search.
            BookOasisServerWebView(
                source = source,
                openShelf = {
                    navigator.pop()
                    context.appGraph.basePreferences.downloadedOnly.set(true)
                    scope.launch { HomeScreen.openTab(HomeScreen.Tab.Library()) }
                },
                initialUrl = url,
                reloadOnResume = false,
            )
        }
    }
}
