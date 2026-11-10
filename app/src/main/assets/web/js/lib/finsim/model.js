// 검사건·제재건 데이터 모양과 검사건 종합 계산. 화면과 무관한 순수 함수(ViewModel 역할).
import { FINES, PENALTIES } from "./data-laws.js";
import { calcFine } from "./fine.js";
import { calcPenalty } from "./penalty.js";
import { casePerson, caseInst, instFinal, personFinal } from "./yangjeong.js";

export const SCHEMA = 1;
const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
export const SECTORS = ["은행", "보험", "금융투자", "여신전문", "저축은행", "상호금융·신협", "전자금융", "신용정보", "기타"];

export function newCase(n = 1) {
  return {
    id: uid(), title: `제재건 ${n}`, law: "", facts: "",
    use: { inst: true, person: true, fine: false, pen: false },
    inst: { mode: "grounds", grounds: {}, loss: null, mitig: false, item: null, group: 0, row: 0, manual: null, pick: null, extra: [] },
    people: [],
    fine: { itemId: null, amount: null, revenueUnknown: false, scores: {}, special: 0, gain: null, specialBenefit: null, promisedOnly: false, mit: {}, heavyCut: 0, damages: null, exempt: "" },
    pens: [],
  };
}
export function newPenalty() {
  return { id: uid(), itemId: null, subject: "법인", step: 1, violation: null, violationUnknown: false, target: "", base: null, count: 1, motive: "중", result: "보통", annexResult: null, special: 0, agg5y: 0, mit: {}, sameTypeCap: false, maxInvest: null, exempt: "" };
}
export function newPersonEntry(pid) {
  return { pid, role: "행위자", mode: "general", gType: 1, degree: "심함", fault: "중과실", item: null, group: 0, row: 0, pick: null, roleSteps: 0, itemAdj: [] };
}
export function newPerson(n = 1) {
  return { id: uid(), name: `관련자 ${n}`, kind: "임원", position: "", hist: { heavy3y: 0, light3y: 0, total3y: 0, repeatMain: false }, adj: { noConcurrent: false, d50Steps: 0, r23Steps: 0, r23_2: false, merit: false, crime: false } };
}
export function newInspection(n = 1) {
  return {
    schema: SCHEMA, id: uid(), name: `검사건 ${n}`, company: "", sector: "은행", date: new Date().toISOString().slice(0, 10),
    equity: null, capital: null, instHist: { count3y: 0 }, instAdj: { excl24_2: false, d50_4: false, r23Steps: 0, r23_2: false },
    people: [], cases: [newCase(1)], updated: Date.now(),
  };
}

/** 불러온 JSON을 현재 모양으로 맞춘다(빠진 칸은 기본값). 형식이 틀리면 예외. */
export function normalizeInspection(x) {
  if (!x || typeof x !== "object" || !Array.isArray(x.cases)) throw new Error("검사건 파일 형식이 아닙니다.");
  const base = newInspection();
  const out = { ...base, ...x, id: typeof x.id === "string" ? x.id : base.id, instHist: { ...base.instHist, ...x.instHist }, instAdj: { ...base.instAdj, ...x.instAdj } };
  out.people = (Array.isArray(x.people) ? x.people : []).map((p) => {
    const d = newPerson();
    return { ...d, ...p, hist: { ...d.hist, ...p?.hist }, adj: { ...d.adj, ...p?.adj } };
  });
  out.cases = x.cases.map((c, i) => {
    const d = newCase(i + 1);
    return {
      ...d, ...c, use: { ...d.use, ...c?.use }, inst: { ...d.inst, ...c?.inst }, fine: knownFine({ ...d.fine, ...c?.fine }),
      people: (Array.isArray(c?.people) ? c.people : []).filter((e) => e && out.people.some((p) => p.id === e.pid)).map((e) => ({ ...newPersonEntry(e.pid), ...e })),
      pens: (Array.isArray(c?.pens) ? c.pens : []).map((p) => knownPen({ ...newPenalty(), ...p })),
    };
  });
  if (!out.cases.length) out.cases = [newCase(1)];
  return out;
}

// 예전 버전의 조항 번호(F0, P12 등)나 없어진 조항은 비워서 다시 고르게 한다.
function knownFine(f) {
  if (f.itemId && !fineItem(f.itemId)) f.itemId = null;
  return f;
}
function knownPen(p) {
  if (p.itemId && !penaltyItem(p.itemId)) p.itemId = null;
  if (!["법인", "개인", "임직원"].includes(p.subject)) p.subject = "법인";
  return p;
}

export const fineItem = (id) => FINES.find((x) => x.id === id) || null;
export const penaltyItem = (id) => PENALTIES.find((x) => x.id === id) || null;

/** 제재건 하나의 계산 결과 */
export function evalCase(insp, c) {
  const out = { inst: null, people: [], fine: null, pens: [] };
  if (c.use.inst) out.inst = caseInst(c.inst, Number(insp.equity) || 0);
  if (c.use.person) {
    for (const e of c.people) {
      const p = insp.people.find((x) => x.id === e.pid);
      if (p) out.people.push({ entry: e, person: p, res: casePerson(e, p.kind) });
    }
  }
  if (c.use.fine) {
    const item = fineItem(c.fine.itemId);
    out.fine = calcFine({ ...c.fine, item, capital: Number(c.fine.capital) || Number(insp.capital) || 0 });
  }
  if (c.use.pen) {
    for (const p of c.pens) {
      const item = penaltyItem(p.itemId);
      const capital = p.subject === "법인" ? Number(insp.capital) || 0 : 0;
      out.pens.push({ pen: p, res: calcPenalty({ ...p, item, capital }) });
    }
  }
  return out;
}

/** 검사건 종합: 기관 최종, 사람별 최종, 금액 합계 */
export function evalInspection(insp) {
  const cases = insp.cases.map((c) => ({ c, r: evalCase(insp, c) }));
  const inst = instFinal(insp, cases.filter((x) => x.r.inst).map((x) => ({ caseTitle: x.c.title, caseId: x.c.id, res: x.r.inst })));
  const people = insp.people.map((p) => {
    const results = cases.flatMap((x) => x.r.people.filter((y) => y.person.id === p.id).map((y) => ({ caseTitle: x.c.title, caseId: x.c.id, res: y.res })));
    return { person: p, results, final: personFinal(p, results) };
  });
  const fineTotal = cases.reduce((s, x) => s + (x.r.fine?.final || 0), 0);
  const penTotal = cases.reduce((s, x) => s + x.r.pens.reduce((t, y) => t + (y.res.final || 0), 0), 0);
  const errors = cases.flatMap((x) => [
    x.r.inst?.error && `${x.c.title} 기관: ${x.r.inst.error}`,
    ...x.r.people.filter((y) => y.res.error).map((y) => `${x.c.title} ${y.person.name}: ${y.res.error}`),
    ...(x.r.fine?.errors || []).map((e) => `${x.c.title} 과징금: ${e}`),
    ...x.r.pens.flatMap((y) => (y.res.errors || []).map((e) => `${x.c.title} 과태료: ${e}`)),
  ].filter(Boolean));
  return { cases, inst, people, fineTotal, penTotal, errors };
}
