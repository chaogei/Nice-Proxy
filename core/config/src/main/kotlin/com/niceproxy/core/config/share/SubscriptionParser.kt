package com.niceproxy.core.config.share

import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RealityConfig
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.SubscriptionTraffic
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import com.niceproxy.core.model.UtlsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import java.util.UUID

enum class SubscriptionFormat {
    /** Base64 编码的分享链接列表，机场最常用。 */
    BASE64_LINKS,

    /** 每行一条分享链接的明文。 */
    PLAIN_LINKS,

    /** Clash / mihomo 的 YAML 配置，取其 `proxies` 段。 */
    CLASH_YAML,

    /** sing-box 配置，取其 `outbounds` 段。 */
    SING_BOX_JSON,

    /** SIP008：Shadowsocks 官方定义的 JSON 订阅格式。 */
    SIP008,
}

data class SubscriptionResult(
    val format: SubscriptionFormat,
    val nodes: List<ServerProfile>,
    val failedEntries: List<String> = emptyList(),
)

object SubscriptionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 探测结果连同「真正该拿去解析的正文」一起返回，避免 Base64 被解码两遍。 */
    private data class Detected(val format: SubscriptionFormat, val content: String)

    fun detect(content: String): SubscriptionFormat? = detectInternal(content)?.format

    /**
     * 格式探测。
     *
     * 顺序有讲究：先判断结构化格式（JSON / YAML）的特征键，最后才落到
     * Base64。因为 Base64 的判定条件最宽松，放前面会把别的格式误吞掉。
     */
    private fun detectInternal(content: String, allowBase64: Boolean = true): Detected? {
        // 订阅正文常常带 BOM（服务端直接吐 Windows 上生成的文件），
        // 带着 BOM 连 `{` 开头都判断不出来，整份订阅会被识别成链接列表
        val trimmed = UriSupport.sanitize(content)
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            return when {
                obj == null -> null
                obj.containsKey("outbounds") -> Detected(SubscriptionFormat.SING_BOX_JSON, trimmed)
                obj.containsKey("servers") -> Detected(SubscriptionFormat.SIP008, trimmed)
                else -> null
            }
        }

        if (PROXIES_KEY.containsMatchIn(trimmed)) {
            return Detected(SubscriptionFormat.CLASH_YAML, trimmed)
        }

        if (trimmed.lineSequence().any { it.contains("://") }) {
            return Detected(SubscriptionFormat.PLAIN_LINKS, trimmed)
        }

        if (allowBase64 && UriSupport.looksLikeBase64(trimmed)) {
            val decoded = UriSupport.decodeBase64(trimmed) ?: return null
            // 只往下探一层。有的机场连 YAML / JSON 都整体 Base64 了，
            // 一律按链接列表解析的话，用户看到的是一个「0 个节点」的空订阅，
            // 完全无从判断是自己填错了地址还是订阅本身有问题。
            val inner = detectInternal(decoded, allowBase64 = false) ?: return null
            return if (inner.format == SubscriptionFormat.PLAIN_LINKS) {
                Detected(SubscriptionFormat.BASE64_LINKS, inner.content)
            } else {
                inner
            }
        }

        return null
    }

    fun parse(content: String, groupId: String = ""): Result<SubscriptionResult> {
        val detected = detectInternal(content)
            ?: return Result.failure(IllegalArgumentException("无法识别的订阅格式"))
        return runCatching {
            when (detected.format) {
                SubscriptionFormat.BASE64_LINKS, SubscriptionFormat.PLAIN_LINKS ->
                    ShareLinkParsers.parseMany(detected.content, groupId)
                        .let { SubscriptionResult(detected.format, it.nodes, it.failedLines) }
                SubscriptionFormat.CLASH_YAML -> parseClash(detected.content, groupId)
                SubscriptionFormat.SING_BOX_JSON -> parseSingBox(detected.content, groupId)
                SubscriptionFormat.SIP008 -> parseSip008(detected.content, groupId)
            }
        }
    }

    /**
     * 解析订阅响应头里的流量信息：
     * `subscription-userinfo: upload=1234; download=5678; total=107374182400; expire=1735689600`
     */
    fun parseUserInfo(header: String?): SubscriptionTraffic? {
        if (header.isNullOrBlank()) return null
        val fields = header.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "").trim().toByteCount()
                if (key.isEmpty() || value == null) null else key to value
            }
            .toMap()
        if (fields.isEmpty()) return null
        return SubscriptionTraffic(
            uploadBytes = fields["upload"] ?: 0,
            downloadBytes = fields["download"] ?: 0,
            totalBytes = fields["total"] ?: 0,
            expireAtSeconds = fields["expire"] ?: 0,
        )
    }

    // ------------------------------------------------------------ Clash YAML

    /**
     * 订阅正文是第三方给的，必须按不可信输入对待。
     *
     * [SafeConstructor] 关掉 `!!java.*` 这类全局标签的对象构造；
     * 同时把 SnakeYAML 默认 3 MB 的 `codePointLimit` 放宽 —— 上千节点的
     * 机场 YAML 很容易越过默认值，越过之后是整份订阅直接失败。
     */
    private fun newYaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            codePointLimit = YAML_CODE_POINT_LIMIT
            // 别名炸弹的防护（maxAliasesForCollections）保持默认值，不放宽
            isAllowDuplicateKeys = true
        }
        val dumperOptions = DumperOptions()
        // 用最长的那个构造函数：短构造函数是否把 LoaderOptions 透传下去，
        // 各个 SnakeYAML 版本的实现并不一致，显式传才能保证限额真的生效
        return Yaml(
            SafeConstructor(loaderOptions),
            Representer(dumperOptions),
            dumperOptions,
            loaderOptions,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseClash(content: String, groupId: String): SubscriptionResult {
        val root = newYaml().load<Any>(content) as? Map<String, Any?>
            ?: throw IllegalArgumentException("YAML 解析失败")
        val proxies = root["proxies"] as? List<Any?>
            ?: throw IllegalArgumentException("YAML 中没有 proxies 段")

        val nodes = mutableListOf<ServerProfile>()
        val failures = mutableListOf<String>()
        proxies.forEach { raw ->
            val proxy = raw as? Map<String, Any?>
            if (proxy == null) {
                failures += "未命名节点"
                return@forEach
            }
            runCatching { clashProxyToProfile(proxy, groupId) }
                .fold(
                    onSuccess = { nodes += it },
                    onFailure = { failures += (proxy["name"]?.toString() ?: "未命名节点") },
                )
        }
        return SubscriptionResult(SubscriptionFormat.CLASH_YAML, nodes, failures)
    }

    @Suppress("UNCHECKED_CAST")
    private fun clashProxyToProfile(proxy: Map<String, Any?>, groupId: String): ServerProfile {
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { proxy[it]?.toString()?.trim()?.takeIf { v -> v.isNotEmpty() } }

        fun bool(vararg keys: String): Boolean =
            keys.any { proxy[it]?.toString()?.lowercase() in TRUTHY }

        val type = str("type")?.lowercase() ?: throw IllegalArgumentException("缺少 type")
        val server = str("server") ?: throw IllegalArgumentException("缺少 server")
        // port 写成带引号的字符串在机场模板里非常普遍，也见过带尾随空格的
        val port = str("port")?.toIntOrNull() ?: throw IllegalArgumentException("缺少 port")
        val name = str("name") ?: "$server:$port"

        val network = str("network") ?: "tcp"
        val wsOpts = proxy["ws-opts"] as? Map<String, Any?>
        val h2Opts = proxy["h2-opts"] as? Map<String, Any?>
        val grpcOpts = proxy["grpc-opts"] as? Map<String, Any?>
        val realityOpts = proxy["reality-opts"] as? Map<String, Any?>

        val transport = when (network.lowercase()) {
            "ws" -> TransportConfig.WebSocket(
                // 老版本 Clash 与大量机场模板用扁平的 ws-path / ws-headers。
                // 只认 ws-opts 会把路径丢掉，得到一个握手必然失败的节点。
                path = (wsOpts?.get("path") ?: proxy["ws-path"])?.toString()?.ifBlank { "/" } ?: "/",
                headers = headerMap(wsOpts?.get("headers") ?: proxy["ws-headers"]),
            )
            "grpc" -> TransportConfig.Grpc(
                serviceName = grpcOpts?.get("grpc-service-name")?.toString() ?: "",
            )
            "http", "h2" -> TransportConfig.Http(
                host = anyToStringList(h2Opts?.get("host")),
                path = (h2Opts?.get("path") ?: proxy["path"])?.toString()?.ifBlank { "/" } ?: "/",
            )
            "httpupgrade" -> TransportConfig.HttpUpgrade(
                path = (proxy["http-upgrade-opts"] as? Map<String, Any?>)
                    ?.get("path")?.toString()?.ifBlank { "/" } ?: "/",
            )
            else -> null
        }

        val tlsEnabled = bool("tls") || type in ALWAYS_TLS_TYPES
        val tls = if (tlsEnabled) {
            TlsConfig(
                enabled = true,
                serverName = str("sni", "servername", "peer"),
                insecure = bool("skip-cert-verify"),
                // alpn 按 Clash 的 schema 是列表，但不少机场写成单个字符串
                alpn = anyToStringList(proxy["alpn"]),
                utls = str("client-fingerprint")?.let { UtlsConfig(fingerprint = it) },
                reality = realityOpts?.get("public-key")?.toString()?.let {
                    RealityConfig(
                        publicKey = it,
                        shortId = realityOpts["short-id"]?.toString().orEmpty(),
                    )
                },
            )
        } else {
            null
        }

        val (protocol, params) = when (type) {
            "ss", "shadowsocks" -> ProxyProtocol.SHADOWSOCKS to ProtocolParams.Shadowsocks(
                method = str("cipher", "method") ?: throw IllegalArgumentException("缺少 cipher"),
                password = str("password").orEmpty(),
            )
            "vmess" -> ProxyProtocol.VMESS to ProtocolParams.VMess(
                uuid = str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                security = str("cipher") ?: "auto",
                alterId = str("alterId", "alterid")?.toIntOrNull() ?: 0,
            )
            "vless" -> ProxyProtocol.VLESS to ProtocolParams.VLess(
                uuid = str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                flow = str("flow"),
            )
            "trojan" -> ProxyProtocol.TROJAN to ProtocolParams.Trojan(
                password = str("password") ?: throw IllegalArgumentException("缺少 password"),
            )
            "hysteria2", "hy2" -> ProxyProtocol.HYSTERIA2 to ProtocolParams.Hysteria2(
                password = str("password", "auth") ?: throw IllegalArgumentException("缺少 password"),
                upMbps = str("up")?.filter(Char::isDigit)?.toIntOrNull(),
                downMbps = str("down")?.filter(Char::isDigit)?.toIntOrNull(),
                obfsType = str("obfs"),
                obfsPassword = str("obfs-password"),
                // mihomo 的端口跳跃写作 ports: "20000-30000"，sing-box 用冒号
                serverPorts = str("ports")
                    ?.split(',')
                    ?.map { it.trim().replace('-', ':') }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
            "tuic" -> ProxyProtocol.TUIC to ProtocolParams.Tuic(
                uuid = str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                password = str("password").orEmpty(),
                congestionControl = str("congestion-controller") ?: "cubic",
                udpRelayMode = str("udp-relay-mode") ?: "native",
            )
            "anytls" -> ProxyProtocol.ANYTLS to ProtocolParams.AnyTls(
                password = str("password") ?: throw IllegalArgumentException("缺少 password"),
            )
            "socks5" -> ProxyProtocol.SOCKS to ProtocolParams.Socks(
                username = str("username"),
                password = str("password"),
            )
            "http" -> ProxyProtocol.HTTP to ProtocolParams.Http(
                username = str("username"),
                password = str("password"),
            )
            else -> throw IllegalArgumentException("不支持的 Clash 类型：$type")
        }

        val now = System.currentTimeMillis()
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            name = name,
            protocol = protocol,
            server = server,
            serverPort = port,
            params = params,
            transport = transport.takeIf { protocol.supportsTransport },
            tls = tls,
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------ sing-box JSON

    private fun parseSingBox(content: String, groupId: String): SubscriptionResult {
        val root = json.parseToJsonElement(content) as? JsonObject
            ?: throw IllegalArgumentException("JSON 根节点不是对象")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("JSON 中没有 outbounds 段")

        val nodes = mutableListOf<ServerProfile>()
        val failures = mutableListOf<String>()
        outbounds.forEach { element ->
            // outbounds 里混进字符串或 null 不该让整份订阅失败
            val outbound = element as? JsonObject ?: return@forEach
            val type = outbound.str("type")
            // selector / urltest / direct / block 不是节点，静默跳过
            if (type == null || type in NON_NODE_TYPES) return@forEach
            runCatching { singBoxOutboundToProfile(outbound, groupId) }
                .fold(
                    onSuccess = { nodes += it },
                    onFailure = { failures += (outbound.str("tag") ?: type) },
                )
        }
        return SubscriptionResult(SubscriptionFormat.SING_BOX_JSON, nodes, failures)
    }

    private val NON_NODE_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

    private fun singBoxOutboundToProfile(obj: JsonObject, groupId: String): ServerProfile {
        val type = obj.str("type") ?: throw IllegalArgumentException("缺少 type")
        val protocol = ProxyProtocol.fromSingBoxType(type)
            ?: throw IllegalArgumentException("不支持的类型：$type")
        val server = obj.str("server") ?: throw IllegalArgumentException("缺少 server")
        val port = obj.int("server_port") ?: throw IllegalArgumentException("缺少 server_port")

        val params: ProtocolParams = when (protocol) {
            ProxyProtocol.SHADOWSOCKS -> ProtocolParams.Shadowsocks(
                method = obj.str("method") ?: throw IllegalArgumentException("缺少 method"),
                password = obj.str("password").orEmpty(),
            )
            ProxyProtocol.VMESS -> ProtocolParams.VMess(
                uuid = obj.str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                security = obj.str("security") ?: "auto",
                alterId = obj.int("alter_id") ?: 0,
            )
            ProxyProtocol.VLESS -> ProtocolParams.VLess(
                uuid = obj.str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                flow = obj.str("flow"),
            )
            ProxyProtocol.TROJAN -> ProtocolParams.Trojan(
                password = obj.str("password") ?: throw IllegalArgumentException("缺少 password"),
            )
            ProxyProtocol.HYSTERIA2 -> ProtocolParams.Hysteria2(
                password = obj.str("password") ?: throw IllegalArgumentException("缺少 password"),
                upMbps = obj.int("up_mbps"),
                downMbps = obj.int("down_mbps"),
                obfsType = obj.objectAt("obfs")?.str("type"),
                obfsPassword = obj.objectAt("obfs")?.str("password"),
            )
            ProxyProtocol.TUIC -> ProtocolParams.Tuic(
                uuid = obj.str("uuid") ?: throw IllegalArgumentException("缺少 uuid"),
                password = obj.str("password").orEmpty(),
                congestionControl = obj.str("congestion_control") ?: "cubic",
                udpRelayMode = obj.str("udp_relay_mode") ?: "native",
            )
            ProxyProtocol.ANYTLS -> ProtocolParams.AnyTls(
                password = obj.str("password") ?: throw IllegalArgumentException("缺少 password"),
            )
            ProxyProtocol.SOCKS -> ProtocolParams.Socks(
                version = obj.str("version") ?: "5",
                username = obj.str("username"),
                password = obj.str("password"),
            )
            ProxyProtocol.HTTP -> ProtocolParams.Http(
                username = obj.str("username"),
                password = obj.str("password"),
            )
            else -> throw IllegalArgumentException("暂不支持导入 $type")
        }

        val tlsObj = obj.objectAt("tls")
        val tls = if (tlsObj?.bool("enabled") == true) {
            val realityObj = tlsObj.objectAt("reality")
            TlsConfig(
                enabled = true,
                serverName = tlsObj.str("server_name"),
                insecure = tlsObj.bool("insecure") ?: false,
                alpn = tlsObj.stringListAt("alpn"),
                utls = tlsObj.objectAt("utls")?.str("fingerprint")?.let { UtlsConfig(fingerprint = it) },
                reality = realityObj?.str("public_key")?.let {
                    RealityConfig(publicKey = it, shortId = realityObj.str("short_id").orEmpty())
                },
            )
        } else {
            null
        }

        val transport = obj.objectAt("transport")?.let { t ->
            when (t.str("type")) {
                "ws" -> TransportConfig.WebSocket(path = t.str("path") ?: "/")
                "grpc" -> TransportConfig.Grpc(serviceName = t.str("service_name") ?: "")
                "httpupgrade" -> TransportConfig.HttpUpgrade(path = t.str("path") ?: "/")
                "http" -> TransportConfig.Http(path = t.str("path") ?: "/")
                else -> null
            }
        }

        val now = System.currentTimeMillis()
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            name = obj.str("tag") ?: "$server:$port",
            protocol = protocol,
            server = server,
            serverPort = port,
            params = params,
            // QUIC 系协议自带传输层，硬塞一个 transport 会让这个出站被内核判为非法
            transport = transport.takeIf { protocol.supportsTransport },
            tls = tls,
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------ SIP008

    private fun parseSip008(content: String, groupId: String): SubscriptionResult {
        val root = json.parseToJsonElement(content) as? JsonObject
            ?: throw IllegalArgumentException("JSON 根节点不是对象")
        val servers = root["servers"] as? JsonArray
            ?: throw IllegalArgumentException("JSON 中没有 servers 段")

        val nodes = mutableListOf<ServerProfile>()
        val failures = mutableListOf<String>()
        servers.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            runCatching {
                val server = entry.str("server") ?: throw IllegalArgumentException("缺少 server")
                val port = entry.int("server_port")
                    ?: throw IllegalArgumentException("缺少 server_port")
                val now = System.currentTimeMillis()
                ServerProfile(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    name = entry.str("remarks") ?: "$server:$port",
                    protocol = ProxyProtocol.SHADOWSOCKS,
                    server = server,
                    serverPort = port,
                    params = ProtocolParams.Shadowsocks(
                        method = entry.str("method") ?: throw IllegalArgumentException("缺少 method"),
                        password = entry.str("password").orEmpty(),
                        plugin = entry.str("plugin"),
                        pluginOpts = entry.str("plugin_opts"),
                    ),
                    createdAt = now,
                    updatedAt = now,
                )
            }.fold(
                onSuccess = { nodes += it },
                onFailure = { failures += (entry.str("remarks") ?: "未命名节点") },
            )
        }
        return SubscriptionResult(SubscriptionFormat.SIP008, nodes, failures)
    }

    private val PROXIES_KEY = Regex("^proxies\\s*:", RegexOption.MULTILINE)

    private val TRUTHY = setOf("true", "1", "yes", "on")

    private val ALWAYS_TLS_TYPES = setOf("trojan", "hysteria2", "hy2", "tuic", "anytls")

    /** SnakeYAML 默认只允许 3 MB，放宽到能容下上千节点的机场配置。 */
    private const val YAML_CODE_POINT_LIMIT = 64 * 1024 * 1024
}

/**
 * JSON 字段的读取一律走这几个宽容取值器。
 *
 * `element.jsonPrimitive` 在字段类型对不上（数组、对象）时是**抛异常**，
 * 而机场导出的 JSON 里字段类型对不上是常态，抛出去就是整份订阅导入失败。
 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? = when (str(key)?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.stringListAt(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }
    is JsonPrimitive -> value.contentOrNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
    else -> emptyList()
}

/** YAML 里同一个字段既可能是列表也可能是单个字符串。 */
private fun anyToStringList(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
    else -> value.toString().split(',').map(String::trim).filter(String::isNotEmpty)
}

private fun headerMap(value: Any?): Map<String, String> =
    (value as? Map<*, *>)
        ?.mapNotNull { (k, v) -> if (k == null || v == null) null else k.toString() to v.toString() }
        ?.toMap()
        ?: emptyMap()

/**
 * 流量数值的宽容解析。
 *
 * 部分面板（多为 Python 实现）把字节数输出成 `1.07374182e+11` 这样的浮点，
 * 严格按 Long 解析会整条流量信息读不出来，界面上就只剩「未知」。
 */
private fun String.toByteCount(): Long? = toLongOrNull() ?: toDoubleOrNull()?.toLong()
