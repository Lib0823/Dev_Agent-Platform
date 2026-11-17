/* Dev Agent 공통 UI 스크립트 — 상단바(브랜드·페이지 탭·에이전트 상태·테마), 토스트, 포맷 유틸 */
window.UI = (() => {
  const $ = id => document.getElementById(id);
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const fmt = t => { if (!t) return ''; const d = new Date(t); return isNaN(d) ? String(t) : d.toLocaleString('ko-KR', {hour12:false, month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit'}); };
  const hhmm = t => { if (!t) return ''; const d = new Date(t); return isNaN(d) ? '' : d.toLocaleTimeString('ko-KR', {hour12:false, hour:'2-digit', minute:'2-digit', second:'2-digit'}); };
  const ago = t => { if (!t) return ''; const s = Math.max(0, (Date.now() - new Date(t).getTime()) / 1000); if (s < 60) return '방금'; if (s < 3600) return Math.floor(s/60)+'분 전'; if (s < 86400) return Math.floor(s/3600)+'시간 전'; return Math.floor(s/86400)+'일 전'; };
  const dur = ms => { if (ms == null || ms < 0) return ''; const s = Math.round(ms/1000); if (s < 60) return s+'s'; const m = Math.floor(s/60); return m < 60 ? m+'m '+String(s%60).padStart(2,'0')+'s' : Math.floor(m/60)+'h '+(m%60)+'m'; };
  const num = n => (n == null ? '—' : Number(n).toLocaleString('ko-KR'));

  const KOR = {NEW:'대기열', PREPARING:'선분석 중', READY:'설계 준비됨', ANALYZING:'분석 중', DESIGN_WAIT:'설계 확인 대기', FIXING:'수정 중', VERIFYING:'검증 중',
               REVIEW_WAIT:'리뷰 확인 대기', PUSHING:'푸시 중', DONE:'완료', ABORTED:'중단됨', FAILED:'실패'};
  const cls = st => st === 'DONE' ? 'ok' : (st === 'FAILED' || st === 'ABORTED') ? 'err' : (st === 'DESIGN_WAIT' || st === 'REVIEW_WAIT') ? 'wait'
                  : st === 'NEW' ? 'gray' : st === 'READY' ? 'info' : 'run';
  const isActive = st => !(st === 'DONE' || st === 'FAILED' || st === 'ABORTED');

  const I = {
    sun: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
    moon: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>',
    logo: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z"/><path d="M12 11v9M4 6.5l8 4.5 8-4.5"/></svg>',
    home: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-8 9 8v10a1 1 0 0 1-1 1h-5v-7h-6v7H4a1 1 0 0 1-1-1z"/></svg>',
    agent: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4M7 9h.01M12 9h.01M17 9h.01"/></svg>',
    ext: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3"/></svg>',
    arrow: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7"/></svg>',
    check: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
    x: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg>',
    send: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>',
    search: '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>',
    refresh: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-2.6-6.4"/><path d="M21 3v6h-6"/></svg>',
    warn: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg>',
    info: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v4M12 16h.01"/></svg>',
    plus: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
    terminal: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>',
    book: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20M4 19.5A2.5 2.5 0 0 0 6.5 22H20V2H6.5A2.5 2.5 0 0 0 4 4.5v15z"/></svg>',
    rooms: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
    clock: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
    pulse: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
    bell: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></svg>',
    branch: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="5" r="2.5"/><circle cx="6" cy="19" r="2.5"/><circle cx="18" cy="8" r="2.5"/><path d="M6 7.5v9M18 10.5c0 3-3 4-6 4.5-2 .3-3.5 1-3.5 2.5"/></svg>',
  };

  // ---------- theme ----------
  function applyTheme(t) {
    document.documentElement.dataset.theme = t;
    const b = $('themeBtn'); if (b) b.innerHTML = t === 'dark' ? I.sun : I.moon;
    try { localStorage.setItem('mb-theme', t); } catch (_) {}
  }
  function initTheme() {
    let t = 'dark';
    try { t = localStorage.getItem('mb-theme') || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'); } catch (_) {}
    document.documentElement.dataset.theme = t; return t;
  }
  const toggleTheme = () => applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
  initTheme();

  // ---------- toast ----------
  function toast(msg, kind) {
    let box = $('toasts'); if (!box) { box = document.createElement('div'); box.id = 'toasts'; box.className = 'toasts'; document.body.appendChild(box); }
    const t = document.createElement('div'); t.className = 'toast ' + (kind || ''); t.textContent = msg; box.appendChild(t);
    setTimeout(() => { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; setTimeout(() => t.remove(), 320); }, 4200);
    noticeAdd(msg, kind);
  }

  // ---------- 알림함(상단 종 버튼) ----------
  // 두 가지를 시간순으로 합쳐 보여 준다(메신저와 무관한 플랫폼 자체 알림):
  //  ① 화면 알림 — 잠깐 떴다 사라지는 토스트(재적재·저장·실패 등). 브라우저 localStorage 에 최근 NOTICE_MAX 건(화면 간 공유).
  //  ② 플랫폼 알림 — 이슈 에이전트·질의·시스템이 서버에 남긴 것(GET /api/notifications, 1분마다).
  // 읽음·비우기 시각도 브라우저가 기억한다. 저장소를 못 쓰면 이 화면의 메모리에만 남는다.
  const NOTICE_KEY = 'da-notices', NOTICE_MAX = 100;
  const PAGE_NAMES = { home: '홈', agent: '에이전트', research: '질의', messenger: '메신저', platform: '플랫폼' };
  const KIND_NAMES = { issue: '이슈 에이전트', research: '질의', system: '시스템' };
  let noticeMem = { seen: 0, cleared: 0, items: [] }, noticeSrv = [], noticePage = '', noticeFirst = true;
  function noticeLoad() {
    try { const d = JSON.parse(localStorage.getItem(NOTICE_KEY) || 'null'); if (d && Array.isArray(d.items)) noticeMem = { seen: d.seen || 0, cleared: d.cleared || 0, items: d.items }; } catch (_) {}
    return noticeMem;
  }
  function noticeSave(d) { noticeMem = d; try { localStorage.setItem(NOTICE_KEY, JSON.stringify(d)); } catch (_) {} }
  function noticeAdd(msg, kind) {
    if (msg == null || msg === '') return;
    const d = noticeLoad();
    d.items.unshift({ t: Date.now(), m: String(msg).slice(0, 500), k: kind || '', p: noticePage });
    if (d.items.length > NOTICE_MAX) d.items.length = NOTICE_MAX;
    if (noticeOpen()) d.seen = Date.now();
    noticeSave(d); noticeRender();
  }
  /** 화면 알림 + 플랫폼 알림을 최신순으로. */
  function noticeAll(d) {
    const srv = noticeSrv.filter(n => n.at > d.cleared).map(n => ({ t: n.at, m: n.title, b: n.body, k: n.level, link: n.link, src: KIND_NAMES[n.kind] || n.kind }));
    const loc = d.items.map(n => ({ t: n.t, m: n.m, k: n.k, src: n.p ? (PAGE_NAMES[n.p] || n.p) + ' 화면' : '' }));
    return srv.concat(loc).sort((a, b) => b.t - a.t);
  }
  const noticeOpen = () => { const p = $('noticePanel'); return !!p && p.classList.contains('open'); };
  function noticeRender() {
    const btn = $('noticeBtn'); if (!btn) return;
    const d = noticeLoad(), all = noticeAll(d), fresh = all.filter(n => n.t > d.seen);
    const hot = fresh.some(n => n.k === 'err') ? ' err' : fresh.some(n => n.k === 'wait' || n.k === 'warn') ? ' warn' : '';
    btn.innerHTML = I.bell + (fresh.length ? `<span class="nbadge${hot}">${fresh.length > 99 ? '99+' : fresh.length}</span>` : '');
    btn.title = fresh.length ? '새 알림 ' + fresh.length + '건 (Alt+N)' : '알림 (Alt+N)';
    const list = $('noticeList'); if (!list || !noticeOpen()) return;
    const seenAtOpen = Number(list.dataset.seen || 0);
    list.innerHTML = all.map(n => {
      const inner = `<span class="nd"></span><div class="nb"><div class="nm">${esc(n.m)}</div>${n.b ? `<div class="nx">${esc(n.b)}</div>` : ''}
        <div class="nt" title="${esc(new Date(n.t).toLocaleString('ko-KR', {hour12:false}))}">${ago(n.t)}${n.src ? ' · ' + esc(n.src) : ''}</div></div>`;
      const c = `nitem ${esc(n.k || '')}${n.t > seenAtOpen ? ' new' : ''}`;
      return n.link ? `<a class="${c}" href="${esc(n.link)}">${inner}</a>` : `<div class="${c}">${inner}</div>`;
    }).join('') || '<div class="nempty">알림이 없습니다.<br>에이전트·질의 진행 상황과 저장·재적재·실패 같은 화면 알림이 여기에 쌓입니다.</div>';
    $('noticeCount').textContent = all.length ? all.length + '건' : '';
  }
  function noticeToggle(force) {
    const p = $('noticePanel'); if (!p) return;
    const open = force == null ? !p.classList.contains('open') : force;
    if (open) {
      const d = noticeLoad();
      $('noticeList').dataset.seen = d.seen;   // 열기 전까지 안 읽었던 것을 강조
      d.seen = Date.now(); noticeSave(d);
    }
    p.classList.toggle('open', open);
    noticeRender();
  }
  function noticeClear() { const d = noticeLoad(); noticeSave({ seen: Date.now(), cleared: Date.now(), items: [] }); noticeRender(); }
  async function noticePoll() {
    try { noticeSrv = (await (await fetch('/api/notifications')).json()).items || []; } catch (_) { return; }
    const d = noticeLoad();
    if (noticeFirst && !d.seen) { d.seen = Date.now(); noticeSave(d); }   // 처음 쓰는 브라우저: 지난 알림을 새 알림으로 세지 않는다
    noticeFirst = false;
    if (noticeOpen()) { d.seen = Date.now(); noticeSave(d); }
    noticeRender();
  }
  function initNotices(active) {
    noticePage = active || '';
    $('noticeBtn').addEventListener('click', e => { e.stopPropagation(); noticeToggle(); });
    $('noticeClear').addEventListener('click', noticeClear);
    $('noticePanel').addEventListener('click', e => e.stopPropagation());
    document.addEventListener('click', () => { if (noticeOpen()) noticeToggle(false); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && noticeOpen()) noticeToggle(false); });
    window.addEventListener('storage', e => { if (e.key === NOTICE_KEY) noticeRender(); });   // 다른 탭에서 쌓인 알림
    noticeRender();
    noticePoll(); every(noticePoll, 60000);
  }

  // ---------- top bar ----------
  /**
   * 공통 상단바. active: 'home' | 'agent' | 'research' | 'messenger' | 'platform'. 두 페이지를 세그먼트 탭으로 오가며, 에이전트 상태 칩은 항상 노출(관리 페이지에서는 대시보드로 가는 링크).
   * 단축키: Alt+1 홈, Alt+2 에이전트, Alt+3 질의, Alt+4 메신저, Alt+5 플랫폼, Alt+T 테마, Alt+N 알림함.
   */
  function topbar(active, subtitle) {
    const el = $('topbar'); if (!el) return;
    el.className = 'top';
    el.innerHTML = `
      <a class="brand" href="/home.html" title="Dev Agent"><div class="logo">${I.logo}</div><div><div class="t">Dev Agent</div><div class="s" id="brandSub">${esc(subtitle || '에이전트 플랫폼')}</div></div></a>
      <nav class="nav">
        <a href="/home.html" class="${active==='home'?'on':''}">${I.home}홈 <span class="k">Alt+1</span></a>
        <a href="/agent.html" class="${active==='agent'?'on':''}">${I.agent}에이전트 <span class="k">Alt+2</span></a>
        <a href="/research.html" class="${active==='research'?'on':''}">${I.search}질의 <span class="k">Alt+3</span></a>
        <a href="/messenger.html" class="${active==='messenger'?'on':''}">${I.rooms}메신저 <span class="k">Alt+4</span></a>
        <a href="/platform.html" class="${active==='platform'?'on':''}">${I.branch}플랫폼 <span class="k">Alt+5</span></a>
      </nav>
      <div class="strip" id="strip">
        <a class="chip" id="agentChip" href="/agent.html" title="에이전트 대시보드로 이동"><span class="dot"></span><span>에이전트 상태 확인 중…</span></a>
      </div>
      <div class="right" id="topRight">
        <a class="ver" id="verTag" href="/platform.html" title="Dev Agent 버전 — 플랫폼 > 시스템"></a>
        <div class="notice-wrap">
          <button class="icon-btn" id="noticeBtn" title="알림 (Alt+N)"></button>
          <div class="notice-panel" id="noticePanel">
            <div class="nhead"><b>알림</b><span class="nc" id="noticeCount"></span><button class="btn sm" id="noticeClear">비우기</button></div>
            <div class="nlist" id="noticeList"></div>
          </div>
        </div>
        <button class="icon-btn" id="themeBtn" title="테마 전환 (Alt+T)"></button>
      </div>`;
    $('themeBtn').addEventListener('click', toggleTheme);
    initNotices(active);
    applyTheme(document.documentElement.dataset.theme);
    startUsageBattery();
    loadVersion();
    document.addEventListener('keydown', e => {
      if (!e.altKey || e.ctrlKey || e.metaKey) return;
      const go = { '1': '/home.html', '2': '/agent.html', '3': '/research.html', '4': '/messenger.html', '5': '/platform.html' }[e.key];
      if (go) { location.href = go; } else if (e.key.toLowerCase() === 't') { toggleTheme(); } else if (e.key.toLowerCase() === 'n') { e.preventDefault(); noticeToggle(); }
    });
  }

  /** 상단 에이전트 상태 칩 갱신. overview = GET /api/agent/overview 응답. */
  function setAgentChip(ov, {link = true} = {}) {
    const c = $('agentChip'); if (!c) return;
    let k = '', text;
    if (!ov) { text = '에이전트 상태 조회 실패'; k = 'err'; }
    else if (!ov.enabled) { text = '에이전트 비활성'; k = 'err'; }
    else if (ov.activeId == null) { text = ov.nextRef ? '에이전트 유휴 · 다음 ' + ov.nextRef : '에이전트 유휴'; }
    else {
      const n = (ov.activeIds || [ov.activeId]).length; const busy = ov.busyIds ? ov.busyIds.includes(ov.activeId) : ov.busy;
      k = cls(ov.activeState); text = (busy ? '작업 중 · ' : '') + (KOR[ov.activeState] || ov.activeState) + (ov.activeRef ? ' · ' + ov.activeRef : '') + (n > 1 ? ' 외 ' + (n - 1) + '건' : '');
    }
    c.className = 'chip ' + k;
    c.innerHTML = '<span class="dot"></span><span>' + esc(text) + '</span>' + (link ? '<span style="opacity:.6">' + I.arrow + '</span>' : '');
    if (!link) { c.removeAttribute('href'); c.style.cursor = 'default'; }
  }
  async function pollAgentChip() {
    try { const ov = await (await fetch('/api/agent/overview?limit=1')).json(); setAgentChip(ov); return ov; }
    catch (_) { setAgentChip(null); return null; }
  }

  // ---------- 버전 표시(배터리 왼쪽) ----------
  // 화면을 옮길 때마다 깜빡이지 않게 sessionStorage 값을 먼저 쓰고, 서버 값으로 갱신한다(재시작으로 버전이 바뀐 경우).
  async function loadVersion() {
    const el = $('verTag'); if (!el) return;
    const show = v => { if (v) el.textContent = 'v' + v; };
    try { show(sessionStorage.getItem('da-version')); } catch (_) {}
    try {
      const st = await (await fetch('/api/system/status')).json();
      show(st.version); try { sessionStorage.setItem('da-version', st.version || ''); } catch (_) {}
    } catch (_) {}
  }

  // ---------- Claude 사용량 배터리(상단바 오른쪽) ----------
  // 5시간 창 사용량 / 용량(한도에 걸린 순간의 사용량을 학습, 없으면 설정 예산). 남은 비율을 배터리처럼 채운다.
  const kfmt = n => n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e5 ? 0 : 1) + 'k' : String(n || 0);
  function renderBattery(d) {
    let b = $('usageBattery');
    if (!b) {
      const right = $('topRight'); if (!right) return;
      b = document.createElement('a'); b.id = 'usageBattery'; b.className = 'battery'; b.href = '/home.html';
      const v = $('verTag'); right.insertBefore(b, v ? v.nextSibling : right.firstChild);   // 버전 표시 오른쪽
    }
    if (!d) { b.className = 'battery err'; b.innerHTML = '<span class="bat"><i style="width:0"></i></span><span class="lbl">사용량 조회 실패</span>'; return; }
    const bu = d.budget || {}, lim = d.limit || {}, w = (d.windows || {})['5h'] || {}, plan = d.plan || null;
    const rem = Math.max(0, Math.min(100, bu.remainingPct == null ? 100 : bu.remainingPct));
    const k = lim.active ? 'err' : rem <= 15 ? 'err' : rem <= 40 ? 'warn' : 'ok';
    // 구독 API 를 못 읽어 토큰 합계로 추정한 값은 실제와 크게 다를 수 있다 → '~' 와 '추정' 을 붙이고 경고색을 쓰지 않는다
    const est = !plan && !lim.active, stale = plan && plan.stale;
    const label = lim.active ? '한도 · 리셋 ' + (lim.untilText || '') : est ? '~' + rem + '% 추정' : rem + '%' + (stale ? ' (이전 값)' : '');
    b.className = 'battery ' + (est ? 'ok' : k);
    const fh = plan && plan.fiveHour || null, sd = plan && plan.sevenDay || null;
    b.title = (lim.active ? '사용량 한도 도달 — 리셋 ' + (lim.untilText || '') + '\n' : '')
      + (fh ? '구독 사용률(실제) · 5시간 창 ' + Math.round(fh.percent) + '% (리셋 ' + (fh.resetsAtText || '') + ')'
              + (sd ? ' · 7일 창 ' + Math.round(sd.percent) + '% (리셋 ' + (sd.resetsAtText || '') + ')' : '') + '\n'
            : '추정치: 최근 5시간 ' + kfmt(bu.used || 0) + ' / 용량 ' + kfmt(bu.capacity || 0) + ' 토큰' + (bu.learned ? ' (한도 시점 학습)' : ' (설정 예산)') + (d.planError ? '\n구독 API 조회 실패: ' + d.planError : '') + '\n')
      + '봇 호출 토큰 · 5시간 ↑' + kfmt(w.in || 0) + ' ↓' + kfmt(w.out || 0) + ' (' + (w.calls || 0) + '회)'
      + ' · 24시간 ↑' + kfmt(((d.windows || {})['24h'] || {}).in || 0) + ' ↓' + kfmt(((d.windows || {})['24h'] || {}).out || 0)
      + '\n클릭: 홈';
    b.innerHTML = '<span class="bat"><i style="width:' + rem + '%"></i></span><span class="lbl">' + esc(label) + '</span>';
  }
  async function pollUsage() {
    try { renderBattery(await (await fetch('/api/usage')).json()); } catch (_) { renderBattery(null); }
  }
  function startUsageBattery() { pollUsage(); every(pollUsage, 60000); }

  /**
   * 주기 실행(setInterval 대체): 탭이 가려져 있으면 건너뛰고, 다시 보이면 밀린 것을 한 번 바로 실행한다.
   * 화면을 띄워 둔 채 다른 창에서 일할 때 봇·브라우저가 쓸데없이 조회하지 않게 한다.
   */
  const timers = [];
  function every(fn, ms) {
    const t = { fn, ms, last: Date.now() };
    timers.push(t);
    setInterval(() => { if (!document.hidden) { t.last = Date.now(); fn(); } }, ms);
  }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    const now = Date.now();
    timers.forEach(t => { if (now - t.last >= t.ms) { t.last = now; t.fn(); } });
  });

  return { $, esc, fmt, hhmm, ago, dur, num, KOR, cls, isActive, I, toast, every, topbar, setAgentChip, pollAgentChip, toggleTheme, applyTheme, startUsageBattery, renderBattery };
})();
