// 법률별 과징금·과태료 기준. 법률마다 laws/*.js 한 파일(국가법령정보센터 현행 법률 조문 + 시행령 과태료 부과기준 별표).
// 과징금 kind: rate = 기준금액 × 비율, full = 기준금액 이내(100%), fixed = 정액 한도, revenue = 수입등·매출액 × 비율(산정 곤란 시 정액).
// 과태료 max = 법정최고금액(조문 각 항), decreeBy = 시행령 개별기준 금액(법인·개인·임직원), steps = 위반 차수별 금액.
import { addSource, lawUrl } from "./sources.js";
import capital from "./laws/capital.js";
import bank from "./laws/bank.js";
import insurance from "./laws/insurance.js";
import consumer from "./laws/consumer.js";
import credit from "./laws/credit.js";
import efin from "./laws/efin.js";
import cinfo from "./laws/cinfo.js";
import savings from "./laws/savings.js";
import governance from "./laws/governance.js";
import holding from "./laws/holding.js";
import aml from "./laws/aml.js";
import loan from "./laws/loan.js";
import cu from "./laws/cu.js";
import realname from "./laws/realname.js";
import p2p from "./laws/p2p.js";
import vasp from "./laws/vasp.js";
import forex from "./laws/forex.js";

export const LAW_FILES = [bank, insurance, capital, consumer, credit, efin, cinfo, savings, governance, holding, aml, loan, cu, realname, p2p, vasp, forex];
export const LAWS = Object.fromEntries(LAW_FILES.map((x) => [x.law, x]));
export const shortName = (law) => LAWS[law]?.short || law;
export const lawSrcId = (law, jo) => `L:${law}:${jo}`;
export const decreeSrcId = (law) => `DECREE:${law}`;

const 만 = 1e4;
/** "제1항제2호의3" 같은 표기 */
export function hangHo(hang, ho) {
  return `${hang !== "" && hang != null ? `제${hang}항` : ""}${ho ? `제${String(ho).replace(/^(\d+)/, "$1호").replace(/^(\d+호)([가-힣])$/, "$1 $2목")}` : ""}`;
}
const korMoney = (v) => (v >= 1e8 && v % 1e8 === 0 ? `${v / 1e8}억원` : v >= 1e4 ? `${(v / 1e4).toLocaleString("ko-KR")}만원` : `${v}원`);

function uniqueId(seen, base) {
  let id = base;
  for (let k = 2; seen.has(id); k++) id = `${base}~${k}`;
  seen.add(id);
  return id;
}

const fines = [];
const pens = [];
for (const L of LAW_FILES) {
  const seen = new Set();
  for (const [hang, ho, text, kind, rate, basis, opts = {}] of L.F || []) {
    const ref = hangHo(hang, ho);
    fines.push({
      id: uniqueId(seen, `${L.short}:F:${hang}-${ho}`), law: L.law, jo: L.fine.jo, ref,
      title: `${ref ? ref + " " : ""}${text}`, text, kind, rate: kind === "full" ? 1 : rate, basis: basis === "정액" ? "" : basis,
      cap: opts.cap ?? null, fallback: opts.fallback ?? null, note: opts.note || "",
    });
  }
  for (const [hang, ho, text, amt, opts = {}] of L.P || []) {
    const ref = hangHo(hang, ho);
    const max = L.pen.max[hang === "" ? 1 : hang];
    if (!max) throw new Error(`${L.short} ${L.pen.jo} ${ref}: 법정최고금액 없음`);
    let decreeBy = null;
    if (Array.isArray(amt)) decreeBy = { 법인: amt[0] * 만, 개인: amt[1] * 만, ...(amt[2] != null ? { 임직원: amt[2] * 만 } : {}) };
    else if (amt != null) decreeBy = { 법인: amt * 만, 개인: amt * 만 };
    const stepsBy = opts.steps ? { 법인: opts.steps[0].map((x) => x * 만), 개인: opts.steps[1].map((x) => x * 만) } : null;
    pens.push({
      id: uniqueId(seen, `${L.short}:P:${hang}-${ho}`), law: L.law, jo: L.pen.jo, ref,
      title: `${ref ? ref + " " : ""}${text}`, text, max, decreeBy, stepsBy,
      decree: decreeBy ? decreeBy.법인 : null, decreeRef: L.pen.decree,
      split: !!decreeBy && (decreeBy.법인 !== decreeBy.개인 || "임직원" in decreeBy),
      byAmount: opts.byAmount ? { min: opts.byAmount[0] * 만, rate: opts.byAmount[1], hard: opts.hard ? opts.hard * 만 : null } : null,
      inspection: !!opts.inspection, person: !!opts.person, annex6: opts.annex6 || null, note: opts.note || "",
    });
  }
}
export const FINES = fines;
export const PENALTIES = pens;

