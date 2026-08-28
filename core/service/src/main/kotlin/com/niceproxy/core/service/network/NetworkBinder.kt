package com.niceproxy.core.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.niceproxy.core.model.NetworkPreference
import com.niceproxy.core.network.LatencyTester
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 出站到底绑上了没有。
 *
 * 存在的理由是「宣称 Running 却根本没绑上」是本模块最贵的一种静默失败：用户在设置里
 * 选了「只走蜂窝」，请求没被系统受理、或者那张网压根没出现，出站就老老实实跑在 Wi-Fi
 * 上 —— 流量、计费、可达性全不是他要的，而界面上一切正常。
 */
sealed interface OutboundBinding {

    /** 用户选的是「自动」，本来就不该有进程绑定。 */
    data object Unbound : OutboundBinding

    /** 已经绑上 [preference] 对应的网卡。 */
    data class Bound(val preference: NetworkPreference) : OutboundBinding

    /**
     * 用户要求绑到 [preference]，但现在没绑上，出站正跑在系统默认网络上。
     *
     * @param reason 给用户看的一句话，不是给日志看的。
     */
    data class Unavailable(
        val preference: NetworkPreference,
        val reason: String,
    ) : OutboundBinding
}

/**
 * 控制出站流量走哪张网卡。
 *
 * sing-box 出站的 `bind_interface` 依赖 `SO_BINDTODEVICE`，需要 `CAP_NET_RAW`，
 * 非 root 的 Android 上不可用。因此接口绑定必须由宿主完成。
 *
 * 这里用的是 [ConnectivityManager.bindProcessToNetwork] —— 它把**整个进程**新建的
 * socket 绑定到指定网络。因为 Go 内核与 Kotlin 同进程，sing-box 的所有出站连接
 * 自动生效，Go 侧一行代码都不用改。监听套接字不受影响，`0.0.0.0` 上的 listener
 * 依然接受来自所有接口的连接，热点客户端可以正常接入。
 *
 * 进程绑定只覆盖走内核的那部分流量。**不经过内核的探测另有一条路**，
 * 见 [OutboundNetworkSelection]。
 *
 * 见 docs/DESIGN.md §6.7。
 */
