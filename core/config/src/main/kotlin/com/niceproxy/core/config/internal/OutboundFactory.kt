package com.niceproxy.core.config.internal

import com.niceproxy.core.model.MultiplexConfig
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import com.niceproxy.core.model.WellKnownTag
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal sealed interface OutboundResult {
    data class Ok(
        val json: JsonObject,
        /** 这个出站真的写出了 `insecure: true`，调用方据此给出安全警告。 */
        val insecureTls: Boolean = false,
        /**
         * 这份 JSON 属于根对象的 `endpoints` 而不是 `outbounds`。
         *
         * 目前只有 WireGuard 走这条路，见 [ProxyProtocol.isEndpoint]。
         */
        val endpoint: Boolean = false,
    ) : OutboundResult

    data class Invalid(val reason: String) : OutboundResult
}

/**
 * 把 [ServerProfile] 翻译成 sing-box 的一个 outbound（或 endpoint）对象。
 *
 * 每个协议分支只写出该协议真正接受的字段 —— sing-box 用的是
 * `DisallowUnknownFields` 的解码器，多写一个字段不是被忽略，而是**整份**配置
 * 加载失败。同理，字段的**取值**也要先自己判一遍：`congestion_control: "bbr2"`、
 * `hop_interval: "30秒"` 这类东西界面上看不出问题，内核却会直接罢工。
 */
internal class OutboundFactory {

    fun create(node: ServerProfile): OutboundResult {
        mismatchedParams(node)?.let { return OutboundResult.Invalid(it) }
        validateCommon(node)?.let { return OutboundResult.Invalid(it) }

        return runCatching {
            if (node.protocol.isEndpoint) buildEndpoint(node) else buildOutbound(node)
        }.fold(
            onSuccess = { it },
            onFailure = { OutboundResult.Invalid(it.message ?: "未知错误") },
        )
    }

    private fun buildOutbound(node: ServerProfile): OutboundResult.Ok {
        val params = node.params
        val json = buildJsonObject {
            put("type", node.protocol.singBoxType)
            put("tag", node.outboundTag)
            if (node.protocol != ProxyProtocol.DIRECT) {
                put("server", node.server)
                put("server_port", node.serverPort)
            }
            when (params) {
                is ProtocolParams.Direct -> Unit
                is ProtocolParams.Http -> putHttp(params)
                is ProtocolParams.Socks -> putSocks(params)
                is ProtocolParams.Shadowsocks -> putShadowsocks(params)
                is ProtocolParams.VMess -> putVMess(params)
                is ProtocolParams.VLess -> putVLess(params, node)
                is ProtocolParams.Trojan -> putTrojan(params)
                is ProtocolParams.Hysteria -> putHysteria(params)
                is ProtocolParams.Hysteria2 -> putHysteria2(params)
                is ProtocolParams.Tuic -> putTuic(params)
                is ProtocolParams.AnyTls -> putAnyTls(params)
                is ProtocolParams.ShadowTls -> putShadowTls(params)
                is ProtocolParams.Ssh -> putSsh(params)
                // isEndpoint 的协议不会走到 buildOutbound
                is ProtocolParams.WireGuard -> error("WireGuard 必须写成 endpoint")
            }
            putTransport(node)
            putTls(node)
            putMultiplex(node)
            putIfNotBlank("detour", node.detour)
        }
        return OutboundResult.Ok(json, insecureTls = json.declaresInsecureTls())
    }

    // ---------- 通用校验 ----------

