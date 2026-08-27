package com.niceproxy.core.config.share

import com.niceproxy.core.model.ServerProfile

/**
 * 外部来源（订阅正文、分享链接）声明的「跳过证书校验」一律不采信。
 *
 * 订阅正文由机场控制，而 `SubscriptionUpdateWorker` 会周期性拉取并覆盖已有节点：
 * 导入时干净的订阅，可以在某一次自动更新之后把所有节点悄悄变成不校验证书，
 * 此后中间人能解密经过代理的全部流量 —— 不只是这台手机的，而是局域网里
 * 每一台把网关指过来的设备的。分享链接同理，来源是剪贴板和二维码。
 *
 * 这个开关的正当用途（连自签证书的自建服务）完全可以由用户在节点编辑页显式打开，
 * 那条路径有风险提示、有明确的操作意图；远端单方面替用户打开则一样都没有。
 * 所以解析侧只做一件事：强制置 false，并把「有多少个节点提过这个要求」带出去。
 */
internal object RemoteTlsPolicy {

    data class Sanitized(
        val profile: ServerProfile,
        /** 这个节点原本要求关闭证书校验，已被忽略。 */
        val insecureIgnored: Boolean,
    )

    fun sanitize(profile: ServerProfile): Sanitized {
        val tls = profile.tls
        if (tls?.insecure != true) return Sanitized(profile, insecureIgnored = false)
        return Sanitized(profile.copy(tls = tls.copy(insecure = false)), insecureIgnored = true)
    }
}