/** 규정 별표6 예정비율표. rows: 결과, cols: 동기 상·중·하. */
export const ANNEX6 = {
  ins1: {
    title: "보험 모집종사자 위반(신계약 건수 기준)",
    count: "위반 신계약 건수",
    results: [["중대", "건당 수입수수료 200만원 이상", [1, 0.55, 0.45]], ["보통", "50만원 이상 200만원 미만", [0.5, 0.3, 0.2]], ["경미", "50만원 미만", [0.2, 0.15, 0.05]]],
    nonCorp: 0.4,
  },
  ins99: {
    title: "보험 제99조제2항 무자격자 모집위탁(무자격자 수 기준)",
    count: "무자격자 수",
    results: [
      ["중대", "수수료 600만원 이상 또는 50건 이상", [1, 0.8, 0.7]], ["보통", "400만원 이상 또는 30건 이상", [0.75, 0.55, 0.45]], ["경미", "그 밖의 경우", [0.5, 0.3, 0.2]],
      ["경미(법인1)", "법인: 150~300만원 또는 10~20건", [0.25, 0.15, 0.05]], ["경미(법인2)", "법인: 150만원 미만이고 10건 미만", [0.1, 0.07, 0.04]],
    ],
    nonCorp: 0.4,
  },
};


// ---- 근거(출처) 등록: 법률 조문, 시행령 별표 ----
function penSummary(L) {
  const tiers = Object.entries(L.pen.max).map(([h, v]) => `${korMoney(v)}(제${h}항)`).join(", ");
  return `${tiers} 이하 과태료. 위반행위별 기준금액은 ${L.pen.decree}. 일반기준: ${L.pen.rule}.`;
}
function fineSummary(L) {
  const parts = L.F.map(([hang, ho, text, kind, rate, basis, o = {}]) => {
    const ref = hangHo(hang, ho);
    const how = kind === "fixed" ? `${korMoney(o.cap)} 이하` : kind === "full" ? `${basis} 이내` : `${basis}의 ${Math.round(rate * 1000) / 10}% 이하${o.fallback ? `(산정 곤란 시 ${korMoney(o.fallback)} 이하)` : ""}`;
    return `${ref ? ref + " " : ""}${text}: ${how}`;
  });
  return parts.join(" · ");
}
for (const L of LAW_FILES) {
  addSource(lawSrcId(L.law, L.pen.jo), { doc: L.law, ver: "현행 법률(국가법령정보센터)", title: `${L.short} ${L.pen.jo}(과태료)`, text: penSummary(L), url: lawUrl(L.law, L.pen.jo) });
  addSource(decreeSrcId(L.law), { doc: `${L.law} 시행령`, ver: "현행(국가법령정보센터)", title: L.pen.decree, text: `위반행위별 개별기준 금액(법인·법인 아닌 자·임직원을 나누는 경우가 있음). 일반기준: ${L.pen.rule}.`, url: lawUrl(`${L.law} 시행령`) });
  if (L.fine?.jo && L.F?.length) addSource(lawSrcId(L.law, L.fine.jo), { doc: L.law, ver: "현행 법률(국가법령정보센터)", title: `${L.short} ${L.fine.jo}(과징금)`, text: fineSummary(L), url: lawUrl(L.law, L.fine.jo) });
}
