package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.R
import dev.aegis.browser.home.HomeBackgroundStore
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 壁纸中心：预览当前壁纸、内置壁纸、从相册选择（可双指缩放调整）、恢复默认。
 * 与主页设置里的“壁纸”读写同一份 HomeBackgroundStore。
 */
class WallpaperActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private lateinit var backgroundStore: HomeBackgroundStore
    private var dark = false
    private var surfaceColor = Color.WHITE
    private var spacerColor = 0xFFF2F3F5.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        super.onCreate(savedInstanceState)
        ScreenOrientation.apply(this)
        setContentView(R.layout.activity_wallpaper)
        ThemeManager.applyStatusBarAppearance(this, dark)
        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.wallpaper)

        backgroundStore = HomeBackgroundStore(this)
        if (dark) {
            surfaceColor = 0xFF1B1E23.toInt()
            spacerColor = 0xFF0E1013.toInt()
        }
        findViewById<View>(R.id.wallpaper_scroll).setBackgroundColor(spacerColor)

        buildUi()
    }

    private fun buildUi() {
        val container = findViewById<LinearLayout>(R.id.wallpaper_container)
        container.removeAllViews()

        // 内置壁纸
        container.addView(sectionHeader(getString(R.string.wallpaper_builtin)))
        container.addView(builtinGrid())

        // 从相册选择 + 恢复默认
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(16), dp(10), dp(16), dp(16))
            addView(TextView(this@WallpaperActivity).apply {
                text = getString(R.string.choose_from_gallery)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(14), 0, dp(14))
                background = GradientDrawable().apply {
                    setColor(0xFF3B5BDB.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                isClickable = true
                setOnClickListener { pickImage() }
            })
            addView(TextView(this@WallpaperActivity).apply {
                text = getString(R.string.restore_default_wallpaper)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(0xFFE03131.toInt())
                setPadding(0, dp(14), 0, dp(6))
                isClickable = true
                setOnClickListener { restoreDefault() }
            })
        })

    }

    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        textSize = 13f
        setTextColor(if (dark) 0xFF8A8F98.toInt() else 0xFF9AA0A8.toInt())
        setPadding(dp(18), dp(14), dp(18), dp(8))
        setBackgroundColor(spacerColor)
    }

    private fun builtinGrid(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val width = resources.displayMetrics.widthPixels
        val cellW = (width - dp(24) - dp(4 * 8)) / 4
        val cellH = (cellW * 16) / 9
        val ids = BUILTIN_WALLS
        var cursor = 0
        while (cursor < ids.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { if (cursor > 0) it.topMargin = dp(8) }
            }
            val slice = ids.subList(cursor, minOf(cursor + 4, ids.size))
            slice.forEach { resId ->
                val selected = backgroundStore.builtinRes() == resId
                val cell = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(cellW, cellH).also { it.marginEnd = dp(8) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (dark) 0xFF2A2E34.toInt() else 0xFFF0F1F3.toInt())
                        if (selected) setStroke(dp(2), 0xFF4C6FFF.toInt())
                    }
                    clipToOutline = true
                    isClickable = true
                    setOnClickListener {
                        backgroundStore.setBuiltin(resId)
                        buildUi()
                        Toast.makeText(this@WallpaperActivity, R.string.wallpaper_changed, Toast.LENGTH_SHORT).show()
                    }
                }
                cell.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setImageBitmap(decodeThumb(resId, cellW, cellH))
                })
                if (selected) {
                    cell.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_wallpaper_check)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
                    })
                }
                row.addView(cell)
            }
            repeat(4 - slice.size) {
                row.addView(View(this), LinearLayout.LayoutParams(cellW, cellH).also { it.marginEnd = dp(8) })
            }
            grid.addView(row)
            cursor += 4
        }
        return grid
    }

    private fun decodeThumb(resId: Int, w: Int, h: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        BitmapFactory.decodeResource(resources, resId, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.wallpaper_changed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreDefault() {
        backgroundStore.setBackground(null)
        buildUi()
        Toast.makeText(this, R.string.wallpaper_reset, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            startActivityForResult(
                Intent(this, WallpaperCropActivity::class.java).putExtra(
                    WallpaperCropActivity.EXTRA_URI, uri.toString()
                ),
                REQ_CROP
            )
        }
        if (requestCode == REQ_CROP && resultCode == RESULT_OK) {
            val saved = data?.getStringExtra(WallpaperCropActivity.EXTRA_RESULT_URI) ?: return
            backgroundStore.setBackground(saved)
            buildUi()
            Toast.makeText(this, R.string.wallpaper_changed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PICK = 3001
        private const val REQ_CROP = 3002
        private val BUILTIN_WALLS = listOf(
            R.drawable.wall_soft_01,
            R.drawable.wall_soft_02,
            R.drawable.wall_soft_03,
            R.drawable.wall_soft_04,
            R.drawable.wall_soft_05,
            R.drawable.wall_soft_06,
            R.drawable.wall_soft_07,
            R.drawable.wall_soft_08
        )
    }
}
