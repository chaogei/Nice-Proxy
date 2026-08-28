package com.niceproxy.appearance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `a null tag means follow the system`() {
        assertThat(AppLanguage.fromTag(null)).isEqualTo(AppLanguage.SYSTEM)
    }

    /**
     * SYSTEM 自己的 tag 就是 null。如果 fromTag 少了那道 `it.tag != null` 的
     * 判断，`fromTag("de")` 会先撞上 SYSTEM 的 null 再拿它去比 —— 结果碰巧
     * 也对，但换成任何一个 tag 为 null 的新枚举项就会错。
     */
    @Test
    fun `an unsupported tag falls back to the system`() {
        assertThat(AppLanguage.fromTag("de")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTag("")).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun `exact tags round trip`() {
        AppLanguage.entries.filter { it.tag != null }.forEach { language ->
            assertThat(AppLanguage.fromTag(language.tag)).isEqualTo(language)
        }
    }

    /**
     * 系统存的是 `zh-Hans-CN` 这类带脚本的完整标签，和 `zh-CN` 直接比字符串
     * 永远不相等 —— 症状是设置页的选中项每次进去都弹回「跟随系统」。
     */
    @Test
    fun `locales with a script subtag still match`() {
        assertThat(AppearanceStore.matchTag(Locale.forLanguageTag("zh-Hans-CN"))).isEqualTo("zh-CN")
        assertThat(AppearanceStore.matchTag(Locale.forLanguageTag("zh-Hant-TW"))).isEqualTo("zh-CN")
        assertThat(AppearanceStore.matchTag(Locale.forLanguageTag("en-GB"))).isEqualTo("en")
    }

    @Test
    fun `an unsupported locale matches nothing`() {
        assertThat(AppearanceStore.matchTag(Locale.forLanguageTag("de-DE"))).isNull()
    }

    /** 声明支持的语言必须和 locales_config.xml 里的一致，否则 API 33 的系统语言选单会对不上。 */
    @Test
    fun `every language with a tag is declared in locales config`() {
        val declared = java.io.File("src/main/res/xml/locales_config.xml")
            .readText()
            .let { Regex("""android:name="([^"]+)"""").findAll(it) }
            .map { it.groupValues[1] }
            .toSet()

        val supported = AppLanguage.entries.mapNotNull { it.tag }.toSet()
        assertThat(declared).isEqualTo(supported)
    }

    @Test
    fun `theme modes are distinct`() {
        assertThat(ThemeMode.entries.map { it.labelRes }).containsNoDuplicates()
    }
}
