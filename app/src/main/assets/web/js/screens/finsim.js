// 제재 양정·과징금·과태료 시뮬레이터 화면(시안 1: 3단 워크벤치).
// 왼쪽: 검사건·제재건 목록과 합계 / 가운데: 선택한 항목 입력(기관·임원·직원·과징금·과태료 탭) / 오른쪽: 고른 계산 줄의 근거.
// 휴대폰에서는 목록 → 입력 → 근거를 한 화면씩 보여 주고, 각 이동을 브라우저 기록에 남겨 뒤로가기로 돌아온다.
// 계산은 js/lib/finsim/*(순수 함수)에서 하고, 이 파일은 화면 그리기와 입력 반영만 맡는다.
import { setHeader } from "../app.js";
import { dialog, download, h, icon, iconButton, store, tabs, toast } from "../core.js";
import { xlsx } from "../lib/xlsx.js";
import { korUnit, parseAmount, pct, won } from "../lib/finsim/amount.js";
import { ANNEX3, ANNEX3_PARTIAL, annex3ById } from "../lib/finsim/data-annex3.js";
import { ANNEX6, FINES, PENALTIES, shortName } from "../lib/finsim/data-laws.js";
import { FACTORS, FINE_MITIGATIONS } from "../lib/finsim/fine.js";
import { INST, instName, personName } from "../lib/finsim/levels.js";
import { evalCase, evalInspection, fineItem, newCase, newInspection, newPenalty, newPerson, newPersonEntry, normalizeInspection, penaltyItem, SECTORS } from "../lib/finsim/model.js";
import { MOTIVE_HELP, MOTIVES, PENALTY_MITIGATIONS, RESULTS } from "../lib/finsim/penalty.js";
import { getSource } from "../lib/finsim/sources.js";
import { accidentThreshold, GENERAL_COLS, GENERAL_TYPES, INST_GROUNDS, KINDS, ROLES, generalColumn } from "../lib/finsim/yangjeong.js";

const KEY = "finsim.cases";
const TABS = [["inst", "기관"], ["person", "임원·직원"], ["fine", "과징금"], ["pen", "과태료"]];
const EXTRA_MEASURES = ["영업점 폐쇄·영업정지(제3호)", "위법·부당행위 중지(제4호)", "위법내용 공표·게시(제6호)", "계약이전 결정(제5호)"];
const FINE_EXEMPT = ["", "경영개선명령 등으로 납부능력 없음", "같은 행위로 이미 과징금·형벌 등 제재", "기준금액이 경미하고 5영업일 이내 해소", "정당한 사유 있는 법령 오인", "기타 미부과 사유"];
const PEN_EXEMPT = ["", "납부능력 없음", "같은 행위로 이미 과태료·형벌 또는 인가취소·해임권고·면직", "천재지변 등 불가항력", "정당한 사유 있는 법령 오인", "회사 방침에 따른 위반(직원 개인)", "경미한 착오"];

const S = { list: [], inspId: null, sel: "sum", tab: "inst", view: "list", basis: null, root: null, ctx: null };
const mobile = () => matchMedia("(max-width: 860px)").matches;

// ---------- 저장 ----------
function load() {
  const raw = store.get(KEY, []);
  const list = [];
  for (const x of Array.isArray(raw) ? raw : []) {
    try {
      list.push(normalizeInspection(x));
    } catch (e) {
      window.FSS_ERRLOG?.log("load-case", e.message, e.stack);
    }
  }
  if (!list.length) list.push(newInspection(1));
  return list;
}
function save() {
  const insp = cur();
  if (insp) insp.updated = Date.now();
  store.set(KEY, S.list);
}
const cur = () => S.list.find((x) => x.id === S.inspId) || S.list[0];
const curCase = () => cur().cases.find((c) => c.id === S.sel) || null;

// ---------- 화면 진입 ----------
export async function render(root, params, ctx) {
  S.root = root;
  S.ctx = ctx;
  S.list = load();
  applyParams(params);
  draw();
}
export function update(params) {
  applyParams(params);
  draw();
}
export function leave() {
  S.root = null;
}

function applyParams(p) {
  S.inspId = S.list.some((x) => x.id === p.case) ? p.case : S.inspId && S.list.some((x) => x.id === S.inspId) ? S.inspId : S.list[0].id;
  const insp = cur();
  S.sel = p.sel === "sum" || insp.cases.some((c) => c.id === p.sel) ? p.sel : "sum";
  S.tab = TABS.some(([k]) => k === p.tab) ? p.tab : S.tab || "inst";
  S.view = ["list", "edit", "basis"].includes(p.view) ? p.view : "list";
  if (S.view === "basis" && !S.basis) S.view = "edit";
}

/** 화면 상태를 주소에 남긴다. 목록·근거 보기 이동은 기록을 쌓아 뒤로가기가 되게 한다. */
function nav(next, { replace = false } = {}) {
  const p = { case: S.inspId, sel: S.sel, tab: S.tab, view: S.view, ...next };
  S.ctx.go(p, { replace });
}

// ---------- 그리기 ----------
let parts = {};
function draw() {
  if (!S.root) return;
  const insp = cur();
  setHeader("(베타)제재 양정 시뮬레이터", `${insp.name}${insp.company ? " · " + insp.company : ""}`, [
    h("button", { class: "btn ghost sm", type: "button", onclick: exportXlsx, title: "산정 내역을 엑셀로 저장" }, icon("file-spreadsheet"), h("span", { class: "hide-sm" }, "엑셀")),
  ]);
  parts = { tree: h("aside", { class: "fs-tree" }), main: h("section", { class: "fs-main" }), basis: h("aside", { class: "fs-basis" }) };
  const wrap = h("div", { class: "fs", "data-view": S.view }, parts.tree, parts.main, parts.basis);
  set(S.root, wrap);
  drawTree();
  drawMain();
  drawBasis();
}

/** 값만 바뀐 경우: 입력칸은 두고 결과·합계만 다시 그린다(입력 중 포커스 유지). */
let resultFillers = [];
function refresh() {
  save();
  drawTree();
  for (const f of resultFillers) {
    try {
      f();
    } catch (e) {
      window.FSS_ERRLOG?.log("refresh", e.message, e.stack);
    }
  }
}
/** 구조가 바뀐 경우(항목 추가·삭제, 기준 종류 변경 등) */
function rebuild() {
  save();
  const y = parts.main?.scrollTop || 0;
  const pageY = S.root?.closest(".content")?.scrollTop || 0;
  drawTree();
  drawMain();
  if (parts.main) parts.main.scrollTop = y;
  const content = S.root?.closest(".content");
  if (content) content.scrollTop = pageY;
}

