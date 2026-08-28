package com.niceproxy.core.network.concurrent

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一个针对「对端整个不在了」这种故障的熔断器。
 *
 * 本机内核挂掉之后，Clash API 的每一次请求都是**即刻**返回 ECONNREFUSED ——
 * 不是超时，是零耗时失败。于是任何「失败就重试」的调用方都会退化成忙等：
 * 监控页每秒刷新一次连接列表、通知栏每秒取一次流量，几个页面叠在一起就是
 * 每秒上百次系统调用，而屏幕上什么都不会变。手机发烫、耗电飙升，
 * 用户唯一能观察到的现象是「开着代理特别费电」。
 *
 * 熔断打开之后调用方拿到的是本地失败，一次系统调用都不发。冷却时间随连续失败
 * 指数增长并封顶，因为「内核挂了」和「内核正在重启」的时长量级完全不同。
 *
 * @param nowMillis 时钟。测试要能把时间往前拨，否则每个用例都得真睡几秒。
 */
class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val baseCooldownMillis: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    enum class State {
        /** 正常放行。 */
        CLOSED,

        /** 冷却中，一律本地失败。 */
        OPEN,

        /** 冷却结束，放**一次**探测过去。它的成败决定是回到 [CLOSED] 还是继续冷却。 */
        HALF_OPEN,
    }

    init {
        require(failureThreshold > 0) { "熔断阈值必须为正数" }
        require(baseCooldownMillis > 0) { "冷却时间必须为正数" }
    }

    private val lock = ReentrantLock()

    private var currentState = State.CLOSED
    private var consecutiveFailures = 0
    private var openUntilMillis = 0L
    private var cooldownMillis = baseCooldownMillis

    /**
     * 半开状态下已经放出去、还没回来的那一次探测。
     *
     * 没有它，冷却一到点，正在排队的十几个调用会**同时**被判为半开而全部放行 ——
     * 那不是探测，那是把刚喘过气的内核再按下去一次。
     */
    private var probeInFlight = false

    @Volatile
    private var shortCircuited = 0L

    val state: State get() = lock.withLock { refresh(); currentState }

    /** 熔断期间被本地挡掉、一次系统调用都没发的请求数。 */
    val shortCircuitedCount: Long get() = shortCircuited

    /**
     * 询问这一次能不能真的发出去。
     *
     * 返回 true 的调用方**必须**在拿到结局后调用 [recordSuccess] 或 [recordFailure]，
     * 否则半开状态下的那一次探测许可会永远还不回来。[withBreaker] 帮你保证这一点。
     */
    fun tryAcquire(): Boolean = lock.withLock {
        refresh()
        when (currentState) {
            State.CLOSED -> true
            State.OPEN -> {
                shortCircuited++
                false
            }

            State.HALF_OPEN -> {
                if (probeInFlight) {
                    shortCircuited++
                    false
                } else {
                    probeInFlight = true
                    true
                }
            }
        }
    }

    fun recordSuccess() = lock.withLock {
        currentState = State.CLOSED
        consecutiveFailures = 0
        cooldownMillis = baseCooldownMillis
        openUntilMillis = 0
        probeInFlight = false
    }

    fun recordFailure() = lock.withLock {
        val wasProbing = currentState == State.HALF_OPEN
        probeInFlight = false
        consecutiveFailures++
        // 半开探测失败说明对端还没回来，冷却翻倍；否则从基准冷却重新开始
        if (wasProbing) {
            cooldownMillis = (cooldownMillis * 2).coerceAtMost(maxCooldownMillis)
        }
        if (wasProbing || consecutiveFailures >= failureThreshold) {
            currentState = State.OPEN
            openUntilMillis = nowMillis() + cooldownMillis
        }
    }

    /**
     * 调用被取消了：既不算成功也不算失败，只把半开的那一张探测许可还回来。
     *
     * 少了这一步，「用户退出监控页」这种再正常不过的取消会把许可永久扣住，
     * 熔断器从此再也回不到关闭状态 —— 内核明明好了，界面却一直说连不上。
     */
    fun recordCancelled() = lock.withLock { probeInFlight = false }

    /** 内核确认恢复（例如另一条链路探活成功）时立刻放行，不必等冷却走完。 */
    fun reset() = recordSuccess()

    /** 只允许在锁内调用：把「冷却时间到了」这件事落到状态上。 */
    private fun refresh() {
        if (currentState == State.OPEN && nowMillis() >= openUntilMillis) {
            currentState = State.HALF_OPEN
            probeInFlight = false
        }
    }

    companion object {
        /**
         * 连续三次才熔断，而不是一次。
         *
         * 内核启动过程中有一段几百毫秒的窗口：端口已经在监听、Clash API 还没就绪，
         * 这期间零星的失败是正常的。一次就熔断会让「刚点启动」这个最需要及时反馈的
         * 时刻反而先冷却几秒。
         */
        const val DEFAULT_FAILURE_THRESHOLD = 3

        /** 本机内核重启通常在两秒内完成，冷却从这个量级起步。 */
        const val DEFAULT_BASE_COOLDOWN_MS = 1_000L

        /** 封顶。用户手动停掉内核之后不该每秒都被打扰，但也不能久到「起来了还不知道」。 */
        const val DEFAULT_MAX_COOLDOWN_MS = 15_000L
    }
}

