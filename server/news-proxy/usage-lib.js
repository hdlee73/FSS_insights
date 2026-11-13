// 익명 사용 통계(일일 핑) 수집·집계. Cloudflare D1(binding: DB)에 저장한다.
//  - POST /usage/ping  : 앱이 하루 한 번 보낸다(앱 토큰 필요). 설치ID·버전·날짜·메뉴별 이용 횟수만 받는다.
//  - GET  /admin       : 관리자 통계 화면(HTML). 토큰을 입력하면 /admin/api 를 부른다.
//  - GET  /admin/api   : 일자별 활성사용자(DAU/WAU/MAU)·버전 분포·메뉴별 이용량(관리자 토큰 필요).
// 설치ID는 저장 전에 (설치ID + 서버 비밀값)으로 SHA-256 해시해서 원래 값을 복원할 수 없게 한다. IP·기기정보는 저장하지 않는다.

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const INSTALL_ID = /^[A-Za-z0-9-]{16,64}$/;
const SCHEMA = [
  "CREATE TABLE IF NOT EXISTS usage_daily (day TEXT NOT NULL, uid TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY (day, uid))",
  // 예전 합계 표(사용자 구분 없음). 새 기록은 usage_menu_user 에만 쌓고, 조회할 때 둘을 합쳐 횟수를 보여 준다.
  "CREATE TABLE IF NOT EXISTS usage_menu (day TEXT NOT NULL, menu TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, menu))",
  "CREATE TABLE IF NOT EXISTS usage_menu_user (day TEXT NOT NULL, menu TEXT NOT NULL, uid TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, menu, uid))",
  // 접속 환경(설치별 최신 값, 대분류만): 기기 종류·브라우저·운영체제·화면 크기·설치 여부
  "CREATE TABLE IF NOT EXISTS usage_env (uid TEXT PRIMARY KEY, day TEXT NOT NULL, dev TEXT, br TEXT, os TEXT, sc TEXT, inst TEXT)",
  // 시간대별 이용(메뉴를 연 시각의 '시'), 오류·실패 횟수
  "CREATE TABLE IF NOT EXISTS usage_hour (day TEXT NOT NULL, hour INTEGER NOT NULL, uid TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, hour, uid))",
  "CREATE TABLE IF NOT EXISTS usage_error (day TEXT NOT NULL, name TEXT NOT NULL, uid TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, name, uid))",
];
let schemaReady = false;

const reply = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });

async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch(SCHEMA.map((sql) => db.prepare(sql)));
  schemaReady = true;
}

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const dayShift = (day, delta) => new Date(Date.parse(`${day}T00:00:00Z`) + delta * 86400000).toISOString().slice(0, 10);

const ENV_FIELD = /[^0-9A-Za-z .+_\-]/g;
const envValue = (v) => String(v ?? "").replace(ENV_FIELD, "").trim().slice(0, 20);
const ERROR_NAME = /[^0-9A-Za-z:/._\-]/g;

/**
 * 앱·웹이 보낸 본문을 검증해 정리한다. 잘못된 값은 버린다. 테스트하기 쉽게 따로 둔다.
 * 날짜별 m(맵)에는 메뉴 횟수와 함께 특수 키가 섞여 올 수 있다: "~h:14"(14시에 메뉴를 연 횟수), "~e:이름"(오류·실패 횟수).
 */
export function sanitizePing(body, nowMs = Date.now()) {
  if (!body || typeof body !== "object") return null;
  const id = String(body.id ?? "");
  if (!INSTALL_ID.test(id)) return null;
  const version = String(body.v ?? "").slice(0, 20).replace(/[^0-9A-Za-z.\-+]/g, "") || "?";
  const today = new Date(nowMs).toISOString().slice(0, 10);
  const oldest = dayShift(today, -40);
  const newest = dayShift(today, 1);
  const days = [];
  for (const d of Array.isArray(body.days) ? body.days.slice(0, 10) : []) {
    const day = String(d?.d ?? "");
    if (!DAY.test(day) || day < oldest || day > newest) continue;
    const menus = {};
    const hours = {};
    const errors = {};
    let count = 0, errCount = 0;
    for (const [name, hits] of Object.entries(d?.m && typeof d.m === "object" ? d.m : {})) {
      const n = Math.min(1000, Math.max(0, parseInt(hits, 10) || 0));
      if (!n) continue;
      if (name.startsWith("~h:")) {
        const hour = parseInt(name.slice(3), 10);
        if (hour >= 0 && hour <= 23) hours[hour] = n;
        continue;
      }
      if (name.startsWith("~e:")) {
        const label = name.slice(3).replace(ERROR_NAME, "").slice(0, 40);
        if (label && ++errCount <= 30) errors[label] = n;
        continue;
      }
      const label = String(name).trim().slice(0, 40);
      if (!label || ++count > 30) continue;
      menus[label] = n;
    }
    days.push({ day, menus, hours, errors });
  }
  const e = body.env && typeof body.env === "object" ? body.env : null;
  const env = e ? { dev: envValue(e.dev), br: envValue(e.br), os: envValue(e.os), sc: envValue(e.sc), inst: envValue(e.inst) } : null;
  return days.length ? { id, version, days, env: env && Object.values(env).some(Boolean) ? env : null } : null;
}