    /**
     * `protocol` 与 `params` 必须是同一个协议。
     *
     * 正常路径下这两者由同一个解析器一起产出，不可能对不上。会对不上是因为
     * 数据库的降级：`protocol` 列存的是枚举名，读不出来时 `NiceTypeConverters`
     * 会兜底成 `TROJAN`（选它是因为绝不能兜底成 DIRECT —— 那会生成一个可用的
     * 直连出站，用户以为在走代理而流量裸奔）。若此时 `params_json` 恰好完好，
     * 就会拼出 `type: "trojan"` 配着别的协议字段的 outbound。
     *
     * 那种 outbound 语法合法、语义残缺，sing-box 会拒绝**整份**配置 ——
     * 一条坏记录让所有节点一起失效、代理起不来。所以要在这里单独挡下来，
     * 让它退化成「这一个节点不可用」。
     *
     * 穷举 `when` 是刻意的：以后新增协议时编译器会强制更新这张表。
     */
    private fun mismatchedParams(node: ServerProfile): String? {
        val params = node.params
        val consistent = when (node.protocol) {
            ProxyProtocol.DIRECT -> params is ProtocolParams.Direct
            ProxyProtocol.HTTP -> params is ProtocolParams.Http
            ProxyProtocol.SOCKS -> params is ProtocolParams.Socks
            ProxyProtocol.SHADOWSOCKS -> params is ProtocolParams.Shadowsocks
            ProxyProtocol.VMESS -> params is ProtocolParams.VMess
            ProxyProtocol.VLESS -> params is ProtocolParams.VLess
            ProxyProtocol.TROJAN -> params is ProtocolParams.Trojan
            ProxyProtocol.HYSTERIA -> params is ProtocolParams.Hysteria
            ProxyProtocol.HYSTERIA2 -> params is ProtocolParams.Hysteria2
            ProxyProtocol.TUIC -> params is ProtocolParams.Tuic
            ProxyProtocol.ANYTLS -> params is ProtocolParams.AnyTls
            ProxyProtocol.SHADOWTLS -> params is ProtocolParams.ShadowTls
            ProxyProtocol.SSH -> params is ProtocolParams.Ssh
            ProxyProtocol.WIREGUARD -> params is ProtocolParams.WireGuard
        }
        return if (consistent) null else "节点数据已损坏（协议与参数不匹配），请重新导入"
    }

    private fun validateCommon(node: ServerProfile): String? {
        detourProblem(node)?.let { return it }
        if (node.protocol == ProxyProtocol.DIRECT) return null
        if (node.server.isBlank()) return "服务器地址为空"
        if (node.serverPort !in 1..65535) return "端口 ${node.serverPort} 无效"
        if (node.protocol.requiresTls && node.tls?.enabled != true) {
            return "${node.protocol.displayName} 协议必须启用 TLS"
        }
        if (!node.protocol.supportsTls && node.tls?.enabled == true) {
            return "${node.protocol.displayName} 出站没有 tls 字段，启用 TLS 会让内核拒绝整份配置"
        }
        if (!node.protocol.supportsTransport && node.transport != null) {
            return "${node.protocol.displayName} 协议不支持传输层配置"
        }
        if (!node.protocol.supportsMultiplex && node.multiplex?.enabled == true) {
            return "${node.protocol.displayName} 协议不支持多路复用"
        }
        return null
    }

    /**
     * 链式代理里能在单个节点上判定的那部分（FR-2.10）。
     *
     * 成环和「指向的节点不存在 / 已被跳过」需要看到全部节点，交给
     * [DetourResolver]；这里只挡住自指和指向策略组 —— 后者同样是环，
     * 因为 `proxy` / `auto` 的候选里就包含本节点。
     */
    private fun detourProblem(node: ServerProfile): String? {
        val detour = node.detour?.takeIf { it.isNotBlank() } ?: return null
        if (detour == node.outboundTag) return "链式代理指向了自己"
        if (detour == WellKnownTag.PROXY || detour == WellKnownTag.AUTO) {
            return "链式代理不能指向策略组 $detour（该策略组的候选里就包含本节点，会绕成死循环）"
        }
        return null
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw IllegalArgumentException(message)
    }

    private fun requireDuration(value: String?, field: String) {
        if (value.isNullOrBlank()) return
        require(SingBoxFormats.isDuration(value), "$field「$value」不是合法的时长，应形如 30s / 5m / 1h")
    }

    // ---------- 各协议字段 ----------

