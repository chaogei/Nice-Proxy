package com.niceproxy.core.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.niceproxy.core.model.NetworkPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

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
 * 见 docs/DESIGN.md §6.7。
 */
@Singleton
class NetworkBinder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun apply(preference: NetworkPreference) {
        val manager = connectivityManager ?: return
        release()

        if (preference == NetworkPreference.AUTO) {
            manager.bindProcessToNetwork(null)
            return
        }

        val transport = when (preference) {
            NetworkPreference.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkPreference.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkPreference.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            NetworkPreference.AUTO -> return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                // 指定的网络断了就回落到系统默认，总比彻底断网强。
                manager.bindProcessToNetwork(null)
            }
        }
        callback = newCallback
        runCatching { manager.requestNetwork(request, newCallback) }
            .onFailure { callback = null }
    }

    fun release() {
        val manager = connectivityManager ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
        manager.bindProcessToNetwork(null)
    }

    /**
     * 默认网络的变化流。
     *
     * QUIC 连接（Hysteria2 / TUIC）在网络切换后无法自愈，必须重启内核；
     * 监听地址也会随之变化，首页需要刷新。见 docs/DESIGN.md 风险 R-6。
     */
    fun defaultNetworkChanges(): Flow<Network?> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        manager.registerDefaultNetworkCallback(cb)
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
}
