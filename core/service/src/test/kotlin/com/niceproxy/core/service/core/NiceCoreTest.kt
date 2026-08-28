package com.niceproxy.core.service.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NiceCore 里最难写对的是启动那一段：超时、超时之后那个还在原生层跑着的实例、
 * 以及这期间外面还在不停地问「内核起来了没」。这三件事过去一行测试都没有，因为
 * gomobile 的类是 compileOnly 依赖，根本不在单测的类路径上。
 *
 * 现在内核入口收敛到了 [KernelRuntime]，可以拿一个假内核把这些时序摆出来测。
 * 这里用真实调度器和真实时间（超时都压到百毫秒级），不用虚拟时间 —— 被测代码的
 * 要害正是「阻塞的原生调用打不断」，而虚拟时间会把这个前提抹掉。
 */
class NiceCoreTest {

    @Nested
    @DisplayName("启动与关停")
    inner class Lifecycle {

        @Test
        @DisplayName("启动成功后持有实例，关停后释放")
        fun startThenStop() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime)

            assertThat(core.start(CONFIG, WORK_DIR).isSuccess).isTrue()
            assertThat(core.isRunning()).isTrue()

            assertThat(core.stop().isSuccess).isTrue()
            assertThat(core.isRunning()).isFalse()
            assertThat(runtime.opened.single().closed).isTrue()
        }

        @Test
        @DisplayName("重复启动被挡下，不会再开一个实例")
        fun startIsRejectedWhileRunning() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime)
            core.start(CONFIG, WORK_DIR)

            val second = core.start(CONFIG, WORK_DIR)

            assertThat(second.isFailure).isTrue()
            assertThat(second.exceptionOrNull()).hasMessageThat().contains("已在运行")
            // 关键：第二次没有走到 open —— 否则就是两个内核抢同一个端口
            assertThat(runtime.opened).hasSize(1)
        }

        @Test
        @DisplayName("没启动过也能 stop，且是幂等的")
        fun stopIsIdempotent() = runBlocking {
            val core = newCore(FakeKernelRuntime())

            assertThat(core.stop().isSuccess).isTrue()
            assertThat(core.stop().isSuccess).isTrue()
        }

        @Test
        @DisplayName("装配失败原样上报，不留下实例")
        fun openFailureIsReported() = runBlocking {
            val runtime = FakeKernelRuntime(openFailure = IOException("配置里引用了不存在的出站"))
            val core = newCore(runtime)

            val result = core.start(CONFIG, WORK_DIR)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("不存在的出站")
            assertThat(core.isRunning()).isFalse()
            // 失败之后还能重试，不能被自己卡住
            assertThat(core.start(CONFIG, WORK_DIR).isFailure).isTrue()
        }

        @Test
        @DisplayName("启动失败时关掉已装配的实例，否则端口漏在那里")
        fun failedStartClosesTheHandle() = runBlocking {
            val runtime = FakeKernelRuntime(startFailure = IOException("address already in use"))
            val core = newCore(runtime)

            assertThat(core.start(CONFIG, WORK_DIR).isFailure).isTrue()

            val leaked = runtime.opened.single()
            assertThat(leaked.closed).isTrue()
            assertThat(core.isRunning()).isFalse()
        }

        @Test
        @DisplayName("启动失败后可以重新启动")
        fun retryAfterFailure() = runBlocking {
            val runtime = FakeKernelRuntime(startFailure = IOException("address already in use"))
            val core = newCore(runtime)
            assertThat(core.start(CONFIG, WORK_DIR).isFailure).isTrue()

            runtime.startFailure = null
            assertThat(core.start(CONFIG, WORK_DIR).isSuccess).isTrue()
            assertThat(core.isRunning()).isTrue()
        }
    }

    @Nested
    @DisplayName("启动超时")
    inner class StartTimeout {

        @Test
        @DisplayName("截止时间下推给内核，而不是只在宿主这边计时")
        fun deadlineIsPushedIntoTheKernel() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime)

            core.start(CONFIG, WORK_DIR, timeoutMs = 12_345)

            // 宿主的超时打不断阻塞的 JNI 调用，只有内核自己的截止时间是真的中止。
            // 传 0 或者不传，就退化成「超时之后放着一个还在跑的内核不管」。
            assertThat(runtime.opened.single().startTimeoutMs).isEqualTo(12_345)
        }

        @Test
        @DisplayName("内核不响应截止时间时，宿主兜底放弃等待")
        fun hostGivesUpWhenKernelIgnoresItsDeadline() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime, abortGraceMs = 150)
            runtime.blockStart()

            val result = core.start(CONFIG, WORK_DIR, timeoutMs = 100)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("内核启动超时")
            assertThat(core.isRunning()).isFalse()

            runtime.releaseStart()
        }

        @Test
        @DisplayName("超时期间不占着锁，isRunning 照常瞬时回话")
        fun isRunningStaysResponsiveDuringStart() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime, abortGraceMs = 10_000)
            runtime.blockStart()

            val starting = async(Dispatchers.IO) { core.start(CONFIG, WORK_DIR, timeoutMs = 10_000) }
            runtime.awaitStartEntered()

            // 这一步以前会挂满整个启动过程：start 握着 mutex，isRunning 也要那把锁。
            // 卡住的后果是看门狗探不到内核，把一个正在正常启动的实例当成死的杀掉。
            repeat(5) {
                withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                    assertThat(core.isRunning()).isFalse()
                }
            }

            runtime.releaseStart()
            assertThat(starting.await().isSuccess).isTrue()
        }

        @Test
        @DisplayName("超时后迟到成功的内核会被自动关停，不留孤儿")
        fun lateSuccessIsCleanedUp() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime, abortGraceMs = 100)
            runtime.blockStart()

            assertThat(core.start(CONFIG, WORK_DIR, timeoutMs = 100).isFailure).isTrue()

            // 内核终于起来了，但已经没有任何人持有它 —— 不收尾就是个绑着端口、
            // 却没有东西能停掉它的孤儿，只能靠杀进程收场。
            runtime.releaseStart()
            val orphan = runtime.opened.single()
            awaitUntil("超时的内核没有被关停") { orphan.closed }
        }

        @Test
        @DisplayName("上一次启动还没了结时，新的启动被明确拒绝")
        fun startIsRejectedWhilePreviousIsUnfinished() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime, abortGraceMs = 100)
            runtime.blockStart()
            assertThat(core.start(CONFIG, WORK_DIR, timeoutMs = 100).isFailure).isTrue()

            val second = core.start(CONFIG, WORK_DIR, timeoutMs = 100)

            // 这时候端口多半还占着，与其让用户对着一串 EADDRINUSE 发懵，
            // 不如如实说清楚上一次还没结束
            assertThat(second.exceptionOrNull()).hasMessageThat().contains("上一次内核启动尚未结束")
            assertThat(runtime.opened).hasSize(1)

            runtime.releaseStart()
            awaitUntil("孤儿实例没有被回收") { runtime.opened.single().closed }
            // 回收之后必须能重新启动，否则这个单例就废了
            awaitUntil("孤儿回收后仍然启动不了") { core.start(CONFIG, WORK_DIR).isSuccess }
        }

        @Test
        @DisplayName("调用方被取消时，启动中的内核仍会被认领并收尾")
        fun cancelledCallerStillCleansUp() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime, abortGraceMs = 10_000)
            runtime.blockStart()

            val caller = CoroutineScope(Dispatchers.IO).launch {
                core.start(CONFIG, WORK_DIR, timeoutMs = 10_000)
            }
            runtime.awaitStartEntered()
            caller.cancelAndJoin()

            runtime.releaseStart()
            awaitUntil("调用方取消后内核没有被关停") { runtime.opened.single().closed }
        }
    }

    @Nested
    @DisplayName("异常收敛")
    inner class ErrorContainment {

        @Test
        @DisplayName("版本号读取失败时退化成 unknown 而不是抛出")
        fun versionFallsBack() {
            val core = newCore(FakeKernelRuntime(versionFailure = UnsatisfiedLinkError("没有 so")))

            assertThat(core.version).isEqualTo("unknown")
        }

        @Test
        @DisplayName("配置校验的错误收敛成 Result")
        fun checkConfigReturnsResult() = runBlocking {
            val runtime = FakeKernelRuntime(checkFailure = IOException("字段名写错了"))
            val core = newCore(runtime)

            val result = core.checkConfig(CONFIG)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("字段名写错了")
            assertThat(newCore(FakeKernelRuntime()).checkConfig(CONFIG).isSuccess).isTrue()
        }

        @Test
        @DisplayName("句柄的 isRunning 抛异常时当作没在跑")
        fun isRunningSwallowsHandleFailure() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime)
            core.start(CONFIG, WORK_DIR)
            runtime.opened.single().isRunningFailure = IllegalStateException("句柄已失效")

            assertThat(core.isRunning()).isFalse()
        }

        @Test
        @DisplayName("关停抛异常时收敛成失败的 Result，且引用已经摘掉")
        fun stopFailureIsContained() = runBlocking {
            val runtime = FakeKernelRuntime()
            val core = newCore(runtime)
            core.start(CONFIG, WORK_DIR)
            runtime.opened.single().closeFailure = IOException("关停时报错")

            assertThat(core.stop().isFailure).isTrue()
            // 引用必须已经摘走：留着的话下一次 start 会被「内核已在运行」永久挡住
            assertThat(core.isRunning()).isFalse()
            assertThat(core.start(CONFIG, WORK_DIR).isSuccess).isTrue()
        }
    }

    private fun newCore(runtime: FakeKernelRuntime, abortGraceMs: Long = 1_000) =
        NiceCore(Dispatchers.IO, runtime, abortGraceMs)

    /** 轮询等待，用于观察那些发生在 NiceCore 内部协程里的收尾动作。 */
    private suspend fun awaitUntil(message: String, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val CONFIG = """{"log":{"level":"error"}}"""
        const val WORK_DIR = "/tmp/nice-test"
    }
}

