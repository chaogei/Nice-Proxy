package com.niceproxy.core.service.core

import android.util.Log
import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [com.niceproxy.libnice] 的 Kotlin 包装。
 *
 * 存在的意义有三个：把 gomobile 抛出的裸 [Exception] 收敛成 [Result]；
 * 保证同一时刻只有一个内核实例（重复 start 会导致端口冲突，而 sing-box 的报错信息
 * 在这种情况下相当难懂）；以及**把阻塞的 JNI 调用挡在主线程之外**。
 *
 * 最后一条决定了这个类的形态。`Libnice` 的每个方法都是同步阻塞的原生调用：
 * `newService` 要解析并装配整份配置，`start` 里包含远程 rule-set 的下载（断网时是
 * 几十秒），`close` 收尾时会跑一整轮 GC 再把内存还给系统。所以对外一律只给
 * `suspend` 版本 —— 想在主线程上误调都调不出来。
 *
 * 并发模型：所有**改动** [service] 的路径都串在同一把 [mutex] 上。选 kotlinx 的
 * [Mutex] 而不是 `synchronized` 或 `ReentrantLock`，除了它可挂起之外还有一个不能
 * 换掉的性质 —— 它是**公平的**（等待者按 FIFO 唤醒）。「用户飞快地按停止再按启动」
 * 全靠这一点保证 stop 一定排在 start 前面；换成任何非公平锁，start 都可能抢先拿到
 * 锁，然后对着一个还没关掉的内核报「端口已被占用」。
 *
 * [isRunning] 是唯一的例外，它**不拿锁**，理由见那里的注释。
 */
