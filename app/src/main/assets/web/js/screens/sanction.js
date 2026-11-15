// 금융회사 제재현황: 2011년 이후 금감원 제재공시(공시마다 조치·지적제목·위반 조항·원문보기)와 통계, 엑셀 저장.
// 위반 조항을 누르면 스마트 법령검색이 그 조문으로 열린다. companyPane()은 금융회사 주요 현황의 '최근 제재내역' 탭에서 쓴다.
import { go, setHeader } from "../app.js";
import { dialog, download, errorText, h, icon, iconButton, loading, message, openUrl, tabs, toast } from "../core.js";
import { dartQuery } from "../lib/accdata.js";
import {
  FIELDS, PERIODS, SECTORS, NOTICE, actionLine, casesFromRows, casesOf, coverageNote, exportXlsx, filterRows, loadSanctions, periodLabel, periodRange, presetOf, scopeLabel, summarize, whyText, won,
} from "../lib/sanctions.js";

const PAGE = 40;
let filter = { from: null, to: null, sector: null, q: "", field: "all" };
const dot = (d) => d.replace(/-/g, ".");

// 제재공시의 권역·회사명으로 금융회사 주요 현황(FISIS)의 권역 코드를 짐작한다(어느 권역인지 모호하면 null).
function fisisSector(c) {
  const n = c.company || "";
  switch (c.sector) {
    case "은행": return "A";
    case "금융지주": return "L";
    case "증권": return "F";
    case "운용": return "G";
    case "저축은행": return "E";
    case "부신사": return "M";
    case "자문일임": return "ADV";
    case "보험": return /생명/.test(n) ? "H" : /손해|화재|해상/.test(n) ? "I" : null;
    case "여신전문": return /카드/.test(n) ? "C" : null;
    default: return null;
  }
}
const fisisHref = (c) => {
  const code = fisisSector(c);
  const q = dartQuery(c.company);
  return code && q ? `#/fisis?sector=${code}&q=${encodeURIComponent(q)}` : null;
};

/** 공시 한 건 카드. matched: 검색어에 걸린 지적사항(굵게). link: 금융회사 주요 현황 바로가기 표시 */
function caseCard(c, matched = new Set(), link = true) {
  const acts = [];
  const inst = actionLine(c.inst);
  if (inst) acts.push(["기관", inst]);
  if (!c.inst && c.fine + c.penalty > 0) acts.push(["금전", [c.fine > 0 ? `과태료 ${won(c.fine)}` : null, c.penalty > 0 ? `과징금 ${won(c.penalty)}` : null].filter(Boolean).join(", ")]);
  const ex = actionLine(c.exec);
  if (ex) acts.push(["임원", ex]);
  const sf = actionLine(c.staff);
  if (sf) acts.push(["직원", sf]);
  return h("article", { class: "card sx" },
    h("div", { class: "sx-head" },
      h("div", null, h("b", null, c.company), h("div", { class: "note" }, [c.sector, dot(c.date), c.dept].filter(Boolean).join(" · "))),
      h("div", { class: "row-gap" },
        link && fisisHref(c) ? h("a", { class: "btn ghost sm", href: fisisHref(c), title: "금융회사 주요 현황(FISIS)에서 이 회사 찾기" }, "금융회사 현황") : null,
        h("button", { class: "btn ghost sm", type: "button", onclick: () => openUrl(c.viewUrl) }, "원문보기"))),
    h("div", { class: "sx-h" }, "[조치내용]"),
    acts.length ? acts.map(([l, t]) => h("div", { class: "sx-act" }, h("span", null, l), t)) : h("p", { class: "note" }, "표시할 조치내용이 없습니다. 원문보기로 확인해 주세요."),
    h("div", { class: "sx-h sx-h2" }, "[지적사항]"),
    c.ocr && c.items.length ? h("p", { class: "note", style: { color: "#b26a00" } }, "스캔 문서를 글자 인식(OCR)으로 읽었습니다. 숫자·법조문이 원문과 다를 수 있으니 원문보기로 확인하세요.") : null,
    c.items.length
      ? h("ol", { class: "sx-items" }, c.items.map((it) => h("li", { class: matched.has(it) ? "hit" : "" },
        it.category ? `[${it.category}] ` : "", it.title,
        it.laws.length ? [" (", it.laws.map((law, j) => [j ? ", " : "", h("a", { href: "#/law?q=" + encodeURIComponent(law), title: `${law} 스마트 법령검색` }, law)]), ")"] : null)))
      : h("p", { class: "note" }, whyText(c)));
}