// ---------- 왼쪽: 목록 ----------
function drawTree() {
  const insp = cur();
  const sum = evalInspection(insp);
  const pick = h("select", { class: "sel", "aria-label": "검사건 고르기", onchange: (e) => (S.sel = "sum", nav({ case: e.target.value, sel: "sum" })) },
    S.list.map((x) => h("option", { value: x.id, selected: x.id === insp.id }, x.name)));
  const caseRow = (c, i) => {
    const r = sum.cases[i].r;
    const amt = (r.fine?.final || 0) + r.pens.reduce((s, y) => s + (y.res.final || 0), 0);
    const chips = TABS.filter(([k]) => c.use[k]).map(([, l]) => h("span", { class: "fs-chip" }, l));
    return h("button", { type: "button", class: "fs-item" + (S.sel === c.id ? " on" : ""), onclick: () => select(c.id) },
      h("div", { class: "fs-item-t" }, h("b", null, c.title || `제재건 ${i + 1}`), amt ? h("span", { class: "fs-amt" }, korUnit(amt)) : null),
      h("div", { class: "fs-item-s" }, chips, r.inst && !r.inst.error && !r.inst.none ? h("span", { class: "note" }, instName(r.inst.level)) : null));
  };
  set(parts.tree, 
    h("div", { class: "fs-pane-h" }, h("b", null, "검사건"), h("div", { class: "row-gap" },
      iconButton("plus", "새 검사건", addInspection),
      iconButton("pencil", "검사건 이름 바꾸기", renameInspection),
      iconButton("copy", "검사건 복제", copyInspection),
      iconButton("trash-2", "검사건 삭제", deleteInspection))),
    pick,
    h("div", { class: "row-gap fs-io" },
      h("button", { class: "btn ghost sm", type: "button", onclick: importJson }, icon("upload"), "불러오기"),
      h("button", { class: "btn ghost sm", type: "button", onclick: exportJson }, icon("download"), "JSON 저장")),
    h("div", { class: "fs-list" },
      h("button", { type: "button", class: "fs-item sum" + (S.sel === "sum" ? " on" : ""), onclick: () => select("sum") },
        h("div", { class: "fs-item-t" }, icon("gavel"), h("b", null, "검사건 종합")),
        h("div", { class: "fs-item-s note" }, `관련자 ${insp.people.length}명 · 제재건 ${insp.cases.length}건`)),
      h("div", { class: "fs-sub" }, "제재건"),
      insp.cases.map(caseRow),
      h("button", { type: "button", class: "btn ghost sm fs-add", onclick: addCase }, icon("plus"), "제재건 추가")),
    h("div", { class: "fs-total" },
      totalRow("기관", sum.inst.none ? "-" : sum.inst.name),
      totalRow("임원·직원", sum.people.filter((x) => !x.final.none).length ? `${sum.people.filter((x) => !x.final.none).length}명` : "-"),
      totalRow("과징금 합계", sum.fineTotal ? korUnit(sum.fineTotal) : "-"),
      totalRow("과태료 합계", sum.penTotal ? korUnit(sum.penTotal) : "-"),
      sum.errors.length ? h("div", { class: "fs-warn" }, icon("alert-triangle"), `입력 확인 ${sum.errors.length}건`) : null)
  );
}
const totalRow = (k, v) => h("div", { class: "fs-total-r" }, h("span", null, k), h("b", null, v));

function select(sel) {
  S.basis = null;
  nav({ sel, view: "edit" });
}

// ---------- 가운데: 입력 ----------
function drawMain() {
  resultFillers = [];
  const insp = cur();
  const back = mobile() ? h("button", { class: "btn ghost sm fs-back", type: "button", onclick: () => history.back() }, icon("chevron-left"), "목록") : null;
  if (S.sel === "sum") {
    set(parts.main, back, ...summaryView(insp));
    return;
  }
  const c = curCase();
  if (!c) {
    set(parts.main, back, h("div", { class: "state" }, "제재건을 골라 주세요."));
    return;
  }
  const enabled = TABS.filter(([k]) => c.use[k]);
  if (!enabled.some(([k]) => k === S.tab) && enabled.length) S.tab = enabled[0][0];
  const toggles = h("div", { class: "chip-row wrap" }, h("span", { class: "note" }, "적용할 조치"),
    TABS.map(([k, l]) => h("label", { class: "pill sm" + (c.use[k] ? " on" : "") },
      h("input", { type: "checkbox", checked: c.use[k], onchange: (e) => {
        c.use[k] = e.target.checked;
        if (e.target.checked) S.tab = k;
        rebuild();
      } }), l)));
  const head = h("section", { class: "card stack" },
    h("div", { class: "fs-row2" },
      field("제재건 이름", textIn(c.title, (v) => (c.title = v || "제재건", refresh()))),
      field("관련 법률·조문", textIn(c.law, (v) => (c.law = v, refresh()), "예: 금융소비자보호법 제19조"))),
    field("위반 사실(메모)", textArea(c.facts, (v) => (c.facts = v, save()))),
    toggles,
    h("div", { class: "row-gap" },
      h("button", { class: "btn ghost sm", type: "button", onclick: () => copyCase(c) }, icon("copy"), "복제"),
      h("button", { class: "btn ghost sm", type: "button", onclick: () => deleteCase(c) }, icon("trash-2"), "삭제")));
  const body = h("div", { class: "stack" });
  if (!enabled.length) add(body, h("div", { class: "state" }, "위에서 적용할 조치(기관·임원·직원·과징금·과태료)를 하나 이상 켜 주세요."));
  else {
    const idx = enabled.findIndex(([k]) => k === S.tab);
    add(body, tabs(enabled.map(([, l]) => l), idx, (i) => {
      S.tab = enabled[i][0];
      nav({ tab: S.tab }, { replace: true });
    }));
    add(body, ...({ inst: instTab, person: personTab, fine: fineTab, pen: penTab }[S.tab])(insp, c));
  }
  set(parts.main, back, head, body);
}

// ---- 검사건 종합 ----
function summaryView(insp) {
  const info = h("section", { class: "card stack" }, h("div", { class: "ch" }, icon("building-2"), h("h3", null, "검사건 정보")),
    h("div", { class: "fs-row2" },
      field("검사건 이름", textIn(insp.name, (v) => (insp.name = v || "검사건", refresh()))),
      field("금융회사", textIn(insp.company, (v) => (insp.company = v, refresh()), "예: ○○은행"))),
    h("div", { class: "fs-row2" },
      field("권역", selectIn(SECTORS.map((x) => [x, x]), insp.sector, (v) => (insp.sector = v, save()))),
      field("검사 기준일", h("input", { type: "date", value: insp.date || "", onchange: (e) => (insp.date = e.target.value, save()) }))),
    h("div", { class: "fs-row2" },
      field("자기자본(최직근 분기말)", amountIn(insp.equity, (v) => (insp.equity = v, refresh())), "기관경고 금융사고 손실 기준(규정 제17조 제7호 다목)에 씁니다."),
      field("자본금·자본총액 중 큰 금액", amountIn(insp.capital, (v) => (insp.capital = v, refresh())), "과징금·과태료의 자본 10% 상한(별표2·별표3)에 씁니다.")));
  const th = accidentThreshold(Number(insp.equity));
  if (th) add(info, h("p", { class: "note" }, `금융사고 손실 기준: 자기자본 2%(최소 10억) ${korUnit(th.a)} 또는 ${korUnit(th.b)} 중 하나를 넘으면 기관경고 사유입니다.`));

  const people = h("section", { class: "card stack" },
    h("div", { class: "ch" }, icon("list"), h("h3", null, "관련자(임원·직원)"), h("div", { class: "ch-acts" },
      h("button", { class: "btn ghost sm", type: "button", onclick: () => (insp.people.push(newPerson(insp.people.length + 1)), rebuild()) }, icon("plus"), "관련자 추가"))),
    insp.people.length ? null : h("p", { class: "note" }, "관련자를 먼저 추가한 뒤, 각 제재건의 '임원·직원' 탭에서 그 사람을 연결합니다. 같은 사람이 여러 제재건에 관련되면 검사건 종합에서 가장 무거운 제재를 기준으로 경합·이력 가중을 합니다."),
    insp.people.map((p) => personCard(insp, p)));

  const instAdj = h("section", { class: "card stack" }, h("div", { class: "ch" }, icon("scale"), h("h3", null, "기관제재 종합 조정")),
    h("div", { class: "fs-row2" },
      field("최근 3년 기관주의 이상 제재 횟수", numIn(insp.instHist.count3y, (v) => (insp.instHist.count3y = v, refresh()), 0, 20), "2회 이상이면 1단계 가중(규정 제24조제1항)"),
      field("정상참작 감경 단계", stepSel(insp.instAdj.r23Steps, (v) => (insp.instAdj.r23Steps = v, refresh())), "규정 제23조제1항")),
    check("같은 검사 4건 이상 경합 가중에서 제외(목적·수단 관계, 실질적으로 하나의 행위 등)", insp.instAdj.excl24_2, (v) => (insp.instAdj.excl24_2 = v, refresh())),
    check("같은 행위에 과징금·과태료를 부과하여 감경(규정 제23조제2항)", insp.instAdj.r23_2, (v) => (insp.instAdj.r23_2 = v, refresh())),
    check("내부통제 우수 기관 감경(세칙 제50조의4, 별표9)", insp.instAdj.d50_4, (v) => (insp.instAdj.d50_4 = v, refresh())));

  const result = h("section", { class: "card stack fs-result" });
  const fill = () => {
    const sum = evalInspection(insp);
    set(result, h("div", { class: "ch" }, icon("clipboard-check"), h("h3", null, "종합 결과")));
    if (sum.errors.length) add(result, h("div", { class: "fs-warn-box" }, h("b", null, "입력 확인"), h("ul", { class: "sim-ul" }, sum.errors.map((e) => h("li", null, e)))));
    add(result, h("h4", { class: "fs-h4" }, "기관"));
    if (sum.inst.none) add(result, h("p", { class: "note" }, "기관제재 대상 제재건이 없습니다."));
    else add(result, finalBlock(sum.inst, "inst", "기관"));
    add(result, h("h4", { class: "fs-h4" }, "임원·직원"));
    const ppl = sum.people.filter((x) => !x.final.none);
    if (!ppl.length) add(result, h("p", { class: "note" }, "제재건에 연결된 관련자가 없습니다."));
    for (const x of ppl) add(result, finalBlock(x.final, x.person.kind, `${x.person.name}${x.person.position ? " " + x.person.position : ""} (${x.person.kind})`, x.results));
    add(result, h("h4", { class: "fs-h4" }, "과징금·과태료"));
    const rows = [];
    for (const { c, r } of sum.cases) {
      if (r.fine) rows.push([c.title, "과징금", itemLabel(fineItem(c.fine.itemId)), r.fine.final, r.fine]);
      for (const y of r.pens) rows.push([c.title, `과태료${y.pen.target ? " · " + y.pen.target : ""}`, itemLabel(penaltyItem(y.pen.itemId)), y.res.final, y.res]);
    }
    if (!rows.length) add(result, h("p", { class: "note" }, "과징금·과태료를 켠 제재건이 없습니다."));
    else add(result, h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
      h("thead", null, h("tr", null, h("th", null, "제재건"), h("th", null, "구분"), h("th", null, "조항"), h("th", { class: "num" }, "금액"))),
      h("tbody", null, rows.map(([t, k, it, v, res]) => h("tr", { class: "click", onclick: () => showBasis({ title: `${t} ${k}`, note: it, ids: [...new Set(res.lines.flatMap((l) => l.src || []))] }) },
        h("td", null, t), h("td", null, k), h("td", null, it), h("td", { class: "num" }, won(v)))),
        h("tr", { class: "bold" }, h("td", { colspan: 3 }, "과징금 합계"), h("td", { class: "num" }, won(sum.fineTotal))),
        h("tr", { class: "bold" }, h("td", { colspan: 3 }, "과태료 합계"), h("td", { class: "num" }, won(sum.penTotal)))))));
  };
  resultFillers.push(fill);
  fill();
  return [info, people, instAdj, result];
}

