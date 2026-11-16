// 최근 금융관련 법령개정 소식(스마트 법령검색의 서브 메뉴): 주요 금융 법령과 그 시행령·시행규칙·감독규정·시행세칙의 최근 제·개정을 공포(발령)일 최신순으로 보여 준다.
import { setHeader } from "../app.js";
import { cached, h, icon, skeleton } from "../core.js";
import { KINDS, docHref, loadLawNews } from "../lib/lawnews.js";
import { lawTabs } from "../lib/lawtabs.js";

const dot = (v) => (v && /^\d{8}$/.test(v) ? `${v.slice(0, 4)}.${v.slice(4, 6)}.${v.slice(6)}` : v || "-");
const today = () => new Date().toISOString().slice(0, 10).replace(/-/g, "");
const KIND_TAG = { 법률: "blue", 시행령: "green", 시행규칙: "green", 감독규정: "orange", 시행세칙: "orange" };

export async function render(root, params, ctx) {
  setHeader("최근 금융관련 법령개정 소식", "주요 금융 법령과 시행령·시행규칙·감독규정·시행세칙의 최근 제·개정 · 누르면 전체 조문과 개정 내용");
  const box = h("div", { class: "card list" }, skeleton(8));
  const chips = h("div", { class: "lw-search-opts" });
  root.append(lawTabs("lawnews"), chips, box, h("p", { class: "note", style: { margin: "10px 2px" } }, "국가법령정보 연혁에서 읽은 자료입니다(감독규정·시행세칙은 현행본 기준). 시행일이 아직 오지 않은 개정은 ‘시행 예정’으로 표시합니다. 정확한 내용은 법령 원문을 확인하세요."));
  let rows;
  try {
    rows = await cached("lawnews.all2", 6 * 3600 * 1000, () => loadLawNews({ per: 3, limit: 80, sub: true }));
  } catch {
    rows = [];
  }
  if (!ctx.alive()) return;
  if (!rows.length) return box.replaceChildren(h("div", { class: "note" }, "법령 개정 정보를 불러오지 못했습니다. 새로고침을 눌러 주세요."));
  const t = today();
  let kind = params.kind || "";
  const draw = () => {
    const shown = rows.filter((x) => !kind || x.kind === kind).slice(0, 60);
    chips.replaceChildren(...["", ...KINDS].map((k) => {
      const n = k ? rows.filter((x) => x.kind === k).length : rows.length;
      const b = h("button", { type: "button", class: "pill" + (kind === k ? " on" : ""), disabled: !n && !!k }, `${k || "전체"} ${n}`);
      b.onclick = () => ((kind = k), draw());
      return b;
    }));
    box.replaceChildren(...(shown.length ? shown.map((x) => h("a", { class: "item ln-row", href: docHref(x) }, h("div", { class: "body" },
      h("div", { class: "ln-t" }, h("b", null, x.name), h("span", { class: "tag " + (KIND_TAG[x.kind] || "") }, x.kind), x.type ? h("span", { class: "tag blue" }, x.type) : null, x.effectiveDate > t ? h("span", { class: "tag orange" }, "시행 예정") : null),
      h("div", { class: "m" }, `${x.adm ? "발령" : "공포"} ${dot(x.promulgationDate)} · 시행 ${dot(x.effectiveDate)}`)), icon("chevron-right"))) : [h("div", { class: "note" }, "해당 구분의 개정 소식이 없습니다.")]));
  };
  draw();
}
