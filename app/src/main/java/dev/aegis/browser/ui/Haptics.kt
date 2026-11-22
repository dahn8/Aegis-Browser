package dev.aegis.browser.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View

/**
 * 轻微振动：优先用系统振动器给一次短促 25ms 轻震，
 * 振动器不可用时退回 View 的长按触感反馈。
 */
object Haptics {

    fun tap(view: View) {
        // FLAG_IGNORE_GLOBAL_SETTING：即使系统“触摸振动”被关，也强行走触感通道
        view.performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING else 0
        )
        val context = view.context
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val ok = if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(45L)
                }
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (!ok && vibrator == null) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
