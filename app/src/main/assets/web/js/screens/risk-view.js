// 금융사 리스크 대시보드 화면(회사 한 곳 원스톱 + 업권 Peer 사분면). 자료 공급원(provider)만 바꿔 끼우면
// 실제 서버 자료(risk-live.js)로도, 시뮬레이션용 스냅샷으로도 같은 화면이 나온다.
//   env = { provider, setHeader(title, sub, actions), go(params), openUrl(url) }
import { h, icon, loading, message, tabs } from "../core.js";
import { histogramSvg, lineChartSvg, quadrantSvg, stackedBarsSvg } from "../lib/riskchart.js";
import { OVERVIEW_ORDER, SECTOR_NAME, distribution, rankSize, ncrBasis, SECTOR_CFG, SECTOR_ORDER, SEVERITY, latestMonth, monthsBack, nameKey, quadrantData, rankOf, sanctionYears, sectorStat, seriesOf, severityOf, signals, valueAt, wonText } from "../lib/risk.js";

const SEV_CLASS = { 3: "red", 2: "orange", 1: "" };
const LEVEL = { warn: ["주의", "red"], info: ["참고", "blue"], ok: ["양호", "green"] };
const dot = (d) => String(d || "").replace(/-/g, ".");
const svgBox = (svg, cls = "") => h("div", { class: "rk-box " + cls, html: svg });
const pctText = (v) => (v == null ? "-" : v.toFixed(2) + "%");
const sectorLabel = (s) => SECTOR_CFG[s]?.label || s;
const section = (title, sub, ...kids) => h("section", { class: "card rk-sec" }, h("div", { class: "rk-sh" }, h("b", null, title), sub ? h("span", { class: "note" }, sub) : null), ...kids);

export async function mountRisk(root, params, env) {
  const { provider } = env;
  const overview = params.view === "overview";
  const sector = SECTOR_CFG[params.sector] || (overview && params.sector === "G") ? params.sector : "A";
  root.append(banner(provider.info));
  if (params.co) return dashboard(root, params, env, sector);
  const view = params.view === "peer" ? 1 : overview ? 2 : 0;
  const vname = ["", "peer", "overview"][view] || null;
  root.append(tabs(["회사 대시보드", "업권 Peer 사분면", "업권 현황"], view, (i) => env.go({ sector: i === 2 || SECTOR_CFG[sector] ? sector : "A", view: ["", "peer", "overview"][i] || null })));
  const order = view === 2 ? OVERVIEW_ORDER : SECTOR_ORDER;
  const pills = h("div", { class: "row-gap", style: { margin: "10px 0" } }, order.map((c) => h("button", { class: "pill" + (c === sector ? " on" : ""), type: "button", onclick: () => env.go({ sector: c, view: vname }) }, SECTOR_NAME[c])));
  root.append(pills);
  if (view === 2) return overviewView(root, params, env, sector);
  return view ? peerView(root, params, env, sector) : landing(root, params, env, sector);
}

function banner(info) {
  if (!info?.label) return "";
  return h("div", { class: "rk-banner" + (info.kind === "live" ? " live" : "") }, icon("info"), h("div", null, h("b", null, info.label), info.detail ? h("div", { class: "note" }, info.detail) : null));
}

// ---------- 시작 화면: 회사 찾기 ----------
async function landing(root, params, env, sector) {
  env.setHeader("금융사 리스크 대시보드", "건전성 추이·지배구조·제재 이력을 한 화면에서", []);
  const input = h("input", { type: "search", placeholder: `${sectorLabel(sector)} 회사 이름 검색`, value: params.q || "" });
  root.append(h("div", { class: "search" }, icon("search"), input));
  const body = h("div", { class: "card list", style: { marginTop: "12px" } }, loading());
  root.append(body);
  let list = [];
  try {
    list = await provider_companies(env, sector);
  } catch (e) {
    return body.replaceChildren(message("회사 목록을 불러오지 못했습니다.", { error: true }));
  }
  const draw = () => {
    const q = nameKey(input.value);
    const shown = list.filter((c) => !q || nameKey(c.name).includes(q)).slice(0, 60);
    body.replaceChildren(...(shown.length ? shown.map((c, i) => h("div", { class: "item co-row", onclick: () => env.go({ sector, co: c.code }) },
      h("span", { class: "avatar" }, c.name.slice(0, 1)),
      h("div", { class: "body" }, h("div", { class: "t" }, c.name), h("div", { class: "m" }, q ? "" : `${i + 1}위 · `, c.assets ? `총자산 ${Math.round(c.assets).toLocaleString("en-US")}억원` : ""))))
      : [message("검색 결과가 없습니다.")]));
  };
  input.addEventListener("input", draw);
  draw();
}
const provider_companies = (env, sector) => env.provider.companies(sector);

