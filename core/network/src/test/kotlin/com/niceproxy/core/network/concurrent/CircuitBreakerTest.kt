package com.niceproxy.core.network.concurrent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 熔断器要防的不是「请求失败」，而是**零耗时的失败**：内核挂掉之后每一次连接都是
 * 即刻 ECONNREFUSED，于是「失败就重试」会退化成忙等。用户能观察到的现象只有
 * 「开着代理特别费电」，没有任何报错 —— 所以这里的每一条断言都对着一个查不出原因的
 * 线上症状。
 */
class CircuitBreakerTest {

    private var now = 0L
    private val breaker = CircuitBreaker(
        failureThreshold = 3,
        baseCooldownMillis = 1_000,
        maxCooldownMillis = 8_000,
        nowMillis = { now },
    )

    @Nested
    @DisplayName("状态迁移")
    inner class Transitions {

        @Test
        @DisplayName("失败没到阈值之前照常放行")
        fun staysClosedBelowThreshold() {
            // 内核启动过程中有几百毫秒「端口在听、API 还没就绪」的窗口，
            // 一次失败就熔断会让最需要及时反馈的那一刻反而先冷却几秒
            repeat(2) {
                assertThat(breaker.tryAcquire()).isTrue()
                breaker.recordFailure()
            }

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
            assertThat(breaker.tryAcquire()).isTrue()
        }

        @Test
        @DisplayName("连续失败到阈值就打开，冷却期内一次系统调用都不发")
        fun opensAfterThreshold() {
            repeat(3) {
                breaker.tryAcquire()
                breaker.recordFailure()
            }

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
            assertThat(breaker.tryAcquire()).isFalse()
            assertThat(breaker.shortCircuitedCount).isEqualTo(1)
        }

        @Test
        @DisplayName("冷却到点转半开，且只放一次探测过去")
        fun halfOpenAdmitsExactlyOneProbe() {
            // 不限一次的话，冷却一到点，排队的十几个调用会同时被放行 ——
            // 那不是探测，那是把刚喘过气的内核再按下去一次
            openBreaker()
            now += 1_000

            assertThat(breaker.tryAcquire()).isTrue()
            assertThat(breaker.tryAcquire()).isFalse()
            assertThat(breaker.tryAcquire()).isFalse()
        }

        @Test
        @DisplayName("探测成功立刻恢复，计数与冷却一起归零")
        fun successClosesBreaker() {
            openBreaker()
            now += 1_000
            breaker.tryAcquire()

            breaker.recordSuccess()

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
            repeat(2) {
                breaker.tryAcquire()
                breaker.recordFailure()
            }
            // 冷却归零了，所以这两次失败不该重新触发熔断
            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        }

        @Test
        @DisplayName("探测失败让冷却翻倍，直到封顶")
        fun failedProbeDoublesCooldown() {
            openBreaker()

            // 1s → 2s → 4s → 8s → 8s（封顶）
            val expected = listOf(2_000L, 4_000L, 8_000L, 8_000L)
            var elapsed = 1_000L
            expected.forEach { nextCooldown ->
                now += elapsed
                assertThat(breaker.tryAcquire()).isTrue()
                breaker.recordFailure()

                // 差一毫秒还不该放行
                now += nextCooldown - 1
                assertThat(breaker.tryAcquire()).isFalse()
                now += 1
                elapsed = 0
            }
        }

        @Test
        @DisplayName("取消只归还探测许可，既不算成功也不算失败")
        fun cancellationReleasesProbeOnly() {
            // 少了这一步，「用户退出监控页」这种再正常不过的取消会把许可永久扣住，
            // 熔断器从此回不到关闭状态：内核明明好了，界面却一直说连不上
            openBreaker()
            now += 1_000
            assertThat(breaker.tryAcquire()).isTrue()

            breaker.recordCancelled()

            assertThat(breaker.tryAcquire()).isTrue()
            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.HALF_OPEN)
        }