/** 熔断打开时抛出。调用方能据此把「对端不在」和「请求真的失败了」分开处理。 */
class CircuitOpenException(message: String) : java.io.IOException(message)

/**
 * 在熔断器的保护下跑一段调用，并把成败如实回报给它。
 *
 * 只有 [java.io.IOException] 计入失败：4xx / 解析失败说明对端**活着**，
 * 只是这一次请求不对，把它算进熔断只会让一个业务错误连累掉整条链路。
 *
 * [kotlin.coroutines.cancellation.CancellationException] 单独处理并原样抛出。
 * `runCatching` 会把它当普通异常吞掉，那样调用方的取消就变成了一个「失败的
 * Result」，协程作用域取消不干净 —— 这是 `runCatching` 在挂起函数里最常见的坑。
 */
suspend fun <T> CircuitBreaker.withBreaker(
    /**
     * 什么样的异常算「对端不在」。默认是任何 IO 异常，但调用方通常要收窄 ——
     * 一个能被解析出来的 HTTP 错误响应恰恰证明对端活着。
     */
    isConnectivityFailure: (Throwable) -> Boolean = { it is java.io.IOException },
    onOpen: () -> Throwable = { CircuitOpenException("对端连续失败，正在冷却") },
    block: suspend () -> T,
): Result<T> {
    if (!tryAcquire()) return Result.failure(onOpen())
    val result = runCatching { block() }
    val failure = result.exceptionOrNull()
    when {
        failure == null -> recordSuccess()
        failure is CancellationException -> {
            recordCancelled()
            throw failure
        }

        isConnectivityFailure(failure) -> recordFailure()
        // 对端回了一个能解析的错误响应，说明它活着，不该拖累熔断状态
        else -> recordSuccess()
    }
    return result
}

/**
 * 探活调用：**永远真的发出去**，但把结果如实汇报给熔断器。
 *
 * 探活本身就是「对端回来了没有」的答案，用熔断去挡它是循环论证 —— 冷却期间探活
 * 一律失败，于是冷却永远续下去，而上层看到的是「内核明明起来了，监控还说它死着」。
 */
suspend fun <T> CircuitBreaker.withProbe(
    isConnectivityFailure: (Throwable) -> Boolean = { it is java.io.IOException },
    block: suspend () -> T,
): Result<T> {
    val result = runCatching { block() }
    val failure = result.exceptionOrNull()
    when {
        failure == null -> recordSuccess()
        failure is CancellationException -> throw failure
        isConnectivityFailure(failure) -> recordFailure()
        else -> recordSuccess()
    }
    return result
}
