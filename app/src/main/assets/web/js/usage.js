// 익명 사용 통계(앱 UsageStats.kt와 같은 방식). 하루 한 번 서버에 (랜덤 기기ID, 웹 버전, 날짜, 메뉴별 이용 횟수)만 보낸다.
// 이름·계정·검색어는 보내지 않으며 앱 정보에서 끌 수 있다. 메뉴 횟수는 그날 누적값을 보낸다(오늘 것도 바뀔 때마다 보내며, 서버는 최댓값으로 받아 중복 집계되지 않는다).
// 사용자별 하루 방문 횟수·사용 시간(화면이 보인 시간)도 함께 보낸다(아래 '방문 횟수·사용 시간').
// 서버의 관리자 화면(/admin)에서 버전 이름 'web-…'으로 앱과 구분된다.
import { APP_VERSION, SERVER, store } from "./core.js";

const KEEP_DAYS = 14;
const pad = (n) => String(n).padStart(2, "0");
// 통계 날짜는 기기 시간대와 관계없이 항상 한국시간(UTC+9) 기준이다(서버·관리자 화면이 한국시간 하루로 집계하므로, 해외·다른 시간대 기기의 신호가 전날로 잡히지 않게 한다).
const dayOf = (d = new Date()) => {
  const k = new Date(d.getTime() + 9 * 3600e3);
  return `${k.getUTCFullYear()}-${pad(k.getUTCMonth() + 1)}-${pad(k.getUTCDate())}`;
};
// 자동화 브라우저(테스트·점검용: HeadlessChrome·Playwright·Puppeteer, navigator.webdriver)나 주소에 ?notrack=1 로 한 번 연 브라우저는 통계를 보내지 않는다(?notrack=0 으로 다시 켠다).
try {
  const nt = new URLSearchParams(location.search).get("notrack");
  if (nt === "1") store.set("usage.notrack", true);
  else if (nt === "0") store.set("usage.notrack", false);
} catch {
  // 주소를 못 읽어도 통계 동작에는 영향 없음
}
const automated = () => navigator.webdriver === true || /HeadlessChrome|Playwright|Puppeteer|PhantomJS/i.test(navigator.userAgent || "") || store.get("usage.notrack", false) === true;
export const enabled = () => store.get("usageStats", true) && !automated();

export function installId() {
  let id = store.get("usage.id", "");
  if (!/^[A-Za-z0-9-]{16,64}$/.test(id)) {
    id = crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
    store.set("usage.id", id);
  }
  return id;
}

/** 메뉴(화면)를 열 때마다. 이 브라우저에 날짜별 횟수만 쌓는다. */
export function recordMenu(label) {
  if (!enabled() || !label) return;
  const p = store.get("usage.pending", {});
  const day = dayOf();
  p[day] = p[day] || {};
  p[day][label] = (p[day][label] || 0) + 1;
  const hk = "~h:" + new Date().getHours(); // 시간대별 이용(기기 시계 기준 '시')
  p[day][hk] = (p[day][hk] || 0) + 1;
  store.set("usage.pending", p);
}

/** 오류·실패 한 건(이름만, 주소·내용은 남기지 않는다). 예: "api:/news:503", "fetch:upbit". 같은 날 같은 이름은 횟수만 쌓는다. */
export function recordError(name) {
  if (!enabled() || !name) return;
  const p = store.get("usage.pending", {});
  const day = dayOf();
  p[day] = p[day] || {};
  const key = "~e:" + String(name).replace(/[^0-9A-Za-z:/._-]/g, "").slice(0, 40);
  p[day][key] = Math.min(1000, (p[day][key] || 0) + 1);
  store.set("usage.pending", p);
}

