package eu.kanade.tachiyomi.source.bookoasis

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Match the web cover resolver: relative filenames, including subfolders, live under /covers/. */
internal fun bookOasisCoverUrl(baseUrl: String, rawCover: String?): String? {
    val server = baseUrl.toHttpUrlOrNull() ?: return null
    val raw = rawCover?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.replace('\\', '/') ?: return null
    if (server.username.isNotEmpty() || server.password.isNotEmpty() || raw.startsWith("//")) return null
    val absolute = raw.toHttpUrlOrNull()
    val resolved = if (absolute != null) {
        absolute
    } else {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) return null
        val path = raw.trimStart('/')
        val target = when {
            path.startsWith("api/") || path.startsWith("covers/") ||
                path.startsWith("app-opds/covers/") || path.startsWith("app-opds-adult/covers/") -> "/$path"
            else -> "/covers/$path"
        }
        server.resolve(target)
    } ?: return null
    if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
    return resolved.toString().takeIf { sameBookOasisOrigin(it, baseUrl) }
}
