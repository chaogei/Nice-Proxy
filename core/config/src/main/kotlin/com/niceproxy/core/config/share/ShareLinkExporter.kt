package com.niceproxy.core.config.share

import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TransportConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.io.encoding.Base64

/**
 * 把节点导出回分享链接，用于「复制链接」与生成二维码。
 *
 * 导出格式刻意与主流客户端保持一致（v2rayN 的 vmess Base64 JSON、
 * Xray 的 vless/trojan URI），这样导出的链接能被别的客户端直接导入 ——
 * 只能被自己读懂的链接没有分享价值。
 */
object ShareLinkExporter {

    private val json = Json { encodeDefaults = false }

    /**
     * SIP002 规定 ss:// 的 userinfo 用 URL 安全表且不带填充。
     *
     * 这里用 [kotlin.io.encoding.Base64] 而不是 `java.util.Base64`：
     * 后者要 API 26，minSdk 是 24。本模块是纯 JVM 模块，用错了编译和单测都不会报，
     * 只有 Android 7.x 真机会崩。
     */
    private val URL_SAFE_NO_PAD =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun export(node: ServerProfile): String? = runCatching {
        when (val params = node.params) {
            is ProtocolParams.Shadowsocks -> exportShadowsocks(node, params)
            is ProtocolParams.VMess -> exportVMess(node, params)
            is ProtocolParams.VLess -> exportVLess(node, params)
            is ProtocolParams.Trojan -> exportTrojan(node, params)
            is ProtocolParams.Hysteria2 -> exportHysteria2(node, params)
            is ProtocolParams.Tuic -> exportTuic(node, params)
            is ProtocolParams.AnyTls -> exportAnyTls(node, params)
            is ProtocolParams.Socks -> exportSocks(node, params)
            is ProtocolParams.Http -> exportHttp(node, params)
            // 其余协议没有社区通用的链接格式，导出会产生别的客户端读不懂的东西
            else -> null
        }
    }.getOrNull()

    private fun exportShadowsocks(node: ServerProfile, p: ProtocolParams.Shadowsocks): String {
        val userInfo = URL_SAFE_NO_PAD.encode("${p.method}:${p.password}".toByteArray())
        val query = buildQuery(
            "plugin" to listOfNotNull(p.plugin, p.pluginOpts).joinToString(";").ifBlank { null },
        )
        return "ss://$userInfo@${node.hostPort()}$query#${node.encodedName()}"
    }

    private fun exportVMess(node: ServerProfile, p: ProtocolParams.VMess): String {
        val ws = node.transport as? TransportConfig.WebSocket
        val obj = JsonObject(
            buildMap {
                put("v", JsonPrimitive("2"))
                put("ps", JsonPrimitive(node.name))
                put("add", JsonPrimitive(node.server))
                put("port", JsonPrimitive(node.serverPort.toString()))
                put("id", JsonPrimitive(p.uuid))
                put("aid", JsonPrimitive(p.alterId.toString()))
                put("scy", JsonPrimitive(p.security))
                put("net", JsonPrimitive(node.transport?.singBoxType ?: "tcp"))
                put("type", JsonPrimitive("none"))
                put("host", JsonPrimitive(ws?.headers?.get("Host").orEmpty()))
                put("path", JsonPrimitive(ws?.path.orEmpty()))
                put("tls", JsonPrimitive(if (node.tls?.enabled == true) "tls" else ""))
                node.tls?.serverName?.let { put("sni", JsonPrimitive(it)) }
                node.tls?.utls?.fingerprint?.let { put("fp", JsonPrimitive(it)) }
                node.tls?.alpn?.takeIf { it.isNotEmpty() }
                    ?.let { put("alpn", JsonPrimitive(it.joinToString(","))) }
            },
        )
        val payload = Base64.Default
            .encode(json.encodeToString(JsonObject.serializer(), obj).toByteArray())
        return "vmess://$payload"
    }

    private fun exportVLess(node: ServerProfile, p: ProtocolParams.VLess): String {
        val query = buildQuery(
            "encryption" to "none",
            "flow" to p.flow,
            *node.tlsQueryPairs(),
            *node.transportQueryPairs(),
        )
        return "vless://${p.uuid}@${node.hostPort()}$query#${node.encodedName()}"
    }

    private fun exportTrojan(node: ServerProfile, p: ProtocolParams.Trojan): String {
        val query = buildQuery(*node.tlsQueryPairs(), *node.transportQueryPairs())
        return "trojan://${encode(p.password)}@${node.hostPort()}$query#${node.encodedName()}"
    }

