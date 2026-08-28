package com.niceproxy.core.service.pac

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * PacServer 是手写的 HTTP 服务，socket 层没有任何框架兜底，所以这里一律绑真实端口、
 * 发真实请求、按字节校验响应，而不是去测内部方法 —— 会出问题的正是内部方法之间的接缝。
 */
class PacServerTest {

    private var server = PacServer()
    private val port = freePort()

    @AfterEach
    fun tearDown() = runBlocking { server.stop() }

    @Nested
    @DisplayName("正常请求")
    inner class HappyPath {

        @Test
        @DisplayName("GET /proxy.pac 返回 200、PAC 专用 MIME 与禁止缓存")
        fun servesScript() {
            start { "// pac" }

            val response = request("GET /proxy.pac HTTP/1.1", "Host: 192.168.1.8:8090")

            assertThat(response.statusLine).isEqualTo("HTTP/1.1 200 OK")
            // 用 text/plain 会让部分系统拒绝解析 PAC，这个 MIME 是事实标准
            assertThat(response.header("Content-Type"))
                .isEqualTo("application/x-ns-proxy-autoconfig; charset=utf-8")
            // 配置随时可能变，客户端缓存住旧脚本就会一直指向错误的代理
            assertThat(response.header("Cache-Control")).contains("no-store")
            assertThat(response.body).isEqualTo("// pac")
            assertThat(response.header("Content-Length")).isEqualTo("6")
        }

        @Test
        @DisplayName("脚本按请求方的 Host 头生成，Host 里的端口被剥掉")
        fun scriptFollowsHostHeader() {
            // 设备可能同时挂在 Wi-Fi 和热点上，客户端从哪个网段进来就得给哪个地址，
            // 给错了它会拿到一个根本路由不到的 IP
            start { host ->
                PacScript.build(PacScript.Options(host = host, httpPort = 8080, socksPort = 1080))
            }

            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 192.168.43.1:8090").body)
                .contains("PROXY 192.168.43.1:8080; SOCKS5 192.168.43.1:1080")
            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 10.0.0.5:8090").body)
                .contains("PROXY 10.0.0.5:8080; SOCKS5 10.0.0.5:1080")
        }

        @Test
        @DisplayName("IPv6 字面量保留方括号，只剥掉端口")
        fun keepsIpv6Brackets() {
            // 按第一个冒号切会切出一个「[」，那份 PAC 客户端根本没法用
            start { host -> "host=$host" }

            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: [fe80::1]:8090").body)
                .isEqualTo("host=[fe80::1]")
        }

        @Test
        @DisplayName("连接用完即关，同一个服务能连续处理多个请求")
        fun servesSequentialRequests() {
            start { "// pac" }

            repeat(3) {
                assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").statusLine)
                    .isEqualTo("HTTP/1.1 200 OK")
            }
        }
    }

    @Nested
    @DisplayName("Host 头的处理")
    inner class HostHeader {

        @Test
        @DisplayName("缺失时回落到本机地址，而不是给出空主机名")
        fun fallsBackWhenHostMissing() {
            start { host -> "host=$host" }

            val response = request("GET /proxy.pac HTTP/1.1")

            assertThat(response.statusLine).isEqualTo("HTTP/1.1 200 OK")
            assertThat(response.body).isEqualTo("host=127.0.0.1")
        }

        @Test
        @DisplayName("畸形 Host 被丢弃并回落，绝不原样拼进脚本")
        fun rejectsMalformedHost() {
            // Host 头是完全不可信的输入。原样拼进去会产出一份语法坏掉的 PAC，
            // 客户端只显示「代理配置无效」，没有任何线索指向真正的原因。
            start { host -> "host=$host" }

            listOf("""evil"host""", "a b", "<script>", "a;DIRECT").forEach { malformed ->
                assertThat(request("GET /proxy.pac HTTP/1.1", "Host: $malformed").body)
                    .isEqualTo("host=127.0.0.1")
            }
        }
    }

