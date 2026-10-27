package eu.kanade.tachiyomi.ui.bookoasis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.source.bookoasis.BOOKOASIS_CORE_HOME_SECTIONS
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeBook
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeGoal
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeInsights
import eu.kanade.tachiyomi.source.bookoasis.BookOasisHomeSnapshot

/** Adds the server dashboard as native Compose sections in the host LazyColumn. */
internal fun LazyListScope.bookOasisHomeSections(
    snapshots: List<BookOasisHomeSnapshot>,
    cached: Boolean,
    openingKey: String?,
    onContinue: (BookOasisHomeBook) -> Unit,
    onDetails: (BookOasisHomeBook) -> Unit,
) {
    if (snapshots.isEmpty()) {
        item(key = "bookoasis-home-cache-status") {
            DashboardEmptyState(if (cached) "저장된 대시보드 정보가 없습니다." else "대시보드 정보를 아직 불러오지 못했습니다.")
        }
    }

    val multipleSources = snapshots.size > 1
    snapshots.forEachIndexed { snapshotIndex, snapshot ->
        val prefix = "bookoasis-home-$snapshotIndex-${snapshot.dbType}"
        val sections = snapshot.sectionOrder.distinct().filter { it in BOOKOASIS_CORE_HOME_SECTIONS }

        if (multipleSources) {
            item(key = "$prefix-source-heading") {
                Text(
                    text = snapshot.dbType.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
        }

        if (snapshot.totalBookCount != null || snapshot.totalSeriesCount != null) {
            item(key = "$prefix-totals") {
                DashboardTotals(snapshot.totalBookCount, snapshot.totalSeriesCount)
            }
        }

        if (snapshot.failedSections.isNotEmpty()) {
            item(key = "$prefix-failures") {
                val labels = snapshot.failedSections.map(::sectionLabel).distinct().take(3).joinToString(" · ")
                val more = if (snapshot.failedSections.size > 3) " 외" else ""
                DashboardNotice("일부 정보를 불러오지 못했습니다 · $labels$more")
            }
        }

        if (snapshot.webOnlyWidgets.isNotEmpty()) {
            item(key = "$prefix-web-widgets") {
                val names = snapshot.webOnlyWidgets.distinct().take(3).joinToString(" · ")
                val more = if (snapshot.webOnlyWidgets.size > 3) " 외" else ""
                DashboardNotice("웹 대시보드 전용 위젯은 네이티브 화면에서 지원하지 않습니다 · $names$more")
            }
        }

        sections.forEach { section ->
            when (section) {
                "core.reading_insights" -> {
                    item(key = "$prefix-reading-heading") {
                        SectionHeading("현재 읽는 책")
                    }
                    if (snapshot.currentlyReading.isEmpty()) {
                        item(key = "$prefix-current-reading-empty") {
                            DashboardEmptyState("읽는 중인 책이 없습니다.")
                        }
                    } else {
                        item(key = "$prefix-current-reading-row") {
                            CurrentlyReadingRow(snapshot.currentlyReading, openingKey, onContinue, onDetails)
                        }
                    }
                    snapshot.insights?.takeIf(::hasInsights)?.let { insights ->
                        item(key = "$prefix-insights-heading") { SectionSubheading("독서 현황") }
                        item(key = "$prefix-insights") { ReadingInsights(insights) }
                    }
                }

                "core.recent" -> {
                    item(key = "$prefix-recent-heading") {
                        SectionHeading("최근 읽은 책")
                    }
                    if (snapshot.recentBooks.isEmpty()) {
                        item(key = "$prefix-recent-empty") {
                            DashboardEmptyState("최근 읽은 책이 없습니다.")
                        }
                    } else {
                        item(key = "$prefix-recent-row") {
                            BookShelfRow(snapshot.recentBooks, showPosition = true, onDetails = onContinue)
                        }
                    }
                }

                "core.new" -> {
                    item(key = "$prefix-new-heading") {
                        SectionHeading("새로 추가된 책")
                    }
                    if (snapshot.newBooks.isEmpty()) {
                        item(key = "$prefix-new-empty") {
                            DashboardEmptyState("새로 추가된 책이 없습니다.")
                        }
                    } else {
                        item(key = "$prefix-new-row") {
                            BookShelfRow(snapshot.newBooks, showPosition = false, onDetails = onDetails)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTotals(bookCount: Int?, seriesCount: Int?) {
    val labels = buildList {
        bookCount?.let { add("서재 ${it}권") }
        seriesCount?.let { add("$it 시리즈") }
    }
    Text(
        text = labels.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

@Composable
private fun DashboardNotice(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun DashboardEmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionSubheading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ReadingInsights(insights: BookOasisHomeInsights) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            insights.streakDays?.let { days ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "연속 독서",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${days}일", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (insights.lastSevenDays.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "최근 7일",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        insights.lastSevenDays.forEachIndexed { index, read ->
                            val dayState = if (read) "읽음" else "기록 없음"
                            Box(
                                modifier = Modifier
                                    .size(width = 18.dp, height = 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (read) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    )
                                    .semantics { contentDescription = "${index + 1}일째 $dayState" },
                            )
                        }
                    }
                    Text("${insights.lastSevenDays.count { it }}일 읽음", style = MaterialTheme.typography.bodySmall)
                }
            }
            insights.annualGoal?.let { GoalProgress(it, "올해 목표") }
            insights.monthlyGoal?.let { GoalProgress(it, "월간 목표") }
            if (insights.genres.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "선호 장르",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 2.dp),
                    ) {
                        items(insights.genres) { genre ->
                            Text(
                                text = "${genre.name} ${genre.percent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalProgress(goal: BookOasisHomeGoal, fallbackLabel: String) {
    val label = if (goal.label.toIntOrNull() != null) "${goal.label}년 독서 목표" else fallbackLabel
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                "${goal.completed} / ${goal.target} · ${goal.percent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { goal.percent.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun CurrentlyReadingRow(
    books: List<BookOasisHomeBook>,
    openingKey: String?,
    onContinue: (BookOasisHomeBook) -> Unit,
    onDetails: (BookOasisHomeBook) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.key }) { book ->
            Card(modifier = Modifier.width(304.dp), shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = { onDetails(book) })
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MangaCover.Book(
                            data = book.coverUrl,
                            modifier = Modifier.width(72.dp),
                            contentDescription = "${book.accessibleTitle} 표지",
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = book.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = book.volumeTitle?.let { "$it · ${formatLabel(book.format)}" }
                                    ?: formatLabel(book.format),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = book.positionLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            book.progressPercent?.let { percent ->
                                LinearProgressIndicator(
                                    progress = { percent.coerceIn(0, 100) / 100f },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                            if (book.pendingSync) {
                                Text(
                                    "기기 위치 동기화 대기 중",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { onContinue(book) },
                        enabled = openingKey == null,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription =
                                "${book.accessibleTitle} 이어 읽기"
                        },
                    ) {
                        Text(if (openingKey == book.key) "여는 중…" else "이어 읽기")
                    }
                }
            }
        }
    }
}

@Composable
private fun BookShelfRow(
    books: List<BookOasisHomeBook>,
    showPosition: Boolean,
    onDetails: (BookOasisHomeBook) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(books, key = { it.key }) { book ->
            Card(
                modifier = Modifier.width(148.dp)
                    .semantics { contentDescription = "${book.accessibleTitle} ${if (showPosition) "이어 읽기" else "상세"}" }
                    .clickable(role = Role.Button, onClick = { onDetails(book) }),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MangaCover.Book(
                        data = book.coverUrl,
                        modifier = Modifier.width(76.dp).align(Alignment.CenterHorizontally),
                        contentDescription = "${book.accessibleTitle} 표지",
                    )
                    Text(
                        text = book.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.volumeTitle?.let { volume ->
                        Text(
                            text = volume,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = formatLabel(book.format),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showPosition) {
                        Text(
                            text = if (book.completed) "완독 · ${book.positionLabel}" else book.positionLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun hasInsights(insights: BookOasisHomeInsights): Boolean =
    insights.streakDays != null || insights.lastSevenDays.isNotEmpty() ||
        insights.annualGoal != null || insights.monthlyGoal != null || insights.genres.isNotEmpty()

private fun formatLabel(format: String): String = when (format.lowercase()) {
    "imgdir" -> "이미지 폴더"
    "zip" -> "ZIP"
    "cbz" -> "CBZ"
    "epub" -> "EPUB"
    "pdf" -> "PDF"
    "txt" -> "TXT"
    else -> format.uppercase()
}

private fun sectionLabel(section: String): String = when (section) {
    "core.reading_insights", "insights" -> "읽기 인사이트"
    "core.recent", "history" -> "최근 읽은 책"
    "core.new", "new", "new_books" -> "신간"
    "totals" -> "서재 통계"
    "layout" -> "대시보드 구성"
    "widgets" -> "위젯"
    else -> section.replace('_', ' ').replace('.', ' ')
}
