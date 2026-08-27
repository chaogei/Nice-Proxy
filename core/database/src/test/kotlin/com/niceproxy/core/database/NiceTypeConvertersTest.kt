package com.niceproxy.core.database

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RuleSetFormat
import com.niceproxy.core.model.RuleSetType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 枚举列的读取。
 *
 * 这些转换器是在 Room 读游标的过程中被调用的，早于 `toDomain()`，
 * 上层任何 `runCatching` 都够不着 —— 一个认不出来的字符串会让
 * `observeAll()` 整条 Flow 失败，用户看到的是一整页空白，而且没有任何
 * 办法恢复。所以读的方向一律不许抛异常。
 *
 * 兜底值的选取比「不抛异常」更要紧：选错了会让一条坏记录变成一个
 * 能正常工作的明文通道。
 */
class NiceTypeConvertersTest {

    private val converters = NiceTypeConverters()

    /** 上一个版本写的、本版本已经不认识的枚举名。 */
    private val unknown = "SOMETHING_FROM_THE_FUTURE"

    @Nested
    @DisplayName("往返")
    inner class RoundTrip {

        @Test
        @DisplayName("按名称存取，增删枚举项不会让旧数据错位")
        fun namesRoundTrip() {
            ProxyProtocol.entries.forEach {
                assertThat(converters.toProtocol(converters.fromProtocol(it))).isEqualTo(it)
            }
            InboundType.entries.forEach {
                assertThat(converters.toInboundType(converters.fromInboundType(it))).isEqualTo(it)
            }
            GroupType.entries.forEach {
                assertThat(converters.toGroupType(converters.fromGroupType(it))).isEqualTo(it)
            }
            RuleSetType.entries.forEach {
                assertThat(converters.toRuleSetType(converters.fromRuleSetType(it))).isEqualTo(it)
            }
            RuleSetFormat.entries.forEach {
                assertThat(converters.toRuleSetFormat(converters.fromRuleSetFormat(it)))
                    .isEqualTo(it)
            }
        }
    }

    @Nested
    @DisplayName("认不出来的值")
    inner class UnknownValues {

        @Test
        @DisplayName("一律不抛异常，否则整条 Flow 会死在读游标的过程里")
        fun neverThrows() {
            converters.toProtocol(unknown)
            converters.toInboundType(unknown)
            converters.toGroupType(unknown)
            converters.toRuleSetType(unknown)
            converters.toRuleSetFormat(unknown)
        }

        @Test
        @DisplayName("空字符串同样不抛")
        fun emptyStringIsSafe() {
            assertThat(converters.toProtocol("")).isNotNull()
            assertThat(converters.toInboundType("")).isNotNull()
        }

        @Test
        @DisplayName("协议绝不能退化成直连")
        fun protocolNeverBecomesDirect() {
            // DIRECT 会被 OutboundFactory 原样生成成一个可用的直连出站，
            // 用户以为在走代理，流量全部裸奔
            assertThat(converters.toProtocol(unknown)).isNotEqualTo(ProxyProtocol.DIRECT)
        }

        @Test
        @DisplayName("协议退化成 TROJAN，和凭据占位符凑成一条必然被拒的记录")
        fun protocolPairsWithCredentialPlaceholder() {
            // 认不出协议名，多半这一行的 params_json 也带着本版本不认识的
            // 类型判别符，会退化成 ProtocolParams.Trojan("")。两者凑在一起时
            // OutboundFactory 的 Trojan 分支因为密码为空判定 Invalid，
            // 节点被挡在配置之外而不会让整份配置构建失败。
            assertThat(converters.toProtocol(unknown)).isEqualTo(ProxyProtocol.TROJAN)
        }

        @Test
        @DisplayName("入站退化成内核不会监听的类型，绝不能变成敞开的代理端口")
        fun inboundNeverOpensAPort() {
            val fallback = converters.toInboundType(unknown)

            // PAC 是唯一 isSingBoxManaged == false 的类型，
            // SingBoxConfigBuilder 不会为它生成任何监听
            assertThat(fallback.isSingBoxManaged).isFalse()
            assertThat(fallback).isEqualTo(InboundType.PAC)
        }

        @Test
        @DisplayName("分组退化成手动组，最坏只是不再自动更新")
        fun groupFallsBackToManual() {
            // 反过来把手动组当成订阅组，自动更新任务会反复拿着空地址失败
            assertThat(converters.toGroupType(unknown)).isEqualTo(GroupType.MANUAL)
        }

        @Test
        @DisplayName("规则集退化成模型自身的默认值")
        fun ruleSetFallsBackToModelDefaults() {
            assertThat(converters.toRuleSetType(unknown)).isEqualTo(RuleSetType.REMOTE)
            assertThat(converters.toRuleSetFormat(unknown)).isEqualTo(RuleSetFormat.BINARY)
        }
    }
}
