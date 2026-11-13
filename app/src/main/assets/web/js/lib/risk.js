// 금융사 리스크 대시보드의 계산 부분(화면·서버와 무관한 순수 함수). 노드에서 바로 시험할 수 있다.
// 자료(provider)는 스냅샷(시뮬레이션) 또는 서버(risk-live.js) 어느 쪽이든 같은 모양이다:
//   peers(sector) → [{ code, name, assets, ratios: {키: [[YYYYMM, 값], …]}, netincome_cum, assetsSeries }]

/** 권역별 사분면 축. 업권마다 쓰는 건전성 지표가 달라 지표 키·방향을 여기서 정한다. */
export const SECTOR_CFG = {
  A: { label: "은행", x: { key: "bis", label: "BIS 자기자본비율", higher: true }, y: { key: "npl", label: "고정이하여신비율", higher: false }, trend: ["bis", "npl", "delinq"],
    names: { bis: "BIS 자기자본비율", npl: "고정이하여신비율", delinq: "연체율", lcr: "유동성커버리지비율" } },
  E: { label: "저축은행", x: { key: "bis", label: "BIS 자기자본비율", higher: true }, y: { key: "npl", label: "고정이하여신비율", higher: false }, trend: ["bis", "npl", "delinq"],
    names: { bis: "BIS 자기자본비율", npl: "고정이하여신비율", delinq: "연체율", liq: "유동성비율" } },
  F: { label: "증권", x: { key: "ncr", label: "순자본비율(연결 우선)", higher: true }, y: { key: "lev", label: "레버리지비율", higher: false }, trend: ["ncr", "lev", "liq"],
    names: { ncr: "순자본비율(연결 우선)", lev: "레버리지비율", liq: "유동성비율" } },
  H: { label: "생보", x: { key: "solv", label: "지급여력비율", higher: true }, y: { key: "roa", label: "자산 대비 당기순이익(누계)", higher: true }, trend: ["solv", "roa"],
    names: { solv: "지급여력비율", roa: "자산 대비 당기순이익(누계)" } },
  I: { label: "손보", x: { key: "solv", label: "지급여력비율", higher: true }, y: { key: "roa", label: "자산 대비 당기순이익(누계)", higher: true }, trend: ["solv", "roa"],
    names: { solv: "지급여력비율", roa: "자산 대비 당기순이익(누계)" } },
  C: { label: "카드", x: { key: "adj", label: "조정자기자본비율", higher: true }, y: { key: "npl", label: "고정이하채권비율", higher: false }, trend: ["adj", "npl"],
    names: { adj: "조정자기자본비율", npl: "고정이하채권비율" } },
};
export const SECTOR_ORDER = ["A", "F", "H", "I", "C", "E"];

// ---- 값 읽기 ----
const num = (v) => typeof v === "number" && Number.isFinite(v);
const ptsOf = (row, key) => {
  if (key === "roa") {
    // 당기순이익 누계 ÷ 자산총계(같은 시점). 누계라 분기가 지날수록 커지므로 같은 시점끼리만 비교한다.
    const as = new Map(row.assetsSeries || []);
    return (row.netincome_cum || []).map(([m, v]) => (num(v) && as.get(m) > 0 ? [m, (v / as.get(m)) * 100] : null)).filter(Boolean);
  }
  // 증권 순자본비율: 연결이 있는 회사는 연결, 없으면 개별(FISIS ncr_c 우선, 없으면 ncr)
  if (key === "ncr") { const c = (row.ratios?.ncr_c || []).filter((p) => num(p[1])); return c.length ? row.ratios.ncr_c : row.ratios?.ncr || []; }
  return row.ratios?.[key] || [];
};
export const ncrBasis = (row) => ((row.ratios?.ncr_c || []).some((p) => num(p[1])) ? "연결" : "개별");
export const valueAt = (row, key, month) => { const p = ptsOf(row, key).find((x) => x[0] === month); return p && num(p[1]) ? p[1] : null; };
export const seriesOf = (row, key, months) => months.map((m) => [m, valueAt(row, key, m)]);

/** 대상 지표를 가진 회사가 절반 이상인 가장 최근 시점. */
export function latestMonth(rows, key) {
  const cnt = {};
  for (const r of rows) for (const [m, v] of ptsOf(r, key)) if (num(v)) cnt[m] = (cnt[m] || 0) + 1;
  const need = Math.max(1, Math.ceil(rows.length / 2));
  return Object.keys(cnt).filter((m) => cnt[m] >= need).sort().pop() || Object.keys(cnt).sort().pop() || null;
}
/** 분기 말월(03·06·09·12) 기준으로 최신 포함 n개의 과거 시점. */
export function monthsBack(month, n) {
  const out = [];
  let y = +month.slice(0, 4), m = +month.slice(4);
  for (let i = 0; i < n; i++) {
    out.unshift(`${y}${String(m).padStart(2, "0")}`);
    m -= 3;
    if (m <= 0) { m += 12; y -= 1; }
  }
  return out;
}
const mean = (a) => a.reduce((s, v) => s + v, 0) / a.length;
const median = (a) => { const s = [...a].sort((x, y) => x - y); const k = s.length >> 1; return s.length % 2 ? s[k] : (s[k - 1] + s[k]) / 2; };