// ---------- 업권 Peer 사분면 ----------
async function peerView(root, params, env, sector) {
  const cfg = SECTOR_CFG[sector];
  env.setHeader("업권 Peer 사분면", `${cfg.label} · X: ${cfg.x.label}, Y: ${cfg.y.label}`, []);
  const body = h("div", null, loading(`${cfg.label} 회사 지표를 불러오는 중…`));
  root.append(body);
  let rows;
  try { rows = await env.provider.peers(sector); } catch (e) { return body.replaceChildren(message("업권 자료를 불러오지 못했습니다.", { error: true })); }
  const q = quadrantData(rows, sector);
  body.replaceChildren(
    section(`${cfg.label} 업권 위치 (${monthText(q.month)})`, `회사 ${q.points.length}곳${q.excluded ? ` · 지표 없음 ${q.excluded}곳 제외` : ""}`,
      h("p", { class: "note" }, `가로 ${cfg.x.label}(클수록 양호), 세로 ${cfg.y.label}(${cfg.y.higher ? "클수록" : "작을수록"} 양호). 점선은 업권 중앙값이고, 붉은 영역은 두 지표가 모두 중앙값보다 나쁜 쪽입니다. 점을 누르면 그 회사 대시보드로 이동합니다.`),
      svgBox(quadrantSvg({ points: q.points, xMean: q.xMean, yMean: q.yMean, xLabel: cfg.x.label, yLabel: cfg.y.label, yHigherBetter: cfg.y.higher }), "quad"),
      axisNote(sector)),
    section("취약 구간 회사", `${cfg.x.label} 중앙값 미만 · ${cfg.y.label} ${cfg.y.higher ? "중앙값 미만" : "중앙값 초과"}`,
      h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
        h("thead", null, h("tr", null, h("th", null, "회사"), h("th", { class: "num" }, cfg.x.label), h("th", { class: "num" }, cfg.y.label))),
        h("tbody", null, (q.dangerIds.length ? q.points.filter((p) => q.dangerIds.includes(p.id)).sort((a, b) => a.x - b.x) : []).map((p) => h("tr", { class: "click", onclick: () => env.go({ sector, co: p.id }) }, h("td", null, h("b", null, p.name)), h("td", { class: "num" }, pctText(p.x)), h("td", { class: "num" }, pctText(p.y))))))),
      q.dangerIds.length ? null : h("p", { class: "note" }, "해당하는 회사가 없습니다.")));
  body.querySelectorAll(".rk-pt").forEach((g) => {
    const open = () => env.go({ sector, co: g.dataset.id });
    g.addEventListener("click", open);
    g.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); } });
  });
}
function axisNote(sector) {
  const n = { H: "생·손보는 금융통계에 부실여신 지표가 없어 세로축을 '자산 대비 당기순이익(누계)'으로 대신했습니다(누계라 같은 시점끼리만 비교).", I: "생·손보는 금융통계에 부실여신 지표가 없어 세로축을 '자산 대비 당기순이익(누계)'으로 대신했습니다(누계라 같은 시점끼리만 비교).",
    F: "증권사 순자본비율은 회사 간 편차가 매우 커서 축 범위를 넘는 값은 가장자리에 속이 빈 점으로 표시합니다.", A: "", E: "", C: "" }[sector];
  return n ? h("p", { class: "note" }, n) : null;
}
const monthText = (m) => (m ? `${m.slice(0, 4)}.${m.slice(4)}` : "-");

