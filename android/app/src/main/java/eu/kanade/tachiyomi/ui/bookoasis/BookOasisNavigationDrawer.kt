package eu.kanade.tachiyomi.ui.bookoasis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.bookoasis.BookOasisLibraryMenu
import eu.kanade.tachiyomi.source.bookoasis.BookOasisMenu
import eu.kanade.tachiyomi.source.bookoasis.BookOasisPluginMenu

@Composable
internal fun BookOasisNavigationDrawer(
    menus: List<BookOasisMenu>,
    loading: Boolean,
    failed: Boolean,
    selectedLibrary: BookOasisLibraryMenu?,
    dashboard: Boolean,
    downloadCount: Int,
    onHome: () -> Unit,
    onAll: () -> Unit,
    onLibrary: (BookOasisLibraryMenu) -> Unit,
    onPlugin: (BookOasisPluginMenu) -> Unit,
    onOffline: () -> Unit,
    onDownloads: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.86f)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Text("BookOasis", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                DrawerItem("메인 대시보드", dashboard, onHome)
                DrawerItem("전체 도서", !dashboard && selectedLibrary == null, onAll)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
            }
            if (loading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            if (failed) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text("서버 메뉴를 불러오지 못했습니다. 연결을 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("메뉴 다시 불러오기") }
                    }
                }
            }
            menus.forEach { menu ->
                if (menus.size > 1) {
                    item {
                        MenuHeading(if (menu.dbType == "adult") "성인 도서" else "일반 도서")
                    }
                }
                // Preserve the server's group order and category membership, including mixed plugin groups.
                val groupIds = menu.groups.map { it.id }.toSet()
                val sections = listOf(null) + menu.groups
                sections.forEach { group ->
                    val libraries = menu.libraries.filter {
                        if (group == null) it.groupId !in groupIds else it.groupId == group.id
                    }
                    val plugins = menu.plugins.filter {
                        if (group == null) it.groupId !in groupIds else it.groupId == group.id
                    }
                    if (group != null &&
                        (libraries.isNotEmpty() || plugins.isNotEmpty())
                    ) {
                        item { MenuHeading(group.title) }
                    }
                    if (group == null && libraries.isNotEmpty()) item { MenuHeading("도서 분류") }
                    libraries.forEach { library ->
                        item(key = "library:${library.key}") {
                            DrawerItem(library.title, selectedLibrary?.key == library.key) { onLibrary(library) }
                        }
                    }
                    if (group == null && plugins.isNotEmpty()) item { MenuHeading("플러그인") }
                    plugins.forEach { plugin ->
                        item(key = "plugin:${plugin.dbType}:${plugin.id}") {
                            DrawerItem(plugin.title, false) { onPlugin(plugin) }
                        }
                    }
                }
            }
            if (!loading && !failed && menus.all { it.libraries.isEmpty() && it.plugins.isEmpty() }) {
                item {
                    Text("이 계정에 표시할 서버 메뉴가 없습니다.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                DrawerItem("오프라인 서재", false, onOffline)
                DrawerItem("다운로드 · $downloadCount", false, onDownloads)
                DrawerItem("서버 연결", false, onConnect)
            }
        }
    }
}

@Composable
private fun MenuHeading(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerItem(title: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
