package com.niceproxy.core.service.network

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.network.LatencyTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * 用户在设置里选了「只走 Wi-Fi」，而 TCPing 测速跑在系统默认网络上 —— 测出来的
 * 延迟和他开了代理之后实际体验到的不是同一条链路，据此挑节点等于抛硬币。
 * 而这种错误在界面上没有任何迹象：数字照常刷出来，只是全都指向另一张网卡。
 *
 * 这里测的就是「选中的那张网到底有没有传到测速那边去」，以及 onAvailable /
 * onLost 成对出现时的几种非平凡顺序。
 */
class OutboundNetworkSelectionTest {

    private val tester = LatencyTester(Dispatchers.IO)

    /** 一个真实可连的目标。测速要真的建一次连接，才能看出它用了哪个工厂。 */
    private val listener = ServerSocket(0)

    private val wifi = CountingSocketFactory()
    private val cellular = CountingSocketFactory()
    private val factories = mapOf(WIFI to wifi, CELLULAR to cellular)

    /** 进程级绑定的下发历史，null 表示回到系统默认网络。 */
    private val processBindings = mutableListOf<String?>()

    private val selection = OutboundNetworkSelection<String>(
        latencyTester = tester,
        socketFactoryOf = { factories.getValue(it) },
        bindProcess = { processBindings += it },
    )

    @AfterEach
    fun tearDown() {
        // 这个 LatencyTester 是单例语义的，绑定不解掉会漏进同一个 JVM 里的别的用例
        tester.bindTo(null)
        listener.close()
    }

    @Nested
    @DisplayName("测速跟着选中的网卡走")
    inner class LatencyBinding {

        @Test
        @DisplayName("选中一张网之后，测速用的是那张网的 socketFactory")
        fun usesSelectedNetworkFactory(): Unit = runBlocking {
            selection.onAvailable(WIFI)

            probeOnce()

            assertThat(wifi.created.get()).isEqualTo(1)
            assertThat(processBindings).containsExactly(WIFI)
        }

        @Test
        @DisplayName("换到另一张网之后，测速立刻改用新网卡")
        fun followsSwitch(): Unit = runBlocking {
            selection.onAvailable(WIFI)
            probeOnce()

            selection.onAvailable(CELLULAR)
            probeOnce()

            assertThat(cellular.created.get()).isEqualTo(1)
            // 旧网卡不该再被用到：它可能已经没有出口了，而测速会全线超时
            assertThat(wifi.created.get()).isEqualTo(1)
        }

        @Test
        @DisplayName("解除偏好之后回到系统默认，不会把测速钉死在一张消失的网卡上")
        fun clearingRestoresDefault(): Unit = runBlocking {
            selection.onAvailable(WIFI)

            selection.clear()

            assertThat(processBindings).containsExactly(WIFI, null).inOrder()
            // 用得上系统默认工厂才连得通；仍然钉在 wifi 上的话计数会涨
            assertThat(probeOnce()).isTrue()
            assertThat(wifi.created.get()).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("onAvailable / onLost 的配对")
    inner class Pairing {

        @Test
        @DisplayName("掉的不是当前这张网就什么也不做")
        fun losingAnotherNetworkKeepsBinding(): Unit = runBlocking {
            // requestNetwork 是按 transport 请求的，同一个 transport 下可以同时有两张
            // 网络（Wi-Fi 漫游到另一个 AP），而**旧网络的 onLost 晚于新网络的 onAvailable**。
            // 无条件回落的写法会把刚绑好的新网络又解掉，出站悄悄退回系统默认。
            selection.onAvailable(WIFI)
            selection.onAvailable(CELLULAR)

            selection.onLost(WIFI)

            assertThat(selection.current).isEqualTo(CELLULAR)
            assertThat(processBindings).containsExactly(WIFI, CELLULAR).inOrder()
            probeOnce()
            assertThat(cellular.created.get()).isEqualTo(1)
        }

        @Test
        @DisplayName("当前这张掉了就回落到另一张还活着的，而不是直接回系统默认")
        fun fallsBackToRemainingNetwork(): Unit = runBlocking {
            selection.onAvailable(CELLULAR)
            selection.onAvailable(WIFI)

            selection.onLost(WIFI)

            assertThat(selection.current).isEqualTo(CELLULAR)
            assertThat(processBindings).containsExactly(CELLULAR, WIFI, CELLULAR).inOrder()
            probeOnce()
            assertThat(cellular.created.get()).isEqualTo(1)
        }

        @Test
        @DisplayName("最后一张也掉了才回系统默认")
        fun lastLossRestoresDefault(): Unit = runBlocking {
            selection.onAvailable(WIFI)

            selection.onLost(WIFI)

            assertThat(selection.current).isNull()
            assertThat(processBindings).containsExactly(WIFI, null).inOrder()
            assertThat(probeOnce()).isTrue()
            assertThat(wifi.created.get()).isEqualTo(0)
        }

        @Test
        @DisplayName("同一张网重复通告不会重绑")
        fun duplicateAnnouncementIsIgnored() {
            // 能力变化（计费状态、验证状态）会让 onAvailable 再来一次。每来一次都重绑，
            // 就是一次白花的跨进程调用，而正在跑的那批测速还会中途换掉工厂 ——
            // 同一次「全部测速」里的数字于是不再可比。
            selection.onAvailable(WIFI)
            selection.onAvailable(WIFI)
            selection.onAvailable(WIFI)

            assertThat(processBindings).containsExactly(WIFI)
        }
    }

    /** @return 这次测速有没有连上。 */
    private suspend fun probeOnce(): Boolean =
        tester.probe(LOOPBACK, listener.localPort, resolver = LOOPBACK_RESOLVER).isSuccess

    /** 数一数这张网卡的工厂被真的用去建了几个 socket。 */
    private class CountingSocketFactory : SocketFactory() {
        val created = AtomicInteger()

        override fun createSocket(): Socket {
            created.incrementAndGet()
            return Socket()
        }

        override fun createSocket(host: String?, port: Int) = createSocket()

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ) = createSocket()

        override fun createSocket(host: InetAddress?, port: Int) = createSocket()

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ) = createSocket()
    }

    private companion object {
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val LOOPBACK = "127.0.0.1"

        val LOOPBACK_RESOLVER = LatencyTester.HostResolver { InetAddress.getByName(LOOPBACK) }
    }
}
