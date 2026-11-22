package dev.aegis.browser.home

import dev.aegis.browser.ui.AegisDialog
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.ui.MaxHeightScrollView
import dev.aegis.browser.ui.SoftThemes
import dev.aegis.browser.ui.SoftThemeStore
import java.util.Calendar
import java.util.Locale

/**
 * 主页天气弹窗：实况 + 今日逐 3 小时 + 未来三天 + 日出日落，右上角可改城市。
 * 卡片跟随浅色/深色，点外部或返回键收起；数据走 WeatherStore 的 30 分钟缓存。
 */
object WeatherPopup {

    private var popup: PopupWindow? = null
    private var host: FrameLayout? = null
    private var session: Session? = null

    private class Session(
        val activity: Activity,
        val dark: Boolean,
        val onChanged: Runnable
    ) {
        val store = WeatherStore(activity)
        var failed = false
        var binder: (() -> Unit)? = null
    }

    fun show(activity: Activity, anchor: View, dark: Boolean, onChanged: Runnable) {
        dismiss()
        val current = Session(activity, dark, onChanged)
        session = current
        val container = FrameLayout(activity)
        host = container
        val reload = {
            container.removeAllViews()
            container.addView(buildCard(current))
        }
        current.binder = reload
        reload()

        val width = (activity.resources.displayMetrics.widthPixels - dp(activity, 28))
        val window = PopupWindow(container, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(activity, 10).toFloat()
            setAnimationStyle(R.style.WeatherPopupAnimation)
        }
        val anchorRect = IntArray(2)
        anchor.getLocationInWindow(anchorRect)
        val gap = dp(activity, 4)
        val available = activity.resources.displayMetrics.heightPixels -
            anchorRect[1] - anchor.height - dp(activity, 24)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST)
        )
        window.showAsDropDown(anchor, dp(activity, 8), gap, Gravity.START)
        popup = window

        if (current.store.needsRefresh()) {
            WeatherFetcher.refresh(activity, false, {
                current.failed = !current.store.hasData()
                current.binder?.invoke()
                current.onChanged.run()
            })
        }
    }

    fun dismiss() {
        popup?.let { if (it.isShowing) it.dismiss() }
        popup = null
        host = null
        session = null
    }

    private fun buildCard(current: Session): View {
        val activity = current.activity
        val dark = current.dark
        val store = current.store
        val weather = store.data()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (dark) R.drawable.bg_weather_card_dark else R.drawable.bg_weather_card)
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
        }

        // 标题行：城市 + 更换城市
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = weather?.city?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.weather_auto_city)
            textSize = 16f
            setTextColor(primary(dark))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(activity).apply {
            text = activity.getString(R.string.weather_change_city)
            textSize = 13f
            setTextColor(secondary(dark))
            setPadding(dp(activity, 8), dp(activity, 4), 0, dp(activity, 4))
            isClickable = true
            setOnClickListener { askCity(current) }
        })
        card.addView(header)

        if (weather == null) {
            card.addView(TextView(activity).apply {
                text = activity.getString(
                    if (current.failed) R.string.weather_load_failed else R.string.weather_loading
                )
                textSize = 14f
                setTextColor(secondary(dark))
                setPadding(0, dp(activity, 18), 0, dp(activity, 18))
            })
            return card
        }

        // 实况：大温度 + 天气
        val currentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 6), 0, 0)
        }
        currentRow.addView(TextView(activity).apply {
            text = "${weather.tempC}°"
            textSize = 34f
            setTextColor(primary(dark))
            includeFontPadding = false
        })
        currentRow.addView(TextView(activity).apply {
            text = WeatherLabels.label(activity, weather.code)
            textSize = 15f
            setTextColor(secondary(dark))
            setPadding(dp(activity, 10), 0, 0, 0)
        })
        card.addView(currentRow)

        // 四项实况
        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        details.addView(metric(activity, dark, activity.getString(R.string.weather_feels), "${weather.feelsLike}°"))
        details.addView(metric(activity, dark, activity.getString(R.string.weather_humidity), "${weather.humidity}%"))
        details.addView(
            metric(
                activity, dark,
                activity.getString(R.string.weather_wind),
                WeatherLabels.wind(activity, weather.windDegree),
                "${weather.windSpeed} km/h"
            )
        )
        details.addView(
            metric(
                activity, dark,
                activity.getString(R.string.weather_uv),
                weather.uvIndex.ifBlank { "-" }
            )
        )
        card.addView(details, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(activity, 10) })

        card.addView(divider(activity, dark))

        // 未来三天
        weather.days.take(3).forEachIndexed { index, day ->
            card.addView(dayRow(activity, dark, index, day))
        }

        card.addView(divider(activity, dark))

        // 今日逐 3 小时
        card.addView(hoursStrip(activity, dark, weather.hours))

        card.addView(divider(activity, dark))

        // 日出日落
        card.addView(TextView(activity).apply {
            val text = StringBuilder()
            if (weather.sunrise.isNotBlank()) {
                text.append(activity.getString(R.string.weather_sunrise)).append(" ").append(weather.sunrise)
            }
            if (weather.sunset.isNotBlank()) {
                if (text.isNotEmpty()) text.append("   ·   ")
                text.append(activity.getString(R.string.weather_sunset)).append(" ").append(weather.sunset)
            }
            this.text = text
            textSize = 11f
            setTextColor(secondary(dark))
        })

        // 长内容封顶高度，内部滚动
        val scroll = MaxHeightScrollView(activity).apply {
            maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            isVerticalScrollBarEnabled = false
        }
        scroll.addView(card)
        return scroll
    }

    private fun metric(context: Context, dark: Boolean, label: String, value: String, sub: String? = null): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(secondary(dark))
        })
        cell.addView(TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(primary(dark))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(context, 3), 0, 0)
        })
        if (sub != null) {
            cell.addView(TextView(context).apply {
                text = sub
                textSize = 10f
                setTextColor(secondary(dark))
                setPadding(0, dp(context, 1), 0, 0)
            })
        }
        return cell
    }

    private fun dayRow(activity: Activity, dark: Boolean, index: Int, day: DaySlot): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 7), 0, dp(activity, 7))
        }
        row.addView(TextView(activity).apply {
            text = dayLabel(activity, index, day.date)
            textSize = 13f
            setTextColor(primary(dark))
            layoutParams = LinearLayout.LayoutParams(dp(activity, 96), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(activity).apply {
            text = WeatherLabels.label(activity, day.code)
            textSize = 12f
            setTextColor(secondary(dark))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(activity).apply {
            text = "${day.maxC}° / ${day.minC}°"
            textSize = 13f
            setTextColor(primary(dark))
        })
        return row
    }

    private fun dayLabel(activity: Activity, index: Int, date: String): String {
        val prefix = when (index) {
            0 -> activity.getString(R.string.weather_today)
            1 -> activity.getString(R.string.weather_tomorrow)
            else -> activity.getString(R.string.weather_day_after)
        }
        val short = date.split("-").takeLast(2).joinToString("/") { it.trimStart('0') }
        return if (short.isBlank()) prefix else "$prefix  $short"
    }

    private fun hoursStrip(activity: Activity, dark: Boolean, hours: List<HourSlot>): View {
        if (hours.isEmpty()) return View(activity)
        val strip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        hours.forEach { slot ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                layoutParams = LinearLayout.LayoutParams(dp(activity, 52), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            cell.addView(TextView(activity).apply {
                text = slot.time
                textSize = 11f
                setTextColor(secondary(dark))
            })
            cell.addView(TextView(activity).apply {
                text = "${slot.tempC}°"
                textSize = 14f
                setTextColor(primary(dark))
                setPadding(0, dp(activity, 3), 0, 0)
            })
            cell.addView(TextView(activity).apply {
                text = WeatherLabels.label(activity, slot.code)
                textSize = 10f
                setTextColor(secondary(dark))
                setPadding(0, dp(activity, 1), 0, 0)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            strip.addView(cell)
        }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return scroller
    }

    private fun divider(context: Context, dark: Boolean): View = View(context).apply {
        setBackgroundColor(if (dark) 0xFF32373E.toInt() else 0xFFEDEFF2.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
        ).also { it.topMargin = dp(context, 10); it.bottomMargin = dp(context, 2) }
    }

    private fun askCity(current: Session) {
        val activity = current.activity
        val input = EditText(activity).apply {
            setText(current.store.city)
            hint = activity.getString(R.string.weather_auto_city)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
        }
        val wrapper = FrameLayout(activity).apply {
            setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AegisDialog.Builder(activity)
            .setTitle(R.string.weather_city)
            .setView(wrapper)
            .setPositiveButton(R.string.done) { _, _ ->
                val name = input.text.toString().trim()
                current.store.city = name
                current.failed = false
                current.binder?.invoke()
                WeatherFetcher.refresh(activity, true, {
                    current.failed = !current.store.hasData()
                    current.binder?.invoke()
                    current.onChanged.run()
                })
            }
            .setNeutralButton(R.string.weather_use_auto) { _, _ ->
                current.store.city = ""
                current.failed = false
                current.binder?.invoke()
                WeatherFetcher.refresh(activity, true, {
                    current.failed = !current.store.hasData()
                    current.binder?.invoke()
                    current.onChanged.run()
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun primary(dark: Boolean): Int = if (dark) 0xFFF2F4F7.toInt() else 0xFF16181C.toInt()

    private fun secondary(dark: Boolean): Int = if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