/** 접속 환경 대분류(기기 종류·브라우저·운영체제·화면 크기·설치 여부). 모델·정확한 버전·IP는 보내지 않는다. */
export function envInfo() {
  const ua = navigator.userAgent || "";
  const mobile = /Android|iPhone|iPod/i.test(ua) || (/iPad/i.test(ua) || (navigator.maxTouchPoints > 1 && /Macintosh/i.test(ua)) ? "tablet" : false);
  const dev = mobile === "tablet" ? "태블릿" : mobile ? "휴대폰" : "PC";
  const br = /Edg\//.test(ua) ? "Edge" : /Whale/.test(ua) ? "Whale" : /SamsungBrowser/.test(ua) ? "Samsung" : /Firefox|FxiOS/.test(ua) ? "Firefox" : /OPR\//.test(ua) ? "Opera" : /Chrome|CriOS/.test(ua) ? "Chrome" : /Safari/.test(ua) ? "Safari" : "기타";
  const os = /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /iPhone|iPad|iPod/.test(ua) ? "iOS" : /Macintosh/.test(ua) ? (navigator.maxTouchPoints > 1 ? "iOS" : "macOS") : /Linux|CrOS/.test(ua) ? "Linux" : "기타";
  const w = window.innerWidth || 0;
  const sc = w < 480 ? "480 미만" : w < 768 ? "480~767" : w < 1280 ? "768~1279" : "1280 이상";
  const inst = matchMedia("(display-mode: standalone)").matches || navigator.standalone ? "설치형(PWA)" : "브라우저";
  return { dev, br, os, sc, inst };
}

// ---- 방문 횟수·사용 시간 ----
// 화면을 열 때마다(처음 열기·숨겼다가 다시 보기) 방문 1회, 화면이 보이는 동안 1분마다 시간을 쌓는다(숨기면 멈춤).
// 날짜별 '누적값'(v=방문, s=초)을 이 브라우저에 두고, 서버에는 그 누적값을 보낸다(서버가 최댓값으로 받아 중복 전송해도 안전).
// 서버가 이 기능을 지원한다고 알려 준 뒤(응답의 live)에만 추가로 보내므로, 서버가 아직 옛 버전이어도 부담·오류가 없다.
const TICK_MS = 60_000;
const LIVE_PUSH_MS = 2 * 60_000; // 보는 동안 서버에 올리는 간격(변동이 있을 때만)
const EARLY_PUSH_MS = 20_000; // 연 지 20초 뒤 한 번 더 올려, 금방 닫혀 마지막 전송이 누락돼도 시간이 0초로 남지 않게 한다
const MAX_GAP_MS = 90_000; // 절전·타이머 지연으로 한 번에 크게 늘어나는 것을 막는다
const sigOf = (x) => `${x.v || 0}:${x.s || 0}`;
let tickTimer = 0, lastTick = 0, lastPush = 0, earlyTimer = 0;

function bumpStat(fn) {
  const st = store.get("usage.stat", {});
  const day = dayOf();
  st[day] = st[day] || { v: 0, s: 0 };
  fn(st[day]);
  const cutoff = dayOf(new Date(Date.now() - KEEP_DAYS * 86400000));
  for (const d of Object.keys(st)) if (d < cutoff) delete st[d];
  store.set("usage.stat", st);
}
function tick(push = true) {
  const now = Date.now();
  // 같은 브라우저의 여러 탭·창이 동시에 보이면 각자 시간을 더해 두 배로 잡히므로, 마지막으로 시간을 더한 시각을 탭끼리 공유해 겹치는 구간은 한 번만 센다.
  const shared = Number(store.get("usage.tickAt", 0)) || 0;
  const gap = Math.min(now - Math.max(lastTick, shared), MAX_GAP_MS);
  lastTick = now;
  store.set("usage.tickAt", now);
  if (gap > 0) bumpStat((x) => (x.s = Math.min(86400, (x.s || 0) + Math.round(gap / 1000))));
  if (push && now - lastPush >= LIVE_PUSH_MS) {
    lastPush = now;
    sendIfNeeded();
  }
}
function startVisible(countVisit) {
  if (countVisit) bumpStat((x) => (x.v = Math.min(2000, (x.v || 0) + 1)));
  lastTick = Date.now();
  if (!tickTimer) tickTimer = setInterval(tick, TICK_MS);
  clearTimeout(earlyTimer);
  earlyTimer = setTimeout(() => {
    if (document.visibilityState !== "visible") return;
    tick(false); // 지금까지 본 시간을 더해 올린다
    sendIfNeeded();
  }, EARLY_PUSH_MS);
}
function stopVisible() {
  clearTimeout(earlyTimer);
  if (tickTimer) {
    clearInterval(tickTimer);
    tickTimer = 0;
    tick(false);
  }
}
/** 앱 시작 때 한 번. 화면 표시 여부에 따라 방문·시간을 기록한다. */
export function trackActivity() {
  if (!enabled()) return;
  lastPush = Date.now(); // 연 직후에는 하루 한 번 보내는 기본 전송이 먼저 나간다
  if (document.visibilityState === "visible") startVisible(true);
  document.addEventListener("visibilitychange", () => {
    if (!enabled()) return;
    if (document.visibilityState === "visible") {
      if (!tickTimer) startVisible(true);
    } else {
      stopVisible();
      sendIfNeeded(true);
    }
  });
  addEventListener("pagehide", () => {
    if (!enabled()) return;
    stopVisible();
    sendIfNeeded(true);
  });
}

