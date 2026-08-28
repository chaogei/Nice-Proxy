package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 节点批量操作的判断逻辑。
 *
 * 这两个操作（删除重复、删除无效）都是**不可逆的批量删除**，
 * 判断标准差一点点，用户丢掉的就是刚导入还没来得及测的一整个订阅。
 */
internal class ServerRepositoryTest {

    private val groupDao = FakeServerGroupDao()
    private val serverDao = FakeServerDao(groupDao)
    private val transactions = FakeTransactionRunner(listOf(groupDao, serverDao))
    private val repository =
        ServerRepository(serverDao, groupDao, transactions, Dispatchers.Unconfined)

    private suspend fun seed(vararg groupIds: String, nodes: List<ServerProfile>) {
        groupIds.forEach { groupDao.upsert(group(it).toEntity()) }
        serverDao.upsertAll(nodes.map { it.toEntity() })
    }

    @Nested
    @DisplayName("删除重复")
    inner class Duplicates {

        @Test
        fun `判重看协议地址端口与参数而不是节点名`() = runTest {
            // 同一个物理节点出现在两份订阅里时，名字往往完全不同
            // （「香港 01」和「HK-01」），按名字判重会一个都删不掉
            seed(
                "g1",
                nodes = listOf(
                    node("a", name = "香港 01", createdAt = 100),
                    node("b", name = "HK-01", createdAt = 200),
                    node("c", name = "日本", server = "b.example.com", createdAt = 300),
                ),
            )

            assertThat(repository.deleteDuplicates()).isEqualTo(1)
            assertThat(repository.getAll().map { it.id }).containsExactly("a", "c")
        }

        @Test
        fun `同名但参数不同的是两个真节点`() = runTest {
            // 反过来也要成立：机场经常给两个不同端口的节点起同一个名字
            seed(
                "g1",
                nodes = listOf(
                    node("a", name = "香港", port = 443, createdAt = 100),
                    node("b", name = "香港", port = 8443, createdAt = 200),
                    node("c", name = "香港", password = "另一个密码", createdAt = 300),
                ),
            )

            assertThat(repository.deleteDuplicates()).isEqualTo(0)
            assertThat(repository.getAll()).hasSize(3)
        }

        @Test
        fun `每组保留创建时间最早的那条`() = runTest {
            seed(
                "g1",
                nodes = listOf(
                    node("late", createdAt = 300),
                    node("early", createdAt = 100),
                    node("middle", createdAt = 200),
                ),
            )

            repository.deleteDuplicates()
            assertThat(repository.getAll().map { it.id }).containsExactly("early")
        }

        @Test
        fun `创建时间完全相同时也只留一条`() = runTest {
            // 同一次批量导入进来的重复节点，createdAt 会一模一样。
            // 不要求留下哪一条，但绝不能因为比不出先后而两条都留着。
            seed("g1", nodes = listOf(node("a", createdAt = 100), node("b", createdAt = 100)))

            assertThat(repository.deleteDuplicates()).isEqualTo(1)
            assertThat(repository.getAll()).hasSize(1)
        }

        @Test
        fun `限定分组时不牵连其他分组的同名节点`() = runTest {
            // 在「订阅 A」里点删除重复，不应该动到「订阅 B」
            seed(
                "g1", "g2",
                nodes = listOf(
                    node("a", groupId = "g1", createdAt = 100),
                    node("b", groupId = "g1", createdAt = 200),
                    node("c", groupId = "g2", createdAt = 300),
                ),
            )

            assertThat(repository.deleteDuplicates("g2")).isEqualTo(0)
            assertThat(repository.deleteDuplicates("g1")).isEqualTo(1)
            assertThat(repository.getAll().map { it.id }).containsExactly("a", "c")
        }
    }

    @Nested
    @DisplayName("删除无效")
    inner class Invalid {

        @Test
        fun `绝不删除从未测速的节点`() = runTest {
            // latency 为 null 表示「还没测过」，不是「测过而且不通」。
            // 把它当无效删掉，等于用户刚导入订阅点一下就全没了。
            seed(
                "g1",
                nodes = listOf(
                    node("never", server = "n.com", latencyMs = null),
                    node("timeout", server = "t.com", latencyMs = ServerProfile.LATENCY_TIMEOUT),
                    node("fast", server = "f.com", latencyMs = 42),
                ),
            )

            assertThat(repository.deleteInvalid()).isEqualTo(1)
            assertThat(repository.getAll().map { it.id }).containsExactly("never", "fast")
        }

        @Test
        fun `清空测速结果之后不再有节点被判为无效`() = runTest {
            seed("g1", nodes = listOf(node("timeout", latencyMs = ServerProfile.LATENCY_TIMEOUT)))

            repository.clearAllLatency()
            assertThat(repository.deleteInvalid()).isEqualTo(0)
        }

        @Test
        fun `限定分组时不牵连其他分组`() = runTest {
            seed(
                "g1", "g2",
                nodes = listOf(
                    node("a", groupId = "g1", server = "a.com", latencyMs = ServerProfile.LATENCY_TIMEOUT),
                    node("b", groupId = "g2", server = "b.com", latencyMs = ServerProfile.LATENCY_TIMEOUT),
                ),
            )

            assertThat(repository.deleteInvalid("g1")).isEqualTo(1)
            assertThat(repository.getAll().map { it.id }).containsExactly("b")
        }
    }

