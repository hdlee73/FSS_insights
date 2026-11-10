// 가상자산 시장 동향: 국내 원화거래소 5곳 합산 시장 일보(VirtualAssets에서 옮겨 온 화면).
// 자료는 FSS_insights 레포 crypto-data 브랜치(매일 12:00·24:00 KST 집계 JSON·PDF·HWPX, 워크플로 crypto-data.yml)를 읽는다. 별도 서버 없음.
import { setHeader } from "../app.js";
import { card, errorText, h, icon, loading, message, tabs } from "../core.js";

const BASE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/crypto-data";
const TABS = [["summary", "시장 요약"], ["coins", "주요코인 시세"], ["exchange", "거래소별 현황"], ["global", "해외 시장 비교"], ["report", "시장 일보"]];
const EXCHANGES = [["upbit", "업비트"], ["bithumb", "빗썸"], ["coinone", "코인원"], ["korbit", "코빗"], ["gopax", "고팍스"]];
const MAJORS = ["BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "USDT"];
const SRC_NOTE = "정보 제공용이며 투자 권유가 아닙니다.";

// ---------- 자료 읽기 ----------
const cache = new Map();
async function getJson(path) {
  const hit = cache.get(path);
  if (hit && Date.now() - hit.at < 5 * 60 * 1000) return hit.v;
  let res;
  try {
    res = await fetch(`${BASE}/${path}`, {});
  } catch {
    throw new Error("네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
  }
  if (res.status === 404) throw new Error("아직 데이터가 없습니다(404). 첫 일보가 생성된 뒤 다시 확인해 주세요.");
  if (!res.ok) throw new Error(`서버 응답 오류(${res.status})`);
  let v;
  try {
    v = await res.json();
  } catch {
    throw new Error("데이터 형식을 읽지 못했습니다.");
  }
  cache.set(path, { at: Date.now(), v });
  return v;
}
async function loadAll() {
  const [snapshot, history, reports, coins] = await Promise.all([
    getJson("data/latest.json"),
    getJson("data/history.json").catch(() => []),
    getJson("reports/index.json").catch(() => []),
    getJson("data/coins.json").catch(() => null),
  ]);
  return { snapshot, history: Array.isArray(history) ? history : [], reports: Array.isArray(reports) ? reports : [], coins };
}

// ---------- 서식 ----------
const num = (v, d = 0) => (v == null || !Number.isFinite(v) ? "-" : v.toLocaleString("ko-KR", { minimumFractionDigits: d, maximumFractionDigits: d }));
const won = (v) => (v == null ? "-" : Math.abs(v) >= 1e12 ? num(v / 1e12, 2) + "조" : Math.abs(v) >= 1e8 ? num(v / 1e8) + "억" : num(v));
const price = (v) => (v == null ? "-" : v >= 100 ? num(v) : v >= 1 ? num(v, 2) : num(v, 4));
const pct = (r, d = 1, signed = false) => (r == null ? "-" : (signed && r * 100 >= 0.5 / 10 ** d ? "+" : "") + num(r * 100, d) + "%");
const pp = (diff) => (diff == null ? "-" : (diff * 100 >= 0.05 ? "+" : "") + num(diff * 100, 1) + "%p");
// $2.51T = 2.51조 달러, $71.9B = 719억 달러
const usdT = (v) => (v == null ? "-" : v >= 1e12 ? "$" + num(v / 1e12, 2) + "T" : "$" + num(v / 1e9, 1) + "B");
const ratio = (c, p) => (c == null || p == null || p === 0 ? null : c / p - 1);
const diff = (c, p) => (c == null || p == null ? null : c - p);
const sign = (v) => (v == null || v === 0 ? 0 : v > 0 ? 1 : -1);
const cls = (v) => (sign(v) > 0 ? "up" : sign(v) < 0 ? "down" : "");
const shortDate = (d) => (d && d.length >= 10 ? d.slice(5, 10).replace("-", "/") : d || "");
const slotShort = (d, s) => shortDate(d) + (s === "1200" ? " 12시" : s === "2400" ? " 24시" : "");
const whenText = (s) => (s.date || (s.as_of || "").slice(0, 10)) + " " + (s.slot_label || "09:00");
const exName = (id) => EXCHANGES.find(([k]) => k === id)?.[1] || id;

const slotRank = (s) => (s === "1200" ? 1 : s === "2400" ? 2 : 0);
/** 전날 같은 시각 집계가 있으면 그것을, 없으면 직전 집계를 쓴다. */
function findPrevious(history, date, slot) {
  const earlier = history.filter((x) => x.date < date || (x.date === date && slotRank(x.slot) < slotRank(slot)));
  const same = earlier.filter((x) => (x.slot || "") === (slot || "") && x.date < date).sort((a, b) => (a.date < b.date ? 1 : -1))[0];
  if (same) return same;
  return earlier.sort((a, b) => (a.date === b.date ? slotRank(b.slot) - slotRank(a.slot) : a.date < b.date ? 1 : -1))[0] || null;
}

// ---------- 작은 SVG 그래프 ----------
const NS = "http://www.w3.org/2000/svg";
function svg(tag, attrs = {}, ...kids) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  for (const k of kids) if (k != null) el.append(k);
  return el;
}
const COLORS = ["#1f6feb", "#0f7b62", "#a36200", "#6d45b8", "#8a94a6"];

/** 꺾은선 그래프. 세로축은 데이터 범위에 맞춰 확대하고, 왼쪽에 눈금값·각 점·마지막 값을 표시한다.
 *  series: [{ name, color, data, dashed }], opts: { height, format(눈금·값 서식), dots } */
const narrowW = () => (innerWidth < 700 ? 360 : 640);
function lineChart(series, labels, { height = 200, format = num, dots, width } = {}) {
  const vals = series.flatMap((s) => s.data).filter((v) => v != null && Number.isFinite(v));
  const n = Math.max(0, ...series.map((s) => s.data.length));
  if (vals.length < 2) return h("div", { class: "note" }, "그래프를 그리기에 데이터가 아직 부족합니다.");
  const W = width || narrowW(), H = width && width < 500 ? 190 : height, L = W < 500 ? 54 : 64, R = W < 500 ? 46 : 56, T = 14, B = 24;
  let lo = Math.min(...vals), hi = Math.max(...vals);
  const flat = hi === lo;
  if (flat) { lo -= Math.abs(lo) * 0.01 || 1; hi += Math.abs(hi) * 0.01 || 1; }
  const pad = (hi - lo) * 0.12; lo -= pad; hi += pad;
  const x = (i) => L + (n <= 1 ? 0 : (i * (W - L - R)) / (n - 1));
  const y = (v) => T + ((hi - v) / (hi - lo)) * (H - T - B);
  const showDots = dots ?? n <= 40;
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, class: "cr-chart", role: "img" });
  const txt = (xx, yy, anchor, str, fill = "#5b6b86", size = 11, weight = 400) => svg("text", { x: xx, y: yy, "text-anchor": anchor, "font-size": size, "font-weight": weight, fill }, document.createTextNode(str));
  for (let g = 0; g <= 3; g++) {
    const v = hi - ((hi - lo) * g) / 3, gy = y(v);
    root.append(svg("line", { x1: L, x2: W - R, y1: gy, y2: gy, stroke: "#e2e7ef", "stroke-width": 1 }), txt(L - 6, gy + 4, "end", format(v)));
  }
  for (const s of series) {
    let d = "", pen = false, last = -1;
    s.data.forEach((v, i) => {
      if (v == null || !Number.isFinite(v)) { pen = false; return; }
      d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
      pen = true;
      last = i;
    });
    root.append(svg("path", { d, fill: "none", stroke: s.color, "stroke-width": 2.2, "stroke-linejoin": "round", "stroke-linecap": "round", "stroke-dasharray": s.dashed ? "5 4" : "" }));
    if (showDots) s.data.forEach((v, i) => v != null && Number.isFinite(v) && root.append(svg("circle", { cx: x(i), cy: y(v), r: 3, fill: "#fff", stroke: s.color, "stroke-width": 2 })));
    if (last >= 0) {
      root.append(svg("circle", { cx: x(last), cy: y(s.data[last]), r: 4.5, fill: s.color }), txt(Math.min(x(last) + 8, W - 2), y(s.data[last]) + 4, "start", format(s.data[last]), s.color, 11.5, 700));
    }
  }
  const first = Math.max(0, labels.findIndex((l) => l));
  const lab = (i, anchor) => (labels[i] ? txt(x(i), H - 6, anchor, labels[i]) : null);
  root.append(lab(first, "start"), lab(labels.length - 1, "end"));
  const legend = series.length > 1 ? h("div", { class: "cr-legend" }, series.map((s) => h("span", null, h("i", { style: { background: s.color } }), s.name))) : null;
  return h("div", null, flat ? h("div", { class: "note" }, "기간 내 값이 거의 변하지 않았습니다.") : null, root, legend);
}

