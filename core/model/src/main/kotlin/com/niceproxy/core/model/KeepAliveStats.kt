package com.niceproxy.core.model

import kotlinx.serialization.Serializable

/**
 * 代理这一次是被谁拉起来的。
 *
 * 记这个是因为保活质量在此之前**无法证伪**：用户只能凭感觉说「好像不太稳」，
 * 而我们连「昨晚它被杀过没有」都答不上来。分清来源之后，「感觉不稳」就变成了
 * 「昨晚被系统杀了 3 次，都由看门狗在 15 分钟内拉回来」——那是一句能指导行动的话，
 * 它直接告诉用户该去开电池优化白名单还是厂商自启动白名单。
 *
 * 光看状态是分不出来的：开机自启和「被杀之后恢复」在服务眼里长得一模一样，
 * 都是「从未运行变成运行」。只有发起方知道自己是谁。
 */
@Serializable
enum class StartReason(val label: String, val involuntary: Boolean) {
    /** 用户按了启动、磁贴或桌面快捷方式。 */
    USER("手动启动", involuntary = false),

    /** 开机自启。设备重启不算「被杀」，用户自己配的。 */
    BOOT("开机自启", involuntary = false),

    /** 内核自己退出了，服务还活着，就地重启。见 `ProxyService.superviseCore`。 */
    CORE_REVIVE("内核自愈", involuntary = true),

    /** 进程被系统回收，`START_STICKY` 让系统把服务重建了。 */
    STICKY_RESTART("进程重建", involuntary = true),

    /** 看门狗醒来发现该跑却没在跑。说明前两层都没兜住。 */
    WATCHDOG("看门狗恢复", involuntary = true),

    /** 前面全都没救回来，用户打开应用时才补上。这是最坏的一档。 */
    COLD_START("打开应用时恢复", involuntary = true),
}

/** 一次非自愿中断，以及它最终是靠哪一层救回来的。 */
@Serializable
data class InterruptionRecord(
    val atMillis: Long,
    val recovery: StartReason,
)

/**
 * 保活的运行记录。
 *
 * @param sessionStartedAt 本轮连续服务的起点，null 表示当前没在跑。
 *        内核自愈这种「服务没断、只是内核重启」的情况不重置它 —— 对局域网里那些
 *        设备来说，网关一直是同一个，中断由 [interruptions] 单独记账。
 * @param interruptions 倒序排列，最近的在前。
 */
data class KeepAliveStats(
    val sessionStartedAt: Long? = null,
    val interruptions: List<InterruptionRecord> = emptyList(),
) {
    /** 本轮会话期间发生过几次中断。判断「现在这一轮稳不稳」看这个。 */
    fun interruptionsSince(startedAt: Long?): Int {
        if (startedAt == null) return 0
        return interruptions.count { it.atMillis >= startedAt }
    }

    fun interruptionsWithin(windowMillis: Long, now: Long = System.currentTimeMillis()): Int =
        interruptions.count { now - it.atMillis <= windowMillis }
}
