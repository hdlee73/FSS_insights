// 과태료 계산(규정 별표3, 업권별은 별표6). 순수 함수.
import { pct, truncate, won } from "./amount.js";
import { ANNEX6, decreeSrcId, lawSrcId } from "./data-laws.js";

export const SUBJECTS = ["법인", "개인", "임직원"];

/** 부과 대상에 맞는 시행령 개별기준 금액. 임직원 금액이 따로 없으면 개인(법인 아닌 자) 금액. */
export function decreeAmount(it, subject, step) {
  const key = subject === "법인" ? "법인" : "개인";
  if (it?.stepsBy) {
    const arr = it.stepsBy[key];
    return arr[Math.max(0, Math.min(arr.length - 1, (Number(step) || 1) - 1))];
  }
  if (!it?.decreeBy) return null;
  if (subject === "임직원" && it.decreeBy.임직원 != null) return it.decreeBy.임직원;
  return it.decreeBy[key];
}

export const MOTIVES = ["상", "중", "하"];
export const RESULTS = ["중대", "보통", "경미"];
/** 별표3 예정비율 [결과][동기] */
export const RATIO = { 중대: [1, 0.8, 0.6], 보통: [0.8, 0.6, 0.4], 경미: [0.6, 0.4, 0.2] };
export const MOTIVE_HELP = { 상: "고의이고 정상참작 사유 없음", 중: "고의이나 정상참작 사유 있음, 또는 중과실", 하: "그 밖의 경우(경과실 등)" };

export const PENALTY_MITIGATIONS = [
  ["r23", "(1) 규정 제23조제1항 정상참작", 0.1],
  ["r26", "(1) 규정 제26조(다른 기관 조치 등)", 0.5],
  ["internal", "(2) 내부통제 상당한 주의", 0.5],
  ["selfReport", "(3) 자진신고", 0.5],
  ["selfFix", "(4) 위반상태 스스로 시정", 0.5],
  ["ability", "(7) 나이·납부능력 등", 0.5],
];

/**
 * pen: {
 *   item: PENALTIES 항목, subject:'법인'|'개인'|'임직원', step(위반 차수, 차수별 금액 조항), base(직접 입력 기준금액), count,
 *   motive, result, special, annexResult(별표6 결과 이름),
 *   agg5y: 0~0.2, mit:{key:비율}, sameTypeCap: bool, capital, maxInvest, exempt
 * }
 */
