/* 지라봇 대시보드 — fetch + Chart.js 렌더링. 빌드체인 없는 바닐라 JS. */
const API = '/api/dashboard';
const charts = {};   // canvasId → Chart 인스턴스 (탭 재방문 시 destroy 후 재생성)
const loaded = {};   // 탭별 1회 로드 플래그 (새로고침 버튼/탭 재클릭 시 갱신)

const fmtDate = (iso) => iso ? new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-';
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
  const d = await get('/workload');
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
  const d = await get('/bugs?weeks=8');
  const ratio = d.totalCount > 0 ? Math.round(d.bugCount * 100 / d.totalCount) : 0;
  document.getElementById('bug-cards').innerHTML =
    card('전체 버그', d.bugCount) + card('미해결 버그', d.openBugCount, d.openBugCount > 0 ? 'red' : 'green')
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

  rows('table-issues', d.map(i =>
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
  const f = await get('/intent-failures?limit=50');
  rows('table-failures', f.map(x =>
    `<tr><td class="muted">${fmtDate(x.failedAt)}</td><td>${esc(x.errorType)}</td>` +
    `<td>${esc(x.rawInput)}</td><td class="muted">${esc(x.slackUserId ?? '')}</td></tr>`).join('')
    || '<tr><td colspan="4" class="muted">실패 기록 없음 🎉</td></tr>');
}

/* ---------- 탭 전환 / 이벤트 ---------- */

const LOADERS = { overview: loadOverview, sprint: loadSprint, trends: loadTrends,
  workload: loadWorkload, bugs: loadBugs, issues: loadIssues, users: loadUsers, bot: loadBot };

function showTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === 'panel-' + name));
  LOADERS[name]().catch(err => console.error(err));
}

document.querySelectorAll('.tab').forEach(t => t.onclick = () => showTab(t.dataset.tab));
document.getElementById('trend-weeks').onchange = loadTrends;
document.getElementById('btn-filter').onclick = loadIssues;
document.getElementById('f-q').addEventListener('keydown', e => { if (e.key === 'Enter') loadIssues(); });

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
