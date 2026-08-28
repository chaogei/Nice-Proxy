package com.niceproxy.core.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 状态迁移。
 *
 * 这张表要挡的是两类都很难自查的事故：
 *
 * - **复活**。用户按了停止、服务已经销毁，而那条还在飞的启动协程终于等到内核起来，
 *   把状态改回 Running。界面上代理开着，实际上什么都没在跑，点停止也没反应。
 * - **卡死**。Failed 之后又来一个 Running，或者 Stopping 之后回到 Starting，
 *   界面会停在一个用户操作不了的中间态。
 *
 * 两者在运行时都不会报错，只会表现为「这应用有时候会抽风」。
 */
internal class ProxyStateMachineTest {

    @Nested
    @DisplayName("正常的一整圈")
    inner class HappyPath {

        @Test
        @DisplayName("停止 → 启动中 → 运行中 → 停止中 → 停止")
        fun fullCycle() {
            assertThat(can(ProxyState.Stopped, ProxyState.Starting)).isTrue()
            assertThat(can(ProxyState.Starting, running())).isTrue()
            assertThat(can(running(), ProxyState.Stopping)).isTrue()
            assertThat(can(ProxyState.Stopping, ProxyState.Stopped)).isTrue()
        }

        @Test
        @DisplayName("运行中可以回到启动中 —— 切网自愈与内核自愈都是就地重启")
        fun restartInPlace() {
            // 刻意复用 Starting 而不是新增「重启中」：ProxyState 是 app 层多处穷举
            // when 的分支来源，加一个分支会牵动 core:service 之外的代码
            assertThat(can(running(), ProxyState.Starting)).isTrue()
        }

        @Test
        @DisplayName("失败之后用户可以再启动一次")
        fun retryAfterFailure() {
            assertThat(can(failed(), ProxyState.Starting)).isTrue()
            assertThat(can(failed(), ProxyState.Stopped)).isTrue()
        }

        @Test
        @DisplayName("同一种状态的就地刷新一律放行")
        fun inPlaceRefresh() {
            // Running 的端口列表、Failed 的文案、退避重试期间反复写 Starting
            assertThat(can(running(port = 8080), running(port = 8081))).isTrue()
            assertThat(can(failed("A"), failed("B"))).isTrue()
            assertThat(can(ProxyState.Starting, ProxyState.Starting)).isTrue()
            assertThat(can(ProxyState.Stopped, ProxyState.Stopped)).isTrue()
            assertThat(can(ProxyState.Stopping, ProxyState.Stopping)).isTrue()
        }
    }

    @Nested
    @DisplayName("不许复活")
    inner class NoResurrection {

        @Test
        @DisplayName("正在停止时，迟到的「起来了」必须被丢掉")
        fun stoppingIgnoresLateSuccess() {
            // 用户按了停止，而那条还在飞的启动协程此刻才等到内核起来。
            // 放它进来的话，界面显示运行中、实际什么都没跑，而且再点停止也没用 ——
            // 服务早就没了，那个 intent 谁都收不到。
            assertThat(can(ProxyState.Stopping, running())).isFalse()
            assertThat(can(ProxyState.Stopping, ProxyState.Starting)).isFalse()
        }

        @Test
        @DisplayName("终态失败之后不会突然变成运行中")
        fun failedIgnoresLateSuccess() {
            // fail() 已经 stopSelf 了，一个迟到的 Running 指向的是一个不存在的服务
            assertThat(can(failed(), running())).isFalse()
        }

        @Test
        @DisplayName("已停止时不会凭空出现运行中或停止中")
        fun stoppedStaysStopped() {
            assertThat(can(ProxyState.Stopped, running())).isFalse()
            assertThat(can(ProxyState.Stopped, ProxyState.Stopping)).isFalse()
        }
    }

    @Nested
    @DisplayName("失败随时可能发生")
    inner class FailureIsAlwaysReachable {

        @Test
        @DisplayName("除了正在启动，其余状态也都能落到失败")
        fun everyStateCanFail() {
            // 这个方向刻意放宽：把一次真实的失败挡在门外，用户就永远看不到原因，
            // 而多接受一次失败最坏也只是多显示一条报错
            listOf(
                ProxyState.Stopped,
                ProxyState.Starting,
                running(),
                ProxyState.Stopping,
            ).forEach { from ->
                assertThat(can(from, failed())).isTrue()
            }
        }

        @Test
        @DisplayName("停止是任何状态都能到达的终点")
        fun everyActiveStateCanStop() {
            listOf(ProxyState.Starting, running(), ProxyState.Stopping, failed())
                .forEach { from -> assertThat(can(from, ProxyState.Stopped)).isTrue() }
        }
    }

    @Test
    @DisplayName("每个状态至少还有一条出路，不存在死角")
    fun noDeadEnd() {
        // 有出路才不会卡死。真正卡住用户的是「界面停在某个状态，而所有操作都不生效」
        val all = listOf(
            ProxyState.Stopped,
            ProxyState.Starting,
            running(),
            ProxyState.Stopping,
            failed(),
        )
        all.forEach { from ->
            val reachable = all.filter { it !== from && can(from, it) }
            assertThat(reachable).isNotEmpty()
        }
    }

    private fun can(from: ProxyState, to: ProxyState) = ProxyStateMachine.canTransition(from, to)

    private fun running(port: Int = 8080) = ProxyState.Running(
        startedAtMillis = 0,
        listeningOn = listOf(
            ListeningEndpoint(inboundId = "in", typeLabel = "混合", port = port, requiresAuth = false),
        ),
    )

    private fun failed(message: String = "内核启动失败") = ProxyState.Failed(message)
}
