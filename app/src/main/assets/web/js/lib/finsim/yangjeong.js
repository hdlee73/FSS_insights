// 신분제재 양정 엔진(기관·임원·직원). 화면과 무관한 순수 함수.
// 1) 제재건별 기본 양정: 세칙 별표3 구체기준이 있으면 그 표, 없으면 세칙 별표2 일반기준(기관은 규정 제17조 사유).
// 2) 제재건별 조정: 역할(세칙 제52조제3항), 별표3 항목별 가감.
// 3) 검사건 단위 종합: 가장 무거운 제재 → 경합·이력 가중과 감경을 모두 합산(규정 제25조의2) → 최종.
import { annex3ById } from "./data-annex3.js";
import { INST_MAX, PERSON_MAX, instName, parseRange, personName, rangeLabel, shift } from "./levels.js";

export const ROLES = ["행위자", "지시자", "보조자", "감독자"];
export const KINDS = ["임원", "미등기임원", "직원"];

/** 세칙 별표2 위반유형 */
export const GENERAL_TYPES = [
  "1. 법령·규정·감독기관 명령 위반",
  "2. 횡령·배임·사기 등 범죄 관련",
  "3. 금전적 손해 또는 사회적 물의 야기",
  "4. 정관·내규 위반, 충실의무 위반",
  "5. 직무태만·감독 소홀",
  "6. 그 밖의 위법·부당행위, 단순 절차 위반",
];
const GENERAL_TABLE = {
  a: ["해임권고(면직)", "해임권고(면직)~문책경고(감봉)", "직무정지(정직)~문책경고(감봉)", "주의적경고(견책)"],
  b: ["해임권고(면직)", "직무정지(정직)~문책경고(감봉)", "문책경고(감봉)~주의적경고(견책)", "주의적경고(견책)"],
};
export const GENERAL_COLS = ["심함 + 고의", "심함 + 중과실 / 약함 + 고의", "심함 + 경과실 / 약함 + 중과실", "약함 + 경과실"];

/** 위반 정도·고의 여부 → 일반기준 열 번호 */
export function generalColumn(degree, fault) {
  const f = { 고의: 0, 중과실: 1, 경과실: 2 }[fault] ?? 2;
  return Math.min(3, f + (degree === "약함" ? 1 : 0));
}

export function generalRange(type, degree, fault) {
  const t = Math.min(6, Math.max(1, Number(type) || 1));
  const col = generalColumn(degree, fault);
  const text = (t <= 4 ? GENERAL_TABLE.a : GENERAL_TABLE.b)[col];
  return { range: parseRange(text, "person"), col, text };
}

/** 별표3 항목에서 고른 행 */
export function annex3Row(itemId, group, row) {
  const it = annex3ById(itemId);
  const g = it?.groups[group];
  const r = g?.rows[row];
  return r ? { it, g, r } : null;
}

const roleColumn = (role) => (role === "보조자" ? "보조자" : role === "감독자" ? "감독자" : "행위자");

/**
 * 제재건 하나에서 한 사람의 양정.
 * entry: { role, mode:'annex3'|'general', item, group, row, gType, degree, fault, pick, roleSteps, itemAdj:[index] }
 * 반환: { range, base, level, steps:[{label, steps, src}], basis:[src], text, error }
 */
