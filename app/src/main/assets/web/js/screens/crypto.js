// 가상자산 시장 동향: 국내 원화거래소 5곳 합산 시장 일보(VirtualAssets에서 옮겨 온 화면).
// 자료는 FSS_insights 레포 crypto-data 브랜치(매일 24:00 KST 하루 한 번 집계한 JSON·PDF·HWPX와 거래소 재무, 워크플로 crypto-data.yml). 주요 코인 시세는 업비트·Coinbase 공개 API를 브라우저에서 실시간 조회를 읽는다. 별도 서버 없음.
import { setHeader } from "../app.js";
import { card, download as coreDownload, errorText, h, icon, loading, message, tabs } from "../core.js";

const BASE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/crypto-data";
const TABS = [["summary", "시장 요약"], ["coins", "주요코인 시세"], ["exchange", "거래소별 현황"], ["fin", "거래소 재무"], ["global", "해외 시장 비교"], ["report", "시장 일보"]];
const EXCHANGES = [["upbit", "업비트"], ["bithumb", "빗썸"], ["coinone", "코인원"], ["korbit", "코빗"], ["gopax", "고팍스"]];
const MAJORS = ["BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "USDT", "USDC"];
const STABLES = ["USDT", "USDC"];
const MAJOR_NAME = { BTC: "비트코인", ETH: "이더리움", XRP: "리플", SOL: "솔라나", DOGE: "도지코인", ADA: "에이다", USDT: "테더", USDC: "USD코인" };
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
  const [snapshot, history, reports, coins, fin] = await Promise.all([
    getJson("data/latest.json"),
    getJson("data/history.json").catch(() => []),
    getJson("reports/index.json").catch(() => []),
    getJson("data/coins.json").catch(() => null),
    getJson("data/exchange_fin.json").catch(() => null),
  ]);
  // 집계는 매일 24:00 한 번. 예전(12시 포함) 기록은 그래프가 들쭉날쭉해지므로 24시 마감 기록만 쓴다.
  const daily = (Array.isArray(history) ? history : []).filter((x) => x.slot !== "1200");
  return { snapshot, history: daily, reports: Array.isArray(reports) ? reports : [], coins, fin };
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
const slotShort = (d, s) => shortDate(d) + (s === "1200" ? " 12시" : "");
const whenText = (s) => (s.date || (s.as_of || "").slice(0, 10)) + " " + (s.slot_label || "24:00") + " 마감";
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
    pts.length <= 3 ? h("p", { class: "cr-few" }, `아직 집계가 ${pts.length}번뿐이라 선이 짧습니다. 매일 24시에 한 번씩 쌓여 점점 길어집니다.`) : null);
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
  return h("div", null, root, vs.length <= 3 ? h("p", { class: "cr-few" }, `아직 집계가 ${vs.length}번뿐입니다. 매일 24시에 한 번씩 쌓여 막대가 늘어납니다.`) : null);
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

function hbars(items, fmt = pct) {
  const max = Math.max(0.1, ...items.map((i) => i.v)) * 1.1;
  return h("div", { class: "cr-hbars" }, items.map((it) =>
    h("div", { class: "cr-hb" }, h("span", { class: "cr-hb-l" }, it.label),
      h("div", { class: "cr-hb-t" }, h("div", { class: "cr-hb-f", style: { width: (it.v / max) * 100 + "%", background: it.color } })),
      h("b", null, fmt(it.v)))));
}

// ---------- 실시간 시세(브라우저에서 직접 조회) ----------
async function liveUpbit(syms) {
  const r = await fetch("https://api.upbit.com/v1/ticker?markets=" + syms.map((x) => "KRW-" + x).join(","));
  if (!r.ok) throw new Error("upbit " + r.status);
  const out = {};
  for (const t of await r.json()) out[String(t.market).replace("KRW-", "")] = { price: t.trade_price, rate: t.signed_change_rate, value: t.acc_trade_price_24h };
  return out;
}
async function liveUsd(syms) {
  const rs = await Promise.allSettled(syms.map(async (x) => {
    const r = await fetch(`https://api.exchange.coinbase.com/products/${x}-USD/ticker`);
    if (!r.ok) throw new Error("cb " + r.status);
    return [x, Number((await r.json()).price)];
  }));
  const out = {};
  for (const r of rs) if (r.status === "fulfilled" && Number.isFinite(r.value[1])) out[r.value[0]] = r.value[1];
  return out;
}
/** 화면이 살아 있는 동안 ms마다 fn 실행(탭이 숨겨져 있으면 건너뜀). */
function poll(ctx, fn, ms = 8000) {
  let t = 0;
  const tick = async () => {
    if (!ctx.alive()) return;
    if (!document.hidden) { try { await fn(); } catch { /* 일시 오류는 다음 주기에 재시도 */ } }
    if (ctx.alive()) t = setTimeout(tick, ms);
  };
  tick();
  return () => clearTimeout(t);
}
const usd = (v) => (v == null ? "-" : "$" + (v >= 100 ? num(v) : v >= 1 ? num(v, 3) : num(v, 4)));
/** 실시간 시세표: 코인 · 현재가 · 24h 등락 · 달러가 · 김치 프리미엄. 업비트 현재가 + Coinbase 달러가 + 집계 환율. */
function liveTable(ctx, snap, syms, { full = innerWidth >= 560 } = {}) {
  const g = snap.global || {}, cp = snap.coin_premium || {};
  const cells = {};
  const head = full ? ["코인", "현재가(원)", "24h", "해외가", "김프"] : ["코인", "현재가(원)", "24h", "김프"];
  const body = syms.map((x) => {
    const c = (cells[x] = { p: h("td", { class: "num" }, "-"), r: h("td", { class: "num" }, "-"), u: h("td", { class: "num" }, usd(cp[x]?.usd)), k: h("td", { class: "num" }, pct(cp[x]?.premium, 2, true)) });
    return h("tr", null, h("td", null, h("b", null, x), full ? h("small", { class: "note" }, " " + (MAJOR_NAME[x] || "")) : null), c.p, c.r, full ? c.u : null, c.k);
  });
  const stamp = h("span", { class: "cr-live" }, h("i"), "실시간 연결 중…");
  const stop = poll(ctx, async () => {
    const [up, us] = await Promise.all([liveUpbit(syms), liveUsd(syms).catch(() => ({}))]);
    for (const x of syms) {
      const c = cells[x], q = up[x];
      if (!q) continue;
      c.p.textContent = price(q.price);
      c.r.textContent = pct(q.rate, 2, true);
      c.r.className = "num " + cls(q.rate);
      const u = us[x] ?? cp[x]?.usd;
      c.u.textContent = usd(u);
      const pr = u && g.usdkrw ? q.price / (u * g.usdkrw) - 1 : cp[x]?.premium;
      c.k.textContent = pct(pr, 2, true);
      c.k.className = "num " + cls(pr);
    }
    stamp.lastChild.textContent = "실시간 " + new Date().toTimeString().slice(0, 8) + " 갱신";
  });
  void stop;
  return h("div", { class: "stack" }, stamp,
    h("div", { class: "tbl-wrap" }, h("table", { class: "tbl cr-live-tbl" }, h("thead", null, h("tr", null, head.map((t, i) => h("th", { class: i ? "num" : "" }, t)))), h("tbody", null, body))),
    note(`현재가=업비트, 해외가=Coinbase(달러), 환율 ${num(g.usdkrw, 1)}원(집계 시점). 김프=원화가÷(해외가×환율)−1. 약 8초마다 갱신.`));
}

