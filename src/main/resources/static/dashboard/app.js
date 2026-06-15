/* 지라봇 대시보드 — fetch + Chart.js 렌더링. 빌드체인 없는 바닐라 JS. */
const API = '/api/dashboard';
const charts = {};   // canvasId → Chart 인스턴스 (탭 재방문 시 destroy 후 재생성)
const loaded = {};   // 탭별 1회 로드 플래그 (새로고침 버튼/탭 재클릭 시 갱신)

// STUDY: Safari 호환. (1) Safari 의 Date 파서는 ISO 소수점 4자리+(마이크로/나노초)를 Invalid Date 로 본다
//        → 밀리초(3자리)로 잘라낸다. (2) toLocaleString 의 dateStyle/timeStyle 은 Safari 14.1 미만에서
//        RangeError 를 던진다 → 직접 포맷한다. 대시보드가 100% JS 렌더라 여기서 throw 하면 화면이 통째로 빈다.
function toDate(iso) {
  return new Date(String(iso == null ? '' : iso).replace(/(\.\d{3})\d+/, '$1'));
}
function fmtDate(iso) {
  if (!iso) return '-';
  const d = toDate(iso);
  if (isNaN(d.getTime())) return String(iso);
  const p = (n) => String(n).padStart(2, '0');
  return `${String(d.getFullYear()).slice(2)}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
const fmtDay = (d) => d ? d.slice(5).replace('-', '/') : '';
const esc = (s) => (s ?? '').toString().replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

async function get(path) {
  const res = await fetch(API + path);
  if (!res.ok) throw new Error(`${path} → HTTP ${res.status}`);
  return res.json();
}

function makeChart(id, config) {
  if (charts[id]) charts[id].destroy();
  charts[id] = new Chart(document.getElementById(id), config);
}

function rows(tableId, html) {
  document.querySelector(`#${tableId} tbody`).innerHTML = html;
}

function card(label, value, cls = '') {
  return `<div class="card"><div class="label">${esc(label)}</div><div class="value ${cls}">${value}</div></div>`;
}

const PALETTE = ['#4493f8', '#3fb950', '#d29922', '#f85149', '#a371f7', '#79c0ff', '#ffa657'];

/* ---------- 탭별 로더 ---------- */

async function loadOverview() {
  const s = await get('/summary');
  document.getElementById('last-sync').textContent = '마지막 동기화: ' + fmtDate(s.lastSyncAt);
  document.getElementById('kpi-cards').innerHTML =
    card('전체 이슈', s.totalIssues) +
    card('미해결', s.openIssues, 'yellow') +
    card('진행 중', s.inProgress) +
    card(`스프린트 완료율${s.sprintName ? ' · ' + esc(s.sprintName) : ''}`,
         `${s.sprintCompletionRate}% <span class="muted">(${s.sprintSpDone}/${s.sprintSpTotal} SP)</span>`,
         s.sprintCompletionRate >= 70 ? 'green' : '') +
    card('정체 이슈 (7일+)', s.staleCount, s.staleCount > 0 ? 'red' : 'green') +
    card('등록 사용자', s.mappedUsers);
}

async function loadSprint() {
  const d = await get('/sprint');
  document.getElementById('sprint-title').textContent = '스프린트: ' + (d.sprintName ?? '없음');
  makeChart('chart-sprint-status', {
    type: 'doughnut',
    data: { labels: d.statusSlices.map(x => x.status),
            datasets: [{ data: d.statusSlices.map(x => x.count), backgroundColor: PALETTE }] },
    options: { plugins: { legend: { position: 'bottom' } } }
  });
  makeChart('chart-sprint-assignee', {
    type: 'bar',
    data: { labels: d.assigneeLoads.map(x => x.assignee),
            datasets: [{ label: '미해결 SP', data: d.assigneeLoads.map(x => x.openSp),
                         backgroundColor: '#4493f8' }] },
    options: { plugins: { legend: { display: false } } }
  });
  rows('table-stale', d.staleIssues.map(i =>
    `<tr><td><a href="${i.url}" target="_blank">${i.key}</a></td><td>${esc(i.summary)}</td>` +
    `<td>${esc(i.assignee ?? '미배정')}</td><td>${i.storyPoint ?? '-'}</td></tr>`).join('')
    || '<tr><td colspan="4" class="muted">정체 이슈 없음 🎉</td></tr>');
}

