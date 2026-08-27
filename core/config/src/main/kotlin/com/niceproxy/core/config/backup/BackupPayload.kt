package com.niceproxy.core.config.backup

import com.niceproxy.core.model.InboundService
import com.niceproxy.core.model.RoutingRule
import com.niceproxy.core.model.RuleSetRef
import com.niceproxy.core.model.ServerGroup
import com.niceproxy.core.model.ServerProfile
import kotlinx.serialization.Serializable

/**
 * 备份内容。
 *
 * 刻意不包含 Clash API 的端口与密钥：那是每台设备的本地凭据，
 * 跨设备恢复时应当重新生成，而不是把同一个密钥复制到多台设备上。
 */
@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val createdAt: Long = 0,
    val inbounds: List<InboundService> = emptyList(),
    val groups: List<ServerGroup> = emptyList(),
    val servers: List<ServerProfile> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val ruleSets: List<RuleSetRef> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

sealed interface BackupError {
    val message: String

    data object WrongPassword : BackupError {
        override val message = "密码不正确，或文件已损坏"
    }

    data object NotABackup : BackupError {
        override val message = "这不是 Nice Proxy 的备份文件"
    }

    data class UnsupportedVersion(val version: Int) : BackupError {
        override val message = "备份文件版本 $version 高于当前应用支持的版本，请先升级应用"
    }

    data class Failed(override val message: String) : BackupError
}
