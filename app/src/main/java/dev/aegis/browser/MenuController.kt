package dev.aegis.browser

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.aegis.browser.core.tab.BLANK_URL
import dev.aegis.browser.core.web.DesktopMode
import dev.aegis.browser.core.web.IncognitoMode
import dev.aegis.browser.menu.MenuCustomizationStore
import dev.aegis.browser.sections.SectionHostActivity
import dev.aegis.browser.settings.SettingsActivity
import dev.aegis.browser.ui.AllMenuDragController
import dev.aegis.browser.ui.MaxHeightScrollView
import dev.aegis.browser.ui.ThemeSettings

/**
 * Google Chrome 风格菜单控制器：
 * - 主菜单：顶部 5 项色调胶囊快捷操作 + 垂直分组功能列表（带实时状态徽章）+ 下滑关闭手势
 * - 全部菜单：支持拖拽排序与显示/隐藏定制
 */
class MenuController(
    private val activity: Activity,
    private val rootView: FrameLayout,
    private val menuCustomizationStore: MenuCustomizationStore,
    private val themeSettings: ThemeSettings,
    private val isDarkMode: () -> Boolean,
    private val currentTabUrl: () -> String?,
    private val isCurrentHostWhitelisted: () -> Boolean,
    private val isCurrentUrlBookmarked: () -> Boolean,
    private val onAction: (String) -> Unit
) {

    interface HostCallbacks {
        fun openBookmarks()
        fun openHistory()
        fun openDownloads()
        fun reloadCurrentPage()
        fun openSettings()
    }

    companion object {
        private const val MAX_MENU_LIST_RATIO = 0.56f
        private const val MENU_BOUNCE_MS = 220L
        private const val MENU_DRAG_CLOSE_RATIO = 0.18f
        private const val MENU_DRAG_EXIT_MS = 160L
        private const val MENU_DRAG_FLING_SPEED = 0.45f
        private const val MENU_DRAG_UP_DAMPING = 0.35f
        private const val MENU_ENTER_MS = 220L
    }

    var mainMenuView: View? = null
        private set
    var allMenuView: View? = null
        private set

    private val editingHidden = LinkedHashSet<String>()

    fun showMenu(callbacks: HostCallbacks) {
        if (mainMenuView != null) return
        val dark = isDarkMode()
        val panel = activity.layoutInflater.inflate(R.layout.view_main_menu, rootView, false)
        val card = panel.findViewById<View>(R.id.menu_card)
        card.setBackgroundResource(if (dark) R.drawable.bg_m3_card_dark else R.drawable.bg_m3_card)

        // 顶部 5 个 Chrome 风格色调快捷操作
        val topContainer = panel.findViewById<LinearLayout>(R.id.menu_top_container)
        val accent = activity.getColor(if (dark) R.color.primary_dark else R.color.primary)
        val pillBg = if (dark) R.drawable.bg_m3_tonal_pill_dark else R.drawable.bg_m3_tonal_pill
        val onHome = currentTabUrl() == BLANK_URL

        topContainer.addView(makeTopPill(R.drawable.ic_star, R.string.bookmarks, accent, pillBg) {
            callbacks.openBookmarks()
        })
        topContainer.addView(makeTopPill(R.drawable.ic_history, R.string.history, accent, pillBg) {
            callbacks.openHistory()
        })
        topContainer.addView(makeTopPill(R.drawable.ic_download, R.string.download, accent, pillBg) {
            callbacks.openDownloads()
        })
        topContainer.addView(makeTopPill(R.drawable.ic_reload, R.string.reload, accent, pillBg, !onHome) {
            callbacks.reloadCurrentPage()
        })
        topContainer.addView(makeTopPill(R.drawable.ic_settings, R.string.settings, accent, pillBg) {
            callbacks.openSettings()
        })

        // 垂直列表（按用户自定义顺序与可见性）
        val listContainer = panel.findViewById<LinearLayout>(R.id.menu_action_list)
        val scrollView = panel.findViewById<MaxHeightScrollView>(R.id.menu_scroll)
        scrollView.maxHeight = (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()

        val ripple = if (dark) R.drawable.bg_m3_ripple_dark else R.drawable.bg_m3_ripple
        val iconNormal = if (dark) 0xFFE3E3E3.toInt() else 0xFF444746.toInt()
        val disabledColor = activity.getColor(R.color.menu_disabled)

        for (actionId in menuCustomizationStore.visibleActions()) {
            val enabled = isActionEnabled(actionId)
            val row = activity.layoutInflater.inflate(R.layout.item_chrome_menu_row, listContainer, false)
            row.setBackgroundResource(ripple)
            val icon = row.findViewById<ImageView>(R.id.chrome_menu_icon)
            val label = row.findViewById<TextView>(R.id.chrome_menu_label)
            val badge = row.findViewById<TextView>(R.id.chrome_menu_badge)

            icon.setImageResource(actionIcon(actionId))
            icon.setColorFilter(if (enabled) iconNormal else disabledColor)
            label.text = actionLabel(actionId)
            label.setTextColor(if (enabled) themeTextPrimary() else disabledColor)

            val badgeText = actionBadge(actionId)
            if (badgeText != null) {
                badge.text = badgeText
                badge.setTextColor(accent)
                badge.setBackgroundResource(pillBg)
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }

            if (enabled) {
                row.isClickable = true
                row.setOnClickListener {
                    dismissMainMenu()
                    onAction(actionId)
                }
            } else {
                row.isClickable = false
                row.alpha = 0.6f
            }
            listContainer.addView(row)
        }

        panel.findViewById<View>(R.id.menu_divider).setBackgroundColor(
            activity.getColor(if (dark) R.color.m3_divider_dark else R.color.m3_divider)
        )
        panel.setOnClickListener { dismissMainMenu() }

        rootView.addView(panel)
        panel.elevation = dp(24).toFloat()
        panel.bringToFront()
        mainMenuView = panel

        applyMenuOrientation(panel, activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        installMenuDrag(panel, card)
        animateEntrance(panel, card)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        mainMenuView?.let { applyMenuOrientation(it, landscape) }
        allMenuView?.let { applyAllMenuOrientation(it, landscape) }
    }

    /** 全部菜单是底部大卡片，横屏可用高度小得多，列表高度上限必须按当前方向重算 */
    private fun applyAllMenuOrientation(panel: View, landscape: Boolean) {
        val scroll = panel.findViewById<MaxHeightScrollView>(R.id.all_menu_scroll) ?: return
        val dm = activity.resources.displayMetrics
        scroll.maxHeight = if (landscape) {
            (dm.heightPixels - dp(180)).coerceAtLeast(dp(96))
        } else {
            (dm.heightPixels * MAX_MENU_LIST_RATIO).toInt()
        }
        scroll.requestLayout()
    }

    private fun applyMenuOrientation(panel: View, landscape: Boolean) {
        val card = panel.findViewById<View>(R.id.menu_card) ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val dm = activity.resources.displayMetrics
        val dragArea = panel.findViewById<View>(R.id.menu_drag_area)
        val scroll = panel.findViewById<MaxHeightScrollView>(R.id.menu_scroll)
        if (landscape) {
            val screenW = dm.widthPixels
            lp.width = minOf(dp(360), (screenW * 0.52f).toInt())
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.marginStart = 0
            lp.marginEnd = dp(12)
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(8)
            // 横屏可视高度有限：藏掉拖拽关闭条，列表高度按当前屏高重算，
            // 否则竖屏开菜单再转横屏，卡片会沿用竖屏的高度上限，上下都被挤出屏幕
            dragArea?.visibility = View.GONE
            scroll?.maxHeight = (dm.heightPixels - dp(120)).coerceAtLeast(dp(96))
            card.translationY = 0f
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM
            lp.marginStart = dp(12)
            lp.marginEnd = dp(12)
            lp.topMargin = 0
            lp.bottomMargin = dp(66)
            dragArea?.visibility = View.VISIBLE
            scroll?.maxHeight = (dm.heightPixels * 0.48f).toInt()
        }
        // 列表高度变了，滚动容器必须重新测量，否则 maxHeight 只有在下次打开菜单时才生效
        scroll?.requestLayout()
        card.layoutParams = lp
        card.requestLayout()
    }

    private fun makeTopPill(
        iconRes: Int,
        labelRes: Int,
        tint: Int,
        bgRes: Int,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): View {
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(bgRes)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            isClickable = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) {
                setOnClickListener {
                    dismissMainMenu()
                    onClick()
                }
            }
        }
        cell.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        cell.addView(TextView(activity).apply {
            setText(labelRes)
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(tint)
            setPadding(0, dp(4), 0, 0)
        })
        return cell
    }

    fun dismissMainMenu() {
        mainMenuView?.let { rootView.removeView(it) }
        mainMenuView = null
    }

    fun showAllMenuDialog() {
        if (allMenuView != null) return
        val dark = isDarkMode()
        val panel = activity.layoutInflater.inflate(R.layout.view_all_menu, rootView, false)
        val card = panel.findViewById<View>(R.id.all_menu_card)
        card.setBackgroundResource(if (dark) R.drawable.bg_m3_card_dark else R.drawable.bg_m3_card)

        val list = panel.findViewById<LinearLayout>(R.id.all_menu_list)
        val scrollView = panel.findViewById<MaxHeightScrollView>(R.id.all_menu_scroll)
        applyAllMenuOrientation(
            panel,
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )

        val editingOrder = menuCustomizationStore.orderedActions().toMutableList()
        editingHidden.clear()
        editingHidden.addAll(menuCustomizationStore.hiddenActions())

        val dragController = AllMenuDragController(list, scrollView, editingOrder)
        rebuildAllMenuRows(list, editingOrder, editingHidden, dragController)

        panel.findViewById<View>(R.id.all_menu_close).setOnClickListener { dismissAllMenu() }
        panel.findViewById<View>(R.id.all_menu_done).setOnClickListener {
            menuCustomizationStore.setOrder(editingOrder)
            for (actionId in MenuCustomizationStore.ALL_ACTIONS) {
                menuCustomizationStore.setHidden(actionId, editingHidden.contains(actionId))
            }
            dismissAllMenu()
        }
        panel.setOnClickListener { dismissAllMenu() }

        rootView.addView(panel)
        allMenuView = panel
        animateEntrance(panel, card)
    }

    fun dismissAllMenu() {
        allMenuView?.let { rootView.removeView(it) }
        allMenuView = null
    }

    private fun rebuildAllMenuRows(
        container: LinearLayout,
        list: List<String>,
        hidden: MutableSet<String>,
        dragController: AllMenuDragController
    ) {
        container.removeAllViews()
        val iconColor = activity.getColor(R.color.icon)
        val handleColor = themeColor(android.R.attr.textColorHint)
        for (actionId in list) {
            val row = activity.layoutInflater.inflate(R.layout.item_all_menu_row, container, false)
            row.tag = actionId
            row.findViewById<ImageView>(R.id.all_row_icon).apply {
                setImageResource(actionIcon(actionId))
                setColorFilter(iconColor)
            }
            row.findViewById<TextView>(R.id.all_row_label).text = actionLabel(actionId)
            row.findViewById<ImageView>(R.id.all_row_handle).setColorFilter(handleColor)
            row.findViewById<CheckBox>(R.id.all_row_visibility).apply {
                isChecked = !hidden.contains(actionId)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) hidden.remove(actionId) else hidden.add(actionId)
                }
            }
            dragController.bind(row)
            container.addView(row)
        }
    }

    private fun installMenuDrag(panel: View, card: View) {
        val dragArea = panel.findViewById<View>(R.id.menu_drag_area)
        val scrim = panel.findViewById<View>(R.id.menu_scrim)
        var downRawY = 0f
        var lastRawY = 0f
        var lastTime = 0L
        var speed = 0f
        dragArea.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    lastRawY = event.rawY
                    lastTime = SystemClock.uptimeMillis()
                    speed = 0f
                    card.animate().cancel()
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    val dy = card.translationY
                    val fling = speed > MENU_DRAG_FLING_SPEED && dy > dp(18)
                    if (dy > card.height * MENU_DRAG_CLOSE_RATIO || fling) {
                        animateMenuExit(card)
                    } else {
                        card.animate().translationY(0f).setDuration(MENU_BOUNCE_MS)
                            .setInterpolator(DecelerateInterpolator()).start()
                        scrim.animate().alpha(1f).setDuration(MENU_BOUNCE_MS).start()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    card.translationY = if (dy < 0f) MENU_DRAG_UP_DAMPING * dy else dy
                    val fade = (dy / (card.height * 1.1f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    scrim.alpha = 1f - fade
                    val now = SystemClock.uptimeMillis()
                    val dt = now - lastTime
                    if (dt > 0) speed = (event.rawY - lastRawY) / dt.toFloat()
                    lastRawY = event.rawY
                    lastTime = now
                    true
                }
                else -> false
            }
        }
    }

    private fun animateEntrance(panel: View, card: View) {
        panel.findViewById<View>(R.id.menu_scrim)?.let { scrim ->
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(160L).start()
        }
        card.alpha = 0f
        card.translationY = dp(36).toFloat()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MENU_ENTER_MS)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    private fun animateMenuExit(card: View) {
        val panel = mainMenuView ?: return
        card.animate()
            .translationY((card.height + dp(32)).toFloat())
            .alpha(0f)
            .setDuration(MENU_DRAG_EXIT_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        val scrim = panel.findViewById<View>(R.id.menu_scrim)
        if (scrim == null) {
            dismissMainMenu()
        } else {
            scrim.animate().alpha(0f).setDuration(MENU_DRAG_EXIT_MS)
                .withEndAction { dismissMainMenu() }.start()
        }
    }

    private fun isActionEnabled(actionId: String): Boolean {
        val onHome = currentTabUrl() == BLANK_URL
        return when (actionId) {
            MenuCustomizationStore.ACTION_SNIFF,
            MenuCustomizationStore.ACTION_ADD_HOME,
            MenuCustomizationStore.ACTION_WHITELIST,
            MenuCustomizationStore.ACTION_RELOAD,
            MenuCustomizationStore.ACTION_ADD_BOOKMARK,
            MenuCustomizationStore.ACTION_SHARE,
            MenuCustomizationStore.ACTION_FIND_IN_PAGE -> !onHome
            else -> true
        }
    }

    private fun actionIcon(actionId: String): Int = when (actionId) {
        MenuCustomizationStore.ACTION_SNIFF -> R.drawable.ic_sniff
        MenuCustomizationStore.ACTION_ORIENTATION -> R.drawable.ic_orientation
        MenuCustomizationStore.ACTION_ADD_HOME -> R.drawable.ic_add_home
        MenuCustomizationStore.ACTION_WEB_SETTINGS -> R.drawable.ic_globe
        MenuCustomizationStore.ACTION_FIND_IN_PAGE -> R.drawable.ic_search
        MenuCustomizationStore.ACTION_DARK_MODE -> when (themeSettings.themeMode) {
            0 -> R.drawable.ic_sun
            1 -> R.drawable.ic_moon
            else -> R.drawable.ic_dark_mode
        }
        MenuCustomizationStore.ACTION_WHITELIST ->
            if (isCurrentHostWhitelisted()) R.drawable.ic_shield_off else R.drawable.ic_shield
        MenuCustomizationStore.ACTION_FONT_SIZE -> R.drawable.ic_font_size
        MenuCustomizationStore.ACTION_INCOGNITO ->
            if (IncognitoMode.isEnabled(activity)) R.drawable.ic_incognito_on else R.drawable.ic_incognito
        MenuCustomizationStore.ACTION_RELOAD -> R.drawable.ic_reload
        MenuCustomizationStore.ACTION_ADD_BOOKMARK ->
            if (isCurrentUrlBookmarked()) R.drawable.ic_star_filled else R.drawable.ic_star
        MenuCustomizationStore.ACTION_EXIT -> R.drawable.ic_exit
        MenuCustomizationStore.ACTION_HOME -> R.drawable.ic_home
        MenuCustomizationStore.ACTION_SHARE -> R.drawable.ic_share
        MenuCustomizationStore.ACTION_TRANSLATE -> R.drawable.ic_translate
        MenuCustomizationStore.ACTION_DESKTOP ->
            if (DesktopMode.isEnabled(activity)) R.drawable.ic_mobile else R.drawable.ic_desktop
        MenuCustomizationStore.ACTION_ALL_MENU -> R.drawable.ic_all_menu
        else -> R.drawable.ic_settings
    }

    private fun actionLabel(actionId: String): String = when (actionId) {
        MenuCustomizationStore.ACTION_SNIFF -> activity.getString(R.string.sniff)
        MenuCustomizationStore.ACTION_ORIENTATION -> activity.getString(R.string.orientation)
        MenuCustomizationStore.ACTION_ADD_HOME -> activity.getString(R.string.add_home)
        MenuCustomizationStore.ACTION_WEB_SETTINGS -> activity.getString(R.string.web_settings)
        MenuCustomizationStore.ACTION_FIND_IN_PAGE -> activity.getString(R.string.find_in_page)
        MenuCustomizationStore.ACTION_DARK_MODE -> activity.getString(R.string.dark_mode)
        MenuCustomizationStore.ACTION_WHITELIST ->
            if (isCurrentHostWhitelisted()) activity.getString(R.string.restore_blocking)
            else activity.getString(R.string.whitelist_site)
        MenuCustomizationStore.ACTION_FONT_SIZE -> activity.getString(R.string.font_size)
        MenuCustomizationStore.ACTION_INCOGNITO -> activity.getString(R.string.incognito)
        MenuCustomizationStore.ACTION_RELOAD -> activity.getString(R.string.reload)
        MenuCustomizationStore.ACTION_ADD_BOOKMARK -> activity.getString(R.string.add_bookmark)
        MenuCustomizationStore.ACTION_EXIT -> activity.getString(R.string.exit)
        MenuCustomizationStore.ACTION_HOME -> activity.getString(R.string.home)
        MenuCustomizationStore.ACTION_SHARE -> activity.getString(R.string.share)
        MenuCustomizationStore.ACTION_TRANSLATE -> activity.getString(R.string.translate_page)
        MenuCustomizationStore.ACTION_DESKTOP ->
            if (DesktopMode.isEnabled(activity)) activity.getString(R.string.mobile_site)
            else activity.getString(R.string.desktop_site)
        MenuCustomizationStore.ACTION_ALL_MENU -> activity.getString(R.string.menu_customize)
        else -> ""
    }

    private fun actionBadge(actionId: String): String? = when (actionId) {
        MenuCustomizationStore.ACTION_DESKTOP ->
            if (DesktopMode.isEnabled(activity)) activity.getString(R.string.suggest_enabled_on) else null
        MenuCustomizationStore.ACTION_INCOGNITO ->
            if (IncognitoMode.isEnabled(activity)) activity.getString(R.string.suggest_enabled_on) else null
        else -> null
    }

    private fun themeTextPrimary(): Int {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            return if (value.resourceId != 0) activity.getColor(value.resourceId) else value.data
        }
        return activity.getColor(R.color.icon)
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