async function loadTrends() {
  const weeks = document.getElementById('trend-weeks').value;
  const d = await get('/trends?weeks=' + weeks);
  makeChart('chart-created-resolved', {
    type: 'line',
    data: { labels: d.weekly.map(w => fmtDay(w.weekStart)),
            datasets: [
              { label: '생성', data: d.weekly.map(w => w.created), borderColor: '#f85149', tension: .3 },
              { label: '해결', data: d.weekly.map(w => w.resolved), borderColor: '#3fb950', tension: .3 }] },
    options: { plugins: { legend: { position: 'bottom' } } }
  });
  makeChart('chart-resolution', {
    type: 'bar',
    data: { labels: d.resolution.map(w => fmtDay(w.weekStart)),
            datasets: [{ label: '평균 소요(시간)', data: d.resolution.map(w => w.avgHours),
                         backgroundColor: '#a371f7' }] },
    options: { plugins: { legend: { display: false } } }
  });
}

async function loadWorkload() {
  const scope = document.getElementById('wl-scope').value;
  const d = await get('/workload?scope=' + scope);
  makeChart('chart-workload', {
    type: 'bar',
    data: { labels: d.map(x => x.assignee),
            datasets: [
              { label: '미해결 이슈', data: d.map(x => x.openCount), backgroundColor: '#4493f8' },
              { label: '정체', data: d.map(x => x.staleCount), backgroundColor: '#f85149' }] },
    options: { plugins: { legend: { position: 'bottom' } } }
  });
  rows('table-workload', d.map(x =>
    `<tr><td>${esc(x.assignee)}</td><td>${x.openCount}</td><td>${x.openSp}</td>` +
    `<td>${x.staleCount > 0 ? '⚠️ ' + x.staleCount : '-'}</td></tr>`).join(''));
}

async function loadBugs() {
  const scope = document.getElementById('bug-scope').value;
  const d = await get('/bugs?weeks=8&scope=' + scope);
  const ratio = d.totalCount > 0 ? Math.round(d.bugCount * 100 / d.totalCount) : 0;
  const bugLabel = scope === 'sprint' ? '스프린트 버그' : '버그 (로컬 동기화분)';
  document.getElementById('bug-cards').innerHTML =
    card(bugLabel, d.bugCount) + card('미해결 버그', d.openBugCount, d.openBugCount > 0 ? 'red' : 'green')
    + card('버그 비율', ratio + '%');
  makeChart('chart-bugs-weekly', {
    type: 'line',
    data: { labels: d.weekly.map(w => fmtDay(w.weekStart)),
            datasets: [
              { label: '발생', data: d.weekly.map(w => w.created), borderColor: '#f85149', tension: .3 },
              { label: '해결', data: d.weekly.map(w => w.resolved), borderColor: '#3fb950', tension: .3 }] },
    options: { plugins: { legend: { position: 'bottom' } } }
  });
  rows('table-open-bugs', d.openBugs.map(i =>
    `<tr><td><a href="${i.url}" target="_blank">${i.key}</a></td><td>${esc(i.summary)}</td>` +
    `<td>${esc(i.status)}</td><td>${esc(i.assignee ?? '미배정')}</td></tr>`).join('')
    || '<tr><td colspan="4" class="muted">미해결 버그 없음 🎉</td></tr>');
}