// ---------- 공통 조각 ----------
function kpi(label, value, sub, sg = 0, hint = "", unit = "") {
  return h("div", { class: "kpi" }, h("small", null, label), h("b", null, value, unit ? h("span", { class: "u" }, unit) : null), sub ? h("div", { class: "chg " + (sg > 0 ? "up" : sg < 0 ? "down" : "") }, sub) : null, hint ? h("div", { class: "kpi-hint" }, hint) : null);
}
const chgSub = (r) => (r == null ? null : "전일 대비 " + pct(r, 1, true));
const table = (head, rows) =>
  h("div", { class: "tbl-wrap" }, h("table", { class: "tbl" },
    h("thead", null, h("tr", null, head.map((t, i) => h("th", { class: i ? "num" : "" }, t)))),
    h("tbody", null, rows.map((r) => h("tr", null, r.map((c, i) => (c instanceof Node ? h("td", { class: "num" }, c) : h("td", { class: i ? "num" : "" }, String(c)))))))));
const chg = (r, d = 2) => h("span", { class: cls(r) }, pct(r, d, true));
const note = (t) => h("p", { class: "note" }, t);


// ---------- 거래소 점유율(기간별) ----------
const SHARE_PERIODS = [["day", "일"], ["week", "주(7일)"], ["month", "월"], ["quarter", "분기"], ["half", "반기"], ["year", "연"], ["custom", "기간 설정"]];
function periodStart(period, date) {
  const [y, m] = [Number(date.slice(0, 4)), Number(date.slice(5, 7))];
  const pad = (n) => String(n).padStart(2, "0");
  if (period === "month") return `${y}-${pad(m)}-01`;
  if (period === "quarter") return `${y}-${pad(Math.floor((m - 1) / 3) * 3 + 1)}-01`;
  if (period === "half") return `${y}-${m <= 6 ? "01" : "07"}-01`;
  if (period === "year") return `${y}-01-01`;
  if (period === "week") { const d = new Date(date + "T00:00:00Z"); d.setUTCDate(d.getUTCDate() - 6); return d.toISOString().slice(0, 10); }
  return date;
}
/** 기간 내 일별 24h 거래대금을 합산해 거래소별 점유율을 계산한다(달력 기준 누계: 월·분기·반기·연은 해당 기간 첫날부터 마지막 집계일까지). */
function periodShares(hist, period, range) {
  const rows = hist.filter((x) => x.date);
  if (!rows.length) return null;
  const last = range?.to || rows.at(-1).date, from = range?.from || periodStart(period, last);
  const sel = rows.filter((x) => x.date >= from && x.date <= last);
  const sums = {};
  let days = 0;
  for (const r of sel) {
    let any = false;
    for (const [k] of EXCHANGES) {
      const v = r.exchanges?.[k]?.value ?? r.by_exchange?.[k];
      if (v != null && Number.isFinite(v)) { sums[k] = (sums[k] || 0) + v; any = true; }
    }
    if (any) days++;
  }
  const total = Object.values(sums).reduce((a, b) => a + b, 0);
  return { from, to: last, days, sums, total };
}
function sharePanel(s, hist, palette, prev) {
  const body = h("div", { class: "stack" });
  const dates = hist.map((x) => x.date).filter(Boolean);
  const minD = dates[0] || "", maxD = dates.at(-1) || "";
  const fromIn = h("input", { type: "date", min: minD, max: maxD, value: minD }), toIn = h("input", { type: "date", min: minD, max: maxD, value: maxD });
  const rangeBox = h("div", { class: "cr-range", hidden: true }, h("label", null, "시작 ", fromIn), h("label", null, "종료 ", toIn), h("span", { class: "note" }, `선택 가능: ${minD} ~ ${maxD}`));
  let cur = "day";
  const draw = (period) => {
    cur = period;
    rangeBox.hidden = period !== "custom";
    let range = null;
    if (period === "custom") {
      let f = fromIn.value || minD, t = toIn.value || maxD;
      if (f > t) [f, t] = [t, f];
      range = { from: f, to: t };
    }
    const r = period === "day" ? null : periodShares(hist, period, range);
    let sums, total, head;
    if (period === "day" || !r) {
      sums = Object.fromEntries((s.exchanges || []).filter((e) => e.ok).map((e) => [e.exchange, e.value_24h || 0]));
      total = Object.values(sums).reduce((a, b) => a + b, 0);
      head = note(`${s.date || ""} 24시 마감 기준 하루 거래대금`);
    } else {
      sums = r.sums; total = r.total;
      const full = { week: 7, month: 28, quarter: 85, half: 175, year: 360 }[period];
      if (!r.days) { body.replaceChildren(note("선택한 기간에 집계 자료가 없습니다. 기간을 바꿔 주세요.")); return; }
      head = note(`${r.from} ~ ${r.to} 누계(집계 ${r.days}일분)` + (period !== "custom" && r.days < Math.min(full || 5, 5) ? " · 이력이 아직 짧아 일부 기간만 반영됩니다." : ""));
    }
    const rows = EXCHANGES.filter(([k]) => sums[k] > 0).map(([k, n], i) => ({ k, n, v: sums[k], color: palette[i % palette.length] }));
    body.replaceChildren(head,
      donut(rows.map((e) => ({ name: e.n, v: e.v, color: e.color }))),
      table(["거래소", period === "day" ? "거래대금" : "기간 거래대금", "점유율"], rows.map((e) => [e.n, won(e.v) + " 원", pct(total ? e.v / total : null)]).concat([["합계", won(total) + " 원", "100%"]])),
      note("점유율 = 거래소 거래대금 ÷ 5개 거래소 합계. 기간 거래대금은 매일 24시 마감 집계값을 더한 것입니다."));
  };
  const seg = tabs(SHARE_PERIODS.map((p) => p[1]), 0, (i) => {
    seg.querySelectorAll("[role=tab]").forEach((b, j) => { b.classList.toggle("on", j === i); b.setAttribute("aria-selected", String(j === i)); });
    draw(SHARE_PERIODS[i][0]);
  }, "seg");
  fromIn.addEventListener("change", () => cur === "custom" && draw("custom"));
  toIn.addEventListener("change", () => cur === "custom" && draw("custom"));
  draw("day");
  return card("거래소 점유율 (기간별)", "building-2", h("div", { class: "stack" }, seg, rangeBox, body));
}