export async function usagePing(request, env) {
  if (!env.DB) return reply({ error: "통계 저장소(D1)가 연결되지 않았습니다" }, 503);
  const text = await request.text();
  if (text.length > 16384) return reply({ error: "too large" }, 413);
  let body;
  try { body = JSON.parse(text); } catch { return reply({ error: "bad json" }, 400); }
  const ping = sanitizePing(body);
  if (!ping) return reply({ error: "invalid" }, 400);
  await ensureSchema(env.DB);
  const uid = await sha256(`${ping.id}:${env.APP_TOKEN}`);
  const statements = [];
  if (ping.env) {
    const today = ping.days.map((d) => d.day).sort().at(-1);
    statements.push(
      env.DB.prepare("INSERT INTO usage_env (uid, day, dev, br, os, sc, inst) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(uid) DO UPDATE SET day = excluded.day, dev = excluded.dev, br = excluded.br, os = excluded.os, sc = excluded.sc, inst = excluded.inst")
        .bind(uid, today, ping.env.dev, ping.env.br, ping.env.os, ping.env.sc, ping.env.inst)
    );
  }
  for (const { day, menus, hours, errors } of ping.days) {
    for (const [hour, hits] of Object.entries(hours)) {
      statements.push(
        env.DB.prepare("INSERT INTO usage_hour (day, hour, uid, hits) VALUES (?, ?, ?, ?) ON CONFLICT(day, hour, uid) DO UPDATE SET hits = hits + excluded.hits").bind(day, Number(hour), uid, hits)
      );
    }
    for (const [name, hits] of Object.entries(errors)) {
      statements.push(
        env.DB.prepare("INSERT INTO usage_error (day, name, uid, hits) VALUES (?, ?, ?, ?) ON CONFLICT(day, name, uid) DO UPDATE SET hits = hits + excluded.hits").bind(day, name, uid, hits)
      );
    }
    statements.push(
      env.DB.prepare("INSERT INTO usage_daily (day, uid, version) VALUES (?, ?, ?) ON CONFLICT(day, uid) DO UPDATE SET version = excluded.version").bind(day, uid, ping.version)
    );
    for (const [menu, hits] of Object.entries(menus)) {
      statements.push(
        env.DB.prepare("INSERT INTO usage_menu_user (day, menu, uid, hits) VALUES (?, ?, ?, ?) ON CONFLICT(day, menu, uid) DO UPDATE SET hits = hits + excluded.hits").bind(day, menu, uid, hits)
      );
    }
  }
  await env.DB.batch(statements);
  return reply({ ok: true });
}

