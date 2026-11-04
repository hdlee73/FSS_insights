// 익명 사용 통계(일일 핑) 수집·집계. Cloudflare D1(binding: DB)에 저장한다.
//  - POST /usage/ping  : 앱이 하루 한 번 보낸다(앱 토큰 필요). 설치ID·버전·날짜·메뉴별 이용 횟수만 받는다.
//  - GET  /admin       : 관리자 통계 화면(HTML). 토큰을 입력하면 /admin/api 를 부른다.
//  - GET  /admin/api   : 일자별 활성사용자(DAU/WAU/MAU)·버전 분포·메뉴별 이용량(관리자 토큰 필요).
// 설치ID는 저장 전에 (설치ID + 서버 비밀값)으로 SHA-256 해시해서 원래 값을 복원할 수 없게 한다. IP·기기정보는 저장하지 않는다.

const DAY = /^\d{4}-\d{2}-\d{2}$/;
const INSTALL_ID = /^[A-Za-z0-9-]{16,64}$/;
const SCHEMA = [
  "CREATE TABLE IF NOT EXISTS usage_daily (day TEXT NOT NULL, uid TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY (day, uid))",
  "CREATE TABLE IF NOT EXISTS usage_menu (day TEXT NOT NULL, menu TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, menu))",
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
        env.DB.prepare("INSERT INTO usage_menu (day, menu, hits) VALUES (?, ?, ?) ON CONFLICT(day, menu) DO UPDATE SET hits = hits + excluded.hits").bind(day, menu, hits)
      );
    }
  }
  await env.DB.batch(statements);
  return reply({ ok: true });
}

/** 일자별 DAU와 7일·30일 이동 활성사용자, 최근 7일 버전 분포, 기간 내 메뉴별 이용량. */
export function summarize(rows, menuRows, from, to) {
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
  const menus = {};
  for (const m of menuRows) if (m.day >= from && m.day <= to) menus[m.menu] = (menus[m.menu] ?? 0) + m.hits;
  return { series, versions, menus };
}

export async function adminApi(request, url, env) {
  if (!env.ADMIN_TOKEN || request.headers.get("X-Admin-Token") !== env.ADMIN_TOKEN) return new Response("Unauthorized", { status: 401 });
  if (!env.DB) return reply({ error: "통계 저장소(D1)가 연결되지 않았습니다" }, 503);
  await ensureSchema(env.DB);
  const span = Math.min(120, Math.max(7, parseInt(url.searchParams.get("days"), 10) || 30));
  const to = new Date(Date.now() + 9 * 3600000).toISOString().slice(0, 10); // 한국 날짜 기준
  const from = dayShift(to, -(span - 1));
  const [daily, menu, total] = await Promise.all([
    env.DB.prepare("SELECT day, uid, version FROM usage_daily WHERE day >= ?").bind(dayShift(from, -29)).all(),
    env.DB.prepare("SELECT day, menu, hits FROM usage_menu WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT COUNT(DISTINCT uid) AS n FROM usage_daily").first(),
  ]);
  return reply({ from, to, totalInstalls: total?.n ?? 0, ...summarize(daily.results ?? [], menu.results ?? [], from, to) });
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
<div class="card"><input id="tok" type="password" placeholder="관리자 토큰" autocomplete="off"> <select id="span"><option value="14">14일</option><option value="30" selected>30일</option><option value="90">90일</option></select> <button id="go">조회</button> <span id="msg" class="leg"></span></div>
<div id="out" hidden>
<div class="card"><div class="kpi"><div><b id="k1"></b><span>오늘 DAU</span></div><div><b id="k2"></b><span>최근 7일 WAU</span></div><div><b id="k3"></b><span>최근 30일 MAU</span></div><div><b id="k4"></b><span>누적 설치(고유)</span></div></div></div>
<div class="card"><h2>일자별 활성사용자</h2><svg id="chart" viewBox="0 0 600 200" preserveAspectRatio="none"></svg><div class="leg"><b style="background:var(--a)"></b>DAU<b style="background:var(--b)"></b>WAU(7일)<b style="background:var(--c)"></b>MAU(30일)</div></div>
<div class="card"><h2>버전별 분포 (최근 7일 활성)</h2><div id="ver"></div></div>
<div class="card"><h2>메뉴별 이용 횟수</h2><div id="menu"></div></div>
<div class="card"><h2>일자별 표</h2><table id="tbl"></table></div>
</div></main>
<script>
const $=i=>document.getElementById(i);$("tok").value=sessionStorage.getItem("t")||"";
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
function bars(el,obj){const e=Object.entries(obj).sort((a,b)=>b[1]-a[1]);const m=Math.max(1,...e.map(x=>x[1]));el.innerHTML=e.length?e.map(([k,v])=>'<div class="bar"><em>'+esc(k)+'</em><i style="width:'+Math.round(v/m*55)+'%"></i>'+v+'</div>').join(""):'<span class="leg">자료 없음</span>'}
function line(s,key,color,max){const n=s.length;return '<polyline fill="none" stroke="'+color+'" stroke-width="2" points="'+s.map((p,i)=>(n>1?i/(n-1)*580+10:300)+","+(185-p[key]/max*170)).join(" ")+'"/>'}
async function load(){$("msg").textContent="불러오는 중…";sessionStorage.setItem("t",$("tok").value);
const r=await fetch("/admin/api?days="+$("span").value,{headers:{"X-Admin-Token":$("tok").value}});
if(!r.ok){$("msg").textContent=r.status==401?"토큰이 맞지 않습니다":"오류 "+r.status;return}
const d=await r.json();$("msg").textContent=d.from+" ~ "+d.to;$("out").hidden=false;const s=d.series,l=s[s.length-1];
$("k1").textContent=l.dau;$("k2").textContent=l.wau;$("k3").textContent=l.mau;$("k4").textContent=d.totalInstalls;
const max=Math.max(1,...s.map(p=>p.mau));$("chart").innerHTML='<line x1="10" x2="590" y1="185" y2="185" stroke="currentColor" opacity=".2"/>'+line(s,"mau","var(--c)",max)+line(s,"wau","var(--b)",max)+line(s,"dau","var(--a)",max);
bars($("ver"),d.versions);bars($("menu"),d.menus);
$("tbl").innerHTML="<tr><th>날짜</th><th>DAU</th><th>WAU</th><th>MAU</th></tr>"+s.slice().reverse().map(p=>"<tr><td>"+p.day+"</td><td>"+p.dau+"</td><td>"+p.wau+"</td><td>"+p.mau+"</td></tr>").join("")}
$("go").onclick=load;if($("tok").value)load();
</script></body></html>`;