    private fun JsonObjectBuilder.putHttp(p: ProtocolParams.Http) {
        putIfNotBlank("username", p.username)
        putIfNotBlank("password", p.password)
        putIfNotBlank("path", p.path)
        putIfNotEmpty("headers", p.headers)
    }

    private fun JsonObjectBuilder.putSocks(p: ProtocolParams.Socks) {
        require(p.version in SOCKS_VERSIONS, "SOCKS 版本必须是 4、4a 或 5")
        put("version", p.version)
        putIfNotBlank("username", p.username)
        putIfNotBlank("password", p.password)
        putIfTrue("udp_over_tcp", p.udpOverTcp)
    }

    private fun JsonObjectBuilder.putShadowsocks(p: ProtocolParams.Shadowsocks) {
        require(p.method.isNotBlank(), "缺少加密方式")
        require(p.method == "none" || p.password.isNotBlank(), "缺少密码")
        put("method", p.method)
        put("password", p.password)
        putIfNotBlank("plugin", p.plugin)
        putIfNotBlank("plugin_opts", p.pluginOpts)
        putIfTrue("udp_over_tcp", p.udpOverTcp)
    }

    private fun JsonObjectBuilder.putVMess(p: ProtocolParams.VMess) {
        require(p.uuid.isNotBlank(), "缺少 UUID")
        // sing-vmess 对 security 是白名单匹配，认不出来就整份配置报错
        require(p.security in ProtocolParams.VMess.SECURITIES) {
            "VMess 加密方式「${p.security}」不被 sing-box 支持"
        }
        requirePacketEncoding(p.packetEncoding)
        put("uuid", p.uuid)
        put("security", p.security)
        if (p.alterId != 0) put("alter_id", p.alterId)
        putIfTrue("global_padding", p.globalPadding)
        putIfTrue("authenticated_length", p.authenticatedLength)
        putIfNotBlank("packet_encoding", p.packetEncoding)
    }

    private fun JsonObjectBuilder.putVLess(p: ProtocolParams.VLess, node: ServerProfile) {
        require(p.uuid.isNotBlank(), "缺少 UUID")
        val flow = p.flow?.takeIf { it.isNotBlank() }
        if (flow != null) {
            require(flow == VLESS_FLOW_VISION) { "VLESS flow「$flow」不被 sing-box 支持" }
            // Vision 是在 TLS 记录层之上做的，没有 TLS 就没有可以「看穿」的东西；
            // 服务端会在握手阶段直接断开，表现是「节点显示已连接但一个字节都过不去」
            require(node.tls?.enabled == true) { "VLESS $VLESS_FLOW_VISION 必须配合 TLS 使用" }
        }
        requirePacketEncoding(p.packetEncoding)
        put("uuid", p.uuid)
        putIfNotBlank("flow", flow)
        putIfNotBlank("packet_encoding", p.packetEncoding)
    }

    /** VMess / VLESS 共用：认不出来的值会让 `NewOutbound` 报 unknown packet encoding。 */
    private fun requirePacketEncoding(value: String?) {
        if (value.isNullOrBlank()) return
        require(value in PACKET_ENCODINGS) {
            "packet_encoding「$value」无效，只能是 xudp 或 packetaddr"
        }
    }

    private fun JsonObjectBuilder.putTrojan(p: ProtocolParams.Trojan) {
        require(p.password.isNotBlank(), "缺少密码")
        put("password", p.password)
    }

