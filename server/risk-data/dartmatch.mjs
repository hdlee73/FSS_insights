// FISIS 회사명 → 전자공시(DART) 회사 맞추기. 화면(risk-live.js)과 사전수집기(server/risk-data/dartmatch.mjs, 이 파일의 사본)가 같은 규칙을 쓴다.
// 순수 함수만 둔다(네트워크 없음). 검색은 호출하는 쪽이 넘겨 준다.
//
// 이름이 다른 이유는 크게 네 가지다. 한곳씩 고치지 말고 아래 규칙으로 풀고, 그래도 안 되는 곳만 ALIAS에 둔다.
//  1) 영문 약어 ↔ 한글 읽기 : CK저축은행 = 씨케이저축은행, KB라이프생명 = 케이비라이프생명, DB증권 = 디비증권
//  2) 꼬리말 차이          : 삼성생명보험 = 삼성생명, 현대해상화재보험 = 현대해상, 세람상호저축은행 = 세람저축은행
//  3) 앞 글자 차이         : 농협손해보험 = NH농협손해보험(엔에이치농협손해보험)
//  4) 공시회사명이 다른 곳 : 중소기업은행 = 기업은행, 구 사명(더블저축은행 = 라인저축은행) → ALIAS 또는 DART 누리집 검색의 과거 상호 결과

const LETTER = { a: "에이", b: "비", c: "씨", d: "디", e: "이", f: "에프", g: "지", h: "에이치", i: "아이", j: "제이", k: "케이", l: "엘", m: "엠", n: "엔", o: "오", p: "피", q: "큐", r: "알", s: "에스", t: "티", u: "유", v: "브이", w: "더블유", x: "엑스", y: "와이", z: "제트" };
const LETTER_REV = Object.entries(LETTER).sort((a, b) => b[1].length - a[1].length); // 긴 읽기부터(에이치 → 에이)

