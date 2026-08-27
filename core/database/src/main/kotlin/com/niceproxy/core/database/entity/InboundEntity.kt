package com.niceproxy.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.niceproxy.core.database.crypto.SecretText
import com.niceproxy.core.database.crypto.asSecret
import com.niceproxy.core.database.crypto.readableOrNull
import com.niceproxy.core.model.InboundAuth
import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.InboundType

@Entity(tableName = "inbounds")
data class InboundEntity(
    @PrimaryKey val id: String,
    val type: InboundType,
    val listen: String,
    @ColumnInfo(name = "listen_port") val listenPort: Int,
    /**
     * 用户名不加密：它会原样显示在入站配置页上，本身不是秘密，
     * 加密只会换来一份没人受益的解密开销和一个额外的失败点。
     */
    @ColumnInfo(name = "auth_username") val authUsername: String?,
    @ColumnInfo(name = "auth_password") val authPassword: SecretText?,
    @ColumnInfo(name = "udp_enabled") val udpEnabled: Boolean,
    @ColumnInfo(name = "tcp_fast_open") val tcpFastOpen: Boolean,
    @ColumnInfo(name = "udp_timeout") val udpTimeout: String,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
) {
    /** 存了密码但解不开。UI 可以据此提示用户重设密码。 */
    val hasUnreadableAuth: Boolean
        get() = authPassword is SecretText.Unreadable
}

/**
 * 密码解不开时**连带停用**这个入站。
 *
 * 这是整套降级策略里唯一不能商量的一处：如果只是把 [InboundService.auth] 置空，
 * sing-box 会把它当成「免认证入站」监听在 `0.0.0.0` 上，同网段任何设备立刻
 * 就能白嫖这个代理 —— 那正是 docs/DESIGN.md §9 第一行要防的事。
 * 一个用不了的入站远好过一个敞开的入站。
 *
 * 副作用是：若所有入站都因此停用，`SingBoxConfigBuilder` 会以
 * `NoEnabledInbound` 拒绝构建配置。那是有明确报错的失败，同样好过静默敞开。
 */
fun InboundEntity.toDomain(): InboundService {
    val password = authPassword?.readableOrNull
    return InboundService(
        id = id,
        type = type,
        listen = listen,
        listenPort = listenPort,
        auth = if (authUsername != null && password != null) {
            InboundAuth(authUsername, password)
        } else {
            null
        },
        udpEnabled = udpEnabled,
        tcpFastOpen = tcpFastOpen,
        udpTimeout = udpTimeout,
        enabled = enabled && !hasUnreadableAuth,
        sortOrder = sortOrder,
    )
}

fun InboundService.toEntity(): InboundEntity = InboundEntity(
    id = id,
    type = type,
    listen = listen,
    listenPort = listenPort,
    authUsername = auth?.username,
    authPassword = auth?.password?.asSecret(),
    udpEnabled = udpEnabled,
    tcpFastOpen = tcpFastOpen,
    udpTimeout = udpTimeout,
    enabled = enabled,
    sortOrder = sortOrder,
)
