package dev.aegis.browser.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.aegis.browser.R

/**
 * 全应用统一弹窗：白/深色圆角卡片、克制字号、右对齐文字按钮、可选单选列表。
 * 直接替换系统 AlertDialog，避免各机型上直角白块与 22sp 超大标题。
 */
class AegisDialog {


    class Builder(private val context: Context) : DialogInterface {
        @Suppress("UNUSED_PARAMETER")
        constructor(context: Context, themeResId: Int) : this(context)


        private var titleText: CharSequence? = null
        private var messageText: CharSequence? = null
        private var items: Array<out CharSequence>? = null
        private var singleChoice = false
        private var checkedIndex = -1
        private var itemListener: DialogInterface.OnClickListener? = null

        private var positiveText: CharSequence? = null
        private var negativeText: CharSequence? = null
        private var neutralText: CharSequence? = null
        private var positiveListener: DialogInterface.OnClickListener? = null
        private var negativeListener: DialogInterface.OnClickListener? = null
        private var neutralListener: DialogInterface.OnClickListener? = null

        private var customView: View? = null
        private var isCancelable = true
        private var cancelListener: DialogInterface.OnCancelListener? = null
        private var dismissListener: DialogInterface.OnDismissListener? = null

        private var dialog: Dialog? = null

        fun setTitle(title: CharSequence?): Builder {
            titleText = title
            return this
        }

        fun setTitle(title: Int): Builder {
            titleText = context.getString(title)
            return this
        }

        fun setMessage(message: CharSequence?): Builder {
            messageText = message
            return this
        }

        fun setMessage(message: Int): Builder {
            messageText = context.getString(message)
            return this
        }

        fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): Builder {
            this.items = items
            this.singleChoice = false
            this.itemListener = listener
            return this
        }

        fun setItems(itemsRes: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.items = context.resources.getTextArray(itemsRes)
            this.singleChoice = false
            this.itemListener = listener
            return this
        }

        fun setSingleChoiceItems(items: Array<out CharSequence>?, checked: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.items = items
            this.singleChoice = true
            this.checkedIndex = checked
            this.itemListener = listener
            return this
        }

