package com.niceproxy.core.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.network.concurrent.BoundedWorkers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

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

    /** 测速失败的原因。区分它们才能给出有用的提示，而不是一律「超时」。 */
    enum class Failure {
        /** 域名解析不出来。多半是本机 DNS 有问题，或者机场把域名撤了。 */
        DNS,

        /** 解析到了地址，但握不上手：端口没开、被墙、路由不可达。 */
        CONNECT,

        /** 预算用完。解析或握手没报错，只是太久了。 */
        TIMEOUT,
    }

    /**
     * 一次测速的完整结局。
     *
     * 拆出 [dnsMillis] 不是为了好看：TCPing 慢有两种截然不同的原因，而它们的对策
     * 完全相反 —— 解析慢要换 DNS，握手慢要换节点。只回一个总毫秒数的话，
     * 用户对着一个 2000 ms 的红色数字换了一圈节点，其实是本机 DNS 在拖后腿。
     */
    data class Probe(
        val latencyMs: Int?,
        val dnsMillis: Int = 0,
        val connectMillis: Int = 0,
        val failure: Failure? = null,
    ) {
        val isSuccess: Boolean get() = latencyMs != null
    }

    /**
     * 与本单例同生命周期的作用域，只用来跑 DNS 解析。
     *
     * 必须独立于调用方：`InetAddress.getByName` 是**阻塞且不可取消**的，协程取消
     * 打不断它。挂在调用方的作用域下，一次卡住的解析就会把整批测速连同它所在的
     * worker 一起钉死；挂在这里，超时的那次解析只是被放弃（线程继续阻塞到系统
     * 解析器自己超时），worker 立刻就能去测下一个节点。
     */
    private val resolverScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * 建 socket 用的工厂。
     *
     * 默认是系统默认工厂，也就是「跟着系统当前的默认网络走」。当用户在设置里指定了
     * 出口网络（只走 Wi-Fi / 只走蜂窝），宿主应当把 `Network.getSocketFactory()`
     * 交给这里 —— 否则测速走的是默认网络，而真实流量走的是另一张网卡，
     * **测出来的延迟和用户实际体验到的根本不是同一条链路**。
     *
     * 用 [SocketFactory] 而不是暴露一个 `Network`：那是 Android 平台类型，
     * 摆进这一层会让整个测速逻辑再也没法在 JVM 上单测。
     */
    @Volatile
    private var socketFactory: SocketFactory = SocketFactory.getDefault()

    /** 传 null 恢复成系统默认网络。由掌握 `Network` 的那一层（NetworkBinder）调用。 */
    fun bindTo(factory: SocketFactory?) {
        socketFactory = factory ?: SocketFactory.getDefault()
    }

    /** 单个节点。返回毫秒；失败返回 null。 */
    suspend fun tcping(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int? =
        probe(host, port, timeoutMs).latencyMs

    /** 同 [tcping]，但带上失败原因与分段耗时。 */
    suspend fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        resolver: HostResolver = SystemHostResolver,
    ): Probe = runProbe(host, port, timeoutMs, ResolverGroup(resolver, resolverScope))

    /**
     * 批量测速。
     *
     * 并发度必须有上限：一次性对几百个节点发起连接会耗尽文件描述符，
     * 在移动网络上还会因为瞬时连接数过高被运营商限速，反而测出虚高的延迟。
     *
     * 用 [BoundedWorkers] 而不是 `targets.map { async { gate.withPermit { … } } }`：
     * 后者的并发度虽然也被信号量压住了，但**协程是一次性全建出来的** ——
     * 一千个节点就是一千个 `Deferred` 加一千个信号量等待者当场躺在堆上，
     * 而它们中的绝大多数在接下来几十秒里什么也不做。低端机上这一下 GC 尖峰
     * 足以让界面明显一顿，而症状看起来像是「点了测速就卡」。
     */
    suspend fun tcpingAll(
        targets: List<Target>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        resolver: HostResolver = SystemHostResolver,
        onResult: suspend (id: String, latencyMs: Int?) -> Unit,
    ) = probeAll(targets, concurrency, timeoutMs, resolver) { id, probe ->
        onResult(id, probe.latencyMs)
    }

    /** 同 [tcpingAll]，但回调拿到的是完整的 [Probe]。 */
    suspend fun probeAll(
        targets: List<Target>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        resolver: HostResolver = SystemHostResolver,
        onResult: suspend (id: String, probe: Probe) -> Unit,
    ) {
        if (targets.isEmpty()) return
        // 同一批里重复出现的主机只解析一次：机场常见几十个节点共用一个域名，
        // 每个都独立解析一遍纯属浪费，还会平白放大对 DNS 服务器的压力。
        val group = ResolverGroup(resolver, resolverScope)
        BoundedWorkers.forEach(targets, concurrency.coerceIn(1, MAX_CONCURRENCY)) { target ->
            onResult(target.id, runProbe(target.host, target.port, timeoutMs, group))
        }
    }

    /**
     * 解析与握手分两段计时，各有各的预算。
     *
     * 原来的写法是 `socket.connect(InetSocketAddress(host, port), timeoutMs)`，
     * 有两个问题：
     *
     * 1. `InetSocketAddress(host, port)` 在**构造时**就同步做了 DNS 解析，
     *    那段时间根本不在 `connect` 的超时管辖之内。部分 ROM 上 DNS 卡住十几秒
     *    的情况并不罕见，而用户看到的是一个远超设定超时的「3000 ms」。
     * 2. 解析耗时被算进了延迟数字里。同一个机场的几十个节点共用一个域名，
     *    第一个被测的那个会因为背了整段解析时间而显得特别慢，用户于是避开它 ——
     *    完全是误导。
     */
    private suspend fun runProbe(
        host: String,
        port: Int,
        timeoutMs: Int,
        group: ResolverGroup,
    ): Probe {
        val budget = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
        val startedAt = System.nanoTime()

        val resolved = withTimeoutOrNull(budget.toLong()) { group.resolve(host) }
        val dnsMillis = elapsedMs(startedAt)
        if (resolved == null) {
            return Probe(null, dnsMillis = dnsMillis, failure = Failure.TIMEOUT)
        }
        val address = resolved.getOrElse {
            return Probe(null, dnsMillis = dnsMillis, failure = Failure.DNS)
        }

        val remaining = budget - dnsMillis
        if (remaining <= 0) {
            return Probe(null, dnsMillis = dnsMillis, failure = Failure.TIMEOUT)
        }
        return withContext(ioDispatcher) { connect(address, port, remaining, dnsMillis) }
    }

    /**
     * 握手这一段可以放心地阻塞：`connect` 的超时是操作系统层面的，一定会到点返回，
     * 不像 DNS 那样能无限期卡住。
     */
    private fun connect(address: InetAddress, port: Int, budgetMs: Int, dnsMillis: Int): Probe {
        val startedAt = System.nanoTime()
        return try {
            socketFactory.createSocket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), budgetMs)
            }
            val connectMillis = elapsedMs(startedAt)
            Probe(
                // 报出去的是**握手**耗时，不含解析：那才是这条链路的真实 RTT。
                // 至少记 1 ms，否则同机房的节点会显示成 0，看起来像是没测
                latencyMs = connectMillis.coerceAtLeast(1),
                dnsMillis = dnsMillis,
                connectMillis = connectMillis,
            )
        } catch (t: Throwable) {
            val connectMillis = elapsedMs(startedAt)
            Probe(
                latencyMs = null,
                dnsMillis = dnsMillis,
                connectMillis = connectMillis,
                failure = if (connectMillis >= budgetMs) Failure.TIMEOUT else Failure.CONNECT,
            )
        }
    }

    private fun elapsedMs(sinceNanos: Long): Int =
        ((System.nanoTime() - sinceNanos) / NANOS_PER_MILLI).toInt()

    /** 主机名到地址的解析。抽成接口只为可测：真实 DNS 在单测里既慢又不可控。 */
    fun interface HostResolver {
        /** 会阻塞。由 [ResolverGroup] 负责把它放到 IO 上并加超时。 */
        @Throws(UnknownHostException::class)
        fun resolve(host: String): InetAddress
    }

    object SystemHostResolver : HostResolver {
        override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
    }

    /**
     * 一批测速内共享的解析。同一个主机名只会真正解析一次，后来者复用同一个 [Deferred]。
     *
     * 只在单批内有效，不做跨批缓存：节点 IP 变了（机场换机、DNS 轮询到另一台）之后
     * 还拿旧地址去测，测出来的绿色是假的，而这种错误没有任何办法自愈。
     */
    private class ResolverGroup(
        private val delegate: HostResolver,
        private val scope: CoroutineScope,
    ) {
        private val pending = ConcurrentHashMap<String, Deferred<Result<InetAddress>>>()

        /**
         * 结果用 [Result] 包着而不是让 [Deferred] 以异常结束：后者在多个 worker
         * 同时 await 同一个失败的解析时，会把同一个异常实例抛给每一个等待者，
         * 而那个实例的堆栈来自第一个调用点 —— 排查时指向的是一个无关的节点。
         */
        suspend fun resolve(host: String): Result<InetAddress> =
            pending.computeIfAbsent(host) { name ->
                scope.async { runCatching { delegate.resolve(name) } }
            }.await()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val DEFAULT_CONCURRENCY = 16
        const val MAX_CONCURRENCY = 64

        /** 再小的预算连一次本机握手都不够，只会把所有节点都测成超时。 */
        const val MIN_TIMEOUT_MS = 100

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
