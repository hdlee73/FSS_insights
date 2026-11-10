// 과징금 계산(규정 별표2). 순수 함수.
import { pct, truncate, won } from "./amount.js";
import { lawSrcId } from "./data-laws.js";

export const FACTORS = [
  ["motive", "위반행위의 동기", 0.2],
  ["method", "위반행위의 방법", 0.2],
  ["gain", "부당이득 규모", 0.2],
  ["harm", "피해 규모", 0.2],
  ["market", "시장에 미치는 영향", 0.1],
  ["period", "위반기간·횟수", 0.1],
];
export const GRADE_POINTS = { 상: 3, 중: 2, 하: 1 };

/** 부과기준율: 가중평균 점수 2.3 이상 100%, 1.6 이상 75%, 그 미만 50%. */
export function baseRate(scores) {
  let score = 0;
  for (const [key, , w] of FACTORS) score += w * (GRADE_POINTS[scores?.[key]] ?? 2);
  score = Math.round(score * 100) / 100;
  return { score, rate: score >= 2.3 ? 1 : score >= 1.6 ? 0.75 : 0.5 };
}

/** 감경 사유와 상한(기본과징금 대비) */
export const FINE_MITIGATIONS = [
  ["selfFix", "위반상태를 스스로 시정·해소", 0.5],
  ["selfReport", "자진신고 또는 조사 적극 협조", 0.5],
  ["internal", "내부통제를 위한 상당한 주의·감독", 0.5],
  ["merit", "공적(세칙 제50조제2항) 또는 경영실태평가 1등급", 0.1],
  ["r23", "규정 제23조제1항 정상참작", 0.1],
  ["r26", "규정 제26조(다른 기관 조치 등) 해당", 0.5],
];

/**
 * fine: {
 *   item: FINES 항목, amount: 기준금액(위반금액·수입등), revenueUnknown: bool,
 *   scores:{motive..}, special: -0.1~0.1,
 *   gain: 부당이득, specialBenefit: 특별이익 제공액, promisedOnly: bool,
 *   mit:{ key: 비율 }, heavyCut: 0~0.5(6호 가목), capital: 자본금·자본총액 중 큰 금액, damages: 피해배상액,
 *   exempt: 사유 문자열(있으면 0)
 * }
 * 반환: { lines:[{label, value, note, src}], final, errors:[] }
 */
