// 가상자산 실시간 시세: 업비트·빗썸 웹소켓으로 받고, 연결이 끊기면 REST 폴링으로 대신하다가 다시 웹소켓을 붙인다.
// 브라우저에서 직접 연결하며 API 키는 필요 없다. 화면이 닫히면(ctx.alive()가 false) 모든 연결을 정리한다.

const UPBIT_WS = "wss://api.upbit.com/websocket/v1";
const BITHUMB_WS = "wss://pubwss.bithumb.com/pub/ws";
const POLL_MS = 3000;

const text = (d) => (typeof d === "string" ? d : new TextDecoder().decode(d));

async function pollUpbit(syms) {
  const r = await fetch("https://api.upbit.com/v1/ticker?markets=" + syms.map((x) => "KRW-" + x).join(","));
  if (!r.ok) throw new Error("upbit " + r.status);
  const out = {};
  for (const t of await r.json()) out[String(t.market).replace("KRW-", "")] = { price: t.trade_price, rate: t.signed_change_rate };
  return out;
}
async function pollBithumb(syms) {
  const r = await fetch("https://api.bithumb.com/public/ticker/ALL_KRW");
  if (!r.ok) throw new Error("bithumb " + r.status);
  const data = (await r.json()).data || {};
  const out = {};
  for (const x of syms) if (data[x]) out[x] = { price: Number(data[x].closing_price), rate: Number(data[x].fluctate_rate_24H) / 100 };
  return out;
}
async function pollUsd(syms) {
  const rs = await Promise.allSettled(syms.map(async (x) => {
    const r = await fetch(`https://api.exchange.coinbase.com/products/${x}-USD/ticker`);
    if (!r.ok) throw new Error("cb " + r.status);
    return [x, Number((await r.json()).price)];
  }));
  const out = {};
  for (const r of rs) if (r.status === "fulfilled" && Number.isFinite(r.value[1])) out[r.value[0]] = r.value[1];
  return out;
}

// ---------- 상장 종목 목록(세션당 한 번만 읽어 재사용) ----------
// 없는 종목을 업비트 REST·웹소켓에 섞어 보내면 요청 전체가 실패하므로, 상장 목록으로 걸러서 보낸다.
let marketsP = null, cbP = null, cbSet = null;
const jget = async (url) => { const r = await fetch(url); if (!r.ok) throw new Error(url + " " + r.status); return r.json(); };
/** 업비트(원화)·빗썸 상장 목록. 실패하면 null(걸러내지 않고 그대로 시도). {upbit: Map(심볼→{ko,en}), bithumb: Set} */
export function loadMarkets() {
  if (!marketsP) {
    marketsP = Promise.allSettled([jget("https://api.upbit.com/v1/market/all"), jget("https://api.bithumb.com/public/ticker/ALL_KRW")]).then(([u, b]) => {
      const out = { upbit: null, bithumb: null };
      if (u.status === "fulfilled" && Array.isArray(u.value)) {
        out.upbit = new Map();
        for (const m of u.value) if (String(m.market).startsWith("KRW-")) out.upbit.set(m.market.slice(4), { ko: m.korean_name || "", en: m.english_name || "" });
      }
      if (b.status === "fulfilled" && b.value?.data) out.bithumb = new Set(Object.keys(b.value.data).filter((k) => k !== "date"));
      if (!out.upbit && !out.bithumb) marketsP = null; // 둘 다 실패하면 다음에 다시 시도
      return out;
    });
  }
  return marketsP;
}
/** Coinbase 달러 상장 목록(검색할 때만 읽음). 실패하면 null. */
export function loadCoinbase() {
  if (!cbP) {
    cbP = jget("https://api.exchange.coinbase.com/products").then((a) => (cbSet = new Set(a.filter((p) => p.quote_currency === "USD" && p.status === "online").map((p) => p.base_currency)))).catch(() => { cbP = null; return null; });
  }
  return cbP;
}

