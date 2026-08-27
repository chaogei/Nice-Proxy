package com.niceproxy.core.service.core

import com.niceproxy.libnice.Libnice
import com.niceproxy.libnice.Service
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [com.niceproxy.libnice] 的 Kotlin 包装。
 *
 * 存在的意义有两个：把 gomobile 抛出的裸 [Exception] 收敛成 [Result]，
 * 以及保证同一时刻只有一个内核实例 —— 重复 start 会导致端口冲突，
 * 而 sing-box 的报错信息在这种情况下相当难懂。
 */
@Singleton
class NiceCore @Inject constructor() {

    private var service: Service? = null

    val version: String get() = runCatching { Libnice.version() }.getOrDefault("unknown")

    val isRunning: Boolean get() = service?.isRunning == true

    /** 不启动内核，仅校验配置。用于保存设置时的即时反馈。 */
    fun checkConfig(configJson: String): Result<Unit> = runCatching {
        Libnice.checkConfig(configJson)
    }

    fun start(configJson: String, workDir: String): Result<Unit> = runCatching {
        check(service == null) { "内核已在运行" }
        val created = Libnice.newService(configJson, workDir)
        try {
            created.start()
        } catch (t: Throwable) {
            // 启动失败时实例仍持有已打开的监听套接字，必须显式释放，
            // 否则下次启动会撞上「端口已被占用」。
            runCatching { created.close() }
            throw t
        }
        service = created
    }

    fun stop(): Result<Unit> = runCatching {
        service?.close()
    }.also { service = null }.map { }
}
