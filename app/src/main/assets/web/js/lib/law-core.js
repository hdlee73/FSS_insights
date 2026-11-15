// 스마트 법령검색의 핵심 로직(앱 law-core 모듈을 옮김). 국가법령정보 DRF 응답은 서버의 /law 중계로 받는다.
// 위임 트리: 법률 → 시행령 → 시행규칙 → 감독규정 → 시행세칙, 판례 관련도, 키워드 통합 검색.
import { apiText, cached } from "../core.js";

// ------------------------------------------------------------------ 모델

export const LAW = 0, DECREE = 1, RULE = 2, SUP = 3, DETAIL = 4;
export const LEVELS = [
  { label: "법률", short: "법률" },
  { label: "시행령", short: "시행령" },
  { label: "시행규칙", short: "시행규칙" },
  { label: "감독규정", short: "감독규정" },
  { label: "시행세칙", short: "세칙" },
];
export const LEVEL_KEYS = ["LAW", "DECREE", "RULE", "SUPERVISORY_REGULATION", "ENFORCEMENT_DETAIL"];

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** "0420" → "420", "02-03" → "2-3" */
export function normalizeMain(raw) {
  return String(raw).trim().split("-").map((p) => p.replace(/^0+/, "") || "0").join("-");
}
export function articleNo(main, branch = null) {
  return { main: normalizeMain(main), branch: branch || null };
}
export const noDisplay = (n) => (n ? `제${n.main}조` + (n.branch ? `의${n.branch}` : "") : "");
export const joParam = (n) => (n && /^\d+$/.test(n.main) ? String(+n.main).padStart(4, "0") + String(n.branch || 0).padStart(2, "0") : null);
export const sameNo = (a, b) => !!a && !!b && a.main.replace(/^0+/, "") === b.main.replace(/^0+/, "") && (a.branch || 0) === (b.branch || 0);
export function parseNo(text) {
  const m = /제?\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?/.exec(text);
  return m ? articleNo(m[1], m[2] ? +m[2] : null) : null;
}

// 항·호·목 위치: { paragraph, subparagraph, item } (없으면 null). paragraph 0은 "각 호 외의 부분".
export const path = (paragraph = null, subparagraph = null, item = null) => ({ paragraph, subparagraph, item });
export const EMPTY = path();
export const LEAD = path(0);
export const isEmptyPath = (p) => p.paragraph == null && p.subparagraph == null && p.item == null;
export const pathKey = (p) => [p.paragraph != null ? "h" + p.paragraph : null, p.subparagraph != null ? "o" + p.subparagraph : null, p.item != null ? "m" + p.item : null].filter(Boolean).join("/");
export const pathEq = (a, b) => pathKey(a) === pathKey(b);
export const pathDisplay = (p) =>
  [p.paragraph != null ? (p.paragraph === 0 ? "각 호 외의 부분" : `제${p.paragraph}항`) : null, p.subparagraph != null ? `제${p.subparagraph}호` : null, p.item != null ? `${p.item}목` : null].filter(Boolean).join(" ");
export const pathStartsWith = (p, prefix) =>
  (prefix.paragraph == null || prefix.paragraph === p.paragraph) && (prefix.subparagraph == null || prefix.subparagraph === p.subparagraph) && (prefix.item == null || prefix.item === p.item);
export const pathParent = (p) => (p.item != null ? { ...p, item: null } : p.subparagraph != null ? { ...p, subparagraph: null } : EMPTY);

const flatItem = (i) => [i, ...i.children.flatMap(flatItem)];

/** 조문 하나. 본문을 항·호·목으로 나눈 결과(parsed)와 들여쓴 본문(body)은 필요할 때 한 번만 계산한다. */
export class Article {
  constructor(number, title, content) {
    this.number = number || null;
    this.title = title || "";
    this.content = content || "";
  }
  get parsed() {
    return (this._p ||= parseItems(this.content));
  }
  get body() {
    return (this._b ||= itemLines(this.parsed).map(([t, d]) => "  ".repeat(d) + t).join("\n").trim() || this.content);
  }
  get heading() {
    return [noDisplay(this.number), this.title.trim()].filter(Boolean).join(" ");
  }
  get fullTitle() {
    if (this.parsed.header.trim()) return this.parsed.header;
    const t = this.title.trim();
    return noDisplay(this.number) + (!t || t.startsWith("(") ? t : `(${t})`);
  }
  withContent(content) {
    return new Article(this.number, this.title, content);
  }
}
export const findItem = (parsed, p) => parsed.items.flatMap(flatItem).find((i) => pathEq(i.path, p)) || null;

// ------------------------------------------------------------------ 항·호·목 나누기

const circled = (c) => {
  const k = c.charCodeAt(0);
  if (k >= 0x2460 && k <= 0x2473) return k - 0x2460 + 1;
  if (k >= 0x3251 && k <= 0x325f) return k - 0x3251 + 21;
  if (k >= 0x32b1 && k <= 0x32bf) return k - 0x32b1 + 36;
  return null;
};
const HEADER_RE = /^\s*(제\s*\d+\s*조(?:\s*의\s*\d+)?(?:\s*\([^)]*\))?)\s*([\s\S]*)$/;
const SUB_RE = /^(\d+(?:의\d+)?)\.\s*([\s\S]*)$/;
const ITEM_RE = /^([가-힣])\.\s*([\s\S]*)$/;

export function parseItems(content) {
  const lines = content.split("\n").map((l) => l.trim()).filter(Boolean);
  let header = "";
  if (lines.length) {
    const m = HEADER_RE.exec(lines[0]);
    if (m) {
      header = m[1];
      const rest = m[2].trim();
      if (!rest) lines.shift();
      else lines[0] = rest;
    }
  }
  let lead = "";
  const roots = [];
  let para = null, sub = null, last = null;
  for (const line of lines) {
    const c = circled(line[0]);
    if (c != null) {
      const it = { path: path(c), label: line[0], text: line, children: [] };
      roots.push(it);
      para = it; sub = null; last = it;
      continue;
    }
    const s = SUB_RE.exec(line);
    if (s) {
      const base = para ? para.path : EMPTY;
      const it = { path: { ...base, subparagraph: s[1], item: null }, label: s[1] + ".", text: line, children: [] };
      (para ? para.children : roots).push(it);
      sub = it; last = it;
      continue;
    }
    const m = ITEM_RE.exec(line);
    if (m && (sub || para)) {
      const base = sub ? sub.path : para.path;
      const it = { path: { ...base, item: m[1] }, label: m[1] + ".", text: line, children: [] };
      (sub ? sub.children : para.children).push(it);
      last = it;
      continue;
    }
    if (last) last.text += "\n" + line;
    else lead += (lead ? "\n" : "") + line;
  }
  return { header, lead, items: roots };
}

/** 머리말을 뺀 본문을 [줄, 깊이] 목록으로 */
export function itemLines(parsed) {
  const out = [];
  if (parsed.lead.trim()) out.push([parsed.lead, 0]);
  const walk = (i, d) => (out.push([i.text, d]), i.children.forEach((c) => walk(c, d + 1)));
  parsed.items.forEach((i) => walk(i, 0));
  return out;
}

// ------------------------------------------------------------------ 트리 노드

/** 위임 트리 노드(평범한 객체). 복사는 {...node, 바꿀 값}. */
export function node(id, level, sourceName, article, issuer = null, children = [], anchor = null) {
  return { id, level, sourceName, article, issuer, children, anchor, part: null, delegatedBy: [] };
}
export const flatten = (n) => [n, ...n.children.flatMap(flatten)];
export const descendants = (n) => n.children.reduce((s, c) => s + 1 + descendants(c), 0);
export const findNode = (n, id) => (n.id === id ? n : n.children.reduce((r, c) => r || findNode(c, id), null));
export const displayHeading = (n) => [n.article.heading, n.part].filter(Boolean).join(" ");
export const stableKey = (n) => `${LEVEL_KEYS[n.level]}|${n.sourceName}|${noDisplay(n.article.number) || n.article.title}` + (n.part ? "#" + n.part : "");

// ------------------------------------------------------------------ XML (DRF 응답)

class DrfFormatError extends Error {}

/** DOM을 앱의 XmlNode와 같은 가벼운 트리로 바꾼다: {name, text, children} */
function toTree(el) {
  const children = [];
  let text = "";
  for (const c of el.childNodes) {
    if (c.nodeType === 1) children.push(toTree(c));
    else if (c.nodeType === 3 || c.nodeType === 4) text += c.nodeValue;
  }
  return { name: el.nodeName, text: text.trim(), children };
}
const child = (n, tag) => n.children.find((c) => c.name === tag) || null;
const childText = (n, ...tags) => {
  for (const t of tags) {
    const c = child(n, t);
    if (c && c.text.trim()) return c.text;
  }
  return null;
};
function findAll(n, pred, out = []) {
  if (typeof pred === "string") {
    const tag = pred;
    pred = (x) => x.name === tag;
  }
  if (pred(n)) out.push(n);
  n.children.forEach((c) => findAll(c, pred, out));
  return out;
}
const first = (n, tag) => findAll(n, tag)[0] || null;
const firstText = (n, ...tags) => {
  for (const t of tags) {
    const f = first(n, t);
    if (f && f.text.trim()) return f.text;
  }
  return null;
};
function deepText(n) {
  const parts = [];
  const walk = (x) => (x.text.trim() && parts.push(x.text), x.children.forEach(walk));
  walk(n);
  return parts.join("\n");
}

