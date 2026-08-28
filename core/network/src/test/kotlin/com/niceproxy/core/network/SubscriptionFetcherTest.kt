package com.niceproxy.core.network

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.network.concurrent.ExponentialBackoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 订阅拉取是**后台周期任务**，它的失败没有人在看：一次瞬时抖动如果直接落成
 * 「更新失败」，用户下次打开应用看到的是一排红色感叹号，而其实只要过两秒再问
 * 一次就好了。反过来，对一个已经过期的链接反复重试，机场那边看到的是一台在
 * 暴力尝试的客户端。下面测的就是这条界线画在哪。
 */
class SubscriptionFetcherTest {

    private val fetcher = SubscriptionFetcher(Dispatchers.IO)
    private val server = MockWebServer()

    /** 退避基准压到 1 ms：这里要验证的是重试**次数与条件**，不是等待时长。 */
    private val fastRetry = SubscriptionFetcher.RetryPolicy(
        maxRetries = 2,
        backoff = ExponentialBackoff(baseMillis = 1, maxMillis = 4),
    )

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Nested
    @DisplayName("正常拉取")
    inner class HappyPath {

        @Test
        @DisplayName("返回体、流量信息头与建议名都被带出来")
        fun parsesResponseMetadata() = runBlocking {
            respond {
                MockResponse.Builder()
                    .body("vmess://node")
                    .addHeader("subscription-userinfo", "upload=1; download=2; total=3")
                    .addHeader("content-disposition", """attachment; filename="my-airport.yaml"""")
                    .build()
            }

            val response = fetcher.fetch(url()).getOrThrow()

            assertThat(response.body).isEqualTo("vmess://node")
            assertThat(response.userInfoHeader).contains("total=3")
            assertThat(response.suggestedName).isEqualTo("my-airport.yaml")
        }

        @Test
        @DisplayName("profile-title 优先于 content-disposition")
        fun prefersProfileTitle() = runBlocking {
            respond {
                MockResponse.Builder()
                    .body("x")
                    .addHeader("profile-title", "preferred")
                    .addHeader("content-disposition", """attachment; filename="fallback.yaml"""")
                    .build()
            }

            assertThat(fetcher.fetch(url()).getOrThrow().suggestedName).isEqualTo("preferred")
        }

        @Test
        @DisplayName("默认伪装成通用客户端；调用方给了 UA 就用它的")
        fun sendsUserAgent() = runBlocking {
            val seen = java.util.concurrent.atomic.AtomicReference<String>()
            respond { request ->
                seen.set(request.headers["User-Agent"])
                MockResponse.Builder().body("x").build()
            }

            fetcher.fetch(url(), userAgent = "clash-verge/1.0")

            assertThat(seen.get()).isEqualTo("clash-verge/1.0")
        }
    }

    @Nested
    @DisplayName("重试的边界")
    inner class Retrying {

        @Test
        @DisplayName("5xx 会退避重试，恢复之后照常成功")
        fun retriesServerErrors() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                if (attempts.incrementAndGet() < 3) {
                    MockResponse.Builder().code(502).build()
                } else {
                    MockResponse.Builder().body("ok").build()
                }
            }

            val response = fetcher.fetch(url(), retry = fastRetry)

            assertThat(response.getOrNull()?.body).isEqualTo("ok")
            assertThat(attempts.get()).isEqualTo(3)
        }

        @Test
        @DisplayName("429 也算「等会儿再来」")
        fun retriesRateLimits() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                if (attempts.incrementAndGet() == 1) {
                    MockResponse.Builder().code(429).build()
                } else {
                    MockResponse.Builder().body("ok").build()
                }
            }

