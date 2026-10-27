package eu.kanade.tachiyomi.source.bookoasis

import eu.kanade.tachiyomi.source.bookoasis.progress.BookOasisPendingProgress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class BookOasisHomeBook(
    val bookId: Long,
    val dbType: String,
    val libraryId: String,
    val title: String,
    val seriesName: String,
    val format: String,
    val coverUrl: String?,
    val author: String?,
    val pagesRead: Int,
    val totalPages: Int,
    val progressPercent: Int?,
    val completed: Boolean,
    val lastReadAt: String? = null,
    val devicePage: Int? = null,
    val deviceTotalPages: Int? = null,
    val pendingSync: Boolean = false,
) {
    val key: String get() = "$dbType:$bookId"
    val displayTitle: String get() = seriesName.ifBlank { title }
    val volumeTitle: String? get() = title.takeIf { it != displayTitle }
    val accessibleTitle: String get() = volumeTitle?.let { "$displayTitle · $it" } ?: displayTitle
    val positionLabel: String get() = when {
        devicePage != null && deviceTotalPages != null -> "$devicePage / $deviceTotalPages 쪽"
        format in setOf("epub", "txt") -> progressPercent?.let { "$it%" } ?: "읽기 시작 전"
        totalPages > 0 -> "$pagesRead / $totalPages 쪽"
        else -> progressPercent?.let { "$it%" } ?: "읽기 시작 전"
    }
}

internal data class BookOasisHomeGoal(val completed: Int, val target: Int, val percent: Int, val label: String)
internal data class BookOasisHomeGenre(val name: String, val percent: Int)
internal data class BookOasisHomeInsights(
    val streakDays: Int?,
    val lastSevenDays: List<Boolean>,
    val annualGoal: BookOasisHomeGoal?,
    val monthlyGoal: BookOasisHomeGoal?,
    val genres: List<BookOasisHomeGenre>,
)

internal data class BookOasisHomeSnapshot(
    val dbType: String,
    val currentlyReading: List<BookOasisHomeBook>,
    val recentBooks: List<BookOasisHomeBook>,
    val newBooks: List<BookOasisHomeBook>,
    val insights: BookOasisHomeInsights?,
    val totalBookCount: Int?,
    val totalSeriesCount: Int?,
    val sectionOrder: List<String>,
    val webOnlyWidgets: List<String>,
    val failedSections: List<String>,
    val fetchedAt: Long,
)

internal val BOOKOASIS_CORE_HOME_SECTIONS = listOf("core.reading_insights", "core.recent", "core.new")
private val HOME_FORMATS = setOf("zip", "cbz", "imgdir", "epub", "pdf", "txt")

internal fun parseBookOasisHomeBook(value: JSONObject, baseUrl: String, dbType: String): BookOasisHomeBook? {
    if (dbType !in setOf("general", "adult")) return null
    val id = value.opt("id")?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val format = value.text("file_format") ?: value.text("format") ?: return null
    if (format.lowercase() !in HOME_FORMATS) return null
    val title =
        value.text("title_alias") ?: value.text("title") ?: value.text("representative_title")
            ?: value.text("series_name")
            ?: return null
    val total = value.nonNegativeInt("total_pages") ?: 0
    val read = value.nonNegativeInt("current_page") ?: value.nonNegativeInt("pages_read") ?: 0
    val completed =
        value.optBoolean("is_completed", false) || value.optInt("is_completed", 0) == 1 || (total > 0 && read >= total)
    val percent = if (completed) {
        100
    } else {
        value.nonNegativeInt("progress_pct")?.coerceAtMost(100)
            ?: if (format.equals("epub", ignoreCase = true)) {
                read.coerceIn(0, 100)
            } else {
                total.takeIf { it > 0 }?.let { (read * 100.0 / it).roundToInt().coerceIn(0, 100) }
            }
    }
    val rawCover = value.text("cover_url") ?: value.text("cover_image")
    val server = baseUrl.toHttpUrlOrNull()
    val cover = bookOasisCoverUrl(baseUrl, rawCover)
        ?: server?.newBuilder()?.addPathSegments(
            "api/cover/$id",
        )?.addQueryParameter("type", dbType)?.build()?.toString()
    return BookOasisHomeBook(
        bookId = id,
        dbType = dbType,
        libraryId = value.text("library_id") ?: "all",
        title = title,
        seriesName = value.text("series_alias") ?: value.text("series_name") ?: title,
        format = format.lowercase(),
        coverUrl = cover,
        author = value.text("author"),
        pagesRead = read,
        totalPages = total,
        progressPercent = percent,
        completed = completed,
        lastReadAt = value.text("last_read_at"),
    )
}