    private fun JsonObjectBuilder.putHysteria(p: ProtocolParams.Hysteria) {
        // v1 没有 BBR 自适应：带宽是协议握手的一部分，缺了内核直接报错。
        // 两种写法认一种就行，都没有才算缺。
        val hasBandwidth = (!p.up.isNullOrBlank() && !p.down.isNullOrBlank()) ||
            (p.upMbps != null && p.downMbps != null)
        require(hasBandwidth) {
            "Hysteria v1 必须填写上下行带宽（up/down 或 up_mbps/down_mbps）"
        }
        require(!p.authString.isNullOrBlank() || !p.authBase64.isNullOrBlank()) {
            "Hysteria v1 缺少认证串"
        }
        requirePortHopping(p.serverPorts, p.hopInterval)

        putIfNotBlank("up", p.up)
        putIfNotBlank("down", p.down)
        putIfNotNull("up_mbps", p.upMbps)
        putIfNotNull("down_mbps", p.downMbps)
        putIfNotBlank("obfs", p.obfs)
        putIfNotBlank("auth", p.authBase64)
        putIfNotBlank("auth_str", p.authString)
        if (p.serverPorts.isNotEmpty()) {
            putIfNotEmpty("server_ports", p.serverPorts)
            putIfNotBlank("hop_interval", p.hopInterval)
        }
        putIfNotNull("recv_window_conn", p.recvWindowConn)
        putIfNotNull("recv_window", p.recvWindow)
        putIfTrue("disable_mtu_discovery", p.disableMtuDiscovery)
    }

    private fun JsonObjectBuilder.putHysteria2(p: ProtocolParams.Hysteria2) {
        require(p.password.isNotBlank(), "缺少密码")
        requirePortHopping(p.serverPorts, p.hopInterval)
        put("password", p.password)
        // 带宽留空即启用 BBR 自适应，比填错的固定值表现更好。
        putIfNotNull("up_mbps", p.upMbps)
        putIfNotNull("down_mbps", p.downMbps)
        if (p.serverPorts.isNotEmpty()) {
            putIfNotEmpty("server_ports", p.serverPorts)
            putIfNotBlank("hop_interval", p.hopInterval)
        }
        if (!p.obfsType.isNullOrBlank()) {
            require(p.obfsType in ProtocolParams.Hysteria2.OBFS_TYPES) {
                "Hysteria2 混淆方式「${p.obfsType}」在 sing-box 1.13 上不被支持"
            }
            require(!p.obfsPassword.isNullOrBlank(), "启用混淆时必须填写混淆密码")
            putJsonObject("obfs") {
                put("type", p.obfsType)
                put("password", p.obfsPassword)
            }
        }
        putIfTrue("brutal_debug", p.brutalDebug)
    }

    /** Hysteria v1 / v2 共用的端口跳跃校验。 */
    private fun requirePortHopping(serverPorts: List<String>, hopInterval: String?) {
        serverPorts.forEach {
            require(SingBoxFormats.isPortRange(it)) {
                "端口跳跃范围「$it」格式错误，应为 443 或 20000:30000"
            }
        }
        requireDuration(hopInterval, "hop_interval")
    }

    private fun JsonObjectBuilder.putTuic(p: ProtocolParams.Tuic) {
        // TUIC 是唯一一个严格解析 UUID 的协议（`uuid.FromString` 失败即报错），
        // VMess / VLESS 那边解析失败会退化成 UUIDv5，这里不会
        require(SingBoxFormats.isUuid(p.uuid)) { "TUIC 的 UUID「${p.uuid}」格式非法" }
        require(p.congestionControl in ProtocolParams.Tuic.CONGESTION_CONTROLS) {
            "TUIC 拥塞控制「${p.congestionControl}」无效"
        }
        require(p.udpRelayMode in ProtocolParams.Tuic.UDP_RELAY_MODES) {
            "TUIC udp_relay_mode「${p.udpRelayMode}」无效"
        }
        requireDuration(p.heartbeat, "heartbeat")

        put("uuid", p.uuid)
        put("password", p.password)
        put("congestion_control", p.congestionControl)
        // udp_over_stream 与 udp_relay_mode 在内核里是显式互斥的
        // （"udp_over_stream is conflict with udp_relay_mode"），
        // 而 udp_relay_mode 在我们的模型里有默认值，两个一起写必然撞上
        if (p.udpOverStream) {
            put("udp_over_stream", true)
        } else {
            put("udp_relay_mode", p.udpRelayMode)
        }
        putIfTrue("zero_rtt_handshake", p.zeroRttHandshake)
        putIfNotBlank("heartbeat", p.heartbeat)
    }

