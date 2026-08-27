package com.niceproxy.core.service.pac

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * PacServer 是手写的 HTTP 服务，socket 层没有任何框架兜底，所以这里一律绑真实端口、
 * 发真实请求、按字节校验响应，而不是去测内部方法 —— 会出问题的正是内部方法之间的接缝。
 */
class PacServerTest {

    private val server = PacServer()
    private val port = freePort()

    @AfterEach
    fun tearDown() = runBlocking { server.stopAndJoin() }

    @Test
    @DisplayName("GET /proxy.pac 返回 200、PAC 专用 MIME 与禁止缓存")
    fun servesScript() {
        server.start(port) { "// pac" }

        val response = request("GET /proxy.pac HTTP/1.1", "Host: 192.168.1.8:8090")

        assertThat(response.statusLine).isEqualTo("HTTP/1.1 200 OK")
        // 用 text/plain 会让部分系统拒绝解析 PAC，这个 MIME 是事实标准
        assertThat(response.header("Content-Type"))
            .isEqualTo("application/x-ns-proxy-autoconfig; charset=utf-8")
        // 配置随时可能变，客户端缓存住旧脚本就会一直指向错误的代理
        assertThat(response.header("Cache-Control")).contains("no-store")
        assertThat(response.body).isEqualTo("// pac")
        assertThat(response.header("Content-Length")).isEqualTo("6")
    }

    @Test
    @DisplayName("脚本按请求方的 Host 头生成，Host 里的端口被剥掉")
    fun scriptFollowsHostHeader() {
        // 设备可能同时挂在 Wi-Fi 和热点上，客户端从哪个网段进来就得给哪个地址，
        // 给错了它会拿到一个根本路由不到的 IP
        server.start(port) { host ->
            PacScript.build(PacScript.Options(host = host, httpPort = 8080, socksPort = 1080))
        }

        assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 192.168.43.1:8090").body)
            .contains("PROXY 192.168.43.1:8080; SOCKS5 192.168.43.1:1080; DIRECT")
        assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 10.0.0.5:8090").body)
            .contains("PROXY 10.0.0.5:8080; SOCKS5 10.0.0.5:1080; DIRECT")
    }

    @Test
    @DisplayName("Host 头缺失时回落到本机地址，而不是给出空主机名")
    fun fallsBackWhenHostMissing() {
        server.start(port) { host -> "host=$host" }

        val response = request("GET /proxy.pac HTTP/1.1")

        assertThat(response.statusLine).isEqualTo("HTTP/1.1 200 OK")
        assertThat(response.body).isEqualTo("host=127.0.0.1")
    }

    @Test
    @DisplayName("非 GET 方法返回 405，且不去生成脚本")
    fun rejectsNonGet() {
        var generated = false
        server.start(port) { generated = true; "// pac" }

        val response = request("POST /proxy.pac HTTP/1.1", "Host: 127.0.0.1")

        assertThat(response.statusLine).isEqualTo("HTTP/1.1 405 Method Not Allowed")
        assertThat(response.body).isEmpty()
        assertThat(generated).isFalse()
    }

    @Test
    @DisplayName("其他路径返回 404")
    fun rejectsOtherPaths() {
        server.start(port) { "// pac" }

        val response = request("GET /favicon.ico HTTP/1.1", "Host: 127.0.0.1")

        assertThat(response.statusLine).isEqualTo("HTTP/1.1 404 Not Found")
        assertThat(response.body).isEmpty()
    }

    @Test
    @DisplayName("连接用完即关，同一个服务能连续处理多个请求")
    fun servesSequentialRequests() {
        server.start(port) { "// pac" }

        repeat(3) {
            assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").statusLine)
                .isEqualTo("HTTP/1.1 200 OK")
        }
    }

    @Test
    @DisplayName("stop 之后端口立刻释放")
    fun stopReleasesPort() {
        server.start(port) { "// pac" }
        assertThat(server.isRunning).isTrue()

        runBlocking { server.stopAndJoin() }

        assertThat(server.isRunning).isFalse()
        // 端口没释放的话下一次启动会撞上「地址已被占用」
        assertThrows<IOException> { request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1") }
    }

    @Test
    @DisplayName("重复 start 不会把旧的监听遗留下来")
    fun restartRebindsSamePort() {
        server.start(port) { "// first" }
        server.start(port) { "// second" }

        assertThat(request("GET /proxy.pac HTTP/1.1", "Host: 127.0.0.1").body).isEqualTo("// second")
    }

    // ---------------------------------------------------------------- 测试工具

    private fun request(vararg lines: String): RawResponse =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write((lines.joinToString(CRLF) + CRLF + CRLF).toByteArray())
                flush()
            }
            // 服务端响应完就关连接，读到 EOF 即为完整响应
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val head = raw.substringBefore(CRLF + CRLF).split(CRLF)
            RawResponse(
                statusLine = head.first(),
                headers = head.drop(1),
                body = raw.substringAfter(CRLF + CRLF, ""),
            )
        }

    private data class RawResponse(
        val statusLine: String,
        val headers: List<String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CRLF = "\r\n"
        const val TIMEOUT_MS = 5_000

        /** 借系统分配一个当前空闲的端口，避免与开发机上已占用的端口撞车。 */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
