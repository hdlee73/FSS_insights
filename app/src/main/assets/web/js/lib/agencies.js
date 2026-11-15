// 보도자료·연구보고서 기관별 목록 주소와 읽는 방법. 앱의 AgencyRepository.kt(AgencySources)를 옮겼다.
// 사이트가 개편되면 이 파일만 고치면 된다.
// 앱은 막힌 사이트를 휴대폰 안 브라우저(WebView)로 다시 읽지만, 웹은 서버(/fetch)로만 읽는다.
// 그래서 스크립트로 목록을 그리는 사이트는 비어 나올 수 있고, 그때는 "사이트에서 보기"를 안내한다.
import { fetchSite, store } from "../core.js";
import * as P from "./htmllist.js";

const enc = encodeURIComponent;

export const GROUPS = {
  PRESS: { label: "금융당국 보도자료", noun: "보도자료", addNoun: "기관" },
  RESEARCH: { label: "금융관련 리포트", noun: "보고서", addNoun: "연구소" },
};

// BIS가 2025년께 사이트를 개편해 옛 RSS(/doclist/bcbspubls.rss)와 /bcbs/publications.htm은 없어졌다.
// 지금은 /committees/bcbs/publications 의 카드 목록(?page=0부터)을 읽는다. 상세 주소는 /publications/202610-... 꼴이다.
const bcbsPattern = /\/publications\/\d{6}-[^/?#"\s]+/i;

/** 기관 목록(앱의 AgencyId와 같은 순서·이름). */
export const AGENCIES = [
  {
    id: "FSS", label: "금융감독원", short: "금융감독원", group: "PRESS", noun: "보도자료",
    home: "https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218",
    listUrl: (p) => `https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&pageIndex=${p}`,
    searchUrl: (q, p) => `https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&pageIndex=${p}&searchCnd=1&searchWrd=${enc(q)}`,
    siteSearch: (q) => `https://www.fss.or.kr/fss/bbs/B0000188/list.do?menuNo=200218&searchCnd=1&searchWrd=${enc(q)}`,
    linkPattern: /view\.do\?.*nttId=\d+/,
  },
  {
    id: "FSC", label: "금융위원회", short: "금융위원회", group: "PRESS", noun: "보도자료",
    home: "https://www.fsc.go.kr/no010101",
    listUrl: (p) => `https://www.fsc.go.kr/no010101?curPage=${p}`,
    searchUrl: (q, p) => `https://www.fsc.go.kr/no010101?curPage=${p}&srchKey=sj&srchText=${enc(q)}`,
    siteSearch: (q) => `https://www.fsc.go.kr/no010101?srchKey=sj&srchText=${enc(q)}`,
    linkPattern: /no010101\/\d+/,
  },
  {
    id: "MOEF", label: "재정경제부", short: "재정경제부", group: "PRESS", noun: "보도자료",
    home: "https://www.mofe.go.kr/nw/nes/nesdta.do?bbsId=MOSFBBS_000000000028&menuNo=4010100",
    listUrl: (p) => `https://www.mofe.go.kr/nw/nes/nesdta.do?bbsId=MOSFBBS_000000000028&menuNo=4010100&pageIndex=${p}`,
    linkPattern: /detailNesDtaView\.do|nesdta\.do\?[^"]*nttId/i,
    requireDate: true,
  },
  {
    id: "BOK_PRESS", label: "한국은행", short: "한국은행", group: "PRESS", noun: "보도자료",
    home: "https://www.bok.or.kr/portal/singl/newsData/list.do?targetDepth=3&menuNo=201263&depth2=200038&depth3=201263",
    listUrl: (p, s) => `https://www.bok.or.kr/portal/singl/newsData/listCont.do?pageIndex=${p}&targetDepth=3&menuNo=201263&syncMenuChekKey=1&searchCnd=1&depth2=200038&depth3=201263&sort=1&pageUnit=${s}`,
    linkPattern: /view\.do\?[^"]*nttId=\d+/,
    requireDate: true, pageSize: 20,
  },
  {
    id: "SEC", label: "미국 SEC", short: "SEC", group: "PRESS", noun: "보도자료",
    home: "https://www.sec.gov/newsroom",
    listUrl: () => "https://www.sec.gov/news/pressreleases.rss",
    siteSearch: (q) => `https://www.google.com/search?q=site%3Asec.gov%2Fnewsroom%2Fpress-releases+${enc(q)}`,
    isRss: true, pageable: false,
  },
  {
    id: "FSS_RESEARCH", label: "금융감독연구", short: "금융감독연구", group: "RESEARCH", noun: "논문",
    home: "https://www.fss.or.kr/fss/bbs/B0000332/list.do?menuNo=200807",
    listUrl: (p) => `https://www.fss.or.kr/fss/bbs/B0000332/list.do?menuNo=200807&pageIndex=${p}`,
    searchUrl: (q, p) => `https://www.fss.or.kr/fss/bbs/B0000332/list.do?menuNo=200807&pageIndex=${p}&searchCnd=1&searchWrd=${enc(q)}`,
    siteSearch: (q) => `https://www.fss.or.kr/fss/bbs/B0000332/list.do?menuNo=200807&searchCnd=1&searchWrd=${enc(q)}`,
    // 카드형 목록: 제목·저자(소속)·등록일을 카드 안에서 읽는다.
    customParser: (body, url) => P.extractFssCards(body, url),
  },
  {
    id: "KCMI", label: "자본시장연구원", short: "자본시장연구원", group: "RESEARCH", noun: "보고서",
    home: "https://www.kcmi.re.kr/report/report_list",
    listUrl: (p, s) => `https://www.kcmi.re.kr/report/report_list?pg=${p}&pp=${s}`,
    // 화면의 '더보기'가 부르는 목록(JSON)을 직접 읽는다. 실패하면 목록 화면 HTML을 읽는다.
    jsonList: async (p, s) => {
      const { text } = await fetchSite("https://www.kcmi.re.kr/report/json_report_list", {
        method: "POST", body: `thispage=${p}&perpage=${s}&s_report_subject=&s_report_type=`, referer: "https://www.kcmi.re.kr/report/report_list",
      });
      return JSON.parse(text).map((r) => ({
        title: P.compact(P.decodeEntities(String(r.report_title || "").replace(/<[^>]+>/g, " "))),
        url: new URL(r.report_pdf_preview_link || r.report_pdf_download_link || `/report/report_view?report_no=${r.report_no}`, "https://www.kcmi.re.kr").href,
        date: String(r.pub_date || "").replace(/\./g, "-").slice(0, 10) || null,
        author: r.author || null,
      }));
    },
    customParser: (body, url) => {
      const a = safe(() => P.extractReports(body, url));
      const b = safe(() => P.extractReportsByLink(body, url));
      return b.length > a.length ? b : a;
    },
    pageSize: 20, deepSearch: false,
  },
  {
    id: "KIF", label: "한국금융연구원", short: "금융연구원", group: "RESEARCH", noun: "보고서",
    home: "https://www.kif.re.kr/kif4/publication/pub_list?mid=10",
    listUrl: () => "https://www.kif.re.kr/kif4/publication/pub_list?mid=10",
    // 목록 화면은 스크립트로 그려서, 그 스크립트가 부르는 목록(JSON)을 같은 사이트에서 부른 것처럼(Referer) 읽는다.
    jsonList: async (p, s, q = "") => {
      const { text } = await fetchSite(
        `https://www.kif.re.kr/kif4/biz/async_proc?ac=dataSearch&mid=10&nid=0&vid=0&t1=&t2=&df=&dt=&kw=${enc(q)}&pn=${p}&at=0&sfield=&pcnt=${s}&lang=0`,
        { referer: "https://www.kif.re.kr/kif4/publication/pub_list?mid=10" }
      );
      const strip = (t) => P.compact(P.decodeEntities(String(t || "").replace(/<[^>]+>/g, " ")));
      return (JSON.parse(text).datalist || []).map((r) => ({
        title: strip(r.title),
        url: `https://www.kif.re.kr/kif4/publication/pub_detail?mid=${r.mid}&nid=${r.nid}&sid=${r.sid}&vid=${r.vid}&cno=${r.cno}`,
        date: /^\d{4}-\d{2}$/.test(r.vname || "") ? `${r.vname}-01` : null,
        author: strip(r.authors || r.author) || null,
      }));
    },
    linkPattern: /pub_detail\?[^"\s]*mid=10(&|$)/,
    pageSize: 20, requireDate: true, deepSearch: false,
  },
  {
    id: "BOK_RESEARCH", label: "한국은행", short: "한국은행", group: "RESEARCH", noun: "보고서",
    home: "https://www.bok.or.kr/portal/singl/newsData/list.do?targetDepth=2&menuNo=201156&depth2=201156",
    listUrl: (p, s) => `https://www.bok.or.kr/portal/singl/newsData/listCont.do?pageIndex=${p}&targetDepth=2&menuNo=201156&syncMenuChekKey=1&searchCnd=1&depth2=201156&sort=1&pageUnit=${s}`,
    linkPattern: /view\.do\?[^"]*nttId=\d+/,
    requireDate: true, deepSearch: false, pageSize: 20,
  },
  {
    id: "IOSCO", label: "IOSCO", short: "IOSCO", group: "RESEARCH", noun: "보고서",
    home: "https://www.iosco.org/publications/?subsection=public_reports",
    listUrl: (p) => "https://www.iosco.org/publications/?subsection=public_reports" + (p > 1 ? `&page=${p}` : ""),
    searchUrl: (q, p) => `https://www.iosco.org/publications/?subsection=public_reports&keywords=${enc(q)}&keywordsTitle=on` + (p > 1 ? `&page=${p}` : ""),
    linkPattern: /pubdocs\/pdf\/IOSCOPD\d+\.pdf/i,
  },
  {
    id: "FSB", label: "FSB", short: "FSB", group: "RESEARCH", noun: "보고서",
    home: "https://www.fsb.org/publications/",
    listUrl: (p) => "https://www.fsb.org/publications/" + (p > 1 ? `?mt_page=${p}` : ""),
    customParser: (body, url) => P.extract(P.abbreviateMonths(body), url, /\/20\d{2}\/\d{2}\/[^/?#]+/).filter((x) => x.date),
    deepSearch: false,
  },
  {
    id: "BCBS", label: "바젤은행감독위원회(BIS)", short: "BCBS", group: "RESEARCH", noun: "보고서",
    home: "https://www.bis.org/committees/bcbs/publications",
    listUrl: (p) => `https://www.bis.org/committees/bcbs/publications?page=${Math.max(0, p - 1)}`,
    customParser: (body, url) => P.extractCards(body, url, bcbsPattern).filter((x) => x.date).sort(byDateDesc),
    deepSearch: false,
  },
  {
    id: "ESMA", label: "ESMA", short: "ESMA", group: "RESEARCH", noun: "보고서",
    home: "https://www.esma.europa.eu/databases-library/esma-library",
    listUrl: (p) => `https://www.esma.europa.eu/databases-library/esma-library?page=${p - 1}`,
    linkPattern: /\/document\/[\w-]+/,
    requireDate: true, deepSearch: false,
  },
  {
    id: "MAS", label: "싱가포르 통화청", short: "MAS", group: "RESEARCH", noun: "보고서",
    home: "https://www.mas.gov.sg/publications",
    listUrl: (p) => `https://www.mas.gov.sg/publications?page=${p}`,
    // 목록 화면은 스크립트로 그려서 HTML에는 카드가 없다. 그 스크립트가 부르는 검색 API(JSON)를 직접 읽는다.
    jsonList: async (p, s, q = "") => {
      const kw = String(q || "").trim();
      const api =
        "https://www.mas.gov.sg/api/v1/search?json.nl=map&wt=json" +
        `&q=${enc(kw ? `document_title_t:(${kw.replace(/[+\-&|!(){}\[\]^"~*?:\\/]/g, " ")})` : "*:*")}` +
        `&start=${(p - 1) * s}&rows=${s}&fq=${enc('mas_mastercontenttypes_sm:"Publications"')}&sort=${enc("mas_date_tdt desc")}`;
      const { text } = await fetchSite(api, { referer: "https://www.mas.gov.sg/publications" });
      return (JSON.parse(text).response?.docs || [])
        .filter((d) => d.page_url_s && d.document_title_string_s)
        .map((d) => ({
          title: P.compact(P.decodeEntities(String(d.document_title_string_s))),
          url: new URL(d.page_url_s, "https://www.mas.gov.sg").href,
          // 응답은 UTC라 싱가포르 시각(+8)으로 날짜를 맞춘다.
          date: d.mas_date_tdt ? new Date(Date.parse(d.mas_date_tdt) + 8 * 3600e3).toISOString().slice(0, 10) : null,
          author: null,
        }));
    },
    pageSize: 20, deepSearch: false,
  },
].map((a) => ({ pageable: true, pageSize: 10, deepSearch: true, ...a }));

export const agencyById = (id) => AGENCIES.find((a) => a.id === id);

function safe(f) {
  try {
    return f();
  } catch {
    return [];
  }
}
const byDateDesc = (a, b) => (b.date || "").localeCompare(a.date || "");

/** 검색어를 공백으로 나눠 모든 단어가 제목에 들어 있으면 일치. */
export function matches(title, query) {
  const tokens = query.trim().split(/\s+/).filter(Boolean);
  const t = title.toLowerCase();
  return tokens.length > 0 && tokens.every((w) => t.includes(w.toLowerCase()));
}

const attachmentLabel = /^(첨부\s?파일.{0,10}|.{0,12}(다운로드|문서\s?보기|미리\s?보기|바로\s?보기|파일\s?보기)\s*)$/;

function parse(source, body, url) {
  if (source.customParser) return source.customParser(body, url);
  let parsed = source.isRss ? P.parseRss(body, url) : P.extract(body, url, source.linkPattern);
  if (source.requireDate) parsed = parsed.filter((x) => x.date).sort(byDateDesc);
  if (!parsed.length && source.requireDate) parsed = P.extractLoose(body, url);
  return parsed.filter((x) => !attachmentLabel.test(x.title));
}

async function fetchParsed(label, url, parser) {
  let text;
  try {
    text = (await fetchSite(url)).text;
  } catch (e) {
    throw new Error(`${label} 사이트에 접속하지 못했습니다. (${e.message})\n‘사이트에서 보기’를 이용해 주세요.`);
  }
  const parsed = parser(text);
  if (!parsed.length) {
    throw new Error(`${label} 목록을 읽지 못했습니다. 사이트가 접속을 막았거나, 화면을 스크립트로 그리는 사이트라 웹에서는 읽을 수 없습니다.\n‘사이트에서 보기’를 이용해 주세요. (응답 ${text.length}자 · 링크 ${P.anchorCount(text)}개)`);
  }
  return parsed;
}

async function fetchPage(agency, source, url, page = 1, query = "") {
  if (source.jsonList) {
    try {
      const items = await source.jsonList(page, source.pageSize, query);
      if (items.length) return items;
    } catch {
      /* 아래의 목록 화면 읽기로 */
    }
  }
  try {
    return await fetchParsed(agency.label, url, (body) => parse(source, body, url));
  } catch (e) {
    if (!source.fallback) throw e;
    const fb = source.fallback;
    const fbUrl = fb.listUrl(1, fb.pageSize || 10);
    return fetchParsed(agency.label, fbUrl, (body) => parse(fb, body, fbUrl));
  }
}

const toItem = (agency, x) => ({ agency: agency.id, label: agency.short, title: x.title, link: x.url, date: x.date || null, author: x.author || null });

/** 가장 최근 count건. 첫 쪽을 읽으면 onPartial로 먼저 알리고, 모자라면 다음 쪽(최대 4쪽)을 함께 읽는다. */
export async function latest(agency, count = 20, onPartial = () => {}) {
  const items = (await fetchPage(agency, agency, agency.listUrl(1, agency.pageSize))).map((x) => toItem(agency, x));
  const current = () => [...items].sort(byDateDesc).slice(0, count);
  const target = agency.id === "KCMI" || agency.id === "IOSCO" ? count * 2 : count;
  if (items.length >= target || !agency.pageable) return current();
  onPartial(current());
  const needed = Math.min(3, Math.max(1, Math.ceil((target - items.length) / Math.max(1, items.length))));
  const pages = await Promise.all(
    Array.from({ length: needed }, (_, i) => fetchPage(agency, agency, agency.listUrl(i + 2, agency.pageSize), i + 2).catch(() => null))
  );
  for (const more of pages) {
    if (!more) continue;
    for (const x of more) if (!items.some((it) => it.link === x.url)) items.push(toItem(agency, x));
  }
  return current();
}

const SEARCH_YEARS = 10;
/** 과거 자료 제목 검색(최근 10년). 여러 쪽을 4쪽씩 함께 읽어 제목이 맞는 것을 모은다. */
export async function search(agency, query, page = 1) {
  const research = agency.group === "RESEARCH";
  const maxPages = research ? 12 : 4;
  const target = research ? 30 : 10;
  const cut = new Date();
  cut.setFullYear(cut.getFullYear() - SEARCH_YEARS);
  const cutoff = cut.toISOString().slice(0, 10);
  const found = new Map();
  let current = page;
  let done = false;
  let scanned = 0;
  while (!done && scanned < maxPages) {
    const pages = agency.pageable ? [0, 1, 2, 3].map((i) => current + i).filter((p) => p <= 200) : [current];
    const results = await Promise.all(
      pages.map((p) => {
        const url = agency.searchUrl ? agency.searchUrl(query, p) : agency.listUrl(p, agency.pageSize);
        return fetchPage(agency, agency, url, p, query).then((v) => ({ ok: v }), (e) => ({ err: e }));
      })
    );
    for (const [i, r] of results.entries()) {
      const p = pages[i];
      if (r.err && p === page) throw r.err;
      const items = r.ok || [];
      for (const x of items) if (matches(x.title, query) && (!x.date || x.date >= cutoff) && !found.has(x.url)) found.set(x.url, toItem(agency, x));
      scanned++;
      current = p + 1;
      const dated = items.map((x) => x.date).filter(Boolean);
      const tooOld = dated.length > 0 && dated.every((d) => d < cutoff);
      if (!agency.pageable || !items.length || tooOld || p >= 200) {
        done = true;
        break;
      }
    }
    if (found.size >= target) break;
  }
  return { items: [...found.values()].sort(byDateDesc), hasMore: !done, nextPage: current };
}

// ---- 사용자가 추가한 기관 ----
export function customList(group) {
  return store.get("institutes", []).filter((x) => (x.group || "RESEARCH") === group);
}
export function addCustom(name, url, group) {
  const list = store.get("institutes", []).filter((x) => x.url !== url);
  list.push({ name, url, group });
  store.set("institutes", list);
}
export function removeCustom(url) {
  store.set("institutes", store.get("institutes", []).filter((x) => x.url !== url));
}
export async function latestCustom(inst, count = 20) {
  const parsed = await fetchParsed(inst.name, inst.url, (body) => P.extractGeneric(body, inst.url));
  return parsed.slice(0, count).map((x) => ({ agency: "CUSTOM", label: inst.name, title: x.title, link: x.url, date: x.date || null }));
}
