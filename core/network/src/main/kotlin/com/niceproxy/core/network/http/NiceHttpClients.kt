package com.niceproxy.core.network.http

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 OkHttp 实例工厂。
 *
 * OkHttp 的默认值是照着「连公网、连很多个域名」调的，而本应用有两类**性质完全不同**
 * 的 HTTP 流量，它们对默认值的偏离方向恰好相反：
 *
 * - **本机控制面**（Clash API，127.0.0.1）：RTT 是微秒级，超时该给到秒级；
 *   对端只有一个，`maxRequestsPerHost` 那道默认闸门会直接变成瓶颈。
 * - **订阅拉取**（公网）：RTT 是百毫秒级，超时该给到几十秒；对端是少数几个机场域名，
 *   连接复用能省下每次 TLS 握手的一个 RTT。
 *
 * 各建各的，比让一个 client 同时凑合两边要省事得多，也不会互相拖累 ——
 * 一次卡住的订阅拉取不该让通知栏的流量数字停下来。
 */
object NiceHttpClients {

    /**
     * 本机 Clash API 的请求式客户端。
     *
     * 三处偏离默认值都是必须的：
     *
     * 1. `maxRequestsPerHost` 默认是 5。所有 Clash API 请求都打向同一个 host，
     *    于是「一次给两百个节点测延迟」会被 OkHttp 自己排成 5 路 —— 我们在上层
     *    设的并发度根本不生效，而现象只是「测速比预期慢很多」，查不到原因。
     * 2. `Proxy.NO_PROXY`。本应用**自己就是一台代理网关**，用户完全可能把这台设备
     *    的系统代理指向它自己；那样默认的 ProxySelector 会把发往 127.0.0.1 的
     *    控制面请求也送进代理，绕一圈回到内核，形成自指环路。
     * 3. `retryOnConnectionFailure(false)`。回环只有一条路由，重试不可能换到别的路上，
     *    只会把一次 ECONNREFUSED 变成两次系统调用；而熔断器要按真实失败次数计数。
     */
    fun loopbackApi(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        // 明写 HTTP/1.1：Clash API 是明文 h1，让 OkHttp 省掉协议协商这一步的判断
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(LOOPBACK_IDLE_CONNECTIONS, LOOPBACK_KEEP_ALIVE_S, TimeUnit.SECONDS))
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = LOOPBACK_MAX_REQUESTS
                maxRequestsPerHost = LOOPBACK_MAX_REQUESTS
            },
        )
        // 对端就在本机，连不上是即刻的事，等两秒纯属浪费
        .connectTimeout(LOOPBACK_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(LOOPBACK_READ_TIMEOUT_S, TimeUnit.SECONDS)
        // callTimeout 是唯一覆盖「从发起到读完」的封顶，缺了它慢速吐字节能让请求永远挂着
        .callTimeout(LOOPBACK_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * 由 [loopbackApi] 派生出的 WebSocket 客户端，**共用同一个连接池与派发器**。
     *
     * 长连接的读超时必须关掉（`/traffic` 一秒一帧、`/logs` 可能几分钟没动静），
     * 而 callTimeout 更是绝对不能留 —— 它会在到点时把一条正常工作的长连接掐掉。
     */
    fun loopbackStream(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        // 内核被 OOM killer 干掉时不会发 FIN，只有 ping 超时能让我们察觉到连接已经死了
        .pingInterval(STREAM_PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    /**
     * 订阅拉取客户端。
     *
     * 连接池刻意比 OkHttp 默认的 5 分钟保活更长一点、槽位更多一点：一次「全部更新」
     * 会连着打同一个机场的多个订阅链接，复用连接省下的是每条链路一次完整 TLS 握手。
     * 同时 `maxRequestsPerHost` 压到一个克制的值 —— 机场普遍有限流，
     * 并发打过去只会换来 429，比串行还慢。
     */
    fun subscription(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(
            ConnectionPool(SUBSCRIPTION_IDLE_CONNECTIONS, SUBSCRIPTION_KEEP_ALIVE_MIN, TimeUnit.MINUTES),
        )
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = SUBSCRIPTION_MAX_REQUESTS
                maxRequestsPerHost = SUBSCRIPTION_MAX_REQUESTS_PER_HOST
            },
        )
        .connectTimeout(SUBSCRIPTION_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(SUBSCRIPTION_READ_TIMEOUT_S, TimeUnit.SECONDS)
        // readTimeout 只管**单次读**：服务端每 29 秒吐一个字节就能让这个请求永远挂着，
        // 而订阅自动更新是后台周期任务，挂住的调用会一直占着线程。
        .callTimeout(SUBSCRIPTION_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        // https 的订阅不接受被 302 到 http：URL 里那个 token 会明文重发一遍，
        // 而它一把梭出整个机场账号。没有任何正常机场依赖这种跳转。
        .followSslRedirects(false)
        .build()

    // ---------------------------------------------------------------- 本机控制面
    private const val LOOPBACK_MAX_REQUESTS = 32
    private const val LOOPBACK_IDLE_CONNECTIONS = 8
    private const val LOOPBACK_KEEP_ALIVE_S = 60L
    private const val LOOPBACK_CONNECT_TIMEOUT_S = 2L
    private const val LOOPBACK_READ_TIMEOUT_S = 8L
    private const val LOOPBACK_CALL_TIMEOUT_S = 10L
    private const val STREAM_PING_INTERVAL_S = 30L

    // ---------------------------------------------------------------- 订阅
    private const val SUBSCRIPTION_MAX_REQUESTS = 16
    private const val SUBSCRIPTION_MAX_REQUESTS_PER_HOST = 4
    private const val SUBSCRIPTION_IDLE_CONNECTIONS = 8
    private const val SUBSCRIPTION_KEEP_ALIVE_MIN = 5L
    private const val SUBSCRIPTION_CONNECT_TIMEOUT_S = 15L
    private const val SUBSCRIPTION_READ_TIMEOUT_S = 30L
    private const val SUBSCRIPTION_CALL_TIMEOUT_S = 60L
}