/** 버전 이름이 'web-'으로 시작하면 웹, 아니면 앱. */
export const platformOf = (version) => (String(version ?? "").startsWith("web-") ? "web" : "app");
/** 일자별 DAU와 7일·30일 이동 활성사용자, 최근 7일 버전 분포, 기간 내 메뉴별 이용량. */
export function summarize(allRows, menuRows, from, to, menuUserRows = [], platform = "all") {
  // 오늘 기준 앱/웹 나눠 보기(전체 보기에서도 함께 보여 준다)
  const split = {};
  for (const pf of ["app", "web"]) {
    const part = allRows.filter((r) => platformOf(r.version) === pf);
    const per = (end, len) => {
      const set = new Set();
      for (const r of part) if (r.day <= end && r.day > dayShift(end, -len)) set.add(r.uid);
      return set.size;
    };
    split[pf] = { dau: per(to, 1), wau: per(to, 7), mau: per(to, 30) };
  }
  const rows = platform === "all" ? allRows : allRows.filter((r) => platformOf(r.version) === platform);
  const platformUids = new Set(rows.map((r) => r.uid));
  if (platform !== "all") {
    menuUserRows = menuUserRows.filter((m) => platformUids.has(m.uid));
    if (platform === "web") menuRows = []; // 예전 합계 표는 앱 시절 기록이라 웹에는 넣지 않는다
  }
  const byDay = new Map();
  const latestVersion = new Map();
  for (const r of rows) {
    if (!byDay.has(r.day)) byDay.set(r.day, new Set());
    byDay.get(r.day).add(r.uid);
  }
  const window = (end, len) => {
    const set = new Set();
    for (let i = 0; i < len; i++) for (const u of byDay.get(dayShift(end, -i)) ?? []) set.add(u);
    return set.size;
  };
  const series = [];
  for (let d = from; d <= to; d = dayShift(d, 1)) {
    series.push({ day: d, dau: byDay.get(d)?.size ?? 0, wau: window(d, 7), mau: window(d, 30) });
  }
  const weekStart = dayShift(to, -6);
  for (const r of [...rows].sort((a, b) => (a.day < b.day ? -1 : 1))) {
    if (r.day >= weekStart) latestVersion.set(r.uid, r.version);
  }
  const versions = {};
  for (const v of latestVersion.values()) versions[v] = (versions[v] ?? 0) + 1;
  // 메뉴별: 열린 횟수(hits), 기간 중 쓴 사용자 수(users), 최근 7일 일자별 사용자 수(daily).
  const menus = {};
  const entry = (name) => (menus[name] ??= { hits: 0, users: 0, daily: {} });
  const uniq = new Map();
  for (const m of menuRows) if (m.day >= from && m.day <= to) entry(m.menu).hits += m.hits;
  for (const m of menuUserRows) {
    if (m.day < from || m.day > to) continue;
    const e = entry(m.menu);
    e.hits += m.hits;
    if (!uniq.has(m.menu)) uniq.set(m.menu, new Set());
    uniq.get(m.menu).add(m.uid);
    if (m.day >= weekStart) e.daily[m.day] = (e.daily[m.day] ?? 0) + 1;
  }
  for (const [name, set] of uniq) menus[name].users = set.size;
  return { platform, split, series, versions, menus, weekDays: Array.from({ length: 7 }, (_, i) => dayShift(weekStart, i)) };
}


const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const tally = (map, key) => { if (key) map[key] = (map[key] ?? 0) + 1; };

/**
 * 세분화 통계. summarize 와 같은 플랫폼 필터(app·web·all)를 쓴다.
 * 신규/재방문, 재방문율(첫 사용 7일·30일 뒤에도 사용), 한 달 사용 일수 분포, 업데이트 반영률, 메뉴 조합·이전 기간 비교,
 * 접속 환경, 시간대·요일, 오류 현황.
 */
