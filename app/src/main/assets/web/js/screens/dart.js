// 기업 개황 및 공시(DART): 회사 검색 → 개황·최대주주·재무손익·정기/주요사항/기타 공시와 주가, 관심기업의 새 공시.
// 앱의 DartScreen.kt를 옮겼다. 공시 자료는 서버(/dart)가 DART Open API에서 받아 준다.
import { setHeader } from "../app.js";
import { api, cached, dialog, errorText, h, icon, iconButton, loading, message, openUrl, searchBox, store, tabs, toast } from "../core.js";
import { withTableTools } from "../lib/tabletools.js";
import { changeOf, history, intraday, percentOf, quote, signed, sparkline, tint, yearEndCloses, DOWN, UP } from "../lib/market.js";

const MAX_WATCH = 12;
const MAX_RECENT = 8;
const TABS = ["개황", "최대주주", "재무손익", "정기보고서", "주요사항보고서", "기타공시", "뉴스"];
const MARKET = { Y: ["유가증권", "blue"], K: ["코스닥", "green"], N: ["코넥스", "orange"], E: ["기타법인", ""] };
const clsOf = (c) => c.cls || (c.stock ? "Y" : "E");
const symbolOf = (c) => (c.stock?.length === 6 ? `${c.stock}.${c.cls === "K" ? "KQ" : "KS"}` : null);
const marketTag = (cls) => h("span", { class: "tag " + (MARKET[cls] || MARKET.E)[1] }, (MARKET[cls] || MARKET.E)[0]);

const pad = (n) => String(n).padStart(2, "0");
const ymdOf = (d) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
const todayStr = () => ymdOf(new Date());
const shift = (fn) => {
  const d = new Date();
  fn(d);
  return ymdOf(d);
};
const daysAgo = (n) => shift((d) => d.setDate(d.getDate() - n));
const yearsAgo = (n) => shift((d) => d.setFullYear(d.getFullYear() - n));
const monthsAgo = (n) => shift((d) => d.setMonth(d.getMonth() - n));
const dateText = (s) => (/^\d{8}$/.test(s) ? `${s.slice(0, 4)}.${s.slice(4, 6)}.${s.slice(6)}` : s || "");
const nowYm = () => {
  const d = new Date();
  return d.getFullYear() * 100 + d.getMonth() + 1;
};
const addMonths = (ym, delta) => {
  const t = Math.floor(ym / 100) * 12 + (ym % 100) - 1 + delta;
  return Math.floor(t / 12) * 100 + (t % 12) + 1;
};
const ymText = (ym) => `${Math.floor(ym / 100)}.${pad(ym % 100)}`;
const monthEnd = (ym) => `${Math.floor(ym / 100)}${pad(ym % 100)}${pad(new Date(Math.floor(ym / 100), ym % 100, 0).getDate())}`;

const grouped = (v, d = 0) => (v == null || !Number.isFinite(v) ? "-" : v.toLocaleString("ko-KR", { minimumFractionDigits: d, maximumFractionDigits: d }));
const eok = (v) => (v == null ? "-" : grouped(v, Math.abs(v) >= 100 ? 0 : 1));
const pct = (v, d = 1) => (v == null || !Number.isFinite(v) ? "-" : grouped(v, d) + "%");

// ---- 저장(이 브라우저) ----
const watchList = () => store.get("dart.watch", []);
const saveWatch = (l) => store.set("dart.watch", l);
const isWatched = (corp) => watchList().some((c) => c.corp === corp);
const seenOf = (corp) => store.get("dart.seen", {})[corp] || "";
const markSeen = (corp) => store.set("dart.seen", { ...store.get("dart.seen", {}), [corp]: todayStr() });

function toggleWatch(c) {
  const cur = watchList();
  if (cur.some((x) => x.corp === c.corp)) {
    saveWatch(cur.filter((x) => x.corp !== c.corp));
    toast("관심기업에서 뺐습니다.");
    return false;
  }
  if (cur.length >= MAX_WATCH) {
    toast(`관심기업은 ${MAX_WATCH}곳까지 담을 수 있습니다.`);
    return false;
  }
  saveWatch([...cur, { corp: c.corp, name: c.name, stock: c.stock || "", cls: c.cls || "" }]);
  markSeen(c.corp);
  toast("관심기업에 담았습니다.");
  return true;
}

const starButton = (c, onChange) => {
  const b = iconButton("star", "관심기업", (e) => {
    e?.stopPropagation?.();
    toggleWatch(c);
    paint();
    onChange?.();
  });
  const paint = () => {
    const on = isWatched(c.corp);
    b.classList.toggle("on", on);
    b.title = on ? "관심기업 해제" : "관심기업 추가";
    b.setAttribute("aria-label", b.title);
  };
  paint();
  return b;
};

// ---- 서버 ----
const company = (corp) => cached("dart.c:" + corp, 3_600_000, () => api("/dart/company", { corp }));
const holders = (corp) => cached("dart.h:" + corp, 3_600_000, () => api("/dart/holders", { corp }));
export const finance = (corp) => cached("dart.f:" + corp, 3_600_000, () => api("/dart/finance", { corp }));
const filings = (corp, type, from, to) => cached(`dart.l:${corp}|${type}|${from}|${to}`, 600_000, () => api("/dart/filings", { corp, type, from, to }));

export async function render(root, params, ctx) {
  if (params.corp) return detail(root, params, ctx);
  return home(root, params, ctx);
}