    @Nested
    @DisplayName("请求限额")
    inner class Limits {

        @Test
        @DisplayName("超长的请求行被拒绝，而不是一路吃进堆里")
        fun rejectsOversizedRequestLine() {
            // 这就是「局域网里任何一台设备都能把进程撑爆」的那条路径：
            // 一行不带换行的数据，BufferedReader 会用无界 StringBuilder 一直接。
            var generated = false
            start { generated = true; "// pac" }

            val response = rawRequest(
                "GET /proxy.pac" + "A".repeat(PacServer.MAX_LINE_BYTES + 1_000) +
                    " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            )

            assertThat(response.statusLine).startsWith("HTTP/1.1 431")
            assertThat(generated).isFalse()
        }

        @Test
        @DisplayName("头部行数超限被拒绝")
        fun rejectsTooManyHeaders() {
            start { "// pac" }

            val headers = (1..PacServer.MAX_HEADER_LINES + 8)
                .joinToString("") { "X-Pad-$it: v\r\n" }
            val response = rawRequest("GET /proxy.pac HTTP/1.1\r\n$headers\r\n")

            assertThat(response.statusLine).startsWith("HTTP/1.1 431")
        }

        @Test
        @DisplayName("整个请求超过总字节上限被拒绝 —— 单行和行数都合规也拦得住")
        fun rejectsOversizedRequestTotal() {
            start { "// pac" }

            // 每行 600 字节、共 30 行：单行没超 8 KB，行数没超 32，但合计 18 KB
            val padding = "v".repeat(600)
            val headers = (1..30).joinToString("") { "X-Pad-$it: $padding\r\n" }
            val response = rawRequest("GET /proxy.pac HTTP/1.1\r\n$headers\r\n")

            assertThat(response.statusLine).startsWith("HTTP/1.1 431")
        }

        @Test
        @DisplayName("同时处理的连接数被闸门压在上限之内")
        fun concurrencyIsCapped() {
            // 没有闸门的话，200 条 Slowloris 连接就能把线程池占满。以前用的还是全 App
            // 共享的 Dispatchers.IO，症状会从 Room、DataStore 那边冒出来。
            //
            // 这里把每客户端份额调到全局上限之上，好让这条用例只考察全局闸门 ——
            // 测试里所有连接都来自 127.0.0.1，默认份额会先一步把它们拦下来，
            // 那样测到的就不是这里想测的东西了。每客户端份额另有专门的用例。
            server = PacServer(maxPerClient = Int.MAX_VALUE)
            val cap = PacServer.MAX_CONCURRENT_CONNECTIONS
            val gate = CountDownLatch(1)
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val entered = AtomicInteger()

            start {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { seen -> maxOf(seen, now) }
                entered.incrementAndGet()
                gate.await()
                inFlight.decrementAndGet()
                "// pac"
            }

            val clients = (1..cap + EXCESS_CLIENTS).map { openAndSend() }
            try {
                awaitUntil { entered.get() >= cap }
                // 再等一会儿，确认第 33 条确实被挡在外面而不是只是慢了一拍
                Thread.sleep(SETTLE_MS)
                assertThat(peak.get()).isAtMost(cap)
                assertThat(entered.get()).isEqualTo(cap)
            } finally {
                gate.countDown()
            }

            // 被挡住的那些是排队，不是丢弃
            clients.forEach {
                assertThat(readResponse(it).statusLine).isEqualTo("HTTP/1.1 200 OK")
            }
            // 用 served 而不是 entered 计数：多出来的那些请求会命中响应缓存，
            // 根本不会再走一遍脚本生成 —— 那正是缓存该有的样子
            assertThat(server.metrics().served).isEqualTo((cap + EXCESS_CLIENTS).toLong())
            clients.forEach { runCatching { it.close() } }
        }
    }

    @Nested
    @DisplayName("慢客户端隔离")
    inner class ClientFairness {

        @Test
        @DisplayName("单个客户端最多占住自己那一份，其余槽位留给别人")
        fun oneClientCannotStarveTheRest() {
            // 一台被入侵的智能插座开 32 条 Slowloris 就能占满全部槽位整整十秒，
            // 这期间局域网里其他所有设备取 PAC 全部超时 —— 表现为「家里突然集体
            // 上不了网」，而网关进程一切正常、日志里也只有几条限流记录
            val share = 4
            server = PacServer(maxPerClient = share)
            val gate = CountDownLatch(1)
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val entered = AtomicInteger()

            start {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { seen -> maxOf(seen, now) }
                entered.incrementAndGet()
                gate.await()
                inFlight.decrementAndGet()
                "// pac"
            }

            val clients = (1..share * 3).map { openAndSend() }
            try {
                awaitUntil { entered.get() >= share }
                // 多出来的那些必须**当场**被拒，而不是排在队里等前面的慢连接
                awaitUntil { server.metrics().clientLimited.toInt() == share * 2 }
                Thread.sleep(SETTLE_MS)
                assertThat(peak.get()).isAtMost(share)
                assertThat(entered.get()).isEqualTo(share)
            } finally {
                gate.countDown()
            }

            val statuses = clients.map { readResponse(it).statusLine }
            assertThat(statuses.count { it.startsWith("HTTP/1.1 200") }).isEqualTo(share)
            // 明确回 503 而不是静默 RST：被限流的设备的主人得有办法知道发生了什么
            assertThat(statuses.count { it.startsWith("HTTP/1.1 503") }).isEqualTo(share * 2)
            clients.forEach { runCatching { it.close() } }
        }

        @Test
        @DisplayName("连接结束后份额立刻归还")
        fun slotsAreReturnedAfterEachRequest() {
            server = PacServer(maxPerClient = 1)
            start { "// pac" }

            // 份额只有一个，但连续的请求彼此不重叠，所以每一次都该成功
            repeat(5) {
                assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").statusLine)
                    .isEqualTo("HTTP/1.1 200 OK")
            }
            assertThat(server.metrics().clientLimited).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("响应缓存")
    inner class Caching {

        @Test
        @DisplayName("同一个 host 的脚本只生成一次")
        fun reusesRenderedResponse() {
            // 一屋子设备在刚连上 Wi-Fi 的同一瞬间来取 PAC，而这份内容只取决于主机名。
            // 每次都重跑一遍脚本拼接加 UTF-8 编码，就是在最不该慢的时刻反复付 CPU
            val renders = AtomicInteger()
            start { host ->
                renders.incrementAndGet()
                "// pac for $host"
            }

            repeat(5) {
                assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 192.168.1.8:8090").body)
                    .isEqualTo("// pac for 192.168.1.8")
            }

            assertThat(renders.get()).isEqualTo(1)
            assertThat(server.metrics().cacheHits).isEqualTo(4)
        }

        @Test
        @DisplayName("不同 host 各缓存各的，绝不会串到别的网段去")
        fun cachesPerHost() {
            // 设备同时挂在 Wi-Fi 和热点上时，两个网段进来的客户端必须各拿各的地址。
            // 缓存要是不按 host 分键，其中一边会拿到一个它根本路由不到的 IP
            start { host -> "host=$host" }

            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 192.168.43.1").body)
                .isEqualTo("host=192.168.43.1")
            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 10.0.0.5").body)
                .isEqualTo("host=10.0.0.5")
            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 192.168.43.1").body)
                .isEqualTo("host=192.168.43.1")
        }

        @Test
        @DisplayName("重启会把缓存整个丢掉，配置变更后绝不发出旧脚本")
        fun restartInvalidatesCache() {
            // 客户端缓存了一份指向旧端口的 PAC 之后就一直连不上，
            // 而界面上一切正常，因为服务确实在跑
            start { "// first" }
            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").body)
                .isEqualTo("// first")

            start { "// second" }

            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").body)
                .isEqualTo("// second")
        }
    }

    @Nested
    @DisplayName("指标")
    inner class Observability {

        @Test
        @DisplayName("被服务、被拒绝的请求都记在账上")
        fun countsServedAndRejected() {
            // 没有指标的话，「局域网里有台设备在灌畸形请求」只会表现为
            // 「有些设备偶尔上不了网」，而那个现象查不出任何原因
            start { "// pac" }

            request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1")
            request("POST /proxy.pac HTTP/1.1", "Host: 127.0.0.1")
            request("GET /favicon.ico HTTP/1.1", "Host: 127.0.0.1")

            val metrics = server.metrics()
            assertThat(metrics.accepted).isEqualTo(3)
            assertThat(metrics.served).isEqualTo(1)
            assertThat(metrics.rejected).isEqualTo(2)
            assertThat(metrics.cachedHosts).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("路由与方法")
    inner class Routing {

        @Test
        @DisplayName("非 GET 方法返回 405，且不去生成脚本")
        fun rejectsNonGet() {
            var generated = false
            start { generated = true; "// pac" }

            val response = request("POST /proxy.pac HTTP/1.1", "Host: 127.0.0.1")

            assertThat(response.statusLine).isEqualTo("HTTP/1.1 405 Method Not Allowed")
            assertThat(response.body).isEmpty()
            assertThat(generated).isFalse()
        }

        @Test
        @DisplayName("其他路径返回 404")
        fun rejectsOtherPaths() {
            start { "// pac" }

            val response = request("GET /favicon.ico HTTP/1.1", "Host: 127.0.0.1")

            assertThat(response.statusLine).isEqualTo("HTTP/1.1 404 Not Found")
            assertThat(response.body).isEmpty()
        }
    }

    @Nested
    @DisplayName("生命周期")
    inner class Lifecycle {

        @Test
        @DisplayName("stop 之后端口立刻释放")
        fun stopReleasesPort() {
            start { "// pac" }
            assertThat(server.isRunning).isTrue()

            runBlocking { server.stop() }

            assertThat(server.isRunning).isFalse()
            // 端口没释放的话下一次启动会撞上「地址已被占用」
            assertThrows<IOException> { request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1") }
        }

        @Test
        @DisplayName("重复 start 不会让旧闭包再发出指向旧端口的脚本")
        fun restartRebindsSamePort() {
            // 只 cancel 不等待的话，旧协程会在新监听建立的同一瞬间发出一份旧脚本，
            // 客户端缓存了那一份之后就一直连不上，而界面上一切正常
            start { "// first" }
            start { "// second" }

            repeat(3) {
                assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").body)
                    .isEqualTo("// second")
            }
        }

        @Test
        @DisplayName("绑定失败后状态是干净的，不会对外声称自己在跑")
        fun bindFailureLeavesNothingBehind() {
            // 以前 serverSocket 是在 bind 成功之后才赋值的，失败时那个已经创建出来的
            // fd 没人关 —— 退避重试一轮能漏六个
            runBlocking { server.start(INVALID_PORT) { "// pac" } }

            assertThat(server.isRunning).isFalse()
        }
    }

    // ---------------------------------------------------------------- 测试工具

    private fun start(resolveScript: (String) -> String) =
        runBlocking { server.start(port, resolveScript) }

    private fun request(vararg lines: String): RawResponse =
        Socket().use { socket ->
            connect(socket)
            socket.getOutputStream().apply {
                write((lines.joinToString(CRLF) + CRLF + CRLF).toByteArray())
                flush()
            }
            readResponse(socket)
        }

    private fun rawRequest(payload: String): RawResponse =
        Socket().use { socket ->
            connect(socket)
            socket.getOutputStream().apply {
                write(payload.toByteArray())
                flush()
            }
            readResponse(socket)
        }

    private fun openAndSend(): Socket {
        val socket = Socket()
        connect(socket)
        socket.getOutputStream().apply {
            write("GET /proxy.pac HTTP/1.1${CRLF}Host: 127.0.0.1$CRLF$CRLF".toByteArray())
            flush()
        }
        return socket
    }

    private fun connect(socket: Socket) {
        socket.connect(InetSocketAddress(LOOPBACK, port), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
    }

    /** 服务端响应完就关连接，读到 EOF 即为完整响应。 */
    private fun readResponse(socket: Socket): RawResponse {
        val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val head = raw.substringBefore(CRLF + CRLF).split(CRLF)
        return RawResponse(
            statusLine = head.first(),
            headers = head.drop(1),
            body = raw.substringAfter(CRLF + CRLF, ""),
        )
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("等待条件成立超时")
    }

    private data class RawResponse(
        val statusLine: String,
        val headers: List<String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CRLF = "\r\n"
        const val TIMEOUT_MS = 5_000
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L

        /** 比闸门上限多开这么多条，用来确认多出来的那些是排队而不是被丢弃。 */
        const val EXCESS_CLIENTS = 8

        /** 一个必然让 bind 失败的端口，用来走绑定失败那条路径。 */
        const val INVALID_PORT = -1

        /** 借系统分配一个当前空闲的端口，避免与开发机上已占用的端口撞车。 */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
