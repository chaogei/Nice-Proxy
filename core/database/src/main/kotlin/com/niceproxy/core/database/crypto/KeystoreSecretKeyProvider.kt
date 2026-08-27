package com.niceproxy.core.database.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 AndroidKeyStore 取一把不可导出的 AES 密钥。
 *
 * **为什么不用 `androidx.security:security-crypto`**（docs/DESIGN.md §7.2 提到过它）：
 * 该库自 1.1.0 起整体废弃，`MasterKey` 的官方替代建议正是「直接用
 * `KeyGenerator` + AndroidKeyStore」，也就是这个类做的事。而且它的
 * `EncryptedFile` / `EncryptedSharedPreferences` 是面向**文件**的，
 * 没有加密单个数据库列的入口，为此引入整个 Tink 也不划算。
 *
 * **为什么不要求用户认证**：`setUserAuthenticationRequired` 和
 * `setUnlockedDeviceRequired` 都会让锁屏状态下无法解密。本应用的核心场景
 * 是常驻后台给局域网设备转发流量，还支持开机自启（docs/DESIGN.md §6.8），
 * 屏幕锁着时必须能读到节点凭据，否则代理会在息屏后直接断掉。
 *
 * **为什么不要求 StrongBox**：`setIsStrongBoxBacked(true)` 在没有独立安全
 * 芯片的设备上会直接抛异常，而这类设备正是本应用的主要用户群（旧手机
 * 改造成网关）。TEE 支持的密钥已经满足威胁模型。
 */
@Singleton
class KeystoreSecretKeyProvider @Inject constructor() : SecretKeyProvider {

    // 每次读写数据库的每一行都会走到这里，而 KeyStore.getKey 是一次到
    // keystore2 守护进程的 binder 往返，不缓存的话列表滚动会明显掉帧。
    @Volatile
    private var cached: SecretKey? = null

    override fun keyOrNull(): SecretKey? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreate()?.also { cached = it }
        }
    }

    private fun loadOrCreate(): SecretKey? = try {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        existingKey(store) ?: generate()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        // KeyStore.load 的签名带 IOException，实际上对 AndroidKeyStore 不会发生，
        // 但这里宁可返回 null 走降级，也不要把异常抛到数据库读取路径上。
        null
    }

    private fun existingKey(store: KeyStore): SecretKey? = try {
        store.getKey(ALIAS, null) as? SecretKey
    } catch (_: UnrecoverableKeyException) {
        // 密钥条目还在但内容已经读不出来（部分 ROM 升级后会这样）。
        // 这种情况下旧密文已经永久解不开了，留着坏条目只会让新数据也存不了，
        // 所以删掉重建 —— 已加密的旧数据会被识别为 Unreadable 并提示用户重新导入。
        runCatching { store.deleteEntry(ALIAS) }
        null
    }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            // 默认就是 true，显式写出来是因为 SecretCodec 依赖这一点：
            // 它不传 IV，由 Keystore 保证每次加密的 IV 都不重复。
            .setRandomizedEncryptionRequired(true)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nice_proxy_field_key_v1"
        const val KEY_BITS = 256
    }
}
