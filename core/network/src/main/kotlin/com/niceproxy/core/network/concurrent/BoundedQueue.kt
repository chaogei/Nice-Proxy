package com.niceproxy.core.network.concurrent

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 满了之后怎么办。这一步的选择决定了整条链路在过载时**丢什么**，
 * 而「丢什么」远比「丢多少」重要。
 */
enum class OverflowPolicy {
    /**
     * 覆盖最旧的一格。适合日志这类「每一帧都独立、但只有最近的有价值」的流：
     * 界面上滚过去的那几行没人会回头看，而卡住生产者会把整条 WebSocket 堵死。
     */
    DROP_OLDEST,

    /**
     * 覆盖最新的那一格，也就是队尾原地替换。容量为 1 时这就是标准的**合帧**
     * （conflate）：流量、内存这种 latest-value 语义的观测流，积压十帧毫无意义，
     * 消费者要的只有最新那一帧。
     *
     * 容量大于 1 时的语义是「前面的排队，最新的永远进得来」—— 既吸收一点突发，
     * 又保证最新值不会因为队列满而被丢掉。
     */
    LATEST_WINS,

    /** 满了就拒绝，把背压如实还给生产者。适合不允许丢数据的任务队列。 */
    REJECT,
}

/** [BoundedQueue.trySend] 的结局。调用方据此累加自己的指标，或决定要不要重试。 */
enum class SendResult {
    /** 进队列了，深度 +1。 */
    ACCEPTED,

    /** 队列已满，挤掉了最旧的一格（[OverflowPolicy.DROP_OLDEST]）。 */
    DROPPED_OLDEST,

    /** 队列已满，原地替换了最新的一格（[OverflowPolicy.LATEST_WINS]）。 */
    COALESCED,

    /** 队列已满且策略是拒绝，这一帧没有进去。 */
    REJECTED,
    ;

    /** 数据有没有落进队列。被拒绝是唯一「没落进去」的情形。 */
    val accepted: Boolean get() = this != REJECTED
}

/**
 * 一个定容的多生产者 / 多消费者队列，用环形缓冲实现。
 *
 * 为什么不直接用 `Channel`：这里要的三件事它都不给全 ——
 * 一是 `trySend` 必须在**非协程**上下文（OkHttp 的 WebSocket 回调线程）里安全调用
 * 且绝不阻塞；二是溢出时到底发生了什么必须能被观测到（丢了几帧、合了几帧、
 * 峰值深度多少），否则线上过载只会表现成「数字偶尔跳一下」，没有任何线索；
 * 三是 `BufferOverflow.DROP_OLDEST` 这类策略在 `Channel` 上无法与「容量 1 的合帧」
 * 共用一套指标口径。
 *
 * 实现上是一把 [ReentrantLock] 保护的环形数组，加一个信号量表示「可取的元素数」。
 * 二者的不变式是 **信号量的许可数恒等于队列深度**：只有真正让深度 +1 的入队才
 * release，覆盖与替换都不 release。因此 [receive] 拿到许可之后一定能取到元素。
 *
 * @param capacity 容量。[OverflowPolicy.LATEST_WINS] 想要纯合帧语义时传 1。
 */
