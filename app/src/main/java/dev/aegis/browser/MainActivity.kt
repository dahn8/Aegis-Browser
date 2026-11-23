package dev.aegis.browser

import android.app.Activity
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.aegis.browser.bookmarks.BookmarkStore
import dev.aegis.browser.core.tab.BLANK_URL
import dev.aegis.browser.core.tab.BrowserTab
import dev.aegis.browser.core.tab.TabManager
import dev.aegis.browser.core.web.BlobDownloaderImpl
import dev.aegis.browser.core.web.DesktopMode
import dev.aegis.browser.core.web.DownloadHandler
import dev.aegis.browser.core.web.HttpsUpgradeCallback
import dev.aegis.browser.core.web.IncognitoMode
import dev.aegis.browser.core.web.SitePermissions
import dev.aegis.browser.core.web.SslWarning
import dev.aegis.browser.core.web.UserAgentMode
import dev.aegis.browser.data.TabSessionStore
import dev.aegis.browser.downloads.DownloadManagerStore
import dev.aegis.browser.history.HistoryStore
import dev.aegis.browser.home.FaviconLoader
import dev.aegis.browser.home.HomeBackgroundStore
import dev.aegis.browser.home.Shortcut
import dev.aegis.browser.home.ShortcutStore
import dev.aegis.browser.home.WeatherFetcher
import dev.aegis.browser.home.WeatherPopup
import dev.aegis.browser.home.WeatherStore
import dev.aegis.browser.menu.MenuCustomizationStore
import dev.aegis.browser.privacy.PrivacyInterceptor
import dev.aegis.browser.privacy.PrivacyScriptProvider
import dev.aegis.browser.privacy.RuleLoader
import dev.aegis.browser.privacy.RuleUpdater
import dev.aegis.browser.privacy.data.DataCleaner
import dev.aegis.browser.privacy.data.HttpsUpgradePolicy
import dev.aegis.browser.privacy.data.JsPolicyImpl
import dev.aegis.browser.privacy.data.PrivacySettingsStore
import dev.aegis.browser.privacy.data.TrackingPolicyImpl
import dev.aegis.browser.scanner.ScannerActivity
import dev.aegis.browser.search.SearchEngineStore
import dev.aegis.browser.search.SearchPreconnect
import dev.aegis.browser.search.SuggestionFetcher
import dev.aegis.browser.sections.SectionHostActivity
import dev.aegis.browser.settings.AppThemeActivity
import dev.aegis.browser.settings.BackNoReload
import dev.aegis.browser.settings.BrowserPrefs
import dev.aegis.browser.settings.SettingsActivity
import dev.aegis.browser.settings.SiteDataCleaner
import dev.aegis.browser.settings.SniffActivity
import dev.aegis.browser.settings.WallpaperActivity
import dev.aegis.browser.settings.WebSettingsActivity
import dev.aegis.browser.ui.AegisDialog
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.FontSizeStore
import dev.aegis.browser.ui.Haptics
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.SoftThemeChrome
import dev.aegis.browser.ui.SoftThemeStore
import dev.aegis.browser.ui.SoftThemes
import dev.aegis.browser.ui.SuggestionsAdapter
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity(), HttpsUpgradeCallback {

    companion object {
        const val REQ_BOOKMARKS = 100
        const val REQ_SCANNER = 101
        const val REQ_PICK_BACKGROUND = 102
        const val REQ_HISTORY = 103
        const val SHORTCUTS_PER_ROW = 4
        const val SHORTCUT_MAX = 12
        const val SUGGEST_DEBOUNCE_MS = 150L
        private val URL_REGEX = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+([/?#].*)?$")

        var pendingRestoreUrl: String? = null
    }

    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "aegis-io").apply { isDaemon = true }
    }

    private lateinit var themeSettings: ThemeSettings
    private lateinit var topAddress: EditText
    private lateinit var homeAddress: TextView
    private lateinit var topBar: View
    private lateinit var homePill: View
    private lateinit var homeEngineButton: View
    private lateinit var contentColumn: LinearLayout
    private lateinit var rootView: FrameLayout
    private lateinit var bottomNav: View
    private lateinit var homeGradient: View
    private lateinit var homeContent: View
    private lateinit var pageSettingsButton: View
    private var homeWeather: TextView? = null
    private var weatherStore: WeatherStore? = null
    private lateinit var incognitoBanner: View
    private var inprivateCard: View? = null
    private lateinit var shortcutContainer: LinearLayout
    private lateinit var backArrow: View
    private lateinit var topReload: View
    private lateinit var topStop: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tabCountView: TextView
    private lateinit var errorPage: View
    private lateinit var errorDescView: TextView
    private lateinit var errorCodeView: TextView
    private lateinit var errorUrlView: TextView
    private lateinit var errorDetailsToggle: View
    private lateinit var errorDetailsArrow: ImageView
    private lateinit var webContainer: ViewGroup
    private lateinit var topOverlay: View
    private lateinit var statusBarSpacer: View
    private lateinit var topSpacer: View
    private lateinit var homeBgImage: ImageView
    /** 上一次排版使用的方向，用来避免尺寸微调时反复重建快捷方式网格 */
    private var orientationLandscapeFlag: Boolean? = null

    private var findBar: View? = null
    private var findInput: EditText? = null
    private var findCountView: TextView? = null
    private var suggestionPanel: LinearLayout? = null
    private var suggestionList: ListView? = null

    private lateinit var store: SearchEngineStore
    private lateinit var interceptor: PrivacyInterceptor
    private lateinit var privacySettings: PrivacySettingsStore
    private lateinit var browserPrefs: BrowserPrefs
    private lateinit var httpsUpgradePolicy: HttpsUpgradePolicy
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var historyStore: HistoryStore
    private lateinit var tabSessionStore: TabSessionStore
    private lateinit var menuCustomizationStore: MenuCustomizationStore
    private lateinit var fontSizeStore: FontSizeStore
    private lateinit var shortcutStore: ShortcutStore
    private lateinit var homeBackgroundStore: HomeBackgroundStore
    private lateinit var downloadStore: DownloadManagerStore
    private lateinit var tabManager: TabManager

    private lateinit var menuController: MenuController
    private lateinit var translateController: TranslateController
    private val softThemeStore by lazy { SoftThemeStore(this) }

    var sniffHandler: DownloadHandler? = null

    private var isDarkMode = false
    private var appLanguage = "zh-CN"
    private var screenOrientation = ScreenOrientation.FOLLOW
    private var statusInsetTop = 0
    private var userEditingAddress = false
    private var errorDetailsOpen = false
    private var pageSettingsHiddenByLandscape = false
    private var pageSettingsPopup: PopupWindow? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var tabSwitcherView: View? = null
    private var appliedBackgroundUri: String? = null
    private var backgroundApplied = false
    private var appliedWebFingerprint: String? = null
    private var lastSectionUrl: String? = null
    private var lastSectionAt = 0L
    private var suggestSequence = 0

    // 快捷方式拖拽状态
    private val shortcutCells = mutableListOf<View>()
    private var shortcutDragActive = false
    private var swipeNavTracking = false
    private var swipeNavTriggered = false
    private var swipeNavStartX = 0f
    private var swipeNavStartY = 0f
    private var shortcutDragDownRawX = 0f
    private var shortcutDragDownRawY = 0f
    private var shortcutDragStartRawX = 0f
    private var shortcutDragStartRawY = 0f
    private var shortcutDragSlop = 0f
    private var shortcutDragIndex = -1
    private var shortcutDragView: View? = null
    private var shortcutDragShortcut: Shortcut? = null
    private var shortcutEditMode = false
    private var shortcutEditDim: View? = null
    private var shortcutEditOriginParent: ViewGroup? = null
    private var shortcutEditOriginIndex = -1
    private var shortcutEditOriginLp: ViewGroup.LayoutParams? = null
    private var shortcutEditBallVisibility = -1
    private var shortcutSlots: Array<IntArray>? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        val dark = ThemeManager.apply(this, themeSettings, false)
        isDarkMode = dark
        screenOrientation = ScreenOrientation.current(this)
        ScreenOrientation.apply(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ThemeManager.applyStatusBarAppearance(this, dark)

        bindViews()
        applyWindowInsets()
        applySoftThemeChrome()
        setupErrorPage()
        setupFindBar()
        initStoresAndTabManager(savedInstanceState)
        initControllers()
        setupAddressAndNavListeners()
        setupSuggestions()
        restoreInitialTabs(savedInstanceState)

        applyShortcutLayoutForOrientation()
        applyHomeLogoSize()
        renderShortcuts()
        applyHomeBackground()
        orientationLandscapeFlag = isLandscapeNow()
        applyIncognitoWindowSecurity()
        updateIncognitoIndicator()
        SearchPreconnect.warmUp(store.selected())
    }

    private fun bindViews() {
        topAddress = findViewById(R.id.top_address)
        homeAddress = findViewById(R.id.home_address)
        topAddress.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        topBar = findViewById(R.id.top_bar)
        homePill = findViewById(R.id.home_pill)
        homeEngineButton = findViewById(R.id.button_home_engine)
        contentColumn = findViewById(R.id.content_column)
        rootView = findViewById(R.id.root_view)
        bottomNav = findViewById(R.id.bottom_nav)
        homeGradient = findViewById(R.id.home_gradient)
        homeContent = findViewById(R.id.home_content)
        pageSettingsButton = findViewById(R.id.button_page_settings)
        homeWeather = findViewById(R.id.home_weather)
        weatherStore = WeatherStore(this)
        incognitoBanner = findViewById(R.id.incognito_banner)
        inprivateCard = findViewById(R.id.inprivate_card)
        shortcutContainer = findViewById(R.id.shortcut_container)
        backArrow = findViewById(R.id.button_back_arrow)
        topReload = findViewById(R.id.button_top_reload)
        topStop = findViewById(R.id.button_top_stop)
        progressBar = findViewById(R.id.progress_bar)
        tabCountView = findViewById(R.id.tab_count)
        errorPage = findViewById(R.id.error_page)
        errorDescView = findViewById(R.id.text_error_desc)
        errorCodeView = findViewById(R.id.text_error_code)
        errorUrlView = findViewById(R.id.text_error_url)
        errorDetailsToggle = findViewById(R.id.error_details_toggle)
        errorDetailsArrow = findViewById(R.id.error_details_arrow)
        webContainer = findViewById(R.id.web_container)
        topOverlay = findViewById(R.id.top_overlay)
        statusBarSpacer = findViewById(R.id.status_bar_spacer)
        topSpacer = findViewById(R.id.top_spacer)
        homeBgImage = findViewById(R.id.home_bg_image)

        findBar = findViewById(R.id.find_bar)
        findInput = findViewById(R.id.find_input)
        findCountView = findViewById(R.id.find_count)

        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                val current = tabManager.currentTab
                val home = current == null || current.url == BLANK_URL
                applyWeatherVisibility(home)
                // 窗口尺寸变了：方向若翻转，按新方向整体重排（图标尺寸、快捷方式、胶囊落位）
                syncOrientationLayout()
            }
        }
        homeWeather?.setOnClickListener { view ->
            WeatherPopup.show(this, view, isDarkMode) { applyWeatherText() }
        }
        webContainer.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                // 可用高度变了：按最新方向整体重排，再落位搜索胶囊
                syncOrientationLayout()
            }
        }
    }

    private fun initStoresAndTabManager(savedInstanceState: Bundle?) {
        store = SearchEngineStore(this)
        interceptor = PrivacyInterceptor.get(this)
        SslWarning.handler = object : SslWarning.Handler {
            override fun onWarning(view: WebView, handler: SslErrorHandler, host: String, errorCode: Int) {
                showSslWarning(view, handler, host, errorCode)
            }
        }
        privacySettings = PrivacySettingsStore(this)
        browserPrefs = BrowserPrefs(this)
        if (savedInstanceState == null && browserPrefs.startInIncognito && !IncognitoMode.isEnabled(this)) {
            IncognitoMode.setEnabled(this, true)
            CookieManager.getInstance().setAcceptCookie(false)
        }
        httpsUpgradePolicy = HttpsUpgradePolicy(privacySettings)
        bookmarkStore = BookmarkStore(this)
        historyStore = HistoryStore(this)
        tabSessionStore = TabSessionStore(this)
        menuCustomizationStore = MenuCustomizationStore(this)
        fontSizeStore = FontSizeStore(this)
        shortcutStore = ShortcutStore(this)
        appLanguage = AppLanguage.current(this)
        shortcutDragSlop = ViewConfiguration.get(this).scaledTouchSlop * 2f
        homeBackgroundStore = HomeBackgroundStore(this)
        downloadStore = DownloadManagerStore.get(this)

        val downloadHandler = DownloadHandler(this, downloadStore) {
            startActivity(
                SectionHostActivity.intentFor(this, 2).apply {
                    putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                    putExtra(BrowserToolbarHelper.EXTRA_SLIDE_FROM, "right")
                }
            )
        }
        sniffHandler = downloadHandler
        installPermissionPrompter()

        tabManager = TabManager(
            context = this,
            container = webContainer,
            requestInterceptor = interceptor,
            scriptProvider = PrivacyScriptProvider { privacySettings.antiFingerprint },
            httpsPolicy = httpsUpgradePolicy,
            jsPolicy = JsPolicyImpl(privacySettings),
            trackingPolicy = TrackingPolicyImpl(privacySettings),
            downloadHandler = downloadHandler,
            blobDownloader = BlobDownloaderImpl(this, downloadStore) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(
                            SectionHostActivity.intentFor(this, 2).apply {
                                putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                            }
                        )
                    }
                }
            },
            upgradeCallback = this,
            mainFrameErrorCallback = { code, _, failingUrl -> showErrorPage(code, failingUrl) },
            onShowCustomView = { view, callback -> showCustomView(view, callback) },
            onHideCustomView = { hideCustomView() },
            onExternalBlocked = { browserPrefs.addExternalBlocked() }
        )
        appliedWebFingerprint = webFingerprint()

        tabManager.listener = object : TabManager.Listener {
            override fun onTabChanged(tab: BrowserTab) {
                hideErrorPage()
                hideFindInPage()
                applyWebViewPrefs(tab.webView)
                syncUi(tab)
            }

            override fun onTabUpdated(tab: BrowserTab) {
                if (tabManager.currentTab === tab) syncUi(tab)
            }

            override fun onPageStarted(tab: BrowserTab) {
                hideErrorPage()
                translateController.onPageStarted(tab.webView)
                applyWebViewPrefs(tab.webView)
            }

            override fun onPageFinished(tab: BrowserTab) {
                if (tab.url != BLANK_URL && tab.url.isNotBlank() && !IncognitoMode.isEnabled(this@MainActivity)) {
                    historyStore.add(tab.title, tab.url)
                }
                if (browserPrefs.doNotTrack && tab.url != BLANK_URL) {
                    tab.webView.evaluateJavascript(
                        "try{Object.defineProperty(navigator,'doNotTrack',{get:()=>'1'});" +
                            "Object.defineProperty(navigator,'globalPrivacyControl',{get:()=>true});}catch(e){}",
                        null
                    )
                }
                DesktopMode.enforceDesktopViewport(tab.webView, this@MainActivity)
                if (tabManager.currentTab === tab) {
                    translateController.maybeAutoTranslate(tab)
                }
            }

            override fun onTabsChanged() {
                tabCountView.text = tabManager.tabCount.toString()
            }
        }
    }

    private fun initControllers() {
        translateController = TranslateController(
            activity = this,
            rootView = rootView,
            ioExecutor = ioExecutor,
            isDarkMode = { isDarkMode },
            currentWebView = { tabManager.currentTab?.webView }
        )
        menuController = MenuController(
            activity = this,
            rootView = rootView,
            menuCustomizationStore = menuCustomizationStore,
            themeSettings = themeSettings,
            isDarkMode = { isDarkMode },
            currentTabUrl = { tabManager.currentTab?.url },
            isCurrentHostWhitelisted = { isCurrentHostWhitelisted() },
            isCurrentUrlBookmarked = { isCurrentUrlBookmarked() },
            onAction = { runMenuAction(it) }
        )
    }

    private fun restoreInitialTabs(savedInstanceState: Bundle?) {
        val deepLink = intent?.data?.toString()
        val restoring = pendingRestoreUrl
        pendingRestoreUrl = null
        if (deepLink != null) {
            tabManager.newTab(deepLink)
            return
        }
        if (restoring != null) {
            tabManager.newTab(restoring)
            return
        }
        if (browserPrefs.openLastPage || savedInstanceState != null) {
            val savedTabs = tabSessionStore.loadTabs()
            if (savedTabs.isNotEmpty()) {
                var activeIdx = 0
                savedTabs.forEachIndexed { idx, item ->
                    tabManager.newTab(item.url)
                    if (item.isCurrent) activeIdx = idx
                }
                tabManager.selectTab(activeIdx)
                return
            }
            val lastUrl = browserPrefs.lastPageUrl
            if (!lastUrl.isNullOrBlank()) {
                tabManager.newTab(lastUrl)
                return
            }
        }
        tabManager.newTab(BLANK_URL)
    }

    private fun applyWebViewPrefs(webView: WebView) {
        val zoom = fontSizeStore.textZoom
        val allowZoom = browserPrefs.zoomEnabled
        webView.settings.apply {
            textZoom = zoom
            setSupportZoom(allowZoom)
            builtInZoomControls = allowZoom
        }
    }

    private fun setupAddressAndNavListeners() {
        topAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                userEditingAddress = true
                SearchPreconnect.warmUp(store.selected())
                val tab = tabManager.currentTab
                if (tab != null && tab.url != BLANK_URL) {
                    topAddress.setText("")
                }
                updateBars()
            }
        }
        topAddress.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                commitAddress()
                true
            } else {
                false
            }
        }
        homePill.setOnClickListener { enterEditMode() }
        homeEngineButton.setOnClickListener { showEnginePicker() }
        backArrow.setOnClickListener { cancelEdit() }
        topReload.setOnClickListener { tabManager.currentTab?.webView?.reload() }
        topStop.setOnClickListener {
            val tab = tabManager.currentTab
            tab?.webView?.stopLoading()
            tab?.progress = 100
            progressBar.visibility = View.GONE
            updateBars()
        }
        findViewById<View>(R.id.button_back).setOnClickListener {
            val webView = tabManager.currentTab?.webView
            if (webView != null && webView.canGoBack()) {
                BackNoReload.prepare(webView.context, webView)
                webView.goBack()
            }
        }
        findViewById<View>(R.id.button_forward).setOnClickListener {
            val webView = tabManager.currentTab?.webView
            if (webView != null && webView.canGoForward()) {
                webView.goForward()
            }
        }
        findViewById<View>(R.id.button_new_tab).apply {
            scaleX = 1.28f
            scaleY = 1.28f
            setOnClickListener { tabManager.newTab(BLANK_URL) }
        }
        findViewById<View>(R.id.button_tab).setOnClickListener { showTabSwitcher() }
        findViewById<View>(R.id.button_menu).setOnClickListener { showMainMenu() }
        findViewById<View>(R.id.button_page_settings).setOnClickListener { showPageSettings() }

        val scanAction = View.OnClickListener {
            startActivityForResult(Intent(this, ScannerActivity::class.java), REQ_SCANNER)
        }
        findViewById<View>(R.id.button_home_scan).setOnClickListener(scanAction)
        findViewById<View>(R.id.button_top_scan).setOnClickListener(scanAction)
    }

    private fun showMainMenu() {
        menuController.showMenu(object : MenuController.HostCallbacks {
            override fun openBookmarks() {
                startActivityForResult(
                    SectionHostActivity.intentFor(this@MainActivity, 0).apply {
                        putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                        putExtra(BrowserToolbarHelper.EXTRA_SLIDE_FROM, "right")
                    },
                    REQ_BOOKMARKS
                )
            }

            override fun openHistory() {
                startActivityForResult(
                    SectionHostActivity.intentFor(this@MainActivity, 1).apply {
                        putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                        putExtra(BrowserToolbarHelper.EXTRA_SLIDE_FROM, "right")
                    },
                    REQ_HISTORY
                )
            }

            override fun openDownloads() {
                startActivity(
                    SectionHostActivity.intentFor(this@MainActivity, 2).apply {
                        putExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, true)
                    }
                )
            }

            override fun reloadCurrentPage() {
                tabManager.currentTab?.webView?.reload()
            }

            override fun openSettings() {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })
    }

    // ==================== 页内查找 ====================

    private fun setupFindBar() {
        val input = findInput ?: return
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val webView = tabManager.currentTab?.webView ?: return
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    webView.clearMatches()
                    findCountView?.text = "0/0"
                } else {
                    webView.findAllAsync(q)
                }
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                tabManager.currentTab?.webView?.findNext(true)
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.button_find_prev)?.setOnClickListener {
            tabManager.currentTab?.webView?.findNext(false)
        }
        findViewById<View>(R.id.button_find_next)?.setOnClickListener {
            tabManager.currentTab?.webView?.findNext(true)
        }
        findViewById<View>(R.id.button_find_close)?.setOnClickListener {
            hideFindInPage()
        }
    }

    fun showFindInPage() {
        val tab = tabManager.currentTab
        if (tab == null || tab.url == BLANK_URL || tab.url.isBlank()) {
            Toast.makeText(this, R.string.find_home, Toast.LENGTH_SHORT).show()
            return
        }
        val bar = findBar ?: return
        bar.setBackgroundResource(if (isDarkMode) R.drawable.top_pill_bg_dark else R.drawable.top_pill_bg)
        bar.visibility = View.VISIBLE
        tab.webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCountView?.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }
        findCountView?.text = "0/0"
        findInput?.apply {
            requestFocus()
            setSelection(text.length)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideFindInPage() {
        val bar = findBar ?: return
        if (bar.visibility != View.VISIBLE) return
        bar.visibility = View.GONE
        findInput?.let { input ->
            input.setText("")
            input.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        tabManager.currentTab?.webView?.apply {
            clearMatches()
            setFindListener(null)
        }
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        val latestOrientation = ScreenOrientation.current(this)
        if (latestOrientation != screenOrientation) {
            screenOrientation = latestOrientation
            ScreenOrientation.apply(this)
        }
        super.onResume()

        if (ThemeManager.isDarkNow(this, themeSettings) != isDarkMode) {
            pendingRestoreUrl = tabManager.currentTab?.url
            recreate()
            return
        }
        if (AppLanguage.current(this) != appLanguage) {
            pendingRestoreUrl = tabManager.currentTab?.url
            recreate()
            return
        }

        applyIncognitoWindowSecurity()
        tabManager.tabsSnapshot().forEach { applyWebViewPrefs(it.webView) }
        updateBars()
        val bgBefore = appliedBackgroundUri
        applyHomeBackground()
        refreshWeatherIfNeeded()
        if (backgroundApplied && appliedBackgroundUri != bgBefore) {
            updateBars()
        }
        cancelPendingAnimations(rootView)
        rootView.invalidate()
        homeContent.invalidate()

        BrowserToolbarHelper.takePendingOpenUrl()?.let { openUrlFromSection(it) }

        val reloadHosts = SiteDataCleaner.takePendingReloadHosts()
        if (reloadHosts.isNotEmpty()) {
            tabManager.tabsSnapshot().forEach { tab ->
                val host = runCatching { Uri.parse(tab.url).host }.getOrNull()
                if (host != null && reloadHosts.contains(host.lowercase(Locale.US))) {
                    tab.webView.reload()
                }
            }
        }

        val fingerprint = webFingerprint()
        if (appliedWebFingerprint != null && appliedWebFingerprint != fingerprint) {
            tabManager.refreshUserAgent()
        }
        appliedWebFingerprint = fingerprint
    }

    private fun installPermissionPrompter() {
        SitePermissions.prompter = SitePermissions.Prompter { key, origin, callback ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    callback.accept(false)
                    return@runOnUiThread
                }
                AegisDialog.Builder(this)
                    .setTitle(getString(R.string.perm_grant_site, hostOrOrigin(origin), sitePermissionName(key)))
                    .setPositiveButton(R.string.perm_allow) { _, _ -> callback.accept(true) }
                    .setNegativeButton(R.string.perm_block) { _, _ -> callback.accept(false) }
                    .setOnCancelListener { callback.accept(false) }
                    .show()
            }
        }
    }

    private fun hostOrOrigin(origin: String): String {
        val host = SitePermissions.hostOf(origin)
        return if (host.isBlank()) getString(R.string.web_settings) else host
    }

    private fun sitePermissionName(key: String): String = when (key) {
        SitePermissions.CLIPBOARD -> getString(R.string.perm_clipboard)
        SitePermissions.OPEN_APP -> getString(R.string.perm_open_app)
        SitePermissions.LOCATION -> getString(R.string.perm_location)
        SitePermissions.CAMERA -> getString(R.string.perm_camera)
        SitePermissions.MIC -> getString(R.string.perm_mic)
        SitePermissions.REDIRECT -> getString(R.string.perm_redirect)
        else -> getString(R.string.perm_vibrate)
    }

    private fun showOrientationPicker() {
        val options = arrayOf(
            ScreenOrientation.FOLLOW,
            ScreenOrientation.AUTO,
            ScreenOrientation.PORTRAIT,
            ScreenOrientation.LANDSCAPE
        )
        val labels = options.map { ScreenOrientation.label(this, it) }.toTypedArray()
        val current = ScreenOrientation.current(this)
        val selected = options.indexOf(current).coerceAtLeast(0)
        AegisDialog.Builder(this)
            .setTitle(R.string.orientation)
            .setSingleChoiceItems(labels, selected) { d, which ->
                d.dismiss()
                ScreenOrientation.set(this, options[which])
                ScreenOrientation.apply(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addToHomeScreen() {
        val tab = tabManager.currentTab
        val url = tab?.url
        if (url.isNullOrBlank() || url == BLANK_URL) {
            Toast.makeText(this, R.string.add_home_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val title = tab.title.ifBlank { displayHost(url) }
        try {
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager == null || !manager.isRequestPinShortcutSupported) {
                Toast.makeText(this, R.string.add_home_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
            val shortcut = ShortcutInfo.Builder(this, "aegis_${abs(url.hashCode())}")
                .setShortLabel(if (title.length > 12) title.substring(0, 12) else title)
                .setLongLabel(title)
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(url))
                )
                .build()
            val ok = manager.requestPinShortcut(shortcut, null)
            Toast.makeText(this, if (ok) R.string.add_home_ok else R.string.add_home_unsupported, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.add_home_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSslWarning(view: WebView, handler: SslErrorHandler, host: String, errorCode: Int) {
        if (isFinishing || isDestroyed) {
            handler.cancel()
            return
        }
        val reason = when (errorCode) {
            SslError.SSL_UNTRUSTED -> getString(R.string.ssl_reason_untrusted)
            SslError.SSL_EXPIRED -> getString(R.string.ssl_reason_expired)
            SslError.SSL_IDMISMATCH -> getString(R.string.ssl_reason_mismatch)
            SslError.SSL_NOTYETVALID -> getString(R.string.ssl_reason_not_yet)
            SslError.SSL_DATE_INVALID -> getString(R.string.ssl_reason_date)
            else -> getString(R.string.ssl_reason_invalid)
        }
        AegisDialog.Builder(this)
            .setTitle(R.string.ssl_warning_title)
            .setMessage(getString(R.string.ssl_warning_msg, host, reason))
            .setPositiveButton(R.string.ssl_go_back) { _, _ ->
                handler.cancel()
                view.stopLoading()
                if (view.canGoBack()) {
                    BackNoReload.prepare(view.context, view)
                    view.goBack()
                } else {
                    goHome()
                }
            }
            .setNegativeButton(R.string.ssl_continue) { _, _ -> handler.proceed() }
            .setCancelable(false)
            .show()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (shortcutEditMode && event.actionMasked == MotionEvent.ACTION_DOWN && !shortcutDragActive) {
            if (shortcutContainer.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                shortcutContainer.getLocationOnScreen(loc)
                val x = event.rawX
                val y = event.rawY
                val inside = x >= loc[0] && x <= loc[0] + shortcutContainer.width &&
                    y >= loc[1] && y <= loc[1] + shortcutContainer.height
                if (!inside) exitShortcutEdit()
            }
        }
        handleEdgeSwipeNav(event)
        return super.dispatchTouchEvent(event)
    }

    private fun handleEdgeSwipeNav(event: MotionEvent) {
        if (!browserPrefs.swipeNavEnabled) return
        val tab = tabManager.currentTab ?: return
        val onWeb = tab.url != BLANK_URL && tab.url.isNotBlank() && !userEditingAddress &&
            customView == null && menuController.mainMenuView == null &&
            menuController.allMenuView == null && tabSwitcherView == null
        if (!onWeb) {
            swipeNavTracking = false
            return
        }
        val edgeZone = dpSize(28).toFloat()
        val threshold = dpSize(68).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val w = rootView.width.toFloat()
                val x = event.rawX
                swipeNavTriggered = false
                swipeNavTracking = x <= edgeZone || (w > 0f && x >= w - edgeZone)
                swipeNavStartX = x
                swipeNavStartY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeNavTracking || swipeNavTriggered) return
                val dx = event.rawX - swipeNavStartX
                val dy = event.rawY - swipeNavStartY
                if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f) {
                    val w = rootView.width.toFloat()
                    if (swipeNavStartX <= edgeZone && dx > 0f && tab.webView.canGoBack()) {
                        swipeNavTriggered = true
                        Haptics.tap(webContainer)
                        BackNoReload.prepare(this, tab.webView)
                        tab.webView.goBack()
                    } else if (w > 0f && swipeNavStartX >= w - edgeZone && dx < 0f && tab.webView.canGoForward()) {
                        swipeNavTriggered = true
                        Haptics.tap(webContainer)
                        tab.webView.goForward()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeNavTracking = false
                swipeNavTriggered = false
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val tab = tabManager.currentTab
        val onWeb = tab != null && tab.url != BLANK_URL && tab.url.isNotBlank() &&
            !userEditingAddress && customView == null &&
            menuController.mainMenuView == null && menuController.allMenuView == null && tabSwitcherView == null

        if (browserPrefs.volumeKeyPageTurn && onWeb && tab != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    tab.webView.evaluateJavascript(
                        "window.scrollBy({top: Math.round(window.innerHeight * 0.85), behavior: 'smooth'})",
                        null
                    )
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    tab.webView.evaluateJavascript(
                        "window.scrollBy({top: -Math.round(window.innerHeight * 0.85), behavior: 'smooth'})",
                        null
                    )
                    return true
                }
            }
        }

        if (browserPrefs.keyboardShortcuts) {
            if (event.isCtrlPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_T -> {
                        tabManager.newTab(BLANK_URL)
                        return true
                    }
                    KeyEvent.KEYCODE_W -> {
                        if (tabManager.currentTabIndex >= 0) tabManager.closeTab(tabManager.currentTabIndex)
                        return true
                    }
                    KeyEvent.KEYCODE_R -> {
                        tabManager.currentTab?.webView?.reload()
                        return true
                    }
                    KeyEvent.KEYCODE_F -> {
                        if (onWeb) showFindInPage()
                        return true
                    }
                    KeyEvent.KEYCODE_L -> {
                        if (tab == null || tab.url == BLANK_URL) enterEditMode()
                        else {
                            userEditingAddress = true
                            updateBars()
                            topAddress.requestFocus()
                            topAddress.selectAll()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_N -> {
                        if (event.isShiftPressed) toggleIncognito() else tabManager.newTab(BLANK_URL)
                        return true
                    }
                }
            }
            if (event.isAltPressed) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        tab?.takeIf { it.webView.canGoBack() }?.let {
                            BackNoReload.prepare(this, it.webView)
                            it.webView.goBack()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        tab?.takeIf { it.webView.canGoForward() }?.webView?.goForward()
                        return true
                    }
                }
            }
            if (keyCode == KeyEvent.KEYCODE_F5) {
                tab?.webView?.reload()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shortcutEditMode) {
            exitShortcutEdit()
            return
        }
        if (findBar?.visibility == View.VISIBLE) {
            hideFindInPage()
            return
        }
        if (customView != null) {
            hideCustomView()
            return
        }
        if (menuController.allMenuView != null) {
            menuController.dismissAllMenu()
            return
        }
        if (menuController.mainMenuView != null) {
            menuController.dismissMainMenu()
            return
        }
        pageSettingsPopup?.takeIf { it.isShowing }?.let {
            it.dismiss()
            pageSettingsPopup = null
            return
        }
        if (tabSwitcherView != null) {
            dismissTabSwitcher()
            return
        }
        if (userEditingAddress) {
            cancelEdit()
            return
        }
        val tab = tabManager.currentTab
        if (tab != null) {
            val url = tab.url
            if (url != BLANK_URL && url.isNotBlank()) {
                if (tab.webView.canGoBack()) {
                    BackNoReload.prepare(this, tab.webView)
                    tab.webView.goBack()
                } else {
                    goHome()
                }
                return
            }
        }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        WeatherPopup.dismiss()
        pageSettingsPopup?.takeIf { it.isShowing }?.dismiss()
        pageSettingsPopup = null
        menuController.dismissMainMenu()
        menuController.dismissAllMenu()

        val tab = tabManager.currentTab
        if (tab != null && tab.url != BLANK_URL && tab.url.isNotBlank() && !IncognitoMode.isEnabled(this)) {
            browserPrefs.lastPageUrl = tab.url
        } else {
            // 无痕模式不记录最后访问地址，避免退出后被落盘
            browserPrefs.lastPageUrl = null
        }

        if (!IncognitoMode.isEnabled(this)) {
            val snapshot = tabManager.tabsSnapshot()
            val curIdx = snapshot.indexOf(tab).coerceAtLeast(0)
            tabSessionStore.saveTabs(snapshot.map { it.url to it.title }, curIdx)
        } else {
            tabSessionStore.clear()
        }
    }

    fun cancelPendingAnimations(view: View) {
        view.animate().cancel()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                cancelPendingAnimations(view.getChildAt(i))
            }
        }
    }

    fun webFingerprint(): String =
        "${UserAgentMode.get(this)}|${DesktopMode.isPcToMobile(this)}"

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pickedUrl = intent.getStringExtra(BrowserToolbarHelper.EXTRA_OPEN_URL)
        if (!pickedUrl.isNullOrBlank()) {
            openUrlFromSection(pickedUrl)
            return
        }
        intent.data?.toString()?.let { tabManager.newTab(it) }
    }

    fun enterEditMode() {
        topAddress.setText(homeAddress.text)
        userEditingAddress = true
        SearchPreconnect.warmUp(store.selected())
        updateBars()
        topBar.post {
            if (userEditingAddress) {
                topBar.visibility = View.VISIBLE
                topBar.requestLayout()
                topBar.invalidate()
                topAddress.requestFocus()
                topAddress.setSelection(topAddress.text.length)
                showKeyboard()
            }
        }
    }

    fun cancelEdit() {
        hideSuggestions()
        hideKeyboard()
        browserPrefs.lastPageUrl = null
        topAddress.clearFocus()
        val tab = tabManager.currentTab
        val isHome = tab == null || tab.url == BLANK_URL
        if (isHome) {
            topAddress.setText("")
            homeAddress.text = ""
        } else {
            topAddress.setText(displayHost(tab?.url.orEmpty()))
        }
        userEditingAddress = false
        updateBars()
    }

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        hideKeyboard()
        rootView.addView(view, FrameLayout.LayoutParams(-1, -1))
        contentColumn.visibility = View.GONE
        topOverlay.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enterImmersive()
    }

    fun hideCustomView() {
        val view = customView ?: return
        rootView.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        contentColumn.visibility = View.VISIBLE
        topOverlay.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        exitImmersive()
    }

    fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894
    }

    fun exitImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        ThemeManager.applyStatusBarAppearance(this, isDarkMode)
    }

    fun applyTopSpacer() {
        val tab = tabManager.currentTab
        val isHome = tab == null || tab.url == BLANK_URL
        val onHome = isHome && !userEditingAddress
        val bannerH = if (!onHome && incognitoBanner.visibility == View.VISIBLE) {
            if (incognitoBanner.height > 0) incognitoBanner.height + dpSize(6) else dpSize(46)
        } else 0
        val target = if (onHome) statusInsetTop else statusInsetTop + dpSize(52) + bannerH
        val lp = topSpacer.layoutParams
        if (lp.height != target) {
            lp.height = target
            topSpacer.layoutParams = lp
        }
    }

    fun updateBars() {
        ensureStatusInset()
        val tab = tabManager.currentTab
        val isHome = tab == null || tab.url == BLANK_URL
        val onHome = isHome && !userEditingAddress
        val wasTopBarVisible = topBar.visibility == View.VISIBLE
        bottomNav.visibility = if (userEditingAddress) View.GONE else View.VISIBLE

        if (onHome) {
            homeContent.visibility = View.VISIBLE
            pageSettingsButton.visibility = View.VISIBLE
            topBar.visibility = View.INVISIBLE
        } else {
            homeContent.visibility = View.GONE
            pageSettingsButton.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            backArrow.visibility = if (userEditingAddress) View.VISIBLE else View.GONE
            val isLoading = tab != null && tab.progress in 1..99
            val showAction = !userEditingAddress && !isHome
            topReload.visibility = if (showAction && !isLoading) View.VISIBLE else View.GONE
            topStop.visibility = if (showAction && isLoading) View.VISIBLE else View.GONE
            topAddress.gravity = if (userEditingAddress) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            if (!wasTopBarVisible) {
                topBar.post {
                    topBar.requestLayout()
                    topBar.invalidate()
                    topOverlay.requestLayout()
                }
            }
        }
        applyTopSpacer()
        val incognito = IncognitoMode.isEnabled(this)
        val effectiveDark = isDarkMode || incognito
        homeGradient.visibility = if (onHome) View.VISIBLE else View.GONE
        homeBgImage.visibility = if (onHome && !incognito && homeBackgroundStore.hasCustomBackground()) View.VISIBLE else View.GONE
        if (incognito) {
            homeGradient.setBackgroundColor(0xFF1A1C20.toInt())
        } else {
            homeGradient.setBackgroundResource(if (isDarkMode) R.drawable.home_bg_gradient_dark else R.drawable.home_bg_gradient)
        }

        val chromeColor = when {
            incognito -> 0xFF202329.toInt()
            isDarkMode -> getColor(R.color.bg_dark)
            else -> getColor(R.color.header_light)
        }
        if (onHome) {
            topOverlay.background = null
            topOverlay.elevation = 0f
            bottomNav.background = null
        } else {
            topOverlay.setBackgroundColor(chromeColor)
            topOverlay.elevation = dpSize(3).toFloat()
            bottomNav.setBackgroundColor(chromeColor)
        }
        tab?.webView?.setBackgroundColor(
            if (onHome) Color.TRANSPARENT else if (effectiveDark) getColor(R.color.bg_dark) else Color.WHITE
        )
        if (effectiveDark) {
            homePill.setBackgroundResource(R.drawable.search_pill_bg_dark)
            if (!onHome) topBar.setBackgroundResource(R.drawable.top_pill_bg_dark)
        } else {
            homePill.setBackgroundResource(R.drawable.search_pill_bg)
            if (!onHome) topBar.setBackgroundResource(R.drawable.top_pill_bg)
        }
        homeAddress.setTextColor(if (effectiveDark) 0xFFE8EAED.toInt() else 0xFF1F2328.toInt())
        homeAddress.setHintTextColor(if (effectiveDark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt())
        tintChromeIcons(effectiveDark)
        if (customView == null) {
            ThemeManager.applyStatusBarAppearance(this, effectiveDark)
        }
        if (!incognito) applySoftThemeChrome(onHome)
        applyWeatherVisibility(onHome && !incognito)
        updateIncognitoIndicator()
        tintProgressBar()
        if (onHome) {
            homeContent.post { applyHomePillAnchor() }
        }
    }

    private fun tintChromeIcons(effectiveDark: Boolean) {
        val primaryColor = if (effectiveDark) 0xFFE8EAED.toInt() else 0xFF1F2328.toInt()
        val secondaryColor = if (effectiveDark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        topAddress.setTextColor(primaryColor)
        topAddress.setHintTextColor(secondaryColor)
        findInput?.setTextColor(primaryColor)
        findInput?.setHintTextColor(secondaryColor)
        findCountView?.setTextColor(secondaryColor)
        findBar?.setBackgroundResource(if (effectiveDark) R.drawable.top_pill_bg_dark else R.drawable.top_pill_bg)
        tabCountView.setTextColor(primaryColor)
        tabCountView.setBackgroundResource(if (effectiveDark) R.drawable.tab_count_bg_dark else R.drawable.tab_count_bg)

        val primaryIds = intArrayOf(R.id.button_back, R.id.button_forward, R.id.button_new_tab, R.id.button_menu)
        for (id in primaryIds) {
            (findViewById<View>(id) as? ImageView)?.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN)
        }
        val secondaryIds = intArrayOf(
            R.id.button_home_engine, R.id.button_home_scan, R.id.button_page_settings,
            R.id.button_back_arrow, R.id.button_top_reload, R.id.button_top_stop, R.id.button_top_scan,
            R.id.button_find_prev, R.id.button_find_next, R.id.button_find_close
        )
        for (id in secondaryIds) {
            (findViewById<View>(id) as? ImageView)?.setColorFilter(secondaryColor, PorterDuff.Mode.SRC_IN)
        }
    }

    fun applyHomePillAnchor() {
        val h = webContainer.height
        val pillH = homePill.height
        if (h <= 0 || pillH <= 0) return
        val landscape = isLandscapeNow()
        // 横屏整体贴顶紧凑排列：小号 A 图标 → 搜索胶囊 → 快捷方式，全部落在可视区内；
        // 竖屏保持原来的偏下居中布局
        var target = if (landscape) 0f else (h - pillH) / 2f + dpSize(110)
        val homeLogo = findViewById<ImageView>(R.id.home_logo)
        if (!landscape) {
            if (homeLogo != null && homeLogo.visibility == View.VISIBLE) {
                target -= homeLogo.height + dpSize(30)
            } else if (inprivateCard?.visibility == View.VISIBLE) {
                val cardH = inprivateCard?.height?.takeIf { it > 0 } ?: dpSize(96)
                target -= cardH + dpSize(16)
            }
        }
        homeContent.translationY = target.coerceIn(0f, (h - pillH).coerceAtLeast(0).toFloat())
        if (landscape) {
            if (pageSettingsButton.visibility == View.VISIBLE) {
                pageSettingsHiddenByLandscape = true
                pageSettingsButton.visibility = View.GONE
            }
        } else if (pageSettingsHiddenByLandscape && homeContent.visibility == View.VISIBLE) {
            pageSettingsHiddenByLandscape = false
            pageSettingsButton.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 快捷方式编辑态是脱离原容器的浮层，转屏必须先退出，否则位置与尺寸会错乱
        if (shortcutEditMode) exitShortcutEdit()
        hideSuggestions()
        menuController.onConfigurationChanged(newConfig)
        // 转屏瞬间窗口尺寸往往还没更新，先排一次，等新尺寸就绪后再强制重排一次
        syncOrientationLayout(force = true)
        rootView.postDelayed({ syncOrientationLayout(force = true) }, 120L)
        rootView.postDelayed({ syncOrientationLayout(force = true) }, 360L)
    }

    /**
     * 主页的方向相关排版统一入口：A 图标尺寸、图标显隐、快捷方式容器与网格列数、
     * 搜索胶囊落位，全部按当前真实窗口方向重算，避免只改一半造成错位。
     */
    private fun syncOrientationLayout(force: Boolean = false) {
        val landscape = isLandscapeNow()
        val landscapeChanged = orientationLandscapeFlag != landscape
        if (!force && !landscapeChanged) {
            // 方向没变时只校正胶囊落位，避免每次尺寸变化都重建快捷方式网格
            applyHomePillAnchor()
            return
        }
        orientationLandscapeFlag = landscape
        applyHomeLogoSize()
        applyHomeBackground()
        applyShortcutLayoutForOrientation()
        renderShortcuts()
        val tab = tabManager.currentTab
        applyWeatherVisibility(tab == null || tab.url == BLANK_URL || tab.url.isBlank())
        applyHomePillAnchor()
    }

    /** 横屏压缩 A 图标尺寸与上下留白，把竖向空间让给搜索胶囊和快捷方式；竖屏复原 */
    private fun applyHomeLogoSize() {
        val logo = findViewById<ImageView>(R.id.home_logo) ?: return
        val landscape = isLandscapeNow()
        val size = if (landscape) dpSize(84) else dpSize(150)
        val lp = logo.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.width != size || lp.height != size) {
            lp.width = size
            lp.height = size
        }
        lp.topMargin = if (landscape) dpSize(2) else dpSize(4)
        lp.bottomMargin = if (landscape) dpSize(4) else dpSize(10)
        logo.layoutParams = lp
    }

    // ==================== 快捷方式 ====================

    /**
     * 横屏时列数跟随窗口宽度自适应，避免固定 4 列被拉成稀疏的大格子；
     * 竖屏保持原来的 4 列。
     */
    private fun shortcutColumns(): Int {
        if (!isLandscapeNow()) return SHORTCUTS_PER_ROW
        val usable = minOf(windowWidthPx() - dpSize(32), dpSize(680))
        return (usable / dpSize(88)).coerceIn(6, 8)
    }

    /** 实际使用的列数：横屏按格子数量收窄，避免少量图标被摊到大半屏显得稀疏 */
    private fun shortcutColumnsFor(cellCount: Int): Int {
        val max = shortcutColumns()
        return if (cellCount <= 0) max else minOf(cellCount, max)
    }

    /** 取真实窗口宽度，避开转屏时 displayMetrics 尚未刷新的滞后 */
    private fun windowWidthPx(): Int {
        val w = rootView.width
        return if (w > 0) w else resources.displayMetrics.widthPixels
    }

    /**
     * 横屏下快捷方式容器按实际列数收窄并压缩上边距，
     * 保证小号 A 图标 + 搜索胶囊 + 快捷方式完整落在可视区内；竖屏复原。
     */
    private fun applyShortcutLayoutForOrientation() {
        val lp = shortcutContainer.layoutParams as? LinearLayout.LayoutParams ?: return
        if (isLandscapeNow()) {
            val shortcuts = shortcutStore.all()
            val cellCount = shortcuts.size + if (shortcuts.size < SHORTCUT_MAX) 1 else 0
            val columns = shortcutColumnsFor(cellCount)
            lp.width = minOf(columns * dpSize(88), windowWidthPx() - dpSize(32), dpSize(680))
            lp.topMargin = dpSize(10)
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.topMargin = dpSize(24)
        }
        shortcutContainer.layoutParams = lp
    }

    fun renderShortcuts() {
        shortcutContainer.removeAllViews()
        shortcutCells.clear()
        shortcutSlots = null
        if (!shortcutStore.isShown()) {
            shortcutContainer.visibility = View.GONE
            return
        }
        val shortcuts = shortcutStore.all()
        val canAdd = shortcuts.size < SHORTCUT_MAX
        val cellCount = shortcuts.size + if (canAdd) 1 else 0
        if (cellCount == 0) {
            shortcutContainer.visibility = View.GONE
            return
        }
        shortcutContainer.visibility = View.VISIBLE
        val perRow = shortcutColumnsFor(cellCount)
        val landscape = isLandscapeNow()
        var rowLayout: LinearLayout? = null
        for (index in 0 until cellCount) {
            if (index % perRow == 0) {
                rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                }
                shortcutContainer.addView(rowLayout)
            }
            val isAdd = index == shortcuts.size
            val item = layoutInflater.inflate(R.layout.item_shortcut, rowLayout, false)
            // 横屏竖向空间紧张：压缩格子上下留白与标题间距，保证两行也能完整显示
            if (landscape) {
                item.setPadding(dpSize(4), dpSize(2), dpSize(4), dpSize(2))
                item.findViewById<TextView>(R.id.shortcut_title).apply {
                    val tlp = layoutParams as? LinearLayout.LayoutParams
                    if (tlp != null) {
                        tlp.topMargin = dpSize(4)
                        layoutParams = tlp
                    }
                }
            }
            val card = item.findViewById<View>(R.id.shortcut_card)
            card.clipToOutline = true
            val icon = item.findViewById<ImageView>(R.id.shortcut_icon)
            val initial = item.findViewById<TextView>(R.id.shortcut_initial)
            val add = item.findViewById<ImageView>(R.id.shortcut_add)
            val title = item.findViewById<TextView>(R.id.shortcut_title)

            val effectiveDark = isDarkMode || IncognitoMode.isEnabled(this)
            title.setTextColor(if (effectiveDark) 0xFFE8EAED.toInt() else 0xFF1F2328.toInt())
            if (isAdd) {
                card.setBackgroundResource(if (effectiveDark) R.drawable.shortcut_add_bg_dark else R.drawable.shortcut_add_bg)
                add.setColorFilter(if (effectiveDark) 0xFFE8EAED.toInt() else 0xFF5F6368.toInt(), PorterDuff.Mode.SRC_IN)
                icon.visibility = View.GONE
                initial.visibility = View.GONE
                add.visibility = View.VISIBLE
                add.setImageResource(R.drawable.ic_add_rounded)
                (add.layoutParams as FrameLayout.LayoutParams).apply {
                    width = dpSize(24)
                    height = dpSize(24)
                    add.layoutParams = this
                }
                title.text = getString(R.string.add)
                item.setOnClickListener { showAddShortcut() }
            } else {
                val shortcut = shortcuts[index]
                card.setBackgroundResource(if (effectiveDark) R.drawable.shortcut_card_bg_dark else R.drawable.shortcut_card_bg)
                initial.setTextColor(FaviconLoader.colorFor(shortcut.title))
                add.visibility = View.GONE
                title.text = shortcut.title
                FaviconLoader.load(icon, initial, shortcut.url, shortcut.title, this)
                val delete = item.findViewById<ImageView>(R.id.shortcut_delete)
                delete.visibility = if (shortcutEditMode) View.VISIBLE else View.GONE
                delete.setOnClickListener {
                    shortcutStore.remove(shortcut.url)
                    renderShortcuts()
                }
                item.setOnClickListener {
                    if (shortcutEditMode) showShortcutActions(shortcut) else openShortcut(shortcut.url)
                }
                item.setOnTouchListener { v, e -> onShortcutTouch(v, e) }
                item.setOnLongClickListener {
                    onShortcutLongPress(item, index, shortcut)
                    true
                }
                shortcutCells.add(item)
            }
            rowLayout?.addView(item)
        }
        val remainder = cellCount % perRow
        if (remainder != 0) {
            repeat(perRow - remainder) {
                rowLayout?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
    }

    fun showPageSettings() {
        val content = layoutInflater.inflate(R.layout.view_page_settings, null)
        val cardBg = if (isDarkMode || ThemeManager.isDarkNow(this, themeSettings))
            R.drawable.dialog_card_bg_dark else R.drawable.dialog_card_bg
        val shortcutSwitch = content.findViewById<Switch>(R.id.switch_shortcuts)
        shortcutSwitch.isChecked = shortcutStore.isShown()
        shortcutSwitch.setOnCheckedChangeListener { _, isChecked ->
            shortcutStore.setShown(isChecked)
            renderShortcuts()
        }
        val weatherSwitch = content.findViewById<Switch>(R.id.switch_weather)
        val ws = weatherStore
        if (weatherSwitch != null && ws != null) {
            weatherSwitch.isChecked = ws.shown
            weatherSwitch.setOnCheckedChangeListener { _, checked ->
                ws.shown = checked
                updateBars()
                if (checked) refreshWeatherIfNeeded()
            }
        }
        content.findViewById<TextView>(R.id.text_theme_value).text = themeValueLabel()
        content.findViewById<View>(R.id.row_theme).setOnClickListener {
            startActivity(Intent(this, AppThemeActivity::class.java))
        }
        content.findViewById<View>(R.id.row_background).setOnClickListener {
            startActivity(Intent(this, WallpaperActivity::class.java))
        }
        val resetRow = content.findViewById<View>(R.id.row_reset_background)
        if (homeBackgroundStore.hasCustomBackground()) {
            resetRow.visibility = View.VISIBLE
            resetRow.setOnClickListener {
                homeBackgroundStore.setBackground(null)
                applyHomeBackground()
                Toast.makeText(this, R.string.reset_background, Toast.LENGTH_SHORT).show()
            }
        }
        val width = (resources.displayMetrics.widthPixels * 0.70).toInt()
        val popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pageSettingsPopup?.takeIf { it.isShowing }?.dismiss()
        pageSettingsPopup = popup
        popup.animationStyle = R.style.SettingsPopupAnimation
        popup.setBackgroundDrawable(getDrawable(cardBg))
        popup.isOutsideTouchable = true
        popup.elevation = dpSize(10).toFloat()
        val anchorLoc = IntArray(2)
        pageSettingsButton.getLocationOnScreen(anchorLoc)
        popup.showAtLocation(
            window.decorView,
            Gravity.TOP or Gravity.START,
            resources.displayMetrics.widthPixels - width - dpSize(10),
            anchorLoc[1] + pageSettingsButton.height
        )
    }

    fun isLandscapeNow(): Boolean {
        // 以真实窗口尺寸为准：部分机型转屏时 resources 的配置与尺寸更新滞后，
        // 只看 configuration 会把横屏当竖屏（或反之）算出错误布局
        val w = rootView.width
        val h = rootView.height
        if (w > 0 && h > 0 && w != h) {
            return w > h
        }
        val orientation = resources.configuration.orientation
        if (orientation != Configuration.ORIENTATION_UNDEFINED) {
            return orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return false
    }

    fun applyWeatherVisibility(onHome: Boolean) {
        val view = homeWeather ?: return
        val shown = weatherStore?.shown == true
        view.visibility = if (onHome && shown && !isLandscapeNow()) View.VISIBLE else View.GONE
    }

    fun applyWeatherText() {
        val view = homeWeather ?: return
        val ws = weatherStore ?: return
        if (isLandscapeNow()) view.visibility = View.GONE
        view.text = if (ws.hasData()) ws.summary(this) else ""
    }

    fun refreshWeatherIfNeeded() {
        val ws = weatherStore ?: return
        if (!ws.shown) return
        applyWeatherText()
        if (!ws.needsRefresh(false)) return
        WeatherFetcher.refresh(this, false) { applyWeatherText() }
    }

    fun showAddShortcut() {
        if (shortcutStore.all().size >= SHORTCUT_MAX) {
            Toast.makeText(this, R.string.shortcut_limit, Toast.LENGTH_SHORT).show()
        } else {
            showShortcutEditor(false, null)
        }
    }

    fun onShortcutLongPress(item: View, index: Int, shortcut: Shortcut) {
        if (!shortcutEditMode) enterShortcutEdit() else startShortcutDrag(item, index, shortcut)
    }

    fun enterShortcutEdit() {
        shortcutEditMode = true
        translateController.translateBallView?.let { ball ->
            shortcutEditBallVisibility = ball.visibility
            ball.animate().alpha(0f).setDuration(120L).start()
        }
        setHomeEditBlur(true)
        renderShortcuts()
    }

    fun exitShortcutEdit() {
        if (!shortcutEditMode) return
        shortcutEditMode = false
        shortcutDragActive = false
        shortcutDragView = null
        shortcutDragShortcut = null
        translateController.translateBallView?.let { ball ->
            if (shortcutEditBallVisibility >= 0) {
                ball.visibility = shortcutEditBallVisibility
                ball.animate().alpha(1f).setDuration(120L).start()
                if (Build.VERSION.SDK_INT >= 31) ball.setRenderEffect(null)
            }
        }
        shortcutEditBallVisibility = -1
        setHomeEditBlur(false)
        renderShortcuts()
    }

    private fun setHomeEditBlur(on: Boolean) {
        if (on) {
            val origin = shortcutContainer.parent as? ViewGroup ?: return
            shortcutEditOriginParent = origin
            shortcutEditOriginIndex = origin.indexOfChild(shortcutContainer)
            shortcutEditOriginLp = shortcutContainer.layoutParams
            val loc = IntArray(2)
            val rootLoc = IntArray(2)
            shortcutContainer.getLocationOnScreen(loc)
            rootView.getLocationOnScreen(rootLoc)
            origin.removeView(shortcutContainer)

            val dim = View(this).apply {
                tag = "aegis_edit_dim"
                setBackgroundColor(0x59000000)
                setOnClickListener { exitShortcutEdit() }
            }
            rootView.addView(dim, FrameLayout.LayoutParams(-1, -1))
            shortcutEditDim = dim

            val lp = FrameLayout.LayoutParams(shortcutContainer.width, shortcutContainer.height).apply {
                leftMargin = loc[0] - rootLoc[0]
                topMargin = loc[1] - rootLoc[1]
            }
            rootView.addView(shortcutContainer, lp)
            for (i in 0 until rootView.childCount) {
                val child = rootView.getChildAt(i)
                if (child !== shortcutContainer && child !== dim) applyBlurView(child, true)
            }
        } else {
            for (i in 0 until rootView.childCount) {
                val child = rootView.getChildAt(i)
                if (child !== shortcutContainer && child !== shortcutEditDim) applyBlurView(child, false)
            }
            for (i in rootView.childCount - 1 downTo 0) {
                val child = rootView.getChildAt(i)
                if (child.tag == "aegis_edit_dim") rootView.removeView(child)
            }
            shortcutEditDim = null
            shortcutEditOriginParent?.let { origin ->
                rootView.removeView(shortcutContainer)
                val idx = shortcutEditOriginIndex.coerceIn(0, origin.childCount)
                val lp = shortcutEditOriginLp
                if (lp != null) origin.addView(shortcutContainer, idx, lp) else origin.addView(shortcutContainer, idx)
            }
            shortcutEditOriginParent = null
            shortcutEditOriginLp = null
            shortcutEditOriginIndex = -1
        }
    }

    private fun applyBlurView(view: View?, on: Boolean) {
        if (view == null) return
        if (Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(
                if (on) RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP) else null
            )
        } else {
            view.animate().alpha(if (on) 0.25f else 1f).setDuration(160L).start()
        }
    }

    fun startShortcutDrag(view: View, index: Int, shortcut: Shortcut) {
        if (shortcutDragActive) return
        shortcutDragActive = true
        shortcutDragView = view
        shortcutDragIndex = index
        shortcutDragShortcut = shortcut
        shortcutDragStartRawX = shortcutDragDownRawX
        shortcutDragStartRawY = shortcutDragDownRawY
        ensureShortcutSlots()
        view.elevation = dpSize(10).toFloat()
        view.scaleX = 1.06f
        view.scaleY = 1.06f
        var p = view.parent
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            if (p === shortcutContainer) break
            p = p.parent
        }
    }

    fun onShortcutTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shortcutDragDownRawX = event.rawX
                shortcutDragDownRawY = event.rawY
                shortcutDragActive = false
                shortcutDragView = null
                shortcutDragShortcut = null
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!shortcutDragActive) return false
                shortcutDragActive = false
                val dx = event.rawX - shortcutDragStartRawX
                val dy = event.rawY - shortcutDragStartRawY
                val moved = abs(dx) + abs(dy) > shortcutDragSlop
                val dragged = shortcutDragView
                shortcutDragView = null
                shortcutDragShortcut = null
                if (moved) {
                    shortcutSlots = null
                    renderShortcuts()
                    return true
                }
                dragged?.let {
                    it.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(140L).start()
                    it.elevation = 0f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!shortcutDragActive) return false
                view.translationX = event.rawX - shortcutDragStartRawX
                view.translationY = event.rawY - shortcutDragStartRawY
                val hover = shortcutIndexAt(event.rawX, event.rawY)
                if (hover != null && hover != shortcutDragIndex) {
                    val from = shortcutDragIndex
                    shortcutStore.move(from, hover)
                    reflowShortcutCells(from, hover)
                    shortcutDragIndex = hover
                }
                return true
            }
        }
        return false
    }

    private fun reflowShortcutCells(from: Int, to: Int) {
        if (from == to || from !in shortcutCells.indices || to !in shortcutCells.indices) return
        ensureShortcutSlots()
        val slots = shortcutSlots ?: return
        val moved = shortcutCells.removeAt(from)
        shortcutCells.add(to, moved)
        val dragged = shortcutDragView
        for (k in shortcutCells.indices) {
            val cell = shortcutCells[k]
            if (cell === dragged) continue
            val physical = cell.tag as? Int ?: continue
            if (physical >= slots.size || k >= slots.size) continue
            val tx = (slots[k][0] - slots[physical][0]).toFloat()
            val ty = (slots[k][1] - slots[physical][1]).toFloat()
            cell.animate().translationX(tx).translationY(ty).setDuration(120L).start()
        }
    }

    private fun ensureShortcutSlots() {
        if (shortcutSlots?.size == shortcutCells.size) return
        shortcutSlots = Array(shortcutCells.size) { i ->
            val cell = shortcutCells[i]
            cell.tag = i
            IntArray(2).also { cell.getLocationOnScreen(it) }
        }
    }

    fun shortcutIndexAt(rawX: Float, rawY: Float): Int? {
        ensureShortcutSlots()
        val slots = shortcutSlots
        if (slots != null && slots.size == shortcutCells.size) {
            for (i in shortcutCells.indices) {
                val cell = shortcutCells[i]
                val loc = slots[i]
                if (rawX >= loc[0] && rawX <= loc[0] + cell.width &&
                    rawY >= loc[1] && rawY <= loc[1] + cell.height
                ) return i
            }
            return null
        }
        for (i in shortcutCells.indices) {
            val cell = shortcutCells[i]
            val loc = IntArray(2)
            cell.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + cell.width &&
                rawY >= loc[1] && rawY <= loc[1] + cell.height
            ) return i
        }
        return null
    }

    fun showShortcutActions(shortcut: Shortcut) {
        val content = layoutInflater.inflate(R.layout.dialog_shortcut_actions, null)
        content.setBackgroundResource(if (isDarkMode) R.drawable.dialog_card_bg_dark else R.drawable.dialog_card_bg)
        content.findViewById<TextView>(R.id.shortcut_action_title).text = shortcut.title
        val dialog = Dialog(this).apply {
            requestWindowFeature(1)
            setContentView(content)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(0))
                setLayout((resources.displayMetrics.widthPixels * 0.72).toInt(), -2)
            }
        }
        content.findViewById<View>(R.id.row_edit_shortcut).setOnClickListener {
            dialog.dismiss()
            showShortcutEditor(true, shortcut)
        }
        content.findViewById<View>(R.id.row_delete_shortcut).setOnClickListener {
            dialog.dismiss()
            shortcutStore.remove(shortcut.url)
            renderShortcuts()
        }
        dialog.show()
    }

    fun showShortcutEditor(isEdit: Boolean, shortcut: Shortcut?) {
        val view = layoutInflater.inflate(R.layout.dialog_add_shortcut, null)
        view.setBackgroundResource(if (isDarkMode) R.drawable.dialog_card_bg_dark else R.drawable.dialog_card_bg)
        val nameInput = view.findViewById<EditText>(R.id.et_shortcut_name)
        val urlInput = view.findViewById<EditText>(R.id.et_shortcut_url)
        val fieldBg = if (isDarkMode) R.drawable.input_field_bg_dark else R.drawable.input_field_bg
        nameInput.setBackgroundResource(fieldBg)
        urlInput.setBackgroundResource(fieldBg)
        view.findViewById<TextView>(R.id.dialog_shortcut_title).setText(
            if (isEdit) R.string.shortcut_edit_title else R.string.add_shortcut
        )
        if (isEdit && shortcut != null) {
            nameInput.setText(shortcut.title)
            urlInput.setText(shortcut.url)
        }
        val dialog = Dialog(this).apply {
            requestWindowFeature(1)
            setContentView(view)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(0))
                setLayout((resources.displayMetrics.widthPixels * 0.86).toInt(), -2)
            }
        }
        view.findViewById<View>(R.id.button_dialog_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.button_dialog_confirm).setOnClickListener {
            val rawUrl = urlInput.text.toString().trim()
            if (rawUrl.isBlank()) {
                Toast.makeText(this, R.string.shortcut_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normalized = normalizeInput(rawUrl)
            val name = nameInput.text.toString().trim().ifBlank { displayHost(normalized) }
            if (isEdit && shortcut != null) {
                shortcutStore.update(shortcut.url, name, normalized)
                Toast.makeText(this, R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
            } else {
                shortcutStore.add(name, normalized)
                Toast.makeText(this, R.string.shortcut_added, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
            renderShortcuts()
        }
        dialog.show()
    }

    fun openShortcut(url: String) {
        val current = tabManager.currentTab
        if (current == null) {
            tabManager.newTab(url)
            return
        }
        current.url = url
        current.webView.loadUrl(url)
        userEditingAddress = false
        topAddress.setText(displayHost(url))
        updateBars()
    }

    fun themeValueLabel(): String = getString(softThemeStore.palette().nameRes)

    fun applySoftThemeChrome(onHome: Boolean = false) {
        val palette = softThemeStore.palette()
        if (onHome || SoftThemes.isDefault(palette)) return
        val top = if (isDarkMode) SoftThemes.darken(palette.top) else palette.top
        val bottom = if (isDarkMode) SoftThemes.darken(palette.bottom) else palette.bottom
        topOverlay.background = SoftThemeChrome.bar(top, bottom)
        bottomNav.background = SoftThemeChrome.bar(top, bottom)
    }

    private fun applyHomeBackgroundTint() {
        if (isDarkMode) {
            homeBgImage.setColorFilter(Color.argb(0x8C, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        } else {
            homeBgImage.clearColorFilter()
        }
    }

    fun applyHomeBackground() {
        applyHomeBackgroundTint()
        val homeLogo = findViewById<ImageView>(R.id.home_logo)
        if (homeLogo != null) {
            val plainWallpaper = homeBackgroundStore.builtinRes() == 0 && homeBackgroundStore.backgroundUri() == null
            // A 标识横竖屏都显示：横屏由 applyHomeLogoSize() 缩小尺寸，竖向空间留给快捷方式
            homeLogo.visibility = if (plainWallpaper && !IncognitoMode.isEnabled(this)) View.VISIBLE else View.GONE
            homeLogo.post { applyHomePillAnchor() }
        }
        val builtin = homeBackgroundStore.builtinRes()
        val uriString = homeBackgroundStore.backgroundUri()
        val key = if (builtin != 0) "res:$builtin" else uriString
        if (backgroundApplied && key == appliedBackgroundUri) return
        appliedBackgroundUri = key
        backgroundApplied = true

        when {
            builtin != 0 -> {
                homeBgImage.setImageResource(builtin)
                homeBgImage.visibility = View.VISIBLE
            }
            uriString == null -> {
                homeBgImage.setImageDrawable(null)
                homeBgImage.visibility = View.GONE
            }
            else -> try {
                homeBgImage.setImageURI(Uri.parse(uriString))
                homeBgImage.visibility = View.VISIBLE
            } catch (_: Exception) {
                homeBgImage.setImageDrawable(null)
                homeBgImage.visibility = View.GONE
            }
        }
    }

    private fun tintProgressBar() {
        val hex = if (isDarkMode) "#8AB4F8" else "#1A73E8"
        progressBar.progressTintList = ColorStateList.valueOf(Color.parseColor(hex))
    }

    // ==================== 导航与快速搜索 ====================

    fun goHome() {
        hideSuggestions()
        hideFindInPage()
        hideKeyboard()
        topAddress.clearFocus()
        userEditingAddress = false
        val current = tabManager.currentTab
        if (current != null) {
            current.webView.loadUrl(BLANK_URL)
        } else {
            tabManager.newTab(BLANK_URL)
        }
        updateBars()
    }

    fun commitAddress() {
        val raw = topAddress.text.toString().trim()
        if (raw.isEmpty()) return
        val target = normalizeInput(raw)
        val current = tabManager.currentTab
        if (current == null) {
            tabManager.newTab(target)
        } else {
            current.url = target
            current.webView.loadUrl(target)
        }
        hideSuggestions()
        hideKeyboard()
        topAddress.clearFocus()
        userEditingAddress = false
        topAddress.setText(displayHost(target))
        updateBars()
    }

    fun normalizeInput(input: String): String {
        if (input.startsWith("about:", ignoreCase = true) || input.contains("://")) {
            return input
        }
        val looksDomain = !input.contains(' ') && input.contains('.') &&
            !input.startsWith('.') && !input.endsWith('.')
        if (looksDomain) {
            return "https://$input"
        }
        return store.selected().buildSearchUrl(input)
    }

    fun looksLikeUrl(input: String): Boolean {
        val q = input.trim()
        if (q.isEmpty()) return false
        if (q.startsWith("http://", ignoreCase = true) ||
            q.startsWith("https://", ignoreCase = true) ||
            q.startsWith("about:", ignoreCase = true)
        ) return true
        if (!q.contains(' ') && q.none { it.code > 127 }) {
            return URL_REGEX.matches(q)
        }
        return false
    }

    // ==================== 错误页 ====================

    private fun setupErrorPage() {
        findViewById<Button>(R.id.btn_error_reload).setOnClickListener {
            hideErrorPage()
            tabManager.currentTab?.webView?.reload()
        }
        errorDetailsToggle.setOnClickListener {
            errorDetailsOpen = !errorDetailsOpen
            errorUrlView.visibility = if (errorDetailsOpen) View.VISIBLE else View.GONE
            errorDetailsArrow.rotation = if (errorDetailsOpen) 180f else 0f
        }
    }

    fun showErrorPage(errorCode: Int, failingUrl: String) {
        val (netError, description) = errorInfo(errorCode)
        errorDescView.text = description
        errorCodeView.text = netError
        errorUrlView.text = failingUrl
        errorDetailsOpen = false
        errorUrlView.visibility = View.GONE
        errorDetailsArrow.rotation = 0f
        errorPage.elevation = 16f
        errorPage.bringToFront()
        errorPage.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        if (errorPage.visibility == View.VISIBLE) {
            errorPage.visibility = View.GONE
        }
    }

    private fun errorInfo(errorCode: Int): Pair<String, String> = when (errorCode) {
        -16 -> "ERR_CERT_SYMANTEC_LEGACY" to "该网站的证书不受信任。"
        -15 -> "ERR_TOO_MANY_REQUESTS" to "请求过于频繁。"
        -14 -> "ERR_FILE_NOT_FOUND" to "找不到该文件。"
        -13 -> "ERR_FILE_ERROR" to "文件错误。"
        -12 -> "ERR_INVALID_URL" to "网址无效。"
        -11 -> "ERR_SSL_PROTOCOL_ERROR" to "安全连接握手失败。"
        -10 -> "ERR_UNKNOWN_URL_SCHEME" to "不支持该网址协议。"
        -9 -> "ERR_TOO_MANY_REDIRECTS" to "重定向次数过多。"
        -8 -> "ERR_TIMED_OUT" to "连接超时。"
        -7 -> "ERR_INTERNET_DISCONNECTED" to "设备似乎已断开网络连接。"
        -6 -> "ERR_CONNECTION_RESET" to "连接已重置。"
        -5 -> "ERR_PROXY_AUTH_UNSUPPORTED" to "代理身份验证失败。"
        -4 -> "ERR_MISCONFIGURED_AUTH_ENVIRONMENT" to "身份验证失败。"
        -3 -> "ERR_UNSUPPORTED_AUTH_SCHEME" to "不支持的身份验证方案。"
        -2 -> "ERR_NAME_NOT_RESOLVED" to "无法找到该网站的服务器地址。"
        else -> "ERR_FAILED" to "发生未知错误。"
    }

    // ==================== 搜索建议（SQLite 本地即时 + 远程缓存/预热） ====================

    private fun setupSuggestions() {
        suggestionPanel = findViewById(R.id.home_suggestion_panel)
        suggestionList = findViewById(R.id.home_suggestion_list)
        suggestionList?.setOnItemClickListener { parent, _, position, _ ->
            val chosen = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            topAddress.setText(chosen)
            commitAddress()
        }
        topAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                handler.removeCallbacksAndMessages(null)
                val query = editable?.toString()?.trim().orEmpty()
                if (query.isEmpty() || !userEditingAddress || looksLikeUrl(query)) {
                    hideSuggestions()
                    return
                }
                val localMatches = buildLocalSuggestions(query)
                if (localMatches.isNotEmpty()) {
                    showSuggestionResults(localMatches)
                }
                if (store.isSuggestEnabled()) {
                    handler.postDelayed({ fetchSuggestions(query, localMatches) }, SUGGEST_DEBOUNCE_MS)
                } else if (localMatches.isEmpty()) {
                    hideSuggestions()
                }
            }
        })
    }

    private fun buildLocalSuggestions(query: String): List<String> {
        val results = LinkedHashSet<String>(4)
        bookmarkStore.search(query, 3).forEach { results.add(it.url) }
        if (results.size < 3) {
            historyStore.search(query, 3 - results.size).forEach { results.add(it.url) }
        }
        return results.toList()
    }

    fun showSuggestionResults(items: List<String>) {
        val panel = suggestionPanel ?: return
        val list = suggestionList ?: return
        if (!topAddress.hasFocus() || topBar.visibility != View.VISIBLE) {
            hideSuggestions()
            return
        }
        panel.setBackgroundColor(if (isDarkMode) 0xFF121316.toInt() else 0xFFFFFFFF.toInt())
        list.adapter = SuggestionsAdapter(this, items, isDarkMode)
        val rows = items.size.coerceAtMost(6)
        val lp = list.layoutParams
        lp.height = dpSize(59 * rows) + dpSize(2)
        list.layoutParams = lp
        panel.y = topOverlay.y + topOverlay.height
        panel.visibility = View.VISIBLE
        panel.bringToFront()
    }

    fun hideSuggestions() {
        suggestionPanel?.let {
            if (it.visibility == View.VISIBLE) it.visibility = View.GONE
        }
    }

    private fun fetchSuggestions(query: String, localMatches: List<String>) {
        if (looksLikeUrl(query)) {
            hideSuggestions()
            return
        }
        suggestSequence++
        val sequence = suggestSequence
        val engine = store.selected()
        ioExecutor.execute {
            val remote = SuggestionFetcher.fetch(engine, query)
            val merged = (localMatches + remote).distinct()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (sequence != suggestSequence || merged.isEmpty()) {
                    if (sequence == suggestSequence && merged.isEmpty()) hideSuggestions()
                    return@runOnUiThread
                }
                showSuggestionResults(merged)
            }
        }
    }

    // ==================== UI 同步与标签切换 ====================

    fun syncUi(tab: BrowserTab) {
        val isHome = tab.url == BLANK_URL
        if (!userEditingAddress) {
            topAddress.setText(if (isHome) "" else displayHost(tab.url))
        }
        updateBars()
        progressBar.progress = tab.progress
        progressBar.visibility = if (tab.progress in 1..99) View.VISIBLE else View.GONE
        tabCountView.text = tabManager.tabCount.toString()
    }

    fun displayHost(url: String): String {
        if (url.startsWith("about:", ignoreCase = true)) return ""
        return runCatching { Uri.parse(url).host }.getOrNull() ?: url
    }

    fun showTabSwitcher() {
        if (tabSwitcherView != null) return
        val panel = layoutInflater.inflate(R.layout.view_tab_switcher, rootView, false)
        val card = panel.findViewById<View>(R.id.tab_switcher_card)
        card.setBackgroundResource(if (isDarkMode) R.drawable.bg_m3_card_dark else R.drawable.bg_m3_card)
        val listContainer = panel.findViewById<LinearLayout>(R.id.tab_list_container)
        val tabs = tabManager.tabsSnapshot()
        val current = tabManager.currentTab

        for (tab in tabs) {
            val row = layoutInflater.inflate(R.layout.item_tab_switcher, listContainer, false)
            row.setBackgroundResource(if (isDarkMode) R.drawable.bg_m3_ripple_dark else R.drawable.bg_m3_ripple)
            val icon = row.findViewById<ImageView>(R.id.tab_item_icon)
            val name = row.findViewById<TextView>(R.id.tab_item_name)
            val close = row.findViewById<ImageView>(R.id.tab_item_close)
            val isHome = tab.url == BLANK_URL
            icon.setImageResource(if (isHome) R.drawable.ic_globe else R.drawable.ic_search)
            name.text = tabDisplayName(tab)
            if (tab === current) {
                val accent = getColor(if (isDarkMode) R.color.primary_dark else R.color.primary)
                name.setTextColor(accent)
                icon.setColorFilter(accent)
            }
            row.setOnClickListener {
                tabManager.selectTab(tabs.indexOf(tab))
                dismissTabSwitcher()
            }
            close.setOnClickListener {
                tabManager.closeTab(tabs.indexOf(tab))
                if (tabManager.tabCount == 0) tabManager.newTab(BLANK_URL)
                dismissTabSwitcher()
                showTabSwitcher()
            }
            listContainer.addView(row)
        }
        panel.findViewById<View>(R.id.button_add_in_switcher).setOnClickListener {
            tabManager.newTab(BLANK_URL)
            dismissTabSwitcher()
        }
        panel.setOnClickListener { dismissTabSwitcher() }
        rootView.addView(panel)
        card.alpha = 0f
        card.translationY = dpSize(24).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(180L)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        tabSwitcherView = panel
    }

    fun dismissTabSwitcher() {
        tabSwitcherView?.let { rootView.removeView(it) }
        tabSwitcherView = null
    }

    private fun tabDisplayName(tab: BrowserTab): String =
        if (tab.url == BLANK_URL) getString(R.string.home) else tab.title.ifBlank { tab.url }

    // ==================== 菜单动作分发 ====================

    private fun runMenuAction(actionId: String) {
        when (actionId) {
            MenuCustomizationStore.ACTION_SNIFF -> startActivity(Intent(this, SniffActivity::class.java))
            MenuCustomizationStore.ACTION_ORIENTATION -> showOrientationPicker()
            MenuCustomizationStore.ACTION_ADD_HOME -> addToHomeScreen()
            MenuCustomizationStore.ACTION_WEB_SETTINGS -> startActivity(Intent(this, WebSettingsActivity::class.java))
            MenuCustomizationStore.ACTION_FIND_IN_PAGE -> showFindInPage()
            MenuCustomizationStore.ACTION_DARK_MODE -> showThemePicker()
            MenuCustomizationStore.ACTION_WHITELIST -> toggleWhitelist()
            MenuCustomizationStore.ACTION_FONT_SIZE -> showFontSizePicker()
            MenuCustomizationStore.ACTION_INCOGNITO -> toggleIncognito()
            MenuCustomizationStore.ACTION_RELOAD -> tabManager.currentTab?.webView?.reload()
            MenuCustomizationStore.ACTION_ADD_BOOKMARK -> addBookmark()
            MenuCustomizationStore.ACTION_EXIT -> exitApp()
            MenuCustomizationStore.ACTION_HOME -> goHome()
            MenuCustomizationStore.ACTION_SHARE -> sharePage()
            MenuCustomizationStore.ACTION_TRANSLATE -> translateController.onTranslateAction()
            MenuCustomizationStore.ACTION_DESKTOP -> toggleDesktopMode()
            MenuCustomizationStore.ACTION_ALL_MENU -> menuController.showAllMenuDialog()
        }
    }

    fun toggleDesktopMode() {
        val enabled = !DesktopMode.isEnabled(this)
        DesktopMode.setEnabled(this, enabled)
        tabManager.tabsSnapshot().forEach { DesktopMode.applyTo(it.webView, this) }
        val current = tabManager.currentTab
        if (current != null && current.url != BLANK_URL) {
            current.webView.clearCache(true)
            current.webView.loadUrl(current.url)
        }
        Toast.makeText(this, if (enabled) R.string.desktop_on else R.string.mobile_on, Toast.LENGTH_SHORT).show()
    }

    fun showFontSizePicker() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpSize(2), dpSize(6), dpSize(2), dpSize(2))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            text = fontZoomLabel(fontSizeStore.textZoom)
        }
        row.addView(TextView(this).apply { text = "A"; textSize = 12f })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(valueText)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply { text = "A"; textSize = 21f })

        fun applyZoom(zoom: Int) {
            fontSizeStore.textZoom = zoom
            tabManager.tabsSnapshot().forEach { it.webView.settings.textZoom = zoom }
            valueText.text = fontZoomLabel(zoom)
        }

        val seek = SeekBar(this).apply {
            max = 13
            progress = ((fontSizeStore.textZoom - 85) / 5).coerceIn(0, 13)
            splitTrack = false
            setPadding(dpSize(18), dpSize(10), dpSize(18), dpSize(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    applyZoom(85 + 5 * progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        body.addView(row)
        body.addView(seek)
        AegisDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setView(body)
            .setNeutralButton(R.string.font_size_reset) { d, _ ->
                applyZoom(100)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun fontZoomLabel(zoom: Int): String =
        if (zoom == 100) getString(R.string.font_normal) else String.format(Locale.US, "%d%%", zoom)

    fun toggleIncognito() {
        val enabled = !IncognitoMode.isEnabled(this)
        IncognitoMode.setEnabled(this, enabled)
        val cookieManager = CookieManager.getInstance()
        tabManager.tabsSnapshot().forEach { IncognitoMode.applyTo(it.webView, this) }
        if (enabled) {
            cookieManager.setAcceptCookie(false)
            cookieManager.flush()
            DataCleaner.clear(this, emptyList())
        } else {
            cookieManager.setAcceptCookie(true)
            DataCleaner.clear(this, emptyList())
        }
        applyIncognitoWindowSecurity()
        applyHomeBackground()
        renderShortcuts()
        updateIncognitoIndicator()
        updateBars()
        Toast.makeText(this, if (enabled) R.string.incognito_on else R.string.incognito_off, Toast.LENGTH_SHORT).show()
    }

    private fun applyIncognitoWindowSecurity() {
        if (IncognitoMode.isEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun updateIncognitoIndicator() {
        val incognito = IncognitoMode.isEnabled(this)
        val tab = tabManager.currentTab
        val onHome = (tab == null || tab.url == BLANK_URL) && !userEditingAddress
        // 网页浏览态显示顶部无痕横幅；主页态显示 Edge InPrivate 卡片
        incognitoBanner.visibility = if (incognito && !onHome) View.VISIBLE else View.GONE
        inprivateCard?.visibility = if (incognito && onHome) View.VISIBLE else View.GONE
        applyTopSpacer()
        if (incognito && !onHome) {
            incognitoBanner.post { applyTopSpacer() }
        }
    }

    fun toggleWhitelist() {
        val host = currentHost() ?: return
        if (interceptor.isWhitelisted(host)) interceptor.removeWhitelist(host) else interceptor.addWhitelist(host)
    }

    fun currentHost(): String? {
        val url = tabManager.currentTab?.url ?: return null
        return runCatching { Uri.parse(url).host }.getOrNull()
    }

    fun isCurrentHostWhitelisted(): Boolean {
        val host = currentHost() ?: return false
        return interceptor.isWhitelisted(host)
    }

    fun addBookmark() {
        val tab = tabManager.currentTab ?: return
        val url = tab.url
        if (url == BLANK_URL || url.isBlank()) return
        if (bookmarkStore.contains(url)) {
            bookmarkStore.remove(url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            bookmarkStore.add(tab.title.ifBlank { url }, url)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    fun isCurrentUrlBookmarked(): Boolean {
        val url = tabManager.currentTab?.url ?: return false
        if (url == BLANK_URL || url.isBlank()) return false
        return bookmarkStore.contains(url)
    }

    fun sharePage() {
        val tab = tabManager.currentTab ?: return
        val url = tab.url
        if (url == BLANK_URL || url.isBlank()) return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (tab.title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, tab.title)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    fun showEnginePicker() {
        val engines = store.allEngines()
        val selectedId = store.selected().id
        val content = layoutInflater.inflate(R.layout.dialog_engine_picker, null)
        content.setBackgroundResource(if (isDarkMode) R.drawable.dialog_card_bg_dark else R.drawable.dialog_card_bg)
        val group = content.findViewById<RadioGroup>(R.id.engine_radio_group)
        for (engine in engines) {
            val rb = RadioButton(this).apply {
                text = engine.displayName(this@MainActivity)
                textSize = 17f
                setTextColor(getColor(if (isDarkMode) R.color.white else R.color.icon))
                id = engine.id.hashCode()
                isChecked = engine.id == selectedId
                setPadding(0, dpSize(10), 0, dpSize(10))
            }
            group.addView(rb)
        }
        val dialog = Dialog(this).apply {
            requestWindowFeature(1)
            setContentView(content)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(0))
                setLayout((resources.displayMetrics.widthPixels * 0.8).toInt(), -2)
            }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val engine = engines.firstOrNull { it.id.hashCode() == checkedId } ?: return@setOnCheckedChangeListener
            if (engine.id != selectedId) {
                store.select(engine.id)
                SearchPreconnect.warmUp(engine)
            }
            group.postDelayed({ dialog.dismiss() }, 180L)
        }
        dialog.show()
    }

    fun dpSize(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun applyWindowInsets() {
        rootView.setOnApplyWindowInsetsListener { _, insets ->
            val statusTop = readStatusTop(insets)
            val navBottom = readNavBottom(insets)
            statusInsetTop = statusTop
            statusBarSpacer.layoutParams = statusBarSpacer.layoutParams.apply { height = statusTop }
            applyTopSpacer()
            val basePad = dpSize(2)
            bottomNav.setPadding(bottomNav.paddingLeft, bottomNav.paddingTop, bottomNav.paddingRight, basePad + navBottom)
            insets
        }
        rootView.requestApplyInsets()
    }

    private fun ensureStatusInset() {
        if (!rootView.isAttachedToWindow) return
        val insets = rootView.rootWindowInsets ?: return
        val statusTop = readStatusTop(insets)
        if (statusTop > 0 && statusInsetTop != statusTop) {
            statusInsetTop = statusTop
            statusBarSpacer.layoutParams = statusBarSpacer.layoutParams.apply { height = statusTop }
            applyTopSpacer()
        }
    }

    private fun readStatusTop(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop

    private fun readNavBottom(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.systemBars()).bottom
        else @Suppress("DEPRECATION") insets.systemWindowInsetBottom

    fun showThemePicker() {
        val labels = arrayOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_system))
        val current = themeSettings.themeMode
        AegisDialog.Builder(this)
            .setTitle(R.string.dark_mode)
            .setSingleChoiceItems(labels, current) { d, which ->
                if (which != current) {
                    themeSettings.themeMode = which
                    recreate()
                }
                d.dismiss()
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode != REQ_BOOKMARKS && requestCode != REQ_HISTORY && requestCode != REQ_SCANNER) return
        val url = data?.getStringExtra(BrowserToolbarHelper.EXTRA_OPEN_URL)
            ?: data?.data?.toString()
            ?: data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
            ?: BrowserToolbarHelper.takePendingOpenUrl()
        if (!url.isNullOrBlank()) {
            openUrlFromSection(url)
        }
    }

    fun openUrlFromSection(url: String) {
        exitShortcutEdit()
        val now = SystemClock.elapsedRealtime()
        if (url == lastSectionUrl && now - lastSectionAt < 1500L) return
        lastSectionUrl = url
        lastSectionAt = now
        val current = tabManager.currentTab
        if (current == null) {
            tabManager.newTab(url)
        } else {
            current.url = url
            current.webView.loadUrl(url)
        }
        userEditingAddress = false
        updateBars()
    }

    override fun onUpgradeFailed(originalHttpUrl: String, onDecision: (Boolean) -> Unit) {
        AegisDialog.Builder(this)
            .setTitle(R.string.https_warning_title)
            .setMessage(R.string.https_warning_msg)
            .setPositiveButton(R.string.continue_http) { _, _ -> onDecision(true) }
            .setNegativeButton(R.string.go_back) { _, _ -> onDecision(false) }
            .setCancelable(false)
            .show()
    }

    fun exitApp() {
        // 无痕会话退出必须清理，不受“退出自动清除”开关影响，做到零落盘
        if (privacySettings.clearOnExit || IncognitoMode.isEnabled(this)) {
            val webViews = tabManager.tabsSnapshot().map { it.webView }
            DataCleaner.clear(this, webViews)
            historyStore.clear()
            tabSessionStore.clear()
        }
        finish()
    }

    fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(topAddress.windowToken, 0)
        hideSuggestions()
    }

    fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(topAddress, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        // 无痕会话销毁时兜底清理：进程内数据不留在 Cookie / WebStorage / 会话库
        if (IncognitoMode.isEnabled(this)) {
            runCatching {
                DataCleaner.clear(this, tabManager.tabsSnapshot().map { it.webView })
                historyStore.clear()
                tabSessionStore.clear()
            }
        }
        if (customView != null) hideCustomView()
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        tabManager.destroy()
        super.onDestroy()
    }
}
