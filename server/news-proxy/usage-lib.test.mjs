// node server/news-proxy/usage-lib.test.mjs  — 방문·사용 시간 집계 검증
import assert from "node:assert/strict";
import { sanitizePing, engagement } from "./usage-lib.js";

const now = Date.parse("2026-11-13T03:00:00Z");
const id = "a".repeat(20);

// 1) 옛 클라이언트(vis/sec 없음)도 그대로 받아들인다
let p = sanitizePing({ id, v: "2.6.0", days: [{ d: "2026-11-13", m: { "뉴스 검색": 2 } }] }, now);
assert.equal(p.days[0].visits, null);
assert.equal(p.days[0].secs, null);
assert.equal(p.days[0].menus["뉴스 검색"], 2);

// 2) 새 필드: 정수·상한 보정, 이상한 값은 0
p = sanitizePing({ id, v: "web-2.20.0", days: [{ d: "2026-11-13", m: {}, vis: 3.9, sec: 99999999 }, { d: "2026-11-12", m: {}, vis: "x", sec: -5 }] }, now);
assert.equal(p.days[0].visits, 3);
assert.equal(p.days[0].secs, 86400);
assert.equal(p.days[1].visits, 0);
assert.equal(p.days[1].secs, 0);

// 3) 집계
const allRows = [
  { day: "2026-11-12", uid: "u1", version: "2.7.0" },
  { day: "2026-11-12", uid: "u2", version: "web-2.20.0" },
  { day: "2026-11-12", uid: "u3", version: "web-2.19.0" }, // 옛 웹: 측정 없음
  { day: "2026-11-13", uid: "u1", version: "2.7.0" },
];
const activityRows = [
  { day: "2026-11-12", uid: "u1", visits: 2, secs: 600, last_at: 1763000000000 },
  { day: "2026-11-12", uid: "u2", visits: 6, secs: 1800 },
  { day: "2026-11-13", uid: "u1", visits: 4, secs: 120 },
];
let e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13" });
assert.equal(e.userDays, 3);
assert.equal(e.avgVisits, 4);
assert.equal(e.maxVisits, 6);
assert.equal(e.avgSecs, 840);
assert.equal(e.maxSecs, 1800);
assert.equal(e.firstDay, "2026-11-12");
const d12 = e.series.find((x) => x.day === "2026-11-12");
assert.deepEqual([d12.dau, d12.users, d12.avgVisits, d12.maxVisits], [3, 2, 4, 6]);
assert.equal(e.series.find((x) => x.day === "2026-11-11").avgVisits, null);
assert.deepEqual(d12.list.map((x) => [x.id, x.p, x.visits, x.secs]), [["u2", "web", 6, 1800], ["u1", "app", 2, 600]]); // 사용 시간 긴 순
assert.deepEqual(d12.list.map((x) => x.at), [null, 1763000000000]);
assert.deepEqual(d12.list.map((x) => [x.cd, x.cv, x.cs]), [[1, 6, 1800], [2, 6, 720]]); // u1: 11/12(2회·600초)+11/13(4회·120초), u2: 11/12 하루
assert.equal(e.series.find((x) => x.day === "2026-11-11").list.length, 0);
assert.equal(e.visitDist["2~3회"], 1);
assert.equal(e.timeDist["15~30분"], 1);
assert.equal(e.timeDist["1~5분"], 1);

e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2000-01-01", uto: "2026-11-13" });
assert.deepEqual(e.period.users.filter((x) => !x.un).map((x) => [x.id, x.days, x.visits, x.secs]), [["u2", 1, 6, 1800], ["u1", 2, 6, 720]]);
assert.equal(e.period.from, "2026-11-12");
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2026-11-13", uto: "2026-11-13" });
assert.deepEqual(e.period.users.map((x) => [x.id, x.days, x.visits, x.secs]), [["u1", 1, 4, 120]]);

// 3-b) 접속일은 측정 여부와 무관하게 접속 신호를 받은 모든 날(방문·시간은 측정된 날만)
{
  const rows = ["2026-11-05", "2026-11-06", "2026-11-07", "2026-11-13"].map((day) => ({ day, uid: "b04da1xx", version: "2.8.1" }));
  const act = [{ day: "2026-11-13", uid: "b04da1xx", visits: 3, secs: 90 }];
  const r = engagement({ allRows: rows, activityRows: act, from: "2026-11-13", to: "2026-11-13", ufrom: "2000-01-01", uto: "2026-11-13" });
  assert.deepEqual(r.period.users.map((x) => [x.id, x.days, x.visits, x.secs]), [["b04da1", 4, 3, 90]]);
  assert.equal(r.period.from, "2026-11-05");
  assert.equal(r.series[0].list[0].cd, 4);
  assert.equal(r.series[0].list[0].cv, 3);
}

// 4) 플랫폼 필터
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", platform: "web" });
assert.equal(e.userDays, 1);
assert.equal(e.avgVisits, 6);
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", platform: "app" });
assert.equal(e.userDays, 2);
assert.equal(e.avgVisits, 3);

// 5) 자료 없음
e = engagement({ allRows, activityRows: [], from: "2026-11-11", to: "2026-11-13" });
assert.equal(e.avgVisits, null);
assert.equal(e.firstDay, "");
// 버전 표시: 앱은 앱 버전, 웹은 'web-' 제거, 기간 표는 가장 최근 날짜의 버전
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2000-01-01", uto: "2026-11-13" });
assert.equal(e.period.users.find((u) => u.id === "u2".slice(0, 6)).ver, "2.20.0");
assert.equal(e.period.users.find((u) => u.id === "u1".slice(0, 6)).ver, "2.7.0");
assert.equal(e.series.find((x) => x.day === "2026-11-12").list.find((u) => u.id === "u2").ver, "2.20.0");
assert.equal("verDay" in e.period.users[0], false);

