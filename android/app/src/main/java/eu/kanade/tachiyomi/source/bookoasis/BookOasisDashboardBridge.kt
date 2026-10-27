package eu.kanade.tachiyomi.source.bookoasis

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal fun bookOasisDashboardDetailPath(path: String): String =
    if (path.startsWith("/app-opds/api/media/detail?")) path.removePrefix("/app-opds") else path

internal enum class BookOasisDashboardAction { Open, Download }

internal data class BookOasisDashboardRequest(
    val action: BookOasisDashboardAction,
    val bookId: Long,
    val dbType: String,
    val libraryId: String = "all",
)

/** Only the configured HTTP(S) origin may issue a bounded book action. No arbitrary URL/file payload. */
internal fun bookOasisDashboardOrigin(address: String): String? {
    val url = address.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
}

internal fun isTrustedBookOasisDashboardMessage(
    address: String,
    sourceOrigin: String,
    isMainFrame: Boolean,
): Boolean {
    if (!isMainFrame) return false
    val expected = bookOasisDashboardOrigin(address) ?: return false
    return bookOasisDashboardOrigin(sourceOrigin) == expected
}

internal fun parseBookOasisDashboardMessage(message: String): BookOasisDashboardRequest? {
    if (message.length > 1024) return null
    return runCatching {
        val json = JSONObject(message)
        val action = when (json.optString("action")) {
            "open" -> BookOasisDashboardAction.Open
            "download" -> BookOasisDashboardAction.Download
            else -> return null
        }
        val rawId = json.opt("bookId")
        if (rawId !is Number && rawId !is String) return null
        val bookId = rawId.toString().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val dbType = json.optString("dbType", "general")
        if (dbType !in setOf("general", "adult")) return null
        val libraryId = json.optString("libraryId", "all").ifBlank { "all" }
        if (libraryId != "all" && (libraryId.length > 20 || libraryId.toLongOrNull()?.let { it > 0 } != true)) return null
        BookOasisDashboardRequest(action, bookId, dbType, libraryId)
    }.getOrNull()
}
