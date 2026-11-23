package dev.aegis.browser.privacy

import android.content.Context
import android.net.Uri
import dev.aegis.browser.core.web.BlockDecision
import dev.aegis.browser.core.web.RequestInterceptor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 隐私拦截器：规则在工作线程异步加载，加载完成前不拦截；
 * 内置站点白名单（内存缓存，避免每次请求重复拷贝）与累计拦截计数。
 *
 * 单例：MainActivity 与设置页共享同一实例，规则数与拦截计数保持一致。
 */
class PrivacyInterceptor private constructor(context: Context) : RequestInterceptor {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var engine: FilterEngine? = null

    @Volatile
    private var cachedWhitelist: Set<String> =
        prefs.getStringSet(KEY_WHITELIST, emptySet())?.map { it.lowercase() }?.toSet() ?: emptySet()

    private val blockedTotal = AtomicInteger(0)

    /** 最近拦截记录（用于 M6 命中与误伤排查），最多保留 [BLOCK_LOG_MAX] 条 */
    data class BlockedHit(
        val host: String,
        val pageHost: String?,
        val reason: String,
        val timeMs: Long
    )

    private val blockLog = ArrayDeque<BlockedHit>()

    init {
        reload()
    }

    fun reload() {
        Thread {
            engine = RuleLoader.load(appContext)
        }.start()
    }

    val blockedCount: Int get() = blockedTotal.get()

    /** 拦截广告总开关 */
    var adsBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADS_BLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADS_BLOCK, value).apply()
        }

    /** 清零拦截计数 */
    fun resetBlocked() {
        blockedTotal.set(0)
    }

    val ruleCount: Int get() = engine?.ruleCount ?: 0

    override fun intercept(uri: Uri, pageHost: String?, isForMainFrame: Boolean): BlockDecision? {
        if (!adsBlockEnabled) return null
        val current = engine ?: return null
        val host = uri.host ?: return null

        if (isWhitelisted(pageHost ?: host)) return null

        if (current.isBlocked(host)) {
            blockedTotal.incrementAndGet()
            recordBlock(host, pageHost, "filter")
            return BlockDecision(reason = "filter")
        }
        return null
    }

    /** 记录最近拦截，供隐私设置页的“拦截记录”核对命中与误伤 */
    @Synchronized
    private fun recordBlock(host: String, pageHost: String?, reason: String) {
        blockLog.addLast(BlockedHit(host, pageHost, reason, System.currentTimeMillis()))
        while (blockLog.size > BLOCK_LOG_MAX) {
            blockLog.removeFirst()
        }
    }

    /** 最近的拦截记录，按时间倒序（最新在前） */
    @Synchronized
    fun recentBlocks(): List<BlockedHit> = blockLog.toList().asReversed()

    @Synchronized
    fun clearBlockLog() {
        blockLog.clear()
    }

    /** 日志里出现过的不同域名数量，用于设置页展示 */
    fun loggedHostCount(): Int = recentBlocks().map { it.host }.toSet().size

    // 白名单
    fun whitelist(): Set<String> = cachedWhitelist

    fun isWhitelisted(host: String): Boolean =
        cachedWhitelist.contains(host.lowercase())

    fun addWhitelist(host: String) {
        val updated = cachedWhitelist + host.lowercase()
        cachedWhitelist = updated
        prefs.edit().putStringSet(KEY_WHITELIST, updated).apply()
    }

    fun removeWhitelist(host: String) {
        val updated = cachedWhitelist - host.lowercase()
        cachedWhitelist = updated
        prefs.edit().putStringSet(KEY_WHITELIST, updated).apply()
    }

    companion object {
        @Volatile
        private var instance: PrivacyInterceptor? = null

        fun get(context: Context): PrivacyInterceptor =
            instance ?: synchronized(this) {
                instance ?: PrivacyInterceptor(context.applicationContext)
                    .also { instance = it }
            }

        private const val PREFS_NAME = "aegis_privacy"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_ADS_BLOCK = "ads_block"
        private const val BLOCK_LOG_MAX = 200
    }
}
