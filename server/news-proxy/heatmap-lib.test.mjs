// node server/news-proxy/heatmap-lib.test.mjs — 시총 히트맵 자료 정리·캐시 검증
import assert from "node:assert/strict";
import { buildHeatmap, buildEtfHeatmap, fixEtfCategory, heatmap, TOP, ETF_TOP } from "./heatmap-lib.js";
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

// 5) ETF: 정리(순자산 큰 순·중복/깨진 값 제외·분류 범위 밖은 7)·전체 합계·상위 ETF_TOP
const et = (code, name, cap, pct, tab) => ({ itemcode: code, itemname: name, marketSum: cap, changeRate: pct, etfTabCode: tab });
const de = buildEtfHeatmap({ result: { etfItemList: [et("a", "KODEX 200", 1000, -6.4, 1), et("b", "TIGER 미국S&P500", 2000, -1.4, 4), et("a", "중복", 5, 0, 1), et("c", "깨짐", "x", 1, 1), et("d", "분류밖", 50, 0.5, 99)] } }, 9);
assert.deepEqual(de.rows, [["b", "TIGER 미국S&P500", 2000, -1.4, 4], ["a", "KODEX 200", 1000, -6.4, 1], ["d", "분류밖", 50, 0.5, 7]]);
assert.equal(de.total, 3050);
assert.equal(de.count, 3);
assert.equal(buildEtfHeatmap({}).rows.length, 0);
const bigList = Array.from({ length: 300 }, (_, i) => et("e" + i, "n" + i, 300 - i, 0, 2));
assert.equal(buildEtfHeatmap({ result: { etfItemList: bigList } }).rows.length, ETF_TOP);

// 5-1) 분류 보정: NAVER 가 운용사마다 다르게 준 값을 종목명 기준으로 맞춤
assert.equal(fixEtfCategory("KODEX 머니마켓액티브", 7), 6);
assert.equal(fixEtfCategory("RISE 머니마켓액티브", 6), 6);
assert.equal(fixEtfCategory("TIGER CD1년금리액티브(합성)", 7), 6);
assert.equal(fixEtfCategory("KODEX 200미국채혼합50", 6), 7);
assert.equal(fixEtfCategory("KODEX 삼성전자채권혼합", 1), 7);
assert.equal(fixEtfCategory("TIGER 미국나스닥100레버리지(합성)", 4), 3);
assert.equal(fixEtfCategory("KODEX 국채선물10년인버스", 6), 3);
assert.equal(fixEtfCategory("TIGER 골드선물(H)", 4), 5);
assert.equal(fixEtfCategory("KIWOOM 미국원유에너지기업", 4), 4);
assert.equal(fixEtfCategory("TIGER 미국30년국채커버드콜액티브(H)", 6), 6);
assert.equal(fixEtfCategory("KODEX 200", 1), 1);

// 6) ETF 서버 처리: EUC-KR 해독, 1분 캐시, 빈 응답 재시도, 계속 비면 오류
const euc = (obj) => {
  // 테스트용 EUC-KR 인코딩: "한" 같은 한글을 직접 만들 수 없어 TextDecoder 가 같은 바이트를 한글로 읽는지만 본다(0xC7 0xD1 = 한).
  const head = new TextEncoder().encode('{"result":{"etfItemList":[{"itemcode":"069500","itemname":"KODEX ');
  const tail = new TextEncoder().encode('","changeRate":-6.51,"marketSum":188998,"etfTabCode":1}]}}');
  return new Uint8Array([...head, 0xc7, 0xd1, ...tail]);
};
const realFetch2 = globalThis.fetch;
let ecalls = 0, emptyFirst = 2;
globalThis.fetch = async () => {
  ecalls++;
  if (emptyFirst-- > 0) return new Response("", { status: 200 });
  return new Response(euc(), { status: 200 });
};
try {
  resetShared();
  const eurl = new URL("https://x/heatmap?market=ETF");
  const e1 = await (await heatmap(eurl, null)).json();
  assert.equal(ecalls, 3, "빈 응답 두 번 뒤 세 번째에 성공");
  assert.equal(e1.market, "ETF");
  assert.equal(e1.rows[0][1], "KODEX 한", "EUC-KR 해독");
  assert.equal(e1.rows[0][4], 1);
  await heatmap(eurl, null);
  assert.equal(ecalls, 3, "1분 안에는 NAVER 를 다시 부르지 않는다");
  resetShared();
  globalThis.fetch = async () => new Response("", { status: 200 });
  await assert.rejects(() => heatmap(eurl, null));
} finally {
  globalThis.fetch = realFetch2;
  resetShared();
}
console.log("heatmap-lib OK");
