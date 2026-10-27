package eu.kanade.tachiyomi.source.bookoasis.progress

import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.bookoasis.BookOasisDashboardSession
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import eu.kanade.tachiyomi.source.bookoasis.sameBookOasisOrigin
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.round

/** Snapshot derived only from stock progress-state and TTS position responses. */
internal data class BookOasisServerProgress(
    val revision: String,
    /** Local connection/account fingerprint, not a server-issued account scope. */
    val accountScope: String,
    val pagesRead: Int,
    val totalPages: Int? = null,
    val documentIndex: Int?,
    val startOffset: Long?,
    val percent: Int?,
    val fingerprint: String? = null,
    val fingerprintIsRaw: Boolean = true,
    val positionKind: String? = null,
    val updatedAt: Long = 0L,
)

internal sealed interface BookOasisProgressPostResult {
    data class Success(val revision: String, val accountScope: String) : BookOasisProgressPostResult
    data class Conflict(val revision: String, val accountScope: String) : BookOasisProgressPostResult
}

internal class BookOasisProgressException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal object BookOasisProgressApi {
    suspend fun get(source: HttpSource, item: BookOasisPendingProgress): BookOasisServerProgress =
        directClient(source, item.accountBinding).get(item)

    suspend fun post(
        source: HttpSource,
        item: BookOasisPendingProgress,
        baseRevision: String,
        accountScope: String,
    ): BookOasisProgressPostResult = directClient(source, item.accountBinding).post(
        item = item,
        baseRevision = baseRevision,
        accountScope = accountScope,
    )

    internal fun localAccountBinding(source: HttpSource): String? {
        val builtIn = source as? BookOasisSource ?: return null
        val username = builtIn.getSourcePreferences().getString("username", "").orEmpty()
        return accountBinding(builtIn.baseUrl, username)
    }

    internal fun localAccountBinding(address: String, username: String): String? = accountBinding(address, username)

    private fun accountBinding(address: String, username: String): String? {
        val root = address.toHttpUrlOrNull()?.let { url ->
            url.newBuilder().username("").password("").build().toString().trimEnd('/')
        } ?: return null
        if (username.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$root\n$username".toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    internal fun endpoint(baseUrl: String, path: String): String {
        val root = normalizeRoot(baseUrl)
        return "$root/${path.trimStart('/')}"
    }

    internal fun normalizeRoot(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.endsWith("/app-opds-adult", ignoreCase = true) ->
                normalized.dropLast("/app-opds-adult".length)
            normalized.endsWith("/app-opds", ignoreCase = true) ->
                normalized.dropLast("/app-opds".length)
            else -> normalized
        }
    }

    internal fun isolatedDirectClient(
        baseClient: okhttp3.OkHttpClient,
        address: String,
        username: String,
        password: String,
        expectedBinding: String,
        currentBinding: () -> String?,
    ) = BookOasisDirectProgressClient(
        baseClient,
        address,
        username,
        password,
        expectedBinding,
        currentBinding,
    )

    private fun directClient(source: HttpSource, expectedBinding: String?): BookOasisDirectProgressClient {
        val builtIn = source as? BookOasisSource
            ?: throw BookOasisProgressException(
                "UNSUPPORTED",
                "Stock progress sync requires the built-in BookOasis connection",
            )
        val binding = expectedBinding?.takeIf(String::isNotBlank)
            ?: throw BookOasisProgressException("ACCOUNT_CHANGED", "BookOasis account binding is missing")
        val address = builtIn.baseUrl
        val prefs = builtIn.getSourcePreferences()
        val username = prefs.getString("username", "").orEmpty()
        val password = prefs.getString("password", "").orEmpty()
        if (accountBinding(address, username) != binding || localAccountBinding(source) != binding) {
            throw BookOasisProgressException("ACCOUNT_CHANGED", "BookOasis account changed before stock API request")
        }
        if (!sameBookOasisOrigin(endpoint(address, "api/media/progress-state"), address)) {
            throw BookOasisProgressException("INVALID", "Stock progress endpoint is not same-origin")
        }
        return isolatedDirectClient(
            baseClient = builtIn.client,
            address = address,
            username = username,
            password = password,
            expectedBinding = binding,
            currentBinding = { localAccountBinding(source) },
        )
    }

