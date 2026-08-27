package com.niceproxy.core.database.health

import com.niceproxy.core.database.crypto.SecretCodec
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 落盘凭据到底有没有被加密，供界面查询。
 *
 * 存在的理由是 [SecretCodec] 的降级是**静默且单向**的：设备拿不到 Keystore
 * 密钥时它会退回明文写入并把状态位翻成 true，此后每一次写入都是明文，而界面
 * 上和加密时一模一样 —— 密码、UUID、Shadowsocks 密钥、SSH 私钥，以及订阅
 * URL 里那个能一把梭出整个机场账号的 token，全部裸躺在数据库里。
 * `SecretCodec` 的注释早就要求这个状态必须能被上层查询到，但在此之前没有
 * 任何生产代码读过它。
 *
 * 单开一个类而不是让界面直接注入 [SecretCodec]：后者是加密原语，不该出现在
 * UI 的依赖图里，而且它的实例是 `DatabaseModule` 手工装配的，不是随便能注入的。
 */
@Singleton
class CredentialHealth @Inject constructor(
    private val codec: SecretCodec,
) {

    /** true 表示敏感字段正在以明文落盘。一旦为 true 就不会再变回 false。 */
    val degraded: StateFlow<Boolean> = codec.degraded

    /**
     * 主动试一次加密，让降级状态在用户保存第一个节点**之前**就暴露出来。
     *
     * 不做这一步的话 [degraded] 在启动时永远是 false：它只在真正写入失败时
     * 才翻转，等到翻转时第一份明文凭据已经落盘了。提示要有意义就必须早于
     * 那一次写入。
     *
     * 会走一次到 keystore2 守护进程的 binder 往返，别在主线程调。
     */
    fun probe(): Boolean {
        codec.encrypt(PROBE_PLAINTEXT)
        return degraded.value
    }

    private companion object {
        const val PROBE_PLAINTEXT = ""
    }
}