/** 추세 카드 한 칸: 제목·쉬운 설명·최신값·처음 대비 변화·그래프. 점이 적으면 그 사실을 알린다. */
function trend({ title, desc, data, labels, color, format, deltaText, wide }) {
  const pts = data.filter((v) => v != null && Number.isFinite(v));
  const lastV = pts.at(-1), firstV = pts[0];
  const dl = pts.length >= 2 && deltaText ? deltaText(lastV, firstV) : null;
  return h("section", { class: "card cr-trend" },
    h("h4", null, title),
    desc ? h("p", { class: "cr-desc" }, desc) : null,
    h("div", { class: "cr-head" }, h("b", null, lastV == null ? "-" : format(lastV)), dl ? h("span", { class: "cr-delta " + cls(dl.sign) }, dl.text) : null),
    lineChart([{ name: title, color, data }], labels, { format, width: wide && innerWidth >= 700 ? 640 : 360 }),
    pts.length <= 3 ? h("p", { class: "cr-few" }, `아직 집계가 ${pts.length}번뿐이라 선이 짧습니다. 매일 12:00·24:00에 한 번씩 쌓여 점점 길어집니다.`) : null);
}

function barChart(values, labels, color = COLORS[0]) {
  const vs = values.map((v) => v ?? 0);
  if (!vs.length || Math.max(...vs) <= 0) return h("div", { class: "note" }, "그래프 데이터가 아직 부족합니다.");
  const max = Math.max(...vs);
  const W = narrowW(), H = 200, B = 24, T = 22, gap = vs.length > 12 ? 3 : 10;
  const bw = (W - gap * (vs.length - 1)) / vs.length;
  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, class: "cr-chart", role: "img" });
  const txt = (xx, yy, anchor, str, fill = "#5b6b86", size = 11, weight = 400) => svg("text", { x: xx, y: yy, "text-anchor": anchor, "font-size": size, "font-weight": weight, fill }, document.createTextNode(str));
  vs.forEach((v, i) => {
    const bh = ((H - T - B) * v) / max, bx = i * (bw + gap), last = i === vs.length - 1;
    const r = svg("rect", { x: bx, y: H - B - bh, width: bw, height: Math.max(bh, 0.5), fill: color, rx: 3, opacity: last ? 1 : 0.65 });
    r.append(svg("title", {}, document.createTextNode(`${labels[i] || ""} ${won(v)}`)));
    root.append(r);
    if (vs.length <= 12 || last) root.append(txt(bx + bw / 2, H - B - bh - 5, "middle", won(v), last ? color : "#5b6b86", 11, last ? 700 : 400));
    if (vs.length <= 8 || i === 0 || last) root.append(txt(bx + bw / 2, H - 6, i === 0 && vs.length > 8 ? "start" : last && vs.length > 8 ? "end" : "middle", labels[i] || ""));
  });
  return h("div", null, root, vs.length <= 3 ? h("p", { class: "cr-few" }, `아직 집계가 ${vs.length}번뿐입니다. 매일 12:00·24:00에 한 번씩 쌓여 막대가 늘어납니다.`) : null);
}

