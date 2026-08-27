package com.niceproxy.core.network.clash

import com.niceproxy.core.model.ClashApiSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * sing-box 内置 Clash API 的客户端。
 *
 * 这是 Kotlin 侧获取实时流量、连接列表、日志，以及执行节点切换和延迟测速的
 * 唯一通道 —— 一次性覆盖 FR-2.8、FR-6.1、FR-6.2、FR-6.3、FR-6.5 五条需求，
 * 并且切换节点不需要重启内核。见 docs/DESIGN.md §6.9。
 *
 * 服务端只监听 127.0.0.1，密钥随安装随机生成。
 */
@Singleton
class ClashApiClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 对端就在本机，请求慢了一定是出了问题，所以超时给得很短。 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** WebSocket 是长连接，读超时必须关闭。 */
    private val streamClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- 流式接口

    fun traffic(settings: ClashApiSettings): Flow<TrafficFrame> =
        webSocketFlow(settings, "traffic") { json.decodeFromString<TrafficFrame>(it) }

    fun logs(settings: ClashApiSettings, level: String = "info"): Flow<LogFrame> =
        webSocketFlow(settings, "logs", mapOf("level" to level)) {
            json.decodeFromString<LogFrame>(it)
        }

    fun connections(settings: ClashApiSettings): Flow<ConnectionsSnapshot> =
        webSocketFlow(settings, "connections") {
            json.decodeFromString<ConnectionsSnapshot>(it)
        }

    fun memory(settings: ClashApiSettings): Flow<MemoryFrame> =
        webSocketFlow(settings, "memory") { json.decodeFromString<MemoryFrame>(it) }

    /**
     * 退避计数放在 `flow { }` 内部，让每一次收集各持一份。
     *
     * 放在外面（或者用 `retryWhen` 自带的 `attempt`）会有两个后果：并发收集的
     * 两条流互相缩短对方的退避；以及 `attempt` 是**终身累计**的，成功发射之后
     * 不归零 —— 一次长会话里断开五次之后，第六次就永久终结了。而通知栏的流量
     * 数字正挂在这条流上，结果是代理明明在工作，通知却停止刷新，用户以为它死了。
     */
    private fun <T> webSocketFlow(
        settings: ClashApiSettings,
        path: String,
        query: Map<String, String> = emptyMap(),
        parse: (String) -> T,
    ): Flow<T> = flow {
        var consecutiveFailures = 0
        emitAll(
            frames(settings, path, query, parse)
                // 收到一帧就说明连接是活的，退避从头开始算
                .onEach { consecutiveFailures = 0 }
                .retryWhen { cause, _ ->
                    if (cause !is IOException) return@retryWhen false
                    consecutiveFailures++
                    // 内核真停了之后每次连接都是即刻 ECONNREFUSED，
                    // 退避曲线必须真生效，否则这里就是个忙等循环。
                    delay(retryDelayMillis(consecutiveFailures))
                    true
                },
        )
    }

    private fun <T> frames(
        settings: ClashApiSettings,
        path: String,
        query: Map<String, String>,
        parse: (String) -> T,
    ): Flow<T> = callbackFlow {
        val url = settings.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(path)
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

        val socket = streamClient.newWebSocket(
            authorized(url, settings).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 内核偶尔推来无法解析的帧（例如版本更新引入的新字段），
                    // 丢掉单帧远好于让整条流断掉。
                    runCatching { parse(text) }.getOrNull()?.let { trySend(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }

                /**
                 * 服务端优雅关闭（内核正常停止）时只会发一个 Close 帧。不回一个
                 * Close，`onClosed` 就永远不来，这条 callbackFlow 既不完成也不
                 * 失败，收集方永久挂起 —— 流量数字冻结、日志停止滚动、TCP 半开。
                 *
                 * 结束时带一个 IOException 而不是正常完成：内核重启是这条流最
                 * 常见的中断原因，走重试路径才能在它回来之后自动接上；正常完成
                 * 会让流悄悄结束，用户得手动退出重进页面。
                 */
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    close(IOException("Clash API 连接被服务端关闭（$code）"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close(IOException("Clash API 连接已关闭（$code）"))
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    /** 指数退避，封顶到分钟级。内核停着的时候这条曲线决定了我们多久打扰它一次。 */
    private fun retryDelayMillis(consecutiveFailures: Int): Long =
        (RETRY_BASE_DELAY_MS shl (consecutiveFailures - 1).coerceIn(0, MAX_BACKOFF_SHIFT))
            .coerceAtMost(MAX_RETRY_DELAY_MS)

    // ---------------------------------------------------------------- 请求式接口

    suspend fun version(settings: ClashApiSettings): Result<VersionResponse> =
        get(settings, listOf("version")) { json.decodeFromString(it) }

    suspend fun proxies(settings: ClashApiSettings): Result<ProxiesResponse> =
        get(settings, listOf("proxies")) { json.decodeFromString(it) }

    /** 切换策略组的选中节点。内核无需重启，已建立的连接也不会中断。 */
    suspend fun selectProxy(
        settings: ClashApiSettings,
        group: String,
        proxyName: String,
    ): Result<Unit> = runCatching {
        val url = buildUrl(settings, listOf("proxies", group))
        val payload = json.encodeToString(SelectRequest.serializer(), SelectRequest(proxyName))
        execute(authorized(url, settings).put(payload.toRequestBody(JSON_MEDIA_TYPE)).build())
        Unit
    }

    /** 延迟测速，返回毫秒。timeoutMs 到点仍未完成即判定超时。 */
    suspend fun testDelay(
        settings: ClashApiSettings,
        proxyName: String,
        testUrl: String,
        timeoutMs: Int = 5000,
    ): Result<Int> = runCatching {
        val url = settings.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("proxies")
            .addPathSegment(proxyName)
            .addPathSegment("delay")
            .addQueryParameter("url", testUrl)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        json.decodeFromString<DelayResponse>(execute(authorized(url, settings).get().build())).delay
    }

    suspend fun closeConnection(settings: ClashApiSettings, id: String): Result<Unit> =
        runCatching {
            val url = buildUrl(settings, listOf("connections", id))
            execute(authorized(url, settings).delete().build())
            Unit
        }

    private suspend fun <T> get(
        settings: ClashApiSettings,
        segments: List<String>,
        parse: (String) -> T,
    ): Result<T> = runCatching {
        parse(execute(authorized(buildUrl(settings, segments), settings).get().build()))
    }

    private fun buildUrl(settings: ClashApiSettings, segments: List<String>): HttpUrl =
        settings.baseUrl.toHttpUrl().newBuilder()
            .apply { segments.forEach { addPathSegment(it) } }
            .build()

    private fun authorized(url: HttpUrl, settings: ClashApiSettings): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.secret}")

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            continuation.resume(it.body.string())
                        } else {
                            continuation.resumeWithException(
                                IOException("Clash API ${it.code}: ${it.message}"),
                            )
                        }
                    }
                }
            })
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** WebSocket 正常关闭码。 */
        const val NORMAL_CLOSURE = 1000

        const val RETRY_BASE_DELAY_MS = 300L

        /**
         * 不设重试次数上限，只设退避上限。
         *
         * 这些流的生命周期由收集方（服务、监控页）决定，它一走协程就取消了；
         * 而只要它还在收，就说明用户还想看这些数字 —— 内核重启后自动接上
         * 才是对的。封顶一分钟之后代价是每分钟一次本机连接尝试，可以忽略。
         */
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_BACKOFF_SHIFT = 8
    }
}

@kotlinx.serialization.Serializable
private data class SelectRequest(val name: String)
