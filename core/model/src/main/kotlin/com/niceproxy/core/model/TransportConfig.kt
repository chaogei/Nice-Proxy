package com.niceproxy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * v2ray 系传输层。仅 VMess / VLESS / Trojan 支持；
 * QUIC 系协议（Hysteria2 / TUIC）自带传输层，不使用本配置。
 */
@Serializable
sealed interface TransportConfig {

    val singBoxType: String

    @Serializable
    @SerialName("ws")
    data class WebSocket(
        val path: String = "/",
        val headers: Map<String, String> = emptyMap(),
        val maxEarlyData: Int = 0,
        val earlyDataHeaderName: String? = null,
    ) : TransportConfig {
        override val singBoxType: String get() = "ws"
    }

    @Serializable
    @SerialName("grpc")
    data class Grpc(
        val serviceName: String = "",
        val idleTimeout: String? = null,
        val pingTimeout: String? = null,
        val permitWithoutStream: Boolean = false,
    ) : TransportConfig {
        override val singBoxType: String get() = "grpc"
    }

    @Serializable
    @SerialName("http")
    data class Http(
        val host: List<String> = emptyList(),
        val path: String = "/",
        val method: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val idleTimeout: String? = null,
        val pingTimeout: String? = null,
    ) : TransportConfig {
        override val singBoxType: String get() = "http"
    }

    @Serializable
    @SerialName("httpupgrade")
    data class HttpUpgrade(
        val host: String? = null,
        val path: String = "/",
        val headers: Map<String, String> = emptyMap(),
    ) : TransportConfig {
        override val singBoxType: String get() = "httpupgrade"
    }

    @Serializable
    @SerialName("quic")
    data object Quic : TransportConfig {
        override val singBoxType: String get() = "quic"
    }
}
