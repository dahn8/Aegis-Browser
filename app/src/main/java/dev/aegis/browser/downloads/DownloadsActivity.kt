package dev.aegis.browser.downloads

import dev.aegis.browser.ui.AegisDialog
import android.content.Context
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.webkit.MimeTypeMap
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.PageSlide
import dev.aegis.browser.ui.AnchoredMenu
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.util.Calendar

/**
 * 下载页：大字标题 + 存储占用行 + 分组列表。
 * 每行左侧为圆形进度环，中心按钮即暂停 / 继续；长按弹出分享、重命名、删除卡片。
 */
class DownloadsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    private lateinit var store: DownloadManagerStore
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var batchBar: View
    private lateinit var batchDelete: Button
    private lateinit var pageTitle: TextView
    private lateinit var storageView: TextView
    private lateinit var adapter: DownloadAdapter

    private class GroupHeader(val label: String)
    private class SpeedSample(val bytes: Long, val time: Long)

    /** 列表实际内容：分组标题或下载记录 */
    private val rows = mutableListOf<Any>()
    private val samples = HashMap<String, SpeedSample>()
    private val speeds = HashMap<String, Long>()

    private val homeChain: Boolean by lazy {
        intent?.getBooleanExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, false) == true
    }

    private var keyword = ""
    private var batchMode = false
    private val selectedIds = mutableSetOf<String>()
    private var pendingInstall: DownloadRecord? = null

    /** 仅结构变化（新增/状态/文件名）时才整表刷新，避免高频进度回调打断点击 */
    private var lastSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        BrowserToolbarHelper.animateSectionContentIn(this)
        BrowserToolbarHelper.installSwipe(this, BrowserToolbarHelper.Section.DOWNLOADS)
        ThemeManager.applyStatusBarAppearance(this, dark)

        BrowserToolbarHelper.bind(this, BrowserToolbarHelper.Section.DOWNLOADS, homeChain)

        pageTitle = findViewById(R.id.page_title)
        storageView = findViewById(R.id.text_storage)
        listView = findViewById(R.id.download_list)
        emptyView = findViewById(R.id.empty_view)
        batchBar = findViewById(R.id.batch_bar)
        batchDelete = findViewById(R.id.button_batch_delete)
        store = DownloadManagerStore.get(this)

        adapter = DownloadAdapter()
        listView.adapter = adapter

        findViewById<View>(R.id.button_more).setOnClickListener { showMoreMenu() }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position)
            if (row !is DownloadRecord) return@setOnItemLongClickListener false
            if (batchMode) toggleSelection(row) else showItemActions(row)
            true
        }

        findViewById<Button>(R.id.button_batch_all).setOnClickListener { selectAll() }
        batchDelete.setOnClickListener { confirmDeleteSelected() }
        findViewById<Button>(R.id.button_batch_cancel).setOnClickListener { exitBatchMode() }

        store.onChange = { refresh() }
        updateStorageLine()
        refresh()
        PageSlide.play(
            this,
            intent?.getIntExtra(BrowserToolbarHelper.EXTRA_SLIDE_DIR, 0) ?: 0
        )
    }

    // ---------------- 顶部与菜单 ----------------

    private fun updateStorageLine() {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val used = total - stat.availableBytes
        storageView.text = getString(
            R.string.storage_usage, formatSize(total), formatSize(used)
        )
    }


    private fun showMoreMenu() {
        val labels = if (batchMode) listOf("退出批量管理") else listOf(getString(R.string.batch_delete))
        AnchoredMenu.show(this, findViewById(R.id.button_more), labels, isNightMode()) {
            if (batchMode) exitBatchMode() else enterBatchMode(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (BrowserToolbarHelper.goToReturnTarget(this)) return
        if (homeChain) BrowserToolbarHelper.goHome(this) else finish()
    }

    /** 长按：白色圆角卡片，分享 / 重命名 / 删除 */
    private fun showItemActions(record: DownloadRecord) {
        val dialog = AegisDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.share),
                    getString(R.string.rename),
                    getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> shareFile(record)
                    1 -> showRenameDialog(record)
                    else -> confirmDelete(record)
                }
            }
            .create()
        dialog.show()
    }

    private fun shareFile(record: DownloadRecord) {
        val uriString = record.uriString
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "文件位置不可用", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = resolveMime(record)
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "没有可分享的应用", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show() }
    }

    private fun showRenameDialog(record: DownloadRecord) {
        if (record.status != DownloadRecord.Status.COMPLETED) {
            Toast.makeText(this, "仅已完成文件可重命名", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(record.filename)
            setSelection(record.filename.length)
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty() || name == record.filename) return@setPositiveButton
                if (!store.rename(record.id, name)) {
                    Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isNightMode(): Boolean =
        dev.aegis.browser.ui.ThemeManager.isDarkNow(this, dev.aegis.browser.ui.ThemeSettings(this))

    // ---------------- 批量 ----------------

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

    private fun enterBatchMode(initialId: String?) {
        batchMode = true
        selectedIds.clear()
        if (initialId != null) selectedIds.add(initialId)
        showBatchBar()
        updateBatchState()
    }

    private fun exitBatchMode() {
        batchMode = false
        selectedIds.clear()
        hideBatchBar()
        updateBatchState()
    }

    private fun toggleSelection(record: DownloadRecord) {
        if (selectedIds.contains(record.id)) selectedIds.remove(record.id)
        else selectedIds.add(record.id)
        if (selectedIds.isEmpty()) {
            exitBatchMode()
            return
        }
        updateBatchState()
    }

    private fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(records().map { it.id })
        updateBatchState()
    }

    private fun updateBatchState() {
        batchDelete.text = "${getString(R.string.delete)}(${selectedIds.size})"
        pageTitle.text =
            if (batchMode) "已选 ${selectedIds.size} 项" else getString(R.string.downloads)
        lastSignature = ""
        refresh()
    }

    private fun confirmDeleteSelected() {
        val count = selectedIds.size
        if (count == 0) return
        AegisDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("将删除选中的 $count 项及其本地文件")
            .setPositiveButton(R.string.delete) { _, _ ->
                val ids = selectedIds.toList()
                ids.forEach { store.remove(it) }
                selectedIds.clear()
                batchMode = false
                hideBatchBar()
                updateBatchState()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(record: DownloadRecord) {
        AegisDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("${record.filename}\n将同时删除已下载的本地文件")
            .setPositiveButton(R.string.delete) { _, _ -> store.remove(record.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 列表构建 ----------------

    private fun records(): List<DownloadRecord> = store.snapshot()

    private fun visibleRecords(): List<DownloadRecord> {
        val all = records()
        if (keyword.isEmpty()) return all
        return all.filter { it.filename.contains(keyword, true) }
    }

    private fun refresh() {
        rows.clear()
        val list = visibleRecords()
        val now = System.currentTimeMillis()
        val startOfToday = startOfToday(now)

        list.groupBy { groupLabel(it, now, startOfToday) }.forEach { (label, items) ->
            if (items.isEmpty()) return@forEach
            rows.add(GroupHeader(label))
            rows.addAll(items)
        }

        // 采样下载速度，用于估算剩余时间
        list.forEach { record ->
            if (record.status == DownloadRecord.Status.DOWNLOADING) {
                val prev = samples[record.id]
                if (prev != null) {
                    val deltaTime = now - prev.time
                    val deltaBytes = record.downloadedBytes - prev.bytes
                    if (deltaTime > 400 && deltaBytes > 0) {
                        speeds[record.id] = (deltaBytes * 1000L) / deltaTime
                    }
                }
                samples[record.id] = SpeedSample(record.downloadedBytes, now)
            } else {
                samples.remove(record.id)
                speeds.remove(record.id)
            }
        }

        val signature = buildString {
            list.forEach {
                append(it.id).append('|').append(it.status).append('|')
                    .append(it.filename).append('|')
            }
            append("filter=").append(keyword)
            append("batch=").append(batchMode)
            append("selected=").append(selectedIds.size)
        }
        if (signature == lastSignature) {
            updateVisibleProgress()
        } else {
            lastSignature = signature
            adapter.notifyDataSetChanged()
        }
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 进度回调时只更新已有可见行，不整表重建，避免打断点击 */
    private fun updateVisibleProgress() {
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        if (first < 0 || last < first) return
        for (position in first..last) {
            val record = rows.getOrNull(position) as? DownloadRecord ?: continue
            val child = listView.getChildAt(position - first) ?: continue
            child.findViewById<TextView>(R.id.text_status)?.text = statusText(record)
            val ring = child.findViewById<DownloadRingView>(R.id.ring_progress) ?: continue
            ring.state = ringState(record)
            ring.progress = record.progressPercent()
        }
    }

    private fun startOfToday(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun groupLabel(record: DownloadRecord, now: Long, startOfToday: Long): String {
        if (record.status == DownloadRecord.Status.DOWNLOADING ||
            record.status == DownloadRecord.Status.PAUSED
        ) {
            return getString(R.string.group_just_now)
        }
        val created = record.createdAt
        return when {
            created <= 0L || now - created <= 60_000L -> getString(R.string.group_just_now)
            created >= startOfToday -> getString(R.string.group_today)
            created >= startOfToday - 24 * 60 * 60 * 1000L -> getString(R.string.group_yesterday)
            else -> getString(R.string.group_earlier)
        }
    }

    private fun statusText(record: DownloadRecord): String {
        val downloaded = formatSize(record.downloadedBytes)
        val total = formatSize(record.totalBytes)
        val percent = record.progressPercent()
        return when (record.status) {
            DownloadRecord.Status.DOWNLOADING -> {
                val remaining = estimateRemaining(record.id, record)
                when {
                    percent >= 0 && remaining != null -> getString(
                        R.string.downloading_progress, downloaded, total, remaining
                    )
                    percent >= 0 -> "已下载 $downloaded，共 $total"
                    else -> getString(R.string.downloading_progress_unknown, downloaded)
                }
            }
            DownloadRecord.Status.PAUSED -> "已暂停·已下载 $downloaded，共 $total"
            DownloadRecord.Status.COMPLETED -> "已完成·$total"
            DownloadRecord.Status.FAILED -> "下载失败·${record.error.orEmpty()}"
        }
    }

    private fun ringState(record: DownloadRecord): DownloadRingView.State = when (record.status) {
        DownloadRecord.Status.DOWNLOADING -> DownloadRingView.State.DOWNLOADING
        DownloadRecord.Status.PAUSED -> DownloadRingView.State.PAUSED
        DownloadRecord.Status.COMPLETED -> DownloadRingView.State.COMPLETED
        DownloadRecord.Status.FAILED -> DownloadRingView.State.FAILED
    }

    private inner class DownloadAdapter : BaseAdapter() {

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is GroupHeader) 0 else 1

        override fun isEnabled(position: Int): Boolean = rows[position] is DownloadRecord

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is GroupHeader) {
                val header = convertView ?: layoutInflater.inflate(
                    R.layout.item_group_header, parent, false
                )
                header.findViewById<TextView>(R.id.group_title).text = row.label
                return header
            }
            val record = row as DownloadRecord
            val view = convertView ?: layoutInflater.inflate(
                R.layout.item_download, parent, false
            )
            bind(view, record)
            return view
        }
    }

    private fun bind(row: View, record: DownloadRecord) {
        row.findViewById<TextView>(R.id.text_filename).text = record.filename
        row.findViewById<TextView>(R.id.text_status).text = statusText(record)

        val ring = row.findViewById<DownloadRingView>(R.id.ring_progress)
        val close = row.findViewById<ImageView>(R.id.btn_row_close)
        val checkbox = row.findViewById<CheckBox>(R.id.checkbox_select)

        ring.state = ringState(record)
        ring.progress = record.progressPercent()
        ring.isClickable = true
        ring.setOnClickListener { onRingClick(record) }

        // 整行点击（文件名区域）也能打开文件
        row.isClickable = true
        row.setOnClickListener {
            if (batchMode) toggleSelection(record) else onRowClick(record)
        }

        if (batchMode) {
            checkbox.visibility = View.VISIBLE
            ring.visibility = View.INVISIBLE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = selectedIds.contains(record.id)
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(record.id) else selectedIds.remove(record.id)
                if (selectedIds.isEmpty()) exitBatchMode() else updateBatchState()
            }
            close.visibility = View.GONE
        } else {
            checkbox.visibility = View.GONE
            ring.visibility = View.VISIBLE
            close.visibility = View.VISIBLE
            close.setOnClickListener { confirmDelete(record) }
        }
    }

    /** 圆环中心：下载中→暂停，已暂停→继续 */
    private fun onRingClick(record: DownloadRecord) {
        when (record.status) {
            DownloadRecord.Status.DOWNLOADING -> {
                if (!store.pause(record.id)) {
                    Toast.makeText(this, R.string.pause_not_supported, Toast.LENGTH_SHORT).show()
                }
            }
            DownloadRecord.Status.PAUSED -> {
                if (!store.resume(record.id)) {
                    Toast.makeText(this, "无法继续，请重新下载", Toast.LENGTH_SHORT).show()
                }
            }
            DownloadRecord.Status.FAILED -> {
                Toast.makeText(this, "该任务已中断，请重新下载", Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
    }

    /** 行点击：已完成文件直接打开（APK 会引导安装） */
    private fun onRowClick(record: DownloadRecord) {
        when (record.status) {
            DownloadRecord.Status.COMPLETED -> openFile(record)
            DownloadRecord.Status.DOWNLOADING, DownloadRecord.Status.PAUSED ->
                Toast.makeText(this, "点左侧圆环可暂停 / 继续", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, record.error ?: "下载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun estimateRemaining(id: String, record: DownloadRecord): String? {
        val speed = speeds[id] ?: return null
        if (speed <= 0 || record.totalBytes <= 0) return null
        val left = record.totalBytes - record.downloadedBytes
        if (left <= 0) return null
        val seconds = left / speed
        return when {
            seconds < 60 -> "$seconds 秒"
            seconds < 3600 -> "${seconds / 60} 分"
            else -> "${seconds / 3600} 时"
        }
    }

    // ---------------- 打开 / 定位文件 ----------------

    private fun revealFile(record: DownloadRecord) {
        val uriString = record.uriString
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "文件位置不可用", Toast.LENGTH_SHORT).show()
            return
        }
        if (!uriString.startsWith("content://")) {
            Toast.makeText(
                this,
                "文件位于应用私有目录：${Uri.parse(uriString).path}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val dirUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_PROVIDER, "primary:Download"
        )
        val fileUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_PROVIDER, "primary:Download/${record.filename}"
        )
        val viewDir = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        pickFileManager(viewDir)?.let { targeted ->
            if (runCatching { startActivity(targeted) }.isSuccess) return
        }

        val fallback = runCatching {
            Intent(Intent.ACTION_VIEW).setData(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            )
        }.getOrNull()
        if (fallback != null && runCatching { startActivity(fallback) }.isSuccess) return

        Toast.makeText(this, "未找到可用的文件管理器", Toast.LENGTH_SHORT).show()
    }

    private fun pickFileManager(base: Intent): Intent? {
        val handlers = runCatching {
            packageManager.queryIntentActivities(base, 0)
        }.getOrDefault(emptyList())
        if (handlers.isEmpty()) return null

        val knownFileManagers = setOf(
            "com.android.filemanager",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.sec.android.app.myfiles",
            "com.android.fileexplorer",
            "com.huawei.hidisk"
        )

        val chosen = handlers.firstOrNull { it.activityInfo.packageName in knownFileManagers }
            ?: handlers.firstOrNull {
                val flags = it.activityInfo.applicationInfo.flags
                flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            }
            ?: handlers.firstOrNull {
                runCatching {
                    val label = packageManager.getApplicationLabel(
                        it.activityInfo.applicationInfo
                    ).toString()
                    label.contains("文件")
                }.getOrDefault(false)
            }
            ?: handlers.first()

        return Intent(base).apply {
            setClassName(chosen.activityInfo.packageName, chosen.activityInfo.name)
        }
    }

    private fun openFile(record: DownloadRecord) {
        val uriString = record.uriString
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "文件位置不可用", Toast.LENGTH_SHORT).show()
            return
        }

        val mime = resolveMime(record)

        if (mime == APK_MIME || record.filename.endsWith(".apk", ignoreCase = true)) {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstall = record
                AegisDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("请在系统设置中允许 Aegis 安装未知应用，返回后将继续安装该文件。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
        }

        launchExternal(Uri.parse(uriString), mime)
    }

    private fun launchExternal(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMime(record: DownloadRecord): String {
        val stored = record.mime.trim()
        if (stored.isNotEmpty() && stored != "application/octet-stream") return stored
        val ext = record.filename.substringAfterLast('.', "").lowercase()
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromExt ?: stored.ifBlank { "*/*" }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            val pending = pendingInstall
            if (pending != null && packageManager.canRequestPackageInstalls()) {
                pendingInstall = null
                val uri = pending.uriString
                if (!uri.isNullOrBlank()) {
                    launchExternal(Uri.parse(uri), resolveMime(pending))
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    override fun onDestroy() {
        store.onChange = null
        super.onDestroy()
    }

    companion object {
        private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"
        private const val REQUEST_INSTALL_PERMISSION = 1001
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
