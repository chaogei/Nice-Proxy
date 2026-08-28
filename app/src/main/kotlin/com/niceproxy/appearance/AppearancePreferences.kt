package com.niceproxy.appearance

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.niceproxy.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 外观主题。跟随系统是默认值：绝大多数人已经在系统里表达过一次偏好了。 */
enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.settings_theme_system),
    LIGHT(R.string.settings_theme_light),
    DARK(R.string.settings_theme_dark),
}

/**
 * 界面语言（FR-7.2）。
 *
 * [tag] 为 null 表示交还给系统。刻意只列出真正有翻译的两种语言：
 * 摆一个「日本語」出来却回落成中文，比不提供选项更糟。
 */
enum class AppLanguage(val tag: String?, @StringRes val labelRes: Int) {
    SYSTEM(null, R.string.settings_language_system),
    CHINESE("zh-CN", R.string.settings_language_chinese),
    ENGLISH("en", R.string.settings_language_english),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
    }
}

/**
 * 主题与语言的落盘位置。
 *
 * 和 `OnboardingPreferences` 一样刻意避开 `core:datastore`：那里存的是内核配置，
 * 会随备份导出、也会被恢复覆盖。把别人手机上的界面语言恢复到你这台设备上，
 * 是一次谁都没要求过的意外。
 *
 * 用 SharedPreferences 而不是 DataStore，同样是因为**首帧之前**就得拿到值：
 * 语言要在 `attachBaseContext` 里生效，那时 Hilt 还没注入、协程也还没地方跑；
 * 主题晚一步则会先闪一下浅色再切深色。
 *
 * 做成 object 而不是注入式单例，正是为了 `attachBaseContext` 这条路径 ——
 * 那个回调早于 `onCreate`，拿不到任何注入进来的东西。
 */
object AppearanceStore {

    private const val FILE_NAME = "appearance"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language_tag"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): ThemeMode =
        prefs(context).getString(KEY_THEME, null)
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.SYSTEM

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.name).apply()
    }

    /**
     * API 33 起系统自己就是这份偏好的权威来源 —— 用户可以在「系统设置 → 应用 →
     * 语言」里直接改，那条路径绕过我们。所以在新系统上以 [LocaleManager] 为准，
     * 只把本地这份当缓存。
     */
    fun language(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val system = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
            if (system != null) return AppLanguage.fromTag(matchTag(system))
            // 空 LocaleList 是明确的「跟随系统」，不要回落到缓存里的旧值
            if (prefs(context).contains(KEY_LANGUAGE)) return AppLanguage.SYSTEM
        }
        return AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))
    }

    /**
     * @return true 表示调用方需要自己重建 Activity。API 33+ 上系统会代劳，
     *         重复调用 `recreate()` 会让界面闪两次。
     */
    fun setLanguage(context: Context, language: AppLanguage): Boolean {
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
            return false
        }
        return true
    }

    /**
     * 在 `attachBaseContext` 里给 Context 套上选定的语言。
     *
     * API 33+ 上直接原样返回：系统已经在创建 Context 时就把语言应用好了，
     * 我们再包一层只会和「系统设置里改语言」打架。
     */
    fun wrapLocale(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = language(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = android.content.res.Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    /**
     * 把系统给的 Locale 收敛到我们支持的那几个标签上。
     *
     * 系统存的可能是 `zh-Hans-CN` 这类带脚本的完整标签，直接和 `zh-CN` 比字符串
     * 永远不相等 —— 症状是设置页的选中项每次进去都弹回「跟随系统」。
     */
    private fun matchTag(locale: Locale): String? = AppLanguage.entries
        .mapNotNull { it.tag }
        .firstOrNull { Locale.forLanguageTag(it).language == locale.language }
}

/**
 * [AppearanceStore] 的可观察外壳，供 ViewModel 与 Compose 使用。
 *
 * 值在构造时同步读一次，因此首帧就是终态：不会先用浅色画一帧再切成深色。
 */
@Singleton
class AppearancePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _themeMode = MutableStateFlow(AppearanceStore.themeMode(context))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(AppearanceStore.language(context))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        AppearanceStore.setThemeMode(context, mode)
        _themeMode.value = mode
    }

    /** @return true 表示调用方需要重建 Activity，见 [AppearanceStore.setLanguage]。 */
    fun setLanguage(language: AppLanguage): Boolean {
        val needsRecreate = AppearanceStore.setLanguage(context, language)
        _language.value = language
        return needsRecreate
    }

    /** 系统设置里改过语言之后回到应用，内存里这份可能已经过期。 */
    fun refreshLanguage() {
        _language.value = AppearanceStore.language(context)
    }
}
