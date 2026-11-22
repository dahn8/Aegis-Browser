package dev.aegis.browser.translate

import dev.aegis.browser.R

/** 可选的翻译目标语言：code 是本应用内部标识，另外带腾讯 / Google 各自的写法 */
data class TranslateLanguage(
    val code: String,
    val displayName: String,
    val tencentCode: String,
    val googleCode: String,
    /** 本地化显示名资源；为 0 时用 displayName */
    val nameRes: Int = 0
)

object TranslateLanguages {

    const val DEFAULT = "zh-CN"

    val all: List<TranslateLanguage> = listOf(
        TranslateLanguage("zh-CN", "简体中文", "zh", "zh-CN", R.string.lang_zh_cn),
        TranslateLanguage("zh-TW", "繁体中文", "zh-TW", "zh-TW", R.string.lang_zh_tw),
        TranslateLanguage("en", "英语", "en", "en", R.string.lang_en),
        TranslateLanguage("ja", "日语", "ja", "ja", R.string.lang_ja),
        TranslateLanguage("ko", "韩语", "ko", "ko", R.string.lang_ko),
        TranslateLanguage("fr", "法语", "fr", "fr", R.string.lang_fr),
        TranslateLanguage("de", "德语", "de", "de", R.string.lang_de),
        TranslateLanguage("es", "西班牙语", "es", "es", R.string.lang_es),
        TranslateLanguage("ru", "俄语", "ru", "ru", R.string.lang_ru),
        TranslateLanguage("pt", "葡萄牙语", "pt", "pt", R.string.lang_pt),
        TranslateLanguage("it", "意大利语", "it", "it", R.string.lang_it),
        TranslateLanguage("ar", "阿拉伯语", "ar", "ar", R.string.lang_ar),
        TranslateLanguage("th", "泰语", "th", "th", R.string.lang_th),
        TranslateLanguage("vi", "越南语", "vi", "vi", R.string.lang_vi),
        TranslateLanguage("id", "印尼语", "id", "id", R.string.lang_id),
        TranslateLanguage("ms", "马来语", "ms", "ms", R.string.lang_ms),
        TranslateLanguage("tr", "土耳其语", "tr", "tr", R.string.lang_tr),
        TranslateLanguage("hi", "印地语", "hi", "hi", R.string.lang_hi),
        TranslateLanguage("nl", "荷兰语", "nl", "nl", R.string.lang_nl),
        TranslateLanguage("pl", "波兰语", "pl", "pl", R.string.lang_pl)
    )

    /** 大模型 MT 指令里用的语言英文名（Hunyuan-MT 用 WMT25 指令格式） */
    private val mtNames = mapOf(
        "zh-CN" to "Simplified Chinese",
        "zh-TW" to "Traditional Chinese",
        "en" to "English",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "ru" to "Russian",
        "pt" to "Portuguese",
        "it" to "Italian",
        "ar" to "Arabic",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "ms" to "Malay",
        "tr" to "Turkish",
        "hi" to "Hindi",
        "nl" to "Dutch",
        "pl" to "Polish"
    )

    fun mtName(code: String?): String = mtNames[code] ?: "Simplified Chinese"

    fun byCode(code: String?): TranslateLanguage =
        all.firstOrNull { it.code == code } ?: all.first()

    fun displayName(code: String?): String = byCode(code).displayName

    /** 当前应用语言下的目标语言名 */
    fun displayName(context: android.content.Context, code: String?): String {
        val lang = byCode(code)
        return if (lang.nameRes != 0) context.getString(lang.nameRes) else lang.displayName
    }

    /** 当前应用语言下的目标语言名列表（顺序与 all 一致） */
    fun labels(context: android.content.Context): List<String> =
        all.map { if (it.nameRes != 0) context.getString(it.nameRes) else it.displayName }

    /** 腾讯交互翻译只支持译成简体中文和英语，其它目标语言要走 Google */
    fun isTencentSupported(code: String?): Boolean = code == "zh-CN" || code == "en"

    /** 页面 lang 属性是否是我们认识的源语言（不认识就交给 auto 检测） */
    fun isKnownSource(code: String?): Boolean =
        code != null && all.any { it.code == code }
}

class TranslateTargetStore(context: android.content.Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aegis_translate", android.content.Context.MODE_PRIVATE)

    var target: String
        get() = prefs.getString("target", TranslateLanguages.DEFAULT) ?: TranslateLanguages.DEFAULT
        set(value) {
            prefs.edit().putString("target", value).apply()
        }
}
