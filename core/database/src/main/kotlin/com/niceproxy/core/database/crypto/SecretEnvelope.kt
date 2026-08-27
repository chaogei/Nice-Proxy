package com.niceproxy.core.database.crypto

/**
 * 加密字段的存储格式：
 *
 * ```
 * "nsec1:" + hex(iv(12) || ciphertext || tag(16))
 * ```
 *
 * **为什么带自描述前缀**：靠它区分「已加密」和「升级前遗留的明文」，
 * 于是这次改动不需要任何数据库迁移 —— 老数据读出来原样返回，下次写入
 * 才变成密文。反过来，加一个 NOT NULL 列或改列的可空性都会改变 Room 的
 * identity hash，而 `DatabaseModule` 目前开着 `fallbackToDestructiveMigration`，
 * 那等于把所有用户的节点配置清空一次。
 *
 * **为什么用 hex 而不是 Base64**：`java.util.Base64` 需要 API 26，本模块
 * minSdk 是 24；`android.util.Base64` 在 JVM 单元测试里只是个抛异常的空壳。
 * hex 多占三分之一空间，但这些字段只有几百字节，换来的是同一份代码在
 * 真机和纯 JUnit 测试里走完全一样的路径。
 *
 * **为什么 IV 存在密文里而不是单开一列**：同上，避免动 schema；而且
 * IV 不是秘密，和密文放一起没有任何安全损失。
 */
internal object SecretEnvelope {

    const val PREFIX = "nsec1:"

    /** GCM 的标准 IV 长度。12 字节是唯一不需要额外 GHASH 处理的长度，也是 Keystore 的默认值。 */
    const val IV_BYTES = 12

    /** 认证标签取满 128 位。截短标签能省 8 个字节，但会同比削弱伪造难度，不值得。 */
    const val TAG_BITS = 128

    private const val TAG_BYTES = TAG_BITS / 8

    fun isEnvelope(stored: String): Boolean = stored.startsWith(PREFIX)

    fun wrap(iv: ByteArray, ciphertext: ByteArray): String =
        PREFIX + (iv + ciphertext).toHex()

    /**
     * 解析信封，格式不合法时返回 null。
     *
     * 调用方需要区分两种失败：这里返回 null 表示「这根本不是我们写的密文」
     * （多半是恰好以前缀开头的明文），而解密阶段的失败才意味着密钥对不上。
     */
    fun unwrap(stored: String): Parsed? {
        if (!isEnvelope(stored)) return null
        val body = stored.removePrefix(PREFIX).hexToBytesOrNull() ?: return null
        // 空明文加密后也有 12 字节 IV + 16 字节标签，短于此长度的一定不是有效信封
        if (body.size < IV_BYTES + TAG_BYTES) return null
        return Parsed(
            iv = body.copyOfRange(0, IV_BYTES),
            ciphertext = body.copyOfRange(IV_BYTES, body.size),
        )
    }

    data class Parsed(val iv: ByteArray, val ciphertext: ByteArray) {
        // ByteArray 的 equals 是引用比较，data class 生成的实现在这里没有意义。
        // 只有测试会比较它，按内容比较才符合预期。
        override fun equals(other: Any?): Boolean = other is Parsed &&
            iv.contentEquals(other.iv) &&
            ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val v = byte.toInt() and 0xFF
            out[index * 2] = HEX_DIGITS[v ushr 4]
            out[index * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = this[i * 2].hexDigitOrNull() ?: return null
            val lo = this[i * 2 + 1].hexDigitOrNull() ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun Char.hexDigitOrNull(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
