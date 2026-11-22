package dev.aegis.browser.core.web

import android.content.Context
import android.net.Uri

/**
 * 站点权限策略：剪贴板 / 打开应用 / 位置 / 摄像头 / 麦克风 / 页面重定向 / 振动。
 *
 * 三档：allow（允许）、ask（每次询问）、block（禁止）。
 * 摄像头、麦克风、位置、打开应用在 WebViewClient / WebChromeClient 里真实生效；
 * 剪贴板、页面重定向、振动 系统 WebView 未暴露对应钩子，仅作为策略记录。
 */
object SitePermissions {

    const val CLIPBOARD = "clipboard"
    const val OPEN_APP = "open_app"
    const val LOCATION = "location"
    const val CAMERA = "camera"
    const val MIC = "mic"
    const val REDIRECT = "redirect"
    const val VIBRATE = "vibrate"

    const val ALLOW = "allow"
    const val ASK = "ask"
    const val BLOCK = "block"

    val keys = listOf(CLIPBOARD, OPEN_APP, LOCATION, CAMERA, MIC, REDIRECT, VIBRATE)

    private fun defaults(key: String): String = when (key) {
        CLIPBOARD, REDIRECT -> ALLOW
        OPEN_APP, CAMERA, MIC -> ASK
        else -> BLOCK
    }

    private const val PREFS = "aegis_site_perm"

    fun state(context: Context, key: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, defaults(key)) ?: defaults(key)

    fun set(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    /** 询问框：由 MainActivity 注入，避免 core 模块依赖 UI */
    fun interface Prompter {
        fun confirm(key: String, origin: String, callback: java.util.function.Consumer<Boolean>)
    }

    @Volatile
    var prompter: Prompter? = null

    /**
     * 按策略决定是否放行：allow 直接放行，block 直接拒绝，ask 弹一次询问。
     * 回调一定在调用线程之外的主线程执行（prompter 负责），无 prompter 时按拒绝处理。
     */
    fun decide(context: Context, key: String, hostLabel: String, onResult: (Boolean) -> Unit) {
        when (state(context, key)) {
            ALLOW -> onResult(true)
            BLOCK -> onResult(false)
            else -> {
                val prompt = prompter
                if (prompt == null) {
                    onResult(false)
                } else {
                    prompt.confirm(key, hostLabel) { granted -> onResult(granted) }
                }
            }
        }
    }

    fun hostOf(url: String?): String = runCatching {
        Uri.parse(url ?: "").host ?: ""
    }.getOrDefault("")
}
