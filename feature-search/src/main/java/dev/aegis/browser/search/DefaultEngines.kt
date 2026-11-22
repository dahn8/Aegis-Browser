package dev.aegis.browser.search

/**
 * 内置搜索引擎。国内可直连的引擎排在前面，默认使用必应（Bing 中国版）。
 */
object DefaultEngines {

    const val DEFAULT_ID = "bing_cn"

    val ALL: List<SearchEngine> = listOf(
        SearchEngine(
            id = "bing_cn",
            name = "必应",
            nameRes = R.string.engine_bing,
            searchTemplate = "https://cn.bing.com/search?q=%s",
            suggestTemplate = "https://cn.bing.com/osjson.aspx?query=%s"
        ),
        SearchEngine(
            id = "baidu",
            name = "百度",
            nameRes = R.string.engine_baidu,
            searchTemplate = "https://www.baidu.com/s?wd=%s",
            suggestTemplate = "https://suggestion.baidu.com/su?wd=%s&action=opensearch"
        ),
        SearchEngine(
            id = "sogou",
            name = "搜狗",
            nameRes = R.string.engine_sogou,
            // 直接用移动端结果页：PC 域名 www.sogou.com/web 在移动 UA 下会被
            // 302 到 m.sogou.com 首页并丢掉关键词，导致停在首页。
            searchTemplate = "https://m.sogou.com/web/searchList.jsp?keyword=%s"
        ),
        SearchEngine(
            id = "so360",
            name = "360 搜索",
            nameRes = R.string.engine_360,
            searchTemplate = "https://www.so.com/s?q=%s"
        ),
        SearchEngine(
            id = "shenma",
            name = "神马",
            nameRes = R.string.engine_shenma,
            searchTemplate = "https://m.sm.cn/s?q=%s"
        ),
        SearchEngine(
            id = "google",
            name = "Google",
            searchTemplate = "https://www.google.com/search?q=%s",
            suggestTemplate = "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"
        ),
        SearchEngine(
            id = "yandex",
            name = "Yandex",
            searchTemplate = "https://yandex.com/search/?text=%s"
        )
    )

    fun default(): SearchEngine = ALL.first { it.id == DEFAULT_ID }
}