function donut(slices) {
  const total = slices.reduce((a, s) => a + s.v, 0);
  if (total <= 0) return h("div", { class: "note" }, "데이터가 없습니다.");
  const r = 42, c = 2 * Math.PI * r;
  let off = 0;
  const root = svg("svg", { viewBox: "0 0 120 120", class: "cr-donut", role: "img" });
  slices.forEach((s) => {
    const len = (s.v / total) * c;
    root.append(svg("circle", { cx: 60, cy: 60, r, fill: "none", stroke: s.color, "stroke-width": 20, "stroke-dasharray": `${len} ${c - len}`, "stroke-dashoffset": -off, transform: "rotate(-90 60 60)" }));
    off += len;
  });
  return h("div", { class: "cr-donut-wrap" }, root,
    h("div", { class: "cr-legend col" }, slices.map((s) => h("span", null, h("i", { style: { background: s.color } }), `${s.name} ${pct(s.v / total)}`))));
}

function hbars(items) {
  const max = Math.max(0.1, ...items.map((i) => i.v)) * 1.1;
  return h("div", { class: "cr-hbars" }, items.map((it) =>
    h("div", { class: "cr-hb" }, h("span", { class: "cr-hb-l" }, it.label),
      h("div", { class: "cr-hb-t" }, h("div", { class: "cr-hb-f", style: { width: (it.v / max) * 100 + "%", background: it.color } })),
      h("b", null, pct(it.v)))));
}

