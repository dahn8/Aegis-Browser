package dev.aegis.browser.privacy

import dev.aegis.browser.core.web.ScriptProvider

/**
 * 轻量元素隐藏与外链放行脚本。只选择明确的广告/推广容器，
 * 避免使用 .ad 等宽泛选择器误伤正常内容。
 * CSS 属性选择器内部使用双引号，外层 JS 字符串使用单引号，避免引号冲突。
 */
class PrivacyScriptProvider : ScriptProvider {

    override fun scriptFor(pageHost: String?): String = INJECT_SCRIPT

    companion object {
        private const val HIDE_CSS =
            "ins.adsbygoogle," +
            "iframe[id^=\"google_ads\"]," +
            "iframe[src*=\"doubleclick\"]," +
            "iframe[src*=\"googlesyndication\"]," +
            ".adsbygoogle," +
            ".ad-container," +
            ".advertisement," +
            ".ad-banner," +
            "#ad-banner," +
            ".google-ad-container," +
            // 必应移动版底部“微软必应 点击下载”固定推广横幅
            "#bnp_rich_div," +
            // 必应移动版“继续搜索”外链拦截遮罩与弹窗（自动放行逻辑见下方脚本）
            "#sacs_win{display:none!important}"

        // 仅对必应生效：隐藏结果页下滑后浮出的吸顶搜索条。
        // 滚动超过阈值时必应会把 #sb_form 搬进 #mob_phead（position:fixed 白条），
        // 于是页面顶部叠出第二个搜索框，盖住结果；浏览器本身已有固定地址栏。
        private const val BING_HIDE_CSS =
            "#mob_phead{display:none!important;height:0!important;overflow:hidden!important}"

        val INJECT_SCRIPT = """
            (function () {
                var host = location.hostname || '';
                var css = '$HIDE_CSS';
                if (host.indexOf('bing.com') >= 0) css += ',' + '$BING_HIDE_CSS';

                // 幂等：多次注入（页面开始 + 页面完成）只保留一个样式节点；
                // 若页面重建根节点导致样式丢失，定时补回。
                function ensureStyle() {
                    try {
                        if (document.getElementById('aegis-hide-css')) return;
                        var style = document.createElement('style');
                        style.id = 'aegis-hide-css';
                        style.textContent = css;
                        (document.head || document.documentElement).appendChild(style);
                    } catch (e) {}
                }
                ensureStyle();

                // 必应点击外链时会弹出 #sacs_win 拦截导航，要求选择“浏览器继续”。
                // 这里在弹窗出现时自动点击 #sacs_close 静默放行，用户无需看到弹窗。
                function autoContinue() {
                    var box = document.getElementById('sacs_win');
                    if (!box || box.getAttribute('data-aegis-done') === '1') return;
                    var btn = document.getElementById('sacs_close');
                    if (btn) {
                        box.setAttribute('data-aegis-done', '1');
                        btn.click();
                    }
                }

                // 必应滚动时会把这个吸顶条重新显示，若仅靠样式表，可能被行内
                // !important 覆盖；这里同步压一条行内 !important，优先级最高。
                function killSticky() {
                    var p = document.getElementById('mob_phead');
                    if (p) p.style.setProperty('display', 'none', 'important');
                }

                function tick() {
                    ensureStyle();
                    killSticky();
                    autoContinue();
                }

                try {
                    var observer = new MutationObserver(tick);
                    observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['style', 'class']
                    });
                } catch (e) {}

                // 兜底：节点已存在、仅切换 display 的情况，定时检查，开销极小
                setInterval(tick, 500);
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', tick);
                } else {
                    tick();
                }
            })();
        """.trimIndent()
    }
}
