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

/** 앱이 보낸 본문을 검증해 정리한다. 잘못된 값은 버린다. 테스트하기 쉽게 따로 둔다. */
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
    let count = 0;
    for (const [name, hits] of Object.entries(d?.m && typeof d.m === "object" ? d.m : {})) {
      const label = String(name).trim().slice(0, 40);
      const n = Math.min(1000, Math.max(0, parseInt(hits, 10) || 0));
      if (!label || !n || ++count > 30) continue;
      menus[label] = n;
    }
    days.push({ day, menus });
  }
  return days.length ? { id, version, days } : null;
}

export async function usagePing(request, env) {
  if (!env.DB) return reply({ error: "통계 저장소(D1)가 연결되지 않았습니다" }, 503);
  const text = await request.text();
  if (text.length > 8192) return reply({ error: "too large" }, 413);
  let body;
  try { body = JSON.parse(text); } catch { return reply({ error: "bad json" }, 400); }
  const ping = sanitizePing(body);
  if (!ping) return reply({ error: "invalid" }, 400);
  await ensureSchema(env.DB);
  const uid = await sha256(`${ping.id}:${env.APP_TOKEN}`);
  const statements = [];
  for (const { day, menus } of ping.days) {
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
  const [daily, menu, menuUser, total] = await Promise.all([
    env.DB.prepare("SELECT day, uid, version FROM usage_daily WHERE day >= ?").bind(dayShift(from, -29)).all(),
    env.DB.prepare("SELECT day, menu, hits FROM usage_menu WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT day, menu, uid, hits FROM usage_menu_user WHERE day >= ?").bind(from).all(),
    env.DB.prepare(totalSql).first(),
  ]);
  return reply({ from, to, totalInstalls: total?.n ?? 0, ...summarize(daily.results ?? [], menu.results ?? [], from, to, menuUser.results ?? [], pf) });
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
<h1>FSS Insights 사용 통계</h1>
<div class="card"><input id="tok" type="password" placeholder="관리자 토큰" autocomplete="off"> <select id="pf"><option value="all">전체(앱+웹)</option><option value="app">앱만</option><option value="web">웹만</option></select> <select id="span"><option value="14">14일</option><option value="30" selected>30일</option><option value="90">90일</option></select> <button id="go">조회</button> <span id="msg" class="leg"></span></div>
<div id="out" hidden>
<div class="card"><div class="kpi"><div><b id="k1"></b><span>오늘 DAU</span></div><div><b id="k2"></b><span>최근 7일 WAU</span></div><div><b id="k3"></b><span>최근 30일 MAU</span></div><div><b id="k4"></b><span>누적 설치(고유)</span></div></div><div id="split" class="leg" style="margin-top:8px;text-align:center"></div></div>
<div class="card"><h2>일자별 활성사용자</h2><svg id="chart" viewBox="0 0 600 200" preserveAspectRatio="none"></svg><div class="leg"><b style="background:var(--a)"></b>DAU<b style="background:var(--b)"></b>WAU(7일)<b style="background:var(--c)"></b>MAU(30일)</div></div>
<div class="card"><h2>버전별 분포 (최근 7일 활성)</h2><div id="ver"></div></div>
<div class="card"><h2>메뉴별 사용자 수·열린 횟수</h2><table id="menu"></table><div class="leg">사용자 수는 조회 기간 중 그 메뉴를 연 서로 다른 설치 수입니다. 메뉴 기록은 사용자가 다음 날 앱을 열 때 올라오므로 오늘 것은 내일부터 보입니다.</div></div>
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
$("menu").innerHTML="<tr><th>메뉴</th><th>사용자</th><th>횟수</th>"+d.weekDays.map(x=>"<th>"+x.slice(5)+"</th>").join("")+"</tr>"+(ms.length?ms.map(([k,v])=>"<tr><td>"+esc(k)+"</td><td>"+v.users+"</td><td>"+v.hits+"</td>"+d.weekDays.map(x=>"<td>"+(v.daily[x]||"")+"</td>").join("")+"</tr>").join(""):"<tr><td colspan=10>자료 없음</td></tr>");
$("tbl").innerHTML="<tr><th>날짜</th><th>DAU</th><th>WAU</th><th>MAU</th></tr>"+s.slice().reverse().map(p=>"<tr><td>"+p.day+"</td><td>"+p.dau+"</td><td>"+p.wau+"</td><td>"+p.mau+"</td></tr>").join("")}
$("go").onclick=load;if($("tok").value)load();
</script></body></html>`;
