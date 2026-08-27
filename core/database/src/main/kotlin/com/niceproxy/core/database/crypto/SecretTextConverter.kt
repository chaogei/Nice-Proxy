package com.niceproxy.core.database.crypto

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter

/**
 * 在 Room 读写列值的位置做加解密。
 *
 * **为什么放在 TypeConverter 而不是 `toDomain()` / `toEntity()`**：线程。
 * Room 是在读游标、绑定语句的过程中调用转换器的，这些都发生在 Room 自己的
 * 执行器上；而 `ServerRepository.servers` 是
 * `serverDao.observeAll().map { it.toDomain() }`，这个 `map` 跑在**收集方**的
 * 上下文里 —— Compose 用 `collectAsStateWithLifecycle` 收集，也就是主线程。
 * 把加解密写进映射层，等于每次列表刷新都在主线程上跑一遍全表解密。
 *
 * 顺带还有两个好处：`toDomain()` / `toEntity()` 的签名不用变（`core:data`
 * 在调用它们，那个模块不在本次改动范围内），以及实体被原样读出再写回时
 * 密文能被照搬，见 [SecretText.Unreadable]。
 *
 * 用 `@ProvidedTypeConverter` 是因为它需要注入 [SecretCodec]，
 * 实例由 `DatabaseModule` 通过 `addTypeConverter` 交给 Room。
 */
@ProvidedTypeConverter
class SecretTextConverter(private val codec: SecretCodec) {

    @TypeConverter
    fun fromSecretText(value: SecretText): String = when (value) {
        is SecretText.Readable -> codec.encrypt(value.value)
        // 解不开的密文原样写回。这里绝不能落一个占位符：解密失败可能只是
        // 暂时的，用占位符覆盖会把「这次读不出来」变成「永远读不出来」。
        is SecretText.Unreadable -> value.stored
    }

    @TypeConverter
    fun toSecretText(value: String): SecretText = codec.decrypt(value)
}