function root(xml) {
  const t = xml.replace(/^[﻿\s]+/, "");
  if (!t.startsWith("<")) throw new DrfFormatError(t.slice(0, 200));
  if (/^<!DOCTYPE html|^<html/i.test(t)) throw new DrfFormatError("API가 HTML을 반환했습니다. 서버의 법령 인증값 설정을 확인해 주세요.");
  const doc = new DOMParser().parseFromString(t, "application/xml");
  const err = doc.getElementsByTagName("parsererror")[0];
  if (err) throw new DrfFormatError("법령 응답을 읽지 못했습니다.");
  return toTree(doc.documentElement);
}
function diagnose(list, r, xml) {
  if (list.length || first(r, "totalCnt")) return list;
  throw new DrfFormatError(`예상과 다른 응답 <${r.name}> — ` + xml.replace(/\s+/g, " ").slice(0, 240));
}

const HEAD = /^\s*제\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?\s*(?:\(([^)]*)\))?/;

export const DrfParsers = {
  lawSearch(xml) {
    const r = root(xml);
    return diagnose(
      findAll(r, "law").map((n) => {
        const name = childText(n, "법령명한글", "법령명");
        return name ? { id: childText(n, "법령ID") || "", mst: childText(n, "법령일련번호", "MST") || "", name, abbreviation: childText(n, "법령약칭명"), kind: childText(n, "법령구분명"), effectiveDate: childText(n, "시행일자") } : null;
      }).filter(Boolean), r, xml);
  },
  lawVersions(xml) {
    return findAll(root(xml), "law").map((n) => {
      const ef = childText(n, "시행일자");
      return ef ? { mst: childText(n, "법령일련번호", "MST") || "", name: childText(n, "법령명한글", "법령명") || "", effectiveDate: ef, promulgationDate: childText(n, "공포일자"), revisionType: childText(n, "제개정구분명"), status: childText(n, "현행연혁코드") } : null;
    }).filter(Boolean);
  },
  lawBody(xml) {
    const r = root(xml);
    const name = firstText(r, "법령명_한글", "법령명한글", "법령명") || "";
    const articles = findAll(r, "조문단위")
      .filter((u) => (childText(u, "조문여부") || "조문") === "조문")
      .map((u) => {
        const no = childText(u, "조문번호");
        const branch = +(childText(u, "조문가지번호") || 0) || null;
        return new Article(no ? articleNo(no, branch) : null, childText(u, "조문제목") || "", bodyLines(u).join("\n").trim());
      });
    return { name, articles };
  },
  threeTier(xml) {
    const r = root(xml);
    const info = first(r, "기본정보") || r;
    const groups = findAll(r, (n) => n.children.some((c) => c.name.includes("법률조문")));
    const rows = groups.flatMap((g) =>
      g.children.filter((c) => c.name.includes("법률조문")).map((lawEl) => ({
        law: articleOf(lawEl),
        decrees: g.children.filter((c) => c.name.includes("시행령조문")).flatMap(articlesOf),
        rules: g.children.filter((c) => c.name.includes("시행규칙조문")).flatMap(articlesOf),
      })));
    return { lawName: childText(info, "법령명", "법률명", "법령명한글") || "", decreeName: childText(info, "시행령명"), ruleName: childText(info, "시행규칙명"), rows };
  },
  hierarchyAdminRuleNames(xml) {
    const r = root(xml);
    const direct = findAll(r, (n) => n.name.includes("행정규칙명")).map((n) => n.text);
    const nested = findAll(r, (n) => n.name.includes("행정규칙") && !n.name.includes("명"))
      .flatMap((n) => findAll(n, (c) => c.name === "법령명" || c.name === "명칭" || c.name === "법령명한글"))
      .map((n) => n.text);
    return [...new Set([...direct, ...nested].map((s) => s.trim()).filter(Boolean))];
  },
  adminRuleSearch(xml) {
    const r = root(xml);
    return diagnose(
      findAll(r, "admrul").map((n) => {
        const name = childText(n, "행정규칙명");
        return name ? { serialNo: childText(n, "행정규칙일련번호", "행정규칙ID") || "", name, kind: childText(n, "행정규칙종류"), agency: childText(n, "소관부처명", "발령기관명") } : null;
      }).filter(Boolean), r, xml);
  },
  adminRule(xml) {
    const r = root(xml);
    const info = first(r, "행정규칙기본정보") || r;
    return {
      serialNo: childText(info, "행정규칙일련번호") || "",
      name: childText(info, "행정규칙명") || "",
      agency: childText(info, "소관부처명", "발령기관명"),
      articles: findAll(r, "조문내용").map(deepText).flatMap(splitAdminArticles),
    };
  },
  precedentSearch(xml) {
    const r = root(xml);
    return diagnose(
      findAll(r, "prec").map((n) => {
        const name = childText(n, "사건명");
        return name ? { serialNo: childText(n, "판례일련번호") || "", caseName: name, caseNumber: childText(n, "사건번호"), decisionDate: childText(n, "선고일자"), court: childText(n, "법원명"), caseType: childText(n, "사건종류명"), matchNote: null } : null;
      }).filter(Boolean), r, xml);
  },
  precedent(xml) {
    const r = root(xml);
    const t = (...tags) => (firstText(r, ...tags) || "").replace(/<br\s*\/?>/gi, "\n").replace(/\n{3,}/g, "\n\n").trim();
    return {
      summary: { serialNo: t("판례정보일련번호", "판례일련번호"), caseName: t("사건명"), caseNumber: t("사건번호") || null, decisionDate: t("선고일자") || null, court: t("법원명") || null, caseType: t("사건종류명") || null },
      holdings: t("판시사항"),
      gist: t("판결요지"),
      referencedArticles: t("참조조문"),
      referencedCases: t("참조판례"),
      fullText: t("판례내용"),
    };
  },
};