        @Test
        @DisplayName("reset 让熔断器立刻可用，不必等冷却走完")
        fun resetSkipsCooldown() {
            openBreaker()

            breaker.reset()

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
            assertThat(breaker.tryAcquire()).isTrue()
        }
    }

    @Nested
    @DisplayName("withBreaker")
    inner class Wrapper {

        @Test
        @DisplayName("熔断打开时直接本地失败，block 一次都不执行")
        fun shortCircuitsWithoutInvokingBlock() = runTest {
            openBreaker()
            val invocations = AtomicInteger()

            val result = breaker.withBreaker { invocations.incrementAndGet() }

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(CircuitOpenException::class.java)
            assertThat(invocations.get()).isEqualTo(0)
        }

        @Test
        @DisplayName("能解析出来的业务错误不算「对端不在」，不该拖累熔断")
        fun businessErrorsDoNotTripBreaker() = runTest {
            // 最典型的是 /delay：节点不通时内核回一个错误状态码，
            // 而那是**被测节点**的问题。算进熔断等于让一个坏节点连累整个控制面
            val isConnectivityFailure = { cause: Throwable -> cause is IOException }

            repeat(10) {
                breaker.withBreaker<Unit>(isConnectivityFailure) {
                    throw IllegalStateException("HTTP 404")
                }
            }

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        }

        @Test
        @DisplayName("连不上会计入熔断，到阈值后转为本地失败")
        fun connectivityFailuresTripBreaker() = runTest {
            repeat(3) {
                breaker.withBreaker<Unit> { throw IOException("ECONNREFUSED") }
            }

            val result = breaker.withBreaker { "should not run" }
            assertThat(result.exceptionOrNull()).isInstanceOf(CircuitOpenException::class.java)
        }

        @Test
        @DisplayName("取消原样抛出，不会被吞成一个失败的 Result")
        fun rethrowsCancellation() = runTest {
            // runCatching 会把 CancellationException 一起吞掉，那样调用方的作用域
            // 就取消不干净了 —— 这是 runCatching 用在挂起函数里最常见的坑
            assertThrows<CancellationException> {
                breaker.withBreaker<Unit> { throw CancellationException("collector gone") }
            }
        }
    }

    @Nested
    @DisplayName("withProbe")
    inner class Probe {

        @Test
        @DisplayName("探活永远真的发出去 —— 拿熔断挡探活是循环论证")
        fun probeIsNeverShortCircuited() = runTest {
            // 冷却期间探活一律失败，于是冷却永远续下去，上层看到的是
            // 「内核明明起来了，看门狗还在反复重启它」
            openBreaker()
            val invoked = AtomicInteger()

            val result = breaker.withProbe { invoked.incrementAndGet() }

            assertThat(invoked.get()).isEqualTo(1)
            assertThat(result.isSuccess).isTrue()
        }

        @Test
        @DisplayName("探活成功把熔断器整个放回关闭状态")
        fun successfulProbeClosesBreaker() = runTest {
            openBreaker()

            breaker.withProbe { "v1.13" }

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.CLOSED)
        }

        @Test
        @DisplayName("探活失败照常计入熔断")
        fun failedProbeCountsAsFailure() = runTest {
            repeat(3) {
                breaker.withProbe<Unit> { throw IOException("ECONNREFUSED") }
            }

            assertThat(breaker.state).isEqualTo(CircuitBreaker.State.OPEN)
        }
    }

    @Test
    @DisplayName("多协程并发争用时，半开期间也只有一个探测能出去")
    fun concurrentProbesAreSerialized() = runTest {
        openBreaker()
        now += 1_000
        val gate = CompletableDeferred<Unit>()
        val admitted = AtomicInteger()

        val callers = (1..8).map {
            async {
                breaker.withBreaker {
                    admitted.incrementAndGet()
                    gate.await()
                }
            }
        }
        // 让所有调用都跑到熔断器这一步
        withTimeoutOrNull(100) { callers.first().await() }

        assertThat(admitted.get()).isEqualTo(1)
        gate.complete(Unit)
        callers.forEach { it.await() }
    }

    private fun openBreaker() {
        repeat(3) {
            breaker.tryAcquire()
            breaker.recordFailure()
        }
        check(breaker.state == CircuitBreaker.State.OPEN)
    }
}
