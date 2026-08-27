package com.niceproxy.core.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCPing：直接对节点的 `server:port` 做一次 TCP 握手并计时。
 *
 * 相比经由内核的「真连接延迟」，它有一个决定性的优势：**不需要内核在运行**。
 * 用户刚导入一批订阅想先挑个能用的节点时，还不需要先把代理跑起来。
 *
 * 代价是它只测到「能否连上入口」，不反映代理链路的真实质量 ——
 * 服务器活着但配置错误、密码不对，TCPing 一样是绿的。
 * 所以两种测速要并存，而不是互相取代。
 */
@Singleton
class LatencyTester @Inject constructor(
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    data class Target(val id: String, val host: String, val port: Int)

    /** 单个节点。返回毫秒；失败返回 null。 */
    suspend fun tcping(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int? =
        withContext(ioDispatcher) {
            withTimeoutOrNull(timeoutMs.toLong()) {
                runCatching {
                    val start = System.nanoTime()
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                    }
                    ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
                }.getOrNull()
            }
        }

    /**
     * 批量测速。
     *
     * 并发度必须有上限：一次性对几百个节点发起连接会耗尽文件描述符，
     * 在移动网络上还会因为瞬时连接数过高被运营商限速，反而测出虚高的延迟。
     */
    suspend fun tcpingAll(
        targets: List<Target>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onResult: suspend (id: String, latencyMs: Int?) -> Unit,
    ) = coroutineScope {
        val gate = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
        targets.map { target ->
            async {
                gate.withPermit {
                    onResult(target.id, tcping(target.host, target.port, timeoutMs))
                }
            }
        }.awaitAll()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val DEFAULT_CONCURRENCY = 16
        const val MAX_CONCURRENCY = 64
    }
}