function bodyLines(unit) {
  const lines = [];
  const labelled = (label, text) => {
    const t = (text || "").trim();
    if (!t) return null;
    const l = (label || "").trim();
    return !l || t.startsWith(l) ? t : `${l} ${t}`;
  };
  const push = (v) => v && lines.push(v);
  const c = childText(unit, "조문내용");
  if (c) lines.push(c.trim());
  for (const hang of unit.children.filter((x) => x.name === "항")) {
    push(labelled(childText(hang, "항번호"), childText(hang, "항내용")));
    for (const ho of hang.children.filter((x) => x.name === "호")) {
      push(labelled(childText(ho, "호번호"), childText(ho, "호내용")));
      for (const mok of ho.children.filter((x) => x.name === "목")) push(labelled(childText(mok, "목번호"), childText(mok, "목내용")));
    }
  }
  return lines;
}
const NUMBER_TAGS = new Set(["조번호", "조문번호"]);
const CONTENT_TAGS = new Set(["조내용", "조문내용"]);
const ownArticle = (el) => el.children.some((c) => NUMBER_TAGS.has(c.name) || CONTENT_TAGS.has(c.name));
function articlesOf(el) {
  return ownArticle(el) ? [articleOf(el)] : el.children.filter(ownArticle).map(articleOf);
}
function articleOf(el) {
  const no = childText(el, "조번호", "조문번호");
  const branch = +(childText(el, "조가지번호", "조문가지번호") || 0) || null;
  const content = childText(el, "조내용", "조문내용") ?? deepText(el);
  const rawTitle = childText(el, "조제목", "조문제목");
  let title;
  if (rawTitle != null) {
    const m = HEAD.exec(rawTitle);
    title = m && m.index === rawTitle.length - rawTitle.trimStart().length ? m[3] || "" : rawTitle;
  } else title = HEAD.exec(content)?.[3] || "";
  let number = no ? parseNo(no) || articleNo(no, branch) : parseNo(content.slice(0, 20));
  if (number && branch && !number.branch) number = { ...number, branch };
  return new Article(number, title, content.trim());
}
export function splitAdminArticles(block) {
  const starts = [...block.matchAll(/^\s*제\s*\d+(?:-\d+)*\s*조(?:\s*의\s*\d+)?\s*\(/gm)].map((m) => m.index);
  return starts.map((s, i) => {
    const chunk = block.slice(s, i + 1 < starts.length ? starts[i + 1] : block.length).trim();
    const m = HEAD.exec(chunk);
    return new Article(m ? { main: m[1], branch: m[2] ? +m[2] : null } : null, m?.[3] || "", chunk);
  });
}

// ------------------------------------------------------------------ 저장소 (서버 /law 중계)

const TTL = 30 * 60 * 1000;
// 매일 새로 모아 올리는 금융 관련 법령 사전수집 자료(GitHub Actions law-data). 같은 요청이 있으면 서버 왕복 없이 이것을 쓰고, 없거나 오래됐으면 실시간 조회로 대신한다.
const PRE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/law-data/";
const PRE_STALE = 5 * 86400e3;
const preIndex = () => cached("law:pre:index", 6 * 3600e3, async () => {
  const r = await fetch(PRE + "index.json");
  if (!r.ok) throw new Error("사전수집 없음");
  const d = await r.json();
  const t = Date.parse(String(d.updated || "").replace(" KST", "+09:00").replace(" ", "T"));
  if (!(Date.now() - t < PRE_STALE)) throw new Error("사전수집이 오래됨");
  return d.keys || {};
});
const preKey = (endpoint, p) => endpoint + "?" + Object.entries(p).filter(([k, v]) => v != null && k !== "type" && k !== "JO").map(([k, v]) => `${k}=${v}`).sort().join("&"); // 서버의 build_law.mjs keyOf와 같은 규칙(JO는 빼고 전체 본문에서 번호로 고른다)
const preText = async (endpoint, p) => {
  const name = (await preIndex().catch(() => ({})))[preKey(endpoint, p)];
  if (!name) return null;
  return cached("law:pre:" + name, TTL, async () => {
    const r = await fetch(PRE + name);
    if (!r.ok) throw new Error("사전수집 파일 오류");
    return r.text();
  }).catch(() => null);
};
const drf = (endpoint, params) => {
  const p = { type: "XML", ...params };
  const key = "law:" + endpoint + "?" + new URLSearchParams(Object.entries(p).filter(([, v]) => v != null)).toString();
  return cached(key, TTL, async () => (await preText(endpoint, p)) ?? apiText("/law/DRF/" + endpoint, p));
};
const compact = (s) => (s || "").replace(/ /g, "");

export const repo = {
  async searchLaws(query, bodySearch = false) {
    return DrfParsers.lawSearch(await drf("lawSearch.do", { target: "law", query, search: bodySearch ? 2 : 1, display: 20 }));
  },
  async lawArticles(law, no = null) {
    return DrfParsers.lawBody(await drf("lawService.do", { target: "law", MST: law.mst, JO: joParam(no) })).articles;
  },
  async lawVersions(law) {
    const seen = new Set();
    return DrfParsers.lawVersions(await drf("lawSearch.do", { target: "eflaw", query: law.name, nw: "1,2,3", display: 100 }))
      .filter((v) => compact(v.name) === compact(law.name))
      .filter((v) => !seen.has(v.mst + v.effectiveDate) && seen.add(v.mst + v.effectiveDate))
      .sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate));
  },
  async versionsAsOf(name, ymd) {
    const groups = new Map();
    for (const v of DrfParsers.lawVersions(await drf("lawSearch.do", { target: "eflaw", query: name, nw: "1,2,3", display: 100 }))) {
      if (!compact(v.name).includes(compact(name))) continue;
      (groups.get(v.name) || groups.set(v.name, []).get(v.name)).push(v);
    }
    return [...groups.values()]
      .map((vs) => vs.filter((v) => v.effectiveDate <= ymd).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate))[0])
      .filter((v) => v && !(v.revisionType || "").includes("폐지"));
  },
  async lawArticlesAt(version, no = null) {
    return DrfParsers.lawBody(await drf("lawService.do", { target: "eflaw", MST: version.mst, efYd: version.effectiveDate, JO: joParam(no) })).articles;
  },
  async threeTier(mst) {
    return DrfParsers.threeTier(await drf("lawService.do", { target: "thdCmp", MST: mst, knd: 2 }));
  },
  async relatedAdminRuleNames(law) {
    return DrfParsers.hierarchyAdminRuleNames(await drf("lawService.do", { target: "lsStmd", MST: law.mst }));
  },
  async searchAdminRules(query) {
    return DrfParsers.adminRuleSearch(await drf("lawSearch.do", { target: "admrul", query, search: 1, display: 20 }));
  },
  async adminRule(serialNo) {
    return DrfParsers.adminRule(await drf("lawService.do", { target: "admrul", ID: serialNo }));
  },
  async searchPrecedents(query, bodySearch = true) {
    return DrfParsers.precedentSearch(await drf("lawSearch.do", { target: "prec", query, search: bodySearch ? 2 : 1, display: 40, sort: "ddes" }));
  },
  async precedent(serialNo) {
    return DrfParsers.precedent(await drf("lawService.do", { target: "prec", ID: serialNo }));
  },
};

// ------------------------------------------------------------------ 검색어 해석

const ALIASES = {
  자본시장법: "자본시장과 금융투자업에 관한 법률",
  자통법: "자본시장과 금융투자업에 관한 법률",
  금소법: "금융소비자 보호에 관한 법률",
  금융소비자보호법: "금융소비자 보호에 관한 법률",
  전금법: "전자금융거래법",
  여전법: "여신전문금융업법",
  저축은행법: "상호저축은행법",
  금융지주법: "금융지주회사법",
  특금법: "특정 금융거래정보의 보고 및 이용 등에 관한 법률",
  신용정보법: "신용정보의 이용 및 보호에 관한 법률",
  금융실명법: "금융실명거래 및 비밀보장에 관한 법률",
  외감법: "주식회사 등의 외부감사에 관한 법률",
  개인정보보호법: "개인정보 보호법",
};
const SUPERVISORY = {
  "자본시장과 금융투자업에 관한 법률": [["금융투자업규정", "증권의 발행 및 공시 등에 관한 규정"], ["금융투자업규정시행세칙", "증권의 발행 및 공시 등에 관한 규정 시행세칙"]],
  은행법: [["은행업감독규정"], ["은행업감독업무시행세칙"]],
  보험업법: [["보험업감독규정"], ["보험업감독업무시행세칙"]],
  여신전문금융업법: [["여신전문금융업감독규정"], ["여신전문금융업감독업무시행세칙"]],
  전자금융거래법: [["전자금융감독규정"], ["전자금융감독규정시행세칙"]],
  금융지주회사법: [["금융지주회사감독규정"], ["금융지주회사감독규정시행세칙"]],
  상호저축은행법: [["상호저축은행업감독규정"], ["상호저축은행업감독업무시행세칙"]],
  "금융소비자 보호에 관한 법률": [["금융소비자 보호에 관한 감독규정"], ["금융소비자 보호에 관한 감독규정 시행세칙"]],
};
const HISTORICAL = ["증권거래법", "증권투자신탁업법", "간접투자자산운용업법", "선물거래법", "종합금융회사에 관한 법률", "금융산업의 구조개선에 관한 법률", "자산유동화에 관한 법률", "신탁법", "외국환거래법", "한국증권선물거래소법", "주식회사의 외부감사에 관한 법률", "주식회사등의외부감사에관한법률", "증권투자회사법", "부동산투자회사법", "상법", "신용협동조합법", "새마을금고법", "한국산업은행법", "예금자보호법", "독점규제 및 공정거래에 관한 법률", "한국은행법", "은행법", "보험업법", "여신전문금융업법", "상호저축은행법", "신용정보의 이용 및 보호에 관한 법률"];

export const LawAliases = {
  namesFor: (formal) => [formal, ...Object.keys(ALIASES).filter((k) => ALIASES[k] === formal)],
  knownNames: () => [...new Set([...Object.keys(ALIASES), ...Object.values(ALIASES), ...Object.keys(SUPERVISORY)])].sort((a, b) => b.length - a.length),
  isKnown: (name) => name in ALIASES || compact(name) in ALIASES || name in SUPERVISORY,
  resolve: (name) => ALIASES[name] || ALIASES[compact(name)] || name,
  majorLaws: () => [...new Set([...Object.keys(SUPERVISORY), ...Object.values(ALIASES).filter((v) => v !== "개인정보 보호법")])],
  historical: () => HISTORICAL,
  supervisory: (law) => SUPERVISORY[law]?.[0] || [],
  details: (law) => SUPERVISORY[law]?.[1] || [],
};

const Q_ARTICLE = /(?:제\s*)?(\d+)\s*조(?:\s*의\s*(\d+))?|§\s*(\d+)(?:\s*의\s*(\d+))?/;
const at = (re, s, pos) => {
  const r = new RegExp(re.source, "y");
  r.lastIndex = pos;
  return r.exec(s);
};