function personCard(insp, p) {
  const officer = p.kind !== "직원";
  return h("div", { class: "fs-person" },
    h("div", { class: "fs-row3" },
      field("이름", textIn(p.name, (v) => (p.name = v || "관련자", refresh()))),
      field("구분", selectIn(KINDS.map((k) => [k, k]), p.kind, (v) => (p.kind = v, rebuild()))),
      field("직위", textIn(p.position, (v) => (p.position = v, refresh()), "예: 부행장"))),
    h("div", { class: "fs-row3" },
      officer ? field("최근 3년 문책경고 이상", numIn(p.hist.heavy3y, (v) => (p.hist.heavy3y = v, refresh()), 0, 20)) : field("최근 3년 제재 횟수", numIn(p.hist.total3y, (v) => (p.hist.total3y = v, refresh()), 0, 20), "2회 이상이면 1단계 가중(규정 제25조①)"),
      officer ? field("최근 3년 주의적경고·주의", numIn(p.hist.light3y, (v) => (p.hist.light3y = v, refresh()), 0, 20), "문책경고 이상 1회 또는 이것 2회 이상이면 가중(제24조의2③)") : h("div"),
      field("감면 사유 감경(세칙 제50조①)", stepSel(p.adj.d50Steps, (v) => (p.adj.d50Steps = v, refresh())))),
    h("div", { class: "fs-row3" },
      field("정상참작 감경(규정 제23조①)", stepSel(p.adj.r23Steps, (v) => (p.adj.r23Steps = v, refresh()))),
      h("div", { class: "stack fs-checks" },
        officer ? check("주의적경고 이상 받은 주된 행위자의 재위반", p.hist.repeatMain, (v) => (p.hist.repeatMain = v, refresh())) : null,
        check("과징금·과태료 부과로 감경", p.adj.r23_2, (v) => (p.adj.r23_2 = v, refresh()))),
      h("div", { class: "stack fs-checks" },
        check("공적 감경(세칙 제50조②)", p.adj.merit, (v) => (p.adj.merit = v, refresh())),
        check("범죄 관련(공적 감경 제외)", p.adj.crime, (v) => (p.adj.crime = v, refresh())),
        check("경합 가중 제외(관련된 행위)", p.adj.noConcurrent, (v) => (p.adj.noConcurrent = v, refresh())))),
    h("div", { class: "row-gap" }, h("span", { class: "note" }, `관련 제재건 ${insp.cases.filter((c) => c.people.some((e) => e.pid === p.id)).length}건`),
      h("button", { class: "btn ghost sm", type: "button", onclick: async () => {
        if (!(await dialog({ title: "관련자 삭제", text: `${p.name}을(를) 지우면 모든 제재건에서 빠집니다.`, ok: "삭제", danger: true }))) return;
        insp.people = insp.people.filter((x) => x.id !== p.id);
        for (const c of insp.cases) c.people = c.people.filter((e) => e.pid !== p.id);
        rebuild();
      } }, icon("trash-2"), "삭제")));
}

/** 최종 양정 블록(기관·사람 공통) */
function finalBlock(f, kind, title, results) {
  const scale = kind === "inst" ? "inst" : "person";
  const nm = (lv) => (scale === "inst" ? instName(lv) : personName(lv, kind));
  const ids = [...new Set([...(f.from?.res?.basis || []), ...f.basis])];
  return h("div", { class: "fs-final click", onclick: () => showBasis({ title: `${title} 최종 양정`, note: `${nm(f.heaviest)}에서 ${f.net > 0 ? f.net + "단계 가중" : f.net < 0 ? -f.net + "단계 감경" : "조정 없음"} → ${f.name}`, ids }) },
    h("div", { class: "fs-final-t" }, h("b", null, title), h("span", { class: "fs-badge lv" + Math.min(5, f.level) }, f.name)),
    results && results.length > 1 ? h("div", { class: "note" }, "제재건별: " + results.map((x) => `${x.caseTitle} ${x.res.error ? "입력 확인" : nm(x.res.level)}`).join(" · ")) : null,
    h("ol", { class: "fs-steps" },
      h("li", null, `가장 무거운 양정: ${nm(f.heaviest)} (${f.from?.caseTitle || ""})`),
      f.steps.map((s) => h("li", { class: s.steps > 0 ? "up" : "down" }, `${s.steps > 0 ? "▲ +" : "▼ "}${s.steps}단계 ${s.label}`)),
      h("li", null, h("b", null, `최종: ${f.name}`))),
    f.notes?.length ? h("ul", { class: "sim-ul note" }, f.notes.map((n) => h("li", null, n))) : null);
}

