package com.niceproxy.core.service.pac

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PAC 脚本的 HTTP 服务。
 *
 * sing-box 不提供 PAC 能力，所以这一块由应用自己实现。好在 PAC 的协议要求极低 ——
 * 一个返回固定 MIME 的 GET 端点而已，不值得为它引入一个完整的 HTTP 框架。
 *
 * **但「协议简单」不等于「可以不设防」。** 这个端口监听在 `0.0.0.0` 上，局域网里
 * 任何一台设备（包括被入侵的智能插座）都能连上来，而它跑在前台服务进程里，与 Room、
 * DataStore、订阅拉取共用同一个进程。所以下面每一项限额都不是洁癖：请求行长度、
 * 头部行数、整请求字节数、并发连接数、整条连接的存活时间，全部有上限，超了就断。
 * 没有它们，一台设备只要持续灌一行不带换行的数据就能把堆撑爆、让进程被系统杀掉 ——
 * 全屋断网，而看门狗最长要 15 分钟才会来救。
 *
 * 见 docs/DESIGN.md §6.5。
 *
 * @param maxPerClient 单个客户端 IP 能同时占住的连接数。低于 [MAX_CONCURRENT_CONNECTIONS]
 *        才有意义 —— 见 [clientSlots] 的说明。只有测试会显式传值。
 */
