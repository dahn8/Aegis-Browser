package dev.aegis.browser.search

import android.content.Context
import android.net.Uri

/**
 * @param searchTemplate 搜索 URL 模板，用 %s 占位查询词
 * @param suggestTemplate 搜索建议 URL 模板，null 表示该引擎不支持建议
 */
data class SearchEngine(
    val id: String,
    val name: String,
    val searchTemplate: String,
    val suggestTemplate: String? = null,
    val builtIn: Boolean = true,
    /** 内置引擎的显示名资源；自定义引擎为 0，直接用 name */
    val nameRes: Int = 0
) {
    fun displayName(context: Context): String =
        if (nameRes != 0) context.getString(nameRes) else name

    fun buildSearchUrl(query: String): String =
        searchTemplate.replace(PLACEHOLDER, Uri.encode(query))

    fun buildSuggestUrl(query: String): String? =
        suggestTemplate?.replace(PLACEHOLDER, Uri.encode(query))

    companion object {
        const val PLACEHOLDER = "%s"
    }
}
