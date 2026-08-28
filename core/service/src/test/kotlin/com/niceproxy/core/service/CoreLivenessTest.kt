package com.niceproxy.core.service

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ClashApiSettings
import com.niceproxy.core.network.clash.ClashApiClient
import com.niceproxy.core.network.concurrent.CircuitBreaker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class CoreLivenessTest {

    @Nested
    @DisplayName("死亡判定")
    inner class Verdicts {

        @Test
        @DisplayName("探到了就是活着，之前攒的失败次数一并清零")
        fun successClearsMisses(): Unit = runBlocking {
            val alive = AtomicInteger(0)
            val liveness = CoreLiveness(missesBeforeDead = 3, probe = { alive.get() > 0 }) {}

            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
            alive.set(1)

            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.ALIVE)
            assertThat(liveness.consecutiveMisses).isEqualTo(0)
        }

        @Test
        @DisplayName("单次失败不判死刑，连够次数才判")
        fun requiresConsecutiveMisses(): Unit = runBlocking {
            val liveness = CoreLiveness(missesBeforeDead = 3, probe = { false }) {}

            // 重启内核会断掉全屋设备的连接，为一次可能只是「系统刚好卡了一下」的
            // 回环超时付这个代价不划算
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.DEAD)
        }

        @Test
        @DisplayName("判过一次死刑之后重新数，不会每一轮都再判一次")
        fun deadVerdictRestartsCounting(): Unit = runBlocking {
            val liveness = CoreLiveness(missesBeforeDead = 2, probe = { false }) {}

            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.DEAD)
            // 刚拉起来的内核还没把 Clash API 端口监听起来，这一轮探不到很正常
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
        }

        @Test
        @DisplayName("重启期间的探测不算数")
        fun resetDropsPendingMisses(): Unit = runBlocking {
            val liveness = CoreLiveness(missesBeforeDead = 2, probe = { false }) {}

            liveness.check()
            liveness.reset()

            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)
        }
    }

    @Nested
    @DisplayName("与控制面熔断的接线")
    inner class BreakerHandoff {

        /**
         * 这一条测的是接线本身，所以用的是真的 [ClashApiClient] 而不是替身：
         * 「探活成功要把熔断放回关闭」这件事，只有从熔断器的实际状态上才看得出来。
         */
        @Test
        @DisplayName("探活成功后，之前打开的 REST 熔断被放回关闭")
        fun aliveProbeClosesBreaker(): Unit = runBlocking {
            val client = ClashApiClient()
            // 内核死掉时控制面的每一次请求都是即刻 ECONNREFUSED，正是熔断要挡的那种
            val settings = ClashApiSettings(port = closedPort(), secret = "secret")
            repeat(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD) { client.proxies(settings) }
            assertThat(client.metrics().breakerState).isNotEqualTo(CircuitBreaker.State.CLOSED)

            val liveness = CoreLiveness(
                missesBeforeDead = 3,
                probe = { true },
                onAlive = client::noteCoreAlive,
            )
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.ALIVE)

            // 没有这一步，用户在冷却走完之前点节点切换只会得到「正在冷却」，
            // 而界面上那个按钮明明是可点的 —— 看起来就是个 bug
            assertThat(client.metrics().breakerState).isEqualTo(CircuitBreaker.State.CLOSED)
        }

        @Test
        @DisplayName("探不到就不通告，熔断继续挡着")
        fun deadProbeLeavesBreakerOpen(): Unit = runBlocking {
            val client = ClashApiClient()
            val settings = ClashApiSettings(port = closedPort(), secret = "secret")
            repeat(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD) { client.proxies(settings) }

            val liveness = CoreLiveness(
                missesBeforeDead = 3,
                probe = { false },
                onAlive = client::noteCoreAlive,
            )
            assertThat(liveness.check()).isEqualTo(CoreLiveness.Verdict.WATCHING)

            assertThat(client.metrics().breakerState).isNotEqualTo(CircuitBreaker.State.CLOSED)
        }
    }

    /** 一个刚被让出来、确定没人监听的端口。连上去必然是即刻 ECONNREFUSED。 */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }
}
