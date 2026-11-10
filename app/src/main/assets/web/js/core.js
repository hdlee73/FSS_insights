// 화면 공통 도구: 서버 호출, 기관 사이트 읽기, 저장소, DOM 만들기, 알림 문구.
import { ICONS } from "./icons.js";

export const APP_NAME = "FSS Insights 웹";
// 면책 안내(앱 DisclaimerDialog.kt와 같은 문구)
export const DISCLAIMER_SHORT = "이 웹사이트는 금융감독원의 공식 사이트가 아닙니다. 이용에 참고하시기 바랍니다.";
export const DISCLAIMER_DETAIL =
  "FSS Insights는 개인이 만든 비공식 서비스이며 금융감독원 등 어떤 기관과도 제휴·위탁 관계가 없습니다. " +
  "화면에 표시되는 보도자료, 통계, 법령 등은 외부 출처의 공개 자료를 모아 보여주는 것으로, 정확성과 최신성을 보장하지 않습니다. " +
  "중요한 판단에는 반드시 원문과 공식 자료를 확인하시기 바랍니다.";
export const APP_VERSION = "2.8.0";

// 서버(Cloudflare Worker). 앱과 같은 서버를 쓰고, 웹 주소(Origin)로 들어온 요청만 토큰 없이 받는다.
const DEFAULT_SERVER = "https://news-proxy.hdlee73.workers.dev";
export const SERVER = (() => {
  try {
    return (localStorage.getItem("fss.server") || DEFAULT_SERVER).replace(/\/+$/, "");
  } catch {
    return DEFAULT_SERVER;
  }
})();

// ---- 저장소 (이 브라우저에만 저장) ----
export const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem("fss." + key);
      return v == null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem("fss." + key, JSON.stringify(value));
    } catch {
      /* 저장 공간이 없거나 막힌 브라우저: 저장 없이 계속 */
    }
  },
  remove(key) {
    try {
      localStorage.removeItem("fss." + key);
    } catch {}
  },
};

// ---- 서버 호출 ----
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

// ---- 데이터 최신성: 서버에서 자료를 마지막으로 정상 수신한 시각(헤더 우측에 표시) ----
export const freshness = { at: store.get("updated", 0), stale: 0 };
let savedAt = 0;
function emitFresh() {
  window.dispatchEvent(new Event("fss-fresh"));
}
function markFresh() {
  freshness.at = Date.now();
  freshness.stale = 0;
  if (freshness.at - savedAt > 10000) {
    savedAt = freshness.at;
    store.set("updated", freshness.at);
  }
  emitFresh();
}
/** 연결이 안 되어 저장해 둔 자료(저장 시각 at)로 대신 보여 주는 중임을 알린다. */
export function markStale(at) {
  freshness.stale = Math.max(freshness.stale, at || 0);
  emitFresh();
}

async function request(path, init = {}) {
  let res;
  try {
    res = await fetch(SERVER + path, init);
  } catch {
    throw new ApiError("서버에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.", 0);
  }
  if (res.ok) markFresh();
  if (res.status === 429) throw new ApiError("요청이 많아 잠시 쉬고 있습니다. 1분 뒤 다시 시도해 주세요.", 429);
  if (!res.ok) {
    let message = `서버 응답 오류 (${res.status})`;
    try {
      const body = await res.clone().json();
      if (body && body.error) message = body.error;
    } catch {
      const text = await res.text().catch(() => "");
      if (text && text.length < 200) message = text;
    }
    throw new ApiError(message, res.status);
  }
  return res;
}

/** 서버의 JSON 경로(/dart/…, /fisis/… 등). */
export async function api(path, params) {
  const qs = params ? "?" + new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== "")) : "";
  return (await request(path + qs)).json();
}

/** 서버의 원문 응답(법령 XML 등). */
export async function apiText(path, params) {
  const qs = params ? "?" + new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== "")) : "";
  return (await request(path + qs)).text();
}

export async function apiBlob(path) {
  const res = await request(path);
  return { blob: await res.blob(), disposition: res.headers.get("Content-Disposition") || "" };
}

/** 앱이 기기에서 직접 읽던 기관·시세 사이트를 서버(/fetch)를 거쳐 읽는다. 문자 집합(EUC-KR 등)을 알아서 푼다. */
export async function fetchSite(url, { method = "GET", body, contentType, referer } = {}) {
  const init = { method };
  if (method === "POST") {
    init.body = body;
    init.headers = { "Content-Type": contentType || "application/x-www-form-urlencoded; charset=UTF-8" };
  }
  const res = await request("/fetch?url=" + encodeURIComponent(url) + (referer ? "&ref=" + encodeURIComponent(referer) : ""), init);
  const bytes = await res.arrayBuffer();
  return { text: decodeBytes(bytes, res.headers.get("Content-Type")), finalUrl: res.headers.get("X-Final-Url") || url };
}

