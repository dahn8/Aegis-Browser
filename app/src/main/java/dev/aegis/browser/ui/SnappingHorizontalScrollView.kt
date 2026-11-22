package dev.aegis.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 轻量分页横向滚动容器（无 AndroidX 依赖）：
 * 子级为一个水平 LinearLayout，内部每个“页”等宽（=本容器宽度）。
 *
 * 触摸拦截、拖拽滚动、页宽对齐全部由本类自主完成，不依赖父类
 * HorizontalScrollView 的内部拖拽状态，因此在主页/网页等不同焦点
 * 环境下行为一致。手指抬起时综合滑动速度与拖动距离决定目标页，
 * 一次手势即可翻页。
 */
class SnappingHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private var currentPage = 0
    private var pageChanged: ((Int) -> Unit)? = null

    private var downX = 0f
    private var lastX = 0f
    private var startPage = 0
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun setOnPageChanged(callback: (Int) -> Unit) {
        pageChanged = callback
    }

    fun currentPage(): Int = currentPage

    private fun maxPage(): Int {
        val child = getChildAt(0) ?: return 0
        if (width <= 0) return 0
        return ((child.width - width) / width).coerceAtLeast(0)
    }

    private fun maxScroll(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.width - width).coerceAtLeast(0)
    }

    /** 把每个子页宽度对齐为容器宽度，保证整页滚动，避免初始 WRAP_CONTENT 时序问题 */
    fun applyPageWidths() {
        val w = width
        if (w <= 0) return
        val pages = getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until pages.childCount) {
            val page = pages.getChildAt(i)
            if (page.width != w) {
                page.layoutParams = LinearLayout.LayoutParams(
                    w, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPageWidths()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                lastX = ev.x
                startPage = currentPage
                dragging = false
                obtainTracker().clear()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (abs(ev.x - downX) > touchSlop) {
                    dragging = true
                    lastX = ev.x
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (width <= 0) return super.onTouchEvent(ev)
        obtainTracker().addMovement(ev)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                lastX = ev.x
                startPage = currentPage
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    val delta = lastX - ev.x
                    val target = (scrollX + delta).toInt().coerceIn(0, maxScroll())
                    scrollTo(target, 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(VELOCITY_UNITS)
                // xVelocity 为负表示手指向左滑（下一页）
                val velocityX = tracker?.xVelocity ?: 0f
                val totalDrag = downX - ev.x

                val target = when {
                    abs(velocityX) >= FLING_VELOCITY ->
                        if (velocityX < 0) startPage + 1 else startPage - 1
                    abs(totalDrag) >= width * DRAG_RATIO ->
                        if (totalDrag > 0) startPage + 1 else startPage - 1
                    else -> startPage
                }
                snapToPage(target)
                dragging = false
            }
        }
        return true
    }

    /** 拦截父类自带 fling：翻页统一在 ACTION_UP 处理，避免动画冲突 */
    override fun fling(velocity: Int) {
        // 故意不调用 super.fling
    }

    private var pendingPage = -1

    /** 内容重建后原地恢复到指定页：等新子页量完宽高再跳，避免被旧宽度夹到第 1 页 */
    fun jumpToPage(page: Int) {
        pendingPage = page
        requestLayout()
        post { applyPendingPage() }
    }

    private fun applyPendingPage() {
        val page = pendingPage
        if (page < 0 || width <= 0 || getChildAt(0) == null) return
        if ((getChildAt(0)?.width ?: 0) <= 0) return
        pendingPage = -1
        val target = page.coerceIn(0, maxPage())
        currentPage = target
        scrollTo(target * width, 0)
        pageChanged?.invoke(target)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        applyPendingPage()
    }

    /** 吸附到指定页 */
    fun snapToPage(page: Int) {
        val target = page.coerceIn(0, maxPage())
        currentPage = target
        smoothScrollTo(target * width, 0)
        pageChanged?.invoke(target)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val w = width
        if (w > 0) {
            val p = ((l + w / 2) / w).coerceIn(0, maxPage())
            if (p != currentPage) {
                currentPage = p
                pageChanged?.invoke(p)
            }
        }
    }

    private fun obtainTracker(): VelocityTracker {
        val existing = velocityTracker
        if (existing != null) return existing
        return VelocityTracker.obtain().also { velocityTracker = it }
    }

    override fun onDetachedFromWindow() {
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val VELOCITY_UNITS = 1000
        private const val FLING_VELOCITY = 400f
        private const val DRAG_RATIO = 0.15f
    }
}
