package dev.aegis.browser.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView

/**
 * 长按拖动排序（无 AndroidX）：长按生成浮动快照跟随手指，其余行按一格行高上下让位，
 * 松手时快照吸附到空出的槽位，再提交顺序。
 */
class DragSortHelper(
    private val activity: Activity,
    private val listView: ListView,
    private val onCommit: (from: Int, to: Int) -> Unit
) {

    private var dragView: ImageView? = null
    private var startIndex = -1
    private var hoverIndex = -1
    private var stepPx = 0
    private var pressRawY = 0f
    private var firstMoveY = -1f
    private var listTop = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var pendingPosition = -1
    var allowDragCheck: (Int) -> Boolean = { true }
    private val handler = Handler(Looper.getMainLooper())
    private val slop by lazy { ViewConfiguration.get(activity).scaledTouchSlop }

    private val longPressTask = Runnable {
        val p = pendingPosition
        pendingPosition = -1
        if (p >= 0 && p < listView.adapter.count && allowDragCheck(p)) {
            start(p, downRawY, downRawY)
        }
    }

    /**
     * 行内直接接管：行的触摸事件一定经过这里（行自身可点击，是 touch target），
     * 自己的 420ms 计时不受 ListView/系统 long click 影响。
     */
    fun onRowTouch(ev: MotionEvent, position: Int): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dragView == null) {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    pendingPosition = position
                    handler.removeCallbacks(longPressTask)
                    handler.postDelayed(longPressTask, 420)
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView != null) {
                    handleTouch(ev)
                    return true
                }
                if (pendingPosition >= 0 &&
                    (Math.abs(ev.rawX - downRawX) > slop || Math.abs(ev.rawY - downRawY) > slop)
                ) {
                    handler.removeCallbacks(longPressTask)
                    pendingPosition = -1
                }
                return false
            }
            else -> {
                handler.removeCallbacks(longPressTask)
                pendingPosition = -1
                if (dragView != null) {
                    handleTouch(ev)
                    return true
                }
                return false
            }
        }
    }

    /** 触摸点所在项（屏幕坐标），不在列表范围内返回 -1 */
    private fun positionAtRaw(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        listView.getLocationOnScreen(loc)
        val x = rawX - loc[0]
        val y = rawY - loc[1]
        if (x < 0f || y < 0f || x > listView.width || y > listView.height) return -1
        return listView.pointToPosition(x.toInt(), y.toInt())
    }

    /**
     * 在 Activity.dispatchTouchEvent 最外层调用：自己计时长按并接管后续事件。
     * 返回 true 表示这次事件已被拖动消费，不再向下分发。
     */
    fun onHostTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dragView == null) {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    pendingPosition = positionAtRaw(ev.rawX, ev.rawY)
                    handler.removeCallbacks(longPressTask)
                    if (pendingPosition >= 0) handler.postDelayed(longPressTask, 420)
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView != null) {
                    handleTouch(ev)
                    return true
                }
                if (pendingPosition >= 0 &&
                    (Math.abs(ev.rawX - downRawX) > slop || Math.abs(ev.rawY - downRawY) > slop)
                ) {
                    handler.removeCallbacks(longPressTask)
                    pendingPosition = -1
                }
                return false
            }
            else -> {
                handler.removeCallbacks(longPressTask)
                pendingPosition = -1
                if (dragView != null) {
                    handleTouch(ev)
                    return true
                }
                return false
            }
        }
    }

    /**
     * 在 ListView 自身接管长按：系统 item long click 在这台机器上不可靠，
     * 这里自己计时 420ms，位移超过 touchSlop 就取消；长按成立后事件流由本类接管。
     * allowCheck 返回 false 表示这一项不拖动（批量模式等），由外部处理。
     */
    fun enableLongPressDrag(check: (Int) -> Boolean = { true }) {
        allowDragCheck = check
        listView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    if (dragView == null) {
                        pendingPosition = listView.pointToPosition(ev.x.toInt(), ev.y.toInt())
                        handler.removeCallbacks(longPressTask)
                        if (pendingPosition >= 0) handler.postDelayed(longPressTask, 420)
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragView != null) {
                        handleTouch(ev)
                        true
                    } else {
                        if (pendingPosition >= 0 &&
                            (Math.abs(ev.rawX - downRawX) > slop || Math.abs(ev.rawY - downRawY) > slop)
                        ) {
                            handler.removeCallbacks(longPressTask)
                            pendingPosition = -1
                        }
                        false
                    }
                }
                else -> {
                    handler.removeCallbacks(longPressTask)
                    pendingPosition = -1
                    if (dragView != null) {
                        handleTouch(ev)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private val host: ViewGroup?
        get() = (listView.parent as? ViewGroup)
            ?: (activity.findViewById<ViewGroup>(android.R.id.content))

    val isActive: Boolean
        get() = dragView != null

    fun start(position: Int, pressY: Float = 0f, touchRawY: Float = -1f): Boolean {
        val parent = host ?: return false
        val first = listView.firstVisiblePosition
        var child = listView.getChildAt(position - first)
        var index = position - first
        if (child == null && touchRawY >= 0f) {
            val y = (touchRawY - windowListTop()).toInt()
            for (i in 0 until listView.childCount) {
                val c = listView.getChildAt(i)
                if (y >= c.top && y <= c.bottom) {
                    child = c
                    index = i
                    break
                }
            }
        }
        if (child == null || child.width <= 0 || child.height <= 0) return false
        pressRawY = pressY
        firstMoveY = -1f

        stepPx = child.height + listView.dividerHeight
        val bitmap = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
        child.draw(Canvas(bitmap))

        val location = IntArray(2)
        child.getLocationInWindow(location)
        val parentLocation = IntArray(2)
        parent.getLocationInWindow(parentLocation)
        listTop = (location[1] - parentLocation[1]).toFloat()

        val drag = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0.96f
            elevation = 16f * activity.resources.displayMetrics.density
            x = (location[0] - parentLocation[0]).toFloat()
            y = listTop
        }
        parent.addView(drag, FrameLayout.LayoutParams(child.width, child.height))

        child.visibility = View.INVISIBLE
        startIndex = first + index
        hoverIndex = position
        dragView = drag

        return true
    }

    /** 由 Activity.dispatchTouchEvent 转发：长按后事件流仍属于原行，必须在这里接管 */
    fun handleTouch(event: MotionEvent): Boolean {
        val drag = dragView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (firstMoveY < 0f) firstMoveY = event.rawY
                drag.translationY = event.rawY - firstMoveY
                val target = listView.pointToPosition(
                    event.rawX.toInt(),
                    (event.rawY - windowListTop()).toInt()
                )
                if (target >= 0 && target != hoverIndex) {
                    hoverIndex = target
                    reflow(target)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
        return true
    }

    private fun windowListTop(): Float {
        val location = IntArray(2)
        listView.getLocationOnScreen(location)
        return location[1].toFloat()
    }

    /** 让位：起始位置与目标位置之间的行整体平移一格，把目标槽位空出来 */
    private fun reflow(target: Int) {
        val first = listView.firstVisiblePosition
        val low = minOf(startIndex, target)
        val high = maxOf(startIndex, target)
        val direction = if (target > startIndex) -1f else 1f
        for (i in 0 until listView.childCount) {
            val position = first + i
            val child = listView.getChildAt(i) ?: continue
            if (position == startIndex) continue
            val offset = if (position in low..high) direction * stepPx else 0f
            child.animate().translationY(offset).setDuration(150).start()
        }
    }

    private fun finish() {
        val drag = dragView ?: return
        val from = startIndex
        val to = hoverIndex
        dragView = null
        startIndex = -1
        hoverIndex = -1

        val parent = host
        val first = listView.firstVisiblePosition
        val targetChild = if (to >= 0) listView.getChildAt(to - first) else null
        val endY = if (targetChild != null) {
            val location = IntArray(2)
            targetChild.getLocationInWindow(location)
            val parentLocation = IntArray(2)
            parent?.getLocationInWindow(parentLocation)
            (location[1] - (parentLocation?.get(1) ?: 0)).toFloat()
        } else {
            drag.y + drag.translationY
        }

        drag.animate()
            .y(endY)
            .setDuration(170)
            .withEndAction {
                parent?.removeView(drag)
                resetRows()
                if (from >= 0 && to >= 0 && from != to) onCommit(from, to)
            }
            .start()
    }

    private fun resetRows() {
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            child.animate().cancel()
            child.translationY = 0f
            child.visibility = View.VISIBLE
        }
    }
}
