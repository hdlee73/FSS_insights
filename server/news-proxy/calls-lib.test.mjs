// node server/news-proxy/calls-lib.test.mjs — 경로별 호출 집계·공유 캐시 검증
import assert from "node:assert/strict";
import { categorize, sourceOf, hourKey, summarizeCalls, recordCall, adminCalls, _reset, _state, _flush, CATS } from "./calls-lib.js";
import { shared, resetShared, snapOf, toResponse } from "./cache-lib.js";
import { proxyFetch, cacheSeconds } from "./web-lib.js";

// 1) 경로 분류
const q = (s) => new URL("https://x/?" + s).searchParams;
assert.equal(categorize("GET", "/admin", q("")), "admin");
assert.equal(categorize("GET", "/admin/api", q("")), "admin");
assert.equal(categorize("POST", "/usage/ping", q("")), "usage");
assert.equal(categorize("GET", "/fetch", q("url=" + encodeURIComponent("https://stock.naver.com/api/x"))), "quote");
assert.equal(categorize("GET", "/fetch", q("url=" + encodeURIComponent("https://query1.finance.yahoo.com/v8/finance/chart/^KS11"))), "quote");
assert.equal(categorize("GET", "/fetch", q("url=" + encodeURIComponent("https://www.fss.or.kr/a"))), "fetch");
assert.equal(categorize("GET", "/fetch", q("url=bad")), "fetch");
assert.equal(categorize("OPTIONS", "/fetch", q("")), "preflight");
for (const [p, c] of [["/news", "news"], ["/stats", "stats"], ["/notices", "notices"], ["/law/DRF/lawSearch.do", "law"], ["/dart/company", "dart"], ["/fisis/ranking", "fisis"], ["/library/list", "library"], ["/board/posts", "board"], ["/zzz", "other"]]) assert.equal(categorize("GET", p, q("")), c, p);
for (const c of ["admin", "usage", "quote", "fetch", "news", "stats", "law", "dart", "fisis", "library", "notices", "board", "preflight", "other"]) assert.ok(c in CATS);

// 2) 출처
assert.equal(sourceOf("https://www.fssinsights.com", null, "https://a.com,https://www.fssinsights.com"), "web");
assert.equal(sourceOf(null, "tok", "https://a.com"), "app");
assert.equal(sourceOf("https://evil.com", null, "https://a.com"), "other");

// 3) 일자별 합계(UTC·KST 경계). UTC 2026-11-17 16:00 = KST 11-18 01:00
const rows = [
  { hour: "2026-11-17T14:00".slice(0, 13), cat: "quote", src: "web", hits: 10 }, // KST 11-17 23시
  { hour: "2026-11-17T15", cat: "quote", src: "web", hits: 5 }, // KST 11-18 00시
  { hour: "2026-11-18T03", cat: "admin", src: "other", hits: 2 },
  { hour: "2026-11-18T03", cat: "news", src: "app", hits: 3 },
  { hour: "2026-11-10T03", cat: "news", src: "app", hits: 9 }, // 범위 밖
];
const now = Date.parse("2026-11-18T04:00:00Z");
const utc = summarizeCalls(rows, 0, 2, now), kst = summarizeCalls(rows, 9, 2, now);
assert.equal(utc[0].day, "2026-11-18");
assert.equal(utc[0].total, 5); assert.equal(utc[0].admin, 2); assert.equal(utc[0].normal, 3); assert.equal(utc[0].bySrc.app, 3);
assert.equal(utc[1].total, 15); assert.equal(utc[1].bySrc.web, 15);
assert.equal(kst[0].day, "2026-11-18");
assert.equal(kst[0].total, 10); // KST 11-18: 15시(UTC 11-17T15) 5 + 03시 5
assert.equal(kst[1].total, 10); // KST 11-17: UTC 11-17T14 의 10
assert.equal(kst[0].byCat.quote.web, 5);

// 4) 모아 기록: 첫 요청은 바로 기록, 이후는 3분 뒤에 묶어서
const calls = [];
const db = {
  prepare(sql) { return { sql, bind: (...a) => ({ sql, a }), run: async () => { calls.push(["run", sql]); } }; },
  async batch(list) { calls.push(["batch", list.map((x) => x.a)]); },
};
const env = { DB: db, WEB_ORIGINS: "https://web.example" };
const waits = [];
const ctx = { waitUntil: (p) => waits.push(p) };
const req = (path, h = {}, method = "GET") => new Request("https://w.example" + path, { method, headers: h });
_reset();
const t0 = Date.parse("2026-11-18T04:00:10Z");
recordCall(env, ctx, req("/news?query=a", { "X-App-Token": "t" }), new URL("https://w.example/news?query=a"), t0);
assert.equal(waits.length, 1);
await Promise.all(waits.splice(0));
let batches = calls.filter((c) => c[0] === "batch");
assert.equal(batches.length, 1);
assert.deepEqual(batches[0][1][0], ["2026-11-18T04", "news", "app", 1]);
for (let i = 0; i < 5; i++) recordCall(env, ctx, req("/fetch?url=x", { Origin: "https://web.example" }), new URL("https://w.example/fetch?url=x"), t0 + 1000 * i);
assert.equal(waits.length, 0, "3분 안에는 다시 기록하지 않는다");
assert.equal(_state().pending.get("2026-11-18T04|fetch|web"), 5);
recordCall(env, ctx, req("/admin"), new URL("https://w.example/admin"), t0 + 181000);
assert.equal(waits.length, 1);
await Promise.all(waits.splice(0));
batches = calls.filter((c) => c[0] === "batch");
assert.equal(batches.length, 2);
assert.equal(batches[1][1].length, 2); // fetch|web 5, admin|other 1
assert.equal(_state().pending.size, 0);