// ---------- 공통 조각 ----------
function kpi(label, value, sub, sg = 0, hint = "") {
  return h("div", { class: "kpi" }, h("small", null, label), h("b", null, value), sub ? h("div", { class: "chg " + (sg > 0 ? "up" : sg < 0 ? "down" : "") }, sub) : null, hint ? h("div", { class: "kpi-hint" }, hint) : null);
}
const chgSub = (r) => (r == null ? null : "전일 대비 " + pct(r, 1, true));
const table = (head, rows) =>
  h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
    h("thead", null, h("tr", null, head.map((t, i) => h("th", { class: i ? "num" : "" }, t)))),
    h("tbody", null, rows.map((r) => h("tr", null, r.map((c, i) => (c instanceof Node ? h("td", { class: "num" }, c) : h("td", { class: i ? "num" : "" }, String(c)))))))));
const chg = (r, d = 2) => h("span", { class: cls(r) }, pct(r, d, true));
const note = (t) => h("p", { class: "note" }, t);

// ---------- 시장 요약 ----------
function summary(root, data, goto) {
  const s = data.snapshot, d = s.domestic || {}, g = s.global || {};
  const prev = findPrevious(data.history, s.date || (s.as_of || "").slice(0, 10), s.slot || "");
  const next = s.slot === "1200" ? "오늘 24:00" : "12:00";
  root.append(note(`${whenText(s)} 집계 (다음 ${next}) · 수집 ${s.collected_at || "-"} · 원화마켓 5개 거래소 합산`));
  const dd = diff(g.kimchi_premium, prev?.kimchi_premium);
  root.append(h("div", { class: "kpis" },
    kpi("국내 24h 거래대금", won(d.total_value_24h) + " 원", chgSub(ratio(d.total_value_24h, prev?.total_value_24h)), sign(ratio(d.total_value_24h, prev?.total_value_24h)), "최근 24시간 동안 국내 원화거래소 5곳에서 거래된 금액 합계(1조=1,000억의 10배)."),
    kpi("BTC 가격(업비트)", price(d.btc_price_krw) + " 원", chgSub(ratio(d.btc_price_krw, prev?.btc_price_krw)), sign(ratio(d.btc_price_krw, prev?.btc_price_krw)), "국내 최대 거래소 업비트의 비트코인 1개 가격."),
    kpi("김치 프리미엄", pct(g.kimchi_premium, 1, true), dd == null ? null : "전일 대비 " + pp(dd), sign(dd), "국내 BTC가 해외보다 몇 % 비싼지(+이면 국내가 비쌈)."),
    kpi("BTC 도미넌스 국내 vs 글로벌", `${pct(d.btc_dominance)} / ${pct(g.btc_dom_volume)}`, `거래대금 기준 · 시총 기준 글로벌 ${pct(g.btc_dom_mcap)}`, 0, "전체 거래 중 비트코인 비중. 앞이 국내, 뒤가 글로벌입니다.")));
  const hist = data.history.slice(-30);
  const palette = [COLORS[0], COLORS[1], COLORS[2], COLORS[3], COLORS[4]];
  root.append(h("div", { class: "grid2" },
    card(`국내 거래대금 추이 (최근 ${hist.length}회 집계)`, "bar-chart-3", barChart(hist.map((x) => x.total_value_24h), hist.map((x) => slotShort(x.date, x.slot)))),
    card("거래소 점유율 (24h 거래대금)", "building-2", donut((s.exchanges || []).filter((e) => e.ok && (e.value_24h || 0) > 0).map((e, i) => ({ name: e.name || exName(e.exchange), v: e.value_24h, color: palette[i % palette.length] }))))));
  root.append(h("div", { class: "grid2" },
    card("거래대금 상위 (5개 거래소 합산)", "trending-up", table(["종목", "등락", "거래대금"], (s.top || []).slice(0, 6).map((r) => [r.symbol, chg(r.change_rate), won(r.value_24h)]))),
    card("주요코인 시세(업비트)", "activity", h("div", { class: "stack" },
      ...(data.coins?.coins || []).slice(0, 3).map((c) => h("div", { class: "cr-row" }, h("b", null, c.symbol), h("span", null, price(c.price)), chg(c.day?.change_rate))),
      !(data.coins?.coins || []).length ? note("시세 데이터가 아직 없습니다.") : null,
      h("button", { type: "button", class: "btn ghost sm", onclick: () => goto("coins") }, "더보기 ›")))));
  const bars = [];
  if (g.btc_dom_mcap != null) bars.push({ label: "글로벌 · 시가총액", v: g.btc_dom_mcap, color: COLORS[1] });
  if (g.btc_dom_volume != null) bars.push({ label: "글로벌 · 거래대금", v: g.btc_dom_volume, color: "#6fb8a6" });
  if (d.btc_dominance != null) bars.push({ label: "국내 합계 · 거래대금", v: d.btc_dominance, color: COLORS[0] });
  for (const e of s.exchanges || []) if (e.ok && e.btc_dominance != null) bars.push({ label: "  " + (e.name || exName(e.exchange)), v: e.btc_dominance, color: "#7fa8ee" });
  root.append(card("BTC 도미넌스 비교", "scale", h("div", null, note("국내 = BTC 원화 거래대금 ÷ 전체 원화 거래대금"), hbars(bars))));
  const lst = Object.entries(s.listings || {}).filter(([, l]) => (l.listed || []).length || (l.delisted || []).length);
  root.append(card("신규 상장·상장 폐지 (직전 집계 원화마켓 대비)", "list", lst.length
    ? h("div", { class: "stack" }, lst.map(([ex, l]) => h("div", null, h("b", null, exName(ex)),
      (l.listed || []).length ? h("div", { class: "up" }, "상장: " + l.listed.join(", ")) : null,
      (l.delisted || []).length ? h("div", { class: "down" }, "폐지: " + l.delisted.join(", ")) : null)))
    : note("변동 없음")));
  const latest = data.reports[0];
  root.append(card("시장 일보 (매일 12:00·24:00 집계 · PDF·HWPX)", "file-text", latest
    ? h("div", { class: "cr-row" }, h("span", null, `${reportTitle(latest)} 일보`), reportLinks(latest))
    : note("아직 생성된 일보가 없습니다.")));
  if (s.errors && Object.keys(s.errors).length) root.append(h("p", { class: "note err-note" }, "수집 실패: " + Object.keys(s.errors).join(", ") + " (해당 값은 표에서 빠짐)"));
  root.append(note(`${SRC_NOTE} 출처: 각 거래소 공개 API, ${g.source || "CoinGecko"}, Coinbase`));
}

