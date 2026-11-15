// 뉴스검색 핵심(앱 NewsRepository·NewsProviders·SearchQuery·PublisherCatalog·NewsText·KeywordTrend와 같은 규칙).
// 네이버 뉴스(서버 /news, 키는 서버 비밀값)를 먼저 쓰고, 안 되면 Google 뉴스 RSS(서버 /fetch)로 대신 찾는다.
import { api, fetchSite, store } from "../core.js";
import * as dm from "./dartmatch.js";

export const DEFAULT_KEYWORDS = ["금융감독원", "증권사", "자산운용사", "금융투자", "금융사고", "금감원 제재", "불완전판매", "내부통제", "사모펀드", "공매도"];
export const SCOPES = [["MAJOR_30", "30대 언론"], ["ALL", "전체 언론"]];
export const RANGES = [["DAY", "24시간", "when:1d", 1], ["WEEK", "7일", "when:7d", 7], ["MONTH", "30일", "when:30d", 30]];

export function settings() {
  const kw = store.get("news.keywords", null);
  return {
    keywords: Array.isArray(kw) && kw.length ? kw : DEFAULT_KEYWORDS,
    scope: store.get("news.scope", "ALL"),
    range: store.get("news.range", "WEEK"),
  };
}
export function saveSettings(patch) {
  if (patch.keywords) store.set("news.keywords", [...new Set(patch.keywords.map((k) => k.trim()).filter(Boolean))].slice(0, 10));
  if (patch.scope) store.set("news.scope", patch.scope);
  if (patch.range) store.set("news.range", patch.range);
}
const RANGE_EXTRA = [["DAY3", "3일", "when:3d", 3]]; // 회사별 뉴스 탭 전용(필터 목록에는 안 보임)
const rangeOf = (id) => RANGES.find((r) => r[0] === id) || RANGE_EXTRA.find((r) => r[0] === id) || RANGES[1];
export const rangeLabel = (id) => rangeOf(id)[1];
export const scopeLabel = (id) => (SCOPES.find((s) => s[0] === id) || SCOPES[1])[1];

// ---- 즐겨찾기 ----
export const bookmarks = () => store.get("news.bookmarks", []);
export const isBookmarked = (link) => bookmarks().some((b) => b.link === link);
export function toggleBookmark(a) {
  const list = bookmarks();
  const i = list.findIndex((b) => b.link === a.link);
  if (i >= 0) list.splice(i, 1);
  else list.unshift({ title: a.title, link: a.link, source: a.source, at: a.at, summary: a.summary });
  store.set("news.bookmarks", list.slice(0, 500));
  return i < 0;
}

// ---- 30대 언론 ----
export const MAJOR30 = [
  "연합뉴스", "뉴시스", "뉴스1", "KBS", "MBC", "SBS", "YTN", "JTBC", "TV조선", "MBN", "채널A",
  "조선일보", "중앙일보", "동아일보", "한겨레", "경향신문", "한국일보", "서울신문", "세계일보", "국민일보", "문화일보",
  "매일경제", "한국경제", "서울경제", "머니투데이", "이데일리", "아시아경제", "헤럴드경제", "파이낸셜뉴스", "디지털타임스",
];
const ALIASES = [
  ["yna", "연합뉴스"], ["연합 뉴스", "연합뉴스"], ["newsis", "뉴시스"], ["news1", "뉴스1"],
  ["kbs", "KBS"], ["mbc", "MBC"], ["sbs", "SBS"], ["ytn", "YTN"], ["jtbc", "JTBC"], ["tv조선", "TV조선"], ["tv chosun", "TV조선"], ["mbn", "MBN"], ["채널a", "채널A"],
  ["chosun", "조선일보"], ["joongang", "중앙일보"], ["donga", "동아일보"], ["hani", "한겨레"], ["khan", "경향신문"], ["hankookilbo", "한국일보"],
  ["seoul.co.kr", "서울신문"], ["segye", "세계일보"], ["kmib", "국민일보"], ["munhwa", "문화일보"],
  ["mk.co.kr", "매일경제"], ["매경", "매일경제"], ["hankyung", "한국경제"], ["sedaily", "서울경제"], ["mt.co.kr", "머니투데이"], ["머니s", "머니투데이"],
  ["edaily", "이데일리"], ["asiae", "아시아경제"], ["heraldcorp", "헤럴드경제"], ["fnnews", "파이낸셜뉴스"], ["dt.co.kr", "디지털타임스"],
];
export function canonicalName(source, link = "") {
  const raw = String(source || "").trim().replace(/언론사 선정$/, "");
  const exact = MAJOR30.find((m) => m.toLowerCase() === raw.toLowerCase());
  if (exact) return exact;
  let host = "";
  try {
    host = new URL(link).host;
  } catch {}
  const hay = `${raw} ${host}`.toLowerCase();
  const alias = ALIASES.find(([k]) => hay.includes(k));
  if (alias) return alias[1];
  return raw || host.replace(/^www\./, "") || "출처 미상";
}
export const isMajor = (source, link) => MAJOR30.includes(canonicalName(source, link));

