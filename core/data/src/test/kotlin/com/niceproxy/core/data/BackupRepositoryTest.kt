package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.config.backup.BackupCrypto
import com.niceproxy.core.config.backup.BackupError
import com.niceproxy.core.config.backup.BackupException
import com.niceproxy.core.config.backup.BackupPayload
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.database.entity.TrafficDailyEntity
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
    private val trafficDao = FakeTrafficDao()
    private val transactions = FakeTransactionRunner(
        listOf(groupDao, serverDao, inboundDao, routingDao, trafficDao),
    )
    private val repository = BackupRepository(
        inboundDao,
        serverDao,
        groupDao,
        routingDao,
        trafficDao,
        transactions,
        Dispatchers.Unconfined,
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

        /** 节点指向一个不在备份里的分组 —— 放行的话会撞上外键约束。 */
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

        /** 同一个 id 在备份里出现两次。 */
        val DUPLICATE_BLOB: ByteArray by lazy {
            encode(
                SAMPLE.copy(
                    groups = listOf(group("g1")),
                    servers = listOf(
                        node("s1", groupId = "g1", name = "先来的"),
                        node("s1", groupId = "g1", name = "后来的"),
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
            trafficDao.upsertAll(listOf(trafficRow(20260101, "keep-tag")))
            // 备份本身是好的，是写到一半磁盘满了 —— 规整不掉的那类失败，
            // 也是这个事务唯一还需要兜住的那类
            serverDao.failOnUpsert = { error("磁盘写满") }

            val result = repository.restore(SAMPLE_BLOB, PASSWORD)

            assertThat(result.isFailure).isTrue()
            assertThat(groupDao.getAll().map { it.id }).containsExactly("keep")
            assertThat(serverDao.getAll().map { it.id }).containsExactly("keep-server")
            assertThat(inboundDao.getAll().map { it.id }).containsExactly("keep-inbound")
            assertThat(routingDao.getRules().map { it.id }).containsExactly("keep-rule")
            assertThat(routingDao.getRuleSets().map { it.tag }).containsExactly("geoip-keep")
            assertThat(trafficDao.count()).isEqualTo(1)
        }

        @Test
        @DisplayName("失败被包成 Result 返回，不会炸到调用方")
        fun failureIsReported() = runTest {
            serverDao.failOnUpsert = { error("磁盘写满") }

            assertThat(repository.restore(SAMPLE_BLOB, PASSWORD).isFailure).isTrue()
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
     * 恢复的冲突策略。
     *
     * 一份被手工改过、或者跨版本导出的备份不该让用户「什么都恢复不了」——
     * 清空是不可逆的，所以任何「这份备份有问题」的判断都必须发生在第一条
     * DELETE 之前，而不是靠事务回滚兜底。靠回滚兜住的每一次失败，
     * 对用户来说都是一次「恢复失败」。
     */
    @Nested
    @DisplayName("冲突策略")
    inner class Conflicts {

        @Test
        @DisplayName("孤儿节点被丢掉，其余照常恢复")
        fun orphanedServersAreDropped() = runTest {
            // 放行的话第一条就撞上 servers.group_id 的外键约束，
            // 而恢复是一个事务 —— 一个改坏的分组能连累整份备份
            val summary = repository.restore(ORPHAN_BLOB, PASSWORD).getOrThrow()

            assertThat(serverDao.getAll().map { it.id }).containsExactly("s1")
            assertThat(summary.conflicts.orphanedServers).containsExactly("s2")
        }

        @Test
        @DisplayName("备份内部 id 重复时保留第一条")
        fun duplicateIdsKeepTheFirst() = runTest {
            // 都写进去的话，@Upsert 的行为是后写的盖掉先写的，
            // 于是「保留哪一条」取决于迭代顺序 —— 那不是策略，是抽签
            val summary = repository.restore(DUPLICATE_BLOB, PASSWORD).getOrThrow()

            assertThat(serverDao.getAll().map { it.name }).containsExactly("先来的")
            assertThat(summary.conflicts.duplicateIds).containsExactly("s1")
        }

        @Test
        @DisplayName("被丢掉的条目计入 summary，UI 能如实告诉用户")
        fun conflictsAreReported() = runTest {
            val summary = repository.restore(ORPHAN_BLOB, PASSWORD).getOrThrow()

            assertThat(summary.conflicts.isEmpty).isFalse()
            assertThat(summary.conflicts.total).isEqualTo(1)
        }

        @Test
        @DisplayName("一份健康的备份不报任何冲突")
        fun healthyBackupHasNoConflicts() = runTest {
            val summary = repository.restore(SAMPLE_BLOB, PASSWORD).getOrThrow()

            assertThat(summary.conflicts.isEmpty).isTrue()
        }
    }

    /**
     * 流量统计进备份。
     *
     * 它是这份文件里唯一**可丢弃**的部分：丢了只是图表断一截，而节点凭据
     * 丢了就再也回不来。这个差别决定了它的所有处置方式。
     */
    @Nested
    @DisplayName("流量统计")
    inner class Traffic {

        @Test
        @DisplayName("导出再恢复能还原流量账")
        fun roundTrips() = runTest {
            groupDao.upsert(group("g1").toEntity())
            trafficDao.upsertAll(
                listOf(
                    trafficRow(20260827, "proxy", upload = 100, download = 200),
                    trafficRow(20260828, "direct", upload = 5, download = 6),
                ),
            )

            val blob = repository.export(PASSWORD).getOrThrow()
            trafficDao.deleteAll()
            val summary = repository.restore(blob, PASSWORD).getOrThrow()

            assertThat(summary.trafficRows).isEqualTo(2)
            assertThat(trafficDao.getRange(0, 99991231).map { it.outboundTag to it.upload })
                .containsExactly("proxy" to 100L, "direct" to 5L)
        }

        @Test
        @DisplayName("旧版本导出的备份里没有这个键，照样能恢复")
        fun oldBackupsRestoreFine() = runTest {
            // SAMPLE_BLOB 是直接序列化 BackupPayload 得到的，
            // 也就是这次改动之前的文件格式
            val summary = repository.restore(SAMPLE_BLOB, PASSWORD).getOrThrow()

            assertThat(summary.trafficRows).isEqualTo(0)
            assertThat(summary.servers).isEqualTo(3)
        }

        @Test
        @DisplayName("恢复会先清空本机的流量账，不和备份里的混在一起")
        fun restoreReplacesLocalTraffic() = runTest {
            groupDao.upsert(group("g1").toEntity())
            trafficDao.upsertAll(listOf(trafficRow(20250101, "本机的旧账")))
            val blob = repository.export(PASSWORD).getOrThrow()
            trafficDao.upsertAll(listOf(trafficRow(20260828, "导出之后又记的")))

            repository.restore(blob, PASSWORD).getOrThrow()

            assertThat(trafficDao.getRange(0, 99991231).map { it.outboundTag })
                .containsExactly("本机的旧账")
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

            assertThat(skips.groups).hasSize(1)
            assertThat(skips.servers).hasSize(1)
            assertThat(skips.inbounds).hasSize(1)
            assertThat(skips.isEmpty).isFalse()
        }

        @Test
        @DisplayName("跳过的条目带名字和原因，用户才知道该去重导哪一个")
        fun skipsAreAuditable() = runTest {
            // 一句「跳过了 3 个节点」是没法处置的：用户既不知道跳的是哪几个，
            // 也就无从判断要不要现在就去重新导入
            groupDao.upsert(group("g1").toEntity())
            serverDao.upsert(
                node("broken", name = "香港 01").toEntity()
                    .copy(paramsJson = SecretText.Unreadable("nsec1:dead")),
            )
            serverDao.upsert(node("stale", name = "日本 02").toEntity().copy(tlsJson = "{ 不是 JSON"))

            val skips = repository.inspectExport()

            assertThat(skips.servers.map { it.name })
                .containsExactly("香港 01", "日本 02")
            assertThat(skips.servers.single { it.id == "broken" }.reason)
                .isEqualTo(BackupRepository.SkipReason.UNREADABLE_SECRET)
            // 密文是好的，是里面的 JSON 读不懂了 —— 成因和处置都不一样
            assertThat(skips.servers.single { it.id == "stale" }.reason)
                .isEqualTo(BackupRepository.SkipReason.UNDECODABLE_JSON)
        }

        @Test
        @DisplayName("因为分组被跳过而连坐的节点，原因要如实标出来")
        fun collateralSkipsSayWhy() = runTest {
            // 这个节点自己没毛病，让用户去「重新导入」它是误导 ——
            // 他真正要做的是把订阅链接重新粘一遍
            groupDao.upsert(
                subscription("lost", "https://b.example.com/sub").toEntity()
                    .copy(url = SecretText.Unreadable("nsec1:dead")),
            )
            serverDao.upsert(node("healthy", groupId = "lost").toEntity())

            val skips = repository.inspectExport()

            assertThat(skips.servers.single().reason)
                .isEqualTo(BackupRepository.SkipReason.GROUP_SKIPPED)
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

    private fun trafficRow(
        day: Int,
        tag: String,
        upload: Long = 1,
        download: Long = 1,
    ) = TrafficDailyEntity(
        day = day,
        outboundTag = tag,
        upload = upload,
        download = download,
        updatedAt = 0,
    )
}
