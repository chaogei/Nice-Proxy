package com.niceproxy.core.network.clash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/traffic` WebSocket 每秒推送一帧，单位为字节。 */
@Serializable
data class TrafficFrame(
    val up: Long = 0,
    val down: Long = 0,
)

/** `/memory` WebSocket。 */
@Serializable
data class MemoryFrame(
    val inuse: Long = 0,
    val oslimit: Long = 0,
)

/** `/logs` WebSocket。 */
@Serializable
data class LogFrame(
    val type: String = "info",
    val payload: String = "",
)

/** `/connections` WebSocket 推送的全量快照。 */
@Serializable
data class ConnectionsSnapshot(
    @SerialName("downloadTotal") val downloadTotal: Long = 0,
    @SerialName("uploadTotal") val uploadTotal: Long = 0,
    val connections: List<ConnectionInfo> = emptyList(),
)

@Serializable
data class ConnectionInfo(
    val id: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val metadata: ConnectionMetadata = ConnectionMetadata(),
) {
    /** 客户端设备的地址，网关形态下这是识别「谁在用」的关键信息。 */
    val clientAddress: String
        get() = metadata.sourceIP.ifEmpty { "-" }

    val destination: String
        get() = metadata.host.ifEmpty { metadata.destinationIP }
            .let { if (metadata.destinationPort.isEmpty()) it else "$it:${metadata.destinationPort}" }

    /** 实际使用的出站，chains 是倒序的，第一个元素才是最终出站。 */
    val outbound: String
        get() = chains.firstOrNull() ?: "-"
}

@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
)

/** `/proxies` 返回的策略组与节点状态。 */
@Serializable
data class ProxiesResponse(
    val proxies: Map<String, ProxyInfo> = emptyMap(),
)

@Serializable
data class ProxyInfo(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: List<String> = emptyList(),
    val udp: Boolean = false,
    val history: List<DelayHistory> = emptyList(),
) {
    val latestDelayMs: Int?
        get() = history.lastOrNull()?.delay?.takeIf { it > 0 }
}

@Serializable
data class DelayHistory(
    val time: String = "",
    val delay: Int = 0,
)

@Serializable
data class DelayResponse(
    val delay: Int = 0,
)

@Serializable
data class VersionResponse(
    val version: String = "",
)
