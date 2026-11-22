package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.core.web.DesktopMode
import dev.aegis.browser.core.web.UserAgentMode
import dev.aegis.browser.privacy.PrivacyInterceptor
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * Google Material 3 风格网页设置页：UA 标识、双指缩放、安全拦截统计与高级缓存控制。
 */
class WebSettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var themeSettings: ThemeSettings
    private lateinit var interceptor: PrivacyInterceptor
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var container: LinearLayout
    private lateinit var builder: SettingsSectionBuilder

    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_web_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.web_settings)

        interceptor = PrivacyInterceptor.get(this)
        browserPrefs = BrowserPrefs(this)
        container = findViewById(R.id.web_settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.web_settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_browsing)) { box ->
            builder.row(
                box,
                title = getString(R.string.browser_ua),
                value = WebSettingsHelper.uaLabel(this)
            ) { showUaPicker() }

            builder.switchRow(
                box,
                title = getString(R.string.pc_to_mobile),
                checked = DesktopMode.isPcToMobile(this),
                subtitle = getString(R.string.pc_to_mobile_hint)
            ) { checked -> DesktopMode.setPcToMobile(this, checked) }

            builder.switchRow(
                box,
                title = getString(R.string.zoom_enabled),
                checked = browserPrefs.zoomEnabled,
                subtitle = getString(R.string.zoom_enabled_desc)
            ) { checked -> browserPrefs.zoomEnabled = checked }
        }

        builder.section(getString(R.string.web_security)) { box ->
            box.addView(statsRow())
            builder.divider(box)
            builder.switchRow(
                box,
                title = getString(R.string.block_ads),
                checked = interceptor.adsBlockEnabled
            ) { checked -> interceptor.adsBlockEnabled = checked }

            builder.switchRow(
                box,
                title = getString(R.string.block_external_jump),
                checked = browserPrefs.blockExternalEnabled
            ) { checked -> browserPrefs.blockExternalEnabled = checked }

            builder.row(box, getString(R.string.reset_stats)) {
                interceptor.resetBlocked()
                browserPrefs.resetExternalBlocked()
                buildUi()
                Toast.makeText(this, R.string.stats_reset, Toast.LENGTH_SHORT).show()
            }
        }

        builder.section(getString(R.string.perm_settings)) { box ->
            builder.row(box, getString(R.string.perm_settings)) {
                startActivity(Intent(this, SitePermissionsActivity::class.java))
            }
        }

        builder.section(getString(R.string.advanced)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.back_no_reload),
                checked = BackNoReload.enabled(this),
                subtitle = getString(R.string.back_no_reload_desc)
            ) { checked -> BackNoReload.setEnabled(this, checked) }

            val exceptions = BackNoReload.exceptions(this)
            builder.row(
                box,
                title = getString(R.string.back_no_reload_exceptions),
                value = if (exceptions.isEmpty()) getString(R.string.back_no_reload_empty)
                else exceptions.joinToString("、")
            ) { editExceptions() }
        }
    }

    private fun editExceptions() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_host_hint)
            setSingleLine()
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val current = BackNoReload.exceptions(this)
        val dlg = AegisDialog.Builder(this)
            .setTitle(R.string.back_no_reload_exceptions)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                BackNoReload.addException(this, input.text.toString())
                buildUi()
            }
            .setNeutralButton(R.string.delete) { _, _ -> showExceptionList() }
            .setNegativeButton(R.string.cancel, null)
        if (current.isNotEmpty()) dlg.setMessage(current.joinToString("\n"))
        dlg.create().show()
    }

    private fun showExceptionList() {
        val current = BackNoReload.exceptions(this)
        if (current.isEmpty()) {
            Toast.makeText(this, R.string.back_no_reload_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.back_no_reload_exceptions)
            .setItems(current.toTypedArray()) { _, which ->
                BackNoReload.removeException(this, current[which])
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun statsRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        row.addView(statCell(interceptor.blockedCount, getString(R.string.ads_blocked)))
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(40))
            setBackgroundColor(getColor(if (dark) R.color.m3_divider_dark else R.color.m3_divider))
        })
        row.addView(statCell(browserPrefs.externalBlockedCount, getString(R.string.external_blocked)))
        return row
    }

    private fun statCell(count: Int, label: String): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(this).apply {
            text = count.toString()
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
        })
        cell.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(getColor(if (dark) R.color.m3_subtitle_dark else R.color.m3_subtitle))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        })
        return cell
    }

    private fun showUaPicker() {
        val modes = arrayOf(UserAgentMode.ANDROID, UserAgentMode.IPHONE, UserAgentMode.DESKTOP)
        val labels = arrayOf(
            getString(R.string.ua_android),
            getString(R.string.ua_iphone),
            getString(R.string.ua_desktop)
        )
        val current = modes.indexOf(UserAgentMode.get(this)).coerceAtLeast(0)
        AegisDialog.Builder(this)
            .setTitle(R.string.ua_picker_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                UserAgentMode.set(this, modes[which])
                buildUi()
                Toast.makeText(this, R.string.ua_hint, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