/** 업권 평균·중앙값(해당 시점). 비교 기준은 중앙값. */
export function sectorStat(rows, key, month) {
  const v = rows.map((r) => valueAt(r, key, month)).filter(num);
  return v.length ? { n: v.length, mean: mean(v), median: median(v) } : null;
}
/** 순위(1=가장 좋음). higher=true면 값이 클수록 좋음. */
export function rankOf(rows, row, key, month, higher) {
  const mine = valueAt(row, key, month);
  if (mine == null) return null;
  const all = rows.map((r) => valueAt(r, key, month)).filter(num);
  return { rank: all.filter((v) => (higher ? v > mine : v < mine)).length + 1, n: all.length };
}

/** 사분면용 점 목록과 평균. cfg.x/y 기준, 두 값이 모두 있는 회사만. */
export function quadrantData(rows, sector, month, selfCode = null) {
  const cfg = SECTOR_CFG[sector];
  const m = month || latestMonth(rows, cfg.x.key);
  const pts = rows.map((r) => ({ id: r.code, name: sector === "F" && cfg.x.key === "ncr" ? `${r.name} (${ncrBasis(r)})` : r.name, x: valueAt(r, cfg.x.key, m), y: valueAt(r, cfg.y.key, m), big: r.assets || 0, self: r.code === selfCode }))
    .filter((p) => num(p.x) && num(p.y));
  // 증권 순자본비율처럼 극단값이 큰 업권이 있어 평균 대신 중앙값을 기준선으로 쓴다.
  const xMean = pts.length ? median(pts.map((p) => p.x)) : 0;
  const yMean = pts.length ? median(pts.map((p) => p.y)) : 0;
  const danger = (p) => p.x < xMean && (cfg.y.higher ? p.y < yMean : p.y > yMean);
  return { cfg, month: m, points: pts, xMean, yMean, excluded: rows.length - pts.length, dangerIds: pts.filter(danger).map((p) => p.id) };
}

// ---- 제재 ----
const HIGH = /영업정지|업무정지|영업\s*일부정지|인가취소|등록취소|허가취소|영업취소|기관경고|해임|직무정지|문책경고|정직/;
const MID = /기관주의|주의적\s*경고|주의|견책|감봉|과징금|과태료|시정명령|면직|경고/;
/** 제재 한 건의 무게: 3=중(영업정지·기관경고·해임 등) 2=금전·주의 1=경미. */
export function severityOf(c) {
  if (HIGH.test(c.inst || "") || HIGH.test(c.exec || "")) return 3;
  if (c.fine > 0 || c.penalty > 0 || MID.test(c.inst || "") || MID.test(c.exec || "") || HIGH.test(c.staff || "")) return 2;
  return 1;
}
export const SEVERITY = { 3: ["중", "중징계·영업제한 등"], 2: ["보통", "금전제재·주의 등"], 1: ["경미", "직원 조치·경미"] };
export const caseYear = (c) => +c.date.slice(0, 4);

/** 연도별 건수(최근 n년, 비어 있는 해도 포함)와 무게별 분해. */
export function sanctionYears(cases, endYear, n = 8) {
  const out = [];
  for (let y = endYear - n + 1; y <= endYear; y++) {
    const cs = cases.filter((c) => caseYear(c) === y);
    out.push({ year: y, high: cs.filter((c) => severityOf(c) === 3).length, mid: cs.filter((c) => severityOf(c) === 2).length, low: cs.filter((c) => severityOf(c) === 1).length, total: cs.length });
  }
  return out;
}
export const wonText = (v) => (v >= 1e8 ? `${(v / 1e8).toLocaleString("en-US", { maximumFractionDigits: 1 })}억원` : v >= 1e4 ? `${Math.round(v / 1e4).toLocaleString("en-US")}만원` : `${v}원`);

