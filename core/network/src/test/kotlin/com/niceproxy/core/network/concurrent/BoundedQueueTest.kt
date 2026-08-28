package com.niceproxy.core.network.concurrent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 这个队列是整条观测链路的背压支点，所以下面刻意不去测「放进去能取出来」这种自明的
 * 性质，而是测**溢出时到底丢了什么**：丢错了东西不会报错，只会让界面上的数字变得
 * 微妙地不对，而那种缺陷没有任何日志能指向它。
 */
class BoundedQueueTest {

    @Nested
    @DisplayName("溢出策略")
    inner class Overflow {

        @Test
        @DisplayName("未满时按先进先出，谁也不丢")
        fun keepsOrderWhenNotFull() {
            val queue = BoundedQueue<Int>(4, OverflowPolicy.DROP_OLDEST)

            (1..4).forEach { assertThat(queue.trySend(it)).isEqualTo(SendResult.ACCEPTED) }

            assertThat(queue.drain()).containsExactly(1, 2, 3, 4).inOrder()
        }

        @Test
        @DisplayName("DROP_OLDEST 满了之后丢最旧的，保留最近的一段")
        fun dropOldestKeepsRecentWindow() {
            // 日志正是这个语义：滚过去的那几行没人会回头看，
            // 但「刚刚发生了什么」必须留住
            val queue = BoundedQueue<Int>(3, OverflowPolicy.DROP_OLDEST)

            (1..6).forEach { queue.trySend(it) }

            assertThat(queue.drain()).containsExactly(4, 5, 6).inOrder()
            assertThat(queue.metrics().droppedOldest).isEqualTo(3)
        }

        @Test
        @DisplayName("LATEST_WINS 容量为 1 时就是纯合帧：只留最新那一帧")
        fun latestWinsCoalesces() {
            // 流量/内存/连接快照是 latest-value 语义。把积压的旧帧显示出来，
            // 用户看到的是一个**倒退**的数字 —— 比丢掉它糟糕得多。
            val queue = BoundedQueue<Int>(1, OverflowPolicy.LATEST_WINS)

            assertThat(queue.trySend(1)).isEqualTo(SendResult.ACCEPTED)
            assertThat(queue.trySend(2)).isEqualTo(SendResult.COALESCED)
            assertThat(queue.trySend(3)).isEqualTo(SendResult.COALESCED)

            assertThat(queue.drain()).containsExactly(3)
            assertThat(queue.metrics().coalesced).isEqualTo(2)
        }

        @Test
        @DisplayName("LATEST_WINS 容量大于 1 时，最新的一定进得来，队头照常排队")
        fun latestWinsReplacesTail() {
            val queue = BoundedQueue<Int>(2, OverflowPolicy.LATEST_WINS)

            queue.trySend(1)
            queue.trySend(2)
            queue.trySend(3)
            queue.trySend(4)

            assertThat(queue.drain()).containsExactly(1, 4).inOrder()
        }

        @Test
        @DisplayName("REJECT 满了就拒绝，把背压如实还给生产者")
        fun rejectRefusesWhenFull() {
            val queue = BoundedQueue<Int>(2, OverflowPolicy.REJECT)

            queue.trySend(1)
            queue.trySend(2)
            val result = queue.trySend(3)

            assertThat(result).isEqualTo(SendResult.REJECTED)
            assertThat(result.accepted).isFalse()
            assertThat(queue.drain()).containsExactly(1, 2).inOrder()
        }
    }

