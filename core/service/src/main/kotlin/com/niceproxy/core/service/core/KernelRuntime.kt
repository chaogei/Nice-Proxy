package com.niceproxy.core.service.core

import com.niceproxy.libnice.Libnice
import com.niceproxy.libnice.Service

/**
 * 一个内核实例的句柄。
 *
 * 存在的唯一理由是**把 gomobile 的类型挡在 [NiceCore] 之外**。AAR 是 `compileOnly`
 * 依赖（AGP 不允许 library 模块直接打包本地 .aar），因此它的类根本不在单元测试的
 * 运行时类路径上 —— 只要 [NiceCore] 里还留着一处 `com.niceproxy.libnice.*` 的引用，
 * 整个类就无法被单测覆盖。而它里面装的恰好是这个模块最难写对的一段逻辑：启动超时、
 * 超时之后的孤儿实例收尾、以及「启动进行中」时的并发读。那段逻辑过去一行测试都没有。
 *
 * 所以这里只保留 [NiceCore] 真正用得到的三个动作，实现见 [GomobileKernelRuntime]。
 */
internal interface KernelHandle {

    /**
     * 启动内核，阻塞到内核起来（或失败）为止。
     *
     * @param timeoutMs 正数时把截止时间**交给内核自己执行**。这一点和调用方那层的
     *        超时有本质区别：协程取消打不断阻塞中的 JNI 调用，宿主的超时只是「不再
     *        等了」，内核照样在跑；而内核自己的截止时间会取消它内部的 context，
     *        正在下载的远程 rule-set 会立刻返回错误。非正数表示不设截止时间。
     */
    fun start(timeoutMs: Long)

    /** 必须瞬时返回，即使有一次启动正在进行。 */
    fun isRunning(): Boolean

    fun close()
}

/** 内核的进程级入口。抽出来的理由同 [KernelHandle]。 */
internal interface KernelRuntime {

    val version: String

    fun checkConfig(configJson: String)

    /** 解析并装配配置，但不启动。失败时抛出 gomobile 的裸异常。 */
    fun open(configJson: String, workDir: String): KernelHandle
}

/** 唯一接触 gomobile 生成类的地方。 */
internal object GomobileKernelRuntime : KernelRuntime {

    override val version: String get() = Libnice.version()

    override fun checkConfig(configJson: String) = Libnice.checkConfig(configJson)

    override fun open(configJson: String, workDir: String): KernelHandle =
        GomobileKernel(Libnice.newService(configJson, workDir))
}

private class GomobileKernel(private val service: Service) : KernelHandle {

    override fun start(timeoutMs: Long) {
        if (timeoutMs > 0) service.startWithTimeout(timeoutMs) else service.start()
    }

    override fun isRunning(): Boolean = service.isRunning

    override fun close() = service.close()
}