/** 법인 형태 표기와 공백·구두점을 지우고 소문자로. */
export const base = (n) => String(n || "").replace(/주식회사|유한회사|\(주\)|㈜|\(유\)|\(주|\s|[.,·\-_()]/g, "").replace(/(한국|서울)지점$/, "").toLowerCase();
/** 영문 알파벳을 한글 읽기로(CK → 씨케이). */
export const hangulize = (s) => s.replace(/ez/g, "이지").replace(/[a-z]/g, (c) => LETTER[c]);
/** 앞쪽의 한글 알파벳 읽기 연속을 영문으로(비엔케이 → bnk). 3글자 이하 약어만, 최소 2자. */
export function latinize(s) {
  let i = 0, out = "";
  while (i < s.length && out.length < 5) {
    const hit = LETTER_REV.find(([, ko]) => s.startsWith(ko, i));
    if (!hit) break;
    out += hit[0]; i += hit[1].length;
  }
  return out.length >= 2 && out.length <= 4 ? out + s.slice(i) : s;
}

// 같은 뜻으로 쓰이는 꼬리말(길이 긴 것부터). 비교할 때 양쪽을 같은 모양으로 줄인다.
const TAILS = [["화재해상보험", "화재"], ["해상화재보험", "해상"], ["생명보험", "생명"], ["손해보험", "손해보험"], ["재보험", ""], ["상호저축은행", "저축은행"], ["투자증권", "증권"], ["금융투자", "증권"]];
const reduceTail = (k) => { for (const [a, b] of TAILS) if (k.endsWith(a)) return k.slice(0, -a.length) + b; return k; };
const dropSangho = (k) => k.replace(/상호(?=저축은행)/, "");
// 앞 글자 치환(브랜드 표기 차이). 한쪽만 붙은 경우를 맞춘다.
const HEADS = [["엔에이치농협", "농협"], ["nh농협", "농협"], ["엔에이치", "농협"], ["nh", "농협"], ["한국", ""]];

/** 한 회사 이름이 가질 수 있는 비교용 모양 모음. 두 이름의 모양이 하나라도 겹치면 같은 회사로 본다. */
export function forms(name) {
  let k = dropSangho(base(name));
  if (ALIAS_NAME[k]) k = base(ALIAS_NAME[k]);
  const set = new Set();
  const add = (x) => { if (x) { set.add(x); set.add(reduceTail(x)); } };
  for (const v of [k, hangulize(k)]) {
    add(v);
    for (const [a, b] of HEADS) if (v.startsWith(a) && v.length > a.length + 1) add(b + v.slice(a.length));
  }
  return set;
}

// 이름 규칙으로 풀 수 없는 곳. { FISIS 회사명(base): [DART 고유번호 | 검색어, ...] } 고유번호(8자리)는 DART 누리집에서 확인한 현행 법인.
// 확인한 근거: 중소기업은행 = DART 공시회사명 '기업은행'(종목 024110, 법인등록번호 110135-0000903).
export const ALIAS = {
  중소기업은행: "00149646",
};
// 이름만 바뀐 곳(FISIS 현재 이름 → DART에 남아 있는 이름). DART 기업개황의 영문명(DOUBLE SAVINGS BANK 등)으로 확인한 곳.
export const ALIAS_NAME = {
  더블저축은행: "라인저축은행",
  에스비아이저축은행: "교보에스비아이저축은행",
};

const JUNK = /호스팩|스팩|펀드|사모|제\d+호|제\d+차|지점|영업소|투자전문회사|유동화|신탁|조합|리츠/;
const KIND = [/저축은행/, /은행/, /선물/, /증권/, /생명/, /손해보험|화재|해상|재보험|보험/, /카드/, /캐피탈/, /자산운용|투자자문/];
const kindOf = (k) => { const i = KIND.findIndex((r) => r.test(k)); return i; };

/** 검색에 쓸 질의어 후보(앞의 것이 더 정확). */
export function queryVariants(name) {
  const clean = String(name || "").replace(/\(\s*주\s*\)|㈜|주식회사/g, " ").replace(/\s+/g, " ").trim();
  let b = dropSangho(base(clean));
  if (ALIAS_NAME[b]) b = base(ALIAS_NAME[b]);
  const out = [];
  const push = (q) => { if (q && q.length >= 2 && !out.includes(q)) out.push(q); };
  push(clean);
  push(b);
  const h = hangulize(b);
  push(h);
  const l = latinize(b);
  push(l);
  push(reduceTail(h));
  for (const [a, to] of HEADS) if (h.startsWith(a) && h.length > a.length + 1 && to) push(to + h.slice(a.length)); // 농협손해보험 ← 엔에이치농협손해보험은 반대 방향도
  if (/^농협/.test(b)) push("엔에이치" + b);
  push(b.replace(/(생명보험|손해보험|화재해상보험|해상화재보험|재보험|보험)$/, "")); // 삼성생명보험 → 삼성생명(앞에서 줄인 것과 겹치면 무시)
  return out.slice(0, 5);
}

/**
 * 후보(items: [{corp,name,stock}]) 중 가장 알맞은 것. tier: exact 이름이 같음(영문↔한글·꼬리말 차이 포함) / guess 앞부분이 같거나 구 사명 추정 / none.
 * 같은 이름이 여럿이면 상장(종목코드 있음)을 먼저 고른다.
 */
export function pickDart(name, items) {
  const mine = forms(name);
  const kind = kindOf(base(name));
  const list = (items || []).map((it, idx) => ({ it, idx, f: forms(it.name) }));
  const pri = (x) => (x.it.stock ? 0 : 1) * 1000 + x.idx;
  const share = (x) => [...mine].some((m) => x.f.has(m));
  const exs = list.filter(share).sort((a, b) => pri(a) - pri(b));
  if (exs.length) return { match: "exact", item: exs[0].it, ties: exs.map((x) => x.it) };
  const g = list.find((x) => [...x.f].some((o) => [...mine].some((m) => o.length >= 3 && m.length >= 3 && (o.startsWith(m) || m.startsWith(o)) && (kind < 0 ? Math.min(o.length, m.length) / Math.max(o.length, m.length) >= 0.7 : kindOf(base(x.it.name)) === kind))));
  if (g) return { match: "guess", item: g.it };
  return { match: "none", item: null };
}

/**
 * 여러 질의어로 검색해 가장 좋은 것. 같은 이름의 법인이 여럿이면(국민은행·신한카드 등 옛 법인 포함) recent(corp)로 최근 정기공시 날짜를 비교해 현행 법인을 고른다.
 * search(q) → [{corp,name,stock}] (실패는 빈 배열).
 * detail(corp) → 기업개황 {name, nameEn}: 이름으로 못 맞춘 경우, 같은 업종 후보의 정식명칭·영문명을 확인해 맞춘다(중소기업은행 ↔ 공시회사명 기업은행 같은 경우).
 */
export async function findDart(name, search, detail, recent) {
  const a = ALIAS[base(name)];
  if (/^\d{8}$/.test(a || "")) return { match: "exact", item: { corp: a, name }, alias: true };
  let best = { match: "none", item: null };
  const seen = [];
  const rank = { exact: 2, guess: 1, none: 0 };
  for (const q of a ? [a, ...queryVariants(name)] : queryVariants(name)) {
    const items = await search(q).catch(() => []);
    const r = pickDart(name, items);
    if (rank[r.match] > rank[best.match]) best = r;
    if (best.match === "exact") break;
    seen.push(...items);
  }
  if (best.match === "exact" && recent && best.ties?.length > 1) {
    const dated = await Promise.all(best.ties.slice(0, 4).map(async (c) => ({ c, d: (await recent(c.corp).catch(() => "")) || "" })));
    dated.sort((a, b) => (a.d < b.d ? 1 : a.d > b.d ? -1 : 0));
    if (dated[0].d) best = { ...best, item: dated[0].c };
  }
  if (best.match !== "exact" && detail) {
    const kind = kindOf(base(name));
    const mine = forms(name);
    const cands = [...new Map(seen.map((x) => [x.corp, x])).values()].filter((x) => !JUNK.test(x.name) && (kind < 0 || kindOf(base(x.name)) === kind)).slice(0, 3);
    for (const c of cands) {
      const d = await detail(c.corp).catch(() => null);
      if (!d) continue;
      const names = [d.name, d.nameEn && hangulize(base(d.nameEn))].filter(Boolean).map((n) => forms(n));
      if (names.some((f) => [...mine].some((m) => f.has(m)))) return { match: "exact", item: c, verified: true };
    }
  }
  return best;
}
