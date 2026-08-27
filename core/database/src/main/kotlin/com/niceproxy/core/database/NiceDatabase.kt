package com.niceproxy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.niceproxy.core.database.crypto.SecretTextConverter
import com.niceproxy.core.database.dao.InboundDao
import com.niceproxy.core.database.dao.RoutingDao
import com.niceproxy.core.database.dao.ServerDao
import com.niceproxy.core.database.dao.ServerGroupDao
import com.niceproxy.core.database.entity.InboundEntity
import com.niceproxy.core.database.entity.RoutingRuleEntity
import com.niceproxy.core.database.entity.RuleSetEntity
import com.niceproxy.core.database.entity.ServerEntity
import com.niceproxy.core.database.entity.ServerGroupEntity
import com.niceproxy.core.model.GroupType
import com.niceproxy.core.model.InboundType
import com.niceproxy.core.model.ProxyProtocol
import com.niceproxy.core.model.RuleSetFormat
import com.niceproxy.core.model.RuleSetType

@Database(
    entities = [
        InboundEntity::class,
        ServerEntity::class,
        ServerGroupEntity::class,
        RoutingRuleEntity::class,
        RuleSetEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(NiceTypeConverters::class, SecretTextConverter::class)
abstract class NiceDatabase : RoomDatabase() {
    abstract fun inboundDao(): InboundDao
    abstract fun serverDao(): ServerDao
    abstract fun serverGroupDao(): ServerGroupDao
    abstract fun routingDao(): RoutingDao

    companion object {
        const val NAME = "nice-proxy.db"
    }
}

/**
 * 枚举一律按名称存字符串而不是序号。
 *
 * 序号会随枚举项的增删顺序变化而错位，是一类很难排查的数据损坏；
 * 名称多占几个字节，但增删枚举项时旧数据依然正确。
 */
class NiceTypeConverters {
    @TypeConverter fun fromInboundType(value: InboundType): String = value.name
    @TypeConverter fun toInboundType(value: String): InboundType = InboundType.valueOf(value)

    @TypeConverter fun fromProtocol(value: ProxyProtocol): String = value.name
    @TypeConverter fun toProtocol(value: String): ProxyProtocol = ProxyProtocol.valueOf(value)

    @TypeConverter fun fromGroupType(value: GroupType): String = value.name
    @TypeConverter fun toGroupType(value: String): GroupType = GroupType.valueOf(value)

    @TypeConverter fun fromRuleSetType(value: RuleSetType): String = value.name
    @TypeConverter fun toRuleSetType(value: String): RuleSetType = RuleSetType.valueOf(value)

    @TypeConverter fun fromRuleSetFormat(value: RuleSetFormat): String = value.name
    @TypeConverter fun toRuleSetFormat(value: String): RuleSetFormat = RuleSetFormat.valueOf(value)
}
