package com.niceproxy.core.config.share

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 原先解析不了的那几类：WireGuard、Hysteria v1、ShadowTLS、SSH，
 * 以及订阅里的链式代理引用。
 *
 * 顺带守住失败明细的可诊断性 —— 「3 条失败」加三串看不懂的 Base64，用户既
 * 判断不出是自己复制漏了字符，还是机场用了我们不认识的协议，而这两种情况的
 * 处理方式完全相反。
 */
class ProtocolGapParsingTest {

    private val wgPrivate = "bmljZS1wcm94eS1wcml2YXRlLWtleS1maXh0dXJlLTE="
    private val wgPublic = "bmljZS1wcm94eS1wZWVyLXB1YmxpYy1rZXktZml4LTI="

    @Nested
    @DisplayName("分享链接")
    inner class Links {

        @Test
        fun `wireguard 链接`() {
            val node = ShareLinkParsers.parse(
                "wireguard://$wgPrivate@wg.example.com:51820" +
                    "?publickey=$wgPublic&address=172.16.0.2/32&mtu=1408" +
                    "&reserved=209,98,59&keepalive=25#WARP",
            ).getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.WIREGUARD)
            assertThat(node.name).isEqualTo("WARP")
            assertThat(node.server).isEqualTo("wg.example.com")
            assertThat(node.serverPort).isEqualTo(51820)

            val params = node.params as ProtocolParams.WireGuard
            assertThat(params.privateKey).isEqualTo(wgPrivate)
            assertThat(params.peerPublicKey).isEqualTo(wgPublic)
            assertThat(params.localAddress).containsExactly("172.16.0.2/32")
            assertThat(params.reserved).containsExactly(209, 98, 59)
            assertThat(params.mtu).isEqualTo(1408)
            assertThat(params.persistentKeepaliveInterval).isEqualTo(25)
        }

        /**
         * `Address = 10.0.0.2` 在 wg-quick 里合法，WARP 的配置导出也这么写；
         * 而 sing-box 用 `netip.ParsePrefix` 解析，不带掩码就在读配置的第一步
         * 失败 —— 失败的不是这一个节点，是整份配置。
         */
        @Test
        fun `wireguard 地址缺掩码位时补齐`() {
            val node = ShareLinkParsers.parse(
                "wireguard://$wgPrivate@wg.example.com:51820" +
                    "?publickey=$wgPublic&address=172.16.0.2,fd01::2",
            ).getOrThrow()

            assertThat((node.params as ProtocolParams.WireGuard).localAddress)
                .containsExactly("172.16.0.2/32", "fd01::2/128")
        }

        @Test
        fun `wireguard 缺少对端公钥时给出明确原因`() {
            val error = ShareLinkParsers
                .parse("wireguard://$wgPrivate@wg.example.com:51820?address=172.16.0.2/32")
                .exceptionOrNull()

            assertThat(error).isNotNull()
            assertThat(error!!.message).contains("公钥")
        }

