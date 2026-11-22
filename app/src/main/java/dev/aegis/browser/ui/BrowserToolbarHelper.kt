package dev.aegis.browser.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.aegis.browser.MainActivity
import dev.aegis.browser.R
import dev.aegis.browser.bookmarks.BookmarksActivity
import dev.aegis.browser.downloads.DownloadsActivity
import dev.aegis.browser.history.HistoryActivity

/**
 * 子页面通用快捷工具栏的绑定：高亮当前页面、跳转其它页面、关闭。
 * 所有返回 / 关闭入口都统一回到主页，避免子页之间叠栈。
 */
object BrowserToolbarHelper {

    enum class Section { BOOKMARKS, HISTORY, DOWNLOADS }

    /** 子页选中网址后带回主页的 extra */
    const val EXTRA_OPEN_URL = "aegis_open_url"

    /** 标记该页面属于"主页 → 子页"链路：退出时直接回主页，而不是回上一页 */
    const val EXTRA_HOME_CHAIN = "aegis_home_chain"

    /** 子页之间互跳的标记：这种链路不能用结果回传，改用 CLEAR_TOP 回主页 */
    private const val EXTRA_FROM_SECTION = "aegis_from_section"
    /** 滑动切页进场更快一点，手感更连贯 */
    private const val EXTRA_SLIDE_FAST = "aegis_slide_fast"

    /** 从设置页等处进来的子页：关闭时回到哪个页面（目前支持 "settings"） */
    const val EXTRA_RETURN_TO = "aegis_return_to"

    /** 横滑方向标记：1 = 往右页走（内容从右滑入），-1 = 往左页走 */
    const val EXTRA_SLIDE_DIR = "aegis_slide_dir"

    /** 子页内容滑入方向：right / left，顶栏固定不参与动画 */
    const val EXTRA_SLIDE_FROM = "aegis_slide_from"

    /** 待主页消费的网址：结果回传在部分机型上会丢，这里做进程内中转兜底 */
    private var pendingOpenUrl: String? = null

    fun takePendingOpenUrl(): String? {
        val url = pendingOpenUrl
        pendingOpenUrl = null
        return url
    }

    /** 只刷新三个页签的选中态，不装点击监听 */
    fun updateTabs(activity: Activity, current: Section) {
        val accent = activity.getColor(R.color.primary)
        val plain = resolveColor(activity, android.R.attr.textColorSecondary, Color.GRAY)
        setTabLook(activity, R.id.icon_bookmarks, R.id.ind_bookmarks, accent, plain, current == Section.BOOKMARKS)
        setTabLook(activity, R.id.icon_history, R.id.ind_history, accent, plain, current == Section.HISTORY)
        setTabLook(activity, R.id.icon_downloads, R.id.ind_downloads, accent, plain, current == Section.DOWNLOADS)
    }

    /**
     * 绑定工具栏；current 为当前页面。
     * onSelect 不为空时页签只在组内切换（三页同一个窗口），否则开新页面。
     */
    fun bind(
        activity: Activity,
        current: Section,
        fromHome: Boolean = true,
        onSelect: ((Section) -> Unit)? = null
    ) {
        updateTabs(activity, current)

        activity.findViewById<View>(R.id.tab_bookmarks).setOnClickListener {
            if (onSelect != null) onSelect(Section.BOOKMARKS)
            else if (current != Section.BOOKMARKS) {
                startSection(activity, BookmarksActivity::class.java, fromHome, directionTo(current, Section.BOOKMARKS))
            }
        }
        activity.findViewById<View>(R.id.tab_history).setOnClickListener {
            if (onSelect != null) onSelect(Section.HISTORY)
            else if (current != Section.HISTORY) {
                startSection(activity, HistoryActivity::class.java, fromHome, directionTo(current, Section.HISTORY))
            }
        }
        activity.findViewById<View>(R.id.tab_downloads).setOnClickListener {
            if (onSelect != null) onSelect(Section.DOWNLOADS)
            else if (current != Section.DOWNLOADS) {
                startSection(activity, DownloadsActivity::class.java, fromHome, directionTo(current, Section.DOWNLOADS))
            }
        }
        activity.findViewById<View>(R.id.toolbar_close).setOnClickListener {
            if (goToReturnTarget(activity)) return@setOnClickListener
            if (fromHome) goHome(activity) else activity.finish()
        }
    }