    internal fun responseException(status: Int): BookOasisProgressException {
        val code = when (status) {
            400 -> "INVALID"
            401, 403 -> "FORBIDDEN"
            404 -> "STOCK_API_UNAVAILABLE"
            in 300..399 -> "UNSUPPORTED"
            else -> "HTTP_$status"
        }
        return BookOasisProgressException(code, "BookOasis stock API request failed (HTTP $status)")
    }

    internal fun parseSnapshot(
        progressPayload: JSONObject,
        ttsPayload: JSONObject?,
        format: String,
        accountScope: String,
        receivedAt: Long = System.currentTimeMillis(),
    ): BookOasisServerProgress {
        requireStockSuccess(progressPayload, "progress-state")
        val state = progressPayload.optJSONObject("state")
            ?: throw BookOasisProgressException("INVALID", "Stock progress-state response omitted state")
        val ttsState = if (ttsPayload == null) {
            null
        } else {
            requireStockSuccess(ttsPayload, "TTS position")
            ttsPayload.optJSONObject("state")
                ?: throw BookOasisProgressException("INVALID", "Stock TTS response omitted state")
        }
        val session = state.optJSONObject("epub_session") ?: JSONObject()
        val selectedKind = ttsState?.optString("latest")?.takeIf { it in setOf("read", "listen") }
        val selected = selectedKind?.let { ttsState?.optJSONObject(it) }
        val legacyAnchor = session.optStringOrNull("fingerprint")
        val legacyTimestamp = parseStockTimestamp(session.optStringOrNull("updatedAt"))
        val fineTimestamp = selected?.optLongOrNull("updated_ms") ?: 0L
        val legacyIsNewer = selected != null && !legacyAnchor.isNullOrBlank() && legacyTimestamp > fineTimestamp
        val useFine = selected != null && !legacyIsNewer
        val fineAnchor = selected?.optStringOrNull("anchor")?.takeIf(String::isNotBlank)
        val rawPointerAnchor = if (useFine && !fineAnchor.isNullOrBlank()) fineAnchor else legacyAnchor
        val fingerprint = if (useFine && !fineAnchor.isNullOrBlank()) {
            fineAnchor
        } else {
            rawPointerAnchor?.let { Parser.unescapeEntities(it, false) }
        }
        val documentIndex = if (useFine) {
            selected?.optIntOrNull("chapter_idx")
        } else {
            session.optIntOrNull("index")
                ?: if (format.equals("txt", true) &&
                    (state.optInt("pages_read") > 0 || legacyAnchor != null)
                ) {
                    0
                } else {
                    null
                }
        }
        val startOffset = if (useFine) {
            if (format.equals("txt", true)) selected?.optLongOrNull("char_offset") else null
        } else {
            session.optStringOrNull("cfi")
                ?.takeIf { it.startsWith(TEXT_OFFSET_PREFIX) }
                ?.removePrefix(TEXT_OFFSET_PREFIX)?.toLongOrNull()
        }
        val kind = when {
            useFine && fineAnchor != null -> selectedKind
            !legacyAnchor.isNullOrBlank() || state.optInt(
                "pages_read",
                0,
            ) > 0 || session.optIntOrNull("index") != null -> "legacy"
            else -> null
        }
        val ttsCanonical = ttsState ?: JSONObject()
        val revision = stockRevision(accountScope, state, ttsCanonical)
        val recordedAt = if (useFine) {
            fineTimestamp
        } else {
            maxOf(
                legacyTimestamp,
                parseStockTimestamp(state.optStringOrNull("last_read_at")),
            )
        }
        val serverNow = ttsPayload?.optLongOrNull("server_now_ms") ?: 0L
        val updatedAt = if (recordedAt > 0L && serverNow > 0L) {
            (recordedAt + receivedAt - serverNow).coerceAtLeast(0L)
        } else {
            recordedAt
        }
        return BookOasisServerProgress(
            revision = revision,
            accountScope = accountScope,
            pagesRead = state.optInt("pages_read", 0),
            totalPages = state.optIntOrNull("total_pages"),
            documentIndex = documentIndex,
            startOffset = startOffset,
            percent = session.optIntOrNull("percent"),
            fingerprint = fingerprint,
            fingerprintIsRaw = true,
            positionKind = kind,
            updatedAt = updatedAt,
        )
    }