/** "자본시장법 제11조 제2항" → { raw, lawName, article, path } 또는 { error } */
export function parseQuery(input) {
  const raw = (input || "").trim();
  if (!raw) return { error: "법령명과 조문을 입력해 주세요. 예) 자본시장법 제11조" };
  const m = Q_ARTICLE.exec(raw);
  if (!m) return { error: "조문 번호를 찾지 못했어요. '제11조'처럼 입력해 주세요." };
  const main = m[1] || m[3];
  const branch = +(m[2] || m[4] || 0) || null;
  let pos = m.index + m[0].length;
  let paragraph = null, sub = null, item = null;
  let x = at(/\s*(?:제\s*)?(\d+)\s*항/, raw, pos);
  if (x) (paragraph = +x[1], pos += x[0].length);
  x = at(/\s*(?:제\s*)?(\d+(?:\s*의\s*\d+)?)\s*호/, raw, pos);
  if (x) (sub = x[1].replace(/\s+/g, ""), pos += x[0].length);
  x = at(/\s*([가-힣])\s*목/, raw, pos);
  if (x) (item = x[1], pos += x[0].length);
  const name = (raw.slice(0, m.index) + " " + raw.slice(pos)).replace(/\s+/g, " ").trim().replace(/[,.·]+$/, "").trim();
  if (!name) return { error: "법령명을 함께 입력해 주세요. 예) 자본시장법 제11조" };
  return { raw, lawName: LawAliases.resolve(name), article: articleNo(main, branch), path: path(paragraph, sub, item) };
}
export const queryTarget = (q) => (noDisplay(q.article) + " " + pathDisplay(q.path)).trim();
/** 조문 번호가 있으면 위임 트리, 없으면 키워드 검색 */
export const isArticleQuery = (s) => Q_ARTICLE.test(s || "");

// ------------------------------------------------------------------ 본문 인용 링크

const NO = String.raw`제\s*(\d+)\s*조(?:\s*의\s*(\d+))?`;
const NOT_A_LAW = new Set(["대통령령", "총리령", "부령", "법령", "방법", "민법상", "이법"]);

/** 본문 속 "법 제12조", 「은행법」 제34조 등을 [{start, end, query}]로. ctx = {lawName, decreeName, ruleName, ownName} */
export function findCitations(text, ctx) {
  const out = [];
  const overlaps = (s, e) => out.some((c) => s < c.end && e > c.start);
  const add = (m, name, g) => {
    const s = m.index, e = m.index + m[0].length;
    if (!name || !name.trim() || overlaps(s, e)) return;
    out.push({ start: s, end: e, query: `${name} ${noDisplay(articleNo(m[g], +(m[g + 1] || 0) || null))}` });
  };
  for (const m of text.matchAll(new RegExp(`「([^」]+)」\\s*${NO}`, "g"))) add(m, m[1].trim(), 2);
  for (const m of text.matchAll(new RegExp(`(같은\\s*)?([가-힣]*(?:법률|법|시행령|령|시행규칙|규칙)|영)\\s*${NO}`, "g"))) {
    const w = m[2];
    if (NOT_A_LAW.has(w)) continue;
    const name = w === "법" || w === "법률" ? ctx.lawName
      : w === "영" || w === "시행령" ? ctx.decreeName || `${ctx.lawName} 시행령`
      : w === "규칙" || w === "시행규칙" ? ctx.ruleName || `${ctx.lawName} 시행규칙`
      : w.length >= 3 && !m[1] ? w : null;
    add(m, name, 3);
  }
  for (const m of text.matchAll(new RegExp(NO, "g"))) add(m, ctx.ownName, 1);
  return out.sort((a, b) => a.start - b.start);
}
export function citationContext(rootNode, n) {
  const all = flatten(rootNode);
  return {
    lawName: rootNode.sourceName,
    decreeName: all.find((x) => x.level === DECREE)?.sourceName || null,
    ruleName: all.find((x) => x.level === RULE)?.sourceName || null,
    ownName: n.level <= RULE ? n.sourceName : null, // 감독규정·세칙의 "제2-3조"는 행정규칙 조문이라 연결하지 않는다
  };
}

/** 글 속 "자본시장법 제55조", 「…」 시행령 제N조 인용을 찾는다(법령명을 알 수 있는 것만). */
export function extractCitations(text) {
  const names = LawAliases.knownNames();
  const found = new Map();
  for (const m of text.matchAll(/제\s*(\d+)\s*조(?:\s*의\s*(\d+))?(?:\s*제\s*(\d+)\s*항)?/g)) {
    const before = text.slice(Math.max(0, m.index - 80), m.index).replace(/[\s ]+$/, "");
    let law = null, level = null;
    const qm = /「([^」]{2,60})」\s*(시행령|시행규칙)?\s*$/.exec(before);
    if (qm) (law = qm[1].trim(), level = qm[2] || null);
    else {
      level = /\s*(시행령|시행규칙)\s*$/.exec(before)?.[1] || null;
      const head = level ? before.replace(/\s*(시행령|시행규칙)\s*$/, "") : before;
      law = names.find((n) => head.endsWith(n)) || null;
    }
    if (!law) continue;
    const art = `제${m[1]}조` + (m[2] ? `의${m[2]}` : "");
    const full = law + (level ? " " + level : "");
    const query = `${full} ${art}`;
    const prev = found.get(query);
    found.set(query, { lawName: full, article: art, query, text: prev?.text || `${full} ${m[0].replace(/\s+/g, "")}`, count: (prev?.count || 0) + 1 });
  }
  return [...found.values()];
}

// ------------------------------------------------------------------ 위임 판단·트리 조립

const PHRASE = /(?:대통령령|총리령|[가-힣]{1,12}부령|[가-힣]{1,12}규칙|[가-힣]{1,12}령|고시|정관|조례)[^\n.]{0,40}?(?:정하|정한|위임|고시)|위임\s*(?:하|한|받)|(?:정하여|정해)\s*고시/;
const hasPhrase = (t) => PHRASE.test(t || "");
function delegatesAt(article, p) {
  const parsed = article.parsed;
  if (isEmptyPath(p)) return hasPhrase(article.content);
  if (pathEq(p, LEAD)) return hasPhrase(parsed.lead);
  for (let q = p; !isEmptyPath(q); q = pathParent(q)) {
    const it = findItem(parsed, q);
    if (it) return flatItem(it).some((x) => hasPhrase(x.text));
  }
  return hasPhrase(article.content);
}

export const LAW_PREFIX = "(?<![가-힣])법";
export const DECREE_PREFIX = "(?:(?<![가-힣])영|시행령)";
export const RULE_PREFIX = "(?:(?<![가-힣])규칙|시행규칙)";
export const SUP_PREFIX = "(?:(?<![가-힣])규정|감독규정)";
const branchRe = (no) => (no.branch ? `\\s*의\\s*${no.branch}` : "(?!\\s*의\\s*\\d)");

/** [text]가 "[prefix] 제N조 제K항 제J호 X목"을 인용했으면 그 위치(조만이면 빈 위치), 없으면 null */
export function anchorOf(text, prefix, no) {
  const re = new RegExp(`${prefix}\\s*제\\s*${esc(no.main)}\\s*조${branchRe(no)}(?![\\d-])(?:\\s*제\\s*(\\d+)\\s*항)?(?:\\s*제\\s*(\\d+(?:\\s*의\\s*\\d+)?)\\s*호)?(?:\\s*([가-힣])\\s*목)?`);
  const m = re.exec(text);
  if (!m) return null;
  const p = path(m[1] ? +m[1] : null, m[2] ? m[2].replace(/\s+/g, "") : null, m[3] || null);
  if (isEmptyPath(p) && /^\s*(?:각\s*호\s*외의\s*부분|본문|단서)/.test(text.slice(m.index + m[0].length, m.index + m[0].length + 20))) return LEAD;
  return p;
}
export function refersTo(text, prefix, no) {
  return new RegExp(`${prefix}\\s*제\\s*${esc(no.main)}\\s*조${branchRe(no)}(?![\\d-])`).test(text);
}

const MAX_PER_PARENT = 30;
function buildTree(input) {
  const lawNo = input.lawArticle.number;
  const mk = (id, level, src, a, issuer) => node(id, level, src, a, issuer, [], null);
  const decrees = input.decrees.map((a, i) => mk("decree-" + i, DECREE, input.decreeName || `${input.lawName} 시행령`, a));
  const rules = input.rules.map((a, i) => mk("rule-" + i, RULE, input.ruleName || `${input.lawName} 시행규칙`, a));
  const rootN = mk("law", LAW, input.lawName, input.lawArticle);
  rootN.children.push(...decrees);
  decrees.forEach((d) => (d.anchor = lawNo ? delegationAnchor(d.article.content, lawNo) || EMPTY : EMPTY));
  for (const r of rules) {
    const parent = decrees.find((d) => d.article.number && refersTo(r.article.content, DECREE_PREFIX, d.article.number)) || decrees[0] || rootN;
    parent.children.push(r);
    if (parent === rootN) r.anchor = lawNo ? anchorOf(r.article.content, LAW_PREFIX, lawNo) || EMPTY : EMPTY;
  }
  const sups = [];
  input.supervisory.forEach((doc, di) =>
    doc.articles.forEach((a, ai) => {
      const parent = rules.find((r) => r.article.number && refersTo(a.content, RULE_PREFIX, r.article.number))
        || decrees.find((d) => d.article.number && refersTo(a.content, DECREE_PREFIX, d.article.number))
        || (lawNo && refersTo(a.content, LAW_PREFIX, lawNo) ? rootN : null);
      if (!parent || parent.children.length >= MAX_PER_PARENT) return;
      const n = mk(`sup-${di}-${ai}`, SUP, doc.name, a, doc.agency);
      parent.children.push(n);
      if (parent === rootN) n.anchor = anchorOf(a.content, LAW_PREFIX, lawNo) || EMPTY;
      sups.push(n);
    }));
  input.details.forEach((doc, di) =>
    doc.articles.forEach((a, ai) => {
      const parent = sups.find((s) => {
        const no = s.article.number;
        return no && (refersTo(a.content, SUP_PREFIX, no) || (no.main.includes("-") && refersTo(a.content, "", no)));
      });
      if (!parent || parent.children.length >= MAX_PER_PARENT) return;
      parent.children.push(mk(`det-${di}-${ai}`, DETAIL, doc.name, a, doc.agency));
    }));
  return rootN;
}
/**
 * 시행령이 법 조문을 인용한 위치들. strong = `법 제71조제3호에서 "대통령령으로 정하는 …"란`처럼 위임을 되풀이해 정의한 꼴,
 * weak = 그 밖의 모든 인용(`법 제71조제3호에 따른 기업금융업무` 같은 단순 인용 포함).
 */
