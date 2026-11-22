package dev.aegis.browser.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.aegis.browser.R

/**
 * 以某个锚点（通常是右上角三点）为基准展开的圆角矩形菜单。
 * 卡片右边缘与锚点右边缘对齐，从锚点处缩放淡入。
 */
object AnchoredMenu {

    private const val MENU_WIDTH_DP = 196
    private const val ROW_HEIGHT_DP = 46

    fun show(
        activity: Activity,
        anchor: View,
        items: List<String>,
        dark: Boolean,
        checkedIndex: Int = -1,
        onPick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val menuWidth = dp(MENU_WIDTH_DP)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(
                if (dark) R.drawable.dialog_card_bg_dark else R.drawable.dialog_card_bg
            )
            setPadding(0, dp(6), 0, dp(6))
        }

        val popup = PopupWindow(
            content,
            menuWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }

        items.forEachIndexed { index, label ->
            if (index > 0) {
                content.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = dp(16) }
                    setBackgroundColor(if (dark) 0x1FFFFFFF else 0x14000000)
                })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
                )
                isClickable = true
            }
            val labelView = TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            }
            row.addView(labelView, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index == checkedIndex) {
                val check = android.widget.ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check_accent)
                    setColorFilter(if (dark) 0xFF8AB4FF.toInt() else 0xFF3B5BDB.toInt())
                }
                row.addView(check, LinearLayout.LayoutParams(dp(16), dp(16)))
            }
            row.setOnClickListener {
                popup.dismiss()
                onPick(index)
            }
            content.addView(row)
        }

        // 右边缘对齐锚点，向下留 4dp
        val xoff = anchor.width - menuWidth
        popup.showAsDropDown(anchor, xoff, dp(4))

        content.post {
            content.pivotX = content.width.toFloat()
            content.pivotY = 0f
            content.alpha = 0f
            content.scaleX = 0.86f
            content.scaleY = 0.86f
            content.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
}