// ---- 첫 화면: 검색 · 관심기업 · 새 공시 · 최근 검색 ----
async function home(root, params, ctx) {
  setHeader("기업 개황 및 공시(DART)", "회사명·종목코드로 검색 · 관심기업의 새 공시 모아 보기");
  const q = (params.q || "").trim();
  const box = searchBox({ placeholder: "회사명 또는 종목코드 검색", value: q, onSearch: (v) => ctx.go({ q: v.trim() || null }) });
  box.input.addEventListener("input", () => {
    if (!box.input.value && q) ctx.go({}, { replace: true });
  });
  root.append(box);
  if (q) return results(root, q, ctx);

  const watch = watchList();
  const grid = h("div", { class: "grid2" });
  const left = h("div", { class: "col" });
  const right = h("div", { class: "col" });
  grid.append(left, right);
  root.append(grid);

  const chips = h("div", { class: "row-gap" });
  const feedBox = h("div", { class: "card list" });
  left.append(
    h("div", { class: "section-label" }, h("span", null, "★ 관심기업" + (watch.length ? ` ${watch.length}곳` : "")),
      watch.length ? h("div", { class: "acts" }, iconButton("refresh-cw", "새로고침", () => loadFeed(true))) : null),
    watch.length ? chips : h("p", { class: "note" }, "검색 결과의 별(★)을 눌러 관심기업을 담으면 새 공시와 주가를 한곳에서 볼 수 있습니다."));
  if (watch.length) {
    left.append(h("div", { class: "section-label", style: { marginTop: "14px" } }, "관심기업 새 공시 · 최근 7일"), feedBox);
    right.append(h("div", { class: "section-label" }, "관심기업 주가"));
    const prices = h("div", { class: "kpis" });
    right.append(prices);
    for (const c of watch) {
      const sym = symbolOf(c);
      if (!sym) continue;
      const k = h("div", { class: "kpi click", onclick: () => ctx.go({ corp: c.corp, name: c.name }) }, h("small", null, c.name), h("b", null, "…"));
      prices.append(k);
      quote(sym).then((qq) => {
        const ch = changeOf(qq);
        k.replaceChildren(...[h("small", null, c.name), h("b", null, grouped(qq.price)), ch != null ? h("div", { class: "chg", style: { color: tint(ch) } }, `${signed(ch)} (${percentOf(qq) > 0 ? "+" : ""}${percentOf(qq).toFixed(2)}%)`) : null].filter(Boolean));
      }).catch(() => k.replaceChildren(h("small", null, c.name), h("b", null, "-"), h("div", { class: "note" }, "시세 없음")));
    }
    if (!prices.children.length) prices.append(h("p", { class: "note" }, "상장사가 없어 표시할 주가가 없습니다."));
  }
  const recent = store.get("dart.recent", []);
  if (recent.length) {
    right.append(h("div", { class: "section-label", style: { marginTop: "14px" } }, "최근 검색"),
      h("div", { class: "row-gap" }, recent.map((w) => h("button", { class: "pill", type: "button", onclick: () => ctx.go({ q: w }) }, w))));
  }
  right.append(h("p", { class: "note", style: { marginTop: "16px" } }, "DART(금융감독원 전자공시) 자료이며 주가는 지연될 수 있습니다."));

  function drawChips(counts = {}) {
    chips.replaceChildren(...watch.map((c) => h("button", { class: "pill", type: "button", onclick: () => ctx.go({ corp: c.corp, name: c.name }) },
      c.name, counts[c.corp] ? h("span", { class: "tag red", style: { marginLeft: "4px" } }, "N") : null)));
  }
  drawChips();

  async function loadFeed(force) {
    if (!watch.length) return;
    feedBox.replaceChildren(loading("새 공시를 모으는 중…"));
    const to = todayStr();
    const since = daysAgo(7);
    const get = (c, type, from) => (force ? api("/dart/filings", { corp: c.corp, type, from, to }) : filings(c.corp, type, from, to)).then((r) => r.items || []).catch(() => []);
    const all = (await Promise.all(watch.map(async (c) => {
      const [a, m, e] = await Promise.all([get(c, "a", yearsAgo(1)), get(c, "m", daysAgo(60)), get(c, "e", daysAgo(60))]);
      return [...a, ...m, ...e].map((f) => ({ c, f }));
    }))).flat();
    if (!ctx.alive()) return;
    const counts = {};
    for (const { c, f } of all) if (f.date > seenOf(c.corp)) counts[c.corp] = (counts[c.corp] || 0) + 1;
    drawChips(counts);
    const fresh = all.filter((x) => x.f.date >= since).sort((a, b) => b.f.date.localeCompare(a.f.date)).slice(0, 10);
    feedBox.replaceChildren(...(fresh.length ? fresh.map(({ c, f }) => h("div", { class: "item" },
      h("div", { class: "body", role: "link", tabindex: 0, onclick: () => openUrl(f.url) },
        h("div", { class: "t" }, f.title),
        h("div", { class: "m" }, [c.name, dateText(f.date), f.src].filter(Boolean).join(" · "))))) : [message("최근 7일 새 공시가 없습니다.")]));
  }
  loadFeed(false);
}

