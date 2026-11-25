// node server/news-proxy/heatmap-lib.test.mjs — 시총 히트맵 자료 정리·캐시 검증
import assert from "node:assert/strict";
import { buildHeatmap, heatmap, TOP } from "./heatmap-lib.js";
import { resetShared } from "./cache-lib.js";
import { categorize } from "./calls-lib.js";

const st = (code, name, raw, pct, type = "stock") => ({ itemCode: code, stockName: name, marketValueRaw: String(raw), fluctuationsRatio: String(pct), stockEndType: type, localTradedAt: "2026-11-25T09:48:21+09:00" });

// 1) ETF·잘못된 값 제외, 시총 큰 순, 억원 단위
const d = buildHeatmap([{ marketStatus: "OPEN", stocks: [st("1", "작은", 5e11, 1.2), st("2", "ETF", 9e14, 0, "etf"), st("3", "큰", 1.5e14, -7.29), st("4", "깨짐", "x", 1), st("5", "비율없음", 1e12, "N/A")] }], "KOSPI", 123);
assert.deepEqual(d.rows, [["3", "큰", 1500000, -7.29], ["1", "작은", 5000, 1.2]]);
assert.equal(d.status, "OPEN");
assert.equal(d.at, 123);

// 2) 상위 TOP개만, 중복 종목 한 번만
const many = Array.from({ length: 300 }, (_, i) => st(String(1000 + i), "n" + i, (300 - i) * 1e10, 0));
const dm = buildHeatmap([{ stocks: many.slice(0, 200) }, { stocks: many.slice(100) }], "KOSDAQ");
assert.equal(dm.rows.length, TOP);
assert.equal(new Set(dm.rows.map((r) => r[0])).size, TOP);

// 3) 경로 분류
assert.equal(categorize("GET", "/heatmap", new URLSearchParams()), "heatmap");

// 4) 서버 처리: 2페이지 호출, 1분 안 재요청은 상류 호출 없음, 2페이지 실패면 partial(기억 안 함)
const realFetch = globalThis.fetch;
let calls = 0, failPage2 = false;
globalThis.fetch = async (u) => {
  calls++;
  const n = new URL(u).searchParams.get("page");
  if (n === "2" && failPage2) return new Response("x", { status: 500 });
  return new Response(JSON.stringify({ marketStatus: "OPEN", stocks: [st("P" + n, "p" + n, (3 - Number(n)) * 1e12, 1)] }), { status: 200 });
};
try {
  resetShared();
  const url = new URL("https://x/heatmap?market=KOSDAQ");
  const a = await (await heatmap(url, null)).json();
  assert.equal(calls, 2);
  assert.equal(a.market, "KOSDAQ");
  assert.equal(a.rows.length, 2);
  await heatmap(url, null);
  assert.equal(calls, 2, "1분 안에는 NAVER 를 다시 부르지 않는다");
  resetShared();
  failPage2 = true;
  calls = 0;
  const b = await (await heatmap(url, null)).json();
  assert.equal(b.partial, true);
  assert.equal(b.rows.length, 1);
  await heatmap(url, null);
  assert.ok(calls > 4, "일부만 받은 결과는 기억하지 않고 다시 시도한다");
  // 1페이지 실패면 오류
  resetShared();
  globalThis.fetch = async () => new Response("x", { status: 500 });
  await assert.rejects(() => heatmap(url, null));
} finally {
  globalThis.fetch = realFetch;
  resetShared();
}
console.log("heatmap-lib OK");