export async function render(root, params, ctx) {
  const stats = params.view === "stats";
  const dlBtn = iconButton("download", "엑셀로 저장", () => exportNow());
  const rfBtn = iconButton("refresh-cw", "새로고침", async () => {
    rfBtn.disabled = true;
    await loadSanctions(true).catch((e) => toast(errorText(e)));
    ctx.go(params, { replace: true });
  });
  setHeader("금융회사 제재 공시", "2011년 이후 금감원 제재공시와 통계", [dlBtn, rfBtn]);
  root.append(tabs(["제재 공시", "통계"], stats ? 1 : 0, (i) => ctx.go({ view: i ? "stats" : null }, { replace: true })));

  // 검색 조건
  const fieldRow = h("div", { class: "row-gap", style: { margin: "12px 0 6px" } });
  const input = h("input", { type: "search", value: filter.q });
  const searchRow = h("div", { class: "search" }, icon("search"), input);
  const periodRow = h("div", { class: "row-gap", style: { margin: "8px 0 6px" } });
  const sectorRow = h("div", { class: "row-gap" });
  const resetRow = h("div");
  const body = h("div", { style: { marginTop: "12px" } }, loading("2011년 이후 제재공시를 받는 중입니다…"));
  root.append(fieldRow, searchRow, periodRow, sectorRow, resetRow, body);

  let data;
  try {
    data = await loadSanctions();
  } catch (e) {
    return body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go(params, { replace: true }) }));
  }
  if (!ctx.alive()) return;
  let rows = [];
  let summary = null;

  const pill = (label, on, onclick) => h("button", { class: "pill sm" + (on ? " on" : ""), type: "button", onclick }, label);
  function drawFilters() {
    fieldRow.replaceChildren(h("span", { class: "note" }, "검색"), ...FIELDS.map(([k, l]) => pill(l, filter.field === k, () => set({ field: k }))));
    input.placeholder = FIELDS.find(([k]) => k === filter.field)[2];
    const preset = presetOf(filter);
    periodRow.replaceChildren(...PERIODS.map(([k, l]) => pill(k === "all" ? `전체(${data.fromYear}~)` : l, preset === k, () => {
      const [a, b] = periodRange(k);
      set({ from: a, to: b });
    })), pill(preset ? "기간 지정…" : "기간: " + periodLabel(filter, data.fromYear), !preset, pickPeriod));
    sectorRow.replaceChildren(pill("전 권역", !filter.sector, () => set({ sector: null })), ...SECTORS.map((s) => pill(s, filter.sector === s, () => set({ sector: filter.sector === s ? null : s }))));
    const dirty = filter.from != null || filter.to != null || filter.sector || filter.q.trim() || filter.field !== "all";
    resetRow.replaceChildren(dirty ? h("button", { class: "btn ghost sm", type: "button", style: { marginTop: "8px" }, onclick: () => ((input.value = ""), set({ from: null, to: null, sector: null, q: "", field: "all" })) }, icon("rotate-ccw"), "조건 초기화") : "");
  }
  function set(patch) {
    filter = { ...filter, ...patch };
    drawFilters();
    draw();
  }
  let timer = 0;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => set({ q: input.value }), 250);
  });

  async function pickPeriod() {
    const now = new Date();
    const years = [];
    for (let y = now.getFullYear(); y >= data.fromYear; y--) years.push(y);
    const sel = (opts, v, unit) => {
      const s = h("select", { class: "sel" }, opts.map((o) => h("option", { value: o }, o + unit)));
      s.value = String(v);
      return s;
    };
    const fy = sel(years, Math.floor((filter.from || data.fromYear * 100 + 1) / 100), "년");
    const fm = sel([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], (filter.from || 1) % 100 || 1, "월");
    const ty = sel(years, Math.floor((filter.to || now.getFullYear() * 100) / 100), "년");
    const tm = sel([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12], filter.to ? filter.to % 100 : now.getMonth() + 1, "월");
    const ok = await dialog({
      title: "기간 지정",
      body: [h("div", { class: "note" }, "조치요구일 기준으로 통계·세부내역·엑셀 저장에 함께 적용됩니다."),
        h("div", { class: "row-gap" }, h("span", { style: { width: "36px" } }, "시작"), fy, fm),
        h("div", { class: "row-gap" }, h("span", { style: { width: "36px" } }, "끝"), ty, tm)],
      ok: "적용",
    });
    if (!ok) return;
    let a = +fy.value * 100 + +fm.value;
    let b = +ty.value * 100 + +tm.value;
    if (a > b) [a, b] = [b, a];
    set({ from: a, to: b });
  }

  function exportNow() {
    if (!rows.length) return toast("저장할 내역이 없습니다.");
    const period = periodLabel(filter, data.fromYear).replace(/[^0-9A-Za-z가-힣.~]/g, "").replace("~", "-");
    const d = new Date();
    const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
    download(exportXlsx(rows, summary, scopeLabel(filter, data), data.generatedAt), `제재현황_${period}_${stamp}.xlsx`);
    toast(`엑셀로 저장했습니다 (${rows.length.toLocaleString("ko-KR")}행)`);
  }

  function draw() {
    rows = filterRows(data, filter);
    summary = summarize(rows, data, filter);
    if (stats) return drawStats();
    const cases = casesFromRows(rows);
    const byCase = new Map();
    if (filter.q.trim() && filter.field !== "company") for (const r of rows) if (r.item) (byCase.get(r.c.id) || byCase.set(r.c.id, new Set()).get(r.c.id)).add(r.item);
    const list = h("div", { class: "stack" });
    const more = h("button", { class: "btn ghost", type: "button", style: { width: "100%" } });
    let shown = 0;
    const page = () => {
      list.append(...cases.slice(shown, shown + PAGE).map((c) => caseCard(c, byCase.get(c.id))));
      shown += PAGE;
      more.hidden = shown >= cases.length;
      more.textContent = `더 보기 (${(cases.length - shown).toLocaleString("ko-KR")}건 남음)`;
    };
    more.addEventListener("click", page);
    page();
    body.replaceChildren(
      h("div", { class: "section-label" }, h("span", null, `공시 ${cases.length.toLocaleString("ko-KR")}건 · 지적 ${summary.itemCount.toLocaleString("ko-KR")}건 · ${periodLabel(filter, data.fromYear)}`),
        h("div", { class: "acts" }, h("button", { class: "btn ghost sm", type: "button", onclick: exportNow }, icon("download"), "엑셀 저장"))),
      cases.length ? list : message("조건에 맞는 공시가 없습니다."),
      more,
      h("p", { class: "note", style: { marginTop: "12px" } }, `자료 기준 ${data.generatedAt.slice(0, 16).replace("T", " ")} · 금감원 공시를 자동으로 정리한 참고용 자료입니다.`));
  }

  function toList(patch) {
    filter = { ...filter, ...patch };
    ctx.go({ view: null });
  }

  function bars(values, onClick, trailing) {
    if (!values.length) return h("p", { class: "note" }, "해당 없음");
    const max = Math.max(1, ...values.map(([, v]) => v));
    return h("div", { class: "bars" }, values.map(([label, v]) => h("div", { class: "bar-row" + (onClick ? " click" : ""), onclick: onClick ? () => onClick(label) : null },
      h("span", { class: "bl" }, label), h("span", { class: "bt" }, h("i", { style: { width: (v / max) * 100 + "%" } })), h("span", { class: "bv" }, v.toLocaleString("ko-KR")), trailing ? trailing(label) : null)));
  }
  const statCard = (title, hint, content) => h("section", { class: "card" }, h("div", { class: "section-label", style: { marginTop: 0 } }, h("span", { style: { color: "var(--fg)" } }, title), hint ? h("div", { class: "acts note", style: { fontWeight: 400 } }, hint) : null), content);

  function drawStats() {
    const s = summary;
    const kpi = (l, v) => h("div", { class: "kpi" }, h("small", null, l), h("b", null, v));
    const years = s.byYear;
    const maxY = Math.max(1, ...years.map(([, n]) => n));
    body.replaceChildren(
      h("div", { class: "infobox" },
        h("b", null, `${data.fromYear}년 이후 금감원 제재공시 기준`),
        h("div", { class: "note" }, `범위: ${scopeLabel(filter, data)} · 자료 기준 ${data.generatedAt.slice(0, 16).replace("T", " ")}`),
        h("div", { class: "note", style: { marginTop: "4px" } }, NOTICE)),
      coverageNote(s) ? h("div", { class: "infobox", style: { background: "#fff4e0", color: "#6b4300", marginTop: "10px" } }, h("b", null, "통계 미반영 안내"), h("div", { class: "note", style: { color: "#6b4300" } }, coverageNote(s))) : null,
      h("div", { class: "kpis", style: { margin: "12px 0" } }, kpi("공시", s.caseCount.toLocaleString("ko-KR") + "건"), kpi("지적사항", s.itemCount.toLocaleString("ko-KR") + "건"),
        kpi("금전제재 합계", won(s.fine + s.penalty)), kpi("과태료", won(s.fine)), kpi("과징금", won(s.penalty))),
      h("div", { class: "grid2" },
        h("div", { class: "col" },
          years.length > 1 ? statCard("연도별 공시 건수", "누르면 그해 내역", h("div", { class: "ybars" }, years.map(([y, n]) => h("button", { type: "button", title: `${y}년 ${n}건`, onclick: () => toList({ from: y * 100 + 1, to: y * 100 + 12 }) },
            h("span", { class: "n" }, n), h("i", { style: { height: (n / maxY) * 100 + "%" } }), h("span", { class: "y" }, String(y).slice(2)))))) : null,
          statCard("권역별 공시 건수", "누르면 해당 권역 내역", bars(s.bySector, (sec) => toList({ sector: sec }))),
          statCard("기관 조치 종류별", null, bars(s.instKinds.slice(0, 10))),
          statCard("임원 조치 종류별", null, bars(s.execKinds.slice(0, 8))),
          statCard("직원 조치 종류별", null, bars(s.staffKinds.slice(0, 8)))),
        h("div", { class: "col" },
          statCard("자주 지적된 법률", "누르면 해당 내역", bars(s.topLawNames, (l) => toList({ q: l, field: "law" }))),
          statCard("자주 지적된 조항", "누르면 내역, 아이콘은 법령검색", bars(s.topLaws.slice(0, 15), (l) => toList({ q: l, field: "law" }),
            (l) => iconButton("network", `${l} 법령검색`, (e) => (e.stopPropagation(), go("law", { q: l }))))),
          statCard("제재 공시가 많은 회사", "누르면 해당 내역", bars(s.topCompanies, (c) => toList({ q: c, field: "company" }))),
          statCard("금전제재 상위 10건", null, s.topMoney.length ? h("div", { class: "list" }, s.topMoney.map((c, i) => h("div", { class: "item co-row", onclick: () => toList({ q: c.company, field: "company", from: c.year * 100 + 1, to: c.year * 100 + 12 }) },
            h("span", { class: "note", style: { width: "18px" } }, i + 1),
            h("div", { class: "body" }, h("div", { class: "t" }, c.company), h("div", { class: "m" }, `${c.sector} · ${dot(c.date)}`)),
            h("b", { style: { color: "#b4233a", whiteSpace: "nowrap" } }, won(c.fine + c.penalty))))) : h("p", { class: "note" }, "해당 없음")))),
      h("button", { class: "btn ghost", type: "button", style: { marginTop: "14px" }, onclick: exportNow }, icon("download"), "공시 세부내역·통계 엑셀로 저장"));
  }

  drawFilters();
  draw();
}

/** 금융회사 주요 현황 → '최근 제재내역' 탭: 그 회사의 제재공시(최신순). */
export async function companyPane(box, company) {
  box.replaceChildren(loading("제재공시를 찾는 중…"));
  let data;
  try {
    data = await loadSanctions();
  } catch (e) {
    return box.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => companyPane(box, company) }));
  }
  const cases = casesOf(data, company);
  box.replaceChildren(
    cases.length
      ? h("div", { class: "stack" }, h("div", { class: "section-label" }, `${data.fromYear}년 이후 제재공시 ${cases.length}건`), cases.map((c) => caseCard(c, new Set(), false)))
      : message(`${data.fromYear}년 이후 금감원 제재공시에서 ‘${company}’ 이름으로 된 공시를 찾지 못했습니다.\n(회사명이 바뀌었으면 ‘금융회사 제재현황’에서 옛 이름으로 검색해 보세요.)`),
    h("p", { class: "note", style: { marginTop: "12px" } }, "금감원 ‘금융회사 제재공시’를 회사명으로 맞춰 보여 줍니다. 원문과 다를 수 있으니 업무에는 금감원 원문을 확인하세요."));
}