// 해결된 버그 — Jira 라이브 조회라 펼칠 때 1회 lazy 로드, 검색은 재조회.
let resolvedLoaded = false;
async function loadResolvedBugs() {
  const q = document.getElementById('resolved-q').value.trim();
  const msg = document.getElementById('resolved-msg');
  msg.className = 'msg'; msg.textContent = '불러오는 중… (Jira 조회, 잠시만요)';
  try {
    const d = await get('/bugs/resolved' + (q ? '?q=' + encodeURIComponent(q) : ''));
    rows('table-resolved-bugs', d.map(b =>
      `<tr><td><a href="${b.url}" target="_blank">${b.key}</a></td><td>${esc(b.summary)}</td>` +
      `<td>${esc(b.assignee ?? '미배정')}</td><td class="muted">${fmtDate(b.resolutionDate)}</td></tr>`).join('')
      || `<tr><td colspan="4" class="muted">${q ? '검색 결과 없음' : '해결된 버그 없음'}</td></tr>`);
    msg.className = 'msg'; msg.textContent = `${d.length}건${q ? ` (검색: ${esc(q)})` : ''}`;
  } catch (e) {
    msg.className = 'msg err'; msg.textContent = '조회 실패: ' + e.message;
  }
}

let prData = [];   // 마지막 fetch 결과 — 필터/정렬 변경 시 refetch 없이 재렌더

async function loadPrs() {
  const d = await get('/prs');
  const msg = document.getElementById('pr-msg');
  if (!d.enabled) {
    msg.className = 'msg warn';
    msg.textContent = 'GITHUB_BRANCH_TOKEN 이 설정되지 않아 PR 조회가 비활성 상태입니다.';
    document.getElementById('pr-cards').innerHTML = '';
    rows('table-prs', '');
    return;
  }
  if (d.inaccessibleRepos.length > 0) {
    msg.className = 'msg warn';
    msg.textContent = `⚠️ 접근 불가 repo ${d.inaccessibleRepos.length}개 — 토큰에 "Pull requests: Read" 권한을 추가하세요: ${d.inaccessibleRepos.join(', ')}`;
  } else {
    msg.className = 'msg'; msg.textContent = '';
  }
  prData = d.prs;
  fillSelect('pr-repo', [...new Set(prData.map(p => p.repo))].sort());
  fillSelect('pr-author', [...new Set(prData.map(p => p.author))].sort());
  renderPrs();
}

// 데이터로 옵션을 다시 채우되, 기존 선택값은 가능하면 유지한다.
function fillSelect(id, values) {
  const sel = document.getElementById(id);
  const prev = sel.value;
  while (sel.options.length > 1) sel.remove(1);   // 첫 옵션("전체")만 남김
  values.forEach(v => sel.add(new Option(v, v)));
  if (values.includes(prev)) sel.value = prev;
}

function renderPrs() {
  const repo = document.getElementById('pr-repo').value;
  const author = document.getElementById('pr-author').value;
  const [field, dir] = document.getElementById('pr-sort').value.split('-');
  const key = field === 'created' ? 'createdAt' : 'updatedAt';

  const list = prData
    .filter(p => !repo || p.repo === repo)
    .filter(p => !author || p.author === author)
    .sort((a, b) => (toDate(a[key]) - toDate(b[key])) * (dir === 'asc' ? 1 : -1));

  const drafts = list.filter(p => p.draft).length;
  const linked = list.filter(p => p.issueKey).length;
  document.getElementById('pr-cards').innerHTML =
    card('열린 PR', list.length === prData.length ? list.length : `${list.length} <span class="muted">/ ${prData.length}</span>`) +
    card('Draft', drafts) + card('Jira 연결됨', linked);
  rows('table-prs', list.map(p =>
    `<tr><td class="muted">${esc(p.repo)}</td>` +
    `<td><a href="${p.url}" target="_blank">#${p.number}</a> ${p.draft ? '<span class="badge todo">draft</span> ' : ''}${esc(p.title)}</td>` +
    `<td>${esc(p.author)}</td><td class="muted">${esc(p.branch)}</td>` +
    `<td>${p.issueKey
        ? `<a href="${p.issueUrl}" target="_blank">${p.issueKey}</a> ${p.issueStatus ? badge(p.issueStatus) : ''} ${p.issueSummary ? '<span class="muted">' + esc(p.issueSummary) + '</span>' : ''}`
        : '<span class="muted">-</span>'}</td>` +
    `<td>${esc(p.issueAssignee ?? '-')}</td>` +
    `<td class="muted">${fmtDate(p.createdAt)}</td>` +
    `<td class="muted">${fmtDate(p.updatedAt)}</td></tr>`).join('')
    || '<tr><td colspan="8" class="muted">조건에 맞는 PR 없음</td></tr>');
}