// ---------- 주요코인 시세 ----------
function coinsView(root, data, params, ctx) {
  const cs = data.coins;
  if (!cs || !(cs.coins || []).length) return root.append(message("주요코인 시세를 불러오지 못했습니다. 다음 집계 후 새로고침해 주세요."));
  const coin = cs.coins.find((c) => c.symbol === params.coin) || cs.coins[0];
  const year = params.range === "year";
  const r = (year ? coin.year : coin.day) || {};
  root.append(note(`업비트 원화마켓 · ${cs.date} ${cs.slot_label || ""} 집계 · 수집 ${cs.collected_at || "-"}`));
  root.append(tabs(cs.coins.map((c) => c.symbol), cs.coins.indexOf(coin), (i) => ctx.go({ ...params, coin: cs.coins[i].symbol }, { replace: true }), "seg"));
  const axis = (t) => (!t ? "" : year ? (t.length >= 7 ? t.slice(2, 7).replace("-", ".") : "") : t.length >= 16 ? t.slice(11, 16) : "");
  const pts = r.points || [];
  root.append(card(`${coin.name} (${coin.symbol}) · 현재가 ${price(coin.price)} 원`, "activity", h("div", { class: "stack" },
    tabs(["당일", "1년"], year ? 1 : 0, (i) => ctx.go({ ...params, range: i ? "year" : null }, { replace: true }), "seg"),
    h("div", { class: "cr-row3" },
      h("div", null, h("small", { class: "note" }, year ? "1년 변동" : "당일 변동"), h("div", null, chg(r.change_rate))),
      h("div", null, h("small", { class: "note" }, "고가"), h("div", null, price(r.high))),
      h("div", null, h("small", { class: "note" }, "저가"), h("div", null, price(r.low)))),
    lineChart([{ name: year ? "1년" : "당일", color: (r.change_rate ?? 0) >= 0 ? "#e5484d" : "#1f6feb", data: pts }], pts.map((_, i) => axis(r.times?.[i])), { format: price }),
    note(year ? "주봉 종가 기준 최근 1년. 변동률은 1년 전 첫 주 시가 대비 현재가." : "30분봉 종가 기준 최근 24시간. 변동률은 24시간 전 시가 대비 현재가."))));
  root.append(card("전체 비교 (원화 가격과 변동률)", "table", table(["코인", "현재가", "당일", "1년"], cs.coins.map((c) => [c.symbol, price(c.price), chg(c.day?.change_rate), chg(c.year?.change_rate, 1)]))));
  root.append(note(`출처: 업비트 공개 API. ${SRC_NOTE}`));
}