function citeInfo(sub, lawNo) {
  const parsed = sub.parsed;
  const texts = [parsed.lead, ...parsed.items.map((p) => flatItem(p).map((x) => x.text).join("\n"))];
  let weak = texts.map((t) => anchorOf(t, LAW_PREFIX, lawNo)).filter(Boolean);
  if (!weak.length) weak = [anchorOf(sub.content, LAW_PREFIX, lawNo)].filter(Boolean);
  let strong = texts.flatMap((t) => restatedAnchors(t, lawNo));
  if (!strong.length) strong = restatedAnchors(sub.content, lawNo);
  return { weak, strong };
}
export function restatedAnchors(text, no) {
  const re = new RegExp(`${LAW_PREFIX}\\s*제\\s*${esc(no.main)}\\s*조${branchRe(no)}(?![\\d-])(?:\\s*제\\s*(\\d+)\\s*항)?(?:\\s*제\\s*(\\d+(?:\\s*의\\s*\\d+)?)\\s*호)?(?:\\s*([가-힣])\\s*목)?([^\\n"“”]{0,25}?)에서\\s*(?:["“][^"”\\n]{1,80}["”]|위임|(?:대통령령|총리령|[가-힣]{1,12}부령)으로)`, "g");
  const out = [];
  for (const m of text.matchAll(re)) {
    const p = path(m[1] ? +m[1] : null, m[2] ? m[2].replace(/\s+/g, "") : null, m[3] || null);
    out.push(isEmptyPath(p) && /^\s*(?:각\s*호\s*외의\s*부분|본문|단서)/.test(m[4]) ? LEAD : p);
  }
  return out;
}
/** 위임을 되풀이해 정의한 인용이 있으면 그 위치, 없으면 처음 인용한 위치 */
const delegationAnchor = (text, no) => restatedAnchors(text, no)[0] || anchorOf(text, LAW_PREFIX, no);

/** 위치의 법 조문 속 순서: 조 전체 < 각 호 외의 부분 < 항 < 호 < 목 */
function cmpPath(a, b) {
  const nums = (x) => (x == null ? [] : String(x).split("의").map((t) => parseInt(t, 10)).filter((n) => !isNaN(n)));
  const cmpList = (x, y) => {
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
      const c = (x[i] ?? -1) - (y[i] ?? -1);
      if (c) return c;
    }
    return 0;
  };
  const pk = (x) => x.paragraph ?? (isEmptyPath(x) ? -1 : 1); // 항 없이 호만 있는 위치는 각 호 외의 부분(0) 뒤, 조 전체는 맨 앞
  return (pk(a) - pk(b)) || cmpList(nums(a.subparagraph), nums(b.subparagraph)) || String(a.item ?? "").localeCompare(String(b.item ?? ""));
}
/** 위임한 법 조문의 항·호·목 순서로 정렬(같은 위치면 원래 순서 유지) */
export function sortByAnchor(nodes) {
  return nodes.map((n, i) => [n, i]).sort((x, y) => cmpPath(x[0].anchor || EMPTY, y[0].anchor || EMPTY) || x[1] - y[1]).map((x) => x[0]);
}
function buildSplit(input) {
  const tree = buildTree(input);
  const lawNo = input.lawArticle.number;
  if (!lawNo) return { root: tree, cited: [] };
  const infos = tree.children.map((c) => {
    const info = citeInfo(c.article, lawNo);
    return { c, info, own: info.strong.filter((a) => delegatesAt(input.lawArticle, a)) };
  });
  const related = (x, y) => pathStartsWith(x, y) || pathStartsWith(y, x);
  const kept = [], cited = [];
  for (const { c, info, own } of infos) {
    let direct;
    if (own.length) direct = true;
    else if (!info.weak.length) direct = true; // 법 조문 인용이 없는데 3단비교가 연결해 준 것은 위임으로 믿는다
    else if (!info.weak.some((w) => delegatesAt(input.lawArticle, w))) direct = false;
    // 위임 문구가 있는 위치를 "법 제N조제K호에 따른 …"처럼 인용만 했고 같은 위치의 위임을 다른 규정이 직접 정의했으면 단순 인용
    else direct = !infos.some((o) => o.c !== c && o.own.some((sa) => info.weak.some((w) => related(sa, w))));
    (direct ? kept : cited).push(c);
  }
  return { root: { ...tree, children: sortByAnchor(kept) }, cited };
}

/** 시행령 한 조의 항들이 법률의 서로 다른 위치를 위임하면 항별 노드로 쪼갠다. */
export function withParagraphPieces(n) {
  const lawNo = n.article.number;
  if (!lawNo) return n;
  return { ...n, children: sortByAnchor(n.children.flatMap((c) => splitChild(c, lawNo))) };
}
const isPara = (i) => i.path.paragraph != null && i.path.subparagraph == null && i.path.item == null;
function splitChild(c, lawNo) {
  const parsed = c.article.parsed;
  const paras = parsed.items.filter(isPara);
  if (paras.length < 2) return [c];
  const pieces = [];
  for (const p of paras) {
    const a = delegationAnchor(flatItem(p).map((x) => x.text).join("\n"), lawNo);
    if (!a && pieces.length) pieces[pieces.length - 1].paras.push(p);
    else pieces.push({ paras: [p], anchor: a || c.anchor || EMPTY });
  }
  if (new Set(pieces.map((p) => pathKey(p.anchor))).size < 2) return [c];
  const prefix = c.level === DECREE ? DECREE_PREFIX : c.level === RULE ? RULE_PREFIX : c.level === SUP ? SUP_PREFIX : null;
  const own = c.article.number;
  const pieceOf = (ch) => {
    const para = prefix && own ? anchorOf(ch.article.content, prefix, own)?.paragraph ?? null : null;
    if (para == null) return 0;
    const i = pieces.findIndex((pc) => pc.paras.some((x) => x.path.paragraph === para));
    return i >= 0 ? i : 0;
  };
  return pieces.map((pc, i) => {
    const nums = pc.paras.map((x) => x.path.paragraph).filter((x) => x != null);
    const part = nums.length === 1 ? `제${nums[0]}항` : `제${nums[0]}항~제${nums[nums.length - 1]}항`;
    const body = pc.paras.map((p) => flatItem(p).map((x) => x.text).join("\n")).join("\n");
    const content = i === 0 && parsed.lead.trim() ? parsed.lead + "\n" + body : body;
    return { ...c, id: `${c.id}#${nums[0]}`, article: c.article.withContent(content), children: c.children.filter((ch) => pieceOf(ch) === i), anchor: pc.anchor, part };
  });
}
function selectForPath(children, p) {
  const direct = children.filter((c) => c.anchor && pathStartsWith(c.anchor, p));
  if (direct.length) return [direct, null];
  const broader = children.filter((c) => c.anchor && (isEmptyPath(c.anchor) || pathStartsWith(p, c.anchor)));
  const d = pathDisplay(p);
  if (broader.length) return [broader, `${d}을(를) 직접 인용한 하위 규정은 없어, 그 조(또는 상위 항·호) 전체를 위임 근거로 삼은 규정을 보여 드려요.`];
  if (!children.length) return [children, `${d}에 연결된 하위 규정을 찾지 못했어요.`];
  return [children, `${d}을(를) 직접 인용한 하위 규정을 찾지 못해, 이 조 전체의 하위 규정을 보여 드려요. 본문에 '대통령령으로 정하는' 등 위임 문구가 있어도 시행령이 그 위치를 인용하지 않으면 연결되지 않습니다.`];
}

