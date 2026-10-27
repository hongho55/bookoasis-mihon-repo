package eu.kanade.tachiyomi.source.bookoasis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

internal fun shouldOpenBookOasisBrowse(firstBrowseAlreadyOpened: Boolean): Boolean = !firstBrowseAlreadyOpened

internal fun shouldShowBookOasisOnboarding(address: String?, dismissed: Boolean): Boolean =
    address.isNullOrBlank() && !dismissed

internal sealed interface BookOasisConnectionResult {
    data object Connected : BookOasisConnectionResult
    data object InvalidUrl : BookOasisConnectionResult
    data object Unreachable : BookOasisConnectionResult
    data object AuthenticationRejected : BookOasisConnectionResult
    data object UnexpectedResponse : BookOasisConnectionResult
}

/** Read-only validation against the same authenticated catalog endpoint used by the source. */
internal object BookOasisConnection {
    suspend fun validate(
        client: okhttp3.OkHttpClient,
        address: String,
        username: String,
        password: String,
    ): BookOasisConnectionResult = withContext(Dispatchers.IO) {
        val root = try {
            BookOasisSource.normalizeServerRoot(address).takeIf(String::isNotBlank)?.let {
                it.toHttpUrl()
            } ?: return@withContext BookOasisConnectionResult.InvalidUrl
        } catch (_: IllegalArgumentException) {
            return@withContext BookOasisConnectionResult.InvalidUrl
        }
        if (root.username.isNotEmpty() || root.password.isNotEmpty() || root.host.isBlank()) {
            return@withContext BookOasisConnectionResult.InvalidUrl
        }
        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        val request = Request.Builder()
            .url(
                root.newBuilder().addPathSegments("app-opds/api/media/list").addQueryParameter("type", "general")
                    .addQueryParameter("library_id", "all").addQueryParameter("page", "1")
                    .addQueryParameter("limit", "30").addQueryParameter("sort", "asc").build(),
            )
            .header("Accept", "application/json")
            .header("Authorization", "Basic $credentials")
            .get()
            .build()
        try {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(request).execute().use(::classify)
        } catch (_: UnknownHostException) {
            BookOasisConnectionResult.Unreachable
        } catch (_: SocketTimeoutException) {
            BookOasisConnectionResult.Unreachable
        } catch (_: IOException) {
            BookOasisConnectionResult.Unreachable
        }
    }

    private fun classify(response: Response): BookOasisConnectionResult = when {
        response.code == 401 || response.code == 403 -> BookOasisConnectionResult.AuthenticationRejected
        response.code !in 200..299 -> BookOasisConnectionResult.UnexpectedResponse
        else -> try {
            val body = response.body?.string() ?: return BookOasisConnectionResult.UnexpectedResponse
            val json = JSONObject(body)
            if (json.optBoolean("success") && json.optJSONArray("series") != null) {
                BookOasisConnectionResult.Connected
            } else {
                BookOasisConnectionResult.UnexpectedResponse
            }
        } catch (_: Exception) {
            BookOasisConnectionResult.UnexpectedResponse
        }
    }
}