    /** 直接回到主页（清掉中间所有子页），不携带网址 */
    fun goHome(activity: Activity) {
        activity.startActivity(homeIntent(activity))
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    /** 回到主页并在当前标签打开网址 */
    fun openInHome(activity: Activity, url: String) {
        pendingOpenUrl = url
        val cameFromSection =
            activity.intent?.getBooleanExtra(EXTRA_FROM_SECTION, false) == true
        if (!cameFromSection) {
            // 主页直接进来的页面都是以 startActivityForResult 打开：
            // 用结果回传，主页在 onActivityResult 里打开，避免 CLEAR_TOP 吞掉 onNewIntent
            activity.setResult(
                Activity.RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_OPEN_URL, url)
                    data = Uri.parse(url)
                }
            )
            activity.finish()
            activity.overridePendingTransition(0, 0)
            return
        }
        val intent = homeIntent(activity).apply {
            putExtra(EXTRA_OPEN_URL, url)
            data = Uri.parse(url)
        }
        activity.startActivity(intent)
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    /** 目标页在右边 → 1（从右滑入），在左边 → -1（从左滑入） */
    private fun directionTo(from: Section, to: Section): Int =
        if (to.ordinal > from.ordinal) 1 else -1

    private fun startSection(
        activity: Activity,
        target: Class<*>,
        fromHome: Boolean,
        direction: Int,
        fast: Boolean = false
    ) {
        activity.startActivity(Intent(activity, target).apply {
            putExtra(EXTRA_HOME_CHAIN, fromHome)
            putExtra(EXTRA_FROM_SECTION, true)
            putExtra(EXTRA_SLIDE_DIR, direction)
            if (fast) putExtra(EXTRA_SLIDE_FAST, true)
            activity.intent?.getStringExtra(EXTRA_RETURN_TO)?.let { putExtra(EXTRA_RETURN_TO, it) }
        })
        // 窗口本身不做转场，只让下方内容滑入，顶栏看起来是连续的
        activity.overridePendingTransition(0, 0)
    }

    /**
     * 子页进场：顶栏（图标条 + 分隔线）原地不动，下面的标题、搜索框、列表整体从
     * 指定方向滑入。方向跟点击顺序一致：点右边的页从右滑入，点左边的页从左滑入。
     */
    fun animateSectionContentIn(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = (content.getChildAt(0) as? ViewGroup) ?: return
        val fast = activity.intent?.getBooleanExtra(EXTRA_SLIDE_FAST, false) == true
        val direction = activity.intent?.getIntExtra(EXTRA_SLIDE_DIR, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?: if (activity.intent?.getStringExtra(EXTRA_SLIDE_FROM) == "left") -1 else 1
        root.post {
            val width = root.width
            if (width <= 0 || root.childCount <= 1) return@post
            val offset = if (direction > 0) width.toFloat() else -width.toFloat()
            val duration = if (fast) 170L else 220L
            for (index in 1 until root.childCount) {
                val child = root.getChildAt(index)
                if (child.visibility == View.GONE) continue
                child.translationX = offset
                child.animate()
                    .translationX(0f)
                    .setDuration(duration)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    /** 给子页装上横向拖拽切页 */
    fun installSwipe(activity: Activity, current: Section) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val page = content.getChildAt(0) as? ViewGroup ?: return
        if (page.parent !== content) return
        val swipe = SectionSwipeLayout(activity)
        content.removeView(page)
        swipe.addView(
            page,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            swipe,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        swipe.contentRoot = page
        swipe.onCommit = { delta -> switchBySwipe(activity, current, delta) }
    }

    /** 滑动翻页：delta -1 = 右滑（回上一页，最左边则回主页），+1 = 左滑（去下一页） */
    private fun switchBySwipe(activity: Activity, current: Section, delta: Int): Boolean {
        val target = current.ordinal + delta
        if (target < 0) {
            goHome(activity)
            return true
        }
        if (target > Section.DOWNLOADS.ordinal) return false
        val section = Section.values()[target]
        val cls = when (section) {
            Section.BOOKMARKS -> BookmarksActivity::class.java
            Section.HISTORY -> HistoryActivity::class.java
            Section.DOWNLOADS -> DownloadsActivity::class.java
        }
        startSection(
            activity, cls,
            fromHome = activity.intent?.getBooleanExtra(EXTRA_HOME_CHAIN, false) == true,
            direction = delta,
            fast = true
        )
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }

    /** 若这一页是从设置页进来的，直接切回设置页；否则返回 false */
    fun goToReturnTarget(activity: Activity): Boolean {
        val targetName = activity.intent?.getStringExtra(EXTRA_RETURN_TO) ?: return false
        val target = when (targetName) {
            "settings" -> dev.aegis.browser.settings.SettingsActivity::class.java
            else -> return false
        }
        activity.startActivity(Intent(activity, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }

    private fun homeIntent(activity: Activity): Intent =
        Intent(activity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun setTabLook(
        activity: Activity,
        iconId: Int,
        indicatorId: Int,
        accent: Int,
        plain: Int,
        selected: Boolean
    ) {
        val icon = activity.findViewById<ImageView>(iconId)
        val indicator = activity.findViewById<View>(indicatorId)
        icon.setColorFilter(if (selected) accent else plain)
        indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun resolveColor(activity: Activity, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
        } else {
            fallback
        }
    }
}
