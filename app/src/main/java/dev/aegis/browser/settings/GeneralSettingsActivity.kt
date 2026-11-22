package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.search.SearchEngineStore
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * Google Material 3 风格通用设置页：搜索引擎、屏幕方向、日/夜间模式、多标签启动恢复与默认无痕。
 */
class GeneralSettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var themeSettings: ThemeSettings
    private lateinit var engineStore: SearchEngineStore
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var container: LinearLayout
    private lateinit var builder: SettingsSectionBuilder

    private var dark = false
    private var shownMode = -1

    override fun onResume() {
        super.onResume()
        if (themeSettings.themeMode != shownMode || ThemeManager.isDarkNow(this, themeSettings) != dark) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        shownMode = themeSettings.themeMode
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_general_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        applyWindowColors()
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.general)

        engineStore = SearchEngineStore(this)
        browserPrefs = BrowserPrefs(this)
        container = findViewById(R.id.general_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.general_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun applyWindowColors() {
        val bg = getColor(if (dark) R.color.bg_dark else R.color.bg_light)
        window.setBackgroundDrawable(ColorDrawable(bg))
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun reapplyThemeInPlace() {
        val scrollY = (findViewById<View>(R.id.general_scroll) as? ScrollView)?.scrollY ?: 0
        dark = ThemeManager.isDarkNow(this, themeSettings)
        ThemeManager.apply(this, themeSettings, settingsScreen = true)
        setContentView(R.layout.activity_general_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        applyWindowColors()
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.general)
        container = findViewById(R.id.general_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.general_scroll).setBackgroundColor(builder.spacerColor)
        buildUi()
        findViewById<View>(R.id.general_scroll).post {
            (findViewById<View>(R.id.general_scroll) as? ScrollView)?.scrollTo(0, scrollY)
        }
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_general)) { box ->
            builder.row(
                box,
                title = getString(R.string.search_settings),
                value = engineStore.selected().displayName(this),
                iconRes = R.drawable.ic_search
            ) { startActivity(Intent(this, SearchSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.screen_orientation),
                value = ScreenOrientation.label(this, ScreenOrientation.current(this)),
                iconRes = R.drawable.ic_orientation
            ) { showOrientationPicker() }
        }

        builder.section(getString(R.string.group_theme)) { box ->
            box.addView(themeCards())
        }

        builder.section(getString(R.string.group_browsing)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.start_last_page),
                checked = browserPrefs.openLastPage,
                iconRes = R.drawable.ic_history,
                subtitle = getString(R.string.start_last_page_desc)
            ) { checked -> browserPrefs.openLastPage = checked }

            builder.switchRow(
                box,
                title = getString(R.string.start_in_incognito),
                checked = browserPrefs.startInIncognito,
                iconRes = R.drawable.ic_incognito,
                subtitle = getString(R.string.start_in_incognito_desc)
            ) { checked -> browserPrefs.startInIncognito = checked }
        }
    }

    private fun showOrientationPicker() {
        val values = ScreenOrientation.options
        val labels = values.map { ScreenOrientation.label(this, it) }.toTypedArray()
        val checked = values.indexOf(ScreenOrientation.current(this)).coerceAtLeast(0)
        AegisDialog.Builder(this)
            .setTitle(R.string.screen_orientation)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ScreenOrientation.set(this, values[which])
                ScreenOrientation.apply(this)
                dialog.dismiss()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun themeCards(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val gap = dp(10)
        row.addView(
            themeCard(R.drawable.ic_dark_mode, getString(R.string.theme_auto), ThemeManager.MODE_SYSTEM),
            LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = gap }
        )
        row.addView(
            themeCard(R.drawable.ic_moon, getString(R.string.night_mode), ThemeManager.MODE_DARK),
            LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = gap }
        )
        row.addView(
            themeCard(R.drawable.ic_sun, getString(R.string.day_mode), ThemeManager.MODE_LIGHT),
            LinearLayout.LayoutParams(0, dp(96), 1f)
        )
        return row
    }

    private fun themeCard(iconRes: Int, label: String, mode: Int): View {
        val selected = themeSettings.themeMode == mode
        val accent = getColor(if (dark) R.color.primary_dark else R.color.primary)
        val border = getColor(if (dark) R.color.pill_stroke_dark else R.color.pill_stroke_light)
        val tonalBg = getColor(if (dark) R.color.m3_tonal_container_dark else R.color.m3_tonal_container)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(6), dp(12))
            background = GradientDrawable().apply {
                setColor(if (selected) tonalBg else builder.surfaceColor)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(if (selected) 2 else 1), if (selected) accent else border)
            }
            isClickable = true
            setOnClickListener {
                if (themeSettings.themeMode != mode) {
                    themeSettings.themeMode = mode
                    shownMode = mode
                    window.decorView.post { reapplyThemeInPlace() }
                }
            }
        }
        card.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (selected) accent else if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        card.addView(TextView(this).apply {
            text = label
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(if (selected) accent else if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
