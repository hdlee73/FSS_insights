// 회사 대시보드 사전수집. 매일 돌려 risk-data 브랜치에 JSON으로 올린다(웹·앱이 먼저 읽고, 없는 회사만 실시간 조회).
//  - peers-<권역>.json : 권역 전체 회사의 건전성 지표 추이(업권 순위·사분면·회사 대시보드에 모두 쓰임)
//  - top.json          : top15.json 회사의 DART 개황·지배구조
//  - meta.json         : 수집 시각
// 서버(Worker)의 공개 읽기 경로를 쓰므로 별도 비밀키가 필요 없다(키는 Worker가 가진다).
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { findDart } from "./dartmatch.mjs"; // 웹(js/lib/dartmatch.js)과 같은 규칙. 이 파일은 그 사본이므로 함께 고친다.

const BASE = process.env.RISK_BASE || "https://news-proxy.hdlee73.workers.dev";
const ORIGIN = process.env.RISK_ORIGIN || "https://www.fssinsights.com";
const OUT = process.argv[2] || "out";
const KEEP = ["bis", "npl", "delinq", "ncr", "ncr_c", "lev", "liq", "solv", "adj", "lcr", "roa"];
const SECTORS = ["A", "E", "F", "H", "I", "C"];
const FOREIGN = /지점|골드만|모간스탠리|모건스탠리|메릴린치|비엔피|BNP|씨엘에스에이|CLSA|크레디|노무라|맥쿼리|유비에스|UBS|제이피모간|JP모간|도이치|소시에테|나틱시스|다이와|미즈호|HSBC|에이치에스비씨|바클레이|스탠다드차타드|씨티|ING|아이엔지|SMBC|라자드|이스트스프링|아이씨엠|ICM|CMS|씨엠에스|에스지|SG|CGS|CIMB|씨아이엠비|글로벌마켓|알비에스|RBS|스미토모|미쓰비시|도쿄|SBI증권/;
const clean = (n) => String(n || "").replace(/\(\s*주\s*\)|㈜|주식회사/g, " ").replace(/\s+/g, " ").trim();
const nameKey = (n) => String(n || "").replace(/주식회사|유한회사|\(주\)|㈜|\(유\)|\s|[.,·]/g, "").toLowerCase();

async function api(path, params, tries = 4) {
  const url = BASE + path + "?" + new URLSearchParams(params);
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url, { headers: { Origin: ORIGIN } });
      if (r.ok) return await r.json();
      if (r.status < 500 && r.status !== 429) throw new Error(`${r.status} ${path}`);
    } catch (e) { if (i === tries - 1) throw e; }
    await new Promise((r) => setTimeout(r, 1500 * (i + 1)));
  }
  throw new Error("실패 " + path);
}
async function pool(items, n, fn) {
  const out = new Array(items.length); let i = 0;
  await Promise.all(Array.from({ length: n }, async () => { while (i < items.length) { const k = i++; out[k] = await fn(items[k]).catch((e) => { console.error("건너뜀:", e.message); return null; }); } }));
  return out;
}

async function collectSector(sector) {
  const [co, rk] = await Promise.all([api("/fisis/companies", { sector }), api("/fisis/ranking", { sector, offset: 0 }).catch(() => ({}))]);
  const assets = rk.assets || {};
  let list = (co.items || []).map((c) => ({ code: c.code, name: clean(c.name), assets: assets[c.code]?.value ?? 0 })).filter((c) => c.code && c.name).sort((a, b) => b.assets - a.assets);
  const all = list;
  list = list.filter((c) => !(sector === "F" && FOREIGN.test(c.name)));
  const rows = (await pool(list, 4, async (c) => {
    const p = await api("/fisis/profile", { sector, finance: c.code });
    const ratios = {};
    for (const m of p.metrics || []) if (m.group === "ratio" && KEEP.includes(m.key)) ratios[m.key] = (m.points || []).slice(-12).map((x) => [x.month, x.value]);
    const fin = (k) => (p.metrics || []).find((m) => m.group === "fin" && m.key === k && m.basis === "별도")?.points.slice(-12).map((x) => [x.month, x.value]) || [];
    return { code: c.code, name: c.name, assets: c.assets, ratios, netincome_cum: fin("netincome_cum"), assetsSeries: fin("assets") };
  })).filter(Boolean);
  return { all, rows };
}

async function dartOf(name, parent, own = {}) {
  const ownCorp = own[nameKey(name)] || own[name];
  if (ownCorp) {
    const [company, holders] = await Promise.all([api("/dart/company", { corp: ownCorp }).catch(() => null), api("/dart/holders", { corp: ownCorp }).catch(() => null)]);
    return { rel: "본인", match: "exact", corp: ownCorp, name: company?.name || clean(name), company, holders };
  }
  const hit = parent[nameKey(name)] || parent[name];
  const rel = hit ? hit[1] : "본인";
  const ymd = (y) => { const d = new Date(); d.setFullYear(d.getFullYear() + y); return d.toISOString().slice(0, 10).replace(/-/g, ""); };
  const search = async (q) => (await api("/dart/search", { q }).catch(() => ({ items: [] }))).items || [];
  const detail = (c) => api("/dart/company", { corp: c });
  const recent = async (c) => (await api("/dart/filings", { corp: c, type: "a", from: ymd(-2), to: ymd(0) })).items?.[0]?.date || "";
  const { match, item } = hit ? await findDart(hit[0], search) : await findDart(clean(name), search, detail, recent);
  if (!item) return { rel, match: "none" };
  const [company, holders] = await Promise.all([api("/dart/company", { corp: item.corp }).catch(() => null), api("/dart/holders", { corp: item.corp }).catch(() => null)]);
  return { rel, match: rel === "본인" ? match : "exact", corp: item.corp, name: item.name, company, holders };
}

mkdirSync(OUT, { recursive: true });
const cfg = JSON.parse(readFileSync(new URL("./top15.json", import.meta.url), "utf8"));
const fisisList = {};
let failed = 0;
for (const s of SECTORS) {
  const { all, rows } = await collectSector(s);
  fisisList[s] = all;
  // 너무 적게 받았으면(서버 장애) 이전 자료를 덮지 않도록 실패로 끝낸다.
  if (rows.length < Math.max(3, all.length * 0.8) && !(s === "F")) { console.error(`권역 ${s}: ${rows.length}/${all.length}곳만 수집 — 중단`); failed++; continue; }
  writeFileSync(`${OUT}/peers-${s}.json`, JSON.stringify({ sector: s, rows }));
  console.log(`권역 ${s}: ${rows.length}곳`);
}
const top = {};
for (const t of cfg.companies) {
  const f = (fisisList[t.sector] || []).find((c) => c.name.includes(t.name));
  if (!f) { console.error("경고: FISIS에서 못 찾음(top15.json 확인):", t.name); continue; }
  const dart = await dartOf(f.name, cfg.parentDart || {}, cfg.ownDart || {}).catch((e) => { console.error("DART 실패:", f.name, e.message); return null; });
  if (!dart) continue; // 상위 15개사 일부 실패는 경고만(화면이 실시간 조회로 대신한다)
  top[`${t.sector}:${f.code}`] = { name: f.name, dart };
  console.log("DART", f.name, dart.match, dart.name || "");
}
writeFileSync(`${OUT}/top.json`, JSON.stringify({ companies: top }));
writeFileSync(`${OUT}/meta.json`, JSON.stringify({ updated: new Date(Date.now() + 9 * 3600e3).toISOString().slice(0, 16).replace("T", " ") + " KST", companies: Object.keys(top).length }));
if (failed) { console.error("실패 항목 수:", failed); process.exit(1); }
