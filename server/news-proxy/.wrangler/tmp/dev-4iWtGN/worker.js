var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// fisis-data.json
var fisis_data_default = { sectors: {} };

// usage-lib.js
var DAY = /^\d{4}-\d{2}-\d{2}$/;
var INSTALL_ID = /^[A-Za-z0-9-]{16,64}$/;
var SCHEMA = [
  "CREATE TABLE IF NOT EXISTS usage_daily (day TEXT NOT NULL, uid TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY (day, uid))",
  // 예전 합계 표(사용자 구분 없음). 새 기록은 usage_menu_user 에만 쌓고, 조회할 때 둘을 합쳐 횟수를 보여 준다.
  "CREATE TABLE IF NOT EXISTS usage_menu (day TEXT NOT NULL, menu TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, menu))",
  "CREATE TABLE IF NOT EXISTS usage_menu_user (day TEXT NOT NULL, menu TEXT NOT NULL, uid TEXT NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY (day, menu, uid))"
];
var schemaReady = false;
var reply = /* @__PURE__ */ __name((data, status = 200) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } }), "reply");
async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch(SCHEMA.map((sql) => db.prepare(sql)));
  schemaReady = true;
}
__name(ensureSchema, "ensureSchema");
async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(sha256, "sha256");
var dayShift = /* @__PURE__ */ __name((day, delta) => new Date(Date.parse(`${day}T00:00:00Z`) + delta * 864e5).toISOString().slice(0, 10), "dayShift");
function sanitizePing(body, nowMs = Date.now()) {
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
      const n = Math.min(1e3, Math.max(0, parseInt(hits, 10) || 0));
      if (!label || !n || ++count > 30) continue;
      menus[label] = n;
    }
    days.push({ day, menus });
  }
  return days.length ? { id, version, days } : null;
}
__name(sanitizePing, "sanitizePing");
async function usagePing(request, env) {
  if (!env.DB) return reply({ error: "\uD1B5\uACC4 \uC800\uC7A5\uC18C(D1)\uAC00 \uC5F0\uACB0\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4" }, 503);
  const text = await request.text();
  if (text.length > 8192) return reply({ error: "too large" }, 413);
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return reply({ error: "bad json" }, 400);
  }
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
__name(usagePing, "usagePing");
function summarize(rows, menuRows, from, to, menuUserRows = []) {
  const byDay = /* @__PURE__ */ new Map();
  const latestVersion = /* @__PURE__ */ new Map();
  for (const r of rows) {
    if (!byDay.has(r.day)) byDay.set(r.day, /* @__PURE__ */ new Set());
    byDay.get(r.day).add(r.uid);
  }
  const window = /* @__PURE__ */ __name((end, len) => {
    const set = /* @__PURE__ */ new Set();
    for (let i = 0; i < len; i++) for (const u of byDay.get(dayShift(end, -i)) ?? []) set.add(u);
    return set.size;
  }, "window");
  const series = [];
  for (let d = from; d <= to; d = dayShift(d, 1)) {
    series.push({ day: d, dau: byDay.get(d)?.size ?? 0, wau: window(d, 7), mau: window(d, 30) });
  }
  const weekStart = dayShift(to, -6);
  for (const r of [...rows].sort((a, b) => a.day < b.day ? -1 : 1)) {
    if (r.day >= weekStart) latestVersion.set(r.uid, r.version);
  }
  const versions = {};
  for (const v of latestVersion.values()) versions[v] = (versions[v] ?? 0) + 1;
  const menus = {};
  const entry = /* @__PURE__ */ __name((name) => menus[name] ??= { hits: 0, users: 0, daily: {} }, "entry");
  const uniq = /* @__PURE__ */ new Map();
  for (const m of menuRows) if (m.day >= from && m.day <= to) entry(m.menu).hits += m.hits;
  for (const m of menuUserRows) {
    if (m.day < from || m.day > to) continue;
    const e = entry(m.menu);
    e.hits += m.hits;
    if (!uniq.has(m.menu)) uniq.set(m.menu, /* @__PURE__ */ new Set());
    uniq.get(m.menu).add(m.uid);
    if (m.day >= weekStart) e.daily[m.day] = (e.daily[m.day] ?? 0) + 1;
  }
  for (const [name, set] of uniq) menus[name].users = set.size;
  return { series, versions, menus, weekDays: Array.from({ length: 7 }, (_, i) => dayShift(weekStart, i)) };
}
__name(summarize, "summarize");
async function adminApi(request, url, env) {
  if (!env.ADMIN_TOKEN || request.headers.get("X-Admin-Token") !== env.ADMIN_TOKEN) return new Response("Unauthorized", { status: 401 });
  if (!env.DB) return reply({ error: "\uD1B5\uACC4 \uC800\uC7A5\uC18C(D1)\uAC00 \uC5F0\uACB0\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4" }, 503);
  await ensureSchema(env.DB);
  const span = Math.min(120, Math.max(7, parseInt(url.searchParams.get("days"), 10) || 30));
  const to = new Date(Date.now() + 9 * 36e5).toISOString().slice(0, 10);
  const from = dayShift(to, -(span - 1));
  const [daily, menu, menuUser, total] = await Promise.all([
    env.DB.prepare("SELECT day, uid, version FROM usage_daily WHERE day >= ?").bind(dayShift(from, -29)).all(),
    env.DB.prepare("SELECT day, menu, hits FROM usage_menu WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT day, menu, uid, hits FROM usage_menu_user WHERE day >= ?").bind(from).all(),
    env.DB.prepare("SELECT COUNT(DISTINCT uid) AS n FROM usage_daily").first()
  ]);
  return reply({ from, to, totalInstalls: total?.n ?? 0, ...summarize(daily.results ?? [], menu.results ?? [], from, to, menuUser.results ?? []) });
}
__name(adminApi, "adminApi");
function adminPage() {
  return new Response(PAGE, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}
__name(adminPage, "adminPage");
var PAGE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FSS Insights \uC0AC\uC6A9 \uD1B5\uACC4</title>
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
<h1>FSS Insights \uC0AC\uC6A9 \uD1B5\uACC4</h1>
<div class="card"><input id="tok" type="password" placeholder="\uAD00\uB9AC\uC790 \uD1A0\uD070" autocomplete="off"> <select id="span"><option value="14">14\uC77C</option><option value="30" selected>30\uC77C</option><option value="90">90\uC77C</option></select> <button id="go">\uC870\uD68C</button> <span id="msg" class="leg"></span></div>
<div id="out" hidden>
<div class="card"><div class="kpi"><div><b id="k1"></b><span>\uC624\uB298 DAU</span></div><div><b id="k2"></b><span>\uCD5C\uADFC 7\uC77C WAU</span></div><div><b id="k3"></b><span>\uCD5C\uADFC 30\uC77C MAU</span></div><div><b id="k4"></b><span>\uB204\uC801 \uC124\uCE58(\uACE0\uC720)</span></div></div></div>
<div class="card"><h2>\uC77C\uC790\uBCC4 \uD65C\uC131\uC0AC\uC6A9\uC790</h2><svg id="chart" viewBox="0 0 600 200" preserveAspectRatio="none"></svg><div class="leg"><b style="background:var(--a)"></b>DAU<b style="background:var(--b)"></b>WAU(7\uC77C)<b style="background:var(--c)"></b>MAU(30\uC77C)</div></div>
<div class="card"><h2>\uBC84\uC804\uBCC4 \uBD84\uD3EC (\uCD5C\uADFC 7\uC77C \uD65C\uC131)</h2><div id="ver"></div></div>
<div class="card"><h2>\uBA54\uB274\uBCC4 \uC0AC\uC6A9\uC790 \uC218\xB7\uC5F4\uB9B0 \uD69F\uC218</h2><table id="menu"></table><div class="leg">\uC0AC\uC6A9\uC790 \uC218\uB294 \uC870\uD68C \uAE30\uAC04 \uC911 \uADF8 \uBA54\uB274\uB97C \uC5F0 \uC11C\uB85C \uB2E4\uB978 \uC124\uCE58 \uC218\uC785\uB2C8\uB2E4. \uBA54\uB274 \uAE30\uB85D\uC740 \uC0AC\uC6A9\uC790\uAC00 \uB2E4\uC74C \uB0A0 \uC571\uC744 \uC5F4 \uB54C \uC62C\uB77C\uC624\uBBC0\uB85C \uC624\uB298 \uAC83\uC740 \uB0B4\uC77C\uBD80\uD130 \uBCF4\uC785\uB2C8\uB2E4.</div></div>
<div class="card"><h2>\uC77C\uC790\uBCC4 \uD45C</h2><table id="tbl"></table></div>
</div></main>
<script>
const $=i=>document.getElementById(i);$("tok").value=sessionStorage.getItem("t")||"";
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
function bars(el,obj){const e=Object.entries(obj).sort((a,b)=>b[1]-a[1]);const m=Math.max(1,...e.map(x=>x[1]));el.innerHTML=e.length?e.map(([k,v])=>'<div class="bar"><em>'+esc(k)+'</em><i style="width:'+Math.round(v/m*55)+'%"></i>'+v+'</div>').join(""):'<span class="leg">\uC790\uB8CC \uC5C6\uC74C</span>'}
function line(s,key,color,max){const n=s.length;return '<polyline fill="none" stroke="'+color+'" stroke-width="2" points="'+s.map((p,i)=>(n>1?i/(n-1)*580+10:300)+","+(185-p[key]/max*170)).join(" ")+'"/>'}
async function load(){$("msg").textContent="\uBD88\uB7EC\uC624\uB294 \uC911\u2026";sessionStorage.setItem("t",$("tok").value);
const r=await fetch("/admin/api?days="+$("span").value,{headers:{"X-Admin-Token":$("tok").value}});
if(!r.ok){$("msg").textContent=r.status==401?"\uD1A0\uD070\uC774 \uB9DE\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4":"\uC624\uB958 "+r.status;return}
const d=await r.json();$("msg").textContent=d.from+" ~ "+d.to;$("out").hidden=false;const s=d.series,l=s[s.length-1];
$("k1").textContent=l.dau;$("k2").textContent=l.wau;$("k3").textContent=l.mau;$("k4").textContent=d.totalInstalls;
const max=Math.max(1,...s.map(p=>p.mau));$("chart").innerHTML='<line x1="10" x2="590" y1="185" y2="185" stroke="currentColor" opacity=".2"/>'+line(s,"mau","var(--c)",max)+line(s,"wau","var(--b)",max)+line(s,"dau","var(--a)",max);
bars($("ver"),d.versions);
const ms=Object.entries(d.menus).sort((a,b)=>b[1].users-a[1].users||b[1].hits-a[1].hits);
$("menu").innerHTML="<tr><th>\uBA54\uB274</th><th>\uC0AC\uC6A9\uC790</th><th>\uD69F\uC218</th>"+d.weekDays.map(x=>"<th>"+x.slice(5)+"</th>").join("")+"</tr>"+(ms.length?ms.map(([k,v])=>"<tr><td>"+esc(k)+"</td><td>"+v.users+"</td><td>"+v.hits+"</td>"+d.weekDays.map(x=>"<td>"+(v.daily[x]||"")+"</td>").join("")+"</tr>").join(""):"<tr><td colspan=10>\uC790\uB8CC \uC5C6\uC74C</td></tr>");
$("tbl").innerHTML="<tr><th>\uB0A0\uC9DC</th><th>DAU</th><th>WAU</th><th>MAU</th></tr>"+s.slice().reverse().map(p=>"<tr><td>"+p.day+"</td><td>"+p.dau+"</td><td>"+p.wau+"</td><td>"+p.mau+"</td></tr>").join("")}
$("go").onclick=load;if($("tok").value)load();
<\/script></body></html>`;

// dart-corps.json
var dart_corps_default = { corps: [] };

// dart-lib.js
var DART = "https://opendart.fss.or.kr/api";
var redact = /* @__PURE__ */ __name((text) => String(text || "").replace(/crtfc_key=[^&\s"'<>]*/gi, "crtfc_key=***"), "redact");
var DART_HEADERS = { "User-Agent": "Mozilla/5.0 (compatible; FSSInsights/2.1; +https://github.com/hdlee73/FSS_insights)", Accept: "application/json", "Accept-Language": "ko-KR,ko;q=0.9" };
var norm = /* @__PURE__ */ __name((s) => String(s || "").replace(/\(\s*주\s*\)|㈜|주식회사|\s+/g, "").toLowerCase(), "norm");
var index = null;
function corpIndex() {
  if (!index) index = (dart_corps_default.corps || []).map(([code, name, stock, cls]) => ({ code, name, stock: stock || "", cls: cls || (stock ? "Y" : "E"), key: norm(name) }));
  return index;
}
__name(corpIndex, "corpIndex");
function searchCorps(query, limit = 15) {
  const q = norm(query);
  if (!q) return [];
  const isCode = /^\d{6}$/.test(q);
  const scored = [];
  for (const c of corpIndex()) {
    let score = -1;
    if (isCode) score = c.stock === q ? 100 : -1;
    else if (c.key === q) score = 90;
    else if (c.key.startsWith(q)) score = 70;
    else if (c.key.includes(q)) score = 50;
    if (score < 0) continue;
    scored.push({ c, score: score + (c.stock ? 5 : 0) - Math.min(c.key.length, 30) / 100 });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(({ c }) => ({ corp: c.code, name: c.name, stock: c.stock, cls: c.cls }));
}
__name(searchCorps, "searchCorps");
async function withDartClass(items, lookup) {
  return Promise.all(items.map(async (it) => {
    try {
      const c = await lookup(it.corp);
      return { ...it, cls: c.cls, stock: c.stock || it.stock };
    } catch {
      return it;
    }
  }));
}
__name(withDartClass, "withDartClass");
var corpCount = /* @__PURE__ */ __name(() => corpIndex().length, "corpCount");
async function dartGet(env, name, params) {
  if (!env.DART_API_KEY) {
    const e = new Error("DART \uC778\uC99D\uD0A4\uAC00 \uC11C\uBC84\uC5D0 \uB4F1\uB85D\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uAD00\uB9AC\uC790\uAC00 DART_API_KEY\uB97C \uB4F1\uB85D\uD558\uACE0 Deploy news proxy\uB97C \uB2E4\uC2DC \uC2E4\uD589\uD574\uC57C \uD569\uB2C8\uB2E4.");
    e.status = 503;
    throw e;
  }
  const qs = new URLSearchParams({ crtfc_key: env.DART_API_KEY, ...params });
  let body;
  try {
    const res = await fetch(`${DART}/${name}.json?${qs}`, { headers: DART_HEADERS });
    if (!res.ok) throw new Error(`DART \uC11C\uBC84 \uC751\uB2F5 ${res.status}`);
    body = await res.json();
  } catch (e) {
    throw new Error(redact(e.message || "DART \uC11C\uBC84\uC5D0 \uC5F0\uACB0\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."));
  }
  if (body.status === "000" || body.status === "013") return body;
  throw new Error(redact(`DART \uC624\uB958 ${body.status}: ${body.message || ""}`));
}
__name(dartGet, "dartGet");
var ymd = /* @__PURE__ */ __name((d) => d.toISOString().slice(0, 10).replace(/-/g, ""), "ymd");
var yearsAgo = /* @__PURE__ */ __name((n) => {
  const d = /* @__PURE__ */ new Date();
  d.setUTCFullYear(d.getUTCFullYear() - n);
  return ymd(d);
}, "yearsAgo");
var CLS = { Y: "\uC720\uAC00\uC99D\uAD8C", K: "\uCF54\uC2A4\uB2E5", N: "\uCF54\uB125\uC2A4", E: "\uAE30\uD0C0\uBC95\uC778" };
async function company(env, corp) {
  const r = await dartGet(env, "company", { corp_code: corp });
  if (r.status === "013" || !r.corp_name) throw new Error("\uD68C\uC0AC \uC815\uBCF4\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
  const stock = (r.stock_code || "").trim();
  return {
    corp,
    name: r.corp_name,
    nameEn: r.corp_name_eng || "",
    stock,
    cls: CLS[r.corp_cls] ? r.corp_cls : "E",
    marketName: CLS[r.corp_cls] || CLS.E,
    ceo: r.ceo_nm || "",
    corpNo: r.jurir_no || "",
    bizNo: r.bizr_no || "",
    address: r.adres || "",
    homepage: r.hm_url || "",
    ir: r.ir_url || "",
    phone: r.phn_no || "",
    industryCode: r.induty_code || "",
    established: r.est_dt || "",
    accountMonth: r.acc_mt || ""
  };
}
__name(company, "company");
var REPORTS = [
  ["11011", "\uC0AC\uC5C5\uBCF4\uACE0\uC11C"],
  ["11014", "3\uBD84\uAE30\uBCF4\uACE0\uC11C"],
  ["11012", "\uBC18\uAE30\uBCF4\uACE0\uC11C"],
  ["11013", "1\uBD84\uAE30\uBCF4\uACE0\uC11C"]
];
async function holders(env, corp) {
  const now = (/* @__PURE__ */ new Date()).getUTCFullYear();
  const tries = [];
  for (const [code, label] of REPORTS.slice(1)) tries.push([now, code, label]);
  tries.push([now - 1, "11011", "\uC0AC\uC5C5\uBCF4\uACE0\uC11C"]);
  for (const [code, label] of REPORTS.slice(1)) tries.push([now - 1, code, label]);
  tries.push([now - 2, "11011", "\uC0AC\uC5C5\uBCF4\uACE0\uC11C"]);
  let used = 0;
  for (const [year, code, label] of tries) {
    if (++used > 7) break;
    const r = await dartGet(env, "hyslrSttus", { corp_code: corp, bsns_year: String(year), reprt_code: code });
    const rows = r.list || [];
    if (!rows.length) continue;
    const num2 = /* @__PURE__ */ __name((v) => {
      const n = parseFloat(String(v || "").replace(/,/g, ""));
      return Number.isFinite(n) ? n : null;
    }, "num");
    const slot = /* @__PURE__ */ __name((x) => ({ shares: num2(x.trmend_posesn_stock_co), ratio: num2(x.trmend_posesn_stock_qota_rt) }), "slot");
    const isPref = /* @__PURE__ */ __name((x) => /우선/.test(String(x.stock_knd || "")), "isPref");
    const totals = { common: null, pref: null };
    const byKey = /* @__PURE__ */ new Map();
    for (const x of rows) {
      const name = String(x.nm || "").trim();
      if (name === "\uACC4" || name === "\uD569\uACC4") {
        totals[isPref(x) ? "pref" : "common"] = slot(x).ratio;
        continue;
      }
      const relation = String(x.relate || "").trim();
      const key = `${name}|${relation}`;
      if (!byKey.has(key)) byKey.set(key, { name, relation, common: null, pref: null });
      byKey.get(key)[isPref(x) ? "pref" : "common"] = slot(x);
    }
    const items = [...byKey.values()];
    const sum = /* @__PURE__ */ __name((k) => {
      const v = items.map((i) => i[k] && i[k].ratio || 0).reduce((p, q) => p + q, 0);
      return Math.round(v * 100) / 100;
    }, "sum");
    return {
      basis: `${year} ${label}`,
      items,
      totalCommon: totals.common ?? (items.some((i) => i.common) ? sum("common") : null),
      totalPref: totals.pref ?? (items.some((i) => i.pref) ? sum("pref") : null)
    };
  }
  return { basis: "", items: [], totalCommon: null, totalPref: null };
}
__name(holders, "holders");
var PBLNTF = { a: ["A"], m: ["B"], e: ["D", "E", "I"] };
var DART_VIEW = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";
var ymdOk = /* @__PURE__ */ __name((v) => /^\d{8}$/.test(String(v || "")), "ymdOk");
function dateRange(bgn, end) {
  const today = ymd(/* @__PURE__ */ new Date());
  const to = ymdOk(end) && end <= today ? end : today;
  const earliest = String(Number(to.slice(0, 4)) - 10) + to.slice(4);
  let from = ymdOk(bgn) ? bgn : yearsAgo(1);
  if (from > to) from = to;
  if (from < earliest) from = earliest;
  return { from, to };
}
__name(dateRange, "dateRange");
async function filings(env, corp, type, bgn, end) {
  const kinds = PBLNTF[type];
  if (!kinds) throw new Error("\uACF5\uC2DC \uC885\uB958\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.");
  const { from, to } = dateRange(bgn, end);
  const maxPages = type === "a" ? 2 : 3;
  const page = /* @__PURE__ */ __name((ty, n) => dartGet(env, "list", { corp_code: corp, bgn_de: from, end_de: to, pblntf_ty: ty, page_no: String(n), page_count: "100", sort: "date", sort_mth: "desc" }), "page");
  const parts = await Promise.all(
    kinds.map(async (ty) => {
      const first = await page(ty, 1);
      const total = Math.min(Number(first.total_page) || 1, maxPages);
      const rest = await Promise.all(Array.from({ length: total - 1 }, (_, i) => page(ty, i + 2)));
      return [first, ...rest].flatMap((p) => p.list || []);
    })
  );
  const seen = /* @__PURE__ */ new Set();
  const items = parts.flat().filter((x) => seen.has(x.rcept_no) ? false : seen.add(x.rcept_no)).map((x) => ({ id: x.rcept_no, title: String(x.report_nm || "").trim(), date: x.rcept_dt, by: String(x.flr_nm || "").trim(), note: String(x.rm || "").trim(), url: DART_VIEW + x.rcept_no, src: "DART" })).sort((a, b) => a.date < b.date ? 1 : a.date > b.date ? -1 : 0).slice(0, 400);
  return { items, from, to };
}
__name(filings, "filings");
var amt = /* @__PURE__ */ __name((v) => {
  const n = parseFloat(String(v ?? "").replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
}, "amt");
var toEok = /* @__PURE__ */ __name((n) => n == null ? null : Math.round(n / 1e6) / 100, "toEok");
var squash = /* @__PURE__ */ __name((s) => String(s || "").replace(/\s+/g, ""), "squash");
var ACCOUNTS = {
  assets: [/^자산총계$/],
  liab: [/^부채총계$/],
  equity: [/^자본총계$/],
  revenue: [/^매출액$/, /^수익\(매출액\)$/, /^영업수익$/, /^매출$/, /^순영업수익$/, /^보험수익$/, /^수익$/],
  op: [/^영업이익/, /^영업손익/],
  net: [/^당기순이익/, /^당기순손익/, /^(분기|반기)순이익/, /^(분기|반기)순손익/]
};
async function acnt(env, corp, year, code) {
  const r = await dartGet(env, "fnlttSinglAcnt", { corp_code: corp, bsns_year: String(year), reprt_code: code });
  return r.list || [];
}
__name(acnt, "acnt");
function pick(rows, fs, sj, key) {
  const inSj = rows.filter((x) => x.fs_div === fs && (x.sj_div === sj || sj === "IS" && x.sj_div === "CIS"));
  for (const re of ACCOUNTS[key]) {
    const hit = inSj.find((x) => re.test(squash(x.account_nm)));
    if (hit) return hit;
  }
  return null;
}
__name(pick, "pick");
var field = /* @__PURE__ */ __name((row, f) => row ? toEok(amt(row[f])) : null, "field");
var QUARTER = { "11013": ["1\uBD84\uAE30", "01", "03", "01"], "11012": ["\uBC18\uAE30", "04", "06", "01"], "11014": ["3\uBD84\uAE30", "07", "09", "01"] };
async function finance(env, corp, accMonth) {
  const now = (/* @__PURE__ */ new Date()).getUTCFullYear();
  const dec = !accMonth || String(accMonth) === "12";
  const [q3, half, q1, a1, a2] = await Promise.all([
    acnt(env, corp, now, "11014"),
    acnt(env, corp, now, "11012"),
    acnt(env, corp, now, "11013"),
    acnt(env, corp, now - 1, "11011"),
    acnt(env, corp, now - 2, "11011")
  ]);
  const annualRows = a1.length ? a1 : a2;
  const A = a1.length ? now - 1 : now - 2;
  if (!annualRows.length) return { basis: "", unit: "\uC5B5\uC6D0", balance: [], income: [], trend: [], eps: {}, note: "\uC0AC\uC5C5\uBCF4\uACE0\uC11C \uC7AC\uBB34 \uC790\uB8CC\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4." };
  const older = await acnt(env, corp, A - 3, "11011");
  const quarter = q3.length ? ["11014", q3] : half.length ? ["11012", half] : q1.length ? ["11013", q1] : null;
  const fs = annualRows.some((x) => x.fs_div === "CFS") ? "CFS" : "OFS";
  const basis = fs === "CFS" ? "\uC5F0\uACB0" : "\uBCC4\uB3C4";
  const bs = /* @__PURE__ */ __name((rows, f) => ({ assets: field(pick(rows, fs, "BS", "assets"), f), liab: field(pick(rows, fs, "BS", "liab"), f), equity: field(pick(rows, fs, "BS", "equity"), f) }), "bs");
  const is = /* @__PURE__ */ __name((rows, f) => ({ revenue: field(pick(rows, fs, "IS", "revenue"), f), op: field(pick(rows, fs, "IS", "op"), f), net: field(pick(rows, fs, "IS", "net"), f) }), "is");
  const yearEnd = /* @__PURE__ */ __name((y) => dec ? `${y}.12` : `${y}`, "yearEnd");
  const balance = [];
  const income = [];
  if (quarter) {
    const [code, rows] = quarter;
    const [qName, m1, m2, mStart] = QUARTER[code];
    const label = dec ? `${now}.${m2} ${qName}\uB9D0` : `${now} ${qName}\uB9D0`;
    balance.push({ label, ...bs(rows, "thstrm_amount") });
    const cur = is(rows, "thstrm_amount");
    const cum = is(rows, "thstrm_add_amount");
    const useCum = cum.revenue != null || cum.op != null || cum.net != null ? cum : cur;
    income.push({ label: "\uBD84\uAE30", sub: dec ? `${now}.${m1}~${m2}` : qName, ...cur });
    income.push({ label: "\uB204\uC801", sub: dec ? `${now}.${mStart}~${m2}` : `${qName}\uAE4C\uC9C0`, ...useCum });
    income.push({ label: `${A}\uB144 \uC5F0\uAC04`, sub: "\uC804\uB144", ...is(annualRows, "thstrm_amount") });
    income.push({ label: `${A - 1}\uB144 \uC5F0\uAC04`, sub: "\uC804\uC804\uB144", ...is(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A), ...bs(annualRows, "thstrm_amount") });
    balance.push({ label: yearEnd(A - 1), ...bs(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A - 2), ...bs(annualRows, "bfefrmtrm_amount") });
  } else {
    balance.push({ label: yearEnd(A), ...bs(annualRows, "thstrm_amount") });
    balance.push({ label: yearEnd(A - 1), ...bs(annualRows, "frmtrm_amount") });
    balance.push({ label: yearEnd(A - 2), ...bs(annualRows, "bfefrmtrm_amount") });
    if (older.length) balance.push({ label: yearEnd(A - 3), ...bs(older, "thstrm_amount") });
    income.push({ label: `${A}\uB144 \uC5F0\uAC04`, sub: "", ...is(annualRows, "thstrm_amount") });
    income.push({ label: `${A - 1}\uB144 \uC5F0\uAC04`, sub: "", ...is(annualRows, "frmtrm_amount") });
    income.push({ label: `${A - 2}\uB144 \uC5F0\uAC04`, sub: "", ...is(annualRows, "bfefrmtrm_amount") });
  }
  const points = /* @__PURE__ */ new Map();
  const put = /* @__PURE__ */ __name((y, rows, f) => {
    const b = bs(rows, f);
    const i = is(rows, f);
    points.set(y, { year: y, ...i, debt: b.equity ? Math.round(b.liab / b.equity * 1e3) / 10 : null });
  }, "put");
  put(A, annualRows, "thstrm_amount");
  put(A - 1, annualRows, "frmtrm_amount");
  put(A - 2, annualRows, "bfefrmtrm_amount");
  if (older.length) {
    put(A - 3, older, "thstrm_amount");
    put(A - 4, older, "frmtrm_amount");
  }
  const trend = [...points.values()].sort((x, y) => x.year - y.year).slice(-5);
  const eps = {};
  const epsOf = /* @__PURE__ */ __name(async (year) => {
    const r = await dartGet(env, "fnlttSinglAcntAll", { corp_code: corp, bsns_year: String(year), reprt_code: "11011", fs_div: fs });
    const rows = (r.list || []).filter((x) => x.sj_div === "IS" || x.sj_div === "CIS");
    const cands = rows.filter((x) => /주당(순)?(이익|손익)|주당순이익/.test(squash(x.account_nm)));
    const hit = cands.find((x) => /기본/.test(squash(x.account_nm))) || cands[0];
    if (!hit) return;
    const set = /* @__PURE__ */ __name((y, v) => {
      const n = amt(v);
      if (n != null && eps[y] == null) eps[y] = n;
    }, "set");
    set(year, hit.thstrm_amount);
    set(year - 1, hit.frmtrm_amount);
    set(year - 2, hit.bfefrmtrm_amount);
  }, "epsOf");
  await Promise.all([epsOf(A), older.length ? epsOf(A - 3) : Promise.resolve()]).catch(() => {
  });
  return {
    basis,
    unit: "\uC5B5\uC6D0",
    annualYear: A,
    quarterLabel: quarter ? `${now} ${QUARTER[quarter[0]][0]}` : "",
    balance,
    income,
    trend,
    eps,
    note: ""
  };
}
__name(finance, "finance");

// web-lib.js
var WEB_PATHS = /^\/(law|dart|fisis|stats|library|fetch|usage\/ping)(\/|$)/;
var FETCH_HOSTS = [
  "fss.or.kr",
  "fsc.go.kr",
  "kcmi.re.kr",
  "kif.re.kr",
  "iosco.org",
  "bis.org",
  "fsb.org",
  "esma.europa.eu",
  "sec.gov",
  "mas.gov.sg",
  "bok.or.kr",
  "mofe.go.kr",
  "moef.go.kr",
  "stock.naver.com",
  "finance.naver.com",
  "finance.yahoo.com",
  "kofia.or.kr",
  "kofiabond.or.kr",
  "krx.co.kr"
];
var MAX_BYTES = 4 * 1024 * 1024;
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
function allowedHost(host) {
  return FETCH_HOSTS.some((h) => host === h || host.endsWith("." + h));
}
__name(allowedHost, "allowedHost");
function webAccess(request, url, env) {
  const origin = request.headers.get("Origin");
  if (!origin || !WEB_PATHS.test(url.pathname)) return null;
  const allowed = String(env.WEB_ORIGINS || "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!allowed.includes(origin)) return null;
  return {
    origin,
    async check() {
      if (!env.WEB_LIMIT) return null;
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const { success } = await env.WEB_LIMIT.limit({ key: ip });
      return success ? null : new Response(JSON.stringify({ error: "\uC694\uCCAD\uC774 \uB108\uBB34 \uB9CE\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uB4A4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694." }), {
        status: 429,
        headers: { "Content-Type": "application/json; charset=utf-8", "Retry-After": "60" }
      });
    }
  };
}
__name(webAccess, "webAccess");
function corsPreflight(origin) {
  return new Response(null, {
    status: 204,
    headers: {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Max-Age": "86400",
      Vary: "Origin"
    }
  });
}
__name(corsPreflight, "corsPreflight");
function withCors(response, origin) {
  const out = new Response(response.body, response);
  out.headers.set("Access-Control-Allow-Origin", origin);
  out.headers.set("Access-Control-Expose-Headers", "Content-Disposition, X-Final-Url");
  out.headers.append("Vary", "Origin");
  return out;
}
__name(withCors, "withCors");
async function proxyFetch(request, url, ctx) {
  let target;
  try {
    target = new URL(url.searchParams.get("url") || "");
  } catch {
    return new Response("url required", { status: 400 });
  }
  if (target.protocol !== "https:" && target.protocol !== "http:") return new Response("bad url", { status: 400 });
  if (!allowedHost(target.hostname)) return new Response("\uD5C8\uC6A9\uB418\uC9C0 \uC54A\uC740 \uC0AC\uC774\uD2B8\uC785\uB2C8\uB2E4.", { status: 403 });
  const isPost = request.method === "POST";
  let referer = null;
  try {
    const ref = url.searchParams.get("ref");
    if (ref && allowedHost(new URL(ref).hostname)) referer = new URL(ref).href;
  } catch {
  }
  const cache = caches.default;
  const cacheKey = new Request(`https://fetch-cache.invalid/?u=${encodeURIComponent(target.href)}&r=${encodeURIComponent(referer || "")}`);
  if (!isPost) {
    const hit = await cache.match(cacheKey);
    if (hit) return hit;
  }
  const init = {
    method: isPost ? "POST" : "GET",
    redirect: "follow",
    headers: {
      "User-Agent": UA,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.5",
      "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.5"
    }
  };
  if (referer) init.headers.Referer = referer;
  if (isPost) {
    const body = await request.arrayBuffer();
    if (body.byteLength > 32 * 1024) return new Response("body too large", { status: 413 });
    init.body = body;
    init.headers["Content-Type"] = request.headers.get("Content-Type") || "application/x-www-form-urlencoded";
  }
  const upstream = await fetch(target.href, init);
  const finalUrl = upstream.url || target.href;
  if (!allowedHost(new URL(finalUrl).hostname)) return new Response("\uD5C8\uC6A9\uB418\uC9C0 \uC54A\uC740 \uC0AC\uC774\uD2B8\uB85C \uC774\uB3D9\uD588\uC2B5\uB2C8\uB2E4.", { status: 403 });
  const bytes = await upstream.arrayBuffer();
  if (bytes.byteLength > MAX_BYTES) return new Response("\uC751\uB2F5\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4.", { status: 413 });
  const out = new Response(bytes, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") || "application/octet-stream",
      // 시세는 1분, 그 밖의 목록은 10분 동안 캐시한다.
      "Cache-Control": `public, max-age=${/(^|\.)(stock\.naver\.com|finance\.yahoo\.com)$/.test(target.hostname) ? 60 : 600}`,
      "X-Final-Url": finalUrl
    }
  });
  if (!isPost && upstream.ok) ctx.waitUntil(cache.put(cacheKey, out.clone()));
  return out;
}
__name(proxyFetch, "proxyFetch");

