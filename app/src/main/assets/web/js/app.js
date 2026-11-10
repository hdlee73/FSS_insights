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
  main.replaceChildren(root);
  main.scrollTop = 0;
  currentScreen = mod;
  try {
    await mod.render(root, params, { id, go: (p, opts) => go(id, p, opts), alive: () => seq === renderSeq });
  } catch (e) {
    console.error(e);
    if (seq === renderSeq) root.replaceChildren(h("div", { class: "state err" }, "화면 오류: " + (e.message || e)));
  }
}

addEventListener("hashchange", render);
if (!location.hash) history.replaceState(null, "", "#/" + HOME);
render();
