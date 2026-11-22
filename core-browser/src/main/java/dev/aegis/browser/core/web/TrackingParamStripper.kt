package dev.aegis.browser.core.web

import android.net.Uri

/**
 * URL 跟踪参数剥离：仅作用于主框架导航，移除常见分析/广告点击参数。
 * 使用 encodedQuery 字符串重建，保留原始编码，不改动其余参数顺序。
 */
object TrackingParamStripper {

    private val TRACKING_PARAMS = setOf(
        // Google Analytics
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_cid", "utm_reader", "utm_referrer",
        "utm_social", "utm_social_type", "utm_brand", "utm_pubreferrer", "utm_swu",
        // 广告点击标识
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
        "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
        "msclkid", "mc_cid", "mc_eid", "yclid", "twclid", "ttclid",
        "li_fat_id", "igshid", "awc", "_openstat",
        // 营销/邮件/其他
        "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv",
        "wickedid", "hsctatracking", "ref", "referrer",
        // 国内电商与统计
        "spm", "scm", "sn", "clickid", "track_id", "trackid",
        "union_track", "impid", "campaign_id", "ad_id", "creative_id",
        "keyword", "kw", "fr"
    )

    fun strip(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val encodedQuery = uri.encodedQuery
        if (encodedQuery.isNullOrEmpty()) return url

        val originParts = encodedQuery.split("&")
        val kept = originParts.filter { part ->
            val rawKey = part.substringBefore("=")
            val key = runCatching { Uri.decode(rawKey) }.getOrNull()?.lowercase().orEmpty()
            key.isNotEmpty() && key !in TRACKING_PARAMS
        }
        if (kept.size == originParts.size) return url

        val newQuery = kept.joinToString("&")
        val builder = uri.buildUpon()
        if (newQuery.isEmpty()) builder.encodedQuery(null) else builder.encodedQuery(newQuery)
        return runCatching { builder.build().toString() }.getOrElse { return url }
    }
}