// ---- 글자 다듬기 ----
const PRIORITY = ["금융감독원", "금감원", "검사", "제재", "징계", "기관경고", "기관주의", "경영유의", "과징금", "과태료", "증권선물위원회", "불완전판매", "내부통제", "횡령", "배임"];
export const normalizeTitle = (t) => t.replace(/^(\[[^\]]+\]|【[^】]+】|\([^)]*단독[^)]*\))\s*/, "").replace(/[^가-힣a-zA-Z0-9]/g, "").toLowerCase();
const matching = (title, summary, kws) => kws.filter((k) => k && `${title} ${summary}`.toLowerCase().includes(k.toLowerCase()));
const priority = (title, summary) => PRIORITY.filter((p) => `${title} ${summary}`.toLowerCase().includes(p.toLowerCase())).length >= 2;
const parser = new DOMParser();
function cleanHtml(s) {
  const text = parser.parseFromString(`<body>${s || ""}</body>`, "text/html").body.textContent || "";
  return text.replace(/\s+/g, " ").trim();
}
const NO_SUMMARY = "기사 주요 내용은 원문에서 확인하세요.";

// ---- 검색식: 공백·AND 모두 포함 / OR 하나 이상 / "문구" / -제외어 ----
const MAX_BRANCHES = 12;
export function parseQuery(raw) {
  const excluded = [];
  const tokens = tokenize(String(raw).trim(), excluded);
  if (!tokens.length) throw new Error(excluded.length ? "제외할 단어(-단어)만으로는 검색할 수 없습니다. 찾을 단어를 함께 입력해 주세요." : "검색어를 입력해 주세요.");
  let i = 0;
  const peek = () => tokens[i];
  const tooMany = () => new Error(`OR 조합이 너무 많습니다. OR 분기를 ${MAX_BRANCHES}개 이하로 줄여 주세요.`);
  const primary = () => {
    const t = peek();
    if (t?.term != null) return i++, [[t.term]];
    if (t === "(") {
      i++;
      const g = or();
      if (peek() !== ")") throw new Error("닫는 괄호가 필요합니다.");
      i++;
      return g;
    }
    throw new Error("AND/OR 앞뒤에 검색어가 필요합니다.");
  };
  const and = () => {
    let left = primary();
    for (;;) {
      const t = peek();
      if (t === "AND") i++;
      else if (!(t?.term != null || t === "(")) return left;
      const right = primary();
      if (left.length * right.length > MAX_BRANCHES) throw tooMany();
      left = left.flatMap((l) => right.map((r) => [...l, ...r]));
    }
  };
  const or = () => {
    let left = and();
    while (peek() === "OR") i++, (left = [...left, ...and()]);
    return left;
  };
  const groups = or();
  if (i !== tokens.length) throw new Error("검색식의 괄호 또는 연산자 위치를 확인해 주세요.");
  const uniq = (arr, key) => arr.filter((x, j) => arr.findIndex((y) => key(y) === key(x)) === j);
  const gs = uniq(groups.map((g) => uniq(g, (x) => x.toLowerCase())), (g) => g.join("\u001f").toLowerCase());
  if (gs.length > MAX_BRANCHES) throw tooMany();
  return {
    queries: gs.map((g) => g.map((t) => (/\s/.test(t) ? `"${t.replace(/"/g, "")}"` : t)).join(" ")),
    terms: uniq(gs.flat(), (x) => x.toLowerCase()),
    boolean: tokens.some((t) => t === "AND" || t === "OR" || t === "("),
    excluded: uniq(excluded, (x) => x.toLowerCase()),
  };
}
function tokenize(raw, excluded) {
  const out = [];
  const stop = (c) => /\s/.test(c) || "()&|".includes(c);
  const quoted = (k) => {
    const q = raw[k];
    let j = k + 1;
    while (j < raw.length && raw[j] !== q) j++;
    if (j >= raw.length) throw new Error("따옴표를 닫아 주세요.");
    const phrase = raw.slice(k + 1, j).trim();
    if (!phrase) throw new Error("빈 따옴표는 검색할 수 없습니다.");
    return [phrase, j + 1];
  };
  let k = 0;
  while (k < raw.length) {
    const c = raw[k];
    if (/\s/.test(c)) k++;
    else if (c === "-" && k + 1 < raw.length && !stop(raw[k + 1])) {
      k++;
      if (raw[k] === '"' || raw[k] === "'") {
        const [p, n] = quoted(k);
        excluded.push(p);
        k = n;
      } else {
        const s = k;
        while (k < raw.length && !stop(raw[k])) k++;
        excluded.push(raw.slice(s, k));
      }
    } else if (c === "(" || c === ")") out.push(c), k++;
    else if (c === "&") out.push("AND"), k++;
    else if (c === "|") out.push("OR"), k++;
    else if (c === '"' || c === "'") {
      const [p, n] = quoted(k);
      out.push({ term: p });
      k = n;
    } else {
      const s = k;
      while (k < raw.length && !stop(raw[k])) k++;
      const w = raw.slice(s, k);
      const up = w.toUpperCase();
      out.push(up === "AND" || w === "그리고" ? "AND" : up === "OR" || w === "또는" ? "OR" : { term: w });
    }
  }
  return out;
}
export const isExcluded = (text, ex) => ex.some((e) => e && text.toLowerCase().includes(e.toLowerCase()));

