// 리스크 대시보드(대시보드·업권 현황)를 저장용 문서(doc)로 만든다. 형식은 report.js의 saveReport(PDF·한글 HWPX)가 받는 모양.
// 차트는 그림 대신 같은 값을 표로 담는다(앱 WebView에서도 한글 문서로 저장되고, PDF는 인쇄 창을 쓰므로).
import { SECTOR_CFG, SEVERITY, distribution, latestMonth, monthsBack, ncrBasis, quadrantData, rankOf, rankSize, sanctionYears, sectorStat, seriesOf, severityOf, signals, valueAt, wonText } from "./risk.js";
import { SECTOR_NAME } from "./risk.js";
import { trendImage } from "./dashchart.js";

const pct = (v) => (v == null ? "-" : v.toFixed(2) + "%");
const ym = (m) => (m ? `${m.slice(0, 4)}.${m.slice(4)}` : "-");
const dot = (d) => String(d || "").replace(/-/g, ".");
const eok = (v) => (v == null ? "-" : Math.round(v).toLocaleString("en-US") + "억원");
const LEVEL = { warn: "주의", info: "참고", ok: "양호" };
const NOTE = "자료출처: 금융감독원 금융통계정보시스템(FISIS), 전자공시시스템(DART), 금융감독원 제재공시. 회사 이름으로 자료를 맞춘 것이라 오류가 있을 수 있으며 참고용입니다. 업무에 인용할 때는 원문을 확인하세요.";

export const DASH_PARTS = [
  { id: "sum", label: "핵심 지표·체크 포인트" },
  { id: "trend", label: "건전성 추이 (업권 중앙값 비교 표)" },
  { id: "gov", label: "기업 개황·지배구조 (DART)" },
  { id: "sanc", label: "제재 이력" },
];
export const OV_PARTS = [
  { id: "rank", label: "규모 순위 (전체 회사)" },
  { id: "dist", label: "경영지표 분포 통계" },
  { id: "peer", label: "Peer 사분면 값 (회사별 지표·주의 구간)" },
];

/** 기업 개황·지배구조(DART) 구역 */
export function govSection(d) {
  const blocks = [];
  if (!d?.corp) blocks.push({ text: "전자공시(DART)에서 찾은 회사가 없습니다(비상장·외부감사 비대상 등).", gray: true });
  else {
    const ci = d.company || {};
    if (d.rel && d.rel !== "본인") blocks.push({ text: `이 회사의 DART 자료는 ${d.rel}(${d.name})의 것입니다.`, gray: true });
    else if (d.match === "guess") blocks.push({ text: "DART 회사를 이름으로 추정해 연결했습니다. 맞는지 확인하세요.", gray: true });
    blocks.push({ kv: [["대표이사", ci.ceo], ["설립일", /^\d{8}$/.test(ci.established || "") ? `${ci.established.slice(0, 4)}.${ci.established.slice(4, 6)}.${ci.established.slice(6)}` : ci.established], ["시장·종목", ci.marketName ? `${ci.marketName}${ci.stock ? " " + ci.stock : ""}` : ci.stock], ["홈페이지", ci.homepage], ["주소", ci.address]] });
    const hold = d.holders?.items || [];
    if (hold.length) blocks.push({ heading: "최대주주 및 특수관계인 지분" }, { table: { head: ["성명", "관계", "보통주 지분율"], rows: hold.slice(0, 10).map((x) => [x.name, x.relation && x.relation !== "-" ? x.relation : "", x.common?.ratio != null ? x.common.ratio.toFixed(2) + "%" : "-"]), weights: [2, 2, 1] } });
  }
  return { title: "기업 개황·지배구조", note: "전자공시시스템(DART)", blocks };
}

