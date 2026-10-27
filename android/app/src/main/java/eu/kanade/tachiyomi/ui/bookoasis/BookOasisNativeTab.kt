package eu.kanade.tachiyomi.ui.bookoasis

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.bookoasis.BookOasisDashboardSession
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeBook
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeRepository
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeSnapshot
import eu.kanade.tachiyomi.source.bookoasis.BookOasisLibraryMenu
import eu.kanade.tachiyomi.source.bookoasis.BookOasisMenu
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.fetchBookOasisMenu
import eu.kanade.tachiyomi.source.bookoasis.overlayBookOasisHomeProgress
import eu.kanade.tachiyomi.source.bookoasis.progress.BookOasisProgressApi
import eu.kanade.tachiyomi.source.bookoasis.progress.BookOasisProgressStore
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.main.BookOasisConnectScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Menu
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

private val CONNECTION_PREFERENCE_KEYS = setOf(
    "address",
    "username",
    "password",
    "library_type",
)

internal data object BookOasisNativeTab : Tab {
    override val options: TabOptions
        @Composable get() {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(0u, "북오아시스", rememberAnimatedVectorPainter(image, true))
        }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val graph = context.appGraph
        val navigator = LocalNavigator.currentOrThrow
        val tabs = LocalTabNavigator.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val source = remember { BookOasisSource(1) }
        val prefs = remember { source.getSourcePreferences() }
        val scope = rememberCoroutineScope()
        val downloads by graph.downloadManager.queueState.collectAsState()
        val homeRepository = remember { BookOasisHomeRepository(context) }
        val progressStore = remember { BookOasisProgressStore(context) }
        val pendingProgress by remember(progressStore) {
            progressStore.observeItems()
        }.collectAsState(initial = emptyList())

        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val openGestureDistance = with(LocalDensity.current) { 48.dp.toPx() }
        var selectedLibrary by rememberSaveable { mutableStateOf<BookOasisLibraryMenu?>(null) }
        var browseAll by rememberSaveable { mutableStateOf(false) }
        var menus by remember { mutableStateOf<List<BookOasisMenu>>(emptyList()) }
        var menuLoading by remember { mutableStateOf(false) }
        var menuError by remember { mutableStateOf(false) }
        var loadedMenuKey by rememberSaveable { mutableStateOf("") }
        var query by rememberSaveable { mutableStateOf("") }
        val showDashboard = selectedLibrary == null && !browseAll && query.isBlank()
        var books by remember { mutableStateOf<List<SManga>>(emptyList()) }
        var isLoading by remember { mutableStateOf(false) }
        var isLoadingMore by remember { mutableStateOf(false) }
        var hasNextPage by remember { mutableStateOf(false) }
        var page by remember { mutableIntStateOf(1) }
        var loadError by remember { mutableStateOf(false) }
        var loadMoreError by remember { mutableStateOf(false) }
        var loadMoreJob by remember { mutableStateOf<Job?>(null) }
        var openingUrl by remember { mutableStateOf<String?>(null) }
        var openError by remember { mutableStateOf(false) }
        var preferenceRevision by remember { mutableIntStateOf(0) }
        var retryRevision by remember { mutableIntStateOf(0) }
        var resumeRevision by remember { mutableIntStateOf(0) }
        var hasResumedOnce by remember { mutableStateOf(false) }
        var homeActive by remember {
            mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        var homeSnapshots by remember { mutableStateOf<List<BookOasisHomeSnapshot>>(emptyList()) }
        var localHomeBooks by remember { mutableStateOf<List<BookOasisHomeBook>>(emptyList()) }
        var loadedHomeKey by remember { mutableStateOf("") }
        val activeHomeKey = remember(preferenceRevision) { homeRepository.key(source) }
        var homeLoading by remember { mutableStateOf(false) }
        var homeError by remember { mutableStateOf(false) }
        var homeRefreshRevision by remember { mutableIntStateOf(0) }
        var hadPendingProgress by remember { mutableStateOf(false) }
        var homeOpenError by remember { mutableStateOf<String?>(null) }
        val currentProgressBinding = BookOasisProgressApi.localAccountBinding(source)
        val accountPendingProgress = currentProgressBinding?.let { binding ->
            pendingProgress.filter { it.accountBinding == binding }
        }.orEmpty()
        val displayedHome = if (loadedHomeKey != activeHomeKey) {
            emptyList()
        } else {
            homeSnapshots.map {
                overlayBookOasisHomeProgress(it, accountPendingProgress, source.id, localHomeBooks)
            }
        }
        val readerBridgeStatus = when {
            pendingProgress.any { it.status == BookOasisProgressStore.STATUS_CONFLICT } ->
                "최근에 읽은 위치로 자동 동기화하고 있습니다."
            pendingProgress.any { it.status == BookOasisProgressStore.STATUS_ACCOUNT_CHANGED } ->
                "계정이 달라 읽기 진행 업로드를 보류했습니다. 기존 대기 데이터는 보존됩니다."
            pendingProgress.any { it.status == BookOasisProgressStore.STATUS_UNBOUND } ->
                "계정에 연결되지 않은 이전 읽기 진행 데이터가 있어 검토 전까지 업로드하지 않습니다."
            pendingProgress.any {
                it.status == BookOasisProgressStore.STATUS_API_UNAVAILABLE ||
                    it.status == BookOasisProgressStore.STATUS_UNSUPPORTED
            } -> "BookOasis 기본 API로 읽기 위치를 확인할 수 없어 동기화를 보류했습니다. 대기 데이터는 보존됩니다."
            else -> null
        }

        // This tab displays the online server catalog, not a downloaded-only library.
        // Returning from the offline shelf must not leave the global offline-only banner
        // claiming that the unfiltered server list contains only downloaded books.
        LaunchedEffect(Unit) { graph.basePreferences.downloadedOnly.set(false) }

        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in CONNECTION_PREFERENCE_KEYS) preferenceRevision++
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) homeActive = false
                if (event == Lifecycle.Event.ON_RESUME) {
                    homeActive = true
                    if (hasResumedOnce) resumeRevision++ else hasResumedOnce = true
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(activeHomeKey, retryRevision, resumeRevision, homeRefreshRevision, homeActive) {
            if (!homeActive) return@LaunchedEffect
            graph.basePreferences.downloadedOnly.set(false)
            homeError = false
            homeLoading = false
            if (source.baseUrl.isBlank()) {
                homeSnapshots = emptyList()
                loadedHomeKey = activeHomeKey
                return@LaunchedEffect
            }
            if (loadedHomeKey != activeHomeKey || homeSnapshots.isEmpty()) {
                homeSnapshots = withContext(Dispatchers.IO) { homeRepository.cached(source) }
                loadedHomeKey = activeHomeKey
            }
            homeLoading = true
            try {
                homeSnapshots = homeRepository.refresh(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                homeError = true
            } finally {
                homeLoading = false
            }
        }

        LaunchedEffect(accountPendingProgress, homeActive, activeHomeKey) {
            if (!homeActive) return@LaunchedEffect
            localHomeBooks = graph.bookOasisDashboardLibrary.localHomeBooks(source, accountPendingProgress)
            if (hadPendingProgress && accountPendingProgress.isEmpty()) homeRefreshRevision++
            hadPendingProgress = accountPendingProgress.isNotEmpty()
        }

        LaunchedEffect(activeHomeKey, retryRevision, resumeRevision) {
            if (loadedMenuKey != activeHomeKey) {
                menus = emptyList()
                selectedLibrary = null
                browseAll = false
                query = ""
                loadedMenuKey = activeHomeKey
            }
            menuError = false
            if (source.baseUrl.isBlank()) return@LaunchedEffect
            menuLoading = true
            try {
                BookOasisDashboardSession.login(source)
                menus = source.configuredLibraryTypes().map { type ->
                    fetchBookOasisMenu(source.client, source.baseUrl, source.currentHeaders(), type)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "BookOasis native menu refresh failed"
                }
                menuError = true
            } finally {
                menuLoading = false
            }
        }

        LaunchedEffect(query, selectedLibrary, preferenceRevision, retryRevision, resumeRevision) {
            loadMoreJob?.cancel()
            loadMoreJob = null
            isLoadingMore = false
            books = emptyList()
            page = 1
            hasNextPage = false
            loadError = false
            loadMoreError = false

            if (source.baseUrl.isBlank()) {
                isLoading = false
                return@LaunchedEffect
            }

            isLoading = true
            try {
                if (query.isNotBlank()) delay(300)
                val requestedQuery = query.trim()
                val result = withContext(Dispatchers.IO) {
                    if (selectedLibrary != null) {
                        source.getLibraryManga(1, requestedQuery, selectedLibrary!!)
                    } else if (requestedQuery.isEmpty()) {
                        source.getPopularManga(1)
                    } else {
                        source.getSearchManga(1, requestedQuery, FilterList())
                    }
                }
                books = result.mangas
                hasNextPage = result.hasNextPage
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadError = true
            } finally {
                isLoading = false
            }
        }

        val openOfflineShelf = {
            graph.basePreferences.downloadedOnly.set(true)
            tabs.current = LibraryTab
        }
        val openBook: (SManga) -> Unit = { remote ->
            if (openingUrl == null) {
                openingUrl = remote.url
                openError = false
                scope.launch {
                    try {
                        val manga = withContext(Dispatchers.IO) {
                            graph.bookOasisDashboardLibrary.importSeries(source, remote)
                        }
                        graph.basePreferences.downloadedOnly.set(false)
                        navigator.push(MangaScreen(manga.id))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        openError = true
                    } finally {
                        openingUrl = null
                    }
                }
            }
        }
        val openHomeBook: (BookOasisHomeBook, Boolean) -> Unit = { book, continueReading ->
            if (openingUrl == null) {
                openingUrl = book.key
                homeOpenError = null
                scope.launch {
                    try {
                        val (manga, chapter) = graph.bookOasisDashboardLibrary.prepareHomeBook(source, book)
                        if (continueReading) {
                            val downloaded = withContext(Dispatchers.IO) {
                                graph.downloadManager.isChapterDownloadedOnDisk(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    manga.title,
                                    source,
                                )
                            }
                            if (homeError && !downloaded) {
                                homeOpenError = "오프라인에서는 내려받은 책만 이어 읽을 수 있습니다."
                            } else {
                                graph.basePreferences.downloadedOnly.set(homeError && downloaded)
                                context.startActivity(ReaderActivity.newIntent(context, manga.id, chapter.id))
                            }
                        } else {
                            navigator.push(MangaScreen(manga.id))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        homeOpenError = "책을 열지 못했습니다. 연결을 확인하거나 오프라인 서재를 이용해 주세요."
                    } finally {
                        openingUrl = null
                    }
                }
            }
        }
        val loadNextPage: () -> Unit = {
            if (!isLoadingMore && hasNextPage) {
                isLoadingMore = true
                loadMoreError = false
                loadMoreJob = scope.launch {
                    try {
                        val nextPage = page + 1
                        val requestedQuery = query.trim()
                        val result = withContext(Dispatchers.IO) {
                            if (selectedLibrary != null) {
                                source.getLibraryManga(nextPage, requestedQuery, selectedLibrary!!)
                            } else if (requestedQuery.isEmpty()) {
                                source.getPopularManga(nextPage)
                            } else {
                                source.getSearchManga(nextPage, requestedQuery, FilterList())
                            }
                        }
                        books = books + result.mangas.filterNot { manga -> books.any { it.url == manga.url } }
                        page = nextPage
                        hasNextPage = result.hasNextPage
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        loadMoreError = true
                    } finally {
                        isLoadingMore = false
                        loadMoreJob = null
                    }
                }
            }
        }

        BackHandler(enabled = drawerState.isClosed && (selectedLibrary != null || browseAll)) {
            selectedLibrary = null
            browseAll = false
            query = ""
        }
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                BookOasisNavigationDrawer(
                    menus = if (loadedMenuKey == activeHomeKey) menus else emptyList(),
                    loading = menuLoading,
                    failed = menuError,
                    selectedLibrary = selectedLibrary,
                    dashboard = selectedLibrary == null && !browseAll,
                    downloadCount = downloads.size,
                    onHome = {
                        selectedLibrary = null
                        browseAll = false
                        query = ""
                        scope.launch { drawerState.close() }
                    },
                    onAll = {
                        selectedLibrary = null
                        browseAll = true
                        query = ""
                        scope.launch { drawerState.close() }
                    },
                    onLibrary = {
                        selectedLibrary = it
                        browseAll = true
                        query = ""
                        scope.launch { drawerState.close() }
                    },
                    onPlugin = { plugin ->
                        scope.launch {
                            drawerState.close()
                            navigator.push(BookOasisPluginScreen(plugin.id, plugin.title, plugin.dbType))
                        }
                    },
                    onOffline = {
                        scope.launch {
                            drawerState.close()
                            openOfflineShelf()
                        }
                    },
                    onDownloads = {
                        scope.launch {
                            drawerState.close()
                            navigator.push(DownloadQueueScreen)
                        }
                    },
                    onConnect = {
                        scope.launch {
                            drawerState.close()
                            navigator.push(BookOasisConnectScreen())
                        }
                    },
                    onRetry = { retryRevision++ },
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(MaterialSymbols.Rounded.Menu, contentDescription = "북오아시스 메뉴 열기")
                            }
                            Text(
                                selectedLibrary?.title ?: if (browseAll) "전체 도서" else "BookOasis",
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { retryRevision++ }) {
                                Icon(MaterialSymbols.Rounded.Refresh, contentDescription = "홈 새로고침")
                            }
                            OutlinedButton(onClick = { navigator.push(BookOasisConnectScreen()) }) {
                                Text("연결")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text(selectedLibrary?.let { "${it.title} 안에서 검색" } ?: "서버 책 검색") },
                        placeholder = { Text("책 제목이나 저자") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = openOfflineShelf,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("오프라인 서재", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = { navigator.push(DownloadQueueScreen) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("다운로드 · ${downloads.size}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    readerBridgeStatus?.let {
                        Text(
                            text = it,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pendingProgress.any { item ->
                                    item.status == BookOasisProgressStore.STATUS_CONFLICT ||
                                        item.status == BookOasisProgressStore.STATUS_ACCOUNT_CHANGED
                                }
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // Keep stale/offline status outside the keyed list: inserting a new first item
                    // preserves the old visible key and can otherwise hide this important notice.
                    if (showDashboard && homeError && displayedHome.isNotEmpty()) {
                        Text(
                            "저장된 대시보드 정보입니다. 서버 연결 시 갱신됩니다.",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (openError || homeOpenError != null) {
                        Text(
                            text = homeOpenError ?: "책을 열지 못했습니다. 다시 시도해 주세요.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    when {
                        source.baseUrl.isBlank() -> EmptyCatalog(
                            message = "서버를 연결하면 책 목록을 볼 수 있습니다. 내려받은 책은 오프라인 서재에서 읽을 수 있습니다.",
                            actionLabel = "서버 연결",
                            onAction = { navigator.push(BookOasisConnectScreen()) },
                            secondaryLabel = "오프라인 서재 열기",
                            onSecondaryAction = openOfflineShelf,
                        )

                        (isLoading || (showDashboard && homeLoading)) &&
                            books.isEmpty() && (!showDashboard || displayedHome.isEmpty()) -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        loadError && books.isEmpty() && (!showDashboard || displayedHome.isEmpty()) -> EmptyCatalog(
                            message = "책 목록을 불러오지 못했습니다. 서버 연결을 확인해 주세요.",
                            actionLabel = "다시 불러오기",
                            onAction = { retryRevision++ },
                            secondaryLabel = "오프라인 서재 열기",
                            onSecondaryAction = openOfflineShelf,
                        )

                        books.isEmpty() && (!showDashboard || displayedHome.isEmpty()) -> EmptyCatalog(
                            message = "표시할 책이 없습니다.",
                            actionLabel = "새로고침",
                            onAction = { retryRevision++ },
                            secondaryLabel = "서버 연결",
                            onSecondaryAction = { navigator.push(BookOasisConnectScreen()) },
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (showDashboard) {
                                bookOasisHomeSections(
                                    snapshots = displayedHome,
                                    cached = homeError,
                                    openingKey = openingUrl,
                                    onContinue = { openHomeBook(it, true) },
                                    onDetails = { openHomeBook(it, false) },
                                )
                                if (homeLoading) {
                                    item {
                                        Text(
                                            "대시보드 새로고침 중…",
                                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                if (homeError && displayedHome.isEmpty()) {
                                    item {
                                        Text(
                                            "대시보드 자료를 불러오지 못했습니다. 홈 새로고침으로 다시 시도해 주세요.",
                                            Modifier.padding(16.dp),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                item {
                                    Text(
                                        "서버 전체 도서",
                                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                            items(books, key = { it.url }) { book ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = openingUrl == null,
                                            role = Role.Button,
                                            onClick = { openBook(book) },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MangaCover.Book(
                                        data = MangaCoverData(
                                            mangaId = 0L,
                                            sourceId = source.id,
                                            isMangaFavorite = false,
                                            url = book.thumbnail_url,
                                            lastModified = 0L,
                                        ),
                                        modifier = Modifier.width(76.dp),
                                        contentDescription = book.title,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        book.author?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        book.description?.takeIf {
                                            it.isNotBlank() && it != book.title
                                        }?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (openingUrl == book.url) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                            item {
                                when {
                                    isLoadingMore -> Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                    hasNextPage -> Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Button(onClick = loadNextPage) {
                                            Text(if (loadMoreError) "다시 불러오기" else "더 보기")
                                        }
                                    }

                                    loadError -> Text(
                                        text = "책 목록을 새로 고치지 못했습니다.",
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (drawerState.isClosed) {
                    // Keep an app-side edge gesture above horizontal book shelves. The outermost
                    // 24dp remains Android's system Back edge; dragging here opens the menu instead.
                    Box(
                        Modifier.fillMaxHeight().width(24.dp).offset(x = 24.dp).padding(top = 80.dp)
                            .pointerInput(openGestureDistance) {
                                var distance = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { distance = 0f },
                                    onDragCancel = { distance = 0f },
                                    onDragEnd = {
                                        if (distance > openGestureDistance) scope.launch { drawerState.open() }
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        distance += amount
                                        change.consume()
                                    },
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCatalog(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String,
    onSecondaryAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        TextButton(onClick = onSecondaryAction) { Text(secondaryLabel) }
    }
}
