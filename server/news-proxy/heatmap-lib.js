// 코스피·코스닥 시가총액 히트맵 자료(/heatmap?market=KOSPI|KOSDAQ)와 ETF 순자산 히트맵 자료(/heatmap?market=ETF).
// NAVER 증권의 시가총액순 목록(1페이지 100종목)을 서버가 2페이지 읽어 상위 150개 일반 종목(ETF 제외)만 남긴 작은 JSON으로 돌려준다.
// 1분 캐시(인스턴스 메모리 → Cache API)라서 접속자가 많아도 NAVER 로 나가는 호출은 시장당 분당 2회 안팎이다. 화면은 이 JSON 한 번만 읽는다.
import { cacheControl, shared, snapOf, toResponse } from "./cache-lib.js";

const UPSTREAM = "https://m.stock.naver.com/api/stocks/marketValue/";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
export const TOP = 150;
const PAGES = [1, 2]; // 페이지당 100종목. 200을 넘기면 NAVER 가 응답을 끊는다.
const TTL_SEC = 60;
export const ETF_TOP = 100;
const ETF_UPSTREAM = "https://finance.naver.com/api/sise/etfItemList.nhn?etfType=0&targetColumn=market_sum&sortOrder=desc";

/** NAVER ETF 전체 목록(JSON, EUC-KR 해독 후) → 히트맵 자료. rows: [코드, 이름, 순자산총액(억원), 등락률(%), 분류(1~7)] 순자산 큰 순 상위 ETF_TOP개. total·count 는 전체 ETF 기준. */
export function buildEtfHeatmap(json, now = Date.now()) {
  const list = json?.result?.etfItemList;
  if (!Array.isArray(list)) return { market: "ETF", at: now, rows: [], total: 0, count: 0 };
  const rows = [];
  let total = 0;
  const seen = new Set();
  for (const e of list) {
    const cap = Number(e?.marketSum);
    const pct = Number(e?.changeRate);
    const cat = Number(e?.etfTabCode);
    if (!e?.itemcode || !e?.itemname || !(cap > 0) || !Number.isFinite(pct) || seen.has(e.itemcode)) continue;
    seen.add(e.itemcode);
    total += cap;
    rows.push([String(e.itemcode), String(e.itemname), Math.round(cap), pct, cat >= 1 && cat <= 7 ? cat : 7]);
  }
  rows.sort((a, b) => b[2] - a[2]);
  return { market: "ETF", at: now, rows: rows.slice(0, ETF_TOP), total: Math.round(total), count: rows.length };
}

async function etfJson() {
  let last;
  for (let i = 0; i < 3; i++) {
    try {
      const res = await fetch(ETF_UPSTREAM, { headers: { "User-Agent": UA, Accept: "application/json", Referer: "https://finance.naver.com/" } });
      if (!res.ok) throw new Error("NAVER " + res.status);
      const text = new TextDecoder("euc-kr").decode(await res.arrayBuffer()); // 응답이 EUC-KR 이다
      const json = JSON.parse(text);
      if (!Array.isArray(json?.result?.etfItemList) || !json.result.etfItemList.length) throw new Error("ETF 목록이 비었습니다");
      return json;
    } catch (e) {
      last = e; // 가끔 빈 응답이 와서 다시 시도한다
    }
  }
  throw last;
}

/** NAVER 응답 페이지들 → 히트맵 자료. rows: [종목코드, 종목명, 시가총액(억원), 등락률(%)] 시가총액 큰 순. */
export function buildHeatmap(pages, market, now = Date.now()) {
  const rows = [];
  let time = "";
  let status = "";
  for (const page of pages) {
    for (const s of page?.stocks || []) {
      if (s.stockEndType !== "stock") continue; // ETF·ETN 제외
      const cap = Number(String(s.marketValueRaw ?? "").replace(/,/g, "")) / 1e8;
      const pct = Number(String(s.fluctuationsRatio ?? "").replace(/,/g, ""));
      if (!s.itemCode || !s.stockName || !(cap > 0) || !Number.isFinite(pct)) continue;
      rows.push([String(s.itemCode), String(s.stockName), Math.round(cap), pct]);
      if (s.localTradedAt && s.localTradedAt > time) time = s.localTradedAt;
    }
    if (page?.marketStatus) status = page.marketStatus;
  }
  rows.sort((a, b) => b[2] - a[2]);
  const seen = new Set();
  const top = rows.filter((r) => !seen.has(r[0]) && seen.add(r[0])).slice(0, TOP);
  return { market, at: now, time, status, rows: top };
}

async function page(market, n) {
  let last;
  for (let i = 0; i < 2; i++) {
    try {
      const res = await fetch(`${UPSTREAM}${market}?page=${n}&pageSize=100`, { headers: { "User-Agent": UA, Accept: "application/json", Referer: "https://m.stock.naver.com/" } });
      if (!res.ok) throw new Error("NAVER " + res.status);
      return await res.json();
    } catch (e) {
      last = e;
    }
  }
  throw last;
}

/** GET /heatmap?market=KOSPI|KOSDAQ → 정리한 JSON(1분 캐시). 1페이지가 실패하면 오류, 2페이지만 실패하면 있는 만큼만(partial). */
export async function heatmap(url, ctx, now = Date.now()) {
  const q = url.searchParams.get("market");
  const market = q === "KOSDAQ" ? "KOSDAQ" : q === "ETF" ? "ETF" : "KOSPI";
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const key = new Request(`https://heatmap-cache.invalid/?m=${market}`);
  const snap = await shared(key.url, TTL_SEC * 1000, async () => {
    const hit = cache ? await cache.match(key) : null;
    if (hit) return snapOf(hit);
    let data;
    if (market === "ETF") {
      data = buildEtfHeatmap(await etfJson(), now); // 한 번 호출로 전체 ETF
    } else {
      const results = await Promise.allSettled(PAGES.map((n) => page(market, n)));
      if (results[0].status !== "fulfilled") throw results[0].reason;
      data = buildHeatmap(results.filter((r) => r.status === "fulfilled").map((r) => r.value), market, now);
      if (results.some((r) => r.status !== "fulfilled")) data.partial = true;
    }
    if (!data.rows.length) throw new Error("시총 자료가 비었습니다");
    const fresh = {
      status: 200,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": cacheControl(20) },
      body: new TextEncoder().encode(JSON.stringify(data)).buffer,
      volatile: !!data.partial, // 일부만 받았으면 기억하지 않고 곧 다시 시도한다
    };
    if (cache && !data.partial && ctx) ctx.waitUntil(cache.put(key, toResponse(fresh, { "cache-control": cacheControl(TTL_SEC) })));
    return fresh;
  });
  return toResponse(snap);
}