            assertThat(fetcher.fetch(url(), retry = fastRetry).isSuccess).isTrue()
            assertThat(attempts.get()).isEqualTo(2)
        }

        @Test
        @DisplayName("404 一次就放弃 —— 重试多少次结果都一样，只会白等")
        fun doesNotRetryClientErrors() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                attempts.incrementAndGet()
                MockResponse.Builder().code(404).build()
            }

            val result = fetcher.fetch(url(), retry = fastRetry)

            assertThat(result.isFailure).isTrue()
            assertThat(attempts.get()).isEqualTo(1)
        }

        @Test
        @DisplayName("重试次数封顶，不会无限往下打")
        fun stopsAfterMaxRetries() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                attempts.incrementAndGet()
                MockResponse.Builder().code(503).build()
            }

            val result = fetcher.fetch(url(), retry = fastRetry)

            assertThat(result.isFailure).isTrue()
            // 首次 + 两次重试
            assertThat(attempts.get()).isEqualTo(3)
        }

        @Test
        @DisplayName("前台手动触发时可以完全关掉重试，用户不必对着转圈干等")
        fun honoursNoRetryPolicy() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                attempts.incrementAndGet()
                MockResponse.Builder().code(503).build()
            }

            fetcher.fetch(url(), retry = SubscriptionFetcher.RetryPolicy.NONE)

            assertThat(attempts.get()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("响应体上限")
    inner class BodyCap {

        @Test
        @DisplayName("声明长度超限直接拒绝，一个字节都不读进堆里")
        fun rejectsOversizedDeclaredLength() = runBlocking {
            // body.string() 会把响应读成 UTF-16 的 String：1 GB 的响应要占约 2 GB 内存。
            // 而订阅自动更新是周期任务，会一遍遍地把网关杀掉
            respond {
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Length", (SubscriptionFetcher.MAX_BODY_BYTES + 1).toString())
                    .build()
            }

            val failure = fetcher.fetch(url(), retry = fastRetry).exceptionOrNull()

            assertThat(failure).isInstanceOf(SubscriptionFetcher.OversizedBodyException::class.java)
        }

        @Test
        @DisplayName("超限不重试：再问一次还是那么大")
        fun doesNotRetryOversizedBody() = runBlocking {
            val attempts = AtomicInteger()
            respond {
                attempts.incrementAndGet()
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Length", (SubscriptionFetcher.MAX_BODY_BYTES + 1).toString())
                    .build()
            }

            fetcher.fetch(url(), retry = fastRetry)

            assertThat(attempts.get()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("批量更新")
    inner class Batch {

        @Test
        @DisplayName("并发有上限，不会同时对机场开三十条连接")
        fun capsConcurrency() = runBlocking {
            // 上限有两个理由：本机的 TLS 握手内存峰值，以及机场普遍按 IP 限流 ——
            // 并发打过去换来一串 429，反而比串行更慢
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            respond {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { seen -> maxOf(seen, now) }
                Thread.sleep(HOLD_MS)
                inFlight.decrementAndGet()
                MockResponse.Builder().body("ok").build()
            }
            val requests = (1..24).map { SubscriptionRequest("${url()}?i=$it") }

            fetcher.fetchAll(requests, concurrency = 3, retry = SubscriptionFetcher.RetryPolicy.NONE)

            assertThat(peak.get()).isAtMost(3)
        }

        @Test
        @DisplayName("结果按输入顺序返回，调用方能直接与订阅列表对齐")
        fun preservesOrder() = runBlocking {
            respond { request ->
                MockResponse.Builder().body(request.url.queryParameter("i").orEmpty()).build()
            }
            val requests = (1..12).map { SubscriptionRequest("${url()}?i=$it") }

            val results = fetcher.fetchAll(requests, retry = SubscriptionFetcher.RetryPolicy.NONE)

            assertThat(results.map { it.getOrNull()?.body }).isEqualTo((1..12).map { it.toString() })
        }

        @Test
        @DisplayName("单条失败不影响其余，每条各自拿到自己的结局")
        fun isolatesFailures() = runBlocking {
            respond { request ->
                val index = request.url.queryParameter("i")?.toInt() ?: 0
                if (index % 2 == 0) {
                    MockResponse.Builder().code(404).build()
                } else {
                    MockResponse.Builder().body("ok-$index").build()
                }
            }
            val requests = (1..8).map { SubscriptionRequest("${url()}?i=$it") }

            val results = fetcher.fetchAll(requests, retry = SubscriptionFetcher.RetryPolicy.NONE)

            assertThat(results.count { it.isSuccess }).isEqualTo(4)
            assertThat(results.count { it.isFailure }).isEqualTo(4)
            assertThat(results[0].getOrNull()?.body).isEqualTo("ok-1")
            assertThat(results[1].exceptionOrNull()).isInstanceOf(IOException::class.java)
        }

        @Test
        @DisplayName("空批次返回空结果")
        fun handlesEmptyBatch() = runBlocking {
            assertThat(fetcher.fetchAll(emptyList())).isEmpty()
        }
    }

    // ---------------------------------------------------------------- 测试工具

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
        server.start()
    }

    private fun url(): String = server.url("/sub").toString()

    private companion object {
        const val HOLD_MS = 40L
    }
}
