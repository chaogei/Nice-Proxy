package com.niceproxy.core.config.share

import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RealityConfig
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import com.niceproxy.core.model.UtlsConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * 分享链接解析入口。
 *
 * 各家客户端导出的链接在细节上差异很大，这里的原则是**尽量解析成功**：
 * 缺省字段用协议默认值补齐，无法识别的字段直接忽略。导入失败对用户来说
 * 是最糟糕的体验 —— 他们通常拿不到链接的「正确」版本。
 */
object ShareLinkParsers {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 单条链接。无法识别时返回失败并带上原因。 */
    fun parse(link: String, groupId: String = ""): Result<ServerProfile> {
        val trimmed = UriSupport.sanitize(link)
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("链接为空"))

        val scheme = trimmed.substringBefore("://", "").lowercase()
        return runCatching {
            when (scheme) {
                "ss" -> parseShadowsocks(trimmed, groupId)
                "vmess" -> parseVMess(trimmed, groupId)
                "vless" -> parseVLess(trimmed, groupId)
                "trojan" -> parseTrojan(trimmed, groupId)
                "hysteria2", "hy2" -> parseHysteria2(trimmed, groupId)
                "tuic" -> parseTuic(trimmed, groupId)
                "anytls" -> parseAnyTls(trimmed, groupId)
                "socks", "socks5", "socks4", "socks4a" -> parseSocks(trimmed, groupId)
                "http", "https" -> parseHttp(trimmed, groupId)
                else -> throw IllegalArgumentException("不支持的协议：$scheme")
            }
        }
    }

    /**
     * 批量解析：按行拆分，跳过空行、注释和无法识别的行。
     *
     * 返回成功解析的节点与失败明细，UI 可以「导入 8 个，3 个失败」这样呈现，
     * 而不是整批失败让用户无从下手。
     */
    fun parseMany(text: String, groupId: String = ""): BatchResult {
        val nodes = mutableListOf<ServerProfile>()
        val failures = mutableListOf<String>()
        text.lineSequence()
            .map { UriSupport.sanitize(it) }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                parse(line, groupId).fold(
                    onSuccess = { nodes += it },
                    onFailure = { failures += line.take(60) },
                )
            }
        return BatchResult(nodes, failures)
    }

    data class BatchResult(
        val nodes: List<ServerProfile>,
        val failedLines: List<String>,
    )

    // ------------------------------------------------------------ Shadowsocks

    /**
     * 支持三种写法：
     * - SIP002 编码：`ss://base64url(method:password)@host:port?plugin=...#name`
     * - SIP002 明文：`ss://method:password@host:port#name`
     * - 传统整体编码：`ss://base64(method:password@host:port)#name`
     */
    private fun parseShadowsocks(link: String, groupId: String): ServerProfile {
        val body = link.removePrefix("ss://")
        val fragment = UriSupport.decode(body.substringAfter('#', ""))
        val withoutFragment = body.substringBefore('#')

        // 传统整体编码没有 '@'（编码后不含），先尝试整体解码
        if (!withoutFragment.contains('@')) {
            val decoded = UriSupport.decodeBase64(withoutFragment.substringBefore('?'))
                ?: throw IllegalArgumentException("ss 链接 Base64 解码失败")
            val parsed = UriSupport.parse("ss://$decoded")
                ?: throw IllegalArgumentException("ss 链接结构无法识别")
            val method = parsed.userInfo.substringBefore(':')
            val password = parsed.userInfo.substringAfter(':', "")
            return shadowsocksProfile(parsed, method, password, fragment, groupId)
        }

        val parsed = UriSupport.parse("ss://$withoutFragment")
            ?: throw IllegalArgumentException("ss 链接结构无法识别")
        // userinfo 可能是 base64url(method:password)，也可能已经是明文。
        // 解码结果必须含冒号才算数 —— 否则说明这段本来就不是 Base64，
        // 硬解会得到乱码加密方式，导入一个「看着正常但连不上」的节点。
        val userInfo = if (parsed.userInfo.contains(':')) {
            parsed.userInfo
        } else {
            UriSupport.decodeBase64(parsed.userInfo)?.takeIf { it.contains(':') }
                ?: throw IllegalArgumentException("ss 用户信息解码失败")
        }
        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':', "")
        return shadowsocksProfile(parsed, method, password, fragment, groupId)
    }

    private fun shadowsocksProfile(
        uri: ParsedUri,
        method: String,
        password: String,
        fragment: String,
        groupId: String,
    ): ServerProfile {
        require(method.isNotBlank()) { "ss 缺少加密方式" }
        val plugin = uri.q("plugin")
        return newProfile(
            groupId = groupId,
            name = fragment.ifBlank { uri.displayName() },
            protocol = ProxyProtocol.SHADOWSOCKS,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Shadowsocks(
                method = method,
                password = password,
                // plugin 字段形如 "obfs-local;obfs=http;obfs-host=x"，
                // 分号前是插件名，其余是参数
                plugin = plugin?.substringBefore(';')?.takeIf { it.isNotBlank() },
                pluginOpts = plugin?.substringAfter(';', "")?.takeIf { it.isNotBlank() },
            ),
        )
    }

    // ------------------------------------------------------------ VMess

    private fun parseVMess(link: String, groupId: String): ServerProfile {
        val body = link.removePrefix("vmess://")
        // 有的订阅在 Base64 之后又缀了 #备注，连着一起解码必然失败
        val payload = body.substringBefore('#').substringBefore('?')
        val decoded = UriSupport.decodeBase64(payload)

        if (decoded != null && decoded.trimStart().startsWith("{")) {
            return vmessFromJson(decoded, UriSupport.decode(body.substringAfter('#', "")), groupId)
        }
        return vmessFromUri(link, decoded, groupId)
    }

    /** v2rayN 格式：`vmess://` 后接一段 Base64 编码的 JSON，最常见的一种。 */
    private fun vmessFromJson(payload: String, fragment: String, groupId: String): ServerProfile {
        val obj = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("vmess 内容不是 JSON 对象")

        // 各家面板对字段类型没有共识：port / aid 有时是数字有时是字符串，
        // alpn 有时是逗号串有时是数组。直接取 jsonPrimitive 碰到数组会抛异常，
        // 整个节点就此丢失，所以一律「取不到就当没写」。
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

        val host = str("add") ?: throw IllegalArgumentException("vmess 缺少服务器地址")
        val port = str("port")?.toIntOrNull() ?: throw IllegalArgumentException("vmess 缺少端口")
        val uuid = str("id") ?: throw IllegalArgumentException("vmess 缺少 UUID")

        val network = str("net") ?: "tcp"
        // v2rayN 写 "tls":"tls"，也有面板直接写布尔 true
        val tlsMode = str("tls").orEmpty().lowercase()
        val tlsEnabled = tlsMode in TLS_MODES
        val wsHost = str("host")
        val path = str("path") ?: "/"

        return newProfile(
            groupId = groupId,
            name = str("ps") ?: fragment.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.VMESS,
            server = host,
            port = port,
            params = ProtocolParams.VMess(
                uuid = uuid,
                security = str("scy") ?: "auto",
                alterId = str("aid")?.toIntOrNull() ?: 0,
            ),
            transport = buildTransport(
                type = network,
                path = path,
                host = wsHost,
                serviceName = str("path", "serviceName"),
            ),
            tls = if (tlsEnabled) {
                TlsConfig(
                    enabled = true,
                    serverName = str("sni") ?: wsHost,
                    alpn = obj.stringList("alpn"),
                    utls = str("fp")?.let { UtlsConfig(fingerprint = it) },
                )
            } else {
                null
            },
        )
    }

    /**
     * Base64 里不是 JSON 时的两种在野形态：
     * - Shadowrocket：`vmess://Base64(加密方式:uuid@host:port)?remarks=…&obfs=websocket`
     * - VMessAEAD 提案（NekoBox、v2rayN 新版）：`vmess://uuid@host:port?type=ws&security=tls#名字`
     *
     * iOS 用户分享给 Android 用户的多半是前者，两者拆出来的结构一致，
     * 区别只在 userinfo 是藏在 Base64 里还是写成明文。
     */
    private fun vmessFromUri(link: String, decodedUserPart: String?, groupId: String): ServerProfile {
        val rebuilt = if (decodedUserPart != null && decodedUserPart.contains('@')) {
            val query = link.substringBefore('#').substringAfter('?', "")
            val fragment = link.substringAfter('#', "")
            buildString {
                append("vmess://").append(decodedUserPart)
                if (query.isNotEmpty()) append('?').append(query)
                if (fragment.isNotEmpty()) append('#').append(fragment)
            }
        } else {
            link
        }

        val uri = UriSupport.parse(rebuilt)
            ?: throw IllegalArgumentException("vmess 链接结构无法识别")
        val userInfo = UriSupport.decode(uri.userInfo)
        val uuid = userInfo.substringAfterLast(':')
        require(uuid.isNotBlank()) { "vmess 缺少 UUID" }

        val security = uri.q("security")?.lowercase()
        val tlsEnabled = uri.qBool("tls") || (security != null && security in TLS_SECURITIES)
        val host = uri.q("host", "obfsparam")

        return newProfile(
            groupId = groupId,
            name = uri.q("remarks") ?: uri.displayName(),
            protocol = ProxyProtocol.VMESS,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.VMess(
                uuid = uuid,
                security = if (userInfo.contains(':')) userInfo.substringBeforeLast(':') else "auto",
                alterId = uri.qInt("alterid", "aid") ?: 0,
            ),
            // Shadowrocket 把传输层写在 obfs 里：websocket / http / none
            transport = buildTransport(
                type = uri.q("type", "network", "obfs") ?: "tcp",
                path = uri.q("path"),
                host = host,
                serviceName = uri.q("servicename", "service-name"),
            ),
            tls = if (tlsEnabled) {
                TlsConfig(
                    enabled = true,
                    serverName = uri.q("sni", "peer") ?: host,
                    insecure = uri.qBool("allowinsecure", "insecure", "skip-cert-verify"),
                    alpn = uri.q("alpn").splitCsv(),
                    utls = uri.q("fp", "fingerprint")?.let { UtlsConfig(fingerprint = it) },
                )
            } else {
                null
            },
        )
    }

    // ------------------------------------------------------------ VLESS

    private fun parseVLess(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("vless 链接结构无法识别")
        require(uri.userInfo.isNotBlank()) { "vless 缺少 UUID" }
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.VLESS,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.VLess(
                uuid = UriSupport.decode(uri.userInfo),
                flow = uri.q("flow"),
                packetEncoding = uri.q("packetencoding"),
            ),
            transport = uri.buildTransportFromQuery(),
            tls = uri.buildTlsFromQuery(),
        )
    }

    // ------------------------------------------------------------ Trojan

    private fun parseTrojan(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("trojan 链接结构无法识别")
        require(uri.userInfo.isNotBlank()) { "trojan 缺少密码" }
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.TROJAN,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Trojan(password = UriSupport.decode(uri.userInfo)),
            transport = uri.buildTransportFromQuery(),
            // Trojan 天然基于 TLS，链接里不写 security 也要启用
            tls = uri.buildTlsFromQuery(forceEnabled = true),
        )
    }

    // ------------------------------------------------------------ Hysteria2

    private fun parseHysteria2(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("hysteria2 链接结构无法识别")
        val password = UriSupport.decode(uri.userInfo).ifBlank {
            uri.q("auth", "password") ?: throw IllegalArgumentException("hysteria2 缺少密码")
        }
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.HYSTERIA2,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Hysteria2(
                password = password,
                upMbps = uri.qInt("up", "upmbps"),
                downMbps = uri.qInt("down", "downmbps"),
                obfsType = uri.q("obfs"),
                obfsPassword = uri.q("obfs-password", "obfs_password"),
                // 端口跳跃在链接里写作 mport=20000-30000，sing-box 用冒号
                serverPorts = uri.q("mport", "ports").splitCsv().map { it.replace('-', ':') },
            ),
            tls = TlsConfig(
                enabled = true,
                serverName = uri.q("sni", "peer"),
                insecure = uri.qBool("insecure", "allowinsecure", "skip-cert-verify"),
                alpn = uri.q("alpn").splitCsv(),
            ),
        )
    }

    // ------------------------------------------------------------ TUIC

    /** `tuic://uuid:password@host:port?...` */
    private fun parseTuic(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("tuic 链接结构无法识别")
        val uuid = UriSupport.decode(uri.userInfo.substringBefore(':'))
        val password = UriSupport.decode(uri.userInfo.substringAfter(':', ""))
        require(uuid.isNotBlank()) { "tuic 缺少 UUID" }
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.TUIC,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Tuic(
                uuid = uuid,
                password = password,
                congestionControl = uri.q("congestion_control", "congestion-control") ?: "cubic",
                udpRelayMode = uri.q("udp_relay_mode", "udp-relay-mode") ?: "native",
                zeroRttHandshake = uri.qBool("zero_rtt_handshake", "reduce_rtt"),
            ),
            tls = TlsConfig(
                enabled = true,
                serverName = uri.q("sni", "peer"),
                insecure = uri.qBool("insecure", "allowinsecure", "allow_insecure"),
                alpn = uri.q("alpn").splitCsv(),
            ),
        )
    }

    // ------------------------------------------------------------ AnyTLS

    private fun parseAnyTls(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("anytls 链接结构无法识别")
        require(uri.userInfo.isNotBlank()) { "anytls 缺少密码" }
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.ANYTLS,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.AnyTls(password = UriSupport.decode(uri.userInfo)),
            tls = TlsConfig(
                enabled = true,
                serverName = uri.q("sni", "peer"),
                insecure = uri.qBool("insecure", "allowinsecure"),
            ),
        )
    }

    // ------------------------------------------------------------ SOCKS / HTTP

    private fun parseSocks(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("socks 链接结构无法识别")
        val version = when (uri.scheme) {
            "socks4" -> "4"
            "socks4a" -> "4a"
            else -> "5"
        }
        val (user, pass) = splitCredentials(uri.userInfo)
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.SOCKS,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Socks(version = version, username = user, password = pass),
        )
    }

    private fun parseHttp(link: String, groupId: String): ServerProfile {
        val uri = UriSupport.parse(link) ?: throw IllegalArgumentException("http 链接结构无法识别")
        val (user, pass) = splitCredentials(uri.userInfo)
        return newProfile(
            groupId = groupId,
            name = uri.displayName(),
            protocol = ProxyProtocol.HTTP,
            server = uri.host,
            port = uri.port,
            params = ProtocolParams.Http(username = user, password = pass),
            tls = if (uri.scheme == "https") TlsConfig(enabled = true, serverName = uri.host) else null,
        )
    }

    private fun splitCredentials(userInfo: String): Pair<String?, String?> {
        if (userInfo.isBlank()) return null to null
        // 部分客户端把 user:pass 整体 Base64 了。只有解出来确实是一对凭据才采信 ——
        // 否则 `http://admin1@host:8080` 这种只有用户名的链接会被当成 Base64 解成乱码。
        val decoded = if (userInfo.contains(':')) {
            userInfo
        } else {
            UriSupport.decodeBase64(userInfo)?.takeIf { it.contains(':') } ?: userInfo
        }
        val user = UriSupport.decode(decoded.substringBefore(':')).takeIf { it.isNotBlank() }
        val pass = UriSupport.decode(decoded.substringAfter(':', "")).takeIf { it.isNotBlank() }
        return user to pass
    }

    // ------------------------------------------------------------ 共用构造

    private fun newProfile(
        groupId: String,
        name: String,
        protocol: ProxyProtocol,
        server: String,
        port: Int,
        params: ProtocolParams,
        transport: TransportConfig? = null,
        tls: TlsConfig? = null,
    ): ServerProfile {
        require(port in 1..65535) { "端口 $port 无效" }
        val now = System.currentTimeMillis()
        return ServerProfile(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            name = name,
            protocol = protocol,
            server = server,
            serverPort = port,
            params = params,
            transport = transport,
            tls = tls,
            createdAt = now,
            updatedAt = now,
        )
    }

    private val TLS_MODES = setOf("tls", "reality", "xtls", "true", "1")
    private val TLS_SECURITIES = setOf("tls", "reality", "xtls")
}