export function decodeBytes(bytes, contentType) {
  const fromHeader = /charset=([\w-]+)/i.exec(contentType || "")?.[1];
  const head = new TextDecoder("latin1").decode(bytes.slice(0, 2048));
  const fromMeta = /charset=["']?([\w-]+)/i.exec(head)?.[1];
  for (const cs of [fromHeader, fromMeta, "utf-8"]) {
    if (!cs) continue;
    try {
      return new TextDecoder(cs.toLowerCase() === "ks_c_5601-1987" ? "euc-kr" : cs).decode(bytes);
    } catch {
      /* 모르는 문자 집합이면 다음 후보 */
    }
  }
  return new TextDecoder().decode(bytes);
}

/** 같은 키의 결과를 잠시 기억한다(화면을 오갈 때 다시 읽지 않도록). */
const memo = new Map();
export function cached(key, ttlMs, load) {
  const hit = memo.get(key);
  if (hit && Date.now() - hit.at < ttlMs) return hit.promise;
  const promise = load().catch((e) => {
    memo.delete(key);
    throw e;
  });
  memo.set(key, { at: Date.now(), promise });
  return promise;
}
/** 같은 주소의 JSON을 읽는다(실패하면 예외). 30분 동안은 다시 받지 않는다. */
export async function loadJson(path) {
  return cached("json:" + path, 30 * 60 * 1000, async () => {
    const res = await fetch(path);
    if (!res.ok) throw new Error("자료를 불러오지 못했습니다 (" + res.status + ")");
    return res.json();
  });
}

export function forget(prefix) {
  for (const k of memo.keys()) if (k.startsWith(prefix)) memo.delete(k);
}

// ---- DOM ----
// replaceChildren(null)은 "null"이라는 글자를 넣어 버리므로, 비어 있는 자리(null·false)는 건너뛰도록 바꿔 둔다.
{
  const orig = Element.prototype.replaceChildren;
  Element.prototype.replaceChildren = function (...kids) {
    return orig.apply(this, kids.flat().filter((k) => k != null && k !== false));
  };
}

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === "html") el.innerHTML = v;
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function icon(name, cls = "i") {
  const span = document.createElement("span");
  span.className = cls;
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ""}</svg>`;
  return span;
}

export function iconButton(name, label, onClick, cls = "") {
  return h("button", { class: "ib " + cls, type: "button", title: label, "aria-label": label, onclick: onClick }, icon(name));
}

export function loading(text = "불러오는 중…") {
  return h("div", { class: "state" }, h("div", { class: "spin" }), h("div", null, text));
}

/** 자료를 기다리는 동안 보여 주는 뼈대(스켈레톤). rows: 줄 수, kind: "list"(기사 줄) 또는 "page"(화면 전체). */
export function skeleton(rows = 5, kind = "list") {
  const row = () => h("div", { class: "skel-row" }, h("div", { class: "skel skel-s" }), h("div", { class: "skel skel-l" }), h("div", { class: "skel skel-m" }));
  if (kind === "page") {
    return h("div", { class: "skel-page", "aria-busy": "true", "aria-label": "불러오는 중" },
      h("div", { class: "skel skel-hero" }),
      h("div", { class: "skel-tiles" }, [1, 2, 3, 4].map(() => h("div", { class: "skel skel-tile" }))),
      h("div", { class: "card list" }, Array.from({ length: rows }, row)));
  }
  return h("div", { class: "skel-list", "aria-busy": "true", "aria-label": "불러오는 중" }, Array.from({ length: rows }, row));
}

export function message(text, { error = false, action, onAction } = {}) {
  return h(
    "div",
    { class: "state" + (error ? " err" : "") },
    h("div", { class: "msg" }, text),
    action ? h("button", { class: "btn ghost", type: "button", onclick: onAction }, action) : null
  );
}

export function errorText(e) {
  return e?.message || String(e);
}

let toastTimer;
export function toast(text) {
  let el = document.getElementById("toast");
  if (!el) {
    el = h("div", { id: "toast", role: "status" });
    document.body.append(el);
  }
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2600);
}

export function openUrl(url) {
  if (window.FssNative) return window.FssNative.openUrl(String(url));
  window.open(url, "_blank", "noopener");
}

export async function share(title, url) {
  if (window.FssNative) return window.FssNative.share(String(title), String(url));
  if (navigator.share) {
    try {
      await navigator.share({ title, url });
      return;
    } catch (e) {
      if (e?.name === "AbortError") return;
    }
  }
  try {
    await navigator.clipboard.writeText(`${title}\n${url}`);
    toast("제목과 링크를 복사했습니다.");
  } catch {
    toast("복사하지 못했습니다.");
  }
}

/** 파일 내려받기(엑셀·HWPX·PDF 등). */
export function download(blob, name) {
  if (window.FssNative) {
    // 앱: 파일 저장 창(SAF)으로 넘긴다.
    const fr = new FileReader();
    fr.onload = () => window.FssNative.saveFile(String(name), blob.type || "application/octet-stream", String(fr.result).split(",")[1] || "");
    fr.readAsDataURL(blob);
    return;
  }
  const a = h("a", { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(a.href);
    a.remove();
  }, 1000);
}

/** 간단한 확인·입력 창. fields가 있으면 입력값 객체를, 없으면 true를 돌려준다(취소는 null). */
export function dialog({ title, text, body, fields, ok = "확인", cancel = "취소", danger = false, wide = false }) {
  return new Promise((resolve) => {
    const inputs = (fields || []).map((f) =>
      h("label", { class: "field" }, h("span", null, f.label), h(f.multiline ? "textarea" : "input", { name: f.name, value: f.value || "", placeholder: f.placeholder || "", rows: f.multiline ? 3 : null }))
    );
    for (const [i, f] of (fields || []).entries()) if (f.multiline) inputs[i].querySelector("textarea").value = f.value || "";
    const close = (v) => {
      back.remove();
      resolve(v);
    };
    const submit = () => {
      if (!fields) return close(true);
      const out = {};
      for (const el of back.querySelectorAll("input[name],textarea[name]")) out[el.name] = el.value.trim();
      close(out);
    };
    const back = h(
      "div",
      { class: "modal-back", onclick: (e) => e.target === back && close(null) },
      h(
        "form",
        { class: "modal" + (wide ? " wide" : ""), onsubmit: (e) => (e.preventDefault(), submit()) },
        h("h3", null, title),
        text ? h("p", null, text) : null,
        body ? h("div", { class: "modal-body" }, body) : null,
        inputs,
        h("div", { class: "modal-acts" }, cancel ? h("button", { type: "button", class: "btn ghost", onclick: () => close(null) }, cancel) : null, h("button", { type: "submit", class: "btn" + (danger ? " danger" : "") }, ok))
      )
    );
    document.body.append(back);
    (back.querySelector("input,textarea") || back.querySelector("button[type=submit]")).focus();
    back.addEventListener("keydown", (e) => e.key === "Escape" && close(null));
  });
}

// ---- 날짜·숫자 ----
export function ymd(d) {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
export function dotDate(iso) {
  if (!iso) return "";
  return iso.slice(0, 10).replace(/-/g, ".");
}
export function today() {
  return ymd(new Date());
}
export function fmtNum(n, digits = 0) {
  if (n == null || Number.isNaN(n)) return "-";
  return Number(n).toLocaleString("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** 탭 줄. 고른 번호를 onSelect로 알린다.
 *  탭이 화면 폭을 넘어 오른쪽이 가려지면 오른쪽 끝에 » 표시가 나타나고, 누르면 다음 탭들이 보이도록 밀어 준다. */
export function tabs(labels, selected, onSelect, cls = "") {
  const bar = h(
    "div",
    { class: "tabs " + cls, role: "tablist" },
    labels.map((label, i) => h("button", { type: "button", role: "tab", class: i === selected ? "on" : "", "aria-selected": String(i === selected), onclick: () => onSelect(i) }, label))
  );
  const more = h("button", { type: "button", class: "tabs-more", "aria-label": "다음 메뉴 더 보기", title: "더 보기", hidden: true, onclick: () => bar.scrollBy({ left: Math.max(120, bar.clientWidth * 0.7), behavior: "smooth" }) }, "»");
  const wrap = h("div", { class: "tabs-wrap" + (cls.includes("seg") ? " seg" : "") }, bar, more);
  const check = () => {
    more.hidden = bar.scrollWidth - bar.scrollLeft - bar.clientWidth <= 2;
  };
  bar.addEventListener("scroll", check, { passive: true });
  // 탭 줄 자체와 각 탭의 크기가 바뀔 때(창 크기·글꼴 로딩)마다 다시 확인한다.
  if (typeof ResizeObserver === "function") {
    const ro = new ResizeObserver(check);
    ro.observe(bar);
    for (const b of bar.children) ro.observe(b);
  } else setTimeout(check, 0);
  document.fonts?.ready?.then(check).catch(() => {});
  // 고른 탭이 가려져 있으면 보이는 곳으로 옮긴다.
  requestAnimationFrame(() => {
    const on = bar.querySelector("button.on");
    if (on && on.offsetLeft + on.offsetWidth > bar.clientWidth) bar.scrollLeft = on.offsetLeft - 16;
    check();
  });
  return wrap;
}

export function searchBox({ placeholder, value = "", onSearch, autofocus = false }) {
  const input = h("input", { type: "search", placeholder, value, enterkeyhint: "search", autofocus });
  const form = h(
    "form",
    { class: "search", onsubmit: (e) => (e.preventDefault(), input.blur(), onSearch(input.value.trim())) },
    icon("search"),
    input,
    h("button", { class: "btn sm", type: "submit" }, "검색")
  );
  form.input = input;
  return form;
}

export function card(title, iconName, body, actions) {
  return h("section", { class: "card" }, h("div", { class: "ch" }, iconName ? icon(iconName) : null, h("h3", null, title), h("div", { class: "ch-acts" }, actions)), body);
}
