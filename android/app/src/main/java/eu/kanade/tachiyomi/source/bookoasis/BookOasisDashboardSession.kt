package eu.kanade.tachiyomi.source.bookoasis

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Exchange the saved account for the server's normal HttpOnly web session, never a JS credential. */
internal object BookOasisDashboardSession {
    suspend fun login(source: BookOasisSource): List<String> = withContext(Dispatchers.IO) {
        val prefs = source.getSourcePreferences()
        val cookies = login(
            source.client,
            source.baseUrl,
            prefs.getString("username", "").orEmpty(),
            prefs.getString("password", "").orEmpty(),
        )
        // AndroidCookieJar writes asynchronously: await native-session persistence before
        // the next API call, including first launch where no WebView has been created.
        withContext(Dispatchers.Main) {
            val manager = CookieManager.getInstance()
            for (cookie in cookies) {
                suspendCancellableCoroutine { continuation ->
                    manager.setCookie(source.baseUrl, cookie) { continuation.resume(Unit) }
                }
            }
            manager.flush()
        }
        cookies
    }

    internal fun login(client: OkHttpClient, address: String, username: String, password: String): List<String> {
        require(bookOasisDashboardOrigin(address) != null) { "서버 주소를 확인해 주세요." }
        val payload = JSONObject().put("username", username).put("password", password)
            .put("remember_me", true).toString()
        val request = Request.Builder().url("${address.trimEnd('/')}/login")
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return client.newBuilder().apply { networkInterceptors().clear() }
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw IOException("연결 설정에서 계정을 다시 확인해 주세요.")
                if (!response.isSuccessful) throw IOException("대시보드에 연결할 수 없습니다. 오프라인 서재를 이용해 주세요.")
                val success = runCatching { JSONObject(response.body.string()).optBoolean("success") }.getOrDefault(false)
                if (!success) throw IOException("서버 로그인 응답을 확인할 수 없습니다.")
                response.headers.values("Set-Cookie").takeIf { it.isNotEmpty() }
                    ?: throw IOException("서버가 대시보드 로그인 세션을 보내지 않았습니다.")
            }
    }
}
