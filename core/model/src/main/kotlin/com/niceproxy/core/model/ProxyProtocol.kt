package com.niceproxy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 出站协议类型。
 *
 * [singBoxType] 必须与 sing-box 配置中 `outbounds[].type` 的取值完全一致，
 * 配置生成器直接使用该值，不做二次映射。
 */
@Serializable
enum class ProxyProtocol(
    val singBoxType: String,
    val displayName: String,
    val badge: String,
) {
    @SerialName("direct")
    DIRECT("direct", "直连", "DIR"),

    @SerialName("http")
    HTTP("http", "HTTP", "HTTP"),

    @SerialName("socks")
    SOCKS("socks", "SOCKS", "SOCKS"),

    @SerialName("shadowsocks")
    SHADOWSOCKS("shadowsocks", "Shadowsocks", "SS"),

    @SerialName("vmess")
    VMESS("vmess", "VMess", "VM"),

    @SerialName("vless")
    VLESS("vless", "VLESS", "VL"),

    @SerialName("trojan")
    TROJAN("trojan", "Trojan", "TR"),

    @SerialName("hysteria")
    HYSTERIA("hysteria", "Hysteria", "HY"),

    @SerialName("hysteria2")
    HYSTERIA2("hysteria2", "Hysteria2", "HY2"),

    @SerialName("tuic")
    TUIC("tuic", "TUIC", "TUIC"),

    @SerialName("anytls")
    ANYTLS("anytls", "AnyTLS", "ATLS"),

    @SerialName("shadowtls")
    SHADOWTLS("shadowtls", "ShadowTLS", "STLS"),

    @SerialName("ssh")
    SSH("ssh", "SSH", "SSH"),
    ;

    /** QUIC 系协议自带传输层，不接受 transport 配置。 */
    val isQuicBased: Boolean
        get() = this == HYSTERIA || this == HYSTERIA2 || this == TUIC

    /** 协议本身强制要求 TLS，UI 不应允许关闭。 */
    val requiresTls: Boolean
        get() = this == HYSTERIA || this == HYSTERIA2 || this == TUIC || this == ANYTLS

    /** 是否支持 v2ray 系传输层（ws / grpc / http / httpupgrade / quic）。 */
    val supportsTransport: Boolean
        get() = this == VMESS || this == VLESS || this == TROJAN

    /** 是否支持多路复用。 */
    val supportsMultiplex: Boolean
        get() = this == SHADOWSOCKS || this == VMESS || this == VLESS || this == TROJAN

    companion object {
        fun fromSingBoxType(type: String): ProxyProtocol? =
            entries.firstOrNull { it.singBoxType == type }
    }
}