/** 끄면 아직 보내지 않은 기록을 지운다. */
export function setEnabled(on) {
  store.set("usageStats", !!on);
  if (!on) {
    store.set("usage.pending", {});
    store.set("usage.stat", {});
    store.set("usage.acked", {});
    store.set("usage.mack", {});
    if (tickTimer) clearInterval(tickTimer), (tickTimer = 0);
  } else if (document.visibilityState === "visible" && !tickTimer) {
    startVisible(false);
  }
  window["ga-disable-G-FL8LM4E0Y1"] = !on;
}

let sending = false;
/**
 * 오늘 아직 보내지 않았거나 지난 날짜 기록이 남아 있거나(서버가 지원하면) 방문·시간 누적값이 바뀌었으면 보낸다. 실패하면 다음에 다시.
 * keepalive 는 화면을 닫는 순간에도 요청이 끝까지 가도록 한다.
 */
export async function sendIfNeeded(keepalive = false) {
  if (!enabled() || (sending && !keepalive) || !navigator.onLine) return;
  const today = dayOf();
  const cutoff = dayOf(new Date(Date.now() - KEEP_DAYS * 86400000));
  const pending = Object.fromEntries(Object.entries(store.get("usage.pending", {})).filter(([d]) => d >= cutoff));
  const past = Object.keys(pending).filter((d) => d < today).sort().slice(-10);
  const includeToday = store.get("usage.last", "") !== today || store.get("usage.lastV", "") !== APP_VERSION; // 같은 날 새 버전으로 바뀐 경우도 한 번 더 알린다
  const live = true; // 서버는 이미 방문·시간 수집을 지원한다. (예전엔 응답의 live를 받은 뒤에만 보냈는데, 첫 접속자는 그 전에 닫으면 시간이 0초로 남았다)
  const stat = store.get("usage.stat", {});
  const acked = store.get("usage.acked", {});
  const changed = Object.keys(stat).filter((d) => sigOf(stat[d]) !== acked[d]).sort().slice(-10);
  const extra = live ? changed : [];
  // 오늘 메뉴 누적값이 바뀌었으면(마지막으로 보낸 값과 다르면) 오늘 것도 함께 보낸다
  const mack = store.get("usage.mack", {});
  const todayMenus = pending[today];
  const menuSig = (m) => Object.values(m || {}).reduce((t, n) => t + n, 0);
  const todayMenuChanged = !!todayMenus && menuSig(todayMenus) !== (mack[today] || 0);
  if (!past.length && !includeToday && !extra.length && !todayMenuChanged) return;
  const map = new Map();
  past.forEach((d) => map.set(d, { d, m: pending[d] }));
  if (includeToday || todayMenuChanged) map.set(today, { d: today, m: todayMenus || {} });
  extra.forEach((d) => !map.has(d) && map.set(d, { d, m: {} }));
  const sentSig = {};
  for (const [d, o] of map) {
    if (stat[d]) {
      o.vis = stat[d].v || 0;
      o.sec = stat[d].s || 0;
      sentSig[d] = sigOf(stat[d]);
    }
  }
  const days = [...map.values()].sort((a, b) => (a.d < b.d ? -1 : 1)).slice(-10);
  const payload = JSON.stringify({ id: installId(), v: "web-" + APP_VERSION, env: envInfo(), days });
  const markSent = () => {
    const now = Object.fromEntries(Object.entries(store.get("usage.pending", {})).filter(([d]) => d >= cutoff));
    past.forEach((d) => delete now[d]);
    store.set("usage.pending", now);
    store.set("usage.last", today);
    store.set("usage.lastV", APP_VERSION);
    // 보낸 오늘 메뉴 누적 횟수(다음에 바뀐 경우에만 다시 보낸다)
    const mk = Object.fromEntries(Object.entries(store.get("usage.mack", {})).filter(([d]) => d >= cutoff));
    if (todayMenus) mk[today] = menuSig(todayMenus);
    store.set("usage.mack", mk);
    // 누적값은 서버가 최댓값으로 받으므로, 보낸 값과 같은 서명만 기록해 두면 이후 변동분만 다시 보낸다
    const a = Object.fromEntries(Object.entries(store.get("usage.acked", {})).filter(([d]) => d >= cutoff));
    Object.assign(a, sentSig);
    store.set("usage.acked", a);
  };
  // 화면을 닫는 순간에는 sendBeacon(사전 확인 요청이 없는 text/plain)으로 보낸다. fetch+JSON은 사전 확인(preflight) 때문에 닫히면서 끊기는 일이 있다.
  if (keepalive && navigator.sendBeacon) {
    try {
      if (navigator.sendBeacon(SERVER + "/usage/ping", new Blob([payload], { type: "text/plain" }))) {
        markSent();
        return;
      }
    } catch {
      // 아래 fetch 로 다시 시도
    }
  }
  sending = true;
  try {
    const res = await fetch(SERVER + "/usage/ping", { method: "POST", keepalive, headers: { "Content-Type": "application/json" }, body: payload });
    if (res.ok) {
      markSent();
      store.set("usage.live", true);
    }
  } catch {
    // 오프라인·서버 오류는 다음에 다시
  } finally {
    sending = false;
  }
}