export function detail({ allRows, menuUserRows = [], prevMenuUserRows = [], hourRows = [], errorRows = [], envRows = [], from, to, span, platform = "all" }) {
  const rows = platform === "all" ? allRows : allRows.filter((r) => platformOf(r.version) === platform);
  const uids = new Set(rows.map((r) => r.uid));
  const inScope = (r) => uids.has(r.uid);

  // 설치별 첫 사용일과 사용한 날 목록
  const days = new Map();
  for (const r of rows) {
    if (!days.has(r.uid)) days.set(r.uid, new Set());
    days.get(r.uid).add(r.day);
  }
  const first = new Map();
  for (const [uid, set] of days) first.set(uid, [...set].sort()[0]);

  // 1. 신규 vs 재방문(일자별)
  const dau = new Map();
  for (const r of rows) dau.set(r.day, (dau.get(r.day) ?? 0) + 1);
  const newBy = new Map();
  for (const f of first.values()) newBy.set(f, (newBy.get(f) ?? 0) + 1);
  const newReturning = [];
  for (let d = from; d <= to; d = dayShift(d, 1)) {
    const total = dau.get(d) ?? 0;
    const n = Math.min(total, newBy.get(d) ?? 0);
    newReturning.push({ day: d, total, new: n, returning: total - n });
  }

  // 2. 재방문율: 첫 사용 N일 뒤(또는 이후)에도 다시 쓴 비율. 기준일이 아직 안 지난 사용자는 뺀다.
  const retention = {};
  for (const n of [7, 30]) {
    let base = 0, back = 0;
    for (const [uid, f] of first) {
      if (f > dayShift(to, -n)) continue;
      base++;
      if ([...days.get(uid)].some((d) => d >= dayShift(f, n))) back++;
    }
    retention[n] = { base, back, rate: base ? back / base : null };
  }
  // 첫 사용 주(월요일 시작)별 코호트: 크기와 7일 후 재방문
  const cohorts = {};
  for (const [uid, f] of first) {
    if (f < from) continue;
    const dt = new Date(`${f}T00:00:00Z`);
    const monday = dayShift(f, -((dt.getUTCDay() + 6) % 7));
    const c = (cohorts[monday] ??= { size: 0, base: 0, back: 0 });
    c.size++;
    if (f <= dayShift(to, -7)) {
      c.base++;
      if ([...days.get(uid)].some((d) => d >= dayShift(f, 7))) c.back++;
    }
  }

  // 3. 사용 빈도: 최근 30일 중 며칠 썼는지
  const freq = { "1일": 0, "2~5일": 0, "6~10일": 0, "11일 이상": 0 };
  const start30 = dayShift(to, -29);
  for (const set of days.values()) {
    const n = [...set].filter((d) => d >= start30 && d <= to).length;
    if (!n) continue;
    freq[n === 1 ? "1일" : n <= 5 ? "2~5일" : n <= 10 ? "6~10일" : "11일 이상"]++;
  }

  // 4. 메뉴 조합(같은 사용자가 기간 중 함께 쓴 메뉴 쌍)과 이전 기간 대비 사용자 수
  const userMenus = new Map();
  for (const m of menuUserRows) {
    if (!inScope(m) || m.day < from || m.day > to) continue;
    if (!userMenus.has(m.uid)) userMenus.set(m.uid, new Set());
    userMenus.get(m.uid).add(m.menu);
  }
  const pairs = {};
  for (const set of userMenus.values()) {
    const list = [...set].sort();
    for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length; j++) tally(pairs, `${list[i]} + ${list[j]}`);
  }
  const prevSets = new Map();
  for (const m of prevMenuUserRows) {
    if (!inScope(m)) continue;
    if (!prevSets.has(m.menu)) prevSets.set(m.menu, new Set());
    prevSets.get(m.menu).add(m.uid);
  }
  const prevMenuUsers = {};
  for (const [menu, set] of prevSets) prevMenuUsers[menu] = set.size;

  // 5. 업데이트 반영률: 최근 3개 버전의 최근 14일 일자별 사용자 비율
  const verFirst = new Map();
  for (const r of rows) if (!verFirst.has(r.version) || r.day < verFirst.get(r.version)) verFirst.set(r.version, r.day);
  const newest = [...verFirst.entries()].sort((a, b) => (a[1] < b[1] ? 1 : a[1] > b[1] ? -1 : 0)).slice(0, 3).map(([v]) => v);
  const adoptDays = Array.from({ length: 14 }, (_, i) => dayShift(to, i - 13));
  const adoption = { versions: newest.map((v) => ({ version: v, first: verFirst.get(v) })), days: adoptDays, total: adoptDays.map((d) => dau.get(d) ?? 0), data: {} };
  for (const v of newest) adoption.data[v] = adoptDays.map((d) => rows.filter((r) => r.day === d && r.version === v).length);

  // 6. 접속 환경: 기간 중 활동한 설치만
  const active = new Set(rows.filter((r) => r.day >= from && r.day <= to).map((r) => r.uid));
  const env = { dev: {}, br: {}, os: {}, sc: {}, inst: {}, unknown: 0 };
  const seenEnv = new Set();
  for (const e of envRows) {
    if (!active.has(e.uid)) continue;
    seenEnv.add(e.uid);
    for (const k of ["dev", "br", "os", "sc", "inst"]) tally(env[k], e[k]);
  }
  env.unknown = active.size - seenEnv.size;

  // 7. 시간대(메뉴를 연 시각, 사용자 기기 시계 기준)와 요일(평균 DAU)
  const hours = Array.from({ length: 24 }, () => ({ hits: 0, users: new Set() }));
  for (const h of hourRows) {
    if (!inScope(h) || h.day < from || h.day > to || h.hour < 0 || h.hour > 23) continue;
    hours[h.hour].hits += h.hits;
    hours[h.hour].users.add(h.uid);
  }
  const wd = WEEKDAYS.map(() => ({ sum: 0, n: 0 }));
  for (let d = from; d <= to; d = dayShift(d, 1)) {
    const w = new Date(`${d}T00:00:00Z`).getUTCDay();
    wd[w].sum += dau.get(d) ?? 0;
    wd[w].n++;
  }

  // 8. 오류·실패 현황: 이름별 횟수·영향 사용자·마지막 발생일
  const errors = {};
  const errUsers = new Map();
  for (const e of errorRows) {
    if (!inScope(e) || e.day < from || e.day > to) continue;
    const x = (errors[e.name] ??= { hits: 0, users: 0, last: e.day });
    x.hits += e.hits;
    if (e.day > x.last) x.last = e.day;
    if (!errUsers.has(e.name)) errUsers.set(e.name, new Set());
    errUsers.get(e.name).add(e.uid);
  }
  for (const [name, set] of errUsers) errors[name].users = set.size;

  return {
    newReturning,
    retention,
    cohorts: Object.entries(cohorts).sort().map(([week, c]) => ({ week, ...c, rate: c.base ? c.back / c.base : null })),
    freq,
    menuPairs: Object.entries(pairs).sort((a, b) => b[1] - a[1]).slice(0, 10),
    prevMenuUsers,
    adoption,
    env,
    hours: hours.map((h) => ({ hits: h.hits, users: h.users.size })),
    weekday: wd.map((w, i) => ({ label: WEEKDAYS[i], avg: w.n ? w.sum / w.n : 0 })),
    errors,
    activeUsers: active.size,
  };
}