// fisis-lib.js
var FISIS_API = "https://fisis.fss.or.kr/openapi";
var FISIS_TERMS = ["Q", "H", "Y"];
var CLOSED = /\[폐\]|\(폐\)|［폐］/;
var rowsOf = /* @__PURE__ */ __name((v) => Array.isArray(v) ? v : v && Array.isArray(v.row) ? v.row : v && typeof v === "object" ? [v] : [], "rowsOf");
var num = /* @__PURE__ */ __name((x) => {
  if (x === null || x === void 0 || String(x).trim() === "" || String(x).trim() === "-") return null;
  const n = Number(String(x).replace(/,/g, ""));
  return Number.isFinite(n) ? n : null;
}, "num");
async function fisisFetch(key, name, params, fetchImpl = fetch) {
  if (!key) throw new Error("FISIS \uC778\uC99D\uD0A4(FISIS_API_KEY)\uAC00 \uC544\uC9C1 \uC124\uC815\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4.");
  const q = new URLSearchParams({ lang: "kr", auth: key, ...params });
  const res = await fetchImpl(`${FISIS_API}/${name}.json?${q}`);
  if (res.status === 429) throw Object.assign(new Error("FISIS \uC77C\uC77C \uD638\uCD9C \uD55C\uB3C4\uB97C \uB118\uC5C8\uC2B5\uB2C8\uB2E4. \uB0B4\uC77C \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694."), { quota: true });
  const text = await res.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`FISIS \uC751\uB2F5\uC744 \uC77D\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4 (HTTP ${res.status})`);
  }
  const result = body.result || body;
  const code = String(result.err_cd ?? "000");
  if (code === "020") throw Object.assign(new Error("FISIS \uC77C\uC77C \uD638\uCD9C \uD55C\uB3C4\uB97C \uB118\uC5C8\uC2B5\uB2C8\uB2E4. \uB0B4\uC77C \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694."), { quota: true });
  if (code !== "000" && code !== "100") throw new Error(`FISIS \uC624\uB958 ${code}: ${result.err_msg || ""}`.trim());
  return result;
}
__name(fisisFetch, "fisisFetch");
var RATIO_SPECS = {
  F: [
    { key: "ncr", label: "\uC21C\uC790\uBCF8\uBE44\uC728(\uAC1C\uBCC4)", pats: [/^순자본비율/, /순자본비율|NCR/], pool: "sep" },
    { key: "ncr_c", label: "\uC21C\uC790\uBCF8\uBE44\uC728(\uC5F0\uACB0)", pats: [/^순자본비율/, /순자본비율|NCR/], pool: "con" },
    { key: "lev", label: "\uB808\uBC84\uB9AC\uC9C0\uBE44\uC728", pats: [/^레버리지비율/, /레버리지/] },
    { key: "liq", label: "\uC720\uB3D9\uC131\uBE44\uC728", pats: [/^유동성비율/, /^(잔존만기)?3개월이내유동성비율/, /유동성비율/], exclude: /외화|자산|부채/ }
  ],
  G: [],
  A: [
    { key: "bis", label: "BIS\uAE30\uC900 \uC790\uAE30\uC790\uBCF8\uBE44\uC728", pats: [/^(총자본|BIS)(자기자본)?비율/, /BIS/, /총자본비율/, /자기자본비율/] },
    { key: "delinq", label: "\uCD1D\uB300\uCD9C\uCC44\uAD8C \uC5F0\uCCB4\uC728", pats: [/총대출채권.*연체율/, /^총대출.*연체율/, /연체율/] },
    { key: "npl", label: "\uACE0\uC815\uC774\uD558\uC5EC\uC2E0\uBE44\uC728", pats: [/^고정이하여신비율/, /고정이하여신비율/, /고정이하(채권|대출)비율/, /부실채권비율/] },
    { key: "lcr", label: "\uC720\uB3D9\uC131\uCEE4\uBC84\uB9AC\uC9C0\uBE44\uC728", pats: [/유동성커버리지/, /LCR/] }
  ],
  E: [
    { key: "bis", label: "BIS\uC790\uAE30\uC790\uBCF8\uBE44\uC728", pats: [/BIS/, /총자본비율/, /자기자본비율/] },
    { key: "npl", label: "\uACE0\uC815\uC774\uD558\uC5EC\uC2E0\uBE44\uC728", pats: [/^고정이하여신비율/, /고정이하여신비율/, /고정이하(채권|대출)비율/, /부실채권비율/] },
    { key: "delinq", label: "\uC5F0\uCCB4\uC728", pats: [/연체율/] },
    { key: "liq", label: "\uC720\uB3D9\uC131\uBE44\uC728", pats: [/^유동성비율/, /유동성비율/], exclude: /외화|자산|부채|커버리지/ }
  ],
  C: [
    { key: "adj", label: "\uC870\uC815\uC790\uAE30\uC790\uBCF8\uBE44\uC728", pats: [/^조정자기자본비율/, /조정자기자본비율/, /자기자본비율/] },
    { key: "npl", label: "\uACE0\uC815\uC774\uD558\uCC44\uAD8C\uBE44\uC728", pats: [/고정이하(채권|여신|자산)비율/, /부실채권비율/] }
  ],
  H: [{ key: "solv", label: "\uC9C0\uAE09\uC5EC\uB825\uBE44\uC728", pats: [/K-?ICS|킥스|신지급여력/, /지급여력비율/] }],
  I: [{ key: "solv", label: "\uC9C0\uAE09\uC5EC\uB825\uBE44\uC728", pats: [/K-?ICS|킥스|신지급여력/, /지급여력비율/] }]
};
RATIO_SPECS.W = RATIO_SPECS.F;
function specListNos(spec) {
  const all = [...Object.values(spec.basis).flat(), ...spec.ratios, ...spec.funds];
  return [...new Set(all.flatMap((m) => [m.listNo, ...(m.alts || []).map((a) => a.listNo)]))];
}
__name(specListNos, "specListNos");
function monthsBack(term, now = /* @__PURE__ */ new Date()) {
  const y = now.getUTCFullYear();
  const end = `${y}${String(now.getUTCMonth() + 1).padStart(2, "0")}`;
  const span = { Q: 3, H: 5, Y: 8 }[term];
  return { start: `${y - span}${end.slice(4)}`, end };
}
__name(monthsBack, "monthsBack");
function parseTable(result, term) {
  const rows = rowsOf(result.list);
  if (!rows.length) return null;
  const desc = result.description || {};
  const cols = rowsOf(desc.column ?? desc).map((c) => ({ id: String(c.column_id || ""), name: String(c.column_nm || "") })).filter((c) => c.id);
  const columns = cols.length ? cols : [{ id: "a", name: "" }];
  const accounts = {};
  for (const row of rows) {
    const key = String(row.account_cd ?? "");
    const a = accounts[key] ||= { name: String(row.account_nm || ""), points: [] };
    const v = {};
    const raw = {};
    for (const c of columns) {
      v[c.id] = num(row[c.id]);
      raw[c.id] = row[c.id] == null ? "" : String(row[c.id]).trim();
    }
    a.points.push({ month: String(row.base_month || ""), v, raw });
  }
  const monthMax = {};
  let max = 0;
  for (const a of Object.values(accounts)) {
    a.points.sort((x, y) => x.month.localeCompare(y.month));
    for (const p of a.points) {
      for (const c of columns) {
        const v = p.v[c.id];
        if (v === null) continue;
        monthMax[p.month] = Math.max(monthMax[p.month] || 0, Math.abs(v));
        max = Math.max(max, Math.abs(v));
      }
    }
  }
  return { term, unit: String(desc.unit || ""), name: String(rows[0] && rows[0].finance_nm || ""), columns, accounts, max, monthMax };
}
__name(parseTable, "parseTable");
async function fetchTable(call, finance2, listNo, firstTerm = "Q", accountCd = "") {
  const order = FISIS_TERMS.slice(Math.max(0, FISIS_TERMS.indexOf(firstTerm)));
  for (const term of order) {
    const { start, end } = monthsBack(term);
    const params = { financeCd: finance2, listNo, term, startBaseMm: start, endBaseMm: end };
    if (accountCd) params.accountCd = accountCd;
    const table = parseTable(await call(params), term);
    if (table) return table;
  }
  return null;
}
__name(fetchTable, "fetchTable");
var PL_KEYS = /^(revenue|expense|opinc|netincome)$/;
var CUM_COL = /누계|누적|연초/;
var AMOUNT_COL = /금액|잔액|채권|여신|대출|원본|자산|부채|규모|액$/;
function pickColumn(table, colSource, group = "", key = "", cum = false) {
  if (cum) return table.columns.find((c) => CUM_COL.test(c.name)) || null;
  if (colSource) return table.columns.find((c) => new RegExp(colSource).test(c.name.replace(/\s/g, ""))) || null;
  const cols = table.columns;
  if (!cols.length) return null;
  if (PL_KEYS.test(key) && cols.length > 1) {
    const cum2 = cols.find((c) => CUM_COL.test(c.name));
    return cum2 ? cols.find((c) => c !== cum2 && !CUM_COL.test(c.name)) || cols[0] : cols[0];
  }
  if (group === "ratio" && cols.length > 1) return cols.find((c) => /비율|율|%/.test(c.name)) || cols.find((c) => !AMOUNT_COL.test(c.name)) || cols[0];
  return cols[0];
}
__name(pickColumn, "pickColumn");
function unitMult(unit) {
  const u = String(unit || "").replace(/\s/g, "");
  if (/조/.test(u)) return 1e12;
  if (/억/.test(u)) return 1e8;
  if (/백만/.test(u)) return 1e6;
  if (/천만/.test(u)) return 1e7;
  if (/만원|^만$/.test(u)) return 1e4;
  if (/천원|^천$/.test(u)) return 1e3;
  if (/원/.test(u)) return 1;
  return null;
}
__name(unitMult, "unitMult");
function metricFromTable(m, t) {
  const account = t.accounts[m.accountCd];
  const column = pickColumn(t, m.col, m.group, m.key, m.cum);
  if (!account || !column) return null;
  let points = account.points.map((p) => ({ month: p.month, value: p.v[column.id] })).filter((p) => p.value !== null);
  if (!points.length) return null;
  let unit = t.unit;
  if (m.group === "ratio") {
    if (points.some((p) => Math.abs(p.value) > 1e5)) return null;
    unit = "%";
  } else {
    points = points.map((p) => {
      const mult = (t.monthMax[p.month] ?? t.max) >= 1e7 ? 1 : unitMult(t.unit) ?? 1e6;
      return { month: p.month, value: Math.round(p.value * mult / 1e8 * 100) / 100 };
    });
    unit = "\uC5B5\uC6D0";
  }
  return {
    key: m.key,
    label: m.label,
    group: m.group,
    basis: m.basis,
    unit,
    term: t.term,
    column: t.columns.length > 1 ? column.name : "",
    source: `${m.listNm} \xB7 ${m.accountNm}`,
    points
  };
}
__name(metricFromTable, "metricFromTable");
function buildMetrics(spec, tables) {
  const requests = [
    ...Object.entries(spec.basis).flatMap(([basis, list]) => list.map((m) => ({ ...m, group: "fin", basis }))),
    ...spec.ratios.map((m) => ({ ...m, group: "ratio", basis: "" })),
    ...spec.funds.map((m) => ({ ...m, group: "fund", basis: "" }))
  ];
  let name = "";
  let term = "";
  const metrics = [];
  for (const m of requests) {
    const t = tables[m.listNo];
    if (!t) continue;
    name = name || t.name;
    const metric = metricFromTable(m, t);
    if (!metric) continue;
    if (m.group === "fin" && !term) term = t.term;
    metrics.push(metric);
    if (m.group === "fin" && PL_KEYS.test(m.key)) {
      const cum = metricFromTable({ ...m, cum: true, key: `${m.key}_cum` }, t);
      if (cum) metrics.push(cum);
    }
  }
  deriveMissing(metrics);
  return { name, term, basisOptions: Object.keys(spec.basis), metrics };
}
__name(buildMetrics, "buildMetrics");
var IDENTITIES = [
  ["opinc", "revenue", "expense", "\uC601\uC5C5\uC774\uC775"],
  // opinc = revenue - expense
  ["revenue", "opinc", "expense", "\uC601\uC5C5\uC218\uC775"],
  // revenue = opinc + expense
  ["expense", "revenue", "opinc", "\uC601\uC5C5\uBE44\uC6A9"],
  // expense = revenue - opinc
  ["liab", "assets", "equity", "\uBD80\uCC44\uCD1D\uACC4"],
  // liab = assets - equity
  ["equity", "assets", "liab", "\uC790\uAE30\uC790\uBCF8"],
  // equity = assets - liab
  ["assets", "liab", "equity", "\uC790\uC0B0\uCD1D\uACC4"]
  // assets = liab + equity
];
var IDENTITY_SIGN = { opinc: -1, revenue: 1, expense: -1, liab: -1, equity: -1, assets: 1 };
function deriveMissing(metrics) {
  for (const basis of ["\uBCC4\uB3C4", "\uC5F0\uACB0"]) {
    for (const suffix of ["", "_cum"]) {
      const find = /* @__PURE__ */ __name((key) => metrics.find((m) => m.group === "fin" && m.basis === basis && m.key === key + suffix), "find");
      for (const [target, a, b, label] of IDENTITIES) {
        if (suffix && !PL_KEYS.test(target)) continue;
        if (find(target)) continue;
        const x = find(a);
        const y = find(b);
        if (!x || !y) continue;
        const sign = IDENTITY_SIGN[target];
        const byMonth = new Map(y.points.map((p) => [p.month, p.value]));
        const points = x.points.filter((p) => byMonth.has(p.month)).map((p) => ({ month: p.month, value: p.value + sign * byMonth.get(p.month) }));
        if (!points.length) continue;
        metrics.push({ key: target + suffix, label, group: "fin", basis, unit: x.unit, term: x.term, column: "", source: "\uACC4\uC0B0", points });
      }
    }
  }
}
__name(deriveMissing, "deriveMissing");