    private fun JsonObjectBuilder.putAnyTls(p: ProtocolParams.AnyTls) {
        require(p.password.isNotBlank(), "缺少密码")
        requireDuration(p.idleSessionCheckInterval, "idle_session_check_interval")
        requireDuration(p.idleSessionTimeout, "idle_session_timeout")
        put("password", p.password)
        putIfNotBlank("idle_session_check_interval", p.idleSessionCheckInterval)
        putIfNotBlank("idle_session_timeout", p.idleSessionTimeout)
        putIfNotNull("min_idle_session", p.minIdleSession)
    }

    private fun JsonObjectBuilder.putShadowTls(p: ProtocolParams.ShadowTls) {
        require(p.version in ProtocolParams.ShadowTls.VERSIONS) {
            "ShadowTLS 版本 ${p.version} 不被支持，只能是 1、2 或 3"
        }
        // v1 只是纯粹的握手转发，没有认证；v2/v3 靠这个密码做 HMAC，缺了必然握手失败
        require(p.version == 1 || !p.password.isNullOrBlank()) {
            "ShadowTLS v${p.version} 必须填写密码"
        }
        put("version", p.version)
        putIfNotBlank("password", p.password)
    }

    private fun JsonObjectBuilder.putSsh(p: ProtocolParams.Ssh) {
        require(p.user.isNotBlank(), "缺少用户名")
        require(!p.password.isNullOrBlank() || !p.privateKey.isNullOrBlank()) {
            "SSH 必须提供密码或私钥"
        }
        put("user", p.user)
        putIfNotBlank("password", p.password)
        putIfNotBlank("private_key", p.privateKey)
        putIfNotBlank("private_key_passphrase", p.privateKeyPassphrase)
        putIfNotEmpty("host_key", p.hostKey)
        putIfNotEmpty("host_key_algorithms", p.hostKeyAlgorithms)
        putIfNotBlank("client_version", p.clientVersion)
    }

    // ---------- WireGuard endpoint ----------

    /**
     * WireGuard 走 1.13 的 endpoint 形态。
     *
     * 与被移除的 outbound 形态的字段对应关系：
     * `local_address` → `address`、`peer_public_key` → `peers[].public_key`、
     * `server`/`server_port` → `peers[].address`/`peers[].port`，
     * `reserved` 与 `pre_shared_key` 下沉到 peer 里。
     */
    private fun buildEndpoint(node: ServerProfile): OutboundResult.Ok {
        val p = node.params as ProtocolParams.WireGuard

        require(SingBoxFormats.isWireGuardKey(p.privateKey)) {
            "WireGuard 私钥格式非法（应为 Base64 编码的 32 字节）"
        }
        require(SingBoxFormats.isWireGuardKey(p.peerPublicKey)) {
            "WireGuard 对端公钥格式非法（应为 Base64 编码的 32 字节）"
        }
        p.preSharedKey?.takeIf { it.isNotBlank() }?.let {
            require(SingBoxFormats.isWireGuardKey(it)) { "WireGuard 预共享密钥格式非法" }
        }
        // 没有本地地址就没有隧道内的源地址，内核起不来。这个字段在生态里
        // 经常被写成不带掩码的 `10.0.0.2`，那样内核在解析阶段就会失败
        require(p.localAddress.isNotEmpty()) { "WireGuard 缺少本地地址（local_address）" }
        p.localAddress.forEach {
            require(SingBoxFormats.isCidrPrefix(it)) {
                "WireGuard 本地地址「$it」必须带掩码位，例如 10.0.0.2/32"
            }
        }
        val allowedIps = p.allowedIps.ifEmpty { ProtocolParams.WireGuard.DEFAULT_ALLOWED_IPS }
        allowedIps.forEach {
            require(SingBoxFormats.isCidrPrefix(it)) { "WireGuard allowed_ips「$it」不是合法网段" }
        }
        require(p.reserved.isEmpty() || p.reserved.size == ProtocolParams.WireGuard.RESERVED_SIZE) {
            "WireGuard reserved 必须恰好 3 个字节"
        }
        require(p.reserved.all { it in 0..255 }) { "WireGuard reserved 的取值必须在 0-255" }
        require(p.mtu == null || p.mtu in MTU_RANGE) {
            "WireGuard MTU ${p.mtu} 超出可用范围 ${MTU_RANGE.first}-${MTU_RANGE.last}"
        }
        // 内核里是显式互斥的：`listen_port` is conflict with `detour`
        require(p.listenPort == null || node.detour.isNullOrBlank()) {
            "WireGuard 的 listen_port 与链式代理 detour 互斥"
        }

        val json = buildJsonObject {
            put("type", node.protocol.singBoxType)
            put("tag", node.outboundTag)
            putIfNotNull("mtu", p.mtu)
            putIfNotEmpty("address", p.localAddress)
            put("private_key", p.privateKey)
            putIfNotNull("listen_port", p.listenPort)
            putJsonArray("peers") {
                add(
                    buildJsonObject {
                        put("address", node.server)
                        put("port", node.serverPort)
                        put("public_key", p.peerPublicKey)
                        putIfNotBlank("pre_shared_key", p.preSharedKey)
                        putIfNotEmpty("allowed_ips", allowedIps)
                        putIfNotNull(
                            "persistent_keepalive_interval",
                            p.persistentKeepaliveInterval,
                        )
                        putIfNotEmpty("reserved", p.reserved)
                    },
                )
            }
            putIfNotBlank("detour", node.detour)
        }
        return OutboundResult.Ok(json, endpoint = true)
    }

