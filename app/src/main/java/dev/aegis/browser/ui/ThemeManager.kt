package dev.aegis.browser.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import dev.aegis.browser.R

/**
 * 主题应用：在各 Activity super.onCreate 之前调用 setTheme。
 * 所有页面均使用 NoActionBar 主题，标题栏由各布局内的统一简约标题栏承载。
 */
object ThemeManager {

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    /** 仅应用主题资源（在 super.onCreate 前调用），返回当前是否深色 */
    fun apply(activity: Activity, settings: ThemeSettings, settingsScreen: Boolean): Boolean {
        val dark = isDark(settings.themeMode, activity)
        val themeRes = when {
            settingsScreen && dark -> R.style.Theme_Aegis_Settings_Dark
            settingsScreen -> R.style.Theme_Aegis_Settings
            dark -> R.style.Theme_Aegis_Dark
            else -> R.style.Theme_Aegis
        }
        activity.setTheme(themeRes)
        return dark
    }

    /**
     * 状态栏图标外观：深色为白色图标（清除 light 标志），浅色为深色图标。
     * 必须在 super.onCreate/setContentView 之后、窗口与 DecorView 就绪时调用。
     */
    fun applyStatusBarAppearance(activity: Activity, dark: Boolean) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (dark) {
                controller.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            val decor = window.decorView
            decor.systemUiVisibility = if (dark) {
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    /** 当前设置下这一页应该是深色还是浅色（各页 onResume 用来判断要不要重建） */
    fun isDarkNow(activity: Activity, settings: ThemeSettings): Boolean =
        isDark(settings.themeMode, activity)

    private fun isDark(mode: Int, activity: Activity): Boolean = when (mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> {
            val night = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            night == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
