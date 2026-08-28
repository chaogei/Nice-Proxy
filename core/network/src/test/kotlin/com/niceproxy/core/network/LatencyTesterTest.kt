package com.niceproxy.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * 测速这条路径上最容易出错的不是「能不能连上」，而是**并发形状**和**耗时归属**：
 * 前者决定了点一次「全部测速」会不会把手机拖垮，后者决定了用户看到的那个数字
 * 到底在说什么。两者出错都不会报任何异常。
 */
class LatencyTesterTest {

    private val tester = LatencyTester(Dispatchers.IO)

    /**
     * 一个真实在监听的本机端口，用来做「一定能握上手」的对照。
     *
     * 必须真的把连接 accept 掉。只 bind 不 accept 的话，超过 backlog 的那些 SYN 会被
     * 内核直接丢掉，测试里几百个并发连接就会一个个卡到 3 秒超时 —— 那不是在测并发度，
     * 是在测超时。
     */
    private val listener = ServerSocket(0)
    private val acceptor = Thread {
        while (!listener.isClosed) {
            runCatching { listener.accept().close() }.onFailure { return@Thread }
        }
    }.apply { isDaemon = true; start() }

    @AfterEach
    fun tearDown() {
        listener.close()
        acceptor.interrupt()
    }

    @Nested
    @DisplayName("并发形状")
    inner class Concurrency {

        @Test
        @DisplayName("同时在测的节点数被压在并发度之内")
        fun capsConcurrency() = runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val targets = (1..120).map { LatencyTester.Target("id-$it", LOOPBACK, listener.localPort) }

            // 必须在真实的多线程派发器上跑：runBlocking 只有一条线程，
            // worker 会被排成串行，那样任何并发度都能「通过」
            withContext(Dispatchers.IO) {
                tester.probeAll(targets, concurrency = 6, resolver = LOOPBACK_RESOLVER) { _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { seen -> maxOf(seen, now) }
                    Thread.sleep(2)
                    inFlight.decrementAndGet()
                }
            }

            assertThat(peak.get()).isAtMost(6)
            assertThat(peak.get()).isGreaterThan(1)
        }

        @Test
        @DisplayName("并发度被夹在合理区间，传 0 或者一万都不会失控")
        fun clampsConcurrency() = runBlocking {
            val peak = AtomicInteger()
            val inFlight = AtomicInteger()
            val targets = (1..200).map { LatencyTester.Target("id-$it", LOOPBACK, listener.localPort) }

            withContext(Dispatchers.IO) {
                tester.probeAll(targets, concurrency = 10_000, resolver = LOOPBACK_RESOLVER) { _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { seen -> maxOf(seen, now) }
                    Thread.sleep(2)
                    inFlight.decrementAndGet()
                }
            }

            assertThat(peak.get()).isAtMost(LatencyTester.MAX_CONCURRENCY)
        }

        @Test
        @DisplayName("每个节点恰好回调一次，id 一一对应")
        fun reportsEveryTargetOnce() = runBlocking {
            val targets = (1..120).map { LatencyTester.Target("id-$it", LOOPBACK, listener.localPort) }
            val seen = ConcurrentHashMap<String, Int>()

            tester.tcpingAll(targets, resolver = LOOPBACK_RESOLVER) { id, _ ->
                seen.merge(id, 1, Int::plus)
            }

            assertThat(seen.keys).containsExactlyElementsIn(targets.map { it.id })
            assertThat(seen.values.toSet()).containsExactly(1)
        }