// worker.js
var NAVER = "https://openapi.naver.com/v1/search/news.json";
var DRIVE = "https://www.googleapis.com/drive/v3/files";
var FOLDER = "application/vnd.google-apps.folder";
var EXPORTS = {
  "application/vnd.google-apps.document": ["application/pdf", ".pdf"],
  "application/vnd.google-apps.presentation": ["application/pdf", ".pdf"],
  "application/vnd.google-apps.spreadsheet": ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"],
  "application/vnd.google-apps.drawing": ["application/pdf", ".pdf"]
};
var clamp = /* @__PURE__ */ __name((value, min, max, fallback) => {
  const n = parseInt(value, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}, "clamp");
var json = /* @__PURE__ */ __name((data, status = 200) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8" } }), "json");
var fail = /* @__PURE__ */ __name((status, message) => json({ error: message }, status), "fail");
var worker_default = {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const web = webAccess(request, url, env);
    if (web) {
      if (request.method === "OPTIONS") return corsPreflight(web.origin);
      const blocked = await web.check();
      if (blocked) return withCors(blocked, web.origin);
      return withCors(await route(request, url, env, ctx, true), web.origin);
    }
    return route(request, url, env, ctx, false);
  }
};
async function route(request, url, env, ctx, fromWeb) {
  {
    if (url.pathname === "/admin" && request.method === "GET") return adminPage();
    if (url.pathname === "/admin/api" && request.method === "GET") {
      try {
        return await adminApi(request, url, env);
      } catch (e) {
        return fail(502, `\uD1B5\uACC4 \uC870\uD68C \uC624\uB958: ${e.message}`);
      }
    }
    if (!fromWeb && (!env.APP_TOKEN || request.headers.get("X-App-Token") !== env.APP_TOKEN)) {
      return new Response("Unauthorized", { status: 401 });
    }
    if (url.pathname === "/fetch" && fromWeb) {
      try {
        return await proxyFetch(request, url, ctx);
      } catch (e) {
        return fail(502, `\uC0AC\uC774\uD2B8 \uC77D\uAE30 \uC624\uB958: ${e.message}`);
      }
    }
    if (url.pathname === "/usage/ping" && request.method === "POST") {
      try {
        return await usagePing(request, env);
      } catch (e) {
        return fail(502, "\uD1B5\uACC4 \uC800\uC7A5 \uC624\uB958");
      }
    }
    if (url.pathname === "/news" && request.method === "GET") return news(url, env, ctx);
    if (url.pathname === "/stats" && request.method === "GET") {
      try {
        return await stats(url, env, ctx);
      } catch (e) {
        return fail(502, `\uD1B5\uACC4 \uC11C\uBC84 \uC624\uB958: ${e.message}`);
      }
    }
    if (url.pathname.startsWith("/fisis/") && request.method === "GET") {
      try {
        return await fisis(url, env);
      } catch (e) {
        return fail(502, e.message || "FISIS \uC11C\uBC84 \uC624\uB958");
      }
    }
    if (url.pathname.startsWith("/dart/") && request.method === "GET") {
      try {
        return await dart(url, env);
      } catch (e) {
        return fail(e.status || 502, redact(e.message) || "DART \uC11C\uBC84 \uC624\uB958");
      }
    }
    if (url.pathname.startsWith("/law/") && request.method === "GET") {
      try {
        return await law(url, env, ctx);
      } catch (e) {
        return fail(502, `\uBC95\uB839 \uC11C\uBC84 \uC624\uB958: ${e.message}`);
      }
    }
    if (url.pathname.startsWith("/library") && request.method === "GET") {
      try {
        return await library(url, env);
      } catch (e) {
        return fail(502, `\uC790\uB8CC\uC2E4 \uC11C\uBC84 \uC624\uB958: ${e.message}`);
      }
    }
    return new Response("Not found", { status: 404 });
  }
}
__name(route, "route");
var LAW_ORIGIN = "https://www.law.go.kr";
var LAW_ENDPOINTS = /* @__PURE__ */ new Set(["/DRF/lawSearch.do", "/DRF/lawService.do"]);
async function law(url, env, ctx) {
  if (!env.LAW_OC) return fail(503, "LAW_OC\uAC00 \uC124\uC815\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4");
  const path = url.pathname.slice("/law".length);
  if (!LAW_ENDPOINTS.has(path)) return new Response("Not found", { status: 404 });
  const params = new URLSearchParams();
  for (const [k, v] of url.searchParams) if (k.toLowerCase() !== "oc") params.append(k, v.slice(0, 200));
  params.set("OC", env.LAW_OC);
  const cacheKey = new Request(`${LAW_ORIGIN}${path}?${[...params].filter(([k]) => k !== "OC").map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&")}`);
  const cache = caches.default;
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  const res = await fetch(`${LAW_ORIGIN}${path}?${params}`);
  const body = await res.text();
  const out = new Response(body, {
    status: res.status,
    headers: { "Content-Type": "application/xml; charset=utf-8", "Cache-Control": "public, max-age=3600" }
  });
  if (res.ok) ctx.waitUntil(cache.put(cacheKey, out.clone()));
  return out;
}
__name(law, "law");
async function news(url, env, ctx) {
  const query = (url.searchParams.get("query") || "").trim().slice(0, 200);
  if (!query) return new Response("query required", { status: 400 });
  const display = clamp(url.searchParams.get("display"), 1, 100, 100);
  const start = clamp(url.searchParams.get("start"), 1, 1e3, 1);
  const target = `${NAVER}?query=${encodeURIComponent(query)}&display=${display}&start=${start}&sort=date`;
  const cacheKey = new Request(target);
  const cache = caches.default;
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  const upstream = await fetch(target, {
    headers: { "X-Naver-Client-Id": env.NAVER_CLIENT_ID, "X-Naver-Client-Secret": env.NAVER_CLIENT_SECRET }
  });
  const response = new Response(upstream.body, {
    status: upstream.status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=120" }
  });
  if (upstream.ok) ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}
__name(news, "news");
async function drive(path, env, params) {
  const query = new URLSearchParams({ ...params, key: env.GOOGLE_API_KEY });
  return fetch(`${DRIVE}${path}?${query}`);
}
__name(drive, "drive");
var indexCache = { at: 0, root: "", data: null };
function splitDescription(text) {
  const tags = [];
  const body = String(text || "").replace(/(^|\s)#([^\s#]{1,20})/g, (m, sp, tag) => {
    tags.push(tag);
    return sp;
  }).replace(/\s+/g, " ").trim();
  return { tags: [...new Set(tags)], body };
}
__name(splitDescription, "splitDescription");
function toItem(f, parent) {
  const isFolder = f.mimeType === FOLDER;
  const exp = EXPORTS[f.mimeType];
  const native = f.mimeType.startsWith("application/vnd.google-apps.");
  if (!isFolder && native && !exp) return null;
  const d = splitDescription(f.description);
  return {
    id: f.id,
    name: f.name,
    folder: isFolder,
    type: exp ? exp[0] : f.mimeType,
    downloadName: exp && !f.name.toLowerCase().endsWith(exp[1]) ? f.name + exp[1] : f.name,
    size: Number(f.size || 0),
    modified: f.modifiedTime || "",
    description: d.body,
    tags: d.tags,
    parent
  };
}
__name(toItem, "toItem");
async function buildIndex(env) {
  const root = env.GDRIVE_FOLDER_ID;
  if (indexCache.data && indexCache.root === root && Date.now() - indexCache.at < 6e4) return indexCache.data;
  const byFolder = /* @__PURE__ */ new Map();
  const files = /* @__PURE__ */ new Map();
  const names = /* @__PURE__ */ new Map([[root, ""]]);
  let level = [root];
  for (let depth = 0; depth < 5 && level.length && byFolder.size < 60; depth++) {
    const next = [];
    for (const folder of level) {
      if (byFolder.size >= 60) break;
      const res = await drive("", env, {
        q: `'${folder}' in parents and trashed = false`,
        fields: "files(id,name,mimeType,size,modifiedTime,description)",
        orderBy: "folder,name_natural",
        pageSize: "300"
      });
      if (!res.ok) throw new Error(`\uB4DC\uB77C\uC774\uBE0C \uC751\uB2F5 ${res.status}`);
      const items = ((await res.json()).files || []).map((f) => toItem(f, folder)).filter(Boolean);
      byFolder.set(folder, items);
      for (const item of items) {
        files.set(item.id, item);
        if (item.folder && !names.has(item.id)) {
          names.set(item.id, item.name);
          next.push(item.id);
        }
      }
    }
    level = next;
  }
  const tagCount = /* @__PURE__ */ new Map();
  for (const item of files.values()) for (const t of item.tags) tagCount.set(t, (tagCount.get(t) || 0) + 1);
  const tags = [...tagCount.entries()].sort((a, b) => b[1] - a[1]).map(([t]) => t).slice(0, 40);
  const data = { root, byFolder, files, names, tags };
  indexCache = { at: Date.now(), root, data };
  return data;
}
__name(buildIndex, "buildIndex");
async function bodySearch(words, index2, env) {
  const esc = /* @__PURE__ */ __name((w) => w.replace(/\\/g, "\\\\").replace(/'/g, "\\'"), "esc");
  const terms = words.slice(0, 5).map((w) => `fullText contains '${esc(w)}'`).join(" and ");
  const folders = [...index2.byFolder.keys()];
  const batches = [];
  for (let i = 0; i < folders.length; i += 20) batches.push(folders.slice(i, i + 20));
  const ids = /* @__PURE__ */ new Set();
  await Promise.all(batches.map(async (batch) => {
    const parents = batch.map((f) => `'${f}' in parents`).join(" or ");
    const res = await drive("", env, { q: `(${parents}) and ${terms} and trashed = false`, fields: "files(id)", pageSize: "100" });
    if (!res.ok) throw new Error(`\uB4DC\uB77C\uC774\uBE0C \uC751\uB2F5 ${res.status}`);
    for (const f of (await res.json()).files || []) if (index2.files.has(f.id)) ids.add(f.id);
  }));
  return ids;
}
__name(bodySearch, "bodySearch");
async function library(url, env) {
  if (!env.GOOGLE_API_KEY || !env.GDRIVE_FOLDER_ID) return fail(501, "\uC790\uB8CC\uC2E4(\uAD6C\uAE00 \uB4DC\uB77C\uC774\uBE0C)\uC774 \uC544\uC9C1 \uC5F0\uACB0\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4.");
  const path = url.pathname.replace(/^\/library/, "").replace(/\/+$/, "");
  let index2;
  try {
    index2 = await buildIndex(env);
  } catch (e) {
    return fail(502, "\uB4DC\uB77C\uC774\uBE0C \uBAA9\uB85D\uC744 \uC77D\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3F4\uB354\uAC00 '\uB9C1\uD06C\uAC00 \uC788\uB294 \uBAA8\uB4E0 \uC0AC\uC6A9\uC790'\uB85C \uACF5\uC720\uB418\uC5B4 \uC788\uB294\uC9C0, API \uD0A4\uAC00 \uB9DE\uB294\uC9C0 \uD655\uC778\uD574 \uC8FC\uC138\uC694.");
  }
  if (path === "/list") {
    const folder = url.searchParams.get("folder") || index2.root;
    if (!index2.byFolder.has(folder)) return fail(403, "\uC790\uB8CC\uC2E4 \uBC16\uC758 \uD3F4\uB354\uC785\uB2C8\uB2E4.");
    return json({ folder, root: folder === index2.root, tags: index2.tags, items: index2.byFolder.get(folder) });
  }
  if (path === "/search") {
    const q = (url.searchParams.get("q") || "").trim().slice(0, 60).toLowerCase();
    if (!q) return json({ items: [], tags: index2.tags });
    const tagQuery = q.startsWith("#") ? q.slice(1) : null;
    const words = q.split(/\s+/).filter(Boolean);
    const hits = [];
    const bodyIds = tagQuery !== null ? /* @__PURE__ */ new Set() : await bodySearch(words, index2, env).catch(() => /* @__PURE__ */ new Set());
    for (const item of index2.files.values()) {
      if (item.folder) continue;
      const haystack = `${item.name} ${item.description} ${item.tags.join(" ")}`.toLowerCase();
      const ok = tagQuery !== null ? item.tags.some((t) => t.toLowerCase() === tagQuery) : words.every((w) => haystack.includes(w));
      if (ok || bodyIds.has(item.id)) hits.push({ ...item, location: index2.names.get(item.parent) || "", bodyMatch: !ok });
    }
    hits.sort((a, b) => (b.modified || "").localeCompare(a.modified || ""));
    return json({ items: hits.slice(0, 100), tags: index2.tags });
  }
  const m = path.match(/^\/file\/([A-Za-z0-9_-]+)$/);
  if (m) {
    const id = m[1];
    const entry = index2.files.get(id);
    if (!entry || entry.folder) return fail(403, "\uC790\uB8CC\uC2E4 \uBC16\uC758 \uD30C\uC77C\uC785\uB2C8\uB2E4.");
    const meta = await drive(`/${id}`, env, { fields: "name,mimeType" });
    if (!meta.ok) return fail(404, "\uD30C\uC77C\uC744 \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.");
    const info = await meta.json();
    const conv = EXPORTS[info.mimeType];
    const upstream = conv ? await drive(`/${id}/export`, env, { mimeType: conv[0] }) : await drive(`/${id}`, env, { alt: "media" });
    if (!upstream.ok) return fail(502, "\uD30C\uC77C\uC744 \uB0B4\uB824\uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4(\uAD6C\uAE00 \uBCC0\uD658\xB7\uC804\uC1A1 \uD55C\uB3C4\uB97C \uB118\uC5C8\uC744 \uC218 \uC788\uC2B5\uB2C8\uB2E4).");
    const headers = {
      "Content-Type": conv ? conv[0] : info.mimeType,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(entry.downloadName)}`,
      "X-Content-Type-Options": "nosniff"
    };
    const length = upstream.headers.get("Content-Length");
    if (length) headers["Content-Length"] = length;
    return new Response(upstream.body, { headers });
  }
  return fail(404, "Not found");
}
__name(library, "library");
var ECOS = "https://ecos.bok.or.kr/api";
var STAT_SPECS = [
  { id: "base-rate", name: "\uD55C\uAD6D\uC740\uD589 \uAE30\uC900\uAE08\uB9AC", unit: "%", cycle: "D", table: ["\uAE30\uC900\uAE08\uB9AC", "\uC5EC\uC218\uC2E0\uAE08\uB9AC"], item: ["\uAE30\uC900\uAE08\uB9AC"], lastChange: true },
  { id: "call-1d", name: "\uCF5C\uAE08\uB9AC(1\uC77C)", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010101000" },
  { id: "cd-91", name: "CD 91\uC77C", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010502000" },
  { id: "cp-91", name: "CP 91\uC77C", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010503000" },
  { id: "ktb-1y", name: "\uAD6D\uACE0\uCC44 1\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010190000" },
  { id: "ktb-2y", name: "\uAD6D\uACE0\uCC44 2\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010195000" },
  { id: "ktb-3y", name: "\uAD6D\uACE0\uCC44 3\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010200000" },
  { id: "ktb-5y", name: "\uAD6D\uACE0\uCC44 5\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010200001" },
  { id: "ktb-10y", name: "\uAD6D\uACE0\uCC44 10\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010210000" },
  { id: "ktb-20y", name: "\uAD6D\uACE0\uCC44 20\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010220000" },
  { id: "ktb-30y", name: "\uAD6D\uACE0\uCC44 30\uB144", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010230000" },
  { id: "corp-aa", name: "\uD68C\uC0AC\uCC44 3\uB144 AA-", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010300000" },
  { id: "corp-bbb", name: "\uD68C\uC0AC\uCC44 3\uB144 BBB-", unit: "%", cycle: "D", tableCode: "817Y002", itemCode: "010320000" },
  { id: "household-credit", name: "\uAC00\uACC4\uC2E0\uC6A9 \uC794\uC561", unit: "\uC870\uC6D0", scale: 1e-3, cycle: "Q", tableCode: "151Y001", itemCode: "1000000" },
  { id: "household-credit-loan", name: "\uAC00\uACC4\uB300\uCD9C \uC794\uC561(\uBD84\uAE30)", unit: "\uC870\uC6D0", scale: 1e-3, cycle: "Q", tableCode: "151Y001", itemCode: "1100000" },
  { id: "bank-household-loan", name: "\uC740\uD589 \uAC00\uACC4\uB300\uCD9C \uC794\uC561", unit: "\uC870\uC6D0", scale: 1e-3, cycle: "M", tableCode: "151Y002", want: ["\uC608\uAE08\uC740\uD589"] },
  { id: "deposit-household-loan", name: "\uC608\uAE08\uCDE8\uAE09\uAE30\uAD00 \uAC00\uACC4\uB300\uCD9C", unit: "\uC870\uC6D0", scale: 1e-3, cycle: "M", tableCode: "151Y002", want: ["\uC608\uAE08\uCDE8\uAE09\uAE30\uAD00"] },
  { id: "bank-mortgage", name: "\uC740\uD589 \uC8FC\uD0DD\uAD00\uB828\uB300\uCD9C", unit: "\uC870\uC6D0", scale: 1e-3, cycle: "M", tableCode: "151Y005", itemCode: "11110A0" },
  { id: "bank-delinquency", name: "\uC740\uD589 \uAC00\uACC4\uB300\uCD9C \uC5F0\uCCB4\uC728", unit: "%", cycle: "M", tableCode: "901Y054", want: ["\uAC00\uACC4\uB300\uCD9C", "\uC740\uD589\uC804\uCCB4"] },
  { id: "bank-delinquency-corp", name: "\uC740\uD589 \uAE30\uC5C5\uB300\uCD9C \uC5F0\uCCB4\uC728", unit: "%", cycle: "M", tableCode: "901Y054", want: ["\uAE30\uC5C5\uB300\uCD9C", "\uC740\uD589\uC804\uCCB4"] },
  { id: "loan-rate", name: "\uC608\uAE08\uC740\uD589 \uB300\uCD9C\uAE08\uB9AC", unit: "%", cycle: "M", tableCode: "121Y006", itemCode: "BECBLA01" },
  { id: "mortgage-rate", name: "\uC608\uAE08\uC740\uD589 \uC8FC\uD0DD\uB2F4\uBCF4\uB300\uCD9C\uAE08\uB9AC", unit: "%", cycle: "M", tableCode: "121Y006", itemCode: "BECBLA0302" },
  { id: "fx-reserves", name: "\uC678\uD658\uBCF4\uC720\uC561", unit: "", cycle: "M", table: ["\uC678\uD658\uBCF4\uC720\uC561"], item: [] }
];
var DEFAULT_STAT_IDS = ["base-rate", "ktb-3y", "ktb-10y", "cd-91", "corp-aa", "household-credit", "bank-household-loan", "bank-delinquency", "loan-rate", "fx-reserves"];
var tableCache = { at: 0, rows: null };
async function ecos(env, path) {
  const res = await fetch(`${ECOS}/${path.replace("{key}", env.ECOS_API_KEY)}`);
  if (!res.ok) throw new Error(`ECOS ${res.status}`);
  const body = await res.json();
  if (body.RESULT) throw new Error(`ECOS ${body.RESULT.CODE}`);
  return body;
}
__name(ecos, "ecos");
var pick2 = /* @__PURE__ */ __name((rows, keywords, nameOf) => {
  const hits = rows.filter((r) => keywords.every((k) => nameOf(r).includes(k)));
  return hits.sort((a, b) => nameOf(a).length - nameOf(b).length)[0] || null;
}, "pick");
var range = /* @__PURE__ */ __name((cycle, now = /* @__PURE__ */ new Date(), back = 36) => {
  const pad = /* @__PURE__ */ __name((n) => String(n).padStart(2, "0"), "pad");
  const ymd2 = /* @__PURE__ */ __name((d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`, "ymd");
  const ym = /* @__PURE__ */ __name((d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}`, "ym");
  const monthsAgo = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - back, 1));
  if (cycle === "D") return [ymd2(new Date(now.getTime() - 400 * 864e5)), ymd2(now)];
  if (cycle === "Q") return [`${monthsAgo.getUTCFullYear()}Q${Math.floor(monthsAgo.getUTCMonth() / 3) + 1}`, `${now.getUTCFullYear()}Q4`];
  return [ym(monthsAgo), ym(now)];
}, "range");
var plainName = /* @__PURE__ */ __name((name) => String(name || "").replace(/\s*\d+\)\s*$/, "").trim(), "plainName");
var filterRows = /* @__PURE__ */ __name((rows, want) => rows.filter((r) => {
  const names = [1, 2, 3, 4].map((i) => plainName(r[`ITEM_NAME${i}`]));
  return want.every((w) => names.includes(w));
}), "filterRows");
var latestPair = /* @__PURE__ */ __name((rows, lastChange) => {
  const points = rows.map((r) => ({ time: String(r.TIME), value: parseFloat(String(r.DATA_VALUE).replace(/,/g, "")), unit: r.UNIT_NAME || "" })).filter((p) => Number.isFinite(p.value)).sort((a, b) => a.time.localeCompare(b.time));
  if (!points.length) return null;
  const last = points[points.length - 1];
  const before = points.slice(0, -1);
  const prev = lastChange ? [...before].reverse().find((p) => p.value !== last.value) : before[before.length - 1];
  return { value: last.value, previous: prev ? prev.value : null, period: last.time, unit: last.unit };
}, "latestPair");
async function statOne(spec, env, tables) {
  let tableCode = spec.tableCode;
  let source = spec.tableCode;
  if (!tableCode) {
    const table = pick2(tables, spec.table, (r) => r.STAT_NAME);
    if (!table) throw new Error("\uD1B5\uACC4\uD45C \uC5C6\uC74C");
    tableCode = table.STAT_CODE;
    source = table.STAT_NAME;
  }
  let itemCode = spec.itemCode;
  if (!itemCode && !spec.want) {
    const items = (await ecos(env, `StatisticItemList/{key}/json/kr/1/500/${tableCode}`)).StatisticItemList.row;
    const item = spec.item.length ? pick2(items, spec.item, (r) => r.ITEM_NAME) : items[0];
    if (!item) throw new Error("\uD56D\uBAA9 \uC5C6\uC74C");
    itemCode = item.ITEM_CODE;
    source += ` / ${item.ITEM_NAME}`;
  }
  const [from, to] = range(spec.cycle, /* @__PURE__ */ new Date(), spec.want ? 18 : 36);
  const path = `StatisticSearch/{key}/json/kr/1/500/${tableCode}/${spec.cycle}/${from}/${to}` + (itemCode ? `/${itemCode}` : "");
  let rows = (await ecos(env, path)).StatisticSearch.row;
  const fetched = rows.length;
  if (spec.want) rows = filterRows(rows, spec.want);
  const pair = latestPair(rows, spec.lastChange);
  if (!pair) throw new Error(`\uAC12 \uC5C6\uC74C(${tableCode} \uC870\uD68C ${fetched}\uD589, \uC870\uAC74 \uC77C\uCE58 ${rows.length}\uD589${fetched >= 500 ? ", 500\uD589 \uD55C\uB3C4 \uB3C4\uB2EC" : ""})`);
  const scale = spec.scale || 1;
  return {
    id: spec.id,
    name: spec.name,
    cycle: spec.cycle,
    value: pair.value * scale,
    previous: pair.previous == null ? null : pair.previous * scale,
    period: pair.period,
    unit: spec.unit || pair.unit,
    source
  };
}
__name(statOne, "statOne");
var selectSpecs = /* @__PURE__ */ __name((idsParam) => {
  const ids = String(idsParam || "").split(",").map((s) => s.trim()).filter(Boolean).slice(0, 24);
  const chosen = STAT_SPECS.filter((s) => ids.includes(s.id));
  return chosen.length ? chosen : STAT_SPECS.filter((s) => DEFAULT_STAT_IDS.includes(s.id));
}, "selectSpecs");
async function stats(url, env, ctx) {
  if (!env.ECOS_API_KEY) return fail(503, "ECOS_API_KEY\uAC00 \uC124\uC815\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4");
  const specs = selectSpecs(url.searchParams.get("ids"));
  const cacheKey = new Request(`https://stats.cache/ecos-v4/${specs.map((s) => s.id).sort().join(",")}`);
  const cache = caches.default;
  const hit = await cache.match(cacheKey);
  if (hit) return hit;
  if (specs.some((s) => !s.tableCode) && (!tableCache.rows || Date.now() - tableCache.at > 864e5)) {
    tableCache = { at: Date.now(), rows: (await ecos(env, "StatisticTableList/{key}/json/kr/1/5000/")).StatisticTableList.row };
  }
  const settled = await Promise.allSettled(specs.map((s) => statOne(s, env, tableCache.rows)));
  const items = settled.filter((r) => r.status === "fulfilled").map((r) => r.value);
  const missing = specs.filter((_, i) => settled[i].status === "rejected").map((s) => s.id);
  const reasons = Object.fromEntries(specs.flatMap((s, i) => settled[i].status === "rejected" ? [[s.id, String(settled[i].reason?.message || settled[i].reason).slice(0, 160)]] : []));
  const response = new Response(JSON.stringify({ items, missing, reasons }), {
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=3600" }
  });
  if (items.length) ctx.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}
__name(stats, "stats");
var FISIS_SET = /* @__PURE__ */ new Set(["F", "G", "A", "H", "I", "E", "C"]);
var subSectors = /* @__PURE__ */ __name((sector) => sector === "F" ? ["F", "W"] : [sector], "subSectors");
var bundled = /* @__PURE__ */ __name((sector) => fisis_data_default && fisis_data_default.sectors && fisis_data_default.sectors[sector] || null, "bundled");
var fisisMemo = /* @__PURE__ */ new Map();
async function memo(key, ttl, build) {
  const hot = fisisMemo.get(key);
  if (hot && Date.now() - hot.at < ttl * 1e3) return hot.value;
  const value = await build();
  fisisMemo.set(key, { at: Date.now(), value });
  if (fisisMemo.size > 400) fisisMemo.delete(fisisMemo.keys().next().value);
  return value;
}
__name(memo, "memo");
var fisisCall = /* @__PURE__ */ __name((env, name, params) => fisisFetch(env.FISIS_API_KEY, name, params), "fisisCall");
async function fisis(url, env) {
  const action = url.pathname.slice("/fisis/".length);
  const sector = url.searchParams.get("sector") || "";
  if (!FISIS_SET.has(sector)) return fail(400, "\uC9C0\uC6D0\uD558\uC9C0 \uC54A\uB294 \uAD8C\uC5ED\uC785\uB2C8\uB2E4.");
  const parts = subSectors(sector).map((s) => ({ sector: s, data: bundled(s) }));
  const ready = parts.filter((p) => p.data);
  if (action === "companies") {
    if (ready.length) return json({ items: ready.flatMap((p) => p.data.companies).map(([code, name]) => ({ code, name })) });
    return json(await memo(`companies/${sector}`, 21600, async () => {
      const r = await fisisCall(env, "companySearch", { partDiv: sector });
      return { items: rowsOf(r.list).map((c) => ({ code: String(c.finance_cd || ""), name: String(c.finance_nm || "").trim() })).filter((c) => c.code && c.name && !CLOSED.test(c.name)) };
    }));
  }
  if (action === "ranking") {
    const assets = {};
    for (const p of ready) for (const [code, v] of Object.entries(p.data.assets || {})) assets[code] = { value: v[0], month: v[1], unit: v[2] };
    return json({ assets, next: null, total: ready.reduce((n, p) => n + p.data.companies.length, 0), ranked: ready.length > 0 });
  }
  if (action === "spec") return json(Object.fromEntries(ready.map((p) => [p.sector, p.data.spec])));
  if (action === "profile") {
    const finance2 = url.searchParams.get("finance") || "";
    if (!/^[0-9A-Za-z]{4,12}$/.test(finance2)) return fail(400, "\uD68C\uC0AC \uCF54\uB4DC\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4.");
    if (!ready.length) return fail(503, "\uACBD\uC601\uC815\uBCF4 \uC124\uC815\uC774 \uC544\uC9C1 \uB9CC\uB4E4\uC5B4\uC9C0\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uAD00\uB9AC\uC790\uAC00 Deploy news proxy\uB97C \uB2E4\uC2DC \uC2E4\uD589\uD574\uC57C \uD569\uB2C8\uB2E4.");
    const owner = ready.find((p) => p.data.companies.some(([code]) => code === finance2)) || ready[0];
    return json(await memo(`profile/${owner.sector}/${finance2}`, 21600, () => fisisProfile(env, owner.data, finance2)));
  }
  return new Response("Not found", { status: 404 });
}
__name(fisis, "fisis");
async function fisisProfile(env, data, finance2) {
  const spec = data.spec;
  const listNos = specListNos(spec).slice(0, 16);
  const companyName = (data.companies.find(([code]) => code === finance2) || [])[1] || "";
  const errors = [];
  let used = 0;
  const budgeted = /* @__PURE__ */ __name((p) => {
    if (++used > 45) throw new Error("FISIS \uD638\uCD9C \uC218\uAC00 \uD55C\uB3C4\uC5D0 \uAC00\uAE4C\uC6CC \uC77C\uBD80 \uD1B5\uACC4\uD45C\uB97C \uAC74\uB108\uB701\uB2C8\uB2E4.");
    return fisisCall(env, "statisticsInfoSearch", p);
  }, "budgeted");
  const tableList = await Promise.all(
    listNos.map(
      (listNo) => fetchTable(budgeted, finance2, listNo, spec.terms && spec.terms[listNo] || "Q").catch((e) => {
        errors.push(e);
        return null;
      })
    )
  );
  const tables = Object.fromEntries(listNos.map((l, i) => [l, tableList[i]]));
  const result = buildMetrics(spec, tables);
  if (!result.metrics.length && errors.length) throw errors[0];
  return { name: result.name || companyName, term: result.term, basisOptions: result.basisOptions, metrics: result.metrics };
}
__name(fisisProfile, "fisisProfile");
async function dart(url, env) {
  const action = url.pathname.slice("/dart/".length);
  const corp = url.searchParams.get("corp") || "";
  const needCorp = /* @__PURE__ */ __name(() => {
    if (!/^\d{8}$/.test(corp)) throw Object.assign(new Error("\uD68C\uC0AC \uCF54\uB4DC\uAC00 \uC62C\uBC14\uB974\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4."), { status: 400 });
  }, "needCorp");
  if (action === "search") {
    const q = (url.searchParams.get("q") || "").trim().slice(0, 40);
    if (corpCount() === 0) return fail(503, "\uD68C\uC0AC \uBAA9\uB85D\uC774 \uC544\uC9C1 \uB9CC\uB4E4\uC5B4\uC9C0\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uAD00\uB9AC\uC790\uAC00 DART_API_KEY\uB97C \uB4F1\uB85D\uD558\uACE0 Deploy news proxy\uB97C \uB2E4\uC2DC \uC2E4\uD589\uD574\uC57C \uD569\uB2C8\uB2E4.");
    const lookup = /* @__PURE__ */ __name((c) => memo(`dart/company/${c}`, 21600, () => company(env, c)), "lookup");
    return json({ items: await withDartClass(searchCorps(q), lookup) });
  }
  if (action === "company") {
    needCorp();
    return json(await memo(`dart/company/${corp}`, 21600, () => company(env, corp)));
  }
  if (action === "holders") {
    needCorp();
    return json(await memo(`dart/holders/${corp}`, 21600, () => holders(env, corp)));
  }
  if (action === "filings") {
    needCorp();
    const type = url.searchParams.get("type") || "";
    const from = url.searchParams.get("from") || "";
    const to = url.searchParams.get("to") || "";
    return json(await memo(`dart/filings/${corp}/${type}/${from}/${to}`, 600, () => filings(env, corp, type, from, to)));
  }
  if (action === "finance") {
    needCorp();
    return json(await memo(`dart/finance/${corp}`, 21600, async () => {
      const c = await memo(`dart/company/${corp}`, 21600, () => company(env, corp));
      return finance(env, corp, c.accountMonth);
    }));
  }
  return new Response("Not found", { status: 404 });
}
__name(dart, "dart");

// ../../../wt/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// ../../../wt/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
__name(reduceError, "reduceError");
var jsonError = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    const body = JSON.stringify(error);
    const headers = {
      "Content-Type": "application/json",
      "MF-Experimental-Error-Stack": "true"
    };
    const encoded = encodeURIComponent(body);
    if (encoded.length <= 8192) {
      headers["MF-Experimental-Error-Stack-Payload"] = encoded;
    }
    return new Response(body, { status: 500, headers });
  }
}, "jsonError");
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-6eWoQn/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = worker_default;

// ../../../wt/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-6eWoQn/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class ___Facade_ScheduledController__ {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  scheduledTime;
  cron;
  static {
    __name(this, "__Facade_ScheduledController__");
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof ___Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = /* @__PURE__ */ __name((request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    }, "#fetchDispatcher");
    #dispatcher = /* @__PURE__ */ __name((type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    }, "#dispatcher");
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  DEFAULT_STAT_IDS,
  STAT_SPECS,
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default,
  filterRows,
  latestPair,
  pick2 as pick,
  range,
  selectSpecs
};
//# sourceMappingURL=worker.js.map
