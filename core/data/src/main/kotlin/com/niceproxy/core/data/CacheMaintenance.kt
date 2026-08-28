package com.niceproxy.core.data

import com.niceproxy.core.common.Dispatcher
import com.niceproxy.core.common.NiceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存的种类。
 *
 * 分种类而不是「一键清空」，是因为它们的**代价和风险完全不同**：规则集缓存
 * 清掉要重新下载几百 KB 且内核启动会慢一截，而临时文件清掉毫无代价。
 * 设置页要能把这个差别告诉用户，而不是给一个「清理缓存」按钮让人赌。
 */
enum class CacheKind {

    /**
     * 内核的 `cache_file`（`SingBoxConfigBuilder` 写的 `workDir/cache.db`）。
     *
     * 里面装着下载好的 `.srs` 规则集和 RDRC（拒绝解析缓存）。这是通常唯一
     * 长得比较大的一块，也是唯一**清掉有代价**的一块：下次启动要重新下载
     * 全部规则集，走的还是尚未建立的代理连接。
     */
    KERNEL_RULE_SET,

    /**
     * HTTP 磁盘缓存。
     *
     * OkHttp 的 `Cache` 目录。目前 `SubscriptionFetcher` 没有开磁盘缓存，
     * 所以这一项多数时候是空的 —— 保留它是因为「清理缓存」这个入口一旦
     * 交付给用户，就不该在某天有人给 OkHttp 加上 `Cache(...)` 之后
     * 变成一个漏掉一半的清理。约定好目录，加缓存的那个改动就不必再回来
     * 动这里。
     */
    HTTP_DISK,

    /**
     * 其余落在 `cacheDir` 下的东西：下载到一半的订阅正文、导出备份时的临时
     * 文件、系统组件自己塞进来的。
     *
     * 按 Android 的约定 `cacheDir` 本来就是可被系统随时清空的，所以这一项
     * 永远安全。
     */
    TRANSIENT,
    ;

    /**
     * 代理正在运行时清它安不安全。
     *
     * 内核开着 `cache.db` 的文件句柄。在 Linux 上删掉一个打开着的文件不会
     * 立刻出错，但内核后续的写会落进一个已经没有目录项的 inode，
     * 下次启动读到的是空缓存 —— 更糟的是 SQLite 在这种情况下可能直接报
     * `disk I/O error` 把内核带崩。所以这一项必须先停代理。
     */
    val safeWhileRunning: Boolean get() = this != KERNEL_RULE_SET
}

/**
 * 各类缓存落在磁盘上的位置。
 *
 * 做成数据类由 DI 注入，而不是让 [CacheMaintenance] 自己去摸 `Context`：
 * 这个类要做的事是**递归删除文件**，那是整个数据层里最容易写出灾难的一段
 * （一个拼错的路径就能把用户的数据库删掉）。让路径成为参数，测试才能拿
 * 临时目录把每一条边界都跑一遍。
 *
 * @param kernelWorkDir 内核的工作目录，即 `Context.filesDir`。**注意这个
 *   目录里还有别的东西**（Room 的库文件就在它的 `databases` 子目录旁边），
 *   所以这里只删白名单里的那几个文件名，绝不整目录递归。
 */
data class CacheLayout(
    val kernelWorkDir: File,
    val httpCacheDir: File,
    val transientDir: File,
) {
    companion object {
        /** 和 `SingBoxConfigBuilder.buildExperimental` 写进配置的路径保持一致。 */
        const val KERNEL_CACHE_NAME = "cache.db"

        /**
         * SQLite 在 WAL 模式下的伴生文件。
         *
         * 只删主文件是没用的：残留的 `-wal` 会被下一次打开重新套用，
         * 等于什么都没清。`DatabaseModule` 里对 Room 的库也写过同一条注释。
         */
        val KERNEL_CACHE_FILES = listOf(
            KERNEL_CACHE_NAME,
            "$KERNEL_CACHE_NAME-wal",
            "$KERNEL_CACHE_NAME-shm",
            "$KERNEL_CACHE_NAME-journal",
        )

        /** OkHttp `Cache` 的约定目录名，挂在 `Context.cacheDir` 下。 */
        const val HTTP_CACHE_DIR_NAME = "http"
    }
}

/** 一类缓存当前占了多少。 */
data class CacheUsage(val kind: CacheKind, val bytes: Long)

/**
 * 一次清理的结果。
 *
 * 把「释放了多少」和「有没有删不掉的」分开报：删不掉在 Android 上是常态
 * （文件正被别的进程打开、存储被卸载），静默当成成功会让用户点了没反应
 * 还以为是自己看错了。
 */