// ---- 검색 출처 ----
async function google(query, range) {
  const r = rangeOf(range);
  const url = `https://news.google.com/rss/search?q=${encodeURIComponent(`${query} ${r[2]}`)}&hl=ko&gl=KR&ceid=KR:ko`;
  const { text } = await fetchSite(url);
  const doc = parser.parseFromString(text, "text/xml");
  if (doc.querySelector("parsererror")) throw new Error("Google 뉴스 응답을 읽지 못했습니다.");
  const out = [];
  for (const it of doc.querySelectorAll("item")) {
    const get = (t) => it.getElementsByTagName(t)[0]?.textContent || "";
    const title = cleanHtml(get("title"));
    const link = get("link").trim();
    const src = get("source") || (title.includes(" - ") ? title.slice(title.lastIndexOf(" - ") + 3) : "");
    const display = src && title.endsWith(" - " + src) ? title.slice(0, -(src.length + 3)) : title;
    const source = canonicalName(src, link);
    let summary = cleanHtml(get("description"));
    if (summary.startsWith(display)) summary = summary.slice(display.length);
    if (summary.startsWith(source)) summary = summary.slice(source.length);
    summary = summary.replace(/^[\s\-–—|]+|[\s\-–—|]+$/g, "");
    if (display && link) out.push({ title: display, link, source, at: Date.parse(get("pubDate")) || 0, summary: summary || NO_SUMMARY });
  }
  return { articles: out.slice(0, 100), hasMore: false, nextStart: 1 };
}