    // ---------- 传输层 / TLS / 多路复用 ----------

    private fun JsonObjectBuilder.putTransport(node: ServerProfile) {
        val transport = node.transport ?: return
        // QUIC 传输层跑在 TLS 之上，没有 TLS 时内核报 ErrTLSRequired
        require(transport !is TransportConfig.Quic || node.tls?.enabled == true) {
            "QUIC 传输层必须启用 TLS"
        }
        putJsonObject("transport") {
            put("type", transport.singBoxType)
            when (transport) {
                is TransportConfig.WebSocket -> {
                    putIfNotBlank("path", transport.path)
                    putIfNotEmpty("headers", transport.headers)
                    if (transport.maxEarlyData > 0) {
                        put("max_early_data", transport.maxEarlyData)
                        putIfNotBlank("early_data_header_name", transport.earlyDataHeaderName)
                    }
                }
                is TransportConfig.Grpc -> {
                    requireDuration(transport.idleTimeout, "grpc idle_timeout")
                    requireDuration(transport.pingTimeout, "grpc ping_timeout")
                    putIfNotBlank("service_name", transport.serviceName)
                    putIfNotBlank("idle_timeout", transport.idleTimeout)
                    putIfNotBlank("ping_timeout", transport.pingTimeout)
                    putIfTrue("permit_without_stream", transport.permitWithoutStream)
                }
                is TransportConfig.Http -> {
                    requireDuration(transport.idleTimeout, "http idle_timeout")
                    requireDuration(transport.pingTimeout, "http ping_timeout")
                    putIfNotEmpty("host", transport.host)
                    putIfNotBlank("path", transport.path)
                    putIfNotBlank("method", transport.method)
                    putIfNotEmpty("headers", transport.headers)
                    putIfNotBlank("idle_timeout", transport.idleTimeout)
                    putIfNotBlank("ping_timeout", transport.pingTimeout)
                }
                is TransportConfig.HttpUpgrade -> {
                    putIfNotBlank("host", transport.host)
                    putIfNotBlank("path", transport.path)
                    putIfNotEmpty("headers", transport.headers)
                }
                is TransportConfig.Quic -> Unit
            }
        }
    }