    internal fun stockRevision(accountScope: String, progress: JSONObject, ttsState: JSONObject): String {
        val canonical = JSONArray()
            .put(accountScope)
            .put(canonicalJson(progress))
            .put(canonicalJson(ttsState))
            .toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "stock1:" + digest.joinToString("") { "%02x".format(it) }
    }

    internal fun validateAcknowledgedPosition(
        item: BookOasisPendingProgress,
        format: String,
        state: JSONObject,
        ttsState: JSONObject?,
        expectedWebOffset: Long? = null,
        expectedWebTextLength: Int? = null,
        expectedChunk: Pair<Int, Int>? = null,
    ) {
        if (format == "epub") {
            val session = state.optJSONObject("epub_session")
                ?: throw notPersisted("EPUB read-back omitted epub_session")
            val expectedPercent = epubPercent(item)
            val savedPercent = if (item.documentIndex == 0) 0 else expectedPercent
            if (session.optIntOrNull("index") != item.documentIndex ||
                session.optIntOrNull("percent") != savedPercent
            ) {
                throw notPersisted("Stock API acknowledged a different EPUB spine or percentage")
            }
            if (state.optInt("pages_read", 0) != expectedPercent.coerceAtLeast(1) ||
                state.optInt("total_pages", 0) != 100
            ) {
                throw notPersisted("Stock API acknowledged different EPUB coarse progress")
            }
            if (session.optStringOrNull("fingerprint") != item.fingerprint?.let(::escapeWebHtml)) {
                throw notPersisted("Stock API acknowledged a different EPUB legacy anchor")
            }
            validateFineRead(ttsState, item, "epub", expectedWebOffset, expectedWebTextLength)
        } else if (format == "txt") {
            val session = state.optJSONObject("epub_session")
                ?: throw notPersisted("TXT read-back omitted epub_session")
            if (session.optIntOrNull("index") != 0 ||
                session.optStringOrNull("cfi") != "$TEXT_OFFSET_PREFIX${requireNotNull(expectedWebOffset)}" ||
                session.optStringOrNull("fingerprint") != item.fingerprint?.let(::escapeWebHtml)
            ) {
                throw notPersisted("Stock API acknowledged a different TXT legacy pointer")
            }
            if (expectedChunk == null || state.optInt("pages_read", 0) != expectedChunk.first + 1 ||
                state.optInt("total_pages", 0) != expectedChunk.second
            ) {
                throw notPersisted("Stock API acknowledged a different TXT web chunk")
            }
            validateFineRead(ttsState, item, "txt", expectedWebOffset, expectedWebTextLength)
        } else {
            if (state.optInt("pages_read", 0) != item.pageIndex + 1 ||
                state.optInt("total_pages", 0) != item.totalPages
            ) {
                throw notPersisted("Stock API acknowledged a different page")
            }
        }
    }

    private fun validateFineRead(
        ttsState: JSONObject?,
        item: BookOasisPendingProgress,
        format: String,
        expectedOffset: Long?,
        expectedTextLength: Int?,
    ) {
        val state = ttsState ?: throw notPersisted("Stock TTS read-back omitted state")
        val read = state.optJSONObject("read") ?: throw notPersisted("Stock TTS read-back omitted read position")
        if (state.optString("latest") != "read" || read.optStringOrNull("anchor") != item.fingerprint) {
            throw notPersisted("Stock TTS read-back did not preserve the requested read anchor")
        }
        val chapter = if (format == "txt") 0 else item.documentIndex
        if (read.optIntOrNull("chapter_idx") != chapter) {
            throw notPersisted("Stock TTS read-back acknowledged a different text chapter")
        }
        if (expectedOffset != null && read.optLongOrNull("char_offset") != expectedOffset) {
            throw notPersisted("Stock TTS read-back acknowledged a different text offset")
        }
        if (expectedTextLength != null && read.optIntOrNull("text_len") != expectedTextLength) {
            throw notPersisted("Stock TTS read-back acknowledged a different text length")
        }
    }

    internal fun epubPercent(item: BookOasisPendingProgress): Int =
        round(((item.pageIndex + 1).toDouble() / item.totalPages) * 100).toInt().coerceIn(0, 100)

