// 제재 수준 척도와 별표 문구 해석.
// 숫자가 작을수록 무겁다. 가중 1단계 = 숫자 1 감소, 감경 1단계 = 숫자 1 증가.

/** 임원(규정 제18조), 직원(규정 제19조) 척도. 미등기임원은 임원 척도로 양정하고 직원 조치명을 붙인다(세칙 제46조의3). */
export const PERSON = {
  임원: ["해임권고", "업무집행정지", "문책경고", "주의적경고", "주의"],
  직원: ["면직", "정직", "감봉", "견책", "주의"],
};
/** 기관(규정 제17조). 영업점 폐쇄·위법행위 중지·계약이전·공표는 병과 조치로 따로 고른다. */
export const INST = ["인가·등록 취소", "영업 전부정지", "영업 일부정지", "기관경고", "기관주의"];
export const PERSON_MAX = PERSON.임원.length - 1; // 4 = 주의
export const INST_MAX = INST.length - 1; // 4 = 기관주의

/** 사람 제재 이름 → 단계. 별표에 나오는 다른 표기(직무정지, 개선요구 등)를 함께 받는다. */
const PERSON_WORDS = [
  [/해임권고|해임요구|해임|개선요구|개선|면직/, 0],
  [/업무집행정지|직무정지|업무정지|정직/, 1],
  [/문책경고|문책|감봉/, 2],
  [/주의적경고|견책/, 3],
  [/주의/, 4],
];
const INST_WORDS = [
  [/인가.?취소|등록취소|허가취소|취소/, 0],
  [/전부\s?정지/, 1],
  [/일부\s?정지|영업정지|업무정지|영업 정지/, 2],
  [/기관경고|경고/, 3],
  [/기관주의|주의/, 4],
];

function levelOf(word, scale) {
  const list = scale === "inst" ? INST_WORDS : PERSON_WORDS;
  for (const [re, lv] of list) if (re.test(word)) return lv;
  return null;
}

/**
 * 별표 문구를 범위로 바꾼다.
 *  "직무정지 이상"  → { hi:0, lo:1, def:1 }
 *  "주의적경고 이하" → { hi:3, lo:4, def:3 }
 *  "해임권고(면직)~문책경고(감봉)" → { hi:0, lo:2, def:0 }
 *  "업무정지 6월"   → { hi:2, lo:2, def:2, detail:"6월" }  (기관)
 * 괄호 안 직원 표기는 무시한다(같은 단계). 읽지 못하면 null.
 */
export function parseRange(text, scale = "person") {
  if (!text) return null;
  const t = String(text).replace(/\([^)]*\)/g, "").replace(/\s+/g, " ").trim();
  if (/^[-–—]$|해당\s?없음/.test(t)) return null;
  const max = scale === "inst" ? INST_MAX : PERSON_MAX;
  const detail = /(\d+\s?(?:개월|월|일))/.exec(t)?.[1]?.replace(/\s/g, "") || null;
  const parts = t.split(/\s*[~∼]\s*/);
  if (parts.length === 2) {
    const a = levelOf(parts[0], scale);
    const b = levelOf(parts[1], scale);
    if (a == null || b == null) return null;
    return { hi: Math.min(a, b), lo: Math.max(a, b), def: a, detail, text: String(text) };
  }
  // "업무 전부·일부 정지"
  if (scale === "inst" && /전부.?일부/.test(t)) return { hi: 1, lo: 2, def: 1, detail, text: String(text) };
  const lv = levelOf(t, scale);
  if (lv == null) return null;
  if (/이상/.test(t)) return { hi: 0, lo: lv, def: lv, detail, text: String(text) };
  if (/이하/.test(t)) return { hi: lv, lo: max, def: lv, detail, text: String(text) };
  return { hi: lv, lo: lv, def: lv, detail, text: String(text) };
}

export function personName(lv, kind = "임원") {
  if (lv == null) return "-";
  if (lv > PERSON_MAX) return "제재 면제(자율처리 등 검토)";
  const scale = kind === "직원" ? PERSON.직원 : PERSON.임원;
  const name = scale[Math.max(0, lv)];
  return kind === "미등기임원" ? `${name} (직원 조치: ${PERSON.직원[Math.max(0, lv)]})` : name;
}

export function instName(lv) {
  if (lv == null) return "-";
  if (lv > INST_MAX) return "기관제재 면제(경영유의·개선 등 검토)";
  return INST[Math.max(0, lv)];
}

export function rangeLabel(r, scale, kind) {
  if (!r) return "-";
  const nm = (lv) => (scale === "inst" ? instName(lv) : personName(lv, kind === "미등기임원" ? "임원" : kind));
  return r.hi === r.lo ? nm(r.hi) : `${nm(r.hi)} ~ ${nm(r.lo)}`;
}

/** 단계 조정. steps>0 이면 가중(무겁게). 위로는 0에서 멈추고, 아래로는 max+1(면제 검토)까지만 내려간다. */
export function shift(lv, steps, max) {
  return Math.min(max + 1, Math.max(0, lv - steps));
}
