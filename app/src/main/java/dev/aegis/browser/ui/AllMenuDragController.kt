package dev.aegis.browser.ui

import dev.aegis.browser.R
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 全部菜单的跟手拖拽控制器（纯触摸，不依赖系统 Drag & Drop）。
 *
 * 长按后被拖行严格钉在手指上：translationY 同时补偿手指位移与滚动
 * 偏移，因此即使边缘自动滚动、手指静止，被拖行也不会脱离手指。
 * 进入两行之间时相关行用动画平移、弹开让位；抬手时平滑吸附落位并
 * 更新顺序。
 *
 * 事件目标统一为行本身（手柄不单独消费触摸），自实现长按判定，
 * 关键索引均做边界收敛与异常防护，避免快速拖拽时崩溃。
 */
class AllMenuDragController(
    private val container: LinearLayout,
    private val scrollView: MaxHeightScrollView,
    private val order: MutableList<String>
) {
    private val density = container.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(container.context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val edgeZone = 48 * density
    private val maxEdgeVelocity = MAX_EDGE_VELOCITY_DP * density
    private val handler = Handler(Looper.getMainLooper())

    private var activeRow: View? = null
    private var downRawY = 0f
    private var lastRawY = 0f
    private var downScrollY = 0
    private var startIndex = -1
    private var targetIndex = -1
    private var dragging = false
    private var edgeVelocity = 0
    private var lastFrameNanos = 0L

    private val longPressRunnable = Runnable {
        activeRow?.let { startDrag(it) }
    }

    private val edgeScroller = object : Runnable {
        override fun run() {
            if (edgeVelocity == 0) return
            val now = System.nanoTime()
            val dtSeconds = (((now - lastFrameNanos) / NANOS_PER_MILLI)
                .toFloat().coerceAtMost(MAX_FRAME_MS) / MILLIS_PER_SECOND)
            lastFrameNanos = now
            // 按时间积分速度，每帧位移很小，实现匀速平滑滚动而非整格跳跃
            scrollView.scrollBy(0, (edgeVelocity * dtSeconds).toInt())
            // 滚动后被拖行需要重新钉住手指、重算让位目标（手指静止时同样生效）
            placeDraggedRow()
            scrollView.postOnAnimation(this)
        }
    }

    /** 重建每一行时调用：手柄不消费触摸，行统一处理事件 */
    fun bind(row: View) {
        row.findViewById<View>(R.id.all_row_handle)?.apply {
            isClickable = false
            isLongClickable = false
            isFocusable = false
        }
        row.isClickable = true
        row.setOnTouchListener { v, event -> handleTouch(v, event) }
    }

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeRow = v
                downRawY = event.rawY
                lastRawY = event.rawY
                downScrollY = scrollView.scrollY
                startIndex = container.indexOfChild(v)
                targetIndex = startIndex
                dragging = false
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeRow !== v) return false
                if (!dragging) {
                    // 长按成立前移动超过阈值则取消长按，按普通滑动处理
                    if (abs(event.rawY - downRawY) > touchSlop) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    return false
                }
                lastRawY = event.rawY
                placeDraggedRow()
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging && activeRow === v) {
                    finishDrag()
                    return true
                }
                activeRow = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) cancelDrag()
                activeRow = null
                return true
            }
        }
        return false
    }

    private fun startDrag(row: View) {
        if (dragging) return
        dragging = true
        scrollView.requestDisallowInterceptTouchEvent(true)
        row.translationZ = 8 * density
        row.alpha = 0.95f
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 把被拖行钉在手指当前位置，并据此重算让位目标与边缘滚动。
     * 同时被 ACTION_MOVE 与边缘滚动帧调用，保证任何情况下都跟手。
     */
    private fun placeDraggedRow() {
        val row = activeRow ?: return
        val dy = lastRawY - downRawY
        // 补偿边缘滚动：容器滚动 Δs 会让子行整体偏移，translationY 需反向加回
        row.translationY = dy + (scrollView.scrollY - downScrollY)
        val centerY = row.top + row.height / 2f + row.translationY
        val newTarget = computeTarget(row, centerY)
        if (newTarget != targetIndex) {
            targetIndex = newTarget
            applySpacing(row)
        }
        updateEdge(centerY)
    }

    /** 被拖行中心对应的完整序列目标索引 */
    private fun computeTarget(dragged: View, centerY: Float): Int {
        var pos = 0
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === dragged) continue
            if (centerY > child.top + child.height / 2f) pos++
        }
        return pos.coerceIn(0, (container.childCount - 1).coerceAtLeast(0))
    }

    /** 目标区间内的行平移、弹开让位 */
    private fun applySpacing(dragged: View) {
        val h = dragged.height
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === dragged) continue
            val offset = when {
                targetIndex > startIndex && i in (startIndex + 1)..targetIndex -> -h
                targetIndex < startIndex && i in targetIndex until startIndex -> h
                else -> 0
            }
            child.animate().translationY(offset.toFloat())
                .setDuration(SPACE_DUR)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun updateEdge(centerY: Float) {
        val viewY = centerY - scrollView.scrollY + container.top
        val velocity = when {
            viewY < edgeZone -> {
                val depth = ((edgeZone - viewY) / edgeZone).coerceIn(0f, 1f)
                -(maxEdgeVelocity * (MIN_SPEED_RATIO + (1f - MIN_SPEED_RATIO) * depth))
            }
            viewY > scrollView.height - edgeZone -> {
                val depth = ((viewY - (scrollView.height - edgeZone)) / edgeZone)
                    .coerceIn(0f, 1f)
                maxEdgeVelocity * (minSpeedAt(depth))
            }
            else -> 0f
        }.toInt()

        if (velocity == 0) {
            edgeVelocity = 0
        } else if (edgeVelocity == 0) {
            edgeVelocity = velocity
            lastFrameNanos = System.nanoTime()
            scrollView.postOnAnimation(edgeScroller)
        } else {
            edgeVelocity = velocity
        }
    }

    private fun minSpeedAt(depth: Float) =
        MIN_SPEED_RATIO + (1f - MIN_SPEED_RATIO) * depth

    private fun stopEdge() {
        edgeVelocity = 0
        scrollView.removeCallbacks(edgeScroller)
    }

    private fun finishDrag() {
        val row = activeRow ?: return
        stopEdge()
        try {
            if (targetIndex != startIndex && startIndex in 0 until container.childCount) {
                val id = row.tag as? String
                container.removeView(row)
                val insertAt = targetIndex.coerceIn(0, container.childCount)
                container.addView(row, insertAt)
                if (id != null) {
                    if (startIndex in order.indices) order.removeAt(startIndex)
                    order.add(insertAt.coerceAtMost(order.size), id)
                }
            }
        } catch (t: Throwable) {
            // 重排失败也必须继续复位视觉，避免界面卡死
        }
        settleAll(row)
        dragging = false
        activeRow = null
        startIndex = -1
        targetIndex = -1
    }

    private fun cancelDrag() {
        val row = activeRow
        stopEdge()
        for (i in 0 until container.childCount) {
            with(container.getChildAt(i)) {
                animate().cancel()
                translationY = 0f
            }
        }
        row?.apply {
            translationZ = 0f
            alpha = 1f
            translationY = 0f
        }
        dragging = false
    }

    private fun settleAll(row: View) {
        // 重排后被拖行已在目标布局位置，直接清零其位移，避免从补偿值飞回造成跳动
        row.translationY = 0f
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === row) continue
            child.animate()
                .translationY(0f)
                .setDuration(SETTLE_DUR)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        row.animate()
            .translationZ(0f)
            .alpha(1f)
            .setDuration(SETTLE_DUR)
            .start()
    }

    private companion object {
        const val SPACE_DUR = 150L
        const val SETTLE_DUR = 180L

        // 边缘自动滚动：最大速度（dp/s）、起步最小速度比例、时间积分常量
        const val MAX_EDGE_VELOCITY_DP = 420f
        const val MIN_SPEED_RATIO = 0.35f
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1000f
        const val MAX_FRAME_MS = 32f
    }
}