class BoundedQueue<T : Any>(
    val capacity: Int,
    val policy: OverflowPolicy,
) {

    init {
        require(capacity > 0) { "队列容量必须为正数，实际为 $capacity" }
    }

    private val lock = ReentrantLock()
    private val buffer = arrayOfNulls<Any?>(capacity)

    /** 队头下标（下一个被取走的元素）。 */
    private var head = 0
    private var count = 0

    /**
     * 可取元素数的信号量视图。初始持满，等价于「可用许可 0」。
     *
     * 上限设成 [capacity]，与不变式一致；一旦哪天改坏了不变式，
     * 多出来的那次 release 会当场抛异常，而不是悄悄变成一次永久挂起。
     */
    private val available = Semaphore(permits = capacity, acquiredPermits = capacity)

    @Volatile
    private var enqueued = 0L

    @Volatile
    private var droppedOldest = 0L

    @Volatile
    private var coalesced = 0L

    @Volatile
    private var rejected = 0L

    @Volatile
    private var peakDepth = 0

    /** 当前深度。只用于观测，读到的一定是某个瞬间的真值，但读完就可能过期。 */
    val size: Int get() = lock.withLock { count }

    val isEmpty: Boolean get() = size == 0

    /**
     * 入队，绝不阻塞、绝不挂起。
     *
     * 可以在任意线程调用，包括 OkHttp 的 WebSocket 读线程 —— 那正是它存在的理由：
     * 那条线程被所有本机 WebSocket 共用，在上面做任何可能变慢的事（JSON 解析、
     * 等待下游消费）都会连累其他流。
     */
    fun trySend(value: T): SendResult {
        val result: SendResult
        var depth: Int
        lock.withLock {
            if (count < capacity) {
                buffer[(head + count) % capacity] = value
                count++
                result = SendResult.ACCEPTED
            } else {
                when (policy) {
                    OverflowPolicy.DROP_OLDEST -> {
                        buffer[head] = value
                        head = (head + 1) % capacity
                        result = SendResult.DROPPED_OLDEST
                    }

                    OverflowPolicy.LATEST_WINS -> {
                        buffer[(head + count - 1) % capacity] = value
                        result = SendResult.COALESCED
                    }

                    OverflowPolicy.REJECT -> result = SendResult.REJECTED
                }
            }
            depth = count
            when (result) {
                SendResult.ACCEPTED -> enqueued++
                SendResult.DROPPED_OLDEST -> droppedOldest++
                SendResult.COALESCED -> coalesced++
                SendResult.REJECTED -> rejected++
            }
            if (depth > peakDepth) peakDepth = depth
        }
        // release 放在锁外：信号量的等待者会在这里被唤醒，没必要让它们醒来就撞上我们的锁
        if (result == SendResult.ACCEPTED) available.release()
        return result
    }

    /** 取一个，没有就返回 null。 */
    fun tryReceive(): T? {
        if (!available.tryAcquire()) return null
        return takeLocked()
    }

    /**
     * 取一个，队列空就挂起等待。
     *
     * 没有 `close()`：这个队列的生命周期由消费者所在的协程作用域决定，作用域一取消，
     * 挂起在这里的 [receive] 立刻抛 `CancellationException`。再叠一套关闭状态机
     * 只会多出「关闭时残留元素算谁的」这种没有正确答案的问题。
     */
    suspend fun receive(): T {
        available.acquire()
        return takeLocked()
    }

    private fun takeLocked(): T {
        lock.withLock {
            @Suppress("UNCHECKED_CAST")
            val value = buffer[head] as T
            buffer[head] = null
            head = (head + 1) % capacity
            count--
            return value
        }
    }

    /** 把当前积压一次性取空，按入队顺序返回。用于「攒一批再处理」的消费者。 */
    fun drain(limit: Int = capacity): List<T> {
        val taken = ArrayList<T>(minOf(limit, capacity))
        while (taken.size < limit) {
            taken.add(tryReceive() ?: break)
        }
        return taken
    }

    fun metrics(): QueueMetrics = QueueMetrics(
        capacity = capacity,
        depth = size,
        peakDepth = peakDepth,
        enqueued = enqueued,
        droppedOldest = droppedOldest,
        coalesced = coalesced,
        rejected = rejected,
    )
}

/**
 * 队列的观测量。
 *
 * [droppedOldest] 与 [coalesced] 持续增长说明消费端跟不上生产端 —— 这是过载最早、
 * 也是唯一能在用户报障之前看到的信号。没有它，同样的过载只会表现为「监控页的数字
 * 偶尔卡一下」，而那个现象没有任何人能从日志里查出原因。
 */
data class QueueMetrics(
    val capacity: Int,
    val depth: Int,
    val peakDepth: Int,
    val enqueued: Long,
    val droppedOldest: Long,
    val coalesced: Long,
    val rejected: Long,
) {
    /** 因为容量不足而没能完整交付的帧数。 */
    val discarded: Long get() = droppedOldest + coalesced + rejected
}