// ---- 리스크 신호(상대 비교 중심: 법정 기준선을 단정하지 않고, 업권 평균·추세·제재 이력으로만 알려 준다) ----
export function signals({ row, rows, sector, month, cases, nowYear }) {
  const cfg = SECTOR_CFG[sector];
  const out = [];
  const mk = (level, text) => out.push({ level, text });
  for (const ax of [cfg.x, cfg.y]) {
    const v = valueAt(row, ax.key, month);
    const st = sectorStat(rows, ax.key, month);
    if (v == null || !st) continue;
    const prev = valueAt(row, ax.key, monthsBack(month, 2)[0]);
    const yago = valueAt(row, ax.key, monthsBack(month, 5)[0]);
    const worse = ax.higher ? v < st.median : v > st.median;
    const rel = v < st.median ? "낮습니다" : "높습니다";
    mk(worse ? "warn" : "ok", `${ax.label} ${v.toFixed(2)}%로 업권 중앙값(${st.median.toFixed(2)}%)보다 ${rel}${worse ? "" : " (양호)"}.`);
    const dir = (a, b) => (ax.higher ? a < b : a > b);
    if (prev != null && Math.abs(v - prev) >= Math.max(0.3, Math.abs(st.median) * 0.05) && dir(v, prev)) mk("warn", `${ax.label}가 직전 분기보다 ${Math.abs(v - prev).toFixed(2)}%p ${ax.higher ? "하락" : "상승"}했습니다.`);
    if (yago != null && Math.abs(v - yago) >= Math.max(0.5, Math.abs(st.median) * 0.1) && dir(v, yago)) mk("warn", `${ax.label}가 1년 전보다 ${Math.abs(v - yago).toFixed(2)}%p ${ax.higher ? "하락" : "상승"}했습니다.`);
  }
  const recent = cases.filter((c) => caseYear(c) >= nowYear - 2);
  const high = recent.filter((c) => severityOf(c) === 3);
  if (high.length) mk("warn", `최근 3년 안에 중징계·영업제한 성격의 제재가 ${high.length}건 있습니다(가장 최근 ${high[0].date.replace(/-/g, ".")}).`);
  else if (recent.length) mk("info", `최근 3년 제재공시 ${recent.length}건(중징계 없음).`);
  else mk("ok", "최근 3년 제재공시가 없습니다.");
  const money = recent.reduce((s, c) => s + (c.fine || 0) + (c.penalty || 0), 0);
  if (money > 0) mk("info", `최근 3년 과태료·과징금 합계 ${wonText(money)}.`);
  const order = { warn: 0, info: 1, ok: 2 };
  return out.sort((a, b) => order[a.level] - order[b.level]);
}

// ---- 이름 맞추기(FISIS ↔ DART ↔ 제재공시) ----
export const nameKey = (n) => String(n || "").replace(/주식회사|유한회사|\(주\)|㈜|\(유\)|\s|[.,·]/g, "").toLowerCase();
/** DART 검색 후보 중 FISIS 회사와 가장 가까운 것. 이름이 같으면 exact, 포함 관계면 guess, 없으면 none. */
export function pickDart(fisisName, items) {
  const k = nameKey(fisisName);
  const ex = items.find((i) => nameKey(i.name) === k);
  if (ex) return { match: "exact", item: ex };
  const g = items.find((i) => { const o = nameKey(i.name); return o && (o.includes(k) || k.includes(o)); });
  return g ? { match: "guess", item: g } : { match: "none", item: null };
}

// ---- 업권 현황: 규모 순위·비율 분포 ----
export const OVERVIEW_ORDER = [...SECTOR_ORDER, "G"];
export const SECTOR_NAME = { ...Object.fromEntries(Object.entries(SECTOR_CFG).map(([k, v]) => [k, v.label])), G: "운용" };
/** 분위수(선형 보간). 정렬된 배열 기준. */
const quant = (sorted, f) => { const i = (sorted.length - 1) * f; const lo = Math.floor(i), hi = Math.ceil(i); return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo); };
/** 분포: 값 목록 → 통계와 히스토그램 칸(극단값은 2~98% 범위 밖이면 가장자리 칸에 모아 센다). */
export function distribution(values, binCount = 12) {
  const v = values.filter(num).sort((a, b) => a - b);
  if (!v.length) return null;
  const st = { n: v.length, min: v[0], max: v[v.length - 1], q1: quant(v, 0.25), med: quant(v, 0.5), q3: quant(v, 0.75) };
  let lo = quant(v, 0.02), hi = quant(v, 0.98);
  if (!(hi > lo)) { lo = st.min; hi = st.max > st.min ? st.max : st.min + 1; }
  const w = (hi - lo) / binCount;
  const bins = Array.from({ length: binCount }, (_, i) => ({ x0: lo + w * i, x1: lo + w * (i + 1), n: 0 }));
  let below = 0, above = 0;
  for (const x of v) { if (x < lo) { below++; bins[0].n++; } else if (x > hi) { above++; bins[binCount - 1].n++; } else bins[Math.min(binCount - 1, Math.floor((x - lo) / w))].n++; }
  return { ...st, bins, lo, hi, below, above };
}
/** 규모 순위: [{ code, name, value }] 를 값 큰 순으로, 점유율·누적 상위5 점유율 계산. */
export function rankSize(list) {
  const rows = list.filter((r) => num(r.value) && r.value > 0).sort((a, b) => b.value - a.value || a.name.localeCompare(b.name, "ko"));
  const total = rows.reduce((s, r) => s + r.value, 0);
  const top5 = rows.slice(0, 5).reduce((s, r) => s + r.value, 0);
  return { rows: rows.map((r, i) => ({ ...r, rank: i + 1, share: total ? r.value / total : 0 })), total, top5Share: total ? top5 / total : 0, n: rows.length, missing: list.length - rows.length, median: rows.length ? median(rows.map((r) => r.value)) : 0 };
}
