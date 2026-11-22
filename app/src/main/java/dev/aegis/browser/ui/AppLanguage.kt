package dev.aegis.browser.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言：只影响 Aegis 自己的界面文案，不改系统语言。
 * 各 Activity 在 attachBaseContext 里套一层，切完语言重建页面即生效。
 */
object AppLanguage {

    const val ZH_CN = "zh-CN"
    const val EN = "en"
    const val ZH_TW = "zh-TW"
    const val ZH_HK = "zh-HK"

    val options = listOf(ZH_CN, EN, ZH_TW, ZH_HK)

    private const val PREFS = "aegis_language"
    private const val KEY = "lang"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ZH_CN) ?: ZH_CN

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tag).apply()
    }

    /** 语言名用各自母语写法，不随界面语言变化 */
    fun displayName(tag: String): String = when (tag) {
        EN -> "English"
        ZH_TW -> "繁體中文（台灣）"
        ZH_HK -> "繁體中文（香港）"
        else -> "简体中文"
    }

    fun wrap(base: Context): Context {
        val tag = current(base)
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