// ---- 기관 탭 ----
function instTab(insp, c) {
  const st = c.inst;
  const modes = [["grounds", "제17조 사유"], ["annex3", "별표3 구체기준"], ["manual", "직접 선택"]];
  const box = h("section", { class: "card stack" },
    h("p", { class: "note" }, "세칙 별표3에 기관 기준이 있는 위반이면 '별표3 구체기준', 없으면 규정 제17조 사유로 판단합니다."),
    tabs(modes.map((x) => x[1]), Math.max(0, modes.findIndex((x) => x[0] === st.mode)), (i) => (st.mode = modes[i][0], st.pick = null, rebuild()), "seg"));
  if (st.mode === "grounds") {
    add(box, h("div", { class: "sim-reasons" }, INST_GROUNDS.map((g) => check(g.label, !!st.grounds[g.key], (v) => (st.grounds[g.key] = v, st.pick = null, rebuild())))));
    add(box, h("div", { class: "fs-row2" },
      field("최근 1년 금융사고 손실(예상)금액", amountIn(st.loss, (v) => (st.loss = v, st.pick = null, rebuild())), insp.equity ? "검사건 정보의 자기자본으로 다목(1) 기준을 판정합니다." : "검사건 종합에서 자기자본을 넣으면 자동 판정합니다."),
      h("div", { class: "stack fs-checks" }, check("정상참작 사유가 크거나 위반 정도가 경미(기관주의, 제9호)", st.mitig, (v) => (st.mitig = v, rebuild())))));
  } else if (st.mode === "annex3") {
    add(box, ...annexPicker(st, "i", rebuild));
  } else {
    add(box, field("기관제재", selectIn(INST.map((n, i) => [i, n]), st.manual ?? 4, (v) => (st.manual = Number(v), refresh()))));
  }
  const res = caseInst(insp, c);
  if (res && res.range && res.range.hi !== res.range.lo) add(box, field("범위 안에서 고르기", selectIn(levelsIn(res.range, "inst"), res.base, (v) => (st.pick = Number(v), refresh())), `기준: ${res.rangeText}`));
  add(box, h("div", { class: "stack" }, h("span", { class: "note" }, "함께 검토할 조치(규정 제17조, 금융위원회 건의 사항 포함)"),
    h("div", { class: "chip-row wrap" }, EXTRA_MEASURES.map((m) => h("label", { class: "pill sm" + (st.extra?.includes(m) ? " on" : "") },
      h("input", { type: "checkbox", checked: !!st.extra?.includes(m), onchange: (e) => {
        st.extra = e.target.checked ? [...(st.extra || []), m] : (st.extra || []).filter((x) => x !== m);
        rebuild();
      } }), m)))));
  const out = h("section", { class: "card stack fs-result" });
  const fill = () => {
    const r = caseInst(insp, c);
    set(out, h("div", { class: "ch" }, icon("gavel"), h("h3", null, "이 제재건의 기관 양정")));
    if (r.error) return add(out, h("div", { class: "fs-warn-box" }, r.error));
    if (r.none) return add(out, h("p", null, "해당 사유가 없어 기관제재 대상이 아닙니다."), ...(r.notes || []).map((n) => h("p", { class: "note" }, n)));
    add(out, levelLine("기관", r.name + (r.detail ? ` ${r.detail}` : ""), r.text, r.basis, r.rangeText),
      ...(r.notes || []).map((n) => h("p", { class: "note" }, n)),
      st.extra?.length ? h("p", { class: "note" }, "병과 검토: " + st.extra.join(", ")) : null,
      h("p", { class: "note" }, "최근 3년 이력·경합 가중과 감경은 '검사건 종합'에서 모든 제재건을 합쳐 반영합니다."));
  };
  resultFillers.push(fill);
  fill();
  return [box, out];
}
const caseInst = (insp, c) => evalCase(insp, { ...c, use: { inst: true } }).inst;

// ---- 임원·직원 탭 ----
function personTab(insp, c) {
  const linked = new Set(c.people.map((e) => e.pid));
  const free = insp.people.filter((p) => !linked.has(p.id));
  const addSel = h("select", { class: "sel", "aria-label": "관련자 연결" }, h("option", { value: "" }, "관련자 고르기…"), free.map((p) => h("option", { value: p.id }, `${p.name} (${p.kind})`)), h("option", { value: "__new" }, "+ 새 관련자"));
  addSel.addEventListener("change", () => {
    let pid = addSel.value;
    if (!pid) return;
    if (pid === "__new") {
      const p = newPerson(insp.people.length + 1);
      insp.people.push(p);
      pid = p.id;
      toast("새 관련자를 만들었습니다. 이름·이력은 '검사건 종합'에서 고칩니다.");
    }
    c.people.push(newPersonEntry(pid));
    rebuild();
  });
  const top = h("section", { class: "card stack" },
    h("div", { class: "row-gap" }, addSel),
    h("p", { class: "note" }, "세칙 별표3에 해당 위반의 구체기준이 있으면 '별표3 구체기준'을, 없으면 '일반기준(별표2)'을 고릅니다. 보조자·감독자는 행위자보다 1~3단계 감경할 수 있습니다(세칙 제52조제3항)."));
  const cards = c.people.map((e) => {
    const p = insp.people.find((x) => x.id === e.pid);
    if (!p) return null;
    const box = h("section", { class: "card stack" },
      h("div", { class: "ch" }, icon("list"), h("h3", null, `${p.name} (${p.kind})`), h("div", { class: "ch-acts" },
        iconButton("x", "이 제재건에서 빼기", () => (c.people = c.people.filter((x) => x !== e), rebuild())))),
      h("div", { class: "fs-row2" },
        field("역할", selectIn(ROLES.map((r) => [r, r]), e.role, (v) => (e.role = v, e.pick = null, rebuild()))),
        e.role === "보조자" || e.role === "감독자" ? field("역할 감경(행위자 대비)", stepSel(e.roleSteps, (v) => (e.roleSteps = v, refresh())), "역할별 열이 있는 별표3 표는 이미 반영되어 겹치지 않습니다.") : h("div")),
      tabs(["일반기준(별표2)", "별표3 구체기준"], e.mode === "annex3" ? 1 : 0, (i) => (e.mode = i ? "annex3" : "general", e.pick = null, e.itemAdj = [], rebuild()), "seg"));
    if (e.mode === "general") {
      add(box, field("위반 유형", selectIn(GENERAL_TYPES.map((t, i) => [i + 1, t]), e.gType, (v) => (e.gType = Number(v), e.pick = null, rebuild()))),
        h("div", { class: "fs-row2" },
          field("위반 정도", tabs(["심함", "약함"], e.degree === "약함" ? 1 : 0, (i) => (e.degree = i ? "약함" : "심함", e.pick = null, rebuild()), "seg")),
          field("고의·과실", tabs(["고의", "중과실", "경과실"], Math.max(0, ["고의", "중과실", "경과실"].indexOf(e.fault)), (i) => (e.fault = ["고의", "중과실", "경과실"][i], e.pick = null, rebuild()), "seg"))),
        h("p", { class: "note" }, `적용 열: ${GENERAL_COLS[generalColumn(e.degree, e.fault)]}`));
    } else {
      add(box, ...annexPicker(e, "p", rebuild));
      const it = annex3ById(e.item);
      if (it?.adj?.length) add(box, h("div", { class: "sim-reasons" }, it.adj.map((a, k) => check(a.label, e.itemAdj?.includes(k), (v) => {
        e.itemAdj = v ? [...(e.itemAdj || []), k] : (e.itemAdj || []).filter((x) => x !== k);
        refresh();
      }))));
    }
    const res = evalCase(insp, { ...c, use: { person: true }, people: [e] }).people[0]?.res;
    if (res?.range && res.range.hi !== res.range.lo) add(box, field("범위 안에서 고르기", selectIn(levelsIn(res.range, "person", p.kind), res.base, (v) => (e.pick = Number(v), refresh())), `기준: ${res.rangeText}`));
    const out = h("div", { class: "fs-result-line" });
    const fill = () => {
      const r = evalCase(insp, { ...c, use: { person: true }, people: [e] }).people[0]?.res;
      if (!r) return set(out, );
      if (r.error) return set(out, h("div", { class: "fs-warn-box" }, r.error));
      set(out, levelLine(`${p.name} ${e.role}`, r.name, r.text, [...r.basis, ...r.steps.map((s) => s.src)], r.rangeText, r.steps));
    };
    resultFillers.push(fill);
    fill();
    add(box, out);
    return box;
  });
  if (!c.people.length) cards.push(h("div", { class: "state" }, insp.people.length ? "위에서 관련자를 연결해 주세요." : "관련자를 고르거나 '+ 새 관련자'로 만들어 주세요."));
  cards.push(h("p", { class: "note" }, "같은 사람의 여러 제재건은 '검사건 종합'에서 가장 무거운 제재를 기준으로 경합·이력 가중과 감경을 합산해 최종 양정을 냅니다(규정 제24조의2·제25조·제25조의2)."));
  return [top, ...cards.filter(Boolean)];
}