    internal fun webTxtChunks(textInput: String): List<Int> {
        val text = textInput.removePrefix("\uFEFF")
        val starts = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            starts += start
            if (start + WEB_TXT_CHUNK_SIZE >= text.length) break
            var end = start + WEB_TXT_CHUNK_SIZE
            val newline = text.indexOf('\n', end)
            if (newline >= 0 && newline - end < WEB_TXT_NEWLINE_LOOKAHEAD) end = newline + 1
            start = end
        }
        return starts
    }

    internal fun mapTxtAnchor(textInput: String, startOffset: Long, anchor: String): Long {
        val text = textInput.removePrefix("\uFEFF")
        if (anchor.codePointCount(0, anchor.length) < MIN_ANCHOR_CODEPOINTS) {
            throw BookOasisProgressException("INVALID", "TXT progress requires a meaningful sentence anchor")
        }
        val offsets = anchorOffsets(text, anchor)
        if (offsets.isEmpty()) {
            throw BookOasisProgressException(
                "NOT_PERSISTED",
                "TXT anchor was not found in stock text",
            )
        }
        if (offsets.size == 1) return offsets.single().toLong()
        val nearest = offsets.minBy { kotlin.math.abs(it.toLong() - startOffset) }
        val distance = kotlin.math.abs(nearest.toLong() - startOffset)
        val nearestCount = offsets.count { kotlin.math.abs(it.toLong() - startOffset) == distance }
        if (nearestCount != 1 || distance > MAX_TXT_OFFSET_DRIFT) {
            throw BookOasisProgressException(
                "INVALID",
                "TXT anchor is repeated or its native offset cannot disambiguate it",
            )
        }
        return nearest.toLong()
    }

    internal fun epubWebAnchorIsUnique(html: String, anchor: String): Boolean {
        if (anchor.codePointCount(0, anchor.length) < MIN_ANCHOR_CODEPOINTS) return false
        val text = Jsoup.parseBodyFragment(html).text()
        return anchorOffsets(text, anchor).size == 1
    }

    internal fun escapeWebHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")

    internal fun anchorOffsets(text: String, anchor: String): List<Int> {
        val points = anchor.codePoints().toArray().filterNot { Character.isWhitespace(it) }
        if (points.size < MIN_ANCHOR_CODEPOINTS) return emptyList()
        val expression = points.joinToString("\\s*") { cp -> Regex.escape(String(Character.toChars(cp))) }
        val regex = Regex(expression, setOf(RegexOption.IGNORE_CASE))
        return regex.findAll(text).map { it.range.first }.toList()
    }

    private fun parseStockTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (pattern in patterns) {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern)
                return if (pattern.contains("XXX")) {
                    java.time.OffsetDateTime.parse(value, formatter).toInstant().toEpochMilli()
                } else {
                    java.time.LocalDateTime.parse(value, formatter)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            } catch (_: Exception) {
                // Try the next stock timestamp representation.
            }
        }
        return 0L
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(value.opt(it))
        }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun requireStockSuccess(payload: JSONObject, operation: String) {
        if (!payload.optBoolean("success", false)) {
            throw BookOasisProgressException("INVALID", "BookOasis $operation failed")
        }
    }

    private fun notPersisted(message: String) = BookOasisProgressException("NOT_PERSISTED", message)

    private fun localScope(binding: String) = "local1:${binding.removePrefix("sha256:")}"

    private fun formatFor(item: BookOasisPendingProgress): String = when (item.format.lowercase()) {
        "comic" -> "comic"
        "epub", "txt", "pdf", "cbz", "zip" -> item.format.lowercase()
        else -> throw BookOasisProgressException("UNSUPPORTED", "Stock progress sync does not support this format")
    }

    private const val WEB_TXT_CHUNK_SIZE = 4000
    private const val WEB_TXT_NEWLINE_LOOKAHEAD = 500
    private const val MIN_ANCHOR_CODEPOINTS = 4
    private const val MAX_TXT_OFFSET_DRIFT = 12_000L
    private const val TEXT_OFFSET_PREFIX = "mihon-text-offset:"
}