async function results(root, q, ctx) {
  const list = h("div", { class: "card list" }, loading("검색 중…"));
  root.append(list);
  let items;
  try {
    items = (await cached("dart.s:" + q, 600_000, () => api("/dart/search", { q }))).items || [];
  } catch (e) {
    return list.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => ctx.go({ q }, { replace: true }) }));
  }
  if (!ctx.alive()) return;
  store.set("dart.recent", [q, ...store.get("dart.recent", []).filter((w) => w !== q)].slice(0, MAX_RECENT));
  if (!items.length) return list.replaceChildren(message("검색 결과가 없습니다. 상장사·외부감사 대상 법인 위주로 찾습니다."));
  list.replaceChildren(...items.map((c) => h("div", { class: "item co-row", onclick: () => ctx.go({ corp: c.corp, name: c.name }) },
    h("span", { class: "avatar" }, c.name.slice(0, 1)),
    h("div", { class: "body" }, h("div", { class: "t" }, c.name), h("div", { class: "m" }, marketTag(clsOf(c)), c.stock || "")),
    h("div", { class: "acts" }, starButton(c)))));
}

// ---- 회사 상세 ----
async function detail(root, params, ctx) {
  const corp = params.corp;
  let c = watchList().find((x) => x.corp === corp) || { corp, name: params.name || corp, stock: "", cls: "" };
  markSeen(corp);
  const star = () => starButton(c);
  setHeader(c.name, "기업 개황 및 공시(DART)", [h("button", { class: "pill", type: "button", onclick: () => window.history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록"))]);
  const tagLine = h("div", { class: "row-gap", style: { marginBottom: "10px" } });
  const stockBox = h("div");
  const tab = Math.min(TABS.length - 1, +(params.tab || 0));
  const body = h("div", { style: { marginTop: "12px" } });
  root.append(tagLine, stockBox, tabs(TABS, tab, (i) => ctx.go({ ...params, tab: i, p: null, from: null, to: null }, { replace: true })), body);

  const drawTop = () => {
    setHeader(c.name, "기업 개황 및 공시(DART)", [
      star(),
      h("button", { class: "pill", type: "button", title: "PDF·한글로 저장", onclick: () => saveCompany(c) }, icon("download"), h("span", { class: "lbl" }, "저장")),
      h("button", { class: "pill", type: "button", onclick: () => window.history.back() }, icon("chevron-left"), h("span", { class: "lbl" }, "목록")),
    ]);
    tagLine.replaceChildren(...[marketTag(clsOf(c)), c.stock ? h("span", { class: "note" }, c.stock) : null].filter(Boolean));
  };
  drawTop();

  let info = null;
  const infoP = company(corp).then((ci) => {
    info = ci;
    c = { corp, name: ci.name || c.name, stock: ci.stock || "", cls: ci.cls || "" };
    // 관심기업 정보 보강(시장 구분·종목코드)
    const w = watchList();
    if (w.some((x) => x.corp === corp)) saveWatch(w.map((x) => (x.corp === corp ? c : x)));
    if (ctx.alive()) {
      drawTop();
      if (c.stock) stockCard(stockBox, c, params, ctx);
    }
    return ci;
  });
  if (c.stock) stockCard(stockBox, c, params, ctx);

  const fail = (e, retry) => body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: retry }));
  const reload = () => ctx.go(params, { replace: true });
  body.append(loading());
  try {
    if (tab === 0) overview(body, await infoP);
    else if (tab === 1) holdersTab(body, await holders(corp));
    else if (tab === 2) {
      await infoP.catch(() => null);
      const [f, closes] = await Promise.all([finance(corp), c.stock?.length === 6 ? yearEndCloses(c.stock) : {}]);
      if (ctx.alive()) financeTab(body, f, closes, "연결");
    } else if (TABS[tab] === "뉴스") {
      await infoP.catch(() => null);
      if (ctx.alive()) await (await import("./news.js")).companyPane(body, c.name);
    } else {
      const type = ["a", "m", "e"][tab - 3];
      await filingsTab(body, corp, type, params, ctx);
    }
  } catch (e) {
    if (ctx.alive()) fail(e, reload);
  }
  infoP.catch(() => {});
  void info;
}

// 주가 상자: 현재가·등락, 1일/1주/1개월/3개월/1년 그래프, 52주 최고·최저.
const PERIODS = [["1일", 1], ["1주", 5], ["1개월", 21], ["3개월", 63], ["1년", 260]];
const START = [() => todayStr(), () => daysAgo(7), () => monthsAgo(1), () => monthsAgo(3), () => yearsAgo(1)];

/** 주가 카드. opts.key·opts.def: 기간 선택을 기억할 저장 이름과 기본 기간(0일·1주·1개월·3개월·1년 순서의 번호). 다른 화면(금융회사 개황)에서도 쓴다. */
export async function stockCard(box, c, params, ctx, opts = {}) {
  const sym = symbolOf(c);
  if (!sym || box.dataset.sym === sym) return;
  box.dataset.sym = sym;
  const card = h("section", { class: "card stock" }, h("div", { class: "note" }, "주가"), loading());
  box.replaceChildren(card);
  let q;
  let hist = null;
  try {
    [q, hist] = await Promise.all([quote(sym), history(sym).catch(() => null)]);
  } catch {
    return card.replaceChildren(h("div", { class: "note" }, "주가"), message("시세를 불러오지 못했습니다.", { error: true, action: "다시 시도", onAction: () => ((box.dataset.sym = ""), stockCard(box, c, params, ctx)) }));
  }
  if (!ctx.alive()) return;
  const periodKey = opts.key || "dart.stockPeriod";
  let period = store.get(periodKey, opts.def ?? 3);
  const ch = changeOf(q);
  const p = percentOf(q);
  const chart = h("div", { class: "stock-chart" });
  const foot = h("div", { class: "stock-foot note" });
  const pills = h("div", { class: "row-gap" });
  card.replaceChildren(
    h("div", { class: "note" }, "주가"),
    h("div", { class: "stock-head" }, h("b", null, grouped(q.price) + "원"),
      ch != null ? h("span", { style: { color: tint(ch), fontWeight: 700 } }, `${signed(ch)} (${p > 0 ? "+" : ""}${p.toFixed(2)}%)`) : null),
    chart, pills, foot);

  async function draw() {
    pills.replaceChildren(...PERIODS.map(([l], i) => h("button", { class: "pill sm" + (i === period ? " on" : ""), type: "button", onclick: () => ((period = i), store.set(periodKey, i), draw()) }, l)));
    const [label, count] = PERIODS[period];
    let start = "";
    let change = "";
    if (period === 0) {
      chart.replaceChildren(loading(""));
      try {
        const d = await intraday(sym, q.previous, q.price);
        const up = d.points[d.points.length - 1] >= (d.previous ?? d.points[0]);
        chart.replaceChildren(sparkline(d.points, { height: 80, color: up ? UP : DOWN, baseline: d.previous, bars: d.bars }));
        start = `시작 시가 ${grouped(d.open ?? d.points[0])}` + (d.status ? ` · ${d.status}` : "");
      } catch (e) {
        chart.replaceChildren(h("div", { class: "note", style: { color: "var(--danger)" } }, "당일 시세를 불러오지 못했습니다."));
      }
    } else {
      const slice = (hist?.closes || []).slice(-count);
      chart.replaceChildren(slice.length >= 2 ? sparkline(slice, { height: 80, color: slice[slice.length - 1] >= slice[0] ? UP : DOWN }) : h("div", { class: "note" }, "차트 자료가 없습니다."));
      if (slice.length >= 2) {
        start = `시작 ${dateText(START[period]())} · ${grouped(slice[0])}`;
        const r = (slice[slice.length - 1] / slice[0] - 1) * 100;
        change = `${label} ${r > 0 ? "+" : ""}${r.toFixed(1)}%`;
      }
    }
    foot.replaceChildren(
      h("div", { class: "row-gap", style: { justifyContent: "space-between" } },
        h("span", null, `52주 최저 ${grouped(hist?.low)}`), change ? h("span", null, change) : null, h("span", null, `52주 최고 ${grouped(hist?.high)}`)),
      h("div", null, [start, `${c.stock} · 시세 제공처 기준, 지연될 수 있음`].filter(Boolean).join(" · ")));
  }
  draw();
}

function kvList(rows) {
  return h("dl", { class: "kv" }, rows.filter(([, v]) => v).map(([k, v]) => [h("dt", null, k), h("dd", null, v)]));
}

function overview(body, ci) {
  const home = ci.homepage ? (/^https?:/i.test(ci.homepage) ? ci.homepage : "https://" + ci.homepage) : "";
  body.replaceChildren(h("section", { class: "card" },
    kvList([
      ["회사명", ci.name], ["영문명", ci.nameEn], ["종목코드", ci.stock || "-"], ["시장", (MARKET[ci.cls || "E"] || MARKET.E)[0]],
      ["대표이사", ci.ceo], ["법인등록번호", ci.corpNo], ["사업자등록번호", ci.bizNo], ["설립일", dateText(ci.established)],
      ["결산월", ci.accountMonth ? ci.accountMonth + "월" : ""], ["업종코드", ci.industryCode], ["주소", ci.address], ["전화", ci.phone],
      ["홈페이지", home ? h("a", { href: home, target: "_blank", rel: "noopener" }, ci.homepage) : ""],
      ["IR", ci.ir ? h("a", { href: /^https?:/i.test(ci.ir) ? ci.ir : "https://" + ci.ir, target: "_blank", rel: "noopener" }, ci.ir) : ""],
    ]),
    h("p", { class: "note", style: { marginTop: "10px" } }, "출처: DART 기업개황")));
}

const shareCell = (s) => h("td", { class: "num" }, s?.ratio != null ? h("b", null, s.ratio.toFixed(2) + "%") : "-", s?.shares != null ? h("div", { class: "note" }, grouped(s.shares) + "주") : null);

function holdersTab(body, hd) {
  if (!hd.items?.length) return body.replaceChildren(message("최근 정기보고서에서 최대주주 현황을 찾지 못했습니다. 정기보고서 제출 대상이 아닌 회사일 수 있습니다."));
  body.replaceChildren(
    h("div", { class: "kpis", style: { marginBottom: "12px" } },
      h("div", { class: "kpi hl" }, h("small", null, `${hd.basis} 기준 · 최대주주 및 특수관계인 합계`), h("b", null, "보통주 " + pct(hd.totalCommon, 2)), hd.totalPref != null ? h("div", { class: "chg" }, "우선주 " + pct(hd.totalPref, 2)) : null)),
    h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
      h("thead", null, h("tr", null, h("th", null, "주주"), h("th", { class: "num" }, "보통주"), h("th", { class: "num" }, "우선주"), h("th", { class: "wide-only", style: { width: "22%" } }, "보통주 지분"))),
      h("tbody", null, hd.items.map((x) => h("tr", null,
        h("td", null, h("b", null, x.name), x.relation && x.relation !== "-" ? h("div", { class: "note" }, x.relation) : null),
        shareCell(x.common), shareCell(x.pref),
        h("td", { class: "wide-only" }, h("div", { class: "hbar" }, h("span", { style: { width: Math.max(1, Math.min(100, x.common?.ratio || 0)) + "%" } })))))))),
    h("p", { class: "note", style: { marginTop: "10px" } }, "출처: DART 최대주주 현황(정기보고서). 같은 주주의 보통주·우선주는 한 줄로 합쳐 보여 줍니다."));
}

