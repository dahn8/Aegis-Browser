package dev.aegis.browser.bookmarks

import dev.aegis.browser.ui.AegisDialog
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.PageSlide
import dev.aegis.browser.home.FaviconLoader
import dev.aegis.browser.ui.AnchoredMenu
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 书签页：顶部快捷工具栏 + 返回箭头 + 大字标题 + 圆角搜索框。
 * 右上三点直接进入批量删除，底部批量栏带动画弹出。
 */
class BookmarksActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private lateinit var store: BookmarkStore
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var batchBar: View
    private lateinit var batchDelete: Button
    private lateinit var pageTitle: TextView
    private lateinit var searchField: EditText
    private lateinit var adapter: RowAdapter

    private val homeChain: Boolean by lazy {
        intent?.getBooleanExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, false) == true
    }

    private val entries = mutableListOf<Bookmark>()
    private var keyword = ""
    private var batchMode = false
    private val selectedUrls = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)
        BrowserToolbarHelper.animateSectionContentIn(this)
        BrowserToolbarHelper.installSwipe(this, BrowserToolbarHelper.Section.BOOKMARKS)
        ThemeManager.applyStatusBarAppearance(this, dark)

        BrowserToolbarHelper.bind(this, BrowserToolbarHelper.Section.BOOKMARKS, homeChain)

        pageTitle = findViewById(R.id.page_title)
        pageTitle.setText(R.string.bookmarks)
        searchField = findViewById(R.id.search_field)
        searchField.setBackgroundResource(
            if (isNightMode()) R.drawable.bg_search_field_dark else R.drawable.bg_search_field
        )
        listView = findViewById(R.id.bookmark_list)
        emptyView = findViewById(R.id.empty_view)
        batchBar = findViewById(R.id.batch_bar)
        batchDelete = findViewById(R.id.button_batch_delete)
        store = BookmarkStore(this)

        adapter = RowAdapter()
        listView.adapter = adapter

        findViewById<View>(R.id.button_back).setOnClickListener {
            if (homeChain) BrowserToolbarHelper.goHome(this) else finish()
        }
        findViewById<View>(R.id.button_search).setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.button_more).setOnClickListener { showSortMenu() }

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
            if (batchMode) {
                toggleSelection(item)
            } else {
                store.markOpened(item.url)
                BrowserToolbarHelper.openInHome(this, item.url)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position !in entries.indices) return@setOnItemLongClickListener false
            if (batchMode) toggleSelection(entries[position]) else enterBatchMode(entries[position].url)
            true
        }

        findViewById<Button>(R.id.button_batch_all).setOnClickListener { selectAll() }
        batchDelete.setOnClickListener { confirmDeleteSelected() }
        findViewById<Button>(R.id.button_batch_cancel).setOnClickListener { exitBatchMode() }

        refresh()
        PageSlide.play(
            this,
            intent?.getIntExtra(BrowserToolbarHelper.EXTRA_SLIDE_DIR, 0) ?: 0
        )
    }

    /** 排序菜单：与参考图一致的六种排序，当前项打勾 */
    private fun showSortMenu() {
        val modes = listOf(
            BookmarkStore.SORT_MANUAL,
            BookmarkStore.SORT_NEWEST,
            BookmarkStore.SORT_OLDEST,
            BookmarkStore.SORT_LAST_OPENED,
            BookmarkStore.SORT_AZ,
            BookmarkStore.SORT_ZA
        )
        val labels = listOf(
            getString(R.string.sort_manual),
            getString(R.string.sort_newest),
            getString(R.string.sort_oldest),
            getString(R.string.sort_last_opened),
            getString(R.string.sort_az),
            getString(R.string.sort_za)
        )
        val checked = modes.indexOf(store.sortMode()).coerceAtLeast(0)
        AnchoredMenu.show(this, findViewById(R.id.button_more), labels, isNightMode(), checked) { index ->
            store.setSortMode(modes[index])
            refresh()
        }
    }



    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (BrowserToolbarHelper.goToReturnTarget(this)) return
        if (homeChain) BrowserToolbarHelper.goHome(this) else finish()
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

    // ------------------------------------------------------------ 批量栏动画

    private fun showBatchBar() {
        if (batchBar.visibility == View.VISIBLE) return
        batchBar.visibility = View.VISIBLE
        batchBar.post {
            batchBar.translationY = batchBar.height.toFloat()
            batchBar.alpha = 0f
            batchBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun hideBatchBar() {
        if (batchBar.visibility != View.VISIBLE) return
        batchBar.animate()
            .translationY(batchBar.height.toFloat())
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                batchBar.visibility = View.GONE
                batchBar.translationY = 0f
                batchBar.alpha = 1f
            }
            .start()
    }

    private fun selectedColor(): Int {
        val base = getColor(R.color.primary)
        return Color.argb(40, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun enterBatchMode(initialUrl: String?) {
        batchMode = true
        selectedUrls.clear()
        if (initialUrl != null) selectedUrls.add(initialUrl)
        showBatchBar()
        updateBatchState()
    }

    private fun exitBatchMode() {
        batchMode = false
        selectedUrls.clear()
        hideBatchBar()
        updateBatchState()
    }

    private fun toggleSelection(item: Bookmark) {
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
        batchDelete.text = "${getString(R.string.delete)}(${selectedUrls.size})"
        pageTitle.text =
            if (batchMode) "已选 ${selectedUrls.size} 项" else getString(R.string.bookmarks)
        adapter.notifyDataSetChanged()
    }

    private fun confirmDeleteSelected() {
        val count = selectedUrls.size
        if (count == 0) return
        AegisDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("将删除选中的 $count 个书签")
            .setPositiveButton(R.string.delete) { _, _ ->
                store.removeAll(selectedUrls.toList())
                selectedUrls.clear()
                batchMode = false
                hideBatchBar()
                updateBatchState()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        entries.clear()
        val all = store.sorted()
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
        override fun getItem(position: Int): Bookmark = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_site_row, parent, false)
            val item = entries[position]
            val host = hostOf(item.url)
            val titleText = if (item.title.isBlank()) host else item.title

            val avatar = row.findViewById<View>(R.id.site_avatar)
            val letter = row.findViewById<TextView>(R.id.site_letter)
            val title = row.findViewById<TextView>(R.id.site_title)
            val hostView = row.findViewById<TextView>(R.id.site_host)
            val action = row.findViewById<ImageView>(R.id.site_action)

            letter.text = firstLetter(titleText)
            avatar.backgroundTintList =
                ColorStateList.valueOf(FaviconLoader.colorFor(host.ifBlank { item.url }))
            title.text = titleText
            hostView.text = host.ifBlank { item.url }

            if (batchMode) {
                val picked = selectedUrls.contains(item.url)
                action.clearColorFilter()
                action.setImageResource(
                    if (picked) R.drawable.ic_check_circle else R.drawable.bg_check_circle
                )
                if (picked) action.setColorFilter(getColor(R.color.primary))
                action.setOnClickListener { toggleSelection(item) }
            } else {
                action.clearColorFilter()
                action.setImageResource(R.drawable.ic_more_vert)
                action.setColorFilter(getColorAttr(android.R.attr.textColorSecondary))
                action.setOnClickListener { showRowMenu(item) }
            }
            action.visibility = View.VISIBLE

            row.setBackgroundColor(
                if (batchMode && selectedUrls.contains(item.url)) selectedColor() else Color.TRANSPARENT
            )
            // 长按进入批量选择（拖动换位已移除）
            row.setOnLongClickListener {
                if (batchMode) toggleSelection(item) else enterBatchMode(item.url)
                true
            }
            // 行内再兜一层点击：部分机型 ListView 的 itemClick 会被子控件吞掉
            row.setOnClickListener {
                if (batchMode) toggleSelection(item)
                else BrowserToolbarHelper.openInHome(this@BookmarksActivity, item.url)
            }
            return row
        }
    }

    private fun getColorAttr(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) getColor(value.resourceId) else value.data
        } else {
            Color.GRAY
        }
    }

    private fun showRowMenu(bookmark: Bookmark) {
        val dialog = AegisDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.open),
                    "复制链接",
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> BrowserToolbarHelper.openInHome(this, bookmark.url)
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", bookmark.url))
                        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        store.remove(bookmark.url)
                        refresh()
                    }
                }
            }
            .create()
        dialog.show()
    }

    private fun isNightMode(): Boolean =
        dev.aegis.browser.ui.ThemeManager.isDarkNow(this, dev.aegis.browser.ui.ThemeSettings(this))
}
