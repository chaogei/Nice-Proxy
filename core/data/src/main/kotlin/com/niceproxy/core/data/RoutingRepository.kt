package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import com.niceproxy.core.config.RoutingTemplates
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.entity.toDomain
import com.niceproxy.core.database.entity.toEntity
import com.niceproxy.core.model.RoutingMode
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutingRepository @Inject constructor(
    private val dao: RoutingDao,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    // toDomain() 要反序列化规则的匹配字段，别放在主线程上做
    val rules: Flow<List<RoutingRule>> =
        dao.observeRules().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    val ruleSets: Flow<List<RuleSetRef>> =
        dao.observeRuleSets().map { list -> list.map { it.toDomain() } }.flowOn(ioDispatcher)

    suspend fun getRules(): List<RoutingRule> = withContext(ioDispatcher) {
        dao.getRules().map { it.toDomain() }
    }

    suspend fun getRuleSets(): List<RuleSetRef> = withContext(ioDispatcher) {
        dao.getRuleSets().map { it.toDomain() }
    }

    suspend fun saveRule(rule: RoutingRule) = withContext(ioDispatcher) {
        dao.upsertRule(rule.toEntity())
    }

    suspend fun deleteRule(id: String) = withContext(ioDispatcher) {
        dao.deleteRule(id)
    }

    /** 拖拽排序后整体回写。 */
    suspend fun reorder(rules: List<RoutingRule>) = withContext(ioDispatcher) {
        dao.upsertRules(rules.mapIndexed { index, rule -> rule.copy(sortOrder = index).toEntity() })
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) = withContext(ioDispatcher) {
        dao.getRules().firstOrNull { it.id == id }?.let {
            dao.upsertRule(it.copy(enabled = enabled))
        }
    }

    suspend fun saveRuleSet(ruleSet: RuleSetRef) = withContext(ioDispatcher) {
        dao.upsertRuleSet(ruleSet.toEntity())
    }

    suspend fun deleteRuleSet(id: String) = withContext(ioDispatcher) {
        dao.deleteRuleSet(id)
    }

    /**
     * 套用分流模板。
     *
     * 会**整体替换**现有规则 —— 模板的意义就是「一键回到某个已知状态」，
     * 与残留的旧规则混在一起只会产生难以预期的行为。UI 需要在此前给出确认。
     */
    suspend fun applyTemplate(mode: RoutingMode) = withContext(ioDispatcher) {
        dao.applyTemplateRules(RoutingTemplates.rulesFor(mode).map { it.toEntity() })
        val needed = RoutingTemplates.ruleSetsFor(mode)
        if (needed.isNotEmpty()) {
            dao.upsertRuleSets(needed.map { it.toEntity() })
        }
    }

    suspend fun setRuleLocked(id: String, locked: Boolean) = withContext(ioDispatcher) {
        dao.getRules().firstOrNull { it.id == id }?.let {
            dao.upsertRule(it.copy(locked = locked))
        }
    }

    fun newRule(order: Int): RoutingRule = RoutingRule(
        id = UUID.randomUUID().toString(),
        name = "新规则",
        sortOrder = order,
    )
}
