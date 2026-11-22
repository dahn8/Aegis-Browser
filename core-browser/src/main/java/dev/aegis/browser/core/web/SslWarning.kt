package dev.aegis.browser.core.web

import android.webkit.SslErrorHandler
import android.webkit.WebView

/**
 * 证书异常的统一出口。
 *
 * 默认行为是不降级（直接 cancel）。主工程启动时注册 Handler，
 * 由它弹出风险提示，让用户选择「返回」或「仅本次继续」。errorCode 为 SslError.primaryError，文案由宿主本地化。
 */
object SslWarning {

    interface Handler {
        /** 由宿主弹窗处理；未注册 Handler 或未处理时调用方会取消连接 */
        fun onWarning(view: WebView, handler: SslErrorHandler, host: String, errorCode: Int)
    }

    @Volatile
    var handler: Handler? = null
}
