// 공개 시세 조회(키 없음): 국내 종목·지수는 NAVER, 해외·환율·원자재는 Yahoo Finance. 서버의 /fetch 중계를 거친다.
import { api, cached, fetchSite, store } from "../core.js";

const NAVER_REF = "https://m.stock.naver.com/";
const KR = /^[A-Z0-9]{6}\.(KS|KQ)$/;

export const isKorean = (sym) => KR.test(sym) || sym === "^KS11" || sym === "^KQ11";

async function json(url) {
  const { text } = await fetchSite(url, { referer: NAVER_REF });
  return JSON.parse(text);
}
const num = (t) => Number(String(t ?? "").replace(/,/g, "").trim());

function checked(raw) {
  const s = String(raw || "").toUpperCase();
  if (!/^[A-Z0-9^.=\-]{1,32}$/.test(s)) throw new Error("잘못된 종목 코드");
  return s;
}

function naverPath(sym) {
  if (sym === "^KS11") return "index/KOSPI";
  if (sym === "^KQ11") return "index/KOSDAQ";
  if (KR.test(sym)) return "item/" + sym.slice(0, 6);
  return null;
}

async function yahooChart(sym, range, interval = "1d") {
  let last;
  for (const host of ["query1.finance.yahoo.com", "query2.finance.yahoo.com"]) {
    try {
      const root = await json(`https://${host}/v8/finance/chart/${encodeURIComponent(sym)}?range=${range}&interval=${interval}`);
      const r = root?.chart?.result?.[0];
      if (!r) throw new Error("해당 종목 시세가 없습니다");
      return r;
    } catch (e) {
      last = e;
    }
  }
  throw last || new Error("시세 연결 실패");
}

async function naverQuote(url) {
  const o = await json(url);
  const price = num(o.closePrice);
  const code = o.compareToPreviousPrice?.code || "";
  let change = num(o.compareToPreviousClosePrice);
  if (code === "4" || code === "5") change = -Math.abs(change);
  else if (code === "1" || code === "2") change = Math.abs(change);
  else if (code === "3") change = 0;
  const previous = price - change;
  if (!(price > 0) || !(previous > 0)) throw new Error("가격 없음");
  return { price, previous, market: o.marketStatus || "", time: o.localTradedAt || "", at: Date.parse(o.localTradedAt || "") || 0, source: "NAVER" };
}

async function yahooQuote(sym) {
  const chart = await yahooChart(sym, "5d");
  const meta = chart.meta || {};
  const price = meta.regularMarketPrice;
  if (!(price > 0)) throw new Error("유효한 가격 없음");
  let previous = sym.startsWith("^") || sym.endsWith("=X") ? NaN : meta.previousClose ?? NaN;
  if (!Number.isFinite(previous)) {
    // 지수·환율은 일봉에서 전일 종가를 찾는다(5일 전 기준이 되지 않도록).
    const tz = meta.exchangeTimezoneName || "America/New_York";
    const day = (t) => new Date(t * 1000).toLocaleDateString("en-CA", { timeZone: tz });
    const latest = day(meta.regularMarketTime || 0);
    const ts = chart.timestamp || [];
    const closes = chart.indicators?.quote?.[0]?.close || [];
    ts.forEach((t, i) => {
      if (day(t) < latest && closes[i] > 0) previous = closes[i];
    });
  }
  return { price, previous: Number.isFinite(previous) ? previous : null, market: meta.exchangeName || "", time: "", at: (meta.regularMarketTime || 0) * 1000, source: "Yahoo Finance" };
}

/** 현재가 재사용 시간(서버 캐시도 3초라 이보다 짧게 두어도 더 새로워지지 않는다). */
const QUOTE_TTL = 3_000;
/** 시세 기준 시각이 이 시간보다 오래되면 ‘지연’으로 표시한다(장중·24시간 지표에서만 쓴다). */
export const STALE_MS = 20 * 60_000;

/** 현재가와 전일 종가. at=시세 기준 시각(ms), source=출처. */
export function quote(raw) {
  const sym = checked(raw);
  return cached("q:" + sym, QUOTE_TTL, async () => {
    if (sym === "^KS11" || sym === "^KQ11") {
      return naverQuote(`https://m.stock.naver.com/api/index/${sym === "^KS11" ? "KOSPI" : "KOSDAQ"}/basic`).catch(() => yahooQuote(sym));
    }
    if (KR.test(sym)) return naverQuote(`https://m.stock.naver.com/api/stock/${sym.slice(0, 6)}/basic`).catch(() => yahooQuote(sym));
    return yahooQuote(sym);
  });
}

