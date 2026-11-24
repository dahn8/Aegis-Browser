package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
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
 * 键盘快捷键说明页：独立页面，按功能逐行纵向排列。
 */
class KeyboardShortcutsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = ThemeManager.apply(this, ThemeSettings(this), settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_keyboard_shortcuts)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.keyboard_shortcuts_help)

        val container = findViewById<LinearLayout>(R.id.keyboard_container)
        val builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.keyboard_scroll).setBackgroundColor(builder.spacerColor)

        builder.section(getString(R.string.keyboard_shortcuts)) { box ->
            shortcutRow(builder, box, R.string.shortcut_new_tab, "Ctrl + T")
            shortcutRow(builder, box, R.string.shortcut_close_tab, "Ctrl + W")
            shortcutRow(builder, box, R.string.shortcut_refresh, "Ctrl + R / F5")
            shortcutRow(builder, box, R.string.shortcut_find, "Ctrl + F")
            shortcutRow(builder, box, R.string.shortcut_focus_address, "Ctrl + L")
            shortcutRow(builder, box, R.string.shortcut_incognito, "Ctrl + Shift + N")
            shortcutRow(builder, box, R.string.shortcut_back_forward, "Alt + ← / →")
        }
    }

    private fun shortcutRow(
        builder: SettingsSectionBuilder,
        box: LinearLayout,
        titleRes: Int,
        keys: String
    ) {
        builder.row(
            box,
            title = getString(titleRes),
            value = keys
        )
    }
}
