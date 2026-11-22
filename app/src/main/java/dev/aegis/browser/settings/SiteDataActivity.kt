package dev.aegis.browser.settings

import dev.aegis.browser.ui.AegisDialog
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 站点数据管理：列出访问过的站点，可逐个清除该站点的 Cookie 与本地存储，
 * 也可以一次清掉全部站点数据（不影响历史与书签）。
 */
class SiteDataActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_site_data)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.site_data)

        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)
        buildUi()
    }

    private fun buildUi() {
        builder.reset()
        val hosts = SiteDataCleaner.hosts(this)

        builder.section(getString(R.string.site_data)) { box ->
            builder.row(
                box,
                getString(R.string.site_data_clear_all),
                if (hosts.isEmpty()) null else getString(R.string.site_data_count, hosts.size)
            ) { confirmClearAll() }
            builder.note(box, getString(R.string.site_data_desc))
        }

        if (hosts.isEmpty()) {
            builder.section(null) { box ->
                builder.row(box, getString(R.string.site_data_none))
            }
            return
        }

        builder.section(getString(R.string.site_data_sites)) { box ->
            hosts.forEach { host ->
                builder.row(box, host, null) { confirmClearHost(host) }
            }
        }
    }

    private fun confirmClearHost(host: String) {
        AegisDialog.Builder(this)
            .setTitle(host)
            .setMessage(R.string.site_data_clear_msg)
            .setPositiveButton(R.string.confirm) { _, _ ->
                SiteDataCleaner.clearHost(this, host)
                Toast.makeText(this, R.string.site_data_cleared, Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AegisDialog.Builder(this)
            .setMessage(R.string.site_data_clear_all_msg)
            .setPositiveButton(R.string.confirm) { _, _ ->
                SiteDataCleaner.clearAll(this)
                Toast.makeText(this, R.string.site_data_cleared, Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
