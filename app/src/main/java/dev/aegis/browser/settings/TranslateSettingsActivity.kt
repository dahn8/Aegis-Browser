package dev.aegis.browser.settings

import dev.aegis.browser.ui.AegisDialog
import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.Toast
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import dev.aegis.browser.translate.TranslateLanguages
import dev.aegis.browser.translate.TranslateSource
import dev.aegis.browser.translate.SiliconFlowKeyStore
import dev.aegis.browser.translate.SiliconFlowTranslator
import dev.aegis.browser.translate.ZhipuKeyStore
import dev.aegis.browser.translate.ZhipuTranslator
import dev.aegis.browser.translate.TranslateSourceStore
import dev.aegis.browser.translate.TranslateTargetStore
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 翻译设置页：选择默认翻译源（腾讯 / Google），选一次后点悬浮球就直接用它翻，
 * 不再每次弹窗询问。
 */
class TranslateSettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private lateinit var store: TranslateSourceStore
    private lateinit var keyStore: SiliconFlowKeyStore
    private lateinit var zhipuKeyStore: ZhipuKeyStore
    private lateinit var targetStore: TranslateTargetStore
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_translate_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.translate_settings)

        store = TranslateSourceStore(this)
        keyStore = SiliconFlowKeyStore(this)
        zhipuKeyStore = ZhipuKeyStore(this)
        targetStore = TranslateTargetStore(this)
        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_translate_source)) { box ->
            builder.checkRow(
                box = box,
                title = getString(R.string.translate_source_tencent_name),
                selected = store.source == TranslateSource.TENCENT
            ) {
                store.source = TranslateSource.TENCENT
                buildUi()
            }
            builder.checkRow(
                box = box,
                title = getString(R.string.translate_source_microsoft_name),
                selected = store.source == TranslateSource.MICROSOFT
            ) {
                store.source = TranslateSource.MICROSOFT
                buildUi()
            }
            builder.checkRow(
                box = box,
                title = getString(R.string.translate_source_siliconflow_name),
                selected = store.source == TranslateSource.SILICONFLOW
            ) {
                store.source = TranslateSource.SILICONFLOW
                buildUi()
            }
            builder.checkRow(
                box = box,
                title = getString(R.string.translate_source_zhipu_name),
                selected = store.source == TranslateSource.ZHIPU
            ) {
                store.source = TranslateSource.ZHIPU
                buildUi()
            }
            builder.checkRow(
                box = box,
                title = getString(R.string.translate_source_google_name),
                selected = store.source == TranslateSource.GOOGLE
            ) {
                store.source = TranslateSource.GOOGLE
                buildUi()
            }
        }

        builder.section(getString(R.string.group_translate_target)) { box ->
            builder.row(
                box,
                getString(R.string.translate_target_language),
                TranslateLanguages.displayName(this, targetStore.target)
            ) { showTargetDialog() }
        }

        builder.section(getString(R.string.group_translate_siliconflow)) { box ->
            builder.row(
                box,
                getString(R.string.translate_sf_key),
                if (keyStore.hasKey()) getString(R.string.translate_sf_key_set)
                else getString(R.string.translate_sf_key_unset)
            ) { showKeyDialog() }
            builder.note(box, getString(R.string.translate_sf_key_note))
        }

        builder.section(getString(R.string.group_translate_zhipu)) { box ->
            builder.row(
                box,
                getString(R.string.translate_zp_key),
                if (zhipuKeyStore.hasKey()) getString(R.string.translate_sf_key_set)
                else getString(R.string.translate_sf_key_unset)
            ) { showZhipuKeyDialog() }
            builder.note(box, getString(R.string.translate_zp_key_note))
        }
    }

    private fun showZhipuKeyDialog() {
        val input = EditText(this).apply {
            setText(zhipuKeyStore.key)
            hint = getString(R.string.translate_zp_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(10), dp(4), dp(10), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.translate_zp_key)
            .setView(wrapper)
            .setPositiveButton(R.string.confirm) { _, _ ->
                zhipuKeyStore.key = input.text.toString()
                if (zhipuKeyStore.hasKey()) store.source = TranslateSource.ZHIPU
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.translate_sf_key_test) { _, _ ->
            val candidate = input.text.toString().trim()
            if (candidate.isBlank()) {
                Toast.makeText(this, R.string.translate_zp_no_key, Toast.LENGTH_SHORT).show()
                return@setNeutralButton
            }
            Toast.makeText(this, R.string.translate_sf_key_testing, Toast.LENGTH_SHORT).show()
            Thread {
                val message = ZhipuTranslator.testKey(candidate, targetStore.target)
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }.start()
        }.show()
    }

    private fun showKeyDialog() {
        val input = EditText(this).apply {
            setText(keyStore.key)
            hint = getString(R.string.translate_sf_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(10), dp(4), dp(10), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.translate_sf_key)
            .setView(wrapper)
            .setPositiveButton(R.string.confirm) { _, _ ->
                keyStore.key = input.text.toString()
                if (keyStore.hasKey()) store.source = TranslateSource.SILICONFLOW
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.translate_sf_key_test) { _, _ ->
            val candidate = input.text.toString().trim()
            if (candidate.isBlank()) {
                Toast.makeText(this, R.string.translate_sf_no_key, Toast.LENGTH_SHORT).show()
                return@setNeutralButton
            }
            Toast.makeText(this, R.string.translate_sf_key_testing, Toast.LENGTH_SHORT).show()
            Thread {
                val message = SiliconFlowTranslator.testKey(candidate, targetStore.target)
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }.start()
        }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showTargetDialog() {
        val languages = TranslateLanguages.all
        val checked = languages.indexOfFirst { it.code == targetStore.target }
        AegisDialog.Builder(this)
            .setTitle(R.string.translate_target_language)
            .setSingleChoiceItems(languages.map { TranslateLanguages.displayName(this, it.code) }.toTypedArray(), checked) { dialog, which ->
                targetStore.target = languages[which].code
                dialog.dismiss()
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