@Singleton
class NiceCore internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val runtime: KernelRuntime,
    /** 宿主兜底超时相对内核自身截止时间的余量，见 [start]。 */
    private val abortGraceMs: Long = START_ABORT_GRACE_MS,
) {

    @Inject
    constructor(@Dispatcher(NiceDispatcher.IO) ioDispatcher: CoroutineDispatcher) :
        this(ioDispatcher, GomobileKernelRuntime)

    private val mutex = Mutex()

    /**
     * 与本单例同生命周期，用来跑那些**不能跟着调用方一起被取消**的工作：
     * 阻塞的启动本身（见 [start]），以及超时之后的收尾（见 [disownPendingStart]）。
     */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 只允许在 [mutex] 内**写**；读是无锁的，所以必须 volatile —— 曾经是裸 `var`，
     * 挪到 IO 线程之后那就是真正的竞态了。
     */
    @Volatile
    private var service: KernelHandle? = null

    /**
     * 超时后仍卡在 `box.Start()` 里的那一次启动。只允许在 [mutex] 内读写。
     *
     * 它非 null 就意味着「有一个我们已经不要了、却可能随时绑上端口的内核」。
     * 这期间任何新的启动都注定撞上端口占用，与其让用户对着一串 EADDRINUSE 发懵，
     * 不如如实说清楚上一次还没结束。
     */
    private var abandonedStart: Deferred<KernelHandle>? = null

    /** 读的是编译期常量，没有锁也没有 I/O，所以保留同步形式供 UI 直接取用。 */
    val version: String get() = runCatching { runtime.version }.getOrDefault("unknown")

    /**
     * 宿主这边**认为**内核在跑。
     *
     * 它反映的是指令历史（start 成功过且还没 stop），不是存活状态：内核因内部错误
     * 退出时，Go 侧没有任何代码会把这一位改回去。真正的存活判定必须去探测 Clash
     * API，见 `ProxyService.superviseCore`。这里只回答「还有没有一个需要被关掉的
     * 实例」。
     *
     * **刻意不拿 [mutex]。** [start] 会把那把锁一直握到内核起来或超时为止，最长一分钟；
     * 拿锁的话，这个本该瞬时的探测会在整个启动期间挂住。它是一个「还活着吗」的查询，
     * 查询挂在被查询者身上，恰恰是最需要它的时候最不可用 —— 那正是 Go 侧把
     * `box.Start()` 移出锁要解决的问题，在这一层再锁一次等于白改。
     *
     * 无锁读会看到什么：要么是启动前的 null（如实答 false），要么是启动完成后写入的
     * 实例。[service] 是 volatile，不存在读到半个对象的可能；而底层句柄自身对并发的
     * `IsRunning` 是安全的，关停之后也只会答 false。
     */
    suspend fun isRunning(): Boolean {
        val current = service ?: return false
        return withContext(ioDispatcher) { runCatching { current.isRunning() }.getOrDefault(false) }
    }

    /** 不启动内核，仅校验配置。用于保存设置时的即时反馈。 */
    suspend fun checkConfig(configJson: String): Result<Unit> =
        withContext(ioDispatcher) { runCatching { runtime.checkConfig(configJson) } }

    /**
     * 启动内核。
     *
     * 超时分两层，缺一不可：
     *
     * 1. **内核自己的截止时间**（[KernelHandle.start] 的参数）。这一层是真正的中止 ——
     *    到点就取消内核内部的 context，正在下载的 rule-set 立刻返回错误，`box.Start()`
     *    随之收尾，不留下任何还在跑的东西。
     * 2. **宿主的兜底**（[abortGraceMs] 之后放弃等待）。第一层依赖内核里每个组件都
     *    老实响应 context 取消，那是个假设而不是保证；万一某个组件不响应，这个阻塞的
     *    JNI 调用没有任何办法打断，只能不再等它、把控制权还给调用方，并留下
     *    [abandonedStart] 把后事接管过去。
     *
     * 换句话说：第二层触发就意味着内核违反了自己的契约，正常路径上永远走不到。
     *
     * @param timeoutMs 内核启动的截止时间。取值要给远程 rule-set 的下载留足余量 ——
     *        那一步就发生在 `box.Start()` 内部，首次启动时要拉好几个几 MB 的 srs 文件。
     *        定得太短，弱网用户会永远启动不起来。
     */
    suspend fun start(
        configJson: String,
        workDir: String,
        timeoutMs: Long = START_TIMEOUT_MS,
    ): Result<Unit> = mutex.withLock {
        if (service != null) {
            return@withLock Result.failure(IllegalStateException("内核已在运行"))
        }
        if (abandonedStart != null) {
            return@withLock Result.failure(IllegalStateException("上一次内核启动尚未结束"))
        }

        // 刻意不用 withContext：那样兜底超时根本不会生效。协程取消打不断阻塞中的 JNI
        // 调用，withContext 会一直等到 box.Start() 自己返回 —— 断网时那是几十秒的
        // 主线程之外的空等，调用方却拿不回控制权。只有把它扔进独立协程再去等，
        // 超时才是真的超时。
        val pending = scope.async { openAndStart(configJson, workDir, timeoutMs) }

        // join 而不是 await：await 会把子协程的异常抛到当前协程，而这里需要区分
        // 「超时了」和「启动失败了」这两种完全不同的结局。
        val finished = try {
            withTimeoutOrNull(timeoutMs + abortGraceMs) { pending.join() } != null
        } catch (e: CancellationException) {
            // 调用方被取消了（多半是服务正在销毁）。这次启动仍在原生层跑着，
            // 不认领下来的话，它一旦迟到成功就是个没人能停的孤儿内核。
            disownPendingStart(pending)
            throw e
        }
        if (!finished) {
            disownPendingStart(pending)
            return@withLock Result.failure(
                IllegalStateException("内核启动超时（${timeoutMs / MILLIS_PER_SECOND} 秒）"),
            )
        }

        // pending 此刻已经完成，await 不会再挂起，也就没有「赋值前被取消」的窗口
        runCatching { pending.await() }.onSuccess { service = it }.map { }
    }

    /**
     * 停止内核。
     *
     * 挂起到内核确实关停为止 —— 监听端口的释放是下一次启动能否成功的前提，
     * 「发出了关停请求」和「端口真的没了」之间那段时间必须由调用方承担。
     */
    suspend fun stop(): Result<Unit> = mutex.withLock {
        val current = service ?: return@withLock Result.success(Unit)
        service = null
        // NonCancellable：这一步只要开了头就必须做完。引用已经从字段里摘走了，
        // 此时被取消掉就等于把一个没人持有、端口却还占着的内核留在原地。
        withContext(NonCancellable + ioDispatcher) { runCatching { current.close() } }
    }

    private fun openAndStart(configJson: String, workDir: String, timeoutMs: Long): KernelHandle {
        val created = runtime.open(configJson, workDir)
        try {
            created.start(timeoutMs)
        } catch (t: Throwable) {
            // 启动失败时实例仍持有已打开的监听套接字，必须显式释放，
            // 否则下次启动会撞上「端口已被占用」。
            runCatching { created.close() }
            throw t
        }
        return created
    }

    /**
     * 认领一次超时的启动，等它自己了结之后把内核关掉。
     *
     * 调用方此刻已经拿到失败结果、走上了失败处理路径，不会再有任何人持有这个实例的
     * 引用。放着不管的话，`box.Start()` 一旦迟到成功，就留下一个绑着端口、却没有任何
     * 东西能停掉它的内核 —— 那只能靠杀进程收场。
     */
    private fun disownPendingStart(pending: Deferred<KernelHandle>) {
        abandonedStart = pending
        scope.launch {
            val leaked = runCatching { pending.await() }
                .onFailure { Log.i(TAG, "超时的那次启动最终失败，无需收尾", it) }
                .getOrNull()
            mutex.withLock {
                if (abandonedStart === pending) abandonedStart = null
                leaked?.let {
                    Log.w(TAG, "超时的内核迟到启动成功，立即关停以释放端口")
                    runCatching { it.close() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "NiceCore"

        /**
         * 首次启动要下载远程 rule-set，弱网下十几秒很常见，所以不能定得太紧；
         * 而超过一分钟仍起不来的话，继续等下去对用户已经没有意义。
         */
        const val START_TIMEOUT_MS = 60_000L

        /**
         * 内核到点没有自行中止时，宿主再多等这么久就放弃。
         *
         * 取值只需要覆盖「取消 context → 各组件收尾 → `box.Start()` 返回」这一段，
         * 实测是毫秒级的；给到 5 秒纯粹是余量。再长没有意义 —— 走到这里已经说明
         * 内核不响应取消了，继续等下去只是让用户多盯几秒转圈。
         */
        const val START_ABORT_GRACE_MS = 5_000L

        const val MILLIS_PER_SECOND = 1_000L
    }
}
