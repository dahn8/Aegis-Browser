// Aegis 网页翻译注入脚本（视口优先 + 滚动续译）
// 1) __aegisStart()          收集全页可翻译文本节点，返回总数
// 2) __aegisNext(limit)      取「当前屏幕附近尚未翻译」的若干段，返回 {hasMore, items:[[idx,text]]}
// 3) __aegisApplyPairs(json) 按序号回填译文，保留原首尾空白
// 4) __aegisRevert()         全部还原为原文并移除顶条
// 滚动时累加 window.__aegisScrollTick，原生层轮询该计数后继续取下一批
(function () {
  if (window.__aegisReady) return;
  window.__aegisReady = true;

  var SKIP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, PRE: 1,
    IFRAME: 1, CANVAS: 1, SVG: 1, INPUT: 1, SELECT: 1, OPTION: 1, VIDEO: 1, AUDIO: 1
  };
  var MAX_NODES = 4000;

  var nodes = [];
  var texts = [];
  var leads = [];
  var trails = [];
  var doneFlags = [];
  var pending = 0;

  // 只要包含常见文字（拉丁/西里尔/阿拉伯/天城/泰/中日韩/假名/谚文）就参与翻译
  var LETTERS = /[A-Za-z\u00c0-\u024f\u0400-\u04ff\u0600-\u06ff\u0900-\u097f\u0e00-\u0e7f\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]/;

  function shouldSkip(text) {
    if (!text) return true;
    if (text.length < 2 || text.length > 1000) return true;
    if (/^[0-9+\-_.%/: ]+$/.test(text)) return true;
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(text)) return true;
    if (/^(https?:\/\/|www\.)/i.test(text)) return true;
    if (!LETTERS.test(text)) return true;
    return false;
  }

  // 已经是目标语言写法的段落不必再翻（例如目标中文，页面本身是中文）
  function isTargetText(text, target) {
    var latin = /[A-Za-z]/.test(text);
    var cjk = /[\u4e00-\u9fff]/.test(text);
    var kana = /[\u3040-\u30ff]/.test(text);
    var hangul = /[\uac00-\ud7af]/.test(text);
    if (target === 'zh-CN' || target === 'zh-TW') return cjk;
    if (target === 'ja') return kana && !latin;
    if (target === 'ko') return hangul && !latin;
    if (target === 'en') return latin && !cjk;
    return false;
  }

  function collectNodes(target) {
    nodes = [];
    texts = [];
    leads = [];
    trails = [];
    doneFlags = [];
    pending = 0;
    var root = document.body || document.documentElement;
    if (!root) return 0;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = walker.nextNode())) {
      if (nodes.length >= MAX_NODES) break;
      var parent = node.parentElement;
      if (!parent) continue;
      if (SKIP[parent.tagName]) continue;
      if (parent.closest && parent.closest('[data-aegis-tr]')) continue;
      var style = window.getComputedStyle(parent);
      if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
      if (parent.getClientRects().length === 0) continue;
      var raw = node.nodeValue || '';
      if (!raw.trim()) continue;
      if (shouldSkip(raw.trim())) continue;
      if (isTargetText(raw.trim(), target)) continue;
      var lead = (raw.match(/^[ \t\r\n]*/) || [''])[0];
      var trail = (raw.match(/[ \t\r\n]*$/) || [''])[0];
      nodes.push(node);
      texts.push(raw.trim());
      leads.push(lead);
      trails.push(trail);
      doneFlags.push(0);
      pending++;
    }
    return pending;
  }

  function inViewport(el) {
    var rect = el.getBoundingClientRect();
    var h = window.innerHeight || document.documentElement.clientHeight || 800;
    return rect.bottom > -h * 0.6 && rect.top < h * 1.6;
  }

  function anyPending() {
    for (var i = 0; i < nodes.length; i++) {
      if (!doneFlags[i]) return true;
    }
    return false;
  }

  window.__aegisStart = function (target) {
    window.__aegisTarget = target || 'zh-CN';
    return collectNodes(window.__aegisTarget);
  };

  window.__aegisNext = function (limit) {
    if (!nodes.length) return JSON.stringify({ hasMore: false, items: [] });
    var max = limit || 20;
    var items = [];
    for (var i = 0; i < nodes.length; i++) {
      if (doneFlags[i]) continue;
      var el = nodes[i].parentElement;
      if (!el) continue;
      if (!inViewport(el)) continue;
      items.push([i, texts[i]]);
      if (items.length >= max) break;
    }
    return JSON.stringify({ hasMore: anyPending(), items: items });
  };

  window.__aegisApplyPairs = function (json) {
    var pairs = typeof json === 'string' ? JSON.parse(json) : json;
    var applied = 0;
    for (var k = 0; k < pairs.length; k++) {
      var idx = pairs[k][0];
      var val = pairs[k][1];
      if (val === null || val === undefined) continue;
      val = String(val).trim();
      if (!val) continue;
      var node = nodes[idx];
      if (!node) continue;
      node.nodeValue = leads[idx] + val + trails[idx];
      doneFlags[idx] = 1;
      pending--;
      var parent = node.parentElement;
      if (parent) parent.setAttribute('data-aegis-tr', '1');
      applied++;
    }
    return applied;
  };

  window.__aegisRevert = function () {
    for (var i = 0; i < nodes.length; i++) {
      if (!doneFlags[i]) continue;
      var node = nodes[i];
      if (!node) continue;
      node.nodeValue = leads[i] + texts[i] + trails[i];
      var parent = node.parentElement;
      if (parent) parent.removeAttribute('data-aegis-tr');
    }
    nodes = [];
    texts = [];
    leads = [];
    trails = [];
    doneFlags = [];
    pending = 0;
    window.__aegisReady = false;
    hideBar();
    return true;
  };

  function showBar(text) {
    var bar = document.getElementById('aegis-tr-bar');
    if (!bar) {
      bar = document.createElement('div');
      bar.id = 'aegis-tr-bar';
      bar.setAttribute('style', [
        'position:fixed', 'top:0', 'left:0', 'right:0', 'height:44px',
        'display:flex', 'align-items:center', 'justify-content:space-between',
        'padding:0 14px', 'box-sizing:border-box',
        'background:rgba(255,255,255,0.97)', 'color:#16181c',
        'font:14px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif',
        'border-bottom:1px solid #e6e8ec', 'z-index:2147483647',
        'box-shadow:0 1px 6px rgba(0,0,0,0.06)'
      ].join(';'));

      var label = document.createElement('span');
      label.id = 'aegis-tr-label';
      label.setAttribute('style', 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap');
      bar.appendChild(label);

      var btn = document.createElement('a');
      btn.id = 'aegis-tr-btn';
      btn.textContent = '显示原文';
      btn.setAttribute('style', [
        'flex:0 0 auto', 'margin-left:12px', 'padding:5px 10px',
        'border-radius:14px', 'background:#f2f3f5', 'color:#3b5bdb',
        'font-size:13px', 'text-decoration:none', 'cursor:pointer'
      ].join(';'));
      btn.onclick = function () { window.__aegisRevert(); };
      bar.appendChild(btn);

      (document.body || document.documentElement).appendChild(bar);
    }
    var label = document.getElementById('aegis-tr-label');
    if (label) label.textContent = text || '';
  }

  function hideBar() {
    var bar = document.getElementById('aegis-tr-bar');
    if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
  }

  window.__aegisShowBar = showBar;
  window.__aegisHideBar = hideBar;

  // 滚动计数：原生层轮询这个很小的数字，变化了才去取新进入屏幕的段落
  if (!window.__aegisScrollTick) window.__aegisScrollTick = 0;
  if (!window.__aegisScrollBound) {
    window.__aegisScrollBound = true;
    window.addEventListener('scroll', function () {
      if (!window.__aegisReady) return;
      window.__aegisScrollTick = (window.__aegisScrollTick || 0) + 1;
    }, true);
    document.addEventListener('scroll', function () {
      if (!window.__aegisReady) return;
      window.__aegisScrollTick = (window.__aegisScrollTick || 0) + 1;
    }, true);
  }
})();
