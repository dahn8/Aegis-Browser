package dev.aegis.browser.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 子页横向拖拽容器：手指横向拖动时，顶栏以下的内容整块跟手平移；
 * 松手超过阈值就切到相邻页（书签 / 历史 / 下载），否则回弹。
 *
 * 纵向滚动不受影响：只有横向位移明显压过纵向时才接管事件。
 */
class SectionSwipeLayout(context: Context) : FrameLayout(context) {

    /** 顶栏所在的前几个子视图不参与平移 */
    var headerCount: Int = 1

    /** 只平移它的子视图（顶栏除外） */
    var contentRoot: ViewGroup? = null

    /** delta = -1 右滑去上一页，+1 左滑去下一页；返回 true 表示已处理 */
    var onCommit: ((Int) -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var offset = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                offset = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                        // 从当前位置重新起算，避免接管时内容一次性跳出去
                        downX = ev.x
                        downY = ev.y
                        dragging = true
                        contentRoot?.let { root ->
                            for (index in headerCount until root.childCount) {
                                root.getChildAt(index).animate().cancel()
                            }
                        }
                        return true
                    }
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                offset = ev.x - downX
                applyOffset(offset)
            }
            MotionEvent.ACTION_UP -> finishDrag(ev.x - downX)
            MotionEvent.ACTION_CANCEL -> settleBack()
        }
        return true
    }

    private fun applyOffset(value: Float) {
        val root = contentRoot ?: return
        val width = root.width.toFloat()
        if (width <= 0f) return
        // 到头了给一点阻尼，别让内容整块拉飞
        val resisted = when {
            value > width * 0.30f -> width * 0.30f + (value - width * 0.30f) * 0.25f
            value < -width * 0.30f -> -width * 0.30f + (value + width * 0.30f) * 0.25f
            else -> value
        }
        setContentTranslation(resisted)
    }

    private fun setContentTranslation(value: Float) {
        val root = contentRoot ?: return
        for (index in headerCount until root.childCount) {
            val child = root.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.translationX = value
        }
    }

    private fun finishDrag(raw: Float) {
        val root = contentRoot ?: return
        val width = root.width.toFloat()
        val threshold = width * 0.22f
        if (raw <= -threshold) {
            commit(1)
        } else if (raw >= threshold) {
            commit(-1)
        } else {
            settleBack()
        }
    }

    private fun commit(delta: Int) {
        val handler = onCommit
        if (handler == null || !handler(delta)) {
            settleBack()
        }
    }

    private fun settleBack() {
        val root = contentRoot ?: return
        dragging = false
        for (index in headerCount until root.childCount) {
            val child = root.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.animate()
                .translationX(0f)
                .setDuration(200L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
}