// ---- Google 애널리틱스(GA4) ----
// 메뉴 이동은 주소의 # 뒤만 바뀌어 GA가 구분하지 못하므로, 메뉴마다 경로(/fss-insights-web/law 등)를 넣어 page_view를 직접 보낸다.
// GA는 처음 불러올 때 /fss-insights-web/ 로 page_view를 스스로 한 번 보낸다(끌 수 없음). 보고서에서 이 경로는 '접속', 나머지는 '메뉴 조회'로 보면 된다.
// 검색어 같은 화면 조건(# 뒤 ? 이하)은 보내지 않는다. 통계 스위치를 끄면 GA도 멈춘다.
const GA_ID = "G-FL8LM4E0Y1";
let gaReady = false;
function gtag() {
  (window.dataLayer = window.dataLayer || []).push(arguments);
}
const basePath = () => location.pathname.replace(/[^/]*$/, "");
const pageOf = (id, label) => ({ page_title: label, page_location: location.origin + basePath() + id, page_path: basePath() + id });

/** 화면을 열 때마다 GA에 페이지 조회를 남긴다(github.io·fssinsights.com에서만). */
export function pageView(id, label) {
  if (!enabled() || location.protocol !== "https:" || !(location.hostname.endsWith("github.io") || location.hostname.endsWith("fssinsights.com"))) return;
  if (!gaReady) {
    gaReady = true;
    window[`ga-disable-${GA_ID}`] = false;
    window.gtag = gtag;
    gtag("js", new Date());
    gtag("config", GA_ID);
    const s = document.createElement("script");
    s.async = true;
    s.src = `https://www.googletagmanager.com/gtag/js?id=${GA_ID}`;
    document.head.append(s);
  }
  gtag("event", "page_view", pageOf(id, label));
}