@Singleton
class PacServer internal constructor(
    private val maxPerClient: Int,
) {

    @Inject
    constructor() : this(DEFAULT_MAX_PER_CLIENT)

    /** 会被 accept 循环、请求处理协程和调用方三边读到，必须是 volatile。 */
    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptJob: Job? = null

    /**
     * 专属线程池，而不是直接用 `Dispatchers.IO`。
     *
     * `Dispatchers.IO` 是**全 App 共享**的：Room 的查询、DataStore 的读写、订阅拉取
     * 都排在同一个池子里。PAC 的处理是阻塞式 socket 读，一条慢连接就占死一个线程，
     * 而慢连接恰恰是最容易被制造出来的东西（每隔几秒发一个字节即可）。不隔离的话，
     * 症状会以「打开节点列表卡住」「设置保存不了」这类和 PAC 毫无关系的形式冒出来，
     * 排查时根本不会有人往这边想。
     *
     * `Dispatchers.IO.limitedParallelism` 得到的视图是弹性的 —— 它占的线程不从 IO 池
     * 的额度里扣，所以哪怕这里被占满，别的模块也不受影响。并发数比
     * [MAX_CONCURRENT_CONNECTIONS] 多留一个：那一个是 accept 循环自己的，否则连接一满
     * accept 就再也拿不到线程，连排队都排不上。
     */
    private val dispatcher =
        Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_CONNECTIONS + 1)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 同时在处理的连接数闸门。 */
    private val inFlight = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    /**
     * 正在处理中的客户端 socket。
     *
     * 之所以要记着它们：[serve] 里是**阻塞式**读，协程取消打不断阻塞在 read 上的线程。
     * 停服务时只有把 socket 关掉，那些 read 才会立刻抛异常返回；否则 [stop] 要一直等
     * 到读超时。
     */
    private val liveConnections: MutableSet<Socket> =
        Collections.synchronizedSet(mutableSetOf<Socket>())

    /**
     * 每个客户端 IP 当前占住的连接数。
     *
     * [inFlight] 那道全局闸门只保证「进程不会被撑爆」，它保证不了**公平**：一台被
     * 入侵的智能插座开 32 条 Slowloris，就能把全部槽位占满整整十秒，这期间局域网里
     * 其他所有设备取 PAC 全部超时 —— 表现为「家里突然集体上不了网」，而网关进程
     * 一切正常、日志里也只有几条限流记录。压住每个 IP 的份额之后，一个客户端最多
     * 只能占住 [maxPerClient] 个槽位，剩下的永远留给别人。
     *
     * 计数归零就把键删掉：不然一次全网段扫描会在这个 map 里留下 254 个常驻条目。
     */
    private val clientSlots = ConcurrentHashMap<String, Int>()

    /**
     * 当前这一轮监听的 PAC 响应缓存，键是脚本里要用的主机名。
     *
     * 缓存的是**整个响应的字节**，不只是脚本文本：PAC 的内容只取决于主机名，
     * 而同一台设备的浏览器、系统代理设置、各类应用会在几秒内反复来取同一份。
     * 每次都重跑一遍脚本拼接加 UTF-8 编码，纯粹是在给一个只有几百字节的静态响应
     * 付 CPU；在 32 条并发的压力下这笔开销恰好落在最不该出现的时刻。
     *
     * 随 [start] 整个换掉，所以配置变更之后绝不会发出旧脚本 —— 缓存的生命周期
     * 与那份 `resolveScript` 闭包严格一致。
     */
    @Volatile
    private var responseCache: ResponseCache? = null

    private val counters = Counters()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** 运行期观测量。限流有没有真的在拦人、缓存有没有命中，只能从这里看出来。 */
    fun metrics(): Metrics = counters.snapshot(
        inFlight = MAX_CONCURRENT_CONNECTIONS - inFlight.availablePermits,
        cachedHosts = responseCache?.size ?: 0,
    )

    /**
     * @param resolveScript 由调用方按「客户端访问用的主机名」生成脚本内容。
     *        传 host 进去而不是固定一个 IP，是因为同一台设备可能同时挂在
     *        Wi-Fi 和热点上，客户端从哪个网段进来，PAC 里就该给哪个地址 ——
     *        给错了客户端会指向一个它根本路由不到的 IP。
     */
    suspend fun start(port: Int, resolveScript: (host: String) -> String) {
        stop()

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LISTEN_ALL, port))
        } catch (t: Throwable) {
            // bind 失败（端口被占、无权绑定）时 fd 已经创建出来了。以前这里靠外层的
            // onFailure 调 stop() 收拾，可那时 serverSocket 还是 null，关掉的是个寂寞
            // —— 退避重试一轮下来能漏掉六个 fd。
            runCatching { socket.close() }
            Log.w(TAG, "PAC 服务启动失败", t)
            return
        }
        serverSocket = socket
        // 缓存与这一份 resolveScript 闭包绑定：换了配置就整个丢掉，
        // 绝不可能有一份指向旧端口的脚本活过这次重启
        responseCache = ResponseCache(MAX_CACHED_HOSTS, resolveScript)
        acceptJob = scope.launch { acceptLoop(socket) }
    }

    /**
     * 停止并等到旧协程真的退干净。
     *
     * **必须等**，不能只 cancel 就走。`resolveScript` 是个闭包，捕获着上一份配置里的
     * 端口号；而 [serve] 阻塞在 socket 读上，取消打不断它。旧协程只要还活着，就可能在
     * 新监听建立的同一瞬间发出一份指向**旧端口**的 PAC —— 客户端缓存了那一份之后就
     * 一直连不上，而界面上一切正常，因为服务确实在跑。
     */
    suspend fun stop() {
        closeSockets()
        acceptJob?.cancelAndJoin()
        acceptJob = null
        responseCache = null
        clientSlots.clear()
    }

    private fun closeSockets() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 先复制再关：close 会触发 serve() 的 finally 反过来改这个集合
        synchronized(liveConnections) { liveConnections.toList() }
            .forEach { runCatching { it.close() } }
    }

    private suspend fun CoroutineScope.acceptLoop(socket: ServerSocket) {
        var failures = 0
        while (isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                // stop() 关闭 socket 会让阻塞中的 accept 抛异常，属正常退出
                return
            } catch (e: IOException) {
                // 以前这里直接 break：一次 EMFILE（进程 fd 用尽，往往还是别的模块引起
                // 的）就让 PAC 永久死掉，而 serverSocket 仍非 null、isRunning 仍返回
                // true，于是整个系统都以为它活着，用户只发现「PAC 地址打不开了」。
                failures++
                counters.acceptFailures.incrementAndGet()
                if (failures > MAX_ACCEPT_FAILURES) {
                    Log.w(TAG, "PAC accept 连续失败 $failures 次，停止监听", e)
                    // 关掉它，好让 isRunning 如实变成 false，上层才有机会重建
                    runCatching { socket.close() }
                    return
                }
                Log.w(TAG, "PAC accept 失败，第 $failures 次退避重试", e)
                delay(ACCEPT_RETRY_DELAY_MS * failures)
                continue
            }
            failures = 0

            // 先拿许可再 launch。反过来的话，对面建多少连接我们就起多少协程，闸门形同
            // 虚设 —— 内存和 fd 会在协程排队等许可的过程中先被耗光。这里的挂起同时
            // 构成对 accept 的背压：满了就先不收新的。
            try {
                inFlight.acquire()
            } catch (t: Throwable) {
                // 停服务时这里会被取消。此刻这条连接已经 accept 出来、却还没交给任何
                // 协程，也就没进 liveConnections —— 不在这里关掉它就是一个没人认领的 fd。
                runCatching { client.close() }
                throw t
            }
            counters.accepted.incrementAndGet()
            launch {
                try {
                    serve(client)
                } finally {
                    inFlight.release()
                }
            }
        }
    }

    private fun serve(client: Socket) {
        liveConnections.add(client)
        val clientKey = client.inetAddress?.hostAddress ?: UNKNOWN_CLIENT
        var admitted = false
        try {
            // 注册的动作与 closeSockets() 的快照之间有一瞬空隙。补这一次检查，
            // 否则挤进空隙的那条连接会让 stop() 一直等到它自己读超时才结束。
            if (serverSocket == null) return

            admitted = acquireClientSlot(clientKey)
            if (!admitted) {
                // 明确回一个 503 而不是直接关：被限流的往往是家里某台行为异常的设备，
                // 静默 RST 只会让它无限重试，而它的主人永远不知道发生了什么
                counters.clientLimited.incrementAndGet()
                runCatching {
                    client.write(SERVICE_UNAVAILABLE)
                    drainQuietly(client)
                }
                return
            }

            runCatching { respond(client) }
                .onFailure { if (it !is IOException) Log.w(TAG, "PAC 请求处理失败", it) }
        } finally {
            if (admitted) releaseClientSlot(clientKey)
            liveConnections.remove(client)
            runCatching { client.close() }
        }
    }

    /** @return 拿到名额返回 true。达到上限时不占坑，直接拒。 */
    private fun acquireClientSlot(client: String): Boolean {
        var admitted = false
        clientSlots.compute(client) { _, current ->
            val used = current ?: 0
            if (used >= maxPerClient) {
                used
            } else {
                admitted = true
                used + 1
            }
        }
        return admitted
    }

    private fun releaseClientSlot(client: String) {
        // 归零就删键，否则一次全网段扫描会在 map 里留下二百多个常驻条目
        clientSlots.computeIfPresent(client) { _, used -> (used - 1).takeIf { it > 0 } }
    }

    private fun respond(socket: Socket) {
        val reader = BoundedRequestReader(socket, System.nanoTime() + CONNECTION_BUDGET_NANOS)

        val request = try {
            reader.readRequest()
        } catch (e: RequestRejectedException) {
            counters.rejected.incrementAndGet()
            socket.write(errorResponse(e.status, e.reason))
            drainQuietly(socket)
            return
        }
        if (request == null) return

        val host = request.host?.let(::stripPort)?.takeIf(PacScript::isValidHost)
            ?: localHost(socket)

        when {
            request.method != "GET" -> {
                counters.rejected.incrementAndGet()
                socket.write(errorResponse(405, "Method Not Allowed"))
            }

            !request.path.startsWith(PAC_PATH) -> {
                counters.rejected.incrementAndGet()
                socket.write(errorResponse(404, "Not Found"))
            }

            else -> {
                // 服务已经在关停的路上时缓存已被丢掉，此刻绝不能再拿旧闭包现生成一份
                val cache = responseCache ?: return
                val cached = cache.get(host, counters)
                counters.served.incrementAndGet()
                socket.write(cached)
            }
        }
    }

    /**
     * 一次 write 写完整个响应。
     *
     * 不用 `BufferedOutputStream` 包一层：响应本来就是一个已经拼好的连续字节数组，
     * 再套一层缓冲只是多一次拷贝。真正省下来的是**编码**那一步 —— 见 [ResponseCache]。
     */
    private fun Socket.write(response: ByteArray) {
        getOutputStream().apply {
            write(response)
            flush()
        }
    }

    /**
     * 拒掉一个请求之后，先把对面还在发的东西读掉一点再关。
     *
     * 直接 close 的话，内核发现接收缓冲区里还有没被读走的数据，会发 RST 而不是 FIN
     * —— 而 RST 会让对面连同**已经收到的响应一起丢掉**。那样我们精心回的那个 431 就
     * 白回了，客户端只会看到「连接被重置」，跟没做限流时的表现毫无区别。
     */
    private fun drainQuietly(socket: Socket) {
        runCatching {
            socket.soTimeout = DRAIN_TIMEOUT_MS
            val input = socket.getInputStream()
            val buffer = ByteArray(DRAIN_CHUNK_BYTES)
            var remaining = MAX_REQUEST_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                remaining -= read
            }
        }
    }

    /**
     * Host 头非法或缺失时的回落地址。
     *
     * 用本机在**这条连接**上的地址，而不是随便挑一个：客户端既然连上来了，这个地址
     * 对它一定是可路由的。IPv6 要补上方括号，否则 PAC 里的冒号会被客户端当成端口
     * 分隔符。
     */
    private fun localHost(socket: Socket): String {
        val raw = socket.localAddress?.hostAddress ?: return LOOPBACK
        val candidate = if (raw.contains(':')) "[$raw]" else raw
        return candidate.takeIf(PacScript::isValidHost) ?: LOOPBACK
    }

    /** Host 头里带端口，PAC 里要的是纯主机名。IPv6 字面量的冒号不能当端口分隔符。 */
    private fun stripPort(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith('[')) {
            val end = trimmed.indexOf(']')
            return if (end < 0) trimmed else trimmed.substring(0, end + 1)
        }
        return trimmed.substringBefore(':')
    }

    private fun errorResponse(code: Int, reason: String): ByteArray =
        "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.UTF_8)

    /**
     * 按主机名缓存整个响应的字节。
     *
     * 同一台设备的浏览器、系统代理设置、各类应用会在几秒内反复来取同一份 PAC，
     * 而这份内容只取决于主机名。每次都重跑一遍脚本拼接加 UTF-8 编码，就是在给一个
     * 几百字节的静态响应反复付 CPU —— 偏偏这笔开销总是集中在「刚连上 Wi-Fi、
     * 一屋子设备同时来取」的那一瞬，也就是最不该慢的时刻。
     *
     * 用 access-order 的 [LinkedHashMap] 做 LRU 并封顶：主机名来自 Host 头，虽然
     * 已经过 [PacScript.isValidHost] 过滤，仍然是**外部可控**的输入 —— 不封顶的话，
     * 局域网里任何一台设备只要每次换一个合法的 Host 值，就能把这张表撑到 OOM。
     */
    private class ResponseCache(
        private val maxEntries: Int,
        private val resolveScript: (String) -> String,
    ) {
        private val entries = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
                size > maxEntries
        }

        val size: Int get() = synchronized(entries) { entries.size }

        fun get(host: String, counters: Counters): ByteArray {
            synchronized(entries) { entries[host] }?.let {
                counters.cacheHits.incrementAndGet()
                return it
            }
            // 生成放在锁外：resolveScript 是调用方给的闭包，把它圈进锁里就等于让
            // 一个我们管不着的函数决定所有并发请求的排队长度。多生成一两次的代价
            // 远小于此。
            val response = render(resolveScript(host))
            synchronized(entries) { entries[host] = response }
            return response
        }

        private fun render(script: String): ByteArray {
            val body = script.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                // 这个 MIME 是 PAC 的事实标准，用 text/plain 会让部分系统拒绝解析
                append("Content-Type: application/x-ns-proxy-autoconfig; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                // 配置随时可能变，绝不能让客户端缓存
                append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            return head + body
        }
    }

    /** 累加器与对外快照分开：前者要无锁地被几十条连接同时更新，后者只是一次读。 */
    private class Counters {
        val accepted = AtomicLong()
        val served = AtomicLong()
        val rejected = AtomicLong()
        val clientLimited = AtomicLong()
        val cacheHits = AtomicLong()
        val acceptFailures = AtomicLong()

        fun snapshot(inFlight: Int, cachedHosts: Int) = Metrics(
            accepted = accepted.get(),
            served = served.get(),
            rejected = rejected.get(),
            clientLimited = clientLimited.get(),
            cacheHits = cacheHits.get(),
            acceptFailures = acceptFailures.get(),
            inFlight = inFlight,
            cachedHosts = cachedHosts,
        )
    }

    /**
     * PAC 服务的运行期观测量。
     *
     * [clientLimited] 一直在涨说明局域网里有一台设备在异常地开连接；[rejected] 涨则是
     * 有人在灌畸形请求。这两件事在没有指标的时候只会表现为「家里有些设备偶尔上不了网」，
     * 而那个现象查不出任何原因。
     */
    data class Metrics(
        val accepted: Long,
        val served: Long,
        val rejected: Long,
        val clientLimited: Long,
        val cacheHits: Long,
        val acceptFailures: Long,
        val inFlight: Int,
        val cachedHosts: Int,
    )

    private data class ParsedRequest(
        val method: String,
        val path: String,
        val host: String?,
    )

    /** 请求触碰了某项限额，带上该回给对面的状态码。 */
    private class RequestRejectedException(
        val status: Int,
        val reason: String,
    ) : IOException(reason)

    /**
     * 带上限的请求读取。
     *
     * 手写而不用 [java.io.BufferedReader.readLine]：后者内部拿一个**无界**的
     * StringBuilder 累积，遇到一行永远不结束的输入就一路吃到 OOM，而且它没有
     * 「整个请求最多读多少」的概念。
     *
     * `soTimeout` 同样不够用 —— 它是**每次 read** 的超时，对面每隔一秒发一个字节就能
     * 让它永远不触发（Slowloris）。所以这里额外压一条整条连接的截止时间。
     */
    private class BoundedRequestReader(socket: Socket, private val deadlineNanos: Long) {

        private val input: InputStream = socket.getInputStream().buffered()
        private var consumed = 0

        init {
            socket.soTimeout = READ_TIMEOUT_MS
        }

        /** @return 请求的必要部分；对面没发出一个完整的请求行就返回 null。 */
        fun readRequest(): ParsedRequest? {
            val requestLine = readLine() ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            return ParsedRequest(
                method = parts[0].uppercase(),
                path = parts[1],
                host = readHeaders()[HOST_HEADER]?.takeIf { it.isNotBlank() },
            )
        }

        /** 读到空行为止。PAC 请求没有 body，不必消费更多。 */
        private fun readHeaders(): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val line = readLine() ?: break
                if (line.isBlank()) break
                if (++count > MAX_HEADER_LINES) reject("头部超过 $MAX_HEADER_LINES 行")
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                headers[line.substring(0, separator).lowercase()] =
                    line.substring(separator + 1).trim()
            }
            return headers
        }

        /** @return 一行内容（不含换行符），读到流末尾返回 null。 */
        private fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    throw RequestRejectedException(408, "Request Timeout")
                }
                val byte = input.read()
                if (byte < 0) return line.takeIf { it.isNotEmpty() }?.toString()

                if (++consumed > MAX_REQUEST_BYTES) reject("请求超过 $MAX_REQUEST_BYTES 字节")
                when {
                    byte == LF -> return line.toString()
                    // 单独出现的 \r 按 HTTP 的宽容惯例忽略
                    byte == CR -> Unit
                    line.length >= MAX_LINE_BYTES -> reject("单行超过 $MAX_LINE_BYTES 字节")
                    // 请求行与头部按 RFC 只允许 ASCII，非 ASCII 字节到这里会变成
                    // Latin-1 字符，随后一定过不了 host 白名单，正是我们要的结果
                    else -> line.append(Char(byte))
                }
            }
        }

        private fun reject(reason: String): Nothing {
            Log.w(TAG, "PAC 请求被拒绝：$reason")
            throw RequestRejectedException(431, "Request Header Fields Too Large")
        }
    }

    companion object {
        const val PAC_PATH = "/proxy.pac"

        private const val TAG = "PacServer"
        private const val LISTEN_ALL = "0.0.0.0"
        private const val LOOPBACK = "127.0.0.1"
        private const val HOST_HEADER = "host"
        private const val UNKNOWN_CLIENT = "unknown"

        private val SERVICE_UNAVAILABLE =
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
        private const val CR = '\r'.code
        private const val LF = '\n'.code

        /**
         * 单次 read 的超时。它挡不住 Slowloris（对面稳定慢速发送就永远不触发），
         * 真正的兜底是 [CONNECTION_BUDGET_MS]。
         */
        private const val READ_TIMEOUT_MS = 5_000

        /** 拒绝请求后收尾读取的时限，见 [drainQuietly]。 */
        private const val DRAIN_TIMEOUT_MS = 250
        private const val DRAIN_CHUNK_BYTES = 2 * 1024

        /** 整条连接从建立到读完请求头的总预算。 */
        private const val CONNECTION_BUDGET_MS = 10_000L
        private const val CONNECTION_BUDGET_NANOS = CONNECTION_BUDGET_MS * 1_000_000

        /** 一行 8 KB。真实的 PAC 请求行加 Host 头合起来不到 100 字节。 */
        internal const val MAX_LINE_BYTES = 8 * 1024
        internal const val MAX_HEADER_LINES = 32
        internal const val MAX_REQUEST_BYTES = 16 * 1024

        /** 家里的设备数远不到这个量级，而这也是攻击者能同时占住的资源上限。 */
        internal const val MAX_CONCURRENT_CONNECTIONS = 32

        /**
         * 单个客户端 IP 的份额。
         *
         * 正常设备取一份 PAC 只开一条连接，浏览器加系统代理同时来也不过两三条，
         * 八条留了充足余量。而它同时保证了「一台设备最多只能占住四分之一的槽位」——
         * 剩下的四分之三永远留给局域网里其他设备。
         */
        internal const val DEFAULT_MAX_PER_CLIENT = MAX_CONCURRENT_CONNECTIONS / 4

        /**
         * 缓存的主机名数量上限。
         *
         * 一台设备可能同时挂在 Wi-Fi 与热点上，再加上 IPv4 / IPv6 两种字面量，
         * 实际用到的主机名不过个位数。封顶是因为主机名来自 Host 头 —— 那是外部输入。
         */
        private const val MAX_CACHED_HOSTS = 16

        private const val MAX_ACCEPT_FAILURES = 5
        private const val ACCEPT_RETRY_DELAY_MS = 500L
    }
}
