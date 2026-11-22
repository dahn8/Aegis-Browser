package dev.aegis.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.core.tab.BLANK_URL
import dev.aegis.browser.core.tab.BrowserTab
import dev.aegis.browser.translate.AutoTranslateStore
import dev.aegis.browser.translate.GoogleTranslator
import dev.aegis.browser.translate.MicrosoftTranslator
import dev.aegis.browser.translate.SiliconFlowKeyStore
import dev.aegis.browser.translate.SiliconFlowTranslator
import dev.aegis.browser.translate.TencentTranslator
import dev.aegis.browser.translate.TranslateLanguages
import dev.aegis.browser.translate.TranslateSource
import dev.aegis.browser.translate.TranslateSourceStore
import dev.aegis.browser.translate.TranslateTargetStore
import dev.aegis.browser.translate.ZhipuKeyStore
import dev.aegis.browser.translate.ZhipuTranslator
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.Haptics
import dev.aegis.browser.ui.ScreenOrientation
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ExecutorService

/**
 * 网页翻译控制器：从 MainActivity 拆分出来的独立控制器，
 * 负责翻译悬浮球、流式批处理翻译、多翻译源回退与翻译面板弹窗。
 */
class TranslateController(
    private val activity: Activity,
    private val rootView: FrameLayout,
    private val ioExecutor: ExecutorService,
    private val isDarkMode: () -> Boolean,
    private val currentWebView: () -> WebView?
) {

    data class BatchPayload(val hasMore: Boolean, val pairs: List<Pair<Int, String>>)

    private val translateSourceStore by lazy { TranslateSourceStore(activity) }
    private val translateTargetStore by lazy { TranslateTargetStore(activity) }
    private val autoTranslateStore by lazy { AutoTranslateStore(activity) }
    private val siliconFlowKeyStore by lazy { SiliconFlowKeyStore(activity) }
    private val zhipuKeyStore by lazy { ZhipuKeyStore(activity) }

    var translateBallView: View? = null
        private set
    private var ballCloseChip: View? = null
    private var translateBallDownRawX = 0f
    private var translateBallDownRawY = 0f
    private var translateBallStartX = 0
    private var translateBallStartY = 0
    private var translateBallTouchSlop = 0
    private var translateBallMoved = false
    private var translateBallLongPressed = false
    private var translateBallTranslated = false

    private var translateSessionWebView: WebView? = null
    private var translateSessionSource = TranslateSource.TENCENT
    private var translateSessionDone = true
    private var translateSessionBusy = false
    private var translateSessionTarget = "zh-CN"
    private var translateSessionSourceLang = ScreenOrientation.AUTO
    private var translateAppliedCount = 0
    private var translateFallbackNotified = false
    private var translatePollCount = 0
    private var translateScrollTick = -1
    private var translateScriptSource: String? = null
    private var autoTranslatedUrl: String? = null

    private val translatePollRunnable: Runnable = object : Runnable {
        override fun run() {
            val webView = translateSessionWebView ?: return
            if (translateSessionDone) return
            translatePollCount++
            val forceCheck = translatePollCount % 4 == 0
            webView.evaluateJavascript("(window.__aegisScrollTick||0)") { raw ->
                val tick = raw?.trim()?.toIntOrNull() ?: 0
                if (!translateSessionDone && !translateSessionBusy) {
                    if (forceCheck || tick != translateScrollTick) {
                        translateScrollTick = tick
                        pullNextTranslationBatch(webView)
                    }
                }
            }
            webView.postDelayed(this, 500L)
        }
    }

    private val translateBallLongPress = Runnable {
        translateBallLongPressed = true
        translateBallView?.let { Haptics.tap(it) }
        showTranslateBallMenu()
    }

    fun onPageStarted(webView: WebView) {
        translateSessionDone = true
        translateSessionBusy = false
        if (webView === translateSessionWebView) {
            translateSessionWebView = null
        }
        updateTranslateBallState(busy = false, translated = false)
    }

    fun onTranslateAction() {
        if (translateBallView != null) {
            hideTranslateBall()
        } else if (ensureTranslateBall()) {
            showTranslatePanel()
        }
    }

    fun maybeAutoTranslate(tab: BrowserTab) {
        val url = tab.url
        if (url.isBlank() || url == BLANK_URL) return
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        if (!autoTranslateStore.isEnabled(host) || autoTranslatedUrl == url) return
        autoTranslatedUrl = url
        if (ensureTranslateBall()) {
            tab.webView.evaluateJavascript("!!window.__aegisReady") { active ->
                if (active != "true") {
                    startPageTranslation(tab.webView, translateSourceStore.source)
                }
            }
        }
    }

    fun startPageTranslation(webView: WebView, requestedSource: TranslateSource) {
        val targetPreview = translateTargetStore.target
        var source = requestedSource
        if (requestedSource == TranslateSource.TENCENT && !TranslateLanguages.isTencentSupported(targetPreview)) {
            Toast.makeText(activity, R.string.translate_tencent_unsupported, Toast.LENGTH_SHORT).show()
            source = TranslateSource.GOOGLE
        } else if (requestedSource == TranslateSource.SILICONFLOW && !siliconFlowKeyStore.hasKey()) {
            Toast.makeText(activity, R.string.translate_sf_no_key, Toast.LENGTH_SHORT).show()
            source = fallbackSourceForTarget()
        } else if (requestedSource == TranslateSource.ZHIPU && !zhipuKeyStore.hasKey()) {
            Toast.makeText(activity, R.string.translate_zp_no_key, Toast.LENGTH_SHORT).show()
            source = fallbackSourceForTarget()
        }
        val previous = translateSessionWebView
        if (previous != null && previous !== webView) {
            previous.evaluateJavascript("window.__aegisRevert()", null)
        }
        translateSessionWebView = webView
        translateSessionSource = source
        translateSessionDone = false
        translateSessionBusy = false
        translateAppliedCount = 0
        translateFallbackNotified = false
        translateScrollTick = -1
        translatePollCount = 0
        webView.removeCallbacks(translatePollRunnable)
        webView.postDelayed(translatePollRunnable, 600L)
        val target = translateTargetStore.target
        translateSessionTarget = target
        translateSessionSourceLang = ScreenOrientation.AUTO
        webView.evaluateJavascript(translateScript(), null)
        updateTranslateBallState(busy = true, translated = false)
        webView.evaluateJavascript("(document.documentElement.lang||'').toLowerCase().split('-')[0]") { langRaw ->
            val pageLang = unwrapJsString(langRaw).orEmpty().trim()
            if (TranslateLanguages.isKnownSource(pageLang)) {
                translateSessionSourceLang = pageLang
            }
            webView.evaluateJavascript("window.__aegisStart(${JSONObject.quote(target)})") { raw ->
                val total = raw?.trim()?.toIntOrNull() ?: 0
                if (total <= 0) {
                    translateSessionWebView = null
                    translateSessionDone = true
                    updateTranslateBallState(busy = false, translated = false)
                    Toast.makeText(activity, R.string.translate_none, Toast.LENGTH_SHORT).show()
                } else {
                    pullNextTranslationBatch(webView)
                }
            }
        }
    }

    private fun pullNextTranslationBatch(webView: WebView) {
        if (translateSessionDone || translateSessionBusy || webView !== translateSessionWebView) return
        translateSessionBusy = true
        val batchLimit = when (translateSessionSource) {
            TranslateSource.SILICONFLOW -> 8
            TranslateSource.ZHIPU -> 10
            TranslateSource.MICROSOFT -> 20
            else -> 16
        }
        webView.evaluateJavascript("window.__aegisNext($batchLimit)") { raw ->
            val payload = parseBatchPayload(raw)
            if (payload == null) {
                translateSessionBusy = false
                if (translateAppliedCount > 0) {
                    finishTranslationSession()
                } else {
                    translateSessionDone = true
                    translateSessionWebView = null
                    updateTranslateBallState(busy = false, translated = false)
                }
            } else if (payload.pairs.isEmpty()) {
                translateSessionBusy = false
                if (payload.hasMore) updateTranslateBallState(busy = false, translated = true)
                else finishTranslationSession()
            } else {
                val texts = payload.pairs.map { it.second }
                translateBatchTexts(texts, translateSessionSource) { translated, success, used ->
                    translateSessionSource = used
                    if (success <= 0) {
                        translateSessionBusy = false
                        if (translateAppliedCount <= 0) failTranslationSession()
                        else finishTranslationSession()
                        return@translateBatchTexts
                    }
                    val pairs = ArrayList<Pair<Int, String>>(payload.pairs.size)
                    payload.pairs.forEachIndexed { idx, pair ->
                        val v = translated.getOrNull(idx)?.trim().orEmpty()
                        if (v.isNotBlank()) pairs.add(pair.first to v)
                    }
                    if (pairs.isEmpty()) {
                        translateSessionBusy = false
                        finishTranslationSession()
                        return@translateBatchTexts
                    }
                    val jsonArr = JSONArray().apply {
                        pairs.forEach { put(JSONArray().put(it.first).put(it.second)) }
                    }
                    webView.evaluateJavascript("window.__aegisApplyPairs(${JSONObject.quote(jsonArr.toString())})") { appliedRaw ->
                        val applied = appliedRaw?.trim()?.toIntOrNull() ?: pairs.size
                        translateAppliedCount += applied
                        translateSessionBusy = false
                        pullNextTranslationBatch(webView)
                    }
                }
            }
        }
    }

    private fun translateScript(): String {
        translateScriptSource?.let { return it }
        return try {
            activity.assets.open("aegis_translate.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
                .also { translateScriptSource = it }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseBatchPayload(raw: String?): BatchPayload? {
        val unwrapped = unwrapJsString(raw) ?: return null
        return runCatching {
            val obj = JSONObject(unwrapped)
            val array = obj.optJSONArray("items") ?: JSONArray()
            val pairs = ArrayList<Pair<Int, String>>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONArray(i) ?: continue
                val index = item.optInt(0, -1)
                val text = item.optString(1, "")
                if (index >= 0 && text.isNotBlank()) pairs.add(index to text)
            }
            BatchPayload(obj.optBoolean("hasMore", false), pairs)
        }.getOrNull()
    }

    private fun unwrapJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"")) {
            return runCatching { JSONTokener(trimmed).nextValue() as? String }.getOrNull()
        }
        return trimmed
    }

    private fun fallbackSourceForTarget(): TranslateSource =
        if (TranslateLanguages.isTencentSupported(translateSessionTarget)) TranslateSource.TENCENT else TranslateSource.GOOGLE

    private fun translateBatchTexts(
        texts: List<String>,
        source: TranslateSource,
        onDone: (List<String>, Int, TranslateSource) -> Unit
    ) {
        when (source) {
            TranslateSource.MICROSOFT -> {
                ioExecutor.execute {
                    val translated = MicrosoftTranslator.translateBatch(texts, translateSessionTarget)
                    if (translated == null) {
                        val fb = fallbackSourceForTarget()
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                translateBatchTexts(texts, fb, onDone)
                            }
                        }
                    } else {
                        val success = texts.indices.count { translated[it] != texts[it] }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onDone(translated, success.coerceAtLeast(1), TranslateSource.MICROSOFT)
                            }
                        }
                    }
                }
            }
            TranslateSource.SILICONFLOW -> {
                val apiKey = siliconFlowKeyStore.key
                if (apiKey.isBlank()) {
                    translateBatchTexts(texts, fallbackSourceForTarget(), onDone)
                    return
                }
                ioExecutor.execute {
                    val results = SiliconFlowTranslator.translateAll(texts, translateSessionTarget, apiKey)
                    val okCount = results.count { !it.isNullOrBlank() }
                    if (okCount <= 0) {
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (!translateFallbackNotified) {
                                translateFallbackNotified = true
                                Toast.makeText(activity, R.string.translate_sf_fallback, Toast.LENGTH_SHORT).show()
                            }
                            translateBatchTexts(texts, fallbackSourceForTarget(), onDone)
                        }
                    } else {
                        val merged = texts.mapIndexed { idx, orig ->
                            results.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: orig
                        }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onDone(merged, okCount, TranslateSource.SILICONFLOW)
                            }
                        }
                    }
                }
            }
            TranslateSource.ZHIPU -> {
                val apiKey = zhipuKeyStore.key
                if (apiKey.isBlank()) {
                    translateBatchTexts(texts, fallbackSourceForTarget(), onDone)
                    return
                }
                ioExecutor.execute {
                    val results = ZhipuTranslator.translateAll(texts, translateSessionTarget, apiKey)
                    val okCount = results.count { !it.isNullOrBlank() }
                    if (okCount <= 0) {
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (!translateFallbackNotified) {
                                translateFallbackNotified = true
                                Toast.makeText(activity, R.string.translate_zp_fallback, Toast.LENGTH_SHORT).show()
                            }
                            translateBatchTexts(texts, fallbackSourceForTarget(), onDone)
                        }
                    } else {
                        val merged = texts.mapIndexed { idx, orig ->
                            results.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: orig
                        }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onDone(merged, okCount, TranslateSource.ZHIPU)
                            }
                        }
                    }
                }
            }
            TranslateSource.GOOGLE -> {
                ioExecutor.execute {
                    val translated = GoogleTranslator.translateAll(texts, translateSessionTarget)
                    val okCount = translated.count { !it.isNullOrBlank() }
                    if (okCount <= 0) {
                        if (!TranslateLanguages.isTencentSupported(translateSessionTarget)) {
                            activity.runOnUiThread {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    onDone(emptyList(), 0, TranslateSource.GOOGLE)
                                }
                            }
                            return@execute
                        }
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed && !translateFallbackNotified) {
                                translateFallbackNotified = true
                                Toast.makeText(activity, R.string.translate_google_fallback, Toast.LENGTH_SHORT).show()
                            }
                        }
                        translateWithTencentBatch(texts) { result, count ->
                            activity.runOnUiThread {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    onDone(result, count, TranslateSource.TENCENT)
                                }
                            }
                        }
                        return@execute
                    }
                    val merged = ArrayList<String>(texts.size)
                    val missing = ArrayList<Int>()
                    for (i in texts.indices) {
                        val v = translated.getOrNull(i)
                        if (v.isNullOrBlank()) {
                            merged.add(texts[i])
                            missing.add(i)
                        } else {
                            merged.add(v)
                        }
                    }
                    if (missing.isNotEmpty() && TranslateLanguages.isTencentSupported(translateSessionTarget)) {
                        val fillTexts = missing.map { texts[it] }
                        val filled = ArrayList<String>(fillTexts.size)
                        val to = TranslateLanguages.byCode(translateSessionTarget).tencentCode
                        val from = translateSessionSourceLang
                        for (batch in TencentTranslator.chunk(fillTexts)) {
                            filled.addAll(TencentTranslator.translateBatch(batch, from, to) ?: batch)
                        }
                        missing.forEachIndexed { k, idx ->
                            val v = filled.getOrNull(k).orEmpty()
                            if (v.isNotBlank()) merged[idx] = v
                        }
                    }
                    val success = texts.indices.count { merged[it] != texts[it] }
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onDone(merged, success, TranslateSource.GOOGLE)
                        }
                    }
                }
            }
            else -> {
                translateWithTencentBatch(texts) { result, count ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onDone(result, count, TranslateSource.TENCENT)
                        }
                    }
                }
            }
        }
    }

    private fun translateWithTencentBatch(
        texts: List<String>,
        onDone: (List<String>, Int) -> Unit
    ) {
        ioExecutor.execute {
            val merged = ArrayList<String>(texts.size)
            var success = 0
            val to = TranslateLanguages.byCode(translateSessionTarget).tencentCode
            val from = translateSessionSourceLang
            for (batch in TencentTranslator.chunk(texts)) {
                val translated = TencentTranslator.translateBatch(batch, from, to)
                if (translated == null) {
                    merged.addAll(batch)
                } else {
                    merged.addAll(translated)
                    success += batch.size
                }
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onDone(merged, success)
            }
        }
    }

    private fun finishTranslationSession() {
        translateSessionDone = true
        translateSessionBusy = false
        updateTranslateBallState(busy = false, translated = translateAppliedCount > 0)
    }

    private fun failTranslationSession() {
        translateSessionDone = true
        translateSessionBusy = false
        translateSessionWebView = null
        updateTranslateBallState(busy = false, translated = false)
        Toast.makeText(activity, R.string.translate_failed, Toast.LENGTH_SHORT).show()
    }

    private fun revertPageTranslation(webView: WebView) {
        translateSessionDone = true
        translateSessionBusy = false
        translateSessionWebView = null
        webView.evaluateJavascript("window.__aegisRevert()", null)
        updateTranslateBallState(busy = false, translated = false)
    }

    private fun ensureTranslateBall(): Boolean {
        if (translateBallView != null) return true
        val ball = activity.layoutInflater.inflate(R.layout.view_translate_ball, rootView, false)
        val size = dp(40)
        ball.layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        translateBallTouchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        ball.setOnClickListener { showTranslatePanel() }
        ball.setOnTouchListener { v, e -> onTranslateBallTouch(v, e, rootView) }
        rootView.addView(ball)
        translateBallView = ball
        rootView.post { restoreTranslateBallPosition(rootView, ball) }
        updateTranslateBallState(busy = false, translated = false)
        return true
    }

    private fun onTranslateBallTouch(view: View, event: MotionEvent, root: ViewGroup): Boolean {
        val params = view.layoutParams as FrameLayout.LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                translateBallMoved = false
                translateBallLongPressed = false
                translateBallDownRawX = event.rawX
                translateBallDownRawY = event.rawY
                translateBallStartX = params.leftMargin
                translateBallStartY = params.topMargin
                hideBallCloseChip()
                view.postDelayed(translateBallLongPress, 460L)
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(translateBallLongPress)
                if (!translateBallLongPressed) {
                    if (!translateBallMoved) view.performClick() else snapTranslateBallToEdge(root, view)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - translateBallDownRawX
                val dy = event.rawY - translateBallDownRawY
                if (!translateBallMoved && (kotlin.math.abs(dx) > translateBallTouchSlop || kotlin.math.abs(dy) > translateBallTouchSlop)) {
                    translateBallMoved = true
                    view.removeCallbacks(translateBallLongPress)
                }
                if (translateBallMoved) {
                    val maxX = (root.width - view.width).coerceAtLeast(0)
                    val minY = ballTopInset()
                    val maxY = (root.height - view.height - ballBottomInset()).coerceAtLeast(minY)
                    params.leftMargin = (translateBallStartX + dx.toInt()).coerceIn(0, maxX)
                    params.topMargin = (translateBallStartY + dy.toInt()).coerceIn(minY, maxY)
                    view.layoutParams = params
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(translateBallLongPress)
                if (translateBallMoved) snapTranslateBallToEdge(root, view)
            }
        }
        return true
    }

    private fun ballTopInset(): Int {
        var sb = 0
        if (Build.VERSION.SDK_INT >= 30) {
            sb = rootView.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.top ?: 0
        }
        if (sb <= 0) {
            val resId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) sb = activity.resources.getDimensionPixelSize(resId)
        }
        if (sb <= 0) sb = (24 * activity.resources.displayMetrics.density).toInt()
        return sb + (4 * activity.resources.displayMetrics.density).toInt()
    }

    private fun ballBottomInset(): Int {
        var nav = 0
        if (Build.VERSION.SDK_INT >= 30) {
            nav = rootView.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0
        }
        return nav + (4 * activity.resources.displayMetrics.density).toInt()
    }

    private fun snapTranslateBallToEdge(root: ViewGroup, ball: View) {
        val params = ball.layoutParams as FrameLayout.LayoutParams
        val maxX = (root.width - ball.width).coerceAtLeast(0)
        val minY = ballTopInset()
        val maxY = (root.height - ball.height - ballBottomInset()).coerceAtLeast(minY)
        params.topMargin = params.topMargin.coerceIn(minY, maxY)
        val centerX = params.leftMargin + ball.width / 2f
        val targetX = if (centerX >= root.width / 2f) maxX else 0
        val startX = params.leftMargin
        if (startX == targetX) {
            ball.layoutParams = params
            saveTranslateBallPosition(root, ball)
            return
        }
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.leftMargin = it.animatedValue as Int
                ball.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveTranslateBallPosition(root, ball)
                }
            })
            start()
        }
    }

    private fun ballPrefs(): SharedPreferences = activity.getSharedPreferences("aegis_translate_ball", 0)

    private fun restoreTranslateBallPosition(root: ViewGroup, ball: View) {
        val maxX = (root.width - ball.width).coerceAtLeast(0)
        val fx = ballPrefs().getFloat("fx", 1f).coerceIn(0f, 1f)
        val fy = ballPrefs().getFloat("fy", 0.66f).coerceIn(0f, 1f)
        val params = ball.layoutParams as FrameLayout.LayoutParams
        val minY = ballTopInset()
        val safeMaxY = (root.height - ball.height - ballBottomInset()).coerceAtLeast(minY)
        params.leftMargin = (maxX * fx).toInt()
        params.topMargin = (safeMaxY * fy).toInt().coerceIn(minY, safeMaxY)
        ball.layoutParams = params
    }

    private fun saveTranslateBallPosition(root: ViewGroup, ball: View) {
        val maxX = (root.width - ball.width).coerceAtLeast(1)
        val maxY = (root.height - ball.height).coerceAtLeast(1)
        val params = ball.layoutParams as FrameLayout.LayoutParams
        ballPrefs().edit()
            .putFloat("fx", params.leftMargin.toFloat() / maxX)
            .putFloat("fy", params.topMargin.toFloat() / maxY)
            .apply()
    }

    fun showTranslatePanel() {
        hideBallCloseChip()
        val webView = currentWebView() ?: return
        val url = webView.url.orEmpty()
        if (url.isBlank() || url == BLANK_URL) {
            Toast.makeText(activity, R.string.translate_home, Toast.LENGTH_SHORT).show()
            return
        }
        val dark = isDarkMode()
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        var target = translateTargetStore.target
        var source = translateSourceStore.source
        val view = activity.layoutInflater.inflate(R.layout.dialog_translate_panel, null, false)
        val dialog = AegisDialog.Builder(
            activity,
            if (dark) R.style.Theme_Aegis_Dialog_Dark else R.style.Theme_Aegis_Dialog
        ).setView(view).create()

        val targetValue = view.findViewById<TextView>(R.id.panel_target_value)
        val sourceValue = view.findViewById<TextView>(R.id.panel_source_value)
        val revert = view.findViewById<TextView>(R.id.panel_revert)

        fun refresh() {
            targetValue.text = TranslateLanguages.displayName(activity, target)
            sourceValue.text = sourceDisplayName(source)
        }
        refresh()

        val openTargetPicker = {
            showLanguagePicker(target) { code ->
                target = code
                refresh()
            }
        }
        val openSourcePicker = {
            showSourcePicker(source) { picked ->
                source = picked
                refresh()
            }
        }
        view.findViewById<TextView>(R.id.panel_target_label).setOnClickListener { openTargetPicker() }
        targetValue.setOnClickListener { openTargetPicker() }
        view.findViewById<TextView>(R.id.panel_source_label).setOnClickListener { openSourcePicker() }
        sourceValue.setOnClickListener { openSourcePicker() }

        val autoBox = view.findViewById<CheckBox>(R.id.panel_auto_translate)
        autoBox.isChecked = autoTranslateStore.isEnabled(host)
        autoBox.setOnCheckedChangeListener { _, checked -> autoTranslateStore.setEnabled(host, checked) }

        webView.evaluateJavascript("!!window.__aegisReady") { active ->
            val translated = active == "true"
            revert.isEnabled = translated
            revert.alpha = if (translated) 1f else 0.35f
        }
        revert.setOnClickListener {
            if (revert.isEnabled) {
                revertPageTranslation(webView)
                dialog.dismiss()
            }
        }
        if (dark) {
            listOf(
                R.id.panel_title, R.id.panel_target_label, R.id.panel_target_value,
                R.id.panel_source_label, R.id.panel_source_value
            ).forEach { view.findViewById<TextView>(it).setTextColor(0xFFEDF0F2.toInt()) }
            view.findViewById<TextView>(R.id.panel_cancel).setTextColor(0xFF8FB4FF.toInt())
            view.findViewById<TextView>(R.id.panel_translate).setTextColor(0xFF8FB4FF.toInt())
            sourceValue.setBackgroundResource(R.drawable.bg_panel_underline_grey_dark)
            autoBox.setTextColor(0xFF9AA1A8.toInt())
        }
        view.findViewById<TextView>(R.id.panel_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.panel_translate).setOnClickListener {
            translateTargetStore.target = target
            translateSourceStore.source = source
            dialog.dismiss()
            startPageTranslation(webView, source)
        }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2)
    }

    private fun sourceDisplayName(source: TranslateSource): String = when (source) {
        TranslateSource.MICROSOFT -> activity.getString(R.string.translate_source_microsoft_name)
        TranslateSource.SILICONFLOW -> activity.getString(R.string.translate_source_siliconflow_name)
        TranslateSource.ZHIPU -> activity.getString(R.string.translate_source_zhipu_name)
        TranslateSource.GOOGLE -> activity.getString(R.string.translate_source_google_name)
        else -> activity.getString(R.string.translate_source_tencent_name)
    }

    private fun showLanguagePicker(current: String, onPick: (String) -> Unit) {
        val languages = TranslateLanguages.all
        val checked = languages.indexOfFirst { it.code == current }
        val labels = languages.map { TranslateLanguages.displayName(activity, it.code) }.toTypedArray()
        AegisDialog.Builder(activity)
            .setTitle(R.string.translate_target_language)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                onPick(languages[which].code)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSourcePicker(current: TranslateSource, onPick: (TranslateSource) -> Unit) {
        val sources = listOf(
            TranslateSource.TENCENT, TranslateSource.MICROSOFT,
            TranslateSource.SILICONFLOW, TranslateSource.ZHIPU, TranslateSource.GOOGLE
        )
        val labels = sources.map { sourceDisplayName(it) }.toTypedArray()
        val checked = sources.indexOf(current).coerceAtLeast(0)
        AegisDialog.Builder(activity)
            .setTitle(R.string.translate_source_label)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                onPick(sources[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTranslateBallMenu() {
        if (ballCloseChip != null) {
            hideBallCloseChip()
            return
        }
        val ball = translateBallView ?: return
        val dark = isDarkMode()
        val chipSize = dp(22)
        val chip = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (dark) 0xFFEDF0F2.toInt() else 0xFF3A4145.toInt())
            setBackgroundResource(if (dark) R.drawable.bg_ball_close_chip_dark else R.drawable.bg_ball_close_chip)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                hideBallCloseChip()
                hideTranslateBall()
            }
        }
        val ballParams = ball.layoutParams as FrameLayout.LayoutParams
        val ballSize = if (ball.width > 0) ball.width else dp(40)
        val ballOnLeft = ballParams.leftMargin + ballSize / 2 < rootView.width / 2
        val chipParams = FrameLayout.LayoutParams(chipSize, chipSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = if (ballOnLeft) ballParams.leftMargin + ballSize - dp(8) else ballParams.leftMargin - chipSize + dp(8)
            topMargin = (ballParams.topMargin - dp(8)).coerceIn(0, (rootView.height - chipSize).coerceAtLeast(0))
        }
        rootView.addView(chip, chipParams)
        chip.elevation = dp(8).toFloat()
        chip.translationZ = dp(8).toFloat()
        chip.bringToFront()
        chip.alpha = 0f
        chip.animate().alpha(1f).setDuration(120L).start()
        chip.postDelayed({
            if (ballCloseChip === chip) hideBallCloseChip()
        }, 3500L)
        ballCloseChip = chip
    }

    private fun hideBallCloseChip() {
        ballCloseChip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        ballCloseChip = null
    }

    private fun hideTranslateBall() {
        hideBallCloseChip()
        translateSessionDone = true
        translateSessionBusy = false
        translateSessionWebView = null
        translateBallView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        translateBallView = null
        translateBallTranslated = false
    }

    private fun updateTranslateBallState(busy: Boolean, translated: Boolean) {
        translateBallTranslated = translated
        val ball = translateBallView ?: return
        val dark = isDarkMode()
        val icon = ball.findViewById<ImageView>(R.id.translate_ball_icon) ?: return
        val tint = when {
            translated && dark -> 0xFF8FB4FF.toInt()
            translated -> 0xFF1A73E8.toInt()
            else -> if (dark) 0xFFEDF0F2.toInt() else 0xFF1A1E21.toInt()
        }
        icon.setColorFilter(tint)
        ball.setBackgroundResource(
            when {
                translated && dark -> R.drawable.bg_translate_ball_active_dark
                translated -> R.drawable.bg_translate_ball_active
                dark -> R.drawable.bg_translate_ball_dark
                else -> R.drawable.bg_translate_ball
            }
        )
        ball.findViewById<ImageView>(R.id.translate_ball_badge)?.visibility =
            if (translated) View.VISIBLE else View.GONE
        ball.alpha = if (busy) 0.55f else 1f
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
