// 금융꿀팁·소비자경보: 금융감독원 파인의 공개 목록(제목·번호·날짜·링크)을 검색하고, 원문은 공식 사이트로 연결한다.
import { setHeader } from "./hdr.js";
import { card, dotDate, errorText, h, icon, loadJson, loading, message, searchBox } from "../../core.js";

export async function render(root, params, ctx) {
  setHeader("금융꿀팁·소비자경보", "금융감독원이 알리는 소비자 유의사항");
  root.append(loading());
  let tips, links;
  try {
    [tips, links] = await Promise.all([loadJson("data/consumer/tips.json"), loadJson("data/consumer/links.json")]);
  } catch (e) {
    root.replaceChildren(message("자료를 불러오지 못했습니다.\n" + errorText(e), { error: true }));
    return;
  }
  let q = (params.q || "").trim();
  const list = h("div", { class: "link-list" });
  const count = h("div", { class: "note" });
  const draw = () => {
    const kw = q.toLowerCase();
    const rows = tips.items.filter((t) => !kw || (t.title + " " + t.cat + " " + t.dept).toLowerCase().includes(kw));
    count.textContent = `${tips.items.length}건 중 ${rows.length}건`;
    list.replaceChildren(...(rows.length ? rows.map((t) =>
      h("a", { class: "link-item", href: t.url, target: "_blank", rel: "noopener noreferrer" },
        h("div", null, h("b", null, `[금융꿀팁 ${t.no}] ${t.title}`), h("small", null, `${dotDate(t.date)} · ${t.cat} · ${t.dept}`)), icon("external-link")))
      : [h("div", { class: "state" }, "검색 결과가 없습니다. 아래 ‘더 보기’의 전체 목록에서 찾아보세요.")]));
  };
  const box = searchBox({ placeholder: "예: 연금, 보험, 대출, 투자", value: q, onSearch: (v) => { q = v; draw(); ctx.go(v ? { q: v } : {}, { replace: true }); } });
  box.input.addEventListener("input", () => { q = box.input.value.trim(); draw(); });
  root.replaceChildren(
    h("div", { class: "grid2" },
      h("div", { class: "stack" }, box, count, list,
        h("p", { class: "note" }, `${tips.source}. 최근 ${tips.items.length}건만 수록했고 기준일은 ${tips.asOf}입니다. 전체 목록은 오른쪽 ‘더 보기’에서 확인하세요.`)),
      h("div", { class: "stack" },
        card("더 보기 (금융감독원·파인)", "globe", h("div", { class: "link-list" }, links.learn.map((x) =>
          h("a", { class: "link-item slim", href: x.u, target: "_blank", rel: "noopener noreferrer" }, h("b", null, x.t), icon("external-link"))))),
        card("소비자경보란?", "bell", h("p", { class: "note" }, "금융소비자의 피해가 우려될 때 금융감독원이 발령하는 경보입니다. 발령내역은 ‘금융소비자경보 발령내역’에서 확인할 수 있습니다."))))
  );
  draw();
}