export async function adminApi(request, url, env) {
  if (!env.ADMIN_TOKEN || request.headers.get("X-Admin-Token") !== env.ADMIN_TOKEN) return new Response("Unauthorized", { status: 401 });
  if (!env.DB) return reply({ error: "통계 저장소(D1)가 연결되지 않았습니다" }, 503);
  await ensureSchema(env.DB);
  const span = Math.min(120, Math.max(7, parseInt(url.searchParams.get("days"), 10) || 30));
  const to = new Date(Date.now() + 9 * 3600000).toISOString().slice(0, 10); // 한국 날짜 기준
  const from = dayShift(to, -(span - 1));
  const pf = ["app", "web"].includes(url.searchParams.get("platform")) ? url.searchParams.get("platform") : "all";
  const totalSql =
    pf === "web" ? "SELECT COUNT(DISTINCT uid) AS n FROM usage_daily WHERE version LIKE 'web-%'"
    : pf === "app" ? "SELECT COUNT(DISTINCT uid) AS n FROM usage_daily WHERE version NOT LIKE 'web-%'"
    : "SELECT COUNT(DISTINCT uid) AS n FROM usage_daily";
  const prevFrom = dayShift(from, -span);
  const [daily, menu, menuUser, total, prevMenuUser, hourRows, errorRows, envRows] = await Promise.all([
    env.DB.prepare("SELECT day, uid, version FROM usage_daily WHERE day >= ?").bind(dayShift(to, -400)).all(),
    env.DB.prepare("SELECT day, menu, hits FROM usage_menu WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT day, menu, uid, hits FROM usage_menu_user WHERE day >= ?").bind(from).all(),
    env.DB.prepare(totalSql).first(),
    env.DB.prepare("SELECT menu, uid FROM usage_menu_user WHERE day >= ? AND day < ? GROUP BY menu, uid").bind(prevFrom, from).all(),
    env.DB.prepare("SELECT day, hour, uid, hits FROM usage_hour WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT day, name, uid, hits FROM usage_error WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT uid, dev, br, os, sc, inst FROM usage_env").all(),
  ]);
  const allRows = daily.results ?? [];
  return reply({
    from, to, totalInstalls: total?.n ?? 0,
    ...summarize(allRows, menu.results ?? [], from, to, menuUser.results ?? [], pf),
    detail: detail({ allRows, menuUserRows: menuUser.results ?? [], prevMenuUserRows: prevMenuUser.results ?? [], hourRows: hourRows.results ?? [], errorRows: errorRows.results ?? [], envRows: envRows.results ?? [], from, to, span, platform: pf }),
  });
}