function badge(cat) {
  const cls = cat === '완료' ? 'done' : cat === '진행 중' ? 'progress' : 'todo';
  return `<span class="badge ${cls}">${esc(cat ?? '-')}</span>`;
}

async function loadIssues() {
  const p = new URLSearchParams();
  const status = document.getElementById('f-status').value;
  const assignee = document.getElementById('f-assignee').value;
  const type = document.getElementById('f-type').value;
  const q = document.getElementById('f-q').value;
  if (status) p.set('status', status);
  if (assignee) p.set('assignee', assignee);
  if (type) p.set('type', type);
  if (q) p.set('q', q);
  const d = await get('/issues?' + p.toString());

  // 필터 옵션 채우기 (최초 1회 — 현재 결과 기반)
  if (!loaded.issueFilters) {
    loaded.issueFilters = true;
    const assignees = [...new Set(d.map(i => i.assignee).filter(Boolean))].sort();
    const types = [...new Set(d.map(i => i.issueType).filter(Boolean))].sort();
    document.getElementById('f-assignee').innerHTML +=
      assignees.map(a => `<option>${esc(a)}</option>`).join('');
    document.getElementById('f-type').innerHTML +=
      types.map(t => `<option>${esc(t)}</option>`).join('');
  }

  issueData = d;
  renderIssues();
}

let issueData = [];
let issueKeyDir = null;   // null=서버순(최근 갱신), 'asc'|'desc'=키 정렬

// 키의 끝 숫자로 정렬(ES2-9 < ES2-10). 키가 같은 프로젝트라는 전제.
function issueKeyNum(k) {
  const m = /(\d+)\s*$/.exec(k || '');
  return m ? parseInt(m[1], 10) : 0;
}

function renderIssues() {
  let list = issueData.slice();
  if (issueKeyDir) {
    list.sort((a, b) => (issueKeyNum(a.key) - issueKeyNum(b.key)) * (issueKeyDir === 'asc' ? 1 : -1));
  }
  document.getElementById('issues-sort-key').textContent =
    '키 ' + (issueKeyDir === 'asc' ? '▲' : issueKeyDir === 'desc' ? '▼' : '⇅');
  rows('table-issues', list.map(i =>
    `<tr><td><a href="${i.url}" target="_blank">${i.key}</a></td><td>${esc(i.summary)}</td>` +
    `<td>${esc(i.issueType ?? '-')}</td><td>${badge(i.statusCategory)}</td>` +
    `<td>${esc(i.assignee ?? '미배정')}</td><td>${i.storyPoint ?? '-'}</td>` +
    `<td class="muted">${esc(i.sprintName ?? '백로그')}</td></tr>`).join('')
    || '<tr><td colspan="7" class="muted">결과 없음</td></tr>');
}