export function dashboardDoc(ids, { row, rows, sector, info, span = 8, charts = false }) {
  const cfg = SECTOR_CFG[sector];
  const month = latestMonth(rows, cfg.x.key);
  const nowYear = info.nowYear || new Date().getFullYear();
  const cases = info.sanctions || [];
  const months = monthsBack(month, span + 1);
  const sections = [];
  if (ids.has("sum")) {
    const keys = [[cfg.x.key, cfg.x.label, cfg.x.higher], [cfg.y.key, cfg.y.label, cfg.y.higher]];
    const trs = keys.map(([k, label, higher]) => {
      const v = valueAt(row, k, month), prev = valueAt(row, k, monthsBack(month, 2)[0]), st = sectorStat(rows, k, month), rk = rankOf(rows, row, k, month, higher);
      return [label, pct(v), v != null && prev != null ? `${(v - prev).toFixed(2)}%p` : "-", st ? pct(st.median) : "-", rk ? `${rk.rank}/${rk.n}위` : "-"];
    });
    const sig = signals({ row, rows, sector, month, cases, nowYear });
    sections.push({ title: "핵심 지표·체크 포인트", note: `기준 ${ym(month)}${sector === "F" ? ` · 순자본비율 ${ncrBasis(row)}` : ""}`, blocks: [
      { table: { head: ["지표", "값", "전분기 대비", "업권 중앙값", "업권 순위"], rows: trs, weights: [3, 1, 1, 1, 1] } },
      { text: `자산총계(별도): ${eok(row.assets)}` },
      { heading: "체크 포인트 (업권 중앙값·추세·제재 이력 기준, 법정 기준선이 아닌 상대 비교)" },
      ...sig.map((s) => ({ text: `[${LEVEL[s.level]}] ${s.text}`, indent: 1 })),
    ] });
  }
  if (ids.has("trend")) {
    const keys = cfg.trend.filter((k) => seriesOf(row, k, months).some((p) => p[1] != null));
    sections.push({ title: "건전성 추이", note: `최근 ${span}분기 · 회사 값과 업권 중앙값`, blocks: keys.flatMap((k) => {
      const img = charts ? trendImage({ title: cfg.names[k] || k, months, series: [
        { name: "업권 중앙값", color: "#8a94a6", dash: true, pts: months.map((m) => [m, sectorStat(rows, k, m)?.median ?? null]) },
        { name: row.name, color: "#1f64d8", pts: seriesOf(row, k, months) },
      ] }) : null;
      if (img) return [{ image: img, half: true }];
      return [
        { heading: cfg.names[k] || k },
        { table: { head: ["시점", row.name, "업권 중앙값"], rows: months.map((m) => [ym(m), pct(valueAt(row, k, m)), pct(sectorStat(rows, k, m)?.median)]), weights: [1, 1, 1] } },
      ];
    }) });
  }
  if (ids.has("gov")) sections.push(govSection(info.dart));
  if (ids.has("sanc")) {
    const blocks = [];
    if (!cases.length) blocks.push({ text: "이 회사 이름으로 찾은 제재공시가 없습니다.", gray: true });
    else {
      const years = sanctionYears(cases, nowYear, 8);
      blocks.push({ table: { head: ["연도", "중징계·영업제한", "금전·주의", "경미"], rows: years.map((y) => [String(y.year), String(y.high), String(y.mid), String(y.low)]), weights: [1, 1, 1, 1] } });
      blocks.push({ heading: `제재공시 ${cases.length}건` }, { table: { head: ["공시일", "수준", "조치", "지적 사항"], rows: cases.map((c) => [dot(c.date), SEVERITY[severityOf(c)][0],
        [c.inst && `기관: ${c.inst}`, c.exec && `임원: ${c.exec}`, c.staff && `직원: ${c.staff}`, c.fine > 0 && `과태료 ${wonText(c.fine)}`, c.penalty > 0 && `과징금 ${wonText(c.penalty)}`].filter(Boolean).join(" / "),
        (c.items || []).map((i) => i.t).join(" / ")]), weights: [1, 1, 3, 4] } });
    }
    sections.push({ title: "제재 이력", note: "금융감독원 제재공시(2011년 이후)", blocks });
  }
  return { title: `${row.name} 리스크 대시보드`, subtitle: cfg.label, fileName: `${row.name}_리스크대시보드`, meta: [`기준 ${ym(month)} · 자료는 금융감독원 FISIS·DART·제재공시`], sections, footer: [NOTE] };
}

export function overviewDoc(ids, { sector, ov, rows }) {
  const cfg = SECTOR_CFG[sector];
  const name = SECTOR_NAME[sector];
  const sections = [];
  if (ids.has("rank")) {
    const rk = rankSize(ov.items);
    sections.push({ title: `${ov.basis} 순위`, note: `${ov.asOf} 기준 · 전체 ${rk.rows.length}곳`, blocks: [{ table: { head: ["순위", "회사", ov.basis.includes("펀드") ? "펀드 순자산(억원)" : "총자산(억원)", "점유율"], rows: rk.rows.map((r) => [String(r.rank), r.name, Math.round(r.value).toLocaleString("en-US"), (r.share * 100).toFixed(1) + "%"]), weights: [1, 3, 2, 1] } }] });
  }
  if (cfg && ov.rows && ids.has("dist")) {
    const month = latestMonth(ov.rows, cfg.x.key);
    const trs = Object.keys(cfg.names).map((k) => ({ k, d: distribution(ov.rows.map((r) => valueAt(r, k, month))) })).filter((x) => x.d)
      .map(({ k, d }) => [cfg.names[k], String(d.n), pct(d.med), `${d.q1.toFixed(2)}~${d.q3.toFixed(2)}%`, `${d.min.toFixed(2)}~${d.max.toFixed(2)}%`]);
    sections.push({ title: "경영지표 분포", note: `${ym(month)} 기준`, blocks: [{ table: { head: ["지표", "회사 수", "중앙값", "사분위 범위", "최소~최대"], rows: trs, weights: [3, 1, 1, 2, 2] } }] });
  }
  if (cfg && rows && ids.has("peer")) {
    const q = quadrantData(rows, sector);
    const danger = new Set(q.dangerIds);
    sections.push({ title: "업권 Peer 사분면", note: `${ym(q.month)} · 가로 ${cfg.x.label}, 세로 ${cfg.y.label} · 중앙값 ${pct(q.xMean)} / ${pct(q.yMean)}`, blocks: [{ table: { head: ["회사", cfg.x.label, cfg.y.label, "구간"], rows: [...q.points].sort((a, b) => a.x - b.x).map((p) => [p.name, pct(p.x), pct(p.y), danger.has(p.id) ? "주의 구간" : ""]), weights: [3, 1, 1, 1] } }] });
  }
  return { title: `${name} 업권 현황`, subtitle: "금융사 리스크 대시보드", fileName: `${name}_업권현황`, meta: [`${ov.asOf} 기준`], sections, footer: [NOTE] };
}
