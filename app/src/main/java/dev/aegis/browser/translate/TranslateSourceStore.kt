package dev.aegis.browser.translate

import android.content.Context

/**
 * 翻译源：腾讯交互翻译 / Microsoft 翻译 / 硅基流动 / 智谱 / Google
 */
enum class TranslateSource { TENCENT, MICROSOFT, SILICONFLOW, ZHIPU, GOOGLE }

class TranslateSourceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var source: TranslateSource
        get() = when (prefs.getString(KEY_SOURCE, null)) {
            VALUE_MICROSOFT -> TranslateSource.MICROSOFT
            VALUE_GOOGLE -> TranslateSource.GOOGLE
            VALUE_SILICONFLOW -> TranslateSource.SILICONFLOW
            VALUE_ZHIPU -> TranslateSource.ZHIPU
            else -> TranslateSource.TENCENT
        }
        set(value) {
            val stored = when (value) {
                TranslateSource.MICROSOFT -> VALUE_MICROSOFT
                TranslateSource.GOOGLE -> VALUE_GOOGLE
                TranslateSource.SILICONFLOW -> VALUE_SILICONFLOW
                TranslateSource.ZHIPU -> VALUE_ZHIPU
                TranslateSource.TENCENT -> VALUE_TENCENT
            }
            prefs.edit().putString(KEY_SOURCE, stored).apply()
        }

    private companion object {
        const val PREFS_NAME = "aegis_translate"
        const val KEY_SOURCE = "source"
        const val VALUE_TENCENT = "tencent"
        const val VALUE_MICROSOFT = "microsoft"
        const val VALUE_GOOGLE = "google"
        const val VALUE_SILICONFLOW = "siliconflow"
        const val VALUE_ZHIPU = "zhipu"
    }
}

/** 硅基流动 API Key：只存本机 prefs，不上传任何服务器 */
class SiliconFlowKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aegis_translate", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString("siliconflow_key", "").orEmpty()
        set(value) {
            prefs.edit().putString("siliconflow_key", value.trim()).apply()
        }

    fun hasKey(): Boolean = key.isNotBlank()
}

/** 智谱开放平台 API Key：同样只存本机 prefs，不上传任何服务器 */
class ZhipuKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aegis_translate", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString("zhipu_key", "").orEmpty()
        set(value) {
            prefs.edit().putString("zhipu_key", value.trim()).apply()
        }

    fun hasKey(): Boolean = key.isNotBlank()
}
