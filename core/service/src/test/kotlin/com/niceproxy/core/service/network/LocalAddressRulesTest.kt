package com.niceproxy.core.service.network

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * 地址判定。
 *
 * 两个方向都会静默出错，而且错法完全不对称：
 *
 * - **多列一个地址**，用户照着填进电脑，发现连不上，然后去怀疑代理本身。
 * - **少认一个地址**，省电模式会判定「监听地址已消失」，把整个网关停掉 ——
 *   而且连运行意图一起清掉，看门狗都不会来救。
 */
internal class LocalAddressRulesTest {

    @Nested
    @DisplayName("哪些地址值得展示给用户")
    inner class Presentable {

        @Test
        @DisplayName("内网 IPv4 是主力场景，一个都不能漏")
        fun keepsPrivateIpv4() {
            listOf("192.168.1.5", "10.0.0.2", "172.20.3.4", "43.10.2.1").forEach {
                assertThat(LocalAddressRules.isPresentable(ip(it))).isTrue()
            }
        }

        @Test
        @DisplayName("169.254/16 不展示 —— 它的含义是「DHCP 没要到地址」")
        fun dropsIpv4LinkLocal() {
            // 以太网转接头刚插上、热点还没起来的那几秒会冒出这个地址。
            // 列出来等于让用户去填一个几秒后就消失、且多半根本不通的 IP。
            assertThat(LocalAddressRules.isPresentable(ip("169.254.10.20"))).isFalse()
        }

        @Test
        @DisplayName("fe80::/10 不展示 —— 没有 %scope 后缀用不了，带了又只对本机有意义")
        fun dropsIpv6LinkLocal() {
            assertThat(LocalAddressRules.isPresentable(ip("fe80::1"))).isFalse()
        }

        @Test
        @DisplayName("fec0::/10 不展示 —— 早就废弃了")
        fun dropsDeprecatedSiteLocal() {
            assertThat(LocalAddressRules.isPresentable(ip("fec0::1"))).isFalse()
        }

        @Test
        @DisplayName("ULA 与全局 IPv6 照常展示")
        fun keepsUsableIpv6() {
            assertThat(LocalAddressRules.isPresentable(ip("fd00::1"))).isTrue()
            assertThat(LocalAddressRules.isPresentable(ip("2001:db8::1"))).isTrue()
        }

        @Test
        @DisplayName("通配与多播地址不展示")
        fun dropsWildcardAndMulticast() {
            assertThat(LocalAddressRules.isPresentable(ip("0.0.0.0"))).isFalse()
            assertThat(LocalAddressRules.isPresentable(ip("::"))).isFalse()
            assertThat(LocalAddressRules.isPresentable(ip("224.0.0.1"))).isFalse()
            assertThat(LocalAddressRules.isPresentable(ip("ff02::1"))).isFalse()
        }
    }

    @Nested
    @DisplayName("哪些地址算「还在设备上」")
    inner class Bindable {

        @Test
        @DisplayName("链路本地地址是可以监听的，判定存在性时不能按展示规则来")
        fun linkLocalCountsAsPresent() {
            // 这是省电模式那条路径的关键：用户手动把入站绑到 fe80::… 之后，
            // 拿展示规则去判定存在性会**永远**判为不存在，于是打开省电模式的那一刻
            // 整个网关就被停掉，运行意图一并清掉，看门狗都不会来救。
            assertThat(LocalAddressRules.isBindable(ip("fe80::1"))).isTrue()
            assertThat(LocalAddressRules.isBindable(ip("169.254.10.20"))).isTrue()
        }

        @Test
        @DisplayName("回环也算存在 —— 只监听 127.0.0.1 的入站不该被省电模式停掉")
        fun loopbackCountsAsPresent() {
            assertThat(LocalAddressRules.isBindable(ip("127.0.0.1"))).isTrue()
            assertThat(LocalAddressRules.isBindable(ip("::1"))).isTrue()
        }

        @Test
        @DisplayName("通配与多播不是设备上的具体地址")
        fun wildcardIsNotAnAddress() {
            assertThat(LocalAddressRules.isBindable(ip("0.0.0.0"))).isFalse()
            assertThat(LocalAddressRules.isBindable(ip("ff02::1"))).isFalse()
        }
    }

