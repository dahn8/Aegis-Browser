package dev.aegis.browser.settings

import android.content.Context
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.history.HistoryStore
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 浏览器专清：按项选择要清理的内容，缓存类默认勾选。
 * 每一项都对应真实的清理动作；大小来自本地目录实测。
 */
class ClearDataActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private data class CleanItem(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val sizeLabel: String? = null,
        val defaultChecked: Boolean = false,
        val action: () -> Unit
    )

    private lateinit var historyStore: HistoryStore
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var container: LinearLayout
    private lateinit var cleanButton: TextView

    private val checked = mutableMapOf<String, Boolean>()
    private var items: List<CleanItem> = emptyList()
    private var appCacheBytes = 0L
    private var webCacheBytes = 0L

    private var dark = false
    private var surfaceColor = Color.WHITE
    private var spacerColor = 0xFFF2F3F5.toInt()
    private var dividerColor = 0x14000000
    private var valueColor = 0xFF8A8F98.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_clear_data)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.clear_browsing_data)

        historyStore = HistoryStore(this)
        browserPrefs = BrowserPrefs(this)
        container = findViewById(R.id.clear_container)
        cleanButton = findViewById(R.id.btn_clean)

        if (dark) {
            surfaceColor = 0xFF1B1E23.toInt()
            spacerColor = 0xFF0E1013.toInt()
            dividerColor = 0x1FFFFFFF
            valueColor = 0xFF9AA0A8.toInt()
        }
        findViewById<View>(R.id.clear_scroll).setBackgroundColor(spacerColor)

        cleanButton.setOnClickListener { runClean() }
        buildUi()
    }

    private fun collectItems(): Pair<List<CleanItem>, List<CleanItem>> {
        val historyCount = historyStore.all().size
        val externalCount = browserPrefs.externalBlockedCount

        val privacy = listOf(
            CleanItem(
                id = "history",
                title = getString(R.string.history_records),
                subtitle = if (historyCount > 0) getString(R.string.item_count, historyCount) else null
            ) { historyStore.clear() },
            CleanItem(
                id = "cookies",
                title = getString(R.string.cookies)
            ) { BrowserDataCleaner.clearCookies() },
            CleanItem(
                id = "form",
                title = getString(R.string.form_data)
            ) { BrowserDataCleaner.clearFormData(this) },
            CleanItem(
                id = "external",
                title = getString(R.string.external_record),
                subtitle = if (externalCount > 0) getString(R.string.item_count, externalCount) else null
            ) { browserPrefs.resetExternalBlocked() }
        )

        val cache = listOf(
            CleanItem(
                id = "app_cache",
                title = getString(R.string.app_temp_cache),
                sizeLabel = BrowserDataCleaner.formatSize(appCacheBytes),
                defaultChecked = true
            ) { BrowserDataCleaner.clearAppCache(this) },
            CleanItem(
                id = "web_cache",
                title = getString(R.string.web_temp_cache),
                sizeLabel = BrowserDataCleaner.formatSize(webCacheBytes),
                defaultChecked = true
            ) { BrowserDataCleaner.clearWebCache(this) }
        )
        return privacy to cache
    }

    private fun buildUi() {
        container.removeAllViews()
        appCacheBytes = BrowserDataCleaner.appCacheSize(this)
        webCacheBytes = BrowserDataCleaner.webCacheSize(this)
        val (privacy, cache) = collectItems()
        items = privacy + cache

        if (checked.isEmpty()) {
            items.forEach { checked[it.id] = it.defaultChecked }
        }

        addCard(getString(R.string.privacy_records), privacy, showSize = false)
        addCard(getString(R.string.safe_clean), cache, showSize = true)
        updateCleanButton()
    }

    private fun addCard(header: String, list: List<CleanItem>, showSize: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(10)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(6))
        }
        headerRow.addView(TextView(this).apply {
            text = header
            textSize = 16f
            setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val summary = TextView(this).apply {
            textSize = 12f
            setTextColor(valueColor)
        }
        headerRow.addView(summary)
        card.addView(headerRow)

        list.forEachIndexed { index, item ->
            if (index > 0) card.addView(divider())
            val row = layoutInflater.inflate(R.layout.item_clear_row, card, false)
            row.findViewById<TextView>(R.id.clear_row_title).text = item.title
            val sub = row.findViewById<TextView>(R.id.clear_row_sub)
            if (item.subtitle.isNullOrBlank()) {
                sub.visibility = View.GONE
            } else {
                sub.text = item.subtitle
                sub.visibility = View.VISIBLE
            }
            val sizeView = row.findViewById<TextView>(R.id.clear_row_size)
            if (showSize && !item.sizeLabel.isNullOrBlank()) {
                sizeView.text = item.sizeLabel
            } else {
                sizeView.text = ""
            }
            val check = row.findViewById<CheckBox>(R.id.clear_row_check)
            check.isChecked = checked[item.id] == true
            row.setOnClickListener {
                check.isChecked = !check.isChecked
                checked[item.id] = check.isChecked
                summary.text = summaryFor(header, list, showSize)
                updateCleanButton()
            }
            card.addView(row)
        }

        summary.text = summaryFor(header, list, showSize)
        container.addView(card)
    }

    private fun summaryFor(header: String, list: List<CleanItem>, showSize: Boolean): String {
        if (showSize) {
            var selectedBytes = 0L
            for (item in list) {
                if (checked[item.id] == true) {
                    selectedBytes += if (item.id == "app_cache") appCacheBytes else webCacheBytes
                }
            }
            return getString(R.string.selected_total, BrowserDataCleaner.formatSize(selectedBytes))
        }
        val selected = list.count { checked[it.id] == true }
        return getString(R.string.selected_items, selected)
    }

    private fun updateCleanButton() {
        val selectedBytes =
            (if (checked["app_cache"] == true) appCacheBytes else 0L) +
                (if (checked["web_cache"] == true) webCacheBytes else 0L)
        cleanButton.text = if (selectedBytes > 0) {
            getString(R.string.clean_button_selected, BrowserDataCleaner.formatSize(selectedBytes))
        } else {
            getString(R.string.clean_button)
        }
    }

    private fun runClean() {
        val selected = items.filter { checked[it.id] == true }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.clean_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        selected.forEach { it.action() }
        Toast.makeText(this, R.string.clean_done, Toast.LENGTH_SHORT).show()
        // 清理后重新统计大小
        buildUi()
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).apply { marginStart = dp(18) }
        setBackgroundColor(dividerColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
