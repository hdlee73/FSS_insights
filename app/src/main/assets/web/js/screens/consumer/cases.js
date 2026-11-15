// 분쟁조정 사례 검색: 자연어 질문으로 찾고, 비슷한 사례와 배상(인용) 비율 통계를 보여 준다.
// 자료: FSS_insights 레포 consumer-data 브랜치의 disputes.json(금감원 '분쟁조정결정례' 1999년~·'분쟁조정사례' 전체를 매일 모아 요약).
// 받지 못하면 data/consumer/cases.json(사람이 검수한 요약 몇 건)으로 대신한다. 원문은 금융감독원 사이트로 연결한다.
import { setHeader } from "./hdr.js";
import { buildIndex, ratioStats, search, similar } from "../../lib/consumer/search.js";
import { card, dotDate, errorText, h, icon, loadJson, loading, message, searchBox } from "../../core.js";

const EXAMPLES = ["백내장 다초점렌즈 실손", "메신저피싱 비대면 대출", "ELS 설명의무", "보험금 부지급 고지의무"];
const DISPUTES_URL = "https://raw.githubusercontent.com/hdlee73/FSS_insights/consumer-data/disputes.json";
const SECTORS = ["은행ㆍ중소서민", "보험", "금융투자"];
const KINDS = ["분쟁조정결정례", "분쟁조정사례"];
const PAGE = 30;
const FIELDS = { title: 3, issue: 2.5, type: 2, tags: 2, claim: 1.5, claims: 1, facts: 1, reasoning: 1, order: 0.8, tip: 0.6, result: 0.5 };
const normSector = (s) => (/^(은행|중소서민|은행.*서민)/.test(s || "") ? "은행ㆍ중소서민" : s || "기타");

/** 전체 자료를 받는다. 실패하면 앱에 들어 있는 소량 자료로 대신하고 fallback을 표시한다. */
async function loadData() {
  try {
    const d = await loadJson(DISPUTES_URL);
    if (d && Array.isArray(d.items) && d.items.length) return { data: d, fallback: false };
  } catch (e) { /* 아래에서 대체 */ }
  const d = await loadJson("data/consumer/cases.json");
  return { data: d, fallback: true };
}
const RESULT_CLS = { 인용: "green", 일부인용: "blue", 기각: "red" };

