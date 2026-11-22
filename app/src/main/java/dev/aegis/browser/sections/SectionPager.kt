package dev.aegis.browser.sections

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * 三页横向容器：书签 / 历史 / 下载并排放在一条宽 3 屏的“纸带”上，
 * 拖动时整条纸带跟着手指走，所以相邻页是真的跟着一起滑，就像同一个页面。
 * 判定跟手：手指位移超过 12% 屏宽，或者轻轻一甩达到速度阈值，就翻页。
 */
class SectionPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    var currentIndex: Int = 0
        private set

    var onIndexChanged: ((Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragPx = 0f
    private var animator: ValueAnimator? = null
    private var dragStart = 0f
    private var tracker: VelocityTracker? = null

    val pageCount: Int get() = childCount

    /** 不做进场动画，直接停在第 index 页 */
    fun selectInitial(index: Int) {
        currentIndex = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        dragPx = 0f
        applyOffsets(0f)
        onIndexChanged?.invoke(currentIndex)
    }

    fun goTo(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        if (target == currentIndex) {
            settleBack()
            return
        }
        animator?.cancel()
        val width = width.toFloat()
        // 先切换当前页并立即回调，让顶栏图标与页面状态同步，不等动画结束
        val oldOffset = -currentIndex * width + dragPx
        currentIndex = target
        dragPx = 0f
        onIndexChanged?.invoke(target)
        if (!animate || width <= 0f) {
            applyOffsets(0f)
            return
        }
        val from = oldOffset + target * width
        val value = ValueAnimator.ofFloat(from, 0f)
        value.duration = 260
        value.interpolator = DecelerateInterpolator(1.4f)
        value.addUpdateListener {
            dragPx = it.animatedValue as Float
            applyOffsets(dragPx)
        }
        value.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                dragPx = 0f
                applyOffsets(0f)
            }
        })
        animator = value
        value.start()
    }

    private fun applyOffsets(drag: Float) {
        val width = width.toFloat()
        for (index in 0 until childCount) {
            val child = getChildAt(index) ?: continue
            child.translationX = -currentIndex * width + drag
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidth, childHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.layout(index * width, 0, (index + 1) * width, b - t)
            child.translationX = -currentIndex * width + dragPx
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 上一次翻页动画还没停就再滑：直接接管，连续滑动不卡顿
                animator?.cancel()
                animator = null
                downX = ev.x
                downY = ev.y
                dragStart = dragPx
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.25f) {
                        dragging = true
                        // 从当前位置起算，避免接管时跳一下
                        downX = ev.x
                        downY = ev.y
                        dragStart = dragPx
                        return true
                    }
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val width = width.toFloat()
                if (width <= 0f) return true
                var drag = dragStart + (ev.x - downX)
                // 到头了给一点阻尼，但不会卡死不动
                val atStart = currentIndex == 0 && drag > 0
                val atEnd = currentIndex == childCount - 1 && drag < 0
                if (atStart || atEnd) drag *= 0.35f
                dragPx = drag
                applyOffsets(drag)
            }
            MotionEvent.ACTION_UP -> {
                val velocity = tracker?.let {
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                finishDrag(ev.x - downX, velocity)
                tracker?.recycle()
                tracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                settleBack()
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    private fun finishDrag(dx: Float, vx: Float) {
        dragging = false
        val width = width.toFloat()
        if (width <= 0f) {
            settleBack()
            return
        }
        val atStart = currentIndex == 0 && dx > 0
        val atEnd = currentIndex == childCount - 1 && dx < 0
        // 到头时位移被阻尼过，速度也跟着折算，避免在边缘也能甩翻页
        val effectiveDx = if (atStart || atEnd) 0f else dx
        val effectiveVx = if (atStart || atEnd) 0f else vx

        val distanceThreshold = width * 0.12f
        val flingThreshold = width * 0.9f
        val tinyMove = width * 0.03f

        when {
            effectiveDx <= -distanceThreshold ||
                (effectiveVx <= -flingThreshold && effectiveDx < -tinyMove) ->
                goTo(currentIndex + 1)
            effectiveDx >= distanceThreshold ||
                (effectiveVx >= flingThreshold && effectiveDx > tinyMove) ->
                goTo(currentIndex - 1)
            else -> settleBack()
        }
    }

    private fun settleBack() {
        animator?.cancel()
        val from = dragPx
        if (from == 0f) {
            dragging = false
            applyOffsets(0f)
            return
        }
        val value = ValueAnimator.ofFloat(from, 0f)
        value.duration = 200
        value.interpolator = DecelerateInterpolator()
        value.addUpdateListener {
            dragPx = it.animatedValue as Float
            applyOffsets(dragPx)
        }
        value.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                dragging = false
            }
        })
        animator = value
        value.start()
    }
}
