package com.niceproxy.core.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.network.concurrent.BoundedWorkers
import com.niceproxy.core.network.concurrent.ExponentialBackoff
import com.niceproxy.core.network.http.NiceHttpClients
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class SubscriptionResponse(
    val body: String,
    /** 响应头 `subscription-userinfo` 的原文，由调用方解析。 */
    val userInfoHeader: String?,
    /** 机场可通过 `profile-title` 或 `content-disposition` 提供分组名。 */
    val suggestedName: String?,
)

/** 一次订阅拉取请求。批量更新时用它把每条订阅各自的 UA 与自定义头带上。 */
data class SubscriptionRequest(
    val url: String,
    val userAgent: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * 服务端明确表示「现在别问，等会儿再来」（429 / 5xx）。**值得重试。**
 */
class SubscriptionTransientException(val code: Int, message: String) : IOException(message)

/**
 * 服务端明确表示「你错了」（401 / 403 / 404 …）。**重试没有意义。**
 *
 * 和 [SubscriptionTransientException] 分成两个类型，是因为把两者混为一谈的代价是
 * 双向的：一律重试，就会对着一个已经过期的订阅链接反复请求，机场那边看到的是一台
 * 在暴力尝试的客户端；一律不重试，则一次网关抖动就让整条订阅当天都更新不了。
 */
class SubscriptionHttpException(val code: Int, message: String) : IOException(message)

@Singleton
class SubscriptionFetcher @Inject constructor(
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val client: OkHttpClient = NiceHttpClients.subscription()

    /**
     * 拉一条订阅，失败按 [RetryPolicy] 退避重试。
     *
     * 默认会重试。订阅更新绝大多数是**后台周期任务**，一次瞬时的 DNS 抖动或
     * 502 就让整条订阅标记为失败，用户下次打开应用看到的是一排红色感叹号 ——
     * 而其实只要过两秒再问一次就好了。
     */
    suspend fun fetch(
        url: String,
        userAgent: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retry: RetryPolicy = RetryPolicy.DEFAULT,
    ): Result<SubscriptionResponse> =
        fetch(SubscriptionRequest(url, userAgent, extraHeaders), retry)

    suspend fun fetch(
        request: SubscriptionRequest,
        retry: RetryPolicy = RetryPolicy.DEFAULT,
    ): Result<SubscriptionResponse> = withContext(ioDispatcher) {
        var last: Result<SubscriptionResponse> = Result.failure(IOException("订阅请求未发起"))
        for (attempt in 0..retry.maxRetries) {
            if (attempt > 0) delay(retry.backoff.delayMillis(attempt))
            last = runCatching { fetchOnce(request) }
            val failure = last.exceptionOrNull() ?: return@withContext last
            // runCatching 会把取消也吞成一个「失败的 Result」，那样调用方的作用域
            // 就取消不干净了 —— 这是 runCatching 用在挂起函数里最常见的坑。
            if (failure is kotlin.coroutines.cancellation.CancellationException) throw failure
            if (!isRetryable(failure)) return@withContext last
        }
        last
    }

    /**
     * 批量拉取，并发有上限。
     *
     * 上限有两个理由，缺一不可：一是本机资源 —— 三十条订阅同时开三十条 TLS 连接，
     * 在低端机上单是握手的内存峰值就够呛；二是对端 —— 机场普遍按 IP 限流，
     * 并发打过去换来的是一串 429，反而比串行更慢，还可能触发临时封禁。
     *
     * 结果按输入顺序返回，调用方可以直接和自己的订阅列表下标对齐。
     */
    suspend fun fetchAll(
        requests: List<SubscriptionRequest>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        retry: RetryPolicy = RetryPolicy.DEFAULT,
    ): List<Result<SubscriptionResponse>> = BoundedWorkers.map(
        items = requests,
        concurrency = concurrency.coerceIn(1, MAX_CONCURRENCY),
    ) { request -> fetch(request, retry) }

    /** 调用方（[fetch]）已经切到 IO 上，这里只管发一次请求。 */
    private fun fetchOnce(request: SubscriptionRequest): SubscriptionResponse {
        val httpRequest = Request.Builder()
            .url(request.url)
            // 不少机场按 UA 返回不同格式：给 clash 返回 YAML，给其他返回 Base64。
            // 默认伪装成通用客户端以拿到最兼容的格式。
            .header(
                "User-Agent",
                request.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT,
            )
            .apply { request.extraHeaders.forEach { (key, value) -> header(key, value) } }
            .get()
            .build()

        return client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw failureFor(response)
            SubscriptionResponse(
                body = response.body.readCapped(),
                userInfoHeader = response.header("subscription-userinfo"),
                suggestedName = response.header("profile-title")
                    ?: parseFilename(response.header("content-disposition")),
            )
        }
    }

    /** 429 和 5xx 是「等会儿再来」，其余非 2xx 是「你错了」。 */
    private fun failureFor(response: Response): IOException =
        if (response.code == TOO_MANY_REQUESTS || response.code >= SERVER_ERROR) {
            SubscriptionTransientException(response.code, "订阅返回 ${response.code}")
        } else {
            SubscriptionHttpException(response.code, "订阅返回 ${response.code}")
        }

    /**
     * 只有「再问一次可能就好了」的失败才重试。
     *
     * 默认分支才是这里的重点：连接被拒、TLS 握手中断、读到一半断线 —— 这些都是
     * 移动网络上的家常便饭，而它们全是 [IOException] 的其他子类。
     */
    private fun isRetryable(cause: Throwable): Boolean = when (cause) {
        is SubscriptionTransientException -> true
        // 链接本身就是错的，再问一百次也是同一个答案
        is SubscriptionHttpException -> false
        // 内容确实那么大，重试只是再下载一遍再拒一遍
        is OversizedBodyException -> false
        is IOException -> true
        else -> false
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
            throw OversizedBodyException("订阅内容过大：${declared / 1024 / 1024} MB，上限 $MAX_BODY_MB MB")
        }
        if (source().request(MAX_BODY_BYTES + 1)) {
            throw OversizedBodyException("订阅内容超过 $MAX_BODY_MB MB 上限")
        }
        return string()
    }

    private fun parseFilename(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        val match = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""").find(contentDisposition)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 响应体超过上限。是 IOException 的子类，沿用既有的失败处理路径。 */
    class OversizedBodyException(message: String) : IOException(message)

    /**
     * 重试节奏。
     *
     * 次数刻意很少：订阅更新是后台周期任务，下一轮很快就会到，
     * 在这里死磕只会拖长一次「全部更新」的总耗时，而用户正盯着那个进度条。
     */
    data class RetryPolicy(
        val maxRetries: Int,
        val backoff: ExponentialBackoff,
    ) {
        companion object {
            val DEFAULT = RetryPolicy(
                maxRetries = 2,
                backoff = ExponentialBackoff(
                    baseMillis = 500L,
                    // 抖动在这里尤其重要：一次「全部更新」的多条订阅往往属于同一个
                    // 机场，它们会在同一秒被限流、于是又在同一秒一起重试。
                    maxMillis = 4_000L,
                ),
            )

            /** 前台手动触发时用：用户在等，宁可快点报错也别让他对着转圈。 */
            val NONE = RetryPolicy(maxRetries = 0, backoff = ExponentialBackoff(1L, 1L))
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "NiceProxy/0.1 (sing-box)"

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500

        /**
         * 批量更新的默认并发度。
         *
         * 四条是照着「不触发机场限流」定的，而不是照着本机能扛多少定的 ——
         * 后者能到几十，但那个数字在这里毫无意义。
         */
        const val DEFAULT_CONCURRENCY = 4
        const val MAX_CONCURRENCY = 16

        /**
         * 上千节点的机场 YAML 大约几 MB，16 MB 留了足够余量；
         * 同时也远低于 `SubscriptionParser` 给 SnakeYAML 放宽到的 64 MB
         * codePointLimit —— 那个限制在这一层之后，指望不上。
         */
        const val MAX_BODY_MB = 16
        const val MAX_BODY_BYTES = MAX_BODY_MB * 1024L * 1024L
    }
}