export const changeOf = (q) => (q?.previous > 0 ? q.price - q.previous : null);
export const percentOf = (q) => (q?.previous > 0 ? ((q.price - q.previous) / q.previous) * 100 : null);

async function naverHistory(code) {
  const { text } = await fetchSite(`https://fchart.stock.naver.com/sise.nhn?symbol=${code}&timeframe=day&count=260&requestType=0`, { referer: NAVER_REF });
  const cutoff = new Date();
  cutoff.setFullYear(cutoff.getFullYear() - 1);
  const cut = cutoff.toISOString().slice(0, 10).replace(/-/g, "");
  const closes = [];
  const dates = [];
  let high = -Infinity;
  let low = Infinity;
  for (const m of text.matchAll(/<item data="([^"]+)"/g)) {
    const v = m[1].split("|");
    if (v.length < 5 || v[0] < cut) continue;
    const c = num(v[4]);
    if (!(c > 0)) continue;
    high = Math.max(high, num(v[2]));
    low = Math.min(low, num(v[3]));
    closes.push(c);
    dates.push(v[0]);
  }
  if (closes.length < 2) throw new Error("차트 자료 없음");
  return { closes, dates, high, low };
}

async function yahooHistory(sym) {
  const chart = await yahooChart(sym, "1y");
  const q = chart.indicators?.quote?.[0] || {};
  const ts = chart.timestamp || [];
  const tz = chart.meta?.exchangeTimezoneName || "UTC";
  const closes = [];
  const dates = [];
  let high = -Infinity;
  let low = Infinity;
  (q.close || []).forEach((c, i) => {
    if (!Number.isFinite(c)) return;
    if (Number.isFinite(q.high?.[i])) high = Math.max(high, q.high[i]);
    if (Number.isFinite(q.low?.[i])) low = Math.min(low, q.low[i]);
    closes.push(c);
    dates.push(new Date(ts[i] * 1000).toLocaleDateString("en-CA", { timeZone: tz }).replace(/-/g, ""));
  });
  if (closes.length < 2) throw new Error("1년 차트 자료가 부족합니다");
  return { closes, dates, high, low };
}

/** 최근 1년 일별 종가(dates는 yyyyMMdd)와 52주 최고·최저. */
export function history(raw) {
  const sym = checked(raw);
  return cached("h:" + sym, 3_600_000, () =>
    KR.test(sym) ? naverHistory(sym.slice(0, 6)).catch(() => yahooHistory(sym)) : yahooHistory(sym).catch((e) => {
      throw e;
    }));
}

const kstDay = (d = new Date()) => d.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" }).replace(/-/g, "");

/**
 * 당일(장이 끝났으면 가장 최근 거래일) 흐름: { points, previous, prevPoints, status, bars, live }.
 * 국내는 NAVER 1분봉, 해외는 Yahoo 1분봉(24시간 지표는 5분봉)으로 마지막 두 거래일을 묶는다.
 */