// D1 가 없거나 실패해도 요청 처리에 영향이 없다
recordCall({}, ctx, req("/news"), new URL("https://w.example/news"), t0);
const bad = { DB: { prepare: () => ({ run: async () => { throw new Error("boom"); }, bind: () => ({}) }), batch: async () => { throw new Error("boom"); } } };
_reset();
recordCall(bad, ctx, req("/news"), new URL("https://w.example/news"), t0);
await Promise.all(waits.splice(0));
assert.equal(_state().pending.size, 1, "실패하면 대기열로 되돌린다");

// 5) /admin/calls 인증·응답
const denied = await adminCalls(req("/admin/calls"), new URL("https://w.example/admin/calls"), { ADMIN_TOKEN: "s", DB: db });
assert.equal(denied.status, 401);
const dbRead = { prepare: (sql) => ({ run: async () => {}, bind: () => ({ all: async () => ({ results: [{ hour: hourKey(Date.now()), cat: "news", src: "web", hits: 4 }] }) }) }) };
const ok = await adminCalls(req("/admin/calls", { "X-Admin-Token": "s" }), new URL("https://w.example/admin/calls?days=3"), { ADMIN_TOKEN: "s", DB: dbRead });
const body = await ok.json();
assert.equal(body.kst.length, 3); assert.equal(body.utc[0].byCat.news.web, 4); assert.equal(body.limit, 100000);

// 6) 공유 캐시: 동시 요청 합치기, 메모리 보관, 실패 비보관
resetShared();
let builds = 0;
const mk = (status = 200, extra = {}) => async () => { builds++; await new Promise((r) => setTimeout(r, 20)); return { status, headers: { "content-type": "text/plain" }, body: new TextEncoder().encode("hi").buffer, ...extra }; };
const [a, b, c] = await Promise.all([shared("k", 5000, mk()), shared("k", 5000, mk()), shared("k", 5000, mk())]);
assert.equal(builds, 1); assert.equal(a, b); assert.equal(await toResponse(a).text(), "hi"); assert.equal(await toResponse(a).text(), "hi", "응답을 여러 번 만들 수 있다");
await shared("k", 5000, mk()); assert.equal(builds, 1, "메모리에서 재사용");
await shared("k", 5000, mk(), Date.now() + 6000); assert.equal(builds, 2, "ttl 지나면 다시");
await shared("bad", 5000, mk(500)); await shared("bad", 5000, mk(500)); assert.equal(builds, 4, "오류 응답은 보관하지 않는다");
await shared("vol", 5000, mk(200, { volatile: true })); await shared("vol", 5000, mk(200, { volatile: true })); assert.equal(builds, 6);
await assert.rejects(shared("err", 5000, async () => { throw new Error("x"); }));
await shared("err", 5000, mk()); assert.equal(builds, 7, "던진 오류는 기억하지 않는다");

// 7) /fetch: 같은 시세 동시 요청은 상류 한 번
resetShared();
const store = new Map();
globalThis.caches = { default: { match: async (r) => (store.get(r.url) ? store.get(r.url).clone() : undefined), put: async (r, res) => { store.set(r.url, res); } } };
let upstream = 0;
globalThis.fetch = async (u) => { upstream++; await new Promise((r) => setTimeout(r, 20)); const res = new Response('{"p":1}', { headers: { "Content-Type": "application/json" } }); Object.defineProperty(res, "url", { value: u }); return res; };
const pend = [];
const fctx = { waitUntil: (p) => pend.push(p) };
const target = "https://stock.naver.com/api/realtime/domestic/stock/005930/basic";
const furl = new URL("https://w.example/fetch?url=" + encodeURIComponent(target));
const rs = await Promise.all([1, 2, 3, 4].map(() => proxyFetch(req("/fetch"), furl, fctx)));
assert.equal(upstream, 1);
for (const r of rs) { assert.equal(r.status, 200); assert.equal(await r.text(), '{"p":1}'); assert.equal(r.headers.get("Cache-Control"), "public, max-age=3"); }
await Promise.all(pend);
await proxyFetch(req("/fetch"), furl, fctx); assert.equal(upstream, 1, "메모리 캐시");
resetShared(); // 다른 인스턴스: Cache API 에서 읽는다
const r5 = await proxyFetch(req("/fetch"), furl, fctx); assert.equal(upstream, 1); assert.equal(await r5.text(), '{"p":1}');
// 느린 자료는 낡은 값 허용(swr), 비허용 호스트·POST 는 기존대로
const listUrl = new URL("https://w.example/fetch?url=" + encodeURIComponent("https://www.fss.or.kr/x"));
assert.equal((await proxyFetch(req("/fetch"), listUrl, fctx)).headers.get("Cache-Control"), "public, max-age=600, stale-while-revalidate=600");
assert.equal((await proxyFetch(req("/fetch"), new URL("https://w.example/fetch?url=" + encodeURIComponent("https://evil.com/")), fctx)).status, 403);
upstream = 0;
await proxyFetch(req("/fetch", {}, "POST"), listUrl, fctx); await proxyFetch(req("/fetch", {}, "POST"), listUrl, fctx);
assert.equal(upstream, 2, "POST 는 캐시하지 않는다");
assert.equal(cacheSeconds(new URL(target)), 3);
console.log("calls-lib / cache-lib tests passed");
