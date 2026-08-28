package com.niceproxy.core.config.share

import com.niceproxy.core.model.ServerProfile
import com.niceproxy.core.model.WellKnownTag

/**
 * 把订阅里的链式代理引用换算成本地的出站 tag（FR-2.10）。
 *
 * 订阅里的引用用的是源配置的标识（Clash 的 `dialer-proxy` 用节点名、
 * sing-box 的 `detour` 用 outbound tag），而我们给每个导入的节点都重新生成了
 * id，出站 tag 是从这个新 id 派生的。不做这一步翻译，`detour` 会指向一个本地
 * 根本不存在的 tag，那个节点在生成配置时会被整个剔除。
 */
internal object ChainLinker {

    /**
     * @param chains 节点 id → 它在源配置里引用的那个标识
     * @param keyOf 从节点取出「源配置里用来引用它的那个标识」
     * @param failures 解析不了的引用往这里记，调用方会一起呈现给用户
     */
    fun link(
        nodes: List<ServerProfile>,
        chains: Map<String, String>,
        keyOf: (ServerProfile) -> String,
        failures: MutableList<ParseFailure>,
    ): List<ServerProfile> {
        if (chains.isEmpty()) return nodes

        val byKey = nodes.associateBy(keyOf)
        return nodes.mapNotNull { node ->
            val reference = chains[node.id] ?: return@mapNotNull node
            if (reference.equals(WellKnownTag.DIRECT, ignoreCase = true)) {
                return@mapNotNull node.copy(detour = WellKnownTag.DIRECT)
            }

            val target = byKey[reference]
            when {
                target == null -> {
                    // 整条不导入，而不是「丢掉 detour 照常导入」：用户配链式代理
                    // 通常是因为落地机只接受中转机的 IP，直连过去要么连不上、
                    // 要么在对端留下一条本不该出现的记录
                    failures += ParseFailure.of(
                        node.name,
                        "链式代理指向的「$reference」不在本次订阅里",
                    )
                    null
                }
                target.id == node.id -> {
                    failures += ParseFailure.of(node.name, "链式代理指向了自己")
                    null
                }
                else -> node.copy(detour = target.outboundTag)
            }
        }
    }
}