/**
 * 한 거래소의 실시간 연결. 웹소켓이 열려 있으면 mode="ws", 아니면 3초 폴링(mode="poll"),
 * 폴링도 실패하면 mode="down". 웹소켓은 끊기면 1·2·4…최대 30초 간격으로 다시 시도한다.
 */
function exchangeFeed(ctx, { name, syms, wsUrl, hello, parse, poll, onData, onMode, pollMs = POLL_MS, minRetryMs = 1000 }) {
  let ws = null, retry = 0, retryTimer = 0, pollTimer = 0, mode = "";
  const setMode = (m) => { if (m !== mode) { mode = m; onMode(name, m); } };
  const stopPoll = () => { clearTimeout(pollTimer); pollTimer = 0; };
  const startPoll = () => {
    if (pollTimer || !ctx.alive()) return;
    const tick = async () => {
      pollTimer = 0;
      if (!ctx.alive() || document.hidden) return (pollTimer = setTimeout(tick, pollMs));
      try { onData(await poll(syms)); if (!ws || ws.readyState !== 1) setMode("poll"); } catch { if (!ws || ws.readyState !== 1) setMode("down"); }
      if (ctx.alive() && !(ws && ws.readyState === 1)) pollTimer = setTimeout(tick, pollMs);
    };
    tick();
  };
  const connect = () => {
    if (!ctx.alive() || document.hidden || typeof WebSocket === "undefined") { startPoll(); return; }
    try {
      ws = new WebSocket(wsUrl);
      ws.binaryType = "arraybuffer";
    } catch { startPoll(); return; }
    const mine = ws;
    mine.onopen = () => { retry = 0; mine.send(hello(syms)); };
    mine.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(text(ev.data)); } catch { return; }
      const got = parse(m);
      if (got) { stopPoll(); setMode("ws"); onData(got); }
    };
    const lost = () => {
      if (ws !== mine) return;
      ws = null;
      if (!ctx.alive()) return;
      startPoll(); // 끊긴 동안은 폴링으로 이어 간다.
      retryTimer = setTimeout(connect, Math.min(30_000, Math.max(minRetryMs, 1000 * 2 ** retry++)));
    };
    mine.onclose = lost;
    mine.onerror = () => { try { mine.close(); } catch { /* 이미 닫힘 */ } };
  };
  const onVis = () => {
    if (!ctx.alive()) return stop();
    if (document.hidden) { // 숨은 탭은 연결을 내려 자원을 아낀다.
      clearTimeout(retryTimer);
      const w = ws; ws = null;
      try { w?.close(); } catch { /* 무시 */ }
      stopPoll();
    } else if (!ws) { retry = 0; connect(); }
  };
  function stop() {
    document.removeEventListener("visibilitychange", onVis);
    clearTimeout(retryTimer); stopPoll();
    const w = ws; ws = null;
    try { w?.close(); } catch { /* 무시 */ }
  }
  document.addEventListener("visibilitychange", onVis);
  connect();
  return stop;
}

/**
 * 코인 목록(syms)의 업비트·빗썸 시세와 해외(Coinbase, 달러) 시세를 계속 받는다.
 * onChange(state)는 최대 초당 1회 호출된다. state = { up, bi, usd, mode:{upbit,bithumb}, at }.
 * 반환값은 정리 함수(화면이 닫히면 자동으로도 정리된다).
 *
 * 업비트는 같은 주소(IP)·같은 출처(Origin)에서 웹소켓을 동시에 여러 개 열면 두 번째부터 거절(1006)하고
 * REST도 Origin 기준으로 10초에 1회로 제한한다. 그래서 한 화면에서 여러 표·차트가 시세를 써도
 * 연결은 거래소마다 하나만 열어 모두가 나눠 쓴다(허브). 코인이 늘면 합집합으로 다시 연결한다.
 */
let hub = null;

