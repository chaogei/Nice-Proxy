package com.niceproxy.core.config.share

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.TransportConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * 分享链接解析是最容易出隐蔽 bug 的地方 —— Base64 变体、URL 编码、
 * 字段别名、厂商私有扩展，每一处都可能让导入静默失败。
 * 这里的样本尽量贴近各家客户端实际导出的形态。
 */
class ShareLinkParsersTest {

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())

    private fun b64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    @Nested
    @DisplayName("Shadowsocks")
    inner class Shadowsocks {

        @Test
        fun `SIP002 编码用户信息`() {
            val userInfo = b64Url("aes-256-gcm:my-password")
            val node = ShareLinkParsers.parse("ss://$userInfo@example.com:8388#香港节点").getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.SHADOWSOCKS)
            assertThat(node.server).isEqualTo("example.com")
            assertThat(node.serverPort).isEqualTo(8388)
            assertThat(node.name).isEqualTo("香港节点")
            val params = node.params as ProtocolParams.Shadowsocks
            assertThat(params.method).isEqualTo("aes-256-gcm")
            assertThat(params.password).isEqualTo("my-password")
        }

        @Test
        fun `SIP002 明文用户信息`() {
            val node = ShareLinkParsers.parse("ss://aes-128-gcm:pw@1.2.3.4:8388#node").getOrThrow()
            val params = node.params as ProtocolParams.Shadowsocks
            assertThat(params.method).isEqualTo("aes-128-gcm")
            assertThat(params.password).isEqualTo("pw")
        }

        @Test
        fun `传统整体 Base64 编码`() {
            val payload = b64("chacha20-ietf-poly1305:secret@example.org:443")
            val node = ShareLinkParsers.parse("ss://$payload#旧格式").getOrThrow()

            assertThat(node.server).isEqualTo("example.org")
            assertThat(node.serverPort).isEqualTo(443)
            val params = node.params as ProtocolParams.Shadowsocks
            assertThat(params.method).isEqualTo("chacha20-ietf-poly1305")
            assertThat(params.password).isEqualTo("secret")
        }

        @Test
        fun `plugin 参数拆分为插件名与选项`() {
            val userInfo = b64Url("aes-128-gcm:pw")
            val link = "ss://$userInfo@h.com:443?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#n"
            val params = ShareLinkParsers.parse(link).getOrThrow().params as ProtocolParams.Shadowsocks

            assertThat(params.plugin).isEqualTo("obfs-local")
            assertThat(params.pluginOpts).isEqualTo("obfs=http;obfs-host=bing.com")
        }
    }

    @Nested
    @DisplayName("VMess")
    inner class VMess {

        @Test
        fun `v2rayN 的 Base64 JSON 格式`() {
            val payload = b64(
                """
                {"v":"2","ps":"日本 01","add":"jp.example.com","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811",
                 "aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray",
                 "tls":"tls","sni":"jp.example.com","alpn":"h2,http/1.1","fp":"chrome"}
                """.trimIndent(),
            )
            val node = ShareLinkParsers.parse("vmess://$payload").getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.VMESS)
            assertThat(node.name).isEqualTo("日本 01")
            assertThat(node.server).isEqualTo("jp.example.com")
            assertThat(node.serverPort).isEqualTo(443)

            val params = node.params as ProtocolParams.VMess
            assertThat(params.uuid).isEqualTo("b831381d-6324-4d53-ad4f-8cda48b30811")

            val ws = node.transport as TransportConfig.WebSocket
            assertThat(ws.path).isEqualTo("/ray")
            assertThat(ws.headers["Host"]).isEqualTo("cdn.example.com")

            assertThat(node.tls?.enabled).isTrue()
            assertThat(node.tls?.serverName).isEqualTo("jp.example.com")
            assertThat(node.tls?.alpn).containsExactly("h2", "http/1.1")
            assertThat(node.tls?.utls?.fingerprint).isEqualTo("chrome")
        }

        @Test
        fun `未开启 TLS 时不生成 tls 段`() {
            val payload = b64("""{"ps":"n","add":"h.com","port":"80","id":"uuid-x","net":"tcp","tls":""}""")
            val node = ShareLinkParsers.parse("vmess://$payload").getOrThrow()
            assertThat(node.tls).isNull()
            assertThat(node.transport).isNull()
        }
    }

    @Nested
    @DisplayName("VLESS")
    inner class VLess {

        @Test
        fun `REALITY + Vision 流控`() {
            val link = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443" +
                "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome" +
                "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12&type=tcp" +
                "&flow=xtls-rprx-vision#REALITY%20节点"
            val node = ShareLinkParsers.parse(link).getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.VLESS)
            assertThat(node.name).isEqualTo("REALITY 节点")
            assertThat((node.params as ProtocolParams.VLess).flow).isEqualTo("xtls-rprx-vision")
            assertThat(node.tls?.reality?.publicKey).isEqualTo("jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0")
            assertThat(node.tls?.reality?.shortId).isEqualTo("ab12")
            assertThat(node.tls?.utls?.fingerprint).isEqualTo("chrome")
            // type=tcp 是默认传输层，不应生成 transport 对象
            assertThat(node.transport).isNull()
        }

        @Test
        fun `gRPC 传输层`() {
            val link = "vless://uuid-1@h.com:443?security=tls&type=grpc&serviceName=my-svc#n"
            val node = ShareLinkParsers.parse(link).getOrThrow()
            assertThat((node.transport as TransportConfig.Grpc).serviceName).isEqualTo("my-svc")
        }

        @Test
        fun `无 security 参数时不启用 TLS`() {
            val node = ShareLinkParsers.parse("vless://uuid-1@h.com:80?type=tcp#n").getOrThrow()
            assertThat(node.tls).isNull()
        }
    }

    @Nested
    @DisplayName("Trojan")
    inner class Trojan {

        @Test
        fun `即使链接未写 security 也强制启用 TLS`() {
            val node = ShareLinkParsers.parse("trojan://pw@h.com:443#n").getOrThrow()
            assertThat(node.tls?.enabled).isTrue()
            assertThat((node.params as ProtocolParams.Trojan).password).isEqualTo("pw")
        }

        @Test
        fun `跳过证书校验参数的多种别名`() {
            listOf("allowInsecure=1", "insecure=true", "skip-cert-verify=1").forEach { param ->
                val node = ShareLinkParsers.parse("trojan://pw@h.com:443?$param#n").getOrThrow()
                assertThat(node.tls?.insecure).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("Hysteria2")
    inner class Hysteria2 {

        @Test
        fun `含混淆与端口跳跃`() {
            val link = "hysteria2://my-pass@example.com:443" +
                "?sni=example.com&obfs=salamander&obfs-password=ob-pw&mport=20000-30000#HY2"
            val node = ShareLinkParsers.parse(link).getOrThrow()

            assertThat(node.protocol).isEqualTo(ProxyProtocol.HYSTERIA2)
            val params = node.params as ProtocolParams.Hysteria2
            assertThat(params.password).isEqualTo("my-pass")
            assertThat(params.obfsType).isEqualTo("salamander")
            assertThat(params.obfsPassword).isEqualTo("ob-pw")
            // 链接里用连字符表示范围，sing-box 要冒号
            assertThat(params.serverPorts).containsExactly("20000:30000")
            assertThat(node.tls?.enabled).isTrue()
        }

        @Test
        fun `hy2 简写等价于 hysteria2`() {
            val node = ShareLinkParsers.parse("hy2://pw@h.com:443#n").getOrThrow()
            assertThat(node.protocol).isEqualTo(ProxyProtocol.HYSTERIA2)
        }
    }

    @Nested
    @DisplayName("TUIC / AnyTLS / SOCKS / HTTP")
    inner class Others {

        @Test
        fun `TUIC 的 uuid 与密码由冒号分隔`() {
            val link = "tuic://uuid-abc:pass-xyz@h.com:443?congestion_control=bbr&alpn=h3&sni=h.com#T"
            val params = ShareLinkParsers.parse(link).getOrThrow().params as ProtocolParams.Tuic
            assertThat(params.uuid).isEqualTo("uuid-abc")
            assertThat(params.password).isEqualTo("pass-xyz")
            assertThat(params.congestionControl).isEqualTo("bbr")
        }

        @Test
        fun `AnyTLS`() {
            val node = ShareLinkParsers.parse("anytls://pw@h.com:443?sni=h.com#A").getOrThrow()
            assertThat(node.protocol).isEqualTo(ProxyProtocol.ANYTLS)
            assertThat(node.tls?.enabled).isTrue()
        }

        @Test
        fun `SOCKS5 带认证`() {
            val node = ShareLinkParsers.parse("socks5://alice:s3cret@h.com:1080#S").getOrThrow()
            val params = node.params as ProtocolParams.Socks
            assertThat(params.version).isEqualTo("5")
            assertThat(params.username).isEqualTo("alice")
            assertThat(params.password).isEqualTo("s3cret")
        }

        @Test
        fun `HTTPS 代理启用 TLS`() {
            val node = ShareLinkParsers.parse("https://u:p@h.com:8443#H").getOrThrow()
            assertThat(node.protocol).isEqualTo(ProxyProtocol.HTTP)
            assertThat(node.tls?.enabled).isTrue()
        }
    }

    @Nested
    @DisplayName("健壮性")
    inner class Robustness {

        @Test
        fun `IPv6 字面量`() {
            val node = ShareLinkParsers.parse("trojan://pw@[2001:db8::1]:443#v6").getOrThrow()
            assertThat(node.server).isEqualTo("2001:db8::1")
            assertThat(node.serverPort).isEqualTo(443)
        }

        @Test
        fun `密码中含未编码的 @ 符号`() {
            // java.net.URI 会在这里抛异常，手写拆解取最后一个 @ 才能正确处理
            val node = ShareLinkParsers.parse("trojan://p@ss@h.com:443#n").getOrThrow()
            assertThat(node.server).isEqualTo("h.com")
            assertThat((node.params as ProtocolParams.Trojan).password).isEqualTo("p@ss")
        }

        @Test
        fun `不支持的协议返回失败而不是抛异常`() {
            val result = ShareLinkParsers.parse("wireguard://whatever")
            assertThat(result.isFailure).isTrue()
        }

        @Test
        fun `端口越界被拒绝`() {
            assertThat(ShareLinkParsers.parse("trojan://pw@h.com:70000#n").isFailure).isTrue()
        }

        @Test
        fun `批量解析跳过坏行并报告数量`() {
            val text = """
                trojan://pw@a.com:443#A

                # 这是注释
                这不是链接
                hy2://pw@b.com:443#B
            """.trimIndent()
            val result = ShareLinkParsers.parseMany(text)

            assertThat(result.nodes).hasSize(2)
            assertThat(result.nodes.map { it.name }).containsExactly("A", "B")
            assertThat(result.failedLines).hasSize(1)
        }
    }

    @Nested
    @DisplayName("不可见字符")
    inner class InvisibleCharacters {

        @Test
        fun `记事本存的 UTF-8 文件带 BOM`() {
            // Windows 记事本默认给 UTF-8 加 BOM，用户从这种文件里复制链接时会一起带上。
            // BOM 不是 trim() 认的空白，留着会让 scheme 变成 "\uFEFFtrojan" 而整条解析失败。
            val node = ShareLinkParsers.parse("\uFEFFtrojan://pw@h.com:443#n").getOrThrow()
            assertThat(node.server).isEqualTo("h.com")
        }

        @Test
        fun `网页复制夹带的零宽空格`() {
            // 从机场官网或 Telegram 频道复制链接时很容易混进 U+200B，
            // 落在 Base64 中间会直接让解码失败
            val payload = b64("""{"ps":"n","add":"h.com","port":"443","id":"u","net":"tcp"}""")
            val dirty = "vmess://" + payload.take(8) + "\u200B" + payload.drop(8)
            assertThat(ShareLinkParsers.parse(dirty).getOrThrow().server).isEqualTo("h.com")
        }

        @Test
        fun `聊天软件把首尾空格换成了不换行空格`() {
            // U+00A0 在 Character.isWhitespace 下为 false，String.trim 清不掉
            val node = ShareLinkParsers.parse("\u00A0trojan://pw@h.com:443#n\u00A0").getOrThrow()
            assertThat(node.server).isEqualTo("h.com")
        }
    }

    @Nested
    @DisplayName("百分号编码")
    inner class PercentEncoding {

        @Test
        fun `密码里的加号不能被解成空格`() {
            // Shadowsocks 密码和机场生成的 Trojan 密码常是标准 Base64，含 '+'。
            // URLDecoder 按 form-urlencoded 把 '+' 当空格，结果是一个
            // 能导入、能显示、就是连不上的节点 —— 比导入失败还难排查。
            val params = ShareLinkParsers.parse("trojan://ab+cd/ef@h.com:443#n")
                .getOrThrow().params as ProtocolParams.Trojan
            assertThat(params.password).isEqualTo("ab+cd/ef")
        }

        @Test
        fun `REALITY 公钥里的加号不能被解成空格`() {
            val link = "vless://uuid-1@h.com:443?security=reality&pbk=jNXH+1yRo0/DuchQ&sid=ab#n"
            assertThat(ShareLinkParsers.parse(link).getOrThrow().tls?.reality?.publicKey)
                .isEqualTo("jNXH+1yRo0/DuchQ")
        }

        @Test
        fun `节点名里的裸百分号不影响其余转义`() {
            // 机场爱把倍率写进节点名：「香港 50%off」。URLDecoder 撞上 "%of" 会整串抛异常，
            // 之前的兜底是返回原文，于是同一个名字里合法的 %20 也跟着不解码了
            val node = ShareLinkParsers.parse("trojan://pw@h.com:443#%E9%A6%99%E6%B8%AF%2050%off")
                .getOrThrow()
            assertThat(node.name).isEqualTo("香港 50%off")
        }

        @Test
        fun `emoji 节点名在解码后不被拆散`() {
            val node = ShareLinkParsers.parse("trojan://pw@h.com:443#%F0%9F%9A%80").getOrThrow()
            assertThat(node.name).isEqualTo("\uD83D\uDE80")
        }
    }

    @Nested
    @DisplayName("省略端口")
    inner class OmittedPort {

        @Test
        fun `trojan 省略 443`() {
            // 面板生成的链接省掉默认端口是合法的 URI 写法，但之前会整条解析失败
            val node = ShareLinkParsers.parse("trojan://pw@h.com#n").getOrThrow()
            assertThat(node.serverPort).isEqualTo(443)
        }

        @Test
        fun `hysteria2 省略 443`() {
            assertThat(ShareLinkParsers.parse("hy2://pw@h.com#n").getOrThrow().serverPort)
                .isEqualTo(443)
        }

        @Test
        fun `IPv6 字面量省略端口`() {
            val node = ShareLinkParsers.parse("trojan://pw@[2001:db8::1]#n").getOrThrow()
            assertThat(node.server).isEqualTo("2001:db8::1")
            assertThat(node.serverPort).isEqualTo(443)
        }

        @Test
        fun `http 与 https 刻意不补默认端口`() {
            // 否则订阅正文里一条「官网地址」公告就会被当成 HTTP 代理节点导进来
            assertThat(ShareLinkParsers.parse("https://example.com/notice").isFailure).isTrue()
            assertThat(ShareLinkParsers.parse("http://example.com/sub?token=x").isFailure).isTrue()
        }
    }

    @Nested
    @DisplayName("用户信息的 Base64 歧义")
    inner class UserInfoDecoding {

        @Test
        fun `只有用户名的 HTTP 代理不被当成 Base64 硬解`() {
            // "admin1" 恰好是合法 Base64（补两个 = 之后），硬解会得到一串乱码用户名。
            // 只有解出来确实含冒号才能当作 user:pass 采信。
            val params = ShareLinkParsers.parse("http://admin1@h.com:8080#n")
                .getOrThrow().params as ProtocolParams.Http
            assertThat(params.username).isEqualTo("admin1")
            assertThat(params.password).isNull()
        }

        @Test
        fun `ss 的用户信息解不出冒号时明确失败`() {
            // 与其导入一个加密方式是乱码的节点，不如让用户看到「解码失败」
            val result = ShareLinkParsers.parse("ss://YWJjZGVmZ2g@h.com:8388#n")
            assertThat(result.isFailure).isTrue()
        }
    }

    @Nested
    @DisplayName("VMess 的字段类型与在野变体")
    inner class VMessVariants {

        @Test
        fun `port 与 aid 写成数字`() {
            // v2rayN 写字符串，不少面板直接写 JSON 数字
            val payload = b64("""{"ps":"n","add":"h.com","port":443,"id":"u","aid":2,"net":"tcp"}""")
            val node = ShareLinkParsers.parse("vmess://$payload").getOrThrow()
            assertThat(node.serverPort).isEqualTo(443)
            assertThat((node.params as ProtocolParams.VMess).alterId).isEqualTo(2)
        }

        @Test
        fun `alpn 写成 JSON 数组`() {
            // 之前 obj["alpn"].jsonPrimitive 遇到数组会抛异常，整个节点直接丢失
            val payload = b64(
                """{"ps":"n","add":"h.com","port":"443","id":"u","net":"tcp","tls":"tls",""" +
                    """"alpn":["h2","http/1.1"]}""",
            )
            val node = ShareLinkParsers.parse("vmess://$payload").getOrThrow()
            assertThat(node.tls?.alpn).containsExactly("h2", "http/1.1")
        }

        @Test
        fun `tls 写成布尔真值`() {
            val payload = b64("""{"ps":"n","add":"h.com","port":"443","id":"u","net":"tcp","tls":true}""")
            assertThat(ShareLinkParsers.parse("vmess://$payload").getOrThrow().tls?.enabled).isTrue()
        }

        @Test
        fun `Base64 后面又缀了备注`() {
            // 部分订阅生成器会在 vmess:// 的 Base64 后面加 #名字，整体解码必然失败
            val payload = b64("""{"add":"h.com","port":"443","id":"u","net":"tcp"}""")
            val node = ShareLinkParsers.parse("vmess://$payload#我的节点").getOrThrow()
            assertThat(node.server).isEqualTo("h.com")
            assertThat(node.name).isEqualTo("我的节点")
        }

        @Test
        fun `Shadowrocket 导出的 URI 形态`() {
            // iOS 用户分享给 Android 用户的多半长这样：
            // userinfo 是 Base64(加密方式:uuid)，传输层写在 obfs / obfsParam 里
            val userInfo = b64Url("auto:11111111-2222-3333-4444-555555555555@h.com:443")
            val link = "vmess://$userInfo?remarks=HK&obfs=websocket&path=/ws" +
                "&obfsParam=cdn.example.com&tls=1"
            val node = ShareLinkParsers.parse(link).getOrThrow()

            assertThat(node.name).isEqualTo("HK")
            assertThat(node.server).isEqualTo("h.com")
            assertThat((node.params as ProtocolParams.VMess).uuid)
                .isEqualTo("11111111-2222-3333-4444-555555555555")
            val ws = node.transport as TransportConfig.WebSocket
            assertThat(ws.path).isEqualTo("/ws")
            assertThat(ws.headers["Host"]).isEqualTo("cdn.example.com")
            assertThat(node.tls?.enabled).isTrue()
        }

        @Test
        fun `VMessAEAD 提案的纯 URI 形态`() {
            // NekoBox 与新版 v2rayN 都在用，写法与 vless 一致
            val link = "vmess://11111111-2222-3333-4444-555555555555@h.com:443" +
                "?type=ws&security=tls&path=/x&host=cdn.com&sni=cdn.com#HK%2001"
            val node = ShareLinkParsers.parse(link).getOrThrow()

            assertThat(node.name).isEqualTo("HK 01")
            assertThat(node.serverPort).isEqualTo(443)
            assertThat((node.params as ProtocolParams.VMess).uuid)
                .isEqualTo("11111111-2222-3333-4444-555555555555")
            assertThat((node.transport as TransportConfig.WebSocket).path).isEqualTo("/x")
            assertThat(node.tls?.serverName).isEqualTo("cdn.com")
        }
    }

    @Nested
    @DisplayName("畸形输入")
    inner class Malformed {

        @Test
        fun `fragment 里含井号与问号`() {
            val node = ShareLinkParsers.parse("trojan://pw@h.com:443?sni=a.com#节点#1?x").getOrThrow()
            assertThat(node.name).isEqualTo("节点#1?x")
            assertThat(node.tls?.serverName).isEqualTo("a.com")
        }

        @Test
        fun `insecure 写成 yes`() {
            // Clash 转换器生成的链接里常见
            assertThat(ShareLinkParsers.parse("trojan://pw@h.com:443?insecure=yes#n")
                .getOrThrow().tls?.insecure).isTrue()
        }

        @Test
        fun `各种残缺链接一律返回失败而不是抛异常`() {
            // 解析器被喂到的内容完全不受控：剪贴板、二维码、订阅正文。
            // 只要有一条能把异常抛到调用栈上，导入流程就整体崩掉。
            listOf(
                "vmess://",
                "ss://",
                "ss://####",
                "ss://@@@@",
                "trojan://@:",
                "trojan://pw@h.com:99999999999999",
                "trojan://pw@:443",
                "vless://@h.com:443",
                "hysteria2://",
                "tuic://:@h.com:443",
                "vmess://%%%%%%%%",
                "://",
                "?#",
            ).forEach { link ->
                val result = runCatching { ShareLinkParsers.parse(link) }
                assertThat(result.isSuccess).isTrue()
                assertThat(result.getOrThrow().isFailure).isTrue()
            }
        }

        @Test
        fun `超长与全垃圾的输入不会把异常抛到调用栈上`() {
            listOf(
                "socks5://" + "a".repeat(50_000),
                "vmess://" + "A".repeat(50_000),
                "ss://" + "\u0000\uFFFD".repeat(500),
                "trojan://" + "@".repeat(500) + "h.com:443",
            ).forEach { link ->
                assertThat(runCatching { ShareLinkParsers.parse(link) }.isSuccess).isTrue()
            }
        }
    }
}