let tierSeq = 0;
/** 별표3 항목·유형·구간 고르기. scale: 'p'(임직원) | 'i'(기관) — 그 기준이 있는 항목만 보여 준다. */
function annexPicker(st, scale, onChange) {
  const has = (it) => it.groups.some((g) => g.rows.some((r) => (scale === "i" ? r.i : r.p || r.pr)));
  const items = ANNEX3.filter(has);
  if (!items.some((x) => x.id === st.item)) {
    st.item = items[0].id;
    st.group = 0;
    st.row = 0;
  }
  const it = annex3ById(st.item);
  const groups = it.groups.map((g, i) => [i, g]).filter(([, g]) => g.rows.some((r) => (scale === "i" ? r.i : r.p || r.pr)));
  if (!groups.some(([i]) => i === st.group)) {
    st.group = groups[0][0];
    st.row = 0;
  }
  const g = it.groups[st.group];
  const sectors = [...new Set(items.map((x) => x.sector))];
  const itemSel = h("select", { class: "sel", onchange: (e) => (st.item = e.target.value, st.group = 0, st.row = 0, st.pick = null, st.itemAdj = [], onChange()) },
    sectors.map((sec) => h("optgroup", { label: sec }, items.filter((x) => x.sector === sec).map((x) => h("option", { value: x.id, selected: x.id === st.item }, `${x.code} ${x.title}`)))));
  const rows = g.rows.map((r, i) => {
    const text = scale === "i" ? r.i : r.pr ? `행위자 ${r.pr.행위자} · 보조자 ${r.pr.보조자} · 감독자 ${r.pr.감독자}` : r.p;
    const disabled = !(scale === "i" ? r.i : r.p || r.pr);
    return h("label", { class: "sim-reason fs-tier" + (st.row === i ? " on" : "") + (disabled ? " off" : "") },
      h("input", { type: "radio", name: "tier-" + Math.random().toString(36).slice(2, 6), checked: st.row === i, disabled, onchange: () => (st.row = i, st.pick = null, onChange()) }),
      h("span", null, h("b", null, r.c), h("br"), h("span", { class: "note" }, text || "해당 기준 없음")));
  });
  const name = "tier-" + ++tierSeq; // 같은 항목을 고른 카드가 여럿이어도 라디오 묶음이 섞이지 않게
  rows.forEach((x) => (x.querySelector("input").name = name));
  return [
    field("별표3 항목", itemSel),
    groups.length > 1 ? field("유형", selectIn(groups.map(([i, gg]) => [i, gg.name]), st.group, (v) => (st.group = Number(v), st.row = 0, st.pick = null, onChange()))) : h("p", { class: "note" }, g.name),
    h("div", { class: "sim-reasons" }, rows),
    it.note ? h("p", { class: "note" }, "※ " + it.note) : null,
    h("button", { type: "button", class: "btn ghost sm fs-src", onclick: () => showBasis({ title: `별표3 ${it.code} ${it.title}`, note: "고른 항목의 구체기준 전체", ids: ["A3:" + it.id, "D46"] }) }, icon("book-marked"), "이 항목 기준표 보기"),
    h("p", { class: "note" }, `원문을 다 옮기지 못한 항목(${ANNEX3_PARTIAL.join(", ")})은 일반기준으로 계산하고 원문을 확인해 주세요.`),
  ];
}

// ---- 과징금 탭 ----
function fineTab(insp, c) {
  const f = c.fine;
  const it = fineItem(f.itemId);
  const box = h("section", { class: "card stack" },
    field("과징금 조항", lawPicker(FINES, f.itemId, (v) => (f.itemId = v, rebuild()))));
  if (it) {
    const basisLabel = it.kind === "fixed" ? null : `기준금액(${it.basis || "위반금액, 위반기간 중 최대"})`;
    add(box, h("p", { class: "note" }, `${it.law} ${it.jo} · `, it.kind === "fixed" ? `정액 한도 ${won(it.cap)}` : `${it.basis || "기준금액"} × ${pct(it.rate)}${it.kind === "full" ? " (그 금액 이내)" : ""}${it.fallback ? ` (산정 곤란 시 ${korUnit(it.fallback)} 이하)` : ""}`));
    if (it.note) add(box, h("p", { class: "note fs-warn" }, it.note));
    if (basisLabel) add(box, h("div", { class: "fs-row2" }, field(basisLabel, amountIn(f.amount, (v) => (f.amount = v, refresh()))),
      it.fallback ? h("div", { class: "stack fs-checks" }, check(`${it.basis || "기준금액"}이 없거나 산정 곤란`, f.revenueUnknown, (v) => (f.revenueUnknown = v, refresh()))) : h("div")));
  }
  add(box, h("h4", { class: "fs-h4" }, "부과기준율 평가(별표2 제4호)"),
    h("div", { class: "fs-factors" }, FACTORS.map(([k, label, w]) => h("div", { class: "fs-factor" }, h("span", null, `${label} `, h("small", { class: "note" }, `×${w}`)),
      tabs(["상", "중", "하"], Math.max(0, ["상", "중", "하"].indexOf(f.scores[k] || "중")), (i) => (f.scores[k] = ["상", "중", "하"][i], rebuildKeep()), "seg")))),
    field("특별한 사유 조정", selectIn([-0.1, -0.05, 0, 0.05, 0.1].map((x) => [x, x ? `${x > 0 ? "+" : ""}${Math.round(x * 100)}%p` : "없음"]), f.special || 0, (v) => (f.special = Number(v), refresh()))),
    h("h4", { class: "fs-h4" }, "가중(별표2 제5호)"),
    h("div", { class: "fs-row2" },
      field("부당이득", amountIn(f.gain, (v) => (f.gain = v, refresh())), "기본과징금보다 크면 그 차액을 가중"),
      field("보험 특별이익 제공액", amountIn(f.specialBenefit, (v) => (f.specialBenefit = v, refresh())))),
    check("특별이익은 약속만 함(약속금액의 50%)", f.promisedOnly, (v) => (f.promisedOnly = v, refresh())),
    h("h4", { class: "fs-h4" }, "감경(별표2 제5호)"),
    h("div", { class: "fs-row2" }, FINE_MITIGATIONS.map(([k, label, cap]) => field(label, pctSel(f.mit[k], cap, (v) => (f.mit[k] = v, refresh()))))),
    h("h4", { class: "fs-h4" }, "부과과징금 결정(별표2 제6호)"),
    h("div", { class: "fs-row2" },
      field("현저히 과중·부담능력 등 감경", pctSel(f.heavyCut, 0.5, (v) => (f.heavyCut = v, refresh()))),
      field("피해배상액", amountIn(f.damages, (v) => (f.damages = v, refresh())))),
    h("div", { class: "fs-row2" },
      field("자본금·자본총액 중 큰 금액(이 건만)", amountIn(f.capital, (v) => (f.capital = v, refresh())), insp.capital ? `비우면 검사건 값 ${korUnit(insp.capital)}` : "검사건 종합에서 넣거나 여기서 넣습니다."),
      field("미부과 사유", selectIn(FINE_EXEMPT.map((x) => [x, x || "없음"]), f.exempt || "", (v) => (f.exempt = v, refresh())))));
  const out = h("section", { class: "card stack fs-result" });
  const fill = () => {
    const r = evalCase(insp, { ...c, use: { fine: true } }).fine;
    set(out, h("div", { class: "ch" }, icon("calculator"), h("h3", null, "과징금 산정")));
    if (r.errors.length) add(out, h("div", { class: "fs-warn-box" }, r.errors.join(" ")));
    add(out, calcTable(r.lines, "과징금"));
    if (!r.errors.length) add(out, h("div", { class: "sim-total" }, "부과과징금 ", h("b", null, won(r.final))));
  };
  resultFillers.push(fill);
  fill();
  return [box, out];
}
/** 탭 버튼처럼 다시 그려야 보이는 입력: 스크롤을 지키며 다시 그린다. */
const rebuildKeep = () => rebuild();