function table(head, rows) {
  return withTableTools(h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
    h("thead", null, h("tr", null, h("th", null, "구분"), head.map(([l, s]) => h("th", { class: "num" }, l, s ? h("div", { class: "note", style: { fontWeight: 400 } }, s) : null)))),
    h("tbody", null, rows.map(([label, vals]) => h("tr", null, h("td", null, label), vals.map((v) => h("td", { class: "num" + (String(v).startsWith("-") && v !== "-" ? " neg" : "") }, v))))))), "DART_재무");
}

/** 손익현황 열. 분기·누적 뒤에 최근 3개 사업연도 연간을 모두 보이도록, 서버 연간 열에 없는 연도는 추이(trend)에서 채운다. */
function incomeCols(f) {
  const cols = [...(f.income || [])];
  const have = new Set(cols.map((c) => /^(\d{4})년 연간/.exec(c.label)?.[1]).filter(Boolean).map(Number));
  [...(f.trend || [])].sort((a, b) => b.year - a.year).slice(0, 3).forEach((t) => {
    if (!have.has(t.year)) cols.push({ label: `${t.year}년 연간`, sub: "", revenue: t.revenue, op: t.op, net: t.net });
  });
  return cols;
}

/** 연결 재무제표가 있는 회사는 연결·별도 전환 단추를 보여 주고, 없는 회사는 별도(개별)만 보여 준다. */
export function financeTab(body, f0, closes, basisSel) {
  if (!f0.balance?.length && !f0.income?.length) return body.replaceChildren(message(f0.note || "재무 자료를 찾지 못했습니다. 정기보고서 제출 대상이 아닌 회사일 수 있습니다."));
  const sep = f0.separate?.balance?.length || f0.separate?.income?.length ? f0.separate : null;
  const useSep = !!sep && basisSel === "별도";
  const f = useSep ? { ...f0, ...sep } : f0;
  const toggle = sep
    ? h("div", { class: "row-gap", style: { marginBottom: "4px" } },
        [f0.basis, sep.basis].map((b) => h("button", { class: "pill" + (b === f.basis ? " on" : ""), type: "button", "aria-pressed": String(b === f.basis), onclick: () => financeTab(body, f0, closes, b) }, `${b}재무제표`)))
    : null;
  const sec = (title, sub) => h("div", { class: "section-label", style: { marginTop: "14px" } }, h("span", { style: { color: "var(--acc-fg)" } }, title), h("div", { class: "acts note" }, sub));
  body.replaceChildren(
    ...(toggle ? [toggle] : []),
    sec("재무상태 비교", `${f.basis}재무제표 · 단위 억원`),
    table(f.balance.map((b) => [b.label]), [
      ["자산총계", f.balance.map((b) => eok(b.assets))],
      ["부채총계", f.balance.map((b) => eok(b.liab))],
      ["자본총계", f.balance.map((b) => eok(b.equity))],
      ["부채비율", f.balance.map((b) => pct(b.equity && b.liab != null ? (b.liab / b.equity) * 100 : null))],
    ]),
    h("p", { class: "note" }, "부채비율 = 부채총계 ÷ 자본총계"),
    sec("손익현황", "DART 정기보고서 · 단위 억원"),
    table(incomeCols(f).map((x) => [x.label, x.sub]), [
      ["매출액", incomeCols(f).map((x) => eok(x.revenue))],
      ["영업이익", incomeCols(f).map((x) => eok(x.op))],
      ["당기순이익", incomeCols(f).map((x) => eok(x.net))],
    ]),
    h("p", { class: "note" }, "금융회사는 영업수익 등 업종별 계정을 매출액 줄에 보여 주며, 해당 계정이 없으면 -로 표시됩니다."));
  if (f.trend?.length >= 2) body.append(sec(`최근 ${f.trend.length}개년 추이`, `${f.basis}재무제표 · 단위 억원`), trendCard(f.trend));
  const years = (f.trend || []).map((t) => t.year).filter((y) => f.eps?.[y] != null).slice(-4);
  if (years.length) {
    const rows = [["EPS(원)", years.map((y) => grouped(f.eps[y]))]];
    if (Object.keys(closes).length) {
      rows.push(["연말 종가(원)", years.map((y) => grouped(closes[y]))]);
      rows.push(["PER(배)", years.map((y) => (closes[y] && f.eps[y] > 0 ? (closes[y] / f.eps[y]).toFixed(2) : "-"))]);
    }
    body.append(sec("주당·배수 지표", ""), table(years.map((y) => [`${y}년`]), rows),
      h("p", { class: "note" }, "EPS는 DART 공시값입니다. PER = 연말 종가 ÷ EPS로 직접 계산한 추정치라 증권사 표시값과 다를 수 있습니다. 적자(EPS 0 이하)는 -로 표시합니다."));
  }
  body.append(h("p", { class: "note", style: { marginTop: "10px" } }, "출처: DART 주요계정·전체 재무제표. 연말 종가는 시세 제공처 기준입니다."));
}

