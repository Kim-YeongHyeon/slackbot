/* 아티팩트 인라인 댓글 리뷰 — 사이드바+API+레이아웃 (v0.0.75). 바닐라 JS, 빌드체인 없음.
 * 이 페이지는 인증된 일반 origin — 유일한 보안 크리티컬 렌더링이라 사용자 텍스트는 전부 esc().
 * sandbox iframe(고유 origin) 안의 comment-agent.js 와 postMessage 로만 통신한다. */
(function () {
  'use strict';

  var NS = 'cmt';
  var esc = function (s) {
    return (s == null ? '' : String(s)).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  };
  var apiFetch = function (path, opts) { return fetch(new URL(path, location.origin), opts); };

  // ===== id 파싱 =====
  var params = new URLSearchParams(location.search);
  var raw = params.get('id');
  var id = /^\d+$/.test(raw || '') ? parseInt(raw, 10) : null;
  if (id == null) {
    document.body.innerHTML = '<div class="review-error">잘못된 링크입니다 — 아티팩트 id 가 없습니다.</div>';
    return;
  }

  var frame = document.getElementById('doc-frame');
  var rail = document.getElementById('rail');
  var titleEl = document.getElementById('doc-title');
  var authorDisplay = document.getElementById('author-display');
  var toggleBtn = document.getElementById('btn-toggle-resolved');

  var allComments = [];       // 서버 원본 (루트+대댓글, resolved 포함)
  var anchors = {};           // cid → {y, found} (에이전트가 준 지오메트리)
  var docHeight = 0;
  var showResolved = false;
  var activeCid = null;
  var pending = null;         // 작성 중 선택 {quote, prefix, suffix, y}
  var railTopOffset = 0;      // rail 의 문서상 top (스크롤 좌표 보정용)

  var author = '';
  try { author = localStorage.getItem('cmt_author') || ''; } catch (_) {}
  updateAuthorDisplay();
  function updateAuthorDisplay() {
    authorDisplay.textContent = author ? '작성자: ' + author : '';
  }

  // ===== iframe → 에이전트 통신 =====
  frame.src = '/artifacts/view/' + id + '/';
  // 상단 "원본 보기" — 댓글 UI 없는 순수 뷰어(새 탭). 리뷰가 기본 진입점 (v0.0.76).
  document.getElementById('btn-raw-view').href = '/artifacts/view/' + id + '/';

  function sendToFrame(type, extra) {
    if (!frame.contentWindow) return;
    var msg = { ns: NS, type: type };
    if (extra) for (var k in extra) msg[k] = extra[k];
    frame.contentWindow.postMessage(msg, '*');   // opaque origin 이라 '*' 불가피
  }
  function unresolvedRoots() {
    return allComments.filter(function (c) { return c.parentId == null && !c.resolved; })
      .map(function (c) { return { cid: c.id, quote: c.quote, prefix: c.prefix, suffix: c.suffix }; });
  }
  window.addEventListener('message', function (e) {
    if (e.source !== frame.contentWindow || !e.data || e.data.ns !== NS) return;
    var d = e.data;
    if (d.type === 'ready') {
      sendToFrame('init', { comments: unresolvedRoots() });
    } else if (d.type === 'state') {
      docHeight = d.docHeight || 0;
      frame.style.height = docHeight + 'px';
      anchors = {};
      (d.anchors || []).forEach(function (a) { anchors[a.cid] = { y: a.y, found: a.found }; });
      // 고아 판정은 에이전트의 state 가 도착해야 확정된다 — 렌더 시점(refetch 직후)엔 아직
      // 모르므로 여기서 기존 카드에 배지를 동적으로 토글한다 (전체 재렌더는 작성 중 텍스트를 날림).
      updateOrphanBadges();
      layout();
    } else if (d.type === 'selection') {
      pending = { quote: d.quote, prefix: d.prefix, suffix: d.suffix, y: d.y };
      showBubble(d.y);
    } else if (d.type === 'selection-cleared') {
      if (!composingSelection) { pending = null; hideBubble(); }
    } else if (d.type === 'highlight-click') {
      setActive(d.cid, true);
    }
  });

  // ===== 데이터 로드 =====
  function refetch() {
    return apiFetch('/api/artifacts/' + id + '/comments')
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        allComments = Array.isArray(list) ? list : [];
        sendToFrame('set-comments', { comments: unresolvedRoots() });
        render();
      });
  }
  // 제목: 목록 projection 에서 가져온다 (없으면 기본값 유지)
  apiFetch('/api/artifacts').then(function (r) { return r.ok ? r.json() : []; })
    .then(function (list) {
      var found = (list || []).filter(function (a) { return a.id === id; })[0];
      if (found) titleEl.textContent = found.title;
    }).catch(function () {});
  refetch().catch(function (e) {
    rail.innerHTML = '<div class="review-error">댓글을 불러오지 못했습니다: ' + esc(e.message) + '</div>';
  });

  // ===== 렌더링 =====
  var composingSelection = false;   // 새 선택 작성 카드가 열려있나
  function roots() {
    return allComments.filter(function (c) { return c.parentId == null; });
  }
  function repliesOf(rootId) {
    return allComments.filter(function (c) { return c.parentId === rootId; })
      .sort(function (a, b) { return a.id - b.id; });   // ASC — created 동률도 id 로 안정
  }
  function fmtDate(iso) {
    if (!iso) return '';
    var d = new Date(String(iso).replace(/(\.\d{3})\d+/, '$1'));
    if (isNaN(d.getTime())) return String(iso);
    var p = function (n) { return String(n).padStart(2, '0'); };
    return String(d.getFullYear()).slice(2) + '.' + p(d.getMonth() + 1) + '.' + p(d.getDate())
      + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function render() {
    rail.querySelectorAll('.cmt-card:not([data-compose])').forEach(function (el) { el.remove(); });
    roots().forEach(function (c) {
      if (c.resolved && !showResolved) return;
      rail.appendChild(buildCard(c));
    });
    updateOrphanBadges();   // state 가 이미 와 있는 경우(토글/재렌더) 배지 즉시 반영
    layout();
  }

  // 앵커 실패(고아) 배지 — state 도착 시마다 기존 카드에 토글 (카드 재생성 없이).
  function updateOrphanBadges() {
    rail.querySelectorAll('.cmt-card').forEach(function (el) {
      if (el.dataset.compose === '1') return;
      var a = anchors[el.dataset.cid];
      var orphan = !!(a && a.found === false);
      el.classList.toggle('orphan', orphan);
      var badge = el.querySelector('.cmt-badge');
      if (orphan && !badge) {
        var b = document.createElement('div');
        b.className = 'cmt-badge';
        b.textContent = '⚠ 원문 위치를 찾을 수 없음';
        el.insertBefore(b, el.firstChild);
      } else if (!orphan && badge) {
        badge.remove();
      }
    });
  }

  function buildCard(c) {
    var card = document.createElement('div');
    card.className = 'cmt-card' + (c.resolved ? ' resolved' : '');
    card.dataset.cid = c.id;

    var html = '';
    if (c.quote) {
      var q = c.quote.length > 80 ? c.quote.slice(0, 80) + '…' : c.quote;
      html += '<div class="cmt-quote">' + esc(q) + '</div>';
    }
    html += '<div class="cmt-body">' + esc(c.body) + '</div>';
    html += '<div class="cmt-by">' + esc(c.author || '익명') + ' · ' + esc(fmtDate(c.createdAt)) + '</div>';

    // 액션 버튼
    html += '<div class="cmt-actions">';
    if (c.resolved) {
      html += '<button class="btn small" data-unresolve="' + c.id + '">되돌리기</button>';
    } else {
      html += '<button class="btn small" data-resolve="' + c.id + '">완료</button>';
    }
    html += '<button class="btn small danger" data-del="' + c.id + '">삭제</button>';
    html += '</div>';

    // 대댓글 목록
    var replies = repliesOf(c.id);
    if (replies.length) {
      html += '<div class="cmt-replies">';
      replies.forEach(function (r) {
        html += '<div class="cmt-reply"><div class="cmt-body">' + esc(r.body) + '</div>'
          + '<div class="cmt-by">' + esc(r.author || '익명') + ' · ' + esc(fmtDate(r.createdAt)) + '</div></div>';
      });
      html += '</div>';
    }

    // 대댓글 작성기 (완료 안 된 것만)
    if (!c.resolved) {
      html += '<div class="cmt-compose">'
        + '<textarea rows="2" data-reply-body="' + c.id + '" placeholder="답글…"></textarea>'
        + '<div class="cmt-actions"><button class="btn small primary" data-reply-submit="' + c.id + '">등록</button></div>'
        + '</div>';
    }
    card.innerHTML = html;

    // 카드 클릭 → 활성화+스크롤 (버튼/입력 클릭은 제외)
    card.addEventListener('click', function (ev) {
      if (ev.target.closest('button, textarea, input')) return;
      setActive(c.id, true);
    });
    wireCardActions(card, c);
    return card;
  }

  function wireCardActions(card, c) {
    var q = function (sel) { return card.querySelector(sel); };
    var resolve = q('[data-resolve]');
    if (resolve) resolve.onclick = function () { patchResolved(c.id, true); };
    var unresolve = q('[data-unresolve]');
    if (unresolve) unresolve.onclick = function () { patchResolved(c.id, false); };
    var del = q('[data-del]');
    if (del) del.onclick = function () {
      if (!confirm('이 댓글을 삭제할까요? (대댓글도 함께 삭제됩니다)')) return;
      apiFetch('/api/artifacts/' + id + '/comments/' + c.id, { method: 'DELETE' })
        .then(function () { return refetch(); });
    };
    var submit = q('[data-reply-submit]');
    if (submit) submit.onclick = function () {
      var ta = q('[data-reply-body]');
      var text = ta.value.trim();
      if (!text) return;
      postComment({ body: text, author: author, parentId: c.id }).then(function () { return refetch(); });
    };
  }

  function patchResolved(cid, resolved) {
    apiFetch('/api/artifacts/' + id + '/comments/' + cid, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolved: resolved })
    }).then(function () { return refetch(); });
  }
  function postComment(payload) {
    return apiFetch('/api/artifacts/' + id + '/comments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
  }

  // ===== 활성 카드 + 스크롤 =====
  function setActive(cid, scroll) {
    activeCid = cid;
    sendToFrame('set-active', { cid: cid });
    rail.querySelectorAll('.cmt-card').forEach(function (el) {
      el.classList.toggle('active', el.dataset.cid == cid);
    });
    layout();
    if (scroll) {
      var a = anchors[cid];
      if (a && a.y != null) {
        window.scrollTo({ top: a.y + railTopOffset - 100, behavior: 'smooth' });
      }
    }
  }

  // ===== 레이아웃 (그리디 + 활성 카드 pull-up) =====
  function layout() {
    railTopOffset = rail.getBoundingClientRect().top + (window.scrollY || window.pageYOffset || 0);
    var cards = Array.prototype.slice.call(rail.querySelectorAll('.cmt-card'));
    // anchorY: 고아/미앵커는 Infinity (맨 뒤로). 작성 카드는 pending.y.
    var items = cards.map(function (el) {
      var cid = el.dataset.cid;
      var y;
      if (el.dataset.compose === '1') {
        y = pending ? pending.y : 0;
      } else {
        var a = anchors[cid];
        y = (a && a.y != null) ? a.y : Infinity;
      }
      return { el: el, y: y, h: el.offsetHeight, cid: cid };
    });
    items.sort(function (a, b) {
      if (a.y === b.y) return 0;
      return a.y < b.y ? -1 : 1;
    });

    var GAP = 8;
    // 활성 카드는 자기 앵커에 고정하고 위는 역방향 밀어올림·아래는 그리디 ("pull up").
    var activeIdx = -1;
    for (var i = 0; i < items.length; i++) {
      if ((activeCid != null && items[i].cid == activeCid) || items[i].el.dataset.compose === '1') {
        activeIdx = i;
      }
    }
    var tops = new Array(items.length);
    if (activeIdx >= 0 && isFinite(items[activeIdx].y)) {
      tops[activeIdx] = items[activeIdx].y;
      // 아래로 그리디
      var prevBottom = tops[activeIdx] + items[activeIdx].h;
      for (var j = activeIdx + 1; j < items.length; j++) {
        tops[j] = Math.max(items[j].y === Infinity ? prevBottom : items[j].y, prevBottom + GAP);
        prevBottom = tops[j] + items[j].h;
      }
      // 위로 역방향 밀어올림
      var nextTop = tops[activeIdx];
      for (var k = activeIdx - 1; k >= 0; k--) {
        tops[k] = Math.min(items[k].y, nextTop - GAP - items[k].h);
        nextTop = tops[k];
      }
    } else {
      var pb = 0;
      for (var m = 0; m < items.length; m++) {
        var base = items[m].y === Infinity ? pb : items[m].y;
        tops[m] = Math.max(base, pb + (m === 0 ? 0 : GAP));
        pb = tops[m] + items[m].h;
      }
    }

    var lastBottom = 0;
    items.forEach(function (it, i) {
      var t = Math.max(0, tops[i]);
      it.el.style.top = t + 'px';
      lastBottom = Math.max(lastBottom, t + it.h);
    });
    rail.style.height = Math.max(docHeight, lastBottom) + 'px';
  }

  // ===== 드래그 후 "댓글" 버블 + 작성 카드 =====
  var bubble = null;
  function showBubble(y) {
    if (composingSelection) return;
    hideBubble();
    bubble = document.createElement('button');
    bubble.className = 'cmt-bubble';
    bubble.textContent = '💬 댓글';
    bubble.style.top = Math.max(0, y - 10) + 'px';
    bubble.onclick = openComposer;
    rail.appendChild(bubble);
  }
  function hideBubble() { if (bubble) { bubble.remove(); bubble = null; } }

  function openComposer() {
    hideBubble();
    composingSelection = true;
    var card = document.createElement('div');
    card.className = 'cmt-card active';
    card.dataset.compose = '1';
    var quotePreview = pending && pending.quote
      ? '<div class="cmt-quote">' + esc(pending.quote.length > 80 ? pending.quote.slice(0, 80) + '…' : pending.quote) + '</div>' : '';
    card.innerHTML = quotePreview
      + '<div class="cmt-compose">'
      + '<input type="text" data-c-author placeholder="이름" value="' + esc(author) + '">'
      + '<textarea rows="3" data-c-body placeholder="댓글을 입력하세요…"></textarea>'
      + '<div class="cmt-actions">'
      + '<button class="btn small primary" data-c-submit>등록</button>'
      + '<button class="btn small" data-c-cancel>취소</button>'
      + '</div></div>';
    rail.appendChild(card);
    card.querySelector('[data-c-body]').focus();
    card.querySelector('[data-c-cancel]').onclick = closeComposer;
    card.querySelector('[data-c-submit]').onclick = function () {
      var body = card.querySelector('[data-c-body]').value.trim();
      if (!body) return;
      var name = card.querySelector('[data-c-author]').value.trim();
      author = name;
      try { localStorage.setItem('cmt_author', author); } catch (_) {}
      updateAuthorDisplay();
      postComment({
        body: body, author: author,
        quote: pending && pending.quote, prefix: pending && pending.prefix, suffix: pending && pending.suffix
      }).then(function () {
        closeComposer();
        sendToFrame('clear-selection');
        return refetch();
      });
    };
    layout();
  }
  function closeComposer() {
    composingSelection = false;
    pending = null;
    sendToFrame('clear-selection');
    var el = rail.querySelector('.cmt-card[data-compose="1"]');
    if (el) el.remove();
    layout();
  }

  // ===== 완료 댓글 토글 =====
  toggleBtn.onclick = function () {
    showResolved = !showResolved;
    toggleBtn.textContent = showResolved ? '완료된 댓글 숨기기' : '완료된 댓글 보기';
    render();
  };

  window.addEventListener('resize', layout);
})();
