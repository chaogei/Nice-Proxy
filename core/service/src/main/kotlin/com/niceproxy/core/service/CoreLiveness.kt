package com.niceproxy.core.service

/**
 * 内核存活探测的判定与记账。
 *
 * 从 [ProxyService] 里拆出来是为了让两件事变成可以被测试钉住的约定 —— 它们原先
 * 埋在一个 `while (true)` 里，而那个循环跑在 Android 服务上，没有任何办法验证。
 *
 * **一、探测成功必须向控制面通告内核活着。** Clash API 的 REST 面有熔断器
 * （见 `ClashApiClient`）：内核死掉的那阵子它会打开，之后即便内核已经回来，
 * 冷却也还要自己走完。这中间用户点节点切换得到的是「正在冷却」，而界面上那个
 * 按钮明明是可点的 —— 看起来就是个 bug。探活是整条链路上第一个知道内核回来了
 * 的人，它有义务立刻说出来，见 [onAlive]。
 *
 * **二、单次探测失败不判死刑。** 重启内核会断掉全屋设备的连接，为一次可能只是
 * 「刚好赶上系统卡了一下」的回环超时付这个代价不划算。
 *
 * @param missesBeforeDead 连续这么多次探不到才判定内核已死。
 * @param probe 一次探测。true 表示内核还活着。
 * @param onAlive 探测成功后的通告。只在**这一次**探测成功时调用，包括连着成功的
 *        每一次 —— 熔断可能在两次探测之间才打开，只在「由死转生」时通告会漏掉它。
 */
internal class CoreLiveness(
    private val missesBeforeDead: Int,
    private val probe: suspend () -> Boolean,
    private val onAlive: () -> Unit,
) {

    /** 一次探测之后该怎么办。 */
    enum class Verdict {
        /** 内核答上话了。 */
        ALIVE,

        /** 这次没答上，但还没到判死刑的次数，再观察一轮。 */
        WATCHING,

        /** 连续失败够多次了，该拉起来了。 */
        DEAD,
    }

    var consecutiveMisses: Int = 0
        private set

    suspend fun check(): Verdict {
        if (probe()) {
            consecutiveMisses = 0
            onAlive()
            return Verdict.ALIVE
        }
        if (++consecutiveMisses < missesBeforeDead) return Verdict.WATCHING
        // 判完就归零。留着的话，拉起内核之后的第一次探测失败会立刻再判一次死刑，
        // 而那时候内核可能只是还没把 Clash API 端口监听起来。
        consecutiveMisses = 0
        return Verdict.DEAD
    }

    /** 重启期间探不到是理所当然的，那些不该算进死亡判定里。 */
    fun reset() {
        consecutiveMisses = 0
    }
}