// ---------- 회사 대시보드 ----------
async function dashboard(root, params, env, sector) {
  const cfg = SECTOR_CFG[sector];
  const back = h("button", { class: "pill", type: "button", onclick: () => history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록"));
  env.setHeader("금융사 리스크 대시보드", `${cfg.label} · 원스톱 요약`, [back]);
  const body = h("div", null, loading("건전성·공시·제재 자료를 모으는 중…"));
  root.append(body);
  let rows, info;
  try {
    rows = await env.provider.peers(sector);
    info = await env.provider.company(sector, params.co);
  } catch (e) {
    return body.replaceChildren(message("자료를 불러오지 못했습니다.", { error: true, action: "다시 시도", onAction: () => env.go(params) }));
  }
  const row = rows.find((r) => r.code === params.co);
  if (!row) return body.replaceChildren(message("이 회사의 금융통계 자료를 찾지 못했습니다."));
  const month = latestMonth(rows, cfg.x.key);
  const nowYear = info.nowYear || new Date().getFullYear();
  const cases = info.sanctions || [];
  const span = +(params.span || 8);
  const months = monthsBack(month, span + 1);
  env.setHeader(row.name, `${cfg.label} · 금융사 리스크 대시보드`, [back]);

  // 머리말: 이름 + 매칭 상태
  const dart = info.dart;
  const head = h("div", { class: "rk-head" },
    h("span", { class: "avatar big" }, row.name.slice(0, 1)),
    h("div", null, h("div", { class: "rk-name" }, row.name), h("div", { class: "rk-tags" },
      h("span", { class: "tag blue" }, cfg.label), h("span", { class: "tag" }, `기준 ${monthText(month)}`), sector === "F" ? h("span", { class: "tag" }, `순자본비율 ${ncrBasis(row)}`) : null,
      dart?.corp ? h("span", { class: "tag " + (dart.match === "guess" ? "orange" : "green") }, `DART ${dart.match === "guess" ? "추정 일치" : dart.rel && dart.rel !== "본인" ? dart.rel : "일치"}: ${nameOnly(dart)}`) : h("span", { class: "tag" }, "DART 자료 없음"),
      cases.length ? h("span", { class: "tag " + (cases.some((c) => severityOf(c) === 3) ? "red" : "orange") }, `제재공시 ${cases.length}건`) : h("span", { class: "tag green" }, "제재공시 없음"))));

  // 핵심 지표 타일
  const tile = (key, label, higher) => {
    const v = valueAt(row, key, month);
    const prev = valueAt(row, key, monthsBack(month, 2)[0]);
    const st = sectorStat(rows, key, month);
    const rk = rankOf(rows, row, key, month, higher);
    const d = v != null && prev != null ? v - prev : null;
    const good = d == null || d === 0 ? null : higher ? d > 0 : d < 0;
    const worse = v != null && st ? (higher ? v < st.median : v > st.median) : null;
    return h("div", { class: "kpi" + (worse ? " rk-warn" : "") }, h("small", null, label), h("b", null, pctText(v)),
      d != null ? h("div", { class: "chg", style: { color: good == null ? "var(--muted)" : good ? "var(--ok-fg)" : "var(--danger)" } }, `${d > 0 ? "▲" : d < 0 ? "▼" : "–"} ${Math.abs(d).toFixed(2)}%p (전분기)`) : null,
      h("div", { class: "note" }, rk ? `업권 ${rk.rank}/${rk.n}위 · 중앙값 ${st.median.toFixed(2)}%` : "순위 없음"));
  };
  const tiles = h("div", { class: "kpis" }, tile(cfg.x.key, cfg.x.label, cfg.x.higher), tile(cfg.y.key, cfg.y.label, cfg.y.higher),
    assetTile(row, month), h("div", { class: "kpi" }, h("small", null, "최근 3년 제재공시"), h("b", null, `${cases.filter((c) => +c.date.slice(0, 4) >= nowYear - 2).length}건`), h("div", { class: "note" }, cases.length ? `전체 ${cases.length}건 · 최근 ${dot(cases[0].date)}` : "공시 없음")));

  // 신호
  const sig = signals({ row, rows, sector, month, cases, nowYear });
  const sigBox = section("리스크 신호", "업권 중앙값·추세·제재 이력 기준(법정 기준선이 아니라 상대 비교입니다)",
    h("div", { class: "rk-sig" }, sig.map((s) => h("div", { class: "rk-sg " + s.level }, h("span", { class: "tag " + LEVEL[s.level][1] }, LEVEL[s.level][0]), h("span", null, s.text)))));

  // 추이 차트
  const trendKeys = cfg.trend.filter((k) => rowsHave(rows, row, k));
  const spanPills = h("div", { class: "row-gap" }, [4, 8].map((n) => h("button", { class: "pill sm" + (span === n ? " on" : ""), type: "button", onclick: () => env.go({ ...params, span: n }) }, `최근 ${n}분기`)));
  const charts = trendKeys.map((k) => {
    const label = cfg.names[k] || k;
    const med = months.map((m) => [m, sectorStat(rows, k, m)?.median ?? null]);
    return h("div", { class: "rk-chartcard" }, h("div", { class: "rk-ct" }, h("b", null, label), h("span", { class: "rk-legend" }, h("i", { style: { background: "var(--acc)" } }), row.name, h("i", { class: "d" }), "업권 중앙값")),
      svgBox(lineChartSvg({ series: [{ name: "업권 중앙값", color: "var(--muted)", dash: true, pts: med }, { name: row.name, color: "var(--acc)", pts: seriesOf(row, k, months) }] })));
  });
  const trend = section("건전성 추이", "분기, 금융통계정보시스템", spanPills, h("div", { class: "rk-grid2" }, charts));

  // 사분면 축소판
  const q = quadrantData(rows, sector, month, row.code);
  const quad = section("업권 내 위치", `${cfg.label} ${q.points.length}곳 중 ${row.name}`,
    svgBox(quadrantSvg({ points: q.points, xMean: q.xMean, yMean: q.yMean, xLabel: cfg.x.label, yLabel: cfg.y.label, yHigherBetter: cfg.y.higher, height: 380 }), "quad"),
    h("button", { class: "btn ghost sm", type: "button", onclick: () => env.go({ sector, view: "peer" }) }, "전체 사분면 보기"));

  // 지배구조·개황
  const gov = section("기업 개황 · 지배구조", dart?.corp ? `DART · ${dart.holders?.basis || ""}` : "DART", govBody(dart));

  // 제재
  const sanc = section("제재 이력 타임라인", "금융감독원 제재공시(2011년 이후)", sanctionBody(cases, nowYear, env));

  body.replaceChildren(head, tiles, sigBox, trend, quad, gov, sanc,
    h("p", { class: "note", style: { marginTop: "12px" } }, "자료출처: 금융감독원 금융통계정보시스템(FISIS), 전자공시시스템(DART), 금융감독원 제재공시. 회사 이름으로 세 자료를 맞춘 것이라 오류가 있을 수 있으며 참고용입니다. 업무에 인용할 때는 원문을 확인하세요."));
  const open = (g) => env.go({ sector, co: g.dataset.id });
  body.querySelectorAll(".rk-pt").forEach((g) => { g.addEventListener("click", () => open(g)); g.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(g); } }); });
}
const rowsHave = (rows, row, k) => seriesOf(row, k, monthsBack(latestMonth(rows, k) || "202612", 9)).some((p) => p[1] != null);
const nameOnly = (d) => String(d.company?.name || d.name || "").replace(/\(주\)|주식회사|㈜/g, "").trim() || "-";