// ---- 과태료 탭 ----
function penTab(insp, c) {
  const top = h("section", { class: "card row-gap" },
    h("button", { class: "btn sm", type: "button", onclick: () => (c.pens.push(newPenalty()), rebuild()) }, icon("plus"), "과태료 항목 추가"),
    h("span", { class: "note" }, "위반행위별로 각각 부과합니다. 한 행위가 여러 조항에 걸리면 가장 무거운 조항 하나만 넣습니다(별표3)."));
  const cards = c.pens.map((p, n) => {
    const it = penaltyItem(p.itemId);
    const table = it?.annex6 ? ANNEX6[it.annex6] : null;
    const box = h("section", { class: "card stack" },
      h("div", { class: "ch" }, icon("scale"), h("h3", null, `과태료 ${n + 1}${p.target ? " · " + p.target : ""}`), h("div", { class: "ch-acts" },
        iconButton("copy", "복제", () => (c.pens.splice(n + 1, 0, { ...structuredClone(p), id: newPenalty().id }), rebuild())),
        iconButton("trash-2", "삭제", () => (c.pens = c.pens.filter((x) => x !== p), rebuild())))),
      field("과태료 조항", lawPicker(PENALTIES, p.itemId, (v) => {
        p.itemId = v;
        p.annexResult = null;
        const ni = penaltyItem(v);
        if (ni?.person) p.subject = ni.decreeBy?.임직원 != null ? "임직원" : "개인"; // 임직원·설계사 대상 조항은 개인으로 시작
        if (p.subject === "임직원" && !subjectsOf(ni).includes("임직원")) p.subject = "개인";
        rebuild();
      })),
      it ? h("p", { class: "note" }, `${it.law} ${it.jo} · 법정최고금액 ${won(it.max)} · ${decreeNote(it)}${it.inspection ? " · 검사 거부·방해는 결과 '중대'" : ""}`) : null,
      it?.note ? h("p", { class: "note fs-warn" }, it.note) : null,
      h("div", { class: "fs-row3" },
        field("부과 대상", textIn(p.target, (v) => (p.target = v, refresh()), "예: ○○은행, 홍길동")),
        field("부과 대상 구분", tabs(subjectsOf(it).map((x) => (x === "개인" ? "법인 아닌 자" : x)), Math.max(0, subjectsOf(it).indexOf(p.subject)), (i) => (p.subject = subjectsOf(it)[i], rebuild()), "seg")),
        field(table ? table.count : "위반 건수", numIn(p.count, (v) => (p.count = Math.max(1, v), refresh()), 1, 100000))),
      it?.stepsBy ? field("위반 차수(최근 3년 같은 위반 과태료 처분 기준)", tabs(["1차", "2차", "3차 이상"], Math.max(0, Math.min(2, (p.step || 1) - 1)), (i) => (p.step = i + 1, rebuild()), "seg")) : null,
      it?.byAmount ? h("div", { class: "fs-row2" },
        field(`위반금액(신고 등을 하지 않은 금액, 시행령 별표4 비고1)`, amountIn(p.violation, (v) => (p.violation = v, refresh()))),
        it.byAmount.hard ? h("div", { class: "stack fs-checks" }, check("위반금액 산정 곤란(200만원)", p.violationUnknown, (v) => (p.violationUnknown = v, refresh()))) : h("div")) : null,
      field("기준금액 직접 입력(비우면 시행령 금액 또는 법정최고금액)", amountIn(p.base, (v) => (p.base = v, refresh()))),
      field("위반 동기", tabs(MOTIVES.map((m) => `${m}`), Math.max(0, MOTIVES.indexOf(p.motive)), (i) => (p.motive = MOTIVES[i], rebuild()), "seg"), MOTIVE_HELP[p.motive]),
      table
        ? field(`위반 결과(별표6 ${table.title})`, selectIn(table.results.map((r) => [r[0], `${r[0]} — ${r[1]}`]), p.annexResult || table.results[0][0], (v) => (p.annexResult = v, refresh())))
        : field("위반 결과", tabs(RESULTS, Math.max(0, RESULTS.indexOf(p.result)), (i) => (p.result = RESULTS[i], refresh()), "seg"), "중대: 금융거래자·시장에 중대한 영향, 보통, 경미: 영향이 미미"),
      h("div", { class: "fs-row2" },
        field("특별한 사유 조정", selectIn([-0.1, -0.05, 0, 0.05, 0.1].map((x) => [x, x ? `${x > 0 ? "+" : ""}${Math.round(x * 100)}%p` : "없음"]), p.special || 0, (v) => (p.special = Number(v), refresh()))),
        field("가중: 최근 5년 같은 위반", pctSel(p.agg5y, 0.2, (v) => (p.agg5y = v, refresh())))),
      h("div", { class: "fs-row2" }, PENALTY_MITIGATIONS.map(([k, label, cap]) => field(label, pctSel(p.mit[k], cap, (v) => (p.mit[k] = v, refresh()))))),
      check("(5) 같은 유형 합계가 법정최고금액 10배를 넘으면 초과분 감경", p.sameTypeCap, (v) => (p.sameTypeCap = v, refresh())),
      it?.law === "자본시장과 금융투자업에 관한 법률" && it.jo === "제449조" && it.title.includes("제63조") ? field("(9) 최대투자원금", amountIn(p.maxInvest, (v) => (p.maxInvest = v, refresh()))) : null,
      field("면제 사유", selectIn(PEN_EXEMPT.map((x) => [x, x || "없음"]), p.exempt || "", (v) => (p.exempt = v, refresh()))),
      p.subject === "법인" && insp.capital ? h("p", { class: "note" }, `자본 10% 상한(감경 (6)): 검사건의 자본금·자본총액 ${korUnit(insp.capital)} 기준`) : null);
    const out = h("div", { class: "stack" });
    const fill = () => {
      const r = evalCase(insp, { ...c, use: { pen: true }, pens: [p] }).pens[0].res;
      set(out, );
      if (r.errors.length) return add(out, h("div", { class: "fs-warn-box" }, r.errors.join(" ")));
      add(out, calcTable(r.lines, "과태료"), h("div", { class: "sim-total" }, "부과과태료 ", h("b", null, won(r.final))));
    };
    resultFillers.push(fill);
    fill();
    add(box, out);
    return box;
  });
  if (!c.pens.length) cards.push(h("div", { class: "state" }, "과태료 항목을 추가해 주세요."));
  return [top, ...cards];
}

// ---------- 계산 줄 표 ----------
function calcTable(lines, kind) {
  return h("div", { class: "tbl-wrap" }, h("table", { class: "tbl fs-calc" },
    h("tbody", null, lines.map((l) => h("tr", { class: "click" + (l.label.startsWith("부과") || l.label.startsWith("조정") || l.label.startsWith("기본") ? " bold" : ""), title: "눌러서 근거 보기", onclick: () => showBasis({ title: `${kind}: ${l.label}`, note: [l.value != null ? won(l.value) : null, l.note].filter(Boolean).join(" · "), ids: l.src || [] }) },
      h("td", null, l.label, l.note ? h("div", { class: "note" }, l.note) : null),
      h("td", { class: "num" + (l.value < 0 ? " neg" : "") }, l.value == null ? "" : won(l.value)),
      h("td", { class: "fs-src-i" }, icon("book-marked")))))));
}

function levelLine(who, name, text, ids, rangeText, steps = []) {
  return h("div", { class: "fs-final click", onclick: () => showBasis({ title: `${who} 양정`, note: `기준: ${text}${steps.length ? " / 조정: " + steps.map((s) => `${s.steps > 0 ? "+" : ""}${s.steps} ${s.label}`).join(", ") : ""}`, ids: [...new Set(ids)] }) },
    h("div", { class: "fs-final-t" }, h("b", null, who), h("span", { class: "fs-badge" }, name)),
    h("div", { class: "note" }, `적용 기준: ${text}${rangeText && rangeText !== name ? ` (범위 ${rangeText})` : ""}`),
    steps.length ? h("ol", { class: "fs-steps" }, steps.map((s) => h("li", { class: s.steps > 0 ? "up" : "down" }, `${s.steps > 0 ? "▲ +" : "▼ "}${s.steps}단계 ${s.label}`))) : null,
    h("div", { class: "note fs-hint" }, icon("book-marked"), "눌러서 근거 보기"));
}