// ---------- 거래소별 현황 ----------
function exchangeView(root, data, params, ctx) {
  const s = data.snapshot;
  const sel = EXCHANGES.some(([k]) => k === params.ex) ? params.ex : "upbit";
  root.append(tabs(EXCHANGES.map((e) => e[1]), EXCHANGES.findIndex(([k]) => k === sel), (i) => ctx.go({ ...params, ex: EXCHANGES[i][0] }, { replace: true }), "seg"));
  const sum = (s.exchanges || []).find((e) => e.exchange === sel);
  const days = data.history.slice(-30).filter((x) => x.exchanges?.[sel]).map((x) => ({ label: slotShort(x.date, x.slot), ...x.exchanges[sel] }));
  const today = days.at(-1), prev = days.at(-2);
  if (!sum || !sum.ok) root.append(h("p", { class: "note err-note" }, `${exName(sel)} 데이터를 이번 집계에서 수집하지 못했습니다.`));
  const mk = today && prev ? today.markets - prev.markets : null;
  const vr = ratio(today?.value, prev?.value);
  root.append(h("div", { class: "kpis" },
    kpi("상장 종목 수(원화)", `${sum?.markets ?? today?.markets ?? "-"}개`, mk == null ? null : `전일 대비 ${mk > 0 ? "+" : ""}${mk}`, sign(mk)),
    kpi("24h 거래대금", won(sum?.value_24h ?? today?.value) + " 원", chgSub(vr), sign(vr)),
    kpi("국내 점유율", pct(sum?.share)),
    kpi("BTC 도미넌스(거래대금)", pct(sum?.btc_dominance))));
  const prices = today?.prices || {};
  root.append(card("주요 코인 가격 (원화, 집계 시각 기준 · 직전 집계 대비)", "table", MAJORS.some((m) => m in prices)
    ? table(["코인", "가격(원)", "전일 대비"], MAJORS.filter((m) => m in prices).map((m) => [m, price(prices[m]), chg(ratio(prices[m], prev?.prices?.[m]))]))
    : note("가격 기록이 아직 없습니다.")));
  root.append(h("div", { class: "grid2" },
    card(`거래대금 추이 (최근 ${days.length}회 집계)`, "bar-chart-3", barChart(days.map((d) => d.value), days.map((d) => d.label))),
    card("상장 종목 수 추이 (원화마켓에서 거래되는 코인 수)", "activity", lineChart([{ name: "종목 수", color: COLORS[0], data: days.map((d) => d.markets) }], days.map((d) => d.label), { format: (v) => num(v) }))));
  root.append(card("BTC 가격 추이 (원화, 이 거래소 기준)", "trending-up", lineChart([{ name: "BTC", color: COLORS[0], data: days.map((d) => d.prices?.BTC) }], days.map((d) => d.label), { format: (v) => num(v / 1e4) + "만" })));
  const l = s.listings?.[sel];
  root.append(card("신규 상장·상장 폐지", "list", h("div", { class: "stack" },
    h("div", { class: "up" }, "이번 집계 상장: " + (l?.listed?.join(", ") || "없음")),
    h("div", { class: "down" }, "이번 집계 폐지: " + (l?.delisted?.join(", ") || "없음")),
    note(`최근 ${days.length}회 집계 합계: 상장 ${days.reduce((a, d) => a + (d.listed || 0), 0)}건 · 폐지 ${days.reduce((a, d) => a + (d.delisted || 0), 0)}건`))));
}

