package com.niceproxy.core.config.internal

import com.niceproxy.core.model.WellKnownTag

/**
 * 节点链式代理（FR-2.10）的全局收敛。
 *
 * 单个节点上能判的（自指、指向策略组）已经由 `OutboundFactory` 挡掉了；
 * 剩下两种必须看到全部节点才判得出来：
 *
 * - **成环**：A 经 B、B 经 C、C 又经 A。内核里 detour 是懒解析的，装配阶段
 *   察觉不到，跑起来之后第一次拨号就会无限递归下去。
 * - **悬空**：指向的节点被删了，或者它自己因为别的原因没能生成出来。
 *   这时候写出去的 `detour` 引用一个不存在的 tag，内核拒绝**整份**配置。
 *
 * 两种都按 fail-closed 处理 —— 把这个节点剔除，而不是「忽略 detour 直接出站」。
 * 后者才是真正危险的：用户特意配了链路（比如中转机在墙内、落地机只允许中转机
 * 的 IP 访问），悄悄改成直连意味着流量以完全不同的路径、完全不同的源 IP 发出去。
 */
internal object DetourResolver {

    /**
     * @param detours 已通过单节点校验的 tag → 它的 detour 目标（只含非空的）
     * @param available 已通过单节点校验的全部 tag
     * @return 必须额外剔除的 tag → 原因
     */
    fun reject(detours: Map<String, String>, available: Set<String>): Map<String, String> {
        if (detours.isEmpty()) return emptyMap()

        val rejected = LinkedHashMap<String, String>()
        cyclicTags(detours).forEach { tag ->
            rejected[tag] = "链式代理成环：${describeCycle(tag, detours)}"
        }

        // 悬空要跑到不动点：剔掉 A 之后，本来好好指着 A 的 B 也跟着悬空了
        var changed = true
        while (changed) {
            changed = false
            detours.forEach { (tag, target) ->
                if (tag in rejected || target == WellKnownTag.DIRECT) return@forEach
                val reason = when {
                    target !in available -> "链式代理指向的节点 $target 不存在"
                    target in rejected -> "链式代理指向的节点 $target 不可用"
                    else -> return@forEach
                }
                rejected[tag] = reason
                changed = true
            }
        }
        return rejected
    }

    /**
     * 每个节点最多一个 detour，整张图是「函数图」：从任一点出发只有一条路。
     * 所以不需要通用的强连通分量算法，顺着走一遍、看是否回到本次走过的点即可。
     */
    private fun cyclicTags(detours: Map<String, String>): Set<String> {
        val onCycle = LinkedHashSet<String>()
        val settled = HashSet<String>()

        detours.keys.forEach { start ->
            if (start in settled) return@forEach
            val path = LinkedHashSet<String>()
            var current: String? = start
            while (current != null && current !in settled) {
                if (!path.add(current)) {
                    // 回到了本次路径上的某一点：从那一点起的这一段全在环上
                    onCycle += path.dropWhile { it != current }
                    break
                }
                current = detours[current]
            }
            settled += path
        }
        return onCycle
    }

    /** 给用户看的环路径，例如 `node-a → node-b → node-a`。 */
    private fun describeCycle(start: String, detours: Map<String, String>): String {
        val path = mutableListOf(start)
        var current = detours[start]
        while (current != null && current != start && path.size <= MAX_CYCLE_DESCRIPTION) {
            path += current
            current = detours[current]
        }
        path += start
        return path.joinToString(" → ")
    }

    private const val MAX_CYCLE_DESCRIPTION = 8
}
