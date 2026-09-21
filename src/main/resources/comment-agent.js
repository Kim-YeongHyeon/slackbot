/* 아티팩트 인라인 댓글 에이전트 (v0.0.75) — sandbox(고유 origin) 문서 안에 주입되어 동작한다.
 * 문서 자체는 API/부모 DOM 에 접근 불가 → 선택 캡처·앵커링·하이라이트·히트테스트만 하고
 * 인증된 부모(리뷰 페이지)와 postMessage 로만 통신한다 (Hypothesis 패턴).
 * envelope: {ns:'cmt', type, ...}. 부모→자식 targetOrigin '*'(opaque 라 불가피),
 * 자식→부모 location.origin. 부모의 init 이 오기 전엔 ready 만 보내고 영구 휴면. */
(function () {
  'use strict';

  // 프레임 밖(직접 열림)이면 아무것도 하지 않는다 — 일반 뷰어에 영향 0.
  if (window.parent === window) return;

  var NS = 'cmt';
  var comments = [];          // 부모가 준 미해결 루트 [{cid, quote, prefix, suffix}]
  var idx = null;             // {text, nodes[], starts[]} 텍스트 인덱스
  var anchored = new Map();   // cid → Range (재앵커 성공한 것만)
  var activeCid = null;
  var listenersInstalled = false;
  var initialized = false;
  var hasHighlight = typeof CSS !== 'undefined' && CSS.highlights;  // CSS Custom Highlight API 지원?

  // ===== postMessage =====
  function post(type, extra) {
    var msg = { ns: NS, type: type };
    if (extra) for (var k in extra) msg[k] = extra[k];
    window.parent.postMessage(msg, location.origin);
  }
  window.addEventListener('message', function (e) {
    if (e.origin !== location.origin) return;             // 부모와 같은 origin 만
    var d = e.data;
    if (!d || d.ns !== NS) return;
    if (d.type === 'init' || d.type === 'set-comments') {
      comments = Array.isArray(d.comments) ? d.comments : [];
      initialized = true;
      rebuild();
    } else if (d.type === 'set-active') {
      activeCid = (d.cid == null) ? null : d.cid;
      applyHighlights();
    } else if (d.type === 'clear-selection') {
      var sel = window.getSelection();
      if (sel) sel.removeAllRanges();
    }
  });

  // ===== 텍스트 인덱스 =====
  // TreeWalker 로 body 의 텍스트 노드를 문서 순서대로 이어붙여 단일 문자열 + 각 노드의 시작 오프셋.
  // Range.toString() 은 안 쓴다 — 같은 walk 로 문자열과 오프셋을 함께 계산해 일관성을 보장한다.
  function buildIndex() {
    var text = '';
    var nodes = [];
    var starts = [];
    if (!document.body) return { text: '', nodes: [], starts: [] };
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentElement;
        if (p && p.closest('script,style,noscript,textarea')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = walker.nextNode())) {
      nodes.push(n);
      starts.push(text.length);
      text += n.nodeValue;
    }
    return { text: text, nodes: nodes, starts: starts };
  }

  // ===== 재앵커 (W3C TextQuoteSelector) =====
  function commonPrefixLen(a, b) {
    var i = 0, m = Math.min(a.length, b.length);
    while (i < m && a.charCodeAt(i) === b.charCodeAt(i)) i++;
    return i;
  }
  function commonSuffixLen(a, b) {
    var i = 0, m = Math.min(a.length, b.length);
    while (i < m && a.charCodeAt(a.length - 1 - i) === b.charCodeAt(b.length - 1 - i)) i++;
    return i;
  }
  // sel {quote, prefix, suffix} → 문서 내 [s,e) 오프셋, 못 찾으면 null.
  function findOffsets(sel) {
    var text = idx.text;
    var quote = sel.quote || '';
    if (!quote) return null;
    var prefix = sel.prefix || '';
    var suffix = sel.suffix || '';
    // 1) prefix+quote+suffix 정확 일치 우선
    var exact = text.indexOf(prefix + quote + suffix);
    if (exact >= 0) {
      var s = exact + prefix.length;
      return { s: s, e: s + quote.length };
    }
    // 2) quote 후보들을 문맥 점수로 채점 — 최고점(동점 시 첫 매치)
    var best = null, bestScore = -1;
    var from = 0, at;
    while ((at = text.indexOf(quote, from)) >= 0) {
      var before = text.slice(Math.max(0, at - 32), at);
      var after = text.slice(at + quote.length, at + quote.length + 32);
      var score = commonSuffixLen(prefix, before) + commonPrefixLen(suffix, after);
      if (score > bestScore) { bestScore = score; best = at; }
      from = at + 1;
    }
    if (best == null) return null;
    return { s: best, e: best + quote.length };
  }

  // 오프셋 → Range. starts[] 이진 탐색(upper bound)으로 (노드, 로컬오프셋) 을 찾는다.
  function locate(off) {
    var starts = idx.starts, nodes = idx.nodes;
    if (!nodes.length) return null;
    var lo = 0, hi = starts.length - 1, ans = 0;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (starts[mid] <= off) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    var node = nodes[ans];
    var local = off - starts[ans];
    if (local > node.nodeValue.length) local = node.nodeValue.length;
    return { node: node, offset: local };
  }
  function offsetsToRange(s, e) {
    var a = locate(s), b = locate(e);
    if (!a || !b) return null;
    var r = document.createRange();
    try {
      r.setStart(a.node, a.offset);
      r.setEnd(b.node, b.offset);
    } catch (_) { return null; }
    return r;
  }

  // ===== 하이라이트 =====
  function ensureStyle() {
    if (!hasHighlight || document.getElementById('__cmt-style')) return;
    var st = document.createElement('style');
    st.id = '__cmt-style';
    st.textContent = '::highlight(cmt){background:rgba(255,213,79,.45)}'
      + '::highlight(cmt-active){background:rgba(255,160,0,.65)}';
    (document.head || document.documentElement).appendChild(st);
  }
  function applyHighlights() {
    if (!hasHighlight) return;
    ensureStyle();
    var normal = [], active = [];
    anchored.forEach(function (range, cid) {
      if (cid === activeCid) active.push(range); else normal.push(range);
    });
    // new Highlight(...ranges) — 0개 인자여도 유효(빈 하이라이트). 매번 통째로 재설정.
    CSS.highlights.set('cmt', newHighlight(normal));
    CSS.highlights.set('cmt-active', newHighlight(active));
    emitStateDebounced();
  }
  function newHighlight(ranges) {
    // Highlight 생성자는 가변 인자 — bind+apply 로 배열을 펼쳐 new 로 호출한다.
    return new (Function.prototype.bind.apply(Highlight, [null].concat(ranges)))();
  }

  // ===== 지오메트리 =====
  function rangeY(range) {
    var rect = range.getBoundingClientRect();
    return rect.top + (window.scrollY || window.pageYOffset || 0);
  }
  function emitState() {
    var anchors = [];
    anchored.forEach(function (range, cid) {
      anchors.push({ cid: cid, y: rangeY(range), found: true });
    });
    // 고아(재앵커 실패) 댓글도 부모가 스택할 수 있게 알린다.
    comments.forEach(function (c) {
      if (!anchored.has(c.cid)) anchors.push({ cid: c.cid, y: null, found: false });
    });
    post('state', { docHeight: document.documentElement.scrollHeight, anchors: anchors });
  }
  var stateTimer = null;
  function emitStateDebounced() {
    if (stateTimer) clearTimeout(stateTimer);
    stateTimer = setTimeout(emitState, 100);
  }

  // ===== 재구성 (init / set-comments 시) =====
  function rebuild() {
    idx = buildIndex();
    anchored.clear();
    comments.forEach(function (c) {
      var off = findOffsets(c);
      if (!off) return;
      var range = offsetsToRange(off.s, off.e);
      if (range) anchored.set(c.cid, range);
    });
    applyHighlights();
    installListeners();
    emitState();
  }

  // ===== 선택 캡처 =====
  function pointToOffset(container, offset) {
    if (container.nodeType === Node.TEXT_NODE) {
      var i = idx.nodes.indexOf(container);
      if (i < 0) return null;
      return idx.starts[i] + offset;
    }
    // 엘리먼트 노드: childNodes[offset] 부터 문서 순서로 앞으로 걸어 첫 인덱싱 텍스트 노드의 시작을 쓴다.
    var kids = container.childNodes;
    for (var k = offset; k < kids.length; k++) {
      var t = firstIndexedIn(kids[k]);
      if (t != null) return t;
    }
    return idx.text.length;  // 뒤에 텍스트가 없으면 문서 끝
  }
  function firstIndexedIn(node) {
    if (node.nodeType === Node.TEXT_NODE) {
      var i = idx.nodes.indexOf(node);
      return i < 0 ? null : idx.starts[i];
    }
    var w = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var j = idx.nodes.indexOf(n);
      if (j >= 0) return idx.starts[j];
    }
    return null;
  }
  function captureSelection() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
      post('selection-cleared');
      return;
    }
    var range = sel.getRangeAt(0);
    var s = pointToOffset(range.startContainer, range.startOffset);
    var e = pointToOffset(range.endContainer, range.endOffset);
    if (s == null || e == null || e <= s) { post('selection-cleared'); return; }
    var text = idx.text;
    var quote = text.slice(s, Math.min(e, s + 500));
    var prefix = text.slice(Math.max(0, s - 32), s);
    var suffix = text.slice(e, e + 32);   // suffix 는 실제 끝 e 기준 (잘린 quote 와 무관)
    post('selection', { quote: quote, prefix: prefix, suffix: suffix, y: rangeY(range) });
  }

  // ===== 클릭 히트테스트 =====
  function caretAt(x, y) {
    if (document.caretPositionFromPoint) {
      var cp = document.caretPositionFromPoint(x, y);
      if (cp) return { node: cp.offsetNode, offset: cp.offset };
    }
    if (document.caretRangeFromPoint) {
      var r = document.caretRangeFromPoint(x, y);
      if (r) return { node: r.startContainer, offset: r.startOffset };
    }
    return null;
  }
  function onDocClick(ev) {
    if (!hasHighlight || anchored.size === 0) return;
    var caret = caretAt(ev.clientX, ev.clientY);
    if (!caret) return;
    var hitCid = null, narrowest = Infinity;
    anchored.forEach(function (range, cid) {
      try {
        if (range.comparePoint(caret.node, caret.offset) === 0) {
          var len = range.toString().length;
          if (len < narrowest) { narrowest = len; hitCid = cid; }
        }
      } catch (_) { /* comparePoint 는 다른 root 면 throw */ }
    });
    if (hitCid != null) post('highlight-click', { cid: hitCid, y: rangeY(anchored.get(hitCid)) });
  }

  // ===== 리스너 (1회만) =====
  var mouseTimer = null;
  function installListeners() {
    if (listenersInstalled) return;
    listenersInstalled = true;
    document.addEventListener('mouseup', function () {
      if (mouseTimer) clearTimeout(mouseTimer);
      mouseTimer = setTimeout(captureSelection, 0);   // 브라우저가 선택을 확정한 뒤 읽는다
    });
    document.addEventListener('click', onDocClick);
    window.addEventListener('resize', emitStateDebounced);
    if (typeof ResizeObserver !== 'undefined' && document.body) {
      new ResizeObserver(emitStateDebounced).observe(document.body);
    }
  }

  // ===== 라이프사이클: load 시 ready 만 보낸다 =====
  function announce() { post('ready'); }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', announce);
  } else {
    announce();
  }
})();
