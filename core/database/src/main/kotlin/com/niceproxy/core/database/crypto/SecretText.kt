package com.niceproxy.core.database.crypto

/**
 * 数据库中一个敏感字段在内存里的表示。
 *
 * 之所以不直接用 [String]，是因为「解不开」必须是一种**值**而不是异常。
 * Keystore 密钥一旦失效，整张表的密文会同时解不开；如果在 Room 读游标的
 * 过程中抛异常，`ServerDao.observeAll()` 这条 Flow 会直接失败，用户看到的
 * 是一个空白的节点页，且没有任何线索说明发生了什么。
 *
 * 见 docs/DESIGN.md §9「凭据泄露」。
 */
sealed interface SecretText {

    /**
     * 明文可用。
     *
     * 相等性按明文定义。这一点是必需的而不只是顺手：每次加密都用新的随机 IV，
     * 同一份明文两次落盘的密文完全不同，若按密文比较，
     * `ServerRepository.deleteDuplicates()` 的「协议+地址+端口+参数」判重
     * 会一个重复节点都找不出来。
     */
    data class Readable(val value: String) : SecretText

    /**
     * 有密文但解不开：密钥已失效，或这一行的数据损坏。
     *
     * 保留原始存储值，使得实体被读出来又原样写回时能把密文照搬回去，
     * 而不是用占位符把它覆盖掉 —— 万一失败是暂时性的（部分 ROM 上
     * Keystore 会偶发返回错误），覆盖会把可恢复的情况变成永久丢失。
     */
    data class Unreadable(val stored: String) : SecretText
}

/** 明文，解不开时为 null。 */
val SecretText.readableOrNull: String?
    get() = (this as? SecretText.Readable)?.value

fun String.asSecret(): SecretText = SecretText.Readable(this)