export function intraday(raw, fallbackPrevious, price) {
  const sym = checked(raw);
  return cached("i:" + sym, 20_000, async () => {
    const path = naverPath(sym);
    const h24 = sym.endsWith("=X") || sym.endsWith("=F") || sym.endsWith("-USD");
    let days = [];
    let lastEpoch = 0;
    let bars;
    let kr = false;
    let tz = "Asia/Seoul";
    if (path) {
      kr = true;
      bars = 391;
      for (const span of [4, 12]) {
        const from = new Date(Date.now() - span * 86_400_000);
        const rows = await json(`https://api.stock.naver.com/chart/domestic/${path}/minute?startDateTime=${kstDay(from)}0900&endDateTime=${kstDay()}1600`);
        const byDay = new Map();
        let lastKey = "";
        for (const r of rows || []) {
          const dt = String(r.localDateTime || "");
          const hhmm = Number(dt.slice(8, 12));
          const c = Number(r.currentPrice);
          if (dt.length < 12 || hhmm < 900 || hhmm > 1530 || !(c > 0)) continue;
          const k = dt.slice(0, 8);
          if (!byDay.has(k)) byDay.set(k, { date: k, closes: [], open: Number(r.openPrice) > 0 ? Number(r.openPrice) : null });
          byDay.get(k).closes.push(c);
          lastKey = dt;
        }
        days = [...byDay.values()].filter((d) => d.closes.length >= 2).sort((a, b) => a.date.localeCompare(b.date));
        if (days.length >= 2) {
          const iso = `${lastKey.slice(0, 4)}-${lastKey.slice(4, 6)}-${lastKey.slice(6, 8)}T${lastKey.slice(8, 10)}:${lastKey.slice(10, 12)}:00+09:00`;
          lastEpoch = Date.parse(iso) / 1000;
          break;
        }
      }
    }
    if (days.length < 2) {
      kr = false;
      const chart = await yahooChart(sym, h24 ? "3d" : "5d", h24 ? "5m" : "1m");
      tz = chart.meta?.exchangeTimezoneName || "UTC";
      bars = h24 ? 288 : 390;
      const ts = chart.timestamp || [];
      const q = chart.indicators?.quote?.[0] || {};
      const byDay = new Map();
      ts.forEach((t, i) => {
        const c = q.close?.[i];
        if (!(c > 0)) return;
        const k = new Date(t * 1000).toLocaleDateString("en-CA", { timeZone: tz }).replace(/-/g, "");
        if (!byDay.has(k)) byDay.set(k, { date: k, closes: [], open: q.open?.[i] > 0 ? q.open[i] : null });
        byDay.get(k).closes.push(c);
        lastEpoch = t;
      });
      days = [...byDay.values()].filter((d) => d.closes.length >= 2).sort((a, b) => a.date.localeCompare(b.date));
    }
    if (days.length < 2) throw new Error("분봉 자료 부족");
    const last = days[days.length - 1];
    const prev = days[days.length - 2];
    const now = Date.now() / 1000;
    const today = new Date().toLocaleDateString("en-CA", { timeZone: tz }).replace(/-/g, "");
    const live = h24 || (last.date === today && now - lastEpoch <= 15 * 60);
    const points = last.closes.slice();
    if (live && price > 0) points[points.length - 1] = price;
    const md = (d) => `${Number(d.slice(4, 6))}/${Number(d.slice(6, 8))}`;
    const local = new Date(new Date().toLocaleString("en-US", { timeZone: tz }));
    const weekday = local.getDay() >= 1 && local.getDay() <= 5;
    const early = local.getHours() * 100 + local.getMinutes() < 910;
    const status = h24 ? null : live ? "장중" : last.date === today ? "마감" : kr && weekday && early ? "개장 전" : kr ? `휴장 · ${md(last.date)} 마감` : `마감 ${md(last.date)}(미국)`;
    const previous = last.date === today || live ? fallbackPrevious ?? prev.closes[prev.closes.length - 1] : prev.closes[prev.closes.length - 1];
    return { points, previous, prevPoints: prev.closes, open: last.open ?? points[0], status, bars, live };
  });
}

/** 코스피·코스닥 투자자별 순매수(억원). */
export function investors(sym) {
  return cached("inv:" + sym, 30_000, async () => {
    const o = await json(`https://m.stock.naver.com/api/index/${sym === "^KS11" ? "KOSPI" : "KOSDAQ"}/trend`);
    return { bizdate: o.bizdate || "", personal: num(o.personalValue), foreign: num(o.foreignValue), institutional: num(o.institutionalValue) };
  });
}

/** 최근 9년 연말 종가(PER 계산용). */
export function yearEndCloses(code) {
  return cached("ye:" + code, 3_600_000, async () => {
    const out = {};
    try {
      const { text } = await fetchSite(`https://fchart.stock.naver.com/sise.nhn?symbol=${code}&timeframe=year&count=9&requestType=0`, { referer: NAVER_REF });
      for (const m of text.matchAll(/<item data="([^"]+)"/g)) {
        const v = m[1].split("|");
        const c = num(v[4]);
        if (v.length >= 5 && c > 0) out[Number(v[0].slice(0, 4))] = c;
      }
    } catch {
      /* 시세가 없으면 PER을 비운다 */
    }
    return out;
  });
}

export const POPULAR = [
  ["005930.KS", "삼성전자", "KRW", "EQUITY"], ["000660.KS", "SK하이닉스", "KRW", "EQUITY"], ["035420.KS", "NAVER", "KRW", "EQUITY"],
  ["035720.KS", "카카오", "KRW", "EQUITY"], ["005380.KS", "현대차", "KRW", "EQUITY"], ["069500.KS", "KODEX 200", "KRW", "ETF"],
  ["360750.KS", "TIGER 미국S&P500", "KRW", "ETF"], ["133690.KS", "TIGER 미국나스닥100", "KRW", "ETF"],
  ["^KS11", "코스피", "KRW", "INDEX"], ["^KQ11", "코스닥", "KRW", "INDEX"],
  ["AAPL", "애플 · Apple", "USD", "EQUITY"], ["MSFT", "마이크로소프트 · Microsoft", "USD", "EQUITY"], ["NVDA", "엔비디아 · NVIDIA", "USD", "EQUITY"],
  ["TSLA", "테슬라 · Tesla", "USD", "EQUITY"], ["MU", "마이크론 · Micron", "USD", "EQUITY"], ["VOO", "Vanguard S&P 500 ETF", "USD", "ETF"],
  ["SPY", "SPDR S&P 500 ETF", "USD", "ETF"], ["QQQ", "Invesco QQQ ETF", "USD", "ETF"], ["SOXX", "iShares 반도체 ETF", "USD", "ETF"],
  ["^IXIC", "나스닥 종합지수", "USD", "INDEX"], ["^GSPC", "S&P 500 지수", "USD", "INDEX"], ["^DJI", "다우존스 지수", "USD", "INDEX"],
  ["^NDX", "나스닥 100 지수", "USD", "INDEX"], ["^SOX", "필라델피아 반도체 지수", "USD", "INDEX"], ["^VIX", "VIX 변동성 지수", "USD", "INDEX"],
].map(([symbol, name, currency, type]) => ({ symbol, name, currency, type }));