data class CacheCleanupReport(
    val freedBytes: Long,
    val cleared: Set<CacheKind>,
    val failed: Set<CacheKind>,
) {
    val isCompleteSuccess: Boolean get() = failed.isEmpty()
}

/**
 * 缓存清理（设置页的「清理缓存」）。
 *
 * 只碰缓存，绝不碰数据库、DataStore、备份文件。这条边界靠两点保证：
 * 路径由 [CacheLayout] 显式给出，以及内核目录下只按文件名白名单删。
 */
@Singleton
class CacheMaintenance @Inject constructor(
    private val layout: CacheLayout,
    @Dispatcher(NiceDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 各类缓存现在占多少，供设置页在按钮上直接显示体积。
     *
     * 单独一个入口而不是「清理完再告诉你清了多少」：用户需要在点之前就知道
     * 值不值得 —— 清掉规则集缓存的代价是下次启动慢一截。
     */
    suspend fun inspect(): List<CacheUsage> = withContext(ioDispatcher) {
        CacheKind.entries.map { CacheUsage(it, sizeOf(it)) }
    }

    /**
     * 清理指定种类的缓存。
     *
     * @param kinds 要清哪些。默认只清运行中也安全的那些 —— 调用方没有明确
     *   表态时，不该替他做「顺手把内核缓存也删了」这种可能弄坏正在跑的代理
     *   的决定。要清 [CacheKind.KERNEL_RULE_SET] 就显式传进来，并且先停代理。
     */
    suspend fun clear(
        kinds: Set<CacheKind> = CacheKind.entries.filterTo(mutableSetOf()) { it.safeWhileRunning },
    ): CacheCleanupReport = withContext(ioDispatcher) {
        var freed = 0L
        val cleared = mutableSetOf<CacheKind>()
        val failed = mutableSetOf<CacheKind>()

        kinds.forEach { kind ->
            val before = sizeOf(kind)
            val ok = runCatching { deleteAll(kind) }.getOrDefault(false)
            val after = sizeOf(kind)
            freed += (before - after).coerceAtLeast(0)
            if (ok && after == 0L) cleared += kind else failed += kind
        }

        CacheCleanupReport(freedBytes = freed, cleared = cleared, failed = failed)
    }

    private fun targetsOf(kind: CacheKind): List<File> = when (kind) {
        // 白名单而不是「删整个 workDir」：filesDir 里还躺着 PAC 脚本之类
        // 真正需要留下的东西，而这个方法删起来是不问的
        CacheKind.KERNEL_RULE_SET ->
            CacheLayout.KERNEL_CACHE_FILES.map { File(layout.kernelWorkDir, it) }
        CacheKind.HTTP_DISK -> listOf(layout.httpCacheDir)
        // HTTP 缓存目录就挂在 cacheDir 下，不排除掉的话它会被算两遍，
        // 用户看到的「已释放」是实际的两倍
        CacheKind.TRANSIENT ->
            layout.transientDir.listFiles().orEmpty().filterNot { it == layout.httpCacheDir }
    }

    private fun sizeOf(kind: CacheKind): Long = targetsOf(kind).sumOf { sizeOnDisk(it) }

    /**
     * 递归求大小。
     *
     * 不用 `walkTopDown().sumOf { it.length() }` 是为了显式挡住符号链接：
     * 一个指回上层目录的链接会让遍历打转，而 `cacheDir` 是别的组件也在写的
     * 地方，不能假设里面全是规规矩矩的普通文件。
     */
    private fun sizeOnDisk(file: File): Long = when {
        !file.exists() -> 0
        file.isDirectory -> file.listFiles().orEmpty().sumOf { child ->
            if (isSymlink(child)) 0 else sizeOnDisk(child)
        }
        else -> file.length()
    }

    /** @return 全部删干净了才返回 true。 */
    private fun deleteAll(kind: CacheKind): Boolean =
        targetsOf(kind).map { delete(it) }.all { it }

    private fun delete(file: File): Boolean {
        if (!file.exists()) return true
        if (isSymlink(file)) return file.delete()
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty().map { delete(it) }
            // 目录本身留着：OkHttp 那类组件会在启动时抓着目录句柄，
            // 把目录删掉再让它去写只会换来一个 IOException
            return children.all { it }
        }
        return file.delete()
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { file.canonicalFile != file.absoluteFile }.getOrDefault(false)
}
