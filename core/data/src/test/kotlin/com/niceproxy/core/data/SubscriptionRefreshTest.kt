package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.network.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 订阅更新管道的接线测试。
 *
 * 这里刻意用真实的 [SubscriptionFetcher] 打一个真实的 HTTP 服务端，而不是给
 * fetcher 做替身：要证明的正是「批量更新到底有没有并发发出去」，而那件事只在
 * 真实的 socket 上才看得见 —— 换成假的，剩下的就只是在测我自己写的那行调用。
 */
internal class SubscriptionRefreshTest {

    private val groupDao = FakeServerGroupDao()
    private val serverDao = FakeServerDao(groupDao)
    private val serverRepository = ServerRepository(
        serverDao,
        groupDao,
        FakeTransactionRunner(listOf(groupDao, serverDao)),
        Dispatchers.IO,
    )
    private val fetcher = SubscriptionFetcher(Dispatchers.IO)
    private val repository = SubscriptionRepository(fetcher, serverRepository, Dispatchers.IO)

    private val server = MockWebServer()

    /** 同一时刻真的压在服务端上的请求数，以及它的峰值。 */
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Nested
    @DisplayName("批量更新")
    inner class Batch {

        @Test
        @DisplayName("多条订阅同时在飞，而不是一条接一条")
        fun fetchesConcurrently(): Unit = runBlocking {
            // 串行的话，一次「全部更新」的耗时是所有订阅往返时间之和 —— 二十条订阅
            // 每条两秒就是四十秒，而这四十秒里本机几乎全闲着，用户正盯着进度条
            respondSlowly()
            val ids = (1..8).map { seedSubscription("g$it") }

            val results = repository.refresh(ids)

            assertThat(results.all { it.isSuccess }).isTrue()
            assertThat(peakInFlight.get()).isGreaterThan(1)
        }

        @Test
        @DisplayName("并发有上限：机场普遍按 IP 限流，打太猛换来的是一串 429")
        fun respectsFetcherConcurrencyCap(): Unit = runBlocking {
            respondSlowly()
            val ids = (1..12).map { seedSubscription("g$it") }

            repository.refresh(ids)

            assertThat(peakInFlight.get()).isAtMost(SubscriptionFetcher.DEFAULT_CONCURRENCY)
        }

        @Test
        @DisplayName("每条订阅的节点各自落到自己的分组里")
        fun storesNodesPerGroup(): Unit = runBlocking {
            respondWithOneNodePerGroup()
            val ids = (1..4).map { seedSubscription("g$it") }

            repository.refresh(ids)

            ids.forEach { id ->
                val nodes = serverDao.getAll().filter { it.groupId == id }
                assertThat(nodes.map { it.name }).containsExactly("node-$id")
            }
        }

        @Test
        @DisplayName("一条失败不拖累其余，且结果与入参顺序一一对应")
        fun isolatesFailures(): Unit = runBlocking {
            respondWithOneNodePerGroup(failing = setOf("g2"))
            val ids = (1..3).map { seedSubscription("g$it") }

            val results = repository.refresh(ids)

            assertThat(results.map { it.isSuccess }).containsExactly(true, false, true).inOrder()
            // 保留旧节点、只把原因写在分组上：订阅服务器临时不可用时，
            // 用户至少还能连上上一次拉到的节点
            assertThat(serverRepository.getGroup("g2")?.lastError).isNotNull()
            assertThat(serverRepository.getGroup("g1")?.lastError).isNull()
        }

        @Test
        @DisplayName("发不出去的分组不占并发名额，也不打乱后面那些的对位")
        fun keepsAlignmentAroundUnfetchableGroups(): Unit = runBlocking {
            respondWithOneNodePerGroup()
            val first = seedSubscription("g1")
            val manual = "manual"
            serverRepository.saveGroup(group(manual, type = GroupType.MANUAL))
            val last = seedSubscription("g2")

            val results = repository.refresh(listOf(first, manual, "不存在", last))

            assertThat(results.map { it.isSuccess })
                .containsExactly(true, false, false, true).inOrder()
            assertThat(results[0].getOrNull()?.nodeCount).isEqualTo(1)
            assertThat(results[3].getOrNull()?.nodeCount).isEqualTo(1)
            // 手动分组被误传进来时不该挂上一条订阅错误：那个红点用户既看不懂也清不掉
            assertThat(serverRepository.getGroup(manual)?.lastError).isNull()
        }

        @Test
        @DisplayName("refreshAll 只挑订阅分组，手动分组不参与")
        fun refreshAllSkipsManualGroups(): Unit = runBlocking {
            respondWithOneNodePerGroup()
            seedSubscription("g1")
            serverRepository.saveGroup(group("manual", type = GroupType.MANUAL))

            val results = repository.refreshAll()

            assertThat(results).hasSize(1)
            assertThat(results.single().isSuccess).isTrue()
        }

        @Test
        @DisplayName("没有订阅时不发请求")
        fun handlesEmptyBatch(): Unit = runBlocking {
            assertThat(repository.refreshAll()).isEmpty()
        }
    }

    // ---------------------------------------------------------------- 测试工具

    /** 建一个指向本地服务端的订阅分组，返回它的 id。 */
    private suspend fun seedSubscription(id: String): String {
        serverRepository.saveGroup(
            group(id, type = GroupType.SUBSCRIPTION, url = server.url("/sub/$id").toString()),
        )
        return id
    }

    /** 每条请求都压住一会儿，让「同时在飞的有几条」这件事可观测。 */
    private fun respondSlowly() = respond {
        MockResponse.Builder().body("trojan://pw@a.com:443#node").build()
    }

    private fun respondWithOneNodePerGroup(failing: Set<String> = emptySet()) = respond { request ->
        val groupId = request.url.pathSegments.last()
        if (groupId in failing) {
            MockResponse.Builder().code(404).build()
        } else {
            MockResponse.Builder().body("trojan://pw@a.com:443#node-$groupId").build()
        }
    }

    private fun respond(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val now = inFlight.incrementAndGet()
                peakInFlight.updateAndGet { seen -> maxOf(seen, now) }
                try {
                    Thread.sleep(HOLD_MS)
                    return handler(request)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
    }

    private companion object {
        /**
         * 每条请求压住多久。
         *
         * 要明显长于本机回环的往返时间，否则并发窗口太窄，「同时在飞」这件事
         * 会因为请求各自太快而观测不到 —— 那样这个测试就成了一个偶尔通过的摆设。
         */
        const val HOLD_MS = 120L
    }
}