const DELETED = /^(?:[①-⑳㉑-㉟㊱-㊿]|\d+(?:의\d+)?\.|[가-힣]\.)?\s*삭제\s*(?:[<〈‹][^>〉›]*[>〉›])?$/;
/** 법률 조문의 위치(pathKey)별로 그 위치를 위임 근거로 삼는 하위 노드를 묶는다. 빈 키 = 조 전체. */
export function attach(article, children) {
  const parsed = article.parsed;
  const all = parsed.items.flatMap(flatItem);
  const paths = new Set(all.map((i) => pathKey(i.path)));
  const articleDeleted = !parsed.items.length && DELETED.test((parsed.lead.trim() ? parsed.lead : article.body).trim());
  const deleted = new Set(all.filter((i) => DELETED.test(i.text.trim())).map((i) => pathKey(i.path)));
  const out = new Map();
  if (articleDeleted) return out;
  for (const c of children) {
    let p = c.anchor || EMPTY;
    while (!isEmptyPath(p) && !pathEq(p, LEAD) && !paths.has(pathKey(p))) p = pathParent(p);
    const k = pathKey(p);
    if (deleted.has(k)) continue;
    (out.get(k) || out.set(k, []).get(k)).push(c);
  }
  return out;
}

/** 비교표: [{lawText, cells: Map(level → nodes), anchorStart}] */
export function compareColumns(chosen) {
  return [LAW, DECREE, RULE, SUP, DETAIL].filter((l) => l === LAW || chosen.has(l));
}
export function compareRows(rootN, chosen) {
  const levels = compareColumns(chosen);
  const split = withParagraphPieces(rootN);
  const attached = attach(split.article, split.children);
  const parsed = split.article.parsed;
  const cells = (nodes) => {
    const all = nodes.flatMap(flatten);
    const m = new Map();
    for (const lv of levels.slice(1)) {
      const xs = all.filter((x) => x.level === lv);
      if (xs.length) m.set(lv, xs);
    }
    return m;
  };
  const rows = [];
  let pending = "";
  let lastStart = 0;
  const add = (t) => {
    if (!t.trim()) return;
    if (pending) pending += "\n";
    lastStart = pending.length;
    pending += t;
  };
  const emit = (p, blank) => {
    const start = isEmptyPath(p) ? 0 : lastStart;
    const nodes = attached.get(pathKey(p));
    if (!nodes) return;
    const c = cells(nodes);
    if (!c.size) return;
    rows.push({ lawText: pending.trim() ? pending : blank || "", cells: c, anchorStart: Math.min(start, pending.length) });
    pending = "";
  };
  add(split.article.fullTitle);
  if (!parsed.items.length && !parsed.lead.trim()) add(split.article.body);
  else add(parsed.lead);
  emit(LEAD);
  const item = (i, d) => (add("  ".repeat(d) + i.text), emit(i.path), i.children.forEach((c) => item(c, d + 1)));
  parsed.items.forEach((i) => item(i, 0));
  emit(EMPTY, "(조 전체)");
  if (pending.trim()) rows.push({ lawText: pending, cells: new Map(), anchorStart: 0 });
  if (!rows.length) rows.push({ lawText: split.article.body, cells: new Map(), anchorStart: 0 });
  return rows;
}

// ------------------------------------------------------------------ 결과 안에서 찾기·숨기기

export function withoutHidden(n, hidden) {
  if (!hidden.size) return n;
  return { ...n, children: n.children.filter((c) => !hidden.has(stableKey(c))).map((c) => withoutHidden(c, hidden)) };
}
export function hiddenCount(n, hidden) {
  if (!hidden.size) return 0;
  return n.children.reduce((s, c) => s + (hidden.has(stableKey(c)) ? 1 : hiddenCount(c, hidden)), 0);
}
const FILTER_NO = /^제?\s*(\d+(?:-\d+)*)\s*조(?:\s*의\s*(\d+))?$/;
export function filterNo(q) {
  const m = FILTER_NO.exec(q.trim());
  return m ? articleNo(m[1], +(m[2] || 0) || null) : null;
}
export function nodeMatches(n, q) {
  q = q.trim();
  if (!q) return true;
  const no = filterNo(q);
  if (no) return sameNo(n.article.number, no);
  const l = q.toLowerCase();
  return n.article.heading.toLowerCase().includes(l) || n.article.content.toLowerCase().includes(l) || n.sourceName.toLowerCase().includes(l);
}
export function filterTree(rootN, q) {
  if (!q.trim()) return rootN;
  const keep = (n) => {
    if (nodeMatches(n, q)) return n;
    const kids = n.children.map(keep).filter(Boolean);
    return kids.length ? { ...n, children: kids } : null;
  };
  return { ...rootN, children: rootN.children.map(keep).filter(Boolean) };
}
export const matchCount = (rootN, q) => (q.trim() ? flatten(rootN).slice(1).filter((n) => nodeMatches(n, q)).length : 0);

// ------------------------------------------------------------------ 판례 관련도

const WHERE = [
  { rank: 3, label: "참조조문에 인용" },
  { rank: 2, label: "판결요지에 인용" },
  { rank: 1, label: "본문에 인용" },
];
const OTHER_LAW = /(?<![가-힣])[가-힣]+(?:법률|법|령|규칙)(?![가-힣])/;
function cites(text, names, no) {
  if (!text || !text.trim()) return false;
  const clean = text.replace(/\([^)]*\)/g, " ");
  const art = no.branch ? new RegExp(`제\\s*${esc(no.main)}\\s*조\\s*의\\s*${no.branch}(?!\\d)`) : new RegExp(`제\\s*${esc(no.main)}\\s*조(?!\\s*의\\s*\\d)`);
  for (const name of [...new Set(names.filter((n) => n && n.trim()))]) {
    const nameRe = new RegExp([...name.replace(/\s/g, "")].map(esc).join("\\s*"), "g");
    for (const m of clean.matchAll(nameRe)) {
      const rest = clean.slice(m.index + m[0].length, m.index + m[0].length + 160);
      const cut = OTHER_LAW.exec(rest)?.index ?? rest.length;
      if (art.test(rest.slice(0, cut))) return true;
    }
  }
  return false;
}
function precedentWhere(d, names, no) {
  if (cites(d.referencedArticles, names, no)) return WHERE[0];
  if (cites(d.gist + "\n" + d.holdings, names, no)) return WHERE[1];
  if (cites(d.fullText, names, no)) return WHERE[2];
  return null;
}

// ------------------------------------------------------------------ 유스케이스

async function settle(p) {
  try {
    return { ok: true, value: await p };
  } catch (e) {
    return { ok: false, error: e };
  }
}
/** 최대 [limit]개씩 동시에 돌린다 */
async function pool(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i], i);
    }
  }));
  return out;
}
const withTimeout = (p, ms) => Promise.race([p, new Promise((r) => setTimeout(() => r(null), ms))]);
export const formatYmd = (s) => (s && /^\d{8}$/.test(s) ? `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6)}` : s || "");
const msg = (e) => e?.message || String(e);

export class LawError extends Error {}

/**
 * "자본시장법 제12조" → 법률·시행령·시행규칙(3단비교) + 감독규정·시행세칙 + 관련 판례 트리.
 * onStep(문구)로 진행 상황을 알린다. asOf(yyyyMMdd)를 주면 그날 시행 중이던 법률 기준.
 */