export async function render(root, params, ctx) {
  setHeader("분쟁조정 사례 검색", "내 상황과 비슷한 사례와 배상 비율을 찾아봅니다");
  root.append(loading());
  let data, fallback = false;
  try {
    ({ data, fallback } = await loadData());
  } catch (e) {
    root.replaceChildren(message("사례 자료를 불러오지 못했습니다.\n" + errorText(e), { error: true }));
    return;
  }
  const items = (data.items || []).map((d) => ({ ...d, sector: normSector(d.sector) }));
  const index = buildIndex(items, FIELDS);
  const st = { q: (params.q || "").trim(), sector: params.sector || "", result: params.result || "", kind: params.kind || "", sim: params.sim || "", n: PAGE };

  const out = h("div", { class: "stack" });
  const box = searchBox({ placeholder: "상황을 문장으로 써 보세요. 예: 백내장 수술했는데 실손보험금을 안 줘요", value: st.q, onSearch: (v) => { st.q = v; st.sim = ""; sync(); } });
  const sectorTabs = h("div", { class: "chip-row wrap" });
  const examples = h("div", { class: "chip-row wrap" });

  const sync = () => {
    st.n = PAGE;
    ctx.go({ q: st.q, sector: st.sector, result: st.result, kind: st.kind, sim: st.sim }, { replace: true });
  };
  const filter = (d) => (!st.sector || d.sector === st.sector) && (!st.result || d.result === st.result) && (!st.kind || d.kind === st.kind);

  const caseCard = (d, hits = []) => h("section", { class: "case" },
    h("div", { class: "case-h" },
      h("span", { class: "tag blue" }, d.sector || "기타"),
      d.kind ? h("span", { class: "tag" }, d.kind.replace("분쟁조정", "")) : null,
      d.type ? h("span", { class: "tag" }, d.type) : null,
      d.result ? h("span", { class: "tag " + (RESULT_CLS[d.result] || "") }, d.result) : null,
      d.outcome ? h("span", { class: "tag green" }, d.outcome) : null,
      typeof d.ratio === "number" ? h("span", { class: "tag orange" }, `인정 비율 ${d.ratio}%`) : null,
      h("span", { class: "note" }, [d.date ? dotDate(d.date) + " 게시" : "", d.no ? "조정번호 " + d.no : ""].filter(Boolean).join(" · "))),
    h("h4", null, d.title),
    d.issue ? h("p", null, h("b", null, "핵심 쟁점 "), d.issue) : null,
    d.order ? h("p", null, h("b", null, "주문 "), d.order) : null,
    d.claim ? h("p", null, h("b", null, "신청취지 "), d.claim) : null,
    d.facts ? h("p", null, h("b", null, "사실관계 "), d.facts) : null,
    d.claims ? h("p", null, h("b", null, "당사자 주장 "), [d.claims.applicant ? `신청인: ${d.claims.applicant}` : "", d.claims.respondent ? `금융회사: ${d.claims.respondent}` : ""].filter(Boolean).join(" / ")) : null,
    d.reasoning ? h("p", null, h("b", null, d.kind === "분쟁조정결정례" ? "위원회 판단 " : "처리결과 "), d.reasoning) : null,
    d.tip ? h("p", null, h("b", null, "소비자 유의사항 "), d.tip) : null,
    d.outcome && !d.result ? h("p", null, h("b", null, "결과 "), d.outcome) : null,
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
    sectorTabs.replaceChildren(h("span", { class: "note" }, "구분"), ...["", ...KINDS].map((k) => h("button", { type: "button", class: "pill" + (st.kind === k ? " on" : ""), onclick: () => { st.kind = k; sync(); } }, k ? k.replace("분쟁조정", "") : "전체")),
      h("span", { class: "sep" }), h("span", { class: "note" }, "분야"), ...["", ...SECTORS].map((s) => h("button", { type: "button", class: "pill" + (st.sector === s ? " on" : ""), onclick: () => { st.sector = s; sync(); } }, s || "전체")),
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
      rows = similar(index, st.sim, { limit: 40 }).filter((r) => filter(r.doc));
      head = base ? h("div", { class: "hint-top" }, icon("git-compare-arrows"), h("span", null, `“${base.title}”와 비슷한 사례 ${rows.length}건`), h("button", { class: "link-more", type: "button", onclick: () => { st.sim = ""; sync(); } }, "닫기")) : null;
    } else if (st.q) rows = search(index, st.q, { filter, limit: 300 });
    else rows = items.filter(filter).sort((a, b) => String(b.date || "").localeCompare(String(a.date || ""))).map((doc) => ({ doc, hits: [] }));
    const docs = rows.map((r) => r.doc);
    const shown = rows.slice(0, st.n);
    const more = rows.length > shown.length
      ? h("button", { class: "btn", type: "button", onclick: () => { st.n += PAGE; draw(); } }, `더 보기 (${shown.length} / ${rows.length}건)`)
      : null;
    out.replaceChildren(...[head, h("div", { class: "note" }, `${st.q || st.sim ? "검색 결과" : "목록"} ${rows.length}건${rows.length > shown.length ? ` 중 ${shown.length}건 표시` : ""}`), statsCard(docs),
      ...(rows.length ? shown.map((r) => caseCard(r.doc, r.hits)) : [h("div", { class: "state" }, "비슷한 사례를 찾지 못했습니다. 상품 종류, 문제가 된 일(예: 보험금 거절, 설명 부족)을 바꿔 써 보세요.")]), more].filter(Boolean));
  };
  root.replaceChildren(h("div", { class: "note-bar" }, icon("info"), h("span", null, fallback
      ? `최신 분쟁조정 자료를 받지 못해 내장된 ${items.length}건만 보여 드립니다. 잠시 뒤 다시 열어 보세요. 법률자문이 아니며 결과를 예측하지 않습니다.`
      : `금융감독원이 공개한 분쟁조정결정례 ${items.filter((d) => d.kind === "분쟁조정결정례").length}건(1999년~)·분쟁조정사례 ${items.filter((d) => d.kind === "분쟁조정사례").length}건을 요약한 참고 도구입니다${data.asOf ? ` · 기준일 ${data.asOf}` : ""}. 원문과 다를 수 있으니 원문(금융감독원) 링크로 확인하세요. 법률자문이 아니며 결과를 예측하지 않습니다.`)),
    h("div", { class: "stack" }, box, examples, sectorTabs, out));
  draw();
}
