package com.niceproxy.core.network.clash

import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.network.concurrent.CircuitBreaker
import com.niceproxy.core.network.concurrent.ExponentialBackoff
import com.niceproxy.core.network.concurrent.OverflowPolicy
import com.niceproxy.core.network.concurrent.SendResult
import com.niceproxy.core.network.concurrent.withBreaker
import com.niceproxy.core.network.concurrent.withProbe
import com.niceproxy.core.network.http.NiceHttpClients
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.atomic.AtomicLong
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
 *
 * ## 这一层的并发形状
 *
 * 观测面（WebSocket）和控制面（REST）在过载时的失效方式完全不同，因此各有各的护栏：
 *
 * - **观测面**：传输线程只做入队，解析与下发在 [parseDispatcher] 上；每条流的缓冲
 *   由 [com.niceproxy.core.network.concurrent.BoundedQueue] 显式限容 ——
 *   流量/内存/连接列表是 latest-value 语义，积压毫无价值，满了就合帧；
 *   日志是独立事件，满了丢最旧的。见 [clashFrameFlow]。
 * - **控制面**：并发上限 + 熔断。内核不在时每次请求都是零耗时的 ECONNREFUSED，
 *   没有熔断的调用方会退化成忙等（现象是「开着代理特别费电」，而不是任何报错）。
 */