export async function getDelegationTree(input, { asOf = null, onStep = () => {} } = {}) {
  const query = parseQuery(input);
  if (query.error) throw new LawError(query.error);
  onStep(`'${query.lawName}' 찾는 중`);
  const candidates = await repo.searchLaws(query.lawName);
  const law = (asOf ? pickLaw(query, candidates, true) || (await historicalLaw(query.lawName, asOf)) : null) || pickLaw(query, candidates);
  if (!law) throw new LawError(`'${query.lawName}' 법령을 찾지 못했어요.`);
  const warnings = [];

  let version = null;
  if (asOf) {
    onStep(`${formatYmd(asOf)} 시행 버전 찾는 중`);
    version = (await repo.lawVersions(law)).filter((v) => v.effectiveDate <= asOf).sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate))[0];
    if (!version) throw new LawError(`${formatYmd(asOf)}에 시행 중이던 ${law.name}을(를) 찾지 못했어요.`);
    warnings.push("감독규정·시행세칙과 판례는 현행 기준으로 함께 표시됩니다.");
  }

  onStep("3단 비교로 시행령·시행규칙 매핑 중");
  const tt = await settle(repo.threeTier(version?.mst || law.mst));
  if (!tt.ok) warnings.push(`3단 비교 조회 실패: ${msg(tt.error)}`);
  const threeTier = tt.ok ? tt.value : null;
  const row = threeTier?.rows.find((r) => sameNo(r.law.number, query.article));

  const fetched = await settle(version ? repo.lawArticlesAt(version, query.article) : repo.lawArticles(law, query.article));
  const fromBody = fetched.ok ? fetched.value.find((a) => sameNo(a.number, query.article)) : null;
  const lawArticle = fromBody || (row?.law.content.trim() ? row.law : null);
  if (!lawArticle) throw new LawError(`${law.name}에 ${noDisplay(query.article)}가 없어요.`);

  let decrees = row?.decrees || [];
  let rules = row?.rules || [];
  let decreeName = threeTier?.decreeName || null;
  let ruleName = threeTier?.ruleName || null;
  onStep("시행령 본문에서 인용 조문 찾는 중");
  const fd = await findCiting(decreeName || `${law.name} 시행령`, !version, warnings, (t) => refersTo(t, LAW_PREFIX, query.article));
  if (fd && fd[1].length) {
    if (!decrees.length) warnings.push(`3단 비교에서 시행령 연결을 받지 못해, 시행령 본문의 '법 ${noDisplay(query.article)}' 인용으로 찾았습니다.`);
    decreeName ||= fd[0];
    decrees = [...decrees, ...fd[1].filter((f) => !decrees.some((d) => sameNo(d.number, f.number)))];
  }
  onStep("시행규칙 본문에서 인용 조문 찾는 중");
  const decreeNos = decrees.map((d) => d.number).filter(Boolean);
  const fr = await findCiting(ruleName || `${law.name} 시행규칙`, !version, warnings, (t) => refersTo(t, LAW_PREFIX, query.article) || decreeNos.some((n) => refersTo(t, DECREE_PREFIX, n)));
  if (fr && fr[1].length) {
    ruleName ||= fr[0];
    rules = [...rules, ...fr[1].filter((f) => !rules.some((r) => sameNo(r.number, f.number)))];
  }

  onStep("감독규정·시행세칙과 판례 찾는 중");
  const precP = relevantPrecedents(law, query.article, warnings);
  const [supNames, detNames] = await adminRuleNames(law);
  const [supervisory, details, prec] = await Promise.all([loadAdminRules(supNames, warnings), loadAdminRules(detNames, warnings), precP]);

  const split = buildSplit({ lawName: law.name, lawArticle, decreeName, decrees, ruleName, rules, supervisory, details });
  let rootN = split.root;
  if (!isEmptyPath(query.path)) rootN = focusOn(rootN, query, warnings);
  const cited = split.cited
    .filter((c) => isEmptyPath(query.path) || !c.anchor || pathStartsWith(c.anchor, query.path) || pathStartsWith(query.path, c.anchor))
    .map((c) => ({ ...c, delegatedBy: delegatingArticles(threeTier, c, query.article) }));
  return { query, law, root: rootN, asOf, version, precedents: prec[0], precedentBasis: prec[1], cited, warnings };
}

function pickLaw(query, list, exactOnly = false) {
  const c = compact(query.lawName);
  return list.find((l) => compact(l.name) === c) || list.find((l) => l.abbreviation && compact(l.abbreviation) === c) || (exactOnly ? null : list.find((l) => l.kind === "법률") || list[0]) || null;
}
async function historicalLaw(name, asOf) {
  const resolved = LawAliases.resolve(name);
  const r = await settle(repo.versionsAsOf(resolved, asOf));
  const v = (r.ok ? r.value : []).find((x) => compact(x.name) === compact(resolved));
  return v ? { id: v.mst, mst: v.mst, name: v.name, abbreviation: null, kind: "법률", effectiveDate: v.effectiveDate } : null;
}
async function findCiting(name, current, warnings, matches) {
  if (!current) return null;
  try {
    const hit = (await repo.searchLaws(name)).find((l) => compact(l.name) === compact(name));
    if (!hit) return null;
    const arts = (await repo.lawArticles(hit)).filter((a) => a.number && matches(a.content));
    return [hit.name, arts.slice(0, 30)];
  } catch (e) {
    warnings.push(`'${name}' 본문 조회 실패: ${msg(e)}`);
    return null;
  }
}
async function adminRuleNames(law) {
  const r = await settle(repo.relatedAdminRuleNames(law));
  const h = r.ok ? r.value : [];
  const det = [...new Set([...LawAliases.details(law.name), ...h.filter((x) => x.includes("세칙"))])];
  const sup = [...new Set([...LawAliases.supervisory(law.name), ...h.filter((x) => x.includes("규정") && !x.includes("세칙"))])];
  return [sup.slice(0, 3), det.slice(0, 3)];
}
async function loadAdminRules(names, warnings) {
  const docs = await Promise.all(names.map(async (name) => {
    try {
      const list = await repo.searchAdminRules(name);
      const hit = list.find((x) => compact(x.name) === compact(name)) || list[0];
      if (!hit) return null;
      const doc = await repo.adminRule(hit.serialNo);
      return doc.name.trim() ? doc : { ...doc, name: hit.name, agency: hit.agency };
    } catch (e) {
      warnings.push(`'${name}' 조회 실패: ${msg(e)}`);
      return null;
    }
  }));
  return docs.filter(Boolean);
}
async function relevantPrecedents(law, no, warnings) {
  const target = `${law.name} ${noDisplay(no)}`;
  const s = await settle(repo.searchPrecedents(target));
  if (!s.ok) warnings.push(`판례 조회 실패: ${msg(s.error)}`);
  const candidates = (s.ok ? s.value : []).slice(0, 40);
  if (!candidates.length) return [[], `'${target}'을(를) 본문에서 찾은 판례가 없어요.`];
  const names = [...new Set([law.name, law.abbreviation, ...LawAliases.namesFor(law.name)].filter(Boolean))];
  const checked = (await pool(candidates, 6, async (p) => {
    const d = await settle(repo.precedent(p.serialNo));
    return d.ok ? [p, precedentWhere(d.value, names, no)] : null;
  })).filter(Boolean);
  if (!checked.length) return [candidates.slice(0, 10), `판례 본문을 확인하지 못해 관련도를 가리지 못했어요. 검색 결과 최근 ${Math.min(10, candidates.length)}건을 그대로 보여 드려요.`];
  const picked = checked.filter(([, w]) => w)
    .sort((a, b) => b[1].rank - a[1].rank || (b[0].decisionDate || "").localeCompare(a[0].decisionDate || ""))
    .map(([p, w]) => ({ ...p, matchNote: w.label }))
    .slice(0, 20);
  return [picked, `판례 본문 검색 결과 ${candidates.length}건 중, 본문에서 '${law.name}'(약칭 포함) 바로 뒤에 ${noDisplay(no)}를 인용한 ${picked.length}건만 골랐어요. 참조조문 → 판결요지 → 본문 순으로, 같은 순서면 최근 선고순이에요. 다른 법령의 같은 조 번호 인용은 제외했어요.`];
}
function focusOn(rootN, query, warnings) {
  const parsed = rootN.article.parsed;
  const target = findItem(parsed, query.path);
  if (!target) throw new LawError(`${rootN.sourceName} ${noDisplay(query.article)}에 ${pathDisplay(query.path)}이(가) 없어요.`);
  const lines = [];
  if (parsed.header.trim()) lines.push(parsed.header);
  if (query.path.subparagraph != null && query.path.paragraph != null) {
    const p = findItem(parsed, path(query.path.paragraph));
    if (p) lines.push(p.text.split("\n")[0]);
  }
  flatItem(target).forEach((x) => lines.push(x.text));
  const [kept, note] = selectForPath(withParagraphPieces(rootN).children, query.path);
  if (note) warnings.push(note);
  return { ...rootN, article: rootN.article.withContent(lines.join("\n").trim()), children: kept };
}
function delegatingArticles(tt, n, self) {
  const no = n.article.number;
  if (!no || !tt) return [];
  const seen = new Set();
  return tt.rows
    .filter((r) => !sameNo(r.law.number, self) && (n.level === DECREE ? r.decrees : n.level === RULE ? r.rules : []).some((a) => sameNo(a.number, no)))
    .map((r) => r.law)
    .filter((a) => !seen.has(noDisplay(a.number)) && seen.add(noDisplay(a.number)));
}

// ---- 키워드 통합 검색

const looksLikeLawName = (s) => {
  const c = compact(s);
  return LawAliases.isKnown(s) || c.endsWith("법") || c.endsWith("법률") || c.endsWith("령");
};
const asLaw = (v) => ({ id: v.mst, mst: v.mst, name: v.name, abbreviation: null, kind: null, effectiveDate: v.effectiveDate });
const isSub = (n) => n.endsWith("시행령") || n.endsWith("시행규칙");

function snippet(a, words) {
  const line = a.content.split("\n").find((l) => words.some((w) => l.includes(w)));
  if (!line) return "";
  const at = line.indexOf(words.find((w) => line.includes(w)));
  const from = Math.max(0, at - 20);
  return (from > 0 ? "…" : "") + line.slice(from, from + 80).trim() + (from + 80 < line.length ? "…" : "");
}
async function articleHits(law, words, limit, version = null) {
  const arts = (version ? await repo.lawArticlesAt(version) : await repo.lawArticles(law)).filter((a) => a.number);
  return arts
    .map((a) => {
      const inTitle = words.every((w) => a.title.includes(w));
      const inBody = words.every((w) => (a.title + "\n" + a.content).includes(w));
      return inTitle || inBody ? { law, article: a, inTitle, snippet: snippet(a, words) } : null;
    })
    .filter(Boolean)
    .sort((a, b) => b.inTitle - a.inTitle)
    .slice(0, limit);
}
const foundIn = (hits) => {
  const laws = [...new Set(hits.map((h) => h.law.name))];
  return laws.length ? `조문은 ${laws.length}곳(${laws.slice(0, 3).join(", ")}${laws.length > 3 ? " 등" : ""})에서 찾았어요.` : "조문을 찾지 못했어요.";
};
const describe = (e) => msg(e) || "(메시지 없음)";

