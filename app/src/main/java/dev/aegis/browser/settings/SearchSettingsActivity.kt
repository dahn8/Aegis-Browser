package dev.aegis.browser.settings

import dev.aegis.browser.ui.AegisDialog
import android.content.Context
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.search.SearchEngine
import dev.aegis.browser.search.SearchEngineStore
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 搜索引擎设置页：与主设置页一致的通栏分组列表。
 * 引擎为单选行，建议开关 + 说明行，底部一行"添加搜索引擎"。
 */
class SearchSettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private lateinit var store: SearchEngineStore
    private lateinit var builder: SettingsSectionBuilder
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_search_settings)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.search_settings)

        store = SearchEngineStore(this)
        container = findViewById(R.id.settings_container)
        builder = SettingsSectionBuilder(this, container, dark)
        findViewById<View>(R.id.settings_scroll).setBackgroundColor(builder.spacerColor)

        buildUi()
    }

    private fun buildUi() {
        builder.reset()

        builder.section(getString(R.string.group_engines)) { box ->
            val selectedId = store.selected().id
            store.allEngines().forEach { engine ->
                val isCustom = !engine.builtIn
                builder.checkRow(
                    box = box,
                    title = engine.displayName(this),
                    selected = engine.id == selectedId,
                    trailingText = if (isCustom) getString(R.string.delete) else null,
                    onTrailing = if (isCustom) {
                        {
                            store.removeCustom(engine.id)
                            buildUi()
                        }
                    } else {
                        null
                    }
                ) {
                    store.select(engine.id)
                    buildUi()
                }
            }
        }

        builder.section(getString(R.string.group_privacy)) { box ->
            builder.switchRow(
                box,
                getString(R.string.suggest_switch),
                store.isSuggestEnabled()
            ) { checked -> store.setSuggestEnabled(checked) }
            builder.note(box, getString(R.string.suggest_warning))
        }

        builder.section(null) { box ->
            builder.row(box, getString(R.string.add_engine)) { showAddDialog() }
        }
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_engine, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_name)
        val etSearch = dialogView.findViewById<EditText>(R.id.et_search)
        val etSuggest = dialogView.findViewById<EditText>(R.id.et_suggest)

        AegisDialog.Builder(this)
            .setTitle(R.string.add_engine)
            .setView(dialogView)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = etName.text.toString().trim()
                val search = etSearch.text.toString().trim()
                val suggest = etSuggest.text.toString().trim().ifBlank { null }

                if (name.isBlank() || !search.contains(SearchEngine.PLACEHOLDER)) {
                    Toast.makeText(this, R.string.engine_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.addCustom(name, search, suggest)
                buildUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
