package dev.aegis.browser.privacy

import dev.aegis.browser.core.web.ScriptProvider

/**
 * 轻量元素隐藏与外链放行脚本。只选择明确的广告/推广容器，
 * 避免使用 .ad 等宽泛选择器误伤正常内容。
 * CSS 属性选择器内部使用双引号，外层 JS 字符串使用单引号，避免引号冲突。
 *
 * 另外承担 M6 的两项加固：
 * - Referrer 策略：注入 strict-origin-when-cross-origin，跨站只带源不留路径
 * - 反指纹基线：统一 navigator 的平台/厂商/硬件参数，与 UA 自洽
 */
class PrivacyScriptProvider(
    private val antiFingerprintEnabled: () -> Boolean = { true }
) : ScriptProvider {

    override fun scriptFor(pageHost: String?): String =
        if (antiFingerprintEnabled()) INJECT_SCRIPT + REFERRER_SCRIPT + FINGERPRINT_SCRIPT
        else INJECT_SCRIPT + REFERRER_SCRIPT

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

        /**
         * Referrer 收紧：默认策略会跨站带完整路径，注入 meta 后只保留源（scheme+host），
         * 避免搜索结果页、内网地址等路径信息被第三方拿到。
         */
        private val REFERRER_SCRIPT = """
            (function () {
                function apply() {
                    try {
                        if (document.querySelector('meta[name="referrer"]')) return;
                        var meta = document.createElement('meta');
                        meta.name = 'referrer';
                        meta.content = 'strict-origin-when-cross-origin';
                        var head = document.head || document.documentElement;
                        if (head) head.insertBefore(meta, head.firstChild);
                    } catch (e) {}
                }
                apply();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', apply);
                }
            })();
        """.trimIndent()

        /**
         * 反指纹基线：不随机化、不伪造硬件，只把容易与 UA 冲突、且熵很高的取值统一。
         * navigator.platform / appVersion 由真实 UA 推导，保证与请求头一致，避免站点识别出伪装。
         */
        private val FINGERPRINT_SCRIPT = """
            (function () {
                function def(obj, key, value) {
                    try {
                        Object.defineProperty(obj, key, {
                            get: function () { return value; },
                            configurable: true
                        });
                    } catch (e) {}
                }
                try {
                    var ua = '';
                    try { ua = navigator.userAgent || ''; } catch (e) {}
                    var platform = 'Linux x86_64';
                    if (ua.indexOf('Android') >= 0) platform = 'Linux armv8l';
                    else if (ua.indexOf('Windows') >= 0) platform = 'Win32';
                    else if (ua.indexOf('Macintosh') >= 0) platform = 'MacIntel';
                    def(navigator, 'platform', platform);
                    def(navigator, 'appVersion', ua.replace(/^Mozilla\//, ''));
                    def(navigator, 'vendor', 'Google Inc.');
                    def(navigator, 'productSub', '20030107');
                    def(navigator, 'webdriver', false);
                    // 硬件并发与内存向下取整到常见档位，避免高端机型形成唯一组合
                    try {
                        if (navigator.hardwareConcurrency > 4) def(navigator, 'hardwareConcurrency', 4);
                    } catch (e) {}
                    try {
                        if (navigator.deviceMemory && navigator.deviceMemory > 4) {
                            def(navigator, 'deviceMemory', 4);
                        }
                    } catch (e) {}
                    try { def(navigator, 'maxTouchPoints', 5); } catch (e) {}
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