// ---------- 오른쪽: 근거 ----------
function showBasis(b) {
  S.basis = b;
  if (mobile()) nav({ view: "basis" });
  else drawBasis();
}
function drawBasis() {
  const b = S.basis;
  const back = mobile() ? h("button", { class: "btn ghost sm fs-back", type: "button", onclick: () => history.back() }, icon("chevron-left"), "입력으로") : null;
  const head = h("div", { class: "fs-pane-h" }, h("b", null, "적용 근거"));
  if (!b) {
    set(parts.basis, back, head,
      h("p", { class: "note" }, "가운데의 계산 줄이나 양정 결과를 누르면 여기에 적용한 규정·세칙·법률 조문의 요지와 원문 링크가 나옵니다."),
      ...["R_A2", "R_A3", "D_A2", "R25_2"].map(srcCard));
    return;
  }
  const ids = [...new Set(b.ids || [])].filter(Boolean);
  set(parts.basis, back, head,
    h("div", { class: "fs-basis-sel" }, h("b", null, b.title), b.note ? h("div", { class: "note" }, b.note) : null),
    ids.length ? ids.map(srcCard) : h("p", { class: "note" }, "이 줄은 입력값을 그대로 옮긴 것입니다."),
    h("p", { class: "note" }, "요지는 원문을 줄인 것입니다. 판단에는 링크의 국가법령정보센터 원문을 확인해 주세요."));
  if (!mobile()) parts.basis.scrollTop = 0;
}
function srcCard(id) {
  const s = getSource(id);
  if (!s) return h("div", { class: "fs-src-card" }, h("b", null, id), h("p", { class: "note" }, "근거 자료를 찾지 못했습니다."));
  return h("div", { class: "fs-src-card" },
    h("div", { class: "note" }, `${s.doc}${s.ver ? " · " + s.ver : ""}`),
    h("b", null, s.title),
    h("p", { class: "fs-src-text" }, s.text),
    h("a", { href: s.url, target: "_blank", rel: "noopener", class: "fs-link" }, icon("external-link"), "원문 보기(국가법령정보센터)"));
}

// ---------- 입력 도우미 ----------
/** null·false를 건너뛰고 붙인다(DOM append는 null을 "null" 글자로 붙이므로). */
function add(el, ...kids) {
  el.append(...kids.flat(Infinity).filter((x) => x != null && x !== false));
}
function set(el, ...kids) {
  el.replaceChildren(...kids.flat(Infinity).filter((x) => x != null && x !== false));
}
function field(label, input, help) {
  // 단추 묶음(탭)을 label로 감싸면 글자를 눌렀을 때 첫 단추가 눌리므로, 입력칸 하나일 때만 label을 쓴다.
  const tag = input instanceof HTMLInputElement || input instanceof HTMLSelectElement || input instanceof HTMLTextAreaElement ? "label" : "div";
  return h(tag, { class: "field" }, h("span", null, label), input, help ? h("small", { class: "note" }, help) : null);
}
function textIn(value, onChange, placeholder = "") {
  return h("input", { type: "text", value: value || "", placeholder, onchange: (e) => onChange(e.target.value.trim()) });
}
function textArea(value, onChange) {
  const t = h("textarea", { rows: 2, onchange: (e) => onChange(e.target.value) });
  t.value = value || "";
  return t;
}
function selectIn(options, value, onChange) {
  return h("select", { onchange: (e) => onChange(e.target.value) }, options.map(([v, l]) => h("option", { value: String(v), selected: String(v) === String(value) }, l)));
}
function numIn(value, onChange, min = 0, max = 1e9) {
  return h("input", { type: "number", inputmode: "numeric", min, max, step: 1, value: value ?? 0, onchange: (e) => {
    const n = Math.floor(Number(e.target.value));
    const v = Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : min;
    e.target.value = v;
    onChange(v);
  } });
}
/** 금액 입력: "81억", "3억5천만", "12,000,000" 모두 받는다. 아래에 한글 단위를 보여 준다. */
function amountIn(value, onChange) {
  const hint = h("small", { class: "note fs-amt-hint" }, value ? won(value) : "예: 81억, 3억5천만, 12,000,000");
  const input = h("input", { type: "text", inputmode: "decimal", value: value ? Number(value).toLocaleString("ko-KR") : "", placeholder: "금액(원)", onchange: (e) => {
    const raw = e.target.value.trim();
    if (!raw) {
      hint.textContent = "예: 81억, 3억5천만, 12,000,000";
      input.classList.remove("bad");
      return onChange(null);
    }
    const n = parseAmount(raw);
    if (n == null) {
      input.classList.add("bad");
      hint.textContent = "금액을 읽지 못했습니다. 숫자나 '억·만' 단위로 넣어 주세요.";
      return;
    }
    input.classList.remove("bad");
    e.target.value = n.toLocaleString("ko-KR");
    hint.textContent = won(n);
    onChange(n);
  } });
  return h("div", { class: "fs-amount" }, input, hint);
}
function check(label, checked, onChange) {
  return h("label", { class: "sim-reason" }, h("input", { type: "checkbox", checked: !!checked, onchange: (e) => onChange(e.target.checked) }), h("span", null, label));
}
function stepSel(value, onChange) {
  return selectIn([[0, "없음"], [1, "1단계"], [2, "2단계"], [3, "3단계"]], value || 0, (v) => onChange(Number(v)));
}
function pctSel(value, cap, onChange) {
  const opts = [];
  for (let x = 0; x <= cap + 1e-9; x += cap <= 0.2 ? 0.05 : 0.1) opts.push([Math.round(x * 100) / 100, x ? pct(x) : "없음"]);
  return selectIn(opts, Math.round((value || 0) * 100) / 100, (v) => onChange(Number(v)));
}
/** 과태료 부과 대상 구분: 시행령이 임직원 금액을 따로 정하면 임직원도 고를 수 있다. */
function subjectsOf(it) {
  return it?.decreeBy?.임직원 != null ? ["법인", "개인", "임직원"] : ["법인", "개인"];
}
function decreeNote(it) {
  if (it.byAmount) return `시행령 개별기준 ${it.byAmount.min ? `${korUnit(it.byAmount.min)}과 ` : ""}위반금액의 ${pct(it.byAmount.rate)}${it.byAmount.min ? " 중 큰 금액" : ""}`;
  if (it.stepsBy) return `시행령 개별기준 법인 ${it.stepsBy.법인.map(korUnit).join("/")} · 법인 아닌 자 ${it.stepsBy.개인.map(korUnit).join("/")} (1차/2차/3차 이상)`;
  if (!it.decreeBy) return "시행령 개별금액 없음(법정최고금액 기준)";
  if (!it.split) return `시행령 개별기준 ${won(it.decreeBy.법인)}`;
  return `시행령 개별기준 법인 ${won(it.decreeBy.법인)} · 법인 아닌 자 ${won(it.decreeBy.개인)}${it.decreeBy.임직원 != null ? ` · 임직원 ${won(it.decreeBy.임직원)}` : ""}`;
}
const itemLabel = (x) => (x ? `${shortName(x.law)} ${x.jo} ${x.title}` : "-");
const pickerLaw = new Map(); // 목록별로 마지막에 고른 법률(조항을 아직 안 고른 카드의 시작값)