        fun setSingleChoiceItems(itemsRes: Int, checked: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.items = context.resources.getTextArray(itemsRes)
            this.singleChoice = true
            this.checkedIndex = checked
            this.itemListener = listener
            return this
        }

        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            positiveText = text
            positiveListener = listener
            return this
        }

        fun setPositiveButton(textRes: Int, listener: DialogInterface.OnClickListener?): Builder {
            positiveText = context.getString(textRes)
            positiveListener = listener
            return this
        }

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            negativeText = text
            negativeListener = listener
            return this
        }

        fun setNegativeButton(textRes: Int, listener: DialogInterface.OnClickListener?): Builder {
            negativeText = context.getString(textRes)
            negativeListener = listener
            return this
        }

        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            neutralText = text
            neutralListener = listener
            return this
        }

        fun setNeutralButton(textRes: Int, listener: DialogInterface.OnClickListener?): Builder {
            neutralText = context.getString(textRes)
            neutralListener = listener
            return this
        }

        fun setView(view: View?): Builder {
            customView = view
            return this
        }

        fun setCancelable(flag: Boolean): Builder {
            isCancelable = flag
            return this
        }

        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder {
            cancelListener = listener
            return this
        }

        fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder {
            dismissListener = listener
            return this
        }

        fun show(): Dialog {
            val d = create()
            d.show()
            return d
        }

        fun create(): Dialog {
            val dark = isDarkContext(context)
            val d = Dialog(context, if (dark) R.style.Theme_Aegis_Dialog_Card_Dark else R.style.Theme_Aegis_Dialog_Card)
            dialog = d

            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val surface = if (dark) 0xFF23262B.toInt() else 0xFFFFFFFF.toInt()
            val titleColor = if (dark) 0xFFF2F4F7.toInt() else 0xFF16181C.toInt()
            val bodyColor = if (dark) 0xFFAEB5BF.toInt() else 0xFF5A616B.toInt()
            val dividerColor = if (dark) 0xFF33373D.toInt() else 0xFFEDEFF3.toInt()
            val accent = context.getColor(R.color.primary)
            val rowPressed = if (dark) 0x14FFFFFF else 0x0F000000

            val card = LinearLayout(context)
            card.orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(24).toFloat()
            bg.setColor(surface)
            card.background = bg
            card.setPadding(dp(22), dp(22), dp(22), dp(10))

            if (!TextUtils.isEmpty(titleText)) {
                val title = TextView(context)
                title.text = titleText
                title.setTextSize(16f)
                title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                title.setTextColor(titleColor)
                title.maxLines = 3
                title.ellipsize = TextUtils.TruncateAt.END
                title.includeFontPadding = false
                card.addView(title, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            if (!TextUtils.isEmpty(messageText)) {
                val body = TextView(context)
                body.text = messageText
                body.setTextSize(13f)
                body.setTextColor(bodyColor)
                body.setLineSpacing(dp(4).toFloat(), 1.0f)
                body.includeFontPadding = false
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(if (TextUtils.isEmpty(titleText)) 0 else 10)
                card.addView(body, lp)
            }

            val list = items
            if (list != null && list.isNotEmpty()) {
                val column = LinearLayout(context)
                column.orientation = LinearLayout.VERTICAL
                for (i in list.indices) {
                    column.addView(buildItem(context, list[i], i, dark, accent, rowPressed))
                }
                val scroll = MaxHeightScrollView(context).apply {
                    maxHeight = (context.resources.displayMetrics.heightPixels * 0.52f).toInt()
                }
                scroll.isFillViewport = true
                scroll.overScrollMode = View.OVER_SCROLL_NEVER
                scroll.addView(column, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(if (TextUtils.isEmpty(titleText) && TextUtils.isEmpty(messageText)) 2 else 12)
                card.addView(scroll, lp)
            }

            val extra = customView
            if (extra != null) {
                if (extra.parent != null) {
                    (extra.parent as? ViewGroup)?.removeView(extra)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(if (TextUtils.isEmpty(titleText)) 0 else 12)
                card.addView(extra, lp)
            }

            val hasButton = positiveText != null || negativeText != null || neutralText != null
            if (hasButton) {
                val divider = View(context)
                divider.setBackgroundColor(dividerColor)
                val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                dlp.topMargin = dp(if (items != null || customView != null || messageText != null) 12 else 6)
                card.addView(divider, dlp)

                val bar = LinearLayout(context)
                bar.orientation = LinearLayout.HORIZONTAL
                bar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                bar.setPadding(0, dp(4), 0, 0)

                fun addButton(text: CharSequence?, color: Int, which: Int, listener: DialogInterface.OnClickListener?) {
                    if (text == null) return
                    val button = TextView(context)
                    button.text = text
                    button.setTextSize(13f)
                    button.setTextColor(color)
                    button.gravity = Gravity.CENTER
                    button.includeFontPadding = false
                    button.setPadding(dp(14), dp(10), dp(14), dp(10))
                    val ripple = GradientDrawable()
                    ripple.shape = GradientDrawable.RECTANGLE
                    ripple.cornerRadius = dp(10).toFloat()
                    ripple.setColor(Color.TRANSPARENT)
                    button.background = RippleDrawable(
                        android.content.res.ColorStateList.valueOf(rowPressed), ripple, null)
                    button.isClickable = true
                    button.setOnClickListener {
                        // 与系统一致：点完按钮先关闭，再回调
                        dialog?.dismiss()
                        listener?.onClick(dialog, which)
                    }
                    bar.addView(button)
                }

                addButton(neutralText, bodyColor, DialogInterface.BUTTON_NEUTRAL, neutralListener)
                addButton(positiveText, accent, DialogInterface.BUTTON_POSITIVE, positiveListener)
                addButton(negativeText, bodyColor, DialogInterface.BUTTON_NEGATIVE, negativeListener)
                card.addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                // 只用于撑开右侧留白
                bar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }

            d.setContentView(card)
            d.setCancelable(isCancelable)
            d.setCanceledOnTouchOutside(isCancelable)
            if (cancelListener != null) d.setOnCancelListener(cancelListener)
            if (dismissListener != null) d.setOnDismissListener(dismissListener)

            val width = WindowManager.LayoutParams.WRAP_CONTENT
            d.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val metrics = context.resources.displayMetrics
                val maxWidth = dp(390)
                val target = if (metrics.widthPixels - dp(56) < maxWidth) metrics.widthPixels - dp(56) else maxWidth
                window.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT)
                window.setDimAmount(0.32f)
            }
            return d
        }

        private fun buildItem(
            context: Context,
            text: CharSequence,
            index: Int,
            dark: Boolean,
            accent: Int,
            rowPressed: Int
        ): View {
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(2), dp(11), dp(2), dp(11))
            row.isClickable = true

            val label = TextView(context)
            label.text = text
            label.setTextSize(14f)
            label.setTextColor(if (dark) 0xFFF2F4F7.toInt() else 0xFF22262C.toInt())
            label.includeFontPadding = false
            label.isSingleLine = false
            label.maxLines = 2
            row.addView(label, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (singleChoice) {
                row.addView(RadioDot(context, index == checkedIndex, accent, dark),
                    LinearLayout.LayoutParams(dp(20), dp(20)).also { it.leftMargin = dp(10) })
            }

            val ripple = GradientDrawable()
            ripple.shape = GradientDrawable.RECTANGLE
            ripple.cornerRadius = dp(12).toFloat()
            ripple.setColor(Color.TRANSPARENT)
            row.background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(rowPressed), ripple, null)
            row.setOnClickListener {
                itemListener?.onClick(dialog, index)
            }
            return row
        }

        private var canceled = false

        override fun cancel() {
            dialog?.cancel()
        }

        override fun dismiss() {
            dialog?.dismiss()
        }
    }
}

/** 单选项的圆环与内点，18dp，避免用字符导致基线偏移 */
private class RadioDot(
    context: Context,
    private val active: Boolean,
    private val accent: Int,
    private val dark: Boolean
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(9).toFloat()
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = if (active) accent else if (dark) 0xFF6B727C.toInt() else 0xFF9AA1AC.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        if (active) {
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = accent
            canvas.drawCircle(cx, cy, r - dp(3).toFloat(), paint)
        }
    }
}

/** 深色判断跟随应用主题（手动选深色时系统 uiMode 仍是浅色） */
private fun isDarkContext(context: Context): Boolean {
    val mode = ThemeSettings(context).themeMode
    return when (mode) {
        ThemeManager.MODE_DARK -> true
        ThemeManager.MODE_LIGHT -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