internal fun parseBookOasisHomeSnapshot(
    baseUrl: String,
    dbType: String,
    history: JSONObject?,
    newBooks: JSONObject?,
    insights: JSONObject?,
    totals: JSONObject?,
    layout: JSONObject?,
    failedSections: List<String> = emptyList(),
    fetchedAt: Long,
): BookOasisHomeSnapshot {
    fun books(array: JSONArray?) = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { parseBookOasisHomeBook(it, baseUrl, dbType) }?.let(::add)
            }
        }
    }.distinctBy { it.key }
    val recent = books(history?.optJSONArray("books"))
    val current = if (insights?.has("currently_reading") == true) {
        books(insights.optJSONArray("currently_reading"))
    } else {
        recent.filter { !it.completed && it.pagesRead > 0 }
    }
    val pluginMode = layout?.text("mode") == "plugin"
    val configured = layout?.optJSONArray("widgets")
    val visible = buildList<JSONObject> {
        if (configured != null) {
            for (i in 0 until configured.length()) {
                configured.optJSONObject(i)?.takeUnless { it.optBoolean("hidden") }?.let(::add)
            }
        }
    }
    val webWidgets = if (pluginMode) visible.filter { it.text("kind") == "plugin" } else emptyList()
    return BookOasisHomeSnapshot(
        dbType = dbType,
        currentlyReading = current,
        recentBooks = recent,
        newBooks = books(newBooks?.optJSONArray("books")),
        insights = insights?.let(::parseHomeInsights),
        totalBookCount = totals?.nonNegativeInt("total_book_count"),
        totalSeriesCount = totals?.nonNegativeInt("total_series_count"),
        sectionOrder = if (pluginMode) {
            visible.mapNotNull { it.text("id") }.filter { it in BOOKOASIS_CORE_HOME_SECTIONS }
        } else {
            BOOKOASIS_CORE_HOME_SECTIONS
        },
        webOnlyWidgets = webWidgets.mapNotNull { it.text("title") ?: it.text("name") ?: it.text("id") },
        failedSections = failedSections,
        fetchedAt = fetchedAt,
    )
}

private fun parseHomeInsights(value: JSONObject): BookOasisHomeInsights {
    fun goal(name: String, labelField: String): BookOasisHomeGoal? = value.optJSONObject(name)?.let { goal ->
        val complete = goal.nonNegativeInt("completed") ?: return@let null
        val target = goal.nonNegativeInt("target") ?: return@let null
        BookOasisHomeGoal(
            complete,
            target,
            goal.nonNegativeInt("pct")?.coerceAtMost(100) ?: 0,
            goal.text(labelField).orEmpty(),
        )
    }
    val days = value.optJSONObject("streak")?.optJSONArray("last_7_days")
    val genres = value.optJSONArray("genre_breakdown")
    return BookOasisHomeInsights(
        streakDays = value.optJSONObject("streak")?.nonNegativeInt("days"),
        lastSevenDays = if (days == null) emptyList() else (0 until days.length()).map { days.optBoolean(it) },
        annualGoal = goal("annual_goal", "year"),
        monthlyGoal = goal("monthly_challenge", "month_name"),
        genres = buildList {
            if (genres != null) {
                for (i in 0 until genres.length()) {
                    val genre = genres.optJSONObject(i) ?: continue
                    val name = genre.text("name") ?: continue
                    val percent = genre.nonNegativeInt("pct") ?: continue
                    add(BookOasisHomeGenre(name, percent.coerceAtMost(100)))
                }
            }
        },
    )
}

/** Pending device position wins visibly until it is confirmed; remote data is never overwritten here. */
internal fun overlayBookOasisHomeProgress(
    snapshot: BookOasisHomeSnapshot,
    pending: List<BookOasisPendingProgress>,
    sourceId: Long,
    localBooks: List<BookOasisHomeBook> = emptyList(),
): BookOasisHomeSnapshot {
    val updates = pending.filter { it.sourceId == sourceId && it.dbType == snapshot.dbType && it.totalPages > 0 }
        .associateBy { it.bookId }
    if (updates.isEmpty()) return snapshot
    fun update(book: BookOasisHomeBook): BookOasisHomeBook {
        val item = updates[book.bookId] ?: return book
        val page = (item.pageIndex + 1).coerceIn(1, item.totalPages)
        return book.copy(
            devicePage = page,
            deviceTotalPages = item.totalPages,
            progressPercent = (page * 100.0 / item.totalPages).roundToInt().coerceIn(0, 100),
            completed = page == item.totalPages,
            pendingSync = true,
        )
    }
    val pool = (snapshot.currentlyReading + snapshot.recentBooks + snapshot.newBooks + localBooks)
        .filter { it.dbType == snapshot.dbType }.distinctBy { it.key }
    val pendingBooks = pool.filter {
        it.bookId in updates
    }.sortedByDescending { updates[it.bookId]?.updatedAt }.map(::update)
    val current = (pendingBooks + snapshot.currentlyReading.map(::update)).distinctBy {
        it.key
    }.filterNot { it.completed }
    val recent = (pendingBooks + snapshot.recentBooks.map(::update)).distinctBy { it.key }
    return snapshot.copy(currentlyReading = current, recentBooks = recent, newBooks = snapshot.newBooks.map(::update))
}

private fun JSONObject.text(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
private fun JSONObject.nonNegativeInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name, -1).takeIf { it >= 0 }