export function casePerson(entry, kind = "임원") {
  const steps = [];
  const basis = [];
  let range = null;
  let text = "";
  if (entry.mode === "annex3") {
    const hit = annex3Row(entry.item, entry.group ?? 0, entry.row ?? 0);
    if (!hit) return { error: "별표3 항목과 구간을 골라 주세요." };
    const { it, g, r } = hit;
    text = r.pr ? r.pr[roleColumn(entry.role)] : r.p;
    if (!text) return { error: `${it.code} ${g.name} '${r.c}' 구간에는 임직원 기준이 없습니다(기관 기준만 있음). 일반기준으로 바꾸거나 다른 구간을 골라 주세요.` };
    range = parseRange(text, "person");
    basis.push("A3:" + it.id);
    if (r.pr && entry.role !== "행위자" && entry.role !== "지시자") basis.push("D52");
    (entry.itemAdj || []).forEach((k) => {
      const a = it.adj?.[k];
      if (a) steps.push({ label: `${it.code} ${a.label}`, steps: a.steps, src: "A3:" + it.id });
    });
  } else {
    const g = generalRange(entry.gType, entry.degree, entry.fault);
    range = g.range;
    text = g.text;
    basis.push("D_A2", "D46");
  }
  if (!range) return { error: `기준 문구를 읽지 못했습니다: ${text}` };
  const base = Number.isInteger(entry.pick) && entry.pick >= range.hi && entry.pick <= range.lo ? entry.pick : range.def;
  // 역할별 열이 따로 있는 표(실명법 등)는 이미 반영되어 있으므로 역할 감경을 겹쳐 적용하지 않는다.
  const roleSteps = Math.min(3, Math.max(0, Number(entry.roleSteps) || 0));
  const hasRoleCols = entry.mode === "annex3" && !!annex3Row(entry.item, entry.group ?? 0, entry.row ?? 0)?.r.pr;
  if (roleSteps && !hasRoleCols && (entry.role === "보조자" || entry.role === "감독자")) {
    steps.push({ label: `${entry.role} 감경(행위자 대비 ${roleSteps}단계)`, steps: -roleSteps, src: "D52" });
  }
  const net = steps.reduce((s, x) => s + x.steps, 0);
  const level = shift(base, net, PERSON_MAX);
  return { range, base, level, steps, basis, text, rangeText: rangeLabel(range, "person", kind), name: personName(level, kind) };
}

/** 규정 제17조 기관경고 다목 금융사고 손실 기준. 두 기준 중 하나라도 넘으면 해당(‘또는’)으로 본다. */
export function accidentThreshold(equity) {
  if (!Number.isFinite(equity) || equity <= 0) return null;
  const a = Math.max(equity * 0.02, 10e8);
  const b = equity < 1.5e12 ? 100e8 : equity < 2.5e12 ? 300e8 : 500e8;
  return { a, b, min: Math.min(a, b) };
}

/** 기관 사유 체크 목록(규정 제17조) */
export const INST_GROUNDS = [
  { key: "g1", label: "인가·허가 부정 취득, 건전영업 크게 저해로 중대한 손실, 정지조치 위반·반복, 시정명령 불이행(제1호)", level: 0, range: [0, 1] },
  { key: "g2", label: "건전영업 저해로 건전경영 훼손·재산상 손실, 기관경고 후 반복 등(제2호)", level: 2 },
  { key: "g7a", label: "제2호 나목에 해당하나 그보다 가벼움(제7호 가목)", level: 3 },
  { key: "g7b1", label: "경영방침·경영자세에 기인(제7호 나목(1))", level: 3 },
  { key: "g7b2", label: "관련 점포 다수 또는 조직적(나목(2))", level: 3 },
  { key: "g7b3", label: "임원이 주된 관련자 또는 다수 임원 관련(나목(3))", level: 3 },
  { key: "g7b4", label: "동일유형 민원 집단 제기 또는 피해규모 큼(나목(4))", level: 3 },
  { key: "g7b5", label: "금융실명법 중대 위반(나목(5))", level: 3 },
  { key: "g7b6", label: "고발·통보 사항 + 중대한 내부통제·감독 소홀(나목(6))", level: 3 },
  { key: "g7c2", label: "손실이 기준 미달이나 내부통제 매우 취약, 중대 사고 빈발·사회적 물의(다목(2))", level: 3 },
];

/**
 * 제재건 하나의 기관 양정.
 * inst: { mode:'annex3'|'grounds'|'manual', item, group, row, grounds:{key:true}, loss, mitig, manual, pick }
 */
