package dev.aegis.browser.sections

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dev.aegis.browser.R
import dev.aegis.browser.ui.AppLanguage
import dev.aegis.browser.ui.ScreenOrientation
import dev.aegis.browser.ui.BrowserToolbarHelper
import dev.aegis.browser.ui.ThemeManager
import dev.aegis.browser.ui.ThemeSettings

/**
 * 书签 / 历史 / 下载三页的宿主：三个页面在同一个窗口里横向排列，
 * 顶栏固定，滑动时整条纸带走，相邻页真的跟着手指一起滑。
 */
class SectionHostActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    companion object {
        const val EXTRA_INITIAL_SECTION = "aegis_initial_section"

        fun intentFor(context: Context, section: Int): Intent =
            Intent(context, SectionHostActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_SECTION, section)
            }
    }

    private val sections = arrayOf(
        BrowserToolbarHelper.Section.BOOKMARKS,
        BrowserToolbarHelper.Section.HISTORY,
        BrowserToolbarHelper.Section.DOWNLOADS
    )

    private lateinit var pager: SectionPager
    private lateinit var bookmarksPanel: BookmarksPanel
    private lateinit var historyPanel: HistoryPanel
    private lateinit var downloadsPanel: DownloadsPanel
    private lateinit var themeSettings: ThemeSettings
    private var screenOrientation: String = ScreenOrientation.FOLLOW
    private var dark = false

    val homeChain: Boolean by lazy {
        intent?.getBooleanExtra(BrowserToolbarHelper.EXTRA_HOME_CHAIN, false) == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        themeSettings = ThemeSettings(this)
        dark = ThemeManager.apply(this, themeSettings, settingsScreen = true)
        screenOrientation = ScreenOrientation.current(this)
        ScreenOrientation.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sections)
        ThemeManager.applyStatusBarAppearance(this, dark)

        pager = findViewById(R.id.section_pager)

        val bookmarksView = layoutInflater.inflate(R.layout.panel_bookmarks, pager, false)
        val historyView = layoutInflater.inflate(R.layout.panel_history, pager, false)
        val downloadsView = layoutInflater.inflate(R.layout.panel_downloads, pager, false)
        pager.addView(bookmarksView)
        pager.addView(historyView)
        pager.addView(downloadsView)

        bookmarksPanel = BookmarksPanel(this, bookmarksView)
        historyPanel = HistoryPanel(this, historyView)
        downloadsPanel = DownloadsPanel(this, downloadsView)

        pager.onIndexChanged = { index ->
            updateChrome()
            when (index) {
                0 -> bookmarksPanel.onShown()
                1 -> historyPanel.onShown()
                else -> downloadsPanel.onShown()
            }
        }

        BrowserToolbarHelper.bind(this, sections[pager.currentIndex], homeChain) { section ->
            pager.goTo(section.ordinal)
        }
        findViewById<View>(R.id.toolbar_close).setOnClickListener { handleClose() }

        val initial = intent?.getIntExtra(EXTRA_INITIAL_SECTION, 0) ?: 0
        pager.selectInitial(initial)
        updateChrome()
    }

    override fun onResume() {
        val latestOrientation = ScreenOrientation.current(this)
        if (latestOrientation != screenOrientation) {
            screenOrientation = latestOrientation
            ScreenOrientation.apply(this)
        }
        super.onResume()
        if (ThemeManager.isDarkNow(this, themeSettings) != dark) {
            recreate()
            return
        }
        when (pager.currentIndex) {
            0 -> bookmarksPanel.onShown()
            1 -> historyPanel.onShown()
            else -> downloadsPanel.onShown()
        }
    }

    private fun updateChrome() {
        BrowserToolbarHelper.updateTabs(this, sections[pager.currentIndex])
    }

    /** 关掉这一组页面：设置页进来的回设置，主页进来的回主页 */
    fun handleClose() {
        if (BrowserToolbarHelper.goToReturnTarget(this)) return
        if (homeChain) BrowserToolbarHelper.goHome(this) else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleClose()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        downloadsPanel.handleActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        downloadsPanel.release()
        super.onDestroy()
    }
}