async function naver(query, range, start = 1, size = 100) {
  const root = await api("/news", { query, display: size, start, sort: "date" });
  if (root.errorMessage) throw new Error(root.errorMessage);
  const total = Math.min(root.total || 0, 1000);
  const parsed = (root.items || []).map((it) => {
    const link = it.originallink || it.link;
    return { title: cleanHtml(it.title), link, source: canonicalName("", link), at: Date.parse(it.pubDate) || 0, summary: cleanHtml(it.description) || NO_SUMMARY };
  });
  const cutoff = Date.now() - rangeOf(range)[3] * 864e5;
  const next = start + size;
  const reached = parsed.length ? parsed[parsed.length - 1].at < cutoff : false;
  return { articles: parsed.filter((a) => a.at >= cutoff), hasMore: next <= total && next <= 1000 && !reached, nextStart: next };
}

/** 네이버를 먼저 쓰고, 실패하면(첫 쪽만) Google 뉴스로 대신 찾는다. note에 실제 쓰인 경로를 남긴다. */
async function naverFirst(query, range, start, size, note) {
  try {
    const page = await naver(query, range, start, size);
    note.naver = true;
    return page;
  } catch (e) {
    if (start > 1) throw e;
    // Google도 안 되면 원래(네이버) 오류를 보여 준다.
    const page = await google(query, range).catch(() => {
      throw e;
    });
    note.fallback ||= `Google 뉴스 (네이버 오류로 대체: ${String(e.message || e).slice(0, 80)})`;
    return page;
  }
}
const noteText = (note) => note.fallback || (note.naver ? "네이버 뉴스" : "Google 뉴스");

function refine(items, s, kws) {
  const inScope = items.filter((a) => s.scope === "ALL" || isMajor(a.source, a.link));
  const seen = new Set();
  const articles = inScope
    .sort((a, b) => b.at - a.at)
    .filter((a) => {
      const key = normalizeTitle(a.title) || a.link;
      return !seen.has(key) && seen.add(key);
    })
    .map((a) => ({ ...a, matched: matching(a.title, a.summary, kws), priority: priority(a.title, a.summary) }));
  return { articles, duplicates: inScope.length - articles.length, outletExcluded: items.length - inScope.length };
}

async function gather(jobs) {
  const res = await Promise.allSettled(jobs.map((j) => j.run()));
  const ok = res.filter((r) => r.status === "fulfilled");
  if (!ok.length) throw res.find((r) => r.status === "rejected")?.reason || new Error("기사를 불러오지 못했습니다.");
  return { res, failed: res.length - ok.length };
}

/** 직접 검색. start>1이면 '이전 기사 더 불러오기'. */
export async function search(query, s = settings(), start = 1) {
  const plan = parseQuery(query);
  const note = {};
  const { res, failed } = await gather(plan.queries.map((q) => ({ run: () => naverFirst(q, s.range, start, 100, note) })));
  const pages = res.filter((r) => r.status === "fulfilled").map((r) => r.value);
  const fetched = pages.flatMap((p) => p.articles).filter((a) => !isExcluded(`${a.title} ${a.summary}`, plan.excluded));
  const r = refine(fetched, s, plan.terms);
  const more = pages.filter((p) => p.hasMore);
  return { ...r, fetched: fetched.length, hasMore: more.length > 0, nextStart: more.length ? Math.max(...more.map((p) => p.nextStart)) : 1, note: noteText(note), failed };
}

/** 맞춤 뉴스: 내 키워드마다 첫 쪽을 모은다. 키워드에 붙인 제외어는 그 키워드로 찾은 기사에만 적용. */
export async function home(s = settings()) {
  const note = {};
  const jobs = [];
  const seenQ = new Set();
  for (const k of s.keywords) {
    let plan;
    try {
      plan = parseQuery(k);
    } catch {
      plan = { queries: [k], excluded: [], terms: [k] };
    }
    for (const q of plan.queries) if (!seenQ.has(q)) seenQ.add(q), jobs.push({ excluded: plan.excluded, run: () => naverFirst(q, s.range, 1, 100, note) });
  }
  const { res, failed } = await gather(jobs);
  const fetched = res.flatMap((r, i) => (r.status === "fulfilled" ? r.value.articles.filter((a) => !isExcluded(`${a.title} ${a.summary}`, jobs[i].excluded)) : []));
  const terms = [...new Set(s.keywords.flatMap((k) => { try { return parseQuery(k).terms; } catch { return [k]; } }))];
  return { ...refine(fetched, s, terms), fetched: fetched.length, hasMore: false, nextStart: 1, note: noteText(note), failed };
}

