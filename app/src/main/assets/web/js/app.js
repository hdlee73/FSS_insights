// 앱에 내장된 화면(금융소비자 지킴이·제재 양정 시뮬레이터·가상자산 시장 동향)을 띄우는 얇은 뼈대.
// 웹(fss-insights-web)의 js/app.js 와 같은 약속(go · setHeader · currentRoute, 화면의 render(root, params, ctx))을 지켜
// 웹의 화면 코드를 그대로 쓴다. 메뉴·서랍·뒤로가기는 앱(Compose)이 맡는다. 이 파일은 tools/sync_web_assets.py 가 덮어쓰지 않는다.
import { h } from "./core.js";

const ROUTES = {
  consumer: { label: "금융소비자 지킴이", load: () => import("./screens/consumer.js") },
  finsim: { label: "(베타)제재 양정 시뮬레이터", load: () => import("./screens/finsim.js") },
  crypto: { label: "가상자산 시장 동향", load: () => import("./screens/crypto.js") },
};
const HOME = "consumer";

const main = document.getElementById("content");
const titleEl = document.getElementById("title");
const subEl = document.getElementById("sub");
const actionsEl = document.getElementById("top-actions");

export function currentRoute() {
  const raw = location.hash.replace(/^#\/?/, "");
  const [path, qs] = raw.split("?");
  const id = ROUTES[path] ? path : HOME;
  return { id, params: Object.fromEntries(new URLSearchParams(qs || "")) };
}

/** 다른 화면(또는 같은 화면의 다른 상태)으로 이동. replace면 기록을 늘리지 않는다. */
export function go(id, params = {}, { replace = false } = {}) {
  const qs = new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== "")).toString();
  const hash = `#/${id}${qs ? "?" + qs : ""}`;
  if (hash === location.hash) return render();
  if (replace) {
    history.replaceState(null, "", hash);
    render();
  } else location.hash = hash;
}

export function setHeader(title, sub = "", actions = []) {
  titleEl.textContent = title;
  subEl.textContent = sub;
  actionsEl.replaceChildren(...actions.filter(Boolean));
}

let renderSeq = 0;
let currentScreen = null;
async function render() {
  const seq = ++renderSeq;
  const { id, params } = currentRoute();
  setHeader(ROUTES[id].label);
  try {
    currentScreen?.leave?.();
  } catch {}
  let mod;
  try {
    mod = await ROUTES[id].load();
  } catch {
    main.replaceChildren(h("div", { class: "state err" }, "화면을 불러오지 못했습니다. 앱을 다시 열어 주세요."));
    return;
  }
  if (seq !== renderSeq) return;
  const root = h("div", { class: "screen screen-" + id });
  main.dataset.route = id; // 화면별 여백·스크롤 규칙(index.html)에 쓴다. :has()를 못 쓰는 WebView에서도 동작한다.
  main.replaceChildren(root);
  main.scrollTop = 0;
  currentScreen = mod;
  try {
    await mod.render(root, params, { id, go: (p, opts) => go(id, p, opts), alive: () => seq === renderSeq });
    checkVisible(seq);
  } catch (e) {
    console.error(e);
    if (seq === renderSeq) root.replaceChildren(h("div", { class: "state err" }, "화면 오류: " + (e.message || e)));
  }
}

/** 본문이 비어 보이는 문제를 숨기지 않는다: 오류는 화면에 글로 보여 주고 앱의 오류 기록(콘솔)에도 남긴다. */
function showFatal(msg) {
  console.error("[embedded] " + msg);
  if (main.querySelector(".state.err")) return;
  main.append(h("div", { class: "state err" }, "화면 오류: " + msg));
}
addEventListener("error", (e) => showFatal(e.message || "알 수 없는 오류"));
addEventListener("unhandledrejection", (e) => showFatal(String((e.reason && e.reason.message) || e.reason || "알 수 없는 오류")));
/** 그린 뒤에도 본문 높이가 0이면(레이아웃 실패) 크기 정보와 함께 알려 준다. */
function checkVisible(seq) {
  setTimeout(() => {
    if (seq !== renderSeq) return;
    const r = main.getBoundingClientRect();
    const k = main.firstElementChild?.getBoundingClientRect();
    if (r.height < 40 || !k || k.height < 40) showFatal(`본문 크기 이상(content ${Math.round(r.width)}x${Math.round(r.height)}, screen ${k ? Math.round(k.width) + "x" + Math.round(k.height) : "없음"})`);
  }, 1500);
}

addEventListener("hashchange", render);
if (!location.hash) history.replaceState(null, "", "#/" + HOME);
render();
