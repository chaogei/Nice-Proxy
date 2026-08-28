package com.niceproxy.core.network.concurrent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class BoundedWorkersTest {

    @Test
    @DisplayName("同时在跑的任务数被压在并发度之内")
    fun capsConcurrency() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        BoundedWorkers.forEach((1..200).toList(), concurrency = 5) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { seen -> maxOf(seen, now) }
            delay(1)
            inFlight.decrementAndGet()
        }

        assertThat(peak.get()).isAtMost(5)
        assertThat(inFlight.get()).isEqualTo(0)
    }

    @Test
    @DisplayName("协程只建并发度那么多条，而不是每个任务一条")
    fun createsBoundedNumberOfCoroutines() = runTest {
        // 这正是替换掉 `items.map { async { … } }` 的理由：那种写法的并发度也被
        // 信号量压住了，但**一千个节点会当场创建一千个协程对象**躺在堆上，
        // 而低端机上那一下 GC 尖峰的表现是「点了测速界面就卡一下」。
        // 数协程身份是唯一能把这件事钉死的断言，光测并发度是测不出来的。
        val workerJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

        BoundedWorkers.forEach((1..500).toList(), concurrency = 4) {
            // 必须用 currentCoroutineContext()：runTest 的接收者 TestScope 自带一个
            // coroutineContext 属性，直接写 coroutineContext 拿到的是它，而不是
            // 当前这条 worker 协程的上下文
            workerJobs.add(currentCoroutineContext()[Job]!!)
            delay(1)
        }

        assertThat(workerJobs).hasSize(4)
    }

    @Test
    @DisplayName("每个元素恰好被处理一次")
    fun visitsEveryItemExactlyOnce() = runTest {
        val seen = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        BoundedWorkers.forEach((1..1_000).toList(), concurrency = 16) {
            seen.merge(it, 1, Int::plus)
        }

        assertThat(seen).hasSize(1_000)
        assertThat(seen.values.toSet()).containsExactly(1)
    }

    @Test
    @DisplayName("map 的结果保持输入顺序，哪怕完成顺序是乱的")
    fun mapPreservesOrder() = runTest {
        // 顺序不保证的话，每个调用方都得自己在结果里塞一个 id 再重排一遍
        val result = BoundedWorkers.map((1..50).toList(), concurrency = 8) { value ->
            // 故意让后面的元素先完成
            delay((50 - value).toLong())
            value * 2
        }

        assertThat(result).isEqualTo((1..50).map { it * 2 })
    }

    @Test
    @DisplayName("空输入什么也不做，也不会起协程")
    fun handlesEmptyInput() = runTest {
        var called = false
        BoundedWorkers.forEach(emptyList<Int>(), concurrency = 8) { called = true }
        assertThat(called).isFalse()
        assertThat(BoundedWorkers.map(emptyList<Int>(), 8) { it }).isEmpty()
    }

    @Test
    @DisplayName("元素比并发度少时不会多建 worker")
    fun clampsWorkersToItemCount() = runTest {
        val peak = AtomicInteger()
        val inFlight = AtomicInteger()

        BoundedWorkers.forEach(listOf(1, 2), concurrency = 64) {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { seen -> maxOf(seen, now) }
            delay(5)
            inFlight.decrementAndGet()
        }

        assertThat(peak.get()).isAtMost(2)
    }

    @Test
    @DisplayName("单个任务抛异常会取消整批并向上传播")
    fun propagatesFailure() = runTest {
        // 结构化并发的默认行为。想「单个失败不影响整批」的调用方
        // 必须自己在 action 里 runCatching —— 这一点要能被测试固定住
        assertThrows<IOException> {
            BoundedWorkers.forEach((1..20).toList(), concurrency = 4) {
                if (it == 7) throw IOException("boom")
                delay(1)
            }
        }
    }

    @Test
    @DisplayName("真实多线程派发下计数依然准确")
    fun worksOnRealThreadPool() = runTest {
        val total = AtomicInteger()

        withContext(Dispatchers.Default) {
            BoundedWorkers.forEach((1..5_000).toList(), concurrency = 32) {
                total.incrementAndGet()
            }
        }

        assertThat(total.get()).isEqualTo(5_000)
    }
}