    @Nested
    @DisplayName("分组与导入")
    inner class GroupsAndImport {

        @Test
        fun `已有手动分组时不再新建默认分组`() = runTest {
            val first = repository.ensureDefaultGroup()
            val second = repository.ensureDefaultGroup()

            assertThat(second).isEqualTo(first)
            assertThat(repository.getGroups()).hasSize(1)
        }

        @Test
        fun `剪贴板导入会先把默认分组建出来`() = runTest {
            // 节点表对 group_id 有外键，先插节点会直接违反约束；
            // 更重要的是不能要求用户「先去建个分组」再导入
            val outcome = repository.importFromText("trojan://pw@a.com:443#A\n这不是链接")

            assertThat(outcome.imported).isEqualTo(1)
            assertThat(outcome.failed).isEqualTo(1)
            assertThat(repository.getAll().single().groupId)
                .isEqualTo(repository.getGroups().single().id)
        }

        @Test
        fun `订阅更新整组替换且不影响其他分组`() = runTest {
            seed(
                "g1", "g2",
                nodes = listOf(node("old", groupId = "g1"), node("keep", groupId = "g2")),
            )

            repository.replaceGroupServers("g1", listOf(node("new", groupId = "g1")))

            assertThat(repository.getAll().map { it.id }).containsExactly("new", "keep")
        }

        @Test
        fun `删除分组会连带删掉组内节点`() = runTest {
            seed("g1", nodes = listOf(node("a"), node("b", server = "b.com")))

            repository.deleteGroup("g1")

            assertThat(repository.getAll()).isEmpty()
        }

        @Test
        @DisplayName("分组还不存在时也能一次把它和节点写进去")
        fun `新分组与节点一次写入`() = runTest {
            // 新增订阅走的就是这条路：分组是刚在内存里造出来的，
            // 先写节点必然撞上 servers.group_id 的外键约束
            repository.saveGroupWithServers(
                group("new-group"),
                listOf(node("n1", groupId = "new-group")),
            )

            assertThat(repository.getGroups().map { it.id }).containsExactly("new-group")
            assertThat(repository.getAll().map { it.id }).containsExactly("n1")
        }

        @Test
        @DisplayName("分组与节点在同一个事务里，节点写失败不会留下半个分组")
        fun `分组与节点同事务`() = runTest {
            seed("g1", nodes = listOf(node("old", groupId = "g1")))
            serverDao.failOnUpsert = { error("磁盘写满") }

            val failure = runCatching {
                repository.saveGroupWithServers(
                    group("g1", name = "改过的名字"),
                    listOf(node("new", groupId = "g1")),
                )
            }

            assertThat(failure.isFailure).isTrue()
            assertThat(groupDao.getById("g1")!!.name).isEqualTo("g1")
            assertThat(repository.getAll().map { it.id }).containsExactly("old")
        }
    }

    /**
     * 批量删除的写放大。
     *
     * 逐条 `deleteById` 在 SQLite 里是逐条事务：一次判重能删掉几百条，
     * 那就是几百次 fsync，而中途被系统杀掉会留下一个删了一半的列表。
     */
    @Nested
    @DisplayName("批量删除")
    inner class BatchDeletes {

        @Test
        @DisplayName("删除几百条只发出个位数的语句")
        fun `批量删除不逐条发语句`() = runTest {
            groupDao.upsert(group("g1").toEntity())
            // 全都是同一个物理节点的重复项，除第一条外都会被删
            serverDao.upsertAll(
                (0 until 600).map { node("dup-$it", createdAt = it.toLong()).toEntity() },
            )

            assertThat(repository.deleteDuplicates()).isEqualTo(599)

            // 599 条分两块（DELETE_CHUNK = 500），也就是两条语句
            assertThat(serverDao.deleteCount).isEqualTo(2)
            assertThat(repository.getAll()).hasSize(1)
        }

        @Test
        @DisplayName("一条都不用删时不发语句")
        fun `没有可删的就不动数据库`() = runTest {
            seed("g1", nodes = listOf(node("only")))

            assertThat(repository.deleteDuplicates()).isEqualTo(0)
            assertThat(serverDao.deleteCount).isEqualTo(0)
        }
    }
}
