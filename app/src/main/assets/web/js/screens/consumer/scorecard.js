// 금융회사 소비자보호 성적표: 실태평가 등급·민원·제재를 한곳에서 비교한다(자료는 data/scorecard.json).
import { setHeader } from "./hdr.js";
import { card, dotDate, errorText, fmtNum, h, icon, loadJson, loading, message, store } from "../../core.js";

const GRADE_ORDER = ["우수", "양호", "보통", "미흡", "취약"];
const GRADE_CLS = { 우수: "green", 양호: "blue", 보통: "", 미흡: "orange", 취약: "red" };
const SECTORS = ["은행", "보험", "금융투자", "저축은행", "카드", "기타"];

export async function render(root, params, ctx) {
  setHeader("소비자보호 성적표", "금융회사의 소비자보호 등급·민원·제재를 비교합니다");
  root.append(loading());
  let data;
  try {
    data = await loadJson("data/consumer/scorecard.json");
  } catch (e) {
    root.replaceChildren(message("성적표 자료를 불러오지 못했습니다.\n" + errorText(e), { error: true }));
    return;
  }
  const companies = data.companies || [];
  const evalCard = card("금융소비자보호 실태평가 결과(공식 발표)", "badge-check", h("div", { class: "link-list" }, (data.evaluations || []).map((e) =>
    h("a", { class: "link-item slim", href: e.u, target: "_blank", rel: "noopener noreferrer" }, h("b", null, e.title), h("small", null, dotDate(e.date)), icon("external-link")))));
  const srcCard = card("자료 출처", "globe", h("div", { class: "link-list" }, (data.sources || []).map((e) =>
    h("a", { class: "link-item slim", href: e.u, target: "_blank", rel: "noopener noreferrer" }, h("b", null, e.t), icon("external-link")))));

  if (!companies.length) {
    root.replaceChildren(h("div", { class: "grid2" },
      h("div", { class: "stack" },
        card("회사별 성적표를 준비하고 있습니다", "info", h("div", { class: "stack" },
          h("p", null, "회사별 등급·민원 건수·제재 내역을 한 화면에서 비교하는 기능은 만들어 두었지만, 검수한 수치 자료가 아직 수록되지 않았습니다. 틀린 숫자를 보여 드리지 않기 위해 비워 두었습니다."),
          h("p", { class: "note" }, "실태평가 결과는 아래 공식 발표를 확인하세요. 평가 대상 회사는 해마다 달라 전체 회사가 매년 평가받지는 않습니다."))),
        evalCard),
      h("div", { class: "stack" }, srcCard)));
    return;
  }

  const st = { sector: params.sector || "", sort: params.sort || "per10k", pick: store.get("score.pick", []) };
  const out = h("div", { class: "stack" });
  const latest = (c) => (c.complaints || []).slice().sort((a, b) => (a.period < b.period ? 1 : -1))[0];
  const grade = (c) => (c.gradeHistory || []).slice().sort((a, b) => b.year - a.year)[0];
  const draw = () => {
    const rows = companies.filter((c) => !st.sector || c.sector === st.sector).slice().sort((a, b) => {
      if (st.sort === "grade") return GRADE_ORDER.indexOf(grade(a)?.grade) - GRADE_ORDER.indexOf(grade(b)?.grade);
      return (latest(a)?.per10k ?? 1e9) - (latest(b)?.per10k ?? 1e9);
    });
    const picked = companies.filter((c) => st.pick.includes(c.id));
    out.replaceChildren(
      h("div", { class: "chip-row wrap" }, h("span", { class: "note" }, "분야"), ...["", ...SECTORS].map((s) => h("button", { type: "button", class: "pill" + (st.sector === s ? " on" : ""), onclick: () => { st.sector = s; draw(); } }, s || "전체")),
        h("span", { class: "sep" }), h("span", { class: "note" }, "정렬"), ...[["per10k", "민원 적은 순"], ["grade", "등급 좋은 순"]].map(([id, l]) => h("button", { type: "button", class: "pill" + (st.sort === id ? " on" : ""), onclick: () => { st.sort = id; draw(); } }, l))),
      picked.length ? h("div", { class: "grid3" }, picked.map((c) => compareCard(c, latest(c), grade(c)))) : h("p", { class: "note" }, "‘비교에 담기’를 눌러 최대 3곳을 나란히 볼 수 있습니다(이 기기에만 저장)."),
      h("div", { class: "table-wrap" }, h("table", { class: "tbl" },
        h("thead", null, h("tr", null, ["회사", "분야", "실태평가", "민원(최근 분기)", "10만명당", "증감", ""].map((t) => h("th", null, t)))),
        h("tbody", null, rows.map((c) => { const g = grade(c), l = latest(c); return h("tr", null,
          h("td", null, h("b", null, c.name)), h("td", null, c.sector),
          h("td", null, g ? h("span", { class: "tag " + (GRADE_CLS[g.grade] || "") }, `${g.grade} (${g.year})`) : "-"),
          h("td", null, l ? `${fmtNum(l.count)}건 (${l.period})` : "-"), h("td", null, l?.per10k != null ? fmtNum(l.per10k, 2) : "-"),
          h("td", null, l?.yoy != null ? `${l.yoy > 0 ? "+" : ""}${l.yoy}%` : "-"),
          h("td", null, h("button", { class: "btn ghost sm", type: "button", onclick: () => { st.pick = st.pick.includes(c.id) ? st.pick.filter((x) => x !== c.id) : [...st.pick, c.id].slice(-3); store.set("score.pick", st.pick); draw(); } }, st.pick.includes(c.id) ? "빼기" : "비교에 담기"))); })))),
      h("p", { class: "note" }, "규모가 다른 회사의 민원 ‘건수’ 단순 비교는 오해를 부를 수 있어 고객 10만명당 환산 값을 함께 보여 줍니다. 등급과 민원은 서로 다른 기준·시점의 자료입니다.")
    );
  };
  const compareCard = (c, l, g) => card(c.name, "building", h("div", { class: "stack" },
    h("div", null, g ? h("span", { class: "tag " + (GRADE_CLS[g.grade] || "") }, `실태평가 ${g.grade} (${g.year})`) : "평가 없음"),
    l ? h("div", null, `민원 ${fmtNum(l.count)}건 · 10만명당 ${l.per10k != null ? fmtNum(l.per10k, 2) : "-"} (${l.period})`) : null,
    ...(c.sanctions || []).slice(0, 3).map((s) => h("a", { class: "note", href: s.url, target: "_blank", rel: "noopener noreferrer" }, `${dotDate(s.date)} ${s.title}`))));
  draw();
  root.replaceChildren(h("div", { class: "note-bar" }, icon("info"), h("span", null, `자료 기준일 ${data.asOf || "-"} · 금융감독원 등 공개 자료를 정리한 참고용이며 특정 회사의 평판을 판단하는 근거가 아닙니다.`)),
    h("div", { class: "grid2" }, out, h("div", { class: "stack" }, evalCard, srcCard)));
}