// ---------- 해외 시장 비교 ----------
const ppDelta = (l, f) => { const d = l - f; return { sign: d, text: `처음 대비 ${pp(d)}` }; };
const pctDelta = (l, f) => { const r = f ? l / f - 1 : null; return { sign: r, text: `처음 대비 ${pct(r, 1, true)}` }; };
function globalView(root, data) {
  const s = data.snapshot, g = s.global || {}, d = s.domestic || {};
  const prev = findPrevious(data.history, s.date || (s.as_of || "").slice(0, 10), s.slot || "");
  const hist = data.history.slice(-30), labels = hist.map((x) => slotShort(x.date, x.slot));
  const mr = ratio(g.total_mcap_usd, prev?.total_mcap_usd), kd = diff(g.kimchi_premium, prev?.kimchi_premium);
  const share = g.total_volume_usd && g.usdkrw ? pct(d.total_value_24h / (g.total_volume_usd * g.usdkrw), 2) : "-";
  root.append(note("용어가 낯설면 카드 아래 설명을 보세요. 전일 대비는 어제 같은 시각(없으면 직전 집계)과 비교한 값입니다."));
  root.append(h("div", { class: "kpis" },
    kpi("글로벌 시가총액", usdT(g.total_mcap_usd), chgSub(mr), sign(mr), "전 세계 가상자산 전체의 가치 합계(달러). T=조, B=십억. 시장 크기를 보여 줍니다."),
    kpi("BTC 시가총액", usdT(g.btc_mcap_usd), `전체의 ${pct(g.btc_dom_mcap)}`, 0, "비트코인 하나의 전체 가치. 아래 %는 전체 시장에서 BTC가 차지하는 비중입니다."),
    kpi("글로벌 24h 거래대금", usdT(g.total_volume_usd), null, 0, "최근 24시간 동안 전 세계에서 실제로 사고판 금액 합계(달러)."),
    kpi("BTC 가격(Coinbase)", g.btc_price_usd == null ? "-" : "$" + num(g.btc_price_usd), g.usdkrw == null ? null : `원/달러 ${num(g.usdkrw, 1)}`, 0, "미국 거래소 Coinbase의 비트코인 가격(달러). 아래 김치 프리미엄 계산에 쓰는 기준입니다."),
    kpi("김치 프리미엄", pct(g.kimchi_premium, 1, true), kd == null ? null : "전일 대비 " + pp(kd), sign(kd), "국내 BTC 가격이 해외(달러×환율)보다 몇 % 비싼지. +이면 국내가 더 비싸고, −이면 더 쌉니다. 0%에 가까울수록 국내외 가격이 같습니다."),
    kpi("국내 거래대금 / 글로벌", share, null, 0, "전 세계 거래대금 중 국내 원화거래소 5곳이 차지하는 비중(환율 환산). 국내 시장의 상대적 크기입니다.")));
  root.append(h("div", { class: "cr-sechead" }, h("h3", null, "비트코인이 시장에서 차지하는 비중 (BTC 도미넌스)"),
    h("p", { class: "cr-desc" }, "도미넌스 = 전체 가상자산 중 비트코인이 차지하는 비율입니다. 높을수록 비트코인에 쏠린 시장, 낮을수록 알트코인(비트코인 외 코인)에 관심이 퍼진 시장입니다. 세 가지 기준을 따로 보여 줍니다.")));
  root.append(h("div", { class: "cr-trends" },
    trend({ title: "글로벌 · 시가총액 기준", desc: "전 세계 전체 가치 중 BTC 가치의 비중", data: hist.map((x) => x.btc_dom_global_mcap), labels, color: COLORS[1], format: (v) => pct(v), deltaText: ppDelta }),
    trend({ title: "글로벌 · 거래대금 기준", desc: "전 세계 거래대금 중 BTC 거래의 비중", data: hist.map((x) => x.btc_dom_global_volume), labels, color: "#4aa58f", format: (v) => pct(v), deltaText: ppDelta }),
    trend({ title: "국내 5개 거래소 · 거래대금 기준", desc: "국내 원화 거래대금 중 BTC 거래의 비중. 글로벌보다 낮으면 국내는 알트코인 거래가 활발합니다.", data: hist.map((x) => x.btc_dominance_kr), labels, color: COLORS[0], format: (v) => pct(v), deltaText: ppDelta })));
  root.append(h("div", { class: "cr-trends one" },
    trend({ title: "김치 프리미엄 추이 — 국내 BTC가 해외보다 얼마나 비싼가", desc: "0% 위(빨강 쪽)는 국내가 비싼 때, 아래는 싼 때입니다. 보통 ±1~3% 안에서 움직이며 급등하면 국내 쏠림 신호로 봅니다.", data: hist.map((x) => x.kimchi_premium), labels, color: "#e5484d", format: (v) => pct(v, 1, true), deltaText: ppDelta, wide: true })));
  root.append(note(`출처: ${g.source || "CoinGecko"}(시가총액·거래대금), Coinbase Exchange(BTC 달러 가격). ${SRC_NOTE}`));
}