    @Nested
    @DisplayName("IPv6 临时地址")
    inner class Ephemeral {

        @Test
        @DisplayName("同一前缀下出现多个全局地址，就说明隐私扩展开着，整组都不稳定")
        fun sharedPrefixMeansPrivacyExtensions() {
            // 隐私扩展打开时，稳定地址和临时地址会同前缀共存，而 Java 侧拿不到
            // IFA_F_TEMPORARY，分不出哪个是哪个。宁可整组一起降级：少推荐一个 IPv6
            // 只是少一个选项，推荐一个几小时后作废的地址是「昨天还好好的」。
            val stable = ip("2001:db8:1:2::1")
            val temporary = ip("2001:db8:1:2:a1b2:c3d4:e5f6:7890")

            val ephemeral = LocalAddressRules.ephemeralIpv6(listOf(stable, temporary))

            assertThat(ephemeral).containsExactly(stable, temporary)
        }

        @Test
        @DisplayName("只有一个全局地址时不可能开着隐私扩展")
        fun singleGlobalIsStable() {
            assertThat(LocalAddressRules.ephemeralIpv6(listOf(ip("2001:db8::1")))).isEmpty()
        }

        @Test
        @DisplayName("不同前缀的两个地址各算各的，不算临时")
        fun differentPrefixesAreIndependent() {
            // ULA 与全局地址并存是很常见的正常配置，把它们判成临时会让 IPv6
            // 在这类网络里彻底不展示
            val ula = ip("fd00:1::1")
            val global = ip("2001:db8::1")

            assertThat(LocalAddressRules.ephemeralIpv6(listOf(ula, global))).isEmpty()
        }

        @Test
        @DisplayName("链路本地不参与判定 —— 它本来就不展示")
        fun linkLocalIsNotCounted() {
            val global = ip("2001:db8::1")

            val ephemeral = LocalAddressRules.ephemeralIpv6(listOf(ip("fe80::1"), global))

            assertThat(ephemeral).isEmpty()
        }

        @Test
        @DisplayName("IPv4 不参与判定")
        fun ipv4IsNotCounted() {
            assertThat(
                LocalAddressRules.ephemeralIpv6(listOf(ip("192.168.1.5"), ip("192.168.1.6"))),
            ).isEmpty()
        }
    }

    @Nested
    @DisplayName("归一化与接口分类")
    inner class Naming {

        @Test
        @DisplayName("剥掉 IPv6 的 %scope 后缀 —— 设置里存的是不带后缀的字面量")
        fun stripsScopeSuffix() {
            assertThat(LocalAddressRules.normalize("fe80::1%wlan0")).isEqualTo("fe80::1")
            assertThat(LocalAddressRules.normalize("192.168.1.5")).isEqualTo("192.168.1.5")
            assertThat(LocalAddressRules.normalize(null)).isEmpty()
        }

        @Test
        @DisplayName("热点接口在各家 ROM 上名字不一，都要认出来")
        fun classifiesHotspot() {
            listOf("ap0", "swlan0", "softap0", "wlan1").forEach {
                assertThat(LocalAddressRules.classify(it)).isEqualTo(InterfaceKind.HOTSPOT)
            }
        }

        @Test
        @DisplayName("其余接口按常见命名归类")
        fun classifiesOthers() {
            assertThat(LocalAddressRules.classify("wlan0")).isEqualTo(InterfaceKind.WIFI)
            assertThat(LocalAddressRules.classify("eth0")).isEqualTo(InterfaceKind.ETHERNET)
            assertThat(LocalAddressRules.classify("rndis0")).isEqualTo(InterfaceKind.USB_TETHER)
            assertThat(LocalAddressRules.classify("rmnet_data0")).isEqualTo(InterfaceKind.CELLULAR)
            assertThat(LocalAddressRules.classify("lo")).isEqualTo(InterfaceKind.LOOPBACK)
            assertThat(LocalAddressRules.classify("dummy0")).isEqualTo(InterfaceKind.OTHER)
        }
    }

    /** 只解析字面量，不会触发 DNS 查询。 */
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)
}