async function loadUsers() {
  const res = await fetch('/api/user-mappings');
  const d = await res.json();
  rows('table-users', d.map(u =>
    `<tr><td>${esc(u.slackDisplayName ?? '')} <span class="muted">${esc(u.slackUserId)}</span></td>` +
    `<td>${esc(u.jiraDisplayName)}</td>` +
    `<td class="muted">${u.jiraAccountId ? '✓' : '<span style="color:var(--red)">미해석</span>'}</td>` +
    `<td><button class="toggle" data-user="${esc(u.slackUserId)}" data-field="reminderEnabled" data-val="${!u.reminderEnabled}">${u.reminderEnabled ? '🔔' : '🔕'}</button></td>` +
    `<td><button class="toggle" data-user="${esc(u.slackUserId)}" data-field="assignDmEnabled" data-val="${!u.assignDmEnabled}">${u.assignDmEnabled ? '🔔' : '🔕'}</button></td>` +
    `<td><button class="btn danger" data-del="${esc(u.slackUserId)}">삭제</button></td></tr>`).join('')
    || '<tr><td colspan="6" class="muted">등록된 사용자 없음</td></tr>');

  document.querySelectorAll('#table-users .toggle').forEach(b => b.onclick = async () => {
    await fetch('/api/user-mappings/' + b.dataset.user, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ [b.dataset.field]: b.dataset.val === 'true' })
    });
    loadUsers();
  });
  document.querySelectorAll('#table-users [data-del]').forEach(b => b.onclick = async () => {
    if (!confirm(b.dataset.del + ' 매핑을 삭제할까요?')) return;
    await fetch('/api/user-mappings/' + b.dataset.del, { method: 'DELETE' });
    loadUsers();
  });
}

async function loadFeatures() {
  const res = await fetch('/api/feature-requests');
  const d = await res.json();
  rows('table-features', d.map(f =>
    `<tr${f.done ? ' style="opacity:.55"' : ''}>` +
    `<td>${f.done ? '<span class="badge done">완료</span>' : '<span class="badge todo">대기</span>'}</td>` +
    `<td>${f.done ? '<s>' + esc(f.title) + '</s>' : esc(f.title)}</td>` +
    `<td class="muted" style="white-space:pre-wrap">${esc(f.content ?? '')}</td>` +
    `<td>${esc(f.author ?? '익명')}</td>` +
    `<td class="muted">${fmtDate(f.createdAt)}</td>` +
    `<td class="muted">${f.completedAt ? fmtDate(f.completedAt) : '-'}</td>` +
    `<td><button class="btn" data-fr="${f.id}" data-done="${!f.done}">${f.done ? '되돌리기' : '✅ 완료'}</button></td></tr>`).join('')
    || '<tr><td colspan="7" class="muted">아직 요청이 없습니다 — 첫 기능을 제안해보세요!</td></tr>');

  document.querySelectorAll('#table-features [data-fr]').forEach(b => b.onclick = async () => {
    await fetch('/api/feature-requests/' + b.dataset.fr, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ done: b.dataset.done === 'true' })
    });
    loadFeatures();
  });
}

async function addFeature() {
  const msg = document.getElementById('fr-msg');
  const title = document.getElementById('fr-title').value.trim();
  if (!title) { msg.className = 'msg warn'; msg.textContent = '제목을 입력해주세요.'; return; }
  const btn = document.getElementById('btn-fr-add');
  btn.disabled = true;
  try {
    const res = await fetch('/api/feature-requests', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title,
        content: document.getElementById('fr-content').value.trim(),
        author: document.getElementById('fr-author').value.trim()
      })
    });
    if (!res.ok) {
      const e = await res.json().catch(() => ({}));
      msg.className = 'msg warn'; msg.textContent = '등록 실패: ' + (e.error ?? res.status);
      return;
    }
    msg.className = 'msg ok'; msg.textContent = '등록되었습니다 — 관리자에게 DM이 전송됐어요.';
    document.getElementById('fr-title').value = '';
    document.getElementById('fr-content').value = '';
    loadFeatures();
  } finally {
    btn.disabled = false;
  }
}

