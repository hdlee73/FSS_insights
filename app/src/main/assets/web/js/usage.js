// 익명 사용 통계(앱 UsageStats.kt와 같은 방식). 하루 한 번 서버에 (랜덤 기기ID, 웹 버전, 날짜, 메뉴별 이용 횟수)만 보낸다.
// 이름·계정·검색어는 보내지 않으며 앱 정보에서 끌 수 있다. 지난 날짜는 메뉴 횟수와 함께, 오늘은 '사용함'만 보낸다.
// 서버의 관리자 화면(/admin)에서 버전 이름 'web-…'으로 앱과 구분된다.
import { APP_VERSION, SERVER, store } from "./core.js";

const KEEP_DAYS = 14;
const pad = (n) => String(n).padStart(2, "0");
const dayOf = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const enabled = () => store.get("usageStats", true);

function installId() {
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

/** 끄면 아직 보내지 않은 기록을 지운다. */
export function setEnabled(on) {
  store.set("usageStats", !!on);
  if (!on) store.set("usage.pending", {});
  window["ga-disable-G-FL8LM4E0Y1"] = !on;
}

let sending = false;
/** 오늘 아직 보내지 않았거나 지난 날짜 기록이 남아 있으면 보낸다. 실패하면 다음에 다시. */
export async function sendIfNeeded() {
  if (!enabled() || sending || !navigator.onLine) return;
  const today = dayOf();
  const cutoff = dayOf(new Date(Date.now() - KEEP_DAYS * 86400000));
  const pending = Object.fromEntries(Object.entries(store.get("usage.pending", {})).filter(([d]) => d >= cutoff));
  const past = Object.keys(pending).filter((d) => d < today).sort().slice(-10);
  const includeToday = store.get("usage.last", "") !== today || store.get("usage.lastV", "") !== APP_VERSION; // 같은 날 새 버전으로 바뀐 경우도 한 번 더 알린다
  if (!past.length && !includeToday) return;
  const days = [...past.map((d) => ({ d, m: pending[d] })), ...(includeToday ? [{ d: today, m: {} }] : [])];
  sending = true;
  try {
    const res = await fetch(SERVER + "/usage/ping", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id: installId(), v: "web-" + APP_VERSION, env: envInfo(), days }) });
    if (res.ok) {
      const now = Object.fromEntries(Object.entries(store.get("usage.pending", {})).filter(([d]) => d >= cutoff));
      past.forEach((d) => delete now[d]);
      store.set("usage.pending", now);
      store.set("usage.last", today);
      store.set("usage.lastV", APP_VERSION);
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
