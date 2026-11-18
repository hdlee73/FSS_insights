// 서버(Worker) 호출 수 집계: 경로 종류별·출처(웹/앱)별 요청 수를 D1(binding: DB)에 시간 단위로 쌓고 /admin 화면에 보여 준다.
// Cloudflare 무료 요금제는 Worker 요청이 하루 10만 건이라, 어디서 많이 부르는지 보려는 용도다.
//  - 모든 요청을 센다(401·404·사전요청 포함). 관리자 화면 호출은 'admin', 나머지는 일반 방문 호출('normal')로 나눈다.
//  - D1 쓰기 한도(무료 하루 10만 행)를 아끼려고 요청마다 쓰지 않고, 인스턴스 메모리에 모았다가 FLUSH_MS 마다 한 번에 올린다.
//    인스턴스가 그 사이에 없어지면 마지막 몇 분 치는 빠질 수 있다(대략치).
//  - 시간 단위(UTC)로 저장하므로 UTC 날짜별·한국시간(+9시간) 날짜별 합계를 모두 계산할 수 있다.
//  - GET /admin/calls?days=7 : 일자별 합계(관리자 토큰 필요).

export const CATS = {
  admin: "관리자 화면·통계",
  usage: "사용 통계 신호(/usage/ping)",
  quote: "실시간 시세(/fetch 네이버·야후)",
  fetch: "기관·사이트 읽기(/fetch 기타)",
  news: "뉴스(/news)",
  stats: "거시 통계(/stats)",
  law: "법령(/law)",
  dart: "기업공시(/dart)",
  fisis: "금융회사(/fisis)",
  library: "자료실(/library)",
  notices: "공지(/notices)",
  board: "익명게시판(/board)",
  preflight: "사전요청(OPTIONS)",
  other: "그 밖(404 등)",
};
export const SOURCES = { web: "웹", app: "앱", other: "그 밖" };

const FLUSH_MS = 180000; // 3분마다 모아서 기록
const MAX_KEYS = 300;
const SCHEMA = "CREATE TABLE IF NOT EXISTS call_count (hour TEXT NOT NULL, cat TEXT NOT NULL, src TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (hour, cat, src))";

const QUOTE_HOSTS = /(^|\.)(stock\.naver\.com|finance\.yahoo\.com|finance\.naver\.com)$/;

/** 요청을 경로 종류로 나눈다. 순수 함수(시험용). */
export function categorize(method, pathname, searchParams) {
  if (method === "OPTIONS") return "preflight";
  if (pathname === "/admin" || pathname.startsWith("/admin/")) return "admin";
  if (pathname === "/usage/ping") return "usage";
  if (pathname === "/fetch") {
    try {
      return QUOTE_HOSTS.test(new URL(searchParams.get("url") || "").hostname) ? "quote" : "fetch";
    } catch {
      return "fetch";
    }
  }
  if (pathname === "/news") return "news";
  if (pathname === "/stats") return "stats";
  if (pathname === "/notices") return "notices";
  for (const [prefix, cat] of [["/law/", "law"], ["/dart/", "dart"], ["/fisis/", "fisis"], ["/board/", "board"]]) if (pathname.startsWith(prefix)) return cat;
  if (pathname.startsWith("/library")) return "library";
  return "other";
}