const ALIASES = {
  "^IXIC": "nasdaq composite ixic 나스닥 나스닥종합 나스닥지수", "^GSPC": "s&p 500 sp500 snp 에스앤피 gspc",
  "^DJI": "dow jones djia 다우 다우지수", "^NDX": "nasdaq 100 nasdaq100 나스닥100 ndx",
  "^SOX": "philadelphia semiconductor sox 필라델피아 반도체지수", "^VIX": "volatility vix 변동성 공포지수",
  "^KS11": "kospi 코스피", "^KQ11": "kosdaq 코스닥",
};
const norm = (s) => String(s || "").replace(/\s+/g, "").toLowerCase();
const rank = (name, key) => { const n = norm(name); return n === key ? 0 : n.startsWith(key) ? 1 : 2; };
const US_EX = new Set(["NMS", "NYQ", "NGM", "NCM", "ASE", "PCX", "BTS", "NASDAQ", "NYSE", "NYSEArca"]);

function collectDomestic(v, out) {
  if (Array.isArray(v)) return v.forEach((x) => collectDomestic(x, out));
  if (!v || typeof v !== "object") return;
  const code = String(v.code || "");
  const name = String(v.name || "");
  const reuters = String(v.reutersCode || "");
  const isStock = String(v.url || "").includes("/domestic/stock/") || KR.test(reuters);
  if (/^[A-Z0-9]{6}$/.test(code) && name && isStock) {
    const symbol = KR.test(reuters) ? reuters : code + (String(v.typeName || "").includes("코스닥") ? ".KQ" : ".KS");
    if (!out.has(symbol)) out.set(symbol, { symbol, name, currency: "KRW", type: "EQUITY" });
    return;
  }
  Object.values(v).forEach((x) => collectDomestic(x, out));
}

/** NAVER 국내 ETF 전체 목록(하루 캐시). 자동완성은 '반도체'처럼 일반 단어로는 ETF를 못 찾아서 앱처럼 이름 포함 검색에 쓴다. */
function etfCatalog() {
  return cached("etf-catalog", 86_400_000, async () => {
    const { text } = await fetchSite("https://finance.naver.com/api/sise/etfItemList.nhn", { referer: NAVER_REF });
    const items = JSON.parse(text)?.result?.etfItemList || [];
    const list = items
      .map((i) => ({ code: String(i.itemcode || "").toUpperCase(), name: String(i.itemname || "") }))
      .filter((i) => /^[A-Z0-9]{6}$/.test(i.code) && i.name)
      .map((i) => ({ symbol: i.code + ".KS", name: i.name, currency: "KRW", type: "ETF" }));
    if (!list.length) throw new Error("ETF 목록 없음");
    return list;
  });
}

/** 이름·코드로 국내/미국 주식·ETF·지수를 찾는다. */
export async function search(query) {
  const key = norm(query);
  const out = new Map();
  POPULAR.filter((p) => norm(p.name).includes(key) || norm(p.symbol).includes(key) || norm(ALIASES[p.symbol]).includes(key)).forEach((p) => out.set(p.symbol, p));
  let last = null;
  await etfCatalog()
    .then((list) => {
      list
        .filter((e) => norm(e.name).includes(key) || norm(e.symbol).includes(key))
        .sort((a, b) => rank(a.name, key) - rank(b.name, key))
        .forEach((e) => !out.has(e.symbol) && out.set(e.symbol, e));
    })
    .catch((e) => (last = e));
  await json(`https://m.stock.naver.com/front-api/search/autoComplete?query=${encodeURIComponent(query)}&target=stock,index`)
    .then((o) => collectDomestic(o, out))
    .catch((e) => (last = e));
  if (!(out.size && /[가-힣]/.test(query))) {
    await json(`https://query1.finance.yahoo.com/v1/finance/search?q=${encodeURIComponent(query)}&quotesCount=20&newsCount=0`)
      .then((o) => {
        for (const v of o.quotes || []) {
          const kind = v.quoteType;
          const sym = v.symbol || "";
          const korean = isKorean(sym);
          const us = US_EX.has(v.exchange) || (kind === "INDEX" && /^\^[A-Z0-9]{2,8}$/.test(sym));
          if ((korean || us) && ["EQUITY", "ETF", "INDEX"].includes(kind) && !out.has(sym)) {
            out.set(sym, { symbol: sym, name: v.shortname || v.longname || sym, currency: korean ? "KRW" : "USD", type: kind });
          }
        }
      })
      .catch((e) => (last = e));
  }
  if (!out.size && last) throw new Error("검색 연결 실패 · 종목 코드로 다시 검색해 주세요");
  return [...out.values()];
}