export function calcPenalty(pen) {
  const lines = [];
  const it = pen?.item;
  if (!it) return { errors: ["과태료 조항을 골라 주세요."], lines, final: 0 };
  const add = (label, value, note, src) => lines.push({ label, value, note, src });
  const lawSrc = lawSrcId(it.law, it.jo);
  const max = it.max;
  const override = Number(pen.base) > 0 ? Number(pen.base) : null;
  const subject = SUBJECTS.includes(pen.subject) ? pen.subject : "법인";
  let decree = decreeAmount(it, subject, pen.step);
  let byNote = "";
  if (it.byAmount) {
    // 외국환거래법 시행령 별표4: 최소 금액과 위반금액 × 비율 중 큰 금액
    const { min, rate, hard } = it.byAmount;
    const v = Number(pen.violation) || 0;
    if (v > 0) {
      decree = Math.max(min, v * rate);
      byNote = `${min ? `${won(min)}과 ` : ""}위반금액 ${won(v)} × ${pct(rate)} ${min ? "중 큰 금액" : ""}`.trim();
    } else if (pen.violationUnknown && hard) {
      decree = hard;
      byNote = "위반금액 산정 곤란(별표4 비고2)";
    } else if (!(Number(pen.base) > 0)) {
      return { errors: [hard ? "위반금액을 넣거나 '위반금액 산정 곤란'을 골라 주세요." : "위반금액을 넣어 주세요."], lines, final: 0 };
    }
  }
  const base = Math.min(max, override ?? decree ?? max);
  add("법정최고금액", max, `${it.law} ${it.jo} ${it.title}`, [lawSrc]);
  if (override && override > max) add("기준금액 제한", null, "직접 넣은 금액이 법정최고금액을 넘어 최고금액으로 맞췄습니다.", ["R_A3"]);
  const who = it.split || it.stepsBy ? `, ${subject === "법인" ? "법인" : subject === "임직원" && it.decreeBy?.임직원 != null ? "임직원" : "법인 아닌 자"}${it.stepsBy ? ` ${Math.max(1, Math.min(3, Number(pen.step) || 1))}차${Number(pen.step) >= 3 ? " 이상" : ""}` : ""}` : "";
  add("기준금액", base, override ? "직접 입력" : decree != null ? `시행령 개별기준(${it.decreeRef}${who}${byNote ? `: ${byNote}` : ""})` : "시행령 개별금액 없음 → 법정최고금액", override ? ["R_A3"] : decree != null ? [decreeSrcId(it.law), "R_A3"] : ["R_A3"]);
  if (it.note) add("참고", null, it.note, [lawSrc]);

  const mi = Math.max(0, MOTIVES.indexOf(pen.motive));
  const motive = MOTIVES[mi];
  const table = it.annex6 && pen.useAnnex6 !== false ? ANNEX6[it.annex6] : null;
  let ratio;
  let resultName;
  if (table) {
    const r = table.results.find((x) => x[0] === pen.annexResult) || table.results[0];
    resultName = r[0];
    ratio = r[2][mi];
    add("예정비율(별표6)", null, `${table.title}: 결과 ${resultName}(${r[1]}) × 동기 ${motive} → ${pct(ratio)}`, ["R_A6"]);
  } else {
    resultName = it.inspection ? "중대" : RESULTS.includes(pen.result) ? pen.result : "보통";
    ratio = RATIO[resultName][mi];
    add("예정비율", null, `결과 ${resultName}${it.inspection ? "(검사 거부·방해는 중대로 봄)" : ""} × 동기 ${motive}(${MOTIVE_HELP[motive]}) → ${pct(ratio)}`, ["R_A3"]);
  }
  const special = Math.max(-0.1, Math.min(0.1, Number(pen.special) || 0));
  if (special) {
    ratio = Math.max(0, Math.min(1, ratio + special));
    add("예정비율 조정", null, `특별한 사유 ${special > 0 ? "+" : ""}${Math.round(special * 100)}%p → ${pct(ratio)}`, ["R_A3"]);
  }
  let planned = base * ratio;
  if (table && subject !== "법인") {
    planned *= table.nonCorp;
    add("비법인 적용", null, `별표6: 법인이 아닌 경우 ${pct(table.nonCorp)} 적용`, ["R_A6"]);
  }
  add("예정금액(건당)", planned, `기준금액 × ${pct(ratio)}`, ["R_A3"]);

  // 가중·감경(합산 후 ±50% 범위, 가중해도 법정최고금액 이내)
  const parts = [];
  const agg = Math.max(0, Math.min(0.2, Number(pen.agg5y) || 0));
  if (agg) parts.push(["가중: 최근 5년 이내 같은 위반", agg]);
  for (const [key, label, cap] of PENALTY_MITIGATIONS) {
    const r = Math.max(0, Math.min(cap, Number(pen.mit?.[key]) || 0));
    if (r) parts.push([`감경: ${label}`, -r]);
  }
  let net = parts.reduce((s, [, r]) => s + r, 0);
  for (const [label, r] of parts) add(label, planned * r, `예정금액 × ${pct(Math.abs(r))}`, ["R_A3_adj"]);
  if (Math.abs(net) > 0.5) {
    add("가중·감경 한도 적용", null, `합산 ${pct(net)} → ±50% 이내로 제한`, ["R_A3_adj"]);
    net = Math.sign(net) * 0.5;
  }
  let per = planned * (1 + net);
  if (per > max) {
    add("법정최고금액 적용", null, `건당 금액이 최고금액을 넘어 ${won(max)}로 제한`, ["R_A3"]);
    per = max;
  }
  add("부과금액(건당)", per, net ? `예정금액 × (1 ${net > 0 ? "+" : "−"} ${pct(Math.abs(net))})` : "", ["R_A3"]);

  const count = Math.max(1, Math.floor(Number(pen.count) || 1));
  let total = per * count;
  if (count > 1) add(`위반 ${count}건 합계`, total, table ? table.count : "위반행위별로 각각 부과(같은 유형 반복은 하나로 볼 수 있음)", ["R_A3"]);
  // 50%를 넘어 감경할 수 있는 사유 (5)(6)(9)
  if (pen.sameTypeCap && total > max * 10) {
    add("감경 (5) 같은 유형 10배 초과분", -(total - max * 10), `법정최고금액의 10배(${won(max * 10)})를 넘는 부분`, ["R_A3_adj"]);
    total = max * 10;
  }
  const capital = Number(pen.capital) || 0;
  if (capital > 0 && total > capital * 0.1) {
    add("감경 (6) 자본 10% 초과분", -(total - capital * 0.1), `자본금·자본총액 중 큰 금액 ${won(capital)}의 10%를 넘는 부분`, ["R_A3_adj"]);
    total = capital * 0.1;
  }
  const inv = Number(pen.maxInvest) || 0;
  if (inv > 0 && total > inv) {
    add("감경 (9) 최대투자원금 초과분", -(total - inv), "자본시장법 제63조제1항 위반(중대 아님·선행매매 없음)", ["R_A3_adj"]);
    total = inv;
  }
  if (pen.exempt) {
    add("면제", -total, pen.exempt, ["R_A3_ex"]);
    total = 0;
  }
  const final = truncate(total, 1e5);
  if (total > 0 && final === 0) add("면제 검토", null, "최종 금액이 10만원 미만이면 면제할 수 있습니다.", ["R_A3_ex"]);
  add("부과과태료(10만원 미만 절사)", final, total !== final ? `${won(total)} → 절사` : "", ["R_A3"]);
  return { lines, final, errors: [], ratio, resultName };
}
