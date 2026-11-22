package dev.aegis.browser.settings

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.home.HomeBackgroundStore
import dev.aegis.browser.ui.FontSizeStore
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.util.Locale

/**
 * 壁纸与字体：字号预览 + 滑块实时预览、壁纸入口、字体（系统字体）说明。
 * 字号写入 FontSizeStore，返回主页后由 MainActivity 应用到所有标签。
 */
class AppearanceSettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private lateinit var themeSettings: ThemeSettings
    private lateinit var fontSizeStore: FontSizeStore
    private lateinit var backgroundStore: HomeBackgroundStore
    private lateinit var container: LinearLayout

    private var dark = false
    private var surfaceColor = Color.WHITE
    private var spacerColor = 0xFFF2F3F5.toInt()
    private var dividerColor = 0x14000000
    private var headerTextColor = 0xFF8A8F98.toInt()
    private var valueColor = 0xFF8A8F98.toInt()

    private var previewText: TextView? = null
    private var levelLabel: TextView? = null
    private var fontSeek: SeekBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_appearance)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.appearance)

        fontSizeStore = FontSizeStore(this)
        backgroundStore = HomeBackgroundStore(this)
        container = findViewById(R.id.appearance_container)

        if (dark) {
            surfaceColor = 0xFF1B1E23.toInt()
            spacerColor = 0xFF0E1013.toInt()
            dividerColor = 0x1FFFFFFF
            headerTextColor = 0xFF9AA0A8.toInt()
            valueColor = 0xFF9AA0A8.toInt()
        }
        findViewById<View>(R.id.appearance_scroll).setBackgroundColor(spacerColor)

        buildUi()
    }

    private fun buildUi() {
        container.removeAllViews()

        addSection(null) { box ->
            box.addView(previewCard())
            box.addView(divider())
            box.addView(fontSliderCard())
            box.addView(divider())
            box.addView(fontResetRow())
        }

        addSection(null) { box ->
            box.addView(hintStrip(getString(R.string.font_applied_hint)))
        }

        addSection(null) { box ->
            addRow(box, getString(R.string.font_family), getString(R.string.system_font), null)
            addRow(
                box,
                getString(R.string.wallpaper),
                if (backgroundStore.hasCustomBackground()) getString(R.string.wallpaper_custom)
                else getString(R.string.wallpaper_default)
            ) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        }
    }

    // ---------------------------------------------------------------- 预览与滑块

    private fun previewCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        previewText = TextView(this).apply {
            text = getString(R.string.font_preview_text)
            setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            setLineSpacing(dp(6).toFloat(), 1f)
            textSize = previewSize(fontSizeStore.textZoom)
        }
        card.addView(previewText)
        return card
    }

    private fun fontSliderCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(TextView(this).apply {
            text = "A"
            textSize = 14f
            setTextColor(valueColor)
        })
        labelRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        levelLabel = TextView(this).apply {
            text = levelLabel(fontSizeStore.textZoom)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(valueColor)
        }
        labelRow.addView(levelLabel)
        labelRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        labelRow.addView(TextView(this).apply {
            text = "A"
            textSize = 26f
            setTextColor(valueColor)
        })
        card.addView(labelRow)

        val seek = SeekBar(this).apply {
            max = MAX_STEPS
            progress = ((fontSizeStore.textZoom - MIN_ZOOM) / STEP).coerceIn(0, MAX_STEPS)
            splitTrack = false
            // 左右留出滑块半径的余量，拖到两头时蓝色圆点不会被裁掉
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = zoomForProgress(progress)
                fontSizeStore.textZoom = zoom
                previewText?.textSize = previewSize(zoom)
                levelLabel?.text = levelLabel(zoom)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        card.addView(seek)
        fontSeek = seek
        return card
    }

    /** 恢复默认：字号回到标准 100% */
    private fun fontResetRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(surfaceColor)
            isClickable = true
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.font_size_reset)
            textSize = 15f
            setTextColor(accentColor())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val zoom = FontSizeStore.DEFAULT_ZOOM
            fontSizeStore.textZoom = zoom
            fontSeek?.progress = ((zoom - MIN_ZOOM) / STEP).coerceIn(0, MAX_STEPS)
            previewText?.textSize = previewSize(zoom)
            levelLabel?.text = levelLabel(zoom)
        }
        return row
    }

    private fun accentColor(): Int =
        if (dark) 0xFF8AB4FF.toInt() else 0xFF3B5BDB.toInt()

    private fun hintStrip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(valueColor)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setBackgroundColor(spacerColor)
    }

    // ---------------------------------------------------------------- 通用构件

    private fun addSection(header: String?, builder: (LinearLayout) -> Unit) {
        if (container.childCount > 0) {
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
                )
                setBackgroundColor(spacerColor)
            })
        }
        if (header != null) {
            container.addView(TextView(this).apply {
                text = header
                textSize = 13f
                setTextColor(headerTextColor)
                setPadding(dp(20), dp(12), dp(20), dp(6))
                setBackgroundColor(spacerColor)
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        builder(box)
        container.addView(box)
    }

    private fun addRow(box: LinearLayout, title: String, value: String?, onClick: (() -> Unit)?) {
        if (box.childCount > 0) box.addView(divider())
        val row = layoutInflater.inflate(R.layout.item_settings_row, box, false)
        row.findViewById<TextView>(R.id.settings_row_title).text = title
        val valueView = row.findViewById<TextView>(R.id.settings_row_value)
        if (value.isNullOrBlank()) {
            valueView.visibility = View.GONE
        } else {
            valueView.text = value
            valueView.visibility = View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.settings_row_arrow).visibility =
            if (onClick != null) View.VISIBLE else View.GONE
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }
        box.addView(row)
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).apply { marginStart = dp(20) }
        setBackgroundColor(dividerColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 字号换算

    private fun zoomForProgress(progress: Int): Int =
        (MIN_ZOOM + progress * STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** 预览文字随字号档位线性变化：14sp ~ 22sp */
    private fun previewSize(zoom: Int): Float =
        14f + (zoom - MIN_ZOOM) * 8f / (MAX_ZOOM - MIN_ZOOM).toFloat()

    private fun levelLabel(zoom: Int): String = when (zoom) {
        FontSizeStore.ZOOM_SMALL -> getString(R.string.font_small)
        FontSizeStore.ZOOM_NORMAL -> getString(R.string.font_normal)
        FontSizeStore.ZOOM_LARGE -> getString(R.string.font_large)
        FontSizeStore.ZOOM_HUGE -> getString(R.string.font_huge)
        else -> String.format(Locale.US, "%d%%", zoom)
    }

    companion object {
        private const val MIN_ZOOM = 85
        private const val MAX_ZOOM = 150
        private const val STEP = 5
        private const val MAX_STEPS = (MAX_ZOOM - MIN_ZOOM) / STEP
    }
}