    @Nested
    @DisplayName("消费者")
    inner class Consuming {

        @Test
        @DisplayName("空队列上 receive 会挂起，来了一帧立刻醒")
        fun receiveSuspendsUntilOffered() = runTest {
            val queue = BoundedQueue<String>(4, OverflowPolicy.DROP_OLDEST)

            val received = async { queue.receive() }
            // 还没有人入队，这里必须是挂着的
            assertThat(withTimeoutOrNull(50) { received.await() }).isNull()

            queue.trySend("frame")

            assertThat(withTimeout(1_000) { received.await() }).isEqualTo("frame")
        }

        @Test
        @DisplayName("取消挂起中的 receive 不会吃掉后续的帧")
        fun cancellingReceiveKeepsPermits() = runTest {
            // 信号量的许可数与队列深度是这个类的核心不变式。取消路径上少还一个许可，
            // 症状是「监控页开开关关几次之后，流量数字就再也不更新了」—— 而队列里
            // 明明有数据。这类缺陷靠肉眼审查是发现不了的。
            val queue = BoundedQueue<Int>(4, OverflowPolicy.DROP_OLDEST)

            repeat(5) {
                val cancelled = async { queue.receive() }
                assertThat(withTimeoutOrNull(20) { cancelled.await() }).isNull()
                cancelled.cancel()
            }

            queue.trySend(42)

            assertThat(withTimeout(1_000) { queue.receive() }).isEqualTo(42)
        }

        @Test
        @DisplayName("多消费者不会拿到同一帧，也不会漏帧")
        fun multipleConsumersSplitWork() = runTest {
            val queue = BoundedQueue<Int>(64, OverflowPolicy.REJECT)
            val total = 64
            (1..total).forEach { queue.trySend(it) }

            val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val counted = AtomicInteger()
            coroutineScope {
                repeat(4) {
                    launch {
                        while (counted.get() < total) {
                            val value = queue.tryReceive() ?: break
                            seen.add(value)
                            counted.incrementAndGet()
                        }
                    }
                }
            }

            assertThat(seen).hasSize(total)
        }
    }

    @Nested
    @DisplayName("线程安全")
    inner class ThreadSafety {

        @Test
        @DisplayName("多个真实线程同时入队，深度永远不越界")
        fun concurrentProducersNeverExceedCapacity() {
            // trySend 会在 OkHttp 的 WebSocket 读线程上被调用，而那不是协程 ——
            // 所以这里必须用真实线程压，用 runTest 的虚拟并发压不出环形缓冲的竞态
            val capacity = 8
            val queue = BoundedQueue<Int>(capacity, OverflowPolicy.DROP_OLDEST)
            val threads = 8
            val perThread = 2_000
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)

            repeat(threads) { worker ->
                pool.execute {
                    start.await()
                    repeat(perThread) { queue.trySend(worker * perThread + it) }
                    done.countDown()
                }
            }
            start.countDown()
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
            pool.shutdown()

            val metrics = queue.metrics()
            assertThat(metrics.peakDepth).isAtMost(capacity)
            assertThat(metrics.depth).isEqualTo(capacity)
            // 每一次入队都必须被归到某一类，一个都不能漏
            assertThat(metrics.enqueued + metrics.discarded)
                .isEqualTo((threads * perThread).toLong())
        }

        @Test
        @DisplayName("生产者与消费者同时跑，每一帧的去向都对得上账")
        fun concurrentProducerConsumerAccounting() {
            // 环形缓冲的下标运算在「边入队边出队」时最容易错位，而错位的表现不是崩溃，
            // 是偶尔取到一个已经被覆盖掉的旧值 —— 对流量数字来说就是「偶尔跳一下」
            val queue = BoundedQueue<Int>(4, OverflowPolicy.DROP_OLDEST)
            val produced = 50_000
            val consumed = AtomicInteger()

            val producer = Thread { repeat(produced) { queue.trySend(it) } }
            producer.start()
            while (producer.isAlive || !queue.isEmpty) {
                if (queue.tryReceive() != null) consumed.incrementAndGet()
            }
            producer.join(TimeUnit.SECONDS.toMillis(30))

            val metrics = queue.metrics()
            // 每一次 trySend 都必须被归到某一类，一个都不能漏
            assertThat(metrics.enqueued + metrics.discarded).isEqualTo(produced.toLong())
            // 「可取元素数」这个不变式：让深度 +1 的入队有多少次，消费者就该取到多少个。
            // 覆盖最旧只是把某个已占用格子里的**值**换掉，格子本身还在，
            // 所以它不减少可取数 —— 这条账对不上就说明信号量与环形缓冲脱了节，
            // 而那个缺陷的表现是「监控页开开关关几次之后流量数字再也不更新」
            assertThat(consumed.get().toLong() + metrics.depth).isEqualTo(metrics.enqueued)
            assertThat(metrics.droppedOldest).isGreaterThan(0L)
        }
    }

    @Test
    @DisplayName("容量非正数当场拒绝，而不是留一个永远不工作的队列")
    fun rejectsNonPositiveCapacity() {
        assertThrows<IllegalArgumentException> { BoundedQueue<Int>(0, OverflowPolicy.REJECT) }
    }
}