// ---------- 시장 요약 ----------
function summary(root, data, goto, ctx) {
  const s = data.snapshot, d = s.domestic || {}, g = s.global || {};
  const prev = findPrevious(data.history, s.date || (s.as_of || "").slice(0, 10), s.slot || "");
  root.append(note(`${whenText(s)} 집계(하루 한 번 24시 마감 · 통계·일보 기준) · 수집 ${s.collected_at || "-"} · 원화마켓 5개 거래소 합산. 주요 코인 시세는 실시간입니다.`));
  const dd = diff(g.kimchi_premium, prev?.kimchi_premium);
  root.append(h("div", { class: "kpis cr-kpis" },
    kpi("국내 24h 거래대금", won(d.total_value_24h), chgSub(ratio(d.total_value_24h, prev?.total_value_24h)), sign(ratio(d.total_value_24h, prev?.total_value_24h)), "최근 24시간 동안 국내 원화거래소 5곳에서 거래된 금액 합계(1조=1,000억의 10배).", "원"),
    kpi("BTC 가격(업비트·집계)", price(d.btc_price_krw), chgSub(ratio(d.btc_price_krw, prev?.btc_price_krw)), sign(ratio(d.btc_price_krw, prev?.btc_price_krw)), "국내 최대 거래소 업비트의 비트코인 1개 가격.", "원"),
    kpi("김치 프리미엄", pct(g.kimchi_premium, 1, true), dd == null ? null : "전일 대비 " + pp(dd), sign(dd), "국내 BTC가 해외보다 몇 % 비싼지(+이면 국내가 비쌈)."),
    kpi("BTC 도미넌스 · 국내 거래대금", pct(d.btc_dominance), `글로벌 거래대금 ${pct(g.btc_dom_volume)}`, 0, "국내 원화 거래대금 중 비트코인 거래의 비중."),
    kpi("BTC 도미넌스 · 국내 시가총액", pct(d.btc_dom_mcap), `글로벌 시가총액 ${pct(g.btc_dom_mcap)}`, 0, "국내 상장 코인들의 시가총액 합 중 비트코인 비중."),
    kpi("스테이블코인 거래 비중", pct(d.stable_share), null, 0, "국내 거래대금 중 USDT·USDC 등 달러 연동 코인의 비중.")));
  const hist = data.history.slice(-30);
  const palette = [COLORS[0], COLORS[1], COLORS[2], COLORS[3], COLORS[4]];
  root.append(h("div", { class: "cr-grid2" },
    card(`국내 거래대금 추이 (최근 ${hist.length}회 집계)`, "bar-chart-3", h("div", { class: "stack" },
      barChart(hist.map((x) => x.total_value_24h), hist.map((x) => slotShort(x.date, x.slot))),
      (() => {
        const v = hist.map((x) => x.total_value_24h).filter((x) => x != null && Number.isFinite(x));
        if (v.length < 2) return null;
        const avg = v.reduce((a, b) => a + b, 0) / v.length, mx = Math.max(...v), mn = Math.min(...v);
        return table(["구분", "거래대금", "비고"], [
          ["이번 집계", won(v.at(-1)) + " 원", `평균 대비 ${pct(ratio(v.at(-1), avg), 1, true)}`],
          ["기간 평균", won(avg) + " 원", `${v.length}회 집계`],
          ["기간 최고", won(mx) + " 원", slotShort(hist[hist.findIndex((x) => x.total_value_24h === mx)]?.date, "")],
          ["기간 최저", won(mn) + " 원", slotShort(hist[hist.findIndex((x) => x.total_value_24h === mn)]?.date, "")]]);
      })(),
      note("막대는 매일 24시 마감 기준 국내 5개 거래소 24시간 거래대금 합계입니다."))),
    sharePanel(s, data.history, palette, prev)));
  root.append(h("div", { class: "cr-grid2" },
    card("거래대금 상위 (5개 거래소 합산)", "trending-up", table(["종목", "등락", "거래대금"], (s.top || []).slice(0, 6).map((r) => [r.symbol, chg(r.change_rate), won(r.value_24h)]))),
    card("주요코인 시세 · 김프 (실시간)", "activity", h("div", { class: "stack" },
      liveTable(ctx, s, ["BTC", "ETH", "XRP", "SOL", "USDT"], { full: false }),
      h("button", { type: "button", class: "btn ghost sm", onclick: () => goto("coins") }, "더보기 ›")))));
  const bars = [];
  if (g.btc_dom_mcap != null) bars.push({ label: "글로벌 · 시가총액", v: g.btc_dom_mcap, color: COLORS[1] });
  if (g.btc_dom_volume != null) bars.push({ label: "글로벌 · 거래대금", v: g.btc_dom_volume, color: "#6fb8a6" });
  if (d.btc_dom_mcap != null) bars.push({ label: "국내 합계 · 시가총액", v: d.btc_dom_mcap, color: COLORS[3] });
  if (d.btc_dominance != null) bars.push({ label: "국내 합계 · 거래대금", v: d.btc_dominance, color: COLORS[0] });
  for (const e of s.exchanges || []) {
    if (!e.ok) continue;
    if (e.btc_dom_mcap != null) bars.push({ label: "  " + (e.name || exName(e.exchange)) + " · 시총", v: e.btc_dom_mcap, color: "#a98be0" });
    if (e.btc_dominance != null) bars.push({ label: "  " + (e.name || exName(e.exchange)) + " · 대금", v: e.btc_dominance, color: "#7fa8ee" });
  }
  root.append(card("BTC 도미넌스 비교", "scale", h("div", null, note("거래대금 기준 = BTC 원화 거래대금 ÷ 전체 원화 거래대금 / 시가총액 기준 = 상장 코인 글로벌 시가총액 합 중 BTC 비중"), hbars(bars))));
  const lst = Object.entries(s.listings || {}).filter(([, l]) => (l.listed || []).length || (l.delisted || []).length);
  root.append(card("신규 상장·상장 폐지 (직전 집계 원화마켓 대비)", "list", lst.length
    ? h("div", { class: "stack" }, lst.map(([ex, l]) => h("div", null, h("b", null, exName(ex)),
      (l.listed || []).length ? h("div", { class: "up" }, "상장: " + l.listed.join(", ")) : null,
      (l.delisted || []).length ? h("div", { class: "down" }, "폐지: " + l.delisted.join(", ")) : null)))
    : note("변동 없음")));
  const latest = data.reports[0];
  root.append(card("시장 일보 (매일 24시 집계 · PDF·HWPX)", "file-text", latest
    ? h("div", { class: "cr-row" }, h("span", null, `${reportTitle(latest)} 일보`), reportLinks(latest))
    : note("아직 생성된 일보가 없습니다.")));
  if (s.errors && Object.keys(s.errors).length) root.append(h("p", { class: "note err-note" }, "수집 실패: " + Object.keys(s.errors).join(", ") + " (해당 값은 표에서 빠짐)"));
  root.append(note(`${SRC_NOTE} 출처: 각 거래소 공개 API, ${g.source || "CoinGecko"}, Coinbase`));
}