export function calcFine(fine) {
  const errors = [];
  const lines = [];
  const it = fine?.item;
  if (!it) return { errors: ["과징금 조항을 골라 주세요."], lines, final: 0 };
  const lawSrc = lawSrcId(it.law, it.jo);
  const amount = Number(fine.amount);
  if (it.note) lines.push({ label: "참고", value: null, note: it.note, src: [lawSrc] });
  const add = (label, value, note, src) => lines.push({ label, value, note, src });

  // 1. 법정부과한도
  let limit;
  if (it.kind === "fixed") {
    limit = it.cap;
    add("법정부과한도", limit, `${it.law} ${it.jo}의 정액 한도`, [lawSrc, "R_A2"]);
  } else if (it.fallback && (fine.revenueUnknown || !(amount > 0))) {
    if (!fine.revenueUnknown) return { errors: [`${it.basis || "기준금액"}을 넣거나 '산정 곤란'을 선택해 주세요.`], lines, final: 0 };
    limit = it.fallback;
    add("법정부과한도", limit, `${it.basis || "기준금액"}이 없거나 산정 곤란 → 정액 한도`, [lawSrc, "R_A2"]);
  } else {
    if (!(amount > 0)) return { errors: ["기준금액(위반금액)을 넣어 주세요."], lines, final: 0 };
    add("기준금액", amount, it.basis || "위반금액(위반기간 중 최대 위반금액)", ["R_A2"]);
    limit = amount * it.rate;
    add("법정부과한도", limit, `기준금액 × ${pct(it.rate)}${it.kind === "full" ? "(위반금액 이내)" : ""}`, [lawSrc, "R_A2"]);
  }

  // 2. 부과기준율 → 기본과징금
  const { score, rate } = baseRate(fine.scores);
  const special = Math.max(-0.1, Math.min(0.1, Number(fine.special) || 0));
  const appliedRate = Math.max(0, Math.min(1, rate + special));
  add("부과기준율", null, `가중평균 ${score.toFixed(2)}점 → ${pct(rate)}${special ? ` ${special > 0 ? "+" : ""}${Math.round(special * 100)}%p(특별 사유) = ${pct(appliedRate)}` : ""}`, ["R_A2_score"]);
  const basic = limit * appliedRate;
  add("기본과징금", basic, `법정부과한도 × ${pct(appliedRate)}`, ["R_A2"]);

  // 3. 가중·감경 (기본과징금의 50% 범위, 법정부과한도 이내)
  let up = 0;
  const gain = Number(fine.gain) || 0;
  if (gain > basic) {
    up += gain - basic;
    add("가중: 부당이득 초과분", gain - basic, `부당이득 ${won(gain)} − 기본과징금`, ["R_A2_adj"]);
  }
  const sb = (Number(fine.specialBenefit) || 0) * (fine.promisedOnly ? 0.5 : 1);
  if (sb > basic) {
    up += sb - basic;
    add("가중: 특별이익 초과분", sb - basic, fine.promisedOnly ? "약속금액의 50% 기준" : "특별이익 − 기본과징금", ["R_A2_adj"]);
  }
  let down = 0;
  for (const [key, label, cap] of FINE_MITIGATIONS) {
    const r = Math.max(0, Math.min(cap, Number(fine.mit?.[key]) || 0));
    if (!r) continue;
    down += basic * r;
    add(`감경: ${label}`, -basic * r, `기본과징금 × ${pct(r)} (한도 ${pct(cap)})`, ["R_A2_adj"]);
  }
  let net = up - down;
  const bound = basic * 0.5;
  if (Math.abs(net) > bound) {
    add("가중·감경 한도 적용", null, `합산 ${won(net)} → 기본과징금의 50%(${won(bound)}) 이내로 제한`, ["R_A2_adj"]);
    net = Math.sign(net) * bound;
  }
  let adjusted = basic + net;
  if (adjusted > limit) {
    add("법정부과한도 적용", null, `조정 금액이 한도를 넘어 ${won(limit)}로 제한`, ["R_A2_adj"]);
    adjusted = limit;
  }
  add("조정과징금", adjusted, net ? `기본과징금 ${net > 0 ? "+" : "−"} ${won(Math.abs(net))}` : "가중·감경 없음", ["R_A2_adj"]);

  // 4. 부과과징금
  let final = adjusted;
  const cut = Math.max(0, Math.min(0.5, Number(fine.heavyCut) || 0));
  if (cut) {
    final -= adjusted * cut;
    add("감경: 현저히 과중·부담능력 등", -adjusted * cut, `조정과징금 × ${pct(cut)} (한도 50%)`, ["R_A2_final"]);
  }
  const capital = Number(fine.capital) || 0;
  if (capital > 0 && final > capital * 0.1) {
    add("감경: 자본 10% 초과분", -(final - capital * 0.1), `자본금·자본총액 중 큰 금액 ${won(capital)}의 10%를 넘는 부분`, ["R_A2_final"]);
    final = capital * 0.1;
  }
  const dmg = Number(fine.damages) || 0;
  if (dmg > 0) {
    const d = Math.min(dmg, final);
    final -= d;
    add("감액: 피해배상액", -d, "피해자에게 배상한 금액", ["R_A2_final"]);
  }
  if (adjusted <= 1e6 && adjusted > 0) add("미부과 검토", null, "조정과징금이 100만원 이하이면 부과하지 않을 수 있습니다.", ["R_A2_final"]);
  if (fine.exempt) {
    add("미부과", -final, fine.exempt, ["R_A2_final"]);
    final = 0;
  }
  const truncated = truncate(final, 1e6);
  add("부과과징금(100만원 미만 절사)", truncated, final !== truncated ? `${won(final)} → 절사` : "", ["R_A2"]);
  return { lines, final: truncated, limit, basic, adjusted, errors };
}
