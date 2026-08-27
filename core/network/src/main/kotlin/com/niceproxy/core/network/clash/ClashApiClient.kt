package com.niceproxy.core.network.clash

import com.niceproxy.core.model.ClashApiSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    private fun <T> webSocketFlow(
        settings: ClashApiSettings,
        path: String,
        query: Map<String, String> = emptyMap(),
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

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }.retryWhen { cause, attempt ->
        // 内核重启期间连接必然中断，这是预期内的，退避重连而不是让 UI 报错。
        if (cause is IOException && attempt < MAX_RETRY) {
            delay(RETRY_DELAY_MS * (attempt + 1))
            true
        } else {
            false
        }
    }

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
        const val MAX_RETRY = 5L
        const val RETRY_DELAY_MS = 300L
    }
}

@kotlinx.serialization.Serializable
private data class SelectRequest(val name: String)