/** 회사별 뉴스: 최근 3일, 회사명이 제목·요약에 든 기사만(언론 범위는 전체). 영문·약칭 이름으로도 찾아 합친다. */
const coCache = new Map();
export const companySearchName = (name) => String(name || "").replace(/\(주\)|㈜|주식회사/g, "").replace(/\s+/g, " ").trim();

// 기사에서 자주 쓰는 약칭·영문 표기(회사 이름은 base()로 공백 없이 소문자로 비교). 규칙으로 풀리지 않는 곳만 둔다.
const NEWS_ALIAS = {
  농협금융지주: ["NH농협금융지주", "NH금융지주", "농협금융", "NH금융", "농협지주"],
  우리금융지주: ["우리금융", "우리지주"],
  신한금융지주: ["신한금융", "신한지주"],
  하나금융지주: ["하나금융", "하나지주"],
  메리츠금융지주: ["메리츠금융", "메리츠지주"],
  한국투자금융지주: ["한국투자금융", "한투지주", "한국금융지주"],
  디지비금융지주: ["DGB금융지주", "DGB금융", "DGB"],
  제이비금융지주: ["JB금융지주", "JB금융", "JB"],
  중소기업은행: ["IBK기업은행", "기업은행", "IBK"],
  산업은행: ["KDB산업은행", "한국산업은행", "KDB"],
  한국산업은행: ["KDB산업은행", "산업은행", "KDB"],
  한국씨티은행: ["씨티은행", "한국씨티", "씨티"],
  에스씨제일은행: ["SC제일은행", "제일은행"],
  제일은행: ["SC제일은행"],
  카카오뱅크: ["카뱅"],
  케이뱅크: ["K뱅크", "케이뱅크"],
  농협은행: ["NH농협은행", "NH은행"],
  엔에이치투자증권: ["NH투자증권", "NH증권"],
  한국투자증권: ["한투증권", "한국투자"],
  삼성화재해상보험: ["삼성화재"],
  현대해상화재보험: ["현대해상"],
  디비손해보험: ["DB손해보험", "DB손보"],
  케이비손해보험: ["KB손해보험", "KB손보"],
  케이비증권: ["KB증권"],
  케이비국민카드: ["KB국민카드", "국민카드"],
  케이비라이프생명: ["KB라이프", "KB라이프생명"],
  교보생명보험: ["교보생명"],
  한화생명보험: ["한화생명"],
  삼성생명보험: ["삼성생명"],
  신한라이프생명보험: ["신한라이프"],
  엔에이치농협생명보험: ["NH농협생명", "농협생명"],
  엔에이치농협손해보험: ["NH농협손해보험", "농협손보", "농협손해보험"],
};

