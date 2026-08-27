package com.niceproxy.core.database.entity

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 密钥失效后的降级行为。
 *
 * 这些用例描述的是「Keystore 密钥没了」之后应用应该长什么样。密钥失效是
 * 全局的（一把密钥管所有字段），所以真正的场景是**所有**加密字段同时解不开，
 * 而不是零星一两条。
 */
class CredentialDegradationTest {

    private val trojanParams = """{"type":"trojan","password":"p@ss"}"""

    private fun serverEntity(params: SecretText, name: String = "香港 01") = ServerEntity(
        id = "s1",
        groupId = "g1",
        name = name,
        protocol = ProxyProtocol.TROJAN,
        server = "example.com",
        serverPort = 443,
        paramsJson = params,
        transportJson = null,
        tlsJson = null,
        multiplexJson = null,
        sortOrder = 0,
        latencyMs = null,
        lastTestedAt = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun inboundEntity(password: SecretText?, enabled: Boolean = true) = InboundEntity(
        id = "i1",
        type = InboundType.MIXED,
        listen = InboundService.LISTEN_ALL,
        listenPort = 8080,
        authUsername = "user",
        authPassword = password,
        udpEnabled = true,
        tcpFastOpen = false,
        udpTimeout = "5m",
        enabled = enabled,
        sortOrder = 0,
    )

    // ------------------------------------------------------------ 节点

    @Test
    @DisplayName("凭据可读时映射结果和加密前完全一致")
    fun readableServer() {
        val domain = serverEntity(SecretText.Readable(trojanParams)).toDomain()

        assertThat(domain.name).isEqualTo("香港 01")
        assertThat(domain.params).isEqualTo(ProtocolParams.Trojan("p@ss"))
    }

    @Test
    @DisplayName("凭据解不开时节点仍留在列表里，并带上失效状态")
    fun unreadableServerStaysVisible() {
        // 密钥失效是全局的，若把这些节点直接过滤掉，用户看到的是一个空列表，
        // 完全无从判断发生了什么
        val domain = serverEntity(SecretText.Unreadable("nsec1:dead")).toDomain()

        assertThat(domain.id).isEqualTo("s1")
        assertThat(domain.credentialState).isEqualTo(CredentialState.UNREADABLE)
        // 名字保持原样：状态是独立字段，不能污染用户可见、可搜索、可导出的名称
        assertThat(domain.name).isEqualTo("香港 01")
    }

    @Test
    @DisplayName("凭据解不开的节点绝不能退化成直连")
    fun unreadableServerFailsClosed() {
        val params = serverEntity(SecretText.Unreadable("nsec1:dead")).toDomain().params

        // Direct 是 OutboundFactory 里唯一不做校验的分支，会被原样生成成一个
        // 可用的直连出站——用户以为在走代理，流量却是裸奔的
        assertThat(params).isNotEqualTo(ProtocolParams.Direct)
        // 占位符必须是校验一定不通过的形态，这样节点会被挡在配置生成之外
        assertThat(params).isEqualTo(ProtocolParams.Trojan(""))
    }

    @Test
    @DisplayName("JSON 损坏和解不开走同一条降级路径，不抛异常")
    fun corruptJsonDoesNotThrow() {
        val domain = serverEntity(SecretText.Readable("{ 这不是 JSON")).toDomain()

        assertThat(domain.credentialState).isEqualTo(CredentialState.UNREADABLE)
        assertThat(domain.params).isEqualTo(ProtocolParams.Trojan(""))
    }

    @Test
    @DisplayName("读出来再写回去，名称与状态都不会漂移")
    fun roundTripDoesNotMutateName() {
        val once = serverEntity(SecretText.Unreadable("nsec1:dead")).toDomain()

        assertThat(once.toEntity().name).isEqualTo("香港 01")
        // 二次往返同样稳定：状态位不会像文本前缀那样越叠越多
        assertThat(once.toEntity().toDomain().name).isEqualTo("香港 01")
    }

    @Test
    @DisplayName("凭据正常的节点状态为 OK")
    fun readableServerIsOk() {
        val domain = serverEntity(SecretText.Readable(trojanParams)).toDomain()

        assertThat(domain.credentialState).isEqualTo(CredentialState.OK)
    }

    @Test
    @DisplayName("实体暴露出可枚举的失效标志，供 UI 给出重新导入入口")
    fun unreadableIsQueryable() {
        assertThat(serverEntity(SecretText.Unreadable("x")).hasUnreadableCredentials).isTrue()
        assertThat(serverEntity(SecretText.Readable(trojanParams)).hasUnreadableCredentials)
            .isFalse()
    }

    @Test
    @DisplayName("相同明文的两个节点判等，去重功能不受随机 IV 影响")
    fun deduplicationStillWorks() {
        // ServerRepository.deleteDuplicates 按 listOf(protocol, server, port, paramsJson)
        // 分组。若 paramsJson 是密文，随机 IV 会让同一份参数每次都不相等，
        // 「删除重复节点」会一个都找不出来。
        val a = serverEntity(SecretText.Readable(trojanParams))
        val b = serverEntity(SecretText.Readable(trojanParams)).copy(id = "s2")

        assertThat(a.paramsJson).isEqualTo(b.paramsJson)
        assertThat(listOf(a.protocol, a.server, a.serverPort, a.paramsJson))
            .isEqualTo(listOf(b.protocol, b.server, b.serverPort, b.paramsJson))
    }

    // ------------------------------------------------------------ 入站

    @Test
    @DisplayName("认证密码可读时正常带出认证信息")
    fun readableInbound() {
        val domain = inboundEntity(SecretText.Readable("secret")).toDomain()

        assertThat(domain.auth).isEqualTo(InboundAuth("user", "secret"))
        assertThat(domain.enabled).isTrue()
    }

    @Test
    @DisplayName("认证密码解不开时入站被停用，绝不能变成免认证")
    fun unreadableInboundIsDisabled() {
        val domain = inboundEntity(SecretText.Unreadable("nsec1:dead")).toDomain()

        // 密码没了，auth 只能是 null；真正兜底的是 enabled=false ——
        // SingBoxConfigBuilder 只会为 enabled 的入站生成监听。少了这一步，
        // 它就变成一个挂在 0.0.0.0 上的免认证代理，同网段谁都能白嫖。
        assertThat(domain.enabled).isFalse()
        assertThat(domain.auth).isNull()
    }

    @Test
    @DisplayName("用户手动重新启用也拉不回来，除非重设密码")
    fun reEnablingDoesNotReopenIt() {
        // InboundDao.setEnabled 是一条裸 UPDATE，不会碰 auth_password，
        // 所以下次读出来仍然是停用的。这正是我们要的：想恢复只能重设密码。
        val domain = inboundEntity(SecretText.Unreadable("nsec1:dead"), enabled = true).toDomain()

        assertThat(domain.enabled).isFalse()
    }

    @Test
    @DisplayName("本来就免认证的入站不受影响")
    fun noAuthInboundUnaffected() {
        val domain = inboundEntity(password = null).toDomain()

        assertThat(domain.auth).isNull()
        assertThat(domain.enabled).isTrue()
    }

    // ------------------------------------------------------------ 订阅

    @Test
    @DisplayName("订阅地址解不开时退化为 null，分组本身还在")
    fun unreadableSubscriptionUrl() {
        val entity = ServerGroupEntity(
            id = "g1",
            name = "机场 A",
            type = GroupType.SUBSCRIPTION,
            url = SecretText.Unreadable("nsec1:dead"),
            userAgent = null,
            autoUpdate = true,
            updateIntervalMinutes = 1440,
            lastUpdateAt = null,
            lastError = null,
            trafficJson = null,
            sortOrder = 0,
        )

        val domain = entity.toDomain()

        // SubscriptionRepository 对空地址返回「缺少订阅地址」而不是 NPE，
        // 用户重新粘一次链接即可恢复
        assertThat(domain.name).isEqualTo("机场 A")
        assertThat(domain.url).isNull()
    }
}