/**
 * 작은 꺾은선(SVG). values가 2개 미만이면 빈 상자. baseline이 있으면 점선 기준선(전일 종가).
 * prev(전 거래일 분봉)가 있으면 앱처럼 왼쪽 40%에 전일(회색)·세로 구분선·전일 종가→오늘 시가 연결선(갭 방향 색)·오른쪽에 오늘을 그린다.
 * bars가 있으면(장중) 오늘 선은 거래 시간 비율만큼만 채운다.
 */
export function sparkline(values, { width = 300, height = 64, color, baseline, prev, open, bars } = {}) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("class", "spark");
  if (!values || values.length < 2) return svg;
  const stroke = color || "#1f6feb";
  const compare = Array.isArray(prev) && prev.length >= 2;
  const opening = compare && open > 0 ? open : null;
  const all = values.concat(baseline > 0 ? [baseline] : [], compare ? prev : [], opening ? [opening] : []);
  let lo = Infinity;
  let hi = -Infinity;
  for (const v of all) {
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  const span = hi - lo || 1;
  const y = (v) => height - ((v - lo) / span) * height * 0.86 - height * 0.07;
  const el = (tag, attrs) => {
    const n = document.createElementNS(ns, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
    n.setAttribute("vector-effect", "non-scaling-stroke");
    svg.append(n);
    return n;
  };
  const poly = (pts, s, w) => el("polyline", { points: pts.map(([px, py]) => `${px.toFixed(1)},${py.toFixed(1)}`).join(" "), fill: "none", stroke: s, "stroke-width": w, "stroke-linejoin": "round", "stroke-linecap": "round" });
  const hline = (v, s, opacity) => el("line", { x1: 0, x2: width, y1: y(v), y2: y(v), stroke: s, "stroke-opacity": opacity, "stroke-dasharray": "3 3" });
  // 화면 비율이 늘어나도 둥근 점으로 보이도록 길이 0인 선에 둥근 끝을 쓴다.
  const dot = (px, py) => el("path", { d: `M${px.toFixed(1)},${py.toFixed(1)}h0`, stroke, "stroke-width": 5, "stroke-linecap": "round", fill: "none" });

  if (compare) {
    const mid = width * 0.4;
    const pEnd = mid - width * 0.01;
    const oStart = mid + width * 0.01;
    const frac = bars > 1 ? Math.min(1, Math.max(0.02, (values.length - 1) / (bars - 1))) : 1;
    const endX = oStart + (width * 0.985 - oStart) * frac;
    if (baseline > 0) hline(baseline, stroke, 0.55);
    poly(prev.map((v, i) => [(i / (prev.length - 1)) * pEnd, y(v)]), "#9aa4b5", 1.2);
    el("line", { x1: mid, x2: mid, y1: 0, y2: height, stroke: "#9aa4b5", "stroke-opacity": 0.7, "stroke-dasharray": "2 3" });
    const ref = baseline > 0 ? baseline : prev[prev.length - 1];
    const first = opening ?? values[0];
    poly([[pEnd, y(prev[prev.length - 1])], [oStart, y(first)]], first > ref ? UP : first < ref ? DOWN : "var(--muted)", 2);
    const pts = values.map((v, i) => [oStart + ((endX - oStart) * i) / (values.length - 1), y(v)]);
    poly(pts, stroke, 1.6);
    dot(...pts[pts.length - 1]);
    return svg;
  }
  if (baseline > 0) hline(baseline, "#9aa4b5", 1);
  const total = Math.max(bars || 0, values.length);
  const pts = values.map((v, i) => [(i / ((bars ? total : values.length) - 1)) * width, y(v)]);
  poly(pts, stroke, 1.8);
  return svg;
}

export const UP = "var(--up)";
export const DOWN = "var(--down)";
export const tint = (change) => (change > 0 ? UP : change < 0 ? DOWN : "var(--muted)");
export const signed = (v, digits = 0) => (v > 0 ? "▲" : v < 0 ? "▼" : "") + Math.abs(v).toLocaleString("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits });

// ---- 지표·종목 목록(앱과 같음) ----
const ins = (symbol, name, currency, type) => ({ symbol, name, currency, type });
export const KOREA_INDEXES = [ins("^KS11", "KOSPI", "KRW", "INDEX"), ins("^KQ11", "KOSDAQ", "KRW", "INDEX")];
export const PANEL_CATALOG = [
  ins("^GSPC", "S&P 500", "USD", "INDEX"), ins("^IXIC", "Nasdaq", "USD", "INDEX"), ins("KRW=X", "USD/KRW", "KRW", "FX"), ins("CL=F", "WTI", "USD", "EQUITY"),
  ins("^TNX", "US 10Y", "USD", "RATE"), ins("GC=F", "Gold", "USD", "EQUITY"),
  ins("^DJI", "Dow Jones", "USD", "INDEX"), ins("^SOX", "Philadelphia Semiconductor", "USD", "INDEX"), ins("^NDX", "Nasdaq 100", "USD", "INDEX"),
  ins("^RUT", "Russell 2000", "USD", "INDEX"), ins("^VIX", "VIX", "USD", "INDEX"),
  ins("^N225", "Nikkei 225", "USD", "INDEX"), ins("^HSI", "Hang Seng", "USD", "INDEX"), ins("000001.SS", "Shanghai Composite", "USD", "INDEX"),
  ins("^TWII", "Taiwan Weighted", "USD", "INDEX"), ins("^STOXX50E", "Euro Stoxx 50", "USD", "INDEX"), ins("^GDAXI", "DAX", "USD", "INDEX"), ins("^FTSE", "FTSE 100", "USD", "INDEX"),
  ins("DX-Y.NYB", "Dollar Index", "USD", "FX"), ins("JPY=X", "USD/JPY", "USD", "FX"), ins("EURKRW=X", "EUR/KRW", "KRW", "FX"), ins("CNYKRW=X", "CNY/KRW", "KRW", "FX"),
  ins("^FVX", "US 5Y", "USD", "RATE"), ins("^TYX", "US 30Y", "USD", "RATE"),
  ins("BZ=F", "Brent", "USD", "EQUITY"), ins("SI=F", "Silver", "USD", "EQUITY"), ins("HG=F", "Copper", "USD", "EQUITY"), ins("NG=F", "Natural Gas", "USD", "EQUITY"),
  ins("BTC-USD", "Bitcoin", "USD", "EQUITY"), ins("ETH-USD", "Ethereum", "USD", "EQUITY"),
];
export const INDEX_CHOICES = [...KOREA_INDEXES, ...PANEL_CATALOG];
const DEFAULT_MARKET_INDEXES = [...KOREA_INDEXES, ...PANEL_CATALOG.slice(0, 6)].map((x) => x.symbol);
const BRIEFING_EXCLUDED = ["^GSPC", "CL=F", "^TNX", "^SOX"];
export const DEFAULT_WATCH = [ins("005930.KS", "삼성전자", "KRW", "EQUITY"), ins("000660.KS", "SK하이닉스", "KRW", "EQUITY"), ins("069500.KS", "KODEX 200", "KRW", "ETF")];

/** 증시 지표 선택 { market:[...], briefing:[...] } (이 브라우저에 저장). */
export function indexSelection() {
  const market = store.get("market.indexes", DEFAULT_MARKET_INDEXES);
  const briefing = store.get("briefing.indexes", market.filter((s) => !BRIEFING_EXCLUDED.includes(s)));
  return { market, briefing };
}
export function saveIndexSelection(sel) {
  store.set("market.indexes", sel.market);
  store.set("briefing.indexes", sel.briefing);
}

const grp = (v, d) => Number(v).toLocaleString("ko-KR", { minimumFractionDigits: d, maximumFractionDigits: d });
const changeDigits = (it) => (it.type === "INDEX" || it.currency === "USD" || it.type === "FX" ? 2 : 0);
export function priceText(it, v) {
  if (it.type === "FX") return grp(v, 2) + (it.currency === "KRW" ? "원" : "");
  if (it.type === "RATE") return grp(v, 3) + "%";
  if (it.type === "INDEX") return grp(v, 2);
  if (it.currency === "USD") return "$" + grp(v, 2);
  return grp(v, 0) + "원";
}
const sgn = (v, d, suffix = "") => (v > 0 ? "▲" : v < 0 ? "▼" : "") + grp(Math.abs(v), d) + suffix;
/** "▼92.30 (▼1.30%)" */
export function deltaText(it, q) {
  const ch = changeOf(q);
  return ch == null ? null : `${sgn(ch, changeDigits(it))} (${sgn(percentOf(q) ?? 0, 2, "%")})`;
}

// ---- 국내 금융 통계(한국은행 ECOS, 서버 /stats) ----
const sd = (id, name, group) => ({ id, name, group });
export const STAT_CATALOG = [
  sd("base-rate", "한국은행 기준금리", "금리·채권"), sd("call-1d", "콜금리(1일)", "금리·채권"), sd("cd-91", "CD 91일", "금리·채권"), sd("cp-91", "CP 91일", "금리·채권"),
  sd("ktb-1y", "국고채 1년", "금리·채권"), sd("ktb-2y", "국고채 2년", "금리·채권"), sd("ktb-3y", "국고채 3년", "금리·채권"), sd("ktb-5y", "국고채 5년", "금리·채권"),
  sd("ktb-10y", "국고채 10년", "금리·채권"), sd("ktb-20y", "국고채 20년", "금리·채권"), sd("ktb-30y", "국고채 30년", "금리·채권"),
  sd("corp-aa", "회사채 3년 AA-", "금리·채권"), sd("corp-bbb", "회사채 3년 BBB-", "금리·채권"), sd("loan-rate", "예금은행 대출금리", "금리·채권"),
  sd("mortgage-rate", "예금은행 주택담보대출금리", "금리·채권"),
  sd("household-credit", "가계신용 잔액", "가계·은행 건전성"), sd("household-credit-loan", "가계대출 잔액(분기)", "가계·은행 건전성"),
  sd("bank-household-loan", "은행 가계대출 잔액", "가계·은행 건전성"), sd("deposit-household-loan", "예금취급기관 가계대출", "가계·은행 건전성"),
  sd("bank-mortgage", "은행 주택관련대출", "가계·은행 건전성"), sd("bank-delinquency", "은행 가계대출 연체율", "가계·은행 건전성"),
  sd("bank-delinquency-corp", "은행 기업대출 연체율", "가계·은행 건전성"), sd("fx-reserves", "외환보유액", "대외"),
];
const DEFAULT_MARKET_STATS = ["base-rate", "ktb-3y", "ktb-10y", "cd-91", "corp-aa", "household-credit", "bank-household-loan", "bank-delinquency", "loan-rate", "fx-reserves"];
const DEFAULT_BRIEFING_STATS = ["ktb-3y", "corp-aa"];
export function statSelection() {
  return { market: store.get("market.stats", DEFAULT_MARKET_STATS), briefing: store.get("briefing.stats", DEFAULT_BRIEFING_STATS) };
}
export function saveStatSelection(sel) {
  store.set("market.stats", sel.market);
  store.set("briefing.stats", sel.briefing);
}
/** { items:[{id,name,unit,value,previous,period}], missing, reasons } */
export function loadStats(ids) {
  if (!ids.length) return Promise.resolve({ items: [], missing: [], reasons: {} });
  return cached("stats:" + ids.join(","), 600_000, async () => {
    const r = await api("/stats", { ids: ids.join(",") });
    return { items: (r.items || []).filter((x) => Number.isFinite(x.value)), missing: r.missing || [], reasons: r.reasons || {} };
  });
}
export const groupOf = (id) => STAT_CATALOG.find((s) => s.id === id)?.group || "기타";
export function periodLabel(p) {
  if (/^\d{8}$/.test(p)) return `${p.slice(4, 6)}/${p.slice(6)}`;
  if (/^\d{6}$/.test(p)) return `${p.slice(0, 4)}.${p.slice(4)}`;
  if (/^\d{4}Q[1-4]$/.test(p)) return `${p.slice(0, 4)} ${p.slice(-1)}분기`;
  return p || "";
}
export function statText(it, v) {
  const rate = it.unit === "%";
  const d = Number.isInteger(it.digits) ? it.digits : rate ? 2 : it.unit === "조원" ? 1 : Math.abs(v) >= 1000 ? 0 : 1;
  return grp(v, d) + (rate ? "%" : it.unit ? " " + it.unit : "");
}
export function statDelta(it) {
  if (it.previous == null) return null;
  const ch = it.value - it.previous;
  const rate = it.unit === "%";
  const d = Number.isInteger(it.digits) ? it.digits : rate ? 2 : it.unit === "조원" ? 2 : Math.abs(it.value) >= 1000 ? 0 : 1;
  const pc = !rate && it.previous ? ` (${sgn(((it.value - it.previous) / Math.abs(it.previous)) * 100, 2, "%")})` : "";
  return sgn(ch, d) + (rate ? "%p" : "") + pc;
}

// ---- 거시경제 지표(한국은행 ECOS, 서버 /stats) ----
// 기본 4개(실질GDP 성장률·소비자물가 상승률·실업률·경상수지)와 추가로 고를 수 있는 지표. 서버(Worker)의 STAT_SPECS와 id가 같아야 한다.
export const MACRO_CATALOG = [
  sd("gdp-growth", "실질GDP 성장률(전기 대비)", "성장·소득"), sd("consumption-growth", "민간소비 증감률(전기 대비)", "성장·소득"), sd("facility-growth", "설비투자 증감률(전기 대비)", "성장·소득"), sd("gni-growth", "실질 GNI 증감률(전기 대비)", "성장·소득"),
  sd("cpi-yoy", "소비자물가 상승률(전년동월비)", "물가"), sd("core-cpi-yoy", "근원물가 상승률(농산물·석유류 제외)", "물가"), sd("living-cpi-yoy", "생활물가 상승률(전년동월비)", "물가"), sd("ppi-yoy", "생산자물가 상승률(전년동월비)", "물가"),
  sd("unemployment", "실업률", "고용·인구"), sd("employment-rate", "고용률", "고용·인구"), sd("employed", "취업자 수", "고용·인구"), sd("labor-participation", "경제활동참가율", "고용·인구"),
  sd("production-yoy", "전산업생산 증감률(전년동월비)", "생산·투자·소비"), sd("retail-yoy", "소매판매 증감률(불변, 전년동월비)", "생산·투자·소비"), sd("facility-index-yoy", "설비투자지수 증감률(전년동월비)", "생산·투자·소비"), sd("utilization", "제조업 평균가동률", "생산·투자·소비"),
  sd("ccsi", "소비자심리지수", "경기·심리"), sd("esi", "경제심리지수", "경기·심리"), sd("bsi", "기업경기실사지수(전산업 업황실적)", "경기·심리"), sd("leading-index", "선행지수 순환변동치", "경기·심리"),
  sd("current-account", "경상수지", "대외"), sd("exports-sa", "상품수출(계절조정)", "대외"), sd("imports-sa", "상품수입(FOB)", "대외"), sd("usdkrw", "원/달러 환율(매매기준율)", "대외"),
];
export const DEFAULT_MACRO = ["gdp-growth", "cpi-yoy", "unemployment", "current-account"];
export const macroSelection = () => store.get("market.macro", DEFAULT_MACRO);
export const saveMacroSelection = (ids) => store.set("market.macro", ids);

// ---- 자본시장 통계(금융투자협회 FreeSIS, 키 없음) ----
const KOFIA_LISTS = { 펀드: ["fundList1", "fundList2", "fundList3", "fundList4", "fundList5"], 자금동향: ["fundTrendList1", "fundTrendList2", "fundTrendList3"] };
const knum = (v) => (typeof v === "number" ? v : Number.isFinite(Number(String(v ?? "").replace(/,/g, "").trim())) && String(v ?? "").trim() ? Number(String(v).replace(/,/g, "")) : null);
export function loadKofia() {
  return cached("kofia", 600_000, async () => {
    const { text } = await fetchSite("https://freesis.kofia.or.kr/alootStatsAll/STATTOT0600000900BO.do", {
      method: "POST",
      contentType: "application/json; charset=UTF-8",
      body: JSON.stringify({ data: { userId: "GUEST", searchLog: "메인", serviceId: "STATTOT0600000900" } }),
      referer: "https://freesis.kofia.or.kr/stat/FreeSIS.do?parentDivId=MSIS02000000000000&serviceId=MSIS02010000000000",
    });
    const root = JSON.parse(text);
    const groups = [];
    for (const [title, lists] of Object.entries(KOFIA_LISTS)) {
      const sections = new Map();
      for (const ln of lists) {
        for (const r of root[ln] || []) {
          const parts = String(r.TMPV14 || "").split(">");
          const value = String(r.TMPV4 || "").trim();
          if (parts.length < 3 || !value) continue;
          const clean = (s) => s.replace(/\s*\n\s*/g, " ").trim();
          const st = parts[1].trim();
          if (!sections.has(st)) sections.set(st, { title: st, asOf: String(r.TMPV3 || "").trim(), unit: String(r.TMPV5 || "").trim(), items: [] });
          sections.get(st).items.push({ key: parts.map(clean).join(">"), name: clean(parts.slice(2).join(">")), value, change: knum(r.TMPV6), changeText: String(r.TMPV6 || "").trim() || "0" });
        }
      }
      if (sections.size) groups.push({ title, sections: [...sections.values()] });
    }
    if (!groups.length) throw new Error("금융투자협회 응답에 자료가 없습니다");
    return groups;
  });
}
