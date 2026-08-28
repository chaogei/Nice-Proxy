package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.network.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * 订阅更新管道，从 HTTP 响应一路走到落库。
 *
 * 用真的 [SubscriptionFetcher] 对着本地的 MockWebServer 跑，而不是做一个
 * 替身：那个类在 `:core:network` 里是 final 的（本次改动不碰那个模块），
 * 而它本身是纯 JVM 的 OkHttp，接本地服务器反而比替身更接近真实。
 *
 * 这里要钉的是**部分失败不能留下半截状态**。管道有三段会各自失败
 * （网络、解析、落库），而落库这一段涉及两张表：分组和节点，中间还隔着一条
 * `servers.group_id` 的外键。
 */
internal class SubscriptionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var groupDao: FakeServerGroupDao
    private lateinit var serverDao: FakeServerDao
    private lateinit var serverRepository: ServerRepository
    private lateinit var repository: SubscriptionRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply {
            // 拉取失败会按退避重试（见 SubscriptionFetcher.RetryPolicy），所以一条
            // 「服务端返回 500」的用例发出的请求比排进去的响应多。队列空了默认是把
            // 连接挂住，于是重试要等满 callTimeout —— 这里的用例只关心「这次刷新失败
            // 了」，让空队列立刻回 503 即可，重试拿到的仍是可重试的失败。
            dispatcher = QueueDispatcher().apply {
                setFailFast(MockResponse.Builder().code(503).build())
            }
            start()
        }
        groupDao = FakeServerGroupDao()
        serverDao = FakeServerDao(groupDao)
        serverRepository = ServerRepository(
            serverDao,
            groupDao,
            FakeTransactionRunner(listOf(groupDao, serverDao)),
            Dispatchers.Unconfined,
        )
        repository = SubscriptionRepository(
            SubscriptionFetcher(Dispatchers.Unconfined),
            serverRepository,
            Dispatchers.Unconfined,
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun enqueueNodes(vararg links: String, userInfo: String? = null) {
        val body = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())
        server.enqueue(
            MockResponse.Builder()
                .body(body)
                .apply { userInfo?.let { setHeader("subscription-userinfo", it) } }
                .build(),
        )
    }

    private fun url() = server.url("/sub?token=secret").toString()

    @Nested
    @DisplayName("新增订阅")
    inner class Add {

        @Test
        @DisplayName("分组和节点一起落库")
        fun writesGroupAndNodes() = runTest {
            // 分组是刚在内存里造出来的，落库时还不存在。先写节点的话，
            // 第一条就撞上 servers.group_id 的外键约束 —— 而那个异常
            // 不在任何 runCatching 里，会直接抛给调用方
            enqueueNodes("trojan://pw@a.com:443#A", "trojan://pw2@b.com:443#B")

            val outcome = repository.addSubscription(url(), name = "机场").getOrThrow()

            assertThat(outcome.nodeCount).isEqualTo(2)
            val group = groupDao.getAll().single()
            assertThat(group.type).isEqualTo(GroupType.SUBSCRIPTION)
            assertThat(serverDao.getAll().map { it.groupId }).containsExactly(group.id, group.id)
        }

        @Test
        @DisplayName("拉取失败时一个分组都不留")
        fun failedPullLeavesNothing() = runTest {
            // 留下一个空分组的话，用户看到的是一个永远刷不出节点的订阅
            server.enqueue(MockResponse.Builder().code(403).build())

            assertThat(repository.addSubscription(url()).isFailure).isTrue()
            assertThat(groupDao.getAll()).isEmpty()
            assertThat(serverDao.getAll()).isEmpty()
        }

        @Test
        @DisplayName("内容解析不出来时同样什么都不留")
        fun unparsableBodyLeavesNothing() = runTest {
            server.enqueue(MockResponse.Builder().body("这不是订阅").build())

            assertThat(repository.addSubscription(url()).isFailure).isTrue()
            assertThat(groupDao.getAll()).isEmpty()
        }

        @Test
        @DisplayName("过滤规则把节点滤空时不落库")
        fun filteredToEmptyLeavesNothing() = runTest {
            // 和「机场返回了空订阅」是两回事，但对落库来说结论一样：
            // 不能留下一个空分组
            enqueueNodes("trojan://pw@a.com:443#A")

            val result = repository.addSubscription(url(), remarksFilter = "A")

            assertThat(result.isFailure).isTrue()
            assertThat(groupDao.getAll()).isEmpty()
        }

        @Test
        @DisplayName("自定义请求头不是合法 JSON 时连请求都不发")
        fun invalidHeadersFailFast() = runTest {
            val result = repository.addSubscription(url(), extraHeaders = "{ 这不是 JSON")

            assertThat(result.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(groupDao.getAll()).isEmpty()
        }
    }

    @Nested
    @DisplayName("刷新订阅")
    inner class Refresh {

        private suspend fun seedSubscription(): String {
            enqueueNodes("trojan://pw@old.com:443#Old")
            return repository.addSubscription(url()).let { groupDao.getAll().single().id }
        }

        @Test
        @DisplayName("整组替换，节点和元数据一起更新")
        fun replacesGroupAtomically() = runTest {
            val groupId = seedSubscription()
            enqueueNodes("trojan://pw@new.com:443#New", userInfo = "upload=1; download=2; total=3")

            repository.refresh(groupId).getOrThrow()

            assertThat(serverDao.getAll().map { it.name }).containsExactly("New")
            val group = groupDao.getById(groupId)!!
            assertThat(group.lastUpdateAt).isNotNull()
            assertThat(group.lastError).isNull()
        }

        @Test
        @DisplayName("刷新失败时保留原有节点，只写 lastError")
        fun keepsNodesOnFailure() = runTest {
            // 订阅服务器临时不可用时，用户至少还能连上
            val groupId = seedSubscription()
            server.enqueue(MockResponse.Builder().code(500).build())

            assertThat(repository.refresh(groupId).isFailure).isTrue()

            assertThat(serverDao.getAll().map { it.name }).containsExactly("Old")
            assertThat(groupDao.getById(groupId)!!.lastError).isNotEmpty()
        }

        @Test
        @DisplayName("上一次的失败信息在下一次成功后被清掉")
        fun clearsStaleError() = runTest {
            val groupId = seedSubscription()
            server.enqueue(MockResponse.Builder().code(500).build())
            repository.refresh(groupId)

            enqueueNodes("trojan://pw@new.com:443#New")
            repository.refresh(groupId).getOrThrow()

            assertThat(groupDao.getById(groupId)!!.lastError).isNull()
        }

        @Test
        @DisplayName("落库中途失败时分组元数据不会单独往前跑")
        fun partialWriteRollsBack() = runTest {
            // 节点写成功、分组元数据写失败（或反过来），用户会看到一批新节点
            // 配着一个「上次更新：三天前」的分组，下一次自动更新又会把它重拉一遍
            val groupId = seedSubscription()
            val before = groupDao.getById(groupId)!!
            enqueueNodes("trojan://pw@new.com:443#New")
            serverDao.failOnUpsert = { error("磁盘写满") }

            assertThat(repository.refresh(groupId).isFailure).isTrue()

            assertThat(serverDao.getAll().map { it.name }).containsExactly("Old")
            assertThat(groupDao.getById(groupId)!!.lastUpdateAt).isEqualTo(before.lastUpdateAt)
        }

        @Test
        @DisplayName("分组不存在时明确报错")
        fun unknownGroup() = runTest {
            assertThat(repository.refresh("不存在").isFailure).isTrue()
        }

        @Test
        @DisplayName("手动分组不是订阅，拒绝刷新")
        fun manualGroupIsNotASubscription() = runTest {
            groupDao.upsert(group("manual").toEntity())

            assertThat(repository.refresh("manual").isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }
}
