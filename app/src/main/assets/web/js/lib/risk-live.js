// 리스크 대시보드의 실제 자료 공급원. 새 서버 기능 없이 기존 읽기 전용 경로만 쓴다:
//   /fisis/companies, /fisis/ranking, /fisis/profile (금융통계) · /dart/search, /dart/company, /dart/holders (전자공시) · 제재공시(sanction-data).
import { api, cached } from "../core.js";
import { casesOf, loadSanctions } from "./sanctions.js";
import { nameKey } from "./risk.js";
import { findDart } from "./dartmatch.js";

const TTL = 6 * 3600e3;
// 매일 새로 모아 올리는 사전수집 자료(GitHub Actions risk-data). 있으면 이걸 먼저 쓰고, 없거나 오래됐으면 실시간 조회로 대신한다.
const PRE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/risk-data/";
const STALE_MS = 5 * 86400e3;
const pre = (name) => cached("risk:pre:" + name, TTL, async () => {
  const r = await fetch(PRE + name + "?r=1");
  if (!r.ok) throw new Error("사전수집 자료 없음");
  return r.json();
});
const preFresh = () => cached("risk:pre:ok", TTL, async () => {
  const m = await pre("meta.json");
  const t = Date.parse(String(m.updated || "").replace(" KST", "+09:00").replace(" ", "T"));
  if (!(Date.now() - t < STALE_MS)) throw new Error("사전수집 자료가 오래됨");
  return true;
});
const prePeers = async (sector) => { await preFresh(); const d = await pre(`peers-${sector}.json`); if (!d.rows?.length) throw new Error("빈 자료"); return d.rows; };
const KEEP = ["bis", "npl", "delinq", "ncr", "ncr_c", "lev", "liq", "solv", "adj", "lcr"];
const clean = (n) => String(n || "").replace(/\(\s*주\s*\)|㈜|주식회사/g, " ").replace(/\s+/g, " ").trim().replace(/지주회사$/, "지주");
// 이름 검색으로는 같은 이름의 옛 법인(폐지·합병)과 섞이거나 공시회사명 목록에서 빠지는 회사의 DART 고유번호. DART 누리집에서 최근 사업보고서(또는 공시) 제출을 확인한 현행 법인만 둔다(2026-11-13 확인).
// 국민은행(060000)·신한은행·하나은행·우리은행·농협은행·KB증권(공시회사명 케이비증권)은 모두 자체 DART 자료가 있어 모회사로 대신하지 않는다.
export const OWN_DART = { 국민은행: "00386937", 신한은행: "00149293", 하나은행: "00158909", 우리은행: "00254045", 농협은행: "00908757", "KB증권": "00164876", "kb국민은행": "00386937", 케이비국민은행: "00386937", "nh농협은행": "00908757", 엔에이치농협은행: "00908757", "kb증권": "00164876", 케이비증권: "00164876" };
// 자체 DART 자료가 정말 없는 금융지주 자회사만 여기에 두어 모회사로 잇는다(현재 없음). 형식: 회사명: [모회사 검색어, "모회사(금융지주)"]
export const PARENT_DART = {};

const ymd = (y) => { const d = new Date(); d.setFullYear(d.getFullYear() + y); return d.toISOString().slice(0, 10).replace(/-/g, ""); };
async function pool(items, n, fn) {
  const out = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => { while (i < items.length) { const k = i++; out[k] = await fn(items[k]).catch(() => null); } }));
  return out;
}

const companies = (sector) => cached("risk:co:" + sector, TTL, async () => {
  const rows = await prePeers(sector).catch(() => null);
  if (rows) return rows.map((r) => ({ code: r.code, name: r.name, assets: r.assets })).sort((a, b) => b.assets - a.assets);
  const [co, rk] = await Promise.all([api("/fisis/companies", { sector }), api("/fisis/ranking", { sector, offset: 0 }).catch(() => ({}))]);
  const assets = rk.assets || {};
  return (co.items || []).map((c) => ({ code: c.code, name: clean(c.name), assets: assets[c.code]?.value ?? 0 })).filter((c) => c.code && c.name).sort((a, b) => b.assets - a.assets);
});