// ---------- 시장 일보 ----------
const reportTitle = (r) => (r.slot_label ? `${r.date} ${r.slot_label}` : r.date);
const reportLinks = (r) => h("span", { class: "cr-links" },
  h("a", { class: "btn sm", href: `${BASE}/${String(r.pdf).replace(/^\//, "")}`, target: "_blank", rel: "noopener" }, "PDF"),
  r.hwpx ? h("a", { class: "btn ghost sm", href: `${BASE}/${String(r.hwpx).replace(/^\//, "")}`, target: "_blank", rel: "noopener" }, "HWPX") : null);
function reportView(root, data) {
  root.append(card("국내 가상자산 시장 일보", "file-text", note("매일 12:00·24:00 KST 집계, A4 2쪽 · PDF·HWPX. BTC 가격·김치 프리미엄·시가총액·거래금액 요약, 거래소별 현황, BTC 도미넌스 국내 vs 글로벌, 거래대금 상위, 등락 상위, 신규 상장·상장 폐지를 담습니다. HWPX는 한글에서 열어 편집할 수 있습니다.")));
  if (!data.reports.length) return root.append(note("아직 생성된 일보가 없습니다."));
  root.append(h("div", { class: "stack" }, data.reports.map((r) => h("div", { class: "cr-row card-row" }, h("span", null, `${reportTitle(r)} 일보`), reportLinks(r)))));
}

// ---------- 화면 ----------
export async function render(root, params, ctx) {
  setHeader("가상자산 시장 동향", "국내 원화거래소 5곳 합산 · 매일 12:00·24:00 집계", []);
  const idx = Math.max(0, TABS.findIndex(([k]) => k === params.tab));
  root.append(tabs(TABS.map((t) => t[1]), idx, (i) => ctx.go({ tab: TABS[i][0] }, { replace: true })));
  const body = h("div", { class: "stack" }, loading());
  root.append(body);
  let data;
  try {
    data = await loadAll();
  } catch (e) {
    if (!ctx.alive()) return;
    body.replaceChildren(message(errorText(e), { error: true, action: "다시 시도", onAction: () => (cache.clear(), ctx.go({ ...params, t: Date.now() }, { replace: true })) }));
    return;
  }
  if (!ctx.alive()) return;
  const inner = h("div", { class: "stack" });
  body.replaceChildren(inner);
  const goto = (tab) => ctx.go({ tab }, { replace: true });
  try {
    ({ summary: () => summary(inner, data, goto), coins: () => coinsView(inner, data, params, ctx), exchange: () => exchangeView(inner, data, params, ctx), global: () => globalView(inner, data), report: () => reportView(inner, data) })[TABS[idx][0]]();
  } catch (e) {
    window.FSS_ERRLOG?.log("crypto", e.message, e.stack);
    inner.replaceChildren(message("화면을 그리지 못했습니다: " + errorText(e), { error: true }));
  }
}
