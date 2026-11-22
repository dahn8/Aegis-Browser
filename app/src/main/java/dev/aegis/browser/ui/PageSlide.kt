package dev.aegis.browser.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 书签 / 历史 / 下载之间切换时的横滑动画：
 * 顶部工具栏（含那条分隔横线）保持不动，只有下面的内容整体从一侧滑进来。
 * direction > 0 表示往右边的页走（内容从右侧滑入），< 0 反之，0 表示不动画。
 */
object PageSlide {

    private const val DURATION = 260L
    /** 起始位移占屏宽的比例，滑 35% 比整屏更像“内容在动、框架不动” */
    private const val OFFSET_RATIO = 0.35f

    fun play(activity: Activity, direction: Int) {
        if (direction == 0) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? ViewGroup ?: return
        if (root.childCount <= 1) return
        val distance = activity.resources.displayMetrics.widthPixels * OFFSET_RATIO
        val start = if (direction > 0) distance else -distance
        // 第 0 个子 View 是顶部工具栏，不参与动画；其余内容先挪到起始位，避免第一帧闪在终点
        for (index in 1 until root.childCount) {
            val child: View = root.getChildAt(index) ?: continue
            child.translationX = start
            child.alpha = 0.92f
        }
        root.post {
            for (index in 1 until root.childCount) {
                val child: View = root.getChildAt(index) ?: continue
                child.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(DURATION)
                    .setInterpolator(DecelerateInterpolator(1.4f))
                    .start()
            }
        }
    }
}
