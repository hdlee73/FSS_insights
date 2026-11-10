// 자연어 사례 검색 엔진(서버 없이 브라우저에서 동작).
// 방식: 글자 2-gram TF-IDF 코사인 유사도 + 동의어 확장 + 제목 가중. 같은 벡터로 '유사 사례'도 찾는다.
// 형태소 분석기 없이도 "백내장 다초점렌즈 실손"처럼 낱말이 붙거나 조사가 달라져도 걸리도록 2-gram을 쓴다.

/** 같은 뜻으로 묶는 낱말들. 질문에 한 낱말이 있으면 나머지도 약한 가중치로 함께 찾는다. */
export const SYNONYMS = [
  ["실손", "실손의료보험", "실손보험", "의료실손", "실비"],
  ["백내장", "다초점", "다초점렌즈", "수정체", "인공수정체"],
  ["메신저피싱", "보이스피싱", "스미싱", "전기통신금융사기", "사기이용계좌", "지급정지", "피싱"],
  ["els", "주가연계증권", "파생결합증권", "elf", "dls", "홍콩h지수"],
  ["설명의무", "설명의무위반", "설명", "불완전판매", "적합성", "적합성원칙"],
  ["보험금", "보험금지급", "부지급", "지급거절", "면책"],
  ["고지의무", "알릴의무", "계약전알릴의무", "해지"],
  ["대출", "여신", "대환", "금리", "중도상환수수료"],
  ["펀드", "사모펀드", "라임", "옵티머스", "집합투자"],
  ["암", "암진단", "암보험", "진단비", "입원"],
  ["자동차", "자동차보험", "과실", "과실비율", "대인", "대물"],
  ["카드", "신용카드", "체크카드", "부정사용", "분실"],
  ["연금", "즉시연금", "퇴직연금", "연금보험"],
  ["청약철회", "철회", "위법계약해지", "해지권"],
];
const SYN_WEIGHT = 0.45;

const STOP = new Set(["그리고", "하지만", "때문", "경우", "대한", "관련", "있는", "없는", "했는데", "합니다", "입니다", "에서", "으로", "에게", "것이", "해서"]);

export const norm = (s) => String(s || "").toLowerCase().replace(/[^0-9a-z가-힣\s]/g, " ").replace(/\s+/g, " ").trim();

/** 낱말(공백 기준) 목록. */
export function words(s) {
  return norm(s).split(" ").filter((w) => w.length >= 2 && !STOP.has(w));
}

function bigrams(word, out, w = 1) {
  if (word.length < 2) return;
  if (word.length === 2 || !/[가-힣]/.test(word)) out.set("w:" + word, (out.get("w:" + word) || 0) + w); // 영문·숫자 낱말과 2글자 낱말은 통째로도 센다
  for (let i = 0; i + 2 <= word.length; i++) {
    const g = word.slice(i, i + 2);
    out.set(g, (out.get(g) || 0) + w);
  }
}

function countGrams(text, weight = 1, out = new Map()) {
  for (const w of words(text)) bigrams(w, out, weight);
  return out;
}

/** 질문을 동의어로 넓힌다. 반환: [{ term, weight }] */
export function expand(query) {
  const q = norm(query).replace(/\s/g, "");
  const terms = words(query).map((t) => ({ term: t, weight: 1 }));
  const seen = new Set(terms.map((t) => t.term));
  for (const group of SYNONYMS) {
    const hit = group.some((g) => q.includes(g.replace(/\s/g, "")));
    if (!hit) continue;
    for (const g of group) {
      const n = norm(g);
      if (!seen.has(n)) {
        seen.add(n);
        terms.push({ term: n, weight: SYN_WEIGHT });
      }
    }
  }
  return terms;
}