// ---------- 주요코인 시세 ----------
function coinsView(root, data, params, ctx) {
  const cs = data.coins;
  root.append(card("주요 코인 실시간 시세와 김치 프리미엄", "activity", liveTable(ctx, data.snapshot, ["BTC", "ETH", "XRP", "SOL", "DOGE", "ADA"])));
  root.append(card("대표 스테이블코인 (달러 연동 코인)", "coins", liveTable(ctx, data.snapshot, STABLES)));
  root.append(note("스테이블코인은 달러와 1:1을 목표로 해서, 원화 가격이 환율과 같으면 김프 0%입니다. 환율보다 높으면 달러 수요가 몰린 것입니다."));
  if (!cs || !(cs.coins || []).length) return root.append(message("당일·1년 그래프 자료를 불러오지 못했습니다. 다음 집계 후 새로고침해 주세요."));
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
  root.append(h("div", { class: "kpis cr-kpis" },
    kpi("상장 종목 수(원화)", `${sum?.markets ?? today?.markets ?? "-"}`, mk == null ? null : `전일 대비 ${mk > 0 ? "+" : ""}${mk}`, sign(mk), "", "개"),
    kpi("24h 거래대금", won(sum?.value_24h ?? today?.value), chgSub(vr), sign(vr), "", "원"),
    kpi("국내 점유율", pct(sum?.share)),
    kpi("BTC 도미넌스(거래대금)", pct(sum?.btc_dominance)),
    kpi("BTC 도미넌스(시가총액)", pct(sum?.btc_dom_mcap)),
    kpi("BTC 김치 프리미엄", pct(s.exchange_majors?.[sel]?.BTC?.premium, 2, true))));
  const prices = today?.prices || {};
  const maj = s.exchange_majors?.[sel] || {};
  const syms = MAJORS.filter((m) => m in maj || m in prices);
  root.append(card(`주요 코인 · 스테이블코인 (${exName(sel)}, 전일 24시 마감 기준)`, "table", syms.length
    ? table(["코인", "가격(원)", "24h", "김프", "전일 거래금액"], syms.map((m) => [m, price(maj[m]?.price ?? prices[m]), maj[m] ? chg(maj[m].change_rate) : chg(ratio(prices[m], prev?.prices?.[m])), maj[m] ? chg(maj[m].premium) : "-", won(maj[m]?.value_24h ?? prev?.values?.[m] ?? days.at(-1)?.values?.[m])]))
    : note("가격 기록이 아직 없습니다.")));
  const cmp = EXCHANGES.filter(([k]) => s.exchange_majors?.[k]);
  if (cmp.length) root.append(card("거래소별 주요 코인 전일 거래금액 비교 (24시 마감, 원)", "scale", table(["코인", ...cmp.map((e) => e[1])], MAJORS.map((m) => [m, ...cmp.map(([k]) => won(s.exchange_majors[k][m]?.value_24h))]))));
  root.append(h("div", { class: "cr-grid2" },
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
  root.append(h("div", { class: "kpis cr-kpis" },
    kpi("글로벌 시가총액", usdT(g.total_mcap_usd), chgSub(mr), sign(mr), "전 세계 가상자산 전체의 가치 합계(달러). T=조, B=십억. 시장 크기를 보여 줍니다."),
    kpi("BTC 시가총액", usdT(g.btc_mcap_usd), `전체의 ${pct(g.btc_dom_mcap)}`, 0, "비트코인 하나의 전체 가치. 아래 %는 전체 시장에서 BTC가 차지하는 비중입니다."),
    kpi("글로벌 24h 거래대금", usdT(g.total_volume_usd), null, 0, "최근 24시간 동안 전 세계에서 실제로 사고판 금액 합계(달러)."),
    kpi("BTC 가격(Coinbase)", g.btc_price_usd == null ? "-" : "$" + num(g.btc_price_usd), g.usdkrw == null ? null : `원/달러 ${num(g.usdkrw, 1)}`, 0, "미국 거래소 Coinbase의 비트코인 가격(달러). 아래 김치 프리미엄 계산에 쓰는 기준입니다."),
    kpi("김치 프리미엄", pct(g.kimchi_premium, 1, true), kd == null ? null : "전일 대비 " + pp(kd), sign(kd), "국내 BTC 가격이 해외(달러×환율)보다 몇 % 비싼지. +이면 국내가 더 비싸고, −이면 더 쌉니다. 0%에 가까울수록 국내외 가격이 같습니다."),
    kpi("국내 거래대금 / 글로벌", share, null, 0, "전 세계 거래대금 중 국내 원화거래소 5곳이 차지하는 비중(환율 환산). 국내 시장의 상대적 크기입니다.")));
  root.append(h("div", { class: "cr-sechead" }, h("h3", null, "비트코인이 시장에서 차지하는 비중 (BTC 도미넌스)"),
    h("p", { class: "cr-desc" }, "도미넌스 = 전체 가상자산 중 비트코인이 차지하는 비율입니다. 높을수록 비트코인에 쏠린 시장, 낮을수록 알트코인(비트코인 외 코인)에 관심이 퍼진 시장입니다. 네 가지 기준을 따로 보여 줍니다.")));
  root.append(h("div", { class: "cr-trends" },
    trend({ title: "글로벌 · 시가총액 기준", desc: "전 세계 전체 가치 중 BTC 가치의 비중", data: hist.map((x) => x.btc_dom_global_mcap), labels, color: COLORS[1], format: (v) => pct(v), deltaText: ppDelta }),
    trend({ title: "글로벌 · 거래대금 기준", desc: "전 세계 거래대금 중 BTC 거래의 비중", data: hist.map((x) => x.btc_dom_global_volume), labels, color: "#4aa58f", format: (v) => pct(v), deltaText: ppDelta }),
    trend({ title: "국내 5개 거래소 · 시가총액 기준", desc: "국내 상장 코인들의 글로벌 시가총액 합 중 BTC 비중", data: hist.map((x) => x.btc_dominance_kr_mcap), labels, color: COLORS[3], format: (v) => pct(v), deltaText: ppDelta }),
    trend({ title: "국내 5개 거래소 · 거래대금 기준", desc: "국내 원화 거래대금 중 BTC 거래의 비중. 글로벌보다 낮으면 국내는 알트코인 거래가 활발합니다.", data: hist.map((x) => x.btc_dominance_kr), labels, color: COLORS[0], format: (v) => pct(v), deltaText: ppDelta })));
  root.append(h("div", { class: "cr-trends one" },
    trend({ title: "김치 프리미엄 추이 — 국내 BTC가 해외보다 얼마나 비싼가", desc: "0% 위(빨강 쪽)는 국내가 비싼 때, 아래는 싼 때입니다. 보통 ±1~3% 안에서 움직이며 급등하면 국내 쏠림 신호로 봅니다.", data: hist.map((x) => x.kimchi_premium), labels, color: "#e5484d", format: (v) => pct(v, 1, true), deltaText: ppDelta, wide: true })));
  root.append(note(`출처: ${g.source || "CoinGecko"}(시가총액·거래대금), Coinbase Exchange(BTC 달러 가격). ${SRC_NOTE}`));
}

// ---------- 거래소 재무(DART) ----------
const ratioOf = (a, b) => (a == null || !b ? null : a / b);
function finView(root, data, params, ctx) {
  const fin = data.fin, ex = fin?.exchanges || {};
  const CORP = { upbit: "두나무", bithumb: "빗썸코리아", coinone: "코인원", korbit: "코빗", gopax: "스트리미" };
  const list = EXCHANGES.map(([k, n]) => ({ k, n, ...(ex[k] || {}), corp: ex[k]?.corp || CORP[k], years: (ex[k]?.years || []) }));
  if (!fin || !list.some((e) => e.years.length)) {
    return root.append(message("거래소 재무 자료를 아직 가져오지 못했습니다. 다음 집계(매일 24시) 때 DART에서 수집되며, 사업보고서·감사보고서가 공시된 거래소부터 표시됩니다."));
  }
  root.append(note(`DART 사업보고서(정기보고서) 또는 외부감사 보고서의 재무제표 · 갱신 ${fin.updated || "-"} · 금액은 원 단위 환산`));
  // 가장 최근 연도의 연결·별도(있는 것만)를 모두 보여 준다. 연결이 없는 법인은 별도만.
  const annual = (e) => e.years.filter((y) => (y.period || "연간") === "연간");
  const interim = (e) => e.years.filter((y) => y.period && y.period !== "연간");
  const latest = list.map((e) => { const a = annual(e); return { ...e, ys: a.filter((y) => y.year === a[0]?.year), y: a[0] }; });
  const cols = [["자산", "assets"], ["부채", "liabilities"], ["자본", "equity"], ["영업수익", "revenue"], ["영업이익", "op_income"], ["당기순이익", "net_income"]];
  root.append(card("거래소별 비교 (가장 최근 사업연도 · 연결/별도)", "scale", table(["거래소(법인)", "연도", ...cols.map((c) => c[0]), "부채비율", "영업이익률"],
    latest.flatMap((e) => (e.ys.length
      ? e.ys.map((y) => [`${e.n}(${e.corp || ""})`, `${y.year} ${y.basis || ""}`, ...cols.map(([, key]) => (y[key] == null ? "-" : won(y[key]))), pct(ratioOf(y.liabilities, y.equity), 0), pct(ratioOf(y.op_income, y.revenue), 1)])
      : [[`${e.n}(${e.corp || ""})`, "공시 없음", ...cols.map(() => "-"), "-", "-"]])))));
  const interimRows = list.flatMap((e) => interim(e).map((y) => [`${e.n}(${e.corp || ""})`, `${y.year} ${y.period} ${y.basis || ""}`, ...cols.map(([, key]) => (y[key] == null ? "-" : won(y[key]))), pct(ratioOf(y.liabilities, y.equity), 0), pct(ratioOf(y.op_income, y.revenue), 1)]));
  if (interimRows.length) root.append(card("최근 분기·반기 (정기보고서 제출 법인, 손익은 누적)", "calendar", table(["거래소(법인)", "기간", ...cols.map((c) => c[0]), "부채비율", "영업이익률"], interimRows)));
  const bar = (key, label, color) => {
    const items = latest.filter((e) => e.y?.[key] != null).map((e) => ({ label: e.n, v: e.y[key], color: e.y[key] < 0 ? "#e5484d" : color }));
    return items.length ? card(label, "bar-chart-3", hbars(items.map((i) => ({ ...i, v: Math.max(i.v, 0) })), (v) => won(v))) : null;
  };
  root.append(h("div", { class: "cr-grid2" }, bar("revenue", "영업수익 비교", COLORS[0]), bar("net_income", "당기순이익 비교 (적자는 0 표시)", COLORS[1]), bar("assets", "자산총계 비교", COLORS[3]), bar("equity", "자본총계 비교", COLORS[2])));
  const sel = list.some((e) => e.k === params.ex) ? params.ex : (list.find((e) => e.years.length)?.k || "upbit");
  root.append(tabs(EXCHANGES.map((e) => e[1]), EXCHANGES.findIndex(([k]) => k === sel), (i) => ctx.go({ ...params, ex: EXCHANGES[i][0] }, { replace: true }), "seg"));
  const cur = list.find((e) => e.k === sel);
  const ys = cur.years;
  root.append(card(`${cur.n}(${cur.corp || ""}) 연도별 재무·손익`, "table", ys.length
    ? table(["항목", ...ys.map((y) => `${y.year}${y.period && y.period !== "연간" ? " " + y.period : ""} ${y.basis || ""}`)], [...cols.map(([nm, key]) => [nm, ...ys.map((y) => (y[key] == null ? "-" : won(y[key])))]),
      ["부채비율", ...ys.map((y) => pct(ratioOf(y.liabilities, y.equity), 0))], ["영업이익률", ...ys.map((y) => pct(ratioOf(y.op_income, y.revenue), 1))],
      ["출처", ...ys.map((y) => (y.source || "").replace("(DART)", ""))]])
    : note("이 거래소는 DART에서 가져올 수 있는 재무제표가 아직 없습니다(외부감사 대상이 아니거나 공시 전).")));
  root.append(note("법인은 DART 상호·법인등록번호로 확인한 값입니다(빗썸=주식회사 빗썸, 코빗=디지털엑스). 분·반기 손익은 해당 기간 누적입니다. 법인에 따라 사업보고서(연결/별도) 또는 감사보고서(별도)에서 가져와 계정 과목명이 다르면 일부 항목이 '-'로 보일 수 있습니다. 정확한 값은 DART 원문을 확인하세요. " + SRC_NOTE));
}

// ---------- 시장 일보 ----------

const reportTitle = (r) => (r.slot_label ? `${r.date} ${r.slot_label}` : r.date);
const reportLinks = (r) => h("span", { class: "cr-links" },
  h("a", { class: "btn sm", href: `${BASE}/${String(r.pdf).replace(/^\//, "")}`, target: "_blank", rel: "noopener" }, "PDF"),
  r.hwpx ? h("a", { class: "btn ghost sm", href: `${BASE}/${String(r.hwpx).replace(/^\//, "")}`, target: "_blank", rel: "noopener" }, "HWPX") : null);
// ---- 일보 저장(항목 선택): 최신 집계 자료로 브라우저에서 바로 HTML/PDF 문서를 만든다.
const esc = (v) => String(v ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const SAVE_ITEMS = [
  ["kpi", "핵심 지표", true], ["dom", "BTC 도미넌스(거래대금·시총)", true], ["ex", "거래소별 현황", true], ["coin", "주요 코인·스테이블코인 시세와 김프", true],
  ["top", "거래대금 상위", true], ["mv", "등락 상위", true], ["list", "신규 상장·폐지", true], ["fin", "거래소 재무·손익(DART)", false],
];
const tblHtml = (head, rows) => `<table><thead><tr>${head.map((t, i) => `<th${i ? ' class="r"' : ""}>${esc(t)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c, i) => `<td${i ? ' class="r"' : ""}>${esc(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
function reportHtml(data, picked) {
  const s = data.snapshot, d = s.domestic || {}, g = s.global || {}, sec = [];
  const add = (k, title, body) => picked.has(k) && body && sec.push(`<h2>${esc(title)}</h2>${body}`);
  const prev = findPrevious(data.history, s.date, s.slot || "");
  add("kpi", "핵심 지표", tblHtml(["지표", "값", "전일 대비"], [
    ["국내 24h 거래대금", won(d.total_value_24h) + " 원", chgSub(ratio(d.total_value_24h, prev?.total_value_24h)) || "-"],
    ["BTC 가격(업비트)", price(d.btc_price_krw) + " 원", chgSub(ratio(d.btc_price_krw, prev?.btc_price_krw)) || "-"],
    ["김치 프리미엄", pct(g.kimchi_premium, 2, true), pp(diff(g.kimchi_premium, prev?.kimchi_premium))],
    ["스테이블코인 거래 비중", pct(d.stable_share), "-"], ["글로벌 시가총액", usdT(g.total_mcap_usd), "-"], ["원/달러", num(g.usdkrw, 1), "-"]]));
  add("dom", "BTC 도미넌스", tblHtml(["기준", "비중"], [["국내 · 거래대금", pct(d.btc_dominance)], ["국내 · 시가총액", pct(d.btc_dom_mcap)], ["글로벌 · 거래대금", pct(g.btc_dom_volume)], ["글로벌 · 시가총액", pct(g.btc_dom_mcap)]]));
  add("ex", "거래소별 현황", tblHtml(["거래소", "종목", "24h 거래대금", "점유율", "BTC 도미넌스(대금)", "BTC 도미넌스(시총)"],
    (s.exchanges || []).filter((e) => e.ok).map((e) => [e.name || exName(e.exchange), e.markets, won(e.value_24h), pct(e.share), pct(e.btc_dominance), pct(e.btc_dom_mcap)])));
  const cp = s.coin_premium || {};
  add("coin", "주요 코인·스테이블코인 (업비트, 24시 마감)", Object.keys(cp).length ? tblHtml(["코인", "원화가", "해외가($)", "김프"], Object.entries(cp).map(([k, v]) => [k, price(v.krw), usd(v.usd), pct(v.premium, 2, true)])) : "");
  add("top", "거래대금 상위", tblHtml(["종목", "등락", "거래대금"], (s.top || []).map((r) => [r.symbol, pct(r.change_rate, 2, true), won(r.value_24h)])));
  add("mv", "등락 상위", tblHtml(["상승", "등락", "거래대금"], (s.gainers || []).map((r) => [r.symbol, pct(r.change_rate, 2, true), won(r.value_24h)])) + tblHtml(["하락", "등락", "거래대금"], (s.losers || []).map((r) => [r.symbol, pct(r.change_rate, 2, true), won(r.value_24h)])));
  const lst = Object.entries(s.listings || {});
  add("list", "신규 상장·상장 폐지", `<ul>${lst.map(([k, l]) => `<li><b>${esc(exName(k))}</b> 상장: ${esc((l.listed || []).join(", ") || "없음")} / 폐지: ${esc((l.delisted || []).join(", ") || "없음")}</li>`).join("")}</ul>`);
  const fx = data.fin?.exchanges || {};
  add("fin", "거래소 재무·손익 (DART, 최근 사업연도)", Object.keys(fx).length ? tblHtml(["거래소", "연도", "자산", "부채", "자본", "영업수익", "영업이익", "순이익"], EXCHANGES.filter(([k]) => fx[k]?.years?.length).flatMap(([k, n]) => { const an = fx[k].years.filter((y) => (y.period || "연간") === "연간"); return an.filter((y) => y.year === an[0]?.year).map((y) => [n, `${y.year} ${y.basis || ""}`, won(y.assets), won(y.liabilities), won(y.equity), won(y.revenue), won(y.op_income), won(y.net_income)]); })) : "");
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>국내 가상자산 시장 일보 ${esc(whenText(s))}</title><style>
body{font-family:'Malgun Gothic','Apple SD Gothic Neo',sans-serif;color:#1b2a41;max-width:860px;margin:24px auto;padding:0 16px}
h1{font-size:34px;line-height:1.25;margin:0 0 6px}.meta{color:#5b6b86;font-size:13px;margin-bottom:18px}
h2{font-size:19px;margin:22px 0 8px;border-bottom:2px solid #1f6feb;padding-bottom:4px}
table{border-collapse:collapse;width:100%;margin:6px 0 10px;font-size:13px}th,td{border:1px solid #d5dbe6;padding:5px 8px;text-align:left}th{background:#eef3fb}.r{text-align:right}
.foot{margin-top:26px;color:#5b6b86;font-size:12px}@media print{body{margin:0}}</style></head><body>
<h1>국내 가상자산 시장 일보</h1><div class="meta">기준 ${esc(whenText(s))} · 수집 ${esc(s.collected_at || "-")} · 업비트·빗썸·코인원·코빗·고팍스(원화마켓) · FSS Insights</div>
${sec.join("\n") || "<p>선택한 항목이 없습니다.</p>"}<div class="foot">${esc(SRC_NOTE)} 출처: 각 거래소 공개 API, ${esc(g.source || "CoinGecko")}, Coinbase${picked.has("fin") ? ", DART" : ""}</div></body></html>`;
}
function saveCard(data) {
  let saved = null;
  try { saved = JSON.parse(localStorage.getItem("fss.crypto.saveItems") || "null"); } catch { /* 저장소 사용 불가 */ }
  const boxes = SAVE_ITEMS.map(([k, label, on]) => {
    const cb = h("input", { type: "checkbox" });
    cb.checked = saved ? saved.includes(k) : on;
    return { k, cb, el: h("label", { class: "cr-pick" }, cb, " " + label) };
  });
  const picked = () => new Set(boxes.filter((b) => b.cb.checked).map((b) => b.k));
  const remember = () => { try { localStorage.setItem("fss.crypto.saveItems", JSON.stringify([...picked()])); } catch { /* 무시 */ } };
  const make = () => { remember(); return reportHtml(data, picked()); };
  const status = h("p", { class: "note" }, "");
  const dl = () => {
    if (!picked().size) { status.textContent = "저장할 항목을 하나 이상 선택해 주세요."; return; }
    const blob = new Blob([make()], { type: "text/html;charset=utf-8" });
    coreDownload(blob, `가상자산시장일보_${data.snapshot.date}.html`);
    status.textContent = "HTML 파일로 저장했습니다. 한글·워드에서 열거나 브라우저에서 인쇄해 PDF로 만들 수 있습니다.";
  };
  const pr = () => {
    if (!picked().size) { status.textContent = "저장할 항목을 하나 이상 선택해 주세요."; return; }
    const w = window.open("", "_blank");
    if (!w) { status.textContent = "팝업이 차단되었습니다. HTML 저장을 이용해 주세요."; return; }
    w.document.write(make()); w.document.close(); w.focus(); setTimeout(() => w.print(), 400);
  };
  return card("일보 저장 — 포함할 항목 선택", "download", h("div", { class: "stack" },
    h("div", { class: "cr-picks" }, boxes.map((b) => b.el)),
    h("div", { class: "cr-links" }, h("button", { type: "button", class: "btn sm", onclick: dl }, "HTML로 저장"), window.FssNative ? null : h("button", { type: "button", class: "btn ghost sm", onclick: pr }, "PDF로 저장(인쇄)")),
    status, note("선택한 항목만 담은 문서를 최신 집계로 만듭니다. 제목은 크게 표시됩니다. 아래 PDF·HWPX는 전체 항목 고정본입니다.")));
}
function reportView(root, data) {
  root.append(card("국내 가상자산 시장 일보", "file-text", note("매일 24:00 KST 하루 한 번 집계, A4 2쪽 · PDF·HWPX. BTC 가격·김치 프리미엄·시가총액·거래금액 요약, 거래소별 현황, BTC 도미넌스 국내 vs 글로벌, 거래대금 상위, 등락 상위, 신규 상장·상장 폐지를 담습니다. HWPX는 한글에서 열어 편집할 수 있습니다.")));
  root.append(saveCard(data));
  if (!data.reports.length) return root.append(note("아직 생성된 일보가 없습니다."));
  root.append(h("div", { class: "stack" }, data.reports.map((r) => h("div", { class: "cr-row card-row" }, h("span", null, `${reportTitle(r)} 일보`), reportLinks(r)))));
}

// ---------- 화면 ----------
export async function render(root, params, ctx) {
  setHeader("가상자산 시장 동향", "국내 원화거래소 5곳 합산 · 매일 24시 마감 집계 · 시세는 실시간", []);
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
  const inner = h("div", { class: "stack cr-root" });
  body.replaceChildren(inner);
  const goto = (tab) => ctx.go({ tab }, { replace: true });
  try {
    ({ summary: () => summary(inner, data, goto, ctx), coins: () => coinsView(inner, data, params, ctx), exchange: () => exchangeView(inner, data, params, ctx), fin: () => finView(inner, data, params, ctx), global: () => globalView(inner, data), report: () => reportView(inner, data) })[TABS[idx][0]]();
  } catch (e) {
    window.FSS_ERRLOG?.log("crypto", e.message, e.stack);
    inner.replaceChildren(message("화면을 그리지 못했습니다: " + errorText(e), { error: true }));
  }
}
