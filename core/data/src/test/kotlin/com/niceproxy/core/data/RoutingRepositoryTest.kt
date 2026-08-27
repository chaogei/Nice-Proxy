package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import com.niceproxy.core.model.RoutingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class RoutingRepositoryTest {

    private val dao = FakeRoutingDao()
    private val repository = RoutingRepository(dao, Dispatchers.Unconfined)

    @Test
    fun `套用模板会清掉未锁定的旧规则`() = runTest {
        repository.saveRule(rule("old", order = 0))
        repository.applyTemplate(RoutingMode.BYPASS_MAINLAND)

        assertThat(repository.getRules().map { it.id }).doesNotContain("old")
        assertThat(repository.getRules()).isNotEmpty()
    }

    @Test
    fun `锁定的规则会被保留并排在模板规则之前`() = runTest {
        // 手写规则通常是要覆盖默认行为的，排在模板的「国内域名直连」后面会被截胡
        repository.saveRule(rule("mine", order = 5, locked = true))
        repository.applyTemplate(RoutingMode.BYPASS_MAINLAND)

        val ids = repository.getRules().map { it.id }
        assertThat(ids).contains("mine")
        assertThat(ids.first()).isEqualTo("mine")
    }

    @Test
    fun `套用模板会带上模板需要的规则集`() = runTest {
        repository.applyTemplate(RoutingMode.BYPASS_MAINLAND)

        val referenced = repository.getRules().flatMap { it.matcher.ruleSet }.toSet()
        val declared = repository.getRuleSets().map { it.tag }.toSet()
        // 规则引用了却没声明的规则集会让内核拒绝加载整份配置
        assertThat(declared).containsAtLeastElementsIn(referenced)
    }

    @Test
    fun `拖拽排序后 sortOrder 与列表顺序一致`() = runTest {
        listOf(rule("a", order = 0), rule("b", order = 1), rule("c", order = 2))
            .forEach { repository.saveRule(it) }

        repository.reorder(
            listOf(
                repository.getRules().single { it.id == "c" },
                repository.getRules().single { it.id == "a" },
                repository.getRules().single { it.id == "b" },
            ),
        )

        assertThat(repository.getRules().map { it.id }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `启用与锁定开关按 id 精确命中`() = runTest {
        repository.saveRule(rule("a"))
        repository.saveRule(rule("b", order = 1))

        repository.setRuleEnabled("a", false)
        repository.setRuleLocked("b", true)

        val rules = repository.getRules().associateBy { it.id }
        assertThat(rules.getValue("a").enabled).isFalse()
        assertThat(rules.getValue("a").locked).isFalse()
        assertThat(rules.getValue("b").enabled).isTrue()
        assertThat(rules.getValue("b").locked).isTrue()
    }

    @Test
    fun `全局直连模板不需要任何远程规则集`() = runTest {
        // 纯中继场景要能完全离线工作，不能因为下载不到 srs 就起不来
        repository.applyTemplate(RoutingMode.GLOBAL_DIRECT)

        assertThat(repository.getRules()).isNotEmpty()
        assertThat(repository.getRuleSets()).isEmpty()
    }
}
