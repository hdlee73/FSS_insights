// 분쟁조정 사례 검색: 자연어 질문으로 찾고, 비슷한 사례와 배상(인용) 비율 통계를 보여 준다.
// 자료는 data/cases.json(사람이 검수한 요약)이며 원문은 금융감독원 사이트로 연결한다.
import { setHeader } from "./hdr.js";
import { buildIndex, ratioStats, search, similar } from "../../lib/consumer/search.js";
import { card, dotDate, errorText, h, icon, loadJson, loading, message, searchBox } from "../../core.js";

const EXAMPLES = ["백내장 다초점렌즈 실손", "메신저피싱 비대면 대출", "ELS 설명의무", "보험금 부지급 고지의무"];
const SECTORS = ["은행", "보험", "금융투자", "중소서민", "기타"];
const RESULT_CLS = { 인용: "green", 일부인용: "blue", 기각: "red" };

export async function render(root, params, ctx) {
  setHeader("분쟁조정 사례 검색", "내 상황과 비슷한 사례와 배상 비율을 찾아봅니다");
  root.append(loading());
  let data;
  try {
    data = await loadJson("data/consumer/cases.json");
  } catch (e) {
    root.replaceChildren(message("사례 자료를 불러오지 못했습니다.\n" + errorText(e), { error: true }));
    return;
  }
  const items = data.items || [];
  const index = buildIndex(items);
  const st = { q: (params.q || "").trim(), sector: params.sector || "", result: params.result || "", sim: params.sim || "" };

  const out = h("div", { class: "stack" });
  const box = searchBox({ placeholder: "상황을 문장으로 써 보세요. 예: 백내장 수술했는데 실손보험금을 안 줘요", value: st.q, onSearch: (v) => { st.q = v; st.sim = ""; sync(); } });
  const sectorTabs = h("div", { class: "chip-row wrap" });
  const examples = h("div", { class: "chip-row wrap" });

  const sync = () => {
    ctx.go({ q: st.q, sector: st.sector, result: st.result, sim: st.sim }, { replace: true });
  };
  const filter = (d) => (!st.sector || d.sector === st.sector) && (!st.result || d.result === st.result);

  const caseCard = (d, hits = []) => h("section", { class: "case" },
    h("div", { class: "case-h" },
      h("span", { class: "tag blue" }, d.sector || "기타"),
      d.kind ? h("span", { class: "tag" }, d.kind) : null,
      d.result ? h("span", { class: "tag " + (RESULT_CLS[d.result] || "") }, d.result) : null,
      d.outcome ? h("span", { class: "tag green" }, d.outcome) : null,
      typeof d.ratio === "number" ? h("span", { class: "tag orange" }, `인정 비율 ${d.ratio}%`) : null,
      h("span", { class: "note" }, [d.date ? dotDate(d.date) + " 게시" : "", d.no || ""].filter(Boolean).join(" · "))),
    h("h4", null, d.title),
    d.issue ? h("p", null, h("b", null, "핵심 쟁점 "), d.issue) : null,
    d.claims ? h("p", null, h("b", null, "당사자 주장 "), [d.claims.applicant ? `신청인: ${d.claims.applicant}` : "", d.claims.respondent ? `금융회사: ${d.claims.respondent}` : ""].filter(Boolean).join(" / ")) : null,
    d.reasoning ? h("p", null, h("b", null, "위원회 판단 "), d.reasoning) : null,
    hits.length ? h("div", { class: "note" }, "맞은 낱말: " + hits.join(", ")) : null,
    h("div", { class: "row-gap" },
      h("button", { class: "btn ghost sm", type: "button", onclick: () => { st.sim = d.id; st.q = ""; sync(); } }, icon("git-compare-arrows"), "비슷한 사례"),
      d.url ? h("a", { class: "btn ghost sm", href: d.url, target: "_blank", rel: "noopener noreferrer" }, icon("external-link"), "원문(금융감독원)") : null));

  const statsCard = (docs) => {
    const s = ratioStats(docs);
    if (!s) return null;
    const max = Math.max(...s.bins, 1);
    return card("인정 비율 통계", "bar-chart-3", h("div", { class: "stack" },
      h("p", null, `비율이 공개된 ${s.n}건 기준 · 중앙값 ${Math.round(s.median)}% · 평균 ${Math.round(s.mean)}% · 최소 ${s.min}% ~ 최대 ${s.max}%`),
      h("div", { class: "cbars" }, s.bins.map((n, i) => h("div", { class: "bar-col" }, h("div", { class: "bar", style: { height: Math.max(4, (n / max) * 80) + "px" }, title: `${n}건` }), h("small", null, `${i * 20}-${i * 20 + 20}%`), h("small", null, `${n}건`)))),
      h("p", { class: "note" }, "결정례마다 사실관계가 달라 통계는 참고용입니다. 내 사건의 결과를 예측하거나 보장하지 않습니다.")));
  };

  const draw = () => {
    sectorTabs.replaceChildren(h("span", { class: "note" }, "분야"), ...["", ...SECTORS].map((s) => h("button", { type: "button", class: "pill" + (st.sector === s ? " on" : ""), onclick: () => { st.sector = s; sync(); } }, s || "전체")),
      h("span", { class: "sep" }), ...["", "인용", "일부인용", "기각"].map((r) => h("button", { type: "button", class: "pill" + (st.result === r ? " on" : ""), onclick: () => { st.result = r; sync(); } }, r || "결과 전체")));
    examples.replaceChildren(h("span", { class: "note" }, "예시"), ...EXAMPLES.map((e) => h("button", { type: "button", class: "pill", onclick: () => { st.q = e; st.sim = ""; box.input.value = e; sync(); } }, e)));
    if (!items.length) {
      out.replaceChildren(card("사례를 준비하고 있습니다", "info", h("div", { class: "stack" },
        h("p", null, "검색 기능은 준비되어 있지만, 검수를 마친 분쟁조정 사례 자료가 아직 수록되지 않았습니다. 사실과 다른 사례를 보여 드리지 않기 위해 비워 두었습니다."),
        h("p", { class: "note" }, "그동안은 금융감독원의 공식 사례 목록에서 직접 찾아볼 수 있습니다."),
        h("div", { class: "row-gap" }, h("a", { class: "btn", href: data.decisionsUrl || data.listUrl, target: "_blank", rel: "noopener noreferrer" }, icon("external-link"), "분쟁조정결정례 목록(금융감독원)"),
          h("a", { class: "btn ghost", href: "https://www.fss.or.kr/fss/job/fncCnflCase/list.do?menuNo=201195", target: "_blank", rel: "noopener noreferrer" }, "분쟁조정사례 목록")))));
      return;
    }
    let rows = [], head = null;
    if (st.sim) {
      const base = items.find((d) => d.id === st.sim);
      rows = similar(index, st.sim, { limit: 8 }).filter((r) => filter(r.doc));
      head = base ? h("div", { class: "hint-top" }, icon("git-compare-arrows"), h("span", null, `“${base.title}”와 비슷한 사례 ${rows.length}건`), h("button", { class: "link-more", type: "button", onclick: () => { st.sim = ""; sync(); } }, "닫기")) : null;
    } else if (st.q) rows = search(index, st.q, { filter });
    else rows = items.filter(filter).slice(0, 30).map((doc) => ({ doc, hits: [] }));
    const docs = rows.map((r) => r.doc);
    out.replaceChildren(...[head, h("div", { class: "note" }, st.q || st.sim ? `${rows.length}건` : `전체 ${items.length}건 중 최근 ${rows.length}건`), statsCard(docs),
      ...(rows.length ? rows.map((r) => caseCard(r.doc, r.hits)) : [h("div", { class: "state" }, "비슷한 사례를 찾지 못했습니다. 상품 종류, 문제가 된 일(예: 보험금 거절, 설명 부족)을 바꿔 써 보세요.")])].filter(Boolean));
  };
  root.replaceChildren(h("div", { class: "note-bar" }, icon("info"), h("span", null, `금융감독원이 공개한 민원·분쟁사례를 요약한 참고 도구입니다(분쟁조정위원회 결정례는 순차 수록 예정). 수록 ${items.length}건${data.asOf ? ` · 기준일 ${data.asOf}` : ""}. 법률자문이 아니며 결과를 예측하지 않습니다.`)),
    h("div", { class: "stack" }, box, examples, sectorTabs, out));
  draw();
}
