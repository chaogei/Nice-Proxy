package com.niceproxy.core.config.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把用户填写的 DNS 地址字符串翻译成 sing-box 1.12+ 的类型化 DNS 服务器对象。
 *
 * 1.12 起 DNS 服务器从「一个 address 字符串」改成了 `{type, server, server_port, ...}`
 * 的结构，旧写法在 1.13 已不受支持。用户习惯填的仍然是 URL 形式，所以在这里做转换。
 */
internal object DnsServerParser {

    fun parse(tag: String, raw: String, detour: String?): JsonObject {
        val value = raw.trim()
        if (value.isEmpty() || value.equals("local", ignoreCase = true)) {
            return buildJsonObject {
                put("type", "local")
                put("tag", tag)
            }
        }

        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = if (scheme.isEmpty()) value else value.substringAfter("://")

        return when (scheme) {
            "", "udp" -> hostServer("udp", tag, rest, defaultPort = 53, detour = detour)
            "tcp" -> hostServer("tcp", tag, rest, defaultPort = 53, detour = detour)
            "tls" -> hostServer("tls", tag, rest, defaultPort = 853, detour = detour)
            "quic" -> hostServer("quic", tag, rest, defaultPort = 853, detour = detour)
            "https" -> pathServer("https", tag, rest, defaultPort = 443, detour = detour)
            "h3" -> pathServer("h3", tag, rest, defaultPort = 443, detour = detour)
            "dhcp" -> buildJsonObject {
                put("type", "dhcp")
                put("tag", tag)
            }
            "rcode" -> buildJsonObject {
                put("type", "predefined")
                put("tag", tag)
                put("rcode", rest)
            }
            else -> hostServer("udp", tag, value, defaultPort = 53, detour = detour)
        }
    }

    private fun hostServer(
        type: String,
        tag: String,
        hostPort: String,
        defaultPort: Int,
        detour: String?,
    ): JsonObject {
        val (host, port) = splitHostPort(hostPort.substringBefore('/'))
        return buildJsonObject {
            put("type", type)
            put("tag", tag)
            put("server", host)
            if (port != null && port != defaultPort) put("server_port", port)
            putIfNotBlank("detour", detour)
        }
    }

    private fun pathServer(
        type: String,
        tag: String,
        rest: String,
        defaultPort: Int,
        detour: String?,
    ): JsonObject {
        val authority = rest.substringBefore('/')
        val path = rest.substringAfter('/', missingDelimiterValue = "").let { if (it.isEmpty()) null else "/$it" }
        val (host, port) = splitHostPort(authority)
        return buildJsonObject {
            put("type", type)
            put("tag", tag)
            put("server", host)
            if (port != null && port != defaultPort) put("server_port", port)
            // sing-box 默认 /dns-query，与绝大多数 DoH 提供方一致，相同时不必写出。
            if (path != null && path != "/dns-query") put("path", path)
            putIfNotBlank("detour", detour)
        }
    }

    /** 兼容 IPv6 字面量 `[2001:4860:4860::8888]:853`。 */
    private fun splitHostPort(value: String): Pair<String, Int?> {
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close > 0) {
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val colon = value.lastIndexOf(':')
        // 裸 IPv6 地址含多个冒号，不能按冒号切分。
        if (colon > 0 && value.count { it == ':' } == 1) {
            val port = value.substring(colon + 1).toIntOrNull()
            if (port != null) return value.substring(0, colon) to port
        }
        return value to null
    }
}