/** 뉴스 검색에 쓸 이름들(앞쪽일수록 우선): 원래 이름, 영문↔한글 읽기, 줄임말(…금융지주→…금융), 별칭 사전. */
export function newsNames(name) {
  const key = companySearchName(name);
  if (!key) return [];
  const out = [key];
  const add = (v) => { v = String(v || "").trim(); if (v.length >= 2 && !out.some((o) => o.replace(/\s/g, "").toLowerCase() === v.replace(/\s/g, "").toLowerCase())) out.push(v); };
  const compact = key.replace(/\s/g, "");
  for (const a of NEWS_ALIAS[dm.base(compact)] || []) add(a);
  const latin = dm.latinize(dm.base(compact));
  const upperLatin = (t) => t.replace(/^[a-z]{2,4}/, (m) => m.toUpperCase());
  const forms = [compact];
  if (latin !== dm.base(compact)) { forms.push(upperLatin(latin)); add(upperLatin(latin)); } // 케이비금융지주 → KB금융지주
  else if (/^[A-Za-z]{2,4}/.test(compact)) add(dm.hangulize(dm.base(compact))); // KB금융지주 → 케이비금융지주
  for (const f of [...forms].reverse()) {
    const m = f.match(/^(.*?)(금융)?지주(회사)?$/);
    if (m && m[1]) {
      add(m[1] + "금융"); // KB금융
      if (/^[A-Za-z]{3,4}$/.test(m[1])) add(m[1]); // BNK, DGB처럼 그 자체가 통용되는 영문 약칭
      add(m[1] + " 지주"); // KB 지주
      add(m[1] + "지주");
    }
    const t = f.replace(/(생명|화재해상|해상화재)보험$/, "$1").replace(/화재해상$/, "화재").replace(/해상화재$/, "해상");
    if (t !== f) add(t);
  }
  return out.slice(0, 8);
}

export async function companyNews(name) {
  const names = newsNames(name);
  if (!names.length) return { articles: [], note: "", failed: 0 };
  const key = names[0];
  const hit = coCache.get(key);
  if (hit && Date.now() - hit.at < 3 * 60 * 1000) return hit.page;
  const note = {};
  const { res, failed } = await gather(names.slice(0, 5).map((n) => ({ run: () => naverFirst(`"${n}"`, "DAY3", 1, 100, note) })));
  const pages = res.filter((r) => r.status === "fulfilled").map((r) => r.value);
  const compact = (t) => t.replace(/\s+/g, "").toLowerCase();
  const ks = names.map(compact);
  const cutoff = Date.now() - 3 * 864e5;
  const raw = pages.flatMap((p) => p.articles).filter((a) => a.at >= cutoff && ks.some((k) => compact(`${a.title} ${a.summary}`).includes(k)));
  const r = refine(raw, { scope: "ALL" }, names);
  const out = { articles: r.articles, note: noteText(note), failed, names };
  coCache.set(key, { at: Date.now(), page: out });
  return out;
}

// ---- 키워드 모니터링(최근 7일 기사 수) ----
export const TREND_DAYS = 7;
export const TREND_LIMIT = 100;
const seoulDay = (t) => new Date(t + 9 * 3600e3).toISOString().slice(0, 10);
export async function trends(s = settings()) {
  const note = {};
  return Promise.all(s.keywords.map(async (k) => {
    let plan;
    try {
      plan = parseQuery(k);
    } catch {
      plan = { queries: [k], excluded: [] };
    }
    try {
      const page = await naverFirst(plan.queries[0], "WEEK", 1, TREND_LIMIT, note);
      const arts = page.articles.filter((a) => !isExcluded(`${a.title} ${a.summary}`, plan.excluded));
      const counts = Array(TREND_DAYS).fill(0);
      const today = Date.parse(seoulDay(Date.now()));
      for (const a of arts) {
        if (!a.at) continue;
        const ago = Math.round((today - Date.parse(seoulDay(a.at))) / 864e5);
        if (ago >= 0 && ago < TREND_DAYS) counts[TREND_DAYS - 1 - ago]++;
      }
      return trendOf(k, counts, page.articles.length >= TREND_LIMIT);
    } catch (e) {
      return { ...trendOf(k, Array(TREND_DAYS).fill(0), false), error: String(e.message || e) };
    }
  }));
}
function trendOf(keyword, daily, capped) {
  const today = daily[daily.length - 1] || 0;
  const prev = daily.slice(0, -1);
  const avg = prev.length ? prev.reduce((a, b) => a + b, 0) / prev.length : 0;
  // 100건이 모두 오늘 기사라 지난 날 수를 알 수 없으면 '급증'으로 보지 않는다.
  return { keyword, daily, capped, today, avg, total: daily.reduce((a, b) => a + b, 0),
    surge: today >= 5 && today >= 2 * Math.max(avg, 1) && !(capped && prev.every((v) => v === 0)) };
}