@Singleton
class NetworkBinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val latencyTester: LatencyTester,
) {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    /**
     * 保护 [callback]、[requested] 与 [firstAvailable] 三者的一致性。
     *
     * 需要一把锁是因为回调线程与调用线程不是同一个：`onAvailable` 跑在
     * ConnectivityManager 自己的线程上，而 [apply] / [release] 来自服务的协程。
     * 没有这把锁，「注册完还没记下 callback，回调就先到了」这一下会让
     * [release] 漏掉一个已经注册出去的回调 —— 那是个永久泄漏，且它还会继续
     * 改写进程绑定。
     */
    private val lock = Any()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 当前生效的偏好。null 表示 AUTO 或已释放，此时不该发布任何绑定告警。 */
    private var requested: NetworkPreference? = null

    /** [apply] 用它等第一次 onAvailable，见那里的说明。 */
    private var firstAvailable: CompletableDeferred<Unit>? = null

    private val _binding = MutableStateFlow<OutboundBinding>(OutboundBinding.Unbound)

    /**
     * 出站绑定的实时状态。
     *
     * 与 [apply] 的返回值不是一回事：那一位只回答「启动这一刻绑上了没有」，而网卡是
     * 会中途消失的 —— 网线被拔、副卡掉网、热点关掉。那之后 [OutboundNetworkSelection]
     * 会回落到系统默认，出站于是偷偷换了一条链路，这条流就是唯一说得出这件事的地方。
     */
    val binding: StateFlow<OutboundBinding> = _binding.asStateFlow()

    /**
     * 选哪张网、以及选定之后要通知谁，全在这里面。整个 binder 共用一个实例，
     * 于是「上一轮 apply 留下的绑定」与「这一轮的选择」经过的是同一个状态机。
     */
    private val selection = OutboundNetworkSelection<Network>(
        latencyTester = latencyTester,
        socketFactoryOf = { network -> network.socketFactory },
        bindProcess = { network -> connectivityManager?.bindProcessToNetwork(network) },
        onSelected = ::onSelectionSettled,
    )

    /**
     * 按用户偏好绑定出站网卡，并**等到真的绑上为止**。
     *
     * 以前这里是发完 `requestNetwork` 就返回。两个后果，都是静默的：
     *
     * 1. `requestNetwork` 抛异常（系统未受理、并发请求数超限）时只是把字段清成 null，
     *    调用方拿不到任何信号，照样把状态改成 Running。
     * 2. 就算受理了，`onAvailable` 也是异步来的。内核在那之前就起来了，它建的每一个
     *    出站 socket 都落在系统默认网络上 —— `bindProcessToNetwork` 只影响**之后**
     *    新建的 socket，绑定迟到等于对已经建好的连接完全无效。QUIC 尤其致命：
     *    Hysteria2 / TUIC 会一直用那条建错网卡的 UDP socket，直到下一次重启内核。
     *
     * 所以现在等，而且等不到就如实说。等待上界不能太长：网卡真的不存在时（选了以太网
     * 却没插转接头），每次启动都干等十几秒只会让用户以为应用卡死了。
     *
     * @return 这一次的绑定结局。[OutboundBinding.Unavailable] 表示出站没有按用户的
     *         要求走，调用方必须让它可见，不能当成成功。
     */
    suspend fun apply(
        preference: NetworkPreference,
        awaitMillis: Long = BIND_AWAIT_MS,
    ): OutboundBinding {
        // release 已经把绑定收回到系统默认，AUTO 到此为止
        release()
        if (preference == NetworkPreference.AUTO) return publish(OutboundBinding.Unbound)

        val manager = connectivityManager
            ?: return publish(OutboundBinding.Unavailable(preference, "取不到系统网络服务"))

        val request = NetworkRequest.Builder()
            .addTransportType(transportOf(preference))
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val signal = CompletableDeferred<Unit>()
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                selection.onAvailable(network)
                signal.complete(Unit)
            }

            override fun onLost(network: Network) {
                // 掉的若是当前这张，[OutboundNetworkSelection] 会回落到另一张
                // 仍然活着的同类网络，都没有了才回到系统默认 —— 总比彻底断网强。
                selection.onLost(network)
            }

            /**
             * 只有带超时的 `requestNetwork` 才会回调它；这里不用那个重载，
             * 留着覆写是为了万一系统仍然发过来时能立刻结束等待，而不是干等满。
             */
            override fun onUnavailable() {
                signal.complete(Unit)
            }
        }

        // 先记下再注册：反过来的话，回调可能赶在赋值之前到达，
        // 那一瞬间发生的 release 就会漏掉这个已经注册出去的回调
        synchronized(lock) {
            callback = newCallback
            requested = preference
            firstAvailable = signal
        }

        val accepted = runCatching { manager.requestNetwork(request, newCallback) }
        if (accepted.isFailure) {
            Log.w(TAG, "系统未受理 ${preference.name} 的网卡绑定请求", accepted.exceptionOrNull())
            synchronized(lock) {
                callback = null
                requested = null
                firstAvailable = null
            }
            return publish(OutboundBinding.Unavailable(preference, "系统未受理网卡绑定请求"))
        }

        withTimeoutOrNull(awaitMillis) { signal.await() }

        // 以状态机里真正选中的那张网为准，而不是以 signal 有没有完成为准：
        // 网络可能在 onAvailable 之后、这一行之前就又掉了
        return publish(
            if (selection.current != null) {
                OutboundBinding.Bound(preference)
            } else {
                OutboundBinding.Unavailable(preference, "当前没有可用的${preference.displayName}")
            },
        )
    }

    fun release() {
        val toUnregister = synchronized(lock) {
            val previous = callback
            callback = null
            // 先清掉偏好再 clear：clear 会触发 onSelected(null)，
            // 而「用户主动解除绑定」不该被当成一次绑定丢失去告警
            requested = null
            firstAvailable = null
            previous
        }
        val manager = connectivityManager
        if (manager != null && toUnregister != null) {
            runCatching { manager.unregisterNetworkCallback(toUnregister) }
                .onFailure { Log.w(TAG, "注销网络回调失败", it) }
        }
        selection.clear()
        _binding.value = OutboundBinding.Unbound
    }

    /**
     * 默认网络的变化流。
     *
     * QUIC 连接（Hysteria2 / TUIC）在网络切换后无法自愈，必须重启内核；
     * 监听地址也会随之变化，首页需要刷新。见 docs/DESIGN.md 风险 R-6。
     *
     * **`onLost` 不能无条件发 null，这是一个会丢事件的写法。** 切网时系统给的顺序
     * 常常是「新网络 onAvailable → 旧网络 onLost」，而这条流是 [conflate] 的：
     * 收集方稍微慢一点，那一对事件就只剩下最后的 null，于是「已经换到蜂窝了」这件事
     * 被整个吃掉，内核不重启，QUIC 继续钉在那张已经没了的 Wi-Fi 上。现象是切网之后
     * 代理彻底不通，而且要等到下一次网络抖动才可能自己好。
     *
     * 现在只在「掉的正是我们最后报出去的那张」时才发 null，于是无论收集方多慢，
     * 合并之后剩下的那个值永远是当前真实的默认网络。
     */
    fun defaultNetworkChanges(): Flow<Network?> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            close(IllegalStateException("取不到 ConnectivityManager，无法监听默认网络变化"))
            return@callbackFlow
        }

        // 只在回调线程上访问，ConnectivityManager 保证回调是串行的
        var reported: Network? = null

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reported = network
                trySend(network)
            }

            override fun onLost(network: Network) {
                if (network != reported) return
                reported = null
                trySend(null)
            }
        }

        // 注册本身会抛：部分 ROM 在权限被裁剪时抛 SecurityException，
        // 而这条流是用 launchIn 收集的，漏出去就是一次服务崩溃
        runCatching { manager.registerDefaultNetworkCallback(cb) }
            .onFailure {
                close(it)
                return@callbackFlow
            }
        awaitClose { runCatching { manager.unregisterNetworkCallback(cb) } }
    }.conflate()

    /**
     * 是否有其他 VPN 处于活动状态。
     *
     * 此时本应用的出站流量会被那个 VPN 捕获，非 root 无法绕过 ——
     * 这也是 Every Proxy 需要单独发布一个 Network Bridge 伴侣应用的原因。
     * 我们不做规避，只在 UI 上提示。见 docs/DESIGN.md §10 P-10。
     */
    fun isOtherVpnActive(): Boolean {
        val manager = connectivityManager ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * 选择落定时更新 [binding]。
     *
     * 这是**运行期**那一半：热点关掉、网线拔掉、副卡掉网都不会经过 [apply]，
     * 只会在这里表现为一次 network == null。
     */
    private fun onSelectionSettled(network: Network?) {
        val preference = synchronized(lock) { requested } ?: return
        _binding.value = if (network != null) {
            OutboundBinding.Bound(preference)
        } else {
            OutboundBinding.Unavailable(preference, "${preference.displayName}已不可用")
        }
    }

    private fun publish(binding: OutboundBinding): OutboundBinding {
        _binding.value = binding
        return binding
    }

    private fun transportOf(preference: NetworkPreference): Int = when (preference) {
        NetworkPreference.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
        NetworkPreference.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
        NetworkPreference.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
        // AUTO 在 apply 里就返回了，走不到这里
        NetworkPreference.AUTO -> error("AUTO 没有对应的 transport")
    }

    private companion object {
        const val TAG = "NetworkBinder"

        /**
         * 等第一次 onAvailable 的上界。
         *
         * 网卡已经连着时这一步是毫秒级的；真要等满，多半意味着那张网根本不存在
         * （选了以太网却没插转接头、副卡没开数据）。再长只是让用户多盯几秒转圈，
         * 而结论早就定了。
         */
        const val BIND_AWAIT_MS = 6_000L
    }
}