    private fun exportHysteria2(node: ServerProfile, p: ProtocolParams.Hysteria2): String {
        val query = buildQuery(
            "sni" to node.tls?.serverName,
            "insecure" to if (node.tls?.insecure == true) "1" else null,
            "obfs" to p.obfsType,
            "obfs-password" to p.obfsPassword,
            // 导出时把冒号换回连字符，社区链接格式用的是 20000-30000
            "mport" to p.serverPorts.joinToString(",") { it.replace(':', '-') }.ifBlank { null },
        )
        return "hysteria2://${encode(p.password)}@${node.hostPort()}$query#${node.encodedName()}"
    }

    private fun exportTuic(node: ServerProfile, p: ProtocolParams.Tuic): String {
        val query = buildQuery(
            "congestion_control" to p.congestionControl,
            "udp_relay_mode" to p.udpRelayMode,
            "sni" to node.tls?.serverName,
            "alpn" to node.tls?.alpn?.joinToString(",")?.ifBlank { null },
        )
        return "tuic://${encode(p.uuid)}:${encode(p.password)}@${node.hostPort()}$query" +
            "#${node.encodedName()}"
    }

    private fun exportAnyTls(node: ServerProfile, p: ProtocolParams.AnyTls): String {
        val query = buildQuery(
            "sni" to node.tls?.serverName,
            "insecure" to if (node.tls?.insecure == true) "1" else null,
        )
        return "anytls://${encode(p.password)}@${node.hostPort()}$query#${node.encodedName()}"
    }

    private fun exportSocks(node: ServerProfile, p: ProtocolParams.Socks): String {
        val credentials = credentials(p.username, p.password)
        return "socks5://$credentials${node.hostPort()}#${node.encodedName()}"
    }

    private fun exportHttp(node: ServerProfile, p: ProtocolParams.Http): String {
        val scheme = if (node.tls?.enabled == true) "https" else "http"
        val credentials = credentials(p.username, p.password)
        return "$scheme://$credentials${node.hostPort()}#${node.encodedName()}"
    }

    // ------------------------------------------------------------ 辅助

    private fun credentials(user: String?, password: String?): String =
        if (user.isNullOrBlank()) "" else "${encode(user)}:${encode(password.orEmpty())}@"

    /** IPv6 字面量在 URI 中必须用方括号包裹。 */
    private fun ServerProfile.hostPort(): String =
        if (server.contains(':')) "[$server]:$serverPort" else "$server:$serverPort"

    private fun ServerProfile.encodedName(): String = encode(name)

    private fun ServerProfile.tlsQueryPairs(): Array<Pair<String, String?>> {
        val tls = this.tls?.takeIf { it.enabled } ?: return arrayOf("security" to "none")
        val reality = tls.reality
        return arrayOf(
            "security" to if (reality != null) "reality" else "tls",
            "sni" to tls.serverName,
            "fp" to tls.utls?.fingerprint,
            "alpn" to tls.alpn.joinToString(",").ifBlank { null },
            "allowInsecure" to if (tls.insecure) "1" else null,
            "pbk" to reality?.publicKey,
            "sid" to reality?.shortId?.ifBlank { null },
        )
    }

    private fun ServerProfile.transportQueryPairs(): Array<Pair<String, String?>> =
        when (val transport = this.transport) {
            null -> arrayOf("type" to "tcp")
            is TransportConfig.WebSocket -> arrayOf(
                "type" to "ws",
                "path" to transport.path,
                "host" to transport.headers["Host"],
            )
            is TransportConfig.Grpc -> arrayOf(
                "type" to "grpc",
                "serviceName" to transport.serviceName.ifBlank { null },
            )
            is TransportConfig.Http -> arrayOf(
                "type" to "http",
                "path" to transport.path,
                "host" to transport.host.joinToString(",").ifBlank { null },
            )
            is TransportConfig.HttpUpgrade -> arrayOf(
                "type" to "httpupgrade",
                "path" to transport.path,
                "host" to transport.host,
            )
            TransportConfig.Quic -> arrayOf("type" to "quic")
        }

    private fun buildQuery(vararg pairs: Pair<String, String?>): String {
        val parts = pairs.mapNotNull { (key, value) ->
            value?.takeIf { it.isNotBlank() }?.let { "$key=${encode(it)}" }
        }
        return if (parts.isEmpty()) "" else "?${parts.joinToString("&")}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