function assetTile(row, month) {
  const a = (row.assetsSeries || []).find((p) => p[0] === month)?.[1] ?? row.assets;
  const ni = (row.netincome_cum || []).find((p) => p[0] === month)?.[1];
  return h("div", { class: "kpi" }, h("small", null, "자산총계(별도)"), h("b", null, a ? `${Math.round(a).toLocaleString("en-US")}억원` : "-"),
    h("div", { class: "note" }, ni != null ? `당기순이익(누계) ${Math.round(ni).toLocaleString("en-US")}억원` : "순이익 자료 없음"));
}

function govBody(dart) {
  if (!dart?.corp) return h("p", { class: "note" }, dart?.rel && dart.rel !== "본인" ? dart.rel + ". 전자공시(DART) 자료가 없는 회사입니다." : "전자공시(DART)에서 찾은 회사가 없습니다(비상장·외부감사 비대상 등). 개황·지배구조는 표시하지 않습니다.");
  const ci = dart.company || {};
  const rel = dart.rel && dart.rel !== "본인" ? h("p", { class: "note rk-rel" }, `이 회사의 DART 자료는 ${dart.rel}(${nameOnly(dart)})의 것입니다.`) : dart.match === "guess" ? h("p", { class: "note rk-rel" }, "DART 회사를 이름으로 추정해 연결했습니다. 아래 자료가 맞는지 확인하세요.") : null;
  const kv = [["대표이사", ci.ceo], ["설립일", fmtDate(ci.established)], ["시장·종목", ci.marketName ? `${ci.marketName}${ci.stock ? " " + ci.stock : ""}` : ci.stock], ["홈페이지", ci.homepage], ["주소", ci.address]].filter(([, v]) => v);
  const hold = dart.holders?.items || [];
  const total = dart.holders?.totalCommon;
  return h("div", null, rel,
    h("div", { class: "rk-kv" }, kv.map(([k, v]) => h("div", null, h("small", null, k), h("span", null, v)))),
    h("div", { class: "rk-sh2" }, "최대주주 및 특수관계인 지분", total != null ? h("span", { class: "note" }, ` 합계 ${total.toFixed(2)}%`) : null),
    hold.length ? h("div", { class: "rk-hold" }, hold.slice(0, 6).map((x) => h("div", { class: "rk-hrow" },
      h("div", { class: "nm" }, h("b", null, x.name), x.relation && x.relation !== "-" ? h("div", { class: "note" }, x.relation) : null),
      h("div", { class: "rb" }, h("span", { style: { width: Math.max(1, Math.min(100, x.common?.ratio || 0)) + "%" } })),
      h("div", { class: "pc" }, x.common?.ratio != null ? x.common.ratio.toFixed(2) + "%" : "-"))))
      : h("p", { class: "note" }, "정기보고서에서 최대주주 현황을 찾지 못했습니다."));
}
const fmtDate = (s) => (/^\d{8}$/.test(s || "") ? `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6)}` : s);

