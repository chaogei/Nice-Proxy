package com.niceproxy.core.network.clash

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.network.concurrent.CircuitBreaker
import com.niceproxy.core.network.concurrent.CircuitOpenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 控制面的护栏测试。
 *
 * 这里全部用真实的 HTTP 服务端而不是打桩：要验证的是并发闸门、熔断和「哪种失败算
 * 对端不在」这三件事怎么配合，而它们的接缝正好落在 OkHttp 的调用路径上 ——
 * 把 OkHttp 换成假的，剩下的就只是在测我自己写的 if。
 */
class ClashApiClientTest {

    private val client = ClashApiClient()
    private var server: MockWebServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    @Nested
    @DisplayName("基本约定")
    inner class Basics {

        @Test
        @DisplayName("每个请求都带上 Bearer 密钥")
        fun sendsAuthorizationHeader() = runBlocking {
            val seen = java.util.concurrent.atomic.AtomicReference<String>()
            val server = start { request ->
                seen.set(request.headers["Authorization"])
                MockResponse.Builder().body("""{"version":"1.13"}""").build()
            }
            val settings = settingsFor(server, secret = "s3cr3t")

            assertThat(client.version(settings).getOrNull()?.version).isEqualTo("1.13")
            assertThat(seen.get()).isEqualTo("Bearer s3cr3t")
        }

        @Test
        @DisplayName("非 2xx 变成带状态码的失败，而不是被当成空响应解析")
        fun mapsHttpErrors() = runBlocking {
            val server = start { MockResponse.Builder().code(404).build() }

            val failure = client.proxies(settingsFor(server)).exceptionOrNull()

            assertThat(failure).isInstanceOf(ClashApiHttpException::class.java)
            assertThat((failure as ClashApiHttpException).code).isEqualTo(404)
        }
    }

    @Nested
    @DisplayName("并发闸门")
    inner class Throttling {

        @Test
        @DisplayName("同时在飞的请求被压在上限之内")
        fun capsInFlightRequests() = runBlocking {
            // 每个 /delay 在内核侧都是一次真实出站连接。不限流就是自己把自己的代理
            // 打满，而测出来的延迟会比实际值高一大截 —— 用户据此挑出来的「最快节点」
            // 其实只是运气好那个。
            //
            // 顺带守住 OkHttp 那道默认 maxRequestsPerHost=5：所有 Clash API 请求都打向
            // 同一个 host，忘了调它的话上层并发度根本不生效。
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val server = start {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { seen -> maxOf(seen, now) }
                Thread.sleep(HOLD_MS)
                inFlight.decrementAndGet()
                MockResponse.Builder().body("""{"delay":42}""").build()
            }
            val settings = settingsFor(server)

            withContext(Dispatchers.IO) {
                (1..CONCURRENT_CALLERS).map {
                    async { client.testDelay(settings, "node-$it", "http://example.com") }
                }.awaitAll()
            }

            assertThat(peak.get()).isAtMost(ClashApiClient.MAX_CONCURRENT_REST)
            // 被挡住的是排队而不是丢弃：每一个调用最终都拿到了结果
            assertThat(server.requestCount).isEqualTo(CONCURRENT_CALLERS)
        }
    }

    @Nested
    @DisplayName("熔断")
    inner class Breaking {

        @Test
        @DisplayName("连不上内核几次之后转本地失败，不再发系统调用")
        fun opensAfterRepeatedConnectionFailures() = runBlocking {
            // 内核挂掉后每次连接都是**零耗时**的 ECONNREFUSED，「失败就重试」于是
            // 退化成忙等。用户能观察到的只有「开着代理特别费电」，没有任何报错
            val settings = ClashApiSettings(port = freePort(), secret = "x")

            repeat(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD) {
                assertThat(client.proxies(settings).isFailure).isTrue()
            }

            val shortCircuited = client.proxies(settings)
            assertThat(shortCircuited.exceptionOrNull())
                .isInstanceOf(CircuitOpenException::class.java)
            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.OPEN)
            assertThat(client.metrics().restShortCircuited).isEqualTo(1)
        }

        @Test
        @DisplayName("HTTP 错误不算「对端不在」，一个坏节点不该连累整个控制面")
        fun httpErrorsDoNotOpenBreaker() = runBlocking {
            // /delay 在节点不通时返回错误状态码，那是**被测节点**的问题。
            // 把它算进熔断，一批坏节点就能让切换、关连接这些操作全部失灵
            val server = start { MockResponse.Builder().code(504).build() }
            val settings = settingsFor(server)

            repeat(10) { client.testDelay(settings, "dead-node", "http://example.com") }

            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.CLOSED)
            assertThat(client.metrics().restShortCircuited).isEqualTo(0)
        }

        @Test
        @DisplayName("探活不受熔断阻挡，成功之后整个控制面立刻恢复")
        fun probeBypassesBreakerAndRecovers() = runBlocking {
            // 拿熔断挡探活是循环论证：冷却期间探活必然失败，冷却于是永远续下去，
            // 而看门狗看到的是「内核死了」—— 它会去重启一个其实很健康的内核
            val port = freePort()
            val settings = ClashApiSettings(port = port, secret = "x")

            repeat(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD) { client.proxies(settings) }
            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.OPEN)

            // 内核回来了
            val revived = start(port) { MockResponse.Builder().body("""{"version":"1.13"}""").build() }

            assertThat(client.version(settings).isSuccess).isTrue()
            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.CLOSED)
            // 熔断已经放开，普通请求不再被本地挡掉
            assertThat(revived.requestCount).isEqualTo(1)
            assertThat(client.proxies(settings).isFailure).isFalse()
        }

        @Test
        @DisplayName("noteCoreAlive 能立刻放开熔断，用户不必对着可点的按钮吃闭门羹")
        fun noteCoreAliveResetsBreaker() = runBlocking {
            val settings = ClashApiSettings(port = freePort(), secret = "x")
            repeat(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD) { client.proxies(settings) }

            client.noteCoreAlive()

            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.CLOSED)
        }
    }

    // ---------------------------------------------------------------- 测试工具

    private fun start(port: Int = 0, respond: (RecordedRequest) -> MockResponse): MockWebServer {
        val created = MockWebServer()
        created.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        if (port == 0) created.start() else created.start(port)
        server = created
        return created
    }

    private fun settingsFor(server: MockWebServer, secret: String = "x") =
        ClashApiSettings(port = server.port, secret = secret)

    private companion object {
        const val HOLD_MS = 60L
        const val CONCURRENT_CALLERS = 40

        /** 借系统分配一个当前空闲的端口。用完即释放，对它的连接会被立刻拒绝。 */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