export function caseInst(inst, equity) {
  const basis = [];
  let range = null;
  let text = "";
  const notes = [];
  if (inst.mode === "annex3") {
    const hit = annex3Row(inst.item, inst.group ?? 0, inst.row ?? 0);
    if (!hit) return { error: "별표3 항목과 구간을 골라 주세요." };
    text = hit.r.i;
    if (!text) return { error: `${hit.it.code} '${hit.r.c}' 구간에는 기관 기준이 없습니다. 사유 체크나 직접 선택으로 정해 주세요.` };
    range = parseRange(text, "inst");
    basis.push("A3:" + hit.it.id, "R17");
  } else if (inst.mode === "manual") {
    const lv = Number.isInteger(inst.manual) ? inst.manual : INST_MAX;
    range = { hi: lv, lo: lv, def: lv };
    text = "직접 선택";
    basis.push("R17");
  } else {
    const g = inst.grounds || {};
    const hits = INST_GROUNDS.filter((x) => g[x.key]);
    const th = accidentThreshold(equity);
    const loss = Number(inst.loss) || 0;
    if (loss > 0) {
      if (!th) notes.push("자기자본을 입력하면 금융사고 손실 기준(다목(1))을 자동 판정합니다.");
      else if (loss > th.min) hits.push({ key: "g7c1", label: "금융사고 손실이 기준 초과(다목(1))", level: 3 });
      else notes.push(`금융사고 손실이 다목(1) 기준(${Math.round(th.min / 1e8).toLocaleString("ko-KR")}억원)을 넘지 않습니다.`);
    }
    if (!hits.length) return { none: true, notes, basis: ["R17"], text: "해당 사유 없음" };
    const top = Math.min(...hits.map((x) => x.level));
    range = top === 0 ? { hi: 0, lo: 1, def: 0 } : { hi: top, lo: top, def: top };
    if (top === 3 && inst.mitig) {
      range = { hi: 4, lo: 4, def: 4 };
      notes.push("정상참작 사유가 크거나 위반 정도가 경미하여 기관주의(제9호)로 봅니다.");
      basis.push("R17_9");
    }
    text = hits.map((x) => x.label).join(" / ");
    basis.push(top <= 2 ? "R17_2" : "R17_7", "R17");
  }
  if (!range) return { error: `기준 문구를 읽지 못했습니다: ${text}` };
  const base = Number.isInteger(inst.pick) && inst.pick >= range.hi && inst.pick <= range.lo ? inst.pick : range.def;
  return { range, base, level: base, basis, text, notes, rangeText: rangeLabel(range, "inst"), detail: range.detail || null, name: instName(base) };
}

/**
 * 검사건 단위로 한 사람의 최종 양정.
 * person: { id, name, kind, hist:{ heavy3y, light3y, total3y, repeatMain }, adj:{ noConcurrent, d50Steps, r23Steps, r23_2, merit, crime } }
 * results: 그 사람이 관련된 제재건별 casePerson 결과 [{ caseTitle, res }]
 */
export function personFinal(person, results) {
  const kind = person.kind || "임원";
  const ok = results.filter((x) => x.res && !x.res.error);
  if (!ok.length) return { none: true };
  const heaviest = Math.min(...ok.map((x) => x.res.level));
  const from = ok.find((x) => x.res.level === heaviest);
  const steps = [];
  const officerLike = kind !== "직원";
  const a = person.adj || {};
  const hist = person.hist || {};
  // 경합 가중: 서로 관련 없는 위반 3개 이상(주의 포함 시 4개 이상), 가장 무거운 것이 업무집행정지(정직) 이상이면 제외.
  const n = ok.filter((x) => x.res.level <= PERSON_MAX).length;
  const need = ok.some((x) => x.res.level === PERSON_MAX) ? 4 : 3;
  if (!a.noConcurrent && n >= need && heaviest > 1 && heaviest <= PERSON_MAX) {
    steps.push({ label: `경합 가중(서로 관련 없는 위반 ${n}건)`, steps: 1, src: officerLike ? "R24_2" : "D49" });
  }
  if (officerLike) {
    if ((Number(hist.heavy3y) || 0) >= 1 || (Number(hist.light3y) || 0) >= 2) steps.push({ label: "최근 3년 제재 이력 가중(문책경고 이상 1회 또는 주의적경고·주의 2회 이상)", steps: 1, src: "R24_2" });
    if (hist.repeatMain) steps.push({ label: "주의적경고 이상 받은 주된 행위자의 재위반 가중", steps: 1, src: "R24_2" });
  } else if ((Number(hist.total3y) || 0) >= 2) {
    steps.push({ label: "최근 3년 이내 2회 이상 제재 이력 가중", steps: 1, src: "R25" });
  }
  if (a.r23_2) steps.push({ label: "같은 행위에 과징금·과태료 부과로 감경", steps: -1, src: "R23" });
  const d50 = Math.min(3, Math.max(0, Number(a.d50Steps) || 0));
  if (d50) steps.push({ label: `감면 사유(자진신고·시정·변상 등) ${d50}단계 감경`, steps: -d50, src: "D50" });
  const r23 = Math.min(3, Math.max(0, Number(a.r23Steps) || 0));
  if (r23) steps.push({ label: `정상참작 ${r23}단계 감경`, steps: -r23, src: "R23" });
  // 공적 감경(세칙 제50조제2항)은 다른 조정을 먼저 반영한 뒤(제4항), 범죄 관련이거나 '주의'면 적용하지 않는다.
  let net = steps.reduce((s, x) => s + x.steps, 0);
  const notes = [];
  if (a.merit) {
    const before = shift(heaviest, net, PERSON_MAX);
    if (a.crime) notes.push("범죄 관련 행위라 공적 감경을 적용하지 않습니다(세칙 제50조제2항).");
    else if (before >= PERSON_MAX) notes.push("'주의' 이하라 공적 감경을 적용하지 않습니다(세칙 제50조제2항).");
    else {
      steps.push({ label: "공적 감경(별표5)", steps: -1, src: "D50" });
      net -= 1;
    }
  }
  const level = shift(heaviest, net, PERSON_MAX);
  if (level > PERSON_MAX) notes.push("감경 결과 '주의'보다 가벼워져 제재 면제 또는 자율처리필요사항 통보를 검토합니다(규정 제19조제2항·제23조).");
  if (kind === "미등기임원") notes.push("미등기임원은 임원 기준으로 양정하고 직원 조치를 합니다(세칙 제46조의3, 규정 제25조제4항).");
  if (officerLike && level <= 1) notes.push("해임권고·업무집행정지는 금융위원회에 건의하는 사항입니다(규정 제18조).");
  return { heaviest, from, steps, net, level, name: personName(level, kind), notes, basis: ["R25_2", ...new Set(steps.map((x) => x.src))] };
}

