package dev.aegis.browser.sections

import dev.aegis.browser.ui.AegisDialog
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.history.HistoryEntry
import dev.aegis.browser.history.HistoryStore
import dev.aegis.browser.ui.PageSlide
import dev.aegis.browser.home.FaviconLoader
import dev.aegis.browser.ui.AnchoredMenu
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 历史记录页：顶部快捷工具栏 + 大字标题 + 圆形首字母行 + 右侧单条删除。
 * 点行回传 URL 给 MainActivity，长按进入批量模式。
 */
class HistoryPanel(private val activity: SectionHostActivity, private val root: View) {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var batchBar: View
    private lateinit var batchDelete: Button
    private lateinit var pageTitle: TextView
    private lateinit var searchField: EditText
    private lateinit var adapter: RowAdapter

    private val entries = mutableListOf<HistoryEntry>()
    private var keyword = ""
    private var batchMode = false
    private val selectedUrls = mutableSetOf<String>()

    private val store = HistoryStore(activity)

    init {
        pageTitle = root.findViewById(R.id.page_title)
        searchField = root.findViewById(R.id.search_field)
        searchField.setBackgroundResource(
            if (isNightMode()) R.drawable.bg_search_field_dark else R.drawable.bg_search_field
        )
        listView = root.findViewById(R.id.history_list)
        emptyView = root.findViewById(R.id.empty_view)
        batchBar = root.findViewById(R.id.batch_bar)
        batchDelete = root.findViewById(R.id.button_batch_delete)
        adapter = RowAdapter()
        listView.adapter = adapter

        root.findViewById<View>(R.id.button_search).setOnClickListener { toggleSearch() }
        root.findViewById<View>(R.id.button_more).setOnClickListener { showMoreMenu() }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty().trim()
                refresh()
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = entries[position]
            if (batchMode) toggleSelection(item)
            else BrowserToolbarHelper.openInHome(activity, item.url)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val item = entries[position]
            if (batchMode) toggleSelection(item) else enterBatchMode(item.url)
            true
        }

        root.findViewById<Button>(R.id.button_batch_all).setOnClickListener { selectAll() }
        batchDelete.setOnClickListener { confirmDeleteSelected() }
        root.findViewById<Button>(R.id.button_batch_cancel).setOnClickListener { exitBatchMode() }

        refresh()
    }

    private fun toggleSearch() {
        val showing = searchField.visibility == View.VISIBLE
        searchField.visibility = if (showing) View.GONE else View.VISIBLE
        if (showing) {
            searchField.setText("")
            keyword = ""
            refresh()
        } else {
            searchField.requestFocus()
        }
    }

    private fun showMoreMenu() {
        val labels = if (batchMode) {
            listOf("退出批量管理")
        } else {
            listOf(activity.getString(R.string.batch_delete), activity.getString(R.string.clear_history))
        }
        AnchoredMenu.show(activity, root.findViewById(R.id.button_more), labels, isNightMode()) { index ->
            if (batchMode) {
                exitBatchMode()
            } else if (index == 0) {
                enterBatchMode(null)
            } else {
                confirmClearHistory()
            }
        }
    }

    private fun isNightMode(): Boolean =
        dev.aegis.browser.ui.ThemeManager.isDarkNow(activity, dev.aegis.browser.ui.ThemeSettings(activity))

    private fun selectedColor(): Int {
        val base = activity.getColor(R.color.primary)
        return Color.argb(40, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun enterBatchMode(initialUrl: String?) {
        batchMode = true
        selectedUrls.clear()
        if (initialUrl != null) selectedUrls.add(initialUrl)
        batchBar.visibility = View.VISIBLE
        updateBatchState()
    }

    private fun exitBatchMode() {
        batchMode = false
        selectedUrls.clear()
        batchBar.visibility = View.GONE
        updateBatchState()
    }

    private fun toggleSelection(item: HistoryEntry) {
        if (selectedUrls.contains(item.url)) selectedUrls.remove(item.url)
        else selectedUrls.add(item.url)
        if (selectedUrls.isEmpty()) {
            exitBatchMode()
            return
        }
        updateBatchState()
    }

    private fun selectAll() {
        selectedUrls.clear()
        selectedUrls.addAll(entries.map { it.url })
        updateBatchState()
    }

    private fun updateBatchState() {
        batchDelete.text = "${activity.getString(R.string.delete)}(${selectedUrls.size})"
        pageTitle.text = if (batchMode) "已选 ${selectedUrls.size} 项" else activity.getString(R.string.history)
        refresh()
    }

    private fun confirmClearHistory() {
        AegisDialog.Builder(activity)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                store.clear()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val count = selectedUrls.size
        if (count == 0) return
        AegisDialog.Builder(activity)
            .setTitle(R.string.delete)
            .setMessage("将删除选中的 $count 条历史记录")
            .setPositiveButton(R.string.delete) { _, _ ->
                store.removeAll(selectedUrls.toList())
                exitBatchMode()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        entries.clear()
        val all = store.all()
        if (keyword.isEmpty()) {
            entries.addAll(all)
        } else {
            all.filter {
                it.title.contains(keyword, true) || it.url.contains(keyword, true)
            }.forEach { entries.add(it) }
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun hostOf(url: String): String = runCatching {
        Uri.parse(url).host.orEmpty().removePrefix("www.")
    }.getOrDefault("")

    private fun firstLetter(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "?"
        val ch = trimmed.first()
        return if (ch.isLetter()) ch.uppercase() else "#"
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): HistoryEntry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: activity.layoutInflater.inflate(R.layout.item_site_row, parent, false)
            val item = entries[position]
            val host = hostOf(item.url)
            val titleText = if (item.title.isBlank()) host else item.title

            val avatar = row.findViewById<View>(R.id.site_avatar)
            val letter = row.findViewById<TextView>(R.id.site_letter)
            val title = row.findViewById<TextView>(R.id.site_title)
            val hostView = row.findViewById<TextView>(R.id.site_host)
            val action = row.findViewById<ImageView>(R.id.site_action)

            val icon = row.findViewById<ImageView>(R.id.site_icon)
            icon.visibility = View.GONE
            icon.setImageDrawable(null)
            letter.text = firstLetter(titleText)
            avatar.backgroundTintList =
                ColorStateList.valueOf(FaviconLoader.colorFor(host.ifBlank { item.url }))
            title.text = titleText
            hostView.text = host.ifBlank { item.url }
            letter.visibility = View.VISIBLE
            hostView.visibility = View.VISIBLE

            action.visibility = if (batchMode) View.GONE else View.VISIBLE
            action.setOnClickListener {
                store.remove(item.url)
                refresh()
            }

            row.setBackgroundColor(
                if (batchMode && selectedUrls.contains(item.url)) selectedColor() else Color.TRANSPARENT
            )
            // 行内再兜一层点击：部分机型 ListView 的 itemClick 会被子控件吞掉
            row.setOnClickListener {
                if (batchMode) toggleSelection(item)
                else BrowserToolbarHelper.openInHome(activity, item.url)
            }
            row.setOnLongClickListener {
                if (batchMode) toggleSelection(item) else enterBatchMode(item.url)
                true
            }
            return row
        }
    }

    fun onShown() {
        refresh()
    }
}
