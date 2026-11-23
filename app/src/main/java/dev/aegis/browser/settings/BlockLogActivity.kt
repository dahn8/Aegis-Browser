package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.privacy.PrivacyInterceptor
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截记录页（M6 命中与误伤排查）：
 * 列出最近被规则拦下的域名、来源页面与时间；点按可一键加入白名单。
 */
class BlockLogActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var interceptor: PrivacyInterceptor
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_block_log)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.block_log)

        interceptor = PrivacyInterceptor.get(this)
        container = findViewById(R.id.block_log_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.block_log_scroll).setBackgroundColor(builder.spacerColor)
        buildUi()
    }

    private fun buildUi() {
        builder.reset()
        val hits = interceptor.recentBlocks()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        if (hits.isEmpty()) {
            builder.section(getString(R.string.block_log)) { box ->
                builder.row(
                    box,
                    title = getString(R.string.block_log_empty),
                    subtitle = getString(R.string.block_log_empty_desc)
                )
            }
            return
        }

        builder.section(getString(R.string.block_log_recent, hits.size)) { box ->
            for (hit in hits) {
                val page = hit.pageHost?.takeIf { it.isNotBlank() }
                val subtitle = if (page != null) {
                    getString(R.string.block_log_from, page)
                } else {
                    getString(R.string.block_log_no_page)
                }
                builder.row(
                    box,
                    title = hit.host,
                    value = timeFormat.format(Date(hit.timeMs)),
                    subtitle = subtitle
                ) { showHitActions(hit) }
            }
        }

        builder.section(null) { box ->
            builder.row(box, getString(R.string.block_log_clear)) {
                interceptor.clearBlockLog()
                Toast.makeText(this, R.string.block_log_cleared, Toast.LENGTH_SHORT).show()
                buildUi()
            }
        }
    }

    private fun showHitActions(hit: PrivacyInterceptor.BlockedHit) {
        AegisDialog.Builder(this)
            .setTitle(hit.host)
            .setMessage(
                if (hit.pageHost.isNullOrBlank()) getString(R.string.block_log_no_page)
                else getString(R.string.block_log_from, hit.pageHost)
            )
            .setPositiveButton(R.string.whitelist_site) { _, _ ->
                // 误伤处理：把该域名加入拦截白名单，后续不再拦截
                interceptor.addWhitelist(hit.host)
                Toast.makeText(this, R.string.block_log_whitelisted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
