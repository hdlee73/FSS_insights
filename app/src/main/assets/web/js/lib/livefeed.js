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

/**
 * 한 거래소의 실시간 연결. 웹소켓이 열려 있으면 mode="ws", 아니면 3초 폴링(mode="poll"),
 * 폴링도 실패하면 mode="down". 웹소켓은 끊기면 1·2·4…최대 30초 간격으로 다시 시도한다.
 */
function exchangeFeed(ctx, { name, syms, wsUrl, hello, parse, poll, onData, onMode }) {
  let ws = null, retry = 0, retryTimer = 0, pollTimer = 0, mode = "";
  const setMode = (m) => { if (m !== mode) { mode = m; onMode(name, m); } };
  const stopPoll = () => { clearTimeout(pollTimer); pollTimer = 0; };
  const startPoll = () => {
    if (pollTimer || !ctx.alive()) return;
    const tick = async () => {
      pollTimer = 0;
      if (!ctx.alive() || document.hidden) return (pollTimer = setTimeout(tick, POLL_MS));
      try { onData(await poll(syms)); if (!ws || ws.readyState !== 1) setMode("poll"); } catch { if (!ws || ws.readyState !== 1) setMode("down"); }
      if (ctx.alive() && !(ws && ws.readyState === 1)) pollTimer = setTimeout(tick, POLL_MS);
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
      retryTimer = setTimeout(connect, Math.min(30_000, 1000 * 2 ** retry++));
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
 * 옵션 { bithumb:false, usd:false }로 빗썸·해외가 연결을 끌 수 있다(종목 차트는 업비트만 쓴다).
 * 반환값은 정리 함수(화면이 닫히면 자동으로도 정리된다).
 */
export function liveCrypto(ctx, syms, onChange, { bithumb = true, usd = true } = {}) {
  const state = { up: {}, bi: {}, usd: {}, mode: {}, at: 0 };
  let dirty = false, timer = 0;
  const flush = () => { timer = 0; if (!ctx.alive()) return stopAll(); if (dirty) { dirty = false; onChange(state); } };
  const touch = () => { state.at = Date.now(); dirty = true; if (!timer) timer = setTimeout(flush, 800); };
  const onMode = (k, m) => { state.mode[k] = m; touch(); };

  const stops = [
    exchangeFeed(ctx, {
      name: "upbit", syms, wsUrl: UPBIT_WS, poll: pollUpbit, onMode,
      hello: (s) => JSON.stringify([{ ticket: "fss-" + Math.random().toString(36).slice(2) }, { type: "ticker", codes: s.map((x) => "KRW-" + x), isOnlyRealtime: true }, { format: "DEFAULT" }]),
      parse: (m) => (m?.type === "ticker" && m.code ? { [String(m.code).replace("KRW-", "")]: { price: m.trade_price, rate: m.signed_change_rate } } : null),
      onData: (d) => { Object.assign(state.up, d); touch(); },
    }),
    ...(!bithumb ? [] : [exchangeFeed(ctx, {
      name: "bithumb", syms, wsUrl: BITHUMB_WS, poll: pollBithumb, onMode,
      hello: (s) => JSON.stringify({ type: "ticker", symbols: s.map((x) => x + "_KRW"), tickTypes: ["24H"] }),
      parse: (m) => (m?.type === "ticker" && m.content?.symbol ? { [String(m.content.symbol).replace("_KRW", "")]: { price: Number(m.content.closePrice), rate: Number(m.content.chgRate) / 100 } } : null),
      onData: (d) => { Object.assign(state.bi, d); touch(); },
    })]),
  ];
  // 해외 달러가는 웹소켓 없이 5초마다 읽는다.
  let usdTimer = 0, usdStop = false;
  const usdTick = async () => {
    usdTimer = 0;
    if (usdStop || !ctx.alive()) return;
    if (!document.hidden) { try { Object.assign(state.usd, await pollUsd(syms)); touch(); } catch { /* 다음 주기 */ } }
    if (!usdStop && ctx.alive()) usdTimer = setTimeout(usdTick, 5000);
  };
  if (usd) usdTick();
  function stopAll() { usdStop = true; clearTimeout(usdTimer); clearTimeout(timer); stops.forEach((f) => f()); }
  return stopAll;
}
