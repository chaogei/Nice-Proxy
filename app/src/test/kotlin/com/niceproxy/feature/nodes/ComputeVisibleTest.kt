package com.niceproxy.feature.nodes

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.ServerProfile
import org.junit.jupiter.api.Test

class ComputeVisibleTest {

    @Test
    fun `no group filter shows everything`() {
        val all = listOf(node("a", group = "g1"), node("b", group = "g2"))

        assertThat(visible(all, groupId = null)).hasSize(2)
    }

    @Test
    fun `group filter keeps only that group`() {
        val all = listOf(node("a", group = "g1"), node("b", group = "g2"))

        assertThat(visible(all, groupId = "g2").map { it.name }).containsExactly("b")
    }

    @Test
    fun `search matches name or address, case insensitively`() {
        val all = listOf(
            node("Tokyo", server = "1.1.1.1"),
            node("Osaka", server = "tokyo.example.com"),
            node("Seoul", server = "2.2.2.2"),
        )

        assertThat(visible(all, query = "TOKYO").map { it.name })
            .containsExactly("Tokyo", "Osaka")
    }

    @Test
    fun `a blank query filters nothing`() {
        val all = listOf(node("a"), node("b"))

        assertThat(visible(all, query = "   ")).hasSize(2)
    }

    @Test
    fun `default sort preserves the incoming order`() {
        val all = listOf(node("c"), node("a"), node("b"))

        assertThat(visible(all, sort = NodeSort.DEFAULT).map { it.name })
            .containsExactly("c", "a", "b")
            .inOrder()
    }

    @Test
    fun `name sort is alphabetical`() {
        val all = listOf(node("c"), node("a"), node("b"))

        assertThat(visible(all, sort = NodeSort.NAME).map { it.name })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    /**
     * 延迟排序里有两个特殊值，顺序不能颠倒：超时的节点至少证明「测过、没通」，
     * 而没测过的什么都没证明。把未测速的排在超时之后，用户从上往下试才是
     * 从「最可能能用」走到「最不可能」。
     */
    @Test
    fun `latency sort puts timeouts after real results and untested last`() {
        val all = listOf(
            node("untested", latency = null),
            node("timeout", latency = ServerProfile.LATENCY_TIMEOUT),
            node("slow", latency = 800),
            node("fast", latency = 40),
        )

        assertThat(visible(all, sort = NodeSort.LATENCY).map { it.name })
            .containsExactly("fast", "slow", "timeout", "untested")
            .inOrder()
    }

    @Test
    fun `filtering runs before sorting`() {
        val all = listOf(
            node("beta", group = "g1", latency = 10),
            node("alpha", group = "g2", latency = 5),
        )

        assertThat(visible(all, groupId = "g1", sort = NodeSort.LATENCY).map { it.name })
            .containsExactly("beta")
    }

    private fun visible(
        servers: List<ServerProfile>,
        groupId: String? = null,
        query: String = "",
        sort: NodeSort = NodeSort.DEFAULT,
    ) = computeVisible(servers, groupId, query, sort)

    private fun node(
        name: String,
        group: String = "g1",
        server: String = "example.com",
        latency: Int? = null,
    ) = ServerProfile(
        id = name,
        groupId = group,
        name = name,
        protocol = ProxyProtocol.DIRECT,
        server = server,
        serverPort = 443,
        params = ProtocolParams.Direct,
        latencyMs = latency,
    )
}
