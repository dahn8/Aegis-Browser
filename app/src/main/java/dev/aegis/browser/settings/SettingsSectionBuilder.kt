package dev.aegis.browser.settings

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.aegis.browser.R

/**
 * Google Material 3 风格设置分组构建器：
 * 浅灰/深黑页面底色 + 20dp 圆角独立卡片分组 + Google 蓝分组标题 + 可选图标与副标题摘要。
 */
class SettingsSectionBuilder(
    private val activity: Activity,
    private val container: LinearLayout,
    val dark: Boolean
) {

    val surfaceColor: Int = activity.getColor(if (dark) R.color.m3_surface_card_dark else R.color.m3_surface_card)
    val spacerColor: Int = activity.getColor(if (dark) R.color.bg_dark else R.color.bg_light)
    private val dividerColor: Int = activity.getColor(if (dark) R.color.m3_divider_dark else R.color.m3_divider)
    private val headerTextColor: Int = activity.getColor(if (dark) R.color.primary_dark else R.color.primary)
    private val iconTintColor: Int = if (dark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()
    private val valueColor: Int = activity.getColor(if (dark) R.color.m3_subtitle_dark else R.color.m3_subtitle)
    private val noteColor: Int = activity.getColor(if (dark) R.color.m3_subtitle_dark else R.color.m3_subtitle)
    private val rippleRes: Int = if (dark) R.drawable.bg_m3_ripple_dark else R.drawable.bg_m3_ripple
    private val cardBgRes: Int = if (dark) R.drawable.bg_m3_card_dark else R.drawable.bg_m3_card

    fun reset() {
        container.removeAllViews()
    }

    /** Material 3 圆角卡片分组：可选主色分组标题 + 20dp 圆角卡片容器 */
    fun section(header: String?, builder: (LinearLayout) -> Unit) {
        if (header != null) {
            container.addView(TextView(activity).apply {
                text = header
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(headerTextColor)
                setPadding(dp(24), dp(14), dp(24), dp(8))
            })
        } else if (container.childCount > 0) {
            container.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
                )
            })
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(cardBgRes)
            clipToOutline = true
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(4)
            }
        }
        builder(box)
        container.addView(box)
    }

    /** Material 3 设置行：可选左侧图标 + 标题 + 可选副标题 + 右侧值 + 箭头 */
    fun row(
        box: LinearLayout,
        title: String,
        value: String? = null,
        iconRes: Int = 0,
        subtitle: String? = null,
        onClick: (() -> Unit)? = null
    ): View {
        if (box.childCount > 0) divider(box)
        val row = activity.layoutInflater.inflate(R.layout.item_settings_row, box, false)
        row.setBackgroundResource(rippleRes)
        row.findViewById<TextView>(R.id.settings_row_title).text = title

        val iconView = row.findViewById<ImageView>(R.id.settings_row_icon)
        if (iconRes != 0) {
            iconView.setImageResource(iconRes)
            iconView.setColorFilter(iconTintColor)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }

        val subView = row.findViewById<TextView>(R.id.settings_row_subtitle)
        if (subtitle.isNullOrBlank()) {
            subView.visibility = View.GONE
        } else {
            subView.text = subtitle
            subView.visibility = View.VISIBLE
        }

        val valueView = row.findViewById<TextView>(R.id.settings_row_value)
        if (value.isNullOrBlank()) {
            valueView.visibility = View.GONE
        } else {
            valueView.text = value
            valueView.visibility = View.VISIBLE
        }

        row.findViewById<ImageView>(R.id.settings_row_arrow).visibility =
            if (onClick != null) View.VISIBLE else View.GONE
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        } else {
            row.isClickable = false
        }
        box.addView(row)
        return row
    }

    /** Material 3 开关行：支持左侧图标与副标题 */
    fun switchRow(
        box: LinearLayout,
        title: String,
        checked: Boolean,
        iconRes: Int = 0,
        subtitle: String? = null,
        onChange: (Boolean) -> Unit
    ): Switch {
        if (box.childCount > 0) divider(box)
        val row = activity.layoutInflater.inflate(R.layout.item_settings_switch, box, false)
        row.setBackgroundResource(rippleRes)
        row.findViewById<TextView>(R.id.settings_row_title).text = title

        val iconView = row.findViewById<ImageView>(R.id.settings_row_icon)
        if (iconRes != 0) {
            iconView.setImageResource(iconRes)
            iconView.setColorFilter(iconTintColor)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }

        val subView = row.findViewById<TextView>(R.id.settings_row_subtitle)
        if (subtitle.isNullOrBlank()) {
            subView.visibility = View.GONE
        } else {
            subView.text = subtitle
            subView.visibility = View.VISIBLE
        }

        val switch = row.findViewById<Switch>(R.id.settings_row_switch)
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.setOnClickListener { switch.toggle() }
        box.addView(row)
        return switch
    }

    /** 卡片内说明小字 */
    fun note(box: LinearLayout, text: String) {
        box.addView(TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(noteColor)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(18), 0, dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }

    /** 单选行：标题 + 可选右侧小字 + 选中勾 */
    fun checkRow(
        box: LinearLayout,
        title: String,
        selected: Boolean,
        trailingText: String? = null,
        onTrailing: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        if (box.childCount > 0) divider(box)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundResource(rippleRes)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!trailingText.isNullOrBlank()) {
            row.addView(TextView(activity).apply {
                text = trailingText
                textSize = 13f
                setTextColor(valueColor)
                setPadding(dp(8), 0, dp(12), 0)
                if (onTrailing != null) {
                    isClickable = true
                    setOnClickListener { onTrailing() }
                }
            })
        }
        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check_circle)
            setColorFilter(headerTextColor)
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        row.addView(check)
        box.addView(row)
        return row
    }

    /** 卡片内分隔线 */
    fun divider(box: LinearLayout) {
        box.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            }
            setBackgroundColor(dividerColor)
        })
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
