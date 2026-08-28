package com.niceproxy.core.config.share

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.TransportConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

class SubscriptionParserTest {

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())

    @Nested
    @DisplayName("格式探测")
    inner class Detection {

        @Test
        fun `Base64 链接列表`() {
            val content = b64("trojan://pw@a.com:443#A\nhy2://pw@b.com:443#B")
            assertThat(SubscriptionParser.detect(content))
                .isEqualTo(SubscriptionFormat.BASE64_LINKS)
        }

        @Test
        fun `明文链接列表`() {
            assertThat(SubscriptionParser.detect("trojan://pw@a.com:443#A"))
                .isEqualTo(SubscriptionFormat.PLAIN_LINKS)
        }

        @Test
        fun `Clash YAML`() {
            val yaml = "port: 7890\nproxies:\n  - name: a\n    type: ss\n"
            assertThat(SubscriptionParser.detect(yaml)).isEqualTo(SubscriptionFormat.CLASH_YAML)
        }

        @Test
        fun `sing-box JSON`() {
            assertThat(SubscriptionParser.detect("""{"outbounds":[]}"""))
                .isEqualTo(SubscriptionFormat.SING_BOX_JSON)
        }

        @Test
        fun `SIP008`() {
            assertThat(SubscriptionParser.detect("""{"version":1,"servers":[]}"""))
                .isEqualTo(SubscriptionFormat.SIP008)
        }

        @Test
        fun `无法识别的内容返回 null`() {
            assertThat(SubscriptionParser.detect("hello world")).isNull()
            assertThat(SubscriptionParser.detect("")).isNull()
        }
    }

    @Nested
    @DisplayName("Clash YAML")
    inner class Clash {

        private val yaml = """
            port: 7890
            proxies:
              - name: "SS 节点"
                type: ss
                server: ss.example.com
                port: 8388
                cipher: aes-256-gcm
                password: ss-pw
              - name: "VMess WS"
                type: vmess
                server: vm.example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                alterId: 0
                cipher: auto
                tls: true
                servername: vm.example.com
                network: ws
                ws-opts:
                  path: /path
                  headers:
                    Host: cdn.example.com
              - name: "HY2"
                type: hysteria2
                server: hy.example.com
                port: 443
                password: hy-pw
                sni: hy.example.com
                skip-cert-verify: true
              - name: "坏节点"
                type: unknown-proto
                server: x.com
                port: 1
        """.trimIndent()

        @Test
        fun `解析多种协议并跳过不支持的类型`() {
            val result = SubscriptionParser.parse(yaml).getOrThrow()

            assertThat(result.format).isEqualTo(SubscriptionFormat.CLASH_YAML)
            assertThat(result.nodes).hasSize(3)
            // 失败明细现在带上原因：只给一句「坏节点」的话，用户既判断不出是自己
            // 复制漏了字符，还是机场用了我们不认识的协议，而这两种处理方式相反
            assertThat(result.failures.map { it.entry }).containsExactly("坏节点")
            assertThat(result.failures.single().reason).contains("unknown-proto")
        }

        @Test
        fun `VMess 的 ws-opts 转为传输层配置`() {
            val node = SubscriptionParser.parse(yaml).getOrThrow().nodes
                .single { it.protocol == ProxyProtocol.VMESS }
            val ws = node.transport as TransportConfig.WebSocket

            assertThat(ws.path).isEqualTo("/path")
            assertThat(ws.headers["Host"]).isEqualTo("cdn.example.com")
            assertThat(node.tls?.serverName).isEqualTo("vm.example.com")
        }

        @Test
        fun `Hysteria2 自动启用 TLS`() {
            val node = SubscriptionParser.parse(yaml).getOrThrow().nodes
                .single { it.protocol == ProxyProtocol.HYSTERIA2 }

            assertThat(node.tls?.enabled).isTrue()
            assertThat((node.params as ProtocolParams.Hysteria2).password).isEqualTo("hy-pw")
        }

        @Test
        fun `订阅要求的 skip-cert-verify 不予采信，只计数`() {
            // 订阅正文由机场控制，而 SubscriptionUpdateWorker 会定期拉取并覆盖节点：
            // 导入时干净的订阅可以在某次自动更新后把所有节点悄悄变成不校验证书
            val result = SubscriptionParser.parse(yaml).getOrThrow()
            val node = result.nodes.single { it.protocol == ProxyProtocol.HYSTERIA2 }

            assertThat(node.tls?.insecure).isFalse()
            assertThat(result.ignoredInsecureCount).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("sing-box JSON")
    inner class SingBox {

        @Test
        fun `导入 outbounds 并跳过策略组`() {
            val content = """
                {
                  "outbounds": [
                    { "type": "selector", "tag": "proxy", "outbounds": ["a"] },
                    { "type": "direct", "tag": "direct" },
                    {
                      "type": "hysteria2", "tag": "HY2 节点",
                      "server": "h.example.com", "server_port": 443,
                      "password": "pw",
                      "obfs": { "type": "salamander", "password": "ob" },
                      "tls": { "enabled": true, "server_name": "h.example.com", "alpn": ["h3"] }
                    }
                  ]
                }
            """.trimIndent()
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.nodes).hasSize(1)
            val node = result.nodes.single()
            assertThat(node.name).isEqualTo("HY2 节点")
            val params = node.params as ProtocolParams.Hysteria2
            assertThat(params.obfsType).isEqualTo("salamander")
            assertThat(node.tls?.alpn).containsExactly("h3")
        }

        @Test
        fun `tls 段里的 insecure 同样不予采信`() {
            val content = """
                {
                  "outbounds": [
                    {
                      "type": "trojan", "tag": "T",
                      "server": "t.example.com", "server_port": 443,
                      "password": "pw",
                      "tls": { "enabled": true, "server_name": "t.example.com", "insecure": true }
                    }
                  ]
                }
            """.trimIndent()
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.nodes.single().tls?.insecure).isFalse()
            assertThat(result.ignoredInsecureCount).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("SIP008")
    inner class Sip008 {

        @Test
        fun `导入 Shadowsocks 节点列表`() {
            val content = """
                {
                  "version": 1,
                  "servers": [
                    {
                      "id": "x", "remarks": "SS-01",
                      "server": "s.example.com", "server_port": 8388,
                      "password": "pw", "method": "chacha20-ietf-poly1305"
                    }
                  ]
                }
            """.trimIndent()
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.nodes).hasSize(1)
            assertThat(result.nodes.single().name).isEqualTo("SS-01")
            assertThat((result.nodes.single().params as ProtocolParams.Shadowsocks).method)
                .isEqualTo("chacha20-ietf-poly1305")
        }
    }

    @Nested
    @DisplayName("流量信息")
    inner class UserInfo {

        @Test
        fun `解析 subscription-userinfo 响应头`() {
            val traffic = SubscriptionParser.parseUserInfo(
                "upload=1024; download=2048; total=10240; expire=1735689600",
            )!!

            assertThat(traffic.uploadBytes).isEqualTo(1024)
            assertThat(traffic.downloadBytes).isEqualTo(2048)
            assertThat(traffic.totalBytes).isEqualTo(10240)
            assertThat(traffic.usedBytes).isEqualTo(3072)
            assertThat(traffic.remainingBytes).isEqualTo(7168)
            assertThat(traffic.expireAtSeconds).isEqualTo(1735689600)
        }

        @Test
        fun `缺失字段按 0 处理`() {
            val traffic = SubscriptionParser.parseUserInfo("total=100")!!
            assertThat(traffic.totalBytes).isEqualTo(100)
            assertThat(traffic.usedBytes).isEqualTo(0)
        }

        @Test
        fun `空头或无效内容返回 null`() {
            assertThat(SubscriptionParser.parseUserInfo(null)).isNull()
            assertThat(SubscriptionParser.parseUserInfo("")).isNull()
            assertThat(SubscriptionParser.parseUserInfo("nonsense")).isNull()
        }

        @Test
        fun `字节数写成浮点或科学计数法`() {
            // Python 实现的面板经常直接把浮点丢进响应头
            val traffic = SubscriptionParser.parseUserInfo(
                "upload=0; download=1.5e3; total=1.073741824E11; expire=1735689600",
            )!!
            assertThat(traffic.downloadBytes).isEqualTo(1500)
            assertThat(traffic.totalBytes).isEqualTo(107_374_182_400)
        }
    }

    @Nested
    @DisplayName("正文的外层包装")
    inner class Envelope {

        @Test
        fun `带 BOM 的 sing-box JSON 仍按 JSON 识别`() {
            // 订阅服务端直接吐一个 Windows 上生成的文件时会带 BOM，
            // 带着 BOM 判断不出 '{' 开头，整份配置会被当成链接列表解析出 0 个节点
            assertThat(SubscriptionParser.detect("\uFEFF{\"outbounds\":[]}"))
                .isEqualTo(SubscriptionFormat.SING_BOX_JSON)
        }

        @Test
        fun `带 BOM 的 Clash YAML 仍按 YAML 识别`() {
            assertThat(SubscriptionParser.detect("\uFEFFproxies:\n  - name: a\n"))
                .isEqualTo(SubscriptionFormat.CLASH_YAML)
        }

        @Test
        fun `整体 Base64 的 Clash YAML 要按 YAML 解析`() {
            // 有的机场把 YAML 也一并 Base64 了。按链接列表去解会得到一个空订阅，
            // 用户完全无法判断是自己填错了地址还是订阅本身有问题。
            val yaml = """
                proxies:
                  - name: "SS 节点"
                    type: ss
                    server: ss.example.com
                    port: 8388
                    cipher: aes-256-gcm
                    password: ss-pw
            """.trimIndent()
            val result = SubscriptionParser.parse(b64(yaml)).getOrThrow()

            assertThat(result.format).isEqualTo(SubscriptionFormat.CLASH_YAML)
            assertThat(result.nodes).hasSize(1)
        }
    }

    @Nested
    @DisplayName("Clash 的字段变体")
    inner class ClashVariants {

        private fun parseOne(proxyBody: String) =
            SubscriptionParser.parse("proxies:\n$proxyBody").getOrThrow().nodes.single()

        @Test
        fun `port 写成带引号的字符串且有尾随空格`() {
            val node = parseOne(
                """
                  - name: SS
                    type: ss
                    server: s.com
                    port: "8388 "
                    cipher: aes-256-gcm
                    password: pw
                """.trimIndent(),
            )
            assertThat(node.serverPort).isEqualTo(8388)
        }

        @Test
        fun `alpn 写成单个字符串而不是列表`() {
            val node = parseOne(
                """
                  - name: T
                    type: trojan
                    server: t.com
                    port: 443
                    password: pw
                    alpn: h2
                """.trimIndent(),
            )
            assertThat(node.tls?.alpn).containsExactly("h2")
        }

        @Test
        fun `老版本 Clash 的扁平 ws-path 与 ws-headers`() {
            // 大量机场模板仍在用 ws-opts 之前的写法。只认 ws-opts 会把路径丢掉，
            // 得到一个能保存、能显示、握手必然失败的节点。
            val node = parseOne(
                """
                  - name: VM
                    type: vmess
                    server: v.com
                    port: 443
                    uuid: 11111111-2222-3333-4444-555555555555
                    cipher: auto
                    network: ws
                    ws-path: /oldpath
                    ws-headers:
                      Host: old.cdn.com
                """.trimIndent(),
            )
            val ws = node.transport as TransportConfig.WebSocket
            assertThat(ws.path).isEqualTo("/oldpath")
            assertThat(ws.headers["Host"]).isEqualTo("old.cdn.com")
        }

        @Test
        fun `h2 传输层的 host 与 path 在 h2-opts 里`() {
            val node = parseOne(
                """
                  - name: H2
                    type: vmess
                    server: h.com
                    port: 443
                    uuid: 11111111-2222-3333-4444-555555555555
                    cipher: auto
                    tls: true
                    network: h2
                    h2-opts:
                      host:
                        - a.com
                        - b.com
                      path: /h2path
                """.trimIndent(),
            )
            val http = node.transport as TransportConfig.Http
            assertThat(http.host).containsExactly("a.com", "b.com")
            assertThat(http.path).isEqualTo("/h2path")
        }

        @Test
        fun `mihomo 的 Hysteria2 端口跳跃`() {
            val node = parseOne(
                """
                  - name: HY
                    type: hysteria2
                    server: hy.com
                    port: 443
                    password: pw
                    ports: "20000-30000"
                """.trimIndent(),
            )
            assertThat((node.params as ProtocolParams.Hysteria2).serverPorts)
                .containsExactly("20000:30000")
        }

        @Test
        fun `proxies 里混进非映射条目不影响其他节点`() {
            // 手工编辑过的 YAML 里出现一个裸字符串条目并不罕见。
            // 之前这会在收集失败节点名时再抛一次 ClassCastException，整份订阅一起完蛋。
            val yaml = """
                proxies:
                  - 这是一行乱入的字符串
                  - name: OK
                    type: ss
                    server: s.com
                    port: 8388
                    cipher: aes-256-gcm
                    password: pw
            """.trimIndent()
            val result = SubscriptionParser.parse(yaml).getOrThrow()

            assertThat(result.nodes.map { it.name }).containsExactly("OK")
            assertThat(result.failedEntries).hasSize(1)
        }

        @Test
        fun `超过 SnakeYAML 默认 3MB 限额的订阅仍能解析`() {
            // 上千节点的机场 YAML 很容易超过默认限额，超过之后是整份订阅直接失败，
            // 错误信息还是 SnakeYAML 的英文原文，用户无从下手
            val padding = "x".repeat(4 * 1024 * 1024)
            val yaml = "proxies:\n" +
                "  - name: \"$padding\"\n" +
                "    type: ss\n" +
                "    server: s.com\n" +
                "    port: 8388\n" +
                "    cipher: aes-256-gcm\n" +
                "    password: pw\n"
            assertThat(SubscriptionParser.parse(yaml).getOrThrow().nodes).hasSize(1)
        }

        @Test
        fun `带 Java 全局标签的 YAML 返回失败而不是构造对象`() {
            // 订阅正文是第三方给的，必须按不可信输入对待
            val yaml = """
                proxies:
                  - !!javax.script.ScriptEngineManager [!!java.net.URL ["http://127.0.0.1/"]]
            """.trimIndent()
            val result = runCatching { SubscriptionParser.parse(yaml) }
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().isFailure).isTrue()
        }
    }

    @Nested
    @DisplayName("JSON 的字段类型变体")
    inner class JsonVariants {

        @Test
        fun `outbounds 里混进字符串不影响其他节点`() {
            // sing-box 配置里 outbounds 元素理论上都是对象，但用户手工裁剪过的
            // 配置里出现字符串很常见 —— 之前 it.jsonObject 会抛异常，整份配置作废
            val content = """
                {
                  "outbounds": [
                    "direct",
                    null,
                    {
                      "type": "trojan", "tag": "T",
                      "server": "t.example.com", "server_port": "443",
                      "password": "pw",
                      "tls": { "enabled": true, "server_name": "t.example.com" }
                    }
                  ]
                }
            """.trimIndent()
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.nodes).hasSize(1)
            // server_port 写成字符串同样要认
            assertThat(result.nodes.single().serverPort).isEqualTo(443)
        }

        @Test
        fun `SIP008 里混进非对象条目不影响其他节点`() {
            val content = """
                {
                  "version": 1,
                  "servers": [
                    "坏条目",
                    {
                      "id": "x", "remarks": "SS-01",
                      "server": "s.example.com", "server_port": "8388",
                      "password": "pw", "method": "chacha20-ietf-poly1305"
                    }
                  ]
                }
            """.trimIndent()
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.nodes).hasSize(1)
            assertThat(result.nodes.single().serverPort).isEqualTo(8388)
        }
    }

    @Nested
    @DisplayName("证书校验")
    inner class CertVerification {

        @Test
        fun `链接列表订阅的忽略计数会一路带到订阅结果里`() {
            val content = "trojan://pw@a.com:443?allowInsecure=1#A\ntrojan://pw@b.com:443#B"
            val result = SubscriptionParser.parse(content).getOrThrow()

            assertThat(result.format).isEqualTo(SubscriptionFormat.PLAIN_LINKS)
            assertThat(result.nodes.none { it.tls?.insecure == true }).isTrue()
            // 分享链接那条路径自己已经清洗过一遍，订阅这一层不能再数第二次
            assertThat(result.ignoredInsecureCount).isEqualTo(1)
        }

        @Test
        fun `没有节点要求关闭校验时计数为零`() {
            val result = SubscriptionParser.parse("trojan://pw@a.com:443#A").getOrThrow()
            assertThat(result.ignoredInsecureCount).isEqualTo(0)
        }
    }
}