const BAR = [["revenue", "매출액", "#2a7de1"], ["op", "영업이익", "#f08a4b"], ["net", "당기순이익", "#3f5a82"]];

function trendCard(trend) {
  const ns = "http://www.w3.org/2000/svg";
  const el = (tag, attrs) => {
    const e = document.createElementNS(ns, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
  };
  let sel = trend.length - 1;
  const caption = h("div", { class: "note", style: { margin: "6px 0" } });
  const W = 600;
  const H = 150;
  const bars = el("svg", { viewBox: `0 0 ${W} ${H}`, class: "trend", role: "img", "aria-label": "연도별 매출액·영업이익·당기순이익" });
  const debt = el("svg", { viewBox: `0 0 ${W} 70`, class: "trend small", role: "img", "aria-label": "연도별 부채비율" });
  const years = h("div", { class: "trend-years" });
  const vals = trend.flatMap((t) => BAR.map(([k]) => t[k]).filter((v) => v != null));
  const hi = Math.max(0, ...vals);
  const lo = Math.min(0, ...vals);
  const span = hi - lo || 1;
  const y = (v) => ((hi - v) / span) * (H - 6) + 3;
  const gw = W / trend.length;
  const bw = gw * 0.22;

  function draw() {
    bars.replaceChildren();
    trend.forEach((t, i) => {
      BAR.forEach(([k, , color], j) => {
        if (t[k] == null) return;
        const top = Math.min(y(t[k]), y(0));
        const r = el("rect", { x: i * gw + gw / 2 - bw * 1.5 + j * bw, y: top, width: bw * 0.92, height: Math.max(1, Math.abs(y(t[k]) - y(0))), fill: color, opacity: i === sel ? 1 : 0.45 });
        r.addEventListener("click", () => ((sel = i), draw()));
        bars.append(r);
      });
    });
    bars.append(el("line", { x1: 0, x2: W, y1: y(0), y2: y(0), stroke: "#0003" }));
    debt.replaceChildren();
    const known = trend.map((t) => t.debt).filter((v) => v != null);
    if (known.length >= 2) {
      const dlo = Math.min(...known);
      const dspan = Math.max(...known) - dlo || 1;
      const dy = (v) => 70 - ((v - dlo) / dspan) * 56 - 7;
      const pts = trend.map((t, i) => (t.debt == null ? null : [i * gw + gw / 2, dy(t.debt)])).filter(Boolean);
      debt.append(el("polyline", { points: pts.map((p) => p.join(",")).join(" "), fill: "none", stroke: "var(--acc)", "stroke-width": 2 }));
      trend.forEach((t, i) => t.debt != null && debt.append(el("circle", { cx: i * gw + gw / 2, cy: dy(t.debt), r: i === sel ? 5 : 3, fill: "var(--acc)" })));
    }
    const s = trend[sel];
    caption.textContent = `${s.year}년 매출액 ${eok(s.revenue)}억 · 영업이익 ${eok(s.op)}억 · 당기순이익 ${eok(s.net)}억 · 부채비율 ${pct(s.debt)}`;
    years.replaceChildren(...trend.map((t, i) => h("button", { type: "button", class: i === sel ? "on" : "", onclick: () => ((sel = i), draw()) }, String(t.year))));
  }
  draw();
  return h("section", { class: "card" },
    h("div", { class: "row-gap note" }, BAR.map(([, l, c]) => h("span", { class: "legend" }, h("i", { style: { background: c } }), l)), h("span", null, "· 연도를 누르면 수치 표시")),
    caption, bars, years, h("div", { class: "note", style: { marginTop: "8px" } }, "부채비율(%)"), debt);
}

// 공시 목록: 1년·3년·5년·기간선택(최대 10년).
const TYPE_INFO = { a: ["정기", "기간 안에 정기보고서(사업·반기·분기)가 없습니다."], m: ["주요", "기간 안에 주요사항보고서가 없습니다."], e: ["기타", "기간 안에 기타공시가 없습니다."] };

async function filingsTab(body, corp, type, params, ctx) {
  const p = Math.min(3, +(params.p || 0));
  const now = nowYm();
  const fromYm = +params.from || addMonths(now, -24);
  const toYm = +params.to || now;
  const from = p === 3 ? `${fromYm}01` : yearsAgo([1, 3, 5][p]);
  const to = p === 3 ? [monthEnd(toYm), todayStr()].sort()[0] : todayStr();
  const labels = ["1년", "3년", "5년", p === 3 ? `${ymText(fromYm)}~${ymText(toYm)}` : "기간선택"];
  const bar = h("div", { class: "row-gap", style: { marginBottom: "10px" } }, labels.map((l, i) => h("button", { class: "pill" + (i === p ? " on" : ""), type: "button", onclick: async () => {
    if (i < 3) return ctx.go({ ...params, p: i || null }, { replace: true });
    const picked = await pickRange(fromYm, toYm);
    if (picked) ctx.go({ ...params, p: 3, from: picked[0], to: picked[1] }, { replace: true });
  } }, i === 3 ? icon("calendar") : null, l)));
  body.replaceChildren(bar, loading());
  const r = await filings(corp, type, from, to);
  if (!ctx.alive()) return;
  const [tag, empty] = TYPE_INFO[type];
  const items = r.items || [];
  if (!items.length) return body.replaceChildren(bar, message(empty));
  const list = h("div", { class: "card list" });
  let shown = 0;
  const more = h("button", { class: "btn ghost", type: "button", style: { width: "100%", marginTop: "8px" } });
  const page = () => {
    list.append(...items.slice(shown, shown + 50).map((f) => h("div", { class: "item" },
      h("div", { class: "body", role: "link", tabindex: 0, onclick: () => openUrl(f.url) },
        h("div", { class: "t" }, h("span", { class: "tag", style: { marginRight: "6px" } }, tag), f.title),
        h("div", { class: "m" }, [dateText(f.date), f.by, f.note].filter(Boolean).join(" · "))))));
    shown += 50;
    more.textContent = `더 보기 (${items.length - shown}건 남음)`;
    more.hidden = shown >= items.length;
  };
  more.addEventListener("click", page);
  page();
  body.replaceChildren(bar,
    h("div", { class: "section-label" }, `${items.length}건 · ${dateText(from)} ~ ${dateText(to)}`),
    list, more,
    h("p", { class: "note", style: { marginTop: "10px" } }, "출처: DART · 누르면 원문 공시가 열립니다."));
}

/** 시작·종료 연월 고르기(최대 10년). [fromYm, toYm] 또는 null. */
function pickRange(fromYm, toYm) {
  const now = nowYm();
  const opts = [];
  for (let ym = now; ym >= addMonths(now, -12 * 25); ym = addMonths(ym, -1)) opts.push(ym);
  const sel = (value) => {
    const s = h("select", null, opts.map((ym) => h("option", { value: ym }, ymText(ym))));
    s.value = String(value);
    return s;
  };
  const a = sel(fromYm);
  const b = sel(toYm);
  const note = h("div", { class: "note" }, "최대 10년까지 조회할 수 있습니다.");
  const fix = (changed) => {
    let f = +a.value;
    let t = +b.value;
    if (changed === a) {
      if (t < f) t = f;
      if (t > addMonths(f, 120)) t = addMonths(f, 120);
    } else {
      if (f > t) f = t;
      if (f < addMonths(t, -120)) f = addMonths(t, -120);
    }
    a.value = String(f);
    b.value = String(Math.min(t, now));
  };
  a.addEventListener("change", () => fix(a));
  b.addEventListener("change", () => fix(b));
  return dialog({
    title: "기간 선택",
    body: h("div", { class: "stack" }, h("label", { class: "field" }, "시작", a), h("label", { class: "field" }, "종료", b), note),
    ok: "적용",
  }).then((ok) => (ok ? [+a.value, +b.value] : null));
}

// ---- PDF·한글 저장: 기업개황·최대주주·재무손익 ----
async function saveCompany(c) {
  const { saveReport } = await import("../lib/report.js");
  await saveReport({
    title: `${c.name} 저장`,
    parts: [
      { id: "info", label: "기업개황" },
      { id: "holders", label: "최대주주 현황" },
      { id: "price", label: "주가 그래프 (최근 1년, 상장사만)" },
      { id: "finance", label: "재무손익 (재무상태·손익·추이·주당 지표)" },
    ],
    build: (ids) => companyDoc(ids, c),
  });
}

export function financeBlocks(f, withEps, closes = {}) {
  const blocks = [];

  blocks.push({ heading: `재무상태 비교 (${f.basis}재무제표 · 단위 억원)` }, { table: { head: ["구분", ...f.balance.map((b) => b.label)], rows: [
    ["자산총계", ...f.balance.map((b) => eok(b.assets))],
    ["부채총계", ...f.balance.map((b) => eok(b.liab))],
    ["자본총계", ...f.balance.map((b) => eok(b.equity))],
    ["부채비율", ...f.balance.map((b) => pct(b.equity && b.liab != null ? (b.liab / b.equity) * 100 : null))],
  ] } });
  // 저장 문서는 같은 숫자를 두 번 싣지 않는다: 손익은 분기·누적 + 가능한 모든 사업연도 연간을 한 표로 합치고(추이 표를 따로 두지 않음),
  // 재무상태 표에 이미 있는 연도의 부채비율은 추이에서 다시 싣지 않는다.
  const cols = incomeCols(f);
  const yearOf = (x) => Number(/^(\d{4})년 연간/.exec(x.label)?.[1]) || null;
  const annual = new Map();
  cols.forEach((x) => { const y = yearOf(x); if (y) annual.set(y, x); });
  (f.trend || []).forEach((t) => { if (!annual.has(t.year)) annual.set(t.year, { label: `${t.year}년 연간`, revenue: t.revenue, op: t.op, net: t.net }); });
  const plCols = [...cols.filter((x) => !yearOf(x)), ...[...annual.entries()].sort((a, b) => b[0] - a[0]).map(([y, x]) => ({ label: `${y}년 연간`, revenue: x.revenue, op: x.op, net: x.net }))];
  blocks.push({ heading: "손익현황 (DART 정기보고서 · 단위 억원)" }, { table: { head: ["구분", ...plCols.map((x) => (x.sub ? `${x.label}\n${x.sub}` : x.label))], rows: [
    ["매출액", ...plCols.map((x) => eok(x.revenue))],
    ["영업이익", ...plCols.map((x) => eok(x.op))],
    ["당기순이익", ...plCols.map((x) => eok(x.net))],
  ], bold: (i) => i === 2, weights: [1.3, ...plCols.map(() => 1)] } });
  const shown = new Set(f.balance.map((b) => /^(\d{4})\.12/.exec(b.label)?.[1]).filter(Boolean).map(Number));
  const olderDebt = (f.trend || []).filter((t) => t.debt != null && !shown.has(t.year)).sort((a, b) => b.year - a.year);
  if (olderDebt.length) blocks.push({ text: "이전 사업연도 부채비율: " + olderDebt.map((t) => `${t.year}년 ${pct(t.debt)}`).join(" · "), gray: true });
  const years = (f.trend || []).map((t) => t.year).filter((y) => f.eps?.[y] != null).slice(-4);
  if (withEps && years.length) {
    const rows = [["EPS(원)", ...years.map((y) => grouped(f.eps[y]))]];
    if (Object.keys(closes).length) {
      rows.push(["연말 종가(원)", ...years.map((y) => grouped(closes[y]))]);
      rows.push(["PER(배)", ...years.map((y) => (closes[y] && f.eps[y] > 0 ? (closes[y] / f.eps[y]).toFixed(2) : "-"))]);
    }
    blocks.push({ heading: "주당·배수 지표" }, { table: { head: ["구분", ...years.map((y) => `${y}년`)], rows } },
      { text: "PER = 연말 종가 ÷ EPS로 직접 계산한 추정치입니다. 적자(EPS 0 이하)는 -로 표시합니다.", gray: true });
  }
  return blocks;
}

async function companyDoc(ids, c) {
  const sections = [];
  const ci = await company(c.corp).catch(() => null);
  const name = ci?.name || c.name;
  if (ids.has("info")) {
    sections.push({
      title: "기업개황",
      blocks: ci
        ? [{ kv: [
            ["회사명", ci.name], ["영문명", ci.nameEn], ["종목코드", ci.stock || "-"], ["시장", (MARKET[ci.cls || "E"] || MARKET.E)[0]],
            ["대표이사", ci.ceo], ["법인등록번호", ci.corpNo], ["사업자등록번호", ci.bizNo], ["설립일", dateText(ci.established)],
            ["결산월", ci.accountMonth ? ci.accountMonth + "월" : ""], ["업종코드", ci.industryCode], ["주소", ci.address], ["전화", ci.phone],
            ["홈페이지", ci.homepage], ["IR", ci.ir],
          ] }]
        : [{ text: "기업개황을 불러오지 못했습니다.", gray: true }],
    });
  }
  if (ids.has("holders")) {
    const hd = await holders(c.corp);
    const cell = (s) => (s?.ratio != null ? `${s.ratio.toFixed(2)}%` + (s.shares != null ? `\n${grouped(s.shares)}주` : "") : "-");
    sections.push({
      title: "최대주주 현황",
      note: hd.items?.length ? `${hd.basis} 기준 · 최대주주 및 특수관계인 합계: 보통주 ${pct(hd.totalCommon, 2)}${hd.totalPref != null ? ` · 우선주 ${pct(hd.totalPref, 2)}` : ""}` : "",
      blocks: hd.items?.length
        ? [{ table: { head: ["주주", "관계", "보통주", "우선주"], weights: [2.2, 1.4, 1.2, 1.2], num: (i) => i >= 2,
            rows: hd.items.map((x) => [x.name, x.relation && x.relation !== "-" ? x.relation : "", cell(x.common), cell(x.pref)]) } }]
        : [{ text: "최근 정기보고서에서 최대주주 현황을 찾지 못했습니다.", gray: true }],
    });
  }
  if (ids.has("price")) sections.push(await (await import("../lib/stockchart.js")).stockSection(ci?.stock || c.stock, ci?.cls || c.cls));
  if (ids.has("finance")) {
    const [f, closes] = await Promise.all([finance(c.corp), (ci?.stock || c.stock)?.length === 6 ? yearEndCloses(ci?.stock || c.stock).catch(() => ({})) : {}]);
    const blocks = [];
    const basisBlocks = (f2, withEps) => blocks.push(...financeBlocks(f2, withEps, closes));
    if (!f.balance?.length && !f.income?.length) blocks.push({ text: f.note || "재무 자료를 찾지 못했습니다.", gray: true });
    else {
      basisBlocks(f, true);
      if (f.separate?.balance?.length || f.separate?.income?.length) basisBlocks({ ...f, ...f.separate }, false);
      blocks.push({ text: "부채비율 = 부채총계 ÷ 자본총계. 금융회사는 영업수익 등 업종별 계정을 매출액 줄에 보여 주며, 해당 계정이 없으면 -로 표시됩니다.", gray: true });
    }
    sections.push({ title: "재무손익", blocks });
  }
  return {
    title: name,
    subtitle: "기업 개황 및 공시(DART)",
    fileName: `${name}_기업개황`,
    meta: [[ci?.stock ? `종목코드 ${ci.stock}` : null, ci ? (MARKET[ci.cls || "E"] || MARKET.E)[0] : null, `출력: ${new Date().toLocaleDateString("ko-KR")}`].filter(Boolean).join("   ·   ")],
    sections,
    footer: ["출처: 금융감독원 전자공시시스템(DART) 기업개황·최대주주 현황·주요계정. 연말 종가는 시세 제공처 기준입니다."],
  };
}
