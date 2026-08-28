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

    /**
     * WireGuard。
     *
     * [singBoxType] 与其他协议一样是 `wireguard`，但 1.13 起它**不在 `outbounds` 里**——
     * 见 [isEndpoint]。
     */
    @SerialName("wireguard")
    WIREGUARD("wireguard", "WireGuard", "WG"),
    ;

    /**
     * 这个协议在 sing-box 1.13 里属于 `endpoints` 而不是 `outbounds`。
     *
     * WireGuard 的 outbound 形态（`local_address` / `peer_public_key` 那一套）在
     * 1.11 被标记废弃，并已于 **1.13.0 移除**。写成 outbound 的话，内核在解析阶段
     * 就报 `unknown outbound type: wireguard` 并拒绝**整份**配置 —— 不是这一个节点
     * 不可用，而是所有节点一起失效。所以模型层就要把这个差异标出来，
     * 让配置生成器把它放进 `endpoints` 数组。
     *
     * endpoint 与 outbound 共用同一个 tag 命名空间（`option.checkOutbounds` 是
     * 合起来查重的），也一样能被 selector / urltest / 路由规则引用。
     */
    val isEndpoint: Boolean
        get() = this == WIREGUARD

    /** QUIC 系协议自带传输层，不接受 transport 配置。 */
    val isQuicBased: Boolean
        get() = this == HYSTERIA || this == HYSTERIA2 || this == TUIC

    /**
     * 协议本身强制要求 TLS，UI 不应允许关闭。
     *
     * ShadowTLS 也在其中：它整个协议就是「把流量伪装成一次真实的 TLS 握手」，
     * sing-box 的 `shadowtls.NewOutbound` 第一件事就是 `if options.TLS == nil ||
     * !options.TLS.Enabled { return nil, C.ErrTLSRequired }`。
     */
    val requiresTls: Boolean
        get() = this == HYSTERIA || this == HYSTERIA2 || this == TUIC ||
            this == ANYTLS || this == SHADOWTLS

    /**
     * 出站结构里到底有没有 `tls` 这个字段。
     *
     * 只有 sing-box 的 option 结构体内嵌了 `OutboundTLSOptionsContainer` 的协议才有。
     * `shadowsocks`、`socks`、`ssh`、`direct`、`wireguard` 都没有 —— 给它们写一个
     * `tls` 对象不是「被忽略」，而是解析阶段的未知字段，内核连带拒绝**整份**配置。
     *
     * 这条路径真实存在：机场的 Clash 模板里给 ss 节点顺手写上 `tls: true` 很常见，
     * 订阅解析会把它照单收下。
     */
    val supportsTls: Boolean
        get() = this == HTTP || this == VMESS || this == VLESS || this == TROJAN ||
            this == HYSTERIA || this == HYSTERIA2 || this == TUIC ||
            this == ANYTLS || this == SHADOWTLS

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
