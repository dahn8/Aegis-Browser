package dev.aegis.browser.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import dev.aegis.browser.R

/**
 * 屏幕方向设置：跟随系统 / 自动旋转 / 竖屏 / 横屏。
 * 只影响应用自己的页面，不改系统设置。
 */
object ScreenOrientation {

    const val FOLLOW = "follow"
    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    val options = listOf(FOLLOW, AUTO, PORTRAIT, LANDSCAPE)

    private const val PREFS = "aegis_app"
    private const val KEY = "screen_orientation"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, FOLLOW) ?: FOLLOW

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    fun label(context: Context, value: String): String = context.getString(
        when (value) {
            AUTO -> R.string.orientation_auto
            PORTRAIT -> R.string.orientation_portrait
            LANDSCAPE -> R.string.orientation_landscape
            else -> R.string.orientation_follow_system
        }
    )

    /** 在 Activity 启动时套用；看完设置页也能立刻转过去 */
    fun apply(activity: Activity) {
        activity.requestedOrientation = when (current(activity)) {
            AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
