package com.niceproxy.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.niceproxy.core.database.entity.InboundEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InboundDao {

    @Query("SELECT * FROM inbounds ORDER BY sort_order ASC, listen_port ASC")
    fun observeAll(): Flow<List<InboundEntity>>

    @Query("SELECT * FROM inbounds ORDER BY sort_order ASC, listen_port ASC")
    suspend fun getAll(): List<InboundEntity>

    @Query("SELECT * FROM inbounds WHERE id = :id")
    suspend fun getById(id: String): InboundEntity?

    /** 端口冲突检测：查询占用该端口的其他入站。 */
    @Query("SELECT COUNT(*) FROM inbounds WHERE listen_port = :port AND id != :excludeId AND enabled = 1")
    suspend fun countByPort(port: Int, excludeId: String): Int

    @Upsert
    suspend fun upsert(inbound: InboundEntity)

    @Upsert
    suspend fun upsertAll(inbounds: List<InboundEntity>)

    @Delete
    suspend fun delete(inbound: InboundEntity)

    @Query("DELETE FROM inbounds WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE inbounds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM inbounds")
    suspend fun count(): Int

    /**
     * 认证密码解不开的入站。这些入站已被 `toDomain()` 强制停用，
     * UI 需要据此提示用户重设密码，否则代理会「莫名其妙不工作」。
     */
    suspend fun getUnreadable(): List<InboundEntity> =
        getAll().filter { it.hasUnreadableAuth }
}
