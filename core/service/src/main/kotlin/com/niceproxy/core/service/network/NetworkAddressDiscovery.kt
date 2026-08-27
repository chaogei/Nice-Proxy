package com.niceproxy.core.service.network

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可供局域网客户端连接的本机地址。 */
data class LocalAddress(
    val kind: InterfaceKind,
    val interfaceName: String,
    val address: String,
    val isIpv6: Boolean,
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
 */
@Singleton
class NetworkAddressDiscovery @Inject constructor(
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun discover(includeIpv6: Boolean = true, includeLoopback: Boolean = false): List<LocalAddress> =
        withContext(ioDispatcher) {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
                .getOrNull()
                ?.toList()
                ?: return@withContext emptyList()

            interfaces
                .asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { nif ->
                    val kind = classify(nif.name)
                    if (kind == InterfaceKind.LOOPBACK && !includeLoopback) {
                        return@flatMap emptySequence()
                    }
                    nif.inetAddresses.asSequence().mapNotNull { addr ->
                        when {
                            addr is Inet4Address -> LocalAddress(
                                kind = kind,
                                interfaceName = nif.name,
                                address = addr.hostAddress.orEmpty(),
                                isIpv6 = false,
                            )
                            // 链路本地地址（fe80::）带 %scope 后缀，
                            // 客户端无法直接使用，过滤掉以免误导用户。
                            includeIpv6 && addr is Inet6Address && !addr.isLinkLocalAddress ->
                                LocalAddress(
                                    kind = kind,
                                    interfaceName = nif.name,
                                    address = addr.hostAddress.orEmpty().substringBefore('%'),
                                    isIpv6 = true,
                                )
                            else -> null
                        }
                    }
                }
                .filter { it.address.isNotBlank() }
                .distinctBy { it.address }
                .sortedWith(compareBy({ it.kind.priority }, { it.isIpv6 }, { it.address }))
                .toList()
        }

    /** 某个地址是否还存在于设备上，供「省电模式」判断是否该停止服务（FR-5.4）。 */
    suspend fun isAddressPresent(address: String): Boolean =
        discover(includeLoopback = true).any { it.address == address }

    private fun classify(name: String): InterfaceKind {
        val lower = name.lowercase()
        return when {
            lower == "lo" -> InterfaceKind.LOOPBACK
            // 热点接口在各家 ROM 上命名不一，wlan1 通常是与 wlan0 并存的 AP 接口
            lower.startsWith("ap") || lower.startsWith("swlan") ||
                lower.startsWith("softap") || lower == "wlan1" -> InterfaceKind.HOTSPOT
            lower.startsWith("wlan") -> InterfaceKind.WIFI
            lower.startsWith("eth") -> InterfaceKind.ETHERNET
            lower.startsWith("usb") || lower.startsWith("rndis") -> InterfaceKind.USB_TETHER
            lower.startsWith("rmnet") || lower.startsWith("ccmni") ||
                lower.startsWith("pdp") -> InterfaceKind.CELLULAR
            else -> InterfaceKind.OTHER
        }
    }
}
