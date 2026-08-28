package com.niceproxy.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 中英两份文案表必须逐条对齐。
 *
 * 缺翻译在 Android 上不会报错，只会静默回落到默认语言 —— 于是英文界面上
 * 冒出一句中文，而且只有真的把语言切过去才看得见。这类漏网每加一条字符串
 * 就多一次机会，靠人眼复查守不住。
 *
 * 占位符也一起对：`%1$s` 写成 `%1$d` 会在运行时抛
 * `IllegalFormatConversionException`，同样只在那一种语言下才炸。
 */
class StringCatalogueTest {

    private val defaults = parse("values")
    private val english = parse("values-en")

    @Test
    fun `the default catalogue is not empty`() {
        // 路径写错时上面两个 map 都是空的，下面所有断言都会「通过」
        assertThat(defaults).isNotEmpty()
    }

    @Test
    fun `english translates every default string`() {
        assertThat(defaults.keys - english.keys).isEmpty()
    }

    @Test
    fun `english has no strings the default catalogue lacks`() {
        // 多出来的多半是重命名后忘了删的旧键，永远不会被读到
        assertThat(english.keys - defaults.keys).isEmpty()
    }

    @Test
    fun `placeholders match across languages`() {
        val mismatched = defaults.keys.intersect(english.keys).filter { key ->
            placeholders(defaults.getValue(key)) != placeholders(english.getValue(key))
        }

        assertThat(mismatched).isEmpty()
    }

    @Test
    fun `no translation was left as a copy of the chinese source`() {
        val untranslated = defaults.keys.intersect(english.keys).filter { key ->
            english.getValue(key).any { it.code in CJK_RANGE }
        }

        assertThat(untranslated).isEmpty()
    }

    @Test
    fun `no string is blank`() {
        val blank = (defaults + english).filterValues { it.isBlank() }.keys
        assertThat(blank).isEmpty()
    }

    /**
     * 位置参数（`%s`）和索引参数（`%1$s`）不能混用，混了以后加第二个参数时
     * 一定出错。这里统一要求带索引。
     */
    @Test
    fun `placeholders are indexed`() {
        val positional = (defaults + english)
            .filterValues { POSITIONAL.containsMatchIn(it) }
            .keys

        assertThat(positional).isEmpty()
    }

    private fun placeholders(value: String): Set<String> =
        INDEXED.findAll(value).map { it.value }.toSet()

    private fun parse(qualifier: String): Map<String, String> {
        val file = File(RES_DIR, "$qualifier/strings.xml")
        check(file.isFile) { "找不到 ${file.absolutePath}" }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            // translatable="false" 是 Android 声明「这条各语言下都一样」的标准
            // 方式（语言名的 endonym、协议名等）。它只该出现在默认目录里，
            // 别的语言目录不该再放一份。
            .filterNot { it.getAttribute("translatable") == "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        /**
         * 单测的工作目录是模块根目录（AGP 的默认设置）。直接读源码树里的 XML，
         * 而不是走 `R.string` —— 后者只能拿到当前 locale 的一份，正好看不见
         * 「另一种语言少了这条」。
         */
        val RES_DIR = File("src/main/res")

        val INDEXED = Regex("""%\d+\$[a-zA-Z]""")
        val POSITIONAL = Regex("""%(?!\d+\$)(?!%)[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

        /** CJK 统一汉字，够用来认出「这条根本没翻」。 */
        val CJK_RANGE = 0x4E00..0x9FFF
    }
}
