package com.niceproxy.core.service.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可供局域网客户端连接的本机地址。 */
data class LocalAddress(
    val kind: InterfaceKind,
    val interfaceName: String,
    val address: String,
    val isIpv6: Boolean,
    /**
     * 这个地址随时可能被系统换掉，不适合让用户抄到别的设备上长期使用。
     *
     * 目前只有 IPv6 隐私扩展的临时地址会命中，判定依据见
     * [LocalAddressRules.ephemeralIpv6]。
     */
    val ephemeral: Boolean = false,
) {
    /** IPv6 字面量在 URL 与代理配置中必须用方括号包裹。 */
    val hostForUrl: String get() = if (isIpv6) "[$address]" else address
}

enum class InterfaceKind(val label: String, val priority: Int) {
    HOTSPOT("热点", 0),
    WIFI("Wi-Fi", 1),
    ETHERNET("以太网", 2),
    USB_TETHER("USB 网络共享", 3),
    CELLULAR("蜂窝数据", 4),
    OTHER("其他", 5),
    LOOPBACK("本机", 6),
}

/**
 * 枚举本机所有可用于监听的地址。
 *
 * 刻意使用 [NetworkInterface] 而不是 `WifiManager.getConnectionInfo()`：
 * 后者从 Android 10 起需要定位权限才能返回有效信息，而且拿不到热点接口的地址 ——
 * 恰恰热点是本应用最主要的使用场景。见 docs/DESIGN.md §6.6。
 *
 * 「哪些地址算数」的规则全在 [LocalAddressRules] 里，这里只负责枚举。
 */
@Singleton
class NetworkAddressDiscovery @Inject constructor(
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param includeEphemeral 是否把 IPv6 隐私扩展的临时地址也列出来。默认不列 ——
     *        这个列表的用途是「在电脑上填这个」，而临时地址过几小时就换一个，
     *        用户第二天回来只会看到连不上，且完全无从判断原因。
     */
    suspend fun discover(
        includeIpv6: Boolean = true,
        includeLoopback: Boolean = false,
        includeEphemeral: Boolean = false,
    ): List<LocalAddress> = withContext(ioDispatcher) {
        enumerate()
            .asSequence()
            .flatMap { (nif, addresses) ->
                val kind = LocalAddressRules.classify(nif)
                if (kind == InterfaceKind.LOOPBACK && !includeLoopback) {
                    return@flatMap emptySequence()
                }
                val ephemeral = LocalAddressRules.ephemeralIpv6(addresses)
                addresses.asSequence()
                    .filter { LocalAddressRules.isPresentable(it) }
                    .filter { includeIpv6 || !LocalAddressRules.isIpv6(it) }
                    .filter { includeEphemeral || it !in ephemeral }
                    .map { addr ->
                        LocalAddress(
                            kind = kind,
                            interfaceName = nif,
                            address = LocalAddressRules.normalize(addr.hostAddress),
                            isIpv6 = LocalAddressRules.isIpv6(addr),
                            ephemeral = addr in ephemeral,
                        )
                    }
            }
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address }
            .sortedWith(compareBy({ it.kind.priority }, { it.isIpv6 }, { it.address }))
            .toList()
    }

    /**
     * 这批监听地址里，哪些已经从设备上消失了。供「省电模式」判断是否该停机（FR-5.4）。
     *
     * 一次枚举回答全部地址，而不是每个地址各枚举一遍：`getNetworkInterfaces()` 要
     * 读一遍 `/proc/net`，在多网卡（Wi-Fi + 热点 + 蜂窝 + USB 共享同时在）的设备上
     * 并不便宜，而这个判定跑在每一次网络变化之后。
     *
     * 判定用的是 [LocalAddressRules.isBindable] 而不是展示那套规则，理由见那里 ——
     * 用展示规则的话，绑在链路本地地址上的入站会被永远判为「地址已消失」，
     * 打开省电模式就等于立刻永久停机。
     */
    suspend fun missingAddresses(watched: Collection<String>): List<String> {
        if (watched.isEmpty()) return emptyList()
        val present = withContext(ioDispatcher) {
            enumerate()
                .flatMap { (_, addresses) -> addresses }
                .filter { LocalAddressRules.isBindable(it) }
                .mapTo(mutableSetOf()) { LocalAddressRules.normalize(it.hostAddress) }
        }
        return watched.filterNot { it in present }
    }

    /** 某个地址是否还存在于设备上。 */
    suspend fun isAddressPresent(address: String): Boolean =
        missingAddresses(listOf(address)).isEmpty()

    /**
     * 枚举所有已启用接口及其地址。
     *
     * 单个接口读地址时抛异常不该让整份列表作废：热点刚关掉的那一瞬间，接口还在列表
     * 里但已经读不出地址了，让它整个抛出去的话，首页会突然一个地址都不剩。
     */
    private fun enumerate(): List<Pair<String, List<InetAddress>>> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrNull()
            ?.toList()
            ?: return emptyList()

        return interfaces.mapNotNull { nif ->
            val up = runCatching { nif.isUp }.getOrDefault(false)
            if (!up) return@mapNotNull null
            val addresses = runCatching { nif.inetAddresses.toList() }.getOrDefault(emptyList())
            nif.name to addresses
        }
    }
}