/** 逗号分隔的多值字段（alpn、mport 等）。 */
private fun String?.splitCsv(): List<String> =
    this?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()

/** JSON 里同一个字段可能写成数组也可能写成逗号串，两种都要认。 */
private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }
    is JsonPrimitive -> value.contentOrNull.splitCsv()
    else -> emptyList()
}

/** VLESS / Trojan 共用的 TLS 参数解析。 */
private fun ParsedUri.buildTlsFromQuery(forceEnabled: Boolean = false): TlsConfig? {
    val security = q("security")?.lowercase()
    val realityKey = q("pbk", "public-key", "publickey")
    val enabled = forceEnabled ||
        security == "tls" ||
        security == "reality" ||
        security == "xtls" ||
        realityKey != null
    if (!enabled) return null

    return TlsConfig(
        enabled = true,
        serverName = q("sni", "peer", "servername") ?: q("host"),
        insecure = qBool("allowinsecure", "insecure", "skip-cert-verify"),
        alpn = q("alpn").splitCsv(),
        utls = q("fp", "fingerprint")?.let { UtlsConfig(fingerprint = it) },
        reality = realityKey?.let {
            RealityConfig(publicKey = it, shortId = q("sid", "short-id", "shortid").orEmpty())
        },
    )
}

/** VLESS / Trojan 共用的传输层解析。 */
private fun ParsedUri.buildTransportFromQuery(): TransportConfig? = buildTransport(
    type = q("type", "network") ?: "tcp",
    path = q("path"),
    host = q("host"),
    serviceName = q("servicename", "service-name"),
)

private fun buildTransport(
    type: String,
    path: String?,
    host: String?,
    serviceName: String?,
): TransportConfig? = when (type.lowercase()) {
    // tcp 是默认传输层，sing-box 不接受显式的 tcp transport 对象
    "", "tcp", "none", "raw" -> null
    "ws", "websocket" -> TransportConfig.WebSocket(
        path = path?.ifBlank { "/" } ?: "/",
        headers = host?.takeIf { it.isNotBlank() }?.let { mapOf("Host" to it) } ?: emptyMap(),
    )
    "grpc" -> TransportConfig.Grpc(serviceName = serviceName ?: path?.trim('/') ?: "")
    "http", "h2" -> TransportConfig.Http(
        host = host?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList(),
        path = path?.ifBlank { "/" } ?: "/",
    )
    "httpupgrade" -> TransportConfig.HttpUpgrade(
        host = host,
        path = path?.ifBlank { "/" } ?: "/",
    )
    "quic" -> TransportConfig.Quic
    else -> null
}
