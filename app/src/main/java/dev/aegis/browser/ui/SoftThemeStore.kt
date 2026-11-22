package dev.aegis.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import dev.aegis.browser.R

/** 一套柔和配色：名称 + 纵向双色渐变（上浅下略深），0 表示保持默认灰白 */
data class SoftPalette(val nameRes: Int, val top: Int, val bottom: Int)

/**
 * 应用主题色板：全部低饱和、很浅的柔和双色，用来给顶部地址栏胶囊和底部导航栏上色。
 * 第一项 default（0/0）表示沿用原来的灰白样式。
 */
object SoftThemes {

    val all: List<SoftPalette> = listOf(
        SoftPalette(R.string.app_theme_default, 0, 0),
        SoftPalette(R.string.app_theme_blue, 0xFFDBE8F8.toInt(), 0xFFC6DAF2.toInt()),
        SoftPalette(R.string.app_theme_cyan, 0xFFD6EDEA.toInt(), 0xFFC0E2DE.toInt()),
        SoftPalette(R.string.app_theme_green, 0xFFDEF0DE.toInt(), 0xFFC9E4C7.toInt()),
        SoftPalette(R.string.app_theme_sage, 0xFFE3EDE5.toInt(), 0xFFCEDFD2.toInt()),
        SoftPalette(R.string.app_theme_yellow, 0xFFF7F1D7.toInt(), 0xFFEFE3B8.toInt()),
        SoftPalette(R.string.app_theme_orange, 0xFFFAE8DB.toInt(), 0xFFF2D4BD.toInt()),
        SoftPalette(R.string.app_theme_red, 0xFFF9DFDD.toInt(), 0xFFF1C7C3.toInt()),
        SoftPalette(R.string.app_theme_pink, 0xFFFAE2EC.toInt(), 0xFFF2C9D9.toInt()),
        SoftPalette(R.string.app_theme_purple, 0xFFEAE4F6.toInt(), 0xFFD7CCEF.toInt())
    )

    fun palette(index: Int): SoftPalette = all.getOrElse(index) { all[0] }

    fun isDefault(palette: SoftPalette): Boolean = palette.top == 0 || palette.bottom == 0

    /** 深色模式下把浅色压暗：保留色相、稍微提饱和，压到比深色底稍亮的低亮度，避免糊成灰 */
    fun darken(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 2.0f).coerceIn(0.10f, 0.30f)
        hsv[2] = 0.150f + hsv[2] * 0.105f
        return Color.HSVToColor(hsv)
    }

    /** 预览卡片的页面底色：把主题色稀释到很浅 */
    fun pageTint(color: Int, dark: Boolean): Int {
        if (dark) return darken(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val f = 0.22f
        return Color.rgb(
            (0xFF * (1 - f) + r * f).toInt(),
            (0xFF * (1 - f) + g * f).toInt(),
            (0xFF * (1 - f) + b * f).toInt()
        )
    }
}

/** 控件实际使用的两种底板：胶囊（圆角）与底栏（直角） */
object SoftThemeChrome {

    fun pill(top: Int, bottom: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        ).apply { cornerRadius = radiusPx.toFloat() }

    fun bar(top: Int, bottom: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )

    /** 顶部通栏的淡底：直角纯色 */
    fun wash(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }

    /** 色板圆点：0/0 的默认项画灰白渐变，其余按主题色 */
    fun swatch(top: Int, bottom: Int, strokePx: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (top == 0 || bottom == 0) {
                intArrayOf(0xFFF6F7F9.toInt(), 0xFFE6E8EC.toInt())
            } else {
                intArrayOf(top, bottom)
            }
        ).apply {
            shape = GradientDrawable.OVAL
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }
}

/** 主题选择持久化：只存色板下标，默认 0（灰白） */
class SoftThemeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aegis_app", Context.MODE_PRIVATE)

    var index: Int
        get() = prefs.getInt(KEY_THEME_INDEX, 0).coerceIn(0, SoftThemes.all.lastIndex)
        set(value) {
            prefs.edit().putInt(KEY_THEME_INDEX, value.coerceIn(0, SoftThemes.all.lastIndex)).apply()
        }

    fun palette(): SoftPalette = SoftThemes.palette(index)

    private companion object {
        const val KEY_THEME_INDEX = "soft_theme_index"
    }
}