function sanctionBody(cases, nowYear, env) {
  if (!cases.length) return h("p", { class: "note" }, "이 회사 이름으로 찾은 제재공시가 없습니다. (이름이 달라 누락됐을 수 있어 제재 공시 화면에서 한 번 더 검색해 보세요.)");
  const years = sanctionYears(cases, nowYear, 8);
  const bars = years.map((y) => ({ label: String(y.year).slice(2), parts: [{ value: y.high, color: "var(--danger)", title: "중징계·영업제한" }, { value: y.mid, color: "var(--warm)", title: "금전·주의" }, { value: y.low, color: "#9fb0cb", title: "경미" }] }));
  let shown = 6;
  const list = h("div", { class: "rk-tl" });
  const more = h("button", { class: "btn ghost sm", type: "button", onclick: () => { shown += 10; draw(); } }, "더 보기");
  const draw = () => {
    list.replaceChildren(...cases.slice(0, shown).map((c) => {
      const sv = severityOf(c);
      const acts = [c.inst && `기관: ${c.inst}`, c.exec && `임원: ${c.exec}`, c.staff && `직원: ${c.staff}`, c.fine > 0 && `과태료 ${wonText(c.fine)}`, c.penalty > 0 && `과징금 ${wonText(c.penalty)}`].filter(Boolean);
      return h("div", { class: "rk-ti" }, h("div", { class: "rk-dot s" + sv }),
        h("div", { class: "rk-tb" }, h("div", { class: "rk-tt" }, h("b", null, dot(c.date)), h("span", { class: "tag " + SEV_CLASS[sv] }, SEVERITY[sv][0]), c.view ? h("button", { class: "btn ghost sm", type: "button", onclick: () => env.openUrl(c.view) }, "원문") : null),
          h("div", { class: "note" }, acts.join(" · ") || "조치내용 없음"),
          (c.items || []).length ? h("div", { class: "rk-ci" }, (c.items || []).slice(0, 3).map((i) => h("div", null, "· ", i.t))) : null));
    }));
    more.hidden = shown >= cases.length;
  };
  draw();
  return h("div", null,
    h("div", { class: "rk-legend2" }, [["var(--danger)", "중징계·영업제한 등"], ["var(--warm)", "금전제재·주의"], ["#9fb0cb", "경미"]].map(([c, t]) => h("span", null, h("i", { style: { background: c } }), t))),
    svgBox(stackedBarsSvg({ bars })), list, more,
    h("p", { class: "note" }, "조치 무게는 조치 문구(영업정지·기관경고·과태료 등)로 분류한 것으로 공식 구분이 아닙니다."));
}

