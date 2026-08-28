package com.niceproxy.core.network.clash

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.network.concurrent.OverflowPolicy
import com.niceproxy.core.network.concurrent.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 这条流水线上的三件事都无法用真实 WebSocket 稳定复现：解析跑在哪条线程、
 * 队列满了丢的是哪一帧、服务端静默关闭之后消费者会不会永久挂起。所以传输层在这里
 * 被替换成一个由测试驱动的假实现，其余部分是生产代码原样。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClashFrameStreamTest {

    private val parseExecutor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable -> Thread(runnable, PARSE_THREAD) },
    )
    private val parseDispatcher = parseExecutor.asCoroutineDispatcher()

    /**
     * 用例自己的作用域。用 SupervisorJob 是必须的：好几个用例要断言「这条流以异常
     * 结束」，而挂在 `runBlocking` 下的 `async` 会在异常发生的一刻直接把测试体本身
     * 打断，断言根本轮不到执行。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        parseExecutor.shutdownNow()
    }

    /** 由测试驱动的传输层。`connect` 被调用时把 sink 交出来，让用例自己推帧。 */
    private class FakeTransport {
        private val ready = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)

        @Volatile
        private var sink: FrameSink? = null

        fun connect(target: FrameSink): FrameSubscription {
            sink = target
            ready.countDown()
            return FrameSubscription { cancelled.set(true) }
        }

        fun awaitReady(): FrameSink {
            check(ready.await(5, TimeUnit.SECONDS)) { "传输层始终没有被连接" }
            return checkNotNull(sink)
        }
    }

    @Nested
    @DisplayName("线程归属")
    inner class Threading {

        @Test
        @DisplayName("解析不发生在传输层回调线程上，回调立刻返回")
        fun parsingLeavesTheCallbackThread() = runBlocking {
            // OkHttp 的 WebSocket 读线程由所有连接共享。在上面解一次 /connections 的
            // 几十 KB 快照，同一批线程上的 /traffic、/logs 会一起被推迟 —— 症状是
            // 「日志偶尔一卡一卡的」，而没人会想到罪魁祸首是另一个页面的连接列表。
            val parseThread = java.util.concurrent.atomic.AtomicReference<String>()
            val transport = FakeTransport()
            val flow = stream(capacity = 4, policy = OverflowPolicy.DROP_OLDEST, transport = transport) {
                parseThread.set(Thread.currentThread().name)
                // 故意解析得很慢：回调线程绝不能陪着一起等
                Thread.sleep(SLOW_PARSE_MS)
                it
            }

            val collected = collectFirst(flow)
            val sink = transport.awaitReady()

            val callbackThread = Thread(
                { sink.onFrame("frame") },
                TRANSPORT_THREAD,
            )
            val startedAt = System.nanoTime()
            callbackThread.start()
            callbackThread.join(TimeUnit.SECONDS.toMillis(5))
            val callbackCostMs = (System.nanoTime() - startedAt) / 1_000_000

            assertThat(withTimeout(5_000) { collected.await() }).isEqualTo("frame")
            // 协程调试模式会给线程名追加 " @coroutine#N"，所以只比对前缀
            assertThat(parseThread.get()).startsWith(PARSE_THREAD)
            // 回调必须在解析完成之前就已经返回
            assertThat(callbackCostMs).isLessThan(SLOW_PARSE_MS)
        }
    }

    @Nested
    @DisplayName("背压")
    inner class BackPressure {

        @Test
        @DisplayName("latest-value 流在收集方变慢时合帧，最新那一帧一定送达")
        fun coalescesForLatestValueStreams() {
            // 积压的旧流量帧一旦被显示出来，用户看到的是一个**倒退**的数字，
            // 比丢掉它糟糕得多
            val transport = FakeTransport()
            val flow = stream(1, OverflowPolicy.LATEST_WINS, transport) { it.toInt() }
            val collector = SlowCollector(flow)

            collector.start()
            val sink = transport.awaitReady()
            sink.onFrame("1")
            collector.awaitFirst()

            // 收集方此刻被卡住，这一整串突发只有最新的那一帧有价值
            (2..BURST).forEach { sink.onFrame(it.toString()) }
            collector.release()
            collector.awaitValue(BURST)

            val seen = collector.seen()
            assertThat(seen.first()).isEqualTo(1)
            assertThat(seen.last()).isEqualTo(BURST)
            // 中间那些必须真的被丢掉了，而不是排在某个隐形缓冲里慢慢放出来
            assertThat(seen).doesNotContain(BURST / 2)
            assertThat(seen.size).isAtMost(MAX_COALESCED_DELIVERIES)
            collector.stop()
        }

        @Test
        @DisplayName("日志流保留最近一段，丢的是最旧的那些")
        fun keepsRecentWindowForLogs() {
            // 日志是独立事件，合帧会把中间内容整段抹掉；
            // 而不限容的话，用户顺手把级别调成 debug 就是一条稳定的 OOM 路径
            val transport = FakeTransport()
            val flow = stream(RING, OverflowPolicy.DROP_OLDEST, transport) { it.toInt() }
            val collector = SlowCollector(flow)

            collector.start()
            val sink = transport.awaitReady()
            sink.onFrame("1")
            collector.awaitFirst()

            (2..BURST).forEach { sink.onFrame(it.toString()) }
            collector.release()
            collector.awaitValue(BURST)

            val seen = collector.seen()
            assertThat(seen.takeLast(RING)).isEqualTo((BURST - RING + 1..BURST).toList())
            assertThat(seen).doesNotContain(BURST / 2)
            collector.stop()
        }

        @Test
        @DisplayName("溢出会被如实计数，过载在指标上是看得见的")
        fun reportsOverflowToRecorder() {
            val transport = FakeTransport()
            val coalesced = AtomicInteger()
            val received = AtomicInteger()
            val recorder = object : FrameStreamRecorder {
                override fun onSendResult(result: SendResult) {
                    received.incrementAndGet()
                    if (result == SendResult.COALESCED) coalesced.incrementAndGet()
                }

                override fun onParseFailure() = Unit
            }
            val flow = stream(1, OverflowPolicy.LATEST_WINS, transport, recorder) { it.toInt() }
            val collector = SlowCollector(flow)

            collector.start()
            val sink = transport.awaitReady()
            sink.onFrame("1")
            collector.awaitFirst()
            (2..BURST).forEach { sink.onFrame(it.toString()) }

            assertThat(received.get()).isEqualTo(BURST)
            assertThat(coalesced.get()).isGreaterThan(0)
            collector.release()
            collector.stop()
        }
    }

    @Nested
    @DisplayName("终止与清理")
    inner class Termination {

        @Test
        @DisplayName("传输层结束时消费者立刻收到异常，而不是永久挂起")
        fun terminationWakesIdleConsumer() = runBlocking {
            // 这是最凶的一种缺陷：服务端优雅关闭之后不会再有帧，消费者阻塞在
            // 「等下一帧」上，流既不完成也不失败 —— 流量数字冻结、日志停止滚动、
            // TCP 半开，而界面上一切正常
            val transport = FakeTransport()
            val flow = stream(4, OverflowPolicy.DROP_OLDEST, transport) { it }
            val collected = collectFirst(flow)
            val sink = transport.awaitReady()

            sink.onTerminated(IOException("内核停止"))

            val failure = assertThrows<IOException> {
                runBlocking { withTimeout(5_000) { collected.await() } }
            }
            assertThat(failure).hasMessageThat().contains("内核停止")
        }

        @Test
        @DisplayName("收集方取消时传输层连接被关掉，不留半开的 socket")
        fun cancellationClosesTransport() = runBlocking {
            val transport = FakeTransport()
            val flow = stream(4, OverflowPolicy.DROP_OLDEST, transport) { it }
            val seen = CountDownLatch(1)

            val job = launch(Dispatchers.Default) {
                flow.collect { seen.countDown() }
            }
            val sink = transport.awaitReady()
            sink.onFrame("frame")
            assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue()

            job.cancelAndJoin()

            assertThat(transport.cancelled.get()).isTrue()
        }

        @Test
        @DisplayName("解析不了的单帧被跳过，整条流不受影响")
        fun skipsUnparsableFrames() = runBlocking {
            // 内核版本更新引入新字段时会推来我们不认识的帧。
            // 丢掉单帧远好于让整条流断掉
            val transport = FakeTransport()
            val failures = AtomicInteger()
            val recorder = object : FrameStreamRecorder {
                override fun onSendResult(result: SendResult) = Unit
                override fun onParseFailure() {
                    failures.incrementAndGet()
                }
            }
            val flow = stream(8, OverflowPolicy.DROP_OLDEST, transport, recorder) { it.toInt() }
            val collected = collectFirst(flow)
            val sink = transport.awaitReady()

            sink.onFrame("not-a-number")
            sink.onFrame("7")

            assertThat(withTimeout(5_000) { collected.await() }).isEqualTo(7)
            assertThat(failures.get()).isEqualTo(1)
        }
    }

    // ---------------------------------------------------------------- 测试工具

    private fun <T : Any> stream(
        capacity: Int,
        policy: OverflowPolicy,
        transport: FakeTransport,
        recorder: FrameStreamRecorder = FrameStreamRecorder.NOOP,
        parse: (String) -> T?,
    ): Flow<T> = clashFrameFlow(
        capacity = capacity,
        policy = policy,
        parseDispatcher = parseDispatcher,
        recorder = recorder,
        parse = parse,
        connect = transport::connect,
    )

    /** 先把收集挂到后台，用例才有机会拿到 sink 去推帧。只关心第一项。 */
    private fun <T> collectFirst(flow: Flow<T>): Deferred<T> = scope.async { flow.first() }

    /**
     * 一个会在第一项上停住的收集方。
     *
     * 用真实线程阻塞而不是 `delay`：要验证的正是「下游没有隐形缓冲」，
     * 而任何基于虚拟时间的收集方都会把这件事测没了 —— Turbine 内部就用了
     * 一个无界通道，拿它测背压永远是绿的。
     */
    private inner class SlowCollector(private val flow: Flow<Int>) {
        private val seen = CopyOnWriteArrayList<Int>()
        private val first = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        private var job: kotlinx.coroutines.Job? = null

        fun start() {
            job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                flow.collect { value ->
                    seen.add(value)
                    if (seen.size == 1) {
                        first.countDown()
                        gate.await()
                    }
                }
            }
        }

        fun awaitFirst() = check(first.await(5, TimeUnit.SECONDS)) { "第一帧始终没到" }

        fun release() = gate.countDown()

        fun awaitValue(value: Int) {
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
            while (System.currentTimeMillis() < deadline) {
                if (seen.contains(value)) return
                Thread.sleep(POLL_MS)
            }
            throw AssertionError("始终没有收到 $value，实际收到 $seen")
        }

        fun seen(): List<Int> = seen.toList()

        fun stop() = runBlocking { job?.cancelAndJoin() }
    }

    private companion object {
        const val PARSE_THREAD = "clash-parse"
        const val TRANSPORT_THREAD = "fake-ws-read"
        const val SLOW_PARSE_MS = 400L
        const val BURST = 400
        const val RING = 4
        const val POLL_MS = 5L

        /**
         * 收集方被卡住期间，下游最多只能有一格在途（会合式），队列里最多一格。
         * 再多就说明某处又冒出了一个隐形缓冲。
         */
        const val MAX_COALESCED_DELIVERIES = 4
    }
}
