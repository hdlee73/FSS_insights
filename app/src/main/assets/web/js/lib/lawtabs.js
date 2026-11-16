// 스마트 법령검색의 서브 메뉴: 법령 검색 · 최근 개정 소식. 두 화면 맨 위에 같은 줄을 붙인다.
import { h, icon } from "../core.js";

export function lawTabs(active) {
  const tab = (id, label, ic) => h("a", { class: "lw-tab" + (active === id ? " on" : ""), href: "#/" + id, "aria-current": active === id ? "page" : null }, icon(ic), label);
  return h("nav", { class: "lw-tabs", "aria-label": "스마트 법령검색 하위 메뉴" }, tab("law", "법령 검색", "search"), tab("lawnews", "최근 개정 소식", "file-text"));
}
