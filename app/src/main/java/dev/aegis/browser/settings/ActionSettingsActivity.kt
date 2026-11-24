package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 操作设定页：滑动屏幕前进后退、音量键翻页、视频播放器手势、键盘快捷键。
 */
class ActionSettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var prefs: BrowserPrefs
    private lateinit var builder: SettingsSectionBuilder

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = ThemeManager.apply(this, ThemeSettings(this), settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_action_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.action_settings)

        prefs = BrowserPrefs(this)
        val container = findViewById<LinearLayout>(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_gestures)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.swipe_nav_enabled),
                checked = prefs.swipeNavEnabled,
                subtitle = getString(R.string.swipe_nav_desc)
            ) { checked -> prefs.swipeNavEnabled = checked }

            builder.switchRow(
                box,
                title = getString(R.string.volume_key_page_turn),
                checked = prefs.volumeKeyPageTurn,
                subtitle = getString(R.string.volume_key_page_turn_desc)
            ) { checked -> prefs.volumeKeyPageTurn = checked }
        }

        builder.section(getString(R.string.group_media_keyboard)) { box ->
            builder.switchRow(
                box,
                title = getString(R.string.video_player_gestures),
                checked = prefs.videoPlayerGestures,
                subtitle = getString(R.string.video_player_gestures_desc)
            ) { checked -> prefs.videoPlayerGestures = checked }

            builder.switchRow(
                box,
                title = getString(R.string.keyboard_shortcuts),
                checked = prefs.keyboardShortcuts,
                subtitle = getString(R.string.keyboard_shortcuts_desc)
            ) { checked -> prefs.keyboardShortcuts = checked }

            builder.row(
                box,
                title = getString(R.string.keyboard_shortcuts_help),
                subtitle = getString(R.string.keyboard_shortcuts_help_desc)
            ) {
                startActivity(Intent(this, KeyboardShortcutsActivity::class.java))
            }
        }
    }
}