async function loadBot() {
  let health = '🔴 DOWN';
  try {
    const h = await (await fetch('/actuator/health')).json();
    if (h.status === 'UP') health = '🟢 UP';
  } catch (e) { /* keep DOWN */ }
  const s = await get('/summary');
  document.getElementById('bot-cards').innerHTML =
    card('서버 상태', health) +
    card('마지막 동기화', `<span style="font-size:15px">${fmtDate(s.lastSyncAt)}</span>`) +
    card('등록 사용자', s.mappedUsers);
  const m = await get('/response-metrics');
  const sec = ms => ms == null ? '-' : (ms / 1000).toFixed(1) + 's';
  document.getElementById('metric-cards').innerHTML =
    card('7일 건수', `${m.weekly.count}${m.weekly.failCount ? ` <span style="font-size:13px">(실패 ${m.weekly.failCount})</span>` : ''}`) +
    card('평균', sec(m.weekly.avgMs)) +
    card('p50', sec(m.weekly.p50Ms)) +
    card('p95', sec(m.weekly.p95Ms)) +
    card('최대', sec(m.weekly.maxMs));
  rows('table-metrics', m.recent.map(x =>
    `<tr><td class="muted">${fmtDate(x.startedAt)}</td><td>${esc(x.issueKey ?? '-')}</td>` +
    `<td><b>${sec(x.totalMs)}</b></td><td>${sec(x.classifyMs)}</td><td>${sec(x.duplicateMs)}</td>` +
    `<td>${sec(x.jiraMs)}</td><td>${sec(x.dbMs)}</td><td>${sec(x.notifyMs)}</td>` +
    `<td>${x.success ? '✅' : '❌ ' + esc(x.errorType ?? '')}</td></tr>`).join('')
    || '<tr><td colspan="9" class="muted">계측 기록 없음 (이슈 생성 시 자동 적재)</td></tr>');
  const f = await get('/intent-failures?limit=50');
  rows('table-failures', f.map(x =>
    `<tr><td class="muted">${fmtDate(x.failedAt)}</td><td>${esc(x.errorType)}</td>` +
    `<td>${esc(x.rawInput)}</td><td class="muted">${esc(x.slackUserId ?? '')}</td></tr>`).join('')
    || '<tr><td colspan="4" class="muted">실패 기록 없음 🎉</td></tr>');
}

/* ---------- 탭 전환 / 이벤트 ---------- */

const LOADERS = { overview: loadOverview, sprint: loadSprint, trends: loadTrends,
  workload: loadWorkload, bugs: loadBugs, prs: loadPrs, issues: loadIssues,
  users: loadUsers, bot: loadBot, features: loadFeatures };

function showTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === 'panel-' + name));
  LOADERS[name]().catch(err => console.error(err));
}

document.querySelectorAll('.tab').forEach(t => t.onclick = () => showTab(t.dataset.tab));
document.getElementById('trend-weeks').onchange = loadTrends;
['pr-repo', 'pr-author', 'pr-sort'].forEach(id =>
  document.getElementById(id).onchange = renderPrs);