// ---------- 업권 현황: 규모 순위 + 비율 분포 ----------
const eokText = (v) => (v >= 10000 ? `${Math.floor(v / 10000).toLocaleString("en-US")}조${v % 10000 ? ` ${Math.round(v % 10000).toLocaleString("en-US")}억` : ""}` : `${Math.round(v).toLocaleString("en-US")}억`);
async function overviewView(root, params, env, sector) {
  const cfg = SECTOR_CFG[sector];
  const name = SECTOR_NAME[sector];
  env.setHeader("업권 현황", `${name} · 규모 순위와 경영지표 분포`, []);
  const body = h("div", null, loading(`${name} 전체 회사 자료를 불러오는 중…`));
  root.append(body);
  let ov;
  try { ov = await env.provider.overview(sector); } catch (e) { return body.replaceChildren(message("업권 자료를 불러오지 못했습니다.", { error: true })); }
  const rk = rankSize(ov.items);
  const canOpen = !!cfg;
  let shown = 15;
  const list = h("div");
  const more = h("button", { class: "btn ghost sm", type: "button", onclick: () => { shown += 25; draw(); } }, "더 보기");
  const draw = () => {
    const max = rk.rows[0]?.value || 1;
    list.replaceChildren(...rk.rows.slice(0, shown).map((r) => h("div", { class: "rk-rank" + (canOpen ? " click" : ""), onclick: canOpen ? () => env.go({ sector, co: r.code }) : null },
      h("div", { class: "n" }, r.rank), h("div", null, h("b", null, r.name)), h("div", { class: "rb" }, h("span", { style: { width: Math.max(1, (r.value / max) * 100) + "%" } })), h("div", { class: "v" }, eokText(r.value), h("div", { class: "note" }, (r.share * 100).toFixed(1) + "%")))));
    more.hidden = shown >= rk.rows.length;
  };
  draw();
  const parts = [section(`${ov.basis} 순위`, `${ov.asOf} 기준${canOpen ? " · 회사를 누르면 대시보드" : ""}`, list, more)];
  if (cfg && ov.rows) {
    const month = latestMonth(ov.rows, cfg.x.key);
    const keys = Object.keys(cfg.names).filter((k) => ov.rows.some((r) => valueAt(r, k, month) != null));
    const cards = keys.map((k) => {
      const dist = distribution(ov.rows.map((r) => valueAt(r, k, month)));
      return h("div", { class: "rk-chartcard" }, h("div", { class: "rk-ct" }, h("b", null, cfg.names[k]), h("span", { class: "note" }, `${dist?.n || 0}곳`)),
        svgBox(histogramSvg({ dist })),
        dist ? h("div", { class: "rk-stats" }, h("span", null, "중앙값 ", h("b", null, dist.med.toFixed(2) + "%")), h("span", null, "사분위 ", h("b", null, `${dist.q1.toFixed(2)}~${dist.q3.toFixed(2)}%`)), h("span", null, "범위 ", h("b", null, `${dist.min.toFixed(2)}~${dist.max.toFixed(2)}%`)),
          dist.below + dist.above ? h("span", null, `축 밖 ${dist.below + dist.above}곳(가장자리 칸에 포함)`) : null) : null);
    });
    parts.push(section(`경영지표 분포 (${monthText(month)})`, "막대=회사 수, 옅은 띠=사분위 범위, 점선=중앙값", h("div", { class: "rk-grid2" }, cards),
      sector === "F" ? h("p", { class: "note" }, "순자본비율은 연결 우선(없으면 개별)입니다. 외국계 증권사 지점은 제외했습니다.") : null));
  } else parts.push(h("p", { class: "note" }, "운용사는 금융통계에 건전성 비율이 없어 펀드 순자산 규모 순위만 제공합니다."));
  body.replaceChildren(...parts);
}