/**
 * 검사건 단위 기관 최종 양정.
 * insp: { instHist:{ count3y }, instAdj:{ excl24_2, d50_4, r23Steps, r23_2 } }
 */
export function instFinal(insp, results) {
  const ok = results.filter((x) => x.res && !x.res.error && !x.res.none);
  if (!ok.length) return { none: true };
  const heaviest = Math.min(...ok.map((x) => x.res.level));
  const from = ok.find((x) => x.res.level === heaviest);
  const steps = [];
  const a = insp.instAdj || {};
  if ((Number(insp.instHist?.count3y) || 0) >= 2) steps.push({ label: "최근 3년 기관주의 이상 2회 이상 후 재위반 가중", steps: 1, src: "R24" });
  const warnLike = ok.filter((x) => x.res.level >= 3).length;
  const hasSuspension = ok.some((x) => x.res.level <= 2);
  if (!a.excl24_2 && warnLike >= 4 && !hasSuspension) steps.push({ label: `같은 검사에서 서로 관련 없는 기관경고·주의 사유 ${warnLike}건 가중`, steps: 1, src: "R24" });
  if (a.r23_2) steps.push({ label: "같은 행위에 과징금·과태료 부과로 감경", steps: -1, src: "R23" });
  if (a.d50_4) steps.push({ label: "내부통제 우수 기관 감경(별표9)", steps: -1, src: "D50_4" });
  const r23 = Math.min(3, Math.max(0, Number(a.r23Steps) || 0));
  if (r23) steps.push({ label: `정상참작 ${r23}단계 감경`, steps: -r23, src: "R23" });
  const net = steps.reduce((s, x) => s + x.steps, 0);
  const level = shift(heaviest, net, INST_MAX);
  const notes = [];
  if (level <= 2) notes.push("인가취소·영업정지는 금융위원회에 건의하는 사항입니다(규정 제17조).");
  if (level > INST_MAX) notes.push("감경 결과 기관주의보다 가벼워져 기관제재 면제(경영유의·개선사항 등)를 검토합니다.");
  const detail = from.res.detail && level === from.res.level ? from.res.detail : null;
  return { heaviest, from, steps, net, level, name: instName(level) + (detail ? ` ${detail}` : ""), notes, basis: ["R25_2", "R17", ...new Set(steps.map((x) => x.src))] };
}
