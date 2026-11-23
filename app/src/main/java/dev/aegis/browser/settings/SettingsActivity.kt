package dev.aegis.browser.settings

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.home.HomeBackgroundStore
import dev.aegis.browser.privacy.PrivacyInterceptor
import dev.aegis.browser.privacy.RuleLoader
import dev.aegis.browser.privacy.RuleUpdater
import dev.aegis.browser.search.SearchEngineStore
import dev.aegis.browser.sections.SectionHostActivity
import dev.aegis.browser.translate.TranslateSource
import dev.aegis.browser.translate.TranslateSourceStore
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.FontSizeStore
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Material 3 风格统一设置页：
 * 支持实时搜索设置项、按「基础 / 浏览与外观 / 隐私与安全 / 实用工具与关于」四组分布。
 */
class SettingsActivity : Activity() {

    private data class SearchEntry(
        val title: String,
        val category: String,
        val iconRes: Int,
        val keywords: String,
        val action: () -> Unit
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var themeSettings: ThemeSettings
    private var screenOrientation: String = ScreenOrientation.FOLLOW
    private lateinit var interceptor: PrivacyInterceptor
    private lateinit var container: LinearLayout
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var searchInput: EditText
    private lateinit var searchClear: View

    private var dark = false
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        screenOrientation = ScreenOrientation.current(this)
        ScreenOrientation.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        applyWindowColors()
        bindHeaderAndSearch()

        interceptor = PrivacyInterceptor.get(this)
        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun bindHeaderAndSearch() {
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.settings)
        findViewById<View>(R.id.settings_search_bar).setBackgroundResource(
            if (dark) R.drawable.bg_m3_card_dark else R.drawable.bg_m3_card
        )
        searchInput = findViewById(R.id.settings_search_input)
        searchClear = findViewById(R.id.settings_search_clear)
        searchClear.setOnClickListener {
            searchInput.setText("")
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                searchClear.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                buildUi()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val latestOrientation = ScreenOrientation.current(this)
        if (latestOrientation != screenOrientation) {
            screenOrientation = latestOrientation
            ScreenOrientation.apply(this)
        }
        if (ThemeManager.isDarkNow(this, themeSettings) != dark) {
            reapplyThemeInPlace()
            return
        }
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
        val scrollY = (findViewById<View>(R.id.settings_scroll) as? ScrollView)?.scrollY ?: 0
        dark = ThemeManager.isDarkNow(this, themeSettings)
        ThemeManager.apply(this, themeSettings, settingsScreen = true)
        setContentView(R.layout.activity_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        applyWindowColors()
        bindHeaderAndSearch()
        if (searchQuery.isNotEmpty()) {
            searchInput.setText(searchQuery)
            searchInput.setSelection(searchQuery.length)
        }
        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)
        buildUi()
        findViewById<View>(R.id.settings_scroll).post {
            (findViewById<View>(R.id.settings_scroll) as? ScrollView)?.scrollTo(0, scrollY)
        }
    }

    private fun buildUi() {
        builder.reset()
        if (searchQuery.isNotBlank()) {
            buildSearchResults(searchQuery)
            return
        }

        val engineStore = SearchEngineStore(this)
        val suggestState = getString(
            if (engineStore.isSuggestEnabled()) R.string.suggest_enabled_on else R.string.suggest_enabled_off
        )
        val fontZoom = FontSizeStore(this).textZoom

        // 1. 基础
        builder.section(getString(R.string.group_general)) { box ->
            builder.row(
                box,
                title = getString(R.string.search_settings),
                subtitle = "${engineStore.selected().displayName(this)} · ${getString(R.string.suggest_switch)}: $suggestState",
                iconRes = R.drawable.ic_search
            ) { startActivity(Intent(this, SearchSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.general),
                subtitle = ScreenOrientation.label(this, ScreenOrientation.current(this)),
                iconRes = R.drawable.ic_settings
            ) { startActivity(Intent(this, GeneralSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.translate_settings),
                subtitle = translateSourceLabel(),
                iconRes = R.drawable.ic_translate
            ) { startActivity(Intent(this, TranslateSettingsActivity::class.java)) }
        }

        // 2. 浏览与外观
        builder.section(getString(R.string.group_browsing)) { box ->
            builder.row(
                box,
                title = getString(R.string.appearance),
                subtitle = "${if (homeBackgroundCustom()) getString(R.string.wallpaper_custom) else getString(R.string.wallpaper_default)} · $fontZoom%",
                iconRes = R.drawable.ic_dark_mode
            ) { startActivity(Intent(this, AppearanceSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.web_settings),
                subtitle = WebSettingsHelper.uaLabel(this),
                iconRes = R.drawable.ic_globe
            ) { startActivity(Intent(this, WebSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.action_settings),
                subtitle = getString(R.string.action_settings_summary),
                iconRes = R.drawable.ic_orientation
            ) { startActivity(Intent(this, ActionSettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.language),
                value = AppLanguage.displayName(AppLanguage.current(this)),
                iconRes = R.drawable.ic_page_settings
            ) { showLanguagePicker() }
        }

        // 3. 隐私与安全
        builder.section(getString(R.string.group_privacy_security)) { box ->
            builder.row(
                box,
                title = getString(R.string.privacy_settings),
                iconRes = R.drawable.ic_shield
            ) { startActivity(Intent(this, PrivacySettingsActivity::class.java)) }

            builder.row(
                box,
                title = getString(R.string.privacy_protection),
                subtitle = ruleSummary(),
                iconRes = R.drawable.ic_shield_off
            ) { showProtectionDialog() }

            builder.row(
                box,
                title = getString(R.string.clear_browsing_data),
                value = BrowserDataCleaner.formatSize(BrowserDataCleaner.sizeBytes(this)),
                iconRes = R.drawable.ic_delete
            ) { startActivity(Intent(this, ClearDataActivity::class.java)) }
        }

        // 4. 实用工具与关于
        builder.section(getString(R.string.group_tools)) { box ->
            builder.row(
                box,
                title = getString(R.string.download_manager),
                iconRes = R.drawable.ic_download
            ) {
                startActivity(
                    SectionHostActivity.intentFor(this, 2).apply {
                        putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                        putExtra(BrowserToolbarHelper.EXTRA_RETURN_TO, "settings")
                    }
                )
            }

            builder.row(
                box,
                title = getString(R.string.set_default_browser),
                iconRes = R.drawable.ic_home
            ) { openDefaultAppSettings() }

            builder.row(
                box,
                title = getString(R.string.about_app),
                value = versionName(),
                iconRes = R.drawable.ic_menu
            ) { showAboutDialog() }
        }
    }

    private fun buildSearchResults(query: String) {
        val q = query.lowercase(Locale.US)
        val matches = buildSearchIndex().filter {
            it.title.lowercase(Locale.US).contains(q) ||
                it.category.lowercase(Locale.US).contains(q) ||
                it.keywords.lowercase(Locale.US).contains(q)
        }
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.search_settings_empty)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(getColor(if (dark) R.color.m3_subtitle_dark else R.color.m3_subtitle))
                setPadding(24, 96, 24, 48)
            })
            return
        }
        builder.section(null) { box ->
            matches.forEach { entry ->
                builder.row(
                    box,
                    title = entry.title,
                    subtitle = entry.category,
                    iconRes = entry.iconRes,
                    onClick = entry.action
                )
            }
        }
    }

    private fun buildSearchIndex(): List<SearchEntry> {
        val gGeneral = getString(R.string.group_general)
        val gBrowsing = getString(R.string.group_browsing)
        val gPrivacy = getString(R.string.group_privacy_security)
        val gTools = getString(R.string.group_tools)

        val goSearch = { startActivity(Intent(this, SearchSettingsActivity::class.java)) }
        val goGeneral = { startActivity(Intent(this, GeneralSettingsActivity::class.java)) }
        val goTranslate = { startActivity(Intent(this, TranslateSettingsActivity::class.java)) }
        val goAppearance = { startActivity(Intent(this, AppearanceSettingsActivity::class.java)) }
        val goWeb = { startActivity(Intent(this, WebSettingsActivity::class.java)) }
        val goAction = { startActivity(Intent(this, ActionSettingsActivity::class.java)) }
        val goPrivacy = { startActivity(Intent(this, PrivacySettingsActivity::class.java)) }
        val goClear = { startActivity(Intent(this, ClearDataActivity::class.java)) }
        val goPerms = { startActivity(Intent(this, SitePermissionsActivity::class.java)) }

        return listOf(
            SearchEntry(getString(R.string.search_settings), gGeneral, R.drawable.ic_search, "engine bing baidu google suggest 搜索引擎 搜索建议", goSearch),
            SearchEntry(getString(R.string.suggest_switch), "$gGeneral · ${getString(R.string.search_settings)}", R.drawable.ic_search, "suggestion autocomplete 搜索建议 联想", goSearch),
            SearchEntry(getString(R.string.general), gGeneral, R.drawable.ic_settings, "general theme dark night orientation 通用 日间 夜间", goGeneral),
            SearchEntry(getString(R.string.screen_orientation), "$gGeneral · ${getString(R.string.general)}", R.drawable.ic_orientation, "orientation portrait landscape 屏幕方向 横屏 竖屏", goGeneral),
            SearchEntry(getString(R.string.start_last_page), "$gGeneral · ${getString(R.string.general)}", R.drawable.ic_history, "restore tabs session 启动 恢复 标签", goGeneral),
            SearchEntry(getString(R.string.start_in_incognito), "$gGeneral · ${getString(R.string.general)}", R.drawable.ic_incognito, "incognito inprivate 无痕 隐私启动", goGeneral),
            SearchEntry(getString(R.string.translate_settings), gGeneral, R.drawable.ic_translate, "translate microsoft google tencent siliconflow zhipu 翻译 微软 腾讯", goTranslate),
            SearchEntry(getString(R.string.appearance), gBrowsing, R.drawable.ic_dark_mode, "wallpaper font zoom theme 壁纸 字体 主题 外观", goAppearance),
            SearchEntry(getString(R.string.web_settings), gBrowsing, R.drawable.ic_globe, "ua user agent zoom pc mobile 网页 缩放 标识", goWeb),
            SearchEntry(getString(R.string.action_settings), gBrowsing, R.drawable.ic_orientation, "gesture swipe volume video keyboard 手势 滑动 音量键翻页 视频手势 快捷键 操作设定", goAction),
            SearchEntry(getString(R.string.swipe_nav_enabled), "$gBrowsing · ${getString(R.string.action_settings)}", R.drawable.ic_orientation, "swipe back forward gesture 滑动 前进 后退", goAction),
            SearchEntry(getString(R.string.volume_key_page_turn), "$gBrowsing · ${getString(R.string.action_settings)}", R.drawable.ic_orientation, "volume page turn 音量键 翻页", goAction),
            SearchEntry(getString(R.string.video_player_gestures), "$gBrowsing · ${getString(R.string.action_settings)}", R.drawable.ic_orientation, "video player gesture brightness volume seek 视频 播放器 手势", goAction),
            SearchEntry(getString(R.string.keyboard_shortcuts), "$gBrowsing · ${getString(R.string.action_settings)}", R.drawable.ic_orientation, "keyboard shortcut ctrl 键盘 快捷键", goAction),
            SearchEntry(getString(R.string.zoom_enabled), "$gBrowsing · ${getString(R.string.web_settings)}", R.drawable.ic_globe, "pinch zoom 缩放 双指", goWeb),
            SearchEntry(getString(R.string.perm_settings), "$gBrowsing · ${getString(R.string.web_settings)}", R.drawable.ic_shield, "permissions camera location clipboard 站点权限 剪贴板 定位", goPerms),
            SearchEntry(getString(R.string.language), gBrowsing, R.drawable.ic_page_settings, "language zh en 中文 英文 语言") { showLanguagePicker() },
            SearchEntry(getString(R.string.privacy_settings), gPrivacy, R.drawable.ic_shield, "privacy https cookie js javascript dnt 隐私 安全", goPrivacy),
            SearchEntry(getString(R.string.https_only), "$gPrivacy · ${getString(R.string.privacy_settings)}", R.drawable.ic_shield, "https ssl 安全连接", goPrivacy),
            SearchEntry(getString(R.string.do_not_track), "$gPrivacy · ${getString(R.string.privacy_settings)}", R.drawable.ic_shield, "dnt gpc track 请勿追踪", goPrivacy),
            SearchEntry(getString(R.string.third_party_cookie), "$gPrivacy · ${getString(R.string.privacy_settings)}", R.drawable.ic_shield, "cookie 第三方", goPrivacy),
            SearchEntry(getString(R.string.js_manage), "$gPrivacy · ${getString(R.string.privacy_settings)}", R.drawable.ic_shield, "javascript js 脚本", goPrivacy),
            SearchEntry(getString(R.string.privacy_protection), gPrivacy, R.drawable.ic_shield_off, "adblock filter whitelist 拦截 广告 白名单 规则") { showProtectionDialog() },
            SearchEntry(getString(R.string.clear_browsing_data), gPrivacy, R.drawable.ic_delete, "clear cache history cookies 清除 数据 缓存", goClear),
            SearchEntry(getString(R.string.download_manager), gTools, R.drawable.ic_download, "download 下载管理") {
                startActivity(
                    SectionHostActivity.intentFor(this, 2).apply {
                        putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                        putExtra(BrowserToolbarHelper.EXTRA_RETURN_TO, "settings")
                    }
                )
            },
            SearchEntry(getString(R.string.set_default_browser), gTools, R.drawable.ic_home, "default browser 默认浏览器") { openDefaultAppSettings() },
            SearchEntry(getString(R.string.about_app), gTools, R.drawable.ic_menu, "about version aegis 关于 版本") { showAboutDialog() }
        )
    }

    private fun showLanguagePicker() {
        val options = AppLanguage.options
        val current = AppLanguage.current(this)
        AegisDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(
                options.map { AppLanguage.displayName(it) }.toTypedArray(),
                options.indexOf(current)
            ) { dialog, which ->
                dialog.dismiss()
                val tag = options[which]
                if (tag != current) {
                    AppLanguage.set(this, tag)
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun translateSourceLabel(): String =
        when (TranslateSourceStore(this).source) {
            TranslateSource.MICROSOFT -> getString(R.string.translate_source_microsoft_name)
            TranslateSource.GOOGLE -> getString(R.string.translate_source_google_name)
            TranslateSource.SILICONFLOW -> getString(R.string.translate_source_siliconflow_name)
            TranslateSource.ZHIPU -> getString(R.string.translate_source_zhipu_name)
            else -> getString(R.string.translate_source_tencent_name)
        }

    private fun homeBackgroundCustom(): Boolean = HomeBackgroundStore(this).hasCustomBackground()

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun openDefaultAppSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.default_browser_settings_missing, Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        AegisDialog.Builder(this)
            .setTitle(R.string.about_app)
            .setMessage(getString(R.string.version_format, versionName()))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun ruleSummary(): String {
        val updatedAt = RuleUpdater.updatedAt(this)
        val date = if (updatedAt > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(updatedAt))
        } else {
            getString(R.string.rules_builtin)
        }
        return getString(R.string.rules_summary, interceptor.ruleCount, date)
    }

    private fun showProtectionDialog() {
        val updatedAt = RuleUpdater.updatedAt(this)
        val date = if (updatedAt > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(updatedAt))
        } else {
            getString(R.string.rules_builtin)
        }
        val message = buildString {
            append(getString(R.string.rules_count)).append("：").append(interceptor.ruleCount)
            append('\n')
            append(getString(R.string.rules_updated_at)).append("：").append(date)
            append('\n')
            append(getString(R.string.blocked_count)).append("：").append(interceptor.blockedCount)
        }
        val actions = arrayOf(
            getString(R.string.update_rules),
            getString(R.string.rules_subscriptions),
            getString(R.string.whitelist_manage),
            getString(R.string.restore_rules)
        )
        AegisDialog.Builder(this)
            .setTitle(R.string.privacy_protection)
            .setMessage(message)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> updateRules()
                    1 -> showRuleSubscriptions()
                    2 -> showWhitelistManager()
                    3 -> restoreBuiltInRules()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun updateRules() {
        val progress = ProgressDialog(this).apply {
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
                        interceptor.reload()
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

    private fun showWhitelistManager() {
        val list = interceptor.whitelist().toList()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreBuiltInRules() {
        File(filesDir, RuleLoader.UPDATED_NAME).delete()
        RuleUpdater.clearUpdatedState(this)
        interceptor.reload()
        Toast.makeText(this, R.string.restored, Toast.LENGTH_SHORT).show()
        buildUi()
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
                val next = sub.id !in enabled
                RuleUpdater.setSubscriptionEnabled(this, sub.id, next)
                dialog.dismiss()
                showRuleSubscriptions()
            }
            .setNeutralButton(R.string.rules_sub_add_custom) { _, _ ->
                showAddCustomSubscriptionDialog()
            }
            .setPositiveButton(R.string.update_rules) { _, _ ->
                updateRules()
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
                showRuleSubscriptions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

}
