package com.niceproxy.core.service.pac

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PAC 脚本的 HTTP 服务。
 *
 * sing-box 不提供 PAC 能力，所以这一块由应用自己实现。
 * 好在 PAC 的协议要求极低 —— 一个返回固定 MIME 的 GET 端点而已，
 * 不值得为它引入一个完整的 HTTP 框架。
 *
 * 见 docs/DESIGN.md §6.5。
 */
@Singleton
class PacServer @Inject constructor() {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /**
     * @param resolveScript 由调用方按「客户端访问用的主机名」生成脚本内容。
     *        传 host 进去而不是固定一个 IP，是因为同一台设备可能同时挂在
     *        Wi-Fi 和热点上，客户端从哪个网段进来，PAC 里就该给哪个地址 ——
     *        给错了客户端会指向一个它根本路由不到的 IP。
     */
    fun start(port: Int, resolveScript: (host: String) -> String) {
        stop()
        runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket

            acceptJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketException) {
                        // stop() 关闭 socket 会让阻塞中的 accept 抛异常，属正常退出
                        break
                    } catch (e: IOException) {
                        Log.w(TAG, "PAC accept 失败", e)
                        break
                    }
                    launch { serve(client, resolveScript) }
                }
            }
        }.onFailure {
            Log.w(TAG, "PAC 服务启动失败", it)
            stop()
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    suspend fun stopAndJoin() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancelAndJoin()
        acceptJob = null
    }

    private fun serve(client: Socket, resolveScript: (String) -> String) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return

                val (method, path) = parseRequestLine(requestLine) ?: return
                val host = readHostHeader(reader)
                    ?: socket.localAddress.hostAddress
                    ?: "127.0.0.1"

                val response = when {
                    method != "GET" -> errorResponse(405, "Method Not Allowed")
                    !path.startsWith(PAC_PATH) -> errorResponse(404, "Not Found")
                    // Host 头带端口，PAC 里要的是纯主机名
                    else -> pacResponse(resolveScript(host.substringBefore(':')))
                }

                socket.getOutputStream().apply {
                    write(response.toByteArray(Charsets.UTF_8))
                    flush()
                }
            }.onFailure {
                if (it !is IOException) Log.w(TAG, "PAC 请求处理失败", it)
            }
        }
    }

    private fun parseRequestLine(line: String): Pair<String, String>? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        return parts[0].uppercase() to parts[1]
    }

    private fun readHostHeader(reader: BufferedReader): String? {
        var host: String? = null
        // 只读到空行为止；PAC 请求没有 body，不必消费更多
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            if (line.startsWith("Host:", ignoreCase = true)) {
                host = line.substringAfter(':').trim()
            }
        }
        return host?.takeIf { it.isNotBlank() }
    }

    private fun pacResponse(script: String): String {
        val body = script.toByteArray(Charsets.UTF_8)
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            // 这个 MIME 是 PAC 的事实标准，用 text/plain 会让部分系统拒绝解析
            append("Content-Type: application/x-ns-proxy-autoconfig; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            // 配置随时可能变，绝不能让客户端缓存
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Connection: close\r\n\r\n")
            append(script)
        }
    }

    private fun errorResponse(code: Int, reason: String): String =
        "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    companion object {
        const val PAC_PATH = "/proxy.pac"
        private const val TAG = "PacServer"
        private const val READ_TIMEOUT_MS = 5_000
    }
}
