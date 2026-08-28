package com.niceproxy.core.service.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 「这个地址该不该告诉用户 / 该不该拿来判定监听还在不在」的全部规则。
 *
 * 从 [NetworkAddressDiscovery] 里拆出来只有一个理由：那一位要枚举
 * [java.net.NetworkInterface]，而它没有可用的公开构造器，于是整段判定逻辑在 JVM
 * 单测里碰都碰不到。而这段判定恰恰是会静默出错的那种 —— 多列一个地址，用户照着填
 * 进电脑发现连不上；少认一个地址，省电模式会把整个网关停掉。
 */
internal object LocalAddressRules {

    /**
     * 这个地址值不值得作为「在电脑上填这个」展示给用户。
     *
     * 排除项都是「看起来像个能用的 IP、实际上一定连不上」的那类：
     *
     * - **169.254.0.0/16（IPv4 链路本地）**。它的含义是「DHCP 没要到地址」，
     *   Android 上常见于以太网转接头刚插上、或者热点还没起来的那几秒。把它列出来
     *   等于告诉用户去填一个几秒钟后就会消失、且多半根本不通的地址。
     * - **fe80::/10（IPv6 链路本地）**。字面量必须带 `%scope` 后缀才能用，而那个
     *   后缀是**接收端**的接口名，抄到另一台设备上没有任何意义。
     * - **fec0::/10（已废弃的站点本地）**、多播、通配地址：同理，填了也连不上。
     *
     * 回环单独判断，由调用方通过 `includeLoopback` 决定，不在这里一刀切。
     */
    fun isPresentable(address: InetAddress): Boolean = when {
        address.isAnyLocalAddress -> false
        address.isMulticastAddress -> false
        address.isLinkLocalAddress -> false
        // isSiteLocalAddress 在 IPv4 上指的是 10/172.16/192.168 这些内网段，
        // 那恰恰是本应用最常用的地址；只有 IPv6 的 fec0::/10 才是该排除的废弃前缀
        address is Inet6Address && address.isSiteLocalAddress -> false
        else -> true
    }

    /**
     * 判定「省电模式该不该停机」时，这个地址算不算存在。
     *
     * 门槛必须比 [isPresentable] 松：那一位回答的是「能不能推荐给用户」，而这里
     * 回答的是「内核的监听套接字还有没有着落」。链路本地地址虽然没法推荐，却是完全
     * 可以监听的 —— 用户手动把入站绑到 `fe80::…` 之后，拿展示规则去判定存在性会
     * **永远**判为不存在，于是打开省电模式的那一刻整个网关就被停掉，而且连运行意图
     * 一起清了，看门狗都不会来救。
     */
    fun isBindable(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isMulticastAddress

    /**
     * 把 `hostAddress` 归一成可以直接和用户填的字符串比对的形式。
     *
     * IPv6 的 `hostAddress` 带 `%wlan0` 这样的 scope 后缀，而设置里存的是不带后缀的
     * 字面量；不剥掉的话两边永远对不上。
     */
    fun normalize(hostAddress: String?): String = hostAddress.orEmpty().substringBefore('%')

    /**
     * 这张接口上哪些全局 IPv6 属于「随时会换掉」的临时地址（RFC 4941 隐私扩展）。
     *
     * **说清楚这是个启发式，因为它只能是启发式。** 临时地址由内核的 `IFA_F_TEMPORARY`
     * 标记，而 `NetworkInterface` 根本不暴露地址标志位，Java 侧拿不到那一位。能观测到
     * 的只有一个信号：隐私扩展打开时，同一张接口上会**同时**存在同前缀的稳定地址和
     * 临时地址；只有一个全局 IPv6 的接口不可能开着隐私扩展。
     *
     * 于是规则是：同一个 /64 前缀下出现两个及以上全局 IPv6 时，这一组里必然混着临时
     * 地址，而 Java 侧分不出哪个是哪个 —— 所以整组一起标记为「不稳定」。宁可把稳定
     * 的那个也一并降级，也不要把一个几小时后就作废的地址推荐给用户去填：前者只是少
     * 推荐一个 IPv6（IPv4 照常展示），后者是「昨天还好好的，今天就连不上了」。
     */
    fun ephemeralIpv6(addresses: List<InetAddress>): Set<InetAddress> {
        val globals = addresses.filterIsInstance<Inet6Address>().filter { isPresentable(it) }
        if (globals.size < 2) return emptySet()
        return globals
            .groupBy { prefix64(it) }
            .values
            .filter { it.size >= 2 }
            .flatten()
            .toSet()
    }

    fun classify(interfaceName: String): InterfaceKind {
        val lower = interfaceName.lowercase()
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

    fun isIpv6(address: InetAddress): Boolean = address is Inet6Address

    fun isIpv4(address: InetAddress): Boolean = address is Inet4Address

    /** 前 64 位就是 IPv6 的子网前缀，隐私扩展生成的临时地址与稳定地址共用它。 */
    private fun prefix64(address: Inet6Address): List<Byte> = address.address.take(IPV6_PREFIX_BYTES)

    private const val IPV6_PREFIX_BYTES = 8
}
