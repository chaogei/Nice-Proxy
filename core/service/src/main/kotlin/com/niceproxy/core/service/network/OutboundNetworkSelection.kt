package com.niceproxy.core.service.network

import com.niceproxy.core.network.LatencyTester
import javax.net.SocketFactory

/**
 * 「出站现在走哪一张网」的状态机，以及那张网定下来之后要通知谁。
 *
 * 从 [NetworkBinder] 里拆出来有两个理由。
 *
 * **一是这段逻辑没有看上去那么显然。** `requestNetwork` 是按 transport 请求的，
 * 同一个 transport 下完全可能同时存在两张网络 —— Wi-Fi 漫游到另一个 AP、双卡
 * 之间切换、蜂窝在 4G/5G 之间重建 —— 此时**旧网络的 onLost 会晚于新网络的
 * onAvailable 到达**。无条件地在 onLost 里回落到系统默认，就会把刚绑好的新网络
 * 又解掉：用户设的「只走蜂窝」形同虚设，而界面上没有任何迹象。
 *
 * **二是不拆出来就测不了。** `ConnectivityManager` 与 `Network` 都没有可用的公开
 * 构造器，而这个项目的单测跑在 JVM 上、不引 Robolectric。泛型参数 [T] 就是为此
 * 存在的：真实调用方传 `Network`，测试传一个普通对象。
 *
 * @param socketFactoryOf 取这张网络专属的 socket 工厂，即 `Network.getSocketFactory()`。
 * @param bindProcess 把整个进程新建的 socket 绑到这张网上，即
 *        `ConnectivityManager.bindProcessToNetwork`。传 null 表示回到系统默认。
 * @param onSelected 每一次**落定**都会通知，包括回落到 null。用户挑了「只走蜂窝」
 *        而蜂窝掉了的时候，这里传出去的 null 就是「出站已经悄悄退回系统默认网络」——
 *        没有这个回调，那件事在界面上没有任何迹象，而它恰恰是用户设这个偏好想避免的。
 *        回调在锁内同步执行，实现里不许阻塞或反过来调本类的方法。
 */
internal class OutboundNetworkSelection<T : Any>(
    private val latencyTester: LatencyTester,
    private val socketFactoryOf: (T) -> SocketFactory?,
    private val bindProcess: (T?) -> Unit,
    private val onSelected: (T?) -> Unit = {},
) {

    private val lock = Any()

    /** 当前还活着的候选网络，按 onAvailable 的先后排列，末位是最新的一张。 */
    private val live = ArrayList<T>()

    private var selected: T? = null

    val current: T? get() = synchronized(lock) { selected }

    fun onAvailable(network: T) = synchronized(lock) {
        // 后来居上：系统刚拉起来的那张通常才是用户想要的（换了 AP、插上了网线）
        live.remove(network)
        live += network
        select(network)
    }

    fun onLost(network: T) = synchronized(lock) {
        live.remove(network)
        // 掉的不是当前这张就不用动。动了反而是一次无谓的重绑，
        // 而重绑期间新建的连接会短暂地跑到系统默认网络上去
        if (selected == network) select(live.lastOrNull())
    }

    /**
     * 不再有出站偏好（用户改回「自动」，或者服务停了）。
     *
     * 无条件下发，不看当前选中的是谁：`release` 完全可能发生在 `requestNetwork`
     * 之后、任何一次 onAvailable 之前，而那期间上一轮留下的进程绑定仍在生效。
     */
    fun clear() = synchronized(lock) {
        live.clear()
        selected = null
        apply(null)
    }

    private fun select(network: T?) {
        // 同一张网的重复通告（能力变化、断了又以同一 netId 回来）不必重走一遍：
        // bindProcessToNetwork 是跨进程调用，而换 socket 工厂会让正在跑的那批
        // 测速中途改用另一个工厂，同一次「全部测速」里的数字于是不再可比
        if (network == selected) return
        selected = network
        apply(network)
    }

    /**
     * 选中的网络要同时作用到两条路径上，缺一条都会有流量走错网卡。
     *
     * - 进程绑定管的是**新建**的 socket。Go 内核与 Kotlin 同进程，sing-box 的出站
     *   靠它生效（`bind_interface` 需要 `CAP_NET_RAW`，非 root 的 Android 上用不了）。
     * - TCPing 测速得单独交代。它刻意**不经过内核**（见 [LatencyTester] 的说明），
     *   所以用户最常用它的时刻恰恰是代理还没开的时候 —— 那时候进程绑定根本不存在，
     *   测速会老老实实走系统默认网络，测出来的延迟和用户开了代理之后实际体验到的
     *   不是同一条链路，据此挑节点等于抛硬币。
     *
     * 在锁内下发是有意的：这两个调用必须与 [selected] 的变化保持同一个顺序，
     * 否则两次网络切换挤在一起时，最后落定的绑定可能来自那张已经不在的网。
     */
    private fun apply(network: T?) {
        bindProcess(network)
        latencyTester.bindTo(network?.let(socketFactoryOf))
        onSelected(network)
    }
}