/** Stock, plugin-free adapter. The client has one captured-account cookie jar and no CAS promise. */
internal class BookOasisDirectProgressClient internal constructor(
    baseClient: okhttp3.OkHttpClient,
    private val address: String,
    private val username: String,
    private val password: String,
    private val expectedBinding: String,
    private val currentBinding: () -> String?,
) {
    private val localScope = "local1:${expectedBinding.removePrefix("sha256:")}"
    private val session = BookOasisDirectWebSession(
        baseClient,
        address,
        username,
        password,
        expectedBinding,
        currentBinding,
    )

    suspend fun get(item: BookOasisPendingProgress): BookOasisServerProgress {
        checkItem(item)
        return get(item, resolvedFormat(item))
    }

    private suspend fun get(item: BookOasisPendingProgress, format: String): BookOasisServerProgress {
        checkItem(item)
        val progress = json("GET", progressStateUrl(item))
        val tts = if (format == "epub" || format == "txt") json("GET", ttsPositionUrl(item)) else null
        var parsed = BookOasisProgressApi.parseSnapshot(progress, tts, format, localScope)
        if (format == "epub" && !parsed.fingerprint.isNullOrBlank()) {
            val chapter = parsed.documentIndex
                ?: throw BookOasisProgressException("INVALID", "Server EPUB anchor omitted its chapter")
            val html = epubChapterBody(item, chapter)
            val raw = parsed.fingerprint!!
            val visibleText = Jsoup.parseBodyFragment(html).text()
            val verifiedAnchor = listOf(raw, Parser.unescapeEntities(raw, false)).distinct().firstOrNull {
                BookOasisProgressApi.anchorOffsets(visibleText, it).size == 1
            } ?: throw BookOasisProgressException(
                "INVALID",
                "Server EPUB anchor is missing or ambiguous; retain the local position",
            )
            // Never accept an unusable server snapshot as the base for an automatic local write.
            // Stock web anchors can start inside a serialized entity (for example "p;B").
            parsed = parsed.copy(fingerprint = verifiedAnchor, fingerprintIsRaw = true)
        }
        if (format == "txt" && parsed.positionKind == "legacy" && parsed.startOffset == null && parsed.pagesRead > 0) {
            val text = txtBody(item)
            val starts = BookOasisProgressApi.webTxtChunks(text)
            val start = starts.getOrNull((parsed.pagesRead - 1).coerceIn(0, starts.lastIndex))
            return parsed.copy(startOffset = start?.toLong(), documentIndex = 0)
        }
        return parsed
    }

    suspend fun post(
        item: BookOasisPendingProgress,
        baseRevision: String,
        accountScope: String,
    ): BookOasisProgressPostResult {
        checkItem(item)
        if (baseRevision.isBlank()) throw BookOasisProgressException("INVALID", "Missing stock snapshot revision")
        if (accountScope !=
            localScope
        ) {
            throw BookOasisProgressException("ACCOUNT_CHANGED", "Local BookOasis account binding changed")
        }
        val format = resolvedFormat(item)
        val current = get(item, format)
        if (current.accountScope !=
            accountScope
        ) {
            throw BookOasisProgressException("ACCOUNT_CHANGED", "Local BookOasis account binding changed")
        }
        // This is only a second client-side preflight. Stock endpoints have no conditional write/CAS.
        // A changed snapshot may be our own completed write whose acknowledgement was lost.
        val mayMatch = when (format) {
            "epub" ->
                current.positionKind == "read" && current.fingerprint == item.fingerprint &&
                    current.documentIndex == item.documentIndex && current.totalPages == 100 &&
                    current.pagesRead == BookOasisProgressApi.epubPercent(item).coerceAtLeast(1)
            "txt" ->
                current.positionKind == "read" && current.fingerprint == item.fingerprint &&
                    current.documentIndex == 0
            else -> current.pagesRead == item.pageIndex + 1 && current.totalPages == item.totalPages
        }
        val needsExistingAck = current.revision != baseRevision && mayMatch
        if (!needsExistingAck && current.updatedAt >= item.updatedAt) {
            return BookOasisProgressPostResult.Conflict(current.revision, localScope)
        }
        val anchor = item.fingerprint
        var txtTextLength: Int? = null
        var txtOffset: Long? = null
        var txtChunk: Pair<Int, Int>? = null
        var epubHtml: String? = null

        when (format) {
            "txt" -> {
                if (item.documentIndex != 0 || item.startOffset == null || anchor.isNullOrBlank()) {
                    throw BookOasisProgressException(
                        "INVALID",
                        "TXT progress requires document zero, an offset, and a sentence anchor",
                    )
                }
                val text = txtBody(item)
                val starts = BookOasisProgressApi.webTxtChunks(text)
                if (starts.isEmpty()) throw BookOasisProgressException("INVALID", "TXT body is empty")
                txtTextLength = text.removePrefix("\uFEFF").length
                txtOffset = BookOasisProgressApi.mapTxtAnchor(text, item.startOffset, anchor)
                val chunk = starts.indexOfLast { it.toLong() <= txtOffset!! }.coerceAtLeast(0)
                txtChunk = chunk to starts.size
            }
            "epub" -> {
                if (item.documentIndex == null || item.documentIndex < 0 || anchor.isNullOrBlank()) {
                    throw BookOasisProgressException(
                        "INVALID",
                        "EPUB progress requires a spine index and sentence anchor",
                    )
                }
                epubHtml = epubChapterBody(item, item.documentIndex)
                if (!BookOasisProgressApi.epubWebAnchorIsUnique(epubHtml, anchor)) {
                    throw BookOasisProgressException(
                        "NOT_PERSISTED",
                        "EPUB anchor is missing or repeated in the stock web chapter",
                    )
                }
            }
        }

        val coarsePercent = if (format == "epub") {
            BookOasisProgressApi.epubPercent(item)
        } else {
            null
        }
        val progressPage = when (format) {
            "epub" -> (coarsePercent!! - 1).coerceAtLeast(0)
            "txt" -> txtChunk!!.first
            else -> item.pageIndex
        }
        val progressTotal = when (format) {
            "epub" -> 100
            "txt" -> txtChunk!!.second
            else -> item.totalPages
        }
        val epubSession = when (format) {
            "epub" -> JSONObject()
                .put("index", item.documentIndex)
                .put("percent", if (item.documentIndex == 0) 0 else coarsePercent)
                .put("fingerprint", BookOasisProgressApi.escapeWebHtml(anchor!!))
            "txt" -> JSONObject()
                .put("index", 0)
                .put("percent", JSONObject.NULL)
                .put("cfi", "$TEXT_OFFSET_PREFIX$txtOffset")
                .put("fingerprint", BookOasisProgressApi.escapeWebHtml(anchor!!))
            else -> null
        }
        val progressBody = JSONObject()
            .put("db_type", item.dbType)
            .put("book_id", item.bookId)
            .put("page_idx", progressPage)
            .put("total_pages", progressTotal)
            .put("flush_immediately", true)
            .also { if (epubSession != null) it.put("epub_session", epubSession) }
        if (needsExistingAck) {
            val storedProgress = json("GET", progressStateUrl(item))
            val storedTts = if (format == "epub" || format == "txt") json("GET", ttsPositionUrl(item)) else null
            val stored = BookOasisProgressApi.parseSnapshot(storedProgress, storedTts, format, localScope)
            try {
                BookOasisProgressApi.validateAcknowledgedPosition(
                    item,
                    format,
                    storedProgress.getJSONObject("state"),
                    storedTts?.getJSONObject("state"),
                    txtOffset,
                    txtTextLength,
                    txtChunk,
                )
            } catch (error: BookOasisProgressException) {
                if (error.code != "NOT_PERSISTED") throw error
                return BookOasisProgressPostResult.Conflict(stored.revision, localScope)
            }
            return BookOasisProgressPostResult.Success(stored.revision, localScope)
        }
        val progressAck = json("POST", BookOasisProgressApi.endpoint(address, "api/media/progress"), progressBody)
        requireStockSuccess(progressAck, "progress write")

        if (format == "epub" || format == "txt") {
            val readBody = JSONObject()
                .put("db_type", item.dbType)
                .put("book_id", item.bookId)
                .put("kind", "read")
                .put("chapter_idx", if (format == "txt") 0 else item.documentIndex)
                .put("char_offset", if (format == "txt") txtOffset else 0)
                .put("text_len", if (format == "txt") txtTextLength else 0)
                .put("anchor", anchor)
            val readAck = json("POST", BookOasisProgressApi.endpoint(address, "api/media/tts/position"), readBody)
            requireStockSuccess(readAck, "TTS read position write")
        }

        val afterProgress = json("GET", progressStateUrl(item))
        val afterTts = if (format == "epub" || format == "txt") json("GET", ttsPositionUrl(item)) else null
        requireStockSuccess(afterProgress, "progress read-back")
        val afterState = afterProgress.optJSONObject("state")
            ?: throw BookOasisProgressException("NOT_PERSISTED", "Stock progress read-back omitted state")
        val afterTtsState = afterTts?.let {
            requireStockSuccess(it, "TTS read-back")
            it.optJSONObject("state")
                ?: throw BookOasisProgressException("NOT_PERSISTED", "Stock TTS read-back omitted state")
        }
        BookOasisProgressApi.validateAcknowledgedPosition(
            item = item,
            format = format,
            state = afterState,
            ttsState = afterTtsState,
            expectedWebOffset = txtOffset,
            expectedWebTextLength = txtTextLength,
            expectedChunk = txtChunk,
        )
        val verified = BookOasisProgressApi.parseSnapshot(afterProgress, afterTts, format, localScope)
        return BookOasisProgressPostResult.Success(verified.revision, localScope)
    }

    /** Exposed internally so the HTTP JVM acceptance test can build a native-position fixture from real stock text. */
    internal suspend fun readOnlyText(item: BookOasisPendingProgress, chapterIndex: Int = 0): String =
        if (resolvedFormat(item) == "txt") txtBody(item) else epubPlainBody(item, chapterIndex)

    private suspend fun resolvedFormat(item: BookOasisPendingProgress): String {
        val requested = when (item.format.lowercase()) {
            "comic" -> "comic"
            "epub", "txt", "pdf", "cbz", "zip" -> item.format.lowercase()
            else -> throw BookOasisProgressException("UNSUPPORTED", "Stock progress sync does not support this format")
        }
        // progress-state/progress have weaker per-book checks than Reader Bridge. Always verify
        // the authenticated account can see this exact book through the existing reader-info API.
        val url = BookOasisProgressApi.endpoint(address, "api/media/books/${item.bookId}/reader-info")
            .toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("type", item.dbType)?.build()?.toString()
            ?: throw BookOasisProgressException("INVALID", "Invalid book metadata URL")
        val payload = json("GET", url)
        val book = payload.optJSONObject("book")
        val actual = book?.optString("file_format")?.lowercase()
        if (!payload.optBoolean("success") || book?.optLong("id") != item.bookId) {
            throw BookOasisProgressException("FORBIDDEN", "BookOasis account cannot access this book")
        }
        if (requested == "comic") {
            if (actual !in setOf("cbz", "zip")) {
                throw BookOasisProgressException("UNSUPPORTED", "Comic archive type could not be verified")
            }
            return actual!!
        }
        if (actual != requested) {
            throw BookOasisProgressException("UNSUPPORTED", "Book format does not match the stock reader-info response")
        }
        return requested
    }

    private suspend fun txtBody(item: BookOasisPendingProgress): String {
        val url = BookOasisProgressApi.endpoint(address, "api/media/txt").toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("db_type", item.dbType)
            .addQueryParameter("book_id", item.bookId.toString())
            .build().toString()
        return session.text("GET", url)
    }

    private suspend fun epubChapterBody(item: BookOasisPendingProgress, chapterIndex: Int): String {
        if (chapterIndex < 0) throw BookOasisProgressException("INVALID", "Invalid EPUB spine index")
        val url = BookOasisProgressApi.endpoint(address, "api/media/epub/chapter").toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("db_type", item.dbType)
            .addQueryParameter("book_id", item.bookId.toString())
            .addQueryParameter("chapter_idx", chapterIndex.toString())
            .build().toString()
        val payload = json("GET", url)
        val html = payload.optStringOrNull("content")
            ?: payload.optJSONObject("chapter")?.optStringOrNull("content")
            ?: throw BookOasisProgressException("INVALID", "Stock EPUB chapter response omitted content")
        return html
    }

    private suspend fun epubPlainBody(item: BookOasisPendingProgress, chapterIndex: Int): String =
        Jsoup.parseBodyFragment(epubChapterBody(item, chapterIndex)).text()

    private suspend fun json(method: String, url: String, body: JSONObject? = null): JSONObject =
        session.json(method, url, body)

    private suspend fun requireStockSuccess(payload: JSONObject, operation: String) {
        if (!payload.optBoolean(
                "success",
                false,
            )
        ) {
            throw BookOasisProgressException("INVALID", "BookOasis $operation failed")
        }
    }

    private fun progressStateUrl(item: BookOasisPendingProgress): String =
        BookOasisProgressApi.endpoint(address, "api/media/progress-state").toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("db_type", item.dbType)
            .addQueryParameter("book_id", item.bookId.toString())
            .build().toString()

    private fun ttsPositionUrl(item: BookOasisPendingProgress): String =
        BookOasisProgressApi.endpoint(address, "api/media/tts/position").toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("db_type", item.dbType)
            .addQueryParameter("book_id", item.bookId.toString())
            .build().toString()

    private fun checkItem(item: BookOasisPendingProgress) {
        if (item.accountBinding != expectedBinding || currentBinding() != expectedBinding) {
            throw BookOasisProgressException("ACCOUNT_CHANGED", "BookOasis account changed before stock API operation")
        }
        if (item.bookId <= 0 || item.dbType !in setOf("general", "adult") || item.totalPages <= 0 ||
            item.pageIndex !in 0 until item.totalPages
        ) {
            throw BookOasisProgressException("INVALID", "Invalid BookOasis progress reference")
        }
    }

    private companion object {
        const val TEXT_OFFSET_PREFIX = "mihon-text-offset:"
    }
}