/** 색인 만들기. docs: [{ id, title, issue, tags, claims, reasoning, ... }] */
export function buildIndex(docs, fields = { title: 3, issue: 2.5, tags: 2, claims: 1, reasoning: 1, result: 0.5 }) {
  const vecs = docs.map((d) => {
    const m = new Map();
    for (const [f, w] of Object.entries(fields)) {
      const v = d[f];
      const text = Array.isArray(v) ? v.join(" ") : v && typeof v === "object" ? Object.values(v).join(" ") : v;
      if (text) countGrams(text, w, m);
    }
    return m;
  });
  const df = new Map();
  for (const m of vecs) for (const g of m.keys()) df.set(g, (df.get(g) || 0) + 1);
  const N = docs.length || 1;
  const idf = (g) => Math.log(1 + N / (1 + (df.get(g) || 0))) + 0.2;
  const weighted = vecs.map((m) => {
    const out = new Map();
    let norm2 = 0;
    for (const [g, tf] of m) {
      const x = (1 + Math.log(tf)) * idf(g);
      out.set(g, x);
      norm2 += x * x;
    }
    return { v: out, n: Math.sqrt(norm2) || 1 };
  });
  return { docs, weighted, idf, df };
}

function queryVec(index, query) {
  const m = new Map();
  for (const { term, weight } of expand(query)) bigrams(term, m, weight);
  const out = new Map();
  let n2 = 0;
  for (const [g, tf] of m) {
    const x = (1 + Math.log(tf)) * index.idf(g);
    out.set(g, x);
    n2 += x * x;
  }
  return { v: out, n: Math.sqrt(n2) || 1 };
}

const cosine = (a, b) => {
  let dot = 0;
  const [s, l] = a.v.size < b.v.size ? [a, b] : [b, a];
  for (const [g, x] of s.v) {
    const y = l.v.get(g);
    if (y) dot += x * y;
  }
  return dot / (a.n * b.n);
};

/**
 * 검색. 반환: [{ doc, score, hits }] 점수 높은 순. hits: 질문 낱말 중 문서에 실제로 들어 있는 것(강조용).
 * 점수 0.08 미만은 우연한 2-gram 일치라 버린다.
 */
export function search(index, query, { limit = 30, filter } = {}) {
  if (!norm(query)) return [];
  const q = queryVec(index, query);
  const terms = expand(query).filter((t) => t.weight === 1).map((t) => t.term);
  const out = [];
  index.docs.forEach((doc, i) => {
    if (filter && !filter(doc)) return;
    const score = cosine(q, index.weighted[i]);
    if (score < 0.08) return;
    const hay = norm([doc.title, doc.issue, (doc.tags || []).join(" ")].join(" "));
    out.push({ doc, score, hits: terms.filter((t) => hay.includes(t)) });
  });
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, limit);
}

/** 이 사례와 비슷한 사례. */
export function similar(index, id, { limit = 5 } = {}) {
  const i = index.docs.findIndex((d) => d.id === id);
  if (i < 0) return [];
  const base = index.weighted[i];
  const out = [];
  index.docs.forEach((doc, j) => {
    if (j === i) return;
    const score = cosine(base, index.weighted[j]);
    if (score >= 0.12) out.push({ doc, score });
  });
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, limit);
}

/** 배상(인용) 비율 통계: ratio(%)가 공개된 건만. */
export function ratioStats(docs) {
  const xs = docs.map((d) => d.ratio).filter((r) => typeof r === "number" && r >= 0 && r <= 100).sort((a, b) => a - b);
  if (!xs.length) return null;
  const mid = xs.length % 2 ? xs[(xs.length - 1) / 2] : (xs[xs.length / 2 - 1] + xs[xs.length / 2]) / 2;
  const bins = [0, 0, 0, 0, 0]; // 0-20,20-40,40-60,60-80,80-100
  for (const x of xs) bins[Math.min(4, Math.floor(x / 20))]++;
  return { n: xs.length, min: xs[0], max: xs[xs.length - 1], median: mid, mean: xs.reduce((a, b) => a + b, 0) / xs.length, bins };
}