/** 법령·행정규칙·판례·조문 키워드 통합 검색. "법령명 키워드"면 그 법령 안에서 조문을 찾는다. */
export async function searchKeyword(keyword, { asOf = null, onStep = () => {} } = {}) {
  const k = keyword.trim();
  if (k.length < 2) throw new LawError("검색어를 두 글자 이상 입력해 주세요.");
  onStep(`'${k}' 찾는 중`);
  const scopedR = await scoped(k, asOf);
  if (scopedR) return scopedR;
  const [lawsR, rulesR, precsR] = await Promise.all([settle(repo.searchLaws(k, true)), settle(repo.searchAdminRules(k)), settle(repo.searchPrecedents(k))]);
  const failures = [["법령", lawsR], ["행정규칙", rulesR], ["판례", precsR]].filter(([, r]) => !r.ok).map(([l, r]) => `${l} 검색 실패 · ${describe(r.error)}`);
  if (failures.length === 3) throw new LawError(failures.join("\n"));
  const lawList = lawsR.ok ? lawsR.value : [];
  onStep("조문 제목·내용을 훑는 중");
  const scan = asOf ? await scanAsOf(k, asOf, lawList) : await scanUnscoped(k, lawList);
  return {
    keyword: k,
    laws: asOf ? [] : lawList,
    adminRules: asOf || !rulesR.ok ? [] : rulesR.value,
    precedents: precsR.ok ? precsR.value : [],
    warnings: [...failures, ...(asOf ? [`기준일(${formatYmd(asOf)}) 검색: 조문은 그날 시행 중이던 법령에서 찾았어요. 법령·행정규칙 목록은 현행 기준이라 제외했고 판례는 현행 검색 그대로예요.`] : [])],
    articleHits: scan.hits,
    scanNote: scan.note,
    scopeLaw: null,
  };
}
async function scanUnscoped(k, found) {
  const scan = async (laws) => (await pool(laws, 4, async (l) => (await withTimeout(articleHits(l, [k], 8).catch(() => []), 30000)) || [])).flat();
  const major = new Set(LawAliases.majorLaws().map(compact));
  const byName = ((await settle(repo.searchLaws(k))).value || []).filter((l) => compact(l.name).includes(compact(k)));
  const nameIds = new Set(byName.map((l) => l.id));
  const ordered = [...byName, ...found.filter((l) => !nameIds.has(l.id))]
    .map((l, i) => [l, i])
    .sort(([a, i], [b, j]) => (nameIds.has(a.id) ? 0 : 1) - (nameIds.has(b.id) ? 0 : 1) || (major.has(compact(a.name)) ? 0 : 1) - (major.has(compact(b.name)) ? 0 : 1) || isSub(a.name) - isSub(b.name) || i - j)
    .map(([l]) => l);
  let scanned = ordered.slice(0, 8);
  let hits = await scan(scanned);
  let widened = false;
  if (!hits.length) {
    widened = true;
    const rest = ordered.slice(8, 16);
    const missing = (await pool(LawAliases.majorLaws().filter((m) => !ordered.some((l) => compact(l.name) === compact(m))), 4, async (name) => ((await settle(repo.searchLaws(name))).value || []).find((l) => compact(l.name) === compact(name)))).filter(Boolean);
    const more = [...rest, ...missing];
    hits = await scan(more);
    scanned = [...scanned, ...more];
  }
  const note = scanned.length ? `법령 ${scanned.length}곳을 훑었어요. ` + foundIn(hits) + (widened ? " 처음 훑은 곳에 없어 나머지 검색 결과와 주요 금융법령까지 넓혀 봤어요." : "") + " 법령명을 앞에 붙이면 그 법 전체에서 찾아요." : "";
  return { hits: hits.sort((a, b) => b.inTitle - a.inTitle), note };
}
async function scanAsOf(k, asOf, bodyResults) {
  const uniq = (xs) => {
    const s = new Set();
    return xs.filter((x) => !s.has(compact(x)) && s.add(compact(x)));
  };
  const nameQueries = uniq([k, ...k.split(/\s+/).filter((w) => w.length >= 2)]);
  const names = uniq([...nameQueries, ...LawAliases.historical(), ...LawAliases.majorLaws(), ...bodyResults.slice(0, 12).map((l) => l.name)]);
  const nameMatches = (n) => nameQueries.some((q) => compact(n).includes(compact(q)));
  const seen = new Set();
  const versions = (await pool(names, 4, async (n) => (await settle(repo.versionsAsOf(n, asOf))).value || []))
    .flat()
    .filter((v) => !seen.has(v.mst) && seen.add(v.mst))
    .map((v, i) => [v, i])
    .sort(([a, i], [b, j]) => (nameMatches(a.name) ? 0 : 1) - (nameMatches(b.name) ? 0 : 1) || isSub(a.name) - isSub(b.name) || i - j)
    .map(([v]) => v);
  const scan = async (vs) => (await pool(vs, 4, async (v) => (await withTimeout(articleHits(asLaw(v), [k], 8, v).catch(() => []), 30000)) || [])).flat();
  let scanned = versions.slice(0, 12);
  let hits = await scan(scanned);
  let widened = false;
  if (!hits.length && versions.length > 12) {
    widened = true;
    const more = versions.slice(12, 28);
    hits = await scan(more);
    scanned = [...scanned, ...more];
  }
  const d = formatYmd(asOf);
  const note = !scanned.length
    ? `${d}에 시행 중이던 법령을 연혁에서 찾지 못했어요. 법령명을 앞에 붙여 보세요(예: 증권거래법 ${k}).`
    : `${d} 시행 법령 ${scanned.length}곳을 훑었어요. ` + foundIn(hits) + (widened ? " 처음 훑은 곳에 없어 더 넓혀 봤어요." : "") + " 법령명을 앞에 붙이면 그 법 전체에서 찾아요.";
  return { hits: hits.sort((a, b) => b.inTitle - a.inTitle), note };
}
async function scoped(k, asOf) {
  const tokens = k.split(/\s+/).filter(Boolean);
  for (let i = tokens.length - 1; i >= 1; i--) {
    const prefix = tokens.slice(0, i).join(" ");
    if (!looksLikeLawName(prefix)) continue;
    const resolved = LawAliases.resolve(prefix);
    const c = compact(resolved);
    let law, version = null;
    if (asOf) {
      const vs = (await settle(repo.versionsAsOf(resolved, asOf))).value || [];
      const v = vs.find((x) => compact(x.name) === c) || vs.find((x) => compact(x.name).includes(c));
      if (!v) continue;
      law = asLaw(v);
      version = v;
    } else {
      const found = (await settle(repo.searchLaws(resolved))).value || [];
      law = found.find((x) => compact(x.name) === c) || found.find((x) => compact(x.name).includes(c));
      if (!law) continue;
    }
    const words = tokens.slice(i);
    const [hitsR, precsR] = await Promise.all([settle(articleHits(law, words, 40, version)), settle(repo.searchPrecedents(`${law.name} ${words.join(" ")}`))]);
    return {
      keyword: k,
      laws: [law],
      adminRules: [],
      precedents: precsR.ok ? precsR.value : [],
      warnings: [!hitsR.ok && `조문 검색 실패 · ${describe(hitsR.error)}`, !precsR.ok && `판례 검색 실패 · ${describe(precsR.error)}`].filter(Boolean),
      articleHits: hitsR.ok ? hitsR.value : [],
      scopeLaw: law,
      scanNote: "",
    };
  }
  return null;
}

/** PDF·한글 저장에 덧붙일 판례 본문과, 본문이 인용한 같은 법의 다른 조문 */
export async function buildExtras(tree, { precedentBodies, relatedArticles }) {
  const precs = precedentBodies ? (await Promise.all(tree.precedents.slice(0, 10).map((p) => settle(repo.precedent(p.serialNo))))).filter((r) => r.ok).map((r) => r.value) : [];
  let related = [];
  if (relatedArticles) {
    const self = tree.query.article;
    const seen = new Set();
    const nos = findCitations(tree.root.article.content, { lawName: tree.law.name, ownName: tree.law.name })
      .filter((c) => c.query.startsWith(tree.law.name + " "))
      .map((c) => parseNo(c.query.slice(tree.law.name.length)))
      .filter((n) => n && !sameNo(n, self) && !seen.has(noDisplay(n)) && seen.add(noDisplay(n)))
      .slice(0, 8);
    related = (await Promise.all(nos.map((n) => settle(repo.lawArticles(tree.law, n).then((as) => as.find((a) => sameNo(a.number, n))))))).filter((r) => r.ok && r.value).map((r) => r.value);
  }
  return { precedents: precs, relatedArticles: related };
}