document.getElementById('btn-fr-add').onclick = addFeature;
document.getElementById('btn-filter').onclick = loadIssues;
document.getElementById('f-q').addEventListener('keydown', e => { if (e.key === 'Enter') loadIssues(); });
document.getElementById('wl-scope').onchange = loadWorkload;
document.getElementById('bug-scope').onchange = loadBugs;
document.getElementById('issues-sort-key').onclick = () => {
  issueKeyDir = issueKeyDir === 'asc' ? 'desc' : 'asc';
  renderIssues();
};
document.getElementById('btn-resolved-toggle').onclick = () => {
  const wrap = document.getElementById('resolved-wrap');
  const opening = wrap.style.display === 'none';
  wrap.style.display = opening ? '' : 'none';
  document.getElementById('btn-resolved-toggle').textContent = opening ? '접기 ▲' : '펼치기 ▼';
  if (opening && !resolvedLoaded) { resolvedLoaded = true; loadResolvedBugs(); }
};
document.getElementById('btn-resolved-search').onclick = loadResolvedBugs;
document.getElementById('resolved-q').addEventListener('keydown', e => { if (e.key === 'Enter') loadResolvedBugs(); });
document.getElementById('btn-import-pr').onclick = async () => {
  const url = document.getElementById('import-pr-url').value.trim();
  const msg = document.getElementById('import-pr-msg');
  if (!url) { msg.className = 'msg warn'; msg.textContent = 'PR URL을 입력해주세요.'; return; }
  const btn = document.getElementById('btn-import-pr');
  btn.disabled = true; btn.textContent = '분석 중…';
  msg.className = 'msg'; msg.textContent = 'PR 분석 중… (GitHub 조회 + 내용 분석 + Jira 전환, 잠시만요)';
  try {
    const res = await fetch(API + '/actions/import-pr', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    });
    const d = await res.json();
    if (!d.success) {
      msg.className = 'msg err'; msg.textContent = '실패: ' + (d.message || res.status);
    } else {
      msg.className = 'msg ok';
      msg.innerHTML = `✅ <a href="${d.issueUrl}" target="_blank">${esc(d.issueKey)}</a> 등록 — 영업일 ${d.businessDays.toFixed(1)}일 → SP ${d.storyPoint}, 상태 <b>${esc(d.finalStatus)}</b>, 보고자/담당자 <b>${esc(d.assignee || '미지정')}</b> (현재 스프린트)`;
      document.getElementById('import-pr-url').value = '';
      loadPrs();
    }
  } catch (e) {
    msg.className = 'msg err'; msg.textContent = '오류: ' + e.message;
  } finally {
    btn.disabled = false; btn.textContent = 'PR → 티켓 등록';
  }
};
document.getElementById('btn-backfill').onclick = async () => {
  const btn = document.getElementById('btn-backfill');
  const msg = document.getElementById('trend-msg');
  btn.disabled = true; btn.textContent = '백필 중… (Jira 전체 조회)';
  try {
    const res = await fetch(API + '/actions/backfill-history', { method: 'POST' });
    const d = await res.json();
    msg.className = 'msg ok'; msg.textContent = d.result;
    loadTrends();
  } catch (e) {
    msg.className = 'msg err'; msg.textContent = '백필 실패: ' + e.message;
  } finally {
    btn.disabled = false; btn.textContent = '히스토리 백필';
  }
};

document.getElementById('btn-sync').onclick = async () => {
  const btn = document.getElementById('btn-sync');
  btn.disabled = true; btn.textContent = '동기화 중…';
  try {
    const res = await fetch(API + '/actions/sync', { method: 'POST' });
    const d = await res.json();
    alert(d.result);
    showTab(document.querySelector('.tab.active').dataset.tab);
    loadOverview();
  } catch (e) {
    alert('동기화 실패: ' + e.message);
  } finally {
    btn.disabled = false; btn.textContent = '지금 동기화';
  }
};

document.getElementById('btn-user-add').onclick = async () => {
  const slackUserId = document.getElementById('u-slack').value.trim();
  const jiraDisplayName = document.getElementById('u-jira').value.trim();
  const msg = document.getElementById('user-msg');
  if (!slackUserId || !jiraDisplayName) {
    msg.className = 'msg err'; msg.textContent = 'Slack ID와 Jira 이름을 모두 입력하세요.'; return;
  }
  const res = await fetch('/api/user-mappings', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slackUserId, jiraDisplayName })
  });
  const d = await res.json();
  if (!res.ok) { msg.className = 'msg err'; msg.textContent = d.error ?? '등록 실패'; return; }
  if (d.warning) { msg.className = 'msg warn'; msg.textContent = d.warning; }
  else { msg.className = 'msg ok'; msg.textContent = `${d.status === 'created' ? '등록' : '갱신'} 완료 (accountId 해석됨)`; }
  document.getElementById('u-slack').value = ''; document.getElementById('u-jira').value = '';
  loadUsers();
};

/* 초기 로드 */
loadOverview().catch(err => console.error(err));
