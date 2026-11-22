// 공개 시세 조회(키 없음): 국내 종목·지수는 NAVER, 해외·환율·원자재는 Yahoo Finance. 서버의 /fetch 중계를 거친다.
import { api, cached, fetchSite, store } from "../core.js";

const NAVER_REF = "https://m.stock.naver.com/";
const KR = /^[A-Z0-9]{6}\.(KS|KQ)$/;

export const isKorean = (sym) => KR.test(sym) || sym === "^KS11" || sym === "^KQ11";

// bust: 중간 저장소(브라우저·통신망·서버 가장자리)가 붙잡은 옛 응답을 피하려고 요청 주소를 매번 다르게 만든다(Referer 값은 시세 제공처가 보지 않는다).
async function json(url, bust = false) {
  const { text } = await fetchSite(url, { referer: NAVER_REF + (bust ? "?b=" + Date.now().toString(36) : "") });
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

// 오래된 값이 내려온 종목은 잠시 동안 중간 저장소를 건너뛰고 받는다.
const bustUntil = new Map();
const bustTried = new Map();
const bustOn = (sym) => (bustUntil.get(sym) || 0) > Date.now();

async function yahooChart(sym, range, interval = "1d", bust = bustOn(sym)) {
  let last;
  for (const host of ["query1.finance.yahoo.com", "query2.finance.yahoo.com"]) {
    try {
      const root = await json(`https://${host}/v8/finance/chart/${encodeURIComponent(sym)}?range=${range}&interval=${interval}`, bust);
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
  let chart = await yahooChart(sym, "5d");
  // 기준 시각이 너무 오래됐으면 한 번 더, 저장소를 건너뛰고 받아 본다(장이 닫힌 종목에서 반복하지 않도록 1분에 한 번만).
  const at0 = (chart.meta?.regularMarketTime || 0) * 1000;
  if (at0 && Date.now() - at0 > STALE_MS && !bustOn(sym) && Date.now() - (bustTried.get(sym) || 0) > 60_000) {
    bustTried.set(sym, Date.now());
    try {
      const fresh = await yahooChart(sym, "5d", "1d", true);
      if ((fresh.meta?.regularMarketTime || 0) * 1000 > at0 + 60_000) {
        chart = fresh;
        bustUntil.set(sym, Date.now() + 5 * 60_000);
      }
    } catch {
      /* 원래 값을 그대로 쓴다 */
    }
  }
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

// 한국거래소가 평소(09:00~15:30)보다 1시간 늦게 열고 닫는 날(수능일, 매년 첫 거래일 등). 날짜(yyyymmdd) → 개장·종료 시각(hhmm).
// 수능일은 10:00~16:30(한국거래소 공지). 휴장일은 따로 목록이 없고 "오늘 분봉이 없음"으로 추정하므로, 지연개장일은 여기 등록해야 휴장으로 오판하지 않는다. 해마다 확인해 추가한다.
export const KRX_LATE_OPEN = {
  "20261119": { open: 1000, close: 1630 },
  "20270104": { open: 1000, close: 1630 },
  "20271118": { open: 1000, close: 1630 },
};

const kstDay = (d = new Date()) => d.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" }).replace(/-/g, "");

// 아시아 거래소 점심 휴장(현지시각 hhmm)과 하루 분봉 수. 도쿄 11:30~12:30, 홍콩 12:00~13:00, 상하이 11:30~13:00.
const ASIA_SESSIONS = {
  "Asia/Tokyo": { lunch: [1130, 1230], bars: 300 },
  "Asia/Hong_Kong": { lunch: [1200, 1300], bars: 330 },
  "Asia/Shanghai": { lunch: [1130, 1300], bars: 240 },
  "Asia/Taipei": { lunch: null, bars: 270 },
};

/**
 * 24시간 지표 중 주말·정비 시간에 거래가 멈추는 것(CME 선물 =F, 외환 =X)의 휴장 여부(미국 동부시간 기준).
 * 선물: 금 17:00 ~ 일 18:00 주말 마감, 월~목 17:00~18:00 정비 휴장. 외환: 금 17:00 ~ 일 17:00 주말 마감.
 * 반환: "weekend"(마감) | "break"(정비 휴장) | null(거래 중). 휴일 단축거래는 반영하지 않는다.
 */
export function marketClosure(sym, nowMs = Date.now()) {
  const fut = sym.endsWith("=F");
  if (!fut && !sym.endsWith("=X")) return null;
  const et = new Date(new Date(nowMs).toLocaleString("en-US", { timeZone: "America/New_York" }));
  const day = et.getDay(); // 0=일
  const hm = et.getHours() * 60 + et.getMinutes();
  const close = 17 * 60;
  const reopen = (fut ? 18 : 17) * 60;
  if (day === 6 || (day === 5 && hm >= close) || (day === 0 && hm < reopen)) return "weekend";
  if (fut && day >= 1 && day <= 4 && hm >= close && hm < reopen) return "break";
  return null;
}

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
    let dayShift = 0; // 거래일 경계를 달력 날짜에서 밀어 주는 초(선물용)
    let session = null; // Yahoo 정규장 시작·종료(초). 아시아 지수 장중 판정에 쓴다.
    if (path) {
      kr = true;
      bars = 391;
      for (const span of [4, 12]) {
        const from = new Date(Date.now() - span * 86_400_000);
        const rows = await json(`https://api.stock.naver.com/chart/domestic/${path}/minute?startDateTime=${kstDay(from)}0900&endDateTime=${kstDay()}1700`);
        const byDay = new Map();
        let lastKey = "";
        for (const r of rows || []) {
          const dt = String(r.localDateTime || "");
          const hhmm = Number(dt.slice(8, 12));
          const c = Number(r.currentPrice);
          if (dt.length < 12 || hhmm < 900 || hhmm > (KRX_LATE_OPEN[dt.slice(0, 8)]?.close ?? 1530) || !(c > 0)) continue;
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
      bars = h24 ? 288 : ASIA_SESSIONS[tz]?.bars || 390;
      const reg = chart.meta?.currentTradingPeriod?.regular;
      if (reg?.start && reg?.end) session = { start: reg.start, end: reg.end };
      // 선물(=F)은 미국 동부 18:00에 새 거래일이 시작한다(한국 아침 8시). 달력 날짜(자정)로 나누면 한국 저녁에도 오늘 선이 하루의 일부만 채워져 늦게 반영되는 것처럼 보이므로, 18:00 기준으로 거래일을 나눈다.
      if (sym.endsWith("=F") && tz.startsWith("America/")) dayShift = 6 * 3600;
      const ts = chart.timestamp || [];
      const q = chart.indicators?.quote?.[0] || {};
      const byDay = new Map();
      ts.forEach((t, i) => {
        const c = q.close?.[i];
        if (!(c > 0)) return;
        const k = new Date((t + dayShift) * 1000).toLocaleDateString("en-CA", { timeZone: tz }).replace(/-/g, "");
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
    const today = new Date(Date.now() + dayShift * 1000).toLocaleDateString("en-CA", { timeZone: tz }).replace(/-/g, "");
    const local = new Date(new Date().toLocaleString("en-US", { timeZone: tz }));
    const hhmm = local.getHours() * 100 + local.getMinutes();
    const weekday = local.getDay() >= 1 && local.getDay() <= 5;
    const lunch = ASIA_SESSIONS[tz]?.lunch;
    // 아시아·유럽 지수: Yahoo가 15~20분 지연되므로 "마지막 분봉이 15분 이내"로 보면 장중에도 마감으로 나온다. 거래소 정규장 시간(오늘 날짜 자료가 있을 때)으로 판정한다.
    const inSession = !!session && last.date === today && now >= session.start && now <= session.end + 600;
    const onLunch = !!lunch && inSession && hhmm >= lunch[0] && hhmm < lunch[1];
    const closure = h24 ? marketClosure(sym) : null; // 주말·정비 휴장이면 지연이 아니라 마감으로 표시한다
    const live = (h24 && !closure) || (session && !kr ? inSession : last.date === today && now - lastEpoch <= 15 * 60);
    const points = last.closes.slice();
    if (live && price > 0) points[points.length - 1] = price;
    const md = (d) => `${Number(d.slice(4, 6))}/${Number(d.slice(6, 8))}`;
    const early = hhmm < 910;
    let beforeOpen = false; // 아직 오늘 장이 열리지 않았거나 막 열려 첫 분봉이 없음(어제 자료)
    if (session && weekday) {
      const sl = new Date(new Date(session.start * 1000).toLocaleString("en-US", { timeZone: tz }));
      beforeOpen = hhmm < sl.getHours() * 100 + sl.getMinutes() + 20;
    }
    const lateOpen = kr && weekday ? KRX_LATE_OPEN[today]?.open ?? 0 : 0; // 오늘이 지연개장일이면 개장 시각(hhmm)
    const lateWait = lateOpen && last.date !== today && hhmm < lateOpen + 10; // 개장 10분 뒤까지 첫 분봉을 기다린다
    const status = closure === "weekend" ? `마감 ${md(last.date)}` : closure === "break" ? "휴장" : h24 ? null : onLunch ? "점심 휴장" : live ? "장중" : last.date === today ? "마감" : lateWait ? `지연개장 · ${String(Math.floor(lateOpen / 100)).padStart(2, "0")}:${String(lateOpen % 100).padStart(2, "0")} 개장` : kr && weekday && early ? "개장 전" : kr ? `휴장 · ${md(last.date)} 마감`
      : !session ? `마감 ${md(last.date)}(미국)`
      : beforeOpen ? "개장 전"
      : tz.startsWith("America/") ? `마감 ${md(last.date)}(미국)` : `휴장 · ${md(last.date)} 마감`;
    const previous = last.date === today || live ? fallbackPrevious ?? prev.closes[prev.closes.length - 1] : prev.closes[prev.closes.length - 1];
    return { points, previous, prevPoints: prev.closes, open: last.open ?? points[0], status, bars, live };
  });
}

/** 코스피·코스닥 투자자별 순매수(억원). 한 번 실패하면 잠시 뒤 한 번 더 읽고, 그래도 안 되면 마지막으로 읽은 값(같은 날 것)을 쓴다. */
const lastInv = new Map();
export function investors(sym) {
  return cached("inv:" + sym, 30_000, async () => {
    const url = `https://m.stock.naver.com/api/index/${sym === "^KS11" ? "KOSPI" : "KOSDAQ"}/trend`;
    let o;
    try {
      try {
        o = await json(url);
      } catch {
        await new Promise((r) => setTimeout(r, 1500));
        o = await json(url, true);
      }
    } catch (e) {
      const prev = lastInv.get(sym);
      if (prev) return prev;
      throw e;
    }
    const v = { bizdate: o.bizdate || "", personal: num(o.personalValue), foreign: num(o.foreignValue), institutional: num(o.institutionalValue) };
    lastInv.set(sym, v);
    return v;
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
  "NQ=F": "nasdaq 100 futures nq 나스닥100선물 나스닥선물 선물",
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
  svg.pos = (i) => ({ x: pts[i][0] / width, y: pts[i][1] / height });
  return svg;
}

const yDate = (d) => (d && d.length >= 8 ? `${d.slice(2, 4)}.${+d.slice(4, 6)}.${+d.slice(6, 8)}` : "");

/**
 * 1년 그래프: 꺾은선 위에 시작·최고·최저 가격을 해당 일자에 표시한다(종가 기준).
 * 선 위·아래 띠에 글자를 놓고, 서로 겹치면 반대쪽 띠나 옆으로 옮긴다. 화면에 붙은 뒤 실제 글자 폭을 재서 배치한다.
 */
export function yearChart(it, closes, dates, { color, price } = {}) {
  const wrap = document.createElement("div");
  wrap.className = "ych";
  const svg = sparkline(closes, { height: 40, color });
  wrap.append(svg);
  const n = closes.length;
  if (n < 2 || !svg.pos) return svg;
  let hi = 0;
  let lo = 0;
  closes.forEach((v, i) => {
    if (v > closes[hi]) hi = i;
    if (v < closes[lo]) lo = i;
  });
  const groups = new Map();
  const add = (i, name) => groups.set(i, [...(groups.get(i) || []), name]);
  if (closes[hi] !== closes[lo]) {
    add(hi, "최고");
    add(lo, "최저");
  }
  add(0, "시작");
  const items = [];
  for (const [i, names] of groups) {
    const p = svg.pos(i);
    const up = names.includes("최고");
    const down = names.includes("최저");
    const c = up ? UP : down ? DOWN : "var(--muted)";
    const dot = document.createElement("i");
    dot.className = "yd";
    dot.style.cssText = `left:${(p.x * 100).toFixed(2)}%;top:calc(22px + (100% - 44px) * ${p.y.toFixed(4)});background:${c}`;
    const lab = document.createElement("span");
    lab.className = "yl";
    lab.style.color = c;
    const b = document.createElement("b");
    const pv = closes[i] >= 1000 && it.type !== "RATE" && it.type !== "FX" ? Math.round(closes[i]).toLocaleString("ko-KR") : priceText(it, closes[i]).replace(/원$/, "");
    b.textContent = names.join("·") + " " + pv;
    const dt = document.createElement("span");
    dt.className = "ydt";
    dt.textContent = "(" + yDate(dates?.[i]) + ")";
    lab.append(b, dt);
    wrap.append(dot, lab);
    items.push({ lab, x: p.x, y: p.y, band: up ? "top" : down ? "bot" : p.y < 0.5 ? "top" : "bot", fixed: up || down });
  }
  requestAnimationFrame(() => {
    const W = wrap.clientWidth;
    const H = wrap.clientHeight;
    if (!W) {
      for (const t of items) t.lab.style.visibility = "visible";
      return;
    }
    const placed = { top: [], bot: [] };
    const clash = (band, l, w) => placed[band].find((r) => l < r.r + 3 && l + w + 3 > r.l);
    const place = (t) => {
      const w = t.lab.offsetWidth;
      let l = Math.min(Math.max(t.x * W - w / 2, 0), W - w);
      let band = t.band;
      if (clash(band, l, w)) {
        const other = band === "top" ? "bot" : "top";
        if (!clash(other, l, w)) band = other;
        else {
          const c = clash(band, l, w);
          const right = c.r + 3;
          if (right + w <= W) l = right;
          else if (c.l - 3 - w >= 0) l = c.l - 3 - w;
          else return false;
        }
      }
      placed[band].push({ l, r: l + w });
      t.lab.style.left = l + "px";
      t.lab.style.top = band === "top" ? "0px" : H - 22 + "px";
      t.lab.style.visibility = "visible";
      return true;
    };
    items.sort((a, b) => Number(b.fixed) - Number(a.fixed)).forEach((t) => {
      // 자리가 모자라면 날짜를 빼고 가격만 보여 준다.
      if (!place(t)) {
        t.lab.classList.add("short");
        place(t);
      }
    });
  });
  return wrap;
}

export const UP = "var(--up)";
export const DOWN = "var(--down)";
export const tint = (change) => (change > 0 ? UP : change < 0 ? DOWN : "var(--muted)");
export const signed = (v, digits = 0) => (v > 0 ? "▲" : v < 0 ? "▼" : "") + Math.abs(v).toLocaleString("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits });

// ---- 지표·종목 목록(앱과 같음) ----
const ins = (symbol, name, currency, type) => ({ symbol, name, currency, type });
export const KOREA_INDEXES = [ins("^KS11", "KOSPI", "KRW", "INDEX"), ins("^KQ11", "KOSDAQ", "KRW", "INDEX")];
export const PANEL_CATALOG = [
  ins("^GSPC", "S&P 500", "USD", "INDEX"), ins("^IXIC", "Nasdaq", "USD", "INDEX"), ins("NQ=F", "Nasdaq 100 Futures", "USD", "INDEX"), ins("KRW=X", "USD/KRW", "KRW", "FX"), ins("CL=F", "WTI", "USD", "EQUITY"),
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
const DEFAULT_MARKET_INDEXES = [...KOREA_INDEXES, ...PANEL_CATALOG.slice(0, 7)].map((x) => x.symbol);
const BRIEFING_DEFAULT = ["^KS11", "^KQ11", "^IXIC", "^GSPC", "KRW=X"]; // 오늘의 브리핑 첫째 줄 기본: KOSPI·KOSDAQ·Nasdaq·S&P 500·환율(고르기 전). 나스닥100 선물은 지표 선택에서 추가한다.
const BRIEFING_OLD_DEFAULT = ["^KS11", "^KQ11", "^IXIC", "^GSPC", "NQ=F", "KRW=X"];
export const DEFAULT_WATCH = [ins("005930.KS", "삼성전자", "KRW", "EQUITY"), ins("000660.KS", "SK하이닉스", "KRW", "EQUITY"), ins("069500.KS", "KODEX 200", "KRW", "ETF")];

/** 증시 지표 선택 { market:[...], briefing:[...] } (이 브라우저에 저장). */
export function indexSelection() {
  let market = store.get("market.indexes", DEFAULT_MARKET_INDEXES);
  let briefing = store.get("briefing.indexes", BRIEFING_DEFAULT);
  // 나스닥100 선물을 기본에 새로 넣었으므로, 이미 지표를 골라 둔 사용자에게도 한 번만 추가한다(이후 빼면 그대로 유지).
  if (!store.get("nq.added", false)) {
    const add = (list, after) => (list.includes("NQ=F") ? list : (() => { const i = list.indexOf(after); const c = list.slice(); c.splice(i < 0 ? c.length : i + 1, 0, "NQ=F"); return c; })());
    market = add(market, "^IXIC");
    store.set("market.indexes", market);
    store.set("nq.added", true);
  }
  // v3.3: 브리핑 첫째 줄을 5개로 맞추기 위해, 옛 기본값 그대로인 사용자는 새 기본값으로 한 번만 바꾼다(직접 고른 목록은 그대로).
  if (!store.get("brief.v33", false)) {
    if (briefing.length === BRIEFING_OLD_DEFAULT.length && BRIEFING_OLD_DEFAULT.every((x, i) => briefing[i] === x)) {
      briefing = BRIEFING_DEFAULT.slice();
      store.set("briefing.indexes", briefing);
    }
    store.set("brief.v33", true);
  }
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
const DEFAULT_BRIEFING_STATS = ["ktb-3y", "corp-aa", "household-credit"]; // 브리핑 둘째 줄 기본(고르기 전): 국고채 3년·회사채 3년 AA-·가계신용 잔액 + 소비자물가 상승률·실업률(거시경제)
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
export const DEFAULT_BRIEFING_MACRO = ["cpi-yoy", "unemployment"];
export const macroBriefingSelection = () => store.get("briefing.macro", DEFAULT_BRIEFING_MACRO);
export const saveMacroBriefing = (ids) => store.set("briefing.macro", ids);

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