    private fun JsonObjectBuilder.putTls(node: ServerProfile) {
        val tls = node.tls ?: return
        if (!tls.enabled) return
        putJsonObject("tls") {
            put("enabled", true)
            putIfNotBlank("server_name", tls.serverName ?: node.server.takeIf { !it.isIpAddress() })
            putIfTrue("insecure", tls.insecure)
            putIfNotEmpty("alpn", tls.alpn.ifEmpty { node.protocol.defaultAlpn() })
            putIfNotBlank("min_version", tls.minVersion)
            putIfNotBlank("max_version", tls.maxVersion)
            putIfNotEmpty("cipher_suites", tls.cipherSuites)
            putIfNotBlank("certificate", tls.certificate)

            val reality = tls.reality
            if (reality != null && reality.enabled) {
                require(reality.publicKey.isNotBlank(), "REALITY 缺少 public_key")
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", reality.publicKey)
                    putIfNotBlank("short_id", reality.shortId)
                }
                // REALITY 依赖 uTLS 完成指纹伪装，缺失时补默认值而不是报错。
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", tls.utls?.fingerprint ?: "chrome")
                }
            } else {
                val utls = tls.utls
                if (utls != null && utls.enabled) {
                    putJsonObject("utls") {
                        put("enabled", true)
                        put("fingerprint", utls.fingerprint)
                    }
                }
            }

            val ech = tls.ech
            if (ech != null && ech.enabled) {
                putJsonObject("ech") {
                    put("enabled", true)
                    putIfNotEmpty("config", ech.config)
                }
            }
        }
    }

    private fun JsonObjectBuilder.putMultiplex(node: ServerProfile) {
        val mux = node.multiplex ?: return
        if (!mux.enabled) return
        require(mux.protocol in MultiplexConfig.PROTOCOLS, "未知的多路复用协议 ${mux.protocol}")
        putJsonObject("multiplex") {
            put("enabled", true)
            put("protocol", mux.protocol)
            putIfNotNull("max_connections", mux.maxConnections)
            putIfNotNull("min_streams", mux.minStreams)
            putIfNotNull("max_streams", mux.maxStreams)
            putIfTrue("padding", mux.padding)
            val brutal = mux.brutal
            if (brutal != null && brutal.enabled) {
                // sing-mux 对低于 64 KB/s 的带宽直接报 "brutal: invalid upload speed"，
                // 而 1 Mbps = 125 KB/s，所以按 Mbps 算就是「必须 ≥ 1」
                require(brutal.upMbps >= 1 && brutal.downMbps >= 1) {
                    "Brutal 拥塞控制必须填写不小于 1 Mbps 的上下行带宽"
                }
                putJsonObject("brutal") {
                    put("enabled", true)
                    put("up_mbps", brutal.upMbps)
                    put("down_mbps", brutal.downMbps)
                }
            }
        }
    }

    private companion object {
        val SOCKS_VERSIONS = setOf("4", "4a", "5")
        val PACKET_ENCODINGS = setOf("xudp", "packetaddr")
        const val VLESS_FLOW_VISION = "xtls-rprx-vision"

        /** 低于 1280 连 IPv6 的最小 MTU 都不到，高于 1500 会在大多数链路上被丢弃。 */
        val MTU_RANGE = 1280..1500
    }
}

/**
 * 判据取自生成结果本身，而不是把 `putTls` 里那串条件
 * （tls 非空、enabled、insecure）再抄一份 —— 抄出来的第二份迟早会跟原件漂移，
 * 而漂移的后果是「配置里关着证书校验，警告却没发出来」。
 */
private fun JsonObject.declaresInsecureTls(): Boolean =
    ((this["tls"] as? JsonObject)?.get("insecure") as? JsonPrimitive)?.booleanOrNull == true

/** QUIC 系协议在 ALPN 缺省时给出协议要求的默认值。 */
private fun ProxyProtocol.defaultAlpn(): List<String> = when (this) {
    ProxyProtocol.HYSTERIA2, ProxyProtocol.HYSTERIA -> listOf("h3")
    else -> emptyList()
}

private fun String.isIpAddress(): Boolean =
    matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || contains(':')
