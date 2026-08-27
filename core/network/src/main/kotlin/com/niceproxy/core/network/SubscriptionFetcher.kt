package com.niceproxy.core.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionResponse(
    val body: String,
    /** 响应头 `subscription-userinfo` 的原文，由调用方解析。 */
    val userInfoHeader: String?,
    /** 机场可通过 `profile-title` 或 `content-disposition` 提供分组名。 */
    val suggestedName: String?,
)

@Singleton
class SubscriptionFetcher @Inject constructor(
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(
        url: String,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result<SubscriptionResponse> =
        withContext(ioDispatcher) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    // 不少机场按 UA 返回不同格式：给 clash 返回 YAML，给其他返回 Base64。
                    // 默认伪装成通用客户端以拿到最兼容的格式。
                    .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
                    .apply { extraHeaders.forEach { (key, value) -> header(key, value) } }
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("订阅返回 ${response.code}")
                    }
                    SubscriptionResponse(
                        body = response.body.string(),
                        userInfoHeader = response.header("subscription-userinfo"),
                        suggestedName = response.header("profile-title")
                            ?: parseFilename(response.header("content-disposition")),
                    )
                }
            }
        }

    private fun parseFilename(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""").find(contentDisposition)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "NiceProxy/0.1 (sing-box)"
    }
}
