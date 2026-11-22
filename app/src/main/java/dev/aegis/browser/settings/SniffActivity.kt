package dev.aegis.browser.settings

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.aegis.browser.R
import dev.aegis.browser.core.web.ResourceLog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资源嗅探结果页：只列媒体资源（m3u8 / 视频 / 音频），点击就地全屏播放。
 *
 * 播放器直接叠在本页里而不是另开 Activity：返回键第一次只关播放层，
 * 列表页保持原样，不会出现"回到列表一秒又被弹回主页"。
 */
class SniffActivity : Activity() {

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        const val HIDE_CONTROLS_DELAY = 3500L
    }

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private var dark = false

    // 播放层
    private lateinit var layer: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var audioBox: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var playButton: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeView: TextView
    private lateinit var durationView: TextView

    private var player: MediaPlayer? = null
    private var prepared = false
    private var dragging = false

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            syncProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val hideControls = Runnable {
        if (prepared && player?.isPlaying == true) controls.visibility = View.GONE
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = ThemeManager.apply(this, ThemeSettings(this), settingsScreen = true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sniff)
        ThemeManager.applyStatusBarAppearance(this, dark)

        findViewById<View>(R.id.button_top_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.topbar_title).setText(R.string.sniff)
        findViewById<ImageButton>(R.id.button_sniff_refresh).setOnClickListener { render() }

        container = findViewById(R.id.sniff_container)
        scroll = findViewById(R.id.sniff_scroll)

        layer = findViewById(R.id.player_layer)
        stage = findViewById(R.id.player_stage)
        surfaceView = findViewById(R.id.player_surface)
        audioBox = findViewById(R.id.player_audio_box)
        loading = findViewById(R.id.player_loading)
        errorView = findViewById(R.id.player_error)
        controls = findViewById(R.id.player_controls)
        playButton = findViewById(R.id.player_play)
        seek = findViewById(R.id.player_seek)
        timeView = findViewById(R.id.player_time)
        durationView = findViewById(R.id.player_duration)

        findViewById<View>(R.id.player_close).setOnClickListener { closePlayer() }
        setupPlayerUi()
        setupPlayerGestures()
        render()
    }

    // ---------------- 列表 ----------------

    private fun render() {
        container.removeAllViews()
        val entries = ResourceLog.snapshot()
        container.addView(heading(getString(R.string.sniff_log_hint)))
        if (entries.isEmpty()) {
            container.addView(hint(getString(R.string.sniff_empty_log)))
            return
        }
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        entries.reversed().forEach { entry ->
            container.addView(row(format.format(Date(entry.time)), entry.kind, entry.url, entry.referer))
        }
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setTextColor(if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt())
        setPadding(dp(24), dp(18), dp(24), dp(6))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(0, dp(40), 0, 0)
    }

    private fun row(time: String, kind: String, url: String, referer: String): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            setOnClickListener { playInPlace(url, referer.ifEmpty { ResourceLog.pageUrl ?: "" }) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = time
            textSize = 13f
            setTextColor(if (dark) 0xFF9AA0A8.toInt() else 0xFF8A8F98.toInt())
        })
        head.addView(chip("load", 0x33E05252, 0xFFE05252.toInt()))
        head.addView(chip(kind, 0x333B5BDB, 0xFF3B5BDB.toInt()))
        block.addView(head)
        block.addView(TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(if (dark) 0xFFEDEFF2.toInt() else 0xFF1F2328.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
            setTextIsSelectable(true)
        })
        block.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(if (dark) 0x1FFFFFFF else 0x14000000)
        })
        return block
    }

    private fun chip(text: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(fg)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(bg)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(8)
        layoutParams = lp
    }

    // ---------------- 播放层 ----------------

    private fun setupPlayerUi() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val mp = player
                if (mp == null) {
                    openStream()
                } else {
                    // 缩放画面会重建 Surface，必须重新挂载，否则只剩声音
                    runCatching { mp.setDisplay(holder) }
                    if (prepared) runCatching { mp.start() }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                player?.let { runCatching { it.setDisplay(holder) } }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        playButton.setOnClickListener { toggle() }
        stage.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else {
                scheduleHide()
                View.VISIBLE
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val mp = player ?: return
                if (prepared && mp.duration > 0) {
                    mp.seekTo((sb.progress * mp.duration) / 1000)
                }
            }

            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mp = player
                    if (mp != null && prepared) timeView.text = format((progress.toLong() * mp.duration) / 1000)
                }
            }
        })
    }

    private fun playInPlace(url: String, referer: String) {
        closePlayer()
        layer.visibility = View.VISIBLE
        layer.tag = referer
        layer.contentDescription = url
        findViewById<TextView>(R.id.player_audio_name).text = fileName(url)
        // 播放层黑底：状态栏同步改黑 + 白图标
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
        (findViewById<View>(R.id.player_close).layoutParams as FrameLayout.LayoutParams).topMargin =
            statusBarHeight() + dp(8)
        if (surfaceView.holder.surface.isValid) openStream()
    }

    private fun headers(referer: String): Map<String, String> {
        val map = HashMap<String, String>()
        map["User-Agent"] = UA
        if (referer.isNotEmpty()) {
            map["Referer"] = referer
            runCatching {
                val u = Uri.parse(referer)
                map["Origin"] = "${u.scheme}://${u.host}/"
            }
        }
        return map
    }

    private fun openStream() {
        val url = layer.contentDescription?.toString().orEmpty()
        if (url.isEmpty()) return
        if (player != null) return
        loading.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        val mp = MediaPlayer()
        player = mp
        mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
        try {
            mp.setDataSource(this, Uri.parse(url), headers(layer.tag as? String ?: ""))
        } catch (e: Exception) {
            showError("地址无法解析：${e.message}")
            return
        }
        mp.setOnPreparedListener {
            prepared = true
            loading.visibility = View.GONE
            fitSurface(it.videoWidth, it.videoHeight)
            it.start()
            syncProgress()
            handler.removeCallbacks(tick)
            handler.post(tick)
            scheduleHide()
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) fitSurface(w, h) }
        mp.setOnErrorListener { _, _, extra ->
            showError(if (prepared) "播放中断，点按重试" else "播放失败（${errorText(extra)}）")
            true
        }
        mp.setOnCompletionListener {
            prepared = false
            playButton.setImageResource(R.drawable.ic_play)
            controls.visibility = View.VISIBLE
            handler.removeCallbacks(hideControls)
        }
        try {
            mp.prepareAsync()
        } catch (e: Exception) {
            showError("准备失败：${e.message}")
        }
    }

    /** 按视频原始比例居中；宽高为 0 说明是纯音频，藏画面、显波形卡 */
    private fun fitSurface(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            surfaceView.visibility = View.GONE
            audioBox.visibility = View.VISIBLE
            return
        }
        surfaceView.visibility = View.VISIBLE
        audioBox.visibility = View.GONE
        stage.post {
            val cw = stage.width
            val ch = stage.height
            if (cw <= 0 || ch <= 0) return@post
            val ratio = videoWidth.toFloat() / videoHeight.toFloat()
            var w = cw
            var h = (cw / ratio).toInt()
            if (h > ch) {
                h = ch
                w = (ch * ratio).toInt()
            }
            surfaceView.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.CENTER
            }
            handler.postDelayed({ player?.let { mp -> runCatching { mp.setDisplay(surfaceView.holder) } } }, 60)
        }
    }

    private fun syncProgress() {
        val mp = player ?: return
        if (!prepared || dragging) return
        val position = runCatching { mp.currentPosition }.getOrDefault(0)
        val duration = runCatching { mp.duration }.getOrDefault(0)
        if (duration > 0) {
            seek.progress = ((position.toLong() * 1000) / duration).toInt()
            durationView.text = format(duration.toLong())
        }
        timeView.text = format(position.toLong())
        val playing = runCatching { mp.isPlaying }.getOrDefault(false)
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggle() {
        val mp = player ?: return
        if (!prepared) {
            retry()
            return
        }
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                controls.visibility = View.VISIBLE
                handler.removeCallbacks(hideControls)
            } else {
                mp.start()
                scheduleHide()
            }
        }
        syncProgress()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, HIDE_CONTROLS_DELAY)
    }

    private fun retry() {
        releasePlayer()
        openStream()
    }

    private fun showError(text: String) {
        loading.visibility = View.GONE
        errorView.text = text
        errorView.visibility = View.VISIBLE
        errorView.setOnClickListener { retry() }
    }

    private fun setupPlayerGestures() {
        val prefs = BrowserPrefs(this)
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        var downX = 0f
        var downY = 0f
        var mode = 0 // 0=none, 1=seek, 2=brightness, 3=volume
        var startBrightness = 0.5f
        var startVolume = 0
        var startPos = 0
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        stage.setOnTouchListener { v, event ->
            if (!prefs.videoPlayerGestures) {
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    mode = 0
                    startBrightness = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                    startVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    startPos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (mode == 0 && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        mode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1
                        else if (downX < v.width / 2f) 2 else 3
                    }
                    when (mode) {
                        2 -> {
                            val delta = -dy / v.height.coerceAtLeast(1)
                            val lp = window.attributes
                            lp.screenBrightness = (startBrightness + delta).coerceIn(0.05f, 1.0f)
                            window.attributes = lp
                        }
                        3 -> {
                            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val delta = ((-dy / v.height.coerceAtLeast(1)) * maxVol).toInt()
                            am.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                (startVolume + delta).coerceIn(0, maxVol),
                                android.media.AudioManager.FLAG_SHOW_UI
                            )
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (mode == 0) {
                        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    } else if (mode == 1) {
                        val mp = player
                        val dur = runCatching { mp?.duration ?: 0 }.getOrDefault(0)
                        if (mp != null && dur > 0) {
                            val dx = event.x - downX
                            val deltaMs = ((dx / v.width.coerceAtLeast(1)) * 90_000f).toInt()
                            val target = (startPos + deltaMs).coerceIn(0, dur)
                            runCatching { mp.seekTo(target) }
                        }
                    }
                    mode = 0
                }
            }
            true
        }
    }

    private fun closePlayer() {
        releasePlayer()
        layer.visibility = View.GONE
        surfaceView.visibility = View.VISIBLE
        audioBox.visibility = View.GONE
        loading.visibility = View.GONE
        errorView.visibility = View.GONE
        controls.visibility = View.VISIBLE
        seek.progress = 0
        timeView.text = "00:00"
        durationView.text = "00:00"
        layer.tag = null
        ThemeManager.applyStatusBarAppearance(this, dark)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideControls)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.reset() }
            it.release()
        }
        player = null
        prepared = false
    }

    private fun errorText(extra: Int): String = when (extra) {
        MediaPlayer.MEDIA_ERROR_IO -> "网络或地址不可读"
        MediaPlayer.MEDIA_ERROR_MALFORMED -> "媒体格式不完整"
        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "系统不支持该格式"
        MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "连接超时"
        else -> "错误码 $extra"
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun format(ms: Long): String {
        val total = ms / 1000
        val minute = total / 60
        val second = total % 60
        return if (minute >= 60) {
            String.format("%d:%02d:%02d", minute / 60, minute % 60, second)
        } else {
            String.format("%02d:%02d", minute, second)
        }
    }

    private fun fileName(link: String): String {
        val name = link.substringAfterLast('/').substringBefore('?')
        return name.ifBlank { link }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (layer.visibility == View.VISIBLE) {
            closePlayer()
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        player?.let { if (prepared && it.isPlaying) it.pause() }
        playButton.setImageResource(R.drawable.ic_play)
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
