package com.niceproxy.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * 「清理缓存」。
 *
 * 这是整个数据层里最容易写出灾难的一段：递归删除文件，而目标目录
 * （`filesDir`）里同时躺着 Room 的数据库和 PAC 脚本。一个拼错的路径、
 * 一次多走一层的递归，删掉的就是用户唯一无法重建的东西。
 * 所以这里的测试重点不是「删干净了没有」，而是「有没有多删」。
 */
internal class CacheMaintenanceTest {

    @TempDir
    lateinit var root: File

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var maintenance: CacheMaintenance

    @BeforeEach
    fun setUp() {
        filesDir = File(root, "files").apply { mkdirs() }
        cacheDir = File(root, "cache").apply { mkdirs() }
        maintenance = CacheMaintenance(
            CacheLayout(
                kernelWorkDir = filesDir,
                httpCacheDir = File(cacheDir, CacheLayout.HTTP_CACHE_DIR_NAME),
                transientDir = cacheDir,
            ),
            Dispatchers.Unconfined,
        )
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private fun usage(kind: CacheKind, usages: List<CacheUsage>) =
        usages.single { it.kind == kind }.bytes

    @Nested
    @DisplayName("边界")
    inner class Boundaries {

        @Test
        @DisplayName("绝不碰内核工作目录里的非缓存文件")
        fun neverTouchesNonCacheFiles() = runTest {
            // filesDir 里还躺着 PAC 脚本、Room 的库文件。
            // 「删整个 workDir」和「删白名单里那几个文件名」的差别就在这里
            write(File(filesDir, "cache.db"), 10)
            write(File(filesDir, "nice-proxy.db"), 20)
            write(File(filesDir, "proxy.pac"), 5)
            write(File(filesDir, "databases/nice-proxy.db"), 30)

            maintenance.clear(setOf(CacheKind.KERNEL_RULE_SET))

            assertThat(File(filesDir, "cache.db").exists()).isFalse()
            assertThat(File(filesDir, "nice-proxy.db").exists()).isTrue()
            assertThat(File(filesDir, "proxy.pac").exists()).isTrue()
            assertThat(File(filesDir, "databases/nice-proxy.db").exists()).isTrue()
        }

        @Test
        @DisplayName("WAL 伴生文件一起删，只删主文件等于什么都没删")
        fun deletesWalSiblings() = runTest {
            // 残留的 -wal 会被下一次打开重新套用
            CacheLayout.KERNEL_CACHE_FILES.forEach { write(File(filesDir, it), 10) }

            maintenance.clear(setOf(CacheKind.KERNEL_RULE_SET))

            CacheLayout.KERNEL_CACHE_FILES.forEach {
                assertThat(File(filesDir, it).exists()).isFalse()
            }
        }

        @Test
        @DisplayName("默认不动内核缓存，那需要先停代理")
        fun kernelCacheIsOptIn() = runTest {
            // 内核开着 cache.db 的句柄。删一个打开着的文件在 Linux 上不会
            // 立刻出错，但 SQLite 后续可能直接报 disk I/O error 把内核带崩
            write(File(filesDir, "cache.db"), 100)
            write(File(cacheDir, "tmp.bin"), 10)

            val report = maintenance.clear()

            assertThat(File(filesDir, "cache.db").exists()).isTrue()
            assertThat(report.cleared).doesNotContain(CacheKind.KERNEL_RULE_SET)
            assertThat(File(cacheDir, "tmp.bin").exists()).isFalse()
        }

        @Test
        @DisplayName("只有内核缓存要求先停代理")
        fun onlyKernelCacheIsUnsafeWhileRunning() {
            assertThat(CacheKind.KERNEL_RULE_SET.safeWhileRunning).isFalse()
            assertThat(CacheKind.HTTP_DISK.safeWhileRunning).isTrue()
            assertThat(CacheKind.TRANSIENT.safeWhileRunning).isTrue()
        }

        @Test
        @DisplayName("符号链接只删链接本身，不顺着它删到别处去")
        fun doesNotFollowSymlinks() = runTest {
            val precious = File(root, "precious").apply { mkdirs() }
            write(File(precious, "nice-proxy.db"), 42)
            Files.createSymbolicLink(File(cacheDir, "escape").toPath(), precious.toPath())

            maintenance.clear(setOf(CacheKind.TRANSIENT))

            assertThat(File(precious, "nice-proxy.db").exists()).isTrue()
        }
    }

    @Nested
    @DisplayName("统计")
    inner class Reporting {

        @Test
        @DisplayName("清理前能问出各类缓存占多少")
        fun inspectReportsSizes() = runTest {
            // 用户要在点之前就知道值不值得：清掉规则集缓存的代价是
            // 下次启动要重新下载全部规则集
            write(File(filesDir, "cache.db"), 300)
            write(File(cacheDir, CacheLayout.HTTP_CACHE_DIR_NAME + "/entry"), 50)
            write(File(cacheDir, "tmp.bin"), 7)

            val usages = maintenance.inspect()

            assertThat(usage(CacheKind.KERNEL_RULE_SET, usages)).isEqualTo(300)
            assertThat(usage(CacheKind.HTTP_DISK, usages)).isEqualTo(50)
            assertThat(usage(CacheKind.TRANSIENT, usages)).isEqualTo(7)
        }

        @Test
        @DisplayName("HTTP 缓存不会被临时文件那一项重复计一遍")
        fun httpCacheIsNotDoubleCounted() = runTest {
            // 它就挂在 cacheDir 下。算两遍的话用户看到的「已释放」是实际的两倍
            write(File(cacheDir, CacheLayout.HTTP_CACHE_DIR_NAME + "/entry"), 64)

            val report = maintenance.clear(setOf(CacheKind.HTTP_DISK, CacheKind.TRANSIENT))

            assertThat(report.freedBytes).isEqualTo(64)
        }

        @Test
        @DisplayName("什么都没有时报 0，不报错")
        fun emptyIsFine() = runTest {
            val report = maintenance.clear(CacheKind.entries.toSet())

            assertThat(report.freedBytes).isEqualTo(0)
            assertThat(report.isCompleteSuccess).isTrue()
        }

        @Test
        @DisplayName("释放的字节数按实际删掉的算")
        fun freedBytesAreReal() = runTest {
            write(File(filesDir, "cache.db"), 1000)
            write(File(filesDir, "cache.db-wal"), 24)

            val report = maintenance.clear(setOf(CacheKind.KERNEL_RULE_SET))

            assertThat(report.freedBytes).isEqualTo(1024)
            assertThat(report.cleared).contains(CacheKind.KERNEL_RULE_SET)
        }

        @Test
        @DisplayName("HTTP 缓存目录本身留着，OkHttp 抓着它的句柄")
        fun keepsCacheDirectoryItself() = runTest {
            val httpDir = File(cacheDir, CacheLayout.HTTP_CACHE_DIR_NAME)
            write(File(httpDir, "journal"), 8)

            maintenance.clear(setOf(CacheKind.HTTP_DISK))

            assertThat(httpDir.exists()).isTrue()
            assertThat(httpDir.listFiles()).isEmpty()
        }
    }
}
