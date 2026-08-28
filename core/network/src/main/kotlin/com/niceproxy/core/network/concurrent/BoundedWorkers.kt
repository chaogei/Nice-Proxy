package com.niceproxy.core.network.concurrent

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 定量的 worker 池：**先建好 N 条协程，再让它们从同一个游标上抢任务**。
 *
 * 和常见的 `items.map { async { gate.withPermit { … } } }` 相比，差别不在于并发度
 * ——两者的并发度都被压在 N —— 而在于**峰值对象数**。后者会为一千个节点当场创建
 * 一千个 `Deferred`：一千个协程对象、一千条延续链、一千个信号量等待者，全部在真正
 * 开始干活之前就已经躺在堆上。用户点一次「全部测速」就是一次可观的 GC 压力尖峰，
 * 在低端机上表现为界面明显一顿。
 *
 * 这里的分派用的是一个原子游标而不是队列：任务集合是已知的 [List]，
 * 再套一层队列只是把索引换个地方存，白白多一次装箱和一次锁。
 */
object BoundedWorkers {

    /**
     * 对 [items] 逐个执行 [action]，同时最多 [concurrency] 个在跑。
     *
     * [action] 抛出的异常会照常向上传播并取消整批 —— 这是结构化并发的默认行为，
     * 想「单个失败不影响整批」请在 [action] 内部自己 `runCatching`。
     */
    suspend fun <T> forEach(
        items: List<T>,
        concurrency: Int,
        action: suspend (item: T) -> Unit,
    ) {
        forEachIndexed(items, concurrency) { _, item -> action(item) }
    }

    /** 同 [forEach]，但把元素下标一并交给 [action]。 */
    suspend fun <T> forEachIndexed(
        items: List<T>,
        concurrency: Int,
        action: suspend (index: Int, item: T) -> Unit,
    ) {
        if (items.isEmpty()) return
        // 元素比并发度少的时候多起的协程只会空转一圈就退出，没必要付这份创建成本
        val workers = concurrency.coerceIn(1, items.size)
        val cursor = AtomicInteger(0)
        coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val index = cursor.getAndIncrement()
                        if (index >= items.size) break
                        action(index, items[index])
                    }
                }
            }
        }
    }

    /**
     * 有界并发的 map，**结果保持输入顺序**。
     *
     * 顺序是刻意保证的：调用方拿到的结果要能和输入一一对应，否则每个调用方都得
     * 自己在结果里塞一个 id 再重排一遍。写进预分配数组而不是并发容器，
     * 也顺带省掉了那层同步。
     */
    suspend fun <T, R> map(
        items: List<T>,
        concurrency: Int,
        transform: suspend (item: T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val results = arrayOfNulls<Any?>(items.size)
        forEachIndexed(items, concurrency) { index, item -> results[index] = transform(item) }
        @Suppress("UNCHECKED_CAST")
        return results.asList() as List<R>
    }
}