/** 출처: 허용 웹 주소에서 온 요청이면 web, 앱 토큰 머리글이 있으면 app, 아니면 other. */
export function sourceOf(origin, appToken, webOrigins) {
  const allowed = String(webOrigins || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (origin && allowed.includes(origin)) return "web";
  if (appToken) return "app";
  return "other";
}

/** UTC 시각(ms) → "YYYY-MM-DDTHH". */
export const hourKey = (ms) => new Date(ms).toISOString().slice(0, 13);

const pending = new Map(); // "hour|cat|src" -> hits
let lastFlush = 0; // 0: 이 인스턴스의 첫 요청은 바로 한 번 기록한다
let schemaReady = false;
let flushing = false;

/** 요청 하나를 센다. 오류가 나도 요청 처리에는 영향이 없다. */
export function recordCall(env, ctx, request, url, now = Date.now()) {
  try {
    if (!env.DB) return;
    const cat = categorize(request.method, url.pathname, url.searchParams);
    const src = sourceOf(request.headers.get("Origin"), request.headers.get("X-App-Token"), env.WEB_ORIGINS);
    const key = `${hourKey(now)}|${cat}|${src}`;
    if (pending.has(key) || pending.size < MAX_KEYS) pending.set(key, (pending.get(key) || 0) + 1);
    if (!flushing && now - lastFlush >= FLUSH_MS) ctx.waitUntil(flush(env, now));
  } catch {
    /* 집계 실패는 무시 */
  }
}

async function flush(env, now = Date.now()) {
  if (flushing || !pending.size) return;
  flushing = true;
  lastFlush = now;
  const batch = new Map(pending);
  pending.clear();
  try {
    if (!schemaReady) {
      await env.DB.prepare(SCHEMA).run();
      schemaReady = true;
    }
    const stmt = env.DB.prepare("INSERT INTO call_count (hour, cat, src, hits) VALUES (?, ?, ?, ?) ON CONFLICT(hour, cat, src) DO UPDATE SET hits = hits + excluded.hits");
    await env.DB.batch([...batch].map(([k, n]) => stmt.bind(...k.split("|"), n)));
  } catch {
    // 실패하면 다음 기록 때 다시 시도하도록 되돌린다(끝없이 쌓이지 않게 상한 안에서만).
    for (const [k, n] of batch) if (pending.size < MAX_KEYS || pending.has(k)) pending.set(k, (pending.get(k) || 0) + n);
  } finally {
    flushing = false;
  }
}

/** 시험용 */
export const _state = () => ({ pending: new Map(pending), lastFlush });
export function _reset() {
  pending.clear();
  lastFlush = 0;
  schemaReady = false;
  flushing = false;
}
export const _flush = flush;

const dayOf = (hour, offsetH) => new Date(Date.parse(hour + ":00:00Z") + offsetH * 3600000).toISOString().slice(0, 10);

/**
 * 시간 단위 행 → 일자별 합계. offsetH: 0=UTC 날짜, 9=한국 날짜. 최근 days 일(오늘 포함)을 최신순으로.
 * 각 날짜: total, admin, normal(관리자 외), bySrc{web,app,other}(일반 호출만), byCat{cat: {total, web, app, other}}.
 */
export function summarizeCalls(rows, offsetH, days, now = Date.now()) {
  const today = new Date(now + offsetH * 3600000).toISOString().slice(0, 10);
  const out = new Map();
  for (let i = 0; i < days; i++) {
    const d = new Date(Date.parse(today + "T00:00:00Z") - i * 86400000).toISOString().slice(0, 10);
    out.set(d, { day: d, total: 0, admin: 0, normal: 0, bySrc: { web: 0, app: 0, other: 0 }, byCat: {} });
  }
  for (const r of rows) {
    const e = out.get(dayOf(r.hour, offsetH));
    if (!e) continue;
    const n = Number(r.hits) || 0;
    const src = r.src in SOURCES ? r.src : "other";
    const cat = r.cat in CATS ? r.cat : "other";
    e.total += n;
    if (cat === "admin") e.admin += n;
    else {
      e.normal += n;
      e.bySrc[src] += n;
    }
    const c = (e.byCat[cat] ||= { total: 0, web: 0, app: 0, other: 0 });
    c.total += n;
    c[src] += n;
  }
  return [...out.values()];
}

export async function adminCalls(request, url, env) {
  const reply = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
  if (!env.ADMIN_TOKEN || request.headers.get("X-Admin-Token") !== env.ADMIN_TOKEN) return new Response("Unauthorized", { status: 401 });
  if (!env.DB) return reply({ error: "통계 저장소(D1)가 연결되지 않았습니다" }, 503);
  if (!schemaReady) {
    await env.DB.prepare(SCHEMA).run();
    schemaReady = true;
  }
  const days = Math.min(31, Math.max(1, parseInt(url.searchParams.get("days"), 10) || 7));
  const now = Date.now();
  const since = hourKey(now - (days + 1) * 86400000);
  const { results } = await env.DB.prepare("SELECT hour, cat, src, hits FROM call_count WHERE hour >= ?").bind(since).all();
  const rows = results ?? [];
  return reply({
    cats: CATS,
    sources: SOURCES,
    limit: 100000,
    kst: summarizeCalls(rows, 9, days, now),
    utc: summarizeCalls(rows, 0, days, now),
    // 이 인스턴스가 아직 기록하지 않은 건수(참고). 다른 인스턴스의 대기분은 알 수 없다.
    pendingHere: [...pending.values()].reduce((a, b) => a + b, 0),
  });
}

// ---- 관리자 화면 조각(usage-lib.js 의 PAGE 에 끼워 넣는다) ----
export const CALLS_CARD =
  '<div class="card"><h2>서버 호출 수 (Worker 요청, 경로별)</h2>' +
  '<div><select id="cz"><option value="kst">한국시간(KST) 날짜</option><option value="utc">UTC 날짜(Cloudflare 한도 기준)</option></select> <select id="cdays"><option value="7">7일</option><option value="14">14일</option><option value="31">31일</option></select></div>' +
  '<div id="ckpi" class="kpi" style="margin-top:8px"></div>' +
  '<div id="cbar" style="margin-top:8px"></div>' +
  '<div style="overflow-x:auto;margin-top:8px"><table id="ctbl" style="white-space:nowrap"></table></div>' +
  '<div class="leg" style="margin-top:6px">Cloudflare 무료 요금제 한도는 하루 100,000건(UTC 기준 초기화)입니다. <strong>일반 방문 호출</strong> = 앱·웹을 쓰는 사람의 요청(관리자 화면 제외), <strong>관리자</strong> = 이 통계 화면 자체의 조회입니다. 서버가 3분마다 모아서 기록하므로 최근 몇 분 치는 빠질 수 있고(대략치), 집계는 이 기능 배포 시점부터 쌓입니다. 웹/앱은 요청 출처로 구분하며 \'그 밖\'은 출처 표시가 없는 요청(관리자 화면·점검 등)입니다.</div></div>';

export const CALLS_SCRIPT = `<script>
(function(){
const $=i=>document.getElementById(i),esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const fmt=n=>Number(n).toLocaleString("ko-KR");
let D=null,sel=null;
function draw(){
  if(!D)return;
  const rows=D[$("cz").value],cats=D.cats;
  if(!sel||!rows.some(r=>r.day===sel))sel=rows[0].day;
  const t=rows[0],pct=Math.round(t.total/D.limit*1000)/10;
  $("ckpi").innerHTML='<div><b>'+fmt(t.total)+'</b><span>오늘 전체 ('+pct+'% / 10만)</span></div><div><b>'+fmt(t.normal)+'</b><span>일반 방문 호출</span></div><div><b>'+fmt(t.bySrc.web)+'</b><span>웹(일반)</span></div><div><b>'+fmt(t.bySrc.app)+'</b><span>앱(일반)</span></div><div style="grid-column:1/-1"><span>관리자 화면 호출 '+fmt(t.admin)+'건 · 출처 불명 일반 호출 '+fmt(t.bySrc.other)+'건</span></div>';
  const e=rows.find(r=>r.day===sel),list=Object.entries(e.byCat).sort((a,b)=>b[1].total-a[1].total),m=Math.max(1,...list.map(x=>x[1].total));
  $("cbar").innerHTML='<h2 style="margin:0 0 4px">경로별 — <select id="csel">'+rows.map(r=>'<option value="'+r.day+'"'+(r.day===sel?" selected":"")+'>'+r.day+'</option>').join("")+'</select></h2>'+(list.length?list.map(([k,v])=>'<div class="bar"><em style="min-width:190px">'+esc(cats[k]||k)+(k==="admin"?" ⚙":"")+'</em><i style="width:'+Math.round(v.total/m*40)+'%"></i>'+fmt(v.total)+' <span class="leg">웹 '+fmt(v.web)+' · 앱 '+fmt(v.app)+' · 그 밖 '+fmt(v.other)+'</span></div>').join(""):'<span class="leg">기록 없음</span>');
  $("csel").onchange=ev=>{sel=ev.target.value;draw()};
  $("ctbl").innerHTML='<tr><th>일자</th><th>전체</th><th>일반 방문</th><th>관리자</th><th>웹</th><th>앱</th><th>그 밖</th></tr>'+rows.map(r=>'<tr><td>'+r.day.slice(5)+'</td><td>'+fmt(r.total)+'</td><td>'+fmt(r.normal)+'</td><td>'+fmt(r.admin)+'</td><td>'+fmt(r.bySrc.web)+'</td><td>'+fmt(r.bySrc.app)+'</td><td>'+fmt(r.bySrc.other)+'</td></tr>').join("");
}
async function loadCalls(){
  const tok=$("tok").value;if(!tok)return;
  try{const r=await fetch("/admin/calls?days="+$("cdays").value,{headers:{"X-Admin-Token":tok}});
  if(!r.ok){$("cbar").innerHTML='<span class="leg">'+(r.status===401?"토큰이 맞지 않습니다":"호출 수를 불러오지 못했습니다("+r.status+")")+'</span>';return}
  D=await r.json();draw()}catch(e){$("cbar").innerHTML='<span class="leg">호출 수를 불러오지 못했습니다</span>'}
}
$("cz").onchange=draw;$("cdays").onchange=loadCalls;
$("go").addEventListener("click",loadCalls);
if($("tok").value)loadCalls();
})();
</script>`;
