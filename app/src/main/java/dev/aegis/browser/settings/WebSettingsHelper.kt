package dev.aegis.browser.settings

import android.content.Context
import dev.aegis.browser.R
import dev.aegis.browser.core.web.UserAgentMode

/** 设置页里 UA 标识的可读名称 */
object WebSettingsHelper {

    fun uaLabel(context: Context): String = when (UserAgentMode.get(context)) {
        UserAgentMode.IPHONE -> context.getString(R.string.ua_iphone)
        UserAgentMode.DESKTOP -> context.getString(R.string.ua_desktop)
        else -> context.getString(R.string.ua_android)
    }
}
