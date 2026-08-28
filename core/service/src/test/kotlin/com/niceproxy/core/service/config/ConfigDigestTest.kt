package com.niceproxy.core.service.config

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.NetworkPreference
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConfigDigestTest {

    @Test
    @DisplayName("只有 selector 选中项不同时指纹相同 —— 切节点走热切换，不该重启")
    fun ignoresSelectorDefault() {
        assertThat(key(config(selected = "auto")))
            .isEqualTo(key(config(selected = "node-1")))
    }

    @Test
    @DisplayName("入站端口变化必须体现为指纹变化")
    fun detectsInboundChange() {
        assertThat(key(config(port = 8080))).isNotEqualTo(key(config(port = 8081)))
    }

    @Test
    @DisplayName("PAC 端口变化也算变更 —— PAC 根本不在内核配置里")
    fun detectsPacChange() {
        val json = config()

        assertThat(key(json, inbounds = listOf(pacInbound(port = 8090))))
            .isNotEqualTo(key(json, inbounds = listOf(pacInbound(port = 8091))))
    }

    @Test
    @DisplayName("停用的 PAC 入站不计入指纹")
    fun ignoresDisabledPac() {
        val json = config()

        assertThat(key(json))
            .isEqualTo(key(json, inbounds = listOf(pacInbound(enabled = false))))
    }

    @Test
    @DisplayName("交给内核管的入站不重复计入 —— 它们已经在配置 JSON 里了")
    fun ignoresSingBoxManagedInbounds() {
        val json = config()
        val mixed = InboundService(id = "m", type = InboundType.MIXED, listenPort = 8080)

        assertThat(key(json, inbounds = listOf(mixed))).isEqualTo(key(json))
    }

    @Test
    @DisplayName("出站网卡偏好变化也算变更 —— 绑定由宿主做，同样不在配置里")
    fun detectsNetworkPreferenceChange() {
        val json = config()

        assertThat(key(json, preference = NetworkPreference.AUTO))
            .isNotEqualTo(key(json, preference = NetworkPreference.CELLULAR))
    }

    @Test
    @DisplayName("PAC 直连兜底开关变化也算变更 —— 脚本内容在启动时就定死在闭包里了")
    fun detectsPacFallbackChange() {
        // 不算进去的话，用户改了开关、点了「应用」，指纹一样于是不重启，
        // PAC 服务继续用旧闭包发旧脚本 —— 又一个「设置改了但静默不生效」
        val json = config()
        val inbounds = listOf(pacInbound())

        assertThat(key(json, inbounds = inbounds, pacDirectFallback = false))
            .isNotEqualTo(key(json, inbounds = inbounds, pacDirectFallback = true))
    }

    @Test
    @DisplayName("配置解析不了时退回全文比对，而不是抛异常")
    fun fallsBackOnUnparseableJson() {
        val broken = "{ not json"

        assertThat(key(broken)).isEqualTo(key(broken))
        assertThat(key(broken)).isNotEqualTo(key("$broken "))
    }

    @Test
    @DisplayName("没有 outbounds 的配置不会让归一化崩掉")
    fun toleratesMissingOutbounds() {
        val minimal = """{ "log": { "level": "info" } }"""

        assertThat(key(minimal)).isEqualTo(key(minimal))
    }

    @Nested
    @DisplayName("宿主侧那几项不用重启内核的设置")
    inner class HostKey {

        @Test
        @DisplayName("保持 Wi-Fi 唤醒变了要能看出来")
        fun detectsKeepWifiAwake() {
            // 这一位以前只在服务启动时读过一次，改了之后既不生效、也不会提示
            // 「配置已变更」—— 开关拨过去了、行为纹丝不动，是最难自查的一类 bug
            assertThat(ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = true))
                .isNotEqualTo(
                    ConfigDigest.hostKey(keepWifiAwake = false, autoRestartOnFailure = true),
                )
        }

        @Test
        @DisplayName("自动重试开关变了要能看出来")
        fun detectsAutoRestart() {
            // 服务持有的是一份快照（失败路径必须同步执行），不换掉那份快照的话，
            // 用户关了自动重试，代理还是会自己一遍遍重来
            assertThat(ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = true))
                .isNotEqualTo(
                    ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = false),
                )
        }

        @Test
        @DisplayName("没变就是没变，不会白白重做一遍")
        fun stableWhenUnchanged() {
            assertThat(ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = false))
                .isEqualTo(
                    ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = false),
                )
        }

        @Test
        @DisplayName("它和重启指纹是两条独立的比对，不许互相污染")
        fun independentFromRestartKey() {
            // 合成一个指纹的话，换一把 WifiLock 就会重启内核、断掉全屋设备的连接 ——
            // 那比设置不生效还糟
            val before = key(config())
            val after = key(config())

            assertThat(before).isEqualTo(after)
            assertThat(before).isNotEqualTo(
                ConfigDigest.hostKey(keepWifiAwake = true, autoRestartOnFailure = true),
            )
        }
    }

    private fun key(
        json: String,
        inbounds: List<InboundService> = emptyList(),
        preference: NetworkPreference = NetworkPreference.AUTO,
        pacDirectFallback: Boolean = false,
    ) = ConfigDigest.restartKey(json, inbounds, preference, pacDirectFallback)

    private fun config(selected: String = "auto", port: Int = 8080): String = """
        {
          "inbounds": [
            { "type": "mixed", "tag": "in-1", "listen": "0.0.0.0", "listen_port": $port }
          ],
          "outbounds": [
            {
              "type": "selector", "tag": "proxy",
              "outbounds": ["auto", "node-1"], "default": "$selected"
            },
            { "type": "urltest", "tag": "auto", "outbounds": ["node-1"] },
            { "type": "hysteria2", "tag": "node-1", "server": "example.com", "server_port": 443 },
            { "type": "direct", "tag": "direct" }
          ]
        }
    """.trimIndent()

    private fun pacInbound(port: Int = 8090, enabled: Boolean = true) = InboundService(
        id = "pac",
        type = InboundType.PAC,
        listenPort = port,
        enabled = enabled,
    )
}