        @Test
        fun `hysteria v1 链接`() {
            val node = ShareLinkParsers.parse(
                "hysteria://hy1.example.com:443?auth=pw&upmbps=50&downmbps=200" +
                    "&peer=sni.example.com&obfsParam=obfs-pw&alpn=hysteria#HY1",
            ).getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.HYSTERIA)
            val params = node.params as ProtocolParams.Hysteria
            assertThat(params.authString).isEqualTo("pw")
            assertThat(params.upMbps).isEqualTo(50)
            assertThat(params.downMbps).isEqualTo(200)
            // 链接里 obfs 是算法名、obfsParam 才是密码，而 sing-box 的 obfs 就是密码
            assertThat(params.obfs).isEqualTo("obfs-pw")
            assertThat(node.tls?.serverName).isEqualTo("sni.example.com")
            assertThat(node.tls?.alpn).containsExactly("hysteria")
        }

        /** v1 没有 BBR 自适应，带宽是握手的一部分，缺了内核直接拒绝整份配置。 */
        @Test
        fun `hysteria v1 缺少带宽时给出明确原因`() {
            val error = ShareLinkParsers
                .parse("hysteria://hy1.example.com:443?auth=pw&downmbps=200")
                .exceptionOrNull()

            assertThat(error!!.message).contains("上行带宽")
        }

        /** sing-box 只实现了原生 UDP，faketcp 导进来是个永远连不上的节点。 */
        @Test
        fun `hysteria v1 的 faketcp 承载方式被拒绝`() {
            val error = ShareLinkParsers
                .parse("hysteria://h.com:443?auth=pw&upmbps=1&downmbps=1&protocol=faketcp")
                .exceptionOrNull()

            assertThat(error!!.message).contains("faketcp")
        }

        @Test
        fun `ssh 链接`() {
            val node = ShareLinkParsers.parse("ssh://root:pw@ssh.example.com:2222#跳板机")
                .getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.SSH)
            assertThat(node.serverPort).isEqualTo(2222)
            val params = node.params as ProtocolParams.Ssh
            assertThat(params.user).isEqualTo("root")
            assertThat(params.password).isEqualTo("pw")
        }

        @Test
        fun `ssh 默认端口是 22`() {
            val node = ShareLinkParsers.parse("ssh://root:pw@ssh.example.com").getOrThrow()

            assertThat(node.serverPort).isEqualTo(22)
        }

        @Test
        fun `ssh 缺少密码时给出明确原因`() {
            val error = ShareLinkParsers.parse("ssh://root@ssh.example.com:22").exceptionOrNull()

            assertThat(error!!.message).contains("密码")
        }

        @Test
        fun `批量解析的失败明细带上原因`() {
            val result = ShareLinkParsers.parseMany(
                """
                ssh://root@ssh.example.com:22
                gopher://what.example.com:70
                """.trimIndent(),
            )

            assertThat(result.nodes).isEmpty()
            assertThat(result.failures).hasSize(2)
            assertThat(result.failures[0].reason).contains("密码")
            assertThat(result.failures[1].reason).contains("gopher")
        }

        /** 失败明细会出现在界面上，也可能被用户直接截图发到群里求助。 */
        @Test
        fun `失败明细里的原文被截断`() {
            val long = "vmess://" + "A".repeat(500)
            val failure = ShareLinkParsers.parseMany(long).failures.single()

            assertThat(failure.entry.length).isLessThan(long.length)
            assertThat(failure.entry).endsWith("…")
        }
    }

    @Nested
    @DisplayName("Clash YAML")
    inner class Clash {

        private fun parse(yaml: String) = SubscriptionParser.parse(yaml).getOrThrow()

        @Test
        fun `mihomo 的 wireguard 节点`() {
            val node = parse(
                """
                proxies:
                  - name: WG
                    type: wireguard
                    server: wg.example.com
                    port: 51820
                    private-key: $wgPrivate
                    public-key: $wgPublic
                    ip: 172.16.0.2
                    ipv6: fd01::2
                    mtu: 1408
                    reserved: [209, 98, 59]
                    persistent-keepalive: 25
                """.trimIndent(),
            ).nodes.single()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.WIREGUARD)
            val params = node.params as ProtocolParams.WireGuard
            // mihomo 把本地地址拆成 ip / ipv6 两个字段，而且都不带掩码位
            assertThat(params.localAddress).containsExactly("172.16.0.2/32", "fd01::2/128")
            assertThat(params.reserved).containsExactly(209, 98, 59)
            assertThat(params.mtu).isEqualTo(1408)
        }

        @Test
        fun `hysteria v1 节点`() {
            val node = parse(
                """
                proxies:
                  - name: HY1
                    type: hysteria
                    server: hy.example.com
                    port: 443
                    auth-str: pw
                    up: "100 Mbps"
                    down: "300 Mbps"
                    sni: hy.example.com
                """.trimIndent(),
            ).nodes.single()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.HYSTERIA)
            val params = node.params as ProtocolParams.Hysteria
            assertThat(params.up).isEqualTo("100 Mbps")
            assertThat(params.down).isEqualTo("300 Mbps")
            // v1 天然基于 TLS，模板里不写 tls: true 也要启用
            assertThat(node.tls?.enabled).isTrue()
        }

        @Test
        fun `ssh 节点`() {
            val node = parse(
                """
                proxies:
                  - name: SSH
                    type: ssh
                    server: ssh.example.com
                    port: 22
                    username: root
                    password: pw
                """.trimIndent(),
            ).nodes.single()

            assertThat((node.params as ProtocolParams.Ssh).user).isEqualTo("root")
        }

        /**
         * 机场模板给 ss 节点顺手写上 `tls: true` 是常事。mihomo 那边这个字段
         * 本来就没用，照搬到 sing-box 却是个未知字段，整份配置作废。
         */
        @Test
        fun `不支持 TLS 的协议不会带上 tls 配置`() {
            val node = parse(
                """
                proxies:
                  - name: SS
                    type: ss
                    server: ss.example.com
                    port: 8388
                    cipher: aes-256-gcm
                    password: pw
                    tls: true
                """.trimIndent(),
            ).nodes.single()

            assertThat(node.tls).isNull()
        }

        /** mihomo 的 `dialer-proxy` 引用的是节点名，而本地 tag 是新生成的。 */
        @Test
        fun `dialer-proxy 换算成本地出站 tag`() {
            val nodes = parse(
                """
                proxies:
                  - name: 中转
                    type: ss
                    server: relay.example.com
                    port: 8388
                    cipher: aes-256-gcm
                    password: pw
                  - name: 落地
                    type: trojan
                    server: exit.example.com
                    port: 443
                    password: pw
                    dialer-proxy: 中转
                """.trimIndent(),
            ).nodes

            val relay = nodes.single { it.name == "中转" }
            val exit = nodes.single { it.name == "落地" }
            assertThat(exit.detour).isEqualTo(relay.outboundTag)
            assertThat(relay.detour).isNull()
        }

        /**
         * 不导入这个节点，而不是「丢掉 detour 照常导入」：用户配链式代理通常是
         * 因为落地机只接受中转机的 IP，直连过去要么连不上，要么在对端留下一条
         * 本不该出现的记录。
         */
        @Test
        fun `dialer-proxy 指向订阅里没有的节点时整条不导入`() {
            val result = parse(
                """
                proxies:
                  - name: 落地
                    type: trojan
                    server: exit.example.com
                    port: 443
                    password: pw
                    dialer-proxy: 不存在的中转
                """.trimIndent(),
            )

            assertThat(result.nodes).isEmpty()
            assertThat(result.failures.single().reason).contains("不存在的中转")
        }

        @Test
        fun `dialer-proxy 指向 DIRECT 时保留为直连`() {
            val node = parse(
                """
                proxies:
                  - name: 落地
                    type: trojan
                    server: exit.example.com
                    port: 443
                    password: pw
                    dialer-proxy: DIRECT
                """.trimIndent(),
            ).nodes.single()

            assertThat(node.detour).isEqualTo(WellKnownTag.DIRECT)
        }
    }

    @Nested
    @DisplayName("sing-box JSON")
    inner class SingBox {

        private fun parse(json: String) = SubscriptionParser.parse(json).getOrThrow()

        /** 1.11+ 的 WireGuard 在 endpoints 里，跟 outbounds 共用 tag 命名空间。 */
        @Test
        fun `endpoints 里的 WireGuard`() {
            val node = parse(
                """
                {
                  "outbounds": [{"type": "direct", "tag": "direct"}],
                  "endpoints": [
                    {
                      "type": "wireguard",
                      "tag": "WG",
                      "mtu": 1408,
                      "address": ["172.16.0.2/32"],
                      "private_key": "$wgPrivate",
                      "peers": [
                        {
                          "address": "wg.example.com",
                          "port": 51820,
                          "public_key": "$wgPublic",
                          "allowed_ips": ["0.0.0.0/0"],
                          "reserved": [209, 98, 59],
                          "persistent_keepalive_interval": 25
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes.single()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.WIREGUARD)
            assertThat(node.server).isEqualTo("wg.example.com")
            assertThat(node.serverPort).isEqualTo(51820)
            val params = node.params as ProtocolParams.WireGuard
            assertThat(params.allowedIps).containsExactly("0.0.0.0/0")
            assertThat(params.reserved).containsExactly(209, 98, 59)
            assertThat(params.persistentKeepaliveInterval).isEqualTo(25)
        }

        /**
         * 已被 1.13 移除、但仍大量存在于旧配置里的 outbound 形态。
         * 用户手上的配置多半是一两年前生成的，这才是导入的主要来源。
         */
        @Test
        fun `旧的 wireguard outbound 形态也能导入`() {
            val node = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "wireguard",
                      "tag": "旧 WG",
                      "server": "wg.example.com",
                      "server_port": 51820,
                      "local_address": ["172.16.0.2/32"],
                      "private_key": "$wgPrivate",
                      "peer_public_key": "$wgPublic",
                      "reserved": [1, 2, 3]
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes.single()

            val params = node.params as ProtocolParams.WireGuard
            assertThat(params.peerPublicKey).isEqualTo(wgPublic)
            assertThat(params.localAddress).containsExactly("172.16.0.2/32")
            assertThat(params.reserved).containsExactly(1, 2, 3)
        }

        @Test
        fun `shadowtls 与 ssh`() {
            val nodes = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "shadowtls", "tag": "STLS",
                      "server": "s.example.com", "server_port": 443,
                      "version": 3, "password": "pw",
                      "tls": {"enabled": true, "server_name": "www.microsoft.com"}
                    },
                    {
                      "type": "ssh", "tag": "SSH",
                      "server": "ssh.example.com", "server_port": 22,
                      "user": "root", "password": "pw",
                      "host_key_algorithms": ["ssh-ed25519"],
                      "client_version": "SSH-2.0-OpenSSH_9.6"
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes

            val stls = nodes.single { it.protocol == ProxyProtocol.SHADOWTLS }
            assertThat((stls.params as ProtocolParams.ShadowTls).version).isEqualTo(3)
            assertThat(stls.tls?.serverName).isEqualTo("www.microsoft.com")

            val ssh = nodes.single { it.protocol == ProxyProtocol.SSH }.params as ProtocolParams.Ssh
            assertThat(ssh.hostKeyAlgorithms).containsExactly("ssh-ed25519")
            assertThat(ssh.clientVersion).isEqualTo("SSH-2.0-OpenSSH_9.6")
        }

        @Test
        fun `hysteria v1 的端口跳跃与 Base64 认证串`() {
            val params = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "hysteria", "tag": "HY1",
                      "server": "hy.example.com", "server_port": 443,
                      "auth": "cHc=", "up_mbps": 50, "down_mbps": 200,
                      "server_ports": ["20000:30000"], "hop_interval": "30s",
                      "tls": {"enabled": true}
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes.single().params as ProtocolParams.Hysteria

            assertThat(params.authBase64).isEqualTo("cHc=")
            assertThat(params.serverPorts).containsExactly("20000:30000")
            assertThat(params.hopInterval).isEqualTo("30s")
        }

        @Test
        fun `TUIC 与 AnyTLS 的完整字段`() {
            val nodes = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "tuic", "tag": "TUIC",
                      "server": "t.example.com", "server_port": 443,
                      "uuid": "11111111-2222-3333-4444-555555555555", "password": "pw",
                      "congestion_control": "bbr", "udp_relay_mode": "quic",
                      "zero_rtt_handshake": true, "heartbeat": "10s",
                      "tls": {"enabled": true}
                    },
                    {
                      "type": "anytls", "tag": "ATLS",
                      "server": "a.example.com", "server_port": 443,
                      "password": "pw",
                      "idle_session_check_interval": "30s",
                      "idle_session_timeout": "5m",
                      "min_idle_session": 2,
                      "tls": {"enabled": true}
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes

            val tuic = nodes.single { it.protocol == ProxyProtocol.TUIC }
                .params as ProtocolParams.Tuic
            assertThat(tuic.congestionControl).isEqualTo("bbr")
            assertThat(tuic.udpRelayMode).isEqualTo("quic")
            assertThat(tuic.zeroRttHandshake).isTrue()
            assertThat(tuic.heartbeat).isEqualTo("10s")

            val anytls = nodes.single { it.protocol == ProxyProtocol.ANYTLS }
                .params as ProtocolParams.AnyTls
            assertThat(anytls.idleSessionTimeout).isEqualTo("5m")
            assertThat(anytls.minIdleSession).isEqualTo(2)
        }

        @Test
        fun `detour 换算成本地出站 tag`() {
            val nodes = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "shadowsocks", "tag": "relay",
                      "server": "relay.example.com", "server_port": 8388,
                      "method": "aes-256-gcm", "password": "pw"
                    },
                    {
                      "type": "trojan", "tag": "exit",
                      "server": "exit.example.com", "server_port": 443,
                      "password": "pw", "detour": "relay",
                      "tls": {"enabled": true}
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes

            val relay = nodes.single { it.name == "relay" }
            assertThat(nodes.single { it.name == "exit" }.detour).isEqualTo(relay.outboundTag)
        }

        @Test
        fun `多路复用与 brutal 一并导入`() {
            val mux = parse(
                """
                {
                  "outbounds": [
                    {
                      "type": "trojan", "tag": "T",
                      "server": "t.example.com", "server_port": 443,
                      "password": "pw",
                      "tls": {"enabled": true},
                      "multiplex": {
                        "enabled": true, "protocol": "yamux",
                        "max_connections": 4, "padding": true,
                        "brutal": {"enabled": true, "up_mbps": 50, "down_mbps": 100}
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ).nodes.single().multiplex

            assertThat(mux?.protocol).isEqualTo("yamux")
            assertThat(mux?.maxConnections).isEqualTo(4)
            assertThat(mux?.brutal?.upMbps).isEqualTo(50)
        }

        @Test
        fun `失败的条目带上 tag 与原因`() {
            val result = parse(
                """
                {
                  "outbounds": [
                    {"type": "trojan", "tag": "缺密码", "server": "t.com", "server_port": 443},
                    {
                      "type": "shadowsocks", "tag": "OK",
                      "server": "s.com", "server_port": 8388,
                      "method": "aes-256-gcm", "password": "pw"
                    }
                  ]
                }
                """.trimIndent(),
            )

            assertThat(result.nodes.map { it.name }).containsExactly("OK")
            val failure = result.failures.single()
            assertThat(failure.entry).isEqualTo("缺密码")
            assertThat(failure.reason).contains("password")
        }
    }

    /**
     * NFR：远程订阅一律不得开启 insecure。机场只要在模板里加一行
     * `skip-cert-verify: true`，就能对全部用户的流量做中间人 —— 而用户不会
     * 逐个节点去翻这个开关。
     */
    @Nested
    @DisplayName("远程 TLS 策略")
    inner class TlsPolicy {

        @Test
        fun `新增协议同样受 insecure 清洗约束`() {
            val result = SubscriptionParser.parse(
                """
                proxies:
                  - name: HY1
                    type: hysteria
                    server: hy.example.com
                    port: 443
                    auth-str: pw
                    up: "100 Mbps"
                    down: "100 Mbps"
                    skip-cert-verify: true
                """.trimIndent(),
            ).getOrThrow()

            assertThat(result.nodes.single().tls?.insecure).isFalse()
            assertThat(result.ignoredInsecureCount).isEqualTo(1)
        }

        @Test
        fun `hysteria v1 链接里的 insecure 同样被清洗`() {
            val node: ServerProfile = ShareLinkParsers.parseMany(
                "hysteria://h.com:443?auth=pw&upmbps=1&downmbps=1&insecure=1",
            ).nodes.single()

            assertThat(node.tls?.insecure).isFalse()
        }
    }
}
