package dev.aegis.browser.downloads

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.aegis.browser.R

/**
 * 下载项左侧的圆形进度环：
 * 外圈为静态轨道，内圈为进度弧；中心图标随状态显示暂停 / 继续 / 对勾 / 感叹号。
 */
class DownloadRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { DOWNLOADING, PAUSED, COMPLETED, FAILED }

    private val accent = context.getColor(R.color.primary)
    private val trackColor = if (isDark()) Color.parseColor("#3A3D42") else Color.parseColor("#E6E8EB")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = trackColor
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val oval = RectF()
    private val path = Path()

    /** 0-100；-1 表示总大小未知 */
    var progress: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var state: State = State.DOWNLOADING
        set(value) {
            field = value
            invalidate()
        }

    private fun isDark(): Boolean {
        val nightMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(2.5f)
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = dp(3f)

        val size = minOf(width, height).toFloat()
        val inset = stroke / 2f + dp(1f)
        oval.set(
            (width - size) / 2f + inset,
            (height - size) / 2f + inset,
            (width + size) / 2f - inset,
            (height + size) / 2f - inset
        )
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        val fraction = when {
            state == State.COMPLETED -> 1f
            progress < 0 -> 0.25f
            else -> progress / 100f
        }
        canvas.drawArc(oval, -90f, 360f * fraction, false, arcPaint)

        drawCenter(canvas)
    }

    private fun drawCenter(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (state) {
            State.PAUSED -> {
                // 播放三角：点击即继续
                path.reset()
                path.moveTo(cx - dp(5f), cy - dp(6.5f))
                path.lineTo(cx + dp(6.5f), cy)
                path.lineTo(cx - dp(5f), cy + dp(6.5f))
                path.close()
                canvas.drawPath(path, iconPaint)
            }
            State.DOWNLOADING -> {
                // 两条竖条 = 可暂停；与参考图的暂停键一致
                val barW = dp(2.5f)
                val barH = dp(12f)
                val gap = dp(2.5f)
                val top = cy - barH / 2f
                canvas.drawRoundRect(
                    cx - gap - barW, top, cx - gap, top + barH, dp(1f), dp(1f), iconPaint
                )
                canvas.drawRoundRect(
                    cx + gap, top, cx + gap + barW, top + barH, dp(1f), dp(1f), iconPaint
                )
            }
            State.COMPLETED -> {
                // 对勾
                val checkPath = Path().apply {
                    moveTo(cx - dp(6f), cy)
                    lineTo(cx - dp(1.5f), cy + dp(5f))
                    lineTo(cx + dp(6.5f), cy - dp(5f))
                }
                canvas.drawPath(checkPath, iconPaint.apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) })
                iconPaint.style = Paint.Style.FILL
            }
            State.FAILED -> Unit
        }
        if (state == State.FAILED) {
            // 感叹号
            canvas.drawRoundRect(
                cx - dp(1.6f), cy - dp(7f), cx + dp(1.6f), cy + dp(2.5f),
                dp(1.6f), dp(1.6f), iconPaint
            )
            canvas.drawCircle(cx, cy + dp(6f), dp(1.8f), iconPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