export function adminPage() {
  return new Response(PAGE, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

const PAGE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FSS Insights 사용 통계</title>
<style>
:root{--bg:#f6f8fa;--card:#fff;--fg:#1c2530;--mut:#6b7785;--a:#1b8a6b;--b:#e08a3c;--c:#4a6fd8;--ln:#e2e7ec}
@media(prefers-color-scheme:dark){:root{--bg:#12171d;--card:#1b222a;--fg:#e6ebf0;--mut:#93a0ad;--ln:#2a343e}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif}main{max-width:880px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:8px 0 12px}h2{font-size:15px;margin:0 0 8px}.card{background:var(--card);border-radius:14px;padding:14px;margin-bottom:12px;border:1px solid var(--ln)}
.kpi{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.kpi div{text-align:center}.kpi b{display:block;font-size:24px}.kpi span{color:var(--mut);font-size:12px}
input,select,button{font:inherit;padding:8px 10px;border-radius:8px;border:1px solid var(--ln);background:var(--card);color:var(--fg)}button{background:var(--a);color:#fff;border:0}
.bar{display:flex;align-items:center;gap:8px;margin:4px 0;font-size:13px}.bar i{display:block;height:10px;background:var(--a);border-radius:5px}.bar em{min-width:130px;font-style:normal}
svg{width:100%;height:200px}.leg{font-size:12px;color:var(--mut)}.leg b{display:inline-block;width:10px;height:10px;border-radius:3px;margin:0 4px 0 10px}
table{width:100%;border-collapse:collapse;font-size:13px}td,th{padding:4px 6px;border-bottom:1px solid var(--ln);text-align:right}td:first-child,th:first-child{text-align:left}
</style></head><body><main>
<h1>FSS Insights 사용 통계 <a class="leg" href="/admin/notices">· 공지사항 관리</a></h1>
<div class="card"><input id="tok" type="password" placeholder="관리자 토큰" autocomplete="off"> <select id="pf"><option value="all">전체(앱+웹)</option><option value="app">앱만</option><option value="web">웹만</option></select> <select id="span"><option value="14">14일</option><option value="30" selected>30일</option><option value="90">90일</option></select> <button id="go">조회</button> <span id="msg" class="leg"></span></div>
<div id="out" hidden>
<div class="card"><div class="kpi"><div><b id="k1"></b><span>오늘 DAU</span></div><div><b id="k2"></b><span>최근 7일 WAU</span></div><div><b id="k3"></b><span>최근 30일 MAU</span></div><div><b id="k4"></b><span>누적 설치(고유)</span></div></div><div id="split" class="leg" style="margin-top:8px;text-align:center"></div></div>
<div class="card"><h2>일자별 활성사용자</h2><svg id="chart" viewBox="0 0 600 200" preserveAspectRatio="none"></svg><div class="leg"><b style="background:var(--a)"></b>DAU<b style="background:var(--b)"></b>WAU(7일)<b style="background:var(--c)"></b>MAU(30일)</div></div>
<div class="card"><h2>버전별 분포 (최근 7일 활성)</h2><div id="ver"></div></div>
<div class="card"><h2>메뉴별 사용자 수·열린 횟수</h2><table id="menu"></table><div class="leg">사용자 수는 조회 기간 중 그 메뉴를 연 서로 다른 설치 수입니다. 메뉴 기록은 사용자가 다음 날 앱을 열 때 올라오므로 오늘 것은 내일부터 보입니다.</div></div>
<div class="card"><h2>신규 vs 재방문 (일자별 활성사용자)</h2><svg id="nr" viewBox="0 0 600 186"></svg><div class="leg"><b style="background:var(--c)"></b>신규(처음 나타난 날)<b style="background:var(--a)"></b>재방문 · 자료가 쌓이기 전부터 쓰던 사용자는 첫 기록일에 신규로 잡힙니다. 막대 위 숫자는 그날 활성사용자 수(명)이고, 막대를 가리키거나 누르면 날짜별 신규·재방문 인원이 나옵니다.</div><details style="margin-top:8px"><summary class="leg">날짜별 인원 표</summary><table id="nrt"></table></details></div>
<div class="card"><h2>재방문율 (첫 사용 뒤 다시 사용)</h2><div id="ret" class="kpi" style="grid-template-columns:repeat(2,1fr)"></div><table id="coh" style="margin-top:8px"></table><div class="leg">7일/30일 뒤 재방문 = 첫 사용일로부터 그 일수 이후에도 다시 쓴 비율(그 일수가 아직 지나지 않은 사용자는 제외).</div></div>
<div class="card"><h2>사용 빈도 (최근 30일 중 사용한 날 수)</h2><div id="freq"></div></div>
<div class="card"><h2>메뉴 조합 (기간 중 같은 사용자가 함께 쓴 메뉴 TOP 10)</h2><div id="pairs"></div></div>
<div class="card"><h2>업데이트 반영률 (최근 3개 버전, 최근 14일 활성사용자 중 비율)</h2><table id="adopt"></table></div>
<div class="card"><h2>접속 환경 (기간 중 활성 설치 기준)</h2><div id="envn" class="leg"></div><h2 style="margin-top:8px">기기</h2><div id="e_dev"></div><h2 style="margin-top:8px">브라우저</h2><div id="e_br"></div><h2 style="margin-top:8px">운영체제</h2><div id="e_os"></div><h2 style="margin-top:8px">화면 크기</h2><div id="e_sc"></div><h2 style="margin-top:8px">설치형 웹앱 여부</h2><div id="e_inst"></div></div>
<div class="card"><h2>시간대별 이용 (메뉴를 연 시각, 사용자 기기 시계 기준)</h2><div id="hrs"></div><h2 style="margin-top:8px">요일별 평균 활성사용자</h2><div id="wdy"></div><div class="leg">시간대·요일 기록은 이 기능이 배포된 뒤부터 쌓입니다.</div></div>
<div class="card"><h2>오류·실패 현황</h2><table id="errs"></table><div class="leg">api:/경로 = 서버 자료 불러오기 실패, fetch:이름 = 외부 사이트 직접 읽기 실패. 영향 사용자 수와 마지막 발생일을 함께 봅니다. 이 기능 배포 전 기록은 없습니다.</div></div>
<div class="card"><h2>일자별 표</h2><table id="tbl"></table></div>
</div></main>
<script>
const $=i=>document.getElementById(i);$("tok").value=sessionStorage.getItem("t")||"";
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
function bars(el,obj){const e=Object.entries(obj).sort((a,b)=>b[1]-a[1]);const m=Math.max(1,...e.map(x=>x[1]));el.innerHTML=e.length?e.map(([k,v])=>'<div class="bar"><em>'+esc(k)+'</em><i style="width:'+Math.round(v/m*55)+'%"></i>'+v+'</div>').join(""):'<span class="leg">자료 없음</span>'}
function line(s,key,color,max){const n=s.length;return '<polyline fill="none" stroke="'+color+'" stroke-width="2" points="'+s.map((p,i)=>(n>1?i/(n-1)*580+10:300)+","+(185-p[key]/max*170)).join(" ")+'"/>'}
async function load(){$("msg").textContent="불러오는 중…";sessionStorage.setItem("t",$("tok").value);
const r=await fetch("/admin/api?days="+$("span").value+"&platform="+$("pf").value,{headers:{"X-Admin-Token":$("tok").value}});
if(!r.ok){$("msg").textContent=r.status==401?"토큰이 맞지 않습니다":"오류 "+r.status;return}
const d=await r.json();$("msg").textContent=d.from+" ~ "+d.to;$("out").hidden=false;const s=d.series,l=s[s.length-1];
$("k1").textContent=l.dau;$("k2").textContent=l.wau;$("k3").textContent=l.mau;$("k4").textContent=d.totalInstalls;const sp=d.split;$("split").textContent="오늘 DAU 앱 "+sp.app.dau+" · 웹 "+sp.web.dau+"  /  7일 앱 "+sp.app.wau+" · 웹 "+sp.web.wau+"  /  30일 앱 "+sp.app.mau+" · 웹 "+sp.web.mau+" (한 사람이 앱과 웹을 모두 쓰면 양쪽에 1명씩 잡힙니다)";
const max=Math.max(1,...s.map(p=>p.mau));$("chart").innerHTML='<line x1="10" x2="590" y1="185" y2="185" stroke="currentColor" opacity=".2"/>'+line(s,"mau","var(--c)",max)+line(s,"wau","var(--b)",max)+line(s,"dau","var(--a)",max);
bars($("ver"),d.versions);
const ms=Object.entries(d.menus).sort((a,b)=>b[1].users-a[1].users||b[1].hits-a[1].hits);
$("menu").innerHTML="<tr><th>메뉴</th><th>사용자</th><th>이전 기간</th><th>횟수</th>"+d.weekDays.map(x=>"<th>"+x.slice(5)+"</th>").join("")+"</tr>"+(ms.length?ms.map(([k,v])=>"<tr><td>"+esc(k)+"</td><td>"+v.users+"</td><td>"+(d.detail.prevMenuUsers[k]??0)+"</td><td>"+v.hits+"</td>"+d.weekDays.map(x=>"<td>"+(v.daily[x]||"")+"</td>").join("")+"</tr>").join(""):"<tr><td colspan=11>자료 없음</td></tr>");

const D=d.detail;
(function(){const n=D.newReturning,N=n.length,m=Math.max(1,...n.map(x=>x.total)),W=600,L=26,B=170,H=B-22,w=(W-L-6)/N,bw=Math.max(1,w-2);
let g='<line x1="'+L+'" x2="'+W+'" y1="'+B+'" y2="'+B+'" stroke="currentColor" opacity=".25"/><text x="0" y="'+(B-H+4)+'" font-size="9" fill="currentColor" opacity=".6">'+m+'</text><text x="0" y="'+(B-1)+'" font-size="9" fill="currentColor" opacity=".6">0</text>';
const step=N>45?7:N>20?3:1,showNum=N<=31;
n.forEach((x,i)=>{const h1=x.returning/m*H,h2=x.new/m*H,xx=L+i*w;
g+='<g><title>'+x.day+' · 활성 '+x.total+'명 (신규 '+x.new+' · 재방문 '+x.returning+')</title><rect x="'+xx+'" width="'+bw+'" y="'+(B-h1)+'" height="'+h1+'" fill="var(--a)"/><rect x="'+xx+'" width="'+bw+'" y="'+(B-h1-h2)+'" height="'+h2+'" fill="var(--c)"/><rect x="'+xx+'" width="'+bw+'" y="0" height="'+B+'" fill="transparent"/></g>';
if(showNum&&x.total)g+='<text x="'+(xx+bw/2)+'" y="'+(B-h1-h2-2)+'" font-size="8" text-anchor="middle" fill="currentColor">'+x.total+'</text>';
if(i%step===0||i===N-1)g+='<text x="'+(xx+bw/2)+'" y="'+(B+11)+'" font-size="8" text-anchor="middle" fill="currentColor" opacity=".75">'+x.day.slice(5).replace("-","/")+'</text>'});
$("nr").setAttribute("viewBox","0 0 "+W+" "+(B+16));$("nr").style.height="auto";$("nr").innerHTML=g;
$("nrt").innerHTML="<tr><th>날짜</th><th>활성</th><th>신규</th><th>재방문</th></tr>"+n.slice().reverse().map(x=>"<tr><td>"+x.day+"</td><td>"+x.total+"</td><td>"+x.new+"</td><td>"+x.returning+"</td></tr>").join("")})();
const pc=v=>v==null?"-":Math.round(v*100)+"%";
$("ret").innerHTML=[7,30].map(n=>{const r=D.retention[n];return '<div><b>'+pc(r.rate)+'</b><span>'+n+'일 뒤 재방문 ('+r.back+'/'+r.base+'명)</span></div>'}).join("");
$("coh").innerHTML="<tr><th>첫 사용 주(월요일)</th><th>신규</th><th>7일 재방문</th></tr>"+(D.cohorts.length?D.cohorts.map(c=>"<tr><td>"+c.week+"</td><td>"+c.size+"</td><td>"+(c.base?pc(c.rate)+" ("+c.back+"/"+c.base+")":"-")+"</td></tr>").join(""):"<tr><td colspan=3>자료 없음</td></tr>");
bars($("freq"),D.freq);
bars($("pairs"),Object.fromEntries(D.menuPairs));
const av=D.adoption;$("adopt").innerHTML="<tr><th>버전(첫 사용일)</th>"+av.days.map(x=>"<th>"+x.slice(5)+"</th>").join("")+"</tr>"+av.versions.map(v=>"<tr><td>"+esc(v.version)+" ("+v.first.slice(5)+")</td>"+av.days.map((x,i)=>"<td>"+(av.total[i]?Math.round(av.data[v.version][i]/av.total[i]*100)+"%":"")+"</td>").join("")+"</tr>").join("")+"<tr><td>활성사용자</td>"+av.total.map(t=>"<td>"+t+"</td>").join("")+"</tr>";
$("envn").textContent="활성 설치 "+D.activeUsers+"곳 중 환경 정보가 아직 없는 설치 "+D.env.unknown+"곳(환경 전송 기능이 들어간 새 버전을 아직 안 쓴 경우)";
for(const k of ["dev","br","os","sc","inst"])bars($("e_"+k),D.env[k]);
const hm=Math.max(1,...D.hours.map(x=>x.hits));$("hrs").innerHTML=D.hours.map((x,i)=>'<div class="bar"><em>'+i+'시</em><i style="width:'+Math.round(x.hits/hm*55)+'%"></i>'+x.hits+(x.users?' <span class="leg">('+x.users+'명)</span>':'')+'</div>').join("");
const wm=Math.max(1,...D.weekday.map(x=>x.avg));$("wdy").innerHTML=D.weekday.map(x=>'<div class="bar"><em>'+x.label+'요일</em><i style="width:'+Math.round(x.avg/wm*55)+'%"></i>'+x.avg.toFixed(1)+'</div>').join("");
const er=Object.entries(D.errors).sort((a,b)=>b[1].hits-a[1].hits);
$("errs").innerHTML="<tr><th>이름</th><th>횟수</th><th>영향 사용자</th><th>마지막</th></tr>"+(er.length?er.map(([k,v])=>"<tr><td>"+esc(k)+"</td><td>"+v.hits+"</td><td>"+v.users+"</td><td>"+v.last.slice(5)+"</td></tr>").join(""):"<tr><td colspan=4>기록 없음</td></tr>");
$("tbl").innerHTML="<tr><th>날짜</th><th>DAU</th><th>WAU</th><th>MAU</th></tr>"+s.slice().reverse().map(p=>"<tr><td>"+p.day+"</td><td>"+p.dau+"</td><td>"+p.wau+"</td><td>"+p.mau+"</td></tr>").join("")}
$("go").onclick=load;if($("tok").value)load();
</script></body></html>`;
