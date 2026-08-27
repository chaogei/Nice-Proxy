package com.niceproxy.core.config.internal

import com.niceproxy.core.model.MultiplexConfig
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal sealed interface OutboundResult {
    data class Ok(val json: JsonObject) : OutboundResult
    data class Invalid(val reason: String) : OutboundResult
}

/**
 * 把 [ServerProfile] 翻译成 sing-box 的一个 outbound 对象。
 *
 * 每个协议分支只写出该协议真正接受的字段 —— sing-box 对未知字段是
 * 严格拒绝的，多写一个字段会导致整份配置加载失败。
 */
internal class OutboundFactory {

    fun create(node: ServerProfile): OutboundResult {
        validateCommon(node)?.let { return OutboundResult.Invalid(it) }

        val params = node.params
        return runCatching {
            buildJsonObject {
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
                    is ProtocolParams.VLess -> putVLess(params)
                    is ProtocolParams.Trojan -> putTrojan(params)
                    is ProtocolParams.Hysteria -> putHysteria(params)
                    is ProtocolParams.Hysteria2 -> putHysteria2(params)
                    is ProtocolParams.Tuic -> putTuic(params)
                    is ProtocolParams.AnyTls -> putAnyTls(params)
                    is ProtocolParams.ShadowTls -> putShadowTls(params)
                    is ProtocolParams.Ssh -> putSsh(params)
                }
                putTransport(node)
                putTls(node)
                putMultiplex(node)
            }
        }.fold(
            onSuccess = { OutboundResult.Ok(it) },
            onFailure = { OutboundResult.Invalid(it.message ?: "未知错误") },
        )
    }

    // ---------- 通用校验 ----------

    private fun validateCommon(node: ServerProfile): String? {
        if (node.protocol == ProxyProtocol.DIRECT) return null
        if (node.server.isBlank()) return "服务器地址为空"
        if (node.serverPort !in 1..65535) return "端口 ${node.serverPort} 无效"
        if (node.protocol.requiresTls && node.tls?.enabled != true) {
            return "${node.protocol.displayName} 协议必须启用 TLS"
        }
        if (!node.protocol.supportsTransport && node.transport != null) {
            return "${node.protocol.displayName} 协议不支持传输层配置"
        }
        if (!node.protocol.supportsMultiplex && node.multiplex?.enabled == true) {
            return "${node.protocol.displayName} 协议不支持多路复用"
        }
        return null
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw IllegalArgumentException(message)
    }

    // ---------- 各协议字段 ----------

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHttp(p: ProtocolParams.Http) {
        putIfNotBlank("username", p.username)
        putIfNotBlank("password", p.password)
        putIfNotBlank("path", p.path)
        putIfNotEmpty("headers", p.headers)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSocks(p: ProtocolParams.Socks) {
        require(p.version in setOf("4", "4a", "5"), "SOCKS 版本必须是 4、4a 或 5")
        put("version", p.version)
        putIfNotBlank("username", p.username)
        putIfNotBlank("password", p.password)
        putIfTrue("udp_over_tcp", p.udpOverTcp)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putShadowsocks(p: ProtocolParams.Shadowsocks) {
        require(p.method.isNotBlank(), "缺少加密方式")
        require(p.method == "none" || p.password.isNotBlank(), "缺少密码")
        put("method", p.method)
        put("password", p.password)
        putIfNotBlank("plugin", p.plugin)
        putIfNotBlank("plugin_opts", p.pluginOpts)
        putIfTrue("udp_over_tcp", p.udpOverTcp)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putVMess(p: ProtocolParams.VMess) {
        require(p.uuid.isNotBlank(), "缺少 UUID")
        put("uuid", p.uuid)
        put("security", p.security)
        if (p.alterId != 0) put("alter_id", p.alterId)
        putIfTrue("global_padding", p.globalPadding)
        putIfTrue("authenticated_length", p.authenticatedLength)
        putIfNotBlank("packet_encoding", p.packetEncoding)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putVLess(p: ProtocolParams.VLess) {
        require(p.uuid.isNotBlank(), "缺少 UUID")
        put("uuid", p.uuid)
        putIfNotBlank("flow", p.flow)
        putIfNotBlank("packet_encoding", p.packetEncoding)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTrojan(p: ProtocolParams.Trojan) {
        require(p.password.isNotBlank(), "缺少密码")
        put("password", p.password)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHysteria(p: ProtocolParams.Hysteria) {
        putIfNotBlank("up", p.up)
        putIfNotBlank("down", p.down)
        putIfNotBlank("obfs", p.obfs)
        putIfNotBlank("auth_str", p.authString)
        putIfNotNull("recv_window_conn", p.recvWindowConn)
        putIfNotNull("recv_window", p.recvWindow)
        putIfTrue("disable_mtu_discovery", p.disableMtuDiscovery)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putHysteria2(p: ProtocolParams.Hysteria2) {
        require(p.password.isNotBlank(), "缺少密码")
        put("password", p.password)
        // 带宽留空即启用 BBR 自适应，比填错的固定值表现更好。
        putIfNotNull("up_mbps", p.upMbps)
        putIfNotNull("down_mbps", p.downMbps)
        if (p.serverPorts.isNotEmpty()) {
            p.serverPorts.forEach {
                require(PORT_RANGE_PATTERN.matches(it), "端口跳跃范围「$it」格式错误，应为 443 或 20000:30000")
            }
            putIfNotEmpty("server_ports", p.serverPorts)
            putIfNotBlank("hop_interval", p.hopInterval)
        }
        if (!p.obfsType.isNullOrBlank()) {
            require(p.obfsType == "salamander", "Hysteria2 目前仅支持 salamander 混淆")
            require(!p.obfsPassword.isNullOrBlank(), "启用混淆时必须填写混淆密码")
            putJsonObject("obfs") {
                put("type", p.obfsType)
                put("password", p.obfsPassword)
            }
        }
        putIfTrue("brutal_debug", p.brutalDebug)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTuic(p: ProtocolParams.Tuic) {
        require(p.uuid.isNotBlank(), "缺少 UUID")
        put("uuid", p.uuid)
        put("password", p.password)
        put("congestion_control", p.congestionControl)
        put("udp_relay_mode", p.udpRelayMode)
        putIfTrue("udp_over_stream", p.udpOverStream)
        putIfTrue("zero_rtt_handshake", p.zeroRttHandshake)
        putIfNotBlank("heartbeat", p.heartbeat)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAnyTls(p: ProtocolParams.AnyTls) {
        require(p.password.isNotBlank(), "缺少密码")
        put("password", p.password)
        putIfNotBlank("idle_session_check_interval", p.idleSessionCheckInterval)
        putIfNotBlank("idle_session_timeout", p.idleSessionTimeout)
        putIfNotNull("min_idle_session", p.minIdleSession)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putShadowTls(p: ProtocolParams.ShadowTls) {
        put("version", p.version)
        putIfNotBlank("password", p.password)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSsh(p: ProtocolParams.Ssh) {
        require(p.user.isNotBlank(), "缺少用户名")
        put("user", p.user)
        putIfNotBlank("password", p.password)
        putIfNotBlank("private_key", p.privateKey)
        putIfNotBlank("private_key_passphrase", p.privateKeyPassphrase)
        putIfNotEmpty("host_key_algorithms", p.hostKeyAlgorithms)
    }

    // ---------- 传输层 / TLS / 多路复用 ----------

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTransport(node: ServerProfile) {
        val transport = node.transport ?: return
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
                    putIfNotBlank("service_name", transport.serviceName)
                    putIfNotBlank("idle_timeout", transport.idleTimeout)
                    putIfNotBlank("ping_timeout", transport.pingTimeout)
                    putIfTrue("permit_without_stream", transport.permitWithoutStream)
                }
                is TransportConfig.Http -> {
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTls(node: ServerProfile) {
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMultiplex(node: ServerProfile) {
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
                putJsonObject("brutal") {
                    put("enabled", true)
                    put("up_mbps", brutal.upMbps)
                    put("down_mbps", brutal.downMbps)
                }
            }
        }
    }

    private companion object {
        val PORT_RANGE_PATTERN = Regex("""^\d{1,5}(:\d{1,5})?$""")
    }
}

/** QUIC 系协议在 ALPN 缺省时给出协议要求的默认值。 */
private fun ProxyProtocol.defaultAlpn(): List<String> = when (this) {
    ProxyProtocol.HYSTERIA2, ProxyProtocol.HYSTERIA -> listOf("h3")
    else -> emptyList()
}

private fun String.isIpAddress(): Boolean =
    matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || contains(':')