/** Account-pinned stock web API session. It never uses Android's shared WebView cookie jar. */
internal class BookOasisDirectWebSession(
    baseClient: okhttp3.OkHttpClient,
    private val address: String,
    private val username: String,
    private val password: String,
    private val expectedBinding: String,
    private val currentBinding: () -> String?,
) {
    private val client = baseClient.newBuilder()
        .cookieJar(IsolatedBookOasisCookieJar())
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun json(method: String, url: String, body: JSONObject? = null): JSONObject {
        val response = request(method, url, body?.toString()?.toRequestBody(JSON_MEDIA_TYPE))
        return runCatching { JSONObject(response.body) }.getOrElse {
            throw BookOasisProgressException("INVALID", "BookOasis returned invalid JSON", it)
        }
    }

    suspend fun text(method: String, url: String): String = request(method, url, null).body

    private suspend fun request(method: String, url: String, body: RequestBody?): HttpResponse {
        if (!sameBookOasisOrigin(url, address)) {
            throw BookOasisProgressException("INVALID", "Stock API request is not same-origin")
        }
        suspend fun send(): HttpResponse {
            checkBinding()
            val request = Request.Builder()
                .url(url)
                .header(
                    "Accept",
                    if (url.contains("/api/media/txt")) "text/plain, application/json" else "application/json",
                )
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .method(method, body)
                .build()
            val result = client.newCall(request).await().use { HttpResponse(it.code, it.body.string()) }
            checkBinding()
            if (result.status in
                300..399
            ) {
                throw BookOasisProgressException("UNSUPPORTED", "Stock API redirects are not followed")
            }
            if (result.status !in 200..299) throw BookOasisProgressApi.responseException(result.status)
            return result
        }

        try {
            return send()
        } catch (error: BookOasisProgressException) {
            if (error.code != "FORBIDDEN") throw error
            checkBinding()
            try {
                withContext(Dispatchers.IO) {
                    checkBinding()
                    BookOasisDashboardSession.login(client, address, username, password)
                    checkBinding()
                }
            } catch (loginError: BookOasisProgressException) {
                throw loginError
            } catch (loginError: Exception) {
                throw BookOasisProgressException(
                    "FORBIDDEN",
                    "BookOasis web session could not be refreshed",
                    loginError,
                )
            }
            return send()
        }
    }

    private fun checkBinding() {
        if (currentBinding() != expectedBinding) {
            throw BookOasisProgressException("ACCOUNT_CHANGED", "BookOasis account changed during stock API request")
        }
    }

    private data class HttpResponse(val status: Int, val body: String)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Cookie jar scoped to this short-lived account-pinned sequence, not Android's shared WebView session. */
private class IsolatedBookOasisCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt <= now }
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name) || !has(name)) null else getInt(name)

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name) || !has(name)) null else getLong(name)

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else getString(name)