private class FakeKernelRuntime(
    var openFailure: Throwable? = null,
    var startFailure: Throwable? = null,
    private val checkFailure: Throwable? = null,
    private val versionFailure: Throwable? = null,
) : KernelRuntime {

    val opened = CopyOnWriteArrayList<FakeKernel>()

    /** 非 null 时，[FakeKernel.start] 会阻塞在上面，模拟一次打不断的原生调用。 */
    @Volatile
    private var startGate: CountDownLatch? = null
    private val startEntered = CountDownLatch(1)
    private val checkCalls = AtomicInteger()

    override val version: String
        get() = versionFailure?.let { throw it } ?: "1.13.0-nice"

    override fun checkConfig(configJson: String) {
        checkCalls.incrementAndGet()
        checkFailure?.let { throw it }
    }

    override fun open(configJson: String, workDir: String): KernelHandle {
        openFailure?.let { throw it }
        val kernel = FakeKernel(
            onStart = { timeoutMs ->
                startEntered.countDown()
                startGate?.await(10, TimeUnit.SECONDS)
                startFailure?.let { throw it }
                timeoutMs
            },
        )
        opened += kernel
        return kernel
    }

    /** 让下一次 [KernelHandle.start] 卡住，直到 [releaseStart]。 */
    fun blockStart() {
        startGate = CountDownLatch(1)
    }

    fun releaseStart() {
        startGate?.countDown()
    }

    fun awaitStartEntered() {
        check(startEntered.await(5, TimeUnit.SECONDS)) { "内核的 start 一直没有被调用" }
    }
}

private class FakeKernel(private val onStart: (Long) -> Unit) : KernelHandle {

    @Volatile
    var startTimeoutMs: Long = -1
        private set

    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    var isRunningFailure: Throwable? = null

    @Volatile
    var closeFailure: Throwable? = null

    @Volatile
    private var running: Boolean = false

    override fun start(timeoutMs: Long) {
        startTimeoutMs = timeoutMs
        onStart(timeoutMs)
        running = true
    }

    override fun isRunning(): Boolean {
        isRunningFailure?.let { throw it }
        return running && !closed
    }

    override fun close() {
        closed = true
        running = false
        closeFailure?.let { throw it }
    }
}
