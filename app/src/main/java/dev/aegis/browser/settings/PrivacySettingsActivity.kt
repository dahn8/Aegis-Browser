package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.core.web.CookiePolicy
import dev.aegis.browser.privacy.PrivacyInterceptor
import dev.aegis.browser.privacy.RuleLoader
import dev.aegis.browser.privacy.RuleUpdater
import dev.aegis.browser.privacy.data.DataCleaner
import dev.aegis.browser.privacy.data.PrivacySettingsStore
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Material 3 风格隐私设置页：数据管理、安全防护（含 Do Not Track）、拦截规则与 JS 权限。
 */
class PrivacySettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var settings: PrivacySettingsStore
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_privacy_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.privacy_settings)

        settings = PrivacySettingsStore(this)
        browserPrefs = BrowserPrefs(this)
        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_data)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.clear_on_exit),
                checked = settings.clearOnExit,
                subtitle = getString(R.string.clear_on_exit_desc)
            ) { checked -> settings.clearOnExit = checked }

            builder.row(
                box,
                title = getString(R.string.clear_now),
                value = BrowserDataCleaner.formatSize(BrowserDataCleaner.sizeBytes(this))
            ) { confirmClearNow() }

            builder.row(box, getString(R.string.site_data), null) {
                startActivity(Intent(this, SiteDataActivity::class.java))
            }
        }

        builder.section(getString(R.string.group_security)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.https_only),
                checked = settings.httpsOnly,
                subtitle = getString(R.string.https_only_desc)
            ) { checked -> settings.httpsOnly = checked }

            builder.switchRow(
                box,
                title = getString(R.string.tracking_strip),
                checked = settings.trackingStrip,
                subtitle = getString(R.string.tracking_strip_desc)
            ) { checked -> settings.trackingStrip = checked }

            builder.switchRow(
                box,
                title = getString(R.string.do_not_track),
                checked = browserPrefs.doNotTrack,
                subtitle = getString(R.string.do_not_track_desc)
            ) { checked -> browserPrefs.doNotTrack = checked }

            builder.switchRow(
                box,
                title = getString(R.string.third_party_cookie),
                checked = CookiePolicy.blockThirdParty(this),
                subtitle = getString(R.string.third_party_cookie_desc)
            ) { checked -> CookiePolicy.setBlockThirdParty(this, checked) }

            builder.switchRow(
                box,
                title = getString(R.string.anti_fingerprint),
                checked = settings.antiFingerprint,
                subtitle = getString(R.string.anti_fingerprint_desc)
            ) { checked -> settings.antiFingerprint = checked }

            builder.row(
                box,
                title = getString(R.string.third_party_allow),
                value = CookiePolicy.allowList(this).size.let {
                    if (it == 0) getString(R.string.third_party_allow_none) else it.toString()
                }
            ) { showThirdPartyAllow() }
        }

        builder.section(getString(R.string.rules_section)) { box ->
            builder.row(box, getString(R.string.rules_state), ruleStateText())
            builder.row(
                box,
                title = getString(R.string.block_log),
                value = PrivacyInterceptor.get(this).loggedHostCount().let {
                    if (it == 0) getString(R.string.block_log_empty) else it.toString()
                }
            ) { startActivity(Intent(this, BlockLogActivity::class.java)) }
            builder.row(
                box,
                title = getString(R.string.rules_subscriptions),
                value = getString(R.string.rules_sub_count, RuleUpdater.enabledSubscriptionIds(this).size)
            ) { showRuleSubscriptions() }
            builder.row(box, getString(R.string.rules_check_update)) { checkRulesUpdate() }
            builder.row(box, getString(R.string.restore_rules)) { restoreBuiltInRules() }
            builder.row(box, getString(R.string.whitelist_manage)) { showWhitelistManager() }
        }

        builder.section(getString(R.string.group_js)) { box ->
            val blocked = settings.jsBlockedHosts().size
            builder.row(
                box,
                title = getString(R.string.js_manage),
                value = if (blocked > 0) getString(R.string.js_manage_count, blocked)
                else getString(R.string.js_manage_none)
            ) { showJsManager() }
        }
    }

    private fun confirmClearNow() {
        AegisDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                DataCleaner.clear(this, emptyList())
                Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showJsManager() {
        val hosts = settings.jsBlockedHosts().toMutableList()
        val dlg = AegisDialog.Builder(this).setTitle(R.string.js_manage)
        if (hosts.isEmpty()) {
            dlg.setMessage(R.string.no_js_sites)
        } else {
            dlg.setItems(hosts.toTypedArray()) { _, which ->
                settings.unblockJs(hosts[which])
                Toast.makeText(this, R.string.restored, Toast.LENGTH_SHORT).show()
                buildUi()
            }
        }
        dlg.setPositiveButton(R.string.add_js_site) { _, _ -> showAddJsDialog() }
        dlg.setNegativeButton(R.string.cancel, null)
        dlg.show()
    }

    private fun showAddJsDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.js_site_hint)
            setSingleLine(true)
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.add_js_site)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val host = input.text.toString().trim().lowercase().trimEnd('.')
                if (!isValidDomain(host)) {
                    Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                settings.blockJs(host)
                Toast.makeText(this, R.string.confirm, Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun ruleStateText(): String {
        val count = PrivacyInterceptor.get(this).ruleCount
        val updatedAt = RuleUpdater.updatedAt(this)
        val date = if (updatedAt > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(updatedAt))
        } else {
            getString(R.string.rules_builtin)
        }
        return getString(R.string.rules_summary, count, date)
    }

    @Suppress("DEPRECATION")
    private fun checkRulesUpdate() {
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.updating))
            setCancelable(false)
            show()
        }
        Thread {
            val result = RuleUpdater.update(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                when (result) {
                    is RuleUpdater.Result.Success -> {
                        PrivacyInterceptor.get(this).reload()
                        Toast.makeText(
                            this,
                            getString(R.string.update_success, result.count),
                            Toast.LENGTH_LONG
                        ).show()
                        buildUi()
                    }
                    is RuleUpdater.Result.Failure -> Toast.makeText(
                        this,
                        getString(R.string.update_failed, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun restoreBuiltInRules() {
        File(filesDir, RuleLoader.UPDATED_NAME).delete()
        RuleUpdater.clearUpdatedState(this)
        PrivacyInterceptor.get(this).reload()
        Toast.makeText(this, R.string.restored, Toast.LENGTH_SHORT).show()
        buildUi()
    }

    private fun showWhitelistManager() {
        val interceptor = PrivacyInterceptor.get(this)
        val list = interceptor.whitelist().sorted()
        if (list.isEmpty()) {
            AegisDialog.Builder(this)
                .setTitle(R.string.whitelist_manage)
                .setMessage(R.string.no_whitelist)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.whitelist_manage)
            .setItems(list.toTypedArray()) { _, which ->
                interceptor.removeWhitelist(list[which])
                buildUi()
            }
            .show()
    }

    private fun showThirdPartyAllow() {
        val hosts = CookiePolicy.allowList(this).sorted()
        val dlg = AegisDialog.Builder(this).setTitle(R.string.third_party_allow)
        if (hosts.isEmpty()) {
            dlg.setMessage(R.string.third_party_allow_none)
        } else {
            dlg.setItems(hosts.toTypedArray()) { _, which ->
                CookiePolicy.removeAllow(this, hosts[which])
                Toast.makeText(this, R.string.site_data_cleared, Toast.LENGTH_SHORT).show()
                buildUi()
            }
        }
        dlg.setPositiveButton(R.string.third_party_allow_add) { _, _ -> showAddThirdPartyDialog() }
        dlg.setNegativeButton(R.string.cancel, null)
        dlg.show()
    }

    private fun showAddThirdPartyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.third_party_allow_hint)
            setSingleLine(true)
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.third_party_allow_add)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val host = input.text.toString().trim().lowercase().trimEnd('.')
                if (!isValidDomain(host)) {
                    Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CookiePolicy.allow(this, host)
                Toast.makeText(this, R.string.third_party_added, Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isValidDomain(host: String): Boolean {
        if (!host.contains('.')) return false
        if (!host.contains(Regex("[a-z]"))) return false
        if (host.contains("..")) return false
        return host.matches(Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$"))
    }

    private fun showRuleSubscriptions() {
        val subs = RuleUpdater.allSubscriptions(this)
        val enabled = RuleUpdater.enabledSubscriptionIds(this)
        val labels = subs.map { sub ->
            val mark = if (sub.id in enabled) "✓ " else "   "
            "$mark${sub.name}"
        }.toTypedArray()
        AegisDialog.Builder(this)
            .setTitle(R.string.rules_subscriptions)
            .setItems(labels) { dialog, which ->
                val sub = subs[which]
                RuleUpdater.setSubscriptionEnabled(this, sub.id, sub.id !in enabled)
                dialog.dismiss()
                buildUi()
                showRuleSubscriptions()
            }
            .setNeutralButton(R.string.rules_sub_add_custom) { _, _ ->
                showAddCustomSubscriptionDialog()
            }
            .setPositiveButton(R.string.update_rules) { _, _ ->
                checkRulesUpdate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddCustomSubscriptionDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.rules_sub_name_hint)
            setSingleLine(true)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.rules_sub_url_hint)
            setSingleLine(true)
        }
        box.addView(nameInput)
        box.addView(urlInput)
        AegisDialog.Builder(this)
            .setTitle(R.string.rules_sub_add_custom)
            .setView(box)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, R.string.rules_sub_invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                RuleUpdater.addCustomSubscription(this, nameInput.text.toString(), url)
                Toast.makeText(this, R.string.rules_sub_added, Toast.LENGTH_SHORT).show()
                buildUi()
                showRuleSubscriptions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

}
