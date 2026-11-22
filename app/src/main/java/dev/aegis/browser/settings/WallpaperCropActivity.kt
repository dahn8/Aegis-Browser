package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 自定义壁纸：全屏预览（画面直接铺满整屏，含状态栏与导航栏区域）。
 *
 * 预览容器与主页背景（home_bg_image，match_parent + centerCrop）尺寸一致，
 * 因此保存出来的图片在主页的显示区域与调整时看到的完全相同。
 * 交互：单指拖动、双指缩放（以双指中心为焦点），始终保持铺满不留边。
 */
class WallpaperCropActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var frame: FrameLayout
    private lateinit var image: ImageView
    private lateinit var bitmap: Bitmap

    private val matrix = Matrix()
    private var baseScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var initialized = false
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全屏预览：内容铺到状态栏/导航栏下面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_wallpaper_crop)

        frame = findViewById(R.id.crop_container)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(image)

        // 悬浮控件让开状态栏与导航栏
        findViewById<View>(R.id.crop_top_bar).setPadding(dp(4), statusBarHeight(), dp(16), 0)
        findViewById<View>(R.id.crop_bottom_bar).also { bar ->
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            bar.setPadding(dp(16), dp(10), dp(16), navBarHeight() + dp(10))
            bar.layoutParams = lp
        }

        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }
        val loaded = loadSampled(Uri.parse(uri))
        if (loaded == null) {
            android.widget.Toast.makeText(this, R.string.wallpaper_adjusted_failed, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        bitmap = loaded
        image.setImageBitmap(bitmap)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                clamp()
                return true
            }
        })

        image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    frame.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        matrix.postTranslate(event.x - lastX, event.y - lastY)
                        clamp()
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
            true
        }

        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (frame.width > 0 && frame.height > 0) {
                // 尺寸变化（如旋转）后重新铺底，保持图片铺满
                initMatrix()
            }
        }

        findViewById<View>(R.id.crop_back).setOnClickListener { finish() }
        findViewById<View>(R.id.crop_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.crop_confirm).setOnClickListener { save() }
    }

    private fun loadSampled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    private fun initMatrix() {
        if (::bitmap.isInitialized.not()) return
        val vw = frame.width.toFloat()
        val vh = frame.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        baseScale = max(vw / bw, vh / bh)
        matrix.reset()
        matrix.setScale(baseScale, baseScale)
        matrix.postTranslate((vw - bw * baseScale) / 2f, (vh - bh * baseScale) / 2f)
        image.imageMatrix = matrix
        initialized = true
    }

    private fun clamp() {
        val vw = frame.width.toFloat()
        val vh = frame.height.toFloat()
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(rect)

        // 缩得太小就拉回铺满
        if (rect.width() < vw || rect.height() < vh) {
            val fix = max(vw / rect.width(), vh / rect.height())
            matrix.postScale(fix, fix, vw / 2f, vh / 2f)
            rect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            matrix.mapRect(rect)
        }
        var dx = 0f
        var dy = 0f
        if (rect.left > 0f) dx = -rect.left
        if (rect.right < vw) dx = vw - rect.right
        if (rect.top > 0f) dy = -rect.top
        if (rect.bottom < vh) dy = vh - rect.bottom
        matrix.postTranslate(dx, dy)
        image.imageMatrix = matrix
    }

    private fun save() {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return
        try {
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawBitmap(bitmap, image.imageMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val file = File(filesDir, "home_bg_custom.jpg")
            FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            output.recycle()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_URI, Uri.fromFile(file).toString()))
            finish()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.wallpaper_adjusted_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun statusBarHeight(): Int = systemBarHeight("status_bar_height")

    private fun navBarHeight(): Int = systemBarHeight("navigation_bar_height")

    private fun systemBarHeight(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_RESULT_URI = "extra_result_uri"
    }
}