function makeHub() {
  const h = { subs: new Set(), built: new Set(), state: { up: {}, bi: {}, usd: {}, mode: {}, at: 0 }, stops: [], usdTimer: 0, rebuildTimer: 0, flushTimer: 0, fillTimer: 0, fillUpTimer: 0, gen: 0, building: false, dead: false };
  const ctx = { alive: () => !h.dead && [...h.subs].some((x) => x.ctx.alive()) };
  const st = h.state;
  let dirty = false;
  const flush = () => {
    h.flushTimer = 0;
    for (const x of [...h.subs]) if (!x.ctx.alive()) h.subs.delete(x);
    if (!h.subs.size) return stopHub();
    if (!dirty) return;
    dirty = false;
    for (const x of h.subs) { try { x.cb(st); } catch { /* 한 화면의 오류가 다른 화면을 막지 않게 */ } }
  };
  const touch = () => { st.at = Date.now(); dirty = true; if (!h.flushTimer) h.flushTimer = setTimeout(flush, 800); };
  const onMode = (k, m) => { st.mode[k] = m; touch(); };
  const fillMissing = (target, d) => { for (const [k, v] of Object.entries(d)) if (!target[k]) target[k] = v; };
  /** 지금 살아 있는 구독이 원하는 코인의 합집합(닫힌 화면의 코인은 빠진다). */
  const wanted = () => {
    const set = new Set();
    for (const x of h.subs) if (x.ctx.alive()) for (const s of x.syms) set.add(s);
    return set;
  };
  h.wanted = wanted;

  function stopFeeds() {
    h.stops.forEach((f) => f());
    h.stops = [];
    clearTimeout(h.usdTimer); h.usdTimer = 0;
    clearTimeout(h.fillTimer); h.fillTimer = 0;
    clearTimeout(h.fillUpTimer); h.fillUpTimer = 0;
  }
  function stopHub() {
    h.dead = true;
    clearTimeout(h.flushTimer); clearTimeout(h.rebuildTimer);
    stopFeeds();
    if (hub === h) hub = null;
  }
  async function build() {
    h.rebuildTimer = 0;
    if (h.dead) return;
    const gen = ++h.gen;
    h.building = true;
    try { await build1(gen); } finally { if (gen === h.gen) h.building = false; }
  }
  async function build1(gen) {
    const hadFeeds = h.stops.length > 0;
    stopFeeds();
    const all = [...wanted()];
    h.built = new Set(all);
    if (!all.length) return;
    // 같은 출처에서 웹소켓을 닫자마자 다시 열면 업비트가 거절하는 일이 있어, 다시 연결할 때는 잠깐 쉰다.
    if (hadFeeds) await new Promise((r) => setTimeout(r, 900));
    const mk = await loadMarkets().catch(() => ({}));
    if (h.dead || gen !== h.gen || !ctx.alive()) return;
    // 상장되지 않은 코인은 해당 거래소 요청에서 뺀다(하나만 섞여도 요청 전체가 거절되기 때문).
    const upSyms = mk.upbit ? all.filter((x) => mk.upbit.has(x)) : all;
    const biSyms = mk.bithumb ? all.filter((x) => mk.bithumb.has(x)) : all;
    const usdSyms = cbSet ? all.filter((x) => cbSet.has(x)) : all; // 검색으로 목록을 읽어 둔 경우에만 거른다(없으면 개별 조회가 404여도 건너뜀)
    // 업비트 REST는 Origin 기준 10초에 1회 제한이라 폴링 간격을 11초로 둔다.
    if (upSyms.length) h.stops.push(exchangeFeed(ctx, {
      name: "upbit", syms: upSyms, wsUrl: UPBIT_WS, poll: pollUpbit, onMode, pollMs: 11000, minRetryMs: 5000,
      hello: (s) => JSON.stringify([{ ticket: "fss-" + Math.random().toString(36).slice(2) }, { type: "ticker", codes: s.map((x) => "KRW-" + x) }, { format: "DEFAULT" }]),
      parse: (m) => (m?.type === "ticker" && m.code ? { [String(m.code).replace("KRW-", "")]: { price: m.trade_price, rate: m.signed_change_rate } } : null),
      onData: (d) => { Object.assign(st.up, d); touch(); },
    }));
    else onMode("upbit", "none");
    if (biSyms.length) h.stops.push(exchangeFeed(ctx, {
      name: "bithumb", syms: biSyms, wsUrl: BITHUMB_WS, poll: pollBithumb, onMode, pollMs: POLL_MS,
      hello: (s) => JSON.stringify({ type: "ticker", symbols: s.map((x) => x + "_KRW"), tickTypes: ["24H"] }),
      parse: (m) => (m?.type === "ticker" && m.content?.symbol ? { [String(m.content.symbol).replace("_KRW", "")]: { price: Number(m.content.closePrice), rate: Number(m.content.chgRate) / 100 } } : null),
      onData: (d) => { Object.assign(st.bi, d); touch(); },
    }));
    else onMode("bithumb", "none");
    // 웹소켓이 코인 일부만 보내거나 거래가 뜸한 코인은 오래 비어 있을 수 있어, 비어 있는 칸만 REST로 채운다.
    // 빗썸은 시작할 때와 30초마다, 업비트는 비는 코인이 있을 때만 1.5초 뒤부터 15초 간격(Origin당 10초 1회 제한 안)으로 시도한다.
    const fillBithumb = async () => {
      if (h.dead || gen !== h.gen || !ctx.alive()) return;
      if (!document.hidden && biSyms.length) { try { fillMissing(st.bi, await pollBithumb(biSyms)); touch(); } catch { /* 다음 주기 */ } }
      if (!h.dead && gen === h.gen && ctx.alive()) h.fillTimer = setTimeout(fillBithumb, 30000);
    };
    fillBithumb();
    const fillUpbit = async () => {
      h.fillUpTimer = 0;
      if (h.dead || gen !== h.gen || !ctx.alive()) return;
      const miss = upSyms.filter((x) => !st.up[x]);
      if (!miss.length) return; // 다 채워졌으면 멈춘다(웹소켓이 이어서 갱신)
      if (!document.hidden) { try { fillMissing(st.up, await pollUpbit(miss)); touch(); } catch { /* 다음 주기 */ } }
      if (!h.dead && gen === h.gen && ctx.alive()) h.fillUpTimer = setTimeout(fillUpbit, 15000);
    };
    if (upSyms.length) h.fillUpTimer = setTimeout(fillUpbit, 1500);
    // 해외 달러가는 웹소켓 없이 5초마다 읽는다.
    const usdTick = async () => {
      h.usdTimer = 0;
      if (h.dead || gen !== h.gen || !ctx.alive()) return;
      if (!document.hidden && usdSyms.length) { try { Object.assign(st.usd, await pollUsd(usdSyms)); touch(); } catch { /* 다음 주기 */ } }
      if (!h.dead && gen === h.gen && ctx.alive()) h.usdTimer = setTimeout(usdTick, 5000);
    };
    usdTick();
  }
  h.schedule = () => { if (!h.rebuildTimer) h.rebuildTimer = setTimeout(build, 0); }; // 같은 순간에 붙은 구독은 타이머 한 번으로 묶인다.
  h.stop = stopHub;
  return h;
}

export function liveCrypto(ctx, syms, onChange) {
  if (!hub || hub.dead) hub = makeHub();
  const h = hub;
  const sub = { ctx, cb: onChange, syms: [...new Set(syms)] };
  h.subs.add(sub);
  const grew = sub.syms.some((x) => !h.built.has(x)) || (h.stops.length === 0 && !h.building && !h.rebuildTimer);
  if (grew) { for (const x of sub.syms) h.built.add(x); h.schedule(); }
  else { try { onChange(h.state); } catch { /* 무시 */ } } // 이미 받아 둔 값이 있으면 바로 보여 준다.
  return () => { h.subs.delete(sub); if (!h.subs.size) h.stop(); };
}
