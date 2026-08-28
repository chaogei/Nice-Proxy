package com.niceproxy.core.service

import com.niceproxy.core.network.clash.ClashApiMetrics
import com.niceproxy.core.service.pac.PacServer

/**
 * 把控制面与 PAC 的运行期观测量拼成一行日志。
 *
 * 这些数字没有展示它们的界面，短期内也不该有 —— 对用户而言它们全无意义。但
 * 「代理开着开着就卡一下」「家里有几台设备偶尔上不了网」这类反馈，除了它们之外
 * 没有任何可查的东西：帧被合掉了多少、熔断有没有在挡人、PAC 的每客户端限流有没有
 * 真的拦到谁，在没有指标的时候一律表现为「偶发」，而偶发是查不出原因的。
 *
 * 单行 key=value 而不是多行：logcat 上多行输出会被别的 tag 穿插，抓下来之后
 * 一次采样的几个数字对不到一起，反而不如挤在一行里难看但完整。
 */
internal object RuntimeMetricsLog {

    /** @param pac PAC 没开启时传 null，那一段整个略去，免得一排 0 让人以为它在跑。 */
    fun format(clash: ClashApiMetrics, pac: PacServer.Metrics?): String = buildString {
        append("clash frames=").append(clash.framesReceived)
        append(" coalesced=").append(clash.framesCoalesced)
        append(" dropped=").append(clash.framesDropped)
        append(" parseFail=").append(clash.parseFailures)
        append(" reconnects=").append(clash.streamReconnects)
        append(" rest=").append(clash.restCalls)
        append(" shortCircuited=").append(clash.restShortCircuited)
        append(" breaker=").append(clash.breakerState)
        if (pac == null) return@buildString
        append(" | pac accepted=").append(pac.accepted)
        append(" served=").append(pac.served)
        append(" rejected=").append(pac.rejected)
        append(" clientLimited=").append(pac.clientLimited)
        append(" cacheHits=").append(pac.cacheHits)
        append(" acceptFail=").append(pac.acceptFailures)
        append(" inFlight=").append(pac.inFlight)
        append(" hosts=").append(pac.cachedHosts)
    }
}
