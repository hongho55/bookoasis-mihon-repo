package eu.kanade.tachiyomi.ui.bookoasis

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.bookoasis.BookOasisDashboardAction
import eu.kanade.tachiyomi.source.bookoasis.BookOasisDashboardRequest
import eu.kanade.tachiyomi.source.bookoasis.BookOasisDashboardSession
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.bookOasisDashboardOrigin
import eu.kanade.tachiyomi.source.bookoasis.isTrustedBookOasisDashboardMessage
import eu.kanade.tachiyomi.source.bookoasis.parseBookOasisDashboardMessage
import eu.kanade.tachiyomi.source.bookoasis.sameBookOasisOrigin
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.main.BookOasisConnectScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import org.json.JSONObject

internal data object BookOasisDashboardTab : Tab {
    override val options: TabOptions
        @Composable get() {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(0u, "북오아시스", rememberAnimatedVectorPainter(image, true))
        }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val tabs = LocalTabNavigator.current
        val source = remember { BookOasisSource(1) }
        val prefs = remember { source.getSourcePreferences() }
        var revision by remember { mutableIntStateOf(0) }
        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
                if (name in setOf("address", "username", "password")) revision++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val openShelf = {
            context.appGraph.basePreferences.downloadedOnly.set(true)
            tabs.current = LibraryTab
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("BookOasis", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = openShelf) { Text("오프라인 서재") }
                TextButton(onClick = { navigator.push(DownloadQueueScreen) }) { Text("다운로드") }
                TextButton(onClick = { navigator.push(BookOasisConnectScreen()) }) { Text("연결") }
            }
            key(revision) {
                if (source.baseUrl.isBlank()) {
                    DashboardFallback(
                        "북오아시스 서버를 연결해 주세요. 내려받은 책은 연결 없이 읽을 수 있습니다.",
                        onRetry = { navigator.push(BookOasisConnectScreen()) },
                        openShelf = openShelf,
                        retryLabel = "서버 연결",
                    )
                } else {
                    BookOasisServerWebView(source, openShelf)
                }
            }
        }
    }
}

@Composable
private fun DashboardFallback(
    message: String,
    onRetry: () -> Unit,
    openShelf: () -> Unit,
    retryLabel: String = "다시 연결",
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        Button(onClick = openShelf) { Text("오프라인 서재 열기") }
        OutlinedButton(onClick = onRetry) { Text(retryLabel) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BookOasisServerWebView(
    source: BookOasisSource,
    openShelf: () -> Unit,
    initialUrl: String = source.baseUrl + "/",
    reloadOnResume: Boolean = true,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var cookies by remember { mutableStateOf<List<String>?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val address = source.baseUrl
    val origin = bookOasisDashboardOrigin(address)
    val bridge = remember { context.assets.open("bookoasis_dashboard.js").bufferedReader().use { it.readText() } }
    LaunchedEffect(retry) {
        error = null
        cookies = null
        try {
            check(origin != null && sameBookOasisOrigin(initialUrl, address)) { "서버 주소를 확인해 주세요." }
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                "Android System WebView를 업데이트한 후 다시 연결해 주세요."
            }
            cookies = BookOasisDashboardSession.login(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "서버에 연결할 수 없습니다. 오프라인 서재를 이용해 주세요."
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (reloadOnResume && event == Lifecycle.Event.ON_RESUME) webView?.reload()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    val onBook: (BookOasisDashboardRequest) -> Unit = { selection ->
        if (!busy) {
            busy = true
            status = if (selection.action == BookOasisDashboardAction.Download) "오프라인 저장을 준비하는 중…" else "책을 여는 중…"
            scope.launch {
                try {
                    val cookie = CookieManager.getInstance().getCookie(address).orEmpty()
                    val (manga, chapter) = context.appGraph.bookOasisDashboardLibrary.prepare(source, selection, cookie)
                    context.appGraph.basePreferences.downloadedOnly.set(false)
                    if (selection.action == BookOasisDashboardAction.Download) {
                        context.appGraph.downloadManager.downloadChapters(manga, listOf(chapter), manualStart = true)
                        status = "다운로드 대기열에 추가했습니다. 완료 후 오프라인 서재에서 읽을 수 있습니다."
                    } else {
                        context.startActivity(ReaderActivity.newIntent(context, manga.id, chapter.id))
                        status = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status = "책을 열거나 저장하지 못했습니다. 연결·저장 공간·다운로드 권한을 확인해 주세요."
                } finally {
                    busy = false
                }
            }
        }
    }
    status?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
    if (busy || progress in 1..99) LinearProgressIndicator(Modifier.fillMaxWidth())
    when {
        error != null -> DashboardFallback(error!!, { retry++ }, openShelf)
        cookies == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            key(retry) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            WebViewCompat.addWebMessageListener(
                                this,
                                "BookOasisNative",
                                setOf(origin!!),
                            ) { _, message, sourceOrigin, isMainFrame, _ ->
                                if (isTrustedBookOasisDashboardMessage(address, sourceOrigin.toString(), isMainFrame)) {
                                    message.data?.let(::parseBookOasisDashboardMessage)?.let(onBook)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, value: Int) {
                                    progress = value
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean =
                                    !sameBookOasisOrigin(request.url.toString(), address)

                                override fun onPageFinished(view: WebView, url: String) {
                                    canGoBack = view.canGoBack()
                                    if (sameBookOasisOrigin(url, address)) {
                                        view.evaluateJavascript("($bridge)(${JSONObject.quote(address)})", null)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    failure: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) error = "서버에 연결할 수 없습니다. 내려받은 책은 오프라인 서재에서 열어 주세요."
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    response: WebResourceResponse,
                                ) {
                                    if (request.isForMainFrame && response.statusCode >= 400) {
                                        error = "대시보드를 불러오지 못했습니다. 연결 설정을 확인해 주세요."
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView,
                                    handler: SslErrorHandler,
                                    failure: SslError,
                                ) {
                                    handler.cancel()
                                    error = "서버 인증서를 확인할 수 없습니다. 보안 연결 설정을 확인해 주세요."
                                }
                            }
                            val manager = CookieManager.getInstance()
                            val sessionCookies = cookies.orEmpty()
                            var remaining = sessionCookies.size
                            sessionCookies.forEach { cookie ->
                                manager.setCookie(address, cookie) {
                                    if (--remaining == 0) {
                                        manager.flush()
                                        loadUrl(initialUrl)
                                    }
                                }
                            }
                        }
                    },
                    onRelease = { view ->
                        if (webView === view) webView = null
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}