@Singleton
class ClashApiClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = NiceHttpClients.loopbackApi()

    /** WebSocket 与 REST 共用连接池和派发器，只在超时与心跳上分道。 */
    private val streamClient: OkHttpClient = NiceHttpClients.loopbackStream(httpClient)

    /**
     * 解析专用派发器，与 OkHttp 的读线程、与 `Dispatchers.IO` 全部隔开。
     *
     * 用 `Default` 而不是 `IO` 的视图：JSON 解析是纯 CPU 活，放到 IO 池里既没有
     * 阻塞可言，还会跟 Room、DataStore、订阅拉取抢线程。并行度压到 2 是因为
     * 同时存在的流最多也就四五条，且它们各自有序 —— 再多的并行只会增加上下文切换。
     */
    private val parseDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(PARSE_PARALLELISM)

    /**
     * 控制面的并发闸门。
     *
     * OkHttp 自己的 `maxRequestsPerHost` 已经放宽（见 [NiceHttpClients.loopbackApi]），
     * 所以这道闸门必须由我们自己压：一次「全部测速」会把两百个 `/delay` 一起丢下来，
     * 而每一个在内核侧都是一次真实的出站连接。不限流就是自己把自己的代理打满。
     */
    private val restGate = Semaphore(MAX_CONCURRENT_REST)

    /**
     * 只保护「连不上」这一类故障，不保护业务错误。见 [withBreaker] 的取舍说明。
     *
     * 与 WebSocket 的重连退避是**分开**的两套：REST 由调用方按需发起，
     * 冷却期把它挡在本地即可；WebSocket 是常驻订阅，需要的是持续重连而不是熔断。
     */
    private val restBreaker = CircuitBreaker()

    private val counters = Counters()

    // ---------------------------------------------------------------- 流式接口

    fun traffic(settings: ClashApiSettings): Flow<TrafficFrame> =
        webSocketFlow(settings, "traffic", LATEST_VALUE_CAPACITY, OverflowPolicy.LATEST_WINS) {
            json.decodeFromString<TrafficFrame>(it)
        }

    fun logs(settings: ClashApiSettings, level: String = "info"): Flow<LogFrame> =
        webSocketFlow(
            settings = settings,
            path = "logs",
            capacity = LOG_RING_CAPACITY,
            policy = OverflowPolicy.DROP_OLDEST,
            query = mapOf("level" to level),
        ) { json.decodeFromString<LogFrame>(it) }

    fun connections(settings: ClashApiSettings): Flow<ConnectionsSnapshot> =
        webSocketFlow(settings, "connections", LATEST_VALUE_CAPACITY, OverflowPolicy.LATEST_WINS) {
            json.decodeFromString<ConnectionsSnapshot>(it)
        }

    fun memory(settings: ClashApiSettings): Flow<MemoryFrame> =
        webSocketFlow(settings, "memory", LATEST_VALUE_CAPACITY, OverflowPolicy.LATEST_WINS) {
            json.decodeFromString<MemoryFrame>(it)
        }

    /**
     * 退避计数放在 `flow { }` 内部，让每一次收集各持一份。
     *
     * 放在外面（或者用 `retryWhen` 自带的 `attempt`）会有两个后果：并发收集的
     * 两条流互相缩短对方的退避；以及 `attempt` 是**终身累计**的，成功发射之后
     * 不归零 —— 一次长会话里断开五次之后，第六次就永久终结了。而通知栏的流量
     * 数字正挂在这条流上，结果是代理明明在工作，通知却停止刷新，用户以为它死了。
     */
    private fun <T : Any> webSocketFlow(
        settings: ClashApiSettings,
        path: String,
        capacity: Int,
        policy: OverflowPolicy,
        query: Map<String, String> = emptyMap(),
        parse: (String) -> T,
    ): Flow<T> = flow {
        var consecutiveFailures = 0
        emitAll(
            frames(settings, path, capacity, policy, query, parse)
                // 收到一帧就说明连接是活的，退避从头开始算
                .onEach { consecutiveFailures = 0 }
                .retryWhen { cause, _ ->
                    if (cause !is IOException) return@retryWhen false
                    consecutiveFailures++
                    counters.reconnects.incrementAndGet()
                    // 内核真停了之后每次连接都是即刻 ECONNREFUSED，
                    // 退避曲线必须真生效，否则这里就是个忙等循环。
                    delay(reconnectBackoff.delayMillis(consecutiveFailures))
                    true
                },
        )
    }

    private fun <T : Any> frames(
        settings: ClashApiSettings,
        path: String,
        capacity: Int,
        policy: OverflowPolicy,
        query: Map<String, String>,
        parse: (String) -> T,
    ): Flow<T> = clashFrameFlow(
        capacity = capacity,
        policy = policy,
        parseDispatcher = parseDispatcher,
        recorder = counters,
        parse = parse,
    ) { sink ->
        val url = settings.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(path)
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

        val socket = streamClient.newWebSocket(
            authorized(url, settings).build(),
            object : WebSocketListener() {
                /**
                 * **这个回调里不能出现任何解析。**
                 *
                 * OkHttp 的 WebSocket 读线程由所有连接共享，在上面解一次
                 * `/connections` 的几十 KB 快照，同一批线程上的 `/traffic`、
                 * `/logs` 会一起被推迟 —— 症状是「日志偶尔一卡一卡的」，
                 * 而没人会想到罪魁祸首是另一个页面的连接列表。
                 */
                override fun onMessage(webSocket: WebSocket, text: String) {
                    sink.onFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sink.onTerminated(t)
                }

                /**
                 * 服务端优雅关闭（内核正常停止）时只会发一个 Close 帧。不回一个
                 * Close，`onClosed` 就永远不来，这条流既不完成也不失败，收集方
                 * 永久挂起 —— 流量数字冻结、日志停止滚动、TCP 半开。
                 *
                 * 结束时带一个 IOException 而不是正常完成：内核重启是这条流最
                 * 常见的中断原因，走重试路径才能在它回来之后自动接上；正常完成
                 * 会让流悄悄结束，用户得手动退出重进页面。
                 */
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    sink.onTerminated(IOException("Clash API 连接被服务端关闭（$code）"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sink.onTerminated(IOException("Clash API 连接已关闭（$code）"))
                }
            },
        )
        FrameSubscription { socket.cancel() }
    }

    /** 指数退避 + 抖动，封顶到分钟级。内核停着的时候这条曲线决定了我们多久打扰它一次。 */
    private val reconnectBackoff = ExponentialBackoff(
        baseMillis = RETRY_BASE_DELAY_MS,
        // 不设重试次数上限，只设退避上限：这些流的生命周期由收集方决定，
        // 它一走协程就取消了；而只要它还在收，就说明用户还想看这些数字。
        maxMillis = MAX_RETRY_DELAY_MS,
    )

    // ---------------------------------------------------------------- 请求式接口

    /**
     * `/version` 同时承担**探活**职责（见 `ProxyService.probeCore`），所以它是唯一
     * 不受熔断阻挡的调用：拿熔断去挡探活是循环论证 —— 冷却期间探活必然失败，
     * 冷却于是永远续下去，看门狗会把一个刚起来的健康内核反复重启。
     */
    suspend fun version(settings: ClashApiSettings): Result<VersionResponse> =
        restBreaker.withProbe(::isConnectivityFailure) {
            restGate.withPermit {
                counters.restCalls.incrementAndGet()
                json.decodeFromString<VersionResponse>(
                    execute(authorized(buildUrl(settings, listOf("version")), settings).get().build()),
                )
            }
        }

    suspend fun proxies(settings: ClashApiSettings): Result<ProxiesResponse> =
        get(settings, listOf("proxies")) { json.decodeFromString(it) }

    /** 切换策略组的选中节点。内核无需重启，已建立的连接也不会中断。 */
    suspend fun selectProxy(
        settings: ClashApiSettings,
        group: String,
        proxyName: String,
    ): Result<Unit> = governed {
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
    ): Result<Int> = governed {
        val url = settings.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("proxies")
            .addPathSegment(proxyName)
            .addPathSegment("delay")
            .addQueryParameter("url", testUrl)
            .addQueryParameter("timeout", timeoutMs.toString())
            .build()
        json.decodeFromString<DelayResponse>(execute(authorized(url, settings).get().build())).delay
    }

    suspend fun closeConnection(settings: ClashApiSettings, id: String): Result<Unit> = governed {
        val url = buildUrl(settings, listOf("connections", id))
        execute(authorized(url, settings).delete().build())
        Unit
    }

    /**
     * 内核确认活着时（例如探活成功）把熔断器立刻放回关闭状态。
     *
     * 没有它，用户手动重启内核之后还要白等一个冷却周期才能操作 —— 而那时候
     * 界面上的按钮明明是可点的，点下去却报「正在冷却」，看起来就是个 bug。
     */
    fun noteCoreAlive() = restBreaker.reset()

    /** 观测量快照。过载与熔断在这里是可见的，不然它们只会表现为「偶尔卡一下」。 */
    fun metrics(): ClashApiMetrics = ClashApiMetrics(
        framesReceived = counters.framesReceived.get(),
        framesCoalesced = counters.framesCoalesced.get(),
        framesDropped = counters.framesDropped.get(),
        parseFailures = counters.parseFailures.get(),
        streamReconnects = counters.reconnects.get(),
        restCalls = counters.restCalls.get(),
        restShortCircuited = restBreaker.shortCircuitedCount,
        breakerState = restBreaker.state,
    )

    private suspend fun <T> get(
        settings: ClashApiSettings,
        segments: List<String>,
        parse: (String) -> T,
    ): Result<T> = governed {
        parse(execute(authorized(buildUrl(settings, segments), settings).get().build()))
    }

    /**
     * 所有 REST 调用的统一入口：先过熔断，再过并发闸门。
     *
     * 顺序不能颠倒。反过来的话，内核不在的时候那两百个 `/delay` 会先在信号量上
     * 排成长队，然后一个个被熔断器秒退 —— 队列该有的削峰作用没了，
     * 而每个调用方还是得排到队才知道自己被拒。
     */
    private suspend fun <T> governed(block: suspend () -> T): Result<T> =
        restBreaker.withBreaker(isConnectivityFailure = ::isConnectivityFailure) {
            restGate.withPermit {
                counters.restCalls.incrementAndGet()
                block()
            }
        }

    /**
     * 只有「连不上」才计入熔断。
     *
     * [ClashApiHttpException] 明确排除在外：内核回了一个 4xx / 5xx，恰恰证明它活着。
     * 最典型的是 `/delay` —— 节点不通时内核返回一个错误状态码，而那是**被测节点**的
     * 问题，把它算进熔断等于让一个坏节点连累掉整个控制面。
     */
    private fun isConnectivityFailure(cause: Throwable): Boolean =
        cause is IOException && cause !is ClashApiHttpException

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
                                ClashApiHttpException(it.code, it.message),
                            )
                        }
                    }
                }
            })
        }

    /** 所有流共用一份计数器。分流统计没有额外价值，而累加是无锁的。 */
    private class Counters : FrameStreamRecorder {
        val framesReceived = AtomicLong()
        val framesCoalesced = AtomicLong()
        val framesDropped = AtomicLong()
        val parseFailures = AtomicLong()
        val reconnects = AtomicLong()
        val restCalls = AtomicLong()

        override fun onSendResult(result: SendResult) {
            framesReceived.incrementAndGet()
            when (result) {
                SendResult.COALESCED -> framesCoalesced.incrementAndGet()
                SendResult.DROPPED_OLDEST, SendResult.REJECTED -> framesDropped.incrementAndGet()
                SendResult.ACCEPTED -> Unit
            }
        }

        override fun onParseFailure() {
            parseFailures.incrementAndGet()
        }
    }

    internal companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** WebSocket 正常关闭码。 */
        const val NORMAL_CLOSURE = 1000

        const val RETRY_BASE_DELAY_MS = 300L
        const val MAX_RETRY_DELAY_MS = 60_000L

        const val PARSE_PARALLELISM = 2

        /**
         * latest-value 流的容量。
         *
         * 取 1 就是纯合帧：收集方还没消费完上一帧时，新到的那帧直接原地替换。
         * 对流量/内存/连接快照来说这正是想要的 —— 迟到的旧值一旦被显示出来，
         * 用户看到的就是一个**倒退**的数字，比丢掉它糟糕得多。
         */
        const val LATEST_VALUE_CAPACITY = 1

        /**
         * 日志环的容量。
         *
         * 日志是独立事件，合帧会丢掉中间内容，所以用环形保留最近若干条。256 条
         * 足够覆盖界面一屏加上滚动缓冲，而内核在 debug 级别下能一秒推几百条 ——
         * 不限容的话，用户切到日志页顺手调成 debug 就是一条稳定的 OOM 路径。
         */
        const val LOG_RING_CAPACITY = 256

        /**
         * REST 并发上限。
         *
         * 每个 `/delay` 在内核侧都是一次真实出站连接，八路已经足以跑满一条家宽；
         * 再高只会让测速结果互相干扰，测出来的延迟比实际值高一大截。
         */
        const val MAX_CONCURRENT_REST = 8
    }
}

/** Clash API 回了一个非 2xx。它是 IOException 的子类，以便沿用现有的失败处理路径。 */
class ClashApiHttpException(val code: Int, val reason: String) :
    IOException("Clash API $code: $reason")

/** 控制面与观测面的运行时观测量，见 [ClashApiClient.metrics]。 */
data class ClashApiMetrics(
    val framesReceived: Long,
    val framesCoalesced: Long,
    val framesDropped: Long,
    val parseFailures: Long,
    val streamReconnects: Long,
    val restCalls: Long,
    val restShortCircuited: Long,
    val breakerState: CircuitBreaker.State,
)

@kotlinx.serialization.Serializable
private data class SelectRequest(val name: String)