/** 조항 고르기: 법률 → 키워드 → 조항. 목록이 길어 select 하나 대신 걸러 보며 고른다. */
function lawPicker(list, value, onChange) {
  const cur = list.find((x) => x.id === value) || null;
  const laws = [...new Set(list.map((x) => x.law))];
  let law = cur?.law ?? pickerLaw.get(list) ?? "";
  let q = "";
  const itemSel = h("select", { "aria-label": "조항", onchange: (e) => onChange(e.target.value || null) });
  const opt = (x) => h("option", { value: x.id, selected: x.id === value }, law ? `${x.jo} ${x.title}` : itemLabel(x));
  const fill = () => {
    const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const items = list.filter((x) => (!law || x.law === law) && words.every((w) => `${shortName(x.law)} ${x.law} ${x.jo} ${x.title}`.toLowerCase().includes(w)));
    const head = h("option", { value: "" }, items.length ? `조항 고르기… (${items.length}개)` : "맞는 조항이 없습니다");
    const rest = law ? items.map(opt) : laws.filter((l) => items.some((x) => x.law === l)).map((l) => h("optgroup", { label: shortName(l) }, items.filter((x) => x.law === l).map(opt)));
    if (cur && !items.includes(cur)) rest.unshift(h("option", { value: cur.id, selected: true }, `(현재) ${itemLabel(cur)}`));
    itemSel.replaceChildren(head, ...rest);
  };
  const lawSel = h("select", { "aria-label": "법률", onchange: (e) => { law = e.target.value; pickerLaw.set(list, law); fill(); } },
    h("option", { value: "", selected: !law }, `전체 법률 (${list.length})`),
    laws.map((l) => h("option", { value: l, selected: l === law }, `${shortName(l)} (${list.filter((x) => x.law === l).length})`)));
  const search = h("input", { type: "search", "aria-label": "조항 검색", placeholder: "조문·키워드 (예: 85조, 검사 거부)", oninput: (e) => { q = e.target.value; fill(); } });
  fill();
  return h("div", { class: "fs-picker" }, lawSel, search, itemSel);
}
function levelsIn(range, scale, kind) {
  const out = [];
  for (let lv = range.hi; lv <= range.lo; lv++) out.push([lv, scale === "inst" ? instName(lv) : personName(lv, kind)]);
  return out;
}

// ---------- 검사건 관리 ----------
function addInspection() {
  const x = newInspection(S.list.length + 1);
  S.list.push(x);
  save();
  nav({ case: x.id, sel: "sum", view: "edit" });
}
async function renameInspection() {
  const insp = cur();
  const r = await dialog({ title: "검사건 이름 바꾸기", fields: [{ name: "name", label: "이름", value: insp.name }] });
  if (!r || !r.name) return;
  insp.name = r.name;
  save();
  draw();
}
function copyInspection() {
  const insp = cur();
  const x = normalizeInspection({ ...structuredClone(insp), id: newInspection().id, name: insp.name + " (복사)" });
  S.list.push(x);
  save();
  nav({ case: x.id, sel: "sum" });
  toast("검사건을 복제했습니다.");
}
async function deleteInspection() {
  const insp = cur();
  if (!(await dialog({ title: "검사건 삭제", text: `'${insp.name}'과(와) 그 안의 제재건을 모두 지웁니다. 되돌릴 수 없으니 필요하면 먼저 JSON으로 저장해 주세요.`, ok: "삭제", danger: true }))) return;
  S.list = S.list.filter((x) => x !== insp);
  if (!S.list.length) S.list.push(newInspection(1));
  save();
  nav({ case: S.list[0].id, sel: "sum", view: "list" }, { replace: true });
}
function addCase() {
  const insp = cur();
  const c = newCase(insp.cases.length + 1);
  insp.cases.push(c);
  save();
  S.tab = "inst";
  nav({ sel: c.id, tab: "inst", view: "edit" });
}
function copyCase(c) {
  const insp = cur();
  const x = { ...structuredClone(c), id: newCase().id, title: c.title + " (복사)" };
  x.pens = x.pens.map((p) => ({ ...p, id: newPenalty().id }));
  insp.cases.splice(insp.cases.indexOf(c) + 1, 0, x);
  save();
  nav({ sel: x.id });
}
async function deleteCase(c) {
  const insp = cur();
  if (!(await dialog({ title: "제재건 삭제", text: `'${c.title}'을(를) 지웁니다.`, ok: "삭제", danger: true }))) return;
  insp.cases = insp.cases.filter((x) => x !== c);
  if (!insp.cases.length) insp.cases.push(newCase(1));
  save();
  nav({ sel: "sum" }, { replace: true });
}

// ---------- 파일 ----------
const safeName = (s) => (s || "검사건").replace(/[\\/:*?"<>|]+/g, "_").slice(0, 60);
function exportJson() {
  const insp = cur();
  download(new Blob([JSON.stringify({ app: "FSS_Helper finsim", exported: new Date().toISOString(), inspection: insp }, null, 2)], { type: "application/json" }), `${safeName(insp.name)}.json`);
}
function importJson() {
  const input = h("input", { type: "file", accept: "application/json,.json" });
  input.addEventListener("change", async () => {
    const file = input.files?.[0];
    if (!file) return;
    if (file.size > 5e6) return toast("파일이 너무 큽니다(5MB 이하).");
    try {
      const data = JSON.parse(await file.text());
      const x = normalizeInspection(data.inspection || data);
      if (S.list.some((y) => y.id === x.id)) x.id = newInspection().id;
      S.list.push(x);
      save();
      nav({ case: x.id, sel: "sum" });
      toast(`'${x.name}'을(를) 불러왔습니다.`);
    } catch (e) {
      toast("불러오지 못했습니다: " + (e.message || "형식 오류"));
    }
  });
  input.click();
}
function exportXlsx() {
  const insp = cur();
  const sum = evalInspection(insp);
  const nm = (lv, kind) => (kind === "inst" ? instName(lv) : personName(lv, kind));
  const rows1 = [["검사건", insp.name], ["금융회사", insp.company], ["권역", insp.sector], ["검사 기준일", insp.date], ["자기자본", insp.equity ?? ""], ["자본금·자본총액", insp.capital ?? ""], [], ["기관 최종", sum.inst.none ? "-" : sum.inst.name]];
  if (!sum.inst.none) for (const s of sum.inst.steps) rows1.push(["  조정", `${s.steps > 0 ? "+" : ""}${s.steps} ${s.label}`]);
  for (const x of sum.people.filter((y) => !y.final.none)) {
    rows1.push([`${x.person.name} (${x.person.kind})`, x.final.name]);
    rows1.push(["  가장 무거운 양정", `${nm(x.final.heaviest, x.person.kind)} (${x.final.from?.caseTitle || ""})`]);
    for (const s of x.final.steps) rows1.push(["  조정", `${s.steps > 0 ? "+" : ""}${s.steps} ${s.label}`]);
  }
  rows1.push([], ["과징금 합계", sum.fineTotal], ["과태료 합계", sum.penTotal]);
  if (sum.errors.length) rows1.push([], ["입력 확인", sum.errors.join(" / ")]);
  const rows2 = [];
  for (const { c, r } of sum.cases) {
    if (r.inst && !r.inst.error && !r.inst.none) rows2.push([c.title, "기관", r.inst.text, r.inst.name, "", (r.inst.basis || []).map((id) => getSource(id)?.title).filter(Boolean).join(", ")]);
    for (const y of r.people) rows2.push([c.title, `${y.person.name} ${y.entry.role}`, y.res.text || y.res.error, y.res.name || "", "", (y.res.basis || []).map((id) => getSource(id)?.title).filter(Boolean).join(", ")]);
    if (r.fine) for (const l of r.fine.lines) rows2.push([c.title, "과징금", l.label, l.note || "", l.value ?? "", (l.src || []).map((id) => getSource(id)?.title).filter(Boolean).join(", ")]);
    for (const y of r.pens) for (const l of y.res.lines) rows2.push([c.title, `과태료${y.pen.target ? " " + y.pen.target : ""}`, l.label, l.note || "", l.value ?? "", (l.src || []).map((id) => getSource(id)?.title).filter(Boolean).join(", ")]);
  }
  const blob = xlsx([
    { name: "종합", header: ["항목", "내용"], rows: rows1, widths: [28, 70] },
    { name: "산정내역", header: ["제재건", "구분", "단계", "설명", "금액(원)", "근거"], rows: rows2, widths: [16, 18, 30, 50, 16, 50] },
  ]);
  download(blob, `${safeName(insp.name)}_제재시뮬레이션.xlsx`);
}