        @Test
        @DisplayName("空列表直接返回，不去建 worker")
        fun handlesEmptyInput() = runBlocking {
            var called = false
            tester.tcpingAll(emptyList()) { _, _ -> called = true }
            assertThat(called).isFalse()
        }
    }

    @Nested
    @DisplayName("DNS 与握手的分账")
    inner class Timing {

        @Test
        @DisplayName("解析耗时不计入延迟数字")
        fun dnsTimeIsExcludedFromLatency() = runBlocking {
            // 同一个机场的几十个节点共用一个域名，第一个被测的那个会因为背了整段
            // 解析时间而显得特别慢，用户于是避开它 —— 完全是误导。
            // 而且解析慢要换 DNS、握手慢要换节点，对策完全相反
            val slowResolver = LatencyTester.HostResolver {
                Thread.sleep(DNS_DELAY_MS)
                InetAddress.getByName(LOOPBACK)
            }

            val probe = tester.probe(LOOPBACK, listener.localPort, timeoutMs = 5_000, resolver = slowResolver)

            assertThat(probe.isSuccess).isTrue()
            assertThat(probe.dnsMillis).isAtLeast(DNS_DELAY_MS.toInt())
            // 本机握手是微秒级的，绝不该把那 300 ms 背在身上
            assertThat(probe.latencyMs!!).isLessThan(DNS_DELAY_MS.toInt())
        }

        @Test
        @DisplayName("同一批里重复的主机只解析一次")
        fun deduplicatesResolutionWithinBatch() = runBlocking {
            // 机场常见几十个节点共用一个域名。每个都独立解析一遍不只是浪费，
            // 还会平白放大对 DNS 服务器的压力，在部分 ROM 上直接触发限速
            val resolutions = AtomicInteger()
            val resolver = LatencyTester.HostResolver {
                resolutions.incrementAndGet()
                InetAddress.getByName(LOOPBACK)
            }
            val targets = (1..50).map { LatencyTester.Target("id-$it", "airport.example", listener.localPort) }

            tester.probeAll(targets, resolver = resolver) { _, _ -> }

            assertThat(resolutions.get()).isEqualTo(1)
        }

        @Test
        @DisplayName("解析失败也只吃一次，不会让每个节点各等一次 DNS 超时")
        fun cachesResolutionFailureWithinBatch() = runBlocking {
            val resolutions = AtomicInteger()
            val resolver = LatencyTester.HostResolver {
                resolutions.incrementAndGet()
                throw UnknownHostException("gone.example")
            }
            val targets = (1..30).map { LatencyTester.Target("id-$it", "gone.example", 443) }
            val failures = AtomicInteger()

            tester.probeAll(targets, resolver = resolver) { _, probe ->
                if (probe.failure == LatencyTester.Failure.DNS) failures.incrementAndGet()
            }

            assertThat(resolutions.get()).isEqualTo(1)
            assertThat(failures.get()).isEqualTo(30)
        }
    }

    @Nested
    @DisplayName("失败分类")
    inner class Failures {

        @Test
        @DisplayName("解析不出来报 DNS，而不是笼统的超时")
        fun reportsDnsFailure() = runBlocking {
            val resolver = LatencyTester.HostResolver { throw UnknownHostException("nope") }

            val probe = tester.probe("nope.example", 443, resolver = resolver)

            assertThat(probe.latencyMs).isNull()
            assertThat(probe.failure).isEqualTo(LatencyTester.Failure.DNS)
        }

        @Test
        @DisplayName("端口没开报 CONNECT")
        fun reportsConnectFailure() = runBlocking {
            val closedPort = ServerSocket(0).use { it.localPort }

            val probe = tester.probe(LOOPBACK, closedPort, resolver = LOOPBACK_RESOLVER)

            assertThat(probe.latencyMs).isNull()
            assertThat(probe.failure).isEqualTo(LatencyTester.Failure.CONNECT)
        }

        @Test
        @DisplayName("成功时至少记 1 ms，本机节点不会显示成 0")
        fun neverReportsZero() = runBlocking {
            val probe = tester.probe(LOOPBACK, listener.localPort, resolver = LOOPBACK_RESOLVER)

            assertThat(probe.latencyMs).isAtLeast(1)
        }

        @Test
        @DisplayName("tcping 与 probe 对同一次测速给出一致的结论")
        fun tcpingMatchesProbe() = runBlocking {
            assertThat(tester.tcping(LOOPBACK, listener.localPort)).isNotNull()
            val closedPort = ServerSocket(0).use { it.localPort }
            assertThat(tester.tcping(LOOPBACK, closedPort)).isNull()
        }
    }

    @Nested
    @DisplayName("出口网络绑定")
    inner class Binding {

        @Test
        @DisplayName("测速走调用方指定的 SocketFactory，而不是系统默认网络")
        fun usesInjectedSocketFactory() = runBlocking {
            // 用户设了「只走 Wi-Fi」，而测速却跑在默认网络上 —— 测出来的延迟和他实际
            // 体验到的根本不是同一条链路，据此挑节点等于抛硬币
            val created = AtomicInteger()
            val factory = object : SocketFactory() {
                override fun createSocket(): Socket {
                    created.incrementAndGet()
                    return Socket()
                }

                override fun createSocket(host: String?, port: Int) = createSocket()
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
                    createSocket()

                override fun createSocket(host: InetAddress?, port: Int) = createSocket()
                override fun createSocket(
                    address: InetAddress?,
                    port: Int,
                    localAddress: InetAddress?,
                    localPort: Int,
                ) = createSocket()
            }

            tester.bindTo(factory)
            try {
                tester.probe(LOOPBACK, listener.localPort, resolver = LOOPBACK_RESOLVER)
                assertThat(created.get()).isEqualTo(1)
            } finally {
                tester.bindTo(null)
            }
        }

        @Test
        @DisplayName("传 null 回到系统默认，不会把测速永久钉在一张已经消失的网卡上")
        fun clearingBindingRestoresDefault() = runBlocking {
            val factory = object : SocketFactory() {
                override fun createSocket(): Socket = error("不该再被用到")
                override fun createSocket(host: String?, port: Int) = createSocket()
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
                    createSocket()

                override fun createSocket(host: InetAddress?, port: Int) = createSocket()
                override fun createSocket(
                    address: InetAddress?,
                    port: Int,
                    localAddress: InetAddress?,
                    localPort: Int,
                ) = createSocket()
            }
            tester.bindTo(factory)

            tester.bindTo(null)

            assertThat(tester.probe(LOOPBACK, listener.localPort, resolver = LOOPBACK_RESOLVER).isSuccess)
                .isTrue()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DNS_DELAY_MS = 300L

        val LOOPBACK_RESOLVER = LatencyTester.HostResolver { InetAddress.getByName(LOOPBACK) }
    }
}
