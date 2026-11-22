package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import dev.aegis.browser.R
import dev.aegis.browser.home.HomeBackgroundStore
import dev.aegis.browser.home.WeatherStore
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.SoftPalette
import dev.aegis.browser.ui.SoftThemeChrome
import dev.aegis.browser.ui.SoftThemeStore
import dev.aegis.browser.ui.SoftThemes
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 修改应用主题（Beta）：柔和配色面板。
 * 只给顶部地址栏胶囊与底部导航栏上色，不动网页内容；第一项灰白即原来的默认样式。
 */
class AppThemeActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var store: SoftThemeStore
    private lateinit var themeSettings: ThemeSettings
    private lateinit var backgroundStore: HomeBackgroundStore
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_app_theme)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.app_theme_title)

        store = SoftThemeStore(this)
        backgroundStore = HomeBackgroundStore(this)
        container = findViewById(R.id.app_theme_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.app_theme_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(null) { box ->
            box.addView(previewRow())
        }

        builder.section(getString(R.string.app_theme_colors)) { box ->
            box.addView(swatchGrid())
        }

        builder.section(getString(R.string.app_theme_image)) { box ->
            builder.row(
                box,
                getString(R.string.wallpaper),
                if (backgroundStore.hasCustomBackground()) getString(R.string.wallpaper_custom)
                else getString(R.string.wallpaper_default)
            ) { startActivity(Intent(this, WallpaperActivity::class.java)) }
            if (backgroundStore.hasCustomBackground()) {
                builder.row(box, getString(R.string.reset_background), null) {
                    backgroundStore.setBackground(null)
                    buildUi()
                }
            }
        }

        builder.section(getString(R.string.group_theme_mode)) { box ->
            builder.checkRow(
                box = box,
                title = getString(R.string.theme_light),
                selected = themeSettings.themeMode == ThemeManager.MODE_LIGHT
            ) { switchMode(ThemeManager.MODE_LIGHT) }
            builder.checkRow(
                box = box,
                title = getString(R.string.theme_dark),
                selected = themeSettings.themeMode == ThemeManager.MODE_DARK
            ) { switchMode(ThemeManager.MODE_DARK) }
            builder.checkRow(
                box = box,
                title = getString(R.string.theme_system),
                selected = themeSettings.themeMode == ThemeManager.MODE_SYSTEM
            ) { switchMode(ThemeManager.MODE_SYSTEM) }
        }
    }

    private fun switchMode(mode: Int) {
        if (themeSettings.themeMode == mode) return
        themeSettings.themeMode = mode
        // 深浅色变了，本页按新主题重建，主页在 onResume 会自行重建
        recreate()
    }

    /** 预览用的两种底板颜色：默认项还原灰白，深色模式自动压暗 */
    private fun chromeColors(): Pair<Int, Int> {
        val palette = store.palette()
        if (SoftThemes.isDefault(palette)) {
            return if (dark) {
                0xFF24272C.toInt() to 0xFF1D2025.toInt()
            } else {
                0xFFF2F3F5.toInt() to 0xFFE7E9ED.toInt()
            }
        }
        return if (dark) {
            SoftThemes.darken(palette.top) to SoftThemes.darken(palette.bottom)
        } else {
            palette.top to palette.bottom
        }
    }

    // ---------------------------------------------------------------- 预览

    private fun previewRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setBackgroundColor(builder.surfaceColor)
        }
        row.addView(tile(getString(R.string.app_theme_preview_home), false), weighted())
        row.addView(
            View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) }
        )
        row.addView(tile(getString(R.string.app_theme_preview_web), true), weighted())
        return row
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** 主页不跟着配色走，预览里保持原灰白 */
    private fun homeChromeColors(): Pair<Int, Int> =
        if (dark) 0xFF2A2E34.toInt() to 0xFF212429.toInt()
        else 0xFFFDFDFE.toInt() to 0xFFE9EBEF.toInt()

    private val iconGray: Int
        get() = if (dark) 0xFF7C838D.toInt() else 0xFFB7BDC6.toInt()

    private val chipGray: Int
        get() = if (dark) 0xFF3C424A.toInt() else 0xFFE4E7EB.toInt()

    /** 主页不跟着配色走：还原灰白渐变 */
    private fun homeSurface(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (dark) intArrayOf(0xFF2B2F35.toInt(), 0xFF1E2126.toInt())
        else intArrayOf(0xFFFFFFFF.toInt(), 0xFFEAECF0.toInt())
    ).apply { cornerRadius = dp(16).toFloat() }

    private fun pageSurface(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(pageTintColor())
        cornerRadius = dp(16).toFloat()
    }

    private fun tile(label: String, browsing: Boolean): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            )
            background = if (browsing) pageSurface() else homeSurface()
            clipToOutline = true
        }
        card.addView(
            if (browsing) browseMock() else homeMock(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        holder.addView(card)
        holder.addView(TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        })
        return holder
    }

    /** 迷你主页：左上天气（开启时）+ 右上页面设置 → 搜索胶囊 → 快捷方式 → 底部导航 */
    private fun homeMock(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), 0)
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (WeatherStore(this@AppThemeActivity).shown) {
            topRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(if (dark) 0xFF565D66.toInt() else 0xFFBFC5CD.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(4))
            })
        }
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(navIcon(R.drawable.ic_page_settings, dp(11)))
        column.addView(
            topRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        )
        column.addView(
            homePillMock(dp(22), dp(11)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
            ).also { it.topMargin = dp(5) }
        )
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(4) { index ->
            icons.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(chipGray)
                }
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also {
                    it.marginStart = if (index == 0) 0 else dp(9)
                }
            })
        }
        column.addView(
            icons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(14) }
        )
        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        column.addView(
            navStrip(dp(30), if (dark) 0xFF23262B.toInt() else 0xFFF4F5F7.toInt(), dp(11)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        )
        return column
    }

    /** 迷你浏览页：主题色通栏（胶囊不变色）+ 正文 + 主题色底栏 */
    private fun browseMock(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = SoftThemeChrome.bar(chromeColors().first, chromeColors().second)
        }
        topBar.addView(
            homePillMock(dp(19), dp(9)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(19)
            )
        )
        column.addView(
            topBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        )
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), 0)
        }
        body.addView(contentLines())
        column.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        column.addView(
            navStrip(dp(30), chromeColors().second, dp(11)),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
        )
        return column
    }

    /** 胶囊：与真实页面一样保持默认灰白，不随主题色变化 */
    private fun homePillMock(height: Int, radius: Int): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = radius.toFloat()
                setColor(if (dark) 0xFF34383E.toInt() else 0xFFFFFFFF.toInt())
                setStroke(dp(1), if (dark) 0xFF454B53.toInt() else 0xFFE3E6EA.toInt())
            }
        }
        val dotSize = if (height >= dp(20)) dp(7) else dp(6)
        pill.addView(mockDot(dotSize), LinearLayout.LayoutParams(dotSize, dotSize))
        pill.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pill.addView(mockDot(dotSize), LinearLayout.LayoutParams(dotSize, dotSize))
        return pill
    }

    private fun mockDot(size: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(iconGray)
        }
    }

    /** 底部导航：五个图标，与真实底栏一一对应 */
    private fun navStrip(height: Int, bgColor: Int, iconSize: Int): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
        }
        repeat(5) { index ->
            val slot = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                )
            }
            when (index) {
                0 -> slot.addView(navIcon(R.drawable.ic_back, iconSize))
                1 -> slot.addView(navIcon(R.drawable.ic_forward, iconSize))
                2 -> slot.addView(navIcon(R.drawable.ic_add, iconSize))
                3 -> slot.addView(tabBadge(iconSize))
                else -> slot.addView(navIcon(R.drawable.ic_menu, iconSize))
            }
            bar.addView(slot)
        }
        return bar
    }

    private fun navIcon(resId: Int, sizePx: Int): ImageView = ImageView(this).apply {
        setImageResource(resId)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = ColorStateList.valueOf(iconGray)
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
    }

    /** 标签页按钮：底栏中间那个带数字的圆圈，迷你版画成空心圈 */
    private fun tabBadge(iconSize: Int): View {
        val size = (iconSize * 0.8f).toInt()
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0)
                setStroke(dp(1), iconGray)
            }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
    }

    private fun contentLines(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val widths = floatArrayOf(1f, 0.82f, 0.92f, 0.6f, 0.88f)
        widths.forEachIndexed { index, ratio ->
            val line = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(if (dark) 0xFF3A3F45.toInt() else 0xFFDDE0E5.toInt())
                }
            }
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = if (index == 0) 0 else dp(7) }
            }
            slot.addView(line, LinearLayout.LayoutParams(0, dp(4), ratio))
            if (ratio < 1f) {
                slot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f - ratio))
            }
            column.addView(slot)
        }
        return column
    }

    private fun pageTintColor(): Int {
        val palette = store.palette()
        val base = if (SoftThemes.isDefault(palette)) {
            if (dark) 0xFF1B1E23.toInt() else 0xFFF6F7F9.toInt()
        } else {
            SoftThemes.pageTint(palette.top, dark)
        }
        return base
    }

    // ---------------------------------------------------------------- 色板

    private fun swatchGrid(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(builder.surfaceColor)
        }
        val paletteList = SoftThemes.all
        val perRow = 5
        var cursor = 0
        while (cursor < paletteList.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { if (cursor > 0) it.topMargin = dp(10) }
            }
            val slice = paletteList.subList(cursor, minOf(cursor + perRow, paletteList.size))
            slice.forEachIndexed { offset, palette ->
                // 5 列等宽均分，圆点居中，窄屏也不会被裁掉
                val cell = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                }
                cell.addView(
                    swatch(palette, cursor + offset == store.index),
                    FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                )
                row.addView(cell)
            }
            // 最后一行不足 5 个时补空位，保证圆点对齐
            repeat(perRow - slice.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            grid.addView(row)
            cursor += perRow
        }
        return grid
    }

    private fun swatch(palette: SoftPalette, selected: Boolean): View {
        val strokeColor = if (dark) 0x66EDEFF2 else 0x553A3F45
        return View(this).apply {
            background = SoftThemeChrome.swatch(
                palette.top,
                palette.bottom,
                if (selected) dp(2) else 0,
                strokeColor.toInt()
            )
            isClickable = true
            setOnClickListener {
                store.index = SoftThemes.all.indexOf(palette)
                buildUi()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