const FOREIGN = /지점|골드만|모간스탠리|모건스탠리|메릴린치|비엔피|BNP|씨엘에스에이|CLSA|크레디|노무라|맥쿼리|유비에스|UBS|제이피모간|JP모간|도이치|소시에테|나틱시스|다이와|미즈호|HSBC|에이치에스비씨|바클레이|스탠다드차타드|씨티|ING|아이엔지|SMBC|라자드|이스트스프링|아이씨엠|ICM|CMS|씨엠에스|에스지|SG|CGS|CIMB|씨아이엠비|글로벌마켓|알비에스|RBS|스미토모|미쓰비시|도쿄|SBI증권/;
const peers = (sector) => cached("risk:peers:" + sector, TTL, async () => {
  const pc = await prePeers(sector).catch(() => null);
  if (pc) return pc;
  const list = (await companies(sector)).filter((c) => !(sector === "F" && FOREIGN.test(c.name))); // 증권은 외국계 지점 제외, 전체 회사
  const rows = await pool(list, 6, async (c) => {
    const p = await api("/fisis/profile", { sector, finance: c.code });
    const ratios = {};
    for (const m of p.metrics || []) if (m.group === "ratio" && KEEP.includes(m.key)) ratios[m.key] = (m.points || []).slice(-12).map((x) => [x.month, x.value]);
    const fin = (k) => (p.metrics || []).find((m) => m.group === "fin" && m.key === k && m.basis === "별도")?.points.slice(-12).map((x) => [x.month, x.value]) || [];
    return { code: c.code, name: c.name, assets: c.assets, ratios, netincome_cum: fin("netincome_cum"), assetsSeries: fin("assets") };
  });
  return rows.filter(Boolean);
});

const loadDart = async (corp) => {
  const [company, holders] = await Promise.all([
    cached("dart.c:" + corp, 3600e3, () => api("/dart/company", { corp })).catch(() => null),
    cached("dart.h:" + corp, 3600e3, () => api("/dart/holders", { corp })).catch(() => null),
  ]);
  return { company, holders };
};

export async function dartOf(name, key) {
  const own = OWN_DART[nameKey(name)] || OWN_DART[name];
  if (key) {
    const t = await preFresh().then(() => pre("top.json")).catch(() => null);
    const hit = t?.companies?.[key]?.dart;
    // 사전수집에 모회사 자료(rel)가 남아 있거나, 확인된 자체 고유번호와 다른 회사가 담겼으면 쓰지 않고 실시간으로 조회한다.
    if (hit && (!hit.rel || hit.rel === "본인") && (!own || hit.corp === own)) return hit;
  }
  if (own) {
    const d = await loadDart(own);
    return { rel: "본인", match: "exact", corp: own, name: d.company?.name || clean(name), ...d };
  }
  const hit = PARENT_DART[nameKey(name)] || PARENT_DART[name];
  const search = (q) => cached("risk:ds:" + q, 600e3, () => api("/dart/search", { q })).then((r) => r.items || []);
  const detail = (c) => cached("dart.c:" + c, 3600e3, () => api("/dart/company", { corp: c }));
  const recent = (c) => cached("dart.r:" + c, 6 * 3600e3, () => api("/dart/filings", { corp: c, type: "a", from: ymd(-2), to: ymd(0) })).then((r) => r.items?.[0]?.date || "");
  // 이름 규칙(dartmatch.js): 영문↔한글 읽기·꼬리말·구 사명·정식명칭 확인, 같은 이름 법인이 여럿이면 최근 공시가 있는 현행 법인.
  const { match, item } = hit ? await findDart(hit[0], search) : await findDart(clean(name), search, detail, recent);
  const rel = hit ? hit[1] : "본인";
  if (!item) return { rel, match: "none" };
  const d = await loadDart(item.corp);
  return { rel, match: rel === "본인" ? match : "exact", corp: item.corp, name: item.name, ...d };
}

export function liveProvider() {
  return {
    info: null,
    companies,
    peers,
    async overview(sector) {
      if (sector === "G") {
        const fm = await import("./fundaum.js");
        const [list, fund] = await Promise.all([companies("G"), fm.loadFundAum()]);
        const items = list.map((c) => { const f = fm.findCompany(fund, c.name, "G"); return { code: c.code, name: c.name, value: f ? f.pub[0] + f.pri[0] : 0 }; });
        return { items, rows: null, basis: "펀드 순자산(공모+사모)", asOf: fm.dateLabel(fund.asOf.recent), scope: "금융투자협회 자료" };
      }
      const rows = await peers(sector);
      return { items: rows.map((r) => ({ code: r.code, name: r.name, value: r.assets })), rows, basis: "총자산", asOf: "최근 분기말", scope: sector === "F" ? "외국계 지점 제외" : "" };
    },
    async company(sector, code) {
      const row = (await companies(sector)).find((c) => c.code === code);
      const name = row?.name || "";
      const [dart, san] = await Promise.all([dartOf(name), loadSanctions().catch(() => null)]);
      const cases = san ? casesOf(san, name).map((c) => ({ id: c.id, date: c.date, co: c.company, inst: c.inst, exec: c.exec, staff: c.staff, fine: c.fine, penalty: c.penalty, view: c.viewUrl, items: c.items.map((i) => ({ t: i.title, laws: i.laws })) })).sort((a, b) => (a.date < b.date ? 1 : -1)) : [];
      return { dart, sanctions: cases, nowYear: new Date().getFullYear() };
    },
  };
}
