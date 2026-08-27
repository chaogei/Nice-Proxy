package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.config.backup.BackupCrypto
import com.niceproxy.core.config.backup.BackupError
import com.niceproxy.core.config.backup.BackupException
import com.niceproxy.core.config.backup.BackupPayload
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.GroupType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 备份恢复。
 *
 * 恢复是**整体替换**：先把现有数据清空再写入备份内容。清空这一步意味着
 * 任何判断失误都是不可逆的数据丢失，所以「什么时候允许开始清」和
 * 「按什么顺序写回」这两件事必须钉死。
 */
internal class BackupRepositoryTest {

    private val groupDao = FakeServerGroupDao()
    private val serverDao = FakeServerDao(groupDao)
    private val inboundDao = FakeInboundDao()
    private val routingDao = FakeRoutingDao()
    private val transactions = FakeTransactionRunner(
        listOf(groupDao, serverDao, inboundDao, routingDao),
    )
    private val repository = BackupRepository(
        inboundDao, serverDao, groupDao, routingDao, transactions, Dispatchers.Unconfined,
    )

    private companion object {
        val PASSWORD: CharArray = "correct-horse-battery".toCharArray()
        val WRONG_PASSWORD: CharArray = "wrong".toCharArray()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(payload: BackupPayload): ByteArray =
            BackupCrypto.encrypt(json.encodeToString(BackupPayload.serializer(), payload), PASSWORD)

        /** 一份典型备份：两个分组、三个节点、一个入站、一条规则、一个规则集。 */
        val SAMPLE = BackupPayload(
            createdAt = 1_700_000_000_000,
            inbounds = listOf(inbound("in-1", port = 8080)),
            groups = listOf(group("g1"), group("g2")),
            servers = listOf(
                node("s1", groupId = "g1"),
                node("s2", groupId = "g1", server = "b.example.com"),
                node("s3", groupId = "g2", server = "c.example.com"),
            ),
            rules = listOf(rule("r1")),
            ruleSets = listOf(ruleSet("geosite-cn")),
        )

        /** PBKDF2 是 21 万次迭代，能复用就别重复算。 */
        val SAMPLE_BLOB: ByteArray by lazy { encode(SAMPLE) }

        /** 节点指向一个不在备份里的分组，`upsertAll` 会撞上外键约束。 */
        val ORPHAN_BLOB: ByteArray by lazy {
            encode(
                SAMPLE.copy(
                    groups = listOf(group("g1")),
                    servers = listOf(
                        node("s1", groupId = "g1"),
                        node("s2", groupId = "已经不存在的分组"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `导出再恢复能还原全部内容`() = runTest {
        groupDao.upsert(group("g1").toEntity())
        serverDao.upsert(node("s1").toEntity())
        inboundDao.upsert(inbound("in-1").toEntity())
        routingDao.upsertRule(rule("r1").toEntity())
        routingDao.upsertRuleSet(ruleSet("geosite-cn").toEntity())

        val blob = repository.export(PASSWORD).getOrThrow()
        val summary = repository.restore(blob, PASSWORD).getOrThrow()

        assertThat(summary.groups).isEqualTo(1)
        assertThat(summary.servers).isEqualTo(1)
        assertThat(summary.inbounds).isEqualTo(1)
        assertThat(summary.rules).isEqualTo(1)
        assertThat(serverDao.getAll().map { it.id }).containsExactly("s1")
        assertThat(routingDao.getRuleSets().map { it.tag }).containsExactly("geosite-cn")
    }

    @Test
    fun `分组必须先于节点写入否则外键约束会失败`() = runTest {
        // 备份里的节点归属于备份里的分组，而这些分组在恢复前的库中并不存在。
        // 顺序写反的话，第一条节点就会撞上 servers.group_id 的外键约束。
        val summary = repository.restore(SAMPLE_BLOB, PASSWORD).getOrThrow()

        assertThat(summary.servers).isEqualTo(3)
        assertThat(serverDao.getAll().map { it.groupId }.toSet()).containsExactly("g1", "g2")
    }

    @Test
    fun `恢复是整体替换而不是合并`() = runTest {
        // 合并会留下一堆重复节点和端口冲突的入站，
        // 而用户点「恢复」的预期就是「回到备份时的那个样子」
        groupDao.upsert(group("old-group").toEntity())
        serverDao.upsert(node("old-server", groupId = "old-group").toEntity())
        inboundDao.upsert(inbound("old-inbound", port = 9999).toEntity())
        routingDao.upsertRule(rule("old-rule").toEntity())
        routingDao.upsertRuleSet(ruleSet("geoip-old").toEntity())

        repository.restore(SAMPLE_BLOB, PASSWORD).getOrThrow()

        assertThat(groupDao.getAll().map { it.id }).containsExactly("g1", "g2")
        assertThat(serverDao.getAll().map { it.id }).containsExactly("s1", "s2", "s3")
        assertThat(inboundDao.getAll().map { it.id }).containsExactly("in-1")
        assertThat(routingDao.getRules().map { it.id }).containsExactly("r1")
        // 规则集之前没有被清掉，恢复出来的状态既不是备份时的也不是恢复前的
        assertThat(routingDao.getRuleSets().map { it.tag }).containsExactly("geosite-cn")
    }

    @Test
    fun `密码错误时不动数据库`() = runTest {
        groupDao.upsert(group("keep").toEntity())
        serverDao.upsert(node("keep-server", groupId = "keep").toEntity())

        val error = repository.restore(SAMPLE_BLOB, WRONG_PASSWORD).exceptionOrNull()

        assertThat((error as BackupException).error).isEqualTo(BackupError.WrongPassword)
        assertThat(serverDao.getAll().map { it.id }).containsExactly("keep-server")
    }

    @Test
    fun `备份版本高于当前应用时拒绝恢复`() = runTest {
        // 高版本备份里可能有本版本读不懂的字段，先清空再发现读不懂就晚了
        groupDao.upsert(group("keep").toEntity())
        val future = encode(SAMPLE.copy(version = BackupPayload.CURRENT_VERSION + 1))

        val error = repository.restore(future, PASSWORD).exceptionOrNull()

        assertThat((error as BackupException).error)
            .isInstanceOf(BackupError.UnsupportedVersion::class.java)
        assertThat(groupDao.getAll().map { it.id }).containsExactly("keep")
    }

    @Test
    fun `不是备份文件时明确报错`() = runTest {
        val error = repository.restore("这只是一段普通文本".toByteArray(), PASSWORD).exceptionOrNull()
        assertThat((error as BackupException).error).isEqualTo(BackupError.NotABackup)
    }

    /**
     * 恢复途中失败。
     *
     * 这是整个数据层唯一能一次性清空用户全部数据的路径：删除先于插入，
     * 而删分组会顺着外键 CASCADE 带走全部节点。备份又恰恰是其他所有故障的
     * 逃生舱 —— 它自己不能是最大的那个坑。
     */
    @Nested
    @DisplayName("中途失败")
    inner class MidwayFailure {

        @Test
        @DisplayName("插入失败时数据库回到恢复前的样子，一条都不少")
        fun rollsBackEverything() = runTest {
            groupDao.upsert(group("keep").toEntity())
            serverDao.upsert(node("keep-server", groupId = "keep").toEntity())
            inboundDao.upsert(inbound("keep-inbound", port = 9999).toEntity())
            routingDao.upsertRule(rule("keep-rule").toEntity())
            routingDao.upsertRuleSet(ruleSet("geoip-keep").toEntity())

            val result = repository.restore(ORPHAN_BLOB, PASSWORD)

            assertThat(result.isFailure).isTrue()
            assertThat(groupDao.getAll().map { it.id }).containsExactly("keep")
            assertThat(serverDao.getAll().map { it.id }).containsExactly("keep-server")
            assertThat(inboundDao.getAll().map { it.id }).containsExactly("keep-inbound")
            assertThat(routingDao.getRules().map { it.id }).containsExactly("keep-rule")
            assertThat(routingDao.getRuleSets().map { it.tag }).containsExactly("geoip-keep")
        }

        @Test
        @DisplayName("失败被包成 Result 返回，不会炸到调用方")
        fun failureIsReported() = runTest {
            assertThat(repository.restore(ORPHAN_BLOB, PASSWORD).isFailure).isTrue()
        }

        @Test
        @DisplayName("整段删除加插入只开一个事务")
        fun singleTransaction() = runTest {
            // 分成几个事务等于没有事务：第一个提交完第二个失败，
            // 用户手上就是一份删了一半的数据
            repository.restore(SAMPLE_BLOB, PASSWORD).getOrThrow()

            assertThat(transactions.transactionCount).isEqualTo(1)
        }
    }

    /**
     * 导出时的取舍：宁可少导出，也不能导出坏数据。
     *
     * 但「少导出」必须是用户知情的 —— 静默剔除会让一次读取失误变成永久丢失。
     */
    @Nested
    @DisplayName("导出筛选")
    inner class ExportFiltering {

        @Test
        @DisplayName("凭据解不开的节点不写进备份")
        fun skipsUnreadableCredentials() = runTest {
            // 这类节点 toDomain() 出来密码是空占位符。照原样导出，用户当时看不出异常，
            // 等换机恢复才发现节点连不上 —— 而那时库里原本还在的密文早没了。
            groupDao.upsert(group("g1").toEntity())
            serverDao.upsert(node("good").toEntity())
            serverDao.upsert(
                node("broken").toEntity().copy(paramsJson = SecretText.Unreadable("nsec1:dead")),
            )

            val blob = repository.export(PASSWORD).getOrThrow()
            repository.restore(blob, PASSWORD).getOrThrow()

            assertThat(serverDao.getAll().map { it.id }).containsExactly("good")
        }

        @Test
        @DisplayName("TLS 读不出来的节点也不写进备份")
        fun skipsUndecodableTls() = runTest {
            // 密文是好的，是 JSON 读不懂了。降级后的领域对象里 tls 是顶上去的
            // 占位值，导出等于把一次序列化失误固化成永久损坏。
            groupDao.upsert(group("g1").toEntity())
            serverDao.upsert(node("good").toEntity())
            serverDao.upsert(node("broken").toEntity().copy(tlsJson = "{ 这不是 JSON"))

            val blob = repository.export(PASSWORD).getOrThrow()
            repository.restore(blob, PASSWORD).getOrThrow()

            assertThat(serverDao.getAll().map { it.id }).containsExactly("good")
        }

        @Test
        @DisplayName("订阅地址解不开的分组不写进备份")
        fun skipsUnreadableSubscriptionUrl() = runTest {
            // 导出一个没有 url 的订阅组，恢复之后它会被判定成「不是订阅」，
            // 用户永远刷不了新，而 token 恰恰是最不可能靠记忆重建的东西
            groupDao.upsert(subscription("ok", "https://a.example.com/sub?token=1").toEntity())
            groupDao.upsert(
                subscription("lost", "https://b.example.com/sub?token=2").toEntity()
                    .copy(url = SecretText.Unreadable("nsec1:dead")),
            )

            val blob = repository.export(PASSWORD).getOrThrow()
            repository.restore(blob, PASSWORD).getOrThrow()

            assertThat(groupDao.getAll().map { it.id }).containsExactly("ok")
        }

        @Test
        @DisplayName("分组被跳过时组内节点一起跳过，否则恢复会整体失败")
        fun skipsOrphanedServers() = runTest {
            // 留下孤儿节点，恢复时第一条就撞上外键约束；而恢复现在是一个事务，
            // 那等于这份备份彻底恢复不了
            groupDao.upsert(subscription("ok", "https://a.example.com/sub").toEntity())
            groupDao.upsert(
                subscription("lost", "https://b.example.com/sub").toEntity()
                    .copy(url = SecretText.Unreadable("nsec1:dead")),
            )
            serverDao.upsert(node("kept", groupId = "ok").toEntity())
            serverDao.upsert(node("orphan", groupId = "lost").toEntity())

            val blob = repository.export(PASSWORD).getOrThrow()

            assertThat(repository.restore(blob, PASSWORD).isSuccess).isTrue()
            assertThat(serverDao.getAll().map { it.id }).containsExactly("kept")
        }

        @Test
        @DisplayName("导出前能问出会跳过多少条")
        fun skipsAreVisibleBeforeExport() = runTest {
            groupDao.upsert(group("g1").toEntity())
            groupDao.upsert(
                subscription("lost", "https://b.example.com/sub").toEntity()
                    .copy(url = SecretText.Unreadable("nsec1:dead")),
            )
            serverDao.upsert(node("good").toEntity())
            serverDao.upsert(node("orphan", groupId = "lost").toEntity())
            inboundDao.upsert(
                inbound("in-1").toEntity().copy(authPassword = SecretText.Unreadable("nsec1:x")),
            )

            val skips = repository.inspectExport()

            assertThat(skips.groups).isEqualTo(1)
            assertThat(skips.servers).isEqualTo(1)
            assertThat(skips.inbounds).isEqualTo(1)
            assertThat(skips.isEmpty).isFalse()
        }

        @Test
        @DisplayName("一切正常时不报跳过，UI 才不会天天弹无谓的警告")
        fun nothingSkippedWhenHealthy() = runTest {
            groupDao.upsert(group("g1").toEntity())
            serverDao.upsert(node("s1").toEntity())
            inboundDao.upsert(inbound("in-1").toEntity())

            assertThat(repository.inspectExport().isEmpty).isTrue()
        }
    }

    private fun subscription(id: String, url: String) =
        group(id, type = GroupType.SUBSCRIPTION, url = url)
}
