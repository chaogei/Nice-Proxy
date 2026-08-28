package com.niceproxy.core.service

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.network.clash.ClashApiMetrics
import com.niceproxy.core.network.concurrent.CircuitBreaker
import com.niceproxy.core.service.pac.PacServer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RuntimeMetricsLogTest {

    @Test
    @DisplayName("过载与熔断的痕迹都出现在这一行里")
    fun carriesTheNumbersWorthChasing() {
        val line = RuntimeMetricsLog.format(clash(), pac())

        // 这几个数字是「偶尔卡一下」「有些设备偶尔上不了网」这类反馈唯一的抓手
        assertThat(line).contains("dropped=3")
        assertThat(line).contains("shortCircuited=9")
        assertThat(line).contains("breaker=OPEN")
        assertThat(line).contains("clientLimited=4")
        assertThat(line).contains("rejected=2")
    }

    @Test
    @DisplayName("PAC 没开就整段不打，免得一排 0 让人以为它在跑")
    fun omitsPacWhenNotRunning() {
        val line = RuntimeMetricsLog.format(clash(), pac = null)

        assertThat(line).doesNotContain("pac")
        assertThat(line).contains("breaker=OPEN")
    }

    @Test
    @DisplayName("单行输出：logcat 上多行会被别的 tag 穿插，抓下来对不到一起")
    fun staysOnOneLine() {
        assertThat(RuntimeMetricsLog.format(clash(), pac())).doesNotContain("\n")
    }

    private fun clash() = ClashApiMetrics(
        framesReceived = 100,
        framesCoalesced = 7,
        framesDropped = 3,
        parseFailures = 1,
        streamReconnects = 5,
        restCalls = 42,
        restShortCircuited = 9,
        breakerState = CircuitBreaker.State.OPEN,
    )

    private fun pac() = PacServer.Metrics(
        accepted = 20,
        served = 18,
        rejected = 2,
        clientLimited = 4,
        cacheHits = 15,
        acceptFailures = 0,
        inFlight = 1,
        cachedHosts = 2,
    )
}
