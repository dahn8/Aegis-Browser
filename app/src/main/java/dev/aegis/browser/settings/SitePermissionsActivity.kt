package dev.aegis.browser.settings

import dev.aegis.browser.ui.AegisDialog
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.core.web.SitePermissions
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 站点权限：按权限种类给出 允许 / 每次询问 / 禁止 三档，点一行即可切换。
 */
class SitePermissionsActivity : Activity() {

    private lateinit var container: LinearLayout
    private var dark = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = ThemeManager.apply(this, ThemeSettings(this), settingsScreen = true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_permissions)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.perm_settings)
        container = findViewById(R.id.settings_container)
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.setPadding(0, dp(6), 0, 0)
        SitePermissions.keys.forEach { key ->
            container.addView(row(key))
            container.addView(divider())
        }
    }

    private fun nameOf(key: String): String = getString(
        when (key) {
            SitePermissions.CLIPBOARD -> R.string.perm_clipboard
            SitePermissions.OPEN_APP -> R.string.perm_open_app
            SitePermissions.LOCATION -> R.string.perm_location
            SitePermissions.CAMERA -> R.string.perm_camera
            SitePermissions.MIC -> R.string.perm_mic
            SitePermissions.REDIRECT -> R.string.perm_redirect
            else -> R.string.perm_vibrate
        }
    )

    private fun stateText(state: String): String = getString(
        when (state) {
            SitePermissions.ALLOW -> R.string.perm_allow
            SitePermissions.BLOCK -> R.string.perm_block
            else -> R.string.perm_ask
        }
    )

    private fun row(key: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            isClickable = true
            setOnClickListener { pick(key) }
        }
        box.addView(TextView(this).apply {
            text = nameOf(key)
            textSize = 17f
            setTextColor(if (dark) 0xFFE8EBEF.toInt() else 0xFF1F2328.toInt())
        })
        box.addView(TextView(this).apply {
            text = stateText(SitePermissions.state(this@SitePermissionsActivity, key))
            textSize = 13f
            setTextColor(if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        return box
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(22)
        }
        setBackgroundColor(if (dark) 0x1FFFFFFF else 0x14000000)
    }

    private fun pick(key: String) {
        val states = listOf(SitePermissions.ALLOW, SitePermissions.ASK, SitePermissions.BLOCK)
        val current = SitePermissions.state(this, key)
        val index = states.indexOf(current).coerceAtLeast(0)
        AegisDialog.Builder(this)
            .setTitle(nameOf(key))
            .setSingleChoiceItems(
                states.map { stateText(it) }.toTypedArray(),
                index
            ) { dialog, which ->
                SitePermissions.set(this, key, states[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
