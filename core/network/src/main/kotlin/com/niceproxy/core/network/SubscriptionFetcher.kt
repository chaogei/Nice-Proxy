package com.niceproxy.core.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
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
        // readTimeout 只管**单次读**：服务端每 29 秒吐一个字节就能让这个请求
        // 永远挂着，而订阅自动更新是后台周期任务，挂住的调用会一直占着线程。
        // callTimeout 是唯一覆盖「从发起到读完」的封顶。
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        // https 的订阅不接受被 302 到 http：URL 里那个 token 会明文重发一遍，
        // 而它一把梭出整个机场账号。没有任何正常机场依赖这种跳转。
        .followSslRedirects(false)
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
                        body = response.body.readCapped(),
                        userInfoHeader = response.header("subscription-userinfo"),
                        suggestedName = response.header("profile-title")
                            ?: parseFilename(response.header("content-disposition")),
                    )
                }
            }
        }

    /**
     * 读完整个响应体，但绝不超过 [MAX_BODY_BYTES]。
     *
     * `body.string()` 会把响应一次性读成 String，而 String 是 UTF-16：
     * 一个 1 GB 的响应要占约 2 GB 内存，必定 OOM。订阅自动更新是后台周期
     * 任务，会一遍遍地把网关杀掉。
     *
     * 两道闸都得有：`contentLength()` 那道只在服务端老实报长度时管用，
     * 而不报长度（chunked）恰恰是绕过它最省事的方式，所以真正封顶的是下面
     * 按实际字节数的那道。`request()` 只把数据读进缓冲、不消费，
     * 后面仍然交给 `string()` 去做 BOM 与字符集处理。
     */
    private fun ResponseBody.readCapped(): String {
        val declared = contentLength()
        if (declared > MAX_BODY_BYTES) {
            throw IOException("订阅内容过大：${declared / 1024 / 1024} MB，上限 $MAX_BODY_MB MB")
        }
        if (source().request(MAX_BODY_BYTES + 1)) {
            throw IOException("订阅内容超过 $MAX_BODY_MB MB 上限")
        }
        return string()
    }

    private fun parseFilename(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""").find(contentDisposition)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "NiceProxy/0.1 (sing-box)"

        /**
         * 上千节点的机场 YAML 大约几 MB，16 MB 留了足够余量；
         * 同时也远低于 `SubscriptionParser` 给 SnakeYAML 放宽到的 64 MB
         * codePointLimit —— 那个限制在这一层之后，指望不上。
         */
        const val MAX_BODY_MB = 16
        const val MAX_BODY_BYTES = MAX_BODY_MB * 1024L * 1024L
    }
}