// 6) 미측정(신호만 보낸) 사용자: u3 는 11/12 DAU 에는 있으나 방문·시간 기록 없음
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2000-01-01", uto: "2026-11-13" });
const d12u = e.series.find((x) => x.day === "2026-11-12");
assert.equal(d12u.unmeasured, 1);
assert.equal(d12u.users + d12u.unmeasured, d12u.dau); // 측정 + 미측정 = DAU
assert.deepEqual(d12u.unList, [{ id: "u3", p: "web", ver: "2.19.0", at: null, cd: 1, cm: 0, cv: 0, cs: 0 }]);
assert.equal(e.series.find((x) => x.day === "2026-11-13").unmeasured, 0);
assert.equal(e.period.unmeasured, 1);
assert.deepEqual(e.period.users.map((x) => [x.id, !!x.un]), [["u2", false], ["u1", false], ["u3", true]]);
assert.equal(e.period.users[2].visits, null);
assert.equal(e.period.total, 2); // 측정 접속자 수는 그대로
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", platform: "app" });
assert.equal(e.series.find((x) => x.day === "2026-11-12").unmeasured, 0); // 플랫폼 필터 반영

console.log("usage-lib engagement tests passed");

// 4) 접속자별 메뉴 이용 현황
{
  const { userSummary } = await import("./usage-lib.js");
  const u = userSummary({
    menuRows: [
      { day: "2026-11-12", menu: "뉴스 검색", hits: 3 },
      { day: "2026-11-13", menu: "뉴스 검색", hits: 2 },
      { day: "2026-11-13", menu: "법령 검색", hits: 5 },
    ],
    hourRows: [{ day: "2026-11-13", hour: 9, hits: 4 }, { day: "2026-11-13", hour: 99, hits: 1 }],
    dailyRows: [{ day: "2026-11-12", version: "web-2.23.4" }, { day: "2026-11-13", version: "web-2.23.4" }],
    activityRows: [{ day: "2026-11-13", visits: 2, secs: 300 }],
    to: "2026-11-14",
  });
  assert.equal(u.totalHits, 10);
  assert.deepEqual(u.menus.map((m) => [m.menu, m.hits, m.days, m.last]), [["뉴스 검색", 5, 2, "2026-11-13"], ["법령 검색", 5, 1, "2026-11-13"]]);
  assert.equal(u.platform, "web");
  assert.equal(u.version, "2.23.4");
  assert.equal(u.activeDays, 2);
  assert.equal(u.hours[9], 4);
  assert.equal(u.days.length, 14);
  assert.equal(u.days.find((d) => d.day === "2026-11-13").secs, 300);
  assert.equal(userSummary({ to: "2026-11-14" }).menus.length, 0);
}
console.log("userSummary OK");

// ── 접속자 태그: 검증 + 가짜 D1(node:sqlite)로 저장·수정·삭제·인증 확인 ──
{
  const { sanitizeTag, adminTag, adminApi } = await import("./usage-lib.js");
  const { DatabaseSync } = await import("node:sqlite");
  assert.equal(sanitizeTag({ id: "zz" }).error !== undefined, true);
  assert.equal(sanitizeTag({ id: "ABC123", tag: "  내   PC\n<b>", color: "nope" }).tag, "내 PC b");
  assert.equal(sanitizeTag({ id: "abc123", tag: "가".repeat(50) }).tag.length, 20);
  assert.equal(sanitizeTag({ id: "abc123", tag: "x", color: "red" }).color, "red");
  assert.equal(sanitizeTag({ id: "abc123", tag: "x", color: "red" }).id, "abc123");

  const sq = new DatabaseSync(":memory:");
  const stmt = (sql) => { let b = []; const o = {
    bind(...a) { b = a; return o; },
    async run() { sq.prepare(sql).run(...b); return {}; },
    async all() { return { results: sq.prepare(sql).all(...b) }; },
    async first() { return sq.prepare(sql).get(...b) ?? null; },
  }; o.sql = sql; return o; };
  const db = { prepare: stmt, async batch(list) { for (const s of list) await s.run(); } };
  const env = { ADMIN_TOKEN: "t0k", DB: db };
  const post = (body, tok = "t0k") => adminTag(new Request("https://x/admin/tag", { method: "POST", headers: { "X-Admin-Token": tok }, body: JSON.stringify(body) }), env);
  assert.equal((await post({ id: "abc123", tag: "내 PC" }, "bad")).status, 401);
  assert.equal((await post({ id: "nothex", tag: "x" })).status, 400);
  let r = await (await post({ id: "ABC123", tag: "내 PC", color: "blue" })).json();
  assert.deepEqual(r.tags, { abc123: { t: "내 PC", c: "blue" } });
  r = await (await post({ id: "abc123", tag: "팀장님", color: "red" })).json(); // 수정
  assert.deepEqual(r.tags, { abc123: { t: "팀장님", c: "red" } });
  const api = await (await adminApi(new Request("https://x/admin/api", { headers: { "X-Admin-Token": "t0k" } }), new URL("https://x/admin/api"), env)).json();
  assert.deepEqual(api.tags, { abc123: { t: "팀장님", c: "red" } });
  r = await (await post({ id: "abc123", tag: "  " })).json(); // 삭제
  assert.deepEqual(r.tags, {});
  console.log("tag tests OK");
}
