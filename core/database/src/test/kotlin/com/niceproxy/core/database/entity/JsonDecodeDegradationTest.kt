package com.niceproxy.core.database.entity

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.model.CredentialState
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.MultiplexConfig
import com.niceproxy.core.model.ProtocolParams
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RuleAction
import com.niceproxy.core.model.TlsConfig
import com.niceproxy.core.model.TransportConfig
import com.niceproxy.core.model.WellKnownTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * JSON 列读不懂时的降级行为。
 *
 * 和「Keystore 密钥没了」不是一回事：这里密文解得开，是解开之后的 JSON
 * 认不出来了。成因基本都是代码改动 —— 给某个配置类加了没有默认值的字段、
 * 改了字段名、删掉一个密封类分支。注意 `ignoreUnknownKeys` 在这里帮不上忙，
 * 它只忽略未知的**键**，对未知的**类型判别符**无效。
 *
 * 底线只有一条：一条读不懂的记录不能把整条 Flow 拖垮 —— 那意味着节点页
 * 整页空白、`ConfigRepository` 抛异常、代理起不来。
 */
class JsonDecodeDegradationTest {

    private val trojanParams = """{"type":"trojan","password":"p@ss"}"""
    private val brokenJson = """{ 这不是 JSON"""

    private fun serverEntity(
        transportJson: String? = null,
        tlsJson: String? = null,
        multiplexJson: String? = null,
        paramsJson: SecretText = SecretText.Readable(trojanParams),
    ) = ServerEntity(
        id = "s1",
        groupId = "g1",
        name = "香港 01",
        protocol = ProxyProtocol.TROJAN,
        server = "example.com",
        serverPort = 443,
        paramsJson = paramsJson,
        transportJson = transportJson,
        tlsJson = tlsJson,
        multiplexJson = multiplexJson,
        sortOrder = 0,
        latencyMs = null,
        lastTestedAt = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun ruleEntity(matcherJson: String, actionJson: String) = RoutingRuleEntity(
        id = "r1",
        name = "广告拦截",
        enabled = true,
        sortOrder = 0,
        matcherJson = matcherJson,
        actionJson = actionJson,
    )

    private val validMatcher = """{"domainSuffix":["ad.example.com"]}"""
    private val validAction = """{"type":"reject"}"""

    @Nested
    @DisplayName("TLS")
    inner class Tls {

        @Test
        @DisplayName("读不出来时绝不退化成 null，否则连接会静默变成明文")
        fun neverFallsBackToNull() {
            // tls_json 非空说明这个节点当初配了 TLS。VMess / VLESS / Trojan
            // 在 tls 缺席时照样能生成一个语法合法的出站，只是不再加密，
            // 而界面上和正常节点毫无区别。
            val domain = serverEntity(tlsJson = brokenJson).toDomain()

            assertThat(domain.tls).isNotNull()
            assertThat(domain.tls?.enabled).isTrue()
        }

        @Test
        @DisplayName("节点带上失效状态，用户能看到它需要重新导入")
        fun marksTheNode() {
            assertThat(serverEntity(tlsJson = brokenJson).toDomain().credentialState)
                .isEqualTo(CredentialState.UNREADABLE)
        }

        @Test
        @DisplayName("本来就没有 TLS 的节点不受影响")
        fun plaintextNodeUntouched() {
            val domain = serverEntity(tlsJson = null).toDomain()

            assertThat(domain.tls).isNull()
            assertThat(domain.credentialState).isEqualTo(CredentialState.OK)
        }

        @Test
        @DisplayName("读得出来时原样带出")
        fun readableTlsSurvives() {
            val json = entityJson.encodeToString(
                TlsConfig.serializer(),
                TlsConfig(enabled = true, serverName = "sni.example.com", insecure = true),
            )

            val domain = serverEntity(tlsJson = json).toDomain()

            assertThat(domain.tls?.serverName).isEqualTo("sni.example.com")
            assertThat(domain.tls?.insecure).isTrue()
            assertThat(domain.credentialState).isEqualTo(CredentialState.OK)
        }
    }

    @Nested
    @DisplayName("传输层与多路复用")
    inner class TransportAndMultiplex {

        @Test
        @DisplayName("读不出来时退化成 null，但节点要被标记")
        fun degradeToNull() {
            // 这两个退化成 null 本身是安全的：一个会让连接握不上手，
            // 一个只是不复用连接，都不会把加密悄悄关掉。
            val transportBroken = serverEntity(transportJson = brokenJson).toDomain()
            val multiplexBroken = serverEntity(multiplexJson = brokenJson).toDomain()

            assertThat(transportBroken.transport).isNull()
            assertThat(transportBroken.credentialState).isEqualTo(CredentialState.UNREADABLE)
            assertThat(multiplexBroken.multiplex).isNull()
            assertThat(multiplexBroken.credentialState).isEqualTo(CredentialState.UNREADABLE)
        }

        @Test
        @DisplayName("未知的类型判别符同样走降级，不抛异常")
        fun unknownDiscriminatorDoesNotThrow() {
            // 删掉一个 TransportConfig 分支之后，旧数据长这样
            val domain = serverEntity(transportJson = """{"type":"未来才有的传输层"}""").toDomain()

            assertThat(domain.transport).isNull()
        }

        @Test
        @DisplayName("读得出来时原样带出")
        fun readableSurvives() {
            val transport = entityJson.encodeToString(
                TransportConfig.serializer(),
                TransportConfig.WebSocket(path = "/ray"),
            )
            val multiplex = entityJson.encodeToString(
                MultiplexConfig.serializer(),
                MultiplexConfig(protocol = "yamux"),
            )

            val domain = serverEntity(transportJson = transport, multiplexJson = multiplex)
                .toDomain()

            assertThat((domain.transport as TransportConfig.WebSocket).path).isEqualTo("/ray")
            assertThat(domain.multiplex?.protocol).isEqualTo("yamux")
            assertThat(domain.credentialState).isEqualTo(CredentialState.OK)
        }
    }

    @Nested
    @DisplayName("可否无损导出")
    inner class Decodability {

        @Test
        @DisplayName("四个 JSON 列任意一个读不懂都算读不完整")
        fun anyBrokenColumnCounts() {
            assertThat(serverEntity(tlsJson = brokenJson).isFullyDecodable).isFalse()
            assertThat(serverEntity(transportJson = brokenJson).isFullyDecodable).isFalse()
            assertThat(serverEntity(multiplexJson = brokenJson).isFullyDecodable).isFalse()
            assertThat(
                serverEntity(paramsJson = SecretText.Readable(brokenJson)).isFullyDecodable,
            ).isFalse()
        }

        @Test
        @DisplayName("密文解不开也算，备份不能收下一个空密码的节点")
        fun unreadableSecretCounts() {
            assertThat(
                serverEntity(paramsJson = SecretText.Unreadable("nsec1:dead")).isFullyDecodable,
            ).isFalse()
        }

        @Test
        @DisplayName("四列都好的节点可以无损导出")
        fun healthyNodeIsExportable() {
            assertThat(serverEntity().isFullyDecodable).isTrue()
        }
    }

    @Nested
    @DisplayName("路由规则")
    inner class Routing {

        @Test
        @DisplayName("匹配条件读不出来时停用该规则")
        fun brokenMatcherDisablesRule() {
            // 条件被换掉的规则无论怎么执行都不是用户写的那条，
            // 让它参与分流只会产生莫名其妙的行为
            val domain = ruleEntity(brokenJson, validAction).toDomain()

            assertThat(domain.enabled).isFalse()
            assertThat(domain.name).isEqualTo("广告拦截")
        }

        @Test
        @DisplayName("动作读不出来时同样停用")
        fun brokenActionDisablesRule() {
            val domain = ruleEntity(validMatcher, brokenJson).toDomain()

            assertThat(domain.enabled).isFalse()
        }

        @Test
        @DisplayName("占位条件是空 matcher，配置生成器会连同警告一起丢掉它")
        fun placeholderMatcherNeverMatches() {
            // SingBoxConfigBuilder.resolveRules 对空 matcher 有专门处理：
            // 丢弃并记一条 EmptyRule 警告，而不是当成「匹配全部流量」
            val domain = ruleEntity(brokenJson, validAction).toDomain()

            assertThat(domain.matcher.isEmpty).isTrue()
        }

        @Test
        @DisplayName("占位动作是走代理而不是直连")
        fun placeholderActionDoesNotLeak() {
            val domain = ruleEntity(validMatcher, brokenJson).toDomain()

            assertThat(domain.action).isEqualTo(RuleAction.Route(WellKnownTag.PROXY))
        }

        @Test
        @DisplayName("未知的动作类型判别符不抛异常，整个规则页不会空白")
        fun unknownActionDiscriminatorDoesNotThrow() {
            // 删掉一个 RuleAction 分支之后旧数据长这样
            val domain = ruleEntity(validMatcher, """{"type":"未来才有的动作"}""").toDomain()

            assertThat(domain.enabled).isFalse()
        }

        @Test
        @DisplayName("两列都读得出来时规则照常生效")
        fun healthyRuleUnaffected() {
            val domain = ruleEntity(validMatcher, validAction).toDomain()

            assertThat(domain.enabled).isTrue()
            assertThat(domain.action).isEqualTo(RuleAction.Reject())
            assertThat(domain.matcher.domainSuffix).containsExactly("ad.example.com")
        }
    }

    @Nested
    @DisplayName("订阅流量")
    inner class Traffic {

        @Test
        @DisplayName("读不懂就丢掉，不影响这个分组能不能导出")
        fun trafficIsDisposable() {
            // 流量统计是纯展示数据，下次刷新订阅就从响应头重新拿到，
            // 不存在「丢了就没了」的问题
            val entity = ServerGroupEntity(
                id = "g1",
                name = "机场 A",
                type = GroupType.SUBSCRIPTION,
                url = SecretText.Readable("https://a.example.com/sub"),
                userAgent = null,
                autoUpdate = true,
                updateIntervalMinutes = 1440,
                lastUpdateAt = null,
                lastError = null,
                trafficJson = brokenJson,
                sortOrder = 0,
            )

            val domain = entity.toDomain()

            assertThat(domain.traffic).isNull()
            assertThat(domain.url).isEqualTo("https://a.example.com/sub")
            assertThat(entity.hasUnreadableSecrets).isFalse()
        }
    }

    @Test
    @DisplayName("凭据 JSON 读不懂时仍然退化成一个校验不过的占位符")
    fun paramsStillFailClosed() {
        val domain = serverEntity(paramsJson = SecretText.Readable(brokenJson)).toDomain()

        // Direct 是 OutboundFactory 里唯一不做校验的分支，退化成它等于放行裸奔流量
        assertThat(domain.params).isNotEqualTo(ProtocolParams.Direct)
        assertThat(domain.params).isEqualTo(ProtocolParams.Trojan(""))
    }
}
